package com.whyfi.app.lan

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun v4(text: String) = InetAddress.getByName(text) as Inet4Address

class LanScannerTest {

    @Test
    fun `interface names are classified by their conventional prefixes`() {
        assertEquals("WiFi", LanScanner.classify("wlan0"))
        assertEquals("Wired", LanScanner.classify("eth0"))
        assertEquals("VPN/tunnel", LanScanner.classify("tun0"))
        assertEquals("VPN/tunnel", LanScanner.classify("ipsec1"))
        assertEquals("Mobile data", LanScanner.classify("rmnet_data0"))
        assertEquals("Other", LanScanner.classify("something-else"))
    }

    @Test
    fun `an ordinary home network is sweepable`() {
        assertNull(
            LanScanner.skipReason(
                up = true, loopback = false, kind = "WiFi",
                address = v4("192.168.10.148"), prefixLength = 24,
            ),
        )
    }

    @Test
    fun `a slash 23 is sweepable because it fits under the host cap`() {
        // 510 hosts. The old code rejected anything wider than /24 outright,
        // which was stricter than the cap it was protecting.
        assertNull(
            LanScanner.skipReason(
                up = true, loopback = false, kind = "WiFi",
                address = v4("10.0.2.5"), prefixLength = 23,
            ),
        )
    }

    @Test
    fun `a VPN tunnel is refused as a tunnel, not as an empty network`() {
        // The bug this test exists for: a VPN's /32 was reported as "the
        // current network has no other addresses on it" while the phone was
        // sitting on a perfectly populated /24. The distinction matters —
        // one is a wrong claim about the network, the other is the truth.
        val reason = LanScanner.skipReason(
            up = true, loopback = false, kind = "VPN/tunnel",
            address = v4("10.220.77.11"), prefixLength = 32,
        )
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("tunnel"))
    }

    @Test
    fun `mobile data is refused with its own reason`() {
        val reason = LanScanner.skipReason(
            up = true, loopback = false, kind = "Mobile data",
            address = v4("10.11.12.13"), prefixLength = 30,
        )
        assertEquals("mobile data has no local network to sweep", reason)
    }

    @Test
    fun `point-to-point prefixes have no hosts to find`() {
        for (prefix in listOf(31, 32)) {
            val reason = LanScanner.skipReason(
                up = true, loopback = false, kind = "WiFi",
                address = v4("192.168.1.1"), prefixLength = prefix,
            )
            assertTrue("prefix /$prefix", reason!!.contains("point-to-point"))
        }
    }

    @Test
    fun `a self-assigned address is not a real network`() {
        val reason = LanScanner.skipReason(
            up = true, loopback = false, kind = "WiFi",
            address = v4("169.254.4.5"), prefixLength = 16,
        )
        assertTrue(reason!!, reason.contains("self-assigned"))
    }

    @Test
    fun `an oversized subnet is usable but flagged truncated, not refused`() {
        // Changed from an outright refusal: a big office network is still a
        // real LAN worth partial results from, so this is now just capped
        // (see NetworkCandidate.truncated / resolveSubnet's hostCount), not
        // rejected — skipReason must stay null for it.
        assertNull(
            LanScanner.skipReason(
                up = true, loopback = false, kind = "WiFi",
                address = v4("10.0.0.5"), prefixLength = 16,
            ),
        )
        assertTrue(LanScanner.isTruncated(16))
        assertFalse(LanScanner.isTruncated(24))
    }

    @Test
    fun `down and loopback interfaces are skipped first`() {
        assertEquals(
            "loopback",
            LanScanner.skipReason(true, loopback = true, kind = "Other", address = v4("127.0.0.1"), prefixLength = 8),
        )
        assertEquals(
            "interface is down",
            LanScanner.skipReason(false, loopback = false, kind = "WiFi", address = v4("192.168.1.5"), prefixLength = 24),
        )
    }

    @Test
    fun `wifi outranks wired and other when several interfaces qualify`() {
        assertTrue(
            LanScanner.KIND_PREFERENCE.indexOf("WiFi") < LanScanner.KIND_PREFERENCE.indexOf("Wired"),
        )
        assertTrue(
            LanScanner.KIND_PREFERENCE.indexOf("Wired") < LanScanner.KIND_PREFERENCE.indexOf("Other"),
        )
    }
}
