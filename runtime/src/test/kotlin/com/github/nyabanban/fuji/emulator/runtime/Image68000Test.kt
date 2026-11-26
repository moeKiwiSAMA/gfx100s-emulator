package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class Image68000Test {
    private val base = 0xfff68000L
    private val catalog = AddressCatalog.bundled()
    private val selected = mapOf("image68000" to "descriptor-setup-once-v1")
    private val twice = mapOf("image68000" to "descriptor-setup-twice-v1")
    private val expectedOffsets = buildList {
        fun block(offset: Int, count: Int) {
            repeat(count) { index -> add(offset + 4 * index) }
        }
        block(0x000, 5)
        block(0x108, 2)
        block(0x110, 3)
        block(0x200, 28)
        block(0x300, 6)
        block(0x400, 53)
        block(0x500, 7)
        block(0x534, 2)
        block(0x600, 28)
        block(0x53c, 6)
        block(0x554, 6)
        block(0xc00, 41)
        block(0xa00, 60)
        block(0xa00, 12)
        block(0x700, 2)
        block(0x708, 1)
        block(0x70c, 6)
        block(0x800, 23)
        block(0x900, 21)
        add(0xd00)
    }
    private var core = 1
    private var pc = 0x5af98L
    private var lr = 0x11cb8a8L
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun tick() = 832915575L
        override fun pc() = pc
        override fun lr() = lr
    }
    private fun runtime(settings: Map<String, String>, mode: String = "discovery") =
        CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to settings))
    private fun bus(settings: Map<String, String> = selected) = checkNotNull(runtime(settings).createBus(context))

    @Test fun rejectsWrongWordOrderDirectionWidthHoleAndCaller() {
        assertNull(runtime(selected, "strict").createBus(context))
        assertFails { bus(mapOf("image68000" to "retained-words-zero-v1")) }
        val previous = bus(emptyMap())
        assertEquals(listOf(base to 4096L), MmioRouter(emptyList(), bus()).pageRanges() -
            MmioRouter(emptyList(), previous).pageRanges().toSet())
        val legacy = previous.saveSnapshot()
        assertFails { bus().restoreSnapshot(legacy) }
        val first = bus().apply { restoreSnapshot(legacy, true) }
        assertEquals(1L, first.access(1, base, 4, true, 1))
        val saved = first.saveSnapshot()
        fun second(offset: Int = 4, size: Int = 4, write: Boolean = true, value: Long = 0x1010101) =
            bus().apply { restoreSnapshot(saved) }.access(2, base + offset, size, write, value)
        for (offset in listOf(0, 1, 8, 0x14, 0xd00, 0xffc)) assertFails { second(offset) }
        assertFails { second(write = false) }
        assertFails { second(size = 2, value = 1) }
        assertFails { second(value = 0x1_0000_0000L) }
        assertFails { second(value = -1) }
        core = 0
        assertFails { second() }
        core = 1
        pc += 4
        assertFails { second() }
        pc -= 4
        lr += 4
        assertFails { second() }
        lr -= 4
        assertEquals(0x1010101L, second())
        val changed = bus().apply { restoreSnapshot(saved) }
        assertEquals(0xffffffffL, changed.access(2, base + 4, 4, true, 0xffffffffL))
        val receipt = (changed.snapshot().getValue("accesses") as List<*>).single() as Map<*, *>
        assertEquals("guest-write", receipt["valueSource"])
        assertEquals(0xffffffffL, receipt["value"])
        val regions = saved["regions"] as Map<*, *>
        val row = regions[Image68000.REGION] as Map<*, *>
        for (change in listOf(mapOf("position" to 2), mapOf("position" to 314, "accessAttempts" to 314))) {
            val altered = saved + ("regions" to regions + (Image68000.REGION to row + change))
            assertFails { bus().restoreSnapshot(altered, true) }
        }
    }

    @Test fun twicePromotesOnlyFromCompletedOnceAndAcceptsSecondPassAcrossRestores() {
        assertNull(runtime(twice, "strict").createBus(context))
        assertEquals(
            MmioRouter(emptyList(), bus(selected)).pageRanges(),
            MmioRouter(emptyList(), bus(twice)).pageRanges(),
        )
        assertEquals(313, expectedOffsets.size)
        // Missing Image68000 region cannot promote straight to twice.
        assertFails { bus(twice).restoreSnapshot(bus(emptyMap()).saveSnapshot(), true) }

        val onceBus = bus(selected)
        // Cursor 0 cannot promote to twice.
        assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
        for ((index, offset) in expectedOffsets.withIndex()) {
            val word = (index.toLong() shl 16) or offset.toLong()
            assertEquals(word, onceBus.access(index.toLong() + 1, base + offset, 4, true, word))
            if (index in setOf(0, 150, expectedOffsets.lastIndex - 1)) {
                assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
            }
        }
        val completedOnce = onceBus.saveSnapshot()
        // Exhausted once refuses pass 2.
        assertFails { bus(selected).apply { restoreSnapshot(completedOnce) }.access(314, base, 4, true, 1) }
        // Promotion requires allowPromotedRegions = true.
        assertFails { bus(twice).restoreSnapshot(completedOnce) }
        // Reject forged cursors on promotion.
        val onceRegions = completedOnce["regions"] as Map<*, *>
        val onceRow = onceRegions[Image68000.REGION] as Map<*, *>
        for (change in listOf(
            mapOf("position" to 312, "accessAttempts" to 312),
            mapOf("position" to 314, "accessAttempts" to 314),
            mapOf("accessAttempts" to 312),
            mapOf("retainedWritten" to true),
            mapOf("position" to "313"),
        )) {
            val altered = completedOnce + ("regions" to onceRegions + (Image68000.REGION to onceRow + change))
            assertFails { bus(twice).restoreSnapshot(altered, true) }
        }

        // Negative checks at start of pass 2 after promotion.
        fun promoted() = bus(twice).apply { restoreSnapshot(completedOnce, true) }
        for (offset in listOf(4, 0x108, 0xd00, 0xffc)) assertFails { promoted().access(1, base + offset, 4, true, 1) }
        assertFails { promoted().access(1, base, 4, false) }
        assertFails { promoted().access(1, base, 2, true, 1) }
        assertFails { promoted().access(1, base, 4, true, -1) }
        assertFails { promoted().access(1, base, 4, true, 0x1_0000_0000L) }
        core = 0
        assertFails { promoted().access(1, base, 4, true, 1) }
        core = 1
        pc += 4
        assertFails { promoted().access(1, base, 4, true, 1) }
        pc -= 4
        lr += 4
        assertFails { promoted().access(1, base, 4, true, 1) }
        lr -= 4

        // Replay full second pass (313..625) across multiple snapshot restores.
        var active = promoted()
        for ((index, offset) in expectedOffsets.withIndex()) {
            if (index in setOf(1, 150, expectedOffsets.lastIndex)) {
                val mid = active.saveSnapshot()
                assertFails { bus(selected).restoreSnapshot(mid, true) }
                assertFails { bus(emptyMap()).restoreSnapshot(mid, true) }
                active = bus(twice).apply { restoreSnapshot(mid) }
            }
            val word = 0x8000_0000L or (index.toLong() shl 12) or offset.toLong()
            assertEquals(word, active.access(index.toLong() + 1, base + offset, 4, true, word))
        }
        val exhaustedTwice = active.saveSnapshot()
        assertFails { bus(selected).restoreSnapshot(exhaustedTwice, true) }
        assertFails { bus(twice).apply { restoreSnapshot(exhaustedTwice) }.access(1, base, 4, true, 1) }
        val twiceRegions = exhaustedTwice["regions"] as Map<*, *>
        val twiceRow = twiceRegions[Image68000.REGION] as Map<*, *>
        assertEquals(626, twiceRow["position"])
        assertEquals(626L, twiceRow["accessAttempts"])
        for (change in listOf(
            mapOf("position" to 627, "accessAttempts" to 627),
            mapOf("accessAttempts" to 625),
        )) {
            val altered = exhaustedTwice + ("regions" to twiceRegions + (Image68000.REGION to twiceRow + change))
            assertFails { bus(twice).restoreSnapshot(altered) }
        }
    }

    @Test fun repeatablePromotesOnlyFromCompletedTwiceEnforcesHolesAndAcceptsRepeatableWrites() {
        val repeatable = mapOf("image68000" to "descriptor-setup-repeatable-v1")
        assertNull(runtime(repeatable, "strict").createBus(context))
        assertEquals(
            MmioRouter(emptyList(), bus(twice)).pageRanges(),
            MmioRouter(emptyList(), bus(repeatable)).pageRanges(),
        )

        // Verify exact prohibited holes on the repeatable region definition.
        val region = Image68000.region("descriptor-setup-repeatable-v1")
        for (offset in 0 until Image68000.SIZE step 4) {
            val address = base + offset
            val prohibited = region.prohibited.any { it.overlaps(address, address + 4) }
            assertEquals(offset !in Image68000.allowedOffsets, prohibited, "Offset 0x${offset.toString(16)} prohibited mismatch")
        }

        // Cannot promote from missing region or from once (even completed at 313).
        assertFails { bus(repeatable).restoreSnapshot(bus(emptyMap()).saveSnapshot(), true) }
        val onceBus = bus(selected)
        for ((index, offset) in expectedOffsets.withIndex()) {
            onceBus.access(index.toLong() + 1, base + offset, 4, true, index.toLong())
        }
        assertFails { bus(repeatable).restoreSnapshot(onceBus.saveSnapshot(), true) }

        // Drive twice to 626, verifying partial twice cursors cannot promote.
        val twiceBus = bus(twice).apply { restoreSnapshot(onceBus.saveSnapshot(), true) }
        assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
        for ((index, offset) in expectedOffsets.withIndex()) {
            twiceBus.access(index.toLong() + 1, base + offset, 4, true, index.toLong())
            if (index == expectedOffsets.lastIndex - 1) {
                assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
            }
        }
        val completedTwice = twiceBus.saveSnapshot()

        // Requires allowPromotedRegions = true.
        assertFails { bus(repeatable).restoreSnapshot(completedTwice) }
        fun promoted() = bus(repeatable).apply { restoreSnapshot(completedTwice, true) }

        // Negative checks: prohibited holes, reads, wrong width/alignment/core/PC/LR.
        for (hole in listOf(0x014, 0x104, 0x11c, 0x530, 0x6f8, 0xcfc, 0xd04)) {
            assertFails { promoted().access(1, base + hole, 4, true, 1) }
        }
        assertFails { promoted().access(1, base + 1, 4, true, 1) }
        assertFails { promoted().access(1, base, 2, true, 1) }
        assertFails { promoted().access(1, base, 4, false) }
        assertFails { promoted().access(1, base, 4, true, -1) }
        assertFails { promoted().access(1, base, 4, true, 0x1_0000_0000L) }
        core = 0
        assertFails { promoted().access(1, base, 4, true, 1) }
        core = 1
        pc += 4
        assertFails { promoted().access(1, base, 4, true, 1) }
        pc -= 4
        lr += 4
        assertFails { promoted().access(1, base, 4, true, 1) }
        lr -= 4

        // Repeatable writes in arbitrary order to allowedOffsets across snapshot round-trips.
        var active = promoted()
        val repeatTargets = listOf(0xd00, 0x000, 0xa00, 0x400, 0x000, 0xd00, 0x950, 0x108)
        for ((idx, offset) in repeatTargets.withIndex()) {
            val word = 0x5500_0000L or (idx.toLong() shl 12) or offset.toLong()
            assertEquals(word, active.access(idx.toLong() + 1, base + offset, 4, true, word))
            if (idx in setOf(0, 3, repeatTargets.lastIndex)) {
                val snap = active.saveSnapshot()
                assertFails { bus(twice).restoreSnapshot(snap, true) }
                assertFails { bus(selected).restoreSnapshot(snap, true) }
                active = bus(repeatable).apply { restoreSnapshot(snap) }
            }
        }
        val finalSnap = active.saveSnapshot()
        val regions = finalSnap["regions"] as Map<*, *>
        val row = regions[Image68000.REGION] as Map<*, *>
        assertEquals(626 + repeatTargets.size, row["position"])
        assertEquals((626 + repeatTargets.size).toLong(), row["accessAttempts"])
        val forged = finalSnap + ("regions" to regions + (Image68000.REGION to row + mapOf("accessAttempts" to 626L)))
        assertFails { bus(repeatable).restoreSnapshot(forged) }
    }
}
