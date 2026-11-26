package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.MemorySegment
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies bit-exact parity between the native SIMD rendering pipeline (`libfuji_render.so` / `fuji_render.dll`)
 * and the pure-Kotlin scalar reference implementations across every kernel, plus live A/B speedup measurements.
 */
class NativeRenderBridgeParityTest {

    @Test
    fun nativeBridgeLoadsAndMatchesKotlinBitForBit() {
        println(
            "[NativeRenderBridgeParityTest] available=${NativeRenderBridge.isAvailable} " +
                "backend=${NativeRenderBridge.backendName} simd=${NativeRenderBridge.simdLevel} " +
                "path=${NativeRenderBridge.libraryPath} err=${NativeRenderBridge.lastLoadError}",
        )
        val requireNative = System.getenv("FUJI_REQUIRE_RENDER_NATIVE") == "1"
        if (!requireNative) {
            assumeTrue(
                NativeRenderBridge.isAvailable,
                "Native render bridge not present in this environment (${NativeRenderBridge.lastLoadError}); pure-Kotlin fallback active",
            )
        }
        assertTrue(NativeRenderBridge.isAvailable, "Expected NativeRenderBridge to load libfuji_render: ${NativeRenderBridge.lastLoadError}")
        assertTrue(NativeRenderBridge.simdLevel >= 1, "Expected at least SSE2 SIMD level")

        // 1. LcdcFirstScanout compositePic1AndOsd0 parity: 1024x768 1:1, 1280x960 EVF 1:1, and 640x480 scaled
        for ((canvasW, canvasH, outW, outH) in listOf(
            listOf(1024, 768, 1024, 768),
            listOf(1280, 960, 1280, 960),
            listOf(1024, 768, 640, 480),
        )) {
            val osdStride = canvasW * 4
            val osdBuf = ByteBuffer.allocate(osdStride * canvasH).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    val a = when {
                        x < 64 -> 0xff
                        x < 128 -> 0x00
                        else -> ((x * 19 + y * 13) and 0xff)
                    }
                    val r = (x * 255 / (canvasW - 1)) and 0xff
                    val g = (y * 255 / (canvasH - 1)) and 0xff
                    val b = (x xor y) and 0xff
                    osdBuf.putInt((y * canvasW + x) * 4, (r shl 24) or (g shl 16) or (b shl 8) or a)
                }
            }
            val picW = canvasW - 64
            val picH = canvasH - 120
            val picX = 32
            val picY = 60
            val picStride = picW * 2
            val picBytes = ByteArray(picStride * picH)
            for (y in 0 until picH) {
                for (x in 0 until picW step 2) {
                    val idx = y * picStride + x * 2
                    if (x == 0 || (x == 16 && y == 10)) {
                        // BUG-09 all-zero UYVY macro-pixel -> neutral black
                        picBytes[idx] = 0; picBytes[idx + 1] = 0; picBytes[idx + 2] = 0; picBytes[idx + 3] = 0
                    } else if (x == 2 || (x == 18 && y == 11)) {
                        // BUG-A5-5 Milbeaut 0x80800000 ([00 00 80 80]) pillarbox black fill -> neutral black
                        picBytes[idx] = 0; picBytes[idx + 1] = 0; picBytes[idx + 2] = 0x80.toByte(); picBytes[idx + 3] = 0x80.toByte()
                    } else if (x == 4 || (x == 20 && y == 12)) {
                        // BUG-A5-5 Milbeaut 0x00008080 ([80 80 00 00]) pillarbox black fill -> neutral black
                        picBytes[idx] = 0x80.toByte(); picBytes[idx + 1] = 0x80.toByte(); picBytes[idx + 2] = 0; picBytes[idx + 3] = 0
                    } else {
                        picBytes[idx] = ((x * 3 + 40) and 0xff).toByte()
                        picBytes[idx + 1] = ((y * 5 + x) and 0xff).toByte()
                        picBytes[idx + 2] = ((255 - x * 2) and 0xff).toByte()
                        picBytes[idx + 3] = ((y * 7 + x * 3) and 0xff).toByte()
                    }
                }
            }

            val ctx = LcdcFunctionalTest.Context()
            ctx.entries["display.main"] = ArgbFrameBuffer(outW, outH)
            val scanout = LcdcFirstScanout(ctx, 1, LcdcFirstScanout.PIC_YUV_OSD0, continuous = true)
            scanout.bind()

            for (picAlpha in listOf(255, 180)) {
                for (opaqueBackdrop in listOf(false, true)) {
                    val outNative = IntArray(outW * outH)
                    val outKotlin = IntArray(outW * outH)
                    scanout.compositePic1AndOsd0(
                        osdBuf, osdStride, canvasW, canvasH,
                        picBytes, picX, picY, picW, picH, picStride, picAlpha, opaqueBackdrop,
                        outNative, allowNative = true,
                    )
                    scanout.compositePic1AndOsd0(
                        osdBuf, osdStride, canvasW, canvasH,
                        picBytes, picX, picY, picW, picH, picStride, picAlpha, opaqueBackdrop,
                        outKotlin, allowNative = false,
                    )
                    assertContentEquals(
                        outKotlin,
                        outNative,
                        "Composite mismatch for ${canvasW}x$canvasH->${outW}x$outH alpha=$picAlpha opaque=$opaqueBackdrop",
                    )
                }
            }

            // Benchmark 1280x960 EVF 1:1 native vs Kotlin pure composite time
            if (canvasW == 1280 && outW == 1280) {
                val outBuf = IntArray(outW * outH)
                repeat(5) {
                    scanout.compositePic1AndOsd0(osdBuf, osdStride, canvasW, canvasH, picBytes, picX, picY, picW, picH, picStride, 255, false, outBuf, allowNative = false)
                    scanout.compositePic1AndOsd0(osdBuf, osdStride, canvasW, canvasH, picBytes, picX, picY, picW, picH, picStride, 255, false, outBuf, allowNative = true)
                }
                val kotlinTimes = List(15) {
                    val s = System.nanoTime()
                    scanout.compositePic1AndOsd0(osdBuf, osdStride, canvasW, canvasH, picBytes, picX, picY, picW, picH, picStride, 255, false, outBuf, allowNative = false)
                    System.nanoTime() - s
                }.sorted()
                val nativeTimes = List(15) {
                    val s = System.nanoTime()
                    scanout.compositePic1AndOsd0(osdBuf, osdStride, canvasW, canvasH, picBytes, picX, picY, picW, picH, picStride, 255, false, outBuf, allowNative = true)
                    System.nanoTime() - s
                }.sorted()
                println(
                    "PERF2_EVF_COMPOSITE_AB kotlinMedianMs=${"%.3f".format(kotlinTimes[7] / 1e6)} " +
                        "nativeMedianMs=${"%.3f".format(nativeTimes[7] / 1e6)} " +
                        "speedup=${"%.2fx".format(kotlinTimes[7].toDouble() / nativeTimes[7])}",
                )
            }
        }

        // 2. Morton 8x8 tiling parity + A/B speedup on 1024x768 (3 MiB)
        val w = 1024
        val h = 768
        val bytes = w * h * 4
        val linear = ByteArray(bytes) { (it * 31 + (it ushr 8)).toByte() }
        val mortonNative = ByteArray(bytes)
        val mortonKotlin = ByteArray(bytes)
        assertTrue(NativeRenderBridge.mortonTile8x8(linear, mortonNative, w, h, w * 4))
        val mortonCol = IntArray(64) { m -> (m and 1) + ((m ushr 1) and 2) + ((m ushr 2) and 4) }
        val mortonRow = IntArray(64) { m -> ((m ushr 1) and 1) + ((m ushr 2) and 2) + ((m ushr 3) and 4) }
        var off = 0
        for (top in 0 until h step 8) {
            for (left in 0 until w step 8) {
                val base = top * (w * 4) + (left shl 2)
                for (m in 0 until 64) {
                    val s = base + mortonRow[m] * (w * 4) + (mortonCol[m] shl 2)
                    mortonKotlin[off] = linear[s]
                    mortonKotlin[off + 1] = linear[s + 1]
                    mortonKotlin[off + 2] = linear[s + 2]
                    mortonKotlin[off + 3] = linear[s + 3]
                    off += 4
                }
            }
        }
        assertContentEquals(mortonKotlin, mortonNative, "Morton 8x8 native vs Kotlin mismatch")

        // 3. SolidCommandRects fillRectRgba parity (opaque + translucent srcOver + non-srcOver)
        val frameNative = ByteArray(128 * 64 * 4) { (it * 17).toByte() }
        val frameKotlin = frameNative.copyOf()
        for ((color, srcOver) in listOf(
            0xff112233.toInt() to true,
            0x80445566.toInt() to true,
            0x40778899 to false,
        )) {
            assertTrue(NativeRenderBridge.fillRectRgba(frameNative, 128, 7, 5, 113, 59, color, srcOver))
            val alpha = (color ushr 24) and 255
            val blue = (color ushr 16) and 255
            val green = (color ushr 8) and 255
            val red = color and 255
            for (r in 5 until 59) for (c in 7 until 113) {
                val idx = 4 * (r * 128 + c)
                if (!srcOver || alpha == 255) {
                    frameKotlin[idx] = alpha.toByte()
                    frameKotlin[idx + 1] = blue.toByte()
                    frameKotlin[idx + 2] = green.toByte()
                    frameKotlin[idx + 3] = red.toByte()
                } else {
                    val oldA = frameKotlin[idx].toInt() and 255
                    val cov = alpha * 255 + oldA * (255 - alpha)
                    frameKotlin[idx] = div255Rounded(cov).toByte()
                    frameKotlin[idx + 1] = ((blue * alpha * 255 + (frameKotlin[idx + 1].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                    frameKotlin[idx + 2] = ((green * alpha * 255 + (frameKotlin[idx + 2].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                    frameKotlin[idx + 3] = ((red * alpha * 255 + (frameKotlin[idx + 3].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                }
            }
            assertContentEquals(frameKotlin, frameNative, "fillRectRgba mismatch color=0x${color.toUInt().toString(16)} srcOver=$srcOver")
        }

        // 3b. PolygonFill native scanline rasterizer bit-exact parity (concave + translucent + clipped)
        val polySubpath = SolidCommandRects.Subpath(
            listOf(
                SolidCommandRects.Point(10.2, 4.1),
                SolidCommandRects.Point(115.8, 12.6),
                SolidCommandRects.Point(80.5, 31.3),
                SolidCommandRects.Point(121.4, 58.9),
                SolidCommandRects.Point(18.7, 54.2),
            ),
            closed = true,
        )
        val polyScissor = SolidCommandRects.Scissor(12, 6, 118, 56)
        for ((color, srcOver) in listOf(0xff223344.toInt() to true, 0x70556677 to true, 0x508899aa to false)) {
            val fNat = ByteArray(128 * 64 * 4) { (it * 29).toByte() }
            val fKot = fNat.copyOf()
            val ex0 = doubleArrayOf(10.2, 115.8, 80.5, 121.4, 18.7)
            val ey0 = doubleArrayOf(4.1, 12.6, 31.3, 58.9, 54.2)
            val ex1 = doubleArrayOf(115.8, 80.5, 121.4, 18.7, 10.2)
            val ey1 = doubleArrayOf(12.6, 31.3, 58.9, 54.2, 4.1)
            assertTrue(NativeRenderBridge.fillPolygonRgba(fNat, 128, ex0, ey0, ex1, ey1, 5, 6, 56, 12, 118, color, srcOver))
            // Pure Kotlin scalar reference
            val alpha = (color ushr 24) and 255
            val blue = (color ushr 16) and 255
            val green = (color ushr 8) and 255
            val red = color and 255
            val xs = ArrayList<Double>()
            for (row in 6 until 56) {
                val yc = row + 0.5
                xs.clear()
                for (e in 0 until 5) {
                    if ((ey0[e] <= yc && ey1[e] > yc) || (ey1[e] <= yc && ey0[e] > yc)) {
                        xs.add(ex0[e] + (yc - ey0[e]) * (ex1[e] - ex0[e]) / (ey1[e] - ey0[e]))
                    }
                }
                xs.sort()
                var k = 0
                while (k + 1 < xs.size) {
                    val sc = kotlin.math.ceil(xs[k] - 0.5).toInt().coerceIn(polyScissor.clipLeft, polyScissor.clipRight)
                    val ec = kotlin.math.ceil(xs[k + 1] - 0.5).toInt().coerceIn(polyScissor.clipLeft, polyScissor.clipRight)
                    for (col in sc until ec) {
                        val idx = 4 * (row * 128 + col)
                        if (!srcOver || alpha == 255) {
                            fKot[idx] = alpha.toByte(); fKot[idx + 1] = blue.toByte(); fKot[idx + 2] = green.toByte(); fKot[idx + 3] = red.toByte()
                        } else {
                            val oldA = fKot[idx].toInt() and 255
                            val cov = alpha * 255 + oldA * (255 - alpha)
                            fKot[idx] = div255Rounded(cov).toByte()
                            fKot[idx + 1] = ((blue * alpha * 255 + (fKot[idx + 1].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                            fKot[idx + 2] = ((green * alpha * 255 + (fKot[idx + 2].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                            fKot[idx + 3] = ((red * alpha * 255 + (fKot[idx + 3].toInt() and 255) * oldA * (255 - alpha) + cov / 2) / cov).toByte()
                        }
                    }
                    k += 2
                }
            }
            assertContentEquals(fKot, fNat, "fillPolygonRgba mismatch color=0x${color.toUInt().toString(16)} srcOver=$srcOver")
            // Also verify PolygonFill.paint end-to-end
            val fShape = ByteArray(128 * 64 * 4) { (it * 29).toByte() }
            SolidCommandRects.PolygonFill(listOf(polySubpath), color, srcOver, polyScissor, 128).paint(fShape)
            assertContentEquals(fKot, fShape, "PolygonFill.paint mismatch color=0x${color.toUInt().toString(16)} srcOver=$srcOver")
        }

        // 4. Continuous-mode VGLIB fastpath benchmark (live emulator path vs strict path!)
        val fillMemBuf = ByteArray(bytes)
        val copyDstBuf = ByteArray(bytes)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) {
                val src = when (address) {
                    0x1000_0000L -> linear
                    0x2000_0000L -> fillMemBuf
                    else -> copyDstBuf
                }
                destination.put(src, 0, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(if (address == 0x2000_0000L) fillMemBuf else copyDstBuf, 0, source.remaining())
            }
        }
        val fillStrict = GraphicsFillExecution(
            mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 2, "surfaceBase" to 0x2000_0000L, "surfaceBytes" to bytes),
            continuous = false,
        )
        val fillContinuous = GraphicsFillExecution(
            mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 2, "surfaceBase" to 0x2000_0000L, "surfaceBytes" to bytes),
            continuous = true,
        )
        val copyStrict = GraphicsCopyExecution(
            mapOf(
                "profile" to GraphicsCopyExecution.PROFILE,
                "sourceBase" to 0x1000_0000L,
                "destinationBase" to 0x1030_0000L,
                "surfaceBytes" to bytes,
                "submissionLimit" to 2,
                GraphicsMode2Workspace.SETTING to mapOf("base" to 0x1000_0000L, "bytes" to 0x80_0000L),
                GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES,
            ),
            continuous = false,
        )
        val copyContinuous = GraphicsCopyExecution(
            mapOf(
                "profile" to GraphicsCopyExecution.PROFILE,
                "sourceBase" to 0x1000_0000L,
                "destinationBase" to 0x1030_0000L,
                "surfaceBytes" to bytes,
                "submissionLimit" to 2,
                GraphicsMode2Workspace.SETTING to mapOf("base" to 0x1000_0000L, "bytes" to 0x80_0000L),
                GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES,
            ),
            continuous = true,
        )
        val fillWords = longArrayOf(0x2000_0000L, 0x2000_0000L + bytes, 0xff112233L)
        val copyM1Words = longArrayOf(0x1000_0000L, 0x1030_0000L, 0x03000400L, 0x03000400L, 1, 1, 0, 0, 0)
        val copyM2Words = longArrayOf(0x1000_0000L, 0x1030_0000L, 0x03000400L, 0x03000400L, 2, 1, 0, 0, 0)
        repeat(3) {
            fillStrict.execute(mem, fillWords, GraphicsFillExecution.CONTROL)
            fillContinuous.execute(mem, fillWords, GraphicsFillExecution.CONTROL)
            copyStrict.execute(mem, copyM1Words)
            copyContinuous.execute(mem, copyM1Words)
            copyStrict.execute(mem, copyM2Words)
            copyContinuous.execute(mem, copyM2Words)
        }
        fun medMs(block: () -> Unit): Double = List(11) {
            val s = System.nanoTime(); block(); System.nanoTime() - s
        }.sorted()[5] / 1e6
        val fillStrictMs = medMs { fillStrict.execute(mem, fillWords, GraphicsFillExecution.CONTROL) }
        val fillLiveMs = medMs { fillContinuous.execute(mem, fillWords, GraphicsFillExecution.CONTROL) }
        val copyM1StrictMs = medMs { copyStrict.execute(mem, copyM1Words) }
        val copyM1LiveMs = medMs { copyContinuous.execute(mem, copyM1Words) }
        val copyM2StrictMs = medMs { copyStrict.execute(mem, copyM2Words) }
        val copyM2LiveMs = medMs { copyContinuous.execute(mem, copyM2Words) }

        // 5. Real menu-page GraphicsCommandExecution strict vs continuous (live emulator path)
        val heapBase = 0x06fe6000L
        val cmdTarget = 0x06893000L
        val heap = GraphicsMode2Workspace.parse(mapOf("base" to heapBase, "bytes" to 0xa00000))
        val inputs = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/language-page/heap-slices.bin.gz"))).use { it.readBytes() }
        val cmdFrameStrict = ByteArray(0x300000)
        val cmdFrameLive = ByteArray(0x300000)
        fun cmdMemFor(buf: ByteArray) = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == cmdTarget) destination.put(buf)
                else destination.put(inputs, (address - heapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) { source.get(buf) }
        }
        val cmdFirst = mapOf("address" to 0x06fe6040L, "countWord" to 1501L, "control" to 0x0100ffffL)
        val cmdSecond = cmdFirst + mapOf("address" to 0x07066040L, "countWord" to 2619L)
        val cmdStrict = GraphicsCommandExecution(heap, cmdTarget, solidRects = true, continuous = false)
        val cmdLive = GraphicsCommandExecution(heap, cmdTarget, solidRects = true, continuous = true)
        val memStrict = cmdMemFor(cmdFrameStrict)
        val memLive = cmdMemFor(cmdFrameLive)
        repeat(3) {
            cmdFrameStrict.fill(0); cmdStrict.execute(memStrict, cmdFirst, cmdSecond)
            cmdFrameLive.fill(0); cmdLive.execute(memLive, cmdFirst, cmdSecond)
        }
        assertContentEquals(cmdFrameStrict, cmdFrameLive, "Continuous GraphicsCommandExecution must render bit-identical framebuffer to strict mode")
        val cmdStrictMs = medMs { cmdFrameStrict.fill(0); cmdStrict.execute(memStrict, cmdFirst, cmdSecond) }
        val cmdLiveMs = medMs { cmdFrameLive.fill(0); cmdLive.execute(memLive, cmdFirst, cmdSecond) }
        val mortonNativeMs = medMs { NativeRenderBridge.mortonTile8x8(linear, mortonNative, w, h, w * 4) }

        assertEquals(bytes, (fillContinuous.execute(mem, fillWords, GraphicsFillExecution.CONTROL)["bytes"] as Number).toInt())
        println(
            "PERF2_VGLIB_CONTINUOUS_AB " +
                "fillStrictMs=${"%.3f".format(fillStrictMs)} fillLiveMs=${"%.3f".format(fillLiveMs)} " +
                "copyM1StrictMs=${"%.3f".format(copyM1StrictMs)} copyM1LiveMs=${"%.3f".format(copyM1LiveMs)} " +
                "copyM2StrictMs=${"%.3f".format(copyM2StrictMs)} copyM2LiveMs=${"%.3f".format(copyM2LiveMs)} " +
                "cmdStrictMs=${"%.3f".format(cmdStrictMs)} cmdLiveMs=${"%.3f".format(cmdLiveMs)} " +
                "morton3MiBNativeMs=${"%.3f".format(mortonNativeMs)}",
        )
    }

    @Test
    fun continuousFastpathMatchesStrictFramebufferRegardlessOfNativeAvailability() {
        val bytes = 64 * 64 * 4
        val src = ByteArray(bytes) { (it * 13 + 7).toByte() }
        val fillStrictOut = ByteArray(bytes)
        val fillLiveOut = ByteArray(bytes)
        val copyStrictOut = ByteArray(bytes)
        val copyLiveOut = ByteArray(bytes)
        fun memFor(fillDst: ByteArray, copyDst: ByteArray) = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) {
                val arr = when (address) {
                    0x1000_0000L -> src
                    0x2000_0000L -> fillDst
                    else -> copyDst
                }
                destination.put(arr, 0, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(if (address == 0x2000_0000L) fillDst else copyDst, 0, source.remaining())
            }
        }
        val fillCfg = mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 2, "surfaceBase" to 0x2000_0000L, "surfaceBytes" to bytes)
        val fillWords = longArrayOf(0x2000_0000L, 0x2000_0000L + bytes, 0xdeadbeefL)
        val rFillStrict = GraphicsFillExecution(fillCfg, continuous = false).execute(memFor(fillStrictOut, copyStrictOut), fillWords, GraphicsFillExecution.CONTROL)
        val rFillLive = GraphicsFillExecution(fillCfg, continuous = true).execute(memFor(fillLiveOut, copyLiveOut), fillWords, GraphicsFillExecution.CONTROL)
        assertContentEquals(fillStrictOut, fillLiveOut)
        assertEquals(rFillStrict["bytes"], rFillLive["bytes"])
        assertEquals(rFillStrict["firstBytes"], rFillLive["firstBytes"])
        assertEquals(rFillStrict["lastBytes"], rFillLive["lastBytes"])

        val copyCfg = mapOf(
            "profile" to GraphicsCopyExecution.PROFILE,
            "sourceBase" to 0x1000_0000L,
            "destinationBase" to 0x1030_0000L,
            "surfaceBytes" to 1024 * 768 * 4,
            "submissionLimit" to 2,
            GraphicsMode2Workspace.SETTING to mapOf("base" to 0x1000_0000L, "bytes" to 0x80_0000L),
            GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES,
        )
        val m2Words = longArrayOf(0x1000_0000L, 0x1030_0000L, (64L shl 16) or 64L, (64L shl 16) or 64L, 2, 1, 0, 0, 0)
        val rCopyStrict = GraphicsCopyExecution(copyCfg, continuous = false).execute(memFor(fillStrictOut, copyStrictOut), m2Words)
        val rCopyLive = GraphicsCopyExecution(copyCfg, continuous = true).execute(memFor(fillLiveOut, copyLiveOut), m2Words)
        assertContentEquals(copyStrictOut, copyLiveOut)
        assertEquals(rCopyStrict["bytes"], rCopyLive["bytes"])
        assertEquals(rCopyStrict["firstBytes"], rCopyLive["firstBytes"])
        assertEquals(rCopyStrict["lastBytes"], rCopyLive["lastBytes"])
    }

    @Test
    fun resolvesReleaseNativeRenderLibrariesFromHomeAndSiblingPaths(@TempDir dir: Path) {
        val linuxBundle = dir.resolve("linux-release")
        val linuxNative = Files.createDirectories(linuxBundle.resolve("runtime/native"))
        val linuxRender = Files.writeString(linuxNative.resolve("libfuji_render.so"), "elf")
        val linuxDynarmic = Files.writeString(linuxNative.resolve("libfuji_dynarmic.so"), "elf")

        val winBundle = dir.resolve("win-release")
        val winNative = Files.createDirectories(winBundle.resolve("runtime/native"))
        val winRender = Files.writeString(winNative.resolve("fuji_render.dll"), "pe")
        val winAudio = Files.writeString(winNative.resolve("fuji_audio.dll"), "pe")

        // 1. Explicit runtimeHome resolution (Linux & Windows)
        assertEquals(
            linuxRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                runtimeHome = linuxBundle,
                isWindowsOverride = false,
                envProvider = { null },
                propProvider = { null },
            ),
        )
        assertEquals(
            winRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                runtimeHome = winBundle,
                isWindowsOverride = true,
                envProvider = { null },
                propProvider = { null },
            ),
        )

        // 2. fuji.home property (jpackage & win_launcher.c)
        assertEquals(
            linuxRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                isWindowsOverride = false,
                envProvider = { null },
                propProvider = { if (it == "fuji.home") linuxBundle.toString() else null },
            ),
        )

        // 3. Sibling discovery next to libfuji_dynarmic.so / fuji_audio.dll
        assertEquals(
            linuxRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                isWindowsOverride = false,
                envProvider = { if (it == "FUJI_DYNARMIC_LIBRARY") linuxDynarmic.toString() else null },
                propProvider = { null },
            ),
        )
        assertEquals(
            winRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                isWindowsOverride = true,
                envProvider = { if (it == "FUJI_WIN64_AUDIO_DLL") winAudio.toString() else null },
                propProvider = { null },
            ),
        )

        // 4. Explicit FUJI_WIN64_RENDER_DLL override on Windows
        assertEquals(
            winRender.toAbsolutePath().normalize(),
            NativeRenderBridge.resolveLibraryPath(
                isWindowsOverride = true,
                envProvider = { if (it == "FUJI_WIN64_RENDER_DLL") winRender.toString() else null },
                propProvider = { null },
            ),
        )
    }

    @Test
    fun simdRenderV2LinearGradientAndFused3PlaneColorMatrixMatchKotlinBitForBit() {
        val requireNative = System.getenv("FUJI_REQUIRE_RENDER_NATIVE") == "1"
        if (!requireNative) {
            assumeTrue(
                NativeRenderBridge.isAvailable,
                "Native render bridge not present in this environment (${NativeRenderBridge.lastLoadError})",
            )
        }
        assertTrue(NativeRenderBridge.isAvailable)

        // A. LinearGradientRect (Q-Menu 16 tiles: vertical m3==0 AND diagonal m3!=0, opaque + translucent srcOver/replace)
        val rampOpaque = IntArray(128) { i ->
            val shade = 0xdc - ((0xdc - 0xc1) * i) / 127
            (0xff shl 24) or (shade shl 16) or ((shade - 10) shl 8) or (shade - 20)
        }
        val rampTrans = IntArray(128) { i ->
            val a = if (i < 16) 0 else if (i > 112) 255 else (i * 2)
            (a shl 24) or ((i * 2) shl 16) or ((255 - i) shl 8) or ((i * 3) and 0xff)
        }
        for ((m3, m4, m5, ramp, srcOver) in listOf(
            listOf(0.0, 1.0 / 108.0, -560.0 / 108.0, rampOpaque, true),
            listOf(0.0, 1.0 / 108.0, -560.0 / 108.0, rampTrans, true),
            listOf(0.0, 1.0 / 108.0, -560.0 / 108.0, rampTrans, false),
            listOf(0.0042, -0.0031, 0.25, rampOpaque, true),
            listOf(0.0042, -0.0031, 0.25, rampTrans, true),
            listOf(0.0042, -0.0031, 0.25, rampTrans, false),
        )) {
            val fNat = ByteArray(1024 * 768 * 4) { (it * 19 + 7).toByte() }
            val fKot = fNat.copyOf()
            val rect = SolidCommandRects.LinearGradientRect(
                left = 259, top = 100, right = 505, bottom = 208,
                m3 = m3 as Double, m4 = m4 as Double, m5 = m5 as Double,
                rampLut = ramp as IntArray, srcOver = srcOver as Boolean,
                canvasWidth = 1024, canvasHeight = 768,
            )
            rect.paint(fNat, allowNative = true)
            rect.paint(fKot, allowNative = false)
            assertContentEquals(fKot, fNat, "LinearGradientRect mismatch m3=$m3 srcOver=$srcOver")
        }

        // Q-Menu 16-tile LinearGradientRect A/B micro-benchmark (diagonal + vertical)
        val benchGradFrame = ByteArray(1024 * 768 * 4)
        val qTiles = List(16) { idx ->
            val col = idx % 4
            val row = idx / 4
            SolidCommandRects.LinearGradientRect(
                left = 24 + col * 244, top = 64 + row * 160,
                right = 24 + col * 244 + 236, bottom = 64 + row * 160 + 148,
                m3 = if ((idx and 1) == 0) 0.0 else 0.0035,
                m4 = 1.0 / 148.0,
                m5 = -0.25,
                rampLut = rampOpaque,
                srcOver = true,
                canvasWidth = 1024,
                canvasHeight = 768,
            )
        }
        repeat(3) {
            qTiles.forEach { it.paint(benchGradFrame, allowNative = false) }
            qTiles.forEach { it.paint(benchGradFrame, allowNative = true) }
        }
        fun medMs(block: () -> Unit): Double = List(15) {
            val s = System.nanoTime(); block(); System.nanoTime() - s
        }.sorted()[7] / 1e6
        val gradKotMs = medMs { qTiles.forEach { it.paint(benchGradFrame, allowNative = false) } }
        val gradNatMs = medMs { qTiles.forEach { it.paint(benchGradFrame, allowNative = true) } }
        println(
            "SIMD_V2_GRADIENT16_AB backend=${NativeRenderBridge.backendName} " +
                "kotlinMs=${"%.3f".format(gradKotMs)} nativeMs=${"%.3f".format(gradNatMs)} " +
                "speedup=${"%.2fx".format(gradKotMs / gradNatMs)}",
        )

        // B. Fused 3-plane (PIC0 + PIC1 + OSD0) + non-black BGCOLOR + Q10 Color/Brightness Matrix parity & A/B benchmark
        val diagMat = intArrayOf(1465, 0, 0, 0, 1465, 0, 0, 0, 1465) // LCD BRIGHTNESS +5 (~1.43x)
        val fullMat = intArrayOf(1190, -110, 32, -55, 1130, -45, 22, -135, 1210) // LCD COLOR +5 3x3 Q10
        for ((canvasW, canvasH, outW, outH) in listOf(
            listOf(1024, 768, 1024, 768),
            listOf(1280, 960, 1280, 960),
            listOf(1024, 768, 640, 480),
        )) {
            val osdStride = canvasW * 4
            val osdBuf = ByteBuffer.allocate(osdStride * canvasH).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    val a = when {
                        x < 48 -> 0xff
                        x < 96 -> 0x00
                        else -> ((x * 17 + y * 11) and 0xff)
                    }
                    osdBuf.putInt((y * canvasW + x) * 4, (((x * 3) and 0xff) shl 24) or (((y * 5) and 0xff) shl 16) or (((x xor y) and 0xff) shl 8) or a)
                }
            }
            val p0W = 672; val p0H = 504; val p0Stride = 2560
            val p0Bytes = ByteArray(p0H * p0Stride)
            for (y in 0 until p0H) for (x in 0 until p0W step 2) {
                val i = y * p0Stride + x * 2
                if (x == 0) { p0Bytes[i] = 0; p0Bytes[i + 1] = 0; p0Bytes[i + 2] = 0; p0Bytes[i + 3] = 0 }
                else { p0Bytes[i] = ((x + 64) and 0xff).toByte(); p0Bytes[i + 1] = ((y + 40) and 0xff).toByte(); p0Bytes[i + 2] = ((192 - x) and 0xff).toByte(); p0Bytes[i + 3] = ((y + 50) and 0xff).toByte() }
            }
            val p1W = 256; val p1H = 192; val p1Stride = 2560
            val p1Bytes = ByteArray(p1H * p1Stride)
            for (y in 0 until p1H) for (x in 0 until p1W step 2) {
                val i = y * p1Stride + x * 2
                p1Bytes[i] = 0x60; p1Bytes[i + 1] = ((x + y) and 0xff).toByte(); p1Bytes[i + 2] = 0xa8.toByte(); p1Bytes[i + 3] = ((x * 2 + y) and 0xff).toByte()
            }

            val ctx = LcdcFunctionalTest.Context()
            ctx.entries["display.main"] = ArgbFrameBuffer(outW, outH)
            val scanout = LcdcFirstScanout(ctx, 1, LcdcFirstScanout.PIC_YUV_OSD0, continuous = true)
            scanout.bind()

            for ((backdrop, mat, mode, p1Alpha) in listOf(
                listOf(LcdcFirstScanout.BLANK_BLACK_ARGB, null, 0, 255),
                listOf(0xff212320.toInt(), diagMat, 1, 180),
                listOf(0xffffffff.toInt(), fullMat, 2, 255),
            )) {
                val outNat = IntArray(outW * outH)
                val outKot = IntArray(outW * outH)
                scanout.compositePlanesAndMatrix(
                    osdPayload = osdBuf, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                    pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                    pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                    pic1Alpha = p1Alpha as Int, opaqueOsdBackdrop = false, backdropArgb = backdrop as Int,
                    colorMatrixQ10 = mat as IntArray?, matrixMode = mode as Int, output = outNat, allowNative = true,
                )
                scanout.compositePlanesAndMatrix(
                    osdPayload = osdBuf, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                    pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                    pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                    pic1Alpha = p1Alpha, opaqueOsdBackdrop = false, backdropArgb = backdrop,
                    colorMatrixQ10 = mat, matrixMode = mode, output = outKot, allowNative = false,
                )
                assertContentEquals(outKot, outNat, "3-plane+matrix mismatch ${canvasW}x$canvasH->${outW}x$outH mode=$mode alpha=$p1Alpha")
            }

            if (canvasW == 1280 && outW == 1280) {
                val outBuf = IntArray(outW * outH)
                repeat(4) {
                    scanout.compositePlanesAndMatrix(osdBuf, osdStride, canvasW, canvasH, p0Bytes, 56, 132, p0W, p0H, p0Stride, p1Bytes, 700, 288, p1W, p1H, p1Stride, 255, false, 0xff212320.toInt(), fullMat, 2, outBuf, allowNative = false)
                    scanout.compositePlanesAndMatrix(osdBuf, osdStride, canvasW, canvasH, p0Bytes, 56, 132, p0W, p0H, p0Stride, p1Bytes, 700, 288, p1W, p1H, p1Stride, 255, false, 0xff212320.toInt(), fullMat, 2, outBuf, allowNative = true)
                }
                val kotMs = medMs {
                    scanout.compositePlanesAndMatrix(osdBuf, osdStride, canvasW, canvasH, p0Bytes, 56, 132, p0W, p0H, p0Stride, p1Bytes, 700, 288, p1W, p1H, p1Stride, 255, false, 0xff212320.toInt(), fullMat, 2, outBuf, allowNative = false)
                }
                val natMs = medMs {
                    scanout.compositePlanesAndMatrix(osdBuf, osdStride, canvasW, canvasH, p0Bytes, 56, 132, p0W, p0H, p0Stride, p1Bytes, 700, 288, p1W, p1H, p1Stride, 255, false, 0xff212320.toInt(), fullMat, 2, outBuf, allowNative = true)
                }
                println(
                    "SIMD_V2_3PLANE_MATRIX_EVF_AB backend=${NativeRenderBridge.backendName} " +
                        "kotlinMs=${"%.3f".format(kotMs)} nativeMs=${"%.3f".format(natMs)} " +
                        "speedup=${"%.2fx".format(kotMs / natMs)}",
                )
            }
        }
    }

    @Test fun compositeLcdcToArgbPreMatchesLegacyAndBenchmarksLcdAndEvf() {
        val requireNative = System.getenv("FUJI_REQUIRE_RENDER_NATIVE") == "1"
        if (!requireNative) {
            assumeTrue(
                NativeRenderBridge.isAvailable && NativeRenderBridge.hasDirectArgbPreComposite,
                "Native render bridge composite_lcdc_to_argb_pre not present in this environment",
            )
        }
        assertTrue(NativeRenderBridge.isAvailable, "Expected NativeRenderBridge to load libfuji_render")
        assertTrue(NativeRenderBridge.hasDirectArgbPreComposite, "Expected fuji_render_composite_lcdc_to_argb_pre symbol")

        fun argbToPreRef(argb: Int): Int {
            val a = argb ushr 24
            if (a == 255) return argb
            if (a == 0) return 0
            val r = div255Rounded(((argb ushr 16) and 0xff) * a)
            val g = div255Rounded(((argb ushr 8) and 0xff) * a)
            val b = div255Rounded((argb and 0xff) * a)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val diagMat = intArrayOf(1465, 0, 0, 0, 1465, 0, 0, 0, 1465)
        val fullMat = intArrayOf(1190, -110, 32, -55, 1130, -45, 22, -135, 1210)

        for ((canvasW, canvasH, outW, outH) in listOf(
            listOf(1024, 768, 1024, 768),
            listOf(1280, 960, 1280, 960),
            listOf(1600, 1200, 1600, 1200),
            listOf(1024, 768, 640, 480),
        )) {
            val osdStride = canvasW * 4
            val osdBuf = ByteBuffer.allocate(osdStride * canvasH).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    val a = when {
                        y == 1 -> 0x00 // full-transparent OSD row
                        x < 48 -> 0xff
                        x < 96 -> 0x00
                        else -> ((x * 17 + y * 11) and 0xff)
                    }
                    osdBuf.putInt((y * canvasW + x) * 4, (((x * 3) and 0xff) shl 24) or (((y * 5) and 0xff) shl 16) or (((x xor y) and 0xff) shl 8) or a)
                }
            }
            val p0W = 672; val p0H = 504; val p0Stride = 2560
            val p0Bytes = ByteArray(p0H * p0Stride)
            for (y in 0 until p0H) for (x in 0 until p0W step 2) {
                val i = y * p0Stride + x * 2
                p0Bytes[i] = ((x + 64) and 0xff).toByte()
                p0Bytes[i + 1] = ((y + 40) and 0xff).toByte()
                p0Bytes[i + 2] = ((192 - x) and 0xff).toByte()
                p0Bytes[i + 3] = ((y + 50) and 0xff).toByte()
            }
            val p1W = 256; val p1H = 192; val p1Stride = 2560
            val p1Bytes = ByteArray(p1H * p1Stride)
            for (y in 0 until p1H) for (x in 0 until p1W step 2) {
                val i = y * p1Stride + x * 2
                p1Bytes[i] = 0x60; p1Bytes[i + 1] = ((x + y) and 0xff).toByte()
                p1Bytes[i + 2] = 0xa8.toByte(); p1Bytes[i + 3] = ((x * 2 + y) and 0xff).toByte()
            }

            val ctx = LcdcFunctionalTest.Context()
            val port = ArgbFrameBuffer(outW, outH)
            ctx.entries["display.main"] = port
            val scanout = LcdcFirstScanout(ctx, 1, LcdcFirstScanout.PIC_YUV_OSD0, continuous = true)
            scanout.bind()

            val pixelCount = outW * outH
            val directBytes = ByteBuffer.allocateDirect(pixelCount * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            val directIntBuf = directBytes.asIntBuffer()
            val directSeg = MemorySegment.ofBuffer(directBytes)

            data class PreCase(
                val backdrop: Int,
                val mat: IntArray?,
                val mode: Int,
                val p1Alpha: Int,
                val useOsd: Boolean,
                val expectOpaque: Boolean,
            )
            for ((backdrop, mat, mode, p1Alpha, useOsd, expectOpaque) in listOf(
                PreCase(LcdcFirstScanout.BLANK_BLACK_ARGB, null, 0, 255, true, true),
                PreCase(0xff212320.toInt(), diagMat, 1, 180, true, true),
                PreCase(0xffffffff.toInt(), fullMat, 2, 255, true, true),
                PreCase(0x804080c0.toInt(), null, 0, 180, true, false),
                PreCase(0x40204080, null, 0, 0, false, false),
            )) {
                val activeOsd = if (useOsd) osdBuf else null
                val refArgb = IntArray(pixelCount)
                scanout.compositePlanesAndMatrix(
                    osdPayload = activeOsd, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                    pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                    pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                    pic1Alpha = p1Alpha, opaqueOsdBackdrop = false, backdropArgb = backdrop,
                    colorMatrixQ10 = mat, matrixMode = mode, output = refArgb, allowNative = true,
                )
                val refPre = IntArray(pixelCount) { i -> argbToPreRef(refArgb[i]) }

                val gotOpaqueDirect = scanout.compositePlanesToArgbPre(
                    osdPayload = activeOsd, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                    pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                    pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                    pic1Alpha = p1Alpha, opaqueOsdBackdrop = false, backdropArgb = backdrop,
                    colorMatrixQ10 = mat, matrixMode = mode, outputSegment = directSeg, allowNative = true,
                )
                assertEquals(expectOpaque, gotOpaqueDirect, "Opaque flag mismatch ${canvasW}x$canvasH->${outW}x$outH mode=$mode")
                val gotDirectArr = IntArray(pixelCount)
                directIntBuf.get(0, gotDirectArr)
                assertContentEquals(refPre, gotDirectArr, "Direct MemorySegment ARGB_PRE mismatch ${canvasW}x$canvasH->${outW}x$outH mode=$mode")

                val gotHeapArr = IntArray(pixelCount)
                val gotOpaqueHeap = scanout.compositePlanesToArgbPre(
                    osdPayload = activeOsd, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                    pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                    pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                    pic1Alpha = p1Alpha, opaqueOsdBackdrop = false, backdropArgb = backdrop,
                    colorMatrixQ10 = mat, matrixMode = mode, output = gotHeapArr, allowNative = true,
                )
                assertEquals(expectOpaque, gotOpaqueHeap)
                assertContentEquals(refPre, gotHeapArr, "Heap ARGB_PRE mismatch ${canvasW}x$canvasH->${outW}x$outH mode=$mode")

                if (backdrop == 0x40204080) {
                    val gotKotArr = IntArray(pixelCount)
                    val gotOpaqueKot = scanout.compositePlanesToArgbPre(
                        osdPayload = activeOsd, osdStrideBytes = osdStride, canvasWidth = canvasW, canvasHeight = canvasH,
                        pic0Bytes = p0Bytes, pic0X = 56, pic0Y = 132, pic0Width = p0W, pic0Height = p0H, pic0StrideBytes = p0Stride,
                        pic1Bytes = p1Bytes, pic1X = 700, pic1Y = 288, pic1Width = p1W, pic1Height = p1H, pic1StrideBytes = p1Stride,
                        pic1Alpha = p1Alpha, opaqueOsdBackdrop = false, backdropArgb = backdrop,
                        colorMatrixQ10 = mat, matrixMode = mode, output = gotKotArr, allowNative = false,
                    )
                    assertFalse(gotOpaqueKot)
                    assertContentEquals(refPre, gotKotArr, "Kotlin fallback ARGB_PRE mismatch")
                }
            }

            // A/B benchmark at 1024x768 LCD and 1600x1200 EVF:
            // Legacy multi-copy pipeline (composite -> IntArray -> DisplayPort -> bridge IntArray -> direct PixelBuffer)
            // vs Direct single-pass pipeline (composite_lcdc_to_argb_pre -> direct PixelBuffer MemorySegment).
            if ((canvasW == 1024 && outW == 1024) || (canvasW == 1600 && outW == 1600)) {
                val legacyScanoutBuf = IntArray(pixelCount)
                val legacyBridgeBuf = IntArray(pixelCount)
                val threadMx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                fun allocBytes(): Long = threadMx?.currentThreadAllocatedBytes ?: -1L

                fun runLegacyFrame() {
                    scanout.compositePlanesAndMatrix(
                        osdBuf, osdStride, canvasW, canvasH,
                        p0Bytes, 56, 132, p0W, p0H, p0Stride,
                        p1Bytes, 700, 288, p1W, p1H, p1Stride,
                        255, false, 0xff212320.toInt(), fullMat, 2, legacyScanoutBuf, allowNative = true,
                    )
                    port.presentArgb(legacyScanoutBuf, outW, outH)
                    port.copyArgb(legacyBridgeBuf)
                    directIntBuf.put(0, legacyBridgeBuf, 0, pixelCount)
                }

                fun runDirectFrame() {
                    scanout.compositePlanesToArgbPre(
                        osdBuf, osdStride, canvasW, canvasH,
                        p0Bytes, 56, 132, p0W, p0H, p0Stride,
                        p1Bytes, 700, 288, p1W, p1H, p1Stride,
                        255, false, 0xff212320.toInt(), fullMat, 2, directSeg, allowNative = true,
                    )
                }

                repeat(30) {
                    runLegacyFrame()
                    runDirectFrame()
                }
                val legacyTimes = LongArray(15)
                val directTimes = LongArray(15)
                val legacyAllocBefore = allocBytes()
                for (i in 0 until 15) {
                    val s = System.nanoTime()
                    runLegacyFrame()
                    legacyTimes[i] = System.nanoTime() - s
                }
                val legacyAllocPerFrame = if (legacyAllocBefore >= 0) (allocBytes() - legacyAllocBefore) / 15 else -1L

                val directAllocBefore = allocBytes()
                for (i in 0 until 15) {
                    val s = System.nanoTime()
                    runDirectFrame()
                    directTimes[i] = System.nanoTime() - s
                }
                val directAllocPerFrame = if (directAllocBefore >= 0) (allocBytes() - directAllocBefore) / 15 else -1L

                val legacyMedMs = legacyTimes.sorted()[7] / 1e6
                val directMedMs = directTimes.sorted()[7] / 1e6
                println(
                    "W11_DIRECT_PIXELBUFFER_AB res=${outW}x$outH backend=${NativeRenderBridge.backendName} " +
                        "legacyMs=${"%.3f".format(legacyMedMs)} directMs=${"%.3f".format(directMedMs)} " +
                        "speedup=${"%.2fx".format(legacyMedMs / directMedMs)} " +
                        "legacyAllocBytes=$legacyAllocPerFrame directAllocBytes=$directAllocPerFrame",
                )
                assertTrue(
                    directAllocPerFrame <= 1024L,
                    "Expected zero framebuffer-scale heap allocation on direct path, got $directAllocPerFrame bytes/frame",
                )
            }
        }
    }
}
