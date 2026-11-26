package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.EdgeInterruptState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ImageIrq106StatusTest {
    private val legacy = mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_DRIVER.id)
    private val settings = legacy + (ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
    private var core = 1
    private var pc = 0x5afe0L
    private var lr = 0x11b7e50L
    private var active = true
    private var lineActive: Boolean? = null
    private val sink = object : CoupledEdgeInterruptSink {
        override fun lineEnabled(irq: Int) = true
        override fun edgeState(irq: Int) = EdgeInterruptState(true, false, false)
        override fun setLine(irq: Int, high: Boolean) = error("Expected coupled pulse")
        override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) {
            assertEquals(106, irq)
            assertEquals(2, targetMask)
            latchDevice()
        }
    }
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun pc() = pc
        override fun lr() = lr
        override fun tick() = 0L
        override fun imageIrq106Active() = active
        override fun imageIrq106LineActive() = lineActive ?: active
    }

    private fun bus(selection: Map<String, String> = settings, mode: String = "discovery") =
        checkNotNull(CatalogRuntime.load(Path.of("."), mapOf("executionMode" to mode,
            "discovery" to selection + ("logCapacity" to 32))).createBus(context))

    private fun setup(bus: DiscoveryBus) {
        val offsets = listOf(0, 0, 0, 0x2e8, 0x2e4, 0x2e4, 12, 12, 12)
        val callers = listOf(0x11b8154L, 0x11b8160, 0x11b8170, 0x11b8188, 0x11b81a0,
            0x11b81ac, 0x11b7e50, 0x11b7f0c, 0x11b7c58)
        val values = listOf(0L, 0, 1, 0x10000, 0, 0, 0, 0, 0)
        for ((index, offset) in offsets.withIndex()) {
            val write = index in listOf(1, 5, 7)
            lr = callers[index]
            pc = if (write) 0x5af98 else 0x5afe0
            assertEquals(values[index], bus.access(index.toLong(), Image70000.BASE + offset, 4, write))
        }
    }

    private fun at(position: Int, selection: Map<String, String> = legacy): Map<String, Any?> {
        val saved = bus(selection).saveSnapshot()
        val regions = saved.getValue("regions") as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        val driver = row + mapOf("position" to position, "accessAttempts" to position,
            "retainedWritten" to true, "retainedValue" to 0xace10000L)
        return saved + ("regions" to regions + (Image70000.REGION to driver))
    }

    @Test fun legacyPromotionAndMalformedRestoreLeaveAllRegionsAtTheirPreviousValues() {
        assertFails { bus(settings, "strict") }
        assertFails { bus(settings - "image70000") }
        assertFails { bus(legacy + (ImageIrq106Status.KEY to "unknown")) }
        val original = at(144)
        val target = bus()
        val before = target.saveSnapshot()
        assertFails { target.restoreSnapshot(original) }
        assertFails { target.pulseImageIrq106(sink) }
        assertEquals(before, target.saveSnapshot())
        target.restoreSnapshot(original, true)
        target.pulseImageIrq106(sink)
        val saved = target.saveSnapshot()
        val regions = saved.getValue("regions") as Map<*, *>
        val last = regions.entries.last()
        val altered = regions + (last.key to (last.value as Map<*, *>) + ("base" to -1))
        val fresh = bus()
        val status = saved.getValue(ImageIrq106Status.KEY) as Map<*, *>
        for (bad in listOf(saved + ("regions" to altered),
            saved + (ImageIrq106Status.KEY to status + ("bit0" to 0)),
            saved + (ImageIrq106Status.KEY to status + ("phase" to "READ")))) {
            assertFails { fresh.restoreSnapshot(bad, true) }
            assertEquals(before, fresh.saveSnapshot())
        }
        assertFails { bus(legacy).restoreSnapshot(saved, true) }
    }

    @Test fun onlyActiveQualifiedAccessConsumesEachPhase() {
        val early = bus().also(::setup)
        assertFails { early.pulseImageIrq106(sink) }
        val initialized = bus().apply { restoreSnapshot(at(144), true) }
        val idle = initialized.saveSnapshot()
        val address = ImageIrq106Status.ADDRESS
        lr = 0x11b7e50
        assertFails { bus().apply { restoreSnapshot(idle) }.access(1, address, 4, false) }
        initialized.pulseImageIrq106(sink)
        val latched = initialized.saveSnapshot()
        fun firstRead() = bus().apply { restoreSnapshot(latched) }.access(1, address, 4, false)
        active = false
        assertFails { firstRead() }
        active = true
        core = 0
        assertFails { firstRead() }
        core = 1
        lr += 4
        assertFails { firstRead() }
        lr -= 4
        pc += 4
        assertFails { firstRead() }
        pc -= 4
        assertFails { bus().apply { restoreSnapshot(latched) }.access(1, address, 2, false) }
        assertEquals(1L, initialized.access(1, address, 4, false))
        val read = initialized.saveSnapshot()
        lr = 0x11b7f0c
        pc = 0x5af98
        for (word in listOf(0L, 2, 0x101)) {
            assertFails { bus().apply { restoreSnapshot(read) }.access(1, address, 4, true, word) }
        }
        assertEquals(1L, initialized.access(2, address, 4, true, 1))
        pc = 0x5afe0
        lr = 0x11b7c58
        assertEquals(0L, initialized.access(3, address, 4, false))
        assertEquals(listOf(ImageIrq106Status.PROFILE, "guest-write", ImageIrq106Status.PROFILE),
            (initialized.snapshot()["accesses"] as List<*>).map { (it as Map<*, *>)["valueSource"] })
        assertFails { initialized.pulseImageIrq106(sink) }
        assertFails { initialized.access(4, address, 4, false) }
    }

    @Test fun gettersAndStatusKeepIndependentCursorsAndRespectRegionHoles() {
        val getters = legacy + ("image70000" to DiscoveryStrategy.IMAGE_70000_GETTERS.id)
        val selected = getters + (ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        assertFails { bus(settings + ("image70000" to DiscoveryStrategy.IMAGE_70000_UPDATE.id)) }
        val prior = bus(selected).apply { restoreSnapshot(at(143), true) }
        assertFails { prior.pulseImageIrq106(sink) }
        for (position in listOf(144, 145, 211, 277, 278)) {
            val old = at(position, if (position == 144) legacy else getters)
            val bus = bus(selected).apply { restoreSnapshot(old, true) }
            val initial = bus.saveSnapshot()
            val region = bus.regions.single { it.id == Image70000.REGION }
            assertEquals(false, region.prohibited.any { it.overlaps(ImageIrq106Status.ADDRESS, ImageIrq106Status.ADDRESS + 4) })
            for (hole in region.prohibited) {
                val probe = bus(selected).apply { restoreSnapshot(initial) }
                assertFails { probe.access(1, hole.base, 4, false) }
                assertEquals("prohibited-hole", ((probe.snapshot()["accesses"] as List<*>).single() as Map<*, *>)["reason"])
            }
            val baseline = bus(getters).apply { restoreSnapshot(old, true) }
            val next = when (position) {
                144, 211 -> 0x134 to 0x11ba500L
                145 -> 0x138 to 0x11ba52cL
                277 -> 0x204 to 0x11be510L
                else -> null
            }
            if (next != null) {
                lr = next.second
                val address = Image70000.BASE + next.first
                assertEquals(baseline.access(1, address, 4, false), bus.access(1, address, 4, false))
                assertEquals(baseline.saveSnapshot()["regions"], bus.saveSnapshot()["regions"])
            }
            bus.pulseImageIrq106(sink)
            lr = 0x11b7e50
            assertEquals(1L, bus.access(2, ImageIrq106Status.ADDRESS, 4, false))
            val partial = bus.saveSnapshot()
            val resumed = bus(selected).apply { restoreSnapshot(partial) }
            assertEquals(partial, resumed.saveSnapshot())
            pc = 0x5af98
            lr = 0x11b7f0c
            resumed.access(3, ImageIrq106Status.ADDRESS, 4, true, 1)
            pc = 0x5afe0
            lr = 0x11b7c58
            assertEquals(0L, resumed.access(4, ImageIrq106Status.ADDRESS, 4, false))
        }
    }

    @Test fun statusSelectionRequiresAnUnclaimedReadableWritableWord() {
        val driver = Image70000.region(DiscoveryStrategy.IMAGE_70000_GETTERS.id)
        val inside = AddressInterval(ImageIrq106Status.ADDRESS + 2, ImageIrq106Status.ADDRESS + 3)
        val journal = DiscoveryJournal("test", "v1", "a".repeat(64), "b".repeat(64), "c".repeat(64), 32)
        fun rejected(region: DiscoveryRegion, known: List<AddressInterval> = emptyList()) {
            assertFails { DiscoveryBus(listOf(region), known, journal, context, true) }
            assertEquals(0L, journal.attempts)
        }
        rejected(driver, listOf(inside))
        rejected(driver.copy(prohibited = driver.prohibited + inside))
        rejected(driver.copy(readAllowed = IntArray(driver.length)))
        rejected(driver.copy(writeAllowed = IntArray(driver.length)))
    }

    @Test fun repeatableW1cSupportsMultiBitMaskReLatchingAndPromotionFromBit0AckOnce() {
        val repeatableSettings = legacy + (ImageIrq106Status.KEY to ImageIrq106Status.REPEATABLE_PROFILE)
        assertFails { bus(repeatableSettings, "strict") }
        assertFails { bus(repeatableSettings - "image70000") }

        // bit0-ack-once-v1 rejects non-1 status masks.
        val legacyOnce = bus(settings).apply { restoreSnapshot(at(144), true) }
        assertFails { legacyOnce.pulseImageIrq106(sink, 0x41L) }
        assertFails { legacyOnce.pulseImageIrq106(sink, 0L) }

        // Complete one bit0-ack-once-v1 cycle to FINISHED.
        legacyOnce.pulseImageIrq106(sink)
        val legacyLatched = legacyOnce.saveSnapshot()
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(1L, legacyOnce.access(1, ImageIrq106Status.ADDRESS, 4, false))
        pc = 0x5af98; lr = 0x11b7f0c
        assertEquals(1L, legacyOnce.access(2, ImageIrq106Status.ADDRESS, 4, true, 1L))
        pc = 0x5afe0; lr = 0x11b7c58
        assertEquals(0L, legacyOnce.access(3, ImageIrq106Status.ADDRESS, 4, false))
        val legacyFinished = legacyOnce.saveSnapshot()

        // Mid-cycle bit0-ack-once-v1 cannot promote; FINISHED requires allowPromotedRegions = true.
        assertFails { bus(repeatableSettings).restoreSnapshot(legacyLatched, true) }
        assertFails { bus(repeatableSettings).restoreSnapshot(legacyFinished, false) }

        // Promote bit0-ack-once-v1 IDLE -> repeatable-w1c-v1 IDLE cleanly.
        val legacyIdle = bus(settings).apply { restoreSnapshot(at(144), true) }.saveSnapshot()
        val promotedIdle = bus(repeatableSettings).apply { restoreSnapshot(legacyIdle, true) }
        assertEquals(
            mapOf(
                "profile" to ImageIrq106Status.REPEATABLE_PROFILE,
                "confidence" to "HYPOTHESIS",
                "phase" to "IDLE",
                "mask" to 0L,
                "completedCycles" to 0,
            ),
            promotedIdle.irq106State().getValue(ImageIrq106Status.KEY),
        )

        // Promote bit0-ack-once-v1 FINISHED -> repeatable-w1c-v1 FINISHED with mask=0, completedCycles=1.
        val promoted = bus(repeatableSettings).apply { restoreSnapshot(legacyFinished, true) }
        assertEquals(
            mapOf(
                "profile" to ImageIrq106Status.REPEATABLE_PROFILE,
                "confidence" to "HYPOTHESIS",
                "phase" to "FINISHED",
                "mask" to 0L,
                "completedCycles" to 1,
            ),
            promoted.irq106State().getValue(ImageIrq106Status.KEY),
        )

        // Reject invalid masks (0, > 0x3FF, negative).
        for (badMask in listOf(-1L, 0L, 0x400L, 0x1000L)) {
            assertFails { promoted.pulseImageIrq106(sink, badMask) }
        }

        // Re-latch with 0x41 (bit 0 + bit 6), even when round-1 inRequest (active=false) is false as long as lineActive=true!
        active = false
        lineActive = true
        promoted.pulseImageIrq106(sink, 0x41L)
        assertFails { promoted.pulseImageIrq106(sink, 1L) } // cannot re-latch while LATCHED
        val latchedSnap = promoted.saveSnapshot()
        assertEquals(latchedSnap, bus(repeatableSettings).apply { restoreSnapshot(latchedSnap) }.saveSnapshot())
        // Downgrade to bit0-ack-once-v1 is rejected.
        assertFails { bus(settings).restoreSnapshot(latchedSnap, true) }

        // Line inactive must still refuse.
        lineActive = false
        pc = 0x5afe0; lr = 0x11b7e50
        assertFails { bus(repeatableSettings).apply { restoreSnapshot(latchedSnap) }.access(10, ImageIrq106Status.ADDRESS, 4, false) }
        lineActive = true

        // Cycle 2: READ returns 0x41.
        assertEquals(0x41L, promoted.access(10, ImageIrq106Status.ADDRESS, 4, false))
        val readSnap = promoted.saveSnapshot()
        assertEquals(readSnap, bus(repeatableSettings).apply { restoreSnapshot(readSnap) }.saveSnapshot())

        // Wrong W1C value must be rejected.
        pc = 0x5af98; lr = 0x11b7f0c
        for (badAck in listOf(0L, 1L, 0x40L, 0x42L, 0x141L)) {
            assertFails { bus(repeatableSettings).apply { restoreSnapshot(readSnap) }.access(11, ImageIrq106Status.ADDRESS, 4, true, badAck) }
        }

        // Exact W1C write 0x41 transitions to ACKNOWLEDGED (mask becomes 0).
        assertEquals(0x41L, promoted.access(11, ImageIrq106Status.ADDRESS, 4, true, 0x41L))
        val ackSnap = promoted.saveSnapshot()
        assertEquals(0L, (ackSnap.getValue(ImageIrq106Status.KEY) as Map<*, *>)["mask"])
        assertEquals(ackSnap, bus(repeatableSettings).apply { restoreSnapshot(ackSnap) }.saveSnapshot())

        // Poll read returns 0 and transitions to FINISHED with completedCycles=2.
        pc = 0x5afe0; lr = 0x11b7c58
        assertEquals(0L, promoted.access(12, ImageIrq106Status.ADDRESS, 4, false))
        val finishedCycle2 = promoted.saveSnapshot()
        assertEquals(
            mapOf(
                "profile" to ImageIrq106Status.REPEATABLE_PROFILE,
                "confidence" to "HYPOTHESIS",
                "phase" to "FINISHED",
                "mask" to 0L,
                "completedCycles" to 2,
            ),
            finishedCycle2.getValue(ImageIrq106Status.KEY),
        )
        assertEquals(listOf(ImageIrq106Status.REPEATABLE_PROFILE, "guest-write", ImageIrq106Status.REPEATABLE_PROFILE),
            (promoted.snapshot()["accesses"] as List<*>).map { (it as Map<*, *>)["valueSource"] })

        // Restore cycle-2 FINISHED snapshot and run cycle 3 with full 10-bit mask 0x3FF!
        val cycle3 = bus(repeatableSettings).apply { restoreSnapshot(finishedCycle2) }
        cycle3.pulseImageIrq106(sink, 0x3ffL)
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(0x3ffL, cycle3.access(20, ImageIrq106Status.ADDRESS, 4, false))
        pc = 0x5af98; lr = 0x11b7f0c
        assertEquals(0x3ffL, cycle3.access(21, ImageIrq106Status.ADDRESS, 4, true, 0x3ffL))
        pc = 0x5afe0; lr = 0x11b7c58
        assertEquals(0L, cycle3.access(22, ImageIrq106Status.ADDRESS, 4, false))
        assertEquals(3, (cycle3.irq106State().getValue(ImageIrq106Status.KEY) as Map<*, *>)["completedCycles"])
        val cycle3Snap = cycle3.saveSnapshot()
        assertEquals(cycle3Snap, bus(repeatableSettings).apply { restoreSnapshot(cycle3Snap) }.saveSnapshot())
        assertFails { bus(settings).restoreSnapshot(cycle3Snap, true) }
        active = true
        lineActive = null
    }
}
