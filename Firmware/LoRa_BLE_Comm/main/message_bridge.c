#include "message_bridge.h"

#include "ble_config.h"
#include "ble_uart.h"
#include "lora_radio.h"
#include "lora_settings.h"
#include "serial_console.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include <stdio.h>
#include <string.h>

static const char *TAG = "message_bridge";

#define BRIDGE_TASK_STACK_BYTES   4096
#define BRIDGE_TASK_PRIORITY      5
#define BLE_TO_LORA_QUEUE_DEPTH   8

typedef struct {
    uint8_t length;
    char text[LORA_MAX_PACKET_BYTES + 1];
} bridge_message_t;

static QueueHandle_t s_ble_to_lora_queue;

static void make_printable(uint8_t *buf, int length)
{
    for (int i = 0; i < length; i++) {
        if (buf[i] < 32 || buf[i] > 126) {
            buf[i] = '?';
        }
    }
}

/* Push compile-time radio params so the phone Settings tab can display them. */
static void send_radio_status(void)
{
    char msg[96];
    snprintf(msg, sizeof(msg), "RADIO|%d|%d|%d|%d|%d",
             LORA_FREQUENCY_HZ,
             LORA_SPREADING_FACTOR,
             LORA_BANDWIDTH_KHZ,
             LORA_CODING_RATE,
             LORA_TX_POWER_DBM);
    ble_uart_send(msg);
}

static void radio_status_task(void *arg)
{
    (void)arg;
    /* Let CCCD / MTU settle before the first notify. */
    vTaskDelay(pdMS_TO_TICKS(400));
    send_radio_status();
    vTaskDelete(NULL);
}

static void on_ble_notify_changed(bool notify_enabled)
{
    if (!notify_enabled) {
        return;
    }
    xTaskCreate(radio_status_task, "radio_status", 3072, NULL, BRIDGE_TASK_PRIORITY, NULL);
}

/* Called from BLE GATT write callback — keep it short, queue the text. */
static void on_ble_message_received(const char *text, size_t length)
{
    if (text == NULL || length == 0 || s_ble_to_lora_queue == NULL) {
        return;
    }

    /* Drop trailing CR/LF that some phone apps append. */
    while (length > 0 && (text[length - 1] == '\r' || text[length - 1] == '\n')) {
        length--;
    }
    if (length == 0) {
        return;
    }

    bridge_message_t msg = {0};
    if (length > LORA_MAX_PACKET_BYTES) {
        length = LORA_MAX_PACKET_BYTES;
    }

    msg.length = (uint8_t)length;
    memcpy(msg.text, text, length);
    msg.text[length] = '\0';

    if (xQueueSend(s_ble_to_lora_queue, &msg, 0) != pdTRUE) {
        ESP_LOGW(TAG, "BLE->LoRa queue full, message dropped");
    }
}

/* Phone wrote over BLE → send out over LoRa */
static void ble_to_lora_task(void *arg)
{
    (void)arg;
    bridge_message_t msg;

    while (1) {
        if (xQueueReceive(s_ble_to_lora_queue, &msg, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        esp_err_t err = lora_radio_send_bytes((const uint8_t *)msg.text, msg.length);
        if (err == ESP_OK) {
            serial_console_printf("[BLE->LoRa TX] %s\r\n", msg.text);
            ESP_LOGI(TAG, "Sent over LoRa: %s", msg.text);
        } else {
            serial_console_printf("[BLE->LoRa FAILED] %s\r\n", msg.text);
            ESP_LOGW(TAG, "LoRa TX failed for: %s", msg.text);
        }
    }
}

/* LoRa packet arrived → notify phone over BLE */
static void lora_to_ble_task(void *arg)
{
    (void)arg;
    uint8_t packet[LORA_MAX_PACKET_BYTES + 1];
    lora_rx_info_t info;

    while (1) {
        int length = lora_radio_receive_bytes(packet, LORA_MAX_PACKET_BYTES, 80, &info);
        if (length <= 0) {
            continue;
        }

        make_printable(packet, length);
        packet[length] = '\0';

        serial_console_printf("[LoRa RX] rssi=%d dBm snr=%.1f dB | %s\r\n",
                              (int)info.rssi_dbm, info.snr_db, packet);
        ESP_LOGI(TAG, "Received over LoRa: %s", packet);

        ble_uart_send((const char *)packet);
    }
}

void message_bridge_start(void)
{
    s_ble_to_lora_queue = xQueueCreate(BLE_TO_LORA_QUEUE_DEPTH, sizeof(bridge_message_t));
    if (s_ble_to_lora_queue == NULL) {
        ESP_LOGE(TAG, "Could not create BLE->LoRa queue");
        return;
    }

    ble_uart_set_rx_callback(on_ble_message_received);
    ble_uart_set_notify_callback(on_ble_notify_changed);

    serial_console_write_line("");
    serial_console_write_line("========================================");
    serial_console_write_line(" T-Beam BLE <-> LoRa bridge");
    serial_console_printf(" BLE name  : %s\r\n", DEVICE_NAME);
    serial_console_printf(" Frequency : %d Hz\r\n", LORA_FREQUENCY_HZ);
    serial_console_printf(" LoRa      : SF%d / %d kHz / 4/%d\r\n",
                          LORA_SPREADING_FACTOR, LORA_BANDWIDTH_KHZ, LORA_CODING_RATE);
    serial_console_printf(" Power     : %d dBm\r\n", LORA_TX_POWER_DBM);
    serial_console_write_line("========================================");
    serial_console_printf("1. Connect nRF Connect to %s\r\n", DEVICE_NAME);
    serial_console_write_line("2. Enable Notify on TX characteristic");
    serial_console_write_line("3. Write text to RX — it goes out over LoRa");
    serial_console_write_line("4. LoRa RX messages appear on phone via Notify");
    serial_console_write_line("5. GPS reports: GPS|fix|lat|lon|sats|speed_kmh");
    serial_console_write_line("6. Radio status: RADIO|freq|sf|bw|cr|tx_dbm");
    serial_console_write_line("");

    BaseType_t ok;
    ok = xTaskCreate(ble_to_lora_task, "ble_to_lora",
                     BRIDGE_TASK_STACK_BYTES, NULL, BRIDGE_TASK_PRIORITY, NULL);
    if (ok != pdPASS) {
        ESP_LOGE(TAG, "Could not start ble_to_lora task");
        return;
    }

    ok = xTaskCreate(lora_to_ble_task, "lora_to_ble",
                     BRIDGE_TASK_STACK_BYTES, NULL, BRIDGE_TASK_PRIORITY, NULL);
    if (ok != pdPASS) {
        ESP_LOGE(TAG, "Could not start lora_to_ble task");
    }
}
