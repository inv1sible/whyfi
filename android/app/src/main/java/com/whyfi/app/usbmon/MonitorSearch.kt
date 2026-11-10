package com.whyfi.app.usbmon

import java.util.Locale

/**
 * 🔍 search in whyfi USB (user, 2026-11-10: "search literally everything and
 * filter on that"). Every word of the query must appear somewhere on the
 * device: MAC, vendor, kind, names, SSID, probed networks, associated APs
 * (BSSID and SSID), WPS, security, channels, catalog reasons and status.
 * Case-insensitive substring match; MACs also match without separators.
 */
object MonitorSearch {
    fun terms(query: String): List<String> =
        query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }

    /** All searchable text of one device, lower case. */
    fun haystack(
        device: SniffedDevices.Device,
        guess: SniffedDevices.Guess,
        apSsids: Map<String, String> = emptyMap(),
        status: String? = null,
        favorite: Boolean = false,
        hidden: Boolean = false,
    ): String = buildList {
        add(device.mac); add(device.mac.replace(":", ""))
        add(SniffedDevices.displayName(device, guess))
        add(guess.kind.label); guess.vendor?.let(::add); addAll(guess.reasons)
        if (device.isAp) add("ap access point")
        if (device.isClient) add("client station")
        if (device.randomized) add("randomized")
        if (device.isClient && device.probeRequests > 0 && device.bssids.isEmpty()) add("unassociated probing")
        add(device.ssid); addAll(device.probed)
        if (device.wildcardProbe) add("wildcard")
        device.bssids.forEach { add(it); add(it.replace(":", "")); apSsids[it]?.let(::add) }
        add(device.wpsName); add(device.wpsManufacturer); add(device.wpsModel); add(device.wpsModelNumber)
        add(device.wpsSerial); add(device.wpsPrimaryType)
        if (device.wpsName.isNotBlank() || device.wpsManufacturer.isNotBlank() || device.wpsModel.isNotBlank()) add("wps")
        add(device.security); add(device.akms); add(device.pairwiseCiphers); add(device.groupCipher); add(device.pmf)
        device.channels.forEach { add("ch$it") }
        status?.let(::add)
        if (favorite) add("favorite ★")
        if (hidden) add("hidden")
    }.filter { it.isNotBlank() }.joinToString("\n").lowercase(Locale.ROOT)

    fun matches(haystack: String, terms: List<String>): Boolean =
        terms.all { term -> term in haystack || term.replace(":", "").replace("-", "").let { it.length >= 4 && it in haystack } }

    /**
     * MACs to keep for [query], or null when the query is empty. An AP whose
     * client matches is kept too, so the client stays findable on its card.
     */
    fun keep(
        rows: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>,
        query: String,
        status: Map<String, String> = emptyMap(),
        favorites: Set<String> = emptySet(),
        isHidden: (SniffedDevices.Device) -> Boolean = { false },
    ): Set<String>? {
        val terms = terms(query)
        if (terms.isEmpty()) return null
        val apSsids = rows.filter { it.first.isAp && it.first.ssid.isNotBlank() }.associate { it.first.mac to it.first.ssid }
        val hits = rows.filter { (d, g) ->
            matches(haystack(d, g, apSsids, status[d.mac], d.mac in favorites, isHidden(d)), terms)
        }
        return (hits.map { it.first.mac } + hits.flatMap { if (it.first.isClient) it.first.bssids else emptyList() }).toSet()
    }
}
