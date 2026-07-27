package com.whyfi.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.permissions.PermissionHelper
import com.whyfi.app.scan.ScanForegroundService
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
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
    val outboxUsage = rememberOutboxUsage(quotaMb.toIntOrNull() ?: settingsRepository.outboxQuotaMb)

    // Remote control needs a live handle on the scan service (to arm it and
    // to start it in the first place), so this screen binds too — same
    // pattern as ScanScreen/LanScreen.
    val context = LocalContext.current
    var service by remember { mutableStateOf<ScanForegroundService?>(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as ScanForegroundService.LocalBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }
    DisposableEffect(Unit) {
        context.bindService(Intent(context, ScanForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(connection) }
    }

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

/** Human-readable size of whatever is currently queued for upload. */
@Composable
private fun rememberOutboxUsage(quotaMb: Int): String {
    val context = LocalContext.current
    val usage by produceState(initialValue = "…", quotaMb) {
        val dao = WhyfiDatabase.getInstance(context).pendingScanDao()
        val bytes = dao.totalBytes() ?: 0L
        val count = dao.count()
        val readable = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
        value = "$readable of $quotaMb MB ($count scan${if (count == 1) "" else "s"} waiting to upload)"
    }
    return usage
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
