package com.whyfi.app.scan

import java.util.Locale

/**
 * Fieldwatch catalog labels ("Nespresso", "Tesla", "BMW") kept by upper-case
 * MAC/BSSID for the screens and reports. Computed on the phone
 * (signatures/LocalSignatures), so they work offline and without a server;
 * the server labels the same way (SignatureParityTest). Informational
 * pattern matches: no alert state.
 */
object CatalogLabels {
    const val MAX_KEYS = 5000

    fun key(mac: String): String = mac.trim().uppercase(Locale.ROOT)

    /** Adds fresh labels; a key that no longer matches loses its old label. Oldest keys drop past MAX_KEYS. */
    fun merge(current: Map<String, String>, fresh: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap(current)
        fresh.forEach { (k, label) ->
            out.remove(k)
            if (label != null) out[k] = label
        }
        while (out.size > MAX_KEYS) out.remove(out.keys.first())
        return out
    }
}
