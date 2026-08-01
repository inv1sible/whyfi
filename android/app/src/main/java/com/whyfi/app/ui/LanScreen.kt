package com.whyfi.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.ui.components.RadioStatChip
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LanScreen(service: ScanForegroundService?, uiState: ScanUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resultsAnchor = remember { BringIntoViewRequester() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LAN scanner", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sweeps the WiFi network you're currently connected to for other devices (TCP-connect probe on common " +
                "ports — no root needed, but MAC addresses usually aren't available on modern Android without it). " +
                "This is slower than a regular scan — keeps going even if you switch tabs or apps.",
        )

        // Compact summary pinned above the button, mirroring the Scan
        // screen — grows live while scanning, stays as "last results"
        // afterwards. The detailed list is further down; jump straight to
        // it instead of scrolling past the controls every time.
        if (uiState.isLanScanning || uiState.lanDeviceCount != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioStatChip(
                    "🌐", "Devices found",
                    if (uiState.isLanScanning) uiState.lanDevices.size else uiState.lanDeviceCount,
                    false, Color(0xFF34D399),
                )
                if (uiState.lanDevices.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { resultsAnchor.bringIntoView() } }) {
                        Text("↓ Jump to results")
                    }
                }
            }
        }

        Button(
            enabled = !uiState.isLanScanning && service != null,
            onClick = {
                ScanForegroundService.start(context)
                service?.scanLan()
            },
        ) {
            Text(if (uiState.isLanScanning) "Scanning…" else "Scan LAN")
        }

        // Previously this case was silent: the sweep returned an empty list,
        // the chip read "0 devices", and there was nothing to distinguish it
        // from a genuinely empty network.
        uiState.lanUnavailableReason?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.error)
        }

        // Shown whether or not the sweep ran. If the app refuses, this is the
        // evidence for the refusal; if it sweeps, it's a record of which
        // network was swept — either way it can be checked against what a
        // tool like Portdroid reports instead of being taken on trust.
        if (uiState.lanNetworkReport.isNotEmpty()) {
            Text("Network interfaces", style = MaterialTheme.typography.titleMedium)
            uiState.lanNetworkReport.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        if (uiState.isLanScanning && uiState.lanTotal > 0) {
            LinearProgressIndicator(
                progress = { uiState.lanChecked.toFloat() / uiState.lanTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Checked ${uiState.lanChecked} of ${uiState.lanTotal} addresses")
        }

        if (uiState.lanDevices.isNotEmpty()) {
            Column(modifier = Modifier.bringIntoViewRequester(resultsAnchor)) {
                Text("Results", style = MaterialTheme.typography.titleMedium)
                uiState.lanDevices.forEach { device -> LanDeviceRow(device) }
            }
        }
    }
}

@Composable
private fun LanDeviceRow(device: LanObservationDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(device.hostname.ifBlank { device.ipAddress }, style = MaterialTheme.typography.bodyLarge)
            Text(device.ipAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (device.openPorts.isNotEmpty()) {
                Text(
                    "Ports: ${device.openPorts.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(deviceTypeLabel(device.deviceTypeGuess), style = MaterialTheme.typography.labelMedium)
    }
    HorizontalDivider()
}

private fun deviceTypeLabel(deviceType: String): String = when (deviceType) {
    "ROUTER" -> "Router/Gateway"
    "PRINTER" -> "Printer"
    "NAS" -> "NAS/File server"
    "MEDIA" -> "Media/streaming"
    "CAMERA" -> "Camera"
    "WINDOWS_HOST" -> "Windows host"
    "LINUX_HOST" -> "Linux/Unix host"
    "IOT" -> "IoT device"
    else -> "Unknown"
}
