package com.example.messaging_app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lora_chat_prefs")

enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

class UserPreferences(private val context: Context) {

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val BOUND_ADDRESS = stringPreferencesKey("bound_address")
        val BOUND_NAME = stringPreferencesKey("bound_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val profile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        val id = prefs[Keys.USER_ID] ?: ""
        UserProfile(
            userId = id,
            displayName = prefs[Keys.DISPLAY_NAME].orEmpty().ifBlank { "Operator" }
        )
    }

    val themeMode: Flow<AppThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { AppThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: AppThemeMode.DARK.name) }
            .getOrDefault(AppThemeMode.DARK)
    }

    val boundDevice: Flow<BoundDevice?> = context.dataStore.data.map { prefs ->
        val address = prefs[Keys.BOUND_ADDRESS] ?: return@map null
        BoundDevice(
            address = address,
            name = prefs[Keys.BOUND_NAME] ?: "T-Beam"
        )
    }

    suspend fun ensureUserId(): String {
        var created: String? = null
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.USER_ID]
            if (existing.isNullOrBlank()) {
                val id = UUID.randomUUID().toString().replace("-", "").take(8)
                prefs[Keys.USER_ID] = id
                created = id
            } else {
                created = existing
            }
        }
        return created.orEmpty()
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = name.trim().ifBlank { "Operator" }
        }
    }

    suspend fun bindDevice(device: BoundDevice) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BOUND_ADDRESS] = device.address
            prefs[Keys.BOUND_NAME] = device.name
        }
    }

    suspend fun clearBoundDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.BOUND_ADDRESS)
            prefs.remove(Keys.BOUND_NAME)
        }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
        applyThemeMode(mode)
    }

    suspend fun applySavedTheme() {
        applyThemeMode(themeMode.first())
    }

    companion object {
        fun applyThemeMode(mode: AppThemeMode) {
            val nightMode = when (mode) {
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}

data class UserProfile(
    val userId: String,
    val displayName: String
)
