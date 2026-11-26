package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Channel4ControlReadbackTest {
    private val control = 0xfff3c020L
    private val catalog = AddressCatalog.bundled()
    private val readback = "control-readback-after-write-v1"
    private val writeOnly = "write-words-v1"
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 835022112L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x5a048L
    }
    private fun bus(profile: String? = readback, channel11: String = writeOnly): DiscoveryBus {
        val settings = buildMap {
            if (profile != null) put("channel4Programming", profile)
            put("channel11Programming", channel11)
        }
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
    }

    @Test fun promotesPreviousWritesAndLetsTheGuestClearBits() {
        val legacy = bus(writeOnly)
        legacy.access(1, control, 4, true, 0x15000)
        val saved = legacy.saveSnapshot()
        assertFails { bus().restoreSnapshot(saved) }
        assertFails { bus(writeOnly).apply { restoreSnapshot(saved) }.access(2, control, 4, false) }
        val selected = bus().apply { restoreSnapshot(saved, true) }
        val router = MmioRouter(emptyList(), selected)
        val actual = router.read(control, 4)
        assertEquals(0x15000L, actual)
        val beforeClear = selected.saveSnapshot()
        val resumed = bus().apply { restoreSnapshot(beforeClear) }
        val next = MmioRouter(emptyList(), resumed)
        assertEquals(actual, next.read(control, 4))
        next.write(control, 4, actual and 0x3000L.inv())
        assertEquals(0x14000L, next.read(control, 4))
        assertEquals(0x14000L, bus().apply { restoreSnapshot(resumed.saveSnapshot()) }.access(6, control, 4, false))
        assertFails { bus(null).restoreSnapshot(beforeClear, true) }
        assertFails { bus(writeOnly).restoreSnapshot(beforeClear, true) }
        val reads = selected.snapshot()["accesses"] as List<*>
        val entry = reads.single() as Map<*, *>
        assertEquals("read", entry["direction"])
        assertEquals(true, entry["completed"])
        assertEquals(actual, entry["before"])
        assertEquals(actual, entry["after"])
    }

    @Test fun requiresWriteHistoryIncludingAnExplicitZero() {
        val empty = bus(writeOnly).saveSnapshot()
        assertFails { bus().apply { restoreSnapshot(empty, true) }.access(1, control, 4, false) }
        assertFails { bus().access(1, control, 4, false) }
        val zero = bus(writeOnly).apply { access(1, control, 4, true, 0) }.saveSnapshot()
        val active = bus().apply { restoreSnapshot(zero, true) }
        assertEquals(0L, active.access(2, control, 4, false))
        active.access(3, control, 4, true, 0xffffffffL)
        assertEquals(0xffffffffL, active.access(4, control, 4, false))
    }

    @Test fun keepsOtherFieldsAndWidthsClosed() {
        fun selected() = bus().apply { access(1, control, 4, true, 0x15000) }
        for (size in listOf(1, 2, 8)) for (write in listOf(true, false))
            assertFails { selected().access(2, control, size, write, 0) }
        for (address in listOf(control - 4, control + 1, control + 4, 0xfff3c03c,
            0xfff3c140, 0xfff3c144, 0xfff3c148, 0xfff3c14c, 0xfff3c150)) {
            assertFails { selected().access(2, address, 4, false) }
        }
        for (address in listOf(0xfff3c03cL, 0xfff3c140, 0xfff3c144, 0xfff3c148, 0xfff3c14c))
            selected().access(2, address, 4, true, 17)
    }

    @Test fun channel11PromotesItsOwnControlWordWithoutOpeningItsParameterWords() {
        val second = 0xfff3c03cL
        val previous = bus().apply {
            access(1, control, 4, true, 0x15000)
            access(2, second, 4, true, 0x19000)
        }.saveSnapshot()
        fun both() = bus(channel11 = readback)
        assertFails { both().restoreSnapshot(previous) }
        val promoted = both().apply { restoreSnapshot(previous, true) }
        val word = promoted.access(3, second, 4, false)
        assertEquals(0x19000L, word)
        promoted.access(4, second, 4, true, word and 0x3000L.inv())
        val saved = promoted.saveSnapshot()
        val restored = both().apply { restoreSnapshot(saved) }
        assertEquals(0x18000L, restored.access(5, second, 4, false))
        assertEquals(0x15000L, restored.access(6, control, 4, false))
        assertFails { bus().restoreSnapshot(saved, true) }
        assertFails { bus(null, readback).restoreSnapshot(saved, true) }
        assertFails { both().access(1, second, 4, false) }
        assertFails { restored.access(7, 0xfff3c1b0, 4, false) }
    }
}
