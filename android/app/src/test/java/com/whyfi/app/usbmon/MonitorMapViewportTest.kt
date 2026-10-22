package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorMapViewportTest {
    @Test fun close_measurements_get_close_map_zoom() {
        val frame = monitorMapViewport(listOf(52.5 to 13.4, 52.5001 to 13.4001), 400, 600)!!
        assertTrue(frame.zoom > 18.0)
        assertEquals(52.50005, frame.lat, 0.000001)
    }

    @Test fun distant_measurements_fit_without_fixed_close_zoom() {
        val frame = monitorMapViewport(listOf(52.5 to 13.4, 52.6 to 13.5), 400, 600)!!
        assertTrue(frame.zoom < 13.0)
    }
}
