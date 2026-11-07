package com.whyfi.app.lan

/**
 * What a LAN sweep covers: the automatic choice (WiFi first, then wired),
 * the WiFi network only (what the LAN screen offers), one named
 * interface's subnet, or an address range typed by the user.
 */
sealed interface LanTarget {
    data object Auto : LanTarget
    data object Wifi : LanTarget
    data class Interface(val name: String) : LanTarget
    data class Custom(val text: String) : LanTarget
}

/**
 * Parses a custom sweep range. Pure, unit-tested (LanRangeTest).
 *
 * Accepted forms: `192.168.1.0/24`, `192.168.1.10-192.168.1.50`,
 * `192.168.1.10-50` (last octet only) and a single address.
 *
 * Only private address space is accepted (RFC 1918 and the 100.64.0.0/10
 * shared range): this is a LAN scanner, and a TCP-connect sweep of public
 * addresses would be probing other people's machines.
 */
object LanRange {

    sealed interface Parsed
    /** Inclusive range of IPv4 addresses as unsigned 32-bit values. */
    data class Ok(val first: Long, val last: Long) : Parsed {
        val count: Int get() = (last - first + 1).toInt()
        fun describe(): String =
            if (first == last) format(first) else "${format(first)} – ${format(last)} · $count addresses"
        fun addresses(): List<Long> = (first..last).toList()
    }
    data class Invalid(val reason: String) : Parsed

    fun parse(input: String, maxHosts: Int = LanScanner.MAX_HOSTS_TO_SCAN): Parsed {
        val text = input.trim()
        if (text.isEmpty()) return Invalid("Enter a range, e.g. 192.168.1.0/24 or 192.168.1.10-50")
        val range = when {
            '/' in text -> cidr(text)
            '-' in text -> dashed(text)
            else -> ip(text)?.let { Ok(it, it) } ?: Invalid("“$text” is not an IPv4 address")
        }
        if (range !is Ok) return range
        if (range.last < range.first) return Invalid("The range ends before it starts")
        if (!isPrivate(range.first) || !isPrivate(range.last) || !samePrivateBlock(range.first, range.last)) {
            return Invalid("Only private ranges can be swept (10.x, 172.16–31.x, 192.168.x, 100.64–127.x)")
        }
        if (range.count > maxHosts) return Invalid("That range has ${range.count} addresses; at most $maxHosts per sweep")
        return range
    }

    fun ip(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun format(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }

    fun toBytes(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
    )

    private fun cidr(text: String): Parsed {
        val (addrText, prefixText) = text.split('/', limit = 2)
        val addr = ip(addrText) ?: return Invalid("“${addrText.trim()}” is not an IPv4 address")
        val prefix = prefixText.trim().toIntOrNull()?.takeIf { it in 0..32 }
            ?: return Invalid("“/${prefixText.trim()}” is not a prefix length (0–32)")
        val hostBits = 32 - prefix
        val network = addr and (0xFFFFFFFFL shl hostBits and 0xFFFFFFFFL)
        val broadcast = network + (1L shl hostBits) - 1
        // Network and broadcast addresses aren't hosts, except on /31 and /32.
        return if (prefix >= 31) Ok(network, broadcast) else Ok(network + 1, broadcast - 1)
    }

    private fun dashed(text: String): Parsed {
        val (startText, endText) = text.split('-', limit = 2).map { it.trim() }
        val start = ip(startText) ?: return Invalid("“$startText” is not an IPv4 address")
        val end = ip(endText)
            ?: endText.toIntOrNull()?.takeIf { endText.all(Char::isDigit) && it in 0..255 }?.let { (start and 0xFFFFFF00L) or it.toLong() }
            ?: return Invalid("“$endText” is not an address or a last octet (0–255)")
        return Ok(start, end)
    }

    private val PRIVATE_BLOCKS = listOf(
        0x0A000000L to 8,  // 10.0.0.0/8
        0xAC100000L to 12, // 172.16.0.0/12
        0xC0A80000L to 16, // 192.168.0.0/16
        0x64400000L to 10, // 100.64.0.0/10
    )

    private fun blockOf(value: Long): Int = PRIVATE_BLOCKS.indexOfFirst { (base, prefix) ->
        (value shr (32 - prefix)) == (base shr (32 - prefix))
    }

    fun isPrivate(value: Long): Boolean = blockOf(value) >= 0
    private fun samePrivateBlock(a: Long, b: Long): Boolean = blockOf(a) == blockOf(b)
}
