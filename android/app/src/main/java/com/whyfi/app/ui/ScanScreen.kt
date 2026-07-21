package com.whyfi.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanOptions
import com.whyfi.app.scan.ScanPhase
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.ui.components.RadioStatChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

// Pacing between passes in continuous mode — comfortably under the WiFi
// throttle's ~4-per-2-minutes budget (see ScanThrottleController) even
// though only WiFi is actually throttled by the OS.
private const val CONTINUOUS_SCAN_INTERVAL_MS = 30_000L
private const val AVAILABILITY_RECHECK_INTERVAL_MS = 2_000L

private fun phaseLabel(phase: ScanPhase?): String = when (phase) {
    ScanPhase.WIFI -> "Scanning WiFi…"
    ScanPhase.CELLULAR -> "Reading cellular info…"
    ScanPhase.BLE -> "Scanning Bluetooth devices…"
    ScanPhase.GNSS -> "Reading GNSS satellites…"
    ScanPhase.UPLOADING -> "Queuing for upload…"
    ScanPhase.DONE -> "Done"
    null -> ""
}

@Composable
fun ScanScreen() {
    val context = LocalContext.current

    var service by remember { mutableStateOf<ScanForegroundService?>(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as ScanForegroundService.LocalBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }

    // Bound (not started) here — this only gives the UI a live view into
    // whatever the service is doing. The service is independently started
    // (see ScanForegroundService.start) right when a scan is triggered, so
    // it survives this screen unbinding on tab switch / backgrounding.
    DisposableEffect(Unit) {
        context.bindService(Intent(context, ScanForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(connection) }
    }

    val fallbackState = remember { MutableStateFlow(ScanUiState()) }
    val uiState by (service?.uiState ?: fallbackState).collectAsState()

    var permissionsGranted by remember { mutableStateOf(PermissionHelper.hasAllRequiredPermissions(context)) }
    var locationServicesOn by remember { mutableStateOf(PermissionHelper.isLocationServicesEnabled(context)) }

    var includeWifi by remember { mutableStateOf(true) }
    var includeCellular by remember { mutableStateOf(true) }
    var includeBle by remember { mutableStateOf(true) }
    var includeGnss by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = PermissionHelper.hasAllRequiredPermissions(context)
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(PermissionHelper.requiredRuntimePermissions())
        }
    }

    // Polled rather than checked once — WiFi/cellular/Bluetooth can all be
    // toggled off (individually, or via airplane mode) at any time while
    // this screen is open, and a scan pass would otherwise silently return
    // zero results for that radio with no visible explanation.
    LaunchedEffect(service) {
        while (isActive) {
            service?.refreshAvailability()
            delay(AVAILABILITY_RECHECK_INTERVAL_MS)
        }
    }

    fun scanOptions() = ScanOptions(
        includeWifi = includeWifi,
        includeCellular = includeCellular,
        includeBle = includeBle,
        includeGnss = includeGnss,
    )

    fun canScanNow() = permissionsGranted && service != null && service!!.canScanNow(includeWifi)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("whyfi scanner", style = MaterialTheme.typography.headlineSmall)

        // Results stay pinned here, above the controls, so they don't
        // scroll out of view or get replaced by the options/buttons below.
        if (uiState.isScanning || uiState.isContinuous) {
            Text(phaseLabel(uiState.currentPhase))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioStatChip("📶", "WiFi", uiState.wifiCount, uiState.currentPhase == ScanPhase.WIFI, Color(0xFF2DD4BF))
                RadioStatChip("📡", "Cellular", uiState.cellularCount, uiState.currentPhase == ScanPhase.CELLULAR, Color(0xFF60A5FA))
                RadioStatChip("🔵", "BLE", uiState.bleCount, uiState.currentPhase == ScanPhase.BLE, Color(0xFFA78BFA))
                RadioStatChip("🛰️", "Satellites", uiState.satelliteCount, uiState.currentPhase == ScanPhase.GNSS, Color(0xFFFBBF24))
            }
        } else if (uiState.completedScanCount > 0) {
            Text("Last scan:")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioStatChip("📶", "WiFi", uiState.wifiCount, false, Color(0xFF2DD4BF))
                RadioStatChip("📡", "Cellular", uiState.cellularCount, false, Color(0xFF60A5FA))
                RadioStatChip("🔵", "BLE", uiState.bleCount, false, Color(0xFFA78BFA))
                RadioStatChip("🛰️", "Satellites", uiState.satelliteCount, false, Color(0xFFFBBF24))
            }
        }

        if (!permissionsGranted) {
            Text("Location, phone state, and Bluetooth permissions are required to scan.")
            Button(onClick = { permissionLauncher.launch(PermissionHelper.requiredRuntimePermissions()) }) {
                Text("Grant permissions")
            }
        }

        if (permissionsGranted && !locationServicesOn) {
            Text("Device location services are off — WiFi/BLE/GNSS results will be empty or stale until you enable them.")
        }

        Text("What to scan:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeWifi, onCheckedChange = { includeWifi = it }, enabled = !uiState.isContinuous)
            Text("WiFi")
        }
        if (includeWifi && uiState.wifiUnavailableReason != null) {
            Text("${uiState.wifiUnavailableReason} WiFi results will be empty until this is resolved.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeCellular, onCheckedChange = { includeCellular = it }, enabled = !uiState.isContinuous)
            Text("Cellular")
        }
        if (includeCellular && uiState.cellularUnavailableReason != null) {
            Text("${uiState.cellularUnavailableReason} Cellular results will be empty until this is resolved.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeBle, onCheckedChange = { includeBle = it }, enabled = !uiState.isContinuous)
            Text("Bluetooth (BLE)")
        }
        if (includeBle && uiState.bleUnavailableReason != null) {
            Text("${uiState.bleUnavailableReason} BLE results will be empty until this is resolved.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeGnss, onCheckedChange = { includeGnss = it }, enabled = !uiState.isContinuous)
            Text("GNSS satellites")
        }

        Button(
            enabled = canScanNow() && !uiState.isScanning && !uiState.isContinuous,
            onClick = {
                locationServicesOn = PermissionHelper.isLocationServicesEnabled(context)
                ScanForegroundService.start(context)
                service?.scanOnce(scanOptions())
            },
        ) {
            Text(if (canScanNow()) "Scan once" else "Scan throttled — try again shortly")
        }

        Button(
            enabled = permissionsGranted && service != null && !uiState.isScanning,
            onClick = {
                if (uiState.isContinuous) {
                    service?.stopContinuous()
                } else {
                    locationServicesOn = PermissionHelper.isLocationServicesEnabled(context)
                    ScanForegroundService.start(context)
                    service?.startContinuous(scanOptions(), CONTINUOUS_SCAN_INTERVAL_MS)
                }
            },
        ) {
            Text(if (uiState.isContinuous) "Stop continuous scanning" else "Start continuous scanning")
        }

        if (uiState.isContinuous) {
            Text(
                "Walk around while this runs — each pass is a new map point. Keeps going even if you switch tabs " +
                    "or apps. Completed passes: ${uiState.completedScanCount}",
            )
        }
    }
}
