#pragma once

#include "esp_err.h"

#include <stdbool.h>

/*
 * u-blox NEO-6M on T-Beam UART1.
 * Publishes printable ASCII over BLE:
 *   GPS|<fix>|<lat>|<lon>|<sats>|<speed_kmh>
 * fix is 0 (no fix) or 1 (valid).
 */
typedef struct {
    bool valid;
    double latitude;
    double longitude;
    int satellites;
    float speed_kmh;
} gps_fix_t;

esp_err_t gps_neo6m_init(void);
void gps_neo6m_start(void);
bool gps_neo6m_get_fix(gps_fix_t *out);
