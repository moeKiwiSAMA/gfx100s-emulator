package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.EdgeInterruptState
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class Image70000Test {
    private val base = 0xfff70000L
    private val legacy = mapOf("image50000" to "retained-words-zero-v1", "image50000Control" to "write0-read1-once-v1")
    private val selected = legacy + ("image70000" to "setup-polls-once-v1")
    private val catalog = AddressCatalog.bundled()
    private var core = 1
    private var pc = 0x5afe0L
    private var lr = 0x11b8154L
    private var t79Teardown = false
    private var irqActive = false
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun pc() = pc
        override fun lr() = lr
        override fun tick() = 832784841L
        override fun inT79ImageTeardown() = t79Teardown
        override fun imageIrq106LineActive() = irqActive
    }
    private fun runtime(settings: Map<String, String>, mode: String = "discovery") =
        CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to settings))
    private fun bus(settings: Map<String, String> = selected) = checkNotNull(runtime(settings).createBus(context))

    @Test fun secondDriverGettersPromotesFromSetterOrSecondUpdatePreservesControlAndReadsBackRound2Words() {
        val gettersConfig = selected + mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id,
            ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        val setterConfig = gettersConfig + ("image70000" to T79ImageConfigSetter.PROFILE)
        val updateConfig = gettersConfig + ("image70000" to T79ImageSecondUpdate.PROFILE)
        val driver2Config = gettersConfig + ("image70000" to Image70000.SECOND_DRIVER_GETTERS_PROFILE)
        val round1ControlHigh = 0x12340000L
        val round2ControlHigh = 0x56780000L
        val empty = bus(gettersConfig).saveSnapshot()
        fun replaceRow(state: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
            val regions = state["regions"] as Map<*, *>
            val row = regions[Image70000.REGION] as Map<*, *>
            return state + ("regions" to regions + (Image70000.REGION to row + changes))
        }
        val gettersDone = replaceRow(empty, mapOf(
            "position" to 351, "accessAttempts" to 354L,
            "retainedWritten" to true, "retainedValue" to round1ControlHigh)) +
            (ImageIrq106Status.KEY to ImageIrq106Status.FINISHED.receipt())

        // Build genuine completed setter snapshot (cursor 395).
        val setterBus = bus(setterConfig).apply { restoreSnapshot(gettersDone, true) }
        pc = 0x5afe0
        lr = 0x11b9720
        assertEquals(0L, setterBus.access(1, base + 0x20, 4, false))
        pc = 0x5af98
        fun writeSetter(offset: Int, caller: Long, payload: Long = 0) {
            lr = caller
            assertEquals(payload, setterBus.access(2, base + offset, 4, true, payload))
        }
        writeSetter(0x20, 0x11b972c, 1)
        writeSetter(0x24, 0x11b9770, 0xabc0123)
        listOf(0x11b9788L, 0x11b97bc, 0x11b97fc, 0x11b97fc, 0x11b97fc, 0x11b982c,
            0x11b985c, 0x11b989c, 0x11b98d4, 0x11b9914, 0x11b994c, 0x11b96f0, 0x11bd280)
            .forEachIndexed { index, caller -> writeSetter(0x28 + 4 * index, caller) }
        repeat(6) { group ->
            listOf(0x11b9dfcL, 0x11b9e18, 0x11b9e34, 0x11b9e60).forEachIndexed { index, caller ->
                writeSetter(0x9c + 16 * group + 4 * index, caller)
            }
        }
        writeSetter(0xfc, 0x11ba13c)
        writeSetter(0x100, 0x11ba16c)
        writeSetter(0x104, 0x11ba19c)
        val incompleteSetter = setterBus.saveSnapshot()
        writeSetter(0x130, 0x11bab7c, 0x232)
        val completedSetter = setterBus.saveSnapshot()
        val savedSetterState = ((completedSetter["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>)["t79Setter"] as Map<*, *>

        val liveWords = buildList<Long> {
            add(0x00170fef); add(0x00210f41); add(0)
            for (pair in 0 until 10) {
                add(if (pair == 0) 0x0707011f else 0x10000)
                add(if (pair == 0) 0x01e9008d else 0x10000)
            }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x000f01e1); add(0x00190085)
            repeat(5) { add(0x80000); add(0x20001) }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x210f1d); add(0xa0003)
            add(0x210f1d); add(0x40005)
            add(0x210f1d); add(0x00000003)
            add(0x10101); add(0x10000)
            add(0x10101); add(0x10000)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(1); add(1)
            add(0x00160eff); add(0x00170435)
        }
        assertEquals(65, liveWords.size)
        val liveByOffset = T79ImageSecondUpdate.registers.zip(liveWords).associate { (reg, word) -> reg.offset to word }

        // Build genuine completed second-update snapshot (cursor 460).
        val updateBus = bus(updateConfig).apply { restoreSnapshot(completedSetter, true) }
        var incompleteUpdate: Map<String, Any?>? = null
        for ((index, reg) in T79ImageSecondUpdate.registers.withIndex()) {
            pc = 0x5af98
            lr = reg.lr
            assertEquals(liveWords[index], updateBus.access(index.toLong() + 10, base + reg.offset, 4, true, liveWords[index]))
            if (index == 63) incompleteUpdate = updateBus.saveSnapshot()
        }
        val completedUpdate = updateBus.saveSnapshot()
        val savedUpdateState = ((completedUpdate["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>)["t79SecondUpdate"] as Map<*, *>

        // Verify exact prohibited holes on the region definition.
        val driverOffsets = listOf(
            0x004, 0x008, 0x010, 0x014, 0x018, 0x2e4,
        ) + (0x22c..0x2d0 step 4).toList()
        val allowedOffsets = (T79ImageConfigSetter.offsets + T79ImageSecondUpdate.offsets +
            driverOffsets + listOf(0x00c, 0x200, 0x204)).toSet()
        val region = Image70000.region(Image70000.SECOND_DRIVER_GETTERS_PROFILE)
        for (offset in 0 until Image70000.SIZE step 4) {
            val address = base + offset
            val isProhibited = region.prohibited.any { it.overlaps(address, address + 4) }
            assertEquals(offset !in allowedOffsets, isProhibited, "Offset 0x${offset.toString(16)} prohibited mismatch")
        }

        // Reject unpromoted / invalid promotions and direct access or save before promotion.
        assertFails { bus(driver2Config).saveSnapshot() }
        assertFails { bus(driver2Config).restoreSnapshot(completedSetter) }
        assertFails { bus(driver2Config).restoreSnapshot(completedUpdate) }
        assertFails { bus(driver2Config).restoreSnapshot(incompleteSetter, true) }
        assertFails { bus(driver2Config).restoreSnapshot(checkNotNull(incompleteUpdate), true) }
        assertFails { bus(driver2Config).restoreSnapshot(gettersDone, true) }
        for (invalid in listOf(
            replaceRow(completedSetter, mapOf("position" to 394, "accessAttempts" to 397L)),
            replaceRow(completedSetter, mapOf("t79SecondUpdate" to T79ImageSecondUpdate().snapshot())),
            replaceRow(completedUpdate, mapOf("position" to 459, "accessAttempts" to 462L)),
            replaceRow(completedUpdate, mapOf("t79SecondUpdate" to (savedUpdateState + ("writes" to liveWords.dropLast(1))))),
            completedUpdate + (ImageIrq106Status.KEY to ImageIrq106Status.IDLE.receipt()),
            replaceRow(completedUpdate, mapOf("retainedWritten" to false, "retainedValue" to 0L)),
            replaceRow(completedUpdate, mapOf("retainedValue" to 0x12340020L)),
        )) {
            assertFails { bus(driver2Config).restoreSnapshot(invalid, true) }
        }
        pc = 0x5af98
        lr = 0x11bb4ac
        assertFails { bus(driver2Config).access(1, base + 0x254, 4, true, 0x20000000) }

        val addressPairs = listOf(
            0x254 to 0x11bb4acL, 0x22c to 0x11bb244L, 0x244 to 0x11bb714L, 0x24c to 0x11bb848L,
            0x274 to 0x11bb97cL, 0x27c to 0x11bbab0L, 0x284 to 0x11bbbe4L, 0x28c to 0x11bbd18L,
            0x294 to 0x11bbe4cL, 0x29c to 0x11bbf80L, 0x2a4 to 0x11bc0b4L, 0x2ac to 0x11bc1e8L,
            0x25c to 0x11bb5e0L, 0x234 to 0x11bb378L, 0x2b4 to 0x11bc5a8L, 0x2bc to 0x11bc6dcL,
            0x2c4 to 0x11bc810L, 0x2cc to 0x11bc944L, 0x264 to 0x11be6b8L, 0x26c to 0x11be7ecL,
            0x23c to 0x11be584L, 0x014 to 0x11bd11cL,
        )
        val getterSequence = buildList<Pair<Int, Long>> {
            fun addReads(offset: Int, vararg callers: Long) {
                callers.forEachIndexed { idx, caller -> add(offset + idx * 4 to caller) }
            }
            addReads(0x134, 0x11ba500, 0x11ba52c, 0x11ba558)
            repeat(10) { addReads(0x140 + 8 * it, 0x11ba74c, 0x11ba768) }
            addReads(0x05c, 0x11b8f14, 0x11b8f40, 0x11b8f6c)
            addReads(0x068, 0x11b9284, 0x11b92b0, 0x11b92dc)
            addReads(0x190, 0x11bd878, 0x11bd898)
            repeat(5) { addReads(0x074 + 8 * it, 0x11b9558, 0x11b9574) }
            addReads(0x124, 0x11baa38, 0x11baa64, 0x11baa90)
            addReads(0x1b0, 0x11bae1c)
            addReads(0x1ac, 0x11bae3c)
            addReads(0x1b8, 0x11bafe8)
            addReads(0x1b4, 0x11bb008)
            addReads(0x1c0, 0x11bb1b4)
            addReads(0x1bc, 0x11bb1d4)
            addReads(0x1c4, 0x11bda74, 0x11bda9c)
            addReads(0x1cc, 0x11bdc78, 0x11bdca0)
            addReads(0x1d4, 0x11bde8c, 0x11bdeac, 0x11bdebc)
            addReads(0x1e0, 0x11be0b0, 0x11be0d0, 0x11be0e0)
            addReads(0x1ec, 0x11be310, 0x11be32c, 0x11be350)
            addReads(0x224, 0x11bc518, 0x11bc538)
            add(0x200 to 0x11be4f4L)
            add(0x204 to 0x11be510L)
        }
        assertEquals(67, getterSequence.size)

        fun replayDriverAndGetters(initialBus: DiscoveryBus, verifyNegatives: Boolean): Map<String, Any?> {
            var active = initialBus
            var seq = 100L
            fun roundTrip() {
                val state = active.saveSnapshot()
                active = bus(driver2Config).apply { restoreSnapshot(state) }
                assertEquals(state, active.saveSnapshot())
                assertFails { bus(updateConfig).restoreSnapshot(state, true) }
                assertFails { bus(setterConfig).restoreSnapshot(state, true) }
            }
            roundTrip()

            if (verifyNegatives) {
                val atDriverStart = active.saveSnapshot()
                // Prohibited holes and IRQ106 0x00c (stays FINISHED) refuse cleanly.
                pc = 0x5af98; lr = 0x11bb4ac
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x000, 4, true, 0) }
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x01c, 4, true, 0) }
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x108, 4, true, 0) }
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x2e8, 4, false) }
                pc = 0x5afe0; lr = 0x11b7e50
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x00c, 4, false) }
                // Unaligned 34-bit low address bits rejected for 0x254.
                pc = 0x5af98; lr = 0x11bb4ac
                assertFails { bus(driver2Config).apply { restoreSnapshot(atDriverStart) }.access(1, base + 0x254, 4, true, 0x20000010) }
            }

            // 22 address pairs (44 writes, cursor 460..504).
            for ((idx, pair) in addressPairs.withIndex()) {
                val (offset, lowLr) = pair
                pc = 0x5af98
                lr = lowLr
                val lowWord = if (offset == 0x014) 0x2c000005L else 0x2c000000L + idx * 0x100L
                assertEquals(lowWord, active.access(seq++, base + offset, 4, true, lowWord))
                lr = lowLr + 0x10
                val highWord = (idx and 3).toLong()
                assertEquals(highWord, active.access(seq++, base + offset + 4, 4, true, highWord))
            }

            // 0x010 write (cursor 504).
            if (verifyNegatives) {
                val before010 = active.saveSnapshot()
                pc = 0x5af98; lr = 0x11bd000
                assertFails { bus(driver2Config).apply { restoreSnapshot(before010) }.access(1, base + 0x010, 4, true, 0x2) }
            }
            pc = 0x5af98; lr = 0x11bd000
            assertEquals(0x10001L, active.access(seq++, base + 0x010, 4, true, 0x10001L))
            roundTrip() // cursor 505, just before round-2 0x2e4 RMWs

            // 6 CONTROL (0x2e4) accesses (cursor 505..511):
            // 505: READ 0x2e4 -> retains round-1 high half | 0x20 from teardown/secondSetup.
            pc = 0x5afe0; lr = 0x11bc318
            assertEquals(round1ControlHigh or 0x20L, active.access(seq++, base + 0x2e4, 4, false))
            // 506: WRITE 0x2e4 -> must keep low half == 0x20 (bfi r1, r3, #16, #16); updates retained high half.
            if (verifyNegatives) {
                val before2e4Write = active.saveSnapshot()
                pc = 0x5af98; lr = 0x11bc32c
                assertFails { bus(driver2Config).apply { restoreSnapshot(before2e4Write) }.access(1, base + 0x2e4, 4, true, round2ControlHigh) }
                assertFails { bus(driver2Config).apply { restoreSnapshot(before2e4Write) }.access(1, base + 0x2e4, 4, true, round2ControlHigh or 0x120L) }
            }
            pc = 0x5af98; lr = 0x11bc32c
            assertEquals(round2ControlHigh or 0x20L, active.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x20L))
            roundTrip() // cursor 507, retainedControl must now be round2ControlHigh!
            val afterControlRow = (active.saveSnapshot()["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
            assertEquals(round2ControlHigh, afterControlRow["retainedValue"])

            // 507..510: 011bcdd0 read, 011bcde8 write (0x20), 011bce18 read (0x20), 011bce24 write (0x120).
            pc = 0x5afe0; lr = 0x11bcdd0
            assertEquals(round2ControlHigh or 0x20L, active.access(seq++, base + 0x2e4, 4, false))
            if (verifyNegatives) {
                val before2e4Bit5 = active.saveSnapshot()
                pc = 0x5af98; lr = 0x11bcde8
                assertFails { bus(driver2Config).apply { restoreSnapshot(before2e4Bit5) }.access(1, base + 0x2e4, 4, true, round1ControlHigh or 0x20L) }
            }
            pc = 0x5af98; lr = 0x11bcde8
            assertEquals(round2ControlHigh or 0x20L, active.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x20L))
            pc = 0x5afe0; lr = 0x11bce18
            assertEquals(round2ControlHigh or 0x20L, active.access(seq++, base + 0x2e4, 4, false))
            pc = 0x5af98; lr = 0x11bce24
            assertEquals(round2ControlHigh or 0x120L, active.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x120L))

            // 4 enable RMWs on 0x004/0x008 (cursor 511..515):
            // Teardown left both at 0x03ff0000; 011b81e4 ORs bits 25:16 and never clears bit 21 on 0x008 -> 0x03ff03df!
            pc = 0x5afe0; lr = 0x11b8238
            assertEquals(0x03ff0000L, active.access(seq++, base + 0x004, 4, false))
            pc = 0x5af98; lr = 0x11b82c0
            assertEquals(0x03ff03ffL, active.access(seq++, base + 0x004, 4, true, 0x03ff03ffL))
            pc = 0x5afe0; lr = 0x11b82cc
            assertEquals(0x03ff0000L, active.access(seq++, base + 0x008, 4, false))
            if (verifyNegatives) {
                val before008Write = active.saveSnapshot()
                pc = 0x5af98; lr = 0x11b8354
                // Round-1 value 0x03df03df must be rejected because bit 21 stays set from teardown's 0x03ff0000!
                assertFails { bus(driver2Config).apply { restoreSnapshot(before008Write) }.access(1, base + 0x008, 4, true, 0x03df03dfL) }
            }
            pc = 0x5af98; lr = 0x11b8354
            assertEquals(0x03ff03dfL, active.access(seq++, base + 0x008, 4, true, 0x03ff03dfL))
            roundTrip() // cursor 515, getter pass 1 start

            // Two 67-word getter readback passes (cursor 515..649).
            for (pass in 0 until 2) {
                if (verifyNegatives && pass == 0) {
                    val atGetters = active.saveSnapshot()
                    pc = 0x5af98; lr = 0x11ba500
                    assertFails { bus(driver2Config).apply { restoreSnapshot(atGetters) }.access(1, base + 0x134, 4, true, 0x00170fefL) }
                }
                for ((idx, entry) in getterSequence.withIndex()) {
                    val (offset, readLr) = entry
                    pc = 0x5afe0
                    lr = readLr
                    val expected = when (offset) {
                        0x200, 0x204 -> 0L
                        else -> checkNotNull(liveByOffset[offset])
                    }
                    val actual = active.access(seq++, base + offset, 4, false)
                    assertEquals(expected, actual, "Pass $pass index $idx offset 0x${offset.toString(16)}")
                    if (offset == 0x134) assertEquals(0x00170fefL, actual)
                    if (offset == 0x138) assertEquals(0x00210f41L, actual)
                    if (offset == 0x228) assertEquals(0x00170435L, actual)
                }
                roundTrip()
            }

            val finalState = active.saveSnapshot()
            val finalRow = (finalState["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
            assertEquals(649, finalRow["position"])
            assertEquals(652L, finalRow["accessAttempts"])
            assertEquals(round2ControlHigh, finalRow["retainedValue"])
            assertEquals(savedSetterState, finalRow["t79Setter"])
            assertEquals(savedUpdateState, finalRow["t79SecondUpdate"])
            assertEquals(ImageIrq106Status.FINISHED.receipt(), finalState[ImageIrq106Status.KEY])

            // 650th access refuses (finite-input-missing-or-exhausted).
            pc = 0x5afe0; lr = 0x11ba500
            assertFails { active.access(seq++, base + 0x134, 4, false) }
            return finalState
        }

        // Path 1: Promote from completed IMAGE_70000_SECOND_UPDATE (cursor 460).
        val fromUpdateFinal = replayDriverAndGetters(
            bus(driver2Config).apply { restoreSnapshot(completedUpdate, true) },
            verifyNegatives = true,
        )

        // Path 2: Promote directly from completed IMAGE_70000_SETTER (cursor 395), run 65 update writes + driver + 2x67 getters!
        var directBus = bus(driver2Config).apply { restoreSnapshot(completedSetter, true) }
        for ((index, reg) in T79ImageSecondUpdate.registers.withIndex()) {
            pc = 0x5af98
            lr = reg.lr
            assertEquals(liveWords[index], directBus.access(index.toLong() + 10, base + reg.offset, 4, true, liveWords[index]))
            if (index in setOf(0, 23, 64)) {
                val mid = directBus.saveSnapshot()
                directBus = bus(driver2Config).apply { restoreSnapshot(mid) }
                assertEquals(mid, directBus.saveSnapshot())
            }
        }
        val fromSetterFinal = replayDriverAndGetters(directBus, verifyNegatives = false)
        assertEquals(fromUpdateFinal, fromSetterFinal)

        // Reject forged restored IMAGE_70000_SECOND_DRIVER_GETTERS snapshots.
        val updateSnap = ((fromUpdateFinal["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>)["t79SecondUpdate"] as Map<*, *>
        for (bad in listOf(
            replaceRow(fromUpdateFinal, mapOf("position" to 394, "accessAttempts" to 397L)),
            replaceRow(fromUpdateFinal, mapOf("position" to 459, "accessAttempts" to 462L)),
            replaceRow(fromUpdateFinal, mapOf("position" to 650, "accessAttempts" to 653L)),
            replaceRow(fromUpdateFinal, mapOf("accessAttempts" to 649L)),
            replaceRow(fromUpdateFinal, mapOf("retainedWritten" to false, "retainedValue" to 0L)),
            replaceRow(fromUpdateFinal, mapOf("retainedValue" to (round2ControlHigh or 0x20L))),
            fromUpdateFinal + (ImageIrq106Status.KEY to ImageIrq106Status.IDLE.receipt()),
            replaceRow(fromUpdateFinal, mapOf("t79SecondUpdate" to (updateSnap + ("writes" to liveWords.dropLast(1))))),
            replaceRow(fromUpdateFinal, mapOf("t79SecondUpdate" to (updateSnap + ("writes" to (listOf(0x10000000L) + liveWords.drop(1)))))),
            replaceRow(fromUpdateFinal, mapOf("t79Setter" to (savedSetterState + ("writes" to (savedSetterState["writes"] as List<*>).dropLast(1))))),
            replaceRow(fromUpdateFinal, mapOf("t79SecondUpdate" to null)),
            replaceRow(fromUpdateFinal, mapOf("t79Setter" to null)),
        )) {
            assertFails { bus(driver2Config).restoreSnapshot(bad, true) }
        }
    }

    @Test fun statefulIspBankPromotesAt649OrMidDriverPreservesSeedAndHolesAndRoundTrips() {
        val gettersConfig = selected + mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id,
            ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        val setterConfig = gettersConfig + ("image70000" to T79ImageConfigSetter.PROFILE)
        val updateConfig = gettersConfig + ("image70000" to T79ImageSecondUpdate.PROFILE)
        val driver2Config = gettersConfig + ("image70000" to Image70000.SECOND_DRIVER_GETTERS_PROFILE)
        val statefulConfig = gettersConfig + ("image70000" to T79StatefulIspBank.PROFILE)
        assertFails { runtime(statefulConfig, "strict") }
        assertNull(runtime(selected + ("image70000" to T79StatefulIspBank.PROFILE), "strict").createBus(context))

        // Verify exact prohibited holes on the stateful region definition.
        val region = Image70000.region(T79StatefulIspBank.PROFILE)
        for (offset in 0 until Image70000.SIZE step 4) {
            val address = base + offset
            val isProhibited = region.prohibited.any { it.overlaps(address, address + 4) }
            assertEquals(offset !in Image70000.statefulAllowedOffsets, isProhibited, "Offset 0x${offset.toString(16)} prohibited mismatch")
        }
        // Known holes 0x108..0x120 and 0x2d4..0x2e0 must remain prohibited!
        for (hole in listOf(0x108, 0x10c, 0x110, 0x120, 0x2d4, 0x2d8, 0x2dc, 0x2e0)) {
            assertEquals(true, region.prohibited.any { it.overlaps(base + hole, base + hole + 4) })
        }

        val round1ControlHigh = 0x12340000L
        val round2ControlHigh = 0x56780000L
        val empty = bus(gettersConfig).saveSnapshot()
        fun replaceRow(state: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
            val regions = state["regions"] as Map<*, *>
            val row = regions[Image70000.REGION] as Map<*, *>
            return state + ("regions" to regions + (Image70000.REGION to row + changes))
        }
        val gettersDone = replaceRow(empty, mapOf(
            "position" to 351, "accessAttempts" to 354L,
            "retainedWritten" to true, "retainedValue" to round1ControlHigh)) +
            (ImageIrq106Status.KEY to ImageIrq106Status.FINISHED.receipt())

        // Build completed setter (cursor 395).
        val setterBus = bus(setterConfig).apply { restoreSnapshot(gettersDone, true) }
        pc = 0x5afe0; lr = 0x11b9720
        assertEquals(0L, setterBus.access(1, base + 0x20, 4, false))
        pc = 0x5af98
        fun writeSetter(offset: Int, caller: Long, payload: Long = 0) {
            lr = caller
            assertEquals(payload, setterBus.access(2, base + offset, 4, true, payload))
        }
        writeSetter(0x20, 0x11b972c, 1)
        writeSetter(0x24, 0x11b9770, 0xabc0123)
        listOf(0x11b9788L, 0x11b97bc, 0x11b97fc, 0x11b97fc, 0x11b97fc, 0x11b982c,
            0x11b985c, 0x11b989c, 0x11b98d4, 0x11b9914, 0x11b994c, 0x11b96f0, 0x11bd280)
            .forEachIndexed { index, caller -> writeSetter(0x28 + 4 * index, caller) }
        repeat(6) { group ->
            listOf(0x11b9dfcL, 0x11b9e18, 0x11b9e34, 0x11b9e60).forEachIndexed { index, caller ->
                writeSetter(0x9c + 16 * group + 4 * index, caller)
            }
        }
        writeSetter(0xfc, 0x11ba13c)
        writeSetter(0x100, 0x11ba16c)
        writeSetter(0x104, 0x11ba19c)
        writeSetter(0x130, 0x11bab7c, 0x232)
        val completedSetter = setterBus.saveSnapshot()

        // Build completed second update (cursor 460).
        val liveWords = buildList<Long> {
            add(0x00170fef); add(0x00210f41); add(0)
            for (pair in 0 until 10) {
                add(if (pair == 0) 0x0707011f else 0x10000)
                add(if (pair == 0) 0x01e9008d else 0x10000)
            }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x000f01e1); add(0x00190085)
            repeat(5) { add(0x80000); add(0x20001) }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x210f1d); add(0xa0003)
            add(0x210f1d); add(0x40005)
            add(0x210f1d); add(3)
            add(0x10101); add(0x10000)
            add(0x10101); add(0x10000)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(1); add(1)
            add(0x00160eff); add(0x00170435)
        }
        val updateBus = bus(updateConfig).apply { restoreSnapshot(completedSetter, true) }
        for ((index, reg) in T79ImageSecondUpdate.registers.withIndex()) {
            pc = 0x5af98; lr = reg.lr
            assertEquals(liveWords[index], updateBus.access(index.toLong() + 1, base + reg.offset, 4, true, liveWords[index]))
        }
        val completedUpdate = updateBus.saveSnapshot()

        // Drive driver2Config from 460 -> 649, capturing mid-driver (515) and completed (649) snapshots.
        val addressPairs = listOf(
            0x254 to 0x11bb4acL, 0x22c to 0x11bb244L, 0x244 to 0x11bb714L, 0x24c to 0x11bb848L,
            0x274 to 0x11bb97cL, 0x27c to 0x11bbab0L, 0x284 to 0x11bbbe4L, 0x28c to 0x11bbd18L,
            0x294 to 0x11bbe4cL, 0x29c to 0x11bbf80L, 0x2a4 to 0x11bc0b4L, 0x2ac to 0x11bc1e8L,
            0x25c to 0x11bb5e0L, 0x234 to 0x11bb378L, 0x2b4 to 0x11bc5a8L, 0x2bc to 0x11bc6dcL,
            0x2c4 to 0x11bc810L, 0x2cc to 0x11bc944L, 0x264 to 0x11be6b8L, 0x26c to 0x11be7ecL,
            0x23c to 0x11be584L, 0x014 to 0x11bd11cL,
        )
        val getterSequence = buildList {
            fun addRead(offset: Int, vararg callers: Long) {
                callers.forEachIndexed { i, c -> add((offset + i * 4) to c) }
            }
            addRead(0x134, 0x11ba500, 0x11ba52c, 0x11ba558)
            repeat(10) { addRead(0x140 + 8 * it, 0x11ba74c, 0x11ba768) }
            addRead(0x05c, 0x11b8f14, 0x11b8f40, 0x11b8f6c)
            addRead(0x068, 0x11b9284, 0x11b92b0, 0x11b92dc)
            addRead(0x190, 0x11bd878, 0x11bd898)
            repeat(5) { addRead(0x074 + 8 * it, 0x11b9558, 0x11b9574) }
            addRead(0x124, 0x11baa38, 0x11baa64, 0x11baa90)
            addRead(0x1b0, 0x11bae1c); addRead(0x1ac, 0x11bae3c)
            addRead(0x1b8, 0x11bafe8); addRead(0x1b4, 0x11bb008)
            addRead(0x1c0, 0x11bb1b4); addRead(0x1bc, 0x11bb1d4)
            addRead(0x1c4, 0x11bda74, 0x11bda9c)
            addRead(0x1cc, 0x11bdc78, 0x11bdca0)
            addRead(0x1d4, 0x11bde8c, 0x11bdeac, 0x11bdebc)
            addRead(0x1e0, 0x11be0b0, 0x11be0d0, 0x11be0e0)
            addRead(0x1ec, 0x11be310, 0x11be32c, 0x11be350)
            addRead(0x224, 0x11bc518, 0x11bc538)
            add(0x200 to 0x11be4f4L); add(0x204 to 0x11be510L)
        }
        val liveByOffset = T79ImageSecondUpdate.offsets.zip(liveWords).toMap()

        fun runDriver(target: DiscoveryBus) {
            var seq = 1L
            for ((idx, pair) in addressPairs.withIndex()) {
                pc = 0x5af98; lr = pair.second
                val lowWord = if (pair.first == 0x014) 0x2c000005L else 0x2c000000L + idx * 0x100L
                target.access(seq++, base + pair.first, 4, true, lowWord)
                lr = pair.second + 0x10
                target.access(seq++, base + pair.first + 4, 4, true, (idx and 3).toLong())
            }
            pc = 0x5af98; lr = 0x11bd000
            target.access(seq++, base + 0x010, 4, true, 0x10001L)
            pc = 0x5afe0; lr = 0x11bc318; target.access(seq++, base + 0x2e4, 4, false)
            pc = 0x5af98; lr = 0x11bc32c; target.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x20L)
            pc = 0x5afe0; lr = 0x11bcdd0; target.access(seq++, base + 0x2e4, 4, false)
            pc = 0x5af98; lr = 0x11bcde8; target.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x20L)
            pc = 0x5afe0; lr = 0x11bce18; target.access(seq++, base + 0x2e4, 4, false)
            pc = 0x5af98; lr = 0x11bce24; target.access(seq++, base + 0x2e4, 4, true, round2ControlHigh or 0x120L)
            pc = 0x5afe0; lr = 0x11b8238; target.access(seq++, base + 0x004, 4, false)
            pc = 0x5af98; lr = 0x11b82c0; target.access(seq++, base + 0x004, 4, true, 0x03ff03ffL)
            pc = 0x5afe0; lr = 0x11b82cc; target.access(seq++, base + 0x008, 4, false)
            pc = 0x5af98; lr = 0x11b8354; target.access(seq++, base + 0x008, 4, true, 0x03ff03dfL)
        }
        fun runGetters(target: DiscoveryBus) {
            var seq = 100L
            repeat(2) {
                for ((offset, readLr) in getterSequence) {
                    pc = 0x5afe0; lr = readLr
                    val expected = if (offset == 0x200 || offset == 0x204) 0L else checkNotNull(liveByOffset[offset])
                    assertEquals(expected, target.access(seq++, base + offset, 4, false))
                }
            }
        }

        val driver2Bus = bus(driver2Config).apply { restoreSnapshot(completedUpdate, true) }
        val driver2At460 = driver2Bus.saveSnapshot()
        runDriver(driver2Bus)
        val driver2At515 = driver2Bus.saveSnapshot()
        runGetters(driver2Bus)
        val driver2At649 = driver2Bus.saveSnapshot()

        // Reject invalid promotions: missing flag, wrong predecessor strategy, or mid-update cursor (< 460).
        assertFails { bus(statefulConfig).restoreSnapshot(driver2At649) }
        assertFails { bus(statefulConfig).restoreSnapshot(completedUpdate, true) }
        assertFails { bus(statefulConfig).restoreSnapshot(completedSetter, true) }
        assertFails { bus(statefulConfig).restoreSnapshot(gettersDone, true) }
        val midUpdateDriver2 = bus(driver2Config).apply { restoreSnapshot(completedSetter, true) }.saveSnapshot()
        assertFails { bus(statefulConfig).restoreSnapshot(midUpdateDriver2, true) }
        assertFails { bus(statefulConfig).access(1, base + 0x020, 4, false) }

        // Path A: Seamless mid-driver promotion at cursor 515 -> finishes remaining 134 getters -> auto-seeds bank at 649!
        var midBus = bus(statefulConfig).apply { restoreSnapshot(driver2At515, true) }
        val midSaved = midBus.saveSnapshot()
        val midRow = (midSaved["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        assertEquals(515, midRow["position"])
        assertEquals(false, "t79StatefulBank" in midRow)
        midBus = bus(statefulConfig).apply { restoreSnapshot(midSaved) }
        // While < 649, 0x00c still refuses (matching second-driver-getters) and out-of-order accesses refuse.
        pc = 0x5afe0; lr = 0x11b7e50
        assertFails { bus(statefulConfig).apply { restoreSnapshot(midSaved) }.access(1, base + 0x00c, 4, false) }
        runGetters(midBus)
        val transitionedAt649 = midBus.saveSnapshot()

        // Path B: Direct promotion from completed driver2 cursor 649 matches transitionedAt649 identically!
        val directAt649 = bus(statefulConfig).apply { restoreSnapshot(driver2At649, true) }.saveSnapshot()
        assertEquals(directAt649, transitionedAt649)

        // Also verify promotion at cursor 460 works seamlessly through driver + getters -> identical 649 state!
        val from460Bus = bus(statefulConfig).apply { restoreSnapshot(driver2At460, true) }
        runDriver(from460Bus)
        runGetters(from460Bus)
        assertEquals(directAt649, from460Bus.saveSnapshot())

        // Verify exact seeded state at cursor 649 and repeatable stateful operations across restores.
        var active = bus(statefulConfig).apply { restoreSnapshot(directAt649) }
        fun roundTrip() {
            val snap = active.saveSnapshot()
            assertFails { bus(driver2Config).restoreSnapshot(snap, true) }
            active = bus(statefulConfig).apply { restoreSnapshot(snap) }
            assertEquals(snap, active.saveSnapshot())
        }

        // Exact seeded values:
        pc = 0x5afe0; lr = 0x11b9720
        assertEquals(1L, active.access(1, base + 0x020, 4, false)) // setter word
        assertEquals(0xabc0123L, active.access(2, base + 0x024, 4, false)) // setter word
        assertEquals(0x232L, active.access(3, base + 0x130, 4, false)) // setter submit word
        assertEquals(0x00170fefL, active.access(4, base + 0x134, 4, false)) // second-update word
        assertEquals(0x00170435L, active.access(5, base + 0x228, 4, false)) // second-update word
        assertEquals(0x03ff03ffL, active.access(6, base + 0x004, 4, false))
        assertEquals(0x03ff03dfL, active.access(7, base + 0x008, 4, false))
        assertEquals(round2ControlHigh or 0x120L, active.access(8, base + 0x2e4, 4, false))
        assertEquals(1L, active.access(9, base + 0x000, 4, false))
        assertEquals(0x10000L, active.access(10, base + 0x2e8, 4, false))
        assertEquals(0x66480L, active.access(11, base + 0x01c, 4, false))
        assertEquals(0L, active.access(12, base + 0x200, 4, false))
        assertEquals(0L, active.access(13, base + 0x204, 4, false))

        // 0x000: write stores value, read returns value | 1.
        pc = 0x5af98; lr = 0x11b8160
        assertEquals(0x10L, active.access(14, base + 0x000, 4, true, 0x10))
        roundTrip()
        pc = 0x5afe0; lr = 0x11b8170
        assertEquals(0x11L, active.access(15, base + 0x000, 4, false))
        pc = 0x5af98; lr = 0x11b8160
        assertEquals(0L, active.access(16, base + 0x000, 4, true, 0))
        pc = 0x5afe0; lr = 0x11b8170
        assertEquals(1L, active.access(17, base + 0x000, 4, false))

        // 0x00c: when IRQ106 is FINISHED, reads 0 and acks 0 or 1 from 0x11b7f0c.
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(0L, active.access(18, base + 0x00c, 4, false))
        pc = 0x5af98; lr = 0x11b7f0c
        assertEquals(0L, active.access(19, base + 0x00c, 4, true, 0))
        assertEquals(1L, active.access(20, base + 0x00c, 4, true, 1))
        pc = 0x5afe0; lr = 0x11b7c58
        assertEquals(0L, active.access(21, base + 0x00c, 4, false))

        // 0x2e4 write updates both bank word and retainedValue high half!
        val round3ControlHigh = 0xabcd0000L
        pc = 0x5af98; lr = 0x11bc32c
        assertEquals(round3ControlHigh or 0x120L, active.access(22, base + 0x2e4, 4, true, round3ControlHigh or 0x120L))
        roundTrip()
        val updatedRow = (active.saveSnapshot()["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        assertEquals(round3ControlHigh, updatedRow["retainedValue"])
        pc = 0x5afe0; lr = 0x11bc318
        assertEquals(round3ControlHigh or 0x120L, active.access(23, base + 0x2e4, 4, false))

        // Arbitrary repeatable writes/reads on other allowed offsets persist across restores.
        pc = 0x5af98; lr = 0x11b972c
        assertEquals(0L, active.access(24, base + 0x020, 4, true, 0))
        assertEquals(0xdeadbeefL, active.access(25, base + 0x254, 4, true, 0xdeadbeef))
        roundTrip()
        pc = 0x5afe0; lr = 0x11b9720
        assertEquals(0L, active.access(26, base + 0x020, 4, false))
        assertEquals(0xdeadbeefL, active.access(27, base + 0x254, 4, false))

        // Negative checks on restored stateful snapshot.
        val snap = active.saveSnapshot()
        fun fresh() = bus(statefulConfig).apply { restoreSnapshot(snap) }
        // Prohibited holes refused.
        pc = 0x5af98; lr = 0x11b972c
        for (hole in listOf(0x108, 0x120, 0x2d4, 0x2e0)) {
            assertFails { fresh().access(1, base + hole, 4, true, 0) }
        }
        // 0x2e8 write refused (read-only).
        assertFails { fresh().access(1, base + 0x2e8, 4, true, 0x10000) }
        // 0x00c write refused for value != 0/1 or wrong LR.
        lr = 0x11b7f0c
        assertFails { fresh().access(1, base + 0x00c, 4, true, 2) }
        lr = 0x11b7f10
        assertFails { fresh().access(1, base + 0x00c, 4, true, 1) }
        // Wrong width, alignment, core, PC.
        lr = 0x11b972c
        assertFails { fresh().access(1, base + 0x020, 2, true, 0) }
        assertFails { fresh().access(1, base + 0x021, 4, true, 0) }
        core = 0
        assertFails { fresh().access(1, base + 0x020, 4, true, 0) }
        core = 1
        pc = 0x5afe0
        assertFails { fresh().access(1, base + 0x020, 4, true, 0) }
        pc = 0x5af98
        assertFails { fresh().access(1, base + 0x020, 4, false) }

        // Forged stateful bank snapshots rejected.
        val snapRow = (snap["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        val bankSnap = snapRow["t79StatefulBank"] as Map<*, *>
        val bankWords = bankSnap["words"] as Map<*, *>
        for (bad in listOf(
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("profile" to "other")))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("extra" to 1)))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("108" to 0L)))))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("020" to 0L)))))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("2e8" to 0L)))))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("c" to 1L)))))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("20" to -1L)))))),
            replaceRow(snap, mapOf("t79StatefulBank" to (bankSnap + ("words" to (bankWords + ("20" to 0x1_0000_0000L)))))),
            replaceRow(snap, mapOf("retainedValue" to 0L)), // mismatches bank 2e4 high half (0xabcd0000)
            replaceRow(snap, mapOf("t79StatefulBank" to null)),
            replaceRow(midSaved, mapOf("t79StatefulBank" to bankSnap)), // unexpected bank at < 649
        )) {
            assertFails { bus(statefulConfig).restoreSnapshot(bad, true) }
        }

        // Repeatable IRQ106 interop with T79StatefulIspBank: quiet 0x00c stays on stateful bank, active IRQ106 routes 0x00c to W1C machine.
        val repeatableStatefulConfig = statefulConfig + (ImageIrq106Status.KEY to ImageIrq106Status.REPEATABLE_PROFILE)
        val sink = object : CoupledEdgeInterruptSink {
            override fun lineEnabled(irq: Int) = true
            override fun edgeState(irq: Int) = EdgeInterruptState(true, false, false)
            override fun setLine(irq: Int, high: Boolean) = error("Expected coupled pulse")
            override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) {
                assertEquals(106, irq)
                assertEquals(2, targetMask)
                latchDevice()
            }
        }
        var rep = bus(repeatableStatefulConfig).apply { restoreSnapshot(snap, true) }
        // Quiet 0x00c outside IRQ106 handled by T79StatefulIspBank.
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(0L, rep.access(100, base + 0x00c, 4, false))
        // Latch IRQ106 with mask 0x41 -> routes 0x00c to repeatable-w1c-v1 state machine!
        rep.pulseImageIrq106(sink, 0x41L)
        val midLatched = rep.saveSnapshot()
        rep = bus(repeatableStatefulConfig).apply { restoreSnapshot(midLatched) }
        assertEquals(midLatched, rep.saveSnapshot())
        irqActive = true
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(0x41L, rep.access(101, base + 0x00c, 4, false))
        pc = 0x5af98; lr = 0x11b7f0c
        assertEquals(0x41L, rep.access(102, base + 0x00c, 4, true, 0x41L))
        val midAck = rep.saveSnapshot()
        rep = bus(repeatableStatefulConfig).apply { restoreSnapshot(midAck) }
        pc = 0x5afe0; lr = 0x11b7c58
        assertEquals(0L, rep.access(103, base + 0x00c, 4, false))
        irqActive = false
        // Back to FINISHED: quiet 0x00c handled by T79StatefulIspBank again, and snapshot round-trips.
        pc = 0x5afe0; lr = 0x11b7e50
        assertEquals(0L, rep.access(104, base + 0x00c, 4, false))
        val afterRep = rep.saveSnapshot()
        assertEquals(afterRep, bus(repeatableStatefulConfig).apply { restoreSnapshot(afterRep) }.saveSnapshot())
    }

    @Test fun secondUpdatePromotesOnlyFinishedSetterRetainsStateAndEnforcesHoles() {
        val gettersConfig = selected + mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id,
            ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        val setterConfig = gettersConfig + ("image70000" to T79ImageConfigSetter.PROFILE)
        val updateConfig = gettersConfig + ("image70000" to T79ImageSecondUpdate.PROFILE)
        val empty = bus(gettersConfig).saveSnapshot()
        fun replaceRow(state: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
            val regions = state["regions"] as Map<*, *>
            val row = regions[Image70000.REGION] as Map<*, *>
            return state + ("regions" to regions + (Image70000.REGION to row + changes))
        }
        val gettersDone = replaceRow(empty, mapOf("position" to 351, "accessAttempts" to 354L, "retainedWritten" to true)) +
            (ImageIrq106Status.KEY to ImageIrq106Status.FINISHED.receipt())
        // Build a genuine completed T79 setter snapshot (cursor 395, 43 writes).
        val setterBus = bus(setterConfig).apply { restoreSnapshot(gettersDone, true) }
        pc = 0x5afe0
        lr = 0x11b9720
        assertEquals(0L, setterBus.access(1, base + 0x20, 4, false))
        pc = 0x5af98
        fun writeSetter(offset: Int, caller: Long, payload: Long = 0) {
            lr = caller
            assertEquals(payload, setterBus.access(2, base + offset, 4, true, payload))
        }
        writeSetter(0x20, 0x11b972c, 1)
        writeSetter(0x24, 0x11b9770, 0xabc0123)
        listOf(0x11b9788L, 0x11b97bc, 0x11b97fc, 0x11b97fc, 0x11b97fc, 0x11b982c,
            0x11b985c, 0x11b989c, 0x11b98d4, 0x11b9914, 0x11b994c, 0x11b96f0, 0x11bd280)
            .forEachIndexed { index, caller -> writeSetter(0x28 + 4 * index, caller) }
        repeat(6) { group ->
            listOf(0x11b9dfcL, 0x11b9e18, 0x11b9e34, 0x11b9e60).forEachIndexed { index, caller ->
                writeSetter(0x9c + 16 * group + 4 * index, caller)
            }
        }
        writeSetter(0xfc, 0x11ba13c)
        writeSetter(0x100, 0x11ba16c)
        writeSetter(0x104, 0x11ba19c)
        val incompleteSetter = setterBus.saveSnapshot()
        writeSetter(0x130, 0x11bab7c, 0x232)
        val completedSetter = setterBus.saveSnapshot()
        val setterRow = (completedSetter["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        val savedSetterState = setterRow["t79Setter"] as Map<*, *>

        // Verify prohibited holes vs allowed union offsets on the region definition.
        val region = Image70000.region(T79ImageSecondUpdate.PROFILE)
        val allowedOffsets = (T79ImageConfigSetter.offsets + T79ImageSecondUpdate.offsets + 0x00c).toSet()
        for (offset in 0 until Image70000.SIZE step 4) {
            val address = base + offset
            val isProhibited = region.prohibited.any { it.overlaps(address, address + 4) }
            assertEquals(offset !in allowedOffsets, isProhibited, "Offset 0x${offset.toString(16)} prohibited mismatch")
        }

        // Reject invalid promotions and unpromoted access.
        assertFails { bus(updateConfig).restoreSnapshot(completedSetter) }
        assertFails { bus(updateConfig).restoreSnapshot(incompleteSetter, true) }
        assertFails { bus(updateConfig).restoreSnapshot(gettersDone, true) }
        for (invalid in listOf(
            replaceRow(completedSetter, mapOf("position" to 394, "accessAttempts" to 397L)),
            replaceRow(completedSetter, mapOf("position" to 396, "accessAttempts" to 399L)),
            completedSetter + (ImageIrq106Status.KEY to ImageIrq106Status.IDLE.receipt()),
            replaceRow(completedSetter, mapOf("t79SecondUpdate" to T79ImageSecondUpdate().snapshot())),
            replaceRow(completedSetter, mapOf("t79Setter" to (savedSetterState + ("writes" to (savedSetterState["writes"] as List<*>).dropLast(1))))),
        )) {
            assertFails { bus(updateConfig).restoreSnapshot(invalid, true) }
        }
        lr = 0x11ba364
        assertFails { bus(updateConfig).access(1, base + 0x134, 4, true, 0x00170fef) }
        assertFails { bus(setterConfig).apply { restoreSnapshot(completedSetter) }.access(1, base + 0x134, 4, true, 0x00170fef) }

        // Promote from completed setter and verify snapshot preservation + round-trip.
        var active = bus(updateConfig).apply { restoreSnapshot(completedSetter, true) }
        fun roundTrip() {
            val state = active.saveSnapshot()
            active = bus(updateConfig).apply { restoreSnapshot(state) }
            assertEquals(state, active.saveSnapshot())
            assertFails { bus(setterConfig).restoreSnapshot(state, true) }
        }
        roundTrip()

        // Prohibited holes and out-of-order / wrong-offset writes are refused.
        val promoted = active.saveSnapshot()
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x000, 4, true, 0) }
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x108, 4, true, 0) }
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x22c, 4, true, 0) }
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x020, 4, true, 1) }
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x134, 4, false) }
        assertFails { bus(updateConfig).apply { restoreSnapshot(promoted) }.access(1, base + 0x134, 4, true, 0x10000000) }

        // Replay all 65 live round-2 writes through DiscoveryBus.
        val liveWords = buildList<Long> {
            add(0x00170fef); add(0x00210f41); add(0)
            for (pair in 0 until 10) {
                add(if (pair == 0) 0x0707011f else 0x10000)
                add(if (pair == 0) 0x01e9008d else 0x10000)
            }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x000f01e1); add(0x00190085)
            repeat(5) { add(0x80000); add(0x20001) }
            add(0x00cf61ef); add(0x004e6089); add(0)
            add(0x210f1d); add(0xa0003)
            add(0x210f1d); add(0x40005)
            add(0x210f1d); add(0x00000003)
            add(0x10101); add(0x10000)
            add(0x10101); add(0x10000)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(0x10000); add(0)
            add(0x10001); add(1); add(1)
            add(0x00160eff); add(0x00170435)
        }
        assertEquals(65, liveWords.size)
        for ((index, reg) in T79ImageSecondUpdate.registers.withIndex()) {
            pc = 0x5af98
            lr = reg.lr
            assertEquals(liveWords[index], active.access(index.toLong() + 10, base + reg.offset, 4, true, liveWords[index]))
            if (index in setOf(0, 1, 23, 64)) roundTrip()
        }

        val finalState = active.saveSnapshot()
        val finalRow = (finalState["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        assertEquals(460, finalRow["position"])
        assertEquals(463L, finalRow["accessAttempts"])
        assertEquals(savedSetterState, finalRow["t79Setter"])
        val updateSnap = finalRow["t79SecondUpdate"] as Map<*, *>
        assertEquals(liveWords, updateSnap["writes"])

        // Reject forged restored snapshots (mismatched cursor, forged word, incomplete setter, missing object).
        for (bad in listOf(
            replaceRow(finalState, mapOf("position" to 459, "accessAttempts" to 462L)),
            replaceRow(finalState, mapOf("t79SecondUpdate" to (updateSnap + ("writes" to liveWords.dropLast(1))))),
            replaceRow(finalState, mapOf("t79SecondUpdate" to (updateSnap + ("writes" to (listOf(0x10000000L) + liveWords.drop(1)))))),
            replaceRow(finalState, mapOf("t79Setter" to (savedSetterState + ("writes" to (savedSetterState["writes"] as List<*>).dropLast(1))))),
            replaceRow(finalState, mapOf("t79SecondUpdate" to null)),
        )) {
            assertFails { bus(updateConfig).restoreSnapshot(bad, true) }
        }

        // 66th access refuses (finite-input-missing-or-exhausted).
        lr = T79ImageSecondUpdate.registers.last().lr
        assertFails { active.access(100, base + 0x228, 4, true, 0x00170435) }
    }

    @Test fun setterPromotesOnlyFinishedGettersAndRestoresTheSamePayloadPrefix() {
        val previous = selected + mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id,
            ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        val next = previous + ("image70000" to T79ImageConfigSetter.PROFILE)
        val empty = bus(previous).saveSnapshot()
        fun replaceRow(state: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
            val regions = state["regions"] as Map<*, *>
            val row = regions[Image70000.REGION] as Map<*, *>
            return state + ("regions" to regions + (Image70000.REGION to row + changes))
        }
        fun getters(position: Int, status: ImageIrq106Status = ImageIrq106Status.FINISHED) =
            replaceRow(empty, mapOf("position" to position, "accessAttempts" to position + status.accesses,
                "retainedWritten" to true)) + (ImageIrq106Status.KEY to status.receipt())
        val saved = getters(351)
        for (invalid in listOf(getters(309), getters(350), getters(352), getters(351, ImageIrq106Status.IDLE),
            replaceRow(saved, mapOf("retainedWritten" to false)), replaceRow(saved, mapOf("accessAttempts" to 351)),
            replaceRow(saved, mapOf("strategy" to DiscoveryStrategy.IMAGE_70000_REINIT.id)),
            replaceRow(saved, mapOf("t79Setter" to T79ImageConfigSetter().snapshot())))) {
            assertFails { bus(next).restoreSnapshot(invalid, true) }
        }
        assertFails { bus(next).restoreSnapshot(saved) }
        lr = 0x11b9720
        assertFails { bus(previous).apply { restoreSnapshot(saved) }.access(1, base + 0x20, 4, false) }
        assertFails { bus(next).access(1, base + 0x20, 4, false) }
        var active = bus(next).apply { restoreSnapshot(saved, true) }
        fun roundTrip() {
            val state = active.saveSnapshot()
            active = bus(next).apply { restoreSnapshot(state) }
            assertEquals(state, active.saveSnapshot())
            assertFails { bus(previous).restoreSnapshot(state, true) }
        }
        roundTrip()
        assertEquals(0L, active.access(1, base + 0x20, 4, false))
        roundTrip()
        pc = 0x5af98
        lr = 0x11b972c
        assertEquals(1L, active.access(2, base + 0x20, 4, true, 1))
        lr = 0x11b9770
        val beforeFields = active.saveSnapshot()
        assertFails { bus(next).apply { restoreSnapshot(beforeFields) }.access(3, base + 0x28, 4, true, 1) }
        assertEquals(0xabc0123L, active.access(3, base + 0x24, 4, true, 0xabc0123))
        roundTrip()
        val state = active.saveSnapshot()
        val row = (state["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        val setter = row["t79Setter"] as Map<*, *>
        assertEquals(listOf(1L, 0xabc0123L), setter["writes"])
        assertEquals(354, row["position"])
        for (bad in listOf(setter + ("writes" to listOf(1)), setter + ("enabled" to false), null)) {
            assertFails { bus(next).restoreSnapshot(replaceRow(state, mapOf("t79Setter" to bad)), true) }
        }
        fun write(offset: Int, caller: Long, payload: Long = 0) {
            lr = caller
            assertEquals(payload, active.access(4, base + offset, 4, true, payload))
        }
        val fields = listOf(0x11b9788L, 0x11b97bc, 0x11b97fc, 0x11b97fc, 0x11b97fc, 0x11b982c,
            0x11b985c, 0x11b989c, 0x11b98d4, 0x11b9914, 0x11b994c, 0x11b96f0, 0x11bd280)
        fields.forEachIndexed { index, caller -> write(0x28 + 4 * index, caller) }
        repeat(6) { group ->
            listOf(0x11b9dfcL, 0x11b9e18, 0x11b9e34, 0x11b9e60).forEachIndexed { index, caller ->
                write(0x9c + 16 * group + 4 * index, caller)
            }
        }
        write(0xfc, 0x11ba13c)
        write(0x100, 0x11ba16c)
        write(0x104, 0x11ba19c)
        write(0x130, 0x11bab7c, 0x503ff)
        roundTrip()
        val finished = (active.saveSnapshot()["regions"] as Map<*, *>)[Image70000.REGION] as Map<*, *>
        assertEquals(395, finished["position"])
        assertEquals(398L, finished["accessAttempts"])
        assertFails { active.access(5, base + 0x130, 4, true, 0x503ff) }
    }

    @Test fun offlineSetterPromotionFromBlankCardDiscoverySnapshot() {
        val source = System.getenv("FUJI_T79_SETTER_NEAR")
        org.junit.jupiter.api.Assumptions.assumeTrue(source != null)
        val directory = Path.of(checkNotNull(source))
        val home = directory.resolve("home")
        val config = Formats.read(home.resolve("config/emulator.yaml"))
        val settings = Formats.map(config["discovery"]) + ("image70000" to T79ImageConfigSetter.PROFILE)
        val configured = CatalogRuntime.load(home, config + ("discovery" to settings))
        val manifest = Formats.json.readValue(directory.resolve("pre-read/manifest.json").toFile(), Map::class.java)
        val saved = manifest["discovery"] as Map<*, *>
        val active = checkNotNull(configured.createBus(context))
        assertFails { active.restoreSnapshot(saved) }
        active.restoreSnapshot(saved, allowPromotedRegions = true)
        lr = 0x11b9720
        assertEquals(0L, active.access(1, base + 0x20, 4, false))
        val state = active.saveSnapshot()
        val restored = checkNotNull(configured.createBus(context))
        restored.restoreSnapshot(state)
        assertEquals(state, restored.saveSnapshot())
    }

    @Test fun offlineStatefulIspPromotionFromPostDriverSnapshot() {
        val source = System.getenv("FUJI_ROUND2_POST_DRIVER")
        org.junit.jupiter.api.Assumptions.assumeTrue(source != null)
        val directory = Path.of(checkNotNull(source))
        val home = directory.resolve("home")
        val config = Formats.read(home.resolve("config/emulator.yaml"))
        val settings = Formats.map(config["discovery"]) + mapOf(
            "image70000" to T79StatefulIspBank.PROFILE,
            "image6e000" to Image6e000.REPEATABLE_PROFILE,
            "image68000" to DiscoveryStrategy.IMAGE_68000_REPEATABLE.id,
            "image51000" to DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE.id,
            "image50000Control" to DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE.id,
        )
        val configured = CatalogRuntime.load(home, config + ("discovery" to settings))
        val manifest = Formats.json.readValue(directory.resolve("post-driver/manifest.json").toFile(), Map::class.java)
        val saved = manifest["discovery"] as Map<*, *>
        val active = checkNotNull(configured.createBus(context))
        assertFails { active.restoreSnapshot(saved) }
        active.restoreSnapshot(saved, allowPromotedRegions = true)
        // Exact frontier access from post-driver: core1 READ4 FFF70020 LR 011B9720 -> seeded 1 from t79Setter.
        pc = 0x5afe0; lr = 0x11b9720
        assertEquals(1L, active.access(1, base + 0x20, 4, false))
        // Verify Image50000Control repeatable cycle, Image6e000, Image68000, Image51000 TABLE/EMPTY all work after promotion.
        pc = 0x5af98; lr = 0x11c0f78
        assertEquals(0L, active.access(2, 0xfff50000L, 4, true, 0))
        pc = 0x5afe0; lr = 0x11c0f88
        assertEquals(1L, active.access(3, 0xfff50000L, 4, false))
        pc = 0x5af98; lr = 0x11e3b48
        assertEquals(0x00100f30L, active.access(4, 0xfff6e014L, 4, true, 0x00100f30L))
        pc = 0x5af98; lr = 0x11cb8a8
        assertEquals(0x12345678L, active.access(5, 0xfff68000L, 4, true, 0x12345678L))
        pc = 0x5af98; lr = 0x11c2538
        assertEquals(0x0014040fL, active.access(6, 0xfff51008L, 4, true, 0x0014040fL))
        pc = 0x5af98; lr = 0x11c2654
        assertEquals(0x00010002L, active.access(7, 0xfff55000L, 4, true, 0x00010002L))
        val state = active.saveSnapshot()
        val restored = checkNotNull(configured.createBus(context))
        restored.restoreSnapshot(state)
        assertEquals(state, restored.saveSnapshot())
    }

    @Test fun secondConfigurationReadPassRequiresTheCompletedWriterAndResumesInOrder() {
        val previous = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_REINIT.id)
        val next = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id)
        val empty = bus(previous).saveSnapshot()
        val regions = empty.getValue("regions") as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        fun saved(position: Int) = empty + ("regions" to regions + (Image70000.REGION to row + mapOf(
            "position" to position, "accessAttempts" to position, "retainedWritten" to true, "retainedValue" to 0x10000)))
        assertFails { bus(next).restoreSnapshot(saved(308), true) }
        assertFails { bus(next).restoreSnapshot(saved(309)) }
        fun promoted() = bus(next).apply { restoreSnapshot(saved(309), true) }
        lr = 0x11b9b7c
        assertFails { promoted().access(1, base + 0x54, 4, false) }
        assertFails { promoted().access(1, base + 0x20, 4, true, 0) }
        assertEquals(42, Image70000ConfigWords.reads.size)
        var active = promoted()
        for ((index, read) in Image70000ConfigWords.reads.withIndex()) {
            lr = read.lr
            if (index == 1) {
                val state = active.saveSnapshot()
                assertFails { bus(previous).restoreSnapshot(state, true) }
                active = bus(next).apply { restoreSnapshot(state) }
            }
            assertEquals(0L, active.access(1, base + read.offset, 4, false))
        }
        lr = 0x11b9b7c
        assertFails { active.access(1, base + 0x20, 4, false) }
    }

    @Test fun secondInitializationInheritsTeardownAndStopsAtTheFirstUnknownRegister() {
        val previous = selected + mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_TEARDOWN.id,
            ImageIrq106Status.KEY to ImageIrq106Status.PROFILE)
        val next = previous + ("image70000" to DiscoveryStrategy.IMAGE_70000_REINIT.id)
        val high = 0xace10000L
        val empty = bus(previous).saveSnapshot()
        val regions = empty.getValue("regions") as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        fun saved(position: Int, status: ImageIrq106Status = ImageIrq106Status.FINISHED) = empty + mapOf(
            ImageIrq106Status.KEY to status.receipt(), "regions" to regions + (Image70000.REGION to row + mapOf(
                "position" to position, "accessAttempts" to position + status.accesses, "retainedWritten" to true,
                "retainedValue" to high)))
        assertFails { bus(next).restoreSnapshot(saved(285)) }
        for (position in listOf(278, 284, 286)) assertFails { bus(next).restoreSnapshot(saved(position), true) }
        assertFails { bus(next).restoreSnapshot(saved(285, ImageIrq106Status.ACKNOWLEDGED), true) }
        assertFails { bus(previous).apply { restoreSnapshot(saved(285)) }.access(0, base, 4, false) }
        var state = bus(next).apply { restoreSnapshot(saved(285), true) }.saveSnapshot()
        fun restored() = bus(next).apply { restoreSnapshot(state) }
        val offsets = listOf(0, 0, 0, 0x2e8, 0x2e4, 0x2e4, 12, 12, 12,
            0x19c, 0x1a0, 0x1a4, 0x1a8, 0x1fc, 0x1f8, 0x208, 0x20c, 0x210, 0x214, 0x218, 0x21c, 0x220, 0x1c, 0x198)
        val callers = listOf(0x11b8154L, 0x11b8160, 0x11b8170, 0x11b8188, 0x11b81a0, 0x11b81ac,
            0x11b7e50, 0x11b7f0c, 0x11b7c58, 0x11b8600, 0x11b8620, 0x11b8640, 0x11b8654, 0x11b86a0,
            0x11b86c0, 0x11b86e0, 0x11b86f4, 0x11b8714, 0x11b8728, 0x11b8748, 0x11b8768, 0x11b89b0, 0x11b89e0, 0x11b89f4)
        val words = listOf(1L, 0, 1, 0x10000, high + 0x20, high + 0x20, 0, 0, 0,
            0x10101, 0xf00d1234, 0xffff0001, 8, 0x3fff3fff, 0x2d0101, 0x21, 3, 0x24, 5, 0x700080, 0x80000001, 2, 0x66480, 0xabc)
        for (index in offsets.indices) {
            val write = index in listOf(1, 5, 7) || index >= 9
            val address = base + offsets[index]
            lr = callers[index]
            pc = if (write) 0x5af98 else 0x5afe0
            val word = words[index]
            if (index == 0) {
                core = 0
                assertFails { restored().access(0, base, 4, false) }
                core = 1
                assertFails { restored().access(0, base, 2, false) }
                assertFails { restored().access(0, base + 4, 4, false) }
                lr += 4
                assertFails { restored().access(0, base, 4, false) }
                lr -= 4
            }
            if (index == 6) {
                irqActive = true
                assertFails { restored().access(0, address, 4, false) }
                irqActive = false
            }
            val invalid = when (index) {
                1, 7 -> 1L
                5 -> high
                9 -> 0x10301L
                12 -> 9L
                14 -> 0x300000L
                21 -> 3L
                22 -> 0L
                else -> null
            }
            if (invalid != null) assertFails { restored().access(0, address, 4, true, invalid) }
            val active = restored()
            assertEquals(word, active.access(0, address, 4, write, if (write) word else 0))
            state = active.saveSnapshot()
            assertEquals(ImageIrq106Status.FINISHED.receipt(), state[ImageIrq106Status.KEY])
        }
        assertFails { bus(previous).restoreSnapshot(state, true) }
        pc = 0x5afe0
        lr = 0x11b9b7c
        assertFails { restored().access(0, base + 0x20, 4, false) }
        lr = 0x11b8154
        assertFails { restored().access(0, base, 4, false) }
    }

    @Test fun driverBranchesOnlyAtTheCompletedWritesAndQualifiesGuestPayloads() {
        val update = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_UPDATE.id)
        val driver = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_DRIVER.id)
        val saved = bus(update).saveSnapshot()
        val regions = saved.getValue("regions") as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        fun at(position: Int, strategy: DiscoveryStrategy = DiscoveryStrategy.IMAGE_70000_UPDATE,
               attempts: Int = position, written: Boolean = false, retained: Long = 0) = saved + ("regions" to
            regions + (Image70000.REGION to row + mapOf("strategy" to strategy.id, "position" to position,
                "accessAttempts" to attempts, "retainedWritten" to written, "retainedValue" to retained)))
        fun promoted() = bus(driver).apply { restoreSnapshot(at(89), true) }
        for (position in listOf(88, 90, 91, 92))
            assertFails { bus(driver).restoreSnapshot(at(position), true) }
        assertFails { bus(driver).restoreSnapshot(at(24, DiscoveryStrategy.IMAGE_70000_CONFIG), true) }
        assertFails { bus(driver).restoreSnapshot(at(9, DiscoveryStrategy.IMAGE_70000), true) }
        assertFails { bus(driver).restoreSnapshot(bus(legacy).saveSnapshot(), true) }
        assertFails { bus(driver).restoreSnapshot(at(89)) }
        assertFails { bus(driver).restoreSnapshot(at(89, attempts = 88), true) }
        assertFails { bus(driver).restoreSnapshot(at(89, retained = 0xffff0000), true) }
        assertEquals(bus(update).pageRanges(), promoted().pageRanges())
        lr = 0x11bb4ac
        pc = 0x5af98
        assertFails { bus(update).apply { restoreSnapshot(at(89)) }.access(1, base + 0x254, 4, true, 0xfff11060) }
        for (offset in listOf(0x258, 0x22c, 0x2d4)) assertFails { promoted().access(1, base + offset, 4, true, 32) }
        assertFails { promoted().access(1, base + 0x254, 4, true, 33) }
        assertFails { promoted().access(1, base + 0x254, 4, false) }
        assertFails { promoted().access(1, base + 0x254, 2, true, 32) }
        core = 0
        assertFails { promoted().access(1, base + 0x254, 4, true, 32) }
        core = 1
        pc += 4
        assertFails { promoted().access(1, base + 0x254, 4, true, 32) }
        pc -= 4
        lr += 4
        assertFails { promoted().access(1, base + 0x254, 4, true, 32) }
        lr -= 4
        val first = promoted()
        assertEquals(0xabcdef00, first.access(1, base + 0x254, 4, true, 0xabcdef00))
        val paired = first.saveSnapshot()
        assertFails { bus(update).restoreSnapshot(paired, true) }
        lr = 0x11bb4bc
        fun resumed() = bus(driver).apply { restoreSnapshot(paired) }
        assertFails { resumed().access(2, base + 0x258, 4, true, 4) }
        assertEquals(3L, resumed().access(2, base + 0x258, 4, true, 3))

        val strategy = DiscoveryStrategy.IMAGE_70000_DRIVER
        var current = at(131, strategy)
        fun step(offset: Int, caller: Long, write: Boolean, word: Long) {
            pc = if (write) 0x5af98 else 0x5afe0
            lr = caller
            val restored = bus(driver).apply { restoreSnapshot(current) }
            assertEquals(word, restored.access(1, base + offset, 4, write, if (write) word else 0))
            current = restored.saveSnapshot()
        }
        step(0x14, 0x11bd11c, true, 0xffffffff)
        step(0x18, 0x11bd12c, true, 2)
        lr = 0x11bd000
        assertFails { bus(driver).apply { restoreSnapshot(current) }.access(1, base + 16, 4, true, 2) }
        step(16, lr, true, 0x10001)
        step(0x2e4, 0x11bc318, false, 0)
        val high = 0xace10000L
        step(0x2e4, 0x11bc32c, true, high)
        for (invalid in listOf(at(135, strategy, written = true), at(136, strategy),
            at(136, strategy, written = true, retained = high + 1), at(145, strategy, written = true)))
            assertFails { bus(driver).restoreSnapshot(invalid) }
        step(0x2e4, 0x11bcdd0, false, high)
        lr = 0x11bcde8
        pc = 0x5af98
        assertFails { bus(driver).apply { restoreSnapshot(current) }.access(1, base + 0x2e4, 4, true, 0x20) }
        step(0x2e4, lr, true, high + 0x20)
        step(0x2e4, 0x11bce18, false, high + 0x20)
        step(0x2e4, 0x11bce24, true, high + 0x120)
        step(4, 0x11b8238, false, 0)
        pc = 0x5af98
        lr = 0x11b82c0
        assertFails { bus(driver).apply { restoreSnapshot(current) }.access(1, base + 4, 4, true, 0x03df03df) }
        step(4, 0x11b82c0, true, 0x03ff03ff)
        step(8, 0x11b82cc, false, 0)
        step(8, 0x11b8354, true, 0x03df03df)
        pc = 0x5afe0
        lr = 0x11b7e50
        assertFails { bus(driver).apply { restoreSnapshot(current) }.access(1, base + 12, 4, false) }
        val getters = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_GETTERS.id)
        fun getterStart() = bus(getters).apply { restoreSnapshot(current, true) }
        assertFails { bus(getters).restoreSnapshot(current) }
        assertFails { bus(getters).restoreSnapshot(at(145, strategy, written = true), true) }
        assertFails { bus(getters).restoreSnapshot(at(144, strategy), true) }
        assertFails { bus(getters).restoreSnapshot(at(89), true) }
        assertFails { getterStart().access(1, base + 12, 4, false) }
        lr = 0x11ba500
        assertFails { bus(driver).apply { restoreSnapshot(current) }.access(1, base + 0x134, 4, false) }
        assertFails { getterStart().access(1, base + 0x138, 4, false) }
        assertFails { getterStart().access(1, base + 0x134, 2, false) }
        assertFails { getterStart().access(1, base + 0x134, 4, true, 0x80ff1) }
        val getter = getterStart()
        assertEquals(0x80ff1L, getter.access(1, base + 0x134, 4, false))
        val partialGetter = getter.saveSnapshot()
        assertFails { bus(driver).restoreSnapshot(partialGetter, true) }
        val resumedGetter = bus(getters).apply { restoreSnapshot(partialGetter) }
        lr = 0x11ba52c
        assertEquals(0x950f2bL, resumedGetter.access(1, base + 0x138, 4, false))
        val getterStrategy = DiscoveryStrategy.IMAGE_70000_GETTERS
        fun restoredAt(position: Int) = bus(getters).apply {
            restoreSnapshot(at(position, getterStrategy, written = true, retained = high))
        }
        lr = 0x11be510
        assertFails { restoredAt(209).access(1, base + 0x204, 4, false) }
        assertEquals(0L, restoredAt(277).access(1, base + 0x204, 4, false))
        lr = 0x11ba500
        assertFails { restoredAt(278).access(1, base + 0x134, 4, false) }
        assertFails { restoredAt(279) }
    }

    @Test fun teardownRestoresOnlyCompletedGettersAndChecksTheGuestPhaseOnEveryAccess() {
        val getters = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_GETTERS.id)
        val teardown = selected + ("image70000" to DiscoveryStrategy.IMAGE_70000_TEARDOWN.id)
        val empty = bus(getters).saveSnapshot()
        val regions = empty["regions"] as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        val high = 0xabc00000L
        fun boundary(position: Int) = empty + ("regions" to regions + (Image70000.REGION to row +
            mapOf("position" to position, "accessAttempts" to position, "retainedWritten" to true, "retainedValue" to high)))
        fun resumed() = bus(teardown).apply { restoreSnapshot(boundary(278), true) }
        assertFails { bus(teardown).restoreSnapshot(boundary(277), true) }
        assertFails { bus(teardown).restoreSnapshot(boundary(278)) }
        lr = 0x11b8238
        assertFails { resumed().access(1, base + 4, 4, false) }
        t79Teardown = true
        assertFails { bus(getters).apply { restoreSnapshot(boundary(278)) }.access(1, base + 4, 4, false) }
        assertFails { resumed().access(1, base + 4, 2, false) }
        lr = 0x11bcee4
        assertFails { resumed().access(1, base + 0x2e8, 4, false) }
        val offsets = listOf(4, 4, 8, 8, 0x2e4, 0x2e4, 0x2e8)
        val callers = listOf(0x11b8238L, 0x11b82c0, 0x11b82cc, 0x11b8354, 0x11bcec8, 0x11bced4, 0x11bcee4)
        val values = listOf(0x03ff03ffL, 0x03ff0000, 0x03df03df, 0x03ff0000, high + 0x120, high + 0x20, 0x10000)
        var state = resumed().saveSnapshot()
        fun restored() = bus(teardown).apply { restoreSnapshot(state) }
        for (index in offsets.indices) {
            lr = callers[index]
            val write = index % 2 == 1
            pc = if (write) 0x5af98 else 0x5afe0
            val address = base + offsets[index]
            val expected = values[index]
            t79Teardown = false
            assertFails { restored().access(1, address, 4, write, expected) }
            t79Teardown = true
            if (write) assertFails { restored().access(1, address, 4, true, expected xor 1) }
            val next = restored()
            assertEquals(expected, next.access(1, address, 4, write, if (write) expected else 0))
            state = next.saveSnapshot()
        }
        assertFails { restored().access(1, base + 0x2e8, 4, false) }
        assertFails { bus(getters).restoreSnapshot(state, true) }
    }

    @Test fun addsOneTransportPageAndRejectsHolesAndUnqualifiedAccesses() {
        fun pages(settings: Map<String, String>) = MmioRouter(emptyList(), bus(settings)).pageRanges()
        assertEquals(listOf(base to 4096L), pages(selected) - pages(legacy).toSet())
        assertNull(runtime(selected, "strict").createBus(context))
        assertFails { bus(legacy + ("image70000" to "retained-words-zero-v1")) }
        fun router() = MmioRouter(emptyList(), bus())
        for (offset in listOf(-4, 1, 4, 8, 0x10, 0x2e0, 0x2ec, 0xffc))
            assertFails { router().read(base + offset, 4) }
        for (width in listOf(1, 2, 8)) assertFails { router().read(base, width) }
        assertFails { router().read(base + 0x2e8, 4) }
        assertFails { router().write(base, 4, 0) }
        core = 0
        assertFails { router().read(base, 4) }
        core = 1
        pc += 4
        assertFails { router().read(base, 4) }
        pc -= 4
        lr += 4
        assertFails { router().read(base, 4) }
    }

    @Test fun restoresEveryBoundaryWithoutRepeatingOrDroppingTheSequence() {
        val old = bus(legacy).saveSnapshot()
        assertFails { bus().restoreSnapshot(old) }
        var saved = bus().apply { restoreSnapshot(old, true) }.saveSnapshot()
        val offsets = listOf(0, 0, 0, 0x2e8, 0x2e4, 0x2e4, 12, 12, 12)
        val callers = listOf(0x11b8154L, 0x11b8160, 0x11b8170, 0x11b8188, 0x11b81a0, 0x11b81ac,
            0x11b7e50, 0x11b7f0c, 0x11b7c58)
        val words = listOf(0L, 0, 1, 0x10000, 0, 0, 0, 0, 0)
        for (position in offsets.indices) {
            val write = position in setOf(1, 5, 7)
            val address = base + offsets[position]
            lr = callers[position]
            pc = if (write) 0x5af98 else 0x5afe0
            fun resumed() = bus().apply { restoreSnapshot(saved) }
            if (write) assertFails { resumed().access(1, address, 4, true, 1) }
            val active = resumed()
            assertEquals(words[position], active.access(1, address, 4, write, 0))
            saved = active.saveSnapshot()
        }
        assertFails { bus(legacy).restoreSnapshot(saved, true) }
        assertFails { bus().apply { restoreSnapshot(saved) }.access(1, base + 12, 4, false) }
        val regions = saved.getValue("regions") as Map<*, *>
        val row = regions[Image70000.REGION] as Map<*, *>
        for (change in listOf(mapOf("position" to 8), mapOf("position" to 10, "accessAttempts" to 10))) {
            val altered = saved + ("regions" to regions + (Image70000.REGION to row + change))
            assertFails { bus().restoreSnapshot(altered, true) }
        }
        val extended = selected + ("image70000" to "setup-polls-config-once-v1")
        val forged = saved + ("regions" to regions + (Image70000.REGION to row + mapOf("position" to 10, "accessAttempts" to 10)))
        assertFails { bus(extended).restoreSnapshot(forged, true) }
        assertFails { bus(extended).restoreSnapshot(saved) }
        pc = 0x5af98
        lr = 0x11b8600
        assertFails { bus().apply { restoreSnapshot(saved) }.access(1, base + 0x19c, 4, true, 0x10201) }
        val first = bus(extended).apply { restoreSnapshot(saved, true) }
        assertEquals(bus().pageRanges(), first.pageRanges())
        val start = first.saveSnapshot()
        fun resumed() = bus(extended).apply { restoreSnapshot(start) }
        assertFails { resumed().access(1, base + 0x19c, 4, false) }
        assertFails { resumed().access(1, base + 0x19c, 4, true, 0x10200) }
        assertFails { resumed().access(1, base + 0x19c, 2, true, 1) }
        assertFails { resumed().access(1, base + 0x1ac, 4, true, 0x10201) }
        core = 0
        assertFails { resumed().access(1, base + 0x19c, 4, true, 0x10201) }
        core = 1
        lr = 0x11b8620
        assertFails { resumed().access(1, base + 0x1a0, 4, true, 0x02000000) }
        lr = 0x11b8600
        first.access(1, base + 0x19c, 4, true, 0x10201)
        val partial = first.saveSnapshot()
        val update = selected + ("image70000" to "setup-polls-config-update-once-v1")
        assertFails { bus(update).restoreSnapshot(partial, true) }
        assertFails { bus().restoreSnapshot(partial, true) }
        assertFails { bus(extended).apply { restoreSnapshot(partial) }.access(2, base + 0x19c, 4, true, 0x10201) }
        lr = 0x11b8620
        assertEquals(0x02000000L, bus(extended).apply { restoreSnapshot(partial) }.access(2, base + 0x1a0, 4, true, 0x02000000))
    }
}
