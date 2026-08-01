package com.whyfi.app.ui

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
import androidx.compose.runtime.LaunchedEffect
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
    onOpenDetail: (RadioKind) -> Unit = {},
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
            // Only tappable once a pass has actually been retained — the
            // detail screen reads uiState.latestPass, so without one there'd
            // be nothing behind the tap.
            val openable = uiState.latestPass != null
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioStatChip(
                    "📶", "WiFi", uiState.wifiCount, false, Color(0xFF2DD4BF),
                    onClick = if (openable) ({ onOpenDetail(RadioKind.WIFI) }) else null,
                )
                RadioStatChip(
                    "📡", "Cellular", uiState.cellularCount, false, Color(0xFF60A5FA),
                    onClick = if (openable) ({ onOpenDetail(RadioKind.CELLULAR) }) else null,
                )
                RadioStatChip(
                    "🔵", "BLE", uiState.bleCount, false, Color(0xFFA78BFA),
                    onClick = if (openable) ({ onOpenDetail(RadioKind.BLE) }) else null,
                )
                RadioStatChip(
                    "🛰️", "Satellites", uiState.satelliteCount, false, Color(0xFFFBBF24),
                    onClick = if (openable) ({ onOpenDetail(RadioKind.SATELLITE) }) else null,
                )
            }
            if (openable) {
                Text(
                    "Tap a radio to see what it found.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Text(if (uiState.isContinuous) "Stop continuous scanning" else "Start continuous scanning")
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
