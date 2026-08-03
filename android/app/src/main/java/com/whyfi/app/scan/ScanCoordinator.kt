package com.whyfi.app.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.whyfi.app.ble.BleDeviceScanner
import com.whyfi.app.cellular.CellularManager
import com.whyfi.app.data.LocationSourcePreference
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.PendingScanDao
import com.whyfi.app.data.local.PendingScanEntity
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.UploadWorker
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.gnss.GnssStatusManager
import com.whyfi.app.lan.LanScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class ScanPhase { WIFI, CELLULAR, BLE, GNSS, UPLOADING, DONE }

data class ScanOptions(
    val includeWifi: Boolean = true,
    val includeCellular: Boolean = true,
    val includeBle: Boolean = true,
    val includeGnss: Boolean = true,
)

/** A single radio phase's finished observation list, carried out through
 * [ScanCoordinator.runScan]'s `onPartialResult` as soon as that phase
 * completes — not just a count — so the caller (see
 * ScanForegroundService.runOnePass) can fold each radio's results into the
 * Dashboard's running survey immediately, rather than waiting for every
 * phase in the pass to finish. */
sealed interface PartialScanResult {
    data class Wifi(val observations: List<WifiObservationDto>) : PartialScanResult
    data class Cellular(val observations: List<CellObservationDto>) : PartialScanResult
    data class Ble(val observations: List<BleObservationDto>) : PartialScanResult
    data class Satellite(val observations: List<SatelliteObservationDto>) : PartialScanResult
}

/** Ties WiFi/cellular/BLE/GNSS together into one "Scan Now" pass, matching
 * the backend's single-endpoint, multi-radio ingest contract (see
 * backend/scans/serializers.py's ScanSessionIngestSerializer). */
class ScanCoordinator(private val context: Context) {

    val wifiScanManager = WifiScanManager(context)
    val cellularManager = CellularManager(context)
    val bleDeviceScanner = BleDeviceScanner(context)
    val gnssStatusManager = GnssStatusManager(context)
    val lanScanner = LanScanner(context)
    private val settingsRepository = SettingsRepository(context)
    private val gson = Gson()

    suspend fun runScan(
        options: ScanOptions = ScanOptions(),
        onPhaseChange: (ScanPhase) -> Unit = {},
        // Fired right after each radio's results are in, before moving to
        // the next one — lets the UI fold each radio's own results in as
        // the scan progresses instead of only showing anything once the
        // whole pass is done. Carries the actual observations, not just a
        // count, so the caller can update per-device survey state (unique
        // counts, strongest signal, etc.), not just a number on screen.
        onPartialResult: (PartialScanResult) -> Unit = {},
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val location = resolveLocation()

        val wifiObservations = if (options.includeWifi) {
            onPhaseChange(ScanPhase.WIFI)
            runPhaseCatching("WiFi") { wifiScanManager.scan() }.also { onPartialResult(PartialScanResult.Wifi(it)) }
        } else {
            emptyList()
        }

        val cellObservations = if (options.includeCellular) {
            onPhaseChange(ScanPhase.CELLULAR)
            runPhaseCatching("Cellular") { cellularManager.readCellObservations() }
                .also { onPartialResult(PartialScanResult.Cellular(it)) }
        } else {
            emptyList()
        }

        val bleObservations = if (options.includeBle) {
            onPhaseChange(ScanPhase.BLE)
            runPhaseCatching("BLE") { bleDeviceScanner.scan() }.also { onPartialResult(PartialScanResult.Ble(it)) }
        } else {
            emptyList()
        }

        val satelliteObservations = if (options.includeGnss) {
            onPhaseChange(ScanPhase.GNSS)
            runPhaseCatching("GNSS") { gnssStatusManager.captureSnapshot() }
                .also { onPartialResult(PartialScanResult.Satellite(it)) }
        } else {
            emptyList()
        }

        onPhaseChange(ScanPhase.UPLOADING)
        val payload = ScanSessionUploadRequest(
            clientScanId = UUID.randomUUID().toString(),
            startedAt = startedAt,
            completedAt = isoNow(),
            latitude = location.primary?.latitude,
            longitude = location.primary?.longitude,
            locationAccuracyMeters = location.primary?.accuracy,
            locationProvider = location.primary?.provider ?: "",
            fusedLatitude = location.fused?.latitude,
            fusedLongitude = location.fused?.longitude,
            fusedAccuracyMeters = location.fused?.accuracy,
            wifiObservations = wifiObservations,
            cellObservations = cellObservations,
            bleObservations = bleObservations,
            satelliteObservations = satelliteObservations,
        )

        enqueueForUpload(payload)
        onPhaseChange(ScanPhase.DONE)
        return payload
    }

    /** A subnet sweep + port scan takes much longer than the batch radio
     * pass above, so it's its own explicit action with its own lightweight
     * session — same pattern the removed NFC tap flow used. */
    suspend fun runLanScan(
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        onDeviceFound: (LanObservationDto) -> Unit = {},
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val location = resolveLocation()
        val devices = lanScanner.scan(onProgress, onDeviceFound)

        val payload = ScanSessionUploadRequest(
            clientScanId = UUID.randomUUID().toString(),
            startedAt = startedAt,
            completedAt = isoNow(),
            latitude = location.primary?.latitude,
            longitude = location.primary?.longitude,
            locationAccuracyMeters = location.primary?.accuracy,
            locationProvider = location.primary?.provider ?: "",
            fusedLatitude = location.fused?.latitude,
            fusedLongitude = location.fused?.longitude,
            fusedAccuracyMeters = location.fused?.accuracy,
            lanObservations = devices,
        )

        enqueueForUpload(payload)
        return payload
    }

    private suspend fun enqueueForUpload(payload: ScanSessionUploadRequest) {
        val dao = WhyfiDatabase.getInstance(context).pendingScanDao()
        dao.insert(
            PendingScanEntity(
                clientScanId = payload.clientScanId,
                payloadJson = gson.toJson(payload),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        enforceOutboxQuota(dao)
        UploadWorker.enqueue(context)
    }

    /** Keeps the offline outbox inside the user's storage budget by dropping
     * the oldest queued scans first.
     *
     * Budgeted in bytes rather than "keep the newest N scans" because payload
     * size swings by an order of magnitude with how crowded the airwaves are
     * — a fixed count would mean wildly different disk use in a quiet street
     * versus an apartment block. Oldest-first because when a survey has to
     * lose something, the stale end is worth less than what's happening now.
     */
    private suspend fun enforceOutboxQuota(dao: PendingScanDao) {
        val quotaBytes = settingsRepository.outboxQuotaBytes
        if ((dao.totalBytes() ?: 0L) <= quotaBytes) return

        val sizes = dao.sizesOldestFirst()
        var remaining = sizes.sumOf { it.bytes }
        val doomed = mutableListOf<String>()
        for (entry in sizes) {
            // Never evict the last row: if a single scan somehow exceeds the
            // whole quota, dropping it would silently discard every scan
            // forever. Better to overshoot the budget by one payload.
            if (remaining <= quotaBytes || doomed.size == sizes.size - 1) break
            doomed += entry.clientScanId
            remaining -= entry.bytes
        }
        if (doomed.isNotEmpty()) dao.deleteAll(doomed)
    }

    private data class ResolvedLocation(val primary: Location?, val fused: Location?)

    /** GPS mode's primary is untouched (identical call as always); BOTH
     * mode's primary is *also* untouched — it just additionally captures a
     * fused reading alongside it, so the two can be compared rather than
     * only ever recording whichever one "won". FUSED mode reports the
     * fused reading as primary, falling back to the GPS/network pick if
     * fused is unavailable (older API level, or no fix yet). */
    private fun resolveLocation(): ResolvedLocation = when (settingsRepository.locationSourcePreference) {
        LocationSourcePreference.GPS -> ResolvedLocation(primary = lastKnownLocation(), fused = null)
        LocationSourcePreference.FUSED -> ResolvedLocation(primary = fusedLocation() ?: lastKnownLocation(), fused = null)
        LocationSourcePreference.BOTH -> ResolvedLocation(primary = lastKnownLocation(), fused = fusedLocation())
    }

    private fun lastKnownLocation(): Location? = LocationSnapshot.lastKnown(context)

    // FUSED_PROVIDER was added in API 31 (S) — combines GPS/network/sensor
    // data via the platform's own fusion, no Play Services dependency
    // needed (unlike FusedLocationProviderClient).
    @SuppressLint("MissingPermission")
    private fun fusedLocation(): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull()
    }

    /** One radio misbehaving (a permission edge case, hardware the device
     * doesn't have, an OEM firmware quirk) must not take the other three
     * down with it — this ran with no isolation at all before, so an
     * uncaught exception from any single phase crashed the whole scan pass
     * (and, since nothing installs a CoroutineExceptionHandler either, the
     * whole app). Degrades to "nothing from this radio this pass" rather
     * than losing everything already collected. */
    private suspend fun <T> runPhaseCatching(radioName: String, block: suspend () -> List<T>): List<T> =
        runCatching { block() }.getOrElse { error ->
            Log.e("ScanCoordinator", "$radioName phase failed, continuing scan without it", error)
            emptyList()
        }

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
