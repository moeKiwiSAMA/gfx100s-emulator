package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Write49530Test {
    private val base = 0xfff49530L
    private var caller = 0x02394e9cL
    private var pc = 0x5af98L
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 700758026L
        override fun pc() = pc
        override fun lr() = caller
    }
    private val catalog = AddressCatalog.bundled()
    private fun bus(strategy: String? = DiscoveryStrategy.WRITE_49530.id, tail: Boolean = false): DiscoveryBus {
        val inputs = buildMap {
            if (strategy != null) put(WRITE_49530_REGION, mapOf("strategy" to strategy))
            if (tail) put(TAIL_49500_REGION, mapOf("strategy" to DiscoveryStrategy.TAIL_49500.id))
        }
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
    }

    @Test fun resumesBetweenOrderedWritesAndAfterExhaustion() {
        val old = bus(null).saveSnapshot()
        assertFails { bus().restoreSnapshot(old) }
        val first = bus().apply { restoreSnapshot(old, allowPromotedRegions = true) }
        assertEquals(0x10000704L, first.access(1, base, 4, true, 0x10000704))
        val written = first.saveSnapshot()
        assertFails { bus(null).restoreSnapshot(written, allowPromotedRegions = true) }
        caller = 0x02394eacL
        val second = bus().apply { restoreSnapshot(written) }
        assertEquals(0x70L, second.access(2, base+4, 4, true, 0x70))
        assertFails { bus().apply { restoreSnapshot(second.saveSnapshot()) }.access(3, base+4, 4, true, 0x70) }
        val regions = written.getValue("regions") as Map<*, *>
        val row = regions[WRITE_49530_REGION] as Map<*, *>
        assertFails { bus().restoreSnapshot(written + ("regions" to regions + (WRITE_49530_REGION to row + ("position" to 2)))) }
    }

    @Test fun refusesWrongDirectionWidthCallerWordAndOrder() {
        assertFails { bus(null).access(1, base, 4, true, 0x10000704) }
        assertFails { bus("retained-bytes") }
        assertFails { bus().access(1, base, 4, false) }
        assertFails { bus().access(1, base, 2, true, 0x704) }
        assertFails { bus().access(1, base+2, 4, true, 0x10000704) }
        assertFails { bus().access(1, base, 4, true, 0) }
        assertFails { bus().access(1, base+4, 4, true, 0x70) }
        assertFails { bus().apply { access(1, base, 4, true, 0x10000704) }.access(2, base, 4, true, 0x10000704) }
        pc = 0x5afe0
        assertFails { bus().access(1, base, 4, true, 0x10000704) }
        pc = 0x5af98
        caller = 0x02394eac
        assertFails { bus().access(1, base, 4, true, 0x10000704) }
    }

    @Test fun promotesCompletedPairAndRestoresEachTailBoundary() {
        val prefix = bus().apply {
            access(1, base, 4, true, 0x10000704)
            caller = 0x02394eac
            access(2, base + 4, 4, true, 0x70)
        }.saveSnapshot()
        caller = 0x02394ef8
        assertFails { bus().apply { restoreSnapshot(prefix) }.access(3, base + 4, 4, true, 0x71) }
        assertFails { bus(null, tail = true) }
        assertFails { bus(tail = true).restoreSnapshot(prefix) }
        fun promoted() = bus(tail = true).apply { restoreSnapshot(prefix, allowPromotedRegions = true) }
        assertFails { promoted().access(3, base + 4, 4, true, 0x70) }
        val finalAddress = 0xfff49500L
        caller = 0x02394f08
        assertFails { promoted().access(3, finalAddress, 4, true, 3) }
        assertFails { promoted().access(3, base + 4, 4, true, 0x71) }
        caller = 0x02394ef8
        val middle = promoted().apply { assertEquals(0x71L, access(3, base + 4, 4, true, 0x71)) }.saveSnapshot()
        fun restored() = bus(tail = true).apply { restoreSnapshot(middle) }
        assertFails { bus().restoreSnapshot(middle, allowPromotedRegions = true) }
        assertFails { restored().access(4, base + 4, 4, true, 0x71) }
        assertFails { restored().access(4, finalAddress, 4, true, 3) }
        caller = 0x02394f08
        assertFails { restored().access(4, finalAddress, 4, false) }
        assertFails { restored().access(4, finalAddress, 1, true, 3) }
        assertFails { restored().access(4, finalAddress + 4, 4, true, 3) }
        assertFails { restored().access(4, finalAddress, 4, true, 2) }
        val done = restored().apply { assertEquals(3L, access(4, finalAddress, 4, true, 3)) }.saveSnapshot()
        assertFails { bus(tail = true).apply { restoreSnapshot(done) }.access(5, finalAddress, 4, true, 3) }
        val rows = done.getValue("regions") as Map<*, *>
        val pair = rows[WRITE_49530_REGION] as Map<*, *>
        val premature = pair + mapOf("position" to 2, "accessAttempts" to 2)
        assertFails { bus(tail = true).restoreSnapshot(done + ("regions" to rows + (WRITE_49530_REGION to premature))) }

        // Promote completed 49530+49500 cycle to repeatable profiles and run a second full cycle
        fun repeatableBus() = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf("inputs" to mapOf(
            WRITE_49530_REGION to mapOf("strategy" to DiscoveryStrategy.WRITE_49530_REPEATABLE.id),
            TAIL_49500_REGION to mapOf("strategy" to DiscoveryStrategy.TAIL_49500_REPEATABLE.id),
        )))).createBus(context))
        assertFails { repeatableBus().restoreSnapshot(done) }
        val promotedRep = repeatableBus().apply { restoreSnapshot(done, allowPromotedRegions = true) }
        caller = 0x02394e9cL
        assertEquals(0x10000704L, promotedRep.access(5, base, 4, true, 0x10000704))
        caller = 0x02394eacL
        assertEquals(0x70L, promotedRep.access(6, base + 4, 4, true, 0x70))
        caller = 0x02394ef8L
        assertEquals(0x71L, promotedRep.access(7, base + 4, 4, true, 0x71))
        caller = 0x02394f08L
        assertEquals(3L, promotedRep.access(8, finalAddress, 4, true, 3))
        val secondDone = promotedRep.saveSnapshot()
        assertFails { bus(tail = true).restoreSnapshot(secondDone, allowPromotedRegions = true) }
        val resumedRep = repeatableBus().apply { restoreSnapshot(secondDone) }
        caller = 0x02394e9cL
        assertEquals(0x10000704L, resumedRep.access(9, base, 4, true, 0x10000704))
    }
}
