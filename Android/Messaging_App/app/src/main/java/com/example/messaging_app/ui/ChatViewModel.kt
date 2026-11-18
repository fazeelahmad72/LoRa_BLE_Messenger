package com.example.messaging_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.data.ChatMessage
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.PeerContact
import com.example.messaging_app.data.PeerRefreshResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = repository.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<PeerContact>> = repository.peers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState = repository.connectionState
    val statusMessage = repository.statusMessage
    val boundDevice = repository.boundDevice

    val channelMeta: StateFlow<String> = combine(peers, boundDevice, connectionState) { peerList, device, state ->
        val peerCount = peerList.size
        val bridge = when {
            state == BridgeConnectionState.READY && device != null -> "via ${device.name}"
            device != null -> "bound ${device.name}"
            else -> "no bridge"
        }
        "Peers seen: $peerCount · $bridge"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Peers seen: 0")

    fun send(text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(repository.sendMessage(text))
        }
    }

    fun reconnect() = repository.reconnectBound()

    fun disconnect() = repository.disconnectLink()

    fun clearChat() = repository.clearChat()

    fun refreshPeers(onResult: (PeerRefreshResult) -> Unit) {
        viewModelScope.launch {
            onResult(repository.refreshPeers())
        }
    }

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository) as T
        }
    }
}
