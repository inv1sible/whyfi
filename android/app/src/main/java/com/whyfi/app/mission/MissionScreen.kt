package com.whyfi.app.mission

import android.view.ViewGroup
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whyfi.app.data.FavoritesRepository
import com.whyfi.app.scan.LocationSnapshot
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
    onBack: () -> Unit,
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

    // Off by default — the map centers on the estimated position (the
    // weighted centroid of the currently loaded readings), which is what
    // most people want to look at. But with a lot of near-location matches
    // (BLE especially: plenty of separate devices/readings can legitimately
    // sit within the near-radius of wherever you're standing), that
    // estimate can land somewhere other than where you actually are. This
    // only changes what the camera looks at — the cones/estimate itself are
    // still always centroid-based, unaffected by this toggle.
    var centerOnMyLocation by remember { mutableStateOf(false) }
    val myLocationCenter = remember(centerOnMyLocation, uiState.livePosition) {
        if (!centerOnMyLocation) {
            null
        } else {
            // While tracking this updates live (see MissionController.start's
            // location listener); otherwise it's a one-shot "last known"
            // read, re-fetched only when the toggle itself is switched on.
            uiState.livePosition ?: LocationSnapshot.lastKnown(context)?.let { LatLng(it.latitude, it.longitude) }
        }
    }

    fun selectOrRetarget(target: MissionTarget) {
        if (uiState.isTracking) {
            missionController.start(target, service)
        } else {
            scope.launch { missionController.selectTarget(target) }
        }
    }

    // A Box, not a Column with the map sandwiched between two bars —
    // trying to *constrain* the map to a middle slice (weight(1f) +
    // clipToBounds()) never actually kept it from drawing over its
    // siblings, because the osmdroid MapView is an embedded Android View
    // (via AndroidView), and those composite in their own layer rather than
    // properly interleaving z-order with Compose-drawn siblings in a linear
    // layout. The fix is to stop fighting that: let the map be the full-
    // screen *background* layer (nothing above it for it to draw over), and
    // add the bars as later Compose children in the same Box, each with an
    // opaque background — later children in a Box always paint on top of
    // earlier ones, which is the one z-order guarantee that reliably holds
    // here.
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                uiState.target == null -> Text(
                    "Pick a favorite below to see its estimated location.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                uiState.points.isEmpty() -> Text(
                    if (uiState.isLoading) {
                        "Locating…"
                    } else if (uiState.error != null) {
                        ""
                    } else {
                        "No readings of \"${uiState.target?.identifier}\" found near your current location."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                else -> MissionMap(
                    points = uiState.points,
                    livePosition = uiState.livePosition,
                    centerOverride = myLocationCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
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
                            if (tracking) missionController.start(target, service) else missionController.stop()
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
                            )
                        }
                    }
                }

                if (uiState.points.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        // Off by default: the map centers on the estimated
                        // position (weighted centroid of the loaded
                        // readings). With a lot of near-location matches —
                        // BLE especially, where plenty of separate devices
                        // legitimately sit within the near-radius of
                        // wherever you're standing — that estimate can land
                        // somewhere other than where you actually are.
                        Switch(checked = centerOnMyLocation, onCheckedChange = { centerOnMyLocation = it })
                        Text(
                            "Center on my location",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }

        if (uiState.target != null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    statusBarText(uiState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun statusBarText(uiState: MissionUiState): String = when {
    uiState.isLoading -> "Locating…"
    uiState.error != null -> uiState.error
    uiState.truncated && uiState.isTracking -> {
        "Showing ${uiState.points.size} readings (more exist) — tracking, outlined cone is your position."
    }
    uiState.truncated -> "Showing ${uiState.points.size} readings — more exist near here."
    uiState.isTracking && uiState.livePosition != null -> "Tracking — the outlined cone is your current position."
    uiState.isTracking -> "Tracking — waiting for a GPS/network fix…"
    uiState.points.isNotEmpty() -> {
        "${uiState.points.size} reading${if (uiState.points.size == 1) "" else "s"} near your current location."
    }
    else -> ""
}

@Composable
private fun MissionMap(
    points: List<MissionPoint>,
    livePosition: LatLng?,
    centerOverride: LatLng?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val overlay = remember(points, livePosition) { buildOverlay(points, livePosition, density) }
    // centerOverride only changes what the camera looks at — the cone
    // estimate itself (buildOverlay's apex) always stays weighted-centroid-
    // based, regardless of this toggle.
    val center = remember(points, centerOverride) {
        centerOverride ?: Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Required by OSM's tile usage policy (identifies the app to the
            // tile server) and points the cache at app-private storage
            // rather than osmdroid's external-storage default, which would
            // otherwise need a storage permission on this app's minSdk 28.
            Configuration.getInstance().userAgentValue = ctx.packageName
            Configuration.getInstance().osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
            MapView(ctx).apply {
                // Explicit rather than relying on whatever MapView's own
                // constructor defaults to — belt-and-suspenders alongside
                // clipToBounds() above, so its measured size can't end up
                // larger than the space Compose actually gave it.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            mapView.overlays.add(overlay)
            mapView.controller.setCenter(GeoPoint(center.lat, center.lng))
            mapView.invalidate()
        },
    )
}

private fun buildOverlay(points: List<MissionPoint>, livePosition: LatLng?, density: Float): ConeOverlay {
    val apex = Geo.weightedCentroid(points.map { WeightedLatLng(it.lat, it.lng, it.weight) })
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
