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

/** Mirrors backend/sensors/serializers.py's CrashReportIngestSerializer. Sent
 * from ui/SettingsScreen.kt's Diagnostics section — a manual, one-off action,
 * not part of the continuous scan-upload pipeline. */
data class CrashReportRequest(
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("app_version") val appVersion: String = "",
    @SerializedName("device_model") val deviceModel: String = "",
    @SerializedName("os_version") val osVersion: String = "",
    @SerializedName("stack_trace") val stackTrace: String,
)

data class CrashReportResponse(
    @SerializedName("id") val id: String,
)

/**
 * What this device tells the backend it's actually doing, once per
 * heartbeat. Everything here is observed state — the backend refuses to let
 * a device write its own desired state, so none of this can enable scanning.
 */
data class SensorHeartbeatRequest(
    @SerializedName("reported_is_continuous") val reportedIsContinuous: Boolean,
    @SerializedName("reported_is_scanning") val reportedIsScanning: Boolean,
    @SerializedName("reported_phase") val reportedPhase: String = "",
    @SerializedName("reported_completed_scans") val reportedCompletedScans: Int = 0,
    @SerializedName("reported_wifi_unavailable_reason") val reportedWifiUnavailableReason: String = "",
    @SerializedName("reported_cellular_unavailable_reason") val reportedCellularUnavailableReason: String = "",
    @SerializedName("reported_ble_unavailable_reason") val reportedBleUnavailableReason: String = "",
    @SerializedName("reported_permissions_granted") val reportedPermissionsGranted: Boolean,
    @SerializedName("reported_location_services_enabled") val reportedLocationServicesEnabled: Boolean,
    @SerializedName("reported_pending_uploads") val reportedPendingUploads: Int,
    @SerializedName("reported_outbox_bytes") val reportedOutboxBytes: Long,
    @SerializedName("reported_outbox_quota_mb") val reportedOutboxQuotaMb: Int,
    @SerializedName("reported_battery_percent") val reportedBatteryPercent: Int?,
    @SerializedName("reported_app_version") val reportedAppVersion: String = "",
    @SerializedName("reported_policy_revision") val reportedPolicyRevision: Int,
    @SerializedName("reported_scan_now_nonce") val reportedScanNowNonce: Int,
    @SerializedName("reported_reset_counters_nonce") val reportedResetCountersNonce: Int,
    // Why the device is scanning at the cadence it is. Without these a phone
    // deliberately idling at 10-minute intervals is indistinguishable from a
    // broken one in the web UI.
    @SerializedName("reported_motion_state") val reportedMotionState: String = "",
    @SerializedName("reported_effective_interval_seconds") val reportedEffectiveIntervalSeconds: Int? = null,
)

/**
 * What the backend says this device *should* be doing. Not a command — it's
 * the target state to converge on, so an instruction issued while this phone
 * was offline simply takes effect on the next successful heartbeat.
 *
 * Defaults are the safe interpretation of a malformed/partial response:
 * don't scan.
 */
data class ScanPolicyResponse(
    @SerializedName("remote_scan_enabled") val remoteScanEnabled: Boolean = false,
    @SerializedName("scan_interval_seconds") val scanIntervalSeconds: Int = 60,
    @SerializedName("heartbeat_interval_seconds") val heartbeatIntervalSeconds: Int = 15,
    @SerializedName("include_wifi") val includeWifi: Boolean = true,
    @SerializedName("include_cellular") val includeCellular: Boolean = true,
    @SerializedName("include_ble") val includeBle: Boolean = true,
    @SerializedName("include_gnss") val includeGnss: Boolean = true,
    @SerializedName("scan_now_nonce") val scanNowNonce: Int = 0,
    @SerializedName("reset_counters_nonce") val resetCountersNonce: Int = 0,
    @SerializedName("policy_revision") val policyRevision: Int = 0,
    // Per-motion-state cadence. Defaults match SettingsRepository and the
    // backend model, so a malformed or partial response lands on the same
    // numbers rather than something surprising.
    @SerializedName("adaptive_scan_enabled") val adaptiveScanEnabled: Boolean = true,
    @SerializedName("stationary_interval_seconds") val stationaryIntervalSeconds: Int = 600,
    @SerializedName("walking_interval_seconds") val walkingIntervalSeconds: Int = 60,
    @SerializedName("driving_interval_seconds") val drivingIntervalSeconds: Int = 30,
)
