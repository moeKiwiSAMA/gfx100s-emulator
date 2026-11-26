package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs one RECC_S destination strip into the 10-bit 2x2 Y0L2 layout consumed by RESZ. */
internal class ReccMovieTiles {
    private var xOffsets = IntArray(0)
    private var mapSourceWidth = 0
    private var rowBytes = ByteArray(0)

    fun write(
        memory: DmaMemory, base: Long, width: Int, frameHeight: Int, firstRow: Int, rows: Int, stride: Int,
        source: ByteArray, sourceW: Int, sourceH: Int, extendBottom: Boolean = false,
    ): Boolean {
        if (width !in 2..16384 || width % 2 != 0 || firstRow < 0 || firstRow % 2 != 0 ||
            frameHeight !in 2..16384 || rows !in 1..frameHeight || firstRow > frameHeight - rows ||
            stride !in width * 4..0x3ffff || sourceW < 2 || sourceW % 2 != 0 || sourceH < 1 ||
            source.size.toLong() < 2L * sourceW * sourceH) return false
        if (extendBottom && firstRow + rows != frameHeight) return false
        // The firmware programs RESZ to consume the complete 16-line surface envelope.
        val outputRows = if (extendBottom) ((frameHeight + 15) and -16) - firstRow else rows
        val rowSize = width * 4
        val start = base + (firstRow / 2L) * stride
        val span = ((outputRows - 1L) / 2) * stride + rowSize
        if (base <= 0L || start !in 0L..(1L shl 34) - span) return false
        try {
            memory.check(start, span, true)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (xOffsets.size != width || mapSourceWidth != sourceW) {
            xOffsets = IntArray(width) { x -> (x.toLong() * sourceW / width).toInt() * 2 }
            mapSourceWidth = sourceW
        }
        if (rowBytes.size < rowSize) rowBytes = ByteArray(rowSize)
        val row = ByteBuffer.wrap(rowBytes, 0, rowSize).order(ByteOrder.LITTLE_ENDIAN)
        val direct = (memory as? DirectGuestRamPort)?.sliceDirect(start, span, write = true)
        var previousTop = -1
        var previousBottom = -1
        for (y in 0 until outputRows step 2) {
            val top = (minOf(firstRow + y, firstRow + rows - 1).toLong() * sourceH / frameHeight).toInt() * sourceW * 2
            val bottom = (minOf(firstRow + y + 1, firstRow + rows - 1).toLong() * sourceH / frameHeight).toInt() * sourceW * 2
            if (top != previousTop || bottom != previousBottom) {
                row.rewind()
                for (x in 0 until width step 2) {
                    val left = xOffsets[x]
                    val right = xOffsets[x + 1]
                    val pair = left and -4
                    val cb = ((source[top + pair].toInt() and 255) + (source[bottom + pair].toInt() and 255) + 1) / 2
                    val cr = ((source[top + pair + 2].toInt() and 255) + (source[bottom + pair + 2].toInt() and 255) + 1) / 2
                    row.putInt(pack(source[top + left + 1], cb, source[top + right + 1]))
                    row.putInt(pack(source[bottom + left + 1], cr, source[bottom + right + 1]))
                }
                previousTop = top
                previousBottom = bottom
            }
            val offset = (y / 2L) * stride
            if (direct != null) {
                MemorySegment.copy(rowBytes, 0, direct, ValueLayout.JAVA_BYTE, offset, rowSize)
            } else {
                row.rewind()
                memory.write(start + offset, row)
            }
        }
        if (direct != null) memory.notifyDirectWrite(start, span)
        return true
    }

    private fun pack(left: Byte, chroma: Int, right: Byte): Int =
        ((left.toInt() and 255) shl 2) or (chroma shl 12) or ((right.toInt() and 255) shl 22)
}
