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

    @Test
    fun lastKnownFallbackUsesTheSameGates() {
        val candidates = listOf(
            fix("gps", 0.0, accuracyM = 5.0), // 60 s old at the reference: too stale
            fix("network", 55.0, northM = 3000.0, accuracyM = 150.0), // too coarse
            fix("fused", 58.0, northM = 20.0, accuracyM = 15.0),
        )
        assertEquals("fused", FixSelector.bestOf(candidates, referenceBootMs = at(60.0), nowBootMs = at(60.0))?.provider)
        assertNull(FixSelector.bestOf(candidates.take(2), at(60.0), at(60.0)))
    }

    @Test
    fun fastTravelWithMeasuredSpeedIsKept() {
        // 42 m/s is ~151 km/h: an Autobahn at 200 km/h or an ICE at 300 km/h
        // must not freeze the track when GPS reports that speed itself.
        for (mps in listOf(56.0, 83.0)) {
            val selector = FixSelector()
            for (i in 0..20) {
                assertTrue("$mps m/s fix $i", selector.offer(fix("gps", i.toDouble(), northM = i * mps, speedMps = mps), at(i.toDouble())))
            }
        }
    }

    @Test
    fun fastHopWithoutMeasuredSpeedIsStillRejected() {
        val selector = FixSelector()
        assertTrue(selector.offer(fix("gps", 0.0, speedMps = 1.0), at(0.0)))
        assertFalse(selector.offer(fix("network", 1.0, northM = 80.0, accuracyM = 20.0), at(1.0)))
        // A claimed speed doesn't excuse a hop far beyond it.
        assertFalse(selector.offer(fix("gps", 2.0, northM = 900.0, speedMps = 30.0), at(2.0)))
        // A glitchy reported speed is capped at 100 m/s.
        assertFalse(selector.offer(fix("gps", 3.0, northM = 1500.0, speedMps = 900.0), at(3.0)))
    }

    @Test
    fun relocatedWifiFixIsRejectedEvenWhenItClaimsPrecision() {
        // Seen in real data: indoors with no GPS, Wi-Fi location put the phone
        // 2,000+ km away for minutes, claiming 7-12 m accuracy, repeated
        // identically. Only GPS may override the jump gate or re-anchor.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("network", 0.0, accuracyM = 16.0), at(0.0)))
        for (i in 1..6) {
            assertFalse(selector.offer(fix("fused", i * 2.0, northM = 2_000_000.0, accuracyM = 7.0), at(i * 2.0)))
            if (i % 2 == 0) assertTrue(selector.offer(fix("network", i * 2.0, northM = 3.0, accuracyM = 16.0), at(i * 2.0)))
        }
        assertEquals(baseLat + 3.0 * m, selector.best(at(12.0))!!.lat, 1e-9)
    }

    @Test
    fun staleAnchorExpiresAfterHalfAnHour() {
        // Nothing accepted for 30 minutes (a flight, a GPS-less phone): the
        // next fix starts afresh instead of being judged against a position
        // from long ago. Shorter outages keep rejecting relocated fixes.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("network", 0.0, accuracyM = 30.0), at(0.0)))
        // 900 km north (9,000 km would be past the pole, i.e. invalid).
        assertFalse(selector.offer(fix("network", 124.0, northM = 900_000.0, accuracyM = 7.0), at(124.0)))
        assertTrue(selector.offer(fix("network", 1801.0, northM = 900_000.0, accuracyM = 30.0), at(1801.0)))
    }

    @Test
    fun gpsReanchorsAwayFromAFakeAnchorThatKeepsReporting() {
        // Reviewer scenario: a relocated-Wi-Fi fix becomes the anchor and the
        // fused/network providers keep reporting that same wrong place (so
        // they keep being accepted). GPS must still re-anchor.
        val selector = FixSelector()
        assertTrue(selector.offer(fix("network", 0.0, northM = 2_000_000.0, accuracyM = 7.0), at(0.0)))
        var t = 1.0
        repeat(10) {
            selector.offer(fix("gps", t, accuracyM = 8.0, speedMps = 0.0), at(t))
            selector.offer(fix("fused", t + 0.5, northM = 2_000_000.0, accuracyM = 7.0), at(t + 0.5))
            t += 1.0
        }
        assertEquals(baseLat, selector.best(at(t))!!.lat, 1e-6)
        // And once GPS holds the anchor, the fake fused fixes stay out.
        assertFalse(selector.offer(fix("fused", t, northM = 2_000_000.0, accuracyM = 7.0), at(t)))
    }
}
