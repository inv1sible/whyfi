package com.whyfi.app.usbmon

/** Keep APs with the same advertised SSID together; an unnamed AP uses its BSSID. */
internal object MonitorApVisibility {
    fun key(device: SniffedDevices.Device): String =
        device.ssid.takeIf { it.isNotBlank() }?.let { "ssid:$it" } ?: device.mac

    fun isHidden(device: SniffedDevices.Device, keys: Set<String>): Boolean =
        device.isAp && (key(device) in keys || device.mac in keys)

    fun label(key: String): String = key.removePrefix("ssid:")
}
