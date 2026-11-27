package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import java.nio.file.Path
import kotlin.test.*

class SocIspMovieCaptureV3Test {
    private class MockInterruptSink : InterruptSink {
        val lines = linkedMapOf<Int, Boolean>()
        val transitions = mutableListOf<Pair<Int, Boolean>>()
        override fun setLine(irq: Int, high: Boolean) {
            lines[irq] = high
            transitions += irq to high
        }
    }

    private class TestContext(val sink: MockInterruptSink = MockInterruptSink()) : ComponentContext {
        var tick = 1000L
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
        }
    }

    private val factory = SocIspFactory()

    private fun v3Config(autoFrameIrqs: Boolean = true): Map<String, Any> = mapOf(
        "profile" to SocIspFactory.PROFILE_V3,
        "initialFill" to 0L,
        "resultSurfaceFile" to "",
        "resultSurfaceSha256" to "",
        "autoFrameIrqs" to autoFrameIrqs,
    )

    @Test
    fun maliV61SoftResetMapSessionDoorbellAndIrq138Handshake() {
        val ctx = TestContext()
        val comp = factory.create(ctx, v3Config())
        comp.start()
        val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))

        // h265_SoftReset (S4 0x012074B4..0x01207504) & h265_DisableScheduler (S4 0x01207334..0x01207354)
        assertEquals(MaliV61VpuEngine.VERSION_WORD, vpu.read(0x00, 4))
        assertEquals(0L, vpu.read(0x04, 4)) // ENABLE starts 0 on reset
        assertEquals(MaliV61VpuEngine.NCORES_WORD, vpu.read(0x08, 4))
        assertEquals(MaliV61VpuEngine.NLSIDS_WORD, vpu.read(0x0C, 4))
        vpu.write(0x04, 4, 1L)
        assertEquals(1L, vpu.read(0x04, 4))
        vpu.write(0x04, 4, 0L) // h265_DisableScheduler polls +0x04 == 0
        assertEquals(0L, vpu.read(0x04, 4))
        vpu.write(0x50, 4, 1L) // RESET
        assertEquals(MaliV61VpuEngine.CORELSID_IDLE, vpu.read(0x10, 4))
        assertEquals(MaliV61VpuEngine.JOBQUEUE_EMPTY, vpu.read(0x14, 4))
        vpu.write(0x24, 4, 0x100L) // CLKFORCE
        vpu.write(0x44, 4, 0x2L)   // BUSCTRL

        // h265_MapSession on LSID 0 (S4 0x01207508..0x0120763C)
        vpu.write(0x20C, 4, 1L) // ALLOC
        vpu.write(0x218, 4, 1L) // TERMINATE -> self-clears to 0 immediately
        assertEquals(0L, vpu.read(0x218, 4))
        vpu.write(0x204, 4, 0x8800_0000L) // MMU_CTRL
        vpu.write(0x200, 4, 0x4F0L)       // CTRL
        vpu.write(0x210, 4, 0L)           // FLUSH_ALL
        vpu.write(0x214, 4, 1L)           // SCHED

        // Host->VPU doorbell (S4 0x0120383C / 0x012062D8)
        ctx.tick = 2000L
        vpu.write(0x220, 4, 1L) // IRQHOST
        assertEquals(2064L, vpu.nextDeadline())
        assertFalse(ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE] ?: false)

        ctx.tick = 2064L
        vpu.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE])
        assertEquals(1L, vpu.read(0x18, 4))  // Global IRQVE bit 0
        assertEquals(1L, vpu.read(0x21C, 4)) // LSID0 IRQVE

        // h265Int_Handler clears LSID0 IRQVE to 0 (S4 0x01204488)
        vpu.write(0x21C, 4, 0L)
        assertEquals(0L, vpu.read(0x18, 4))
        assertEquals(false, ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE])
        comp.close()
    }

    @Test
    fun companionImagingEnginesSatisfyResetIdlePollsAndLevelIrqHandshakes() {
        val ctx = TestContext()
        val comp = factory.create(ctx, v3Config())
        comp.start()

        // 1. MOVDC (0xFFF7D000, IRQ 110)
        val movdc = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_MOVDC, MmioDevice::class.java))
        movdc.write(0x000, 4, 0L)
        assertEquals(1L, movdc.read(0x000, 4) and 1L)
        assertEquals(0x0001_0001L, movdc.read(0x124, 4) and 0x0001_0001L)
        movdc.write(0x138, 4, 1L)
        ctx.tick = 3000L
        movdc.write(0x004, 4, 1L)
        ctx.tick = 3064L
        movdc.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_MOVDC])
        movdc.write(0x134, 4, 1L) // W1C
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_MOVDC])

        // 2. RETCH (0xFFFA3000, IRQ 142)
        val retch = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_RETCH, MmioDevice::class.java))
        retch.write(0x000, 4, 0L)
        assertEquals(1L, retch.read(0x000, 4) and 1L)
        retch.write(0x0A8, 4, 0x1301L)
        ctx.tick = 4000L
        retch.write(0x004, 4, 1L)
        ctx.tick = 4064L
        retch.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_RETCH])
        assertEquals(1L, retch.read(0x0A0, 4) and 1L)
        retch.write(0x0A4, 4, 0x1331L)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_RETCH])

        // 3. LSRAW (0xFFFA2000, IRQ 137) — init (0x100 -> bit 16), encode (0x000=1 -> bit 0), decode (0x000=2 -> bit 8)
        // DAT section5 0x021E7270..0x021E7CF0: 0xFFFA2008 is active-low IRQ mask (0 = unmasked, 1 = masked).
        val lsraw = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_LSRAW, MmioDevice::class.java))
        // 3a. Open/Init handshake (unmask bit 16 only: 0x00FC_0507, trigger 0xFFFA2100 = 1)
        lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE.inv())
        ctx.tick = 4800L
        lsraw.write(0x100, 4, 1L)
        ctx.tick = 4864L
        lsraw.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])
        assertEquals(ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE, lsraw.read(0x004, 4))
        lsraw.write(0x004, 4, ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])

        // 3b. Soft reset (0xFFFA2000 bit 8 self-clears immediately)
        lsraw.write(0x000, 4, 0x100L)
        assertEquals(0L, lsraw.read(0x000, 4) and 0x100L)

        // 3c. Encode (unmask bit 0 only: 0x00FD_0506, trigger 0xFFFA2000 = 1)
        lsraw.write(0x09C, 4, 1_048_576L)
        lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE.inv())
        ctx.tick = 5000L
        lsraw.write(0x000, 4, 1L) // Encode start
        ctx.tick = 5064L
        lsraw.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])
        assertEquals(ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE, lsraw.read(0x004, 4))
        assertTrue(lsraw.read(0x0A4, 4) > 0L)
        lsraw.write(0x004, 4, ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])

        // 3d. Decode (unmask bit 8 only: 0x00FD_0407, trigger 0xFFFA2000 = 2)
        lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_DECODE_DONE.inv())
        ctx.tick = 6000L
        lsraw.write(0x000, 4, 2L) // Decode start (bit 1)
        ctx.tick = 6064L
        lsraw.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])
        assertEquals(ImagingCompanionEngines.LSRAW_IRQ_DECODE_DONE, lsraw.read(0x004, 4))
        lsraw.write(0x004, 4, ImagingCompanionEngines.LSRAW_IRQ_DECODE_DONE)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_LSRAW])

        // 4. Bit-31 reset + 0x01000100 idle companions (6F000 IRQ 134, 80000 IRQ 126, BA000 IRQ 139) & B9000 IRQ 127
        for ((portId, idleOff, w1cOff, irqLine) in listOf(
            listOf(ImagingCompanionEngines.PORT_COMPANION_6F000, 0x078L, 0x084L, ImagingCompanionEngines.IRQ_6F000),
            listOf(ImagingCompanionEngines.PORT_COMPANION_80000, 0x028L, 0x02CL, ImagingCompanionEngines.IRQ_80000),
            listOf(ImagingCompanionEngines.PORT_UNIDMAC_BA000, 0x040L, 0x048L, ImagingCompanionEngines.IRQ_UNIDMAC),
        )) {
            val dev = assertIs<TickAwareMmioDevice>(ctx.ports.find(portId as String, MmioDevice::class.java))
            dev.write(0x000, 4, 0L)
            assertEquals(0x8000_0000L, dev.read(0x000, 4) and 0x8000_0000L, "$portId bit-31 reset ready")
            assertEquals(0x0100_0100L, dev.read(idleOff as Long, 4) and 0x0100_0100L, "$portId idle ready")
            ctx.tick += 1000L
            dev.write(0x000, 4, 0x8000_0001L)
            ctx.tick += 64L
            dev.beforeInstruction(ctx.tick)
            assertEquals(true, ctx.sink.lines[irqLine as Int], "$portId IRQ $irqLine asserted")
            dev.write(w1cOff as Long, 4, 1L)
            assertEquals(false, ctx.sink.lines[irqLine], "$portId IRQ $irqLine cleared")
        }

        val b9 = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_COMPANION_B9000, MmioDevice::class.java))
        b9.write(0x000, 4, 0L)
        assertEquals(1L, b9.read(0x000, 4) and 1L)
        assertEquals(0x0001_0001L, b9.read(0x040, 4) and 0x0001_0001L)
        ctx.tick += 1000L
        b9.write(0x004, 4, 1L)
        ctx.tick += 64L
        b9.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_B9000])
        b9.write(0x044, 4, 1L)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_B9000])

        // 5. AXI High-34, VCodec Idle, ISP 82000 (IRQ 129 rising-edge), ISP 83000/85000, Companion BB000 (IRQ 140 level)
        val axi = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_AXI_HIGH34, MmioDevice::class.java))
        axi.write(0x40, 4, 0x3L)
        assertEquals(0x3L, axi.read(0x40, 4))
        val vcodecIdle = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_VCODEC_IDLE, MmioDevice::class.java))
        assertEquals(0L, vcodecIdle.read(0x0, 4) and 2L)

        val isp82 = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_82000, MmioDevice::class.java))
        assertEquals(0x111L, isp82.read(0x94, 4) and 0x111L)
        ctx.sink.transitions.clear()
        ctx.tick = 10_000L
        isp82.write(0x004, 4, 1L) // Pass 1 START
        assertEquals(10_064L, isp82.nextDeadline())
        ctx.tick = 10_064L
        isp82.beforeInstruction(ctx.tick)
        assertEquals(
            listOf(ImagingCompanionEngines.IRQ_ISP_82000 to true, ImagingCompanionEngines.IRQ_ISP_82000 to false),
            ctx.sink.transitions.filter { it.first == ImagingCompanionEngines.IRQ_ISP_82000 },
        )
        assertEquals(0L, isp82.read(0x004, 4) and 1L)

        // Pass 2 repeatable rising-edge pulse on IRQ 129
        ctx.sink.transitions.clear()
        ctx.tick = 11_000L
        isp82.write(0x004, 4, 1L)
        ctx.tick = 11_064L
        isp82.beforeInstruction(ctx.tick)
        assertEquals(
            listOf(ImagingCompanionEngines.IRQ_ISP_82000 to true, ImagingCompanionEngines.IRQ_ISP_82000 to false),
            ctx.sink.transitions.filter { it.first == ImagingCompanionEngines.IRQ_ISP_82000 },
        )

        val isp83 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_83000, MmioDevice::class.java))
        isp83.write(0x000, 4, 0x1234_5678L)
        isp83.write(0x6B8, 4, 0xCAFE_BABEL)
        assertEquals(0x1234_5678L, isp83.read(0x000, 4))
        assertEquals(0xCAFE_BABEL, isp83.read(0x6B8, 4))
        assertFails { isp83.read(0x6BC, 4) }

        val isp85 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_85000, MmioDevice::class.java))
        isp85.write(0x000, 4, 0x0A0B_0C0DL)
        isp85.write(0x0EC, 4, 0xDEAD_BEEFL)
        assertEquals(0x0A0B_0C0DL, isp85.read(0x000, 4))
        assertEquals(0xDEAD_BEEFL, isp85.read(0x0EC, 4))
        assertFails { isp85.read(0x0F0, 4) }

        val bb0 = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_COMPANION_BB000, MmioDevice::class.java))
        bb0.write(0x000, 4, 0L)
        assertEquals(1L, bb0.read(0x000, 4) and 1L)
        assertEquals(0x0004_0004L, bb0.read(0x098, 4) and 0x0004_0004L)
        ctx.tick = 12_000L
        bb0.write(0x004, 4, 1L)
        assertEquals(12_064L, bb0.nextDeadline())
        ctx.tick = 12_064L
        bb0.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[ImagingCompanionEngines.IRQ_BB000])
        assertEquals(1L, bb0.read(0x09C, 4))
        bb0.write(0x09C, 4, 1L)
        assertEquals(false, ctx.sink.lines[ImagingCompanionEngines.IRQ_BB000])

        val routedLines = comp.interruptRoutes().flatMap { it.lines.toList() }.toSet()
        assertTrue(SocIspFactory.IRQ_129 in routedLines)
        assertTrue(SocIspFactory.IRQ_140 in routedLines)

        comp.close()
    }

    @Test
    fun reccs82000PumpsActiveOpticalUyvyFrameIntoOutputDmaBufferOnCompletion() {
        val ctx = TestContext()
        val dstAddr = 0x8A00_0000L
        val width = 32
        val height = 16
        val stride = width * 2
        val ram = ByteArray(height * stride)
        ctx.ports.register(
            "cpu",
            "cpu.memory",
            DmaMemory::class.java,
            object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(write && address >= dstAddr && address + size <= dstAddr + ram.size) {
                        "Out-of-bounds DMA 0x${address.toString(16)} size=$size"
                    }
                }
                override fun read(address: Long, destination: java.nio.ByteBuffer) = error("unexpected read")
                override fun write(address: Long, source: java.nio.ByteBuffer) {
                    val off = (address - dstAddr).toInt()
                    source.get(ram, off, source.remaining())
                }
            },
        )
        ctx.ports.register(
            "cmos",
            "cmos",
            SensorPort::class.java,
            object : SensorPort {
                override fun expose(exposure: Exposure, destination: java.nio.ByteBuffer): Int {
                    val pixels = width * height
                    for (i in 0 until pixels) {
                        destination.putShort(0x8000.toShort())
                    }
                    return pixels * 2
                }
            },
        )

        val comp = factory.create(ctx, v3Config())
        comp.start()
        val isp82 = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_82000, MmioDevice::class.java))
        isp82.write(0x050, 4, (stride.toLong() shl 16) or width.toLong())
        isp82.write(0x058, 4, height.toLong())
        isp82.write(0x070, 4, dstAddr)
        isp82.write(0x074, 4, 0L)
        ctx.tick = 20_000L
        isp82.write(0x004, 4, 1L)
        ctx.tick = 20_064L
        isp82.beforeInstruction(ctx.tick)

        assertTrue(ram.any { it != 0.toByte() }, "RECC_S completion should render non-zero UYVY pixels into DMA buffer")
        assertEquals(1L, comp.state()["stillDmaPumps"])
        comp.close()
    }

    @Test
    fun reccs82000DecodesRealSiliconGeometryAndStillIspColorVsMonochromeBanks() {
        val ctx = TestContext()
        val dstAddr = 0xBA51_9000L
        val width = 32
        val height = 16
        val stride = 96 // > width * 2 (64 B), mirroring real-silicon 31,680 B strided layout
        val ram = ByteArray(height * stride)
        ctx.ports.register(
            "cpu",
            "cpu.memory",
            DmaMemory::class.java,
            object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(write && address >= dstAddr && address + size <= dstAddr + ram.size) {
                        "Out-of-bounds DMA 0x${address.toString(16)} size=$size"
                    }
                }
                override fun read(address: Long, destination: java.nio.ByteBuffer) = error("unexpected read")
                override fun write(address: Long, source: java.nio.ByteBuffer) {
                    val off = (address - dstAddr).toInt()
                    source.get(ram, off, source.remaining())
                }
            },
        )
        // Vivid red-dominant RGGB Bayer frame so color vs monochrome U/V separation is unmistakable
        ctx.ports.register(
            "cmos",
            "cmos",
            SensorPort::class.java,
            object : SensorPort {
                override fun expose(exposure: Exposure, destination: java.nio.ByteBuffer): Int {
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val r = ((y and 1) == 0) && ((x and 1) == 0)
                            val v = if (r) 0x3800 else 0x0600
                            destination.putShort(v.toShort())
                        }
                    }
                    return width * height * 2
                }
            },
        )

        val comp = factory.create(ctx, v3Config())
        comp.start()
        val isp82 = assertIs<TickAwareMmioDevice>(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_82000, MmioDevice::class.java))
        val isp86 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_86000, MmioDevice::class.java))
        val isp87 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_87000, MmioDevice::class.java))
        val isp88 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_88000, MmioDevice::class.java))
        val isp98 = assertNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_98000, MmioDevice::class.java))

        // Program real-silicon STD 3x3 Q10 CCM in 0xFFF86204..0xFFF86218 + Color mode (0xFFF98000=0, 0xFFF87200=0x10111515, 0xFFF88000=1)
        isp86.write(0x204, 4, 0x1FB8_041BL)
        isp86.write(0x208, 4, 0x0000_002DL)
        isp86.write(0x20C, 4, 0x0470_1FC5L)
        isp86.write(0x210, 4, 0x0000_1FCBL)
        isp86.write(0x214, 4, 0x1F36_0049L)
        isp86.write(0x218, 4, 0x0000_0481L)
        isp87.write(0x200, 4, 0x1011_1515L)
        isp88.write(0x000, 4, 1L)
        isp98.write(0x000, 4, 0L)

        // 1. Real-silicon Pass 1 (RAW->YC): 0x050=(height<<16), 0x058=width, 0x080=stride
        isp82.write(0x050, 4, height.toLong() shl 16)
        isp82.write(0x054, 4, 0L)
        isp82.write(0x058, 4, width.toLong())
        isp82.write(0x070, 4, dstAddr)
        isp82.write(0x074, 4, 0L)
        isp82.write(0x080, 4, stride.toLong())
        ctx.tick = 10_000L
        isp82.write(0x004, 4, 1L)
        ctx.tick = 10_064L
        isp82.beforeInstruction(ctx.tick)

        assertEquals(1L, comp.state()["stillDmaPumps"])
        val info1 = comp.state()["lastStillDmaInfo"] as Map<*, *>
        assertEquals(width, info1["width"])
        assertEquals(height, info1["height"])
        assertEquals(stride, info1["stride"])
        assertEquals(true, info1["pumped"])
        // Check vivid chroma deviation from neutral 128 in Color mode (row 4, pixel pair 4)
        val colorOff = 4 * stride + 4 * 4
        val colorU = ram[colorOff].toInt() and 0xFF
        val colorV = ram[colorOff + 2].toInt() and 0xFF
        assertTrue(
            kotlin.math.abs(colorV - 128) >= 20 || kotlin.math.abs(colorU - 128) >= 20,
            "STD color mode must preserve chroma (U=$colorU, V=$colorV)",
        )

        // 2. Switch to real-silicon MONOCHROME/ACROS (0xFFF98000=1, 0xFFF87200=0x10111115, 0xFFF88000=0)
        //    and run Pass 2 (YC->YC) layout: 0x050=(height<<16)|width, 0x058=width, 0x080=stride
        isp87.write(0x200, 4, 0x1011_1115L)
        isp88.write(0x000, 4, 0L)
        isp98.write(0x000, 4, 1L)
        ram.fill(0)
        isp82.write(0x050, 4, (height.toLong() shl 16) or width.toLong())
        isp82.write(0x058, 4, width.toLong())
        isp82.write(0x080, 4, stride.toLong())
        ctx.tick = 20_000L
        isp82.write(0x004, 4, 1L)
        ctx.tick = 20_064L
        isp82.beforeInstruction(ctx.tick)

        assertEquals(2L, comp.state()["stillDmaPumps"])
        val monoU = ram[colorOff].toInt() and 0xFF
        val monoV = ram[colorOff + 2].toInt() and 0xFF
        assertEquals(128, monoU, "Monochrome Still ISP mode must neutralize U chroma to 128")
        assertEquals(128, monoV, "Monochrome Still ISP mode must neutralize V chroma to 128")
        comp.close()
    }

    @Test
    fun v2SnapshotUpgradesCleanlyToV3AndV3SnapshotRoundTrips() {
        val ctxV2 = TestContext()
        val v2 = factory.create(
            ctxV2,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V2,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "autoFrameIrqs" to true,
            ),
        ) as StatefulComponent
        val v2Snap = v2.saveSnapshot()
        assertFalse("maliV61" in v2Snap)

        val ctxV3 = TestContext()
        val v3 = factory.create(ctxV3, v3Config()) as StatefulComponent
        v3.restoreSnapshot(v2Snap)
        (v3 as HardwareComponent).start()

        val vpu = assertIs<TickAwareMmioDevice>(ctxV3.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
        vpu.write(0x204, 4, 0x8123_4000L)
        vpu.write(0x220, 4, 1L)
        val v3Snap = v3.saveSnapshot()
        assertTrue("maliV61" in v3Snap && "companionEngines" in v3Snap)

        val ctxV3b = TestContext()
        val v3b = factory.create(ctxV3b, v3Config()) as StatefulComponent
        v3b.restoreSnapshot(v3Snap)
        val vpuB = assertIs<TickAwareMmioDevice>(ctxV3b.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
        assertEquals(0x8123_4000L, vpuB.read(0x204, 4))

        (v2 as HardwareComponent).close()
        (v3 as HardwareComponent).close()
        (v3b as HardwareComponent).close()
    }

    private class MveMemory(ctx: TestContext, val ramBase: Long) {
        val ram = ByteArray(0x40_0000) // 4 MiB guest RAM
        val leRam = java.nio.ByteBuffer.wrap(ram).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun putU32(pa: Long, v: Int) { leRam.putInt((pa - ramBase).toInt(), v) }
        fun getU32(pa: Long): Int = leRam.getInt((pa - ramBase).toInt())
        fun putU16(pa: Long, v: Int) { leRam.putShort((pa - ramBase).toInt(), v.toShort()) }
        fun getU16(pa: Long): Int = leRam.getShort((pa - ramBase).toInt()).toInt() and 0xFFFF

        init {
            ctx.ports.register(
                "cpu",
                "cpu.memory",
                DmaMemory::class.java,
                object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        require(address >= ramBase && size >= 0L && address + size <= ramBase + ram.size) {
                            "Out-of-bounds Mali-V61 DMA 0x${address.toString(16)} size=$size"
                        }
                    }
                    override fun read(address: Long, destination: java.nio.ByteBuffer) {
                        destination.put(ram, (address - ramBase).toInt(), destination.remaining())
                    }
                    override fun write(address: Long, source: java.nio.ByteBuffer) {
                        source.get(ram, (address - ramBase).toInt(), source.remaining())
                    }
                },
            )
        }

        // Build L1 -> L2 Mali-V61 MMU page tables mapping comm pages 0x1007_9000..0x1007_E000
        // and output header/bitstream pages at 0x2000_0000 & 0x2001_0000
        val l1Pa = ramBase + 0x0000L
        val l2CommPa = ramBase + 0x1000L
        val l2OutPa = ramBase + 0x2000L
        val msgInPa = ramBase + 0x10000L
        val msgOutPa = ramBase + 0x11000L
        val bufInHostPa = ramBase + 0x12000L
        val bufInMvePa = ramBase + 0x13000L
        val bufOutHostPa = ramBase + 0x14000L
        val bufOutMvePa = ramBase + 0x15000L
        val hdrOutPa = ramBase + 0x20000L
        val bitOutPa = ramBase + 0x30000L

        fun mmuEntry(pa: Long): Int = (((pa ushr 10) and 0x3FFF_FFFCL) or 3L).toInt()
        init {
            putU32(l1Pa + (0x1007_9000L ushr 22) * 4L, mmuEntry(l2CommPa))
            putU32(l1Pa + (0x2000_0000L ushr 22) * 4L, mmuEntry(l2OutPa))
            putU32(l2CommPa + 0x79 * 4L, mmuEntry(msgInPa))
            putU32(l2CommPa + 0x7A * 4L, mmuEntry(msgOutPa))
            putU32(l2CommPa + 0x7B * 4L, mmuEntry(bufInHostPa))
            putU32(l2CommPa + 0x7C * 4L, mmuEntry(bufInMvePa))
            putU32(l2CommPa + 0x7D * 4L, mmuEntry(bufOutHostPa))
            putU32(l2CommPa + 0x7E * 4L, mmuEntry(bufOutMvePa))
            putU32(l2OutPa + 0x00 * 4L, mmuEntry(hdrOutPa))
            for (p in 0 until 8) {
                putU32(l2OutPa + (0x10 + p) * 4L, mmuEntry(bitOutPa + p * 0x1000L))
            }
        }

        fun pushHost(hostPa: Long, code: Int, words: IntArray) {
            var pos = getU16(hostPa + 2L)
            putU32(hostPa + 16L + pos * 4L, (code and 0xFFFF) or ((words.size * 4) shl 16))
            pos = (pos + 1) % 1020
            for (w in words) {
                putU32(hostPa + 16L + pos * 4L, w)
                pos = (pos + 1) % 1020
            }
            putU16(hostPa + 2L, pos)
        }

        fun popMve(hostPa: Long, mvePa: Long): Pair<Int, IntArray>? {
            var pos = getU16(hostPa)
            val wpos = getU16(mvePa)
            if (pos == wpos) return null
            val hdr = getU32(mvePa + 16L + pos * 4L)
            val code = hdr and 0xFFFF
            val nWords = ((hdr ushr 16) + 3) ushr 2
            pos = (pos + 1) % 1020
            val out = IntArray(nWords) {
                val v = getU32(mvePa + 16L + pos * 4L)
                pos = (pos + 1) % 1020
                v
            }
            putU16(hostPa, pos)
            return code to out
        }

    }

    private inline fun withMveMemory(base: Long = 0x7000_0000L, run: MveMemory.(TestContext) -> Unit) {
        val ctx = TestContext()
        MveMemory(ctx, base).run(ctx)
    }

    @Test
    fun decoderFlushesAndRequeuesThePictureSurfaceBeforeWritingVisibleY0l2() = withMveMemory { ctx ->
        val executable = System.getenv("FUJI_FFMPEG_BIN") ?: System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
        try {
            val probe = ProcessBuilder(executable, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertEquals(0, probe.waitFor())
        } catch (e: java.io.IOException) {
            if (executable != "ffmpeg") throw e
            assumeTrue(false, "FFmpeg unavailable for playback decode")
        }
        val encoder = MaliV61BitstreamEncoder
        val packets = listOf(encoder.buildH264Sps(16, 16, 30L shl 16), encoder.buildH264Pps(),
            encoder.buildH264IdrSlice(16, 16, 0, byteArrayOf(64), byteArrayOf(155.toByte()), byteArrayOf(93)))
        var size = 0
        for (packet in packets) {
            putU32(hdrOutPa + size, packet.size - 4)
            packet.copyInto(ram, (hdrOutPa - ramBase).toInt() + size + 4, 4)
            size += packet.size
        }
        val outputOffset = (bitOutPa - ramBase).toInt() + 4092
        ram.fill(0x55, outputOffset - 4, outputOffset + 8 * 80)
        val frame = IntArray(18).apply {
            this[0] = 19
            this[5] = 0x00100010
            this[6] = 0x121
            this[7] = 0x20010ffc
            this[13] = 80
            this[16] = 0x00100010
        }
        val component = factory.create(ctx, v3Config())
        try {
            component.start()
            val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
            vpu.write(0x204, 4, mmuEntry(l1Pa).toUInt().toLong())
            vpu.write(0x04, 4, 1L)
            fun ring() {
                vpu.write(0x220, 4, 1L)
                ctx.tick += 64
                vpu.beforeInstruction(ctx.tick)
                vpu.write(0x21C, 4, 0L)
            }
            pushHost(msgInPa, 1009, intArrayOf(4, 0))
            pushHost(msgInPa, 1001, IntArray(0))
            pushHost(bufInHostPa, 3002, intArrayOf(18, 0, 77, 0, 1, size, 0, size, 0x20000000, 0))
            pushHost(bufOutHostPa, 3001, frame)
            ring()
            val responses = generateSequence { popMve(msgInPa, msgOutPa)?.first }.toList()
            assertTrue(2015 in responses)
            assertEquals(null, popMve(bufOutHostPa, bufOutMvePa))
            pushHost(msgInPa, 1004, IntArray(0))
            ring()
            pushHost(bufOutHostPa, 3001, frame)
            ring()
            assertEquals(null, popMve(bufOutHostPa, bufOutMvePa))
            var returned = popMve(bufOutHostPa, bufOutMvePa)
            for (i in 0 until 120) {
                if (returned != null) break
                ctx.tick += 900_900
                vpu.beforeInstruction(ctx.tick)
                returned = popMve(bufOutHostPa, bufOutMvePa)
            }
            val (code, values) = assertNotNull(returned)
            assertEquals(3001, code)
            assertEquals(19, values[0])
            assertEquals(77, values[2])
            assertEquals(0x10004, values[4]) // progressive picture and end of stream
            assertEquals(0x00100010, values[5])
            for (y in 0 until 8) {
                for (x in 0 until 16) {
                    val word = leRam.getInt(outputOffset + y * 80 + x * 4)
                    assertTrue(kotlin.math.abs((word and 1023) - 64 * 4) <= 4)
                    assertTrue(kotlin.math.abs((word ushr 20 and 1023) - 64 * 4) <= 4)
                    val chroma = if (x % 2 == 0) 155 else 93
                    assertTrue(kotlin.math.abs((word ushr 10 and 1023) - chroma * 4) <= 4)
                }
                assertTrue((64 until 80).all { ram[outputOffset + y * 80 + it] == 0x55.toByte() })
            }
            assertEquals(0x55.toByte(), ram[outputOffset - 1])
        } finally {
            component.close()
        }
    }

    @Test
    fun movieFrameClockDeliversEdgesWithBasicInterruptSink() = withMveMemory { ctx ->
        val frames = mutableListOf<Long>()
        ctx.ports.register("ccd", CcdMovieFrameInput.PORT_ID, CcdMovieFrameInput::class.java,
            CcdMovieFrameInput { frames.add(it) })
        val component = factory.create(ctx, v3Config())
        try {
            component.start()
            val core = assertIs<TickAwareMmioDevice>(ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java))
            val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
            val clock = assertNotNull(ctx.ports.find(CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java))
            vpu.write(0x204, 4, mmuEntry(l1Pa).toUInt().toLong())
            vpu.write(0x04, 4, 1L)
            fun sendControl(code: Int) {
                pushHost(msgInPa, code, IntArray(0))
                vpu.write(0x220, 4, 1L)
                ctx.tick += 64
                vpu.beforeInstruction(ctx.tick)
            }
            fun advanceFrame() {
                ctx.tick = assertNotNull(core.nextDeadline())
                core.beforeInstruction(ctx.tick)
            }

            sendControl(1001) // MVE GO
            assertTrue(clock.movieFrameClockActive)
            advanceFrame()
            assertEquals(listOf(ctx.tick), frames)
            sendControl(1002) // MVE STOP
            assertFalse(clock.movieFrameClockActive)
            advanceFrame()
            assertEquals(1, frames.size)
        } finally {
            component.close()
        }
    }

    @Test
    fun maliV61RascMveV24EncodesH264AndH265HeadersAndIdrFramesThroughL1L2Mmu() = withMveMemory { ctx ->
        val comp = factory.create(ctx, v3Config())
        comp.start()
        val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
        vpu.write(0x204, 4, mmuEntry(l1Pa).toLong() and 0xFFFF_FFFFL)
        vpu.write(0x04, 4, 1L) // ENABLE = 1

        // 1. Send JOB + SET_OPTION(opt 38 -> H.264) + GO
        pushHost(msgInPa, 1009, intArrayOf(1, 0, 0))
        pushHost(msgInPa, 1010, intArrayOf(38, 8))
        pushHost(msgInPa, 1001, IntArray(0))
        ctx.tick = 1000L
        vpu.write(0x220, 4, 1L)
        ctx.tick = 1064L
        vpu.beforeInstruction(ctx.tick)
        assertEquals(true, ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE])
        vpu.write(0x21C, 4, 0L)

        assertEquals(2001, popMve(msgInPa, msgOutPa)?.first) // RSP_SWITCHED_IN
        assertEquals(2003, popMve(msgInPa, msgOutPa)?.first) // RSP_SET_OPTION
        val goRsp = assertNotNull(popMve(msgInPa, msgOutPa))
        assertEquals(2011, goRsp.first) // RSP_STATE_CHANGE
        assertEquals(1, goRsp.second[0]) // MVE_STATE_RUNNING

        // 2. Queue 2 header buffers (SPS, PPS), 1 bitstream buffer, and 1 320x180 input frame
        for (i in 0 until 2) {
            val h = IntArray(10)
            h[0] = 0x4B0DA000 + i
            h[5] = 64
            h[8] = 0x2000_0000 + i * 64
            pushHost(bufOutHostPa, 3002, h)
        }
        val bitReq = IntArray(10)
        bitReq[0] = 7 // nHandle (frame_id = 7)
        bitReq[5] = 0x8000
        bitReq[8] = 0x2001_0000
        pushHost(bufOutHostPa, 3002, bitReq)

        val frameReq = IntArray(18)
        val inputVa = 0x5000_0000L
        val l2Input = ramBase + 0x4000
        val pixels = ramBase + 0x80000
        putU32(l1Pa + (inputVa ushr 22) * 4, mmuEntry(l2Input))
        repeat(29) { putU32(l2Input + it * 4, mmuEntry(pixels)) }
        for (offset in 0 until 4096 step 4) putU32(pixels + offset, (80 shl 2) or (128 shl 12) or (80 shl 22))
        frameReq[0] = 7 // frame handle
        frameReq[5] = (320 shl 16) or 180
        frameReq[6] = 0x121
        frameReq[7] = inputVa.toInt()
        frameReq[13] = 1280
        frameReq[16] = (180 shl 16) or 320
        pushHost(bufInHostPa, 3001, frameReq)

        ctx.tick = 2000L
        vpu.write(0x220, 4, 1L)
        ctx.tick = 2064L
        vpu.beforeInstruction(ctx.tick)
        vpu.write(0x21C, 4, 0L)

        // Verify SPS & PPS headers + IDR bitstream returned with nTimestamp == nHandle (frame_id=7)
        val spsDesc = assertNotNull(popMve(bufOutHostPa, bufOutMvePa))
        assertEquals(0x80, spsDesc.second[4])
        assertEquals(7, spsDesc.second[2])
        assertTrue(spsDesc.second[7] in 8..64)
        assertEquals(0x67, ram[(hdrOutPa - ramBase).toInt() + 4].toInt() and 0xFF) // H.264 SPS NAL

        val ppsDesc = assertNotNull(popMve(bufOutHostPa, bufOutMvePa))
        assertEquals(0x80, ppsDesc.second[4])
        assertEquals(0x68, ram[(hdrOutPa - ramBase).toInt() + 64 + 4].toInt() and 0xFF) // H.264 PPS NAL

        val idrDesc = assertNotNull(popMve(bufOutHostPa, bufOutMvePa))
        assertEquals(7, idrDesc.second[0])
        assertEquals(7, idrDesc.second[2], "Firmware asserts nTimestamp == nHandle at section4 0x012082BC")
        assertEquals(0x430, idrDesc.second[4]) // complete sync picture, stream continues
        assertTrue(idrDesc.second[7] > 16)
        assertEquals(0x65, ram[(bitOutPa - ramBase).toInt() + 4].toInt() and 0xFF) // H.264 IDR NAL

        val inReturned = assertNotNull(popMve(bufInHostPa, bufInMvePa))
        assertEquals(3001, inReturned.first)
        assertEquals(7, inReturned.second[0])

        // The same format with a wrapped last row is an input error, never a successful grey IDR.
        frameReq[7] = 0xffff_f000.toInt()
        pushHost(bufInHostPa, 3001, frameReq)
        pushHost(bufOutHostPa, 3002, bitReq)
        ctx.tick += 100
        vpu.write(0x220, 4, 1)
        ctx.tick += 64
        vpu.beforeInstruction(ctx.tick)
        val rejected = assertNotNull(popMve(bufOutHostPa, bufOutMvePa)).second
        assertEquals(0, rejected[7])
        assertEquals(0, rejected[4] and 0x20)
        assertEquals(7, assertNotNull(popMve(bufInHostPa, bufInMvePa)).second[0])
        val responses = generateSequence { popMve(msgInPa, msgOutPa) }.toList()
        assertTrue(responses.any { it.first == 2010 })

        frameReq[7] = inputVa.toInt()
        bitReq[5] = 0
        val guard = (bitOutPa - ramBase).toInt()
        ram.fill(0x5a, guard, guard + 64)
        pushHost(bufInHostPa, 3001, frameReq)
        pushHost(bufOutHostPa, 3002, bitReq)
        ctx.tick += 100
        vpu.write(0x220, 4, 1)
        ctx.tick += 64
        vpu.beforeInstruction(ctx.tick)
        assertEquals(0, assertNotNull(popMve(bufOutHostPa, bufOutMvePa)).second[7])
        assertTrue((guard until guard + 64).all { ram[it] == 0x5a.toByte() })
        assertTrue(generateSequence { popMve(msgInPa, msgOutPa) }.any { it.first == 2010 })

        // A failed SPS cannot be followed by a decodable picture until the guest posts fresh header slots.
        pushHost(msgInPa, 1009, intArrayOf(1, 0))
        pushHost(msgInPa, 1010, intArrayOf(38, 8))
        pushHost(msgInPa, 1001, intArrayOf())
        bitReq[5] = 0x8000
        fun submitWithHeaderCapacity(capacity: Int?): List<Int> {
            if (capacity != null) repeat(2) { i ->
                pushHost(bufOutHostPa, 3002, IntArray(10).apply {
                    this[0] = 0x4B0DA000 + i
                    this[5] = if (i == 0) capacity else 64
                    this[8] = 0x2000_0000 + 64 * i
                })
            }
            pushHost(bufInHostPa, 3001, frameReq)
            pushHost(bufOutHostPa, 3002, bitReq)
            ctx.tick += 100
            vpu.write(0x220, 4, 1)
            ctx.tick += 64
            vpu.beforeInstruction(ctx.tick)
            return generateSequence { popMve(bufOutHostPa, bufOutMvePa) }.map { it.second[7] }.toList()
        }
        val badHeaders = submitWithHeaderCapacity(1)
        assertEquals(3, badHeaders.size)
        assertEquals(0, badHeaders.first())
        assertEquals(0, badHeaders.last())
        assertEquals(listOf(0), submitWithHeaderCapacity(null))
        val recovered = submitWithHeaderCapacity(64)
        assertEquals(3, recovered.size)
        assertTrue(recovered.all { it > 0 })

        // 3. Send STOP -> RSP_STATE_CHANGE(0)
        pushHost(msgInPa, 1002, IntArray(0))
        ctx.tick = 3000L
        vpu.write(0x220, 4, 1L)
        ctx.tick = 3064L
        vpu.beforeInstruction(ctx.tick)
        var sawStopped = false
        while (true) {
            val m = popMve(msgInPa, msgOutPa) ?: break
            if (m.first == 2011 && m.second.isNotEmpty() && m.second[0] == 0) sawStopped = true
        }
        assertTrue(sawStopped, "CODE_STOP must emit RSP_STATE_CHANGE(0)")

        // 4. Verify H.265 VPS/SPS/PPS/IDR NAL headers from MaliV61BitstreamEncoder
        val vps = MaliV61BitstreamEncoder.buildH265Vps(bitDepth10 = true)
        val sps = MaliV61BitstreamEncoder.buildH265Sps(1920, 1080, bitDepth10 = true)
        val pps = MaliV61BitstreamEncoder.buildH265Pps()
        val idr = MaliV61BitstreamEncoder.buildH265IdrSlice(1920, 1080)
        assertEquals(0x40, vps[4].toInt() and 0xFF) // VPS_NUT
        assertEquals(0x42, sps[4].toInt() and 0xFF) // SPS_NUT
        assertEquals(0x44, pps[4].toInt() and 0xFF) // PPS_NUT
        assertEquals(0x26, idr[4].toInt() and 0xFF) // IDR_W_RADL
        assertTrue(vps.size <= 64 && sps.size <= 64 && pps.size <= 64)

        comp.close()
    }

    @Test
    fun decodesEveryMveInputLayoutCodecAndFirmwareMovieSize(@TempDir dir: Path) {
        val ffmpeg = System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
        val available = try { ProcessBuilder(ffmpeg, "-version").start().waitFor() == 0 } catch (_: IOException) { false }
        assumeTrue(available, "External FFmpeg is required for movie layout validation")
        for ((width, height) in listOf(1920 to 1080, 2048 to 1080, 3840 to 2160, 4096 to 2160)) {
            for (packed in listOf(false, true)) for (hevc in listOf(false, true)) withMveMemory(0x1_7000_0000L) { ctx ->
                val yVa = 0x5000_0000L
                val uvVa = 0x5400_0000L
                val stride = if (packed) (if (width > 2048) 0x5600 else 8192) else (width + 255) and 255.inv()
                val yPage = ramBase + 0x270000
                val uvPage = yPage + 0x2000
                // Repeated test card pages keep the fixture at 4 MiB even for a 24 MiB, 4K Y0L2 input.
                fun mapRepeatingPlane(va: Long, size: Int, pa: Long, tables: Long) {
                    for (page in 0 until (size + 4095) / 4096) {
                        val table = tables + page / 1024 * 4096L
                        val pageVa = va + page * 4096L
                        putU32(l1Pa + (pageVa ushr 22) * 4, mmuEntry(table))
                        putU32(table + page % 1024 * 4, mmuEntry(pa))
                    }
                }
                if (packed) {
                    for (i in 0 until 4096 step 8) {
                        val y = if (i / 64 % 2 == 0) 60 else 148
                        putU32(yPage + i, (y shl 2) or (76 shl 12) or (y shl 22))
                        putU32(yPage + i + 4, (y shl 2) or (160 shl 12) or (y shl 22))
                    }
                    mapRepeatingPlane(yVa, stride * (height / 2), yPage, ramBase + 0x250000)
                } else {
                    for (i in 0 until 4096) {
                        ram[(yPage - ramBase).toInt() + i] = (if (i / 16 % 2 == 0) 60 else 148).toByte()
                        ram[(uvPage - ramBase).toInt() + i] = (if (i % 2 == 0) 76 else 160).toByte()
                    }
                    mapRepeatingPlane(yVa, stride * height, yPage, ramBase + 0x250000)
                    mapRepeatingPlane(uvVa, stride * (height / 2), uvPage, ramBase + 0x260000)
                }
                for (page in 0 until 256) putU32(l2OutPa + (0x10 + page) * 4L, mmuEntry(bitOutPa + page * 4096L))
                val component = factory.create(ctx, v3Config())
                val stream = dir.resolve("${width}-${packed}." + if (hevc) "hevc" else "h264")
                component.start()
                try {
                    val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
                    vpu.write(0x204, 4, mmuEntry(l1Pa).toUInt().toLong())
                    vpu.write(0x04, 4, 1)
                    pushHost(msgInPa, 1009, intArrayOf(1, 0))
                    if (!hevc) pushHost(msgInPa, 1010, intArrayOf(38, 8))
                    pushHost(msgInPa, 1001, intArrayOf())
                    val headers = if (hevc) 3 else 2
                    repeat(headers + 1) { i ->
                        pushHost(bufOutHostPa, 3002, IntArray(10).apply {
                            this[0] = if (i == headers) 1 else 0x4B0DA000 + i
                            this[5] = if (i == headers) 0x100000 else 64
                            this[8] = if (i == headers) 0x20010000 else 0x20000000 + i * 64
                        })
                    }
                    pushHost(bufInHostPa, 3001, IntArray(18).apply {
                        this[0] = 1
                        this[5] = width shl 16 or height
                        this[6] = if (packed) 0x121 else 0x201
                        this[7] = yVa.toInt()
                        this[8] = if (packed) 0 else uvVa.toInt()
                        this[13] = stride
                        this[16] = height shl 16 or width
                    })
                    ctx.tick = 1000
                    vpu.write(0x220, 4, 1)
                    ctx.tick += 64
                    vpu.beforeInstruction(ctx.tick)
                    Files.newOutputStream(stream).use { output ->
                        repeat(headers + 1) { i ->
                            val packet = assertNotNull(popMve(bufOutHostPa, bufOutMvePa))
                            val offset = if (i == headers) bitOutPa else hdrOutPa + i * 64
                            val size = packet.second[7]
                            assertTrue(size in 6..(if (i == headers) 0x100000 else 64))
                            output.write(ram, (offset - ramBase).toInt(), size)
                        }
                    }
                } finally {
                    component.close()
                }
                val log = dir.resolve("${stream.fileName}.log")
                val process = ProcessBuilder(ffmpeg, "-v", "error", "-xerror", "-i", stream.toString(),
                    "-vf", "crop=32:16:64:32", "-pix_fmt", if (hevc) "yuv420p10le" else if (packed) "yuvj420p" else "yuv420p",
                    "-f", "rawvideo", "pipe:1").redirectError(log.toFile()).start()
                val raw = process.inputStream.readAllBytes()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), Files.readString(log))
                val samples = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                fun sample(i: Int) = if (hevc) (samples.getShort(2 * i).toInt() and 1023) / 4 else raw[i].toUByte().toInt()
                assertEquals(32 * 16 * 3 / 2 * if (hevc) 2 else 1, raw.size)
                val actual = listOf(sample(8 * 32 + 8), sample(8 * 32 + 24), sample(32 * 16 + 12), sample(32 * 20 + 12))
                assertTrue(actual.zip(listOf(60, 148, 76, 160)).all { (v, ref) -> abs(v - ref) <= 3 }, "$stream decoded=$actual")
                println("[MVE-MATRIX] ${width}x$height layout=${if (packed) "Y0L2" else "NV12"} codec=${if (hevc) "HEVC" else "AVC"} stride=$stride decoded=$actual")
            }
        }
    }


    @Test
    fun rotatingY0l2AndNv12BuffersSurvivePageSplitsPaddingAndReuse(@TempDir dir: Path) {
        // Last 16x16 unit samples the odd final column and final row. Guard bytes in
        // every stride and unmapped physical gap have a different colour from the test card.
        val width = 34
        val height = 18
        val framesPerLayout = 5
        fun luma(frame: Int, x: Int, y: Int) =
            35 + frame * 17 + (x / 16) * 9 + (y / 16) * 11 + (if (x == width - 1) 3 else 0) + (if (y == height - 1) 2 else 0)
        val expectedCb = listOf(66, 184, 98, 172, 84)
        val expectedCr = listOf(177, 80, 186, 64, 164)
        val coded = java.io.ByteArrayOutputStream()
        withMveMemory(0x1_7000_0000L) { ctx ->
            val inputVa = 0x5000_0000L
            val l2InPa = ramBase + 0x4000
            putU32(l1Pa + (inputVa ushr 22) * 4, mmuEntry(l2InPa))
            // VA neighbours are reversed, and separated by an unmapped physical page.
            fun sourcePa(va: Long): Long {
                val offset = va - inputVa
                val page = offset.toInt() ushr 12
                return ramBase + 0x100000 + (63 - page) * 0x2000 + (offset and 0xfff)
            }
            for (page in 0 until 64) {
                putU32(l2InPa + 4 * page, mmuEntry(sourcePa(inputVa + 4096L * page)))
            }
            ram.fill(0x55, 0x100000, 0x180000)
            fun putByte(va: Long, value: Int) {
                ram[(sourcePa(va) - ramBase).toInt()] = value.toByte()
            }
            fun putWord(va: Long, value: Int) {
                for (i in 0..3) putByte(va + i, value ushr (8 * i))
            }

            val component = factory.create(ctx, v3Config())
            try {
                component.start()
                val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
                vpu.write(0x204, 4, mmuEntry(l1Pa).toUInt().toLong())
                vpu.write(0x04, 4, 1L)
                fun ring() {
                    ctx.tick += 1000
                    vpu.write(0x220, 4, 1L)
                    ctx.tick += 64
                    vpu.beforeInstruction(ctx.tick)
                    assertEquals(true, ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE])
                    vpu.write(0x21C, 4, 0L)
                    assertEquals(false, ctx.sink.lines[MaliV61VpuEngine.IRQ_LINE])
                }
                pushHost(msgInPa, 1009, intArrayOf(1, 0, 0))
                pushHost(msgInPa, 1010, intArrayOf(38, 8))
                pushHost(msgInPa, 1001, IntArray(0))
                ring()
                for (i in 0..1) {
                    pushHost(bufOutHostPa, 3002, IntArray(10).apply {
                        this[0] = 0x4B0DA000 + i
                        this[5] = 64
                        this[8] = 0x2000_0000 + i * 64
                    })
                }
                for (packed in listOf(true, false)) {
                    for (frame in 0 until framesPerLayout) {
                        val wideStride = packed && frame == 2
                        val stride = if (wideStride) 0x5600 else if (packed) 256 else 80
                        val slot = frame % 4
                        val base = inputVa + if (wideStride) 0x16000L else slot * 0x8000L
                        // The sampled tile or chroma pair at (8,8) straddles a 4 KiB VA boundary.
                        val yVa = base + if (packed) 4094 - 4 * stride - 32 else 4095 - 8 * stride - 8
                        val uvVa = base + 0x4000 + 4095 - 4 * stride - 8
                        for (y in 0 until height step 2) {
                            for (x in 0 until width step 2) {
                                val cb = expectedCb[frame]
                                val cr = expectedCr[frame]
                                if (packed) {
                                    val tile = yVa + (y / 2) * stride + (x / 2) * 8
                                    for (dy in 0..1) {
                                        val chroma = if (dy == 0) cb else cr
                                        val word = (luma(frame, x, y + dy) shl 2) or
                                            (chroma shl 12) or (luma(frame, x + 1, y + dy) shl 22)
                                        putWord(tile + dy * 4, word)
                                    }
                                } else {
                                    for (dy in 0..1) for (dx in 0..1) {
                                        putByte(yVa + (y + dy) * stride + x + dx, luma(frame, x + dx, y + dy))
                                    }
                                    putByte(uvVa + (y / 2) * stride + x, cb)
                                    putByte(uvVa + (y / 2) * stride + x + 1, cr)
                                }
                            }
                        }
                        val handle = (if (packed) 100 else 200) + frame
                        pushHost(bufInHostPa, 3001, IntArray(18).apply {
                            this[0] = handle
                            this[5] = (width shl 16) or height
                            this[6] = if (packed) 0x121 else 0x201
                            this[7] = yVa.toInt()
                            this[8] = if (packed) 0 else uvVa.toInt()
                            this[13] = stride
                            this[16] = (height shl 16) or width
                        })
                        pushHost(bufOutHostPa, 3002, IntArray(10).apply {
                            this[0] = handle
                            this[5] = 0x8000
                            this[8] = 0x2001_0000
                        })
                        ring()
                        assertEquals(handle, assertNotNull(popMve(bufInHostPa, bufInMvePa)).second[0])
                        val count = if (packed && frame == 0) 3 else 1
                        repeat(count) { i ->
                            val (code, response) = assertNotNull(popMve(bufOutHostPa, bufOutMvePa))
                            assertEquals(3002, code)
                            val isSlice = i == count - 1
                            assertEquals(if (isSlice) 0x430 else 0x80, response[4])
                            val offset = if (isSlice) 0x30000 else 0x20000 + 64 * i
                            assertTrue(response[7] in 6..(if (isSlice) 0x8000 else 64))
                            coded.write(ram, offset, response[7])
                        }
                    }
                }
            } finally {
                component.close()
            }
        }

        val stream = dir.resolve("rotating.h264")
        val decoded = dir.resolve("rotating.yuv")
        val errors = dir.resolve("ffmpeg.log")
        Files.write(stream, coded.toByteArray())
        val ffmpeg = System.getenv("FUJI_FFMPEG")
        val process = try {
            ProcessBuilder(ffmpeg ?: "ffmpeg", "-v", "error", "-xerror", "-i", stream.toString(),
                "-pix_fmt", "yuv420p", "-f", "rawvideo", decoded.toString()).redirectError(errors.toFile()).start()
        } catch (e: IOException) {
            if (ffmpeg != null) throw e
            assumeTrue(false, "FFmpeg unavailable for independent decode: ${e.message}")
            return
        }
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), Files.readString(errors))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val data = Files.readAllBytes(decoded)
        val area = width * height
        val frameBytes = area * 3 / 2
        assertEquals(framesPerLayout * 2 * frameBytes, data.size)
        for (i in 0 until framesPerLayout * 2) {
            val frame = i % framesPerLayout
            fun checkSample(offset: Int, expected: Int) {
                val actual = data[i * frameBytes + offset].toInt() and 255
                assertTrue(abs(actual - expected) <= 2,
                    "frame=$i planeOffset=$offset decoded=$actual source=$expected")
            }
            for (y in listOf(8, height - 1)) for (x in listOf(8, 24, width - 1)) {
                checkSample(y * width + x, luma(frame, x, y))
                val chromaOffset = (y / 2) * (width / 2) + x / 2
                checkSample(area + chromaOffset, expectedCb[frame])
                checkSample(area * 5 / 4 + chromaOffset, expectedCr[frame])
            }
        }
    }

    @Test
    fun maliV61ActiveRecordingPhaseLocksIspFramePeriodToFirmwareFpsQ16() {
        assertEquals(900_900L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(3_928_227L)) // 59.94 fps exact
        assertEquals(900_000L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(60L shl 16))
        assertEquals(1_080_000L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(50L shl 16))
        assertEquals(1_801_800L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(1_964_113L)) // 29.97 fps exact
        assertEquals(1_800_000L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(30L shl 16))
        assertEquals(2_160_000L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(25L shl 16))
        assertEquals(2_252_250L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(1_571_291L)) // 23.976 fps exact
        assertEquals(2_250_000L, MaliV61VpuEngine.framePeriodTicksForFpsQ16(24L shl 16))

        val ramBase = 0x1000_0000L
        val ram = ByteArray(0x40000)
        fun putU32(pa: Long, v: Int) {
            val o = (pa - ramBase).toInt()
            ram[o] = (v and 0xFF).toByte()
            ram[o + 1] = ((v ushr 8) and 0xFF).toByte()
            ram[o + 2] = ((v ushr 16) and 0xFF).toByte()
            ram[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putU16(pa: Long, v: Int) {
            val o = (pa - ramBase).toInt()
            ram[o] = (v and 0xFF).toByte()
            ram[o + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        fun getU16(pa: Long): Int {
            val o = (pa - ramBase).toInt()
            return (ram[o].toInt() and 0xFF) or ((ram[o + 1].toInt() and 0xFF) shl 8)
        }
        val ctx = TestContext()
        ctx.ports.register(
            "cpu",
            "cpu.memory",
            DmaMemory::class.java,
            object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(address >= ramBase && address + size <= ramBase + ram.size)
                }
                override fun read(address: Long, destination: java.nio.ByteBuffer) {
                    val o = (address - ramBase).toInt()
                    destination.put(ram, o, destination.remaining())
                }
                override fun write(address: Long, source: java.nio.ByteBuffer) {
                    val o = (address - ramBase).toInt()
                    source.get(ram, o, source.remaining())
                }
            },
        )
        val l1Pa = ramBase + 0x0000L
        val l2CommPa = ramBase + 0x1000L
        val msgInPa = ramBase + 0x10000L
        val msgOutPa = ramBase + 0x11000L
        val bufInHostPa = ramBase + 0x12000L
        val bufInMvePa = ramBase + 0x13000L
        val bufOutHostPa = ramBase + 0x14000L
        val bufOutMvePa = ramBase + 0x15000L
        fun mmuEntry(pa: Long): Int = (((pa ushr 10) and 0x3FFF_FFFCL) or 3L).toInt()
        putU32(l1Pa + (0x1007_9000L ushr 22) * 4L, mmuEntry(l2CommPa))
        putU32(l2CommPa + 0x79 * 4L, mmuEntry(msgInPa))
        putU32(l2CommPa + 0x7A * 4L, mmuEntry(msgOutPa))
        putU32(l2CommPa + 0x7B * 4L, mmuEntry(bufInHostPa))
        putU32(l2CommPa + 0x7C * 4L, mmuEntry(bufInMvePa))
        putU32(l2CommPa + 0x7D * 4L, mmuEntry(bufOutHostPa))
        putU32(l2CommPa + 0x7E * 4L, mmuEntry(bufOutMvePa))
        fun pushHost(hostPa: Long, code: Int, words: IntArray) {
            var pos = getU16(hostPa + 2L)
            putU32(hostPa + 16L + pos * 4L, (code and 0xFFFF) or ((words.size * 4) shl 16))
            pos = (pos + 1) % 1020
            for (w in words) {
                putU32(hostPa + 16L + pos * 4L, w)
                pos = (pos + 1) % 1020
            }
            putU16(hostPa + 2L, pos)
        }

        val comp = factory.create(ctx, v3Config() + ("autoFrameIrqs" to true))
        comp.start()
        val core = assertIs<TickAwareMmioDevice>(ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java))
        val vpu = assertIs<TickAwareMmioDevice>(ctx.ports.find(MaliV61VpuEngine.PORT_ID, MmioDevice::class.java))
        vpu.write(0x204, 4, mmuEntry(l1Pa).toLong() and 0xFFFF_FFFFL)
        vpu.write(0x04, 4, 1L)

        // Idle LiveView stays at AUTO_FRAME_IRQ_PERIOD (2,000,000 ticks)
        assertEquals(SocIspFactory.AUTO_FRAME_IRQ_PERIOD, core.nextDeadline())

        // Start 59.94 fps recording (REQ_CODE_GO): nextAutoIrqTick clamps immediately to <= tick + 900,900
        pushHost(msgInPa, 1001, IntArray(0))
        ctx.tick = 100_000L
        vpu.write(0x220, 4, 1L)
        ctx.tick = 100_064L
        vpu.beforeInstruction(ctx.tick)
        val firstDue = assertNotNull(core.nextDeadline())
        assertEquals(100_064L + 900_900L, firstDue)

        // Positive Dynarmic quantum overshoot (+250 ticks) stays phase-locked to firstDue + 900,900
        ctx.tick = firstDue + 250L
        core.beforeInstruction(ctx.tick)
        assertEquals(firstDue + 900_900L, core.nextDeadline())

        // Reconfigure fpsQ16 to 29.97 fps while running -> 1,801,800 ticks/frame
        pushHost(bufInHostPa, 3003, intArrayOf(9, 1_964_113))
        vpu.write(0x220, 4, 1L)
        ctx.tick += 64L
        vpu.beforeInstruction(ctx.tick)
        val due2 = assertNotNull(core.nextDeadline())
        ctx.tick = due2
        core.beforeInstruction(ctx.tick)
        assertEquals(due2 + 1_801_800L, core.nextDeadline())

        // Stop recording (REQ_CODE_STOP) -> reverts to AUTO_FRAME_IRQ_PERIOD (2,000,000 ticks)
        pushHost(msgInPa, 1002, IntArray(0))
        vpu.write(0x220, 4, 1L)
        ctx.tick += 64L
        vpu.beforeInstruction(ctx.tick)
        val due3 = assertNotNull(core.nextDeadline())
        ctx.tick = due3
        core.beforeInstruction(ctx.tick)
        assertEquals(due3 + SocIspFactory.AUTO_FRAME_IRQ_PERIOD, core.nextDeadline())

        comp.close()
    }
}
