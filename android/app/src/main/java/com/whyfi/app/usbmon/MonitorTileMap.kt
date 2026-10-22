package com.whyfi.app.usbmon

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private fun zoomFor(estimate: MonitorEstimate, width: Float, height: Float): Int {
        val needed = max(220.0, max(estimate.uncertaintyM * 3.5, estimate.receiverSpreadM * 2))
        return (19 downTo 3).firstOrNull { zoom ->
            min(width, height) * metresPerPixel(estimate.lat, zoom) >= needed
        } ?: 3
    }

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
        var tilesDrawn = 0
        var allowNetwork = true
        canvas.save()
        canvas.clipRect(area)
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
        // Relative model fit: red cells show where the observed RSSI pattern
        // fits best. These are deliberately not labeled as probabilities.
        estimate.heat.forEach { cell ->
            if (cell.relativeFit < 0.08) return@forEach
            paint.color = Color.argb((min(0.68, cell.relativeFit * 0.55) * 255).toInt(), 220, 48, 42)
            canvas.drawCircle(px(cell.lng), py(cell.lat), 9f, paint)
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
        if (tilesDrawn == 0) {
            paint.color = Color.rgb(70, 91, 99)
            paint.textSize = 11f
            canvas.drawText("Map tiles unavailable offline; GPS overlay retained", area.left + 12f, area.top + 22f, paint)
        }
        paint.color = Color.WHITE
        canvas.drawRect(area.left, area.bottom - 18f, area.right, area.bottom, paint)
        paint.color = Color.rgb(35, 55, 60)
        paint.textSize = 9f
        canvas.drawText("© OpenStreetMap contributors  |  blue: receiver  |  teal: estimate/uncertainty  |  red: relative RSSI fit",
            area.left + 5f, area.bottom - 5f, paint)
        canvas.restore()
        return tilesDrawn
    }
}
