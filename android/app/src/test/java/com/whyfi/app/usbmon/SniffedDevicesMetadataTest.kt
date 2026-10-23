package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffedDevicesMetadataTest {
    @Test fun `new fields survive TSV parsing and camera type beats generic vendor`() {
        val columns = listOf("aa:bb:cc:11:22:33", "1", "4", "0", "0", "0", "-58", "6", "Camera", "", "0", "", "Door", "Acme", "Cam", "1", "2", "-58", "10", "6", "4:0050f204:1", "C1", "S1", "0080", "configured", "no", "RSN", "PSK", "CCMP-128", "CCMP-128", "capable")
        val d = SniffedDevices.parse(columns.joinToString("\t")).single()
        assertEquals(SniffedDevices.Kind.CAMERA, SniffedDevices.guess(d) { null }.kind)
        assertTrue(d.securityDetails.contains("PMF capable"))
        assertTrue(d.wpsDetails.contains("4:0050f204:1"))
        assertTrue(d.securityAlerts.contains("WPS setup not locked"))
    }

    @Test fun `old mission snapshots remain readable`() {
        val old = listOf("aa:bb:cc:11:22:33", "1", "4", "0", "0", "0", "-58", "6", "Old", "", "0", "", "", "", "", "1", "2")
        assertEquals("", SniffedDevices.parse(old.joinToString("\t")).single().security)
    }
}
