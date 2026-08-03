package com.whyfi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TableColumn(val label: String, val width: Dp)

data class TableBadge(val text: String, val color: Color)

data class DataTableRow(
    val key: String,
    val cells: List<String>,
    val badge: TableBadge? = null,
    /** Rendered faded, for rows describing something no longer present. */
    val dimmed: Boolean = false,
    /** Set (with [onFavoriteToggle]) only for WiFi rows with a real SSID —
     * see ui/ScanDetailScreen.kt. Null on every other radio kind's rows,
     * which renders no star at all. */
    val isFavorite: Boolean = false,
    val onFavoriteToggle: (() -> Unit)? = null,
)

private val BADGE_COLUMN_WIDTH = 62.dp
private val FAVORITE_COLUMN_WIDTH = 36.dp

/** First number found anywhere in a cell — "Ch 6 (2.4GHz)" -> 6, "-72 dBm"
 * -> -72. Falls back to plain string comparison when either side has no
 * number, so a signal/channel/count column sorts by magnitude instead of
 * lexicographically (where "-90" < "-55" is backwards for dBm). */
private val LEADING_NUMBER = Regex("""-?\d+(\.\d+)?""")

private fun naturalCompare(a: String, b: String): Int {
    val an = LEADING_NUMBER.find(a)?.value?.toDoubleOrNull()
    val bn = LEADING_NUMBER.find(b)?.value?.toDoubleOrNull()
    return if (an != null && bn != null) an.compareTo(bn) else a.compareTo(b, ignoreCase = true)
}

private data class SortState(val columnIndex: Int, val ascending: Boolean)

/**
 * A scrolling table sized for a phone, with a search box and sortable column
 * headers above it.
 *
 * Three things here are load-bearing:
 *
 * 1. **The header and every body row share one [rememberScrollState]**, so
 *    dragging sideways moves them together and a value stays under its
 *    column name. A `horizontalScroll` on some outer container instead would
 *    fight [LazyColumn], which needs to own the vertical axis.
 * 2. **The badge column sits outside the horizontal scroll**, pinned left.
 *    New/gone is the thing worth seeing at a glance, and it would otherwise
 *    scroll off exactly when you're reading the columns that explain it.
 * 3. **Search and sort operate on [rows] as given**, not on some separately
 *    fetched superset — this table only ever shows one scan pass's worth of
 *    rows, so there's nothing to page through, just filter/reorder what's
 *    already here.
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
    // Table-level, not per-row: whether *any* row carries a favorite toggle
    // decides if the column exists at all, same reasoning as showBadgeColumn.
    val showFavoriteColumn = rows.any { it.onFavoriteToggle != null }
    val favoriteWidth = if (showFavoriteColumn) FAVORITE_COLUMN_WIDTH else 0.dp

    // rememberSaveable, not remember: rotating the phone shouldn't silently
    // clear a search you were mid-typing.
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf<SortState?>(null) }

    val visibleRows = rows
        .filter { row -> query.isBlank() || row.cells.any { it.contains(query, ignoreCase = true) } }
        .let { filtered ->
            val active = sort ?: return@let filtered
            val compared = filtered.sortedWith { a, b ->
                naturalCompare(
                    a.cells.getOrElse(active.columnIndex) { "" },
                    b.cells.getOrElse(active.columnIndex) { "" },
                )
            }
            if (active.ascending) compared else compared.asReversed()
        }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = { Text("Search…") },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFavoriteColumn) Box(Modifier.width(favoriteWidth))
            if (showBadgeColumn) Box(Modifier.width(leadingWidth))
            Row(Modifier.horizontalScroll(scrollState)) {
                columns.forEachIndexed { index, column ->
                    val active = sort?.takeIf { it.columnIndex == index }
                    Text(
                        column.label + (active?.let { if (it.ascending) " ▲" else " ▼" } ?: ""),
                        modifier = Modifier
                            .width(column.width)
                            .clickable {
                                sort = when {
                                    active == null -> SortState(index, ascending = true)
                                    active.ascending -> SortState(index, ascending = false)
                                    else -> null
                                }
                            }
                            .padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider()

        if (visibleRows.isEmpty()) {
            Text(
                "No rows match \"$query\".",
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // Keyed by position as well as identity. LazyColumn throws on a
            // duplicate key, and radio hardware does hand back rows that look
            // identical — neighbour cells with no cell ID are the common case.
            // A crash is a much worse answer than two indistinguishable rows.
            itemsIndexed(visibleRows, key = { index, row -> "$index:${row.key}" }) { _, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showFavoriteColumn) {
                        Box(Modifier.width(favoriteWidth), contentAlignment = Alignment.Center) {
                            row.onFavoriteToggle?.let { toggle -> FavoriteStar(row.isFavorite, toggle) }
                        }
                    }
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
private fun FavoriteStar(isFavorite: Boolean, onToggle: () -> Unit) {
    // Emoji glyph, not an icon-library asset — matches this app's existing
    // no-icon-library convention (see MainActivity's tab icon constants).
    Text(
        if (isFavorite) "★" else "☆",
        modifier = Modifier.clickable(onClick = onToggle).padding(4.dp),
        fontSize = 18.sp,
        color = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
