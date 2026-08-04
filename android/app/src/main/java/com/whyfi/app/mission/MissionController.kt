package com.whyfi.app.mission

import android.content.Context
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.remote.ApiClientFactory
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
import kotlinx.coroutines.withTimeoutOrNull

/** A favorited thing Mission view can track — an SSID (WIFI), a MAC/stable
 * identifier (BLE), or a tower key (CELLULAR). [kind] picks which backend
 * mission_*_observations endpoint [identifier] is looked up against. */
data class MissionTarget(val kind: RadioKind, val identifier: String)

/** Radio-agnostic point Mission view actually renders — the three backend
 * responses carry a few kind-specific fields (bssid, scan_session_id, …)
 * that nothing here uses; MissionController flattens all three down to just
 * what Geo.kt/ConeOverlay need. [bssid] is carried for WiFi so MissionScreen
 * can group readings by AP (multi-AP deployments broadcasting the same
 * SSID); null for BLE/cell, which have no BSSID and collapse to one group. */
data class MissionPoint(val lat: Double, val lng: Double, val weight: Double, val bssid: String? = null)

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

    fun stop() {
        stopTrackingJobs()
        _uiState.value = _uiState.value.copy(isTracking = false, livePosition = null)
    }

    /** Drops everything, including the last-fetched points — used when
     * navigating away from a favorite entirely, as opposed to [stop], which
     * leaves the map showing what was last found. */
    fun clearSelection() {
        stopTrackingJobs()
        _uiState.value = MissionUiState()
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
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Configure a backend URL and sensor token in Settings first.",
            )
            return
        }

        try {
            val api = ApiClientFactory.create(backendUrl)
            val auth = "Token $token"
            val (points, truncated) = when (target.kind) {
                RadioKind.WIFI -> {
                    val response = api.missionWifiObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Fetch failed (HTTP ${response.code()}).")
                        return
                    }
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight, it.bssid) } to body.truncated
                }
                RadioKind.BLE -> {
                    val response = api.missionBleObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Fetch failed (HTTP ${response.code()}).")
                        return
                    }
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight) } to body.truncated
                }
                RadioKind.CELLULAR -> {
                    val response = api.missionCellObservations(
                        auth, target.identifier, location.latitude, location.longitude, radiusMeters,
                    )
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Fetch failed (HTTP ${response.code()}).")
                        return
                    }
                    body.points.map { MissionPoint(it.lat, it.lng, it.weight) } to body.truncated
                }
                RadioKind.SATELLITE -> error("SATELLITE has no Mission target — this is a caller bug, not a real state.")
            }
            _uiState.value = _uiState.value.copy(isLoading = false, points = points, truncated = truncated, error = null)
        } catch (e: IOException) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not reach the backend: ${e.message}")
        }
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
