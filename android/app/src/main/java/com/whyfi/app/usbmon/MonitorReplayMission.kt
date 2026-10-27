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
    val deviceSamples: List<MonitorGeoSample> = emptyList(),
    private val evidence: MonitorEvidence? = null,
) {
    private val parsedSnapshots by lazy { evidence?.parsedSnapshots ?: snapshots.map { it to SniffedDevices.parse(it.tsv) } }
    private var inventory = emptyList<SniffedDevices.Device>()
    private var nextSnapshot = 0
    private var previousCutoff = Long.MIN_VALUE
    @Synchronized
    fun devicesAt(cutoffMs: Long): List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> {
        if (cutoffMs < previousCutoff) { inventory = emptyList(); nextSnapshot = 0 }
        while (nextSnapshot < parsedSnapshots.size && parsedSnapshots[nextSnapshot].first.atMs <= cutoffMs) {
            inventory = MonitorInventory.merge(inventory, parsedSnapshots[nextSnapshot++].second)
        }
        previousCutoff = cutoffMs
        return inventory.map { d -> d to SniffedDevices.guess(d) { vendors?.vendorOf(it) } }
    }
    val timeline: Pair<Long, Long> by lazy {
        val times = samples.map { it.atMs } + events.map { it.atMs } + snapshots.map { it.atMs } +
            receiverFixes.map { it.atMs } + deviceSamples.map { it.atMs }
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

    fun favoritesAt(cutoffMs: Long): Set<String> =
        MonitorMission.favoritesAfter(initialFavorites, events, cutoffMs)

    companion object {
        fun load(context: Context, log: File, vendors: OuiTable? = null): MonitorReplayMission {
            val table = vendors ?: runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
            val evidence = MonitorEvidence.read(log)
            val names = evidence.inventoryAt().asSequence()
                .associate { device ->
                    device.mac to MonitorMission.deviceName(device,
                        SniffedDevices.guess(device) { table?.vendorOf(it) })
                } + evidence.labels
            return MonitorReplayMission(log, evidence.samples, evidence.targets,
                names, evidence.events, evidence.snapshots, evidence.initialFavorites, table, evidence.receiverFixes,
                evidence.apSamples, evidence.radioSamples, evidence).also { it.timeline }
        }
    }
}
