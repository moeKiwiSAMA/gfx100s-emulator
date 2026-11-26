package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LensOpticalSceneFilterCacheTest {
    @Test
    fun noiselessFilterCachesRepeatedFramesAndInvalidatesOnConfigExposureOrSourceChange() {
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = OpticalLensProfile(
                focalLengthMm = 63.0,
                referenceFocalLengthMm = 63.0,
                k1 = -0.06,
                cos4Power = 2.0,
                mechanicalVignetting = 0.25,
                fNumber = 4.0,
                focusDistanceM = 10.0,
            ),
            noiseConfig = CmosNoiseConfig.NOISELESS,
            planarSubjectDistanceM = 2.5,
        )
        val geom = SensorGeometry(128, 72, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val r = FloatArray(count) { i -> ((i % geom.width) / geom.width.toFloat()) * 0.8f }
        val g = FloatArray(count) { i -> ((i / geom.width) / geom.height.toFloat()) * 0.7f }
        val b = FloatArray(count) { 0.35f }
        val depth = FloatArray(count) { 2.5f }
        val exposure = Exposure(
            startTick = 0L,
            durationNanos = 1000L,
            iso = 100,
            lens = LensParameters("gf45-100", 63.0, 4.0, 10.0, 0.9, 0.0),
        )

        val out1 = ByteBuffer.allocate(count * 2)
        val out2 = ByteBuffer.allocate(count * 2)
        assertEquals(count * 2, filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, depth, exposure, geom, out1, 0L))
        assertEquals(1L, filter.cacheMisses)
        assertEquals(0L, filter.cacheHits)
        assertEquals(2.5, filter.lastCenterDepthMeters)
        assertTrue(filter.lastFrameHadDepth)

        // Second identical frame hits cache even with a different frameIndex (noiseless)
        assertEquals(count * 2, filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, depth, exposure, geom, out2, 42L))
        assertEquals(1L, filter.cacheMisses)
        assertEquals(1L, filter.cacheHits)
        assertEquals(2L, filter.framesFiltered)
        assertEquals(2.5, filter.lastCenterDepthMeters)
        assertContentEquals(out1.array(), out2.array())

        // Changing optical profile invalidates cache immediately
        filter.update(profile = filter.profile.copy(cos4Power = 4.0, mechanicalVignetting = 0.6))
        val outVig = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, depth, exposure, geom, outVig, 43L)
        assertEquals(2L, filter.cacheMisses)
        assertFalse(out1.array().contentEquals(outVig.array()), "Profile update must invalidate cached Bayer")

        // Changing exposure ISO invalidates cache
        val outIso = ByteBuffer.allocate(count * 2)
        val higherIso = exposure.copy(iso = 400)
        filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, depth, higherIso, geom, outIso, 44L)
        assertEquals(3L, filter.cacheMisses)
        assertFalse(outVig.array().contentEquals(outIso.array()), "Exposure ISO change must invalidate cached Bayer")

        // Enabling physical noise bypasses cache so distinct frameIndex produces distinct noise
        filter.update(noiseConfig = CmosNoiseConfig(seed = 77L, enableNoise = true, blackLevel = 128))
        val noisy0 = ByteBuffer.allocate(count * 2)
        val noisy1 = ByteBuffer.allocate(count * 2)
        val noisy0Repeat = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, null, exposure, geom, noisy0, 0L)
        filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, null, exposure, geom, noisy1, 1L)
        filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, null, exposure, geom, noisy0Repeat, 0L)
        assertFalse(noisy0.array().contentEquals(noisy1.array()), "Noisy frames with different frameIndex must differ")
        assertContentEquals(noisy0.array(), noisy0Repeat.array(), "Noisy frames with same seed/frameIndex must remain deterministic")
    }

    @Test
    fun planarDefocusAndPrecomputedVignettingPreserveSharpVsDefocusedContrast() {
        val pipeline = CpuOpticalPipeline()
        val ws = OpticalFrameWorkspace()
        val w = 160
        val h = 90
        val count = w * h
        val stripes = FloatArray(count) { idx -> if ((idx % w) % 4 < 2) 0.9f else 0.1f }
        val source = LinearRgbImage(w, h, stripes, stripes, stripes)

        val profile = OpticalLensProfile(
            focalLengthMm = 80.0,
            referenceFocalLengthMm = 80.0,
            fNumber = 2.0,
            focusDistanceM = 10.0,
            cos4Power = 2.0,
            mechanicalVignetting = 0.3,
        )
        val sharp = pipeline.renderLinearRgb(
            source = source,
            dstWidth = w,
            dstHeight = h,
            profile = profile,
            planarSubjectDistanceM = 10.0,
            workspace = ws,
        )
        val blurred = pipeline.renderLinearRgb(
            source = source,
            dstWidth = w,
            dstHeight = h,
            profile = profile,
            planarSubjectDistanceM = 2.0,
            workspace = ws,
        )

        var sharpVar = 0.0
        var blurVar = 0.0
        val row = (h / 2) * w
        for (x in 20 until w - 20) {
            sharpVar += kotlin.math.abs(sharp.green[row + x] - sharp.green[row + x + 1])
            blurVar += kotlin.math.abs(blurred.green[row + x] - blurred.green[row + x + 1])
        }
        assertTrue(sharpVar > blurVar * 2.0, "Planar defocus row-major blur must strongly attenuate stripe contrast")
        assertNotNull(ws.blurColSum)
        assertTrue(ws.blurColSum.size >= w)
    }

    @Test
    fun stage1OpticalCacheHitsOnExposureChangesAndInvalidatesOnFocusOrProfileChange() {
        val profile = OpticalLensProfile(
            focalLengthMm = 100.0,
            referenceFocalLengthMm = 63.0,
            k1 = -0.02,
            cos4Power = 2.0,
            mechanicalVignetting = 0.15,
            fNumber = 4.0,
            minFNumber = 4.0,
            focusDistanceM = 1.95,
            apertureBladeCount = 9,
            apertureBladeRoundness = 0.65,
        )
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = profile,
            noiseConfig = CmosNoiseConfig.NOISELESS,
        )
        val geom = SensorGeometry(128, 72, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val r = FloatArray(count) { i -> 0.15f + 0.5f * ((i % geom.width) / geom.width.toFloat()) }
        val g = FloatArray(count) { i -> 0.20f + 0.4f * ((i / geom.width) / geom.height.toFloat()) }
        val b = FloatArray(count) { 0.30f }
        val depth = FloatArray(count) { i -> if ((i % geom.width) in 40..88) 1.95f else 4.50f }
        val frame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
            width = geom.width,
            height = geom.height,
            red = r,
            green = g,
            blue = b,
            depthMeters = depth,
            sequence = 7L,
        )
        val baseLens = LensParameters("gf45-100", 100.0, 4.0, 1.95, 0.9, 0.0)
        val expBase = Exposure(0L, 1_000_000L, 100, baseLens)
        val expAe = Exposure(0L, 2_000_000L, 400, baseLens)

        val outBase = ByteBuffer.allocate(count * 2)
        filter.filterRadianceFrame(frame, expBase, geom, outBase, 0L)
        assertEquals(1L, filter.opticalCacheMisses)
        assertEquals(0L, filter.opticalCacheHits)

        // AE updates shutter duration and ISO: full Bayer cache misses, but Stage-1 Optical Linear-RGB cache HITS!
        val outAe = ByteBuffer.allocate(count * 2)
        filter.filterRadianceFrame(frame, expAe, geom, outAe, 1L)
        assertEquals(1L, filter.opticalCacheMisses, "AE exposure change must reuse Stage-1 optical linear RGB")
        assertEquals(1L, filter.opticalCacheHits, "AE exposure change must hit Stage-1 optical cache")
        assertFalse(outBase.array().contentEquals(outAe.array()), "Higher exposure/ISO must still update Bayer output")

        // Verify bit-exact parity against a fresh uncached filter on the exact same AE exposure
        val freshFilter = LensOpticalSceneFilter(enabled = true, profile = profile, noiseConfig = CmosNoiseConfig.NOISELESS)
        val outFresh = ByteBuffer.allocate(count * 2)
        freshFilter.filterRadianceFrame(frame, expAe, geom, outFresh, 1L)
        assertContentEquals(outFresh.array(), outAe.array(), "Stage-1 cached AE output must be bit-exact with fresh render")

        // Focus distance change must invalidate Stage-1 optical cache (depth-of-field changes)
        filter.update(profile = profile.copy(focusDistanceM = 4.50))
        val outRefocus = ByteBuffer.allocate(count * 2)
        filter.filterRadianceFrame(frame, expAe.copy(lens = baseLens.copy(focusDistanceM = 4.50)), geom, outRefocus, 2L)
        assertEquals(2L, filter.opticalCacheMisses, "Focus change must invalidate Stage-1 optical cache")
        assertFalse(outAe.array().contentEquals(outRefocus.array()), "Refocusing must change optical defocus output")
    }

    @Test
    fun updateNeverBlocksWhileBackgroundRenderHoldsRenderLock() {
        val enteredRender = java.util.concurrent.CountDownLatch(1)
        val releaseRender = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowHook = object : com.github.nyabanban.fuji.emulator.api.LensOpticalHook {
            override fun warpUv(
                nx: Double,
                ny: Double,
                focalLengthMm: Double,
                focusDistanceM: Double,
                aperture: Double,
                outUv: DoubleArray,
            ): Boolean {
                if (blocked.compareAndSet(false, true)) {
                    enteredRender.countDown()
                    releaseRender.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return false
            }

            override fun cacheKey(): Long = 0x1234L
        }

        val baseProfile = OpticalLensProfile(
            focalLengthMm = 80.0,
            referenceFocalLengthMm = 80.0,
            fNumber = 2.0,
            focusDistanceM = 2.0,
        )
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = baseProfile,
            noiseConfig = CmosNoiseConfig.NOISELESS,
            parallelThreads = 1,
        )
        filter.update(lensOpticalHook = slowHook)

        val geom = SensorGeometry(32, 24, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val frame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
            width = geom.width,
            height = geom.height,
            red = FloatArray(count) { 0.4f },
            green = FloatArray(count) { 0.5f },
            blue = FloatArray(count) { 0.6f },
            depthMeters = FloatArray(count) { 2.0f },
            sequence = 1L,
        )
        val exp = Exposure(0L, 1_000_000L, 100, LensParameters("GF80", 80.0, 2.0, 2.0, 0.9, 0.0))
        val out = ByteBuffer.allocate(count * 2)

        val renderError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                filter.filterRadianceFrame(frame, exp, geom, out, 0L)
            } catch (t: Throwable) {
                renderError.set(t)
            }
        }
        worker.start()
        try {
            assertTrue(
                enteredRender.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "Background render thread did not enter optical warp hook",
            )
            // Background thread is parked INSIDE renderLock. update() must return immediately (< 50 ms)
            // rather than stalling the ARM owner thread waiting for the in-flight bokeh frame!
            val t0 = System.nanoTime()
            filter.update(profile = baseProfile.copy(focusDistanceM = 4.25))
            val updateMs = (System.nanoTime() - t0) / 1_000_000.0
            assertTrue(
                updateMs < 50.0,
                "LensOpticalSceneFilter.update() must not block on in-flight renderLock (took ${"%.2f".format(updateMs)} ms)",
            )
            assertEquals(4.25, filter.profile.focusDistanceM)
        } finally {
            releaseRender.countDown()
            worker.join(5_000L)
        }
        assertTrue(!worker.isAlive, "Background render thread did not finish")
        assertEquals(null, renderError.get(), "Background render thread threw: ${renderError.get()}")
    }
}
