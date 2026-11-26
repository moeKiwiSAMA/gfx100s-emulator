package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuOpticalPipelineTest {
    private val defaultLens = LensParameters(
        model = "gf-63mm-f2.8-sim",
        focalLengthMm = 63.0,
        aperture = 2.8,
        focusDistanceM = 10.0,
        transmission = 1.0,
        focusBreathing = 0.0,
    )

    private fun nominalExposure(iso: Int = 100, durationNanos: Long = 10_000_000L): Exposure =
        Exposure(startTick = 0L, durationNanos = durationNanos, iso = iso, lens = defaultLens)

    private fun geometry(
        width: Int = 64,
        height: Int = 64,
        bitDepth: Int = 16,
        bayer: BayerPattern = BayerPattern.RGGB,
    ): SensorGeometry = SensorGeometry(
        width = width,
        height = height,
        bitDepth = bitDepth,
        bayer = bayer,
        pitchMicrometres = 3.76,
    )

    private fun unpackLe16(buffer: ByteBuffer, count: Int): IntArray {
        val result = IntArray(count)
        var pos = 0
        for (i in 0 until count) {
            val lo = buffer.get(pos++).toInt() and 0xFF
            val hi = buffer.get(pos++).toInt() and 0xFF
            result[i] = lo or (hi shl 8)
        }
        return result
    }

    private fun drawGaussianBlob(
        plane: FloatArray,
        width: Int,
        height: Int,
        cx: Double,
        cy: Double,
        sigma: Double,
        amplitude: Float = 1.0f,
    ) {
        val invTwoSigmaSq = 1.0 / (2.0 * sigma * sigma)
        for (y in 0 until height) {
            val dy = y - cy
            val row = y * width
            for (x in 0 until width) {
                val dx = x - cx
                val v = (amplitude * kotlin.math.exp(-(dx * dx + dy * dy) * invTwoSigmaSq)).toFloat()
                plane[row + x] = maxOf(plane[row + x], v)
            }
        }
    }

    private fun quadrantCentroid(
        plane: FloatArray,
        width: Int,
        xRange: IntRange,
        yRange: IntRange,
    ): Pair<Double, Double> {
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (y in yRange) {
            val row = y * width
            for (x in xRange) {
                val w = plane[row + x].toDouble()
                sumW += w
                sumX += x * w
                sumY += y * w
            }
        }
        assertTrue(sumW > 1e-6, "Expected non-zero energy in region $xRange x $yRange")
        return (sumX / sumW) to (sumY / sumW)
    }

    @Test
    fun identityPassThroughPreservesUniformImageAndCenterAlignment() {
        val pipeline = CpuOpticalPipeline()
        val width = 32
        val height = 32
        val source = LinearRgbImage.uniform(width, height, r = 0.25f, g = 0.50f, b = 0.75f)
        val expected16 = intArrayOf(
            (0.25 * 65535.0).roundToInt(),
            (0.50 * 65535.0).roundToInt(),
            (0.75 * 65535.0).roundToInt(),
        )

        for (pattern in BayerPattern.entries) {
            for (mode in SamplingMode.entries) {
                val geom = geometry(width, height, bitDepth = 16, bayer = pattern)
                val dest = ByteBuffer.allocate(width * height * 2)
                val written = pipeline.render(
                    source = source,
                    geometry = geom,
                    exposure = nominalExposure(),
                    profile = OpticalLensProfile.IDENTITY,
                    ibis = IbisShiftState.ZERO,
                    noiseConfig = CmosNoiseConfig.NOISELESS,
                    destination = dest,
                    samplingMode = mode,
                )
                assertEquals(width * height * 2, written)
                val samples = unpackLe16(dest, width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val ch = CmosNoiseModel.bayerChannel(pattern, x, y)
                        assertEquals(
                            expected16[ch],
                            samples[y * width + x],
                            "Mismatch for $pattern/$mode at ($x,$y)",
                        )
                    }
                }
            }
        }

        // Verify center alignment with a symmetric center crosshair blob.
        val centered = LinearRgbImage.allocate(width, height)
        val cx = (width - 1) * 0.5
        val cy = (height - 1) * 0.5
        drawGaussianBlob(centered.red, width, height, cx, cy, sigma = 1.5)
        drawGaussianBlob(centered.green, width, height, cx, cy, sigma = 1.5)
        drawGaussianBlob(centered.blue, width, height, cx, cy, sigma = 1.5)

        val rgbOut = pipeline.renderLinearRgb(centered, width, height, OpticalLensProfile.IDENTITY)
        val (outCx, outCy) = quadrantCentroid(rgbOut.green, width, 0 until width, 0 until height)
        assertEquals(cx, outCx, 1e-6)
        assertEquals(cy, outCy, 1e-6)
    }

    @Test
    fun zoomIncreasesCentralMagnificationProportionally() {
        val pipeline = CpuOpticalPipeline()
        val size = 80
        val cx = (size - 1) * 0.5
        val cy = (size - 1) * 0.5
        val src = LinearRgbImage.allocate(size, size)

        // Bright central square of half-width 8 pixels (spans 16 px wide).
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (abs(x - cx) <= 8.0 && abs(y - cy) <= 8.0) {
                    val idx = y * size + x
                    src.red[idx] = 1.0f
                    src.green[idx] = 1.0f
                    src.blue[idx] = 1.0f
                }
            }
        }

        val out1x = pipeline.renderLinearRgb(
            source = src,
            dstWidth = size,
            dstHeight = size,
            profile = OpticalLensProfile(focalLengthMm = 50.0, referenceFocalLengthMm = 50.0),
        )
        val out2x = pipeline.renderLinearRgb(
            source = src,
            dstWidth = size,
            dstHeight = size,
            profile = OpticalLensProfile(focalLengthMm = 100.0, referenceFocalLengthMm = 50.0),
        )

        val lit1x = out1x.green.count { it > 0.5f }
        val lit2x = out2x.green.count { it > 0.5f }
        // 2x linear magnification increases area by ~4x (within edge-quantization tolerance).
        val areaRatio = lit2x.toDouble() / lit1x.toDouble()
        assertTrue(areaRatio in 3.6..4.4, "Expected ~4x area magnification at 2x zoom, got $areaRatio")

        // Verify focus breathing at close focus also shifts magnification smoothly.
        val breathingProfile = OpticalLensProfile(
            focalLengthMm = 50.0,
            referenceFocalLengthMm = 50.0,
            focusDistanceM = 0.25,
            focusBreathing = 0.40,
        )
        assertTrue(breathingProfile.zoomScale() > 1.07)
        val outBreath = pipeline.renderLinearRgb(src, size, size, breathingProfile)
        assertTrue(outBreath.green.count { it > 0.5f } > lit1x)
        assertTrue(outBreath.green.sum() > out1x.green.sum() * 1.10f)
    }

    @Test
    fun barrelAndPincushionDistortionMoveCornersInwardAndOutwardSymmetrically() {
        val pipeline = CpuOpticalPipeline()
        val size = 96
        val cx = (size - 1) * 0.5
        val cy = (size - 1) * 0.5
        val offset = 28.0
        val src = LinearRgbImage.allocate(size, size)

        // Four symmetric blobs placed equidistantly in all four corners.
        val corners = listOf(
            (cx - offset) to (cy - offset),
            (cx + offset) to (cy - offset),
            (cx - offset) to (cy + offset),
            (cx + offset) to (cy + offset),
        )
        for ((mx, my) in corners) {
            drawGaussianBlob(src.red, size, size, mx, my, sigma = 1.6)
            drawGaussianBlob(src.green, size, size, mx, my, sigma = 1.6)
            drawGaussianBlob(src.blue, size, size, mx, my, sigma = 1.6)
        }

        val identityOut = pipeline.renderLinearRgb(src, size, size, OpticalLensProfile.IDENTITY)
        val barrelOut = pipeline.renderLinearRgb(src, size, size, OpticalLensProfile(k1 = -0.25))
        val pincushionOut = pipeline.renderLinearRgb(src, size, size, OpticalLensProfile(k1 = +0.25))

        val half = size / 2
        val quadrants = listOf(
            (0 until half) to (0 until half),
            (half until size) to (0 until half),
            (0 until half) to (half until size),
            (half until size) to (half until size),
        )

        fun cornerRadii(img: LinearRgbImage): List<Double> = quadrants.map { (xr, yr) ->
            val (qx, qy) = quadrantCentroid(img.green, size, xr, yr)
            hypot(qx - cx, qy - cy)
        }

        val rId = cornerRadii(identityOut)
        val rBarrel = cornerRadii(barrelOut)
        val rPin = cornerRadii(pincushionOut)

        for (i in 0 until 4) {
            assertTrue(
                rBarrel[i] < rId[i] - 2.0,
                "Barrel distortion (k1<0) must move corner $i inward: barrel=${rBarrel[i]} vs id=${rId[i]}",
            )
            assertTrue(
                rPin[i] > rId[i] + 2.0,
                "Pincushion distortion (k1>0) must move corner $i outward: pin=${rPin[i]} vs id=${rId[i]}",
            )
            // Verify 4-way radial symmetry across all corners.
            assertEquals(rBarrel[0], rBarrel[i], 1e-4, "Barrel corners must remain symmetric")
            assertEquals(rPin[0], rPin[i], 1e-4, "Pincushion corners must remain symmetric")
        }

        // Verify tangential distortion (p1/p2) shifts off-axis centroids deterministically.
        val tangOut = pipeline.renderLinearRgb(src, size, size, OpticalLensProfile(p1 = 0.04, p2 = -0.03))
        val rTang = cornerRadii(tangOut)
        assertTrue(abs(rTang[0] - rTang[3]) > 0.5, "Tangential p1/p2 should break diagonal symmetry")
    }

    @Test
    fun lateralChromaticAberrationShiftsRedAndBlueInOppositeRadialDirections() {
        val pipeline = CpuOpticalPipeline()
        val width = 96
        val height = 64
        val cx = (width - 1) * 0.5
        val cy = (height - 1) * 0.5
        val src = LinearRgbImage.allocate(width, height)

        // Bright achromatic white marker on the right side of the frame.
        val markerX = cx + 26.0
        drawGaussianBlob(src.red, width, height, markerX, cy, sigma = 1.6)
        drawGaussianBlob(src.green, width, height, markerX, cy, sigma = 1.6)
        drawGaussianBlob(src.blue, width, height, markerX, cy, sigma = 1.6)

        val caProfile = OpticalLensProfile(caRedScale = 1.06, caBlueScale = 0.94)
        val out = pipeline.renderLinearRgb(src, width, height, caProfile)

        val (rx, _) = quadrantCentroid(out.red, width, (width / 2) until width, 0 until height)
        val (gx, _) = quadrantCentroid(out.green, width, (width / 2) until width, 0 until height)
        val (bx, _) = quadrantCentroid(out.blue, width, (width / 2) until width, 0 until height)

        val rRad = rx - cx
        val gRad = gx - cx
        val bRad = bx - cx
        assertTrue(rRad > gRad + 1.0, "Red must shift radially outward relative to Green ($rRad vs $gRad)")
        assertTrue(bRad < gRad - 1.0, "Blue must shift radially inward relative to Green ($bRad vs $gRad)")
        // Opposite shifts of equal magnitude (+6% vs -6%).
        assertEquals(rRad - gRad, gRad - bRad, 0.25)

        // Verify the same dispersion survives end-to-end RGGB Bayer packing.
        val geom = geometry(width, height, bitDepth = 16, bayer = BayerPattern.RGGB)
        val dest = ByteBuffer.allocate(width * height * 2)
        pipeline.render(
            source = src,
            geometry = geom,
            exposure = nominalExposure(),
            profile = caProfile,
            noiseConfig = CmosNoiseConfig.NOISELESS,
            destination = dest,
        )
        val samples = unpackLe16(dest, width * height)
        var rSumX = 0.0
        var rSumW = 0.0
        var bSumX = 0.0
        var bSumW = 0.0
        for (y in 0 until height) {
            for (x in (width / 2) until width) {
                val v = samples[y * width + x].toDouble()
                when (CmosNoiseModel.bayerChannel(BayerPattern.RGGB, x, y)) {
                    CmosNoiseModel.CHANNEL_RED -> {
                        rSumX += x * v
                        rSumW += v
                    }
                    CmosNoiseModel.CHANNEL_BLUE -> {
                        bSumX += x * v
                        bSumW += v
                    }
                }
            }
        }
        assertTrue(rSumX / rSumW > bSumX / bSumW + 2.0, "RGGB Bayer output must preserve R/B lateral CA separation")
    }

    @Test
    fun vignettingDarkensCornersMonotonicallyRelativeToCenter() {
        val pipeline = CpuOpticalPipeline()
        val size = 64
        val src = LinearRgbImage.uniform(size, size, 1.0f, 1.0f, 1.0f)

        val profiles = listOf(
            OpticalLensProfile(focalLengthMm = 32.0, referenceFocalLengthMm = 32.0, cos4Power = 4.0),
            OpticalLensProfile(mechanicalVignetting = 0.45),
            OpticalLensProfile(focalLengthMm = 45.0, referenceFocalLengthMm = 45.0, cos4Power = 4.0, mechanicalVignetting = 0.30),
        )

        for (profile in profiles) {
            val out = pipeline.renderLinearRgb(src, size, size, profile)
            val centerIdx = (size / 2) * size + (size / 2)
            val centerVal = out.green[centerIdx]
            assertTrue(centerVal > 0.99f, "Center should remain near unity brightness, got $centerVal")

            // Walk diagonally from center (32, 32) to bottom-right corner (63, 63).
            var prev = centerVal
            for (step in 1 until (size / 2)) {
                val x = (size / 2) + step
                val y = (size / 2) + step
                val cur = out.green[y * size + x]
                assertTrue(cur < prev, "Expected strict monotonic falloff at step=$step: $cur >= $prev")
                prev = cur
            }
            val tl = out.green[0]
            val tr = out.green[size - 1]
            val bl = out.green[(size - 1) * size]
            val br = out.green[(size - 1) * size + (size - 1)]
            assertEquals(tl, tr, 1e-6f)
            assertEquals(tl, bl, 1e-6f)
            assertEquals(tl, br, 1e-6f)
            assertTrue(br < centerVal * 0.80f, "Corner should be noticeably darkened ($br vs $centerVal)")
        }
    }

    @Test
    fun ibisShiftAndRollTranslateAndRotateDeterministically() {
        val pipeline = CpuOpticalPipeline()
        val size = 64
        val cx = (size - 1) * 0.5
        val cy = (size - 1) * 0.5
        val src = LinearRgbImage.allocate(size, size)

        // Place a bright marker to the right of optical center at (cx + 12, cy).
        val startX = cx + 12.0
        val startY = cy
        drawGaussianBlob(src.red, size, size, startX, startY, sigma = 1.5)
        drawGaussianBlob(src.green, size, size, startX, startY, sigma = 1.5)
        drawGaussianBlob(src.blue, size, size, startX, startY, sigma = 1.5)

        // Translation test: (+5.0, -3.5) pixels.
        val shifted = pipeline.renderLinearRgb(
            source = src,
            dstWidth = size,
            dstHeight = size,
            ibis = IbisShiftState(shiftXPixels = 5.0, shiftYPixels = -3.5),
        )
        val (sx, sy) = quadrantCentroid(shifted.green, size, 0 until size, 0 until size)
        assertEquals(startX + 5.0, sx, 0.05)
        assertEquals(startY - 3.5, sy, 0.05)

        // 90-degree clockwise roll (+pi/2) moves (cx + 12, cy) -> (cx, cy + 12).
        val rolled = pipeline.renderLinearRgb(
            source = src,
            dstWidth = size,
            dstHeight = size,
            ibis = IbisShiftState(rollRadians = Math.PI * 0.5),
        )
        val (rx, ry) = quadrantCentroid(rolled.green, size, 0 until size, 0 until size)
        assertEquals(cx, rx, 0.05)
        assertEquals(cy + 12.0, ry, 0.05)

        // Workspace reuse determinism check on full Bayer render.
        val geom = geometry(size, size, bitDepth = 16, bayer = BayerPattern.RGGB)
        val ibis = IbisShiftState(shiftXPixels = 2.25, shiftYPixels = -1.75, rollRadians = 0.12)
        val ws = OpticalFrameWorkspace()
        val buf1 = ByteBuffer.allocate(size * size * 2)
        val buf2 = ByteBuffer.allocate(size * size * 2)
        pipeline.render(src, geom, nominalExposure(), OpticalLensProfile.IDENTITY, ibis, CmosNoiseConfig.NOISELESS, buf1, workspace = ws)
        pipeline.render(src, geom, nominalExposure(), OpticalLensProfile.IDENTITY, ibis, CmosNoiseConfig.NOISELESS, buf2, workspace = ws)
        assertContentEquals(buf1.array(), buf2.array())
    }

    @Test
    fun depthBufferCocBlursOutOfFocusPlaneWhileKeepingInFocusPlaneSharp() {
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 12)
        val width = 96
        val height = 48
        val src = LinearRgbImage.allocate(width, height)
        val depth = FloatArray(width * height)

        // Left half: in-focus at 2.0m. Right half: far out-of-focus background at 15.0m.
        // Both halves carry identical high-frequency 2-pixel alternating vertical stripes.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val stripe = if ((x / 2) % 2 == 0) 1.0f else 0.0f
                src.red[row + x] = stripe
                src.green[row + x] = stripe
                src.blue[row + x] = stripe
                depth[row + x] = if (x < width / 2) 2.0f else 15.0f
            }
        }

        val fastLens = OpticalLensProfile(
            focalLengthMm = 80.0,
            referenceFocalLengthMm = 80.0,
            fNumber = 1.4,
            focusDistanceM = 2.0,
            sensorWidthMm = 43.8,
        )
        val out = pipeline.renderLinearRgb(src, width, height, fastLens, depthMeters = depth)

        // Measure stripe contrast (max - min) in interior of left (in-focus) vs right (out-of-focus) halves.
        fun stripeContrast(img: LinearRgbImage, xRange: IntRange, yRange: IntRange): Float {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (y in yRange) {
                val row = y * width
                for (x in xRange) {
                    val v = img.green[row + x]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            return hi - lo
        }

        val leftContrast = stripeContrast(out, 8 until 38, 8 until (height - 8))
        val rightContrast = stripeContrast(out, 58 until (width - 8), 8 until (height - 8))
        assertTrue(leftContrast > 0.99f, "In-focus plane must remain razor sharp, got contrast=$leftContrast")
        assertTrue(rightContrast < 0.35f, "Out-of-focus plane must be heavily blurred, got contrast=$rightContrast")

        // Stopping down to f/16 reduces CoC and preserves far more background contrast than f/1.4.
        val stoppedDown = fastLens.copy(fNumber = 16.0)
        val outStopped = pipeline.renderLinearRgb(src, width, height, stoppedDown, depthMeters = depth)
        val stoppedRightContrast = stripeContrast(outStopped, 58 until (width - 8), 8 until (height - 8))
        assertTrue(
            stoppedRightContrast > rightContrast + 0.30f,
            "Stopped-down aperture should increase depth of field ($stoppedRightContrast vs $rightContrast)",
        )

        // Longitudinal CA shifts R vs B defocus differently when enabled.
        val locaLens = fastLens.copy(fNumber = 1.7, focusDistanceM = 2.0, longitudinalCaDefocusMm = 12.0)
        val outLoca = pipeline.renderLinearRgb(src, width, height, locaLens, depthMeters = depth)
        val rBlur = outLoca.red[24 * width + 72]
        val bBlur = outLoca.blue[24 * width + 72]
        assertTrue(abs(rBlur - bBlur) > 1e-3f, "Longitudinal CA should produce distinct R/B defocus ($rBlur vs $bBlur)")
    }

    @Test
    fun bayerNoiseIsDeterministicScalesWithIsoAndSignalClampsFullWellAndRespectsBlackLevel() {
        val pipeline = CpuOpticalPipeline()
        val width = 64
        val height = 64
        val pixels = width * height
        val geom14 = geometry(width, height, bitDepth = 14, bayer = BayerPattern.RGGB)
        val max14 = (1 shl 14) - 1 // 16383

        // 1. Bit-exact determinism for same seed; different output for different seed or frameIndex.
        val midGray = LinearRgbImage.uniform(width, height, 0.2f, 0.2f, 0.2f)
        val cfgSeedA = CmosNoiseConfig(seed = 0x51A7_C0DE_1234_5678L, enableNoise = true, blackLevel = 512)
        val cfgSeedB = cfgSeedA.copy(seed = 0x51A7_C0DE_1234_5679L)

        val bufA1 = ByteBuffer.allocate(pixels * 2)
        val bufA2 = ByteBuffer.allocate(pixels * 2)
        val bufB = ByteBuffer.allocate(pixels * 2)
        val bufFrame1 = ByteBuffer.allocate(pixels * 2)

        pipeline.render(midGray, geom14, nominalExposure(iso = 400), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, bufA1, frameIndex = 0L)
        pipeline.render(midGray, geom14, nominalExposure(iso = 400), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, bufA2, frameIndex = 0L)
        pipeline.render(midGray, geom14, nominalExposure(iso = 400), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedB, bufB, frameIndex = 0L)
        pipeline.render(midGray, geom14, nominalExposure(iso = 400), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, bufFrame1, frameIndex = 1L)

        assertContentEquals(bufA1.array(), bufA2.array())
        assertFalse(bufA1.array().contentEquals(bufB.array()))
        assertFalse(bufA1.array().contentEquals(bufFrame1.array()))

        // 2. Noise variance increases monotonically with ISO (both mid-tone and dark read noise).
        fun sampleVariance(samples: IntArray): Double {
            val mean = samples.average()
            var acc = 0.0
            for (s in samples) {
                val d = s - mean
                acc += d * d
            }
            return acc / samples.size
        }

        val dimGray = LinearRgbImage.uniform(width, height, 0.05f, 0.05f, 0.05f)
        val iso100Buf = ByteBuffer.allocate(pixels * 2)
        val iso400Buf = ByteBuffer.allocate(pixels * 2)
        val iso1600Buf = ByteBuffer.allocate(pixels * 2)

        pipeline.render(dimGray, geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, iso100Buf)
        pipeline.render(dimGray, geom14, nominalExposure(iso = 400), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, iso400Buf)
        pipeline.render(dimGray, geom14, nominalExposure(iso = 1600), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, iso1600Buf)

        val var100 = sampleVariance(unpackLe16(iso100Buf, pixels))
        val var400 = sampleVariance(unpackLe16(iso400Buf, pixels))
        val var1600 = sampleVariance(unpackLe16(iso1600Buf, pixels))
        assertTrue(var400 > var100 * 5.0, "ISO 400 variance ($var400) must exceed ISO 100 ($var100)")
        assertTrue(var1600 > var400 * 5.0, "ISO 1600 variance ($var1600) must exceed ISO 400 ($var400)")

        // 3. Photon shot noise variance scales proportionally with signal electrons (read noise = 0).
        val shotOnlyCfg = CmosNoiseConfig(seed = 42L, enableNoise = true, readNoiseBaseElectrons = 0.0, blackLevel = 512)
        val lowSigBuf = ByteBuffer.allocate(pixels * 2)
        val highSigBuf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(LinearRgbImage.uniform(width, height, 0.05f, 0.05f, 0.05f), geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, shotOnlyCfg, lowSigBuf)
        pipeline.render(LinearRgbImage.uniform(width, height, 0.50f, 0.50f, 0.50f), geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, shotOnlyCfg, highSigBuf)
        val varLow = sampleVariance(unpackLe16(lowSigBuf, pixels))
        val varHigh = sampleVariance(unpackLe16(highSigBuf, pixels))
        val ratio = varHigh / varLow
        assertTrue(ratio in 7.0..13.0, "10x signal should yield ~10x shot noise variance, got $ratio")

        // 4. Black level pedestal respected on dark frames (exact when noiseless; unbiased mean when noisy).
        val dark = LinearRgbImage.uniform(width, height, 0.0f, 0.0f, 0.0f)
        val darkCleanCfg = CmosNoiseConfig(seed = 1L, enableNoise = false, blackLevel = 512)
        val darkCleanBuf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(dark, geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, darkCleanCfg, darkCleanBuf)
        assertTrue(unpackLe16(darkCleanBuf, pixels).all { it == 512 })

        val darkNoisyBuf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(dark, geom14, nominalExposure(iso = 1600), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, darkNoisyBuf)
        val darkNoisySamples = unpackLe16(darkNoisyBuf, pixels)
        assertEquals(512.0, darkNoisySamples.average(), 5.0)
        assertTrue(sampleVariance(darkNoisySamples) > 10.0, "Dark frame at ISO 1600 should exhibit read noise")

        // 5. Full-well saturation clamp at 14-bit maxCode and under extended low ISO (photodiode full-well).
        val superBright = LinearRgbImage.uniform(width, height, 20.0f, 20.0f, 20.0f)
        val satNoisyBuf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(superBright, geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, cfgSeedA, satNoisyBuf)
        val satNoisySamples = unpackLe16(satNoisyBuf, pixels)
        assertTrue(satNoisySamples.all { it in (max14 - 8)..max14 }, "Noisy saturated 14-bit pixels must stay clamped <= $max14")

        val satCleanCfg = CmosNoiseConfig(enableNoise = false, blackLevel = 512, baseIso = 100)
        val sat14Buf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(superBright, geom14, nominalExposure(iso = 100), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, satCleanCfg, sat14Buf)
        assertTrue(unpackLe16(sat14Buf, pixels).all { it == max14 }, "Saturated 14-bit pixels must clamp to $max14")

        val lowIsoSatBuf = ByteBuffer.allocate(pixels * 2)
        pipeline.render(superBright, geom14, nominalExposure(iso = 50), OpticalLensProfile.IDENTITY, IbisShiftState.ZERO, satCleanCfg, lowIsoSatBuf)
        val expectedPhotodiodeClamp = (512.0 + 0.5 * (max14 - 512)).roundToInt()
        assertTrue(
            unpackLe16(lowIsoSatBuf, pixels).all { it == expectedPhotodiodeClamp },
            "Photodiode full-well must clamp before ISO 50 gain (expected $expectedPhotodiodeClamp)",
        )
    }

    @Test
    fun shapedBokehProducesPolygonalFiveBladeCornerCatsEyeAndVintageRimHighlight() {
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 14)
        val size = 96
        val cx = size / 2
        val cy = size / 2

        // Single bright background point light at center (depth 18m, focused at 1.2m)
        val centerSrc = LinearRgbImage.allocate(size, size)
        val depth = FloatArray(size * size) { 18.0f }
        centerSrc.red[cy * size + cx] = 10.0f
        centerSrc.green[cy * size + cx] = 10.0f
        centerSrc.blue[cy * size + cx] = 10.0f

        val pentagonStopped = OpticalLensProfile(
            focalLengthMm = 110.0,
            referenceFocalLengthMm = 110.0,
            fNumber = 4.0,
            minFNumber = 1.4,
            focusDistanceM = 0.55,
            apertureBladeCount = 5,
            apertureBladeRoundness = 0.0,
        )
        val roundedNine = pentagonStopped.copy(
            apertureBladeCount = 9,
            apertureBladeRoundness = 0.95,
        )
        val outPent = pipeline.renderLinearRgb(centerSrc, size, size, pentagonStopped, depthMeters = depth)
        val outRound = pipeline.renderLinearRgb(centerSrc, size, size, roundedNine, depthMeters = depth)

        // Sample ring near polygon boundary (r = 85% of CoC radius) across 40 angles:
        // 5-blade straight polygon has strong angular energy modulation vs nearly flat rounded 9-blade!
        val cocPx = pentagonStopped.circleOfConfusionRadiusPixels(18.0, size).coerceAtMost(14.0)
        assertTrue(cocPx >= 6.0, "Expected well-resolved bokeh disk radius >= 6 px, got $cocPx")
        val probeR = cocPx * 0.85
        fun ringVariance(plane: FloatArray): Double {
            val vals = DoubleArray(40) { k ->
                val theta = (2.0 * Math.PI * k) / 40.0
                val px = (cx + kotlin.math.cos(theta) * probeR).roundToInt().coerceIn(0, size - 1)
                val py = (cy + kotlin.math.sin(theta) * probeR).roundToInt().coerceIn(0, size - 1)
                plane[py * size + px].toDouble()
            }
            val mean = vals.average()
            return vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        val pentVar = ringVariance(outPent.green)
        val roundVar = ringVariance(outRound.green)
        assertTrue(
            pentVar > roundVar * 1.5 && pentVar > 1e-4,
            "Stopped-down 5-blade polygonal bokeh must show stronger angular modulation than rounded 9-blade ($pentVar vs $roundVar)",
        )

        // Corner cat's-eye clipping at wide open f/2.0 compresses bokeh radially toward the frame corner
        val cornerSrc = LinearRgbImage.allocate(size, size)
        val kx = 80
        val ky = 80
        cornerSrc.red[ky * size + kx] = 10.0f
        cornerSrc.green[ky * size + kx] = 10.0f
        cornerSrc.blue[ky * size + kx] = 10.0f
        val catsEyeProfile = OpticalLensProfile(
            focalLengthMm = 110.0,
            referenceFocalLengthMm = 110.0,
            fNumber = 2.0,
            minFNumber = 2.0,
            focusDistanceM = 0.80,
            bokehCatsEye = 0.75,
        )
        val outCats = pipeline.renderLinearRgb(cornerSrc, size, size, catsEyeProfile, depthMeters = depth)
        var sumX = 0.0
        var sumY = 0.0
        var energy = 0.0
        for (y in (ky - 14)..minOf(size - 1, ky + 14)) {
            for (x in (kx - 14)..minOf(size - 1, kx + 14)) {
                val w = outCats.green[y * size + x].toDouble()
                if (w > 1e-4) {
                    sumX += x * w
                    sumY += y * w
                    energy += w
                }
            }
        }
        assertTrue(energy > 0.0)
        val meanX = sumX / energy
        val meanY = sumY / energy
        val invSqrt2 = 1.0 / kotlin.math.sqrt(2.0)
        var radialSpread = 0.0
        var tangSpread = 0.0
        for (y in (ky - 14)..minOf(size - 1, ky + 14)) {
            for (x in (kx - 14)..minOf(size - 1, kx + 14)) {
                val w = outCats.green[y * size + x].toDouble()
                if (w > 1e-4) {
                    val dx = x - meanX
                    val dy = y - meanY
                    radialSpread += abs((dx + dy) * invSqrt2) * w
                    tangSpread += abs((-dx + dy) * invSqrt2) * w
                }
            }
        }
        assertTrue(
            tangSpread / energy > radialSpread / energy * 1.15,
            "Corner cat's-eye must clip radial extent relative to tangential extent (tang=${tangSpread / energy}, rad=${radialSpread / energy})",
        )

        // Vintage soap-bubble rim highlight makes outer annulus brighter than core vs plain disk
        val plainDisk = OpticalLensProfile(
            focalLengthMm = 110.0,
            referenceFocalLengthMm = 110.0,
            fNumber = 2.0,
            minFNumber = 2.0,
            focusDistanceM = 0.75,
        )
        val bubbleProfile = plainDisk.copy(bokehRimHighlight = 0.50)
        val outPlain = pipeline.renderLinearRgb(centerSrc, size, size, plainDisk, depthMeters = depth)
        val outBubble = pipeline.renderLinearRgb(centerSrc, size, size, bubbleProfile, depthMeters = depth)
        val rimStep = (plainDisk.circleOfConfusionRadiusPixels(18.0, size).coerceAtMost(14.0) * 0.85).roundToInt().coerceAtLeast(3)
        val plainRatio = outPlain.green[cy * size + (cx + rimStep)] / outPlain.green[cy * size + cx].coerceAtLeast(1e-6f)
        val bubbleRatio = outBubble.green[cy * size + (cx + rimStep)] / outBubble.green[cy * size + cx].coerceAtLeast(1e-6f)
        assertTrue(
            bubbleRatio > plainRatio * 1.25f,
            "Vintage bokehRimHighlight must boost outer ring relative to core ($bubbleRatio vs $plainRatio, rimStep=$rimStep)",
        )
    }

    @Test
    fun wideOpenContrastDropWarmAmberFlareAndLoCaStopDownBehavePhysically() {
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 12)
        val size = 80

        // 1. Wide-open contrast drop softens micro-contrast at f/1.4 and recovers crisply by f/8
        val grating = LinearRgbImage.allocate(size, size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if ((x / 2) % 2 == 0) 0.9f else 0.05f
                val idx = y * size + x
                grating.red[idx] = v; grating.green[idx] = v; grating.blue[idx] = v
            }
        }
        val wideOpen = OpticalLensProfile(fNumber = 1.4, minFNumber = 1.4, wideOpenContrastDrop = 0.30)
        val stoppedDown = wideOpen.copy(fNumber = 8.0)
        val outWide = pipeline.renderLinearRgb(grating, size, size, wideOpen)
        val outStop = pipeline.renderLinearRgb(grating, size, size, stoppedDown)
        fun contrast(img: LinearRgbImage): Float {
            var lo = 1e9f
            var hi = -1e9f
            val row = (size / 2) * size
            for (x in 16 until (size - 16)) {
                val v = img.green[row + x]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return hi - lo
        }
        assertTrue(
            contrast(outStop) > contrast(outWide) + 0.10f,
            "Stopping down must recover micro-contrast vs wide-open spherical glow (${contrast(outStop)} vs ${contrast(outWide)})",
        )

        // 2. Warm amber flare injects opposite-axis ghosting vs clean Nano GI, and short-circuits on dark scenes
        val backlit = LinearRgbImage.allocate(size, size)
        for (y in 12..18) {
            for (x in 12..18) {
                val idx = y * size + x
                backlit.red[idx] = 4.0f; backlit.green[idx] = 4.0f; backlit.blue[idx] = 4.0f
            }
        }
        val takumarFlare = OpticalLensProfile(flareStrength = 0.55, flareTintR = 1.15, flareTintG = 0.85, flareTintB = 0.45)
        val nanoGiClean = OpticalLensProfile(flareStrength = 0.05, flareTintR = 0.95, flareTintG = 1.0, flareTintB = 1.05)
        val outWarm = pipeline.renderLinearRgb(backlit, size, size, takumarFlare)
        val outClean = pipeline.renderLinearRgb(backlit, size, size, nanoGiClean)
        // Opposite-axis ghost lies in bottom-right quadrant around (56, 56)
        val ghostIdx = 56 * size + 56
        assertTrue(
            outWarm.red[ghostIdx] > outClean.red[ghostIdx] * 5.0f && outWarm.red[ghostIdx] > outWarm.blue[ghostIdx] * 2.0f,
            "Takumar warm flare must produce strong amber opposite-axis ghost (warmR=${outWarm.red[ghostIdx]}, warmB=${outWarm.blue[ghostIdx]}, cleanR=${outClean.red[ghostIdx]})",
        )

        // Low-luminance scene below 0.85 threshold must short-circuit with zero flare addition
        val dimScene = LinearRgbImage.uniform(size, size, 0.4f, 0.4f, 0.4f)
        val outDim = pipeline.renderLinearRgb(dimScene, size, size, takumarFlare)
        assertEquals(0.4f, outDim.red[ghostIdx], 1e-6f)

        // 3. Longitudinal CA diminishes smoothly as lens stops down (effectiveLongitudinalCaDefocusMm)
        val locaWide = OpticalLensProfile(focalLengthMm = 85.0, referenceFocalLengthMm = 85.0, fNumber = 1.4, minFNumber = 1.4, longitudinalCaDefocusMm = 10.0)
        val locaStop = locaWide.copy(fNumber = 8.0)
        assertTrue(locaWide.effectiveLongitudinalCaDefocusMm() > locaStop.effectiveLongitudinalCaDefocusMm() * 5.0)
    }

    @Test
    fun clampBorderEliminatesBarrelDistortionChromaFringeWhilePreservingImageCircleCutoff() {
        // BUG-V3-7-6: GF barrel distortion + lateral CA with BorderMode.CLAMP must produce zero false RGB
        // rainbow fringe on top/bottom/left/right 16:9 LiveView borders, while keeping physical imageCircleMm cutoff intact.
        val pipeline = CpuOpticalPipeline()
        val width = 128
        val height = 72
        val neutral = LinearRgbImage.uniform(width, height, r = 0.5f, g = 0.5f, b = 0.5f)
        val gfBarrelCa = OpticalLensProfile(
            focalLengthMm = 32.0,
            referenceFocalLengthMm = 32.0,
            k1 = -0.06,
            caRedScale = 1.008,
            caBlueScale = 0.992,
            cos4Power = 0.0,
            mechanicalVignetting = 0.0,
            imageCircleMm = 60.0,
        )

        val clamped = pipeline.renderLinearRgb(
            source = neutral,
            dstWidth = width,
            dstHeight = height,
            profile = gfBarrelCa,
            borderMode = BorderMode.CLAMP,
        )
        var maxEdgeChroma = 0.0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val dr = abs(clamped.red[idx] - clamped.green[idx])
                val db = abs(clamped.blue[idx] - clamped.green[idx])
                maxEdgeChroma = maxOf(maxEdgeChroma, dr, db)
            }
        }
        assertTrue(
            maxEdgeChroma < 1e-4f,
            "BorderMode.CLAMP must produce zero false RGB rainbow fringe on neutral borders (maxChroma=$maxEdgeChroma)",
        )

        // Smaller APS-C image circle (28.4mm on 43.8x32.9mm GFX sensor) must still darken corners to 0.0
        val apscProfile = gfBarrelCa.copy(imageCircleMm = 28.4)
        val apscOut = pipeline.renderLinearRgb(
            source = neutral,
            dstWidth = width,
            dstHeight = height,
            profile = apscProfile,
            borderMode = BorderMode.CLAMP,
        )
        assertEquals(0.0f, apscOut.green[0], 1e-6f, "Physical imageCircleMm baffle must still black out corner (0,0)")
        assertTrue(apscOut.green[(height / 2) * width + (width / 2)] > 0.45f, "Center inside image circle must remain lit")
    }

    @Test
    fun rejectsInvalidConfigurationsAndOversizedRequests() {
        assertFailsWith<IllegalArgumentException> { OpticalLensProfile(focalLengthMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { OpticalLensProfile(caRedScale = -1.0) }
        assertFailsWith<IllegalArgumentException> { OpticalLensProfile(mechanicalVignetting = 1.5) }
        assertFailsWith<IllegalArgumentException> { IbisShiftState(pitchRadians = Math.PI * 0.5) }
        assertFailsWith<IllegalArgumentException> { CmosNoiseConfig(fullWellElectrons = 0.0) }
        assertFailsWith<IllegalArgumentException> { CmosNoiseConfig(blackLevel = -1) }

        val pipeline = CpuOpticalPipeline()
        val src = LinearRgbImage.uniform(8, 8, 0.5f, 0.5f, 0.5f)
        assertFailsWith<IllegalArgumentException> {
            pipeline.render(
                source = src,
                geometry = geometry(8, 8, bitDepth = 16),
                exposure = nominalExposure(),
                destination = ByteBuffer.allocate(8 * 8), // too small for 16-bit
            )
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.render(
                source = src,
                geometry = geometry(7, 8, bitDepth = 16), // odd width
                exposure = nominalExposure(),
                destination = ByteBuffer.allocate(7 * 8 * 2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.render(
                source = src,
                geometry = geometry(8, 8, bitDepth = 16),
                exposure = nominalExposure(),
                destination = ByteBuffer.allocate(8 * 8 * 2),
                depthMeters = FloatArray(10), // mismatched length
            )
        }
    }
}
