package com.whyfi.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.RecordingStore
import com.whyfi.app.data.local.RecordingSummary
import com.whyfi.app.scan.RecordingFormat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether a server is set up, updated live as Settings / the setup QR change it.
 * Without one, whyfi is standalone: server-only features are hidden. */
@Composable
fun rememberServerConfigured(): Boolean = rememberServerKey() != null

/** The configured server's identity (URL + token), or null when standalone; live. */
@Composable
fun rememberServerKey(): String? {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context.applicationContext) }
    fun current() = settings.serverKey.takeIf { settings.isConfigured }
    var key by remember { mutableStateOf(current()) }
    DisposableEffect(Unit) {
        val prefs = context.applicationContext.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> key = current() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return key
}

/**
 * Ask on connect: once a server is set up (QR, pasted code, deep link or
 * typed in), recordings made standalone are offered for upload: all, a
 * choice, or none. The answer is remembered per server, so the question
 * comes once; recordings kept local can still be uploaded from Recordings.
 */
@Composable
fun ConnectUploadPrompt() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context.applicationContext) }
    val store = remember { RecordingStore(context.applicationContext) }
    val serverKey = rememberServerKey()
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<List<RecordingSummary>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }

    LaunchedEffect(serverKey) {
        offer = if (serverKey == null || settings.uploadPromptAnsweredFor == serverKey) emptyList()
        else withContext(Dispatchers.IO) { store.summaries().filter { !it.syncAllowed && !it.isOpen && it.passes > 0 } }
        chosen.clear()
        chosen.addAll(offer.map { it.id })
        choosing = false
    }
    if (offer.isEmpty()) return

    fun answer(ids: List<String>) {
        settings.uploadPromptAnsweredFor = settings.serverKey
        offer = emptyList()
        if (ids.isEmpty()) return
        scope.launch { withContext(Dispatchers.IO) { store.allowSync(ids) } }
    }

    val bytes = offer.sumOf { it.bytes }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Upload missions to this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${offer.size} mission${if (offer.size == 1) "" else "s"} (${RecordingFormat.bytes(bytes)}) were made " +
                        "without a server. They stay on this phone either way; you can still upload them later from Scan → Missions.",
                )
                if (choosing) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(offer, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (r.id in chosen) chosen.remove(r.id) else chosen.add(r.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = r.id in chosen, onCheckedChange = { if (it) chosen.add(r.id) else chosen.remove(r.id) })
                            Column {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtMs)),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("${RecordingFormat.kind(r.kind)} · ${r.passes} passes · ${RecordingFormat.bytes(r.bytes)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (choosing) TextButton(onClick = { answer(chosen.toList()) }) { Text("Upload ${chosen.size}") }
            else TextButton(onClick = { answer(offer.map { it.id }) }) { Text("Upload all") }
        },
        dismissButton = {
            Row {
                if (!choosing) TextButton(onClick = { choosing = true }) { Text("Choose…") }
                TextButton(onClick = { answer(emptyList()) }) { Text("Keep on phone") }
            }
        },
    )
}
