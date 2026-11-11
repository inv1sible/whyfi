package com.whyfi.app.usbmon

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "Travels with me" (user, 2026-11-11): devices the adapter keeps hearing
 * while the phone moves. This reverses the earlier "no following-you logic"
 * decision for whyfi USB at the user's explicit request (MEMORY.md).
 *
 * Every reading is placed at the *receiver's* position (where the phone was
 * when it heard the device). Readings within [separationM] of an existing
 * place of that device join it; anything further opens a new place. A device
 * heard at [minPlaces] or more places over at least [MIN_SPAN_MS] moved with
 * the phone, or along the same route. A fixed AP heard while driving past
 * spans seconds, not minutes, so it does not qualify. Randomized MACs that
 * rotate break the trail into several devices; this cannot be avoided.
 */
object MonitorCoTravel {
    const val SEPARATION_M = 100.0
    const val MIN_SPAN_MS = 10 * 60_000L
    const val DEFAULT_MIN_PLACES = 3
    val PLACE_OPTIONS = listOf(2, 3, 5)
    private const val MAX_PLACES = 100
    private const val MAX_ACCURACY_M = 50.0

    data class Place(val lat: Double, val lng: Double, val firstMs: Long, val lastMs: Long, val readings: Int)

    data class Trail(val mac: String, val places: List<Place>) {
        val firstMs get() = places.minOf { it.firstMs }
        val lastMs get() = places.maxOf { it.lastMs }
        val spanMs get() = lastMs - firstMs
        val readings get() = places.sumOf { it.readings }
        fun qualifies(minPlaces: Int) = places.size >= minPlaces && spanMs >= MIN_SPAN_MS
    }

    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dy = (aLat - bLat) * 111_320.0
        val dx = (aLng - bLng) * 111_320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** Incremental; the capture service feeds it every poll. Thread-safe. */
    class Tracker(private val separationM: Double = SEPARATION_M) {
        private val places = HashMap<String, MutableList<Place>>()

        /** Returns true when the reading opened a new place for [mac]. */
        @Synchronized
        fun add(mac: String, atMs: Long, lat: Double, lng: Double, accuracyM: Double = 0.0): Boolean {
            if (!lat.isFinite() || !lng.isFinite() || accuracyM > MAX_ACCURACY_M) return false
            val list = places.getOrPut(mac) { mutableListOf() }
            val nearest = list.indices.minByOrNull { distanceM(list[it].lat, list[it].lng, lat, lng) }
            if (nearest != null && distanceM(list[nearest].lat, list[nearest].lng, lat, lng) < separationM) {
                val p = list[nearest]
                list[nearest] = p.copy(firstMs = minOf(p.firstMs, atMs), lastMs = maxOf(p.lastMs, atMs), readings = p.readings + 1)
                return false
            }
            if (list.size >= MAX_PLACES) return false
            list += Place(lat, lng, atMs, atMs, 1)
            return true
        }

        /** Devices heard at two or more places, most places first. */
        @Synchronized
        fun trails(): List<Trail> = places.filterValues { it.size >= 2 }
            .map { (mac, p) -> Trail(mac, p.toList()) }
            .sortedWith(compareByDescending<Trail> { it.places.size }.thenByDescending { it.lastMs })
    }

    /** Replay and old missions: the same grouping over saved readings. */
    fun analyze(samples: List<MonitorGeoSample>, cutoffMs: Long = Long.MAX_VALUE): List<Trail> {
        val tracker = Tracker()
        samples.asSequence().filter { it.atMs <= cutoffMs }.sortedBy { it.atMs }
            .forEach { tracker.add(it.mac, it.atMs, it.lat, it.lng, it.gpsAccuracyM) }
        return tracker.trails()
    }

    fun spanLabel(ms: Long): String = when {
        ms < 60_000 -> "<1 min"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h ${ms % 3_600_000 / 60_000} min"
    }

    fun chipLabel(minPlaces: Int) = "👣 ≥ $minPlaces places"
}
