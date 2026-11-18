# LoRaLink

Professional Android client for the **LoRa_BLE_Comm** T-Beam firmware.

```
Phone A  --BLE (NUS)-->  T-Beam A  --LoRa-->  T-Beam B  --BLE-->  Phone B
```

Each phone binds to its own ESP32. Chat text is written to the Nordic UART RX characteristic, forwarded over LoRa, and delivered to the peer phone as TX notifications.

## Features

- Home dashboard with live BLE / LoRa bridge status
- Modern chat list + Field Channel messaging UI
- Scan and **bind** nearby T-Beam boards advertising Nordic UART Service
- Nodes screen for local BLE bridge vs remote LoRa peers
- GPS / Map screen prepared for location data (shows Unavailable until firmware sends coordinates)
- Settings: Bluetooth actions, operator profile, theme, about
- Presence announcements when the bridge becomes ready
- Message history persisted locally
- Foreground service while the BLE bridge is active
- Auto-reconnect if the BLE link drops

## Navigation

Bottom navigation:

1. **Home** — connection card, status tiles, recent messages  
2. **Chats** — Field Channel + discovered peers  
3. **Map** — GPS fields (honest Unavailable until protocol support)  
4. **Nodes** — local T-Beam + remote LoRa nodes  
5. **Settings** — Bluetooth, profile, theme, about  

## BLE contract (matches firmware)

| Item | Value |
|------|--------|
| Device names | `TBeam-*`, `TBeam_LoRa_BLE`, etc. |
| NUS service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (write) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (notify) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| Max payload | 128 bytes (LoRa packet limit) |

Wire format over LoRa:

- `CHAT|<nodeId>|<text>`
- `PRES|<nodeId>|<displayName>`
- `ACK|<nodeId>|<msgId>` (parsed; delivery receipts not yet used by firmware)

Plain ASCII from nRF Connect still shows up as a peer message.

## How to use

1. Flash identical `LoRa_BLE_Comm` firmware on both T-Beams.
2. Install this app on two phones.
3. On each phone: **Settings → Manage devices → Scan → Bind** the local board (or use Home quick actions).
4. Set a display name under **Settings → Operator profile**.
5. Chat from **Chats → Field Channel**.

## Build

Open the project in Android Studio and run the `app` configuration, or:

```bash
./gradlew :app:assembleDebug
```
