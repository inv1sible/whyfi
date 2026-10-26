package com.whyfi.app.usbmon

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.whyfi.app.BuildConfig
import com.whyfi.app.scan.LocationSnapshot
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class MapMissionData(
    val path: String,
    val samples: List<MonitorGeoSample>,
    val targets: Set<String>,
    val labels: Map<String, String>,
    val events: List<MonitorMission.ReplayEvent>,
    val receiverFixes: List<MonitorReceiverFix>,
    val accessPointSamples: List<MonitorGeoSample>,
)

internal data class MonitorLocationTarget(val mac: String, val label: String, val readings: Int)

/** Live local map while capturing, with access to retained mission logs. */
@Composable
internal fun MonitorMappingView(
    context: Context,
    state: MonitorUiState,
    favorites: Set<String>,
    hiddenAps: Set<String>,
    activeMissionId: String?,
    selectedMissionLogPath: String?,
    replayMission: MonitorReplayMission?,
    showAllAps: Boolean,
    replayCutoffMs: Long,
    followReceiver: Boolean,
    selectedMac: String?,
    onTargetsChanged: (List<MonitorLocationTarget>) -> Unit,
    onSummaryChanged: (String) -> Unit,
    refreshToken: Int,
    recenterToken: Int,
    showGeoDetails: Boolean,
    onSelectAp: (String) -> Unit,
    onFavoriteAp: (String) -> Unit,
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
            if ((liveFixes[provider]?.elapsedRealtimeNanos ?: 0L) < fix.elapsedRealtimeNanos)
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
                    fix.accuracy.toDouble() else 1_000.0,
                if (fix.hasSpeed() && fix.speed.isFinite() && fix.speed >= 0f)
                    fix.speed.toDouble() else 15.0)
        }
    val fixes = receiverFixes.groupBy { it.provider }.mapNotNull { (_, candidates) ->
        candidates.maxByOrNull { it.bootMs }
    }.sortedBy { it.provider }
    val rawReceiver = MonitorLocationPolicy.select(receiverFixes, clockMs)
    var liveAnchor by remember { mutableStateOf<MonitorLocationCandidate?>(null) }
    LaunchedEffect(rawReceiver?.fix?.bootMs, rawReceiver?.fix?.provider, selectedMissionLogPath) {
        liveAnchor = if (selectedMissionLogPath != null || rawReceiver == null) null
            else MonitorLocationPolicy.stabilize(liveAnchor, rawReceiver.fix)
    }
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
    val providerStatus = providers.joinToString(" · ") { provider ->
        val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        "$provider ${if (enabled) "on" else "off"}"
    }
    val replay = selectedMissionLogPath != null
    val currentLog = (state.missionLogPath?.let(::File)?.takeIf { it.isFile }
        ?: MonitorMission.list(context).firstOrNull { it.directory.name == activeMissionId }?.log)
    val log = if (replay) selectedMissionLogPath?.let(::File)?.takeIf { it.isFile } else currentLog
    val logPath = log?.absolutePath
    var data by remember { mutableStateOf<MapMissionData?>(null) }
    val sampleRevision = if (logPath == currentLog?.absolutePath)
        Triple(state.geoSamples, state.locationFixes.maxOfOrNull { it.bootMs }, state.snapshotRevisionMs) else null
    LaunchedEffect(logPath, sampleRevision, refreshToken) {
        if (log == null || replay) {
            data = null
        } else {
            val loaded = withContext(Dispatchers.IO) {
                val snapshots = MonitorMission.readDeviceSnapshots(log)
                val names = snapshots.asSequence().flatMap { SniffedDevices.parse(it.tsv).asSequence() }
                    .map { device -> device.mac to (device.ssid.takeIf { it.isNotBlank() }
                        ?: device.wpsName.takeIf { it.isNotBlank() } ?: "Access point") }
                    .toMap() + MonitorMission.readLabels(log)
                val savedFixes = MonitorMission.readReceiverFixes(log)
                MapMissionData(log.absolutePath, MonitorMission.readSamples(log),
                    MonitorMission.targets(log), names, MonitorMission.readReplayEvents(log),
                    savedFixes, MonitorSampleReconciler.accessPoints(savedFixes, snapshots))
            }
            data = loaded
        }
    }
    val shown = if (replay) replayMission?.takeIf { it.log.absolutePath == logPath }?.let {
        MapMissionData(it.log.absolutePath, it.samples, it.targets, it.labels, it.events,
            it.receiverFixes, it.accessPointSamples)
    } else data?.takeIf { it.path == logPath }
    val favoriteTargets = (if (replay) shown?.targets.orEmpty() else favorites) - hiddenAps
    val cutoffMs = replayCutoffMs
    val targets = (favoriteTargets + listOfNotNull(selectedMac)).sorted()
    val allApTargets = if (showAllAps) shown?.accessPointSamples.orEmpty().asSequence()
        .filter { (!replay || it.atMs <= cutoffMs) && it.mac !in hiddenAps }
        .map { it.mac }.toSet() else emptySet()
    val apTargetMacs = shown?.accessPointSamples.orEmpty().mapTo(mutableSetOf()) { it.mac }
    val estimateTargets = (targets + allApTargets).distinct()
    val mac = selectedMac?.takeIf { it in targets }
    val readings = remember(shown, replay, cutoffMs, selectedMac, favoriteTargets, showAllAps, hiddenAps) {
        val savedReadings = shown?.samples?.let { all ->
            all.filter { (!replay || it.atMs <= cutoffMs) && (it.mac !in hiddenAps || it.mac == selectedMac) }
        }.orEmpty()
        val apReadings = shown?.accessPointSamples.orEmpty().filter { sample ->
            (showAllAps || sample.mac in favoriteTargets || sample.mac == selectedMac) &&
                (sample.mac !in hiddenAps || sample.mac == selectedMac) &&
                (!replay || sample.atMs <= cutoffMs)
        }
        val apMacs = apReadings.mapTo(mutableSetOf()) { it.mac }
        savedReadings.filter { it.mac !in apMacs } + apReadings
    }
    val rawReceiverPath = shown?.receiverFixes?.let { all ->
        if (replay) all.filter { it.atMs <= cutoffMs } else all
    }.orEmpty()
    val receiverPath = remember(rawReceiverPath) { MonitorLocationPolicy.bestRoute(rawReceiverPath) }
    val locatedApCount = shown?.accessPointSamples.orEmpty().asSequence()
        .filter { !replay || it.atMs <= cutoffMs }.map { it.mac }.distinct().count()
    val route = if (mac == null) readings else readings.filter { it.mac == mac }
    val replayReceiver = rawReceiverPath.lastOrNull()?.let { latest ->
        MonitorLocationPolicy.select(rawReceiverPath.map { it.candidate() },
            latest.bootMs + (cutoffMs - latest.atMs).coerceAtLeast(0))
    }?.let { MonitorLocationPolicy.displayAt(it, receiverPath.lastOrNull()?.candidate()) }
        ?: readings.takeIf { receiverPath.isEmpty() }?.maxByOrNull { it.atMs }?.let { sample ->
        MonitorSelectedLocation(
            MonitorLocationCandidate(sample.locationSource.ifBlank { "recorded" }, 0L,
                sample.lat, sample.lng, sample.gpsAccuracyM, sample.speedMps), sample.fixAgeMs,
            MonitorLocationPolicy.effectiveAccuracy(sample.gpsAccuracyM, sample.fixAgeMs, sample.speedMps),
        )
    }
    val mapReceiver = if (replay) replayReceiver else MonitorLocationPolicy.displayAt(rawReceiver,
        liveAnchor ?: receiverPath.lastOrNull()?.candidate())
    val liveLabels = state.devices.associate { (device, guess) -> device.mac to MonitorMission.deviceName(device, guess) }
    val displayLabels = estimateTargets.associateWith { target ->
        MonitorMission.displayTarget(target,
            if (replay) shown?.labels?.get(target) else liveLabels[target] ?: shown?.labels?.get(target))
    }
    val readingCounts = remember(readings) { readings.groupingBy { it.mac }.eachCount() }
    val targetOptions = targets.map { target ->
        MonitorLocationTarget(target, displayLabels[target].orEmpty(), readingCounts[target] ?: 0)
    }
    LaunchedEffect(targetOptions) { onTargetsChanged(targetOptions) }
    val estimateKey = "$logPath:$sampleRevision:$cutoffMs:${estimateTargets.joinToString()}"
    var estimated by remember { mutableStateOf<Pair<String, Map<String, MonitorEstimate>>?>(null) }
    LaunchedEffect(estimateKey) {
        val calculated = withContext(Dispatchers.Default) {
            estimateTargets.mapNotNull { target ->
                MonitorLocalization.estimate(readings.filter { it.mac == target })
                    ?.let { target to it }
            }.toMap()
        }
        estimated = estimateKey to calculated
    }
    val estimates = estimated?.takeIf { it.first == estimateKey }?.second.orEmpty()
    val visibleEstimates = if (mac == null) estimates else estimates.filterKeys { it == mac }
    val summary = if (replay) {
        "${if (mapReceiver?.held == true) "Position held · " else ""}$locatedApCount APs with phone positions · ${rawReceiverPath.size} receiver fixes"
    } else if (mapReceiver == null) {
        "No recent fix · $locatedApCount APs with phone positions"
    } else {
        "${mapReceiver.fix.provider} ±${mapReceiver.effectiveAccuracyM.toInt()} m${if (mapReceiver.held) " · position held" else ""} · $locatedApCount APs with phone positions"
    }
    LaunchedEffect(summary) { onSummaryChanged(summary) }

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.weight(1f)) {
            if (mapReceiver != null || estimates.isNotEmpty() || receiverPath.isNotEmpty()) {
                MonitorLocationMap(mapReceiver, visibleEstimates, mac, route, displayLabels, Modifier.fillMaxSize(),
                    mapKey = logPath.orEmpty(), recenterToken = recenterToken.toString(),
                    focusToken = if (replay && followReceiver) replayCutoffMs.toString() else "",
                    followReceiver = followReceiver && mac == null,
                    receiverPath = receiverPath, onEstimateTap = { tappedMac ->
                        if (tappedMac in apTargetMacs) {
                            onSelectAp(tappedMac)
                            if (tappedMac !in favorites) onFavoriteAp(tappedMac)
                        }
                    })
                if (mac != null && estimates[mac] == null && route.isEmpty()) {
                    Surface(Modifier.align(Alignment.TopCenter), tonalElevation = 4.dp) {
                        Text("No location readings for ${displayLabels[mac]} at this point.",
                            modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(when {
                    replay && shown == null -> "Loading mission map…"
                    mac != null -> "No location readings for ${displayLabels[mac]} at this point."
                    else -> "No receiver location or device estimate available yet."
                }, style = MaterialTheme.typography.bodySmall)
            }
            SlidingGeoDetails(showGeoDetails, Modifier.align(Alignment.TopCenter)) {
                GeoDetailsContent(fine, coarse, providerStatus, permitted, fixes, clockMs,
                    { permissionLauncher.launch(it) }, replay, replayReceiver, state, mac, route,
                    targets, estimates, displayLabels)
            }
        }
    }
}

@Composable
private fun SlidingGeoDetails(visible: Boolean, modifier: Modifier = Modifier,
                              content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier = modifier,
        enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            content()
        }
    }
}

@Composable
private fun GeoDetailsContent(
    fine: Boolean, coarse: Boolean, providerStatus: String, permitted: Boolean,
    fixes: List<MonitorLocationCandidate>, clockMs: Long,
    requestPermission: (Array<String>) -> Unit,
    replay: Boolean, replayReceiver: MonitorSelectedLocation?, state: MonitorUiState,
    mac: String?, route: List<MonitorGeoSample>, targets: List<String>,
    estimates: Map<String, MonitorEstimate>, displayLabels: Map<String, String>,
) {
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (replay) {
                Text("Recorded receiver at selected time", style = MaterialTheme.typography.titleSmall)
                Text(replayReceiver?.let { selected ->
                    "${selected.fix.provider}: %.6f, %.6f · ±%.0f m · fix age %d s%s".format(
                        selected.fix.lat, selected.fix.lng, selected.effectiveAccuracyM, selected.ageMs / 1_000,
                        if (selected.held) " · position held within reported accuracy" else "")
                } ?: "No geotagged reading before this point.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Android location diagnostics · app v${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.titleSmall)
            Text("Permission: ${if (fine) "precise" else if (coarse) "approximate" else "none"} · $providerStatus",
                style = MaterialTheme.typography.bodySmall)
            if (!permitted) TextButton({ requestPermission(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
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
                    "awaiting fix: ${diagnostics.noMatchingFix} · no RSSI: ${diagnostics.missingRssi} · " +
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
                    matchingFix == null -> "$mac: last RSSI ${((clockMs - bootMs) / 1_000).coerceAtLeast(0)} s ago; no Android fix within ${MonitorLocationPolicy.MAX_FIX_AGE_MS / 1_000} s of that reading"
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
