package com.whyfi.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReportFormatterTest {

    @Test
    fun `printableStrings keeps readable runs from binary tombstone bytes`() {
        val bytes = byteArrayOf(0x08, 0x01) + "SIGSEGV".toByteArray() + byteArrayOf(0x00, 0x12) +
            "/data/app/libwhyfiusb.so".toByteArray() + byteArrayOf(0x1A, 0x03) + "ab".toByteArray()
        assertEquals("SIGSEGV\n/data/app/libwhyfiusb.so", ExitReportFormatter.printableStrings(bytes))
    }

    @Test
    fun `printableStrings drops runs shorter than the minimum`() {
        val bytes = "abc".toByteArray() + byteArrayOf(0x00) + "abcd".toByteArray()
        assertEquals("abcd", ExitReportFormatter.printableStrings(bytes))
    }

    @Test
    fun `printableStrings caps output size`() {
        val bytes = ("x".repeat(100) + "\u0000").repeat(1000).toByteArray()
        assertTrue(ExitReportFormatter.printableStrings(bytes, maxChars = 500).length <= 500)
    }

    @Test
    fun `format includes kind, description, trace and breadcrumbs`() {
        val text = ExitReportFormatter.format("Native crash", "signal 11", "com.whyfi.app", "frame #00", "step: open usb")
        assertTrue(text.startsWith("Native crash"))
        assertTrue(text.contains("Description: signal 11"))
        assertTrue(text.contains("frame #00"))
        assertTrue(text.contains("Last diagnostic steps:\nstep: open usb"))
    }

    @Test
    fun `format omits missing parts`() {
        val text = ExitReportFormatter.format("App not responding (ANR)", null, null, null, null)
        assertFalse(text.contains("Description"))
        assertFalse(text.contains("Last diagnostic steps"))
    }
}
