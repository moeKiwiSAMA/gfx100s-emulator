package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeOsSchedulerTest {
    @Test
    fun loadsNativeAudioBridgeOsHelpersAndVerifiesCapabilitiesAndTuning() {
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so / fuji_audio.dll")
        assertTrue(NativeOsScheduler.isAvailable)
        assertNotNull(NativeOsScheduler.libraryPath)
        val caps = NativeOsScheduler.capabilities
        assertTrue((caps and NativeOsScheduler.CAP_HIGHRES_TIMER) != 0, "Expected CAP_HIGHRES_TIMER in caps=0x${caps.toString(16)}")

        val summary = NativeOsScheduler.statusSummary()
        assertEquals(true, summary["available"])
        assertEquals(NativeOsScheduler.OS_ABI_VERSION, summary["abiVersion"])

        val t0 = NativeOsScheduler.monotonicNanos()
        assertTrue(NativeOsScheduler.sleepNanos(1_000_000L))
        val t1 = NativeOsScheduler.monotonicNanos()
        assertTrue(t1 > t0, "monotonicNanos must advance across sleepNanos")

        val procFlags = NativeOsScheduler.tuneProcess(true)
        assertTrue(procFlags >= 0)
        assertTrue(NativeOsScheduler.acquireTimerPeriod(1))
        assertTrue(NativeOsScheduler.releaseTimerPeriod(1))
        assertFalse(NativeOsScheduler.acquireTimerPeriod(0))
        assertFalse(NativeOsScheduler.acquireTimerPeriod(16))
        NativeOsScheduler.tuneProcess(false)
    }

    @Test
    fun ownerLoopTunesEmulationOwnerThreadOnStartup() {
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so / fuji_audio.dll")
        OwnerLoop().use { owner ->
            // Submit a task so the owner thread has finished startup initialization
            val flags = owner.submit { owner.ownerThreadTuningFlags }.get(2, TimeUnit.SECONDS)
            assertTrue(
                (flags and NativeOsScheduler.CAP_THREAD_PRIORITY) != 0,
                "Expected OwnerLoop thread tuning flags to include CAP_THREAD_PRIORITY, got 0x${flags.toString(16)}",
            )
        }
    }

    @Test
    fun linuxClockNanosleepAchievesSubMillisecondAverageJitter() {
        assumeTrue(!WindowsTimerResolution.isWindowsOs(), "Linux sub-ms jitter verification")
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so")

        NativeOsScheduler.tuneCurrentThread(NativeOsScheduler.ThreadRole.OWNER_LOOP)
        // Warm up JIT + timerslack
        repeat(3) { NativeOsScheduler.sleepNanos(1_000_000L) }

        val targetNs = 2_000_000L // 2.0 ms
        val iterations = 25
        val overshoots = LongArray(iterations)
        for (i in 0 until iterations) {
            val t0 = NativeOsScheduler.monotonicNanos()
            assertTrue(NativeOsScheduler.sleepNanos(targetNs))
            val elapsed = NativeOsScheduler.monotonicNanos() - t0
            overshoots[i] = (elapsed - targetNs).coerceAtLeast(0L)
        }
        overshoots.sort()
        val avgUs = overshoots.average() / 1_000.0
        val p50Us = overshoots[iterations / 2] / 1_000.0
        val p95Us = overshoots[(iterations * 95) / 100] / 1_000.0
        val maxUs = overshoots.last() / 1_000.0
        println("[OS-NATIVE-JITTER] Linux clock_nanosleep(2ms x $iterations): p50=${"%.1f".format(p50Us)} us, p95=${"%.1f".format(p95Us)} us, avg=${"%.1f".format(avgUs)} us, max=${"%.1f".format(maxUs)} us")
        assertTrue(p50Us < 1_000.0, "Expected sub-millisecond median (p50) overshoot on Linux, got $p50Us us")
    }

    @Test
    fun nativeWaiterSupportsTimedExpirationCrossThreadWakeAndConcurrentClose() {
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so / fuji_audio.dll")
        assertNotNull(NativeOsScheduler.createWaiter()).use { waiter ->
            waiter.reset()
            assertEquals(0, waiter.waitNanos(2_000_000L), "Timed wait must return 0 on expiration")

            // Pre-signaled wake returns immediately
            waiter.wake()
            assertEquals(1, waiter.waitNanos(500_000_000L), "Pre-signaled waiter must return 1")

            // Cross-thread wake interrupts a 500ms wait promptly
            val started = CountDownLatch(1)
            val result = AtomicInteger(-1)
            val elapsedNs = AtomicLong(0L)
            val t = Thread {
                started.countDown()
                val t0 = NativeOsScheduler.monotonicNanos()
                result.set(waiter.waitNanos(500_000_000L))
                elapsedNs.set(NativeOsScheduler.monotonicNanos() - t0)
            }
            t.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            NativeOsScheduler.sleepNanos(10_000_000L)
            waiter.wake()
            t.join(2_000L)
            assertFalse(t.isAlive, "Waiter thread must exit promptly on wake()")
            assertEquals(1, result.get())
            assertTrue(elapsedNs.get() < 250_000_000L, "Expected prompt wake, got ${elapsedNs.get()} ns")
        }

        // Concurrent close() while another thread is waiting must wake cleanly without crashing
        val closingWaiter = assertNotNull(NativeOsScheduler.createWaiter())
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closeResult = AtomicInteger()
        val sleeper = Thread {
            entered.countDown()
            closeResult.set(closingWaiter.waitNanos(5_000_000_000L))
            exited.countDown()
        }
        sleeper.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        NativeOsScheduler.sleepNanos(8_000_000L)
        closingWaiter.close()
        closingWaiter.close() // idempotent
        assertTrue(exited.await(2, TimeUnit.SECONDS), "close() must wake blocked waiter thread")
        // A racing close can signal an in-flight wait (1) or prevent entry (-1).
        assertTrue(closeResult.get() in setOf(1, -1), "close() allowed the native wait to time out")
        assertEquals(-1, closingWaiter.waitNanos(1_000_000L), "Closed waiter must refuse new waits")
    }

    @Test
    fun realTimePacerUsesNativeHighResTimerWithTightWallPacingAndPromptWake() {
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so / fuji_audio.dll")
        RealTimePacer(
            ticksPerRealSecond = 54_000_000.0,
            targetRatio = 1.0,
            minSleepNanos = 1_000_000L,
            spinTailNanos = 150_000L,
            maxParkChunkNanos = 10_000_000L,
        ).use { pacer ->
            assertTrue(pacer.usesNativeHighResTimer, "RealTimePacer should bind native high-res waiter by default")
            assertEquals(true, pacer.stats()["nativeHighResTimer"])

            pacer.reanchor(0L)
            val wallStart = System.nanoTime()
            // 4 steps of 5 ms virtual time (270,000 ticks each) = 20 ms target wall time
            var vTick = 0L
            for (step in 1..4) {
                vTick += 270_000L
                pacer.pace(vTick)
            }
            val wallMs = (System.nanoTime() - wallStart) / 1_000_000.0
            println("[OS-NATIVE-PACER] RealTimePacer 4x5ms (20ms target): wall=${"%.2f".format(wallMs)} ms, stats=${pacer.stats()}")
            assertTrue(wallMs in 17.0..45.0, "Expected ~20 ms paced wall time, got $wallMs ms")
            assertTrue(pacer.sleepCount >= 1L)
        }
    }

    @Test
    fun adviseHugepageHandlesValidOffHeapBufferAndNullGuard() {
        assumeTrue(NativeOsScheduler.initialize(), "Requires built libfuji_audio.so / fuji_audio.dll")
        assertFalse(NativeOsScheduler.adviseHugepage(MemorySegment.NULL))
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(2L * 1024L * 1024L, 4096L)
            // Must never throw (returns true on Linux THP-enabled kernels; may return false if kernel disables THP)
            val rc = NativeOsScheduler.adviseHugepage(seg)
            println("[OS-NATIVE-THP] adviseHugepage(2 MiB) -> $rc")
        }
    }
}
