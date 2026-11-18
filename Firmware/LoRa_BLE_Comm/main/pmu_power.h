#pragma once

#include "esp_err.h"

/*
 * AXP2101 power chip on T-BEAM-AXP2101-V1.2.
 * LoRa needs ALDO2 (3.3 V). GPS (NEO-6M) needs ALDO3 (3.3 V).
 */
esp_err_t pmu_power_init(void);
esp_err_t pmu_power_turn_on_lora(void);
esp_err_t pmu_power_turn_on_gps(void);
