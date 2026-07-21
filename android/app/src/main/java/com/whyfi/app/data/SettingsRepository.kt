package com.whyfi.app.data

import android.content.Context
import androidx.core.content.edit

enum class ThemePreference {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromStored(value: String?): ThemePreference = entries.find { it.name == value } ?: SYSTEM
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

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_SENSOR_TOKEN = "sensor_token"
        private const val KEY_THEME = "theme_preference"
    }
}
