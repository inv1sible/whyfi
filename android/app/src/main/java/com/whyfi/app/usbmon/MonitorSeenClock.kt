package com.whyfi.app.usbmon

/**
 * Wall-clock first/last seen for USB devices (user, 2026-11-10: "last seen
 * also in tables / reports").
 *
 * The native capture stamps [SniffedDevices.Device.firstMs]/[lastMs] in ms
 * since that capture started, and [SniffedDevices.Device.lastRssiBootMs] on
 * Android's boot clock (`elapsedRealtime`). Where a device's last frame had a
 * signal reading the two coincide, so the capture's start on the boot clock
 * is the largest `lastRssiBootMs - lastMs`. A snapshot's `at_ms`/`clock_ms`
 * pair then maps the boot clock to wall time. Without any signal reading the
 * newest device is taken as heard at the snapshot (polled every 2 s).
 */
object MonitorSeenClock {
    data class Span(val firstMs: Long, val lastMs: Long)

    /** Capture start on the boot clock, or null when no device has a signal reading. */
    fun captureStartBootMs(devices: List<SniffedDevices.Device>): Long? =
        devices.mapNotNull { d -> d.lastRssiBootMs?.takeIf { it > 0 }?.let { it - d.lastMs } }.maxOrNull()

    /** Offset that turns capture-relative ms into wall ms, for devices listed at [wallMs] / [bootMs]. */
    fun offset(devices: List<SniffedDevices.Device>, wallMs: Long, bootMs: Long): Long? {
        if (devices.isEmpty()) return null
        val start = captureStartBootMs(devices)
        return if (start != null && bootMs > 0 && start <= bootMs) wallMs - bootMs + start
        else wallMs - devices.maxOf { it.lastMs }
    }

    fun spans(devices: List<SniffedDevices.Device>, wallMs: Long, bootMs: Long): Map<String, Span> {
        val off = offset(devices, wallMs, bootMs) ?: return emptyMap()
        return devices.associate { it.mac to Span(it.firstMs + off, (it.lastMs + off).coerceAtMost(wallMs)) }
    }

    /** Folds one snapshot into [into]: earliest first, latest last (continued missions restart the capture clock). */
    fun merge(into: MutableMap<String, Span>, snapshot: Map<String, Span>) {
        snapshot.forEach { (mac, span) ->
            val old = into[mac]
            into[mac] = if (old == null) span else Span(minOf(old.firstMs, span.firstMs), maxOf(old.lastMs, span.lastMs))
        }
    }

    fun fromSnapshots(snapshots: List<Pair<MonitorMission.DeviceSnapshot, List<SniffedDevices.Device>>>): Map<String, Span> {
        val out = HashMap<String, Span>()
        snapshots.forEach { (s, devices) -> merge(out, spans(devices, s.atMs, s.clockMs)) }
        return out
    }

    /** "now", "42s ago", "12m ago", "3h ago". */
    fun ago(ageMs: Long): String = when {
        ageMs < 1_000 -> "now"
        ageMs < 60_000 -> "${ageMs / 1_000}s ago"
        ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
        ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ${ageMs % 3_600_000 / 60_000}m ago"
        else -> "${ageMs / 86_400_000}d ago"
    }
}
