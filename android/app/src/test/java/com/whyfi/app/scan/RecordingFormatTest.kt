package com.whyfi.app.scan

import com.whyfi.app.data.local.RecordingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingFormatTest {
    private fun r(passes: Int, uploaded: Int, sync: Boolean, rejected: Int = 0) =
        RecordingSummary("id", "scan", 0, 1, sync, null, passes, 2_500_000, uploaded, rejected)

    @Test
    fun durations() {
        assertEquals("recording…", RecordingFormat.duration(0, null))
        assertEquals("under 1 min", RecordingFormat.duration(0, 59_000))
        assertEquals("23 min", RecordingFormat.duration(0, 23 * 60_000L))
        assertEquals("1 h 05 min", RecordingFormat.duration(0, 65 * 60_000L))
    }

    @Test
    fun syncStates() {
        assertEquals("on this phone only", RecordingFormat.sync(r(4, 0, sync = false)))
        assertEquals("1 of 4 waiting to upload", RecordingFormat.sync(r(4, 3, sync = true)))
        assertEquals("uploaded", RecordingFormat.sync(r(4, 4, sync = true)))
        assertEquals("uploaded · 1 refused by the server", RecordingFormat.sync(r(4, 3, sync = true, rejected = 1)))
        assertEquals("Scan · 4 passes · 2.4 MB · uploaded", RecordingFormat.summary(r(4, 4, sync = true)))
    }

    @Test
    fun isoTimesFromPasses() {
        assertEquals(1_762_606_931_000L, RecordingFormat.epochMs("2025-11-08T13:02:11Z"))
        assertEquals(1_762_606_931_250L, RecordingFormat.epochMs("2025-11-08T13:02:11.250Z"))
        assertNull(RecordingFormat.epochMs("yesterday"))
    }
}
