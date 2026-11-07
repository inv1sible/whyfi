package com.whyfi.app.lan

/**
 * Browser links for a LAN host's open web ports, so a router, printer or
 * NAS admin page is one tap away. Pure, unit-tested (LanWebLinksTest).
 */
object LanWebLinks {
    data class Link(val label: String, val url: String)

    private val HTTPS_PORTS = setOf(443, 8443)
    /** Ports that answer plain HTTP on common LAN devices (CUPS on 631,
     * Synology/UPnP on 5000, Plex on 32400). */
    private val HTTP_PORTS = setOf(80, 631, 5000, 8000, 8008, 8080, 32400)

    fun links(ipAddress: String, openPorts: List<Int>): List<Link> = openPorts.distinct().sorted().mapNotNull { port ->
        val scheme = when (port) {
            in HTTPS_PORTS -> "https"
            in HTTP_PORTS -> "http"
            else -> return@mapNotNull null
        }
        val default = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        val path = if (port == 32400) "/web" else ""
        Link("$scheme :$port", "$scheme://$ipAddress${if (default) "" else ":$port"}$path")
    }
}

/** Display label for LanScanner's device type guess. */
fun lanDeviceTypeLabel(deviceType: String): String = when (deviceType) {
    "ROUTER" -> "Router/Gateway"
    "PRINTER" -> "Printer"
    "NAS" -> "NAS/File server"
    "MEDIA" -> "Media/streaming"
    "CAMERA" -> "Camera"
    "WINDOWS_HOST" -> "Windows host"
    "LINUX_HOST" -> "Linux/Unix host"
    "IOT" -> "IoT device"
    else -> "Unknown"
}
