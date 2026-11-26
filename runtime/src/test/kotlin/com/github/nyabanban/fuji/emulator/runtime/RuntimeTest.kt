package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.*

class RuntimeTest {
    @Test fun nativePageRangesMergeLargeAperturesAndRetainPreciseHoles() {
        val device = object : MmioDevice { override fun read(offset: Long, size: Int) = 7L; override fun write(offset: Long, size: Int, value: Long) {} }
        val router = MmioRouter(listOf(MmioWindow("image", 0xf0000000, 0x08000000, device), MmioWindow("a", 0x2004, 4, device), MmioWindow("b", 0x2100, 4, device), MmioWindow("c", 0x3000, 4, device)))
        assertEquals(listOf(0x2000L to 0x2000L, 0xf0000000L to 0x08000000L), router.pageRanges())
        assertEquals(7L, router.read(0x2004, 4)); assertFails { router.read(0x2008, 4) }; assertFails { router.read(0x2ffc, 4) }
    }
    @Test fun clocksUseExplicitOrderDeduplicateIdentityAndStopBeforeLaterDevices() {
        val calls = mutableListOf<String>()
        fun port(name: String, priority: Int, fail: Boolean = false) = object : MmioDevice {
            override val clockOrder = priority
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun beforeInstruction(tick: Long) { calls += name; if (fail) error("strict timer expiry") }
        }
        val dma = port("dma", 10)
        val router = MmioRouter(listOf(MmioWindow("config", 0x100, 4, port("config", 20)), MmioWindow("dma", 0x400, 4, dma), MmioWindow("dma-alias", 0x500, 4, dma), MmioWindow("timer", 0x900, 4, port("timer", 0))))
        router.beforeInstruction(1); assertEquals(listOf("timer", "dma", "config"), calls)
        calls.clear()
        val strict = MmioRouter(listOf(MmioWindow("config", 0x100, 4, port("config", 20)), MmioWindow("dma", 0x400, 4, dma), MmioWindow("timer", 0x900, 4, port("timer", 0, true))))
        assertFails { strict.beforeInstruction(1) }; assertEquals(listOf("timer"), calls)
    }
    @Test fun ownerQueuePreservesOrderAndRejectsOverflowAndClosedSubmission() {
        val owner = OwnerLoop(1); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val first = owner.submit { started.countDown(); release.await(); 1 }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = owner.submit { owner.requireOwner(); 2 }
        assertTrue(owner.submit { 3 }.isCompletedExceptionally)
        release.countDown(); assertEquals(1, first.join()); assertEquals(2, second.join())
        owner.close(); assertTrue(owner.submit { 4 }.isCompletedExceptionally)
    }
    @Test fun parserRefusesDuplicatesAndPathEscape() {
        val root = Files.createTempDirectory("fuji-config")
        try {
            val path = root.resolve("bad.yaml"); Files.writeString(path, "charge: 1\ncharge: 2\n")
            assertFails { Formats.read(path) }
            assertFails { containedPath(root, "../escape.img") }
            assertEquals(root.toRealPath().resolve("inside.img"), containedPath(root, "inside.img"))
            Files.writeString(path, "data: " + "x".repeat(65537)); assertFails { Formats.read(path) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun typedRouterRejectsGapsOverlapWidthAndCrossWindow() {
        val device = object : MmioDevice {
            override fun read(offset: Long, size: Int) = offset + size
            override fun write(offset: Long, size: Int, value: Long) {}
        }
        val router = MmioRouter(listOf(MmioWindow("test", 0x100, 16, device)))
        assertEquals(8L, router.read(0x104, 4)); assertFails { router.read(0x10f, 4) }; assertFails { router.read(0x110, 4) }
        assertFails { MmioRouter(listOf(MmioWindow("a", 0, 16, device), MmioWindow("b", 8, 16, device))) }
        assertFails { router.read(0x100, 3) }
    }
    @Test fun dumpManifestHashesMemoryAndRejectsOversizedOrUnsafeNames() {
        val root = Files.createTempDirectory("fuji-dump")
        try {
            val path = DebugDump.write(root, mapOf("tick" to 17), mapOf("synthetic" to byteArrayOf(1, 2, 3)))
            ZipFile(path.toFile()).use { zip ->
                val manifest = Formats.json.readTree(zip.getInputStream(zip.getEntry("manifest.json")))
                assertEquals(17, manifest["state"]["tick"].asInt())
                assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", manifest["memory"]["synthetic"]["sha256"].asText())
                assertContentEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("memory/synthetic.bin")).readAllBytes())
            }
            assertFails { DebugDump.write(root, emptyMap(), mapOf("../unsafe" to byteArrayOf())) }
            assertFails { DebugDump.write(root, emptyMap(), mapOf("large" to ByteArray(16 * 1024 * 1024 + 1))) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun tickAwareDeviceTracksDynamicDeadlinesAndAlwaysTickSync() {
        var nextDeadline: Long? = null
        var interestedTick = Long.MAX_VALUE
        var syncedIrq = false
        val tickCalls = mutableListOf<Long>()
        val scheduledCalls = mutableListOf<Long>()
        val syncIrqDevice = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {
                if (value == 1L) interestedTick = 2L
                else if (value == 2L) nextDeadline = 7L
                else { syncedIrq = true; interestedTick = 4L }
            }
            override fun nextDeadline() = nextDeadline
            override fun nextInterestedTick() = minOf(interestedTick, nextDeadline ?: Long.MAX_VALUE)
            override fun beforeInstruction(tick: Long) {
                scheduledCalls += tick
                if (nextDeadline != null && tick >= nextDeadline!!) nextDeadline = null
                if (syncedIrq && tick >= 4L) { syncedIrq = false; interestedTick = Long.MAX_VALUE }
                if (interestedTick == tick) interestedTick = Long.MAX_VALUE
            }
        }
        val alwaysDevice = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = null
            override fun nextInterestedTick(): Long = Long.MAX_VALUE
            override fun requiresEveryTick() = true
            override fun beforeInstruction(tick: Long) { tickCalls += tick }
        }
        val noopDevice = object : MmioDevice {
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
        }
        val router = MmioRouter(listOf(
            MmioWindow("sync", 0x100, 4, syncIrqDevice),
            MmioWindow("always", 0x200, 4, alwaysDevice),
            MmioWindow("noop", 0x300, 4, noopDevice),
        ))
        router.beforeInstruction(0)
        assertEquals(listOf(0L), tickCalls)
        assertEquals(emptyList(), scheduledCalls) // idle tick skipped (nextInterestedTick == MAX_VALUE)
        router.beforeInstruction(1) // idle tick skipped for syncIrqDevice
        assertEquals(emptyList(), scheduledCalls)
        router.write(0x100, 4, 1L)
        router.beforeInstruction(2)
        assertEquals(listOf(2L), scheduledCalls)
        router.beforeInstruction(3)
        assertEquals(listOf(2L), scheduledCalls)
        router.write(0x100, 4, 3L) // nextDeadline() is null, still schedules IRQ sync at tick 4
        router.beforeInstruction(4)
        assertEquals(listOf(2L, 4L), scheduledCalls)
        router.write(0x100, 4, 2L) // schedules deadline 7; min-heap skips 5 and 6, fires 7
        router.beforeInstruction(5)
        assertEquals(listOf(2L, 4L), scheduledCalls)
        router.beforeInstruction(6)
        assertEquals(listOf(2L, 4L), scheduledCalls)
        router.beforeInstruction(7)
        assertEquals(listOf(2L, 4L, 7L), scheduledCalls)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), tickCalls)
    }
    @Test fun ownerDrainQueuedExecutesFifoWithoutDeadlockAndPreservesHasPending() {
        val owner = OwnerLoop(8)
        val ran = mutableListOf<String>()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val batch = owner.submit {
            entered.countDown()
            assertTrue(gate.await(5, TimeUnit.SECONDS))
            assertTrue(owner.hasPending())
            owner.drainQueued()
            assertFalse(owner.hasPending())
            ran += "after-drain"
            drained.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val q1 = owner.submit { ran += "q1"; 10 }
        val q2 = owner.submit { ran += "q2"; 20 }
        assertTrue(owner.hasPending())
        gate.countDown()
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        batch.join()
        assertEquals(10, q1.join())
        assertEquals(20, q2.join())
        assertEquals(listOf("q1", "q2", "after-drain"), ran)
        assertTrue(owner.isRunning)
        owner.close()
        assertFalse(owner.isRunning)
    }
    @Test fun pureMmioReadsKeepScheduleCleanWhileWriteAndMutatingReadDirtyIt() {
        var interestedQueries = 0
        val pureScheduled = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 42L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = 100L
            override fun nextInterestedTick(): Long { interestedQueries++; return 100L }
            override fun readMayMutateSchedule(): Boolean = false
        }
        val plain = object : MmioDevice {
            override fun read(offset: Long, size: Int) = 7L
            override fun write(offset: Long, size: Int, value: Long) {}
        }
        val mutatingRead = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 9L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = null
            override fun nextInterestedTick(): Long = Long.MAX_VALUE
        }
        val router = MmioRouter(listOf(
            MmioWindow("pure-sched", 0x100, 4, pureScheduled),
            MmioWindow("plain", 0x200, 4, plain),
            MmioWindow("mut-sched", 0x300, 4, mutatingRead),
        ))
        assertEquals(100L, router.refreshNextScheduledTick())
        assertEquals(1, interestedQueries)
        // Pure reads must NOT dirty the schedule or trigger re-scanning nextInterestedTick()
        assertEquals(42L, router.read(0x100, 4))
        assertEquals(7L, router.read(0x200, 4))
        assertEquals(100L, router.nextScheduledTickIfClean())
        assertEquals(100L, router.refreshNextScheduledTick())
        assertEquals(1, interestedQueries)
        // Conservative scheduled device read (readMayMutateSchedule == true) DOES dirty schedule
        assertEquals(9L, router.read(0x300, 4))
        assertEquals(0L, router.nextScheduledTickIfClean())
        assertEquals(100L, router.refreshNextScheduledTick())
        assertEquals(2, interestedQueries)
        // Writes always dirty schedule
        router.write(0x200, 4, 1L)
        assertEquals(0L, router.nextScheduledTickIfClean())
        assertEquals(100L, router.refreshNextScheduledTick())
        assertEquals(3, interestedQueries)
    }

    @Test fun minHeapSchedulerPreservesClockOrderSkipsIdleDevicesAndIsolatesSelfContainedWrites() {
        val fired = mutableListOf<String>()
        var timerDeadline = 10L
        var timerQueries = 0
        var idleQueries = 0
        var peerQueries = 0
        var peerDeadline = Long.MAX_VALUE
        var crossDeviceDeadline = 10L

        val timer = object : TickAwareMmioDevice {
            override val clockOrder = 10
            override val clockIndex = 1
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) { timerDeadline = value }
            override fun readMayMutateSchedule() = false
            override fun nextDeadline(): Long? = timerDeadline.takeIf { it != Long.MAX_VALUE }
            override fun nextInterestedTick(): Long { timerQueries++; return timerDeadline }
            override fun beforeInstruction(tick: Long) {
                fired += "timer@$tick"
                timerDeadline = tick + 100L
            }
        }
        val earlySameTick = object : TickAwareMmioDevice {
            override val clockOrder = 5
            override val clockIndex = 0
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = crossDeviceDeadline.takeIf { it != Long.MAX_VALUE }
            override fun nextInterestedTick(): Long = crossDeviceDeadline
            override fun beforeInstruction(tick: Long) {
                fired += "early@$tick"
                crossDeviceDeadline = Long.MAX_VALUE
                // Cross-device tick arms peer for the same tick; router must still fire peer after early!
                peerDeadline = tick
            }
        }
        val peer = object : TickAwareMmioDevice {
            override val clockOrder = 30
            override val clockIndex = 0
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = peerDeadline.takeIf { it != Long.MAX_VALUE }
            override fun nextInterestedTick(): Long { peerQueries++; return peerDeadline }
            override fun beforeInstruction(tick: Long) {
                fired += "peer@$tick"
                peerDeadline = Long.MAX_VALUE
            }
        }
        val idle = object : TickAwareMmioDevice {
            override val clockOrder = 50
            override val clockIndex = 0
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = null
            override fun nextInterestedTick(): Long { idleQueries++; return Long.MAX_VALUE }
            override fun beforeInstruction(tick: Long) { fired += "idle@$tick" }
        }
        val nonScheduling = object : MmioDevice {
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
        }

        val router = MmioRouter(listOf(
            MmioWindow("soc-timer", 0x100, 4, timer),
            MmioWindow("soc-dma", 0x200, 4, earlySameTick),
            MmioWindow("soc-spi", 0x300, 4, peer),
            MmioWindow("soc-adc", 0x400, 4, idle),
            MmioWindow("soc-system-control", 0x500, 4, nonScheduling),
        ))

        assertEquals(10L, router.refreshNextScheduledTick())
        assertEquals(1, timerQueries)
        assertEquals(1, idleQueries)
        assertEquals(1, peerQueries)

        // Writing to pure non-scheduling device (soc-system-control) must NOT dirty schedule at all.
        router.write(0x500, 4, 1L)
        assertEquals(10L, router.nextScheduledTickIfClean())
        assertEquals(10L, router.refreshNextScheduledTick())
        assertEquals(1, timerQueries)
        assertEquals(1, idleQueries)

        // Writing to self-contained soc-timer must ONLY re-query soc-timer, NOT idle sibling devices!
        router.write(0x100, 4, 10L)
        assertEquals(10L, router.refreshNextScheduledTick())
        assertEquals(2, timerQueries)
        assertEquals(1, idleQueries)
        assertEquals(1, peerQueries)

        // Tick 10 fires early (order 5) -> timer (order 10) -> dynamically armed peer (order 30); never fires idle!
        router.beforeInstruction(10L)
        assertEquals(listOf("early@10", "timer@10", "peer@10"), fired)
        assertEquals(110L, router.refreshNextScheduledTick())

        // Self-contained timer-only expiry at tick 110 must NOT touch idle or peer!
        val idleBefore110 = idleQueries
        val peerBefore110 = peerQueries
        router.beforeInstruction(110L)
        assertEquals(listOf("early@10", "timer@10", "peer@10", "timer@110"), fired)
        assertEquals(idleBefore110, idleQueries)
        assertEquals(peerBefore110, peerQueries)
        assertEquals(210L, router.refreshNextScheduledTick())

        // DMA-family write (soc-dma) must dirty only DMA-family devices (soc-dma/soc-spi), NOT soc-timer or soc-adc!
        val timerBeforeDma = timerQueries
        val idleBeforeDma = idleQueries
        val peerBeforeDma = peerQueries
        router.write(0x200, 4, 1L)
        assertEquals(210L, router.refreshNextScheduledTick())
        assertEquals(timerBeforeDma, timerQueries)
        assertEquals(idleBeforeDma, idleQueries)
        assertEquals(peerBeforeDma + 1, peerQueries)

        // Pure-read soc-isp window must NOT dirty schedule even when inheriting ScheduledMmioDevice.readMayMutateSchedule()==true
        var ispQueries = 0
        val ispPort = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 0x55L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = null
            override fun nextInterestedTick(): Long { ispQueries++; return Long.MAX_VALUE }
        }
        val ispRouter = MmioRouter(listOf(MmioWindow("soc-isp", 0x600, 4, ispPort)))
        assertEquals(Long.MAX_VALUE, ispRouter.refreshNextScheduledTick())
        assertEquals(1, ispQueries)
        assertEquals(0x55L, ispRouter.read(0x600, 4))
        assertEquals(Long.MAX_VALUE, ispRouter.nextScheduledTickIfClean())
        assertEquals(Long.MAX_VALUE, ispRouter.refreshNextScheduledTick())
        assertEquals(1, ispQueries)
    }

    @Test fun directEventsDispatchesZeroAllocIrqLineListenersWithoutBreakingGeneralSubscribers() {
        val owner = OwnerLoop(4)
        try {
            val events = DirectEvents(owner)
            val fastLines = mutableListOf<Triple<Long, Int, Boolean>>()
            val nonIrqKinds = mutableListOf<String>()
            val allKinds = mutableListOf<String>()
            val s1 = events.subscribeIrqLine { tick, line, high -> fastLines += Triple(tick, line, high) }
            val s2 = events.subscribeNonIrqLine { nonIrqKinds += it.kind }
            owner.submit {
                events.publishIrqLine("soc-gic", 50L, 40, true)
                events.publishIrqLine("soc-gic", 50L, 40, false)
                events.publish(ComponentEvent("soc-configuration", "configuration", 51L, emptyMap()))
            }.join()
            assertEquals(listOf(Triple(50L, 40, true), Triple(50L, 40, false)), fastLines)
            assertEquals(listOf("configuration"), nonIrqKinds)

            val s3 = events.subscribe { allKinds += "${it.kind}:${it.values["line"]}:${it.values["high"]}" }
            owner.submit {
                events.publishIrqLine("soc-gic", 60L, 106, true)
            }.join()
            assertEquals(listOf("irq-line:106:true"), allKinds)
            s1.close(); s2.close(); s3.close()
        } finally {
            owner.close()
        }
    }
}
