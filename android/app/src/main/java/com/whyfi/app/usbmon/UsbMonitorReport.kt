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
        if (total > 0) {
            lines += "RSSI: ${stat(stats, Stat.RSSI_MIN)} … ${stat(stats, Stat.RSSI_MAX)} dBm"
        }
        val ch = stat(stats, Stat.CURRENT_CHANNEL)
        if (ch > 0) lines += "Current channel: $ch"
        return lines
    }

    fun perChannelLine(flat: LongArray): String =
        if (flat.isEmpty()) "none" else flat.toList().chunked(2).joinToString(", ") { (ch, n) -> "ch$ch=$n" }

    fun format(run: Run): String = buildString {
        append("USB monitor-mode test (spike) — diagnostic report, not a crash\n")
        append("Result: ${if (run.result.isNullOrEmpty()) "OK" else "FAILED — ${run.result}"}\n")
        append("Device: ${run.device}\n")
        append("Phone: ${run.androidInfo}, app ${run.appVersion}\n")
        append("Duration: ${run.durationMs / 1000} s\n\n")
        summary(run.stats).forEach { append(it).append('\n') }
        append("Frames per channel: ${perChannelLine(run.perChannel)}\n")
        if (!run.trace.isNullOrBlank()) append("\nSteps:\n").append(run.trace).append('\n')
        if (!run.logcat.isNullOrBlank()) append("\nDriver log (logcat):\n").append(run.logcat).append('\n')
    }.trimEnd()
}
