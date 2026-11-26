package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Verifies simultaneous Slot 0 (0xfffc4000, IRQ 230) and Slot 1 (0xfffc8000, IRQ 237) mounting and I/O. */
class DualSdSlotTest {
    private fun cidHex(seed: Int): String {
        val bytes = ByteArray(15) { ((it * 17 + seed) and 0xff).toByte() }
        var crc = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val feedback = ((b.toInt() ushr i) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 127
            if (feedback != 0) crc = crc xor 9
        }
        return (bytes + ((crc shl 1) or 1).toByte()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun dualSlotInput(): Map<String, Any?> = mapOf(
        "schema" to 1,
        "profile" to SdHostFactory.ADDRESS_PROFILE,
        "commandTicks" to 2,
        "blockTicks" to 3,
        "resetTicks" to 2,
        "addressHighBits" to mapOf("0" to 0, "1" to 0),
        "legacy" to mapOf(
            "schema" to 1,
            "profile" to SdHostFactory.PHY_PROFILE,
            "initialRegisters" to (0..1).associate { it.toString() to SdControlHost.fields.associateWith { 0L } },
            "clocks" to (0..3).associate { SdClockHost.key(it) to mapOf("initialWord" to 5, "bit1Samples" to listOf(0, 1)) },
            "initWords" to (0..1).associate {
                it.toString() to SdInitHost.fields.associateWith { name -> if (name == "host4028") 0x0f00L else 0L }
            },
        ),
        "cards" to mapOf(
            "0" to mapOf(
                "basis" to "SYNTHETIC",
                "capacityBytes" to 2L * 1024 * 1024,
                "highCapacity" to false,
                "readOnly" to false,
                "rca" to 0x1111,
                "cidHex" to cidHex(3),
                "ocrVoltageWindow" to 0xff8000L,
                "supportsHighSpeed" to true,
            ),
            "1" to mapOf(
                "basis" to "SYNTHETIC",
                "capacityBytes" to 4L * 1024 * 1024,
                "highCapacity" to false,
                "readOnly" to false,
                "rca" to 0x2222,
                "cidHex" to cidHex(19),
                "ocrVoltageWindow" to 0xff8000L,
                "supportsHighSpeed" to true,
            ),
        ),
    )

    private class DualMedia : CheckedBlockStoragePort {
        val slot1 = ByteArray(2 * 1024 * 1024) { ((it xor 0x3a) and 0xff).toByte() }
        val slot2 = ByteArray(4 * 1024 * 1024) { ((it xor 0xc5) and 0xff).toByte() }
        private fun backing(slot: Int): ByteArray = when (slot) {
            1 -> slot1
            2 -> slot2
            else -> throw IllegalArgumentException("unsupported slot $slot")
        }
        override fun size(slot: Int): Long? = when (slot) {
            1 -> slot1.size.toLong()
            2 -> slot2.size.toLong()
            else -> null
        }
        override fun readOnly(slot: Int): Boolean {
            require(slot == 1 || slot == 2)
            return false
        }
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            val data = backing(slot)
            require(offset >= 0 && length >= 0 && offset <= data.size - length)
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val data = backing(slot)
            val n = target.remaining()
            checkAccess(slot, offset, n.toLong(), false)
            target.put(data, offset.toInt(), n)
            return n
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val data = backing(slot)
            val n = source.remaining()
            checkAccess(slot, offset, n.toLong(), true)
            source.get(data, offset.toInt(), n)
            return n
        }
    }

    private class SimpleMemory : DmaMemory {
        val bytes = ByteArray(65536)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0 && size >= 0 && address <= bytes.size - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            check(address, n.toLong(), false)
            destination.put(bytes, address.toInt(), n)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining()
            check(address, n.toLong(), true)
            source.get(bytes, address.toInt(), n)
        }
    }

    private class Context(val data: Map<String, Any?>) : ComponentContext {
        var tick = 0L
        val entries = mutableMapOf<String, Any>()
        override val directory = Path.of("unused-dual-sd")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(entries.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("unused")
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unused")
            override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unused")
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String) = data
    }

    @Test
    fun mountsSlot0AndSlot1SimultaneouslyWithIndependentInitDmaReadAndIrqLines() {
        val data = dualSlotInput()
        val ctx = Context(data)
        val media = DualMedia()
        val mem = SimpleMemory()
        val irqLines = mutableMapOf<Int, Boolean>()
        ctx.entries["sdcard-reader"] = media
        ctx.entries["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLines[irq] = high }
        }

        val host = SdHostFactory().create(
            ctx,
            mapOf("profile" to SdHostFactory.ADDRESS_PROFILE, "inputFile" to "sd-card-dual.json"),
        )
        host.start()
        ctx.entries["cpu.memory"] = mem

        val bank0 = ctx.entries[SdTransactionHost.portForProfile(0, SdHostFactory.ADDRESS_PROFILE)] as MmioDevice
        val bank1 = ctx.entries[SdTransactionHost.portForProfile(1, SdHostFactory.ADDRESS_PROFILE)] as MmioDevice

        fun step(ticks: Long) {
            ctx.tick += ticks
            bank0.beforeInstruction(ctx.tick)
            bank1.beforeInstruction(ctx.tick)
        }
        fun issue(bank: MmioDevice, index: Int, argument: Long = 0, response: Int = 2, mode: Int = 0, hasData: Boolean = false) {
            bank.write(8, 2, argument and 0xffffL)
            bank.write(10, 2, (argument ushr 16) and 0xffffL)
            bank.write(12, 4, (((index shl 8) or response or (if (hasData) 0x20 else 0)).toLong() shl 16) or mode.toLong())
        }
        fun cmd(bank: MmioDevice, index: Int, argument: Long = 0, response: Int = 2) {
            issue(bank, index, argument, response)
            step(2)
            if (response == 3) step(3)
        }
        fun initCard(bank: MmioDevice, rca: Long) {
            bank.write(0x34, 2, 0xfb)
            bank.write(0x38, 2, 0xfb)
            cmd(bank, 0, response = 0)
            cmd(bank, 8, 0x1aa)
            cmd(bank, 55)
            cmd(bank, 41, 0xff8000L)
            cmd(bank, 2, response = 1)
            cmd(bank, 3)
            cmd(bank, 9, rca shl 16, response = 1)
            cmd(bank, 7, rca shl 16, response = 3)
            bank.write(0x30, 2, 0xfb) // clear status before DMA
        }

        step(2)
        initCard(bank0, 0x1111)
        initCard(bank1, 0x2222)

        // Read LBA 0 from Slot 0 -> DMA 0x0200 (fires IRQ 230 only)
        bank0.write(4, 2, 512)
        bank0.write(6, 2, 1)
        bank0.write(0, 4, 0x0200)
        issue(bank0, 17, argument = 0, response = 2, mode = 0x11, hasData = true)
        step(5)
        assertEquals(true, irqLines[230], "Slot 0 transfer must assert GIC IRQ 230")
        assertContentEquals(media.slot1.copyOfRange(0, 512), mem.bytes.copyOfRange(0x0200, 0x0400))
        bank0.write(0x30, 2, 0xfb)
        assertEquals(false, irqLines[230])

        // Read sector 1 (byte offset 512 in SDSC) from Slot 1 -> DMA 0x0600 (fires IRQ 237 only)
        bank1.write(4, 2, 512)
        bank1.write(6, 2, 1)
        bank1.write(0, 4, 0x0600)
        issue(bank1, 17, argument = 512, response = 2, mode = 0x11, hasData = true)
        step(5)
        assertEquals(true, irqLines[237], "Slot 1 transfer must assert GIC IRQ 237")
        assertEquals(false, irqLines[230], "Slot 1 transfer must not assert Slot 0 IRQ")
        assertContentEquals(media.slot2.copyOfRange(512, 1024), mem.bytes.copyOfRange(0x0600, 0x0800))

        // Verify distinct media bytes did not cross-contaminate
        assertFalse(mem.bytes.copyOfRange(0x0200, 0x0400).contentEquals(mem.bytes.copyOfRange(0x0600, 0x0800)))
        host.close()
    }
}
