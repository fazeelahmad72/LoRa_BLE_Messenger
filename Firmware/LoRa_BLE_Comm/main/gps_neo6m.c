#include "gps_neo6m.h"

#include "ble_app.h"
#include "ble_uart.h"
#include "board_pins.h"
#include "serial_console.h"

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *TAG = "gps_neo6m";

#define GPS_TASK_STACK_BYTES     4096
#define GPS_TASK_PRIORITY        4
#define GPS_UART_BUF_BYTES       1024
#define GPS_NMEA_LINE_MAX        128
#define GPS_BLE_REPORT_MS        2000
#define GPS_STATUS_LOG_MS        10000

static SemaphoreHandle_t s_fix_mutex;
static gps_fix_t s_fix;
static bool s_started;

static double nmea_to_degrees(const char *field, char hemi)
{
    if (field == NULL || field[0] == '\0') {
        return 0.0;
    }

    double raw = atof(field);
    int degrees = (int)(raw / 100.0);
    double minutes = raw - (degrees * 100.0);
    double decimal = degrees + (minutes / 60.0);

    if (hemi == 'S' || hemi == 'W') {
        decimal = -decimal;
    }
    return decimal;
}

static bool sentence_has_type(const char *line, const char *type3)
{
    /* Accept $GPxxx / $GNxxx / $GLxxx */
    if (line == NULL || line[0] != '$' || strlen(line) < 6) {
        return false;
    }
    return (line[3] == type3[0] && line[4] == type3[1] && line[5] == type3[2]);
}

static int split_csv(char *line, char *fields[], int max_fields)
{
    int count = 0;
    char *p = line;
    while (count < max_fields) {
        fields[count++] = p;
        char *comma = strchr(p, ',');
        if (comma == NULL) {
            break;
        }
        *comma = '\0';
        p = comma + 1;
    }
    return count;
}

static void strip_checksum(char *line)
{
    char *star = strchr(line, '*');
    if (star != NULL) {
        *star = '\0';
    }
}

static void apply_gga(char *line)
{
    char *fields[16] = {0};
    strip_checksum(line);
    int n = split_csv(line, fields, 16);
    if (n < 8) {
        return;
    }

    /* $GPGGA,time,lat,N,lon,E,fix,sats,... */
    int fix_quality = atoi(fields[6]);
    int sats = atoi(fields[7]);
    bool valid = (fix_quality > 0);

    double lat = 0.0;
    double lon = 0.0;
    if (valid) {
        lat = nmea_to_degrees(fields[2], fields[3][0]);
        lon = nmea_to_degrees(fields[4], fields[5][0]);
    }

    if (xSemaphoreTake(s_fix_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        s_fix.valid = valid;
        s_fix.satellites = sats;
        if (valid) {
            s_fix.latitude = lat;
            s_fix.longitude = lon;
        }
        xSemaphoreGive(s_fix_mutex);
    }
}

static void apply_rmc(char *line)
{
    char *fields[14] = {0};
    strip_checksum(line);
    int n = split_csv(line, fields, 14);
    if (n < 8) {
        return;
    }

    /* $GPRMC,time,status,lat,N,lon,E,speed_knots,... */
    bool valid = (fields[2][0] == 'A');
    float speed_kmh = 0.0f;
    if (fields[7][0] != '\0') {
        speed_kmh = (float)(atof(fields[7]) * 1.852);
    }

    double lat = 0.0;
    double lon = 0.0;
    if (valid && n >= 7) {
        lat = nmea_to_degrees(fields[3], fields[4][0]);
        lon = nmea_to_degrees(fields[5], fields[6][0]);
    }

    if (xSemaphoreTake(s_fix_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        s_fix.speed_kmh = speed_kmh;
        if (valid) {
            s_fix.valid = true;
            s_fix.latitude = lat;
            s_fix.longitude = lon;
        }
        xSemaphoreGive(s_fix_mutex);
    }
}

static void handle_nmea_line(char *line)
{
    if (sentence_has_type(line, "GGA")) {
        apply_gga(line);
    } else if (sentence_has_type(line, "RMC")) {
        apply_rmc(line);
    }
}

static void publish_ble_report(const gps_fix_t *fix)
{
    if (!ble_app_is_connected()) {
        return;
    }

    char msg[96];
    if (fix->valid) {
        snprintf(msg, sizeof(msg), "GPS|1|%.6f|%.6f|%d|%.1f",
                 fix->latitude, fix->longitude, fix->satellites, fix->speed_kmh);
    } else {
        snprintf(msg, sizeof(msg), "GPS|0|0|0|%d|0.0", fix->satellites);
    }
    ble_uart_send(msg);
}

static void gps_task(void *arg)
{
    (void)arg;
    uint8_t byte;
    char line[GPS_NMEA_LINE_MAX];
    size_t line_len = 0;
    TickType_t last_report = 0;
    TickType_t last_status = 0;

    while (1) {
        int n = uart_read_bytes(GPS_UART_PORT, &byte, 1, pdMS_TO_TICKS(100));
        if (n == 1) {
            if (byte == '\n') {
                if (line_len > 0) {
                    line[line_len] = '\0';
                    if (line[0] == '$') {
                        handle_nmea_line(line);
                    }
                    line_len = 0;
                }
            } else if (byte != '\r') {
                if (line_len < (GPS_NMEA_LINE_MAX - 1)) {
                    line[line_len++] = (char)byte;
                } else {
                    line_len = 0;
                }
            }
        }

        TickType_t now = xTaskGetTickCount();
        if ((now - last_report) >= pdMS_TO_TICKS(GPS_BLE_REPORT_MS)) {
            last_report = now;
            gps_fix_t snapshot = {0};
            if (gps_neo6m_get_fix(&snapshot)) {
                publish_ble_report(&snapshot);
            }
        }

        if ((now - last_status) >= pdMS_TO_TICKS(GPS_STATUS_LOG_MS)) {
            last_status = now;
            gps_fix_t snapshot = {0};
            if (gps_neo6m_get_fix(&snapshot)) {
                if (snapshot.valid) {
                    serial_console_printf("[GPS] fix lat=%.6f lon=%.6f sats=%d speed=%.1f km/h\r\n",
                                          snapshot.latitude, snapshot.longitude,
                                          snapshot.satellites, snapshot.speed_kmh);
                } else {
                    serial_console_printf("[GPS] searching… sats=%d\r\n", snapshot.satellites);
                }
            }
        }
    }
}

esp_err_t gps_neo6m_init(void)
{
    if (s_fix_mutex == NULL) {
        s_fix_mutex = xSemaphoreCreateMutex();
        if (s_fix_mutex == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }

    /* GPIO12 is a strapping pin — keep TX idle low before UART attach. */
    gpio_set_direction(PIN_GPS_TX, GPIO_MODE_OUTPUT);
    gpio_set_level(PIN_GPS_TX, 0);

    uart_config_t cfg = {
        .baud_rate = GPS_UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    esp_err_t err = uart_driver_install(GPS_UART_PORT, GPS_UART_BUF_BYTES, 0, 0, NULL, 0);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "uart_driver_install failed: %s", esp_err_to_name(err));
        return err;
    }

    err = uart_param_config(GPS_UART_PORT, &cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_param_config failed: %s", esp_err_to_name(err));
        return err;
    }

    err = uart_set_pin(GPS_UART_PORT, PIN_GPS_TX, PIN_GPS_RX, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_set_pin failed: %s", esp_err_to_name(err));
        return err;
    }

    memset(&s_fix, 0, sizeof(s_fix));
    ESP_LOGI(TAG, "NEO-6M UART ready (RX=%d TX=%d @ %d)", PIN_GPS_RX, PIN_GPS_TX, GPS_UART_BAUD);
    return ESP_OK;
}

void gps_neo6m_start(void)
{
    if (s_started) {
        return;
    }
    s_started = true;

    BaseType_t ok = xTaskCreate(gps_task, "gps_neo6m",
                                GPS_TASK_STACK_BYTES, NULL, GPS_TASK_PRIORITY, NULL);
    if (ok != pdPASS) {
        ESP_LOGE(TAG, "Could not start GPS task");
        s_started = false;
        return;
    }

    serial_console_write_line("GPS NEO-6M started — waiting for fix (outdoor sky view helps)");
    ESP_LOGI(TAG, "GPS task started");
}

bool gps_neo6m_get_fix(gps_fix_t *out)
{
    if (out == NULL || s_fix_mutex == NULL) {
        return false;
    }
    if (xSemaphoreTake(s_fix_mutex, pdMS_TO_TICKS(50)) != pdTRUE) {
        return false;
    }
    *out = s_fix;
    xSemaphoreGive(s_fix_mutex);
    return true;
}
