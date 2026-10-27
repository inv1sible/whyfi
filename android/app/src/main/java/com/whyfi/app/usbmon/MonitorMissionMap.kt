package com.whyfi.app.usbmon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Live receiver position plus every pinned transmitter estimate. */
@Composable
internal fun MonitorLocationMap(
    receiver: MonitorSelectedLocation?,
    estimates: Map<String, MonitorEstimate>,
    selectedMac: String?,
    selectedSamples: List<MonitorGeoSample>,
    labels: Map<String, String>,
    modifier: Modifier = Modifier.height(280.dp),
    mapKey: String = "",
    recenterToken: String = "",
    focusToken: String = "",
    followReceiver: Boolean = false,
    receiverPath: List<MonitorReceiverFix> = emptyList(),
    onEstimateTap: (String) -> Unit = {},
    onUserPan: () -> Unit = {},
) {
    val panAction = rememberUpdatedState(onUserPan)
    Column(modifier.clipToBounds()) {
        key(mapKey) { AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(), factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid")
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                var downX = 0f
                var downY = 0f
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(
                        event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
                    if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                        kotlin.math.hypot(event.x - downX, event.y - downY) > 8f * resources.displayMetrics.density)
                        panAction.value()
                    false
                }
                controller.setCenter(GeoPoint(0.0, 0.0))
                controller.setZoom(2.0)
                tag = MapCenterState()
            }
        }, update = { map ->
            val centerState = map.tag as? MapCenterState ?: MapCenterState()
            val targetFocus = estimates[selectedMac]?.let { GeoPoint(it.lat, it.lng) }
            val focus = targetFocus
                ?: receiver?.fix?.let { GeoPoint(it.lat, it.lng) }
                ?: receiverPath.lastOrNull()?.let { GeoPoint(it.lat, it.lng) }
                ?: estimates.values.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
            // A long mission may span a large area; open near its latest measurements.
            val recentSamples = selectedSamples.takeLast(16)
            val measurementPoints = recentSamples.map { it.lat to it.lng } +
                (if (selectedMac == null && recentSamples.isEmpty())
                    receiverPath.takeLast(16).map { it.lat to it.lng } else emptyList()) +
                (if (selectedMac != null) estimates[selectedMac]?.let { listOf(it.lat to it.lng) }.orEmpty()
                else if (recentSamples.isEmpty()) estimates.values.map { it.lat to it.lng } else emptyList())
            val fitPoints = measurementPoints.ifEmpty {
                receiver?.fix?.let { listOf(it.lat to it.lng) }.orEmpty()
            }
            val fit = monitorMapViewport(fitPoints,
                map.width.takeIf { it > 0 } ?: map.resources.displayMetrics.widthPixels,
                map.height.takeIf { it > 0 } ?: map.resources.displayMetrics.heightPixels / 2)
            val shouldFit = !followReceiver && (!centerState.initialized || centerState.selectedMac != selectedMac ||
                (!centerState.fittedMeasurements && selectedSamples.isNotEmpty())
            )
            if (targetFocus != null && (!centerState.initialized || centerState.selectedMac != selectedMac)) {
                map.controller.setCenter(targetFocus)
                if (!centerState.initialized) map.controller.setZoom(fit?.zoom ?: 17.0)
                map.tag = centerState.copy(initialized = true, selectedMac = selectedMac,
                    focusedTarget = selectedMac, recenterToken = recenterToken)
            } else if (followReceiver && !centerState.initialized && receiver != null) {
                map.controller.setCenter(GeoPoint(receiver.fix.lat, receiver.fix.lng))
                map.controller.setZoom(17.0)
                map.tag = centerState.copy(initialized = true, centeredOnReceiver = true,
                    recenterToken = recenterToken, selectedMac = selectedMac)
            } else if (fit != null && shouldFit) {
                map.controller.setCenter(GeoPoint(fit.lat, fit.lng))
                map.controller.setZoom(fit.zoom)
                map.tag = MapCenterState(true, receiver != null, recenterToken, selectedMac,
                    focusToken, if (targetFocus != null) selectedMac else null,
                    selectedSamples.isNotEmpty())
            } else if (focus != null && centerState.recenterToken != recenterToken) {
                val center = receiver?.fix?.let { GeoPoint(it.lat, it.lng) } ?: focus
                map.controller.setCenter(center)
                map.tag = centerState.copy(recenterToken = recenterToken, focusToken = focusToken,
                    selectedMac = selectedMac)
            } else if (focus != null && (centerState.selectedMac != selectedMac ||
                centerState.focusToken != focusToken ||
                (targetFocus != null && centerState.focusedTarget != selectedMac))) {
                map.controller.setCenter(focus)
                map.tag = centerState.copy(selectedMac = selectedMac, focusToken = focusToken,
                    focusedTarget = if (targetFocus != null) selectedMac else null)
            } else if (receiver != null && !centerState.centeredOnReceiver && selectedMac == null) {
                map.controller.setCenter(GeoPoint(receiver.fix.lat, receiver.fix.lng))
                map.tag = centerState.copy(centeredOnReceiver = true)
            }
            if (followReceiver && receiver != null) {
                map.controller.setCenter(GeoPoint(receiver.fix.lat, receiver.fix.lng))
            }
            map.overlays.clear()
            map.overlays.add(MonitorLocationOverlay(receiver, estimates, selectedMac,
                selectedSamples, labels, receiverPath.takeLast(800), onEstimateTap))
            map.invalidate()
        }) }
        Text("© OpenStreetMap contributors · blue: phone/measurements with accuracy · teal: estimate ±uncertainty · cones: illustrative RSSI, not bearing · clients may move",
            style = MaterialTheme.typography.bodySmall)
    }
}

private data class MapCenterState(val initialized: Boolean = false,
                                  val centeredOnReceiver: Boolean = false, val recenterToken: String = "",
                                  val selectedMac: String? = null, val focusToken: String = "",
                                  val focusedTarget: String? = null, val fittedMeasurements: Boolean = false)

private class MonitorLocationOverlay(
    private val receiver: MonitorSelectedLocation?,
    private val estimates: Map<String, MonitorEstimate>,
    private val selectedMac: String?,
    private val samples: List<MonitorGeoSample>,
    private val labels: Map<String, String>,
    private val receiverPath: List<MonitorReceiverFix>,
    private val onEstimateTap: (String) -> Unit,
) : Overlay() {
    private val measurements = samples.groupBy { it.mac }.filterKeys { it in estimates }.mapValues { (_, readings) ->
        readings.groupBy { (it.lat / 0.00005).roundToInt() to (it.lng / 0.00005).roundToInt() }
            .values.map { it.last() }.takeLast(80)
    }
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val nearest = estimates.minByOrNull { (_, estimate) ->
            val point = projection.toPixels(GeoPoint(estimate.lat, estimate.lng), null)
            kotlin.math.hypot((point.x - e.x).toDouble(), (point.y - e.y).toDouble())
        } ?: return false
        val point = projection.toPixels(GeoPoint(nearest.value.lat, nearest.value.lng), null)
        val distance = kotlin.math.hypot((point.x - e.x).toDouble(), (point.y - e.y).toDouble())
        if (distance > 36 * mapView.resources.displayMetrics.density) return false
        onEstimateTap(nearest.key)
        return true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val projection = mapView.projection
        fun point(lat: Double, lng: Double) = projection.toPixels(GeoPoint(lat, lng), null)
        fun circle(lat: Double, lng: Double, radiusM: Double, color: Int) {
            val center = point(lat, lng)
            val edge = point(lat, lng + radiusM / (111_320.0 * max(cos(lat * PI / 180), 0.01)))
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), abs(edge.x - center.x).toFloat(), paint)
            paint.style = Paint.Style.FILL
        }
        if (receiverPath.isNotEmpty()) {
            val path = Path()
            receiverPath.forEachIndexed { index, fix ->
                val p = point(fix.lat, fix.lng)
                if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                else path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            paint.color = Color.argb(170, 0, 94, 158)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
        estimates.forEach { (mac, estimate) ->
            val deviceSamples = measurements[mac].orEmpty().takeLast(80)
            MonitorSignalCones.draw(canvas, estimate, deviceSamples) { lat, lng ->
                val p = point(lat, lng)
                PointF(p.x.toFloat(), p.y.toFloat())
            }
            deviceSamples.forEach { sample ->
                circle(sample.lat, sample.lng,
                    MonitorLocationPolicy.effectiveAccuracy(sample.gpsAccuracyM, sample.fixAgeMs, sample.speedMps),
                    Color.argb(65, 0, 94, 158))
                val p = point(sample.lat, sample.lng)
                paint.color = Color.argb(150, 0, 94, 158)
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 5f, paint)
            }
        }
        receiver?.let { selected ->
            val fix = selected.fix
            circle(fix.lat, fix.lng, selected.effectiveAccuracyM, Color.rgb(0, 94, 158))
            val p = point(fix.lat, fix.lng)
            paint.color = Color.WHITE
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 12f, paint)
            paint.color = Color.rgb(0, 94, 158)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f, paint)
        }
        estimates.forEach { (mac, estimate) ->
            circle(estimate.lat, estimate.lng, estimate.uncertaintyM, Color.rgb(0, 125, 113))
            val p = point(estimate.lat, estimate.lng)
            paint.color = Color.WHITE
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, paint)
            paint.color = Color.rgb(0, 110, 97)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 8f, paint)
            val fullMac = mac.uppercase()
            val name = (labels[mac]?.removeSuffix(" · $fullMac")?.take(20) ?: "Device") +
                " ±${estimate.uncertaintyM.toInt()}m"
            paint.textSize = 27f
            paint.setShadowLayer(3f, 1f, 1f, Color.WHITE)
            val labelWidth = max(paint.measureText(name), paint.measureText(fullMac))
            val textX = (if (p.x + 16 + labelWidth > mapView.width) p.x - 16 - labelWidth else p.x + 16f)
                .coerceIn(0f, max(0f, mapView.width - labelWidth))
            canvas.drawText(name, textX, p.y - 10f, paint)
            canvas.drawText(fullMac, textX, p.y + 20f, paint)
            paint.clearShadowLayer()
        }
    }
}

/** Live mission review using the osmdroid tile map already used by Mission. */
@Composable
internal fun MonitorMissionMap(
    estimate: MonitorEstimate,
    samples: List<MonitorGeoSample>,
    modifier: Modifier = Modifier.height(280.dp),
    mapKey: String = "",
) {
    Column(modifier) {
        key(mapKey) { AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid")
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(
                        event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    false
                }
                controller.setCenter(GeoPoint(estimate.lat, estimate.lng))
                val radius = max(estimate.uncertaintyM, estimate.receiverSpreadM)
                controller.setZoom(when {
                    radius > 600 -> 14.0
                    radius > 300 -> 15.0
                    radius > 140 -> 16.0
                    radius > 60 -> 17.0
                    else -> 18.0
                })
            }
        }, update = { map ->
            map.overlays.clear()
            map.overlays.add(MonitorEstimateOverlay(estimate, samples.takeLast(800)))
            map.invalidate()
        }) }
        Text("© OpenStreetMap contributors · blue: receiver · teal: estimate/uncertainty · green→orange/red: sampled RSSI cones",
            style = MaterialTheme.typography.bodySmall)
    }
}

private class MonitorEstimateOverlay(
    private val estimate: MonitorEstimate,
    private val samples: List<MonitorGeoSample>,
) : Overlay() {
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val projection = mapView.projection
        fun at(lat: Double, lng: Double) = projection.toPixels(GeoPoint(lat, lng), null)
        MonitorSignalCones.draw(canvas, estimate, samples) { lat, lng ->
            val p = at(lat, lng)
            PointF(p.x.toFloat(), p.y.toFloat())
        }
        samples.forEach { sample ->
            val point = at(sample.lat, sample.lng)
            paint.color = Color.WHITE
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 7f, paint)
            paint.color = Color.rgb(0, 94, 158)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 5f, paint)
        }
        val center = at(estimate.lat, estimate.lng)
        val edgeLng = estimate.lng + estimate.uncertaintyM /
            (111_320.0 * max(cos(estimate.lat * PI / 180.0), 0.01))
        val edge = at(estimate.lat, edgeLng)
        paint.color = Color.rgb(0, 125, 113)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(),
            kotlin.math.abs(edge.x - center.x).toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 11f, paint)
        paint.color = Color.rgb(0, 110, 97)
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 8f, paint)
    }
}
