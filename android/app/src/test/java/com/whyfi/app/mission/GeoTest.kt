package com.whyfi.app.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pinned against `frontend/src/geo.ts`'s own formulas, plus (for the
 * haversine/centroid cases) the same fixture coordinates already pinned in
 * `backend/scans/tests.py`'s `HaversineTests`/`CentroidParityTests` — this
 * is a third transcription of the same math (frontend TS, backend Python,
 * this Kotlin port), and reusing the same known-good numbers keeps all
 * three honest against each other.
 */
class GeoTest {

    @Test
    fun `known distance matches the backend's pinned fixture`() {
        // backend/scans/tests.py HaversineTests.test_known_distance — one
        // degree of latitude is ~111.2km anywhere on the sphere.
        val distance = Geo.haversineDistanceMeters(48.0, 11.0, 49.0, 11.0)
        assertTrue("expected ~111195m, got $distance", abs(distance - 111195) < 200)
    }

    @Test
    fun `zero distance for identical points`() {
        assertEquals(0.0, Geo.haversineDistanceMeters(48.1351, 11.582, 48.1351, 11.582), 0.0001)
    }

    @Test
    fun `bearing points due north, east, south, and west`() {
        val origin = 0.0 to 0.0
        assertEquals(0.0, Geo.initialBearingDegrees(origin.first, origin.second, 1.0, 0.0), 0.5)
        assertEquals(90.0, Geo.initialBearingDegrees(origin.first, origin.second, 0.0, 1.0), 0.5)
        assertEquals(180.0, Geo.initialBearingDegrees(origin.first, origin.second, -1.0, 0.0), 0.5)
        assertEquals(-90.0, Geo.initialBearingDegrees(origin.first, origin.second, 0.0, -1.0), 0.5)
    }

    @Test
    fun `weighted centroid matches the backend's pinned fixture cases`() {
        // backend/scans/tests.py CentroidParityTests.test_matches_the_frontend_formula
        val single = Geo.weightedCentroid(listOf(WeightedLatLng(48.1351, 11.582, -50.0)))
        assertEquals(48.1351, single.lat, 0.0000001)
        assertEquals(11.582, single.lng, 0.0000001)
    }

    @Test
    fun `equal weights collapse to a plain mean`() {
        // CentroidParityTests.test_equal_weights_collapse_to_a_plain_mean
        val centroid = Geo.weightedCentroid(
            listOf(
                WeightedLatLng(0.0, 0.0, -60.0),
                WeightedLatLng(2.0, 4.0, -60.0),
            ),
        )
        assertEquals(1.0, centroid.lat, 0.0000001)
        assertEquals(2.0, centroid.lng, 0.0000001)
    }

    @Test
    fun `weakest reading still contributes`() {
        // CentroidParityTests.test_weakest_reading_still_contributes
        val centroid = Geo.weightedCentroid(
            listOf(
                WeightedLatLng(0.0, 0.0, -90.0),
                WeightedLatLng(1.0, 0.0, -30.0),
            ),
        )
        assertTrue(centroid.lat < 1.0)
        assertTrue(centroid.lat > 0.5)
    }

    @Test
    fun `cone is null when apex and target are too close`() {
        val apex = LatLng(48.1351, 11.582)
        val tooClose = Geo.offsetPoint(apex, 0.0, 2.0)
        assertNull(Geo.conePolygon(apex, tooClose))
    }

    @Test
    fun `cone apex is always vertex 0`() {
        val apex = LatLng(48.1351, 11.582)
        val target = Geo.offsetPoint(apex, 45.0, 50.0)
        val cone = Geo.conePolygon(apex, target)
        assertNotNull(cone)
        assertEquals(4, cone!!.size)
        assertEquals(apex, cone[0])
    }

    @Test
    fun `one Chaikin pass on a triangle produces a 6-point ring`() {
        val triangle = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 0.0))
        val smoothed = Geo.smoothPolygon(triangle, iterations = 1)
        assertEquals(6, smoothed.size)
        // First cut point on edge (0,0)->(0,1): 0.75*a + 0.25*b.
        assertEquals(LatLng(0.0, 0.25), smoothed[0])
    }

    @Test
    fun `circle polygon round-trips its own radius`() {
        val center = LatLng(48.1351, 11.582)
        val radius = 50.0
        val ring = Geo.circlePolygon(center, radius, segments = 8)
        assertEquals(8, ring.size)
        ring.forEach { point ->
            val distance = Geo.haversineDistanceMeters(center.lat, center.lng, point.lat, point.lng)
            assertTrue("expected ~${radius}m, got $distance", abs(distance - radius) < 2.0)
        }
    }
}
