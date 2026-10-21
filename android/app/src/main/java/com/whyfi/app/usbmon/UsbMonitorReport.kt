package com.whyfi.app.usbmon

import com.whyfi.app.usbmon.UsbMonitorNative.Stat

/**
 * SPIKE — formats one USB monitor test run as plain text, for the screen
 * and for upload through the crash-report channel. Pure (no Android types)
 * so it is JVM-testable.
 */
object UsbMonitorReport {

    data class Run(
        val device: String,
        val androidInfo: String,
        val appVersion: String,
        val result: String?,
        val durationMs: Long,
        val stats: LongArray,
        val perChannel: LongArray,
        val trace: String?,
        val logcat: String?,
        val mode: String = "",
        val devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> = emptyList(),
        /** MACs and network names only when the user opted in. */
        val includeDeviceDetails: Boolean = false,
        /** Vendor prefixes loaded from the asset; 0 = table missing. */
        val vendorPrefixes: Int = 0,
    )

    fun stat(stats: LongArray, s: Stat): Long = stats.getOrElse(s.ordinal) { 0 }

    fun stageName(stats: LongArray): String =
        UsbMonitorNative.STAGES.getOrElse(stat(stats, Stat.STAGE).toInt()) { "stage ${stat(stats, Stat.STAGE)}" }

    /** Live summary lines (also the core of the report). */
    fun summary(stats: LongArray): List<String> {
        val total = stat(stats, Stat.TOTAL)
        val lines = mutableListOf(
            "Stage: ${stageName(stats)}",
            "Frames: $total (mgmt ${stat(stats, Stat.MGMT)}, ctrl ${stat(stats, Stat.CTRL)}, data ${stat(stats, Stat.DATA)})",
            "Beacons: ${stat(stats, Stat.BEACON)}, probe responses: ${stat(stats, Stat.PROBE_RESP)}",
            "Probe requests: ${stat(stats, Stat.PROBE_REQ)} from ${stat(stats, Stat.PROBE_REQ_SOURCES)} distinct MACs " +
                "(${stat(stats, Stat.PROBE_REQ_LOCAL_ADMIN)} randomized), ${stat(stats, Stat.PROBE_REQ_DIRECTED)} naming a network",
            "Client→AP pairs seen in data frames: ${stat(stats, Stat.CLIENTS_TO_AP)}",
        )
        if (total > 0 && stat(stats, Stat.RSSI_MAX) >= stat(stats, Stat.RSSI_MIN)) {
            lines += "Signal: ${stat(stats, Stat.RSSI_MIN)} … ${stat(stats, Stat.RSSI_MAX)} dBm " +
                "(${stat(stats, Stat.NO_RSSI)} frames without a reading)"
        }
        val match = stat(stats, Stat.BEACON_CH_MATCH)
        val other = stat(stats, Stat.BEACON_CH_OTHER)
        if (match + other > 0) {
            lines += "Tuning check: $match of ${match + other} beacons announce the channel we were on"
        }
        if (stat(stats, Stat.BAD_FCS_OR_SHORT) > 0) {
            lines += "Corrupted frames dropped: ${stat(stats, Stat.BAD_FCS_OR_SHORT)}"
        }
        val ch = stat(stats, Stat.CURRENT_CHANNEL)
        if (ch > 0) lines += "Current channel: $ch"
        return lines
    }

    fun perChannelLine(flat: LongArray): String =
        if (flat.isEmpty()) "none" else flat.toList().chunked(2).joinToString(", ") { (ch, n) -> "ch$ch=$n" }

    /** Aggregates only: kinds, behaviour and vendors — no MACs or network names. */
    fun deviceSummary(devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>): String = buildString {
        append("Devices: ${devices.size}\n")
        SniffedDevices.Kind.entries.forEach { kind ->
            val n = devices.count { it.second.kind == kind }
            if (n > 0) append("  ${kind.label}: $n\n")
        }
        val clients = devices.filter { it.first.isClient }
        append("Clients: ${clients.size} (searching: ${clients.count { it.first.probeRequests > 0 }}, ")
        append("connected: ${clients.count { it.first.bssids.isNotEmpty() }}, ")
        append("searching and not connected: ${clients.count { it.first.probeRequests > 0 && it.first.bssids.isEmpty() }}, ")
        append("private MAC: ${clients.count { it.first.randomized }})\n")
        append("Announce a WPS identity: ${devices.count { it.first.wps.isNotEmpty() }}; ")
        append("upload continuously: ${devices.count { it.second.reasons.any { r -> r.startsWith("uploads continuously") } }}\n")
        val vendors = clients.mapNotNull { it.second.vendor }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(15)
        if (vendors.isNotEmpty()) {
            append("Client vendors: ").append(vendors.joinToString(", ") { "${it.key} ×${it.value}" }).append('\n')
        }
    }.trimEnd()

    fun deviceLine(d: SniffedDevices.Device, g: SniffedDevices.Guess): String = buildString {
        append("${g.kind.label} ${d.mac}")
        d.bestRssi?.let { append(" $it dBm") }
        append(" ch ${d.channels.joinToString(",")}")
        if (d.ssid.isNotEmpty()) append(" \"${d.ssid}\"")
        SniffedDevices.activity(d).takeIf { it.isNotEmpty() }?.let { append(" — $it") }
        if (g.reasons.isNotEmpty()) append(" [${g.reasons.joinToString("; ")}]")
    }

    fun format(run: Run): String = buildString {
        append("USB monitor-mode test (spike) — diagnostic report, not a crash\n")
        append("Result: ${if (run.result.isNullOrEmpty()) "OK" else "FAILED — ${run.result}"}\n")
        append("Device: ${run.device}\n")
        append("Phone: ${run.androidInfo}, app ${run.appVersion}\n")
        if (run.mode.isNotEmpty()) append("Mode: ${run.mode}\n")
        append("Duration: ${run.durationMs / 1000} s\n\n")
        summary(run.stats).forEach { append(it).append('\n') }
        append("Frames per channel: ${perChannelLine(run.perChannel)}\n")
        if (run.devices.isNotEmpty()) {
            append('\n').append(deviceSummary(run.devices)).append('\n')
            append(if (run.vendorPrefixes > 0) "Vendor table: ${run.vendorPrefixes} prefixes\n" else "Vendor table: NOT LOADED\n")
        }
        if (run.includeDeviceDetails && run.devices.isNotEmpty()) {
            append("\nDevices (details included by the user):\n")
            run.devices.forEach { (d, g) -> append(deviceLine(d, g)).append('\n') }
        }
        if (!run.trace.isNullOrBlank()) append("\nSteps:\n").append(run.trace).append('\n')
        if (!run.logcat.isNullOrBlank()) append("\nDriver log (logcat):\n").append(run.logcat).append('\n')
    }.trimEnd()
}
