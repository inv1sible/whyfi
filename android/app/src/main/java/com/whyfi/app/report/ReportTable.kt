package com.whyfi.app.report

import java.util.Locale

/** How a table cell is drawn: TEXT left-aligned, MONO for addresses,
 * NUMBER right-aligned, BAR a share bar (the cell holds a 0–1 fraction). */
enum class CellKind { TEXT, MONO, NUMBER, BAR }

data class ReportColumn(val header: String, val weight: Float, val kind: CellKind = CellKind.TEXT)

/** [empty] is printed instead of the table when there are no rows. */
data class ReportTable(val columns: List<ReportColumn>, val rows: List<List<String>>, val empty: String, val note: String? = null)

data class ReportStat(val value: String, val label: String)

object ReportFormat {
    fun number(value: Long): String = String.format(Locale.US, "%,d", value)
    fun coord(value: Double): String = String.format(Locale.US, "%.6f", value)
    fun metres(value: Double): String = String.format(Locale.US, "%.0f m", value)
    fun fraction(value: Double): String = String.format(Locale.US, "%.4f", value)

    /** "45 s", "12 min", "2 h 28 min", "3 d 4 h". */
    fun duration(ms: Long): String {
        val s = (ms / 1_000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3_600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3_600} h ${s % 3_600 / 60} min"
            else -> "${s / 86_400} d ${s % 86_400 / 3_600} h"
        }
    }

    private fun dayOf(ms: Long): Int = java.util.Calendar.getInstance().run { timeInMillis = ms; get(java.util.Calendar.YEAR) * 400 + get(java.util.Calendar.DAY_OF_YEAR) }

    /** Local clock time; with the date as well when [times] fall on more than one day. 0 = "–". */
    fun clock(times: Collection<Long>): (Long) -> String {
        val days = times.filter { it > 0 }.map(::dayOf).distinct().size
        val format = if (days > 1) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
            else java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
        return { ms -> if (ms > 0) format.format(java.util.Date(ms)) else "–" }
    }

    /** "11 Nov 2026, 07:12:05 – 09:40:10 (2 h 28 min)"; the end gets its date only if it is another day. */
    fun period(startMs: Long, endMs: Long): String {
        if (startMs <= 0) return "–"
        val full = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
        val end = endMs.coerceAtLeast(startMs)
        val endText = if (dayOf(end) == dayOf(startMs)) java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(end))
            else full.format(java.util.Date(end))
        return "${full.format(java.util.Date(startMs))} – $endText (${duration(end - startMs)})"
    }

    /** The app's UTC ISO stamps ("2026-11-11T07:12:33Z") as local date and time; anything else unchanged. */
    fun isoLocal(iso: String?): String {
        if (iso.isNullOrBlank()) return "–"
        val ms = isoMs(iso) ?: return iso
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    }

    fun isoMs(iso: String): Long? = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
}
