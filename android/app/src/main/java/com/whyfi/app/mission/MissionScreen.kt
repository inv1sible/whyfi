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
    DisposableEffect(hasLocation, locationOn) {
        val subscription = if (hasLocation) missionController.watchHere() else null
        onDispose { subscription?.close() }
    }

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

    // Favorites on top, the map in the middle, the status line below. The
    // osmdroid MapView used to draw over its Compose neighbours when only
    // clipped by clipToBounds(), so it was a full-screen background with the
    // bars painted over it — which hid the favorites (user, 2026-11-08).
    // MissionMap now clips at the View level as well (a clipping FrameLayout
    // plus an outline clip on the MapView itself).
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // As a tab, the tab row already says Mission: no title line.
            if (onBack != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Mission", style = MaterialTheme.typography.titleMedium)
            }

            if (targets.isEmpty()) {
                Text(
                    "No favorites yet. Star a WiFi network, BLE device, or cell tower from its results " +
                        "table (Scan → WiFi/BLE/Cellular) to track it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // Left-most, per its role as the primary on/off control
                    // for whatever's currently selected — disabled until
                    // something is picked, since there's nothing to track yet.
                    Switch(
                        checked = uiState.isTracking,
                        enabled = uiState.target != null,
                        onCheckedChange = { tracking ->
                            val target = uiState.target ?: return@Switch
                            if (tracking) missionController.start(target, service, nearRadius) else missionController.stop()
                        },
                    )
                    Text(
                        if (uiState.isTracking) "Tracking" else "Track",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp, end = 12.dp),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        targets.forEach { target ->
                            FilterChip(
                                selected = uiState.target == target,
                                onClick = { selectOrRetarget(target) },
                                label = { Text("${target.kind.icon} ${target.identifier}") },
                                trailingIcon = {
                                    // Separate tap target: the X unfavorites
                                    // the target instead of selecting it. If the
                                    // removed favorite was the active target,
                                    // clear the selection so the map doesn't
                                    // keep showing stale data for something
                                    // that's no longer tracked.
                                    Text(
                                        "×",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .clickable {
                                                favoritesRepository.toggleFavorite(target.kind, target.identifier)
                                                if (uiState.target == target) {
                                                    missionController.clearSelection()
                                                }
                                            },
                                    )
                                },
                            )
                        }
                    }
                }

                // Which algorithm decides where the target is. Lives here
                // rather than in Settings because this is the screen where
                // the choice visibly changes something — the cone apex moves
                // as you switch, with the readout below showing how far the
                // others land from it.
                // The estimator comparison comes from the server; the phone's
                // own scans give a weighted centroid only.
                if (uiState.target != null && !uiState.fromPhone) {
                    MissionEstimatorSelector(
                        current = uiState.estimator,
                        onSelect = { selected ->
                            missionController.setEstimator(selected)
                            uiState.target?.let { runTarget(it, nearRadius) }
                        },
                    )
                }

                if (uiState.points.isNotEmpty()) {
                    EstimatorReadout(uiState)
                }

            }
        }

        // The map takes the space between the favorites and the status
        // line, clipped so it can't draw over them (see MissionMap).
        MissionMap(
            points = uiState.points,
            livePosition = uiState.livePosition,
            here = uiState.here,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            estimatedApex = selectedEstimate(uiState),
        )

        run {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        !hasLocation -> "Mission needs location permission."
                        !locationOn -> "Location is off."
                        else -> statusBarText(uiState)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!hasLocation) TextButton({
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Allow") }
                else if (!locationOn) TextButton({
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }) { Text("Turn on") }
            }
        }
    }
}

private fun statusBarText(uiState: MissionUiState): String {
    // Always true now that the near-filter is fixed on — see nearRadius.
    val where = "near your current location"
    return when {
        uiState.target == null && uiState.here == null -> "Waiting for a location fix…"
        uiState.target == null -> "Pick a favorite above to locate it."
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
) {
    val density = LocalDensity.current.density
    val overlay = remember(points, livePosition, estimatedApex, here) {
        if (points.isEmpty()) hereOverlay(here, density) else buildOverlay(points, livePosition, density, estimatedApex)
    }
    // The camera looks at the same place the cone points from — the selected
    // estimator's position, falling back to a local centroid when the backend
    // hasn't supplied one — or at the phone while there is nothing to show.
    val center = remember(points, estimatedApex, here != null) {
        if (points.isEmpty()) here
        else estimatedApex ?: Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
    }
    // Recentred only when what it looks at changes (new readings, first
    // fix), not on every location update, so the map can be panned.
    val centeredOn = remember { arrayOfNulls<Any>(1) }

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
            val key = listOf(points, estimatedApex, center == null)
            if (center != null && centeredOn[0] != key) {
                centeredOn[0] = key
                mapView.controller.setZoom(18.0)
                mapView.controller.setCenter(GeoPoint(center.lat, center.lng))
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
): ConeOverlay {
    val apex = estimatedApex ?: Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
    val historicalShapes = points.map { point ->
        val target = LatLng(point.lat, point.lng)
        val color = SignalColor.signalStrengthColorArgb(point.weight)
        val cone = Geo.conePolygon(apex, target)
        if (cone != null) {
            MissionShape.Cone(Geo.smoothPolygon(cone, 1), color)
        } else {
            val circle = Geo.circlePolygon(target, MissionController.FALLBACK_CIRCLE_RADIUS_M)
            MissionShape.Circle(Geo.smoothPolygon(circle, 1), color)
        }
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

    val shapes = if (liveShape != null) historicalShapes + liveShape else historicalShapes
    val readingPoints = points.map { LatLng(it.lat, it.lng) } + listOfNotNull(livePosition)
    return ConeOverlay(shapes, apex, readingPoints, fadeNonEmphasized = liveShape != null, strokeWidthPx = 3f * density)
}
