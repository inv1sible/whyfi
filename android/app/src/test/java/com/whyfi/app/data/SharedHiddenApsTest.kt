package com.whyfi.app.data

import com.whyfi.app.scan.RadioKind
import com.whyfi.app.usbmon.MonitorApVisibility
import com.whyfi.app.usbmon.SniffedDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** whyfi phone and USB share one hidden-AP list; both must read its keys alike. */
class SharedHiddenApsTest {
    @Test
    fun phoneKeysMatchTheUsbAdapterKeys() {
        assertEquals("ssid:Home", FavoritesRepository.wifiHiddenKey("Home", "AA:BB:CC:00:11:22"))
        assertEquals("aa:bb:cc:00:11:22", FavoritesRepository.wifiHiddenKey("", "AA:BB:CC:00:11:22"))
        val keys = setOf("ssid:Home", "aa:bb:cc:00:11:33")
        assertTrue(FavoritesRepository.wifiHidden(keys, "Home", "11:22:33:44:55:66"))
        assertTrue(FavoritesRepository.wifiHidden(keys, "Other", "AA:BB:CC:00:11:33"))
        assertFalse(FavoritesRepository.wifiHidden(keys, "Other", "11:22:33:44:55:66"))
        assertEquals("Home", FavoritesRepository.hiddenLabel(RadioKind.WIFI, "ssid:Home"))
    }

    @Test
    fun aKeyHiddenOnThePhoneHidesTheApOnUsb() {
        // roles 1 = access point (same fixture as SniffedDevicesCatalogTest).
        val ap = SniffedDevices.parse(listOf("aa:bb:cc:00:11:22", 1, 40, 0, 0, 0, "-60", "6", "Home", "", "0", "", "", "", "", 10, 900)
            .joinToString("\t")).single()
        assertTrue(ap.isAp)
        assertTrue(MonitorApVisibility.isHidden(ap, setOf(FavoritesRepository.wifiHiddenKey("Home", "x"))))
        assertEquals(FavoritesRepository.wifiHiddenKey("Home", ap.mac), MonitorApVisibility.key(ap))
    }
}
