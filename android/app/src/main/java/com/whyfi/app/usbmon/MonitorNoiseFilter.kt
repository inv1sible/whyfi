package com.whyfi.app.usbmon

/** Operator-only card filter. Raw frames and mission snapshots are never filtered. */
internal object MonitorNoiseFilter {
    data class Rules(val patterns: List<Regex>, val error: String? = null)

    fun compile(lines: String): Rules {
        val expressions = lines.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (expressions.size > 20) return Rules(emptyList(), "Use at most 20 patterns.")
        val patterns = mutableListOf<Regex>()
        expressions.forEachIndexed { index, expression ->
            if (expression.length > 120) return Rules(emptyList(), "Line ${index + 1} is too long (max 120 characters).")
            val pattern = runCatching { Regex(expression, RegexOption.IGNORE_CASE) }.getOrElse {
                return Rules(emptyList(), "Invalid pattern on line ${index + 1}: ${it.message ?: "syntax error"}")
            }
            patterns += pattern
        }
        return Rules(patterns)
    }

    fun matches(row: Pair<SniffedDevices.Device, SniffedDevices.Guess>, patterns: List<Regex>): Boolean {
        if (patterns.isEmpty()) return false
        val (device, guess) = row
        val label = SniffedDevices.displayName(device, guess)
        val searchable = listOf(label, device.mac, device.ssid, guess.vendor.orEmpty(),
            device.wpsName, SniffedDevices.activity(device)).joinToString(" ")
        return patterns.any { it.containsMatchIn(searchable) }
    }
}
