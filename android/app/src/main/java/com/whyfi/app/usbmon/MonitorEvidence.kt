package com.whyfi.app.usbmon

import com.google.gson.JsonParser
import java.io.File
import kotlin.math.abs

/** One sequential read of the JSONL, used only when details/replay/report are requested. */
internal data class MonitorEvidence(
    val snapshots: List<MonitorMission.DeviceSnapshot>,
    val receiverFixes: List<MonitorReceiverFix>,
    val savedSamples: List<MonitorGeoSample>,
    val events: List<MonitorMission.ReplayEvent>,
    val labels: Map<String, String>,
    val initialFavorites: Set<String>,
    val targets: Set<String>,
) {
    internal val parsedSnapshots by lazy { snapshots.map { it to SniffedDevices.parse(it.tsv) } }
    fun inventoryAt(cutoff: Long = Long.MAX_VALUE): List<SniffedDevices.Device> {
        var devices = emptyList<SniffedDevices.Device>()
        parsedSnapshots.takeWhile { it.first.atMs <= cutoff }.forEach { devices = MonitorInventory.merge(devices, it.second) }
        return devices
    }
    val radioSamples by lazy { MonitorSampleReconciler.devicesParsed(receiverFixes, parsedSnapshots) }
    val samples by lazy {
        val radioByMac = radioSamples.groupBy { it.mac }
        (radioSamples + savedSamples.filter { sample ->
            radioByMac[sample.mac].orEmpty().none { abs(it.atMs - sample.atMs) <= 2_000 }
        }).sortedBy { it.atMs }
    }
    val apSamples by lazy {
        val aps = inventoryAt().filter { it.isAp }.mapTo(mutableSetOf()) { it.mac }
        radioSamples.filter { it.mac in aps }
    }

    companion object {
        fun read(log: File): MonitorEvidence {
            val snapshots = mutableListOf<MonitorMission.DeviceSnapshot>()
            val fixes = mutableListOf<MonitorReceiverFix>()
            val samples = mutableListOf<MonitorGeoSample>()
            val events = mutableListOf<MonitorMission.ReplayEvent>()
            val labels = mutableMapOf<String, String>()
            val targets = mutableSetOf<String>()
            val initial = mutableSetOf<String>()
            var initialStarted = false
            var initialFinished = false
            log.forEachLine { line ->
                runCatching {
                    val row = JsonParser.parseString(line).asJsonObject
                    fun string(key: String) = row.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    fun number(key: String, fallback: Long = 0L) = row.get(key)?.asLong ?: fallback
                    fun decimal(key: String, fallback: Double = 0.0) = row.get(key)?.asDouble ?: fallback
                    val event = string("event")
                    val at = number("at_ms")
                    val details = string("details")
                    if (!initialFinished) {
                        if (event == "monitor_started") initialStarted = true
                        else if (event == "mission_target" && initialStarted) initial += details
                        else if (initialStarted) initialFinished = true
                    }
                    when (event) {
                        "devices_snapshot" -> snapshots += MonitorMission.DeviceSnapshot(at, number("clock_ms"), string("tsv"))
                        "receiver_fix" -> {
                            val lat = row.get("lat").asDouble
                            val lng = row.get("lng").asDouble
                            require(at > 0 && number("boot_ms") > 0 && lat.isFinite() && lng.isFinite() &&
                                lat in -90.0..90.0 && lng in -180.0..180.0)
                            fixes += MonitorReceiverFix(at, number("boot_ms"), string("provider"),
                                lat, lng, row.get("accuracy_m").asDouble, decimal("speed_mps", 15.0))
                        }
                        "geo_sample" -> {
                            val lat = row.get("lat").asDouble
                            val lng = row.get("lng").asDouble
                            require(at > 0 && string("mac").isNotBlank() && lat.isFinite() && lng.isFinite() &&
                                lat in -90.0..90.0 && lng in -180.0..180.0)
                            samples += MonitorGeoSample(string("mac"), at, lat, lng,
                                row.get("gps_accuracy_m").asDouble, row.get("rssi").asInt, row.get("channel").asInt,
                                string("location_source"), number("fix_age_ms"), decimal("speed_mps"))
                            targets += string("mac")
                        }
                        "device_identity" -> labels[string("mac")] = string("name")
                        else -> {
                            events += MonitorMission.ReplayEvent(at, event, details)
                            if (event == "mission_target" || event == "mission_resumed_target") targets += details
                            if (event == "favorite" && details.endsWith(":true")) targets += details.substringBeforeLast(':')
                        }
                    }
                }
            }
            return MonitorEvidence(snapshots.sortedBy { it.atMs }, fixes.sortedBy { it.atMs }, samples,
                events.sortedBy { it.atMs }, labels, initial.filter(String::isNotBlank).toSet(),
                targets.filter(String::isNotBlank).toSet())
        }
    }
}
