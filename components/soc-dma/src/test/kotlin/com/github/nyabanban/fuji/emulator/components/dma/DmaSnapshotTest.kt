package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class DmaSnapshotTest {
    private class TestContext : ComponentContext {
        var currentTick = 0L
        val claims = linkedMapOf<String, Any>()
        val irqLines = linkedMapOf<Int, Boolean>()
        val routeData: Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to DestinationRoutes.PROFILE,
            "ports" to listOf("test.flash.tx"),
        )
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { currentTick }
        override fun readData(relative: String): Map<String, Any?> {
            require(relative == "routes.yaml")
            return routeData
        }
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

    private class TestRam : DmaMemory {
        val bytes = ByteArray(0x40000) { index -> ((index * 37 + 11) and 0xff).toByte() }
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0x100000L && size > 0L && address <= 0x140000L - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val count = destination.remaining()
            check(address, count.toLong(), false)
            destination.put(bytes, (address - 0x100000L).toInt(), count)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val count = source.remaining()
            check(address, count.toLong(), true)
            source.get(bytes, (address - 0x100000L).toInt(), count)
        }
    }

    private class TestFlashSource(private val context: TestContext) : DmaSourcePeer {
        val baseAddress = 0xf0000000L
        val payload = ByteArray(256) { index -> (0xa0 + (index and 0x3f)).toByte() }
        var readyAt: Long? = null
        var activeOwner: Int? = null
        var consumedCount = 0
        override fun overlaps(address: Long, size: Long): Boolean =
            size > 0L && address < baseAddress + payload.size && baseAddress < address + size
        override fun check(address: Long, size: Long) {
            require(address >= baseAddress && size > 0L && address + size <= baseAddress + payload.size)
        }
        override fun acceptedAt(): Long? = readyAt?.takeIf { ready -> context.currentTick >= ready }
        override fun claim(channel: Int, address: Long, size: Long): Long? {
            check(address, size)
            val accepted = acceptedAt() ?: return null
            check(activeOwner == null || activeOwner == channel)
            activeOwner = channel
            return accepted
        }
        override fun release(channel: Int) {
            if (activeOwner == channel) activeOwner = null
        }
        override fun read(channel: Int, address: Long, destination: ByteBuffer) {
            check(activeOwner == channel)
            val count = destination.remaining()
            destination.put(payload, (address - baseAddress).toInt(), count)
        }
        override fun consume(channel: Int) {
            check(activeOwner == channel)
            activeOwner = null
            consumedCount++
        }
    }

    private class TestDestinationSink(
        private val context: TestContext,
        val endpointAddress: Long,
        val expectedRequest: Int,
    ) : RangedDmaDestinationPeer {
        val staged = ByteArray(64)
        var readyAt: Long? = 0L
        var activeOwner: Int? = null
        var receivedBytes = 0
        var finishCount = 0
        override fun destinationRanges(): List<AddressWindow> =
            listOf(AddressWindow("test.endpoint", endpointAddress, 4L))
        override fun matches(address: Long): Boolean = address == endpointAddress
        override fun check(address: Long, size: Long, width: Int, request: Int) {
            require(matches(address) && size == 64L && width == 4 && request == expectedRequest)
        }
        override fun acceptedAt(): Long? = readyAt?.takeIf { ready -> context.currentTick >= ready }
        override fun claim(channel: Int, address: Long, size: Long, width: Int, request: Int): Long? {
            check(address, size, width, request)
            val accepted = acceptedAt() ?: return null
            check(activeOwner == null || activeOwner == channel)
            activeOwner = channel
            return accepted
        }
        override fun release(channel: Int) {
            if (activeOwner == channel) {
                activeOwner = null
                receivedBytes = 0
            }
        }
        override fun write(channel: Int, address: Long, offset: Long, source: ByteBuffer) {
            check(activeOwner == channel && address == endpointAddress && offset == receivedBytes.toLong())
            val count = source.remaining()
            source.get(staged, receivedBytes, count)
            receivedBytes += count
        }
        override fun readBack(channel: Int, address: Long, offset: Long, destination: ByteBuffer) {
            check(activeOwner == channel)
            destination.put(staged, offset.toInt(), destination.remaining())
        }
        override fun finish(channel: Int, completedAt: Long, observedAt: Long) {
            check(activeOwner == channel && receivedBytes == 64)
            activeOwner = null
            receivedBytes = 0
            finishCount++
        }
    }

    private class Fixture(spi2Enabled: Boolean = false, spi0Enabled: Boolean = false) {
        val context = TestContext()
        val ram = TestRam()
        val flashSource = TestFlashSource(context)
        val serialSink = TestDestinationSink(context, 0xfff30024L, 21)
        val flashSink = TestDestinationSink(context, 0xf3dc0000L, 43)
        val component: StatefulComponent
        val registers: ScheduledMmioDevice
        val mux: MmioDevice
        val requests: MmioDevice

        init {
            context.claims["cpu.memory"] = ram
            context.claims["test.flash.source"] = flashSource
            context.claims["test.serial"] = serialSink
            context.claims["test.flash.tx"] = flashSink
            context.claims["test.irq"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {
                    context.irqLines[irq] = high
                }
            }
            val factory = DmaFactory()
            val config = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
                "softwareProfile" to "software-request44-experimental-v1",
                "transmitPeerPort" to "test.serial",
                "destinationPeersFile" to "routes.yaml",
                "flashPeerPort" to "test.flash.source",
                "interruptPort" to "test.irq",
                "irqBase" to 207L,
                "spi0DmaProfile" to if (spi0Enabled) Spi0Dma.PROFILE else "",
                "spi2DmaProfile" to if (spi2Enabled) Spi2Dma.PROFILE else "",
            )
            component = factory.create(context, config) as StatefulComponent
            component.start()
            registers = context.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
            mux = checkNotNull(context.ports.find("soc-dma.mux", MmioDevice::class.java))
            requests = checkNotNull(context.ports.find("soc-dma.requests", MmioDevice::class.java))
        }

        fun routeMux(channelIndex: Int, route: Int) {
            val shift = (channelIndex % 2) * 8
            val strobe = if (shift == 0) 0x10000L else 0x1000000L
            mux.write((channelIndex / 2) * 4L, 4, strobe or (route.toLong() shl shift))
        }

        fun writeChannel(channelIndex: Int, registerOffset: Int, value: Long) {
            registers.write(channelIndex * 4096L + registerOffset, 4, value)
        }

        fun advanceTo(tick: Long) {
            context.currentTick = tick
            registers.beforeInstruction(tick)
        }
    }

    private class ByteFifo : SpiDmaFifo {
        var received = byteArrayOf()
        var sent = byteArrayOf()
        override fun readableBytes() = received.size
        override fun writableBytes() = 8
        override fun read(destination: ByteBuffer) {
            require(destination.remaining() == received.size)
            destination.put(received)
            received = byteArrayOf()
        }
        override fun write(source: ByteBuffer) {
            sent = ByteArray(source.remaining()).also { source.get(it) }
        }
    }

    private fun Fixture.setupSpi(direction: Spi2Dma) {
        val index = direction.channel
        routeMux(index, direction.request.toInt())
        writeChannel(index, 0x10, 3)
        writeChannel(index, 0x14, if (direction == Spi2Dma.RX) Spi2Dma.DATA else 0x100100)
        writeChannel(index, 0x18, if (direction == Spi2Dma.TX) Spi2Dma.DATA else 0x100200)
        writeChannel(index, 0x1c, if (direction == Spi2Dma.RX) 4 else 0)
        writeChannel(index, 0x20, if (direction == Spi2Dma.TX) 4 else 0)
    }

    @Test
    fun spi2ArmedRestoreWaitsForFourBytesAndTransfersEachDirectionOnce() {
        val legacy = Fixture()
        val fixture = Fixture(true)
        val fifo = ByteFifo()
        fixture.context.claims[Spi2Dma.PORT] = fifo
        fixture.component.restoreSnapshot(legacy.component.saveSnapshot())
        fixture.setupSpi(Spi2Dma.RX)
        fixture.writeChannel(5, 0x28, 0x10201011)
        val armed = simulateJsonRoundTrip(fixture.component.saveSnapshot())
        assertFails { legacy.component.restoreSnapshot(armed) }
        assertFails { fixture.routeMux(5, 5) }
        fixture.writeChannel(5, 0x28, 0)
        fixture.component.restoreSnapshot(armed)
        fixture.setupSpi(Spi2Dma.TX)
        fixture.writeChannel(6, 0x28, 0x10201011)
        val tx = fixture.ram.bytes.copyOfRange(0x100, 0x104)
        val original = fixture.ram.bytes.copyOfRange(0x200, 0x204)
        fifo.received = byteArrayOf(1, 2, 3)
        fixture.advanceTo(17)
        assertContentEquals(tx, fifo.sent)
        assertContentEquals(original, fixture.ram.bytes.copyOfRange(0x200, 0x204))
        assertEquals(false, fixture.context.irqLines[212])
        assertEquals(true, fixture.context.irqLines[213])
        val reply = byteArrayOf(1, 2, 3, 0x7e)
        fifo.received = reply
        fixture.advanceTo(20)
        fixture.component.restoreSnapshot(simulateJsonRoundTrip(fixture.component.saveSnapshot()))
        fixture.advanceTo(37)
        fixture.advanceTo(38)
        assertContentEquals(reply, fixture.ram.bytes.copyOfRange(0x200, 0x204))
        assertEquals(true, fixture.context.irqLines[212])
        val done = simulateJsonRoundTrip(fixture.component.saveSnapshot())
        assertEquals(2, done["eventCount"])
        fixture.component.restoreSnapshot(done)
        assertEquals(done, simulateJsonRoundTrip(fixture.component.saveSnapshot()))
    }

    private fun Fixture.setupSpi0(direction: Spi0Dma) {
        val index = direction.channel
        routeMux(index, direction.request.toInt())
        writeChannel(index, 0x10, 3)
        writeChannel(index, 0x14, if (direction == Spi0Dma.RX) Spi0Dma.DATA else 0x100100)
        writeChannel(index, 0x18, if (direction == Spi0Dma.TX) Spi0Dma.DATA else 0x100200)
        writeChannel(index, 0x1c, if (direction == Spi0Dma.RX) 4 else 0)
        writeChannel(index, 0x20, if (direction == Spi0Dma.TX) 4 else 0)
    }

    @Test
    fun spi0ArmedRestoreWaitsForFourBytesTransfersEachDirectionAndPromotesSnapshotMonotonically() {
        val legacy = Fixture()
        val fixture = Fixture(spi0Enabled = true)
        val fifo = ByteFifo()
        fixture.context.claims[Spi0Dma.PORT] = fifo
        fixture.component.restoreSnapshot(legacy.component.saveSnapshot())
        fixture.setupSpi0(Spi0Dma.RX)
        fixture.writeChannel(2, 0x28, 0x10201011)
        val armed = simulateJsonRoundTrip(fixture.component.saveSnapshot())
        assertFails { legacy.component.restoreSnapshot(armed) }
        assertFails { fixture.routeMux(2, 1) }
        fixture.writeChannel(2, 0x28, 0)
        fixture.component.restoreSnapshot(armed)
        fixture.setupSpi0(Spi0Dma.TX)
        fixture.writeChannel(3, 0x28, 0x10201011)
        val tx = fixture.ram.bytes.copyOfRange(0x100, 0x104)
        val original = fixture.ram.bytes.copyOfRange(0x200, 0x204)
        fifo.received = byteArrayOf(1, 2, 3)
        fixture.advanceTo(17)
        assertContentEquals(tx, fifo.sent)
        assertContentEquals(original, fixture.ram.bytes.copyOfRange(0x200, 0x204))
        assertEquals(false, fixture.context.irqLines[209])
        assertEquals(true, fixture.context.irqLines[210])
        val reply = byteArrayOf(0x08, 0x00, 0x84.toByte(), 0x1a)
        fifo.received = reply
        fixture.advanceTo(20)
        fixture.component.restoreSnapshot(simulateJsonRoundTrip(fixture.component.saveSnapshot()))
        fixture.advanceTo(37)
        fixture.advanceTo(38)
        assertContentEquals(reply, fixture.ram.bytes.copyOfRange(0x200, 0x204))
        assertEquals(true, fixture.context.irqLines[209])
        val done = simulateJsonRoundTrip(fixture.component.saveSnapshot())
        assertEquals(2, done["eventCount"])
        fixture.component.restoreSnapshot(done)
        assertEquals(done, simulateJsonRoundTrip(fixture.component.saveSnapshot()))
    }

    @Test
    fun spi0StreamingBulkTransferHandles131ByteFramesWithoutRxFifoOverflow() {
        val fixture = Fixture(spi0Enabled = true)
        val rxQueue = java.util.ArrayDeque<Byte>()
        val txLog = java.io.ByteArrayOutputStream()
        val streamingFifo = object : SpiDmaFifo {
            override fun readableBytes(): Int = rxQueue.size
            override fun writableBytes(): Int = 8 - rxQueue.size
            override fun read(destination: ByteBuffer) {
                while (destination.hasRemaining() && rxQueue.isNotEmpty()) {
                    destination.put(rxQueue.removeFirst())
                }
            }
            override fun write(source: ByteBuffer) {
                val chunk = ByteArray(source.remaining())
                source.get(chunk)
                txLog.write(chunk)
                // Full-duplex SPI loopback: every TX byte clocks out one RX byte (inverted)
                for (b in chunk) rxQueue.addLast((b.toInt() xor 0x5A).toByte())
            }
        }
        fixture.context.claims[Spi0Dma.PORT] = streamingFifo

        val len = 131
        fixture.routeMux(2, Spi0Dma.RX.request.toInt())
        fixture.writeChannel(2, 0x10, (len - 1).toLong())
        fixture.writeChannel(2, 0x14, Spi0Dma.DATA)
        fixture.writeChannel(2, 0x18, 0x100400L)
        fixture.writeChannel(2, 0x1c, 4L)
        fixture.writeChannel(2, 0x20, 0L)
        fixture.writeChannel(2, 0x28, 0x10201011L)

        fixture.routeMux(3, Spi0Dma.TX.request.toInt())
        fixture.writeChannel(3, 0x10, (len - 1).toLong())
        fixture.writeChannel(3, 0x14, 0x100100L)
        fixture.writeChannel(3, 0x18, Spi0Dma.DATA)
        fixture.writeChannel(3, 0x1c, 0L)
        fixture.writeChannel(3, 0x20, 4L)
        fixture.writeChannel(3, 0x28, 0x10201011L)

        var tick = 0L
        var steps = 0
        while ((fixture.context.irqLines[209] != true || fixture.context.irqLines[210] != true) && steps < 200) {
            tick += 17L
            fixture.advanceTo(tick)
            assertTrue(rxQueue.size <= 8, "RX FIFO overflowed: ${rxQueue.size}")
            steps++
        }
        assertEquals(true, fixture.context.irqLines[210], "TX DMA did not complete")
        assertEquals(true, fixture.context.irqLines[209], "RX DMA did not complete")
        val expectedTx = fixture.ram.bytes.copyOfRange(0x100, 0x100 + len)
        assertContentEquals(expectedTx, txLog.toByteArray())
        val expectedRx = ByteArray(len) { i -> (expectedTx[i].toInt() xor 0x5A).toByte() }
        assertContentEquals(expectedRx, fixture.ram.bytes.copyOfRange(0x400, 0x400 + len))
        fixture.component.close()
    }

    @Test
    fun spi2RequiresSelectedGeometryMuxPeerAndFiniteRamBeforeArming() {
        val legacy = Fixture()
        legacy.setupSpi(Spi2Dma.RX)
        assertFails { legacy.writeChannel(5, 0x28, 0x10201011) }
        val fixture = Fixture(true)
        fixture.setupSpi(Spi2Dma.RX)
        assertFails { fixture.writeChannel(5, 0x28, 0x10201011) }
        fixture.context.claims[Spi2Dma.PORT] = ByteFifo()
        for ((offset, invalid) in listOf(0x10 to 4L, 0x18 to 0x13fffeL, 0x1c to 0x20004L, 0x20 to 4L)) {
            fixture.setupSpi(Spi2Dma.RX)
            fixture.writeChannel(5, offset, invalid)
            val before = fixture.component.saveSnapshot()
            assertFails { fixture.writeChannel(5, 0x28, 0x10201011) }
            assertEquals(before, fixture.component.saveSnapshot())
        }
        fixture.setupSpi(Spi2Dma.TX)
        fixture.routeMux(6, 4)
        assertFails { fixture.writeChannel(6, 0x28, 0x10201011) }
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
    fun t1ToT2EquivalenceAcrossStandaloneSoftware44FlashAndDestinationPeers() {
        val primary = Fixture()
        val restored = Fixture()

        primary.context.currentTick = 5L
        primary.requests.write(0L, 4, 0x12345678L)
        primary.requests.write(44L, 4, 0xdeadbeefL)
        primary.routeMux(1, 21)
        primary.routeMux(2, 44)
        primary.context.currentTick = 12L
        primary.routeMux(3, 43)
        primary.routeMux(5, 42)

        listOf(
            0x10 to 63L,
            0x14 to 0x100000L,
            0x18 to 0x110000L,
            0x1c to 0x20000L,
            0x20 to 0x20000L,
            0x24 to 0L,
        ).forEach { (offset, value) -> primary.writeChannel(0, offset, value) }
        primary.writeChannel(0, 0x28, 0x10000001L)
        primary.advanceTo(32L)
        assertEquals(true, primary.context.irqLines[207])

        listOf(
            0x10 to 63L,
            0x14 to 0x100100L,
            0x18 to 0xfff30024L,
            0x1c to 0x20000L,
            0x20 to 0x20004L,
            0x24 to 0L,
        ).forEach { (offset, value) -> primary.writeChannel(1, offset, value) }
        primary.writeChannel(1, 0x28, 0x10201011L)
        primary.advanceTo(60L)
        assertEquals(1, primary.serialSink.finishCount)

        listOf(
            0x10 to 31L,
            0x14 to 0x100200L,
            0x18 to 0x112000L,
            0x1c to 0x30f04L,
            0x20 to 0x30f00L,
            0x24 to 0L,
        ).forEach { (offset, value) -> primary.writeChannel(2, offset, value) }
        primary.writeChannel(2, 0x28, 0x11100001L)
        primary.advanceTo(85L)
        primary.writeChannel(2, 0x30, 1L)
        primary.advanceTo(86L)
        assertEquals(false, primary.context.irqLines[209])

        primary.flashSink.readyAt = null
        listOf(
            0x10 to 63L,
            0x14 to 0x100300L,
            0x18 to 0xf3dc0000L,
            0x1c to 0x20000L,
            0x20 to 0x20004L,
            0x24 to 0L,
        ).forEach { (offset, value) -> primary.writeChannel(3, offset, value) }
        primary.context.currentTick = 90L
        primary.writeChannel(3, 0x28, 0x10201011L)

        listOf(
            0x10 to 63L,
            0x14 to 0x100400L,
            0x18 to 0x114000L,
            0x1c to 0x20000L,
            0x20 to 0x20000L,
            0x24 to 0L,
        ).forEach { (offset, value) -> primary.writeChannel(4, offset, value) }
        primary.context.currentTick = 95L
        primary.writeChannel(4, 0x28, 0x10000001L)

        val snapshotT1 = simulateJsonRoundTrip(primary.component.saveSnapshot())
        primary.ram.bytes.copyInto(restored.ram.bytes)
        primary.serialSink.staged.copyInto(restored.serialSink.staged)
        restored.serialSink.finishCount = primary.serialSink.finishCount
        restored.flashSink.readyAt = null
        restored.context.currentTick = primary.context.currentTick
        restored.component.restoreSnapshot(snapshotT1)

        assertEquals(primary.component.state(), restored.component.state())
        assertEquals(primary.registers.nextDeadline(), restored.registers.nextDeadline())
        assertEquals(primary.context.irqLines, restored.context.irqLines)

        for (instance in listOf(primary, restored)) {
            instance.flashSink.readyAt = 105L
            instance.advanceTo(130L)
            instance.flashSource.readyAt = 132L
            listOf(
                0x10 to 63L,
                0x14 to 0xf0000000L,
                0x18 to 0x116000L,
                0x1c to 0x20f04L,
                0x20 to 0x20f00L,
                0x24 to 0L,
            ).forEach { (offset, value) -> instance.writeChannel(5, offset, value) }
            instance.context.currentTick = 132L
            instance.writeChannel(5, 0x28, 0x10201011L)
            instance.advanceTo(160L)
            instance.writeChannel(0, 0x30, 1L)
            instance.advanceTo(161L)
        }

        assertEquals(primary.component.state(), restored.component.state())
        assertContentEquals(primary.ram.bytes, restored.ram.bytes)
        assertContentEquals(primary.flashSink.staged, restored.flashSink.staged)
        assertEquals(primary.flashSink.finishCount, restored.flashSink.finishCount)
        assertEquals(primary.flashSource.consumedCount, restored.flashSource.consumedCount)
        assertEquals(primary.context.irqLines, restored.context.irqLines)
        primary.component.close()
        restored.component.close()
    }

    @Test
    fun truncatedEventRingRoundTripsAndMalformedSnapshotsRejectAtomically() {
        val source = Fixture()
        val target = Fixture()

        repeat(260) { step ->
            val tickBase = step * 30L
            source.context.currentTick = tickBase
            listOf(
                0x10 to 3L,
                0x14 to 0x100000L + (step % 32) * 4L,
                0x18 to 0x120000L + (step % 32) * 4L,
                0x1c to 0x20000L,
                0x20 to 0x20000L,
                0x24 to 0L,
            ).forEach { (offset, value) -> source.writeChannel(0, offset, value) }
            source.writeChannel(0, 0x28, 0x10000001L)
            source.advanceTo(tickBase + 20L)
        }
        assertEquals(true, source.component.state()["eventLogTruncated"])

        val validSnapshot = simulateJsonRoundTrip(source.component.saveSnapshot())
        target.component.restoreSnapshot(validSnapshot)
        assertEquals(source.component.state(), target.component.state())

        val baselineState = target.component.state()
        val baselineSnapshot = target.component.saveSnapshot()

        @Suppress("UNCHECKED_CAST")
        val validChannels = validSnapshot["channels"] as List<Map<String, Any?>>
        val corruptedReservedReg = validChannels.mapIndexed { index, channel ->
            if (index != 0) channel else {
                val updatedRegs = (channel["registers"] as List<*>).toMutableList()
                updatedRegs[1] = 4
                channel + ("registers" to updatedRegs)
            }
        }
        val idleWithDeadline = validChannels.mapIndexed { index, channel ->
            if (index != 0) channel else channel + ("deadline" to 500)
        }
        @Suppress("UNCHECKED_CAST")
        val validScalars = validSnapshot["scalars"] as Map<String, Any?>

        val invalidCandidates = listOf(
            validSnapshot + ("schema" to 2),
            validSnapshot + ("componentId" to "soc-flash"),
            validSnapshot + ("unexpected" to true),
            validSnapshot - "muxChanged",
            validSnapshot + ("scalars" to (validScalars + ("irqBase" to 220))),
            validSnapshot + ("eventCount" to 1.5),
            validSnapshot + ("eventCount" to 10),
            validSnapshot + ("channels" to corruptedReservedReg),
            validSnapshot + ("channels" to idleWithDeadline),
        )
        for (badSnapshot in invalidCandidates) {
            assertFails { target.component.restoreSnapshot(badSnapshot) }
            assertEquals(baselineState, target.component.state())
            assertEquals(baselineSnapshot, target.component.saveSnapshot())
        }

        target.component.close()
        assertFails { target.component.saveSnapshot() }
        assertFails { target.component.restoreSnapshot(validSnapshot) }
        source.component.close()
    }
}
