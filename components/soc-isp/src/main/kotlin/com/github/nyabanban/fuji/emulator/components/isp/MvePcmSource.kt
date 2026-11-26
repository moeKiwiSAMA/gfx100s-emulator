package com.github.nyabanban.fuji.emulator.components.isp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unquantized 4:2:0 samples in Y, Cb, Cr order; each value uses the native ten-bit scale. */
internal fun interface MvePcmSource {
    fun readBlock(mbX: Int, mbY: Int, pixels: ShortArray): Boolean
}

/** Reads a strip once for all requested macroblocks in that row, excluding guest stride padding. */
internal class MvePcmTileSource(
    private val width: Int, private val height: Int, private val stride: Int, private val packed: Boolean,
    private val yBase: Long, private val uvBase: Long, private val strip: ByteBuffer,
    private val read: (Long, ByteBuffer) -> Boolean,
) : MvePcmSource {
    private val rowBytes = if (packed) ((width + 1) / 2) * 8 else width
    private val uvBytes = ((width + 1) / 2) * 2
    private var cachedRow = -1
    private var valid = false

    init { strip.order(ByteOrder.LITTLE_ENDIAN) }

    private fun load(mbY: Int): Boolean {
        val start = mbY * 16
        val heightHere = minOf(height - start, 16)
        val chromaRows = (heightHere + 1) / 2
        val yRows = if (packed) chromaRows else heightHere
        val firstRow = if (packed) start / 2 else start
        fun row(address: Long, offset: Int, length: Int): Boolean {
            strip.clear().position(offset).limit(offset + length)
            return read(address, strip)
        }
        for (i in 0 until yRows) {
            if (!row(yBase + (firstRow + i).toLong() * stride, i * rowBytes, rowBytes)) return false
        }
        if (!packed && uvBase != 0L) {
            for (i in 0 until chromaRows) {
                if (!row(uvBase + (start / 2 + i).toLong() * stride, 16 * rowBytes + i * uvBytes, uvBytes)) return false
            }
        }
        strip.clear()
        return true
    }

    override fun readBlock(mbX: Int, mbY: Int, pixels: ShortArray): Boolean {
        if (mbY != cachedRow) {
            cachedRow = mbY
            valid = load(mbY)
        }
        if (!valid) return false
        val x0 = mbX * 16
        val lastY = height - 1 - mbY * 16
        val cols = minOf(16, width - x0)
        for (dy in 0 until 16) {
            val sy = minOf(dy, lastY)
            val dest = dy * 16
            if (packed) {
                val start = (sy / 2) * rowBytes + (x0 / 2) * 8 + (sy and 1) * 4
                for (dx in 0 until cols step 2) {
                    val pair = strip.getInt(start + dx * 4)
                    pixels[dest + dx] = (pair and 1023).toShort()
                    pixels[dest + dx + 1] = ((pair ushr 20) and 1023).toShort()
                }
            } else {
                val start = sy * rowBytes + x0
                for (dx in 0 until cols) pixels[dest + dx] = ((strip.get(start + dx).toInt() and 255) shl 2).toShort()
            }
            if (cols < 16) pixels.fill(pixels[dest + cols - 1], dest + cols, dest + 16)
        }
        for (dy in 0 until 8) for (dx in 0 until 8) {
            val sy = minOf(dy, lastY / 2)
            val sx = minOf(x0 / 2 + dx, (width - 1) / 2)
            val i = dy * 8 + dx
            if (packed) {
                val tile = sy * rowBytes + sx * 8
                pixels[256 + i] = ((strip.getInt(tile) ushr 10) and 1023).toShort()
                pixels[320 + i] = ((strip.getInt(tile + 4) ushr 10) and 1023).toShort()
            } else if (uvBase != 0L) {
                val uv = 16 * rowBytes + sy * uvBytes + sx * 2
                pixels[256 + i] = ((strip.get(uv).toInt() and 255) shl 2).toShort()
                pixels[320 + i] = ((strip.get(uv + 1).toInt() and 255) shl 2).toShort()
            } else {
                pixels[256 + i] = 512
                pixels[320 + i] = 512
            }
        }
        return true
    }
}
