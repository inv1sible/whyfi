package com.whyfi.app.mission

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.max
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

sealed interface MissionShape {
    val polygon: List<LatLng>

    /** The live "you are here" shape (see MissionController.start) is drawn
     * emphasized: full opacity plus an outline, while every other shape
     * fades — see ConeOverlay's fadeNonEmphasized handling. Historical
     * shapes are never emphasized when there's no live tracking. */
    val emphasized: Boolean

    /** A gradient cone from the estimated AP position (green) to one
     * reading's own signal-strength color at its far edge — apex is always
     * `polygon[0]` (see Geo.conePolygon's contract). */
    data class Cone(
        override val polygon: List<LatLng>,
        val edgeColorArgb: Int,
        override val emphasized: Boolean = false,
    ) : MissionShape

    /** Flat-colored fallback for the rare case a cone can't be drawn — see
     * MissionController.FALLBACK_CIRCLE_RADIUS_M. */
    data class Circle(
        override val polygon: List<LatLng>,
        val fillColorArgb: Int,
        override val emphasized: Boolean = false,
    ) : MissionShape
}

/**
 * Renders Mission view's gradient cones/circles plus the estimated AP
 * position marker and raw reading dots — the native equivalent of the PWA's
 * Solo mode (frontend/src/coverageConfig.ts's soloShapes +
 * frontend/src/components/RadioMap.tsx's gradient rendering).
 *
 * Simpler here than there: android.graphics.RadialGradient takes real pixel
 * coordinates directly, so there's no need for the PWA's
 * fractional-bounding-box-position workaround (that only exists to work
 * around SVG's objectBoundingBox gradient units) — a cone's own projected
 * apex pixel IS the gradient center.
 */
class ConeOverlay(
    private val shapes: List<MissionShape>,
    private val apex: LatLng?,
    private val readingPoints: List<LatLng>,
    /** True while live tracking has a position fix — fades every
     * non-emphasized shape so the live "you are here" cone (the one
     * emphasized shape, appended to [shapes] by the caller) visually pops
     * against the historical readings behind it. */
    private val fadeNonEmphasized: Boolean,
    private val strokeWidthPx: Float = 6f,
) : Overlay() {

    private val shapePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        color = EMPHASIS_STROKE_ARGB
        strokeWidth = strokeWidthPx
    }
    private val apexPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = ESTIMATED_POSITION_GREEN_ARGB
    }

    // Slate — a plain reading dot, not signal-colored (the shape it feeds
    // already carries that color).
    private val readingPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = 0xFF64748B.toInt()
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection

        shapes.forEach { shape ->
            val screenPoints = shape.polygon.map { projection.toPixels(GeoPoint(it.lat, it.lng), null) }
            if (screenPoints.size < 3) return@forEach
            val path = Path().apply {
                moveTo(screenPoints[0].x.toFloat(), screenPoints[0].y.toFloat())
                screenPoints.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                close()
            }
            val fade = fadeNonEmphasized && !shape.emphasized
            when (shape) {
                is MissionShape.Cone -> {
                    val apexPx = screenPoints[0]
                    val width = screenPoints.maxOf { it.x } - screenPoints.minOf { it.x }
                    val height = screenPoints.maxOf { it.y } - screenPoints.minOf { it.y }
                    val radius = 1.5f * max(width, height).coerceAtLeast(1)
                    val fadeMultiplier = if (fade) FADE_MULTIPLIER else 1f
                    shapePaint.shader = RadialGradient(
                        apexPx.x.toFloat(),
                        apexPx.y.toFloat(),
                        radius,
                        intArrayOf(
                            withAlpha(ESTIMATED_POSITION_GREEN_ARGB, 0.85f * fadeMultiplier),
                            withAlpha(shape.edgeColorArgb, 0.45f * fadeMultiplier),
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawPath(path, shapePaint)
                    shapePaint.shader = null
                }
                is MissionShape.Circle -> {
                    shapePaint.shader = null
                    val fadeMultiplier = if (fade) FADE_MULTIPLIER else 1f
                    shapePaint.color = withAlpha(shape.fillColorArgb, 0.4f * fadeMultiplier)
                    canvas.drawPath(path, shapePaint)
                }
            }
            if (shape.emphasized) canvas.drawPath(path, strokePaint)
        }

        readingPoints.forEach { point ->
            val px = projection.toPixels(GeoPoint(point.lat, point.lng), null)
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), 5f, readingPaint)
        }

        apex?.let {
            val px = projection.toPixels(GeoPoint(it.lat, it.lng), null)
            canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), 10f, apexPaint)
        }
    }

    companion object {
        const val ESTIMATED_POSITION_GREEN_ARGB = 0xFF22C55E.toInt()
        const val EMPHASIS_STROKE_ARGB = 0xFFFFFFFF.toInt()
        private const val FADE_MULTIPLIER = 0.4f

        private fun withAlpha(argb: Int, alphaFraction: Float): Int {
            val alpha = (alphaFraction * 255).toInt().coerceIn(0, 255)
            return (argb and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}
