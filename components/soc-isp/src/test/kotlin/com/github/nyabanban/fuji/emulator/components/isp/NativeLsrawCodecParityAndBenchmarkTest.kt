package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.NativeCodecBridge
import java.nio.ByteBuffer
import java.util.Locale
import org.junit.jupiter.api.Assumptions
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLsrawCodecParityAndBenchmarkTest {
    private class ByteArrayDma(val bytes: ByteArray) : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0L && size >= 0L && address + size <= bytes.size.toLong()) {
                "DMA OOB addr=$address size=$size cap=${bytes.size}"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            check(address, len.toLong(), false)
            destination.put(bytes, address.toInt(), len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            check(address, len.toLong(), true)
            source.get(bytes, address.toInt(), len)
        }
    }

    private fun buildSyntheticBayer(width: Int, height: Int, stridePixels: Int, bits: Int): ByteArray {
        val maxVal = (1 shl bits) - 1
        val out = ByteArray(stridePixels * height * 2)
        for (y in 0 until height) {
            val rowOff = y * stridePixels * 2
            val phaseY = (y and 1) shl 1
            for (x in 0 until width) {
                val c = phaseY or (x and 1)
                val v = when (c) {
                    0 -> ((x * 7 + y * 31) and maxVal)
                    1 -> (((x * 13 + y * 5) xor 0x55) and maxVal)
                    2 -> (((x * 11 + y * 17) + 128) and maxVal)
                    else -> if (((x / 64 + y / 6) and 1) == 0) (maxVal * 3 / 4) else (maxVal / 5)
                }
                val off = rowOff + x * 2
                out[off] = (v and 0xFF).toByte()
                out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    @Test
    fun nativeAndKotlinLsrawAreBitExactAcrossDepthsModesAndPartialEdges() {
        assertTrue(
            NativeCodecBridge.hasLsraw,
            "Expected libfuji_codec to load: ${NativeCodecBridge.lastLoadError}",
        )

        data class Case(
            val label: String,
            val width: Int,
            val height: Int,
            val stridePixels: Int,
            val blocksX: Int,
            val stripPixels: Int,
            val bits: Int,
            val lossy: Boolean,
        )

        val cases = listOf(
            Case("12b-lossless-edge", 3000, 24, 3000, 4, 768, 12, false),
            Case("14b-lossless-strided", 3000, 38, 3072, 4, 768, 14, false),
            Case("16b-lossless-8strip", 1536, 48, 1536, 8, 192, 16, false),
            Case("16b-compressed-8strip", 1536, 48, 1600, 8, 192, 16, true),
            Case("14b-compressed-nonmult6", 1500, 50, 1536, 4, 384, 14, true),
        )

        for (c in cases) {
            val blocksY = (c.height + 5) / 6
            val raw = buildSyntheticBayer(c.width, c.height, c.stridePixels, c.bits)
            val srcDma = ByteArrayDma(raw)
            val maxCap = (raw.size * 2L).coerceAtLeast(65536L)

            val kotlinEnc = LsrawCodecEngine.encodeFujifilmBayer(
                memory = srcDma,
                srcPa = 0L,
                lineStridePixels = c.stridePixels,
                activeWidth = c.width,
                activeHeight = c.height,
                stripPixels = c.stripPixels,
                blocksX = c.blocksX,
                blocksY = blocksY,
                rawBits = c.bits,
                lossyCompressed = c.lossy,
                maxCap = maxCap,
                useNative = false,
            )
            val nativeEnc = LsrawCodecEngine.encodeFujifilmBayer(
                memory = srcDma,
                srcPa = 0L,
                lineStridePixels = c.stridePixels,
                activeWidth = c.width,
                activeHeight = c.height,
                stripPixels = c.stripPixels,
                blocksX = c.blocksX,
                blocksY = blocksY,
                rawBits = c.bits,
                lossyCompressed = c.lossy,
                maxCap = maxCap,
                useNative = true,
            )
            assertContentEquals(kotlinEnc, nativeEnc, "[${c.label}] Encoded LSRAW bitstream must have 0-byte diff")

            val hdrBytes = LsrawCodecEngine.headerBytesForStrips(c.blocksX)
            val stripSizes = IntArray(c.blocksX) { s ->
                val off = 16 + s * 4
                ((nativeEnc[off].toInt() and 0xFF) shl 24) or
                    ((nativeEnc[off + 1].toInt() and 0xFF) shl 16) or
                    ((nativeEnc[off + 2].toInt() and 0xFF) shl 8) or
                    (nativeEnc[off + 3].toInt() and 0xFF)
            }

            val kotlinDecBuf = ByteArray(raw.size)
            val nativeDecBuf = ByteArray(raw.size)
            val kOk = LsrawCodecEngine.decodeFujifilmBayer(
                stream = nativeEnc,
                hdrBytes = hdrBytes,
                stripSizes = stripSizes,
                rawWidth = c.width,
                rawHeight = c.height,
                lineStridePixels = c.stridePixels,
                stripPixels = c.stripPixels,
                blocksX = c.blocksX,
                blocksY = blocksY,
                rawBits = c.bits,
                memory = ByteArrayDma(kotlinDecBuf),
                dstPa = 0L,
                useNative = false,
            )
            val nOk = LsrawCodecEngine.decodeFujifilmBayer(
                stream = nativeEnc,
                hdrBytes = hdrBytes,
                stripSizes = stripSizes,
                rawWidth = c.width,
                rawHeight = c.height,
                lineStridePixels = c.stridePixels,
                stripPixels = c.stripPixels,
                blocksX = c.blocksX,
                blocksY = blocksY,
                rawBits = c.bits,
                memory = ByteArrayDma(nativeDecBuf),
                dstPa = 0L,
                useNative = true,
            )
            assertTrue(kOk && nOk, "[${c.label}] Both Kotlin and Native decode must succeed")
            assertContentEquals(kotlinDecBuf, nativeDecBuf, "[${c.label}] Decoded RAW pixels must have 0-byte diff")
            if (!c.lossy) {
                assertContentEquals(raw, nativeDecBuf, "[${c.label}] Lossless native round-trip must be bit-exact")
            }
        }
    }

    private fun runLsrawBitExactBenchmark(label: String, width: Int, height: Int, blocksX: Int) {
        assertTrue(
            NativeCodecBridge.hasLsraw,
            "Expected libfuji_codec to load: ${NativeCodecBridge.lastLoadError}",
        )
        val stripPixels = width / blocksX
        val blocksY = height / 6
        val rawBits = 16
        val raw = ByteArray(width * height * 2)
        // Smooth realistic sensor ramp + micro-texture
        for (y in 0 until height) {
            val rowOff = y * width * 2
            val baseY = (y * 5) and 0x3FFF
            var x = 0
            while (x < width) {
                val v = (baseY + ((x * 3) and 0x1FFF) + ((x xor y) and 0x3F)) and 0xFFFF
                val off = rowOff + x * 2
                raw[off] = (v and 0xFF).toByte()
                raw[off + 1] = ((v ushr 8) and 0xFF).toByte()
                x++
            }
        }
        val rawMb = raw.size / (1024.0 * 1024.0)
        val srcDma = ByteArrayDma(raw)
        val maxCap = 256L * 1024L * 1024L

        // Warmup native once on a small slice
        LsrawCodecEngine.encodeFujifilmBayer(
            memory = srcDma, srcPa = 0L, lineStridePixels = width, activeWidth = width,
            activeHeight = 24, stripPixels = stripPixels, blocksX = blocksX, blocksY = 4,
            rawBits = rawBits, lossyCompressed = false, maxCap = maxCap, useNative = true,
        )

        lateinit var kotlinEnc: ByteArray
        val kEncNs = measureNanoTime {
            kotlinEnc = LsrawCodecEngine.encodeFujifilmBayer(
                memory = srcDma, srcPa = 0L, lineStridePixels = width, activeWidth = width,
                activeHeight = height, stripPixels = stripPixels, blocksX = blocksX, blocksY = blocksY,
                rawBits = rawBits, lossyCompressed = false, maxCap = maxCap, useNative = false,
            )
        }
        lateinit var nativeEnc: ByteArray
        val nEncNs = measureNanoTime {
            nativeEnc = LsrawCodecEngine.encodeFujifilmBayer(
                memory = srcDma, srcPa = 0L, lineStridePixels = width, activeWidth = width,
                activeHeight = height, stripPixels = stripPixels, blocksX = blocksX, blocksY = blocksY,
                rawBits = rawBits, lossyCompressed = false, maxCap = maxCap, useNative = true,
            )
        }
        assertEquals(kotlinEnc.size, nativeEnc.size, "[$label] LSRAW encoded byte length must match exactly")
        assertContentEquals(kotlinEnc, nativeEnc, "[$label] LSRAW encoded bitstream must have 0-byte diff")
        // Release Kotlin encoded copy before decoding to stay lean in memory
        kotlinEnc = ByteArray(0)

        val hdrBytes = LsrawCodecEngine.headerBytesForStrips(blocksX)
        val stripSizes = IntArray(blocksX) { s ->
            val off = 16 + s * 4
            ((nativeEnc[off].toInt() and 0xFF) shl 24) or
                ((nativeEnc[off + 1].toInt() and 0xFF) shl 16) or
                ((nativeEnc[off + 2].toInt() and 0xFF) shl 8) or
                (nativeEnc[off + 3].toInt() and 0xFF)
        }
        val decBuf = ByteArray(raw.size)
        val kDecNs = measureNanoTime {
            val ok = LsrawCodecEngine.decodeFujifilmBayer(
                stream = nativeEnc, hdrBytes = hdrBytes, stripSizes = stripSizes,
                rawWidth = width, rawHeight = height, lineStridePixels = width,
                stripPixels = stripPixels, blocksX = blocksX, blocksY = blocksY,
                rawBits = rawBits, memory = ByteArrayDma(decBuf), dstPa = 0L, useNative = false,
            )
            assertTrue(ok)
        }
        assertContentEquals(raw, decBuf, "[$label] Kotlin decode must match original raw")
        decBuf.fill(0)
        val nDecNs = measureNanoTime {
            val ok = LsrawCodecEngine.decodeFujifilmBayer(
                stream = nativeEnc, hdrBytes = hdrBytes, stripSizes = stripSizes,
                rawWidth = width, rawHeight = height, lineStridePixels = width,
                stripPixels = stripPixels, blocksX = blocksX, blocksY = blocksY,
                rawBits = rawBits, memory = ByteArrayDma(decBuf), dstPa = 0L, useNative = true,
            )
            assertTrue(ok)
        }
        assertContentEquals(raw, decBuf, "[$label] Native decode must match original raw (0-byte diff)")

        val kEncMs = kEncNs / 1e6
        val nEncMs = nEncNs / 1e6
        val kDecMs = kDecNs / 1e6
        val nDecMs = nDecNs / 1e6
        println(
            String.format(
                Locale.US,
                "[BENCH-LSRAW-%s] backend=%s threads=%d raw=%.2f MiB encoded=%.2f MiB | " +
                    "Encode: Kotlin=%.1f ms (%.1f MB/s) vs Native=%.1f ms (%.1f MB/s) [%.2fx] | " +
                    "Decode: Kotlin=%.1f ms (%.1f MB/s) vs Native=%.1f ms (%.1f MB/s) [%.2fx]",
                label,
                NativeCodecBridge.backendName,
                NativeCodecBridge.defaultThreads,
                rawMb,
                nativeEnc.size / (1024.0 * 1024.0),
                kEncMs, rawMb / (kEncMs / 1000.0),
                nEncMs, rawMb / (nEncMs / 1000.0),
                kEncMs / nEncMs,
                kDecMs, rawMb / (kDecMs / 1000.0),
                nDecMs, rawMb / (nDecMs / 1000.0),
                kDecMs / nDecMs,
            ),
        )
        assertTrue(nEncNs < kEncNs, "Expected Native MT LSRAW encode ($nEncMs ms) faster than Kotlin ($kEncMs ms)")
        assertTrue(nDecNs < kDecNs, "Expected Native MT LSRAW decode ($nDecMs ms) faster than Kotlin ($kDecMs ms)")
    }

    @Test
    fun benchmark12MpLsrawBitExactAndSpeedup() {
        // Always-on CI-safe 12MP (4096x3072, 24 MiB, 8 strips) bit-exact parity & speedup verification
        runLsrawBitExactBenchmark("12MP", width = 4096, height = 3072, blocksX = 8)
    }

    @Test
    fun benchmark102MpLsrawBitExactAndSpeedup() {
        // Full GFX100S 102MP (11648x8736, 203.5 MiB, 16 strips) — runs when heap >= 1.2 GiB or -Dfuji.bench.102mp=true
        val run102 = System.getProperty("fuji.bench.102mp")?.equals("true", ignoreCase = true) == true ||
            Runtime.getRuntime().maxMemory() >= 1_200_000_000L
        Assumptions.assumeTrue(run102, "Skipping 102MP LSRAW benchmark under <1.2 GiB heap")
        runLsrawBitExactBenchmark("102MP", width = 11648, height = 8736, blocksX = 16)
    }
}
