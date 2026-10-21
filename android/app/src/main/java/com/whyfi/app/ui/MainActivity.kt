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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.whyfi.app.usbmon.MonitorScreen
import com.whyfi.app.ui.theme.WhyfiTheme
import kotlinx.coroutines.launch

/** A whyfi-setup:{json} payload waiting on user confirmation before it's
 * applied — see MainActivity.handleSetupIntent. */
private data class PendingSetup(val backend: String, val token: String, val name: String)

private const val SETUP_PREFIX = "whyfi-setup:"

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var missionController: MissionController

    // Set from handleSetupIntent (onCreate/onNewIntent, not a Composable
    // context), read from setContent's tree below — mutableStateOf still
    // triggers recomposition for readers regardless of where it's declared;
    // remember isn't usable here since there's no composition yet.
    private var pendingSetup by mutableStateOf<PendingSetup?>(null)

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
        // payload — offers to auto-configure backend URL + sensor token so
        // the user doesn't have to type them. Works from a whyfi-setup://
        // link (web UI) or a text-share (clipboard paste → share to whyfi).
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
                    // A whyfi-setup link points this device at a new backend
                    // and hands it a sensor token — applying that silently
                    // would let anyone who gets a link (or shared text) in
                    // front of the user repoint the app and exfiltrate scans
                    // via the swapped token. Requires an explicit tap before
                    // either value is written.
                    pendingSetup?.let { pending ->
                        SetupConfirmationDialog(
                            pending = pending,
                            onConfirm = {
                                settingsRepository.backendUrl = pending.backend
                                settingsRepository.sensorToken = pending.token
                                Log.i("MainActivity", "Auto-configured from setup link")
                                pendingSetup = null
                            },
                            onDismiss = { pendingSetup = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupIntent(intent)
    }

    /** Extracts a whyfi-setup:{json} payload from a deep link or shared text
     * and, if it parses and points at an https backend, stages it as
     * [pendingSetup] for the confirmation dialog rather than applying it
     * directly — the same payload format the QR code uses (see
     * SettingsScreen's SETUP_QR_PREFIX). */
    private fun handleSetupIntent(intent: Intent?) {
        val raw = extractSetupPayload(intent) ?: return
        if (!raw.startsWith(SETUP_PREFIX)) return
        runCatching {
            val payload = org.json.JSONObject(raw.removePrefix(SETUP_PREFIX))
            val backend = payload.getString("backend")
            val token = payload.getString("token")
            val name = payload.optString("name", "")
            if (!backend.startsWith("https://")) {
                Log.w("MainActivity", "Ignoring setup link with a non-https backend URL")
                return@runCatching
            }
            pendingSetup = PendingSetup(backend, token, name)
        }.onFailure { e ->
            Log.e("MainActivity", "Could not parse setup payload", e)
        }
    }

    private fun extractSetupPayload(intent: Intent?): String? {
        intent ?: return null
        // Deep link: whyfi-setup://{json} or whyfi-setup:{json} as data
        val data = intent.dataString
        if (data != null && data.startsWith(SETUP_PREFIX)) return data
        // Shared text (SEND intent, text/plain)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null && text.contains(SETUP_PREFIX)) {
            return text.substring(text.indexOf(SETUP_PREFIX))
        }
        return null
    }
}

/** Shows the incoming backend URL and a masked token so the user can tell
 * this is the setup link they expect before it overwrites their current
 * configuration. */
@Composable
private fun SetupConfirmationDialog(pending: PendingSetup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply new backend setup?") },
        text = {
            Column {
                if (pending.name.isNotBlank()) {
                    Text("Name: ${pending.name}")
                }
                Text("Backend: ${pending.backend}")
                Text("Token: ${maskToken(pending.token)}")
                Text("This replaces your current backend URL and sensor token.")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun maskToken(token: String): String =
    if (token.length <= 8) "•".repeat(token.length) else "${token.take(4)}${"•".repeat(token.length - 8)}${token.takeLast(4)}"

/** Emoji rather than vector icons, matching RadioStatChip — the app has no
 * icon library on the classpath and the radio glyphs here are the same ones
 * used for results throughout, so they already mean something to the reader. */
private const val DASHBOARD_ICON = "📊"
private const val SCAN_ICON = "🔍"
private const val LAN_ICON = "🌐"
private const val MONITOR_ICON = "📡"
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
    var advancedMode by remember { mutableStateOf(settingsRepository.advancedModeEnabled) }
    // Keep the layout stable: Monitor is always discoverable, and its own
    // screen explains how the USB test unlocks receive controls.
    val tabCount = 5
    var detailRadio by rememberSaveable { mutableStateOf<String?>(null) }
    var showMission by rememberSaveable { mutableStateOf(false) }

    // Backs the tab bar with a swipeable pager. selectedTab stays the
    // source of truth for TabRow's indicator and survives rotation via
    // rememberSaveable above; the pager is kept in sync with it in both
    // directions below rather than replacing it, since PagerState's own
    // Saver isn't wired through the same drill-down early-returns this
    // composable already has.
    val pagerState = rememberPagerState(initialPage = selectedTab.coerceAtMost(tabCount - 1)) { tabCount }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { selectedTab = pagerState.currentPage }
    val goToTab: (Int) -> Unit = { index ->
        coroutineScope.launch { pagerState.animateScrollToPage(index) }
    }

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

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab.coerceAtMost(tabCount - 1)) {
            WhyfiTab(DASHBOARD_ICON, "Dashboard", 0, selectedTab) { goToTab(0) }
            WhyfiTab(SCAN_ICON, "Scan", 1, selectedTab) { goToTab(1) }
            WhyfiTab(LAN_ICON, "LAN", 2, selectedTab) { goToTab(2) }
            WhyfiTab(MONITOR_ICON, "Monitor", 3, selectedTab, enabled = advancedMode) { goToTab(3) }
            WhyfiTab(SETTINGS_ICON, "Settings", 4, selectedTab) { goToTab(4) }
        }

        val openMission: () -> Unit = { showMission = true }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
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
                3 -> MonitorScreen(
                    service = service,
                    settingsRepository = settingsRepository,
                    unlocked = advancedMode,
                )
                4 -> SettingsScreen(
                    settingsRepository = settingsRepository,
                    themePreference = themePreference,
                    onThemePreferenceChange = onThemePreferenceChange,
                    service = service,
                    onAdvancedModeChange = { advancedMode = it },
                )
            }
        }
    }
}

@Composable
private fun WhyfiTab(
    icon: String,
    label: String,
    index: Int,
    selectedTab: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Tab(
        selected = selectedTab == index,
        onClick = onClick,
        enabled = enabled,
        text = { Text(label) },
        icon = { Text(icon, fontSize = 16.sp) },
    )
}
