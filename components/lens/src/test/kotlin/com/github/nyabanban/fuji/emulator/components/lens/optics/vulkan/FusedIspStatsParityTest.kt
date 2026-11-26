package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FusedIspStatsParityTest {
    @Test
    fun fusedOpticsToIspYuvWithStatsMatchesBayerAeGridAndHistogramOnGpu() {
        val backend = VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        )
        try {
            assumeTrue(backend.activeBackend == ActiveOpticsBackend.VULKAN, "Vulkan ICD required")
            assertTrue(backend.supportsFusedIspStats(), "Native Vulkan optics library must export fused-with-stats API")

            val srcW = 128
            val srcH = 72
            val count = srcW * srcH
            val red = FloatArray(count)
            val green = FloatArray(count)
            val blue = FloatArray(count)
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    val idx = y * srcW + x
                    red[idx] = (0.12f + 0.55f * x / srcW)
                    green[idx] = (0.15f + 0.45f * y / srcH)
                    blue[idx] = (0.10f + 0.40f * (x + y) / (srcW + srcH))
                }
            }
            val image = LinearRgbImage(srcW, srcH, red, green, blue)
            val geom = SensorGeometry(srcW, srcH, 16, BayerPattern.RGGB, 3.76)
            val lens = LensParameters("gf-63", 63.0, 2.8, 10.0, 1.0, 0.0)
            val exp = Exposure(0L, 10_000_000L, 100, lens)
            val profile = OpticalLensProfile.IDENTITY
            val noise = CmosNoiseConfig(seed = 0L, enableNoise = false, blackLevel = 256)

            // 1. Reference 16-bit Bayer render on Vulkan
            val bayerBuf = ByteBuffer.allocate(srcW * srcH * 2)
            backend.renderToBayer(
                source = image,
                geometry = geom,
                exposure = exp,
                profile = profile,
                noiseConfig = noise,
                destination = bayerBuf,
                maxBokehRadiusPx = 0,
            )
            val bayer = bayerBuf.array()

            // 2. Fused Optics -> CMOS -> ISP UYVY (4:3 center crop 96x72 -> 128x96) + Pass 6 GPU Stats
            val dstW = 128
            val dstH = 96
            val outUyvy = ByteArray(dstW * dstH * 2)
            val outStats = IntArray(LiveViewIspFusionRequest.STATS_OUTPUT_WORDS)
            val viewX = 16
            val viewY = 0
            val viewW = 96
            val viewH = 72
            val statsReq = LiveViewIspStatsRequest(
                viewX = viewX,
                viewY = viewY,
                viewW = viewW,
                viewH = viewH,
                gridCols = 16,
                gridRows = 16,
                stepQuad = 1,
                awbHighlightThresh10 = 960,
                afRoiX0 = viewX,
                afRoiY0 = viewY,
                afRoiX1 = viewX + viewW,
                afRoiY1 = viewY + viewH,
                flags = 7,
            )
            val ispParams = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(
                srcWidth = srcW,
                srcHeight = srcH,
                dstWidth = dstW,
                dstHeight = dstH,
                is16Bit = true,
                outputFormat = LiveViewIspFusionRequest.FMT_UYVY422,
                dstStrideBytes = dstW * 2,
                flags = LiveViewIspFusionRequest.FLAG_SRC_CROP,
                offX = viewX,
                offY = viewY,
                fitW = viewW,
                fitH = viewH,
                gainR1024 = 1024,
                gainG1024 = 1024,
                gainB1024 = 1024,
                yuvShift = 10,
            )
            val ok = backend.tryRenderOpticsToIspYuv(
                source = image,
                geometry = geom,
                exposure = exp,
                profile = profile,
                noiseConfig = noise,
                maxBokehRadiusPx = 0,
                outYOrPacked = outUyvy,
                outUvPlane = null,
                ispParams = ispParams,
                sceneCacheKey = 0x1234_5678L,
                statsRequest = statsReq,
                outStatsWords = outStats,
            )
            assertTrue(ok, "Fused GPU Optics->ISP with stats must succeed")

            // 3. Compare GPU Pass 6 AE sums & histogram count against CPU reference over [viewX..viewX+viewW)
            var refSumR = 0L
            var refSumG1 = 0L
            var refSumB = 0L
            var refSumG2 = 0L
            var refQuads = 0L
            var y = viewY
            while (y + 1 < viewY + viewH) {
                var x = viewX
                while (x + 1 < viewX + viewW) {
                    val p00 = (y * srcW + x) shl 1
                    val p01 = p00 + 2
                    val p10 = ((y + 1) * srcW + x) shl 1
                    val p11 = p10 + 2
                    refSumR += (((bayer[p00].toInt() and 0xFF) or ((bayer[p00 + 1].toInt() and 0xFF) shl 8)) ushr 6).toLong()
                    refSumG1 += (((bayer[p01].toInt() and 0xFF) or ((bayer[p01 + 1].toInt() and 0xFF) shl 8)) ushr 6).toLong()
                    refSumG2 += (((bayer[p10].toInt() and 0xFF) or ((bayer[p10 + 1].toInt() and 0xFF) shl 8)) ushr 6).toLong()
                    refSumB += (((bayer[p11].toInt() and 0xFF) or ((bayer[p11 + 1].toInt() and 0xFF) shl 8)) ushr 6).toLong()
                    refQuads++
                    x += 2
                }
                y += 2
            }

            var gpuSumR = 0L
            var gpuSumG1 = 0L
            var gpuSumB = 0L
            var gpuSumG2 = 0L
            var gpuQuads = 0L
            for (cell in 0 until 256) {
                gpuSumR += outStats[cell].toLong() and 0xFFFF_FFFFL
                gpuSumG1 += outStats[256 + cell].toLong() and 0xFFFF_FFFFL
                gpuSumB += outStats[512 + cell].toLong() and 0xFFFF_FFFFL
                gpuSumG2 += outStats[768 + cell].toLong() and 0xFFFF_FFFFL
                gpuQuads += outStats[1024 + cell].toLong() and 0xFFFF_FFFFL
            }
            val totalHist = outStats[2319].toLong() and 0xFFFF_FFFFL
            assertEquals(refQuads, gpuQuads, "GPU fused stats quad count must match exactly")
            assertEquals(refQuads, totalHist, "GPU fused histogram pixel count must match quad count")

            val meanDiffR = abs(gpuSumR.toDouble() / gpuQuads - refSumR.toDouble() / refQuads)
            val meanDiffG1 = abs(gpuSumG1.toDouble() / gpuQuads - refSumG1.toDouble() / refQuads)
            val meanDiffB = abs(gpuSumB.toDouble() / gpuQuads - refSumB.toDouble() / refQuads)
            val meanDiffG2 = abs(gpuSumG2.toDouble() / gpuQuads - refSumG2.toDouble() / refQuads)
            assertTrue(meanDiffR <= 1.0, "R AE mean diff too large: $meanDiffR")
            assertTrue(meanDiffG1 <= 1.0, "G1 AE mean diff too large: $meanDiffG1")
            assertTrue(meanDiffB <= 1.0, "B AE mean diff too large: $meanDiffB")
            assertTrue(meanDiffG2 <= 1.0, "G2 AE mean diff too large: $meanDiffG2")
            assertTrue(outUyvy.any { it.toInt() != 0 }, "Fused UYVY output must be non-zero")
        } finally {
            backend.close()
        }
    }
}
