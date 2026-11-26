package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class NativeOpticalFilterSimdParityAndBenchTest {
    private fun makeTestFrame(w: Int, h: Int): Triple<FloatArray, FloatArray, FloatArray> {
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        var s = 0x12345678
        for (i in 0 until n) {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            val v = (s and 0xFFFF) / 65535.0f
            r[i] = v * 0.52f + 0.05f
            g[i] = ((v * 0.73f) % 1.0f) * 0.50f + 0.04f
            b[i] = ((v * 1.17f) % 1.0f) * 0.48f + 0.03f
        }
        // Plant realistic specular highlights for Black Mist halation, UV ghosting, and Star spikes
        val cx = w / 3
        val cy = h / 3
        for (dy in -4..4) {
            for (dx in -4..4) {
                val idx = (cy + dy) * w + (cx + dx)
                r[idx] = 1.65f
                g[idx] = 1.50f
                b[idx] = 1.35f
            }
        }
        val cx2 = (w * 3) / 4
        val cy2 = (h * 2) / 3
        for (dy in -2..2) {
            for (dx in -2..2) {
                val idx = (cy2 + dy) * w + (cx2 + dx)
                r[idx] = 1.40f
                g[idx] = 1.55f
                b[idx] = 1.75f
            }
        }
        return Triple(r, g, b)
    }

    private fun verifyParity(name: String, filter: OpticalFilterElement, w: Int, h: Int, tol: Float = 1e-4f) {
        val (br, bg, bb) = makeTestFrame(w, h)
        val kr = br.copyOf()
        val kg = bg.copyOf()
        val kb = bb.copyOf()
        val nr = br.copyOf()
        val ng = bg.copyOf()
        val nb = bb.copyOf()
        val scratchK = OpticalFilterScratch()
        val scratchN = OpticalFilterScratch()

        NativeOpticalFilterSimdBridge.forceDisabled = true
        try {
            filter.applySpatialLinearRgb(kr, kg, kb, w, h, scratchK)
        } finally {
            NativeOpticalFilterSimdBridge.forceDisabled = false
        }
        filter.applySpatialLinearRgb(nr, ng, nb, w, h, scratchN)

        var maxErr = 0f
        for (i in kr.indices) {
            val er = abs(kr[i] - nr[i])
            val eg = abs(kg[i] - ng[i])
            val eb = abs(kb[i] - nb[i])
            if (er > maxErr) maxErr = er
            if (eg > maxErr) maxErr = eg
            if (eb > maxErr) maxErr = eb
        }
        println("[OPTICS-PARITY $name ${w}x${h}] maxErr=$maxErr (tol=$tol)")
        assertTrue(maxErr <= tol, "$name parity exceeded $tol: maxErr=$maxErr")
    }

    private fun requireOrAssumeNativeAvailable() {
        val required = System.getenv("FUJI_REQUIRE_ISP_SIMD") == "1" ||
            !System.getenv("FUJI_ISP_SIMD_LIBRARY").isNullOrBlank()
        if (required) {
            assertTrue(NativeOpticalFilterSimdBridge.isAvailable, "Expected libfuji_isp_simd to load when FUJI_REQUIRE_ISP_SIMD=1 / FUJI_ISP_SIMD_LIBRARY is set")
        } else {
            assumeTrue(NativeOpticalFilterSimdBridge.isAvailable, "libfuji_isp_simd not present; build with bash tools/native/isp-simd/build_isp_simd.sh linux")
        }
    }

    @Test
    fun `native spatial optical filters match pure Kotlin within 1e-4f`() {
        requireOrAssumeNativeAvailable()

        verifyParity("BlackMist-1/4", BlackMistFilterElement(strength = 0.50, threshold = 0.55, preset = "1/4", enabled = true), 320, 180)
        verifyParity("BlackMist-1/1", BlackMistFilterElement(strength = 1.00, threshold = 0.45, preset = "1/1", enabled = true), 320, 180)
        verifyParity("UV-CHEAP_POOR", UvFilterElement(preset = UvFilterPreset.CHEAP_POOR), 320, 180)
        verifyParity("UV-STANDARD", UvFilterElement(preset = UvFilterPreset.STANDARD), 320, 180)
        verifyParity("Star-4pt", StarFilterElement(points = 4, strength = 0.80, threshold = 0.85, length = 0.18, angleDeg = 45.0, enabled = true), 320, 180)
        verifyParity("Star-6pt-30deg", StarFilterElement(points = 6, strength = 0.85, threshold = 0.85, length = 0.20, angleDeg = 30.0, enabled = true), 320, 180)
        verifyParity("Star-8pt", StarFilterElement(points = 8, strength = 0.75, threshold = 0.80, length = 0.16, angleDeg = 15.0, enabled = true), 320, 180)
    }

    @Test
    fun `benchmark Kotlin vs Native spatial optical filters at 1024x576 and 2048x1536`() {
        requireOrAssumeNativeAvailable()
        val filters = listOf(
            "BlackMist" to BlackMistFilterElement(strength = 0.50, threshold = 0.55, preset = "1/4", enabled = true),
            "UV_CHEAP_POOR" to UvFilterElement(preset = UvFilterPreset.CHEAP_POOR),
            "Star_6pt" to StarFilterElement(points = 6, strength = 0.80, threshold = 0.85, length = 0.18, angleDeg = 15.0, enabled = true),
        )

        for ((w, h, iters) in listOf(Triple(1024, 576, 8), Triple(2048, 1536, 3))) {
            val (br, bg, bb) = makeTestFrame(w, h)
            val scratch = OpticalFilterScratch()
            val wr = FloatArray(w * h)
            val wg = FloatArray(w * h)
            val wb = FloatArray(w * h)

            for ((name, filter) in filters) {
                // Warmup both paths
                repeat(2) {
                    System.arraycopy(br, 0, wr, 0, br.size)
                    System.arraycopy(bg, 0, wg, 0, bg.size)
                    System.arraycopy(bb, 0, wb, 0, bb.size)
                    NativeOpticalFilterSimdBridge.forceDisabled = true
                    try {
                        filter.applySpatialLinearRgb(wr, wg, wb, w, h, scratch)
                    } finally {
                        NativeOpticalFilterSimdBridge.forceDisabled = false
                    }
                    System.arraycopy(br, 0, wr, 0, br.size)
                    System.arraycopy(bg, 0, wg, 0, bg.size)
                    System.arraycopy(bb, 0, wb, 0, bb.size)
                    filter.applySpatialLinearRgb(wr, wg, wb, w, h, scratch)
                }

                // Pure Kotlin timing (reset frame outside timer)
                var kNanos = 0L
                NativeOpticalFilterSimdBridge.forceDisabled = true
                try {
                    repeat(iters) {
                        System.arraycopy(br, 0, wr, 0, br.size)
                        System.arraycopy(bg, 0, wg, 0, bg.size)
                        System.arraycopy(bb, 0, wb, 0, bb.size)
                        val t0 = System.nanoTime()
                        filter.applySpatialLinearRgb(wr, wg, wb, w, h, scratch)
                        kNanos += System.nanoTime() - t0
                    }
                } finally {
                    NativeOpticalFilterSimdBridge.forceDisabled = false
                }
                val kMs = kNanos / 1e6 / iters

                // Native SIMD timing (reset frame outside timer)
                var nNanos = 0L
                repeat(iters) {
                    System.arraycopy(br, 0, wr, 0, br.size)
                    System.arraycopy(bg, 0, wg, 0, bg.size)
                    System.arraycopy(bb, 0, wb, 0, bb.size)
                    val t0 = System.nanoTime()
                    filter.applySpatialLinearRgb(wr, wg, wb, w, h, scratch)
                    nNanos += System.nanoTime() - t0
                }
                val nMs = nNanos / 1e6 / iters

                val speedup = kMs / nMs
                println("[OPTICS-BENCH $name ${w}x${h} | ${NativeOpticalFilterSimdBridge.backendName} | threads=${NativeOpticalFilterSimdBridge.defaultThreads}] Kotlin=%.2f ms -> Native=%.2f ms (%.2fx)".format(kMs, nMs, speedup))
                assertTrue(nMs < kMs, "Expected native $name (${nMs}ms) to beat pure Kotlin (${kMs}ms) at ${w}x${h}")
            }
        }
    }
}
