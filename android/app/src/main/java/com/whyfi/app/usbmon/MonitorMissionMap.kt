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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

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
