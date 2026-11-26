package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class PanelInitOnceTest {
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 434554895L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x127698cL
    }
    private val config = mapOf("discovery" to mapOf("inputs" to mapOf(PanelInitOnce.REGION to
        mapOf("strategy" to "panel-init-once-v1"))))
    private fun bus() = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(context))
    private fun twice() = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf(
        "discovery" to mapOf("inputs" to mapOf(PanelInitOnce.REGION to mapOf("strategy" to "panel-init-twice-v1")))
    )).createBus(context))
    private fun DiscoveryBus.step(offset: Int, write: Boolean = false, value: Long = 0) =
        access(1, PanelInitOnce.BASE + offset, 4, write, value)

    @Test fun strictDefaultAndOutOfOrderRefusals() {
        assertNull(CatalogRuntime(catalog, "strict", Path.of("."), config).createBus(context))
        val default = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).createBus(context))
        assertFails { default.step(4) }
        assertFails { bus().step(0) }
        assertFails { bus().step(4, true) }
        assertFails { bus().access(1, PanelInitOnce.BASE + 4, 2, false) }
        assertFails { bus().apply { step(4) }.step(4, true, 1L) }
    }

    @Test fun promotesThenRestoresEachPhaseAndExhausts() {
        val default = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).createBus(context))
        val initial = default.saveSnapshot()
        assertFails { bus().restoreSnapshot(initial) }
        var selected = bus().apply { restoreSnapshot(initial, allowPromotedRegions = true) }
        val control = 0x001f0000L
        val offsets = listOf(4, 4, 8, 12, 16, 20, 0, 0, 0, 4, 4)
        val results = listOf(0L, control, 0xff00ff00, 0xfe, 0xabcdef, 0x1234, 0, 0, 1, control, PanelInitOnce.MASK)
        for (index in offsets.indices) {
            val saved = selected.saveSnapshot()
            selected = bus().apply { restoreSnapshot(saved) }
            val write = index in 1..6 || index == 10
            assertEquals(results[index], selected.step(offsets[index], write, if (write) results[index] else 0))
        }
        assertFails { selected.step(4) }
    }

    @Test fun legacySnapshotRequiresPromotionAndInvalidCursorsRefuse() {
        val saved = bus().saveSnapshot()
        val regions = saved.getValue("regions") as Map<*, *>
        val row = regions[PanelInitOnce.REGION] as Map<*, *>
        val legacy = saved + ("regions" to regions.filterKeys { it != PanelInitOnce.REGION })
        assertFails { bus().restoreSnapshot(legacy) }
        assertEquals(0L, bus().apply { restoreSnapshot(legacy, allowPromotedRegions = true) }.step(4))
        for (invalid in listOf(mapOf("position" to 12, "accessAttempts" to 12),
            mapOf("position" to 2, "accessAttempts" to 2, "retainedValue" to 0),
            mapOf("accessAttempts" to 1), mapOf("retainedValue" to 1))) {
            assertFails { bus().restoreSnapshot(saved + ("regions" to regions + (PanelInitOnce.REGION to row + invalid))) }
        }
    }

    @Test fun secondInitializationStartsWithReleasedControlAndStopsAfterTwoCycles() {
        val first = bus()
        val firstControl = 0x01000fffL
        val released = firstControl or PanelInitOnce.MASK
        val offsets = listOf(4, 4, 8, 12, 16, 20, 0, 0, 0, 4, 4)
        fun cycle(target: DiscoveryBus, initial: Long, control: Long) {
            val values = listOf(initial, control, 6L, 0x43210L, 0x43210L, 0x210L, 0L, 0L, 1L,
                control, control or PanelInitOnce.MASK)
            for (index in offsets.indices) {
                val write = index in 1..6 || index == 10
                assertEquals(values[index], target.step(offsets[index], write, if (write) values[index] else 0L))
            }
        }
        assertFails { twice().restoreSnapshot(first.saveSnapshot(), true) }
        cycle(first, 0, firstControl)
        val checkpoint = first.saveSnapshot()
        assertFails { twice().restoreSnapshot(checkpoint) }
        val second = twice().apply { restoreSnapshot(checkpoint, true) }
        cycle(second, released, released)
        val completed = second.saveSnapshot()
        assertFails { bus().restoreSnapshot(completed, true) }
        assertFails { twice().apply { restoreSnapshot(completed) }.step(4) }
    }

    @Test fun catalogRestrictsWordDirectionsAndRejectsGenericFill() {
        for (offset in 0..20 step 4) {
            val word = assertNotNull(catalog.query("cpu-physical", PanelInitOnce.BASE + offset, 4, "read").register)
            assertEquals(if (offset < 8) listOf(4) else emptyList(), word.readWidths)
            assertEquals(listOf(4), word.writeWidths)
        }
        val fill = mapOf("discovery" to mapOf("inputs" to mapOf(PanelInitOnce.REGION to
            mapOf("strategy" to "retained-bytes", "fill" to 0))))
        assertFails { CatalogRuntime(catalog, "discovery", Path.of("."), fill).createBus(context) }
    }
}
