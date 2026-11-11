package com.whyfi.app.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.ui.rememberServerKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Settings check only: nothing newer. [serverVersion] is null when the server has no build. */
    data class Current(val serverVersion: String?) : UpdateState
    data class Offer(val release: AppRelease, val manual: Boolean) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState
    /** Downloaded and checked. [opened] = the installer was already started once. */
    data class Ready(val release: AppRelease, val apk: File, val opened: Boolean) : UpdateState
    data class Failed(val reason: String, val retry: Boolean) : UpdateState
}

/**
 * One update flow for the whole app: the check on start and the "Check for
 * update" buttons in Settings share this state, and [AppUpdateDialog] shows it.
 */
object AppUpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var startCheckedFor: String? = null

    /** Once per app start and server; says nothing unless a newer build is there. */
    fun checkOnStart(context: Context, serverKey: String) {
        if (startCheckedFor == serverKey || _state.value != UpdateState.Idle) return
        startCheckedFor = serverKey
        val settings = SettingsRepository(context.applicationContext)
        scope.launch {
            val found = AppUpdater.latest(settings) as? AppUpdater.Check.Found ?: return@launch
            if (_state.value == UpdateState.Idle &&
                AppUpdate.shouldOffer(BuildConfig.VERSION_CODE, found.release, settings.updateSkippedCode, manual = false))
                _state.value = UpdateState.Offer(found.release, manual = false)
        }
    }

    /** The button in Settings: always answers, also "up to date" and "could not check". */
    fun check(context: Context) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val settings = SettingsRepository(context.applicationContext)
        _state.value = UpdateState.Checking
        job = scope.launch {
            val next = when (val result = AppUpdater.latest(settings)) {
                is AppUpdater.Check.Failed -> UpdateState.Failed(result.reason, retry = false)
                AppUpdater.Check.Nothing -> UpdateState.Current(null)
                is AppUpdater.Check.Found ->
                    if (AppUpdate.shouldOffer(BuildConfig.VERSION_CODE, result.release, settings.updateSkippedCode, manual = true))
                        UpdateState.Offer(result.release, manual = true)
                    else UpdateState.Current("${result.release.versionName} (${result.release.versionCode})")
            }
            if (_state.value is UpdateState.Checking) _state.value = next
        }
    }

    /**
     * "Update now". Asks the server again first: the download address carries
     * a token that expires, and a newer build may have appeared meanwhile.
     */
    fun download(context: Context) {
        if (_state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        _state.value = UpdateState.Checking
        job = scope.launch {
            val release = when (val result = AppUpdater.latest(settings)) {
                is AppUpdater.Check.Found -> result.release
                is AppUpdater.Check.Failed -> return@launch finish(UpdateState.Failed(result.reason, retry = true))
                AppUpdater.Check.Nothing -> return@launch finish(UpdateState.Failed("The server has no build any more.", retry = false))
            }
            if (release.versionCode <= BuildConfig.VERSION_CODE) return@launch finish(UpdateState.Current("${release.versionName} (${release.versionCode})"))
            _state.value = UpdateState.Downloading(release, 0, release.apkBytes)
            var shownPercent = -1
            val next = try {
                val apk = AppUpdater.download(app, release) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else (done / 262_144).toInt()
                    if (percent != shownPercent) {
                        shownPercent = percent
                        _state.value = UpdateState.Downloading(release, done, total)
                    }
                }
                val problem = withContext(Dispatchers.IO) { AppUpdater.problem(app, apk, release) }
                if (problem == null) UpdateState.Ready(release, apk, opened = false)
                else {
                    apk.delete()
                    UpdateState.Failed("$problem The app on this phone is unchanged.", retry = false)
                }
            } catch (e: IOException) {
                UpdateState.Failed("${e.message ?: "The download failed."} The app on this phone is unchanged.", retry = true)
            }
            finish(next)
        }
    }

    private fun finish(next: UpdateState) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) _state.value = next
    }

    fun installerOpened() {
        (_state.value as? UpdateState.Ready)?.let { _state.value = it.copy(opened = true) }
    }

    /** "Later": the check on start stays quiet about this build; Settings still offers it. */
    fun later(context: Context) {
        (_state.value as? UpdateState.Offer)?.let { SettingsRepository(context.applicationContext).updateSkippedCode = it.release.versionCode }
        close()
    }

    /** Also cancels a running check or download. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }
}

/**
 * Shows the update flow over whatever screen is open. [busy] = a scan or USB
 * capture is running: the install restarts the app and would cut it off, so
 * the offer waits (start check) or the button is disabled (Settings check).
 */
@Composable
fun AppUpdateDialog(busy: Boolean) {
    val context = LocalContext.current
    val serverKey = rememberServerKey()
    LaunchedEffect(serverKey) {
        if (serverKey != null) AppUpdateController.checkOnStart(context.applicationContext, serverKey)
    }
    val state by AppUpdateController.state.collectAsState()
    val installed = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    fun openInstaller(apk: File) {
        try {
            context.startActivity(AppUpdater.installIntent(context, apk))
            AppUpdateController.installerOpened()
        } catch (e: Exception) {
            Toast.makeText(context, "Android's installer could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    when (val s = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking for an update") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = AppUpdateController::close) { Text("Cancel") } },
        )
        is UpdateState.Current -> AlertDialog(
            onDismissRequest = AppUpdateController::close,
            title = { Text("This phone is up to date") },
            text = {
                Text(if (s.serverVersion == null) "Your server has no build yet. This phone has whyfi $installed."
                    else "The newest build on your server is whyfi ${s.serverVersion}. This phone has $installed.")
            },
            confirmButton = { TextButton(onClick = AppUpdateController::close) { Text("OK") } },
        )
        is UpdateState.Offer -> if (s.manual || !busy) AlertDialog(
            onDismissRequest = {},
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("whyfi ${s.release.versionName} (${s.release.versionCode}) is on your server. This phone has $installed.")
                    if (s.release.notes.isNotEmpty()) {
                        Text("What's new", style = MaterialTheme.typography.labelLarge)
                        Text(s.release.notes, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    }
                    Text(
                        if (busy) "A scan or capture is running. Stop it first: the update restarts whyfi."
                        else "Android asks you to confirm the install. whyfi closes for it; your data and settings stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { AppUpdateController.download(context) }) { Text("Update now") }
            },
            dismissButton = { TextButton(onClick = { AppUpdateController.later(context) }) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading the update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fraction = AppUpdate.fraction(s.done, s.total)
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(AppUpdate.progressLabel(s.done, s.total), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = AppUpdateController::close) { Text("Cancel") } },
        )
        is UpdateState.Ready -> {
            // Straight into Android's installer once; the dialog stays for a second try.
            LaunchedEffect(s.apk) { if (!s.opened && !busy) openInstaller(s.apk) }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Ready to install") },
                text = {
                    Text(
                        if (busy) "whyfi ${s.release.versionName} is downloaded and checked. Stop the running scan or capture, then install."
                        else "whyfi ${s.release.versionName} is downloaded and checked. Android asks you to confirm; the first time it " +
                            "also asks to allow installs from whyfi. Open whyfi again afterwards.",
                    )
                },
                confirmButton = { TextButton(enabled = !busy, onClick = { openInstaller(s.apk) }) { Text("Install") } },
                dismissButton = { TextButton(onClick = AppUpdateController::close) { Text("Not now") } },
            )
        }
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = AppUpdateController::close,
            title = { Text("Update not possible") },
            text = { Text(s.reason) },
            confirmButton = {
                if (s.retry) TextButton(onClick = { AppUpdateController.download(context) }) { Text("Try again") }
                else TextButton(onClick = AppUpdateController::close) { Text("OK") }
            },
            dismissButton = { if (s.retry) TextButton(onClick = AppUpdateController::close) { Text("Close") } },
        )
    }
}
