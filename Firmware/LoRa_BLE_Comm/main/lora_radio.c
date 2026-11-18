#include "lora_radio.h"

#include "board_pins.h"
#include "lora_settings.h"

#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include <stdbool.h>
#include <string.h>

static const char *TAG = "lora_radio";

#define SX1276_REG_FIFO                0x00
#define SX1276_REG_OP_MODE             0x01
#define SX1276_REG_FRF_MSB             0x06
#define SX1276_REG_FRF_MID             0x07
#define SX1276_REG_FRF_LSB             0x08
#define SX1276_REG_PA_CONFIG           0x09
#define SX1276_REG_LNA                 0x0C
#define SX1276_REG_FIFO_ADDR_PTR       0x0D
#define SX1276_REG_FIFO_TX_BASE        0x0E
#define SX1276_REG_FIFO_RX_BASE        0x0F
#define SX1276_REG_FIFO_RX_CURRENT     0x10
#define SX1276_REG_IRQ_FLAGS           0x12
#define SX1276_REG_RX_NB_BYTES         0x13
#define SX1276_REG_PKT_SNR_VALUE       0x19
#define SX1276_REG_PKT_RSSI_VALUE      0x1A
#define SX1276_REG_MODEM_CONFIG1       0x1D
#define SX1276_REG_MODEM_CONFIG2       0x1E
#define SX1276_REG_PREAMBLE_MSB        0x20
#define SX1276_REG_PREAMBLE_LSB        0x21
#define SX1276_REG_PAYLOAD_LENGTH      0x22
#define SX1276_REG_MODEM_CONFIG3       0x26
#define SX1276_REG_SYNC_WORD           0x39
#define SX1276_REG_DIO_MAPPING1        0x40
#define SX1276_REG_VERSION             0x42
#define SX1276_REG_PA_DAC              0x4D

#define SX1276_MODE_SLEEP              0x00
#define SX1276_MODE_STDBY              0x01
#define SX1276_MODE_TX                 0x03
#define SX1276_MODE_RX_CONTINUOUS      0x05
#define SX1276_LONG_RANGE_MODE         0x80

#define SX1276_IRQ_TX_DONE             0x08
#define SX1276_IRQ_PAYLOAD_CRC_ERROR   0x20
#define SX1276_IRQ_RX_DONE             0x40

#define SX1276_VERSION_EXPECTED        0x12
#define SX1276_FXOSC_HZ                32000000ULL

static spi_device_handle_t s_spi;
static SemaphoreHandle_t s_bus_lock;
static bool s_ready;

static uint8_t bandwidth_bits(void)
{
#if LORA_BANDWIDTH_KHZ == 125
    return 7;
#elif LORA_BANDWIDTH_KHZ == 250
    return 8;
#elif LORA_BANDWIDTH_KHZ == 500
    return 9;
#else
#error "LORA_BANDWIDTH_KHZ must be 125, 250, or 500"
#endif
}

static uint8_t coding_rate_bits(void)
{
#if LORA_CODING_RATE == 5
    return 1;   /* 4/5 */
#elif LORA_CODING_RATE == 6
    return 2;   /* 4/6 */
#elif LORA_CODING_RATE == 7
    return 3;   /* 4/7 */
#elif LORA_CODING_RATE == 8
    return 4;   /* 4/8 */
#else
#error "LORA_CODING_RATE must be 5, 6, 7, or 8"
#endif
}

static esp_err_t sx1276_write(uint8_t address, uint8_t value)
{
    uint8_t tx[2] = {(uint8_t)(address | 0x80), value};
    spi_transaction_t txn = {
        .length = 16,
        .tx_buffer = tx,
    };
    return spi_device_transmit(s_spi, &txn);
}

static uint8_t sx1276_read(uint8_t address)
{
    uint8_t tx[2] = {(uint8_t)(address & 0x7F), 0x00};
    uint8_t rx[2] = {0};
    spi_transaction_t txn = {
        .length = 16,
        .tx_buffer = tx,
        .rx_buffer = rx,
    };
    spi_device_transmit(s_spi, &txn);
    return rx[1];
}

static void sx1276_write_buffer(uint8_t address, const uint8_t *data, uint8_t length)
{
    uint8_t tx[1 + LORA_MAX_PACKET_BYTES];
    tx[0] = (uint8_t)(address | 0x80);
    memcpy(&tx[1], data, length);

    spi_transaction_t txn = {
        .length = (size_t)(length + 1) * 8,
        .tx_buffer = tx,
    };
    spi_device_transmit(s_spi, &txn);
}

static void sx1276_read_buffer(uint8_t address, uint8_t *data, uint8_t length)
{
    uint8_t tx[1 + LORA_MAX_PACKET_BYTES] = {0};
    uint8_t rx[1 + LORA_MAX_PACKET_BYTES] = {0};
    tx[0] = (uint8_t)(address & 0x7F);

    spi_transaction_t txn = {
        .length = (size_t)(length + 1) * 8,
        .tx_buffer = tx,
        .rx_buffer = rx,
    };
    spi_device_transmit(s_spi, &txn);
    memcpy(data, &rx[1], length);
}

static void sx1276_set_mode(uint8_t mode)
{
    sx1276_write(SX1276_REG_OP_MODE, (uint8_t)(SX1276_LONG_RANGE_MODE | mode));
}

static void sx1276_set_frequency(uint32_t hz)
{
    uint64_t frf = ((uint64_t)hz << 19) / SX1276_FXOSC_HZ;
    sx1276_write(SX1276_REG_FRF_MSB, (uint8_t)(frf >> 16));
    sx1276_write(SX1276_REG_FRF_MID, (uint8_t)(frf >> 8));
    sx1276_write(SX1276_REG_FRF_LSB, (uint8_t)(frf >> 0));
}

static void sx1276_set_tx_power(int dbm)
{
    if (dbm > 17) {
        dbm = 17;
    }
    if (dbm < 2) {
        dbm = 2;
    }
    /* PA_BOOST pin: Pout = 2 + OutputPower */
    sx1276_write(SX1276_REG_PA_DAC, 0x84);
    sx1276_write(SX1276_REG_PA_CONFIG, (uint8_t)(0x80 | (dbm - 2)));
}

static void sx1276_enter_receive(void)
{
    sx1276_write(SX1276_REG_DIO_MAPPING1, 0x00); /* DIO0 = RxDone */
    sx1276_write(SX1276_REG_IRQ_FLAGS, 0xFF);
    sx1276_set_mode(SX1276_MODE_RX_CONTINUOUS);
}

static void pulse_reset_pin(void)
{
    gpio_config_t rst = {
        .pin_bit_mask = 1ULL << PIN_LORA_RST,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&rst);

    gpio_set_level(PIN_LORA_RST, 0);
    vTaskDelay(pdMS_TO_TICKS(10));
    gpio_set_level(PIN_LORA_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(10));
}

esp_err_t lora_radio_init(void)
{
    s_bus_lock = xSemaphoreCreateMutex();
    if (s_bus_lock == NULL) {
        return ESP_ERR_NO_MEM;
    }

    pulse_reset_pin();

    spi_bus_config_t bus_cfg = {
        .mosi_io_num = PIN_LORA_MOSI,
        .miso_io_num = PIN_LORA_MISO,
        .sclk_io_num = PIN_LORA_SCK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 1 + LORA_MAX_PACKET_BYTES,
    };

    esp_err_t err = spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SPI bus init failed: %s", esp_err_to_name(err));
        return err;
    }

    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = 8 * 1000 * 1000,
        .mode = 0,
        .spics_io_num = PIN_LORA_CS,
        .queue_size = 1,
    };

    err = spi_bus_add_device(SPI2_HOST, &dev_cfg, &s_spi);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SPI device add failed: %s", esp_err_to_name(err));
        return err;
    }

    uint8_t version = sx1276_read(SX1276_REG_VERSION);
    if (version != SX1276_VERSION_EXPECTED) {
        ESP_LOGE(TAG,
                 "SX1276 not found (version 0x%02X, expected 0x12). "
                 "Check AXP2101 ALDO2 and SPI wiring.",
                 version);
        return ESP_ERR_NOT_FOUND;
    }

    /* LoRa mode can only be selected while the chip is in sleep. */
    sx1276_write(SX1276_REG_OP_MODE, SX1276_MODE_SLEEP);
    sx1276_set_mode(SX1276_MODE_SLEEP);
    vTaskDelay(pdMS_TO_TICKS(10));
    sx1276_set_mode(SX1276_MODE_STDBY);

    sx1276_set_frequency(LORA_FREQUENCY_HZ);
    sx1276_set_tx_power(LORA_TX_POWER_DBM);

    sx1276_write(SX1276_REG_FIFO_TX_BASE, 0x00);
    sx1276_write(SX1276_REG_FIFO_RX_BASE, 0x00);
    sx1276_write(SX1276_REG_LNA, 0x23); /* max LNA gain + boost */

    uint8_t cfg1 = (uint8_t)((bandwidth_bits() << 4) | (coding_rate_bits() << 1));
    sx1276_write(SX1276_REG_MODEM_CONFIG1, cfg1);

    uint8_t cfg2 = (uint8_t)(LORA_SPREADING_FACTOR << 4);
#if LORA_CRC_ON
    cfg2 |= 0x04;
#endif
    sx1276_write(SX1276_REG_MODEM_CONFIG2, cfg2);

    /* Enable Low Data Rate Optimize when symbol time is longer than 16 ms. */
    uint32_t bw_hz = (uint32_t)LORA_BANDWIDTH_KHZ * 1000u;
    uint32_t symbol_us = (1000000u << LORA_SPREADING_FACTOR) / bw_hz;
    uint8_t cfg3 = 0x04; /* AGC auto on */
    if (symbol_us > 16000u) {
        cfg3 |= 0x08;
    }
    sx1276_write(SX1276_REG_MODEM_CONFIG3, cfg3);

    sx1276_write(SX1276_REG_PREAMBLE_MSB, (uint8_t)(LORA_PREAMBLE_SYMBOLS >> 8));
    sx1276_write(SX1276_REG_PREAMBLE_LSB, (uint8_t)(LORA_PREAMBLE_SYMBOLS & 0xFF));
    sx1276_write(SX1276_REG_SYNC_WORD, LORA_SYNC_WORD);

    sx1276_enter_receive();
    s_ready = true;

    ESP_LOGI(TAG, "SX1276 ready at %d Hz, SF%d, %d kHz, %d dBm",
             LORA_FREQUENCY_HZ, LORA_SPREADING_FACTOR,
             LORA_BANDWIDTH_KHZ, LORA_TX_POWER_DBM);
    return ESP_OK;
}

esp_err_t lora_radio_send_bytes(const uint8_t *data, uint8_t length)
{
    if (!s_ready || data == NULL || length == 0 || length > LORA_MAX_PACKET_BYTES) {
        return ESP_ERR_INVALID_ARG;
    }

    if (xSemaphoreTake(s_bus_lock, pdMS_TO_TICKS(LORA_TX_TIMEOUT_MS)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    sx1276_set_mode(SX1276_MODE_STDBY);
    sx1276_write(SX1276_REG_FIFO_ADDR_PTR, 0x00);
    sx1276_write_buffer(SX1276_REG_FIFO, data, length);
    sx1276_write(SX1276_REG_PAYLOAD_LENGTH, length);
    sx1276_write(SX1276_REG_IRQ_FLAGS, 0xFF);
    sx1276_write(SX1276_REG_DIO_MAPPING1, 0x40); /* DIO0 = TxDone */
    sx1276_set_mode(SX1276_MODE_TX);

    int64_t start_us = esp_timer_get_time();
    esp_err_t err = ESP_ERR_TIMEOUT;
    while ((esp_timer_get_time() - start_us) < ((int64_t)LORA_TX_TIMEOUT_MS * 1000)) {
        uint8_t irq = sx1276_read(SX1276_REG_IRQ_FLAGS);
        if (irq & SX1276_IRQ_TX_DONE) {
            err = ESP_OK;
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }

    sx1276_enter_receive();
    xSemaphoreGive(s_bus_lock);

    if (err != ESP_OK) {
        ESP_LOGW(TAG, "TX timed out");
    }
    return err;
}

int lora_radio_receive_bytes(uint8_t *data, uint8_t max_length, int timeout_ms,
                             lora_rx_info_t *info)
{
    if (!s_ready || data == NULL || max_length == 0) {
        return -1;
    }

    int64_t start_us = esp_timer_get_time();
    int64_t timeout_us = (int64_t)timeout_ms * 1000;

    while ((esp_timer_get_time() - start_us) < timeout_us) {
        if (xSemaphoreTake(s_bus_lock, pdMS_TO_TICKS(20)) != pdTRUE) {
            continue;
        }

        uint8_t irq = sx1276_read(SX1276_REG_IRQ_FLAGS);
        if (irq & SX1276_IRQ_RX_DONE) {
            if (irq & SX1276_IRQ_PAYLOAD_CRC_ERROR) {
                sx1276_write(SX1276_REG_IRQ_FLAGS, 0xFF);
                xSemaphoreGive(s_bus_lock);
                ESP_LOGW(TAG, "RX CRC error, packet dropped");
                return 0;
            }

            uint8_t length = sx1276_read(SX1276_REG_RX_NB_BYTES);
            uint8_t fifo_addr = sx1276_read(SX1276_REG_FIFO_RX_CURRENT);
            sx1276_write(SX1276_REG_FIFO_ADDR_PTR, fifo_addr);

            if (length > max_length) {
                length = max_length;
            }
            sx1276_read_buffer(SX1276_REG_FIFO, data, length);

            if (info != NULL) {
                int8_t snr_raw = (int8_t)sx1276_read(SX1276_REG_PKT_SNR_VALUE);
                uint8_t rssi_raw = sx1276_read(SX1276_REG_PKT_RSSI_VALUE);
                info->snr_db = snr_raw * 0.25f;
                /* HF port (868/915 MHz): RSSI = -157 + packetRSSI */
                info->rssi_dbm = (int16_t)(-157 + rssi_raw);
                if (info->snr_db < 0.0f) {
                    info->rssi_dbm = (int16_t)(info->rssi_dbm + info->snr_db);
                }
            }

            sx1276_write(SX1276_REG_IRQ_FLAGS, 0xFF);
            xSemaphoreGive(s_bus_lock);
            return length;
        }

        xSemaphoreGive(s_bus_lock);
        vTaskDelay(pdMS_TO_TICKS(10));
    }

    return 0;
}
