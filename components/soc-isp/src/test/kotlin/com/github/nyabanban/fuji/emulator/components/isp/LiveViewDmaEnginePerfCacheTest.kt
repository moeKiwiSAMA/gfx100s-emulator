package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveViewDmaEnginePerfCacheTest {
    private class RamSink(val planes: MutableMap<Long, ByteArray>) : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            val buf = planes[address]
            require(write && buf != null && size == buf.size.toLong()) {
                "Unexpected DMA access addr=0x${address.toString(16)} size=$size write=$write"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            error("read not expected")
        }

        override fun write(address: Long, source: ByteBuffer) {
            val buf = checkNotNull(planes[address])
            source.get(buf, 0, source.remaining())
        }
    }

    private fun makePatternRaw16(width: Int, height: Int, seed: Int): ByteArray {
        val raw = ByteArray(width * height * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = ((x * 53 + y * 97 + seed * 131) and 0x0FFF) shl 4
                bb.putShort(v.toShort())
            }
        }
        return raw
    }

    private fun makePatternRaw8(width: Int, height: Int, seed: Int): ByteArray {
        val raw = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                raw[y * width + x] = ((x * 17 + y * 31 + seed * 43) and 0xFF).toByte()
            }
        }
        return raw
    }

    private fun sizeWord(width: Int, height: Int): Long =
        ((height - 1).toLong() shl 16) or (width - 1).toLong()

    @Test
    fun cachesRepeatedPackedUyvyAndInvalidatesOnSingleByteMutation() {
        val srcW = 1024
        val srcH = 576
        val dstW = 1024
        val dstH = 768
        val stride = dstW * 2L
        val raw = makePatternRaw16(srcW, srcH, seed = 1)
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }

        val yAddr = 0x8800_0000L
        val outFirst = ByteArray(dstW * dstH * 2)
        val outCached = ByteArray(dstW * dstH * 2)
        val outMutated = ByteArray(dstW * dstH * 2)
        val engine = LiveViewDmaEngine()
        val lens = LensParameters("GF45-100", 63.0, 4.0, 2.5, 0.9, 0.0)

        assertTrue(
            engine.pumpLiveViewDma(
                tick = 1L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to outFirst)),
                lens = lens,
            ),
        )
        assertEquals(1L, engine.cacheMisses)
        assertEquals(0L, engine.cacheHits)

        // Identical sensor frame hits cache and produces bit-exact UYVY output
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 2L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to outCached)),
                lens = lens,
            ),
        )
        assertEquals(1L, engine.cacheMisses)
        assertEquals(1L, engine.cacheHits)
        assertContentEquals(outFirst, outCached)

        // Mutating a single Bayer pixel invalidates the cache immediately
        val centerByte = ((srcH / 2) * srcW + (srcW / 2)) * 2 + 1
        raw[centerByte] = (raw[centerByte].toInt() xor 0xFF).toByte()
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 3L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to outMutated)),
                lens = lens,
            ),
        )
        assertEquals(2L, engine.cacheMisses)
        assertEquals(1L, engine.cacheHits)
        assertFalse(outFirst.contentEquals(outMutated), "Mutated Bayer pixel must invalidate YUV cache")
    }

    @Test
    fun supportsEvf1280x960SemiPlanarNv16AndNv12WithCachingAnd8BitSensor() {
        val dstW = 1280
        val dstH = 960
        val stride = dstW.toLong()
        val raw16 = makePatternRaw16(1024, 576, seed = 7)
        val raw8 = makePatternRaw8(1024, 576, seed = 3)
        var activeRaw = raw16
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(activeRaw)
                return activeRaw.size
            }
        }

        val yAddr = 0x9000_0000L
        val uvAddr = 0x9040_0000L
        val engine = LiveViewDmaEngine()

        // 1) EVF 1280x960 NV16 (subsamplingMode = 0)
        val yNv16First = ByteArray(dstW * dstH)
        val uvNv16First = ByteArray(dstW * dstH)
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 10L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to yNv16First, uvAddr to uvNv16First)),
                lens = null,
                uvAddr = uvAddr,
            ),
        )
        assertEquals(1L, engine.cacheMisses)
        assertEquals(0L, engine.cacheHits)

        val yNv16Hit = ByteArray(dstW * dstH)
        val uvNv16Hit = ByteArray(dstW * dstH)
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 11L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to yNv16Hit, uvAddr to uvNv16Hit)),
                lens = null,
                uvAddr = uvAddr,
            ),
        )
        assertEquals(1L, engine.cacheMisses)
        assertEquals(1L, engine.cacheHits)
        assertContentEquals(yNv16First, yNv16Hit)
        assertContentEquals(uvNv16First, uvNv16Hit)

        // 2) Switch to NV12 (ctrl bit 20 = 1) -> cache misses and populates half-height UV plane
        val yNv12 = ByteArray(dstW * dstH)
        val uvNv12 = ByteArray(dstW * (dstH / 2))
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 12L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or (1L shl 20),
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to yNv12, uvAddr to uvNv12)),
                lens = null,
                uvAddr = uvAddr,
            ),
        )
        assertEquals(2L, engine.cacheMisses)
        assertContentEquals(yNv16First, yNv12, "NV12 Y plane must match NV16 Y plane bit-identically")

        // 3) Switch to 8-bit sensor readout -> cache misses and hits on repeat
        activeRaw = raw8
        val y8First = ByteArray(dstW * dstH)
        val uv8First = ByteArray(dstW * (dstH / 2))
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 13L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or (1L shl 20),
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to y8First, uvAddr to uv8First)),
                lens = null,
                uvAddr = uvAddr,
            ),
        )
        assertEquals(3L, engine.cacheMisses)

        val y8Hit = ByteArray(dstW * dstH)
        val uv8Hit = ByteArray(dstW * (dstH / 2))
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 14L,
                addr = yAddr,
                strideLong = stride,
                sizeWord = sizeWord(dstW, dstH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or (1L shl 20),
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to y8Hit, uvAddr to uv8Hit)),
                lens = null,
                uvAddr = uvAddr,
            ),
        )
        assertEquals(3L, engine.cacheMisses)
        assertEquals(2L, engine.cacheHits)
        assertContentEquals(y8First, y8Hit)
        assertContentEquals(uv8First, uv8Hit)

        // Dimensions above 2048 are still rejected cleanly
        assertFalse(
            engine.pumpLiveViewDma(
                tick = 15L,
                addr = yAddr,
                strideLong = 4096L,
                sizeWord = sizeWord(2050, 960),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = sensor,
                memory = RamSink(mutableMapOf(yAddr to ByteArray(1))),
                lens = null,
            ),
        )
    }

    @Test
    fun multiSlotCacheAvoidsThrashingBetweenAlternatingCh0AndCh1Geometries() {
        val raw = makePatternRaw16(1024, 576, seed = 11)
        var exposeCalls = 0
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeCalls++
                destination.put(raw)
                return raw.size
            }
        }
        val engine = LiveViewDmaEngine()
        val ch0Addr = 0x8800_0000L
        val ch1YAddr = 0x8900_0000L
        val ch1UvAddr = 0x8920_0000L
        val ch0Buf = ByteArray(1024 * 768 * 2)
        val ch1YBuf = ByteArray(640 * 480)
        val ch1UvBuf = ByteArray(640 * 480)
        val sink = RamSink(mutableMapOf(ch0Addr to ch0Buf, ch1YAddr to ch1YBuf, ch1UvAddr to ch1UvBuf))

        // Prime Ch0 (1024x768 UYVY) and Ch1 (640x480 NV16) at same tick -> only 1 sensor.expose() call!
        assertTrue(engine.pumpLiveViewDma(1L, ch0Addr, 2048L, sizeWord(1024, 768), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null))
        assertTrue(engine.pumpLiveViewDma(1L, ch1YAddr, 640L, sizeWord(640, 480), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null, uvAddr = ch1UvAddr))
        assertEquals(2L, engine.cacheMisses)
        assertEquals(0L, engine.cacheHits)
        assertEquals(1, exposeCalls, "Same-tick Ch0+Ch1 pump must reuse raw sensor exposure")
        assertEquals(1L, engine.sameTickExposeReuses)

        // Alternating Ch0 <-> Ch1 for 5 frames must hit cache 100% (10 hits, 0 additional misses, 1 expose/tick!)
        repeat(5) { idx ->
            assertTrue(engine.pumpLiveViewDma(idx + 2L, ch0Addr, 2048L, sizeWord(1024, 768), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null))
            assertTrue(engine.pumpLiveViewDma(idx + 2L, ch1YAddr, 640L, sizeWord(640, 480), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null, uvAddr = ch1UvAddr))
        }
        assertEquals(2L, engine.cacheMisses, "Alternating Ch0/Ch1 geometries must not thrash cache")
        assertEquals(10L, engine.cacheHits, "Alternating Ch0/Ch1 geometries must hit multi-slot cache")
        assertEquals(6, exposeCalls, "6 distinct ticks should perform exactly 6 sensor.expose() calls")
        assertEquals(6L, engine.sameTickExposeReuses)
    }

    @Test
    fun bayerBorderDemosaicPreservesCfaParityOnAllEdges() {
        // BUG-V3-7-6: Uniform colored RGGB Bayer frame (R=3000, G=1800, B=600 in 12-bit) must demosaic
        // identically on top/bottom/left/right border pixels as in the interior (no CFA parity inversion!).
        val w = 32
        val h = 16
        val raw = ByteArray(w * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v12 = when {
                    (y and 1) == 0 && (x and 1) == 0 -> 3000
                    (y and 1) == 1 && (x and 1) == 1 -> 600
                    else -> 1800
                }
                val v16 = v12 shl 4
                val idx = (y * w + x) * 2
                raw[idx] = (v16 and 0xff).toByte()
                raw[idx + 1] = ((v16 ushr 8) and 0xff).toByte()
            }
        }
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        val engine = LiveViewDmaEngine()
        val uyvyAddr = 0x8a00_0000L
        val yAddr = 0x8a10_0000L
        val uvAddr = 0x8a20_0000L
        val uyvy = ByteArray(w * h * 2)
        val yBuf = ByteArray(w * h)
        val uvBuf = ByteArray(w * h)
        val sink = RamSink(mutableMapOf(uyvyAddr to uyvy, yAddr to yBuf, uvAddr to uvBuf))

        assertTrue(engine.pumpLiveViewDma(1L, uyvyAddr, (w * 2).toLong(), sizeWord(w, h), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null))
        assertTrue(engine.pumpLiveViewDma(2L, yAddr, w.toLong(), sizeWord(w, h), LiveViewDmaEngine.ENABLE_BIT_28, sensor, sink, null, uvAddr = uvAddr))

        val refUyvyOff = 8 * (w * 2) + 10 * 2
        val refY = yBuf[8 * w + 10]
        val refU = uvBuf[8 * w + 10]
        val refV = uvBuf[8 * w + 11]
        val borderPairs = listOf(0 to 0, (w - 2) to 0, 10 to 0, 0 to (h - 1), (w - 2) to (h - 1), 10 to (h - 1), 0 to 8, (w - 2) to 8)
        for ((bx, by) in borderPairs) {
            val off = by * (w * 2) + bx * 2
            for (b in 0 until 4) {
                assertEquals(uyvy[refUyvyOff + b], uyvy[off + b], "UYVY border ($bx,$by)+$b must match interior CFA parity")
            }
            assertEquals(refY, yBuf[by * w + bx], "NV16 Y border ($bx,$by) must match interior")
            assertEquals(refU, uvBuf[by * w + bx], "NV16 U border ($bx,$by) must match interior")
            assertEquals(refV, uvBuf[by * w + bx + 1], "NV16 V border ($bx,$by) must match interior")
        }
    }

    @Test
    fun sensorFrameGenerationFastPathSkipsArraysEqualsAndInvalidatesOnBump() {
        val srcW = 1024
        val srcH = 576
        val dstW = 1024
        val dstH = 576
        val stride = dstW * 2L
        val raw = makePatternRaw16(srcW, srcH, seed = 11)
        var gen = 1L
        val trackedSensor = object : SensorPort {
            override fun currentFrameGeneration(): Long = gen
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        val legacySensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        val addr = 0x8b00_0000L
        val out1 = ByteArray(dstW * dstH * 2)
        val out2 = ByteArray(dstW * dstH * 2)
        val out3 = ByteArray(dstW * dstH * 2)
        val engine = LiveViewDmaEngine()

        // 1. Tracked sensor: first frame caches, second frame hits currentFrameGeneration() fastpath (0 Arrays.equals!)
        assertTrue(engine.pumpLiveViewDma(1L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, trackedSensor, RamSink(mutableMapOf(addr to out1)), null))
        assertTrue(engine.pumpLiveViewDma(2L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, trackedSensor, RamSink(mutableMapOf(addr to out2)), null))
        assertEquals(1L, engine.sensorGenFastPathHits, "Unchanged currentFrameGeneration() must hit O(1) fastpath")
        assertEquals(0L, engine.arraysEqualsFallbackCalls, "Tracked sensor must skip 1.2MB Arrays.equals scan")
        assertEquals(1L, engine.cacheHits)
        assertContentEquals(out1, out2)

        // 2. Bumping currentFrameGeneration() invalidates immediately without Arrays.equals scan
        val centerByte = ((srcH / 2) * srcW + (srcW / 2)) * 2 + 1
        raw[centerByte] = (raw[centerByte].toInt() xor 0xFF).toByte()
        gen = 2L
        assertTrue(engine.pumpLiveViewDma(3L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, trackedSensor, RamSink(mutableMapOf(addr to out3)), null))
        assertEquals(2L, engine.cacheMisses)
        assertEquals(0L, engine.arraysEqualsFallbackCalls)
        assertFalse(out1.contentEquals(out3), "Generation bump must invalidate YUV cache")

        // 3. Legacy sensor returning -1L preserves exact Arrays.equals fallback
        val legacyEngine = LiveViewDmaEngine()
        assertTrue(legacyEngine.pumpLiveViewDma(1L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, legacySensor, RamSink(mutableMapOf(addr to out1)), null))
        assertTrue(legacyEngine.pumpLiveViewDma(2L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, legacySensor, RamSink(mutableMapOf(addr to out2)), null))
        assertEquals(0L, legacyEngine.sensorGenFastPathHits)
        assertEquals(2L, legacyEngine.arraysEqualsFallbackCalls, "Legacy -1L SensorPort must fall back to Arrays.equals")
        assertEquals(1L, legacyEngine.cacheHits)

        // Micro-benchmark: 100 repeated frames (currentFrameGeneration() fastpath vs -1L Arrays.equals)
        val benchOut = ByteArray(dstW * dstH * 2)
        val sink = RamSink(mutableMapOf(addr to benchOut))
        repeat(5) { i -> engine.pumpLiveViewDma(10L + i, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, trackedSensor, sink, null) }
        repeat(5) { i -> legacyEngine.pumpLiveViewDma(10L + i, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, legacySensor, sink, null) }

        val tFast0 = System.nanoTime()
        for (i in 0 until 100) {
            engine.pumpLiveViewDma(100L + i, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, trackedSensor, sink, null)
        }
        val fastMs = (System.nanoTime() - tFast0) / 1_000_000.0 / 100.0

        val tLegacy0 = System.nanoTime()
        for (i in 0 until 100) {
            legacyEngine.pumpLiveViewDma(100L + i, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, legacySensor, sink, null)
        }
        val legacyMs = (System.nanoTime() - tLegacy0) / 1_000_000.0 / 100.0
        println("[W10-BENCH] LiveViewDmaEngine 1024x576 cached frame: genToken=${"%.3f".format(fastMs)} ms/f vs Arrays.equals(-1L)=${"%.3f".format(legacyMs)} ms/f")
    }

    @Test
    fun gpuFusedOpticsIspPathRoutesUncroppedLiveViewAndFallsBackWhenStatsRequired() {
        val w = 1024
        val h = 576
        val raw = makePatternRaw16(w, h, seed = 5)
        var fusedCalls = 0
        var exposeCalls = 0
        val fusedSensor = object : SensorPort {
            override fun currentFrameGeneration(): Long = 1L
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeCalls++
                destination.put(raw)
                return raw.size
            }

            override fun tryExposeFusedIspYuv(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
            ): Boolean {
                fusedCalls++
                java.util.Arrays.fill(outYOrPacked, 0x5A.toByte())
                if (outUvPlane != null) java.util.Arrays.fill(outUvPlane, 0x80.toByte())
                return true
            }
        }
        val engine = LiveViewDmaEngine()
        val uyvyAddr = 0x8c00_0000L
        val yAddr = 0x8c20_0000L
        val uvAddr = 0x8c40_0000L
        val uyvy = ByteArray(w * h * 2)
        val yPlane = ByteArray(w * h)
        val uvPlane = ByteArray(w * h)
        val sink = RamSink(mutableMapOf(uyvyAddr to uyvy, yAddr to yPlane, uvAddr to uvPlane))

        // 1. Uncropped 1024x576 without raw-Bayer AE stats -> hits fused GPU Optics->ISP path (0 expose calls!)
        assertTrue(engine.pumpLiveViewDma(1L, uyvyAddr, (w * 2).toLong(), sizeWord(w, h), LiveViewDmaEngine.ENABLE_BIT_28, fusedSensor, sink, null))
        assertEquals(1, fusedCalls)
        assertEquals(0, exposeCalls, "GPU-fused path must skip intermediate Bayer sensor.expose()")
        assertEquals(1L, engine.gpuFusedIspFrames)
        assertEquals(0x5A.toByte(), uyvy[0])

        // 2. Semi-planar NV16 also routes through fused GPU path
        assertTrue(engine.pumpLiveViewDma(2L, yAddr, w.toLong(), sizeWord(w, h), LiveViewDmaEngine.ENABLE_BIT_28, fusedSensor, sink, null, uvAddr = uvAddr))
        assertEquals(2, fusedCalls)
        assertEquals(0, exposeCalls)
        assertEquals(2L, engine.gpuFusedIspFrames)
        assertEquals(0x5A.toByte(), yPlane[0])
        assertEquals(0x80.toByte(), uvPlane[0])

        // 3. When ISP requires raw-Bayer AE/AF stats and sensor lacks supportsFusedIspStats(), falls back to standard Bayer path!
        val statsCfg = LiveViewDmaEngine.StatsConfig(engine = IspStatsSramEngine(), reg5c = 0L, reg60 = 0L)
        assertTrue(engine.pumpLiveViewDma(3L, uyvyAddr, (w * 2).toLong(), sizeWord(w, h), LiveViewDmaEngine.ENABLE_BIT_28, fusedSensor, sink, null, statsConfig = statsCfg))
        assertEquals(2, fusedCalls, "Legacy sensor without supportsFusedIspStats() must fall back to standard Bayer path")
        assertEquals(1, exposeCalls)
    }

    /** Reference AE cells and RGB/luma histograms for the simulated sensor when the native reference is absent. */
    private fun fillFusedAeAndHistogram(
        raw: ByteArray, width: Int, request: com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest, words: IntArray,
    ) {
        words.fill(0)
        val pixels = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fun sample(index: Int) = (pixels[index].toInt() and 0xffff) ushr 6
        val step = 2 * request.stepQuad
        for (y in request.viewY until request.viewY + request.viewH - 1 step step) {
            val gridY = (y - request.viewY) * request.gridRows / request.viewH
            for (x in request.viewX until request.viewX + request.viewW - 1 step step) {
                val cell = gridY * request.gridCols + (x - request.viewX) * request.gridCols / request.viewW
                val pos = y * width + x
                val r = sample(pos)
                val g1 = sample(pos + 1)
                val g2 = sample(pos + width)
                val b = sample(pos + width + 1)
                words[cell] += r
                words[256 + cell] += g1
                words[512 + cell] += b
                words[768 + cell] += g2
                words[1024 + cell]++
                val red = r ushr 2
                val green = (g1 + g2 + 1) ushr 3
                val blue = b ushr 2
                words[1280 + ((77 * red + 150 * green + 29 * blue + 128) ushr 8)]++
                words[1536 + red]++
                words[1792 + green]++
                words[2048 + blue]++
                words[2319]++
            }
        }
    }

    @Test
    fun gpuFusedOpticsIspWithStatsPopulatesStatsEngineAndCrops43LcdAndEvf() {
        val srcW = 1024
        val srcH = 576
        // Deterministic 16-bit Bayer frame with distinct brightness across zones
        val raw = makePatternRaw16(srcW, srcH, seed = 19)
        var fusedCalls = 0
        var exposeCalls = 0
        var lastRequest: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest? = null

        val fusedStatsSensor = object : SensorPort {
            override fun currentFrameGeneration(): Long = 1L
            override fun supportsFusedIspStats(): Boolean = true
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeCalls++
                destination.put(raw)
                return raw.size
            }

            override fun tryExposeFusedIspYuv(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
            ): Boolean {
                fusedCalls++
                lastRequest = request
                java.util.Arrays.fill(outYOrPacked, 0x64.toByte())
                if (outUvPlane != null) java.util.Arrays.fill(outUvPlane, 0x80.toByte())
                val statsReq = request.statsRequest
                val outWords = request.outStatsWords
                if (statsReq != null && outWords != null) {
                    // Populate exact 10-bit Bayer AE/Hist/CDAF reduction words matching GPU shader on [viewX..viewX+viewW)
                    val snap = VulkanIspStatsBridge.reduceCpuRef(
                        input = raw,
                        width = srcW,
                        height = srcH,
                        inputFormat = VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE,
                        viewX = statsReq.viewX,
                        viewY = statsReq.viewY,
                        viewW = statsReq.viewW,
                        viewH = statsReq.viewH,
                        gridCols = statsReq.gridCols,
                        gridRows = statsReq.gridRows,
                        stepQuad = statsReq.stepQuad,
                        awbHighlightThresh10 = statsReq.awbHighlightThresh10,
                        afRoiX0 = statsReq.afRoiX0,
                        afRoiY0 = statsReq.afRoiY0,
                        afRoiX1 = statsReq.afRoiX1,
                        afRoiY1 = statsReq.afRoiY1,
                        flags = statsReq.flags,
                    )
                    if (snap != null) {
                        java.lang.System.arraycopy(snap.aeSumR, 0, outWords, 0, 256)
                        java.lang.System.arraycopy(snap.aeSumG1, 0, outWords, 256, 256)
                        java.lang.System.arraycopy(snap.aeSumB, 0, outWords, 512, 256)
                        java.lang.System.arraycopy(snap.aeSumG2, 0, outWords, 768, 256)
                        java.lang.System.arraycopy(snap.aeCount, 0, outWords, 1024, 256)
                        java.lang.System.arraycopy(snap.histY, 0, outWords, 1280, 256)
                        java.lang.System.arraycopy(snap.histR, 0, outWords, 1536, 256)
                        java.lang.System.arraycopy(snap.histG, 0, outWords, 1792, 256)
                        java.lang.System.arraycopy(snap.histB, 0, outWords, 2048, 256)
                        outWords[2304] = snap.awbGlobalSumR.toInt()
                        outWords[2305] = snap.awbGlobalSumG.toInt()
                        outWords[2306] = snap.awbGlobalSumB.toInt()
                        outWords[2307] = snap.awbGlobalCount.toInt()
                        outWords[2308] = snap.awbValidSumR.toInt()
                        outWords[2309] = snap.awbValidSumG.toInt()
                        outWords[2310] = snap.awbValidSumB.toInt()
                        outWords[2311] = snap.awbValidCount.toInt()
                        outWords[2312] = snap.cdafHorizTenengrad.toInt()
                        outWords[2313] = snap.cdafHorizLaplacian.toInt()
                        outWords[2314] = snap.cdafVertTenengrad.toInt()
                        outWords[2315] = snap.cdafVertLaplacian.toInt()
                        outWords[2316] = snap.cdafRoiMaxG14
                        outWords[2317] = snap.cdafRoiSumG10.toInt()
                        outWords[2318] = snap.cdafRoiCount.toInt()
                        outWords[2319] = snap.totalHistPixels.toInt()
                    } else {
                        fillFusedAeAndHistogram(raw, srcW, statsReq, outWords)
                    }
                    request.statsProduced = true
                }
                return true
            }
        }

        val engine = LiveViewDmaEngine()
        val statsEngine = IspStatsSramEngine()
        val statsCfg = LiveViewDmaEngine.StatsConfig(engine = statsEngine, reg5c = 0L, reg60 = 0L)

        // Reference CPU Bayer AE engine on exact 4:3 center crop (x=128, y=0, w=768, h=576)
        val refStatsEngine = IspStatsSramEngine()
        refStatsEngine.updateAeFromBayer(raw, srcW, srcH, is16Bit = true, reg5c = 0L, reg60 = 0L, viewX = 128, viewY = 0, viewW = 768, viewH = 576)

        // 1. Rear LCD 4:3 (1024x768) with statsConfig != null -> hits GPU fused path with FLAG_SRC_CROP + populates stats!
        val lcdW = 1024
        val lcdH = 768
        val lcdAddr = 0x8d00_0000L
        val lcdBuf = ByteArray(lcdW * lcdH * 2)
        val lcdSink = RamSink(mutableMapOf(lcdAddr to lcdBuf))
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 10L,
                addr = lcdAddr,
                strideLong = (lcdW * 2).toLong(),
                sizeWord = sizeWord(lcdW, lcdH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = fusedStatsSensor,
                memory = lcdSink,
                lens = null,
                statsConfig = statsCfg,
            ),
        )
        assertEquals(1, fusedCalls, "4:3 LCD (1024x768) with statsConfig must use GPU-fused path")
        assertEquals(0, exposeCalls, "GPU-fused path must skip raw Bayer expose() completely")
        assertEquals(1L, engine.gpuFusedIspFrames)
        assertEquals(1L, statsEngine.aeFramesUpdated, "Fused frame must update AE stats")
        assertEquals(1L, statsEngine.histFramesUpdated, "Fused frame must update Histogram stats")
        assertEquals(1L, statsEngine.gpuStatsFramesUpdated, "Fused frame must increment gpuStatsFramesUpdated")
        val lcdReq = checkNotNull(lastRequest)
        assertTrue((lcdReq.flags and com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_SRC_CROP) != 0, "4:3 LCD must set FLAG_SRC_CROP")
        assertEquals(128, lcdReq.offX)
        assertEquals(0, lcdReq.offY)
        assertEquals(768, lcdReq.fitW)
        assertEquals(576, lcdReq.fitH)
        assertEquals(refStatsEngine.lastMeanAeCode, statsEngine.lastMeanAeCode, "Fused GPU AE brightness must match CPU Bayer AE exactly")

        // 2. EVF 4:3 (1280x960) with statsConfig != null -> ALSO hits GPU fused path with FLAG_SRC_CROP + populates stats!
        val evfW = 1280
        val evfH = 960
        val evfAddr = 0x8e00_0000L
        val evfBuf = ByteArray(evfW * evfH * 2)
        val evfSink = RamSink(mutableMapOf(evfAddr to evfBuf))
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 20L,
                addr = evfAddr,
                strideLong = (evfW * 2).toLong(),
                sizeWord = sizeWord(evfW, evfH),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                sensor = fusedStatsSensor,
                memory = evfSink,
                lens = null,
                statsConfig = statsCfg,
            ),
        )
        assertEquals(2, fusedCalls, "4:3 EVF (1280x960) with statsConfig must use GPU-fused path")
        assertEquals(0, exposeCalls)
        assertEquals(2L, engine.gpuFusedIspFrames)
        assertEquals(2L, statsEngine.aeFramesUpdated)
        assertEquals(2L, statsEngine.histFramesUpdated)
        assertEquals(2L, statsEngine.gpuStatsFramesUpdated)
        val evfReq = checkNotNull(lastRequest)
        assertTrue((evfReq.flags and com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_SRC_CROP) != 0, "4:3 EVF must set FLAG_SRC_CROP")
        assertEquals(128, evfReq.offX)
        assertEquals(768, evfReq.fitW)
        assertEquals(refStatsEngine.lastMeanAeCode, statsEngine.lastMeanAeCode, "EVF fused GPU AE brightness must match CPU Bayer AE exactly")
    }

    @Test
    fun staticFusedLiveViewFrameHitsCacheAndInvalidatesOnStateChangeOrDynamicStream() {
        val dstW = 1024
        val dstH = 576
        val stride = dstW * 2L
        var fusedCalls = 0
        var opticalVersion = 10L
        var dynamicMode = false
        val sensor = object : SensorPort {
            override fun supportsFusedIspStats(): Boolean = true
            override fun fusedFrameStateKey(exposure: Exposure): Long {
                if (dynamicMode) return -1L
                var h = 1469598103934665603L
                h = (h xor opticalVersion) * 1099511628211L
                h = (h xor exposure.durationNanos) * 1099511628211L
                h = (h xor exposure.iso.toLong()) * 1099511628211L
                h = (h xor exposure.lens.focusDistanceM.toBits()) * 1099511628211L
                return h and Long.MAX_VALUE
            }
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("raw expose should not be called")
            override fun tryExposeFusedIspYuv(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
            ): Boolean {
                fusedCalls++
                val fill = ((fusedCalls * 37) and 0xFF).toByte()
                outYOrPacked.fill(fill)
                val outWords = request.outStatsWords
                if (request.statsRequest != null && outWords != null) {
                    outWords.fill(0)
                    outWords[2319] = 1024
                    request.statsProduced = true
                }
                return true
            }
        }

        val engine = LiveViewDmaEngine()
        val statsEngine = IspStatsSramEngine()
        val statsCfg = LiveViewDmaEngine.StatsConfig(engine = statsEngine, reg5c = 0L, reg60 = 0L)
        val addr = 0x8f00_0000L
        val out1 = ByteArray(dstW * dstH * 2)
        val out2 = ByteArray(dstW * dstH * 2)
        val out3 = ByteArray(dstW * dstH * 2)
        val out4 = ByteArray(dstW * dstH * 2)
        val lensA = LensParameters("GF80", 80.0, 1.7, 1.5, 0.9, 0.0)
        val lensB = LensParameters("GF80", 80.0, 1.7, 3.0, 0.9, 0.0)

        // 1. Cold fused render populates slot + stats
        assertTrue(engine.pumpLiveViewDma(1L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, sensor, RamSink(mutableMapOf(addr to out1)), lensA, statsConfig = statsCfg))
        assertEquals(1, fusedCalls)
        assertEquals(0L, engine.cacheHits)

        // 2. Identical static V-Sync hits fused YUV cache (0 extra GPU fused calls!)
        assertTrue(engine.pumpLiveViewDma(2L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, sensor, RamSink(mutableMapOf(addr to out2)), lensA, statsConfig = statsCfg))
        assertEquals(1, fusedCalls, "Unchanged static fused LiveView frame must skip redundant GPU render")
        assertEquals(1L, engine.cacheHits)
        assertContentEquals(out1, out2)

        // 3. Focus distance change invalidates fused cache immediately
        assertTrue(engine.pumpLiveViewDma(3L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, sensor, RamSink(mutableMapOf(addr to out3)), lensB, statsConfig = statsCfg))
        assertEquals(2, fusedCalls, "Lens focus change must invalidate fused YUV cache")
        assertFalse(out1.contentEquals(out3))

        // 4. Dynamic/streaming source returning -1L never caches and always re-renders
        dynamicMode = true
        assertTrue(engine.pumpLiveViewDma(4L, addr, stride, sizeWord(dstW, dstH), LiveViewDmaEngine.ENABLE_BIT_28, sensor, RamSink(mutableMapOf(addr to out4)), lensB, statsConfig = statsCfg))
        assertEquals(3, fusedCalls, "Dynamic sensor returning fusedFrameStateKey=-1L must bypass fused YUV cache")
        opticalVersion++
        assertTrue(opticalVersion > 10L)
        dynamicMode = false
        val statsSink = RamSink(mutableMapOf(addr to out4))
        fun pumpStats(config: LiveViewDmaEngine.StatsConfig): Long {
            assertTrue(engine.pumpLiveViewDma(5L, addr, stride, sizeWord(dstW, dstH),
                LiveViewDmaEngine.ENABLE_BIT_28, sensor, statsSink, lensB, statsConfig = config))
            return statsEngine.gpuStatsFramesUpdated
        }
        var previous = pumpStats(statsCfg)
        val secondaryGrid = statsCfg.copy(reg68 = 31, reg6c = 47)
        for (updated in listOf(secondaryGrid, secondaryGrid.copy(obG1 = 512))) {
            assertEquals(previous + 1, pumpStats(updated), "Changed metering registers must refresh statistics")
            previous++
            assertEquals(previous, pumpStats(updated), "An unchanged metering configuration must hit the cache")
        }
    }

    @Test
    fun renderOpticalUyvyToDma102MpDirectGuestRamMatchesFallbackBitExactAndIsFast() {
        val dstW = 11664
        val dstH = 8764
        val stride = 31680 // Real GFX100S 102MP RECC_S hardware line pitch (~277.6 MB)
        val totalBytes = (dstH - 1).toLong() * stride.toLong() + dstW.toLong() * 2L
        val cropW = 766
        val cropH = 576

        // Deterministic synthetic cropped UYVY frame returned by tryExposeStillUyvy
        val croppedUyvy = ByteArray(cropW * cropH * 2)
        for (y in 0 until cropH) {
            for (x in 0 until cropW step 2) {
                val off = y * cropW * 2 + x * 2
                croppedUyvy[off] = ((x * 3 + y) and 0xFF).toByte()
                croppedUyvy[off + 1] = ((x + y * 5) and 0xFF).toByte()
                croppedUyvy[off + 2] = ((x * 7 + y * 2) and 0xFF).toByte()
                croppedUyvy[off + 3] = ((x * 2 + y * 3 + 17) and 0xFF).toByte()
            }
        }
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unused")
            override fun tryExposeStillUyvy(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outUyvy: ByteArray,
            ): Boolean {
                require(request.dstWidth == cropW && request.dstHeight == cropH) {
                    "Unexpected crop size ${request.dstWidth}x${request.dstHeight}"
                }
                System.arraycopy(croppedUyvy, 0, outUyvy, 0, croppedUyvy.size)
                return true
            }
        }

        val baseAddr = 0x8000_0000L
        var notifyCalls = 0
        var lastNotifiedSize = 0L
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val directBuf = arena.allocate(totalBytes, 64L)
            val directDma = object : DmaMemory, com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(write && address == baseAddr && size == totalBytes)
                }
                override fun read(address: Long, destination: ByteBuffer) = error("unused")
                override fun write(address: Long, source: ByteBuffer) = error("direct path should not use chunked write")
                override fun sliceDirect(address: Long, size: Long, write: Boolean): java.lang.foreign.MemorySegment? {
                    return if (write && address == baseAddr && size == totalBytes) directBuf else null
                }
                override fun notifyDirectWrite(address: Long, size: Long) {
                    notifyCalls++
                    lastNotifiedSize = size
                }
            }

            val engine = LiveViewDmaEngine()
            // Warm up once, then measure
            assertTrue(engine.renderOpticalUyvyToDma(1L, baseAddr, dstW, dstH, stride, sensor, directDma, null))
            notifyCalls = 0
            val t0 = System.nanoTime()
            assertTrue(engine.renderOpticalUyvyToDma(2L, baseAddr, dstW, dstH, stride, sensor, directDma, null))
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            println("[LiveViewDmaEnginePerfCacheTest] 102MP RECC_S DirectGuestRamPort (${dstW}x$dstH, stride=$stride): ${"%.1f".format(elapsedMs)} ms")

            assertEquals(1, notifyCalls)
            assertEquals(totalBytes, lastNotifiedSize)
            assertTrue(elapsedMs < 350.0, "102MP RECC_S DirectGuestRamPort upscale took too long: $elapsedMs ms")

            // Verify bit-exact pixel mapping on sampled rows (top, middle, bottom) + zero padding margin
            val pairCount = dstW ushr 1
            val cropPairCount = cropW ushr 1
            for (dstY in intArrayOf(0, 1, 7, 15, dstH / 2, dstH - 2, dstH - 1)) {
                val sy = ((dstY.toLong() * cropH) / dstH).toInt().coerceIn(0, cropH - 1)
                val rowOff = dstY.toLong() * stride.toLong()
                for (px in intArrayOf(0, 1, 17, pairCount / 2, pairCount - 2, pairCount - 1)) {
                    val sp = ((px.toLong() * cropPairCount) / pairCount).toInt().coerceIn(0, cropPairCount - 1)
                    val sOff = sy * cropW * 2 + sp * 4
                    val dOff = rowOff + (px shl 2).toLong()
                    for (b in 0..3) {
                        assertEquals(
                            croppedUyvy[sOff + b],
                            directBuf.get(java.lang.foreign.ValueLayout.JAVA_BYTE, dOff + b),
                            "Pixel mismatch at dstY=$dstY px=$px b=$b",
                        )
                    }
                }
                if (dstY < dstH - 1) {
                    assertEquals(0.toByte(), directBuf.get(java.lang.foreign.ValueLayout.JAVA_BYTE, rowOff + dstW * 2L))
                    assertEquals(0.toByte(), directBuf.get(java.lang.foreign.ValueLayout.JAVA_BYTE, rowOff + stride - 1L))
                }
            }
        }
    }

    @Test
    fun renderOpticalUyvyToDmaWritesValidUyvyTo34BitHighRamInBothFusedAndFallbackPaths() {
        val highRamAddr = 0x1_B000_0000L
        val dstW = 64
        val dstH = 48
        val stride = dstW * 2
        val totalBytes = dstH * stride
        val ram = ByteArray(totalBytes)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == highRamAddr && size == totalBytes.toLong()) {
                    "Unexpected check at 0x${address.toString(16)} size=$size"
                }
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                val off = (address - highRamAddr).toInt()
                source.get(ram, off, source.remaining())
            }
        }

        val engine = LiveViewDmaEngine()

        // 1. GPU fused path writing to 34-bit High-RAM (>= 0x1_0000_0000L)
        val fusedSensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unused")
            override fun tryExposeStillUyvy(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outUyvy: ByteArray,
            ): Boolean {
                for (i in outUyvy.indices step 2) {
                    outUyvy[i] = 0x80.toByte()
                    outUyvy[i + 1] = 0x64.toByte()
                }
                return true
            }
        }
        assertTrue(engine.renderOpticalUyvyToDma(10L, highRamAddr, dstW, dstH, stride, fusedSensor, mem, null))
        assertEquals(0x80.toByte(), ram[0])
        assertEquals(0x64.toByte(), ram[1])
        assertEquals(0x64.toByte(), ram[totalBytes - 1])

        // 2. CPU Bayer fallback path writing to 34-bit High-RAM
        ram.fill(0)
        val cpuSensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unused")
            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int {
                for (i in 0 until dstW * dstH) destination.putShort(0x4000.toShort())
                return dstW * dstH * 2
            }
        }
        assertTrue(engine.renderOpticalUyvyToDma(20L, highRamAddr, dstW, dstH, stride, cpuSensor, mem, null))
        val meanLuma = (1 until totalBytes step 2).map { ram[it].toInt() and 0xFF }.average()
        assertTrue(meanLuma > 50.0, "CPU fallback High-RAM UYVY luma should be non-zero (got $meanLuma)")

        // 3. Reject addresses past 34-bit AXI space
        assertTrue(!engine.renderOpticalUyvyToDma(30L, (1L shl 34) - 16L, dstW, dstH, stride, cpuSensor, mem, null))
    }

    @Test
    fun renderOpticalUyvyToDmaUsesLatchedStillReadoutAndGainsWithoutReExposingSensor() {
        val highRamAddr = 0x1_B000_0000L
        val srcW = 64
        val srcH = 48
        val dstW = 64
        val dstH = 48
        val stride = dstW * 2
        val totalBytes = dstH * stride
        val ram = ByteArray(totalBytes)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == highRamAddr && size == totalBytes.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(ram, (address - highRamAddr).toInt(), source.remaining())
            }
        }
        val bayer = ByteBuffer.allocate(srcW * srcH * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until srcW * srcH) bayer.putShort(0x3000.toShort())
        bayer.flip()
        val readout = com.github.nyabanban.fuji.emulator.api.IspStillReadout(
            tick = 100L, width = srcW, height = srcH,
            gainR1024 = 1024, gainG1024 = 1024, gainB1024 = 1024,
            samples = bayer,
        )
        val throwingSensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("must not re-expose")
            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int = error("must not re-expose")
            override fun tryExposeStillUyvy(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outUyvy: ByteArray,
            ): Boolean = error("must not call GPU fused when stillReadout is active")
        }
        val engine = LiveViewDmaEngine()
        assertTrue(
            engine.renderOpticalUyvyToDma(
                tick = 200L, addr = highRamAddr, width = dstW, height = dstH, stride = stride,
                sensor = throwingSensor, memory = mem, lens = null, stillReadout = readout,
            ),
        )
        val meanLuma = (1 until totalBytes step 2).map { ram[it].toInt() and 0xFF }.average()
        assertTrue(meanLuma > 40.0, "Latched stillReadout UYVY luma should be positive (got $meanLuma)")
    }
}
