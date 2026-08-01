package com.whyfi.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.LocationSourcePreference
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.scan.ScanForegroundService

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
    var locationSource by remember { mutableStateOf(settingsRepository.locationSourcePreference) }
    var quotaMb by remember { mutableStateOf(settingsRepository.outboxQuotaMb.toString()) }
    var adaptiveScan by remember { mutableStateOf(settingsRepository.adaptiveScanEnabled) }
    var stationaryInterval by remember { mutableStateOf(settingsRepository.stationaryIntervalSeconds.toString()) }
    var walkingInterval by remember { mutableStateOf(settingsRepository.walkingIntervalSeconds.toString()) }
    var drivingInterval by remember { mutableStateOf(settingsRepository.drivingIntervalSeconds.toString()) }
    val outboxUsage = rememberOutboxUsage(quotaMb.toIntOrNull() ?: settingsRepository.outboxQuotaMb)

    val context = LocalContext.current

    var remoteControlOn by remember { mutableStateOf(settingsRepository.remoteControlEnabled) }
    val permissionsGranted = PermissionHelper.hasAllRequiredPermissions(context)

    Column(
        // Scrollable: this screen has outgrown a single phone height.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption("System", ThemePreference.SYSTEM, themePreference, onThemePreferenceChange)
            ThemeOption("Light", ThemePreference.LIGHT, themePreference, onThemePreferenceChange)
            ThemeOption("Dark", ThemePreference.DARK, themePreference, onThemePreferenceChange)
        }

        Text("Location source", style = MaterialTheme.typography.titleMedium)
        Text(
            "GPS is the original behavior (best of GPS/network) and stays the default. Fused uses Android's " +
                "combined location (API 31+); Both records the GPS/network reading as usual plus a separate fused " +
                "reading alongside it, for comparing the two.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocationSourceOption("GPS", LocationSourcePreference.GPS, locationSource) {
                locationSource = it
                settingsRepository.locationSourcePreference = it
            }
            LocationSourceOption("Fused", LocationSourcePreference.FUSED, locationSource) {
                locationSource = it
                settingsRepository.locationSourcePreference = it
            }
            LocationSourceOption("Both", LocationSourcePreference.BOTH, locationSource) {
                locationSource = it
                settingsRepository.locationSourcePreference = it
            }
        }

        Text("Point this app at your self-hosted whyfi backend (see docs/deployment.md for creating a sensor + token).")

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

        Text("Remote control", style = MaterialTheme.typography.titleMedium)
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

        Text("Scan cadence", style = MaterialTheme.typography.titleMedium)
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

        Text("Offline storage", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun LocationSourceOption(
    label: String,
    value: LocationSourcePreference,
    current: LocationSourcePreference,
    onSelect: (LocationSourcePreference) -> Unit,
) {
    FilterChip(selected = current == value, onClick = { onSelect(value) }, label = { Text(label) })
}
