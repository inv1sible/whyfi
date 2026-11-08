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
     * Gson writes it (e.g. "<" becomes <). */
    fun needle(target: MissionTarget): String = when (target.kind) {
        RadioKind.CELLULAR -> target.identifier.substringAfterLast('-')
        else -> gson.toJson(target.identifier).removeSurrounding("\"")
    }

    data class Result(val points: List<MissionPoint>, val truncated: Boolean, val passes: Int)

    fun points(
        payloads: List<String>,
        target: MissionTarget,
        nearLat: Double,
        nearLng: Double,
        radiusM: Double,
        cap: Int = CAP,
    ): Result {
        val points = ArrayList<MissionPoint>()
        var passes = 0
        for (json in payloads) {
            val pass = runCatching { gson.fromJson(json, ScanSessionUploadRequest::class.java) }.getOrNull() ?: continue
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
            weights.forEach { points += MissionPoint(lat, lng, it) }
        }
        return Result(points.take(cap), points.size > cap, passes)
    }
}
