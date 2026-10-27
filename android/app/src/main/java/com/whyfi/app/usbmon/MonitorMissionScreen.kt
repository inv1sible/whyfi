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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.whyfi.app.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MissionView(
    context: Context,
    settings: SettingsRepository,
    missions: List<MonitorMission.Stored>,
    loading: Boolean,
    onRefresh: () -> Unit,
    vendors: OuiTable?,
    onOpenLocation: (String, String?) -> Unit,
    onContinue: (MonitorMission.Stored) -> Unit,
    continuingMissionId: String?,
    continueError: String?,
    onDeleted: (String) -> Unit,
    activeLogPath: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<MonitorMission.Stored?>(null) }
    var pendingRename by remember { mutableStateOf<MonitorMission.Stored?>(null) }
    var editedName by remember { mutableStateOf("") }

    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved missions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton({ onRefresh() }) { Text("Refresh") }
            }
            if (loading) Text("Loading missions…")
            else if (missions.isEmpty()) Text("No monitor missions saved yet.")
            continueError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(missions, key = { it.directory.name }) { mission ->
            val id = mission.directory.name
            var showDevices by remember(id) { mutableStateOf(false) }
            var devices by remember(id, mission.modifiedMs) {
                mutableStateOf<List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>?>(null)
            }
            var deviceLimit by remember(id) { mutableIntStateOf(40) }
            var showLog by remember(id) { mutableStateOf(false) }
            var reportReady by remember(id, mission.reportBytes) { mutableStateOf(mission.reportBytes != null) }
            var preview by remember(id, mission.modifiedMs) { mutableStateOf<String?>(null) }
            LaunchedEffect(showDevices, mission.modifiedMs) {
                if (showDevices && devices == null) devices = withContext(Dispatchers.IO) {
                    if (!mission.log.isFile) return@withContext emptyList()
                    MonitorEvidence.read(mission.log).inventoryAt().map { d ->
                        d to SniffedDevices.guess(d) { vendors?.vendorOf(it) }
                    }
                }
            }
            LaunchedEffect(showLog, mission.modifiedMs) {
                if (showLog && preview == null) preview = withContext(Dispatchers.IO) {
                    runCatching { MonitorMission.previewLog(mission.log) }.getOrElse { "Could not read mission log." }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mission.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ pendingRename = mission; editedName = mission.name }) { Text("Edit") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mission.pcap?.let { pcap ->
                            TextButton({ messages = messages + (id to shareFile(context, mission, pcap, "application/vnd.tcpdump.pcap")) }) {
                                Text("Export PCAP")
                            }
                        }
                        TextButton({ pendingDelete = mission }, enabled = busy == null && continuingMissionId == null &&
                            mission.log.absolutePath != activeLogPath) {
                            Text(if (deleting && busy == id) "Deleting…" else if (mission.log.absolutePath == activeLogPath)
                                "Stop capture to delete" else "Delete mission", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(if (id == settings.activeMonitorMissionId) "Open mission · Start/Stop continues until Reset"
                        else if (mission.captured) "Capture saved locally" else "Saved evidence · no completed capture statistics",
                        style = MaterialTheme.typography.bodySmall)
                    Text("PDF: ${fileSize(mission.reportBytes)} · Log: ${fileSize(mission.logBytes)} · PCAP: ${mission.pcapBytes?.let(::fileSize) ?: "not recorded"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (mission.uploaded) Text("Uploaded · local files retained", color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (reportReady) TextButton({
                            messages = messages + (id to openReport(context, mission))
                        }) { Text("Open PDF") }
                        if (reportReady) TextButton({
                            messages = messages + (id to shareFile(context, mission, mission.report, "application/pdf"))
                        }) { Text("Export PDF") }
                        TextButton({
                            busy = id
                            scope.launch {
                                val result = runCatching { withContext(Dispatchers.IO) { MonitorMission.ensureReport(context, mission, force = reportReady) } }
                                if (result.isSuccess) reportReady = true
                                messages = messages + (id to result.fold({ "PDF ready. Tap Open PDF." },
                                    { "PDF failed: ${it.message}" }))
                                busy = null
                                onRefresh()
                            }
                        }, enabled = activeLogPath == null && busy == null && continuingMissionId == null) {
                            Text(if (busy == id) "Working…" else if (reportReady) "Refresh PDF" else "Create PDF")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
                        TextButton({ messages = messages + (id to shareFile(context, mission, mission.log, "application/x-ndjson")) }) {
                            Text("Export full log")
                        }
                    }
                    if (showLog) {
                        Surface(tonalElevation = 2.dp) {
                            Text(preview ?: "Loading preview…", modifier = Modifier.fillMaxWidth()
                                .height(160.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ onOpenLocation(mission.log.absolutePath, null) }) { Text("Replay") }
                        TextButton({ showDevices = !showDevices }) { Text(if (showDevices) "Hide devices" else "Devices / location") }
                    }
                    Button({ onContinue(mission) }, enabled = activeLogPath == null &&
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
                    if ((mission.captured || reportReady) && id != settings.activeMonitorMissionId) Button({
                        busy = id
                        scope.launch {
                            messages = messages + (id to runCatching {
                                withContext(Dispatchers.IO) { MonitorMission.ensureReport(context, mission) }
                                MonitorMissionUploader.upload(mission, settings.backendUrl.orEmpty(), settings.sensorToken.orEmpty())
                            }.getOrElse { "Upload preparation failed: ${it.message}" })
                            busy = null
                            onRefresh()
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
                    onRefresh()
                }
            }, enabled = editedName.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ pendingRename = null }) { Text("Cancel") } })
    }
    pendingDelete?.let { mission ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete this mission?") },
            text = { Text("Delete this local mission and its PDF, log and PCAP?" +
                (if (mission.directory.name == settings.activeMonitorMissionId) " This closes the open mission; the next Start creates a new one." else "") +
                " Uploaded copies remain on the backend.") },
            confirmButton = { TextButton({
                pendingDelete = null
                busy = mission.directory.name
                deleting = true
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { MonitorMission.delete(context, mission, activeLogPath) }
                    if (removed) onDeleted(mission.log.absolutePath)
                    else messages = messages + (mission.directory.name to "Could not delete this mission. Stop any active capture and refresh the list.")
                    deleting = false
                    busy = null
                }
            }) { Text("Delete") } }, dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } })
    }
}

private fun fileSize(bytes: Long?): String = when {
    bytes == null -> "not generated"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

private fun openReport(context: Context, mission: MonitorMission.Stored): String = try {
    val file = mission.report
    val intent = MonitorEvidenceFiles.intent(context, file, "application/pdf", Intent.ACTION_VIEW,
        MonitorEvidenceFiles.exportName(mission, file))
    context.startActivity(Intent.createChooser(intent, "Open mission PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "PDF viewer opened."
} catch (e: android.content.ActivityNotFoundException) {
    "No PDF viewer installed. Export the PDF to open it on another device."
} catch (e: Exception) { "Could not open PDF: ${e.message}" }

private fun shareFile(context: Context, mission: MonitorMission.Stored, file: File, type: String): String = try {
    val intent = MonitorEvidenceFiles.intent(context, file, type, Intent.ACTION_SEND,
        MonitorEvidenceFiles.exportName(mission, file))
    context.startActivity(Intent.createChooser(intent, "Export mission evidence").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "Export opened. Local file retained."
} catch (e: Exception) { "Could not export: ${e.message}" }
