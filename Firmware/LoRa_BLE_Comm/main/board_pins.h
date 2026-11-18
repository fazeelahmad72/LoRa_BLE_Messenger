#pragma once

/*
 * LILYGO T-BEAM-AXP2101-V1.2 pin map
 * FCC ID: 2ASYE-T-Beam V1.2
 *
 * Onboard parts used by this project:
 *   - ESP-WROOM-32
 *   - SX1276 LoRa (868/915 MHz)
 *   - AXP2101 power management
 *   - u-blox NEO-6M GPS (ALDO3 + UART1)
 */

/* AXP2101 I2C */
#define PIN_PMU_SDA            21
#define PIN_PMU_SCL            22
#define PIN_PMU_IRQ            35
#define AXP2101_I2C_ADDR       0x34

/* SX1276 LoRa SPI + control */
#define PIN_LORA_SCK           5
#define PIN_LORA_MISO          19
#define PIN_LORA_MOSI          27
#define PIN_LORA_CS            18
#define PIN_LORA_RST           23
#define PIN_LORA_DIO0          26
#define PIN_LORA_DIO1          33
#define PIN_LORA_DIO2          32

/* NEO-6M GPS UART1 (module TX -> ESP RX on 34, ESP TX -> module RX on 12) */
#define PIN_GPS_RX             34
#define PIN_GPS_TX             12
#define GPS_UART_PORT          1
#define GPS_UART_BAUD          9600

/* USB serial (UART0) — already wired to the USB-UART chip */
#define SERIAL_UART_PORT       0
#define SERIAL_UART_BAUD       115200
