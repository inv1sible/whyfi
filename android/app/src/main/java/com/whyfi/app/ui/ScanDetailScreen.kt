package com.whyfi.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whyfi.app.scan.DeviceChange
import com.whyfi.app.scan.DeviceRow
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanDiff
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.ui.components.DataTable
import com.whyfi.app.ui.components.DataTableRow
import com.whyfi.app.ui.components.TableBadge
import com.whyfi.app.ui.components.TableColumn

private val NEW_COLOR = Color(0xFF2DD4BF)
private val GONE_COLOR = Color(0xFFF87171)

/** Per-radio column widths. Fixed rather than measured: the table scrolls
 * sideways anyway, and a width that shifts as rows arrive is worse to read
 * than one that's occasionally a little generous. */
private fun columnsFor(kind: RadioKind): List<TableColumn> = when (kind) {
    RadioKind.WIFI -> listOf(
        TableColumn("SSID", 150.dp),
        TableColumn("BSSID", 140.dp),
        TableColumn("Signal", 80.dp),
        TableColumn("Channel", 110.dp),
        TableColumn("Security", 100.dp),
    )
    RadioKind.CELLULAR -> listOf(
        TableColumn("Carrier", 130.dp),
        TableColumn("Cell ID", 110.dp),
        TableColumn("Type", 70.dp),
        TableColumn("Signal", 80.dp),
        TableColumn("TAC/LAC", 90.dp),
        TableColumn("Serving", 90.dp),
    )
    RadioKind.BLE -> listOf(
        TableColumn("Name", 160.dp),
        TableColumn("MAC", 140.dp),
        TableColumn("Signal", 80.dp),
        TableColumn("Type", 110.dp),
    )
    RadioKind.SATELLITE -> listOf(
        TableColumn("Constellation", 120.dp),
        TableColumn("SVID", 60.dp),
        TableColumn("C/N0", 90.dp),
        TableColumn("Elev", 70.dp),
        TableColumn("Azim", 70.dp),
        TableColumn("In fix", 60.dp),
    )
}

@Composable
fun ScanDetailScreen(
    uiState: ScanUiState,
    kind: RadioKind,
    onKindChange: (RadioKind) -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember(kind, uiState.latestPass, uiState.previousPass) {
        ScanDiff.rowsFor(kind, uiState.latestPass, uiState.previousPass)
    }
    val summary = remember(rows) { ScanDiff.summarize(rows) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Last scan", style = MaterialTheme.typography.titleMedium)
        }

        // Switching radio here rather than going back and tapping another
        // chip — comparing WiFi against BLE for the same pass is the common
        // reason to be on this screen at all.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioKind.entries.forEach { option ->
                FilterChip(
                    selected = option == kind,
                    onClick = { onKindChange(option) },
                    label = { Text("${option.icon} ${option.label}") },
                )
            }
        }

        Text(
            summaryLine(summary.total, summary.new, summary.gone, summary.comparable),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (kind == RadioKind.BLE && summary.comparable) {
            // Without this the new/gone counts read as devices arriving and
            // leaving, when a good share of it is one device re-advertising
            // under a fresh address.
            Text(
                "Bluetooth devices rotate their MAC address every few minutes for privacy, " +
                    "so some of the new and gone entries are the same device under a different address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (rows.isEmpty()) {
            Text(
                "Nothing was recorded for ${kind.label} in the last scan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DataTable(
                columns = columnsFor(kind),
                rows = rows.map { it.toTableRow() },
                showBadgeColumn = summary.comparable,
            )
        }
    }
}

private fun DeviceRow.toTableRow(): DataTableRow = DataTableRow(
    key = key,
    cells = columns,
    badge = when (change) {
        DeviceChange.NEW -> TableBadge("NEW", NEW_COLOR)
        DeviceChange.GONE -> TableBadge("GONE", GONE_COLOR)
        DeviceChange.PRESENT, DeviceChange.UNKNOWN -> null
    },
    dimmed = change == DeviceChange.GONE,
)

private fun summaryLine(total: Int, new: Int, gone: Int, comparable: Boolean): String {
    val devices = "$total device${if (total == 1) "" else "s"}"
    // "0 new, 0 gone" against nothing to compare would be a claim we can't
    // support — see ScanDiff.canCompare.
    if (!comparable) return "$devices · no previous scan to compare against"
    return "$devices · $new new · $gone gone since the previous scan"
}
