package com.example.messaging_app.ble

import java.util.UUID

/**
 * Nordic UART Service identity used by the T-Beam LoRa_BLE_Comm firmware.
 */
object NusUuids {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX_WRITE: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val TX_NOTIFY: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Firmware advertises a local name only (see ble_app_advertise).
     * Match common T-Beam / LoRa_BLE_Comm names seen in nRF Connect.
     */
    val KNOWN_DEVICE_NAME_PREFIXES = listOf(
        "TBeam",
        "T-Beam",
        "TBEAM",
        "LoRa_BLE",
        "LoRaBLE",
        "LilyGo",
        "LILYGO"
    )
}
