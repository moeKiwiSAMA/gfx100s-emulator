package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class PdafHardwareEngineTest {
    private class MockInterruptSink : InterruptSink {
        val lines = linkedMapOf<Int, Boolean>()
        override fun setLine(irq: Int, high: Boolean) {
            lines[irq] = high
        }
    }

    private class MockDmaMemory : DmaMemory {
        private val regions = LinkedHashMap<Long, ByteArray>()
        fun map(base: Long, size: Int) {
            regions[base] = ByteArray(size)
        }
        fun bytes(base: Long): ByteArray = regions.getValue(base)

        private fun locate(address: Long, size: Long): Pair<Long, ByteArray> {
            for ((base, arr) in regions) {
                if (address >= base && address + size <= base + arr.size.toLong()) return base to arr
            }
            throw IllegalArgumentException("Unmapped DMA range 0x${address.toString(16)} size=$size")
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            locate(address, size)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            val (base, arr) = locate(address, len.toLong())
            destination.put(arr, (address - base).toInt(), len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            val (base, arr) = locate(address, len.toLong())
            source.get(arr, (address - base).toInt(), len)
        }
    }

    private class TestContext(
        val sink: MockInterruptSink = MockInterruptSink(),
        val dma: MockDmaMemory = MockDmaMemory(),
        var lensParams: LensParameters = LensParameters("GF50mmF3.5", 50.0, 3.5, 5.0, 0.9, 0.0),
        var subjectDepthM: Double = 2.0,
    ) : ComponentContext {
        var tick = 10_000L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override fun readData(relative: String): Map<String, Any?> = emptyMap()
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null) { "Duplicate port $name" }
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)

        init {
            ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, sink)
            ports.register("cpu", "cpu.memory", DmaMemory::class.java, dma)
            ports.register("lens", "lens", LensPort::class.java, object : LensPort {
                override fun parameters(): LensParameters = lensParams
            })
            ports.register("lens", SceneDepthPort.PORT_NAME, SceneDepthPort::class.java, object : SceneDepthPort {
                override fun fallbackSubjectDistanceM(): Double = subjectDepthM
                override fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double = subjectDepthM
            })
        }
    }

    private val factory = SocIspFactory()

    private fun v3Config(): Map<String, Any> = mapOf(
        "profile" to SocIspFactory.PROFILE_V3,
        "initialFill" to 0L,
        "resultSurfaceFile" to "",
        "resultSurfaceSha256" to "",
        "autoFrameIrqs" to false,
    )

    /** Exact 3-point equiangular sub-pixel minimum matching GFX100S 2.13 S5 0x0254650C..0x02547180. */
    private fun equiangularMinLag(buf: ByteArray, blockIdx: Int, band: Int, maxShift: Int): Double {
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val blockBase = blockIdx * 0x800
        val centerWord = 92 + 164 * band
        var bestLag = 0
        var bestVal = Long.MAX_VALUE
        for (lag in -maxShift..maxShift) {
            val v = bb.getInt(blockBase + (centerWord + lag) * 4).toLong() and 0xFFFF_FFFFL
            if (v < bestVal) {
                bestVal = v
                bestLag = lag
            }
        }
        val left = bb.getInt(blockBase + (centerWord + bestLag - 1) * 4).toLong() and 0xFFFF_FFFFL
        val center = bestVal
        val right = bb.getInt(blockBase + (centerWord + bestLag + 1) * 4).toLong() and 0xFFFF_FFFFL
        val denom = (maxOf(left, right) - center).toDouble()
        val frac = if (denom > 0.0) 0.5 * (left - right).toDouble() / denom else 0.0
        return bestLag.toDouble() + frac
    }

    @Test
    fun pdafSynthesizesSubPixelDefocusSadCurvesLrWaveformsAndCompletesSequentialW1cIrq122() {
        val ctx = TestContext()
        val sadBase = 0x2C4E_6000L
        val lrBase = 0x2C4E_E000L
        ctx.dma.map(sadBase, 0x8000)
        ctx.dma.map(lrBase, 0x10000)

        val comp = factory.create(ctx, v3Config())
        comp.start()
        val pdaf = assertIs<TickAwareMmioDevice>(ctx.ports.find(PdafHardwareEngine.PORT_ID, MmioDevice::class.java))

        // Reset/stop poll: bit 16 IDLE/READY must be asserted when stopped (S5 0x021E8148..54)
        pdaf.write(0x000, 4, 0L)
        assertEquals(0x10000L, pdaf.read(0x000, 4) and 0x10000L)

        // Program 4 AF blocks, Output #1 SAD + Output #2 L/R waveforms, maxShift=40 across all 3 bands, IRQ 122 enabled
        pdaf.write(0x024, 4, sadBase)
        pdaf.write(0x028, 4, 0L)
        pdaf.write(0x02C, 4, lrBase)
        pdaf.write(0x030, 4, 0L)
        pdaf.write(0x040, 4, 3L) // numBlocks = 4
        pdaf.write(0x1F0, 4, (40L shl 24) or (40L shl 16) or (40L shl 8) or 1L)
        pdaf.write(0x204, 4, 0xFFFFL)
        pdaf.write(0x208, 4, 0x100L)
        pdaf.write(0x210, 4, 0x100L)

        // Trigger START + Output #2 L/R enable (bit 0 | bit 8)
        ctx.tick = 20_000L
        pdaf.write(0x000, 4, 0x101L)
        assertEquals(0L, pdaf.read(0x000, 4) and 0x10000L, "IDLE bit 16 must clear while running")
        assertEquals(20_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS, pdaf.nextDeadline())
        assertFalse(ctx.sink.lines[PdafHardwareEngine.IRQ_LINE] ?: false)

        // Advance to completion tick
        ctx.tick = 20_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS
        pdaf.beforeInstruction(ctx.tick)
        assertEquals(0x10000L, pdaf.read(0x000, 4) and 0x10000L, "IDLE bit 16 must assert on completion")
        assertEquals(0x000FL, pdaf.read(0x200, 4), "4 block completion bits must be latched in +0x200")
        assertEquals(true, ctx.sink.lines[PdafHardwareEngine.IRQ_LINE], "Level GIC IRQ 122 must assert")

        // Verify SAD header (satL=satR=0, sumL==sumR>0) and exact sub-pixel equiangular minimum matching thin-lens defocus
        val sadBytes = ctx.dma.bytes(sadBase)
        val bb = ByteBuffer.wrap(sadBytes).order(ByteOrder.LITTLE_ENDIAN)
        val pdafState = assertIs<Map<*, *>>(comp.state()["pdaf"])
        val expectedDeltaPx = (pdafState["lastDeltaPx"] as Number).toDouble()
        assertTrue(expectedDeltaPx < -1.0, "Closer subject (2m vs 5m) must yield negative phase disparity (S5 0x025474E8 sign): $expectedDeltaPx")

        for (blk in 0 until 4) {
            val base = blk * 0x800
            assertEquals(0, bb.getInt(base + 0x000), "satCountL must be 0")
            assertEquals(0, bb.getInt(base + 0x004), "satCountR must be 0")
            for (band in 0..2) {
                val sumL = bb.getInt(base + 0x008 + band * 8)
                val sumR = bb.getInt(base + 0x00C + band * 8)
                assertTrue(sumL > 0 && sumL == sumR, "L/R band sums must be positive & balanced")
                val measuredLag = equiangularMinLag(sadBytes, blk, band, 40)
                assertTrue(
                    abs(measuredLag - expectedDeltaPx) < 0.01,
                    "Equiangular sub-pixel lag ($measuredLag) must match physical disparity ($expectedDeltaPx) for blk=$blk band=$band",
                )
            }
        }

        // Verify Output #2 L/R waveforms populated with non-zero 12-bit luminance samples
        val lrBb = ByteBuffer.wrap(ctx.dma.bytes(lrBase)).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(lrBb.getShort(0).toInt() and 0xFFFF in 256..3840)
        assertTrue(lrBb.getShort(0x800).toInt() and 0xFFFF in 256..3840)

        // Sequential W1C clearing matching S5 0x021E7DDC (clears lowest bit per IRQ until all 4 cleared)
        for (bit in 0 until 3) {
            pdaf.write(0x200, 4, 1L shl bit)
            assertEquals(true, ctx.sink.lines[PdafHardwareEngine.IRQ_LINE], "IRQ 122 must stay HIGH while remaining block bits set")
        }
        pdaf.write(0x200, 4, 1L shl 3)
        assertEquals(0L, pdaf.read(0x200, 4))
        assertEquals(false, ctx.sink.lines[PdafHardwareEngine.IRQ_LINE], "IRQ 122 must deassert after final W1C ack")

        // When lens focuses to exact subject depth (2.0 m), disparity converges to 0.0 px
        ctx.lensParams = ctx.lensParams.copy(focusDistanceM = 2.0)
        ctx.tick = 100_000L
        pdaf.write(0x000, 4, 1L)
        ctx.tick = 100_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS
        pdaf.beforeInstruction(ctx.tick)
        val inFocusLag = equiangularMinLag(ctx.dma.bytes(sadBase), 0, 0, 40)
        assertTrue(abs(inFocusLag) < 0.01, "In-focus lens must yield ~0.0 px lag, got $inFocusLag")

        // Narrow fine-tracking window (maxShift=4) with large out-of-window defocus (5m vs 1m -> |deltaPx| > 4)
        // produces a monotonic out-of-window SAD ramp peaking at the edge (-4) and expands +0x09C sub-blocks (4 -> 8 slots).
        ctx.lensParams = ctx.lensParams.copy(focusDistanceM = 5.0)
        ctx.subjectDepthM = 1.0
        pdaf.write(0x040, 4, 0L) // numBlocks = 1 (IRQ bit 0)
        pdaf.write(0x09C, 4, 3L) // numSubBlocks = 4 -> 8 dual-polarity DMA slots
        pdaf.write(0x1F0, 4, (4L shl 24) or (4L shl 16) or (4L shl 8) or 1L)
        ctx.tick = 200_000L
        pdaf.write(0x000, 4, 1L)
        ctx.tick = 200_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS
        pdaf.beforeInstruction(ctx.tick)
        val narrowBb = ByteBuffer.wrap(ctx.dma.bytes(sadBase)).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(narrowBb.getInt(7 * 0x800 + 0x008) > 0, "Slot 7 must be populated when +0x09C=3 (4 sub-blocks)")
        val edgeNeg = narrowBb.getInt((92 - 4) * 4).toLong() and 0xFFFF_FFFFL
        val centerSad = narrowBb.getInt(92 * 4).toLong() and 0xFFFF_FFFFL
        val edgePos = narrowBb.getInt((92 + 4) * 4).toLong() and 0xFFFF_FFFFL
        assertTrue(edgeNeg < centerSad && centerSad < edgePos, "Large negative defocus in ±4 window must yield monotonic SAD ramp: $edgeNeg < $centerSad < $edgePos")

        // 2x-decimated band 1 (+0x1F0 = [50, 25, 50], DAT S5 0x021E8458 / 0x02548CF8) must emit half-rate
        // lag on band 1 so firmware's `lsleq r0, r0, #1` matches full-rate bands 0 & 2.
        ctx.subjectDepthM = 2.0
        pdaf.write(0x1F0, 4, (50L shl 24) or (25L shl 16) or (50L shl 8) or 1L)
        ctx.tick = 300_000L
        pdaf.write(0x000, 4, 1L)
        ctx.tick = 300_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS
        pdaf.beforeInstruction(ctx.tick)
        val band0Lag = equiangularMinLag(ctx.dma.bytes(sadBase), 0, 0, 50)
        val band1Lag = equiangularMinLag(ctx.dma.bytes(sadBase), 0, 1, 25)
        val band2Lag = equiangularMinLag(ctx.dma.bytes(sadBase), 0, 2, 50)
        assertTrue(abs(band0Lag - expectedDeltaPx) < 0.01, "Band 0 must remain 1x full-rate ($expectedDeltaPx), got $band0Lag")
        assertTrue(abs(band1Lag - expectedDeltaPx * 0.5) < 0.01, "Band 1 (maxShift=25 vs 50) must emit 0.5x half-rate (${expectedDeltaPx * 0.5}), got $band1Lag")
        assertTrue(abs(band2Lag - expectedDeltaPx) < 0.01, "Band 2 must remain 1x full-rate ($expectedDeltaPx), got $band2Lag")

        // Snapshot round-trip (and backward compatibility without "pdaf" key)
        val saved = (comp as StatefulComponent).saveSnapshot()
        assertTrue("pdaf" in saved)
        val comp2 = factory.create(TestContext(), v3Config())
        (comp2 as StatefulComponent).restoreSnapshot(saved)
        val state2 = assertIs<Map<*, *>>(comp2.state()["pdaf"])
        assertEquals(4L, state2["completionCount"])

        val legacySnap = LinkedHashMap<String, Any?>(saved).apply { remove("pdaf") }
        val comp3 = factory.create(TestContext(), v3Config())
        (comp3 as StatefulComponent).restoreSnapshot(legacySnap)

        comp.close()
        comp2.close()
        comp3.close()
    }

    @Test
    fun pdafUsesHardwareProgrammedOffCenterBlockRoiRegistersWhenSampling3dDepth() {
        val ctx = TestContext(
            lensParams = LensParameters("GF45-100mmF4", 45.0, 4.0, 4.5, 0.9, 0.0),
            subjectDepthM = 6.0,
        )
        // Replace SceneDepthPort with spatially varying 3D scene: center backdrop=4.50m, off-center right portrait=1.92m
        ctx.claims[SceneDepthPort.PORT_NAME] = object : SceneDepthPort {
            override fun fallbackSubjectDistanceM(): Double = 6.0
            override fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double {
                val cx = (normX0 + normX1) * 0.5
                return if (cx > 0.58) 1.92 else 4.50
            }
        }
        val sadBase = 0x2C4E_6000L
        ctx.dma.map(sadBase, 0x8000)

        val comp = factory.create(ctx, v3Config())
        comp.start()
        val pdaf = assertIs<TickAwareMmioDevice>(ctx.ports.find(PdafHardwareEngine.PORT_ID, MmioDevice::class.java))

        pdaf.write(0x024, 4, sadBase)
        pdaf.write(0x028, 4, 0L)
        pdaf.write(0x040, 4, 0L) // 1 AF block
        pdaf.write(0x1F0, 4, (50L shl 24) or (25L shl 16) or (50L shl 8) or 1L)
        pdaf.write(0x204, 4, 0xFFFFL)
        pdaf.write(0x208, 4, 0x100L)
        // Program off-center right PDAF block matching GFX100S 2.13 S5 (0x021E82C0..0x021E85C8):
        // stride = 11648 in +0x034[31:16], block 0 origin x = 7356 (0x1CBC) in +0x044[29:16], span = 196 in +0x084
        pdaf.write(0x034, 4, 11648L shl 16)
        pdaf.write(0x044, 4, 7356L shl 16)
        pdaf.write(0x084, 4, 196L)

        ctx.tick = 10_000L
        pdaf.write(0x000, 4, 1L)
        ctx.tick = 10_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS
        pdaf.beforeInstruction(ctx.tick)

        val pdafState = assertIs<Map<*, *>>(comp.state()["pdaf"])
        val roiX = (pdafState["lastRoiNormX"] as Number).toDouble()
        val subjDepth = (pdafState["lastSubjectDepthM"] as Number).toDouble()
        val deltaPx = (pdafState["lastDeltaPx"] as Number).toDouble()
        assertTrue(roiX in 0.62..0.68, "Expected off-center right ROI normX (~0.64), got $roiX")
        assertEquals(1.92, subjDepth, 1e-3, "Off-center PDAF block must sample portrait foreground (1.92m), not center backdrop (4.50m) or slider (6.0m)")
        assertTrue(deltaPx < -1.0, "Back-focused 4.5m lens on 1.92m off-center subject must report negative disparity, got $deltaPx")

        comp.close()
    }

    @Test
    fun pdafSensitivityMatchesFirmwareS5PulseFormulaAndUsesTightRoiWithVerticalBand() {
        // Verify S5 0x025474D0 pulse conversion: pulses = |deltaPx| * K / c0 with K=441,500 nm/px, c0=102 nm/pulse
        // GF45-100 @ 45mm (MFD=0.65m): focusM=53.25m (enc=400) -> subjM=5.5m (enc=3872, true delta=+3472 pulses).
        val sampledRois = ArrayList<DoubleArray>()
        val engine = PdafHardwareEngine(
            clockProvider = { 10_000L },
            sinkProvider = { null },
            dmaMemoryProvider = { null },
            lensProvider = { LensParameters("GF45-100mmF4", 45.0, 5.6, 53.25, 0.65, 0.0) },
            depthProvider = {
                object : SceneDepthPort {
                    override fun fallbackSubjectDistanceM(): Double = 5.5
                    override fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double {
                        sampledRois.add(doubleArrayOf(normX0, normY0, normX1, normY1))
                        val cx = (normX0 + normX1) * 0.5
                        val cy = (normY0 + normY1) * 0.5
                        return if (cx in 0.214..0.466 && cy in 0.393..0.729) 0.93 else 5.5
                    }
                }
            },
            afRoiYProvider = { 0.513 to 0.656 },
        )
        val (defocusMm, deltaPx) = engine.computePhysicalDisparity(
            LensParameters("GF45-100mmF4", 45.0, 5.6, 53.25, 0.65, 0.0),
            5.5,
            50,
        )
        assertTrue(defocusMm in 0.32..0.35, "Expected ~0.333mm defocus from 53.25m to 5.5m, got $defocusMm")
        val predictedPulses = abs(deltaPx) * 402_000.0 / 102.0
        assertTrue(
            predictedPulses in 2900.0..3900.0,
            "S5 0x025474D0 pulse jump must land near true 3472 pulses without 5x overshoot, got $predictedPulses (dpx=$deltaPx)",
        )

        // Verify left-edge ball block (rawX=0x0A59=2649, span=0xFA=250, stride=0x5C00 -> 11776) stays
        // strictly inside the 0.93m foreground sphere x=[0.214..0.466], y=[0.393..0.729] instead of
        // bleeding onto the 5.5m background wall.
        val mmio = engine.createMmioDevice { false }
        mmio.write(0x034, 4, 0x5C00_0000L)
        mmio.write(0x044, 4, 0x0A59_0000L)
        mmio.write(0x084, 4, 0x00FA_00FAL)
        val roi = engine.resolveBlockRoi(0, 8)
        assertTrue(roi[0] >= 0.214 && roi[2] <= 0.466, "Tight X ROI must stay on foreground sphere: ${roi.contentToString()}")
        assertEquals(0.513, roi[1], 1e-6)
        assertEquals(0.656, roi[3], 1e-6)

        // Verify numBlocks=1 with numSubBlocks=8 (live Single-Point AF: +0x040=0, +0x09C=7) keeps
        // ALL 16 DMA sub-block slots on Block 0's hardware ROI instead of falling back to a full-frame grid.
        val dma = MockDmaMemory().apply { map(0x2C4E_6000L, 0x8000) }
        val multiSubEngine = PdafHardwareEngine(
            clockProvider = { 10_000L },
            sinkProvider = { null },
            dmaMemoryProvider = { dma },
            lensProvider = { LensParameters("GF45-100mmF4", 45.0, 5.6, 4.0, 0.65, 0.0) },
            depthProvider = {
                object : SceneDepthPort {
                    override fun fallbackSubjectDistanceM(): Double = 5.5
                    override fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double {
                        val cx = (normX0 + normX1) * 0.5
                        val cy = (normY0 + normY1) * 0.5
                        return if (cx in 0.214..0.466 && cy in 0.393..0.729) 0.93 else 5.5
                    }
                }
            },
            afRoiYProvider = { 0.513 to 0.656 },
        )
        val multiMmio = multiSubEngine.createMmioDevice { false }
        multiMmio.write(0x024, 4, 0x2C4E_6000L)
        multiMmio.write(0x034, 4, 0x5C00_0000L)
        multiMmio.write(0x040, 4, 0L) // numBlocks = 1
        multiMmio.write(0x044, 4, 0x0A59_0000L) // only block 0 X programmed
        multiMmio.write(0x084, 4, 0x00FA_00FAL)
        multiMmio.write(0x09C, 4, 7L) // numSubBlocks = 8 -> 16 DMA slots
        multiMmio.write(0x1F0, 4, (50L shl 24) or (25L shl 16) or (50L shl 8) or 1L)
        multiMmio.write(0x000, 4, 1L)
        multiMmio.beforeInstruction(10_000L + PdafHardwareEngine.COMPLETION_DELAY_TICKS)
        val sadBuf = dma.bytes(0x2C4E_6000L)
        val lag0 = equiangularMinLag(sadBuf, 0, 0, 50)
        val lag15 = equiangularMinLag(sadBuf, 15, 0, 50)
        assertTrue(lag0 < -3.0, "Slot 0 must report strong negative disparity for 0.93m sphere vs 4.0m focus, got $lag0")
        assertEquals(lag0, lag15, 1e-3, "Slot 15 must inherit Block 0's sphere ROI, not bleed into 5.5m wall (lag0=$lag0, lag15=$lag15)")
    }
}
