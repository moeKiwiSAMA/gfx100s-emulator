package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.junit.jupiter.api.Test

class Image6e000Test {
    private val base = 0xfff6e000L
    private val config = "config-pairs-once-v1"
    private val stop = "config-pairs-stop-poll-once-v1"
    private val disable = "config-pairs-disable-bit7-once-v1"
    private val secondConfig = "config-pairs-disable-bit7-second-config-once-v1"
    private var disableMode = true
    private var core = 1
    private var pc = 0x5af98L
    private var lr = 0x11e3b48L
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun tick() = 832805429L
        override fun pc() = pc
        override fun lr() = lr
        override fun image6eDisableMode() = disableMode
    }
    private fun bus(selection: String? = null): DiscoveryBus {
        val settings = selection?.let { mapOf("image6e000" to it) }.orEmpty()
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
    }

    @Test fun routesOneNewPageAndRefusesOtherAccesses() {
        fun pages(selection: String?) = MmioRouter(emptyList(), bus(selection)).pageRanges()
        assertEquals(listOf(base to 4096L), pages(stop) - pages(null).toSet())
        assertEquals(pages(config), pages(stop))
        assertFails { bus("retained-bytes") }
        fun attempt(offset: Int = 0x14, width: Int = 4, write: Boolean = true, word: Long = 0x2c0f30) =
            bus(stop).access(0, base + offset, width, write, word)
        for (offset in listOf(0, 4, 8, 12, 0x10, 0x15, 0x18, 0x1c, 0x40, 0x70)) assertFails { attempt(offset) }
        assertFails { attempt(width = 2) }
        assertFails { attempt(write = false) }
        assertFails { attempt(word = 0x2c0f31) }
        core = 0
        assertFails { attempt() }
        core = 1
        pc = 0x5afe0
        assertFails { attempt() }
        pc = 0x5af98
        lr = 0x11e3b68
        assertFails { attempt() }
    }

    @Test fun restoresEachBoundaryAndRejectsReplaysAndInvalidPromotion() {
        val old = bus().saveSnapshot()
        assertFails { bus(config).restoreSnapshot(old) }
        var active = bus(config).apply { restoreSnapshot(old, true) }
        val callers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74, 0x11e33cc, 0x11e3378,
            0x11e33f0, 0x11e33fc, 0x11e3354)
        val offsets = listOf(0x14, 0x18, 0x20, 0x24, 0x68, 0x6c, 0, 0, 0)
        val words = listOf(0x2c0f30L, 0x1003ee, 0xf50, 0x1003da, 0, 1, 0, 0, 1)
        for (position in callers.indices) {
            val saved = active.saveSnapshot()
            val write = position != 6 && position != 8
            pc = if (write) 0x5af98 else 0x5afe0
            lr = callers[position]
            if (position == 4) {
                assertFails { bus(stop).restoreSnapshot(saved) }
                assertFails { active.access(1, base + 0x68, 4, true, 0) }
                assertFails { bus().restoreSnapshot(saved, true) }
                val regions = saved.getValue("regions") as Map<*, *>
                val row = regions[Image6e000.REGION] as Map<*, *>
                val forged = saved + ("regions" to regions + (Image6e000.REGION to row + mapOf("position" to 5, "accessAttempts" to 5)))
                assertFails { bus(stop).restoreSnapshot(forged, true) }
            }
            active = bus(if (position < 4) config else stop).apply { restoreSnapshot(saved, position == 4) }
            assertEquals(words[position], active.access(1, base + offsets[position], 4, write, if (write) words[position] else 0))
        }
        val exhausted = active.saveSnapshot()
        assertFails { bus(config).restoreSnapshot(exhausted, true) }
        assertFails { bus(stop).apply { restoreSnapshot(exhausted) }.access(1, base, 4, false) }
    }

    @Test fun disableBranchesOnlyAfterFourConfigWritesAndRestoresBetweenReadAndWrite() {
        val old = bus(stop)
        val offsets = listOf(0x14, 0x18, 0x20, 0x24)
        val callers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74)
        val words = listOf(0x2c0f30L, 0x1003ee, 0xf50, 0x1003da)
        assertFails { bus(disable).restoreSnapshot(bus().saveSnapshot(), true) }
        for (index in offsets.indices) {
            assertFails { bus(disable).restoreSnapshot(old.saveSnapshot(), true) }
            lr = callers[index]
            old.access(index.toLong(), base + offsets[index], 4, true, words[index])
        }
        val saved = old.saveSnapshot()
        fun promoted() = bus(disable).apply { restoreSnapshot(saved, true) }
        assertFails { bus(disable).restoreSnapshot(saved) }
        lr = 0x11e33cc
        old.access(5, base + 0x68, 4, true, 0)
        assertFails { bus(disable).restoreSnapshot(old.saveSnapshot(), true) }
        assertFails { promoted().access(1, base + 0x68, 4, true, 0) }
        pc = 0x5afe0
        lr = 0x11e37dc
        disableMode = false
        assertFails { promoted().access(1, base + 8, 4, false) }
        disableMode = true
        assertFails { bus(stop).apply { restoreSnapshot(saved) }.access(1, base + 8, 4, false) }
        assertFails { promoted().access(1, base + 8, 2, false) }
        assertFails { promoted().access(1, base + 8, 4, true) }
        core = 0
        assertFails { promoted().access(1, base + 8, 4, false) }
        core = 1
        lr += 4
        assertFails { promoted().access(1, base + 8, 4, false) }
        lr -= 4
        val active = promoted()
        assertEquals(0L, active.access(1, base + 8, 4, false))
        val half = active.saveSnapshot()
        fun resumed() = bus(disable).apply { restoreSnapshot(half) }
        assertFails { bus(stop).restoreSnapshot(half, true) }
        assertFails { resumed().access(2, base + 8, 4, false) }
        pc = 0x5af98
        lr = 0x11e3828
        for (bad in listOf(0x80L, 1L)) assertFails { resumed().access(2, base + 8, 4, true, bad) }
        val last = resumed()
        assertEquals(0L, last.access(2, base + 8, 4, true, 0))
        pc = 0x5afe0
        lr = 0x11e3354
        assertFails { bus(disable).apply { restoreSnapshot(last.saveSnapshot()) }.access(3, base, 4, false) }
    }

    private fun runFirstConfigAndDisable(target: DiscoveryBus) {
        val offsets = listOf(0x14, 0x18, 0x20, 0x24)
        val callers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74)
        val words = listOf(0x2c0f30L, 0x1003ee, 0xf50, 0x1003da)
        pc = 0x5af98
        for (index in offsets.indices) {
            lr = callers[index]
            assertEquals(words[index], target.access(index.toLong(), base + offsets[index], 4, true, words[index]))
        }
        disableMode = true
        pc = 0x5afe0
        lr = 0x11e37dc
        assertEquals(0L, target.access(4, base + 8, 4, false))
        pc = 0x5af98
        lr = 0x11e3828
        assertEquals(0L, target.access(5, base + 8, 4, true, 0))
    }

    @Test fun secondConfigPromotesOnlyFromCompletedDisableAndAcceptsObservedWritesAcrossRestores() {
        assertEquals(
            MmioRouter(emptyList(), bus(disable)).pageRanges(),
            MmioRouter(emptyList(), bus(secondConfig)).pageRanges(),
        )
        // Fresh secondConfig still enforces exact round-1 words at cursor 0.
        pc = 0x5af98
        lr = 0x11e3b48
        assertFails { bus(secondConfig).access(0, base + 0x14, 4, true, 0x00100f30L) }

        // Rejects promotion from snapshots without Image6e000, from config-pairs-once-v1, and from stop-poll.
        assertFails { bus(secondConfig).restoreSnapshot(bus().saveSnapshot(), true) }
        val configBus = bus(config)
        val stopBus = bus(stop)
        val round1Offsets = listOf(0x14, 0x18, 0x20, 0x24)
        val round1Callers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74)
        val round1Words = listOf(0x2c0f30L, 0x1003ee, 0xf50, 0x1003da)
        for (index in round1Offsets.indices) {
            lr = round1Callers[index]
            configBus.access(index.toLong(), base + round1Offsets[index], 4, true, round1Words[index])
            stopBus.access(index.toLong(), base + round1Offsets[index], 4, true, round1Words[index])
            assertFails { bus(secondConfig).restoreSnapshot(configBus.saveSnapshot(), true) }
            assertFails { bus(secondConfig).restoreSnapshot(stopBus.saveSnapshot(), true) }
        }
        // Advance stopBus to position 6 (same numeric cursor as completed disable) and verify it is still rejected.
        lr = 0x11e33cc
        stopBus.access(4, base + 0x68, 4, true, 0)
        lr = 0x11e3378
        stopBus.access(5, base + 0x6c, 4, true, 1)
        assertFails { bus(secondConfig).restoreSnapshot(stopBus.saveSnapshot(), true) }

        // Rejects promotion from disable at every cursor < 6.
        val disableBus = bus(disable)
        for (index in round1Offsets.indices) {
            assertFails { bus(secondConfig).restoreSnapshot(disableBus.saveSnapshot(), true) }
            lr = round1Callers[index]
            disableBus.access(index.toLong(), base + round1Offsets[index], 4, true, round1Words[index])
        }
        assertFails { bus(secondConfig).restoreSnapshot(disableBus.saveSnapshot(), true) }
        disableMode = true
        pc = 0x5afe0
        lr = 0x11e37dc
        disableBus.access(4, base + 8, 4, false)
        assertFails { bus(secondConfig).restoreSnapshot(disableBus.saveSnapshot(), true) }
        pc = 0x5af98
        lr = 0x11e3828
        disableBus.access(5, base + 8, 4, true, 0)

        val completedDisable = disableBus.saveSnapshot()
        // Rejects without allowPromotedRegions=true and rejects forged position/attempt counts.
        assertFails { bus(secondConfig).restoreSnapshot(completedDisable) }
        val savedRegions = completedDisable.getValue("regions") as Map<*, *>
        val savedRow = savedRegions[Image6e000.REGION] as Map<*, *>
        val forgedPos7 = completedDisable + ("regions" to savedRegions +
            (Image6e000.REGION to savedRow + mapOf("position" to 7, "accessAttempts" to 7)))
        val forgedAttempts = completedDisable + ("regions" to savedRegions +
            (Image6e000.REGION to savedRow + mapOf("accessAttempts" to 5)))
        assertFails { bus(secondConfig).restoreSnapshot(forgedPos7, true) }
        assertFails { bus(secondConfig).restoreSnapshot(forgedAttempts, true) }

        // Promotes cleanly from disable cursor 6 and replays observed on-t79-setter writes across restores.
        val observedWords = listOf(0x00100f30L, 0x009a0468L, 0x00000f50L, 0x009a0454L)
        disableMode = false
        var active = bus(secondConfig).apply { restoreSnapshot(completedDisable, true) }
        for (step in round1Offsets.indices) {
            val boundary = active.saveSnapshot()
            assertFails { bus(disable).restoreSnapshot(boundary, true) }
            assertFails { bus(stop).restoreSnapshot(boundary, true) }
            assertFails { bus(config).restoreSnapshot(boundary, true) }
            assertFails { bus().restoreSnapshot(boundary, true) }
            active = bus(secondConfig).apply { restoreSnapshot(boundary) }
            pc = 0x5af98
            lr = round1Callers[step]
            assertEquals(
                observedWords[step],
                active.access(step.toLong(), base + round1Offsets[step], 4, true, observedWords[step]),
            )
        }

        // Exhausted at cursor 10: rejects further writes/reads, rejects downgrades, rejects forged cursor 11.
        val exhausted = active.saveSnapshot()
        assertFails { bus(disable).restoreSnapshot(exhausted, true) }
        val exhaustedRegions = exhausted.getValue("regions") as Map<*, *>
        val exhaustedRow = exhaustedRegions[Image6e000.REGION] as Map<*, *>
        val forgedPos11 = exhausted + ("regions" to exhaustedRegions +
            (Image6e000.REGION to exhaustedRow + mapOf("position" to 11, "accessAttempts" to 11)))
        assertFails { bus(secondConfig).restoreSnapshot(forgedPos11) }
        pc = 0x5af98
        lr = 0x11e3b48
        assertFails { bus(secondConfig).apply { restoreSnapshot(exhausted) }.access(10, base + 0x14, 4, true, 0x00100f30L) }
        pc = 0x5afe0
        lr = 0x11e37dc
        disableMode = true
        assertFails { bus(secondConfig).apply { restoreSnapshot(exhausted) }.access(10, base + 8, 4, false) }
    }

    @Test fun secondConfigEnforcesExactDatBoundsAndCallerContext() {
        val seedBus = bus(disable)
        runFirstConfigAndDisable(seedBus)
        val atCursor6 = seedBus.saveSnapshot()

        val offsets = listOf(0x14, 0x18, 0x20, 0x24)
        val callers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74)
        val validLow1000 = listOf(0x00000001L, 0x00001000L, 0x3fff0001L, 0x3fff1000L)
        val validLow4000 = listOf(0x00000001L, 0x00004000L, 0x3fff0001L, 0x3fff4000L)
        val invalidLow1000 = listOf(
            0x00000000L, 0x00100000L, 0x00001001L, 0x00002000L,
            0x00004000L, 0x0000ffffL, 0x40000001L, 0x80000001L, 0xffff1000L,
        )
        val invalidLow4000 = listOf(
            0x00000000L, 0x009a0000L, 0x00004001L, 0x00008000L,
            0x0000ffffL, 0x40000001L, 0x80000001L, 0xffff4000L,
        )

        fun stateAtStep(step: Int): Map<String, Any?> {
            val b = bus(secondConfig).apply { restoreSnapshot(atCursor6, true) }
            val defaults = listOf(0x00100f30L, 0x009a0468L, 0x00000f50L, 0x009a0454L)
            pc = 0x5af98
            for (i in 0 until step) {
                lr = callers[i]
                b.access(i.toLong(), base + offsets[i], 4, true, defaults[i])
            }
            return b.saveSnapshot()
        }

        for (step in offsets.indices) {
            val saved = stateAtStep(step)
            val offset = offsets[step]
            val caller = callers[step]
            val validWords = if (offset == 0x14 || offset == 0x20) validLow1000 else validLow4000
            val invalidWords = if (offset == 0x14 || offset == 0x20) invalidLow1000 else invalidLow4000

            fun fresh() = bus(secondConfig).apply { restoreSnapshot(saved) }

            for (word in validWords) {
                core = 1
                pc = 0x5af98
                lr = caller
                assertEquals(word, fresh().access(1, base + offset, 4, true, word))
            }
            for (word in invalidWords) {
                core = 1
                pc = 0x5af98
                lr = caller
                assertFails { fresh().access(1, base + offset, 4, true, word) }
            }

            // Wrong direction, width, offset, core, PC, LR.
            val sample = validWords.first()
            core = 1
            pc = 0x5afe0
            lr = caller
            assertFails { fresh().access(1, base + offset, 4, false) }
            pc = 0x5af98
            assertFails { fresh().access(1, base + offset, 2, true, sample and 0xffff) }
            assertFails { fresh().access(1, base + offsets[(step + 1) % 4], 4, true, sample) }
            assertFails { fresh().access(1, base + 8, 4, true, 0) }
            assertFails { fresh().access(1, base + 0x68, 4, true, 0) }
            core = 0
            assertFails { fresh().access(1, base + offset, 4, true, sample) }
            core = 1
            pc = 0x5afe0
            assertFails { fresh().access(1, base + offset, 4, true, sample) }
            pc = 0x5af98
            lr = callers[(step + 1) % 4]
            assertFails { fresh().access(1, base + offset, 4, true, sample) }
            lr = caller + 4
            assertFails { fresh().access(1, base + offset, 4, true, sample) }
        }
    }

    @Test fun repeatablePromotesFromSecondConfigEnforcesBoundsAndHolesAndRetainsWordsAcrossRestores() {
        val repeatable = Image6e000.REPEATABLE_PROFILE
        assertEquals(
            MmioRouter(emptyList(), bus(secondConfig)).pageRanges(),
            MmioRouter(emptyList(), bus(repeatable)).pageRanges(),
        )
        // Strict mode returns null.
        assertEquals(
            null,
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf("image6e000" to repeatable))).createBus(context),
        )

        // Verify exact prohibited holes on repeatable region definition.
        val region = Image6e000.region(repeatable)
        for (offset in 0 until Image6e000.SIZE step 4) {
            val address = base + offset
            val prohibited = region.prohibited.any { it.overlaps(address, address + 4) }
            assertEquals(offset !in Image6e000.repeatableOffsets, prohibited, "Offset 0x${offset.toString(16)} prohibited mismatch")
        }

        // Build secondConfig snapshots at cursors 6, 8, and 10; also build disable cursor 6.
        val disableBus = bus(disable)
        runFirstConfigAndDisable(disableBus)
        val completedDisable = disableBus.saveSnapshot()
        assertFails { bus(repeatable).restoreSnapshot(bus().saveSnapshot(), true) }
        assertFails { bus(repeatable).restoreSnapshot(completedDisable, true) }

        val secondBus = bus(secondConfig).apply { restoreSnapshot(completedDisable, true) }
        val at6 = secondBus.saveSnapshot()
        val observedOffsets = listOf(0x14, 0x18, 0x20, 0x24)
        val observedCallers = listOf(0x11e3b48L, 0x11e3b68, 0x11e3c54, 0x11e3c74)
        val observedWords = listOf(0x00100f30L, 0x009a0468L, 0x00000f50L, 0x009a0454L)
        pc = 0x5af98
        for (i in 0 until 2) {
            lr = observedCallers[i]
            secondBus.access(i.toLong() + 1, base + observedOffsets[i], 4, true, observedWords[i])
        }
        val at8 = secondBus.saveSnapshot()
        for (i in 2 until 4) {
            lr = observedCallers[i]
            secondBus.access(i.toLong() + 1, base + observedOffsets[i], 4, true, observedWords[i])
        }
        val at10 = secondBus.saveSnapshot()

        // Reject without allowPromotedRegions = true, and reject cursor < 6 on secondConfig.
        assertFails { bus(repeatable).restoreSnapshot(at10) }
        val freshSecond = bus(secondConfig)
        pc = 0x5af98; lr = 0x11e3b48
        freshSecond.access(1, base + 0x14, 4, true, 0x2c0f30)
        assertFails { bus(repeatable).restoreSnapshot(freshSecond.saveSnapshot(), true) }

        // Promote seamlessly from cursors 6, 8, and 10.
        for (source in listOf(at6, at8, at10)) {
            val promoted = bus(repeatable).apply { restoreSnapshot(source, true) }
            val snap = promoted.saveSnapshot()
            assertEquals(snap, bus(repeatable).apply { restoreSnapshot(snap) }.saveSnapshot())
        }

        // Exercise all 8 allowed offsets on promoted cursor-10 bus across snapshot round-trips.
        var active = bus(repeatable).apply { restoreSnapshot(at10, true) }
        fun roundTrip() {
            val snap = active.saveSnapshot()
            assertFails { bus(secondConfig).restoreSnapshot(snap, true) }
            assertFails { bus(disable).restoreSnapshot(snap, true) }
            active = bus(repeatable).apply { restoreSnapshot(snap) }
            assertEquals(snap, active.saveSnapshot())
        }

        // 0x000: seeded 0 -> read returns 1; write 0x10 -> read returns 0x11; write 0 -> read returns 1.
        pc = 0x5afe0; lr = 0x11e3354
        assertEquals(1L, active.access(1, base + 0x000, 4, false))
        pc = 0x5af98; lr = 0x11e33fc
        assertEquals(0x10L, active.access(2, base + 0x000, 4, true, 0x10))
        roundTrip()
        pc = 0x5afe0; lr = 0x11e3354
        assertEquals(0x11L, active.access(3, base + 0x000, 4, false))
        pc = 0x5af98; lr = 0x11e33fc
        assertEquals(0L, active.access(4, base + 0x000, 4, true, 0))
        pc = 0x5afe0; lr = 0x11e3354
        assertEquals(1L, active.access(5, base + 0x000, 4, false))

        // 0x008, 0x068, 0x06c: seeded 0, retain arbitrary u32 writes.
        for ((offset, word) in listOf(0x008 to 0x80L, 0x068 to 0x12345678L, 0x06c to 0xffffffffL)) {
            pc = 0x5afe0; lr = 0x11e37dc
            assertEquals(0L, active.access(10, base + offset, 4, false))
            pc = 0x5af98; lr = 0x11e3828
            assertEquals(word, active.access(11, base + offset, 4, true, word))
            roundTrip()
            pc = 0x5afe0; lr = 0x11e37dc
            assertEquals(word, active.access(12, base + offset, 4, false))
        }

        // 0x014/0x020 (lo in 1..0x1000) and 0x018/0x024 (lo in 1..0x4000): enforce bounds & retain words.
        val validConfigs = listOf(
            0x014 to 0x3fff1000L,
            0x018 to 0x3fff4000L,
            0x020 to 0x00000001L,
            0x024 to 0x009a0454L,
        )
        for ((offset, word) in validConfigs) {
            pc = 0x5af98; lr = 0x11e3b48
            assertEquals(word, active.access(20, base + offset, 4, true, word))
            roundTrip()
            pc = 0x5afe0; lr = 0x11e3b48
            assertEquals(word, active.access(21, base + offset, 4, false))
        }
        // Repeat writes on same offsets with new valid values.
        pc = 0x5af98; lr = 0x11e3b48
        assertEquals(0x00100f30L, active.access(22, base + 0x014, 4, true, 0x00100f30))
        pc = 0x5afe0
        assertEquals(0x00100f30L, active.access(23, base + 0x014, 4, false))

        // Negative checks on saved repeatable snapshot.
        val snap = active.saveSnapshot()
        fun fresh() = bus(repeatable).apply { restoreSnapshot(snap) }
        pc = 0x5af98; lr = 0x11e3b48
        for (hole in listOf(0x004, 0x00c, 0x010, 0x01c, 0x028, 0x064, 0x070)) {
            assertFails { fresh().access(1, base + hole, 4, true, 0) }
        }
        // Out-of-bounds DAT config words rejected.
        for (bad in listOf(0x00000000L, 0x00001001L, 0x40000001L)) {
            assertFails { fresh().access(1, base + 0x014, 4, true, bad) }
            assertFails { fresh().access(1, base + 0x020, 4, true, bad) }
        }
        for (bad in listOf(0x00000000L, 0x00004001L, 0x40000001L)) {
            assertFails { fresh().access(1, base + 0x018, 4, true, bad) }
            assertFails { fresh().access(1, base + 0x024, 4, true, bad) }
        }
        assertFails { fresh().access(1, base + 0x014, 2, true, 1) }
        assertFails { fresh().access(1, base + 0x015, 4, true, 1) }
        core = 0
        assertFails { fresh().access(1, base + 0x008, 4, true, 0) }
        core = 1
        pc = 0x5afe0
        assertFails { fresh().access(1, base + 0x008, 4, true, 0) }
        pc = 0x5af98
        assertFails { fresh().access(1, base + 0x008, 4, false) }

        // Forged image6eBank snapshot states rejected.
        val regions = snap["regions"] as Map<*, *>
        val row = regions[Image6e000.REGION] as Map<*, *>
        val bankSnap = row["image6eBank"] as Map<*, *>
        val bankWords = bankSnap["words"] as Map<*, *>
        for (badBank in listOf(
            bankSnap + ("profile" to "other"),
            bankSnap + ("extra" to 1),
            bankSnap + ("words" to (bankWords + ("4" to 0L))),
            bankSnap + ("words" to (bankWords + ("08" to 0L))),
            bankSnap + ("words" to (bankWords + ("14" to 0x00001001L))),
            bankSnap + ("words" to (bankWords + ("18" to 0x40000001L))),
            bankSnap + ("words" to (bankWords + ("8" to -1L))),
            bankSnap + ("words" to (bankWords + ("8" to 0x1_0000_0000L))),
        )) {
            val forged = snap + ("regions" to regions + (Image6e000.REGION to row + mapOf("image6eBank" to badBank)))
            assertFails { bus(repeatable).restoreSnapshot(forged) }
        }
        assertFails { bus(repeatable).restoreSnapshot(snap + ("regions" to regions + (Image6e000.REGION to (row - "image6eBank")))) }
    }
}
