package com.whyfi.app.usbmon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Illustrative RSSI coverage from an estimated transmitter toward observed receiver positions. */
internal object MonitorSignalCones {
    fun draw(canvas: Canvas, estimate: MonitorEstimate, samples: List<MonitorGeoSample>,
             point: (Double, Double) -> PointF) {
        val origin = point(estimate.lat, estimate.lng)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        // Repeated packets at one location should not darken the same cone indefinitely.
        val places = samples.filter { it.lat.isFinite() && it.lng.isFinite() && it.rssi in -110..-5 }
            .groupBy { (it.lat / 0.00005).roundToInt() to (it.lng / 0.00005).roundToInt() }
            .values.map { group -> group.last().copy(rssi = group.map { it.rssi }.sorted()[group.size / 2]) }
            .takeLast(80)
        places.forEach { sample ->
            val end = point(sample.lat, sample.lng)
            val dx = end.x - origin.x
            val dy = end.y - origin.y
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (length < 6f) return@forEach
            val halfWidth = (length * 0.18f).coerceIn(4f, 60f)
            val sidewaysX = -dy / length * halfWidth
            val sidewaysY = dx / length * halfWidth
            val cone = Path().apply {
                moveTo(origin.x, origin.y)
                lineTo(end.x + sidewaysX, end.y + sidewaysY)
                lineTo(end.x - sidewaysX, end.y - sidewaysY)
                close()
            }
            val weak = ((-sample.rssi - 50) / 45f).coerceIn(0f, 1f)
            val endColor = Color.argb(115, 235 - (24 * weak).toInt(),
                151 - (104 * weak).toInt(), 28 + (19 * weak).toInt())
            paint.shader = LinearGradient(origin.x, origin.y, end.x, end.y,
                intArrayOf(Color.argb(75, 27, 169, 92), Color.argb(95, 240, 157, 31), endColor),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(cone, paint)
        }
        paint.shader = null
    }
}
