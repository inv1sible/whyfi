package com.whyfi.app.mission

import com.google.gson.Gson
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanDiff

/**
 * Mission readings from the scans stored on this phone (recordings), for use
 * without a server: the same near-filter and weights as the backend's
 * mission_*_observations views (scans/views.py), so a target looks the same
 * either way. Wi‑Fi targets are an SSID, BLE a MAC, cells a tower key
 * (ScanDiff.cellKey). Weight is RSSI (Wi‑Fi, BLE) or signal dBm (cell).
 */
object LocalMissionPoints {
    const val CAP = 2000
    private val gson = Gson()

    /** A cheap substring the stored JSON of every pass seeing [target]
     * contains, so the database can skip unrelated passes. JSON-escaped like
     * Gson writes it (e.g. "<" becomes \u003c). */
    fun needle(target: MissionTarget): String = when (target.kind) {
        RadioKind.CELLULAR -> target.identifier.substringAfterLast('-')
        else -> gson.toJson(target.identifier).removeSurrounding("\"")
    }

    data class Result(val points: List<MissionPoint>, val truncated: Boolean, val passes: Int)

    /** A stored pass: its JSON plus the recording it belongs to and when it
     * was kept (data.local.StoredPassRow). */
    data class Pass(val json: String, val recordingId: String? = null, val atMs: Long? = null)

    fun points(
        payloads: List<Pass>,
        target: MissionTarget,
        nearLat: Double,
        nearLng: Double,
        radiusM: Double,
        cap: Int = CAP,
    ): Result {
        val points = ArrayList<MissionPoint>()
        var passes = 0
        for (stored in payloads) {
            val pass = runCatching { gson.fromJson(stored.json, ScanSessionUploadRequest::class.java) }.getOrNull() ?: continue
            val lat = pass.latitude ?: continue
            val lng = pass.longitude ?: continue
            if (Geo.haversineDistanceMeters(nearLat, nearLng, lat, lng) > radiusM) continue
            val weights: List<Double> = when (target.kind) {
                RadioKind.WIFI -> pass.wifiObservations.orEmpty().filter { it.ssid == target.identifier }.map { it.rssi.toDouble() }
                RadioKind.BLE -> pass.bleObservations.orEmpty().filter { it.bleMac.equals(target.identifier, ignoreCase = true) }.map { it.rssi.toDouble() }
                RadioKind.CELLULAR -> pass.cellObservations.orEmpty()
                    .filter { ScanDiff.cellKey(it.mcc, it.mnc, it.tacOrLac, it.cellId) == target.identifier }
                    .mapNotNull { it.signalDbm?.toDouble() }
                RadioKind.SATELLITE -> emptyList()
            }
            if (weights.isNotEmpty()) passes++
            val accuracy = pass.locationAccuracyMeters?.toDouble()
            weights.forEach { points += MissionPoint(lat, lng, it, accuracy, stored.atMs, stored.recordingId) }
        }
        return Result(points.take(cap), points.size > cap, passes)
    }

    /** Gap that ends a session for [latestSession]. */
    const val SESSION_GAP_MS = 30 * 60 * 1000L

    /** Mission's "Latest": the newest reading plus every one before it with
     * no gap over [gapMs], from the server or the phone alike. Readings
     * without a time are dropped once any has one; none timed: all kept. */
    fun latestSession(points: List<MissionPoint>, gapMs: Long = SESSION_GAP_MS): List<MissionPoint> {
        val timed = points.filter { it.atMs != null }.sortedByDescending { it.atMs }
        if (timed.isEmpty()) return points
        var oldest = timed.first().atMs!!
        for (point in timed.drop(1)) {
            if (oldest - point.atMs!! > gapMs) break
            oldest = point.atMs
        }
        return points.filter { (it.atMs ?: Long.MIN_VALUE) >= oldest }
    }

    /** The newest non-blank advertised name of BLE device [mac] in [payloads]. */
    fun bleName(payloads: List<Pass>, mac: String): String? = payloads.sortedByDescending { it.atMs ?: 0L }.firstNotNullOfOrNull { stored ->
        runCatching { gson.fromJson(stored.json, ScanSessionUploadRequest::class.java) }.getOrNull()
            ?.bleObservations.orEmpty()
            .firstOrNull { it.bleMac.equals(mac, ignoreCase = true) && it.deviceName.isNotBlank() }?.deviceName
    }
}
