package com.whyfi.app.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.util.Locale

/**
 * Layout primitives for whyfi's PDF reports (mission, LAN, GPS, overview) on A4 (595 × 842 pt): a cover band
 * on page 1, a slim running header after it, numbered section headings,
 * wrapped paragraphs, key/value blocks, stat tiles, figures and tables that
 * repeat their header row on every page they spill onto.
 */
class ReportPdf(
    /** Big line on the cover, e.g. "LAN scan report". */
    private val kind: String,
    /** The report's subject, under [kind] on the cover and in the running header. */
    private val title: String,
    private val footer: String,
) {
    private val pdf = PdfDocument()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = 0f

    val canvas: Canvas get() = page?.canvas ?: newPage().let { page!!.canvas }

    private fun text(size: Float, color: Int = INK, bold: Boolean = false, mono: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.color = color
        paint.typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }

    private fun finishPage() {
        val current = page ?: return
        val c = current.canvas
        paint.color = RULE
        c.drawRect(LEFT, 806f, RIGHT, 806.6f, paint)
        text(8f, MUTED)
        c.drawText(footer, LEFT, 818f, paint)
        val label = "Page $pageNumber"
        c.drawText(label, RIGHT - paint.measureText(label), 818f, paint)
        pdf.finishPage(current)
        page = null
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        val c = page!!.canvas
        if (pageNumber == 1) {
            paint.color = INK
            c.drawRect(0f, 0f, PAGE_W.toFloat(), 116f, paint)
            paint.color = ACCENT
            c.drawRect(0f, 112f, PAGE_W.toFloat(), 116f, paint)
            text(13f, ACCENT, bold = true)
            c.drawText("whyfi", LEFT, 43f, paint)
            text(23f, Color.WHITE, bold = true)
            c.drawText(kind, LEFT, 80f, paint)
            text(11f, Color.rgb(190, 214, 218))
            c.drawText(ellipsize(title, RIGHT - LEFT), LEFT, 100f, paint)
            y = 146f
        } else {
            paint.color = INK
            c.drawRect(0f, 0f, PAGE_W.toFloat(), 30f, paint)
            paint.color = ACCENT
            c.drawRect(0f, 30f, PAGE_W.toFloat(), 32f, paint)
            text(9f, Color.WHITE, bold = true)
            c.drawText(ellipsize("whyfi  ·  $kind  ·  $title", RIGHT - LEFT), LEFT, 20f, paint)
            y = 56f
        }
    }

    /** Starts a new page unless [height] still fits above the footer. */
    fun ensureSpace(height: Float) {
        if (page == null || y + height > BOTTOM) newPage()
    }

    fun pageBreak() = newPage()

    fun gap(height: Float) { y += height }

    fun heading(number: String, title: String) {
        ensureSpace(70f)
        y += 10f
        text(14f, ACCENT, bold = true)
        val numberWidth = if (number.isEmpty()) 0f else paint.measureText(number) + 10f
        canvas.drawText(number, LEFT, y, paint)
        text(14f, INK, bold = true)
        canvas.drawText(title, LEFT + numberWidth, y, paint)
        y += 7f
        paint.color = ACCENT
        canvas.drawRect(LEFT, y, LEFT + 42f, y + 2f, paint)
        paint.color = RULE
        canvas.drawRect(LEFT + 42f, y + 1f, RIGHT, y + 1.6f, paint)
        y += 18f
    }

    fun subheading(value: String) {
        ensureSpace(46f)
        y += 4f
        text(10.5f, INK, bold = true)
        canvas.drawText(ellipsize(value, RIGHT - LEFT), LEFT, y, paint)
        y += 15f
    }

    fun paragraph(value: String, muted: Boolean = false, size: Float = 9.5f) {
        text(size, if (muted) MUTED else INK)
        wrap(value, RIGHT - LEFT, Int.MAX_VALUE).forEach { line ->
            ensureSpace(size + 4f)
            text(size, if (muted) MUTED else INK)
            canvas.drawText(line, LEFT, y, paint)
            y += size + 4.5f
        }
        y += 3f
    }

    fun bullets(items: List<String>, muted: Boolean = true) {
        items.forEach { item ->
            text(9f, if (muted) MUTED else INK)
            val lines = wrap(item, RIGHT - LEFT - 12f, Int.MAX_VALUE)
            lines.forEachIndexed { i, line ->
                ensureSpace(13f)
                text(9f, if (muted) MUTED else INK)
                if (i == 0) canvas.drawText("•", LEFT + 2f, y, paint)
                canvas.drawText(line, LEFT + 12f, y, paint)
                y += 13f
            }
        }
        y += 3f
    }

    /** Label/value rows in two aligned columns. */
    fun keyValues(rows: List<Pair<String, String>>, labelWidth: Float = 120f) {
        rows.forEach { (label, value) ->
            text(9.5f)
            val lines = wrap(value, RIGHT - LEFT - labelWidth, 4)
            ensureSpace(lines.size * 13f + 4f)
            text(9f, MUTED)
            canvas.drawText(label, LEFT, y, paint)
            text(9.5f)
            lines.forEach { line -> canvas.drawText(line, LEFT + labelWidth, y, paint); y += 13f }
            paint.color = RULE_LIGHT
            canvas.drawRect(LEFT, y - 8.5f, RIGHT, y - 8f, paint)
            y += 3f
        }
        y += 6f
    }

    /** Key figures as tiles, three to a row. */
    fun statTiles(stats: List<ReportStat>) {
        val perRow = 3
        val gutter = 8f
        val width = (RIGHT - LEFT - gutter * (perRow - 1)) / perRow
        stats.chunked(perRow).forEach { row ->
            ensureSpace(TILE_H + gutter)
            row.forEachIndexed { i, stat ->
                val left = LEFT + i * (width + gutter)
                val box = RectF(left, y, left + width, y + TILE_H)
                paint.color = TINT
                canvas.drawRoundRect(box, 6f, 6f, paint)
                paint.color = ACCENT
                canvas.drawRect(left, y + 6f, left + 3f, y + TILE_H - 6f, paint)
                text(18f, INK, bold = true)
                canvas.drawText(ellipsize(stat.value, width - 20f), left + 12f, y + 25f, paint)
                text(8.5f, MUTED)
                canvas.drawText(ellipsize(stat.label, width - 20f), left + 12f, y + 41f, paint)
            }
            y += TILE_H + gutter
        }
        y += 6f
    }

    /** A drawing [height] tall across the text width, kept on one page, with
     * a caption under it. */
    fun figure(height: Float, caption: String, draw: (Canvas, RectF) -> Unit) {
        text(8.5f, MUTED)
        val captionLines = wrap(caption, RIGHT - LEFT, 3)
        ensureSpace(height + 8f + captionLines.size * 12f)
        val area = RectF(LEFT, y, RIGHT, y + height)
        draw(canvas, area)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.6f
        paint.color = RULE
        canvas.drawRect(area, paint)
        paint.style = Paint.Style.FILL
        y = area.bottom + 12f
        text(8.5f, MUTED)
        captionLines.forEach { canvas.drawText(it, LEFT, y, paint); y += 12f }
        y += 6f
    }

    fun table(table: ReportTable) {
        if (table.rows.isEmpty()) {
            paragraph(table.empty, muted = true)
            return
        }
        val totalWeight = table.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = table.columns.map { (RIGHT - LEFT) * it.weight / totalWeight }
        val lefts = widths.runningFold(LEFT) { acc, w -> acc + w }
        fun header() {
            paint.color = INK
            canvas.drawRect(LEFT, y, RIGHT, y + HEADER_H, paint)
            table.columns.forEachIndexed { i, column ->
                text(8f, Color.WHITE, bold = true)
                val label = ellipsize(column.header, widths[i] - CELL_PAD * 2)
                val x = if (column.kind == CellKind.NUMBER) lefts[i] + widths[i] - CELL_PAD - paint.measureText(label)
                else lefts[i] + CELL_PAD
                canvas.drawText(label, x, y + 12.5f, paint)
            }
            y += HEADER_H
        }
        ensureSpace(HEADER_H + 30f)
        header()
        table.rows.forEachIndexed { rowIndex, row ->
            val cells = table.columns.mapIndexed { i, column ->
                cellStyle(column.kind)
                if (column.kind == CellKind.BAR) emptyList() else wrap(row.getOrElse(i) { "" }, widths[i] - CELL_PAD * 2, MAX_CELL_LINES)
            }
            val height = maxOf(1, cells.maxOf { it.size }) * LINE_H + 7f
            if (y + height > BOTTOM) { newPage(); header() }
            if (rowIndex % 2 == 1) {
                paint.color = ZEBRA
                canvas.drawRect(LEFT, y, RIGHT, y + height, paint)
            }
            table.columns.forEachIndexed { i, column ->
                if (column.kind == CellKind.BAR) {
                    drawBar(row.getOrElse(i) { "0" }.toFloatOrNull() ?: 0f, lefts[i] + CELL_PAD, y + 4f, widths[i] - CELL_PAD * 2)
                } else {
                    cellStyle(column.kind)
                    cells[i].forEachIndexed { line, value ->
                        val x = if (column.kind == CellKind.NUMBER) lefts[i] + widths[i] - CELL_PAD - paint.measureText(value)
                        else lefts[i] + CELL_PAD
                        canvas.drawText(value, x, y + 11f + line * LINE_H, paint)
                    }
                }
            }
            y += height
            paint.color = RULE_LIGHT
            canvas.drawRect(LEFT, y - 0.5f, RIGHT, y, paint)
        }
        y += 6f
        table.note?.let { paragraph(it, muted = true, size = 8.5f) }
        y += 4f
    }

    private fun cellStyle(kind: CellKind) = text(8.5f, INK, mono = kind == CellKind.MONO)

    private fun drawBar(fraction: Float, left: Float, top: Float, width: Float) {
        val label = String.format(Locale.US, "%.1f %%", fraction * 100)
        text(8f, MUTED)
        val labelWidth = paint.measureText("100.0 %") + 6f
        val barWidth = width - labelWidth
        paint.color = RULE_LIGHT
        canvas.drawRect(left, top + 1f, left + barWidth, top + 9f, paint)
        paint.color = ACCENT
        canvas.drawRect(left, top + 1f, left + barWidth * fraction.coerceIn(0f, 1f), top + 9f, paint)
        text(8f, MUTED)
        canvas.drawText(label, left + width - paint.measureText(label), top + 8.5f, paint)
    }

    /** Word wrap with the current paint; the last allowed line ends in "…". */
    private fun wrap(value: String, width: Float, maxLines: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        value.split('\n').forEach { paragraph ->
            var rest = paragraph
            while (rest.isNotEmpty()) {
                var fit = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                if (fit < rest.length) {
                    val space = rest.lastIndexOf(' ', fit)
                    if (space > 0) fit = space
                }
                lines += rest.take(fit).trimEnd()
                rest = rest.drop(fit).trimStart()
            }
            if (paragraph.isEmpty()) lines += ""
        }
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines - 1) + ellipsize(lines.drop(maxLines - 1).joinToString(" "), width)
    }

    private fun ellipsize(value: String, width: Float): String {
        if (paint.measureText(value) <= width) return value
        val fit = paint.breakText(value, true, width - paint.measureText("…"), null)
        return value.take(fit).trimEnd() + "…"
    }

    fun writeTo(out: OutputStream) {
        finishPage()
        pdf.writeTo(out)
    }

    fun close() = pdf.close()

    companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val LEFT = 36f
        const val RIGHT = 559f
        private const val BOTTOM = 794f
        private const val TILE_H = 50f
        private const val HEADER_H = 18f
        private const val LINE_H = 10.5f
        private const val CELL_PAD = 4f
        private const val MAX_CELL_LINES = 4

        val INK = Color.rgb(24, 43, 51)
        val MUTED = Color.rgb(89, 109, 117)
        val ACCENT = Color.rgb(0, 153, 139)
        val RULE = Color.rgb(203, 216, 219)
        val RULE_LIGHT = Color.rgb(226, 234, 236)
        val ZEBRA = Color.rgb(244, 248, 248)
        val TINT = Color.rgb(232, 244, 242)
    }
}
