package com.whyfi.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.whyfi.app.data.FavoritesRepository
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.ThemePreference
import com.whyfi.app.mission.MissionController
import com.whyfi.app.mission.MissionScreen
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.ui.theme.WhyfiTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var missionController: MissionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)
        favoritesRepository = FavoritesRepository(applicationContext)
        // Constructed here, not per-screen, so a tracking session (see
        // MissionController.start) survives navigating back to Dashboard/
        // Scan — those screens' stat chip reads its uiState too, for the
        // live indicator dot.
        missionController = MissionController(applicationContext, settingsRepository)

        // Handle a deep link or shared text containing a whyfi-setup:{json}
        // payload — auto-configures backend URL + sensor token so the user
        // doesn't have to type them. Works from a whyfi-setup:// link (web
        // UI) or a text-share (clipboard paste → share to whyfi).
        handleSetupIntent(intent)

        setContent {
            var themePreference by remember { mutableStateOf(settingsRepository.themePreference) }

            WhyfiTheme(themePreference = themePreference) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WhyfiApp(
                        settingsRepository = settingsRepository,
                        favoritesRepository = favoritesRepository,
                        missionController = missionController,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupIntent(intent)
    }

    /** Extracts a whyfi-setup:{json} payload from a deep link or shared text
     * and applies the backend URL + sensor token. The same payload format
     * the QR code uses (see SettingsScreen's SETUP_QR_PREFIX) — a clickable
     * link from the web UI or a shared text both work. */
    private fun handleSetupIntent(intent: Intent?) {
        val raw = extractSetupPayload(intent) ?: return
        if (!raw.startsWith("whyfi-setup:")) return
        runCatching {
            val payload = org.json.JSONObject(raw.removePrefix("whyfi-setup:"))
            val backend = payload.getString("backend")
            val token = payload.getString("token")
            settingsRepository.backendUrl = backend
            settingsRepository.sensorToken = token
            Log.i("MainActivity", "Auto-configured from setup link")
        }.onFailure { e ->
            Log.e("MainActivity", "Could not parse setup payload", e)
        }
    }

    private fun extractSetupPayload(intent: Intent?): String? {
        intent ?: return null
        // Deep link: whyfi-setup://{json} or whyfi-setup:{json} as data
        val data = intent.dataString
        if (data != null && data.startsWith("whyfi-setup:")) return data
        // Shared text (SEND intent, text/plain)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null && text.contains("whyfi-setup:")) {
            return text.substring(text.indexOf("whyfi-setup:"))
        }
        return null
    }
}

/** Emoji rather than vector icons, matching RadioStatChip — the app has no
 * icon library on the classpath and the radio glyphs here are the same ones
 * used for results throughout, so they already mean something to the reader. */
private const val DASHBOARD_ICON = "📊"
private const val SCAN_ICON = "🔍"
private const val LAN_ICON = "🌐"
private const val SETTINGS_ICON = "⚙️"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WhyfiApp(
    settingsRepository: SettingsRepository,
    favoritesRepository: FavoritesRepository,
    missionController: MissionController,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
) {
    // rememberSaveable, not remember: rotating the phone shouldn't throw you
    // out of a results table back to the Scan tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var detailRadio by rememberSaveable { mutableStateOf<String?>(null) }
    var showMission by rememberSaveable { mutableStateOf(false) }

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
    // A separate, parallel drill-down, not nested inside the one above —
    // the two are never open at once (the Mission chip only ever sets
    // showMission, never alongside detailRadio), so either BackHandler
    // closing its own screen is sufficient.
    BackHandler(enabled = showMission) { showMission = false }

    val activeDetail = detailRadio?.let { name -> RadioKind.entries.firstOrNull { it.name == name } }
    if (activeDetail != null) {
        ScanDetailScreen(
            uiState = uiState,
            kind = activeDetail,
            onKindChange = { detailRadio = it.name },
            onBack = { detailRadio = null },
            favoritesRepository = favoritesRepository,
        )
        return
    }

    if (showMission) {
        MissionScreen(
            favoritesRepository = favoritesRepository,
            missionController = missionController,
            service = service,
            onBack = { showMission = false },
        )
        return
    }

    // HorizontalPager drives the tab content: swiping between pages changes
    // the visible tab, and tapping a tab scrolls the pager to it. The pager
    // wraps only the four tab screens — drill-downs (ScanDetailScreen,
    // MissionScreen) early-return above this, so swipe never reaches them.
    // The system back button closes those via the BackHandlers above.
    val pagerState = rememberPagerState(pageCount = { 4 })

    // Bidirectional sync: pager swipe → selectedTab, tab tap → pager page.
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) selectedTab = pagerState.currentPage
    }
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            WhyfiTab(DASHBOARD_ICON, "Dashboard", 0, pagerState.currentPage) { selectedTab = 0 }
            WhyfiTab(SCAN_ICON, "Scan", 1, pagerState.currentPage) { selectedTab = 1 }
            WhyfiTab(LAN_ICON, "LAN", 2, pagerState.currentPage) { selectedTab = 2 }
            WhyfiTab(SETTINGS_ICON, "Settings", 3, pagerState.currentPage) { selectedTab = 3 }
        }

        val openMission: () -> Unit = { showMission = true }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> DashboardScreen(
                    service = service, uiState = uiState, onOpenDetail = openDetail,
                    onOpenMission = openMission, missionController = missionController,
                )
                1 -> ScanScreen(
                    service = service, uiState = uiState, onOpenDetail = openDetail,
                    onOpenMission = openMission, missionController = missionController,
                )
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
