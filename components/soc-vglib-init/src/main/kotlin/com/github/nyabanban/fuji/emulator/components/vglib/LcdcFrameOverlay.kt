package com.github.nyabanban.fuji.emulator.components.vglib

import java.nio.IntBuffer

/** LCDC window generator, independent of OSD0 framebuffer DMA. */
internal object LcdcFrameOverlay {
    const val ENABLE = 0x1c4
    const val WINDOW_MASK = 0x003fffff

    fun paint(words: LongArray, canvasWidth: Int, canvasHeight: Int, width: Int, height: Int,
              matrix: IntArray, matrixMode: Int, pixels: IntBuffer) {
        var enabled = words[ENABLE / 4].toInt() and WINDOW_MASK
        while (enabled != 0) {
            val slot = Integer.numberOfTrailingZeros(enabled)
            enabled = enabled and (enabled - 1)
            // S4 0x0126B5F0..0x0126B630 writes 20 endpoint pairs; slots 20 and 21
            // have a third word carrying horizontal and vertical corner lengths.
            val offset = if (slot < 20) 0x208 + slot * 8 else 0x2a8 + (slot - 20) * 12
            val start = words[offset / 4].toInt()
            val end = words[offset / 4 + 1].toInt()
            val x0 = start and 0x1fff
            val y0 = (start ushr 16) and 0x1fff
            val x1 = end and 0x1fff
            val y1 = (end ushr 16) and 0x1fff
            if (x1 < x0 || y1 < y0 || x0 >= canvasWidth || y0 >= canvasHeight) continue
            val corner = if (slot >= 20) words[offset / 4 + 2].toInt() else -1
            val armX = (corner and 0x1fff).coerceAtMost(x1 - x0 + 1)
            val armY = ((corner ushr 16) and 0x1fff).coerceAtMost(y1 - y0 + 1)
            val palette = (words[0x1f0 / 4 + slot / 8].toInt() ushr (slot % 8 * 4)) and 3
            val alphaBank = (words[0x200 / 4 + slot / 16].toInt() ushr (slot % 16 * 2)) and 1
            val alpha = (words[0x1fc / 4].toInt() ushr (8 * alphaBank)) and 255
            if (alpha == 0) continue
            val rawColor = LcdcFirstScanout.decodeBgColor(words[0x1d0 / 4 + palette])
            val color = if (matrixMode == 0) rawColor else {
                val r = rawColor ushr 16 and 255
                val g = rawColor ushr 8 and 255
                val b = rawColor and 255
                fun channel(i: Int) = ((matrix[i] * r + matrix[i + 1] * g + matrix[i + 2] * b + 512) shr 10).coerceIn(0, 255)
                (255 shl 24) or (channel(0) shl 16) or (channel(3) shl 8) or channel(6)
            }
            fun line(y: Int, left: Int, right: Int) {
                if (right < left) return
                val xStart = pixelBoundary(left, width, canvasWidth)
                val xEnd = pixelBoundary(right + 1, width, canvasWidth)
                val yStart = pixelBoundary(y, height, canvasHeight)
                val yEnd = pixelBoundary(y + 1, height, canvasHeight)
                for (row in yStart until yEnd) {
                    for (i in row * width + xStart until row * width + xEnd) {
                        pixels.put(i, if (alpha == 255) color else blend(pixels.get(i), color, alpha))
                    }
                }
            }
            for (y in y0..minOf(y1, canvasHeight - 1)) {
                if (y == y0 || y == y1) {
                    if (armX * 2 >= x1 - x0 + 1) line(y, x0, x1) else {
                        line(y, x0, x0 + armX - 1)
                        line(y, x1 - armX + 1, x1)
                    }
                } else if (y < y0 + armY || y > y1 - armY) {
                    line(y, x0, x0)
                    if (x1 != x0) line(y, x1, x1)
                }
            }
        }
    }

    private fun pixelBoundary(n: Int, target: Int, source: Int) =
        ((n * 2 * target + source - 1) / (2 * source)).coerceIn(0, target)

    private fun blend(background: Int, foreground: Int, alpha: Int): Int {
        fun channel(shift: Int) = (((foreground ushr shift and 255) * alpha +
            (background ushr shift and 255) * (255 - alpha) + 127) / 255)
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
