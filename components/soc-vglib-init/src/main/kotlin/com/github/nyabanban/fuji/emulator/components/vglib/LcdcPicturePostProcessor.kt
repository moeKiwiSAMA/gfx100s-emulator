package com.github.nyabanban.fuji.emulator.components.vglib

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-silicon GFX100S 2.13 `LCDC0` (`0xFFF7A000`) / `LCDC1` (`0xFFFA9000`) picture-plane
 * post-processor implementing:
 *
 * 1. **Hardware `F-Log VIEW ASSIST` 3-stage Display Color-Space Pipeline**
 *    (`section4.bin` `0x01253CA0 -> 0x0126DD84 -> 0x01269FB0 -> 0x0125E78C + 0x0125B7EC`,
 *    verified live on `GXUP0004.DAT`):
 *    - **Stage 1 — De-Gamma 1D LUT** (`+0x448..+0x48C`): 10 × 4-bit power-of-2 band spacing nibbles
 *      (`+0x448/+0x44C`, 3 segments/band = 30 segments across 31 knots) + 31 × 10-bit knots
 *      (`+0x450..+0x48C`, 2 knots/word `[9:0]` & `[25:16]`). Identity `y = x` when OFF; converts
 *      flat F-Log (`9.3%` IRE black floor, `46%` 18%-gray) to linear light when ON.
 *    - **Stage 2 — 3×3 Linear Color-Space Matrix** (`+0x418..+0x42C`): 12-bit signed Q10 (`0x400 = 1.0`)
 *      matrix packed 2 coefficients/word `[11:0]` & `[27:16]`. Exact identity `[1024,0,0; ...]` when OFF;
 *      exact ITU-R BT.2020 (F-Gamut) -> BT.709 matrix `[+1.660,-0.588,-0.073; -0.125,+1.133,-0.009;
 *      -0.019,-0.101,+1.119]` when ON.
 *    - **Stage 3 — Output Gamma 1D LUT** (`+0x490..+0x4D4`): 10 × 4-bit band spacing nibbles
 *      (`+0x490/+0x494`) + 31 × 10-bit knots (`+0x498..+0x4D4`), applying BT.709 OETF preview gamma.
 *
 * 2. **Hardware Per-Plane Diagonal `ZEBRA` Overlay**
 *    (`section5.bin` `0x02513568 -> 0x02389A80` -> `section4.bin` `0x01251AFC -> 0x0126BBC4 -> 0x01269274`,
 *    verified live on `GXUP0004.DAT`):
 *    - `PIC0`: `+0x154` (`ctrl`), `+0x158` (`threshold10`), `+0x15C` (`ycbcrColor`)
 *    - `PIC1`: `+0x188` (`ctrl`), `+0x18C` (`threshold10`), `+0x190` (`ycbcrColor`)
 *    - `ctrl[1:0]`: `0 = OFF`, `1 = RIGHT (///)`, `2 = LEFT (\\\)`, `3 = HIGHLIGHT_ALERT`
 *    - `ctrl[15:8]`: stripe width param (`0x01` stock), `ctrl[23:16]`: stripe pitch param (`0x0A` stock)
 *    - `threshold10[9:0]`: 10-bit video luma threshold (`0x1F4 = 500 -> 50% IRE (Y>=125)`,
 *      `0x3A8 = 936 -> 100% IRE (Y>=234)`)
 *    - `ycbcrColor[29:0]`: 10-bit YCbCr stripe color (`[9:0]=Y10`, `[19:10]=Cb10`, `[29:20]=Cr10`,
 *      stock `0x20080000` = neutral black `Y=0, Cb=128, Cr=128`).
 */
internal class LcdcPicturePostProcessor {
    internal data class ZebraConfig(
        val mode: Int,
        val paramA: Int,
        val paramB: Int,
        val threshold8: Int,
        val stripeY: Int,
        val stripeU: Int,
        val stripeV: Int,
    ) {
        val enabled: Boolean get() = mode in 1..3 && threshold8 in 1..254

        companion object {
            val OFF = ZebraConfig(0, 0, 0, 255, 0, 128, 128)
        }
    }

    private var cachedLutFingerprint: Long = Long.MIN_VALUE
    private var viewAssistActive: Boolean = false
    private val degamma256 = IntArray(256)
    private val gamma1024 = IntArray(1024)
    private val matrixQ10 = IntArray(9)

    // Reusable scratch arrays for zero-allocation LUT rebuilding on register change.
    private val knotsScratch = IntArray(LUT_KNOTS)
    private val knotXScratch = DoubleArray(LUT_KNOTS)
    private val nibblesScratch = IntArray(10)

    /**
     * Re-evaluates the LCDC Block B (`+0x418..+0x4D4`) 3-stage display color pipeline from the
     * current register bank. Returns `true` iff a non-identity De-Gamma or Color-Space Matrix
     * (such as `F-Log VIEW ASSIST = ON`) is actively programmed.
     */
    fun updateDisplayLut(words: LongArray, writeCounts: LongArray): Boolean {
        if (writeCounts[0x418 / 4] == 0L || writeCounts[0x448 / 4] == 0L || writeCounts[0x490 / 4] == 0L) {
            viewAssistActive = false
            return false
        }
        val fp = computeLutFingerprint(words)
        if (fp == cachedLutFingerprint) {
            return viewAssistActive
        }
        cachedLutFingerprint = fp

        val w418 = words[0x418 / 4]
        val w41c = words[0x41C / 4]
        val w420 = words[0x420 / 4]
        val w424 = words[0x424 / 4]
        val w428 = words[0x428 / 4]
        val w42c = words[0x42C / 4]
        val m00 = signExtend12(w418); val m01 = signExtend12(w418 ushr 16); val m02 = signExtend12(w41c)
        val m10 = signExtend12(w420); val m11 = signExtend12(w420 ushr 16); val m12 = signExtend12(w424)
        val m20 = signExtend12(w428); val m21 = signExtend12(w428 ushr 16); val m22 = signExtend12(w42c)
        val matrixZero = (m00 or m01 or m02 or m10 or m11 or m12 or m20 or m21 or m22) == 0
        val matrixIdentity = matrixZero || (
            m00 == 1024 && m01 == 0 && m02 == 0 &&
                m10 == 0 && m11 == 1024 && m12 == 0 &&
                m20 == 0 && m21 == 0 && m22 == 1024
            )
        if (matrixZero) {
            matrixQ10[0] = 1024; matrixQ10[1] = 0; matrixQ10[2] = 0
            matrixQ10[3] = 0; matrixQ10[4] = 1024; matrixQ10[5] = 0
            matrixQ10[6] = 0; matrixQ10[7] = 0; matrixQ10[8] = 1024
        } else {
            matrixQ10[0] = m00; matrixQ10[1] = m01; matrixQ10[2] = m02
            matrixQ10[3] = m10; matrixQ10[4] = m11; matrixQ10[5] = m12
            matrixQ10[6] = m20; matrixQ10[7] = m21; matrixQ10[8] = m22
        }

        val hasDeGamma = buildPiecewiseLut256To10(words, 0x448, 0x44C, 0x450, degamma256)
        var maxDeGammaDev = 0
        if (hasDeGamma) {
            for (i in 0 until 256) {
                val expected = (i shl 2) or (i ushr 6)
                val dev = abs(degamma256[i] - expected)
                if (dev > maxDeGammaDev) maxDeGammaDev = dev
            }
        }
        val deGammaIdentity = !hasDeGamma || maxDeGammaDev <= 8

        if (matrixIdentity && deGammaIdentity) {
            viewAssistActive = false
            return false
        }

        if (!hasDeGamma) {
            for (i in 0 until 256) degamma256[i] = (i shl 2) or (i ushr 6)
        }
        val hasGamma = buildPiecewiseLut10To8(words, 0x490, 0x494, 0x498, gamma1024)
        if (!hasGamma) {
            for (i in 0 until 1024) gamma1024[i] = i ushr 2
        }
        viewAssistActive = true
        return true
    }

    /**
     * Applies active `F-Log VIEW ASSIST` (Stage 1 De-Gamma -> Stage 2 3×3 Matrix -> Stage 3 BT.709
     * Gamma) and/or per-plane hardware `ZEBRA` stripes to a `UYVY422` picture plane buffer in-place.
     *
     * When both are enabled simultaneously, Zebra threshold comparison uses the original input luma
     * (matching real camera IRE metering of the recorded signal) while the Zebra stripe color is
     * stamped after View Assist so stripes remain crisp and un-lifted.
     */
    fun processUyvyPlaneInPlace(
        uyvy: ByteArray,
        width: Int,
        height: Int,
        strideBytes: Int,
        applyViewAssist: Boolean,
        zebra: ZebraConfig,
        frameIndex: Int,
    ) {
        if (!applyViewAssist && !zebra.enabled) return
        val zebraOn = zebra.enabled
        val zMode = zebra.mode
        val zThresh = zebra.threshold8
        val zPeriod = if (zebra.paramB in 4..64) zebra.paramB else DEFAULT_ZEBRA_PERIOD
        val zWidth = (zebra.paramA.coerceIn(1, zPeriod / 2) * 2).coerceIn(2, zPeriod / 2)
        val zY = zebra.stripeY.toByte()
        val zU = zebra.stripeU.toByte()
        val zV = zebra.stripeV.toByte()
        val blinkOn = (frameIndex and 8) != 0

        val dg = degamma256
        val gm = gamma1024
        val m00 = matrixQ10[0]; val m01 = matrixQ10[1]; val m02 = matrixQ10[2]
        val m10 = matrixQ10[3]; val m11 = matrixQ10[4]; val m12 = matrixQ10[5]
        val m20 = matrixQ10[6]; val m21 = matrixQ10[7]; val m22 = matrixQ10[8]

        for (y in 0 until height) {
            val rowOff = y * strideBytes
            var x = 0
            while (x + 1 < width) {
                val off = rowOff + (x shl 1)
                val uIn = uyvy[off].toInt() and 0xFF
                val y0In = uyvy[off + 1].toInt() and 0xFF
                val vIn = uyvy[off + 2].toInt() and 0xFF
                val y1In = uyvy[off + 3].toInt() and 0xFF

                val z0 = zebraOn && y0In >= zThresh && isZebraStripe(zMode, x, y, zPeriod, zWidth, blinkOn)
                val z1 = zebraOn && y1In >= zThresh && isZebraStripe(zMode, x + 1, y, zPeriod, zWidth, blinkOn)

                if (z0 && z1) {
                    uyvy[off] = zU
                    uyvy[off + 1] = zY
                    uyvy[off + 2] = zV
                    uyvy[off + 3] = zY
                    x += 2
                    continue
                }

                if (applyViewAssist) {
                    val d = uIn - 128
                    val e = vIn - 128
                    val rDelta = (359 * e + 128) shr 8
                    val gDelta = (-88 * d - 183 * e + 128) shr 8
                    val bDelta = (454 * d + 128) shr 8

                    val r0 = clamp255(y0In + rDelta)
                    val g0 = clamp255(y0In + gDelta)
                    val b0 = clamp255(y0In + bDelta)
                    val lr0 = dg[r0]; val lg0 = dg[g0]; val lb0 = dg[b0]
                    val or0 = gm[clamp1023((m00 * lr0 + m01 * lg0 + m02 * lb0 + 512) shr 10)]
                    val og0 = gm[clamp1023((m10 * lr0 + m11 * lg0 + m12 * lb0 + 512) shr 10)]
                    val ob0 = gm[clamp1023((m20 * lr0 + m21 * lg0 + m22 * lb0 + 512) shr 10)]

                    val r1 = clamp255(y1In + rDelta)
                    val g1 = clamp255(y1In + gDelta)
                    val b1 = clamp255(y1In + bDelta)
                    val lr1 = dg[r1]; val lg1 = dg[g1]; val lb1 = dg[b1]
                    val or1 = gm[clamp1023((m00 * lr1 + m01 * lg1 + m02 * lb1 + 512) shr 10)]
                    val og1 = gm[clamp1023((m10 * lr1 + m11 * lg1 + m12 * lb1 + 512) shr 10)]
                    val ob1 = gm[clamp1023((m20 * lr1 + m21 * lg1 + m22 * lb1 + 512) shr 10)]

                    val outY0 = clamp255((77 * or0 + 150 * og0 + 29 * ob0 + 128) shr 8)
                    val outY1 = clamp255((77 * or1 + 150 * og1 + 29 * ob1 + 128) shr 8)
                    val avgR = (or0 + or1) ushr 1
                    val avgG = (og0 + og1) ushr 1
                    val avgB = (ob0 + ob1) ushr 1
                    val outU = clamp255(((-43 * avgR - 85 * avgG + 128 * avgB + 128) shr 8) + 128)
                    val outV = clamp255(((128 * avgR - 107 * avgG - 21 * avgB + 128) shr 8) + 128)

                    uyvy[off] = if (z0 || z1) zU else outU.toByte()
                    uyvy[off + 1] = if (z0) zY else outY0.toByte()
                    uyvy[off + 2] = if (z0 || z1) zV else outV.toByte()
                    uyvy[off + 3] = if (z1) zY else outY1.toByte()
                } else if (z0 || z1) {
                    if (z0) uyvy[off + 1] = zY
                    if (z1) uyvy[off + 3] = zY
                    uyvy[off] = zU
                    uyvy[off + 2] = zV
                }
                x += 2
            }
        }
    }

    private fun computeLutFingerprint(words: LongArray): Long {
        var h = 1469598103934665603L
        fun mix(off: Int) {
            h = (h xor words[off / 4]) * 1099511628211L
        }
        for (off in 0x418..0x42C step 4) mix(off)
        for (off in 0x448..0x48C step 4) mix(off)
        for (off in 0x490..0x4D4 step 4) mix(off)
        return h
    }

    private fun readKnotsAndSpacing(
        words: LongArray,
        sp0Off: Int,
        sp1Off: Int,
        knotsStartOff: Int,
    ): Boolean {
        val sp0 = words[sp0Off / 4]
        val sp1 = words[sp1Off / 4]
        var anyNonZeroKnot = false
        for (i in 0 until 15) {
            val w = words[(knotsStartOff / 4) + i]
            val k0 = (w and 0x3FFL).toInt()
            val k1 = ((w ushr 16) and 0x3FFL).toInt()
            knotsScratch[i * 2] = k0
            knotsScratch[i * 2 + 1] = k1
            if ((k0 or k1) != 0) anyNonZeroKnot = true
        }
        val lastWord = words[(knotsStartOff / 4) + 15]
        val k30 = (lastWord and 0x3FFL).toInt()
        knotsScratch[30] = if (k30 > 0) k30 else 1023
        if (!anyNonZeroKnot) return false

        for (i in 0 until 10) {
            val w = if (i < 8) sp0 else sp1
            nibblesScratch[i] = ((w ushr ((i and 7) * 4)) and 0xFL).toInt()
        }
        val useExp = nibblesScratch.any { it != 0 } && nibblesScratch.all { it <= 12 }
        var totalSpan = 0.0
        if (useExp) {
            knotXScratch[0] = 0.0
            for (seg in 0 until LUT_KNOTS - 1) {
                val step = (1 shl nibblesScratch[(seg / 3).coerceIn(0, 9)]).toDouble()
                totalSpan += step
                knotXScratch[seg + 1] = totalSpan
            }
        }
        if (!useExp || totalSpan <= 0.0) {
            for (k in 0 until LUT_KNOTS) {
                knotXScratch[k] = (k * 1023.0) / (LUT_KNOTS - 1).toDouble()
            }
        } else {
            val scale = 1023.0 / totalSpan
            for (k in 0 until LUT_KNOTS) {
                knotXScratch[k] *= scale
            }
        }
        return true
    }

    private fun buildPiecewiseLut256To10(
        words: LongArray,
        sp0Off: Int,
        sp1Off: Int,
        knotsStartOff: Int,
        dst256: IntArray,
    ): Boolean {
        if (!readKnotsAndSpacing(words, sp0Off, sp1Off, knotsStartOff)) return false
        var k = 0
        for (i in 0 until 256) {
            val xf = ((i shl 2) or (i ushr 6)).toDouble()
            while (k < LUT_KNOTS - 2 && xf > knotXScratch[k + 1]) k++
            val x0 = knotXScratch[k]
            val x1 = knotXScratch[k + 1]
            val t = if (x1 > x0) ((xf - x0) / (x1 - x0)).coerceIn(0.0, 1.0) else 0.0
            val y10 = knotsScratch[k] + (knotsScratch[k + 1] - knotsScratch[k]) * t
            dst256[i] = y10.roundToInt().coerceIn(0, 1023)
        }
        return true
    }

    private fun buildPiecewiseLut10To8(
        words: LongArray,
        sp0Off: Int,
        sp1Off: Int,
        knotsStartOff: Int,
        dst1024: IntArray,
    ): Boolean {
        if (!readKnotsAndSpacing(words, sp0Off, sp1Off, knotsStartOff)) return false
        var k = 0
        for (x in 0 until 1024) {
            val xf = x.toDouble()
            while (k < LUT_KNOTS - 2 && xf > knotXScratch[k + 1]) k++
            val x0 = knotXScratch[k]
            val x1 = knotXScratch[k + 1]
            val t = if (x1 > x0) ((xf - x0) / (x1 - x0)).coerceIn(0.0, 1.0) else 0.0
            val y10 = knotsScratch[k] + (knotsScratch[k + 1] - knotsScratch[k]) * t
            dst1024[x] = (y10.roundToInt().coerceIn(0, 1023) ushr 2)
        }
        return true
    }

    companion object {
        private const val LUT_KNOTS = 31
        private const val DEFAULT_ZEBRA_PERIOD = 10

        internal fun signExtend12(raw: Long): Int = ((raw.toInt() and 0xFFF) shl 20) shr 20

        internal fun parseZebra(
            words: LongArray,
            writeCounts: LongArray,
            ctrlOff: Int,
            threshOff: Int,
            colorOff: Int,
        ): ZebraConfig {
            if (writeCounts[ctrlOff / 4] == 0L) return ZebraConfig.OFF
            val ctrl = words[ctrlOff / 4]
            val mode = (ctrl and 0x3L).toInt()
            if (mode == 0) return ZebraConfig.OFF
            val thresh10 = (words[threshOff / 4] and 0x3FFL).toInt()
            val threshold8 = (thresh10 ushr 2).coerceIn(0, 255)
            if (threshold8 !in 1..254) return ZebraConfig.OFF
            val paramA = ((ctrl ushr 8) and 0xFFL).toInt()
            val paramB = ((ctrl ushr 16) and 0xFFL).toInt()
            val colorWord = if (writeCounts[colorOff / 4] > 0L) words[colorOff / 4] else 0x20080000L
            val stripeY = ((colorWord and 0x3FFL) ushr 2).toInt().coerceIn(0, 255)
            val stripeU = (((colorWord ushr 10) and 0x3FFL) ushr 2).toInt().coerceIn(0, 255)
            val stripeV = (((colorWord ushr 20) and 0x3FFL) ushr 2).toInt().coerceIn(0, 255)
            return ZebraConfig(
                mode = mode,
                paramA = paramA,
                paramB = paramB,
                threshold8 = threshold8,
                stripeY = stripeY,
                stripeU = stripeU,
                stripeV = stripeV,
            )
        }

        private fun isZebraStripe(mode: Int, x: Int, y: Int, period: Int, width: Int, blinkOn: Boolean): Boolean =
            when (mode) {
                // RIGHT (///): lines slope bottom-left to top-right -> constant (x + y)
                1 -> Math.floorMod(x + y, period) < width
                // LEFT (\\\): lines slope top-left to bottom-right -> constant (x - y)
                2 -> Math.floorMod(x - y, period) < width
                // HIGHLIGHT_ALERT: alternating blinking highlight alert
                3 -> blinkOn
                else -> false
            }

        private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
        private fun clamp1023(v: Int): Int = if (v < 0) 0 else if (v > 1023) 1023 else v
    }
}
