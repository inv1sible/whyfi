package com.whyfi.app.ble

import com.whyfi.app.data.remote.BleObservationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDeviceInfoTest {
    private val numbers = BleAssignedNumbers.read(
        "# comment\nc\t004c\tApple, Inc.\nm\tfe9f\tGoogle LLC\ns\t180d\tHeart Rate\nbroken line\n".byteInputStream(),
    )

    private fun dto(mac: String, records: List<String> = emptyList(), uuids: List<String> = emptyList(), legacy: String = "") =
        BleObservationDto(bleMac = mac, stableIdentifier = mac, rssi = -60, txPower = -8, manufacturerData = legacy,
            serviceUuids = uuids, deviceTypeGuess = "", isConnectable = true, manufacturerRecords = records)

    @Test
    fun vendorFromManufacturerDataThenServiceThenMac() {
        val apple = BleDeviceInfo.describe(dto("4a:00:00:00:00:01", records = listOf("004c:1005")), numbers) { null }
        assertEquals("Apple, Inc." to "manufacturer data", apple.vendor to apple.vendorSource)
        assertEquals("rotating (private)", apple.addressType)

        val google = BleDeviceInfo.describe(dto("c0:00:00:00:00:01", uuids = listOf("0000fe9f-0000-1000-8000-00805f9b34fb", "0000180d-0000-1000-8000-00805f9b34fb")), numbers) { null }
        assertEquals("Google LLC", google.vendor)
        assertEquals(listOf("Google LLC service", "Heart Rate"), google.services)
        assertEquals("random static", google.addressType)

        val public = BleDeviceInfo.describe(dto("00:1a:7d:00:00:01"), numbers) { "Cyber-blue" }
        assertEquals("Cyber-blue?", public.vendor)
        // A random address never gets an OUI vendor.
        assertEquals("–", BleDeviceInfo.describe(dto("4a:1a:7d:00:00:01"), numbers) { "Cyber-blue" }.vendor)
    }

    @Test
    fun unknownCompanyAndLegacyFieldStillSaySomething() {
        val info = BleDeviceInfo.describe(dto("4a:00:00:00:00:02", legacy = "0999:00"), numbers) { null }
        assertEquals("company 0x0999", info.vendor)
        assertEquals(BleDeviceInfo.HEADERS.size, BleDeviceInfo.columns(info).size)
        assertTrue(BleDeviceInfo.columns(info)[1] == "–")
    }

    @Test
    fun uuid16OnlyForTheBluetoothBase() {
        assertEquals(0x180d, BleAssignedNumbers.uuid16("0000180D-0000-1000-8000-00805F9B34FB"))
        assertEquals(0xfe9f, BleAssignedNumbers.uuid16("fe9f"))
        assertEquals(null, BleAssignedNumbers.uuid16("12345678-0000-1000-8000-00805f9b34fb"))
    }
}
