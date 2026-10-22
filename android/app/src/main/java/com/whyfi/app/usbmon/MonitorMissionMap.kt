package com.whyfi.app.usbmon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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
                controller.setCenter(GeoPoint(0.0, 0.0))
                controller.setZoom(2.0)
                tag = "none"
            }
        }, update = { map ->
            if (map.tag == "none" || (receiver != null && map.tag != "receiver")) {
                val focus = receiver?.fix?.let { GeoPoint(it.lat, it.lng) }
                    ?: estimates.values.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
                if (focus != null) {
                    map.controller.setCenter(focus)
                    map.controller.setZoom(17.0)
                    map.tag = if (receiver != null) "receiver" else "estimate"
                }
            }
            map.overlays.clear()
            map.overlays.add(MonitorLocationOverlay(receiver, estimates, selectedMac, selectedSamples.takeLast(800)))
            map.invalidate()
        }) }
        Text("© OpenStreetMap contributors · blue: phone · teal: estimate/uncertainty · red: selected signal fit",
            style = MaterialTheme.typography.bodySmall)
    }
}

private class MonitorLocationOverlay(
    private val receiver: MonitorSelectedLocation?,
    private val estimates: Map<String, MonitorEstimate>,
    private val selectedMac: String?,
    private val samples: List<MonitorGeoSample>,
) : Overlay() {
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
        estimates[selectedMac]?.heat?.forEach { cell ->
            if (cell.relativeFit < 0.08) return@forEach
            val p = point(cell.lat, cell.lng)
            paint.color = Color.argb((cell.relativeFit * 140).toInt().coerceIn(0, 140), 220, 48, 42)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 12f, paint)
        }
        samples.forEach { sample ->
            val p = point(sample.lat, sample.lng)
            paint.color = Color.argb(150, 0, 94, 158)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 5f, paint)
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
            paint.textSize = 32f
            paint.setShadowLayer(3f, 1f, 1f, Color.WHITE)
            canvas.drawText(mac, p.x + 15f, p.y - 12f, paint)
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
        Text("© OpenStreetMap contributors · blue: receiver · teal: estimate/uncertainty · red: relative RSSI fit",
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
        estimate.heat.forEach { cell ->
            if (cell.relativeFit < 0.08) return@forEach
            val point = at(cell.lat, cell.lng)
            paint.color = Color.argb((cell.relativeFit * 140).toInt().coerceIn(0, 140), 220, 48, 42)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 12f, paint)
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
