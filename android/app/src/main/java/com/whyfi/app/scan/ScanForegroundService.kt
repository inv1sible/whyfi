package com.whyfi.app.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whyfi.app.R
import com.whyfi.app.data.remote.LanObservationDto
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
    val isLanScanning: Boolean = false,
    val lanChecked: Int = 0,
    val lanTotal: Int = 0,
    val lanDeviceCount: Int? = null,
    // Grows live as devices are confirmed during a LAN scan, and stays
    // populated afterwards as the "last results" until the next scan starts.
    val lanDevices: List<LanObservationDto> = emptyList(),
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
    private val serviceScope = CoroutineScope(SupervisorJob())
    private var continuousJob: Job? = null

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): ScanForegroundService = this@ScanForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        scanCoordinator = ScanCoordinator(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("whyfi is idle"))
        return START_NOT_STICKY
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
            stopIfIdle()
        }
    }

    fun startContinuous(options: ScanOptions, intervalMs: Long) {
        if (_uiState.value.isContinuous) return
        _uiState.update { it.copy(isContinuous = true) }
        continuousJob = serviceScope.launch {
            while (isActive) {
                if (canScanNow(options.includeWifi)) {
                    runOnePass(options)
                    delay(intervalMs)
                } else {
                    delay(2000)
                }
            }
        }
    }

    fun stopContinuous() {
        continuousJob?.cancel()
        continuousJob = null
        _uiState.update { it.copy(isContinuous = false, currentPhase = null) }
        stopIfIdle()
    }

    fun scanLan() {
        if (_uiState.value.isLanScanning) return
        _uiState.update { it.copy(isLanScanning = true, lanChecked = 0, lanTotal = 0, lanDeviceCount = null, lanDevices = emptyList()) }
        updateNotification("Scanning LAN…")
        serviceScope.launch {
            val result = scanCoordinator.runLanScan(
                onProgress = { checked, total -> _uiState.update { it.copy(lanChecked = checked, lanTotal = total) } },
                onDeviceFound = { device -> _uiState.update { it.copy(lanDevices = it.lanDevices + device) } },
            )
            _uiState.update { it.copy(isLanScanning = false, lanDeviceCount = result.lanObservations.size) }
            stopIfIdle()
        }
    }

    private suspend fun runOnePass(options: ScanOptions) {
        _uiState.update {
            it.copy(
                isScanning = true, currentPhase = null,
                wifiCount = null, cellularCount = null, bleCount = null, satelliteCount = null,
            )
        }
        scanCoordinator.runScan(
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
        _uiState.update { it.copy(isScanning = false, currentPhase = null, completedScanCount = it.completedScanCount + 1) }
    }

    /** Drops the persistent notification once there's nothing left running
     * — it should only be visible while a scan is actually in flight. */
    private fun stopIfIdle() {
        val state = _uiState.value
        if (!state.isScanning && !state.isContinuous && !state.isLanScanning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

        /** Ensures the service is independently started (not just bound) so
         * it survives its UI client unbinding — call before/alongside
         * binding whenever a scan is about to be triggered. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanForegroundService::class.java))
        }
    }
}
