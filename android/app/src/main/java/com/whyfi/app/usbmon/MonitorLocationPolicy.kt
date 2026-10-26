package com.whyfi.app.usbmon

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

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
    val held: Boolean = false,
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

    fun distanceM(a: MonitorLocationCandidate, b: MonitorLocationCandidate): Double {
        val lat1 = a.lat * PI / 180.0
        val lat2 = b.lat * PI / 180.0
        val dLat = lat2 - lat1
        val dLng = (b.lng - a.lng) * PI / 180.0
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Hold a compatible stationary position; accept better precision or convincing travel. */
    fun stabilize(anchor: MonitorLocationCandidate?, incoming: MonitorLocationCandidate): MonitorLocationCandidate {
        if (anchor == null) return incoming
        val distance = distanceM(anchor, incoming)
        val combinedAccuracy = anchor.accuracyM + incoming.accuracyM
        val better = incoming.accuracyM <= anchor.accuracyM * 0.75 &&
            anchor.accuracyM - incoming.accuracyM >= 5.0
        // Android may report an unknown speed as 15 m/s. The GPS provider
        // reports measured speed; a fused value of exactly 15 is ambiguous.
        val movingSpeed = incoming.speedMps >= 6.0 &&
            (incoming.provider == "gps" || incoming.provider == "fused" && incoming.speedMps != 15.0)
        val clearTravel = distance > max(25.0, combinedAccuracy * 3.0) ||
            (distance > max(20.0, combinedAccuracy * 1.5) &&
                incoming.accuracyM <= anchor.accuracyM * 1.5)
        return if (better || movingSpeed || clearTravel) incoming else anchor
    }

    /** Display the held center with uncertainty at least as wide as the raw fix. */
    fun displayAt(selected: MonitorSelectedLocation?, anchor: MonitorLocationCandidate?): MonitorSelectedLocation? {
        if (selected == null || anchor == null) return selected
        val held = anchor.bootMs != selected.fix.bootMs || anchor.provider != selected.fix.provider
        if (!held) return selected
        return selected.copy(
            fix = selected.fix.copy(lat = anchor.lat, lng = anchor.lng),
            effectiveAccuracyM = max(selected.effectiveAccuracyM,
                anchor.accuracyM + distanceM(anchor, selected.fix)),
            held = true,
        )
    }

    /** Raw providers stay in the log; the visible route uses only the best fix at each update. */
    fun bestRoute(raw: List<MonitorReceiverFix>): List<MonitorReceiverFix> {
        val ordered = raw.sortedBy { it.atMs }
        val latestByProvider = mutableMapOf<String, MonitorReceiverFix>()
        val route = mutableListOf<MonitorReceiverFix>()
        var anchor: MonitorLocationCandidate? = null
        var previousBootMs = 0L
        ordered.forEach { fix ->
            if (previousBootMs > 0 && fix.bootMs + 60_000 < previousBootMs) {
                latestByProvider.clear()
                anchor = null
            }
            previousBootMs = fix.bootMs
            latestByProvider[fix.provider] = fix
            val selected = select(latestByProvider.values.map { it.candidate() }, fix.bootMs)?.fix ?: return@forEach
            val best = latestByProvider[selected.provider] ?: return@forEach
            val accepted = stabilize(anchor, selected)
            if (accepted != anchor && route.lastOrNull()?.let { it.bootMs == best.bootMs && it.provider == best.provider } != true)
                route += best
            anchor = accepted
        }
        return route
    }
}
