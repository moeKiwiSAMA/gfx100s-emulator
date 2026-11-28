package com.github.nyabanban.fuji.emulator.components.isp

/** Unfiltered 8x8 chroma borders for intra prediction next to original-sample (I_PCM) blocks. */
internal class AvcChromaPrediction(columns: Int) {
    private val top = ByteArray(columns * 16)
    private val left = ByteArray(16)

    private fun variation(edge: ByteArray, start: Int): Int {
        var total = 0
        var prev = edge[start].toInt() and 0xFF
        for (i in 1..7) {
            val cur = edge[start + i].toInt() and 0xFF
            val d = cur - prev
            total += if (d >= 0) d else -d
            prev = cur
        }
        val start1 = start + 8
        prev = edge[start1].toInt() and 0xFF
        for (i in 1..7) {
            val cur = edge[start1 + i].toInt() and 0xFF
            val d = cur - prev
            total += if (d >= 0) d else -d
            prev = cur
        }
        return total
    }

    fun mode(mx: Int, my: Int): Int {
        if (mx == 0) return if (my == 0) 0 else 2
        if (my == 0) return 1
        return if (variation(left, 0) <= variation(top, mx shl 4)) 1 else 2
    }

    fun mean(mx: Int, plane: Int, mode: Int): Int {
        if (mode == 0) return 128
        val edge = if (mode == 1) left else top
        val start = (if (mode == 1) 0 else mx shl 4) + (plane shl 3)
        val sum = (edge[start].toInt() and 0xFF) +
            (edge[start + 1].toInt() and 0xFF) +
            (edge[start + 2].toInt() and 0xFF) +
            (edge[start + 3].toInt() and 0xFF) +
            (edge[start + 4].toInt() and 0xFF) +
            (edge[start + 5].toInt() and 0xFF) +
            (edge[start + 6].toInt() and 0xFF) +
            (edge[start + 7].toInt() and 0xFF)
        return (sum + 4) ushr 3
    }

    fun reconstruct(mx: Int, plane: Int, mode: Int, residual: Int) {
        val base = (mx shl 4) + (plane shl 3)
        val side = plane shl 3
        if (mode == 1) {
            for (i in 0 until 8) {
                val v = ((left[side + i].toInt() and 0xFF) + residual).coerceIn(0, 255).toByte()
                left[side + i] = v
            }
            top.fill(left[side + 7], base, base + 8)
        } else if (mode == 2) {
            for (i in 0 until 8) {
                val v = ((top[base + i].toInt() and 0xFF) + residual).coerceIn(0, 255).toByte()
                top[base + i] = v
            }
            left.fill(top[base + 7], side, side + 8)
        } else {
            val v = (128 + residual).coerceIn(0, 255).toByte()
            top.fill(v, base, base + 8)
            left.fill(v, side, side + 8)
        }
    }

    fun raw(mx: Int, pixels: ShortArray) {
        val topBase0 = mx shl 4
        val topBase1 = topBase0 + 8
        for (i in 0 until 8) {
            top[topBase0 + i] = (pixels[312 + i].toInt() ushr 2).toByte()
            left[i] = (pixels[256 + (i shl 3) + 7].toInt() ushr 2).toByte()
            top[topBase1 + i] = (pixels[376 + i].toInt() ushr 2).toByte()
            left[8 + i] = (pixels[320 + (i shl 3) + 7].toInt() ushr 2).toByte()
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
