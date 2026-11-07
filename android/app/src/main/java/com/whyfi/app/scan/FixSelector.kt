package com.whyfi.app.scan

import com.whyfi.app.usbmon.MonitorLocationPolicy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One platform location fix. Times are `elapsedRealtime` milliseconds, so a
 * wall-clock change can't make a fix look fresh or stale. */
data class LocationFix(
    val provider: String,
    val bootMs: Long,
    val lat: Double,
    val lng: Double,
    /** Null when the provider reported no accuracy. */
    val accuracyM: Double?,
    val speedMps: Double? = null,
)

/**
 * Picks the position for a scan pass from a stream of live fixes. Pure
 * Kotlin, no Android types, unit-tested (FixSelectorTest).
 *
 * The gates are Fieldwatch's (`FieldwatchApp.acceptFix`, `Geo.hopPlausible`,
 * MIT): accuracy ≤ 75 m, age ≤ 30 s, and no hop of more than 40 m faster than
 * 42 m/s from the last accepted fix. Three deliberate changes:
 * - a hop that is markedly *more precise* than the anchor is accepted, so a
 *   coarse or stale first fix can't lock out good GPS far away;
 * - three consecutive rejected fixes that agree with each other re-anchor,
 *   so one wrong precise fix can't freeze the track either;
 * - fixes under 0.8 s apart are judged over 0.8 s instead of waved through
 *   (Fieldwatch treats them as always plausible, which lets a network fix
 *   arriving right after a GPS fix teleport).
 *
 * The pass position is the accepted fix with the lowest effective
 * uncertainty at the pass midpoint (MonitorLocationPolicy.effectiveAccuracy:
 * accuracy plus how far the phone may have moved since).
 */
class FixSelector {
    private val accepted = ArrayDeque<LocationFix>()
    private val rejected = ArrayDeque<LocationFix>()

    /** Offers one fix. Returns whether it was accepted. */
    @Synchronized
    fun offer(fix: LocationFix, nowBootMs: Long): Boolean {
        if (!valid(fix) || abs(nowBootMs - fix.bootMs) > MAX_AGE_MS) return false
        val anchor = accepted.maxByOrNull { it.bootMs }
        val ok = anchor == null || hopPlausible(anchor, fix) || morePrecise(fix, anchor) || reanchors(fix)
        if (ok) {
            accepted.addLast(fix)
            rejected.clear()
            while (accepted.isNotEmpty() && nowBootMs - accepted.first().bootMs > HISTORY_MS) accepted.removeFirst()
        }
        return ok
    }

    /** The best accepted fix for a pass centred on [referenceBootMs], or null
     * when nothing fresh enough was accepted (no position beats a stale one). */
    @Synchronized
    fun best(referenceBootMs: Long): LocationFix? =
        accepted
            .filter { abs(it.bootMs - referenceBootMs) <= MAX_AGE_MS }
            .minWithOrNull(
                compareBy<LocationFix> {
                    MonitorLocationPolicy.effectiveAccuracy(
                        it.accuracyM ?: MAX_ACCURACY_M,
                        abs(it.bootMs - referenceBootMs),
                        it.speedMps ?: 0.0,
                    )
                }.thenBy { abs(it.bootMs - referenceBootMs) },
            )

    @Synchronized
    fun reset() {
        accepted.clear()
        rejected.clear()
    }

    private fun reanchors(fix: LocationFix): Boolean {
        rejected.addLast(fix)
        while (rejected.size > REANCHOR_COUNT) rejected.removeFirst()
        return rejected.size == REANCHOR_COUNT && rejected.zipWithNext().all { (a, b) -> hopPlausible(a, b) }
    }

    companion object {
        const val MAX_ACCURACY_M = 75.0
        const val MAX_AGE_MS = 30_000L
        const val SPIKE_MAX_SPEED_MPS = 42.0
        const val SPIKE_MIN_HOP_M = 40.0
        const val SPIKE_MIN_DT_MS = 800L
        const val PRECISE_FACTOR = 0.75
        const val REANCHOR_COUNT = 3
        private const val HISTORY_MS = 120_000L

        fun valid(fix: LocationFix): Boolean =
            fix.lat.isFinite() && fix.lng.isFinite() && fix.lat in -90.0..90.0 && fix.lng in -180.0..180.0 &&
                !(fix.lat == 0.0 && fix.lng == 0.0) &&
                (fix.accuracyM == null || (fix.accuracyM.isFinite() && fix.accuracyM in 0.0..MAX_ACCURACY_M))

        /** Fieldwatch's Geo.hopPlausible, with the time gap clamped to 0.8 s. */
        fun hopPlausible(from: LocationFix, to: LocationFix): Boolean {
            val d = distanceM(from, to)
            if (d <= SPIKE_MIN_HOP_M) return true
            val dtS = max(abs(to.bootMs - from.bootMs), SPIKE_MIN_DT_MS) / 1000.0
            return d / dtS <= SPIKE_MAX_SPEED_MPS
        }

        private fun morePrecise(fix: LocationFix, anchor: LocationFix): Boolean =
            (fix.accuracyM ?: MAX_ACCURACY_M) <= (anchor.accuracyM ?: MAX_ACCURACY_M) * PRECISE_FACTOR

        fun distanceM(a: LocationFix, b: LocationFix): Double {
            val lat1 = a.lat * PI / 180.0
            val lat2 = b.lat * PI / 180.0
            val dLat = lat2 - lat1
            val dLng = (b.lng - a.lng) * PI / 180.0
            val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
            return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
