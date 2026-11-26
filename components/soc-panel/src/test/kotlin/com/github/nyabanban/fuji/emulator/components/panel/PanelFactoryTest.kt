package com.github.nyabanban.fuji.emulator.components.panel

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class PanelFactoryTest {
    private class TestContext : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No panel event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun create(profile: String): Pair<TestContext, StatefulComponent> {
        val ctx = TestContext()
        val comp = PanelFactory().create(ctx, mapOf("profile" to profile)) as StatefulComponent
        comp.start()
        return ctx to comp
    }

    private fun port(ctx: TestContext): MmioDevice =
        assertNotNull(ctx.ports.find("soc-panel", MmioDevice::class.java))

    @Test
    fun disabledDefaultHasNoPort() {
        val (ctx, comp) = create("disabled")
        assertNull(ctx.ports.find("soc-panel", MmioDevice::class.java))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertEquals(false, comp.state()["enabled"])
        val (_, comp2) = create("disabled")
        comp2.restoreSnapshot(comp.saveSnapshot())
        assertEquals(comp.state(), comp2.state())
    }

    @Test
    fun onceProfileExecutesExact11StepsRefusesOutOfOrderAndExhausts() {
        val (ctx, comp) = create(PanelFactory.PROFILE_ONCE)
        val dev = port(ctx)
        assertEquals(listOf("soc-panel"), comp.additionalMmioPorts())
        assertEquals(74, dev.clockOrder)

        // Wrong offset/width/direction refusals leave state untouched
        assertFails { dev.read(0, 4) }
        assertFails { dev.write(4, 4, 0L) }
        assertFails { dev.read(4, 2) }
        assertFails { dev.read(24, 4) }
        assertEquals(0, comp.state()["position"])

        val control = 0x001f0000L
        val offsets = listOf(4L, 4L, 8L, 12L, 16L, 20L, 0L, 0L, 0L, 4L, 4L)
        val values = listOf(0L, control, 0xff00ff00L, 0xfeL, 0xabcdefL, 0x1234L, 0L, 0L, 1L, control, PanelFactory.MASK)
        for (step in offsets.indices) {
            val write = step in 1..6 || step == 10
            if (write) {
                dev.write(offsets[step], 4, values[step])
            } else {
                assertEquals(values[step], dev.read(offsets[step], 4))
            }
            assertEquals(step + 1, comp.state()["position"])
        }
        assertFails { dev.read(4, 4) }
    }

    @Test
    fun twiceProfileCarriesControlAndAllowsUpgradeFromOnceSnapshot() {
        val (ctx1, onceComp) = create(PanelFactory.PROFILE_ONCE)
        val dev1 = port(ctx1)
        val firstControl = 0x01000fffL
        val released = firstControl or PanelFactory.MASK

        fun runCycle(dev: MmioDevice, initial: Long, control: Long) {
            val vals = listOf(initial, control, 6L, 0x43210L, 0x43210L, 0x210L, 0L, 0L, 1L, control, control or PanelFactory.MASK)
            val offs = listOf(4L, 4L, 8L, 12L, 16L, 20L, 0L, 0L, 0L, 4L, 4L)
            for (i in offs.indices) {
                val write = i in 1..6 || i == 10
                if (write) dev.write(offs[i], 4, vals[i]) else assertEquals(vals[i], dev.read(offs[i], 4))
            }
        }

        // Upgrade at intermediate valid state (e.g. position 1)
        assertEquals(0L, dev1.read(4, 4))
        val midSnap = onceComp.saveSnapshot()
        val (ctxMid, twiceMid) = create(PanelFactory.PROFILE_TWICE)
        twiceMid.restoreSnapshot(midSnap)
        assertEquals(1, twiceMid.state()["position"])

        // Continue cycle 1 on onceComp to position 11
        dev1.write(4, 4, firstControl)
        dev1.write(8, 4, 6L)
        dev1.write(12, 4, 0x43210L)
        dev1.write(16, 4, 0x43210L)
        dev1.write(20, 4, 0x210L)
        dev1.write(0, 4, 0L)
        assertEquals(0L, dev1.read(0, 4))
        assertEquals(1L, dev1.read(0, 4))
        assertEquals(firstControl, dev1.read(4, 4))
        dev1.write(4, 4, released)
        val checkpoint = onceComp.saveSnapshot()

        // Upgrade at position 11 -> complete cycle 2 on twice
        val (ctx2, twiceComp) = create(PanelFactory.PROFILE_TWICE)
        twiceComp.restoreSnapshot(checkpoint)
        runCycle(port(ctx2), released, released)
        assertFails { port(ctx2).read(4, 4) }

        val completedSnap = twiceComp.saveSnapshot()
        // Downgrade twice -> once at cycle 2 must fail atomically
        assertFailsWith<IllegalArgumentException> { onceComp.restoreSnapshot(completedSnap) }

        // Invalid snapshots reject atomically without mutating live state
        val before = twiceComp.state()
        for (bad in listOf(
            completedSnap + ("position" to 2), // control invalid for position 2
            completedSnap + ("control" to 0L),  // control=0 invalid at pos 22
            completedSnap + ("position" to 23),
        )) {
            assertFailsWith<IllegalArgumentException> { twiceComp.restoreSnapshot(bad) }
            assertEquals(before, twiceComp.state())
        }
    }

    @Test
    fun repeatableProfileAllowsThirdAndFourthCyclesPromotesFromTwiceAndRefusesDowngrade() {
        fun runCycle(dev: MmioDevice, initial: Long, control: Long) {
            val vals = listOf(initial, control, 6L, 0x43210L, 0x43210L, 0x210L, 0L, 0L, 1L, control, control or PanelFactory.MASK)
            val offs = listOf(4L, 4L, 8L, 12L, 16L, 20L, 0L, 0L, 0L, 4L, 4L)
            for (i in offs.indices) {
                val write = i in 1..6 || i == 10
                if (write) dev.write(offs[i], 4, vals[i]) else assertEquals(vals[i], dev.read(offs[i], 4))
            }
        }

        val (ctxTwice, twiceComp) = create(PanelFactory.PROFILE_TWICE)
        val devTwice = port(ctxTwice)
        val c1 = 0x01000fffL
        val r1 = c1 or PanelFactory.MASK
        runCycle(devTwice, 0L, c1)
        runCycle(devTwice, r1, r1)
        assertEquals(22, twiceComp.state()["position"])
        assertFails { devTwice.read(4, 4) }
        val twiceSnap = twiceComp.saveSnapshot()

        // Monotonic promotion twice -> repeatable and execute cycles 3..6
        val (ctxRep, repComp) = create(PanelFactory.PROFILE_REPEATABLE)
        repComp.restoreSnapshot(twiceSnap)
        val devRep = port(ctxRep)
        val c3 = r1 and 0xfefff000L
        runCycle(devRep, r1, c3)
        assertEquals(33, repComp.state()["position"])
        val r3 = c3 or PanelFactory.MASK
        runCycle(devRep, r3, r3)
        assertEquals(44, repComp.state()["position"])
        runCycle(devRep, r3, r3)
        assertEquals(55, repComp.state()["position"])

        // Wrong offset/control still strictly refused
        assertFails { devRep.read(0, 4) }
        assertEquals(55, repComp.state()["position"])

        // Round-trip repeatable snapshot & refuse downgrade into twice or once
        val repSnap = repComp.saveSnapshot()
        val (ctxRestored, repRestored) = create(PanelFactory.PROFILE_REPEATABLE)
        repRestored.restoreSnapshot(repSnap)
        assertEquals(repComp.state(), repRestored.state())
        runCycle(port(ctxRestored), r3, r3)
        assertEquals(66, repRestored.state()["position"])
        assertFailsWith<IllegalArgumentException> { twiceComp.restoreSnapshot(repSnap) }
        val (_, onceComp) = create(PanelFactory.PROFILE_ONCE)
        assertFailsWith<IllegalArgumentException> { onceComp.restoreSnapshot(repSnap) }
    }

    @Test
    fun repeatableA8000ProfileModelsToneLutResetPollJobIrq144AndSnapshotPromotion() {
        val irqHistory = mutableListOf<Pair<Int, Boolean>>()
        val ctx = TestContext()
        ctx.claims["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqHistory.add(irq to high)
            }
        }
        val comp = PanelFactory().create(ctx, mapOf("profile" to PanelFactory.PROFILE_REPEATABLE_A8000)) as StatefulComponent
        comp.start()
        assertEquals(
            listOf(ToneLutA8000Device.PORT_MAIN_V2, ToneLutA8000Device.PORT_TONE_LUT),
            comp.additionalMmioPorts(),
        )
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 144..144)), comp.interruptRoutes())
        val toneLut = assertNotNull(ctx.ports.find(ToneLutA8000Device.PORT_TONE_LUT, MmioDevice::class.java))

        // Reset poll sequence (0x011FC704..0x011FC740): clear bit 0, immediately reads back bit 0 = 1
        assertEquals(1L, toneLut.read(0x00, 4))
        toneLut.write(0x14, 4, 0x12345678L)
        toneLut.write(0x00, 4, 0L)
        assertEquals(1L, toneLut.read(0x00, 4))
        assertEquals(0L, toneLut.read(0x14, 4)) // cleared by reset

        // LUT upload sequence (0x011FC744..0x011FC808): +0x08 bit 0 = 0, write 1 to +0x04 -> clears bit 0, NO IRQ 144
        toneLut.write(0x0c, 4, 0x015b1940L)
        toneLut.write(0x10, 4, 0x00000100L)
        toneLut.write(0x08, 4, 0x0L)
        toneLut.write(0x04, 4, 0x1L)
        assertEquals(0L, toneLut.read(0x04, 4))
        assertTrue(irqHistory.isEmpty())

        // DMA job start (0x011FCC24): +0x08 bit 0 = 1, write 1 to +0x04 -> pulses GIC IRQ 144 (logical 115) and deasserts on beforeInstruction()
        toneLut.write(0x08, 4, 0x1L)
        toneLut.write(0x44, 4, 0xabcd0000L)
        toneLut.write(0x04, 4, 0x1L)
        assertEquals(0L, toneLut.read(0x04, 4))
        assertEquals(listOf(144 to true), irqHistory)
        toneLut.beforeInstruction(10L)
        assertEquals(listOf(144 to true, 144 to false), irqHistory)

        // Bounds & width enforcement
        assertFails { toneLut.read(0x48, 4) }
        assertFails { toneLut.write(0x00, 2, 0L) }

        // Snapshot round-trip & promotion from repeatable-v1
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val comp2 = PanelFactory().create(ctx2, mapOf("profile" to PanelFactory.PROFILE_REPEATABLE_A8000)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())
        assertEquals(0xabcd0000L, assertNotNull(ctx2.ports.find(ToneLutA8000Device.PORT_TONE_LUT, MmioDevice::class.java)).read(0x44, 4))

        val (_, legacyRep) = create(PanelFactory.PROFILE_REPEATABLE)
        val legacySnap = legacyRep.saveSnapshot()
        comp2.restoreSnapshot(legacySnap)
        assertEquals(1L, assertNotNull(ctx2.ports.find(ToneLutA8000Device.PORT_TONE_LUT, MmioDevice::class.java)).read(0x00, 4))
        assertFailsWith<IllegalArgumentException> { legacyRep.restoreSnapshot(snap) }
    }

    @Test
    fun toneLutA8000Executes24BitAnd16BitRxAndTxBitPackingDmaOverCpuMemory() {
        val ramBase = 0x1A00_0000L
        val ramBytes = ByteArray(4096)
        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= ramBase && size >= 0L && address + size <= ramBase + ramBytes.size) {
                    "Out-of-bounds RAM access 0x${address.toString(16)}+$size"
                }
            }
            override fun read(address: Long, destination: java.nio.ByteBuffer) {
                check(address, destination.remaining().toLong(), false)
                destination.put(ramBytes, (address - ramBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: java.nio.ByteBuffer) {
                check(address, source.remaining().toLong(), true)
                source.get(ramBytes, (address - ramBase).toInt(), source.remaining())
            }
        }

        val ctx = TestContext()
        val irqs = mutableListOf<Pair<Int, Boolean>>()
        ctx.ports.register("test-gic", "soc-gic.lines", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqs += irq to high }
        })
        ctx.ports.register("test-cpu", "cpu.memory", DmaMemory::class.java, ram)

        val comp = PanelFactory().create(ctx, mapOf("profile" to PanelFactory.PROFILE_REPEATABLE_A8000)) as StatefulComponent
        comp.start()
        val a8 = assertNotNull(ctx.ports.find(ToneLutA8000Device.PORT_TONE_LUT, MmioDevice::class.java))

        // 1. RX 24-bit (LUT 1): 4B I2S words [0, sLo, sHi, 0] -> 3B packed LE signed 24-bit [0, sLo, sHi]
        val srcOff = 0x000
        val dstOff = 0x200
        val rx24Words = intArrayOf(0x00200000, 0x00E00000, 0x00123400, 0x00FED000) // +8192<<8, -8192<<8, +0x123400, -0x013000
        val wb = java.nio.ByteBuffer.wrap(ramBytes, srcOff, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        rx24Words.forEach { wb.putInt(it) }

        a8.write(0x08, 4, 1L or (3L shl 8) or (2L shl 24)) // jobMode=1, srcFmt=3 (4B), dstFmt=2 (3B)
        a8.write(0x14, 4, 0L)
        a8.write(0x18, 4, ramBase + srcOff)
        a8.write(0x1c, 4, 16L) // srcLineBytes
        a8.write(0x20, 4, 1L)  // srcLines
        a8.write(0x24, 4, 0L)
        a8.write(0x28, 4, ramBase + dstOff)
        a8.write(0x2c, 4, 12L) // dstLineBytes
        a8.write(0x30, 4, 1L)  // dstLines
        a8.write(0x3c, 4, 1L)  // blockCount
        a8.write(0x04, 4, 1L)  // trigger DMA + IRQ 144

        assertEquals(listOf(144 to true), irqs)
        // Verify packed 24-bit LE samples match section4 0x0129B7DC peak meter decoding
        fun readSigned24Le(off: Int): Int {
            val raw = (ramBytes[off].toInt() and 0xFF) or
                ((ramBytes[off + 1].toInt() and 0xFF) shl 8) or
                ((ramBytes[off + 2].toInt() and 0xFF) shl 16)
            return if ((raw and 0x800000) != 0) raw or -0x1000000 else raw
        }
        assertEquals(0x00200000, readSigned24Le(dstOff))
        assertEquals(-0x00200000, readSigned24Le(dstOff + 3))
        assertEquals(0x00123400, readSigned24Le(dstOff + 6))
        assertEquals(-0x00013000, readSigned24Le(dstOff + 9))

        // 2. TX 24-bit (LUT 0): 3B packed LE -> 4B sign-extended 32-bit I2S words
        val txDstOff = 0x400
        a8.write(0x08, 4, 1L or (2L shl 8) or (3L shl 24)) // srcFmt=2 (3B), dstFmt=3 (4B)
        a8.write(0x18, 4, ramBase + dstOff)
        a8.write(0x1c, 4, 12L)
        a8.write(0x20, 4, 1L)
        a8.write(0x28, 4, ramBase + txDstOff)
        a8.write(0x2c, 4, 16L)
        a8.write(0x30, 4, 1L)
        a8.write(0x04, 4, 1L)
        val rb = java.nio.ByteBuffer.wrap(ramBytes, txDstOff, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x00200000, rb.int)
        assertEquals(0xFFE00000.toInt(), rb.int) // sign-extended!
        assertEquals(0x00123400, rb.int)
        assertEquals(0xFFFED000.toInt(), rb.int) // sign-extended!

        // 3. RX 16-bit (4B->2B) and TX 16-bit (2B->4B sign-extended)
        val rx16Dst = 0x600
        val tx16Dst = 0x700
        java.nio.ByteBuffer.wrap(ramBytes, srcOff, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x00001234)
            putInt(0xFFFF8001.toInt())
        }
        a8.write(0x08, 4, 1L or (3L shl 8) or (1L shl 24)) // 4B -> 2B
        a8.write(0x18, 4, ramBase + srcOff); a8.write(0x1c, 4, 8L); a8.write(0x20, 4, 1L)
        a8.write(0x28, 4, ramBase + rx16Dst); a8.write(0x2c, 4, 4L); a8.write(0x30, 4, 1L)
        a8.write(0x04, 4, 1L)
        val rx16 = java.nio.ByteBuffer.wrap(ramBytes, rx16Dst, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x1234.toShort(), rx16.short)
        assertEquals(0x8001.toShort(), rx16.short)

        a8.write(0x08, 4, 1L or (1L shl 8) or (3L shl 24)) // 2B -> 4B
        a8.write(0x18, 4, ramBase + rx16Dst); a8.write(0x1c, 4, 4L); a8.write(0x20, 4, 1L)
        a8.write(0x28, 4, ramBase + tx16Dst); a8.write(0x2c, 4, 8L); a8.write(0x30, 4, 1L)
        a8.write(0x04, 4, 1L)
        val tx16 = java.nio.ByteBuffer.wrap(ramBytes, tx16Dst, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x00001234, tx16.int)
        assertEquals(0xFFFF8001.toInt(), tx16.int)

        @Suppress("UNCHECKED_CAST")
        val a8State = comp.state()["toneLutA8000"] as Map<String, Any?>
        assertEquals(4L, a8State["dmaJobCount"])
        assertEquals(12L + 16L + 4L + 8L, a8State["dmaBytesWritten"])
        comp.close()
    }
}
