package com.whyfi.app.usbmon

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan

internal data class MonitorMapViewport(val lat: Double, val lng: Double, val zoom: Double)

/** Fits the visible radio measurements into the map, leaving room for markers and labels. */
internal fun monitorMapViewport(points: List<Pair<Double, Double>>, width: Int, height: Int): MonitorMapViewport? {
    if (points.isEmpty()) return null
    val valid = points.filter { it.first.isFinite() && it.second.isFinite() &&
        it.first in -85.0..85.0 && it.second in -180.0..180.0 }
    if (valid.isEmpty()) return null
    val minLat = valid.minOf { it.first }
    val maxLat = valid.maxOf { it.first }
    val minLng = valid.minOf { it.second }
    val maxLng = valid.maxOf { it.second }
    fun mercatorY(lat: Double): Double {
        val radians = lat * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
    }
    val xSpan = (maxLng - minLng) / 360.0
    val ySpan = mercatorY(minLat) - mercatorY(maxLat)
    val xZoom = if (xSpan == 0.0) 19.0 else log2(width.coerceAtLeast(1) * 0.7 / (256.0 * xSpan))
    val yZoom = if (ySpan == 0.0) 19.0 else log2(height.coerceAtLeast(1) * 0.7 / (256.0 * ySpan))
    return MonitorMapViewport((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0,
        min(xZoom, yZoom).coerceIn(3.0, 19.0))
}
