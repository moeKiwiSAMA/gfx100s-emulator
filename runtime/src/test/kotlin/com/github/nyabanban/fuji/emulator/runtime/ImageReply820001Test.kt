package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.junit.jupiter.api.Test

class ImageReply820001Test {
    private var requestMatches = true
    private var requestSelector = 1
    private var returnAddress = 0x11b669cL
    private var coreId = 1
    private var programCounter = 0x5afe0L
    private val callers = listOf(0x11b669cL, 0x11b667cL, 0x11b665cL, 0x11b663cL,
        0x11b661cL, 0x11b65fcL, 0x11b65dcL, 0x11b65b8L)
    private val context = object : DiscoveryContext {
        override fun core() = coreId
        override fun pc() = programCounter
        override fun lr() = returnAddress
        override fun tick() = 0L
        override fun hasImageRequest82(selector: Int) = requestMatches && selector == requestSelector
    }
    private val catalog = AddressCatalog.bundled()
    private fun bus(enabled: Boolean = true, selection: String = "zero-once-v1", key: String = "imageReply820001"): DiscoveryBus {
        val settings = if (enabled) mapOf(key to selection) else emptyMap()
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
    }

    @Test fun consumesOneOrderedReplyAcrossRestore() {
        var active = bus().apply { restoreSnapshot(bus(false).saveSnapshot(), true) }
        for ((position, caller) in callers.withIndex()) {
            if (position == 3) {
                val partial = active.saveSnapshot()
                assertFails { bus(false).restoreSnapshot(partial, true) }
                active = bus().apply { restoreSnapshot(partial) }
            }
            returnAddress = caller
            assertEquals(0L, active.access(position.toLong(), 0xfff6c240L - 4 * position, 4, false))
        }
        assertFails { active.selectScriptInputTail(IMAGE_REPLY_820001, 8, List(9) { 0L }) }
        returnAddress = callers.first()
        assertFails { active.access(9, 0xfff6c240L, 4, false) }
    }

    @Test fun requiresMatchingRequestAndWordAccess() {
        val first = 0xfff6c240L
        assertFails { bus(false).access(0, first, 4, false) }
        assertFails { bus().access(0, first, 4, true) }
        assertFails { bus().access(0, first, 2, false) }
        assertFails { bus().access(0, first - 1, 4, false) }
        assertFails { bus().access(0, first - 4, 4, false) }
        returnAddress = 0x11b667c
        assertFails { bus().access(0, first, 4, false) }
        returnAddress = 0x11b669c
        requestMatches = false
        assertFails { bus().access(0, first, 4, false) }
    }

    @Test fun alternateReplyBindsFirstByteAndSnapshotSelection() {
        val active = bus(selection = "af-once-v1")
        assertEquals(0x00af0000L, active.access(1, 0xfff6c240L, 4, false))
        val saved = active.saveSnapshot()
        assertFails { bus().restoreSnapshot(saved, true) }
        val resumed = bus(selection = "af-once-v1").apply { restoreSnapshot(saved) }
        returnAddress = 0x11b667c
        assertEquals(0L, resumed.access(2, 0xfff6c23cL, 4, false))
    }

    @Test fun successorRequestsRequireCompletePredecessorAndExactSelector() {
        var active = bus(selection = "af-once-v1")
        for (selector in listOf(1, 0x40, 0x41)) {
            requestSelector = selector
            for ((position, caller) in callers.withIndex()) {
                returnAddress = caller
                val expected = if (selector == 1 && position == 0) 0x00af0000L else 0L
                assertEquals(expected, active.access(position.toLong(), 0xfff6c240L - position * 4, 4, false))
                if (position == 6 && selector == 1) {
                    assertFails { bus(key = "imageReply820040").restoreSnapshot(active.saveSnapshot(), true) }
                }
            }
            val saved = active.saveSnapshot()
            returnAddress = callers.first()
            assertFails { active.access(9, 0xfff6c240L, 4, false) }
            if (selector == 0x41) {
                assertFails { bus(key = "imageReply820040").restoreSnapshot(saved, true) }
                break
            }
            val key = if (selector == 1) "imageReply820040" else "imageReply820041"
            if (selector == 1) assertFails { bus(key = "imageReply820041").restoreSnapshot(saved, true) }
            assertFails { bus(key = key).restoreSnapshot(saved) }
            assertFails { bus(key = key).apply { restoreSnapshot(saved, true) }.access(0, 0xfff6c240, 4, false) }
            active = bus(key = key).apply { restoreSnapshot(saved, true) }
        }
    }

    private fun pairs() = bus(key = "imageReply820040", selection = "zero-alternating-16-pairs-v1")

    private fun interleaved(single: String): DiscoveryBus {
        val selections = mapOf("imageReply820040" to "zero-alternating-16-pairs-v1", "imageReply820001" to single)
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to selections)).createBus(context))
    }

    @Test fun interleavesExactlyOne01AndResumesTheFourthPairWithoutReplay() {
        val previous = pairs()
        repeat(48) { readWord(previous, it, if (it % 16 < 8) 0x40 else 0x41) }
        val boundary = previous.saveSnapshot()
        for (selection in listOf("zero-once-v1", "af-once-v1")) {
            assertFails { interleaved(selection).restoreSnapshot(boundary) }
            var active = interleaved(selection).apply { restoreSnapshot(boundary, true) }
            for ((word, caller) in callers.withIndex()) {
                val partial = active.saveSnapshot()
                active = interleaved(selection).apply { restoreSnapshot(partial) }
                assertEquals(48, receipt(active)["sampleIndex"])
                assertEquals(word, receipt(active)["reply01Position"])
                assertFails { pairs().restoreSnapshot(partial, true) }
                assertFails { interleaved(if (selection == "zero-once-v1") "af-once-v1" else "zero-once-v1")
                    .restoreSnapshot(partial, true) }
                if (word != 0) assertFails { readWord(interleaved(selection).apply { restoreSnapshot(partial) }, 48, 0x40) }
                requestSelector = 1
                returnAddress = caller
                val expected = if (word == 0 && selection == "af-once-v1") 0x00af0000L else 0L
                assertEquals(expected, active.access(word.toLong(), 0xfff6c240L - 4 * word, 4, false))
                val access = (active.boundedSnapshot(1)["accesses"] as List<*>).single() as Map<*, *>
                assertEquals("sample-index", access["contextKind"])
                assertEquals(256L + word, access["before"])
            }
            val spent = active.saveSnapshot()
            returnAddress = callers.first()
            assertFails { interleaved(selection).apply { restoreSnapshot(spent) }.access(8, 0xfff6c240, 4, false) }
            active = interleaved(selection).apply { restoreSnapshot(spent) }
            for (position in 48 until 256) readWord(active, position, if (position % 16 < 8) 0x40 else 0x41)
            assertEquals(256, receipt(active)["sampleIndex"])
            assertEquals(8, receipt(active)["reply01Position"])
            val end = interleaved(selection).apply { restoreSnapshot(active.saveSnapshot()) }
            assertFails { readWord(end, 256, 0x40) }
        }
    }

    @Test fun refusesPartialPairInterruptionBadReadAndInvalidInterleaveHistory() {
        val active = interleaved("zero-once-v1")
        readWord(active, 0, 0x40)
        val partialPair = active.saveSnapshot()
        returnAddress = callers.first()
        requestSelector = 1
        assertFails { active.access(1, 0xfff6c240, 4, false) }
        assertEquals(0, receipt(active)["reply01Position"])
        val regions = partialPair["regions"] as Map<*, *>
        val row = regions[IMAGE_REPLY_PAIRS] as Map<*, *>
        for (invalid in listOf(row - "reply01Position", row + ("reply01Position" to "0"),
            row + ("accessAttempts" to 2), row + ("reply01Position" to 9),
            row + mapOf("reply01Position" to 1, "accessAttempts" to 2))) {
            assertFails { interleaved("zero-once-v1").restoreSnapshot(partialPair +
                ("regions" to regions + (IMAGE_REPLY_PAIRS to invalid)), true) }
        }
        fun reject(address: Long = 0xfff6c240, width: Int = 4, write: Boolean = false) {
            val fresh = interleaved("zero-once-v1")
            assertFails { fresh.access(0, address, width, write) }
            assertEquals(0, receipt(fresh)["sampleIndex"])
            assertEquals(0, receipt(fresh)["reply01Position"])
        }
        reject(width = 2)
        reject(write = true)
        reject(address = 0xfff6c23c)
        reject(address = 0xfff6c23f)
        returnAddress = callers[1]
        reject()
        returnAddress = callers.first()
        coreId = 0
        reject()
        coreId = 1
        programCounter += 4
        reject()
        programCounter -= 4
        requestSelector = 2
        reject()
    }

    private fun readWord(active: DiscoveryBus, position: Int, selector: Int) {
        val word = position % 8
        requestSelector = selector
        returnAddress = callers[word]
        assertEquals(0L, active.access(position.toLong(), 0xfff6c240L - 4 * word, 4, false))
    }

    private fun receipt(active: DiscoveryBus): Map<*, *> =
        (active.boundedSnapshot(1)["regions"] as List<*>).filterIsInstance<Map<*, *>>().single { it["id"] == IMAGE_REPLY_PAIRS }

    @Test fun rejectsCorruptPairAndPromotionCursors() {
        for (id in listOf(IMAGE_REPLY_820041, IMAGE_REPLY_PAIRS)) {
            val source = if (id == IMAGE_REPLY_PAIRS) pairs() else bus(key = "imageReply820041")
            repeat(8) { readWord(source, it, if (id == IMAGE_REPLY_PAIRS) 0x40 else 0x41) }
            val saved = source.saveSnapshot()
            val regions = saved["regions"] as Map<*, *>
            val row = regions[id] as Map<*, *>
            for (invalid in listOf(row + ("accessAttempts" to 9), row + ("accessAttempts" to "8"),
                row + ("base" to 0xfff6c220L), row + ("scriptTailSelection" to emptyMap<String, Any>()),
                row + mapOf("position" to 257, "accessAttempts" to 257))) {
                assertFails { pairs().restoreSnapshot(saved + ("regions" to regions + (id to invalid)), true) }
            }
        }
    }

    @Test fun spent41PromotesAtPairTwoAndStopsAfterSixteenPairs() {
        val first40 = bus(key = "imageReply820040")
        repeat(8) { readWord(first40, it, 0x40) }
        val first41 = bus(key = "imageReply820041").apply { restoreSnapshot(first40.saveSnapshot(), true) }
        assertFails { pairs().restoreSnapshot(first40.saveSnapshot(), true) }
        repeat(7) { readWord(first41, it, 0x41) }
        assertFails { pairs().restoreSnapshot(first41.saveSnapshot(), true) }
        readWord(first41, 7, 0x41)
        val spent = first41.saveSnapshot()
        assertFails { pairs().restoreSnapshot(bus(false).saveSnapshot(), true) }
        assertFails { pairs().restoreSnapshot(spent) }
        var active = pairs().apply { restoreSnapshot(spent, true) }
        for (position in 16 until 256) {
            if (position in listOf(23, 24, 32, 255)) active = pairs().apply { restoreSnapshot(active.saveSnapshot()) }
            val selector = if (position % 16 < 8) 0x40 else 0x41
            val state = receipt(active)
            assertEquals(position, state["sampleIndex"])
            assertEquals(position / 16, state["completedPairs"])
            assertEquals(selector, state["nextSelector"])
            assertEquals(position % 8, state["nextWordIndex"])
            readWord(active, position, selector)
        }
        val savedEnd = active.saveSnapshot()
        val end = pairs().apply { restoreSnapshot(savedEnd) }
        assertEquals(16, receipt(end)["completedPairs"])
        assertEquals(null, receipt(end)["nextSelector"])
        assertEquals(null, receipt(end)["nextWordIndex"])
        assertFails { bus(key = "imageReply820041").restoreSnapshot(savedEnd, true) }
        assertFails { end.selectScriptInputTail(IMAGE_REPLY_PAIRS, 256, List(257) { 0L }) }
        requestSelector = 0x40
        returnAddress = callers.first()
        assertFails { end.access(256, 0xfff6c240, 4, false) }
        assertEquals(256, receipt(end)["sampleIndex"])
    }

    @Test fun pairRejectsWrongContextSelectorAndWordWithoutConsuming() {
        val active = pairs()
        repeat(8) { readWord(active, it, 0x40) }
        val boundary = active.saveSnapshot()
        fun reject(address: Long = 0xfff6c240L, width: Int = 4, write: Boolean = false) {
            val restored = pairs().apply { restoreSnapshot(boundary) }
            assertFails { restored.access(8, address, width, write) }
            assertEquals(8, receipt(restored)["sampleIndex"])
        }
        returnAddress = callers.first()
        reject()
        requestSelector = 0x41
        coreId = 0
        reject()
        coreId = 1
        programCounter += 4
        reject()
        programCounter -= 4
        returnAddress = callers[1]
        reject()
        returnAddress = callers.first()
        reject(0xfff6c23c)
        reject(0xfff6c23f)
        reject(width = 2)
        reject(write = true)
        readWord(active, 8, 0x41)
        requestSelector = 0x40
        returnAddress = callers[1]
        assertFails { active.access(9, 0xfff6c23c, 4, false) }
        assertEquals(9, receipt(active)["sampleIndex"])
    }
}
