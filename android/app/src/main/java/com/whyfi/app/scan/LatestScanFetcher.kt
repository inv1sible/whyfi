package com.whyfi.app.scan

import android.util.Log
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.BleObservationResponseDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.CellObservationResponseDto
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.LanObservationResponseDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.SatelliteObservationResponseDto
import com.whyfi.app.data.remote.ScanSessionListResponse
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.data.remote.WifiObservationResponseDto

/**
 * Best-effort backfill of the Dashboard from the backend on a fresh install
 * (or whenever [LastScanStore] has no local pass).
 *
 * Fetches the latest scan-session summary, then its per-radio observations,
 * and reassembles them into a [ScanSessionUploadRequest] — the same shape
 * [ScanCoordinator] produces locally — so the existing display pipeline
 * (ScanDiff, RadioFormat, DashboardScreen) works unchanged.
 *
 * The reconstructed pass is **display-only**: it must never enter the upload
 * outbox. It already exists on the backend; re-uploading would be a no-op
 * (idempotent on client_scan_id) but wasteful and confusing. The
 * client_scan_id is set to the backend session's UUID for traceability.
 *
 * Not persisted to [LastScanStore] — that file is for locally-scanned passes.
 * The fetched pass is ephemeral (lives in ScanUiState until a real local
 * scan replaces it). Re-fetching on every cold start is cheap and keeps the
 * Dashboard fresh.
 */
object LatestScanFetcher {

    private const val TAG = "LatestScanFetcher"

    /**
     * Fetches the latest session and its observations, returning a
     * display-only [ScanSessionUploadRequest], or null on any failure
     * (network error, not configured, empty backend).
     */
    suspend fun fetch(backendUrl: String, token: String): ScanSessionUploadRequest? {
        val api = try {
            ApiClientFactory.create(backendUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create API client", e)
            return null
        }

        val auth = "Token $token"

        // Step 1: latest session summary
        val sessionList = runCatching {
            val resp = api.listScanSessions(auth, limit = 1)
            if (!resp.isSuccessful) {
                Log.w(TAG, "listScanSessions returned ${resp.code()}")
                return null
            }
            resp.body()
        }.getOrElse { e ->
            Log.e(TAG, "listScanSessions failed", e)
            return null
        }
        val sessions = sessionList?.results
        if (sessions.isNullOrEmpty()) return null

        val session = sessions[0]

        // Step 2: fetch all observation types in parallel (each is an
        // independent GET). runCatching on each so a single radio failing
        // doesn't lose the whole pass — mirrors how ScanCoordinator handles
        // per-radio failures locally.
        val wifiResult = runCatching {
            api.getWifiObservations(auth, session.id)
        }
        val cellResult = runCatching {
            api.getCellObservations(auth, session.id)
        }
        val bleResult = runCatching {
            api.getBleObservations(auth, session.id)
        }
        val satResult = runCatching {
            api.getSatelliteObservations(auth, session.id)
        }
        val lanResult = runCatching {
            api.getLanObservations(auth, session.id)
        }

        // Log individual failures but continue — a pass with only WiFi and
        // BLE is still useful on the Dashboard.
        wifiResult.onFailure { Log.w(TAG, "WiFi observations fetch failed", it) }
        cellResult.onFailure { Log.w(TAG, "Cell observations fetch failed", it) }
        bleResult.onFailure { Log.w(TAG, "BLE observations fetch failed", it) }
        satResult.onFailure { Log.w(TAG, "Satellite observations fetch failed", it) }
        lanResult.onFailure { Log.w(TAG, "LAN observations fetch failed", it) }

        val wifiObs = wifiResult.getOrNull()?.body().orEmpty().map { it.toDto() }
        val cellObs = cellResult.getOrNull()?.body().orEmpty().map { it.toDto() }
        val bleObs = bleResult.getOrNull()?.body().orEmpty().map { it.toDto() }
        val satObs = satResult.getOrNull()?.body().orEmpty().map { it.toDto() }
        val lanObs = lanResult.getOrNull()?.body().orEmpty().map { it.toDto() }

        // If every radio came back empty there's nothing to show.
        if (wifiObs.isEmpty() && cellObs.isEmpty() && bleObs.isEmpty() &&
            satObs.isEmpty() && lanObs.isEmpty()
        ) {
            Log.w(TAG, "All observation lists empty for session ${session.id}")
            return null
        }

        return ScanSessionUploadRequest(
            clientScanId = session.id,
            startedAt = session.startedAt,
            completedAt = session.completedAt,
            latitude = session.latitude,
            longitude = session.longitude,
            locationAccuracyMeters = session.locationAccuracyMeters?.toFloat(),
            locationProvider = session.locationProvider,
            fusedLatitude = session.fusedLatitude,
            fusedLongitude = session.fusedLongitude,
            fusedAccuracyMeters = session.fusedAccuracyMeters?.toFloat(),
            wifiObservations = wifiObs,
            cellObservations = cellObs,
            bleObservations = bleObs,
            satelliteObservations = satObs,
            lanObservations = lanObs,
        )
    }

    // --- Mapping from read-serializer DTOs back to ingest-serializer DTOs ---
    // The display pipeline (ScanDiff, RadioFormat, DashboardScreen) reads the
    // ingest DTOs, so we reverse-map. The backend's read serializer carries
    // server-computed channel/band/security_type; the ingest DTO carries the
    // raw capabilities string. For display, RadioFormat.securityLabel and
    // RadioFormat.channelLabel derive from capabilities/frequency — so we
    // pass the security_type through as a pseudo-capabilities string that
    // RadioFormat.securityLabel will parse correctly.

    private fun WifiObservationResponseDto.toDto(): WifiObservationDto {
        // The read serializer gives us security_type (e.g. "WPA3") but not
        // the raw capabilities string. RadioFormat.securityLabel parses
        // capabilities — so we pass security_type as capabilities, which
        // securityLabel handles correctly for all known types (it checks
        // for "SAE", "PSK", "OWE", "WPA2"/"RSN", "WPA", "WEP", "ESS").
        // For WPA2_WPA3 we need both SAE and PSK present.
        val caps = when (securityType) {
            "WPA2_WPA3" -> "SAE PSK"
            "WPA3" -> "SAE"
            "OWE" -> "OWE"
            "WPA2" -> "RSN"
            "WPA" -> "WPA"
            "WEP" -> "WEP"
            "OPEN" -> "ESS"
            else -> ""
        }
        return WifiObservationDto(
            bssid = accessPoint ?: "",
            ssid = "",  // Read serializer doesn't return SSID separately;
                        // it's on the AccessPoint, not the observation.
                        // Dashboard shows "(hidden)" for blank — acceptable
                        // for a display-only backfill.
            rssi = rssi,
            frequencyMhz = frequencyMhz,
            capabilities = caps,
            channelWidthMhz = channelWidthMhz,
            centerFreq0Mhz = centerFreq0Mhz,
            centerFreq1Mhz = centerFreq1Mhz,
            wifiStandard = wifiStandard,
            is80211mcResponder = is80211mcResponder,
            operatorFriendlyName = operatorFriendlyName,
            venueName = venueName,
        )
    }

    private fun CellObservationResponseDto.toDto(): CellObservationDto =
        CellObservationDto(
            mcc = mcc,
            mnc = mnc,
            carrierName = carrierName,
            radioType = radioType,
            cellId = cellId,
            tacOrLac = tacOrLac,
            band = band,
            isServingCell = isServingCell,
            signalDbm = signalDbm,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            physicalCellId = physicalCellId,
            arfcn = arfcn,
            bandwidthKhz = bandwidthKhz,
            timingAdvance = timingAdvance,
        )

    private fun BleObservationResponseDto.toDto(): BleObservationDto =
        BleObservationDto(
            bleMac = bleMac,
            stableIdentifier = stableIdentifier,
            rssi = rssi,
            txPower = txPower,
            manufacturerData = manufacturerDataRaw,
            serviceUuids = serviceUuids,
            deviceTypeGuess = deviceTypeGuess,
            deviceName = deviceName,
            isConnectable = isConnectable,
            primaryPhy = primaryPhy,
        )

    private fun SatelliteObservationResponseDto.toDto(): SatelliteObservationDto =
        SatelliteObservationDto(
            constellation = constellation,
            svid = svid,
            cn0DbHz = cn0DbHz,
            elevationDegrees = elevationDegrees,
            azimuthDegrees = azimuthDegrees,
            usedInFix = usedInFix,
            carrierFrequencyHz = carrierFrequencyHz,
            hasEphemerisData = hasEphemerisData,
            hasAlmanacData = hasAlmanacData,
        )

    private fun LanObservationResponseDto.toDto(): LanObservationDto =
        LanObservationDto(
            ipAddress = ipAddress,
            macAddress = macAddress,
            hostname = hostname,
            vendorOui = vendorOui,
            openPorts = openPorts,
            responseTimeMs = responseTimeMs,
            banner = banner,
            deviceTypeGuess = deviceTypeGuess,
        )
}
