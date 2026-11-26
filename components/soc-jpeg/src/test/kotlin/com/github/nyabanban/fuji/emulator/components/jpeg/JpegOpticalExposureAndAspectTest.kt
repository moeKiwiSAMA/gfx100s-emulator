package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for BUG-V3-2-3:
 * - `JpegUyvyCodec.encodeFromOpticalSensor` uses live exposure parameters (no solid-white blowout)
 *   and applies aspect-preserving center crop matching output dimensions (no anamorphic stretch).
 * - `SocJpegFactory` resolves `Imx461ExposurePort` + `soc-isp.bank-68000` WB gain and emits an
 *   explicit `[FALLBACK][SocJpegFactory]` WARNING when `Imx461ExposurePort` is unavailable.
 */
class JpegOpticalExposureAndAspectTest {
    private fun makeSquareTargetBayer16(srcW: Int = 1024, srcH: Int = 576, boxSize: Int = 200, code12: Int = 2200): ByteArray {
        val raw = ByteArray(srcW * srcH * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val cx = srcW / 2
        val cy = srcH / 2
        val half = boxSize / 2
        val bg = (200 shl 4).toShort()
        val fg = (code12 shl 4).toShort()
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val inside = x in (cx - half) until (cx + half) && y in (cy - half) until (cy + half)
                bb.putShort(if (inside) fg else bg)
            }
        }
        return raw
    }

    @Test
    fun opticalStillUsesSynchronousExposureAndProgrammedChannelCurvesExactlyOnce() {
        val width = 8
        val height = 6
        val adcCode = 1024
        var stillReads = 0
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("Live frame read during still capture")
            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int {
                stillReads++
                destination.order(ByteOrder.LITTLE_ENDIAN)
                repeat(width * height) { destination.putShort((adcCode shl 4).toShort()) }
                return width * height * 2
            }
        }
        fun sample(curves: IspToneCurves?): List<Int> {
            val result = JpegUyvyCodec.exposeAndDemosaicOpticalSensor(
                0, sensor, null, fallbackWidthHint = width, fallbackHeightHint = height, toneCurves = curves,
            )
            assertTrue(result is JpegUyvyCodec.OpticalExposeResult.Success)
            return result.frame.srcBgr.take(3).map { it.toInt() and 255 }
        }
        // 12-bit linear 1024/4095 maps to sRGB 137 in the unprogrammed path.
        assertEquals(listOf(137, 137, 137), sample(null))
        fun curve(v: Int) = IntArray(4096) { i -> if (i == adcCode) v else 0 }
        assertEquals(listOf(53, 101, 177), sample(IspToneCurves(curve(177), curve(101), curve(53), 1)))
        assertEquals(2, stillReads)
    }

    @Test
    fun encodeFromOpticalSensorPreservesSquareAspectRatioAcross4x3And1x1And65x24() {
        val raw = makeSquareTargetBayer16(1024, 576, boxSize = 200, code12 = 2400)
        var lastExposure: Exposure? = null
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                lastExposure = exposure
                destination.put(raw)
                return raw.size
            }
        }

        val cases = listOf(
            Triple("4:3", 640, 480),
            Triple("1:1", 480, 480),
            Triple("65:24", 650, 240),
        )
        for ((label, dstW, dstH) in cases) {
            val res = JpegUyvyCodec.encodeFromOpticalSensor(
                tick = 1234L,
                sensor = sensor,
                lens = null,
                width = dstW,
                height = dstH,
                qscale = 12,
                maxBytes = 0L,
                durationNanos = 3200L,
                iso = 125,
                gainR1024 = 1024,
                gainG1024 = 1024,
                gainB1024 = 1024,
            )
            assertTrue(res is JpegUyvyCodec.EncodeResult.Success, "[$label] Expected Success, got $res")
            assertEquals(3200L, lastExposure?.durationNanos, "[$label] Must forward live durationNanos")
            assertEquals(125, lastExposure?.iso, "[$label] Must forward live ISO")

            val img = ImageIO.read(ByteArrayInputStream(res.jpegBytes))
            assertEquals(dstW, img.width)
            assertEquals(dstH, img.height)

            var minX = dstW; var maxX = -1; var minY = dstH; var maxY = -1
            for (y in 0 until dstH) {
                for (x in 0 until dstW) {
                    val rgb = img.getRGB(x, y)
                    val g = (rgb ushr 8) and 0xFF
                    if (g > 150) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val ar = bw.toDouble() / bh.toDouble()
            assertTrue(ar in 0.94..1.06, "[$label] Expected square target AR ~1.0 (got ${bw}x$bh, ar=$ar)")
        }
    }

    @Test
    fun highAxiEncoderUsesImx461ExposurePortAndLogsFallbackWarningWhenMissing() {
        val raw = makeSquareTargetBayer16(1024, 576, boxSize = 160, code12 = 1800)
        val recordedExposures = mutableListOf<Exposure>()
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                recordedExposures += exposure
                destination.put(raw)
                return raw.size
            }
        }
        val dmaBuf = ByteArray(256 * 1024)
        val dstAddr = 0x8900_0000L
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address == dstAddr && size <= dmaBuf.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(dmaBuf, 0, source.remaining())
            }
        }
        val axiHigh = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = if (offset == 0x40L) 1L else 0L
            override fun write(offset: Long, size: Int, value: Long) = Unit
        }
        var expPort: Imx461ExposurePort? = object : Imx461ExposurePort {
            override val hasExposureRegs: Boolean = true
            override val shr15: Int = 1800
            override val svr12: Int = 0
            override val vmax12: Int = 2000
            override val pgc12: Int = 200
            override val pgcSecondary12: Int = 0
            override val hcgEnabled: Boolean = false
            override val dgain8: Int = 0
        }

        val entries = LinkedHashMap<String, Any>()
        entries["cpu.memory"] = dma
        entries["cmos"] = sensor
        entries["soc-isp.axi-high34"] = axiHigh
        entries["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) = Unit
        }
        var tick = 100L
        val tempDir = Files.createTempDirectory("jpeg-opt-test-")
        tempDir.toFile().deleteOnExit()
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock = EmulationClock { tick }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    entries[name] = port
                }
                override fun removeOwner(owner: String) = Unit
                override fun <T : Any> find(name: String, type: Class<T>): T? {
                    if (name == Imx461ExposurePort.PORT_ID) return expPort?.let(type::cast)
                    val v = entries[name] ?: return null
                    return if (type.isInstance(v)) type.cast(v) else null
                }
            }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = emptyMap()
        }
        val comp = SocJpegFactory().create(ctx, mapOf("profile" to SocJpegFactory.CODEC_PROFILE, "completionDelayTicks" to 4L))
        comp.start()
        val enc = entries.getValue(SocJpegFactory.ENCODER_PORT_ID) as TickAwareMmioDevice

        // 1. With Imx461ExposurePort present: forwards computed live exposure
        enc.write(0x044L, 4, (240L shl 16) or 320L)
        enc.write(0x100L, 4, 0x1000_0000L) // high-AXI (srcHi=1 -> 0x1_1000_0000)
        enc.write(0x10CL, 4, dstAddr)
        enc.write(0x110L, 4, dmaBuf.size.toLong())
        enc.write(0x130L, 4, 640L)
        enc.write(0x000L, 4, 1L)
        tick += 4L
        enc.beforeInstruction(tick)
        assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
        val liveExp = recordedExposures.single()
        val expected = Imx461ExposurePort.computeEffectiveExposure(expPort)
        assertEquals(expected.durationNanos, liveExp.durationNanos)
        assertEquals(expected.iso, liveExp.iso)

        // 2. Without Imx461ExposurePort: falls back with explicit [FALLBACK][SocJpegFactory] warning
        val logger = Logger.getLogger(SocJpegFactory::class.java.name)
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            expPort = null
            enc.write(0x024L, 4, 0xFFFF_FFFFL)
            enc.write(0x000L, 4, 1L)
            tick += 4L
            enc.beforeInstruction(tick)
            assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
            assertTrue(
                records.any { it.message.contains("[FALLBACK][SocJpegFactory]") },
                "Expected [FALLBACK][SocJpegFactory] warning when exposure port missing, got ${records.map { it.message }}",
            )
        } finally {
            logger.removeHandler(handler)
            comp.close()
        }
    }

    @Test
    fun encodeFromOpticalSensorAppliesFilmSimCcmChromaAcrosMonochromeAndSepia() {
        val srcW = 1024
        val srcH = 576
        val raw = ByteArray(srcW * srcH * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        // Vivid red/blue RGGB Bayer pattern (R=2600, G=1200, B=900)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val code12 = when {
                    (y and 1) == 0 && (x and 1) == 0 -> 2600
                    (y and 1) == 1 && (x and 1) == 1 -> 900
                    else -> 1200
                }
                bb.putShort((code12 shl 4).toShort())
            }
        }
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        fun stats(bytes: ByteArray): Triple<Double, Double, Double> {
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            var chroma = 0.0; var rSum = 0.0; var bSum = 0.0; var n = 0
            for (y in 64 until img.height - 64 step 8) {
                for (x in 64 until img.width - 64 step 8) {
                    val rgb = img.getRGB(x, y)
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    chroma += (maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))).toDouble()
                    rSum += r; bSum += b; n++
                }
            }
            return Triple(chroma / n, rSum / n, bSum / n)
        }

        val provia = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 1L, sensor = sensor, lens = null, width = 320, height = 240, qscale = 8, maxBytes = 0L,
            chromaMatQ10 = intArrayOf(1109, -31, 111, 1454),
        ) as JpegUyvyCodec.EncodeResult.Success
        val velvia = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 2L, sensor = sensor, lens = null, width = 320, height = 240, qscale = 8, maxBytes = 0L,
            chromaMatQ10 = intArrayOf(1277, -119, 246, 2044),
        ) as JpegUyvyCodec.EncodeResult.Success
        val acros = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 3L, sensor = sensor, lens = null, width = 320, height = 240, qscale = 8, maxBytes = 0L,
            monochromeMode = true,
        ) as JpegUyvyCodec.EncodeResult.Success
        val sepia = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 4L, sensor = sensor, lens = null, width = 320, height = 240, qscale = 8, maxBytes = 0L,
            monoToneDeltaCb10 = -52, monoToneDeltaCr10 = 51,
        ) as JpegUyvyCodec.EncodeResult.Success

        val (cProvia, _, _) = stats(provia.jpegBytes)
        val (cVelvia, _, _) = stats(velvia.jpegBytes)
        val (cAcros, _, _) = stats(acros.jpegBytes)
        val (cSepia, rSepia, bSepia) = stats(sepia.jpegBytes)
        assertTrue(cProvia > 20.0, "PROVIA must preserve vivid color chroma (got $cProvia)")
        assertTrue(cVelvia > cProvia * 1.15, "Velvia must boost chroma above PROVIA ($cProvia -> $cVelvia)")
        assertTrue(cAcros < 1.5, "ACROS neutral monochrome must have near-zero chroma (got $cAcros)")
        assertTrue(cSepia > 8.0 && rSepia > bSepia + 10.0, "SEPIA must render warm tint (chroma=$cSepia R=$rSepia B=$bSepia)")
    }

    @Test
    fun encodeFromOpticalSensor102MpStreamingScaledJpegSucceedsQuicklyWithValidDimensions() {
        val srcW = 1024
        val srcH = 576
        val raw = makeSquareTargetBayer16(srcW, srcH, boxSize = 180, code12 = 2200)
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                destination.put(raw)
                return raw.size
            }
        }
        val t0 = System.nanoTime()
        val res = JpegUyvyCodec.encodeFromOpticalSensor(
            tick = 500L,
            sensor = sensor,
            lens = null,
            width = 11648,
            height = 8736,
            qscale = 8,
            maxBytes = 0L,
            durationNanos = 2500L,
            iso = 160,
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue(res is JpegUyvyCodec.EncodeResult.Success, "Expected 102MP Success, got $res")
        assertTrue(elapsedMs < 2500.0, "102MP optical encode took too long: $elapsedMs ms")

        val jpeg = res.jpegBytes
        assertTrue(jpeg.size > 10_000, "102MP JPEG unexpectedly small: ${jpeg.size}")
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        assertEquals(0xFF, jpeg[jpeg.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, jpeg[jpeg.size - 1].toInt() and 0xFF)

        val img = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(11648, img.width)
        assertEquals(8736, img.height)
    }

    @Test
    fun opticalCacheReusesSensorExposureForThumbnailWithinTtlAndDowngradesWarning() {
        val raw = makeSquareTargetBayer16(1024, 576, boxSize = 160, code12 = 1900)
        var exposeCalls = 0
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeCalls++
                destination.put(raw)
                return raw.size
            }
        }
        val dmaBuf = ByteArray(16 * 1024 * 1024)
        val thumbSrcAddr = 0xBFEF_E000L
        val thumbSrcBytes = ByteArray(160 * 120 * 2)
        val dstAddr = 0x8900_0000L
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require((!write && address == thumbSrcAddr && size <= thumbSrcBytes.size) ||
                    (write && address == dstAddr && size <= dmaBuf.size))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(thumbSrcBytes, 0, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(dmaBuf, 0, source.remaining())
            }
        }
        val axiHigh = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = if (offset == 0x40L) 1L else 0L
            override fun write(offset: Long, size: Int, value: Long) = Unit
        }
        val expPort = object : Imx461ExposurePort {
            override val hasExposureRegs: Boolean = true
            override val shr15: Int = 1800
            override val svr12: Int = 0
            override val vmax12: Int = 2000
            override val pgc12: Int = 200
            override val pgcSecondary12: Int = 0
            override val hcgEnabled: Boolean = false
            override val dgain8: Int = 0
        }
        val entries = LinkedHashMap<String, Any>()
        entries["cpu.memory"] = dma
        entries["cmos"] = sensor
        entries["soc-isp.axi-high34"] = axiHigh
        entries[Imx461ExposurePort.PORT_ID] = expPort
        entries["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) = Unit
        }
        var tick = 1000L
        val tempDir = Files.createTempDirectory("jpeg-cache-test-")
        tempDir.toFile().deleteOnExit()
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock = EmulationClock { tick }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    entries[name] = port
                }
                override fun removeOwner(owner: String) = Unit
                override fun <T : Any> find(name: String, type: Class<T>): T? {
                    val v = entries[name] ?: return null
                    return if (type.isInstance(v)) type.cast(v) else null
                }
            }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = emptyMap()
        }
        val logger = Logger.getLogger(SocJpegFactory::class.java.name)
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        val comp = SocJpegFactory().create(ctx, mapOf("profile" to SocJpegFactory.CODEC_PROFILE, "completionDelayTicks" to 4L))
        comp.start()
        try {
            val enc = entries.getValue(SocJpegFactory.ENCODER_PORT_ID) as TickAwareMmioDevice

            // 1. Main optical encode via high-AXI (srcHi=1)
            enc.write(0x044L, 4, (480L shl 16) or 640L)
            enc.write(0x100L, 4, 0x1000_0000L)
            enc.write(0x10CL, 4, dstAddr)
            enc.write(0x110L, 4, dmaBuf.size.toLong())
            enc.write(0x130L, 4, 1280L)
            enc.write(0x000L, 4, 1L)
            tick += 4L
            enc.beforeInstruction(tick)
            assertEquals(1, exposeCalls)

            // 2. Subsequent 160x120 EXIF thumbnail from all-zero 0xbfefe000 within 1.5 vSec reuses cached BGR and logs no WARNING
            entries.remove("soc-isp.axi-high34")
            enc.write(0x024L, 4, 0xFFFF_FFFFL)
            enc.write(0x044L, 4, (120L shl 16) or 160L)
            enc.write(0x100L, 4, thumbSrcAddr)
            enc.write(0x10CL, 4, dstAddr)
            enc.write(0x110L, 4, dmaBuf.size.toLong())
            enc.write(0x130L, 4, 320L)
            enc.write(0x000L, 4, 1L)
            tick += 1000L
            enc.beforeInstruction(tick)
            assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
            assertEquals(1, exposeCalls, "160x120 EXIF thumbnail within 1.5 vSec must reuse cached optical BGR without re-exposing")
            assertTrue(
                records.none { it.level.intValue() >= java.util.logging.Level.WARNING.intValue() && it.message.contains("all-zero DRAM") },
                "160x120 all-zero DRAM fallback must be downgraded from WARNING to FINE",
            )
        } finally {
            logger.removeHandler(handler)
            comp.close()
        }
    }
}
