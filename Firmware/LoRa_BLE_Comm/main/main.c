#include "ble_app.h"
#include "ble_config.h"
#include "gps_neo6m.h"
#include "lora_radio.h"
#include "message_bridge.h"
#include "pmu_power.h"
#include "serial_console.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs.h"
#include "nvs_flash.h"

static const char *TAG = "main";

/*
 * Old NimBLE bond blobs (from another project or older IDF) can have a different
 * struct size (e.g. expected 88, got 80). Wipe them once, then keep new bonds.
 */
#define APP_NVS_NAMESPACE   "lora_ble"
#define BOND_FORMAT_KEY     "bond_fmt"
#define BOND_FORMAT_VERSION 1

static void clear_stale_ble_bonds(void)
{
    nvs_handle_t app_handle;
    esp_err_t err = nvs_open(APP_NVS_NAMESPACE, NVS_READWRITE, &app_handle);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Could not open app NVS: %s", esp_err_to_name(err));
        return;
    }

    uint8_t format = 0;
    err = nvs_get_u8(app_handle, BOND_FORMAT_KEY, &format);
    if (err == ESP_OK && format == BOND_FORMAT_VERSION) {
        nvs_close(app_handle);
        return;
    }

    nvs_handle_t bond_handle;
    err = nvs_open("nimble_bond", NVS_READWRITE, &bond_handle);
    if (err == ESP_OK) {
        nvs_erase_all(bond_handle);
        nvs_commit(bond_handle);
        nvs_close(bond_handle);
        ESP_LOGI(TAG, "Cleared stale NimBLE bonding data");
    }

    nvs_set_u8(app_handle, BOND_FORMAT_KEY, BOND_FORMAT_VERSION);
    nvs_commit(app_handle);
    nvs_close(app_handle);
}

static void nvs_init(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    clear_stale_ble_bonds();
    ESP_LOGI(TAG, "NVS ready");
}

void app_main(void)
{
    serial_console_init();
    serial_console_write_line("");
    serial_console_write_line("T-Beam BLE <-> LoRa starting...");

    nvs_init();

    if (pmu_power_init() != ESP_OK) {
        ESP_LOGW(TAG, "AXP2101 not found, trying LoRa anyway");
        serial_console_write_line("WARNING: AXP2101 PMU not found, trying LoRa anyway");
    } else {
        if (pmu_power_turn_on_lora() != ESP_OK) {
            serial_console_write_line("ERROR: Could not turn on LoRa 3.3 V rail");
            return;
        }
        if (pmu_power_turn_on_gps() != ESP_OK) {
            serial_console_write_line("WARNING: Could not turn on GPS 3.3 V rail");
        }
    }

    if (lora_radio_init() != ESP_OK) {
        serial_console_write_line("ERROR: LoRa radio did not start.");
        serial_console_write_line("       Check antenna, AXP2101 ALDO2, and frequency.");
        return;
    }

    if (gps_neo6m_init() != ESP_OK) {
        serial_console_write_line("WARNING: GPS UART did not start");
    }

    ble_app_init();
    message_bridge_start();
    gps_neo6m_start();

    ESP_LOGI(TAG, "Setup complete — advertising as \"%s\"", DEVICE_NAME);

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}
