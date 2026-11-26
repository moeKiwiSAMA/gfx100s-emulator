package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

class DmaChannelProgrammingTest {
    private class TestContext : ComponentContext {
        var currentTick = 0L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { currentTick }
        override fun readData(relative: String): Map<String, Any?> = emptyMap()
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private val factory = DmaFactory()

    private fun dmaConfig(
        ch4: String = "",
        ch7: String = "",
        ch11: String = "",
    ): Map<String, Any> = mapOf(
        "profile" to "standalone-experimental-v1",
        "memoryPort" to "cpu.memory",
        "interruptPort" to "",
        "irqBase" to 207L,
        "flashPeerPort" to "",
        "softwareProfile" to "",
        "spi2DmaProfile" to "",
        "transmitPeerPort" to "",
        "destinationPeersFile" to "",
        "channel4Programming" to ch4,
        "channel7Programming" to ch7,
        "channel11Programming" to ch11,
    )

    @Test
    fun disabledChannelProgrammingKeepsLegacyPortsAndSnapshotKeysUnchanged() {
        val ctx = TestContext()
        val comp = factory.create(ctx, dmaConfig()) as StatefulComponent
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertFalse("channelProgramming" in comp.state())
        assertFalse("channelProgramming" in comp.saveSnapshot())
        assertFalse("channel4Programming" in (comp.saveSnapshot()["scalars"] as Map<*, *>))
        (comp as HardwareComponent).close()
    }

    @Test
    fun channels4And11ControlReadbackAndParamsWriteOnlyPlusCh7RetainedWorkAndPromote() {
        val ctx = TestContext()
        val comp = factory.create(
            ctx,
            dmaConfig(
                ch4 = "write-words-v1",
                ch7 = "retained-words-zero-v1",
                ch11 = "control-readback-after-write-v1",
            ),
        ) as StatefulComponent
        assertEquals(
            listOf(
                "soc-dma.ch4-control", "soc-dma.ch4-params",
                "soc-dma.ch7-enable", "soc-dma.ch7-control", "soc-dma.ch7-params",
                "soc-dma.ch11-control", "soc-dma.ch11-params",
            ),
            comp.additionalMmioPorts(),
        )
        val ch4Ctrl = ctx.ports.find("soc-dma.ch4-control", MmioDevice::class.java)!!
        val ch4Params = ctx.ports.find("soc-dma.ch4-params", MmioDevice::class.java)!!
        val ch7Enable = ctx.ports.find("soc-dma.ch7-enable", MmioDevice::class.java)!!
        val ch7Ctrl = ctx.ports.find("soc-dma.ch7-control", MmioDevice::class.java)!!
        val ch7Params = ctx.ports.find("soc-dma.ch7-params", MmioDevice::class.java)!!
        val ch11Ctrl = ctx.ports.find("soc-dma.ch11-control", MmioDevice::class.java)!!
        val ch11Params = ctx.ports.find("soc-dma.ch11-params", MmioDevice::class.java)!!

        // write-words-v1 refuses readback, allows write; control-readback refuses read before write, allows after write
        ch4Ctrl.write(0L, 4, 0x00015000L)
        assertFailsWith<IllegalArgumentException> { ch4Ctrl.read(0L, 4) }
        assertFailsWith<IllegalArgumentException> { ch11Ctrl.read(0L, 4) }
        ch11Ctrl.write(0L, 4, 0x00019000L)
        assertEquals(0x00019000L, ch11Ctrl.read(0L, 4))

        // ch4/ch11 params are write-only; ch7 ports are read-write
        for (i in 0..3) {
            ch4Params.write(i * 4L, 4, 100L + i)
            ch11Params.write(i * 4L, 4, 200L + i)
            ch7Params.write(i * 4L, 4, 300L + i)
            assertEquals(300L + i, ch7Params.read(i * 4L, 4))
            assertFailsWith<IllegalStateException> { ch4Params.read(i * 4L, 4) }
            assertFailsWith<IllegalStateException> { ch11Params.read(i * 4L, 4) }
        }
        ch7Enable.write(0L, 4, 0x080800L)
        ch7Ctrl.write(0L, 4, 0x019000L)
        assertEquals(0x080800L, ch7Enable.read(0L, 4))
        assertEquals(0x019000L, ch7Ctrl.read(0L, 4))

        // Snapshot promotion from ch4 write-words-v1 -> control-readback-after-write-v1 succeeds
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val promoted = factory.create(
            ctx2,
            dmaConfig(
                ch4 = "control-readback-after-write-v1",
                ch7 = "retained-words-zero-v1",
                ch11 = "control-readback-after-write-v1",
            ),
        ) as StatefulComponent
        promoted.restoreSnapshot(snap)
        val pCh4Ctrl = ctx2.ports.find("soc-dma.ch4-control", MmioDevice::class.java)!!
        assertEquals(0x00015000L, pCh4Ctrl.read(0L, 4))

        // Downgrade fails cleanly
        val ctx3 = TestContext()
        val downgraded = factory.create(
            ctx3,
            dmaConfig(ch4 = "control-readback-after-write-v1", ch7 = "retained-words-zero-v1", ch11 = "write-words-v1"),
        ) as StatefulComponent
        assertFailsWith<IllegalArgumentException> { downgraded.restoreSnapshot(snap) }

        (comp as HardwareComponent).close()
        (promoted as HardwareComponent).close()
        (downgraded as HardwareComponent).close()
    }

    @Test
    fun chainedDescriptorRingAdvancesDestinationFollowsDescriptorsAndRestoresSnapshot() {
        val descBase = 0x0181_6320L
        val buf0 = 0x1a23_4000L
        val buf1 = 0x1a23_4080L
        val descMem = ByteBuffer.allocate(0x60).order(ByteOrder.LITTLE_ENDIAN)
        // Descriptor 1 at descBase + 0x30: count=127 (128 bytes), src=0xfff4f000, dst=buf1, link back to descBase | 1
        descMem.position(0x30)
        descMem.putInt(127)
        descMem.putInt(0xfff4f000.toInt())
        descMem.putInt(buf1.toInt())
        descMem.putInt(0x20004)
        descMem.putInt(0x20000)
        descMem.putInt((descBase or 1L).toInt())
        // Descriptor 0 at descBase: count=127 (128 bytes), src=0xfff4f000, dst=buf0, link to descBase + 0x30 | 1
        descMem.position(0x00)
        descMem.putInt(127)
        descMem.putInt(0xfff4f000.toInt())
        descMem.putInt(buf0.toInt())
        descMem.putInt(0x20004)
        descMem.putInt(0x20000)
        descMem.putInt(((descBase + 0x30L) or 1L).toInt())

        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address >= descBase && address + size <= descBase + descMem.capacity())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val off = (address - descBase).toInt()
                destination.put(descMem.array(), off, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        // Strict default (chainedRingProfile == "") refuses repeat/linked descriptors
        val strictCtx = TestContext()
        strictCtx.claims["cpu.memory"] = ram
        val strictComp = factory.create(strictCtx, dmaConfig())
        val strictDma = strictCtx.ports.find("soc-dma.registers", MmioDevice::class.java)!!
        strictDma.write(0x4010L, 4, 127L)
        strictDma.write(0x4014L, 4, 0xfff4f000L)
        strictDma.write(0x4018L, 4, buf0)
        strictDma.write(0x4024L, 4, (descBase + 0x30L) or 1L)
        assertFailsWith<IllegalArgumentException> { strictDma.write(0x4028L, 4, 0x10200011L) }
        strictComp.close()

        // Opt-in chained-descriptor-ring-v1 advances r[6] every 500k ticks and follows descriptors
        val ctx = TestContext()
        ctx.claims["cpu.memory"] = ram
        val comp = factory.create(ctx, dmaConfig() + ("chainedRingProfile" to DmaFactory.CHAINED_RING_PROFILE)) as StatefulComponent
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java)!!
        dma.write(0x4010L, 4, 127L)
        dma.write(0x4014L, 4, 0xfff4f000L)
        dma.write(0x4018L, 4, buf0)
        dma.write(0x4024L, 4, (descBase + 0x30L) or 1L)
        dma.write(0x4028L, 4, 0x10200011L)

        // First beat: advances +64 bytes inside buf0
        ctx.currentTick = 500_000L
        dma.beforeInstruction(ctx.currentTick)
        assertEquals(buf0 + 64L, dma.read(0x4018L, 4))

        // Snapshot round-trip preserves active chained-ring state
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        ctx2.claims["cpu.memory"] = ram
        val restored = factory.create(ctx2, dmaConfig() + ("chainedRingProfile" to DmaFactory.CHAINED_RING_PROFILE)) as StatefulComponent
        restored.restoreSnapshot(snap)
        val restoredDma = ctx2.ports.find("soc-dma.registers", MmioDevice::class.java)!!
        assertEquals(buf0 + 64L, restoredDma.read(0x4018L, 4))

        // Second beat reaches ringEnd -> loads descriptor 1 (buf1)
        ctx2.currentTick = 1_000_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(buf1, restoredDma.read(0x4018L, 4))

        // Third beat advances +64 inside buf1; fourth beat wraps back to descriptor 0 (buf0)
        ctx2.currentTick = 1_500_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(buf1 + 64L, restoredDma.read(0x4018L, 4))
        ctx2.currentTick = 2_000_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(buf0, restoredDma.read(0x4018L, 4))

        // Stop channel cleanly
        restoredDma.write(0x4028L, 4, 0L)
        assertEquals(0L, restoredDma.read(0x4030L, 4) and 0x10000L)

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun channel14TimerFiresOneShotAndPeriodicGicIrq55AndRoundTripsSnapshot() {
        val irqPulses = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqPulses.add(irq to high)
            }
        }
        val ctx = TestContext()
        ctx.claims["test.irq"] = sink
        val comp = factory.create(
            ctx,
            dmaConfig(ch7 = DmaFactory.RETAINED_ZERO_PROFILE) + mapOf(
                "interruptPort" to "test.irq",
                "channel14TimerProfile" to DmaFactory.CH14_TIMER_PROFILE,
            ),
        ) as StatefulComponent
        assertEquals(
            listOf(InterruptRoute("test.irq", 207..222), InterruptRoute("test.irq", 55..55)),
            (comp as HardwareComponent).interruptRoutes(),
        )
        assertTrue(DmaFactory.CH14_CONTROL_PORT in comp.additionalMmioPorts())
        assertTrue(DmaFactory.CH14_PARAMS_PORT in comp.additionalMmioPorts())

        val ch7Enable = ctx.ports.find(DmaFactory.CH7_ENABLE_PORT, MmioDevice::class.java) as TickAwareMmioDevice
        val ch14Ctrl = ctx.ports.find(DmaFactory.CH14_CONTROL_PORT, MmioDevice::class.java)!!
        val ch14Params = ctx.ports.find(DmaFactory.CH14_PARAMS_PORT, MmioDevice::class.java)!!

        // One-shot mode (bit 4 clear): fires GIC IRQ 55 once at tick 1000 + 38880, then stops
        ctx.currentTick = 1_000L
        ch14Ctrl.write(0L, 4, 0x0001_0000L)
        ch14Params.write(0L, 4, 38_880L)
        ch14Params.write(4L, 4, 38_879L)
        ch7Enable.write(0L, 4, 0x4000_4000L)
        assertEquals(39_880L, ch7Enable.nextDeadline())
        assertEquals(0x4000_4000L, ch7Enable.read(0L, 4))

        // Snapshot round-trip preserves active one-shot deadline
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val irqPulses2 = mutableListOf<Pair<Int, Boolean>>()
        ctx2.claims["test.irq"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqPulses2.add(irq to high) }
        }
        val restored = factory.create(
            ctx2,
            dmaConfig(ch7 = DmaFactory.RETAINED_ZERO_PROFILE) + mapOf(
                "interruptPort" to "test.irq",
                "channel14TimerProfile" to DmaFactory.CH14_TIMER_PROFILE,
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val restoredEnable = ctx2.ports.find(DmaFactory.CH7_ENABLE_PORT, MmioDevice::class.java) as TickAwareMmioDevice
        assertEquals(39_880L, restoredEnable.nextDeadline())

        ctx2.currentTick = 39_880L
        restoredEnable.beforeInstruction(39_880L)
        assertEquals(listOf(55 to true, 55 to false), irqPulses2.filter { it.first == 55 })
        assertNull(restoredEnable.nextDeadline())
        assertEquals(0L, restoredEnable.read(0L, 4) and 0x4000L)

        // Periodic mode (bit 4 set): reloads after expiry and can be stopped via 0x4000_0000
        val restoredCtrl = ctx2.ports.find(DmaFactory.CH14_CONTROL_PORT, MmioDevice::class.java)!!
        val restoredParams = ctx2.ports.find(DmaFactory.CH14_PARAMS_PORT, MmioDevice::class.java)!!
        restoredCtrl.write(0L, 4, 0x0001_0010L)
        restoredParams.write(0L, 4, 500L)
        restoredEnable.write(0L, 4, 0x4000_4000L)
        assertEquals(40_380L, restoredEnable.nextDeadline())
        ctx2.currentTick = 40_380L
        restoredEnable.beforeInstruction(40_380L)
        assertEquals(40_880L, restoredEnable.nextDeadline())
        assertEquals(4, irqPulses2.count { it.first == 55 })
        restoredEnable.write(0L, 4, 0x4000_0000L)
        assertNull(restoredEnable.nextDeadline())

        // Monotonic promotion from legacy snapshot without channel14TimerProfile succeeds
        val legacyCtx = TestContext()
        val legacyComp = factory.create(legacyCtx, dmaConfig(ch7 = DmaFactory.RETAINED_ZERO_PROFILE)) as StatefulComponent
        val legacySnap = legacyComp.saveSnapshot()
        val promotedCtx = TestContext()
        val promoted = factory.create(
            promotedCtx,
            dmaConfig(ch7 = DmaFactory.RETAINED_ZERO_PROFILE) + ("channel14TimerProfile" to DmaFactory.CH14_TIMER_PROFILE),
        ) as StatefulComponent
        promoted.restoreSnapshot(legacySnap)

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
        (legacyComp as HardwareComponent).close()
        (promoted as HardwareComponent).close()
    }

    @Test
    fun channel14OneshotAndPeriodicTimerFiresRisingEdgeIrq55AndRoundTripsSnapshot() {
        val ctx = TestContext()
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        ctx.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high }
        })
        val comp = factory.create(
            ctx,
            dmaConfig(ch7 = "retained-words-zero-v1") + mapOf(
                "interruptPort" to "gic.irq",
                "channel14Programming" to DmaFactory.ONESHOT_IRQ55_PROFILE,
            ),
        ) as StatefulComponent
        assertTrue(DmaFactory.CH14_CONTROL_PORT in comp.additionalMmioPorts())
        assertTrue(DmaFactory.CH14_PARAMS_PORT in comp.additionalMmioPorts())
        assertTrue(comp.interruptRoutes().any { 55 in it.lines })

        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        val ch7Enable = ctx.ports.find(DmaFactory.CH7_ENABLE_PORT, MmioDevice::class.java)!!
        val ch14Ctrl = ctx.ports.find(DmaFactory.CH14_CONTROL_PORT, MmioDevice::class.java)!!
        val ch14Params = ctx.ports.find(DmaFactory.CH14_PARAMS_PORT, MmioDevice::class.java)!!

        // Sync initial IRQ mask, then arm one-shot mode (bit 4 clear) for 162,000 ticks starting at tick 1,000
        dma.beforeInstruction(0L)
        ctx.currentTick = 1_000L
        ch14Ctrl.write(0L, 4, 0x0001_0000L)
        ch14Params.write(0L, 4, 162_000L)
        ch14Params.write(4L, 4, 161_999L)
        ch7Enable.write(0L, 4, (1L shl 30) or (1L shl 14))
        assertEquals(163_000L, dma.nextDeadline())
        assertEquals(163_000L, dma.nextInterestedTick())

        // Snapshot round-trip while armed preserves ch14Deadline
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val irqLog2 = mutableListOf<Pair<Int, Boolean>>()
        ctx2.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLog2 += irq to high }
        })
        val restored = factory.create(
            ctx2,
            dmaConfig(ch7 = "retained-words-zero-v1") + mapOf(
                "interruptPort" to "gic.irq",
                "channel14Programming" to DmaFactory.ONESHOT_IRQ55_PROFILE,
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val restoredDma = ctx2.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        assertEquals(163_000L, restoredDma.nextDeadline())

        // Fire one-shot on restored instance: rising-edge pulse (true, false) and disarm
        ctx2.currentTick = 163_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(listOf(55 to true, 55 to false), irqLog2.filter { it.first == 55 })
        assertNull(restoredDma.nextDeadline())

        // Periodic mode (bit 4 set): re-arms automatically after firing, and stop bit cancels
        val ch7Enable2 = ctx2.ports.find(DmaFactory.CH7_ENABLE_PORT, MmioDevice::class.java)!!
        val ch14Ctrl2 = ctx2.ports.find(DmaFactory.CH14_CONTROL_PORT, MmioDevice::class.java)!!
        val ch14Params2 = ctx2.ports.find(DmaFactory.CH14_PARAMS_PORT, MmioDevice::class.java)!!
        ch14Ctrl2.write(0L, 4, 0x0001_0010L)
        ch14Params2.write(0L, 4, 5_000L)
        ch7Enable2.write(0L, 4, (1L shl 30) or (1L shl 14))
        assertEquals(168_000L, restoredDma.nextDeadline())
        ctx2.currentTick = 168_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(173_000L, restoredDma.nextDeadline())
        assertEquals(4, irqLog2.count { it.first == 55 })
        ch7Enable2.write(0L, 4, 1L shl 30)
        assertNull(restoredDma.nextDeadline())

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun spi0DmaStreamsMultiChunkBulkTransferAcross8ByteFifo() {
        val ctx = TestContext()
        val ramBase = 0x1000_0000L
        val ramBytes = ByteArray(512) { idx -> if (idx < 131) ((idx * 37 + 11) and 0xFF).toByte() else 0 }
        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= ramBase && address + size <= ramBase + ramBytes.size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val off = (address - ramBase).toInt()
                destination.put(ramBytes, off, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                val off = (address - ramBase).toInt()
                source.get(ramBytes, off, source.remaining())
            }
        }
        val txSink = ArrayDeque<Byte>()
        val rxSource = ArrayDeque<Byte>()
        val fifo = object : SpiDmaFifo {
            override fun writableBytes(): Int = (8 - txSink.size).coerceAtLeast(0)
            override fun readableBytes(): Int = rxSource.size.coerceAtMost(8)
            override fun write(source: ByteBuffer) {
                while (source.hasRemaining()) {
                    val b = source.get()
                    txSink.addLast(b)
                    // Echo inverted byte back into RX FIFO as soon as TX drains
                    rxSource.addLast((b.toInt() xor 0x5A).toByte())
                }
            }
            override fun read(destination: ByteBuffer) {
                while (destination.hasRemaining()) {
                    destination.put(rxSource.removeFirst())
                }
            }
        }
        val irqs = mutableMapOf<Int, Boolean>()
        ctx.ports.register("test", "cpu.memory", DmaMemory::class.java, ram)
        ctx.ports.register("test", Spi0Dma.PORT, SpiDmaFifo::class.java, fifo)
        ctx.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqs[irq] = high }
        })

        val comp = factory.create(
            ctx,
            dmaConfig() + mapOf(
                "interruptPort" to "gic.irq",
                "spi0DmaProfile" to Spi0Dma.PROFILE,
            ),
        ) as StatefulComponent
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        val mux = ctx.ports.find("soc-dma.mux", MmioDevice::class.java)!!

        // Route ch2 -> request 0 (SPI0 RX), ch3 -> request 1 (SPI0 TX) via mux word 1 (offset 4)
        mux.write(4L, 4, 0x0101_0000L or 0L or (1L shl 8))

        // Arm ch3 TX for 131 bytes from ramBase+0 to 0xFFF32008
        dma.write(0x3010L, 4, 130L)
        dma.write(0x3014L, 4, ramBase)
        dma.write(0x3018L, 4, Spi0Dma.DATA)
        dma.write(0x301CL, 4, 0L)
        dma.write(0x3020L, 4, 4L)
        dma.write(0x3028L, 4, 0x1020_1011L)

        // Arm ch2 RX for 131 bytes from 0xFFF32008 to ramBase+256
        dma.write(0x2010L, 4, 130L)
        dma.write(0x2014L, 4, Spi0Dma.DATA)
        dma.write(0x2018L, 4, ramBase + 256L)
        dma.write(0x201CL, 4, 4L)
        dma.write(0x2020L, 4, 0L)
        dma.write(0x2028L, 4, 0x1020_1011L)

        // Pump chunks across ticks until both channels finish (advance >= 17 ticks per step)
        val drainedTx = mutableListOf<Byte>()
        for (step in 1..200) {
            ctx.currentTick = step * 20L
            // Simulate SPI0 hardware shifting out up to 4 bytes from TX FIFO each step
            repeat(minOf(4, txSink.size)) { drainedTx += txSink.removeFirst() }
            dma.beforeInstruction(ctx.currentTick)
            if (irqs[209] == true && irqs[210] == true) break
        }

        assertEquals(true, irqs[209], "SPI0 RX DMA ch2 should complete")
        assertEquals(true, irqs[210], "SPI0 TX DMA ch3 should complete")
        assertEquals(131, drainedTx.size)
        for (i in 0 until 131) {
            assertEquals(ramBytes[i], drainedTx[i], "TX byte $i mismatch")
            assertEquals((ramBytes[i].toInt() xor 0x5A).toByte(), ramBytes[256 + i], "RX byte $i mismatch")
        }
        assertEquals(ramBase + 131L, dma.read(0x3014L, 4))
        assertEquals(ramBase + 256L + 131L, dma.read(0x2018L, 4))

        (comp as HardwareComponent).close()
    }

    @Test
    fun channel8OneshotTimerFiresRisingEdgeIrq49AndRoundTripsSnapshot() {
        val ctx = TestContext()
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        ctx.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high }
        })
        val comp = factory.create(
            ctx,
            dmaConfig(ch7 = "retained-words-zero-v1") + mapOf(
                "interruptPort" to "gic.irq",
                "channel8Programming" to DmaFactory.ONESHOT_IRQ49_PROFILE,
            ),
        ) as StatefulComponent
        assertTrue(DmaFactory.CH8_CONTROL_PORT in comp.additionalMmioPorts())
        assertTrue(DmaFactory.CH8_PARAMS_PORT in comp.additionalMmioPorts())
        assertTrue(comp.interruptRoutes().any { 49 in it.lines })

        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        val ch7Enable = ctx.ports.find(DmaFactory.CH7_ENABLE_PORT, MmioDevice::class.java)!!
        val ch8Ctrl = ctx.ports.find(DmaFactory.CH8_CONTROL_PORT, MmioDevice::class.java)!!
        val ch8Params = ctx.ports.find(DmaFactory.CH8_PARAMS_PORT, MmioDevice::class.java)!!

        dma.beforeInstruction(0L)
        ctx.currentTick = 2_000L
        ch8Ctrl.write(0L, 4, 0x0001_0000L)
        ch8Params.write(0L, 4, 270_000L)
        ch8Params.write(4L, 4, 269_999L)
        ch7Enable.write(0L, 4, (1L shl 24) or (1L shl 8))
        assertEquals(272_000L, dma.nextDeadline())

        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val irqLog2 = mutableListOf<Pair<Int, Boolean>>()
        ctx2.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLog2 += irq to high }
        })
        val restored = factory.create(
            ctx2,
            dmaConfig(ch7 = "retained-words-zero-v1") + mapOf(
                "interruptPort" to "gic.irq",
                "channel8Programming" to DmaFactory.ONESHOT_IRQ49_PROFILE,
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val restoredDma = ctx2.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        assertEquals(272_000L, restoredDma.nextDeadline())

        ctx2.currentTick = 272_000L
        restoredDma.beforeInstruction(ctx2.currentTick)
        assertEquals(listOf(49 to true, 49 to false), irqLog2.filter { it.first == 49 })
        assertNull(restoredDma.nextDeadline())

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun softwareRequestRoute44AllowsIncrementingMemcpyGeometry() {
        val ctx = TestContext()
        val ramBase = 0x2000_0000L
        val ramBytes = ByteArray(256) { idx -> if (idx < 64) ((idx * 13 + 7) and 0xFF).toByte() else 0 }
        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= ramBase && address + size <= ramBase + ramBytes.size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(ramBytes, (address - ramBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(ramBytes, (address - ramBase).toInt(), source.remaining())
            }
        }
        ctx.ports.register("test", "cpu.memory", DmaMemory::class.java, ram)
        ctx.ports.register("test", "gic.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {}
        })
        val comp = factory.create(
            ctx,
            dmaConfig() + mapOf(
                "interruptPort" to "gic.irq",
                "softwareProfile" to "software-request44-experimental-v1",
            ),
        )
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as TickAwareMmioDevice
        val mux = ctx.ports.find("soc-dma.mux", MmioDevice::class.java)!!
        mux.write(0L, 4, 0x0100_2c00L) // ch1 -> route 44
        dma.write(0x1010L, 4, 63L)
        dma.write(0x1014L, 4, ramBase)
        dma.write(0x1018L, 4, ramBase + 128L)
        dma.write(0x101CL, 4, 0x30f00L)
        dma.write(0x1020L, 4, 0x30f00L)
        dma.write(0x1028L, 4, 0x1110_0001L)
        ctx.currentTick = 100L
        dma.beforeInstruction(ctx.currentTick)
        for (i in 0 until 64) {
            assertEquals(ramBytes[i], ramBytes[128 + i], "memcpy byte $i mismatch")
        }
        assertEquals(ramBase + 64L, dma.read(0x1014L, 4))
        assertEquals(ramBase + 192L, dma.read(0x1018L, 4))

        // 8-beat 64-bit software-request memcpy (r7=r8=0x30700, used by STILL JPEG preview copy)
        dma.write(0x1028L, 4, 0L)
        dma.write(0x1030L, 4, 0L)
        dma.write(0x1010L, 4, 63L)
        dma.write(0x1014L, 4, ramBase)
        dma.write(0x1018L, 4, ramBase + 192L)
        dma.write(0x101CL, 4, 0x30700L)
        dma.write(0x1020L, 4, 0x30700L)
        dma.write(0x1028L, 4, 0x1110_0001L)
        ctx.currentTick = 200L
        dma.beforeInstruction(ctx.currentTick)
        for (i in 0 until 64) {
            assertEquals(ramBytes[i], ramBytes[192 + i], "8-beat memcpy byte $i mismatch")
        }
        comp.close()
    }
}
