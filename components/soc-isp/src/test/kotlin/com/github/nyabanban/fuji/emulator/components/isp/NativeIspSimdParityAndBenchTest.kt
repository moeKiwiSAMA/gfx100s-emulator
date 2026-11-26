package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class NativeIspSimdParityAndBenchTest {
    private class FlatDmaMemory(sizeBytes: Int) : DmaMemory {
        val bytes = ByteArray(sizeBytes)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0L && address + size <= bytes.size.toLong())
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            destination.put(bytes, address.toInt(), n)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining()
            source.get(bytes, address.toInt(), n)
        }
    }

    private class SyntheticBayerSensor(
        private val rawFrame: ByteArray,
        private val gen: Long = 1L,
    ) : SensorPort {
        override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
            destination.put(rawFrame)
            return rawFrame.size
        }
        override fun currentFrameGeneration(): Long = gen
    }

    private fun buildSyntheticBayer16(w: Int, h: Int): ByteArray {
        val raw = ByteArray(w * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // High-contrast checkerboard + smooth gradient to exercise demosaic, CCM, LUT, peaking, and AF
                val checker = if (((x / 16) + (y / 16)) % 2 == 0) 1400 else 2800
                val grad = ((x * 900) / w + (y * 600) / h)
                val v12 = (checker + grad + ((x * 7 + y * 13) and 0xFF)).coerceIn(0, 4095)
                val v16 = v12 shl 4
                val off = (y * w + x) * 2
                raw[off] = (v16 and 0xFF).toByte()
                raw[off + 1] = ((v16 ushr 8) and 0xFF).toByte()
            }
        }
        return raw
    }

    private fun buildColorPipeline(): IspColorPipeline {
        val words = LongArray(IspCoreOffsets.BANK_68000_OFFSETS.size)
        // Program realistic Q10 3x3 CCM at 0x300..0x314 (Provia-like saturation)
        val ccm = intArrayOf(1120, -84, -12, -52, 1140, -64, 18, -142, 1148)
        for (row in 0 until 3) {
            val w0 = (ccm[row * 3 + 0] and 0x1FFF).toLong() or ((ccm[row * 3 + 1] and 0x1FFF).toLong() shl 16)
            val w1 = (ccm[row * 3 + 2] and 0x1FFF).toLong()
            words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x300 + row * 8)] = w0
            words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x304 + row * 8)] = w1
        }
        // Program BT.601 Q10 YUV matrix at 0x500..0x514
        val yuv = intArrayOf(306, 601, 117, -173, -339, 512, 512, -429, -83)
        for (row in 0 until 3) {
            val w0 = (yuv[row * 3 + 0] and 0x1FFF).toLong() or ((yuv[row * 3 + 1] and 0x1FFF).toLong() shl 16)
            val w1 = (yuv[row * 3 + 2] and 0x1FFF).toLong()
            words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x500 + row * 8)] = w0
            words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x504 + row * 8)] = w1
        }
        return IspColorPipeline().apply { updateFromBank68000(words) }
    }

    private fun buildSrgbLut(): IntArray = IntArray(4096) { idx ->
        val c = idx / 4095.0
        val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun buildTestLut3d16(): FloatArray {
        val lut = FloatArray(16 * 16 * 16 * 3)
        for (b in 0 until 16) {
            val fb = b / 15.0f
            for (g in 0 until 16) {
                val fg = g / 15.0f
                for (r in 0 until 16) {
                    val fr = r / 15.0f
                    val idx = ((b * 16 + g) * 16 + r) * 3
                    val luma = 0.2126f * fr + 0.7152f * fg + 0.0722f * fb
                    lut[idx + 0] = (fr * 0.96f + luma * 0.05f).coerceIn(0f, 1f)
                    lut[idx + 1] = (fg * 0.95f + luma * 0.04f).coerceIn(0f, 1f)
                    lut[idx + 2] = (fb * 0.90f + luma * 0.08f + (1f - luma) * 0.02f).coerceIn(0f, 1f)
                }
            }
        }
        return lut
    }

    private fun applyLut3dReference(lut: FloatArray, r8: Int, g8: Int, b8: Int): Triple<Int, Int, Int> {
        val scale = 15.0f / 255.0f
        val fr = r8 * scale
        val fg = g8 * scale
        val fb = b8 * scale
        val r0 = fr.toInt().coerceAtMost(14)
        val g0 = fg.toInt().coerceAtMost(14)
        val b0 = fb.toInt().coerceAtMost(14)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val base = ((b0 * 16 + g0) * 16 + r0) * 3
        fun interp(c: Int): Int {
            val c000 = lut[base + c]
            val c100 = lut[base + 3 + c]
            val c010 = lut[base + 48 + c]
            val c110 = lut[base + 51 + c]
            val c001 = lut[base + 768 + c]
            val c101 = lut[base + 771 + c]
            val c011 = lut[base + 816 + c]
            val c111 = lut[base + 819 + c]
            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            val v = (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
            return (v * 255.0f + 0.5f).toInt().coerceIn(0, 255)
        }
        return Triple(interp(0), interp(1), interp(2))
    }

    private fun requireOrAssumeNativeAvailable() {
        val required = System.getenv("FUJI_REQUIRE_ISP_SIMD") == "1" ||
            !System.getenv("FUJI_ISP_SIMD_LIBRARY").isNullOrBlank()
        if (required) {
            assertTrue(NativeIspSimdBridge.isAvailable, "Expected libfuji_isp_simd to load when FUJI_REQUIRE_ISP_SIMD=1 / FUJI_ISP_SIMD_LIBRARY is set")
        } else {
            assumeTrue(NativeIspSimdBridge.isAvailable, "libfuji_isp_simd not present; build with bash tools/native/isp-simd/build_isp_simd.sh linux")
        }
    }

    @Test
    fun `native ISP SIMD matches pure Kotlin LiveViewDmaEngine within 1 LSB across UYVY422 YYUV422 NV16 NV12 and histogram`() {
        requireOrAssumeNativeAvailable()

        val w = 1024
        val h = 576
        val raw = buildSyntheticBayer16(w, h)
        val sensor = SyntheticBayerSensor(raw)
        val lens = LensParameters("GF63mmF2.8", 63.0, 2.8, 5.0, 0.9, 0.0)
        val exposure = LiveViewDmaEngine.ExposureParams(1000L, 400, gainR1024 = 1120, gainG1024 = 1024, gainB1024 = 1180)
        val color = buildColorPipeline()
        val peaking = MfAssistPeakingFilter.fromWords(0L, 0x03C0_0000L, 0x80L, 2L, 0xFF55_0064L)
        val sizeWord = ((h - 1).toLong() shl 16) or (w - 1).toLong()

        data class Mode(val name: String, val semiPlanar: Boolean, val nv12: Boolean, val yyuv422: Boolean)
        val modes = listOf(
            Mode("UYVY422", false, false, false),
            Mode("YYUV422", false, false, true),
            Mode("NV16", true, false, false),
            Mode("NV12", true, true, false),
        )

        for (mode in modes) {
            val memKotlin = FlatDmaMemory(w * h * 4)
            val memNative = FlatDmaMemory(w * h * 4)
            val statsKotlin = IspStatsSramEngine()
            val statsNative = IspStatsSramEngine()
            val statsCfgK = LiveViewDmaEngine.StatsConfig(statsKotlin, 0L, 0L)
            val statsCfgN = LiveViewDmaEngine.StatsConfig(statsNative, 0L, 0L)

            val engKotlin = LiveViewDmaEngine().apply { allowNativeSimd = false }
            val engNative = LiveViewDmaEngine().apply { allowNativeSimd = true }

            val stride = if (mode.semiPlanar) w.toLong() else (w * 2).toLong()
            val ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or (if (mode.nv12) (1L shl 20) else 0L)
            val yAddr = 0x1000L
            val uvAddr = if (mode.semiPlanar) (yAddr + w * h) else 0L

            assertTrue(
                engKotlin.pumpLiveViewDma(1L, yAddr, stride, sizeWord, ctrl, sensor, memKotlin, lens, uvAddr, 0L, 0L, peaking, exposure, statsCfgK, color, mode.yyuv422),
                "Kotlin pump failed for ${mode.name}",
            )
            assertTrue(
                engNative.pumpLiveViewDma(1L, yAddr, stride, sizeWord, ctrl, sensor, memNative, lens, uvAddr, 0L, 0L, peaking, exposure, statsCfgN, color, mode.yyuv422),
                "Native pump failed for ${mode.name}",
            )

            val checkBytes = if (mode.semiPlanar) w * h + (if (mode.nv12) w * h / 2 else w * h) else w * h * 2
            var maxDiff = 0
            for (i in 0 until checkBytes) {
                val a = memKotlin.bytes[yAddr.toInt() + i].toInt() and 0xFF
                val b = memNative.bytes[yAddr.toInt() + i].toInt() and 0xFF
                val d = abs(a - b)
                if (d > maxDiff) maxDiff = d
            }
            assertTrue(maxDiff <= 1, "Mode ${mode.name} exceeded 1 LSB parity: maxDiff=$maxDiff")
            assertEquals(statsKotlin.histFramesUpdated, statsNative.histFramesUpdated)
            for (bin in 0 until 1024) {
                val expected = statsKotlin.readHistogram(bin * 4, 4)
                val actual = statsNative.readHistogram(bin * 4, 4)
                assertEquals(expected, actual, "Mode ${mode.name} histogram bin $bin mismatch")
            }
        }
    }

    @Test
    fun `native ISP SIMD 16x16x16 3D LUT and single-pass AF contrast match reference within 1 LSB`() {
        requireOrAssumeNativeAvailable()
        val w = 256
        val h = 144
        val raw = buildSyntheticBayer16(w, h)
        val xmap = IntArray(w) { it }
        val lut = buildSrgbLut()
        val lut3d = buildTestLut3d16()
        val dstIdentity = ByteArray(w * h * 2)
        val dst3d = ByteArray(w * h * 2)
        val afOut = LongArray(1)

        // First verify identity 3D LUT reproduces 0-LSB identical UYVY + non-zero AF contrast
        val identityLut3d = FloatArray(16 * 16 * 16 * 3)
        for (b in 0 until 16) {
            for (g in 0 until 16) {
                for (r in 0 until 16) {
                    val idx = ((b * 16 + g) * 16 + r) * 3
                    identityLut3d[idx + 0] = r / 15.0f
                    identityLut3d[idx + 1] = g / 15.0f
                    identityLut3d[idx + 2] = b / 15.0f
                }
            }
        }
        val cfgNo3d = intArrayOf(
            w, h, 1, w, h, w * 2, 0, 0, 0, 0, w, h,
            1024, 1024, 1024,
            NativeIspSimdBridge.FLAG_AF_CONTRAST,
            10, 48, 0x008080FA, 0,
        )
        val cfgId3d = cfgNo3d.copyOf().also { it[15] = it[15] or NativeIspSimdBridge.FLAG_HAS_LUT3D }
        val afNo3d = LongArray(1)
        assertTrue(NativeIspSimdBridge.demosaicYuvMt(raw, dstIdentity, null, xmap, null, lut, lut, lut, null, null, null, afNo3d, cfgNo3d) >= 0)
        assertTrue(NativeIspSimdBridge.demosaicYuvMt(raw, dst3d, null, xmap, null, lut, lut, lut, identityLut3d, null, null, afOut, cfgId3d) >= 0)
        assertTrue(afNo3d[0] > 0L, "Expected positive AF contrast energy")
        assertEquals(afNo3d[0], afOut[0], "Identity 3D LUT AF contrast should match")
        var maxIdDiff = 0
        for (i in dstIdentity.indices) {
            val d = abs((dstIdentity[i].toInt() and 0xFF) - (dst3d[i].toInt() and 0xFF))
            if (d > maxIdDiff) maxIdDiff = d
        }
        assertTrue(maxIdDiff <= 1, "Identity 3D LUT maxDiff=$maxIdDiff")

        // Verify non-trivial FilmSim 3D LUT against reference trilinear interpolation on uniform RGGB patch
        val uniformRaw = ByteArray(w * h * 2)
        val rVal = 2800 shl 4
        val gVal = 1900 shl 4
        val bVal = 1100 shl 4
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = when {
                    (y and 1) == 0 && (x and 1) == 0 -> rVal
                    (y and 1) == 1 && (x and 1) == 1 -> bVal
                    else -> gVal
                }
                val off = (y * w + x) * 2
                uniformRaw[off] = (v and 0xFF).toByte()
                uniformRaw[off + 1] = ((v ushr 8) and 0xFF).toByte()
            }
        }
        assertTrue(NativeIspSimdBridge.demosaicYuvMt(uniformRaw, dst3d, null, xmap, null, lut, lut, lut, lut3d, null, null, null, cfgId3d) >= 0)
        val (er, eg, eb) = applyLut3dReference(lut3d, lut[2800], lut[1900], lut[1100])
        val expY = ((77 * er + 150 * eg + 29 * eb + 128) shr 8).coerceIn(0, 255)
        val expU = (((-43 * er - 85 * eg + 128 * eb + 128) shr 8) + 128).coerceIn(0, 255)
        val expV = (((128 * er - 107 * eg - 21 * eb + 128) shr 8) + 128).coerceIn(0, 255)
        val centerOff = (h / 2) * (w * 2) + (w / 2) * 2
        assertTrue(abs((dst3d[centerOff + 0].toInt() and 0xFF) - expU) <= 1)
        assertTrue(abs((dst3d[centerOff + 1].toInt() and 0xFF) - expY) <= 1)
        assertTrue(abs((dst3d[centerOff + 2].toInt() and 0xFF) - expV) <= 1)
    }

    @Test
    fun `benchmark Kotlin vs Native ISP SIMD at 1024x576 and 2048x1536`() {
        requireOrAssumeNativeAvailable()
        val color = buildColorPipeline()
        val exposure = LiveViewDmaEngine.ExposureParams(1000L, 400, 1120, 1024, 1180)
        val peaking = MfAssistPeakingFilter.fromWords(0L, 0x03C0_0000L, 0x80L, 2L, 0xFF55_0064L)
        val lens = LensParameters("GF63mmF2.8", 63.0, 2.8, 5.0, 0.9, 0.0)

        for ((w, h, iters) in listOf(Triple(1024, 576, 15), Triple(2048, 1536, 6))) {
            val raw = buildSyntheticBayer16(w, h)
            val sizeWord = ((h - 1).toLong() shl 16) or (w - 1).toLong()
            val stride = (w * 2).toLong()
            val dstAddr = 0x1000L
            val mem = FlatDmaMemory(w * h * 2 + 8192)
            val stats = IspStatsSramEngine()
            val statsCfg = LiveViewDmaEngine.StatsConfig(stats, 0L, 0L)

            val engKotlin = LiveViewDmaEngine().apply { allowNativeSimd = false }
            val engNative = LiveViewDmaEngine().apply { allowNativeSimd = true }

            var genCounter = 1L
            val rotatingSensor = object : SensorPort {
                override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                    destination.put(raw)
                    return raw.size
                }
                override fun currentFrameGeneration(): Long = genCounter++
            }

            // Warmup both paths
            repeat(3) { t ->
                assertTrue(engKotlin.pumpLiveViewDma(t.toLong(), dstAddr, stride, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, rotatingSensor, mem, lens, 0L, 0L, 0L, peaking, exposure, statsCfg, color))
                assertTrue(engNative.pumpLiveViewDma(t.toLong() + 100L, dstAddr, stride, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, rotatingSensor, mem, lens, 0L, 0L, 0L, peaking, exposure, statsCfg, color))
            }

            val kStart = System.nanoTime()
            repeat(iters) { i ->
                assertTrue(engKotlin.pumpLiveViewDma(1000L + i, dstAddr, stride, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, rotatingSensor, mem, lens, 0L, 0L, 0L, peaking, exposure, statsCfg, color))
            }
            val kMs = (System.nanoTime() - kStart) / 1e6 / iters

            val nStart = System.nanoTime()
            repeat(iters) { i ->
                assertTrue(engNative.pumpLiveViewDma(2000L + i, dstAddr, stride, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, rotatingSensor, mem, lens, 0L, 0L, 0L, peaking, exposure, statsCfg, color))
            }
            val nMs = (System.nanoTime() - nStart) / 1e6 / iters

            val speedup = kMs / nMs
            println("[ISP-BENCH ${w}x${h} | ${NativeIspSimdBridge.backendName} | threads=${NativeIspSimdBridge.defaultThreads}] Kotlin=%.2f ms -> Native=%.2f ms (%.2fx)".format(kMs, nMs, speedup))
            assertTrue(nMs < kMs, "Expected native ISP SIMD (${nMs}ms) to beat pure Kotlin (${kMs}ms) at ${w}x${h}")
        }
    }
}
