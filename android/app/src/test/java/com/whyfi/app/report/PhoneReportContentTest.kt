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
