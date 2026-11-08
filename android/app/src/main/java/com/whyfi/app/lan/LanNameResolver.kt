package com.whyfi.app.lan

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Names for LAN hosts that reverse DNS doesn't know, which on a home
 * network is most of them. After a sweep, every answering host without a
 * name gets two unicast UDP questions:
 *
 * - mDNS (port 5353): a PTR query for its reverse name, answered by Apple
 *   devices, Linux (Avahi), printers, Chromecasts… → "macbook.local"
 * - NetBIOS node status (port 137): answered by Windows, Samba and most
 *   NAS boxes → "DESKTOP-1234", plus the host's MAC address, which the
 *   reply carries after its name table. That is the only MAC an app can
 *   get on Android 10+, where the ARP table is closed to apps. Samba often
 *   sends zeros there; those are dropped.
 *
 * Packet building and parsing are pure and unit-tested (LanNameResolverTest).
 */
object LanNameResolver {
    private const val MDNS_PORT = 5353
    private const val NETBIOS_PORT = 137
    private const val TYPE_PTR = 12
    private const val TYPE_NBSTAT = 0x21

    data class Answer(val name: String? = null, val mac: String? = null)

    /** Resolves what it can within [timeoutMs]; hosts without an answer are absent. */
    fun resolve(hosts: List<String>, timeoutMs: Long = 2_000): Map<String, Answer> {
        if (hosts.isEmpty()) return emptyMap()
        val names = HashMap<String, String>()
        val macs = HashMap<String, String>()
        val netbiosDone = HashSet<String>()
        runCatching {
            DatagramSocket().use { socket ->
                hosts.forEachIndexed { i, ip ->
                    val address = InetAddress.getByName(ip)
                    runCatching {
                        val mdns = mdnsQuery(ip)
                        socket.send(DatagramPacket(mdns, mdns.size, InetSocketAddress(address, MDNS_PORT)))
                    }
                    runCatching {
                        val nb = netbiosQuery(i and 0xFFFF)
                        socket.send(DatagramPacket(nb, nb.size, InetSocketAddress(address, NETBIOS_PORT)))
                    }
                }
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                val buffer = ByteArray(2048)
                while (System.nanoTime() < deadline && (names.size < hosts.size || netbiosDone.size < hosts.size)) {
                    socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                    val ip = packet.address?.hostAddress ?: continue
                    val data = packet.data.copyOf(packet.length)
                    if (packet.port == NETBIOS_PORT) {
                        netbiosDone += ip
                        parseNetbiosMac(data)?.let { macs[ip] = it }
                    }
                    val name = when (packet.port) {
                        MDNS_PORT -> parseMdnsPtr(data)
                        NETBIOS_PORT -> parseNetbiosName(data)
                        else -> null
                    }
                    // mDNS wins over NetBIOS: it carries the user-facing name.
                    if (name != null && (packet.port == MDNS_PORT || ip !in names)) names[ip] = name
                }
            }
        }
        return (names.keys + macs.keys).associateWith { Answer(names[it], macs[it]) }
    }

    fun reverseName(ip: String): String = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

    /** Standard query, one PTR question for [ip]'s reverse name. */
    fun mdnsQuery(ip: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        reverseName(ip).split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** NBSTAT query for the wildcard name "*". */
    fun netbiosQuery(id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(0x20)
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        name.forEach { b ->
            val v = b.toInt() and 0xFF
            out.write('A'.code + (v shr 4))
            out.write('A'.code + (v and 0x0F))
        }
        out.write(0)
        out.write(byteArrayOf(0, TYPE_NBSTAT.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** The first PTR answer's target name, without a trailing dot. */
    fun parseMdnsPtr(packet: ByteArray): String? = runCatching {
        if (packet.size < 12) return null
        val questions = u16(packet, 4)
        val answers = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
        var pos = 12
        repeat(questions) { pos = skipName(packet, pos) + 4 }
        repeat(answers) {
            pos = skipName(packet, pos)
            val type = u16(packet, pos)
            val length = u16(packet, pos + 8)
            val rdata = pos + 10
            if (type == TYPE_PTR) return readName(packet, rdata).trimEnd('.').takeIf { it.isNotBlank() }
            pos = rdata + length
        }
        null
    }.getOrNull()

    /** The unique workstation name (suffix 0x00, not a group) from a node status reply. */
    fun parseNetbiosName(packet: ByteArray): String? = runCatching {
        if (packet.size < 57) return null
        var pos = skipName(packet, 12)
        if (u16(packet, pos) != TYPE_NBSTAT) return null
        pos += 10
        val count = packet[pos].toInt() and 0xFF
        pos += 1
        val entries = (0 until count).map { i ->
            val start = pos + i * 18
            val name = String(packet, start, 15, Charsets.US_ASCII).trim()
            val suffix = packet[start + 15].toInt() and 0xFF
            val group = (packet[start + 16].toInt() and 0x80) != 0
            Triple(name, suffix, group)
        }
        (entries.firstOrNull { it.second == 0 && !it.third } ?: entries.firstOrNull { !it.third })
            ?.first?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The MAC ("unit ID") after a node status reply's name table, or null
     * when absent or all zeros. */
    fun parseNetbiosMac(packet: ByteArray): String? = runCatching {
        var pos = skipName(packet, 12)
        if (u16(packet, pos) != TYPE_NBSTAT) return null
        pos += 10
        val count = packet[pos].toInt() and 0xFF
        val start = pos + 1 + count * 18
        if (packet.size < start + 6) return null
        val bytes = packet.copyOfRange(start, start + 6)
        if (bytes.all { it.toInt() == 0 }) return null
        bytes.joinToString(":") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }.getOrNull()

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun skipName(b: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            val len = b[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2
                else -> pos += len + 1
            }
        }
    }

    /** Reads a DNS name, following compression pointers (bounded). */
    private fun readName(b: ByteArray, start: Int): String {
        val labels = mutableListOf<String>()
        var pos = start
        var jumps = 0
        while (jumps < 20) {
            val len = b[pos].toInt() and 0xFF
            when {
                len == 0 -> break
                len and 0xC0 == 0xC0 -> { pos = ((len and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF); jumps++ }
                else -> { labels += String(b, pos + 1, len, Charsets.UTF_8); pos += len + 1 }
            }
        }
        return labels.joinToString(".")
    }
}
