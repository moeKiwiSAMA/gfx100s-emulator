package com.github.nyabanban.fuji.emulator.components.cmos

import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException
import kotlin.math.floor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import org.junit.jupiter.api.Test

class ExposureBayerScalerTest {
    @Test
    fun scalesEveryInputCodeAroundBlackIntoHeapAndDirectSlices() {
        val scaler = ExposureBayerScaler()
        for (depth in listOf(8, 12, 16)) {
            val wide = depth > 8
            val samples = if (wide) 65536 else 256
            val src = ByteBuffer.allocate(samples * if (wide) 2 else 1).order(ByteOrder.LITTLE_ENDIAN)
            // Permute all input codes so adjacent pixels exercise different LUT regions.
            for (i in 0 until samples) {
                val sample = (i * 31337) and (samples - 1)
                if (wide) src.putShort(sample.toShort()) else src.put(sample.toByte())
            }
            val max = (1 shl depth) - 1
            for (ratio in listOf(0.0, 0.25, 0.8264, 1.0, 2.0, 256.0, 256.01)) {
                val black = max / 10
                val expect = ByteBuffer.allocate(src.capacity()).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until samples) {
                    val sample = (i * 31337) and (samples - 1)
                    val signal = sample - black
                    val scaled = if (ratio <= 256.0) {
                        val quantized = floor(ratio * 65536.0 + 0.5) / 65536.0
                        black + floor(signal * quantized + 0.5).toInt()
                    } else {
                        (black + signal * ratio + 0.5).toInt()
                    }.coerceIn(0, max)
                    if (wide) expect.putShort(scaled.toShort()) else expect.put(scaled.toByte())
                }
                for (direct in listOf(false, true)) {
                    val size = src.capacity() + 7
                    val root = if (direct) ByteBuffer.allocateDirect(size) else ByteBuffer.allocate(size)
                    root.put(2, 0x35)
                    root.put(size - 4, 0x65)
                    val slice = root.position(1).slice()
                    slice.position(2).limit(src.capacity() + 2)
                    val order = slice.order()
                    scaler.copy(src.array(), slice, ratio, black, depth)
                    assertEquals(slice.limit(), slice.position())
                    assertEquals(order, slice.order())
                    val actual = ByteArray(src.capacity())
                    root.get(3, actual)
                    assertContentEquals(expect.array(), actual)
                    assertEquals(0x35.toByte(), root.get(2))
                    assertEquals(0x65.toByte(), root.get(size - 4))
                }
            }
        }
    }

    @Test
    fun rejectsIncompleteOrReadOnlyDestinationsWithoutAdvancingThem() {
        val scaler = ExposureBayerScaler()
        val src = ByteArray(16)
        val small = ByteBuffer.allocate(15)
        val readOnly = ByteBuffer.allocate(16).asReadOnlyBuffer()
        assertFailsWith<BufferOverflowException> { scaler.copy(src, small, 2.0, 0, 16) }
        assertFailsWith<ReadOnlyBufferException> { scaler.copy(src, readOnly, 2.0, 0, 16) }
        assertEquals(0, small.position())
        assertEquals(0, readOnly.position())
    }

    @Test
    fun ongoingOwnerReadSurvivesMultipleWorkerPublications() {
        val buffers = PublishedBayerBuffers(4)
        for (ownerFrame in 0..4) {
            val read = buffers.pinForOwner()
            val expected = read.copyOf()
            repeat(3) {
                assertNotSame(read, buffers.back)
                buffers.back.fill((ownerFrame * 3 + it + 1).toByte())
                buffers.publishBack()
                assertContentEquals(expected, read)
            }
        }
    }
}
