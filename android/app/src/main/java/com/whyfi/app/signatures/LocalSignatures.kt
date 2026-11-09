package com.whyfi.app.signatures

import android.content.Context
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.scan.CatalogLabels

/**
 * The bundled catalog, loaded once (about 2 MB of JSON, so off the main
 * thread), and labels for passes: "Nespresso", "Tesla · iBeacon", by
 * upper-case MAC/BSSID. Same answers as the server (SignatureParityTest).
 */
object LocalSignatures {
    @Volatile private var loaded: SignatureCatalog? = null

    fun catalog(context: Context): SignatureCatalog? = loaded ?: synchronized(this) {
        loaded ?: runCatching {
            context.assets.open(SignatureCatalog.ASSET).reader(Charsets.UTF_8).use { SignatureCatalog.read(it) }
        }.getOrNull()?.also { loaded = it; com.whyfi.app.usbmon.SniffedDevices.catalog = it }
    }

    /** Every MAC/BSSID in [passes] mapped to its label, or to null when nothing matched. */
    fun labels(catalog: SignatureCatalog, passes: List<ScanSessionUploadRequest>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (pass in passes) {
            pass.bleObservations.forEach { o ->
                if (o.bleMac.isBlank()) return@forEach
                val advert = BleMatcher.parseAdvert(o.bleMac, o.deviceName, o.serviceUuids, o.manufacturerRecords, o.serviceData, o.manufacturerData)
                out[CatalogLabels.key(o.bleMac)] = text(catalog, BleMatcher.match(advert, catalog))
            }
            pass.wifiObservations.forEach { o ->
                if (o.bssid.isBlank()) return@forEach
                out[CatalogLabels.key(o.bssid)] = text(catalog, WifiMatcher.match(WifiMatcher.parse(o.bssid, o.ssid, o.vendorIeOuis), catalog))
            }
        }
        return out
    }

    fun text(catalog: SignatureCatalog, matches: List<SignatureMatch>): String? =
        matches.mapNotNull { catalog.get(it.id)?.name }.distinct().joinToString(" · ").ifBlank { null }
}
