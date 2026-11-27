package com.github.nyabanban.fuji.emulator.components.isp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unquantized 4:2:0 samples in Y, Cb, Cr order; each value uses the native ten-bit scale. */
internal fun interface MvePcmSource {
    fun readBlock(mbX: Int, mbY: Int, pixels: ShortArray): Boolean

    /** Return a complete block only when its luminance differs from the coarse 4×4 estimates. */
    fun readDetailedBlock(mbX: Int, mbY: Int, pixels: ShortArray, coarse: ByteArray, first: Int, coarseStride: Int): Boolean =
        readBlock(mbX, mbY, pixels) && AvcReferenceFrame.hasFineDetail(pixels, coarse, first, coarseStride)
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

    private fun rowAvailable(mbY: Int): Boolean {
        if (mbY != cachedRow) {
            cachedRow = mbY
            valid = load(mbY)
        }
        return valid
    }

    override fun readDetailedBlock(mbX: Int, mbY: Int, pixels: ShortArray, coarse: ByteArray, first: Int, coarseStride: Int): Boolean {
        if (!packed || mbX * 16 + 16 > width || mbY * 16 + 16 > height) {
            return super.readDetailedBlock(mbX, mbY, pixels, coarse, first, coarseStride)
        }
        if (!rowAvailable(mbY)) return false
        // Check luma directly in the Y0L2 strip before unpacking and copying any chroma.
        for (by in 0 until 4) for (bx in 0 until 4) {
            val estimate = coarse[first + by * coarseStride + bx].toInt() and 255
            val lower = (estimate - 3) * 4
            val start = by * 2 * rowBytes + mbX * 64 + bx * 16
            for (pairRow in 0 until 2) {
                val offset = start + pairRow * rowBytes
                for (i in 0 until 4) {
                    val pair = strip.getInt(offset + i * 4)
                    if ((pair and 1023) - lower !in 0 until 28 || ((pair ushr 20) and 1023) - lower !in 0 until 28) {
                        return readBlock(mbX, mbY, pixels)
                    }
                }
            }
        }
        return false
    }

    override fun readBlock(mbX: Int, mbY: Int, pixels: ShortArray): Boolean {
        if (!rowAvailable(mbY)) return false
        val x0 = mbX * 16
        val lastY = height - 1 - mbY * 16
        val cols = minOf(16, width - x0)
        if (packed && cols == 16 && lastY >= 15) {
            // Each Y0L2 tile holds both rows of luma as well as the two chroma samples.
            for (y in 0 until 8) for (x in 0 until 8) {
                val tile = y * rowBytes + (x0 / 2 + x) * 8
                val top = strip.getInt(tile)
                val bottom = strip.getInt(tile + 4)
                val luma = y * 32 + x * 2
                pixels[luma] = (top and 1023).toShort()
                pixels[luma + 1] = ((top ushr 20) and 1023).toShort()
                pixels[luma + 16] = (bottom and 1023).toShort()
                pixels[luma + 17] = ((bottom ushr 20) and 1023).toShort()
                pixels[256 + y * 8 + x] = ((top ushr 10) and 1023).toShort()
                pixels[320 + y * 8 + x] = ((bottom ushr 10) and 1023).toShort()
            }
            return true
        }
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
