#pragma once

#include <stdbool.h>
#include <stdint.h>

/*
 * BLE application core: NimBLE bring-up, advertising, and connection state.
 */

void ble_app_init(void);
uint16_t ble_app_conn_handle(void);
bool ble_app_is_connected(void);
uint16_t ble_app_mtu(void);
uint16_t ble_app_max_payload(void);
