package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FastOpticalPipelineParityAndBenchmarkTest {
    private fun buildSynthetic3dScene(w: Int, h: Int): Pair<LinearRgbImage, FloatArray> {
        val count = w * h
        val r = FloatArray(count)
        val g = FloatArray(count)
        val b = FloatArray(count)
        val depth = FloatArray(count) { 12.0f }

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                // Background specular grid at 12.0 m
                val orb = ((x % 64) - 32).let { dx -> ((y % 64) - 32).let { dy -> dx * dx + dy * dy < 36 } }
                if (orb) {
                    r[i] = 0.96f
                    g[i] = 0.82f
                    b[i] = 0.36f
                } else {
                    r[i] = 0.04f
                    g[i] = 0.06f
                    b[i] = 0.12f
                }
                depth[i] = 12.0f

                // Midground high-contrast checker at 2.5 m
                if (x in (w / 4) until (w * 3 / 4) && y in (h / 4) until (h * 3 / 4)) {
                    val v = if (((x / 8) + (y / 8)) % 2 == 0) 0.85f else 0.08f
                    r[i] = v
                    g[i] = v * 0.95f
                    b[i] = v * 0.90f
                    depth[i] = 2.5f
                }

                // Foreground focus target at 0.8 m
                if (x < w / 4 && y > h / 2) {
                    val v = if ((x / 4) % 2 == 0) 0.92f else 0.05f
                    r[i] = v
                    g[i] = v
                    b[i] = v
                    depth[i] = 0.8f
                }
            }
        }
        return LinearRgbImage(w, h, r, g, b) to depth
    }

    @Test
    fun multiThreadedStripedPipelineMatchesSingleThreadedReferenceBitExactly() {
        val w = 256
        val h = 144
        val (scene, depth) = buildSynthetic3dScene(w, h)
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val lensParams = LensParameters("gf80-f17", 80.0, 1.7, 2.5, 0.9, 0.02)
        val exposure = Exposure(0L, 2_000_000L, 200, lensParams)
        val ibis = IbisShiftState(shiftXPixels = 3.5, shiftYPixels = -2.25, rollRadians = 0.03)

        val single = CpuOpticalPipeline(maxBokehRadiusPx = 12, parallelThreads = 1)
        val parallel = CpuOpticalPipeline(maxBokehRadiusPx = 12, parallelThreads = OpticalParallelExecutor.defaultThreadCount)

        // 1. Circular 3D bokeh + distortion + lateral CA + vignetting + noisy Bayer readout
        val circularProfile = OpticalLensProfile.fromLensParameters(
            lens = lensParams,
            referenceFocalLengthMm = 63.0,
            k1 = -0.05,
            k2 = 0.01,
            caRedScale = 1.004,
            caBlueScale = 0.996,
            cos4Power = 2.5,
            mechanicalVignetting = 0.25,
            wideOpenContrastDrop = 0.15,
            flareStrength = 0.2,
        )
        val noisyConfig = CmosNoiseConfig(seed = 1337L, enableNoise = true, blackLevel = 256)
        val buf1 = ByteBuffer.allocate(w * h * 2)
        val bufN = ByteBuffer.allocate(w * h * 2)
        single.render(scene, geom, exposure, circularProfile, ibis, noisyConfig, buf1, depthMeters = depth, frameIndex = 7L)
        parallel.render(scene, geom, exposure, circularProfile, ibis, noisyConfig, bufN, depthMeters = depth, frameIndex = 7L)
        assertContentEquals(buf1.array(), bufN.array(), "Circular 3D bokeh + noisy Bayer must be bit-exact across 1 vs N threads")

        // 2. Shaped bokeh (polygonal blades + cat's-eye + onion-ring + rim highlight + LoCA)
        val shapedProfile = circularProfile.copy(
            fNumber = 2.8,
            minFNumber = 1.7,
            longitudinalCaDefocusMm = 1.5,
            apertureBladeCount = 7,
            apertureBladeRoundness = 0.35,
            bokehCatsEye = 0.4,
            bokehOnionRing = 0.2,
            bokehRimHighlight = 0.3,
        )
        val sImg1 = single.renderLinearRgb(scene, w, h, shapedProfile, ibis, depthMeters = depth)
        val sImgN = parallel.renderLinearRgb(scene, w, h, shapedProfile, ibis, depthMeters = depth)
        assertContentEquals(sImg1.red, sImgN.red, "Shaped 3D bokeh + LoCA Red channel must be bit-exact across 1 vs N threads")
        assertContentEquals(sImg1.green, sImgN.green, "Shaped 3D bokeh + LoCA Green channel must be bit-exact across 1 vs N threads")
        assertContentEquals(sImg1.blue, sImgN.blue, "Shaped 3D bokeh + LoCA Blue channel must be bit-exact across 1 vs N threads")

        // 3. Planar 2D separable box-blur defocus
        val pImg1 = single.renderLinearRgb(scene, w, h, circularProfile, ibis, planarSubjectDistanceM = 0.8)
        val pImgN = parallel.renderLinearRgb(scene, w, h, circularProfile, ibis, planarSubjectDistanceM = 0.8)
        assertContentEquals(pImg1.green, pImgN.green, "Planar separable defocus must be bit-exact across 1 vs N threads")
    }

    @Test
    fun lensOpticalSceneFilterHonorsSceneSource2DVirtualSubjectDistance() {
        val w = 160
        val h = 90
        val count = w * h
        val stripes = FloatArray(count) { i -> if ((i % w) % 4 < 2) 0.9f else 0.1f }
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val lensParams = LensParameters("gf80-f17", 80.0, 1.7, 10.0, 0.9, 0.0)
        val exposure = Exposure(0L, 1_000_000L, 100, lensParams)
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = OpticalLensProfile.fromLensParameters(lensParams),
            noiseConfig = CmosNoiseConfig.NOISELESS,
            planarSubjectDistanceM = null, // defers to SceneSource2D.virtualSubjectDistanceM!
        )

        val sharpFrame2D = LinearRadianceFrame(w, h, stripes, stripes, stripes, dimension = SceneDimension.DIM_2D, virtualSubjectDistanceM = 10.0)
        val nearFrame2D = LinearRadianceFrame(w, h, stripes, stripes, stripes, dimension = SceneDimension.DIM_2D, virtualSubjectDistanceM = 1.5)

        val outSharp = ByteBuffer.allocate(count * 2)
        val outNear = ByteBuffer.allocate(count * 2)
        filter.filterRadianceFrame(sharpFrame2D, exposure, geom, outSharp, 0L)
        filter.filterRadianceFrame(nearFrame2D, exposure, geom, outNear, 1L)
        assertFalse(outSharp.array().contentEquals(outNear.array()), "Changing SceneSource2D virtualSubjectDistanceM must modulate defocus & invalidate cache")
        assertEquals(2L, filter.cacheMisses)
    }

    @Test
    fun benchmark3dFocusSweepMultiThreadedSpeedup() {
        val w = 1024
        val h = 576
        val (scene, depth) = buildSynthetic3dScene(w, h)
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val focusSweep = doubleArrayOf(0.8, 1.4, 2.5, 4.5, 8.0, 12.0)

        val single = CpuOpticalPipeline(maxBokehRadiusPx = 12, parallelThreads = 1)
        val multi = CpuOpticalPipeline(maxBokehRadiusPx = 12, parallelThreads = OpticalParallelExecutor.defaultThreadCount)
        val ws1 = OpticalFrameWorkspace()
        val wsN = OpticalFrameWorkspace()
        val dst = ByteBuffer.allocate(w * h * 2)

        fun runSweep(pipeline: CpuOpticalPipeline, ws: OpticalFrameWorkspace): Long {
            val t0 = System.nanoTime()
            for (d in focusSweep) {
                val lp = LensParameters("gf80-f17", 80.0, 1.7, d, 0.9, 0.0)
                val exp = Exposure(0L, 1_000_000L, 100, lp)
                val prof = OpticalLensProfile.fromLensParameters(lp, k1 = -0.04, cos4Power = 2.0, mechanicalVignetting = 0.2)
                dst.clear()
                pipeline.render(scene, geom, exp, prof, IbisShiftState.ZERO, CmosNoiseConfig.NOISELESS, dst, depthMeters = depth, workspace = ws)
            }
            return System.nanoTime() - t0
        }

        // Warmup JIT
        runSweep(single, ws1)
        runSweep(multi, wsN)

        val singleNs = runSweep(single, ws1)
        val multiNs = runSweep(multi, wsN)
        val singleMsPerFrame = singleNs / 1_000_000.0 / focusSweep.size
        val multiMsPerFrame = multiNs / 1_000_000.0 / focusSweep.size
        val singleFps = 1000.0 / singleMsPerFrame
        val multiFps = 1000.0 / multiMsPerFrame
        val speedup = singleNs.toDouble() / multiNs.toDouble().coerceAtLeast(1.0)

        println(
            "[OPTICS-PERF-BENCH] 1024x576 3D focus-sweep (${focusSweep.size} steps, maxBokeh=12, threads=${multi.parallelThreads}): " +
                "1T=${"%.2f".format(singleMsPerFrame)} ms/f (${"%.1f".format(singleFps)} FPS) -> " +
                "${multi.parallelThreads}T=${"%.2f".format(multiMsPerFrame)} ms/f (${"%.1f".format(multiFps)} FPS), " +
                "speedup=${"%.2f".format(speedup)}x",
        )
        if (multi.parallelThreads >= 4) {
            assertTrue(speedup >= 1.5, "Expected >=1.5x multi-threaded speedup on ${multi.parallelThreads} threads (got ${"%.2f".format(speedup)}x)")
        }
        assertTrue(abs(multiFps) > 0.0)
    }
}
