package com.example.messaging_app.data

import com.example.messaging_app.ble.BleConnectionManager
import com.example.messaging_app.protocol.LoRaChatProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ChatRepository(
    private val preferences: UserPreferences,
    private val messageStore: MessageStore,
    private val ble: BleConnectionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val messages: StateFlow<List<ChatMessage>> = messageStore.messages
    val peers: StateFlow<List<PeerContact>> = messageStore.peers
    val connectionState = ble.connectionState
    val statusMessage = ble.statusMessage
    val scannedDevices = ble.scannedDevices
    val boundAddress = ble.boundAddress
    val boundName = ble.boundName

    private val _gpsFix = MutableStateFlow(GpsFix())
    val gpsFix: StateFlow<GpsFix> = _gpsFix.asStateFlow()

    private val _loraRadio = MutableStateFlow(LoraRadioConfig())
    val loraRadio: StateFlow<LoraRadioConfig> = _loraRadio.asStateFlow()

    val profile: StateFlow<UserProfile> = preferences.profile
        .stateIn(scope, SharingStarted.Eagerly, UserProfile("", "Operator"))

    val boundDevice: StateFlow<BoundDevice?> = preferences.boundDevice
        .stateIn(scope, SharingStarted.Eagerly, null)

    val uiReady = combine(connectionState, boundDevice) { state, device ->
        state to device
    }.stateIn(scope, SharingStarted.Eagerly, BridgeConnectionState.DISCONNECTED to null)

    @Volatile
    private var bindingInProgress = false

    init {
        scope.launch {
            preferences.ensureUserId()
            // Auto-reconnect only on cold start / preference restore — never while a
            // manual bind/connect is already running (that caused GATT teardown races).
            preferences.boundDevice.collect { device ->
                if (bindingInProgress) return@collect
                if (device != null &&
                    ble.boundAddress.value != device.address &&
                    ble.connectionState.value == BridgeConnectionState.DISCONNECTED
                ) {
                    ble.connect(device.address, device.name)
                }
            }
        }
        scope.launch {
            ble.incomingText.collect { raw -> handleIncoming(raw) }
        }
        scope.launch {
            var wasReady = false
            ble.connectionState.collect { state ->
                if (state == BridgeConnectionState.READY && !wasReady) {
                    wasReady = true
                    announcePresence()
                } else if (state != BridgeConnectionState.READY) {
                    wasReady = false
                    _gpsFix.value = GpsFix()
                }
            }
        }
    }

    fun startScan() = ble.startScan()

    fun stopScan() = ble.stopScan()

    suspend fun bindDevice(device: BoundDevice) {
        bindingInProgress = true
        try {
            preferences.bindDevice(device)
            ble.connect(device.address, device.name)
        } finally {
            bindingInProgress = false
        }
    }

    suspend fun unbindDevice() {
        preferences.clearBoundDevice()
        ble.disconnect(clearBinding = true)
    }

    suspend fun setDisplayName(name: String) {
        preferences.setDisplayName(name)
        if (ble.connectionState.value == BridgeConnectionState.READY) {
            announcePresence()
        }
    }

    fun reconnectBound() {
        val device = boundDevice.value ?: return
        ble.connect(device.address, device.name)
    }

    fun disconnectLink() {
        ble.disconnect(clearBinding = false)
    }

    fun clearChat() {
        messageStore.clearAllChat()
    }

    /**
     * Broadcast a presence ping over LoRa so peer nodes can rediscover this phone.
     * Peers appear when they reply with PRES/CHAT; this does not invent contacts.
     */
    suspend fun refreshPeers(): PeerRefreshResult {
        return when (ble.connectionState.value) {
            BridgeConnectionState.READY -> {
                announcePresence()
                PeerRefreshResult.ANNOUNCED
            }
            BridgeConnectionState.DISCONNECTED -> PeerRefreshResult.BRIDGE_OFFLINE
            else -> PeerRefreshResult.BRIDGE_BUSY
        }
    }

    suspend fun sendMessage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val profile = preferences.profile.first()
        val packet = LoRaChatProtocol.encodeChat(profile.userId, trimmed)
        val messageId = UUID.randomUUID().toString()
        val conversationId = CHANNEL_ID

        val local = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            senderId = profile.userId,
            senderName = profile.displayName,
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            status = MessageStatus.SENDING
        )
        messageStore.upsertMessage(local)

        val ok = ble.sendText(packet)
        messageStore.updateStatus(
            messageId,
            if (ok) MessageStatus.SENT else MessageStatus.FAILED
        )
        return ok
    }

    fun messagesForChannel(): List<ChatMessage> = messageStore.messagesFor(CHANNEL_ID)

    private suspend fun announcePresence() {
        val profile = preferences.profile.first()
        if (profile.userId.isBlank()) return
        val packet = LoRaChatProtocol.encodePresence(profile.userId, profile.displayName)
        ble.sendText(packet)
    }

    private suspend fun handleIncoming(raw: String) {
        when (val packet = LoRaChatProtocol.parse(raw)) {
            is LoRaChatProtocol.Packet.Chat -> {
                val myId = preferences.profile.first().userId
                if (packet.from == myId) return
                if (!LoRaChatProtocol.isReadableChatText(packet.text)) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = CHANNEL_ID,
                    senderId = packet.from,
                    senderName = resolveName(packet.from),
                    text = packet.text,
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    status = MessageStatus.RECEIVED
                )
                messageStore.upsertMessage(message)
                messageStore.upsertPeer(
                    PeerContact(
                        id = packet.from,
                        displayName = resolveName(packet.from),
                        lastSeen = System.currentTimeMillis(),
                        lastMessagePreview = packet.text,
                        unreadCount = 1
                    )
                )
            }

            is LoRaChatProtocol.Packet.Presence -> {
                val myId = preferences.profile.first().userId
                if (packet.from == myId) return
                messageStore.upsertPeer(
                    PeerContact(
                        id = packet.from,
                        displayName = packet.displayName.ifBlank { packet.from },
                        lastSeen = System.currentTimeMillis()
                    )
                )
            }

            is LoRaChatProtocol.Packet.Ack -> {
                // Reserved for future delivery receipts.
            }

            is LoRaChatProtocol.Packet.Gps -> {
                _gpsFix.value = GpsFix(
                    hasFix = packet.hasFix,
                    latitude = packet.latitude,
                    longitude = packet.longitude,
                    satellites = packet.satellites,
                    speedKmh = packet.speedKmh,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }

            is LoRaChatProtocol.Packet.Radio -> {
                _loraRadio.value = LoraRadioConfig(
                    frequencyHz = packet.frequencyHz,
                    spreadingFactor = packet.spreadingFactor,
                    bandwidthKhz = packet.bandwidthKhz,
                    codingRate = packet.codingRate,
                    txPowerDbm = packet.txPowerDbm,
                    fromFirmware = true,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }

            is LoRaChatProtocol.Packet.Ignored -> {
                // GPS / RADIO / NMEA telemetry — never show in chat.
            }

            is LoRaChatProtocol.Packet.Plain -> {
                if (packet.text.isBlank()) return
                // Safety net: never dump GPS/radio/binary noise into the conversation.
                if (LoRaChatProtocol.isTelemetryNoise(packet.text)) return
                if (!LoRaChatProtocol.isReadableChatText(packet.text)) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = CHANNEL_ID,
                    senderId = "remote",
                    senderName = "LoRa Peer",
                    text = packet.text,
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    status = MessageStatus.RECEIVED
                )
                messageStore.upsertMessage(message)
            }
        }
    }

    private fun resolveName(peerId: String): String {
        return messageStore.peers.value.find { it.id == peerId }?.displayName
            ?: "Node $peerId"
    }

    companion object {
        const val CHANNEL_ID = "lora_channel"
    }
}

enum class PeerRefreshResult {
    ANNOUNCED,
    BRIDGE_OFFLINE,
    BRIDGE_BUSY
}
