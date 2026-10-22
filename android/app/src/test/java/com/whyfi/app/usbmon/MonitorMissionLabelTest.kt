package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorMissionLabelTest {
    @Test fun saved_favorite_shows_name_and_only_last_three_mac_octets() {
        assertEquals("Cafe WiFi · 11:22:33",
            MonitorMission.displayTarget("aa:bb:cc:11:22:33", "Cafe WiFi"))
    }

    @Test fun old_mission_without_name_has_clear_fallback() {
        assertEquals("Device · 11:22:33",
            MonitorMission.displayTarget("aa:bb:cc:11:22:33", null))
    }
}
