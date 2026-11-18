#include "serial_console.h"

#include "board_pins.h"

#include "driver/uart.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static const char *TAG = "serial_console";
static SemaphoreHandle_t s_print_lock;

static void console_write(const char *data, size_t length)
{
    if (data == NULL || length == 0) {
        return;
    }
    uart_write_bytes(SERIAL_UART_PORT, data, length);
}

void serial_console_init(void)
{
    s_print_lock = xSemaphoreCreateMutex();

    const uart_config_t uart_cfg = {
        .baud_rate = SERIAL_UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    /* UART0 is already used for the USB serial monitor. */
    ESP_ERROR_CHECK(uart_driver_install(SERIAL_UART_PORT, 1024, 0, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(SERIAL_UART_PORT, &uart_cfg));

    ESP_LOGI(TAG, "USB serial ready at %d baud", SERIAL_UART_BAUD);
}

void serial_console_write_line(const char *text)
{
    if (s_print_lock != NULL) {
        xSemaphoreTake(s_print_lock, portMAX_DELAY);
    }
    if (text != NULL) {
        console_write(text, strlen(text));
    }
    console_write("\r\n", 2);
    if (s_print_lock != NULL) {
        xSemaphoreGive(s_print_lock);
    }
}

void serial_console_printf(const char *fmt, ...)
{
    char buf[192];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    if (n <= 0) {
        return;
    }
    if (n >= (int)sizeof(buf)) {
        n = (int)sizeof(buf) - 1;
    }

    if (s_print_lock != NULL) {
        xSemaphoreTake(s_print_lock, portMAX_DELAY);
    }
    console_write(buf, (size_t)n);
    if (s_print_lock != NULL) {
        xSemaphoreGive(s_print_lock);
    }
}
