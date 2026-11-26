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
