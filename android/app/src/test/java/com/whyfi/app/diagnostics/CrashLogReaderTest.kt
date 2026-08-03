package com.whyfi.app.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogReaderTest {

    @Test
    fun `readCrashLog returns null for a nonexistent file`() {
        val file = File.createTempFile("crash", ".txt").also { it.delete() }
        assertNull(CrashLogReader.readCrashLog(file))
    }

    @Test
    fun `readCrashLog returns exactly what was written`() {
        val file = File.createTempFile("crash", ".txt")
        file.writeText("2026-08-02T09:28:55Z\njava.lang.IllegalStateException: boom")
        assertEquals("2026-08-02T09:28:55Z\njava.lang.IllegalStateException: boom", CrashLogReader.readCrashLog(file))
        file.delete()
    }

    @Test
    fun `clearCrashLog deletes an existing file and returns true`() {
        val file = File.createTempFile("crash", ".txt")
        file.writeText("something")
        assertTrue(CrashLogReader.clearCrashLog(file))
        assertFalse(file.exists())
    }

    @Test
    fun `clearCrashLog on an already-absent file returns true and throws nothing`() {
        val file = File.createTempFile("crash", ".txt").also { it.delete() }
        assertTrue(CrashLogReader.clearCrashLog(file))
    }

    @Test
    fun `parse splits the timestamp from the stack trace`() {
        val parsed = CrashLogReader.parse("2026-08-02T09:28:55Z\njava.lang.IllegalStateException: boom\n\tat Foo.bar")
        assertEquals("2026-08-02T09:28:55Z", parsed?.occurredAt)
        assertEquals("java.lang.IllegalStateException: boom\n\tat Foo.bar", parsed?.stackTrace)
    }

    @Test
    fun `parse returns null for text with no newline`() {
        assertNull(CrashLogReader.parse("not a crash log"))
    }
}
