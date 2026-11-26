package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class MonaLisaSceneSourceTest {
    @Test
    fun portraitMattePreservesEyesHandsAndSoftVeil() {
        MonaLisaSceneSource(width = 320, height = 180, motionEnabled = false).use { scene ->
            val spec = scene.gpuScene3dRenderSpec(CameraPose3D.IDENTITY)
            val figure = spec.fgRgbaProvider()
            val depth = spec.bgRgbDepthOffsetProvider()
            fun offset(x: Int, y: Int) = (y * spec.srcWidth + x) * 4 + 3
            for ((x, y) in listOf(435 to 211, 487 to 210, 420 to 408, 425 to 647, 350 to 695)) {
                assertTrue(figure[offset(x, y)] >= 0.99f, "Interior coverage at ($x,$y)")
            }
            assertEquals(0f, figure[offset(355, 285)], "Landscape outside the veil")
            val veil = (365..400).map { x -> figure[offset(x, 285)] }
            assertTrue(veil.any { it > 0.02f && it < 0.98f }, "Hair must retain partial coverage")
            assertTrue(depth[offset(450, 245)] < depth[offset(560, 270)], "Nose in front of hair")
            assertTrue(depth[offset(425, 647)] < depth[offset(570, 570)], "Folded hands in front of torso")
        }
    }

    @Test
    fun presetParsesAndValidatesMonaLisaUriAndQueryParameters() {
        assertEquals(OpticalScenePreset.MONA_LISA, OpticalScenePreset.parse("scene://mona-lisa"))
        assertEquals(OpticalScenePreset.MONA_LISA, OpticalScenePreset.parse("scene://mona-lisa?motion=on&period=6.0"))
        assertEquals(OpticalScenePreset.MONA_LISA, OpticalScenePreset.parse("scene://mona-lisa?motion=off&phase=0.25"))

        val provider = ProceduralOpticsSceneSourceProvider(width = 320, height = 180)
        val req = SceneSourceRequest("optical-scene", "scene://mona-lisa?motion=off&phase=0.0", loop = true) { Path.of(it) }
        provider.validate(req)
        val src = provider.open(req) as MonaLisaSceneSource
        src.use {
            assertEquals(false, it.motionEnabled)
            assertEquals(0L, it.latestFrameSequence)
            val frame = it.latestLinearRgbFrame()
            assertEquals(320, frame.width)
            assertEquals(180, frame.height)
            val depth = checkNotNull(frame.depthMeters)
            val minD = depth.min()
            val maxD = depth.max()
            assertTrue(minD < 2.2f, "Portrait foreground depth must be < 2.2m, got $minD")
            assertTrue(maxD >= 4.4f, "Tuscan gallery backdrop depth must be >= 4.4m, got $maxD")
        }

        assertFailsWith<IllegalArgumentException> {
            provider.validate(SceneSourceRequest("optical-scene", "scene://mona-lisa?motion=maybe", true) { Path.of(it) })
        }
        assertFailsWith<IllegalArgumentException> {
            provider.validate(SceneSourceRequest("optical-scene", "scene://mona-lisa?unknown=1", true) { Path.of(it) })
        }
    }

    @Test
    fun motionAdvancesSmoothlyAcrossHorizontalAndDepthRangeWithDistinctRingBuffers() {
        var fakeNow = 0L
        val src = MonaLisaSceneSource(
            width = 320,
            height = 180,
            loop = true,
            motionEnabled = true,
            initialPhase = 0.0,
            periodSeconds = 8.0,
            nanoClock = { fakeNow },
        )
        src.use {
            val frameCenter = it.latestLinearRgbFrame()
            val seq0 = it.latestFrameSequence
            assertTrue(seq0 >= 1L)

            // Advance 2.0s (phase = 0.25 -> max positive horizontal pan + nominal depth 1.95m)
            fakeNow = 2_000_000_000L
            val frameRight = it.latestLinearRgbFrame()
            val seq1 = it.latestFrameSequence
            assertTrue(seq1 > seq0)
            assertNotSame(frameCenter.red, frameRight.red, "Alternating ring slot must yield fresh FloatArray identity for LensOpticalSceneFilter cache")

            // Advance 6.0s (phase = 0.75 -> max negative horizontal pan)
            fakeNow = 6_000_000_000L
            val frameLeft = it.latestLinearRgbFrame()

            // Verify foreground depth centroid moves cleanly left < center < right!
            fun foregroundCentroidX(frame: LinearRgbFrame): Double {
                val d = checkNotNull(frame.depthMeters)
                var sumX = 0.0
                var count = 0
                for (y in 0 until frame.height) {
                    val row = y * frame.width
                    for (x in 0 until frame.width) {
                        if (d[row + x] < 3.0f) {
                            sumX += x
                            count++
                        }
                    }
                }
                assertTrue(count > 500, "Expected substantial portrait foreground pixels, got $count")
                return sumX / count
            }

            val cxLeft = foregroundCentroidX(frameLeft)
            val cxRight = foregroundCentroidX(frameRight)
            assertTrue(
                cxRight - cxLeft > 60.0,
                "Expected clear horizontal portrait motion between phase 0.75 ($cxLeft) and 0.25 ($cxRight)",
            )

            // Verify Z-depth breathing between phase 0.0 (near ~1.60m) and phase 0.5 (far ~2.30m)
            val poseNear = MonaLisaSceneSource.computePose(0.0)
            val poseFar = MonaLisaSceneSource.computePose(0.5)
            assertTrue(poseNear.subjectDepthM < 1.65f)
            assertTrue(poseFar.subjectDepthM > 2.25f)
            assertNotEquals(poseNear.scale, poseFar.scale)

            // Verify 60 Hz update cadence (16.67 ms interval): 5 ms stays cached, 20 ms advances render cleanly
            val rendersBeforeStep = it.framesReceived
            fakeNow += 5_000_000L
            val frameThrottled = it.latestLinearRgbFrame()
            assertEquals(rendersBeforeStep, it.framesReceived, "Sub-frame 5 ms step should stay within 60 Hz throttle")
            assertEquals(frameLeft.red, frameThrottled.red, "Throttled 5 ms step should reuse active ring buffer slot")
            fakeNow += 15_000_000L // total +20 ms > 16.67 ms
            val frameNext = it.latestLinearRgbFrame()
            assertEquals(rendersBeforeStep + 1L, it.framesReceived, "20 ms step should advance Mona Lisa at 60 fps cadence")
            assertNotSame(frameLeft.red, frameNext.red, "60 fps cadence step should swap ring buffer slot")

            // Verify direct Bayer radiance readout succeeds cleanly
            val geom = SensorGeometry(160, 120, 14, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 10_000_000L, 100, LensParameters("gf-63", 63.0, 2.8, 1.95, 0.9, 0.0))
            val dst = ByteBuffer.allocate(geom.width * geom.height * 2)
            it.radiance(exp, geom, dst)
            assertEquals(0, dst.remaining())
        }
    }
}
