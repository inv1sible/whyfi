package com.whyfi.app.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.whyfi.app.ble.BleDeviceScanner
import com.whyfi.app.cellular.CellularManager
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.PendingScanDao
import com.whyfi.app.data.local.PendingScanEntity
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.FtmObservationDto
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.UploadWorker
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.gnss.GnssStatusManager
import com.whyfi.app.lan.LanScanner
import java.text.SimpleDateFormat
import kotlin.math.abs
import kotlinx.coroutines.delay
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
    val ftmRangingManager = FtmRangingManager(context)
    val cellularManager = CellularManager(context)
    val bleDeviceScanner = BleDeviceScanner(context)
    val gnssStatusManager = GnssStatusManager(context)
    val lanScanner = LanScanner(context)
    /** Live GPS/network/fused fixes while anything here needs a position. */
    val liveLocation = LiveLocationTracker(context)
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
        val passStartBootMs = SystemClock.elapsedRealtime()
        liveLocation.acquire()
        try {
            return runScanPass(options, startedAt, passStartBootMs, onPhaseChange, onPartialResult)
        } finally {
            liveLocation.release()
        }
    }

    private suspend fun runScanPass(
        options: ScanOptions,
        startedAt: String,
        passStartBootMs: Long,
        onPhaseChange: (ScanPhase) -> Unit,
        onPartialResult: (PartialScanResult) -> Unit,
    ): ScanSessionUploadRequest {
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

        // Resolved at the end, against the pass midpoint: the radios were
        // heard across the whole pass, and GPS has had the pass to warm up.
        val location = resolveLocation(midpoint(passStartBootMs), waitMs = FIRST_FIX_WAIT_MS)
        onPhaseChange(ScanPhase.UPLOADING)
        val payload = location.toRequest(startedAt).copy(
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
        val startBootMs = SystemClock.elapsedRealtime()
        liveLocation.acquire()
        val (devices, location) = try {
            val found = lanScanner.scan(onProgress, onDeviceFound)
            found to resolveLocation(midpoint(startBootMs), waitMs = FIRST_FIX_WAIT_MS)
        } finally {
            liveLocation.release()
        }

        val payload = location.toRequest(startedAt).copy(lanObservations = devices)

        enqueueForUpload(payload)
        return payload
    }

    /** One manual Wi-Fi RTT/FTM ranging measurement against [bssid] — see
     * ap-localization-design.md's V3 and scan/FtmRangingManager.kt. Not part
     * of [runScan]'s regular multi-radio pass: triggered on demand (see
     * ui/ScanDetailScreen.kt's "Range" action on an 802.11mc-responder WiFi
     * row) against whatever the OS's own last-scan cache already holds for
     * that BSSID (RTT ranging needs the real ScanResult object, not just the
     * BSSID string). Uploads through the same outbox as every other scan —
     * see [enqueueForUpload] — so it survives being offline. */
    suspend fun rangeOnce(bssid: String): FtmObservationDto {
        val scanResult = wifiScanManager.lastScanResults().firstOrNull { it.BSSID == bssid }
            ?: return FtmObservationDto(bssid = bssid, success = false, status = "no_recent_scan")

        val startedAt = isoNow()
        liveLocation.acquire()
        val (result, location) = try {
            val ranged = ftmRangingManager.range(scanResult)
            // A range is only as useful as the position it was taken from,
            // so this one waits longer for a first fix than a scan pass does.
            ranged to resolveLocation(SystemClock.elapsedRealtime(), waitMs = FTM_FIX_WAIT_MS)
        } finally {
            liveLocation.release()
        }
        val payload = location.toRequest(startedAt).copy(ftmObservations = listOf(result))
        enqueueForUpload(payload)
        return result
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

    private data class ResolvedLocation(val primary: LocationFix?, val fused: Location?, val fixAgeMs: Long?)

    private fun ResolvedLocation.toRequest(startedAt: String) = ScanSessionUploadRequest(
        clientScanId = UUID.randomUUID().toString(),
        startedAt = startedAt,
        completedAt = isoNow(),
        latitude = primary?.lat,
        longitude = primary?.lng,
        locationAccuracyMeters = primary?.accuracyM?.toFloat(),
        locationProvider = primary?.provider ?: "",
        locationFixAgeMs = fixAgeMs,
        fusedLatitude = fused?.latitude,
        fusedLongitude = fused?.longitude,
        fusedAccuracyMeters = fused?.takeIf { it.hasAccuracy() }?.accuracy,
    )

    private fun midpoint(startBootMs: Long): Long = (startBootMs + SystemClock.elapsedRealtime()) / 2

    /**
     * The position for a pass centred on [referenceBootMs]: the best live fix
     * through FixSelector's gates, else cached last-known fixes through the
     * same gates, else none at all. No position is better than a stale one:
     * stale and coarse last-known fixes are what made scans jump.
     * `fused` stays the freshest platform fused fix (when under a minute
     * old) so the backend can compare the two.
     */
    private suspend fun resolveLocation(referenceBootMs: Long, waitMs: Long = 0L): ResolvedLocation {
        var best = liveLocation.selector.best(referenceBootMs)
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (best == null && liveLocation.isRunning() && SystemClock.elapsedRealtime() < deadline) {
            delay(FIX_POLL_MS)
            best = liveLocation.selector.best(referenceBootMs)
        }
        val now = SystemClock.elapsedRealtime()
        if (best == null) {
            best = FixSelector.bestOf(LocationSnapshot.lastKnownCandidates(context).map { it.toFix() }, referenceBootMs, now)
        }
        val fused = (liveLocation.latestFused() ?: fusedLocation())
            ?.takeIf { now - it.elapsedRealtimeNanos / 1_000_000L <= FUSED_MAX_AGE_MS }
        return ResolvedLocation(best, fused, best?.let { abs(it.bootMs - referenceBootMs) })
    }

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

    private companion object {
        /** A pass with no fix yet waits this long at the end for a first one. */
        const val FIRST_FIX_WAIT_MS = 5_000L
        const val FTM_FIX_WAIT_MS = 10_000L
        const val FIX_POLL_MS = 250L
        const val FUSED_MAX_AGE_MS = 60_000L
    }
}
