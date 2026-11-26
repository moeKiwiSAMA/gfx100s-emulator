package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class SocIspFactoryTest {
    private class StubContext(private val root: Path) : ComponentContext {
        var currentTick = 100L
        private val portMap = LinkedHashMap<String, Any>()
        override val directory: Path get() = root
        override val clock = EmulationClock { currentTick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = Unit
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(name !in portMap) { "Duplicate port $name" }
                portMap[name] = port
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? {
                val p = portMap[name] ?: return null
                return type.cast(p)
            }

            override fun removeOwner(owner: String) = Unit
        }
        override fun dataPath(relative: String): Path = root.resolve(relative).normalize()
    }

    private class StubGicSink : CoupledEdgeInterruptSink {
        val enabledLines = mutableSetOf(SocIspFactory.IRQ_106, SocIspFactory.IRQ_296)
        val states = mutableMapOf(
            SocIspFactory.IRQ_106 to EdgeInterruptState(edge = true, high = false, pending = false),
            SocIspFactory.IRQ_296 to EdgeInterruptState(edge = false, high = false, pending = false),
        )
        val pulseCalls = mutableListOf<Pair<Int, Int>>()
        val lineTransitions = mutableListOf<Pair<Int, Boolean>>()

        override fun lineEnabled(irq: Int) = irq in enabledLines
        override fun edgeState(irq: Int) = states.getValue(irq)
        override fun setLine(irq: Int, high: Boolean) {
            lineTransitions += irq to high
            val prev = states.getValue(irq)
            states[irq] = prev.copy(high = high, pending = high)
        }

        override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) {
            pulseCalls += irq to targetMask
            latchDevice()
            val prev = states.getValue(irq)
            states[irq] = prev.copy(high = false, pending = true)
        }
    }

    @Test
    fun receiverRestartSynchronizesBeforeFullFrameReadout(@TempDir tempRoot: Path) {
        for (silicon in listOf(false, true)) {
            val ctx = StubContext(tempRoot)
            val gic = StubGicSink().apply {
                enabledLines.clear()
                enabledLines += SocIspFactory.IRQ_296
                for (line in listOf(SocIspFactory.IRQ_297, SocIspFactory.IRQ_298)) {
                    states[line] = EdgeInterruptState(edge = false, high = false, pending = false)
                }
            }
            ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
            val isp = SocIspFactory().create(ctx, mapOf(
                "profile" to SocIspFactory.PROFILE_AUTO_FRAME_IRQS,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "slvsEcSilicon" to silicon,
            )) as StatefulComponent
            try {
                isp.start()
                val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
                val ccdif = checkNotNull(ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java))
                val receiver = checkNotNull(ctx.ports.find(SocIspFactory.PORT_CONFIG_69100, MmioDevice::class.java))
                ccdif.write(0x018, 4, 0x00120b8f)
                ctx.currentTick = SocIspFactory.AUTO_FRAME_IRQ_PERIOD
                core.beforeInstruction(ctx.currentTick)
                val frameEnd = ctx.currentTick + SocIspFactory.STILL_FRAME_IRQ_PERIOD
                assertEquals(frameEnd, core.nextDeadline())

                receiver.write(0x00c, 4, 3L)
                val firstSync = ctx.currentTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD
                ctx.currentTick += 100L
                receiver.write(0x00c, 4, 3L)
                assertEquals(firstSync, core.nextDeadline())
                val armed = isp.saveSnapshot()
                receiver.write(0x00c, 4, 0L)
                assertEquals(frameEnd, core.nextDeadline())

                isp.restoreSnapshot(armed)
                assertEquals(firstSync, core.nextDeadline())
                core.beforeInstruction(firstSync - 1)
                assertFalse(gic.edgeState(SocIspFactory.IRQ_296).high)
                ctx.currentTick = firstSync
                core.beforeInstruction(firstSync)
                assertEquals(3L, receiver.read(0x008, 4))
                assertTrue(gic.edgeState(SocIspFactory.IRQ_296).high)
                receiver.write(0x008, 4, 3L)
                assertTrue(checkNotNull(core.nextDeadline()) > firstSync)
            } finally {
                isp.close()
            }
        }
    }

    @Test
    fun disabledProfilePublishesNoPortsOrInterruptsAndRejectsActiveSnapshot(@TempDir tempRoot: Path) {
        val ctx = StubContext(tempRoot)
        val factory = SocIspFactory()
        val disabled = factory.create(
            ctx,
            mapOf("profile" to "disabled", "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        ) as StatefulComponent
        assertTrue(disabled.additionalMmioPorts().isEmpty())
        assertTrue(disabled.interruptRoutes().isEmpty())
        assertNull(ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java))
        assertNull(ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_DMA, LiveViewDmaPort::class.java))
        assertNull(ctx.ports.find(SocIspFactory.PORT_INTERRUPT_CONTROL, IspInterruptPort::class.java))
        disabled.start()
        val snap = disabled.saveSnapshot()
        disabled.restoreSnapshot(snap)
        assertFails { disabled.restoreSnapshot(snap + ("irq296Pending" to true)) }
        disabled.close()
    }

    @Test
    fun all17PortsEnforceDirectionalRulesHolesIrqsAndLiveViewDma(@TempDir tempRoot: Path) {
        val ctx = StubContext(tempRoot)
        val gic = StubGicSink()
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)

        val surfaceBytes = ByteBuffer.allocate(SocIspFactory.RESULT_SURFACE_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (off in 0 until capacity() step 4) putInt(off, 0x22000000 + off)
        }.array()
        Files.write(tempRoot.resolve("surface.bin"), surfaceBytes)
        val surfaceSha = MessageDigest.getInstance("SHA-256").digest(surfaceBytes).joinToString("") { "%02x".format(it) }

        val picRam = ByteArray(16 * 8 * 2)
        val picAddr = 0x8999c000L
        val dmaMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                if (address == 0x01871ad0L && size == 4L && !write) return
                require(write && address == picAddr && size == picRam.size.toLong()) {
                    "Unexpected DMA check 0x${address.toString(16)} size=$size write=$write"
                }
            }

            override fun read(address: Long, destination: ByteBuffer) {
                require(address == 0x01871ad0L && destination.remaining() == 4)
                destination.order(ByteOrder.LITTLE_ENDIAN).putInt(0x011b7f14)
            }

            override fun write(address: Long, source: ByteBuffer) {
                check(address, source.remaining().toLong(), true)
                source.get(picRam, 0, source.remaining())
            }
        }
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, dmaMem)
        ctx.ports.register("cmos", "cmos", SensorPort::class.java, object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                val n = 16 * 8 * 2
                for (i in 0 until 16 * 8) {
                    destination.putShort(0x4000.toShort())
                }
                return n
            }
        })

        val factory = SocIspFactory()
        val isp = factory.create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE,
                "initialFill" to 0L,
                "resultSurfaceFile" to "surface.bin",
                "resultSurfaceSha256" to surfaceSha,
            ),
        ) as StatefulComponent
        assertEquals(SocIspFactory.ALL_MMIO_PORTS, isp.additionalMmioPorts())
        assertEquals(listOf(106, 296), isp.interruptRoutes().flatMap { it.lines.toList() })
        isp.start()

        val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java)!!
        val b50 = ctx.ports.find(SocIspFactory.PORT_BANK_50000, MmioDevice::class.java)!!
        val lut51 = ctx.ports.find(SocIspFactory.PORT_LUT_51000, MmioDevice::class.java)!!
        val lut55 = ctx.ports.find(SocIspFactory.PORT_LUT_55000, MmioDevice::class.java)!!
        val lut59 = ctx.ports.find(SocIspFactory.PORT_LUT_59000, MmioDevice::class.java)!!
        val b68 = ctx.ports.find(SocIspFactory.PORT_BANK_68000, MmioDevice::class.java)!!
        val c690 = ctx.ports.find(SocIspFactory.PORT_CONFIG_69000, MmioDevice::class.java)!!
        val c691 = ctx.ports.find(SocIspFactory.PORT_CONFIG_69100, MmioDevice::class.java)!!
        val c6b = ctx.ports.find(SocIspFactory.PORT_CONFIG_6B000, MmioDevice::class.java)!!
        val lut6d = ctx.ports.find(SocIspFactory.PORT_LUT_6D000, MmioDevice::class.java)!!
        val b6e = ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java)!!
        val surface = ctx.ports.find(SocIspFactory.PORT_RESULT_SURFACE, MmioDevice::class.java)!!
        val stats0 = ctx.ports.find(SocIspFactory.PORT_STATS_0, MmioDevice::class.java)!!
        val stats1 = ctx.ports.find(SocIspFactory.PORT_STATS_1, MmioDevice::class.java)!!
        val lv0 = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_0, MmioDevice::class.java)!!
        val lv1 = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_1, MmioDevice::class.java)!!
        val lv2 = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_2, MmioDevice::class.java)!!
        val lvDma = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_DMA, LiveViewDmaPort::class.java)!!
        val irqCtl = ctx.ports.find(SocIspFactory.PORT_INTERRUPT_CONTROL, IspInterruptPort::class.java)!!

        // Core bank + IRQ 106
        assertEquals(1L, core.read(0x000, 4))
        assertEquals(0x10000L, core.read(0x2e8, 4))
        assertFails { core.write(0x2e8, 4, 0L) }
        assertFails { core.read(0x2ec, 4) } // OOB
        core.write(0x108, 4, 0x00000005L) // S4 0x011BD3A8 filter config
        assertEquals(0x00000005L, core.read(0x108, 4))
        core.write(0x2d4, 4, 0x2c4b0000L) // S4 0x011BCA24 PDAF primary DMA base
        assertEquals(0x2c4b0000L, core.read(0x2d4, 4))
        core.write(0x134, 4, 0x80ff1L)
        assertEquals(0x80ff1L, core.read(0x134, 4))

        irqCtl.signalInterrupt(106, false, 0x41L)
        assertEquals(0x41L, core.read(0x00c, 4))
        core.write(0x00c, 4, 0x41L)
        assertEquals(0L, core.read(0x00c, 4))
        assertEquals(1L, irqCtl.irq106State()["completedCycles"])

        // Bank 50000 & LUTs
        b50.write(0x000, 4, 0L)
        assertEquals(1L, b50.read(0x000, 4))
        b50.write(0x248, 4, 0x12345678L)
        assertEquals(0x12345678L, b50.read(0x248, 4))
        b50.write(0x1004, 4, 0xabcL)
        assertFails { b50.read(0x1004, 4) } // write-only
        assertFails { b50.write(0x050, 4, 0L) } // hole

        lut51.write(0, 4, 0x1111L)
        assertFails { lut51.read(0, 4) }
        lut55.write(0, 4, 0x2222L)
        assertFails { lut55.read(0, 4) }
        lut59.write(0, 4, 0x3333L)
        assertFails { lut59.write(0x900, 4, 1L) } // hole
        assertFails { lut59.read(0, 4) }

        // Bank 68000, 69000, 69100 (+IRQ 296), 6b000, 6d000, 6e000
        b68.write(0xd00, 4, 0x5555L)
        assertFails { b68.read(0, 4) }
        assertFails { b68.write(0x100, 4, 1L) }

        c690.write(0x14, 4, 0x7777L)
        assertEquals(0x7777L, c690.read(0x14, 4))
        assertFails { c690.read(0x04, 4) }

        c691.write(0x00c, 4, 3L)
        assertFails { c691.write(0x044, 4, 1L) } // RO
        irqCtl.signalInterrupt(296, true, 1L)
        assertEquals(2L, c691.read(0x008, 4))
        c691.write(0x008, 4, 2L)
        assertEquals(0L, c691.read(0x008, 4))
        assertEquals(false, gic.edgeState(296).high)

        c6b.write(0xf0, 4, 0x8888L)
        assertEquals(0x8888L, c6b.read(0xf0, 4))
        assertFails { c6b.read(0x18, 4) }

        lut6d.write(0xffc, 4, 0x9999L)
        assertEquals(0x9999L, lut6d.read(0xffc, 4))

        assertEquals(1L, b6e.read(0x000, 4))
        b6e.write(0x004, 4, 0x00000012L)
        assertEquals(0x00000012L, b6e.read(0x004, 4))
        b6e.write(0x014, 4, 0x002c0f30L)
        assertFails { b6e.write(0x014, 4, 0x002c0000L) } // lo=0 invalid
        b6e.write(0x050, 4, 0x40000000L)
        assertEquals(0x40000000L, b6e.read(0x050, 4))
        b6e.write(0x070, 4, 0x00000002L)
        assertEquals(0x00000002L, b6e.read(0x070, 4))
        assertFails { b6e.read(0x028, 4) } // unmodeled gap
        assertFails { b6e.read(0x074, 4) } // OOB

        // Result surface & stats
        assertEquals(0x22000000L, surface.read(0, 4))
        assertEquals(0x22001940L, surface.read(0x1940, 4))
        assertFails { surface.read(0x1140, 4) } // hole
        assertFails { surface.write(0, 4, 1L) }

        assertEquals(0L, stats0.read(0, 1))
        assertEquals(0L, stats1.read(0xffe, 2))
        assertFails { stats0.write(0, 4, 1L) }

        // Live-view channels & DMA pump
        assertEquals(1L, lv0.read(0x000, 4))
        assertEquals(0x100L, lv0.read(0x0dc, 4))
        lv0.write(0x028, 4, 0x10001000L)
        assertFails { lv0.read(0x028, 4) } // write-only
        assertFails { lv0.read(0x03c, 4) } // hole
        assertEquals(0x100L, lv2.read(0x028, 4))
        lv2.write(0x024, 4, 0x10000000L)
        assertEquals(0L, lv2.read(0x028, 4))

        lv1.write(0x018, 4, (7L shl 16) or 15L) // 16x8
        lv1.write(0x0c4, 4, picAddr)
        lv1.write(0x0d0, 4, 32L)
        lv1.write(0x0d4, 4, 0x10000000L)
        assertTrue(picRam.any { it != 0.toByte() })
        picRam.fill(0)
        assertTrue(lvDma.pumpLiveViewDma(200L))
        assertTrue(picRam.any { it != 0.toByte() })

        // Snapshot roundtrip
        val saved = isp.saveSnapshot()
        val ctx2 = StubContext(tempRoot)
        ctx2.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val restored = factory.create(
            ctx2,
            mapOf(
                "profile" to SocIspFactory.PROFILE,
                "initialFill" to 0L,
                "resultSurfaceFile" to "surface.bin",
                "resultSurfaceSha256" to surfaceSha,
            ),
        ) as StatefulComponent
        restored.start()
        restored.restoreSnapshot(saved)
        assertEquals(saved, restored.saveSnapshot())
        assertFails { restored.restoreSnapshot(saved + ("resultSurfaceSha256" to "0".repeat(64))) }

        isp.close()
        restored.close()
    }

    @Test
    fun autoFrameIrqsProfileSchedulesEnabledIspIrqsWhileExperimentalProfileRemainsPassive(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()

        // Classic experimental profile must remain strictly passive (no deadlines, no auto IRQs).
        val passiveCtx = StubContext(tempRoot)
        val passiveGic = StubGicSink()
        passiveCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, passiveGic)
        val passiveIsp = factory.create(
            passiveCtx,
            mapOf("profile" to SocIspFactory.PROFILE, "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        )
        passiveIsp.start()
        passiveGic.lineTransitions.clear()
        val passiveCore = passiveCtx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
        assertNull(passiveCore.nextDeadline())
        assertEquals(Long.MAX_VALUE, passiveCore.nextInterestedTick())
        passiveCore.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD * 4)
        assertTrue(passiveGic.pulseCalls.isEmpty())
        assertTrue(passiveGic.lineTransitions.isEmpty())
        passiveIsp.close()

        // Opt-in auto-frame-irqs profile schedules enabled 296, 100/102/108, and 106 (with 1-frame arming delay).
        val autoCtx = StubContext(tempRoot)
        val autoGic = StubGicSink().apply {
            enabledLines.clear()
            for (line in listOf(100, 102, 106, 107, 108, 109)) {
                states[line] = EdgeInterruptState(edge = true, high = false, pending = false)
            }
            states[SocIspFactory.IRQ_296] = EdgeInterruptState(edge = false, high = false, pending = false)
        }
        autoCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, autoGic)
        val autoIsp = factory.create(
            autoCtx,
            mapOf("profile" to SocIspFactory.PROFILE_AUTO_FRAME_IRQS, "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        )
        autoIsp.start()
        val routes = autoIsp.interruptRoutes().flatMap { it.lines.toList() }.toSet()
        assertEquals(setOf(100, 102, 106, 107, 108, 109, 296), routes)

        val core = autoCtx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
        val cfg69100 = autoCtx.ports.find(SocIspFactory.PORT_CONFIG_69100, MmioDevice::class.java)!!
        assertEquals(SocIspFactory.AUTO_FRAME_IRQ_PERIOD, core.nextDeadline())

        // Sensor restart clears both status bits before enabling the receiver.
        val initialTransitions = autoGic.lineTransitions.size
        cfg69100.write(0x008, 4, 3L)
        assertEquals(0L, cfg69100.read(0x008, 4))
        assertEquals(initialTransitions, autoGic.lineTransitions.size)

        // Enable IRQ 296 in GIC + 0xfff6910c bit1 -> fires on next frame tick
        cfg69100.write(0x00c, 4, 2L)
        autoGic.enabledLines += SocIspFactory.IRQ_296
        core.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD)
        assertEquals(2L, cfg69100.read(0x008, 4))
        assertTrue(autoGic.lineTransitions.contains(SocIspFactory.IRQ_296 to true))

        // W1C ack clears 296; arming both bit0+bit1 (0x3, STILL cold-boot) latches 0x3 and clears on W1C 0x3
        cfg69100.write(0x008, 4, 2L)
        assertEquals(0L, cfg69100.read(0x008, 4))
        assertEquals(false, autoGic.edgeState(SocIspFactory.IRQ_296).high)
        cfg69100.write(0x00c, 4, 3L)
        core.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD * 2)
        assertEquals(3L, cfg69100.read(0x008, 4))
        assertEquals(true, autoGic.edgeState(SocIspFactory.IRQ_296).high)
        cfg69100.write(0x008, 4, 1L)
        assertEquals(2L, cfg69100.read(0x008, 4))
        assertTrue(autoGic.edgeState(SocIspFactory.IRQ_296).high)
        cfg69100.write(0x008, 4, 3L)
        cfg69100.write(0x008, 4, 3L)
        assertEquals(0L, cfg69100.read(0x008, 4))
        assertEquals(false, autoGic.edgeState(SocIspFactory.IRQ_296).high)

        // Enable IRQ 100 and IRQ 106 -> 100 pulses immediately, 106 arms then fires next frame with mask 0x01
        autoGic.enabledLines.remove(SocIspFactory.IRQ_296)
        autoGic.enabledLines += listOf(SocIspFactory.IRQ_100, SocIspFactory.IRQ_106)
        core.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD * 3)
        assertTrue(autoGic.lineTransitions.contains(SocIspFactory.IRQ_100 to true))
        assertTrue(autoGic.pulseCalls.isEmpty(), "IRQ 106 should arm for one frame before firing")

        // Clear 100 pending, advance one frame -> IRQ 106 fires with mask 0x01
        autoGic.states[SocIspFactory.IRQ_100] = EdgeInterruptState(edge = true, high = false, pending = false)
        core.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD * 4)
        assertEquals(listOf(SocIspFactory.IRQ_106 to 2), autoGic.pulseCalls)
        assertEquals(0x01L, core.read(0x00c, 4))

        // Ack 106 and enable IRQ 108 -> next frame latches mask 0x41
        core.write(0x00c, 4, 0x01L)
        autoGic.states[SocIspFactory.IRQ_106] = EdgeInterruptState(edge = true, high = false, pending = false)
        autoGic.enabledLines += SocIspFactory.IRQ_108
        core.beforeInstruction(SocIspFactory.AUTO_FRAME_IRQ_PERIOD * 5)
        assertEquals(0x41L, core.read(0x00c, 4))
        assertTrue(autoGic.lineTransitions.contains(SocIspFactory.IRQ_108 to true))

        autoIsp.close()
    }

    @Test
    fun profileV2ExtendsResultSurfaceSelectors18To32AndPromotesMonotonically(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()
        val v1Ctx = StubContext(tempRoot)
        v1Ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val v1 = factory.create(
            v1Ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
            ),
        ) as StatefulComponent
        v1.start()
        val v1Snap = v1.saveSnapshot()

        val v2Ctx = StubContext(tempRoot)
        v2Ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val v2 = factory.create(
            v2Ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V2,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
            ),
        ) as StatefulComponent
        v2.start()
        assertTrue(SocIspFactory.PORT_RESULT_SURFACE_V2 in v2.additionalMmioPorts())
        assertTrue(SocIspFactory.PORT_RESULT_SURFACE_1_V2 in v2.additionalMmioPorts())
        assertTrue(SocIspFactory.PORT_STATS_HEAD_0_V2 in v2.additionalMmioPorts())
        assertTrue(SocIspFactory.PORT_STATS_HEAD_1_V2 in v2.additionalMmioPorts())
        assertFalse(SocIspFactory.PORT_RESULT_SURFACE in v2.additionalMmioPorts())
        v2.restoreSnapshot(v1Snap)

        val surfaceV2 = v2Ctx.ports.find(SocIspFactory.PORT_RESULT_SURFACE_V2, MmioDevice::class.java)!!
        val surface1V2 = v2Ctx.ports.find(SocIspFactory.PORT_RESULT_SURFACE_1_V2, MmioDevice::class.java)!!
        for (surf in listOf(surfaceV2, surface1V2)) {
            assertEquals(0L, surf.read(0x0000L, 4))
            // Selectors 14..15 (offsets 0x1140..0x193c, Bank 0 0xfff121a0 / Bank 1 0xfff191a0)
            assertEquals(0L, surf.read(0x1140L, 4))
            assertEquals(0L, surf.read(0x193cL, 4))
            assertEquals(0L, surf.read(0x1940L, 4))
            // Selector 16 word 1 (Bank 0 0xfff129a4 / Bank 1 0xfff199a4 -> offset 0x1944),
            // selector 18 (offsets 0x1980..0x199c), and selector 32 end (0x4f9c)
            assertEquals(0L, surf.read(0x1944L, 4))
            assertEquals(0L, surf.read(0x1980L, 4))
            assertEquals(0L, surf.read(0x1984L, 4))
            assertEquals(0L, surf.read(0x4f9cL, 4))
            // Out-of-range and all writes strictly refuse
            assertFails { surf.read(0x4fa0L, 4) }
            assertFails { surf.write(0x1140L, 4, 1L) }
            assertFails { surf.write(0x1984L, 4, 1L) }
        }
        val statsHead0 = v2Ctx.ports.find(SocIspFactory.PORT_STATS_HEAD_0_V2, MmioDevice::class.java)!!
        val statsHead1 = v2Ctx.ports.find(SocIspFactory.PORT_STATS_HEAD_1_V2, MmioDevice::class.java)!!
        for (head in listOf(statsHead0, statsHead1)) {
            assertEquals(0L, head.read(0x0000L, 4))
            assertEquals(0L, head.read(0x0400L, 1))
            assertEquals(0L, head.read(0x105eL, 2))
            assertFails { head.read(0x1060L, 1) }
            assertFails { head.write(0x0400L, 4, 1L) }
        }

        // Downgrade from v2 snapshot into v1 component is strictly refused
        val v2Snap = v2.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { v1.restoreSnapshot(v2Snap) }

        v1.close()
        v2.close()
    }

    @Test
    fun profileV2WithAutoFrameIrqsCombinesExtendedSurfaceAndPeriodicIrqsWithLegacySnapshotCompat(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()

        // Disabled or v1 cannot opt into autoFrameIrqs
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                StubContext(tempRoot),
                mapOf(
                    "profile" to "disabled",
                    "initialFill" to 0L,
                    "resultSurfaceFile" to "",
                    "resultSurfaceSha256" to "",
                    "autoFrameIrqs" to true,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                StubContext(tempRoot),
                mapOf(
                    "profile" to SocIspFactory.PROFILE,
                    "initialFill" to 0L,
                    "resultSurfaceFile" to "",
                    "resultSurfaceSha256" to "",
                    "autoFrameIrqs" to true,
                ),
            )
        }

        // Capture a legacy 4-key snapshot (without autoFrameIrqs key) from PROFILE_AUTO_FRAME_IRQS
        val legacyCtx = StubContext(tempRoot)
        legacyCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val legacy = factory.create(
            legacyCtx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_AUTO_FRAME_IRQS,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
            ),
        ) as StatefulComponent
        legacy.start()
        val legacySnap = legacy.saveSnapshot().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        legacySnap["scalars"] = (legacySnap["scalars"] as Map<String, Any?>) - "autoFrameIrqs" - "slvsEcSilicon"
        legacy.close()

        // Upgrade into PROFILE_V2 + autoFrameIrqs=true
        val ctx = StubContext(tempRoot)
        val gic = StubGicSink().apply {
            enabledLines.clear()
            for (line in listOf(100, 102, 106, 107, 108, 109)) {
                states[line] = EdgeInterruptState(edge = true, high = false, pending = false)
            }
            states[SocIspFactory.IRQ_296] = EdgeInterruptState(edge = false, high = false, pending = false)
        }
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        val v2Auto = factory.create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V2,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "autoFrameIrqs" to true,
            ),
        ) as StatefulComponent
        v2Auto.start()
        assertTrue(SocIspFactory.PORT_RESULT_SURFACE_V2 in v2Auto.additionalMmioPorts())
        assertEquals(
            setOf(100, 102, 106, 107, 108, 109, 296),
            v2Auto.interruptRoutes().flatMap { it.lines.toList() }.toSet(),
        )
        v2Auto.restoreSnapshot(legacySnap)

        val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
        val cfg69100 = ctx.ports.find(SocIspFactory.PORT_CONFIG_69100, MmioDevice::class.java)!!
        val expectedNextTick = ctx.clock.ticks() + SocIspFactory.AUTO_FRAME_IRQ_PERIOD
        assertEquals(expectedNextTick, core.nextDeadline())
        cfg69100.write(0x00c, 4, 2L)
        gic.enabledLines += SocIspFactory.IRQ_296
        core.beforeInstruction(expectedNextTick)
        assertTrue(gic.lineTransitions.contains(SocIspFactory.IRQ_296 to true))

        // Round-trip v2+autoFrameIrqs snapshot cleanly, and refuse downgrade to autoFrameIrqs=false
        val v2AutoSnap = v2Auto.saveSnapshot()
        v2Auto.restoreSnapshot(v2AutoSnap)

        val passiveCtx = StubContext(tempRoot)
        passiveCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val v2Passive = factory.create(
            passiveCtx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V2,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "autoFrameIrqs" to false,
            ),
        ) as StatefulComponent
        v2Passive.start()
        assertFailsWith<IllegalArgumentException> { v2Passive.restoreSnapshot(v2AutoSnap) }

        v2Passive.close()
        v2Auto.close()
    }

    @Test
    fun coreBankUnblocksFilterConfigAndPdafDmaBaseBank6e000CompleteAndLegacySnapshotRestores(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()
        val ctx = StubContext(tempRoot)
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val isp = factory.create(
            ctx,
            mapOf("profile" to SocIspFactory.PROFILE, "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        ) as StatefulComponent
        isp.start()

        val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java)!!
        val b6e = ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java)!!

        // [0x108..0x120] channel/filter config (7 words) + [0x2d4..0x2e0] PDAF 34-bit DMA bases (4 words)
        for ((i, off) in (0x108..0x120 step 4).withIndex()) {
            val v = (i + 1).toLong()
            core.write(off.toLong(), 4, v)
            assertEquals(v, core.read(off.toLong(), 4))
        }
        core.write(0x2d4, 4, 0x00000001L) // primary hi[1:0]
        core.write(0x2d8, 4, 0x2c4b0000L) // primary lo
        core.write(0x2dc, 4, 0x00000000L) // secondary hi[1:0]
        core.write(0x2e0, 4, 0x2c4b9000L) // secondary lo
        assertEquals(0x2c4b0000L, core.read(0x2d8, 4))
        assertEquals(0x2c4b9000L, core.read(0x2e0, 4))

        // All 22 CCDIF 0xFFF6E000..0xFFF6E070 words
        val expected6e = mapOf(
            0x004 to 0x01L,
            0x00c to 0x0f00_1400L,
            0x010 to 0x0f00_1400L,
            0x01c to 0x0000_0003L,
            0x040 to 0x0000_0001L,
            0x044 to 0x0000_2000L,
            0x048 to 0x0000_0400L,
            0x04c to 0x0000_0001L,
            0x050 to 0x4000_0000L,
            0x054 to 0x0000_0000L,
            0x058 to 0x4100_0000L,
            0x05c to 0x0000_0004L,
            0x060 to 0x0000_0001L,
            0x068 to 0x0000_0010L,
            0x06c to 0x0000_0020L,
            0x070 to 0x0000_0002L,
        )
        for ((off, v) in expected6e) {
            b6e.write(off.toLong(), 4, v)
            assertEquals(v, b6e.read(off.toLong(), 4))
        }
        // +0x008 bit 8 (0x100) W1C clears bit 8 while retaining non-bit8 control bits
        b6e.write(0x008, 4, 0x0000_0105L)
        assertEquals(0x0000_0005L, b6e.read(0x008, 4))

        // Legacy 176-word coreWords + 8-word bank6e000Words + 4-key scalars snapshot restores cleanly
        val snap = isp.saveSnapshot().toMutableMap()
        val legacyCore = LongArray(IspCoreOffsets.CORE_70000_LEGACY_OFFSETS.size)
        legacyCore[IspCoreOffsets.CORE_70000_LEGACY_OFFSETS.indexOf(0x134)] = 0x00abcdefL
        legacyCore[IspCoreOffsets.CORE_70000_LEGACY_OFFSETS.indexOf(0x2e8)] = 0x10000L
        val legacy6e = LongArray(IspCoreOffsets.BANK_6E000_LEGACY_OFFSETS.size)
        legacy6e[IspCoreOffsets.BANK_6E000_LEGACY_OFFSETS.indexOf(0x068)] = 0x00000077L
        @Suppress("UNCHECKED_CAST")
        snap["scalars"] = (snap["scalars"] as Map<String, Any?>) - "autoFrameIrqs" - "slvsEcSilicon"
        snap["coreWords"] = legacyCore.toList()
        snap["bank6e000Words"] = legacy6e.toList()
        isp.restoreSnapshot(snap)
        assertEquals(0x00abcdefL, core.read(0x134, 4))
        assertEquals(0L, core.read(0x108, 4))
        assertEquals(0L, core.read(0x2d4, 4))
        assertEquals(0x00000077L, b6e.read(0x068, 4))
        assertEquals(0L, b6e.read(0x070, 4))

        isp.close()
    }

    @Test
    fun liveViewDmaSupportsDualPlaneSemiPlanarYuvAndPreservesPackedUyvyWhenPlane1IsZero(@TempDir tempRoot: Path) {
        val ctx = StubContext(tempRoot)
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())

        val ramBase = 0x89000000L
        val ram = ByteArray(0x4000)
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= ramBase && address + size <= ramBase + ram.size) {
                    "OOB DMA 0x${address.toString(16)} size=$size"
                }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val n = destination.remaining()
                check(address, n.toLong(), false)
                destination.put(ram, (address - ramBase).toInt(), n)
            }
            override fun write(address: Long, source: ByteBuffer) {
                val n = source.remaining()
                check(address, n.toLong(), true)
                source.get(ram, (address - ramBase).toInt(), n)
            }
        })
        // Uniform warm-red RGGB Bayer pattern so R > G > B -> distinct Y, U (<128), V (>128)
        ctx.ports.register("cmos", "cmos", SensorPort::class.java, object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                val w = 16
                val h = 8
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val v = when ((y and 1) * 2 + (x and 1)) {
                            0 -> 0xE000 // R high
                            3 -> 0x2000 // B low
                            else -> 0x6000 // G mid
                        }
                        destination.putShort(v.toShort())
                    }
                }
                return w * h * 2
            }
        })

        val factory = SocIspFactory()
        val isp = factory.create(
            ctx,
            mapOf("profile" to SocIspFactory.PROFILE, "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        ) as StatefulComponent
        isp.start()

        val lv0 = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_0, MmioDevice::class.java)!!
        val lv1 = ctx.ports.find(SocIspFactory.PORT_LIVE_VIEW_1, MmioDevice::class.java)!!

        // 1. Backward-compatible packed UYVY on LV Ch1 when Plane 1 (+0xCC) == 0
        val uyvyOff = 0x0000
        lv1.write(0x018, 4, (7L shl 16) or 15L) // 16x8
        lv1.write(0x0c4, 4, ramBase + uyvyOff)
        lv1.write(0x0cc, 4, 0L)
        lv1.write(0x0d0, 4, 32L) // 16 * 2
        lv1.write(0x0d4, 4, 0x1000_0000L)
        val uUyvy = ram[uyvyOff].toInt() and 0xFF
        val yUyvy = ram[uyvyOff + 1].toInt() and 0xFF
        val vUyvy = ram[uyvyOff + 2].toInt() and 0xFF
        assertTrue(yUyvy > 40, "Expected non-zero UYVY luma: $yUyvy")
        assertTrue(vUyvy > uUyvy, "Warm red scene should yield V ($vUyvy) > U ($uUyvy)")

        // 2. Real-silicon 2-plane semi-planar NV16 (4:2:2) on LV Ch0 when Plane 1 (+0xCC) != 0
        ram.fill(0)
        val yOff = 0x1000
        val uvOff = 0x2000
        lv0.write(0x018, 4, (7L shl 16) or 15L) // 16x8
        lv0.write(0x0c4, 4, ramBase + yOff)
        lv0.write(0x0cc, 4, ramBase + uvOff)
        lv0.write(0x0d0, 4, 16L) // 1 byte per Y sample
        lv0.write(0x0d4, 4, 0x1000_0000L) // format=0 -> NV16 (8 UV rows * 16B = 128B)
        assertEquals(yUyvy, ram[yOff].toInt() and 0xFF)
        assertEquals(uUyvy, ram[uvOff].toInt() and 0xFF)
        assertEquals(vUyvy, ram[uvOff + 1].toInt() and 0xFF)
        // Row 7 of NV16 UV plane must be populated
        assertTrue((ram[uvOff + 7 * 16 + 1].toInt() and 0xFF) > 128)

        // 3. Semi-planar NV12 (4:2:0) when ctrl[21:20] == 1 -> only height/2 = 4 UV rows written
        ram.fill(0)
        lv0.write(0x0d4, 4, 0x1010_0000L)
        assertEquals(yUyvy, ram[yOff].toInt() and 0xFF)
        assertEquals(uUyvy, ram[uvOff].toInt() and 0xFF)
        assertEquals(vUyvy, ram[uvOff + 1].toInt() and 0xFF)
        // Row 3 (last 4:2:0 UV row) populated, row 4 untouched (0)
        assertTrue((ram[uvOff + 3 * 16 + 1].toInt() and 0xFF) > 128)
        assertEquals(0, ram[uvOff + 4 * 16 + 1].toInt() and 0xFF)

        isp.close()
    }

    @Test
    fun slvsEcSiliconOptInModelsDualBankRxPhyIrqs296297298AndSnapshotRoundTrip(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()
        // Disabled profile rejects slvsEcSilicon=true
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                StubContext(tempRoot),
                mapOf(
                    "profile" to "disabled",
                    "initialFill" to 0L,
                    "resultSurfaceFile" to "",
                    "resultSurfaceSha256" to "",
                    "slvsEcSilicon" to true,
                ),
            )
        }

        val ctx = StubContext(tempRoot)
        val gic = StubGicSink().apply {
            enabledLines += listOf(SocIspFactory.IRQ_296, SocIspFactory.IRQ_297, SocIspFactory.IRQ_298)
            states[SocIspFactory.IRQ_296] = EdgeInterruptState(edge = false, high = false, pending = false)
            states[SocIspFactory.IRQ_297] = EdgeInterruptState(edge = false, high = false, pending = false)
            states[SocIspFactory.IRQ_298] = EdgeInterruptState(edge = false, high = false, pending = false)
        }
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        val isp = factory.create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "slvsEcSilicon" to true,
            ),
        ) as StatefulComponent
        isp.start()

        assertTrue(SocIspFactory.PORT_SLVSEC_BANK1_GLOBAL in isp.additionalMmioPorts())
        assertTrue(SocIspFactory.PORT_SLVSEC_BANK1_CH in isp.additionalMmioPorts())
        assertEquals(setOf(106, 296, 297, 298), isp.interruptRoutes().flatMap { it.lines.toList() }.toSet())

        val b0Global = ctx.ports.find(SocIspFactory.PORT_CONFIG_69000, MmioDevice::class.java)!!
        val b0Ch = ctx.ports.find(SocIspFactory.PORT_CONFIG_69100, MmioDevice::class.java)!!
        val b1Global = ctx.ports.find(SocIspFactory.PORT_SLVSEC_BANK1_GLOBAL, MmioDevice::class.java)!!
        val b1Ch = ctx.ports.find(SocIspFactory.PORT_SLVSEC_BANK1_CH, MmioDevice::class.java)!!
        val phy = ctx.ports.find(SocIspFactory.PORT_CONFIG_6B000, MmioDevice::class.java)!!
        val irqCtl = ctx.ports.find(SocIspFactory.PORT_INTERRUPT_CONTROL, IspInterruptPort::class.java)!!

        // Active-low 8-lane enable mask on +0x0C: 0x00 enables all 8 lanes; 0xFF disables all
        b0Global.write(0x0c, 4, 0x00L)
        b1Global.write(0x0c, 4, 0xF0L) // lower 4 lanes active (0x0F)
        phy.write(0x04, 4, 0x0000_0001L)
        assertEquals(1L, phy.read(0xf4, 4) and 1L) // PHY lock bit asserted

        @Suppress("UNCHECKED_CAST")
        val slvsState = isp.state()["slvsEc"] as Map<String, Any?>
        assertEquals(0xFF, slvsState["bank0ActiveLaneMask"])
        assertEquals(0x0F, slvsState["bank1ActiveLaneMask"])
        assertEquals(true, slvsState["phyLocked"])

        // Ch0 Normal IRQ 296 (6-bit mask 0xF3) + W1C clear
        b0Ch.write(0x00c, 4, 0xF3L)
        irqCtl.signalInterrupt(SocIspFactory.IRQ_296, true, 0x42L)
        assertEquals(0x42L, b0Ch.read(0x008, 4))
        assertTrue(gic.edgeState(SocIspFactory.IRQ_296).high)
        b0Ch.write(0x008, 4, 0x42L)
        assertEquals(0L, b0Ch.read(0x008, 4))
        assertFalse(gic.edgeState(SocIspFactory.IRQ_296).high)

        // Ch1 Normal IRQ 298 (+0x108/+0x10C) + W1C clear
        b0Ch.write(0x10c, 4, 0x11L)
        irqCtl.signalInterrupt(SocIspFactory.IRQ_298, true, 0x11L)
        assertEquals(0x11L, b0Ch.read(0x108, 4))
        assertTrue(gic.edgeState(SocIspFactory.IRQ_298).high)
        b0Ch.write(0x108, 4, 0x11L)
        assertEquals(0L, b0Ch.read(0x108, 4))
        assertFalse(gic.edgeState(SocIspFactory.IRQ_298).high)

        // Shared Error IRQ 297: Link error on +0x010/+0x014 increments RO counters +0x44/+0x48
        b0Ch.write(0x014, 4, 0x005L)
        irqCtl.signalInterrupt(SocIspFactory.IRQ_297, true, 0x005L)
        assertEquals(0x005L, b0Ch.read(0x010, 4))
        assertEquals(1L, b0Ch.read(0x044, 4))
        assertEquals(1L, b0Ch.read(0x048, 4))
        assertFails { b0Ch.write(0x044, 4, 0L) } // RO diagnostic counter
        assertTrue(gic.edgeState(SocIspFactory.IRQ_297).high)
        b0Ch.write(0x010, 4, 0x005L)
        assertFalse(gic.edgeState(SocIspFactory.IRQ_297).high)

        // Bank 1 register retention + snapshot roundtrip
        b1Ch.write(0x000, 4, 0x0000_0001L)
        val snap = isp.saveSnapshot()
        val ctx2 = StubContext(tempRoot)
        val gic2 = StubGicSink().apply {
            states[SocIspFactory.IRQ_297] = EdgeInterruptState(edge = false, high = false, pending = false)
            states[SocIspFactory.IRQ_298] = EdgeInterruptState(edge = false, high = false, pending = false)
        }
        ctx2.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic2)
        val restored = factory.create(
            ctx2,
            mapOf(
                "profile" to SocIspFactory.PROFILE,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
                "slvsEcSilicon" to true,
            ),
        ) as StatefulComponent
        restored.start()
        restored.restoreSnapshot(snap)
        assertEquals(snap, restored.saveSnapshot())
        assertEquals(1L, ctx2.ports.find(SocIspFactory.PORT_SLVSEC_BANK1_CH, MmioDevice::class.java)!!.read(0x000, 4))

        // Downgrade to slvsEcSilicon=false must be strictly refused
        val ctx3 = StubContext(tempRoot)
        ctx3.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, StubGicSink())
        val plain = factory.create(
            ctx3,
            mapOf("profile" to SocIspFactory.PROFILE, "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to ""),
        ) as StatefulComponent
        plain.start()
        assertFailsWith<IllegalArgumentException> { plain.restoreSnapshot(snap) }

        isp.close()
        restored.close()
        plain.close()
    }

    @Test
    fun tallMoviePreviewDoesNotUseTheFullResolutionStillReadoutClock(@TempDir tempRoot: Path) {
        val ctx = StubContext(tempRoot)
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java,
            StubGicSink().apply { enabledLines.clear() })
        val sensor = object : Imx461ExposurePort {
            override var mdsel8 = Imx461ExposurePort.MDSEL_MOVIE_4K_30P
            override var liveViewTimingActive = false
            override val hasExposureRegs = false
            override val shr15 = 0
            override val svr12 = 0
            override val pgc12 = 0
            override val pgcSecondary12 = 0
            override val hcgEnabled = false
            override val vmax12 = 0
            override val dgain8 = 0
        }
        ctx.ports.register("soc-isp-cmd", Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java, sensor)
        val isp = SocIspFactory().create(ctx, mapOf(
            "profile" to SocIspFactory.PROFILE_AUTO_FRAME_IRQS,
            "initialFill" to 0L, "resultSurfaceFile" to "", "resultSurfaceSha256" to "",
        ))
        isp.start()
        try {
            val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
            val ccdif = requireNotNull(ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java))
            ccdif.write(0x018, 4, 0x013E08CE)
            val first = requireNotNull(core.nextDeadline())
            ctx.currentTick = first
            core.beforeInstruction(first)
            val next = requireNotNull(core.nextDeadline())
            assertEquals(SocIspFactory.AUTO_FRAME_IRQ_PERIOD, next - first)
            sensor.mdsel8 = 0
            ctx.currentTick = next
            core.beforeInstruction(next)
            assertEquals(next + SocIspFactory.STILL_FRAME_IRQ_PERIOD, core.nextDeadline())
            sensor.liveViewTimingActive = true
            ctx.currentTick = next + 180_000L
            val timing = requireNotNull(ctx.ports.find(CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java))
            timing.resumeLiveView()
            val readoutEnd = requireNotNull(core.nextDeadline())
            assertEquals(ctx.currentTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD, readoutEnd)
            ctx.currentTick += 100
            timing.resumeLiveView()
            assertEquals(readoutEnd, core.nextDeadline())
            ctx.currentTick = readoutEnd
            core.beforeInstruction(readoutEnd)
            assertEquals(readoutEnd + SocIspFactory.AUTO_FRAME_IRQ_PERIOD, core.nextDeadline())
        } finally {
            isp.close()
        }
    }

    @Test
    fun autoFrameIrqPeriodScalesToStillReadoutDurationWhenCcdifProgramsHighLineCount(@TempDir tempRoot: Path) {
        val factory = SocIspFactory()
        val ctx = StubContext(tempRoot)
        val gic = StubGicSink().apply {
            enabledLines.clear()
            for (line in listOf(100, 102, 106, 107, 108, 109)) {
                states[line] = EdgeInterruptState(edge = true, high = false, pending = false)
            }
            states[SocIspFactory.IRQ_296] = EdgeInterruptState(edge = false, high = false, pending = false)
            enabledLines += listOf(SocIspFactory.IRQ_102, SocIspFactory.IRQ_106)
        }
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)

        val isp = factory.create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_AUTO_FRAME_IRQS,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
            ),
        )
        isp.start()

        val core = ctx.ports.find(SocIspFactory.PORT_CORE, MmioDevice::class.java) as TickAwareMmioDevice
        val b6e = ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java)!!

        // LiveView (0xFFF6E018 = 0x01EC03EE -> 1,006 active lines): stays on AUTO_FRAME_IRQ_PERIOD (2,000,000 ticks)
        b6e.write(0x018, 4, 0x01EC03EEL)
        val lvTick = SocIspFactory.AUTO_FRAME_IRQ_PERIOD
        ctx.currentTick = lvTick
        core.beforeInstruction(lvTick)
        assertEquals(lvTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD, core.nextDeadline())
        assertTrue(gic.pulseCalls.isEmpty(), "IRQ 106 arms on first frame")

        // Firmware switches CCDIF to 102MP STILL readout (0xFFF6E018 = 0x00120B8F -> 2,959 active lines)
        // First VD fires on schedule (st=5->6), and the following interval covers the full readout.
        b6e.write(0x018, 4, 0x00120B8FL)
        val stillStartTick = lvTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD
        assertEquals(stillStartTick, core.nextDeadline(), "Switching into STILL keeps first st=5->6 VD on schedule")
        gic.states[SocIspFactory.IRQ_102] = EdgeInterruptState(edge = true, high = false, pending = false)
        ctx.currentTick = stillStartTick
        core.beforeInstruction(stillStartTick)
        assertEquals(
            stillStartTick + SocIspFactory.STILL_FRAME_IRQ_PERIOD,
            core.nextDeadline(),
            "STILL readout must scale frame interval to STILL_FRAME_IRQ_PERIOD so Timer #36 (ev=0x15af) completes before st=6 exits",
        )
        assertTrue(gic.pulseCalls.isEmpty(), "IRQ 106 must not fire prematurely after only 37ms during 102MP STILL readout")

        // After STILL_FRAME_IRQ_PERIOD elapses, IRQ 106 completes cleanly
        val stillEndTick = stillStartTick + SocIspFactory.STILL_FRAME_IRQ_PERIOD
        gic.states[SocIspFactory.IRQ_102] = EdgeInterruptState(edge = true, high = false, pending = false)
        ctx.currentTick = stillEndTick
        core.beforeInstruction(stillEndTick)
        assertEquals(listOf(SocIspFactory.IRQ_106 to 2), gic.pulseCalls)
        assertEquals(stillEndTick + SocIspFactory.STILL_FRAME_IRQ_PERIOD, core.nextDeadline())

        // Mechanical CH programs the same window twice for each new exposure. Its initial sync
        // must arrive before the mechanical completion callback, as it does for the first exposure.
        ctx.currentTick = stillEndTick + 100_000L
        b6e.write(0x018, 4, 0x00120B8FL)
        val burstStartTick = ctx.currentTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD
        assertEquals(burstStartTick, core.nextDeadline())
        ctx.currentTick += 20_000L
        b6e.write(0x018, 4, 0x00120B8FL)
        assertEquals(burstStartTick, core.nextDeadline())
        ctx.currentTick = burstStartTick
        core.beforeInstruction(burstStartTick)
        assertEquals(burstStartTick + SocIspFactory.STILL_FRAME_IRQ_PERIOD, core.nextDeadline())

        // When firmware switches CCDIF back to LiveView (1,006 lines), nextAutoIrqTick clamps immediately back to LiveView cadence
        ctx.currentTick = burstStartTick + 100_000L
        b6e.write(0x018, 4, 0x01EC03EEL)
        assertEquals(
            ctx.currentTick + SocIspFactory.AUTO_FRAME_IRQ_PERIOD,
            core.nextDeadline(),
            "Returning to LiveView must clamp next frame deadline immediately to AUTO_FRAME_IRQ_PERIOD",
        )

        isp.close()
    }

    @Test
    fun mModeExposurePreviewOffStillCaptureUsesRealFirmwareShutterAndConsistentHighRamYc(@TempDir tempRoot: Path) {
        val ctx = StubContext(tempRoot)
        val gic = StubGicSink().apply {
            for (line in listOf(100, 101, 102, 105, 107, 108, 109, 110, 122, 126, 127, 129, 134, 137, 138, 139, 140, 142, 297, 298)) {
                states.putIfAbsent(line, EdgeInterruptState(edge = false, high = false, pending = false))
            }
        }
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        val highRamAddr = 0x1_B000_0000L
        val ramW = 64
        val ramH = 48
        val ramStride = ramW * 2
        val ycRam = ByteArray(ramH * ramStride)
        val dmaMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == highRamAddr && size == ycRam.size.toLong()) {
                    "Unexpected check 0x${address.toString(16)} size=$size"
                }
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(ycRam, (address - highRamAddr).toInt(), source.remaining())
            }
        }
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, dmaMem)
        val exposures = mutableListOf<Exposure>()
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unused")
            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int {
                exposures += exposure
                val level = (exposure.durationNanos.coerceIn(1L, 8000L) * 4L).toInt().toShort()
                for (i in 0 until 1024 * 576) destination.putShort(level)
                return 1024 * 576 * 2
            }
        }
        ctx.ports.register("cmos", "cmos", SensorPort::class.java, sensor)
        ctx.ports.register("lens", "lens", LensPort::class.java, object : LensPort {
            override fun parameters() = LensParameters("GF45", 45.0, 2.8, 1.0, 1.0, 0.0)
        })

        var currentMdsel = 0x40
        var currentTiming: StillShutterHardwareTiming? = null
        val expPort = object : Imx461ExposurePort {
            override val hasExposureRegs = true
            override val liveViewTimingActive = false
            override val shr15 = 2
            override val svr12 = 0
            override val pgc12 = 2066
            override val pgcSecondary12 = 2066
            override val hcgEnabled = false
            override val vmax12 = 0
            override val dgain8 = 0
            override val mdsel8: Int get() = currentMdsel
            override val stillShutterTiming: StillShutterHardwareTiming? get() = currentTiming
        }
        ctx.ports.register("soc-isp-cmd", Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java, expPort)

        val isp = SocIspFactory().create(
            ctx,
            mapOf(
                "profile" to SocIspFactory.PROFILE_V3,
                "initialFill" to 0L,
                "resultSurfaceFile" to "",
                "resultSurfaceSha256" to "",
            ),
        )
        isp.start()
        try {
            val b6e = requireNotNull(ctx.ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java))
            val stillCap = requireNotNull(ctx.ports.find(ImagingCompanionEngines.PORT_ISP_82000, MmioDevice::class.java))

            // Shot 1: MS 14-bit (0x40) at 1/640 s (391 ns) ISO 100
            currentMdsel = 0x40
            currentTiming = StillShutterHardwareTiming(0x40, 2, 0, 2066, false, 8900, 212_500L, 100_000L)
            b6e.write(0x018, 4, 0x00120B8FL)
            // Shot 2: return to LV then MS 16-bit (0x00) at 1/160 s (1563 ns) ISO 100
            b6e.write(0x018, 4, 0x01EC03EEL)
            currentMdsel = 0x00
            currentTiming = StillShutterHardwareTiming(0x00, 2, 0, 2066, false, 8900, 550_000L, 100_000L)
            b6e.write(0x018, 4, 0x00120B8FL)
            // Shot 3: return to LV then ES 12-bit (0x80) at 1/40 s (SHR=3482 -> 1936 lines -> 6252 ns) ISO 100
            b6e.write(0x018, 4, 0x01EC03EEL)
            currentMdsel = 0x80
            currentTiming = StillShutterHardwareTiming(0x80, 3482, 0, 2066, false, 8900)
            b6e.write(0x018, 4, 0x00120B8FL)

            assertEquals(listOf(391L, 1563L, 6252L), exposures.map { it.durationNanos })
            assertEquals(listOf(100, 100, 100), exposures.map { it.iso })

            // Trigger RECC_S YC DMA (0x490100) to 34-bit High-RAM: must consume latched stillReadout without re-exposing!
            val exposesBeforeYc = exposures.size
            stillCap.write(0x008, 4, 0x490100L)
            stillCap.write(0x050, 4, (ramH.toLong() shl 16) or ramW.toLong())
            stillCap.write(0x054, 4, ramW.toLong())
            stillCap.write(0x058, 4, ramW.toLong())
            stillCap.write(0x070, 4, highRamAddr and 0xFFFF_FFFFL)
            stillCap.write(0x074, 4, highRamAddr ushr 32)
            stillCap.write(0x080, 4, ramStride.toLong())
            stillCap.write(0x004, 4, 1L)
            val tickable = stillCap as TickAwareMmioDevice
            val due = requireNotNull(tickable.nextDeadline())
            ctx.currentTick = due
            tickable.beforeInstruction(due)

            assertEquals(exposesBeforeYc, exposures.size, "RECC_S YC DMA must reuse latched stillReadout without re-exposing")
            val meanLuma = (1 until ycRam.size step 2).map { ycRam[it].toInt() and 0xFF }.average()
            assertTrue(meanLuma > 50.0, "High-RAM RECC_S YC DMA must render valid UYVY pixels (got meanLuma=$meanLuma)")
        } finally {
            isp.close()
        }
    }
}
