package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Unit tests for [VulkanNativeScene3dBridge], [VulkanScene3dRenderer], and [VulkanOpticalBackend]'s
 * GPU-resident 3D scene rasterizer & raytracer pipeline (`W13-VULKAN-3D-SCENE-RASTERIZER`).
 */
class VulkanScene3dUnitTest {

    @Test
    fun cpuBackendDisablesGpuScene3dAndVulkanRendersAllFourScene3dSpecs() {
        // 1. CPU backend must keep GPU Scene3D off (100% CPU fallback)
        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { cpuBackend ->
            assertEquals(ActiveOpticsBackend.CPU, cpuBackend.activeBackend)
            assertFalse(cpuBackend.supportsGpuScene3d())
            val filter = LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                computeBackendProvider = { cpuBackend },
            )
            assertFalse(filter.supportsGpuScene3d())
        }

        // 2. Vulkan backend (hardware or Mesa Lavapipe)
        VulkanOpticalBackend(preference = OpticsBackendPreference.AUTO, allowSoftwareVulkan = true).use { vkBackend ->
            assumeTrue(
                vkBackend.activeBackend == ActiveOpticsBackend.VULKAN && vkBackend.supportsGpuScene3d(),
                "Vulkan Scene3D device unavailable on host (${vkBackend.diagnostics().fallbackReason})",
            )

            val w = 256
            val h = 144

            // A. Milk Frog tiled triangle rasterizer spec (front warm triangle @ 1.50m over far blue triangle @ 1.90m)
            val stage = FloatArray(w * h * 4) { i ->
                when (i and 3) {
                    0 -> 0.02f
                    1 -> 0.03f
                    2 -> 0.05f
                    else -> 10.5f
                }
            }
            val verts = floatArrayOf(
                -0.36f, -0.30f, -0.05f, 0f, 0f, 0f, -1f, 0f, 0.90f, 0.68f, 0.25f, 0f,
                0.36f, -0.30f, -0.05f, 0f, 0f, 0f, -1f, 0f, 0.90f, 0.68f, 0.25f, 0f,
                0.00f, 0.42f, -0.05f, 0f, 0f, 0f, -1f, 0f, 0.92f, 0.72f, 0.28f, 0f,
                -0.45f, -0.35f, 0.35f, 1f, 0f, 0f, -1f, 0f, 0.10f, 0.20f, 0.90f, 0f,
                0.45f, -0.35f, 0.35f, 1f, 0f, 0f, -1f, 0f, 0.10f, 0.20f, 0.90f, 0f,
                0.00f, 0.48f, 0.35f, 1f, 0f, 0f, -1f, 0f, 0.10f, 0.20f, 0.90f, 0f,
            )
            val indices = intArrayOf(0, 1, 2, 3, 5, 4)
            val orbs = FloatArray(64)
            orbs[0] = 24f; orbs[1] = 24f; orbs[2] = 6f; orbs[3] = 10f
            orbs[4] = 6.5f; orbs[5] = 4.5f; orbs[6] = 1.5f; orbs[7] = 0.65f

            val mfSpec = GpuScene3dRenderSpec.MilkFrog(
                width = w, height = h, pose = CameraPose3D.IDENTITY, sceneKey = 0x1111L, sequence = 1L,
                meshKey = 0x2222L, vertexDataProvider = { verts }, vertexCount = 6,
                indicesProvider = { indices }, triCount = 2,
                stageKey = 0x3333L, stageRgbaDepthProvider = { stage },
                hasPose = false, hasTranslation = false,
                halfW = (w - 1) * 0.5f, halfH = (h - 1) * 0.5f, focal = w * 0.76f, invFocalScale = 1f,
                ox = 0f, oy = 0f, oz = 0f, cR = 1f, sR = 0f, yawShiftPx = 0f, pitchShiftPx = 0f,
                posXM = 0f, posYM = 0f, posZM = 0f,
                r00 = 1f, r01 = 0f, r02 = 0f, r10 = 0f, r11 = 1f, r12 = 0f, r20 = 0f, r21 = 0f, r22 = 1f,
                frogX = 0f, frogY = 0f, frogZ = 1.55f,
                cLean = 1f, sLean = 0f, cRock = 1f, sRock = 0f,
                bellySquash = 0f, handBounce = 0f, kickPhase = 0f, mouthVib = 0f, arcPulse = 0f,
                orbCount = 1, pedZ = 1.55f, pedTopY = -0.458f, pedBotY = -0.538f, pedRad = 0.36f, orbs = orbs,
            )
            val (mfRgba, mfGrid) = checkNotNull(vkBackend.renderGpuScene3dForReadback(mfSpec))
            val cIdx = (h / 2) * w + (w / 2)
            assertTrue(abs(mfRgba[cIdx * 4 + 3] - 1.50f) < 0.05f, "Front triangle Z-buffer depth mismatch: ${mfRgba[cIdx * 4 + 3]}")
            assertTrue(mfRgba[cIdx * 4] > mfRgba[cIdx * 4 + 2] * 1.8f, "Front yellow triangle must occlude far blue triangle")
            assertNotNull(mfGrid.centerDepthMeters)
            assertTrue(abs(mfGrid.centerDepthMeters!! - 1.50) < 0.15)

            // The planar source and the 3D renderer share the same device image.
            val empty = FloatArray(w * h)
            val planar = LinearRgbImage(w, h, empty, empty, empty)
            vkBackend.renderLinearRgb(planar, sceneCacheKey = 0xA11L)
            val (restored, _) = checkNotNull(vkBackend.renderGpuScene3dForReadback(mfSpec))
            assertTrue(mfRgba.contentEquals(restored), "Returning to the same pose must restore the 3D device image")
            assertEquals(2L, vkBackend.gpuScene3dFramesRendered)
            assertEquals(2L, vkBackend.gpuScene3dStaticUploads, "Restoring the frame must reuse both mesh and stage")
            vkBackend.renderGpuScene3dForReadback(mfSpec)
            assertEquals(2L, vkBackend.gpuScene3dFramesRendered, "Consecutive users must share the resident frame")
            assertEquals(0.0, vkBackend.gpuScene3dLastMs, "A resident 3D scene must charge no rasterization time to this request")

            // B. QR-Ball analytic 6-DoF raytracer
            val qrRows = IntArray(32) { r -> if ((r and 1) == 0) 0x00AAAAAA else 0x01555555 }
            val qbSpec = GpuScene3dRenderSpec.QrBall(
                width = w, height = h, pose = CameraPose3D.IDENTITY, sceneKey = 0x1112L, sequence = 2L,
                qrKey = 0x4444L, qrRowBits32 = qrRows, qrModules = 25, quietZone = 4, totalQrModules = 33,
                halfW = w * 0.5f, halfH = h * 0.5f, focal = w * 0.60f,
                ox = 0f, oy = 0f, oz = 0f,
                r00 = 1f, r01 = 0f, r02 = 0f, r10 = 0f, r11 = 1f, r12 = 0f, r20 = 0f, r21 = 0f, r22 = 1f,
                wallZ = 5.5f,
            )
            val (qbRgba, _) = checkNotNull(vkBackend.renderGpuScene3dForReadback(qbSpec))
            val sphIdx = (h * 0.56f).toInt() * w + (w * 0.34f).toInt()
            val wallIdx = (h / 2) * w + (w * 0.72f).toInt()
            assertTrue(qbRgba[sphIdx * 4 + 3] in 0.80f..1.08f, "QR-Ball sphere depth out of range: ${qbRgba[sphIdx * 4 + 3]}")
            assertTrue(abs(qbRgba[wallIdx * 4 + 3] - 5.5f) < 0.05f, "QR-Ball wall depth mismatch: ${qbRgba[wallIdx * 4 + 3]}")

            // C. Direct zero-upload Scene3D -> Optics -> CMOS Bayer handoff
            val geom = SensorGeometry(w, h, 12, BayerPattern.RGGB, 3.76)
            val exp = Exposure(
                startTick = 0L,
                durationNanos = 10_000_000L,
                iso = 200,
                lens = LensParameters("gf-45", 45.0, 2.8, 1.55, 0.9, 0.0),
            )
            val dstBayer = ByteBuffer.allocateDirect(w * h * 2)
            val bayerRes = vkBackend.tryRenderGpuScene3dToBayer(
                spec = mfSpec,
                geometry = geom,
                exposure = exp,
                profile = OpticalLensProfile.IDENTITY.copy(focalLengthMm = 45.0, focusDistanceM = 1.55, fNumber = 2.8),
                noiseConfig = CmosNoiseConfig.NOISELESS,
                destination = dstBayer,
            )
            assertNotNull(bayerRes)
            assertEquals(w * h * 2, bayerRes!!.first)
            assertTrue(vkBackend.gpuScene3dFramesRendered >= 2L)
        }
    }
}
