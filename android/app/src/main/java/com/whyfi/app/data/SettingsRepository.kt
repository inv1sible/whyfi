package com.whyfi.app.data

import android.content.Context
import androidx.core.content.edit

enum class ThemePreference {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromStored(value: String?): ThemePreference = entries.find { it.name == value } ?: SYSTEM
    }
}

/** GPS is the original, unchanged behavior (pick the freshest of
 * GPS_PROVIDER/NETWORK_PROVIDER) — default, so existing behavior doesn't
 * silently change for anyone. FUSED requests Android's FUSED_PROVIDER
 * (API 31+) as the reported location instead. BOTH keeps the GPS/network
 * pick as the primary reading (identical to GPS mode) *and* additionally
 * captures a fused reading alongside it, for comparing sources. */
enum class LocationSourcePreference {
    GPS, FUSED, BOTH;

    companion object {
        fun fromStored(value: String?): LocationSourcePreference = entries.find { it.name == value } ?: GPS
    }
}

/**
 * Backend URL + sensor token, entered once on the Settings screen.
 *
 * Deliberately plain `SharedPreferences` (app-private storage, standard
 * Android sandboxing — not accessible to other apps without root), not
 * `androidx.security.crypto.EncryptedSharedPreferences`. That library has
 * been stuck in alpha for years (`1.1.0-alpha06`) with a real history of
 * Android Keystore-related crashes on certain devices/OS versions, and it
 * initialized unconditionally in `MainActivity.onCreate()` before any UI
 * rendered — exactly the failure shape of "app crashes immediately on
 * start," reported specifically on GrapheneOS's hardened Keystore
 * implementation. A sensor token is a meaningful bearer credential but not
 * worth crashing the entire app over; plain SharedPreferences is what the
 * large majority of Android apps use for API tokens. See MEMORY.md.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("whyfi_settings", Context.MODE_PRIVATE)

    var backendUrl: String?
        get() = prefs.getString(KEY_BACKEND_URL, null)
        set(value) = prefs.edit { putString(KEY_BACKEND_URL, value?.trimEnd('/')) }

    var sensorToken: String?
        get() = prefs.getString(KEY_SENSOR_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_SENSOR_TOKEN, value) }

    val isConfigured: Boolean
        get() = !backendUrl.isNullOrBlank() && !sensorToken.isNullOrBlank()

    var themePreference: ThemePreference
        get() = ThemePreference.fromStored(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    var locationSourcePreference: LocationSourcePreference
        get() = LocationSourcePreference.fromStored(prefs.getString(KEY_LOCATION_SOURCE, null))
        set(value) = prefs.edit { putString(KEY_LOCATION_SOURCE, value.name) }

    /** Whether this device obeys start/stop instructions from the backend.
     *
     * Off by default and only ever turned on from the Scan screen: Android
     * won't let a server start a location foreground service in the
     * background, so the agent has to be armed by hand once — which is also
     * the honest place to consent to a persistent notification and the
     * battery cost that comes with it. */
    var remoteControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_CONTROL, false)
        set(value) = prefs.edit { putBoolean(KEY_REMOTE_CONTROL, value) }

    /** How much storage the offline outbox may use before the oldest queued
     * scans are dropped. Only bites while uploads are failing — a reachable
     * backend drains the queue continuously, so steady-state usage is ~0. */
    var outboxQuotaMb: Int
        get() = prefs.getInt(KEY_OUTBOX_QUOTA_MB, DEFAULT_OUTBOX_QUOTA_MB)
        set(value) = prefs.edit { putInt(KEY_OUTBOX_QUOTA_MB, value.coerceIn(MIN_OUTBOX_QUOTA_MB, MAX_OUTBOX_QUOTA_MB)) }

    val outboxQuotaBytes: Long
        get() = outboxQuotaMb.toLong() * 1024L * 1024L

    companion object {
        const val DEFAULT_OUTBOX_QUOTA_MB = 100
        const val MIN_OUTBOX_QUOTA_MB = 5
        const val MAX_OUTBOX_QUOTA_MB = 10_000

        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_SENSOR_TOKEN = "sensor_token"
        private const val KEY_THEME = "theme_preference"
        private const val KEY_LOCATION_SOURCE = "location_source_preference"
        private const val KEY_REMOTE_CONTROL = "remote_control_enabled"
        private const val KEY_OUTBOX_QUOTA_MB = "outbox_quota_mb"
    }
}
