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
    private val totalMacroblocks = macroblockColumns * ((height + 15) / 16)
    // Bottom and right 4x4 luma borders, then Cb and Cr for the next coded macroblock.
    val edges = ByteArray(if (keepReference) totalMacroblocks * 10 else 0)
    val chromaEdges = if (keepReference && trackChroma) ByteArray(totalMacroblocks * 32) else null
    // Flat slab storing 384-byte 8-bit I_PCM macroblocks without per-MB ByteArray allocations.
    private val pcmOffsets = if (keepReference && trackChroma) IntArray(totalMacroblocks) { -1 } else null
    private var pcmSlab = ByteArray(0)
    private var pcmSlabUsed = 0

    fun hasPcmBlock(block: Int): Boolean = pcmOffsets?.get(block)?.let { it >= 0 } == true

    internal fun copyPcmBlockBytes(block: Int): ByteArray? {
        val off = pcmOffsets?.get(block) ?: return null
        if (off < 0) return null
        return pcmSlab.copyOfRange(off, off + PCM_BLOCK_BYTES)
    }

    fun matchesPcmBlock(block: Int, pixels: ShortArray): Boolean {
        val off = pcmOffsets?.get(block) ?: return false
        if (off < 0) return false
        val slab = pcmSlab
        for (i in 0 until PCM_BLOCK_BYTES) {
            if ((pixels[i].toInt() ushr 2).toByte() != slab[off + i]) return false
        }
        return true
    }

    fun copyPcmBlockFrom(block: Int, source: AvcReferenceFrame) {
        val dstOffsets = pcmOffsets ?: return
        val srcOff = source.pcmOffsets?.get(block) ?: return
        if (srcOff < 0) return
        val dstOff = allocatePcmSlot()
        System.arraycopy(source.pcmSlab, srcOff, pcmSlab, dstOff, PCM_BLOCK_BYTES)
        dstOffsets[block] = dstOff
    }

    fun storePcmBlock(block: Int, pixels: ShortArray) {
        val dstOffsets = pcmOffsets ?: return
        val dstOff = allocatePcmSlot()
        val slab = pcmSlab
        for (i in 0 until PCM_BLOCK_BYTES) {
            slab[dstOff + i] = (pixels[i].toInt() ushr 2).toByte()
        }
        dstOffsets[block] = dstOff
    }

    private fun allocatePcmSlot(): Int {
        val needed = pcmSlabUsed + PCM_BLOCK_BYTES
        if (needed > pcmSlab.size) {
            val nextCap = maxOf(needed, maxOf(pcmSlab.size * 2, PCM_BLOCK_BYTES * 64))
            pcmSlab = pcmSlab.copyOf(nextCap)
        }
        val off = pcmSlabUsed
        pcmSlabUsed = needed
        return off
    }

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
        const val PCM_BLOCK_BYTES = 384

        fun matchesPcm(pixels: ShortArray, reference: ByteArray): Boolean {
            for (i in pixels.indices) if ((pixels[i].toInt() ushr 2).toByte() != reference[i]) return false
            return true
        }

        fun hasFineDetail(pixels: ShortArray, coarse: ByteArray, first: Int, stride: Int): Boolean {
            for (blockY in 0 until 4) for (blockX in 0 until 4) {
                // The seven acceptable 8-bit values (estimate ± 3) span 28 consecutive 10-bit values.
                val estimate = coarse[first + blockY * stride + blockX].toInt() and 255
                val lower = (estimate - 3) * 4
                for (row in 0 until 4) {
                    val offset = (blockY * 4 + row) * 16 + blockX * 4
                    for (x in 0 until 4) {
                        val delta = (pixels[offset + x].toInt() and 0xffff) - lower
                        if (delta !in 0 until 28) return true
                    }
                }
            }
            return false
        }
    }
}
