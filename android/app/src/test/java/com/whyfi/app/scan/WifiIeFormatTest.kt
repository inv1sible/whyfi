package com.whyfi.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the `vendor_ie_ouis` wire format that `parse_wifi` in
 * `backend/scans/wifi_signatures.py` reads. */
class WifiIeFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun vendorElementsBecomeUppercaseOuis() {
        assertEquals(
            listOf("FA:0B:BC", "00:50:F2"),
            WifiIeFormat.vendorOuis(
                listOf(
                    0 to bytes(0x48, 0x69), // SSID element: ignored
                    221 to bytes(0xFA, 0x0B, 0xBC, 0x0D, 0x01),
                    221 to bytes(0x00, 0x50, 0xF2, 0x04),
                ),
            ),
        )
    }

    @Test
    fun shortAndDuplicateElementsAreDropped() {
        assertEquals(
            listOf("00:10:18"),
            WifiIeFormat.vendorOuis(
                listOf(221 to bytes(0x00, 0x10), 221 to bytes(0x00, 0x10, 0x18), 221 to bytes(0x00, 0x10, 0x18, 0x02)),
            ),
        )
    }

    @Test
    fun cappedAtThirtyTwo() {
        val many = (0 until 40).map { 221 to bytes(0xAA, 0xBB, it) }
        assertEquals(32, WifiIeFormat.vendorOuis(many).size)
    }
}
