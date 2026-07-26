package com.whyfi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.whyfi.app.data.LocationSourcePreference
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference

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

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
    }
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
