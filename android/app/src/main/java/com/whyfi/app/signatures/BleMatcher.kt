package com.whyfi.app.signatures

import java.util.regex.Pattern

/** Port of `scans/ble_signatures.py` matching (see SignatureCatalog). */
object BleMatcher {
    /** Tesla phone-key adverts use Apple's iBeacon layout so iOS can find the car. */
    const val TESLA_IBEACON_MFG_PREFIX = "021574278BDAB64445208F0C720EAF059935"
    const val APPLE_COMPANY_ID = 0x004C
    private val APPLE_CONTINUITY_PREFIXES = setOf("05", "07", "08", "09", "0A", "0B", "0C", "0D", "0E", "0F", "10")
    private val ADDRESS_KINDS = setOf("OUI", "MAC_PREFIX")
    private val WIFI_ONLY_KINDS = setOf("VENDOR_IE_OUI", "HIDDEN_SSID")
    private val COMPANY = Regex("[0-9A-Fa-f]{1,4}")
    private val FIND_MY_ACCESSORY_ALIASES = Sig.uuidAliases("FD44")

    data class Advert(
        val mac: String,
        val name: String,
        val serviceUuids: List<String>,
        /** (company id, data hex without the two company-id bytes). */
        val manufacturerRecords: List<Pair<Int, String>>,
        /** (uuid as sent, data hex). */
        val serviceData: List<Pair<String, String>>,
    )

    private fun parseManufacturer(entries: List<String>): List<Pair<Int, String>> = entries.mapNotNull { entry ->
        val (head, tail) = Sig.splitEntry(entry) ?: return@mapNotNull null
        if (!COMPANY.matches(head)) return@mapNotNull null
        Sig.dataHex(tail)?.let { head.toInt(16) to it }
    }

    /** From the wire format: records `"cccc:HEX"`, service data `"uuid:HEX"`;
     * [legacyManufacturerData] only when there are no records. */
    fun parseAdvert(
        mac: String,
        name: String,
        serviceUuids: List<String>,
        manufacturerRecords: List<String>,
        serviceData: List<String>,
        legacyManufacturerData: String = "",
    ): Advert {
        var records = parseManufacturer(manufacturerRecords)
        if (records.isEmpty() && legacyManufacturerData.isNotEmpty()) records = parseManufacturer(listOf(legacyManufacturerData))
        val svcData = serviceData.mapNotNull { entry ->
            val (head, tail) = Sig.splitEntry(entry) ?: return@mapNotNull null
            if (Sig.hexOnly(head).isEmpty()) return@mapNotNull null
            Sig.dataHex(tail)?.let { head to it }
        }
        val uuids = serviceUuids.map { it.trim() }.filter { Sig.hexOnly(it).isNotEmpty() }
        return Advert(mac, name, uuids, records, svcData)
    }

    internal class View(val advert: Advert) {
        val macHex = Sig.hexOnly(advert.mac)
        val nameBlank = advert.name.isBlank()
        val nameLower = Sig.lower(advert.name)
        val uuidAliases: Set<String> = buildSet {
            advert.serviceUuids.forEach { addAll(Sig.uuidAliases(it)) }
            advert.serviceData.forEach { addAll(Sig.uuidAliases(it.first)) }
        }
    }

    private fun reverseHexBytes(hex: String): String {
        if (hex.length < 2 || hex.length % 2 != 0) return ""
        return (hex.length - 2 downTo 0 step 2).joinToString("") { hex.substring(it, it + 2) }
    }

    private fun serviceDataHits(view: View, aliases: Set<String>, prefix: String, contains: Boolean): Boolean {
        val rev = if (contains) reverseHexBytes(prefix) else ""
        val needles = if (rev.isEmpty() || rev == prefix) listOf(prefix) else listOf(prefix, rev)
        for ((uuid, data) in view.advert.serviceData) {
            if (aliases.isNotEmpty() && Sig.uuidAliases(uuid).none { it in aliases }) continue
            if (contains) {
                if (needles.any { it in data }) return true
            } else if (data.startsWith(prefix)) return true
        }
        return false
    }

    private fun isRadio(radio: String?) = radio == null || radio == "BLE"

    /** A predicate for one rule against a BLE advert, or null when it can never hit BLE. */
    internal fun compileRule(rule: SignatureCatalog.Rule): ((View) -> Boolean)? {
        val kind = rule.kind
        if (kind in WIFI_ONLY_KINDS) return null
        if (kind == "RADIO_KIND") return if (isRadio(rule.radio)) { _ -> true } else null
        if (!isRadio(rule.radio)) return null
        return when (kind) {
            in ADDRESS_KINDS -> {
                val want = Sig.hexOnly(rule.text)
                if (want.isEmpty()) null else { v -> v.macHex.startsWith(want) }
            }
            "NAME_CONTAINS" -> {
                if (rule.text.isBlank()) null else {
                    val needle = Sig.lower(rule.text)
                    val f: (View) -> Boolean = { v -> !v.nameBlank && needle in v.nameLower }
                    f
                }
            }
            "NAME_GLOB" -> {
                if (rule.text.isBlank()) null else {
                    val regex: Pattern = Sig.glob(rule.text)
                    val f: (View) -> Boolean = { v -> !v.nameBlank && regex.matcher(v.advert.name).matches() }
                    f
                }
            }
            "SERVICE_UUID" -> {
                if (rule.text.isBlank()) null else {
                    val want = Sig.uuidAliases(rule.text)
                    val f: (View) -> Boolean = { v -> want.any { it in v.uuidAliases } }
                    f
                }
            }
            "MANUFACTURER_ID" -> { v -> v.advert.manufacturerRecords.any { it.first == rule.companyId } }
            "MANUFACTURER_DATA" -> {
                val prefix = Sig.hexOnly(rule.dataPrefixHex)
                if (prefix.isEmpty()) null else {
                    val f: (View) -> Boolean = { v ->
                        v.advert.manufacturerRecords.any { (cid, data) -> (rule.companyId == 0 || cid == rule.companyId) && data.startsWith(prefix) }
                    }
                    f
                }
            }
            "SERVICE_DATA" -> {
                val prefix = Sig.hexOnly(rule.dataPrefixHex)
                val aliases = Sig.uuidAliases(rule.text).filter { it.isNotEmpty() }.toSet()
                if (prefix.isEmpty() && aliases.isEmpty()) null else {
                    val contains = rule.text.isBlank() && prefix.isNotEmpty()
                    val f: (View) -> Boolean = { v -> serviceDataHits(v, aliases, prefix, contains) }
                    f
                }
            }
            else -> null // Unknown rule kind (newer catalog): never hits.
        }
    }

    /** The catalog indexed for BLE: 6-hex address rules in a map. */
    internal class Compiled(catalog: SignatureCatalog) {
        val fleets = catalog.fleets
        val kindById = catalog.fleets.associate { it.id to it.kind }
        val oui6 = HashMap<String, MutableList<Pair<Int, Int>>>()
        val longPrefixes = ArrayList<Triple<String, Int, Int>>()
        val otherRules = ArrayList<List<Triple<Int, String, (View) -> Boolean>>>()
        val allRules = ArrayList<List<((View) -> Boolean)?>?>()

        init {
            fleets.forEachIndexed { idx, fleet ->
                val others = ArrayList<Triple<Int, String, (View) -> Boolean>>()
                fleet.rules.forEachIndexed { pos, rule ->
                    if (rule.kind in ADDRESS_KINDS) {
                        if (!isRadio(rule.radio)) return@forEachIndexed
                        val want = Sig.hexOnly(rule.text)
                        if (want.length == 6) oui6.getOrPut(want) { ArrayList() }.add(idx to pos)
                        else if (want.isNotEmpty()) longPrefixes.add(Triple(want, idx, pos))
                        return@forEachIndexed
                    }
                    compileRule(rule)?.let { others.add(Triple(pos, rule.kind, it)) }
                }
                otherRules.add(others)
                allRules.add(if (fleet.matchAny) null else fleet.rules.map { compileRule(it) })
            }
        }

        /** fleet index -> position of its earliest address rule that hits. */
        fun addressHits(macHex: String): Map<Int, Int> {
            val hits = HashMap<Int, Int>()
            if (macHex.isEmpty()) return hits
            oui6[macHex.take(6)]?.forEach { (idx, pos) -> if (pos < (hits[idx] ?: (pos + 1))) hits[idx] = pos }
            for ((want, idx, pos) in longPrefixes) {
                if (macHex.startsWith(want) && pos < (hits[idx] ?: (pos + 1))) hits[idx] = pos
            }
            return hits
        }
    }

    private fun isTeslaPhoneKey(a: Advert) = a.manufacturerRecords.any { (cid, data) -> cid == APPLE_COMPANY_ID && data.startsWith(TESLA_IBEACON_MFG_PREFIX) }
    private fun isAppleContinuity(a: Advert) = a.manufacturerRecords.any { (cid, data) -> cid == APPLE_COMPANY_ID && data.take(2) in APPLE_CONTINUITY_PREFIXES }

    /** Fleets whose rules hit this advert, in catalog order. */
    fun match(advert: Advert, catalog: SignatureCatalog): List<SignatureMatch> {
        val compiled = catalog.ble
        val view = View(advert)
        val address = compiled.addressHits(view.macHex)
        val tesla = isTeslaPhoneKey(advert)

        val hits = LinkedHashMap<String, String>()
        compiled.fleets.forEachIndexed { idx, fleet ->
            if (fleet.clustered || fleet.rules.isEmpty()) return@forEachIndexed
            if (fleet.id == "fleet-ibeacon" && tesla) return@forEachIndexed
            if (!fleet.matchAny) {
                val predicates = compiled.allRules[idx]!!
                if (predicates.all { it != null && it(view) }) hits[fleet.id] = fleet.rules[0].kind
                return@forEachIndexed
            }
            val firstPos = address[idx]
            var ruleKind: String? = firstPos?.let { fleet.rules[it].kind }
            for ((pos, kind, predicate) in compiled.otherRules[idx]) {
                if (firstPos != null && pos > firstPos) break
                if (predicate(view)) {
                    ruleKind = kind
                    break
                }
            }
            if (ruleKind != null) hits[fleet.id] = ruleKind
        }

        // Post-filters, in SignatureEngine's order.
        if ("fleet-ibeacon" in hits && hits.keys.any { it != "fleet-ibeacon" && compiled.kindById[it] != "BEACON" }) hits.remove("fleet-ibeacon")
        if ("fleet-osmo" in hits || "fleet-dji-power" in hits) hits.remove("fleet-dji")
        if ("fleet-polar" in hits) hits.remove("fleet-gopro")
        if ("fleet-airtag" in hits && "airtag" !in view.nameLower && FIND_MY_ACCESSORY_ALIASES.none { it in view.uuidAliases }) {
            if ("fleet-apple-device" in hits || "fleet-apple-audio" in hits || isAppleContinuity(advert)) hits.remove("fleet-airtag")
        }
        return hits.map { (id, rule) -> SignatureMatch(id, rule) }
    }
}
