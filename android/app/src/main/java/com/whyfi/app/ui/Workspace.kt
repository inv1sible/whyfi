package com.whyfi.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The app's two workspaces. Scanning with the phone's own radios (periodic
 * passes, uploaded as sessions) and capturing with an external USB monitor
 * adapter (a live, channel-tuned capture saved as missions) are different
 * jobs, so each gets its own tabs, settings and accent colour.
 *
 * The toggle is always available, adapter or not, so the adapter screens
 * can be explored without one plugged in (and without a USB test run).
 */
enum class Workspace(val key: String, val label: String, val icon: String) {
    PHONE("phone", "Phone radios", "📱"),
    ADAPTER("adapter", "USB adapter", "🔌");

    companion object {
        fun from(key: String?): Workspace = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * One toggle switch left of a workspace's tab row, standing upright to save
 * width: thumb up = phone radios (📱), thumb down = USB adapter (🔌), the
 * icon riding on the thumb.
 */
@Composable
fun WorkspaceToggle(current: Workspace, onChange: (Workspace) -> Unit) {
    val adapter = current == Workspace.ADAPTER
    Switch(
        checked = adapter,
        onCheckedChange = { onChange(if (it) Workspace.ADAPTER else Workspace.PHONE) },
        // Counter-rotated so the emoji stays upright on the turned switch.
        thumbContent = { Text(current.icon, fontSize = 12.sp, modifier = Modifier.rotate(-90f)) },
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .upright()
            .semantics { contentDescription = "Workspace: ${current.label}. Toggle to switch." },
    )
}

/** Lays the content out with width and height swapped and turns it 90°
 * clockwise, so a horizontal control stands upright and only takes its
 * height in width. Off/start ends up on top. */
private fun Modifier.upright(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight.coerceAtLeast(1), maxHeight = constraints.maxWidth.coerceAtLeast(1)))
    layout(placeable.height, placeable.width) {
        placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
    }
}.rotate(90f)

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
