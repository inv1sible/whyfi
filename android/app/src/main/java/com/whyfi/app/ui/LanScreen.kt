package com.whyfi.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.lan.LanRange
import com.whyfi.app.lan.LanScanner
import com.whyfi.app.lan.LanTarget
import com.whyfi.app.lan.LanWebLinks
import com.whyfi.app.lan.lanDeviceTypeLabel
import com.whyfi.app.report.PhoneReports
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * LAN sweep, reduced to one line: the field shows the WiFi network that
 * will be swept; the pencil toggle on its left switches it to a custom
 * range you type. Scan turns into Cancel while a sweep runs. Results below; hosts with web ports open in the browser.
 */
@Composable
fun LanScreen(service: ScanForegroundService?, uiState: ScanUiState) {
    val context = LocalContext.current
    val scanner = remember { LanScanner(context) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var wifiSubnet by remember { mutableStateOf<String?>(null) }
    // The WiFi network can change under the screen; re-read it now and then.
    LaunchedEffect(Unit) {
        while (true) {
            wifiSubnet = withContext(Dispatchers.IO) { scanner.wifiSubnet() }
            delay(5_000)
        }
    }
    val parsed = if (custom) LanRange.parse(customText) else null
    val canScan = !uiState.isLanScanning && service != null &&
        (if (custom) parsed is LanRange.Ok else wifiSubnet != null)
    val start = {
        if (canScan) {
            ScanForegroundService.start(context)
            service?.scanLan(if (custom) LanTarget.Custom(customText) else LanTarget.Wifi)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("bar") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconToggleButton(
                    checked = custom,
                    onCheckedChange = {
                        custom = it
                        // Start from the network you're on; edit from there.
                        if (it && customText.isBlank()) customText = wifiSubnet.orEmpty()
                    },
                    enabled = !uiState.isLanScanning,
                    modifier = Modifier.semantics {
                        contentDescription = if (custom) "Custom range. Tap to sweep the WiFi network." else "WiFi network. Tap to enter a custom range."
                    },
                ) { Text("✏️", fontSize = 18.sp) }
                OutlinedTextField(
                    value = if (custom) customText else wifiSubnet ?: "Not on WiFi",
                    onValueChange = { customText = it },
                    enabled = custom && !uiState.isLanScanning,
                    readOnly = !custom,
                    singleLine = true,
                    isError = custom && customText.isNotBlank() && parsed is LanRange.Invalid,
                    placeholder = { Text("192.168.1.0/24") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { start() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                if (uiState.isLanScanning) {
                    OutlinedButton(onClick = { service?.cancelLan() }) { Text("Cancel") }
                } else {
                    Button(onClick = { start() }, enabled = canScan) { Text("Scan") }
                }
            }
        }
        // One short status line: what's wrong with the range, or what it covers.
        item("status") {
            val line = when {
                custom && customText.isNotBlank() -> parsed?.let { if (it is LanRange.Ok) it.describe() else (it as LanRange.Invalid).reason }
                uiState.lanUnavailableReason != null -> uiState.lanUnavailableReason
                else -> null
            }
            line?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (parsed is LanRange.Invalid || (!custom && uiState.lanUnavailableReason != null))
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (uiState.isLanScanning && uiState.lanTotal > 0) {
            item("progress") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { uiState.lanChecked.toFloat() / uiState.lanTotal }, modifier = Modifier.fillMaxWidth())
                    Text("${uiState.lanChecked} / ${uiState.lanTotal} · ${uiState.lanDevices.size} found", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (uiState.lanDevices.isNotEmpty() || uiState.lanDeviceCount != null) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${uiState.lanDevices.size} devices" + (uiState.lanTargetLabel?.let { " · ${it.substringBefore(" · ")}" } ?: ""),
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    ReportButton(enabled = !uiState.isLanScanning && uiState.lanDevices.isNotEmpty()) {
                        PhoneReports.lan(context, uiState)
                    }
                }
            }
        }
        items(uiState.lanDevices, key = { it.ipAddress }) { device -> LanDeviceRow(device) }
    }
}

@Composable
private fun LanDeviceRow(device: LanObservationDto) {
    val context = LocalContext.current
    val links = remember(device.ipAddress, device.openPorts) {
        LanWebLinks.links(device.ipAddress, device.openPorts).associateBy { it.label.substringAfter(':').toInt() }
    }
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(device.hostname.ifBlank { device.ipAddress }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(lanDeviceTypeLabel(device.deviceTypeGuess), style = MaterialTheme.typography.labelMedium)
        }
        // "192.168.1.1 · ports 22, 80, 443": web ports are underlined links
        // that open the default browser.
        val details = buildAnnotatedString {
            val parts = mutableListOf<AnnotatedString.Builder.() -> Unit>()
            if (device.hostname.isNotBlank()) parts += { append(device.ipAddress) }
            if (device.openPorts.isNotEmpty()) parts += {
                append("ports ")
                device.openPorts.forEachIndexed { i, port ->
                    if (i > 0) append(", ")
                    val link = links[port]
                    if (link == null) append("$port")
                    else withLink(LinkAnnotation.Url(link.url, linkStyle) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            Toast.makeText(context, "No browser to open ${link.url}", Toast.LENGTH_SHORT).show()
                        }
                    }) { append("$port") }
                }
            }
            if (device.onvif.isNotEmpty()) parts += { append("ONVIF ${device.onvif["name"]?.takeIf { n -> n.isNotBlank() } ?: "device"}") }
            parts.forEachIndexed { i, part -> if (i > 0) append(" · "); part() }
        }
        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
