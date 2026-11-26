package com.github.nyabanban.fuji.emulator.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class DiscoverySnapshotTest {
    private val hex64 = "a".repeat(64)
    private val ctx = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 100L
        override fun pc() = 0x1000L
        override fun lr() = 0x1004L
    }

    private fun makeRegions() = listOf(
        DiscoveryRegion(
            id = "fixed.sel",
            deviceId = "dev.a",
            base = 0xfff4_a200L,
            length = 4,
            alignment = 4,
            widths = setOf(4),
            littleEndian = true,
            strategy = DiscoveryStrategy.FIXED,
            selectionValues = listOf(0L, 1L),
        ),
        DiscoveryRegion(
            id = "script.a300",
            deviceId = "dev.b",
            base = 0xfff4_a300L,
            length = 4,
            alignment = 4,
            widths = setOf(4),
            littleEndian = true,
            strategy = DiscoveryStrategy.SCRIPT,
            samples = listOf(0L),
        ),
        DiscoveryRegion(
            id = "retained.mem",
            deviceId = "dev.c",
            base = 0xfff4_a400L,
            length = 8,
            alignment = 4,
            widths = setOf(4),
            littleEndian = true,
            strategy = DiscoveryStrategy.RETAINED,
            initial = ByteArray(8),
        ),
    )

    @Test
    fun a300AppendPreservesUnreadPrefixAndRestoresTheExpandedSequence() {
        fun bus() = DiscoveryBus(makeRegions(), emptyList(), DiscoveryJournal("run", "v1", hex64, hex64, hex64), ctx)
        val id = "script.a300"
        val address = 0xfff4a300L
        val first = bus()
        first.access(1, address, 4, false)
        val prefix = listOf(0L) + List(63) { 7L }
        first.selectScriptInputTail(id, 1, prefix)
        val restored = bus().apply { restoreSnapshot(first.saveSnapshot()) }
        val full = prefix + List(192) { 9L }
        for (invalid in listOf(prefix, full.toMutableList().apply { set(63, 8L) }, prefix + -1L)) {
            assertFails { restored.appendA300Samples(id, 1, invalid) }
        }
        assertFails { restored.appendA300Samples(id, 2, full) }
        assertEquals(prefix, restored.scriptInputSelection(id)["samples"])
        restored.appendA300Samples(id, 1, full)
        val continued = bus().apply { restoreSnapshot(restored.saveSnapshot()) }
        for (index in 1 until full.size) assertEquals(full[index], continued.access(index.toLong(), address, 4, false))
        val legacyBoundary = continued.saveSnapshot()
        assertFailsWith<IllegalStateException> { continued.access(256, address, 4, false) }

        val at256 = bus().apply { restoreSnapshot(legacyBoundary) }
        val first257 = full + 17L
        at256.appendA300Samples(id, 256, first257)
        assertEquals(17L, at256.access(256, address, 4, false))
        val at257 = bus().apply { restoreSnapshot(at256.saveSnapshot()) }
        val extended = first257 + (258L..2048L)
        assertFailsWith<IllegalArgumentException> { at257.appendA300Samples(id, 257, extended + 2049L) }
        at257.appendA300Samples(id, 257, extended)
        val resumed = bus().apply { restoreSnapshot(at257.saveSnapshot()) }
        for (ordinal in 258L..2048L) assertEquals(ordinal, resumed.access(ordinal - 1, address, 4, false))
        val exhausted = bus().apply { restoreSnapshot(resumed.saveSnapshot()) }
        assertFailsWith<IllegalStateException> { exhausted.access(2048, address, 4, false) }
        assertEquals(2048, exhausted.scriptInputSelection(id)["sampleIndex"])
        assertEquals(true, exhausted.scriptInputSelection(id)["accessRefused"])
    }

    @Test
    fun snapshotPreservesFixedSelectionScriptTailCursorAndRetainedBytesAcrossRestoreAndPromotion() {
        val j1 = DiscoveryJournal("run-1", "v1", hex64, hex64, hex64)
        val bus1 = DiscoveryBus(makeRegions(), emptyList(), j1, ctx)
        bus1.selectFixedInput("fixed.sel", 0L)
        assertEquals(0L, bus1.access(1L, 0xfff4_a200L, 4, false))
        assertEquals(0L, bus1.access(2L, 0xfff4_a300L, 4, false))
        bus1.selectScriptInputTail("script.a300", 1, listOf(0L, 7L, 9L))
        assertEquals(7L, bus1.access(3L, 0xfff4_a300L, 4, false))
        bus1.access(4L, 0xfff4_a404L, 4, true, 0x1234_5678L)

        val snap = bus1.saveSnapshot()
        // Direct continuation on bus1
        val directA300 = bus1.access(5L, 0xfff4_a300L, 4, false)
        val directFixed = bus1.access(6L, 0xfff4_a200L, 4, false)
        val directRetained = bus1.access(7L, 0xfff4_a404L, 4, false)

        // Exact restore on fresh bus2
        val j2 = DiscoveryJournal("run-2", "v1", hex64, hex64, hex64)
        val bus2 = DiscoveryBus(makeRegions(), emptyList(), j2, ctx)
        bus2.restoreSnapshot(snap)
        assertEquals(directA300, bus2.access(5L, 0xfff4_a300L, 4, false))
        assertEquals(directFixed, bus2.access(6L, 0xfff4_a200L, 4, false))
        assertEquals(directRetained, bus2.access(7L, 0xfff4_a404L, 4, false))

        // Promoted region restore (when retained.mem is promoted to a modeled device)
        val j3 = DiscoveryJournal("run-3", "v1", hex64, hex64, hex64)
        val bus3 = DiscoveryBus(makeRegions().take(2), listOf(AddressInterval(0xfff4_a400L, 0xfff4_a408L)), j3, ctx)
        bus3.restoreSnapshot(snap, allowPromotedRegions = true)
        assertEquals(9L, bus3.access(5L, 0xfff4_a300L, 4, false))
        assertEquals(0L, bus3.access(6L, 0xfff4_a200L, 4, false))
    }
}
