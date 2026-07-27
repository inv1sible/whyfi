package com.whyfi.app.scan

import android.content.Context
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.ScanPolicyResponse
import com.whyfi.app.data.remote.SensorHeartbeatRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.math.min

/**
 * Polls the backend so scanning on this phone can be started and stopped
 * from the whyfi web UI.
 *
 * The direction is inverted on purpose. A server cannot reach a phone: it's
 * behind carrier NAT, and since Android 12 an app may not start a foreground
 * service from the background at all — and since Android 11 a
 * background-started location service gets no location access even if it does
 * start. Both walls are deliberate anti-covert-activation measures, and FCM
 * would only clear the first. So the phone asks, on a loop, and converges on
 * whatever state it's told.
 *
 * Deliberately a plain class rather than a second Service: scanning already
 * has one ([ScanForegroundService]), and a second would mean two persistent
 * notifications and two lifetimes to reason about. This runs inside that
 * service's own scope and drives it through the same entry points the
 * on-screen buttons use.
 */
class RemoteControlAgent(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** What this device is currently doing, for the heartbeat body. */
        suspend fun currentReport(): SensorHeartbeatRequest

        /** Called with the desired state on every successful heartbeat. */
        suspend fun onPolicy(policy: ScanPolicyResponse)

        /** The token was rejected — this device can no longer be told to stop,
         * so it must stop itself. */
        suspend fun onAuthRejected()
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            // Re-read every pass: the backend URL and token are editable on
            // the Settings screen while this loop is running.
            val settings = SettingsRepository(context)
            val backendUrl = settings.backendUrl
            val token = settings.sensorToken

            if (backendUrl.isNullOrBlank() || token.isNullOrBlank()) {
                delay(UNCONFIGURED_RETRY_MS)
                continue
            }

            var heartbeatMs = DEFAULT_HEARTBEAT_MS
            var scanningWanted = false

            try {
                val response = ApiClientFactory.create(backendUrl)
                    .sensorHeartbeat("Token $token", callbacks.currentReport())

                if (response.code() == 401 || response.code() == 403) {
                    // Sensor deactivated, or its token regenerated. Nothing
                    // the operator does can reach this phone now, so leaving
                    // it scanning would strand it running forever.
                    callbacks.onAuthRejected()
                    return
                }

                val policy = if (response.isSuccessful) response.body() else null
                if (policy != null) {
                    callbacks.onPolicy(policy)
                    heartbeatMs = policy.heartbeatIntervalSeconds.coerceAtLeast(MIN_HEARTBEAT_SECONDS) * 1000L
                    scanningWanted = policy.remoteScanEnabled
                }
            } catch (e: IOException) {
                // Offline or backend down. Keep the loop alive and keep
                // scanning if we already were — an outage shouldn't cost
                // survey data, and the outbox holds results until it clears.
            }

            // Armed-but-idle is the common case for a phone left configured,
            // and there's nothing to be responsive to, so back off. While
            // scanning is actually running the poll is negligible next to the
            // radio work it's coordinating.
            delay(if (scanningWanted) heartbeatMs else min(heartbeatMs * IDLE_BACKOFF, IDLE_MAX_MS))
        }
    }

    private companion object {
        const val DEFAULT_HEARTBEAT_MS = 15_000L
        const val MIN_HEARTBEAT_SECONDS = 5
        const val UNCONFIGURED_RETRY_MS = 60_000L
        const val IDLE_BACKOFF = 4
        const val IDLE_MAX_MS = 60_000L
    }
}
