package com.whyfi.app.usbmon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.whyfi.app.BuildConfig
import com.whyfi.app.diagnostics.CrashReportSender
import com.whyfi.app.diagnostics.DiagnosticTrace
import java.util.Date
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val ACTION_USB_PERMISSION = "com.whyfi.app.USB_MONITOR_PERMISSION"
private val DURATIONS_MS = listOf(60_000, 180_000, 600_000)
private const val MAX_LISTED = 200

/**
 * SPIKE — "USB monitor test" (Settings → Diagnostics). Finds an attached USB
 * WiFi stick, asks Android for permission, hands the device fd to the native
 * devourer probe for the chosen mode and duration, shows live counts and the
 * devices heard (with a vendor/kind guess), and produces a report that can be
 * sent through the crash-report channel — MACs and network names only if the
 * user ticks the box.
 */
@Composable
fun UsbMonitorTestDialog(backendUrl: String, token: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    var usbDevices by remember { mutableStateOf(usbManager.deviceList.values.toList()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf(LongArray(0)) }
    var lastRun by remember { mutableStateOf<UsbMonitorReport.Run?>(null) }
    var mode by remember { mutableStateOf(UsbMonitorNative.Mode.SURVEY) }
    var durationMs by remember { mutableStateOf(DURATIONS_MS[0]) }
    var includeDetails by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>>(emptyList()) }
    var oui by remember { mutableStateOf<OuiTable?>(null) }
    val report = remember(lastRun, includeDetails) {
        lastRun?.let { UsbMonitorReport.format(it.copy(includeDeviceDetails = includeDetails)) }
    }
    var sendMessage by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        oui = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
        }
    }
    fun snapshotDevices() = SniffedDevices.parse(UsbMonitorNative.devices()).map { d ->
        d to SniffedDevices.guess(d) { oui?.vendorOf(it) }
    }
    // Live counters and device list while the native test runs.
    LaunchedEffect(running) {
        var tick = 0
        while (running) {
            stats = UsbMonitorNative.stats()
            if (tick++ % 4 == 0) devices = withContext(Dispatchers.Default) { snapshotDevices() }
            delay(500)
        }
    }
    // Leaving the screen mid-test stops the radio cleanly; the screen stays
    // on while capturing (a long run would otherwise be cut by screen-off).
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { if (running) UsbMonitorNative.stop() } }

    fun startTest(device: UsbDevice) {
        running = true
        lastRun = null
        devices = emptyList()
        sendMessage = null
        val runMode = mode
        val runDuration = durationMs
        scope.launch {
            val startedAt = System.currentTimeMillis()
            DiagnosticTrace.clear(context)
            DiagnosticTrace.log(context, "usbmon: test started for ${describe(device)} (${runMode.name}, ${runDuration / 1000} s)")
            var result: String? = null
            if (UsbMonitorNative.loadError != null) {
                result = "native library failed to load: ${UsbMonitorNative.loadError}"
            } else if (!usbManager.hasPermission(device) && !requestPermission(context, usbManager, device)) {
                result = "USB permission denied"
            } else {
                status = "Running for ${runDuration / 1000} s — keep the stick plugged in…"
                result = withContext(Dispatchers.IO) {
                    val connection = usbManager.openDevice(device)
                        ?: return@withContext "UsbManager.openDevice returned null"
                    try {
                        UsbMonitorNative.run(
                            connection.fileDescriptor,
                            context.cacheDir.absolutePath,
                            DiagnosticTrace.traceFile(context).absolutePath,
                            runMode.channels,
                            runMode.dwellMs,
                            runDuration,
                        ).ifEmpty { null }
                    } finally {
                        connection.close()
                    }
                }
            }
            DiagnosticTrace.log(context, "usbmon: test ended: ${result ?: "OK"}")
            val finalStats = if (UsbMonitorNative.loadError == null) UsbMonitorNative.stats() else LongArray(0)
            stats = finalStats
            val finalDevices = if (UsbMonitorNative.loadError == null) {
                withContext(Dispatchers.Default) { snapshotDevices() }
            } else {
                emptyList()
            }
            devices = finalDevices
            lastRun = UsbMonitorReport.Run(
                device = describe(device),
                androidInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                appVersion = BuildConfig.VERSION_NAME,
                result = result,
                durationMs = System.currentTimeMillis() - startedAt,
                stats = finalStats,
                perChannel = if (UsbMonitorNative.loadError == null) UsbMonitorNative.perChannel() else LongArray(0),
                trace = DiagnosticTrace.tail(context, 80),
                logcat = withContext(Dispatchers.IO) { ownLogcat() },
                mode = "${runMode.label}, ${runMode.dwellMs} ms per channel",
                devices = finalDevices,
                vendorPrefixes = oui?.size ?: 0,
            )
            status = if (result == null) "Finished." else "Failed: $result"
            running = false
        }
    }

    Dialog(onDismissRequest = { if (!running) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("USB monitor test (experimental)", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Puts an RTL8812AU/8821AU USB WiFi stick into monitor mode (receive only — nothing is transmitted) " +
                        "and lists the access points and clients it hears, with a guess at what each device is. " +
                        "Nothing is stored. The report sent to the server contains counts and vendors only, " +
                        "unless you tick the box to include device details.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("Mode", style = MaterialTheme.typography.titleSmall)
                UsbMonitorNative.Mode.entries.forEach { m ->
                    FilterChip(selected = mode == m, enabled = !running, onClick = { mode = m }, label = { Text(m.label) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATIONS_MS.forEach { ms ->
                        FilterChip(
                            selected = durationMs == ms, enabled = !running, onClick = { durationMs = ms },
                            label = { Text(if (ms < 120_000) "${ms / 1000} s" else "${ms / 60_000} min") },
                        )
                    }
                }

                if (usbDevices.isEmpty()) {
                    Text("No USB device attached. Plug the stick in via an OTG adapter, then refresh.")
                }
                usbDevices.forEach { device ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !running, onClick = { startTest(device) }) { Text("Test") }
                        Text(describe(device), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !running, onClick = { usbDevices = usbManager.deviceList.values.toList() }) {
                        Text("Refresh devices")
                    }
                    if (running) {
                        OutlinedButton(onClick = { UsbMonitorNative.stop() }) { Text("Stop early") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }

                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (stats.isNotEmpty()) {
                    UsbMonitorReport.summary(stats).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                if (devices.isNotEmpty()) {
                    HorizontalDivider()
                    if (oui == null) Text("Vendor table not loaded — vendors unknown.", color = MaterialTheme.colorScheme.error)
                    DeviceList(devices)
                }

                report?.let { text ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeDetails, onCheckedChange = { includeDetails = it }, enabled = !sending)
                        Text(
                            "Include device details (MAC addresses, network names) in the report",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        enabled = !sending,
                        onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    sendMessage = CrashReportSender.send(backendUrl, token, DiagnosticTrace.isoUtc(Date()), text)
                                } finally {
                                    sending = false
                                }
                            }
                        },
                    ) { Text(if (sending) "Sending…" else "Send report to server") }
                    sendMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun describe(device: UsbDevice): String =
    "%04x:%04x %s %s".format(
        device.vendorId, device.productId,
        device.manufacturerName.orEmpty(), device.productName.orEmpty(),
    ).trim()

/** Android's USB permission dialog, as a suspend call. */
private suspend fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean =
    suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        // Explicit (package-scoped) intent: required for a mutable PendingIntent
        // on Android 14+, and mutable so the system can add the grant extras.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

/** An app may read its own process's log lines; this captures devourer's
 * driver messages (tag "devourer") and ours, which the user can't reach
 * without adb otherwise. */
private fun ownLogcat(): String? = runCatching {
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-t", "300", "-v", "time", "devourer:V", "whyfiusb:V", "*:S"),
    )
    process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
}.getOrNull()?.takeLast(20_000)

/** Devices grouped by guessed kind, strongest signal first. */
@Composable
private fun DeviceList(devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>) {
    val byKind = devices.groupBy { it.second.kind }
    Text("Heard ${devices.size} devices", style = MaterialTheme.typography.titleMedium)
    var listed = 0
    SniffedDevices.Kind.entries.forEach { kind ->
        val group = byKind[kind].orEmpty().sortedByDescending { it.first.bestRssi ?: -1000 }
        if (group.isEmpty()) return@forEach
        Text("${kind.label} (${group.size})", style = MaterialTheme.typography.titleSmall)
        group.forEach { (d, g) ->
            if (listed++ >= MAX_LISTED) return@forEach
            Column {
                Text(
                    listOfNotNull(
                        g.vendor ?: if (d.randomized) "private MAC" else "unknown vendor",
                        d.ssid.takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
                        d.bestRssi?.let { "$it dBm" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${d.mac} · ch ${d.channels.joinToString(",")} · ${d.frames} frames",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                SniffedDevices.activity(d).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                g.reasons.filterNot { it.startsWith("vendor:") }.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (listed > MAX_LISTED) Text("… and ${listed - MAX_LISTED} more (see report)", style = MaterialTheme.typography.bodySmall)
}
