package com.whyfi.app.usbmon

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.whyfi.app.BuildConfig
import com.whyfi.app.scan.LocationSnapshot
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class MapMissionData(val path: String, val samples: List<MonitorGeoSample>, val targets: Set<String>)

/** Live local map while capturing, with access to retained mission logs. */
@Composable
internal fun MonitorMappingView(
    context: Context,
    state: MonitorUiState,
    favorites: Set<String>,
    modifier: Modifier = Modifier,
) {
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
    }
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val permitted = fine || coarse
    var liveFixes by remember { mutableStateOf<Map<String, Location>>(emptyMap()) }
    var clockMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    DisposableEffect(context, permissionRevision, permitted) {
        liveFixes = LocationSnapshot.lastKnownCandidates(context).associateBy { it.provider ?: "unknown" }
        val subscription = LocationSnapshot.requestUpdates(context, includeFused = true) { fix ->
            val provider = fix.provider ?: "unknown"
            liveFixes = liveFixes + (provider to Location(fix))
        }
        onDispose { subscription?.close() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(2_000); clockMs = SystemClock.elapsedRealtime() }
    }
    val receiverFixes = (if (state.running) state.locationFixes else emptyList()) +
        liveFixes.values.map { fix ->
            MonitorLocationCandidate(fix.provider ?: "unknown", fix.elapsedRealtimeNanos / 1_000_000,
                fix.latitude, fix.longitude,
                if (fix.hasAccuracy() && fix.accuracy.isFinite() && fix.accuracy >= 0f)
                    fix.accuracy.toDouble() else 1_000.0)
        }
    val fixes = receiverFixes.groupBy { it.provider }.mapNotNull { (_, candidates) ->
        candidates.maxByOrNull { it.bootMs }
    }.sortedBy { it.provider }
    val receiver = MonitorLocationPolicy.select(fixes, clockMs)
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
    val providerStatus = providers.joinToString(" · ") { provider ->
        val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        "$provider ${if (enabled) "on" else "off"}"
    }
    var selectedLogPath by remember { mutableStateOf<String?>(null) }
    var selectedMac by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(state.missionLogPath) {
        selectedLogPath = null
        selectedMac = null
    }
    val missions = remember(state.reportPath, state.missionLogPath, refresh) {
        MonitorMission.list(context).filter { it.log.isFile }
    }
    val currentLog = state.missionLogPath?.let(::File)?.takeIf { it.isFile }
    val log = selectedLogPath?.let(::File)?.takeIf { it.isFile }
        ?: currentLog ?: missions.firstOrNull()?.log
    val logPath = log?.absolutePath
    var data by remember { mutableStateOf<MapMissionData?>(null) }
    val sampleRevision = if (logPath == currentLog?.absolutePath) state.geoSamples else 0
    LaunchedEffect(logPath, sampleRevision, refresh) {
        if (log == null) {
            data = null
        } else {
            val loaded = withContext(Dispatchers.IO) {
                MapMissionData(log.absolutePath, MonitorMission.readSamples(log), MonitorMission.targets(log))
            }
            data = loaded
        }
    }
    val shown = data?.takeIf { it.path == logPath }
    val targets = (shown?.targets.orEmpty() +
        if (logPath == currentLog?.absolutePath) favorites else emptySet()).sorted()
    val mac = selectedMac?.takeIf { it in targets } ?: targets.firstOrNull()
    val samples = remember(shown, mac) { shown?.samples?.filter { it.mac == mac }.orEmpty() }
    var estimates by remember { mutableStateOf<Map<String, MonitorEstimate>>(emptyMap()) }
    LaunchedEffect(shown, targets) {
        estimates = withContext(Dispatchers.Default) {
            targets.mapNotNull { target ->
                MonitorLocalization.estimate(shown?.samples?.filter { it.mac == target }.orEmpty())
                    ?.let { target to it }
            }.toMap()
        }
    }
    var recenter by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Location · v${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.titleMedium)
            TextButton({ refresh++; recenter++; permissionRevision++ }) { Text("Refresh") }
        }
        TextButton({ menuOpen = true }) {
            Text(if (logPath == currentLog?.absolutePath && currentLog != null) "Current capture ▾"
                else if (log == null) "No mission yet ▾"
                else "Saved mission ${log.parentFile?.name?.take(8).orEmpty()} ▾")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            currentLog?.let {
                DropdownMenuItem(text = { Text("Current capture") }, onClick = {
                    selectedLogPath = null; selectedMac = null; menuOpen = false
                })
            }
            missions.forEach { mission ->
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(mission.log.lastModified()))
                DropdownMenuItem(text = { Text("$date · ${mission.directory.name.take(8)}") }, onClick = {
                    selectedLogPath = mission.log.absolutePath; selectedMac = null; menuOpen = false
                })
            }
        }
        Text(if (receiver == null) "Own position: no recent Android fix (last 60 s)"
            else "Own position (${receiver.fix.provider}): %.6f, %.6f · ±%.0f m · %d s old".format(
                receiver.fix.lat, receiver.fix.lng, receiver.fix.accuracyM, receiver.ageMs / 1_000),
            style = MaterialTheme.typography.bodySmall)
        MonitorLocationMap(receiver, estimates, mac, samples, Modifier.weight(1f),
            mapKey = "$logPath:$recenter")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${estimates.size}/${targets.size} pinned estimates · ${shown?.samples?.size ?: 0} saved readings",
                style = MaterialTheme.typography.bodySmall)
            TextButton({ recenter++ }, enabled = receiver != null) { Text("Center on me") }
        }
        if (targets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                targets.forEach { target ->
                    FilterChip(target == mac, { selectedMac = target },
                        label = { Text("$target (${shown?.samples?.count { it.mac == target } ?: 0})") })
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Android location diagnostics", style = MaterialTheme.typography.titleSmall)
            Text("Permission: ${if (fine) "precise" else if (coarse) "approximate" else "none"} · $providerStatus",
                style = MaterialTheme.typography.bodySmall)
            if (!permitted) TextButton({ permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Grant location permission") }
            if (fixes.isEmpty()) Text("Android has delivered no GPS, network or fused fix. Check Location and permission in Android settings.",
                style = MaterialTheme.typography.bodySmall)
            fixes.forEach { fix ->
                val ageMs = (clockMs - fix.bootMs).coerceAtLeast(0)
                Text("${fix.provider}: %.6f, %.6f · ±%.0f m · %d s old%s".format(
                    fix.lat, fix.lng, fix.accuracyM, ageMs / 1_000,
                    if (ageMs > MonitorLocationPolicy.MAX_FIX_AGE_MS) " (too old for capture)" else ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.running || state.geoDiagnostics.radioReadings > 0) {
                val diagnostics = state.geoDiagnostics
                Text("Capture location updates: ${if (state.locationUpdatesRegistered) "registered" else "unavailable"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Pinned devices heard: ${diagnostics.favoriteDevicesHeard} · without RSSI: ${diagnostics.favoriteWithoutRssi} · " +
                    "new signal readings: ${diagnostics.radioReadings} · saved: ${state.geoSamples} · " +
                    "no matching fix: ${diagnostics.noMatchingFix} · no RSSI: ${diagnostics.missingRssi} · " +
                    "no channel: ${diagnostics.missingChannel} · limit: ${diagnostics.sampleCap}",
                    style = MaterialTheme.typography.bodySmall)
                Text(state.geoStatus, style = MaterialTheme.typography.bodySmall)
            }
            if (mac != null && state.running) {
                val device = state.devices.firstOrNull { it.first.mac == mac }?.first
                val bootMs = device?.lastRssiBootMs
                val matchingFix = bootMs?.let { MonitorLocationPolicy.select(state.locationFixes, it) }
                Text(when {
                    device == null -> "$mac: no frames heard during this capture yet"
                    bootMs == null -> "$mac: heard, but no RSSI timestamp from adapter"
                    matchingFix == null -> "$mac: last RSSI ${((clockMs - bootMs) / 1_000).coerceAtLeast(0)} s ago; no Android fix within 60 s of that reading"
                    else -> "$mac: last RSSI ${((clockMs - bootMs) / 1_000).coerceAtLeast(0)} s ago on channel ${device.lastRssiChannel}; matching ${matchingFix.fix.provider} fix ±${matchingFix.effectiveAccuracyM.toInt()} m"
                }, style = MaterialTheme.typography.bodySmall)
            }
            when {
                targets.isEmpty() -> Text("Star an AP or station in Nearby. New signal readings will then be paired with Android fixes.",
                    style = MaterialTheme.typography.bodySmall)
                mac != null && samples.isEmpty() -> Text("$mac: no location-tagged signal yet. Keep monitoring and check the counters above.",
                    style = MaterialTheme.typography.bodySmall)
                mac != null -> estimates[mac]?.let { estimate ->
                    Text("$mac estimate: %.6f, %.6f · uncertainty ≥ %.0f m%s".format(
                        estimate.lat, estimate.lng, estimate.uncertaintyM,
                        if (estimate.provisional) " · provisional" else ""),
                        style = MaterialTheme.typography.bodySmall)
                    Text(estimate.advice.take(2).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
