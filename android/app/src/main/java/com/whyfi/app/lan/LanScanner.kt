package com.whyfi.app.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.whyfi.app.data.remote.LanObservationDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val maxHostsToScan = 512 // refuses to sweep pathologically large subnets (e.g. a /16)

    suspend fun scan(
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        // Fired as each alive host is confirmed (per completed chunk, not
        // per host — see hostConcurrency) so the UI can show devices
        // appearing live instead of only once the whole sweep finishes.
        onDeviceFound: (LanObservationDto) -> Unit = {},
    ): List<LanObservationDto> =
        withContext(Dispatchers.IO) {
            val hosts = currentSubnetHosts() ?: return@withContext emptyList()
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
    private fun currentSubnetHosts(): List<InetAddress>? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network = connectivityManager.activeNetwork ?: return null
        val linkProperties: LinkProperties = connectivityManager.getLinkProperties(network) ?: return null

        val linkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val prefixLength = linkAddress.prefixLength
        if (prefixLength < 24) return null

        val hostBits = 32 - prefixLength
        val hostCount = (1 shl hostBits) - 2 // exclude network + broadcast addresses
        if (hostCount <= 0 || hostCount > maxHostsToScan) return null

        val addressBytes = linkAddress.address.address
        val baseInt = ((addressBytes[0].toInt() and 0xFF) shl 24) or
            ((addressBytes[1].toInt() and 0xFF) shl 16) or
            ((addressBytes[2].toInt() and 0xFF) shl 8) or
            (addressBytes[3].toInt() and 0xFF)
        val networkInt = baseInt and (-1 shl hostBits)

        return (1..hostCount).mapNotNull { offset ->
            val hostInt = networkInt + offset
            val bytes = byteArrayOf(
                ((hostInt shr 24) and 0xFF).toByte(),
                ((hostInt shr 16) and 0xFF).toByte(),
                ((hostInt shr 8) and 0xFF).toByte(),
                (hostInt and 0xFF).toByte(),
            )
            runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
        }
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
