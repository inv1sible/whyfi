package com.whyfi.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the scan position gates ported from Fieldwatch (acceptFix / hopPlausible).
 * See docs/superpowers/plans/2026-11-07-live-scan-location.md. */
class FixSelectorTest {

    // ~1 m of latitude in degrees.
    private val m = 1.0 / 111_320.0
    private val baseLat = 48.1351
    private val baseLng = 11.5820

    private fun fix(
        provider: String,
        atS: Double,
        northM: Double = 0.0,
        accuracyM: Double? = 8.0,
        speedMps: Double? = null,
    ) = LocationFix(provider, (atS * 1000).toLong(), baseLat + northM * m, baseLng, accuracyM, speedMps)

    private fun at(s: Double) = (s * 1000).toLong()

    @Test
    fun acceptsFreshAccurateFix() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 10.0), nowBootMs = at(10.5)))
        assertEquals("gps", selector.best(at(10.0))?.provider)
    }

    @Test
    fun rejectsCoarseStaleOrInvalidFixes() {
        val selector = FixSelector()
        assertFalse(selector.offer(fix("network", 10.0, accuracyM = 80.0), at(10.0)))
        assertFalse(selector.offer(fix("gps", 10.0), at(41.0)))
        assertFalse(selector.offer(LocationFix("gps", at(10.0), Double.NaN, baseLng, 5.0), at(10.0)))
        assertFalse(selector.offer(LocationFix("gps", at(10.0), 91.0, baseLng, 5.0), at(10.0)))
        assertNull(selector.best(at(10.0)))
    }

    @Test
    fun missingAccuracyIsAcceptedButRankedAsCoarse() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("network", 10.0, accuracyM = null), at(10.0)))
        assertTrue(selector.offer(fix("gps", 10.0, northM = 5.0, accuracyM = 20.0), at(10.0)))
        assertEquals("gps", selector.best(at(10.0))?.provider)
    }

    @Test
    fun networkJumpIsRejected() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 0.0, accuracyM = 8.0), at(0.0)))
        assertFalse(selector.offer(fix("network", 4.0, northM = 600.0, accuracyM = 40.0), at(4.0)))
        assertEquals(baseLat, selector.best(at(4.0))!!.lat, 1e-9)
    }

    @Test
    fun nearSimultaneousFarFixIsNotPlausible() {
        // Fieldwatch's hopPlausible waves through anything under 0.8 s; here
        // the window is clamped to 0.8 s, so a 2 km teleport can't slip in.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 0.0), at(0.0)))
        assertFalse(selector.offer(fix("network", 0.1, northM = 2000.0, accuracyM = 30.0), at(0.1)))
    }

    @Test
    fun preciseFixOverridesCoarseAnchor() {
        // A stale/coarse first fix must not lock out good GPS far away.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("network", 0.0, accuracyM = 60.0), at(0.0)))
        assertTrue(selector.offer(fix("gps", 2.0, northM = 2000.0, accuracyM = 8.0), at(2.0)))
        assertEquals(baseLat + 2000.0 * m, selector.best(at(2.0))!!.lat, 1e-9)
    }

    @Test
    fun consistentRejectsReanchor() {
        // A wrong but precise anchor: three agreeing fixes elsewhere win.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 0.0, accuracyM = 10.0), at(0.0)))
        assertFalse(selector.offer(fix("gps", 1.0, northM = 1000.0, accuracyM = 12.0), at(1.0)))
        assertFalse(selector.offer(fix("gps", 2.0, northM = 1001.0, accuracyM = 12.0), at(2.0)))
        assertTrue(selector.offer(fix("gps", 3.0, northM = 1002.0, accuracyM = 12.0), at(3.0)))
        assertEquals(baseLat + 1002.0 * m, selector.best(at(3.0))!!.lat, 1e-9)
    }

    @Test
    fun scatteredRejectsDoNotReanchor() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 0.0, accuracyM = 10.0), at(0.0)))
        assertFalse(selector.offer(fix("network", 1.0, northM = 1000.0, accuracyM = 30.0), at(1.0)))
        assertFalse(selector.offer(fix("network", 2.0, northM = -1500.0, accuracyM = 30.0), at(2.0)))
        assertFalse(selector.offer(fix("network", 3.0, northM = 1000.0, accuracyM = 30.0), at(3.0)))
    }

    @Test
    fun walkingAndDrivingTracksAreKept() {
        val selector = FixSelector()
        for (i in 0..30) assertTrue(selector.offer(fix("gps", i.toDouble(), northM = i * 1.4), at(i.toDouble())))
        val driving = FixSelector()
        for (i in 0..30) assertTrue(driving.offer(fix("gps", i.toDouble(), northM = i * 30.0), at(i.toDouble())))
    }

    @Test
    fun bestPrefersPreciseFixNearTheReference() {
        val selector = FixSelector()
        selector.offer(fix("gps", 10.0, accuracyM = 5.0), at(10.0))
        selector.offer(fix("network", 15.0, northM = 10.0, accuracyM = 30.0), at(15.0))
        // gps: 5 m + 5 s × 2 m/s = 15 m beats network's 30 m.
        assertEquals("gps", selector.best(at(15.0))?.provider)
        // Far from the gps fix, the fresher network fix wins.
        selector.offer(fix("network", 38.0, northM = 12.0, accuracyM = 30.0), at(38.0))
        assertEquals("network", selector.best(at(38.0))?.provider)
    }

    @Test
    fun staleOnlyGivesNull() {
        val selector = FixSelector()
        selector.offer(fix("gps", 0.0), at(0.0))
        assertNull(selector.best(at(31.0)))
        assertEquals(500L, selector.best(at(0.5))?.let { at(0.5) - it.bootMs })
    }

    @Test
    fun outOfOrderOlderFixIsJudgedByAbsoluteTimeGap() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 10.0), at(10.0)))
        assertFalse(selector.offer(fix("network", 9.5, northM = 900.0, accuracyM = 25.0), at(10.0)))
    }
}
