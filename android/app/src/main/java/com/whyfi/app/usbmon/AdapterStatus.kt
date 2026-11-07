package com.whyfi.app.usbmon

import java.util.Locale

/** Text for the USB adapter workspace's status strip and the capture
 * notification. Pure, unit-tested (AdapterStatusTest). */
object AdapterStatus {

    /** "ch 1, 6, 11" for a few channels, "13 channels" for many. */
    fun channels(channels: List<Int>): String = when {
        channels.isEmpty() -> "no channels"
        channels.size <= MAX_LISTED -> "ch " + channels.sorted().joinToString(", ")
        else -> "${channels.size} channels"
    }

    /** m:ss below an hour, h:mm:ss above. */
    fun elapsed(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s < 3600) String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        else String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} kB"
        else -> "$bytes B"
    }

    fun devices(count: Int): String = String.format(Locale.US, "%,d device%s", count, if (count == 1) "" else "s")

    /** The notification while a capture runs: says plainly that this is
     * the adapter, not a scan pass of the phone's own radios. */
    fun notification(channels: List<Int>, deviceCount: Int): String =
        "Monitor capture · ${channels(channels)} · ${devices(deviceCount)}"

    /** The strip under the workspace switch. [adapter] is the attached
     * adapter's description, or null when none is plugged in. */
    fun strip(
        running: Boolean,
        adapter: String?,
        channels: List<Int>,
        elapsedMs: Long,
        pcapBytes: Long,
        deviceCount: Int,
    ): String = when {
        running -> listOf(
            "● REC ${elapsed(elapsedMs)}",
            channels(channels),
            devices(deviceCount),
            if (pcapBytes > 0) "PCAP ${bytes(pcapBytes)}" else null,
        ).filterNotNull().joinToString(" · ")
        adapter != null -> "Adapter ready · $adapter"
        else -> "No adapter connected · missions and maps still open"
    }

    private const val MAX_LISTED = 4
}
