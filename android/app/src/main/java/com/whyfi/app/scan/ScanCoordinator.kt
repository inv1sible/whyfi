package com.whyfi.app.scan

import com.whyfi.app.data.local.RecordingEntity
import com.whyfi.app.data.local.RecordingStore

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
import com.whyfi.app.ble.BleDeviceScanner
import com.whyfi.app.cellular.CellularManager
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.FtmObservationDto
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import com.whyfi.app.gnss.GnssStatusManager
import com.whyfi.app.lan.LanScanner
import com.whyfi.app.lan.LanTarget
import java.text.SimpleDateFormat
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** RADIOS: the four radios are running together (see RadioRunner); which of
 * them is still busy is reported per radio through onRadioActive. */
enum class ScanPhase { RADIOS, WIFI, CELLULAR, BLE, GNSS, UPLOADING, DONE }

private data class PassObservations(
    val wifi: List<WifiObservationDto>,
    val cell: List<CellObservationDto>,
    val ble: List<BleObservationDto>,
    val satellite: List<SatelliteObservationDto>,
)

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
    /** Where every pass goes: the phone's recordings (and from there, a server). */
    val recordings = RecordingStore(context)

    suspend fun runScan(
        options: ScanOptions = ScanOptions(),
        /** The recording this pass belongs to; null makes it a one-pass recording. */
        recordingId: String? = null,
        onPhaseChange: (ScanPhase) -> Unit = {},
        // Fired right after each radio's results are in, before moving to
        // the next one — lets the UI fold each radio's own results in as
        // the scan progresses instead of only showing anything once the
        // whole pass is done. Carries the actual observations, not just a
        // count, so the caller can update per-device survey state (unique
        // counts, strongest signal, etc.), not just a number on screen.
        onPartialResult: (PartialScanResult) -> Unit = {},
        // A radio started (true) or finished (false). Radios run at the same
        // time, so the UI tracks a set rather than one current phase.
        onRadioActive: (ScanPhase, Boolean) -> Unit = { _, _ -> },
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val passStartBootMs = SystemClock.elapsedRealtime()
        liveLocation.acquire()
        try {
            return runScanPass(options, recordingId, startedAt, passStartBootMs, onPhaseChange, onPartialResult, onRadioActive)
        } finally {
            liveLocation.release()
        }
    }

    private suspend fun runScanPass(
        options: ScanOptions,
        recordingId: String?,
        startedAt: String,
        passStartBootMs: Long,
        onPhaseChange: (ScanPhase) -> Unit,
        onPartialResult: (PartialScanResult) -> Unit,
        onRadioActive: (ScanPhase, Boolean) -> Unit,
    ): ScanSessionUploadRequest {
        // All radios at once (see RadioRunner); the pass lasts as long as the
        // slowest one instead of the sum of all four.
        onPhaseChange(ScanPhase.RADIOS)
        val runner = RadioRunner(onRadioActive, onPartialResult) { radio, error ->
            Log.e("ScanCoordinator", "$radio phase failed, continuing scan without it", error)
        }
        val (wifiObservations, cellObservations, bleObservations, satelliteObservations) = coroutineScope {
            with(runner) {
                val wifi = radio(options.includeWifi, ScanPhase.WIFI, block = { wifiScanManager.scan() }) {
                    PartialScanResult.Wifi(it)
                }
                val cell = radio(options.includeCellular, ScanPhase.CELLULAR, block = { cellularManager.readCellObservations() }) {
                    PartialScanResult.Cellular(it)
                }
                val ble = radio(
                    options.includeBle, ScanPhase.BLE,
                    startDelayMs = if (options.includeWifi) RadioRunner.BLE_STAGGER_MS else 0,
                    block = { bleDeviceScanner.scan() },
                ) { PartialScanResult.Ble(it) }
                val gnss = radio(options.includeGnss, ScanPhase.GNSS, block = { gnssStatusManager.captureSnapshot() }) {
                    PartialScanResult.Satellite(it)
                }
                PassObservations(wifi.await(), cell.await(), ble.await(), gnss.await())
            }
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

        recordings.store(payload, recordingId, RecordingEntity.KIND_SCAN)
        onPhaseChange(ScanPhase.DONE)
        return payload
    }

    /** A subnet sweep + port scan takes much longer than the batch radio
     * pass above, so it's its own explicit action with its own lightweight
     * session — same pattern the removed NFC tap flow used. */
    suspend fun runLanScan(
        target: LanTarget = LanTarget.Auto,
        recordingId: String? = null,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        onDeviceFound: (LanObservationDto) -> Unit = {},
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val startBootMs = SystemClock.elapsedRealtime()
        liveLocation.acquire()
        val (devices, location) = try {
            val found = lanScanner.scan(target, onProgress, onDeviceFound)
            found to resolveLocation(midpoint(startBootMs), waitMs = FIRST_FIX_WAIT_MS)
        } finally {
            liveLocation.release()
        }

        val payload = location.toRequest(startedAt).copy(lanObservations = devices)

        recordings.store(payload, recordingId, RecordingEntity.KIND_LAN)
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
    suspend fun rangeOnce(bssid: String, recordingId: String? = null): FtmObservationDto {
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
        recordings.store(payload, recordingId, RecordingEntity.KIND_RANGE)
        return result
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
        while (best == null && liveLocation.warmingUp(SystemClock.elapsedRealtime()) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
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
