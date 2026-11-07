package com.whyfi.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire format that `parse_advert` in
 * `backend/scans/ble_signatures.py` reads: `"cccc:hex"` for manufacturer
 * records and `"<uuid>:hex"` for service data.
 */
class BleAdvertFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun formatsCompanyIdAsFourHex() {
        assertEquals(
            listOf("004c:1219"),
            BleAdvertFormat.manufacturerRecords(listOf(0x004C to bytes(0x12, 0x19))),
        )
    }

    @Test
    fun keepsAllRecordsInOrder() {
        assertEquals(
            listOf("004c:10ff", "0006:", "08aa:0600"),
            BleAdvertFormat.manufacturerRecords(
                listOf(0x004C to bytes(0x10, 0xFF), 0x0006 to bytes(), 0x08AA to bytes(0x06, 0x00)),
            ),
        )
    }

    @Test
    fun serviceDataUsesFullUuid() {
        assertEquals(
            listOf("0000fd5a-0000-1000-8000-00805f9b34fb:01"),
            BleAdvertFormat.serviceData(listOf("0000FD5A-0000-1000-8000-00805F9B34FB" to bytes(0x01))),
        )
    }

    @Test
    fun legacyFieldIsFirstRecord() {
        assertEquals("004c:1219", BleAdvertFormat.legacyManufacturerData(listOf("004c:1219", "0006:01")))
        assertEquals("", BleAdvertFormat.legacyManufacturerData(emptyList()))
    }

    @Test
    fun legacyFieldBlankWhenTooLong() {
        val records = BleAdvertFormat.manufacturerRecords(listOf(0x004C to ByteArray(200)))
        assertEquals(405, records.single().length)
        assertEquals("", BleAdvertFormat.legacyManufacturerData(records))
    }
}
