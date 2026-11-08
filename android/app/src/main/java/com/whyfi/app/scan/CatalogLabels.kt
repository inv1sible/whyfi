package com.whyfi.app.scan

import com.google.gson.annotations.SerializedName
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import java.util.Locale

/**
 * Fieldwatch catalog labels ("Nespresso", "Tesla", "BMW") for what the phone
 * just heard. The catalog and its matcher live only on the backend (see
 * MEMORY.md), so after a pass the phone asks `signatures/match/`, which
 * stores nothing, and keeps the answers by MAC/BSSID. Informational pattern
 * matches, like the PWA's labels: no alert state. Pure, JVM-testable.
 */
object CatalogLabels {
    const val MAX_KEYS = 5000

    data class BleItem(
        @SerializedName("ble_mac") val bleMac: String,
        @SerializedName("device_name") val deviceName: String,
        @SerializedName("service_uuids") val serviceUuids: List<String>,
        @SerializedName("manufacturer_records") val manufacturerRecords: List<String>,
        @SerializedName("service_data") val serviceData: List<String>,
    )

    data class WifiItem(
        @SerializedName("bssid") val bssid: String,
        @SerializedName("ssid") val ssid: String,
        @SerializedName("vendor_ie_ouis") val vendorIeOuis: List<String>,
    )

    data class Request(
        @SerializedName("ble") val ble: List<BleItem>,
        @SerializedName("wifi") val wifi: List<WifiItem>,
    ) {
        val isEmpty get() = ble.isEmpty() && wifi.isEmpty()
    }

    data class Label(
        @SerializedName("id") val id: String = "",
        @SerializedName("name") val name: String = "",
        @SerializedName("kind") val kind: String = "",
    )

    data class Response(
        @SerializedName("catalog_version") val catalogVersion: Int? = null,
        @SerializedName("ble") val ble: Map<String, List<Label>>? = null,
        @SerializedName("wifi") val wifi: Map<String, List<Label>>? = null,
    )

    fun key(mac: String): String = mac.trim().uppercase(Locale.ROOT)

    /** A pass's adverts not asked about yet. A BLE advert is asked again when
     * its name or payload changes (a rotating address may become a car key). */
    fun request(pass: ScanSessionUploadRequest, asked: Set<String>): Request = Request(
        ble = pass.bleObservations.filter { it.bleMac.isNotBlank() }
            .map { BleItem(it.bleMac, it.deviceName, it.serviceUuids, it.manufacturerRecords.ifEmpty { listOfNotNull(it.manufacturerData.ifBlank { null }) }, it.serviceData) }
            .filter { bleAskKey(it.bleMac, it.deviceName, it.manufacturerRecords, it.serviceUuids) !in asked },
        wifi = pass.wifiObservations.filter { it.bssid.isNotBlank() && wifiAskKey(it.bssid, it.ssid) !in asked }
            .map { WifiItem(it.bssid, it.ssid, it.vendorIeOuis) },
    )

    fun askedKeys(request: Request): List<String> =
        request.ble.map { bleAskKey(it.bleMac, it.deviceName, it.manufacturerRecords, it.serviceUuids) } +
            request.wifi.map { wifiAskKey(it.bssid, it.ssid) }

    private fun bleAskKey(mac: String, name: String, records: List<String>, uuids: List<String>) =
        "b|${key(mac)}|$name|${records.joinToString(",")}|${uuids.joinToString(",")}".hashCode().toString() + key(mac)

    private fun wifiAskKey(bssid: String, ssid: String) = "w|${key(bssid)}|$ssid"

    /** "Tesla" or "Nespresso · iBeacon": the names, in catalog order. */
    fun text(labels: List<Label>): String? = labels.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { null }

    /** Adds a response to the labels so far; the newest answer for a key wins. */
    fun merge(current: Map<String, String>, response: Response): Map<String, String> {
        val out = LinkedHashMap(current)
        (response.ble.orEmpty() + response.wifi.orEmpty()).forEach { (mac, labels) ->
            val k = key(mac)
            out.remove(k)
            text(labels)?.let { out[k] = it }
        }
        while (out.size > MAX_KEYS) out.remove(out.keys.first())
        return out
    }
}
