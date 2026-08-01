package com.whyfi.app.scan

/**
 * Display helpers for turning raw radio values into what a person reads.
 *
 * [bandFor] and [channelFor] are **transcriptions of
 * `backend/scans/serializers.py`'s `band_for_frequency` / `channel_for_frequency`**
 * and must be changed in step with them. This is the same kind of mirrored
 * pair as `RemoteControlAgent.IDLE_BACKOFF` <-> `sensors/models.py` — the
 * phone and the backend showing different channels for the same sighting
 * would make the two clients quietly contradict each other, and the phone
 * can't ask the backend because it only ever POSTs (see WhyfiApiService).
 *
 * [securityLabel] mirrors `security_type_from_capabilities` from the same
 * file, whose docstring explains why this keys on the *key management* token
 * rather than the protocol prefix (a WPA3 network contains the substring
 * "WPA3" nowhere at all). `SecurityParsingTests` in `backend/scans/tests.py`
 * pins the exact strings it must handle — check there before changing this.
 */
object RadioFormat {

    const val BAND_24 = "2.4GHz"
    const val BAND_5 = "5GHz"
    const val BAND_6 = "6GHz"

    fun bandFor(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2400..2500 -> BAND_24
        in 4900..5900 -> BAND_5
        in 5925..7125 -> BAND_6
        else -> BAND_24
    }

    /** 0 means "couldn't work it out" — same fallback the backend uses, so a
     * nonsense frequency shows the same thing in both clients. */
    fun channelFor(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5000..5900 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> 0
    }

    /** Channel plus band, e.g. "36 · 5GHz". Falls back to the raw frequency
     * when the channel is unknown rather than printing a confident "0". */
    fun channelLabel(frequencyMhz: Int): String {
        val channel = channelFor(frequencyMhz)
        val band = bandFor(frequencyMhz)
        return if (channel == 0) "$frequencyMhz MHz" else "$channel · $band"
    }

    /** Android reports an unknown/hidden SSID as an empty string. */
    fun ssidLabel(ssid: String): String = ssid.ifBlank { "(hidden)" }

    /** `[RSN-SAE-CCMP][ESS][MFPR][MFPC]` is unreadable in a phone-width
     * table cell, and says "WPA3" nowhere. Transcribed from the backend —
     * see the class KDoc. */
    fun securityLabel(capabilities: String): String {
        val caps = capabilities.uppercase()

        val hasSae = "SAE" in caps                      // WPA3-Personal (also FT/SAE)
        val hasPsk = "PSK" in caps                      // WPA/WPA2-Personal (also FT/PSK)
        val hasSuiteB = "EAP_SUITE_B_192" in caps       // WPA3-Enterprise 192-bit
        val hasOwe = "OWE" in caps                      // Enhanced Open (also OWE_TRANSITION)

        return when {
            // One BSS advertising both so either generation of client can join.
            hasSae && hasPsk -> "WPA2/WPA3"
            hasSae || hasSuiteB -> "WPA3"
            // Encrypted, but joinable with no credential — kept distinct from
            // Open, which the UI is entitled to flag as unencrypted.
            hasOwe -> "OWE"
            // "WPA2" is the legacy spelling; "RSN" is what newer Android
            // builds emit for the same PSK/EAP networks. Both mean WPA2.
            "WPA2" in caps || "RSN" in caps -> "WPA2"
            "WPA" in caps -> "WPA"
            "WEP" in caps -> "WEP"
            // Nothing above matched, so no security scheme was advertised.
            caps.isEmpty() || "ESS" in caps -> "Open"
            else -> "Unknown"
        }
    }
}
