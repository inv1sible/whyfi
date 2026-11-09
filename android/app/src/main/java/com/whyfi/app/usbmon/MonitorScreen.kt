package com.whyfi.app.usbmon

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.scan.MonitorUiState
import com.whyfi.app.scan.LocationSnapshot
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.ui.components.DataTable
import com.whyfi.app.ui.components.DataTableRow
import com.whyfi.app.ui.components.TableColumn
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local receive session; completed missions can be uploaded explicitly.
 * [setup] is the adapter workspace's settings (USB test, noise filter),
 * shown in the Setup tab. Capture needs no prior USB monitor test. */
@Composable
fun MonitorScreen(
    service: ScanForegroundService?,
    settingsRepository: SettingsRepository,
    toggle: @Composable () -> Unit,
    setup: @Composable () -> Unit,
) {
    MonitorWorkspace(service, settingsRepository, toggle, setup)
}

/** What a capture is filtered to. The six device categories are grouped
 * under one Devices section (chips) rather than six top-level tabs. */
private enum class MonitorTab(val label: String) {
    MISSIONS("Missions"), NEARBY("Live"), MAPPING("Map"), ACCESS_POINTS("APs"), UNASSOCIATED("Unassociated"),
    IOT("IoT"), CAMERAS("Cameras"), PHONES_LAPTOPS("Phones / laptops"), CLIENTS("Other clients"), SETUP("Settings"), NET_OPS("Net ops"),
}

private val DEVICE_CATEGORIES = listOf(
    MonitorTab.ACCESS_POINTS, MonitorTab.UNASSOCIATED, MonitorTab.IOT,
    MonitorTab.CAMERAS, MonitorTab.PHONES_LAPTOPS, MonitorTab.CLIENTS,
)

/** The adapter workspace's top-level tabs, with emoji icons like the phone
 * workspace's tabs (no icon library dependency). */
private enum class MonitorSection(val label: String, val icon: String) {
    // Same tabs as the phone workspace where they match: Scan 🔍, Mission 🎯
    // (the map), Settings ⚙️. Saved missions are a sub view of Scan; Net ops
    // lists devices by category until it gets its own features.
    LIVE("Scan", "🔍"), MAP("Mission", "🎯"), NET_OPS("Net ops", "🛰️"), SETUP("Settings", "⚙️"),
}

private fun sectionOf(tab: MonitorTab): MonitorSection = when (tab) {
    MonitorTab.NEARBY -> MonitorSection.LIVE
    MonitorTab.MAPPING -> MonitorSection.MAP
    MonitorTab.MISSIONS -> MonitorSection.LIVE
    MonitorTab.NET_OPS -> MonitorSection.NET_OPS
    MonitorTab.SETUP -> MonitorSection.SETUP
    in DEVICE_CATEGORIES -> MonitorSection.NET_OPS
    else -> MonitorSection.LIVE
}

private fun tabOf(section: MonitorSection): MonitorTab = when (section) {
    MonitorSection.LIVE -> MonitorTab.NEARBY
    MonitorSection.MAP -> MonitorTab.MAPPING
    MonitorSection.NET_OPS -> MonitorTab.NET_OPS
    MonitorSection.SETUP -> MonitorTab.SETUP
}

private enum class NearbySort { RSSI, LAST_SEEN }

@Composable
private fun MonitorWorkspace(
    service: ScanForegroundService?,
    settingsRepository: SettingsRepository,
    toggle: @Composable () -> Unit,
    setup: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Warm up location independently of capture and of which Monitor tab is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationPermissionRevision by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var liveLocations by remember { mutableStateOf<Map<String, Location>>(emptyMap()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) locationPermissionRevision++
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, locationPermissionRevision, foreground) {
        liveLocations = LocationSnapshot.lastKnownCandidates(context).associateBy { it.provider ?: "unknown" }
        val subscription = if (!foreground) null else LocationSnapshot.requestUpdates(context, minDistanceM = 0f, includeFused = true) { fix ->
            val provider = fix.provider ?: "unknown"
            if ((liveLocations[provider]?.elapsedRealtimeNanos ?: 0L) < fix.elapsedRealtimeNanos)
                liveLocations = liveLocations + (provider to Location(fix))
        }
        onDispose { subscription?.close() }
    }
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val emptyState = remember { kotlinx.coroutines.flow.MutableStateFlow(MonitorUiState()) }
    val state by (service?.monitorState ?: emptyState).collectAsState()
    var usbDevices by remember { mutableStateOf(usbManager.deviceList.values.toList()) }
    var selectedDevice by remember { mutableStateOf(usbDevices.firstOrNull()) }
    val allChannels = remember { UsbMonitorNative.DEFAULT_CHANNELS.toSet() }
    var channels by remember { mutableStateOf(settingsRepository.monitorChannels.intersect(allChannels).ifEmpty { allChannels }) }
    var allMode by rememberSaveable { mutableStateOf(true) }
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var showUsb by rememberSaveable { mutableStateOf(selectedDevice == null) }
    var showFavoriteCards by rememberSaveable { mutableStateOf(true) }
    var showUnassociatedCards by rememberSaveable { mutableStateOf(true) }
    var showApCards by rememberSaveable { mutableStateOf(true) }
    var favorites by remember { mutableStateOf(settingsRepository.monitorFavoriteMacs) }
    var hiddenAps by remember { mutableStateOf(settingsRepository.monitorHiddenApMacs) }
    var showHiddenAps by rememberSaveable { mutableStateOf(false) }
    var hideNoise by remember { mutableStateOf(settingsRepository.monitorHideNoise) }
    val noisePatterns = remember { MonitorNoiseFilter.compile(settingsRepository.monitorNoisePatterns).patterns }
    var focusedKind by remember { mutableStateOf<SniffedDevices.Kind?>(null) }
    var autoUpdate by rememberSaveable { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(NearbySort.RSSI.name) }
    val nearbySort = NearbySort.entries.firstOrNull { it.name == sortName } ?: NearbySort.RSSI
    var matchesFirst by rememberSaveable { mutableStateOf(false) }
    var recordedDevices by remember { mutableStateOf<List<SniffedDevices.Device>>(emptyList()) }
    var restoredMissionId by remember { mutableStateOf<String?>(null) }
    var vendorTable by remember { mutableStateOf<OuiTable?>(null) }
    var displayDevices by remember { mutableStateOf(state.devices) }
    var displayMatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    var starting by remember { mutableStateOf(false) }
    var recordPcap by remember { mutableStateOf(settingsRepository.monitorRecordPcap) }
    var error by remember { mutableStateOf<String?>(null) }
    // Kept in settings too, so switching to the phone and back (or
    // reopening the app) comes back to the same tab.
    var selectedName by rememberSaveable { mutableStateOf(settingsRepository.monitorTab ?: MonitorTab.NEARBY.name) }
    // Net ops reopens on the category last looked at.
    var lastCategoryName by rememberSaveable {
        mutableStateOf(settingsRepository.monitorTab?.takeIf { name -> DEVICE_CATEGORIES.any { it.name == name } } ?: MonitorTab.ACCESS_POINTS.name)
    }
    var selectedMissionLogPath by remember { mutableStateOf<String?>(null) }
    var replayMission by remember { mutableStateOf<MonitorReplayMission?>(null) }
    var replayFraction by remember { mutableFloatStateOf(1f) }
    var settledFraction by remember { mutableFloatStateOf(1f) }
    var replayPlaying by remember { mutableStateOf(false) }
    var selectedLocationMac by remember { mutableStateOf<String?>(null) }
    var locationTargets by remember { mutableStateOf<List<MonitorLocationTarget>>(emptyList()) }
    var locationRefresh by remember { mutableIntStateOf(0) }
    var locationRecenter by remember { mutableIntStateOf(0) }
    var followMe by rememberSaveable { mutableStateOf(false) }
    var showLocationDetails by remember { mutableStateOf(false) }
    var mapAccuracy by rememberSaveable { mutableStateOf(true) }
    var mapCones by rememberSaveable { mutableStateOf(true) }
    var mapReadings by rememberSaveable { mutableStateOf(true) }
    var mapPath by rememberSaveable { mutableStateOf(true) }
    var locationSummary by remember { mutableStateOf("") }
    var missionRevision by remember { mutableIntStateOf(0) }
    var pendingReset by remember { mutableStateOf(false) }
    var continuingMissionId by remember { mutableStateOf<String?>(null) }
    var continueError by remember { mutableStateOf<String?>(null) }
    var missions by remember { mutableStateOf<List<MonitorMission.Stored>>(emptyList()) }
    var missionsLoading by remember { mutableStateOf(true) }
    var missionHeadersInitialized by remember { mutableStateOf(false) }
    val activeId = settingsRepository.activeMonitorMissionId
    LaunchedEffect(Unit) {
        vendorTable = withContext(Dispatchers.IO) {
            // Loaded first: it also sets SniffedDevices.catalog for the guesses.
            com.whyfi.app.signatures.LocalSignatures.catalog(context)
            runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
        }
    }
    LaunchedEffect(activeId) {
        if (activeId != null && restoredMissionId == activeId) return@LaunchedEffect
        recordedDevices = withContext(Dispatchers.IO) {
            MonitorMission.resume(context, activeId, false)?.let { files ->
                val evidence = MonitorEvidence.read(files.log)
                val cutoff = if (state.devices.isNotEmpty())
                    evidence.events.lastOrNull { it.event == "monitor_started" }?.atMs?.minus(1) ?: Long.MAX_VALUE
                    else Long.MAX_VALUE
                evidence.inventoryAt(cutoff)
            }.orEmpty()
        }
        displayDevices = MonitorInventory.merge(recordedDevices, state.devices.map { it.first }).map { d ->
            d to SniffedDevices.guess(d) { vendorTable?.vendorOf(it) }
        }
        displayMatches = MonitorInventory.matches(recordedDevices, state.devices.map { it.first })
        restoredMissionId = activeId
    }
    LaunchedEffect(state.devices, autoUpdate, recordedDevices, vendorTable) {
        if (autoUpdate) {
            displayDevices = MonitorInventory.merge(recordedDevices, state.devices.map { it.first }).map { d ->
                d to SniffedDevices.guess(d) { vendorTable?.vendorOf(it) }
            }
            displayMatches = MonitorInventory.matches(recordedDevices, state.devices.map { it.first })
        }
    }
    LaunchedEffect(missionRevision, state.running, state.missionLogPath, state.status == "Saving capture…") {
        if (!missionHeadersInitialized) {
            missions = withContext(Dispatchers.IO) { MonitorMission.cachedList(context) }
            missionHeadersInitialized = true
        }
        if (missions.isNotEmpty()) missionsLoading = false
        val showBatches = missions.isEmpty()
        val loadContext = currentCoroutineContext()
        missions = withContext(Dispatchers.IO) {
            MonitorMission.list(context) { batch ->
                if (showBatches && loadContext.isActive) { missions = batch; missionsLoading = false }
            }
        }
        missionsLoading = false
    }
    val resolvedHiddenAps = remember(hiddenAps, recordedDevices, displayDevices) {
        MonitorApVisibility.resolve(hiddenAps, recordedDevices + displayDevices.map { it.first })
    }
    val matchedMacs = displayMatches
    val deviceStatus = recordedDevices.associate { it.mac to if (it.mac in matchedMacs) "Seen again" else "Recorded" }
    LaunchedEffect(selectedMissionLogPath) {
        replayMission = null
        replayFraction = 1f
        settledFraction = 1f
        replayPlaying = false
        selectedMissionLogPath?.let { path ->
            replayMission = withContext(Dispatchers.IO) {
                File(path).takeIf { it.isFile }?.let { MonitorReplayMission.load(context, it, vendorTable) }
            }
        }
    }
    LaunchedEffect(replayFraction, selectedMissionLogPath) {
        if (selectedMissionLogPath != null) { delay(100); settledFraction = replayFraction }
    }
    val activeReplay = replayMission?.takeIf { it.log.absolutePath == selectedMissionLogPath }
    val timeline = activeReplay?.timeline ?: (0L to 0L)
    LaunchedEffect(replayPlaying, timeline, selectedMissionLogPath) {
        val durationMs = timeline.second - timeline.first
        while (selectedMissionLogPath != null && replayPlaying && durationMs > 0 && replayFraction < 1f) {
            delay(500)
            replayFraction = (replayFraction + 5_000f / durationMs).coerceAtMost(1f)
            if (replayFraction >= 1f) replayPlaying = false
        }
    }
    val replayCutoffMs = timeline.first + ((timeline.second - timeline.first) * settledFraction).toLong()
    val replaySnapshot = activeReplay?.snapshotAt(replayCutoffMs)
    val replayReferenceMs = replaySnapshot?.let { snapshot ->
        snapshot.clockMs.takeIf { it > 0 }?.plus((replayCutoffMs - snapshot.atMs).coerceAtLeast(0))
    } ?: replayCutoffMs
    var replayDevices by remember { mutableStateOf<List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>>(emptyList()) }
    LaunchedEffect(activeReplay, replayCutoffMs) {
        replayDevices = withContext(Dispatchers.Default) {
            activeReplay?.takeIf { it.snapshots.isNotEmpty() }?.devicesAt(replayCutoffMs)
                ?: activeReplay?.legacyDevicesAt(replayCutoffMs).orEmpty()
        }
    }
    val replaying = selectedMissionLogPath != null
    // Net ops shows the device category tables.
    val selected = MonitorTab.entries.firstOrNull { it.name == selectedName }
        ?.let { if (it == MonitorTab.NET_OPS) DEVICE_CATEGORIES.firstOrNull { c -> c.name == lastCategoryName } ?: MonitorTab.NET_OPS else it }
        ?: MonitorTab.NEARBY
    val nearbyDevices = displayDevices.filter {
        (focusedKind == null || it.second.kind == focusedKind) || (showHiddenAps && MonitorApVisibility.isHidden(it.first, resolvedHiddenAps))
    }
    val noiseCount = nearbyDevices.count { row ->
        row.first.mac !in favorites && !MonitorApVisibility.isHidden(row.first, resolvedHiddenAps) &&
            MonitorNoiseFilter.matches(row, noisePatterns)
    }
    val categoryDevices = (if (replaying) replayDevices else displayDevices)
        .filter { (focusedKind == null || sectionOf(selected) == MonitorSection.NET_OPS || it.second.kind == focusedKind) && (replaying || !MonitorApVisibility.isHidden(it.first, resolvedHiddenAps)) }
    val newDevices = state.devices.count { incoming -> displayDevices.none { it.first.mac == incoming.first.mac } }

    val recordAction: (String, String) -> Unit = { event, details ->
        if (state.running) service?.logMonitorEvent(event, details)
        else MonitorMission.resume(context, settingsRepository.activeMonitorMissionId, false)?.let {
            MonitorMission.append(it, event, details)
        }
    }

    val setChannels: (Set<Int>) -> Unit = {
        channels = it
        settingsRepository.monitorChannels = it
        recordAction("channels_selected", it.sorted().joinToString())
    }
    val toggleFavorite: (String) -> Unit = { mac ->
        favorites = if (mac in favorites) favorites - mac else favorites + mac
        settingsRepository.monitorFavoriteMacs = favorites
        recordAction("favorite", "$mac:${mac in favorites}")
    }
    val setHiddenAps: (Set<String>) -> Unit = { next ->
        val previous = hiddenAps
        hiddenAps = next
        settingsRepository.monitorHiddenApMacs = next
        (previous - next).forEach { recordAction("ap_hidden", "$it:false") }
        (next - previous).forEach { recordAction("ap_hidden", "$it:true") }
    }
    val toggleHiddenAp: (SniffedDevices.Device) -> Unit = { device ->
        val key = MonitorApVisibility.key(device)
        val next = if (MonitorApVisibility.isHidden(device, resolvedHiddenAps)) resolvedHiddenAps - key - device.mac
            else resolvedHiddenAps + key
        setHiddenAps(next)
        if (displayDevices.any { it.first.mac == selectedLocationMac && MonitorApVisibility.isHidden(it.first, next) })
            selectedLocationMac = null
    }
    val restoreHiddenKey: (String) -> Unit = { key -> setHiddenAps(resolvedHiddenAps - key) }
    val continueMission: (MonitorMission.Stored) -> Unit = { mission ->
        if (!state.running && !starting && continuingMissionId == null) {
            continuingMissionId = mission.directory.name
            continueError = null
            scope.launch {
                try {
                    val (continued, evidence, inventory) = withContext(Dispatchers.IO) {
                        val copied = MonitorMission.continueAsNew(context, mission)
                        val loaded = MonitorEvidence.read(copied.log)
                        Triple(copied, loaded, loaded.inventoryAt())
                    }
                    val selectedFavorites = MonitorMission.favoritesAfter(evidence.initialFavorites, evidence.events, Long.MAX_VALUE)
                    val selectedHidden = MonitorMission.hiddenApsAfter(evidence.events)
                    settingsRepository.activeMonitorMissionId = continued.directory.name
                    favorites = selectedFavorites
                    settingsRepository.monitorFavoriteMacs = selectedFavorites
                    hiddenAps = selectedHidden
                    settingsRepository.monitorHiddenApMacs = selectedHidden
                    showHiddenAps = false
                    service?.clearFinishedMonitor()
                    recordedDevices = inventory
                    restoredMissionId = continued.directory.name
                    displayDevices = recordedDevices.map { d -> d to SniffedDevices.guess(d) { vendorTable?.vendorOf(it) } }
                    displayMatches = emptySet()
                    selectedMissionLogPath = null
                    selectedLocationMac = null
                    locationTargets = emptyList()
                    missionRevision++
                    selectedName = MonitorTab.NEARBY.name
                } catch (e: Exception) {
                    continueError = "Could not continue mission: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    continuingMissionId = null
                }
            }
        }
    }
    val beginMonitor: () -> Unit = start@{
        if (continuingMissionId != null) return@start
        val device = selectedDevice ?: run { showUsb = true; return@start }
        val currentService = service ?: return@start
        val selectedChannels = if (allMode) allChannels else channels
        if (selectedChannels.isEmpty()) {
            error = "Select at least one channel"
            showChannels = true
            return@start
        }
        starting = true
        error = null
        recordedDevices = MonitorInventory.merge(recordedDevices, state.devices.map { it.first })
        displayMatches = emptySet()
        scope.launch {
            try {
                if (!usbManager.hasPermission(device) && !requestPermission(context, usbManager, device)) {
                    error = "USB permission denied"
                    showUsb = true
                } else {
                    val connection = usbManager.openDevice(device)
                    if (connection == null) {
                        error = "Could not open USB adapter"
                        showUsb = true
                    } else try {
                        ScanForegroundService.start(context)
                        currentService.startMonitor(connection, describe(device), selectedChannels.sorted().toIntArray(), 600_000, recordPcap)
                        showUsb = false
                    } catch (e: Exception) { connection.close(); throw e }
                }
            } catch (e: Exception) { error = e.message ?: "Could not start USB monitor" }
            finally { starting = false }
        }
    }
    var pendingStart by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationPermissionRevision++
        if (pendingStart) {
            pendingStart = false
            beginMonitor()
            if (!LocationSnapshot.hasLocationPermission(context)) {
                error = listOfNotNull(error, "Location permission denied; capture can run, but the PDF will have no position data.")
                    .joinToString(" · ")
            }
        }
    }
    val startMonitor: () -> Unit = {
        if (!LocationSnapshot.hasLocationPermission(context)) {
            pendingStart = true
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        } else beginMonitor()
    }

    val section = sectionOf(selected)
    val selectTab: (MonitorTab) -> Unit = { picked ->
        val tab = if (picked == MonitorTab.NET_OPS) DEVICE_CATEGORIES.firstOrNull { it.name == lastCategoryName } ?: MonitorTab.NET_OPS else picked
        settingsRepository.monitorTab = tab.name
        if (tab != MonitorTab.MAPPING) showLocationDetails = false
        if (tab == MonitorTab.MISSIONS || tab == MonitorTab.NEARBY || tab == MonitorTab.SETUP) replayPlaying = false
        if (tab in DEVICE_CATEGORIES || tab == MonitorTab.NET_OPS) lastCategoryName = tab.name
        selectedName = tab.name
    }
    val currentSelectTab by rememberUpdatedState(selectTab)
    // Swipe left/right moves between tabs like the phone workspace's pager.
    // Not on Map, where a drag pans the map. Children that scroll
    // sideways (or sliders) consume their drags first, so this only sees
    // swipes nothing else wanted.
    val swipeTabs = if (section == MonitorSection.MAP) Modifier else Modifier.pointerInput(section) {
        val threshold = 72.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val next = when {
                    total < -threshold -> section.ordinal + 1
                    total > threshold -> section.ordinal - 1
                    else -> -1
                }
                MonitorSection.entries.getOrNull(next)?.let { currentSelectTab(tabOf(it)) }
            },
        ) { change, amount ->
            change.consume()
            total += amount
        }
    }
    Column(Modifier.fillMaxSize().then(swipeTabs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            toggle()
            TabRow(section.ordinal, Modifier.weight(1f)) {
                MonitorSection.entries.forEach { entry ->
                    Tab(entry == section, { selectTab(tabOf(entry)) }, text = {
                        Text(entry.label,
                            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                    }, icon = { Text(entry.icon, fontSize = 16.sp) })
                }
            }
        }
        if (selected == MonitorTab.NEARBY) {
            // Top line like the phone's Scan tab: name, missions, report.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("whyfi USB", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton({ selectTab(MonitorTab.MISSIONS) }) { Text("🎯 Missions") }
                val reportMission = missions.firstOrNull { it.directory.name == activeId } ?: missions.firstOrNull()
                if (reportMission != null && !state.running) com.whyfi.app.ui.ReportButton(enabled = true) {
                    withContext(Dispatchers.IO) { MonitorMission.ensureReport(context, reportMission) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Updates", style = MaterialTheme.typography.bodySmall)
                TextButton({
                    autoUpdate = !autoUpdate
                    recordAction("update_mode", if (autoUpdate) "auto" else "manual")
                }) { Text(if (autoUpdate) "Auto" else "Manual") }
                if (!autoUpdate) {
                    IconButton({
                        displayDevices = MonitorInventory.merge(recordedDevices, state.devices.map { it.first }).map { d ->
                            d to SniffedDevices.guess(d) { vendorTable?.vendorOf(it) }
                        }
                        displayMatches = MonitorInventory.matches(recordedDevices, state.devices.map { it.first })
                        recordAction("display_refresh", "")
                    }, modifier = Modifier.size(40.dp).semantics {
                        contentDescription = "Refresh displayed devices${if (newDevices > 0) ", $newDevices new" else ""}"
                    }) { Text("⟳", style = MaterialTheme.typography.headlineSmall) }
                }
                Spacer(Modifier.weight(1f))
                Text("Sort", style = MaterialTheme.typography.bodySmall)
                TextButton({
                    sortName = if (nearbySort == NearbySort.RSSI) NearbySort.LAST_SEEN.name else NearbySort.RSSI.name
                    recordAction("nearby_sort", sortName)
                }) { Text(if (nearbySort == NearbySort.RSSI) "RSSI ↓" else "Latest ↓") }
            }
        }
        if (selected == MonitorTab.SETUP) {
            // Laid out like the phone's Settings tab (SettingsScreen).
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { setup() }
        } else if (selected == MonitorTab.NEARBY) {
            // Lazy: a long mission holds thousands of devices, and drawing a
            // card for each at once ran the app out of memory (crash report
            // 2026-10-27, OOM while accessibility walked the whole tree).
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
              item(key = "nearby-controls") {
               Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!showUsb && selectedDevice != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Adapter: ${describe(selectedDevice!!)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!state.running) TextButton({ showUsb = true }) { Text("Change") }
                }
                if (showUsb || selectedDevice == null) {
                    UsbPanel(usbDevices, selectedDevice, state.running,
                        {
                            usbDevices = usbManager.deviceList.values.toList()
                            if (selectedDevice !in usbDevices) selectedDevice = usbDevices.firstOrNull()
                        },
                        { selectedDevice = it; showUsb = false })
                }
                if (showChannels) ChannelPanel(state, allChannels, channels, allMode,
                    { allMode = it; recordAction("channel_mode", if (it) "all" else "selected") },
                    setChannels)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.moveSuggested) Text("No new AP or distinct receiver position for 2 minutes. Try another spot.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (!state.running && state.missionLogPath != null) Text("Capture saved. PDF on request in Missions. Start continues; Reset closes this mission.",
                    style = MaterialTheme.typography.bodySmall)
                if (state.pcapTruncated) Text("PCAP recording stopped at the 200 MB limit.", color = MaterialTheme.colorScheme.error)
                if (nearbyDevices.isEmpty() && !state.running) Text(
                    if (selectedDevice == null) "Connect the USB adapter, then press Start below to capture."
                    else "No devices yet. Press Start below to capture; devices show up here as they are heard.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
               }
              }
                nearbyItems(nearbyDevices, favorites, toggleFavorite, resolvedHiddenAps, toggleHiddenAp, restoreHiddenKey,
                    showHiddenAps, hideNoise, noisePatterns, nearbySort,
                    showFavoriteCards, { showFavoriteCards = !showFavoriteCards },
                    showUnassociatedCards, { showUnassociatedCards = !showUnassociatedCards },
                    showApCards, { showApCards = !showApCards }, matchedMacs, matchesFirst, deviceStatus,
                    onLocate = { mac ->
                        // 📍 on a card: the Mission map, focused on that device.
                        selectedLocationMac = mac
                        followMe = false
                        selectTab(MonitorTab.MAPPING)
                        locationRecenter++
                    })
            }
        } else if (selected == MonitorTab.MISSIONS) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ selectTab(MonitorTab.NEARBY) }) { Text("← Back") }
                Text("🎯 Missions", style = MaterialTheme.typography.titleMedium)
            }
            BackHandler { selectTab(MonitorTab.NEARBY) }
            MissionView(context, settingsRepository, missions, missionsLoading, { missionRevision++ }, vendorTable,
                onOpenLocation = { path, mac ->
                    selectedMissionLogPath = path
                    selectedLocationMac = mac
                    replayFraction = 1f
                    settledFraction = 1f
                    replayPlaying = false
                    locationTargets = emptyList()
                    selectedName = MonitorTab.MAPPING.name
                    locationRecenter++
                }, onContinue = continueMission, continuingMissionId = continuingMissionId,
                continueError = continueError, onDeleted = { path ->
                    missions = missions.filterNot { it.log.absolutePath == path }
                    if (File(path).parentFile?.name == settingsRepository.activeMonitorMissionId) {
                        settingsRepository.activeMonitorMissionId = null
                        favorites = emptySet()
                        settingsRepository.monitorFavoriteMacs = emptySet()
                        hiddenAps = emptySet()
                        settingsRepository.monitorHiddenApMacs = emptySet()
                        showHiddenAps = false
                        recordedDevices = emptyList()
                        displayDevices = emptyList()
                        displayMatches = emptySet()
                        selectedLocationMac = null
                        locationTargets = emptyList()
                        service?.clearFinishedMonitor()
                    }
                    missionRevision++
                    if (selectedMissionLogPath == path) {
                        selectedMissionLogPath = null
                        selectedLocationMac = null
                        replayMission = null
                    }
                }, activeLogPath = state.missionLogPath.takeIf { state.running },
                modifier = Modifier.weight(1f))
        } else if (selected == MonitorTab.MAPPING) {
            MonitorMappingView(context, state, favorites, if (replaying) emptySet() else
                resolvedHiddenAps.filterNot { it.startsWith("ssid:") }.toSet() +
                    displayDevices.filter { MonitorApVisibility.isHidden(it.first, resolvedHiddenAps) }.map { it.first.mac },
                settingsRepository.activeMonitorMissionId,
                selectedMissionLogPath, activeReplay,
                replayCutoffMs, followMe, selectedLocationMac,
                { locationTargets = it }, { locationSummary = it }, locationRefresh, locationRecenter,
                showLocationDetails, onSelectAp = { mac ->
                    selectedLocationMac = mac
                    followMe = false
                }, onFavoriteAp = toggleFavorite,
                Modifier.weight(1f), onUserPan = { followMe = false }, liveLocations = liveLocations.values.toList(),
                onLocationPermissionChanged = { locationPermissionRevision++ },
                layers = MonitorMapLayers(mapAccuracy, mapCones, mapReadings, mapPath))
        } else {
            if (replaying && activeReplay?.snapshots?.isEmpty() == true) Text(
                "This older mission did not save device roles or category snapshots.",
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            MonitorTable(selected, categoryDevices, Modifier.weight(1f).padding(12.dp),
                referenceMs = replayReferenceMs.takeIf { replaying },
                matches = if (replaying) emptySet() else matchedMacs, matchesFirst = matchesFirst,
                deviceStatus = if (replaying) emptyMap() else deviceStatus,
                onOpenApLocation = { mac ->
                    selectedLocationMac = mac
                    followMe = false
                    selectedName = MonitorTab.MAPPING.name
                    locationRecenter++
                })
        }
        if (selected != MonitorTab.MISSIONS && selected != MonitorTab.SETUP) {
            if (replaying && selected != MonitorTab.NEARBY && timeline.second > timeline.first) {
                Slider(replayFraction, onValueChange = { replayPlaying = false; replayFraction = it },
                    onValueChangeFinished = { settledFraction = replayFraction },
                    modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp))
            }
            Surface(Modifier.fillMaxWidth(), tonalElevation = 5.dp, shadowElevation = 5.dp) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    // Layer toggles while replaying (live: in the controls row below).
                    if (selected == MonitorTab.MAPPING && replaying) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.whyfi.app.mission.MapLayerChip("◎ Accuracy", mapAccuracy) { mapAccuracy = !mapAccuracy }
                        com.whyfi.app.mission.MapLayerChip("Cones", mapCones) { mapCones = !mapCones }
                        com.whyfi.app.mission.MapLayerChip("Readings", mapReadings) { mapReadings = !mapReadings }
                        com.whyfi.app.mission.MapLayerChip("Path", mapPath) { mapPath = !mapPath }
                    }
                    if (replaying && selected != MonitorTab.NEARBY) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({
                                if (replayFraction >= 1f) { replayFraction = 0f; settledFraction = 0f }
                                replayPlaying = !replayPlaying
                            }, enabled = activeReplay != null && timeline.second > timeline.first,
                                modifier = Modifier.size(44.dp).semantics {
                                    contentDescription = if (replayPlaying) "Pause mission replay" else "Play mission replay at ten times speed"
                                }) { Text(if (replayPlaying) "Ⅱ" else "▶", style = MaterialTheme.typography.titleLarge) }
                            Text(activeReplay?.let {
                                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(replayCutoffMs))} · " +
                                    if (selected == MonitorTab.MAPPING) locationSummary else "${replayDevices.size} devices"
                            } ?: "Loading mission…", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            TextButton({
                                selectedMissionLogPath = null
                                selectedLocationMac = null
                                locationTargets = emptyList()
                            }) { Text("Live") }
                            if (selected == MonitorTab.MAPPING) {
                                com.whyfi.app.mission.MapButtons(showLocationDetails, { showLocationDetails = !showLocationDetails },
                                    { locationRefresh++ }, followMe, { enabled ->
                                        followMe = enabled
                                        if (enabled) { selectedLocationMac = null; locationRecenter++ }
                                    })
                            }
                        }
                    } else if (selected == MonitorTab.MAPPING) {
                        // Laid out like the phone's Mission controls bar:
                        // status, then one row with Start/Stop, the layer
                        // toggles and ⓘ ⟳ ⌖ (PCAP stays on the Scan tab).
                        Text(locationSummary, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.running) Button({ service?.stopMonitor() },
                                enabled = state.status != "Starting…" && state.status != "Stopping…" &&
                                    state.status != "Saving capture…") { Text("Stop") }
                            else Button(startMonitor, enabled = !starting && continuingMissionId == null && selectedDevice != null) {
                                Text(if (starting) "Starting…" else "Start")
                            }
                            com.whyfi.app.mission.MapLayerChip("◎ Accuracy", mapAccuracy) { mapAccuracy = !mapAccuracy }
                            com.whyfi.app.mission.MapLayerChip("Cones", mapCones) { mapCones = !mapCones }
                            com.whyfi.app.mission.MapLayerChip("Readings", mapReadings) { mapReadings = !mapReadings }
                            com.whyfi.app.mission.MapLayerChip("Path", mapPath) { mapPath = !mapPath }
                            com.whyfi.app.mission.MapButtons(showLocationDetails, { showLocationDetails = !showLocationDetails },
                                { locationRefresh++ }, followMe, { enabled ->
                                    followMe = enabled
                                    if (enabled) { selectedLocationMac = null; locationRecenter++ }
                                })
                        }
                    } else {
                        Text(if (selected == MonitorTab.MAPPING) locationSummary
                            else buildString {
                                append("${state.frames} frames · ${state.receiverFixCount} location fixes · ${state.geoSamples} pinned")
                                if (selected == MonitorTab.NEARBY) append(when {
                                    state.pcapPath != null -> " · PCAP ${formatPcapBytes(state.pcapBytes)}"
                                    state.running -> " · PCAP off"
                                    recordPcap -> " · PCAP armed"
                                    else -> " · PCAP off"
                                })
                                append(" · ${state.status}")
                            },
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.running) Button({ service?.stopMonitor() },
                                enabled = state.status != "Starting…" && state.status != "Stopping…" &&
                                    state.status != "Saving capture…") { Text("Stop") }
                            else Button(startMonitor, enabled = !starting && continuingMissionId == null && selectedDevice != null) {
                                Text(if (starting) "Starting…" else "Start")
                            }
                            if (selected == MonitorTab.NEARBY) TextButton({ pendingReset = true },
                                enabled = !state.running && !starting && !pendingStart && continuingMissionId == null) { Text("Reset") }
                            Checkbox(recordPcap, {
                                recordPcap = it
                                settingsRepository.monitorRecordPcap = it
                            }, enabled = !state.running)
                            Text(if (recordPcap) "PCAP on" else "PCAP off", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            if (selected == MonitorTab.MAPPING) {
                                com.whyfi.app.mission.MapButtons(showLocationDetails, { showLocationDetails = !showLocationDetails },
                                    { locationRefresh++ }, followMe, { enabled ->
                                        followMe = enabled
                                        if (enabled) { selectedLocationMac = null; locationRecenter++ }
                                    })
                            } else if (selected == MonitorTab.NEARBY) {
                                TextButton({ showChannels = !showChannels }) {
                                    Text(if (showChannels) "Channels ▼" else "Channels ▲")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selected == MonitorTab.MAPPING) {
            val selectedTarget = selectedLocationMac?.takeIf { mac -> locationTargets.any { it.mac == mac } }
            // Devices to focus, condensed into one bar at the very bottom
            // like the phone's Mission favorites. "All" clears the focus.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selectedTarget == null, { selectedLocationMac = null; focusedKind = null }, label = { Text("All") })
                locationTargets.forEach { target ->
                    FilterChip(selectedTarget == target.mac, { selectedLocationMac = if (selectedTarget == target.mac) null else target.mac; followMe = false },
                        label = { Text("${target.label} (${target.readings})", maxLines = 1) })
                }
            }
        } else if (selected != MonitorTab.MISSIONS && selected != MonitorTab.MAPPING && selected != MonitorTab.SETUP) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(matchesFirst, { focusedKind = null; matchesFirst = !matchesFirst; recordAction("matches_first", matchesFirst.toString()) },
                    label = { Text("Matches first (${matchedMacs.size})") })
                if (selected == MonitorTab.NEARBY) FilterChip(showHiddenAps,
                    { focusedKind = null; showHiddenAps = !showHiddenAps }, label = { Text("Hidden APs (${resolvedHiddenAps.size})") })
                if (selected == MonitorTab.NEARBY) FilterChip(hideNoise, {
                    hideNoise = !hideNoise
                    settingsRepository.monitorHideNoise = hideNoise
                    recordAction("noise_filter", if (hideNoise) "on" else "off")
                }, label = { Text("Hide noise ($noiseCount)") })
                // Net ops: the device categories, in this one bar (the old
                // top bar is gone). Tap again to show every device.
                if (section == MonitorSection.NET_OPS) DEVICE_CATEGORIES.forEach { tab ->
                    FilterChip(tab == selected, {
                        if (tab == selected) selectTab(MonitorTab.NET_OPS.also { lastCategoryName = it.name }) else selectTab(tab)
                    }, label = { Text("${tab.label} (${monitorRows(tab, categoryDevices).size})") })
                } else SniffedDevices.Kind.entries.forEach { kind ->
                    FilterChip(kind == focusedKind, {
                        focusedKind = if (focusedKind == kind) null else kind
                        if (!replaying) recordAction("filter_focus", focusedKind?.name.orEmpty())
                    }, label = { Text("${kindIcon(kind)} ${kind.label}") })
                }
            }
        }

        if (pendingReset) AlertDialog(onDismissRequest = { pendingReset = false },
            title = { Text("Start a fresh mission?") },
            text = { Text("This closes the current mission and clears favorites. Its PDF, log and PCAP remain saved in Missions.") },
            confirmButton = { TextButton({
                pendingReset = false
                recordAction("mission_closed", "")
                settingsRepository.activeMonitorMissionId = null
                favorites = emptySet()
                settingsRepository.monitorFavoriteMacs = emptySet()
                hiddenAps = emptySet()
                settingsRepository.monitorHiddenApMacs = emptySet()
                showHiddenAps = false
                recordedDevices = emptyList()
                displayDevices = emptyList()
                displayMatches = emptySet()
                service?.clearFinishedMonitor()
                missionRevision++
            }) { Text("Reset") } },
            dismissButton = { TextButton({ pendingReset = false }) { Text("Cancel") } })

    }
}

@Composable
private fun UsbPanel(
    usbDevices: List<UsbDevice>, selectedDevice: UsbDevice?, running: Boolean,
    onRefresh: () -> Unit, onDevice: (UsbDevice) -> Unit,
) {
    Text("USB adapter", style = MaterialTheme.typography.titleMedium)
    if (usbDevices.isEmpty()) Text("Connect an RTL8812AU/8821AU adapter via USB OTG, then refresh.")
    usbDevices.forEach { device ->
        FilterChip(selectedDevice == device, { onDevice(device) }, enabled = !running, label = { Text(describe(device)) })
    }
    OutlinedButton(onRefresh, enabled = !running) { Text("Refresh adapters") }
}

@Composable
private fun ChannelPanel(
    state: MonitorUiState, allChannels: Set<Int>, channels: Set<Int>, allMode: Boolean,
    onAllMode: (Boolean) -> Unit, onChannels: (Set<Int>) -> Unit,
) {
    Text("Channels", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(allMode, { onAllMode(true) }, label = { Text("All") })
        FilterChip(!allMode, { onAllMode(false) }, label = { Text("Selected (${channels.size})") })
    }
    Text("Select channels for the next 10-minute session. Counts are received frames.", style = MaterialTheme.typography.bodySmall)
    UsbMonitorNative.DEFAULT_CHANNELS.toList().chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            row.forEach { channel ->
                val frames = state.channelFrames[channel] ?: 0
                FilterChip(allMode || channel in channels, {
                    if (allMode) { onChannels(allChannels - channel); onAllMode(false) }
                    else onChannels(if (channel in channels) channels - channel else channels + channel)
                }, label = { Text("ch $channel ($frames)") })
            }
        }
    }
    OutlinedButton({ onChannels(state.channelFrames.keys.intersect(allChannels)); onAllMode(false) },
        enabled = state.channelFrames.isNotEmpty()) { Text("Use observed channels") }
    if (state.running) Text("Changes apply to the next session.", style = MaterialTheme.typography.bodySmall)
}

private fun monitorRows(
    tab: MonitorTab,
    devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>,
): List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> = devices.filter { (device, guess) -> when (tab) {
        MonitorTab.ACCESS_POINTS -> device.isAp
        MonitorTab.UNASSOCIATED -> device.isClient && device.probeRequests > 0 && device.bssids.isEmpty()
        MonitorTab.IOT -> guess.kind == SniffedDevices.Kind.IOT
        MonitorTab.CAMERAS -> guess.kind == SniffedDevices.Kind.CAMERA
        MonitorTab.PHONES_LAPTOPS -> guess.kind == SniffedDevices.Kind.PHONE_LAPTOP
        MonitorTab.CLIENTS -> device.isClient && guess.kind == SniffedDevices.Kind.CLIENT
        MonitorTab.NET_OPS -> true
        MonitorTab.NEARBY, MonitorTab.MAPPING, MonitorTab.MISSIONS, MonitorTab.SETUP -> false
    } }.sortedWith(
        compareByDescending<Pair<SniffedDevices.Device, SniffedDevices.Guess>> { it.first.lastMs }
            .thenByDescending { it.first.bestRssi ?: -1000 },
    )

@Composable
private fun MonitorTable(
    tab: MonitorTab,
    devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>,
    modifier: Modifier,
    referenceMs: Long? = null,
    matches: Set<String> = emptySet(),
    matchesFirst: Boolean = false,
    deviceStatus: Map<String, String> = emptyMap(),
    onOpenApLocation: (String) -> Unit = {},
) {
    val rows = monitorRows(tab, devices).let { if (matchesFirst) it.sortedWith(MonitorInventory.matchOrder(matches)) else it }
    val newest = referenceMs ?: rows.filter { deviceStatus[it.first.mac] != "Recorded" }.maxOfOrNull { it.first.lastMs } ?: 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == MonitorTab.PHONES_LAPTOPS) {
            Text("Current frame metadata cannot reliably distinguish phones from laptops.", style = MaterialTheme.typography.bodySmall)
        }
        if (tab == MonitorTab.CAMERAS && rows.isEmpty()) {
            Text("No camera evidence identified. ${devices.count { it.second.vendor != null }} devices have a known manufacturer; " +
                "${devices.count { it.first.wpsPrimaryType.isNotBlank() }} advertise a WPS device type. Encrypted traffic may not reveal cameras.",
                style = MaterialTheme.typography.bodySmall)
        }
        DataTable(
            columns = listOf(
                TableColumn("Device", 150.dp),
                TableColumn("MAC", 150.dp),
                TableColumn("Manufacturer", 180.dp),
                TableColumn("History", 100.dp),
                TableColumn("RSSI", 70.dp),
                TableColumn("Channel", 80.dp),
                TableColumn("Security", 170.dp),
                TableColumn("WPS", 180.dp),
                TableColumn("Seen", 76.dp),
                TableColumn("Activity", 190.dp),
                TableColumn("Classification evidence", 240.dp),
            ),
            rows = rows.map { (device, guess) ->
                DataTableRow(
                    key = device.mac,
                    onClick = { onOpenApLocation(device.mac) },
                    cells = listOf(
                        SniffedDevices.displayName(device, guess) + "  ↗ map",
                        device.mac,
                        guess.vendor ?: "—",
                        deviceStatus[device.mac] ?: if (referenceMs == null) "New" else "Replay",
                        device.bestRssi?.let { "$it dBm" } ?: "—",
                        device.channels.joinToString().ifBlank { "—" },
                        device.securityDetails.ifBlank { "—" },
                        device.wpsDetails.ifBlank { "—" },
                        if (deviceStatus[device.mac] == "Recorded") "Recorded" else relativeSeen((newest - device.lastMs).coerceAtLeast(0)),
                        SniffedDevices.activity(device).ifBlank { guess.kind.label },
                        guess.reasons.joinToString("; ").ifBlank { "No identifying evidence" },
                    ),
                )
            },
            modifier = Modifier.weight(1f),
            showBadgeColumn = false,
        )
    }
}

private fun formatPcapBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

private fun relativeSeen(ageMs: Long): String = when {
    ageMs < 1_000 -> "now"
    ageMs < 60_000 -> "${ageMs / 1_000}s ago"
    else -> "${ageMs / 60_000}m ago"
}

/** Relationship view for use in the field. Its parent scrolls while the command bar stays fixed. */
private fun nearbyOrder(sort: NearbySort): Comparator<Pair<SniffedDevices.Device, SniffedDevices.Guess>> =
    if (sort == NearbySort.RSSI) {
        compareByDescending<Pair<SniffedDevices.Device, SniffedDevices.Guess>> { it.first.bestRssi ?: Int.MIN_VALUE }
            .thenByDescending { it.first.lastMs }
            .thenBy { it.first.mac }
    } else {
        compareByDescending<Pair<SniffedDevices.Device, SniffedDevices.Guess>> { it.first.lastMs }
            .thenByDescending { it.first.bestRssi ?: Int.MIN_VALUE }
            .thenBy { it.first.mac }
    }

private fun LazyListScope.nearbyItems(
    devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>,
    favorites: Set<String>,
    onFavorite: (String) -> Unit,
    hiddenAps: Set<String>,
    onHideAp: (SniffedDevices.Device) -> Unit,
    onRestoreHiddenKey: (String) -> Unit,
    showHiddenAps: Boolean,
    hideNoise: Boolean,
    noisePatterns: List<Regex>,
    sort: NearbySort,
    showFavorites: Boolean,
    toggleFavorites: () -> Unit,
    showUnassociated: Boolean,
    toggleUnassociated: () -> Unit,
    showAps: Boolean,
    toggleAps: () -> Unit,
    matches: Set<String>,
    matchesFirst: Boolean,
    deviceStatus: Map<String, String>,
    onLocate: ((String) -> Unit)? = null,
) {
    val order = if (matchesFirst) MonitorInventory.matchOrder(matches) else nearbyOrder(sort)
    if (showHiddenAps) {
        val groups = MonitorApVisibility.groups(hiddenAps, devices.map { it.first })
        item(key = "hidden-title") { Text("Hidden AP groups (${groups.size})", style = MaterialTheme.typography.titleMedium) }
        if (groups.isEmpty()) item(key = "hidden-empty") { Text("No APs hidden in this mission.") }
        items(groups.distinctBy { it.key }, key = { "hidden:" + it.key }) { group ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(group.label + if (group.count > 0) " · ${group.count} APs" else "",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ onRestoreHiddenKey(group.key) }) { Text("Show") }
            }
        }
        return
    }
    val visibleDevices = if (hideNoise) devices.filterNot { row ->
        row.first.mac !in favorites && MonitorNoiseFilter.matches(row, noisePatterns)
    } else devices
    val newest = visibleDevices.maxOfOrNull { it.first.lastMs } ?: 0
    val pinned = visibleDevices.filter { it.first.mac in favorites && !MonitorApVisibility.isHidden(it.first, hiddenAps) }.sortedWith(order)
    val unassociated = visibleDevices.filter {
        it.first.mac !in favorites && !MonitorApVisibility.isHidden(it.first, hiddenAps) && it.first.isClient &&
            it.first.probeRequests > 0 && it.first.bssids.isEmpty()
    }.sortedWith(order)
    val aps = visibleDevices.filter { it.first.mac !in favorites && it.first.isAp && !MonitorApVisibility.isHidden(it.first, hiddenAps) }.sortedWith(order)
    val byBssid = visibleDevices.filter { it.first.isClient }.flatMap { row -> row.first.bssids.map { it to row } }.groupBy(
        { it.first }, { it.second },
    )
    if (pinned.isNotEmpty()) {
        item(key = "hdr-favorites") { NearbySectionHeader("Favorites", pinned.size, showFavorites, toggleFavorites) }
        if (showFavorites) items(pinned.distinctBy { it.first.mac }, key = { "fav:" + it.first.mac }) { row ->
            DeviceCard(row, newest, true, onFavorite,
                if (row.first.isAp) byBssid[row.first.mac].orEmpty() else emptyList(), favorites, sort,
                onHideAp = onHideAp, deviceStatus = deviceStatus, matches = matches, matchesFirst = matchesFirst, onLocate = onLocate)
        }
        item(key = "div-favorites") { HorizontalDivider() }
    }
    item(key = "hdr-unassociated") { NearbySectionHeader("Unassociated", unassociated.size, showUnassociated, toggleUnassociated) }
    if (showUnassociated) {
        if (unassociated.isEmpty()) item(key = "unassociated-empty") { Text("No probing stations without an observed AP association.") }
        items(unassociated.distinctBy { it.first.mac }, key = { "una:" + it.first.mac }) {
            DeviceCard(it, newest, false, onFavorite, sort = sort, showActivity = true,
                deviceStatus = deviceStatus, matches = matches, matchesFirst = matchesFirst, onLocate = onLocate)
        }
    }
    item(key = "div-unassociated") { HorizontalDivider() }
    item(key = "hdr-aps") { NearbySectionHeader("Access points", aps.size, showAps, toggleAps) }
    if (showAps) {
        if (aps.isEmpty()) item(key = "aps-empty") { Text("No access points received yet.") }
        items(aps.distinctBy { it.first.mac }, key = { "ap:" + it.first.mac }) { ap ->
            DeviceCard(ap, newest, false, onFavorite, byBssid[ap.first.mac].orEmpty(), favorites, sort,
                onHideAp = onHideAp, deviceStatus = deviceStatus, matches = matches, matchesFirst = matchesFirst, onLocate = onLocate)
        }
    }
}

@Composable
private fun NearbySectionHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("$title ($count)", style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun kindIcon(kind: SniffedDevices.Kind): String = when (kind) {
    SniffedDevices.Kind.ACCESS_POINT -> "📡"
    SniffedDevices.Kind.CAMERA -> "📷"
    SniffedDevices.Kind.IOT -> "⌂"
    SniffedDevices.Kind.PHONE_LAPTOP -> "▣"
    SniffedDevices.Kind.CLIENT -> "●"
}

@Composable
private fun DeviceCard(
    row: Pair<SniffedDevices.Device, SniffedDevices.Guess>,
    newest: Long,
    favorite: Boolean,
    onFavorite: (String) -> Unit,
    stations: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>> = emptyList(),
    favorites: Set<String> = emptySet(),
    sort: NearbySort = NearbySort.RSSI,
    showActivity: Boolean = false,
    onHideAp: ((SniffedDevices.Device) -> Unit)? = null,
    hidden: Boolean = false,
    deviceStatus: Map<String, String> = emptyMap(),
    matches: Set<String> = emptySet(),
    matchesFirst: Boolean = false,
    onLocate: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(row.first.mac) { mutableStateOf(false) }
    val (device, guess) = row
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindIcon(guess.kind))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(SniffedDevices.displayName(device, guess),
                        style = MaterialTheme.typography.titleSmall)
                    val channel = if (device.isAp) device.channels.joinToString("/")
                        else device.lastRssiChannel?.toString() ?: device.channels.joinToString("/")
                    Text("${(device.lastRssi ?: device.bestRssi)?.let { "$it dBm" } ?: "RSSI ?"} · ch ${channel.ifBlank { "?" }} · " +
                        (deviceStatus[device.mac] ?: relativeSeen((newest - device.lastMs).coerceAtLeast(0))),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (onLocate != null) IconButton({ onLocate(device.mac) },
                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Show on Mission map" }) {
                    Text("📍", style = MaterialTheme.typography.titleMedium)
                }
                if (device.isAp && onHideAp != null) IconButton({ onHideAp(device) },
                    modifier = Modifier.size(40.dp).semantics {
                        contentDescription = if (hidden) "Show AP in mission" else "Hide AP for mission"
                    }) { Text(if (hidden) "↩" else "⊘", style = MaterialTheme.typography.titleMedium) }
                IconButton({ onFavorite(device.mac) }, modifier = Modifier.size(40.dp).semantics {
                    contentDescription = if (favorite) "Remove favorite" else "Add favorite"
                }) { Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.titleMedium) }
            }
            if (device.isAp || device.security.isNotBlank() || device.wpsDetails.isNotBlank()) {
                val wps = when {
                    device.wpsState.isNotBlank() -> "WPS ${device.wpsState}"
                    device.wpsDetails.isNotBlank() -> "WPS advertised"
                    else -> "WPS —"
                }
                Text("${device.security.ifBlank { "Security —" }} · $wps",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (device.securityAlerts.isNotEmpty()) {
                Text("⚠ ${device.securityAlerts.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showActivity && !expanded) SniffedDevices.activity(device).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                Text("${device.mac} · ${guess.kind.label}", style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                Text(guess.reasons.joinToString("; ").ifBlank { "No identifying evidence" }, style = MaterialTheme.typography.bodySmall)
                SniffedDevices.activity(device).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                device.securityDetails.takeIf { it.isNotBlank() }?.let {
                    Text("Security: $it", style = MaterialTheme.typography.bodySmall)
                }
                device.wpsDetails.takeIf { it.isNotBlank() }?.let {
                    Text("WPS: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (stations.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Associated stations (${stations.size})", style = MaterialTheme.typography.labelLarge)
                    stations.sortedWith(if (matchesFirst) MonitorInventory.matchOrder(matches) else nearbyOrder(sort)).forEach {
                        DeviceCard(it, newest, it.first.mac in favorites, onFavorite, sort = sort,
                            onHideAp = onHideAp, deviceStatus = deviceStatus, matches = matches, matchesFirst = matchesFirst, onLocate = onLocate)
                    }
                }
            } else if (stations.isNotEmpty()) {
                Text("${stations.size} associated stations · tap to expand", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
