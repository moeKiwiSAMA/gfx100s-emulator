package com.github.nyabanban.fuji.emulator.components.cmos

import java.lang.invoke.MethodHandles
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immediate, black-level-relative exposure adjustment for the owner thread's published Bayer frame. */
internal class ExposureBayerScaler {
    private val table = ShortArray(1 shl 16)
    private var lastRatio = Double.NaN
    private var lastBlack = -1
    private var lastDepth = -1
    private var cachedScaled = ByteArray(0)
    private var cachedGen = -1L
    private var cachedSize = -1
    private var cachedRatio = Double.NaN
    private var cachedBlack = -1
    private var cachedDepth = -1

    fun copy(
        src: ByteArray,
        dst: ByteBuffer,
        ratio: Double,
        blackLevel: Int,
        bitDepth: Int,
        sourceGeneration: Long = -1L,
    ) {
        if (dst.remaining() < src.size) throw BufferOverflowException()
        val depth = bitDepth.coerceIn(8, 16)
        val maxAdu = (1 shl depth) - 1
        val black = blackLevel.coerceIn(0, maxAdu)
        if (ratio == 1.0 && depth == 16 && black == 0) {
            dst.put(src)
            return
        }
        if (sourceGeneration >= 0L &&
            sourceGeneration == cachedGen &&
            src.size == cachedSize &&
            ratio == cachedRatio &&
            black == cachedBlack &&
            depth == cachedDepth
        ) {
            dst.put(cachedScaled, 0, src.size)
            return
        }
        if (ratio != lastRatio || black != lastBlack || depth != lastDepth) {
            val sampleCount = if (depth == 8) 256 else table.size
            if (ratio in 0.0..256.0) {
                val scale = (ratio * 65536.0 + 0.5).toLong()
                for (sample in 0 until sampleCount) {
                    val scaled = black + (((sample - black) * scale + 32768L) shr 16).toInt()
                    table[sample] = scaled.coerceIn(0, maxAdu).toShort()
                }
            } else {
                for (sample in 0 until sampleCount) {
                    val scaled = (black + (sample - black) * ratio + 0.5).toInt()
                    table[sample] = scaled.coerceIn(0, maxAdu).toShort()
                }
            }
            lastRatio = ratio
            lastBlack = black
            lastDepth = depth
        }

        if (sourceGeneration >= 0L) {
            if (cachedScaled.size < src.size) cachedScaled = ByteArray(src.size)
            val out = cachedScaled
            if (depth == 8) {
                for (i in src.indices) out[i] = table[src[i].toInt() and 0xff].toByte()
            } else {
                var index = 0
                while (index < src.size) {
                    val sample = ARRAY_SHORT.get(src, index) as Short
                    ARRAY_SHORT.set(out, index, table[sample.toInt() and 0xffff])
                    index += 2
                }
            }
            cachedGen = sourceGeneration
            cachedSize = src.size
            cachedRatio = ratio
            cachedBlack = black
            cachedDepth = depth
            dst.put(out, 0, src.size)
            return
        }

        if (depth == 8) {
            for (sample in src) dst.put(table[sample.toInt() and 0xff].toByte())
            return
        }

        val start = dst.position()
        if (dst.hasArray()) {
            val out = dst.array()
            val offset = dst.arrayOffset() + start
            var index = 0
            while (index < src.size) {
                val sample = ARRAY_SHORT.get(src, index) as Short
                ARRAY_SHORT.set(out, offset + index, table[sample.toInt() and 0xffff])
                index += 2
            }
        } else {
            val out = dst.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until src.size / 2) {
                val sample = ARRAY_SHORT.get(src, i * 2) as Short
                out.put(i, table[sample.toInt() and 0xffff])
            }
        }
        dst.position(start + src.size)
    }

    private companion object {
        val ARRAY_SHORT = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
    }
}
