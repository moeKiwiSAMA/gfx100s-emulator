package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HdmiStatusSamplesTest {
    private val address = HdmiStatusSamples.FIRST
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 0L
        override fun pc() = 0L
        override fun lr() = 0L
    }

    private fun bus(samples: List<List<Long>>? = null, linkReads: List<Long>? = null): DiscoveryBus {
        val settings = mutableMapOf<String, Any>("inputs" to mapOf(HdmiStatusSamples.REGION to
            mapOf("strategy" to "retained-bytes", "fill" to 0)))
        samples?.let { settings["hdmiStatusPairs"] = it }
        linkReads?.let { settings["hdmiLinkStatusReads"] = it }
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
    }

    @Test fun linkReadsAreFiniteReadOnlyAndResumeIndependently() {
        val pairs = listOf(listOf(0L, 0L))
        val link = HdmiStatusSamples.LINK
        val words = listOf(0L, 0x18L)
        val old = bus(pairs).saveSnapshot()
        assertFails { bus(pairs).access(1, link, 4, false) }
        assertFails { bus(pairs, words).restoreSnapshot(old) }
        assertFails { bus(pairs, words).access(1, link, 4, true, 0) }
        assertFails { bus(pairs, words).access(1, link, 2, false) }
        assertFails { bus(pairs, words).access(1, link + 4, 4, false) }
        val selected = bus(pairs, words).apply { restoreSnapshot(old, true) }
        assertEquals(0L, selected.access(1, link, 4, false))
        val saved = selected.saveSnapshot()
        assertFails { bus(pairs, words.reversed()).restoreSnapshot(saved, true) }
        val restored = bus(pairs, words).apply { restoreSnapshot(saved) }
        assertEquals(0x18L, restored.access(2, link, 4, false))
        assertEquals(0L, restored.access(3, address, 4, false))
        assertFails { restored.access(4, link, 4, false) }
    }

    @Test fun promotionAppendsAfterTheWholeSavedLinkSequence() {
        val pairs = listOf(listOf(1L, 2L))
        val link = HdmiStatusSamples.LINK
        val original = bus(pairs, listOf(4L, 8L))
        original.access(1, address, 4, false)
        original.access(2, link, 4, false)
        val saved = original.saveSnapshot()
        val longer = listOf(4L, 8L, 16L)
        assertFails { bus(pairs, longer).restoreSnapshot(saved) }
        for (invalid in listOf(null, listOf(4L), listOf(4L, 9L, 16L), listOf(3L, 8L, 16L))) {
            assertFails { bus(pairs, invalid).restoreSnapshot(saved, true) }
        }
        val promoted = bus(pairs, longer).apply { restoreSnapshot(saved, true) }
        assertEquals(2L, promoted.access(3, address + 4, 4, false))
        assertEquals(8L, promoted.access(4, link, 4, false))
        val continued = bus(pairs, longer).apply { restoreSnapshot(promoted.saveSnapshot()) }
        assertEquals(16L, continued.access(5, link, 4, false))
        val exhausted = continued.saveSnapshot()
        assertFails { continued.access(6, link, 4, false) }
        val atLimit = longer + List(HdmiStatusSamples.MAX_LINK_READS - longer.size) { 32L }
        val last = bus(pairs, atLimit).apply { restoreSnapshot(exhausted, true) }
        assertEquals(32L, last.access(7, link, 4, false))
        assertFails { bus(pairs, atLimit + 64L) }
    }

    @Test fun restoreBetweenReadsAndEchoes() {
        val pairs = listOf(listOf(0L, 0L), listOf(0x80000004L, 0x80L))
        val old = bus().saveSnapshot()
        assertFails { bus(pairs).restoreSnapshot(old) }
        val first = bus(pairs).apply { restoreSnapshot(old, allowPromotedRegions = true) }
        assertEquals(0L, first.access(1, address, 4, false))
        assertEquals(0L, first.access(2, address + 4, 4, false))
        val saved = first.saveSnapshot()
        assertFails { bus(pairs.reversed()).restoreSnapshot(saved) }
        val resumed = bus(pairs).apply { restoreSnapshot(saved) }
        resumed.access(3, address, 4, true, 0)
        resumed.access(4, address + 4, 4, true, 0)
        assertEquals(0x80000004L, resumed.access(5, address, 4, false))
        assertEquals(0x80L, resumed.access(6, address + 4, 4, false))
        resumed.access(7, address, 4, true, 0x80000004L)
        resumed.access(8, address + 4, 4, true, 0x80)
        assertFails { resumed.access(9, address, 4, false) }
    }

    @Test fun promotionAppendsOnlyAfterTheFinalEcho() {
        val pairs = listOf(listOf(1L, 2L))
        val extended = pairs + listOf(listOf(3L, 4L))
        val original = bus(pairs)
        original.access(1, address, 4, false)
        original.access(2, address + 4, 4, false)
        original.access(3, address, 4, true, 1)
        assertFails { bus(extended).restoreSnapshot(original.saveSnapshot(), true) }
        original.access(4, address + 4, 4, true, 2)
        val saved = original.saveSnapshot()
        assertFails { bus(extended).restoreSnapshot(saved) }
        assertFails { bus(extended.reversed()).restoreSnapshot(saved, true) }
        val continued = bus(extended).apply { restoreSnapshot(saved, true) }
        assertEquals(3L, continued.access(5, address, 4, false))
        val restored = bus(extended).apply { restoreSnapshot(continued.saveSnapshot()) }
        assertEquals(4L, restored.access(6, address + 4, 4, false))
        restored.access(7, address, 4, true, 3)
        restored.access(8, address + 4, 4, true, 4)
        assertFails { restored.access(9, address, 4, false) }
    }

    @Test fun rejectUnselectedInvalidAndOutOfOrderSamples() {
        val pairs = listOf(listOf(4L, 128L))
        assertFails { bus().access(1, address, 4, false) }
        assertFails { bus(pairs).access(1, address + 4, 4, false) }
        assertFails { bus(pairs).access(1, address, 2, false) }
        assertFails { bus(pairs).access(1, address - 4, 4, false) }
        assertFails { bus(listOf(listOf(0x100000000L, 0L))) }
        val selected = bus(pairs)
        selected.access(1, address, 4, false)
        selected.access(2, address + 4, 4, false)
        assertFails { selected.access(3, address, 4, true, 0) }
        assertEquals(2, (selected.snapshot()["hdmiStatus"] as Map<*, *>)["position"])
    }
}
