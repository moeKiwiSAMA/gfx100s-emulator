package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageDmaMemoryTest {
    @Test
    fun packed102MpFrameWritesBankOneWithoutAliasingCpuFlash() {
        // S5 @ 0x02733450: base low, base high and size are at +8, +12 and +24.
        val base = 0xf300_0000L
        val poolBytes = 0x0c8f_7440L
        val flash = byteArrayOf(0x42)
        val cpu = object : DirectGuestRamPort {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == base && size == 1L) { "Outside CPU flash test aperture" }
            }
            override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment {
                check(address, size, write)
                return MemorySegment.ofArray(flash)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(sliceDirect(address, destination.remaining().toLong(), false).asByteBuffer())
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected CPU flash write")
            override fun notifyDirectWrite(address: Long, size: Long) = error("Unexpected CPU invalidation")
        }
        val image = ImageDmaMemory(cpu) {}

        // Captured 0x490100 packed output. The last row ends after the active samples, before its padding.
        val stride = 23_744L
        val span = 8_763 * stride + 11_664 * 2
        assertEquals(0xff67_3b60L, base + span)
        image.check(base, span, true)
        val direct = assertNotNull(image.sliceDirect(base, span, true))
        val pair = byteArrayOf(0x62, 0x13, 0x8c.toByte(), 0x57)
        val offsets = listOf(0L, 4_382L * stride, span - pair.size)
        for (offset in offsets) direct.asSlice(offset, pair.size.toLong()).asByteBuffer().put(pair)
        image.notifyDirectWrite(base, span)
        for (offset in offsets) {
            val sample = ByteArray(pair.size)
            image.read(base + offset, ByteBuffer.wrap(sample))
            assertContentEquals(pair, sample)
        }
        val last = base + poolBytes - 1
        image.write(last, ByteBuffer.wrap(byteArrayOf(17)))
        assertFailsWith<IllegalArgumentException> { image.write(last, ByteBuffer.wrap(pair)) }
        assertEquals(17, assertNotNull(image.sliceDirect(last, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        assertFailsWith<IllegalArgumentException> { image.check(base - 1, 2, true) }
        assertEquals(0x42, cpu.sliceDirect(base, 1, false).get(ValueLayout.JAVA_BYTE, 0).toInt())
        image.close()
        // Re-allocating after close starts cleanly zeroed rather than exposing freed memory.
        assertEquals(0, assertNotNull(image.sliceDirect(last, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        image.close()
    }

    @Test
    fun splitsAStillScanlineBetweenCpuAndHighPhysicalMemory() {
        val edge = 0x1_0000_0000L
        val bytes = ByteArray(8)
        val notified = mutableListOf<Long>()
        val cpu = object : DirectGuestRamPort {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= edge - 4 && address + size <= edge + 4)
                require(address >= edge || address + size <= edge)
            }
            override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment {
                check(address, size, write)
                return MemorySegment.ofArray(bytes).asSlice(address - edge + 4, size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(sliceDirect(address, destination.remaining().toLong(), false).asByteBuffer())
            }
            override fun write(address: Long, source: ByteBuffer) {
                sliceDirect(address, source.remaining().toLong(), true).asByteBuffer().put(source)
            }
            override fun notifyDirectWrite(address: Long, size: Long) {
                check(address, size, true)
                notified += address
            }
        }
        val image = ImageDmaMemory(cpu) {}
        val expected = byteArrayOf(12, 34, 56, 78, 90, 98)
        image.write(edge - 3, ByteBuffer.wrap(expected))
        val result = ByteArray(expected.size)
        image.read(edge - 3, ByteBuffer.wrap(result))
        assertContentEquals(expected, result)
        assertEquals(0, bytes.first().toInt())
        assertEquals(0, bytes.last().toInt())
        assertNull(image.sliceDirect(edge - 3, 6, false))
        image.notifyDirectWrite(edge - 3, 6)
        assertEquals(listOf(edge - 3, edge), notified)
    }

    @Test
    fun splitsImageRowAtFlashBoundaryAndKeepsBothScratchBanksIndependent(@TempDir snapshots: Path) {
        val edge = 0xf000_0000L
        val ddr = ByteArray(16)
        val flash = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val cpu = object : DirectGuestRamPort {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= edge - ddr.size && address + size <= edge || !write && address == edge && size <= flash.size)
            }
            fun view(address: Long, size: Long, write: Boolean): MemorySegment {
                check(address, size, write)
                return if (address == edge) MemorySegment.ofArray(flash).asSlice(0, size)
                else MemorySegment.ofArray(ddr).asSlice(address - edge + ddr.size, size)
            }
            override fun sliceDirect(address: Long, size: Long, write: Boolean) = view(address, size, write)
            override fun notifyDirectWrite(address: Long, size: Long) = check(address, size, true)
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(view(address, destination.remaining().toLong(), false).asByteBuffer())
            }
            override fun write(address: Long, source: ByteBuffer) {
                view(address, source.remaining().toLong(), true).asByteBuffer().put(source)
            }
        }
        val image = ImageDmaMemory(cpu) { }
        val first = byteArrayOf(11, 22, 33, 44)
        image.write(edge - 2, ByteBuffer.wrap(first))
        val read = ByteArray(4)
        image.read(edge - 2, ByteBuffer.wrap(read))
        assertContentEquals(first, read)
        assertContentEquals(first.copyOf(2), ddr.copyOfRange(14, 16))
        assertNull(image.sliceDirect(edge - 2, 4, true))
        val second = assertNotNull(image.sliceDirect(0xf100_0000L, 2, true))
        second.set(ValueLayout.JAVA_BYTE, 0, 99.toByte())
        image.notifyDirectWrite(0xf100_0000L, 2)
        assertEquals(33, assertNotNull(image.sliceDirect(edge, 2, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        assertFailsWith<IllegalArgumentException> { image.write(0xf084_2bffL, ByteBuffer.wrap(first)) }
        assertEquals(0, assertNotNull(image.sliceDirect(0xf084_2bffL, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        assertFailsWith<IllegalArgumentException> { image.check(0xf284_2bffL, 2, true) }
        val hashes = image.saveWindows(snapshots)
        assertEquals(setOf("f0000000", "f1000000"), hashes.keys)
        image.write(edge, ByteBuffer.wrap(byteArrayOf(77)))
        image.prepareWindowRestore(snapshots, hashes).invoke()
        assertEquals(33, assertNotNull(image.sliceDirect(edge, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        assertEquals(99, assertNotNull(image.sliceDirect(0xf100_0000L, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        Files.newOutputStream(snapshots.resolve("image-dma-f1000000.bin")).use { it.write(1) }
        assertFailsWith<IllegalArgumentException> { image.prepareWindowRestore(snapshots, hashes) }
        assertEquals(99, assertNotNull(image.sliceDirect(0xf100_0000L, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        image.prepareWindowRestore(snapshots, emptyMap<String, String>()).invoke()
        assertEquals(0, assertNotNull(image.sliceDirect(0xf100_0000L, 1, false)).get(ValueLayout.JAVA_BYTE, 0).toInt())
        cpu.read(edge, ByteBuffer.wrap(read))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), read)
    }
}
