package com.whyfi.app.usbmon

import com.whyfi.app.signatures.SignatureCatalog
import com.whyfi.app.usbmon.SniffedDevices.Kind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera recognition from the bundled Fieldwatch catalog (user, 2026-11-09:
 * two cameras at home were never listed). */
class SniffedDevicesCatalogTest {
    private val catalog = File("src/main/assets/${SignatureCatalog.ASSET}").reader().use { SignatureCatalog.read(it) }
    private val noVendor: (String) -> String? = { null }

    private fun device(mac: String, roles: Int, ssid: String = "", up: Long = 0, down: Long = 0) = SniffedDevices.parse(
        listOf(mac, roles, 40, 0, up, down, "-60", "6", ssid, "", "0", "", "", "", "", 10, 900).joinToString("\t"),
    ).single()

    @Test
    fun cameraBoardRadioPrefixIsACamera() {
        // 70:C9:4E is in Fieldwatch's "LiteOn camera radio" fleet.
        val guess = SniffedDevices.guess(device("70:c9:4e:12:34:56", 2), noVendor, catalog)
        assertEquals(Kind.CAMERA, guess.kind)
        assertTrue(guess.reasons.any { it.startsWith("Fieldwatch catalog") })
    }

    @Test
    fun cameraSetupHotspotNameIsACamera() {
        assertEquals(Kind.CAMERA, SniffedDevices.guess(device("02:00:00:00:00:01", 1, ssid = "Ring-1a2b3c"), noVendor, catalog).kind)
        assertEquals(Kind.CAMERA, SniffedDevices.guess(device("00:11:22:33:44:55", 1, ssid = "eufyCam-Setup"), noVendor, catalog).kind)
    }

    @Test
    fun randomizedMacsAreNotMatchedByAddress() {
        // 72:C9:4E is 70:C9:4E with the locally administered bit set.
        assertNotEquals(Kind.CAMERA, SniffedDevices.guess(device("72:c9:4e:12:34:56", 2), noVendor, catalog).kind)
    }

    @Test
    fun streamingClientOfAnyNonPhoneVendorLooksLikeACamera() {
        val tplink: (String) -> String? = { "TP-LINK TECHNOLOGIES CO.,LTD." }
        val d = device("50:91:e3:00:00:01", 2, up = 2_000_000, down = 100_000)
        assertEquals(Kind.CAMERA, SniffedDevices.guess(d, tplink, null).kind)
        val apple: (String) -> String? = { "Apple, Inc." }
        assertNotEquals(Kind.CAMERA, SniffedDevices.guess(d, apple, null).kind)
    }
}
