package com.whyfi.app.usbmon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A receiver position and one recent USB-radio signal reading. Never a transmitter coordinate. */
data class MonitorGeoSample(
    val mac: String,
    val atMs: Long,
    val lat: Double,
    val lng: Double,
    val gpsAccuracyM: Double,
    val rssi: Int,
    val channel: Int,
    val locationSource: String = "",
    val fixAgeMs: Long = 0,
)

data class MonitorHeatCell(val lat: Double, val lng: Double, val relativeFit: Double)

data class MonitorEstimate(
    val lat: Double,
    val lng: Double,
    val uncertaintyM: Double,
    val spatialGroups: Int,
    val receiverSpreadM: Double,
    val baselineLat: Double,
    val baselineLng: Double,
    val band: String,
    val provisional: Boolean,
    val advice: List<String>,
    val heat: List<MonitorHeatCell>,
)

/**
 * Receive-only, local estimator for a mission report. A fixed path-loss slope
 * is used with a bounded, per-device fitted transmit-power offset. Scores use
 * Huber loss; the grid is relative model fit, never a calibrated probability.
 * Spatial grouping prevents thousands of packets at one GPS fix dominating.
 */
object MonitorLocalization {
    private const val METERS_PER_DEGREE = 111_320.0
    private const val GRID_STEPS = 25
    private const val HUBER_DB = 6.0
    private const val OUTLIER_DB = 18.0
    private const val CELL_M = 6.0

    private data class Point(val x: Double, val y: Double, val rssi: Double, val accuracy: Double)
    private data class Candidate(val x: Double, val y: Double, val cost: Double)

    fun estimate(raw: List<MonitorGeoSample>, pathLossExponent: Double = 2.7): MonitorEstimate? {
        val usable = raw.filter {
            it.lat in -90.0..90.0 && it.lng in -180.0..180.0 &&
                it.lat.isFinite() && it.lng.isFinite() && it.gpsAccuracyM.isFinite() && it.gpsAccuracyM >= 0.0 &&
                it.fixAgeMs in 0..MonitorLocationPolicy.MAX_FIX_AGE_MS &&
                it.rssi in -110..-5 && it.channel > 0
        }
        if (usable.isEmpty()) return null
        val band24 = usable.count { it.channel <= 14 } >= usable.size / 2.0
        val selected = usable.filter { (it.channel <= 14) == band24 }
        val originLat = selected.map { it.lat }.average()
        val originLng = selected.map { it.lng }.average()
        val metresPerLng = METERS_PER_DEGREE * max(cos(originLat * PI / 180.0), 0.01)
        fun x(lng: Double) = (lng - originLng) * metresPerLng
        fun y(lat: Double) = (lat - originLat) * METERS_PER_DEGREE
        fun lat(y: Double) = originLat + y / METERS_PER_DEGREE
        fun lng(x: Double) = originLng + x / metresPerLng
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val grouped = selected.groupBy { (x(it.lng) / CELL_M).roundToInt() to (y(it.lat) / CELL_M).roundToInt() }
            .values.map { bin ->
                Point(median(bin.map { x(it.lng) }), median(bin.map { y(it.lat) }),
                    median(bin.map { it.rssi.toDouble() }), median(bin.map {
                        MonitorLocationPolicy.effectiveAccuracy(it.gpsAccuracyM, it.fixAgeMs)
                    }))
            }
        val baselineWeights = grouped.map { exp((it.rssi - grouped.maxOf { p -> p.rssi }) / 12.0) }
        val baselineX = grouped.indices.sumOf { grouped[it].x * baselineWeights[it] } / baselineWeights.sum()
        val baselineY = grouped.indices.sumOf { grouped[it].y * baselineWeights[it] } / baselineWeights.sum()
        val spread = grouped.maxOf { a -> grouped.maxOf { b -> hypot(a.x - b.x, a.y - b.y) } }
        val gpsFloor = max(15.0, grouped.maxOf { it.accuracy } * 2.0)
        val advice = mutableListOf<String>()
        val band = if (band24) "2.4 GHz" else "5 GHz"
        if (usable.size != selected.size) advice += "Mixed bands were heard; estimate uses $band readings only."
        if (grouped.size < 4) advice += "Take readings from at least four separated positions."
        if (spread < 20.0) advice += "Walk farther from the current measurement spot."
        if (grouped.maxOf { it.accuracy } > 25.0)
            advice += "Location is coarse; wait for better GPS/fused accuracy or measure across a wider area."

        val meanX = grouped.map { it.x }.average()
        val meanY = grouped.map { it.y }.average()
        val xx = grouped.sumOf { (it.x - meanX) * (it.x - meanX) }
        val yy = grouped.sumOf { (it.y - meanY) * (it.y - meanY) }
        val xy = grouped.sumOf { (it.x - meanX) * (it.y - meanY) }
        val trace = xx + yy
        val disc = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4 * xy * xy))
        val minor = (trace - disc) / 2.0
        val collinear = grouped.size >= 3 && (trace <= 0.0 || minor / max(trace, 1.0) < 0.04)
        if (collinear) advice += "Measure from a side street or across the route to improve geometry."

        // One or two independent locations do not identify position and power
        // together. Still show the conservative centroid, explicitly provisional.
        if (grouped.size < 3 || spread < 10.0 || grouped.maxOf { it.accuracy } > max(100.0, spread) ||
            !pathLossExponent.isFinite() || pathLossExponent !in 1.2..5.0) {
            return MonitorEstimate(lat(baselineY), lng(baselineX), max(100.0, gpsFloor + spread),
                grouped.size, spread, lat(baselineY), lng(baselineX), band, true,
                advice + "This is a receiver-position hint, not a resolved transmitter location.", emptyList())
        }

        val halfSpan = max(80.0, spread * 0.85 + 40.0).coerceAtMost(600.0)
        val step = 2 * halfSpan / (GRID_STEPS - 1)
        fun cost(cx: Double, cy: Double): Double {
            val inferredPower = grouped.map { point ->
                point.rssi + 10 * pathLossExponent * log10(max(1.5, hypot(cx - point.x, cy - point.y)))
            }
            val p0 = median(inferredPower).coerceIn(-55.0, -30.0)
            return grouped.sumOf { point ->
                val predicted = p0 - 10 * pathLossExponent * log10(max(1.5, hypot(cx - point.x, cy - point.y)))
                val residual = abs(point.rssi - predicted)
                val huber = if (residual <= HUBER_DB) residual * residual / 2
                    else HUBER_DB * (residual - HUBER_DB / 2)
                // A single reflected or transiently loud packet must not
                // outweigh several consistent spatially distinct readings.
                val robustLoss = min(huber, HUBER_DB * (OUTLIER_DB - HUBER_DB / 2))
                robustLoss / (1.0 + point.accuracy / 20.0)
            } + ((p0 + 40.0) / 6.0).let { it * it * grouped.size }
        }
        val grid = (0 until GRID_STEPS).flatMap { row ->
            (0 until GRID_STEPS).map { col ->
                val cx = meanX - halfSpan + col * step
                val cy = meanY - halfSpan + row * step
                Candidate(cx, cy, cost(cx, cy))
            }
        }
        val best = grid.minBy { it.cost }
        // Three refinements improve the point estimate without increasing the
        // heatmap's tile/cell count. The map still displays the coarse surface.
        var refined = best
        var refinementStep = step / 2
        repeat(3) {
            refined = (-1..1).flatMap { dy -> (-1..1).map { dx ->
                val cx = refined.x + dx * refinementStep
                val cy = refined.y + dy * refinementStep
                Candidate(cx, cy, cost(cx, cy))
            } }.minBy { it.cost }
            refinementStep /= 2
        }
        val scoreScale = max(12.0, grouped.size * 5.0)
        val heat = grid.map { MonitorHeatCell(lat(it.y), lng(it.x), exp(-max(0.0, it.cost - best.cost) / scoreScale)) }
        val plausible = grid.filter { it.cost - best.cost <= scoreScale * 1.4 }
        val fitRadius = plausible.maxOfOrNull { hypot(it.x - refined.x, it.y - refined.y) } ?: halfSpan
        val underdetermined = collinear || grouped.size < 4 || grouped.maxOf { it.accuracy } > 100.0
        var uncertainty = max(gpsFloor, fitRadius + step)
        if (underdetermined) uncertainty = max(uncertainty, spread + 40.0)
        if (plausible.any { abs(it.x - meanX) >= halfSpan - step || abs(it.y - meanY) >= halfSpan - step }) {
            uncertainty = max(uncertainty, halfSpan)
            advice += "The fit reaches the map edge; collect readings around the target."
        }
        val residuals = grouped.map { point ->
            point.rssi + 10 * pathLossExponent * log10(max(1.5, hypot(refined.x - point.x, refined.y - point.y)))
        }
        if (residuals.count { abs(it - median(residuals)) > OUTLIER_DB } > 0) {
            uncertainty = max(uncertainty, gpsFloor + 30.0)
            advice += "Some signal readings disagree strongly; repeat the route to check reflections or movement."
        }
        if (median(residuals.map { abs(it - median(residuals)) }) > 9.0) {
            uncertainty = max(uncertainty, spread + 50.0)
            advice += "Signal changes suggest movement or reflections; repeat from more positions."
        }
        if (advice.isEmpty()) advice += "For a tighter estimate, sample from a wider circle around the device."
        return MonitorEstimate(lat(refined.y), lng(refined.x), uncertainty, grouped.size, spread,
            lat(baselineY), lng(baselineX), band, underdetermined, advice, heat)
    }
}
