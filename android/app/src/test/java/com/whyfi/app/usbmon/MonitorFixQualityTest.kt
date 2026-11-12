package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorFixQualityTest {
    private fun fix(provider: String, bootMs: Long, accuracy: Double, speed: Double = 0.0) =
        MonitorLocationCandidate(provider, bootMs, 48.0, 11.0, accuracy, speed)

    @Test fun fresh_gps_fix_is_green() {
        val status = MonitorFixQuality.classify(listOf(fix("gps", 99_000, 8.0)), 100_000)
        assertEquals(MonitorFixQuality.Level.GOOD, status.level)
        assertEquals("gps fix ±10 m", status.headline)
    }

    @Test fun coarse_but_usable_fix_is_orange() {
        // Effective accuracy 35 + 2 = 37 m: kept by the server, but coarse.
        val status = MonitorFixQuality.classify(listOf(fix("fused", 99_000, 35.0)), 100_000)
        assertEquals(MonitorFixQuality.Level.FAIR, status.level)
        assertTrue(status.detail.contains("coarsely"))
    }

    @Test fun fix_the_server_would_drop_is_red() {
        // The indoor case of 2026-11-11: fused/network fixes of 100-144 m.
        val status = MonitorFixQuality.classify(listOf(fix("fused", 100_000, 120.0), fix("network", 100_000, 144.0)), 100_000)
        assertEquals(MonitorFixQuality.Level.POOR, status.level)
        assertEquals("fused fix ±120 m", status.headline)
        assertTrue(status.detail.contains("will not be positioned"))
    }

    @Test fun a_good_fix_ages_into_orange_and_red() {
        val fixes = listOf(fix("gps", 100_000, 8.0))
        // 8 m + 20 s × 2 m/s = 48 m.
        assertEquals(MonitorFixQuality.Level.FAIR, MonitorFixQuality.classify(fixes, 120_000).level)
        assertEquals("gps fix ±48 m", MonitorFixQuality.classify(fixes, 120_000).headline)
        // 8 m + 60 s × 2 m/s = 128 m, and the age is shown.
        val stale = MonitorFixQuality.classify(fixes, 160_000)
        assertEquals(MonitorFixQuality.Level.POOR, stale.level)
        assertEquals("gps fix ±128 m (60 s old)", stale.headline)
    }

    @Test fun no_fix_and_expired_fix_are_red_with_different_words() {
        val none = MonitorFixQuality.classify(emptyList(), 100_000)
        assertEquals(MonitorFixQuality.Level.POOR, none.level)
        assertEquals("No location fix", none.headline)
        val expired = MonitorFixQuality.classify(listOf(fix("gps", 1_000, 8.0)), 400_000)
        assertEquals(MonitorFixQuality.Level.POOR, expired.level)
        assertEquals("Last location fix is older than 3 min", expired.headline)
    }
}
