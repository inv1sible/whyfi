package com.whyfi.app.usbmon

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MapMissionData(val path: String, val samples: List<MonitorGeoSample>, val targets: Set<String>)

/** Live local map while capturing, with access to retained mission logs. */
@Composable
internal fun MonitorMappingView(
    context: Context,
    state: MonitorUiState,
    favorites: Set<String>,
    modifier: Modifier = Modifier,
) {
    var selectedLogPath by remember { mutableStateOf<String?>(null) }
    var selectedMac by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(state.missionLogPath) {
        selectedLogPath = null
        selectedMac = null
    }
    val missions = remember(state.reportPath, state.missionLogPath, refresh) {
        MonitorMission.list(context).filter { it.log.isFile }
    }
    val currentLog = state.missionLogPath?.let(::File)?.takeIf { it.isFile }
    val log = selectedLogPath?.let(::File)?.takeIf { it.isFile }
        ?: currentLog ?: missions.firstOrNull()?.log
    val logPath = log?.absolutePath
    var data by remember { mutableStateOf<MapMissionData?>(null) }
    val sampleRevision = if (logPath == currentLog?.absolutePath) state.geoSamples else 0
    LaunchedEffect(logPath, sampleRevision, refresh) {
        if (log == null) {
            data = null
        } else {
            val loaded = withContext(Dispatchers.IO) {
                MapMissionData(log.absolutePath, MonitorMission.readSamples(log), MonitorMission.targets(log))
            }
            data = loaded
        }
    }
    val shown = data?.takeIf { it.path == logPath }
    val targets = (shown?.targets.orEmpty() +
        if (logPath == currentLog?.absolutePath) favorites else emptySet()).sorted()
    val mac = selectedMac?.takeIf { it in targets } ?: targets.firstOrNull()
    val samples = remember(shown, mac) { shown?.samples?.filter { it.mac == mac }.orEmpty() }
    val estimateKey = "$logPath:$mac:${samples.size}:${samples.lastOrNull()?.atMs}"
    var estimated by remember { mutableStateOf<Pair<String, MonitorEstimate?>?>(null) }
    LaunchedEffect(estimateKey, samples) {
        estimated = estimateKey to withContext(Dispatchers.Default) { MonitorLocalization.estimate(samples) }
    }
    val estimate = estimated?.takeIf { it.first == estimateKey }?.second

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mapping", style = MaterialTheme.typography.titleMedium)
            TextButton({ refresh++ }) { Text("Refresh") }
        }
        TextButton({ menuOpen = true }) {
            Text(if (logPath == currentLog?.absolutePath && currentLog != null) "Current capture ▾"
                else if (log == null) "Choose mission ▾"
                else "Saved mission ${log.parentFile?.name?.take(8).orEmpty()} ▾")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            currentLog?.let {
                DropdownMenuItem(text = { Text("Current capture") }, onClick = {
                    selectedLogPath = null; selectedMac = null; menuOpen = false
                })
            }
            missions.forEach { mission ->
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(mission.log.lastModified()))
                DropdownMenuItem(text = { Text("$date · ${mission.directory.name.take(8)}") }, onClick = {
                    selectedLogPath = mission.log.absolutePath; selectedMac = null; menuOpen = false
                })
            }
        }
        if (log == null) {
            Text("Start a monitor mission to collect map positions. Saved missions remain available here.")
            return@Column
        }
        Text("${shown?.samples?.size ?: 0} location-tagged readings · ${targets.size} pinned devices",
            style = MaterialTheme.typography.bodySmall)
        if (targets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                targets.forEach { target ->
                    FilterChip(target == mac, { selectedMac = target }, label = { Text(target) })
                }
            }
        }
        when {
            mac == null -> Text("Star an AP or station in Nearby to start its position map.")
            samples.isEmpty() -> Text("No location-tagged signal for $mac yet. Keep it starred; GPS, network and fused fixes are accepted.")
            estimate == null -> Text("Calculating position…")
            else -> {
                val position = estimate
                Text("$mac  ·  %.6f, %.6f  ·  uncertainty at least %.0f m%s".format(
                    position.lat, position.lng, position.uncertaintyM,
                    if (position.provisional) " · provisional" else ""),
                    style = MaterialTheme.typography.bodySmall)
                MonitorMissionMap(position, samples, Modifier.weight(1f), mapKey = "${logPath}:$mac")
                Text(position.advice.take(2).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
    }
}
