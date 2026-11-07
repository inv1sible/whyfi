package com.whyfi.app.usbmon

import com.whyfi.app.report.CellKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorReportContentTest {

    private fun device(
        mac: String, ssid: String = "", lastMs: Long = 1, role: Int = SniffedDevices.ROLE_AP,
        probed: List<String> = emptyList(), bssids: List<String> = emptyList(), security: String = "",
    ) = SniffedDevices.Device(mac, role, 1234, 3, 0, 0, -55, listOf(11, 1), ssid,
        probed, false, bssids, "", "", "", 1, lastMs, security = security)

    private val guess = SniffedDevices.Guess(SniffedDevices.Kind.ACCESS_POINT, "Acme", listOf("beacons"))

    private fun estimate(lat: Double, lng: Double, provisional: Boolean = false) =
        MonitorEstimate(lat, lng, 12.4, 4, 80.0, lat, lng, "good", provisional, emptyList(), emptyList())

    @Test
    fun positionsAreNumberedLikeTheMapMarkers() {
        val estimates = mapOf("aa" to estimate(52.5, 13.4), "bb" to null, "cc" to estimate(52.6, 13.5, provisional = true))
        val names = mapOf("aa" to "Office AP")
        val table = MonitorReportContent.positionsTable(listOf("aa", "bb", "cc"), estimates, names)
        assertEquals(listOf("1", "Office AP", "aa", "52.500000", "13.400000", "≥12 m", "4", "estimate"), table.rows[0])
        assertEquals("no location fix", table.rows[1].last())
        assertEquals("provisional", table.rows[2].last())
        assertEquals(table.columns.size, table.rows[0].size)
        val markers = MonitorReportContent.markers(listOf("aa", "bb", "cc"), estimates, names)
        assertEquals(listOf(1, 3), markers.map { it.number })
        assertEquals("Office AP", markers[0].label)
    }

    @Test
    fun devicesAreNewestFirstWithFormattedNumbers() {
        val table = MonitorReportContent.devicesTable(listOf(
            device("00:00:00:00:00:01", "Old", lastMs = 1) to guess,
            device("00:00:00:00:00:02", "New", lastMs = 9) to guess,
        ))
        assertEquals(listOf("00:00:00:00:00:02", "New", "Access point", "-55 dBm", "1, 11", "1,234", "3"), table.rows[0])
    }

    @Test
    fun channelShareBarsSumToOne() {
        val table = MonitorReportContent.channelsTable(mapOf(6 to 300L, 1 to 100L))
        assertEquals(listOf("1", "100", "0.2500"), table.rows[0])
        assertEquals(listOf("6", "300", "0.7500"), table.rows[1])
        assertEquals(CellKind.BAR, table.columns[2].kind)
    }

    @Test
    fun appendixKeepsOnlyDevicesWithDetails() {
        val table = MonitorReportContent.deviceDetailsTable(listOf(
            device("00:00:00:00:00:01", probed = listOf("Home", "Cafe")) to guess.copy(reasons = emptyList()),
            device("00:00:00:00:00:02") to guess.copy(reasons = emptyList()),
            device("00:00:00:00:00:03", security = "WPA2") to guess,
        ))
        assertEquals(listOf("00:00:00:00:00:01", "00:00:00:00:00:03"), table.rows.map { it[0] })
        assertEquals("Home, Cafe", table.rows[0][2])
        assertEquals("–", table.rows[0][1])
        assertEquals("WPA2", table.rows[1][3])
    }

    @Test
    fun timelineFiltersNoiseAndCaps() {
        val events = List(200) { MonitorMission.ReplayEvent(it.toLong(), "monitor_started", "") } +
            MonitorMission.ReplayEvent(5, "ui_action", "tap")
        val table = MonitorReportContent.timelineTable(events) { "t$it" }
        assertEquals(MonitorReportContent.MAX_TIMELINE_ROWS, table.rows.size)
        assertEquals(listOf("t0", "monitor started", "–"), table.rows[0])
        assertTrue(table.note!!.contains("of 200"))
    }

    @Test
    fun boundsCoverRouteAndMarkersAndThinningKeepsTheEnd() {
        assertNull(MonitorReportContent.bounds(emptyList(), emptyList()))
        val route = List(5000) { 52.0 + it * 1e-6 to 13.0 }
        val marker = MonitorReportContent.MapMarker(1, "x", 52.01, 13.01, 10.0)
        val b = MonitorReportContent.bounds(route, listOf(marker))!!
        assertEquals(52.01, b.maxLat, 1e-9)
        assertEquals(13.01, b.maxLng, 1e-9)
        assertTrue(b.spanM in 1_000.0..1_200.0)
        val thin = MonitorReportContent.thinRoute(route)
        assertTrue(thin.size <= 1501)
        assertEquals(route.last(), thin.last())
    }

    @Test
    fun emptyTablesExplainThemselves() {
        assertTrue(MonitorReportContent.positionsTable(emptyList(), emptyMap(), emptyMap()).empty.contains("Star a device"))
        assertTrue(MonitorReportContent.channelsTable(emptyMap()).rows.isEmpty())
    }
}
