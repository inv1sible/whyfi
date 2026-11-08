package com.whyfi.app.lan

import android.content.Context
import android.net.wifi.WifiManager
import com.whyfi.app.usbmon.OuiTable
import com.whyfi.app.data.remote.LanObservationDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Discovers devices on the phone's current WiFi subnet via a TCP-connect
 * sweep — Android apps can't use raw ICMP ping without root, so a short
 * connect attempt on a handful of common ports per host does double duty:
 * it's both the "is anyone home" probe and the port scan, in one pass.
 * Ports are probed in parallel per host (not just hosts in parallel) so the
 * wider port list below doesn't blow up scan time.
 *
 * A couple of ports get a best-effort banner grab (first line of an HTTP
 * response / the SSH banner a server sends unprompted) — enough to guess a
 * device type without needing a real fingerprinting database.
 *
 * ARP-table MAC lookup is attempted (`/proc/net/arp`), but Android 10+
 * closes it to apps (and `ip neigh` from Android 11 / targetSdk 30), so on
 * current phones it comes back empty. MACs then come only from NetBIOS
 * replies (LanNameResolver); the router's Wi-Fi radio is in [gatewayRadio].
 */
class LanScanner(private val context: Context) {

    private val portsToProbe = listOf(
        21, 22, 23, 25, 53, 80, 139, 443, 445, 554, 631, 3389,
        5000, 5353, 7000, 8000, 8008, 8009, 8080, 8443, 9100, 32400, 62078,
    )
    private val bannerPorts = setOf(22, 80, 8000, 8008, 8080)
    private val connectTimeoutMs = 400
    private val bannerReadTimeoutMs = 500
    private val hostConcurrency = 8 // ports are now probed in parallel per host too, so keep this modest


    /** Turns "no usable network" into something a person can act on, naming
     * what was actually found rather than just refusing. */
    private fun explainNoCandidate(candidates: List<NetworkCandidate>): String {
        if (candidates.none { it.kind != "Other" || it.skipReason != "loopback" } && candidates.isEmpty()) {
            return "This phone reports no IPv4 network interfaces at all."
        }
        val real = candidates.filterNot { it.skipReason == "loopback" }
        if (real.isEmpty()) return "This phone isn't connected to any network."

        val tunnels = real.filter { it.kind == "VPN/tunnel" }
        val mobile = real.filter { it.kind == "Mobile data" }
        return when {
            tunnels.isNotEmpty() && real.none { it.kind == "WiFi" || it.kind == "Wired" } ->
                "Only a VPN/tunnel interface (${tunnels.first().interfaceName}) has an IPv4 address, and there's " +
                    "no local network behind a tunnel. Disconnect the VPN, or connect to WiFi, to sweep a LAN."
            mobile.isNotEmpty() && real.none { it.kind == "WiFi" || it.kind == "Wired" } ->
                "This phone is on mobile data, not WiFi — there's no local network to sweep."
            else ->
                "No sweepable network found. Details: " + real.joinToString("; ") { it.describe() }
        }
    }

    suspend fun scan(
        target: LanTarget = LanTarget.Auto,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        // Fired as each alive host is confirmed (per completed chunk, not
        // per host — see hostConcurrency) so the UI can show devices
        // appearing live instead of only once the whole sweep finishes.
        onDeviceFound: (LanObservationDto) -> Unit = {},
    ): List<LanObservationDto> =
        withContext(Dispatchers.IO) {
            // An empty result must mean "swept the subnet, nobody answered" —
            // never "couldn't sweep". Callers check unavailableReason() first;
            // this is the belt-and-braces half of the same contract.
            val subnet = resolveSubnet(target) as? SubnetResolution.Hosts
                ?: return@withContext emptyList()
            val hosts = subnet.addresses
            val onvif = subnet.localAddress
                ?.let { OnvifDiscovery.discover(it, hosts.mapNotNull { h -> h.hostAddress }.toSet()) }
                .orEmpty()
            val arpTable = readArpTable()
            var checked = 0

            val results = mutableListOf<LanObservationDto>()
            hosts.chunked(hostConcurrency).forEach { chunk ->
                val deferred = chunk.map { host ->
                    async {
                        val ipAddress: String = host.hostAddress ?: return@async null
                        val (openPorts, responseTimeMs) = probeHost(host)
                        val onvifMatch = onvif[ipAddress]
                        val alive = onvifMatch != null || openPorts.isNotEmpty() || pingReachable(host)
                        checked++
                        onProgress(checked, hosts.size)
                        if (!alive) return@async null

                        val banner = bannerPorts.firstOrNull { it in openPorts }
                            ?.let { grabBanner(host, it) } ?: ""

                        LanObservationDto(
                            ipAddress = ipAddress,
                            macAddress = arpTable[ipAddress] ?: "",
                            hostname = resolveHostname(host),
                            openPorts = openPorts,
                            responseTimeMs = responseTimeMs,
                            banner = banner,
                            deviceTypeGuess = if (onvifMatch != null) "CAMERA" else guessDeviceType(openPorts, banner),
                            onvif = onvifMatch?.fields().orEmpty(),
                        )
                    }
                }
                val found = deferred.awaitAll().filterNotNull()
                found.forEach(onDeviceFound)
                results += found
            }
            // Ask hosts still missing a name or MAC (see LanNameResolver);
            // NetBIOS replies carry the MAC the ARP table no longer gives.
            val incomplete = results.filter { it.hostname.isBlank() || it.macAddress.isBlank() }.map { it.ipAddress }
            val answers = LanNameResolver.resolve(incomplete)
            val oui = ouiTable()
            results.map { device ->
                val answer = answers[device.ipAddress]
                val mac = device.macAddress.ifBlank { answer?.mac.orEmpty() }
                device.copy(
                    hostname = device.hostname.ifBlank { answer?.name.orEmpty() },
                    macAddress = mac,
                    vendorOui = device.vendorOui.ifBlank { mac.takeIf { it.isNotBlank() }?.let { oui?.vendorOf(it) }.orEmpty() },
                )
            }
        }

    /** Returns the open ports plus the fastest successful connect time
     * (a rough proximity/load indicator), probing all candidate ports for
     * this host concurrently rather than one at a time. */
    private suspend fun probeHost(host: InetAddress): Pair<List<Int>, Double?> = withContext(Dispatchers.IO) {
        val attempts = portsToProbe.map { port ->
            async {
                val startedAt = System.nanoTime()
                val open = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress(host, port), connectTimeoutMs) }
                    true
                }.getOrDefault(false)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                Triple(port, open, elapsedMs)
            }
        }.awaitAll()

        val successes = attempts.filter { it.second }
        val openPorts = successes.map { it.first }
        val fastestMs = successes.minOfOrNull { it.third }
        openPorts to fastestMs
    }

    private fun grabBanner(host: InetAddress, port: Int): String = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = bannerReadTimeoutMs
            if (port == 22) {
                // SSH servers send their identification string unprompted.
                socket.getInputStream().bufferedReader().readLine()?.take(120) ?: ""
            } else {
                socket.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: ${host.hostAddress}\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val statusLine = socket.getInputStream().bufferedReader().readLine() ?: ""
                statusLine.take(120)
            }
        }
    }.getOrDefault("")

    private fun guessDeviceType(openPorts: List<Int>, banner: String): String {
        val ports = openPorts.toSet()
        val bannerLower = banner.lowercase()
        return when {
            9100 in ports || 631 in ports -> "PRINTER"
            554 in ports -> "CAMERA"
            8009 in ports || 7000 in ports || 32400 in ports -> "MEDIA"
            445 in ports || 139 in ports -> "NAS"
            3389 in ports -> "WINDOWS_HOST"
            22 in ports -> "LINUX_HOST"
            bannerLower.contains("router") || bannerLower.contains("gateway") -> "ROUTER"
            openPorts.isNotEmpty() -> "IOT"
            else -> "UNKNOWN"
        }
    }

    private fun pingReachable(host: InetAddress): Boolean =
        runCatching { host.isReachable(connectTimeoutMs) }.getOrDefault(false)

    private fun resolveHostname(host: InetAddress): String {
        val canonical = host.canonicalHostName
        return if (canonical == host.hostAddress) "" else canonical
    }

    /**
     * Why a sweep can't run right now, or null if it can.
     *
     * Mirrors WifiScanManager/CellularManager/BleDeviceScanner's
     * unavailableReason(). Every one of the conditions below used to collapse
     * into an empty device list, which the UI then showed as "0 devices found"
     * — identical to a successful sweep of an empty network, and the reason a
     * phone on mobile data looked like a broken scanner.
     */
    /**
     * Every IPv4 interface the phone has, and whether a sweep could use it.
     *
     * Exposed so the LAN screen can show its working. The previous version
     * asked ConnectivityManager for the *active* network and took the first
     * IPv4 address on it — which, with a VPN up, is the tunnel's own /32.
     * It then reported "no other addresses on it" while the phone was plainly
     * sitting on a /24 with a router and a dozen hosts on it.
     */
    fun inspectNetworks(): List<NetworkCandidate> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())

        return interfaces.flatMap { nic ->
            val up = runCatching { nic.isUp }.getOrDefault(false)
            val loopback = runCatching { nic.isLoopback }.getOrDefault(false)
            nic.interfaceAddresses.orEmpty()
                .filter { it.address is Inet4Address }
                .map { addr ->
                    val kind = classify(nic.name)
                    NetworkCandidate(
                        interfaceName = nic.name,
                        address = addr.address.hostAddress ?: "?",
                        prefixLength = addr.networkPrefixLength.toInt(),
                        kind = kind,
                        skipReason = skipReason(
                            up = up,
                            loopback = loopback,
                            kind = kind,
                            address = addr.address as Inet4Address,
                            prefixLength = addr.networkPrefixLength.toInt(),
                        ),
                        truncated = isTruncated(addr.networkPrefixLength.toInt()),
                    )
                }
        }
    }

    private fun wifiOf(candidates: List<NetworkCandidate>) = candidates.firstOrNull { it.usable && it.kind == "WiFi" }

    /** The WiFi network a sweep would cover, as "192.168.1.0/24", or null
     * when the phone isn't on WiFi. */
    fun wifiSubnet(): String? = wifiOf(inspectNetworks())?.let { nic ->
        "${LanRange.format(nic.networkBounds().first)}/${nic.prefixLength}"
    }

    /** Why a sweep can't run right now, or null if it can. */
    fun unavailableReason(target: LanTarget = LanTarget.Auto): String? =
        (resolveSubnet(target) as? SubnetResolution.Unavailable)?.reason

    /** The addresses [target] covers, for the LAN screen's preview line
     * ("192.168.1.1 – 192.168.1.254 · 254 addresses"), or the reason it can't run. */
    fun describeTarget(target: LanTarget): String = when (val r = resolveSubnet(target)) {
        is SubnetResolution.Unavailable -> r.reason
        is SubnetResolution.Hosts -> r.range.describe()
    }

    data class NetworkCandidate(
        val interfaceName: String,
        val address: String,
        val prefixLength: Int,
        val kind: String,
        /** Null when this is a network a sweep could actually use. */
        val skipReason: String?,
        /** True when this network is usable but has more host addresses than
         * MAX_HOSTS_TO_SCAN — the sweep still runs, just over the first
         * MAX_HOSTS_TO_SCAN addresses rather than refusing outright. A /21
         * office network is a real LAN worth partial results from, not a
         * mistake to reject. */
        val truncated: Boolean = false,
    ) {
        val usable: Boolean get() = skipReason == null

        /** Network and broadcast address of this interface's subnet. */
        fun networkBounds(): Pair<Long, Long> {
            val addr = LanRange.ip(address) ?: 0L
            val hostBits = 32 - prefixLength.coerceIn(0, 32)
            val network = addr and ((0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL)
            return network to network + (1L shl hostBits) - 1
        }

        fun contains(value: Long): Boolean = networkBounds().let { (lo, hi) -> value in lo..hi }

        /** Addresses a sweep of this interface covers, for the picker. */
        val hostCount: Int get() = if (prefixLength >= 31) 0 else minOf((1 shl (32 - prefixLength)) - 2, MAX_HOSTS_TO_SCAN)
        fun describe(): String {
            val head = "$interfaceName  $address/$prefixLength  ($kind)"
            return when {
                skipReason != null -> "$head — $skipReason"
                truncated -> "$head — usable, but only the first $MAX_HOSTS_TO_SCAN of its addresses will be swept"
                else -> "$head — usable"
            }
        }
    }

    companion object {
        /** Refuses to sweep pathologically large subnets (e.g. a /16). */
        const val MAX_HOSTS_TO_SCAN = 512

        /** Which interface to sweep when several qualify. */
        val KIND_PREFERENCE = listOf("WiFi", "Wired", "Other")

        internal fun classify(name: String): String = when {
            name.startsWith("wlan") || name.startsWith("ap") -> "WiFi"
            name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") -> "Wired"
            name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                name.startsWith("ipsec") || name.startsWith("utun") || name.startsWith("wg") -> "VPN/tunnel"
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") ||
                name.startsWith("pdp") -> "Mobile data"
            else -> "Other"
        }

        internal fun skipReason(
            up: Boolean,
            loopback: Boolean,
            kind: String,
            address: Inet4Address,
            prefixLength: Int,
        ): String? {
            if (loopback) return "loopback"
            if (!up) return "interface is down"
            // A tunnel endpoint has no broadcast domain behind it, and sweeping
            // a carrier's subnet would be both useless and other people's
            // machines. Neither is "you are alone on this network".
            if (kind == "VPN/tunnel") return "a tunnel has no local network behind it"
            if (kind == "Mobile data") return "mobile data has no local network to sweep"
            if (address.isLinkLocalAddress) return "self-assigned address, not a real network"
            if (prefixLength >= 31) return "/$prefixLength is point-to-point — no other hosts"
            // No size-based refusal here: a subnet larger than
            // MAX_HOSTS_TO_SCAN is still swept, just capped (see
            // isTruncated/NetworkCandidate.truncated and resolveSubnet's
            // hostCount) — a big office network is a real LAN worth partial
            // results from, not a mistake to reject outright.
            return null
        }

        /** True when this prefix has more host addresses than
         * MAX_HOSTS_TO_SCAN — the network is still usable, just truncated. */
        internal fun isTruncated(prefixLength: Int): Boolean {
            if (prefixLength <= 0 || prefixLength >= 31) return false
            val hosts = (1 shl (32 - prefixLength)) - 2
            return hosts > MAX_HOSTS_TO_SCAN
        }
    }

    private sealed interface SubnetResolution {
        /** [localAddress] is where ONVIF discovery is sent from; null for a
         * custom range with no usable local interface (ONVIF is skipped). */
        data class Hosts(val range: LanRange.Ok, val localAddress: InetAddress?) : SubnetResolution {
            val addresses: List<InetAddress> by lazy {
                range.addresses().mapNotNull { runCatching { InetAddress.getByAddress(LanRange.toBytes(it)) }.getOrNull() }
            }
        }
        data class Unavailable(val reason: String) : SubnetResolution
    }

    private fun resolveSubnet(target: LanTarget): SubnetResolution {
        val candidates = inspectNetworks()
        // WiFi first, then wired, then anything else usable — a phone with
        // both WiFi and USB tethering up should sweep the WiFi it's on.
        val auto = candidates.filter { it.usable }
            .minByOrNull { KIND_PREFERENCE.indexOf(it.kind).takeIf { i -> i >= 0 } ?: KIND_PREFERENCE.size }
        val chosen = when (target) {
            LanTarget.Auto -> auto ?: return SubnetResolution.Unavailable(explainNoCandidate(candidates))
            LanTarget.Wifi -> wifiOf(candidates)
                ?: return SubnetResolution.Unavailable("Not connected to WiFi. Connect to WiFi, or enter a custom range.")
            is LanTarget.Interface -> {
                val nic = candidates.firstOrNull { it.interfaceName == target.name }
                    ?: return SubnetResolution.Unavailable("Interface ${target.name} is gone. Pick another one.")
                if (!nic.usable) return SubnetResolution.Unavailable("${nic.interfaceName} can't be swept: ${nic.skipReason}.")
                nic
            }
            is LanTarget.Custom -> {
                val range = when (val parsed = LanRange.parse(target.text)) {
                    is LanRange.Invalid -> return SubnetResolution.Unavailable(parsed.reason)
                    is LanRange.Ok -> parsed
                }
                // Send ONVIF probes from the interface whose subnet holds the
                // range, else from the automatic choice; routed ranges (e.g.
                // behind the router) are still swept by TCP connect.
                val local = candidates.filter { it.usable }.firstOrNull { it.contains(range.first) } ?: auto
                return SubnetResolution.Hosts(range, local?.let { InetAddress.getByName(it.address) })
            }
        }
        return SubnetResolution.Hosts(subnetRange(chosen), InetAddress.getByName(chosen.address))
    }

    /** The interface's subnet without network and broadcast addresses,
     * capped at MAX_HOSTS_TO_SCAN (see NetworkCandidate.truncated) — the
     * first N addresses of a big network are still worth sweeping. */
    private fun subnetRange(nic: NetworkCandidate): LanRange.Ok {
        val (network, _) = nic.networkBounds()
        val hostCount = minOf((1L shl (32 - nic.prefixLength)) - 2, MAX_HOSTS_TO_SCAN.toLong())
        return LanRange.Ok(network + 1, network + hostCount)
    }

    private var oui: OuiTable? = null

    /** assets/oui.tsv, read once on first use (off the main thread: sweeps run on IO). */
    private fun ouiTable(): OuiTable? = oui ?: runCatching {
        context.assets.open("oui.tsv").use { OuiTable.read(it) }
    }.getOrNull()?.also { oui = it }

    /** The router's IP and the BSSID of the access point the phone is on.
     * On a home router that's its Wi-Fi radio, not its LAN MAC (often one
     * digit apart), so it's shown as such and never uploaded as a LAN MAC. */
    data class GatewayRadio(val gatewayIp: String, val bssid: String, val vendor: String?)

    @Suppress("DEPRECATION")
    fun gatewayRadio(): GatewayRadio? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val bssid = wifi.connectionInfo?.bssid?.lowercase()?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } ?: return null
        val gw = wifi.dhcpInfo?.gateway?.takeIf { it != 0 } ?: return null
        // DhcpInfo stores the address little-endian.
        val ip = listOf(gw and 0xFF, (gw shr 8) and 0xFF, (gw shr 16) and 0xFF, (gw shr 24) and 0xFF).joinToString(".")
        GatewayRadio(ip, bssid, ouiTable()?.vendorOf(bssid))
    }.getOrNull()

    private fun readArpTable(): Map<String, String> = runCatching {
        File("/proc/net/arp").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") parts[0] to parts[3] else null
            }.toMap()
        }
    }.getOrDefault(emptyMap())
}
