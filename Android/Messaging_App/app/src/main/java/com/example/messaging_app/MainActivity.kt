package com.example.messaging_app

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.messaging_app.ble.BlePermissions
import com.example.messaging_app.ble.LoRaBridgeService
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.databinding.ActivityMainBinding
import com.example.messaging_app.ui.MainActivityHost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MainActivityHost {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var pendingBleAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            pendingBleAction?.invoke()
        } else {
            Snackbar.make(binding.root, R.string.permissions_rationale, Snackbar.LENGTH_LONG).show()
        }
        pendingBleAction = null
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingBleAction?.invoke()
        pendingBleAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, 0, bars.right, bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.HomeFragment,
                R.id.ChatListFragment,
                R.id.MapFragment,
                R.id.NodesFragment,
                R.id.SettingsFragment
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val topLevel = appBarConfiguration.topLevelDestinations.contains(destination.id)
            binding.bottomNav.visibility = if (topLevel) View.VISIBLE else View.GONE
            // Keep global bridge status on primary destinations; hide on dense sub-screens.
            binding.statusBar.visibility = when (destination.id) {
                R.id.ChatFragment, R.id.NodeDetailFragment, R.id.DevicesFragment, R.id.ProfileFragment ->
                    View.GONE
                else -> View.VISIBLE
            }
        }

        observeBridgeStatus()
        ensureBleReady {
            startBridgeServiceIfBound()
        }
    }

    private fun observeBridgeStatus() {
        val repo = (application as MessagingApplication).container.chatRepository
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.statusMessage.collect { status ->
                        binding.statusText.text = status
                    }
                }
                launch {
                    repo.connectionState.collect { state ->
                        val colorRes = when (state) {
                            BridgeConnectionState.READY -> R.color.lora_status_ready
                            BridgeConnectionState.CONNECTING,
                            BridgeConnectionState.DISCOVERING,
                            BridgeConnectionState.RECONNECTING,
                            BridgeConnectionState.SCANNING -> R.color.lora_status_busy
                            BridgeConnectionState.DISCONNECTED -> R.color.lora_status_off
                        }
                        val drawable = binding.statusDot.background as? GradientDrawable
                            ?: GradientDrawable().also { binding.statusDot.background = it }
                        drawable.shape = GradientDrawable.OVAL
                        drawable.setColor(ContextCompat.getColor(this@MainActivity, colorRes))

                        if (state == BridgeConnectionState.READY) {
                            startBridgeServiceIfBound()
                        }
                    }
                }
            }
        }
    }

    override fun ensureBleReady(onReady: () -> Unit) {
        if (!BlePermissions.hasAll(this)) {
            pendingBleAction = onReady
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.permissions_rationale)
                .setPositiveButton(R.string.grant_permissions) { _, _ ->
                    permissionLauncher.launch(BlePermissions.requiredRuntimePermissions())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            pendingBleAction = onReady
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        onReady()
    }

    private fun startBridgeServiceIfBound() {
        val bound = (application as MessagingApplication).container.chatRepository.boundDevice.value
        if (bound == null) return
        val intent = Intent(this, LoRaBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
