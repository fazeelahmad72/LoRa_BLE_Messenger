# LoRa BLE Comm

Two-way bridge on **LILYGO T-Beam V1.2** (SX1276 + ESP32 + AXP2101):

```
nRF Connect  --BLE-->  T-Beam A  --LoRa-->  T-Beam B  --BLE-->  nRF Connect
nRF Connect  <--BLE--  T-Beam A  <--LoRa--  T-Beam B  <--BLE--  nRF Connect
```

Flash the **same firmware** on both T-Beams. Each phone talks BLE to its own board; boards talk LoRa to each other.

## Hardware

- 2× LILYGO T-BEAM-AXP2101-V1.2 (915 MHz SX1276)
- LoRa antennas attached
- Phone with [nRF Connect](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile)

## Build and flash

```bash
idf.py set-target esp32
idf.py build
idf.py -p COMx flash monitor
```

Flash both modules the same way.

## Use with nRF Connect

1. Scan and connect to **TBeam_LoRa_BLE** (both boards advertise the same name — pick by MAC).
2. Open Nordic UART Service.
3. Enable **Notify** on the TX characteristic (required to see received LoRa messages).
4. Write ASCII text to the RX characteristic.
5. On the other phone (connected to the other T-Beam), the message appears as a TX notification.

USB serial also logs `[BLE->LoRa TX]` and `[LoRa RX]` for debugging.

## Project layout

| File | Role |
|------|------|
| `main.c` | Boot: NVS, PMU, LoRa, BLE, bridge |
| `board_pins.h` | T-Beam V1.2 pin map |
| `lora_settings.h` | Frequency, SF, bandwidth, power |
| `ble_config.h` | BLE device name and MTU |
| `pmu_power.c/.h` | AXP2101 — turn on LoRa 3.3 V |
| `lora_radio.c/.h` | SX1276 send / receive |
| `ble_app.c/.h` | NimBLE advertising and connections |
| `ble_uart.c/.h` | Nordic UART Service (RX write / TX notify) |
| `message_bridge.c/.h` | BLE ↔ LoRa two-way forwarding |
| `serial_console.c/.h` | USB serial status logs |

## BLE identity

| Item | Value |
|------|--------|
| Advertised name | `TBeam_LoRa_BLE` |
| NUS service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (phone writes) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (ESP32 notifies) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

## Notes

- Both boards must share the same settings in `lora_settings.h` (default 915 MHz).
- Max LoRa text length is 128 bytes (`LORA_MAX_PACKET_BYTES`); longer BLE writes are truncated.
- GPS (Neo-M6) is left powered off in this project.
