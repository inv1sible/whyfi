package com.whyfi.app.scan

import java.util.Locale

/**
 * `vendor_ie_ouis` wire format for `parse_wifi` in
 * `backend/scans/wifi_signatures.py`: the OUI of each vendor-specific
 * information element (ID 221) as `"AA:BB:CC"`, distinct, beacon order,
 * at most [MAX] (the backend truncates there too). Only OUIs are sent, not
 * element bodies: a scan can hold hundreds of access points. Pure Kotlin,
 * no Android types, so it's unit-testable.
 */
object WifiIeFormat {
    const val VENDOR_SPECIFIC_ID = 221
    const val MAX = 32

    fun vendorOuis(elements: List<Pair<Int, ByteArray>>): List<String> {
        val out = LinkedHashSet<String>()
        for ((id, bytes) in elements) {
            if (id != VENDOR_SPECIFIC_ID || bytes.size < 3) continue
            out += String.format(
                Locale.ROOT,
                "%02X:%02X:%02X",
                bytes[0].toInt() and 0xFF,
                bytes[1].toInt() and 0xFF,
                bytes[2].toInt() and 0xFF,
            )
            if (out.size >= MAX) break
        }
        return out.toList()
    }
}
