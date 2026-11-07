package com.whyfi.app.scan

import com.whyfi.app.data.remote.BleObservationDto
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRunnerTest {

    private fun ble(n: Int) = List(n) {
        val mac = "aa:bb:cc:dd:ee:%02x".format(it)
        BleObservationDto(bleMac = mac, stableIdentifier = mac, rssi = -60, txPower = null, manufacturerData = "", deviceTypeGuess = "")
    }

    @Test
    fun radiosRunConcurrently() = runBlocking(Dispatchers.Default) {
        val runner = RadioRunner({ _, _ -> }, {})
        val start = System.nanoTime()
        val results = coroutineScope {
            with(runner) {
                (1..4).map { i -> radio(true, ScanPhase.BLE, block = { delay(300); ble(i) }) { PartialScanResult.Ble(it) } }
                    .awaitAll()
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(listOf(1, 2, 3, 4), results.map { it.size })
        assertTrue("took $elapsedMs ms; sequential would be 1200", elapsedMs < 900)
    }

    @Test
    fun aFailingRadioYieldsEmptyAndTheOthersFinish() = runBlocking(Dispatchers.Default) {
        val errors = mutableListOf<String>()
        val runner = RadioRunner({ _, _ -> }, {}, { radio, _ -> errors += radio })
        val (bad, good) = coroutineScope {
            with(runner) {
                val bad = radio<BleObservationDto>(true, ScanPhase.GNSS, block = { delay(50); error("no GNSS hardware") }) { PartialScanResult.Ble(ble(0)) }
                val good = radio(true, ScanPhase.BLE, block = { delay(200); ble(2) }) { PartialScanResult.Ble(it) }
                bad.await() to good.await()
            }
        }
        assertEquals(emptyList<Any>(), bad)
        assertEquals(2, good.size)
        assertEquals(listOf("GNSS"), errors)
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking(Dispatchers.Default) {
        val results = Collections.synchronizedList(mutableListOf<PartialScanResult>())
        val runner = RadioRunner({ _, _ -> }, { results += it })
        var cancelled = false
        val job = launch {
            try {
                coroutineScope {
                    with(runner) { radio(true, ScanPhase.BLE, block = { delay(5_000); ble(1) }) { PartialScanResult.Ble(it) }.await() }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        delay(100)
        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertTrue("a cancelled radio must not report a result", results.isEmpty())
    }

    @Test
    fun callbacksNeverOverlap() = runBlocking(Dispatchers.Default) {
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val active = Collections.synchronizedList(mutableListOf<Pair<ScanPhase, Boolean>>())
        val runner = RadioRunner(
            onActive = { phase, on -> active += phase to on },
            onResult = {
                val now = inside.incrementAndGet()
                maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(20) // non-thread-safe state is touched here in the app
                inside.decrementAndGet()
            },
        )
        coroutineScope {
            with(runner) {
                (1..8).map { radio(true, ScanPhase.BLE, block = { withContext(Dispatchers.Default) { ble(1) } }) { PartialScanResult.Ble(it) } }
                    .awaitAll()
            }
        }
        assertEquals(1, maxInside.get())
        assertEquals(8, active.count { it.second })
        assertEquals(8, active.count { !it.second })
    }

    @Test
    fun disabledRadioReturnsEmptyWithoutCallbacks() = runBlocking {
        val calls = AtomicInteger()
        val runner = RadioRunner({ _, _ -> calls.incrementAndGet() }, { calls.incrementAndGet() })
        val result = coroutineScope {
            with(runner) { radio(false, ScanPhase.WIFI, block = { ble(3) }) { PartialScanResult.Ble(it) }.await() }
        }
        assertTrue(result.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun staggeredRadioStartsLater() = runBlocking(Dispatchers.Default) {
        val starts = Collections.synchronizedMap(mutableMapOf<ScanPhase, Long>())
        val runner = RadioRunner({ phase, on -> if (on) starts[phase] = System.nanoTime() }, {})
        coroutineScope {
            with(runner) {
                listOf(
                    radio(true, ScanPhase.WIFI, block = { ble(0) }) { PartialScanResult.Ble(it) },
                    radio(true, ScanPhase.BLE, startDelayMs = RadioRunner.BLE_STAGGER_MS, block = { ble(0) }) { PartialScanResult.Ble(it) },
                ).awaitAll()
            }
        }
        val gapMs = (starts.getValue(ScanPhase.BLE) - starts.getValue(ScanPhase.WIFI)) / 1_000_000
        assertTrue("gap $gapMs ms", gapMs >= RadioRunner.BLE_STAGGER_MS - 20)
    }
}
