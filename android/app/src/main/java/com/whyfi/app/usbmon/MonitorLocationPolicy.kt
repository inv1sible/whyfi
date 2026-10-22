package com.whyfi.app.usbmon

import kotlin.math.abs

/** Android location fixes are receiver measurements, not transmitter positions. */
data class MonitorLocationCandidate(
    val provider: String,
    val bootMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
)

data class MonitorSelectedLocation(
    val fix: MonitorLocationCandidate,
    val ageMs: Long,
    val effectiveAccuracyM: Double,
)

/** Prefer a good recent fix, but keep coarse network/fused fixes with honest uncertainty. */
object MonitorLocationPolicy {
    const val MAX_FIX_AGE_MS = 60_000L
    private const val MOTION_ALLOWANCE_M_PER_S = 2.0

    fun effectiveAccuracy(accuracyM: Double, ageMs: Long): Double =
        accuracyM + ageMs / 1000.0 * MOTION_ALLOWANCE_M_PER_S

    fun select(fixes: Collection<MonitorLocationCandidate>, radioBootMs: Long): MonitorSelectedLocation? {
        if (radioBootMs <= 0) return null
        return fixes.mapNotNull { fix ->
            val age = abs(fix.bootMs - radioBootMs)
            if (fix.bootMs <= 0 || age > MAX_FIX_AGE_MS || !fix.lat.isFinite() || !fix.lng.isFinite() ||
                fix.lat !in -90.0..90.0 || fix.lng !in -180.0..180.0 ||
                !fix.accuracyM.isFinite() || fix.accuracyM < 0.0
            ) null else MonitorSelectedLocation(fix, age, effectiveAccuracy(fix.accuracyM, age))
        }.minWithOrNull(compareBy<MonitorSelectedLocation> { it.effectiveAccuracyM }.thenBy { it.ageMs })
    }
}
