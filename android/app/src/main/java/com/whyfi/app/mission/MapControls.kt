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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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

/** ⓘ location details, ⟳ reload, ⌖ follow my position. */
@Composable
fun MapButtons(
    detailsOpen: Boolean, onDetails: () -> Unit,
    onRefresh: () -> Unit,
    follow: Boolean, onFollowChange: (Boolean) -> Unit,
) {
    IconButton(onDetails, modifier = Modifier.size(40.dp).semantics {
        contentDescription = if (detailsOpen) "Hide location details" else "Show location details"
    }) { Text("ⓘ", style = MaterialTheme.typography.titleLarge) }
    IconButton(onRefresh, modifier = Modifier.size(40.dp).semantics {
        contentDescription = "Reload readings"
    }) { Text("⟳", style = MaterialTheme.typography.titleLarge) }
    IconToggleButton(follow, onFollowChange, modifier = Modifier.size(40.dp).semantics {
        contentDescription = if (follow) "Stop following my position" else "Follow my position"
    }) {
        Text("⌖", style = MaterialTheme.typography.titleLarge,
            color = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
