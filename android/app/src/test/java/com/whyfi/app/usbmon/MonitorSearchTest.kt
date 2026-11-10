package com.whyfi.app.usbmon

import com.whyfi.app.usbmon.SniffedDevices.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorSearchTest {
    private fun device(mac: String, roles: Int, ssid: String = "", probed: List<String> = emptyList(),
                       bssids: List<String> = emptyList(), wpsModel: String = "", security: String = "") =
        SniffedDevices.Device(mac, roles, 10, if (probed.isEmpty()) 0 else 3, 0, 0, -60, listOf(6), ssid, probed, false,
            bssids, "", "", wpsModel, 0, 1000, security = security)

    private val ap = device("aa:bb:cc:00:00:01", SniffedDevices.ROLE_AP, ssid = "HomeNet", wpsModel = "Archer C6", security = "WPA2")
    private val client = device("11:22:33:44:55:66", SniffedDevices.ROLE_CLIENT, bssids = listOf(ap.mac))
    private val prober = device("02:00:00:00:00:09", SniffedDevices.ROLE_CLIENT, probed = listOf("CafeWifi"))
    private val rows = listOf(
        ap to SniffedDevices.Guess(Kind.ACCESS_POINT, "TP-LINK", emptyList()),
        client to SniffedDevices.Guess(Kind.PHONE_LAPTOP, "Apple, Inc.", emptyList()),
        prober to SniffedDevices.Guess(Kind.CLIENT, null, emptyList()),
    )

    @Test fun emptyQueryKeepsEverything() = assertNull(MonitorSearch.keep(rows, "   "))

    @Test fun matchesAcrossFieldsCaseInsensitive() {
        assertEquals(setOf(ap.mac), MonitorSearch.keep(rows, "archer"))
        assertEquals(setOf(prober.mac), MonitorSearch.keep(rows, "cafewifi"))
        assertEquals(setOf(ap.mac), MonitorSearch.keep(rows, "wpa2 ch6"))
        assertEquals(setOf(prober.mac), MonitorSearch.keep(rows, "unassociated"))
    }

    @Test fun macMatchesWithOrWithoutSeparators() {
        assertEquals(setOf(client.mac, ap.mac), MonitorSearch.keep(rows, "112233"))
        assertEquals(setOf(client.mac, ap.mac), MonitorSearch.keep(rows, "11-22-33"))
    }

    @Test fun clientMatchesByAssociatedSsidAndKeepsItsAp() {
        // The client's vendor matches; its AP is kept so the card shows it.
        assertEquals(setOf(client.mac, ap.mac), MonitorSearch.keep(rows, "apple"))
        // "homenet" hits the AP and the client associated to it.
        assertEquals(setOf(ap.mac, client.mac), MonitorSearch.keep(rows, "homenet"))
    }

    @Test fun statusAndFavoriteAreSearchable() {
        assertEquals(setOf(prober.mac), MonitorSearch.keep(rows, "seen again", mapOf(prober.mac to "Seen again")))
        assertEquals(setOf(ap.mac), MonitorSearch.keep(rows, "favorite", favorites = setOf(ap.mac)))
    }
}
