#pragma once

/*
 * Both boards must use the same radio settings or packets will not decode.
 * Change values here, then rebuild and flash BOTH modules.
 */

#define LORA_FREQUENCY_HZ          915000000  /* 915MHz*/
#define LORA_SPREADING_FACTOR      7          /* 7 = faster chat, 12 = longer range */
#define LORA_BANDWIDTH_KHZ         125        /* 125, 250, or 500 */
#define LORA_CODING_RATE           5          /* 5 means 4/5 */
#define LORA_TX_POWER_DBM          17         /* PA_BOOST range on T-Beam: 2 to 17 */
#define LORA_SYNC_WORD             0x12       /* private network, not LoRaWAN 0x34 */
#define LORA_PREAMBLE_SYMBOLS      8
#define LORA_CRC_ON                1

/* SX1276 supports up to 255 bytes. Keep this modest for text messages. */
#define LORA_MAX_PACKET_BYTES      128

/* Time to wait for the radio to finish transmitting */
#define LORA_TX_TIMEOUT_MS         2000
