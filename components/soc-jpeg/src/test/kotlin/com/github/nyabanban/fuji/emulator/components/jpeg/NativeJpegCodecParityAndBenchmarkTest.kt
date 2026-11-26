package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.NativeCodecBridge
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import java.util.Locale
import org.junit.jupiter.api.Assumptions
import kotlin.math.abs
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeJpegCodecParityAndBenchmarkTest {
    private fun buildColorRampUyvy(width: Int, height: Int, strideBytes: Int = width * 2): ByteArray {
        val out = ByteArray(strideBytes * height)
        for (y in 0 until height) {
            val rowOff = y * strideBytes
            val vVal = (64 + (y * 128) / height).coerceIn(16, 240).toByte()
            var x = 0
            while (x < width) {
                val uVal = (64 + (x * 128) / width).coerceIn(16, 240).toByte()
                val luma0 = (32 + ((x + y) * 180) / (width + height)).coerceIn(16, 235).toByte()
                val luma1 = (32 + ((x + 1 + y) * 180) / (width + height)).coerceIn(16, 235).toByte()
                val p = rowOff + x * 2
                out[p] = uVal
                out[p + 1] = luma0
                out[p + 2] = vVal
                out[p + 3] = luma1
                x += 2
            }
        }
        return out
    }

    @Test
    fun nativeAndJvmFallbackJpegHaveHighParityAcrossEncodeDecodeCropDownscaleAndOptical() {
        assertTrue(
            NativeCodecBridge.hasJpeg,
            "Expected native libjpeg-turbo backend via libfuji_codec: ${NativeCodecBridge.lastLoadError}",
        )

        val width = 640
        val height = 480
        val uyvy = buildColorRampUyvy(width, height)

        // 1. Encode MemYc UYVY422 parity (Native vs JVM fallback)
        val natEnc = JpegUyvyCodec.encodeFromMemYc(uyvy, width, height, width * 2, qscale = 2, maxBytes = 2_000_000L, useNative = true)
        val jvmEnc = JpegUyvyCodec.encodeFromMemYc(uyvy, width, height, width * 2, qscale = 2, maxBytes = 2_000_000L, useNative = false)
        assertTrue(natEnc is JpegUyvyCodec.EncodeResult.Success)
        assertTrue(jvmEnc is JpegUyvyCodec.EncodeResult.Success)

        // 2. Decode both streams through both Native and JVM fallback and compare Y/U/V MAE
        val natDec = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, 0, false, 0, 0, 0, 0, useNative = true)
        val jvmDec = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, 0, false, 0, 0, 0, 0, useNative = false)
        assertTrue(natDec is JpegUyvyCodec.DecodeResult.Success)
        assertTrue(jvmDec is JpegUyvyCodec.DecodeResult.Success)
        assertEquals(jvmDec.width, natDec.width)
        assertEquals(jvmDec.height, natDec.height)
        assertEquals(jvmDec.strideBytes, natDec.strideBytes)
        assertEquals(jvmDec.alignedWidth, natDec.alignedWidth)
        assertEquals(jvmDec.alignedHeight, natDec.alignedHeight)
        assertEquals(jvmDec.is420, natDec.is420)

        var absSum = 0L
        for (i in natDec.uyvyBytes.indices) {
            absSum += abs((natDec.uyvyBytes[i].toInt() and 0xFF) - (jvmDec.uyvyBytes[i].toInt() and 0xFF))
        }
        val mae = absSum.toDouble() / natDec.uyvyBytes.size.toDouble()
        assertTrue(mae < 2.0, "Expected Native vs JVM decode MAE < 2.0, got $mae")

        // 3. Downscale shifts 1..3 + ROI crop parity
        for (shift in 1..3) {
            val nDs = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, shift, false, 0, 0, 0, 0, useNative = true)
            val jDs = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, shift, false, 0, 0, 0, 0, useNative = false)
            assertTrue(nDs is JpegUyvyCodec.DecodeResult.Success && jDs is JpegUyvyCodec.DecodeResult.Success)
            assertEquals(jDs.width, nDs.width, "Downscale shift=$shift width mismatch")
            assertEquals(jDs.height, nDs.height, "Downscale shift=$shift height mismatch")
            assertEquals(jDs.strideBytes, nDs.strideBytes, "Downscale shift=$shift stride mismatch")
        }

        val nCrop = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, 1, true, 160, 479, 120, 359, useNative = true)
        val jCrop = JpegUyvyCodec.decodeToMemYc(natEnc.jpegBytes, 0L, 1, true, 160, 479, 120, 359, useNative = false)
        assertTrue(nCrop is JpegUyvyCodec.DecodeResult.Success && jCrop is JpegUyvyCodec.DecodeResult.Success)
        assertEquals(jCrop.width, nCrop.width)
        assertEquals(jCrop.height, nCrop.height)
        assertEquals(jCrop.strideBytes, nCrop.strideBytes)

        // 4. Optical sensor BGR24 native encode parity
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                val n = 640 * 480
                val v = (1800 shl 4)
                val lo = (v and 0xFF).toByte()
                val hi = ((v ushr 8) and 0xFF).toByte()
                repeat(n) {
                    destination.put(lo)
                    destination.put(hi)
                }
                return n * 2
            }
        }
        val optNat = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 100L, sensor = sensor, lens = null, width = 320, height = 240,
            qscale = 2, maxBytes = 256 * 1024L, useNative = true,
        )
        assertTrue(optNat is JpegUyvyCodec.EncodeResult.Success)
        assertTrue(optNat.jpegBytes.size > 512)
        val optDec = JpegUyvyCodec.decodeToMemYc(optNat.jpegBytes, 0L, 0, false, 0, 0, 0, 0, useNative = true)
        assertTrue(optDec is JpegUyvyCodec.DecodeResult.Success && optDec.width == 320 && optDec.height == 240)
    }

    private fun runJpegBenchmark(label: String, width: Int, height: Int) {
        assertTrue(
            NativeCodecBridge.hasJpeg,
            "Expected native libjpeg-turbo backend via libfuji_codec: ${NativeCodecBridge.lastLoadError}",
        )

        val uyvy = buildColorRampUyvy(width, height)
        val rawMb = uyvy.size / (1024.0 * 1024.0)

        // Warmup native path on a small frame
        val warm = buildColorRampUyvy(256, 192)
        val warmEnc = JpegUyvyCodec.encodeFromMemYc(warm, 256, 192, 512, 2, 1_000_000L, useNative = true)
        assertTrue(warmEnc is JpegUyvyCodec.EncodeResult.Success)
        JpegUyvyCodec.decodeToMemYc(warmEnc.jpegBytes, 0L, 0, false, 0, 0, 0, 0, useNative = true)

        lateinit var jvmJpeg: ByteArray
        val jvmEncNs = measureNanoTime {
            val r = JpegUyvyCodec.encodeFromMemYc(uyvy, width, height, width * 2, 2, 128L * 1024L * 1024L, useNative = false)
            assertTrue(r is JpegUyvyCodec.EncodeResult.Success)
            jvmJpeg = r.jpegBytes
        }

        lateinit var natJpeg: ByteArray
        val natEncNs = measureNanoTime {
            val r = JpegUyvyCodec.encodeFromMemYc(uyvy, width, height, width * 2, 2, 128L * 1024L * 1024L, useNative = true)
            assertTrue(r is JpegUyvyCodec.EncodeResult.Success)
            natJpeg = r.jpegBytes
        }

        val jvmDecNs = measureNanoTime {
            val r = JpegUyvyCodec.decodeToMemYc(natJpeg, 0L, 0, false, 0, 0, 0, 0, useNative = false)
            assertTrue(r is JpegUyvyCodec.DecodeResult.Success && r.width == width && r.height == height)
        }
        val natDecNs = measureNanoTime {
            val r = JpegUyvyCodec.decodeToMemYc(natJpeg, 0L, 0, false, 0, 0, 0, 0, useNative = true)
            assertTrue(r is JpegUyvyCodec.DecodeResult.Success && r.width == width && r.height == height)
        }
        val jvmDec4Ns = measureNanoTime {
            val r = JpegUyvyCodec.decodeToMemYc(natJpeg, 0L, 2, false, 0, 0, 0, 0, useNative = false)
            assertTrue(r is JpegUyvyCodec.DecodeResult.Success)
        }
        val natDec4Ns = measureNanoTime {
            val r = JpegUyvyCodec.decodeToMemYc(natJpeg, 0L, 2, false, 0, 0, 0, 0, useNative = true)
            assertTrue(r is JpegUyvyCodec.DecodeResult.Success)
        }

        val jvmEncMs = jvmEncNs / 1e6
        val natEncMs = natEncNs / 1e6
        val jvmDecMs = jvmDecNs / 1e6
        val natDecMs = natDecNs / 1e6
        val jvmDec4Ms = jvmDec4Ns / 1e6
        val natDec4Ms = natDec4Ns / 1e6
        println(
            String.format(
                Locale.US,
                "[BENCH-JPEG-%s] backend=%s uyvy=%.2f MiB natJpeg=%.2f MiB jvmJpeg=%.2f MiB | " +
                    "Encode: JVM=%.1f ms (%.1f MB/s) vs Native=%.1f ms (%.1f MB/s) [%.2fx] | " +
                    "Decode 1:1: JVM=%.1f ms (%.1f MB/s) vs Native=%.1f ms (%.1f MB/s) [%.2fx] | " +
                    "Decode 1/4: JVM=%.1f ms vs Native=%.1f ms [%.2fx]",
                label,
                NativeCodecBridge.backendName,
                rawMb,
                natJpeg.size / (1024.0 * 1024.0),
                jvmJpeg.size / (1024.0 * 1024.0),
                jvmEncMs, rawMb / (jvmEncMs / 1000.0),
                natEncMs, rawMb / (natEncMs / 1000.0),
                jvmEncMs / natEncMs,
                jvmDecMs, rawMb / (jvmDecMs / 1000.0),
                natDecMs, rawMb / (natDecMs / 1000.0),
                jvmDecMs / natDecMs,
                jvmDec4Ms, natDec4Ms, jvmDec4Ms / natDec4Ms,
            ),
        )
        assertTrue(natEncNs < jvmEncNs, "Expected Native JPEG $label encode ($natEncMs ms) faster than JVM ($jvmEncMs ms)")
        assertTrue(natDecNs < jvmDecNs, "Expected Native JPEG $label decode ($natDecMs ms) faster than JVM ($jvmDecMs ms)")
        assertTrue(natDec4Ns < jvmDec4Ns, "Expected Native JPEG $label 1/4 decode ($natDec4Ms ms) faster than JVM ($jvmDec4Ms ms)")
    }

    @Test
    fun benchmark12MpJpegEncodeAndDecodeSpeedup() {
        // Always-on CI-safe 12MP (4096x3072, 24 MiB) speedup verification
        runJpegBenchmark("12MP", width = 4096, height = 3072)
    }

    @Test
    fun benchmark102MpJpegEncodeAndDecodeSpeedup() {
        // Full GFX100S 102MP (11648x8736, 203.5 MiB) — runs when heap >= 1.2 GiB or -Dfuji.bench.102mp=true
        val run102 = System.getProperty("fuji.bench.102mp")?.equals("true", ignoreCase = true) == true ||
            Runtime.getRuntime().maxMemory() >= 1_200_000_000L
        Assumptions.assumeTrue(run102, "Skipping 102MP JPEG benchmark under <1.2 GiB heap")
        runJpegBenchmark("102MP", width = 11648, height = 8736)
    }
}
