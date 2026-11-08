package com.whyfi.app.scan

import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.signatures.LocalSignatures
import com.whyfi.app.signatures.SignatureCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogLabelsTest {
    private val catalog = File("src/main/assets/${SignatureCatalog.ASSET}").reader().use { SignatureCatalog.read(it) }

    private fun pass(ble: List<BleObservationDto>, wifi: List<WifiObservationDto>) = ScanSessionUploadRequest(
        clientScanId = "t", startedAt = "", completedAt = "", latitude = null, longitude = null, locationAccuracyMeters = null,
        wifiObservations = wifi, cellObservations = emptyList(), bleObservations = ble, satelliteObservations = emptyList(),
    )

    @Test
    fun realAdvertsGetTheirLabelsOnThePhone() {
        val nespresso = BleObservationDto(
            bleMac = "98:cd:ac:4c:67:8a", stableIdentifier = "", rssi = -60, txPower = null, manufacturerData = "2502:408900000000",
            serviceUuids = listOf("06aa1910-f22a-11e3-9daa-0002a5d5c51b"), deviceTypeGuess = "", deviceName = "Venus_98CDAC4C678A",
        )
        val tesla = BleObservationDto(
            bleMac = "6a:11:22:33:44:55", stableIdentifier = "", rssi = -70, txPower = null, manufacturerData = "",
            serviceUuids = listOf("00001122-0000-1000-8000-00805f9b34fb"), deviceTypeGuess = "", deviceName = "S512d65624d55b716C",
        )
        val plain = BleObservationDto(bleMac = "7f:00:00:00:00:02", stableIdentifier = "", rssi = -80, txPower = null, manufacturerData = "", deviceTypeGuess = "")
        val bmw = WifiObservationDto(bssid = "02:11:22:33:44:55", ssid = "BMW_12345", rssi = -50, frequencyMhz = 2412, capabilities = "")
        val labels = LocalSignatures.labels(catalog, listOf(pass(listOf(nespresso, tesla, plain), listOf(bmw))))
        assertEquals("Nespresso", labels["98:CD:AC:4C:67:8A"])
        assertEquals("Tesla", labels["6A:11:22:33:44:55"])
        assertNull(labels["7F:00:00:00:00:02"])
        assertEquals("BMW", labels["02:11:22:33:44:55"])
    }

    @Test
    fun mergeDropsStaleLabelsAndCapsKeys() {
        val merged = CatalogLabels.merge(mapOf("AA" to "old", "BB" to "keep"), mapOf("AA" to null, "CC" to "Tesla"))
        assertEquals(mapOf("BB" to "keep", "CC" to "Tesla"), merged)
        val many = (0 until CatalogLabels.MAX_KEYS + 10).associate { "K$it" to "x" as String? }
        assertEquals(CatalogLabels.MAX_KEYS, CatalogLabels.merge(emptyMap(), many).size)
    }
}
