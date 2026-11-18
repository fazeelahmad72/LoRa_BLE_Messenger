package com.example.messaging_app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.databinding.FragmentNodesBinding
import kotlinx.coroutines.launch

class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        ChatViewModel.Factory(repo)
    }

    private val adapter = NodeAdapter { peer ->
        findNavController().navigate(
            R.id.action_nodes_to_detail,
            bundleOf("nodeId" to peer.id, "isLocal" to false)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.nodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.nodeList.adapter = adapter

        binding.localNodeCard.setOnClickListener {
            val device = viewModel.boundDevice.value
            findNavController().navigate(
                R.id.action_nodes_to_detail,
                bundleOf(
                    "nodeId" to (device?.address ?: "local"),
                    "isLocal" to true
                )
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.boundDevice.collect { device ->
                        binding.localNodeName.text = device?.name ?: getString(R.string.not_bound)
                    }
                }
                launch {
                    viewModel.connectionState.collect { state ->
                        val device = viewModel.boundDevice.value
                        val bleLabel = when (state) {
                            BridgeConnectionState.READY -> getString(R.string.node_ble_connected)
                            else -> getString(R.string.node_ble_disconnected)
                        }
                        binding.localNodeMeta.text = if (device == null) {
                            getString(R.string.not_bound)
                        } else {
                            "$bleLabel · ${device.address}"
                        }
                    }
                }
                launch {
                    viewModel.peers.collect { peers ->
                        adapter.submitList(peers)
                        binding.emptyNodes.isVisible = peers.isEmpty()
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
