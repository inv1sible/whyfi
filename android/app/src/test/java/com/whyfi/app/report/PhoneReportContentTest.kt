package com.whyfi.app.report

import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.scan.DeviceChange
import com.whyfi.app.scan.DeviceRow
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.SurveyStats
import com.whyfi.app.scan.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneReportContentTest {

    @Test
    fun lanDevicesSortByAddressAndListWebLinks() {
        val devices = listOf(
            LanObservationDto("192.168.1.20", openPorts = listOf(22)),
            LanObservationDto("192.168.1.3", hostname = "router", openPorts = listOf(443, 80), deviceTypeGuess = "ROUTER", responseTimeMs = 4.4),
        )
        val table = LanReportContent.devicesTable(devices)
        assertEquals(listOf("192.168.1.3", "router", "Router/Gateway", "80, 443", "http://192.168.1.3\nhttps://192.168.1.3", "4 ms", "–"), table.rows[0])
        assertEquals("192.168.1.20", table.rows[1][0])
        assertEquals("1", LanReportContent.stats(devices, 254)[2].value)
        assertTrue(LanReportContent.detailsTable(devices).rows.isEmpty())
    }

    @Test
    fun gpsTrackTableIsThinnedButKeepsTheLastPoint() {
        val track = List(450) { TrackPoint(it * 1000L, 52.0, 13.0, 4.0, "gps") }
        val table = GpsReportContent.trackTable(track) { "t$it" }
        assertTrue(table.rows.size <= GpsReportContent.MAX_TRACK_ROWS + 1)
        assertEquals("t449000", table.rows.last()[0])
        assertEquals("Every 3th of 450 positions.", table.note)
        assertEquals("7 min 29 s", GpsReportContent.stats(track, emptyList())[2].value)
        assertEquals(listOf("gps", "450", "±4 m", "1.0000"), GpsReportContent.providersTable(track).rows.single())
    }

    @Test
    fun overviewTablesFromSurveyAndPass() {
        val survey = SurveyStats(passCount = 3, wifiByBand = mapOf("2.4GHz" to 3, "5GHz" to 1))
        assertEquals(listOf(listOf("2.4GHz", "3", "0.7500"), listOf("5GHz", "1", "0.2500")), OverviewReportContent.bandsTable(survey).rows)
        val rows = listOf(
            DeviceRow("a", "A", "", "", listOf("Home", "aa:bb", "-50 dBm", "6", "WPA2"), DeviceChange.NEW),
            DeviceRow("b", "B", "", "", listOf("Cafe"), DeviceChange.GONE),
        )
        val changes = OverviewReportContent.changesTable(mapOf(RadioKind.WIFI to rows))
        assertEquals(listOf("WiFi", "1", "1", "1"), changes.rows.single())
        val pass = OverviewReportContent.passTable(RadioKind.WIFI, rows)
        assertEquals(listOf("Home", "aa:bb", "-50 dBm", "6", "WPA2", "new"), pass.rows[0])
        assertEquals(listOf("Cafe", "–", "–", "–", "–", "gone"), pass.rows[1])
    }
}

class RadioReportContentTest {
    private val rows = listOf(
        DeviceRow("aa", "Watch", "", "-48 dBm", listOf("Watch", "aa", "-48 dBm", "WEARABLE"), DeviceChange.NEW),
        DeviceRow("bb", "(unnamed)", "", "-80 dBm", listOf("(unnamed)", "bb", "-80 dBm", ""), DeviceChange.PRESENT),
        DeviceRow("cc", "Old", "", "-70 dBm", listOf("Old", "cc", "-70 dBm", "PHONE"), DeviceChange.GONE),
    )
    private fun summary(vendor: String) = BleDeviceSummary(vendor, "manufacturer data", "", "random static", "yes", "–", "004c:10")

    @Test
    fun statsCountPresentDevicesAndSignals() {
        val stats = RadioReportContent.stats(RadioKind.BLE, rows, vendors = 1)
        assertEquals(listOf("2", "1", "1", "-48 dBm", "-48 dBm", "1"), stats.map { it.value })
        assertEquals(5, RadioReportContent.stats(RadioKind.CELLULAR, rows, null).size)
    }

    @Test
    fun bleTablesUseVendorSummaries() {
        val info = mapOf("aa" to summary("Apple, Inc."), "bb" to summary("Apple, Inc."), "cc" to summary("–"))
        val table = RadioReportContent.bleTable(rows, info)
        assertEquals(listOf("Watch", "aa", "-48 dBm", "Apple, Inc.", "WEARABLE", "random static", "new"), table.rows[0])
        assertEquals("–", table.rows[1][4])
        val vendors = RadioReportContent.vendorsTable(info.values)
        assertEquals(listOf("Apple, Inc.", "2", "0.6667"), vendors.rows.single())
        assertEquals("aa", RadioReportContent.bleDetailsTable(info).rows[0][0])
    }
}

class ScanReportContentTest {
    @Test
    fun sessionTablesNameTheirColumnsPerRadio() {
        val wifi = ScanReportContent.sessionTable(RadioKind.WIFI,
            listOf(com.whyfi.app.scan.SurveyTally.SeenDevice("aa:bb", "Home", "5GHz", -41)))
        assertEquals(listOf("SSID", "BSSID", "Band", "Best signal"), wifi.columns.map { it.header })
        assertEquals(listOf("Home", "aa:bb", "5GHz", "-41 dBm"), wifi.rows.single())
        assertEquals("Carrier", ScanReportContent.sessionTable(RadioKind.CELLULAR, emptyList()).columns[0].header)
        assertEquals("198.51.100.4", ScanReportContent.stats(SurveyStats(), "198.51.100.4").last().value)
    }

    @Test
    fun statsCoverOnlyTheRadiosThatWereScanned() {
        val labels = ScanReportContent.stats(SurveyStats(), null,
            com.whyfi.app.scan.ScanOptions(includeWifi = true, includeCellular = false, includeBle = false, includeGnss = true)).map { it.label }
        assertEquals(listOf("scan passes", "WiFi access points", "satellites", "public IP"), labels)
    }
}
