package com.whyfi.app.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.location.Location
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whyfi.app.BuildConfig
import com.whyfi.app.R
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.RecordingEntity
import com.whyfi.app.signatures.LocalSignatures
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.lan.LanTarget
import com.whyfi.app.report.PhoneReports
import com.whyfi.app.data.remote.ScanPolicyResponse
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.SensorHeartbeatRequest
import com.whyfi.app.motion.MotionDetector
import com.whyfi.app.motion.MotionState
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.ui.MainActivity
import com.whyfi.app.usbmon.OuiTable
import com.whyfi.app.usbmon.AdapterStatus
import com.whyfi.app.usbmon.MonitorMission
import com.whyfi.app.usbmon.MonitorGeoSample
import com.whyfi.app.usbmon.MonitorReceiverFix
import com.whyfi.app.usbmon.MonitorLocationCandidate
import com.whyfi.app.usbmon.MonitorLocationPolicy
import com.whyfi.app.usbmon.MonitorMovePrompt
import com.whyfi.app.usbmon.SniffedDevices
import com.whyfi.app.usbmon.UsbMonitorNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class MonitorUiState(
    val running: Boolean = false,
    val status: String = "Ready",
    val device: String = "",
    val frames: Long = 0,
    val pcapPath: String? = null,
    val reportPath: String? = null,
    val missionLogPath: String? = null,
    val pcapBytes: Long = 0,
    val pcapTruncated: Boolean = false,
    val eapolKeyFrames: Long = 0,
    val geoSamples: Int = 0,
    val receiverFixCount: Int = 0,
    val snapshotRevisionMs: Long = 0,
    val geoStatus: String = "",
    val moveSuggested: Boolean = false,
    val locationFixes: List<MonitorLocationCandidate> = emptyList(),
    val locationUpdatesRegistered: Boolean = false,
    val geoDiagnostics: MonitorGeoDiagnostics = MonitorGeoDiagnostics(),
    /** Frame totals reported by the adapter for each channel heard so far. */
    val channelFrames: Map<Int, Long> = emptyMap(),
    val devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> = emptyList(),
    /** Channels this capture listens on, and when it started
     * (`elapsedRealtime`), for the adapter workspace's status strip. */
    val channels: List<Int> = emptyList(),
    val startedAtElapsedMs: Long = 0,
)

/** Counts each new radio reading from a pinned transmitter exactly once. */
data class MonitorGeoDiagnostics(
    val radioReadings: Int = 0,
    val favoriteDevicesHeard: Int = 0,
    val favoriteWithoutRssi: Int = 0,
    val missingRssi: Int = 0,
    val missingChannel: Int = 0,
    val noMatchingFix: Int = 0,
    val sampleCap: Int = 0,
)

data class ScanUiState(
    val isScanning: Boolean = false,
    val isContinuous: Boolean = false,
    val currentPhase: ScanPhase? = null,
    /** Radios still running in the current pass (they run together). */
    val activeRadios: Set<ScanPhase> = emptySet(),
    val wifiCount: Int? = null,
    val cellularCount: Int? = null,
    val bleCount: Int? = null,
    val satelliteCount: Int? = null,
    val completedScanCount: Int = 0,
    val wifiUnavailableReason: String? = null,
    val cellularUnavailableReason: String? = null,
    val bleUnavailableReason: String? = null,
    val gnssUnavailableReason: String? = null,
    val lanUnavailableReason: String? = null,
    // Every IPv4 interface the phone has and whether a sweep could use it.
    // Shown on the LAN screen so a refusal can be checked rather than taken
    // on trust — the /32 tunnel bug looked exactly like a correct answer.
    val lanNetworkReport: List<String> = emptyList(),
    /** What the last/current sweep covers, e.g. "192.168.1.1 – 192.168.1.254 · 254 addresses". */
    val lanTargetLabel: String? = null,
    val isLanScanning: Boolean = false,
    val lanChecked: Int = 0,
    val lanTotal: Int = 0,
    val lanDeviceCount: Int? = null,
    // Grows live as devices are confirmed during a LAN scan, and stays
    // populated afterwards as the "last results" until the next scan starts.
    val lanDevices: List<LanObservationDto> = emptyList(),
    /** The router's Wi-Fi radio (BSSID) from the last sweep; shown, not uploaded. */
    val lanGateway: com.whyfi.app.lan.LanScanner.GatewayRadio? = null,
    /** Public IP from ident.me after a pass (see PublicIp), and when. */
    val publicIp: String? = null,
    /** Fieldwatch catalog labels by upper-case MAC/BSSID (see CatalogLabels). */
    val catalogLabels: Map<String, String> = emptyMap(),
    val publicIpAtMs: Long = 0L,
    /** Where each pass this session was taken, for the scan report's map. */
    val passPositions: List<TrackPoint> = emptyList(),
    /** The scan report written when scanning stopped, and the pass count it covers. */
    val scanReportPath: String? = null,
    val scanReportPasses: Int = 0,
    /** The radios the last pass asked for ("What to scan"): the scan report
     * covers only these (user, 2026-11-09). */
    val scanOptions: ScanOptions = ScanOptions(),
    /** Where and when the last sweep finished, for the LAN report. */
    val lanLatitude: Double? = null,
    val lanLongitude: Double? = null,
    val lanAccuracyM: Float? = null,
    val lanFinishedAtMs: Long = 0L,
    // The two most recent completed passes, kept so the UI can show what was
    // actually found rather than only how many — and diff the two (see
    // ScanDiff). Two is the whole buffer: it's what "new since last time"
    // needs, and it keeps this bounded. Anything longer belongs on the
    // backend, which already has every pass ever uploaded.
    val latestPass: ScanSessionUploadRequest? = null,
    val previousPass: ScanSessionUploadRequest? = null,
    val survey: SurveyStats = SurveyStats(),
    // Null when adaptive cadence is off, so the UI can tell "not using this"
    // from "using it and currently stationary".
    val motionState: MotionState? = null,
    val effectiveIntervalSeconds: Int? = null,
    val motionSource: String = "",
)

/** What the continuous loop should be doing right now. Held in a StateFlow
 * the loop re-reads each iteration so the web UI can retune a running scan
 * without restarting it. */
data class ContinuousConfig(
    val options: ScanOptions = ScanOptions(),
    val intervalMs: Long = 30_000L,
)

/**
 * Hosts scan execution independently of any Activity/Composable lifecycle.
 * Without this, an in-progress scan used to get cancelled the instant the
 * user switched tabs (`WhyfiApp`'s `when(selectedTab)` removes ScanScreen
 * from composition, cancelling its `rememberCoroutineScope()`-launched
 * job) or backgrounded the app. The persistent notification while a scan
 * runs is also the honest way to do this — radio scanning that keeps
 * going after you've switched away should be visible, not silent.
 *
 * Both *started* (survives its clients unbinding — see [start]) and
 * *bound* (gives the UI a live [uiState] to observe while visible). See
 * ScanScreen/LanScreen for the client side.
 */
class ScanForegroundService : Service() {

    private val binder = LocalBinder()
    private lateinit var scanCoordinator: ScanCoordinator
    private lateinit var settingsRepository: SettingsRepository
    private val serviceScope = CoroutineScope(SupervisorJob())
    private var continuousJob: Job? = null
    private var remoteAgentJob: Job? = null
    private val _monitorState = MutableStateFlow(MonitorUiState())
    val monitorState: StateFlow<MonitorUiState> = _monitorState.asStateFlow()

    // The phone's path this session (see LocationTrack): fed by every fix
    // LiveLocationTracker receives, i.e. while scanning, sweeping, or while
    // the GPS screen is open (watchLocation).
    private val _locationTrack = MutableStateFlow<List<TrackPoint>>(emptyList())
    val locationTrack: StateFlow<List<TrackPoint>> = _locationTrack.asStateFlow()

    /** Keeps location updates on while a screen shows the live position.
     * Reference-counted with scans through LiveLocationTracker. */
    fun watchLocation(on: Boolean) {
        if (on) scanCoordinator.liveLocation.acquire() else scanCoordinator.liveLocation.release()
    }

    fun clearLocationTrack() { _locationTrack.value = emptyList() }
    private var monitorMission: MonitorMission.Files? = null

    /** Read at the top of every continuous iteration rather than captured
     * when the loop starts, so changing the interval or radio selection
     * mid-run takes effect without cancelling a pass that's in flight. */
    private val continuousConfig = MutableStateFlow(ContinuousConfig())

    /** Set to ask the continuous loop to finish its current pass and then
     * stop, instead of being cancelled where it stands. See [stopContinuous]. */
    @Volatile
    private var gracefulStopRequested = false

    // Echoed back to the backend so the web UI can tell "the phone hasn't
    // picked this up yet" from "it picked it up and still isn't scanning".
    @Volatile
    private var appliedPolicyRevision = 0

    @Volatile
    private var appliedScanNowNonce = 0

    @Volatile
    private var appliedResetCountersNonce = 0

    /** remote_scan_enabled at the last heartbeat; null until the first one
     * after remote control is armed. See [RemoteScanAction]. */
    @Volatile
    private var lastRemoteScanEnabled: Boolean? = null

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** Running totals for the Dashboard. Lives here rather than in a
     * Composable so it survives tab switches, and dies with the service so
     * "since the scanner started" stays true. */
    private val surveyTally = SurveyTally()

    /** Set when the motion detector sees movement resume, to cut short a sleep
     * that was sized for a stationary phone. Without this, walking away from a
     * desk would go unnoticed for the rest of a 10-minute interval — which is
     * most of the ground you'd want mapped. */
    @Volatile
    private var wakeFromSleepRequested = false

    private val motionDetector by lazy {
        MotionDetector(
            context = applicationContext,
            scope = serviceScope,
            onMovementResumed = { wakeFromSleepRequested = true },
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScanForegroundService = this@ScanForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("ScanForegroundService", "onCreate")
        scanCoordinator = ScanCoordinator(applicationContext)
        scanCoordinator.liveLocation.onLocation = { location ->
            val point = TrackPoint(
                atMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                lat = location.latitude, lng = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                provider = location.provider ?: "unknown",
            )
            _locationTrack.update { LocationTrack.append(it, point) }
        }
        settingsRepository = SettingsRepository(applicationContext)
        // Nothing is recording yet: close what a killed process left open.
        serviceScope.launch(Dispatchers.IO) { runCatching { scanCoordinator.recordings.closeStale(emptyList()) } }
        createNotificationChannel()
        // Restore the last completed pass so the Dashboard isn't blank after a
        // force-stop or reboot. Only latestPass + completedScanCount are
        // restored; SurveyStats and previousPass stay session-scoped.
        val local = LastScanStore.load(applicationContext)
        android.util.Log.i("ScanForegroundService", "local=$local isConfigured=${settingsRepository.isConfigured} url=${settingsRepository.backendUrl}")
        if (local != null) {
            val (count, pass) = local
            _uiState.update {
                it.copy(latestPass = pass, completedScanCount = count)
            }
        } else if (settingsRepository.isConfigured) {
            // No local pass and the app is configured — best-effort fetch of
            // the latest backend scan so the Dashboard isn't empty on a fresh
            // install of a device that has already uploaded scans. The
            // reconstructed pass is display-only (never re-uploaded); see
            // LatestScanFetcher. Silently fails on any error.
            serviceScope.launch {
                android.util.Log.i("ScanForegroundService", "launching LatestScanFetcher")
                runCatching {
                    LatestScanFetcher.fetch(
                        backendUrl = settingsRepository.backendUrl!!,
                        token = settingsRepository.sensorToken!!,
                    )
                }.onSuccess { pass ->
                    android.util.Log.i("ScanForegroundService", "LatestScanFetcher returned: $pass")
                    if (pass != null) {
                        _uiState.update {
                            it.copy(latestPass = pass, completedScanCount = 1)
                        }
                    }
                }.onFailure { e ->
                    android.util.Log.w("ScanForegroundService", "LatestScanFetcher failed", e)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On API 34 startForeground throws if a prerequisite permission for a
        // declared foregroundServiceType has been revoked since the last run —
        // which is reachable here, because a sticky restart can happen long
        // after the user last opened the app.
        val started = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(idleNotificationText()))
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (settingsRepository.remoteControlEnabled) {
            startRemoteAgentIfNeeded()
            // Worth coming back after a process kill, since the whole point of
            // remote control is an unattended device. (Not after a reboot,
            // though — nothing restarts us there by design; see the manifest.)
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    // --- Remote control ---------------------------------------------------

    /** Arms or disarms obeying the backend. Only ever called from the UI:
     * Android requires the foreground service to be started from the
     * foreground, and a persistent notification is worth an explicit opt-in. */
    fun setRemoteControlEnabled(enabled: Boolean) {
        settingsRepository.remoteControlEnabled = enabled
        lastRemoteScanEnabled = null
        if (enabled) {
            startRemoteAgentIfNeeded()
            updateNotification(idleNotificationText())
        } else {
            remoteAgentJob?.cancel()
            remoteAgentJob = null
            // Whoever is holding the phone wins: disarming locally also stops
            // whatever the backend had it doing.
            stopContinuous()
        }
    }

    fun isRemoteControlEnabled(): Boolean = settingsRepository.remoteControlEnabled

    /** Zeroes the per-session tallies (completed passes, the last pass's
     * per-radio counts, the retained passes and the Dashboard totals).
     *
     * "Session" used to mean "since the service last started", which was fine
     * when the service died whenever it went idle. Under remote control it
     * never dies, so the count would otherwise climb forever with no way to
     * zero it short of force-stopping the app.
     *
     * Reachable from the Dashboard's Reset button as well as the remote
     * reset-counters nonce — both mean the same thing, so they share a path
     * rather than clearing overlapping subsets of the state. */
    fun resetSessionCounters() {
        surveyTally.reset()
        _uiState.update {
            it.copy(
                completedScanCount = 0,
                passPositions = emptyList(),
                wifiCount = null,
                cellularCount = null,
                bleCount = null,
                satelliteCount = null,
                latestPass = null,
                previousPass = null,
                survey = surveyTally.snapshot(),
            )
        }
        LastScanStore.clear(applicationContext)
    }

    private fun startRemoteAgentIfNeeded() {
        if (remoteAgentJob?.isActive == true) return
        val agent = RemoteControlAgent(applicationContext, RemoteCallbacks())
        remoteAgentJob = serviceScope.launch { agent.run() }
    }

    private inner class RemoteCallbacks : RemoteControlAgent.Callbacks {
        override suspend fun currentReport(): SensorHeartbeatRequest {
            val state = _uiState.value
            val dao = WhyfiDatabase.getInstance(applicationContext).pendingScanDao()
            return SensorHeartbeatRequest(
                reportedIsContinuous = state.isContinuous,
                reportedIsScanning = state.isScanning,
                reportedPhase = state.currentPhase?.name ?: "",
                reportedCompletedScans = state.completedScanCount,
                reportedWifiUnavailableReason = state.wifiUnavailableReason ?: "",
                reportedCellularUnavailableReason = state.cellularUnavailableReason ?: "",
                reportedBleUnavailableReason = state.bleUnavailableReason ?: "",
                reportedPermissionsGranted = PermissionHelper.hasAllRequiredPermissions(applicationContext),
                reportedLocationServicesEnabled = PermissionHelper.isLocationServicesEnabled(applicationContext),
                reportedPendingUploads = dao.count(),
                reportedOutboxBytes = dao.totalBytes() ?: 0L,
                reportedOutboxQuotaMb = settingsRepository.outboxQuotaMb,
                reportedBatteryPercent = batteryPercent(),
                reportedAppVersion = BuildConfig.VERSION_NAME,
                reportedPolicyRevision = appliedPolicyRevision,
                reportedScanNowNonce = appliedScanNowNonce,
                reportedResetCountersNonce = appliedResetCountersNonce,
                reportedMotionState = state.motionState?.name ?: "",
                reportedEffectiveIntervalSeconds = state.effectiveIntervalSeconds,
            )
        }

        override suspend fun onPolicy(policy: ScanPolicyResponse) {
            refreshAvailability()
            // The policy is the source of truth while armed, so these land in
            // local settings too — otherwise the phone's Settings screen and
            // the web UI would show different numbers for the same behaviour.
            settingsRepository.applyRemoteAdaptiveSettings(
                enabled = policy.adaptiveScanEnabled,
                stationary = policy.stationaryIntervalSeconds,
                walking = policy.walkingIntervalSeconds,
                driving = policy.drivingIntervalSeconds,
            )
            val options = ScanOptions(
                includeWifi = policy.includeWifi,
                includeCellular = policy.includeCellular,
                includeBle = policy.includeBle,
                includeGnss = policy.includeGnss,
            )
            val intervalMs = policy.scanIntervalSeconds.toLong() * 1000L

            // Acts on changes of the setting only, so a loop started or
            // stopped on the phone isn't overruled by the next heartbeat.
            val looping = _uiState.value.isContinuous
            when (RemoteScanAction.decide(lastRemoteScanEnabled, policy.remoteScanEnabled, looping)) {
                // startContinuous also swaps the config of a running loop.
                RemoteScanAction.START, RemoteScanAction.UPDATE -> startContinuous(options, intervalMs)
                RemoteScanAction.STOP -> stopContinuous(graceful = true)
                RemoteScanAction.NONE -> Unit
            }
            lastRemoteScanEnabled = policy.remoteScanEnabled

            // A one-off pass, only when not already looping (the loop covers it).
            if (policy.scanNowNonce != appliedScanNowNonce) {
                appliedScanNowNonce = policy.scanNowNonce
                if (!policy.remoteScanEnabled) scanOnce(options)
            }

            if (policy.resetCountersNonce != appliedResetCountersNonce) {
                appliedResetCountersNonce = policy.resetCountersNonce
                resetSessionCounters()
            }

            appliedPolicyRevision = policy.policyRevision
        }

        override suspend fun onAuthRejected() {
            setRemoteControlEnabled(false)
            updateNotification("Remote control off — backend rejected this device's token")
        }
    }

    private fun batteryPercent(): Int? {
        val manager = getSystemService(BatteryManager::class.java) ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    fun refreshAvailability() {
        _uiState.update {
            it.copy(
                wifiUnavailableReason = scanCoordinator.wifiScanManager.unavailableReason(),
                cellularUnavailableReason = scanCoordinator.cellularManager.unavailableReason(),
                bleUnavailableReason = scanCoordinator.bleDeviceScanner.unavailableReason(),
                gnssUnavailableReason = scanCoordinator.gnssStatusManager.unavailableReason(),
            )
        }
    }

    fun canScanNow(includeWifi: Boolean): Boolean =
        !includeWifi || scanCoordinator.wifiScanManager.throttle.canScanNow()

    fun scanOnce(options: ScanOptions) {
        if (_uiState.value.isScanning || _uiState.value.isContinuous) return
        serviceScope.launch {
            beginRecording()
            try {
                runOnePass(options)
            } finally {
                endRecording()
            }
            craftScanReport()
            stopIfNothingKeepsUsAlive()
        }
    }

    /** Starts the continuous loop, or — if it's already running — updates the
     * interval and radio selection in place.
     *
     * Updating in place rather than restarting matters for remote control:
     * changing the cadence from the web UI would otherwise cancel whatever
     * pass happened to be in flight, silently losing it (the upload is the
     * last thing a pass does). */
    fun startContinuous(options: ScanOptions, intervalMs: Long) {
        continuousConfig.value = ContinuousConfig(options, intervalMs)
        if (_uiState.value.isContinuous) return

        gracefulStopRequested = false
        _uiState.update { it.copy(isContinuous = true) }
        if (settingsRepository.adaptiveScanEnabled) motionDetector.start()
        // Keep live location running for the whole loop, not just during
        // passes: GPS stays warm between passes, so each pass starts with a
        // fresh fix instead of a cold start (see LiveLocationTracker).
        scanCoordinator.liveLocation.acquire()
        continuousJob = serviceScope.launch {
            beginRecording()
            try {
                while (isActive && !gracefulStopRequested) {
                    val config = continuousConfig.value
                    if (canScanNow(config.options.includeWifi)) {
                        runOnePass(config.options)
                        interruptibleDelay(nextIntervalMs(config))
                    } else {
                        interruptibleDelay(throttleBackoffMs())
                    }
                }
            } finally {
                // Runs on cancellation too, so both stop paths converge here.
                continuousJob = null
                endRecording()
                scanCoordinator.liveLocation.release()
                motionDetector.stop()
                _uiState.update {
                    it.copy(
                        isContinuous = false,
                        currentPhase = null,
                        motionState = null,
                        effectiveIntervalSeconds = null,
                    )
                }
                craftScanReport()
                stopIfNothingKeepsUsAlive()
            }
        }
    }

    /**
     * @param graceful finish the pass that's in flight before stopping.
     *
     * The local button stops immediately — a human is watching and pressed
     * it deliberately. A remote stop is graceful, because cancelling
     * mid-pass discards that pass's data and nobody would be there to notice.
     */
    fun stopContinuous(graceful: Boolean = false) {
        if (graceful) {
            gracefulStopRequested = true
            return
        }
        continuousJob?.cancel()
    }

    /** Waits in short slices so a graceful stop doesn't have to sit through a
     * full scan interval (which can be minutes) before taking effect. */
    private suspend fun interruptibleDelay(totalMs: Long) {
        wakeFromSleepRequested = false
        var remaining = totalMs
        while (remaining > 0 && !gracefulStopRequested && !wakeFromSleepRequested) {
            val slice = minOf(remaining, GRACEFUL_STOP_CHECK_MS)
            delay(slice)
            remaining -= slice
        }
        wakeFromSleepRequested = false
    }

    /**
     * How long to wait before the next pass.
     *
     * With adaptive cadence off this is just the configured interval. With it
     * on, the motion state picks between three — a phone on a desk re-scans
     * the same airwaves, while a phone in a car covers new ground every
     * second, so the two deserve very different cadences.
     */
    private fun nextIntervalMs(config: ContinuousConfig): Long {
        if (!settingsRepository.adaptiveScanEnabled) {
            _uiState.update {
                it.copy(
                    motionState = null,
                    effectiveIntervalSeconds = (config.intervalMs / 1000L).toInt(),
                    motionSource = "",
                )
            }
            return config.intervalMs
        }

        // Dwell timers only elapse with the clock, so ask for a fresh verdict
        // rather than using whatever the last sensor event left behind.
        motionDetector.refresh()
        val state = motionDetector.state.value
        val seconds = when (state) {
            MotionState.STATIONARY -> settingsRepository.stationaryIntervalSeconds
            MotionState.WALKING -> settingsRepository.walkingIntervalSeconds
            MotionState.DRIVING -> settingsRepository.drivingIntervalSeconds
        }
        _uiState.update {
            it.copy(
                motionState = state,
                effectiveIntervalSeconds = seconds,
                motionSource = motionDetector.describeSource(),
            )
        }
        updateNotification("${state.label} — next scan in ${formatInterval(seconds)}")
        return seconds.toLong() * 1000L
    }

    /** How long until Android's WiFi scan throttle lets us go again.
     * Beats a fixed retry: at intervals near the throttle floor a blind 2s
     * poll wakes the device repeatedly for nothing. */
    private fun throttleBackoffMs(): Long {
        val waitMs = scanCoordinator.wifiScanManager.throttle.nextAllowedScanAtMs() - System.currentTimeMillis()
        return waitMs.coerceIn(500L, 30_000L)
    }

    fun scanLan(target: LanTarget = LanTarget.Auto) {
        if (_uiState.value.isLanScanning) return

        // Ask before sweeping. A sweep that can't run produced an empty device
        // list that was indistinguishable from "nobody answered" — and still
        // uploaded a zero-observation session, which then consumed a slot in
        // the "last N scans" filter for no information at all.
        val report = scanCoordinator.lanScanner.inspectNetworks().map { it.describe() }
        val unavailable = scanCoordinator.lanScanner.unavailableReason(target)
        val targetLabel = scanCoordinator.lanScanner.describeTarget(target)
        if (unavailable != null) {
            _uiState.update {
                it.copy(
                    isLanScanning = false,
                    lanUnavailableReason = unavailable,
                    lanNetworkReport = report,
                    lanDeviceCount = null,
                    lanDevices = emptyList(),
                )
            }
            stopIfNothingKeepsUsAlive()
            return
        }

        _uiState.update {
            it.copy(
                isLanScanning = true, lanChecked = 0, lanTotal = 0,
                lanDeviceCount = null, lanDevices = emptyList(), lanUnavailableReason = null,
                lanNetworkReport = report, lanTargetLabel = targetLabel,
            )
        }
        updateNotification("Scanning LAN…")
        lanJob = serviceScope.launch {
            try {
                val result = scanCoordinator.runLanScan(
                    target = target,
                    // Inside the running scan's recording, else its own.
                    recordingId = recordingId,
                    onProgress = { checked, total -> _uiState.update { it.copy(lanChecked = checked, lanTotal = total) } },
                    onDeviceFound = { device -> _uiState.update { it.copy(lanDevices = it.lanDevices + device) } },
                )
                _uiState.update {
                    it.copy(
                        isLanScanning = false, lanDeviceCount = result.lanObservations.size,
                        // The final list carries the names found after the sweep.
                        lanDevices = result.lanObservations,
                        lanGateway = scanCoordinator.lanScanner.gatewayRadio(),
                        lanLatitude = result.latitude, lanLongitude = result.longitude,
                        lanAccuracyM = result.locationAccuracyMeters, lanFinishedAtMs = System.currentTimeMillis(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled from the LAN screen: keep what was found so far
                // (it's still shown and can go into a report) but upload
                // nothing — a partial sweep would read as an empty network.
                _uiState.update {
                    it.copy(isLanScanning = false, lanDeviceCount = it.lanDevices.size,
                        lanFinishedAtMs = System.currentTimeMillis(),
                        lanUnavailableReason = "Cancelled after ${it.lanChecked} of ${it.lanTotal} addresses.")
                }
                throw e
            } finally {
                lanJob = null
                stopIfNothingKeepsUsAlive()
            }
        }
    }

    @Volatile
    private var lanJob: kotlinx.coroutines.Job? = null

    /** Stops a running LAN sweep; devices found so far stay on screen. */
    fun cancelLan() {
        lanJob?.cancel()
    }

    /** One bounded, user-started receive session. The service owns and closes
     * the USB connection so changing tabs cannot end the capture. */
    fun startMonitor(connection: UsbDeviceConnection, device: String, channels: IntArray, durationMs: Int, recordPcap: Boolean) {
        if (_monitorState.value.running || channels.isEmpty() ||
            channels.any { it !in UsbMonitorNative.DEFAULT_CHANNELS } || durationMs !in 1..600_000
        ) {
            connection.close()
            return
        }
        if (UsbMonitorNative.loadError != null) {
            connection.close()
            _monitorState.value = MonitorUiState(status = "Native library unavailable: ${UsbMonitorNative.loadError}")
            return
        }
        val resumedMission = MonitorMission.resume(applicationContext, settingsRepository.activeMonitorMissionId, recordPcap)
        val mission = resumedMission ?: MonitorMission.create(applicationContext, recordPcap)
        settingsRepository.activeMonitorMissionId = mission.directory.name
        monitorMission = mission
        MonitorMission.append(mission, "monitor_started", "adapter=${device} channels=${channels.joinToString()} pcap=${recordPcap}")
        if (resumedMission != null) MonitorMission.append(mission, "mission_resumed")
        settingsRepository.monitorFavoriteMacs.sorted().forEach {
            MonitorMission.append(mission, if (resumedMission == null) "mission_target" else "mission_resumed_target", it)
        }
        settingsRepository.monitorHiddenApMacs.sorted().forEach {
            MonitorMission.append(mission, "ap_hidden", "$it:true")
        }
        _monitorState.value = MonitorUiState(running = true, status = "Starting…", device = device,
            pcapPath = mission.pcap?.absolutePath.takeIf { recordPcap }, missionLogPath = mission.log.absolutePath,
            channels = channels.toList(), startedAtElapsedMs = SystemClock.elapsedRealtime())
        updateNotification(AdapterStatus.notification(channels.toList(), 0))
        var lastNotificationMs = SystemClock.elapsedRealtime()
        serviceScope.launch {
            val initialFixes = LocationSnapshot.lastKnownCandidates(applicationContext).filter { fix ->
                SystemClock.elapsedRealtime() - fix.elapsedRealtimeNanos / 1_000_000 <= MonitorLocationPolicy.MAX_FIX_AGE_MS
            }
            val locations = AtomicReference(initialFixes.associateBy { it.provider ?: "unknown" })
            val locationHistory = AtomicReference(initialFixes)
            val onFix: (Location) -> Unit = { fix ->
                locations.updateAndGet { previous ->
                    val provider = fix.provider ?: "unknown"
                    val old = previous[provider]
                    if (old == null || fix.elapsedRealtimeNanos >= old.elapsedRealtimeNanos)
                        previous + (provider to Location(fix)) else previous
                }
                locationHistory.updateAndGet { previous ->
                    val last = previous.lastOrNull { it.provider == fix.provider }
                    if (last == null || fix.elapsedRealtimeNanos > last.elapsedRealtimeNanos)
                        (previous + Location(fix)).takeLast(512) else previous
                }
            }
            var locationSubscription = LocationSnapshot.requestUpdates(applicationContext, minDistanceM = 0f,
                includeFused = true, onLocation = onFix)
            val seenSampleTimes = mutableMapOf<String, Long>()
            val countedSampleTimes = mutableMapOf<String, Long>()
            val savedFixTimes = mutableMapOf<String, Long>()
            val awaitingFix = mutableMapOf<String, Long>()
            val knownLabels = mutableMapOf<String, String>()
            val snapshotMutex = Mutex()
            var lastSavedSnapshotAt = 0L
            var geoSampleCount = 0
            var receiverFixCount = 0
            var geoDiagnostics = MonitorGeoDiagnostics()
            val sampledMacs = mutableSetOf<String>()
            val knownApMacs = mutableSetOf<String>()
            var lastInsightBootMs = SystemClock.elapsedRealtime()
            var insightAnchor: MonitorLocationCandidate? = null
            var movePrompted = false
            val vendorTable = withContext(Dispatchers.IO) {
                runCatching { assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
            }
            suspend fun snapshot(forceSave: Boolean = false) = snapshotMutex.withLock {
                if (locationSubscription == null) {
                    locationSubscription = LocationSnapshot.requestUpdates(applicationContext, minDistanceM = 0f,
                        includeFused = true, onLocation = onFix)
                }
                val deviceTsv = UsbMonitorNative.devices()
                val raw = withContext(Dispatchers.Default) {
                    SniffedDevices.parse(deviceTsv).map { d ->
                        d to SniffedDevices.guess(d) { vendorTable?.vendorOf(it) }
                    }
                }
                val nowMs = System.currentTimeMillis()
                if (forceSave || nowMs - lastSavedSnapshotAt >= 5_000) {
                    withContext(Dispatchers.IO) { MonitorMission.appendDeviceSnapshot(mission, deviceTsv) }
                    lastSavedSnapshotAt = nowMs
                }
                fun candidate(fix: Location) = MonitorLocationCandidate(fix.provider ?: "unknown",
                    fix.elapsedRealtimeNanos / 1_000_000, fix.latitude, fix.longitude,
                    if (fix.hasAccuracy() && fix.accuracy.isFinite() && fix.accuracy >= 0f)
                        fix.accuracy.toDouble() else 1_000.0,
                    if (fix.hasSpeed() && fix.speed.isFinite() && fix.speed >= 0f)
                        fix.speed.toDouble() else 15.0)
                val history = locationHistory.get()
                val freshReceiverFixes = history.mapNotNull { fix ->
                    val provider = fix.provider ?: "unknown"
                    val bootMs = fix.elapsedRealtimeNanos / 1_000_000
                    if (bootMs <= (savedFixTimes[provider] ?: 0L)) return@mapNotNull null
                    val selected = candidate(fix)
                    if (!selected.lat.isFinite() || !selected.lng.isFinite() ||
                        selected.lat !in -90.0..90.0 || selected.lng !in -180.0..180.0) return@mapNotNull null
                    val wallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime() + bootMs
                    MonitorReceiverFix(wallMs, bootMs, provider, selected.lat, selected.lng,
                        selected.accuracyM, selected.speedMps)
                }
                if (freshReceiverFixes.isNotEmpty()) {
                    withContext(Dispatchers.IO) { MonitorMission.appendReceiverFixes(mission, freshReceiverFixes) }
                    receiverFixCount += freshReceiverFixes.size
                    freshReceiverFixes.forEach { fix -> savedFixTimes[fix.provider] =
                        maxOf(savedFixTimes[fix.provider] ?: 0L, fix.bootMs) }
                }
                val fixes = history.map(::candidate)
                val latestFixes = locations.get().values.map { fix ->
                    MonitorLocationCandidate(fix.provider ?: "unknown", fix.elapsedRealtimeNanos / 1_000_000,
                        fix.latitude, fix.longitude,
                        if (fix.hasAccuracy() && fix.accuracy.isFinite() && fix.accuracy >= 0f)
                            fix.accuracy.toDouble() else 1_000.0,
                        if (fix.hasSpeed() && fix.speed.isFinite() && fix.speed >= 0f)
                            fix.speed.toDouble() else 15.0)
                }
                val favorites = settingsRepository.monitorFavoriteMacs
                val newLabels = raw.filter { it.first.mac in favorites }.mapNotNull { (device, guess) ->
                    val name = MonitorMission.deviceName(device, guess)
                    if (knownLabels[device.mac] == name) null else {
                        knownLabels[device.mac] = name
                        device.mac to name
                    }
                }
                if (newLabels.isNotEmpty()) withContext(Dispatchers.IO) {
                    newLabels.forEach { (mac, name) -> MonitorMission.appendIdentity(mission, mac, name) }
                }
                val heardFavorites = raw.map { it.first }.filter { it.mac in favorites }
                geoDiagnostics = geoDiagnostics.copy(
                    favoriteDevicesHeard = heardFavorites.size,
                    favoriteWithoutRssi = heardFavorites.count { it.lastRssiBootMs == null },
                )
                var acceptedThisSnapshot = 0
                val newSamples = raw.mapNotNull { (device, _) ->
                    val bootMs = device.lastRssiBootMs ?: return@mapNotNull null
                    if (device.mac !in favorites || bootMs <= (seenSampleTimes[device.mac] ?: 0L)) return@mapNotNull null
                    if (bootMs > (countedSampleTimes[device.mac] ?: 0L)) {
                        countedSampleTimes[device.mac] = bootMs
                        geoDiagnostics = geoDiagnostics.copy(radioReadings = geoDiagnostics.radioReadings + 1)
                    }
                    val rssi = device.lastRssi ?: run {
                        geoDiagnostics = geoDiagnostics.copy(missingRssi = geoDiagnostics.missingRssi + 1)
                        return@mapNotNull null
                    }
                    val channel = device.lastRssiChannel?.takeIf { it > 0 } ?: run {
                        geoDiagnostics = geoDiagnostics.copy(missingChannel = geoDiagnostics.missingChannel + 1)
                        return@mapNotNull null
                    }
                    if (geoSampleCount + acceptedThisSnapshot >= 10_000) {
                        geoDiagnostics = geoDiagnostics.copy(sampleCap = geoDiagnostics.sampleCap + 1)
                        return@mapNotNull null
                    }
                    val selectedFix = MonitorLocationPolicy.select(fixes, bootMs) ?: run {
                        awaitingFix[device.mac] = bootMs
                        return@mapNotNull null
                    }
                    seenSampleTimes[device.mac] = bootMs
                    awaitingFix.remove(device.mac)
                    val wallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime() + bootMs
                    acceptedThisSnapshot++
                    MonitorGeoSample(device.mac, wallMs, selectedFix.fix.lat, selectedFix.fix.lng,
                        selectedFix.fix.accuracyM, rssi, channel, selectedFix.fix.provider,
                        selectedFix.ageMs, selectedFix.fix.speedMps)
                }
                if (newSamples.isNotEmpty()) {
                    withContext(Dispatchers.IO) { MonitorMission.appendSamples(mission, newSamples) }
                    geoSampleCount += newSamples.size
                    sampledMacs += newSamples.map { it.mac }
                }
                geoDiagnostics = geoDiagnostics.copy(noMatchingFix = awaitingFix.size)
                val currentFix = MonitorLocationPolicy.select(latestFixes, SystemClock.elapsedRealtime())
                val newAp = raw.count { it.first.isAp && knownApMacs.add(it.first.mac) } > 0
                val currentLocation = currentFix?.fix
                val moved = MonitorMovePrompt.moved(insightAnchor, currentLocation)
                if (newAp || moved) {
                    lastInsightBootMs = SystemClock.elapsedRealtime()
                    insightAnchor = currentLocation ?: insightAnchor
                    if (movePrompted) getSystemService(NotificationManager::class.java)
                        ?.cancel(MONITOR_MOVE_NOTIFICATION_ID)
                    movePrompted = false
                } else if (insightAnchor == null && currentLocation != null) {
                    insightAnchor = currentLocation
                }
                val suggestMove = !movePrompted && MonitorMovePrompt.shouldPrompt(
                    SystemClock.elapsedRealtime(), lastInsightBootMs, insightAnchor, currentLocation)
                if (suggestMove) {
                    movePrompted = true
                    showMonitorMoveSuggestion()
                    MonitorMission.append(mission, "move_suggested", "No new AP or distinct receiver position for 2 minutes")
                }
                val frames = UsbMonitorNative.stats().getOrElse(UsbMonitorNative.Stat.TOTAL.ordinal) { 0 }
                val nativeStats = UsbMonitorNative.stats()
                val channelFrames = UsbMonitorNative.perChannel().asList().chunked(2)
                    .filter { it.size == 2 }
                    .associate { (channel, count) -> channel.toInt() to count }
                _monitorState.update {
                    it.copy(frames = frames, channelFrames = channelFrames, devices = raw,
                        pcapBytes = nativeStats.getOrElse(UsbMonitorNative.Stat.PCAP_BYTES.ordinal) { 0 },
                        pcapTruncated = nativeStats.getOrElse(UsbMonitorNative.Stat.PCAP_TRUNCATED.ordinal) { 0 } != 0L,
                        eapolKeyFrames = nativeStats.getOrElse(UsbMonitorNative.Stat.EAPOL_KEY_FRAMES.ordinal) { 0 },
                        geoSamples = geoSampleCount,
                        receiverFixCount = receiverFixCount,
                        moveSuggested = movePrompted,
                        snapshotRevisionMs = lastSavedSnapshotAt,
                        locationFixes = latestFixes,
                        locationUpdatesRegistered = locationSubscription != null,
                        geoDiagnostics = geoDiagnostics,
                        geoStatus = when {
                            favorites.isEmpty() -> "Radio snapshots and receiver fixes are saved for AP and client estimates"
                            currentFix == null -> "Waiting for a recent GPS/network/fused fix"
                            geoSampleCount >= 10_000 -> "Location sample cap reached"
                            else -> "${currentFix.fix.provider} location · ±${currentFix.effectiveAccuracyM.toInt()} m"
                        },
                        status = if (it.status == "Stopping…") it.status else "Receiving…")
                }
                // Says "capture", not "scan": this is the adapter, not a pass
                // of the phone's own radios. Throttled; Android rate-limits
                // notification updates anyway.
                if (SystemClock.elapsedRealtime() - lastNotificationMs >= MONITOR_NOTIFICATION_INTERVAL_MS) {
                    lastNotificationMs = SystemClock.elapsedRealtime()
                    updateNotification(AdapterStatus.notification(channels.toList(), raw.size))
                }
            }
            val poller = launch {
                while (isActive) {
                    delay(2_000)
                    snapshot()
                }
            }
            var result = ""
            try {
                result = withContext(Dispatchers.IO) {
                    UsbMonitorNative.run(
                        connection.fileDescriptor, cacheDir.absolutePath, "",
                        mission.pcap?.absolutePath.takeIf { recordPcap }.orEmpty(), channels, 400, durationMs,
                    )
                }
                snapshot(forceSave = true)
            } catch (e: Exception) {
                result = e.message ?: e.javaClass.simpleName
            } finally {
                poller.cancelAndJoin()
                (getSystemService(NotificationManager::class.java))?.cancel(MONITOR_MOVE_NOTIFICATION_ID)
                locationSubscription?.close()
                connection.close()
                val finished = _monitorState.value.copy(running = false, status = if (result.isEmpty()) "Finished" else "Failed: $result")
                _monitorState.value = finished.copy(running = true, status = "Saving capture…")
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        settingsRepository.monitorFavoriteMacs.sorted().forEach { MonitorMission.append(mission, "mission_target", it) }
                        sampledMacs.sorted().forEach { MonitorMission.append(mission, "mission_target", it) }
                        MonitorMission.append(mission, "monitor_finished", "status=${finished.status} frames=${finished.frames}")
                        MonitorMission.saveCaptureSummary(mission, finished)
                    }
                }
                _monitorState.value = finished.copy(
                    reportPath = null,
                    status = if (saved.isFailure) "${finished.status}; capture statistics could not be saved" else finished.status,
                )
                monitorMission = null
                stopIfNothingKeepsUsAlive()
            }
        }
    }

    fun stopMonitor() {
        if (_monitorState.value.running) {
            _monitorState.update { it.copy(status = "Stopping…") }
            UsbMonitorNative.stop()
        }
    }

    fun clearFinishedMonitor() {
        if (!_monitorState.value.running) _monitorState.value = MonitorUiState()
    }

    /** Records an operator decision for later mission replay; never uploads it here. */
    fun logMonitorEvent(event: String, details: String = "") {
        monitorMission?.let { MonitorMission.append(it, event, details) }
    }

    /** The recording passes go into while a scan runs (see RecordingStore). */
    @Volatile private var recordingId: String? = null

    private suspend fun beginRecording() {
        recordingId = runCatching { scanCoordinator.recordings.begin(RecordingEntity.KIND_SCAN) }.getOrNull()
    }

    /** Closes the recording; NonCancellable because it runs from a cancelled loop's finally. */
    private suspend fun endRecording() {
        val id = recordingId ?: return
        recordingId = null
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { scanCoordinator.recordings.end(id, _uiState.value.publicIp) }
        }
    }

    private suspend fun runOnePass(options: ScanOptions) {
        _uiState.update {
            it.copy(
                isScanning = true, currentPhase = null, activeRadios = emptySet(),
                wifiCount = null, cellularCount = null, bleCount = null, satelliteCount = null,
                scanOptions = options,
            )
        }
        // One passCount bump for the whole pass — the four recordXxx calls
        // below happen per radio phase, not per pass, so passCount must be
        // incremented here rather than inside any of them. See
        // SurveyTally.beginPass's own doc for why.
        surveyTally.beginPass()
        val pass = scanCoordinator.runScan(
            options,
            recordingId = recordingId,
            onPhaseChange = { phase ->
                _uiState.update { it.copy(currentPhase = phase) }
                updateNotification(phaseNotificationLabel(phase))
            },
            // Dashboard's "unique devices heard"/band/strongest numbers used
            // to only update once the *entire* pass (all four radios)
            // finished. Recording each radio into surveyTally and refreshing
            // uiState.survey the moment its own phase completes means the
            // Dashboard reflects WiFi results while Cellular/BLE/GNSS are
            // still running, instead of waiting for all four.
            onPartialResult = { result ->
                when (result) {
                    is PartialScanResult.Wifi -> surveyTally.recordWifi(result.observations)
                    is PartialScanResult.Cellular -> surveyTally.recordCellular(result.observations)
                    is PartialScanResult.Ble -> surveyTally.recordBle(result.observations)
                    is PartialScanResult.Satellite -> surveyTally.recordSatellite(result.observations)
                }
                _uiState.update { state ->
                    val counts = when (result) {
                        is PartialScanResult.Wifi -> state.copy(wifiCount = result.observations.size)
                        is PartialScanResult.Cellular -> state.copy(cellularCount = result.observations.size)
                        is PartialScanResult.Ble -> state.copy(bleCount = result.observations.size)
                        is PartialScanResult.Satellite -> state.copy(satelliteCount = result.observations.size)
                    }
                    counts.copy(survey = surveyTally.snapshot())
                }
            },
            onRadioActive = { radio, active ->
                _uiState.update { it.copy(activeRadios = if (active) it.activeRadios + radio else it.activeRadios - radio) }
            },
        )
        // Speed for the walking/driving split, taken from a position the scan
        // already needed — never by waking the GPS just to ask.
        val lat = pass.latitude
        val lng = pass.longitude
        if (lat != null && lng != null) {
            motionDetector.onPassLocation(lat, lng, System.currentTimeMillis())
        }
        _uiState.update {
            it.copy(
                isScanning = false,
                currentPhase = null,
                activeRadios = emptySet(),
                completedScanCount = it.completedScanCount + 1,
                latestPass = pass,
                previousPass = it.latestPass,
                survey = surveyTally.snapshot(),
            )
        }
        // Persist only the last pass + count so the Dashboard survives a
        // restart. SurveyStats and previousPass are deliberately not persisted.
        LastScanStore.save(applicationContext, _uiState.value.completedScanCount, pass)
        if (lat != null && lng != null) {
            val position = TrackPoint(System.currentTimeMillis(), lat, lng, pass.locationAccuracyMeters?.toDouble(), pass.locationProvider)
            _uiState.update { it.copy(passPositions = (it.passPositions + position).takeLast(MAX_PASS_POSITIONS)) }
        }
        refreshPublicIp()
        fetchCatalogLabels(pass)
    }

    /** Labels this pass's devices with the bundled catalog (LocalSignatures). */
    private fun fetchCatalogLabels(pass: ScanSessionUploadRequest) {
        serviceScope.launch(Dispatchers.Default) {
            val catalog = LocalSignatures.catalog(applicationContext) ?: return@launch
            val labels = runCatching { LocalSignatures.labels(catalog, listOf(pass)) }.getOrNull() ?: return@launch
            _uiState.update { it.copy(catalogLabels = CatalogLabels.merge(it.catalogLabels, labels)) }
        }
    }

    /** Re-reads the public IP when it's older than PublicIp.REFRESH_MS. */
    private fun refreshPublicIp(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !PublicIp.due(_uiState.value.publicIpAtMs, now)) return
        _uiState.update { it.copy(publicIpAtMs = now) }
        serviceScope.launch(Dispatchers.IO) {
            PublicIp.fetch()?.let { ip -> _uiState.update { it.copy(publicIp = ip, publicIpAtMs = System.currentTimeMillis()) } }
        }
    }

    /** Session devices for the scan report (see SurveyTally.devices). */
    fun sessionDevices(): SurveyTally.SessionDevices = surveyTally.devices()

    /** Writes the scan report when scanning stops, so it's ready on the Scan tab. */
    private fun craftScanReport() {
        if (_uiState.value.latestPass == null) return
        serviceScope.launch {
            runCatching {
                PhoneReports.scan(applicationContext, _uiState.value, locationTrack.value, surveyTally.devices())
            }.onSuccess { file ->
                _uiState.update { it.copy(scanReportPath = file.absolutePath, scanReportPasses = it.completedScanCount) }
            }.onFailure { android.util.Log.w("ScanForegroundService", "scan report failed", it) }
        }
    }

    /** Drops the persistent notification once nothing needs us alive.
     *
     * Remote control counts as needing us alive: the whole point is that the
     * backend can reach this device while it's sitting idle, and a stopped
     * service can't be reached by anything. That does mean the notification
     * stays up permanently while armed — which is the honest signal, since
     * the phone really is standing by to scan on command. */
    private fun stopIfNothingKeepsUsAlive() {
        val state = _uiState.value
        val busy = state.isScanning || state.isContinuous || state.isLanScanning || _monitorState.value.running
        if (busy || settingsRepository.remoteControlEnabled) {
            if (!busy) updateNotification(idleNotificationText())
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatInterval(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60}s"
    }

    private fun idleNotificationText(): String =
        if (settingsRepository.remoteControlEnabled) {
            "Standing by for remote scan commands"
        } else {
            "whyfi is idle"
        }

    private fun phaseNotificationLabel(phase: ScanPhase): String = when (phase) {
        ScanPhase.RADIOS -> "Scanning WiFi, cellular, Bluetooth and GNSS…"
        ScanPhase.WIFI -> "Scanning WiFi…"
        ScanPhase.CELLULAR -> "Reading cellular info…"
        ScanPhase.BLE -> "Scanning Bluetooth devices…"
        ScanPhase.GNSS -> "Reading GNSS satellites…"
        ScanPhase.UPLOADING -> "Queuing for upload…"
        ScanPhase.DONE -> "Done"
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("whyfi scanner")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Scanning", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(MONITOR_MOVE_CHANNEL_ID, "Monitor field prompts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun showMonitorMoveSuggestion() {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, MONITOR_MOVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Move for new WiFi observations")
            .setContentText("No new AP or distinct receiver position for 2 minutes. Try another spot.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(MONITOR_MOVE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopMonitor()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_PASS_POSITIONS = 2_000
        private const val CHANNEL_ID = "whyfi_scanning"
        private const val NOTIFICATION_ID = 1001
        private const val MONITOR_MOVE_CHANNEL_ID = "whyfi_monitor_move"
        private const val MONITOR_MOVE_NOTIFICATION_ID = 1002

        /** Slice length for [interruptibleDelay] — the worst-case latency
         * between a remote stop arriving and the loop noticing. */
        private const val GRACEFUL_STOP_CHECK_MS = 500L
        private const val MONITOR_NOTIFICATION_INTERVAL_MS = 10_000L

        /** Ensures the service is independently started (not just bound) so
         * it survives its UI client unbinding — call before/alongside
         * binding whenever a scan is about to be triggered. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanForegroundService::class.java))
        }
    }
}
