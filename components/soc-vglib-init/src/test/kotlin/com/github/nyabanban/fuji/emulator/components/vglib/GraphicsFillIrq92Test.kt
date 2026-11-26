package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class GraphicsFillIrq92Test {
    private class Fixture(val samples: List<Long> = GraphicsFillIrq92.samples, val copy: Boolean = false, sequence: String = "", fillLimit: Int = 2, accessLimit: Int = 64, fillDeadline: Long = 42L, copyDeadline: Long = 60L) {
        val host = LcdcFunctionalTest.Context()
        var line = EdgeInterruptState(false, false, false)
        val transitions = mutableListOf<Boolean>()
        private val context = object : ComponentContext by host {
            override fun readData(relative: String) = if (relative != "graphics-fill.yaml") host.readData(relative)
            else mapOf("profile" to GraphicsFillTail.PROFILE, "initialWords" to listOf(0,0,0,0),
                "statusReads" to samples, "accessLimit" to 16, "execution" to mapOf(
                    "profile" to GraphicsFillExecution.PROFILE, "surfaceBase" to 128,
                    "surfaceBytes" to 4, "submissionLimit" to fillLimit)) + if (copy) mapOf(
                        "accessLimit" to accessLimit, "copyInitialWords" to List(9) { 0 },
                        "copyExecution" to mapOf("profile" to GraphicsCopyExecution.PROFILE, "sourceBase" to 0x100000,
                            "destinationBase" to 0x500000, "surfaceBytes" to 0x300000, "submissionLimit" to 2)) else emptyMap()
        }
        init {
            host.entries[GraphicsFillIrq92.SINK] = object : EdgeInterruptSink {
                override fun edgeState(irq: Int) = line.also { assertEquals(92, irq) }
                override fun setLine(irq: Int, high: Boolean) {
                    assertEquals(92, irq)
                    transitions += high
                    line = line.copy(high = high)
                }
            }
            host.entries["cpu.memory"] = object : DmaMemory {
                val pixels = ByteArray(4)
                val copyPixels = if (copy) ByteArray(0x300000) else ByteArray(0)
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(address == 128L && size == 4L || copy && size == 0x300000L &&
                        (address == 0x500000L || address == 0x100000L && !write))
                }
                override fun write(address: Long, source: ByteBuffer) { source.get(if (address == 128L) pixels else copyPixels) }
                override fun read(address: Long, destination: ByteBuffer) { destination.put(if (address == 128L) pixels else copyPixels) }
            }
        }
        val device = VglibInitFactory().create(context, mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank", GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE, GraphicsFillIrq92.SETTING to fillDeadline) +
            (if (copy) mapOf(GraphicsCopyIrq92.SETTING to copyDeadline) else emptyMap()) +
            (if (sequence.isEmpty()) emptyMap() else mapOf(GraphicsIrq92Sequence.SETTING to sequence)))
        val bank = host.entries.getValue("soc-vglib-init.registers") as ScheduledMmioDevice
        val snapshots = device as StatefulComponent
        fun fill() {
            repeat(18) { bank.read(0,4); bank.write(0,4,0) }
            bank.write(16,4,128)
            bank.write(20,4,132)
            bank.write(28,4,GraphicsFillExecution.CONTROL)
        }
        fun fire() {
            host.tick = 42
            bank.beforeInstruction(host.tick)
        }
        fun prefix() {
            fill()
            fire()
            acknowledge(28, 0x04000000)
            host.tick = 55
            listOf(0xc00 to 0x100000, 0xc04 to 0x500000, 0xc08 to 0x03000400, 0xc0c to 0x03000400,
                0xc10 to 1, 0xc18 to 1).forEach { (offset,value) -> bank.write(offset.toLong(),4,value.toLong()) }
            host.tick = 60
            bank.beforeInstruction(60)
            acknowledge(0xc18, 0x40000000)
        }
        fun acknowledge(control: Long, bit: Long) {
            assertEquals(bit, bank.read(52, 4))
            bank.write(control, 4, 0)
            assertEquals(0L, bank.read(52, 4))
        }
    }

    @Test fun sequenceAdoptsOnlyOneOutstandingReceiptAndPreservesConsumedPrefix() {
        val old = Fixture(copy = true)
        old.prefix()
        old.host.tick = 64
        old.bank.write(28, 4, GraphicsFillExecution.CONTROL)
        val checkpoint = old.snapshots.saveSnapshot()
        val originalTail = checkpoint["graphicsFill"]
        val branch = Fixture(copy = true, sequence = "fill")
        branch.host.tick = 80
        branch.snapshots.restoreSnapshot(checkpoint)
        assertEquals(originalTail, branch.snapshots.saveSnapshot()["graphicsFill"])
        assertEquals(81L, branch.bank.nextDeadline())
        branch.bank.beforeInstruction(80)
        assertTrue(branch.transitions.isEmpty())
        val armed = branch.snapshots.saveSnapshot()
        branch.snapshots.restoreSnapshot(armed)
        assertFails { branch.bank.write(0xc18, 4, 1) }
        assertFails { branch.bank.write(28, 4, 0) }
        branch.host.tick++
        branch.line = branch.line.copy(pending = true)
        assertFails { branch.bank.beforeInstruction(81) }
        assertEquals(armed, branch.snapshots.saveSnapshot())
        branch.line = branch.line.copy(pending = false)
        branch.bank.beforeInstruction(81)
        assertEquals(listOf(true), branch.transitions)
        branch.acknowledge(28, 0x04000000)
        val spent = branch.snapshots.saveSnapshot()
        assertFails { branch.bank.write(0xc18, 4, 1) }
        assertFails { branch.snapshots.restoreSnapshot(spent + ("irq92Successors" to emptyList<Any>())) }
        assertEquals(spent, branch.snapshots.saveSnapshot())
        val extended = Fixture(copy = true, sequence = "fill,copy")
        extended.host.tick = 82
        extended.snapshots.restoreSnapshot(spent)
        assertNull(extended.bank.nextDeadline())
        extended.bank.write(0xc18, 4, 1)
        assertEquals(83L, extended.bank.nextDeadline())
        extended.host.tick = 83
        extended.bank.beforeInstruction(83)
        extended.snapshots.restoreSnapshot(extended.snapshots.saveSnapshot())
        extended.acknowledge(0xc18, 0x40000000)
        extended.snapshots.restoreSnapshot(extended.snapshots.saveSnapshot())
        assertEquals(listOf(true, false), extended.transitions)
        assertFails { extended.bank.read(52, 4) }
        assertNull(extended.bank.nextDeadline())
        assertFails { branch.snapshots.restoreSnapshot(extended.snapshots.saveSnapshot()) }
        old.bank.write(0xc18, 4, 1)
        assertFails { extended.snapshots.restoreSnapshot(old.snapshots.saveSnapshot()) }
    }

    @Test fun eitherResolvesFromTheReceiptAndOnlyUnconsumedExactSlotsCanBeWidened() {
        assertFails { Fixture(copy = true, sequence = "either,either,either") }
        val strict = Fixture(copy = true, sequence = "copy,fill")
        strict.prefix()
        val prefix = strict.snapshots.saveSnapshot()
        val branch = Fixture(copy = true, sequence = "either,either")
        branch.host.tick = 60
        branch.snapshots.restoreSnapshot(prefix)
        branch.bank.write(20,4,136)
        val invalid = branch.snapshots.saveSnapshot()
        assertFails { branch.bank.write(28,4,GraphicsFillExecution.CONTROL) }
        assertEquals(invalid, branch.snapshots.saveSnapshot())
        branch.bank.write(20,4,132)
        branch.bank.write(28,4,GraphicsFillExecution.CONTROL)
        val armed = branch.snapshots.saveSnapshot()
        val row = (armed["irq92Successors"] as List<*>).single() as Map<*, *>
        assertEquals("fill", row["operation"])
        for (forged in listOf(row - "operation", row + ("operation" to "copy"))) {
            assertFails { branch.snapshots.restoreSnapshot(armed + ("irq92Successors" to listOf(forged))) }
            assertEquals(armed, branch.snapshots.saveSnapshot())
        }
        branch.snapshots.restoreSnapshot(armed)
        assertFails { branch.bank.write(0xc18,4,1) }
        branch.host.tick = 61
        branch.bank.beforeInstruction(61)
        branch.acknowledge(28,0x04000000)
        branch.bank.write(0xc18,4,1)
        branch.host.tick = 62
        branch.bank.beforeInstruction(62)
        assertEquals(0x40000000L, branch.bank.read(52,4))
        val awaitingClear = branch.snapshots.saveSnapshot()
        branch.snapshots.restoreSnapshot(awaitingClear)
        branch.bank.write(28,4,0)
        assertFails { branch.bank.read(52,4) }
        branch.bank.write(0xc18,4,0)
        assertEquals(0L, branch.bank.read(52,4))
        assertEquals(listOf(true,false,true,false), branch.transitions)
        val spent = branch.snapshots.saveSnapshot()
        branch.snapshots.restoreSnapshot(spent)
        assertFails { branch.bank.write(28,4,GraphicsFillExecution.CONTROL) }
        assertEquals(spent, branch.snapshots.saveSnapshot())
        strict.bank.write(0xc18,4,1)
        assertFails { branch.snapshots.restoreSnapshot(strict.snapshots.saveSnapshot()) }
        assertEquals(spent, branch.snapshots.saveSnapshot())
    }

    @Test fun restoresLegacySelectionAndRetainsEveryReceiptAcross128Successors() {
        val old = Fixture(copy = true, sequence = "either,either", fillLimit = 16, accessLimit = 512)
        old.prefix()
        fun submitFill(fixture: Fixture) {
            fixture.bank.write(16,4,128)
            fixture.bank.write(20,4,132)
            fixture.bank.write(24,4,fixture.host.tick)
            fixture.bank.write(28,4,GraphicsFillExecution.CONTROL)
            fixture.host.tick++
            fixture.bank.beforeInstruction(fixture.host.tick)
            fixture.acknowledge(28,0x04000000)
        }
        submitFill(old)
        val prefix = old.snapshots.saveSnapshot()
        val expanded = Fixture(copy = true, sequence = "either*128", fillLimit = 129, accessLimit = 4096)
        expanded.host.tick = old.host.tick
        expanded.snapshots.restoreSnapshot(prefix)
        repeat(127) { submitFill(expanded) }
        val spent = expanded.snapshots.saveSnapshot()
        val tail = spent["graphicsFill"] as Map<*, *>
        assertEquals(129, (tail["readbacks"] as List<*>).size)
        assertEquals(128, (spent["irq92Successors"] as List<*>).size)
        assertTrue((tail["accesses"] as List<*>).size > 512)
        val restored = Fixture(copy = true, sequence = "either*64,either*64", fillLimit = 129, accessLimit = 4096)
        restored.host.tick = expanded.host.tick
        restored.snapshots.restoreSnapshot(spent)
        val before = restored.snapshots.saveSnapshot()
        assertFails { restored.snapshots.restoreSnapshot(spent + ("graphicsFill" to tail + ("accessLimit" to 512))) }
        assertFails { restored.bank.write(0xc18,4,1) }
        assertEquals(before, restored.snapshots.saveSnapshot())
        assertEquals(spent["irq92Successors"], before["irq92Successors"])
        assertEquals(tail, before["graphicsFill"])
    }

    @Test fun sequenceChecksOrderAndFailedDmaBeforeArming() {
        assertFails { Fixture(copy = true, sequence = "fill,fill") }
        assertFails { Fixture(copy = true, sequence = "skip") }
        assertFails { Fixture(sequence = "fill") }
        val selected = Fixture(copy = true, sequence = "fill")
        val incomplete = Fixture(copy = true)
        incomplete.fill()
        assertFails { selected.snapshots.restoreSnapshot(incomplete.snapshots.saveSnapshot()) }
        selected.prefix()
        assertFails { selected.bank.write(0xc18, 4, 1) }
        selected.bank.write(20, 4, 136)
        val before = selected.snapshots.saveSnapshot()
        assertFails { selected.bank.write(28, 4, GraphicsFillExecution.CONTROL) }
        assertEquals(before, selected.snapshots.saveSnapshot())
        assertNull(selected.bank.nextDeadline())
        selected.bank.write(20, 4, 132)
        selected.bank.write(28, 4, GraphicsFillExecution.CONTROL)
        assertEquals(61L, selected.bank.nextDeadline())
    }

    @Test fun consecutiveFillsEachRequireTheirOwnReceiptAndFinalZero() {
        val fixture = Fixture(copy = true, sequence = "fill,fill", fillLimit = 3)
        fixture.prefix()
        repeat(2) {
            fixture.bank.write(28, 4, GraphicsFillExecution.CONTROL)
            fixture.host.tick++
            fixture.bank.beforeInstruction(fixture.host.tick)
            assertEquals(0x04000000L, fixture.bank.read(52, 4))
            fixture.snapshots.restoreSnapshot(fixture.snapshots.saveSnapshot())
            fixture.bank.write(28, 4, 0)
            assertFails { fixture.bank.write(28, 4, GraphicsFillExecution.CONTROL) }
            fixture.snapshots.restoreSnapshot(fixture.snapshots.saveSnapshot())
            assertEquals(0L, fixture.bank.read(52, 4))
        }
        fixture.snapshots.restoreSnapshot(fixture.snapshots.saveSnapshot())
        assertNull(fixture.bank.nextDeadline())
        assertEquals(List(4) { listOf(true, false) }.flatten(), fixture.transitions)
    }

    @Test fun postponesOnlyBeforeOriginalDeadlineAndFirstSubmission() {
        val original = Fixture(copy = true)
        val before = original.snapshots.saveSnapshot()
        val pair = Fixture(copy = true, fillDeadline = 70L, copyDeadline = 80L)
        pair.snapshots.restoreSnapshot(before)
        assertEquals(70L, pair.bank.nextDeadline())
        val paired = pair.snapshots.saveSnapshot()
        pair.snapshots.restoreSnapshot(paired)
        assertEquals(paired, pair.snapshots.saveSnapshot())
        assertFails { Fixture(copy = true, copyDeadline = 80L).snapshots.restoreSnapshot(before) }
        assertFails { Fixture(copy = true, fillDeadline = 45L, copyDeadline = 59L).snapshots.restoreSnapshot(before) }
        fun branch(tick: Long = 41, deadline: Long = 48) = Fixture(copy = true, fillDeadline = deadline).also { it.host.tick = tick }
        val later = branch()
        later.snapshots.restoreSnapshot(before)
        assertEquals(48L, later.bank.nextDeadline())
        val postponed = later.snapshots.saveSnapshot()
        assertEquals(before - "settings" - "fillIrq92", postponed - "settings" - "fillIrq92")
        val resumed = branch()
        resumed.snapshots.restoreSnapshot(postponed)
        assertEquals(postponed, resumed.snapshots.saveSnapshot())
        resumed.bank.beforeInstruction(42)
        assertTrue(resumed.transitions.isEmpty())
        assertFails { resumed.bank.beforeInstruction(48) }
        assertEquals(postponed, resumed.snapshots.saveSnapshot())
        assertFails { branch(42).snapshots.restoreSnapshot(before) }
        assertFails { branch(38, 40).snapshots.restoreSnapshot(before) }
        val mismatched = (before.getValue("fillIrq92") as Map<*, *>) + ("deadline" to 40L)
        assertFails { later.snapshots.restoreSnapshot(before + ("fillIrq92" to mismatched)) }
        original.fill()
        assertFails { later.snapshots.restoreSnapshot(original.snapshots.saveSnapshot()) }
        original.fire()
        assertFails { later.snapshots.restoreSnapshot(original.snapshots.saveSnapshot()) }
        assertEquals(postponed, later.snapshots.saveSnapshot())
        later.fill()
        later.host.tick = 48
        later.bank.beforeInstruction(48)
        later.acknowledge(28, 0x04000000)
        assertEquals(listOf(true, false), later.transitions)
        later.snapshots.restoreSnapshot(later.snapshots.saveSnapshot())
    }

    @Test fun requiresCompletedFillFiniteStatusAndIdleLevelLine() {
        assertFails { Fixture(listOf(0L)) }
        val early = Fixture()
        assertFails { early.fire() }
        assertTrue(early.transitions.isEmpty())
        val ready = Fixture()
        ready.fill()
        assertFails { ready.bank.read(52,4) }
        ready.bank.beforeInstruction(41)
        for (line in listOf(EdgeInterruptState(true,false,false), EdgeInterruptState(false,true,false), EdgeInterruptState(false,false,true))) {
            ready.line = line
            assertFails { ready.fire() }
        }
        assertTrue(ready.transitions.isEmpty())
        assertEquals(42L, ready.bank.nextDeadline())
    }

    @Test fun restoresAssertedAndSpentLevelAndChecksGuestClearOrder() {
        val start = Fixture()
        start.fill()
        start.fire()
        assertEquals(listOf(true), start.transitions)
        assertNull(start.bank.nextDeadline())
        val asserted = start.snapshots.saveSnapshot()
        val resumed = Fixture()
        resumed.host.tick = 43
        resumed.line = start.line
        resumed.snapshots.restoreSnapshot(asserted)
        resumed.bank.beforeInstruction(43)
        assertFails { resumed.bank.write(28,4,0) }
        assertEquals(0x04000000L, resumed.bank.read(52,4))
        assertFails { resumed.bank.read(52,4) }
        assertFails { resumed.bank.write(28,2,0) }
        assertTrue(resumed.transitions.isEmpty())
        resumed.bank.write(28,4,0)
        assertEquals(0L, resumed.bank.read(52,4))
        assertFails { resumed.bank.read(52,4) }
        assertEquals(listOf(false), resumed.transitions)
        val spent = resumed.snapshots.saveSnapshot()
        val savedIrq = spent.getValue("fillIrq92") as Map<*, *>
        assertFails { resumed.snapshots.restoreSnapshot(spent + ("fillIrq92" to savedIrq + ("cleared" to false))) }
        resumed.snapshots.restoreSnapshot(spent)
        resumed.bank.beforeInstruction(44)
        assertEquals(spent, resumed.snapshots.saveSnapshot())
        assertEquals(listOf(false), resumed.transitions)
    }

    @Test fun secondLevelRequiresCopyReceiptAndC18ClearAndRestoresOnce() {
        val fixture = Fixture(copy = true)
        fixture.fill()
        fixture.fire()
        fixture.bank.read(52,4)
        fixture.bank.write(28,4,0)
        fixture.bank.read(52,4)
        assertFails { fixture.bank.beforeInstruction(60) }
        fixture.host.tick = 55
        listOf(0xc00 to 0x100000, 0xc04 to 0x500000, 0xc08 to 0x03000400, 0xc0c to 0x03000400,
            0xc10 to 1, 0xc18 to 1).forEach { (offset,value) -> fixture.bank.write(offset.toLong(),4,value.toLong()) }
        fixture.host.tick = 60
        fixture.bank.beforeInstruction(60)
        assertEquals(listOf(true,false,true), fixture.transitions)
        assertFails { fixture.bank.read(52,2) }
        assertEquals(0x40000000L,fixture.bank.read(52,4))
        assertFails { fixture.bank.read(52,4) }
        val pending = fixture.snapshots.saveSnapshot()
        val restored = Fixture(copy = true)
        restored.host.tick = 61
        restored.line = fixture.line
        restored.snapshots.restoreSnapshot(pending)
        restored.bank.beforeInstruction(61)
        restored.bank.write(28,4,0)
        assertFails { restored.bank.write(0xc18,2,0) }
        assertTrue(restored.transitions.isEmpty())
        restored.bank.write(0xc18,4,0)
        assertEquals(0L,restored.bank.read(52,4))
        assertFails { restored.bank.read(52,4) }
        val spent = restored.snapshots.saveSnapshot()
        val irq = spent.getValue("copyIrq92") as Map<*, *>
        assertFails { restored.snapshots.restoreSnapshot(spent + ("copyIrq92" to irq + ("clearedAt" to null))) }
        restored.snapshots.restoreSnapshot(spent)
        assertNull(restored.bank.nextDeadline())
        assertEquals(listOf(false),restored.transitions)
    }

    @Test fun autoIrq92DelaySchedulesDynamicCommandCompletionsAcrossRestore() {
        val heapBase = 0x06fe6000L
        val heapBytes = 0x10000L
        val targetBase = 0x06140000L
        val surfaceBytes = 0x300000L
        val vAddr = heapBase + 0x1000L
        val mAddr = heapBase + 0x2000L
        val heap = ByteArray(heapBytes.toInt())
        val vWords = intArrayOf(
            0x85f004, 0, 0x5f004, 1, 0x3f004, 0,
            0x80f026, 0x07c3e000, 0, 0x200, 0x9601000, 0,
            0x6f004, 1, 0x85f004, 1
        )
        val mWords = intArrayOf(0xa6f004, 0, 0x101004, 1, 0xa6f004, 1)
        val vb = ByteBuffer.allocate(vWords.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { vWords.forEach(::putInt) }
        val mb = ByteBuffer.allocate(mWords.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { mWords.forEach(::putInt) }
        vb.array().copyInto(heap, 0x1000)
        mb.array().copyInto(heap, 0x2000)

        fun buildFixture(autoDelay: Long? = null, staticDeadline: Long? = null): Triple<LcdcFunctionalTest.Context, ScheduledMmioDevice, StatefulComponent> {
            val host = LcdcFunctionalTest.Context()
            var line = EdgeInterruptState(false, false, false)
            host.entries[GraphicsFillIrq92.SINK] = object : EdgeInterruptSink {
                override fun edgeState(irq: Int) = line.also { assertEquals(92, irq) }
                override fun setLine(irq: Int, high: Boolean) {
                    assertEquals(92, irq)
                    line = line.copy(high = high)
                }
            }
            host.entries["cpu.memory"] = object : DmaMemory {
                val fillPixels = ByteArray(4)
                val copyPixels = ByteArray(surfaceBytes.toInt())
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(
                        address == 128L && size == 4L ||
                            size == surfaceBytes && (address == targetBase || address == 0x06893000L) ||
                            !write && address >= heapBase && address - heapBase <= heap.size - size
                    )
                }
                override fun write(address: Long, source: ByteBuffer) {
                    if (address == 128L) source.get(fillPixels) else {
                        require(address == targetBase || address == 0x06893000L)
                        source.get(copyPixels)
                    }
                }
                override fun read(address: Long, destination: ByteBuffer) {
                    when {
                        address == 128L -> destination.put(fillPixels)
                        address == targetBase || address == 0x06893000L -> destination.put(copyPixels)
                        else -> destination.put(heap, (address - heapBase).toInt(), destination.remaining())
                    }
                }
            }
            val ctx = object : ComponentContext by host {
                override fun readData(relative: String) = if (relative != "graphics-fill.yaml") host.readData(relative)
                else mapOf(
                    "profile" to GraphicsFillTail.PROFILE,
                    "initialWords" to listOf(0, 0, 0, 0),
                    "statusReads" to GraphicsFillIrq92.samples,
                    "accessLimit" to 256,
                    "execution" to mapOf("profile" to GraphicsFillExecution.PROFILE, "surfaceBase" to 128, "surfaceBytes" to 4, "submissionLimit" to 2),
                    "copyInitialWords" to List(9) { 0 },
                    "copyExecution" to mapOf(
                        "profile" to "same-geometry-shared-ram-copy-experimental-v1",
                        "sourceBase" to 0x06893000L,
                        "destinationBase" to targetBase,
                        "surfaceBytes" to surfaceBytes,
                        "submissionLimit" to 2,
                        "mode2Workspace" to mapOf("base" to heapBase, "bytes" to heapBytes),
                    ),
                    "commandInitialWords" to List(8) { 0 },
                    "commandExecution" to SolidCommandRects.RGBA_PROFILE,
                    "commandPairLimit" to 128,
                ) + (staticDeadline?.let { mapOf(GraphicsCommandCompletion.SETTING to it) } ?: emptyMap()) +
                    (autoDelay?.let { mapOf("commandIrq92AutoDelayTicks" to it) } ?: emptyMap())
            }
            val dev = VglibInitFactory().create(
                ctx,
                mapOf(
                    "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                    "inputFile" to "bank",
                    GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE,
                    GraphicsFillIrq92.SETTING to 42L,
                    GraphicsCopyIrq92.SETTING to 60L,
                    GraphicsIrq92Sequence.SETTING to "either",
                )
            )
            return Triple(host, host.entries.getValue("soc-vglib-init.registers") as ScheduledMmioDevice, dev as StatefulComponent)
        }

        val (host0, bank0, comp0) = buildFixture(autoDelay = 10L)
        repeat(18) { bank0.read(0, 4); bank0.write(0, 4, 0) }
        bank0.write(16, 4, 128); bank0.write(20, 4, 132); bank0.write(28, 4, GraphicsFillExecution.CONTROL)
        host0.tick = 42L; bank0.beforeInstruction(42L)
        assertEquals(GraphicsFillIrq92.samples.first(), bank0.read(52, 4)); bank0.write(28, 4, 0); assertEquals(0L, bank0.read(52, 4))
        host0.tick = 55L
        listOf(0xc00 to 0x06893000, 0xc04 to targetBase.toInt(), 0xc08 to 0x03000400, 0xc0c to 0x03000400, 0xc10 to 1, 0xc18 to 1)
            .forEach { (off, v) -> bank0.write(off.toLong(), 4, v.toLong()) }
        host0.tick = 60L; bank0.beforeInstruction(60L)
        assertEquals(GraphicsCopyIrq92.BIT30, bank0.read(52, 4)); bank0.write(0xc18, 4, 0); assertEquals(0L, bank0.read(52, 4))

        fun submitCmdPair(h: LcdcFunctionalTest.Context, b: ScheduledMmioDevice, tick: Long, control: Long = 0xffffL) {
            h.tick = tick
            b.write(0x1214, 4, control)
            b.write(0x1298, 4, 0L)
            b.write(0x10d4, 4, vAddr); b.write(0x10dc, 4, (vWords.size / 2).toLong()); b.write(0x10d0, 4, 1L)
            b.write(0x10e4, 4, mAddr); b.write(0x10ec, 4, (mWords.size / 2).toLong()); b.write(0x10e0, 4, 1L)
        }
        fun drainCmdIrq(h: LcdcFunctionalTest.Context, b: ScheduledMmioDevice, fireTick: Long) {
            assertEquals(fireTick, b.nextDeadline())
            h.tick = fireTick; b.beforeInstruction(fireTick)
            assertEquals(0x80000000L, b.read(0x34, 4))
            assertEquals(0L, b.read(0x1800, 4)); b.write(0x1800, 4, 0L)
            assertEquals(0L, b.read(0x1020, 4)); b.write(0x1020, 4, 0L)
            assertEquals(0L, b.read(0x34, 4))
            assertEquals(0L, b.read(0x58, 4))
        }

        // Submit pair 0 at tick 70 -> auto deadline 80
        submitCmdPair(host0, bank0, 70L, control = 0x0100ffffL)
        assertEquals(80L, bank0.nextDeadline())
        drainCmdIrq(host0, bank0, 80L)
        assertNull(bank0.nextDeadline())

        // Checkpoint after pair 0, restore in fresh fixture (both autoDelay=10 and upgraded without saved autoDelay field), then submit pair 1
        val snapAfter0 = comp0.saveSnapshot()
        val (host1, bank1, comp1) = buildFixture(autoDelay = 10L)
        host1.tick = 85L
        comp1.restoreSnapshot(snapAfter0)
        assertEquals(snapAfter0, comp1.saveSnapshot())
        submitCmdPair(host1, bank1, 90L)
        assertEquals(100L, bank1.nextDeadline())

        // Checkpoint with pending auto-scheduled pair 1 IRQ, restore and drain
        val midPending = comp1.saveSnapshot()
        val (host2, bank2, comp2) = buildFixture(autoDelay = 10L)
        host2.tick = 92L
        comp2.restoreSnapshot(midPending)
        drainCmdIrq(host2, bank2, 100L)
        assertNull(bank2.nextDeadline())
    }

    @Test
    fun continuousModeIgnoresLegacyStaticIrq92DeadlinesOnPostOobeTicksAndRoundTripsSnapshot() {
        val heapBase = 0x06fd_8000L
        val heapBytes = 4096
        val targetBase = 0x0614_0000L
        val surfaceBytes = 1024 * 768 * 4
        val vAddr = heapBase + 0x100
        val mAddr = heapBase + 0x200
        val vWords = intArrayOf(0x0001_0002, 0x0000_0000, 0x0001_0002, 0x0000_0000, 0x0001_0002, 0x0000_0000)
        val mWords = intArrayOf(
            0x2000_0001, 0x0000_0000,
            0x1100_0000, 0x0000_0000,
            0x1000_0000, 0x0000_0000,
            0x0100_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0011_0000, 0x0000_0000,
            0x0000_2000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
            0x0000_0000, 0x0000_0000,
        )
        val heap = ByteArray(heapBytes)
        val heapBuf = ByteBuffer.wrap(heap).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vWords.forEachIndexed { i, w -> heapBuf.putInt(0x100 + i * 4, w) }
        mWords.forEachIndexed { i, w -> heapBuf.putInt(0x200 + i * 4, w) }
        val surface = ByteArray(surfaceBytes)
        val fillMem = ByteArray(4)

        fun buildContinuousFixture(): Triple<LcdcFunctionalTest.Context, ScheduledMmioDevice, StatefulComponent> {
            val host = LcdcFunctionalTest.Context()
            val lines = object : EdgeInterruptSink {
                var high = false
                override fun setLine(irq: Int, high: Boolean) { this.high = high }
                override fun edgeState(irq: Int) = EdgeInterruptState(high = high, edge = false, pending = false)
            }
            host.entries["soc-gic.lines"] = lines
            host.entries["cpu.execution-location"] = ExecutionLocationSource { ExecutionLocation(0, 0x0140_233cL) }
            host.entries["cpu.memory"] = object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) = Unit
                override fun read(address: Long, destination: ByteBuffer) {
                    val len = destination.remaining()
                    when {
                        address == 128L && len == 4 -> destination.put(fillMem)
                        address in heapBase until (heapBase + heapBytes) ->
                            destination.put(heap, (address - heapBase).toInt(), len)
                        address in targetBase until (targetBase + surfaceBytes) ->
                            destination.put(surface, (address - targetBase).toInt(), len)
                        else -> repeat(len) { destination.put(0) }
                    }
                }
                override fun write(address: Long, source: ByteBuffer) {
                    val len = source.remaining()
                    when {
                        address == 128L && len == 4 -> source.get(fillMem)
                        address in targetBase until (targetBase + surfaceBytes) ->
                            source.get(surface, (address - targetBase).toInt(), len)
                        else -> source.position(source.position() + len)
                    }
                }
            }
            val ctx = object : ComponentContext by host {
                override fun readData(relative: String): Map<String, Any> = if (relative == "bank") mapOf(
                    "schema" to 1, "profile" to VglibInitFactory.PROFILE, "readConfidence" to "HYPOTHESIS",
                    "transactions" to List(18) { mapOf("offset" to 0, "readValue" to 0, "writeValue" to 0) },
                ) else mapOf(
                    "profile" to GraphicsFillTail.PROFILE,
                    "initialWords" to listOf(0, 0, 0, 0),
                    "statusReads" to GraphicsFillIrq92.samples,
                    "accessLimit" to 256,
                    "execution" to mapOf("profile" to GraphicsFillExecution.PROFILE, "surfaceBase" to 128, "surfaceBytes" to 4, "submissionLimit" to 2),
                    "copyInitialWords" to List(9) { 0 },
                    "copyExecution" to mapOf(
                        "profile" to "same-geometry-shared-ram-copy-experimental-v1",
                        "sourceBase" to 0x06893000L,
                        "destinationBase" to targetBase,
                        "surfaceBytes" to surfaceBytes,
                        "submissionLimit" to 2,
                        "mode2Workspace" to mapOf("base" to heapBase, "bytes" to heapBytes),
                    ),
                    "commandInitialWords" to List(8) { 0 },
                    "commandExecution" to SolidCommandRects.RGBA_PROFILE,
                    "commandPairLimit" to 64,
                    // Legacy OOBE boot-trace timestamps matching tools/release/component-configs/soc-vglib-init/graphics-fill.yaml
                    GraphicsCommandCompletion.SETTING to 473_865_998L,
                    "commandIrq92Followups" to listOf(546_648_942L, 556_380_053L, 572_406_608L),
                    "commandIrq92AutoDelayTicks" to 1000L,
                )
            }
            val dev = VglibInitFactory().create(
                ctx,
                mapOf(
                    "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                    "inputFile" to "bank",
                    GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE,
                    GraphicsFillIrq92.SETTING to 42L,
                    GraphicsCopyIrq92.SETTING to 60L,
                    GraphicsIrq92Sequence.SETTING to "either",
                    "continuousScanout" to true,
                ),
            )
            return Triple(host, host.entries.getValue("soc-vglib-init.registers") as ScheduledMmioDevice, dev as StatefulComponent)
        }

        val (host, bank, comp) = buildContinuousFixture()
        // Post-OOBE fast boot hits VGLIB well before legacy 473M..572M OOBE trace timestamps!
        host.tick = 150_000_000L
        repeat(18) { bank.read(0, 4); bank.write(0, 4, 0) }
        bank.write(16, 4, 128); bank.write(20, 4, 132); bank.write(28, 4, GraphicsFillExecution.CONTROL)
        host.tick = 150_000_001L; bank.beforeInstruction(host.tick)
        assertEquals(GraphicsFillIrq92.samples.first(), bank.read(52, 4)); bank.write(28, 4, 0); assertEquals(0L, bank.read(52, 4))
        host.tick = 160_000_000L
        listOf(0xc00 to 0x06893000, 0xc04 to targetBase.toInt(), 0xc08 to 0x03000400, 0xc0c to 0x03000400, 0xc10 to 1, 0xc18 to 1)
            .forEach { (off, v) -> bank.write(off.toLong(), 4, v.toLong()) }
        host.tick = 160_000_001L; bank.beforeInstruction(host.tick)
        assertEquals(GraphicsCopyIrq92.BIT30, bank.read(52, 4)); bank.write(0xc18, 4, 0); assertEquals(0L, bank.read(52, 4))

        // Submit 6 VGLIB draws at ticks 200M..380M (including pair 4 at 376,978,439 < legacy 572,406,608) + 7th past 32-bit u32 max!
        val postOobeTicks = listOf(200_000_000L, 230_000_000L, 270_000_000L, 320_000_000L, 376_978_439L, 380_000_000L, 5_000_000_000L)
        for ((idx, submitTick) in postOobeTicks.withIndex()) {
            host.tick = submitTick
            bank.write(0x1214, 4, if (idx == 0) 0x0100ffffL else 0xffffL)
            bank.write(0x1298, 4, 0L)
            bank.write(0x10d4, 4, vAddr); bank.write(0x10dc, 4, (vWords.size / 2).toLong()); bank.write(0x10d0, 4, 1L)
            bank.write(0x10e4, 4, mAddr); bank.write(0x10ec, 4, (mWords.size / 2).toLong()); bank.write(0x10e0, 4, 1L)
            val fireTick = submitTick + 1L
            assertEquals(fireTick, bank.nextDeadline(), "Pair #$idx must schedule IRQ92 immediately at submitTick+1")
            host.tick = fireTick
            bank.beforeInstruction(fireTick)
            assertEquals(0x80000000L, bank.read(0x34, 4))
            assertEquals(0L, bank.read(0x1800, 4)); bank.write(0x1800, 4, 0L)
            assertEquals(0L, bank.read(0x1020, 4)); bank.write(0x1020, 4, 0L)
            assertEquals(0L, bank.read(0x34, 4))
            assertEquals(0L, bank.read(0x58, 4))
            assertNull(bank.nextDeadline())
        }

        // Snapshot save & restore in continuous mode preserves >32-bit dynamic deadlines cleanly
        val snap = comp.saveSnapshot()
        val (host2, bank2, comp2) = buildContinuousFixture()
        host2.tick = 5_000_000_100L
        comp2.restoreSnapshot(snap)
        assertEquals(snap, comp2.saveSnapshot())
        host2.tick = 5_100_000_000L
        bank2.write(0x1214, 4, 0xffffL)
        bank2.write(0x1298, 4, 0L)
        bank2.write(0x10d4, 4, vAddr); bank2.write(0x10dc, 4, (vWords.size / 2).toLong()); bank2.write(0x10d0, 4, 1L)
        bank2.write(0x10e4, 4, mAddr); bank2.write(0x10ec, 4, (mWords.size / 2).toLong()); bank2.write(0x10e0, 4, 1L)
        assertEquals(5_100_000_001L, bank2.nextDeadline())
    }
}
