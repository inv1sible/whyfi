package com.whyfi.app.report

import com.whyfi.app.scan.SurveyTally
import com.whyfi.app.data.remote.WifiObservationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatTimeTest {
    @Test fun durations() {
        assertEquals("45 s", ReportFormat.duration(45_000))
        assertEquals("12 min", ReportFormat.duration(12 * 60_000L))
        assertEquals("2 h 28 min", ReportFormat.duration((2 * 60 + 28) * 60_000L))
        assertEquals("3 d 4 h", ReportFormat.duration((3 * 24 + 4) * 3_600_000L))
    }

    @Test fun isoStampsBecomeLocalTime() {
        val local = ReportFormat.isoLocal("2026-11-11T07:12:33Z")
        assertNotEquals("2026-11-11T07:12:33Z", local)
        assertTrue(local, local.contains("2026"))
        assertEquals("not a date", ReportFormat.isoLocal("not a date"))
        assertEquals("–", ReportFormat.isoLocal(null))
    }

    @Test fun clockAddsTheDateOnlyAcrossDays() {
        val t = 1_762_844_400_000L // 2025-11-11 07:00 UTC
        val sameDay = ReportFormat.clock(listOf(t, t + 60_000))(t)
        val twoDays = ReportFormat.clock(listOf(t, t + 2 * 86_400_000L))(t)
        assertTrue(twoDays.length > sameDay.length)
        assertEquals("–", ReportFormat.clock(listOf(t))(0))
    }

    @Test fun periodShowsDuration() {
        val t = 1_762_844_400_000L
        assertTrue(ReportFormat.period(t, t + 90 * 60_000L).endsWith("(1 h 30 min)"))
        assertEquals("–", ReportFormat.period(0, t))
    }

    @Test fun sessionTableHasFirstAndLastSeen() {
        var now = 1_000L
        val tally = SurveyTally { now }
        fun ap(rssi: Int) = WifiObservationDto(bssid = "aa:bb:cc:dd:ee:ff", ssid = "Home", rssi = rssi, frequencyMhz = 2437, capabilities = "")
        tally.recordWifi(listOf(ap(-70)))
        now = 5_000L; tally.recordWifi(listOf(ap(-50)))
        now = 9_000L; tally.recordWifi(listOf(ap(-80)))
        val seen = tally.devices().wifi.single()
        assertEquals(listOf(1_000L, 9_000L, -50), listOf(seen.firstMs, seen.lastMs, seen.best.toLong()))
        val table = ScanReportContent.sessionTable(com.whyfi.app.scan.RadioKind.WIFI, listOf(seen))
        assertEquals(table.columns.size, table.rows[0].size)
        assertEquals(listOf("First seen", "Last seen"), table.columns.takeLast(2).map { it.header })
    }
}
