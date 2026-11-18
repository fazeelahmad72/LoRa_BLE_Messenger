package com.example.messaging_app

import android.content.Context
import com.example.messaging_app.ble.BleConnectionManager
import com.example.messaging_app.data.ChatRepository
import com.example.messaging_app.data.MessageStore
import com.example.messaging_app.data.UserPreferences

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val userPreferences = UserPreferences(appContext)
    val messageStore = MessageStore(appContext)
    val bleConnectionManager = BleConnectionManager(appContext)
    val chatRepository = ChatRepository(
        preferences = userPreferences,
        messageStore = messageStore,
        ble = bleConnectionManager
    )
}
