package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class Image51000Test {
    @Test fun checksUploadBoundariesAndPromotionWithoutExposingTheGap() {
        val catalog = AddressCatalog.bundled()
        val previous = mapOf("image50000" to "retained-words-zero-v1")
        val selected = previous + ("image51000" to "packed-table-pair-once-v1")
        var core = 1
        var pc = 0x5af98L
        var lr = 0x11c2538L
        val context = object : DiscoveryContext {
            override fun core() = core
            override fun pc() = pc
            override fun lr() = lr
            override fun tick() = 0L
        }
        fun bus(settings: Map<String, String> = selected) = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
        val old = bus(previous)
        val legacy = old.saveSnapshot()
        assertFails { bus().restoreSnapshot(legacy) }
        assertFails { bus(selected + ("image51000" to "retained-words-zero-v1")) }
        val base = 0xfff51008L
        val empty = 0xfff55000L
        fun pages(bus: DiscoveryBus) = MmioRouter(emptyList(), bus).pageRanges().flatMap { (start, length) ->
            (start until start + length step 4096).toList()
        }.toSet()
        val active = bus().apply { restoreSnapshot(legacy, true) }
        assertTrue(base and -4096L in pages(old))
        assertEquals(setOf(0xfff52000L, 0xfff53000L, empty), pages(active) - pages(old))
        assertFalse(0xfff54000L in pages(active))
        fun resumed() = bus().apply { restoreSnapshot(legacy, true) }
        for (address in listOf(base + 1, base + 4, 0xfff53a4c, 0xfff53a50, 0xfff54000, empty + 8))
            assertFails { resumed().access(1, address, 4, true) }
        assertFails { resumed().access(1, base, 4, false) }
        assertFails { resumed().access(1, base, 2, true) }
        core = 0
        assertFails { resumed().access(1, base, 4, true) }
        core = 1
        pc += 4
        assertFails { resumed().access(1, base, 4, true) }
        pc -= 4
        lr = 0x11c26b0
        assertFails { resumed().access(1, empty, 4, true, 0x3fff3fff) }
        assertFails { resumed().access(1, base, 4, true) }
        lr = 0x11c2538
        repeat(2705) { index -> active.access(index + 1L, base + index * 4, 4, true, 0xffffffffL) }
        val payload = active.saveSnapshot()
        fun restored(snapshot: Map<*, *>) = bus().apply { restoreSnapshot(snapshot) }
        assertFails { old.restoreSnapshot(payload, true) }
        assertFails { restored(payload).access(1, 0xfff53a4c, 4, true) }
        lr = 0x11c25bc
        assertFails { restored(payload).access(1, 0xfff53a4c, 4, true, 1) }
        active.access(2706, 0xfff53a4c, 4, true)
        lr = 0x11c26b0
        val terminal = active.saveSnapshot()
        assertFails { restored(terminal).access(1, empty, 4, true, 0) }
        active.access(2707, empty, 4, true, 0x3fff3fff)
        val partial = active.saveSnapshot()
        val rows = partial["regions"] as Map<*, *>
        val payloadRows = payload["regions"] as Map<*, *>
        assertFails { restored(partial + ("regions" to rows + (Image51000.TABLE to payloadRows[Image51000.TABLE]))) }
        assertFails { bus().restoreSnapshot(partial + ("regions" to rows - Image51000.TABLE), true) }
        lr = 0x11c26c0
        assertFails { restored(partial).access(1, empty + 4, 4, true, 1) }
        val finished = restored(partial)
        finished.access(1, empty + 4, 4, true)
        assertFails { restored(finished.saveSnapshot()).access(1, empty + 4, 4, true) }
    }

    @Test fun twicePromotesOnlyFromCompletedOnceAndAcceptsSecondPass4011TableAcrossRestores() {
        val catalog = AddressCatalog.bundled()
        val previous = mapOf("image50000" to "retained-words-zero-v1")
        val once = previous + ("image51000" to "packed-table-pair-once-v1")
        val twice = previous + ("image51000" to "packed-table-pair-twice-v1")
        var core = 1
        var pc = 0x5af98L
        var lr = 0x11c2538L
        val context = object : DiscoveryContext {
            override fun core() = core
            override fun pc() = pc
            override fun lr() = lr
            override fun tick() = 0L
        }
        fun runtime(settings: Map<String, String>, mode: String = "discovery") =
            CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to settings))
        fun bus(settings: Map<String, String>) = checkNotNull(runtime(settings).createBus(context))
        fun pages(bus: DiscoveryBus) = MmioRouter(emptyList(), bus).pageRanges().flatMap { (start, length) ->
            (start until start + length step 4096).toList()
        }.toSet()

        assertNull(runtime(twice, "strict").createBus(context))
        val base = 0xfff51008L
        val empty = 0xfff55000L
        assertEquals(
            setOf(0xfff52000L, 0xfff53000L, 0xfff54000L, empty),
            pages(bus(twice)) - pages(bus(previous)),
        )

        // Cannot promote to twice from a snapshot missing Image51000.
        assertFails { bus(twice).restoreSnapshot(bus(previous).saveSnapshot(), true) }

        // Drive once through pass 1, verifying partial cursors cannot promote to twice.
        val onceBus = bus(once)
        assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
        repeat(Image51000.PAYLOAD_WORDS) { index ->
            onceBus.access(index + 1L, base + index * 4, 4, true, 0x0010_0000L or index.toLong())
        }
        assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
        lr = 0x11c25bc
        onceBus.access(2706, 0xfff53a4c, 4, true, 0)
        assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
        lr = 0x11c26b0
        onceBus.access(2707, empty, 4, true, 0x3fff3fff)
        assertFails { bus(twice).restoreSnapshot(onceBus.saveSnapshot(), true) }
        lr = 0x11c26c0
        onceBus.access(2708, empty + 4, 4, true, 0)

        val completedOnce = onceBus.saveSnapshot()
        // Promotion requires allowPromotedRegions = true.
        assertFails { bus(twice).restoreSnapshot(completedOnce) }
        // Reject forged cursors, missing region, or forged bounds during promotion.
        val onceRegions = completedOnce["regions"] as Map<*, *>
        val tableRow = onceRegions[Image51000.TABLE] as Map<*, *>
        val emptyRow = onceRegions[Image51000.EMPTY] as Map<*, *>
        assertFails { bus(twice).restoreSnapshot(completedOnce + ("regions" to onceRegions - Image51000.TABLE), true) }
        assertFails { bus(twice).restoreSnapshot(completedOnce + ("regions" to onceRegions - Image51000.EMPTY), true) }
        for (change in listOf(
            mapOf("position" to 2705, "accessAttempts" to 2705),
            mapOf("position" to 2707, "accessAttempts" to 2707),
            mapOf("accessAttempts" to 2705),
            mapOf("endExclusive" to 0xfff54eb0L),
            mapOf("position" to "2706"),
        )) {
            val altered = completedOnce + ("regions" to onceRegions + (Image51000.TABLE to tableRow + change))
            assertFails { bus(twice).restoreSnapshot(altered, true) }
        }
        for (change in listOf(
            mapOf("position" to 1, "accessAttempts" to 1),
            mapOf("position" to 3, "accessAttempts" to 3),
            mapOf("accessAttempts" to 1),
        )) {
            val altered = completedOnce + ("regions" to onceRegions + (Image51000.EMPTY to emptyRow + change))
            assertFails { bus(twice).restoreSnapshot(altered, true) }
        }

        // Negative checks right after valid promotion (TABLE=2706, EMPTY=2).
        fun promoted() = bus(twice).apply { restoreSnapshot(completedOnce, true) }
        lr = 0x11c26b0
        assertFails { promoted().access(1, empty, 4, true, 0x3fff3fff) }
        lr = 0x11c25bc
        assertFails { promoted().access(1, base, 4, true, 0) }
        lr = 0x11c2538
        for (address in listOf(base + 1, base + 4, 0xfff53a4c, 0xfff54eac, 0xfff54eb0, 0xfff54ffc)) {
            assertFails { promoted().access(1, address, 4, true, 0) }
        }
        assertFails { promoted().access(1, base, 4, false) }
        assertFails { promoted().access(1, base, 2, true, 0) }
        core = 0
        assertFails { promoted().access(1, base, 4, true, 0) }
        core = 1
        pc += 4
        assertFails { promoted().access(1, base, 4, true, 0) }
        pc -= 4

        // Replay full pass 2 TABLE (4009 payload words + 0 terminator at FFF54EAC) across restores.
        var active = promoted()
        for (index in 0 until Image51000.SECOND_PAYLOAD_WORDS) {
            if (index in setOf(1, Image51000.TABLE_LIMIT, Image51000.SECOND_PAYLOAD_WORDS - 1)) {
                val mid = active.saveSnapshot()
                assertFails { bus(once).restoreSnapshot(mid, true) }
                assertFails { bus(previous).restoreSnapshot(mid, true) }
                active = bus(twice).apply { restoreSnapshot(mid) }
            }
            val word = 0x2000_0000L or index.toLong()
            assertEquals(word, active.access(index + 1L, base + index * 4L, 4, true, word))
        }
        val beforeTerm = active.saveSnapshot()
        // At TABLE=6715: payload LR fails, non-zero terminator fails, EMPTY pass 2 fails.
        assertFails { bus(twice).apply { restoreSnapshot(beforeTerm) }.access(1, 0xfff54eac, 4, true, 0) }
        lr = 0x11c26b0
        assertFails { bus(twice).apply { restoreSnapshot(beforeTerm) }.access(1, empty, 4, true, 0x3fff3fff) }
        lr = 0x11c25bc
        assertFails { bus(twice).apply { restoreSnapshot(beforeTerm) }.access(1, 0xfff54eac, 4, true, 1) }
        active = bus(twice).apply { restoreSnapshot(beforeTerm) }
        assertEquals(0L, active.access(1, 0xfff54eac, 4, true, 0))

        // At TABLE=6716, EMPTY=2: further TABLE writes fail; EMPTY pass 2 succeeds across restores.
        val afterSecondTable = active.saveSnapshot()
        lr = 0x11c2538
        assertFails { bus(twice).apply { restoreSnapshot(afterSecondTable) }.access(1, base, 4, true, 0) }
        lr = 0x11c25bc
        assertFails { bus(twice).apply { restoreSnapshot(afterSecondTable) }.access(1, 0xfff54eac, 4, true, 0) }
        assertFails { bus(twice).apply { restoreSnapshot(afterSecondTable) }.access(1, 0xfff54eb0, 4, true, 0) }
        lr = 0x11c26b0
        assertFails { bus(twice).apply { restoreSnapshot(afterSecondTable) }.access(1, empty, 4, true, 0) }
        assertFails { bus(twice).apply { restoreSnapshot(afterSecondTable) }.access(1, empty + 4, 4, true, 0x3fff3fff) }
        active = bus(twice).apply { restoreSnapshot(afterSecondTable) }
        assertEquals(0x3fff3fffL, active.access(1, empty, 4, true, 0x3fff3fff))

        val midSecondEmpty = active.saveSnapshot()
        // Cross-region forged cursor checks on twice snapshot.
        val midRegions = midSecondEmpty["regions"] as Map<*, *>
        val midTableRow = midRegions[Image51000.TABLE] as Map<*, *>
        val midEmptyRow = midRegions[Image51000.EMPTY] as Map<*, *>
        assertFails {
            bus(twice).restoreSnapshot(
                midSecondEmpty + ("regions" to midRegions + (Image51000.TABLE to midTableRow + mapOf("position" to 6715, "accessAttempts" to 6715))),
            )
        }
        assertFails {
            bus(twice).restoreSnapshot(
                midSecondEmpty + ("regions" to midRegions + (Image51000.TABLE to midTableRow + mapOf("position" to 2706, "accessAttempts" to 2706))),
            )
        }
        assertFails {
            bus(twice).restoreSnapshot(
                midSecondEmpty + ("regions" to midRegions +
                    (Image51000.TABLE to midTableRow + mapOf("position" to 2707, "accessAttempts" to 2707)) +
                    (Image51000.EMPTY to midEmptyRow + mapOf("position" to 1, "accessAttempts" to 1))),
            )
        }
        lr = 0x11c26c0
        assertFails { bus(twice).apply { restoreSnapshot(midSecondEmpty) }.access(1, empty + 4, 4, true, 1) }
        active = bus(twice).apply { restoreSnapshot(midSecondEmpty) }
        assertEquals(0L, active.access(1, empty + 4, 4, true, 0))

        // Exhausted at TABLE=6716, EMPTY=4.
        val exhaustedTwice = active.saveSnapshot()
        val exhRegions = exhaustedTwice["regions"] as Map<*, *>
        val exhTableRow = exhRegions[Image51000.TABLE] as Map<*, *>
        val exhEmptyRow = exhRegions[Image51000.EMPTY] as Map<*, *>
        assertEquals(6716, exhTableRow["position"])
        assertEquals(6716L, exhTableRow["accessAttempts"])
        assertEquals(4, exhEmptyRow["position"])
        assertEquals(4L, exhEmptyRow["accessAttempts"])
        assertFails { bus(once).restoreSnapshot(exhaustedTwice, true) }
        assertFails { bus(previous).restoreSnapshot(exhaustedTwice, true) }
        assertFails { bus(twice).apply { restoreSnapshot(exhaustedTwice) }.access(1, empty + 4, 4, true, 0) }
        lr = 0x11c2538
        assertFails { bus(twice).apply { restoreSnapshot(exhaustedTwice) }.access(1, base, 4, true, 0) }
        assertFails {
            bus(twice).restoreSnapshot(
                exhaustedTwice + ("regions" to exhRegions + (Image51000.TABLE to exhTableRow + mapOf("position" to 6717, "accessAttempts" to 6717))),
            )
        }
        assertFails {
            bus(twice).restoreSnapshot(
                exhaustedTwice + ("regions" to exhRegions + (Image51000.EMPTY to exhEmptyRow + mapOf("position" to 5, "accessAttempts" to 5))),
            )
        }
    }

    @Test fun repeatablePromotesOnlyFromCompletedTwiceCoversFullDatBoundsAndAcceptsNonEmptySecondTable() {
        val catalog = AddressCatalog.bundled()
        val previous = mapOf("image50000" to "retained-words-zero-v1", "image59000" to "retained-tables-v1")
        val once = previous + ("image51000" to "packed-table-pair-once-v1")
        val twice = previous + ("image51000" to "packed-table-pair-twice-v1")
        val repeatable = previous + ("image51000" to "packed-table-pair-repeatable-v1")
        var core = 1
        var pc = 0x5af98L
        var lr = 0x11c2538L
        val context = object : DiscoveryContext {
            override fun core() = core
            override fun pc() = pc
            override fun lr() = lr
            override fun tick() = 0L
        }
        fun runtime(settings: Map<String, String>, mode: String = "discovery") =
            CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to settings))
        fun bus(settings: Map<String, String>) = checkNotNull(runtime(settings).createBus(context))

        assertNull(runtime(repeatable, "strict").createBus(context))

        // Verify exact bounds: TABLE = FFF51008..FFF55000 (4094 words), EMPTY = FFF55000..FFF59000 (4096 words),
        // with zero overlap against image50000 (..FFF51008) and image59000 (FFF59000..).
        val repRegions = Image51000.regions("packed-table-pair-repeatable-v1")
        val tableDef = repRegions.single { it.id == Image51000.TABLE }
        val emptyDef = repRegions.single { it.id == Image51000.EMPTY }
        assertEquals(0xfff51008L, tableDef.base)
        assertEquals(0xfff55000L, tableDef.endExclusive)
        assertEquals(0xfff55000L, emptyDef.base)
        assertEquals(0xfff59000L, emptyDef.endExclusive)
        assertEquals(4094 * 4, tableDef.length)
        assertEquals(4096 * 4, emptyDef.length)

        // Cannot promote from missing Image51000 or from completed once.
        assertFails { bus(repeatable).restoreSnapshot(bus(previous).saveSnapshot(), true) }
        val onceBus = bus(once)
        repeat(Image51000.PAYLOAD_WORDS) { i -> onceBus.access(i + 1L, 0xfff51008L + i * 4L, 4, true, i.toLong()) }
        lr = 0x11c25bc
        onceBus.access(2706, 0xfff53a4c, 4, true, 0)
        lr = 0x11c26b0
        onceBus.access(2707, 0xfff55000, 4, true, 0x3fff3fff)
        lr = 0x11c26c0
        onceBus.access(2708, 0xfff55004, 4, true, 0)
        val completedOnce = onceBus.saveSnapshot()
        assertFails { bus(repeatable).restoreSnapshot(completedOnce, true) }

        // Drive twice to completed TABLE=6716, EMPTY=4, verifying partial twice cannot promote.
        val twiceBus = bus(twice).apply { restoreSnapshot(completedOnce, true) }
        assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
        lr = 0x11c2538
        repeat(Image51000.SECOND_PAYLOAD_WORDS) { i -> twiceBus.access(i + 1L, 0xfff51008L + i * 4L, 4, true, i.toLong()) }
        lr = 0x11c25bc
        twiceBus.access(1, 0xfff54eac, 4, true, 0)
        assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
        lr = 0x11c26b0
        twiceBus.access(1, 0xfff55000, 4, true, 0x3fff3fff)
        assertFails { bus(repeatable).restoreSnapshot(twiceBus.saveSnapshot(), true) }
        lr = 0x11c26c0
        twiceBus.access(1, 0xfff55004, 4, true, 0)
        val completedTwice = twiceBus.saveSnapshot()

        // Requires allowPromotedRegions = true, rejects forged bounds/cursors on promotion.
        assertFails { bus(repeatable).restoreSnapshot(completedTwice) }
        val twiceRegions = completedTwice["regions"] as Map<*, *>
        val twiceTableRow = twiceRegions[Image51000.TABLE] as Map<*, *>
        val twiceEmptyRow = twiceRegions[Image51000.EMPTY] as Map<*, *>
        assertFails {
            bus(repeatable).restoreSnapshot(
                completedTwice + ("regions" to twiceRegions + (Image51000.TABLE to twiceTableRow + mapOf("position" to 6715, "accessAttempts" to 6715))),
                true,
            )
        }
        assertFails {
            bus(repeatable).restoreSnapshot(
                completedTwice + ("regions" to twiceRegions + (Image51000.EMPTY to twiceEmptyRow + mapOf("position" to 3, "accessAttempts" to 3))),
                true,
            )
        }
        assertFails {
            bus(repeatable).restoreSnapshot(
                completedTwice + ("regions" to twiceRegions + (Image51000.TABLE to twiceTableRow + mapOf("endExclusive" to 0xfff55000L))),
                true,
            )
        }

        // Promote cleanly from completed twice and verify TABLE + EMPTY repeatable writes (both empty and non-empty 011C25C4).
        fun promoted() = bus(repeatable).apply { restoreSnapshot(completedTwice, true) }
        var active = promoted()

        // TABLE: writes across full FFF51008..FFF54FFC via 011C2538 and 0-terminator via 011C25BC.
        lr = 0x11c2538
        assertEquals(0x11223344L, active.access(1, 0xfff51008, 4, true, 0x11223344))
        assertEquals(0x55667788L, active.access(2, 0xfff54ffc, 4, true, 0x55667788))
        lr = 0x11c25bc
        assertEquals(0L, active.access(3, 0xfff54eb0, 4, true, 0))

        // EMPTY: empty sentinel writes (011C26B0/011C26C0) AND non-empty table writes (011C2654/011C26D8) up to FFF58FFC.
        lr = 0x11c26b0
        assertEquals(0x3fff3fffL, active.access(4, 0xfff55000, 4, true, 0x3fff3fff))
        lr = 0x11c26c0
        assertEquals(0L, active.access(5, 0xfff55004, 4, true, 0))
        lr = 0x11c2654
        assertEquals(0xabcdef01L, active.access(6, 0xfff55000, 4, true, 0xabcdef01))
        assertEquals(0x23456789L, active.access(7, 0xfff58ffc, 4, true, 0x23456789))
        lr = 0x11c26d8
        assertEquals(0L, active.access(8, 0xfff55100, 4, true, 0))

        // Snapshot round-trip preserves independent TABLE/EMPTY positions and expanded bounds; downgrades rejected.
        val snap = active.saveSnapshot()
        assertFails { bus(twice).restoreSnapshot(snap, true) }
        assertFails { bus(once).restoreSnapshot(snap, true) }
        assertFails { bus(previous).restoreSnapshot(snap, true) }
        val snapRegions = snap["regions"] as Map<*, *>
        val snapTable = snapRegions[Image51000.TABLE] as Map<*, *>
        val snapEmpty = snapRegions[Image51000.EMPTY] as Map<*, *>
        assertEquals(6719, snapTable["position"])
        assertEquals(6719L, snapTable["accessAttempts"])
        assertEquals(0xfff55000L, snapTable["endExclusive"])
        assertEquals(9, snapEmpty["position"])
        assertEquals(9L, snapEmpty["accessAttempts"])
        assertEquals(0xfff59000L, snapEmpty["endExclusive"])

        active = bus(repeatable).apply { restoreSnapshot(snap) }
        lr = 0x11c2538
        assertEquals(0xdeadbeefL, active.access(9, 0xfff51008, 4, true, 0xdeadbeef))

        // Negative checks on restored repeatable bus.
        fun fresh() = bus(repeatable).apply { restoreSnapshot(snap) }
        lr = 0x11c25bc
        assertFails { fresh().access(1, 0xfff54eb0, 4, true, 1) } // non-zero TABLE terminator
        lr = 0x11c26d8
        assertFails { fresh().access(1, 0xfff55100, 4, true, 1) } // non-zero EMPTY terminator
        lr = 0x11c26b0
        assertFails { fresh().access(1, 0xfff55000, 4, true, 0) } // wrong sentinel value
        assertFails { fresh().access(1, 0xfff55004, 4, true, 0x3fff3fff) } // wrong sentinel offset
        lr = 0x11c26c0
        assertFails { fresh().access(1, 0xfff55004, 4, true, 1) } // non-zero sentinel word 1
        assertFails { fresh().access(1, 0xfff55000, 4, true, 0) } // wrong sentinel offset
        lr = 0x11c2654
        assertFails { fresh().access(1, 0xfff51008, 4, true, 0) } // EMPTY LR on TABLE
        lr = 0x11c2538
        assertFails { fresh().access(1, 0xfff55000, 4, true, 0) } // TABLE LR on EMPTY
        assertFails { fresh().access(1, 0xfff51008, 4, false) } // read refused
        assertFails { fresh().access(1, 0xfff55000, 4, false) } // read refused
        assertFails { fresh().access(1, 0xfff51008, 2, true, 0) } // wrong width
        assertFails { fresh().access(1, 0xfff51009, 4, true, 0) } // unaligned
        core = 0
        assertFails { fresh().access(1, 0xfff51008, 4, true, 0) }
        core = 1
        pc += 4
        assertFails { fresh().access(1, 0xfff51008, 4, true, 0) }
        pc -= 4
    }
}
