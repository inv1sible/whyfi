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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.SettingsRepository

/** Settings that only concern the external USB adapter, shown in the
 * adapter workspace's Setup tab (moved out of the phone's Settings). */
@Composable
fun AdapterSettings(settingsRepository: SettingsRepository, onAdvancedModeChange: (Boolean) -> Unit) {
    var monitorTestPassed by remember { mutableStateOf(settingsRepository.monitorTestPassed) }
    var showUsbMonitorTest by remember { mutableStateOf(false) }
    var noisePatterns by remember { mutableStateOf(settingsRepository.monitorNoisePatterns) }
    var noiseMessage by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB monitor test", style = MaterialTheme.typography.titleLarge)
            Text(
                if (monitorTestPassed) "Passed. Capture is unlocked."
                else "Capture is locked until a USB monitor test receives frames.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { showUsbMonitorTest = true }) { Text("Run USB monitor test (experimental)") }
            if (showUsbMonitorTest) {
                UsbMonitorTestDialog(
                    settingsRepository.backendUrl ?: BuildConfig.DEFAULT_BACKEND_URL,
                    settingsRepository.sensorToken ?: "",
                    onDismiss = { showUsbMonitorTest = false },
                    onSuccessfulTest = {
                        settingsRepository.monitorTestPassed = true
                        settingsRepository.advancedModeEnabled = true
                        monitorTestPassed = true
                        onAdvancedModeChange(true)
                    },
                )
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Noise filter", style = MaterialTheme.typography.titleLarge)
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
}
