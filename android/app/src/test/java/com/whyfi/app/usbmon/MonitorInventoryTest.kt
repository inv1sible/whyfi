package com.whyfi.app.usbmon

import org.junit.Assert.*
import org.junit.Test

class MonitorInventoryTest {
    private fun device(mac: String, rssi: Int, ssid: String = "") = SniffedDevices.Device(mac,
        SniffedDevices.ROLE_CLIENT, 1, 1, 0, 0, rssi, listOf(6), ssid, emptyList(), false,
        emptyList(), "", "", "", 1, 2, rssi)

    @Test fun continuationKeepsUnheardDevicesAndUsesLatestSignalForReturningMac() {
        val old = device("00:11:22:33:44:55", -40, "Known")
        val unseen = device("00:11:22:33:44:66", -60)
        val returning = old.copy(ssid = "", bestRssi = -75, lastRssi = -75)
        val merged = MonitorInventory.merge(listOf(old, unseen), listOf(returning))
        assertEquals(2, merged.size)
        assertEquals("Known", merged.first().ssid)
        assertEquals(-75, merged.first().lastRssi)
        assertEquals(setOf(old.mac), MonitorInventory.matches(listOf(old, unseen), listOf(returning)))
    }

    @Test fun matchSortingUsesExactMacThenCurrentRssi() {
        val old = device("da:11:22:33:44:55", -80)
        val better = device("00:11:22:33:44:66", -50)
        val fresh = device("00:11:22:33:44:77", -20)
        val guess = SniffedDevices.Guess(SniffedDevices.Kind.CLIENT, null, emptyList())
        val sorted = listOf(old to guess, fresh to guess, better to guess)
            .sortedWith(MonitorInventory.matchOrder(setOf(old.mac, better.mac)))
        assertEquals(listOf(better.mac, old.mac, fresh.mac), sorted.map { it.first.mac })
        assertTrue(MonitorInventory.matches(listOf(old), listOf(old.copy(mac = "da:11:22:33:44:99"))).isEmpty())
    }
}
