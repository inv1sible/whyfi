package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCoTravelTest {
    // ~0.009° latitude ≈ 1 km.
    private fun sample(mac: String, minute: Int, lat: Double, accuracy: Double = 10.0) =
        MonitorGeoSample(mac, minute * 60_000L, lat, 13.0, accuracy, -60, 6)

    @Test fun deviceHeardAlongTheDriveQualifies() {
        val drive = (0..20).map { sample("car", it, 52.0 + it * 0.0045) } // 500 m per minute, 20 min
        val trail = MonitorCoTravel.analyze(drive).single()
        assertTrue(trail.places.size >= 10)
        assertTrue(trail.qualifies(3))
        assertEquals(20 * 60_000L, trail.spanMs)
    }

    @Test fun fixedApPassedByCarDoesNotQualify() {
        // Heard over 1.5 km of road but only for 2 minutes.
        val passBy = (0..3).map { sample("ap", 0, 52.0 + it * 0.0045).copy(atMs = it * 40_000L) }
        val trail = MonitorCoTravel.analyze(passBy).single()
        assertEquals(4, trail.places.size)
        assertFalse(trail.qualifies(3))
    }

    @Test fun deviceAtOnePlaceOnlyHasNoTrail() {
        val home = (0..60).map { sample("tv", it, 52.0 + (it % 3) * 0.0003) } // within ~70 m for an hour
        assertTrue(MonitorCoTravel.analyze(home).isEmpty())
    }

    @Test fun twoPlacesMeetTheLowestThresholdOnly() {
        val t = MonitorCoTravel.analyze(listOf(sample("x", 0, 52.0), sample("x", 30, 52.02))).single()
        assertTrue(t.qualifies(2))
        assertFalse(t.qualifies(3))
    }

    @Test fun inaccurateFixesAndCutoffAreIgnored() {
        val samples = listOf(sample("x", 0, 52.0), sample("x", 15, 52.02, accuracy = 900.0), sample("x", 30, 52.04))
        assertEquals(2, MonitorCoTravel.analyze(samples).single().places.size)
        assertTrue(MonitorCoTravel.analyze(samples, cutoffMs = 20 * 60_000L).isEmpty())
    }

    @Test fun placesAreHundredMetresApart() {
        // 40 m steps across 400 m: places open at 0, 120, 240 and 360 m.
        val walk = (0..10).map { sample("w", it, 52.0 + it * 0.00036) }
        assertEquals(4, MonitorCoTravel.analyze(walk).single().places.size)
        // Fixes worse than 50 m are skipped.
        assertTrue(MonitorCoTravel.analyze(listOf(sample("x", 0, 52.0), sample("x", 20, 52.01, accuracy = 80.0))).isEmpty())
    }

    @Test fun trackerReportsNewPlaces() {
        val tracker = MonitorCoTravel.Tracker()
        assertTrue(tracker.add("a", 0, 52.0, 13.0))
        assertFalse(tracker.add("a", 1, 52.0005, 13.0)) // ~55 m
        assertTrue(tracker.add("a", 2, 52.01, 13.0))
        assertEquals(listOf("a"), tracker.trails().map { it.mac })
    }
}
