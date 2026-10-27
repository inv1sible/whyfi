package com.whyfi.app.usbmon

/** Keep APs with the same advertised SSID together; an unnamed AP uses its BSSID. */
internal object MonitorApVisibility {
    fun key(device: SniffedDevices.Device): String =
        device.ssid.takeIf { it.isNotBlank() }?.let { "ssid:$it" } ?: device.mac

    fun isHidden(device: SniffedDevices.Device, keys: Set<String>): Boolean =
        device.isAp && (key(device) in keys || device.mac.lowercase() in keys)

    /** Older missions stored BSSIDs, and an SSID may become known only after hiding an AP. */
    fun resolve(keys: Set<String>, devices: List<SniffedDevices.Device>): Set<String> {
        val aps = devices.filter { it.isAp }.associateBy { it.mac.lowercase() }
        return keys.mapTo(linkedSetOf()) { saved ->
            if (saved.startsWith("ssid:")) saved else aps[saved.lowercase()]?.let(::key) ?: saved.lowercase()
        }
    }

    data class Group(val key: String, val label: String, val count: Int)

    fun groups(keys: Set<String>, devices: List<SniffedDevices.Device>): List<Group> =
        resolve(keys, devices).sorted().map { key ->
            val matches = devices.filter { isHidden(it, setOf(key)) }.distinctBy { it.mac }
            Group(key, if (key.startsWith("ssid:")) label(key) else
                matches.firstOrNull()?.ssid?.takeIf { it.isNotBlank() } ?: "SSID not recorded · $key", matches.size)
        }

    fun label(key: String): String = key.removePrefix("ssid:")
}
