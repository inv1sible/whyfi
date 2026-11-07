package com.whyfi.app.scan

import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's public IP as seen from the internet, from ident.me (plain
 * text, like `curl ident.me`). Fetched after scan passes at most every
 * [REFRESH_MS], so a continuous scan doesn't call out every 30 s.
 */
object PublicIp {
    const val URL_TEXT = "https://ident.me"
    const val REFRESH_MS = 5 * 60_000L

    fun fetch(): String? = runCatching {
        val connection = URL(URL_TEXT).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", "whyfi-android")
        try {
            if (connection.responseCode != 200) return null
            // Bounded read (an address is < 64 bytes); readNBytes needs API 33.
            val buffer = ByteArray(64)
            var length = 0
            connection.inputStream.use { input ->
                while (length < buffer.size) {
                    val n = input.read(buffer, length, buffer.size - length)
                    if (n < 0) break
                    length += n
                }
            }
            parse(String(buffer, 0, length, Charsets.US_ASCII))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** The address if [text] is exactly one IPv4 or IPv6 address, else null
     * (a captive portal's HTML page must not show up as an "IP"). */
    fun parse(text: String): String? {
        val t = text.trim()
        val v4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(t)
        if (v4 != null) return t.takeIf { v4.groupValues.drop(1).all { it.toInt() <= 255 } }
        val v6 = Regex("""[0-9a-fA-F:]{2,39}""").matchEntire(t)
        return t.takeIf { v6 != null && t.count { it == ':' } >= 2 }
    }

    fun due(lastAtMs: Long, nowMs: Long): Boolean = nowMs - lastAtMs >= REFRESH_MS
}
