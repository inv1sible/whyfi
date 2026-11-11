package com.whyfi.app.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.ui.SettingsSection
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
    /** Settings check only: nothing newer. One line per source asked. */
    data class Current(val lines: List<String>) : UpdateState
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
    /** The source of the last "Update now", for "Try again". */
    private var retrySource: UpdateSource? = null

    private fun found(answers: List<AppUpdater.Answer>): AppRelease? =
        AppUpdate.best(answers.mapNotNull { (it.check as? AppUpdater.Check.Found)?.release })

    private fun line(answer: AppUpdater.Answer): String = when (val check = answer.check) {
        is AppUpdater.Check.Found -> "${answer.source.label}: whyfi ${check.release.versionName} (${check.release.versionCode})"
        AppUpdater.Check.Nothing -> "${answer.source.label}: no build yet"
        is AppUpdater.Check.Failed -> check.reason
    }

    /**
     * Once per app start and set of sources ([sourcesKey]); says nothing
     * unless a newer build is there.
     */
    fun checkOnStart(context: Context, sourcesKey: String) {
        if (startCheckedFor == sourcesKey || _state.value != UpdateState.Idle) return
        startCheckedFor = sourcesKey
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        scope.launch {
            val release = found(AppUpdater.latestAll(app, settings)) ?: return@launch
            if (_state.value == UpdateState.Idle &&
                AppUpdate.shouldOffer(BuildConfig.VERSION_CODE, release, settings.updateSkippedCode, manual = false))
                _state.value = UpdateState.Offer(release, manual = false)
        }
    }

    /** The button in Settings: always answers, also "up to date" and "could not check". */
    fun check(context: Context) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        _state.value = UpdateState.Checking
        job = scope.launch {
            val answers = AppUpdater.latestAll(app, settings)
            val release = found(answers)
            val next = when {
                answers.isEmpty() -> UpdateState.Failed(
                    if (AppUpdater.officialBuild(app)) "There is nowhere to look: set up a server or switch on GitHub."
                    else "There is nowhere to look: set up a server. This copy was built with its own signing key, " +
                        "so builds from GitHub cannot update it.",
                    retry = false)
                release != null && AppUpdate.shouldOffer(BuildConfig.VERSION_CODE, release, settings.updateSkippedCode, manual = true) ->
                    UpdateState.Offer(release, manual = true)
                answers.all { it.check is AppUpdater.Check.Failed } -> UpdateState.Failed(answers.joinToString(" ", transform = ::line), retry = false)
                else -> UpdateState.Current(answers.map(::line))
            }
            if (_state.value is UpdateState.Checking) _state.value = next
        }
    }

    /**
     * "Update now" for the build that was offered. Asks its source again
     * first: the server's download address carries a token that expires, and
     * a newer build may have appeared meanwhile.
     */
    fun download(context: Context) {
        val source = when (val now = _state.value) {
            is UpdateState.Offer -> now.release.source
            is UpdateState.Failed -> retrySource ?: return
            else -> return
        }
        retrySource = source
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        _state.value = UpdateState.Checking
        job = scope.launch {
            val release = when (val result = AppUpdater.latest(source, settings)) {
                is AppUpdater.Check.Found -> result.release
                is AppUpdater.Check.Failed -> return@launch finish(UpdateState.Failed(result.reason, retry = true))
                AppUpdater.Check.Nothing -> return@launch finish(UpdateState.Failed("${source.label} has no build any more.", retry = false))
            }
            if (release.versionCode <= BuildConfig.VERSION_CODE)
                return@launch finish(UpdateState.Current(listOf("${source.label}: whyfi ${release.versionName} (${release.versionCode})")))
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
        val app = context.applicationContext
        val sources = withContext(Dispatchers.IO) { AppUpdater.sources(app, SettingsRepository(app)) }
        if (sources.isNotEmpty()) AppUpdateController.checkOnStart(app, "$serverKey|$sources")
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This phone has whyfi $installed. Nothing newer was found.")
                    s.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = AppUpdateController::close) { Text("OK") } },
        )
        is UpdateState.Offer -> if (s.manual || !busy) AlertDialog(
            onDismissRequest = {},
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("whyfi ${s.release.versionName} (${s.release.versionCode}) is ${s.release.source.where}. This phone has $installed.")
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

/**
 * The "App update" section of phone Settings and USB Settings: where whyfi
 * looks, the GitHub switch, and the manual check.
 */
@Composable
fun AppUpdateSettings(settings: SettingsRepository) {
    val context = LocalContext.current
    val serverKey = rememberServerKey()
    val official = remember { AppUpdater.officialBuild(context.applicationContext) }
    var github by remember { mutableStateOf(settings.updateFromGithub) }
    val places = listOfNotNull("your server".takeIf { serverKey != null }, "GitHub".takeIf { github && official })
    SettingsSection("App update", if (places.isEmpty()) "Off" else "From ${places.joinToString(" and ")}") {
        Text(
            "When whyfi starts it looks for a newer build" +
                (if (places.isEmpty()) ": nowhere at the moment." else " on ${places.joinToString(" and ")} and offers the newest one.") +
                " Android asks you to confirm every install.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (official) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also look on GitHub (github.com/${AppUpdate.GITHUB_REPO}). Nothing about this phone is sent.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(github, { github = it; settings.updateFromGithub = it })
        } else Text("This copy was built with its own signing key, so builds from GitHub cannot update it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = places.isNotEmpty(), onClick = { AppUpdateController.check(context) }) { Text("Check for update") }
    }
}
