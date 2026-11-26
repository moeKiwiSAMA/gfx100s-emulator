package com.github.nyabanban.fuji.emulator.components.lens

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.CpuOpticalPipeline
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Optical physics verification for true entrance-pupil star bokeh (`STAR_5`) and scalar ND
 * attenuation inside [CpuOpticalPipeline]:
 * 1. An out-of-focus point light physically convolves into a 5-pointed star PSF with dominant
 *    5-fold angular harmonic (`cos(5*theta)`) compared to a circular aperture.
 * 2. Focusing onto the point light collapses the star back into a sharp pinpoint.
 * 3. `filterTransmittance = 2^(-3)` (`ND8`) reduces linear Bayer readout by exact 8x (`-3.0 EV`).
 */
class StarBokehOpticalVerificationTest {
    private val star5Mask = object : OpticalPupilMask {
        override val shape: BokehMaskShape = BokehMaskShape.STAR_5
        override val angleDeg: Double = 0.0
        override val scale: Double = 0.96
        override val active: Boolean = true
        override val cacheKey: Long = 0x57A5L

        private val sector = 2.0 * PI / 5.0
        private val halfSector = PI / 5.0
        private val sinHalf = sin(halfSector)

        override fun pupilWeight(ux: Double, uy: Double): Double {
            val r = hypot(ux, uy)
            val rOut = scale
            val rIn = scale * 0.382
            if (r >= rOut + 0.085) return 0.0
            if (r <= rIn - 0.085) return 1.0
            val theta = atan2(uy, ux) + PI * 0.5
            var mod = theta % sector
            if (mod < 0.0) mod += sector
            val phi = if (mod <= halfSector) mod else (sector - mod)
            val denom = rIn * sin(halfSector - phi) + rOut * sin(phi)
            val edgeR = if (denom > 1e-6) (rOut * rIn * sinHalf) / denom else rIn
            return ((edgeR - r) / 0.085 + 0.5).coerceIn(0.0, 1.0)
        }
    }

    private fun buildPointLightScene(w: Int, h: Int, lightDepthM: Float): Pair<LinearRgbImage, FloatArray> {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val depth = FloatArray(w * h) { lightDepthM }
        val cx = w / 2
        val cy = h / 2
        // 3x3 bright point light at center
        for (dy in -1..1) {
            for (dx in -1..1) {
                val idx = (cy + dy) * w + (cx + dx)
                r[idx] = 8.0f
                g[idx] = 6.5f
                b[idx] = 2.5f
            }
        }
        return LinearRgbImage(w, h, r, g, b) to depth
    }

    private fun harmonicEnergy(img: LinearRgbImage, cx: Int, cy: Int, sampleRadius: Double, order: Int): Double {
        var cSum = 0.0
        var sSum = 0.0
        var dcSum = 0.0
        val steps = 180
        for (k in 0 until steps) {
            val theta = -PI * 0.5 + (2.0 * PI * k) / steps.toDouble()
            val px = (cx + sampleRadius * cos(theta)).roundToInt().coerceIn(0, img.width - 1)
            val py = (cy + sampleRadius * sin(theta)).roundToInt().coerceIn(0, img.height - 1)
            val v = img.red[py * img.width + px].toDouble()
            dcSum += v
            cSum += v * cos(order * theta)
            sSum += v * sin(order * theta)
        }
        return if (dcSum <= 1e-6) 0.0 else hypot(cSum, sSum) / dcSum
    }

    @Test
    fun `planar point lights use the same pupil as a constant depth map`() {
        val (source, depth) = buildPointLightScene(192, 128, lightDepthM = 7.0f)
        val profile = OpticalLensProfile(focalLengthMm = 110.0, fNumber = 2.0, focusDistanceM = 0.8)
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 16)
        val radius = profile.circleOfConfusionRadiusPixels(7.0, source.width).coerceAtMost(16.0)
        val fromPlane = pipeline.renderLinearRgb(
            source, source.width, source.height, profile, planarSubjectDistanceM = 7.0, pupilMask = star5Mask,
        )
        val fromDepth = pipeline.renderLinearRgb(
            source, source.width, source.height, profile, depthMeters = depth, pupilMask = star5Mask,
        )
        assertTrue(harmonicEnergy(fromPlane, source.width / 2, source.height / 2, radius * 0.65, 5) > 0.25)
        assertContentEquals(fromDepth.red, fromPlane.red)
        assertContentEquals(fromDepth.green, fromPlane.green)
        assertContentEquals(fromDepth.blue, fromPlane.blue)
    }

    @Test
    fun `out-of-focus point light forms genuine 5-pointed star bokeh and collapses to pinpoint when focused`() {
        val w = 256
        val h = 256
        val cx = w / 2
        val cy = h / 2
        val (src, depth) = buildPointLightScene(w, h, lightDepthM = 7.0f)
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 16, parallelThreads = 2)

        // GF110mmF2 focused near (1.0 m) -> background light at 7.0 m has large CoC (~14..16 px)
        val defocusedProfile = OpticalLensProfile(
            focalLengthMm = 110.0,
            fNumber = 2.0,
            focusDistanceM = 1.0,
            sensorWidthMm = 43.8,
        )
        val cocPx = defocusedProfile.circleOfConfusionRadiusPixels(7.0, w).coerceAtMost(16.0)
        assertTrue(cocPx >= 10.0, "Expected large out-of-focus CoC radius >= 10px, got $cocPx")

        val starOut = pipeline.renderLinearRgb(src, w, h, defocusedProfile, depthMeters = depth, pupilMask = star5Mask)
        val circleOut = pipeline.renderLinearRgb(src, w, h, defocusedProfile, depthMeters = depth, pupilMask = null)

        val ringR = cocPx * 0.65 // inside star tips (~0.96*cocPx), outside star valleys (~0.37*cocPx)
        val starH5 = harmonicEnergy(starOut, cx, cy, ringR, order = 5)
        val circleH5 = harmonicEnergy(circleOut, cx, cy, ringR, order = 5)
        val starH4 = harmonicEnergy(starOut, cx, cy, ringR, order = 4)

        assertTrue(
            starH5 > 0.25 && starH5 > circleH5 * 5.0 && starH5 > starH4 * 3.0,
            "Expected strong 5-fold star harmonic at ringR=$ringR (cocPx=$cocPx): starH5=$starH5 circleH5=$circleH5 starH4=$starH4",
        )

        // Verify all 5 tips are bright and all 5 valleys are dark at ringR
        for (k in 0 until 5) {
            val tipTheta = -PI * 0.5 + k * (2.0 * PI / 5.0)
            val tx = (cx + ringR * cos(tipTheta)).roundToInt()
            val ty = (cy + ringR * sin(tipTheta)).roundToInt()
            val tipVal = starOut.red[ty * w + tx]

            val valTheta = tipTheta + PI / 5.0
            val vx = (cx + ringR * cos(valTheta)).roundToInt()
            val vy = (cy + ringR * sin(valTheta)).roundToInt()
            val valleyVal = starOut.red[vy * w + vx]

            assertTrue(
                tipVal > valleyVal * 4.0f + 0.02f,
                "Star tip #$k ($tipVal) must be much brighter than valley #$k ($valleyVal)",
            )
        }

        // Now focus directly at 7.0 m -> point light MUST collapse back into a sharp pinpoint!
        val focusedProfile = defocusedProfile.copy(focusDistanceM = 7.0)
        val sharpOut = pipeline.renderLinearRgb(src, w, h, focusedProfile, depthMeters = depth, pupilMask = star5Mask)
        val centerVal = sharpOut.red[cy * w + cx]
        val outerRingVal = sharpOut.red[(cy - 8) * w + cx]
        assertTrue(centerVal > 5.0f, "In-focus center pinpoint must remain bright: $centerVal")
        assertTrue(outerRingVal < 0.01f, "In-focus point light must not smear into a star at r=8px: $outerRingVal")
    }

    @Test
    fun `nd8 scalar transmittance attenuates linear Bayer output by exact 8x`() {
        val w = 64
        val h = 64
        val r = FloatArray(w * h) { 0.5f }
        val g = FloatArray(w * h) { 0.5f }
        val b = FloatArray(w * h) { 0.5f }
        val src = LinearRgbImage(w, h, r, g, b)
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val lens = LensParameters("GF63mmF2.8", 63.0, 2.8, 2.0, 0.9, 0.0)
        val exp = Exposure(0L, 10_000_000L, 100, lens)
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 0, parallelThreads = 1)

        fun meanAdu(trans: Double): Double {
            val buf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
            pipeline.render(
                source = src,
                geometry = geom,
                exposure = exp,
                profile = OpticalLensProfile.IDENTITY.copy(fNumber = 2.8, transmission = 0.9),
                noiseConfig = CmosNoiseConfig.NOISELESS,
                destination = buf,
                filterTransmittance = trans,
            )
            buf.flip()
            var sum = 0.0
            for (i in 0 until w * h) sum += (buf.short.toInt() and 0xFFFF)
            return sum / (w * h)
        }

        val aduOff = meanAdu(1.0)
        val aduNd8 = meanAdu(0.125)
        val ratio = aduOff / aduNd8
        assertTrue(ratio in 7.8..8.2, "Expected ND8 8.0x linear attenuation, got ratio=$ratio (off=$aduOff, nd8=$aduNd8)")
    }
}
