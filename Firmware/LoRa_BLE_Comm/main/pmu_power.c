#include "pmu_power.h"

#include "board_pins.h"

#include "driver/i2c_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include <stdbool.h>
#include <stdint.h>

static const char *TAG = "pmu_power";

#define AXP2101_REG_CHIP_ID          0x03
#define AXP2101_CHIP_ID_VALUE        0x4A
#define AXP2101_REG_LDO_ONOFF_CTRL0  0x90
#define AXP2101_REG_ALDO2_VOLTAGE    0x93
#define AXP2101_REG_ALDO3_VOLTAGE    0x94
#define AXP2101_ALDO2_ENABLE_BIT     (1 << 1)   /* bit1 = ALDO2 (LoRa 3.3 V) */
#define AXP2101_ALDO3_ENABLE_BIT     (1 << 2)   /* bit2 = ALDO3 (GPS 3.3 V) */
#define AXP2101_ALDO_3300MV_VALUE    28         /* 0.5 V + 28 * 0.1 V = 3.3 V */
#define I2C_TIMEOUT_MS               50

static i2c_master_bus_handle_t s_i2c_bus;
static i2c_master_dev_handle_t s_axp;
static bool s_ready;

static esp_err_t axp_write(uint8_t reg, uint8_t value)
{
    uint8_t buf[2] = {reg, value};
    return i2c_master_transmit(s_axp, buf, sizeof(buf), I2C_TIMEOUT_MS);
}

static esp_err_t axp_read(uint8_t reg, uint8_t *value)
{
    return i2c_master_transmit_receive(s_axp, &reg, 1, value, 1, I2C_TIMEOUT_MS);
}

esp_err_t pmu_power_init(void)
{
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = PIN_PMU_SDA,
        .scl_io_num = PIN_PMU_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };

    esp_err_t err = i2c_new_master_bus(&bus_cfg, &s_i2c_bus);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C bus init failed: %s", esp_err_to_name(err));
        return err;
    }

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = AXP2101_I2C_ADDR,
        .scl_speed_hz = 400000,
    };

    err = i2c_master_bus_add_device(s_i2c_bus, &dev_cfg, &s_axp);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "AXP2101 device add failed: %s", esp_err_to_name(err));
        return err;
    }

    uint8_t chip_id = 0;
    err = axp_read(AXP2101_REG_CHIP_ID, &chip_id);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "AXP2101 not responding on I2C");
        return err;
    }

    if (chip_id != AXP2101_CHIP_ID_VALUE) {
        ESP_LOGE(TAG, "Unexpected PMU chip id 0x%02X (expected 0x%02X)",
                 chip_id, AXP2101_CHIP_ID_VALUE);
        return ESP_ERR_NOT_FOUND;
    }

    s_ready = true;
    ESP_LOGI(TAG, "AXP2101 found (id 0x%02X)", chip_id);
    return ESP_OK;
}

esp_err_t pmu_power_turn_on_lora(void)
{
    if (!s_ready) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t err = axp_write(AXP2101_REG_ALDO2_VOLTAGE, AXP2101_ALDO_3300MV_VALUE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set ALDO2 voltage");
        return err;
    }

    uint8_t onoff = 0;
    err = axp_read(AXP2101_REG_LDO_ONOFF_CTRL0, &onoff);
    if (err != ESP_OK) {
        return err;
    }

    onoff |= AXP2101_ALDO2_ENABLE_BIT;
    err = axp_write(AXP2101_REG_LDO_ONOFF_CTRL0, onoff);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to enable ALDO2 (LoRa 3.3 V)");
        return err;
    }

    /* Give the SX1276 time to power up before SPI starts. */
    vTaskDelay(pdMS_TO_TICKS(50));
    ESP_LOGI(TAG, "LoRa 3.3 V rail (ALDO2) is on");
    return ESP_OK;
}

esp_err_t pmu_power_turn_on_gps(void)
{
    if (!s_ready) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t err = axp_write(AXP2101_REG_ALDO3_VOLTAGE, AXP2101_ALDO_3300MV_VALUE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set ALDO3 voltage");
        return err;
    }

    uint8_t onoff = 0;
    err = axp_read(AXP2101_REG_LDO_ONOFF_CTRL0, &onoff);
    if (err != ESP_OK) {
        return err;
    }

    onoff |= AXP2101_ALDO3_ENABLE_BIT;
    err = axp_write(AXP2101_REG_LDO_ONOFF_CTRL0, onoff);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to enable ALDO3 (GPS 3.3 V)");
        return err;
    }

    /* NEO-6M needs a moment after rail enable before NMEA starts. */
    vTaskDelay(pdMS_TO_TICKS(200));
    ESP_LOGI(TAG, "GPS 3.3 V rail (ALDO3) is on");
    return ESP_OK;
}
