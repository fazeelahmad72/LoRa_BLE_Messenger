#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Nordic UART Service (NUS):
 *   RX — phone writes text to the ESP32
 *   TX — ESP32 notifies text to the phone
 */

typedef void (*ble_uart_rx_callback_t)(const char *text, size_t length);
typedef void (*ble_uart_notify_callback_t)(bool notify_enabled);

int ble_uart_count_cfg(void);
int ble_uart_add_svcs(void);

void ble_uart_set_rx_callback(ble_uart_rx_callback_t callback);
void ble_uart_set_notify_callback(ble_uart_notify_callback_t callback);
void ble_uart_send(const char *msg);

void ble_uart_on_link_reset(void);
void ble_uart_on_subscribe(uint16_t attr_handle, bool notify);
