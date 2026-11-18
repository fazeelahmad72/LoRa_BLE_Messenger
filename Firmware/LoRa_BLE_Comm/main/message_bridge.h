#pragma once

/*
 * Two-way bridge:
 *   nRF app  --BLE-->  T-Beam  --LoRa-->  other T-Beam
 *   other T-Beam  --LoRa-->  T-Beam  --BLE-->  nRF app
 */
void message_bridge_start(void);
