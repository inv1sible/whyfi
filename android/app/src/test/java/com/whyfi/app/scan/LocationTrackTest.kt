package com.whyfi.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTrackTest {
    private fun p(t: Long, lat: Double, provider: String = "gps", acc: Double? = 5.0) = TrackPoint(t, lat, 13.0, acc, provider)
    private fun build(vararg points: TrackPoint) = points.fold(emptyList<TrackPoint>()) { acc, pt -> LocationTrack.append(acc, pt) }

    @Test
    fun gpsWinsOverFusedAndNetworkWhileItIsFresh() {
        val track = build(
            p(0, 52.0), p(1_000, 52.0001, "fused"), p(2_000, 52.0002, "network"),
            p(7_000, 52.0003, "fused"), p(20_000, 52.0004, "network"), p(40_000, 52.0005, "network"),
        )
        assertEquals(listOf("gps", "fused", "network"), track.map { it.provider })
    }

    @Test
    fun badAccuracyDuplicatesAndOutOfOrderFixesAreDropped() {
        val track = build(p(0, 52.0), p(1_000, 52.0, acc = 6.0), p(2_000, 52.001, acc = 900.0), p(3_000, 52.0, acc = 2.0), p(2_500, 52.002))
        assertEquals(listOf(3_000L), track.map { it.atMs })
        assertEquals(listOf(2.0), track.map { it.accuracyM })
    }

    @Test
    fun lengthAndMedianAccuracy() {
        val track = build(p(0, 52.0, acc = 3.0), p(10_000, 52.001, acc = 9.0), p(20_000, 52.002, acc = 5.0))
        assertEquals(222.6, LocationTrack.lengthM(track), 1.0)
        assertEquals(5.0, LocationTrack.medianAccuracyM(track)!!, 1e-9)
    }

    @Test
    fun trackIsCapped() {
        var track = emptyList<TrackPoint>()
        repeat(LocationTrack.MAX_POINTS + 10) { track = LocationTrack.append(track, p(it * 20_000L, 52.0 + it * 0.001)) }
        assertEquals(LocationTrack.MAX_POINTS, track.size)
        assertTrue(track.first().atMs == 10 * 20_000L)
    }
}
