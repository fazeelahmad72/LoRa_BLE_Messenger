package com.example.messaging_app

import android.app.Application
import com.example.messaging_app.data.AppThemeMode
import com.example.messaging_app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessagingApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Dark is the product default before preferences load.
        UserPreferences.applyThemeMode(AppThemeMode.DARK)
        container = AppContainer(this)
        appScope.launch {
            container.userPreferences.applySavedTheme()
        }
    }
}
