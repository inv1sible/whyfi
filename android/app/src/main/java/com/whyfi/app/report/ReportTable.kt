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
}
