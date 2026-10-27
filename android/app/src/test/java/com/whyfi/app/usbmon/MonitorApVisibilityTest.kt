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

    @Test fun legacyBssidHideBecomesWholeSsidGroupWhenNameIsKnown() {
        val first = device("00:11:22:33:44:aa", "Campus WiFi")
        val second = device("00:11:22:33:44:bb", "Campus WiFi")
        val other = device("00:11:22:33:44:cc", "Campus Guest")
        val devices = listOf(first, second, other)
        val hidden = MonitorApVisibility.resolve(setOf(first.mac.uppercase()), devices)
        assertEquals(setOf("ssid:Campus WiFi"), hidden)
        assertTrue(MonitorApVisibility.isHidden(first, hidden))
        assertTrue(MonitorApVisibility.isHidden(second, hidden))
        assertFalse(MonitorApVisibility.isHidden(other, hidden))
        val group = MonitorApVisibility.groups(setOf(first.mac), devices).single()
        assertEquals("Campus WiFi", group.label)
        assertEquals(2, group.count)
        assertEquals(hidden.single(), group.key)
    }

    @Test fun unnamedHideExpandsAfterSsidArrivesAndRestoreSurvivesReplay() {
        val unnamed = device("00:11:22:33:44:55", "")
        val initial = setOf(MonitorApVisibility.key(unnamed))
        assertEquals(initial, MonitorApVisibility.resolve(initial, listOf(unnamed)))
        val named = unnamed.copy(ssid = "Campus: WiFi")
        val resolved = MonitorApVisibility.resolve(initial, listOf(named))
        val events = listOf(MonitorMission.ReplayEvent(1, "ap_hidden", "${unnamed.mac}:true"),
            MonitorMission.ReplayEvent(2, "ap_hidden", "${unnamed.mac}:false"),
            MonitorMission.ReplayEvent(3, "ap_hidden", "${resolved.single()}:true"),
            MonitorMission.ReplayEvent(4, "ap_hidden", "${resolved.single()}:false"))
        assertEquals(setOf("ssid:Campus: WiFi"), MonitorMission.hiddenApsAfter(events.take(3)))
        assertTrue(MonitorMission.hiddenApsAfter(events).isEmpty())
        assertTrue(MonitorApVisibility.groups(initial, listOf(unnamed)).single().label.contains("SSID not recorded"))
    }
}
