#include "ble_app.h"

#include "ble_config.h"
#include "ble_uart.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_att.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include <assert.h>
#include <string.h>

static const char *TAG = "ble_app";

static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_current_mtu = 23;

static void ble_app_advertise(void);

uint16_t ble_app_conn_handle(void)
{
    return s_conn_handle;
}

bool ble_app_is_connected(void)
{
    return s_conn_handle != BLE_HS_CONN_HANDLE_NONE;
}

uint16_t ble_app_mtu(void)
{
    return s_current_mtu;
}

uint16_t ble_app_max_payload(void)
{
    if (s_current_mtu < 23) {
        return 20;
    }
    return s_current_mtu - 3;
}

static int ble_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            s_current_mtu = 23;
            ESP_LOGI(TAG, "Phone connected (handle=%d)", s_conn_handle);

            ble_uart_on_link_reset();

            int rc = ble_gattc_exchange_mtu(s_conn_handle, NULL, NULL);
            if (rc != 0) {
                ESP_LOGW(TAG, "MTU exchange request failed; rc=%d", rc);
            }
        } else {
            ESP_LOGE(TAG, "Connect failed; status=%d", event->connect.status);
            ble_app_advertise();
        }
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "Phone disconnected (reason=%d)", event->disconnect.reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_current_mtu = 23;
        ble_uart_on_link_reset();
        ble_app_advertise();
        break;

    case BLE_GAP_EVENT_MTU:
        s_current_mtu = event->mtu.value;
        ESP_LOGI(TAG, "MTU updated to %u (payload up to %u bytes)",
                 s_current_mtu, ble_app_max_payload());
        break;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ble_uart_on_subscribe(event->subscribe.attr_handle, event->subscribe.cur_notify);
        break;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ble_app_advertise();
        break;

    default:
        break;
    }

    return 0;
}

static void ble_app_advertise(void)
{
    struct ble_hs_adv_fields fields = {0};
    const char *name = ble_svc_gap_device_name();

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv fields failed; rc=%d", rc);
        return;
    }

    struct ble_gap_adv_params adv = {0};
    adv.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER,
                           &adv, ble_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv start failed; rc=%d", rc);
        return;
    }

    ESP_LOGI(TAG, "Advertising as \"%s\"", name);
}

static void ble_on_sync(void)
{
    ble_att_set_preferred_mtu(PREFERRED_MTU);
    ble_app_advertise();
}

static void ble_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_app_init(void)
{
    ESP_ERROR_CHECK(nimble_port_init());

    ble_hs_cfg.sync_cb = ble_on_sync;
    ble_att_set_preferred_mtu(PREFERRED_MTU);

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set(DEVICE_NAME);

    int rc = ble_uart_count_cfg();
    assert(rc == 0);

    rc = ble_uart_add_svcs();
    assert(rc == 0);

    nimble_port_freertos_init(ble_host_task);
    ESP_LOGI(TAG, "BLE ready — device name \"%s\"", DEVICE_NAME);
}
