package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorSeenClockTest {
    private fun device(mac: String, firstMs: Long, lastMs: Long, rssiBootMs: Long? = null) = SniffedDevices.Device(
        mac, SniffedDevices.ROLE_CLIENT, 1, 0, 0, 0, -60, listOf(6), "", emptyList(), false, emptyList(), "", "", "",
        firstMs, lastMs, lastRssi = rssiBootMs?.let { -60 }, lastRssiBootMs = rssiBootMs)

    // Capture started at boot 50_000; snapshot written at boot 80_000 = wall 1_000_080_000.
    private val wall = 1_000_080_000L
    private val boot = 80_000L

    @Test fun captureStartComesFromSignalReadings() {
        // b's last frame had no signal reading (its RSSI is older), so a gives the start.
        val devices = listOf(device("a", 1_000, 20_000, 70_000), device("b", 0, 29_000, 60_000))
        assertEquals(50_000L, MonitorSeenClock.captureStartBootMs(devices))
        val spans = MonitorSeenClock.spans(devices, wall, boot)
        assertEquals(MonitorSeenClock.Span(1_000_051_000, 1_000_070_000), spans["a"])
        assertEquals(1_000_079_000L, spans["b"]!!.lastMs)
    }

    @Test fun withoutSignalTheNewestDeviceIsAtTheSnapshot() {
        val spans = MonitorSeenClock.spans(listOf(device("a", 0, 10_000), device("b", 0, 30_000)), wall, boot)
        assertEquals(wall, spans["b"]!!.lastMs)
        assertEquals(wall - 20_000, spans["a"]!!.lastMs)
        assertNull(MonitorSeenClock.captureStartBootMs(emptyList()))
    }

    @Test fun continuedMissionKeepsEarliestFirstAndLatestLast() {
        val first = MonitorMission.DeviceSnapshot(1_000_000L, 0, "") to listOf(device("a", 0, 1_000))
        // Second capture restarts the capture clock at 0.
        val second = MonitorMission.DeviceSnapshot(2_000_000L, 0, "") to listOf(device("a", 0, 500))
        assertEquals(MonitorSeenClock.Span(999_000, 2_000_000), MonitorSeenClock.fromSnapshots(listOf(first, second))["a"])
    }

    @Test fun agoLabels() {
        assertEquals("now", MonitorSeenClock.ago(500))
        assertEquals("42s ago", MonitorSeenClock.ago(42_000))
        assertEquals("12m ago", MonitorSeenClock.ago(12 * 60_000L))
        assertEquals("3h 5m ago", MonitorSeenClock.ago(3 * 3_600_000L + 5 * 60_000L))
    }
}
