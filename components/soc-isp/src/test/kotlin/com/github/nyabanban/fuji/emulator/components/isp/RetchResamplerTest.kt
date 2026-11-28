package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RetchResamplerTest {
    @Test
    fun highBankY0l2ScalesIntoPitched422BeforeSecondPass() {
        checkTwoPassResize(mapped = false)
        checkTwoPassResize(mapped = true)
    }

    @Test
    fun repeatedRowsRespectEveryActiveByteAndVerticalPhase() {
        val pitch = 12
        val src = 16
        val dst = 128
        val ram = ByteArray(320) { 0x55 }
        val input = listOf(
            byteArrayOf(8, 16, 32, 48, 64, 80, 96, 112),
            byteArrayOf(8, 16, 32, 48, 64, 80, 96, 112),
            byteArrayOf(8, 16, 32, 48, 64, 80, 96, 112),
            byteArrayOf(8, 16, 32, 48, 64, 80, 96, 0),
            byteArrayOf(8, 16, 32, 48, 64, 80, 96, 0),
        )
        input.forEachIndexed { y, pixels -> pixels.copyInto(ram, src + y * pitch) }
        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= 0 && address + size <= ram.size)
            }

            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(ram, address.toInt(), destination.remaining())
            }

            override fun write(address: Long, source: ByteBuffer) {
                source.get(ram, address.toInt(), source.remaining())
            }
        }
        val words = LongArray(0xac / 4)
        words[0x10 / 4] = src.toLong()
        words[0x20 / 4] = pitch.toLong()
        words[0x24 / 4] = 0x50004L
        words[0x30 / 4] = dst.toLong()
        words[0x40 / 4] = pitch.toLong()
        words[0x44 / 4] = 0x90004L
        words[0x5c / 4] = 0x10000L
        words[0x70 / 4] = 0x8000L
        RetchResampler.execute(words, memory)
        for (y in 0 until 9) {
            val top = input[y / 2]
            val bottom = input[minOf(y / 2 + 1, input.lastIndex)]
            val expected = ByteArray(pitch) { i ->
                when {
                    i >= top.size -> 0x55
                    y % 2 == 0 -> top[i]
                    else -> ((top[i] + bottom[i] + 1) / 2).toByte()
                }
            }
            assertContentEquals(expected, ram.copyOfRange(dst + y * pitch, dst + (y + 1) * pitch))
        }
    }
    private fun checkTwoPassResize(mapped: Boolean) {
        val source = 0x1_0000_1000L
        val ram = ByteArray(512) { 0x55 }
        val indexed = object : DmaMemory {
            fun offset(address: Long) = if (address >= source) (address - source).toInt() else address.toInt()
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address == source || address in 0x100..0x1ff)
                require(offset(address) + size <= ram.size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(ram, offset(address), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(ram, offset(address), source.remaining())
            }
        }
        val dirty = mutableListOf<Pair<Long, Long>>()
        val memory: DmaMemory = if (mapped) object : DirectGuestRamPort, DmaMemory by indexed {
            override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? =
                if (address >= source) null else MemorySegment.ofArray(ram).asSlice(indexed.offset(address).toLong(), size)
            override fun notifyDirectWrite(address: Long, size: Long) { dirty += address to size }
        } else indexed
        val buf = ByteBuffer.wrap(ram).order(ByteOrder.LITTLE_ENDIAN)
        fun tileWord(a: Int, chroma: Int, b: Int) = (a shl 2) or (chroma shl 12) or (b shl 22)
        for (pair in 0..3) {
            val level = 10 + pair * 40
            buf.putInt(pair * 8, tileWord(level, 60, level + 4))
            buf.putInt(pair * 8 + 4, tileWord(level + 8, 180, level + 12))
        }
        val words = LongArray(0xac / 4)
        fun set(off: Int, value: Long) { words[off / 4] = value }
        set(0x08, 0x30000)
        set(0x10, 0x1000)
        set(0x14, 1)
        set(0x20, 64)
        set(0x24, 0x20008)
        set(0x30, 0x110)
        set(0x40, 16)
        set(0x44, 0x10004)
        set(0x5c, 0x20000)
        set(0x70, 0x20000)
        set(0x68, 0x8000)
        set(0x78, 0x8000)
        RetchResampler.execute(words, memory)
        assertContentEquals(byteArrayOf(16, 56, 60, 180.toByte(), 96, 136.toByte(), 60, 180.toByte()),
            ram.copyOfRange(0x110, 0x118))
        set(0x08, 0)
        set(0x10, 0x110)
        set(0x14, 0)
        set(0x20, 16)
        set(0x24, 0x10004)
        set(0x30, 0x120)
        set(0x44, 0x10002)
        RetchResampler.execute(words, memory)
        assertContentEquals(byteArrayOf(0x55, 36, 116, 60, 180.toByte(), 0x55), ram.copyOfRange(0x11f, 0x125))
        assertEquals(if (mapped) listOf(0x110L to 8L, 0x120L to 4L) else emptyList(), dirty)
    }

    @Test
    fun outOfBoundsMemoryCheckReturnsFalseWithoutThrowingAndMasksUpper34BitAddress() {
        val base = 0x1_0000_1000L
        val ram = ByteArray(128)
        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= base && address + size <= base + ram.size) {
                    "Out of bounds 0x${address.toString(16)}"
                }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(ram, (address - base).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(ram, (address - base).toInt(), source.remaining())
            }
        }
        val words = LongArray(0xac / 4)
        words[0x08 / 4] = 0L
        words[0x10 / 4] = 0x1000L
        words[0x14 / 4] = 0xFFFF_FFF1L // upper garbage bits above 34-bit AXI must be masked via `and 3L` -> 1
        words[0x20 / 4] = 8L
        words[0x24 / 4] = 0x0002_0004L
        words[0x30 / 4] = 0x1020L
        words[0x34 / 4] = 0xABCD_0001L // masked via `and 3L` -> 1
        words[0x40 / 4] = 8L
        words[0x44 / 4] = 0x0002_0004L
        words[0x5c / 4] = 0x10000L
        words[0x70 / 4] = 0x10000L

        assertEquals(true, RetchResampler.execute(words, memory))

        // Out-of-bounds dst triggers IllegalArgumentException inside memory.check -> must return false, not throw
        words[0x30 / 4] = 0x2000L
        assertEquals(false, RetchResampler.execute(words, memory))

        // Out-of-bounds src triggers IllegalArgumentException inside memory.check -> must return false, not throw
        words[0x30 / 4] = 0x1020L
        words[0x10 / 4] = 0x2000L
        assertEquals(false, RetchResampler.execute(words, memory))
    }
}
