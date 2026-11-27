package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QrBallRotationTest {
    private val provider = ProceduralOpticsSceneSourceProvider(240, 144)
    private fun request(uri: String) = SceneSourceRequest("optical-scene", uri, true) { Path.of(it) }

    @Test
    fun onlyTheSphereTurnsOnCameraTime() {
        var nanos = 0L
        val pose = CameraPose3D.IDENTITY
        (provider.open(request("scene://qr-ball")) { nanos } as RotatingQrBallSource).use { scene ->
            val first = scene.renderForPose(pose)
            val colors = first.red.copyOf()
            val depth = requireNotNull(first.depthMeters).copyOf()
            val before = scene.gpuScene3dRenderSpec(pose) as GpuScene3dRenderSpec.QrBall
            assertSame(first, scene.renderForPose(pose))
            nanos = 250_000_000L
            val after = scene.gpuScene3dRenderSpec(pose) as GpuScene3dRenderSpec.QrBall
            assertEquals(1f / 32f, after.rotationTurns)
            assertNotEquals(before.sceneKey, after.sceneKey)
            val rotated = scene.renderForPose(pose)
            assertContentEquals(depth, rotated.depthMeters)
            var changed = 0
            for (i in depth.indices) {
                if (colors[i] != rotated.red[i]) {
                    assertTrue(depth[i] in 0.8f..1.28f, "Non-sphere pixel changed at $i")
                    changed++
                }
            }
            assertTrue(changed > 400)
            nanos = 8_000_000_000L
            assertContentEquals(colors, scene.renderForPose(pose).red)
            assertEquals(before.sceneKey, scene.gpuScene3dRenderSpec(pose).sceneKey)
        }
    }

    @Test
    fun cameraTicksAdvanceAtEverySixtiethSecond() {
        val ticksPerFrame = 900_000L
        val nearMaximumNanosTick = Long.MAX_VALUE / 1_000_000_000L * 54_000_000L - 300_000_000L
        for (start in listOf(0L, 53L, nearMaximumNanosTick)) {
            var tick = start
            val request = request("scene://qr-ball?period=2")
            (provider.open(request) { cameraNanos(tick) } as RotatingQrBallSource).use { moving ->
                for (index in 1L..240L) {
                    val boundary = index * ticksPerFrame
                    val offsets = listOf(boundary - 1, boundary, boundary + 1, index * 900_900L, index * 901_739L + 23L)
                    for (offset in offsets) {
                        tick = start + offset
                        val expected = offset / ticksPerFrame
                        assertEquals(expected, moving.fusedFrameSequence, "start=$start offset=$offset")
                        assertEquals(expected + 1L, moving.latestFrameSequence)
                        val spec = moving.gpuScene3dRenderSpec(CameraPose3D.IDENTITY) as GpuScene3dRenderSpec.QrBall
                        assertEquals((expected % 120L) / 120f, spec.rotationTurns)
                    }
                }
            }
        }
    }

    @Test
    fun frameClockHandlesLongUptimeAndNanoTimeWraparound() {
        var nanos = 0L
        val rotation = QrBallRotation.parse("scene://qr-ball") { nanos }
        nanos = Long.MAX_VALUE
        assertEquals(553_402_322_211L, rotation.frame())

        nanos = Long.MAX_VALUE - 100_000L
        val wrappingClock = QrBallRotation.parse("scene://qr-ball") { nanos }
        nanos += 16_666_666L
        assertEquals(1L, wrappingClock.frame())
        nanos += 16_666_667L
        assertEquals(2L, wrappingClock.frame())
    }

    @Test
    fun fixedPhaseAndCustomPeriodUseTheSameOrientation() {
        var nanos = 0L
        val pose = CameraPose3D.IDENTITY
        val uri = "scene://qr-ball?motion=off&phase=0.125"
        (provider.open(request(uri)) { nanos } as ProceduralOpticsSceneSource).use { fixed ->
            (provider.open(request("scene://qr-ball?period=2")) { nanos } as RotatingQrBallSource).use { moving ->
                val still = fixed.renderForPose(pose)
                nanos = 250_000_000L
                assertSame(still, fixed.renderForPose(pose))
                assertEquals(uri, fixed.activeSourceRef)
                assertContentEquals(still.green, moving.renderForPose(pose).green)
            }
        }
        for (invalid in listOf("period=0", "period=NaN", "phase=1.1", "motion=maybe", "phase=0&phase=1")) {
            assertFailsWith<IllegalArgumentException> { provider.validate(request("scene://qr-ball?$invalid")) }
        }
    }
}
