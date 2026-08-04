package com.whyfi.app.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun requiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.UWB_RANGING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Without this, the foreground service's scanning notification
            // (ScanForegroundService) silently doesn't show — the service
            // still runs, but the whole point is to be visibly honest about
            // scanning continuing in the background.
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    fun hasAllRequiredPermissions(context: Context): Boolean =
        requiredRuntimePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** WiFi/BLE/GNSS scan results are empty or stale if the device's
     * location services are off, even with the permission granted — a
     * common gotcha worth surfacing explicitly in the UI. */
    fun isLocationServicesEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** True if the Bluetooth adapter exists and is currently powered on.
     * Uses BluetoothManager (not the deprecated static getDefaultAdapter)
     * for the same reason as BleDeviceScanner — the static getter is
     * unreliable on hardened ROMs. */
    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    /** True if the WiFi radio is currently enabled. On API 29+ a normal
     * app can read this state but cannot *change* it (setWifiEnabled is
     * restricted to system apps) — see MEMORY.md. */
    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifiManager?.isWifiEnabled == true
    }
}
