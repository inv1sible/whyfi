package com.whyfi.app.usbmon

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLocalizationTest {
    private val baseLat = 48.0
    private val baseLng = 11.0
    private fun sample(x: Double, y: Double, targetX: Double = 20.0, targetY: Double = 25.0,
                       rssiOverride: Int? = null, gpsAccuracy: Double = 3.0) = MonitorGeoSample(
        "00:11:22:33:44:55", 1_000, baseLat + y / 111_320.0,
        baseLng + x / (111_320.0 * cos(baseLat * PI / 180)), gpsAccuracy,
        rssiOverride ?: (-40 - 27 * log10(hypot(x - targetX, y - targetY).coerceAtLeast(1.5))).toInt(), 6,
    )

    @Test fun geographically_diverse_samples_estimate_a_known_stationary_target() {
        val points = listOf(sample(-40.0, -30.0), sample(40.0, -30.0), sample(-40.0, 50.0),
            sample(50.0, 50.0), sample(0.0, -50.0), sample(60.0, 0.0))
        val estimate = MonitorLocalization.estimate(points)!!
        val east = (estimate.lng - baseLng) * 111_320.0 * cos(baseLat * PI / 180)
        val north = (estimate.lat - baseLat) * 111_320.0
        assertTrue("error ${hypot(east - 20, north - 25)}m", hypot(east - 20, north - 25) < 30)
        assertEquals(6, estimate.spatialGroups)
        assertTrue(estimate.uncertaintyM >= 15)
        assertEquals(625, estimate.heat.size)
    }

    @Test fun repeated_packets_at_one_place_do_not_fabricate_geometry() {
        val estimate = MonitorLocalization.estimate(List(200) { sample(0.0, 0.0) })!!
        assertEquals(1, estimate.spatialGroups)
        assertTrue(estimate.provisional)
        assertTrue(estimate.uncertaintyM >= 100)
    }

    @Test fun poor_gps_is_rejected_but_single_good_point_gets_a_cautious_hint() {
        assertEquals(null, MonitorLocalization.estimate(listOf(sample(0.0, 0.0, gpsAccuracy = 200.0))))
        val estimate = MonitorLocalization.estimate(listOf(sample(10.0, 20.0)))
        assertNotNull(estimate)
        assertTrue(estimate!!.advice.any { it.contains("separated") })
    }

    @Test fun collinear_route_keeps_an_estimate_and_recommends_sideways_sampling() {
        val estimate = MonitorLocalization.estimate(listOf(sample(-60.0, 0.0), sample(-20.0, 0.0),
            sample(20.0, 0.0), sample(60.0, 0.0)))!!
        assertTrue(estimate.provisional)
        assertTrue(estimate.advice.any { it.contains("side street") })
    }

    @Test fun one_bad_signal_reading_does_not_displace_the_estimate_far_from_target() {
        val points = listOf(sample(-40.0, -30.0), sample(40.0, -30.0), sample(-40.0, 50.0),
            sample(50.0, 50.0), sample(0.0, -50.0), sample(60.0, 0.0),
            sample(70.0, 60.0, rssiOverride = -25))
        val estimate = MonitorLocalization.estimate(points)!!
        val east = (estimate.lng - baseLng) * 111_320.0 * cos(baseLat * PI / 180)
        val north = (estimate.lat - baseLat) * 111_320.0
        assertTrue("error ${hypot(east - 20, north - 25)}m", hypot(east - 20, north - 25) < 55)
    }
}
