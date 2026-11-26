package com.github.nyabanban.fuji.emulator.components.method4

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class Method4SnapshotTest {
    private class BackingRam(val sizeBytes: Int, val baseAddress: Long = 0L) : DmaMemory {
        val bytes = ByteArray(sizeBytes)

        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= baseAddress && size >= 0 && address - baseAddress + size <= bytes.size) {
                "RAM bounds check failed at 0x${address.toString(16)}+$size"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val start = (address - baseAddress).toInt()
            destination.put(bytes, start, destination.remaining())
        }

        override fun write(address: Long, source: ByteBuffer) {
            val start = (address - baseAddress).toInt()
            source.get(bytes, start, source.remaining())
        }

        fun putBytes(address: Long, payload: ByteArray) {
            val start = (address - baseAddress).toInt()
            payload.copyInto(bytes, start)
        }

        fun slice(address: Long, length: Int): ByteArray {
            val start = (address - baseAddress).toInt()
            return bytes.copyOfRange(start, start + length)
        }
    }

    private class RecordingIrqSink : InterruptSink {
        val transitions = ArrayList<Pair<Int, Boolean>>()
        override fun setLine(irq: Int, high: Boolean) {
            transitions.add(irq to high)
        }
    }

    private class Method4TestContext(val ram: BackingRam) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        val irqSink = RecordingIrqSink()
        var tick = 100L

        init {
            claims["cpu.memory"] = ram
            claims["soc-gic.lines"] = irqSink
        }

        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                claims.keys.removeIf { key -> key != "cpu.memory" && key != "soc-gic.lines" }
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = error("Unexpected readData: $relative")
    }

    private fun jsonRoundTrip(raw: Any?): Any? = when (raw) {
        null, is Boolean, is String -> raw
        is Byte -> raw.toInt()
        is Short -> raw.toInt()
        is Int -> raw
        is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else raw
        is Double -> raw
        is List<*> -> raw.map { element -> jsonRoundTrip(element) }
        is Map<*, *> -> jsonRoundTripMap(raw)
        else -> error("Unsupported JSON snapshot value type: ${raw::class.java}")
    }

    private fun jsonRoundTripMap(raw: Map<*, *>): Map<String, Any?> {
        val converted = LinkedHashMap<String, Any?>()
        for ((key, entryValue) in raw) {
            check(key is String) { "Non-string snapshot key: $key" }
            converted[key] = jsonRoundTrip(entryValue)
        }
        return converted
    }

    private data class BuiltStream(val fullStreamBytes: ByteArray, val expectedOutput: ByteArray, val pageCount: Int)

    private fun buildMultiPageStream(): BuiltStream {
        val rawPayload = ByteArrayOutputStream()
        val expected = ByteArrayOutputStream()

        val firstChunkSize = 0x3fec - 2
        var emittedPayloadBytes = 0
        var patternCounter = 0
        while (emittedPayloadBytes + 128 <= firstChunkSize) {
            rawPayload.write(127)
            repeat(127) {
                val byteVal = ((patternCounter * 37 + 11) and 0xff)
                rawPayload.write(byteVal)
                expected.write(byteVal)
                patternCounter++
            }
            emittedPayloadBytes += 128
        }
        val remainingBeforeSplit = firstChunkSize - emittedPayloadBytes
        if (remainingBeforeSplit > 0) {
            val litLen = remainingBeforeSplit - 1
            check(litLen >= 1)
            rawPayload.write(litLen)
            repeat(litLen) {
                val byteVal = ((patternCounter * 37 + 11) and 0xff)
                rawPayload.write(byteVal)
                expected.write(byteVal)
                patternCounter++
            }
        }
        rawPayload.write(5)
        rawPayload.write(0xaa)
        expected.write(0xaa)
        check(rawPayload.size() == 0x4000 - 20)

        repeat(4) { step ->
            val byteVal = 0xb0 + step
            rawPayload.write(byteVal)
            expected.write(byteVal)
        }
        var page2Bytes = 4
        while (page2Bytes + 2 <= 0x4000 - 3) {
            val distance = 2000
            val length = 15
            val firstByte = 0x80 or ((distance ushr 4) and 0x7f)
            val secondByte = ((distance and 0x0f) shl 4) or length
            rawPayload.write(firstByte)
            rawPayload.write(secondByte)
            val currentOut = expected.toByteArray()
            repeat(length) { copyOffset ->
                expected.write(currentOut[currentOut.size - distance + copyOffset].toInt() and 0xff)
            }
            page2Bytes += 2
        }
        val tailPadLiterals = (0x4000 - 1) - page2Bytes - 1
        check(tailPadLiterals in 1..127)
        rawPayload.write(tailPadLiterals)
        repeat(tailPadLiterals) { step ->
            val padByte = 0x5a + step
            rawPayload.write(padByte)
            expected.write(padByte)
        }
        page2Bytes += 1 + tailPadLiterals
        check(page2Bytes == 0x4000 - 1)
        val splitDistance = 512
        val splitCount = 9
        val splitFirst = 0x80 or ((splitDistance ushr 4) and 0x7f)
        val splitSecond = ((splitDistance and 0x0f) shl 4) or splitCount
        rawPayload.write(splitFirst)
        check(rawPayload.size() == 0x8000 - 20)

        rawPayload.write(splitSecond)
        val snapshotOut = expected.toByteArray()
        repeat(splitCount) { copyOffset ->
            expected.write(snapshotOut[snapshotOut.size - splitDistance + copyOffset].toInt() and 0xff)
        }
        rawPayload.write(3)
        for (tailByte in listOf(0x45, 0x4c, 0x46)) {
            rawPayload.write(tailByte)
            expected.write(tailByte)
        }

        val payloadBytes = rawPayload.toByteArray()
        val expectedBytes = expected.toByteArray()
        val packedSize = 20 + payloadBytes.size
        val pageCount = (packedSize + 0x3fff) / 0x4000
        check(pageCount == 3)

        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(expectedBytes.size)
            .putInt(packedSize)
            .putInt(pageCount)
            .putInt(0x4000)
            .putInt(4)
            .array()
        return BuiltStream(header + payloadBytes, expectedBytes, pageCount)
    }

    @Test
    fun midStreamAndPostCompletionSnapshotsRoundTripAcrossCrossPageLzTokensAndHistory() {
        val built = buildMultiPageStream()
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(built.expectedOutput).joinToString("") { "%02x".format(it) }
        val sourceBase = 0x0001_0000L
        val destBase = 0x0003_0000L
        val packedSize = built.fullStreamBytes.size

        val factory = Method4Factory()
        val config = mapOf(
            "profile" to Method4Factory.V1_PROFILE,
            "memoryPort" to "cpu.memory",
            "interruptPort" to "soc-gic.lines",
            "irq" to 128L,
        )

        val primaryRam = BackingRam(0x100000)
        primaryRam.putBytes(sourceBase, built.fullStreamBytes)
        val primaryCtx = Method4TestContext(primaryRam)
        val primary = factory.create(primaryCtx, config) as StatefulComponent
        val primaryMmio = primary as ScheduledMmioDevice

        primaryMmio.write(0x20, 4, 0x8400_0400L)
        primaryMmio.write(0x20, 4, 0x9400_1400L)
        primaryMmio.write(0x10, 4, destBase)
        primaryMmio.write(0x14, 4, built.expectedOutput.size.toLong())
        primaryMmio.write(0x18, 4, 20L)
        primaryMmio.write(0x1c, 4, built.pageCount.toLong())

        primaryCtx.tick = 1000L
        primaryMmio.write(0x08, 4, sourceBase + 20L)
        primaryMmio.write(0x0c, 4, 0x4000L)
        primaryMmio.write(0x04, 4, 1L)
        primaryMmio.beforeInstruction(1001L)
        primaryMmio.write(0x28, 4, 1L)
        primaryMmio.beforeInstruction(1002L)

        primaryCtx.tick = 2000L
        primaryMmio.write(0x08, 4, sourceBase + 0x4000L)
        primaryMmio.write(0x0c, 4, 0x4000L)
        primaryMmio.write(0x04, 4, 1L)
        assertEquals(2, primary.state()["page"])
        assertNotNull(primary.state()["matchFirst"])
        assertEquals(true, primary.state()["irqPending"])
        assertNull(primary.state()["completedSha256"])

        val midSaved = primary.saveSnapshot()
        val midRoundTripped = jsonRoundTripMap(midSaved)

        val restoredRam = BackingRam(0x100000)
        primaryRam.bytes.copyInto(restoredRam.bytes)
        val restoredCtx = Method4TestContext(restoredRam)
        restoredCtx.tick = primaryCtx.tick
        val restored = factory.create(restoredCtx, config) as StatefulComponent
        val restoredMmio = restored as ScheduledMmioDevice
        restored.restoreSnapshot(midRoundTripped)

        assertEquals(primary.state(), restored.state())

        primaryCtx.irqSink.transitions.clear()
        restoredCtx.irqSink.transitions.clear()
        val page3Len = (packedSize - 0x8000).toLong()
        for ((ctx, mmio) in listOf(primaryCtx to primaryMmio, restoredCtx to restoredMmio)) {
            mmio.beforeInstruction(2005L)
            mmio.write(0x28, 4, 1L)
            mmio.beforeInstruction(2006L)
            ctx.tick = 3000L
            mmio.write(0x08, 4, sourceBase + 0x8000L)
            mmio.write(0x0c, 4, page3Len)
            mmio.write(0x04, 4, 1L)
            mmio.beforeInstruction(3001L)
        }

        assertEquals(primaryCtx.irqSink.transitions, restoredCtx.irqSink.transitions)
        assertEquals(expectedSha, restored.state()["completedSha256"])
        assertEquals(expectedSha, restored.state()["outputReadbackSha256"])
        assertContentEquals(built.expectedOutput, restoredRam.slice(destBase, built.expectedOutput.size))
        assertEquals(primary.state(), restored.state())

        val postSaved = jsonRoundTripMap(restored.saveSnapshot())
        val postRam = BackingRam(0x100000)
        restoredRam.bytes.copyInto(postRam.bytes)
        val postCtx = Method4TestContext(postRam)
        val postEngine = factory.create(
            postCtx,
            config + mapOf("profile" to Method4Factory.V2_PROFILE),
        ) as StatefulComponent
        postEngine.restoreSnapshot(postSaved)
        assertEquals(expectedSha, postEngine.state()["completedSha256"])
        assertEquals(expectedSha, postEngine.state()["outputReadbackSha256"])

        val postMmio = postEngine as ScheduledMmioDevice
        postMmio.write(0x28, 4, 1L)
        assertEquals(false, postEngine.state()["irqPending"])
        postMmio.write(0x00, 4, 0L)
        assertNull(postEngine.state()["completedSha256"])
    }

    @Test
    fun malformedSnapshotsRejectAtomicallyWithoutCorruptingLiveEngine() {
        val built = buildMultiPageStream()
        val ram = BackingRam(0x100000)
        ram.putBytes(0x10000L, built.fullStreamBytes)
        val ctx = Method4TestContext(ram)
        val factory = Method4Factory()
        val config = mapOf(
            "profile" to Method4Factory.V2_PROFILE,
            "memoryPort" to "cpu.memory",
            "interruptPort" to "soc-gic.lines",
            "irq" to 128,
        )
        val engine = factory.create(ctx, config) as StatefulComponent
        val mmio = engine as ScheduledMmioDevice

        mmio.write(0x20, 4, 0x9400_1400L)
        mmio.write(0x10, 4, 0x30000L)
        mmio.write(0x14, 4, built.expectedOutput.size.toLong())
        mmio.write(0x18, 4, 20L)
        mmio.write(0x1c, 4, built.pageCount.toLong())
        mmio.write(0x08, 4, 0x10014L)
        mmio.write(0x0c, 4, 0x4000L)
        mmio.write(0x04, 4, 1L)

        val validSnap = jsonRoundTripMap(engine.saveSnapshot())
        val stateBeforeBad = engine.state()
        @Suppress("UNCHECKED_CAST")
        val streamMap = validSnap["stream"] as Map<String, Any?>
        val badPayloads = listOf<Map<String, Any?>>(
            validSnap + mapOf("schema" to 2),
            validSnap + mapOf("componentId" to "soc-gic"),
            validSnap + mapOf("configured" to false),
            validSnap + mapOf("irqPending" to false),
            validSnap + mapOf("completedSha256" to "a".repeat(64)),
            validSnap + mapOf("completedSha256" to "not-a-sha256"),
            validSnap + mapOf("eventCount" to 0),
            validSnap + mapOf("eventCount" to 1.25),
            validSnap + mapOf("stream" to (streamMap + mapOf("historyBase64" to Base64.getEncoder().encodeToString(ByteArray(2047))))),
            validSnap + mapOf("stream" to (streamMap + mapOf("literalRemaining" to 3, "matchFirst" to 0x80))),
            validSnap + mapOf("stream" to (streamMap + mapOf("consumed" to 999))),
            validSnap + mapOf("settings" to mapOf(
                "profile" to Method4Factory.V2_PROFILE,
                "memoryPort" to "cpu.memory",
                "interruptPort" to "soc-gic.lines",
                "irq" to 129,
            )),
        )
        for (badPayload in badPayloads) {
            assertFails { engine.restoreSnapshot(badPayload) }
            assertEquals(stateBeforeBad, engine.state())
        }

        val unroutedEngine = factory.create(
            Method4TestContext(BackingRam(0x100000)),
            config + mapOf("interruptPort" to ""),
        ) as StatefulComponent
        assertFails { unroutedEngine.restoreSnapshot(validSnap) }

        val v1Engine = factory.create(
            Method4TestContext(BackingRam(0x100000)),
            config + mapOf("profile" to Method4Factory.V1_PROFILE),
        ) as StatefulComponent
        assertFails { v1Engine.restoreSnapshot(validSnap) }

        mmio.write(0x28, 4, 1L)
        mmio.write(0x08, 4, 0x14000L)
        mmio.write(0x0c, 4, 0x4000L)
        mmio.write(0x04, 4, 1L)
        assertEquals(2, engine.state()["page"])
    }
}
