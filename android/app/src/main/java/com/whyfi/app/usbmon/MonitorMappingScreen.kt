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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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

private data class MapMissionData(
    val path: String,
    val samples: List<MonitorGeoSample>,
    val targets: Set<String>,
    val labels: Map<String, String>,
    val events: List<MonitorMission.ReplayEvent>,
)

internal data class MonitorLocationTarget(val mac: String, val label: String, val readings: Int)

/** Live local map while capturing, with access to retained mission logs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorMappingView(
    context: Context,
    state: MonitorUiState,
    favorites: Set<String>,
    selectedMissionLogPath: String?,
    selectedMac: String?,
    onTargetsChanged: (List<MonitorLocationTarget>) -> Unit,
    onReceiverAvailability: (Boolean) -> Unit,
    refreshToken: Int,
    recenterToken: Int,
    showGeoDetails: Boolean,
    onDismissGeoDetails: () -> Unit,
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
    DisposableEffect(context, permissionRevision, permitted, refreshToken) {
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
    val receiver = MonitorLocationPolicy.select(receiverFixes, clockMs)
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
    val providerStatus = providers.joinToString(" · ") { provider ->
        val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        "$provider ${if (enabled) "on" else "off"}"
    }
    val replay = selectedMissionLogPath != null
    val currentLog = state.missionLogPath?.let(::File)?.takeIf { it.isFile }
    val log = if (replay) selectedMissionLogPath?.let(::File)?.takeIf { it.isFile } else currentLog
    val logPath = log?.absolutePath
    var data by remember { mutableStateOf<MapMissionData?>(null) }
    val sampleRevision = if (logPath == currentLog?.absolutePath) state.geoSamples else 0
    LaunchedEffect(logPath, sampleRevision, refreshToken) {
        if (log == null) {
            data = null
        } else {
            val loaded = withContext(Dispatchers.IO) {
                MapMissionData(log.absolutePath, MonitorMission.readSamples(log), MonitorMission.targets(log),
                    MonitorMission.readLabels(log), MonitorMission.readReplayEvents(log))
            }
            data = loaded
        }
    }
    val shown = data?.takeIf { it.path == logPath }
    val targets = (shown?.targets.orEmpty() +
        if (!replay && logPath == currentLog?.absolutePath) favorites else emptySet()).sorted()
    val mac = selectedMac?.takeIf { it in targets }
    val timeline = remember(shown) {
        val times = shown?.let { data -> data.samples.map { it.atMs } + data.events.map { it.atMs } }.orEmpty()
        (times.minOrNull() ?: 0L) to (times.maxOrNull() ?: 0L)
    }
    var replayFraction by remember(selectedMissionLogPath) { mutableFloatStateOf(1f) }
    var settledFraction by remember(selectedMissionLogPath) { mutableFloatStateOf(1f) }
    var playing by remember(selectedMissionLogPath) { mutableStateOf(false) }
    var scrubRecenter by remember(selectedMissionLogPath) { mutableIntStateOf(0) }
    LaunchedEffect(replayFraction, replay) {
        if (replay) { delay(120); settledFraction = replayFraction }
    }
    LaunchedEffect(playing, timeline, replay) {
        val durationMs = timeline.second - timeline.first
        while (replay && playing && durationMs > 0 && replayFraction < 1f) {
            delay(500)
            replayFraction = (replayFraction + 5_000f / durationMs).coerceAtMost(1f)
            if (replayFraction >= 1f) playing = false
        }
    }
    val cutoffMs = timeline.first + ((timeline.second - timeline.first) * settledFraction).toLong()
    val readings = remember(shown, replay, cutoffMs) {
        shown?.samples?.let { all -> if (replay) all.filter { it.atMs <= cutoffMs } else all }.orEmpty()
    }
    val route = if (mac == null) readings else readings.filter { it.mac == mac }
    val replayReceiver = readings.maxByOrNull { it.atMs }?.let { sample ->
        MonitorSelectedLocation(
            MonitorLocationCandidate(sample.locationSource.ifBlank { "recorded" }, 0L,
                sample.lat, sample.lng, sample.gpsAccuracyM), sample.fixAgeMs,
            MonitorLocationPolicy.effectiveAccuracy(sample.gpsAccuracyM, sample.fixAgeMs),
        )
    }
    val mapReceiver = if (replay) replayReceiver else receiver
    LaunchedEffect(mapReceiver != null) { onReceiverAvailability(mapReceiver != null) }
    val liveLabels = state.devices.associate { (device, guess) -> device.mac to MonitorMission.deviceName(device, guess) }
    val displayLabels = targets.associateWith { target ->
        MonitorMission.displayTarget(target,
            if (replay) shown?.labels?.get(target) else liveLabels[target] ?: shown?.labels?.get(target))
    }
    val readingCounts = remember(readings) { readings.groupingBy { it.mac }.eachCount() }
    val targetOptions = targets.map { target ->
        MonitorLocationTarget(target, displayLabels[target].orEmpty(), readingCounts[target] ?: 0)
    }
    LaunchedEffect(targetOptions) { onTargetsChanged(targetOptions) }
    val estimateKey = "$logPath:$sampleRevision:$cutoffMs:${targets.joinToString()}"
    var estimated by remember { mutableStateOf<Pair<String, Map<String, MonitorEstimate>>?>(null) }
    LaunchedEffect(estimateKey) {
        val calculated = withContext(Dispatchers.Default) {
            targets.mapNotNull { target ->
                MonitorLocalization.estimate(readings.filter { it.mac == target })
                    ?.let { target to it }
            }.toMap()
        }
        estimated = estimateKey to calculated
    }
    val estimates = estimated?.takeIf { it.first == estimateKey }?.second.orEmpty()
    val visibleEstimates = if (mac == null) estimates else estimates.filterKeys { it == mac }

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (replay) {
            "Mission ${log?.parentFile?.name?.take(8) ?: "unavailable"} · ${readings.size} readings so far"
        } else if (receiver == null) "Own position: no recent fix · tap 🌐 for details"
            else "Best fix: ${receiver.fix.provider} · ±${receiver.effectiveAccuracyM.toInt()} m · ${receiver.ageMs / 1_000} s old",
            style = MaterialTheme.typography.bodySmall)
        MonitorLocationMap(mapReceiver, visibleEstimates, mac, route, displayLabels, Modifier.weight(1f),
            mapKey = "$logPath:$recenterToken:$scrubRecenter", followReceiver = replay && playing)
        Text("${estimates.size}/${targets.size} pinned estimates · ${readings.size} location readings",
            style = MaterialTheme.typography.bodySmall)
        if (targets.isEmpty()) {
            Text(if (replay) "No pinned device in this mission." else "Star an AP or station in Nearby to map it here.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (replay && timeline.second > timeline.first) {
            val lastEvent = shown?.events?.lastOrNull { it.atMs <= cutoffMs }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton({
                    if (replayFraction >= 1f) { replayFraction = 0f; settledFraction = 0f; scrubRecenter++ }
                    playing = !playing
                }) { Text(if (playing) "Pause" else "▶ 10×") }
                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(cutoffMs)) +
                    (lastEvent?.let { " · ${replayEventText(it, displayLabels)}" } ?: ""),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Slider(replayFraction, onValueChange = { playing = false; replayFraction = it },
                onValueChangeFinished = { settledFraction = replayFraction; scrubRecenter++ })
        }
    }
    if (showGeoDetails) ModalBottomSheet(onDismissRequest = onDismissGeoDetails) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (replay) {
                Text("Recorded receiver at selected time", style = MaterialTheme.typography.titleSmall)
                Text(replayReceiver?.let { selected ->
                    "${selected.fix.provider}: %.6f, %.6f · ±%.0f m · fix age %d s".format(
                        selected.fix.lat, selected.fix.lng, selected.effectiveAccuracyM, selected.ageMs / 1_000)
                } ?: "No geotagged reading before this point.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Android location diagnostics · app v${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.titleSmall)
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
            if (!replay && (state.running || state.geoDiagnostics.radioReadings > 0)) {
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
            if (mac != null && state.running && !replay) {
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
                targets.isEmpty() -> Text(if (replay) "No pinned device in this mission."
                    else "Star an AP or station in Nearby. New signal readings will then be paired with Android fixes.",
                    style = MaterialTheme.typography.bodySmall)
                mac != null && route.isEmpty() -> Text("${displayLabels[mac]}: no location-tagged signal at this point in the timeline.",
                    style = MaterialTheme.typography.bodySmall)
                mac != null -> estimates[mac]?.let { estimate ->
                    Text("${displayLabels[mac]} estimate: %.6f, %.6f · uncertainty ≥ %.0f m%s".format(
                        estimate.lat, estimate.lng, estimate.uncertaintyM,
                        if (estimate.provisional) " · provisional" else ""),
                        style = MaterialTheme.typography.bodySmall)
                    Text(estimate.advice.take(2).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun replayEventText(event: MonitorMission.ReplayEvent, labels: Map<String, String>): String =
    when (event.event) {
        "monitor_started" -> "Capture started"
        "monitor_finished" -> "Capture finished"
        "favorite" -> {
            val mac = event.details.substringBeforeLast(':')
            val label = labels[mac] ?: MonitorMission.displayTarget(mac, null)
            "${if (event.details.endsWith(":true")) "Pinned" else "Unpinned"} $label"
        }
        "channels_selected" -> "Channels ${event.details.take(30)}"
        "channel_mode" -> "Channel mode ${event.details}"
        "update_mode" -> "Display ${event.details}"
        else -> event.event.replace('_', ' ')
    }
