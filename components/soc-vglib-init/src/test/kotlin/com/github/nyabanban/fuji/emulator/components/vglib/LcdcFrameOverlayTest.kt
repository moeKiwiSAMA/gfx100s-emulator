package com.github.nyabanban.fuji.emulator.components.vglib

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class LcdcFrameOverlayTest {
    @Test
    fun firmwareWindowsUseIndependentEnablesCornerLengthsAndPaletteBanks() {
        val words = LongArray(0x600 / 4 + 1)
        words[0x1c4 / 4] = 0x100001
        words[0x1d0 / 4] = 0x0582c254 // Firmware's green YCbCr palette entry.
        words[0x1d4 / 4] = 0x200803fc // White.
        words[0x1f8 / 4] = 1L shl 16 // Corner window 20 uses entry 1.
        words[0x1fc / 4] = 255
        words[0x208 / 4] = 0x0125023a
        words[0x20c / 4] = 0x01c702dc
        words[0x2a8 / 4] = 0x012a023f
        words[0x2ac / 4] = 0x01c202d7
        words[0x2b0 / 4] = 0x00260026
        val width = 1024
        val height = 768
        val background = 0xff123456.toInt()
        val pixels = IntArray(width * height) { background }
        val green = LcdcFirstScanout.decodeBgColor(words[0x1d0 / 4])
        fun pixel(x: Int, y: Int) = pixels[y * width + x]
        fun paint() = LcdcFrameOverlay.paint(words, width, height, width, height, intArrayOf(), 0, IntBuffer.wrap(pixels))
        paint()
        assertEquals(green, pixel(651, 293))
        assertEquals(green, pixel(732, 455))
        assertEquals(background, pixel(733, 455))
        assertEquals(-1, pixel(575, 298))
        assertEquals(-1, pixel(612, 298))
        assertEquals(background, pixel(613, 298))
        assertEquals(background, pixel(651, 374))
        pixels.fill(background)
        words[0x1c4 / 4] = 1L shl 20
        paint()
        assertEquals(background, pixel(651, 293))
        assertEquals(-1, pixel(575, 298))
    }

    @Test
    fun secondaryAlphaBankAndPanelScalingWorkOnDirectScanout() {
        val words = LongArray(0x600 / 4 + 1)
        words[0x1c4 / 4] = 1
        words[0x1d0 / 4] = 0x200803fc
        words[0x1fc / 4] = 128L shl 8
        words[0x200 / 4] = 1
        words[0x208 / 4] = 0x00010001
        words[0x20c / 4] = 0x00030003
        val pixels = ByteBuffer.allocateDirect(8 * 8 * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        LcdcFrameOverlay.paint(words, 4, 4, 8, 8, intArrayOf(), 0, pixels)
        assertEquals(0xff808080.toInt(), pixels[2 * 8 + 3])
        assertEquals(0, pixels[4 * 8 + 4])
        assertEquals(0xff808080.toInt(), pixels[7 * 8 + 7])
    }
}
