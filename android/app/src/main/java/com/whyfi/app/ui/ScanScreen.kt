package com.whyfi.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.whyfi.app.mission.MissionController
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanOptions
import com.whyfi.app.scan.ScanPhase
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.ui.components.RadioStatChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// Pacing between passes in continuous mode — comfortably under the WiFi
// throttle's ~4-per-2-minutes budget (see ScanThrottleController) even
// though only WiFi is actually throttled by the OS.
private const val CONTINUOUS_SCAN_INTERVAL_MS = 30_000L
private const val AVAILABILITY_RECHECK_INTERVAL_MS = 2_000L

private fun formatInterval(seconds: Int?): String = when {
    seconds == null -> "?"
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60}s"
}

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
fun ScanScreen(
    service: ScanForegroundService?,
    uiState: ScanUiState,
    missionController: MissionController,
    onOpenDetail: (RadioKind) -> Unit = {},
    onOpenMission: () -> Unit = {},
) {
    val context = LocalContext.current

    var permissionsGranted by remember { mutableStateOf(PermissionHelper.hasAllRequiredPermissions(context)) }
    var locationServicesOn by remember { mutableStateOf(PermissionHelper.isLocationServicesEnabled(context)) }
    var remoteControlOn by remember { mutableStateOf(false) }

    // The setting outlives this screen (and the service), so re-read it once
    // the binding lands rather than assuming the local default.
    LaunchedEffect(service) {
        service?.let { remoteControlOn = it.isRemoteControlEnabled() }
    }

    var includeWifi by remember { mutableStateOf(true) }
    var includeCellular by remember { mutableStateOf(true) }
    var includeBle by remember { mutableStateOf(true) }
    var includeGnss by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = PermissionHelper.hasAllRequiredPermissions(context)
    }

    // Bluetooth can be enabled in-app via ACTION_REQUEST_ENABLE — the system
    // shows a dialog overlay ("Allow whyfi to turn on Bluetooth?") and the app
    // stays visible. Must be registered unconditionally (launchers cannot live
    // inside conditionals). After the result returns, the 2-second polling
    // loop below picks up the new adapter state automatically.
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* re-check happens via the availability polling loop */ }

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

    fun canScanNow() = permissionsGranted && service != null && service.canScanNow(includeWifi)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("whyfi scanner", style = MaterialTheme.typography.headlineSmall)

        // One row, always — not three separate fragments that only
        // sometimes render. Before any scan it shows zeros/dashes and
        // untappable chips; mid-scan it shows live counts with a spinner on
        // whichever phase is running; afterwards it's tappable through to
        // each radio's detail table. Mission sits at the very right of the
        // same row as the radio chips, matching Dashboard's layout, and is
        // always tappable regardless of scan state — it talks to the
        // backend directly, not to a local pass. Its own spinner doubles as
        // a live "still tracking" indicator even while you're on this tab
        // instead of Mission itself.
        val missionState by missionController.uiState.collectAsState()
        val scanning = uiState.isScanning || uiState.isContinuous
        // Only tappable once a pass has actually been retained and nothing's
        // actively running — the detail screen reads uiState.latestPass, so
        // without one there'd be nothing behind the tap.
        val openable = !scanning && uiState.latestPass != null

        if (scanning) {
            Text(phaseLabel(uiState.currentPhase))
        } else if (uiState.completedScanCount > 0) {
            Text("Last scan:")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioStatChip(
                "📶", "WiFi", uiState.wifiCount, scanning && uiState.currentPhase == ScanPhase.WIFI, Color(0xFF2DD4BF),
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.WIFI) }) else null,
            )
            RadioStatChip(
                "📡", "Cellular", uiState.cellularCount, scanning && uiState.currentPhase == ScanPhase.CELLULAR, Color(0xFF60A5FA),
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.CELLULAR) }) else null,
            )
            RadioStatChip(
                "🔵", "BLE", uiState.bleCount, scanning && uiState.currentPhase == ScanPhase.BLE, Color(0xFFA78BFA),
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.BLE) }) else null,
            )
            RadioStatChip(
                "🛰️", "GPS", uiState.satelliteCount, scanning && uiState.currentPhase == ScanPhase.GNSS, Color(0xFFFBBF24),
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.SATELLITE) }) else null,
            )
            RadioStatChip("🎯", "Mission", null, missionState.isTracking, Color(0xFFF472B6), modifier = Modifier.weight(1f), onClick = onOpenMission)
        }
        if (openable) {
            Text(
                "Tap a radio to see what it found.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // WiFi cannot be enabled in-app on API 29+ — setWifiEnabled is
            // restricted to system apps. Open the system WiFi settings
            // instead; the user returns on back.
            Button(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    Intent(Settings.ACTION_WIFI_SETTINGS) else
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                runCatching { context.startActivity(intent) }
            }) {
                Text("Open WiFi settings")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeCellular, onCheckedChange = { includeCellular = it }, enabled = !uiState.isContinuous)
            Text("Cellular")
        }
        if (includeCellular && uiState.cellularUnavailableReason != null) {
            Text("${uiState.cellularUnavailableReason} Cellular results will be empty until this is resolved.")
            // Airplane mode cannot be toggled in-app on modern Android.
            // Open the system airplane-mode settings page.
            Button(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            }) {
                Text("Open airplane mode settings")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeBle, onCheckedChange = { includeBle = it }, enabled = !uiState.isContinuous)
            Text("Bluetooth (BLE)")
        }
        if (includeBle && uiState.bleUnavailableReason != null) {
            Text("${uiState.bleUnavailableReason} BLE results will be empty until this is resolved.")
            // Turn Bluetooth on via the system "Allow whyfi to turn on
            // Bluetooth?" dialog (ACTION_REQUEST_ENABLE). This works without
            // BLUETOOTH_CONNECT runtime permission — the system handles the
            // enable. The dialog stays in-app (doesn't leave to settings).
            // "This device has no Bluetooth adapter" has no button.
            if (uiState.bleUnavailableReason == "Bluetooth is turned off.") {
                Button(onClick = {
                    runCatching {
                        bluetoothEnableLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }
                }) {
                    Text("Turn on Bluetooth")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeGnss, onCheckedChange = { includeGnss = it }, enabled = !uiState.isContinuous)
            Text("GNSS satellites")
        }
        if (includeGnss && uiState.gnssUnavailableReason != null) {
            Text("${uiState.gnssUnavailableReason} Satellite results will be empty until this is resolved.")
            // Location services cannot be enabled in-app — open the system
            // location settings page. "This device has no GPS hardware" has
            // no button because there's nothing to enable.
            if (uiState.gnssUnavailableReason == "Location services (GPS) are off.") {
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }) {
                    Text("Open location settings")
                }
            }
        }

        // Side-by-side rather than stacked: the two primary actions share one
        // row at equal width. Labels are shortened so both states (idle and
        // continuous-running) fit without truncation on narrow screens.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                enabled = canScanNow() && !uiState.isScanning && !uiState.isContinuous,
                modifier = Modifier.weight(1f),
                onClick = {
                    locationServicesOn = PermissionHelper.isLocationServicesEnabled(context)
                    ScanForegroundService.start(context)
                    service?.scanOnce(scanOptions())
                },
            ) {
                Text(if (canScanNow()) "Scan once" else "Throttled")
            }

            Button(
                enabled = permissionsGranted && service != null && !uiState.isScanning,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (uiState.isContinuous) {
                        // Doubles as the local kill switch while remote control is
                        // on: whoever is physically holding the phone wins.
                        if (remoteControlOn) {
                            service?.setRemoteControlEnabled(false)
                            remoteControlOn = false
                        }
                        service?.stopContinuous()
                    } else {
                        locationServicesOn = PermissionHelper.isLocationServicesEnabled(context)
                        ScanForegroundService.start(context)
                        service?.startContinuous(scanOptions(), CONTINUOUS_SCAN_INTERVAL_MS)
                    }
                },
            ) {
                Text(if (uiState.isContinuous) "Stop" else "Start")
            }
        }

        if (uiState.isContinuous) {
            Text(
                "Walk around while this runs — each pass is a new map point. Keeps going even if you switch tabs " +
                    "or apps. Completed passes: ${uiState.completedScanCount}",
            )
            // Without this, a phone deliberately idling at the 10-minute
            // stationary cadence is indistinguishable from one that has hung.
            uiState.motionState?.let { motion ->
                Text(
                    "${motion.label} — scanning every ${formatInterval(uiState.effectiveIntervalSeconds)}. " +
                        "Move the phone and the next pass starts right away.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (uiState.motionSource == "unavailable") {
                    Text(
                        "This phone has no usable motion sensor, so movement can't be detected — the walking " +
                            "interval is used throughout. Turn off adaptive cadence in Settings to pick one " +
                            "interval yourself.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (remoteControlOn) {
            Text(
                "Remote control is on — the scan settings above are being driven from the web UI. " +
                    "Turn it off in Settings, or press Stop above, to take back local control.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
