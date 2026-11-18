package com.example.messaging_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.messaging_app.data.BoundDevice
import com.example.messaging_app.data.BridgeConnectionState
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.ScannedDevice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(private val repository: ChatRepository) : ViewModel() {

    val scannedDevices: StateFlow<List<ScannedDevice>> = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val boundDevice = repository.boundDevice
    val connectionState = repository.connectionState
    val statusMessage = repository.statusMessage

    val isScanning: StateFlow<Boolean> = connectionState
        .map { it == BridgeConnectionState.SCANNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun startScan() = repository.startScan()

    fun stopScan() = repository.stopScan()

    fun bind(device: ScannedDevice) {
        viewModelScope.launch {
            repository.bindDevice(
                BoundDevice(address = device.address, name = device.name)
            )
        }
    }

    fun unbind() {
        viewModelScope.launch {
            repository.unbindDevice()
        }
    }

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DevicesViewModel(repository) as T
        }
    }
}
