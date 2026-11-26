package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SdTransactionHostTest {
    private fun input(readOnly: Boolean = false): Map<String, Any?> {
        val bytes = ByteArray(15) { (it * 17 + 3).toByte() }
        var crc = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val feedback = (b.toInt() ushr i and 1) xor (crc ushr 6 and 1)
            crc = crc shl 1 and 127
            if (feedback != 0) crc = crc xor 9
        }
        val cid = (bytes + ((crc shl 1) or 1).toByte()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return mapOf("schema" to 1, "profile" to SdHostFactory.CARD_PROFILE,
            "commandTicks" to 2, "blockTicks" to 3, "resetTicks" to 2,
            "legacy" to mapOf("schema" to 1, "profile" to SdHostFactory.PHY_PROFILE,
                "initialRegisters" to (0..1).associate { it.toString() to SdControlHost.fields.associateWith { 0L } },
                "clocks" to (0..3).associate { SdClockHost.key(it) to mapOf("initialWord" to 5, "bit1Samples" to listOf(0, 1)) },
                "initWords" to (0..1).associate { it.toString() to SdInitHost.fields.associateWith { name -> if (name == "host4028") 0x0f00L else 0L } }),
            "cards" to mapOf("0" to mapOf("basis" to "SYNTHETIC", "capacityBytes" to 1024L * 1024,
                "highCapacity" to false, "readOnly" to readOnly, "rca" to 0x1234, "cidHex" to cid,
                "ocrVoltageWindow" to 0xff8000L, "supportsHighSpeed" to true), "1" to null))
    }
    private class Media(private val isReadOnly: Boolean = false) : CheckedBlockStoragePort {
        val bytes = ByteArray(1024 * 1024) { (it * 13 + 7).toByte() }
        var failWrite = false
        var reads = 0; var writes = 0
        override fun size(slot: Int) = if (slot == 1) bytes.size.toLong() else null
        override fun readOnly(slot: Int): Boolean { require(slot == 1); return isReadOnly }
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            require(slot == 1 && offset >= 0 && length >= 0 && offset <= bytes.size - length)
            require(!write || !isReadOnly) { "SD slot write-protected" }
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val n = target.remaining(); checkAccess(slot, offset, n.toLong(), false)
            target.put(bytes, offset.toInt(), n); reads++; return n
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val n = source.remaining(); checkAccess(slot, offset, n.toLong(), true)
            if (failWrite) { bytes[offset.toInt()] = source.get(); error("injected partial media failure") }
            source.get(bytes, offset.toInt(), n); writes++; return n
        }
    }
    private class Memory : DmaMemory {
        val bytes = ByteArray(65536)
        var reject = false
        override fun check(address: Long, size: Long, write: Boolean) { require(!reject && address >= 0 && size >= 0 && address <= bytes.size - size) }
        override fun read(address: Long, destination: ByteBuffer) { val n = destination.remaining(); check(address, n.toLong(), false); destination.put(bytes, address.toInt(), n) }
        override fun write(address: Long, source: ByteBuffer) { val n = source.remaining(); check(address, n.toLong(), true); source.get(bytes, address.toInt(), n) }
    }
    private class Context(val data: Map<String, Any?>) : ComponentContext {
        var tick = 0L
        val entries = mutableMapOf<String, Any>()
        override val directory = Path.of("unused-sd-transactions")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) { check(entries.putIfAbsent(name, port) == null) }
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
    private fun addressInput(high0: Long = 0, high1: Long = 0) = input() + mapOf(
        "profile" to SdHostFactory.ADDRESS_PROFILE, "addressHighBits" to mapOf("0" to high0, "1" to high1))
    private class AddressMemory : DmaMemory {
        val low = ByteArray(65536) { 0x55 }
        val high = ByteArray(65536) { 0x66 }
        var allowHigh = true
        val accesses = mutableListOf<Pair<Boolean, Long>>()
        private fun bytes(address: Long): ByteArray = when {
            address >= 0x100000000L -> { require(allowHigh); high }
            else -> low
        }
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0 && size >= 0 && address < 0x200000000L)
            val bytes = bytes(address); val offset = address and 0xffffffffL
            require(offset <= bytes.size - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining(); check(address, n.toLong(), false)
            destination.put(bytes(address), (address and 0xffffffffL).toInt(), n); accesses.add(false to address)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining(); check(address, n.toLong(), true)
            source.get(bytes(address), (address and 0xffffffffL).toInt(), n); accesses.add(true to address)
        }
    }
    private inner class Fixture(data: Map<String, Any?> = input(), isReadOnly: Boolean = false) {
        val context = Context(data); val media = Media(isReadOnly); val memory = Memory()
        val lines = mutableMapOf<Int, Boolean>()
        val host: HardwareComponent
        val bank: MmioDevice
        init {
            context.entries["sdcard-reader"] = media
            context.entries["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { lines[irq] = high } }
            host = SdHostFactory().create(context, mapOf("profile" to data.getValue("profile")!!, "inputFile" to "input.yaml"))
            host.start()
            assertEquals(host.state()["initialSlots"], host.state()["slots"])
            context.entries["cpu.memory"] = memory // CPU port appears after component start.
            bank = context.entries[SdTransactionHost.portForProfile(0, data["profile"] as String)] as MmioDevice
            step(2)
            bank.write(0x34, 2, 0xfb); bank.write(0x38, 2, 0xfb)
        }
        fun step(ticks: Long) { context.tick += ticks; bank.beforeInstruction(context.tick) }
        fun arg(value: Long) { bank.write(8, 2, value and 65535); bank.write(10, 2, value ushr 16) }
        fun issue(index: Int, argument: Long = 0, response: Int = 2, mode: Int = 0, data: Boolean = false) {
            arg(argument); bank.write(12, 4, (((index shl 8) or response or (if (data) 0x20 else 0)).toLong() shl 16) or mode.toLong())
        }
        fun cmd(index: Int, argument: Long = 0, response: Int = 2) { issue(index, argument, response); step(2); if (response == 3) step(3) }
        fun init() {
            cmd(0, response = 0); cmd(8, 0x1aa); cmd(55); cmd(41, 0xff8000)
            cmd(2, response = 1); cmd(3); cmd(9, 0x12340000, 1); cmd(7, 0x12340000, 3)
            bank.write(0x30, 2, 0xfb)
        }
        fun setup(size: Int, blocks: Int = 1, address: Long = 0) { bank.write(4, 2, size.toLong()); bank.write(6, 2, blocks.toLong()); bank.write(0, 4, address) }
        fun slot() = (host.state()["slots"] as List<*>)[0] as Map<*, *>
        fun high(slot: Int = 0) = context.entries[SdTransactionHost.addressHighPort(slot)] as MmioDevice
    }
    @Test fun addressExtensionUsesFullMemoryAddressForReadsAndWritesWithoutLowAlias() {
        val f = Fixture(addressInput(1)); val memory = AddressMemory()
        f.context.entries["cpu.memory"] = memory
        assertEquals(22, f.host.additionalMmioPorts().size)
        assertFalse(f.context.entries.containsKey(SdTransactionHost.port(0)))
        assertEquals(mapOf("0" to 1L, "1" to 0L), f.host.state()["initialAddressHighBits"])
        f.init(); f.setup(512, address = 0x200); f.issue(17, data = true, mode = 0x11); f.step(5)
        assertContentEquals(f.media.bytes.copyOfRange(0, 512), memory.high.copyOfRange(0x200, 0x400))
        assertTrue(memory.low.all { it == 0x55.toByte() })
        assertEquals(0x400L, f.bank.read(0, 4)); assertEquals(0x100000400L, f.slot()["effectiveDmaAddress"])
        val payload = ByteArray(512) { (it xor 0x79).toByte() }; payload.copyInto(memory.high, 0x800)
        f.setup(512, address = 0x800); f.issue(24, 512, data = true, mode = 1); f.step(8)
        assertContentEquals(payload, f.media.bytes.copyOfRange(512, 1024))
        assertEquals(listOf(true to 0x100000200L, false to 0x100000800L), memory.accesses)
        val old = Fixture()
        assertEquals(20, old.host.additionalMmioPorts().size)
        assertFalse(old.context.entries.containsKey(SdTransactionHost.addressHighPort(0)))
        assertFalse(old.host.state().containsKey("addressHighBits")); assertFalse(old.slot().containsKey("addressHighBit"))
    }

    @Test fun addressExtensionRequiresExplicitIntegralBitsAndExactWriteOnlyPorts() {
        val good = addressInput()
        val bad = listOf(good - "addressHighBits", good + ("addressHighBits" to mapOf("0" to 0)),
            good + ("addressHighBits" to mapOf("0" to 0, "1" to 0, "2" to 0)),
            good + ("addressHighBits" to mapOf("0" to 0.0, "1" to 0)),
            good + ("addressHighBits" to mapOf("0" to true, "1" to 0)), addressInput(-1), addressInput(2),
            input() + ("addressHighBits" to mapOf("0" to 0, "1" to 0)))
        for (data in bad) {
            val context = Context(data)
            assertFails { SdHostFactory().create(context, mapOf("profile" to data.getValue("profile")!!, "inputFile" to "input.yaml")) }
            assertTrue(context.entries.isEmpty(), "Malformed input must not publish ports")
        }
        val f = Fixture(good); val before = f.host.state(); val lines = f.lines.toMap(); val tick = f.context.tick
        for ((offset, width) in listOf(0L to 1, 0L to 2, 0L to 8, 1L to 4, 4L to 4, 0x100000000L to 4)) {
            assertFails { f.high().write(offset, width, 0) }; assertFails { f.high().read(offset, width) }
            assertEquals(before, f.host.state())
        }
        assertFails { f.high().read(0, 4) }
        for (value in listOf(-1L, 2L, 0xffffffffL, 0x100000000L)) {
            assertFails { f.high().write(0, 4, value) }; assertEquals(before, f.host.state())
        }
        f.high().write(0, 4, 1); f.high(1).write(0, 4, 1)
        assertEquals(mapOf("0" to 1L, "1" to 1L), f.host.state()["addressHighBits"])
        assertEquals(tick, f.context.tick); assertEquals(lines, f.lines)
        assertEquals(0L, f.host.state()["commandCount"]); assertEquals(0L, f.host.state()["transferredBytes"])
        f.bank.write(0, 4, 0x1234); f.bank.write(0x2f, 1, 1); f.step(2)
        assertEquals(1L, f.slot()["addressHighBit"]); assertEquals(0L, f.bank.read(0, 4))
        assertEquals(0x100000000L, f.slot()["effectiveDmaAddress"])
        f.host.close(); val closed = f.host.state()
        assertFails { f.high().write(0, 4, 0) }; assertEquals(closed, f.host.state())
    }

    @Test fun addressExtensionRejectsActiveChangesUnmappedHighMemoryAndLowWordWrapAtomically() {
        val f = Fixture(addressInput()); f.init(); f.setup(512)
        f.issue(17, data = true, mode = 0x11)
        val active = f.host.state(); val lines = f.lines.toMap()
        assertFails { f.high().write(0, 4, 1) }; assertEquals(active, f.host.state()); assertEquals(lines, f.lines)
        f.step(5); f.high().write(0, 4, 1); f.setup(512)
        val unmapped = f.host.state(); val mediaReads = f.media.reads
        assertFails { f.issue(17, data = true, mode = 0x11) }
        assertEquals(unmapped, f.host.state()); assertEquals(mediaReads, f.media.reads)
        f.context.entries["cpu.memory"] = AddressMemory()
        f.setup(512, address = 0xffffff00L); val wrapping = f.host.state()
        assertEquals("SDMA address wraps", assertFails { f.issue(17, data = true, mode = 0x11) }.message)
        assertEquals(wrapping, f.host.state()); assertEquals(mediaReads, f.media.reads)
    }

    @Test fun pausedAddressExtensionDoesNotResumeUntilCheckedLowWordWrite() {
        val f = Fixture(addressInput()); val memory = AddressMemory(); f.context.entries["cpu.memory"] = memory
        f.init(); f.setup(512, address = 4096 - 128L); f.issue(17, data = true, mode = 0x11); f.step(5)
        assertEquals(true, f.slot()["dmaPaused"]); assertEquals(128L, f.host.state()["transferredBytes"])
        val lines = f.lines.toMap(); val accesses = memory.accesses.toList()
        f.high().write(0, 4, 1); f.step(50)
        assertEquals(true, f.slot()["dmaPaused"]); assertNull(f.slot()["deadline"])
        assertEquals(accesses, memory.accesses); assertEquals(lines, f.lines)
        memory.allowHigh = false; val before = f.host.state()
        assertFails { f.bank.write(0, 4, 0x200) }; assertEquals(before, f.host.state())
        memory.allowHigh = true; f.bank.write(0, 4, 0x200); f.step(3)
        assertContentEquals(f.media.bytes.copyOfRange(0, 128), memory.low.copyOfRange(3968, 4096))
        assertContentEquals(f.media.bytes.copyOfRange(128, 512), memory.high.copyOfRange(0x200, 0x380))
        assertEquals(listOf(true to 3968L, true to 0x100000200L), memory.accesses)
        assertEquals(1L, f.host.state()["transferCount"]); assertEquals(0x380L, f.bank.read(0, 4))
    }

    @Test fun addressExtensionDoesNotTurnPioIntoDma() {
        val f = Fixture(addressInput(1)); f.init(); f.setup(512); f.issue(17, data = true, mode = 0x10); f.step(5)
        val before = f.host.state(); assertFails { f.high().write(0, 4, 0) }; assertEquals(before, f.host.state())
        val bytes = ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(128) { bytes.putInt(f.bank.read(0x20, 4).toInt()) }
        assertContentEquals(f.media.bytes.copyOfRange(0, 512), bytes.array())
        assertEquals(1L, f.host.state()["transferCount"]); assertEquals(1L, f.slot()["addressHighBit"])
        assertTrue(f.memory.bytes.all { it == 0.toByte() })
    }
    @Test fun timeoutAndResetHalfwordPreflightsAtomicallyAndPreservesIndependentFields() {
        val f = Fixture()
        assertEquals(0L, f.bank.read(0x2e, 2))
        for (value in 0L..14L) {
            f.bank.write(0x2e, 2, value)
            assertEquals(value, f.bank.read(0x2e, 2))
            assertEquals(0L, f.bank.read(0x2f, 1))
        }
        f.init(); f.setup(512); f.issue(17, data = true, mode = 0x10); f.step(5)
        assertEquals(true, f.slot()["bufferReady"])
        val active = f.host.state()
        for (value in listOf(0x0fL, 0x10L, 0x40fL, 0x800L, 0x10000L)) {
            assertFails { f.bank.write(0x2e, 2, value) }
            assertEquals(active, f.host.state(), "Invalid composite write must not cancel pending data")
        }
        for ((offset, width) in listOf(0x2eL to 1, 0x2fL to 2, 0x2eL to 4)) {
            assertFails { f.bank.read(offset, width) }; assertFails { f.bank.write(offset, width, 0) }
            assertEquals(active, f.host.state())
        }
        f.bank.write(0x2e, 2, 0x40e) // Data reset and retained timeout update are one accepted write.
        assertEquals(0x40eL, f.bank.read(0x2e, 2)); assertEquals(4L, f.bank.read(0x2f, 1))
        assertEquals(false, f.slot()["dataPending"])
        assertEquals("SENDING", (f.slot()["card"] as Map<*, *>)["state"])
        val reset = f.host.state()
        assertFails { f.bank.write(0x2e, 2, 0x206) }
        assertEquals(reset, f.host.state(), "Overlapping reset must not partly update timeout")
        val deadline = f.slot()["resetDeadline"]
        f.bank.write(0x2e, 2, 7) // High byte zero does not clear/restart the in-flight reset.
        assertEquals(0x407L, f.bank.read(0x2e, 2)); assertEquals(deadline, f.slot()["resetDeadline"])
        f.step(2); assertEquals(7L, f.bank.read(0x2e, 2))
        f.bank.write(0x2f, 1, 2); f.step(2); assertEquals(7L, f.bank.read(0x2e, 2))
        assertEquals(0L, f.host.state()["transferCount"])
        f.bank.write(0x2e, 2, 0x10e); assertEquals(0x10eL, f.bank.read(0x2e, 2))
        f.step(2); assertEquals(0L, f.bank.read(0x2e, 2))
        val overflow = Fixture(); overflow.context.tick = Long.MAX_VALUE
        val before = overflow.host.state()
        assertFails { overflow.bank.write(0x2e, 2, 0x205) }
        assertEquals(before, overflow.host.state(), "Deadline overflow must precede timeout mutation")
    }

    @Test fun exactWindowsClockProjectionAndInterruptMasks() {
        val f = Fixture(); assertEquals(20, f.host.additionalMmioPorts().size)
        assertEquals(7L, f.bank.read(0x2c, 2))
        val legacy = f.host.state()["legacyState"]
        for ((offset, size) in listOf(0x08L to 4, 0x3cL to 1, 0x3eL to 4, 0x22L to 2)) assertFails { f.bank.read(offset, size) }
        assertFails { f.bank.write(0x3c, 4, 0) }
        assertEquals(legacy, f.host.state()["legacyState"])
        f.bank.write(0x34, 2, 0); f.cmd(0, response = 0); assertEquals(0L, f.bank.read(0x30, 4))
        f.bank.write(0x34, 2, 1); f.bank.write(0x38, 2, 0); f.cmd(8, 0x1aa)
        assertEquals(1L, f.bank.read(0x30, 2)); assertEquals(false, f.lines[230])
        f.bank.write(0x38, 2, 1); assertEquals(true, f.lines[230])
        f.bank.write(0x30, 2, 1); assertEquals(false, f.lines[230])
        assertEquals(0x1aaL, f.bank.read(0x10, 2))
    }
    @Test fun observedAcmd41RequestUsesExistingCommandDelayResponseAndIrqWithoutData() {
        val f = Fixture(); f.cmd(0, response = 0); f.cmd(8, 0x1aa); f.cmd(55)
        f.cmd(41, 0x51000000)
        assertEquals("IDLE", (f.slot()["card"] as Map<*, *>)["state"])
        assertEquals(0xff8000L, f.bank.read(0x10, 2) or (f.bank.read(0x12, 2) shl 16))
        f.cmd(55); f.bank.write(0x30, 2, 0xfb)
        f.arg(0x59ff8000); val beforeRejected = f.host.state()
        assertFailsWith<IllegalArgumentException> { f.bank.write(12, 4, 0x29020000) }
        assertEquals(beforeRejected, f.host.state(), "Unsupported HO2T cannot consume APP_CMD or host counters")
        f.arg(0x51ff8000); f.bank.write(12, 4, 0x29020000)
        assertEquals(6L, f.host.state()["commandCount"]); assertEquals(5L, f.host.state()["completedCommands"])
        assertEquals(true, f.slot()["commandPending"])
        assertEquals(0x20L, f.bank.read(0x10, 2)); assertEquals(0L, f.bank.read(0x30, 2)); assertEquals(false, f.lines[230])
        f.step(1)
        assertEquals(5L, f.host.state()["completedCommands"]); assertEquals(false, f.lines[230])
        f.step(1)
        assertEquals(6L, f.host.state()["completedCommands"]); assertEquals(false, f.slot()["commandPending"])
        assertEquals(0x80ff8000L, f.bank.read(0x10, 2) or (f.bank.read(0x12, 2) shl 16))
        assertEquals(1L, f.bank.read(0x30, 2)); assertEquals(true, f.lines[230])
        assertEquals("READY", (f.slot()["card"] as Map<*, *>)["state"])
        assertEquals(0L, f.host.state()["transferCount"]); assertEquals(0L, f.host.state()["transferredBytes"])
        assertEquals(0, f.media.reads); assertEquals(0, f.media.writes)
        f.arg(0); val beforeSwitch = f.host.state()
        assertFailsWith<IllegalStateException> { f.bank.write(12, 4, 0x0b020000) }
        assertEquals(beforeSwitch, f.host.state(), "Accepted S18R request must not enable CMD11")
        f.bank.write(0x30, 2, 1); assertEquals(false, f.lines[230])
    }
    @Test fun shortAcmdDmaAndMultiPioUseActualBytesAndStopResponse() {
        val f = Fixture(); f.init()
        f.cmd(55, 0x12340000); f.setup(8, address = 100)
        f.issue(51, mode = 0x11, data = true); f.step(2)
        assertEquals(1L, f.bank.read(0x30, 2)); f.step(3)
        assertContentEquals(byteArrayOf(2, 5, 0, 0, 0, 0, 0, 0), f.memory.bytes.copyOfRange(100, 108))
        assertEquals(0, f.media.reads); assertEquals(3L, f.bank.read(0x30, 2))
        f.bank.write(0x30, 2, 0xfb); f.setup(512, 2)
        f.issue(18, 512, mode = 0x36, data = true); f.step(5)
        val result = ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(2) { block -> repeat(128) { result.putInt(f.bank.read(0x20, 4).toInt()) }; if (block == 0) f.step(3) }
        assertEquals(0L, f.bank.read(0x30, 2) and 2); f.step(3)
        assertContentEquals(f.media.bytes.copyOfRange(512, 1536), result.array())
        assertEquals(5L, (f.bank.read(0x1c, 2) ushr 9) and 15)
        assertEquals(0L, f.bank.read(6, 2)); assertEquals(2L, f.bank.read(0x30, 2) and 2)
        f.cmd(13, 0x12340000); assertEquals(4L, (f.bank.read(0x10, 2) ushr 9) and 15)
    }
    @Test fun sdmaBoundaryPausesAndResumesWithoutSpuriousFinalDmaInterrupt() {
        val f = Fixture(); f.init(); f.setup(512, 2, 3840)
        f.issue(18, mode = 0x37, data = true); f.step(5)
        assertEquals(4096L, f.bank.read(0, 4)); assertEquals(true, f.slot()["dmaPaused"])
        assertEquals(8L, f.bank.read(0x30, 2) and 8); assertEquals(0L, f.bank.read(0x30, 2) and 2)
        val before = f.host.state(); assertFails { f.bank.write(0, 4, 65500) }; assertEquals(before, f.host.state())
        f.bank.write(0x30, 2, 8); f.bank.write(0, 4, 4096); f.step(9)
        assertContentEquals(f.media.bytes.copyOfRange(0, 1024), f.memory.bytes.copyOfRange(3840, 4864))
        assertEquals(0L, f.bank.read(0x30, 2) and 8); assertEquals(2L, f.bank.read(0x30, 2) and 2)
    }
    @Test fun pioAndDmaWritesCommitMediaBeforeCompletion() {
        val f = Fixture(); f.init(); f.setup(512, 2)
        f.issue(25, mode = 0x26, data = true); f.step(5)
        repeat(2) { block -> repeat(128) { f.bank.write(0x20, 4, 0x12345678L + block) }; if (block == 0) f.step(3) }
        assertEquals(2, f.media.writes); assertEquals(0L, f.bank.read(0x30, 2) and 2); f.step(3)
        assertContentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), f.media.bytes.copyOfRange(0, 4))
        f.memory.bytes.fill(0x6b, 8192, 9216); f.setup(512, 2, 8192)
        f.issue(25, 2048, mode = 0x27, data = true); f.step(11)
        assertContentEquals(ByteArray(1024) { 0x6b }, f.media.bytes.copyOfRange(2048, 3072))
        assertEquals(4, f.media.writes)
    }
    @Test fun formatMetadataUsesOneCountedSdmaBlockWithAutoStop() {
        val fixture = Fixture()
        fixture.init()
        val sector = ByteArray(512)
        sector[510] = 0x55
        sector[511] = 0xaa.toByte()
        val expected = fixture.media.bytes.copyOf()
        sector.copyInto(expected)
        sector.copyInto(fixture.memory.bytes, 0x1000)
        fixture.setup(512, 1, 0x1000)
        fixture.arg(0)
        fixture.bank.write(12, 4, 0x193a0027L)
        fixture.step(8)
        assertContentEquals(expected, fixture.media.bytes)
        assertEquals(1, fixture.media.writes)
        assertEquals(0L, fixture.bank.read(6, 2))
        assertEquals(2L, fixture.bank.read(0x30, 2) and 2)
        assertEquals("TRANSFER", (fixture.slot()["card"] as Map<*, *>)["state"])
    }

    @Test fun eraseCommandsRefuseBeforeCommandAcceptanceOrMediaAccess() {
        val fixture = Fixture()
        fixture.init()
        val original = fixture.media.bytes.copyOf()
        for ((index, argument) in listOf(32 to 512L, 33 to 1024L, 38 to 0L)) {
            fixture.arg(argument)
            val before = fixture.host.state()
            val command = (index.toLong() shl 24) or (if (index == 38) 0x001b0000L else 0x001a0000L)
            val failure = assertFailsWith<IllegalStateException> { fixture.bank.write(12, 4, command) }
            assertEquals("unsupported CMD$index", failure.message)
            assertEquals(before, fixture.host.state())
        }
        assertContentEquals(original, fixture.media.bytes)
        assertEquals(0, fixture.media.reads)
        assertEquals(0, fixture.media.writes)
    }

    @Test fun formatZeroClearSupportsPioMultiBlockCmd25WithAutoStop() {
        val f = Fixture()
        f.init()
        val before = f.media.bytes.copyOf()
        val startOffset = 4096
        f.setup(512, 3)
        f.arg(startOffset.toLong())
        f.bank.write(12, 4, 0x193a0026L)
        f.step(5)
        repeat(3) { block ->
            assertEquals(0x10L, f.bank.read(0x30, 2) and 0x10L)
            assertEquals(0L, f.bank.read(0x30, 2) and 2L)
            f.bank.write(0x30, 2, 0x10)
            repeat(128) { f.bank.write(0x20, 4, 0L) }
            if (block < 2) f.step(3)
        }
        assertEquals(0L, f.bank.read(6, 2))
        assertEquals(3, f.media.writes)
        assertEquals(0L, f.bank.read(0x30, 2) and 2L)
        f.step(3)
        assertEquals(2L, f.bank.read(0x30, 2) and 2L)
        assertEquals("TRANSFER", (f.slot()["card"] as Map<*, *>)["state"])
        assertEquals(3L * 512L, f.host.state()["mediaBytes"])
        assertContentEquals(ByteArray(3 * 512), f.media.bytes.copyOfRange(startOffset, startOffset + 3 * 512))
        assertContentEquals(before.copyOfRange(0, startOffset), f.media.bytes.copyOfRange(0, startOffset))
        assertContentEquals(
            before.copyOfRange(startOffset + 3 * 512, before.size),
            f.media.bytes.copyOfRange(startOffset + 3 * 512, f.media.bytes.size),
        )
    }

    @Test fun formatSdmaMultiBlockBoundaryPauseAndSidebandResume() {
        val f = Fixture(addressInput())
        val memory = AddressMemory()
        f.context.entries["cpu.memory"] = memory
        f.init()
        val pattern = ByteArray(1024) { ((it * 29 + 11) and 255).toByte() }
        pattern.copyInto(memory.low, 4096 - 128, 0, 128)
        pattern.copyInto(memory.high, 0x200, 128, 1024)
        val before = f.media.bytes.copyOf()

        f.setup(512, 2, 4096 - 128L)
        f.arg(1024L)
        f.bank.write(12, 4, 0x193a0027L)
        f.step(5)
        assertEquals(true, f.slot()["dmaPaused"])
        assertEquals(4096L, f.bank.read(0, 4))
        assertEquals(8L, f.bank.read(0x30, 2) and 8L)
        assertEquals(0L, f.bank.read(0x30, 2) and 2L)
        assertEquals(128L, f.host.state()["transferredBytes"])
        assertEquals(0, f.media.writes)

        f.high().write(0, 4, 1)
        f.step(10)
        assertEquals(true, f.slot()["dmaPaused"])
        assertEquals(0, f.media.writes)

        f.bank.write(0x30, 2, 8)
        f.bank.write(0, 4, 0x200)
        f.step(9)
        assertEquals(false, f.slot()["dmaPaused"])
        assertEquals(0L, f.bank.read(0x30, 2) and 8L)
        assertEquals(2L, f.bank.read(0x30, 2) and 2L)
        assertEquals(0L, f.bank.read(6, 2))
        assertEquals(2, f.media.writes)
        assertEquals(0x100000580L, f.slot()["effectiveDmaAddress"])
        assertEquals("TRANSFER", (f.slot()["card"] as Map<*, *>)["state"])
        assertContentEquals(pattern, f.media.bytes.copyOfRange(1024, 2048))
        assertContentEquals(before.copyOfRange(0, 1024), f.media.bytes.copyOfRange(0, 1024))
        assertContentEquals(before.copyOfRange(2048, before.size), f.media.bytes.copyOfRange(2048, f.media.bytes.size))
    }

    @Test fun invalidCommandGeometryDmaAndAbsentSlotRefuseBeforeMutation() {
        val f = Fixture(); f.init(); f.setup(512, 2)
        f.arg(0); val before = f.host.state()
        for (word in listOf(0x123a0037L, 0x12220032L, 0x14220010L, 0x1222003fL)) {
            f.memory.reject = true; assertFails { f.bank.write(12, 4, word) }; assertEquals(before, f.host.state())
        }
        f.memory.reject = false
        assertFails { (f.context.entries[SdTransactionHost.port(1)] as MmioDevice).write(12, 4, 0) }
        assertEquals(before, f.host.state()); assertEquals(0, f.media.reads)
    }
    @Test fun realPartialWriteFailureNeverCompletesOrClaimsRollbackAndResetKeepsCardState() {
        val f = Fixture(); f.init(); f.setup(512); f.media.failWrite = true
        f.issue(24, mode = 0, data = true); f.step(5)
        repeat(127) { f.bank.write(0x20, 4, 0x77777777) }
        assertFails { f.bank.write(0x20, 4, 0x77777777) }
        assertEquals(0x77.toByte(), f.media.bytes[0]); assertEquals(1L, f.host.state()["failedTransactions"])
        assertEquals(0L, f.bank.read(0x30, 2) and 2); assertNotNull(f.slot()["fault"])
        f.bank.write(0x2f, 1, 1); assertEquals(1L, f.bank.read(0x2f, 1)); f.step(2)
        assertEquals(0L, f.bank.read(0x2f, 1)); assertEquals("RECEIVE", (f.slot()["card"] as Map<*, *>)["state"])
        assertEquals(0L, f.bank.read(0x2c, 2)); f.bank.write(0x28, 2, 0xf00); f.bank.write(0x2c, 2, 5); f.step(2)
        f.cmd(12, response = 3); f.cmd(13, 0x12340000)
        assertEquals(4L, (f.bank.read(0x10, 2) ushr 9) and 15)
    }
    @Test fun command12AbortsRemainingDataAfterCommittedBlockAndCommandResetPreservesData() {
        val f = Fixture(); f.init(); f.setup(512, 2)
        val untouched = f.media.bytes.copyOfRange(512, 1024)
        f.issue(25, mode = 0x26, data = true); f.step(5)
        repeat(128) { f.bank.write(0x20, 4, 0x33333333) }
        f.bank.write(0x2f, 1, 2); f.step(2)
        assertEquals(true, f.slot()["dataPending"])
        f.arg(0)
        f.bank.write(12, 4, 0x0cc30000) // CMD12, Abort type, R1b; supersedes remaining data.
        f.step(2); assertEquals(6L, f.bank.read(0x10, 2) ushr 9 and 15)
        assertEquals(0L, f.bank.read(0x30, 2) and 2); f.step(3)
        assertEquals(2L, f.bank.read(0x30, 2) and 2)
        assertEquals(1, f.media.writes); assertContentEquals(untouched, f.media.bytes.copyOfRange(512, 1024))
        assertEquals(512L, f.host.state()["transferredBytes"]); assertEquals(0L, f.host.state()["transferCount"])
        assertEquals(false, f.slot()["dataPending"]); f.cmd(13, 0x12340000)
        assertEquals(4L, f.bank.read(0x10, 2) ushr 9 and 15)
    }
    @Test fun finalPhysicalAddressWrapsRegisterWithoutFinalDmaInterrupt() {
        val f = Fixture(); f.init()
        val delivered = ByteArray(512)
        f.context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == 0xfffffe00L && size == 512L)
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unexpected read")
            override fun write(address: Long, source: ByteBuffer) {
                check(address, source.remaining().toLong(), true); source.get(delivered)
            }
        }
        f.setup(512, address = 0xfffffe00L); f.issue(17, mode = 0x11, data = true); f.step(5)
        assertContentEquals(f.media.bytes.copyOfRange(0, 512), delivered)
        assertEquals(0L, f.bank.read(0, 4)); assertEquals(2L, f.bank.read(0x30, 2) and 2)
        assertEquals(0L, f.bank.read(0x30, 2) and 8); assertEquals(false, f.slot()["dmaPaused"])
    }

    @Test fun presentStateBit19UsesActiveHighWriteProtectPolarityAndSupportsSysFormatFlows() {
        val rw = Fixture(input(readOnly = false), isReadOnly = false)
        assertEquals(0L, rw.bank.read(0x24, 4) and 0x80000L, "Writable card must clear PRESENT_STATE bit 19")
        assertEquals(0x70000L, rw.bank.read(0x24, 4) and 0x70000L, "Inserted/stable/detect bits must be set")
        rw.init()

        // Multi-block SDMA write (CMD25 mode=0x27) + CMD13 + CMD18 readback (mode=0x37)
        val multiPattern = ByteArray(1024) { ((it * 19 + 7) and 255).toByte() }
        multiPattern.copyInto(rw.memory.bytes, 0x2000)
        rw.setup(512, 2, 0x2000)
        rw.arg(2048L)
        rw.bank.write(12, 4, 0x193a0027L)
        rw.step(11)
        assertEquals(0L, rw.bank.read(6, 2))
        assertEquals(2L, rw.bank.read(0x30, 2) and 2L)
        assertContentEquals(multiPattern, rw.media.bytes.copyOfRange(2048, 3072))
        rw.cmd(13, 0x12340000)
        assertEquals(4L, (rw.bank.read(0x10, 2) ushr 9) and 15)

        rw.bank.write(0x30, 2, 0xfb)
        rw.setup(512, 2, 0x4000)
        rw.arg(2048L)
        rw.bank.write(12, 4, 0x123a0037L)
        rw.step(11)
        assertEquals(2L, rw.bank.read(0x30, 2) and 2L)
        assertContentEquals(multiPattern, rw.memory.bytes.copyOfRange(0x4000, 0x4400))

        // 1-block metadata write (CMD25 mode=0x27, blockCount=1) + PIO write (CMD25 mode=0x26) + PIO readback (CMD18 mode=0x36)
        rw.bank.write(0x30, 2, 0xfb)
        val singlePattern = ByteArray(512) { ((it * 7 + 3) and 255).toByte() }
        singlePattern.copyInto(rw.memory.bytes, 0x3000)
        rw.setup(512, 1, 0x3000)
        rw.arg(4096L)
        rw.bank.write(12, 4, 0x193a0027L)
        rw.step(8)
        assertEquals(0L, rw.bank.read(6, 2))
        assertContentEquals(singlePattern, rw.media.bytes.copyOfRange(4096, 4608))

        rw.bank.write(0x30, 2, 0xfb)
        rw.setup(512, 2, 0)
        rw.arg(5120L)
        rw.bank.write(12, 4, 0x193a0026L)
        rw.step(5)
        repeat(2) { block ->
            assertEquals(0x10L, rw.bank.read(0x30, 2) and 0x10L)
            rw.bank.write(0x30, 2, 0x10)
            repeat(128) { w -> rw.bank.write(0x20, 4, 0x01020304L + block * 128 + w) }
            if (block == 0) rw.step(3)
        }
        rw.step(3)
        assertEquals(2L, rw.bank.read(0x30, 2) and 2L)
        rw.cmd(13, 0x12340000)
        assertEquals(4L, (rw.bank.read(0x10, 2) ushr 9) and 15)

        rw.bank.write(0x30, 2, 0xfb)
        rw.setup(512, 2, 0)
        rw.arg(5120L)
        rw.bank.write(12, 4, 0x123a0036L)
        rw.step(5)
        val pioReadWords = LongArray(256)
        repeat(2) { block ->
            assertEquals(0x20L, rw.bank.read(0x30, 2) and 0x20L)
            rw.bank.write(0x30, 2, 0x20)
            repeat(128) { w -> pioReadWords[block * 128 + w] = rw.bank.read(0x20, 4) }
            if (block == 0) rw.step(3)
        }
        rw.step(3)
        assertEquals(2L, rw.bank.read(0x30, 2) and 2L)
        repeat(256) { idx -> assertEquals((0x01020304L + idx) and 0xffffffffL, pioReadWords[idx]) }
    }

    @Test fun writeProtectedCardSetsPresentBit19AndRefusesCmd24Cmd25WithoutMediaMutation() {
        val ro = Fixture(input(readOnly = true), isReadOnly = true)
        assertEquals(0x80000L, ro.bank.read(0x24, 4) and 0x80000L, "Read-only card must set PRESENT_STATE bit 19")
        ro.init()
        val beforeMedia = ro.media.bytes.copyOf()

        ro.setup(512, 1, 0x1000)
        ro.arg(0L)
        fun assertWriteProtected(command: Long) {
            ro.bank.write(0x30, 2, 0xfb)
            ro.bank.write(12, 4, command)
            ro.step(2)
            assertEquals(1L, ro.bank.read(0x30, 2) and 3, "command completed without a data phase")
            assertEquals(1L shl 10, ro.bank.read(0x12, 2) and (1L shl 10), "R1.WP_VIOLATION")
            assertEquals(0L, ro.bank.read(0x24, 4) and 7, "both command and data lines released")
        }
        assertEquals(ro.bank.read(0x24, 4) ushr 16, ro.bank.read(0x26, 2))
        assertEquals(ro.bank.read(0x3c, 4) and 0xffff, ro.bank.read(0x3c, 2))
        assertWriteProtected(0x183a0001L)
        ro.setup(512, 2, 0x1000)
        ro.arg(1024L)
        assertWriteProtected(0x193a0027L)
        assertWriteProtected(0x193a0026L)

        assertEquals(0, ro.media.writes)
        assertContentEquals(beforeMedia, ro.media.bytes)

        // Reads (CMD18 mode=0x37) and status (CMD13) still succeed on write-protected media
        ro.bank.write(0x30, 2, 0xfb)
        ro.setup(512, 2, 0x2000)
        ro.arg(0L)
        ro.bank.write(12, 4, 0x123a0037L)
        ro.step(11)
        assertEquals(2L, ro.bank.read(0x30, 2) and 2L)
        assertContentEquals(beforeMedia.copyOfRange(0, 1024), ro.memory.bytes.copyOfRange(0x2000, 0x2400))
        ro.cmd(13, 0x12340000)
        assertEquals(4L, (ro.bank.read(0x10, 2) ushr 9) and 15)
    }

    private fun vendorPhyInput(
        slot0Phy6060: Long = 0L,
        slot0Phy607c: Long = 1L,
        slot1Phy6060: Long = 0L,
        slot1Phy607c: Long = 1L,
    ) = addressInput() + mapOf(
        "profile" to SdHostFactory.VENDOR_PHY_PROFILE,
        "vendorPhyWords" to mapOf(
            "0" to mapOf(
                "vendor41e0" to 0L,
                "vendor41e4" to 0L,
                "phy6060" to slot0Phy6060,
                "phy607c" to slot0Phy607c,
                "phy6094" to 0L,
            ),
            "1" to mapOf(
                "vendor41e0" to 0x11223344L,
                "vendor41e4" to 0x55667788L,
                "phy6060" to slot1Phy6060,
                "phy607c" to slot1Phy607c,
                "phy6094" to 0x00000020L,
            ),
        ),
    )

    @Test fun vendorPhyV8Exposes30PortsExecutesDatInitSequencesAndEnforcesReadOnlyAndWidthRules() {
        val good = vendorPhyInput()
        val f = Fixture(good)
        assertEquals(30, f.host.additionalMmioPorts().size)
        assertFalse(f.context.entries.containsKey(SdTransactionHost.port(0, false)))
        assertFalse(f.context.entries.containsKey(SdTransactionHost.port(0, true)))
        assertFalse(f.context.entries.containsKey(SdTransactionHost.addressHighPort(0)))
        assertNotNull(f.context.entries[SdTransactionHost.addressHighPort(0, vendorPhy = true)])

        for (slot in 0..1) {
            val vendorA = f.context.entries[SdTransactionHost.vendorAPort(slot)] as MmioDevice
            val phy6060 = f.context.entries[SdTransactionHost.phy6060Port(slot)] as MmioDevice
            val phy607c = f.context.entries[SdTransactionHost.phy607cPort(slot)] as MmioDevice
            val phy6094 = f.context.entries[SdTransactionHost.phy6094Port(slot)] as MmioDevice

            // Execute exact 0x011F1C54 DAT RMW sequence on vendor41e0 (+0) & vendor41e4 (+4)
            val init41e0 = vendorA.read(0L, 4)
            vendorA.write(0L, 4, init41e0 or 0x10000L)
            assertEquals(init41e0 or 0x10000L, vendorA.read(0L, 4))
            val init41e4 = vendorA.read(4L, 4)
            vendorA.write(4L, 4, init41e4 or 0x00ff0101L)
            assertEquals(init41e4 or 0x00ff0101L, vendorA.read(4L, 4))
            val mid41e0 = (vendorA.read(0L, 4) and (3L shl 9).inv()) or (2L shl 9)
            vendorA.write(0L, 4, mid41e0)
            val end41e0 = vendorA.read(0L, 4) and 0x10000L.inv()
            vendorA.write(0L, 4, end41e0)
            assertEquals(end41e0, vendorA.read(0L, 4))

            // Execute exact 0x011F1CD8 DAT RMW sequence on phy6094, plus polls on phy6060 & phy607c
            val p94 = phy6094.read(0L, 4)
            val cfg94 = (((p94 and 0xff00L.inv()) or 0x4000L) or 0x50L) and 0x21L.inv()
            phy6094.write(0L, 4, cfg94)
            assertEquals(cfg94, phy6094.read(0L, 4))
            phy6094.write(0L, 4, phy6094.read(0L, 4) or 1L)
            assertEquals(cfg94 or 1L, phy6094.read(0L, 4))

            assertEquals(0L, phy6060.read(0L, 4) and 2L)
            assertEquals(1L, phy607c.read(0L, 4) and 1L)

            // Read-only enforcement on phy6060 & phy607c and width/offset/value refusals leave state intact
            val before = f.host.state()
            assertFails { phy6060.write(0L, 4, 0L) }
            assertFails { phy607c.write(0L, 4, 1L) }
            for ((off, w) in listOf(-4L to 4, 1L to 4, 2L to 4, 8L to 4, 0L to 1, 0L to 2, 0L to 8, 4L to 2)) {
                assertFails { vendorA.read(off, w) }
                assertFails { vendorA.write(off, w, 0L) }
            }
            for ((off, w) in listOf(-4L to 4, 1L to 4, 4L to 4, 0L to 1, 0L to 2, 0L to 8)) {
                assertFails { phy6060.read(off, w) }
                assertFails { phy607c.read(off, w) }
                assertFails { phy6094.read(off, w) }
                assertFails { phy6094.write(off, w, 0L) }
            }
            for (badVal in listOf(-1L, 0x100000000L)) {
                assertFails { vendorA.write(0L, 4, badVal) }
                assertFails { phy6094.write(0L, 4, badVal) }
            }
            assertEquals(before, f.host.state())
        }

        // Normal host ResetAll preserves external vendor/PHY latches (explicit HYP)
        val slot0VendorBeforeReset = (f.context.entries[SdTransactionHost.vendorAPort(0)] as MmioDevice).read(0L, 4)
        f.bank.write(0x2f, 1, 1)
        f.step(2)
        assertEquals(slot0VendorBeforeReset, (f.context.entries[SdTransactionHost.vendorAPort(0)] as MmioDevice).read(0L, 4))

        // Closing refuses further accesses atomically
        f.host.close()
        val closed = f.host.state()
        assertFails { (f.context.entries[SdTransactionHost.vendorAPort(0)] as MmioDevice).read(0L, 4) }
        assertFails { (f.context.entries[SdTransactionHost.phy6060Port(0)] as MmioDevice).read(0L, 4) }
        assertFails { (f.context.entries[SdTransactionHost.phy607cPort(0)] as MmioDevice).read(0L, 4) }
        assertFails { (f.context.entries[SdTransactionHost.phy6094Port(0)] as MmioDevice).read(0L, 4) }
        assertEquals(closed, f.host.state())

        // Malformed vendorPhyWords inputs fail before publishing any ports
        val vp = good["vendorPhyWords"] as Map<*, *>
        val row0 = vp["0"] as Map<*, *>
        val badInputs = listOf(
            good - "vendorPhyWords",
            good + ("vendorPhyWords" to mapOf("0" to row0)),
            good + ("vendorPhyWords" to (vp + ("0" to (row0 - "phy607c")))),
            good + ("vendorPhyWords" to (vp + ("0" to (row0 + ("extra" to 0L))))),
            good + ("vendorPhyWords" to (vp + ("0" to (row0 + ("vendor41e0" to -1L))))),
            good + ("vendorPhyWords" to (vp + ("0" to (row0 + ("phy6094" to 0x100000000L))))),
            addressInput() + ("vendorPhyWords" to vp),
        )
        for (bad in badInputs) {
            val ctx = Context(bad)
            assertFails { SdHostFactory().create(ctx, mapOf("profile" to bad.getValue("profile")!!, "inputFile" to "input.yaml")) }
            assertTrue(ctx.entries.isEmpty(), "Malformed v8 input must not publish ports")
        }
    }

    @Test fun uhsPhyV9Exposes50ExclusivePortsEnforcesWidthsAndExecutesDatUhsSequences() {
        val uhsRow0 = SdTransactionHost.uhsPhyFields.associateWith { f ->
            when (f) {
                "uhs410c" -> 0x0022L
                "uhs410e" -> 0x0044L
                else -> 0L
            }
        }
        val uhsRow1 = SdTransactionHost.uhsPhyFields.associateWith { f ->
            when (f) {
                "uhs410c" -> 0x0066L
                "uhs410e" -> 0x0088L
                else -> 0L
            }
        }
        val good = vendorPhyInput() + mapOf(
            "profile" to SdHostFactory.UHS_PHY_PROFILE,
            "uhsPhyWords" to mapOf("0" to uhsRow0, "1" to uhsRow1),
        )
        val f = Fixture(good)
        assertEquals(50, f.host.additionalMmioPorts().size)
        assertEquals(50, f.context.entries.keys.count { it.startsWith("soc-sd-host.") })
        assertNotNull(f.context.entries[SdTransactionHost.portForProfile(0, SdHostFactory.UHS_PHY_PROFILE)])
        assertNotNull(f.context.entries[SdTransactionHost.addressHighPortForProfile(0, SdHostFactory.UHS_PHY_PROFILE)])

        for (slot in 0..1) {
            val u4100 = f.context.entries[SdTransactionHost.uhs4100Port(slot)] as MmioDevice
            val u4108 = f.context.entries[SdTransactionHost.uhs4108Port(slot)] as MmioDevice
            val u4114 = f.context.entries[SdTransactionHost.uhs4114Port(slot)] as MmioDevice
            val u4124 = f.context.entries[SdTransactionHost.uhs4124Port(slot)] as MmioDevice
            val phyTrim = f.context.entries[SdTransactionHost.phyTrimPort(slot)] as MmioDevice
            val u2Dma = f.context.entries[SdTransactionHost.uhs2DmaPort(slot)] as MmioDevice
            val u26280 = f.context.entries[SdTransactionHost.uhs26280Port(slot)] as MmioDevice
            val u2Cmd = f.context.entries[SdTransactionHost.uhs2CmdPort(slot)] as MmioDevice
            val u2629c = f.context.entries[SdTransactionHost.uhs2629cPort(slot)] as MmioDevice
            val u2Err = f.context.entries[SdTransactionHost.uhs2ErrPort(slot)] as MmioDevice

            // Execute DAT UHS-I tuning sequences (0x011F281C, 0x011F26BC, 0x011F27C8, 0x011F2864, 0x011F0A68, 0x011F2534)
            u4100.write(0L, 2, (u4100.read(0L, 2) and 7L.inv()) or 5L)
            assertEquals(5L, u4100.read(0L, 2))
            u4108.write(0L, 2, u4108.read(0L, 2) or 0x200L)
            u4108.write(0L, 2, (u4108.read(0L, 2) or 0x100L) or 0x3aL)
            assertEquals(0x33aL, u4108.read(0L, 2))
            u4108.write(2L, 2, 1L)
            assertEquals(1L, u4108.read(2L, 2))
            assertEquals(if (slot == 0) 0x22L else 0x66L, u4108.read(4L, 2))
            assertEquals(if (slot == 0) 0x44L else 0x88L, u4108.read(6L, 2))
            u4114.write(0L, 4, u4114.read(0L, 4) or 0x40000L)
            assertEquals(0x40000L, u4114.read(0L, 4))
            u4124.write(0L, 4, 0x200L)
            assertEquals(0x200L, u4124.read(0L, 4))

            // Execute DAT PHY analog trim sequence (0x011F08EC)
            phyTrim.write(0L, 4, 0x00008770L)
            phyTrim.write(4L, 4, 0x0790080cL)
            assertEquals(0x00008770L, phyTrim.read(0L, 4))
            assertEquals(0x0790080cL, phyTrim.read(4L, 4))

            // Execute DAT UHS-II Bank B sequences (0x011F1330, 0x011F1B00, 0x011F1AE4, 0x011F1A80, 0x011F2434, 0x011F0D74, 0x011F1818)
            u2Dma.write(0L, 4, 0x40010000L)
            u2Dma.write(4L, 4, 0x00000001L)
            assertEquals(0x40010000L, u2Dma.read(0L, 4))
            assertEquals(0x00000001L, u2Dma.read(4L, 4))
            u26280.write(0L, 2, 0x7200L)
            assertEquals(0x7200L, u26280.read(0L, 2))
            u2Cmd.write(0L, 4, 1L)
            u2Cmd.write(4L, 4, 0x11223344L)
            u2Cmd.write(8L, 4, 0x55667788L)
            u2Cmd.write(12L, 4, 0x99aabbccL)
            u2629c.write(0L, 4, 0x01000001L)
            u2Err.write(0L, 2, 2L)
            assertEquals(2L, u2Err.read(0L, 2))
            u2Err.write(2L, 2, 0x0075L)
            assertEquals(0x0075L, u2Err.read(2L, 2))
            u2Err.write(4L, 4, 0xffffffffL)
            u2Err.write(8L, 4, 0xffffffffL)
            u2Err.write(12L, 4, 0xffffffffL)
            assertEquals(0xffffffffL, u2Err.read(4L, 4))
            assertEquals(0xffffffffL, u2Err.read(8L, 4))

            // Strict direction & width refusals leave state intact
            val before = f.host.state()
            assertFails { u4108.write(4L, 2, 0L) }
            assertFails { u4108.write(6L, 2, 0L) }
            assertFails { u4100.read(0L, 4) }
            assertFails { u4114.write(0L, 2, 0L) }
            assertFails { u2Cmd.read(0L, 4) }
            assertFails { u2629c.read(0L, 4) }
            assertFails { u2Err.read(12L, 4) }
            assertFails { u2Err.read(0L, 4) }
            assertEquals(before, f.host.state())
        }

        // Malformed uhsPhyWords inputs fail before publishing any ports
        val up = good["uhsPhyWords"] as Map<*, *>
        val row0 = up["0"] as Map<*, *>
        val badInputs = listOf(
            good - "uhsPhyWords",
            good + ("uhsPhyWords" to (up + ("0" to (row0 - "uhs4100")))),
            good + ("uhsPhyWords" to (up + ("0" to (row0 + ("uhs4100" to 0x10000L))))),
            vendorPhyInput() + ("uhsPhyWords" to up),
        )
        for (bad in badInputs) {
            val ctx = Context(bad)
            assertFails { SdHostFactory().create(ctx, mapOf("profile" to bad.getValue("profile")!!, "inputFile" to "input.yaml")) }
            assertTrue(ctx.entries.isEmpty(), "Malformed v9 input must not publish ports")
        }
    }
}
