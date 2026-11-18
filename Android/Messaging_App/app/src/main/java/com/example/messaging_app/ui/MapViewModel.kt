package com.example.messaging_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.GpsFix
import com.example.messaging_app.data.PeerContact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MapViewModel(repository: ChatRepository) : ViewModel() {

    val gpsFix: StateFlow<GpsFix> = repository.gpsFix
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GpsFix())

    val peers: StateFlow<List<PeerContact>> = repository.peers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(repository) as T
        }
    }
}
