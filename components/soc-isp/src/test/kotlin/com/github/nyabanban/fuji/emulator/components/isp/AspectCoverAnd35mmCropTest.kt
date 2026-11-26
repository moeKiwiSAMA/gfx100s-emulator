package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for BUG-V3-2-2 & BUG-V3-2-5:
 * 1. Aspect-preserving center-crop ("cover") framing eliminates 96px/120px top/bottom black bars on
 *    4:3 LCD (1024x768) & EVF (1280x960) while keeping 1.000 square-pixel AR and 0-bit Native/JVM parity.
 * 2. 35mm FORMAT MODE (`fracW = 0.8126`, `1.23x`) genuinely magnifies the active subject by ~1.23x
 *    relative to 35mm OFF (`fracW = 1.0`) in both LiveView DMA and STILL optical UYVY DMA.
 * 3. `IspStatsSramEngine` AF ROI aligns pixel-for-pixel with the 4:3 active sensor viewport.
 */
class AspectCoverAnd35mmCropTest {
    private fun makeSquareSceneRaw16(
        srcW: Int = 1024,
        srcH: Int = 576,
        boxSize: Int = 200,
        bg12: Int = 400,
        fg12: Int = 2800,
    ): ByteArray {
        val raw = ByteArray(srcW * srcH * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val cx = srcW / 2
        val cy = srcH / 2
        val half = boxSize / 2
        val bg = (bg12 shl 4).toShort()
        val fg = (fg12 shl 4).toShort()
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val inside = x in (cx - half) until (cx + half) && y in (cy - half) until (cy + half)
                bb.putShort(if (inside) fg else bg)
            }
        }
        return raw
    }

    private fun sizeWord(w: Int, h: Int): Long = ((h - 1).toLong() shl 16) or (w - 1).toLong()

    private class ArrayMem(private val base: Long, val buf: ByteArray) : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && (address - base) + size <= buf.size.toLong())
        }
        override fun read(address: Long, destination: ByteBuffer) = error("unused")
        override fun write(address: Long, source: ByteBuffer) {
            source.get(buf, (address - base).toInt(), source.remaining())
        }
    }

    private fun measureBrightBoxUyvy(buf: ByteArray, w: Int, h: Int, stride: Int, lumaThresh: Int = 170): Pair<Int, Int> {
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            val rowOff = y * stride
            for (x in 0 until w) {
                val yOff = rowOff + (x and 1.inv()) * 2 + (if ((x and 1) == 0) 1 else 3)
                val luma = buf[yOff].toInt() and 0xFF
                if (luma >= lumaThresh) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        return (maxX - minX + 1) to (maxY - minY + 1)
    }

    @Test
    fun asymmetricMarkersStayAlignedAcrossLcdEvfMovieAnd35mmCrop() {
        val raw = ByteBuffer.allocate(1024 * 576 * 2).order(ByteOrder.LITTLE_ENDIAN)
        val markers = listOf(232 to 78, 781 to 94, 253 to 490, 769 to 478, 503 to 277)
        for (y in 0 until 576) for (x in 0 until 1024) {
            val bright = markers.any { (mx, my) -> x in mx - 5..mx + 5 && y in my - 5..my + 5 }
            raw.putShort(if (bright) 0xfff0.toShort() else 0)
        }
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw.array())
                return raw.capacity()
            }
        }
        val engine = LiveViewDmaEngine()
        val base = 0x3d99_a000L
        for ((width, height) in listOf(1024 to 768, 1280 to 960, 1920 to 1080)) {
            for (crop in listOf(1.0, 0.8126)) {
                val out = ByteArray(width * height * 2)
                assertTrue(engine.pumpLiveViewDma(100, base, width * 2L, sizeWord(width, height),
                    LiveViewDmaEngine.ENABLE_BIT_28, sensor, ArrayMem(base, out), null,
                    cropZoom = LiveViewDmaEngine.CropZoom(fracW = crop)))
                val cropW = kotlin.math.round(minOf(1024.0, 576.0 * width / height) * crop).toInt() and -2
                val cropH = kotlin.math.round(cropW.toDouble() * height / width).toInt() and -2
                for ((sx, sy) in markers) {
                    val cx = (sx - (1024 - cropW) / 2.0) * width / cropW
                    val cy = (sy - (576 - cropH) / 2.0) * height / cropH
                    var count = 0
                    var sumX = 0L
                    var sumY = 0L
                    for (y in cy.toInt() - 27..cy.toInt() + 27) {
                        for (x in cx.toInt() - 27..cx.toInt() + 27) {
                            if (x !in 0 until width || y !in 0 until height) continue
                            if ((out[(y * width + x) * 2 + 1].toInt() and 255) > 190) {
                                count++
                                sumX += x
                                sumY += y
                            }
                        }
                    }
                    val context = "${width}x$height crop=$crop sensor=($sx,$sy)"
                    assertTrue(count > 12, "Missing marker $context")
                    val dx = sumX.toDouble() / count - cx
                    val dy = sumY.toDouble() / count - cy
                    assertTrue(kotlin.math.abs(dx) <= 3.0 && kotlin.math.abs(dy) <= 3.0,
                        "Marker centroid offset ($dx,$dy) for $context")
                }
            }
        }
    }

    @Test
    fun coverFramingFills4x3LcdAndEvfWithoutLetterboxBarsAndMatches35mmZoom() {
        val raw = makeSquareSceneRaw16(1024, 576, boxSize = 200)
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        val base = 0x3d99_a000L

        // 1. 4:3 LCD 1024x768 (35mm OFF): top row (y=0) and bottom row (y=767) must carry non-black scene luma
        val lcdNative = ByteArray(1024 * 768 * 2)
        val lcdJvm = ByteArray(1024 * 768 * 2)
        val engNative = LiveViewDmaEngine().apply { allowNativeSimd = true }
        val engJvm = LiveViewDmaEngine().apply { allowNativeSimd = false }
        assertTrue(
            engNative.pumpLiveViewDma(
                tick = 100L, addr = base, strideLong = 2048L, sizeWord = sizeWord(1024, 768),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28, sensor = sensor, memory = ArrayMem(base, lcdNative), lens = null,
            ),
        )
        assertTrue(
            engJvm.pumpLiveViewDma(
                tick = 100L, addr = base, strideLong = 2048L, sizeWord = sizeWord(1024, 768),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28, sensor = sensor, memory = ArrayMem(base, lcdJvm), lens = null,
            ),
        )
        assertContentEquals(lcdJvm, lcdNative, "Native SIMD and JVM cover framing must be bit-exact")

        val topRowY = lcdNative[1 * 2048 + 512 * 2 + 1].toInt() and 0xFF
        val botRowY = lcdNative[766 * 2048 + 512 * 2 + 1].toInt() and 0xFF
        assertTrue(topRowY > 40, "BUG-V3-2-2: 4:3 LCD top row must NOT be black letterbox (got Y=$topRowY)")
        assertTrue(botRowY > 40, "BUG-V3-2-2: 4:3 LCD bottom row must NOT be black letterbox (got Y=$botRowY)")

        val (offW, offH) = measureBrightBoxUyvy(lcdNative, 1024, 768, 2048)
        val offAr = offW.toDouble() / offH.toDouble()
        assertTrue(offAr in 0.96..1.04, "Expected square target AR ~1.0 on 4:3 LCD (got ${offW}x$offH, ar=$offAr)")

        // 2. 35mm FORMAT MODE = ON (real-silicon LV1 hStepQ12=0x1088 -> fracW = 0.8126 -> 1.23x zoom)
        val lcd35mm = ByteArray(1024 * 768 * 2)
        assertTrue(
            engNative.pumpLiveViewDma(
                tick = 200L, addr = base, strideLong = 2048L, sizeWord = sizeWord(1024, 768),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28, sensor = sensor, memory = ArrayMem(base, lcd35mm), lens = null,
                cropZoom = LiveViewDmaEngine.CropZoom(fracW = 0.8126),
            ),
        )
        val (onW, onH) = measureBrightBoxUyvy(lcd35mm, 1024, 768, 2048)
        val zoomRatioW = onW.toDouble() / offW.toDouble()
        val zoomRatioH = onH.toDouble() / offH.toDouble()
        assertTrue(
            zoomRatioW in 1.18..1.28 && zoomRatioH in 1.18..1.28,
            "BUG-V3-2-5: 35mm ON must zoom ~1.23x vs 35mm OFF (off=${offW}x$offH, on=${onW}x$onH, ratio=${zoomRatioW}x${zoomRatioH})",
        )

        // 3. STILL optical UYVY DMA (`renderOpticalUyvyToDma`) with 35mm cropFactor=1.23 also zooms ~1.23x with AR ~1.0
        val stillOff = ByteArray(640 * 480 * 2)
        val stillOn = ByteArray(640 * 480 * 2)
        assertTrue(engNative.renderOpticalUyvyToDma(300L, base, 640, 480, 1280, sensor, ArrayMem(base, stillOff), null, cropFactor = 1.0))
        assertTrue(engNative.renderOpticalUyvyToDma(301L, base, 640, 480, 1280, sensor, ArrayMem(base, stillOn), null, cropFactor = 1.2306))
        val (sOffW, sOffH) = measureBrightBoxUyvy(stillOff, 640, 480, 1280)
        val (sOnW, sOnH) = measureBrightBoxUyvy(stillOn, 640, 480, 1280)
        assertTrue(sOffW.toDouble() / sOffH.toDouble() in 0.95..1.05, "STILL 4:3 AR must be square (${sOffW}x$sOffH)")
        assertTrue(sOnW.toDouble() / sOffW.toDouble() in 1.18..1.28, "STILL 35mm cropFactor=1.23 must zoom ~1.23x ($sOffW -> $sOnW)")
    }

    @Test
    fun afStatsViewportAlignsWith4x3CoverCroppedSensorWindow() {
        // 1024x576 sensor with high-contrast vertical stripes ONLY at 4:3 display x=25% (sensor x = 128 + 192 = 320), y=50% (y=288).
        val srcW = 1024
        val srcH = 576
        val raw = ByteArray(srcW * srcH * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val inTarget = x in 288 until 352 && y in 256 until 320
                val code12 = if (inTarget && ((x ushr 1) and 1) == 0) 3200 else 400
                bb.putShort((code12 shl 4).toShort())
            }
        }
        val stats = IspStatsSramEngine()
        // Program 1x1 AF ROI centered at 25% X, 50% Y of a 1024x768 hardware grid (x0=200, w=112 -> [200..312], y0=320, h=128)
        val reg1fc = ((768L - 1L) shl 16) or (1024L - 1L)
        val reg124Hit = (200L shl 16) or (0L shl 12) or (112L - 1L)
        val reg128Hit = (320L shl 16) or (0L shl 12) or (128L - 1L)
        stats.updateAeFromBayer(
            raw = raw, srcW = srcW, srcH = srcH, is16Bit = true,
            reg5c = 0L, reg60 = 0L, reg124 = reg124Hit, reg128 = reg128Hit, reg1fc = reg1fc,
            viewX = 128, viewY = 0, viewW = 768, viewH = 576,
        )
        val contrastHit = stats.readHead(IspStatsSramEngine.SEL4_OFF, 4)

        // Program 1x1 AF ROI centered at 75% X, 50% Y (flat background inside 4:3 viewport -> sensor x = 128 + 576 = 704)
        val reg124Miss = (712L shl 16) or (0L shl 12) or (112L - 1L)
        stats.updateAeFromBayer(
            raw = raw, srcW = srcW, srcH = srcH, is16Bit = true,
            reg5c = 0L, reg60 = 0L, reg124 = reg124Miss, reg128 = reg128Hit, reg1fc = reg1fc,
            viewX = 128, viewY = 0, viewW = 768, viewH = 576,
        )
        val contrastMiss = stats.readHead(IspStatsSramEngine.SEL4_OFF, 4)

        assertTrue(
            contrastHit > 10_000L && contrastMiss == 0L,
            "BUG-V3-2-2: 4:3-aligned AF ROI at x=25% must hit sensor x=320 (hit=$contrastHit, miss=$contrastMiss)",
        )
    }

    @Test
    fun stillCaptureUsesFusedVulkanUyvyPathWithSrcCropAndFallsBackToCpuCleanly() {
        val base = 0x1000_0000L
        val eng = LiveViewDmaEngine()

        var capturedReq: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest? = null
        var exposeStillCalls = 0
        var allowFused = true
        val fusedSensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeStillCalls++
                val n = 1024 * 576 * 2
                for (i in 0 until 1024 * 576) {
                    destination.putShort((1200 shl 4).toShort())
                }
                return n
            }

            override fun tryExposeStillUyvy(
                request: com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest,
                outUyvy: ByteArray,
            ): Boolean {
                if (!allowFused) return false
                capturedReq = request
                // Fill valid bright UYVY pattern (U=0x80, Y0=0xCC, V=0x80, Y1=0xCC)
                var i = 0
                while (i + 3 < outUyvy.size) {
                    outUyvy[i] = 0x80.toByte()
                    outUyvy[i + 1] = 0xCC.toByte()
                    outUyvy[i + 2] = 0x80.toByte()
                    outUyvy[i + 3] = 0xCC.toByte()
                    i += 4
                }
                return true
            }
        }

        // 1. Direct GPU fused still capture (640x480 4:3, cropFactor = 1.0 -> source crop [128, 0, 768, 576])
        val dst1 = ByteArray(640 * 480 * 2)
        assertTrue(eng.renderOpticalUyvyToDma(100L, base, 640, 480, 1280, fusedSensor, ArrayMem(base, dst1), null, cropFactor = 1.0))
        assertEquals(1L, eng.stillFusedFrames)
        assertEquals(0, exposeStillCalls, "Fused Vulkan still path must skip CPU exposeStill Bayer readback")
        val r1 = requireNotNull(capturedReq)
        assertTrue((r1.flags and com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_SRC_CROP) != 0)
        assertEquals(128, r1.offX)
        assertEquals(0, r1.offY)
        assertEquals(768, r1.fitW)
        assertEquals(576, r1.fitH)
        assertEquals(640, r1.dstWidth)
        assertEquals(480, r1.dstHeight)
        assertEquals(0xCC, dst1[1].toInt() and 0xFF)

        // 2. Direct GPU fused still capture with 35mm cropFactor = 1.2306 -> zoomed center crop (~624x468)
        val dst2 = ByteArray(640 * 480 * 2)
        assertTrue(eng.renderOpticalUyvyToDma(101L, base, 640, 480, 1280, fusedSensor, ArrayMem(base, dst2), null, cropFactor = 1.2306))
        assertEquals(2L, eng.stillFusedFrames)
        assertEquals(0, exposeStillCalls)
        val r2 = requireNotNull(capturedReq)
        assertTrue(r2.fitW in 620..628 && r2.fitH in 464..472, "Expected 35mm 1.23x source crop, got ${r2.fitW}x${r2.fitH}")
        assertTrue(r2.offX > 180 && r2.offY > 40, "35mm crop must stay centered (off=${r2.offX},${r2.offY})")

        // 3. Graceful fallback to CPU exposeStill + demosaic when Vulkan fusion returns false
        allowFused = false
        val dst3 = ByteArray(640 * 480 * 2)
        assertTrue(eng.renderOpticalUyvyToDma(102L, base, 640, 480, 1280, fusedSensor, ArrayMem(base, dst3), null, cropFactor = 1.0))
        assertEquals(2L, eng.stillFusedFrames)
        assertEquals(1, exposeStillCalls, "CPU fallback must run exposeStill cleanly when GPU fusion is unavailable")
    }
}
