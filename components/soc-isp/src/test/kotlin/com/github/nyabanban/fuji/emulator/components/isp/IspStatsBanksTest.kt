package com.github.nyabanban.fuji.emulator.components.isp

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IspStatsBanksTest {
    @Test
    fun frameCompletionWritesOnlyProgrammedChannelsToTheirPhysicalBank() {
        val size = IspStatsSramEngine.BANK_SIZE
        val source = ByteArray(size) { (it * 31 + 1).toByte() }
        val registers = LongArray(IspCoreOffsets.CORE_70000_OFFSETS.size)
        val bank = IspStatsBanks()
        // Selector 0..3 into bank 1; selector 6..9 into bank 0. Give the histogram
        // a high address with the same low word to detect accidental 32-bit aliasing.
        registers[0x22c / 4] = 0xfff17000L
        registers[0x254 / 4] = 0xfff11060L
        registers[0x2b4 / 4] = 0xfff16000L
        registers[0x2b8 / 4] = 1
        val input = MemorySegment.ofArray(source)
        val expected = ByteArray(2 * size)
        source.copyInto(expected, size, 0, 0x1000)
        source.copyInto(expected, 0x1060, 0x1060, 0x2060)
        bank.publish(input, registers, aeReady = true, histogramReady = true)
        assertContentEquals(expected, bank.save())

        Arena.ofConfined().use { arena ->
            val physical = arena.allocate(IspStatsBanks.SIZE.toLong())
            bank.bind(physical)
            assertContentEquals(expected, physical.toArray(ValueLayout.JAVA_BYTE))
            registers[0x22c / 4] = 0xfff10000L
            registers[0x254 / 4] = 0xfff18060L
            source.fill(0x42)
            bank.publish(input, registers, aeReady = true, histogramReady = false)
            expected.fill(0x42, 0, 0x1000)
            expected.fill(0x42, size + 0x1060, size + 0x2060)
            assertContentEquals(expected, physical.toArray(ValueLayout.JAVA_BYTE))
            assertEquals(0x42424242L, bank.read(1, 0x1060, 4))
            bank.bind(null)
        }
        assertContentEquals(expected, bank.save())
        bank.restore(null)
        assertEquals(0L, bank.read(1, 0x1060, 4))
        bank.restore(expected)
        assertContentEquals(expected, bank.save())
    }

    @Test
    fun programsAllOnChipSelectorGroupsAndPreservesTheOtherBank() {
        val size = IspStatsSramEngine.BANK_SIZE
        val registers = LongArray(IspCoreOffsets.CORE_70000_OFFSETS.size)
        val groups = mapOf(
            0x22c to 0, 0x234 to 0x1000, 0x254 to 0x1060, 0x25c to 0x2060,
            0x244 to 0x21a0, 0x24c to 0x25a0, 0x274 to 0x29a0, 0x27c to 0x29c0,
            0x284 to 0x29e0, 0x28c to 0x2a00, 0x294 to 0x2c00, 0x29c to 0x2e00,
            0x2a4 to 0x3000, 0x2ac to 0x3200, 0x264 to 0x3a00, 0x26c to 0x3d00,
            0x23c to 0x4000, 0x2b4 to 0x6000, 0x2bc to 0x6400, 0x2c4 to 0x6800, 0x2cc to 0x6c00,
        )
        val bank = IspStatsBanks()
        for (index in 0..1) {
            for ((reg, offset) in groups) registers[reg / 4] = 0xfff10000L + index * size + offset
            val input = ByteArray(size) { (it * 17 + index + 1).toByte() }
            bank.publish(MemorySegment.ofArray(input), registers, aeReady = true, histogramReady = false)
            assertEquals(0L, bank.read(index, 0x6000, 4))
            bank.publish(MemorySegment.ofArray(input), registers, aeReady = false, histogramReady = true)
            assertContentEquals(input, bank.save().copyOfRange(index * size, (index + 1) * size))
        }
        assertEquals(1L, bank.read(0, 0, 1))
        assertEquals(2L, bank.read(1, 0, 1))
    }
}
