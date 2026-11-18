#pragma once

#include <stdbool.h>
#include <stddef.h>

void serial_console_init(void);
void serial_console_write_line(const char *text);
void serial_console_printf(const char *fmt, ...);
