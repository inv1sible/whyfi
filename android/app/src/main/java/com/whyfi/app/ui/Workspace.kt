package com.whyfi.app.ui

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyfi.app.scan.MonitorUiState
import com.whyfi.app.usbmon.AdapterStatus
import kotlinx.coroutines.delay

/**
 * The app's two workspaces. Scanning with the phone's own radios (periodic
 * passes, uploaded as sessions) and capturing with an external USB monitor
 * adapter (a live, channel-tuned capture saved as missions) are different
 * jobs, so each gets its own tabs, settings and accent colour.
 *
 * The toggle is always available, adapter or not, so the adapter screens
 * can be developed, tested and demoed without one plugged in.
 */
enum class Workspace(val key: String, val label: String, val icon: String) {
    PHONE("phone", "Phone radios", "📱"),
    ADAPTER("adapter", "USB adapter", "🔌");

    companion object {
        fun from(key: String?): Workspace = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * Compact two-way toggle that sits left of a workspace's tab row: phone on
 * top, USB plug below, the active one filled. Sized to the tab row's height
 * so it reads as part of the tab bar rather than a separate control.
 */
@Composable
fun WorkspaceToggle(current: Workspace, onChange: (Workspace) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.padding(start = 6.dp, end = 2.dp).width(TOGGLE_WIDTH),
    ) {
        Column {
            Workspace.entries.forEach { workspace ->
                val selected = workspace == current
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TOGGLE_CELL_HEIGHT)
                        .background(if (selected) scheme.primaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) { onChange(workspace) }
                        .semantics { contentDescription = workspace.label },
                ) {
                    Text(workspace.icon, fontSize = 16.sp, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val TOGGLE_WIDTH = 44.dp
private val TOGGLE_CELL_HEIGHT = 28.dp

/** The toggle followed by a workspace's tab row, which takes the rest. */
@Composable
fun WorkspaceTabBar(current: Workspace, onChange: (Workspace) -> Unit, tabs: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WorkspaceToggle(current, onChange)
        Box(Modifier.weight(1f)) { tabs() }
    }
}

/** Amber accent for the adapter workspace, so a glance tells a live capture
 * from a survey with the phone's radios (teal). */
fun adapterColorScheme(base: ColorScheme): ColorScheme {
    val dark = base.background.luminance() < 0.5f
    val amber = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val onAmber = if (dark) Color(0xFF2A1A00) else Color(0xFFFFFBEB)
    return base.copy(
        primary = amber,
        onPrimary = onAmber,
        secondary = amber,
        onSecondary = onAmber,
        primaryContainer = amber.copy(alpha = 0.25f),
        secondaryContainer = amber.copy(alpha = 0.25f),
        onSecondaryContainer = base.onSurface,
    )
}

/** USB devices plugged in now. Polled: attach/detach broadcasts aren't
 * reliably delivered to runtime receivers, and the call is cheap. */
@Composable
fun rememberAttachedUsbDevices(): List<UsbDevice> {
    val context = LocalContext.current
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager? }
    var devices by remember { mutableStateOf(usbManager?.deviceList?.values?.toList().orEmpty()) }
    LaunchedEffect(usbManager) {
        while (true) {
            delay(2_000)
            val now = usbManager?.deviceList?.values?.toList().orEmpty()
            if (now.map { it.deviceName } != devices.map { it.deviceName }) devices = now
        }
    }
    return devices
}

/** One line under the switch: recording time, channels, devices, PCAP size,
 * or whether an adapter is plugged in at all. */
@Composable
fun AdapterStatusStrip(state: MonitorUiState, attached: List<UsbDevice>) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.running) {
        while (state.running) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val adapter = state.device.takeIf { it.isNotBlank() }
        ?: attached.firstOrNull()?.let { listOfNotNull(it.manufacturerName, it.productName).joinToString(" ").ifBlank { it.deviceName } }
    val text = AdapterStatus.strip(
        running = state.running,
        adapter = adapter,
        channels = state.channels,
        elapsedMs = if (state.startedAtElapsedMs > 0) nowMs - state.startedAtElapsedMs else 0,
        pcapBytes = state.pcapBytes,
        deviceCount = state.devices.size,
    )
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown in the phone workspace while a USB device is plugged in. */
@Composable
fun AdapterAttachedBanner(onSwitch: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = adapterColorScheme(MaterialTheme.colorScheme).primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("USB device connected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onSwitch) { Text("Open USB adapter") }
        }
    }
}
