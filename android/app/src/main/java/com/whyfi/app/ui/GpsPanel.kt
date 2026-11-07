package com.whyfi.app.ui

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whyfi.app.report.GpsReportContent
import com.whyfi.app.report.PhoneReports
import com.whyfi.app.scan.LocationTrack
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.scan.TrackPoint
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val EMPTY_TRACK = MutableStateFlow<List<TrackPoint>>(emptyList())

/**
 * Top of the GPS (satellites) screen: a map with this session's location
 * path, the latest fix and its accuracy ring, a one-line summary and the
 * GPS report. Location stays on while this is visible (watchLocation).
 */
@Composable
fun GpsPanel(service: ScanForegroundService?, uiState: ScanUiState) {
    val context = LocalContext.current
    DisposableEffect(service) {
        service?.watchLocation(true)
        onDispose { service?.watchLocation(false) }
    }
    val track by (service?.locationTrack ?: EMPTY_TRACK).collectAsState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1_000) } }
    val last = track.lastOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrackMap(track, follow, onUserPan = { follow = false },
            modifier = Modifier.fillMaxWidth().height(240.dp).clipToBounds())
        Text(
            if (last == null) "Waiting for a location fix… (Location must be on)"
            else listOfNotNull(
                last.accuracyM?.let { "±%.0f m".format(it) },
                last.provider,
                "${((nowMs - last.atMs) / 1000).coerceAtLeast(0)} s ago",
                "${track.size} points",
                GpsReportContent.distance(LocationTrack.lengthM(track)),
                LocationTrack.medianAccuracyM(track)?.let { "median ±%.0f m".format(it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(follow, { follow = !follow }, label = { Text("Follow") })
            TextButton(onClick = { service?.clearLocationTrack() }, enabled = track.isNotEmpty()) { Text("Clear path") }
            Spacer(Modifier.weight(1f))
            ReportButton(enabled = track.isNotEmpty() || uiState.latestPass != null) {
                PhoneReports.gps(context, uiState, track)
            }
        }
    }
}

@Composable
private fun TrackMap(track: List<TrackPoint>, follow: Boolean, onUserPan: () -> Unit, modifier: Modifier) {
    val pan = rememberUpdatedState(onUserPan)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            Configuration.getInstance().osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
            MapView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(17.0)
                setOnTouchListener { view, event ->
                    // Keep the page from scrolling while the map is dragged.
                    view.parent?.requestDisallowInterceptTouchEvent(
                        event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) pan.value()
                    false
                }
            }
        },
        update = { map ->
            map.overlays.clear()
            if (track.size > 1) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(track.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = android.graphics.Color.rgb(0, 94, 158)
                    outlinePaint.strokeWidth = 6f
                })
            }
            track.lastOrNull()?.let { last ->
                val center = GeoPoint(last.lat, last.lng)
                last.accuracyM?.takeIf { it > 0 }?.let { radius ->
                    map.overlays.add(Polygon(map).apply {
                        points = Polygon.pointsAsCircle(center, radius)
                        fillPaint.color = android.graphics.Color.argb(50, 0, 153, 139)
                        outlinePaint.color = android.graphics.Color.argb(200, 0, 125, 113)
                        outlinePaint.strokeWidth = 3f
                    })
                }
                map.overlays.add(Polygon(map).apply {
                    points = Polygon.pointsAsCircle(center, 1.5)
                    fillPaint.color = android.graphics.Color.rgb(0, 110, 97)
                    outlinePaint.color = android.graphics.Color.WHITE
                    outlinePaint.strokeWidth = 4f
                })
                if (follow) map.controller.setCenter(center)
            }
            map.invalidate()
        },
    )
}
