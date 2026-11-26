package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.filter.BlackMistFilterElement
import com.github.nyabanban.fuji.emulator.components.filter.StarFilterElement
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class MilkFrogSceneSourceTest {
    @Test
    fun rearPinLightsRemainVisibleBehindTheSubjectAtBothFocalLengths() {
        MilkFrogSceneSource(width = 1024, height = 576, motionEnabled = false, audioEnabled = false,
            initialPhase = 0.45).use { scene ->
            for (focal in listOf(45.0, 110.0)) {
                val pose = CameraPose3D(focalLengthMm = focal)
                val spec = scene.gpuScene3dRenderSpec(pose)
                val frame = scene.renderForPose(pose)
                val depth = assertNotNull(frame.depthMeters)
                val rear = (0 until spec.orbCount).map { it * 8 }.filter { spec.orbs[it + 7] > 7f }
                assertEquals(2, rear.size)
                for (offset in rear) {
                    val x = spec.orbs[offset].toInt()
                    val y = spec.orbs[offset + 1].toInt()
                    assertTrue(x in 30 until frame.width - 30 && y in 30 until frame.height - 30)
                    val i = y * frame.width + x
                    assertEquals(spec.orbs[offset + 7], depth[i], "Rear light must clear the hero and easels")
                    assertEquals(spec.orbs[offset + 4], frame.red[i], "GPU and CPU must agree on the HDR light core")
                    assertTrue(frame.red[i + 8] < 0.1f, "The light must have a dark surround")
                }
            }
        }
    }

    @Test
    fun synthesizedPictureAndMicrophoneShareTheCameraEpochAcrossPausesAndLongRuns() {
        var tick = 54_000_000L * 60 * 60 * 24 * 3
        val startTick = tick
        val request = SceneSourceRequest("optical-scene", "scene://milk-frog?period=2&phase=0.125", true) { Path.of(it) }
        val provider = ProceduralOpticsSceneSourceProvider(width = 96, height = 64)
        (provider.open(request) { cameraNanos(tick) } as MilkFrogSceneSource).use { source ->
            val before = source.latestFrameSequence
            val first = ShortArray(480 * 2)
            val replay = ShortArray(first.size)
            val cameraSound = { buffer: ShortArray ->
                source.sampleAudioAtNanos(buffer, 480, CameraPose3D.IDENTITY, cameraNanos(tick))
            }
            cameraSound(first)
            source.sampleAudioStereo48k(replay, 480, CameraPose3D.IDENTITY)
            assertTrue(first.contentEquals(replay))
            // Host speaker reads can run while the emulated camera is paused.
            repeat(20) { source.sampleAudioStereo48k(replay, 480, CameraPose3D.IDENTITY) }
            cameraSound(replay)
            assertTrue(first.contentEquals(replay))
            assertEquals(before, source.latestFrameSequence)
            tick += 27_000_000L
            assertEquals(0.375, source.currentAnimState().phase, 1e-10)
            assertTrue(source.latestFrameSequence > before)
            cameraSound(replay)
            assertFalse(first.contentEquals(replay))
            tick = startTick + 54_000_000L * 3600 * 72
            assertEquals(0.125, source.currentAnimState().phase, 1e-10)
            cameraSound(replay)
            assertTrue(first.contentEquals(replay))
        }
    }

    @Test
    fun gpuSceneSpecReusesEachCameraFrameAndInvalidatesForPoseAndTimeChanges() {
        var tick = 0L
        val pose = CameraPose3D.IDENTITY
        MilkFrogSceneSource.parseAndOpen("scene://milk-frog", 96, 64, true) { cameraNanos(tick) }.use { source ->
            val first = source.gpuScene3dRenderSpec(pose)
            tick = 899_999L // Last camera tick before the second 60 Hz image.
            assertSame(first, source.gpuScene3dRenderSpec(pose))
            val turned = source.gpuScene3dRenderSpec(pose.copy(yawDeg = 5.0))
            assertNotEquals(first.sceneKey, turned.sceneKey)
            tick = 900_000L
            val second = source.gpuScene3dRenderSpec(pose)
            assertNotEquals(first.sceneKey, second.sceneKey)
            assertTrue(second.sequence > turned.sequence)
            assertSame(second, source.gpuScene3dRenderSpec(pose))
            tick = 0L
            assertEquals(first.sceneKey, source.gpuScene3dRenderSpec(pose).sceneKey)
        }
    }

    @Test
    fun linearReadoutAfterGpuPoseChangeUsesCurrentViewEvenWhileMotionIsPaused() {
        for (motion in listOf("on", "off")) {
            MilkFrogSceneSource.parseAndOpen("scene://milk-frog?motion=$motion", 96, 64, true) { 0L }.use { source ->
                val identity = source.latestLinearRgbFrame().green.copyOf()
                val pose = CameraPose3D(yawDeg = 20.0)
                val turned = source.renderForPose(pose).green.copyOf()
                assertFalse(identity.contentEquals(turned))
                source.gpuScene3dRenderSpec(CameraPose3D.IDENTITY)
                assertTrue(identity.contentEquals(source.latestLinearRgbFrame().green))
                val count = source.framesReceived
                source.latestLinearRgbFrame()
                assertEquals(count, source.framesReceived)
            }
        }
    }

    @Test
    fun parsesQueryParametersAndValidatesFourDepthZonesAndHdrBokehStage() {
        assertEquals(OpticalScenePreset.MILK_FROG, OpticalScenePreset.parse("scene://milk-frog"))
        assertEquals(OpticalScenePreset.MILK_FROG, OpticalScenePreset.parse("scene://milk-frog?motion=off&audio=off&phase=0.45"))

        val provider = ProceduralOpticsSceneSourceProvider(width = 640, height = 360)
        val req = SceneSourceRequest("optical-scene", "scene://milk-frog?motion=off&audio=on&phase=0.45", loop = true) { Path.of(it) }
        provider.validate(req)

        val src = provider.open(req) as MilkFrogSceneSource
        src.use {
            assertFalse(it.motionEnabled)
            assertTrue(it.audioEnabled)
            assertTrue(it.sceneAudioEnabled)
            val frame = it.latestLinearRgbFrame()
            val depth = assertNotNull(frame.depthMeters)
            val n = frame.width * frame.height

            var fgCount = 0      // 0.60..0.88 m (foreground HDR bulbs, HA! badge)
            var heroCount = 0    // 1.15..1.85 m (3D Milk Frog + pedestal + focus ruler)
            var midCount = 0     // 2.55..2.95 m (Macbeth 24-patch + MTF/Siemens easels)
            var bgBokehCount = 0 // 4.00..10.6 m (staggered HDR bokeh stage)
            var hdrCount = 0     // > 4.0 linear radiance
            var maxCh = 0f

            for (i in 0 until n) {
                val r = frame.red[i]
                val g = frame.green[i]
                val b = frame.blue[i]
                val d = depth[i]
                assertTrue(r.isFinite() && g.isFinite() && b.isFinite() && r >= 0f && g >= 0f && b >= 0f)
                assertTrue(d.isFinite() && d >= 0.05f)
                val peak = maxOf(r, g, b)
                if (peak > maxCh) maxCh = peak
                if (peak > 4.0f) hdrCount++
                when {
                    d in 0.58f..0.90f -> fgCount++
                    d in 1.15f..1.88f -> heroCount++
                    d in 2.55f..2.95f -> midCount++
                    d >= 4.0f -> bgBokehCount++
                }
            }

            assertTrue(fgCount >= 400, "Expected near-foreground (0.60..0.85 m) elements, got $fgCount px")
            assertTrue(heroCount >= 12_000, "Expected prominent 3D Milk Frog hero (1.35..1.85 m), got $heroCount px")
            assertTrue(midCount >= 20_000, "Expected midground Macbeth & MTF optical lab easels (2.65..2.85 m), got $midCount px")
            assertTrue(bgBokehCount >= 40_000, "Expected deep HDR bokeh stage (4.2..10.5 m), got $bgBokehCount px")
            assertTrue(maxCh >= 6.0f && hdrCount >= 200, "Expected true HDR bokeh lights (>4.0), got maxCh=$maxCh, count=$hdrCount")

            // Verify StarFilter and BlackMistFilter react cleanly to Milk Frog's HDR foreground & background bulbs
            val scratch = OpticalFilterScratch()
            val sr = frame.red.copyOf(); val sg = frame.green.copyOf(); val sb = frame.blue.copyOf()
            StarFilterElement(enabled = true, points = 6, strength = 1.0, threshold = 0.85, angleDeg = 15.0, length = 0.14)
                .applySpatialLinearRgb(sr, sg, sb, frame.width, frame.height, scratch)
            var starAdded = 0.0
            for (i in 0 until n) {
                if (maxOf(frame.red[i], frame.green[i], frame.blue[i]) <= 1.2f) {
                    starAdded += maxOf(0f, sr[i] - frame.red[i]) + maxOf(0f, sg[i] - frame.green[i]) + maxOf(0f, sb[i] - frame.blue[i])
                }
            }
            assertTrue(starAdded > 30.0, "Expected crisp StarFilter diffraction spikes on Milk Frog HDR bulbs, got $starAdded")

            val mr = frame.red.copyOf(); val mg = frame.green.copyOf(); val mb = frame.blue.copyOf()
            BlackMistFilterElement(enabled = true, preset = "1/4", strength = 0.65, threshold = 0.45)
                .applySpatialLinearRgb(mr, mg, mb, frame.width, frame.height, scratch)
            var mistAdded = 0.0
            for (i in 0 until n) {
                if (maxOf(frame.red[i], frame.green[i], frame.blue[i]) <= 1.2f) {
                    mistAdded += maxOf(0f, mr[i] - frame.red[i]) + maxOf(0f, mg[i] - frame.green[i]) + maxOf(0f, mb[i] - frame.blue[i])
                }
            }
            assertTrue(mistAdded > 80.0, "Expected strong BlackMist halation bloom on Milk Frog HDR bulbs, got $mistAdded")
        }

        // Invalid query params rejected cleanly
        assertFailsWith<IllegalArgumentException> {
            provider.validate(SceneSourceRequest("optical-scene", "scene://milk-frog?motion=maybe", true) { Path.of(it) })
        }
        assertFailsWith<IllegalArgumentException> {
            provider.validate(SceneSourceRequest("optical-scene", "scene://milk-frog?audio=loud", true) { Path.of(it) })
        }
        assertFailsWith<IllegalArgumentException> {
            provider.validate(SceneSourceRequest("optical-scene", "scene://milk-frog?unknown=1", true) { Path.of(it) })
        }
    }

    @Test
    fun animatesAt60FpsRespondsTo6DofPoseAndSynthesizesSpatializedStereoLaughAudio() {
        var fakeNow = 0L
        val src = MilkFrogSceneSource(
            width = 320,
            height = 180,
            loop = true,
            motionEnabled = true,
            audioEnabled = true,
            initialPhase = 0.0,
            periodSeconds = 4.8,
            nanoClock = { fakeNow },
        )
        src.use {
            val f0 = it.latestLinearRgbFrame()
            val renders0 = it.framesReceived

            // 5 ms stays within 60 Hz throttle; 20 ms advances render slot cleanly
            fakeNow += 5_000_000L
            assertEquals(renders0, it.framesReceived)
            fakeNow += 15_000_000L
            val f1 = it.latestLinearRgbFrame()
            assertEquals(renders0 + 1L, it.framesReceived)
            assertNotSame(f0.red, f1.red)

            // Jump to laugh burst peak (phase ~ 0.45 -> ~2.16 s) and verify 6-DoF yaw shifts scene visibly
            fakeNow = 2_160_000_000L
            val burstIdentity = it.renderForPose(CameraPose3D.IDENTITY).green.copyOf()
            val burstYawed = it.renderForPose(CameraPose3D(yawDeg = 22.0)).green.copyOf()
            var yawDiff = 0.0
            for (i in burstIdentity.indices) yawDiff += abs(burstIdentity[i] - burstYawed[i])
            assertTrue(yawDiff / burstIdentity.size > 0.02, "Expected clear 6-DoF yaw parallax in Milk Frog scene")

            // Direct Bayer readout succeeds cleanly
            val geom = SensorGeometry(160, 120, 14, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 10_000_000L, 100, LensParameters("gf-63", 63.0, 2.8, 1.55, 0.9, 0.0))
            val dst = ByteBuffer.allocate(geom.width * geom.height * 2)
            it.radiance(exp, geom, dst)
            assertEquals(0, dst.remaining())

            // Spatialized 48 kHz stereo laugh PCM verification
            val frames = 2400 // 50 ms @ 48 kHz
            val pcmCenter = ShortArray(frames * 2)
            assertEquals(frames, it.sampleAudioStereo48k(pcmCenter, frames, CameraPose3D.IDENTITY))
            fun rmsLR(pcm: ShortArray): Pair<Double, Double> {
                var sumL = 0.0
                var sumR = 0.0
                val n = pcm.size / 2
                for (i in 0 until n) {
                    val l = pcm[i * 2].toDouble() / 32768.0
                    val r = pcm[i * 2 + 1].toDouble() / 32768.0
                    sumL += l * l
                    sumR += r * r
                }
                return sqrt(sumL / n) to sqrt(sumR / n)
            }
            val (cL, cR) = rmsLR(pcmCenter)
            assertTrue(cL > 0.01 && cR > 0.01, "Expected audible comic laugh stereo PCM (cL=$cL, cR=$cR)")

            // Camera yaw right (+35 deg) moves frog to camera's LEFT -> left channel louder than right!
            val pcmYawRight = ShortArray(frames * 2)
            it.sampleAudioStereo48k(pcmYawRight, frames, CameraPose3D(yawDeg = 35.0))
            val (yrL, yrR) = rmsLR(pcmYawRight)
            assertTrue(yrL > yrR * 1.35, "Yawing camera right must pan Milk Frog to left ear (L=$yrL, R=$yrR)")

            // Camera yaw left (-35 deg) moves frog to camera's RIGHT -> right channel louder than left!
            val pcmYawLeft = ShortArray(frames * 2)
            it.sampleAudioStereo48k(pcmYawLeft, frames, CameraPose3D(yawDeg = -35.0))
            val (ylL, ylR) = rmsLR(pcmYawLeft)
            assertTrue(ylR > ylL * 1.35, "Yawing camera left must pan Milk Frog to right ear (L=$ylL, R=$ylR)")

            // Moving farther away attenuates volume naturally on the exact same audio slice
            val pcmNear = ShortArray(frames * 2)
            val pcmFar = ShortArray(frames * 2)
            MilkFrogSceneSource(width = 64, height = 36, motionEnabled = false, audioEnabled = true, initialPhase = 0.35).use { nearSrc ->
                nearSrc.sampleAudioStereo48k(pcmNear, frames, CameraPose3D.IDENTITY)
            }
            MilkFrogSceneSource(width = 64, height = 36, motionEnabled = false, audioEnabled = true, initialPhase = 0.35).use { farSrc ->
                farSrc.sampleAudioStereo48k(pcmFar, frames, CameraPose3D(posZM = -2.5))
            }
            val (nL, nR) = rmsLR(pcmNear)
            val (fL, fR) = rmsLR(pcmFar)
            assertTrue((fL + fR) < (nL + nR) * 0.65, "Moving camera farther away should attenuate laugh audio (near=${nL + nR}, far=${fL + fR})")
        }

        // Keep the articulated mesh within the scene budget and time the 640x480 rasterizer.
        assertTrue(MilkFrogSceneSource.BAKED_MESH.triCount in 8_000..25_000, "Expected the milk frog mesh to fit the runtime triangle budget")
        assertTrue(MilkFrogSceneSource.BAKED_AUDIO.samples.size >= 48_000, "Expected real clipped viral laugh audio")
        MilkFrogSceneSource(width = 640, height = 480, motionEnabled = true, audioEnabled = true, nanoClock = { fakeNow }).use { bench ->
            // Warm up JIT
            repeat(6) { k ->
                fakeNow += 20_000_000L
                bench.renderForPose(CameraPose3D(yawDeg = (k - 3) * 1.5))
            }
            val t0 = System.nanoTime()
            val iters = 12
            repeat(iters) { k ->
                fakeNow += 20_000_000L
                bench.renderForPose(CameraPose3D(yawDeg = (k - 6) * 0.8))
            }
            val avgMs = (System.nanoTime() - t0) / 1_000_000.0 / iters
            println("[MILK-FROG-MESH-BENCH] 640x480 avg=${"%.2f".format(avgMs)} ms/frame (${MilkFrogSceneSource.BAKED_MESH.triCount} tris)")
            // Shared K8s CI runners execute 20+ Gradle test suites concurrently (~42ms measured on CI); keep generous regression ceiling.
            assertTrue(avgMs < 250.0, "640x480 Milk Frog mesh rasterization too slow: ${avgMs}ms/frame")
        }

        // audio=off guarantees strict silence
        MilkFrogSceneSource.parseAndOpen("scene://milk-frog?motion=off&audio=off&phase=0.45", 160, 90, true).use { silent ->
            assertFalse(silent.sceneAudioEnabled)
            val buf = ShortArray(480 * 2) { 1234 }
            assertEquals(480, silent.sampleAudioStereo48k(buf, 480, CameraPose3D.IDENTITY))
            assertTrue(buf.all { it == 0.toShort() }, "audio=off must emit strictly zero PCM")
        }
    }
}
