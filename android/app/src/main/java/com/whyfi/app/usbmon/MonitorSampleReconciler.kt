package com.whyfi.app.usbmon

import kotlin.math.abs

/** Pairs retained radio snapshots with receiver fixes for pinned mission targets. */
internal object MonitorSampleReconciler {
    /** Correlate every AP RSSI observation with the phone's recorded position, regardless of favorites. */
    fun accessPoints(
        receiverFixes: List<MonitorReceiverFix>,
        snapshots: List<MonitorMission.DeviceSnapshot>,
    ): List<MonitorGeoSample> = correlate(receiverFixes, snapshots, apOnly = true)

    /** Clients must be transmitters with their own RSSI; an AP downlink cannot locate its recipient. */
    fun devices(receiverFixes: List<MonitorReceiverFix>, snapshots: List<MonitorMission.DeviceSnapshot>): List<MonitorGeoSample> =
        correlate(receiverFixes, snapshots, apOnly = false)

    private fun correlate(receiverFixes: List<MonitorReceiverFix>, snapshots: List<MonitorMission.DeviceSnapshot>,
                          apOnly: Boolean): List<MonitorGeoSample> = devicesParsed(receiverFixes,
        snapshots.map { it to SniffedDevices.parse(it.tsv) }, apOnly)

    internal fun devicesParsed(receiverFixes: List<MonitorReceiverFix>,
                               snapshots: List<Pair<MonitorMission.DeviceSnapshot, List<SniffedDevices.Device>>>,
                               apOnly: Boolean = false): List<MonitorGeoSample> {
        if (receiverFixes.isEmpty()) return emptyList()
        val orderedFixes = receiverFixes.sortedBy { it.atMs }
        fun fixesAt(atMs: Long): List<MonitorLocationCandidate> {
            var low = 0
            var high = orderedFixes.size
            while (low < high) {
                val mid = (low + high) / 2
                if (orderedFixes[mid].atMs < atMs - MonitorLocationPolicy.MAX_FIX_AGE_MS) low = mid + 1 else high = mid
            }
            val candidates = mutableListOf<MonitorLocationCandidate>()
            while (low < orderedFixes.size && orderedFixes[low].atMs <= atMs + MonitorLocationPolicy.MAX_FIX_AGE_MS)
                candidates += orderedFixes[low++].candidate()
            return candidates
        }
        val seen = mutableMapOf<Pair<String, Long>, Long>()
        return snapshots.flatMap { (snapshot, devices) ->
            devices.mapNotNull { device ->
                val bootMs = device.lastRssiBootMs ?: return@mapNotNull null
                val atMs = snapshot.atMs - snapshot.clockMs + bootMs
                if ((apOnly && !device.isAp) || device.lastRssi == null || device.lastRssiChannel == null ||
                    snapshot.clockMs <= 0 || bootMs > snapshot.clockMs + 2_000 ||
                    seen[device.mac to bootMs]?.let { abs(it - atMs) < 10_000 } == true) return@mapNotNull null
                seen[device.mac to bootMs] = atMs
                val candidates = fixesAt(atMs)
                val selected = MonitorLocationPolicy.select(candidates, bootMs) ?: return@mapNotNull null
                MonitorGeoSample(device.mac, atMs,
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
        val savedByMac = saved.groupBy { it.mac }
        val seen = mutableMapOf<Pair<String, Long>, Long>()
        val activeSince = initialTargets.associateWith { Long.MIN_VALUE }.toMutableMap()
        val changes = favoriteEvents.filter {
            it.event == "favorite" || it.event == "mission_resumed" || it.event == "mission_resumed_target"
        }
            .sortedBy { it.atMs }
        var changeIndex = 0
        val inferred = snapshots.flatMap { snapshot ->
            while (changeIndex < changes.size && changes[changeIndex].atMs <= snapshot.atMs) {
                val change = changes[changeIndex++]
                val mac = change.details.substringBeforeLast(':')
                if (change.event == "mission_resumed") activeSince.clear()
                else if (change.event == "mission_resumed_target") activeSince[change.details] = change.atMs
                else if (change.details.endsWith(":true")) activeSince[mac] = change.atMs
                else if (change.details.endsWith(":false")) activeSince.remove(mac)
            }
            SniffedDevices.parse(snapshot.tsv).mapNotNull { device ->
                val bootMs = device.lastRssiBootMs ?: return@mapNotNull null
                val atMs = snapshot.atMs - snapshot.clockMs + bootMs
                if (device.mac !in activeSince || device.lastRssi == null || device.lastRssiChannel == null ||
                    snapshot.clockMs <= 0 || bootMs > snapshot.clockMs + 2_000 ||
                    seen[device.mac to bootMs]?.let { abs(it - atMs) < 10_000 } == true) return@mapNotNull null
                seen[device.mac to bootMs] = atMs
                if (atMs < (activeSince[device.mac] ?: Long.MAX_VALUE)) return@mapNotNull null
                if (savedByMac[device.mac].orEmpty().any { abs(it.atMs - atMs) <= 2_000 })
                    return@mapNotNull null
                val candidates = receiverFixes.filter { abs(it.atMs - atMs) <= MonitorLocationPolicy.MAX_FIX_AGE_MS }
                    .map { it.candidate() }
                val selected = MonitorLocationPolicy.select(candidates, bootMs) ?: return@mapNotNull null
                MonitorGeoSample(device.mac, atMs, selected.fix.lat, selected.fix.lng,
                    selected.fix.accuracyM, device.lastRssi, device.lastRssiChannel,
                    selected.fix.provider, selected.ageMs, selected.fix.speedMps)
            }
        }
        return (saved + inferred).sortedBy { it.atMs }
    }
}
