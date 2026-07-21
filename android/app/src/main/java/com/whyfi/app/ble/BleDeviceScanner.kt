package com.whyfi.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.whyfi.app.data.remote.BleObservationDto
import kotlinx.coroutines.delay

/** Passive BLE advertisement scan. Emits observations only — device type is
 * an informational best-effort label; there is deliberately no on-device
 * correlation/alerting here. See MEMORY.md. */
class BleDeviceScanner(private val context: Context) {

    // BluetoothManager-based lookup, not the deprecated static
    // BluetoothAdapter.getDefaultAdapter() — the static getter is
    // deprecated since API 33 and has been unreliable on some hardened
    // ROMs (GrapheneOS), silently returning null.
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Non-null only when BLE scanning can't proceed — lets the UI explain
     * why a pass came back with zero results instead of it looking like
     * the scan just silently ended early. */
    fun unavailableReason(): String? {
        val bluetoothAdapter = adapter ?: return "This device has no Bluetooth adapter."
        if (!bluetoothAdapter.isEnabled) return "Bluetooth is turned off."
        return null
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(durationMs: Long = 6000): List<BleObservationDto> {
        val bluetoothAdapter = adapter ?: return emptyList()
        if (!bluetoothAdapter.isEnabled) return emptyList()
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return emptyList()

        val seenByAddress = LinkedHashMap<String, BleObservationDto>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seenByAddress[result.device.address] = toDto(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { seenByAddress[it.device.address] = toDto(it) }
            }
        }

        val started = runCatching { scanner.startScan(callback) }.isSuccess
        if (!started) return emptyList()
        delay(durationMs)
        runCatching { scanner.stopScan(callback) }

        return seenByAddress.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun toDto(result: ScanResult): BleObservationDto {
        val record = result.scanRecord
        val manufacturerHex = record?.manufacturerSpecificData?.let { sparse ->
            if (sparse.size() == 0) "" else {
                val companyId = sparse.keyAt(0)
                val bytes = sparse.valueAt(0)
                "%04x:%s".format(companyId, bytes.joinToString("") { byte -> "%02x".format(byte) })
            }
        } ?: ""

        return BleObservationDto(
            bleMac = result.device.address ?: "",
            stableIdentifier = result.device.address ?: "",
            rssi = result.rssi,
            txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
            manufacturerData = manufacturerHex,
            serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
            deviceTypeGuess = BleSignatureMatcher.guessDeviceType(result),
            // deviceName comes from the advertisement payload itself, not a
            // GATT read — no BLUETOOTH_CONNECT needed for this one.
            deviceName = record?.deviceName ?: "",
            isConnectable = result.isConnectable,
            primaryPhy = primaryPhyLabel(result.primaryPhy),
        )
    }

    private fun primaryPhyLabel(phy: Int): String = when (phy) {
        android.bluetooth.BluetoothDevice.PHY_LE_1M -> "LE 1M"
        android.bluetooth.BluetoothDevice.PHY_LE_CODED -> "LE Coded"
        else -> ""
    }
}
