package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLocationPolicyTest {
    private fun fix(provider: String, bootMs: Long, accuracy: Double) =
        MonitorLocationCandidate(provider, bootMs, 48.0, 11.0, accuracy)

    @Test fun accurate_gps_beats_a_newer_but_coarser_network_fix() {
        val selected = MonitorLocationPolicy.select(
            listOf(fix("gps", 90_000, 8.0), fix("network", 100_000, 90.0)), 100_000,
        )!!
        assertEquals("gps", selected.fix.provider)
        assertEquals(10_000, selected.ageMs)
        assertEquals(28.0, selected.effectiveAccuracyM, 0.001)
    }

    @Test fun fresh_assisted_fix_beats_stale_gps_when_effective_uncertainty_is_lower() {
        val selected = MonitorLocationPolicy.select(
            listOf(fix("gps", 50_000, 5.0), fix("fused", 99_000, 35.0)), 100_000,
        )!!
        assertEquals("fused", selected.fix.provider)
        assertEquals(37.0, selected.effectiveAccuracyM, 0.001)
    }

    @Test fun coarse_assisted_fix_is_kept_with_its_full_uncertainty() {
        val selected = MonitorLocationPolicy.select(listOf(fix("fused", 90_000, 350.0)), 100_000)!!
        assertEquals("fused", selected.fix.provider)
        assertEquals(370.0, selected.effectiveAccuracyM, 0.001)
    }

    @Test fun old_or_invalid_fixes_cannot_be_attached_to_a_packet() {
        assertNull(MonitorLocationPolicy.select(listOf(fix("network", 1_000, 50.0)), 200_000))
        assertNull(MonitorLocationPolicy.select(listOf(fix("fused", 100_000, Double.NaN)), 100_000))
        assertTrue(MonitorLocationPolicy.select(listOf(fix("network", 60_000, 200.0)), 100_000) != null)
    }

    @Test fun driving_speed_expands_uncertainty_for_delayed_fixes() {
        val driving = fix("gps", 100_000, 10.0).copy(speedMps = 30.0)
        val selected = MonitorLocationPolicy.select(listOf(driving), 130_000)!!
        assertEquals(1_360.0, selected.effectiveAccuracyM, 0.001)
    }

    @Test fun route_keeps_only_the_best_available_fix_at_each_update() {
        val gps = MonitorReceiverFix(1_000, 100_000, "gps", 48.0, 11.0, 8.0, 0.0)
        val poorNetwork = MonitorReceiverFix(2_000, 101_000, "network", 48.1, 11.1, 300.0, 0.0)
        val betterNetwork = poorNetwork.copy(atMs = 62_000, bootMs = 161_000, accuracyM = 40.0)
        assertEquals(listOf(gps, betterNetwork), MonitorLocationPolicy.bestRoute(
            listOf(poorNetwork, betterNetwork, gps)))
    }
}
