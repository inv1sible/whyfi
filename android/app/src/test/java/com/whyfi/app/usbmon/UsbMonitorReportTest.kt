package com.whyfi.app.usbmon

import com.whyfi.app.usbmon.UsbMonitorNative.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMonitorReportTest {

    private fun stats(vararg pairs: Pair<Stat, Long>) = LongArray(Stat.entries.size).also { a ->
        pairs.forEach { (s, v) -> a[s.ordinal] = v }
    }

    @Test
    fun `advanced mode requires a successful run with received frames`() {
        assertFalse(UsbMonitorReport.qualifiesForAdvancedMode(null, stats()))
        assertFalse(UsbMonitorReport.qualifiesForAdvancedMode("USB failed", stats(Stat.TOTAL to 3)))
        assertTrue(UsbMonitorReport.qualifiesForAdvancedMode(null, stats(Stat.TOTAL to 3)))
    }

    @Test
    fun `perChannelLine pairs channels with frame counts`() {
        assertEquals("ch1=10, ch36=3", UsbMonitorReport.perChannelLine(longArrayOf(1, 10, 36, 3)))
        assertEquals("none", UsbMonitorReport.perChannelLine(longArrayOf()))
    }

    @Test
    fun `summary reports probe requests and omits RSSI before any frame`() {
        val lines = UsbMonitorReport.summary(stats(Stat.STAGE to 5, Stat.PROBE_REQ to 7, Stat.PROBE_REQ_SOURCES to 3))
        assertEquals("Stage: receiving and hopping", lines[0])
        assertTrue(lines.any { it.startsWith("Probe requests: 7 from 3 distinct MACs") })
        assertTrue(lines.none { it.startsWith("RSSI") })
    }

    @Test
    fun `format marks failures and includes steps`() {
        val text = UsbMonitorReport.format(
            UsbMonitorReport.Run(
                device = "0bda:8812", androidInfo = "Pixel 8, Android 15", appVersion = "spike",
                result = "claiming the USB interface failed (rc -6)", durationMs = 1500,
                stats = stats(Stat.STAGE to 2), perChannel = longArrayOf(), trace = "step a", logcat = null,
            ),
        )
        assertTrue(text.contains("Result: FAILED — claiming the USB interface failed (rc -6)"))
        assertTrue(text.contains("Steps:\nstep a"))
        assertTrue(text.contains("not a crash"))
    }
}
