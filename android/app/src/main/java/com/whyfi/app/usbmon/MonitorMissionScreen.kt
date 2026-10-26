package com.whyfi.app.usbmon

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MissionView(
    context: Context,
    settings: SettingsRepository,
    latestReportPath: String?,
    onOpenLocation: (String, String?) -> Unit,
    onContinue: (MonitorMission.Stored) -> Unit,
    continuingMissionId: String?,
    continueError: String?,
    onDeleted: (String) -> Unit,
    activeLogPath: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var missions by remember { mutableStateOf<List<MonitorMission.Stored>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<MonitorMission.Stored?>(null) }
    var pendingRename by remember { mutableStateOf<MonitorMission.Stored?>(null) }
    var editedName by remember { mutableStateOf("") }
    LaunchedEffect(latestReportPath, activeLogPath, revision) {
        missions = withContext(Dispatchers.IO) { MonitorMission.list(context) }
        loading = false
    }

    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved missions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton({ revision++ }) { Text("Refresh") }
            }
            if (loading) Text("Loading missions…")
            else if (missions.isEmpty()) Text("No monitor missions saved yet.")
            continueError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(missions, key = { it.directory.name }) { mission ->
            val id = mission.directory.name
            var showDevices by remember(id) { mutableStateOf(false) }
            var devices by remember(id, mission.log.lastModified()) {
                mutableStateOf<List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>?>(null)
            }
            var deviceLimit by remember(id) { mutableIntStateOf(40) }
            var showLog by remember(id) { mutableStateOf(false) }
            var preview by remember(id, mission.log.lastModified()) { mutableStateOf<String?>(null) }
            LaunchedEffect(showDevices, mission.log.lastModified()) {
                if (showDevices && devices == null) devices = withContext(Dispatchers.IO) {
                    val vendors = runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
                    MonitorEvidence.read(mission.log).inventoryAt().map { d ->
                        d to SniffedDevices.guess(d) { vendors?.vendorOf(it) }
                    }
                }
            }
            LaunchedEffect(showLog, mission.log.lastModified()) {
                if (showLog && preview == null) preview = withContext(Dispatchers.IO) {
                    runCatching { MonitorMission.previewLog(mission.log) }.getOrElse { "Could not read mission log." }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mission.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ pendingRename = mission; editedName = mission.name }) { Text("Edit") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mission.pcap?.let { pcap ->
                            TextButton({ messages = messages + (id to shareFile(context, pcap, "application/vnd.tcpdump.pcap")) }) {
                                Text("Export PCAP")
                            }
                        }
                        TextButton({ pendingDelete = mission }, enabled = busy == null && continuingMissionId == null &&
                            mission.log.absolutePath != activeLogPath && id != settings.activeMonitorMissionId) {
                            Text("Delete mission", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(mission.dateMs)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(if (id == settings.activeMonitorMissionId) "Open mission · Start/Stop continues until Reset"
                        else if (mission.captured) "Capture saved locally" else "Saved evidence · no completed capture statistics",
                        style = MaterialTheme.typography.bodySmall)
                    Text("PDF: ${fileSize(mission.report)} · Log: ${fileSize(mission.log)} · PCAP: ${mission.pcap?.let(::fileSize) ?: "not recorded"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (mission.uploaded) Text("Uploaded · local files retained", color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({
                            busy = id
                            scope.launch {
                                val result = runCatching { withContext(Dispatchers.IO) { MonitorMission.ensureReport(context, mission) } }
                                messages = messages + (id to result.fold({ openReport(context, it) },
                                    { "PDF failed: ${it.message}" }))
                                busy = null
                                revision++
                            }
                        }, enabled = mission.log.isFile && activeLogPath == null && busy == null && continuingMissionId == null) {
                            Text(if (busy == id) "Working…" else if (mission.report.isFile) "Open / refresh PDF" else "Create PDF")
                        }
                        TextButton({ showLog = !showLog }) { Text(if (showLog) "Hide log preview" else "Log preview") }
                    }
                    if (showLog) {
                        Text(preview ?: "Loading preview…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        TextButton({ messages = messages + (id to shareFile(context, mission.log, "application/x-ndjson")) }) {
                            Text("Export full log")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ onOpenLocation(mission.log.absolutePath, null) }) { Text("Replay") }
                        TextButton({ showDevices = !showDevices }) { Text(if (showDevices) "Hide devices" else "Devices / location") }
                    }
                    if (mission.log.isFile) Button({ onContinue(mission) }, enabled = activeLogPath == null &&
                        continuingMissionId == null && busy == null) {
                        Text(if (continuingMissionId == id) "Continuing…" else "Continue mission")
                    }
                    if (showDevices) {
                        if (devices == null) Text("Loading recorded devices…")
                        else if (devices!!.isEmpty()) Text("This mission has no saved device snapshots.")
                        devices.orEmpty().take(deviceLimit).forEach { (device, guess) ->
                            TextButton({ onOpenLocation(mission.log.absolutePath, device.mac) }, modifier = Modifier.fillMaxWidth()) {
                                Text(MonitorMission.displayTarget(device.mac, MonitorMission.deviceName(device, guess)))
                            }
                        }
                        if (devices.orEmpty().size > deviceLimit) TextButton({ deviceLimit += 40 }) { Text("Show more devices") }
                    }
                    if (mission.captured && id != settings.activeMonitorMissionId) Button({
                        busy = id
                        scope.launch {
                            messages = messages + (id to runCatching {
                                withContext(Dispatchers.IO) { MonitorMission.ensureReport(context, mission) }
                                MonitorMissionUploader.upload(mission, settings.backendUrl.orEmpty(), settings.sensorToken.orEmpty())
                            }.getOrElse { "Upload preparation failed: ${it.message}" })
                            busy = null
                            revision++
                        }
                    }, enabled = busy == null && continuingMissionId == null) {
                        Text(if (busy == id) "Preparing / uploading…" else if (mission.uploaded) "Upload again" else "Create PDF and upload")
                    }
                    messages[id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    pendingRename?.let { mission ->
        AlertDialog(onDismissRequest = { pendingRename = null }, title = { Text("Rename mission") },
            text = { OutlinedTextField(editedName, { editedName = it.take(80) }, singleLine = true, label = { Text("Mission name") }) },
            confirmButton = { TextButton({
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { MonitorMission.rename(mission, editedName) } }
                        .onFailure { messages = messages + (mission.directory.name to "Rename failed: ${it.message}") }
                    pendingRename = null
                    revision++
                }
            }, enabled = editedName.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ pendingRename = null }) { Text("Cancel") } })
    }
    pendingDelete?.let { mission ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete this mission?") },
            text = { Text("Delete this local mission and its PDF, log and PCAP? Uploaded copies remain on the backend.") },
            confirmButton = { TextButton({
                pendingDelete = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { MonitorMission.delete(context, mission, activeLogPath) }
                    if (removed) { onDeleted(mission.log.absolutePath); revision++ }
                    else messages = messages + (mission.directory.name to "Could not delete this mission.")
                }
            }) { Text("Delete") } }, dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } })
    }
}

private fun fileSize(file: File): String = when {
    !file.isFile -> "not generated"
    file.length() < 1024 -> "${file.length()} B"
    file.length() < 1024 * 1024 -> "%.1f KiB".format(file.length() / 1024.0)
    else -> "%.1f MiB".format(file.length() / (1024.0 * 1024.0))
}

private fun openReport(context: Context, file: File): String = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    "PDF opened."
} catch (e: Exception) { "Could not open PDF: ${e.message}" }

private fun shareFile(context: Context, file: File, type: String): String = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Export mission evidence").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "Export opened. Local file retained."
} catch (e: Exception) { "Could not export: ${e.message}" }
