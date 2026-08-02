package com.whyfi.app.cellular

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.whyfi.app.data.remote.CellObservationDto
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Phone's own serving/neighboring cell info only — no SDR, no spectrum
 * scanning, nothing beyond what TelephonyManager already exposes.
 *
 * Uses `requestCellInfoUpdate()` (API 29+), not the older synchronous
 * `getAllCellInfo()` getter — the synchronous getter is well-documented to
 * return a stale or empty cached snapshot on many real devices, especially
 * right after the app gains permission/starts. `requestCellInfoUpdate()`
 * actively asks the modem to refresh before returning. Falls back to the
 * synchronous getter only on API 28 (this app's minSdk), where the async
 * API doesn't exist. */
class CellularManager(private val context: Context) {

    private val telephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /** Non-null only when the cellular radio itself is powered off — the
     * most common cause being airplane mode. Uses the modem's actual
     * service state rather than the airplane-mode flag directly, so a
     * device with cellular somehow still active isn't warned about. */
    @SuppressLint("MissingPermission")
    fun unavailableReason(): String? {
        val serviceState = runCatching { telephonyManager.serviceState }.getOrNull() ?: return null
        return if (serviceState.state == ServiceState.STATE_POWER_OFF) "Cellular radio is off (airplane mode?)." else null
    }

    /** True only when the device actually has a modem. A WiFi-only tablet
     * still returns a (non-null, unusable) TelephonyManager instance from
     * getSystemService — the framework doesn't refuse to hand one out just
     * because there's no radio behind it — so this is the check that
     * actually distinguishes "has cellular hardware" from "doesn't". */
    private fun hasTelephonyHardware(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    @SuppressLint("MissingPermission")
    suspend fun readCellObservations(): List<CellObservationDto> {
        if (!hasTelephonyHardware()) return emptyList()
        val cellInfoList = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestFreshCellInfo()
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.allCellInfo ?: emptyList()
            }
        }.getOrElse { error ->
            // Since API 30, TelephonyManager methods throw
            // UnsupportedOperationException synchronously (before any
            // callback fires) on hardware without FEATURE_TELEPHONY — the
            // check above should already catch that, but a modem can also
            // throw for other transient reasons (radio busy/off, OEM
            // quirks), and this is a "best-effort extra" radio, not one
            // worth taking the whole scan pass down over.
            Log.w("CellularManager", "readCellObservations failed, treating as no cellular this pass", error)
            emptyList()
        }
        return cellInfoList.mapNotNull(::toDto)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshCellInfo(): List<CellInfo> = suspendCancellableCoroutine { continuation ->
        val callback = object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                if (continuation.isActive) continuation.resume(cellInfo)
            }

            override fun onError(errorCode: Int, detail: Throwable?) {
                // Fall back to whatever's cached rather than surfacing nothing.
                @Suppress("DEPRECATION")
                if (continuation.isActive) continuation.resume(telephonyManager.allCellInfo ?: emptyList())
            }
        }
        telephonyManager.requestCellInfoUpdate(ContextCompat.getMainExecutor(context), callback)
    }

    private fun toDto(cellInfo: CellInfo): CellObservationDto? = when (cellInfo) {
        is CellInfoLte -> {
            val identity = cellInfo.cellIdentity
            val signal = cellInfo.cellSignalStrength
            CellObservationDto(
                mcc = identity.mccString ?: "",
                mnc = identity.mncString ?: "",
                carrierName = identity.operatorAlphaLong?.toString() ?: "",
                radioType = "LTE",
                cellId = validOrBlank(identity.ci),
                tacOrLac = validOrBlank(identity.tac),
                band = "",
                isServingCell = cellInfo.isRegistered,
                signalDbm = signal.dbm,
                rsrp = signal.rsrp,
                rsrq = signal.rsrq,
                sinr = signal.rssnr.toDouble(),
                physicalCellId = validOrNull(identity.pci),
                arfcn = validOrNull(identity.earfcn),
                bandwidthKhz = validOrNull(identity.bandwidth),
                timingAdvance = validOrNull(signal.timingAdvance),
            )
        }

        is CellInfoNr -> {
            val identity = cellInfo.cellIdentity as? CellIdentityNr
            val signal = cellInfo.cellSignalStrength as? CellSignalStrengthNr
            CellObservationDto(
                mcc = identity?.mccString ?: "",
                mnc = identity?.mncString ?: "",
                carrierName = identity?.operatorAlphaLong?.toString() ?: "",
                radioType = "NR",
                cellId = identity?.nci?.let { if (it == Long.MAX_VALUE) "" else it.toString() } ?: "",
                tacOrLac = identity?.tac?.let { validOrBlank(it) } ?: "",
                band = "",
                isServingCell = cellInfo.isRegistered,
                signalDbm = signal?.dbm,
                rsrp = signal?.ssRsrp,
                rsrq = signal?.ssRsrq,
                sinr = signal?.ssSinr?.toDouble(),
                physicalCellId = identity?.pci?.let { validOrNull(it) },
                arfcn = identity?.nrarfcn?.let { validOrNull(it) },
            )
        }

        is CellInfoGsm -> {
            val identity = cellInfo.cellIdentity
            val signal = cellInfo.cellSignalStrength
            CellObservationDto(
                mcc = identity.mccString ?: "",
                mnc = identity.mncString ?: "",
                carrierName = identity.operatorAlphaLong?.toString() ?: "",
                radioType = "GSM",
                cellId = validOrBlank(identity.cid),
                tacOrLac = validOrBlank(identity.lac),
                band = "",
                isServingCell = cellInfo.isRegistered,
                signalDbm = signal.dbm,
                rsrp = null,
                rsrq = null,
                sinr = null,
                arfcn = validOrNull(identity.arfcn),
            )
        }

        is CellInfoWcdma -> {
            val identity = cellInfo.cellIdentity
            val signal = cellInfo.cellSignalStrength
            CellObservationDto(
                mcc = identity.mccString ?: "",
                mnc = identity.mncString ?: "",
                carrierName = identity.operatorAlphaLong?.toString() ?: "",
                radioType = "UMTS",
                cellId = validOrBlank(identity.cid),
                tacOrLac = validOrBlank(identity.lac),
                band = "",
                isServingCell = cellInfo.isRegistered,
                signalDbm = signal.dbm,
                rsrp = null,
                rsrq = null,
                sinr = null,
                physicalCellId = validOrNull(identity.psc),
                arfcn = validOrNull(identity.uarfcn),
            )
        }

        else -> null
    }

    private fun validOrBlank(value: Int): String = if (value == Int.MAX_VALUE) "" else value.toString()

    private fun validOrNull(value: Int): Int? = if (value == Int.MAX_VALUE || value < 0) null else value
}
