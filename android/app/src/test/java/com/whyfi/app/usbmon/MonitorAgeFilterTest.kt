package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorAgeFilterTest {
    private fun heardAt(lastMs: Long) = SniffedDevices.parse(
        listOf("aa:bb:cc:00:11:22", 2, 40, 0, 0, 0, "-60", "6", "", "", "0", "", "", "", "", 10, lastMs).joinToString("\t"),
    ).single()

    @Test
    fun keepsOnlyDevicesHeardWithinTheWindow() {
        val newest = 60 * 60_000L
        assertTrue(MonitorAgeFilter.keep(heardAt(newest - 4 * 60_000L), newest, 5, favorite = false))
        assertFalse(MonitorAgeFilter.keep(heardAt(newest - 6 * 60_000L), newest, 5, favorite = false))
        // All, and favorites, are never filtered.
        assertTrue(MonitorAgeFilter.keep(heardAt(0), newest, 0, favorite = false))
        assertTrue(MonitorAgeFilter.keep(heardAt(0), newest, 1, favorite = true))
    }

    @Test
    fun labels() {
        assertEquals("⏱ All", MonitorAgeFilter.label(0))
        assertEquals("⏱ 10 min", MonitorAgeFilter.label(10))
        assertEquals(listOf(0, 1, 2, 5, 10, 60), MonitorAgeFilter.OPTIONS)
    }
}
