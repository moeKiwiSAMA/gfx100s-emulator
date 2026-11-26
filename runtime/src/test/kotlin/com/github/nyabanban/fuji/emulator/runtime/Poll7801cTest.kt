package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Poll7801cTest {
    private val address = 0xfff7801cL
    private val catalog = AddressCatalog.bundled()
    private var pc = 0x5af98L
    private var lr = 0x1294de0L
    private val context = object : DiscoveryContext {
        override fun core() = 3
        override fun tick() = 699997777L
        override fun pc() = pc
        override fun lr() = lr
    }
    private val selection = mapOf("strategy" to DiscoveryStrategy.POLL_7801C.id)
    private fun bus(input: Map<String, Any> = selection): DiscoveryBus {
        val inputs = if (input.isEmpty()) emptyMap() else mapOf(POLL_7801C_REGION to input)
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
    }

    @Test fun refusesInvalidTransactionsBeforeAdvancing() {
        for ((offset, width, value) in listOf(Triple(-4, 4, 1L), Triple(4, 4, 1L),
            Triple(2, 4, 1L), Triple(0, 2, 1L), Triple(0, 4, 2L))) {
            val selected = bus()
            assertFails { selected.access(1, address + offset, width, true, value) }
            assertEquals(0, (selected.regions.indices.single { selected.regions[it].id == POLL_7801C_REGION }).let {
                ((selected.checkpointState()["regions"] as List<*>)[it] as Map<*, *>)["sampleIndex"]
            })
        }
        assertFails { bus().access(1, address, 4, false) }
        assertFails { bus().apply { access(1, address, 4, true, 1) }.access(2, address, 4, true, 1) }
        assertFails { bus(emptyMap()).access(1, address, 4, true, 1) }
        assertFails { bus(mapOf("strategy" to "fixed-input", "value" to 0)) }
    }

    @Test fun promotesUntouchedLegacyStateAndResumesBothSteps() {
        val legacy = bus(emptyMap()).saveSnapshot()
        assertFails { bus().restoreSnapshot(legacy) }
        val first = bus().apply { restoreSnapshot(legacy, allowPromotedRegions = true) }
        assertEquals(1L, first.access(1, address, 4, true, 1))
        val written = first.saveSnapshot()
        assertFails { bus(emptyMap()).restoreSnapshot(written, allowPromotedRegions = true) }
        val second = bus().apply { restoreSnapshot(written) }
        assertEquals(0L, second.access(2, address, 4, false))
        val exhausted = second.saveSnapshot()
        assertFails { bus().apply { restoreSnapshot(exhausted) }.access(3, address, 4, false) }
        val regions = written.getValue("regions") as Map<*, *>
        val row = regions[POLL_7801C_REGION] as Map<*, *>
        for (invalid in listOf(mapOf("position" to 3, "accessAttempts" to 3),
            mapOf("position" to 0), mapOf("retainedWritten" to true), mapOf("base" to address + 4))) {
            assertFails { bus().restoreSnapshot(written + ("regions" to regions + (POLL_7801C_REGION to row + invalid))) }
        }
    }

    @Test fun setupRepeatPromotesOnlyTheCompletedPairAndRestoresItsOwnCursor() {
        val repeat = mapOf("strategy" to DiscoveryStrategy.SETUP_REPEAT_7801C.id)
        val original = bus()
        original.access(1, address, 4, true, 1)
        assertFails { bus(repeat).restoreSnapshot(original.saveSnapshot(), true) }
        original.access(2, address, 4, false)
        val spent = original.saveSnapshot()
        assertFails { bus(repeat).restoreSnapshot(spent) }
        fun promoted() = bus(repeat).apply { restoreSnapshot(spent, true) }
        assertFails { promoted().access(3, address, 4, true, 1) }
        lr = 0x11e1748
        assertFails { promoted().access(3, address, 4, true, 2) }
        val pending = promoted().apply { access(3, address, 4, true, 1) }.saveSnapshot()
        assertFails { bus().restoreSnapshot(pending, true) }
        fun restored() = bus(repeat).apply { restoreSnapshot(pending) }
        assertFails { restored().access(4, address, 4, false) }
        pc = 0x5afe0
        lr = 0x11e1750
        val complete = restored()
        assertEquals(0L, complete.access(4, address, 4, false))
        val exhausted = complete.saveSnapshot()
        pc = 0x5af98
        lr = 0x11e1748
        assertFails { bus(repeat).apply { restoreSnapshot(exhausted) }.access(5, address, 4, true, 1) }

        val repeatable = mapOf("strategy" to DiscoveryStrategy.SETUP_REPEATABLE_7801C.id)
        assertFails { bus(repeatable).restoreSnapshot(exhausted) }
        assertFails {
            bus(repeatable).apply {
                restoreSnapshot(exhausted, true)
                access(5, address, 4, true, 1)
                access(6, address, 4, true, 1)
            }
        }
        val promotedRepeatable = bus(repeatable).apply { restoreSnapshot(exhausted, true) }
        pc = 0x5af98
        lr = 0x1294de0
        assertEquals(1L, promotedRepeatable.access(5, address, 4, true, 1))
        assertEquals(0L, promotedRepeatable.access(6, address, 4, false))
        assertEquals(1L, promotedRepeatable.access(7, address, 4, true, 1))
        assertEquals(0L, promotedRepeatable.access(8, address, 4, false))
        val savedRepeatable = promotedRepeatable.saveSnapshot()
        val resumedRepeatable = bus(repeatable).apply { restoreSnapshot(savedRepeatable) }
        assertEquals(1L, resumedRepeatable.access(9, address, 4, true, 1))
        assertEquals(0L, resumedRepeatable.access(10, address, 4, false))
        assertFails { bus(repeat).restoreSnapshot(savedRepeatable, true) }
    }
}
