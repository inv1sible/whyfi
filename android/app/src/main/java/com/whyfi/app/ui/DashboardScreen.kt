package com.whyfi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whyfi.app.mission.MissionController
import com.whyfi.app.scan.RadioFormat
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanDiff
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.scan.StrongestSighting
import com.whyfi.app.scan.SurveyStats
import com.whyfi.app.ui.components.RadioStatChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WIFI_COLOR = Color(0xFF2DD4BF)
private val CELL_COLOR = Color(0xFF60A5FA)
private val BLE_COLOR = Color(0xFFA78BFA)
private val SAT_COLOR = Color(0xFFFBBF24)
private val LAN_COLOR = Color(0xFF34D399)
private val MISSION_COLOR = Color(0xFFF472B6)

/** Bands in a fixed order rather than whatever the map iterates in, so the
 * bars don't reshuffle between passes. */
private val BAND_ORDER = listOf(RadioFormat.BAND_24, RadioFormat.BAND_5, RadioFormat.BAND_6)

@Composable
fun DashboardScreen(
    service: ScanForegroundService?,
    uiState: ScanUiState,
    missionController: MissionController,
    onOpenDetail: (RadioKind) -> Unit = {},
    onOpenMission: () -> Unit = {},
) {
    val survey = uiState.survey
    val outbox = rememberOutboxStatus(uiState.completedScanCount)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineSmall)

        // survey.hasData tracks the session-scoped tally (passCount), which
        // stays 0 when the Dashboard is backfilled from the backend rather
        // than from a local scan. Also check latestPass so a backfilled
        // Dashboard isn't blank even though the survey tally is empty.
        if (!survey.hasData && uiState.latestPass == null) {
            Text(
                "Nothing scanned yet. Run a scan on the Scan tab and everything this phone " +
                    "hears will be totalled up here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${survey.passCount} pass${if (survey.passCount == 1) "" else "es"} " +
                    "since ${formatClock(survey.startedAtMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { service?.resetSessionCounters() }) { Text("Reset") }
        }

        Text("Unique devices heard", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val openable = uiState.latestPass != null
            RadioStatChip(
                "📶", "WiFi", survey.uniqueWifi, false, WIFI_COLOR,
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.WIFI) }) else null,
            )
            RadioStatChip(
                "📡", "Towers", survey.uniqueCellular, false, CELL_COLOR,
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.CELLULAR) }) else null,
            )
            RadioStatChip(
                "🔵", "BLE", survey.uniqueBle, false, BLE_COLOR,
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.BLE) }) else null,
            )
            RadioStatChip(
                "🛰️", "GPS", survey.uniqueSatellites, false, SAT_COLOR,
                modifier = Modifier.weight(1f),
                onClick = if (openable) ({ onOpenDetail(RadioKind.SATELLITE) }) else null,
            )
            // Not tied to uiState.latestPass like the radio chips above —
            // Mission view talks to the backend directly, not to the last
            // local pass, so it's always tappable. No count: this chip
            // doesn't summarize a number, it opens a different screen. The
            // spinner (isActivePhase) doubles as a live "still tracking"
            // indicator even while you're looking at this tab, not Mission
            // itself — see MissionController.uiState.isTracking.
            val missionState by missionController.uiState.collectAsState()
            RadioStatChip(
                "🎯", "Mission", null, missionState.isTracking, MISSION_COLOR,
                modifier = Modifier.weight(1f),
                onClick = onOpenMission,
            )
        }

        ChangesSection(uiState.latestPass != null, changesFor(uiState))

        BandSection(survey)

        StrongestSection(survey)

        if (uiState.lanDeviceCount != null) {
            HorizontalDivider()
            Text("LAN", style = MaterialTheme.typography.titleMedium)
            RadioStatChip("🌐", "Devices on this network", uiState.lanDeviceCount, false, LAN_COLOR)
        }

        HorizontalDivider()
        outbox?.let {
            Text(
                if (it.count == 0) {
                    "Everything uploaded — nothing waiting."
                } else {
                    "${it.count} scan${if (it.count == 1) "" else "s"} waiting to upload " +
                        "(${formatBytes(it.bytes)})."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The tally lives in the scan service and dies with it, so this is a
        // "this session" figure, not an all-time one. Saying so is cheaper
        // than letting someone read it as their whole survey.
        Text(
            "These totals cover this scanning session only and reset when the scanner stops. " +
                "The complete survey — every scan ever uploaded, on a map — is in whyfi in your browser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class RadioChange(val kind: RadioKind, val new: Int, val gone: Int, val comparable: Boolean)

private fun changesFor(uiState: ScanUiState): List<RadioChange> =
    RadioKind.entries.map { kind ->
        val summary = ScanDiff.summarize(ScanDiff.rowsFor(kind, uiState.latestPass, uiState.previousPass))
        RadioChange(kind, summary.new, summary.gone, summary.comparable)
    }

@Composable
private fun ChangesSection(hasPass: Boolean, changes: List<RadioChange>) {
    if (!hasPass) return
    // Nothing to say until there's a second pass to compare against.
    if (changes.none { it.comparable }) return

    Text("Since the previous pass", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        changes.filter { it.comparable }.forEach { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${change.kind.icon} ${change.kind.label}", modifier = Modifier.width(120.dp))
                Text(
                    if (change.new == 0 && change.gone == 0) {
                        "no change"
                    } else {
                        "+${change.new} new · −${change.gone} gone"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BandSection(survey: SurveyStats) {
    val bands = BAND_ORDER.filter { (survey.wifiByBand[it] ?: 0) > 0 }
    if (bands.isEmpty()) return
    val max = bands.maxOf { survey.wifiByBand[it] ?: 0 }

    Text("WiFi by band", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bands.forEach { band ->
            val count = survey.wifiByBand[band] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(band, modifier = Modifier.width(60.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { count.toFloat() / max },
                    modifier = Modifier.weight(1f),
                    color = WIFI_COLOR,
                )
                Text(count.toString(), modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StrongestSection(survey: SurveyStats) {
    val entries = listOfNotNull(
        survey.strongestWifi?.let { "📶" to it },
        survey.strongestCellular?.let { "📡" to it },
        survey.strongestBle?.let { "🔵" to it },
    )
    if (entries.isEmpty()) return

    Text("Strongest heard", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (icon, sighting) -> StrongestRow(icon, sighting) }
    }
}

@Composable
private fun StrongestRow(icon: String, sighting: StrongestSighting) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$icon ${sighting.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                sighting.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${sighting.signal} dBm", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
