package com.github.nyabanban.fuji.emulator.runtime

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure pixel-based multi-scale human face detector operating strictly on the `640x480`
 * `YYUV422` (`[Y0, Y1, Cb, Cr]`) DMA image buffer fed to `0xFFF7C000` (`ImageFace7c000`).
 *
 * Uses zero external libraries and zero scene ground-truth leakage:
 * 1. Auto-detects active non-letterbox image rows inside `640x480` (e.g. `y in [60, 420)` for 16:9).
 * 2. Downsamples `4x4` into integral images (`Y`, `Y^2`, BT.601 warm skin likelihood).
 * 3. Evaluates classic Viola-Jones Haar-like facial geometry (skin oval + non-skin framing +
 *    forehead/eye-band/cheek vertical contrast + twin-eye/nose-bridge horizontal contrast +
 *    mouth dip + natural luma variance) across the enabled hardware scales.
 * 4. Applies NMS and full-resolution luma iris-pair refinement so firmware CPU Eye-AF
 *    (`task 0x90 @ 0x025a3c68`) receives accurately centered face boxes and roll indices.
 */
internal object PixelFaceDetector {
    data class DetectedFace(
        val centerX: Float,
        val centerY: Float,
        val halfSize: Float,
        val scaleIdx: Int,
        val rollIdx: Int,
        val yawIdx: Int,
        val score: Int,
    )

    // DAT 0x02876392: Mode 1 (640x480) halfSize_q7 table (divide by 128 for pixels).
    val HALF_SIZE_Q7 = intArrayOf(
        2048, 2580, 3244, 4096, 5201, 6553, 8192, 10240,
        13107, 16384, 20480, 25206, 32768, 40960, 54613,
    )

    private const val MAX_FACES = 16
    private const val CELL = 4

    // Precomputed 256x256 (cb, cr) skin-chroma base weight LUTs (0..100f) eliminating per-pixel sqrt()
    // during 640x480 YYUV422 integral construction.
    private val SKIN_CHROMA_NORM = FloatArray(256 * 256).also { lut ->
        for (cb in 72..126) {
            val dCb = abs(cb - 102) / 28.0
            val dCbSq = dCb * dCb
            val row = cb shl 8
            for (cr in 130..186) {
                if (cr - cb < 10) continue
                val dCr = abs(cr - 151.0) / 30.0
                val dist = sqrt(dCbSq + dCr * dCr)
                if (dist < 1.32) {
                    lut[row or cr] = (((1.32 - dist) / 1.32).coerceIn(0.0, 1.0) * 100.0).toFloat()
                }
            }
        }
    }

    private val SKIN_CHROMA_HIGH = FloatArray(256 * 256).also { lut ->
        for (cb in 72..124) {
            val dCb = abs(cb - 102) / 28.0
            val dCbSq = dCb * dCb
            val row = cb shl 8
            for (cr in 129..186) {
                if (cr - cb < 8) continue
                val dCr = abs(cr - 146.0) / 30.0
                val dist = sqrt(dCbSq + dCr * dCr)
                if (dist < 1.32) {
                    lut[row or cr] = (((1.32 - dist) / 1.32).coerceIn(0.0, 1.0) * 100.0).toFloat()
                }
            }
        }
    }

    // Smooth luma taper in [0..1] for Y in 0..255 (supports dark P-mode LiveView down to Y=18).
    private val SKIN_LUMA_WEIGHT = FloatArray(256) { y ->
        if (y < 18) 0f else ((y - 14) / 48.0).coerceIn(0.0, 1.0).toFloat()
    }

    private class IntegralScratch {
        var intY = DoubleArray(0)
        var intY2 = DoubleArray(0)
        var intSkin = DoubleArray(0)
        fun ensure(size: Int) {
            if (intY.size < size) {
                intY = DoubleArray(size)
                intY2 = DoubleArray(size)
                intSkin = DoubleArray(size)
            }
        }
    }

    private val scratchLocal = ThreadLocal.withInitial { IntegralScratch() }

    fun detectYyuv422(
        frame: ByteArray,
        width: Int,
        height: Int,
        strideBytes: Int,
        scaleEnableMask: Int = 0x03FF,
        maxFaces: Int = MAX_FACES,
    ): List<DetectedFace> {
        if (width < 64 || height < 64 || strideBytes < width * 2) return emptyList()
        val maxRows = min(height, frame.size / strideBytes)
        if (maxRows < 64) return emptyList()

        // 1. Detect active non-letterbox vertical bounds (skip uniform 0x00 or 0x80 letterbox rows).
        var activeTop = 0
        while (activeTop < maxRows - 32 && isUniformLetterboxRow(frame, activeTop * strideBytes, width)) {
            activeTop++
        }
        var activeBot = maxRows
        while (activeBot > activeTop + 32 && isUniformLetterboxRow(frame, (activeBot - 1) * strideBytes, width)) {
            activeBot--
        }
        if (activeBot - activeTop < 48) return emptyList()

        // 2. Downsample 4x4 into reusable integral tables for O(1) window feature queries.
        val dw = width / CELL
        val dh = maxRows / CELL
        if (dw < 16 || dh < 16) return emptyList()
        val iw = dw + 1
        val ih = dh + 1
        val scratch = scratchLocal.get()
        scratch.ensure(iw * ih)
        val intY = scratch.intY
        val intY2 = scratch.intY2
        val intSkin = scratch.intSkin
        java.util.Arrays.fill(intY, 0, iw, 0.0)
        java.util.Arrays.fill(intY2, 0, iw, 0.0)
        java.util.Arrays.fill(intSkin, 0, iw, 0.0)

        val activeTopCell = activeTop / CELL
        val activeBotCell = activeBot / CELL
        val chromaNorm = SKIN_CHROMA_NORM
        val chromaHigh = SKIN_CHROMA_HIGH
        val lumaW = SKIN_LUMA_WEIGHT

        for (cy in 0 until dh) {
            val inActive = cy in activeTopCell until activeBotCell
            var rowSumY = 0.0
            var rowSumY2 = 0.0
            var rowSumSkin = 0.0
            val dstRow = (cy + 1) * iw
            val prevRow = cy * iw
            intY[dstRow] = 0.0
            intY2[dstRow] = 0.0
            intSkin[dstRow] = 0.0
            val py0 = cy * CELL
            for (cx in 0 until dw) {
                var cellY = 0.0
                var cellY2 = 0.0
                var cellSkin = 0.0
                if (inActive) {
                    val px0 = cx * CELL
                    var sumY = 0
                    var sumY2 = 0
                    var sumSkin = 0f
                    var samples = 0
                    for (dy in 0 until CELL) {
                        val rowBase = (py0 + dy) * strideBytes
                        var pairOff = rowBase + (px0 ushr 1) * 4
                        val endOff = pairOff + (CELL ushr 1) * 4
                        while (pairOff < endOff && pairOff + 3 < frame.size) {
                            val y0 = frame[pairOff].toInt() and 0xFF
                            val y1 = frame[pairOff + 1].toInt() and 0xFF
                            val cb = frame[pairOff + 2].toInt() and 0xFF
                            val cr = frame[pairOff + 3].toInt() and 0xFF
                            sumY += y0 + y1
                            sumY2 += y0 * y0 + y1 * y1
                            val cc = (cb shl 8) or cr
                            val lw0 = lumaW[y0]
                            if (lw0 > 0f) {
                                sumSkin += (if (y0 >= 228) chromaHigh[cc] else chromaNorm[cc]) * lw0
                            }
                            val lw1 = lumaW[y1]
                            if (lw1 > 0f) {
                                sumSkin += (if (y1 >= 228) chromaHigh[cc] else chromaNorm[cc]) * lw1
                            }
                            samples += 2
                            pairOff += 4
                        }
                    }
                    if (samples > 0) {
                        val inv = 1.0 / samples
                        cellY = sumY * inv
                        cellY2 = sumY2 * inv
                        cellSkin = sumSkin * inv
                    }
                }
                rowSumY += cellY
                rowSumY2 += cellY2
                rowSumSkin += cellSkin
                intY[dstRow + cx + 1] = intY[prevRow + cx + 1] + rowSumY
                intY2[dstRow + cx + 1] = intY2[prevRow + cx + 1] + rowSumY2
                intSkin[dstRow + cx + 1] = intSkin[prevRow + cx + 1] + rowSumSkin
            }
        }

        fun boxMean(table: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            val ax = x0.coerceIn(0, dw)
            val ay = y0.coerceIn(0, dh)
            val bx = x1.coerceIn(ax, dw)
            val by = y1.coerceIn(ay, dh)
            val area = (bx - ax) * (by - ay)
            if (area <= 0) return 0.0
            val s = table[by * iw + bx] - table[ay * iw + bx] - table[by * iw + ax] + table[ay * iw + ax]
            return s / area
        }

        // 3. Multi-scale sliding window across enabled hardware scale pyramid,
        //    evaluating upright (0 deg, rollIdx=0) and portrait +-90 deg (rollIdx=3 CW, rollIdx=9 CCW)
        //    orientations over the shared integral tables whenever the fast skinCore pre-filter passes.
        data class RawCand(
            val cx: Float,
            val cy: Float,
            val side: Float,
            val scaleIdx: Int,
            val orientRollIdx: Int,
            val score: Double,
        )

        val candidates = ArrayList<RawCand>(32)
        for (scaleIdx in HALF_SIZE_Q7.indices) {
            if ((scaleEnableMask and (1 shl scaleIdx)) == 0) continue
            val sidePx = (HALF_SIZE_Q7[scaleIdx] * 2.0f) / 128.0f
            val sideCells = (sidePx / CELL).roundToInt()
            // At 4x4 cell downsampling, windows smaller than 12 cells (~48px, scaleIdx < 2) collapse
            // facial Haar sub-regions (forehead, nose bridge, mouth) to single cells and trigger false positives.
            if (sideCells < 12 || sideCells > (activeBotCell - activeTopCell) - 2 || sideCells > dw - 2) continue
            val step = max(2, sideCells / 9)
            val pad = max(2, (sideCells * 0.18).toInt())

            // Precompute cell offsets for this scale (eliminates repeated float casts inside inner loop).
            val o08 = max(1, (sideCells * 0.08).toInt())
            val o10 = max(1, (sideCells * 0.10).toInt())
            val o16 = (sideCells * 0.16).toInt()
            val o18 = (sideCells * 0.18).toInt()
            val o20 = (sideCells * 0.20).toInt()
            val o22 = (sideCells * 0.22).toInt()
            val o24 = (sideCells * 0.24).toInt()
            val o28 = (sideCells * 0.28).toInt()
            val o30 = (sideCells * 0.30).toInt()
            val o33 = (sideCells * 0.33).toInt()
            val o34 = (sideCells * 0.34).toInt()
            val o42 = (sideCells * 0.42).toInt()
            val o43 = (sideCells * 0.43).toInt()
            val o46 = (sideCells * 0.46).toInt()
            val o47 = (sideCells * 0.47).toInt()
            val o53 = (sideCells * 0.53).toInt()
            val o54 = (sideCells * 0.54).toInt()
            val o57 = (sideCells * 0.57).toInt()
            val o58 = (sideCells * 0.58).toInt()
            val o66 = (sideCells * 0.66).toInt()
            val o67 = (sideCells * 0.67).toInt()
            val o70 = (sideCells * 0.70).toInt()
            val o72 = (sideCells * 0.72).toInt()
            val o76 = (sideCells * 0.76).toInt()
            val o78 = (sideCells * 0.78).toInt()
            val o80 = (sideCells * 0.80).toInt()
            val o82 = (sideCells * 0.82).toInt()
            val o84 = (sideCells * 0.84).toInt()
            val o90 = (sideCells * 0.90).toInt()
            val o92 = (sideCells * 0.92).toInt()

            var y0 = activeTopCell + 1
            while (y0 + sideCells <= activeBotCell - 1) {
                var x0 = 0
                while (x0 + sideCells <= dw) {
                    // Quick O(1) central skin pre-filter (shared across 0, +90, -90 deg orientations).
                    val coreX0 = x0 + o22
                    val coreX1 = x0 + o78
                    val coreY0 = y0 + o16
                    val coreY1 = y0 + o84
                    val skinCore = boxMean(intSkin, coreX0, coreY0, coreX1, coreY1)
                    val skinCoreRot = if (skinCore >= 18.0) {
                        skinCore
                    } else {
                        boxMean(intSkin, x0 + o16, y0 + o22, x0 + o84, y0 + o78)
                    }
                    if (skinCore >= 18.0 || skinCoreRot >= 18.0) {
                        val skinTop = boxMean(intSkin, coreX0, max(activeTopCell, y0 - pad), coreX1, y0 + o08)
                        val skinLeft = boxMean(intSkin, max(0, x0 - pad), coreY0, x0 + o10, coreY1)
                        val skinRight = boxMean(intSkin, x0 + o90, coreY0, min(dw, x0 + sideCells + pad), coreY1)

                        // --- Orientation 0: Upright (0 deg, rollIdx = 0) ---
                        if (skinCore >= 18.0) {
                            val borderSkin = (skinTop + skinLeft + skinRight) / 3.0
                            val skinFraming = skinCore - borderSkin
                            val frameFloor = min(20.0, skinCore * 0.48)
                            val sideFloor = min(14.0, skinCore * 0.34)
                            if (skinFraming >= frameFloor && skinTop <= skinCore - sideFloor && skinLeft <= skinCore - sideFloor && skinRight <= skinCore - sideFloor) {
                                val foreX0 = x0 + o24; val foreX1 = x0 + o76; val foreY0 = y0 + o10; val foreY1 = y0 + o28
                                val eyeX0 = x0 + o18; val eyeX1 = x0 + o82; val eyeY0 = y0 + o28; val eyeY1 = y0 + o46
                                val cheekX0 = x0 + o22; val cheekX1 = x0 + o78; val cheekY0 = y0 + o47; val cheekY1 = y0 + o66

                                val skinFore = boxMean(intSkin, foreX0, foreY0, foreX1, foreY1)
                                val skinEyes = boxMean(intSkin, eyeX0, eyeY0, eyeX1, eyeY1)
                                val skinCheek = boxMean(intSkin, cheekX0, cheekY0, cheekX1, cheekY1)

                                val meanY = boxMean(intY, coreX0, coreY0, coreX1, coreY1)
                                val meanY2 = boxMean(intY2, coreX0, coreY0, coreX1, coreY1)
                                val stdY = sqrt(max(0.0, meanY2 - meanY * meanY))
                                val yFore = boxMean(intY, foreX0, foreY0, foreX1, foreY1)
                                val lumaScale = (meanY / 68.0).coerceIn(0.32, 1.0)
                                val skinMinSub = 30.0 * lumaScale
                                val foreSkinOk = skinFore >= skinMinSub || (yFore >= 225.0 && skinFore >= 10.0)
                                if (skinCore >= 35.0 * lumaScale && foreSkinOk && skinEyes >= 24.0 * lumaScale && skinCheek >= skinMinSub &&
                                    meanY in 20.0..248.0 && stdY in (9.0 * lumaScale)..92.0
                                ) {
                                    val yEyes = boxMean(intY, eyeX0, eyeY0, eyeX1, eyeY1)
                                    val yCheek = boxMean(intY, cheekX0, cheekY0, cheekX1, cheekY1)
                                    val eyeBandDip = min(yFore, yCheek) - yEyes
                                    val yLeftEye = boxMean(intY, eyeX0, eyeY0, x0 + o42, eyeY1)
                                    val yBridge = boxMean(intY, x0 + o43, eyeY0, x0 + o57, eyeY1)
                                    val yRightEye = boxMean(intY, x0 + o58, eyeY0, eyeX1, eyeY1)
                                    val bridgeContrast = yBridge - (yLeftEye + yRightEye) * 0.5
                                    val bridgeMinContrast = yBridge - max(yLeftEye, yRightEye)
                                    val eyeAsymmetry = abs(yLeftEye - yRightEye)
                                    val yMouth = boxMean(intY, x0 + o30, y0 + o67, x0 + o70, y0 + o80)
                                    val mouthDip = yCheek - yMouth
                                    val yAbove = boxMean(intY, coreX0, max(activeTopCell, y0 - pad), coreX1, y0 + o08)
                                    val topContrast = max(0.0, yFore - yAbove)

                                    val invLumaScale = 1.0 / lumaScale
                                    if (eyeBandDip >= 6.5 * lumaScale && bridgeContrast >= 4.5 * lumaScale &&
                                        (bridgeMinContrast >= 0.0 || bridgeContrast >= 10.0 * lumaScale) &&
                                        eyeAsymmetry <= 46.0 && mouthDip >= -1.0 * lumaScale &&
                                        topContrast >= 8.0 * lumaScale
                                    ) {
                                        val totalScore =
                                            skinCore * 1.8 * invLumaScale.coerceAtMost(1.8) +
                                                skinFraming * 2.0 * invLumaScale.coerceAtMost(1.8) +
                                                min(40.0, eyeBandDip * invLumaScale) * 4.5 +
                                                min(35.0, bridgeContrast * invLumaScale) * 4.0 +
                                                min(20.0, max(0.0, mouthDip * invLumaScale)) * 2.2 +
                                                min(25.0, topContrast * invLumaScale) * 1.0 -
                                                eyeAsymmetry * 0.45
                                        if (totalScore >= 280.0) {
                                            candidates.add(
                                                RawCand(
                                                    cx = (x0 + sideCells * 0.5f) * CELL,
                                                    cy = (y0 + sideCells * 0.5f) * CELL,
                                                    side = sidePx,
                                                    scaleIdx = scaleIdx,
                                                    orientRollIdx = 0,
                                                    score = totalScore,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // --- Portrait +-90 deg orientations (BUG-V3-5-7: mma8452q portrait-cw / portrait-ccw) ---
                        val rotCoreX0 = x0 + o16
                        val rotCoreX1 = x0 + o84
                        val rotCoreY0 = y0 + o22
                        val rotCoreY1 = y0 + o78
                        val rotSkinCore = boxMean(intSkin, rotCoreX0, rotCoreY0, rotCoreX1, rotCoreY1)
                        if (rotSkinCore >= 18.0) {
                            val rSkinTop = boxMean(intSkin, rotCoreX0, max(activeTopCell, y0 - pad), rotCoreX1, y0 + o10)
                            val rSkinBot = boxMean(intSkin, rotCoreX0, y0 + o90, rotCoreX1, min(activeBotCell, y0 + sideCells + pad))
                            val rotSideFloor = min(14.0, rotSkinCore * 0.34)
                            if (rSkinTop <= rotSkinCore - rotSideFloor && rSkinBot <= rotSkinCore - rotSideFloor) {
                                val rotFrameFloor = min(20.0, rotSkinCore * 0.48)
                                val rMeanY = boxMean(intY, rotCoreX0, rotCoreY0, rotCoreX1, rotCoreY1)
                                val rMeanY2 = boxMean(intY2, rotCoreX0, rotCoreY0, rotCoreX1, rotCoreY1)
                                val rStdY = sqrt(max(0.0, rMeanY2 - rMeanY * rMeanY))
                                val rLumaScale = (rMeanY / 68.0).coerceIn(0.32, 1.0)
                                if (rotSkinCore >= 35.0 * rLumaScale && rMeanY in 20.0..248.0 && rStdY in (9.0 * rLumaScale)..92.0) {
                                    val rInvLuma = 1.0 / rLumaScale
                                    val rSkinMinSub = 30.0 * rLumaScale

                                    // Orientation +90 deg CW (rollIdx = 3, forehead at +X right, chin at -X left, portrait-ccw)
                                    val rSkinHeadCw = boxMean(intSkin, x0 + o92, rotCoreY0, min(dw, x0 + sideCells + pad), rotCoreY1)
                                    val rFrameCw = rotSkinCore - (rSkinHeadCw + rSkinTop + rSkinBot) / 3.0
                                    if (rFrameCw >= rotFrameFloor && rSkinHeadCw <= rotSkinCore - rotSideFloor) {
                                        val foreX0 = x0 + o72; val foreX1 = x0 + o90; val foreY0 = y0 + o24; val foreY1 = y0 + o76
                                        val eyeX0 = x0 + o54; val eyeX1 = x0 + o72; val eyeY0 = y0 + o18; val eyeY1 = y0 + o82
                                        val cheekX0 = x0 + o34; val cheekX1 = x0 + o53; val cheekY0 = y0 + o22; val cheekY1 = y0 + o78
                                        val skinFore = boxMean(intSkin, foreX0, foreY0, foreX1, foreY1)
                                        val skinEyes = boxMean(intSkin, eyeX0, eyeY0, eyeX1, eyeY1)
                                        val skinCheek = boxMean(intSkin, cheekX0, cheekY0, cheekX1, cheekY1)
                                        val yFore = boxMean(intY, foreX0, foreY0, foreX1, foreY1)
                                        if ((skinFore >= rSkinMinSub || (yFore >= 225.0 && skinFore >= 10.0)) &&
                                            skinEyes >= 24.0 * rLumaScale && skinCheek >= rSkinMinSub
                                        ) {
                                            val yEyes = boxMean(intY, eyeX0, eyeY0, eyeX1, eyeY1)
                                            val yCheek = boxMean(intY, cheekX0, cheekY0, cheekX1, cheekY1)
                                            val eyeBandDip = min(yFore, yCheek) - yEyes
                                            val yEyeA = boxMean(intY, eyeX0, eyeY0, eyeX1, y0 + o42)
                                            val yBridge = boxMean(intY, eyeX0, y0 + o43, eyeX1, y0 + o57)
                                            val yEyeB = boxMean(intY, eyeX0, y0 + o58, eyeX1, eyeY1)
                                            val bridgeContrast = yBridge - (yEyeA + yEyeB) * 0.5
                                            val bridgeMinContrast = yBridge - max(yEyeA, yEyeB)
                                            val eyeAsymmetry = abs(yEyeA - yEyeB)
                                            val yMouth = boxMean(intY, x0 + o20, y0 + o30, x0 + o33, y0 + o70)
                                            val mouthDip = yCheek - yMouth
                                            val yAbove = boxMean(intY, x0 + o92, rotCoreY0, min(dw, x0 + sideCells + pad), rotCoreY1)
                                            val topContrast = max(0.0, yFore - yAbove)
                                            if (eyeBandDip >= 6.5 * rLumaScale && bridgeContrast >= 4.5 * rLumaScale &&
                                                (bridgeMinContrast >= 0.0 || bridgeContrast >= 10.0 * rLumaScale) &&
                                                eyeAsymmetry <= 46.0 && mouthDip >= -1.0 * rLumaScale &&
                                                topContrast >= 8.0 * rLumaScale
                                            ) {
                                                val totalScore =
                                                    rotSkinCore * 1.8 * rInvLuma.coerceAtMost(1.8) +
                                                        rFrameCw * 2.0 * rInvLuma.coerceAtMost(1.8) +
                                                        min(40.0, eyeBandDip * rInvLuma) * 4.5 +
                                                        min(35.0, bridgeContrast * rInvLuma) * 4.0 +
                                                        min(20.0, max(0.0, mouthDip * rInvLuma)) * 2.2 +
                                                        min(25.0, topContrast * rInvLuma) * 1.0 -
                                                        eyeAsymmetry * 0.45
                                                if (totalScore >= 280.0) {
                                                    candidates.add(
                                                        RawCand(
                                                            cx = (x0 + sideCells * 0.5f) * CELL,
                                                            cy = (y0 + sideCells * 0.5f) * CELL,
                                                            side = sidePx,
                                                            scaleIdx = scaleIdx,
                                                            orientRollIdx = 3,
                                                            score = totalScore,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Orientation -90 deg CCW (rollIdx = 9, forehead at -X left, chin at +X right, portrait-cw)
                                    val rSkinHeadCcw = boxMean(intSkin, max(0, x0 - pad), rotCoreY0, x0 + o08, rotCoreY1)
                                    val rFrameCcw = rotSkinCore - (rSkinHeadCcw + rSkinTop + rSkinBot) / 3.0
                                    if (rFrameCcw >= rotFrameFloor && rSkinHeadCcw <= rotSkinCore - rotSideFloor) {
                                        val foreX0 = x0 + o10; val foreX1 = x0 + o28; val foreY0 = y0 + o24; val foreY1 = y0 + o76
                                        val eyeX0 = x0 + o28; val eyeX1 = x0 + o46; val eyeY0 = y0 + o18; val eyeY1 = y0 + o82
                                        val cheekX0 = x0 + o47; val cheekX1 = x0 + o66; val cheekY0 = y0 + o22; val cheekY1 = y0 + o78
                                        val skinFore = boxMean(intSkin, foreX0, foreY0, foreX1, foreY1)
                                        val skinEyes = boxMean(intSkin, eyeX0, eyeY0, eyeX1, eyeY1)
                                        val skinCheek = boxMean(intSkin, cheekX0, cheekY0, cheekX1, cheekY1)
                                        val yFore = boxMean(intY, foreX0, foreY0, foreX1, foreY1)
                                        if ((skinFore >= rSkinMinSub || (yFore >= 225.0 && skinFore >= 10.0)) &&
                                            skinEyes >= 24.0 * rLumaScale && skinCheek >= rSkinMinSub
                                        ) {
                                            val yEyes = boxMean(intY, eyeX0, eyeY0, eyeX1, eyeY1)
                                            val yCheek = boxMean(intY, cheekX0, cheekY0, cheekX1, cheekY1)
                                            val eyeBandDip = min(yFore, yCheek) - yEyes
                                            val yEyeA = boxMean(intY, eyeX0, eyeY0, eyeX1, y0 + o42)
                                            val yBridge = boxMean(intY, eyeX0, y0 + o43, eyeX1, y0 + o57)
                                            val yEyeB = boxMean(intY, eyeX0, y0 + o58, eyeX1, eyeY1)
                                            val bridgeContrast = yBridge - (yEyeA + yEyeB) * 0.5
                                            val bridgeMinContrast = yBridge - max(yEyeA, yEyeB)
                                            val eyeAsymmetry = abs(yEyeA - yEyeB)
                                            val yMouth = boxMean(intY, x0 + o67, y0 + o30, x0 + o80, y0 + o70)
                                            val mouthDip = yCheek - yMouth
                                            val yAbove = boxMean(intY, max(0, x0 - pad), rotCoreY0, x0 + o08, rotCoreY1)
                                            val topContrast = max(0.0, yFore - yAbove)
                                            if (eyeBandDip >= 6.5 * rLumaScale && bridgeContrast >= 4.5 * rLumaScale &&
                                                (bridgeMinContrast >= 0.0 || bridgeContrast >= 10.0 * rLumaScale) &&
                                                eyeAsymmetry <= 46.0 && mouthDip >= -1.0 * rLumaScale &&
                                                topContrast >= 8.0 * rLumaScale
                                            ) {
                                                val totalScore =
                                                    rotSkinCore * 1.8 * rInvLuma.coerceAtMost(1.8) +
                                                        rFrameCcw * 2.0 * rInvLuma.coerceAtMost(1.8) +
                                                        min(40.0, eyeBandDip * rInvLuma) * 4.5 +
                                                        min(35.0, bridgeContrast * rInvLuma) * 4.0 +
                                                        min(20.0, max(0.0, mouthDip * rInvLuma)) * 2.2 +
                                                        min(25.0, topContrast * rInvLuma) * 1.0 -
                                                        eyeAsymmetry * 0.45
                                                if (totalScore >= 280.0) {
                                                    candidates.add(
                                                        RawCand(
                                                            cx = (x0 + sideCells * 0.5f) * CELL,
                                                            cy = (y0 + sideCells * 0.5f) * CELL,
                                                            side = sidePx,
                                                            scaleIdx = scaleIdx,
                                                            orientRollIdx = 9,
                                                            score = totalScore,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    x0 += step
                }
                y0 += step
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // 4. Greedy Non-Maximum Suppression (IoU, nested containment, or close centers).
        candidates.sortByDescending { it.score }
        val kept = ArrayList<RawCand>(min(maxFaces * 2, candidates.size))
        for (c in candidates) {
            if (kept.size >= maxFaces * 2) break
            var overlaps = false
            for (k in kept) {
                if (boxesOverlap(c.cx, c.cy, c.side, k.cx, k.cy, k.side)) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) kept.add(c)
        }

        // 5. Refine each face box using full-resolution YYUV422 luma around the twin eye sockets so
        //    both face tracking box and firmware CPU Eye-AF (0x025a3c68) lock accurately onto the eyes.
        val refined = kept.map { cand ->
            refineWithIrisPair(
                frame, width, maxRows, strideBytes, activeTop, activeBot,
                cand.cx, cand.cy, cand.side, cand.scaleIdx, cand.orientRollIdx, cand.score, scaleEnableMask,
            )
        }.sortedByDescending { it.score }

        // 6. Post-refinement NMS in case two distinct coarse windows refined onto the same face.
        val deduped = ArrayList<DetectedFace>(min(maxFaces, refined.size))
        for (f in refined) {
            if (deduped.size >= maxFaces) break
            var overlaps = false
            for (k in deduped) {
                if (boxesOverlap(f.centerX, f.centerY, f.halfSize * 2f, k.centerX, k.centerY, k.halfSize * 2f)) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) deduped.add(f)
        }
        return deduped
    }

    private fun refineWithIrisPair(
        frame: ByteArray,
        width: Int,
        height: Int,
        strideBytes: Int,
        activeTop: Int,
        activeBot: Int,
        initCx: Float,
        initCy: Float,
        initSide: Float,
        initScaleIdx: Int,
        orientRollIdx: Int,
        rawScore: Double,
        scaleEnableMask: Int,
    ): DetectedFace {
        val half = initSide * 0.5f
        var cx = initCx
        var cy = initCy
        var scaleIdx = initScaleIdx
        var rollIdx = orientRollIdx

        if (orientRollIdx == 0) {
            val yMin = max(activeTop + 2, (initCy - half * 0.48f).roundToInt())
            val yMax = min(activeBot - 3, (initCy + half * 0.02f).roundToInt())
            // Restrict horizontal eye search to [0.12..0.54]*half so outer dark hair/veils are never mistaken for eyes.
            val lxMin = max(2, (initCx - half * 0.54f).roundToInt())
            val lxMax = min(width - 3, (initCx - half * 0.12f).roundToInt())
            val rxMin = max(2, (initCx + half * 0.12f).roundToInt())
            val rxMax = min(width - 3, (initCx + half * 0.54f).roundToInt())

            val leftEye = findDarkestIrisCenter(frame, strideBytes, lxMin, lxMax, yMin, yMax)
            val rightEye = findDarkestIrisCenter(frame, strideBytes, rxMin, rxMax, yMin, yMax)

            if (leftEye != null && rightEye != null) {
                val dx = rightEye.first - leftEye.first
                val dy = rightEye.second - leftEye.second
                val interEyeDist = sqrt(dx * dx + dy * dy)
                // Realistic inter-pupillary distance is ~0.28..0.50 of face box width.
                if (interEyeDist in (initSide * 0.24f)..(initSide * 0.56f) && abs(dy) <= interEyeDist * 0.45f) {
                    val eyeMidX = (leftEye.first + rightEye.first) * 0.5f
                    val eyeMidY = (leftEye.second + rightEye.second) * 0.5f
                    // Firmware CPU Eye-AF (0x025a3c68) searches at (cx +- 3/8*halfSize, cy - 3/8*halfSize).
                    val idealHalf = (interEyeDist / 0.75f).coerceIn(half * 0.75f, half * 1.30f).coerceIn(25.0f, 160.0f)
                    scaleIdx = closestScaleIdx(idealHalf, scaleEnableMask, initScaleIdx)
                    val snappedHalf = HALF_SIZE_Q7[scaleIdx] / 128.0f
                    cx = eyeMidX.coerceIn(snappedHalf * 0.5f, width - snappedHalf * 0.5f)
                    cy = (eyeMidY + snappedHalf * 0.34f).coerceIn(activeTop + snappedHalf * 0.5f, activeBot - snappedHalf * 0.5f)

                    val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    rollIdx = ((angleDeg / 30.0).roundToInt() % 12 + 12) % 12
                }
            }
        } else {
            // Portrait +-90 deg iris pair lies vertically along eye column:
            // rollIdx=3 (+90 deg CW, forehead at +X): eye column x in [cx - 0.02h .. cx + 0.48h], vector eye1(top)->eye2(bot) = +Y (+90 deg).
            // rollIdx=9 (-90 deg CCW, forehead at -X): eye column x in [cx - 0.48h .. cx + 0.02h], vector eye1(bot)->eye2(top) = -Y (-90 deg).
            val isCw = orientRollIdx == 3
            val xMin = if (isCw) {
                max(2, (initCx - half * 0.02f).roundToInt())
            } else {
                max(2, (initCx - half * 0.48f).roundToInt())
            }
            val xMax = if (isCw) {
                min(width - 3, (initCx + half * 0.48f).roundToInt())
            } else {
                min(width - 3, (initCx + half * 0.02f).roundToInt())
            }
            val upperYMin = max(activeTop + 2, (initCy - half * 0.54f).roundToInt())
            val upperYMax = min(activeBot - 3, (initCy - half * 0.12f).roundToInt())
            val lowerYMin = max(activeTop + 2, (initCy + half * 0.12f).roundToInt())
            val lowerYMax = min(activeBot - 3, (initCy + half * 0.54f).roundToInt())

            val upperEye = findDarkestIrisCenter(frame, strideBytes, xMin, xMax, upperYMin, upperYMax)
            val lowerEye = findDarkestIrisCenter(frame, strideBytes, xMin, xMax, lowerYMin, lowerYMax)

            if (upperEye != null && lowerEye != null) {
                val eye1 = if (isCw) upperEye else lowerEye
                val eye2 = if (isCw) lowerEye else upperEye
                val dx = eye2.first - eye1.first
                val dy = eye2.second - eye1.second
                val interEyeDist = sqrt(dx * dx + dy * dy)
                if (interEyeDist in (initSide * 0.24f)..(initSide * 0.56f) && abs(dx) <= interEyeDist * 0.45f) {
                    val eyeMidX = (eye1.first + eye2.first) * 0.5f
                    val eyeMidY = (eye1.second + eye2.second) * 0.5f
                    val idealHalf = (interEyeDist / 0.75f).coerceIn(half * 0.75f, half * 1.30f).coerceIn(25.0f, 160.0f)
                    scaleIdx = closestScaleIdx(idealHalf, scaleEnableMask, initScaleIdx)
                    val snappedHalf = HALF_SIZE_Q7[scaleIdx] / 128.0f
                    cx = (if (isCw) eyeMidX - snappedHalf * 0.34f else eyeMidX + snappedHalf * 0.34f)
                        .coerceIn(snappedHalf * 0.5f, width - snappedHalf * 0.5f)
                    cy = eyeMidY.coerceIn(activeTop + snappedHalf * 0.5f, activeBot - snappedHalf * 0.5f)

                    val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    rollIdx = ((angleDeg / 30.0).roundToInt() % 12 + 12) % 12
                }
            }
        }

        val finalHalf = HALF_SIZE_Q7[scaleIdx] / 128.0f
        val hwScore = (rawScore * 4.0).roundToInt().coerceIn(500, 3000)
        return DetectedFace(
            centerX = cx,
            centerY = cy,
            halfSize = finalHalf,
            scaleIdx = scaleIdx,
            rollIdx = rollIdx,
            yawIdx = 0,
            score = hwScore,
        )
    }

    private fun findDarkestIrisCenter(
        frame: ByteArray,
        strideBytes: Int,
        xMin: Int,
        xMax: Int,
        yMin: Int,
        yMax: Int,
    ): Pair<Float, Float>? {
        if (xMax - xMin < 4 || yMax - yMin < 4) return null
        var bestX = -1
        var bestY = -1
        var bestSum = Int.MAX_VALUE
        var y = yMin + 1
        while (y < yMax - 1) {
            var x = xMin + 1
            while (x < xMax - 1) {
                var s = 0
                for (dy in -1..1) {
                    val rowOff = (y + dy) * strideBytes
                    for (dx in -1..1) {
                        val px = x + dx
                        val off = rowOff + (px ushr 1) * 4 + (px and 1)
                        if (off < frame.size) s += frame[off].toInt() and 0xFF
                    }
                }
                if (s < bestSum) {
                    bestSum = s
                    bestX = x
                    bestY = y
                }
                x += 2
            }
            y += 2
        }
        return if (bestX >= 0) bestX.toFloat() to bestY.toFloat() else null
    }

    private fun closestScaleIdx(targetHalfPx: Float, scaleEnableMask: Int, initial: Int): Int {
        var bestIdx = initial
        var bestErr = Float.MAX_VALUE
        for (i in HALF_SIZE_Q7.indices) {
            if ((scaleEnableMask and (1 shl i)) == 0) continue
            val h = HALF_SIZE_Q7[i] / 128.0f
            val err = abs(h - targetHalfPx)
            if (err < bestErr) {
                bestErr = err
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun isUniformLetterboxRow(frame: ByteArray, rowOff: Int, width: Int): Boolean {
        if (rowOff + width * 2 > frame.size) return true
        val first = frame[rowOff].toInt() and 0xFF
        var x = 8
        while (x < width - 8) {
            val y = frame[rowOff + (x ushr 1) * 4 + (x and 1)].toInt() and 0xFF
            if (abs(y - first) > 4) return false
            x += 16
        }
        return true
    }

    /**
     * Smooth BT.601 YCbCr skin-likelihood score in `[0.0, 100.0]`.
     * Accepts natural human & warm Renaissance portrait skin tones while rejecting neutral
     * gray/white/black QR codes, cool blue/green backgrounds, and hyper-saturated color patches.
     */
    internal fun skinScoreYCbCr(y: Int, cb: Int, cr: Int): Double {
        if (y !in 18..255 || cb !in 0..255 || cr !in 0..255) return 0.0
        val cc = (cb shl 8) or cr
        val chroma = if (y >= 228) SKIN_CHROMA_HIGH[cc] else SKIN_CHROMA_NORM[cc]
        return (chroma * SKIN_LUMA_WEIGHT[y]).toDouble()
    }

    private fun boxesOverlap(cx1: Float, cy1: Float, s1: Float, cx2: Float, cy2: Float, s2: Float): Boolean {
        val dx = cx1 - cx2
        val dy = cy1 - cy2
        val maxSide = max(s1, s2)
        if (sqrt(dx * dx + dy * dy) < 0.65f * maxSide) return true
        val ax0 = cx1 - s1 * 0.5f
        val ay0 = cy1 - s1 * 0.5f
        val ax1 = cx1 + s1 * 0.5f
        val ay1 = cy1 + s1 * 0.5f
        val bx0 = cx2 - s2 * 0.5f
        val by0 = cy2 - s2 * 0.5f
        val bx1 = cx2 + s2 * 0.5f
        val by1 = cy2 + s2 * 0.5f
        val ix = max(0f, min(ax1, bx1) - max(ax0, bx0))
        val iy = max(0f, min(ay1, by1) - max(ay0, by0))
        val inter = ix * iy
        if (inter <= 0f) return false
        val a1 = s1 * s1
        val a2 = s2 * s2
        val minArea = min(a1, a2)
        val union = a1 + a2 - inter
        return (union > 0f && inter / union > 0.25f) || (minArea > 0f && inter / minArea > 0.40f)
    }
}
