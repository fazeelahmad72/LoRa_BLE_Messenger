package com.example.messaging_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.data.ChatMessage
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.GpsFix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeDashboardState(
    val deviceName: String,
    val deviceAddress: String?,
    val connectionState: BridgeConnectionState,
    val statusMessage: String,
    val peerCount: Int,
    val recentMessages: List<ChatMessage>,
    val lastActivityMillis: Long?,
    val gpsFix: GpsFix = GpsFix()
)

class HomeViewModel(private val repository: ChatRepository) : ViewModel() {

    val dashboard: StateFlow<HomeDashboardState> = combine(
        repository.boundDevice,
        repository.connectionState,
        repository.statusMessage,
        repository.peers,
        combine(repository.messages, repository.gpsFix) { messages, gps -> messages to gps }
    ) { device, state, status, peers, messagesAndGps ->
        val (messages, gps) = messagesAndGps
        HomeDashboardState(
            deviceName = device?.name ?: "",
            deviceAddress = device?.address,
            connectionState = state,
            statusMessage = status,
            peerCount = peers.size,
            recentMessages = messages.takeLast(3).reversed(),
            lastActivityMillis = messages.maxOfOrNull { it.timestamp }
                ?: peers.maxOfOrNull { it.lastSeen },
            gpsFix = gps
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeDashboardState(
            deviceName = "",
            deviceAddress = null,
            connectionState = BridgeConnectionState.DISCONNECTED,
            statusMessage = "Not connected",
            peerCount = 0,
            recentMessages = emptyList(),
            lastActivityMillis = null,
            gpsFix = GpsFix()
        )
    )

    val isBound: StateFlow<Boolean> = repository.boundDevice
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun reconnect() = repository.reconnectBound()

    fun disconnect() = repository.disconnectLink()

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
