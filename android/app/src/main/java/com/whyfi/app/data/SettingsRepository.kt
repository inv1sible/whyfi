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
 * Which algorithm estimates a transmitter's position. Mirrors the PWA's
 * estimatorPreference.ts and the backend's scans/estimators.py — the wire
 * value is the lowercase name those two use, so this enum converts rather
 * than inventing its own spelling.
 *
 * CENTROID is the default because it's the original, always-available
 * behaviour: no distance model to be wrong about, works for every radio type.
 */
enum class PositionEstimator(val wireValue: String, val label: String) {
    CENTROID("centroid", "Signal-weighted centroid"),
    STRONGEST("strongest", "Strongest reading"),
    RSSI_MULTILATERATION("rssi_multilateration", "RSSI multilateration"),
    FTM_MULTILATERATION("ftm_multilateration", "FTM multilateration");

    companion object {
        fun fromStored(value: String?): PositionEstimator = entries.find { it.name == value } ?: CENTROID
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

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Identifies the server set up now (URL + token), for "asked once per server". */
    val serverKey: String
        get() = "${backendUrl.orEmpty()}|${sensorToken.orEmpty()}".hashCode().toString()

    /** The [serverKey] whose upload question (ConnectUploadPrompt) was answered. */
    var uploadPromptAnsweredFor: String?
        get() = prefs.getString("upload_prompt_answered_for", null)
        set(value) = prefs.edit { putString("upload_prompt_answered_for", value) }

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


    /** Which estimator Mission view uses for a target's position (the cone
     * apex). Device-local, same as [themePreference] — the PWA keeps its own
     * in localStorage; there's no server-side user-settings model to sync
     * through. */
    var positionEstimator: PositionEstimator
        get() = PositionEstimator.fromStored(prefs.getString(KEY_POSITION_ESTIMATOR, null))
        set(value) = prefs.edit { putString(KEY_POSITION_ESTIMATOR, value.name) }

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

    var monitorTestPassed: Boolean
        get() = prefs.getBoolean(KEY_MONITOR_TEST_PASSED, false)
        set(value) = prefs.edit { putBoolean(KEY_MONITOR_TEST_PASSED, value) }

    /** Which workspace the app opens in: "phone" (the phone's own radios)
     * or "adapter" (the external USB monitor adapter). See ui/Workspace.kt. */
    var workspace: String
        get() = prefs.getString(KEY_WORKSPACE, "phone") ?: "phone"
        set(value) = prefs.edit { putString(KEY_WORKSPACE, value) }

    var monitorChannels: Set<Int>
        get() = prefs.getString(KEY_MONITOR_CHANNELS, null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_MONITOR_CHANNELS, value.sorted().joinToString(",")) }

    var monitorFavoriteMacs: Set<String>
        get() = prefs.getStringSet(KEY_MONITOR_FAVORITES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_MONITOR_FAVORITES, value) }

    /** Hidden APs, shared by the USB adapter's ⊘ and the phone's Wi‑Fi
     * results (user, 2026-11-09): "ssid:<name>" hides every AP with that
     * name, a lowercase BSSID one unnamed AP (usbmon.MonitorApVisibility). */
    var monitorHiddenApMacs: Set<String>
        get() = prefs.getStringSet("monitor_hidden_ap_macs", emptySet()).orEmpty().toSet()
        set(value) {
            prefs.edit { putStringSet("monitor_hidden_ap_macs", value) }
            hiddenApFlow?.value = value
        }

    /** [monitorHiddenApMacs] as it changes, from either workspace. */
    val hiddenApKeys: kotlinx.coroutines.flow.StateFlow<Set<String>>
        get() = synchronized(HIDDEN_LOCK) {
            hiddenApFlow ?: kotlinx.coroutines.flow.MutableStateFlow(monitorHiddenApMacs).also { hiddenApFlow = it }
        }

    var monitorRecordPcap: Boolean
        get() = prefs.getBoolean("monitor_record_pcap", true)
        set(value) = prefs.edit { putBoolean("monitor_record_pcap", value) }

    var monitorNoisePatterns: String
        get() = prefs.getString("monitor_noise_patterns", "unknown device") ?: "unknown device"
        set(value) = prefs.edit { putString("monitor_noise_patterns", value) }

    /** Scan → "What to scan": remembered, and the scan report covers the same radios. */
    var scanIncludeWifi: Boolean
        get() = prefs.getBoolean("scan_include_wifi", true)
        set(value) = prefs.edit { putBoolean("scan_include_wifi", value) }
    var scanIncludeCellular: Boolean
        get() = prefs.getBoolean("scan_include_cellular", true)
        set(value) = prefs.edit { putBoolean("scan_include_cellular", value) }
    var scanIncludeBle: Boolean
        get() = prefs.getBoolean("scan_include_ble", true)
        set(value) = prefs.edit { putBoolean("scan_include_ble", value) }
    var scanIncludeGnss: Boolean
        get() = prefs.getBoolean("scan_include_gnss", true)
        set(value) = prefs.edit { putBoolean("scan_include_gnss", value) }

    /** The tab last shown in each workspace, so switching phone/USB (or
     * reopening the app) comes back to it. */
    var phoneTab: Int
        get() = prefs.getInt("phone_tab", 0)
        set(value) = prefs.edit { putInt("phone_tab", value) }
    var monitorTab: String?
        get() = prefs.getString("monitor_tab", null)
        set(value) = prefs.edit { putString("monitor_tab", value) }

    /** USB Scan tab "⏱ Seen" filter in minutes, 0 = all (MonitorAgeFilter). */
    var monitorSeenWithinMin: Int
        get() = prefs.getInt("monitor_seen_within_min", 0)
        set(value) = prefs.edit { putInt("monitor_seen_within_min", value) }

    /** USB missions upload after every Stop (log; PCAP on Wi‑Fi). */
    var monitorAutoUpload: Boolean
        get() = prefs.getBoolean("monitor_auto_upload", true)
        set(value) = prefs.edit { putBoolean("monitor_auto_upload", value) }

    /** USB "👣 With me": places a device must be heard at (MonitorCoTravel). */
    var monitorCoTravelMinPlaces: Int
        get() = prefs.getInt("monitor_cotravel_min_places", 3)
        set(value) = prefs.edit { putInt("monitor_cotravel_min_places", value) }

    /** Notify while capturing when a device starts travelling with the phone. */
    var monitorCoTravelAlerts: Boolean
        get() = prefs.getBoolean("monitor_cotravel_alerts", true)
        set(value) = prefs.edit { putBoolean("monitor_cotravel_alerts", value) }

    /** Devices the user ⊘-ignored in "With me" (own phone, car): never alerted. */
    var monitorCoTravelIgnored: Set<String>
        get() = prefs.getStringSet("monitor_cotravel_ignored", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet("monitor_cotravel_ignored", value) }

    /** Phone Mission map "⏱ Seen" filter in minutes, 0 = any time. */
    var missionSeenWithinMin: Int
        get() = prefs.getInt("mission_seen_within_min", 0)
        set(value) = prefs.edit { putInt("mission_seen_within_min", value) }

    var monitorHideNoise: Boolean
        get() = prefs.getBoolean("monitor_hide_noise", false)
        set(value) = prefs.edit { putBoolean("monitor_hide_noise", value) }

    var activeMonitorMissionId: String?
        get() = prefs.getString("active_monitor_mission_id", null)
        set(value) = prefs.edit { putString("active_monitor_mission_id", value) }

    /** How much storage recordings may use (RecordingStore / StoragePolicy):
     * uploaded recordings are removed first; never-uploaded ones never
     * automatically. The key keeps its old "outbox" name so a saved value survives. */
    var outboxQuotaMb: Int
        get() = prefs.getInt(KEY_OUTBOX_QUOTA_MB, DEFAULT_OUTBOX_QUOTA_MB)
        set(value) = prefs.edit { putInt(KEY_OUTBOX_QUOTA_MB, value.coerceIn(MIN_OUTBOX_QUOTA_MB, MAX_OUTBOX_QUOTA_MB)) }

    val outboxQuotaBytes: Long
        get() = outboxQuotaMb.toLong() * 1024L * 1024L

    /** Pick the scan interval from how the phone is moving instead of using
     * one fixed cadence. A phone on a desk re-scans the same airwaves all day;
     * a phone in a car covers new ground every second. */
    var adaptiveScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_SCAN, true)
        set(value) = prefs.edit { putBoolean(KEY_ADAPTIVE_SCAN, value) }

    var stationaryIntervalSeconds: Int
        get() = prefs.getInt(KEY_STATIONARY_INTERVAL, DEFAULT_STATIONARY_INTERVAL_SECONDS)
        set(value) = prefs.edit { putInt(KEY_STATIONARY_INTERVAL, clampInterval(value)) }

    var walkingIntervalSeconds: Int
        get() = prefs.getInt(KEY_WALKING_INTERVAL, DEFAULT_WALKING_INTERVAL_SECONDS)
        set(value) = prefs.edit { putInt(KEY_WALKING_INTERVAL, clampInterval(value)) }

    var drivingIntervalSeconds: Int
        get() = prefs.getInt(KEY_DRIVING_INTERVAL, DEFAULT_DRIVING_INTERVAL_SECONDS)
        set(value) = prefs.edit { putInt(KEY_DRIVING_INTERVAL, clampInterval(value)) }

    /** Applies the operator's settings from a heartbeat response.
     *
     * The policy is the single source of truth while remote control is armed,
     * so these are written straight into local settings — the phone's Settings
     * screen then shows the same numbers the web UI does, rather than the two
     * quietly disagreeing about what the device is doing. */
    fun applyRemoteAdaptiveSettings(enabled: Boolean, stationary: Int, walking: Int, driving: Int) {
        adaptiveScanEnabled = enabled
        stationaryIntervalSeconds = stationary
        walkingIntervalSeconds = walking
        drivingIntervalSeconds = driving
    }

    companion object {
        private val HIDDEN_LOCK = Any()
        // One per process: every SettingsRepository instance shares it.
        @Volatile private var hiddenApFlow: kotlinx.coroutines.flow.MutableStateFlow<Set<String>>? = null
        const val DEFAULT_OUTBOX_QUOTA_MB = 500
        const val MIN_OUTBOX_QUOTA_MB = 5
        const val MAX_OUTBOX_QUOTA_MB = 10_000

        const val DEFAULT_STATIONARY_INTERVAL_SECONDS = 600
        const val DEFAULT_WALKING_INTERVAL_SECONDS = 60
        const val DEFAULT_DRIVING_INTERVAL_SECONDS = 30

        /** Mirrors the backend's MinValueValidator(15) in sensors/models.py.
         * Note 30s is the real floor whenever WiFi is included — Android
         * allows 4 WiFi scans per 2 minutes — which the backend enforces in
         * SensorScanPolicyUpdateSerializer and the scan loop absorbs via
         * ScanThrottleController. */
        const val MIN_INTERVAL_SECONDS = 15
        const val MAX_INTERVAL_SECONDS = 24 * 60 * 60

        private fun clampInterval(value: Int) = value.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

        const val PREFS_NAME = "whyfi_settings"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_SENSOR_TOKEN = "sensor_token"
        private const val KEY_THEME = "theme_preference"
        private const val KEY_POSITION_ESTIMATOR = "position_estimator"
        private const val KEY_REMOTE_CONTROL = "remote_control_enabled"
        private const val KEY_MONITOR_TEST_PASSED = "monitor_test_passed"
        private const val KEY_WORKSPACE = "workspace"
        private const val KEY_MONITOR_CHANNELS = "monitor_channels"
        private const val KEY_MONITOR_FAVORITES = "monitor_favorite_macs"
        private const val KEY_OUTBOX_QUOTA_MB = "outbox_quota_mb"
        private const val KEY_ADAPTIVE_SCAN = "adaptive_scan_enabled"
        private const val KEY_STATIONARY_INTERVAL = "stationary_interval_seconds"
        private const val KEY_WALKING_INTERVAL = "walking_interval_seconds"
        private const val KEY_DRIVING_INTERVAL = "driving_interval_seconds"
    }
}
