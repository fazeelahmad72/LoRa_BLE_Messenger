#include "ble_uart.h"

#include "ble_app.h"
#include "ble_config.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_att.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"

#include <string.h>

static const char *TAG = "ble_uart";

static uint16_t s_tx_val_handle;
static bool s_notify_enabled;
static ble_uart_rx_callback_t s_rx_callback;
static ble_uart_notify_callback_t s_notify_callback;

/* Nordic UART Service UUID */
static const ble_uuid128_t s_uart_svc_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);

/* RX: phone writes to ESP32 */
static const ble_uuid128_t s_uart_rx_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);

/* TX: ESP32 notifies phone */
static const ble_uuid128_t s_uart_tx_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);

void ble_uart_set_rx_callback(ble_uart_rx_callback_t callback)
{
    s_rx_callback = callback;
}

void ble_uart_set_notify_callback(ble_uart_notify_callback_t callback)
{
    s_notify_callback = callback;
}

static int uart_chr_access(uint16_t conn_handle_in, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle_in;
    (void)attr_handle;
    (void)arg;

    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_WRITE_CHR: {
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        char buf[BLE_LINE_MAX + 1];

        if (len > BLE_LINE_MAX) {
            len = BLE_LINE_MAX;
        }

        ble_hs_mbuf_to_flat(ctxt->om, buf, len, NULL);
        buf[len] = '\0';

        ESP_LOGI(TAG, "From phone (%u bytes): %s", len, buf);

        if (s_rx_callback != NULL && len > 0) {
            s_rx_callback(buf, len);
        }
        return 0;
    }

    case BLE_GATT_ACCESS_OP_READ_CHR:
        return 0;

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

void ble_uart_send(const char *msg)
{
    if (msg == NULL) {
        return;
    }

    if (!ble_app_is_connected() || !s_notify_enabled) {
        ESP_LOGW(TAG, "Cannot send: connected=%d notify=%d",
                 (int)ble_app_is_connected(), (int)s_notify_enabled);
        return;
    }

    size_t total = strlen(msg);
    size_t max_payload = ble_app_max_payload();
    size_t offset = 0;

    while (offset < total) {
        size_t chunk = total - offset;
        if (chunk > max_payload) {
            chunk = max_payload;
        }

        struct os_mbuf *om = ble_hs_mbuf_from_flat(msg + offset, chunk);
        if (om == NULL) {
            ESP_LOGE(TAG, "mbuf alloc failed");
            return;
        }

        int rc = ble_gatts_notify_custom(ble_app_conn_handle(), s_tx_val_handle, om);
        if (rc != 0) {
            ESP_LOGE(TAG, "notify failed; rc=%d", rc);
            return;
        }

        offset += chunk;
        if (offset < total) {
            vTaskDelay(pdMS_TO_TICKS(20));
        }
    }

    ESP_LOGI(TAG, "To phone: %s", msg);
}

void ble_uart_on_link_reset(void)
{
    s_notify_enabled = false;
}

void ble_uart_on_subscribe(uint16_t attr_handle, bool notify)
{
    if (attr_handle == s_tx_val_handle) {
        s_notify_enabled = notify;
        ESP_LOGI(TAG, "Notify %s", s_notify_enabled ? "ENABLED" : "DISABLED");
        if (s_notify_callback != NULL) {
            s_notify_callback(s_notify_enabled);
        }
    }
}

static const struct ble_gatt_chr_def s_uart_chrs[] = {
    {
        .uuid = &s_uart_rx_uuid.u,
        .access_cb = uart_chr_access,
        .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
    },
    {
        .uuid = &s_uart_tx_uuid.u,
        .access_cb = uart_chr_access,
        .flags = BLE_GATT_CHR_F_NOTIFY,
        .val_handle = &s_tx_val_handle,
    },
    { 0 },
};

static const struct ble_gatt_svc_def s_gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_uart_svc_uuid.u,
        .characteristics = s_uart_chrs,
    },
    { 0 },
};

int ble_uart_count_cfg(void)
{
    return ble_gatts_count_cfg(s_gatt_svcs);
}

int ble_uart_add_svcs(void)
{
    return ble_gatts_add_svcs(s_gatt_svcs);
}
