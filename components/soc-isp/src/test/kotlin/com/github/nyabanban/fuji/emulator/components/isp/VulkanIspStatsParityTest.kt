package com.github.nyabanban.fuji.emulator.components.isp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the Vulkan 1.1 GPU ISP Statistics Parallel Reduction pipeline (`isp_stats_reduce.comp`
 * -> [VulkanIspStatsBridge] -> [IspStatsSramEngine.ingestGpuStats]) against the CPU reference
 * [IspStatsSramEngine.updateAeFromBayer] across uniform patches, 2D gradients, Macbeth 24-patch
 * ColorChecker, highlight-excluded AWB, sharp vs blurred QR CDAF, and post-ISP RGBA8 inputs.
 */
class VulkanIspStatsParityTest {

    private fun putBayer16Quad(raw: ByteArray, w: Int, x: Int, y: Int, r16: Int, g1_16: Int, g2_16: Int, b16: Int) {
        fun wr(px: Int, py: Int, v: Int) {
            val off = (py * w + px) shl 1
            raw[off] = (v and 0xFF).toByte()
            raw[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        wr(x, y, r16)
        wr(x + 1, y, g1_16)
        wr(x, y + 1, g2_16)
        wr(x + 1, y + 1, b16)
    }

    private fun readSramU32(engine: IspStatsSramEngine, surfaceWordOffset: Int): Long =
        engine.readResultSurfaceWord(surfaceWordOffset)

    private fun readSramU64(engine: IspStatsSramEngine, surfaceWordOffset: Int): Long {
        val lo = engine.readResultSurfaceWord(surfaceWordOffset)
        val hi = engine.readResultSurfaceWord(surfaceWordOffset + 4)
        return (hi shl 32) or lo
    }

    private fun reducePreferVulkan(
        input: ByteArray,
        width: Int,
        height: Int,
        inputFormat: Int,
        stepQuad: Int = if (width >= 512 && height >= 256) 2 else 1,
        awbHighlightThresh10: Int = 960,
        afRoiX0: Int = 0,
        afRoiY0: Int = 0,
        afRoiX1: Int = width,
        afRoiY1: Int = height,
    ): GpuIspStatsSnapshot {
        VulkanIspStatsBridge.createContext(allowSoftware = true)?.use { ctx ->
            val gpu = VulkanIspStatsBridge.reduceWithContext(
                ctx = ctx,
                input = input,
                width = width,
                height = height,
                inputFormat = inputFormat,
                stepQuad = stepQuad,
                awbHighlightThresh10 = awbHighlightThresh10,
                afRoiX0 = afRoiX0,
                afRoiY0 = afRoiY0,
                afRoiX1 = afRoiX1,
                afRoiY1 = afRoiY1,
            )
            if (gpu != null) return gpu
        }
        val cpuRef = VulkanIspStatsBridge.reduceCpuRef(
            input = input,
            width = width,
            height = height,
            inputFormat = inputFormat,
            stepQuad = stepQuad,
            awbHighlightThresh10 = awbHighlightThresh10,
            afRoiX0 = afRoiX0,
            afRoiY0 = afRoiY0,
            afRoiX1 = afRoiX1,
            afRoiY1 = afRoiY1,
        )
        assertNotNull(cpuRef, "Native fuji_vk_stats_reduce_cpu_ref must succeed when libfuji_vulkan_optics is present")
        return cpuRef!!
    }

    private fun assertAeSramBitExact(cpu: IspStatsSramEngine, gpu: IspStatsSramEngine) {
        // SEL0..3 (secondary 16x16 grid: 4 * 256 words)
        for (i in 0 until 4 * 256) {
            val off = i * 4
            assertEquals(cpu.readHead(off, 4), gpu.readHead(off, 4), "SEL0..3 mismatch at word $i")
        }
        // SEL6..9 (primary 16x16 grid: 4 * 256 words at resultSurface offset 0..0xFFF)
        for (i in 0 until 4 * 256) {
            val off = i * 4
            assertEquals(readSramU32(cpu, off), readSramU32(gpu, off), "SEL6..9 mismatch at word $i")
        }
        // SEL10 (10 multi-window AE accumulators: 10 * 4 * u64 at resultSurface offset 0x1000)
        for (w in 0 until 10) {
            for (ch in 0 until 4) {
                val off = (IspStatsSramEngine.SEL10_OFF - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF) + w * 32 + ch * 8
                assertEquals(readSramU64(cpu, off), readSramU64(gpu, off), "SEL10 mismatch at win=$w ch=$ch")
            }
        }
        assertEquals(cpu.lastMeanAeCode, gpu.lastMeanAeCode, "lastMeanAeCode must match exactly")
    }

    @Test
    fun `solid uniform 16-bit Bayer matches CPU IspStatsSramEngine bit-for-bit`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")

        val w = 512
        val h = 384
        val raw = ByteArray(w * h * 2)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                putBayer16Quad(raw, w, x, y, 400 shl 6, 500 shl 6, 500 shl 6, 300 shl 6)
            }
        }

        val cpuEngine = IspStatsSramEngine()
        cpuEngine.updateAeFromBayer(raw = raw, srcW = w, srcH = h, is16Bit = true, reg5c = 0L, reg60 = 0L)

        val stats = reducePreferVulkan(raw, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE)
        val gpuEngine = IspStatsSramEngine()
        gpuEngine.ingestGpuStats(stats = stats, srcW = w, srcH = h, reg5c = 0L, reg60 = 0L)

        assertAeSramBitExact(cpuEngine, gpuEngine)
        assertEquals(0L, stats.cdafHorizTenengrad, "Flat frame must have 0 horizontal CDAF")
        assertEquals(0L, stats.cdafVertTenengrad, "Flat frame must have 0 vertical CDAF")
        assertEquals(stats.awbGlobalCount, stats.awbValidCount, "No pixels exceed highlight threshold")
        assertEquals(stats.totalHistPixels, gpuEngine.lastHistPixels)
        assertEquals(stats.totalHistPixels, gpuEngine.readHistogram(100 * 4 + 0x400, 4), "R histogram peak at bin 100")
        assertEquals(stats.totalHistPixels, gpuEngine.readHistogram(125 * 4 + 0x800, 4), "G histogram peak at bin 125")
        assertEquals(stats.totalHistPixels, gpuEngine.readHistogram(75 * 4 + 0xC00, 4), "B histogram peak at bin 75")
        assertEquals(1L, gpuEngine.gpuStatsFramesUpdated)
    }

    @Test
    fun `1024x768 2D gradient ramp with saturated highlights matches CPU SRAM bit-for-bit`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")

        val w = 1024
        val h = 768
        val raw = ByteArray(w * h * 2)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val r10 = (x * 1023) / (w - 1)
                val g10 = (y * 1023) / (h - 1)
                val b10 = ((x + y) * 511) / (w + h - 2)
                putBayer16Quad(raw, w, x, y, r10 shl 6, g10 shl 6, g10 shl 6, b10 shl 6)
            }
        }
        val reg5c = (63L) // cellW = 64
        val reg60 = (47L) // cellH = 48

        val cpuEngine = IspStatsSramEngine()
        cpuEngine.updateAeFromBayer(raw = raw, srcW = w, srcH = h, is16Bit = true, reg5c = reg5c, reg60 = reg60)

        val stats = reducePreferVulkan(
            raw, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE,
            stepQuad = 2,
            awbHighlightThresh10 = 960,
        )
        val gpuEngine = IspStatsSramEngine()
        gpuEngine.ingestGpuStats(stats = stats, srcW = w, srcH = h, reg5c = reg5c, reg60 = reg60)

        assertAeSramBitExact(cpuEngine, gpuEngine)
        assertTrue(stats.awbValidCount in 1 until stats.awbGlobalCount, "Highlight exclusion must filter saturated gradient pixels")
        assertEquals(stats.awbGlobalCount, stats.totalHistPixels)
        assertTrue(stats.histY.sum().toLong() == stats.totalHistPixels, "Histogram Y bins must sum to total pixels")
    }

    @Test
    fun `Macbeth 24-patch synthetic ColorChecker AE and AWB parity`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")

        val w = 768
        val h = 512
        val raw = ByteArray(w * h * 2)
        val patchRgb10 = arrayOf(
            intArrayOf(340, 220, 170), intArrayOf(680, 480, 400), intArrayOf(240, 390, 560), intArrayOf(220, 350, 190),
            intArrayOf(420, 410, 620), intArrayOf(280, 660, 590), intArrayOf(790, 410, 130), intArrayOf(180, 260, 580),
            intArrayOf(720, 220, 280), intArrayOf(280, 160, 360), intArrayOf(580, 690, 190), intArrayOf(840, 590, 160),
            intArrayOf(120, 180, 540), intArrayOf(190, 520, 220), intArrayOf(690, 150, 160), intArrayOf(890, 740, 120),
            intArrayOf(680, 230, 540), intArrayOf(110, 480, 620), intArrayOf(990, 990, 990), intArrayOf(740, 740, 740),
            intArrayOf(520, 520, 520), intArrayOf(320, 320, 320), intArrayOf(180, 180, 180), intArrayOf(70, 70, 70),
        )
        for (y in 0 until h step 2) {
            val pr = (y * 4) / h
            for (x in 0 until w step 2) {
                val pc = (x * 6) / w
                val rgb = patchRgb10[pr * 6 + pc]
                putBayer16Quad(raw, w, x, y, rgb[0] shl 6, rgb[1] shl 6, rgb[1] shl 6, rgb[2] shl 6)
            }
        }

        val cpuEngine = IspStatsSramEngine()
        cpuEngine.updateAeFromBayer(raw = raw, srcW = w, srcH = h, is16Bit = true, reg5c = 0L, reg60 = 0L)

        val stats = reducePreferVulkan(raw, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE, awbHighlightThresh10 = 960)
        val gpuEngine = IspStatsSramEngine()
        gpuEngine.ingestGpuStats(stats = stats, srcW = w, srcH = h, reg5c = 0L, reg60 = 0L)

        assertAeSramBitExact(cpuEngine, gpuEngine)
        // Patch 18 (row 3, col 0) is 990 (clipped white > 960 threshold), so valid AWB count is ~23/24 of global
        val expectedExcludedRatio = stats.awbValidCount.toDouble() / stats.awbGlobalCount.toDouble()
        assertTrue(expectedExcludedRatio in 0.90..0.98, "White patch should be excluded from valid AWB (ratio=$expectedExcludedRatio)")
    }

    @Test
    fun `sharp vs blurred QR checkerboard produces strong CDAF SRAM energy separation`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")

        val w = 512
        val h = 384
        val sharp = ByteArray(w * h * 2)
        val blurred = ByteArray(w * h * 2)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val hi = (((x / 4) xor (y / 4)) and 1) != 0
                val sv = if (hi) (900 shl 6) else (100 shl 6)
                val bv = if (hi) (540 shl 6) else (460 shl 6)
                putBayer16Quad(sharp, w, x, y, sv, sv, sv, sv)
                putBayer16Quad(blurred, w, x, y, bv, bv, bv, bv)
            }
        }

        val sharpStats = reducePreferVulkan(
            sharp, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE,
            stepQuad = 1, afRoiX0 = 128, afRoiY0 = 96, afRoiX1 = 384, afRoiY1 = 288,
        )
        val blurStats = reducePreferVulkan(
            blurred, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE,
            stepQuad = 1, afRoiX0 = 128, afRoiY0 = 96, afRoiX1 = 384, afRoiY1 = 288,
        )

        assertTrue(sharpStats.cdafHorizTenengrad > blurStats.cdafHorizTenengrad * 20L, "Sharp H Tenengrad must exceed blurred by >20x")
        assertTrue(sharpStats.cdafVertTenengrad > blurStats.cdafVertTenengrad * 20L, "Sharp V Tenengrad must exceed blurred by >20x")
        assertTrue(sharpStats.cdafHorizLaplacian > blurStats.cdafHorizLaplacian * 20L, "Sharp H Laplacian must exceed blurred by >20x")
        assertTrue(sharpStats.cdafVertLaplacian > blurStats.cdafVertLaplacian * 20L, "Sharp V Laplacian must exceed blurred by >20x")

        val sharpEngine = IspStatsSramEngine()
        val blurEngine = IspStatsSramEngine()
        sharpEngine.ingestGpuStats(sharpStats, srcW = w, srcH = h, reg5c = 0L, reg60 = 0L)
        blurEngine.ingestGpuStats(blurStats, srcW = w, srcH = h, reg5c = 0L, reg60 = 0L)

        val sharpWinLo0 = sharpEngine.readHead(IspStatsSramEngine.SEL4_OFF, 4) or
            (sharpEngine.readHead(IspStatsSramEngine.SEL4_OFF + 4, 4) shl 32)
        val blurWinLo0 = blurEngine.readHead(IspStatsSramEngine.SEL4_OFF, 4) or
            (blurEngine.readHead(IspStatsSramEngine.SEL4_OFF + 4, 4) shl 32)
        assertTrue(sharpWinLo0 > blurWinLo0 * 20L, "SEL4 Tenengrad SRAM window must preserve >20x focus separation")

        val sharpWinHi0 = sharpEngine.readHead(IspStatsSramEngine.SEL5_OFF, 4) or
            (sharpEngine.readHead(IspStatsSramEngine.SEL5_OFF + 4, 4) shl 32)
        val blurWinHi0 = blurEngine.readHead(IspStatsSramEngine.SEL5_OFF, 4) or
            (blurEngine.readHead(IspStatsSramEngine.SEL5_OFF + 4, 4) shl 32)
        assertTrue(sharpWinHi0 > blurWinHi0 * 20L, "SEL5 Laplacian SRAM window must preserve >20x focus separation")
    }

    @Test
    fun `packed RGBA8 post-ISP stats closes fused-YUV stats gap`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")

        val w = 256
        val h = 192
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                val hi = ((x / 4) and 1) != 0
                rgba[o] = (if (hi) 220 else 60).toByte()
                rgba[o + 1] = (if (hi) 200 else 80).toByte()
                rgba[o + 2] = (if (hi) 180 else 100).toByte()
                rgba[o + 3] = 255.toByte()
            }
        }

        val stats = reducePreferVulkan(rgba, w, h, VulkanIspStatsBridge.FMT_PACKED_RGBA8, stepQuad = 1)
        val engine = IspStatsSramEngine()
        engine.ingestGpuStats(stats, srcW = w, srcH = h, reg5c = 0L, reg60 = 0L, commitHist = true)

        assertEquals(1L, engine.aeFramesUpdated)
        assertEquals(1L, engine.histFramesUpdated)
        assertEquals(stats.totalHistPixels, engine.lastHistPixels)
        assertTrue(stats.cdafHorizTenengrad > 0L, "Vertical stripe RGBA8 image must produce horizontal CDAF energy")
        assertEquals(0L, stats.cdafVertTenengrad, "Vertical stripe RGBA8 image must produce 0 vertical CDAF energy")
        assertTrue(engine.lastMeanAeCode > 0, "AE grid must be populated from RGBA8 post-ISP frame")
    }

    @Test
    fun `Vulkan SPIR-V GPU execution matches native C++ CPU ref on all 2320 words when ICD is present`() {
        assumeTrue(VulkanIspStatsBridge.isNativeLibraryAvailable, "libfuji_vulkan_optics not built in this environment")
        val ctx = VulkanIspStatsBridge.createContext(allowSoftware = true)
        assumeTrue(ctx != null, "No Vulkan compute ICD (hardware or Lavapipe) available in this container")

        ctx!!.use { vkCtx ->
            val w = 512
            val h = 384
            val raw = ByteArray(w * h * 2)
            for (y in 0 until h step 2) {
                for (x in 0 until w step 2) {
                    val r10 = ((x * 9 + y * 3) % 1024)
                    val g10 = ((x * 5 + y * 7) % 1024)
                    val b10 = ((x * 11 + y * 2) % 1024)
                    putBayer16Quad(raw, w, x, y, r10 shl 6, g10 shl 6, g10 shl 6, b10 shl 6)
                }
            }

            val gpu = VulkanIspStatsBridge.reduceWithContext(vkCtx, raw, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE)
            val ref = VulkanIspStatsBridge.reduceCpuRef(raw, w, h, VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE)
            assertNotNull(gpu, "Vulkan GPU dispatch failed on ${vkCtx.deviceName}")
            assertNotNull(ref, "Native CPU ref failed")
            gpu!!
            ref!!

            assertTrue(gpu.usedGpu)
            assertArrayEquals(ref.aeSumR, gpu.aeSumR, "aeSumR GPU vs CPU ref mismatch")
            assertArrayEquals(ref.aeSumG1, gpu.aeSumG1, "aeSumG1 GPU vs CPU ref mismatch")
            assertArrayEquals(ref.aeSumB, gpu.aeSumB, "aeSumB GPU vs CPU ref mismatch")
            assertArrayEquals(ref.aeSumG2, gpu.aeSumG2, "aeSumG2 GPU vs CPU ref mismatch")
            assertArrayEquals(ref.aeCount, gpu.aeCount, "aeCount GPU vs CPU ref mismatch")
            assertArrayEquals(ref.histY, gpu.histY, "histY GPU vs CPU ref mismatch")
            assertArrayEquals(ref.histR, gpu.histR, "histR GPU vs CPU ref mismatch")
            assertArrayEquals(ref.histG, gpu.histG, "histG GPU vs CPU ref mismatch")
            assertArrayEquals(ref.histB, gpu.histB, "histB GPU vs CPU ref mismatch")
            assertEquals(ref.awbGlobalSumR, gpu.awbGlobalSumR)
            assertEquals(ref.awbGlobalSumG, gpu.awbGlobalSumG)
            assertEquals(ref.awbGlobalSumB, gpu.awbGlobalSumB)
            assertEquals(ref.awbGlobalCount, gpu.awbGlobalCount)
            assertEquals(ref.awbValidSumR, gpu.awbValidSumR)
            assertEquals(ref.awbValidSumG, gpu.awbValidSumG)
            assertEquals(ref.awbValidSumB, gpu.awbValidSumB)
            assertEquals(ref.awbValidCount, gpu.awbValidCount)
            assertEquals(ref.cdafHorizTenengrad, gpu.cdafHorizTenengrad)
            assertEquals(ref.cdafHorizLaplacian, gpu.cdafHorizLaplacian)
            assertEquals(ref.cdafVertTenengrad, gpu.cdafVertTenengrad)
            assertEquals(ref.cdafVertLaplacian, gpu.cdafVertLaplacian)
            assertEquals(ref.cdafRoiMaxG14, gpu.cdafRoiMaxG14)
            assertEquals(ref.cdafRoiSumG10, gpu.cdafRoiSumG10)
            assertEquals(ref.cdafRoiCount, gpu.cdafRoiCount)
            assertEquals(ref.totalHistPixels, gpu.totalHistPixels)
        }
    }
}
