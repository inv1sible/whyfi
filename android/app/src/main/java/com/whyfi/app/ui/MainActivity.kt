package com.whyfi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference
import com.whyfi.app.scan.RadioKind
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

/** Emoji rather than vector icons, matching RadioStatChip — the app has no
 * icon library on the classpath and the radio glyphs here are the same ones
 * used for results throughout, so they already mean something to the reader. */
private const val DASHBOARD_ICON = "📊"
private const val SCAN_ICON = "🔍"
private const val LAN_ICON = "🌐"
private const val SETTINGS_ICON = "⚙️"

@Composable
private fun WhyfiApp(
    settingsRepository: SettingsRepository,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
) {
    // rememberSaveable, not remember: rotating the phone shouldn't throw you
    // out of a results table back to the Scan tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var detailRadio by rememberSaveable { mutableStateOf<String?>(null) }

    // Bound once here for the life of the app rather than per screen — see
    // rememberScanService's KDoc for why that matters.
    val service = rememberScanService()
    val uiState by rememberScanState(service)

    val openDetail: (RadioKind) -> Unit = { detailRadio = it.name }

    // One level of drill-down over the tabs, rather than pulling in
    // Navigation Compose for a single route. The system back button has to
    // close it — without this it would leave the app instead, which reads as
    // a crash.
    BackHandler(enabled = detailRadio != null) { detailRadio = null }

    val activeDetail = detailRadio?.let { name -> RadioKind.entries.firstOrNull { it.name == name } }
    if (activeDetail != null) {
        ScanDetailScreen(
            uiState = uiState,
            kind = activeDetail,
            onKindChange = { detailRadio = it.name },
            onBack = { detailRadio = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            WhyfiTab(DASHBOARD_ICON, "Dashboard", 0, selectedTab) { selectedTab = 0 }
            WhyfiTab(SCAN_ICON, "Scan", 1, selectedTab) { selectedTab = 1 }
            WhyfiTab(LAN_ICON, "LAN", 2, selectedTab) { selectedTab = 2 }
            WhyfiTab(SETTINGS_ICON, "Settings", 3, selectedTab) { selectedTab = 3 }
        }

        when (selectedTab) {
            0 -> DashboardScreen(service = service, uiState = uiState, onOpenDetail = openDetail)
            1 -> ScanScreen(service = service, uiState = uiState, onOpenDetail = openDetail)
            2 -> LanScreen(service = service, uiState = uiState)
            3 -> SettingsScreen(
                settingsRepository = settingsRepository,
                themePreference = themePreference,
                onThemePreferenceChange = onThemePreferenceChange,
                service = service,
            )
        }
    }
}

@Composable
private fun WhyfiTab(icon: String, label: String, index: Int, selectedTab: Int, onClick: () -> Unit) {
    Tab(
        selected = selectedTab == index,
        onClick = onClick,
        text = { Text(label) },
        icon = { Text(icon, fontSize = 16.sp) },
    )
}
