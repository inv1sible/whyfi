package com.whyfi.app.usbmon

import java.io.InputStream

/**
 * SPIKE — MAC prefix → vendor, from assets/oui.tsv ("AABBCC<TAB>Vendor" per
 * line, 24-bit IEEE assignments only; generated from oui.sqlite). Plain text:
 * the build tools would unpack a .gz asset anyway, and the APK compresses it.
 */
class OuiTable(private val vendors: Map<String, String>) {

    val size get() = vendors.size

    /** Vendor for "aa:bb:cc:dd:ee:ff", or null (unknown or randomized MAC). */
    fun vendorOf(mac: String): String? {
        val hex = mac.filter { it != ':' && it != '-' }.uppercase()
        if (hex.length < 6) return null
        if (hex.substring(0, 2).toInt(16) and 0x02 != 0) return null
        return vendors[hex.substring(0, 6)]
    }

    companion object {
        fun read(input: InputStream): OuiTable = input.bufferedReader().useLines { lines ->
            OuiTable(
                lines.mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab == 6) line.substring(0, 6) to line.substring(7) else null
                }.toMap(HashMap(40_000)),
            )
        }
    }
}
