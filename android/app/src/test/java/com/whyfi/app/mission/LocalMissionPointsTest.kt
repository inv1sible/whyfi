package com.whyfi.app.mission

import com.google.gson.Gson
import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.scan.RadioKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMissionPointsTest {
    private val gson = Gson()
    private val lat = 52.52
    private val lng = 13.405

    private fun wifi(ssid: String, rssi: Int) = WifiObservationDto(bssid = "aa:bb:cc:dd:ee:01", ssid = ssid, rssi = rssi, frequencyMhz = 2412, capabilities = "")
    private fun ble(mac: String, rssi: Int) = BleObservationDto(
        bleMac = mac, stableIdentifier = "", rssi = rssi, txPower = null, manufacturerData = "", deviceTypeGuess = "UNKNOWN",
    )
    private fun cell(cellId: String, dbm: Int?) = CellObservationDto(
        mcc = "262", mnc = "01", carrierName = "", radioType = "LTE", cellId = cellId, tacOrLac = "4711", band = "",
        isServingCell = true, signalDbm = dbm, rsrp = null, rsrq = null, sinr = null,
    )

    private fun pass(
        lat: Double? = this.lat, lng: Double? = this.lng,
        wifi: List<WifiObservationDto> = emptyList(), ble: List<BleObservationDto> = emptyList(), cells: List<CellObservationDto> = emptyList(),
        recording: String? = null, atMs: Long? = null, accuracy: Float? = null,
    ) = LocalMissionPoints.Pass(gson.toJson(ScanSessionUploadRequest(
        clientScanId = "x", startedAt = "", completedAt = "", latitude = lat, longitude = lng, locationAccuracyMeters = accuracy,
        wifiObservations = wifi, bleObservations = ble, cellObservations = cells,
    )), recording, atMs)

    @Test
    fun wifiBySsidNearbyOnly() {
        val target = MissionTarget(RadioKind.WIFI, "Home <5G>")
        val payloads = listOf(
            pass(wifi = listOf(wifi("Home <5G>", -50), wifi("Other", -40))),
            pass(lat = lat + 0.0005, wifi = listOf(wifi("Home <5G>", -70))), // ~55 m
            pass(lat = lat + 0.01, wifi = listOf(wifi("Home <5G>", -30))), // ~1.1 km: outside
            pass(lat = null, wifi = listOf(wifi("Home <5G>", -30))), // no fix
        )
        val result = LocalMissionPoints.points(payloads, target, lat, lng, 250.0)
        assertEquals(listOf(-50.0, -70.0), result.points.map { it.weight })
        assertEquals(2, result.passes)
        assertFalse(result.truncated)
        // The database prefilter must find the SSID the way Gson stored it.
        assertTrue(payloads.all { it.json.contains(LocalMissionPoints.needle(target)) })
    }

    @Test
    fun bleByMacIgnoringCase() {
        val target = MissionTarget(RadioKind.BLE, "AA:BB:CC:00:11:22")
        val result = LocalMissionPoints.points(listOf(pass(ble = listOf(ble("aa:bb:cc:00:11:22", -60), ble("aa:bb:cc:00:11:23", -40)))), target, lat, lng, 250.0)
        assertEquals(listOf(-60.0), result.points.map { it.weight })
    }

    @Test
    fun cellByTowerKeySkipsMissingSignal() {
        val target = MissionTarget(RadioKind.CELLULAR, "262-01-4711-1234567")
        val payloads = listOf(pass(cells = listOf(cell("1234567", -95), cell("1234567", null), cell("7654321", -80))))
        val result = LocalMissionPoints.points(payloads, target, lat, lng, 250.0)
        assertEquals(listOf(-95.0), result.points.map { it.weight })
        assertTrue(payloads[0].json.contains(LocalMissionPoints.needle(target)))
    }

    @Test
    fun capsAndFlagsTruncation() {
        val target = MissionTarget(RadioKind.WIFI, "Net")
        val payloads = List(5) { pass(wifi = listOf(wifi("Net", -50))) }
        val result = LocalMissionPoints.points(payloads, target, lat, lng, 250.0, cap = 3)
        assertEquals(3, result.points.size)
        assertTrue(result.truncated)
    }

    @Test
    fun brokenJsonIsSkipped() {
        val target = MissionTarget(RadioKind.WIFI, "Net")
        val result = LocalMissionPoints.points(listOf(LocalMissionPoints.Pass("{not json"), pass(wifi = listOf(wifi("Net", -50)))), target, lat, lng, 250.0)
        assertEquals(1, result.points.size)
    }

    @Test
    fun carriesAccuracyTimeAndRecording() {
        val target = MissionTarget(RadioKind.WIFI, "Net")
        val point = LocalMissionPoints.points(listOf(pass(wifi = listOf(wifi("Net", -50)), recording = "r1", atMs = 42L, accuracy = 8f)), target, lat, lng, 250.0).points.single()
        assertEquals(8.0, point.accuracyM!!, 0.0)
        assertEquals(42L, point.atMs)
        assertEquals("r1", point.recordingId)
    }

    @Test
    fun latestSessionStopsAtTheFirstLongGap() {
        val min = 60_000L
        val points = listOf(
            MissionPoint(lat, lng, -50.0, atMs = 0),          // yesterday's session
            MissionPoint(lat, lng, -60.0, atMs = 300 * min),  // today, 300 min later
            MissionPoint(lat, lng, -70.0, atMs = 320 * min),
            MissionPoint(lat, lng, -80.0, atMs = 345 * min),
        )
        assertEquals(listOf(-60.0, -70.0, -80.0), LocalMissionPoints.latestSession(points).map { it.weight })
        // Untimed readings: nothing to split on, all kept.
        val untimed = listOf(MissionPoint(lat, lng, -50.0), MissionPoint(lat, lng, -60.0))
        assertEquals(untimed, LocalMissionPoints.latestSession(untimed))
        assertEquals(emptyList<MissionPoint>(), LocalMissionPoints.latestSession(emptyList()))
    }

    @Test
    fun bleNameIsTheNewestAdvertisedOne() {
        fun named(name: String) = ble("AA:BB:CC:00:11:22", -60).copy(deviceName = name)
        val passes = listOf(
            pass(ble = listOf(named("Old name")), atMs = 1),
            pass(ble = listOf(named("")), atMs = 3),
            pass(ble = listOf(named("Nespresso")), atMs = 2),
        )
        assertEquals("Nespresso", LocalMissionPoints.bleName(passes, "aa:bb:cc:00:11:22"))
        assertEquals(null, LocalMissionPoints.bleName(passes, "11:22:33:44:55:66"))
    }

    @Test
    fun serverTimestampsParse() {
        assertEquals(1_762_646_400_000L, isoToEpochMs("2025-11-09T00:00:00Z"))
        assertEquals(1_762_646_400_000L, isoToEpochMs("2025-11-09T01:00:00+01:00"))
        assertEquals(null, isoToEpochMs("yesterday"))
    }
}
