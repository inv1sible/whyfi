package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorNoiseFilterTest {
    private val device = SniffedDevices.Device("00:11:22:33:44:55", SniffedDevices.ROLE_CLIENT,
        4, 2, 0, 0, -66, listOf(6), "", listOf("Home WiFi"), false,
        emptyList(), "", "", "", 100, 200)

    @Test fun unknownDeviceAndProbeActivityCanBeMatched() {
        val unknown = device to SniffedDevices.Guess(SniffedDevices.Kind.CLIENT, null, emptyList())
        assertTrue(MonitorNoiseFilter.matches(unknown, MonitorNoiseFilter.compile("UNKNOWN DEVICE").patterns))
        assertTrue(MonitorNoiseFilter.matches(unknown, MonitorNoiseFilter.compile("home.*wifi").patterns))
        val named = device to SniffedDevices.Guess(SniffedDevices.Kind.CLIENT, "Example Inc", emptyList())
        assertFalse(MonitorNoiseFilter.matches(named, MonitorNoiseFilter.compile("unknown device").patterns))
    }

    @Test fun invalidOrOversizedPatternsCannotBeSaved() {
        assertNotNull(MonitorNoiseFilter.compile("[").error)
        assertNotNull(MonitorNoiseFilter.compile("x".repeat(121)).error)
        assertEquals(2, MonitorNoiseFilter.compile("unknown device\nexample.*").patterns.size)
    }

    @Test fun randomizedMacLabelMatchesWhatCardsAndTablesShow() {
        val row = device.copy(mac = "da:11:22:33:44:55") to SniffedDevices.Guess(SniffedDevices.Kind.PHONE_LAPTOP, null, emptyList())
        assertEquals("randomized MAC", SniffedDevices.displayName(row.first, row.second))
        assertTrue(MonitorNoiseFilter.matches(row, MonitorNoiseFilter.compile("randomized MAC").patterns))
        assertFalse(MonitorNoiseFilter.matches(row, MonitorNoiseFilter.compile("unknown device").patterns))
    }
}
