package com.example.messaging_app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.databinding.FragmentNodeDetailBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NodeDetailFragment : Fragment() {

    private var _binding: FragmentNodeDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        ChatViewModel.Factory(repo)
    }

    private val nodeId: String by lazy { requireArguments().getString("nodeId").orEmpty() }
    private val isLocal: Boolean by lazy { requireArguments().getBoolean("isLocal", false) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.openChannelButton.setOnClickListener {
            findNavController().navigate(R.id.action_node_detail_to_chat)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (isLocal) {
                    combine(viewModel.boundDevice, viewModel.connectionState) { device, state ->
                        device to state
                    }.collect { (device, state) ->
                        binding.detailBadge.text = getString(R.string.local_bridge_label)
                        binding.detailName.text = device?.name ?: getString(R.string.not_bound)
                        binding.detailId.text = device?.address ?: "—"
                        binding.detailStatus.text = when (state) {
                            BridgeConnectionState.READY -> getString(R.string.node_ble_connected)
                            else -> getString(R.string.node_ble_disconnected)
                        }
                        binding.detailGps.text =
                            "${getString(R.string.node_gps)}: ${getString(R.string.value_unavailable)}"
                        binding.detailBattery.text =
                            "${getString(R.string.node_battery)}: ${getString(R.string.value_unknown)}"
                        binding.detailRssi.text =
                            "${getString(R.string.node_rssi)}: ${getString(R.string.value_unavailable)}"
                        binding.detailLastMessage.text =
                            "${getString(R.string.node_last_message)}: ${getString(R.string.value_none)}"
                    }
                } else {
                    viewModel.peers.collect { peers ->
                        val peer = peers.find { it.id == nodeId }
                        binding.detailBadge.text = getString(R.string.remote_node_label)
                        binding.detailName.text = peer?.displayName ?: nodeId
                        binding.detailId.text = peer?.id ?: nodeId
                        binding.detailStatus.text = peer?.lastSeen?.let {
                            getString(R.string.node_lora_seen) + " · " +
                                DateUtils.getRelativeTimeSpanString(
                                    it,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                )
                        } ?: getString(R.string.value_unknown)
                        binding.detailGps.text =
                            "${getString(R.string.node_gps)}: ${getString(R.string.value_unavailable)}"
                        binding.detailBattery.text =
                            "${getString(R.string.node_battery)}: ${getString(R.string.value_unknown)}"
                        binding.detailRssi.text =
                            "${getString(R.string.node_rssi)}: ${getString(R.string.value_unavailable)}"
                        val preview = peer?.lastMessagePreview?.ifBlank { null }
                            ?: getString(R.string.value_none)
                        binding.detailLastMessage.text =
                            "${getString(R.string.node_last_message)}: $preview"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
