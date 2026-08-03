package com.whyfi.app.mission

/** Port of `frontend/src/signalColor.ts`'s DBM_STEPS/signalStrengthColor
 * only — the LAN response-time scale in that file has no use here, Mission
 * view is WiFi-only. Same thresholds/colors, so a dBm number reads the same
 * on the phone as it does in the web UI.
 *
 * Colors are raw ARGB int literals (full alpha, `0xFF` + the PWA's hex
 * triplet) rather than `android.graphics.Color.parseColor(...)` — this
 * object has no Android framework dependency, deliberately, so it's plain-
 * JUnit-testable (this project's unit tests don't configure Robolectric,
 * and the stub android.jar used for unit tests throws on real framework
 * calls like Color.parseColor). */
object SignalColor {
    private data class Step(val min: Double, val color: Int)

    private val STEPS = listOf(
        Step(-55.0, 0xFF22C55E.toInt()), // Excellent
        Step(-67.0, 0xFF84CC16.toInt()), // Good
        Step(-75.0, 0xFFEAB308.toInt()), // Fair
        Step(-85.0, 0xFFF97316.toInt()), // Weak
        Step(Double.NEGATIVE_INFINITY, 0xFFEF4444.toInt()), // Very weak
    )

    fun signalStrengthColorArgb(dbm: Double): Int =
        (STEPS.firstOrNull { dbm >= it.min } ?: STEPS.last()).color
}
