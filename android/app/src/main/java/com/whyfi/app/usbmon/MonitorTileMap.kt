package com.whyfi.app.usbmon

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** The same OSM standard tile layer as the app's osmdroid map, for the PDF. */
internal object MonitorTileMap {
    private const val TILE_SIZE = 256.0
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

    private fun pixelX(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * TILE_SIZE * (1 shl zoom)

    private fun pixelY(lat: Double, zoom: Int): Double {
        val radians = lat.coerceIn(-85.0, 85.0) * PI / 180
        return (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * TILE_SIZE * (1 shl zoom)
    }

    private fun metresPerPixel(lat: Double, zoom: Int): Double =
        156543.03392 * max(cos(lat * PI / 180), 0.01) / (1 shl zoom)

    private fun zoomFor(estimate: MonitorEstimate, width: Float, height: Float): Int =
        zoomForSpan(estimate.lat, max(220.0, max(estimate.uncertaintyM * 3.5, estimate.receiverSpreadM * 2)), width, height)

    /** Closest zoom at which [neededM] fits the shorter side of the area. */
    internal fun zoomForSpan(lat: Double, neededM: Double, width: Float, height: Float): Int =
        (19 downTo 3).firstOrNull { zoom -> min(width, height) * metresPerPixel(lat, zoom) >= neededM } ?: 3

    private data class TileResult(val file: File?, val networkAvailable: Boolean)

    private fun cachedTile(context: Context, zoom: Int, x: Int, y: Int, allowNetwork: Boolean): TileResult {
        val file = File(context.cacheDir, "monitor-map-tiles/$zoom/$x/$y.png")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < SEVEN_DAYS_MS)
            return TileResult(file, allowNetwork)
        if (!allowNetwork) return TileResult(file.takeIf { it.isFile }, false)
        // Only tiles actually visible in the report are requested. No route
        // prefetch, and stale files remain usable when the phone is offline.
        return try {
            val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "whyfi-android/monitor-report (+https://github.com/inv1sible/whyfi)")
            connection.connectTimeout = 1800
            connection.readTimeout = 1800
            if (file.isFile) connection.ifModifiedSince = file.lastModified()
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    file.setLastModified(System.currentTimeMillis())
                    TileResult(file, true)
                } else if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    TileResult(if (tmp.renameTo(file)) file else file.takeIf { it.isFile }, true)
                } else TileResult(file.takeIf { it.isFile }, false)
            } finally { connection.disconnect() }
        } catch (_: Exception) { TileResult(file.takeIf { it.isFile }, false) }
    }

    /** Returns how many real map tiles could be drawn. All overlays remain when offline. */
    fun draw(context: Context, canvas: Canvas, area: RectF, estimate: MonitorEstimate,
             samples: List<MonitorGeoSample>): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 239, 240) }
        canvas.drawRect(area, paint)
        val zoom = zoomFor(estimate, area.width(), area.height())
        val centerX = pixelX(estimate.lng, zoom)
        val centerY = pixelY(estimate.lat, zoom)
        val left = centerX - area.width() / 2
        val top = centerY - area.height() / 2
        fun px(lng: Double) = area.left + (pixelX(lng, zoom) - left).toFloat()
        fun py(lat: Double) = area.top + (pixelY(lat, zoom) - top).toFloat()
        canvas.save()
        canvas.clipRect(area)
        val tilesDrawn = drawTiles(context, canvas, area, left, top, zoom, paint)
        MonitorSignalCones.draw(canvas, estimate, samples) { lat, lng ->
            PointF(px(lng), py(lat))
        }
        samples.forEach { sample ->
            paint.color = Color.WHITE
            canvas.drawCircle(px(sample.lng), py(sample.lat), 4f, paint)
            paint.color = Color.rgb(0, 94, 158)
            canvas.drawCircle(px(sample.lng), py(sample.lat), 2.7f, paint)
        }
        paint.color = Color.rgb(0, 125, 113)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(px(estimate.lng), py(estimate.lat),
            (estimate.uncertaintyM / metresPerPixel(estimate.lat, zoom)).toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(px(estimate.lng), py(estimate.lat), 6f, paint)
        paint.color = Color.rgb(0, 110, 97)
        canvas.drawCircle(px(estimate.lng), py(estimate.lat), 4f, paint)
        legendBar(canvas, area, tilesDrawn, "blue: receiver  |  teal: estimate  |  green→red: sampled RSSI cones", paint)
        canvas.restore()
        return tilesDrawn
    }

    /** Draws the OSM tiles under [area] whose top-left world pixel is
     * ([left], [top]); returns how many could be drawn. */
    private fun drawTiles(context: Context, canvas: Canvas, area: RectF, left: Double, top: Double, zoom: Int, paint: Paint): Int {
        var tilesDrawn = 0
        var allowNetwork = true
        val worldTiles = 1 shl zoom
        for (tx in floor(left / TILE_SIZE).toInt()..floor((left + area.width()) / TILE_SIZE).toInt()) {
            for (ty in floor(top / TILE_SIZE).toInt()..floor((top + area.height()) / TILE_SIZE).toInt()) {
                if (ty !in 0 until worldTiles) continue
                val tile = cachedTile(context, zoom, ((tx % worldTiles) + worldTiles) % worldTiles, ty, allowNetwork)
                allowNetwork = tile.networkAvailable
                val bitmap = tile.file?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: continue
                val x = area.left + (tx * TILE_SIZE - left).toFloat()
                val y = area.top + (ty * TILE_SIZE - top).toFloat()
                canvas.drawBitmap(bitmap, null, RectF(x, y, x + TILE_SIZE.toFloat(), y + TILE_SIZE.toFloat()), paint)
                bitmap.recycle()
                tilesDrawn++
            }
        }
        return tilesDrawn
    }

    private fun legendBar(canvas: Canvas, area: RectF, tilesDrawn: Int, legend: String, paint: Paint) {
        if (tilesDrawn == 0) {
            paint.color = Color.rgb(70, 91, 99)
            paint.textSize = 11f
            canvas.drawText("Map tiles unavailable offline; GPS overlay retained", area.left + 12f, area.top + 22f, paint)
        }
        paint.color = Color.WHITE
        canvas.drawRect(area.left, area.bottom - 18f, area.right, area.bottom, paint)
        paint.color = Color.rgb(35, 55, 60)
        paint.textSize = 9f
        canvas.drawText("© OpenStreetMap contributors  |  $legend", area.left + 5f, area.bottom - 5f, paint)
    }

    /**
     * The mission at a glance: the phone's route (receiver fixes, blue) and
     * each pinned device's estimate as a numbered marker with its
     * uncertainty ring, numbered like the report's positions table.
     */
    fun drawOverview(
        context: Context,
        canvas: Canvas,
        area: RectF,
        route: List<Pair<Double, Double>>,
        markers: List<MonitorReportContent.MapMarker>,
    ): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 239, 240) }
        canvas.drawRect(area, paint)
        val bounds = MonitorReportContent.bounds(route, markers) ?: return 0
        val zoom = zoomForSpan(bounds.centerLat, max(200.0, bounds.spanM * 1.3), area.width(), area.height() - 18f)
        val left = pixelX(bounds.centerLng, zoom) - area.width() / 2
        val top = pixelY(bounds.centerLat, zoom) - (area.height() - 18f) / 2
        fun px(lng: Double) = area.left + (pixelX(lng, zoom) - left).toFloat()
        fun py(lat: Double) = area.top + (pixelY(lat, zoom) - top).toFloat()
        canvas.save()
        canvas.clipRect(area)
        val tilesDrawn = drawTiles(context, canvas, area, left, top, zoom, paint)
        val path = MonitorReportContent.thinRoute(route)
        if (path.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 4.5f
            paint.color = Color.WHITE
            val line = android.graphics.Path().apply {
                moveTo(px(path[0].second), py(path[0].first))
                path.drop(1).forEach { lineTo(px(it.second), py(it.first)) }
            }
            canvas.drawPath(line, paint)
            paint.strokeWidth = 2.4f
            paint.color = Color.rgb(0, 94, 158)
            canvas.drawPath(line, paint)
            paint.style = Paint.Style.FILL
        }
        route.firstOrNull()?.let { (lat, lng) -> endpoint(canvas, px(lng), py(lat), Color.rgb(0, 94, 158), paint) }
        route.lastOrNull()?.takeIf { route.size > 1 }?.let { (lat, lng) -> endpoint(canvas, px(lng), py(lat), Color.rgb(24, 43, 51), paint) }
        markers.forEach { marker ->
            val x = px(marker.lng)
            val y = py(marker.lat)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Color.argb(200, 0, 125, 113)
            canvas.drawCircle(x, y, (marker.uncertaintyM / metresPerPixel(marker.lat, zoom)).toFloat().coerceAtLeast(10f), paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 9.5f, paint)
            paint.color = Color.rgb(0, 110, 97)
            canvas.drawCircle(x, y, 8f, paint)
            paint.color = Color.WHITE
            paint.textSize = 8.5f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val label = marker.number.toString()
            canvas.drawText(label, x - paint.measureText(label) / 2, y + 3f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
        }
        legendBar(canvas, area, tilesDrawn, "blue: phone route (start ●, end ●)  |  numbers: pinned devices, ring = uncertainty", paint)
        canvas.restore()
        return tilesDrawn
    }

    private fun endpoint(canvas: Canvas, x: Float, y: Float, color: Int, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 5f, paint)
        paint.color = color
        canvas.drawCircle(x, y, 3.5f, paint)
    }
}

