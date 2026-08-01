package com.whyfi.app.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whyfi.app.BuildConfig
import com.whyfi.app.R
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.ScanPolicyResponse
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.SensorHeartbeatRequest
import com.whyfi.app.motion.MotionDetector
import com.whyfi.app.motion.MotionState
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ScanUiState(
    val isScanning: Boolean = false,
    val isContinuous: Boolean = false,
    val currentPhase: ScanPhase? = null,
    val wifiCount: Int? = null,
    val cellularCount: Int? = null,
    val bleCount: Int? = null,
    val satelliteCount: Int? = null,
    val completedScanCount: Int = 0,
    val wifiUnavailableReason: String? = null,
    val cellularUnavailableReason: String? = null,
    val bleUnavailableReason: String? = null,
    val lanUnavailableReason: String? = null,
    // Every IPv4 interface the phone has and whether a sweep could use it.
    // Shown on the LAN screen so a refusal can be checked rather than taken
    // on trust — the /32 tunnel bug looked exactly like a correct answer.
    val lanNetworkReport: List<String> = emptyList(),
    val isLanScanning: Boolean = false,
    val lanChecked: Int = 0,
    val lanTotal: Int = 0,
    val lanDeviceCount: Int? = null,
    // Grows live as devices are confirmed during a LAN scan, and stays
    // populated afterwards as the "last results" until the next scan starts.
    val lanDevices: List<LanObservationDto> = emptyList(),
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
        scanCoordinator = ScanCoordinator(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
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
                wifiCount = null,
                cellularCount = null,
                bleCount = null,
                satelliteCount = null,
                latestPass = null,
                previousPass = null,
                survey = surveyTally.snapshot(),
            )
        }
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

            if (policy.remoteScanEnabled) {
                // Also the update path: if the loop is already running this
                // just swaps the config it reads each iteration.
                startContinuous(options, intervalMs)
            } else if (_uiState.value.isContinuous) {
                stopContinuous(graceful = true)
            }

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
            )
        }
    }

    fun canScanNow(includeWifi: Boolean): Boolean =
        !includeWifi || scanCoordinator.wifiScanManager.throttle.canScanNow()

    fun scanOnce(options: ScanOptions) {
        if (_uiState.value.isScanning || _uiState.value.isContinuous) return
        serviceScope.launch {
            runOnePass(options)
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
        continuousJob = serviceScope.launch {
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
                motionDetector.stop()
                _uiState.update {
                    it.copy(
                        isContinuous = false,
                        currentPhase = null,
                        motionState = null,
                        effectiveIntervalSeconds = null,
                    )
                }
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

    fun scanLan() {
        if (_uiState.value.isLanScanning) return

        // Ask before sweeping. A sweep that can't run produced an empty device
        // list that was indistinguishable from "nobody answered" — and still
        // uploaded a zero-observation session, which then consumed a slot in
        // the "last N scans" filter for no information at all.
        val report = scanCoordinator.lanScanner.inspectNetworks().map { it.describe() }
        val unavailable = scanCoordinator.lanScanner.unavailableReason()
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
                lanNetworkReport = report,
            )
        }
        updateNotification("Scanning LAN…")
        serviceScope.launch {
            val result = scanCoordinator.runLanScan(
                onProgress = { checked, total -> _uiState.update { it.copy(lanChecked = checked, lanTotal = total) } },
                onDeviceFound = { device -> _uiState.update { it.copy(lanDevices = it.lanDevices + device) } },
            )
            _uiState.update { it.copy(isLanScanning = false, lanDeviceCount = result.lanObservations.size) }
            stopIfNothingKeepsUsAlive()
        }
    }

    private suspend fun runOnePass(options: ScanOptions) {
        _uiState.update {
            it.copy(
                isScanning = true, currentPhase = null,
                wifiCount = null, cellularCount = null, bleCount = null, satelliteCount = null,
            )
        }
        val pass = scanCoordinator.runScan(
            options,
            onPhaseChange = { phase ->
                _uiState.update { it.copy(currentPhase = phase) }
                updateNotification(phaseNotificationLabel(phase))
            },
            onPartialResult = { phase, count ->
                _uiState.update { state ->
                    when (phase) {
                        ScanPhase.WIFI -> state.copy(wifiCount = count)
                        ScanPhase.CELLULAR -> state.copy(cellularCount = count)
                        ScanPhase.BLE -> state.copy(bleCount = count)
                        ScanPhase.GNSS -> state.copy(satelliteCount = count)
                        else -> state
                    }
                }
            },
        )
        // runScan's return value used to be discarded, which is why the app
        // could say "41 WiFi" but never which 41. Keeping the last two shifts
        // that from a count to a result set at no storage cost.
        surveyTally.record(pass)
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
                completedScanCount = it.completedScanCount + 1,
                latestPass = pass,
                previousPass = it.latestPass,
                survey = surveyTally.snapshot(),
            )
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
        val busy = state.isScanning || state.isContinuous || state.isLanScanning
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
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "whyfi_scanning"
        private const val NOTIFICATION_ID = 1001

        /** Slice length for [interruptibleDelay] — the worst-case latency
         * between a remote stop arriving and the loop noticing. */
        private const val GRACEFUL_STOP_CHECK_MS = 500L

        /** Ensures the service is independently started (not just bound) so
         * it survives its UI client unbinding — call before/alongside
         * binding whenever a scan is about to be triggered. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanForegroundService::class.java))
        }
    }
}
