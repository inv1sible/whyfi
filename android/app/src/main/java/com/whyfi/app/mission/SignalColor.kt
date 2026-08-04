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

    /** Per-BSSID hue for multi-AP deployments: hashes the BSSID string to a
     * stable hue on the color wheel, then returns a full-alpha ARGB int at
     * fixed saturation/value so groups are visually distinct on the map.
     * Used as the cone's edge color (replacing signal-strength coloring)
     * when more than one BSSID group is present — see MissionScreen. */
    fun bssidColorArgb(bssid: String): Int {
        var hash = 0
        for (c in bssid) hash = hash * 31 + c.code
        val hue = ((hash and 0xFFFF).toFloat() / 0xFFFF) * 360f
        return hsvToArgb(hue, 0.65f, 0.85f)
    }

    /** HSV → ARGB. Pure Kotlin (no android.graphics.Color) so this stays
     * unit-testable — see the class doc. */
    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

