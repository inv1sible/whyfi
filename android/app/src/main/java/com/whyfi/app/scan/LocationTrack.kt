package com.whyfi.app.scan

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One position on the phone's path. [atMs] is wall-clock time. */
data class TrackPoint(val atMs: Long, val lat: Double, val lng: Double, val accuracyM: Double?, val provider: String)

/**
 * The phone's recent path for the GPS screen and report. Pure, unit-tested
 * (LocationTrackTest).
 *
 * GPS, fused and network fixes all arrive together; drawing all three would
 * zigzag between them. GPS is always kept, fused only when GPS has been
 * quiet for [FUSED_AFTER_MS], network only when neither has for
 * [NETWORK_AFTER_MS]. Wildly inaccurate fixes and near-duplicates are
 * dropped, and the track is capped at [MAX_POINTS].
 */
object LocationTrack {
    const val MAX_POINTS = 5_000
    const val MAX_ACCURACY_M = 500.0
    const val FUSED_AFTER_MS = 5_000L
    const val NETWORK_AFTER_MS = 15_000L
    private const val DUPLICATE_M = 1.0
    private const val DUPLICATE_MS = 10_000L

    fun append(track: List<TrackPoint>, point: TrackPoint): List<TrackPoint> {
        if ((point.accuracyM ?: 0.0) > MAX_ACCURACY_M) return track
        if (track.isNotEmpty() && point.atMs < track.last().atMs) return track
        fun lastOf(vararg providers: String) = track.lastOrNull { it.provider in providers }?.atMs
        val keep = when (point.provider) {
            "gps" -> true
            "fused" -> lastOf("gps")?.let { point.atMs - it >= FUSED_AFTER_MS } ?: true
            else -> lastOf("gps", "fused")?.let { point.atMs - it >= NETWORK_AFTER_MS } ?: true
        }
        if (!keep) return track
        val last = track.lastOrNull()
        if (last != null && point.atMs - last.atMs < DUPLICATE_MS && distanceM(last, point) < DUPLICATE_M) {
            // Same spot: keep whichever fix is more accurate.
            val better = (point.accuracyM ?: Double.MAX_VALUE) < (last.accuracyM ?: Double.MAX_VALUE)
            return if (better) track.dropLast(1) + point else track
        }
        val next = track + point
        return if (next.size > MAX_POINTS) next.drop(next.size - MAX_POINTS) else next
    }

    fun distanceM(a: TrackPoint, b: TrackPoint): Double {
        val r = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLng = (b.lng - a.lng) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun lengthM(track: List<TrackPoint>): Double = track.zipWithNext { a, b -> distanceM(a, b) }.sum()

    fun medianAccuracyM(track: List<TrackPoint>): Double? {
        val values = track.mapNotNull { it.accuracyM }.sorted()
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }
}
