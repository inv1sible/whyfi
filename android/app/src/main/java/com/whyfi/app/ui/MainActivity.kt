package com.whyfi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference
import com.whyfi.app.ui.theme.WhyfiTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)

        setContent {
            var themePreference by remember { mutableStateOf(settingsRepository.themePreference) }

            WhyfiTheme(themePreference = themePreference) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WhyfiApp(
                        settingsRepository = settingsRepository,
                        themePreference = themePreference,
                        onThemePreferenceChange = {
                            settingsRepository.themePreference = it
                            themePreference = it
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WhyfiApp(
    settingsRepository: SettingsRepository,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Scan") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("LAN") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Settings") })
        }

        when (selectedTab) {
            0 -> ScanScreen()
            1 -> LanScreen()
            2 -> SettingsScreen(
                settingsRepository = settingsRepository,
                themePreference = themePreference,
                onThemePreferenceChange = onThemePreferenceChange,
            )
        }
    }
}
