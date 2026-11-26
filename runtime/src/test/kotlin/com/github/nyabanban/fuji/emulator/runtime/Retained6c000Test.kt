package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Retained6c000Test {
    private val base = 0xfff6c000L
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 700755719L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x11b2928L
    }
    private fun bus(fill: Int? = 165, upper: Int? = null, completion: Boolean = false): DiscoveryBus {
        val inputs = buildMap {
            if (fill != null) put(RETAINED_6C000_REGION, mapOf("strategy" to "retained-bytes", "fill" to fill))
            if (upper != null) put(RETAINED_6C204_REGION, mapOf("strategy" to "retained-bytes", "fill" to upper) +
                if (completion) mapOf("completion" to C204_COMPLETION) else emptyMap())
        }
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
    }

    @Test fun retainsRmwAndSeedAcrossOrdinaryRestore() {
        val old = bus(null).saveSnapshot()
        assertFails { bus().restoreSnapshot(old) }
        val selected = bus().apply { restoreSnapshot(old, allowPromotedRegions = true) }
        val initial = selected.access(1, base, 4, false)
        assertEquals(0xa5a5a5a5L, initial)
        selected.access(2, base, 4, true, initial and 1L.inv())
        selected.access(3, base + 512, 4, true, 0)
        val saved = selected.saveSnapshot()
        assertEquals(0xa5a5a5a4L, bus().apply { restoreSnapshot(saved) }.access(4, base, 4, false))
        assertFails { bus(0).restoreSnapshot(saved, true) }
        assertFails { bus(null).restoreSnapshot(saved, true) }
    }

    @Test fun enforcesWordRunsAndRequiresSeparateUpperSelection() {
        for (offset in listOf(-4, 40, 148, 380, 436, 508, 516)) {
            assertFails { bus().access(1, base + offset, 4, true, 0) }
        }
        assertFails { bus().access(1, base, 2, true, 0) }
        assertEquals(0xa5a5a5a5L, bus().access(1, base + 512, 4, false))
        assertFails { bus(null).access(1, base, 4, false) }
    }

    @Test fun promotedStartClearsOnlyBitZeroAfterReadAndRestores() {
        val old = bus(upper = 0).apply {
            access(1, base + 516, 4, true, 0x80000003)
            access(2, base + 520, 4, true, 5)
        }.saveSnapshot()
        assertFails { bus(upper = 0, completion = true).restoreSnapshot(old) }
        val selected = bus(upper = 0, completion = true).apply { restoreSnapshot(old, true) }
        assertEquals(0x80000003L, selected.access(3, base + 516, 4, false))
        val saved = selected.saveSnapshot()
        assertFails { bus(upper = 0).restoreSnapshot(saved, true) }
        val restored = bus(upper = 0, completion = true).apply { restoreSnapshot(saved) }
        assertEquals(0x80000002L, restored.access(4, base + 516, 4, false))
        repeat(2) { assertEquals(5L, restored.access(5L + it, base + 520, 4, false)) }
        restored.access(7, base + 516, 4, true, 1)
        assertEquals(1L, restored.access(8, base + 516, 4, false))
        assertEquals(0L, restored.access(9, base + 516, 4, false))
    }

    @Test fun imageEventRequiresEnabledMaskAndPreservesOtherStatusBits() {
        val inputs = mapOf(RETAINED_69100_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0))
        val selected = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
        val status = 0xfff69108L
        var acknowledged = 0
        assertFails { selected.startImageEvent(true) { acknowledged++ } }
        selected.access(1, status + 4, 4, true, 3)
        selected.access(2, status, 4, true, 0x81000001)
        selected.startImageEvent(true) { acknowledged++ }
        assertFails { selected.startImageEvent(false) {} }
        assertFails { selected.saveSnapshot() }
        assertEquals(0x81000003L, selected.access(3, status, 4, false))
        selected.access(4, status, 4, true, 3)
        assertEquals(0x81000001L, selected.access(5, status, 4, false))
        assertEquals(1, acknowledged)
        selected.startImageEvent(false) { acknowledged++ }
        assertEquals(0x81000001L, selected.access(6, status, 4, false))
        selected.access(7, status, 4, true, 1)
        assertEquals(2, acknowledged)
        selected.saveSnapshot()
    }

    @Test fun upperSelectionPreservesPreviousWritesAndHasItsOwnSeed() {
        val previous = bus().apply { access(1, base + 388, 4, true, 16) }.saveSnapshot()
        val selected = bus(upper = 0).apply { restoreSnapshot(previous, true) }
        assertEquals(16, selected.access(2, base + 388, 4, false))
        for (offset in listOf(516, 520)) {
            assertEquals(0L, selected.access(1, base + offset, 4, false))
            selected.access(1, base + offset, 4, true, offset.toLong())
            assertEquals(offset.toLong(), selected.access(2, base + offset, 4, false))
        }
        val saved = selected.saveSnapshot()
        assertFails { bus(upper = 1).restoreSnapshot(saved, true) }
        assertEquals(520L, bus(upper = 0).apply { restoreSnapshot(saved) }.access(1, base + 520, 4, false))
        assertFails { selected.access(3, base + 524, 4, false) }
    }

    @Test fun channel4AllowsOnlySelectedAlignedProgrammingWrites() {
        val profile = mapOf("discovery" to mapOf("channel4Programming" to "write-words-v1"))
        fun selected() = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), profile).createBus(context))
        val original = bus(null).saveSnapshot()
        val control = 0xfff3c020L
        assertFails { bus(null).access(1, control, 4, true, 0x15000) }
        val active = selected().apply { restoreSnapshot(original, true) }
        for (address in listOf(control, 0xfff3c140L, 0xfff3c144L, 0xfff3c148L, 0xfff3c14cL)) {
            active.access(2, address, 4, true, 0x15000)
        }
        val saved = active.saveSnapshot()
        fun restored() = selected().apply { restoreSnapshot(saved) }
        for (address in listOf(control, 0xfff3c140L, 0xfff3c144L, 0xfff3c148L, 0xfff3c14cL)) {
            assertFails { restored().access(3, address, 4, false) }
        }
        for (address in listOf(control - 4, control + 4, 0xfff3c13cL, 0xfff3c150L, control + 1)) {
            assertFails { restored().access(4, address, 4, true, 0) }
        }
        assertFails { restored().access(5, control, 2, true, 0) }
        val channel11Control = 0xfff3c03cL
        assertFails { restored().access(6, channel11Control, 4, true, 0x19000) }
        val expanded = mapOf("discovery" to (profile.getValue("discovery") + ("channel11Programming" to "write-words-v1")))
        val promoted = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), expanded).createBus(context))
        promoted.restoreSnapshot(saved, true)
        for (address in listOf(channel11Control, 0xfff3c1b0L, 0xfff3c1b4L, 0xfff3c1b8L, 0xfff3c1bcL)) {
            promoted.access(7, address, 4, true, 0x19000)
        }
    }
}
