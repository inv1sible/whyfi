package com.whyfi.app.usbmon

import kotlin.math.abs

/** Pairs retained radio snapshots with receiver fixes for pinned mission targets. */
internal object MonitorSampleReconciler {
    /** Correlate every AP RSSI observation with the phone's recorded position, regardless of favorites. */
    fun accessPoints(
        receiverFixes: List<MonitorReceiverFix>,
        snapshots: List<MonitorMission.DeviceSnapshot>,
    ): List<MonitorGeoSample> {
        if (receiverFixes.isEmpty()) return emptyList()
        val candidates = receiverFixes.map { it.candidate() }
        val seen = mutableSetOf<Pair<String, Long>>()
        return snapshots.flatMap { snapshot ->
            SniffedDevices.parse(snapshot.tsv).mapNotNull { device ->
                val bootMs = device.lastRssiBootMs ?: return@mapNotNull null
                if (!device.isAp || device.lastRssi == null || device.lastRssiChannel == null ||
                    snapshot.clockMs <= 0 || bootMs > snapshot.clockMs + 2_000 ||
                    !seen.add(device.mac to bootMs)) return@mapNotNull null
                val selected = MonitorLocationPolicy.select(candidates, bootMs) ?: return@mapNotNull null
                MonitorGeoSample(device.mac, snapshot.atMs - snapshot.clockMs + bootMs,
                    selected.fix.lat, selected.fix.lng, selected.fix.accuracyM,
                    device.lastRssi, device.lastRssiChannel, selected.fix.provider,
                    selected.ageMs, selected.fix.speedMps)
            }
        }.sortedBy { it.atMs }
    }

    fun reconcile(
        saved: List<MonitorGeoSample>,
        receiverFixes: List<MonitorReceiverFix>,
        snapshots: List<MonitorMission.DeviceSnapshot>,
        initialTargets: Set<String>,
        favoriteEvents: List<MonitorMission.ReplayEvent>,
    ): List<MonitorGeoSample> {
        if (receiverFixes.isEmpty()) return saved
        val candidates = receiverFixes.map { it.candidate() }
        val savedByMac = saved.groupBy { it.mac }
        val seen = mutableSetOf<Pair<String, Long>>()
        val activeSince = initialTargets.associateWith { Long.MIN_VALUE }.toMutableMap()
        val changes = favoriteEvents.filter { it.event == "favorite" }.sortedBy { it.atMs }
        var changeIndex = 0
        val inferred = snapshots.flatMap { snapshot ->
            while (changeIndex < changes.size && changes[changeIndex].atMs <= snapshot.atMs) {
                val change = changes[changeIndex++]
                val mac = change.details.substringBeforeLast(':')
                if (change.details.endsWith(":true")) activeSince[mac] = change.atMs
                else if (change.details.endsWith(":false")) activeSince.remove(mac)
            }
            SniffedDevices.parse(snapshot.tsv).mapNotNull { device ->
                val bootMs = device.lastRssiBootMs ?: return@mapNotNull null
                if (device.mac !in activeSince || device.lastRssi == null || device.lastRssiChannel == null ||
                    snapshot.clockMs <= 0 || bootMs > snapshot.clockMs + 2_000 ||
                    !seen.add(device.mac to bootMs)) return@mapNotNull null
                val atMs = snapshot.atMs - snapshot.clockMs + bootMs
                if (atMs < (activeSince[device.mac] ?: Long.MAX_VALUE)) return@mapNotNull null
                if (savedByMac[device.mac].orEmpty().any { abs(it.atMs - atMs) <= 2_000 })
                    return@mapNotNull null
                val selected = MonitorLocationPolicy.select(candidates, bootMs) ?: return@mapNotNull null
                MonitorGeoSample(device.mac, atMs, selected.fix.lat, selected.fix.lng,
                    selected.fix.accuracyM, device.lastRssi, device.lastRssiChannel,
                    selected.fix.provider, selected.ageMs, selected.fix.speedMps)
            }
        }
        return (saved + inferred).sortedBy { it.atMs }
    }
}
