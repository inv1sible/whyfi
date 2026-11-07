package com.whyfi.app.ble

import java.util.Locale

/**
 * Wire format for the complete BLE advertisement, read by `parse_advert` in
 * `backend/scans/ble_signatures.py` (the backend does all signature
 * matching). Pure Kotlin, no Android types, so it's unit-testable.
 *
 * - manufacturer record: `"cccc:hex"`, company id as 4 hex digits, data
 *   without the two company-id bytes (as `ScanRecord` already gives it)
 * - service data: `"<uuid lowercase>:hex"`
 */
object BleAdvertFormat {
    /** The backend's legacy `manufacturer_data` limit on APKs older than this one. */
    const val LEGACY_MAX_LENGTH = 255

    fun manufacturerRecords(records: List<Pair<Int, ByteArray>>): List<String> =
        records.map { (companyId, data) -> String.format(Locale.ROOT, "%04x", companyId) + ":" + hex(data) }

    fun serviceData(entries: List<Pair<String, ByteArray>>): List<String> =
        entries.map { (uuid, data) -> uuid.lowercase(Locale.ROOT) + ":" + hex(data) }

    /**
     * The first record, which is all older backends read. Blank when it's too
     * long for them: an over-long value would reject the whole scan session,
     * and the record still reaches newer backends via `manufacturer_records`.
     */
    fun legacyManufacturerData(records: List<String>): String =
        records.firstOrNull()?.takeIf { it.length <= LEGACY_MAX_LENGTH } ?: ""

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(digits[v shr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }
}
