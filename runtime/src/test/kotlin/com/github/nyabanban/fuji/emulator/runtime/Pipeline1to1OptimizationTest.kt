package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pipeline1to1OptimizationTest {
    @Test
    fun faceScalerReciprocalMatchesExactDivisionAcrossFullDomain() {
        val denom = 639 * 359
        val half = denom / 2
        val shift = 45
        val recip = (1L shl shift) / denom.toLong() + 1L
        val maxN = 255 * denom + half
        // Exhaustively verify every integer in [0, 58_611_955] for exact bit-identity.
        for (n in 0..maxN) {
            val fast = ((n.toLong() * recip) ushr shift).toInt()
            val exact = n / denom
            if (fast != exact) {
                throw AssertionError("Reciprocal mismatch at n=$n: fast=$fast exact=$exact")
            }
        }
    }

    @Test
    fun mmioRouter128BitBitmaskAndHeapExclusionMatchBruteForce() {
        class StubTickDevice(
            override val clockOrder: Int,
            override val clockIndex: Int,
            var nextTick: Long,
        ) : TickAwareMmioDevice {
            var tickedCount = 0
            var lastTick = -1L
            override fun read(offset: Long, size: Int): Long = nextTick
            override fun write(offset: Long, size: Int, value: Long) {
                nextTick = value
            }
            override fun nextDeadline(): Long? = nextTick.takeIf { it != Long.MAX_VALUE }
            override fun nextInterestedTick(): Long = nextTick
            override fun beforeInstruction(tick: Long) {
                tickedCount++
                lastTick = tick
                nextTick = Long.MAX_VALUE
            }
        }

        // Create 80 distinct TickAwareMmioDevice instances (>64 to exercise hi-word 128-bit bitmask path).
        val devices = List(80) { idx ->
            StubTickDevice(clockOrder = idx / 10, clockIndex = idx % 10, nextTick = 1000L + idx * 17L)
        }
        val windows = devices.mapIndexed { idx, dev ->
            MmioWindow(
                id = if (idx % 2 == 0) "soc-configuration" else "soc-dram",
                base = 0xFF70_0000L + idx.toLong() * 0x100L,
                size = 0x100L,
                port = dev,
            )
        }
        val router = MmioRouter(windows)

        // Verify nextScheduledTickExcluding against brute-force for every device (including root and hi-word devices).
        for (excluded in devices) {
            val expected = devices.filter { it !== excluded }.minOf { it.nextInterestedTick() }
            assertEquals(expected, router.nextScheduledTickExcluding(excluded))
        }

        // Mutate a device in the hi-word (>64) via MMIO write and confirm targeted dirty refresh updates heap min.
        router.write(0xFF70_0000L + 75L * 0x100L, 4, 500L)
        assertTrue(router.isScheduleDirty())
        assertEquals(500L, router.refreshNextScheduledTick())
        assertEquals(1000L, router.nextScheduledTickExcluding(devices[75]))

        // Tick at 500: only device 75 should fire.
        router.beforeInstruction(500L)
        assertEquals(1, devices[75].tickedCount)
        assertEquals(500L, devices[75].lastTick)
        assertEquals(0, devices[0].tickedCount)
        assertEquals(1000L, router.refreshNextScheduledTick())

        // Tick at 1017: devices 0 (1000) and 1 (1017) should fire in order.
        router.beforeInstruction(1017L)
        assertEquals(1, devices[0].tickedCount)
        assertEquals(1, devices[1].tickedCount)
        assertEquals(0, devices[2].tickedCount)
        assertEquals(1034L, router.refreshNextScheduledTick())
    }
}
