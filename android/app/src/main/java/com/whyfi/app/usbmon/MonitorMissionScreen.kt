package com.whyfi.app.usbmon

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.whyfi.app.data.SettingsRepository
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun MissionView(
    context: Context,
    settings: SettingsRepository,
    latestReportPath: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var missions by remember { mutableStateOf(MonitorMission.list(context)) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var openLog by remember { mutableStateOf<String?>(null) }
    var mapTargets by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(latestReportPath) { missions = MonitorMission.list(context) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved missions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton({ missions = MonitorMission.list(context) }) { Text("Refresh") }
        }
        Text("Each session remains in this app's monitor-missions folder after a new capture or upload. PDF and log are always saved; PCAP is saved when recording was enabled.",
            style = MaterialTheme.typography.bodySmall)
        if (missions.isEmpty()) Text("No monitor missions saved yet.")
        missions.forEach { mission ->
            val id = mission.directory.name
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(mission.log.lastModified().takeIf { it > 0 } ?: mission.directory.lastModified()))
                    Text(date, style = MaterialTheme.typography.titleSmall)
                    Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(if (mission.complete) "Complete · stored locally" else "Recording or report incomplete",
                        style = MaterialTheme.typography.bodySmall)
                    Text("PDF: ${fileSize(mission.report)} · Log: ${fileSize(mission.log)} · PCAP: ${mission.pcap?.let(::fileSize) ?: "not recorded"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (mission.uploaded) Text("Uploaded · local files retained", color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mission.report.isFile) TextButton({
                            messages = messages + (id to openReport(context, mission.report))
                        }) { Text("Open PDF") }
                        if (mission.log.isFile) TextButton({ openLog = if (openLog == id) null else id }) { Text("View log") }
                    }
                    val targets = if (mission.log.isFile) remember(id, mission.log.lastModified()) {
                        MonitorMission.targets(mission.log).sorted()
                    } else emptyList()
                    if (targets.isNotEmpty()) {
                        Text("Position estimates", style = MaterialTheme.typography.labelLarge)
                        targets.forEach { mac ->
                            TextButton({ mapTargets = mapTargets + (id to mac) }) { Text(mac) }
                        }
                        mapTargets[id]?.let { mac ->
                            val samples = remember(id, mac, mission.log.lastModified()) {
                                MonitorMission.readSamples(mission.log).filter { it.mac == mac }
                            }
                            val estimate = remember(samples) { MonitorLocalization.estimate(samples) }
                            if (estimate == null) {
                                Text("No GPS-tagged readings for this favorite. Keep it pinned and move through several positions.",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("%.6f, %.6f · uncertainty at least %.0f m · %d places".format(
                                    estimate.lat, estimate.lng, estimate.uncertaintyM, estimate.spatialGroups),
                                    style = MaterialTheme.typography.bodySmall)
                                MonitorMissionMap(estimate, samples)
                                estimate.advice.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (openLog == id && mission.log.isFile) {
                        Text(mission.log.readLines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (mission.pcap != null) {
                        TextButton({ messages = messages + (id to shareFile(context, mission.pcap, "application/vnd.tcpdump.pcap")) }) {
                            Text("Export PCAP")
                        }
                    }
                    if (mission.complete) {
                        Button(onClick = {
                            uploading = id
                            scope.launch {
                                val result = MonitorMissionUploader.upload(
                                    mission, settings.backendUrl.orEmpty(), settings.sensorToken.orEmpty(),
                                )
                                messages = messages + (id to result)
                                missions = MonitorMission.list(context)
                                uploading = null
                            }
                        }, enabled = uploading == null) {
                            Text(if (uploading == id) "Uploading…" else if (mission.uploaded) "Upload again" else "Upload to backend")
                        }
                    }
                    messages[id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun fileSize(file: File): String =
    if (!file.isFile) "missing" else when {
        file.length() < 1024 -> "${file.length()} B"
        file.length() < 1024 * 1024 -> "%.1f KiB".format(file.length() / 1024.0)
        else -> "%.1f MiB".format(file.length() / (1024.0 * 1024.0))
    }

private fun openReport(context: Context, file: File): String = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.monitorfiles", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    ""
} catch (e: Exception) {
    "No PDF viewer available: ${e.message ?: e.javaClass.simpleName}"
}

private fun shareFile(context: Context, file: File, type: String): String = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.monitorfiles", file)
    val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Export monitor capture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    ""
} catch (e: Exception) {
    "Could not export file: ${e.message ?: e.javaClass.simpleName}"
}
