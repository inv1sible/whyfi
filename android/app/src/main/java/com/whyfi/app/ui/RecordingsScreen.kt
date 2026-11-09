package com.whyfi.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.data.local.RecordingStore
import com.whyfi.app.data.local.RecordingSummary
import com.whyfi.app.report.PhoneReports
import com.whyfi.app.scan.RecordingFormat
import com.whyfi.app.scan.ScanUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's scan history (RecordingStore): one row per recording with its
 * report, a raw JSON share and delete. Works with or without a server.
 */
@Composable
fun RecordingsScreen(uiState: ScanUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val serverConfigured = rememberServerConfigured()
    val store = remember { RecordingStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var recordings by remember { mutableStateOf<List<RecordingSummary>?>(null) }
    var usage by remember { mutableStateOf<RecordingStore.Usage?>(null) }
    var confirmDelete by remember { mutableStateOf<RecordingSummary?>(null) }

    // Reloads when opened, after a delete, and as passes are added.
    LaunchedEffect(reload, uiState.completedScanCount, uiState.isContinuous, uiState.lanFinishedAtMs) {
        withContext(Dispatchers.IO) {
            recordings = store.summaries()
            usage = store.usage()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("🎯 Missions", style = MaterialTheme.typography.headlineSmall)
        }
        usage?.let { u ->
            Text(
                "${RecordingFormat.bytes(u.bytes)} on this phone · ${u.recordings} missions" +
                    (if (u.waiting > 0) " · ${u.waiting} passes waiting to upload" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (u.overBytes > 0) Text(
                "Over the storage limit by ${RecordingFormat.bytes(u.overBytes)} with missions that aren't uploaded. " +
                    "Nothing is deleted automatically: delete some here or raise the limit in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val list = recordings
        when {
            list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text(
                "No missions yet. Every scan, from Start to Stop, is kept here as a mission, with or without a server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.id }) { r ->
                    RecordingRow(
                        r,
                        onReport = {
                            PhoneReports.recording(context, r, store.passes(r.id), uiState.catalogLabels)
                        },
                        onShare = {
                            scope.launch {
                                val message = runCatching { PhoneReports.exportJson(context, r, store.rawJson(r.id)) }
                                    .fold({ PhoneReports.share(context, it, "application/json") }, { "Export failed: ${it.message}" })
                                message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            }
                        },
                        onDelete = { confirmDelete = r },
                        onUpload = if (serverConfigured && !r.syncAllowed && !r.isOpen && r.passes > 0) ({
                            scope.launch {
                                withContext(Dispatchers.IO) { store.allowSync(listOf(r.id)) }
                                reload++
                            }
                        }) else null,
                    )
                }
            }
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete mission?") },
            text = {
                Text(
                    "${started(r)} · ${RecordingFormat.summary(r)}." +
                        if (r.waiting > 0 || !r.syncAllowed) "\n\nIt isn't on a server, so it will be gone for good." else
                            "\n\nThe copy on the server stays.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(listOf(r.id)) }
                        reload++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun started(r: RecordingSummary) =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtMs))

@Composable
private fun RecordingRow(
    r: RecordingSummary,
    onReport: suspend () -> java.io.File,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpload: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(started(r), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(RecordingFormat.duration(r.startedAtMs, r.endedAtMs), style = MaterialTheme.typography.labelMedium)
        }
        Text(RecordingFormat.summary(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReportButton(enabled = !r.isOpen && r.passes > 0, generate = onReport)
            TextButton(enabled = r.passes > 0, onClick = onShare) { Text("⤴ Share") }
            onUpload?.let { TextButton(onClick = it) { Text("☁ Upload") } }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !r.isOpen, onClick = onDelete) { Text("🗑 Delete") }
        }
    }
    HorizontalDivider()
}
