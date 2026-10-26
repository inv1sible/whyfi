package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorApVisibilityTest {
    private fun device(mac: String, ssid: String, role: Int = SniffedDevices.ROLE_AP) =
        SniffedDevices.Device(mac, role, 1, 0, 0, 0, -55, listOf(6), ssid,
            emptyList(), false, emptyList(), "", "", "", 1, 2)

    @Test fun namedApHideCoversEveryBssidWithThatSsid() {
        val first = device("00:11:22:33:44:55", "Cafe WiFi")
        val second = device("00:11:22:33:44:66", "Cafe WiFi")
        val other = device("00:11:22:33:44:77", "Cafe Guest")
        val hidden = setOf(MonitorApVisibility.key(first))
        assertEquals("ssid:Cafe WiFi", hidden.single())
        assertTrue(MonitorApVisibility.isHidden(second, hidden))
        assertFalse(MonitorApVisibility.isHidden(other, hidden))
        assertFalse(MonitorApVisibility.isHidden(device(first.mac, "Cafe WiFi", SniffedDevices.ROLE_CLIENT), hidden))
    }

    @Test fun blankSsidAndLegacyMacRemainPerBssid() {
        val unnamed = device("00:11:22:33:44:55", "")
        val other = device("00:11:22:33:44:66", "")
        val hidden = setOf(MonitorApVisibility.key(unnamed))
        assertEquals(unnamed.mac, hidden.single())
        assertTrue(MonitorApVisibility.isHidden(unnamed, hidden))
        assertFalse(MonitorApVisibility.isHidden(other, hidden))
        assertTrue(MonitorApVisibility.isHidden(device(unnamed.mac, "New name"), hidden))
    }
}
