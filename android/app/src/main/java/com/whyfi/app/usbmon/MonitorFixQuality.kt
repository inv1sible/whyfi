package com.whyfi.app.usbmon

import kotlin.math.roundToInt

/** The Live tab's banner about the receiver position (user, 2026-11-12).
 *
 * Says whether readings captured right now will get a position on the
 * server, which keeps a USB reading only when its receiver fix, aged by the
 * time between fix and reading, is better than [SERVER_LIMIT_M]
 * (scans/monitor_import.py MAX_EFFECTIVE_ACCURACY_M). A day of indoor
 * captures imported as nothing before this existed, with no hint on the
 * phone. Same selection as the capture itself: [MonitorLocationPolicy.select]
 * over the live fixes, judged at the current boot clock. */
object MonitorFixQuality {
    const val SERVER_LIMIT_M = 100.0
    const val GOOD_M = 30.0

    enum class Level { GOOD, FAIR, POOR }

    data class Status(val level: Level, val headline: String, val detail: String)

    fun classify(fixes: Collection<MonitorLocationCandidate>, nowBootMs: Long): Status {
        val selected = MonitorLocationPolicy.select(fixes, nowBootMs)
        if (selected == null) {
            val any = fixes.any { it.bootMs > 0 }
            return Status(Level.POOR,
                if (any) "Last location fix is older than ${MonitorLocationPolicy.MAX_FIX_AGE_MS / 60_000} min"
                else "No location fix",
                "Readings captured now will not be positioned on the server. Go outdoors or near a window for GPS.")
        }
        val fix = selected.fix
        val metres = selected.effectiveAccuracyM.roundToInt()
        val age = selected.ageMs / 1000
        val headline = "${fix.provider} fix ±$metres m" + if (age >= 30) " ($age s old)" else ""
        return when {
            selected.effectiveAccuracyM > SERVER_LIMIT_M -> Status(Level.POOR, headline,
                "Worse than ${SERVER_LIMIT_M.roundToInt()} m: readings captured now will not be positioned on the server. " +
                    "Go outdoors or near a window for GPS.")
            selected.effectiveAccuracyM > GOOD_M -> Status(Level.FAIR, headline,
                "Readings are positioned, but coarsely. A GPS fix outdoors gets below ${GOOD_M.roundToInt()} m.")
            else -> Status(Level.GOOD, headline, "Readings captured now are positioned on the server.")
        }
    }
}
