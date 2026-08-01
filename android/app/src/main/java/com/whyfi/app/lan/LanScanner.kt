package com.whyfi.app.lan

import android.content.Context
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
 * ARP-table MAC lookup is attempted (`/proc/net/arp`) but Android has
 * restricted this since API 23+ for apps without root — degrades to a
 * blank MAC/vendor rather than failing the whole scan when unavailable.
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
            val hosts = (resolveSubnet() as? SubnetResolution.Hosts)?.addresses
                ?: return@withContext emptyList()
            val arpTable = readArpTable()
            var checked = 0

            val results = mutableListOf<LanObservationDto>()
            hosts.chunked(hostConcurrency).forEach { chunk ->
                val deferred = chunk.map { host ->
                    async {
                        val ipAddress: String = host.hostAddress ?: return@async null
                        val (openPorts, responseTimeMs) = probeHost(host)
                        val alive = openPorts.isNotEmpty() || pingReachable(host)
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
                            deviceTypeGuess = guessDeviceType(openPorts, banner),
                        )
                    }
                }
                val found = deferred.awaitAll().filterNotNull()
                found.forEach(onDeviceFound)
                results += found
            }
            results
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

    /** Current WiFi subnet's usable host addresses, from the active
     * network's link properties (its own IP + prefix length) — refuses
     * anything wider than a /24-ish range to keep the sweep bounded. */
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
                    )
                }
        }
    }

    /** Why a sweep can't run right now, or null if it can. */
    fun unavailableReason(): String? = (resolveSubnet() as? SubnetResolution.Unavailable)?.reason

    data class NetworkCandidate(
        val interfaceName: String,
        val address: String,
        val prefixLength: Int,
        val kind: String,
        /** Null when this is a network a sweep could actually use. */
        val skipReason: String?,
    ) {
        val usable: Boolean get() = skipReason == null
        fun describe(): String {
            val head = "$interfaceName  $address/$prefixLength  ($kind)"
            return if (skipReason == null) "$head — usable" else "$head — $skipReason"
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
            if (prefixLength < 16) return "/$prefixLength is far too large to sweep host by host"
            val hosts = (1 shl (32 - prefixLength)) - 2
            if (hosts > MAX_HOSTS_TO_SCAN) {
                return "$hosts addresses exceeds the $MAX_HOSTS_TO_SCAN-address limit"
            }
            return null
        }
    }

    private sealed interface SubnetResolution {
        data class Hosts(val addresses: List<InetAddress>) : SubnetResolution
        data class Unavailable(val reason: String) : SubnetResolution
    }

    private fun resolveSubnet(): SubnetResolution {
        val candidates = inspectNetworks()
        // WiFi first, then wired, then anything else usable — a phone with
        // both WiFi and USB tethering up should sweep the WiFi it's on.
        val chosen = candidates.filter { it.usable }
            .minByOrNull { KIND_PREFERENCE.indexOf(it.kind).takeIf { i -> i >= 0 } ?: KIND_PREFERENCE.size }
            ?: return SubnetResolution.Unavailable(explainNoCandidate(candidates))

        val prefixLength = chosen.prefixLength
        val hostBits = 32 - prefixLength
        val hostCount = (1 shl hostBits) - 2 // exclude network + broadcast addresses

        val addressBytes = (InetAddress.getByName(chosen.address) as Inet4Address).address

        val baseInt = ((addressBytes[0].toInt() and 0xFF) shl 24) or
            ((addressBytes[1].toInt() and 0xFF) shl 16) or
            ((addressBytes[2].toInt() and 0xFF) shl 8) or
            (addressBytes[3].toInt() and 0xFF)
        val networkInt = baseInt and (-1 shl hostBits)

        return SubnetResolution.Hosts(
            (1..hostCount).mapNotNull { offset ->
            val hostInt = networkInt + offset
            val bytes = byteArrayOf(
                ((hostInt shr 24) and 0xFF).toByte(),
                ((hostInt shr 16) and 0xFF).toByte(),
                ((hostInt shr 8) and 0xFF).toByte(),
                (hostInt and 0xFF).toByte(),
            )
                runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
            },
        )
    }

    private fun readArpTable(): Map<String, String> = runCatching {
        File("/proc/net/arp").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") parts[0] to parts[3] else null
            }.toMap()
        }
    }.getOrDefault(emptyMap())
}
