package com.whyfi.app.usbmon

/**
 * The Scan tab's "⏱ Seen" filter (user, 2026-11-10): only devices heard in
 * the last few minutes of the capture. Measured from the newest device heard,
 * the same clock as the cards' "3m ago", so a replayed or stopped capture
 * filters the same way. Favorites always stay.
 */
object MonitorAgeFilter {
    /** Minutes offered; 0 = all devices. */
    val OPTIONS = listOf(0, 1, 2, 5, 10, 60)

    fun label(minutes: Int): String = if (minutes <= 0) "⏱ Seen: any time" else "⏱ Seen ≤ $minutes min"

    /** Devices of [rows] the filter leaves out, by MAC (for the map). */
    fun staleMacs(rows: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>, minutes: Int, favorites: Set<String>): Set<String> {
        if (minutes <= 0) return emptySet()
        val newest = rows.maxOfOrNull { it.first.lastMs } ?: return emptySet()
        return rows.filterNot { keep(it.first, newest, minutes, it.first.mac in favorites) }.mapTo(hashSetOf()) { it.first.mac }
    }

    fun keep(device: SniffedDevices.Device, newestMs: Long, minutes: Int, favorite: Boolean): Boolean =
        minutes <= 0 || favorite || newestMs - device.lastMs <= minutes * 60_000L
}
