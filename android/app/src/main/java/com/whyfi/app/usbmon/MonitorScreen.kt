package com.whyfi.app.usbmon

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.BuildConfig
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local receive session; completed missions can be uploaded explicitly. */
@Composable
fun MonitorScreen(service: ScanForegroundService?, settingsRepository: SettingsRepository, unlocked: Boolean) {
    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Monitor", style = MaterialTheme.typography.headlineSmall)
            Text("Monitor is locked until this phone has received frames during the USB monitor test.")
            Text("Connect the supported adapter through OTG, then run USB monitor test in Settings. A successful test enables this tab.",
                style = MaterialTheme.typography.bodySmall)
        }
    } else MonitorWorkspace(service, settingsRepository)
}

private enum class MonitorTab(val label: String) {
    NEARBY("Nearby"), MAPPING("Location"), MISSIONS("Missions"), ACCESS_POINTS("APs"), UNASSOCIATED("Unassociated"),
    IOT("IoT"), CAMERAS("Cameras"), PHONES_LAPTOPS("Phones / laptops"), CLIENTS("Other clients"),
}

private enum class NearbySort { RSSI, LAST_SEEN }

@Composable
private fun MonitorWorkspace(service: ScanForegroundService?, settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var visibleKinds by remember { mutableStateOf(SniffedDevices.Kind.entries.toSet()) }
    var autoUpdate by rememberSaveable { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(NearbySort.RSSI.name) }
    val nearbySort = NearbySort.entries.firstOrNull { it.name == sortName } ?: NearbySort.RSSI
    var displayDevices by remember { mutableStateOf(state.devices) }
    LaunchedEffect(state.devices, autoUpdate) {
        if (autoUpdate) displayDevices = state.devices
    }
    var starting by remember { mutableStateOf(false) }
    var recordPcap by remember { mutableStateOf(settingsRepository.monitorRecordPcap) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf(MonitorTab.NEARBY.name) }
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
    var locationSummary by remember { mutableStateOf("") }
    var missionRevision by remember { mutableIntStateOf(0) }
    var pendingReset by remember { mutableStateOf(false) }
    var showMissionPicker by remember { mutableStateOf(false) }
    var showAllAps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedMissionLogPath) {
        replayMission = null
        replayFraction = 1f
        settledFraction = 1f
        replayPlaying = false
        selectedMissionLogPath?.let { path ->
            replayMission = withContext(Dispatchers.IO) {
                File(path).takeIf { it.isFile }?.let { MonitorReplayMission.load(context, it) }
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
    val replayDevices = remember(replaySnapshot?.atMs ?: replayCutoffMs, activeReplay) {
        replaySnapshot?.let { snapshot ->
            SniffedDevices.parse(snapshot.tsv).map { device ->
                device to SniffedDevices.guess(device) { activeReplay.vendors?.vendorOf(it) }
            }
        } ?: activeReplay?.legacyDevicesAt(replayCutoffMs).orEmpty()
    }
    val replaying = selectedMissionLogPath != null
    val selected = MonitorTab.entries.firstOrNull { it.name == selectedName } ?: MonitorTab.NEARBY
    val tabs = MonitorTab.entries.toList()
    val nearbyDevices = displayDevices.filter {
        it.second.kind in visibleKinds || (showHiddenAps && MonitorApVisibility.isHidden(it.first, hiddenAps))
    }
    val noiseCount = nearbyDevices.count { row ->
        row.first.mac !in favorites && !MonitorApVisibility.isHidden(row.first, hiddenAps) &&
            MonitorNoiseFilter.matches(row, noisePatterns)
    }
    val categoryDevices = (if (replaying) replayDevices else displayDevices)
        .filter { it.second.kind in visibleKinds && (replaying || !MonitorApVisibility.isHidden(it.first, hiddenAps)) }
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
    val toggleHiddenAp: (SniffedDevices.Device) -> Unit = { device ->
        val key = MonitorApVisibility.key(device)
        val legacyHidden = device.mac in hiddenAps && key != device.mac
        hiddenAps = if (MonitorApVisibility.isHidden(device, hiddenAps)) hiddenAps - key - device.mac
            else hiddenAps + key
        settingsRepository.monitorHiddenApMacs = hiddenAps
        if (MonitorApVisibility.isHidden(device, hiddenAps) && selectedLocationMac == device.mac) selectedLocationMac = null
        if (legacyHidden) recordAction("ap_hidden", "${device.mac}:false")
        recordAction("ap_hidden", "$key:${MonitorApVisibility.isHidden(device, hiddenAps)}")
    }
    val restoreHiddenKey: (String) -> Unit = { key ->
        hiddenAps = hiddenAps - key
        settingsRepository.monitorHiddenApMacs = hiddenAps
        recordAction("ap_hidden", "$key:false")
    }
    val continueMission: (MonitorMission.Stored) -> Unit = { mission ->
        settingsRepository.activeMonitorMissionId = mission.directory.name
        favorites = MonitorMission.currentFavorites(mission.log)
        settingsRepository.monitorFavoriteMacs = favorites
        hiddenAps = MonitorMission.currentHiddenAps(mission.log)
        settingsRepository.monitorHiddenApMacs = hiddenAps
        showHiddenAps = false
        service?.clearFinishedMonitor()
        displayDevices = emptyList()
        selectedMissionLogPath = null
        selectedLocationMac = null
        locationTargets = emptyList()
        selectedName = MonitorTab.NEARBY.name
        showMissionPicker = false
    }
    val beginMonitor: () -> Unit = start@{
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
        displayDevices = emptyList()
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

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(tabs.indexOf(selected).coerceAtLeast(0), edgePadding = 0.dp) {
            tabs.forEach { tab ->
                val count = when (tab) {
                    MonitorTab.NEARBY, MonitorTab.MAPPING -> 0
                    MonitorTab.MISSIONS -> remember(missionRevision, state.reportPath, state.missionLogPath) {
                        MonitorMission.list(context).size
                    }
                    else -> monitorRows(tab, categoryDevices).size
                }
                Tab(tab == selected, {
                    if (tab != MonitorTab.MAPPING) showLocationDetails = false
                    if (tab == MonitorTab.MISSIONS || tab == MonitorTab.NEARBY) replayPlaying = false
                    selectedName = tab.name
                },
                    text = { Text(if (tab == MonitorTab.MAPPING || tab == MonitorTab.NEARBY) tab.label else "${tab.label} ($count)") })
            }
        }
        if (selected == MonitorTab.NEARBY) {
            Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val activeId = settingsRepository.activeMonitorMissionId
                    Text("${if (activeId == null) "New mission" else "Mission ${activeId.take(8)}"} · v${BuildConfig.VERSION_CODE}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ showMissionPicker = true }, enabled = !state.running && !starting && !pendingStart) {
                        Text("Open mission")
                    }
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
                        displayDevices = state.devices
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
        if (selected == MonitorTab.NEARBY) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                state.reportPath?.let { Text("Capture saved. Start continues this mission; Reset closes it for upload.",
                    style = MaterialTheme.typography.bodySmall) }
                if (state.pcapTruncated) Text("PCAP recording stopped at the 200 MB limit.", color = MaterialTheme.colorScheme.error)
                NearbyView(nearbyDevices, favorites, toggleFavorite, hiddenAps, toggleHiddenAp, restoreHiddenKey,
                    showHiddenAps, hideNoise, noisePatterns, nearbySort,
                    showFavoriteCards, { showFavoriteCards = !showFavoriteCards },
                    showUnassociatedCards, { showUnassociatedCards = !showUnassociatedCards },
                    showApCards, { showApCards = !showApCards })
            }
        } else if (selected == MonitorTab.MISSIONS) {
            MissionView(context, settingsRepository, state.reportPath,
                onOpenLocation = { path, mac ->
                    selectedMissionLogPath = path
                    selectedLocationMac = mac
                    replayFraction = 1f
                    settledFraction = 1f
                    replayPlaying = false
                    locationTargets = emptyList()
                    selectedName = MonitorTab.MAPPING.name
                    locationRecenter++
                }, onContinue = continueMission, onDeleted = { path ->
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
                hiddenAps.filterNot { it.startsWith("ssid:") }.toSet() +
                    displayDevices.filter { MonitorApVisibility.isHidden(it.first, hiddenAps) }.map { it.first.mac },
                settingsRepository.activeMonitorMissionId,
                selectedMissionLogPath, activeReplay, showAllAps,
                replayCutoffMs, followMe, selectedLocationMac,
                { locationTargets = it }, { locationSummary = it }, locationRefresh, locationRecenter,
                showLocationDetails, onSelectAp = { mac ->
                    selectedLocationMac = mac
                    followMe = false
                }, onFavoriteAp = toggleFavorite,
                Modifier.weight(1f))
        } else {
            if (replaying && activeReplay?.snapshots?.isEmpty() == true) Text(
                "This older mission did not save device roles or category snapshots.",
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            MonitorTable(selected, categoryDevices, Modifier.weight(1f).padding(12.dp),
                referenceMs = replayReferenceMs.takeIf { replaying },
                onOpenApLocation = { mac ->
                    selectedLocationMac = mac
                    followMe = false
                    selectedName = MonitorTab.MAPPING.name
                    locationRecenter++
                })
        }
        if (selected != MonitorTab.MISSIONS) {
            if (replaying && selected != MonitorTab.NEARBY && timeline.second > timeline.first) {
                Slider(replayFraction, onValueChange = { replayPlaying = false; replayFraction = it },
                    onValueChangeFinished = { settledFraction = replayFraction },
                    modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp))
            }
            Surface(Modifier.fillMaxWidth(), tonalElevation = 5.dp, shadowElevation = 5.dp) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
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
                                LocationFooterControls(showLocationDetails, { showLocationDetails = !showLocationDetails },
                                    { locationRefresh++ }, followMe, { enabled ->
                                        followMe = enabled
                                        if (enabled) { selectedLocationMac = null; locationRecenter++ }
                                    })
                            }
                        }
                    } else {
                        Text(if (selected == MonitorTab.MAPPING) locationSummary
                            else buildString {
                                append(state.status)
                                if (selected == MonitorTab.NEARBY) append(when {
                                    state.pcapPath != null -> " · PCAP ${formatPcapBytes(state.pcapBytes)}"
                                    state.running -> " · PCAP off"
                                    recordPcap -> " · PCAP armed"
                                    else -> " · PCAP off"
                                })
                                append(" · ${state.frames} frames · ${state.geoSamples} location samples")
                            },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.running) Button({ service?.stopMonitor() },
                                enabled = state.status != "Starting…" && state.status != "Stopping…" &&
                                    state.status != "Saving report…") { Text("Stop") }
                            else Button(startMonitor, enabled = !starting && selectedDevice != null) {
                                Text(if (starting) "Starting…" else "Start")
                            }
                            if (selected == MonitorTab.NEARBY) TextButton({ pendingReset = true },
                                enabled = !state.running && !starting && !pendingStart) { Text("Reset") }
                            Checkbox(recordPcap, {
                                recordPcap = it
                                settingsRepository.monitorRecordPcap = it
                            }, enabled = !state.running)
                            Text(if (recordPcap) "PCAP on" else "PCAP off", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            if (selected == MonitorTab.MAPPING) {
                                LocationFooterControls(showLocationDetails, { showLocationDetails = !showLocationDetails },
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(showAllAps, { showAllAps = !showAllAps }, label = { Text("All AP estimates") })
                FilterChip(selectedTarget == null, { selectedLocationMac = null },
                    label = { Text("All (${locationTargets.size})") })
                locationTargets.forEach { target ->
                    FilterChip(selectedTarget == target.mac, { selectedLocationMac = target.mac; followMe = false },
                        label = { Text("${target.label} (${target.readings})") })
                }
            }
        } else if (selected != MonitorTab.MISSIONS && selected != MonitorTab.MAPPING) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected == MonitorTab.NEARBY) FilterChip(showHiddenAps,
                    { showHiddenAps = !showHiddenAps }, label = { Text("Hidden APs (${hiddenAps.size})") })
                if (selected == MonitorTab.NEARBY) FilterChip(hideNoise, {
                    hideNoise = !hideNoise
                    settingsRepository.monitorHideNoise = hideNoise
                    recordAction("noise_filter", if (hideNoise) "on" else "off")
                }, label = { Text("Hide noise ($noiseCount)") })
                SniffedDevices.Kind.entries.forEach { kind ->
                    FilterChip(kind in visibleKinds, {
                        visibleKinds = if (kind in visibleKinds) visibleKinds - kind else visibleKinds + kind
                        if (!replaying) recordAction("filter", "${kind.name}:${kind in visibleKinds}")
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
                displayDevices = emptyList()
                service?.clearFinishedMonitor()
                missionRevision++
            }) { Text("Reset") } },
            dismissButton = { TextButton({ pendingReset = false }) { Text("Cancel") } })

        if (showMissionPicker) {
            val candidates = MonitorMission.list(context).filter {
                it.log.isFile && !it.uploaded && it.directory.name != settingsRepository.activeMonitorMissionId
            }
            AlertDialog(onDismissRequest = { showMissionPicker = false },
                title = { Text("Continue a saved mission") },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        if (candidates.isEmpty()) Text("No other local, unuploaded mission is available. Start continues the current mission.")
                        candidates.forEach { mission ->
                            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(mission.log.lastModified()))
                            TextButton({ continueMission(mission) }, modifier = Modifier.fillMaxWidth()) {
                                Text("$date · ${mission.directory.name.take(8)}" +
                                    if (mission.pcap != null) " · PCAP" else " · no PCAP")
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ showMissionPicker = false }) { Text("Close") } })
        }

    }
}

@Composable
private fun LocationFooterControls(
    detailsOpen: Boolean, onDetails: () -> Unit, onRefresh: () -> Unit,
    followMe: Boolean, onFollowChange: (Boolean) -> Unit,
) {
    IconButton(onDetails, modifier = Modifier.size(40.dp).semantics {
        contentDescription = if (detailsOpen) "Hide geolocation details" else "Show geolocation details"
    }) { Text("ⓘ", style = MaterialTheme.typography.titleLarge) }
    IconButton(onRefresh, modifier = Modifier.size(40.dp).semantics {
        contentDescription = "Refresh location and mission"
    }) { Text("⟳", style = MaterialTheme.typography.titleLarge) }
    IconToggleButton(followMe, onFollowChange, modifier = Modifier.size(40.dp).semantics {
        contentDescription = if (followMe) "Stop following my position" else "Follow my position"
    }) {
        Text("⌖", style = MaterialTheme.typography.titleLarge,
            color = if (followMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        MonitorTab.NEARBY, MonitorTab.MAPPING, MonitorTab.MISSIONS -> false
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
    onOpenApLocation: (String) -> Unit = {},
) {
    val rows = monitorRows(tab, devices)
    val newest = referenceMs ?: rows.maxOfOrNull { it.first.lastMs } ?: 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == MonitorTab.PHONES_LAPTOPS) {
            Text("Current frame metadata cannot reliably distinguish phones from laptops.", style = MaterialTheme.typography.bodySmall)
        }
        if (tab == MonitorTab.CAMERAS && rows.isEmpty()) {
            Text("No camera identified yet. Check APs and other client tabs; identification needs recognizable names, manufacturer, or uplink traffic.",
                style = MaterialTheme.typography.bodySmall)
        }
        DataTable(
            columns = listOf(
                TableColumn("Device", 150.dp),
                TableColumn("MAC", 150.dp),
                TableColumn("Manufacturer", 180.dp),
                TableColumn("RSSI", 70.dp),
                TableColumn("Channel", 80.dp),
                TableColumn("Security", 170.dp),
                TableColumn("WPS", 180.dp),
                TableColumn("Seen", 76.dp),
                TableColumn("Activity", 190.dp),
            ),
            rows = rows.map { (device, guess) ->
                DataTableRow(
                    key = device.mac,
                    onClick = if (tab == MonitorTab.ACCESS_POINTS) { { onOpenApLocation(device.mac) } } else null,
                    cells = listOf(
                        (device.ssid.takeIf { it.isNotEmpty() } ?: device.wpsName.takeIf { it.isNotEmpty() }
                            ?: if (device.randomized) "private MAC" else "unknown") +
                            if (tab == MonitorTab.ACCESS_POINTS) "  ↗ map" else "",
                        device.mac,
                        guess.vendor ?: "—",
                        device.bestRssi?.let { "$it dBm" } ?: "—",
                        device.channels.joinToString().ifBlank { "—" },
                        device.securityDetails.ifBlank { "—" },
                        device.wpsDetails.ifBlank { "—" },
                        relativeSeen(newest - device.lastMs),
                        SniffedDevices.activity(device).ifBlank { guess.kind.label },
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

@Composable
private fun NearbyView(
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
) {
    val order = nearbyOrder(sort)
    if (showHiddenAps) {
        val hiddenRows = devices.filter { MonitorApVisibility.isHidden(it.first, hiddenAps) }.sortedWith(order)
        val grouped = hiddenRows.groupBy { row ->
            if (row.first.mac in hiddenAps) row.first.mac else MonitorApVisibility.key(row.first)
        }
        Text("Hidden AP groups (${hiddenAps.size})", style = MaterialTheme.typography.titleMedium)
        if (hiddenAps.isEmpty()) Text("No APs hidden in this mission.")
        (hiddenAps + grouped.keys).sorted().distinct().forEach { key ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val count = grouped[key]?.size ?: 0
                Text(MonitorApVisibility.label(key) + if (count > 0) " · $count APs" else "",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ onRestoreHiddenKey(key) }) { Text("Show") }
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
        NearbySectionHeader("Favorites", pinned.size, showFavorites, toggleFavorites)
        if (showFavorites) pinned.forEach { row ->
            DeviceCard(row, newest, true, onFavorite,
                if (row.first.isAp) byBssid[row.first.mac].orEmpty() else emptyList(), favorites, sort,
                onHideAp = onHideAp)
        }
        HorizontalDivider()
    }
    NearbySectionHeader("Unassociated", unassociated.size, showUnassociated, toggleUnassociated)
    if (showUnassociated) {
        if (unassociated.isEmpty()) Text("No probing stations without an observed AP association.")
        unassociated.forEach { DeviceCard(it, newest, false, onFavorite, sort = sort, showActivity = true) }
    }
    HorizontalDivider()
    NearbySectionHeader("Access points", aps.size, showAps, toggleAps)
    if (showAps) {
        if (aps.isEmpty()) Text("No access points received yet.")
        aps.forEach { ap ->
            DeviceCard(ap, newest, false, onFavorite, byBssid[ap.first.mac].orEmpty(), favorites, sort,
                onHideAp = onHideAp)
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
) {
    var expanded by rememberSaveable(row.first.mac) { mutableStateOf(false) }
    val (device, guess) = row
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindIcon(guess.kind))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.ssid.takeIf { it.isNotBlank() } ?: guess.vendor ?: "unknown device",
                        style = MaterialTheme.typography.titleSmall)
                    val channel = if (device.isAp) device.channels.joinToString("/")
                        else device.lastRssiChannel?.toString() ?: device.channels.joinToString("/")
                    Text("${device.bestRssi?.let { "$it dBm" } ?: "RSSI ?"} · ch ${channel.ifBlank { "?" }} · ${relativeSeen(newest - device.lastMs)}",
                        style = MaterialTheme.typography.bodySmall)
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
                    stations.sortedWith(nearbyOrder(sort)).forEach {
                        DeviceCard(it, newest, it.first.mac in favorites, onFavorite, sort = sort,
                            onHideAp = onHideAp)
                    }
                }
            } else if (stations.isNotEmpty()) {
                Text("${stations.size} associated stations · tap to expand", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
