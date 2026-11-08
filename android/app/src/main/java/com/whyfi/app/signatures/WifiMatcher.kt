package com.whyfi.app.signatures

/** Port of `scans/wifi_signatures.py` matching (see SignatureCatalog). */
object WifiMatcher {
    /** WPA (Microsoft) and RSN (IEEE) vendor IE OUIs: protocol tags, not products. */
    private val PROTOCOL_IE_OUIS = setOf("0050F2", "000FAC")
    private val ADDRESS_KINDS = setOf("OUI", "MAC_PREFIX", "VENDOR_IE_OUI")
    private val BLE_PAYLOAD_KINDS = setOf("SERVICE_UUID", "SERVICE_DATA", "MANUFACTURER_ID", "MANUFACTURER_DATA")
    private val VIA_ORDER = listOf("bssid", "bssid_universal", "vendor_ie")
    const val MAX_VENDOR_IES = 32
    private val HEX6 = Regex("[0-9A-F]{6}")

    data class Advert(val bssidHex: String, val ssid: String, val vendorIeOuis: List<String>)

    /** The burned-in vendor OUI behind a virtual (locally administered, unicast) BSSID. */
    fun universalOui(bssid: String): String? {
        val hex = Sig.hexOnly(bssid)
        if (hex.length < 6) return null
        val first = hex.substring(0, 2).toIntOrNull(16) ?: return null
        if (first and 0x02 == 0 || first and 0x01 != 0) return null
        return String.format(java.util.Locale.ROOT, "%02X", first and 0xFD) + hex.substring(2, 6)
    }

    fun parse(bssid: String, ssid: String, vendorIeOuis: List<String>): Advert {
        val ouis = ArrayList<String>()
        for (entry in vendorIeOuis) {
            val hex = Sig.hexOnly(entry)
            if (HEX6.matches(hex) && hex !in ouis) ouis.add(hex)
        }
        return Advert(Sig.hexOnly(bssid), ssid.trim(), ouis.take(MAX_VENDOR_IES))
    }

    private fun vendorIes(a: Advert) = a.vendorIeOuis.filter { it.take(6) !in PROTOCOL_IE_OUIS }

    private fun addressVias(rule: SignatureCatalog.Rule, a: Advert, universal: String?): List<String> {
        val want = Sig.hexOnly(rule.text)
        if (want.isEmpty()) return emptyList()
        val vias = ArrayList<String>()
        if (rule.kind == "OUI" || rule.kind == "MAC_PREFIX") {
            if (a.bssidHex.startsWith(want)) vias.add("bssid")
            if (want.length == 6 && universal == want) vias.add("bssid_universal")
        }
        if ((rule.kind == "OUI" || rule.kind == "VENDOR_IE_OUI") && want.take(6) !in PROTOCOL_IE_OUIS) {
            if (vendorIes(a).any { it.startsWith(want) }) vias.add("vendor_ie")
        }
        return vias
    }

    private fun isRadio(radio: String?) = radio == null || radio == "WIFI"

    internal fun compileOther(rule: SignatureCatalog.Rule): ((Advert) -> Boolean)? {
        val kind = rule.kind
        if (kind == "RADIO_KIND") return if (isRadio(rule.radio)) { _ -> true } else null
        if (!isRadio(rule.radio) || kind in BLE_PAYLOAD_KINDS) return null
        return when (kind) {
            "HIDDEN_SSID" -> { a -> a.ssid.isEmpty() }
            "NAME_CONTAINS" -> if (rule.text.isBlank()) null else {
                val needle = Sig.lower(rule.text)
                val f: (Advert) -> Boolean = { a -> a.ssid.isNotEmpty() && needle in Sig.lower(a.ssid) }
                f
            }
            "NAME_GLOB" -> if (rule.text.isBlank()) null else {
                val regex = Sig.glob(rule.text)
                val f: (Advert) -> Boolean = { a -> a.ssid.isNotEmpty() && regex.matcher(a.ssid).matches() }
                f
            }
            else -> null
        }
    }

    internal class Compiled(catalog: SignatureCatalog) {
        val fleets = catalog.fleets
        val kindById = catalog.fleets.associate { it.id to it.kind }
        val byBssid6 = HashMap<String, MutableList<Pair<Int, Int>>>()
        val byUniversal6 = HashMap<String, MutableList<Pair<Int, Int>>>()
        val byIe6 = HashMap<String, MutableList<Pair<Int, Int>>>()
        val longRules = ArrayList<Triple<Int, Int, SignatureCatalog.Rule>>()
        val otherRules = ArrayList<List<Triple<Int, String, (Advert) -> Boolean>>>()

        init {
            fleets.forEachIndexed { idx, fleet ->
                val others = ArrayList<Triple<Int, String, (Advert) -> Boolean>>()
                fleet.rules.forEachIndexed { pos, rule ->
                    if (rule.kind in ADDRESS_KINDS) {
                        if (rule.radio == "BLE") return@forEachIndexed
                        val want = Sig.hexOnly(rule.text)
                        if (want.isEmpty()) return@forEachIndexed
                        if (want.length != 6) {
                            longRules.add(Triple(idx, pos, rule))
                            return@forEachIndexed
                        }
                        if (rule.kind == "OUI" || rule.kind == "MAC_PREFIX") {
                            byBssid6.getOrPut(want) { ArrayList() }.add(idx to pos)
                            byUniversal6.getOrPut(want) { ArrayList() }.add(idx to pos)
                        }
                        if ((rule.kind == "OUI" || rule.kind == "VENDOR_IE_OUI") && want !in PROTOCOL_IE_OUIS) {
                            byIe6.getOrPut(want) { ArrayList() }.add(idx to pos)
                        }
                        return@forEachIndexed
                    }
                    compileOther(rule)?.let { others.add(Triple(pos, rule.kind, it)) }
                }
                otherRules.add(others)
            }
        }

        /** fleet index -> (position, via) of its earliest address rule hit. */
        fun addressHits(a: Advert): Map<Int, Pair<Int, String>> {
            val found = HashMap<Int, HashMap<Int, MutableSet<String>>>()
            fun mark(entries: List<Pair<Int, Int>>?, via: String) {
                entries?.forEach { (idx, pos) -> found.getOrPut(idx) { HashMap() }.getOrPut(pos) { HashSet() }.add(via) }
            }
            val universal = universalOui(a.bssidHex)
            if (a.bssidHex.length >= 6) mark(byBssid6[a.bssidHex.take(6)], "bssid")
            if (universal != null) mark(byUniversal6[universal], "bssid_universal")
            vendorIes(a).forEach { mark(byIe6[it.take(6)], "vendor_ie") }
            for ((idx, pos, rule) in longRules) {
                addressVias(rule, a, universal).forEach { found.getOrPut(idx) { HashMap() }.getOrPut(pos) { HashSet() }.add(it) }
            }
            return found.mapValues { (_, byPos) ->
                val pos = byPos.keys.min()
                pos to VIA_ORDER.first { it in byPos.getValue(pos) }
            }
        }
    }

    /** For match-all fleets: the via for an address hit, "" for another hit, null for a miss. */
    private fun ruleHits(rule: SignatureCatalog.Rule, a: Advert, universal: String?): String? {
        if (rule.kind in ADDRESS_KINDS) {
            if (rule.radio == "BLE") return null
            val vias = addressVias(rule, a, universal)
            return VIA_ORDER.firstOrNull { it in vias }
        }
        val predicate = compileOther(rule) ?: return null
        return if (predicate(a)) "" else null
    }

    private fun evidence(fleet: SignatureCatalog.Fleet, pos: Int, via: String?) =
        SignatureMatch(fleet.id, fleet.rules[pos].kind, via?.ifEmpty { null })

    fun match(a: Advert, catalog: SignatureCatalog): List<SignatureMatch> {
        val compiled = catalog.wifi
        val address = compiled.addressHits(a)
        val universal = universalOui(a.bssidHex)

        val hits = LinkedHashMap<String, SignatureMatch>()
        compiled.fleets.forEachIndexed { idx, fleet ->
            if (fleet.clustered || fleet.rules.isEmpty()) return@forEachIndexed
            if (!fleet.matchAny) {
                val results = fleet.rules.map { ruleHits(it, a, universal) }
                if (results.all { it != null }) hits[fleet.id] = evidence(fleet, 0, results[0])
                return@forEachIndexed
            }
            val first = address[idx]
            var found: SignatureMatch? = first?.let { evidence(fleet, it.first, it.second) }
            for ((pos, kind, predicate) in compiled.otherRules[idx]) {
                if (first != null && pos > first.first) break
                if (predicate(a)) {
                    found = SignatureMatch(fleet.id, kind)
                    break
                }
            }
            if (found != null) hits[fleet.id] = found
        }

        if ("fleet-ibeacon" in hits && hits.keys.any { it != "fleet-ibeacon" && compiled.kindById[it] != "BEACON" }) hits.remove("fleet-ibeacon")
        if ("fleet-osmo" in hits || "fleet-dji-power" in hits) hits.remove("fleet-dji")
        if ("fleet-polar" in hits) hits.remove("fleet-gopro")
        // Meraki APs carry Cisco's vendor IE; the BSSID is Meraki.
        if ("fleet-meraki" in hits) hits.remove("fleet-cisco")
        return hits.values.toList()
    }
}
