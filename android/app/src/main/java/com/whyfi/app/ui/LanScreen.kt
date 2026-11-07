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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.whyfi.app.lan.LanRange
import com.whyfi.app.lan.LanScanner
import com.whyfi.app.lan.LanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LanScreen(service: ScanForegroundService?, uiState: ScanUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resultsAnchor = remember { BringIntoViewRequester() }
    val scanner = remember { LanScanner(context) }
    // "auto", "iface:<name>" or "custom"; saved so rotation keeps the choice.
    var choice by rememberSaveable { mutableStateOf("auto") }
    var customText by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var networks by remember { mutableStateOf<List<LanScanner.NetworkCandidate>>(emptyList()) }
    LaunchedEffect(refresh) { networks = withContext(Dispatchers.IO) { scanner.inspectNetworks() } }
    val target: LanTarget = when {
        choice == "custom" -> LanTarget.Custom(customText)
        choice.startsWith("iface:") -> LanTarget.Interface(choice.removePrefix("iface:"))
        else -> LanTarget.Auto
    }
    val targetError = (target as? LanTarget.Custom)?.let { (LanRange.parse(it.text) as? LanRange.Invalid)?.reason }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LAN scanner", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sweeps a local network for other devices (TCP-connect probe on common " +
            "ports plus ONVIF discovery — no root needed, but MAC addresses usually aren't available on modern Android without it). " +
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

        LanTargetPicker(
            networks = networks,
            choice = choice,
            onChoice = { choice = it },
            customText = customText,
            onCustomText = { customText = it },
            enabled = !uiState.isLanScanning,
            onRefresh = { refresh++ },
        )

        Button(
            enabled = !uiState.isLanScanning && service != null && targetError == null,
            onClick = {
                ScanForegroundService.start(context)
                service?.scanLan(target)
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

        uiState.lanTargetLabel?.let { Text("Swept: $it", style = MaterialTheme.typography.bodySmall) }

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
            if (device.onvif.isNotEmpty()) {
                Text("ONVIF: ${device.onvif["name"]?.takeIf { it.isNotBlank() } ?: "device discovered"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Where the sweep goes: automatic (WiFi first, then wired), one interface's
 * subnet, or a custom private range. Interfaces that can't be swept are
 * listed greyed out with the reason, so the choice can be checked.
 */
@Composable
private fun LanTargetPicker(
    networks: List<LanScanner.NetworkCandidate>,
    choice: String,
    onChoice: (String) -> Unit,
    customText: String,
    onCustomText: (String) -> Unit,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = enabled) { Text("⟳ Refresh") }
        }
        TargetOption("auto", choice, onChoice, enabled, "Automatic", "WiFi first, then wired")
        networks.filterNot { it.skipReason == "loopback" }.forEach { nic ->
            TargetOption(
                key = "iface:${nic.interfaceName}",
                choice = choice,
                onChoice = onChoice,
                enabled = enabled && nic.usable,
                title = "${nic.interfaceName} · ${nic.kind}",
                detail = "${nic.address}/${nic.prefixLength} · " + when {
                    !nic.usable -> nic.skipReason
                    nic.truncated -> "first ${nic.hostCount} addresses"
                    else -> "${nic.hostCount} addresses"
                },
            )
        }
        TargetOption("custom", choice, onChoice, enabled, "Custom range", "CIDR, start–end, or start–last octet")
        if (choice == "custom") {
            val parsed = LanRange.parse(customText)
            OutlinedTextField(
                value = customText,
                onValueChange = onCustomText,
                enabled = enabled,
                singleLine = true,
                label = { Text("Range") },
                placeholder = { Text("192.168.1.0/24 or 192.168.1.10-50") },
                isError = customText.isNotBlank() && parsed is LanRange.Invalid,
                supportingText = {
                    Text(when (parsed) {
                        is LanRange.Ok -> parsed.describe()
                        is LanRange.Invalid -> parsed.reason
                    })
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            )
        }
    }
}

@Composable
private fun TargetOption(
    key: String,
    choice: String,
    onChoice: (String) -> Unit,
    enabled: Boolean,
    title: String,
    detail: String?,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = choice == key, enabled = enabled, role = Role.RadioButton) { onChoice(key) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = choice == key, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
