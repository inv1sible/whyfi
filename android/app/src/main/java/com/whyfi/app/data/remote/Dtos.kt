package com.whyfi.app.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Mirrors backend/scans/serializers.py's ScanSessionIngestSerializer exactly.
 * One physical scan pass = one POST here, atomically, idempotent on
 * clientScanId. Don't split this into per-radio requests — see AGENT.md.
 */
data class ScanSessionUploadRequest(
    @SerializedName("client_scan_id") val clientScanId: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("completed_at") val completedAt: String,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("location_accuracy_meters") val locationAccuracyMeters: Float?,
    @SerializedName("location_provider") val locationProvider: String = "",
    @SerializedName("fused_latitude") val fusedLatitude: Double? = null,
    @SerializedName("fused_longitude") val fusedLongitude: Double? = null,
    @SerializedName("fused_accuracy_meters") val fusedAccuracyMeters: Float? = null,
    @SerializedName("wifi_observations") val wifiObservations: List<WifiObservationDto> = emptyList(),
    @SerializedName("cell_observations") val cellObservations: List<CellObservationDto> = emptyList(),
    @SerializedName("ble_observations") val bleObservations: List<BleObservationDto> = emptyList(),
    @SerializedName("satellite_observations") val satelliteObservations: List<SatelliteObservationDto> = emptyList(),
    @SerializedName("lan_observations") val lanObservations: List<LanObservationDto> = emptyList(),
)

data class WifiObservationDto(
    @SerializedName("bssid") val bssid: String,
    @SerializedName("ssid") val ssid: String,
    @SerializedName("rssi") val rssi: Int,
    @SerializedName("frequency_mhz") val frequencyMhz: Int,
    @SerializedName("capabilities") val capabilities: String,
    @SerializedName("channel_width_mhz") val channelWidthMhz: Int? = null,
    @SerializedName("center_freq0_mhz") val centerFreq0Mhz: Int? = null,
    @SerializedName("center_freq1_mhz") val centerFreq1Mhz: Int? = null,
    @SerializedName("wifi_standard") val wifiStandard: String = "",
    @SerializedName("is_80211mc_responder") val is80211mcResponder: Boolean = false,
    @SerializedName("operator_friendly_name") val operatorFriendlyName: String = "",
    @SerializedName("venue_name") val venueName: String = "",
)

data class CellObservationDto(
    @SerializedName("mcc") val mcc: String,
    @SerializedName("mnc") val mnc: String,
    @SerializedName("carrier_name") val carrierName: String,
    @SerializedName("radio_type") val radioType: String,
    @SerializedName("cell_id") val cellId: String,
    @SerializedName("tac_or_lac") val tacOrLac: String,
    @SerializedName("band") val band: String,
    @SerializedName("is_serving_cell") val isServingCell: Boolean,
    @SerializedName("signal_dbm") val signalDbm: Int?,
    @SerializedName("rsrp") val rsrp: Int?,
    @SerializedName("rsrq") val rsrq: Int?,
    @SerializedName("sinr") val sinr: Double?,
    @SerializedName("physical_cell_id") val physicalCellId: Int? = null,
    @SerializedName("arfcn") val arfcn: Int? = null,
    @SerializedName("bandwidth_khz") val bandwidthKhz: Int? = null,
    @SerializedName("timing_advance") val timingAdvance: Int? = null,
)

data class BleObservationDto(
    @SerializedName("ble_mac") val bleMac: String,
    @SerializedName("stable_identifier") val stableIdentifier: String,
    @SerializedName("rssi") val rssi: Int,
    @SerializedName("tx_power") val txPower: Int?,
    @SerializedName("manufacturer_data") val manufacturerData: String,
    @SerializedName("service_uuids") val serviceUuids: List<String> = emptyList(),
    @SerializedName("device_type_guess") val deviceTypeGuess: String,
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("is_connectable") val isConnectable: Boolean = false,
    @SerializedName("primary_phy") val primaryPhy: String = "",
)

data class SatelliteObservationDto(
    @SerializedName("constellation") val constellation: String,
    @SerializedName("svid") val svid: Int,
    @SerializedName("cn0_db_hz") val cn0DbHz: Double,
    @SerializedName("elevation_degrees") val elevationDegrees: Double?,
    @SerializedName("azimuth_degrees") val azimuthDegrees: Double?,
    @SerializedName("used_in_fix") val usedInFix: Boolean,
    @SerializedName("carrier_frequency_hz") val carrierFrequencyHz: Double? = null,
    @SerializedName("has_ephemeris_data") val hasEphemerisData: Boolean = false,
    @SerializedName("has_almanac_data") val hasAlmanacData: Boolean = false,
)

data class LanObservationDto(
    @SerializedName("ip_address") val ipAddress: String,
    @SerializedName("mac_address") val macAddress: String = "",
    @SerializedName("hostname") val hostname: String = "",
    @SerializedName("vendor_oui") val vendorOui: String = "",
    @SerializedName("open_ports") val openPorts: List<Int> = emptyList(),
    @SerializedName("response_time_ms") val responseTimeMs: Double? = null,
    @SerializedName("banner") val banner: String = "",
    @SerializedName("device_type_guess") val deviceTypeGuess: String = "UNKNOWN",
)

data class ScanSessionResponse(
    @SerializedName("id") val id: String,
)
