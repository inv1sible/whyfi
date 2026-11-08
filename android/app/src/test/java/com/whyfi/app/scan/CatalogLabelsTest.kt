package com.whyfi.app.scan

import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLabelsTest {
    private fun ble(mac: String, name: String = "", legacy: String = "") = BleObservationDto(
        bleMac = mac, stableIdentifier = mac, rssi = -60, txPower = null, manufacturerData = legacy,
        deviceTypeGuess = "", deviceName = name,
    )

    private fun wifi(bssid: String, ssid: String) = WifiObservationDto(bssid = bssid, ssid = ssid, rssi = -50, frequencyMhz = 2412, capabilities = "")

    private fun pass(ble: List<BleObservationDto>, wifi: List<WifiObservationDto> = emptyList()) = ScanSessionUploadRequest(
        clientScanId = "t", startedAt = "", completedAt = "", latitude = null, longitude = null, locationAccuracyMeters = null,
        wifiObservations = wifi, cellObservations = emptyList(), bleObservations = ble, satelliteObservations = emptyList(),
    )

    @Test
    fun onlyNewOrChangedAdvertsAreAskedAgain() {
        val first = CatalogLabels.request(pass(listOf(ble("aa:01", "Venus_1", legacy = "2502:40")), listOf(wifi("02:00:00:00:00:09", "BMW_1"))), emptySet())
        assertEquals("2502:40", first.ble.single().manufacturerRecords.single())
        val asked = CatalogLabels.askedKeys(first).toSet()
        assertTrue(CatalogLabels.request(pass(listOf(ble("AA:01", "Venus_1", legacy = "2502:40")), listOf(wifi("02:00:00:00:00:09", "BMW_1"))), asked).isEmpty)
        // Same rotating address, now advertising a name: asked again.
        assertEquals(1, CatalogLabels.request(pass(listOf(ble("aa:01", "S123C", legacy = "2502:40"))), asked).ble.size)
    }

    @Test
    fun mergeKeysByUpperCaseMacAndJoinsNames() {
        val response = CatalogLabels.Response(
            ble = mapOf("aa:01" to listOf(CatalogLabels.Label("fleet-tesla", "Tesla", "VEHICLE"), CatalogLabels.Label("fleet-ibeacon", "iBeacon", "BEACON"))),
            wifi = mapOf("02:00:00:00:00:09" to listOf(CatalogLabels.Label("fleet-bmw", "BMW", "VEHICLE"))),
        )
        val merged = CatalogLabels.merge(mapOf("AA:01" to "old"), response)
        assertEquals("Tesla · iBeacon", merged["AA:01"])
        assertEquals("BMW", merged["02:00:00:00:00:09"])
        assertNull(CatalogLabels.text(emptyList()))
        assertEquals(merged, CatalogLabels.merge(merged, CatalogLabels.Response()))
    }
}
