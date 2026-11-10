package com.whyfi.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHealthTest {
    @Test
    fun thermalStatusInWords() {
        // PowerManager.THERMAL_STATUS_NONE … _SHUTDOWN are 0 … 6.
        assertEquals(listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown", "status9"),
            listOf(0, 1, 2, 3, 4, 5, 6, 9).map(DeviceHealth::thermalLabel))
    }
}
