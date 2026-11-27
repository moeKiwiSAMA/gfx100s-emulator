package com.github.nyabanban.fuji.emulator.components.isp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MvePcmTileSourceTest {
    @Test
    fun packedDetailProbeMatchesUnpackedLumaAtEveryTilePosition() {
        val stride = 144
        val grid = ByteArray(64) { i -> ((i % 4 + i / 8 % 4 * 4) * 17).toByte() }
        val memory = ByteBuffer.allocate(stride * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 16) for (x in 0 until 16 step 2) {
            val cell = (grid[36 + y / 4 * 8 + x / 4].toInt() and 255) * 4
            val tile = (8 + y / 2) * stride + (8 + x / 2) * 8 + y % 2 * 4
            memory.putInt(tile, cell or ((x * 31 + y) shl 10) or ((cell + 3) shl 20))
        }
        val expected = ShortArray(384)
        val actual = ShortArray(384)
        val strip = ByteBuffer.allocate(8 * 128)
        for (y in 0 until 16) for (x in 0 until 16) {
            val cell = (grid[36 + y / 4 * 8 + x / 4].toInt() and 255) * 4
            val tile = (8 + y / 2) * stride + (8 + x / 2) * 8 + y % 2 * 4
            val saved = memory.getInt(tile)
            val shift = x % 2 * 20
            for (delta in intArrayOf(-13, -12, 15, 16)) {
                val sample = (cell + delta).coerceIn(0, 1023)
                memory.putInt(tile, (saved and (1023 shl shift).inv()) or (sample shl shift))
                val source = MvePcmTileSource(32, 32, stride, true, 0L, 0L, strip) { address, dst ->
                    dst.put(memory.array(), address.toInt(), dst.remaining())
                    true
                }
                assertTrue(source.readBlock(1, 1, expected))
                val detailed = source.readDetailedBlock(1, 1, actual, grid, 36, 8)
                assertEquals(AvcReferenceFrame.hasFineDetail(expected, grid, 36, 8), detailed)
                if (detailed) assertContentEquals(expected, actual)
            }
            memory.putInt(tile, saved)
        }
    }

    @Test
    fun readsTenBitOriginalsWithoutStridePaddingAndExtendsPartialBlocks() {
        val width = 34
        val height = 18
        val stride = 0x5600
        for (packed in listOf(false, true)) {
            val memory = ByteBuffer.allocate(stride * 28).order(ByteOrder.LITTLE_ENDIAN)
            val uv = stride * 18
            fun y(x: Int, y: Int) = (x * 23 + y * 11) % 1024
            fun cb(x: Int, y: Int) = (30 + x * 7 + y * 13) % 1024
            fun cr(x: Int, y: Int) = (840 + x * 5 + y * 11) % 1024
            for (row in 0 until height step 2) for (x in 0 until width step 2) {
                if (packed) {
                    val tile = row / 2 * stride + x / 2 * 8
                    memory.putInt(tile, y(x, row) or (cb(x / 2, row / 2) shl 10) or (y(x + 1, row) shl 20))
                    memory.putInt(tile + 4, y(x, row + 1) or (cr(x / 2, row / 2) shl 10) or (y(x + 1, row + 1) shl 20))
                } else {
                    for (dy in 0..1) for (dx in 0..1) memory.put((row + dy) * stride + x + dx, (y(x + dx, row + dy) ushr 2).toByte())
                    memory.put(uv + row / 2 * stride + x, (cb(x / 2, row / 2) ushr 2).toByte())
                    memory.put(uv + row / 2 * stride + x + 1, (cr(x / 2, row / 2) ushr 2).toByte())
                }
            }
            val source = MvePcmTileSource(width, height, stride, packed, 0L, uv.toLong(), ByteBuffer.allocate(2048)) { addr, dst ->
                dst.put(memory.array(), addr.toInt(), dst.remaining())
                true
            }
            val pixels = ShortArray(384)
            val detailed = ShortArray(384)
            val mask = if (packed) 1023 else 1020
            for (my in 0..1) for (mx in 0..2) {
                assertTrue(source.readBlock(mx, my, pixels))
                val coarse = ByteArray(16) { cell -> (pixels[(cell / 4 * 4 + 2) * 16 + cell % 4 * 4 + 2].toInt() ushr 2).toByte() }
                val hasDetail = AvcReferenceFrame.hasFineDetail(pixels, coarse, 0, 4)
                assertEquals(hasDetail, source.readDetailedBlock(mx, my, detailed, coarse, 0, 4))
                if (hasDetail) assertContentEquals(pixels, detailed)
                for (dy in 0 until 16) for (dx in 0 until 16) {
                    assertEquals(y(minOf(mx * 16 + dx, width - 1), minOf(my * 16 + dy, height - 1)) and mask,
                        pixels[dy * 16 + dx].toInt())
                }
                for (dy in 0 until 8) for (dx in 0 until 8) {
                    val cx = minOf(mx * 8 + dx, width / 2 - 1)
                    val cy = minOf(my * 8 + dy, height / 2 - 1)
                    assertEquals(cb(cx, cy) and mask, pixels[256 + dy * 8 + dx].toInt())
                    assertEquals(cr(cx, cy) and mask, pixels[320 + dy * 8 + dx].toInt())
                }
            }
        }
    }
}
