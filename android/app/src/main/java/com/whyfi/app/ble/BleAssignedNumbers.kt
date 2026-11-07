package com.whyfi.app.ble

import com.whyfi.app.data.remote.BleObservationDto
import java.io.InputStream
import java.util.Locale

/**
 * Bluetooth SIG assigned numbers bundled as `assets/ble_assigned.tsv`
 * (company identifiers, member service UUIDs, standard service UUIDs; see
 * NOTICE), for naming who made a BLE advertiser. Pure, JVM-testable.
 */
class BleAssignedNumbers(
    private val companies: Map<Int, String>,
    private val members: Map<Int, String>,
    private val services: Map<Int, String>,
) {
    fun company(id: Int): String? = companies[id]
    fun memberService(uuid16: Int): String? = members[uuid16]
    fun service(uuid16: Int): String? = services[uuid16]

    companion object {
        val EMPTY = BleAssignedNumbers(emptyMap(), emptyMap(), emptyMap())

        /** Lines are `kind<TAB>hex<TAB>name` with kind c/m/s; `#` comments. */
        fun read(input: InputStream): BleAssignedNumbers {
            val c = HashMap<Int, String>(); val m = HashMap<Int, String>(); val s = HashMap<Int, String>()
            input.bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith("#") }.forEach { line ->
                    val parts = line.split('\t', limit = 3)
                    if (parts.size < 3) return@forEach
                    val id = parts[1].toIntOrNull(16) ?: return@forEach
                    when (parts[0]) { "c" -> c[id] = parts[2]; "m" -> m[id] = parts[2]; "s" -> s[id] = parts[2] }
                }
            }
            return BleAssignedNumbers(c, m, s)
        }

        /** The 16-bit part of a Bluetooth base UUID (`0000xxxx-0000-1000-8000-00805f9b34fb`), or null. */
        fun uuid16(uuid: String): Int? {
            val u = uuid.trim().lowercase(Locale.ROOT)
            if (u.length == 4) return u.toIntOrNull(16)
            if (u.length == 36 && u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) return u.substring(4, 8).toIntOrNull(16)
            return null
        }
    }
}

/** What can be said about one BLE advertiser beyond its name and RSSI. */
object BleDeviceInfo {
    data class Info(
        val vendor: String,
        /** Where [vendor] came from: manufacturer data, service UUID or MAC prefix. */
        val vendorSource: String,
        val services: List<String>,
        val addressType: String,
        val connectable: Boolean,
        val txPower: Int?,
    )

    /** Extra detail-table columns, in this order. */
    val HEADERS = listOf("Vendor", "Services", "Address", "Connectable", "TX power")

    fun columns(info: Info): List<String> = listOf(
        info.vendor, info.services.joinToString(", ").ifBlank { "–" }, info.addressType,
        if (info.connectable) "yes" else "no", info.txPower?.let { "$it dBm" } ?: "–",
    )

    /**
     * The MAC's two top bits tell the BLE address kind. Android doesn't say
     * whether an address is public or random, so 00 stays ambiguous.
     */
    fun addressType(mac: String): String {
        val first = mac.take(2).toIntOrNull(16) ?: return "unknown"
        return when (first shr 6) {
            0b11 -> "random static"
            0b01 -> "rotating (private)"
            0b00 -> "public or non-resolvable"
            else -> "reserved"
        }
    }

    fun describe(dto: BleObservationDto, numbers: BleAssignedNumbers, ouiVendor: (String) -> String?): Info {
        val companyIds = dto.manufacturerRecords.mapNotNull { it.substringBefore(':').toIntOrNull(16) }
            .ifEmpty { listOfNotNull(dto.manufacturerData.substringBefore(':').takeIf { ':' in dto.manufacturerData }?.toIntOrNull(16)) }
        val uuids = (dto.serviceUuids + dto.serviceData.map { it.substringBefore(':') }).distinct()
        val uuid16s = uuids.mapNotNull { BleAssignedNumbers.uuid16(it) }.distinct()
        val company = companyIds.firstNotNullOfOrNull { numbers.company(it) }
        val member = uuid16s.firstNotNullOfOrNull { numbers.memberService(it) }
        val first = dto.bleMac.take(2).toIntOrNull(16) ?: 0xFF
        // Only a public address carries a real OUI: top bits 00, universally administered.
        val oui = if (first and 0xC2 == 0) ouiVendor(dto.bleMac) else null
        val (vendor, source) = when {
            company != null -> company to "manufacturer data"
            member != null -> member to "service UUID"
            oui != null -> "$oui?" to "MAC prefix (if public address)"
            companyIds.isNotEmpty() -> String.format(Locale.ROOT, "company 0x%04X", companyIds.first()) to "manufacturer data"
            else -> "–" to ""
        }
        val services = uuids.map { uuid ->
            val id = BleAssignedNumbers.uuid16(uuid)
            when {
                id == null -> uuid.take(8) + "…"
                numbers.service(id) != null -> numbers.service(id)!!
                numbers.memberService(id) != null -> "${numbers.memberService(id)} service"
                else -> String.format(Locale.ROOT, "0x%04X", id)
            }
        }
        return Info(vendor, source, services, addressType(dto.bleMac), dto.isConnectable, dto.txPower)
    }
}
