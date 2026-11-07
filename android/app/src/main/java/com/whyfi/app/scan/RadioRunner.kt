package com.whyfi.app.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a scan pass's radios at the same time instead of one after another
 * (as Fieldwatch does): a pass takes about as long as its slowest radio
 * (BLE's 6 s window) rather than the sum, so a moving phone covers less
 * ground per pass and the pass's single position fits its observations
 * better.
 *
 * - One radio failing yields an empty list for it; the others carry on.
 * - Cancellation (the local Stop button) is never swallowed.
 * - Callbacks are serialized, so callers can keep non-thread-safe state
 *   (SurveyTally) as they did when the radios ran in sequence.
 */
internal class RadioRunner(
    private val onActive: (ScanPhase, Boolean) -> Unit,
    private val onResult: (PartialScanResult) -> Unit,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val callbacks = Mutex()

    fun <T> CoroutineScope.radio(
        enabled: Boolean,
        phase: ScanPhase,
        startDelayMs: Long = 0,
        block: suspend () -> List<T>,
        wrap: (List<T>) -> PartialScanResult,
    ): Deferred<List<T>> {
        if (!enabled) return CompletableDeferred(emptyList())
        return async {
            if (startDelayMs > 0) delay(startDelayMs)
            callbacks.withLock { onActive(phase, true) }
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(phase.name, e)
                emptyList()
            }
            callbacks.withLock {
                onActive(phase, false)
                onResult(wrap(result))
            }
            result
        }
    }

    companion object {
        /** Fieldwatch starts BLE 500 ms after Wi-Fi: Wi-Fi, GNSS and BLE
         * starting in the same instant crashed some OEM Bluetooth stacks. */
        const val BLE_STAGGER_MS = 500L
    }
}
