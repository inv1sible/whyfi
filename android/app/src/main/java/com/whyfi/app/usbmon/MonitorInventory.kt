package com.whyfi.app.usbmon

/** Exact observed identities, retaining old devices until new frames update them. */
internal object MonitorInventory {
    fun merge(recorded: List<SniffedDevices.Device>, incoming: List<SniffedDevices.Device>): List<SniffedDevices.Device> {
        val devices = recorded.associateByTo(linkedMapOf()) { it.mac }
        incoming.forEach { latest ->
            val old = devices[latest.mac]
            devices[latest.mac] = if (old == null) latest else latest.copy(
                roles = old.roles or latest.roles,
                ssid = latest.ssid.ifBlank { old.ssid },
                wpsName = latest.wpsName.ifBlank { old.wpsName },
                wpsManufacturer = latest.wpsManufacturer.ifBlank { old.wpsManufacturer },
                wpsModel = latest.wpsModel.ifBlank { old.wpsModel },
                wpsModelNumber = latest.wpsModelNumber.ifBlank { old.wpsModelNumber },
                wpsPrimaryType = latest.wpsPrimaryType.ifBlank { old.wpsPrimaryType },
                wpsSerial = latest.wpsSerial.ifBlank { old.wpsSerial },
                wpsConfigMethods = latest.wpsConfigMethods.ifBlank { old.wpsConfigMethods },
                wpsState = latest.wpsState.ifBlank { old.wpsState },
                wpsLocked = latest.wpsLocked.ifBlank { old.wpsLocked },
                security = latest.security.ifBlank { old.security },
                akms = latest.akms.ifBlank { old.akms },
                pairwiseCiphers = latest.pairwiseCiphers.ifBlank { old.pairwiseCiphers },
                groupCipher = latest.groupCipher.ifBlank { old.groupCipher },
                pmf = latest.pmf.ifBlank { old.pmf },
            )
        }
        return devices.values.toList()
    }

    fun matches(recorded: List<SniffedDevices.Device>, incoming: List<SniffedDevices.Device>): Set<String> =
        recorded.mapTo(mutableSetOf()) { it.mac }.intersect(incoming.mapTo(mutableSetOf()) { it.mac })

    fun matchOrder(matches: Set<String>): Comparator<Pair<SniffedDevices.Device, SniffedDevices.Guess>> =
        compareByDescending<Pair<SniffedDevices.Device, SniffedDevices.Guess>> { it.first.mac in matches }
            .thenByDescending { it.first.lastRssi ?: it.first.bestRssi ?: Int.MIN_VALUE }
            .thenByDescending { it.first.lastMs }.thenBy { it.first.mac }
}
