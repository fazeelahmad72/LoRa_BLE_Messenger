package com.example.messaging_app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.data.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE client for T-Beam LoRa_BLE_Comm (Nordic UART Service).
 *
 * Firmware itself is fine with nRF Connect. This manager carefully serializes
 * GATT operations and ignores stale disconnect callbacks so reconnect loops
 * do not tear down a healthy link mid-discovery.
 */
class BleConnectionManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(BridgeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BridgeConnectionState> = _connectionState.asStateFlow()

    private val _boundAddress = MutableStateFlow<String?>(null)
    val boundAddress: StateFlow<String?> = _boundAddress.asStateFlow()

    private val _boundName = MutableStateFlow<String?>(null)
    val boundName: StateFlow<String?> = _boundName.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _incomingText = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingText: SharedFlow<String> = _incomingText.asSharedFlow()

    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var setupJob: Job? = null
    private var autoReconnect = true
    private var suppressDisconnectHandling = false
    private val sessionId = AtomicInteger(0)
    private var activeSession = 0
    private val discovered = ConcurrentHashMap<String, ScannedDevice>()
    private var scanPublishJob: Job? = null
    private var scanDirty = false

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 12_000L) {
        if (!BlePermissions.hasAll(appContext)) {
            _statusMessage.value = "Bluetooth permissions required"
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            _statusMessage.value = "Enable Bluetooth to scan"
            return
        }

        stopScan()
        discovered.clear()
        scanDirty = false
        scanPublishJob?.cancel()
        scanPublishJob = null
        _scannedDevices.value = emptyList()
        if (_connectionState.value == BridgeConnectionState.DISCONNECTED ||
            _connectionState.value == BridgeConnectionState.SCANNING
        ) {
            _connectionState.value = BridgeConnectionState.SCANNING
        }
        _statusMessage.value = "Scanning for T-Beam devices…"
        startScanPublisher()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (t: Throwable) {
            Log.e(TAG, "BLE scan failed to start", t)
            _statusMessage.value = "Scan failed to start"
            return
        }

        scanJob = scope.launch {
            delay(durationMs)
            stopScan()
            if (_connectionState.value == BridgeConnectionState.SCANNING) {
                _connectionState.value = BridgeConnectionState.DISCONNECTED
                _statusMessage.value = if (_scannedDevices.value.isEmpty()) {
                    "No T-Beam found. Disconnect nRF Connect and keep the board nearby."
                } else {
                    "Scan complete"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanPublishJob?.cancel()
        scanPublishJob = null
        // Flush any pending discoveries once so the final list is visible.
        if (scanDirty) {
            publishScannedDevices()
            scanDirty = false
        }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun startScanPublisher() {
        scanPublishJob?.cancel()
        scanPublishJob = scope.launch {
            while (true) {
                delay(SCAN_UI_THROTTLE_MS)
                if (scanDirty) {
                    publishScannedDevices()
                    scanDirty = false
                }
            }
        }
    }

    private fun publishScannedDevices() {
        // Keep first-seen order so RSSI jitter does not reshuffle rows.
        _scannedDevices.value = discovered.values.sortedWith(
            compareBy<ScannedDevice> { it.firstSeen }.thenBy { it.address }
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String) {
        if (!BlePermissions.hasAll(appContext)) {
            _statusMessage.value = "Bluetooth permissions required"
            return
        }
        if (adapter?.isEnabled != true) {
            _statusMessage.value = "Bluetooth unavailable"
            return
        }

        stopScan()
        reconnectJob?.cancel()
        connectJob?.cancel()
        setupJob?.cancel()
        autoReconnect = true

        _boundAddress.value = address
        _boundName.value = name.ifBlank { "T-Beam" }
        _connectionState.value = BridgeConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${name.ifBlank { address }}…"

        val newSession = sessionId.incrementAndGet()
        activeSession = newSession

        connectJob = scope.launch {
            // Close any previous GATT and wait so its disconnect callback is ignored.
            closeGattInternal(suppressCallback = true)
            delay(600)

            if (activeSession != newSession) return@launch

            val device = adapter?.getRemoteDevice(address) ?: run {
                _statusMessage.value = "Invalid device address"
                _connectionState.value = BridgeConnectionState.DISCONNECTED
                return@launch
            }

            Log.i(TAG, "connectGatt session=$newSession address=$address")
            gatt = connectGattInternal(device)
            if (gatt == null) {
                _statusMessage.value = "connectGatt returned null"
                _connectionState.value = BridgeConnectionState.DISCONNECTED
            }
        }
    }

    fun disconnect(clearBinding: Boolean = false) {
        autoReconnect = false
        reconnectJob?.cancel()
        connectJob?.cancel()
        setupJob?.cancel()
        stopScan()
        activeSession = sessionId.incrementAndGet()
        closeGattInternal(suppressCallback = true)
        if (clearBinding) {
            _boundAddress.value = null
            _boundName.value = null
        }
        _connectionState.value = BridgeConnectionState.DISCONNECTED
        _statusMessage.value = if (clearBinding) "Device unbound" else "Disconnected"
    }

    @SuppressLint("MissingPermission")
    fun sendText(text: String): Boolean {
        val characteristic = rxCharacteristic
        val activeGatt = gatt
        val state = _connectionState.value
        if (characteristic == null || activeGatt == null || state != BridgeConnectionState.READY) {
            Log.w(
                TAG,
                "send blocked state=$state gatt=${activeGatt != null} rx=${characteristic != null}"
            )
            _statusMessage.value = "LoRa bridge not ready — wait until status is green"
            return false
        }

        val bytes = text.toByteArray(Charset.forName("US-ASCII"))
        // Firmware accepts WRITE and WRITE_NO_RSP. Prefer no-response like nRF "without response".
        return writeRx(activeGatt, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ||
            writeRx(activeGatt, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun writeRx(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = activeGatt.writeCharacteristic(characteristic, bytes, writeType)
                Log.i(TAG, "writeCharacteristic type=$writeType code=$result")
                result == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                val ok = activeGatt.writeCharacteristic(characteristic)
                Log.i(TAG, "writeCharacteristic(legacy) type=$writeType ok=$ok")
                ok
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Write failed", t)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattInternal(device: BluetoothDevice): BluetoothGatt? {
        return try {
            // minSdk 26 — use Handler so all GATT callbacks stay on main thread.
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                mainHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt threw", t)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattInternal(suppressCallback: Boolean) {
        suppressDisconnectHandling = suppressCallback
        setupJob?.cancel()
        rxCharacteristic = null
        txCharacteristic = null
        val active = gatt
        gatt = null
        if (active != null) {
            runCatching { active.disconnect() }
            // close() after a short delay so disconnect can complete; still safe if already closed.
            mainHandler.postDelayed({
                runCatching { active.close() }
                suppressDisconnectHandling = false
            }, 400)
        } else {
            suppressDisconnectHandling = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            ingestScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { ingestScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed errorCode=$errorCode")
            _statusMessage.value = "Scan failed ($errorCode)"
            if (_connectionState.value == BridgeConnectionState.SCANNING) {
                _connectionState.value = BridgeConnectionState.DISCONNECTED
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ingestScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val rawName = listOfNotNull(
            result.scanRecord?.deviceName,
            runCatching { device.name }.getOrNull()
        ).firstOrNull { !it.isNullOrBlank() }

        if (!looksLikeTBeam(rawName, result)) return

        val name = rawName?.takeIf { it.isNotBlank() } ?: "T-Beam"
        val existing = discovered[address]
        val next = if (existing == null) {
            ScannedDevice(
                address = address,
                name = name,
                rssi = result.rssi,
                firstSeen = System.currentTimeMillis()
            )
        } else {
            existing.copy(
                name = if (name != "T-Beam") name else existing.name,
                rssi = result.rssi
            )
        }

        // Skip churn when nothing meaningful changed (common with rapid ads).
        if (existing != null &&
            existing.name == next.name &&
            kotlin.math.abs(existing.rssi - next.rssi) < 3
        ) {
            return
        }

        discovered[address] = next
        scanDirty = true
        // First device should appear quickly without waiting a full throttle window.
        if (existing == null && _scannedDevices.value.none { it.address == address }) {
            publishScannedDevices()
            scanDirty = false
        }
    }

    private fun looksLikeTBeam(name: String?, result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
        if (uuids.any { it.uuid == NusUuids.SERVICE }) return true
        val resolved = name?.trim().orEmpty()
        if (resolved.isEmpty()) return false
        return NusUuids.KNOWN_DEVICE_NAME_PREFIXES.any { prefix ->
            resolved.contains(prefix, ignoreCase = true)
        }
    }

    private fun isCurrentGatt(callbackGatt: BluetoothGatt): Boolean {
        return callbackGatt === gatt
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState session=$activeSession")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _statusMessage.value = "BLE connect failed ($status)"
                    return
                }
                if (!isCurrentGatt(gatt) && gatt != this@BleConnectionManager.gatt) {
                    // Accept first callback assignment.
                }
                this@BleConnectionManager.gatt = gatt
                _connectionState.value = BridgeConnectionState.DISCOVERING
                _statusMessage.value = "Discovering Nordic UART…"
                startDiscoveryPipeline(gatt)
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (suppressDisconnectHandling || !isCurrentGatt(gatt)) {
                    Log.i(TAG, "Ignoring stale/intentional disconnect")
                    runCatching { gatt.close() }
                    return
                }

                setupJob?.cancel()
                rxCharacteristic = null
                txCharacteristic = null
                runCatching { gatt.close() }
                if (this@BleConnectionManager.gatt === gatt) {
                    this@BleConnectionManager.gatt = null
                }

                if (autoReconnect && _boundAddress.value != null) {
                    _connectionState.value = BridgeConnectionState.RECONNECTING
                    _statusMessage.value = "Link lost — reconnecting…"
                    scheduleReconnect()
                } else {
                    _connectionState.value = BridgeConnectionState.DISCONNECTED
                    _statusMessage.value = "Disconnected"
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) {
                Log.w(TAG, "Ignoring services from stale GATT")
                return
            }
            Log.i(TAG, "onServicesDiscovered status=$status count=${gatt.services?.size}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "Service discovery failed ($status)"
                return
            }

            // Prefer exact UUID; also scan list in case of cache quirks.
            val service = gatt.getService(NusUuids.SERVICE)
                ?: gatt.services?.firstOrNull {
                    it.uuid.toString().equals(NusUuids.SERVICE.toString(), ignoreCase = true)
                }

            if (service == null) {
                val found = gatt.services?.joinToString { it.uuid.toString() }.orEmpty()
                Log.e(TAG, "NUS missing. Services: $found")
                _statusMessage.value = "Nordic UART not found — refresh and reconnect"
                // Clear Android GATT cache then retry once.
                refreshDeviceCache(gatt)
                setupJob?.cancel()
                setupJob = scope.launch {
                    delay(400)
                    if (isCurrentGatt(gatt)) {
                        gatt.discoverServices()
                    }
                }
                return
            }

            rxCharacteristic = service.getCharacteristic(NusUuids.RX_WRITE)
                ?: service.characteristics?.firstOrNull {
                    it.uuid.toString().equals(NusUuids.RX_WRITE.toString(), ignoreCase = true)
                }
            txCharacteristic = service.getCharacteristic(NusUuids.TX_NOTIFY)
                ?: service.characteristics?.firstOrNull {
                    it.uuid.toString().equals(NusUuids.TX_NOTIFY.toString(), ignoreCase = true)
                }

            if (rxCharacteristic == null || txCharacteristic == null) {
                _statusMessage.value = "UART characteristics missing"
                Log.e(TAG, "chars=${service.characteristics?.joinToString { it.uuid.toString() }}")
                return
            }

            enableNotifications(gatt, txCharacteristic!!)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            Log.i(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
            if (descriptor.uuid != NusUuids.CCCD) return
            // Ready to chat even if notify enable returned a non-success code.
            markReady()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!isCurrentGatt(gatt)) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncoming(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentGatt(gatt)) return
            handleIncoming(value)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            // Firmware already requests MTU on connect; we do not depend on this for READY.
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.i(TAG, "onCharacteristicWrite status=$status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryPipeline(gatt: BluetoothGatt) {
        setupJob?.cancel()
        setupJob = scope.launch {
            delay(300)
            if (!isCurrentGatt(gatt)) return@launch
            refreshDeviceCache(gatt)
            delay(200)
            if (!isCurrentGatt(gatt)) return@launch
            val ok = gatt.discoverServices()
            Log.i(TAG, "discoverServices started=$ok")
            if (!ok) {
                _statusMessage.value = "Could not start service discovery"
            }

            delay(8_000)
            if (_connectionState.value == BridgeConnectionState.DISCOVERING && isCurrentGatt(gatt)) {
                Log.w(TAG, "Discovery still pending — retry")
                _statusMessage.value = "Retrying service discovery…"
                refreshDeviceCache(gatt)
                delay(200)
                gatt.discoverServices()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, tx: BluetoothGattCharacteristic) {
        _statusMessage.value = "Enabling LoRa notifications…"
        val notifyOk = gatt.setCharacteristicNotification(tx, true)
        Log.i(TAG, "setCharacteristicNotification=$notifyOk")

        val cccd = tx.getDescriptor(NusUuids.CCCD)
            ?: tx.descriptors?.firstOrNull {
                it.uuid.toString().equals(NusUuids.CCCD.toString(), ignoreCase = true)
            }

        if (cccd == null) {
            Log.w(TAG, "No CCCD — marking ready (send will still work)")
            markReady()
            return
        }

        setupJob?.cancel()
        setupJob = scope.launch {
            delay(200)
            if (!isCurrentGatt(gatt)) return@launch
            val wrote = writeCccd(gatt, cccd)
            if (!wrote) {
                Log.w(TAG, "CCCD write false — marking ready anyway")
                markReady()
                return@launch
            }
            // Safety: if descriptor callback never arrives, still become ready.
            delay(2_000)
            if (_connectionState.value == BridgeConnectionState.DISCOVERING && isCurrentGatt(gatt)) {
                Log.w(TAG, "CCCD callback timeout — marking ready")
                markReady()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                Log.i(TAG, "writeDescriptor code=$code")
                code == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "CCCD write threw", t)
            false
        }
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt) {
        try {
            val method = gatt.javaClass.getMethod("refresh")
            val result = method.invoke(gatt) as? Boolean
            Log.i(TAG, "gatt.refresh()=$result")
        } catch (t: Throwable) {
            Log.d(TAG, "gatt.refresh() unavailable: ${t.message}")
        }
    }

    private fun handleIncoming(value: ByteArray) {
        if (value.isEmpty()) return
        // Drop binary UART leftovers before they become fake chat text.
        val nonPrintable = value.count { byte ->
            val u = byte.toInt() and 0xFF
            u != 9 && u != 10 && u != 13 && (u < 32 || u > 126)
        }
        if (nonPrintable.toFloat() / value.size > 0.25f) {
            Log.d(TAG, "Ignoring binary BLE payload ($nonPrintable/${value.size} non-printable)")
            return
        }
        val text = value.toString(Charset.forName("US-ASCII")).trimEnd('\u0000', '\r', '\n')
        if (text.isNotEmpty()) {
            _incomingText.tryEmit(text)
        }
    }

    private fun markReady() {
        if (rxCharacteristic == null || gatt == null) {
            _statusMessage.value = "UART write characteristic missing"
            return
        }
        setupJob?.cancel()
        _connectionState.value = BridgeConnectionState.READY
        _statusMessage.value = "Bound to ${_boundName.value ?: "T-Beam"} — LoRa ready"
        Log.i(TAG, "Bridge READY")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2_500)
            val address = _boundAddress.value ?: return@launch
            val name = _boundName.value ?: "T-Beam"
            if (autoReconnect &&
                (_connectionState.value == BridgeConnectionState.RECONNECTING ||
                    _connectionState.value == BridgeConnectionState.DISCONNECTED)
            ) {
                connect(address, name)
            }
        }
    }

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val SCAN_UI_THROTTLE_MS = 700L
    }
}
