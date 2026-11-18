# LoRa BLE Communication

Off-grid messaging over **LoRa**, with phones linked to each board over **Bluetooth Low Energy**.

```
Phone A  --BLE (NUS)-->  T-Beam A  --LoRa-->  T-Beam B  --BLE-->  Phone B
Phone A  <--BLE (NUS)--  T-Beam A  <--LoRa--  T-Beam B  <--BLE--  Phone B
```

This repository contains both sides of the system:

| Folder | Project | Role |
|--------|---------|------|
| [`Android/Messaging_App`](Android/Messaging_App) | **LoRaLink** Android app | Scan/bind a T-Beam, send/receive chat over BLE |
| [`Firmware/LoRa_BLE_Comm`](Firmware/LoRa_BLE_Comm) | ESP-IDF firmware | BLE ↔ LoRa bridge on LILYGO T-Beam V1.2 |

Flash the **same firmware** on both T-Beams. Each phone talks BLE only to its own board; the boards talk LoRa to each other.

---

## Features

### Android (LoRaLink)

- Home dashboard with live BLE / LoRa bridge status
- Field Channel chat UI with local message history
- Scan and bind nearby T-Beam boards (Nordic UART Service)
- Nodes screen for local BLE bridge vs remote LoRa peers
- Map / GPS view (uses coordinates when firmware reports them)
- Settings: Bluetooth actions, operator profile, theme
- Foreground service + auto-reconnect while the bridge is active

### Firmware (T-Beam)

- Two-way **BLE ↔ LoRa** message bridge
- Nordic UART Service (NUS) for phone compatibility
- SX1276 LoRa radio (default **915 MHz**)
- AXP2101 PMU control (LoRa / GPS power rails)
- Optional Neo-6M GPS UART support
- USB serial logs for debugging (`[BLE->LoRa TX]`, `[LoRa RX]`)

---

## Hardware requirements

- **2×** [LILYGO T-BEAM-AXP2101-V1.2](https://www.lilygo.cc/) (ESP32 + SX1276 + AXP2101)
- LoRa antennas attached on both boards
- **2×** Android phones (API 26+) with Bluetooth LE
- USB cable for flashing / serial monitor

> Both boards must use the **same** LoRa settings (frequency, SF, bandwidth, etc.) or packets will not decode.

---

## Repository structure

```
LoRa_BLE_Communication/
├── Android/
│   └── Messaging_App/          # LoRaLink Android client (Kotlin)
├── Firmware/
│   └── LoRa_BLE_Comm/          # ESP-IDF firmware for T-Beam
└── README.md                   # This file
```

More detail:

- Android: [`Android/Messaging_App/README.md`](Android/Messaging_App/README.md)
- Firmware: [`Firmware/LoRa_BLE_Comm/README.md`](Firmware/LoRa_BLE_Comm/README.md)

---

## Quick start

### 1. Flash the firmware (both T-Beams)

Requires [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/get-started/) (ESP32 target).

```bash
cd Firmware/LoRa_BLE_Comm
idf.py set-target esp32
idf.py build
idf.py -p COMx flash monitor
```

On Windows, replace `COMx` with the board’s serial port (e.g. `COM5`).  
Repeat the flash on the second T-Beam with the **identical** build.

Default radio settings (`main/lora_settings.h`):

| Setting | Default |
|---------|---------|
| Frequency | 915 MHz |
| Spreading factor | 7 |
| Bandwidth | 125 kHz |
| Coding rate | 4/5 |
| TX power | 17 dBm |
| Max payload | 128 bytes |

Change these only if you rebuild and flash **both** modules.

### 2. Build the Android app

Open `Android/Messaging_App` in [Android Studio](https://developer.android.com/studio), then run the `app` configuration, or:

```bash
cd Android/Messaging_App
./gradlew :app:assembleDebug
```

- **minSdk:** 26  
- **targetSdk:** 36  
- **App name:** LoRaLink  

Install the APK on both phones.

### 3. Connect and chat

1. Power both T-Beams (antennas attached).
2. On each phone: **Settings → Manage devices → Scan → Bind** the local board (or use Home quick actions).  
   Boards advertise as `TBeam-*` / `TBeam_LoRa_BLE` — pick by MAC if both appear.
3. Set a display name under **Settings → Operator profile**.
4. Open **Chats → Field Channel** and send a message.

You can also test the firmware alone with [nRF Connect](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile): connect to the board, enable Notify on the TX characteristic, and write ASCII to RX.

---

## BLE contract

| Item | Value |
|------|--------|
| NUS service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (phone writes) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (ESP32 notifies) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| Max payload | 128 bytes (LoRa packet limit) |

## Wire protocol (over BLE / LoRa)

| Packet | Format |
|--------|--------|
| Chat | `CHAT\|<nodeId>\|<text>` |
| Presence | `PRES\|<nodeId>\|<displayName>` |
| Ack | `ACK\|<nodeId>\|<msgId>` |
| GPS (local telemetry) | `GPS\|<fix>\|<lat>\|<lon>\|<sats>\|<speed_kmh>` |
| Radio status | `RADIO\|<freqHz>\|<sf>\|<bwKhz>\|<cr>\|<txDbm>` |

Plain ASCII written via nRF Connect is still forwarded and shown as a peer message.

---

## Firmware layout

| File | Role |
|------|------|
| `main/main.c` | Boot: NVS, PMU, LoRa, GPS, BLE, bridge |
| `main/board_pins.h` | T-Beam V1.2 pin map |
| `main/lora_settings.h` | Frequency, SF, bandwidth, power |
| `main/ble_config.h` | BLE device name and MTU |
| `main/pmu_power.c/.h` | AXP2101 — LoRa / GPS 3.3 V rails |
| `main/lora_radio.c/.h` | SX1276 send / receive |
| `main/ble_app.c/.h` | NimBLE advertising and connections |
| `main/ble_uart.c/.h` | Nordic UART Service (RX write / TX notify) |
| `main/message_bridge.c/.h` | BLE ↔ LoRa two-way forwarding |
| `main/gps_neo6m.c/.h` | Neo-6M GPS UART |
| `main/serial_console.c/.h` | USB serial status logs |

---

## Notes

- Antennas are required; transmitting without an antenna can damage the radio.
- Longer BLE writes are truncated to **128 bytes**.
- If messages do not arrive, confirm both boards share the same `lora_settings.h` and that Notify is enabled on the TX characteristic (nRF Connect) or that LoRaLink is bound and connected.
- USB serial (115200 baud) is useful for seeing bridge TX/RX logs while debugging.

---
