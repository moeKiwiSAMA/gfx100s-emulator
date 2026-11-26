package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SdDualConfigAndLiveHotPlugTest {
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

    private fun v9Input(
        slot0Cap: Long = 4L * 1024L * 1024L * 1024L,
        slot0Ro: Boolean = false,
        slot1PresentInJson: Boolean = true,
        slot1Cap: Long = 4L * 1024L * 1024L * 1024L,
        slot1Ro: Boolean = false,
    ): Map<String, Any?> {
        val uhsRow = SdTransactionHost.uhsPhyFields.associateWith { 0L }
        val vpRow = SdTransactionHost.vendorPhyFields.associateWith { if (it == "phy607c") 1L else 0L }
        val slot0Card = mapOf(
            "basis" to "SYNTHETIC",
            "capacityBytes" to slot0Cap,
            "highCapacity" to (slot0Cap > 2L * 1024L * 1024L * 1024L),
            "readOnly" to slot0Ro,
            "rca" to 1,
            "cidHex" to cidHex(3),
            "ocrVoltageWindow" to 0x00ff8000L,
            "supportsHighSpeed" to true,
        )
        val slot1Card = if (slot1PresentInJson) {
            mapOf(
                "basis" to "SYNTHETIC",
                "capacityBytes" to slot1Cap,
                "highCapacity" to (slot1Cap > 2L * 1024L * 1024L * 1024L),
                "readOnly" to slot1Ro,
                "rca" to 2,
                "cidHex" to cidHex(19),
                "ocrVoltageWindow" to 0x00ff8000L,
                "supportsHighSpeed" to true,
            )
        } else null

        return mapOf(
            "schema" to 1,
            "profile" to SdHostFactory.UHS_PHY_PROFILE,
            "commandTicks" to 2,
            "blockTicks" to 3,
            "resetTicks" to 2,
            "addressHighBits" to mapOf("0" to 0, "1" to 0),
            "vendorPhyWords" to mapOf("0" to vpRow, "1" to vpRow),
            "uhsPhyWords" to mapOf("0" to uhsRow, "1" to uhsRow),
            "legacy" to mapOf(
                "schema" to 1,
                "profile" to SdHostFactory.PHY_PROFILE,
                "initialRegisters" to (0..1).associate { it.toString() to SdControlHost.fields.associateWith { 0L } },
                "clocks" to (0..3).associate { SdClockHost.key(it) to mapOf("initialWord" to 5, "bit1Samples" to listOf(0, 1)) },
                "initWords" to (0..1).associate {
                    it.toString() to SdInitHost.fields.associateWith { name -> if (name == "host4028") 0x0f00L else 0L }
                },
            ),
            "cards" to mapOf("0" to slot0Card, "1" to slot1Card),
        )
    }

    private class LiveMockMedia(
        val slotData: Array<ByteArray?>,
        val slotInserted: BooleanArray,
        val slotReadOnly: BooleanArray,
    ) : CheckedBlockStoragePort, HotPlugCardControlPort {
        override fun size(slot: Int): Long? {
            val idx = slot - 1
            return if (slotInserted[idx]) slotData[idx]?.size?.toLong() else null
        }
        override fun readOnly(slot: Int): Boolean {
            val idx = slot - 1
            check(slotInserted[idx] && slotData[idx] != null) { "Slot $slot empty" }
            return slotReadOnly[idx]
        }
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            val idx = slot - 1
            val buf = checkNotNull(if (slotInserted[idx]) slotData[idx] else null) { "Slot $slot empty" }
            require(offset >= 0 && length >= 0 && offset <= buf.size - length)
            require(!write || !slotReadOnly[idx]) { "SD slot write-protected" }
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val n = target.remaining()
            checkAccess(slot, offset, n.toLong(), false)
            target.put(slotData[slot - 1]!!, offset.toInt(), n)
            return n
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val n = source.remaining()
            checkAccess(slot, offset, n.toLong(), true)
            source.get(slotData[slot - 1]!!, offset.toInt(), n)
            return n
        }
        override fun insertCard(slot: Int, readyDelayTicks: Long): SdSlotHotPlugStatus {
            slotInserted[slot - 1] = true
            return slotHotPlugStatus(slot)
        }
        override fun ejectCard(slot: Int): SdSlotHotPlugStatus {
            slotInserted[slot - 1] = false
            return slotHotPlugStatus(slot)
        }
        override fun slotHotPlugStatus(slot: Int): SdSlotHotPlugStatus {
            val inserted = slotInserted[slot - 1] && slotData[slot - 1] != null
            return SdSlotHotPlugStatus(slot, inserted, inserted, if (inserted) 0L else null, if (inserted) 0L else null, 0L)
        }
        override fun isMediaReady(slot: Int): Boolean = slotInserted[slot - 1] && slotData[slot - 1] != null
    }

    private class EventContext(val data: Map<String, Any?>) : ComponentContext {
        var tick = 0L
        val entries = mutableMapOf<String, Any>()
        private val listeners = mutableListOf<(ComponentEvent) -> Unit>()
        override val directory = Path.of("unused-sd-dualconfig")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(entries.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) = Unit
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {
                listeners.toList().forEach { it(event) }
            }
            override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable {
                listeners += listener
                return AutoCloseable { listeners -= listener }
            }
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String) = data
    }

    private class SimpleMemory : DmaMemory {
        val bytes = ByteArray(65536)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0 && size >= 0 && address <= bytes.size - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining(); check(address, n.toLong(), false); destination.put(bytes, address.toInt(), n)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining(); check(address, n.toLong(), true); source.get(bytes, address.toInt(), n)
        }
    }

    @Test
    fun sdcardReaderIsAuthoritativeWhenDifferingFromSdCardV9Json() {
        // BUG-08 exact repro: sd-card-v9.json declares two 4 GiB writable cards,
        // while sdcard-reader configures slot 1 as 2 MiB read-only and slot 2 as uninserted!
        val slot1Bytes = ByteArray(2 * 1024 * 1024) { ((it * 7 + 5) and 0xff).toByte() }
        val media = LiveMockMedia(
            slotData = arrayOf(slot1Bytes, ByteArray(1024 * 1024)),
            slotInserted = booleanArrayOf(true, false),
            slotReadOnly = booleanArrayOf(true, false),
        )
        val ctx = EventContext(v9Input(slot0Cap = 4L * 1024L * 1024L * 1024L, slot0Ro = false, slot1PresentInJson = true))
        val irqLines = mutableMapOf<Int, Boolean>()
        ctx.entries["sdcard-reader"] = media
        ctx.entries["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLines[irq] = high } }

        val host = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "sd-card-v9.json"))
        // Must NOT throw IllegalArgumentException("SD identity/media capacity or protection differs")!
        host.start()
        try {
            ctx.entries["cpu.memory"] = SimpleMemory()
            val bank0 = ctx.entries[SdTransactionHost.portForProfile(0, SdHostFactory.UHS_PHY_PROFILE)] as MmioDevice
            val bank1 = ctx.entries[SdTransactionHost.portForProfile(1, SdHostFactory.UHS_PHY_PROFILE)] as MmioDevice

            // Slot 0: inserted + stable + CD + WP (bit 19 = 0x80000)
            assertEquals(0x000f0000L, bank0.read(0x24, 4) and 0x000f0000L)
            // Slot 1: ejected (bit 17 stable only; bits 16/18/19 clear)
            assertEquals(0x00020000L, bank1.read(0x24, 4) and 0x000f0000L)

            // Reconciled capacity and readOnly reflected in slot state
            @Suppress("UNCHECKED_CAST")
            val slot0Card = ((host.state()["slots"] as List<Map<String, Any?>>)[0]["card"] as Map<String, Any?>)["identity"] as Map<String, Any?>
            assertEquals(2L * 1024L * 1024L, slot0Card["capacityBytes"])
            assertEquals(true, slot0Card["readOnly"])

            // Command to ejected slot 1 fails cleanly
            ctx.tick += 2; bank0.beforeInstruction(ctx.tick); bank1.beforeInstruction(ctx.tick)
            assertFailsWith<IllegalArgumentException> { bank1.write(12, 4, 0L) }
        } finally {
            host.close()
        }
    }

    @Test
    fun liveHotPlugAndWriteProtectEventsUpdateHostPresentStateIrqsAndCommands() {
        val slot1Bytes = ByteArray(2 * 1024 * 1024) { ((it xor 0x3c) and 0xff).toByte() }
        val slot2Bytes = ByteArray(1 * 1024 * 1024) { ((it xor 0x99) and 0xff).toByte() }
        val media = LiveMockMedia(
            slotData = arrayOf(slot1Bytes, slot2Bytes),
            slotInserted = booleanArrayOf(true, false),
            slotReadOnly = booleanArrayOf(false, false),
        )
        // Slot 1 is null in JSON too, verifying synthetic card creation on live insert!
        val ctx = EventContext(v9Input(slot0Cap = 2L * 1024L * 1024L, slot0Ro = false, slot1PresentInJson = false))
        val irqLines = mutableMapOf<Int, Boolean>()
        val mem = SimpleMemory()
        ctx.entries["sdcard-reader"] = media
        ctx.entries["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLines[irq] = high } }

        val host = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "sd-card-v9.json"))
        host.start()
        try {
            ctx.entries["cpu.memory"] = mem
            val bank0 = ctx.entries[SdTransactionHost.portForProfile(0, SdHostFactory.UHS_PHY_PROFILE)] as MmioDevice
            val bank1 = ctx.entries[SdTransactionHost.portForProfile(1, SdHostFactory.UHS_PHY_PROFILE)] as MmioDevice

            fun step(bank: MmioDevice, ticks: Long) {
                ctx.tick += ticks
                bank.beforeInstruction(ctx.tick)
            }
            fun cmd(bank: MmioDevice, index: Int, arg: Long = 0L, resp: Int = 2) {
                bank.write(8, 2, arg and 0xffff)
                bank.write(10, 2, arg ushr 16)
                bank.write(12, 4, (((index shl 8) or resp).toLong() shl 16))
                step(bank, 2)
                if (resp == 3) step(bank, 3)
            }
            fun initCard(bank: MmioDevice, rca: Long) {
                bank.write(0x34, 2, 0xfb)
                bank.write(0x38, 2, 0xfb)
                cmd(bank, 0, resp = 0)
                cmd(bank, 8, 0x1aa)
                cmd(bank, 55)
                cmd(bank, 41, 0xff8000)
                cmd(bank, 2, resp = 1)
                cmd(bank, 3)
                cmd(bank, 9, rca shl 16, resp = 1)
                cmd(bank, 7, rca shl 16, resp = 3)
                bank.write(0x30, 2, 0xfb)
            }

            step(bank0, 2); step(bank1, 2)
            initCard(bank0, 1)

            // Live toggle slot 1 write-protect ON -> PRESENT_STATE bit 19 sets and CMD24 refuses
            assertEquals(0L, bank0.read(0x24, 4) and 0x80000L)
            media.slotReadOnly[0] = true
            ctx.events.publish(ComponentEvent("sdcard-reader", "write-protect", ctx.tick, mapOf("slot" to 1, "readOnly" to true)))
            assertEquals(0x80000L, bank0.read(0x24, 4) and 0x80000L)
            bank0.write(4, 2, 512); bank0.write(6, 2, 1); bank0.write(0, 4, 0)
            bank0.write(8, 2, 0); bank0.write(10, 2, 0)
            bank0.write(12, 4, 0x183a0001L)
            step(bank0, 2)
            assertEquals(0x0400L, bank0.read(0x12, 2) and 0x0400L)

            // Live toggle slot 1 write-protect OFF -> PRESENT_STATE bit 19 clears and CMD24 succeeds
            media.slotReadOnly[0] = false
            ctx.events.publish(ComponentEvent("sdcard-reader", "write-protect", ctx.tick, mapOf("slot" to 1, "readOnly" to false)))
            assertEquals(0L, bank0.read(0x24, 4) and 0x80000L)
            mem.bytes.fill(0x5a, 0, 512)
            bank0.write(4, 2, 512); bank0.write(6, 2, 1); bank0.write(0, 4, 0)
            bank0.write(8, 2, 0); bank0.write(10, 2, 0)
            bank0.write(12, 4, 0x183a0001L)
            step(bank0, 8)
            assertEquals(0x5a.toByte(), slot1Bytes[0])

            // Live eject slot 1 -> latches CARD_REMOVAL (0x80) and clears PRESENT_STATE bit 16/18
            bank0.write(0x30, 2, 0xfb)
            media.ejectCard(1)
            ctx.events.publish(ComponentEvent("sdcard-reader", "hotplug", ctx.tick, mapOf("slot" to 1, "action" to "eject")))
            assertEquals(0x80L, bank0.read(0x30, 2) and 0x80L)
            assertEquals(true, irqLines[230])
            assertEquals(0x00020000L, bank0.read(0x24, 4) and 0x000f0000L)

            // Live insert slot 2 (which was null in JSON!) -> latches CARD_INSERTION (0x40), initializes & reads LBA 0
            bank1.write(0x34, 2, 0xfb)
            bank1.write(0x38, 2, 0xfb)
            media.insertCard(2)
            ctx.events.publish(ComponentEvent("sdcard-reader", "hotplug", ctx.tick, mapOf("slot" to 2, "action" to "insert")))
            assertEquals(0x40L, bank1.read(0x30, 2) and 0x40L)
            assertEquals(true, irqLines[237])
            assertEquals(0x00070000L, bank1.read(0x24, 4) and 0x000f0000L)

            initCard(bank1, 2)
            bank1.write(4, 2, 512); bank1.write(6, 2, 1); bank1.write(0, 4, 0x1000)
            bank1.write(8, 2, 0); bank1.write(10, 2, 0)
            bank1.write(12, 4, 0x113a0011L)
            step(bank1, 5)
            assertContentEquals(slot2Bytes.copyOfRange(0, 512), mem.bytes.copyOfRange(0x1000, 0x1200))
        } finally {
            host.close()
        }
    }

    @Test
    fun secondaryClockControlTracksInternalClockEnableAcrossRepeatedHotReinsertAndSnapshot() {
        val slot1Bytes = ByteArray(2 * 1024 * 1024) { ((it * 5 + 1) and 0xff).toByte() }
        val slot2Bytes = ByteArray(2 * 1024 * 1024) { ((it * 7 + 3) and 0xff).toByte() }
        val media = LiveMockMedia(
            slotData = arrayOf(slot1Bytes, slot2Bytes),
            slotInserted = booleanArrayOf(true, true),
            slotReadOnly = booleanArrayOf(false, false),
        )
        // v9Input only provides 2 entries in bit1Samples per clock channel.
        // Under SdTransactionHost (v6..v9), clock-0-1 (0xFFFC622C) and clock-1-1 (0xFFFCA22C)
        // must implement authentic SDHCI CLOCK_CONTROL (bit 1 tracks bit 0) without finite sample exhaustion.
        val doc = v9Input(slot0Cap = slot1Bytes.size.toLong(), slot1Cap = slot2Bytes.size.toLong())
        val ctx = EventContext(doc)
        ctx.entries["sdcard-reader"] = media
        ctx.entries["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) {} }

        val host = assertIs<StatefulComponent>(
            SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "sd-card-v9.json")),
        )
        host.start()
        try {
            val clock0 = checkNotNull(ctx.ports.find(SdClockHost.port(1), MmioDevice::class.java)) // 0xFFFC622C (0:1)
            val clock1 = checkNotNull(ctx.ports.find(SdClockHost.port(3), MmioDevice::class.java)) // 0xFFFCA22C (1:1)

            // Repeated hot-unplug -> hot-reinsert cycles far exceeding the 2-entry bit1Samples array
            for (cycle in 1..20) {
                for ((slotIdx, clk) in listOf(1 to clock0, 2 to clock1)) {
                    ctx.tick++
                    media.ejectCard(slotIdx)
                    ctx.events.publish(ComponentEvent("sdcard-reader", "hotplug", ctx.tick, mapOf("slot" to slotIdx, "action" to "eject")))

                    // Disable Internal Clock Enable (bit 0 = 0) -> bit 1 (Internal Clock Stable) must clear
                    clk.write(0L, 2, 0x0000L)
                    assertEquals(0x0000L, clk.read(0L, 2))
                    // Attempting to force RO bit 1 = 1 while bit 0 = 0 must still read bit 1 = 0
                    clk.write(0L, 2, 0x0002L)
                    assertEquals(0x0000L, clk.read(0L, 2))

                    ctx.tick++
                    media.insertCard(slotIdx)
                    ctx.events.publish(ComponentEvent("sdcard-reader", "hotplug", ctx.tick, mapOf("slot" to slotIdx, "action" to "insert")))

                    // Firmware enables Internal Clock Enable (bit 0 = 1) -> bit 1 stabilizes (0x0003)
                    clk.write(0L, 2, 0x0001L)
                    assertEquals(0x0003L, clk.read(0L, 2))
                    // Firmware enables SD Clock Enable (bit 2 = 1) -> 0x0007
                    clk.write(0L, 2, 0x0005L)
                    assertEquals(0x0007L, clk.read(0L, 2))
                    // Preserves divider bits while tracking bit 0 -> bit 1
                    val divWord = ((cycle and 0xff) shl 8).toLong()
                    clk.write(0L, 2, divWord or 0x0005L)
                    assertEquals(divWord or 0x0007L, clk.read(0L, 2))
                }
            }

            // Snapshot save & restore must succeed cleanly after 200+ clock reads (> bit1Samples.size)
            val snap = host.saveSnapshot()
            val ctx2 = EventContext(doc)
            ctx2.entries["sdcard-reader"] = media
            ctx2.entries["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) {} }
            val host2 = assertIs<StatefulComponent>(
                SdHostFactory().create(ctx2, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "sd-card-v9.json")),
            )
            host2.start()
            try {
                host2.restoreSnapshot(snap)
                assertEquals(host.state(), host2.state())
                val restoredClock0 = checkNotNull(ctx2.ports.find(SdClockHost.port(1), MmioDevice::class.java))
                val restoredClock1 = checkNotNull(ctx2.ports.find(SdClockHost.port(3), MmioDevice::class.java))
                assertEquals(clock0.read(0L, 2), restoredClock0.read(0L, 2))
                assertEquals(clock1.read(0L, 2), restoredClock1.read(0L, 2))
            } finally {
                host2.close()
            }
        } finally {
            host.close()
        }
    }
}
