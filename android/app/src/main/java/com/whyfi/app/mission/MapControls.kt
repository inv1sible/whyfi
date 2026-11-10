package com.whyfi.app.mission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/*
 * Controls shared by the two map views, phone Mission and the USB adapter's
 * Mission tab, so both look and work the same (user, 2026-11-09): layer
 * toggles, the ⓘ ⟳ ⌖ buttons and the location stats.
 */

/** One layer toggle in a map's controls bar. */
@Composable
fun MapLayerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label, maxLines = 1) })
}

/**
 * The map buttons, floating on the map itself at its lower right, the same
 * on both maps (user, 2026-11-10: recentering was hard to find while
 * driving). Bottom up, so the most used is nearest the thumb: ⌖ follow my
 * position (tap to recenter and follow, again to stop), ⟳ reload,
 * ⓘ location details, ◧ map layers ([layers] in a panel above the buttons).
 */
@Composable
fun MapOverlayControls(
    modifier: Modifier = Modifier,
    detailsOpen: Boolean, onDetails: () -> Unit,
    onRefresh: () -> Unit,
    follow: Boolean, onFollowChange: (Boolean) -> Unit,
    layers: @Composable ColumnScope.() -> Unit,
) {
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(layersOpen) {
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), content = layers)
            }
        }
        OverlayButton("◧", layersOpen, if (layersOpen) "Hide map layers" else "Show map layers") { layersOpen = !layersOpen }
        OverlayButton("ⓘ", detailsOpen, if (detailsOpen) "Hide location details" else "Show location details", onDetails)
        OverlayButton("⟳", false, "Reload readings", onRefresh)
        OverlayButton("⌖", follow, if (follow) "Stop following my position" else "Center on my position and follow") { onFollowChange(!follow) }
    }
}

/** One round button over the map, highlighted while [active]. 48 dp so it
 * can be hit in a car mount. */
@Composable
private fun OverlayButton(glyph: String, active: Boolean, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, style = MaterialTheme.typography.titleLarge) }
    }
}

/** The phone's own position, the same words on both maps: "gps ±8 m". */
fun fixSummary(provider: String, accuracyM: Double?): String =
    provider + (accuracyM?.let { " ±${it.toInt()} m" } ?: "")

/** One platform fix in the details panel. */
data class FixInfo(val provider: String, val lat: Double, val lng: Double, val accuracyM: Double, val ageMs: Long)

fun Location.toFixInfo(nowElapsedMs: Long = SystemClock.elapsedRealtime()): FixInfo = FixInfo(
    provider ?: "unknown", latitude, longitude,
    if (hasAccuracy()) accuracy.toDouble() else 1_000.0,
    (nowElapsedMs - elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0),
)

/** "gps on · network on · fused off". */
fun locationProviderStatus(context: Context): String {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
    return providers.joinToString(" · ") { provider ->
        "$provider ${if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) "on" else "off"}"
    }
}

/** Permission, providers and each provider's latest fix: the top of the
 * ⓘ panel on both maps. */
@Composable
fun LocationFixList(
    context: Context,
    fixes: List<FixInfo>,
    onRequestPermission: () -> Unit,
    staleAfterMs: Long? = null,
) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    Text("Android location", style = MaterialTheme.typography.titleSmall)
    Text("Permission: ${if (fine) "precise" else if (coarse) "approximate" else "none"} · ${locationProviderStatus(context)}",
        style = MaterialTheme.typography.bodySmall)
    if (!fine && !coarse) TextButton(onRequestPermission) { Text("Grant location permission") }
    if (fixes.isEmpty()) Text("Android has delivered no GPS, network or fused fix yet.", style = MaterialTheme.typography.bodySmall)
    fixes.sortedBy { it.provider }.forEach { fix ->
        Text("${fix.provider}: %.6f, %.6f · ±%.0f m · %d s old%s".format(
            fix.lat, fix.lng, fix.accuracyM, fix.ageMs / 1_000,
            if (staleAfterMs != null && fix.ageMs > staleAfterMs) " (too old for capture)" else ""),
            style = MaterialTheme.typography.bodySmall)
    }
}

/** The ⓘ panel, sliding down over the top of the map. */
@Composable
fun MapDetailsPanel(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier = modifier,
        enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        }
    }
}
