package com.example.messaging_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.messaging_app.data.AppThemeMode
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.LoraRadioConfig
import com.example.messaging_app.data.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: ChatRepository,
    private val preferences: UserPreferences
) : ViewModel() {

    val boundDevice = repository.boundDevice
    val connectionState = repository.connectionState
    val statusMessage = repository.statusMessage

    val loraRadio: StateFlow<LoraRadioConfig> = repository.loraRadio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoraRadioConfig())

    val themeMode: StateFlow<AppThemeMode> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppThemeMode.DARK)

    fun reconnect() = repository.reconnectBound()

    fun disconnect() = repository.disconnectLink()

    fun setTheme(mode: AppThemeMode) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val preferences: UserPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository, preferences) as T
        }
    }
}
