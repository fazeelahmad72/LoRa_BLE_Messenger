package com.example.messaging_app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import com.example.messaging_app.databinding.FragmentChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        ChatViewModel.Factory(repo)
    }

    private val adapter = MessageAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.messageList.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter

        binding.bindCta.setOnClickListener {
            findNavController().navigate(R.id.action_chat_to_devices)
        }
        binding.clearChatButton.setOnClickListener { confirmClearChat() }
        binding.sendButton.setOnClickListener { sendCurrentMessage() }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }
        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.root.post { scrollMessagesToEnd() }
                binding.root.postDelayed({ scrollMessagesToEnd() }, 180)
            }
        }
        binding.messageInput.setOnClickListener {
            binding.root.post { scrollMessagesToEnd() }
            binding.root.postDelayed({ scrollMessagesToEnd() }, 180)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        adapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                binding.messageList.scrollToPosition(messages.lastIndex)
                            }
                        }
                        binding.emptyState.isVisible = messages.isEmpty()
                        binding.messageList.isVisible = messages.isNotEmpty()
                        binding.clearChatButton.isEnabled = messages.isNotEmpty()
                        binding.clearChatButton.alpha = if (messages.isNotEmpty()) 1f else 0.45f
                    }
                }
                launch {
                    viewModel.channelMeta.collect { meta ->
                        binding.channelMeta.text = meta
                    }
                }
            }
        }
    }

    private fun confirmClearChat() {
        if (adapter.currentList.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_chat_title)
            .setMessage(R.string.clear_chat_message)
            .setPositiveButton(R.string.clear_chat_confirm) { _, _ ->
                viewModel.clearChat()
                Snackbar.make(binding.root, R.string.chat_cleared, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun scrollMessagesToEnd() {
        val last = adapter.itemCount - 1
        if (last >= 0) {
            binding.messageList.scrollToPosition(last)
        }
    }

    private fun sendCurrentMessage() {
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        binding.messageInput.setText("")
        viewModel.send(text) { ok ->
            if (!ok) {
                Snackbar.make(binding.root, R.string.message_failed, Snackbar.LENGTH_SHORT).show()
            }
            scrollMessagesToEnd()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
