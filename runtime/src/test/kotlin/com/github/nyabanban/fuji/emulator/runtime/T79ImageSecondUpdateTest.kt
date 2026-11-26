package com.github.nyabanban.fuji.emulator.runtime

import kotlin.test.*
import org.junit.jupiter.api.Test

class T79ImageSecondUpdateTest {
    private var core = 1
    private var pc = 0x5af98L
    private var lr = 0L
    private val owner = object : DiscoveryContext {
        override fun core() = core
        override fun pc() = pc
        override fun lr() = lr
        override fun tick() = 0L
    }
    private var bank = T79ImageSecondUpdate()

    private fun roundTrip() {
        val snapshot = bank.snapshot()
        val receipt = bank.receipt()
        bank = T79ImageSecondUpdate.restore(snapshot)
        assertEquals(receipt, bank.receipt())
        assertEquals(snapshot, bank.snapshot())
    }

    private fun reject(offset: Int, width: Int = 4, write: Boolean = true, value: Long = 0) {
        val before = bank.snapshot()
        assertNull(bank.access(offset, width, write, value, owner))
        assertEquals(before, bank.snapshot())
    }

    private val round1Words: List<Long> = buildList {
        add(0x80ff1); add(0x950f2b); add(0)
        for (pair in 0 until 10) {
            add(if (pair == 0) 0x7070121 else 0x10000)
            add(if (pair == 0) 0x1c4005f else 0x10000)
        }
        add(0x80007); add(0x20007); add(0)
        add(0x80007); add(0x20007); add(0)
        add(0x80007); add(0x20007)
        repeat(5) { add(0x80000); add(0x20001) }
        add(0x80007); add(0x20007); add(0)
        add(0x210f1d); add(0xa0003)
        add(0x210f1d); add(0x40005)
        add(0x10001); add(0x10000)
        add(0x10101); add(0x10000)
        add(0x10101); add(0x10000)
        add(0x10001); add(0x10000); add(0)
        add(0x10001); add(0x10000); add(0)
        add(0x10001); add(1); add(1)
        add(1); add(0)
    }

    private val liveRound2Words: List<Long> = round1Words.toMutableList().apply {
        this[0] = 0x00170fef
        this[1] = 0x00210f41
        this[3] = 0x0707011f
        this[4] = 0x01e9008d
        this[23] = 0x00cf61ef
        this[24] = 0x004e6089
        this[26] = 0x00cf61ef
        this[27] = 0x004e6089
        this[29] = 0x000f01e1
        this[30] = 0x00190085
        this[41] = 0x00cf61ef
        this[42] = 0x004e6089
        this[48] = 0x00210f1d
        this[49] = 0x00000003
        this[63] = 0x00160eff
        this[64] = 0x00170435
    }

    private fun replay(words: List<Long>) {
        assertEquals(65, words.size)
        assertEquals(65, T79ImageSecondUpdate.registers.size)
        val accepted = linkedMapOf<String, Long>()
        for ((index, register) in T79ImageSecondUpdate.registers.withIndex()) {
            val word = words[index]
            pc = 0x5af98
            lr = register.lr
            assertEquals(word, bank.access(register.offset, 4, true, word, owner))
            assertEquals(word, bank.retainedWord(register.offset))
            accepted[register.offset.toString(16)] = word
            assertEquals(index + 1, bank.accesses)
            assertEquals(accepted, bank.receipt()["writtenWords"])
            roundTrip()
        }
        assertTrue(bank.complete)
        assertEquals(65, bank.accesses)
        assertNull(bank.retainedWord(0x200))
        assertNull(bank.retainedWord(0x204))
        for ((index, register) in T79ImageSecondUpdate.registers.withIndex()) {
            assertEquals(words[index], bank.retainedWord(register.offset))
        }
        val last = T79ImageSecondUpdate.registers.last()
        lr = last.lr
        reject(last.offset, value = words.last())
    }

    @Test fun acceptsAll65Round1WordsInOrderAndRoundTripsSnapshots() = replay(round1Words)

    @Test fun acceptsAll65ObservedLiveRound2WordsInOrder() = replay(liveRound2Words)

    @Test fun rejectsUnqualifiedOrOutOfOrderAccessesWithoutMovingCursor() {
        val first = T79ImageSecondUpdate.registers[0]
        val second = T79ImageSecondUpdate.registers[1]
        lr = first.lr
        reject(first.offset, width = 2, value = 0x00170fef)
        reject(first.offset, width = 1, value = 0xef)
        reject(first.offset, write = false, value = 0)
        reject(second.offset, value = 0x00210f41)
        reject(0x020, value = 1)
        core = 0
        reject(first.offset, value = 0x00170fef)
        core = 1
        pc = 0x5afe0
        reject(first.offset, value = 0x00170fef)
        pc = 0x5af9c
        reject(first.offset, value = 0x00170fef)
        pc = 0x5af98
        lr = first.lr + 4
        reject(first.offset, value = 0x00170fef)
        lr = second.lr
        reject(second.offset, value = 0x00210f41)
        lr = first.lr
        assertEquals(0x00170fefL, bank.access(first.offset, 4, true, 0x00170fef, owner))
        assertEquals(1, bank.accesses)
        // Repeating first write or jumping ahead fails without moving cursor.
        reject(first.offset, value = 0x00170fef)
        lr = T79ImageSecondUpdate.registers[2].lr
        reject(T79ImageSecondUpdate.registers[2].offset, value = 0)
        assertEquals(1, bank.accesses)
    }

    @Test fun enforcesExactBitmasksFieldMinimumsAndParityOnEveryRegister() {
        val cases = mapOf(
            0x134 to (listOf(0L, 0x0fff0fffL) to listOf(-1L, 0x10000000L, 0x00001000L, 0x100000000L)),
            0x138 to (listOf(0L, 0x0fff0fffL) to listOf(0x10000000L, 0x00001000L)),
            0x13c to (listOf(0L, 0x00ff00ffL) to listOf(0x01000000L, 0x00000100L)),
            0x140 to (listOf(0L, 0x0fff0fffL) to listOf(0x10000000L, 0x00001000L)),
            0x144 to (listOf(0L, 0x0fff0fffL) to listOf(0x10000000L, 0x00001000L)),
            0x05c to (listOf(0x00080007L, 0x00090008L, 0x0ffff1ffL) to listOf(0x00070007L, 0x00080006L, 0x00080207L, 0x10080007L)),
            0x060 to (listOf(0x00020007L, 0x0ffef1ffL) to listOf(0x00000007L, 0x00030007L, 0x00020005L, 0x00020008L, 0x00020207L)),
            0x064 to (listOf(0L, 0x01ff01feL) to listOf(0x00000001L, 0x02000000L, 0x00000200L)),
            0x068 to (listOf(0x00020007L, 0x00030008L, 0x0ffff1ffL) to listOf(0x00010007L, 0x00020006L, 0x00020207L)),
            0x06c to (listOf(0x00020007L, 0x0ffef1ffL) to listOf(0x00000007L, 0x00030007L, 0x00020005L, 0x00020008L)),
            0x070 to (listOf(0L, 0x01ff01feL) to listOf(0x00000001L, 0x02000000L, 0x00000200L)),
            0x190 to (listOf(0L, 0x0fff01ffL) to listOf(0x10000000L, 0x00000200L)),
            0x194 to (listOf(0L, 0x0fff01ffL) to listOf(0x10000000L, 0x00000200L)),
            0x074 to (listOf(0x00080000L, 0x00090000L, 0x0fff0fffL) to listOf(0x00070000L, 0x10080000L, 0x00081000L)),
            0x078 to (listOf(0x00020001L, 0x0ffe0fffL) to listOf(0x00000001L, 0x00030001L, 0x00020000L, 0x00020002L)),
            0x124 to (listOf(0x00080000L, 0x0ffff1ffL) to listOf(0x00070000L, 0x00080200L, 0x10080000L)),
            0x128 to (listOf(0x00020000L, 0x00030000L, 0x0ffff1ffL) to listOf(0x00010000L, 0x00020200L)),
            0x12c to (listOf(0L, 0x00000001L, 0x01ff01ffL) to listOf(0x02000000L, 0x00000200L)),
            0x1b0 to (listOf(0x00000001L, 0x0fff0fffL) to listOf(0x00010000L, 0x10000001L, 0x00001001L)),
            0x1ac to (listOf(0L, 0x3fff003fL) to listOf(0x40000000L, 0x00000040L)),
            0x1b8 to (listOf(0x00000001L, 0x0fff0fffL) to listOf(0x00010000L, 0x10000001L)),
            0x1b4 to (listOf(0L, 0x3fff003fL) to listOf(0x40000000L, 0x00000040L)),
            0x1c0 to (listOf(0x00000001L, 0x0fff0fffL) to listOf(0x00010000L, 0x10000001L)),
            0x1bc to (listOf(0L, 0x3fff003fL) to listOf(0x40000000L, 0x00000040L)),
            0x1c4 to (listOf(0x00000101L, 0x0fffffffL) to listOf(0x00010001L, 0x00010100L, 0x10000101L)),
            0x1c8 to (listOf(0L, 0x3fff03ffL) to listOf(0x40000000L, 0x00000400L)),
            0x1cc to (listOf(0x00000101L, 0x0fffffffL) to listOf(0x00010001L, 0x00010100L, 0x10000101L)),
            0x1d0 to (listOf(0L, 0x3fff03ffL) to listOf(0x40000000L, 0x00000400L)),
            0x1d4 to (listOf(0x00000001L, 0x0fff00ffL) to listOf(0x00010000L, 0x00000101L, 0x10000001L)),
            0x1d8 to (listOf(0L, 0x3fff03ffL) to listOf(0x40000000L, 0x00000400L)),
            0x1dc to (listOf(0L, 0x000003ffL) to listOf(0x00000400L, 0x00010000L)),
            0x1e0 to (listOf(0x00000001L, 0x0fff00ffL) to listOf(0x00010000L, 0x00000101L)),
            0x1e4 to (listOf(0L, 0x3fff03ffL) to listOf(0x40000000L, 0x00000400L)),
            0x1e8 to (listOf(0L, 0x000003ffL) to listOf(0x00000400L, 0x00010000L)),
            0x1ec to (listOf(0L, 0x3fff0fffL) to listOf(0x40000000L, 0x00001000L)),
            0x1f0 to (listOf(0x00000001L, 0x07ff01ffL) to listOf(0L, 0x08000001L, 0x00000201L)),
            0x1f4 to (listOf(0x00000001L, 0x07ff07ffL) to listOf(0L, 0x08000001L, 0x00000801L)),
            0x224 to (listOf(0x00000001L, 0x0ffe0fffL) to listOf(0L, 0x00000002L, 0x00010001L, 0x10000001L)),
            0x228 to (listOf(0L, 0x0fff0fffL) to listOf(0x10000000L, 0x00001000L)),
        )
        for (register in T79ImageSecondUpdate.registers) {
            val lookup = cases[register.offset]
            if (lookup != null) {
                for (good in lookup.first) {
                    assertTrue(register.accepts(good), "Expected 0x${register.offset.toString(16)} to accept 0x${good.toString(16)}")
                }
                for (bad in lookup.second) {
                    assertFalse(register.accepts(bad), "Expected 0x${register.offset.toString(16)} to reject 0x${bad.toString(16)}")
                }
            }
        }
        // Also verify rejection through access() mid-sequence at 0x05c (index 23).
        for (index in 0 until 23) {
            val reg = T79ImageSecondUpdate.registers[index]
            lr = reg.lr
            assertEquals(liveRound2Words[index], bank.access(reg.offset, 4, true, liveRound2Words[index], owner))
        }
        val reg05c = T79ImageSecondUpdate.registers[23]
        assertEquals(0x05c, reg05c.offset)
        lr = reg05c.lr
        reject(0x05c, value = 0x00070007)
        reject(0x05c, value = 0x00080006)
        assertEquals(0x00cf61efL, bank.access(0x05c, 4, true, 0x00cf61ef, owner))
    }

    @Test fun rejectsForgedOrInconsistentSnapshots() {
        val empty = bank.snapshot()
        for (bad in listOf(
            empty + ("profile" to "other"),
            empty - "writes",
            empty + ("extra" to 1),
            empty + ("writes" to "not-a-list"),
            empty + ("writes" to listOf(-1L)),
            empty + ("writes" to listOf(0x10000000L)),
            empty + ("writes" to listOf(0x00001000L)),
            empty + ("writes" to listOf(1.5)),
            empty + ("writes" to listOf("0")),
            empty + ("writes" to List(66) { 0L }),
            empty + ("writes" to liveRound2Words.take(23) + listOf(0x00070007L)),
            empty + ("writes" to liveRound2Words.take(24) + listOf(0x00030007L)),
        )) {
            assertFailsWith<IllegalArgumentException> { T79ImageSecondUpdate.restore(bad) }
        }
    }
}
