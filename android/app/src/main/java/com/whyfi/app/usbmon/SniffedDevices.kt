package com.whyfi.app.usbmon

/**
 * SPIKE — devices heard by the USB monitor test (UsbMonitorNative.devices),
 * plus a heuristic guess at what each one is. Pure (no Android types) so it
 * is JVM-testable. Every guess carries its reasons; none is authoritative.
 */
object SniffedDevices {

    const val ROLE_AP = 1
    const val ROLE_CLIENT = 2

    data class Device(
        val mac: String,
        val roles: Int,
        val frames: Long,
        val probeRequests: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val bestRssi: Int?,
        val channels: List<Int>,
        val ssid: String,
        val probed: List<String>,
        val wildcardProbe: Boolean,
        val bssids: List<String>,
        val wpsName: String,
        val wpsManufacturer: String,
        val wpsModel: String,
        val firstMs: Long,
        val lastMs: Long,
        val lastRssi: Int? = null,
        val lastRssiBootMs: Long? = null,
        val lastRssiChannel: Int? = null,
    ) {
        val isAp get() = roles and ROLE_AP != 0
        val isClient get() = roles and ROLE_CLIENT != 0

        /** Locally administered bit: a randomized ("private") MAC. */
        val randomized get() = mac.substring(0, 2).toInt(16) and 0x02 != 0
        val wps get() = listOf(wpsManufacturer, wpsModel, wpsName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }

    enum class Kind(val label: String) {
        ACCESS_POINT("Access point"),
        CAMERA("Camera?"),
        IOT("IoT / smart home?"),
        PHONE_LAPTOP("Phone / laptop?"),
        CLIENT("Other client"),
    }

    data class Guess(val kind: Kind, val vendor: String?, val reasons: List<String>)

    /** Parses the native TSV export; malformed lines are skipped. */
    fun parse(tsv: String): List<Device> = tsv.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 17) return@mapNotNull null
        runCatching {
            Device(
                mac = f[0], roles = f[1].toInt(), frames = f[2].toLong(), probeRequests = f[3].toLong(),
                bytesUp = f[4].toLong(), bytesDown = f[5].toLong(), bestRssi = f[6].toIntOrNull(),
                channels = f[7].split(',').mapNotNull { it.toIntOrNull() },
                ssid = f[8], probed = f[9].split('|').filter { it.isNotEmpty() }, wildcardProbe = f[10] == "1",
                bssids = f[11].split(',').filter { it.isNotEmpty() },
                wpsName = f[12], wpsManufacturer = f[13], wpsModel = f[14],
                firstMs = f[15].toLong(), lastMs = f[16].toLong(),
                lastRssi = f.getOrNull(17)?.toIntOrNull(),
                lastRssiBootMs = f.getOrNull(18)?.toLongOrNull()?.takeIf { it > 0 },
                lastRssiChannel = f.getOrNull(19)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }.getOrNull()
    }.toList()

    private val CAMERA_VENDORS = Regex(
        "hikvision|dahua|reolink|wyze|\\bring (llc|access)|\\barlo technology|ezviz|uniview|amcrest|" +
            "blink by amazon|lorex|axis communications|hanwha|vivotek|mobotix|swann|netatmo",
        RegexOption.IGNORE_CASE,
    )
    private val IOT_VENDORS = Regex(
        "espressif|tuya|silicon laboratories|texas instruments|amazon technologies|amazon\\.com|sonos inc|" +
            "sonos, inc|signify|philips lighting|ecobee|roku|nest labs|bsh hausger|eq-3|ikea of sweden|" +
            "raspberry pi|belkin|lifx|nanoleaf|broadlink|chamberlain|shelly|allterco",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE_LAPTOP_VENDORS = Regex(
        "^apple|samsung electronics|oneplus|huawei device|xiaomi|motorola mobility|intel corporat|" +
            "lite-on|azurewave|microsoft|fairphone|oppo|vivo mobile|honor device|google, inc",
        RegexOption.IGNORE_CASE,
    )
    private val CHIP_MAKERS = Regex("realtek|mediatek|hon hai|qualcomm|broadcom", RegexOption.IGNORE_CASE)
    private val CAMERA_WORDS = Regex("\\bcam|ipc|nvr|doorbell|hik|ezviz|reolink|imou|dahua", RegexOption.IGNORE_CASE)
    private val IOT_HOTSPOT = Regex(
        "^(esp[_-]|tuya|smartlife|smart life|shelly|sonoff|tasmota|hue|wled|ring setup|amazon-|wemo|ezviz_|hik-)",
        RegexOption.IGNORE_CASE,
    )

    /** Uploads at least this much and ~4× more than it downloads: video-like. */
    private const val STREAM_UP_BYTES = 256L * 1024

    fun guess(d: Device, vendorOf: (String) -> String?): Guess {
        val vendor = if (d.randomized) null else vendorOf(d.mac)
        val reasons = mutableListOf<String>()
        vendor?.let { reasons += "vendor: $it" }
        if (d.wps.isNotEmpty()) reasons += "announces itself as: ${d.wps}"
        val identity = "${d.wps} ${d.ssid}"
        val camera = (vendor != null && CAMERA_VENDORS.containsMatchIn(vendor)) || CAMERA_WORDS.containsMatchIn(identity)
        val streaming = d.bytesUp >= STREAM_UP_BYTES && d.bytesUp >= 4 * d.bytesDown
        if (streaming) reasons += "uploads continuously (${d.bytesUp / 1024} KB up / ${d.bytesDown / 1024} KB down)"
        val only24 = d.channels.isNotEmpty() && d.channels.all { it <= 14 }

        if (d.isAp && !d.isClient) {
            if (camera) return Guess(Kind.CAMERA, vendor, reasons)
            if (d.ssid.isNotEmpty() &&
                (IOT_HOTSPOT.containsMatchIn(d.ssid) || (vendor != null && IOT_VENDORS.containsMatchIn(vendor)))) {
                reasons += "network name/vendor looks like a device's own setup hotspot"
                return Guess(Kind.IOT, vendor, reasons)
            }
            return Guess(Kind.ACCESS_POINT, vendor, reasons)
        }

        val iot = vendor != null && IOT_VENDORS.containsMatchIn(vendor)
        val chip = vendor != null && CHIP_MAKERS.containsMatchIn(vendor)
        if (chip) reasons += "Wi-Fi chip maker's address — typical for embedded devices"
        if (only24 && d.frames >= 20) reasons += "only seen on 2.4 GHz"
        if (camera || (streaming && (iot || chip))) return Guess(Kind.CAMERA, vendor, reasons)
        if (iot) return Guess(Kind.IOT, vendor, reasons)
        if (d.randomized) {
            reasons += "private (randomized) MAC — typical for phones and laptops"
            return Guess(Kind.PHONE_LAPTOP, null, reasons)
        }
        if (vendor != null && PHONE_LAPTOP_VENDORS.containsMatchIn(vendor)) return Guess(Kind.PHONE_LAPTOP, vendor, reasons)
        if (chip && only24 && !streaming) return Guess(Kind.IOT, vendor, reasons)
        return Guess(Kind.CLIENT, vendor, reasons)
    }

    /** "probing for X, Y" / "connected to …" — what a client was doing. */
    fun activity(d: Device): String {
        val parts = mutableListOf<String>()
        if (d.probeRequests > 0) {
            val names = d.probed.take(5).joinToString(", ") { "\"$it\"" }
            parts += when {
                names.isNotEmpty() -> "searching for $names" + if (d.probed.size > 5) " +${d.probed.size - 5}" else ""
                else -> "searching (any network)"
            }
        }
        if (d.bssids.isNotEmpty()) parts += "connected to ${d.bssids.size} AP" + if (d.bssids.size > 1) "s" else ""
        return parts.joinToString("; ")
    }
}
