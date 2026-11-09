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
import com.whyfi.app.mission.MissionTarget
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.usbmon.MonitorScreen
import com.whyfi.app.usbmon.AdapterSettings
import com.whyfi.app.scan.MonitorUiState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
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
private const val SCAN_ICON = "🔍"
private const val LAN_ICON = "🌐"
private const val SETTINGS_ICON = "⚙️"
private const val MISSION_ICON = "🎯"
private const val MISSION_TAB = 1

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
    // Also kept in settings: switching to the USB workspace and back (or
    // reopening the app) returns to the tab last shown.
    var selectedTab by rememberSaveable { mutableIntStateOf(settingsRepository.phoneTab.coerceIn(0, 3)) }
    // See Workspace.kt: phone radios and the USB adapter are separate
    // workspaces, switchable at any time (adapter or not).
    var workspace by rememberSaveable { mutableStateOf(Workspace.from(settingsRepository.workspace)) }
    val switchWorkspace: (Workspace) -> Unit = {
        workspace = it
        settingsRepository.workspace = it.key
    }
    val tabCount = 4
    var detailRadio by rememberSaveable { mutableStateOf<String?>(null) }
    var showRecordings by rememberSaveable { mutableStateOf(false) }

    // Backs the tab bar with a swipeable pager. selectedTab stays the
    // source of truth for TabRow's indicator and survives rotation via
    // rememberSaveable above; the pager is kept in sync with it in both
    // directions below rather than replacing it, since PagerState's own
    // Saver isn't wired through the same drill-down early-returns this
    // composable already has.
    val pagerState = rememberPagerState(initialPage = selectedTab.coerceAtMost(tabCount - 1)) { tabCount }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
        settingsRepository.phoneTab = pagerState.currentPage
    }
    val goToTab: (Int) -> Unit = { index ->
        coroutineScope.launch { pagerState.animateScrollToPage(index) }
    }

    // Bound once here for the life of the app rather than per screen — see
    // rememberScanService's KDoc for why that matters.
    val service = rememberScanService()
    val uiState by rememberScanState(service)

    val openDetail: (RadioKind) -> Unit = { detailRadio = it.name }

    // Radio results and Missions open inside the Scan tab, under the tab
    // bar like every other sub view (user, 2026-11-09). The system back
    // button closes them; without this it would leave the app instead,
    // which reads as a crash.
    BackHandler(enabled = detailRadio != null && selectedTab == 0) { detailRadio = null }
    BackHandler(enabled = showRecordings && detailRadio == null && selectedTab == 0) { showRecordings = false }
    val activeDetail = detailRadio?.let { name -> RadioKind.entries.firstOrNull { it.name == name } }

    // 📍 on a results row: pick that device on the Mission map and go there.
    // The results stay open on the Scan tab for coming back.
    val locate: (MissionTarget) -> Unit = { target ->
        if (missionController.uiState.value.isTracking) missionController.start(target, service)
        else coroutineScope.launch { missionController.selectTarget(target) }
        goToTab(MISSION_TAB)
    }

    // Standalone recordings are offered for upload once a server is set up.
    ConnectUploadPrompt()

    if (workspace == Workspace.ADAPTER) {
        MaterialTheme(colorScheme = adapterColorScheme(MaterialTheme.colorScheme)) {
            Surface(Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(
                        service = service,
                        settingsRepository = settingsRepository,
                        toggle = { WorkspaceToggle(workspace, switchWorkspace) },
                        setup = { AdapterSettings(settingsRepository) },
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkspaceTabBar(workspace, switchWorkspace) {
            TabRow(selectedTabIndex = selectedTab.coerceAtMost(tabCount - 1)) {
                WhyfiTab(SCAN_ICON, "Scan", 0, selectedTab) { goToTab(0) }
                WhyfiTab(MISSION_ICON, "Mission", MISSION_TAB, selectedTab) { goToTab(MISSION_TAB) }
                WhyfiTab(LAN_ICON, "LAN", 2, selectedTab) { goToTab(2) }
                WhyfiTab(SETTINGS_ICON, "Settings", 3, selectedTab) { goToTab(3) }
            }
        }

        // No swiping away from Mission: dragging its map would flip pages.
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f),
            userScrollEnabled = pagerState.currentPage != MISSION_TAB) { page ->
            when (page) {
                // Recordings open inside the Scan tab (tabs stay visible);
                // back or ← Back returns to Scan.
                0 -> if (activeDetail != null) ScanDetailScreen(
                    uiState = uiState,
                    kind = activeDetail,
                    onKindChange = { detailRadio = it.name },
                    onBack = { detailRadio = null },
                    favoritesRepository = favoritesRepository,
                    service = service,
                    onLocate = locate,
                )
                else if (showRecordings) RecordingsScreen(uiState = uiState, onBack = { showRecordings = false },
                    track = service?.locationTrack?.value.orEmpty())
                else ScanScreen(
                    service = service, uiState = uiState, onOpenDetail = openDetail,
                    onOpenRecordings = { showRecordings = true },
                )
                2 -> LanScreen(service = service, uiState = uiState)
                MISSION_TAB -> MissionScreen(
                    favoritesRepository = favoritesRepository,
                    missionController = missionController,
                    service = service,
                    onBack = null,
                )
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
        // Small enough that "Overview" stays on one line beside the toggle.
        text = { Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false) },
        icon = { Text(icon, fontSize = 16.sp) },
    )
}
