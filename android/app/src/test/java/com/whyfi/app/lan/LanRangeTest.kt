package com.whyfi.app.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRangeTest {

    private fun ok(text: String) = LanRange.parse(text) as LanRange.Ok
    private fun invalid(text: String) = (LanRange.parse(text) as LanRange.Invalid).reason

    @Test
    fun cidrExcludesNetworkAndBroadcast() {
        val r = ok("192.168.1.77/24")
        assertEquals("192.168.1.1", LanRange.format(r.first))
        assertEquals("192.168.1.254", LanRange.format(r.last))
        assertEquals(254, r.count)
        assertEquals(1, ok("10.0.0.5/32").count)
        assertEquals(2, ok("10.0.0.4/31").count)
    }

    @Test
    fun dashedRangesTakeFullOrLastOctetEnds() {
        assertEquals(41, ok("192.168.1.10-192.168.1.50").count)
        val short = ok(" 192.168.1.10 - 50 ")
        assertEquals("192.168.1.50", LanRange.format(short.last))
        assertEquals("192.168.1.10 – 192.168.1.50 · 41 addresses", short.describe())
        assertEquals(1, ok("172.20.3.4").count)
    }

    @Test
    fun publicMixedAndOversizedRangesAreRefused() {
        assertTrue(invalid("8.8.8.0/24").startsWith("Only private"))
        assertTrue(invalid("192.168.255.250-192.169.0.5").startsWith("Only private"))
        assertTrue(invalid("10.0.0.0/16").contains("65534 addresses"))
        assertEquals(510, ok("10.1.2.0/23").count)
    }

    @Test
    fun malformedInputSaysWhatIsWrong() {
        assertTrue(invalid("").startsWith("Enter a range"))
        assertTrue(invalid("192.168.1").contains("not an IPv4 address"))
        assertTrue(invalid("192.168.1.0/33").contains("prefix length"))
        assertTrue(invalid("192.168.1.300").contains("not an IPv4 address"))
        assertTrue(invalid("192.168.1.50-10").contains("ends before"))
        assertTrue(invalid("192.168.1.5-abc").contains("last octet"))
    }
}
