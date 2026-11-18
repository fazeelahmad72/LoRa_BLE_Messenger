#pragma once

/*
 * Shared BLE tunables.
 * Device name appears in nRF Connect scan results.
 */

#define DEVICE_NAME      "TBeam-01"
#define PREFERRED_MTU    247
#define BLE_LINE_MAX     (PREFERRED_MTU - 3)   /* MTU minus 3-byte ATT header */
