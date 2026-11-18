package com.example.messaging_app.protocol

/**
 * Compact printable ASCII framing for the LoRa / BLE pipe.
 *
 * CHAT|<from>|<text>
 * PRES|<from>|<displayName>
 * ACK|<from>|<msgId>
 * GPS|<fix>|<lat>|<lon>|<sats>|<speed_kmh>   (local T-Beam NEO-6M over BLE)
 * RADIO|<freqHz>|<sf>|<bwKhz>|<cr>|<txDbm>   (local radio status over BLE)
 */
object LoRaChatProtocol {
    const val MAX_PACKET_BYTES = 128
    private const val SEP = '|'

    sealed class Packet {
        data class Chat(val from: String, val text: String) : Packet()
        data class Presence(val from: String, val displayName: String) : Packet()
        data class Ack(val from: String, val messageId: String) : Packet()
        data class Gps(
            val hasFix: Boolean,
            val latitude: Double,
            val longitude: Double,
            val satellites: Int,
            val speedKmh: Float
        ) : Packet()
        data class Radio(
            val frequencyHz: Int,
            val spreadingFactor: Int,
            val bandwidthKhz: Int,
            val codingRate: Int,
            val txPowerDbm: Int
        ) : Packet()
        /** Firmware telemetry / noise that must never appear in chat. */
        data object Ignored : Packet()
        data class Plain(val text: String) : Packet()
    }

    fun encodeChat(from: String, text: String): String {
        val safeFrom = sanitizeToken(from, 12)
        val header = "CHAT$SEP$safeFrom$SEP"
        val budget = MAX_PACKET_BYTES - header.length
        val body = text.trim().take(budget.coerceAtLeast(0))
        return header + sanitizePrintable(body)
    }

    fun encodePresence(from: String, displayName: String): String {
        val safeFrom = sanitizeToken(from, 12)
        val name = sanitizePrintable(displayName).take(24)
        return "PRES$SEP$safeFrom$SEP$name".take(MAX_PACKET_BYTES)
    }

    fun parse(raw: String): Packet {
        val line = raw.trimEnd('\r', '\n').trim()
        if (line.isEmpty()) return Packet.Plain("")

        // Keep GPS / radio / NMEA out of the chat stream even when fields are incomplete.
        if (isTelemetryNoise(line)) {
            val type = line.substringBefore(SEP).uppercase()
            when (type) {
                "GPS" -> return parseGps(line) ?: Packet.Ignored
                "RADIO" -> return parseRadio(line) ?: Packet.Ignored
                else -> return Packet.Ignored
            }
        }

        val type = line.substringBefore(SEP)
        val parts = line.split(SEP, limit = 3)
        return when {
            type == "CHAT" && parts.size >= 3 -> {
                val body = parts[2].trim()
                if (!isReadableChatText(body)) Packet.Ignored
                else Packet.Chat(parts[1], body)
            }
            type == "PRES" && parts.size >= 3 -> Packet.Presence(parts[1], parts[2])
            type == "PRES" && parts.size == 2 -> Packet.Presence(parts[1], parts[1])
            type == "ACK" && parts.size >= 3 -> Packet.Ack(parts[1], parts[2])
            // Unframed noise / binary BLE leftovers must never become chat bubbles.
            isGarbledPayload(line) -> Packet.Ignored
            !isReadableChatText(line) -> Packet.Ignored
            else -> Packet.Plain(line)
        }
    }

    /**
     * True for local T-Beam telemetry that must not be shown as chat bubbles.
     * Covers framed GPS/RADIO packets and common NEO-6M NMEA sentences.
     */
    fun isTelemetryNoise(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return false
        val upper = line.uppercase()
        if (upper.startsWith("GPS|") || upper == "GPS" || upper.startsWith("GPS ")) return true
        if (upper.startsWith("RADIO|") || upper == "RADIO") return true
        // NMEA: $GPGGA, $GPRMC, $GNGGA, $GLGSV, etc.
        if (line.startsWith("$") && upper.length >= 4 && upper[1] == 'G') return true
        if (upper.startsWith("LAT:") || upper.startsWith("LON:") || upper.startsWith("LONG:")) return true
        if (upper.contains("LAT=") && upper.contains("LON")) return true
        return false
    }

    /**
     * Rejects binary / corrupted UART leftovers like "?.??????9)U?gg".
     * Allows normal human chat and plain ASCII from tools like nRF Connect.
     */
    fun isReadableChatText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        var printable = 0
        var alnumOrSpace = 0
        var questionMarks = 0
        var weird = 0
        var symbols = 0

        for (ch in t) {
            val code = ch.code
            when {
                code == '?'.code -> {
                    questionMarks++
                    printable++
                    symbols++
                }
                code in 32..126 -> {
                    printable++
                    when {
                        ch.isLetterOrDigit() || ch.isWhitespace() -> alnumOrSpace++
                        else -> symbols++
                    }
                }
                code == 9 || code == 10 || code == 13 -> alnumOrSpace++
                else -> weird++
            }
        }

        val len = t.length
        if (weird > 0 && weird.toFloat() / len > 0.08f) return false
        if (printable.toFloat() / len < 0.9f) return false

        // Lots of '?' usually means non-ASCII bytes were replaced while decoding.
        if (questionMarks >= 3 && questionMarks.toFloat() / len >= 0.2f) return false
        if (questionMarks >= 2 && alnumOrSpace.toFloat() / len < 0.4f) return false

        val alnum = t.count { it.isLetterOrDigit() }
        if (len >= 3 && alnum == 0) return false
        if (len >= 4 && alnum.toFloat() / len < 0.25f) return false
        if (len >= 4 && symbols.toFloat() / len > 0.55f) return false

        return true
    }

    fun isGarbledPayload(text: String): Boolean = !isReadableChatText(text)

    private fun parseGps(line: String): Packet.Gps? {
        // GPS|fix|lat|lon|sats|speed  (extra trailing fields tolerated)
        val parts = line.split(SEP)
        if (parts.size < 4) return null
        val hasFix = parts[1] == "1" || parts[1].equals("true", ignoreCase = true) ||
            parts[1].equals("FIX", ignoreCase = true)
        val lat = parts[2].toDoubleOrNull() ?: return null
        val lon = parts[3].toDoubleOrNull() ?: return null
        val sats = parts.getOrNull(4)?.toIntOrNull() ?: 0
        val speed = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
        return Packet.Gps(hasFix, lat, lon, sats, speed)
    }

    private fun parseRadio(line: String): Packet.Radio? {
        // RADIO|freqHz|sf|bwKhz|cr|txDbm
        val parts = line.split(SEP)
        if (parts.size < 6) return null
        val freq = parts[1].toIntOrNull() ?: return null
        val sf = parts[2].toIntOrNull() ?: return null
        val bw = parts[3].toIntOrNull() ?: return null
        val cr = parts[4].toIntOrNull() ?: return null
        val tx = parts[5].toIntOrNull() ?: return null
        return Packet.Radio(freq, sf, bw, cr, tx)
    }

    fun sanitizeToken(value: String, max: Int): String {
        val cleaned = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifBlank { "User" }
        return cleaned.take(max)
    }

    fun sanitizePrintable(value: String): String {
        return value.map { ch ->
            if (ch.code in 32..126) ch else '?'
        }.joinToString("")
    }
}
