package com.example.messaging_app.data

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    RECEIVED
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus
)

data class PeerContact(
    val id: String,
    val displayName: String,
    val lastSeen: Long,
    val lastMessagePreview: String = "",
    val unreadCount: Int = 0
)

data class BoundDevice(
    val address: String,
    val name: String,
    val boundAt: Long = System.currentTimeMillis()
)

data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val firstSeen: Long = System.currentTimeMillis()
)

enum class BridgeConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    READY,
    RECONNECTING
}

/** Local T-Beam NEO-6M fix delivered over BLE (GPS|fix|lat|lon|sats|speed). */
data class GpsFix(
    val hasFix: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val satellites: Int = 0,
    val speedKmh: Float = 0f,
    val updatedAtMillis: Long = 0L
)

/**
 * LoRa radio parameters from firmware (RADIO|freqHz|sf|bwKhz|cr|txDbm).
 * Defaults match LoRa_BLE_Comm main/lora_settings.h so Settings is filled
 * even before the first RADIO notify arrives.
 */
data class LoraRadioConfig(
    val frequencyHz: Int = 915_000_000,
    val spreadingFactor: Int = 7,
    val bandwidthKhz: Int = 125,
    val codingRate: Int = 5,
    val txPowerDbm: Int = 17,
    val fromFirmware: Boolean = false,
    val updatedAtMillis: Long = 0L
)
