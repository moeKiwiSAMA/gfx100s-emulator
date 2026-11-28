package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The single-source RETCH route used by the movie recorder before its thumbnail JPEG submission. */
internal object RetchResampler {
    fun execute(words: LongArray, memory: DmaMemory?): Boolean {
        fun reg(offset: Int) = words[offset / 4]
        val format = reg(0x08)
        val tiled = format == 0x0003_0000L
        if (memory == null || (!tiled && format != 0L) || reg(0x50) != 0L) return false
        val src = ((reg(0x14) and 3L) shl 32) or (reg(0x10) and 0xFFFF_FFFFL)
        val dst = ((reg(0x34) and 3L) shl 32) or (reg(0x30) and 0xFFFF_FFFFL)
        val srcW = (reg(0x24).toInt() and 0x3fff)
        val srcH = (reg(0x24).toInt() ushr 16) and 0x3fff
        val dstW = (reg(0x44).toInt() and 0x3fff)
        val dstH = (reg(0x44).toInt() ushr 16) and 0x3fff
        val srcStride = reg(0x20).toInt() and 0xffff
        val dstStride = reg(0x40).toInt() and 0xffff
        val srcRowBytes = ((srcW + 1) / 2) * if (tiled) 8 else 4
        val dstRowBytes = dstW * 2
        if (src == 0L || dst == 0L || srcW == 0 || srcH == 0 || dstW == 0 || dstH == 0 ||
            dstW % 2 != 0 || srcStride < srcRowBytes || dstStride < dstRowBytes || reg(0x48) != 0L) return false
        val stepX = reg(0x5c)
        val stepY = reg(0x70)
        if (stepX == 0L || stepY == 0L) return false

        val srcRows = if (tiled) (srcH + 1) / 2 else srcH
        val srcSpan = (srcRows - 1L) * srcStride + srcRowBytes
        val dstSpan = (dstH - 1L) * dstStride + dstRowBytes
        try {
            memory.check(src, srcSpan, false)
            memory.check(dst, dstSpan, true)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val direct = memory as? DirectGuestRamPort
        val mappedSrc = direct?.sliceDirect(src, srcSpan, false)?.asByteBuffer()
        val mappedDst = direct?.sliceDirect(dst, dstSpan, true)?.asByteBuffer()
        val sourceRows = Array(2) { ByteBuffer.allocate(srcRowBytes).order(ByteOrder.LITTLE_ENDIAN) }
        val cachedRows = IntArray(2) { -1 }
        fun row(y: Int): ByteBuffer {
            val physical = if (tiled) y / 2 else y
            val slot = physical and 1
            val data = sourceRows[slot]
            if (cachedRows[slot] != physical) {
                data.clear()
                if (mappedSrc != null) {
                    data.put(0, mappedSrc, physical * srcStride, srcRowBytes)
                } else {
                    memory.read(src + physical.toLong() * srcStride, data)
                }
                data.clear()
                cachedRows[slot] = physical
            }
            return data
        }
        fun sample(data: ByteBuffer, x: Int, y: Int, channel: Int): Int {
            if (tiled) {
                val tile = x / 2 * 8
                return when (channel) {
                    0 -> (data.getInt(tile + (y and 1) * 4) ushr (2 + (x and 1) * 20)) and 255
                    1 -> (data.getInt(tile) ushr 12) and 255
                    else -> (data.getInt(tile + 4) ushr 12) and 255
                }
            }
            val pair = (x / 2) * 4
            // Real-silicon Milbeaut packed 4:2:2 layout is YYUV422: [Y0, Y1, Cb, Cr].
            val offset = when (channel) {
                0 -> x and 1
                1 -> 2
                else -> 3
            }
            return data.get(pair + offset).toInt() and 255
        }
        // Geometry registers hold counts. Origins are Q16 pixel centres, including the
        // half-pixel phase on the cascaded 2:1 passes and quarter-pixel phase on the 3:2 pass.
        val lastX = srcW - 1
        val lastY = srcH - 1
        val xQ16 = LongArray(dstW) { minOf(reg(0x68) + it * stepX, lastX.toLong() shl 16) }
        val rowOut = ByteBuffer.allocate(dstRowBytes)
        val previousTop = ByteBuffer.allocate(srcRowBytes)
        val previousBottom = ByteBuffer.allocate(srcRowBytes)
        var previousPhase = -1
        fun mix(a: Int, b: Int, fraction: Int) = a + (((b - a) * fraction + 128) shr 8)
        for (y in 0 until dstH) {
            val sy = minOf(reg(0x78) + y * stepY, lastY.toLong() shl 16)
            val top = (sy ushr 16).toInt()
            val bottom = minOf(top + 1, lastY)
            val fy = ((sy ushr 8) and 255).toInt()
            val a = row(top)
            val b = row(bottom)
            fun filtered(x: Int, channel: Int): Int {
                val sx = xQ16[x]
                val left = (sx ushr 16).toInt()
                val right = minOf(left + 1, lastX)
                val fx = ((sx ushr 8) and 255).toInt()
                val p = mix(sample(a, left, top, channel), sample(a, right, top, channel), fx)
                val q = mix(sample(b, left, bottom, channel), sample(b, right, bottom, channel), fx)
                return mix(p, q, fy)
            }
            // Identical physical source rows and vertical phase produce the same horizontal
            // filtering. Compare all active bytes, including chroma, before reusing a result.
            val phase = if (tiled) fy or ((top and 1) shl 8) or ((bottom and 1) shl 9) else fy
            if (phase != previousPhase || a.mismatch(previousTop) != -1 || b.mismatch(previousBottom) != -1) {
                previousTop.put(0, a, 0, srcRowBytes)
                previousBottom.put(0, b, 0, srcRowBytes)
                previousPhase = phase
                rowOut.clear()
                for (x in 0 until dstW step 2) {
                    rowOut.put(filtered(x, 0).toByte())
                    rowOut.put(filtered(x + 1, 0).toByte())
                    rowOut.put(((filtered(x, 1) + filtered(x + 1, 1) + 1) / 2).toByte())
                    rowOut.put(((filtered(x, 2) + filtered(x + 1, 2) + 1) / 2).toByte())
                }
            }
            rowOut.clear()
            if (mappedDst != null) {
                mappedDst.put(y * dstStride, rowOut, 0, dstRowBytes)
            } else {
                memory.write(dst + y.toLong() * dstStride, rowOut)
            }
        }
        if (mappedDst != null) direct.notifyDirectWrite(dst, dstSpan)
        return true
    }
}
