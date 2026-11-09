package com.whyfi.app.mission

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.whyfi.app.scan.LocationSnapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.osmdroid.util.BoundingBox
import com.whyfi.app.scan.CatalogLabels
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whyfi.app.data.FavoritesRepository
import com.whyfi.app.data.PositionEstimator
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanForegroundService
import java.io.File
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * For a favorited WiFi network, BLE device, or cell tower, downloads its
 * near-location-filtered observations (see MissionController) and renders
 * them as gradient cones toward an estimated position — the native
 * equivalent of the PWA's Solo mode. Entry point is a stat chip next to GPS
 * on Dashboard/Scan (see MainActivity.kt), which also shows a live dot
 * while [missionController] is tracking, even when this screen isn't the
 * one on screen.
 */
@Composable
fun MissionScreen(
    favoritesRepository: FavoritesRepository,
    missionController: MissionController,
    service: ScanForegroundService?,
    onBack: (() -> Unit)? = null,
) {
    val uiState by missionController.uiState.collectAsState()
    val wifiFavorites by favoritesRepository.favorites(RadioKind.WIFI).collectAsState()
    val bleFavorites by favoritesRepository.favorites(RadioKind.BLE).collectAsState()
    val cellFavorites by favoritesRepository.favorites(RadioKind.CELLULAR).collectAsState()
    val targets = remember(wifiFavorites, bleFavorites, cellFavorites) {
        (wifiFavorites.map { MissionTarget(RadioKind.WIFI, it) } +
            bleFavorites.map { MissionTarget(RadioKind.BLE, it) } +
            cellFavorites.map { MissionTarget(RadioKind.CELLULAR, it) })
            .sortedBy { it.identifier }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Location starts with the screen: the map follows the phone before any
    // favorite is picked. Asked for here when not granted yet.
    var locationRevision by remember { mutableIntStateOf(0) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationRevision++ }
    val hasLocation = remember(locationRevision) { LocationSnapshot.hasLocationPermission(context) }
    val locationOn = remember(locationRevision) { LocationSnapshot.isLocationEnabled(context) }
    LaunchedEffect(Unit) {
        if (!LocationSnapshot.hasLocationPermission(context)) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Back from the system location settings: look again.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) locationRevision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Each provider's latest fix, for the status line and the ⓘ panel
    // (the same stats as the USB adapter's Mission map).
    var fixes by remember { mutableStateOf<Map<String, android.location.Location>>(emptyMap()) }
    DisposableEffect(hasLocation, locationOn) {
        fixes = LocationSnapshot.lastKnownCandidates(context).associateBy { it.provider ?: "unknown" }
        val subscription = if (hasLocation) missionController.watchHere { fix -> fixes = fixes + ((fix.provider ?: "unknown") to fix) } else null
        onDispose { subscription?.close() }
    }
    var clockMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); clockMs = android.os.SystemClock.elapsedRealtime() } }
    val fixInfos = fixes.values.map { it.toFixInfo(clockMs) }
    val bestFix = fixInfos.filter { it.ageMs < 120_000 }.minByOrNull { it.accuracyM } ?: fixInfos.minByOrNull { it.ageMs }

    // The 250m near-filter keeps a favorited target's sightings from some
    // other place entirely (a travel router, a BLE device that moved) from
    // corrupting the estimate. Fixed rather than user-toggleable: the
    // escape hatch that used to disable it lived here and was removed.
    val nearRadius = MissionController.DEFAULT_NEAR_RADIUS_M

    // Re-runs [target] at [radius], keeping tracking-vs-one-shot state as it
    // is. Used by the favorite chips and by the estimator selector, which
    // needs a refetch so the new algorithm's apex arrives.
    fun runTarget(target: MissionTarget, radius: Double) {
        if (uiState.isTracking) {
            missionController.start(target, service, radius)
        } else {
            scope.launch { missionController.selectTarget(target, radius) }
        }
    }

    fun selectOrRetarget(target: MissionTarget) = runTarget(target, nearRadius)

    // Display options (controls bar). Accuracy, cones, close readings and
    // reading dots only change the drawing; "24 h" and "Latest" filter what
    // was loaded (server or phone alike). "Latest" is the newest session:
    // readings with no gap over 30 min (LocalMissionPoints.latestSession).
    var showAccuracy by rememberSaveable { mutableStateOf(false) }
    var showCones by rememberSaveable { mutableStateOf(true) }
    var showClose by rememberSaveable { mutableStateOf(true) }
    var showReadings by rememberSaveable { mutableStateOf(true) }
    var last24h by rememberSaveable { mutableStateOf(false) }
    var latest by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    val now = remember(uiState.points, last24h) { System.currentTimeMillis() }
    val shown = remember(uiState.points, last24h, latest, now) {
        val day = if (!last24h) uiState.points else uiState.points.filter { (it.atMs ?: 0L) >= now - DAY_MS }
        if (latest) LocalMissionPoints.latestSession(day) else day
    }
    // The server's estimate covers every reading; once filtered, the cone
    // points from the filtered readings' own centroid instead.
    val apex = if (shown.size == uiState.points.size) selectedEstimate(uiState) else null

    // BLE favorites by name rather than MAC: the last pass first, then the
    // phone's stored scans, then the signature catalog label.
    val scanState = service?.uiState?.collectAsState()?.value
    LaunchedEffect(bleFavorites) { missionController.loadBleNames(bleFavorites) }
    fun label(target: MissionTarget): String {
        if (target.kind != RadioKind.BLE) return target.identifier
        val fromPass = scanState?.latestPass?.bleObservations.orEmpty()
            .firstOrNull { it.bleMac.equals(target.identifier, ignoreCase = true) && it.deviceName.isNotBlank() }?.deviceName
        return fromPass ?: uiState.bleNames[target.identifier]
            ?: scanState?.catalogLabels?.get(CatalogLabels.key(target.identifier))
            ?: target.identifier
    }

    // Laid out like the USB adapter's Map: the map, then a bar of controls,
    // then a bar of devices to track. The MapView is clipped at the View
    // level (see MissionMap): as a full-screen background with the bars
    // painted over it, it hid the favorites (user, 2026-11-08).
    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Mission", style = MaterialTheme.typography.titleMedium)
        }
        // Which algorithm decides where the target is: the comparison comes
        // from the server, so it's only offered for server readings.
        if (uiState.target != null && !uiState.fromPhone && shown.size == uiState.points.size) {
            MissionEstimatorSelector(
                current = uiState.estimator,
                onSelect = { selected ->
                    missionController.setEstimator(selected)
                    uiState.target?.let { runTarget(it, nearRadius) }
                },
            )
            if (uiState.points.isNotEmpty()) EstimatorReadout(uiState)
        }

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            MissionMap(
                points = shown,
                livePosition = uiState.livePosition,
                here = uiState.here,
                modifier = Modifier.fillMaxSize(),
                estimatedApex = apex,
                showAccuracy = showAccuracy,
                showCones = showCones,
                showClose = showClose,
                showReadings = showReadings,
                rangeM = radioRangeM(uiState.target?.kind),
                follow = follow,
                recenter = recenter,
            )
            MapDetailsPanel(showDetails, Modifier.align(Alignment.TopCenter)) {
                LocationFixList(context, fixInfos, {
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                })
                uiState.target?.let { target ->
                    Text("Target", style = MaterialTheme.typography.titleSmall)
                    Text("${target.kind.icon} ${label(target)} · ${shown.size} of ${uiState.points.size} readings shown · " +
                        (if (uiState.fromPhone) "from this phone's scans" else "from the server") +
                        " · within ${nearRadius.toInt()} m of you", style = MaterialTheme.typography.bodySmall)
                    val centre = apex ?: shown.takeIf { it.isNotEmpty() }?.let { pts -> Geo.weightedCentroid(pts.map { WeightedLatLng(it.lat, it.lng, it.weight) }) }
                    centre?.let { Text("Estimate: %.6f, %.6f · zoomed to about ±%d m, the radio's usual range".format(it.lat, it.lng, radioRangeM(target.kind).toInt()),
                        style = MaterialTheme.typography.bodySmall) }
                    shown.mapNotNull { it.accuracyM }.sorted().takeIf { it.isNotEmpty() }?.let { acc ->
                        Text("Reading accuracy: best ±${acc.first().toInt()} m · median ±${acc[acc.size / 2].toInt()} m", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Close readings (round shapes) are readings taken too near the estimated position to draw a cone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Controls: status, Track and the display options.
        Surface(Modifier.fillMaxWidth(), tonalElevation = 5.dp, shadowElevation = 5.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !hasLocation -> "Mission needs location permission."
                            !locationOn -> "Location is off."
                            else -> listOfNotNull(
                                bestFix?.let { fixSummary(it.provider, it.accuracyM) } ?: "No recent fix",
                                (statusBarText(uiState.copy(points = shown)) +
                                    when {
                                        uiState.target == null -> ""
                                        latest && last24h -> " (latest session, last 24 h)"
                                        latest -> " (latest session)"
                                        last24h -> " (last 24 h)"
                                        else -> ""
                                    }).takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = if (uiState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasLocation) TextButton({
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Allow") }
                    else if (!locationOn) TextButton({
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }) { Text("Turn on") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Tracking is the only thing that scans and reloads by
                    // itself; without it a picked target loads once.
                    Switch(
                        checked = uiState.isTracking,
                        enabled = uiState.target != null,
                        onCheckedChange = { tracking ->
                            val target = uiState.target ?: return@Switch
                            if (tracking) missionController.start(target, service, nearRadius) else missionController.stop()
                        },
                    )
                    Text(if (uiState.isTracking) "Tracking" else "Track", style = MaterialTheme.typography.bodyMedium)
                    // Same toggles and buttons as the USB Mission map (MapControls.kt).
                    MapLayerChip("◎ Accuracy", showAccuracy) { showAccuracy = !showAccuracy }
                    MapLayerChip("Cones", showCones) { showCones = !showCones }
                    MapLayerChip("Close", showClose) { showClose = !showClose }
                    MapLayerChip("Readings", showReadings) { showReadings = !showReadings }
                    MapLayerChip("24 h", last24h) { last24h = !last24h }
                    MapLayerChip("Latest", latest) { latest = !latest }
                    MapButtons(showDetails, { showDetails = !showDetails },
                        onRefresh = { uiState.target?.let { runTarget(it, nearRadius) } ?: recenter++ },
                        follow = follow, onFollowChange = { on -> follow = on; recenter++ })
                }
            }
        }

        // Devices to track.
        if (targets.isEmpty()) {
            Text(
                "No favorites yet. Star a Wi‑Fi network, BLE device or cell tower in its results (Scan → Wi‑Fi/BLE/Cellular) to track it here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            targets.forEach { target ->
                FilterChip(
                    selected = uiState.target == target,
                    onClick = { selectOrRetarget(target) },
                    label = { Text("${target.kind.icon} ${label(target)}", maxLines = 1) },
                    trailingIcon = {
                        // Separate tap target: the × unfavorites the target;
                        // if it was the active one, the selection is cleared
                        // so the map doesn't keep stale data.
                        Text(
                            "×",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp).clickable {
                                favoritesRepository.toggleFavorite(target.kind, target.identifier)
                                if (uiState.target == target) missionController.clearSelection()
                            },
                        )
                    },
                )
            }
        }
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Accuracy circles: a light blue, behind the cones. */
private const val ACCURACY_ARGB = 0x5560A5FA

private fun statusBarText(uiState: MissionUiState): String {
    // Always true now that the near-filter is fixed on — see nearRadius.
    val where = "near your current location"
    return when {
        uiState.target == null && uiState.here == null -> "Waiting for a location fix…"
        uiState.target == null -> "Pick a favorite below to locate it."
        uiState.isLoading -> "Locating…"
        uiState.error != null -> uiState.error
        uiState.points.isEmpty() && uiState.fromPhone ->
            listOfNotNull(uiState.notice, "No readings of \"${uiState.target.identifier}\" in this phone's scans $where.").joinToString(". ")
        uiState.points.isEmpty() -> "No readings of \"${uiState.target.identifier}\" found $where."
        uiState.fromPhone -> listOfNotNull(
            uiState.notice,
            "${uiState.points.size} reading${if (uiState.points.size == 1) "" else "s"} from this phone's scans" +
                if (uiState.isTracking) " — tracking." else ".",
        ).joinToString(". ")
        uiState.truncated && uiState.isTracking -> {
            "Showing ${uiState.points.size} readings (more exist) — tracking, outlined cone is your position."
        }
        uiState.truncated -> "Showing ${uiState.points.size} readings — more exist."
        uiState.isTracking && uiState.livePosition != null -> "Tracking — the outlined cone is your current position."
        uiState.isTracking -> "Tracking — waiting for a GPS/network fix…"
        uiState.points.isNotEmpty() -> {
            "${uiState.points.size} reading${if (uiState.points.size == 1) "" else "s"} $where."
        }
        else -> ""
    }
}

@Composable
private fun MissionMap(
    points: List<MissionPoint>,
    livePosition: LatLng?,
    here: LatLng?,
    modifier: Modifier = Modifier,
    estimatedApex: LatLng? = null,
    showAccuracy: Boolean = false,
    showCones: Boolean = true,
    showClose: Boolean = true,
    showReadings: Boolean = true,
    rangeM: Double = DEFAULT_RANGE_M,
    follow: Boolean = false,
    recenter: Int = 0,
) {
    val density = LocalDensity.current.density
    val overlay = remember(points, livePosition, estimatedApex, here, showAccuracy, showCones, showClose, showReadings) {
        if (points.isEmpty()) hereOverlay(here, density)
        else buildOverlay(points, livePosition, density, estimatedApex, showAccuracy, showCones, showClose, showReadings)
    }
    // What the camera frames: the estimated position at the radio's usual
    // range (user, 2026-11-09: "zoom in to scan width"), plus the phone
    // when it is near; or the phone alone while there is nothing to show.
    val camera = remember(points, estimatedApex, here != null, recenter, rangeM) {
        val centre = when {
            points.isNotEmpty() -> estimatedApex ?: Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
            else -> here
        }
        centre?.let { c ->
            val near = here?.takeIf { points.isNotEmpty() && Geo.haversineDistanceMeters(c.lat, c.lng, it.lat, it.lng) <= MissionController.DEFAULT_NEAR_RADIUS_M }
            Frame(c, rangeM, listOfNotNull(near))
        }
    }
    // Reframed only when that changes (new readings, first fix, ⌖), not on
    // every location update, so the map can be panned.
    val framedOn = remember { arrayOfNulls<Any>(1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Required by OSM's tile usage policy (identifies the app to the
            // tile server) and points the cache at app-private storage
            // rather than osmdroid's external-storage default, which would
            // otherwise need a storage permission on this app's minSdk 28.
            Configuration.getInstance().userAgentValue = ctx.packageName
            Configuration.getInstance().osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
            val map = MapView(ctx).apply {
                // Explicit rather than relying on whatever MapView's own
                // constructor defaults to — belt-and-suspenders alongside
                // clipToBounds() above, so its measured size can't end up
                // larger than the space Compose actually gave it.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // Zoomed out over Europe until the first fix arrives.
                controller.setZoom(4.0)
                controller.setCenter(GeoPoint(50.0, 10.0))
                outlineProvider = ViewOutlineProvider.BOUNDS
                clipToOutline = true
            }
            FrameLayout(ctx).apply {
                clipChildren = true
                clipToPadding = true
                outlineProvider = ViewOutlineProvider.BOUNDS
                clipToOutline = true
                addView(map)
            }
        },
        update = { frame ->
            val mapView = frame.getChildAt(0) as MapView
            mapView.overlays.clear()
            overlay?.let { mapView.overlays.add(it) }
            val key = listOf(camera, recenter)
            if (camera != null && framedOn[0] != key) {
                framedOn[0] = key
                // After layout: zoomToBoundingBox needs the view's size.
                mapView.post { frameMap(mapView, camera) }
            } else if (follow && here != null) {
                mapView.controller.setCenter(GeoPoint(here.lat, here.lng))
            }
            mapView.invalidate()
        },
    )
}

/** Estimator picker, on the screen where switching it visibly moves the cone
 * apex. A dropdown rather than chips: four algorithm names don't fit across a
 * phone, and this bar is already crowded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionEstimatorSelector(current: PositionEstimator, onSelect: (PositionEstimator) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = current.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Position estimator") },
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PositionEstimator.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The position from the estimator the user selected, or null when that
 * estimator didn't run (no readings, or it needs data this target lacks) —
 * callers fall back to a local centroid rather than showing nothing. */
private fun selectedEstimate(uiState: MissionUiState): LatLng? {
    val estimate = uiState.estimates?.estimates?.get(uiState.estimator.wireValue) ?: return null
    val lat = estimate.lat ?: return null
    val lng = estimate.lng ?: return null
    return LatLng(lat, lng)
}

/** What each algorithm says about this target, and how far apart they land.
 * Shown in the field precisely because disagreement is the signal: when they
 * cluster the position is trustworthy, and when they don't, walking toward
 * any single one of them is a guess. */
@Composable
private fun EstimatorReadout(uiState: MissionUiState) {
    val estimates = uiState.estimates ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Position estimates (using ${uiState.estimator.label})",
            style = MaterialTheme.typography.labelMedium,
        )
        PositionEstimator.entries.forEach { option ->
            val estimate = estimates.estimates[option.wireValue]
            val summary = when {
                estimate == null -> "—"
                !estimate.available -> "unavailable"
                estimate.lat == null || estimate.lng == null -> "—"
                else -> {
                    val apex = selectedEstimate(uiState)
                    val delta = if (apex != null) {
                        Geo.haversineDistanceMeters(apex.lat, apex.lng, estimate.lat, estimate.lng)
                    } else {
                        0.0
                    }
                    if (option == uiState.estimator) "selected" else "%.0f m away".format(delta)
                }
            }
            Text(
                "${option.label}: $summary",
                style = MaterialTheme.typography.bodySmall,
                color = if (option == uiState.estimator) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Typical range of each radio, the half-width Mission zooms to. */
internal fun radioRangeM(kind: RadioKind?): Double = when (kind) {
    RadioKind.BLE -> 30.0
    RadioKind.WIFI -> 80.0
    RadioKind.CELLULAR -> 1_000.0
    else -> DEFAULT_RANGE_M
}

private const val DEFAULT_RANGE_M = 60.0

/** A camera framing: [rangeM] around [centre], widened to keep [extra] in view. */
private data class Frame(val centre: LatLng, val rangeM: Double, val extra: List<LatLng>)

/** Zooms [mapView] to [frame]; retried until the view has a size. */
private fun frameMap(mapView: MapView, frame: Frame, tries: Int = 0) {
    if (mapView.width == 0 || mapView.height == 0) {
        if (tries < 20) mapView.postDelayed({ frameMap(mapView, frame, tries + 1) }, 50)
        return
    }
    val c = frame.centre
    val dLat = frame.rangeM / 111_320.0
    val dLng = frame.rangeM / (111_320.0 * kotlin.math.max(kotlin.math.cos(Math.toRadians(c.lat)), 0.01))
    val lats = listOf(c.lat - dLat, c.lat + dLat) + frame.extra.map { it.lat }
    val lngs = listOf(c.lng - dLng, c.lng + dLng) + frame.extra.map { it.lng }
    val box = BoundingBox(lats.max(), lngs.max(), lats.min(), lngs.min())
    mapView.zoomToBoundingBox(box, false, (24 * mapView.resources.displayMetrics.density).toInt())
    if (mapView.zoomLevelDouble > 19.0) mapView.controller.setZoom(19.0)
}

/** Just the phone's position, as a small green circle. */
private fun hereOverlay(here: LatLng?, density: Float): ConeOverlay? = here?.let {
    val circle = Geo.circlePolygon(it, MissionController.FALLBACK_CIRCLE_RADIUS_M)
    ConeOverlay(
        listOf(MissionShape.Circle(Geo.smoothPolygon(circle, 1), ConeOverlay.ESTIMATED_POSITION_GREEN_ARGB, emphasized = true)),
        apex = null, readingPoints = listOf(it), fadeNonEmphasized = false, strokeWidthPx = 3f * density,
    )
}

/** [estimatedApex] is the backend's answer under the estimator the user
 * selected in Settings (see PositionEstimator). Falls back to a locally
 * computed weighted centroid when the backend didn't supply one — an older
 * backend, or a fetch that hasn't landed yet — so Mission view keeps working
 * rather than losing its cone entirely. */
private fun buildOverlay(
    points: List<MissionPoint>,
    livePosition: LatLng?,
    density: Float,
    estimatedApex: LatLng? = null,
    showAccuracy: Boolean = false,
    showCones: Boolean = true,
    showClose: Boolean = true,
    showReadings: Boolean = true,
): ConeOverlay {
    val apex = estimatedApex ?: Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
    // GPS accuracy of each reading's position, drawn under everything else.
    val accuracyShapes = if (!showAccuracy) emptyList() else points.mapNotNull { point ->
        point.accuracyM?.takeIf { it > 0 }?.let { radius ->
            MissionShape.Circle(Geo.smoothPolygon(Geo.circlePolygon(LatLng(point.lat, point.lng), radius), 1), ACCURACY_ARGB)
        }
    }
    // A cone per reading; a reading too close to the estimate for a cone is
    // drawn as a round "close reading" in its signal color (the orange
    // circles), toggled separately.
    val historicalShapes = points.mapNotNull { point ->
        val target = LatLng(point.lat, point.lng)
        val color = SignalColor.signalStrengthColorArgb(point.weight)
        val cone = Geo.conePolygon(apex, target)
        if (cone != null) {
            if (showCones) MissionShape.Cone(Geo.smoothPolygon(cone, 1), color) else null
        } else if (showClose) {
            val circle = Geo.circlePolygon(target, MissionController.FALLBACK_CIRCLE_RADIUS_M)
            MissionShape.Circle(Geo.smoothPolygon(circle, 1), color)
        } else null
    }

    // The live "you are here" shape — always emphasized, which is also what
    // tells ConeOverlay to fade every shape above. Falls back to a small
    // circle at the live position itself when a cone can't be drawn (too
    // close to the apex, or the apex/live position coincide).
    val liveShape = livePosition?.let { live ->
        val cone = Geo.conePolygon(apex, live)
        if (cone != null) {
            MissionShape.Cone(Geo.smoothPolygon(cone, 1), ConeOverlay.EMPHASIS_STROKE_ARGB, emphasized = true)
        } else {
            val circle = Geo.circlePolygon(live, MissionController.FALLBACK_CIRCLE_RADIUS_M)
            MissionShape.Circle(Geo.smoothPolygon(circle, 1), ConeOverlay.ESTIMATED_POSITION_GREEN_ARGB, emphasized = true)
        }
    }

    val shapes = accuracyShapes + historicalShapes + listOfNotNull(liveShape)
    val readingPoints = (if (showReadings) points.map { LatLng(it.lat, it.lng) } else emptyList()) + listOfNotNull(livePosition)
    return ConeOverlay(shapes, apex, readingPoints, fadeNonEmphasized = liveShape != null, strokeWidthPx = 3f * density)
}
