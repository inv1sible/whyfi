package com.whyfi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class TableColumn(val label: String, val width: Dp)

data class TableBadge(val text: String, val color: Color)

data class DataTableRow(
    val key: String,
    val cells: List<String>,
    val badge: TableBadge? = null,
    /** Rendered faded, for rows describing something no longer present. */
    val dimmed: Boolean = false,
)

private val BADGE_COLUMN_WIDTH = 62.dp

/**
 * A scrolling table sized for a phone.
 *
 * Two things here are load-bearing:
 *
 * 1. **The header and every body row share one [rememberScrollState]**, so
 *    dragging sideways moves them together and a value stays under its
 *    column name. A `horizontalScroll` on some outer container instead would
 *    fight [LazyColumn], which needs to own the vertical axis.
 * 2. **The badge column sits outside the horizontal scroll**, pinned left.
 *    New/gone is the thing worth seeing at a glance, and it would otherwise
 *    scroll off exactly when you're reading the columns that explain it.
 */
@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<DataTableRow>,
    modifier: Modifier = Modifier,
    showBadgeColumn: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val leadingWidth = if (showBadgeColumn) BADGE_COLUMN_WIDTH else 0.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBadgeColumn) Box(Modifier.width(leadingWidth))
            Row(Modifier.horizontalScroll(scrollState)) {
                columns.forEach { column ->
                    Text(
                        column.label,
                        modifier = Modifier.width(column.width).padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // Keyed by position as well as identity. LazyColumn throws on a
            // duplicate key, and radio hardware does hand back rows that look
            // identical — neighbour cells with no cell ID are the common case.
            // A crash is a much worse answer than two indistinguishable rows.
            itemsIndexed(rows, key = { index, row -> "$index:${row.key}" }) { _, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBadgeColumn) {
                        Box(Modifier.width(leadingWidth).padding(start = 4.dp)) {
                            row.badge?.let { Badge(it) }
                        }
                    }
                    Row(Modifier.horizontalScroll(scrollState)) {
                        row.cells.forEachIndexed { index, cell ->
                            Text(
                                cell,
                                modifier = Modifier
                                    .width(columns.getOrNull(index)?.width ?: 100.dp)
                                    .padding(horizontal = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.dimmed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Badge(badge: TableBadge) {
    Text(
        badge.text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badge.color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = badge.color,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}
