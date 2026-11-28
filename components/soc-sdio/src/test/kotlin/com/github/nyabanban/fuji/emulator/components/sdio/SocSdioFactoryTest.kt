package com.github.nyabanban.fuji.emulator.components.sdio

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SocSdioFactoryTest {
    private class FakeDmaMemory(size: Int = 65536) : DmaMemory {
        val ram = ByteArray(size)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0 && address + size <= ram.size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            destination.put(ram, address.toInt(), len)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            source.get(ram, address.toInt(), len)
        }
    }

    private class TestContext : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                claims[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? =
                claims[name]?.takeIf { type.isInstance(it) }?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    @Test
    fun disabledProfilePublishesNoPortsOrIrqs() {
        val ctx = TestContext()
        val comp = SocSdioFactory().create(ctx, mapOf("profile" to "disabled"))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertTrue(comp.interruptRoutes().isEmpty())
        assertNull(ctx.ports.find(SocSdioFactory.MMIO_PORT, MmioDevice::class.java))
        assertNull(ctx.ports.find(SocSdioFactory.BUS_PORT, SdioBusPort::class.java))
        comp.close()
    }

    @Test
    fun pureSdhciControllerRoutesCommandsPioSdmaAdma2AndIrqs224_225() {
        val ctx = TestContext()
        val mem = FakeDmaMemory()
        ctx.claims["cpu.memory"] = mem
        val irqState = mutableMapOf<Int, Boolean>()
        ctx.claims["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqState[irq] = high
            }
        }
        val comp = SocSdioFactory().create(
            ctx,
            mapOf("profile" to SocSdioFactory.PROFILE, "irqEnabled" to true),
        ) as StatefulComponent
        assertEquals(listOf(SocSdioFactory.MMIO_PORT), comp.additionalMmioPorts())
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 224..225)), comp.interruptRoutes())
        comp.start()

        val mmio = ctx.ports.find(SocSdioFactory.MMIO_PORT, MmioDevice::class.java)!!
        val bus = ctx.ports.find(SocSdioFactory.BUS_PORT, SdioBusPort::class.java)!!

        // Enable normal & error interrupt status + signals
        mmio.write(0x34, 2, 0x01FFL)
        mmio.write(0x36, 2, 0x00FFL)
        mmio.write(0x38, 2, 0x01FFL)
        mmio.write(0x3A, 2, 0x00FFL)

        // Internal clock enable -> immediately reads stable
        mmio.write(0x2C, 2, 0x0001L)
        assertEquals(0x0003L, mmio.read(0x2C, 2) and 0x0003L)

        // No card attached -> CMD5 sets ERR_CMD_TIMEOUT and asserts host IRQ 224
        mmio.write(0x08, 4, 0L)
        mmio.write(0x0E, 2, (5L shl 8) or 0x02L)
        assertEquals(1L, mmio.read(0x32, 2) and 1L)
        assertEquals(true, irqState[224])
        mmio.write(0x32, 2, 1L)
        assertEquals(false, irqState[224])

        // Attach generic chip-agnostic mock SDIO card
        var receivedWrite: ByteArray? = null
        var cardIrq = false
        val mockCard = object : SdioCardPort {
            override fun reset() {}
            override fun isCardInterruptAsserted(): Boolean = cardIrq
            override fun executeCommand(cmdIndex: Int, argument: Long, blockSize: Int, blockCount: Int): SdioCommandResult =
                when (cmdIndex) {
                    5 -> SdioCommandResult(intArrayOf(0xB0FF8000L.toInt(), 0, 0, 0), accepted = true)
                    53 -> {
                        val write = ((argument ushr 31) and 1L) != 0L
                        if (write) {
                            SdioCommandResult(intArrayOf(0x1000, 0, 0, 0), accepted = true, expectDataInBytes = 8)
                        } else {
                            SdioCommandResult(
                                intArrayOf(0x1000, 0, 0, 0),
                                accepted = true,
                                dataOut = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
                            )
                        }
                    }
                    else -> SdioCommandResult(intArrayOf(0x1042, 0, 0, 0), accepted = true)
                }

            override fun writeTransferData(cmdIndex: Int, argument: Long, data: ByteArray): SdioCommandResult {
                receivedWrite = data.copyOf()
                return SdioCommandResult(intArrayOf(0x1000, 0, 0, 0), accepted = true)
            }
        }
        bus.attachCard(mockCard)
        assertEquals(true, (mmio.read(0x24, 4) and (1L shl 16)) != 0L)

        // 1. CMD5 returns OCR
        mmio.write(0x0E, 2, (5L shl 8) or 0x02L)
        assertEquals(0xB0FF8000L, mmio.read(0x10, 4))
        assertEquals(true, irqState[224])
        mmio.write(0x30, 2, 0x0001L)

        // 2. CMD53 SDMA Read -> writes 8 bytes to guest RAM 0x1000
        mmio.write(0x00, 4, 0x1000L) // SDMA_SYS_ADDR
        mmio.write(0x04, 2, 8L)      // BLOCK_SIZE
        mmio.write(0x06, 2, 1L)      // BLOCK_COUNT
        mmio.write(0x08, 4, (1L shl 28) or 8L) // F1 byte read 8B
        mmio.write(0x0C, 2, 0x0011L) // DMA_EN | READ
        mmio.write(0x0E, 2, (53L shl 8) or 0x22L) // CMD53 DATA_PRESENT
        assertEquals(0x11, mem.ram[0x1000].toInt() and 0xff)
        assertEquals(0x88, mem.ram[0x1007].toInt() and 0xff)
        assertEquals(0x0003L, mmio.read(0x30, 2) and 0x0003L)
        mmio.write(0x30, 2, 0x0003L)

        // 3. CMD53 32-bit ADMA2 Write -> scatter/gather from RAM 0x2000 (4B) + 0x2100 (4B)
        byteArrayOf(0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte()).copyInto(mem.ram, 0x2000)
        byteArrayOf(0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte()).copyInto(mem.ram, 0x2100)
        val desc = ByteBuffer.wrap(mem.ram, 0x3000, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        desc.putShort(0x0021) // VALID | TRAN
        desc.putShort(4)
        desc.putInt(0x2000)
        desc.putShort(0x0023) // VALID | END | TRAN
        desc.putShort(4)
        desc.putInt(0x2100)
        mmio.write(0x28, 1, 0x10L) // HOST_CONTROL_1 DMA_SELECT=10 (32-bit ADMA2)
        mmio.write(0x58, 4, 0x3000L) // ADMA_SYS_ADDR_LO
        mmio.write(0x08, 4, (1L shl 31) or (1L shl 28) or 8L) // F1 write 8B
        mmio.write(0x0C, 2, 0x0001L) // DMA_EN | WRITE
        mmio.write(0x0E, 2, (53L shl 8) or 0x22L)
        assertContentEquals(
            byteArrayOf(0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte()),
            receivedWrite,
        )
        mmio.write(0x30, 2, 0x0003L)

        // 4. Card interrupt asserts GIC 225 and bit 8 of Normal Int Status
        cardIrq = true
        bus.setCardInterrupt(true)
        assertEquals(true, irqState[225])
        assertEquals(SocSdioFactory.INT_CARD_INT.toLong(), mmio.read(0x30, 2) and SocSdioFactory.INT_CARD_INT.toLong())
        cardIrq = false
        bus.setCardInterrupt(false)
        assertEquals(false, irqState[225])

        // 5. Async background-thread setCardInterrupt defers GIC mutation safely to owner beforeInstruction()
        var gicCallerThread: Thread? = null
        val ownerThread = Thread.currentThread()
        val strictCtx = TestContext()
        strictCtx.claims["cpu.memory"] = mem
        val strictIrqState = mutableMapOf<Int, Boolean>()
        strictCtx.claims["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                gicCallerThread = Thread.currentThread()
                strictIrqState[irq] = high
            }
        }
        val strictComp = SocSdioFactory().create(
            strictCtx,
            mapOf("profile" to SocSdioFactory.PROFILE, "irqEnabled" to true),
        ) as StatefulComponent
        strictComp.start()
        val strictMmio = strictCtx.ports.find(SocSdioFactory.MMIO_PORT, MmioDevice::class.java) as TickAwareMmioDevice
        val strictBus = strictCtx.ports.find(SocSdioFactory.BUS_PORT, SdioBusPort::class.java)!!
        strictMmio.write(0x34, 2, 0x01FFL)
        strictMmio.write(0x38, 2, 0x01FFL)
        strictIrqState[225] = false
        gicCallerThread = null

        val bg = Thread {
            strictBus.setCardInterrupt(true)
        }
        bg.start()
        bg.join()
        // Off-owner thread must NOT touch GIC directly; instead schedules immediate tick 0 wakeup
        assertNull(gicCallerThread)
        assertEquals(0L, strictMmio.nextInterestedTick())
        strictMmio.beforeInstruction(100L)
        assertSame(ownerThread, gicCallerThread)
        assertEquals(true, strictIrqState[225])
        assertEquals(Long.MAX_VALUE, strictMmio.nextInterestedTick())
        strictComp.close()

        // 6. Snapshot round-trip
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        ctx2.claims["soc-gic.lines"] = ctx.claims["soc-gic.lines"]!!
        val comp2 = SocSdioFactory().create(ctx2, mapOf("profile" to SocSdioFactory.PROFILE, "irqEnabled" to true)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state()["commandCount"], comp2.state()["commandCount"])
        assertEquals(comp.state()["dmaBytesTransferred"], comp2.state()["dmaBytesTransferred"])
        comp.close()
        comp2.close()
    }
}
