package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*

class FlashSnapshotTest {
    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private inner class MockFlashEnvironment {
        var currentTick = 0L
        val arrayBytes = ByteArray(131072) { index -> ((index * 17 + 3) and 0xff).toByte() }
        val seedSha = sha256Hex(arrayBytes)
        var generation = 0L
        var leasePending = false
        val irqHistory = mutableListOf<Boolean>()
        val claims = linkedMapOf<String, Any>()

        val context = object : ComponentContext {
            override val directory: Path = Path.of(".").toAbsolutePath()
            override val clock = EmulationClock { currentTick }
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
            override fun dataPath(relative: String): Path = error("No filesystem access expected in controller unit test")
        }

        init {
            claims["board.layout"] = object : AddressLayout {
                override fun window(port: String) = AddressWindow(port, 0xf0000000L, 0x8000000L)
            }
            claims["test.image"] = object : SupplementalImagePort {
                override val length = 4096L
                override val sha256 = "0".repeat(64)
                override val supplementalRegions = listOf(
                    ImageRegionIdentity("domain", 0x3dc0000L, arrayBytes.size.toLong(), seedSha, "seed.img"),
                )
                override fun check(offset: Long, size: Long) {
                    require(offset >= 0x3dc0000L && size > 0L && offset <= 0x3dc0000L + arrayBytes.size - size)
                }
                override fun read(offset: Long, destination: ByteBuffer) {
                    val count = destination.remaining()
                    check(offset, count.toLong())
                    destination.put(arrayBytes, (offset - 0x3dc0000L).toInt(), count)
                }
            }
            claims["test.image.mutable"] = object : WritableImagePort {
                override fun snapshot() = WritableImageState(
                    regionId = "domain",
                    offset = 0x3dc0000L,
                    length = arrayBytes.size.toLong(),
                    relativeFile = "copy.img",
                    writableOffset = 0x3dc0000L,
                    writableLength = arrayBytes.size.toLong(),
                    seedSha256 = seedSha,
                    currentSha256 = sha256Hex(arrayBytes),
                    generation = generation,
                    pending = leasePending,
                    closed = false,
                    failure = null,
                )
                override fun prepareErase(offset: Long, size: Long): ImageEraseTransaction {
                    require(!leasePending && size in listOf(4096L, 65536L) && offset >= 0x3dc0000L && offset % size == 0L && offset <= 0x3de0000L - size)
                    leasePending = true
                    return object : ImageEraseTransaction {
                        override val offset = offset
                        override val size = size
                        var ended = false
                        override fun commit(): ImageEraseReceipt {
                            check(!ended && leasePending)
                            val before = sha256Hex(arrayBytes)
                            val start = (offset - 0x3dc0000L).toInt()
                            val changed = (start until start + size.toInt()).count { index -> arrayBytes[index] != (-1).toByte() }.toLong()
                            arrayBytes.fill((-1).toByte(), start, start + size.toInt())
                            generation++
                            leasePending = false
                            ended = true
                            return ImageEraseReceipt(offset, size, changed, before, sha256Hex(arrayBytes), generation)
                        }
                        override fun close() {
                            if (!ended) {
                                ended = true
                                leasePending = false
                            }
                        }
                    }
                }
            }
            claims["test.image.program"] = object : ProgrammableImagePort {
                override fun prepareProgram(offset: Long, payload: ByteBuffer): ImageProgramTransaction {
                    check(!leasePending)
                    val frozen = ByteArray(payload.remaining())
                    payload.duplicate().get(frozen)
                    val start = (offset - 0x3dc0000L).toInt()
                    require(frozen.indices.all { index ->
                        (arrayBytes[start + index].toInt() and frozen[index].toInt() and 0xff) == (frozen[index].toInt() and 0xff)
                    }) { "Zero-to-one bit refused" }
                    leasePending = true
                    return object : ImageProgramTransaction {
                        override val offset = offset
                        override val size = frozen.size
                        var ended = false
                        override fun commit(): ImageProgramReceipt {
                            check(!ended && leasePending)
                            val before = sha256Hex(arrayBytes)
                            val changed = frozen.indices.count { index -> arrayBytes[start + index] != frozen[index] }.toLong()
                            frozen.copyInto(arrayBytes, start)
                            generation++
                            leasePending = false
                            ended = true
                            return ImageProgramReceipt(offset, size, changed, sha256Hex(frozen), before, sha256Hex(arrayBytes), generation)
                        }
                        override fun close() {
                            if (!ended) {
                                ended = true
                                leasePending = false
                            }
                        }
                    }
                }
            }
            claims["test.irq"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {
                    irqHistory.add(high)
                }
            }
        }
    }

    private inner class Fixture(profile: String = FlashFactory.PROGRAM_PROFILE) {
        val env = MockFlashEnvironment()
        val component: StatefulComponent
        val registers: ScheduledMmioDevice
        val dmaSource: DmaSourcePeer
        val dmaSink: DmaDestinationPeer?

        init {
            val factory = FlashFactory()
            val config = mapOf(
                "profile" to profile,
                "jedec" to if (profile == "dat-readonly-experimental-v1") -1L else FlashFactory.ENABLE_JEDEC,
                "imagePort" to "test.image",
                "interruptPort" to "test.irq",
                "irq" to 37L,
            )
            component = factory.create(env.context, config) as StatefulComponent
            registers = env.context.ports.find("soc-flash.registers", MmioDevice::class.java) as ScheduledMmioDevice
            dmaSource = checkNotNull(env.context.ports.find("soc-flash.dma", DmaSourcePeer::class.java))
            dmaSink = env.context.ports.find("soc-flash.tx", DmaDestinationPeer::class.java)
        }

        fun writeReg(offset: Int, value: Long) = registers.write(offset.toLong(), 4, value)
        fun readReg(offset: Int): Long = registers.read(offset.toLong(), 4)
        fun advanceTo(tick: Long) {
            env.currentTick = tick
            registers.beforeInstruction(tick)
        }
        fun enableWriteSelector0() {
            writeReg(0, readReg(0) and (15L shl 10).inv())
            writeReg(0x90, 0x06000001L)
            advanceTo(env.currentTick + 8L)
        }
        fun configureProgramPage(rawAddress: Long, byteLength: Long = 256L) {
            writeReg(0, (readReg(0) and 0x4000bc00L.inv()) or 0x8000L)
            writeReg(8, 0x12L)
            writeReg(0x20, 0x202L)
            writeReg(0x74, 0xffffffffL)
            writeReg(0x78, rawAddress)
            writeReg(0x1c, rawAddress)
            writeReg(0x7c, byteLength)
            writeReg(0x80, 15L)
            writeReg(0xe0, 0L)
            writeReg(0xf0, 0L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTrip(snapshot: Map<String, Any?>): Map<String, Any?> =
        roundTripNode(snapshot) as Map<String, Any?>

    private fun roundTripNode(node: Any?): Any? = when (node) {
        null, is Boolean, is String -> node
        is Byte, is Short, is Int, is Long -> {
            val value = (node as Number).toLong()
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        }
        is List<*> -> node.map { element -> roundTripNode(element) }
        is Map<*, *> -> {
            val copy = linkedMapOf<String, Any?>()
            for ((rawKey, rawValue) in node) {
                val key = rawKey as? String ?: fail("Snapshot map key must be String, got: $rawKey")
                copy[key] = roundTripNode(rawValue)
            }
            copy
        }
        else -> fail("Non-JSON snapshot value leaked: ${node::class.java.name}")
    }

    @Test
    fun t1ToT2EquivalenceAcrossPioErasePartialProgramStagingAndMappedRead() {
        val primary = Fixture()
        val restored = Fixture()

        primary.env.currentTick = 10L
        primary.writeReg(0xb4, 0x40000000L)
        primary.advanceTo(18L)
        primary.writeReg(0xb4, 0xc01f0000L)
        primary.advanceTo(26L)

        primary.writeReg(0x44, 0x4000L)
        primary.writeReg(0x90, 0x9f200001L)
        primary.advanceTo(34L)
        assertEquals(FlashFactory.ENABLE_JEDEC, primary.readReg(0xa0))
        assertEquals(true, primary.component.state()["irqPending"])

        primary.writeReg(0, 1L shl 10)
        primary.writeReg(0x90, 0x06000001L)
        primary.advanceTo(42L)

        primary.enableWriteSelector0()
        primary.writeReg(0x94, 0xf3dc0000L)
        primary.writeReg(0xa8, 0L)
        primary.writeReg(0xe0, 0L)
        primary.writeReg(0xf0, 0L)
        primary.writeReg(0x90, 0x210b0001L)
        primary.advanceTo(5000L)

        primary.enableWriteSelector0()
        primary.configureProgramPage(0xf3dc0000L, 256L)
        primary.writeReg(0x70, 1L)
        primary.advanceTo(5016L)
        primary.writeReg(0x70, 0x20L)
        val primarySink = checkNotNull(primary.dmaSink)
        assertEquals(5016L, primarySink.claim(0, 0xf3dc0000L, 256L, 4, 43))
        val pageOne = ByteArray(256) { index -> (index xor 0x55).toByte() }
        primarySink.write(0, 0xf3dc0000L, 0L, ByteBuffer.wrap(pageOne))
        primary.env.currentTick = 5040L
        primarySink.finish(0, 5040L, 5040L)
        primary.advanceTo(9136L)

        primary.enableWriteSelector0()
        primary.configureProgramPage(0xf3dc0100L, 256L)
        primary.writeReg(0x70, 1L)
        primary.advanceTo(9152L)
        assertEquals(9152L, primarySink.claim(2, 0xf3dc0100L, 256L, 4, 43))
        val pageTwoFirstHalf = ByteArray(128) { index -> (0xc0 - index).toByte() }
        primarySink.write(2, 0xf3dc0100L, 0L, ByteBuffer.wrap(pageTwoFirstHalf))
        primary.writeReg(0x90, 0x05800001L)

        val snapshotT1 = simulateJsonRoundTrip(primary.component.saveSnapshot())
        primary.env.arrayBytes.copyInto(restored.env.arrayBytes)
        restored.env.generation = primary.env.generation
        restored.env.currentTick = primary.env.currentTick
        restored.component.restoreSnapshot(snapshotT1)

        assertEquals(primary.component.state(), restored.component.state())
        assertEquals(primary.registers.nextDeadline(), restored.registers.nextDeadline())

        val pageTwoSecondHalf = ByteArray(128) { index -> (0x3f and index).toByte() }
        for (instance in listOf(primary, restored)) {
            instance.advanceTo(9160L)
            assertEquals(1L, instance.readReg(0xa0))
            val sink = checkNotNull(instance.dmaSink)
            sink.write(2, 0xf3dc0100L, 128L, ByteBuffer.wrap(pageTwoSecondHalf))
            val readback = ByteBuffer.allocate(256)
            sink.readBack(2, 0xf3dc0100L, 0L, readback)
            assertContentEquals(pageTwoFirstHalf + pageTwoSecondHalf, readback.array())
            instance.env.currentTick = 9200L
            sink.finish(2, 9200L, 9200L)
            instance.advanceTo(13296L)

            instance.writeReg(0x20, 0x606L)
            instance.writeReg(0x68, 0xf3dc0000L)
            instance.writeReg(0x6c, 512L)
            instance.writeReg(0x60, 1L)
            instance.advanceTo(13304L)
            assertEquals(13304L, instance.dmaSource.claim(4, 0xf3dc0000L, 512L))
            val dmaReadBuffer = ByteBuffer.allocate(512)
            instance.dmaSource.read(4, 0xf3dc0000L, dmaReadBuffer)
            assertContentEquals(pageOne + pageTwoFirstHalf + pageTwoSecondHalf, dmaReadBuffer.array())
            instance.dmaSource.consume(4)
            instance.writeReg(0x60, 0x20L)

            instance.enableWriteSelector0()
            instance.writeReg(0x94, 0xf3dc1000L)
            instance.writeReg(0x90, 0x210b0001L)
            instance.advanceTo(18000L)
        }

        assertEquals(primary.component.state(), restored.component.state())
        assertContentEquals(primary.env.arrayBytes, restored.env.arrayBytes)
        primary.component.close()
        restored.component.close()
    }

    @Test
    fun rejectsInFlightStorageTransactionsAndInvalidSnapshotsAtomically() {
        val eraseInFlight = Fixture()
        eraseInFlight.env.currentTick = 10L
        eraseInFlight.enableWriteSelector0()
        eraseInFlight.writeReg(0x94, 0xf3dc0000L)
        eraseInFlight.writeReg(0x90, 0x210b0001L)
        assertFails { eraseInFlight.component.saveSnapshot() }
        eraseInFlight.advanceTo(26L)
        assertFails { eraseInFlight.component.saveSnapshot() }
        eraseInFlight.component.close()

        val programInFlight = Fixture()
        programInFlight.env.currentTick = 10L
        programInFlight.enableWriteSelector0()
        programInFlight.writeReg(0x94, 0xf3dc0000L)
        programInFlight.writeReg(0x90, 0x210b0001L)
        programInFlight.advanceTo(4200L)
        programInFlight.enableWriteSelector0()
        programInFlight.configureProgramPage(0xf3dc0000L, 64L)
        programInFlight.writeReg(0x70, 1L)
        programInFlight.advanceTo(4216L)
        val sink = checkNotNull(programInFlight.dmaSink)
        sink.claim(0, 0xf3dc0000L, 64L, 4, 43)
        sink.write(0, 0xf3dc0000L, 0L, ByteBuffer.wrap(ByteArray(64) { 0x11 }))
        programInFlight.env.currentTick = 4230L
        sink.finish(0, 4230L, 4230L)
        assertFails { programInFlight.component.saveSnapshot() }
        programInFlight.component.close()

        val target = Fixture()
        target.env.currentTick = 10L
        target.enableWriteSelector0()
        val validSnapshot = simulateJsonRoundTrip(target.component.saveSnapshot())
        val baselineState = target.component.state()

        @Suppress("UNCHECKED_CAST")
        val validScalars = validSnapshot["scalars"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validProgram = validSnapshot["programEngine"] as Map<String, Any?>
        val badRegisters = (validSnapshot["registers"] as List<*>).toMutableList().also { list -> list[9] = 1 }

        val invalidSnapshots = listOf(
            validSnapshot + ("schema" to 99),
            validSnapshot + ("componentId" to "soc-dma"),
            validSnapshot + ("scalars" to (validScalars + ("irq" to 40))),
            validSnapshot + ("scalars" to (validScalars + ("irq" to 1024))),
            validSnapshot + ("registers" to badRegisters),
            validSnapshot + ("irqPending" to true),
            validSnapshot + ("eventCount" to 99),
            validSnapshot + ("programEngine" to (validProgram + ("stagingBufferBase64" to "AA=="))),
            validSnapshot + ("eventCount" to 1) + ("events" to listOf(mapOf("operation" to "unknown-op"))),
        )
        for (badSnapshot in invalidSnapshots) {
            assertFails { target.component.restoreSnapshot(badSnapshot) }
            assertEquals(baselineState, target.component.state())
        }

        val readonlyTarget = Fixture(profile = "dat-readonly-experimental-v1")
        val readonlyBaseline = readonlyTarget.component.state()
        assertFails { readonlyTarget.component.restoreSnapshot(validSnapshot) }
        assertEquals(readonlyBaseline, readonlyTarget.component.state())

        readonlyTarget.component.close()
        target.component.close()
        assertFails { target.component.saveSnapshot() }
    }
}
