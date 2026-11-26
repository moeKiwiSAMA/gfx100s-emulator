package com.github.nyabanban.fuji.emulator.components.isp

/** Unfiltered 8x8 chroma borders for intra prediction next to original-sample (I_PCM) blocks. */
internal class AvcChromaPrediction(columns: Int) {
    private val top = ByteArray(columns * 16)
    private val left = ByteArray(16)

    fun mode(mx: Int, my: Int): Int {
        if (mx == 0) return if (my == 0) 0 else 2
        if (my == 0) return 1
        fun variation(edge: ByteArray, start: Int): Int {
            var total = 0
            for (plane in 0..1) for (i in 1..7) {
                val j = start + plane * 8 + i
                total += kotlin.math.abs(edge[j].toUByte().toInt() - edge[j - 1].toUByte().toInt())
            }
            return total
        }
        return if (variation(left, 0) <= variation(top, mx * 16)) 1 else 2
    }

    fun mean(mx: Int, plane: Int, mode: Int): Int {
        if (mode == 0) return 128
        val edge = if (mode == 1) left else top
        val start = (if (mode == 1) 0 else mx * 16) + plane * 8
        var sum = 0
        for (i in start until start + 8) sum += edge[i].toUByte().toInt()
        return (sum + 4) / 8
    }

    fun reconstruct(mx: Int, plane: Int, mode: Int, residual: Int) {
        val base = mx * 16 + plane * 8
        val side = plane * 8
        for (i in 0 until 8) {
            val prediction = when (mode) {
                1 -> left[side + i].toUByte().toInt()
                2 -> top[base + i].toUByte().toInt()
                else -> 128
            }
            val value = (prediction + residual).coerceIn(0, 255).toByte()
            if (mode == 1) left[side + i] = value else top[base + i] = value
        }
        if (mode == 1) top.fill(left[side + 7], base, base + 8)
        else left.fill(top[base + 7], side, side + 8)
    }

    fun raw(mx: Int, pixels: ShortArray) {
        for (plane in 0..1) for (i in 0 until 8) {
            val src = 256 + plane * 64
            top[mx * 16 + plane * 8 + i] = (pixels[src + 56 + i].toInt() ushr 2).toByte()
            left[plane * 8 + i] = (pixels[src + i * 8 + 7].toInt() ushr 2).toByte()
        }
    }

    fun save(mx: Int, block: Int, ref: AvcReferenceFrame?) {
        val dest = ref?.chromaEdges ?: return
        top.copyInto(dest, block * 32, mx * 16, mx * 16 + 16)
        left.copyInto(dest, block * 32 + 16)
    }

    fun restore(mx: Int, block: Int, ref: AvcReferenceFrame) {
        val src = ref.chromaEdges
        val base = mx * 16
        if (src != null) {
            src.copyInto(top, base, block * 32, block * 32 + 16)
            src.copyInto(left, 0, block * 32 + 16, block * 32 + 32)
        } else {
            for (plane in 0..1) {
                val value = ref.edges[block * 10 + 8 + plane]
                top.fill(value, base + plane * 8, base + plane * 8 + 8)
                left.fill(value, plane * 8, plane * 8 + 8)
            }
        }
    }
}
