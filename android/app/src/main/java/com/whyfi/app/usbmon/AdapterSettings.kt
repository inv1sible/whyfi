package com.whyfi.app.usbmon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.ui.SettingsSection
import com.whyfi.app.update.AppUpdateSettings

/** Settings that only concern the external USB adapter, shown in the
 * adapter workspace's Settings tab, folded like the phone's (SettingsSection). */
@Composable
fun AdapterSettings(settingsRepository: SettingsRepository) {
    var monitorTestPassed by remember { mutableStateOf(settingsRepository.monitorTestPassed) }
    var noisePatterns by remember { mutableStateOf(settingsRepository.monitorNoisePatterns) }
    var noiseMessage by remember { mutableStateOf<String?>(null) }

    SettingsSection("USB monitor test", if (monitorTestPassed) "Passed" else "Optional, not run yet") {
        run {
            Text(
                if (monitorTestPassed) "Passed: this phone has received frames from an adapter."
                else "Optional: checks that a plugged-in adapter delivers frames. Capture works without it.",
                style = MaterialTheme.typography.bodySmall,
            )
            // In the card itself (user, 2026-11-09), scrolling inside it.
            UsbMonitorTestDialog(
                settingsRepository.backendUrl ?: BuildConfig.DEFAULT_BACKEND_URL,
                settingsRepository.sensorToken ?: "",
                onDismiss = {},
                onSuccessfulTest = {
                    settingsRepository.monitorTestPassed = true
                    monitorTestPassed = true
                },
                inline = true,
            )
        }
    }

    val patternCount = noisePatterns.lines().count { it.isNotBlank() }
    SettingsSection("Noise filter", if (patternCount == 0) "No patterns" else "$patternCount pattern${if (patternCount == 1) "" else "s"} for Live's Hide noise") {
        run {
            Text("Live's Hide noise button matches these case-insensitive patterns against device names, SSIDs, vendors, MACs and activity. One pattern per line. Favorites always remain visible. Raw captures and reports are unchanged.",
                style = MaterialTheme.typography.bodySmall)
            val validation = MonitorNoiseFilter.compile(noisePatterns)
            OutlinedTextField(value = noisePatterns, onValueChange = {
                noisePatterns = it
                noiseMessage = null
            }, label = { Text("Noise patterns (e.g. unknown device)") },
                minLines = 2, maxLines = 6, isError = validation.error != null,
                modifier = Modifier.fillMaxWidth())
            validation.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                settingsRepository.monitorNoisePatterns = noisePatterns
                noiseMessage = "Noise patterns saved."
            }, enabled = validation.error == null) { Text("Save noise patterns") }
            noiseMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    AppUpdateSettings(settingsRepository)

    var autoUpload by remember { mutableStateOf(settingsRepository.monitorAutoUpload) }
    SettingsSection("Mission upload", if (!settingsRepository.isConfigured) "No server set" else if (autoUpload) "After every Stop" else "Manual") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Upload missions automatically after every Stop: the log on any network, the PCAP on Wi‑Fi. " +
                "The server adds the AP readings to its WiFi data (source \"<phone>_USB\"). Local files stay.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(autoUpload, { autoUpload = it; settingsRepository.monitorAutoUpload = it })
        }
    }
}
