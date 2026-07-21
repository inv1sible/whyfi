package com.whyfi.app.ble

import android.bluetooth.le.ScanResult
import java.util.UUID

/**
 * Best-effort device-type classification from BLE advertisement data. This
 * is a minimal placeholder matcher (a handful of well-known public
 * identifiers), not a complete signature database — see docs/roadmap.md:
 * a real implementation should seed this from the open-source AirGuard
 * project's public signature set and ship it as a bundled, versioned JSON
 * asset instead of hardcoded constants.
 */
object BleSignatureMatcher {
    private const val COMPANY_ID_APPLE = 0x004C
    private const val COMPANY_ID_SAMSUNG = 0x0075
    private const val APPLE_FINDMY_TYPE_BYTE = 0x12

    private val TILE_SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")

    fun guessDeviceType(result: ScanResult): String {
        val record = result.scanRecord ?: return "UNKNOWN"

        val serviceUuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
        if (TILE_SERVICE_UUID in serviceUuids) return "TILE"

        val appleData = record.getManufacturerSpecificData(COMPANY_ID_APPLE)
        if (appleData != null && appleData.isNotEmpty() && (appleData[0].toInt() and 0xFF) == APPLE_FINDMY_TYPE_BYTE) {
            return "AIRTAG"
        }
        if (appleData != null) return "HEADPHONES" // best-effort: most other Apple mfr-data adverts are AirPods-family

        if (record.getManufacturerSpecificData(COMPANY_ID_SAMSUNG) != null) return "SMARTTAG"

        return "UNKNOWN"
    }
}
