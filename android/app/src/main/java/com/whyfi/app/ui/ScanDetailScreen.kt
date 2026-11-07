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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.data.FavoritesRepository
import com.whyfi.app.data.remote.FtmObservationDto
import com.whyfi.app.scan.DeviceChange
import com.whyfi.app.scan.DeviceRow
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanCoordinator
import com.whyfi.app.scan.ScanDiff
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.ui.components.DataTable
import com.whyfi.app.ui.components.DataTableRow
import com.whyfi.app.ui.components.TableBadge
import com.whyfi.app.ui.components.TableColumn
import kotlinx.coroutines.launch

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
    favoritesRepository: FavoritesRepository,
    service: ScanForegroundService? = null,
) {
    val rows = remember(kind, uiState.latestPass, uiState.previousPass) {
        ScanDiff.rowsFor(kind, uiState.latestPass, uiState.previousPass)
    }
    val summary = remember(rows) { ScanDiff.summarize(rows) }

    // A ranging measurement doesn't need the foreground service running —
    // it's a direct OS API call against whatever WifiScanManager's own
    // last-scan cache already holds — so this screen owns a small
    // ScanCoordinator of its own rather than reaching into the bound
    // service (see ScanCoordinator.rangeOnce's KDoc).
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scanCoordinator = remember { ScanCoordinator(context.applicationContext) }
    // BSSID -> last range outcome for this screen instance ("…" while in
    // flight); not persisted anywhere, purely a "did that just work" hint.
    val rangeStatus = remember { mutableStateMapOf<String, String>() }
    // Whether this phone can do Wi-Fi RTT at all. Most Android phones can't,
    // and without saying so the Range control's absence is indistinguishable
    // from "there's nothing to range here" — which is exactly the confusion
    // that had FTM looking broken when it was simply never attempted.
    val rttAvailable = remember { scanCoordinator.ftmRangingManager.isAvailable() }
    val onRange: (String) -> Unit = { bssid ->
        rangeStatus[bssid] = "…"
        coroutineScope.launch {
            rangeStatus[bssid] = rangeStatusLabel(scanCoordinator.rangeOnce(bssid))
        }
    }
    // SATELLITE has no favorites (see FavoritesRepository) — an empty,
    // static set here means the map below just never wires a toggle for it.
    val favorites by if (kind == RadioKind.SATELLITE) {
        remember { mutableStateOf(emptySet<String>()) }
    } else {
        favoritesRepository.favorites(kind).collectAsState()
    }

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

        if (kind == RadioKind.SATELLITE) GpsPanel(service, uiState)

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

        if (kind == RadioKind.WIFI) {
            val responders = rows.count { it.is80211mcResponder && it.change != DeviceChange.GONE }
            Text(
                when {
                    !rttAvailable ->
                        "Wi-Fi RTT (FTM) ranging isn't supported by this phone, so no distance measurements " +
                            "can be taken here. Most Android devices don't have it."
                    responders == 0 ->
                        "No FTM-capable networks in this scan. Only access points that advertise 802.11mc " +
                            "responder support can be ranged, which is a small minority of them — the 📡 button " +
                            "appears on those rows when one shows up."
                    else ->
                        "$responders network(s) here support FTM ranging — tap 📡 on those rows to measure " +
                            "distance. Repeat from a few different spots to localize them."
                },
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
                rows = rows.map { row ->
                    val id = row.favoriteId
                    val canRange = kind == RadioKind.WIFI && row.is80211mcResponder && row.change != DeviceChange.GONE
                    row.toTableRow(
                        isFavorite = id != null && favorites.contains(id),
                        onFavoriteToggle = if (id != null) {
                            { favoritesRepository.toggleFavorite(kind, id) }
                        } else {
                            null
                        },
                        onRange = if (canRange) ({ onRange(row.key) }) else null,
                        rangeStatus = rangeStatus[row.key],
                    )
                }.sortedByDescending { it.isFavorite }, // favorites to top; stable within each group
                showBadgeColumn = summary.comparable,
            )
        }
    }
}

private fun DeviceRow.toTableRow(
    isFavorite: Boolean,
    onFavoriteToggle: (() -> Unit)?,
    onRange: (() -> Unit)?,
    rangeStatus: String?,
): DataTableRow = DataTableRow(
    key = key,
    cells = columns,
    badge = when (change) {
        DeviceChange.NEW -> TableBadge("NEW", NEW_COLOR)
        DeviceChange.GONE -> TableBadge("GONE", GONE_COLOR)
        DeviceChange.PRESENT, DeviceChange.UNKNOWN -> null
    },
    dimmed = change == DeviceChange.GONE,
    isFavorite = isFavorite,
    onFavoriteToggle = onFavoriteToggle,
    onRange = onRange,
    rangeStatus = rangeStatus,
)

/** Short inline label for a just-finished ranging result — shown in place of
 * the 📡 prompt until the row is ranged again. */
private fun rangeStatusLabel(result: FtmObservationDto): String =
    if (result.success && result.distanceMm != null) {
        "✓ %.1fm".format(result.distanceMm / 1000.0)
    } else {
        "✗ ${result.status.ifBlank { "failed" }}"
    }

private fun summaryLine(total: Int, new: Int, gone: Int, comparable: Boolean): String {
    val devices = "$total device${if (total == 1) "" else "s"}"
    // "0 new, 0 gone" against nothing to compare would be a claim we can't
    // support — see ScanDiff.canCompare.
    if (!comparable) return "$devices · no previous scan to compare against"
    return "$devices · $new new · $gone gone since the previous scan"
}
