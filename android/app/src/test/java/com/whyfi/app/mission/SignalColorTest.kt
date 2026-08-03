package com.whyfi.app.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pinned against `frontend/src/signalColor.ts`'s DBM_STEPS thresholds/colors. */
class SignalColorTest {

    @Test
    fun `boundary values match each DBM_STEPS threshold`() {
        assertEquals(0xFF22C55E.toInt(), SignalColor.signalStrengthColorArgb(-55.0)) // Excellent
        assertEquals(0xFF22C55E.toInt(), SignalColor.signalStrengthColorArgb(-40.0)) // well above Excellent
        assertEquals(0xFF84CC16.toInt(), SignalColor.signalStrengthColorArgb(-67.0)) // Good
        assertEquals(0xFFEAB308.toInt(), SignalColor.signalStrengthColorArgb(-75.0)) // Fair
        assertEquals(0xFFF97316.toInt(), SignalColor.signalStrengthColorArgb(-85.0)) // Weak
        assertEquals(0xFFEF4444.toInt(), SignalColor.signalStrengthColorArgb(-95.0)) // Very weak
    }

    @Test
    fun `just above a threshold picks the better step`() {
        assertEquals(0xFF22C55E.toInt(), SignalColor.signalStrengthColorArgb(-54.9))
        assertEquals(0xFF84CC16.toInt(), SignalColor.signalStrengthColorArgb(-66.9))
    }

    @Test
    fun `just below a threshold picks the worse step`() {
        assertEquals(0xFF84CC16.toInt(), SignalColor.signalStrengthColorArgb(-55.1))
        assertEquals(0xFFEAB308.toInt(), SignalColor.signalStrengthColorArgb(-67.1))
    }
}
