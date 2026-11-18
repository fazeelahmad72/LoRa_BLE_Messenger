package com.example.messaging_app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        HomeViewModel.Factory(repo)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindStatusCard(binding.cardBle.root, R.drawable.ic_bluetooth, getString(R.string.home_status_ble))
        bindStatusCard(binding.cardLora.root, R.drawable.ic_lora_signal, getString(R.string.home_status_lora))
        bindStatusCard(binding.cardGps.root, R.drawable.ic_gps, getString(R.string.home_status_gps))
        bindStatusCard(binding.cardNodes.root, R.drawable.ic_nav_nodes, getString(R.string.home_status_nodes))
        bindStatusCard(binding.cardBattery.root, R.drawable.ic_battery, getString(R.string.home_status_battery))
        bindStatusCard(binding.cardActivity.root, R.drawable.ic_nav_chats, getString(R.string.home_status_activity))

        binding.secondaryBridgeButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_devices)
        }
        binding.openChatButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_chat)
        }
        binding.primaryBridgeButton.setOnClickListener {
            val state = viewModel.dashboard.value
            when {
                state.deviceAddress == null -> findNavController().navigate(R.id.action_home_to_devices)
                state.connectionState == BridgeConnectionState.READY ||
                    state.connectionState == BridgeConnectionState.CONNECTING ||
                    state.connectionState == BridgeConnectionState.DISCOVERING ||
                    state.connectionState == BridgeConnectionState.RECONNECTING -> {
                    viewModel.disconnect()
                }
                else -> {
                    (activity as? MainActivityHost)?.ensureBleReady {
                        viewModel.reconnect()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dashboard.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: HomeDashboardState) {
        val hasDevice = !state.deviceAddress.isNullOrBlank()
        binding.connectedDeviceName.text = if (hasDevice) {
            state.deviceName.ifBlank { getString(R.string.not_bound) }
        } else {
            getString(R.string.not_bound)
        }
        binding.connectionDetail.text = state.statusMessage

        binding.primaryBridgeButton.text = when {
            !hasDevice -> getString(R.string.home_scan_bind)
            state.connectionState == BridgeConnectionState.READY ||
                state.connectionState == BridgeConnectionState.CONNECTING ||
                state.connectionState == BridgeConnectionState.DISCOVERING ||
                state.connectionState == BridgeConnectionState.RECONNECTING ->
                getString(R.string.home_disconnect)
            else -> getString(R.string.home_connect)
        }

        setStatusValue(
            binding.cardBle.root,
            when (state.connectionState) {
                BridgeConnectionState.READY -> getString(R.string.value_ready)
                BridgeConnectionState.DISCONNECTED -> getString(R.string.value_offline)
                BridgeConnectionState.SCANNING -> getString(R.string.scanning)
                BridgeConnectionState.CONNECTING,
                BridgeConnectionState.DISCOVERING,
                BridgeConnectionState.RECONNECTING -> getString(R.string.status_connecting)
            }
        )
        setStatusValue(
            binding.cardLora.root,
            if (state.connectionState == BridgeConnectionState.READY) {
                getString(R.string.status_ready)
            } else {
                getString(R.string.value_offline)
            }
        )
        setStatusValue(
            binding.cardGps.root,
            when {
                state.connectionState != BridgeConnectionState.READY ->
                    getString(R.string.value_offline)
                state.gpsFix.updatedAtMillis == 0L ->
                    getString(R.string.value_waiting)
                state.gpsFix.hasFix ->
                    getString(R.string.map_fix_ok)
                else ->
                    getString(R.string.map_fix_searching)
            }
        )
        setStatusValue(binding.cardNodes.root, state.peerCount.toString())
        setStatusValue(binding.cardBattery.root, getString(R.string.value_unknown))
        setStatusValue(
            binding.cardActivity.root,
            state.lastActivityMillis?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } ?: getString(R.string.last_activity_never)
        )

        binding.recentMessagesText.text = if (state.recentMessages.isEmpty()) {
            getString(R.string.home_no_recent)
        } else {
            state.recentMessages.joinToString("\n") { msg ->
                val who = if (msg.isMine) "You" else msg.senderName
                "$who: ${msg.text}"
            }
        }
    }

    private fun bindStatusCard(card: View, iconRes: Int, label: String) {
        card.findViewById<ImageView>(R.id.statusIcon).setImageResource(iconRes)
        card.findViewById<TextView>(R.id.statusLabel).text = label
    }

    private fun setStatusValue(card: View, value: String) {
        card.findViewById<TextView>(R.id.statusValue).text = value
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
