package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class HdmiB020Test {
    private val id = "gfx100s.hdmi.init-words.candidate"
    private val address = 0xfff7b020L
    private val catalog = AddressCatalog.bundled()
    private val config = mapOf("discovery" to mapOf("inputs" to mapOf(id to
        mapOf("strategy" to "retained-bytes", "fill" to 0xa4))))
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 421467905L
        override fun pc() = 0x01280348L
        override fun lr() = 0x0127d8fcL
    }
    private fun selected() = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(context))

    @Test fun promotesOnlyAnUntouchedRefuseRouteWithExplicitSelection() {
        val empty = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).createBus(context)).saveSnapshot()
        assertFails { selected().restoreSnapshot(empty) }
        val bus = selected().apply { restoreSnapshot(empty, allowPromotedRegions = true) }
        assertEquals(0xa4a4a4a4L, bus.access(1, address, 4, false))
        val refuseSnapshot = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to mapOf(id to mapOf("strategy" to "refuse"))))).createBus(context)).saveSnapshot()
        assertFails { selected().restoreSnapshot(refuseSnapshot) }
        assertEquals(0xa4a4a4a4L, selected().apply { restoreSnapshot(refuseSnapshot, allowPromotedRegions = true) }.access(1, address, 4, false))
        val regions = refuseSnapshot["regions"] as Map<*, *>
        val original = regions[id] as Map<*, *>
        for (change in listOf("accessAttempts" to 1, "retainedWritten" to true, "fixedSelected" to true,
                "position" to 1, "mutatedBytesBase64" to "", "base" to address)) {
            val altered = refuseSnapshot + ("regions" to regions + (id to original + change))
            assertFails { selected().restoreSnapshot(altered, allowPromotedRegions = true) }
        }
    }

    @Test fun selectionPreservesHighBitsAndRoundTripsAfterPromotion() {
        val legacy = selected().saveSnapshot().toMutableMap()
        legacy["regions"] = (legacy["regions"] as Map<*, *>).filterKeys { it != id }
        assertFails { selected().restoreSnapshot(legacy) }
        val bus = selected().apply { restoreSnapshot(legacy, allowPromotedRegions = true) }
        val before = bus.access(1, address, 4, false)
        assertEquals(0xa4a4a4a4L, before)
        bus.access(2, address, 4, true, before or 1)
        val restored = selected().apply { restoreSnapshot(bus.saveSnapshot()) }
        assertEquals(0xa4a4a4a5L, restored.access(3, address, 4, false))
    }

    @Test fun defaultRefusesAndSelectedRouteEnforcesWordRunsAndDirections() {
        assertNull(CatalogRuntime(catalog, "strict", Path.of("."), config).createBus(context))
        val default = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).createBus(context))
        for (write in listOf(false, true)) {
            assertFails { default.access(1, address, 4, write) }
            for ((offset, width) in listOf(-4 to 4, -2 to 4, 0 to 1, 0 to 2, 2 to 4, 16 to 4)) {
                assertFails { selected().access(1, address + offset, width, write) }
            }
        }
        assertFalse(catalog.query("cpu-physical", address, 4, "execute").permitted)
        assertFails { selected().access(1, address + 4, 4, false) }
        assertEquals(200L, selected().access(1, 0xfff7ba8cL, 4, true, 200))
    }
}
