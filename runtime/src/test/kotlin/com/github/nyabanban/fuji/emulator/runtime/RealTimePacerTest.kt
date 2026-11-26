package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealTimePacerTest {
    @Test
    fun pacesVirtualLeadToTargetWallClockAndResyncsOnLargeLag() {
        val fakeClock = AtomicLong(1_000_000_000L)
        var parkCalls = 0
        val pacer = RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            targetRatio = 1.0,
            minSleepNanos = 2_000_000L,
            spinTailNanos = 0L,
            maxLagResyncNanos = 200_000_000L,
            maxParkChunkNanos = 10_000_000L,
            nanoClock = { fakeClock.get() },
            parker = { ns ->
                parkCalls++
                fakeClock.addAndGet(ns)
            },
            spinWaitEnabled = false,
        )

        // First call establishes anchor, zero sleep
        assertEquals(0L, pacer.pace(0L))
        assertEquals(0, parkCalls)

        // Advance virtual clock by 0.05s (2,700,000 ticks) in 0ms wall -> should sleep ~50ms in 10ms chunks
        val slept = pacer.pace(2_700_000L)
        assertEquals(50_000_000L, slept)
        assertEquals(5, parkCalls)
        assertEquals(1L, pacer.sleepCount)
        assertEquals(0L, pacer.resyncCount)

        // Advance virtual clock by 1ms (54,000 ticks) -> below 2ms threshold -> no sleep
        assertEquals(0L, pacer.pace(2_754_000L))
        assertEquals(5, parkCalls)

        // Simulate 500ms host lag (heavy GC / cold boot) -> smooth resync, zero sleep
        fakeClock.addAndGet(500_000_000L)
        assertEquals(0L, pacer.pace(2_754_000L))
        assertEquals(1L, pacer.resyncCount)

        // Subsequent 20ms virtual lead after resync sleeps cleanly ~20ms
        val sleptAfterResync = pacer.pace(2_754_000L + 1_080_000L)
        assertEquals(20_000_000L, sleptAfterResync)
    }

    @Test
    fun wakeInterruptsParkedPacerImmediately() {
        val pacer = RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            targetRatio = 1.0,
            minSleepNanos = 1_000_000L,
            spinTailNanos = 0L,
            maxParkChunkNanos = 500_000_000L,
            spinWaitEnabled = false,
        )
        pacer.reanchor(0L)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sleptNanos = AtomicLong(-1L)
        val t = Thread {
            started.countDown()
            // 2 full virtual seconds ahead -> would park 500ms chunk if not woken
            sleptNanos.set(pacer.pace(108_000_000L))
            finished.countDown()
        }
        t.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(15L)
        pacer.wake()
        assertTrue(finished.await(2, TimeUnit.SECONDS), "wake() must unpark RealTimePacer promptly")
        assertTrue(sleptNanos.get() < 300_000_000L, "Expected prompt wakeup, got ${sleptNanos.get()} ns")
        assertTrue(pacer.wakeCount >= 1L)
    }

    @Test
    fun ownerLoopSubmitRunnerSliceDoesNotSelfWakeRealTimePacerWhileExternalSubmitStillWakes() {
        val fakeClock = AtomicLong(1_000_000_000L)
        val pacer = RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            targetRatio = 1.0,
            minSleepNanos = 2_000_000L,
            spinTailNanos = 0L,
            maxLagResyncNanos = 200_000_000L,
            maxParkChunkNanos = 10_000_000L,
            nanoClock = { fakeClock.get() },
            parker = { ns -> fakeClock.addAndGet(ns) },
            spinWaitEnabled = false,
        )
        OwnerLoop().use { owner ->
            owner.onExternalSubmit = pacer::wake
            pacer.reanchor(0L)

            // Runner stepping slices must NEVER self-wake the pacer (BUG-V3-1-1)
            var vTick = 0L
            repeat(5) {
                vTick = owner.submitRunnerSlice { vTick + 1_080_000L }.join() // +20 ms virtual lead per slice
                val slept = pacer.pace(vTick) { !owner.isRunning || owner.hasPending() }
                assertEquals(20_000_000L, slept, "Runner slice must not self-wake pacer")
            }
            assertEquals(5L, pacer.sleepCount)
            assertEquals(0L, pacer.wakeCount)

            // Genuine external submits (UI/HTTP/input) MUST still invoke pacer.wake()
            owner.submit { 42 }.join()
            val sleptAfterExternal = pacer.pace(vTick + 1_080_000L)
            assertEquals(0L, sleptAfterExternal, "External submit must wake pacer promptly")
            assertEquals(1L, pacer.wakeCount)
        }
    }

    @Test
    fun windowsTimerResolutionIsSafeNoOpOnLinuxAndValidatesPeriod() {
        assertFalse(WindowsTimerResolution.isWindowsOs("Linux"))
        assertFalse(WindowsTimerResolution.isWindowsOs("Mac OS X"))
        assertTrue(WindowsTimerResolution.isWindowsOs("Windows 11"))
        assertTrue(WindowsTimerResolution.isWindowsOs("Windows 10"))

        WindowsTimerResolution.acquireForOs("Linux", 1).use { handle ->
            assertFalse(handle.active)
            assertEquals(0, handle.periodMs)
        }
        // Calling acquire() on this Linux host must never throw and must be idempotently closeable
        val handle = WindowsTimerResolution.acquire(1)
        handle.close()
        handle.close()

        assertFailsWith<IllegalArgumentException> { WindowsTimerResolution.acquireForOs("Linux", 0) }
        assertFailsWith<IllegalArgumentException> { WindowsTimerResolution.acquireForOs("Linux", 16) }
    }

    @Test
    fun paceWfiIdleParksInBoundedSub500UsChunksWithoutSpinAndWakesImmediately() {
        val fakeClock = AtomicLong(1_000_000_000L)
        var clockReads = 0
        val chunks = ArrayList<Long>()
        val pacer = RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            targetRatio = 1.0,
            minSleepNanos = 2_000_000L,
            spinTailNanos = 250_000L,
            maxLagResyncNanos = 200_000_000L,
            maxParkChunkNanos = 10_000_000L,
            nanoClock = {
                check(++clockReads <= 32) { "WFI is polling a clock that advances only during a park" }
                fakeClock.get()
            },
            parker = { ns ->
                chunks.add(ns)
                fakeClock.addAndGet(ns)
            },
            spinWaitEnabled = true,
        )
        pacer.reanchor(0L)

        // 1.5 ms virtual lead (81,000 ticks) -> regular pace() skips (< 2 ms), but paceWfiIdle() parks immediately!
        assertEquals(0L, pacer.pace(81_000L))
        assertTrue(chunks.isEmpty())

        val slept = pacer.paceWfiIdle(81_000L)
        assertEquals(1_500_000L, slept)
        assertEquals(3, chunks.size, "1.5 ms WFI idle should park in three 500 us chunks")
        assertTrue(chunks.all { it in 1L..RealTimePacer.DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS }, "All WFI idle chunks must be <= 500 us: $chunks")
        assertEquals(1L, pacer.wfiIdleParkCount)
        assertEquals(1_500_000L, pacer.wfiIdleSleptNanos)
        assertTrue((pacer.stats()["wfiIdleParkCount"] as Long) == 1L)
        // Another 225 us lies inside the regular pacer's spin tail but still warrants an idle park.
        assertEquals(225_000L, pacer.paceWfiIdle(93_150L))
        assertEquals(225_000L, chunks.last())

        // Interrupt the first park a quarter of the way into its budget. The fake clock
        // advances only while parked, so this checks the pacer's latency independently of
        // the OS scheduling either the waking thread or the pacing thread.
        val clock = AtomicLong()
        val interruptedParks = mutableListOf<Long>()
        lateinit var interruptible: RealTimePacer
        interruptible = RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            nanoClock = clock::get,
            parker = { budget ->
                interruptedParks.add(budget)
                clock.addAndGet(budget / 4)
                interruptible.wake()
            },
        )
        interruptible.use {
            it.reanchor(0L)
            val elapsed = it.paceWfiIdle(54_000_000L)
            assertEquals(listOf(RealTimePacer.DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS), interruptedParks)
            assertEquals(RealTimePacer.DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS / 4, elapsed)
            assertEquals(1L, it.wakeCount)
        }
    }

    @Test
    fun realWfiParkRespondsToExternalWake() {
        RealTimePacer(ticksPerRealSecond = 54_000_000.0).use { pacer ->
            val firstParkCompleted = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val returnedAt = AtomicLong()
            val stop = AtomicBoolean()
            pacer.reanchor(0L)
            val thread = Thread {
                var abortChecks = 0
                pacer.paceWfiIdle(54_000_000L * 60) {
                    // One check at entry, one before parking, then one after the first real park.
                    if (++abortChecks == 3) firstParkCompleted.countDown()
                    stop.get()
                }
                returnedAt.set(System.nanoTime())
                finished.countDown()
            }
            thread.start()
            try {
                assertTrue(firstParkCompleted.await(2, TimeUnit.SECONDS), "Pacer did not complete its first idle park")
                val wakeTime = System.nanoTime()
                pacer.wake()
                assertTrue(finished.await(2, TimeUnit.SECONDS), "External wake did not end the 60-second WFI wait")
                assertEquals(1L, pacer.wakeCount)
                println("[WFI] wake-to-return ns=${returnedAt.get() - wakeTime} native=${pacer.usesNativeHighResTimer}")
            } finally {
                stop.set(true)
                pacer.wake()
                thread.join()
            }
        }
    }
}
