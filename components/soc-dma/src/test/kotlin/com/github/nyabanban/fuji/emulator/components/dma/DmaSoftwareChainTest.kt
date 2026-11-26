package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

/**
 * Deterministic unit tests for HDMAC 6-word hardware Linked-List (LLI) software mem-to-mem chains
 * (`control = 0x11100001`, `route = 44`, `r[9] = firstDescAddr | 1L`, `DAT S4 0x011e5918..0x011e5a08`),
 * used by GFX100S 2.13 in-camera `RAW CONVERSION` to gather 8,760 strided RAW rows (`~207 MB`).
 */
class DmaSoftwareChainTest {
    @TempDir
    lateinit var temporary: Path

    private class Registry : Ports {
        private data class Entry(val type: Class<*>, val port: Any)
        private val data = mutableMapOf<String, Entry>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            require(type.isInstance(port) && name !in data)
            data[name] = Entry(type, port)
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? {
            val e = data[name] ?: return null
            require(e.type == type)
            return type.cast(e.port)
        }
        override fun removeOwner(owner: String) {}
    }

    private class Context(override val directory: Path) : ComponentContext {
        var tick = 0L
        override val clock = EmulationClock { tick }
        override val ports = Registry()
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun readData(relative: String): Map<String, Any?> = error("No data file required")
        override fun dataPath(relative: String) = directory.resolve(relative)
    }

    private class Ram(val base: Long = 0x0200_0000L, size: Int = 0x4_0000) : DmaMemory {
        val bytes = ByteArray(size)
        private val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun putU32(addr: Long, value: Long) {
            le.putInt((addr - base).toInt(), value.toInt())
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && size >= 0L && address - base <= bytes.size && size <= bytes.size - (address - base)) {
                "RAM preflight rejected addr=0x${address.toString(16)} size=$size"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            check(address, destination.remaining().toLong(), false)
            destination.put(bytes, (address - base).toInt(), destination.remaining())
        }

        override fun write(address: Long, source: ByteBuffer) {
            check(address, source.remaining().toLong(), true)
            source.get(bytes, (address - base).toInt(), source.remaining())
        }
    }

    private data class Fixture(
        val ctx: Context,
        val ram: Ram,
        val component: StatefulComponent,
        val dma: ScheduledMmioDevice,
        val mux: MmioDevice,
        val irqs: MutableMap<Int, Boolean>,
    )

    private fun fixture(): Fixture {
        val ctx = Context(temporary)
        val ram = Ram()
        val irqs = mutableMapOf<Int, Boolean>()
        ctx.ports.register("test", "cpu.memory", DmaMemory::class.java, ram)
        ctx.ports.register("test", "test.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqs[irq] = high
            }
        })
        val factory = DmaFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "interruptPort" to "test.irq",
            "softwareProfile" to "software-request44-experimental-v1",
        )
        val comp = factory.create(ctx, cfg) as StatefulComponent
        comp.start()
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
        val mux = checkNotNull(ctx.ports.find("soc-dma.mux", MmioDevice::class.java))
        return Fixture(ctx, ram, comp, dma, mux, irqs)
    }

    private fun writeDesc(ram: Ram, descAddr: Long, countBytes: Int, src: Long, dst: Long, srcGeom: Long, dstGeom: Long, nextWord: Long) {
        ram.putU32(descAddr + 0x00L, (countBytes - 1).toLong())
        ram.putU32(descAddr + 0x04L, src)
        ram.putU32(descAddr + 0x08L, dst)
        ram.putU32(descAddr + 0x0CL, srcGeom)
        ram.putU32(descAddr + 0x10L, dstGeom)
        ram.putU32(descAddr + 0x14L, nextWord)
    }

    private fun armCh1Route44(f: Fixture, firstDescAddr: Long) {
        // Channel 1 -> route 44 via strobe bit 24 (0x0100_0000) | (44 << 8)
        f.mux.write(0L, 4, 0x0100_0000L or (44L shl 8))
        f.dma.write(0x1010L, 4, 0L)
        f.dma.write(0x1014L, 4, 0x0200_1000L)
        f.dma.write(0x1018L, 4, 0x0201_0000L)
        f.dma.write(0x101CL, 4, 0x30F00L)
        f.dma.write(0x1020L, 4, 0x30F00L)
        f.dma.write(0x1024L, 4, firstDescAddr or 1L)
    }

    @Test
    fun executesMultiSegmentHardwareLliChainIncludingFixedFillAndRaisesIrq() {
        val f = fixture()
        val srcBase = 0x0200_1000L
        val dstBase = 0x0201_0000L
        val descBase = 0x0202_0000L

        // Populate 3 strided source rows (stride 0x100, row length 0x40) + 8-byte constant fill word
        for (row in 0 until 3) {
            val rowOff = (srcBase + row * 0x100L - f.ram.base).toInt()
            for (i in 0 until 0x40) {
                f.ram.bytes[rowOff + i] = ((row + 1) * 37 + i).toByte()
            }
        }
        val fillSrc = 0x0200_2000L
        for (i in 0 until 8) f.ram.bytes[(fillSrc - f.ram.base).toInt() + i] = (0xA0 + i).toByte()

        // 4-descriptor 32-byte-spaced LLI chain: 3 strided row copies + 1 fixed-source fill segment
        writeDesc(f.ram, descBase + 0x00L, 0x40, srcBase + 0x000L, dstBase + 0x000L, 0x30F00L, 0x30F00L, (descBase + 0x20L) or 1L)
        writeDesc(f.ram, descBase + 0x20L, 0x40, srcBase + 0x100L, dstBase + 0x040L, 0x30F00L, 0x30F00L, (descBase + 0x40L) or 1L)
        writeDesc(f.ram, descBase + 0x40L, 0x40, srcBase + 0x200L, dstBase + 0x080L, 0x30F00L, 0x30F00L, (descBase + 0x60L) or 1L)
        writeDesc(f.ram, descBase + 0x60L, 0x20, fillSrc, dstBase + 0x0C0L, 0x30F04L, 0x30F00L, descBase + 0x80L) // bit 0 == 0 terminates

        armCh1Route44(f, descBase)
        f.ctx.tick = 100L
        f.dma.write(0x1028L, 4, 0x1110_0001L)

        val due = assertNotNull(f.dma.nextDeadline())
        assertEquals(1124L, due)
        assertEquals(0x10000L, f.dma.read(0x1030L, 4)) // busy
        assertNotEquals(true, f.irqs[208])

        // Snapshot round-trip while pending softwareChain is in flight
        val snap = f.component.saveSnapshot()
        val f2 = fixture()
        f.ram.bytes.copyInto(f2.ram.bytes)
        f2.component.restoreSnapshot(snap)
        assertEquals(due, f2.dma.nextDeadline())

        f2.ctx.tick = due
        f2.dma.beforeInstruction(due)
        assertNull(f2.dma.nextDeadline())
        assertEquals(1L, f2.dma.read(0x1030L, 4))
        assertEquals(true, f2.irqs[208])

        // Verify all 3 strided rows gathered contiguously + 4x 8-byte fixed fill repeats
        for (row in 0 until 3) {
            val sOff = (srcBase + row * 0x100L - f2.ram.base).toInt()
            val dOff = (dstBase + row * 0x40L - f2.ram.base).toInt()
            assertContentEquals(
                f2.ram.bytes.copyOfRange(sOff, sOff + 0x40),
                f2.ram.bytes.copyOfRange(dOff, dOff + 0x40),
            )
        }
        val fillDstOff = (dstBase + 0x0C0L - f2.ram.base).toInt()
        for (rep in 0 until 4) {
            for (i in 0 until 8) {
                assertEquals((0xA0 + i).toByte(), f2.ram.bytes[fillDstOff + rep * 8 + i])
            }
        }
        assertEquals(dstBase + 0x0E0L, f2.dma.read(0x1018L, 4))
        assertEquals(descBase + 0x80L, f2.dma.read(0x1024L, 4))

        // W1C clears IRQ 208 on next tick sync
        f2.dma.write(0x1030L, 4, 1L)
        f2.dma.beforeInstruction(f2.ctx.tick + 1L)
        assertEquals(false, f2.irqs[208])

        f.component.close()
        f2.component.close()
    }

    @Test
    fun rejectsNonRoute44SoftwareChainAndDetectsDescriptorCycle() {
        // 1. Non-route-44 refused at submission time without mutating state
        run {
            val f = fixture()
            f.mux.write(0L, 4, 0x0100_0000L or (21L shl 8))
            f.dma.write(0x101CL, 4, 0x30F00L)
            f.dma.write(0x1020L, 4, 0x30F00L)
            f.dma.write(0x1024L, 4, 0x0202_0001L)
            assertFailsWith<IllegalArgumentException> {
                f.dma.write(0x1028L, 4, 0x1110_0001L)
            }
            assertNull(f.dma.nextDeadline())
            f.component.close()
        }

        // 2. Self-referential descriptor cycle fails safely at execution time
        run {
            val f = fixture()
            val descAddr = 0x0202_0000L
            writeDesc(f.ram, descAddr, 0x40, 0x0200_1000L, 0x0201_0000L, 0x30F00L, 0x30F00L, descAddr or 1L)
            armCh1Route44(f, descAddr)
            f.ctx.tick = 10L
            f.dma.write(0x1028L, 4, 0x1110_0001L)
            val due = assertNotNull(f.dma.nextDeadline())
            assertFailsWith<IllegalArgumentException> {
                f.dma.beforeInstruction(due)
            }
            f.component.close()
        }
    }

    private class DualBankRam(
        val lowBase: Long = 0x0580_0000L,
        lowSize: Int = 0x10_0000,
        val highBase: Long = 0x1_6b6e_0000L,
        highSize: Int = 0x2_0000,
    ) : DmaMemory {
        val lowBytes = ByteArray(lowSize)
        val highBytes = ByteArray(highSize)

        private fun resolveBank(address: Long, size: Long): Pair<ByteArray, Int> {
            if (address >= lowBase && size >= 0L && address - lowBase <= lowBytes.size && size <= lowBytes.size - (address - lowBase)) {
                return lowBytes to (address - lowBase).toInt()
            }
            if (address >= highBase && size >= 0L && address - highBase <= highBytes.size && size <= highBytes.size - (address - highBase)) {
                return highBytes to (address - highBase).toInt()
            }
            throw IllegalArgumentException("DualBankRam rejected addr=0x${address.toString(16)} size=$size")
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            resolveBank(address, size)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            val (bank, off) = resolveBank(address, len.toLong())
            destination.put(bank, off, len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            val (bank, off) = resolveBank(address, len.toLong())
            source.get(bank, off, len)
        }
    }

    @Test
    fun copies34BitAxiHighSourceTailToLowBounceBufferViaRequestsBank() {
        // Reproduces BUG-V3-9-4 exact GFX100S 2.13 firmware path:
        // JPEG encoder writes 102MP JPEG into 34-bit high PA (0x1_6b6eb400), then HDMAC ch1 copies
        // the trailing 238-byte partial sector into low-32-bit FATFS bounce buffer (0x058b3940)
        // with 0xFFF4A808 (requests[2], ch1 srcHi) = 1 and 0xFFF4A80C (requests[3], ch1 dstHi) = 0.
        val ctx = Context(temporary)
        val ram = DualBankRam()
        val irqs = mutableMapOf<Int, Boolean>()
        ctx.ports.register("test", "cpu.memory", DmaMemory::class.java, ram)
        ctx.ports.register("test", "test.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqs[irq] = high }
        })
        val factory = DmaFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "interruptPort" to "test.irq",
            "softwareProfile" to "software-request44-experimental-v1",
        )
        val comp = factory.create(ctx, cfg) as StatefulComponent
        comp.start()
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
        val mux = checkNotNull(ctx.ports.find("soc-dma.mux", MmioDevice::class.java))
        val requests = checkNotNull(ctx.ports.find("soc-dma.requests", MmioDevice::class.java))

        val srcLow = 0x6b6e_b400L
        val dstLow = 0x058b_3940L
        val count = 238
        val srcOff = (0x1_6b6e_b400L - ram.highBase).toInt()
        for (i in 0 until count - 2) ram.highBytes[srcOff + i] = ((i * 31 + 7) and 0xFF).toByte()
        ram.highBytes[srcOff + count - 2] = 0xFF.toByte()
        ram.highBytes[srcOff + count - 1] = 0xD9.toByte()

        // Program ch1 route 44 + 34-bit AXI upper address bank: ch1 srcHi = 1 (+0x08), ch1 dstHi = 0 (+0x0C)
        mux.write(0L, 4, 0x0100_0000L or (44L shl 8))
        requests.write(0x08L, 4, 1L)
        requests.write(0x0CL, 4, 0L)
        dma.write(0x1010L, 4, (count - 1).toLong())
        dma.write(0x1014L, 4, srcLow)
        dma.write(0x1018L, 4, dstLow)
        dma.write(0x101CL, 4, 0x30F00L)
        dma.write(0x1020L, 4, 0x30F00L)
        dma.write(0x1024L, 4, 0L)
        ctx.tick = 100L
        dma.write(0x1028L, 4, 0x1110_0001L)

        val due = assertNotNull(dma.nextDeadline())
        ctx.tick = due
        dma.beforeInstruction(due)
        assertNull(dma.nextDeadline())
        assertEquals(true, irqs[208])

        val dstOff = (dstLow - ram.lowBase).toInt()
        assertContentEquals(
            ram.highBytes.copyOfRange(srcOff, srcOff + count),
            ram.lowBytes.copyOfRange(dstOff, dstOff + count),
        )
        assertEquals(0xFF, ram.lowBytes[dstOff + count - 2].toInt() and 0xFF)
        assertEquals(0xD9, ram.lowBytes[dstOff + count - 1].toInt() and 0xFF)

        // Reverse direction: low-32-bit -> 34-bit high destination (ch1 srcHi=0, dstHi=1)
        val highDstLow = 0x6b6e_c000L
        val highDstOff = (0x1_6b6e_c000L - ram.highBase).toInt()
        dma.write(0x1030L, 4, 1L)
        requests.write(0x08L, 4, 0L)
        requests.write(0x0CL, 4, 1L)
        dma.write(0x1010L, 4, (count - 1).toLong())
        dma.write(0x1014L, 4, dstLow)
        dma.write(0x1018L, 4, highDstLow)
        dma.write(0x1028L, 4, 0x1110_0001L)
        val due2 = assertNotNull(dma.nextDeadline())
        ctx.tick = due2
        dma.beforeInstruction(due2)
        assertContentEquals(
            ram.lowBytes.copyOfRange(dstOff, dstOff + count),
            ram.highBytes.copyOfRange(highDstOff, highDstOff + count),
        )

        // Snapshot round-trip preserves 34-bit event addresses cleanly
        val snap = comp.saveSnapshot()
        comp.restoreSnapshot(snap)
        comp.close()
    }
}
