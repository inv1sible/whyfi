package com.whyfi.app.scan

import android.net.wifi.ScanResult
import android.os.Build
import com.whyfi.app.data.remote.WifiObservationDto

object ScanResultMapper {
    fun toDto(result: ScanResult): WifiObservationDto = WifiObservationDto(
        bssid = result.BSSID ?: "",
        ssid = result.SSID ?: "",
        rssi = result.level,
        frequencyMhz = result.frequency,
        capabilities = result.capabilities ?: "",
        channelWidthMhz = channelWidthMhz(result.channelWidth),
        centerFreq0Mhz = result.centerFreq0.takeIf { it != 0 },
        centerFreq1Mhz = result.centerFreq1.takeIf { it != 0 },
        wifiStandard = wifiStandardLabel(result),
        is80211mcResponder = result.is80211mcResponder,
        operatorFriendlyName = passpointField(result) { it.operatorFriendlyName },
        venueName = passpointField(result) { it.venueName },
        vendorIeOuis = WifiIeFormat.vendorOuis(informationElements(result)),
    )

    // ScanResult.getInformationElements() is API 30 (R), like the Passpoint
    // fields below. Vendor IE OUIs feed the backend's signature matcher
    // (backend/scans/wifi_signatures.py). Guarded like Fieldwatch's
    // WifiRadio.vendorIeOuisOf: a vendor ROM throwing here must not cost the
    // whole scan result.
    private fun informationElements(result: ScanResult): List<Pair<Int, ByteArray>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val elements = runCatching { result.informationElements }.getOrNull() ?: return emptyList()
        return elements.mapNotNull { element ->
            runCatching {
                val buffer = element.bytes.duplicate()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                element.id to bytes
            }.getOrNull()
        }
    }

    // operatorFriendlyName/venueName are Passpoint/Hotspot 2.0 fields added
    // in API 30 (R), same generation as wifiStandard below — referencing
    // them on an older OS build throws NoSuchFieldError (a LinkageError,
    // not caught by a plain try/catch at the call site) since the field
    // doesn't exist in that OS's ScanResult class at all.
    private fun passpointField(result: ScanResult, field: (ScanResult) -> CharSequence?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return field(result)?.toString() ?: ""
    }

    private fun channelWidthMhz(channelWidth: Int): Int? = when (channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
        else -> null
    }

    private fun wifiStandardLabel(result: ScanResult): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return when (result.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "802.11n"
            ScanResult.WIFI_STANDARD_11AC -> "802.11ac"
            ScanResult.WIFI_STANDARD_11AX -> "802.11ax"
            ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
            else -> ""
        }
    }
}
