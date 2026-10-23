package com.whyfi.app.usbmon

import kotlin.math.abs
import kotlin.math.max

/** Android location fixes are receiver measurements, not transmitter positions. */
data class MonitorLocationCandidate(
    val provider: String,
    val bootMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double = 0.0,
)

data class MonitorSelectedLocation(
    val fix: MonitorLocationCandidate,
    val ageMs: Long,
    val effectiveAccuracyM: Double,
)

/** Raw receiver fix retained even when no target was pinned or heard. */
data class MonitorReceiverFix(
    val atMs: Long,
    val bootMs: Long,
    val provider: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double,
) {
    fun candidate() = MonitorLocationCandidate(provider, bootMs, lat, lng, accuracyM, speedMps)
}

/** Prefer a good recent fix, but keep coarse network/fused fixes with honest uncertainty. */
object MonitorLocationPolicy {
    const val MAX_FIX_AGE_MS = 180_000L
    private const val MIN_MOTION_ALLOWANCE_M_PER_S = 2.0

    fun effectiveAccuracy(accuracyM: Double, ageMs: Long, speedMps: Double = 0.0): Double =
        accuracyM + ageMs / 1000.0 * max(MIN_MOTION_ALLOWANCE_M_PER_S, speedMps * 1.5)

    fun select(fixes: Collection<MonitorLocationCandidate>, radioBootMs: Long): MonitorSelectedLocation? {
        if (radioBootMs <= 0) return null
        return fixes.mapNotNull { fix ->
            val age = abs(fix.bootMs - radioBootMs)
            if (fix.bootMs <= 0 || age > MAX_FIX_AGE_MS || !fix.lat.isFinite() || !fix.lng.isFinite() ||
                fix.lat !in -90.0..90.0 || fix.lng !in -180.0..180.0 ||
                !fix.accuracyM.isFinite() || fix.accuracyM < 0.0 ||
                !fix.speedMps.isFinite() || fix.speedMps !in 0.0..100.0
            ) null else MonitorSelectedLocation(fix, age, effectiveAccuracy(fix.accuracyM, age, fix.speedMps))
        }.minWithOrNull(compareBy<MonitorSelectedLocation> { it.effectiveAccuracyM }.thenBy { it.ageMs })
    }

    /** Raw providers stay in the log; the visible route uses only the best fix at each update. */
    fun bestRoute(raw: List<MonitorReceiverFix>): List<MonitorReceiverFix> {
        val ordered = raw.sortedBy { it.bootMs }
        val latestByProvider = mutableMapOf<String, MonitorReceiverFix>()
        val route = mutableListOf<MonitorReceiverFix>()
        ordered.forEach { fix ->
            latestByProvider[fix.provider] = fix
            val selected = select(latestByProvider.values.map { it.candidate() }, fix.bootMs)?.fix ?: return@forEach
            val best = latestByProvider[selected.provider] ?: return@forEach
            if (route.lastOrNull()?.let { it.bootMs == best.bootMs && it.provider == best.provider } != true)
                route += best
        }
        return route
    }
}
