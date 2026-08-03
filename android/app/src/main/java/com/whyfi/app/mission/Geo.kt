package com.whyfi.app.mission

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

data class WeightedLatLng(val lat: Double, val lng: Double, val weight: Double)

/**
 * Direct, verbatim port of `frontend/src/geo.ts` — same formulas, same
 * constants, same edge-case handling. This is what turns a favorited SSID's
 * near-location-filtered readings (see WhyfiApiService.missionWifiObservations)
 * into the same gradient-cone visualization the PWA's "Solo mode" already
 * draws (frontend/src/coverageConfig.ts's soloShapes/frontend/src/components/RadioMap.tsx).
 * Don't reinvent any of this — it's already proven correct there, and
 * [weightedCentroid] in particular is deliberately identical to the
 * backend's own `weighted_centroid()` in `backend/scans/views.py` (there's a
 * parity test pinning those two together).
 */
object Geo {
    private const val EARTH_RADIUS_M = 6371000.0
    private const val METERS_PER_DEGREE_LAT = 111320.0

    private fun toRad(deg: Double): Double = deg * PI / 180.0

    fun haversineDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLng = toRad(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun initialBearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val y = sin(toRad(lng2 - lng1)) * cos(toRad(lat2))
        val x = cos(toRad(lat1)) * sin(toRad(lat2)) -
            sin(toRad(lat1)) * cos(toRad(lat2)) * cos(toRad(lng2 - lng1))
        return atan2(y, x) * 180.0 / PI
    }

    /** A device's estimated position: the signal-weighted centre of every
     * reading passed in. The weakest reading still counts for something
     * (the 0.1 floor) so a few strong readings can't collapse the centre
     * onto themselves. */
    fun weightedCentroid(points: List<WeightedLatLng>): LatLng {
        val minW = points.minOf { it.weight }
        val maxW = points.maxOf { it.weight }
        val spread = (maxW - minW).let { if (it == 0.0) 1.0 else it }
        val weights = points.map { 0.1 + 0.9 * ((it.weight - minW) / spread) }
        val total = weights.sum()
        val lat = points.indices.sumOf { weights[it] * points[it].lat } / total
        val lng = points.indices.sumOf { weights[it] * points[it].lng } / total
        return LatLng(lat, lng)
    }

    fun offsetPoint(origin: LatLng, bearingDegrees: Double, distanceMeters: Double): LatLng {
        val bearingRad = toRad(bearingDegrees)
        val dLat = (distanceMeters * cos(bearingRad)) / METERS_PER_DEGREE_LAT
        val dLng = (distanceMeters * sin(bearingRad)) /
            (METERS_PER_DEGREE_LAT * max(cos(toRad(origin.lat)), 0.01))
        return LatLng(origin.lat + dLat, origin.lng + dLng)
    }

    /** A wedge from a known `apex` (e.g. an AP's estimated position) toward
     * `target` (where a single reading was taken), fanning out at
     * [halfAngleDegrees] on each side and overshooting slightly past
     * `target` so it sits inside the shape rather than exactly on its far
     * edge. Returns null when apex and target are too close to have a
     * meaningful bearing between them. The apex is always vertex 0. */
    fun conePolygon(
        apex: LatLng,
        target: LatLng,
        halfAngleDegrees: Double = 22.0,
        overshoot: Double = 1.15,
    ): List<LatLng>? {
        val distance = haversineDistanceMeters(apex.lat, apex.lng, target.lat, target.lng)
        if (distance < 5) return null

        val bearing = initialBearingDegrees(apex.lat, apex.lng, target.lat, target.lng)
        val farDistance = distance * overshoot
        val left = offsetPoint(apex, bearing - halfAngleDegrees, farDistance)
        val right = offsetPoint(apex, bearing + halfAngleDegrees, farDistance)
        val tip = offsetPoint(apex, bearing, farDistance)
        return listOf(apex, left, tip, right)
    }

    /** One pass of Chaikin corner-cutting per iteration — softens a
     * polygon's straight edges/sharp vertices, which read as a harder
     * boundary than radio coverage actually has. */
    fun smoothPolygon(points: List<LatLng>, iterations: Int = 1): List<LatLng> {
        if (points.size < 3) return points
        var ring = points
        repeat(iterations) {
            val next = mutableListOf<LatLng>()
            ring.forEachIndexed { i, a ->
                val b = ring[(i + 1) % ring.size]
                next.add(LatLng(a.lat * 0.75 + b.lat * 0.25, a.lng * 0.75 + b.lng * 0.25))
                next.add(LatLng(a.lat * 0.25 + b.lat * 0.75, a.lng * 0.25 + b.lng * 0.75))
            }
            ring = next
        }
        return ring
    }

    /** Fallback shape for the rare case a cone can't be drawn (apex too
     * close to the reading, or only one reading exists so far). Flat-plane
     * approximation, same as [offsetPoint]. */
    fun circlePolygon(center: LatLng, radiusMeters: Double, segments: Int = 48): List<LatLng> {
        val latRadians = toRad(center.lat)
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = radiusMeters / (METERS_PER_DEGREE_LAT * max(cos(latRadians), 0.01))
        return (0 until segments).map { i ->
            val t = 2 * PI * i / segments
            LatLng(center.lat + dLat * sin(t), center.lng + dLng * cos(t))
        }
    }
}
