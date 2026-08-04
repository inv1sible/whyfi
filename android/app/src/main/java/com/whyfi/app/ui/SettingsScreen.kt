package com.whyfi.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.CrashReportRequest
import com.whyfi.app.diagnostics.CrashLogReader
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.scan.ScanForegroundService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONObject

// Mirrors SETUP_QR_PREFIX in frontend/src/pages/settings/SensorsTab.tsx —
// change one, change both. Lets a scan tell "this is a whyfi setup code"
// apart from an arbitrary QR code before it tries to parse JSON out of it.
private const val SETUP_QR_PREFIX = "whyfi-setup:"

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    service: ScanForegroundService?,
) {
    // Pre-filled from the build (WHYFI_PUBLIC_URL, set when this APK was
    // built) if nothing's been saved yet — still just a starting point,
    // fully editable. Never pre-filled with a token; see MEMORY.md.
    var backendUrl by remember {
        mutableStateOf(settingsRepository.backendUrl ?: BuildConfig.DEFAULT_BACKEND_URL)
    }
    var token by remember { mutableStateOf(settingsRepository.sensorToken ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var quotaMb by remember { mutableStateOf(settingsRepository.outboxQuotaMb.toString()) }
    var adaptiveScan by remember { mutableStateOf(settingsRepository.adaptiveScanEnabled) }
    var stationaryInterval by remember { mutableStateOf(settingsRepository.stationaryIntervalSeconds.toString()) }
    var walkingInterval by remember { mutableStateOf(settingsRepository.walkingIntervalSeconds.toString()) }
    var drivingInterval by remember { mutableStateOf(settingsRepository.drivingIntervalSeconds.toString()) }
    val outboxUsage = rememberOutboxUsage(quotaMb.toIntOrNull() ?: settingsRepository.outboxQuotaMb)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var remoteControlOn by remember { mutableStateOf(settingsRepository.remoteControlEnabled) }
    val permissionsGranted = PermissionHelper.hasAllRequiredPermissions(context)

    Column(
        // Scrollable: this screen has outgrown a single phone height.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // -- Appearance --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeOption("System", ThemePreference.SYSTEM, themePreference, onThemePreferenceChange)
                    ThemeOption("Light", ThemePreference.LIGHT, themePreference, onThemePreferenceChange)
                    ThemeOption("Dark", ThemePreference.DARK, themePreference, onThemePreferenceChange)
                }
            }
        }

        // -- Location source --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Location source", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each scan records both the GPS/network reading and Android's fused location " +
                        "(API 31+) side by side, so the two can be compared on the backend. The " +
                        "device picks whichever fix is freshest; no manual selection needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // -- Remote control --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remote control", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Lets whyfi in your browser start and stop scanning on this phone, and set how often it scans. " +
                        "The phone checks in with the backend every few seconds and does what it's told — Android " +
                        "doesn't allow a server to wake a phone's scanner, so the app has to stay running, which means " +
                        "a permanent notification and extra battery use. After a reboot or force-stop, come back here " +
                        "to switch it on again.",
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = remoteControlOn,
                        enabled = permissionsGranted && service != null,
                        onCheckedChange = { wanted ->
                            if (wanted) ScanForegroundService.start(context)
                            service?.setRemoteControlEnabled(wanted)
                            remoteControlOn = wanted
                        },
                    )
                    Text(if (remoteControlOn) "On — obeying the backend" else "Off")
                }
                if (!permissionsGranted) {
                    Text(
                        "Grant scanning permissions on the Scan tab first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (remoteControlOn) {
                    // Vendor battery managers (Samsung, Xiaomi, OnePlus…) kill even
                    // foreground services; without an exemption the agent quietly
                    // dies overnight and the web UI just shows the phone as offline.
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) {
                        Text("Exempt whyfi from battery optimisation")
                    }
                }
            }
        }

        // -- Backend connection (no header — continues the flow from remote control) --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backend connection", style = MaterialTheme.typography.headlineSmall)
                Text("Point this app at your self-hosted whyfi backend (see docs/deployment.md for creating a sensor + token).")

                val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                    val raw = result.contents
                    when {
                        raw == null -> Unit // user backed out of the scanner; nothing to report
                        !raw.startsWith(SETUP_QR_PREFIX) -> savedMessage = "That QR code isn't a whyfi setup code."
                        else -> runCatching {
                            val payload = JSONObject(raw.removePrefix(SETUP_QR_PREFIX))
                            val newBackend = payload.getString("backend")
                            val newToken = payload.getString("token")
                            val name = payload.optString("name", "")
                            backendUrl = newBackend
                            token = newToken
                            settingsRepository.backendUrl = newBackend
                            settingsRepository.sensorToken = newToken
                            savedMessage = if (name.isNotBlank()) "Configured for \"$name\" and saved." else "Configured and saved."
                        }.onFailure { e ->
                            savedMessage = "Could not read that QR code: ${e.message}"
                        }
                    }
                }
                Button(onClick = {
                    qrScanLauncher.launch(
                        ScanOptions()
                            .setPrompt("Scan the setup QR code from Settings → Sensors on the whyfi web UI")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                }) {
                    Text("Scan QR to configure")
                }

                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Backend URL (e.g. http://192.168.1.50:8000)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Sensor token") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(onClick = {
                    settingsRepository.backendUrl = backendUrl
                    settingsRepository.sensorToken = token
                    savedMessage = "Saved."
                }) {
                    Text("Save")
                }

                savedMessage?.let { Text(it) }
            }
        }

        // -- Scan cadence --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan cadence", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A phone sitting on a desk keeps re-scanning the same airwaves; a phone in a car covers new " +
                        "ground every second. With this on, the scanner picks its interval from how the phone is " +
                        "actually moving — which is where most of the battery saving is.",
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = adaptiveScan,
                        onCheckedChange = {
                            adaptiveScan = it
                            settingsRepository.adaptiveScanEnabled = it
                        },
                    )
                    Text(if (adaptiveScan) "On — interval follows movement" else "Off — one fixed interval")
                }

                if (adaptiveScan) {
                    IntervalField("Stationary (seconds)", stationaryInterval) { stationaryInterval = it }
                    IntervalField("Walking (seconds)", walkingInterval) { walkingInterval = it }
                    IntervalField("Driving (seconds)", drivingInterval) { drivingInterval = it }
                    Text(
                        "30 seconds is the practical floor while WiFi is included — Android allows 4 WiFi scans " +
                            "per 2 minutes, so a shorter interval produces no extra scans. Movement is detected " +
                            "with the phone's motion sensors; speed (to tell walking from driving) comes from the " +
                            "positions each scan already records, so nothing extra wakes the GPS.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            val stationary = stationaryInterval.toIntOrNull()
                            val walking = walkingInterval.toIntOrNull()
                            val driving = drivingInterval.toIntOrNull()
                            if (stationary == null || walking == null || driving == null ||
                                listOf(stationary, walking, driving).any { it < SettingsRepository.MIN_INTERVAL_SECONDS }
                            ) {
                                savedMessage = "Each interval must be at least ${SettingsRepository.MIN_INTERVAL_SECONDS} seconds."
                            } else {
                                settingsRepository.stationaryIntervalSeconds = stationary
                                settingsRepository.walkingIntervalSeconds = walking
                                settingsRepository.drivingIntervalSeconds = driving
                                // Read back: the setters clamp, so show what was stored.
                                stationaryInterval = settingsRepository.stationaryIntervalSeconds.toString()
                                walkingInterval = settingsRepository.walkingIntervalSeconds.toString()
                                drivingInterval = settingsRepository.drivingIntervalSeconds.toString()
                                savedMessage = "Scan cadence saved."
                            }
                        },
                    ) {
                        Text("Save scan cadence")
                    }
                    if (remoteControlOn) {
                        Text(
                            "Remote control is on, so these are driven from the web UI — whatever you set here " +
                                "will be replaced by the backend's values on the next check-in.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // -- Offline storage --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Offline storage", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Scans are queued on the phone whenever the backend is unreachable, then uploaded automatically. " +
                        "This caps how much space that queue may use — once it's full, the oldest queued scans are " +
                        "dropped to make room. A reachable backend keeps the queue near empty, so this only matters " +
                        "while you're offline. A scan pass is roughly 5–30 KB depending on how many networks and " +
                        "devices are around.",
                )
                Text("Currently using $outboxUsage", style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = quotaMb,
                    onValueChange = { quotaMb = it.filter(Char::isDigit) },
                    label = { Text("Storage limit (MB)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        val parsed = quotaMb.toIntOrNull()
                        if (parsed == null || parsed < SettingsRepository.MIN_OUTBOX_QUOTA_MB) {
                            savedMessage = "Storage limit must be at least ${SettingsRepository.MIN_OUTBOX_QUOTA_MB} MB."
                        } else {
                            settingsRepository.outboxQuotaMb = parsed
                            // Read back, since the setter clamps to the allowed range.
                            quotaMb = settingsRepository.outboxQuotaMb.toString()
                            savedMessage = "Storage limit saved."
                        }
                    },
                ) {
                    Text("Save storage limit")
                }
            }
        }

        // -- Diagnostics --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "If whyfi crashes, the exception is written to this app's private storage before Android's normal " +
                        "crash handling takes over, so it can be read and sent from here afterward — no computer, adb, or " +
                        "root needed. Reviewed and acted on from the web UI's Crash Reports page.",
                )

                var crashLogVersion by remember { mutableStateOf(0) }
                var crashLogMessage by remember { mutableStateOf<String?>(null) }
                var sendingCrashLog by remember { mutableStateOf(false) }
                val crashLogText = remember(crashLogVersion) { CrashLogReader.readCrashLog(context) }

                if (crashLogText == null) {
                    Text("No crash recorded.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectionContainer {
                        Text(crashLogText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !sendingCrashLog,
                            onClick = {
                                val parsed = CrashLogReader.parse(crashLogText)
                                if (backendUrl.isBlank() || token.isBlank()) {
                                    crashLogMessage = "Configure a backend URL and sensor token above first."
                                } else if (parsed == null) {
                                    crashLogMessage = "Could not parse the crash log."
                                } else {
                                    sendingCrashLog = true
                                    crashLogMessage = null
                                    scope.launch {
                                        try {
                                            val api = ApiClientFactory.create(backendUrl)
                                            val response = api.uploadCrashReport(
                                                "Token $token",
                                                CrashReportRequest(
                                                    occurredAt = parsed.occurredAt,
                                                    appVersion = BuildConfig.VERSION_NAME,
                                                    deviceModel = Build.MODEL,
                                                    osVersion = Build.VERSION.RELEASE,
                                                    stackTrace = parsed.stackTrace,
                                                ),
                                            )
                                            crashLogMessage = if (response.isSuccessful) {
                                                "Sent — view it on the web UI's Crash Reports page."
                                            } else {
                                                "Send failed (HTTP ${response.code()})."
                                            }
                                        } catch (e: IOException) {
                                            crashLogMessage = "Could not reach the backend: ${e.message}"
                                        } finally {
                                            sendingCrashLog = false
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (sendingCrashLog) "Sending…" else "Send to server")
                        }
                        TextButton(onClick = {
                            CrashLogReader.clearCrashLog(context)
                            crashLogVersion++
                            crashLogMessage = "Crash log cleared."
                        }) {
                            Text("Clear log")
                        }
                    }
                    crashLogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** Human-readable size of whatever is currently queued for upload, against
 * the configured budget. The read itself is shared with the Dashboard —
 * see [rememberOutboxStatus]. */
@Composable
private fun rememberOutboxUsage(quotaMb: Int): String {
    val status = rememberOutboxStatus(quotaMb) ?: return "…"
    val plural = if (status.count == 1) "" else "s"
    return "${formatBytes(status.bytes)} of $quotaMb MB " +
        "(${status.count} scan$plural waiting to upload)"
}

@Composable
private fun IntervalField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ThemeOption(
    label: String,
    value: ThemePreference,
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    FilterChip(selected = current == value, onClick = { onSelect(value) }, label = { Text(label) })
}

