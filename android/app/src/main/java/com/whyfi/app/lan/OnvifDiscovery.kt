package com.whyfi.app.lan

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** A bounded WS-Discovery Probe on the LAN interface used by the TCP sweep. */
internal object OnvifDiscovery {
    private const val DISCOVERY = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    private const val ADDRESSING = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
    private const val MAX_RESPONSE = 8192

    data class Match(val endpoint: String, val types: String, val scopes: String, val xaddrs: String) {
        fun fields(): Map<String, String> = mapOf(
            "endpoint" to endpoint, "types" to types, "scopes" to scopes, "xaddrs" to xaddrs,
            "name" to name,
        )
        val name: String get() = scopes.split(' ').firstOrNull { it.startsWith("onvif://www.onvif.org/name/") }
            ?.substringAfterLast('/')?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
    }

    fun discover(localAddress: InetAddress, allowedHosts: Set<String>): Map<String, Match> = runCatching {
        val id = "uuid:${UUID.randomUUID()}"
        val xml = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="$ADDRESSING" xmlns:d="$DISCOVERY" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Header><a:Action>$DISCOVERY/Probe</a:Action><a:MessageID>$id</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To></s:Header><s:Body><d:Probe><d:Types>tds:Device</d:Types></d:Probe></s:Body></s:Envelope>"""
        val matches = mutableMapOf<String, Match>()
        DatagramSocket(InetSocketAddress(localAddress, 0)).use { socket ->
            val request = xml.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 3702))
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1).toInt()
                val response = DatagramPacket(ByteArray(MAX_RESPONSE), MAX_RESPONSE)
                try { socket.receive(response) } catch (_: java.net.SocketTimeoutException) { break }
                val ip = response.address.hostAddress ?: continue
                if (ip !in allowedHosts) continue
                parseResponse(response.data, response.length, id)?.let { matches[ip] = it }
            }
        }
        matches
    }.getOrDefault(emptyMap())

    fun parseResponse(bytes: ByteArray, length: Int, messageId: String): Match? = runCatching {
        if (length <= 0 || length > MAX_RESPONSE) return@runCatching null
        val body = String(bytes, 0, length, Charsets.UTF_8)
        if (body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<!ENTITY", ignoreCase = true))
            return@runCatching null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setExpandEntityReferences(false)
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val doc = builder.parse(ByteArrayInputStream(bytes, 0, length))
        fun text(parent: Element, namespace: String, name: String): String =
            parent.getElementsByTagNameNS(namespace, name).item(0)?.textContent?.trim()?.take(2048).orEmpty()
        if (text(doc.documentElement, ADDRESSING, "RelatesTo") != messageId) return@runCatching null
        val probeMatch = doc.getElementsByTagNameNS(DISCOVERY, "ProbeMatch").item(0) as? Element
            ?: return@runCatching null
        Match(
            endpoint = text(probeMatch, ADDRESSING, "Address"),
            types = text(probeMatch, DISCOVERY, "Types"),
            scopes = text(probeMatch, DISCOVERY, "Scopes"),
            xaddrs = text(probeMatch, DISCOVERY, "XAddrs"),
        )
    }.getOrNull()
}
