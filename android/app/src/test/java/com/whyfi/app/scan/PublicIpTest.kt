package com.whyfi.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicIpTest {
    @Test
    fun acceptsOnlyABareAddress() {
        assertEquals("203.0.113.7", PublicIp.parse("203.0.113.7\n"))
        assertEquals("2001:db8::1", PublicIp.parse("2001:db8::1"))
        assertNull(PublicIp.parse("<html>Sign in to the hotel WiFi</html>"))
        assertNull(PublicIp.parse("300.1.1.1"))
        assertNull(PublicIp.parse("abc"))
    }

    @Test
    fun refreshesAfterFiveMinutes() {
        assertTrue(PublicIp.due(0, PublicIp.REFRESH_MS))
        assertFalse(PublicIp.due(1_000, 1_000 + PublicIp.REFRESH_MS - 1))
    }
}
