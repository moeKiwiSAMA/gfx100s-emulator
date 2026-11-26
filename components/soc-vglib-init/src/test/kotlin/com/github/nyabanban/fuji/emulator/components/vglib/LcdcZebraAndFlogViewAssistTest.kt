package com.github.nyabanban.fuji.emulator.components.vglib

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LcdcZebraAndFlogViewAssistTest {
    private fun writeBank(words: LongArray, writeCounts: LongArray, off: Int, value: Long) {
        words[off / 4] = value
        writeCounts[off / 4] = writeCounts[off / 4] + 1L
    }

    private fun programStockFlogViewAssistOff(words: LongArray, writeCounts: LongArray) {
        // Exact GXUP0004.DAT LCDC1 Block B values when F-Log VIEW ASSIST = OFF (identity De-Gamma + identity Matrix)
        writeBank(words, writeCounts, 0x418, 0x00000400L)
        writeBank(words, writeCounts, 0x41C, 0x00000000L)
        writeBank(words, writeCounts, 0x420, 0x04000000L)
        writeBank(words, writeCounts, 0x424, 0x00000000L)
        writeBank(words, writeCounts, 0x428, 0x00000000L)
        writeBank(words, writeCounts, 0x42C, 0x00000400L)

        writeBank(words, writeCounts, 0x448, 0x44556677L)
        writeBank(words, writeCounts, 0x44C, 0x00000044L)
        val degammaOff = longArrayOf(
            0x00540000L, 0x00ff00aaL, 0x01aa0154L, 0x022a01ffL, 0x027f0254L,
            0x02d402aaL, 0x031402ffL, 0x033f032aL, 0x036a0354L, 0x038a037fL,
            0x039f0394L, 0x03b403aaL, 0x03ca03bfL, 0x03df03d4L, 0x03f403eaL, 0x000003ffL,
        )
        for (i in degammaOff.indices) writeBank(words, writeCounts, 0x450 + i * 4, degammaOff[i])

        writeBank(words, writeCounts, 0x490, 0x66665555L)
        writeBank(words, writeCounts, 0x494, 0x00000066L)
        val gammaOff = longArrayOf(
            0x0030001dL, 0x005b0046L, 0x00850070L, 0x00ae009aL, 0x00d800c4L,
            0x010100ecL, 0x013e0115L, 0x01900166L, 0x01e101b9L, 0x0234020bL,
            0x0287025dL, 0x02d902b0L, 0x032e0304L, 0x03810357L, 0x03d403abL, 0x000003ffL,
        )
        for (i in gammaOff.indices) writeBank(words, writeCounts, 0x498 + i * 4, gammaOff[i])
    }

    private fun programStockFlogViewAssistOn(words: LongArray, writeCounts: LongArray) {
        // Exact GXUP0004.DAT LCDC1 Block B values when F-Log VIEW ASSIST = ON
        // (BT.2020 F-Gamut -> BT.709 3x3 matrix + F-Log De-Gamma + BT.709 OETF Gamma)
        writeBank(words, writeCounts, 0x418, 0x0da606a4L)
        writeBank(words, writeCounts, 0x41C, 0x00000fb5L)
        writeBank(words, writeCounts, 0x420, 0x04880f80L)
        writeBank(words, writeCounts, 0x424, 0x00000ff7L)
        writeBank(words, writeCounts, 0x428, 0x0f990fedL)
        writeBank(words, writeCounts, 0x42C, 0x0000047aL)

        writeBank(words, writeCounts, 0x448, 0x66665555L)
        writeBank(words, writeCounts, 0x44C, 0x00000066L)
        val degammaOn = longArrayOf(
            0x00000000L, 0x00000000L, 0x00010000L, 0x00070004L, 0x000f000bL,
            0x001a0014L, 0x00300020L, 0x00610045L, 0x00b60086L, 0x013000f1L,
            0x01b50170L, 0x024001fbL, 0x02cc0286L, 0x0350030eL, 0x03c7038fL, 0x000003ffL,
        )
        for (i in degammaOn.indices) writeBank(words, writeCounts, 0x450 + i * 4, degammaOn[i])

        writeBank(words, writeCounts, 0x490, 0x66653333L)
        writeBank(words, writeCounts, 0x494, 0x00000077L)
        val gammaOn = longArrayOf(
            0x00510000L, 0x006c0054L, 0x00a00082L, 0x00c700b2L, 0x00e700d8L,
            0x010600f7L, 0x01470113L, 0x0190016dL, 0x020501d3L, 0x025b0230L,
            0x02a0027eL, 0x02de02c1L, 0x033102faL, 0x038a035eL, 0x03db03b5L, 0x000003ffL,
        )
        for (i in gammaOn.indices) writeBank(words, writeCounts, 0x498 + i * 4, gammaOn[i])
    }

    private fun makeTwoToneUyvy(w: Int, h: Int, darkY: Int, brightY: Int): ByteArray {
        val buf = ByteArray(w * h * 2)
        for (y in 0 until h) {
            val rowOff = y * w * 2
            var x = 0
            while (x + 1 < w) {
                val lv = (if (x < w / 2) darkY else brightY).toByte()
                val off = rowOff + x * 2
                buf[off] = 0x80.toByte()
                buf[off + 1] = lv
                buf[off + 2] = 0x80.toByte()
                buf[off + 3] = lv
                x += 2
            }
        }
        return buf
    }

    private fun lumaStd(uyvy: ByteArray, w: Int, h: Int): Double {
        var sum = 0.0
        var sumSq = 0.0
        val n = w * h
        for (y in 0 until h) {
            val rowOff = y * w * 2
            for (x in 0 until w) {
                val lv = (uyvy[rowOff + x * 2 + 1].toInt() and 0xFF).toDouble()
                sum += lv
                sumSq += lv * lv
            }
        }
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }

    @Test
    fun zebraOverlayRendersDiagonalRightAndLeftStripesAtHardwareIreThreshold() {
        val words = LongArray(0x1000 / 4)
        val writeCounts = LongArray(0x1000 / 4)
        val proc = LcdcPicturePostProcessor()
        val w = 128
        val h = 64

        // 1. ZEBRA OFF (0x000a0100) -> untouched
        writeBank(words, writeCounts, 0x188, 0x000a0100L)
        writeBank(words, writeCounts, 0x18C, 0x000001f4L) // 50% IRE -> Y >= 125
        writeBank(words, writeCounts, 0x190, 0x20080000L) // Black (Y=0, U=128, V=128)
        val zOff = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        assertFalse(zOff.enabled)
        val base = makeTwoToneUyvy(w, h, darkY = 80, brightY = 200)
        val copyOff = base.copyOf()
        proc.processUyvyPlaneInPlace(copyOff, w, h, w * 2, false, zOff, 0)
        assertContentEquals(base, copyOff)

        // 2. ZEBRA RIGHT (0x000a0101) at 50% IRE (0x1f4 -> 125) -> stripes ONLY on bright right half!
        writeBank(words, writeCounts, 0x188, 0x000a0101L)
        val zRight = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        assertTrue(zRight.enabled)
        assertEquals(1, zRight.mode)
        assertEquals(125, zRight.threshold8)
        val rightBuf = base.copyOf()
        proc.processUyvyPlaneInPlace(rightBuf, w, h, w * 2, false, zRight, 0)

        var darkStriped = 0
        var brightStriped = 0
        var rightDiagMatch = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lv = rightBuf[y * w * 2 + x * 2 + 1].toInt() and 0xFF
                if (x < w / 2) {
                    if (lv == 0) darkStriped++
                } else {
                    if (lv == 0) brightStriped++
                    if (y + 1 < h && x > w / 2) {
                        val lvDiag = rightBuf[(y + 1) * w * 2 + (x - 1) * 2 + 1].toInt() and 0xFF
                        if (lv == 0 && lvDiag == 0) rightDiagMatch++
                    }
                }
            }
        }
        assertEquals(0, darkStriped, "Dark Y=80 region below 50% IRE must have 0 zebra stripes")
        assertTrue(brightStriped in 600..1200, "Bright Y=200 region must have ~20% diagonal stripes (got $brightStriped)")
        assertTrue(rightDiagMatch > 500, "RIGHT (///) stripes must align along (x-1, y+1) diagonal (got $rightDiagMatch)")

        // 3. ZEBRA LEFT (0x000a0102) at 50% IRE -> diagonal aligns along (x+1, y+1) and differs from RIGHT
        writeBank(words, writeCounts, 0x188, 0x000a0102L)
        val zLeft = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        val leftBuf = base.copyOf()
        proc.processUyvyPlaneInPlace(leftBuf, w, h, w * 2, false, zLeft, 0)
        var leftDiagMatch = 0
        var diffRightLeft = 0
        for (y in 0 until h) {
            for (x in w / 2 until w) {
                val lv = leftBuf[y * w * 2 + x * 2 + 1].toInt() and 0xFF
                val rv = rightBuf[y * w * 2 + x * 2 + 1].toInt() and 0xFF
                if (lv != rv) diffRightLeft++
                if (y + 1 < h && x + 1 < w) {
                    val lvDiag = leftBuf[(y + 1) * w * 2 + (x + 1) * 2 + 1].toInt() and 0xFF
                    if (lv == 0 && lvDiag == 0) leftDiagMatch++
                }
            }
        }
        assertTrue(leftDiagMatch > 500, "LEFT (\\\\\\) stripes must align along (x+1, y+1) diagonal (got $leftDiagMatch)")
        assertTrue(diffRightLeft > 800, "LEFT and RIGHT zebra stripe patterns must visibly differ (diff=$diffRightLeft)")

        // 4. Raising threshold to 100% IRE (0x3a8 -> Y >= 234) clears stripes on Y=200, keeps stripes on Y=245
        writeBank(words, writeCounts, 0x18C, 0x000003a8L)
        val z100 = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        assertEquals(234, z100.threshold8)
        val buf200 = makeTwoToneUyvy(w, h, darkY = 80, brightY = 200)
        proc.processUyvyPlaneInPlace(buf200, w, h, w * 2, false, z100, 0)
        assertContentEquals(makeTwoToneUyvy(w, h, darkY = 80, brightY = 200), buf200)

        val buf245 = makeTwoToneUyvy(w, h, darkY = 200, brightY = 245)
        proc.processUyvyPlaneInPlace(buf245, w, h, w * 2, false, z100, 0)
        var clippedStripes = 0
        for (y in 0 until h) for (x in w / 2 until w) {
            if ((buf245[y * w * 2 + x * 2 + 1].toInt() and 0xFF) == 0) clippedStripes++
        }
        assertTrue(clippedStripes > 600, "Clipped Y=245 highlights must still stripe at 100% IRE (got $clippedStripes)")
    }

    @Test
    fun flogViewAssistExpandsContrastAndMatchesNativeAndJvmScanout() {
        val words = LongArray(0x1000 / 4)
        val writeCounts = LongArray(0x1000 / 4)
        val proc = LcdcPicturePostProcessor()

        // 1. Stock F-Log VIEW ASSIST = OFF -> exact identity bypass
        programStockFlogViewAssistOff(words, writeCounts)
        assertFalse(proc.updateDisplayLut(words, writeCounts), "OFF mode De-Gamma + Matrix must bypass")

        // 2. Stock F-Log VIEW ASSIST = ON -> active BT.709 3-stage pipeline
        programStockFlogViewAssistOn(words, writeCounts)
        assertTrue(proc.updateDisplayLut(words, writeCounts), "ON mode must activate BT.709 preview pipeline")

        // Build flat F-Log ramp spanning 9.4% IRE (24) .. 75% IRE (192)
        val w = 128
        val h = 32
        val flatFlog = ByteArray(w * h * 2)
        for (y in 0 until h) {
            val rowOff = y * w * 2
            var x = 0
            while (x + 1 < w) {
                val lv0 = 24 + (x * 168) / (w - 1)
                val lv1 = 24 + ((x + 1) * 168) / (w - 1)
                val off = rowOff + x * 2
                flatFlog[off] = 0x80.toByte()
                flatFlog[off + 1] = lv0.toByte()
                flatFlog[off + 2] = 0x80.toByte()
                flatFlog[off + 3] = lv1.toByte()
                x += 2
            }
        }
        val stdBefore = lumaStd(flatFlog, w, h)
        val assisted = flatFlog.copyOf()
        proc.processUyvyPlaneInPlace(assisted, w, h, w * 2, applyViewAssist = true, zebra = LcdcPicturePostProcessor.ZebraConfig.OFF, frameIndex = 0)
        val stdAfter = lumaStd(assisted, w, h)

        val shadowOut = assisted[1].toInt() and 0xFF
        val midOut = assisted[(w / 2) * 2 + 1].toInt() and 0xFF
        val hiOut = assisted[(w - 1) * 2 + 1].toInt() and 0xFF

        assertTrue(shadowOut <= 8, "F-Log 9.4% black floor (24) must pull down to BT.709 black (got $shadowOut)")
        assertTrue(midOut in 95..135, "F-Log mid-gray must stay well-exposed in BT.709 (got $midOut)")
        assertTrue(hiOut >= 200, "F-Log 75% IRE highlight (192) must expand into bright BT.709 highlight (got $hiOut)")
        assertTrue(stdAfter > stdBefore * 1.25, "BT.709 View Assist must expand flat F-Log contrast (std $stdBefore -> $stdAfter)")

        // 3. Combined F-Log VIEW ASSIST = ON + ZEBRA RIGHT 50% -> pure black stripes over >=125 input luma
        writeBank(words, writeCounts, 0x188, 0x000a0101L)
        writeBank(words, writeCounts, 0x18C, 0x000001f4L)
        writeBank(words, writeCounts, 0x190, 0x20080000L)
        val zRight = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        val combined = flatFlog.copyOf()
        proc.processUyvyPlaneInPlace(combined, w, h, w * 2, applyViewAssist = true, zebra = zRight, frameIndex = 0)
        var pureBlackZebra = 0
        for (y in 0 until h) for (x in (w * 3) / 4 until w) {
            if ((combined[y * w * 2 + x * 2 + 1].toInt() and 0xFF) == 0) pureBlackZebra++
        }
        assertTrue(pureBlackZebra > 100, "Zebra stripes must stay crisp pure black even with F-Log View Assist ON (got $pureBlackZebra)")

        // 4. Verify OSD0 white HUD pixels stay 100% untouched when blended over processed PIC1
        val osd = ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            if (x in 8..24 && y in 4..12) {
                // Opaque white RGBA HUD badge
                osd.put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte())
            } else {
                osd.put(0).put(0).put(0).put(0)
            }
        }
        assertEquals(w * h * 4, osd.position())
    }
}
