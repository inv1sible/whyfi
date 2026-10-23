package com.whyfi.app.usbmon

import android.content.Context
import java.io.File

/** Shared mission timeline for the map and category tables; Nearby stays live. */
internal data class MonitorReplayMission(
    val log: File,
    val samples: List<MonitorGeoSample>,
    val targets: Set<String>,
    val labels: Map<String, String>,
    val events: List<MonitorMission.ReplayEvent>,
    val snapshots: List<MonitorMission.DeviceSnapshot>,
    val initialFavorites: Set<String>,
    val vendors: OuiTable?,
    val receiverFixes: List<MonitorReceiverFix> = emptyList(),
    val accessPointSamples: List<MonitorGeoSample> = emptyList(),
) {
    val timeline: Pair<Long, Long> by lazy {
        val times = samples.map { it.atMs } + events.map { it.atMs } + snapshots.map { it.atMs } +
            receiverFixes.map { it.atMs } + accessPointSamples.map { it.atMs }
        (times.minOrNull() ?: 0L) to (times.maxOrNull() ?: 0L)
    }

    fun snapshotAt(cutoffMs: Long): MonitorMission.DeviceSnapshot? = snapshots.lastOrNull { it.atMs <= cutoffMs }

    /** Older logs can replay only their geotagged favorites, with unknown radio roles. */
    fun legacyDevicesAt(cutoffMs: Long): List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> =
        samples.filter { it.atMs <= cutoffMs }.groupBy { it.mac }.map { (mac, readings) ->
            val latest = readings.maxBy { it.atMs }
            SniffedDevices.Device(mac, 0, readings.size.toLong(), 0, 0, 0,
                readings.maxOf { it.rssi }, readings.map { it.channel }.distinct().sorted(),
                labels[mac].orEmpty(), emptyList(), false, emptyList(), "", "", "",
                readings.minOf { it.atMs }, latest.atMs, latest.rssi, null, latest.channel) to
                SniffedDevices.Guess(SniffedDevices.Kind.CLIENT, null,
                    listOf("Legacy mission: role and association were not recorded"))
        }

    fun favoritesAt(cutoffMs: Long): Set<String> {
        val result = initialFavorites.toMutableSet()
        events.filter { it.atMs <= cutoffMs && it.event == "favorite" }.forEach { event ->
            val mac = event.details.substringBeforeLast(':')
            if (mac.isNotBlank()) {
                if (event.details.endsWith(":true")) result += mac
                else if (event.details.endsWith(":false")) result -= mac
            }
        }
        return result
    }

    companion object {
        fun load(context: Context, log: File): MonitorReplayMission {
            val vendors = runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
            val snapshots = MonitorMission.readDeviceSnapshots(log)
            val names = snapshots.lastOrNull()?.let { latest ->
                SniffedDevices.parse(latest.tsv).associate { device ->
                    device.mac to MonitorMission.deviceName(device,
                        SniffedDevices.guess(device) { vendors?.vendorOf(it) })
                }
            }.orEmpty() + MonitorMission.readLabels(log)
            val receiverFixes = MonitorMission.readReceiverFixes(log)
            return MonitorReplayMission(log, MonitorMission.readSamples(log), MonitorMission.targets(log),
                names, MonitorMission.readReplayEvents(log), snapshots,
                MonitorMission.readInitialFavorites(log), vendors, receiverFixes,
                MonitorSampleReconciler.accessPoints(receiverFixes, snapshots))
        }
    }
}
