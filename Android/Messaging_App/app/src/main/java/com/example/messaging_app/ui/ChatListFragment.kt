package com.example.messaging_app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.messaging_app.data.PeerRefreshResult
import com.example.messaging_app.databinding.FragmentChatListBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        ChatViewModel.Factory(repo)
    }

    private val adapter = ChatPeerAdapter {
        findNavController().navigate(R.id.action_chat_list_to_chat)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.peerList.layoutManager = LinearLayoutManager(requireContext())
        binding.peerList.adapter = adapter
        binding.channelCard.setOnClickListener {
            findNavController().navigate(R.id.action_chat_list_to_chat)
        }
        binding.refreshPeersButton.setOnClickListener { scanForPeers() }
        binding.peersRefresh.setOnRefreshListener { scanForPeers() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        val preview = messages.lastOrNull()?.let { msg ->
                            val who = if (msg.isMine) "You" else msg.senderName
                            "$who: ${msg.text}"
                        } ?: getString(R.string.chat_list_channel_subtitle)
                        binding.channelPreview.text = preview
                    }
                }
                launch {
                    viewModel.peers.collect { peers ->
                        adapter.submitList(peers)
                        binding.emptyPeers.isVisible = peers.isEmpty()
                    }
                }
            }
        }
    }

    private fun scanForPeers() {
        binding.refreshPeersButton.isEnabled = false
        binding.peersRefresh.isRefreshing = true
        viewModel.refreshPeers { result ->
            if (_binding == null) return@refreshPeers
            binding.refreshPeersButton.isEnabled = true
            binding.peersRefresh.isRefreshing = false
            val message = when (result) {
                PeerRefreshResult.ANNOUNCED -> getString(R.string.chat_refresh_peers_ok)
                PeerRefreshResult.BRIDGE_OFFLINE -> getString(R.string.chat_refresh_peers_offline)
                PeerRefreshResult.BRIDGE_BUSY -> getString(R.string.chat_refresh_peers_busy)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
