package com.whyfi.app.ble

import android.content.Context
import androidx.core.uwb.UwbManager

enum class ProximityTrend { GETTING_CLOSER, GETTING_FARTHER, STEADY, UNKNOWN }

/**
 * Two-tier "locate this device" helper for an optional BLEDevicesPage
 * locate action:
 *  1. Universal: BLE-RSSI-based "getting warmer/colder" proximity, works
 *     for any detected device regardless of vendor — always available.
 *  2. Enhanced: precise UWB distance+angle, but ONLY when both this phone
 *     has UWB hardware AND the target supports an Android-compatible
 *     ranging profile (e.g. Google Find My Device network partner
 *     trackers). Apple AirTags are explicitly NOT supported here — their
 *     U1 ranging protocol is proprietary and inaccessible to third-party
 *     Android apps. See MEMORY.md — don't imply otherwise in UI copy.
 */
class UwbLocateManager(private val context: Context) {

    /** Hardware capability check only. A real ranging session additionally
     * needs the target's session parameters exchanged out-of-band (see
     * kdoc on [isPeerRangingProfileSupported]) — this alone doesn't mean
     * ranging to a specific device will succeed. */
    suspend fun isUwbAvailableOnThisDevice(): Boolean = runCatching {
        UwbManager.createInstance(context).controleeSessionScope()
        true
    }.getOrDefault(false)

    /**
     * Whether a given detected device is even a candidate for precise
     * ranging. Real support requires a vendor-specific pairing SDK to
     * negotiate session parameters (session ID, channel, address) over the
     * device's own BLE GATT service before a UWB session can start — that
     * per-vendor handshake isn't implemented here. Until a specific
     * compatible tracker's SDK is integrated, this always returns false and
     * callers fall back to [proximityFromRssiTrend].
     */
    fun isPeerRangingProfileSupported(deviceTypeGuess: String): Boolean = false

    /** Always-available proximity signal: RSSI trending up/down over the
     * last few readings for one device. */
    fun proximityFromRssiTrend(rssiHistory: List<Int>): ProximityTrend {
        if (rssiHistory.size < 2) return ProximityTrend.UNKNOWN
        val delta = rssiHistory.last() - rssiHistory.first()
        return when {
            delta > 3 -> ProximityTrend.GETTING_CLOSER
            delta < -3 -> ProximityTrend.GETTING_FARTHER
            else -> ProximityTrend.STEADY
        }
    }
}
