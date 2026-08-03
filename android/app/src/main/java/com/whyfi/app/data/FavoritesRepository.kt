package com.whyfi.app.data

import android.content.Context
import androidx.core.content.edit
import com.whyfi.app.scan.RadioKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Identifiers starred from a results table (see ui/ScanDetailScreen.kt's
 * favorite-toggle column), feeding the Mission view's picker (see
 * mission/MissionScreen.kt). One independent set per favoritable
 * [RadioKind] — WIFI (SSID), BLE (MAC), CELLULAR (tower key) — matching each
 * backend mission_*_observations endpoint's own identifier scheme. SATELLITE
 * has no favorites; [favorites] throws for it, same as calling any of these
 * methods with it would be a caller bug, not a real runtime case (nothing in
 * this app ever offers a star toggle on a satellite row).
 *
 * Own file rather than folded into [SettingsRepository]: favorites are a
 * growing, observable *set*, unlike every scalar preference there, and
 * [SettingsRepository]'s own doc comment is specifically about credential
 * storage philosophy, which doesn't apply here. Same plain-SharedPreferences
 * choice as that file though — this is a small, low-stakes, per-device list,
 * not worth EncryptedSharedPreferences' crash history (see
 * SettingsRepository's KDoc).
 */
class FavoritesRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("whyfi_favorites", Context.MODE_PRIVATE)

    private val stateFlows: Map<RadioKind, MutableStateFlow<Set<String>>> = mapOf(
        RadioKind.WIFI to MutableStateFlow(load(RadioKind.WIFI)),
        RadioKind.BLE to MutableStateFlow(load(RadioKind.BLE)),
        RadioKind.CELLULAR to MutableStateFlow(load(RadioKind.CELLULAR)),
    )

    fun favorites(kind: RadioKind): StateFlow<Set<String>> = flowFor(kind).asStateFlow()

    fun isFavorite(kind: RadioKind, id: String): Boolean = flowFor(kind).value.contains(id)

    fun toggleFavorite(kind: RadioKind, id: String) {
        val flow = flowFor(kind)
        val updated = flow.value.toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        prefs.edit { putStringSet(prefsKey(kind), updated) }
        flow.value = updated
    }

    private fun flowFor(kind: RadioKind): MutableStateFlow<Set<String>> =
        stateFlows[kind] ?: error("$kind has no favorites — this is a caller bug, not a real state.")

    private fun load(kind: RadioKind): Set<String> =
        prefs.getStringSet(prefsKey(kind), emptySet())?.toSet() ?: emptySet()

    private fun prefsKey(kind: RadioKind): String = when (kind) {
        RadioKind.WIFI -> "favorite_ssids"
        RadioKind.BLE -> "favorite_ble_devices"
        RadioKind.CELLULAR -> "favorite_cell_towers"
        RadioKind.SATELLITE -> error("SATELLITE has no favorites — this is a caller bug, not a real state.")
    }
}
