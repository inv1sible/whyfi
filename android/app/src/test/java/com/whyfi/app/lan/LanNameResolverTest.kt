package com.whyfi.app.lan

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanNameResolverTest {
    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8); write(v and 0xFF) }
    private fun ByteArrayOutputStream.name(vararg labels: String) { labels.forEach { write(it.length); write(it.toByteArray()) }; write(0) }

    @Test
    fun mdnsQueryAsksForTheReversePtr() {
        val q = LanNameResolver.mdnsQuery("192.168.1.20")
        assertEquals("20.1.168.192.in-addr.arpa", LanNameResolver.reverseName("192.168.1.20"))
        assertArrayEquals(byteArrayOf(0, 12, 0, 1), q.copyOfRange(q.size - 4, q.size))
        assertEquals(1, q[5].toInt())
    }

    @Test
    fun mdnsAnswerWithCompressedOwnerName() {
        val p = ByteArrayOutputStream()
        p.u16(0); p.u16(0x8400); p.u16(1); p.u16(1); p.u16(0); p.u16(0)
        p.name("20", "1", "168", "192", "in-addr", "arpa"); p.u16(12); p.u16(1)
        p.u16(0xC00C); p.u16(12); p.u16(0x8001); p.u16(0); p.u16(120)
        val rdata = ByteArrayOutputStream().apply { name("Lukas-MacBook", "local") }.toByteArray()
        p.u16(rdata.size); p.write(rdata)
        assertEquals("Lukas-MacBook.local", LanNameResolver.parseMdnsPtr(p.toByteArray()))
        assertNull(LanNameResolver.parseMdnsPtr(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun netbiosPicksTheUniqueWorkstationName() {
        val q = LanNameResolver.netbiosQuery(7)
        assertEquals(50, q.size)
        assertEquals('C'.code, q[13].toInt()) // "*" = 0x2A -> 'C','K'
        assertEquals('K'.code, q[14].toInt())

        val p = ByteArrayOutputStream()
        p.u16(7); p.u16(0x8400); p.u16(0); p.u16(1); p.u16(0); p.u16(0)
        p.write(q, 12, 34)
        p.u16(0x21); p.u16(1); p.u16(0); p.u16(0); p.u16(0)
        p.write(2)
        fun entry(n: String, flags: Int) { p.write(n.padEnd(15).toByteArray()); p.write(0); p.u16(flags) }
        entry("WORKGROUP", 0x8400)
        entry("DESKTOP-42", 0x0400)
        assertEquals("DESKTOP-42", LanNameResolver.parseNetbiosName(p.toByteArray()))
        // No statistics block after the names: no MAC.
        assertNull(LanNameResolver.parseNetbiosMac(p.toByteArray()))

        // Windows: the unit ID after the name table is the MAC.
        val windows = ByteArrayOutputStream().apply { write(p.toByteArray()); write(byteArrayOf(0x3c, 0x52, 0x82.toByte(), 0x1a, 0x2b, 0x3c)); write(ByteArray(40)) }
        assertEquals("3c:52:82:1a:2b:3c", LanNameResolver.parseNetbiosMac(windows.toByteArray()))
        // Samba: zeros, dropped.
        val samba = ByteArrayOutputStream().apply { write(p.toByteArray()); write(ByteArray(46)) }
        assertNull(LanNameResolver.parseNetbiosMac(samba.toByteArray()))
        assertNull(LanNameResolver.parseNetbiosMac(byteArrayOf(1, 2, 3)))
    }
}
