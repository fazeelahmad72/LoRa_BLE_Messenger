#pragma once

#include "esp_err.h"
#include "lora_settings.h"

#include <stdint.h>

typedef struct {
    int16_t rssi_dbm;
    float snr_db;
} lora_rx_info_t;

esp_err_t lora_radio_init(void);
esp_err_t lora_radio_send_bytes(const uint8_t *data, uint8_t length);
int lora_radio_receive_bytes(uint8_t *data, uint8_t max_length, int timeout_ms,
                             lora_rx_info_t *info);
