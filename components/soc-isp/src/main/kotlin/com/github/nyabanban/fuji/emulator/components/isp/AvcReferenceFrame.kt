package com.github.nyabanban.fuji.emulator.components.isp

/** Encoder reference surface reduced to the samples and prediction edges used by the AVC model. */
internal class AvcReferenceFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray?,
    val cb: ByteArray?,
    val cr: ByteArray?,
    val detail: ByteArray?,
    keepReference: Boolean = true,
    trackChroma: Boolean = false,
) {
    val macroblockColumns = (width + 15) / 16
    // Bottom and right 4x4 luma borders, then Cb and Cr for the next coded macroblock.
    val edges = ByteArray(if (keepReference) macroblockColumns * ((height + 15) / 16) * 10 else 0)
    val chromaEdges = if (keepReference && trackChroma) ByteArray(macroblockColumns * ((height + 15) / 16) * 32) else null
    val pcmBlocks = if (keepReference && trackChroma) arrayOfNulls<ByteArray>(macroblockColumns * ((height + 15) / 16)) else null

    fun saveEdges(block: Int, above: IntArray, firstCol: Int, left: IntArray, cb: Int, cr: Int) {
        if (edges.isEmpty()) return
        val edge = block * 10
        for (i in 0 until 4) {
            edges[edge + i] = above[firstCol + i].toByte()
            edges[edge + 4 + i] = left[i].toByte()
        }
        edges[edge + 8] = cb.toByte()
        edges[edge + 9] = cr.toByte()
    }

    fun matches(previous: AvcReferenceFrame, block: Int, firstDetail: Int): Boolean {
        if (y?.get(block) != previous.y?.get(block) ||
            cb?.get(block) != previous.cb?.get(block) || cr?.get(block) != previous.cr?.get(block)) return false
        val source = detail ?: return previous.detail == null
        val reference = previous.detail ?: return false
        val stride = macroblockColumns * 4
        for (row in 0 until 4) {
            val offset = firstDetail + row * stride
            for (col in 0 until 4) if (source[offset + col] != reference[offset + col]) return false
        }
        return true
    }

    companion object {
        fun matchesPcm(pixels: ShortArray, reference: ByteArray): Boolean {
            for (i in pixels.indices) if ((pixels[i].toInt() ushr 2).toByte() != reference[i]) return false
            return true
        }

        fun hasFineDetail(pixels: ShortArray, coarse: ByteArray, first: Int, stride: Int): Boolean {
            for (y in 0 until 16) for (x in 0 until 16) {
                val estimate = coarse[first + (y / 4) * stride + x / 4].toUByte().toInt()
                if (kotlin.math.abs((pixels[y * 16 + x].toInt() ushr 2) - estimate) >= 4) return true
            }
            return false
        }
    }
}
