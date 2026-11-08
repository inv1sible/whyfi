package com.whyfi.app.mission

import android.content.Context
import com.whyfi.app.data.PositionEstimator
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.RecordingStore
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.MissionEstimatesDto
import com.whyfi.app.scan.LocationSnapshot
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanOptions
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A favorited thing Mission view can track — an SSID (WIFI), a MAC/stable
 * identifier (BLE), or a tower key (CELLULAR). [kind] picks which backend
 * mission_*_observations endpoint [identifier] is looked up against. */
data class MissionTarget(val kind: RadioKind, val identifier: String)

/** Radio-agnostic point Mission view actually renders — the three backend
 * responses carry a few kind-specific fields (bssid, scan_session_id, …)
 * that nothing here uses; MissionController flattens all three down to just
 * what Geo.kt/ConeOverlay need. */
data class MissionPoint(val lat: Double, val lng: Double, val weight: Double)

data class MissionUiState(
    val target: MissionTarget? = null,
    val isLoading: Boolean = false,
    /** True between Start and Stop — see [MissionController.start]. Also
     * what the Dashboard/Scan stat chip's live indicator reads. */
    val isTracking: Boolean = false,
    val points: List<MissionPoint> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
    /** The phone's current GPS/network fix while tracking — redrawn as the
     * emphasized "you are here" cone on every update. Null when not
     * tracking or no fix has arrived yet. */
    val livePosition: LatLng? = null,
    /** What each estimator says about this target's position, from the
     * backend (see compare_estimators in scans/estimators.py). Drives both
     * the cone apex (for the estimator the user selected) and the
     * per-algorithm readout. Null when the backend didn't supply one — e.g.
     * an older backend, or no readings at all — in which case the apex falls
     * back to a locally computed centroid. */
    val estimates: MissionEstimatesDto? = null,
    /** The estimator the user picked in Settings, carried into UI state so
     * the screen doesn't have to re-read SharedPreferences to label things. */
    val estimator: PositionEstimator = PositionEstimator.CENTROID,
    /** The phone's position while Mission is on screen ([MissionController.watchHere]),
     * independent of tracking: the map centres on it before any target. */
    val here: LatLng? = null,
    /** True when [points] come from scans stored on this phone rather than
     * the server (no server set up, or it couldn't be reached). */
    val fromPhone: Boolean = false,
    /** Why the phone's scans were used although a server is set up. */
    val notice: String? = null,
)

/**
 * Fetches and holds one favorited target's near-location-filtered
 * observations for the Mission view's map (see MissionScreen.kt) — either a
 * single fetch ([selectTarget]) or continuous tracking ([start]/[stop]).
 * Works the same way regardless of [MissionTarget.kind]; only [fetchOnce]
 * branches per-kind, to call the matching backend endpoint.
 *
 * Deliberately a plain class, not a ViewModel — this app has no ViewModel
 * usage anywhere (ScanUiState is driven by a bound Service + StateFlow
 * instead, see ScanCoordinator). Constructed once at the app level (see
 * MainActivity.kt) rather than per-screen, so [uiState] — specifically
 * [MissionUiState.isTracking] — stays visible to the Dashboard/Scan stat
 * chip's live indicator even while the user has navigated away from Mission
 * view. This is app-process-scoped state, not a foreground Service: it
 * survives navigating between this app's own tabs, but stops if the app
 * process is killed (e.g. swiped away), same as any other in-memory state.
 * Fetched points are never written to Room/DataStore/SharedPreferences —
 * "temporarily" means in-memory only, for as long as the process lives.
 */
class MissionController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val _uiState = MutableStateFlow(MissionUiState())
    val uiState: StateFlow<MissionUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationSubscription: AutoCloseable? = null
    private var trackingJob: Job? = null
    private val recordings = RecordingStore(context)

    /** Follows the phone's position for the map while Mission is visible.
     * Null without location permission. Close it when Mission leaves the
     * screen. */
    fun watchHere(): AutoCloseable? {
        LocationSnapshot.lastKnown(context)?.let { setHere(it.latitude, it.longitude) }
        return LocationSnapshot.requestUpdates(context, includeFused = true) { setHere(it.latitude, it.longitude) }
    }

    private fun setHere(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(here = LatLng(lat, lng))
    }

    /** One-shot fetch for a tap on a favorite that isn't being tracked. */
    suspend fun selectTarget(target: MissionTarget, radiusMeters: Double = DEFAULT_NEAR_RADIUS_M) {
        stopTrackingJobs()
        _uiState.value = _uiState.value.copy(
            target = target, isLoading = true, isTracking = false, error = null, livePosition = null,
        )
        fetchOnce(target, radiusMeters)
    }

    /**
     * Starts continuous tracking of [target]. Two update rates run at once:
     * - **Live position** (fast, local only): a GPS/network listener redraws
     *   a cone from the estimated position to the phone's current fix on
     *   every location update — no network call, just "which way to walk."
     * - **Fresh data** (slow, every [SCAN_INTERVAL_MS]): triggers a real
     *   scan of just [target]'s radio through the already-bound [service]
     *   (same mechanism as Scan screen's own "Scan Now"), which uploads
     *   through the normal pipeline, then re-queries the backend once it
     *   completes so the estimate folds in whatever was just measured.
     *
     * Replaces any tracking already in progress — switching the favorite
     * chip while tracking restarts against the new target rather than
     * requiring a manual Stop first.
     */
    fun start(target: MissionTarget, service: ScanForegroundService?, radiusMeters: Double = DEFAULT_NEAR_RADIUS_M) {
        stopTrackingJobs()
        _uiState.value = _uiState.value.copy(
            target = target, isTracking = true, error = null, livePosition = null,
        )

        locationSubscription = LocationSnapshot.requestUpdates(context) { location ->
            _uiState.value = _uiState.value.copy(livePosition = LatLng(location.latitude, location.longitude))
        }

        trackingJob = scope.launch {
            fetchOnce(target, radiusMeters)
            while (isActive) {
                delay(SCAN_INTERVAL_MS)
                if (service != null) {
                    val before = service.uiState.value.completedScanCount
                    service.scanOnce(scanOptionsFor(target.kind))
                    // A bounded wait, not a fixed sleep: reacts as soon as
                    // the pass actually finishes (typically a couple of
                    // seconds), but won't hang the loop forever if the scan
                    // gets skipped (e.g. throttled) and completedScanCount
                    // never moves.
                    withTimeoutOrNull(SCAN_COMPLETION_TIMEOUT_MS) {
                        service.uiState.first { it.completedScanCount > before }
                    }
                }
                fetchOnce(target, radiusMeters)
            }
        }
    }

    /** Switches the position estimator and persists it. The caller re-runs
     * the current target afterwards so the new algorithm's apex is fetched —
     * this only records the choice, it doesn't refetch on its own, so
     * changing it while not tracking doesn't fire a surprise network call. */
    fun setEstimator(estimator: PositionEstimator) {
        settingsRepository.positionEstimator = estimator
        _uiState.value = _uiState.value.copy(estimator = estimator)
    }

    fun stop() {
        stopTrackingJobs()
        _uiState.value = _uiState.value.copy(isTracking = false, livePosition = null)
    }

    /** Drops everything, including the last-fetched points — used when
     * navigating away from a favorite entirely, as opposed to [stop], which
     * leaves the map showing what was last found. */
    fun clearSelection() {
        stopTrackingJobs()
        _uiState.value = MissionUiState(here = _uiState.value.here)
    }

    private fun stopTrackingJobs() {
        trackingJob?.cancel()
        trackingJob = null
        locationSubscription?.close()
        locationSubscription = null
    }

    private fun scanOptionsFor(kind: RadioKind): ScanOptions = when (kind) {
        RadioKind.WIFI -> ScanOptions(includeWifi = true, includeCellular = false, includeBle = false, includeGnss = false)
        RadioKind.CELLULAR -> ScanOptions(includeWifi = false, includeCellular = true, includeBle = false, includeGnss = false)
        RadioKind.BLE -> ScanOptions(includeWifi = false, includeCellular = false, includeBle = true, includeGnss = false)
        RadioKind.SATELLITE -> error("SATELLITE has no Mission target — this is a caller bug, not a real state.")
    }

    private suspend fun fetchOnce(target: MissionTarget, radiusMeters: Double) {
        val location = LocationSnapshot.lastKnown(context)
            ?: _uiState.value.here?.let { here -> android.location.Location("mission").apply { latitude = here.lat; longitude = here.lng } }
        if (location == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "No location fix yet — move somewhere with a clear GPS/network signal and try again.",
            )
            return
        }

        val backendUrl = settingsRepository.backendUrl
        val token = settingsRepository.sensorToken
        if (backendUrl.isNullOrBlank() || token.isNullOrBlank()) {
            fetchLocal(target, location.latitude, location.longitude, radiusMeters, notice = null)
            return
        }

        try {
            val api = ApiClientFactory.create(backendUrl)
            val auth = "Token $token"
            var estimates: MissionEstimatesDto? = null
            val (points, truncated) = when (target.kind) {
                RadioKind.WIFI -> {
                    val response = api.missionWifiObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        fetchLocal(target, location.latitude, location.longitude, radiusMeters, "Server answered HTTP ${response.code()}")
                        return
                    }
                    estimates = body.estimates
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight) } to body.truncated
                }
                RadioKind.BLE -> {
                    val response = api.missionBleObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        fetchLocal(target, location.latitude, location.longitude, radiusMeters, "Server answered HTTP ${response.code()}")
                        return
                    }
                    estimates = body.estimates
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight) } to body.truncated
                }
                RadioKind.CELLULAR -> {
                    val response = api.missionCellObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        fetchLocal(target, location.latitude, location.longitude, radiusMeters, "Server answered HTTP ${response.code()}")
                        return
                    }
                    estimates = body.estimates
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight) } to body.truncated
                }
                RadioKind.SATELLITE -> error("SATELLITE has no Mission target — this is a caller bug, not a real state.")
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                points = points,
                truncated = truncated,
                error = null,
                estimates = estimates,
                estimator = settingsRepository.positionEstimator,
                fromPhone = false,
                notice = null,
            )
        } catch (e: IOException) {
            fetchLocal(target, location.latitude, location.longitude, radiusMeters, "Server not reachable")
        }
    }

    /** Readings from this phone's stored scans (LocalMissionPoints). The
     * cone apex is then the local weighted centroid (no estimator comparison). */
    private suspend fun fetchLocal(target: MissionTarget, lat: Double, lng: Double, radiusMeters: Double, notice: String?) {
        val result = withContext(Dispatchers.IO) {
            LocalMissionPoints.points(recordings.payloadsContaining(LocalMissionPoints.needle(target)), target, lat, lng, radiusMeters)
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            points = result.points,
            truncated = result.truncated,
            error = null,
            estimates = null,
            estimator = settingsRepository.positionEstimator,
            fromPhone = true,
            notice = notice,
        )
    }

    companion object {
        const val DEFAULT_NEAR_RADIUS_M = 250.0

        /** Fallback shape radius when a cone can't be drawn (apex too close
         * to the reading, or only one reading exists so far) — see
         * MissionScreen.kt. Deliberately not the PWA's per-radio-type
         * log-distance path-loss model (coverageConfig.ts's
         * estimateRangeMeters); that's real scope for a case Mission view
         * expects to be rare, since its whole purpose is "many readings near
         * one spot," which almost always yields a usable weighted centroid. */
        const val FALLBACK_CIRCLE_RADIUS_M = 15.0

        private const val SCAN_INTERVAL_MS = 30_000L
        private const val SCAN_COMPLETION_TIMEOUT_MS = 15_000L
    }
}
