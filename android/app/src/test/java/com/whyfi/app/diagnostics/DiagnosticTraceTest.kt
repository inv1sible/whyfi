package com.whyfi.app.diagnostics

import java.io.File
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTraceTest {

    private fun tempFile() = File.createTempFile("trace", ".txt").also { it.delete() }

    @Test
    fun `log appends timestamped lines and tail returns the last ones`() {
        val file = tempFile()
        repeat(5) { DiagnosticTrace.log(file, "step $it", Date(0)) }
        assertEquals("1970-01-01T00:00:00Z step 3\n1970-01-01T00:00:00Z step 4", DiagnosticTrace.tail(file, 2))
        file.delete()
    }

    @Test
    fun `tail of a missing file is null`() {
        assertNull(DiagnosticTrace.tail(tempFile()))
    }

    @Test
    fun `tailIfRecent ignores a stale trace`() {
        val file = tempFile()
        DiagnosticTrace.log(file, "old step")
        val now = file.lastModified()
        assertEquals(DiagnosticTrace.tail(file), DiagnosticTrace.tailIfRecent(file, now))
        assertNull(DiagnosticTrace.tailIfRecent(file, now + DiagnosticTrace.RECENT_WINDOW_MS + 1))
        file.delete()
    }

    @Test
    fun `log keeps the newest part once the file grows too large`() {
        val file = tempFile()
        repeat(3000) { DiagnosticTrace.log(file, "step $it " + "x".repeat(40)) }
        assertTrue(file.length() < 70 * 1024)
        assertTrue(DiagnosticTrace.tail(file, 1)!!.contains("step 2999"))
        file.delete()
    }
}
