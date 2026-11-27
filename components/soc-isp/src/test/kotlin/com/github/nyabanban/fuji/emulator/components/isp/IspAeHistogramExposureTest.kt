package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EdgeInterruptState
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.IspInterruptPort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for BUG-A3-2 real-silicon 56 KiB ISP Stats SRAM (AE grids sel 0..3 / sel 6..9 /
 * sel 10 + 256-bin LiveView Histogram sel 33..36) and IMX461 + ISP digital gain preview exposure
 * modulation in [IspStatsSramEngine], [LiveViewDmaEngine], and [SocIspFactory].
 */
class IspAeHistogramExposureTest {
    @Test
    fun darkReferenceSelectorsTrackWindowGeometryAndSensorPedestal() {
        val stats = IspStatsSramEngine()
        fun readChannel(window: Int, channel: Int): Long {
            val offset = IspStatsSramEngine.SEL16_OFF - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF +
                window * IspStatsSramEngine.BLACK_REFERENCE_WINDOW_BYTES + channel * Long.SIZE_BYTES
            return stats.readResultSurfaceWord(offset) or (stats.readResultSurfaceWord(offset + 4) shl 32)
        }
        fun expect(window: Int, sum: Long) {
            for (channel in 0 until 4) assertEquals(sum, readChannel(window, channel))
        }

        stats.updateBlackReferenceWindow(0, 0x000A0003, 0x000B0509, 256)
        stats.updateBlackReferenceWindow(1, 0x00050003, 0x00210F1D, 256)
        stats.updateBlackReferenceWindow(2, 0x00010000, 0x00010001, 256)
        expect(0, 1_290L * 16)
        expect(1, 3_870L * 16)
        expect(2, 0L)
        stats.updateBlackReferenceWindow(0, 0x000A0003, 0x000B0509, 64)
        expect(0, 1_290L * 4)
        expect(1, 3_870L * 16)
        stats.updateBlackReferenceWindow(2, 0xFFFF_FFFF, 0xFFFF_FFFF, 65535)
        expect(2, (64L * 4_096L / 4L) * 4_095L)
        stats.clear()
        expect(0, 0L)
        expect(2, 0L)
        stats.updateBlackReferenceWindow(0, 0x000A0003, 0x000B0509, 64)
        expect(0, 1_290L * 4)
        stats.updateBlackReferenceWindow(0, 0x000A0003, 0x000B0509, 0)
        expect(0, 0L)
    }

    private fun uniformBayer16(width: Int, height: Int, code12: Int): ByteArray {
        val sample16 = (code12.coerceIn(0, 4095) shl 4) and 0xFFFF
        val bytes = ByteArray(width * height * 2)
        val lo = (sample16 and 0xFF).toByte()
        val hi = ((sample16 ushr 8) and 0xFF).toByte()
        var i = 0
        while (i < bytes.size) {
            bytes[i] = lo
            bytes[i + 1] = hi
            i += 2
        }
        return bytes
    }

    @Test
    fun statsSramEnginePopulatesAeGridsSel0To3Sel6To9Sel10And256BinHistogram() {
        val engine = IspStatsSramEngine()
        // 16x16 grid with 64x36 hardware cells (64*36/4 = 576 pixels per Bayer channel per cell)
        val reg5c = (15L shl 12) or 63L
        val reg60 = (15L shl 12) or 35L
        val raw = uniformBayer16(64, 32, code12 = 1024) // 10-bit AE code = (1024 << 4) >>> 6 = 256
        val ob = 1024
        engine.updateAeFromBayer(raw, 64, 32, is16Bit = true, reg5c = reg5c, reg60 = reg60, obR = ob, obG1 = ob, obB = ob, obG2 = ob)

        assertEquals(1L, engine.aeFramesUpdated)
        val expectedCellSum = 576L * (256L + ob)
        // Verify sel 6..9 primary grid ([R, G1, B, G2]) via resultSurface offset (base 0x1060)
        assertEquals(expectedCellSum, engine.readResultSurfaceWord(0x0000)) // sel 6 cell 0 (R)
        assertEquals(expectedCellSum, engine.readResultSurfaceWord(0x0400)) // sel 7 cell 0 (G1)
        assertEquals(expectedCellSum, engine.readResultSurfaceWord(0x0800)) // sel 8 cell 0 (B)
        assertEquals(expectedCellSum, engine.readResultSurfaceWord(0x0C00)) // sel 9 cell 0 (G2)
        // Verify sel 0..3 secondary grid via stats head (base 0x0000)
        assertEquals(expectedCellSum, engine.readHead(0x0000, 4))
        assertEquals(expectedCellSum, engine.readHead(0x0400, 4))
        assertEquals(expectedCellSum, engine.readHead(0x0800, 4))
        assertEquals(expectedCellSum, engine.readHead(0x0C00, 4))
        // Verify sel 10 window 0 full-frame u64 accumulator (at resultSurface + 0x1000)
        val win0RLo = engine.readResultSurfaceWord(0x1000)
        val win0RHi = engine.readResultSurfaceWord(0x1004)
        val win0R = win0RLo or (win0RHi shl 32)
        assertEquals(expectedCellSum * 256L, win0R)

        // Verify sel 33..36 256-bin histogram (Y, R, G, B)
        engine.beginHistogram()
        repeat(100) { engine.addHistogramPixel(y = 128, r = 200, g = 120, b = 40) }
        repeat(50) { engine.addHistogramPixel(y = 64, r = 80, g = 60, b = 20) }
        engine.commitHistogram()

        assertEquals(1L, engine.histFramesUpdated)
        assertEquals(150L, engine.lastHistPixels)
        assertEquals(100L, engine.readHistogram(128 * 4, 4)) // sel 33 Y bin 128
        assertEquals(50L, engine.readHistogram(64 * 4, 4)) // sel 33 Y bin 64
        assertEquals(100L, engine.readHistogram(0x400 + 200 * 4, 4)) // sel 34 R bin 200
        assertEquals(100L, engine.readHistogram(0x800 + 120 * 4, 4)) // sel 35 G bin 120
        assertEquals(100L, engine.readHistogram(0xC00 + 40 * 4, 4)) // sel 36 B bin 40
    }

    @Test
    fun liveViewDmaModulatesBrightnessWithExposureAndDigitalGainAndInvalidatesCache() {
        val engine = LiveViewDmaEngine()
        val stats = IspStatsSramEngine()
        // Linear exposure-proportional sensor (mirrors LinearRgbFrameSupport)
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                val gain = (exposure.durationNanos / 1000.0) * (exposure.iso / 100.0)
                val code12 = (512.0 * gain).roundToInt().coerceIn(0, 4095)
                val sample16 = (code12 shl 4).toShort()
                val total = 64 * 32
                repeat(total) { destination.putShort(sample16) }
                return total * 2
            }
        }
        val dst = ByteArray(64 * 32 * 2)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(dst, 0, source.remaining())
            }
        }
        val sizeWord = ((32L - 1L) shl 16) or (64L - 1L)
        val ctrl = LiveViewDmaEngine.ENABLE_BIT_28
        val statsCfg = LiveViewDmaEngine.StatsConfig(stats, reg5c = (15L shl 12) or 3L, reg60 = (15L shl 12) or 1L)

        // 1. Nominal exposure (1000ns, ISO 100, unity gain)
        assertTrue(engine.pumpLiveViewDma(100L, 0x4000_0000L, 128L, sizeWord, ctrl, sensor, mem, null, exposure = LiveViewDmaEngine.ExposureParams(1000L, 100), statsConfig = statsCfg))
        val lumaNominal = dst[16 * 128 + 32 * 2 + 1].toInt() and 0xFF
        val aeNominal = stats.lastMeanAeCode
        assertTrue(lumaNominal in 60..220, "Nominal luma should be mid-range, got $lumaNominal")
        assertTrue(stats.lastHistPixels == 64L * 32L, "Histogram must record all output pixels")

        // 2. Shorter shutter (250ns -> -2 EV): darker YUV & lower AE code
        assertTrue(engine.pumpLiveViewDma(200L, 0x4000_0000L, 128L, sizeWord, ctrl, sensor, mem, null, exposure = LiveViewDmaEngine.ExposureParams(250L, 100), statsConfig = statsCfg))
        val lumaDark = dst[16 * 128 + 32 * 2 + 1].toInt() and 0xFF
        val aeDark = stats.lastMeanAeCode
        assertTrue(lumaDark < lumaNominal - 15, "Shorter shutter must darken LiveView: dark=$lumaDark nominal=$lumaNominal")
        assertTrue(aeDark < aeNominal, "Shorter shutter must reduce AE mean code: dark=$aeDark nominal=$aeNominal")

        // 3. Digital gain boost (2.0x = 2048/1024) at same raw exposure must invalidate YUV cache & brighten output
        val prevMisses = engine.cacheMisses
        assertTrue(engine.pumpLiveViewDma(300L, 0x4000_0000L, 128L, sizeWord, ctrl, sensor, mem, null, exposure = LiveViewDmaEngine.ExposureParams(250L, 100, 2048, 2048, 2048), statsConfig = statsCfg))
        val lumaBoosted = dst[16 * 128 + 32 * 2 + 1].toInt() and 0xFF
        assertTrue(engine.cacheMisses > prevMisses, "Digital gain change must invalidate YUV cache")
        assertTrue(lumaBoosted > lumaDark + 15, "2x digital gain must brighten LiveView: boosted=$lumaBoosted dark=$lumaDark")
    }

    @Test
    fun socIspFactoryServesLiveStatsSramAcrossBank0AndBank1FromImx461Exposure(@TempDir tempDir: Path) {
        val claims = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        var nowTick = 10_000L
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock = EmulationClock { nowTick }
            override fun readData(relative: String): Map<String, Any?> = emptyMap()
            override val ports: Ports = ports
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, object : CoupledEdgeInterruptSink {
            override fun lineEnabled(irq: Int) = irq == SocIspFactory.IRQ_106
            override fun edgeState(irq: Int) = EdgeInterruptState(edge = true, high = false, pending = false)
            override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) = latchDevice()
            override fun setLine(irq: Int, high: Boolean) = Unit
        })
        var lastDuration = 0L
        var lastIso = 0
        ports.register("cmos", "cmos", SensorPort::class.java, object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                lastDuration = exposure.durationNanos
                lastIso = exposure.iso
                val gain = (exposure.durationNanos / 1000.0) * (exposure.iso / 100.0)
                val code12 = (600.0 * gain).roundToInt().coerceIn(0, 4095)
                val s = (code12 shl 4).toShort()
                repeat(64 * 32) { destination.putShort(s) }
                return 64 * 32 * 2
            }
        })
        val yuvBuf = ByteArray(64 * 32 * 2)
        ports.register("cpu", "cpu.memory", DmaMemory::class.java, object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) {
                check(address == 0x01871ad0L)
                destination.putInt(0x011b7f14)
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(yuvBuf, 0, source.remaining())
            }
        })
        var mockShr = 100
        var mockPgc = 2066
        ports.register("soc-isp-cmd", "soc-isp-cmd.imx461.exposure", Imx461ExposurePort::class.java, object : Imx461ExposurePort {
            override val hasExposureRegs: Boolean = true
            override val shr15: Int get() = mockShr
            override val svr12: Int = 0
            override val pgc12: Int get() = mockPgc
            override val pgcSecondary12: Int = 0
            override val hcgEnabled: Boolean = false
            override val vmax12: Int = 2000
            override val dgain8: Int = 0
        })

        val factory = SocIspFactory()
        val comp = factory.create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V3,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "autoFrameIrqs" to false,
                "slvsEcSilicon" to false,
            ),
        )
        comp.start()

        val irq = checkNotNull(ports.find(SocIspFactory.PORT_INTERRUPT_CONTROL, IspInterruptPort::class.java))
        val core = ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java)!!
        val lv0 = ports.find(SocIspFactory.PORT_LIVE_VIEW_0, MmioDevice::class.java)!!
        val surf0 = ports.find(SocIspFactory.PORT_RESULT_SURFACE_V3, MmioDevice::class.java)!!
        val surf1 = ports.find(SocIspFactory.PORT_RESULT_SURFACE_1_V3, MmioDevice::class.java)!!
        val stats0 = ports.find(SocIspFactory.PORT_STATS_0, MmioDevice::class.java)!!
        val stats1 = ports.find(SocIspFactory.PORT_STATS_1, MmioDevice::class.java)!!
        val head0 = ports.find(SocIspFactory.PORT_STATS_HEAD_0_V3, MmioDevice::class.java)!!

        fun selectDestination(base: Long) {
            core.write(0x22c, 4, base)
            core.write(0x254, 4, base + 0x1060)
            core.write(0x2b4, 4, base + 0x6000)
        }

        fun completeFrame() {
            irq.signalInterrupt(SocIspFactory.IRQ_106, withStatus = false, statusMask = 1)
            core.write(0x00c, 4, 1)
        }

        // Configure 16x16 AE grid and pump LiveView Ch0
        selectDestination(0xfff10000L)
        core.write(0x05cL, 4, (15L shl 12) or 15L)
        core.write(0x060L, 4, (15L shl 12) or 15L)
        lv0.write(0x0c4L, 4, 0x4000_0000L)
        lv0.write(0x0d0L, 4, 128L)
        lv0.write(0x018L, 4, ((32L - 1L) shl 16) or (64L - 1L))
        lv0.write(0x0d4L, 4, LiveViewDmaEngine.ENABLE_BIT_28)

        assertTrue(lastDuration in 900L..1000L, "Expected ~950ns for SHR=100/VMAX=2000, got $lastDuration")
        assertEquals(100, lastIso)
        assertEquals(0L, surf0.read(0, 4))
        completeFrame()
        val aeBright = surf0.read(0x0000L, 4)
        assertTrue(aeBright > 0L, "Bank 0 sel 6 AE grid must be populated")
        assertEquals(0L, surf1.read(0x0000L, 4), "The driver has only targeted bank 0")
        assertEquals(aeBright, head0.read(0x0000L, 4), "Bank 0 sel 0 head must mirror AE grid")

        var histSum0 = 0L
        var histSum1 = 0L
        for (bin in 0 until 256) {
            histSum0 += stats0.read((bin * 4).toLong(), 4)
            histSum1 += stats1.read((bin * 4).toLong(), 4)
        }
        assertEquals(64L * 32L, histSum0, "Bank 0 sel 33 Luma histogram must sum to all frame pixels")
        assertEquals(0L, histSum1)

        // Increase SHR (100 -> 1500 = 1/4x integration time) -> darker AE sum
        mockShr = 1500
        nowTick += 10_000L
        selectDestination(0xfff17000L)
        lv0.write(0x0d4L, 4, LiveViewDmaEngine.ENABLE_BIT_28)
        assertTrue(lastDuration in 200L..300L, "Expected ~250ns for SHR=1500/VMAX=2000, got $lastDuration")
        completeFrame()
        val aeDark = surf1.read(0x0000L, 4)
        assertTrue(aeDark > 0)
        assertTrue(aeDark < aeBright, "Higher IMX461 SHR must reduce AE photometry: dark=$aeDark bright=$aeBright")
        assertEquals(aeBright, surf0.read(0, 4))

        val stateful = comp as StatefulComponent
        val snapshot = stateful.saveSnapshot()
        stateful.restoreSnapshot(snapshot - "statsSramBase64")
        assertEquals(0L, surf0.read(0, 4))
        assertEquals(0L, surf1.read(0, 4))
        stateful.restoreSnapshot(snapshot)
        assertEquals(aeBright, surf0.read(0, 4))
        assertEquals(aeDark, surf1.read(0, 4))

        comp.close()
    }

    @Test
    fun ispColorPipelineAppliesCcmToneLutAndMonochromeYuvMatrixAndInvalidatesCache() {
        val bank = LongArray(IspCoreOffsets.BANK_68000_OFFSETS.size)
        val pipe = IspColorPipeline()
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.hasCcm)
        assertEquals(false, pipe.hasToneLut)
        assertEquals(false, pipe.hasYuvMatrix)

        fun setWord(off: Int, value: Long) {
            bank[IspCoreOffsets.BANK_68000_INDEX.getValue(off)] = value
        }
        fun pack13(a: Int, b: Int): Long = (a.toLong() and 0x1FFFL) or ((b.toLong() and 0x1FFFL) shl 16)

        // Program 0xFFF68300..14 with vivid red-boost CCM (R=1.5R-0.25G-0.25B, G=1.0G, B=1.0B)
        setWord(0x300, pack13(1536, -256))
        setWord(0x304, -256L and 0x1FFFL)
        setWord(0x308, pack13(0, 1024))
        setWord(0x30c, 0L)
        setWord(0x310, pack13(0, 0))
        setWord(0x314, 1024L)

        // Program 0xFFF68408..C4 with high-contrast S-curve 31-knot 14-bit tone LUT
        for (chBase in intArrayOf(0x408, 0x448, 0x488)) {
            for (i in 0 until 15) {
                val k0 = i * 2
                val k1 = k0 + 1
                val v0 = if (k0 < 15) (k0 * 400).coerceAtMost(16383) else (16383 - (30 - k0) * 400).coerceAtLeast(0)
                val v1 = if (k1 < 15) (k1 * 400).coerceAtMost(16383) else (16383 - (30 - k1) * 400).coerceAtLeast(0)
                setWord(chBase + i * 4, (v0.toLong() and 0x3FFFL) or ((v1.toLong() and 0x3FFFL) shl 16))
            }
            setWord(chBase + 15 * 4, 16383L)
        }

        // Program 0xFFF68500..14 with monochrome ACROS RGB->YCbCr matrix (Y=0.30R+0.59G+0.11B, Cb=0, Cr=0)
        setWord(0x500, pack13(306, 601))
        setWord(0x504, 117L)
        setWord(0x508, pack13(0, 0))
        setWord(0x50c, 0L)
        setWord(0x510, pack13(0, 0))
        setWord(0x514, 0L)

        pipe.updateFromBank68000(bank)
        assertTrue(pipe.hasCcm)
        assertTrue(pipe.hasToneLut)
        assertTrue(pipe.hasYuvMatrix)
        assertEquals(10, pipe.yuvShift)
        assertEquals(1536, pipe.ccm[0])
        assertEquals(-256, pipe.ccm[1])
        assertEquals(-256, pipe.ccm[2])

        // Render a vivid red Bayer frame through LiveViewDmaEngine:
        // 1) Default (no pipeline) -> strong Cr chroma (v != 128)
        // 2) Monochrome YUV matrix -> neutral u=128, v=128 & cache invalidated!
        val redSensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                for (y in 0 until 32) {
                    for (x in 0 until 64) {
                        val isR = (y and 1) == 0 && (x and 1) == 0
                        val code12 = if (isR) 3000 else 200
                        destination.putShort((code12 shl 4).toShort())
                    }
                }
                return 64 * 32 * 2
            }
        }
        val dst = ByteArray(64 * 32 * 2)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(dst, 0, source.remaining())
            }
        }
        val dma = LiveViewDmaEngine()
        val sizeWord = ((32L - 1L) shl 16) or (64L - 1L)
        assertTrue(dma.pumpLiveViewDma(100L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null))
        val vColor = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertTrue(vColor > 160, "Expected saturated red Cr > 160 without color pipeline, got $vColor")

        val prevMisses = dma.cacheMisses
        assertTrue(dma.pumpLiveViewDma(200L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        assertTrue(dma.cacheMisses > prevMisses, "Color pipeline change must invalidate YUV cache")
        val uMono = dst[16 * 128 + 32 * 2].toInt() and 0xFF
        val vMono = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertEquals(128, uMono, "Monochrome YUV matrix must zero Cb chroma to 128")
        assertEquals(128, vMono, "Monochrome YUV matrix must zero Cr chroma to 128")

        // 3) Real hardware Monochrome bit (0xFFF68010 bit 4 = 0x11) with neutral 0xCA0=0x0200_0200 must output neutral u=128, v=128
        setWord(0x508, pack13(-173, -339))
        setWord(0x50c, 512L)
        setWord(0x510, pack13(512, -429))
        setWord(0x514, -83L and 0x1FFFL)
        setWord(0x010, 0x11L) // ACROS / MONOCHROME base mode (bit 4 = 0x10)
        setWord(0xc00, 1L)
        setWord(0xca0, 0x0200_0200L) // Neutral WC=0/MG=0
        // Also program 40-knot DR/Tone Curve gain at 0x204..0x250 (0x100 shadow crush -> 0x380 highlight boost)
        for (i in 0 until 20) {
            val g0 = if (i < 10) 0x100 else 0x380
            val g1 = if (i == 19) 0x200 else g0
            setWord(0x204 + i * 4, g0.toLong() or (g1.toLong() shl 16))
        }
        pipe.updateFromBank68000(bank)
        assertTrue(pipe.monochromeMode)
        assertTrue(pipe.hasDrGainCurve)
        assertTrue(dma.pumpLiveViewDma(300L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        assertEquals(128, dst[16 * 128 + 32 * 2].toInt() and 0xFF, "0xFFF68010 bit 4 neutral monochrome must zero Cb to 128")
        assertEquals(128, dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF, "0xFFF68010 bit 4 neutral monochrome must zero Cr to 128")

        // 4) BUG-V3-3-3: Color Film Sims with 0xFFF68010=0x01 (bit 0 set, bit 4 clear) MUST preserve color via 0x534/0x538 2x2 chroma matrix:
        //    - Nostalgic Neg.: 0x010=0x01, 0x534=0x00bf04e7, 0x538=0x1fd50608 (vivid warm color)
        //    - ETERNA BLEACH BYPASS: 0x010=0x01, 0x534=0x000e0226, 0x538=0x00a70271 (desaturated color, >0 chroma)
        setWord(0x010, 0x01L)
        setWord(0xc00, 0L)
        setWord(0xca0, 0L)
        setWord(0x534, 0x00bf_04e7L)
        setWord(0x538, 0x1fd5_0608L)
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.monochromeMode, "Nostalgic Neg. (0x010=0x01) must NOT clamp to monochrome")
        assertTrue(dma.pumpLiveViewDma(400L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        val vNostalgic = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertTrue(vNostalgic > 165, "Nostalgic Neg. must retain vivid red Cr > 165, got $vNostalgic")

        setWord(0x534, 0x000e_0226L)
        setWord(0x538, 0x00a7_0271L)
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.monochromeMode, "ETERNA BLEACH BYPASS (0x010=0x01) must NOT clamp to monochrome")
        assertTrue(dma.pumpLiveViewDma(500L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        val vBleach = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertTrue(vBleach in 135 until vNostalgic, "Bleach Bypass must render authentic desaturated color (135 < $vBleach < $vNostalgic)")

        // 5) BUG-V3-3-3: SEPIA (0x010=0x11, 0xC00=1, 0xCA0=0x01CC_0233) & MONOCHROMATIC COLOR WC/MG render authentic monochrome tint!
        setWord(0x010, 0x11L)
        setWord(0x534, 0L)
        setWord(0x538, 0L)
        setWord(0xc00, 1L)
        setWord(0xca0, 0x01cc_0233L) // DAT SEPIA
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.monochromeMode, "SEPIA toning must not force neutral monochromeMode")
        assertEquals(-52, pipe.monoToneDeltaCb)
        assertEquals(51, pipe.monoToneDeltaCr)
        assertTrue(dma.pumpLiveViewDma(600L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        val uSepia = dst[16 * 128 + 32 * 2].toInt() and 0xFF
        val vSepia = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertTrue(uSepia < 123 && vSepia > 133, "SEPIA must produce warm tint (Cb<123, Cr>133), got Cb=$uSepia Cr=$vSepia")

        // Cool MONOCHROMATIC COLOR (WC-2 -> 0x0208_01FA: +Cb, -Cr)
        setWord(0xca0, 0x0208_01faL)
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.monochromeMode)
        assertEquals(8, pipe.monoToneDeltaCb)
        assertEquals(-6, pipe.monoToneDeltaCr)
        assertTrue(dma.pumpLiveViewDma(700L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        val uCool = dst[16 * 128 + 32 * 2].toInt() and 0xFF
        val vCool = dst[16 * 128 + 32 * 2 + 2].toInt() and 0xFF
        assertTrue(uCool > 128 && vCool < 128, "Cool MONOCHROMATIC COLOR must shift Cb>128, Cr<128, got Cb=$uCool Cr=$vCool")

        // MG-shifted MONOCHROMATIC COLOR (0x03CE_03FF: -50 Cb, -1 Cr)
        setWord(0xca0, 0x03ce_03ffL)
        pipe.updateFromBank68000(bank)
        assertEquals(false, pipe.monochromeMode)
        assertEquals(-50, pipe.monoToneDeltaCb)
        assertEquals(-1, pipe.monoToneDeltaCr)
        assertTrue(dma.pumpLiveViewDma(800L, 0x4000_0000L, 128L, sizeWord, LiveViewDmaEngine.ENABLE_BIT_28, redSensor, mem, null, colorPipeline = pipe))
        val uMg = dst[16 * 128 + 32 * 2].toInt() and 0xFF
        assertTrue(uMg < 123, "MG-shifted MONOCHROMATIC COLOR must tint Cb<123, got Cb=$uMg")
    }

    @Test
    fun cdafMultiBandStatsRespondToSharpVsBlurredAndRespectHardwareRoiAndCrop() {
        val w = 128
        val h = 64
        fun bayerChecker(blur: Boolean): ByteArray {
            val grid = Array(h) { y ->
                IntArray(w) { x ->
                    val on = (((x / 8) + (y / 8)) and 1) == 0
                    if (on) 3500 else 500
                }
            }
            val bytes = ByteArray(w * h * 2)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val code12 = if (!blur) {
                        grid[y][x]
                    } else {
                        var sum = 0
                        var cnt = 0
                        for (dy in -3..3) {
                            val yy = (y + dy).coerceIn(0, h - 1)
                            for (dx in -3..3) {
                                sum += grid[yy][(x + dx).coerceIn(0, w - 1)]
                                cnt++
                            }
                        }
                        sum / cnt
                    }
                    val s16 = (code12 shl 4) and 0xFFFF
                    val off = (y * w + x) shl 1
                    bytes[off] = (s16 and 0xFF).toByte()
                    bytes[off + 1] = ((s16 ushr 8) and 0xFF).toByte()
                }
            }
            return bytes
        }

        // Program 3x3 center AF ROI via 0xFFF70124/128/12C inside 3888x619 CCDIF active crop (0xFFF6E014/18)
        val reg124 = (1727L shl 16) or (2L shl 12) or 143L // x0=1727, cols=3, cellW=144
        val reg128 = (264L shl 16) or (2L shl 12) or 27L // y0=264, rows=3, cellH=28
        val crop6e14 = 3888L
        val crop6e18 = 619L

        val sharpEngine = IspStatsSramEngine()
        sharpEngine.updateAeFromBayer(
            raw = bayerChecker(blur = false),
            srcW = w,
            srcH = h,
            is16Bit = true,
            reg5c = (15L shl 12) or 7L,
            reg60 = (15L shl 12) or 3L,
            reg124 = reg124,
            reg128 = reg128,
            crop6e14 = crop6e14,
            crop6e18 = crop6e18,
        )

        val blurEngine = IspStatsSramEngine()
        blurEngine.updateAeFromBayer(
            raw = bayerChecker(blur = true),
            srcW = w,
            srcH = h,
            is16Bit = true,
            reg5c = (15L shl 12) or 7L,
            reg60 = (15L shl 12) or 3L,
            reg124 = reg124,
            reg128 = reg128,
            crop6e14 = crop6e14,
            crop6e18 = crop6e18,
        )

        val sharpSel4 = sharpEngine.readHead(IspStatsSramEngine.SEL4_OFF, 4)
        val sharpSel5 = sharpEngine.readHead(IspStatsSramEngine.SEL5_OFF, 4)
        val blurSel4 = blurEngine.readHead(IspStatsSramEngine.SEL4_OFF, 4)
        val blurSel5 = blurEngine.readHead(IspStatsSramEngine.SEL5_OFF, 4)
        assertTrue(sharpSel4 > blurSel4 * 2L, "Sharp sel4 Tenengrad must exceed blurred: sharp=$sharpSel4 blur=$blurSel4")
        assertTrue(sharpSel5 > blurSel5 * 2L, "Sharp sel5 Laplacian must exceed blurred: sharp=$sharpSel5 blur=$blurSel5")
        assertTrue(sharpSel4 in 1L..0x7FFF_FFFFL && sharpSel5 in 1L..0x7FFF_FFFFL)

        // Verify sel 32 cell (1,1) band 0 and sel 15 edge sum are active, while out-of-grid cell (3,0) is 0
        val cell11Off = (IspStatsSramEngine.SEL32_OFF + 512 + 32) - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF
        val cell30Off = (IspStatsSramEngine.SEL32_OFF + 3 * 32) - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF
        val sharpBand0 = sharpEngine.readResultSurfaceWord(cell11Off)
        val blurBand0 = blurEngine.readResultSurfaceWord(cell11Off)
        assertTrue(sharpBand0 > blurBand0, "Sharp sel32 center cell must exceed blurred: sharp=$sharpBand0 blur=$blurBand0")
        assertEquals(0L, sharpEngine.readResultSurfaceWord(cell30Off), "Inactive cells outside 3x3 ROI must be zero")

        // Verify 1x1 Single-Point AF mirrors summary window 0 across windows 1..4
        val singleEngine = IspStatsSramEngine()
        singleEngine.updateAeFromBayer(
            raw = bayerChecker(blur = false),
            srcW = w,
            srcH = h,
            is16Bit = true,
            reg5c = (15L shl 12) or 7L,
            reg60 = (15L shl 12) or 3L,
            reg124 = (1727L shl 16) or 431L, // 1x1
            reg128 = (264L shl 16) or 83L,
            crop6e14 = crop6e14,
            crop6e18 = crop6e18,
        )
        val w0 = singleEngine.readHead(IspStatsSramEngine.SEL4_OFF, 4)
        val w4 = singleEngine.readHead(IspStatsSramEngine.SEL4_OFF + 32, 4)
        assertTrue(w0 > 0L)
        assertEquals(w0, w4, "1x1 Single-Point AF must mirror sel 4 summary across all 5 windows")
    }

    @Test
    fun movieModeReg5cReg60NonZeroBits15To12StillPopulatesFull16x16PrimaryAeGrid() {
        val w = 64
        val h = 48
        val raw = ByteArray(w * h * 2)
        // Uniform mid-gray Bayer frame: 10-bit linear code = 168 (16-bit left-aligned = 168 << 6 = 10752)
        val code16 = 168 shl 6
        for (i in 0 until w * h) {
            raw[i * 2] = (code16 and 0xFF).toByte()
            raw[i * 2 + 1] = ((code16 ushr 8) and 0xFF).toByte()
        }
        val engine = IspStatsSramEngine()
        // Live GFX100S 2.13 Movie mode values (MDSEL 0x92): [15:12]=6, cellW=496, cellH=138
        engine.updateAeFromBayer(
            raw = raw,
            srcW = w,
            srcH = h,
            is16Bit = true,
            reg5c = 0x00CF_61EFL,
            reg60 = 0x004E_6089L,
            obR = 0,
            obG1 = 0,
            obB = 0,
            obG2 = 0,
        )
        assertEquals(16, engine.lastGridCols)
        assertEquals(16, engine.lastGridRows)
        val expectedCellSum = 168L * ((496L * 138L) / 4L)
        // Every single cell 0..255 (including cells 49..255 that previously stayed 0!) must be populated
        for (cell in listOf(0, 48, 49, 128, 255)) {
            val rSum = engine.readHead(IspStatsSramEngine.SEL6_R_OFF + cell * 4, 4)
            assertEquals(expectedCellSum, rSum, "Primary AE sel6 cell $cell must be populated in Movie mode")
        }
    }
}
