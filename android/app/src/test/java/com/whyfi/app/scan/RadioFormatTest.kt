package com.whyfi.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinned to the same cases as `SecurityParsingTests` in
 * `backend/scans/tests.py`. These two functions are a deliberate
 * transcription of the backend's (see [RadioFormat]'s KDoc), so the two
 * suites are meant to move together — if you add a case there, add it here.
 */
class RadioFormatTest {

    @Test
    fun `band and channel match the backend for known frequencies`() {
        assertEquals(RadioFormat.BAND_24 to 6, RadioFormat.bandFor(2437) to RadioFormat.channelFor(2437))
        assertEquals(RadioFormat.BAND_24 to 14, RadioFormat.bandFor(2484) to RadioFormat.channelFor(2484))
        assertEquals(RadioFormat.BAND_5 to 36, RadioFormat.bandFor(5180) to RadioFormat.channelFor(5180))
        assertEquals(RadioFormat.BAND_6 to 1, RadioFormat.bandFor(5955) to RadioFormat.channelFor(5955))
        assertEquals(RadioFormat.BAND_6 to 233, RadioFormat.bandFor(7115) to RadioFormat.channelFor(7115))
    }

    @Test
    fun `channel label falls back to the raw frequency when the channel is unknown`() {
        // 5910 sits in the gap between the backend's 5GHz and 6GHz ranges.
        // Mirroring that gap matters more than papering over it: a made-up
        // channel here would disagree with what the backend stored.
        assertEquals("5910 MHz", RadioFormat.channelLabel(5910))
        assertEquals("36 · 5GHz", RadioFormat.channelLabel(5180))
    }

    @Test
    fun `hidden ssid is labelled rather than blank`() {
        assertEquals("(hidden)", RadioFormat.ssidLabel(""))
        assertEquals("MyNetwork", RadioFormat.ssidLabel("MyNetwork"))
    }

    @Test
    fun `wpa3 personal is detected via SAE, not the protocol prefix`() {
        assertEquals("WPA3", RadioFormat.securityLabel("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"))
        assertEquals("WPA3", RadioFormat.securityLabel("[RSN-SAE+FT/SAE-CCMP][ESS][MFPR]"))
        assertEquals("WPA3", RadioFormat.securityLabel("[RSN-EAP_SUITE_B_192-GCMP-256][ESS][MFPR]"))
    }

    @Test
    fun `transition mode advertising both reads as WPA2 WPA3`() {
        assertEquals("WPA2/WPA3", RadioFormat.securityLabel("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
    }

    @Test
    fun `enhanced open is neither WPA2 nor plain open`() {
        assertEquals("OWE", RadioFormat.securityLabel("[RSN-OWE-CCMP][ESS][MFPR]"))
        assertEquals("OWE", RadioFormat.securityLabel("[RSN-OWE_TRANSITION-CCMP][ESS]"))
    }

    @Test
    fun `both spellings of WPA2 are recognised`() {
        assertEquals("WPA2", RadioFormat.securityLabel("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA2", RadioFormat.securityLabel("[RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA2", RadioFormat.securityLabel("[WPA2-EAP-CCMP][ESS]"))
    }

    @Test
    fun `wpa1 and wep`() {
        assertEquals("WPA", RadioFormat.securityLabel("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WEP", RadioFormat.securityLabel("[WEP][ESS]"))
    }

    @Test
    fun `open with and without extra flags`() {
        assertEquals("Open", RadioFormat.securityLabel("[ESS]"))
        assertEquals("Open", RadioFormat.securityLabel("[ESS][WPS]"))
        assertEquals("Open", RadioFormat.securityLabel("[ESS][MFPC]"))
        assertEquals("Open", RadioFormat.securityLabel(""))
    }
}
