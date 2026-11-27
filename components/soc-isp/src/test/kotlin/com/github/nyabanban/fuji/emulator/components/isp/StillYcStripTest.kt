package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StillYcStripTest {
    @Test
    fun overlappingStillStripsPreserveFullFrameProjectionAtAxiBoundary() {
        val width = 96
        val height = 64
        val pitch = width * 2 + 32
        val base = (1L shl 32) - 30L * pitch
        val pixels = ByteArray(width * height) { i -> (15 + 2 * (i / width) + (i % width) / 8).toByte() }
        val frame = IspStillReadout(5, width, height, 1024, 1024, 1024, ByteBuffer.wrap(pixels))
        val engine = LiveViewDmaEngine()
        for (direct in listOf(false, true)) {
            val guard = 0x57.toByte()
            val data = ByteArray(height * pitch + 16) { guard }
            val memory = object : DirectGuestRamPort {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(write && address >= base && size <= height * pitch - (address - base))
                }
                override fun read(address: Long, destination: ByteBuffer) = error("Unexpected read")
                override fun write(address: Long, source: ByteBuffer) {
                    check(address, source.remaining().toLong(), true)
                    source.get(data, (address - base).toInt(), source.remaining())
                }
                override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
                    check(address, size, write)
                    return if (direct) MemorySegment.ofArray(data).asSlice(address - base, size) else null
                }
                override fun notifyDirectWrite(address: Long, size: Long) = check(address, size, true)
            }
            fun render(first: Int, rows: Int) {
                assertTrue(engine.renderOpticalUyvyToDma(
                    5L, base, width, rows, pitch, null, memory, null,
                    stillReadout = frame, firstRow = first, frameHeight = height,
                ))
            }
            render(0, height)
            val expected = data.copyOf()
            val lumas = listOf(4, 32, 58).map { expected[it * pitch + 1].toInt() and 255 }
            assertTrue(lumas[0] < lumas[1] && lumas[1] < lumas[2])
            data.fill(guard)
            val hitsBefore = engine.stillStripCacheHits
            render(0, 36)
            assertTrue(data.sliceArray(36 * pitch until data.size).all { it == guard })
            render(24, 40)
            assertEquals(hitsBefore + 1L, engine.stillStripCacheHits, "Second strip must reuse demosaiced UYVY cache")
            assertContentEquals(expected, data)
            assertEquals(guard, data.last())
        }
    }
}
