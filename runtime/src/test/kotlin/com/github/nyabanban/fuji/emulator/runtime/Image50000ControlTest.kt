package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class Image50000ControlTest {
    private val base = 0xfff50000L
    private val legacy = mapOf("image50000" to "retained-words-zero-v1")
    private val control = mapOf("image50000Control" to "write0-read1-once-v1")
    private var core = 1
    private var pc = 0x5af98L
    private var lr = 0x11c0f78L
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun pc() = pc
        override fun lr() = lr
        override fun tick() = 832780653L
    }
    private val catalog = AddressCatalog.bundled()
    private fun runtime(settings: Map<String, String>, mode: String = "discovery") =
        CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to settings))
    private fun bus(settings: Map<String, String> = control) = checkNotNull(runtime(settings).createBus(context))
    private fun pollContext() {
        pc = 0x5afe0
        lr = 0x11c0f88
    }

    @Test fun selectsOnlyTheBaseWord() {
        assertNull(runtime(control, "strict").createBus(context))
        assertFails { bus(mapOf("image50000Control" to "retained-words-zero-v1")) }
        assertFails { bus(legacy).access(0, base, 4, true) }
        assertFails { bus().access(0, base + 4, 4, true) }
        assertFails { bus().access(0, base - 4, 4, true) }
        assertFails { bus().access(0, base + 1, 4, true) }
        for (width in listOf(1, 2, 8)) assertFails { bus().access(0, base, width, true) }
    }

    @Test fun refusesWrongWriterAndOutOfOrderPolls() {
        assertFails { bus().access(0, base, 4, true, 1) }
        core = 0
        assertFails { bus().access(0, base, 4, true) }
        core = 1
        lr += 4
        assertFails { bus().access(0, base, 4, true) }
        lr -= 4
        pc += 4
        assertFails { bus().access(0, base, 4, true) }
        pc -= 4
        val written = bus().apply { access(0, base, 4, true) }.saveSnapshot()
        fun resumed() = bus().apply { restoreSnapshot(written) }
        assertFails { resumed().access(1, base, 4, true) }
        pollContext()
        assertFails { bus().access(0, base, 4, false) }
        core = 2
        assertFails { resumed().access(1, base, 4, false) }
        core = 1
        lr += 4
        assertFails { resumed().access(1, base, 4, false) }
        lr -= 4
        pc += 4
        assertFails { resumed().access(1, base, 4, false) }
    }

    @Test fun promotesOldSnapshotAndPreservesEachPhase() {
        val previous = bus(legacy).apply { access(0, base + 4, 4, true, 0x52) }.saveSnapshot()
        fun selected() = bus(legacy + control)
        assertFails { selected().restoreSnapshot(previous) }
        val ready = selected().apply { restoreSnapshot(previous, true) }.saveSnapshot()
        val active = selected().apply { restoreSnapshot(ready) }
        assertEquals(0x52L, active.access(0, base + 4, 4, false))
        assertEquals(0L, active.access(1, base, 4, true))
        val armed = active.saveSnapshot()
        assertFails { bus(legacy).restoreSnapshot(armed, true) }
        pollContext()
        val resumed = selected().apply { restoreSnapshot(armed) }
        assertEquals(1L, resumed.access(2, base, 4, false))
        val exhausted = resumed.saveSnapshot()
        assertFails { selected().apply { restoreSnapshot(exhausted) }.access(3, base, 4, false) }
        pc = 0x5af98
        lr = 0x11c0f78
        assertFails { selected().apply { restoreSnapshot(exhausted) }.access(3, base, 4, true) }
    }

    @Test fun rejectsImpossibleSnapshotState() {
        val snapshot = bus().apply { access(0, base, 4, true) }.saveSnapshot()
        val regions = snapshot.getValue("regions") as Map<*, *>
        val row = regions[IMAGE_50000_CONTROL_REGION] as Map<*, *>
        for (change in listOf(mapOf("position" to 0), mapOf("position" to 3, "accessAttempts" to 3),
            mapOf("strategy" to "retained-bytes"), mapOf("mutatedBytesBase64" to "AAAAAA=="))) {
            val altered = snapshot + ("regions" to regions + (IMAGE_50000_CONTROL_REGION to row + change))
            assertFails { bus().restoreSnapshot(altered, true) }
        }
    }

    @Test fun promotesOnlyTheCompletedFirstCycleAndRestoresTheSecond() {
        val twice = mapOf("image50000Control" to "write0-read1-twice-v1")
        val first = bus()
        assertFails { bus(twice).restoreSnapshot(bus(legacy).saveSnapshot(), true) }
        assertFails { bus(twice).restoreSnapshot(first.saveSnapshot(), true) }
        first.access(0, base, 4, true)
        assertFails { bus(twice).restoreSnapshot(first.saveSnapshot(), true) }
        pollContext()
        assertEquals(1L, first.access(1, base, 4, false))
        val completed = first.saveSnapshot()
        assertFails { bus(twice).restoreSnapshot(completed) }
        val promoted = bus(twice).apply { restoreSnapshot(completed, true) }.saveSnapshot()
        fun resume(state: Map<*, *> = promoted) = bus(twice).apply { restoreSnapshot(state) }
        assertFails { resume().access(2, base, 4, false) }
        pc = 0x5af98
        lr = 0x11c0f78
        assertFails { resume().access(2, base, 4, true, 1) }
        assertFails { resume().access(2, base, 2, true) }
        assertFails { resume().access(2, base + 4, 4, true) }
        core = 0
        assertFails { resume().access(2, base, 4, true) }
        core = 1
        lr += 4
        assertFails { resume().access(2, base, 4, true) }
        lr -= 4
        val armed = resume().apply { assertEquals(0L, access(2, base, 4, true)) }.saveSnapshot()
        assertFails { resume(armed).access(3, base, 4, true) }
        assertFails { bus().restoreSnapshot(armed, true) }
        pollContext()
        val finished = resume(armed).apply { assertEquals(1L, access(3, base, 4, false)) }.saveSnapshot()
        assertFails { resume(finished).access(4, base, 4, false) }
        pc = 0x5af98
        lr = 0x11c0f78
        assertFails { resume(finished).access(4, base, 4, true) }
        val regions = finished.getValue("regions") as Map<*, *>
        val row = regions[IMAGE_50000_CONTROL_REGION] as Map<*, *>
        for (change in listOf(mapOf("position" to 5, "accessAttempts" to 5), mapOf("accessAttempts" to 3))) {
            val altered = finished + ("regions" to regions + (IMAGE_50000_CONTROL_REGION to row + change))
            assertFails { resume(altered) }
        }
    }

    @Test fun repeatablePromotesOnlyFromCompletedTwiceAndLoopsAcrossRestores() {
        val twice = mapOf("image50000Control" to "write0-read1-twice-v1")
        val repeatable = mapOf("image50000Control" to "write0-read1-repeatable-v1")
        assertNull(runtime(repeatable, "strict").createBus(context))
        assertFails { bus(repeatable).restoreSnapshot(bus(legacy).saveSnapshot(), true) }

        val twiceBus = bus(twice)
        // Drive cycle 1 + cycle 2, checking every partial cursor < 4 fails promotion.
        for (cycle in 0 until 2) {
            assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
            pc = 0x5af98; lr = 0x11c0f78
            assertEquals(0L, twiceBus.access(cycle * 2L + 1, base, 4, true, 0))
            assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
            pollContext()
            assertEquals(1L, twiceBus.access(cycle * 2L + 2, base, 4, false))
        }
        val completedTwice = twiceBus.saveSnapshot()
        // Reject promotion from once even at its limit (2).
        val onceBus = bus()
        pc = 0x5af98; lr = 0x11c0f78
        onceBus.access(1, base, 4, true, 0)
        pollContext()
        onceBus.access(2, base, 4, false)
        assertFails { bus(repeatable).restoreSnapshot(onceBus.saveSnapshot(), true) }

        // Promotion requires allowPromotedRegions = true.
        assertFails { bus(repeatable).restoreSnapshot(completedTwice) }
        val promoted = bus(repeatable).apply { restoreSnapshot(completedTwice, true) }.saveSnapshot()
        fun resume(state: Map<*, *> = promoted) = bus(repeatable).apply { restoreSnapshot(state) }

        // Negative checks right after promotion (position 4 -> expects WRITE4 0).
        pollContext()
        assertFails { resume().access(1, base, 4, false) }
        pc = 0x5af98; lr = 0x11c0f78
        assertFails { resume().access(1, base, 4, true, 1) }
        assertFails { resume().access(1, base, 2, true, 0) }
        assertFails { resume().access(1, base + 4, 4, true, 0) }
        core = 0
        assertFails { resume().access(1, base, 4, true, 0) }
        core = 1
        lr += 4
        assertFails { resume().access(1, base, 4, true, 0) }
        lr -= 4
        pc += 4
        assertFails { resume().access(1, base, 4, true, 0) }
        pc -= 4

        // Execute 4 repeatable cycles (positions 4..12) with snapshot round-trips at every boundary.
        var active = resume()
        for (cycle in 0 until 4) {
            pc = 0x5af98; lr = 0x11c0f78
            assertEquals(0L, active.access(cycle * 2L + 1, base, 4, true, 0))
            val mid = active.saveSnapshot()
            assertFails { bus(twice).restoreSnapshot(mid, true) }
            assertFails { bus().restoreSnapshot(mid, true) }
            // Repeated write before read refused.
            assertFails { resume(mid).access(1, base, 4, true, 0) }
            pollContext()
            core = 0
            assertFails { resume(mid).access(1, base, 4, false) }
            core = 1
            lr += 4
            assertFails { resume(mid).access(1, base, 4, false) }
            lr -= 4
            active = resume(mid)
            assertEquals(1L, active.access(cycle * 2L + 2, base, 4, false))
            val done = active.saveSnapshot()
            active = resume(done)
        }
        val finalSnap = active.saveSnapshot()
        val regions = finalSnap.getValue("regions") as Map<*, *>
        val row = regions[IMAGE_50000_CONTROL_REGION] as Map<*, *>
        assertEquals(12, row["position"])
        assertEquals(12L, row["accessAttempts"])
        // Forged accessAttempts mismatch rejected.
        val forged = finalSnap + ("regions" to regions + (IMAGE_50000_CONTROL_REGION to row + mapOf("accessAttempts" to 11L)))
        assertFails { resume(forged) }
    }
}
