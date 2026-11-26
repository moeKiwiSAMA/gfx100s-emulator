package com.github.nyabanban.fuji.emulator.components.isp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MvePcmTileSourceTest {
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
            val mask = if (packed) 1023 else 1020
            for (my in 0..1) for (mx in 0..2) {
                assertTrue(source.readBlock(mx, my, pixels))
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
