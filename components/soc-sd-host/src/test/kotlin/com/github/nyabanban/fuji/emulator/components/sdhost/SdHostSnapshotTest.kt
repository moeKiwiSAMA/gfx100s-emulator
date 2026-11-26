package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SdHostSnapshotTest {
    private class TestContext(var inputDocument: Map<String, Any?> = emptyMap()) : ComponentContext {
        var currentTick = 10L
        var currentCore = 1
        var currentPc = 0x5af98L
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-sd-host-snapshot")
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(type.isInstance(port) && registeredPorts.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                registeredPorts.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("Unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> {
            assertEquals("input.yaml", relative)
            return inputDocument
        }

        fun installExecutionLocation() {
            registeredPorts["cpu.execution-location"] = ExecutionLocationSource { ExecutionLocation(currentCore, currentPc) }
        }
    }

    private class MockMedia(capacityBytes: Int = 1024 * 1024) : CheckedBlockStoragePort {
        val bytes = ByteArray(capacityBytes) { offset -> ((offset * 19 + 5) and 0xff).toByte() }
        override fun size(slot: Int): Long? = if (slot == 1) bytes.size.toLong() else null
        override fun readOnly(slot: Int): Boolean {
            require(slot == 1)
            return false
        }
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            require(slot == 1 && offset >= 0L && length >= 0L && offset <= bytes.size.toLong() && length <= bytes.size.toLong() - offset)
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val count = target.remaining()
            checkAccess(slot, offset, count.toLong(), false)
            target.put(bytes, offset.toInt(), count)
            return count
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val count = source.remaining()
            checkAccess(slot, offset, count.toLong(), true)
            source.get(bytes, offset.toInt(), count)
            return count
        }
    }

    private class DualBankMemory : DmaMemory {
        val lowBank = ByteArray(65536) { 0x33 }
        val highBank = ByteArray(65536) { 0x66 }
        private fun selectBank(address: Long): ByteArray = when (address ushr 32) {
            0L -> lowBank
            1L -> highBank
            else -> error("Unsupported address bank")
        }
        override fun check(address: Long, size: Long, write: Boolean) {
            val bank = selectBank(address)
            val localOffset = address and 0xffffffffL
            require(size >= 0L && localOffset >= 0L && localOffset <= bank.size.toLong() && size <= bank.size.toLong() - localOffset)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val count = destination.remaining()
            check(address, count.toLong(), false)
            destination.put(selectBank(address), (address and 0xffffffffL).toInt(), count)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val count = source.remaining()
            check(address, count.toLong(), true)
            source.get(selectBank(address), (address and 0xffffffffL).toInt(), count)
        }
    }

    private fun computeCidHex(): String {
        val prefix = ByteArray(15) { index -> (index * 17 + 3).toByte() }
        var crc = 0
        for (rawByte in prefix) {
            for (bit in 7 downTo 0) {
                val feedback = ((rawByte.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
                crc = (crc shl 1) and 0x7f
                if (feedback != 0) crc = crc xor 9
            }
        }
        val fullBytes = prefix + ((crc shl 1) or 1).toByte()
        return fullBytes.joinToString("") { element -> "%02x".format(element.toInt() and 0xff) }
    }

    private fun phyInputDocument(profile: String = SdHostFactory.PHY_PROFILE): Map<String, Any?> = mapOf(
        "schema" to 1,
        "profile" to profile,
        "initialRegisters" to (0..1).associate { slotIndex ->
            slotIndex.toString() to linkedMapOf(
                "word80" to 0xa55a0005L + slotIndex,
                "control623e" to 0x55aaL + slotIndex,
                "mixed6078" to 0xa55a0005L + slotIndex,
                "control403e" to 0xaa55L + slotIndex,
                "word60ec" to 0xffffffffL - slotIndex,
            )
        },
        "clocks" to (0..3).associate { clockIndex ->
            SdClockHost.key(clockIndex) to mapOf("initialWord" to 0xa550L, "bit1Samples" to listOf(0, 1, 0, 1))
        },
        "initWords" to (0..1).associate { slotIndex ->
            slotIndex.toString() to SdInitHost.fields.mapIndexed { fieldIndex, fieldName ->
                fieldName to if (fieldIndex == 6) 0x0f00L else if (fieldIndex < 3) 0x80000000L + slotIndex * 16 + fieldIndex else 0x1200L + fieldIndex
            }.toMap()
        },
    )

    private fun transactionInputDocument(addressProfile: Boolean = true): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "schema" to 1,
            "profile" to if (addressProfile) SdHostFactory.ADDRESS_PROFILE else SdHostFactory.CARD_PROFILE,
            "commandTicks" to 2L,
            "blockTicks" to 3L,
            "resetTicks" to 2L,
            "legacy" to phyInputDocument(SdHostFactory.PHY_PROFILE).let { phyDoc ->
                phyDoc + ("clocks" to (0..3).associate { clockIndex ->
                    SdClockHost.key(clockIndex) to mapOf("initialWord" to 5L, "bit1Samples" to listOf(0, 1, 1))
                })
            },
            "cards" to mapOf(
                "0" to mapOf(
                    "basis" to "SYNTHETIC",
                    "capacityBytes" to 1024L * 1024L,
                    "highCapacity" to false,
                    "readOnly" to false,
                    "rca" to 0x1234,
                    "cidHex" to computeCidHex(),
                    "ocrVoltageWindow" to 0xff8000L,
                    "supportsHighSpeed" to true,
                ),
                "1" to null,
            ),
        )
        if (addressProfile) {
            base["addressHighBits"] = mapOf("0" to 0L, "1" to 0L)
        }
        return base
    }

    private inner class TransactionHarness(startTick: Long = 10L) {
        val context = TestContext(transactionInputDocument(addressProfile = true)).also { ctx ->
            ctx.currentTick = startTick
            ctx.installExecutionLocation()
        }
        val media = MockMedia()
        val memory = DualBankMemory()
        val irqLines = linkedMapOf<Int, Boolean>()
        val host: StatefulComponent
        val slot0Device: ScheduledMmioDevice
        val highBit0Device: MmioDevice

        init {
            context.registeredPorts["sdcard-reader"] = media
            context.registeredPorts["soc-gic.lines"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {
                    irqLines[irq] = high
                }
            }
            val created = SdHostFactory().create(
                context,
                mapOf("profile" to SdHostFactory.ADDRESS_PROFILE, "inputFile" to "input.yaml"),
            )
            created.start()
            context.registeredPorts["cpu.memory"] = memory
            host = assertIs<StatefulComponent>(created)
            slot0Device = assertIs<ScheduledMmioDevice>(context.ports.find(SdTransactionHost.port(0, true), MmioDevice::class.java))
            highBit0Device = checkNotNull(context.ports.find(SdTransactionHost.addressHighPort(0), MmioDevice::class.java))
        }

        fun advanceTicks(delta: Long) {
            context.currentTick += delta
            slot0Device.beforeInstruction(context.currentTick)
        }

        fun writeArgument(argumentValue: Long) {
            slot0Device.write(0x08L, 2, argumentValue and 0xffffL)
            slot0Device.write(0x0aL, 2, (argumentValue ushr 16) and 0xffffL)
        }

        fun issueCommand(cmdIndex: Int, argumentValue: Long = 0L, responseCode: Int = 2, transferMode: Int = 0, hasData: Boolean = false) {
            writeArgument(argumentValue)
            val commandWord = (((cmdIndex shl 8) or responseCode or (if (hasData) 0x20 else 0)).toLong() shl 16) or transferMode.toLong()
            slot0Device.write(0x0cL, 4, commandWord)
        }

        fun runSimpleCommand(cmdIndex: Int, argumentValue: Long = 0L, responseCode: Int = 2) {
            issueCommand(cmdIndex, argumentValue, responseCode)
            advanceTicks(2L)
            if (responseCode == 3) advanceTicks(3L)
        }

        fun selectCardForTransfer() {
            advanceTicks(2L)
            slot0Device.write(0x34L, 2, 0x00fbL)
            slot0Device.write(0x38L, 2, 0x00fbL)
            runSimpleCommand(0, responseCode = 0)
            runSimpleCommand(8, 0x1aaL)
            runSimpleCommand(55)
            runSimpleCommand(41, 0xff8000L)
            runSimpleCommand(2, responseCode = 1)
            runSimpleCommand(3)
            runSimpleCommand(9, 0x12340000L, responseCode = 1)
            runSimpleCommand(7, 0x12340000L, responseCode = 3)
            runSimpleCommand(55, 0x12340000L)
            runSimpleCommand(6, 2L)
            slot0Device.write(0x30L, 2, 0x00fbL)
        }
    }

    private fun simulateJsonRoundTrip(snapshot: Map<String, Any?>): Map<String, Any?> {
        val jsonText = encodeJsonValue(snapshot)
        val reader = JsonReader(jsonText)
        val parsed = reader.parseValue()
        reader.expectEnd()
        @Suppress("UNCHECKED_CAST")
        return parsed as Map<String, Any?>
    }

    private fun encodeJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().toString()
        is String -> {
            require(value.length <= 65536) { "JSON string exceeds 64 KiB limit" }
            buildString {
                append('"')
                for (character in value) {
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(character)
                    }
                }
                append('"')
            }
        }
        is List<*> -> value.joinToString(",", "[", "]") { element -> encodeJsonValue(element) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { entry ->
            val key = entry.key as? String ?: error("JSON object keys must be String")
            "${encodeJsonValue(key)}:${encodeJsonValue(entry.value)}"
        }
        else -> error("Unsupported non-JSON snapshot value: ${value::class.java.name}")
    }

    private class JsonReader(private val source: String) {
        private var position = 0
        private fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }
        fun expectEnd() {
            skipWhitespace()
            require(position == source.length)
        }
        fun parseValue(): Any? {
            skipWhitespace()
            return when (source[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }
        private fun parseObject(): Map<String, Any?> {
            position++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (source[position] == '}') {
                position++
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                require(source[position++] == ':')
                result[key] = parseValue()
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    '}' -> return result
                    else -> error("Malformed JSON object")
                }
            }
        }
        private fun parseArray(): List<Any?> {
            position++
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (source[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    ']' -> return result
                    else -> error("Malformed JSON array")
                }
            }
        }
        private fun parseString(): String {
            require(source[position++] == '"')
            val builder = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                if (character == '"') return builder.toString()
                if (character == '\\') {
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        else -> error("Unsupported escape: $escaped")
                    }
                } else builder.append(character)
            }
            error("Unterminated string")
        }
        private fun parseBoolean(): Boolean = if (source.startsWith("true", position)) {
            position += 4
            true
        } else if (source.startsWith("false", position)) {
            position += 5
            false
        } else error("Invalid boolean")
        private fun parseNull(): Any? {
            require(source.startsWith("null", position))
            position += 4
            return null
        }
        private fun parseNumber(): Number {
            val start = position
            if (source[position] == '-') position++
            while (position < source.length && source[position].isDigit()) position++
            val numberValue = source.substring(start, position).toLong()
            return if (numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numberValue.toInt() else numberValue
        }
    }

    @Test
    fun word80AndPhyLayeredProfilesPreserveRegistersClockCursorsAndWrappedLogsAcrossRestore() {
        val wordInput = mapOf("schema" to 1, "profile" to SdHostFactory.PROFILE, "initialWords" to mapOf("0" to 0x11223344L, "1" to 0xaabbccddL))
        val directWordContext = TestContext(wordInput).also { ctx -> ctx.installExecutionLocation() }
        val directWordHost = assertIs<StatefulComponent>(
            SdHostFactory().create(directWordContext, mapOf("profile" to SdHostFactory.PROFILE, "inputFile" to "input.yaml")),
        )
        directWordHost.start()
        val directWordPort0 = checkNotNull(directWordContext.ports.find(SdHostFactory.port(0), MmioDevice::class.java))
        for (step in 0 until 270) {
            directWordContext.currentTick = step.toLong()
            directWordContext.currentCore = step % 4
            directWordPort0.write(0L, 4, 0x80000000L + step)
        }
        val wordSnap = simulateJsonRoundTrip(directWordHost.saveSnapshot())
        directWordContext.currentTick = 300L
        val directWordRead = directWordPort0.read(0L, 4)

        val restoredWordContext = TestContext(wordInput).also { ctx -> ctx.installExecutionLocation() }
        val restoredWordHost = assertIs<StatefulComponent>(
            SdHostFactory().create(restoredWordContext, mapOf("profile" to SdHostFactory.PROFILE, "inputFile" to "input.yaml")),
        )
        restoredWordHost.start()
        restoredWordHost.restoreSnapshot(wordSnap)
        val restoredWordPort0 = checkNotNull(restoredWordContext.ports.find(SdHostFactory.port(0), MmioDevice::class.java))
        restoredWordContext.currentTick = 300L
        assertEquals(directWordRead, restoredWordPort0.read(0L, 4))
        assertEquals(directWordHost.state(), restoredWordHost.state())

        val phyDoc = phyInputDocument()
        val directPhyContext = TestContext(phyDoc).also { ctx -> ctx.installExecutionLocation() }
        val directPhyHost = assertIs<StatefulComponent>(
            SdHostFactory().create(directPhyContext, mapOf("profile" to SdHostFactory.PHY_PROFILE, "inputFile" to "input.yaml")),
        )
        directPhyHost.start()
        val directMixed = checkNotNull(directPhyContext.ports.find(SdControlHost.port(0, 2), MmioDevice::class.java))
        val directClock0 = checkNotNull(directPhyContext.ports.find(SdClockHost.port(0), MmioDevice::class.java))
        val directInitHost = checkNotNull(directPhyContext.ports.find(SdInitHost.port(1, 5), MmioDevice::class.java))
        for (step in 0 until 265) {
            directPhyContext.currentTick = step.toLong()
            directMixed.write(0L, if (step % 2 == 0) 4 else 2, (0x1000 + step).toLong())
            directInitHost.write(0L, 2, (0x2000 + step).toLong())
        }
        directClock0.write(0L, 2, 0x55aaL)
        assertEquals(0x55a8L, directClock0.read(0L, 2))
        assertEquals(0x55aaL, directClock0.read(0L, 2))

        val phySnapshot = simulateJsonRoundTrip(directPhyHost.saveSnapshot())

        directPhyContext.currentTick = 500L
        val directNextClockSample = directClock0.read(0L, 2)
        val directNextMixed = directMixed.read(0L, 4)

        val restoredPhyContext = TestContext(phyDoc).also { ctx -> ctx.installExecutionLocation() }
        val restoredPhyHost = assertIs<StatefulComponent>(
            SdHostFactory().create(restoredPhyContext, mapOf("profile" to SdHostFactory.PHY_PROFILE, "inputFile" to "input.yaml")),
        )
        restoredPhyHost.start()
        restoredPhyHost.restoreSnapshot(phySnapshot)
        restoredPhyContext.currentTick = 500L
        val restoredClock0 = checkNotNull(restoredPhyContext.ports.find(SdClockHost.port(0), MmioDevice::class.java))
        val restoredMixed = checkNotNull(restoredPhyContext.ports.find(SdControlHost.port(0, 2), MmioDevice::class.java))
        assertEquals(directNextClockSample, restoredClock0.read(0L, 2))
        assertEquals(directNextMixed, restoredMixed.read(0L, 4))
        assertEquals(directPhyHost.state(), restoredPhyHost.state())
    }

    @Test
    fun midCmd6SwitchPausedSdmaReadAndMidPioMultiBlockWritePreserveExactContinuationEquivalence() {
        val directCmd6 = TransactionHarness(startTick = 20L)
        directCmd6.selectCardForTransfer()
        directCmd6.slot0Device.write(0x04L, 2, 64L)
        directCmd6.slot0Device.write(0x06L, 2, 1L)
        directCmd6.issueCommand(6, 0x80fffff1L, responseCode = 2, transferMode = 0x10, hasData = true)
        directCmd6.advanceTicks(5L)
        val cmd6Prefix = LongArray(8) { directCmd6.slot0Device.read(0x20L, 4) }

        val cmd6Snapshot = simulateJsonRoundTrip(directCmd6.host.saveSnapshot())

        val restoredCmd6 = TransactionHarness(startTick = 0L)
        restoredCmd6.context.currentTick = directCmd6.context.currentTick
        restoredCmd6.host.restoreSnapshot(cmd6Snapshot)
        assertEquals(directCmd6.host.state(), restoredCmd6.host.state())
        assertEquals(directCmd6.irqLines, restoredCmd6.irqLines)

        val directCmd6Tail = LongArray(8) { directCmd6.slot0Device.read(0x20L, 4) }
        val restoredCmd6Tail = LongArray(8) { restoredCmd6.slot0Device.read(0x20L, 4) }
        assertContentEquals(directCmd6Tail, restoredCmd6Tail)
        assertEquals(8, cmd6Prefix.size)
        assertEquals(directCmd6.host.state(), restoredCmd6.host.state())

        val directSdma = TransactionHarness(startTick = 15L)
        directSdma.selectCardForTransfer()
        val legacyClock1 = checkNotNull(directSdma.context.ports.find(SdClockHost.port(1), MmioDevice::class.java))
        legacyClock1.write(0L, 2, 0x1230L)
        assertEquals(0x1230L, legacyClock1.read(0L, 2))
        directSdma.highBit0Device.write(0L, 4, 1L)
        directSdma.slot0Device.write(0x04L, 2, 512L)
        directSdma.slot0Device.write(0x06L, 2, 3L)
        directSdma.slot0Device.write(0x00L, 4, 0x0e00L)
        directSdma.issueCommand(18, 1024L, responseCode = 2, transferMode = 0x37, hasData = true)
        directSdma.advanceTicks(5L)
        assertEquals(true, ((directSdma.host.state()["slots"] as List<*>)[0] as Map<*, *>)["dmaPaused"])

        val sdmaSnapshot = simulateJsonRoundTrip(directSdma.host.saveSnapshot())
        val highBankAtT1 = directSdma.memory.highBank.copyOf()

        val restoredSdma = TransactionHarness(startTick = 0L)
        restoredSdma.context.currentTick = directSdma.context.currentTick
        highBankAtT1.copyInto(restoredSdma.memory.highBank)
        restoredSdma.host.restoreSnapshot(sdmaSnapshot)
        assertEquals(directSdma.host.state(), restoredSdma.host.state())
        assertEquals(directSdma.slot0Device.nextDeadline(), restoredSdma.slot0Device.nextDeadline())

        for (harness in listOf(directSdma, restoredSdma)) {
            harness.slot0Device.write(0x30L, 2, 0x0008L)
            harness.slot0Device.write(0x00L, 4, 0x1000L)
            harness.advanceTicks(12L)
        }
        assertContentEquals(directSdma.memory.highBank, restoredSdma.memory.highBank)
        assertContentEquals(directSdma.memory.lowBank, restoredSdma.memory.lowBank)
        assertEquals(directSdma.irqLines, restoredSdma.irqLines)
        assertEquals(directSdma.host.state(), restoredSdma.host.state())

        val directPioWrite = TransactionHarness(startTick = 30L)
        directPioWrite.selectCardForTransfer()
        directPioWrite.slot0Device.write(0x04L, 2, 512L)
        directPioWrite.slot0Device.write(0x06L, 2, 2L)
        directPioWrite.issueCommand(25, 4096L, responseCode = 2, transferMode = 0x26, hasData = true)
        directPioWrite.advanceTicks(5L)
        for (wordOffset in 0 until 64) {
            directPioWrite.slot0Device.write(0x20L, 4, 0xa5000000L + wordOffset)
        }

        val pioSnapshot = simulateJsonRoundTrip(directPioWrite.host.saveSnapshot())
        val mediaAtT1 = directPioWrite.media.bytes.copyOf()

        val restoredPioWrite = TransactionHarness(startTick = 0L)
        restoredPioWrite.context.currentTick = directPioWrite.context.currentTick
        mediaAtT1.copyInto(restoredPioWrite.media.bytes)
        restoredPioWrite.host.restoreSnapshot(pioSnapshot)
        assertEquals(directPioWrite.host.state(), restoredPioWrite.host.state())

        for (harness in listOf(directPioWrite, restoredPioWrite)) {
            for (wordOffset in 64 until 128) {
                harness.slot0Device.write(0x20L, 4, 0xa5000000L + wordOffset)
            }
            harness.advanceTicks(3L)
            for (wordOffset in 0 until 128) {
                harness.slot0Device.write(0x20L, 4, 0x5a000000L + wordOffset)
            }
            harness.advanceTicks(3L)
        }
        assertContentEquals(directPioWrite.media.bytes, restoredPioWrite.media.bytes)
        assertEquals(directPioWrite.irqLines, restoredPioWrite.irqLines)
        assertEquals(directPioWrite.host.state(), restoredPioWrite.host.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyAcrossAllNestedTransactionAndCardLayers() {
        val harness = TransactionHarness(startTick = 20L)
        harness.selectCardForTransfer()
        harness.slot0Device.write(0x04L, 2, 512L)
        harness.slot0Device.write(0x06L, 2, 1L)
        harness.issueCommand(17, 0L, responseCode = 2, transferMode = 0x10, hasData = true)
        harness.advanceTicks(2L)

        val baselineState = harness.host.state()
        val baselineLines = harness.irqLines.toMap()
        val baselineDeadline = harness.slot0Device.nextDeadline()
        val valid = simulateJsonRoundTrip(harness.host.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val slotsList = valid["slots"] as List<Map<String, Any?>>
        val slot0Map = slotsList[0]
        val slot1Map = slotsList[1]
        @Suppress("UNCHECKED_CAST")
        val card0Map = slot0Map["card"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val pending0Map = slot0Map["pending"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val legacyMap = valid["legacySnapshot"] as Map<String, Any?>

        val invalidCandidates = listOf(
            valid - "slots",
            valid + ("extra" to 1),
            valid + ("schema" to 9),
            valid + ("componentId" to "sdcard-reader"),
            valid + ("started" to false),
            valid + ("addressExtension" to false),
            valid + ("inputCanonicalSha256" to "0".repeat(64)),
            valid + ("completedCommands" to 999L),
            valid + ("mediaBytes" to 513L),
            valid + ("legacySnapshot" to (legacyMap + ("initWriteCount" to 99L))),
            valid + ("slots" to listOf(slot0Map + ("irqHigh" to false), slot1Map)),
            valid + ("slots" to listOf(slot0Map + ("reset" to 1), slot1Map)),
            valid + ("slots" to listOf(slot0Map + ("card" to (card0Map + ("revision" to 999L))), slot1Map)),
            valid + ("slots" to listOf(slot0Map + ("card" to (card0Map + ("pendingDataOrBusy" to false))), slot1Map)),
            valid + ("slots" to listOf(slot0Map + ("pending" to (pending0Map + ("bufferBase64" to "AAAA"))), slot1Map)),
            valid + ("slots" to listOf(slot0Map, slot1Map + ("rawNormalStatus" to 1))),
        )

        for (candidate in invalidCandidates) {
            assertFailsWith<IllegalArgumentException> {
                harness.host.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, harness.host.state())
            assertEquals(baselineLines, harness.irqLines)
            assertEquals(baselineDeadline, harness.slot0Device.nextDeadline())
        }
    }

    @Test
    fun vendorPhyV8SnapshotRoundTripsWritableWordsAndRejectsMutatedReadOnlyPhyWordsAtomically() {
        val v8Doc = transactionInputDocument(addressProfile = true) + mapOf(
            "profile" to SdHostFactory.VENDOR_PHY_PROFILE,
            "vendorPhyWords" to mapOf(
                "0" to mapOf("vendor41e0" to 0L, "vendor41e4" to 0L, "phy6060" to 0L, "phy607c" to 1L, "phy6094" to 0L),
                "1" to mapOf("vendor41e0" to 0L, "vendor41e4" to 0L, "phy6060" to 2L, "phy607c" to 0L, "phy6094" to 0x10L),
            ),
        )
        fun createV8(): Pair<TestContext, StatefulComponent> {
            val ctx = TestContext(v8Doc).also { it.currentTick = 10L; it.installExecutionLocation() }
            ctx.registeredPorts["sdcard-reader"] = MockMedia()
            ctx.registeredPorts["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) {} }
            val created = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.VENDOR_PHY_PROFILE, "inputFile" to "input.yaml"))
            created.start()
            ctx.registeredPorts["cpu.memory"] = DualBankMemory()
            return ctx to assertIs<StatefulComponent>(created)
        }

        val (ctxA, hostA) = createV8()
        val vendorA0 = checkNotNull(ctxA.ports.find(SdTransactionHost.vendorAPort(0), MmioDevice::class.java))
        val phy94_0 = checkNotNull(ctxA.ports.find(SdTransactionHost.phy6094Port(0), MmioDevice::class.java))
        vendorA0.write(0L, 4, 0x00000400L)
        vendorA0.write(4L, 4, 0x00ff0101L)
        phy94_0.write(0L, 4, 0x00004051L)

        val snap = simulateJsonRoundTrip(hostA.saveSnapshot())
        assertEquals(true, snap["vendorPhy"])

        val (ctxB, hostB) = createV8()
        hostB.restoreSnapshot(snap)
        assertEquals(hostA.state(), hostB.state())
        assertEquals(0x00000400L, checkNotNull(ctxB.ports.find(SdTransactionHost.vendorAPort(0), MmioDevice::class.java)).read(0L, 4))
        assertEquals(0x00ff0101L, checkNotNull(ctxB.ports.find(SdTransactionHost.vendorAPort(0), MmioDevice::class.java)).read(4L, 4))
        assertEquals(0x00004051L, checkNotNull(ctxB.ports.find(SdTransactionHost.phy6094Port(0), MmioDevice::class.java)).read(0L, 4))

        // Mutating read-only phy6060 or phy607c or dropping vendorPhy must fail atomically
        val before = hostB.state()
        @Suppress("UNCHECKED_CAST")
        val slotsList = snap["slots"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val slot0Vp = slotsList[0]["vendorPhyWords"] as Map<String, Any?>
        val badSnaps = listOf(
            snap - "vendorPhy",
            snap + ("vendorPhy" to false),
            snap + ("slots" to listOf(slotsList[0] + ("vendorPhyWords" to (slot0Vp + ("phy6060" to 2L))), slotsList[1])),
            snap + ("slots" to listOf(slotsList[0] + ("vendorPhyWords" to (slot0Vp + ("phy607c" to 0L))), slotsList[1])),
            snap + ("slots" to listOf(slotsList[0] - "vendorPhyWords", slotsList[1])),
        )
        for (bad in badSnaps) {
            assertFailsWith<IllegalArgumentException> { hostB.restoreSnapshot(bad) }
            assertEquals(before, hostB.state())
        }
    }

    @Test
    fun uhsPhyV9SnapshotRoundTripsAllPromotedUhsRegistersAndRejectsMutatedReadOnlyTuningStatusAtomically() {
        val initUhsRow0 = SdTransactionHost.uhsPhyFields.associateWith { field ->
            when (field) {
                "uhs410c" -> 0x0012L
                "uhs410e" -> 0x0034L
                else -> 0L
            }
        }
        val initUhsRow1 = SdTransactionHost.uhsPhyFields.associateWith { field ->
            when (field) {
                "uhs410c" -> 0x0056L
                "uhs410e" -> 0x0078L
                else -> 0L
            }
        }
        val v9Doc = transactionInputDocument(addressProfile = true) + mapOf(
            "profile" to SdHostFactory.UHS_PHY_PROFILE,
            "vendorPhyWords" to mapOf(
                "0" to mapOf("vendor41e0" to 0L, "vendor41e4" to 0L, "phy6060" to 0L, "phy607c" to 1L, "phy6094" to 0L),
                "1" to mapOf("vendor41e0" to 0L, "vendor41e4" to 0L, "phy6060" to 2L, "phy607c" to 0L, "phy6094" to 0x10L),
            ),
            "uhsPhyWords" to mapOf("0" to initUhsRow0, "1" to initUhsRow1),
        )
        fun createV9(): Pair<TestContext, StatefulComponent> {
            val ctx = TestContext(v9Doc).also { it.currentTick = 10L; it.installExecutionLocation() }
            ctx.registeredPorts["sdcard-reader"] = MockMedia()
            ctx.registeredPorts["soc-gic.lines"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) {} }
            val created = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "input.yaml"))
            created.start()
            ctx.registeredPorts["cpu.memory"] = DualBankMemory()
            return ctx to assertIs<StatefulComponent>(created)
        }

        val (ctxA, hostA) = createV9()
        checkNotNull(ctxA.ports.find(SdTransactionHost.uhs4100Port(0), MmioDevice::class.java)).write(0L, 2, 0x0005L)
        checkNotNull(ctxA.ports.find(SdTransactionHost.uhs4108Port(0), MmioDevice::class.java)).write(0L, 2, 0x013fL)
        checkNotNull(ctxA.ports.find(SdTransactionHost.uhs4114Port(0), MmioDevice::class.java)).write(0L, 4, 0x00040000L)
        checkNotNull(ctxA.ports.find(SdTransactionHost.phyTrimPort(0), MmioDevice::class.java)).write(0L, 4, 0x00008770L)
        checkNotNull(ctxA.ports.find(SdTransactionHost.phyTrimPort(0), MmioDevice::class.java)).write(4L, 4, 0x0790080cL)
        checkNotNull(ctxA.ports.find(SdTransactionHost.uhs2DmaPort(0), MmioDevice::class.java)).write(0L, 4, 0x40001000L)
        checkNotNull(ctxA.ports.find(SdTransactionHost.uhs2ErrPort(0), MmioDevice::class.java)).write(2L, 2, 0x0075L)

        val snap = simulateJsonRoundTrip(hostA.saveSnapshot())
        assertEquals(true, snap["vendorPhy"])
        assertEquals(true, snap["uhsPhy"])

        val (ctxB, hostB) = createV9()
        hostB.restoreSnapshot(snap)
        assertEquals(hostA.state(), hostB.state())
        assertEquals(0x0005L, checkNotNull(ctxB.ports.find(SdTransactionHost.uhs4100Port(0), MmioDevice::class.java)).read(0L, 2))
        assertEquals(0x013fL, checkNotNull(ctxB.ports.find(SdTransactionHost.uhs4108Port(0), MmioDevice::class.java)).read(0L, 2))
        assertEquals(0x0012L, checkNotNull(ctxB.ports.find(SdTransactionHost.uhs4108Port(0), MmioDevice::class.java)).read(4L, 2))
        assertEquals(0x00008770L, checkNotNull(ctxB.ports.find(SdTransactionHost.phyTrimPort(0), MmioDevice::class.java)).read(0L, 4))
        assertEquals(0x0075L, checkNotNull(ctxB.ports.find(SdTransactionHost.uhs2ErrPort(0), MmioDevice::class.java)).read(2L, 2))

        val before = hostB.state()
        @Suppress("UNCHECKED_CAST")
        val slotsList = snap["slots"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val slot0Uhs = slotsList[0]["uhsPhyWords"] as Map<String, Any?>
        val badSnaps = listOf(
            snap - "uhsPhy",
            snap + ("uhsPhy" to false),
            snap + ("slots" to listOf(slotsList[0] + ("uhsPhyWords" to (slot0Uhs + ("uhs410c" to 0x99L))), slotsList[1])),
            snap + ("slots" to listOf(slotsList[0] + ("uhsPhyWords" to (slot0Uhs + ("uhs410e" to 0x99L))), slotsList[1])),
            snap + ("slots" to listOf(slotsList[0] - "uhsPhyWords", slotsList[1])),
        )
        for (bad in badSnaps) {
            assertFailsWith<IllegalArgumentException> { hostB.restoreSnapshot(bad) }
            assertEquals(before, hostB.state())
        }
    }
}
