package com.github.nyabanban.fuji.emulator.components.serialengine

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SerialEngineSnapshotTest {
    private class TestContext(private val inputDocument: Map<String, Any?> = emptyMap()) : ComponentContext {
        var currentTick = 0L
        var currentCore = 0
        var currentPc = 0x1000L
        val publishedEvents = ArrayList<ComponentEvent>()
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-serial-engine-snapshot")
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
            override fun publish(event: ComponentEvent) {
                publishedEvents.add(event)
            }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> {
            assertEquals("serial.yaml", relative)
            return inputDocument
        }

        fun wireRuntimePorts() {
            ports.register("test", "cpu.execution-location", ExecutionLocationSource::class.java, ExecutionLocationSource {
                ExecutionLocation(currentCore, currentPc)
            })
            ports.register("test", "board.layout", AddressLayout::class.java, object : AddressLayout {
                override fun window(port: String): AddressWindow {
                    val channel = port.substringAfterLast('-').toInt()
                    val baseAddress = 0xfff30004L + (if (channel > 4) 0x1000L else 0L) + (if (channel > 4) channel - 5 else channel) * 0x20L
                    return AddressWindow(port, baseAddress, 4L)
                }
            })
        }
    }

    private fun transmitDocument(): Map<String, Any?> = mapOf(
        "schema" to 1,
        "profile" to SerialEngineFactory.TRANSMIT_PROFILE,
        "firmwareSha256" to SerialEngineFactory.FIRMWARE,
        "initialWords" to mapOf("0" to 0x55aa0000L, "1" to null),
        "statusPlans" to mapOf(
            "1" to listOf(listOf(0x10001L, 0x10001L), listOf(0L)),
            "2" to listOf(listOf(0xa55a0001L), listOf(0x10000L)),
        ),
        "transmitPlans" to mapOf(
            "1" to listOf(
                mapOf("byteCount" to 65536, "acceptDelayTicks" to 0L),
                mapOf("byteCount" to 64, "acceptDelayTicks" to 5L),
            ),
            "2" to listOf(
                mapOf("byteCount" to 32, "acceptDelayTicks" to 4L),
            ),
        ),
    )

    private fun createEngine(
        context: TestContext,
        profile: String = SerialEngineFactory.TRANSMIT_PROFILE,
        captureEvents: Boolean = true,
    ): StatefulComponent {
        val factory = SerialEngineFactory()
        val settings = if (profile == "disabled") {
            factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        } else {
            mapOf("profile" to profile, "inputFile" to "serial.yaml", "captureEvents" to captureEvents)
        }
        val component = factory.create(context, settings)
        component.start()
        context.wireRuntimePorts()
        return assertIs<StatefulComponent>(component)
    }

    private fun roundTripThroughJackson(snapshot: Map<String, Any?>): Map<String, Any?> {
        val serialized = Formats.jsonText(snapshot)
        @Suppress("UNCHECKED_CAST")
        return Formats.json.readValue(serialized, Map::class.java) as Map<String, Any?>
    }

    @Test
    fun transmitPlanCanAppendOnlyAfterACompletedIdlePrefix() {
        val first = mapOf("byteCount" to 8, "acceptDelayTicks" to 0L)
        val second = mapOf("byteCount" to 4, "acceptDelayTicks" to 3L)
        fun context(vararg rows: Map<String, Number>) = TestContext(transmitDocument() +
            ("transmitPlans" to mapOf("1" to rows.toList())))
        val before = context(first)
        val source = createEngine(before)
        val after = context(first, second)
        val target = createEngine(after)
        val control = checkNotNull(before.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val peer = checkNotNull(before.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        val address = 0xfff30024L
        assertFailsWith<IllegalArgumentException> { target.restoreSnapshot(source.saveSnapshot()) }
        control.write(0, 4, 0L)
        peer.claim(1, address, 8, 4, 21)
        assertFailsWith<IllegalArgumentException> { target.restoreSnapshot(source.saveSnapshot()) }
        peer.write(1, address, 0L, ByteBuffer.wrap(ByteArray(8)))
        peer.finish(1, 0L, 0L)
        val completed = roundTripThroughJackson(source.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { createEngine(context(second, first)).restoreSnapshot(completed) }
        target.restoreSnapshot(completed)
        fun capture(context: TestContext) =
            checkNotNull(context.ports.find(SerialEngineFactory.capturePort(1), TransferCapturePort::class.java)).capture()
        assertEquals(assertNotNull(capture(before)), capture(after))
        val resumed = checkNotNull(after.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        checkNotNull(after.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java)).write(0, 4, 0)
        assertFailsWith<IllegalArgumentException> { resumed.claim(1, address, 8, 4, 21) }
        assertEquals(null, resumed.claim(1, address, 4, 4, 21))
        after.currentTick = 3
        assertEquals(3L, resumed.claim(1, address, 4, 4, 21))
        resumed.write(1, address, 0, ByteBuffer.wrap(byteArrayOf(1,2,3,4)))
        resumed.finish(1, 3, 3)
        val appended = roundTripThroughJackson(target.saveSnapshot())
        val third = mapOf("byteCount" to 12, "acceptDelayTicks" to 0L)
        val next = context(first, second, third).also { it.currentTick = 3 }
        val nextEngine = createEngine(next)
        val changed = context(first, second + ("acceptDelayTicks" to 0L), third).also { it.currentTick = 3 }
        assertFailsWith<IllegalArgumentException> { createEngine(changed).restoreSnapshot(appended) }
        nextEngine.restoreSnapshot(appended)
        assertEquals(capture(after), capture(next))
        val thirdPeer = checkNotNull(next.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        val thirdControl = checkNotNull(next.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        thirdControl.write(0, 4, 0)
        assertEquals(3L, thirdPeer.claim(1, address, 12, 4, 21))
        thirdPeer.write(1, address, 0, ByteBuffer.wrap(ByteArray(12) { it.toByte() }))
        thirdPeer.finish(1, 3, 3)
        assertEquals(3L, capture(next)?.sequence)
        assertFailsWith<IllegalStateException> { thirdControl.write(0, 4, 0) }
    }

    @Test
    fun channelOneExplicitRestoreSampleRequiresConsumedPrefixAndIsAppliedOnce() {
        val original = transmitDocument() + ("statusPlans" to mapOf("1" to listOf(listOf(0L)), "2" to listOf(listOf(1L))))
        val selected = original + ("resumeChannel1StatusSample" to 0L)
        val sourceContext = TestContext(original)
        val source = createEngine(sourceContext)
        val targetContext = TestContext(selected)
        val target = createEngine(targetContext)
        fun statusPort(context: TestContext) = checkNotNull(context.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java))
        assertFailsWith<IllegalArgumentException> { target.restoreSnapshot(source.saveSnapshot()) }
        assertEquals(0L, statusPort(sourceContext).read(0, 4))
        assertFailsWith<IllegalArgumentException> { target.restoreSnapshot(source.saveSnapshot()) }
        statusPort(sourceContext).write(0, 4, 0)
        val prefix = roundTripThroughJackson(source.saveSnapshot())
        assertFailsWith<IllegalStateException> { statusPort(sourceContext).read(0, 4) }
        target.restoreSnapshot(prefix)
        assertEquals(0L, statusPort(targetContext).read(0, 4))
        val pending = roundTripThroughJackson(target.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { source.restoreSnapshot(pending) }
        target.restoreSnapshot(pending)
        assertFailsWith<IllegalArgumentException> { statusPort(targetContext).write(0, 4, 1) }
        statusPort(targetContext).write(0, 4, 0)
        target.restoreSnapshot(roundTripThroughJackson(target.saveSnapshot()))
        assertFailsWith<IllegalStateException> { statusPort(targetContext).read(0, 4) }
        assertEquals(source.state()["transmitStates"], target.state()["transmitStates"])
        assertFailsWith<IllegalArgumentException> { createEngine(TestContext(original + ("resumeChannel1StatusSample" to 1L))) }
    }

    @Test
    fun explicitStatusListResumesLegacyAndListPrefixesWithExactWordsAcrossRingWrap() {
        val original = transmitDocument() + ("statusPlans" to mapOf("1" to listOf(listOf(0L)), "2" to listOf(listOf(1L))))
        val legacy = TestContext(original + ("resumeChannel1StatusSample" to 0))
        val baseline = TestContext(original)
        fun port(context: TestContext) = checkNotNull(context.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java))
        fun transact(context: TestContext, expected: Long) {
            assertEquals(expected, port(context).read(0, 4))
            port(context).write(0, 4, expected and 0x10001)
        }
        val baseEngine = createEngine(baseline)
        transact(baseline, 0)
        val first = roundTripThroughJackson(baseEngine.saveSnapshot())
        val legacyEngine = createEngine(legacy)
        legacyEngine.restoreSnapshot(first)
        transact(legacy, 0)
        val second = roundTripThroughJackson(legacyEngine.saveSnapshot())

        val words = listOf(0L) + (2..63).map { 0xa5010000L + it }
        val listKey = "resumeChannel1StatusSamples"
        fun context(samples: List<Long>) = TestContext(original + (listKey to samples))
        val selected = context(words)
        val engine = createEngine(selected)
        engine.restoreSnapshot(first)
        assertEquals(listOf(listOf(0L)) + words.map { listOf(it) }, (engine.state()["statusPlans"] as Map<*,*>)["1"])
        engine.restoreSnapshot(second)
        assertFailsWith<IllegalArgumentException> { createEngine(context(words.reversed())).restoreSnapshot(second) }
        for (word in words.drop(1).dropLast(1)) transact(selected, word)
        assertEquals(words.last(), port(selected).read(0, 4))
        val pending = roundTripThroughJackson(engine.saveSnapshot())
        val resumed = context(words)
        val resumedEngine = createEngine(resumed)
        resumedEngine.restoreSnapshot(pending)
        assertEquals(engine.state(), resumedEngine.state())
        port(resumed).write(0, 4, words.last() and 0x10001)
        assertFailsWith<IllegalStateException> { port(resumed).read(0, 4) }
        val shorter = createEngine(context(words.dropLast(1)))
        assertFailsWith<IllegalArgumentException> { shorter.restoreSnapshot(pending) }
        val unfinished = context(words.take(2))
        val unfinishedEngine = createEngine(unfinished)
        unfinishedEngine.restoreSnapshot(second)
        assertFailsWith<IllegalArgumentException> { engine.restoreSnapshot(unfinishedEngine.saveSnapshot()) }
        for (invalid in listOf(emptyList<Int>(), List(64) { 0 }, listOf(null), listOf(4294967296L))) {
            assertFailsWith<IllegalArgumentException> { createEngine(TestContext(original + (listKey to invalid))) }
        }
        assertFailsWith<IllegalArgumentException> {
            createEngine(TestContext(original + mapOf(listKey to listOf(0), "resumeChannel1StatusSample" to 0)))
        }
    }

    @Test
    fun transmitCaptureStatusCursorsWrappedLogsAndInFlightDmaMatchDirectContinuationAfterJsonRestore() {
        val directContext = TestContext(transmitDocument())
        val directEngine = createEngine(directContext, captureEvents = true)

        val directShared0 = checkNotNull(directContext.ports.find(SerialEngineFactory.port(0), MmioDevice::class.java))
        val directStatus1 = checkNotNull(directContext.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java))
        val directStatus2 = checkNotNull(directContext.ports.find(SerialEngineFactory.statusPort(2), MmioDevice::class.java))
        val directControl1 = assertIs<ScheduledMmioDevice>(directContext.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val directControl2 = assertIs<ScheduledMmioDevice>(directContext.ports.find(SerialEngineFactory.controlPort(2), MmioDevice::class.java))
        val directTx1 = checkNotNull(directContext.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        val directTx2 = checkNotNull(directContext.ports.find(SerialEngineFactory.txPort(2), DmaDestinationPeer::class.java))
        val directCapture1 = checkNotNull(directContext.ports.find(SerialEngineFactory.capturePort(1), TransferCapturePort::class.java))
        val directCapture2 = checkNotNull(directContext.ports.find(SerialEngineFactory.capturePort(2), TransferCapturePort::class.java))

        for (step in 0 until 70) {
            directContext.currentTick = step.toLong()
            directContext.currentCore = step % 4
            directContext.currentPc = 0x2000L + step * 4L
            if (step % 2 == 0) {
                directShared0.write(0L, 4, 0x55aa0000L or (step and 0xff).toLong())
            } else {
                directShared0.read(0L, 4)
            }
        }

        directContext.currentTick = 100L
        assertEquals(0x10001L, directStatus1.read(0L, 4))
        assertEquals(0xa55a0001L, directStatus2.read(0L, 4))
        directStatus2.write(0L, 4, 0x00001L)

        directContext.currentTick = 110L
        directControl1.write(0L, 4, 0L)
        assertEquals(110L, directTx1.claim(3, 0xfff30024L, 65536L, 4, 21))
        val largePayload = ByteArray(65536) { offset -> ((offset * 13 + 7) and 0xff).toByte() }
        directTx1.write(3, 0xfff30024L, 0L, ByteBuffer.wrap(largePayload))
        directContext.currentTick = 120L
        directTx1.finish(3, 118L, 120L)

        directContext.currentTick = 125L
        directControl1.write(0L, 4, 0L)
        directContext.currentTick = 130L
        assertEquals(130L, directTx1.claim(5, 0xfff30024L, 64L, 4, 21))
        val secondPayload = ByteArray(64) { offset -> (0xc0 xor offset).toByte() }
        directTx1.write(5, 0xfff30024L, 0L, ByteBuffer.wrap(secondPayload, 0, 28))

        directContext.currentTick = 132L
        directControl2.write(0L, 4, 0L)

        val snapshotAtT1 = roundTripThroughJackson(directEngine.saveSnapshot())

        val restoredContext = TestContext(transmitDocument())
        val restoredEngine = createEngine(restoredContext, captureEvents = true)
        restoredEngine.restoreSnapshot(snapshotAtT1)

        assertEquals(directEngine.state(), restoredEngine.state())

        val restoredCapture1 = checkNotNull(restoredContext.ports.find(SerialEngineFactory.capturePort(1), TransferCapturePort::class.java))
        val restoredCapture2 = checkNotNull(restoredContext.ports.find(SerialEngineFactory.capturePort(2), TransferCapturePort::class.java))
        val capturedMeta = assertNotNull(restoredCapture1.capture())
        assertEquals(1L, capturedMeta.sequence)
        assertEquals(65536, capturedMeta.size)
        val readCaptureBuffer = ByteBuffer.allocate(65536)
        restoredCapture1.read(1L, 0L, readCaptureBuffer)
        assertContentEquals(largePayload, readCaptureBuffer.array())
        assertFails { restoredCapture1.read(2L, 0L, ByteBuffer.allocate(4)) }

        val restoredControl2 = assertIs<ScheduledMmioDevice>(restoredContext.ports.find(SerialEngineFactory.controlPort(2), MmioDevice::class.java))
        restoredContext.currentTick = 132L
        directContext.currentTick = 132L
        assertEquals(directControl2.nextDeadline(), restoredControl2.nextDeadline())

        val restoredTx1 = checkNotNull(restoredContext.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        val partialReadback = ByteBuffer.allocate(28)
        restoredTx1.readBack(5, 0xfff30024L, 0L, partialReadback)
        assertContentEquals(secondPayload.copyOfRange(0, 28), partialReadback.array())

        fun advanceToT2(
            context: TestContext,
            shared0: MmioDevice,
            status1: MmioDevice,
            status2: MmioDevice,
            tx1: DmaDestinationPeer,
            tx2: DmaDestinationPeer,
            capture1: TransferCapturePort,
            capture2: TransferCapturePort,
        ): List<Any?> {
            context.currentTick = 140L
            context.currentCore = 1
            context.currentPc = 0x4400L
            val secondReadSample = status1.read(0L, 4)
            status1.write(0L, 4, 0x10001L)
            val nextTxnSample = status1.read(0L, 4)
            status1.write(0L, 4, 0L)

            val ch2Sample = status2.read(0L, 4)
            status2.write(0L, 4, 0x10000L)

            context.currentTick = 145L
            tx1.write(5, 0xfff30024L, 28L, ByteBuffer.wrap(secondPayload, 28, 36))
            context.currentTick = 150L
            tx1.finish(5, 148L, 150L)

            context.currentTick = 155L
            val claimedAt = tx2.claim(6, 0xfff30044L, 32L, 4, 22)
            val ch2Bytes = ByteArray(32) { offset -> (offset * 5 + 3).toByte() }
            tx2.write(6, 0xfff30044L, 0L, ByteBuffer.wrap(ch2Bytes))
            context.currentTick = 160L
            tx2.finish(6, 158L, 160L)

            context.currentTick = 162L
            shared0.write(0L, 4, 0x55aa00efL)

            val finalCap1 = ByteBuffer.allocate(64)
            capture1.read(2L, 0L, finalCap1)
            val finalCap2 = ByteBuffer.allocate(32)
            capture2.read(1L, 0L, finalCap2)
            return listOf(
                secondReadSample,
                nextTxnSample,
                ch2Sample,
                claimedAt,
                finalCap1.array().toList(),
                finalCap2.array().toList(),
                shared0.read(0L, 4),
            )
        }

        directContext.publishedEvents.clear()
        restoredContext.publishedEvents.clear()

        val directResults = advanceToT2(
            directContext,
            directShared0,
            directStatus1,
            directStatus2,
            directTx1,
            directTx2,
            directCapture1,
            directCapture2,
        )
        val restoredResults = advanceToT2(
            restoredContext,
            checkNotNull(restoredContext.ports.find(SerialEngineFactory.port(0), MmioDevice::class.java)),
            checkNotNull(restoredContext.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java)),
            checkNotNull(restoredContext.ports.find(SerialEngineFactory.statusPort(2), MmioDevice::class.java)),
            restoredTx1,
            checkNotNull(restoredContext.ports.find(SerialEngineFactory.txPort(2), DmaDestinationPeer::class.java)),
            restoredCapture1,
            restoredCapture2,
        )

        assertEquals(directResults, restoredResults)
        assertEquals(directContext.publishedEvents, restoredContext.publishedEvents)
        assertEquals(directEngine.state(), restoredEngine.state())

        val disabledDirect = createEngine(TestContext(), profile = "disabled")
        val disabledSnapshot = roundTripThroughJackson(disabledDirect.saveSnapshot())
        val disabledRestored = createEngine(TestContext(), profile = "disabled")
        disabledRestored.restoreSnapshot(disabledSnapshot)
        assertEquals(disabledDirect.state(), disabledRestored.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingLiveEngineState() {
        val context = TestContext(transmitDocument())
        val engine = createEngine(context, captureEvents = false)
        val shared0 = checkNotNull(context.ports.find(SerialEngineFactory.port(0), MmioDevice::class.java))
        val control1 = checkNotNull(context.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val tx1 = checkNotNull(context.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))

        context.currentTick = 20L
        shared0.write(0L, 4, 0x55aa0042L)
        control1.write(0L, 4, 0L)
        tx1.claim(2, 0xfff30024L, 65536L, 4, 21)
        tx1.write(2, 0xfff30024L, 0L, ByteBuffer.wrap(ByteArray(65536) { 0x3c }))
        context.currentTick = 25L
        tx1.finish(2, 24L, 25L)

        val baselineState = engine.state()
        val valid = roundTripThroughJackson(engine.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val statusPlans = valid["statusPlans"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val transmitPlans = valid["transmitPlans"] as Map<String, Map<String, Any?>>
        val tx1Map = transmitPlans.getValue("1")
        @Suppress("UNCHECKED_CAST")
        val captureMap = tx1Map["capture"] as Map<String, Any?>

        val invalidCandidates = listOf(
            valid - "clock",
            valid + ("unexpected" to 1),
            valid + ("schema" to 2),
            valid + ("componentId" to "soc-spi"),
            valid + ("captureEvents" to true),
            valid + ("words" to mapOf("0" to 0x55ab0042L, "1" to null)),
            valid + ("words" to mapOf("0" to 0x55aa0042L, "1" to 0L)),
            valid + ("clock" to 10L),
            valid + ("accessCount" to 99L),
            valid + ("statusPlans" to (statusPlans + ("1" to (statusPlans.getValue("1") + ("sampleIndex" to 5))))),
            valid + ("transmitPlans" to (transmitPlans + ("1" to (tx1Map + ("completedCount" to 0L))))),
            valid + ("transmitPlans" to (transmitPlans + ("1" to (tx1Map + ("capture" to (captureMap + ("sha256" to "0".repeat(64)))))))),
            valid + ("transmitPlans" to (transmitPlans + ("1" to (tx1Map + ("capture" to (captureMap + ("payloadChunksBase64" to listOf("AAAA")))))))),
        )

        for (candidate in invalidCandidates) {
            assertFailsWith<IllegalArgumentException> {
                engine.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, engine.state())
        }
    }

    @Test
    fun repeatLastPlanRepeatsStatusAndTransmitPlansAndMonotonicallyUpgrades() {
        val finiteCtx = TestContext(transmitDocument())
        val finite = SerialEngineFactory().create(
            finiteCtx,
            mapOf("profile" to SerialEngineFactory.TRANSMIT_PROFILE, "inputFile" to "serial.yaml"),
        ) as StatefulComponent
        finite.start()
        finiteCtx.wireRuntimePorts()
        val finiteCtrl1 = checkNotNull(finiteCtx.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val finiteTx1 = checkNotNull(finiteCtx.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))

        // Consume both transmit plans on channel 1 (65536 B, then 64 B)
        for (i in 0 until 2) {
            val size = if (i == 0) 65536 else 64
            finiteCtx.currentTick = 10L + i * 20L
            finiteCtrl1.write(0L, 4, 0L)
            finiteCtx.currentTick += 5L
            finiteTx1.claim(2, 0xfff30024L, size.toLong(), 4, 21)
            finiteTx1.write(2, 0xfff30024L, 0L, ByteBuffer.wrap(ByteArray(size) { 0x11 }))
            finiteCtx.currentTick += 2L
            finiteTx1.finish(2, finiteCtx.currentTick, finiteCtx.currentTick)
        }
        // Finite engine refuses a 3rd transmit arm
        assertFailsWith<IllegalStateException> { finiteCtrl1.write(0L, 4, 0L) }
        val snapFinite = roundTripThroughJackson(finite.saveSnapshot())

        // Promote into repeatLastPlan = true
        val repCtx = TestContext(transmitDocument())
        val rep = SerialEngineFactory().create(
            repCtx,
            mapOf("profile" to SerialEngineFactory.TRANSMIT_PROFILE, "inputFile" to "serial.yaml", "repeatLastPlan" to true),
        ) as StatefulComponent
        rep.start()
        repCtx.wireRuntimePorts()
        rep.restoreSnapshot(snapFinite)
        val repCtrl1 = checkNotNull(repCtx.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val repTx1 = checkNotNull(repCtx.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))

        // 3rd transmit repeats the last plan (64 bytes, acceptDelayTicks = 5)
        repCtx.currentTick = 100L
        repCtrl1.write(0L, 4, 0L)
        repCtx.currentTick = 105L
        repTx1.claim(2, 0xfff30024L, 64L, 4, 21)
        repTx1.write(2, 0xfff30024L, 0L, ByteBuffer.wrap(ByteArray(64) { 0x22 }))
        repCtx.currentTick = 107L
        repTx1.finish(2, 107L, 107L)

        // Status channel 1 repeats its last finite plan ([0L]) past horizon
        val repStatus1 = checkNotNull(repCtx.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java))
        assertEquals(0x10001L, repStatus1.read(0L, 4))
        assertEquals(0x10001L, repStatus1.read(0L, 4))
        repStatus1.write(0L, 4, 0x10001L)
        repeat(4) {
            assertEquals(0L, repStatus1.read(0L, 4))
            repStatus1.write(0L, 4, 0L)
        }

        val snapRep = roundTripThroughJackson(rep.saveSnapshot())
        // Round-trip onto repeatable succeeds
        val restoredCtx = TestContext(transmitDocument())
        val restored = SerialEngineFactory().create(
            restoredCtx,
            mapOf("profile" to SerialEngineFactory.TRANSMIT_PROFILE, "inputFile" to "serial.yaml", "repeatLastPlan" to true),
        ) as StatefulComponent
        restored.start()
        restoredCtx.wireRuntimePorts()
        restored.restoreSnapshot(snapRep)
        assertEquals(rep.state(), restored.state())

        // Downgrade repeatLastPlan=true -> false must be refused
        assertFailsWith<IllegalArgumentException> { finite.restoreSnapshot(snapRep) }
    }

    @Test
    fun completionIrqsDrivesIrq90AndIrq91WithW1cStatusAndMonotonicSnapshotPromotion() {
        val doc = mapOf(
            "schema" to 1,
            "profile" to SerialEngineFactory.TRANSMIT_PROFILE,
            "firmwareSha256" to SerialEngineFactory.FIRMWARE,
            "initialWords" to mapOf("0" to 0L, "1" to 0L),
            "statusPlans" to mapOf(
                "1" to listOf(listOf(0L)),
                "6" to listOf(listOf(0L)),
            ),
            "transmitPlans" to mapOf(
                "1" to listOf(mapOf("byteCount" to 64, "acceptDelayTicks" to 2L)),
                "6" to listOf(mapOf("byteCount" to 128, "acceptDelayTicks" to 0L)),
            ),
        )
        // Baseline finite component without completionIrqs
        val legacyCtx = TestContext(doc)
        val legacy = SerialEngineFactory().create(
            legacyCtx,
            mapOf("profile" to SerialEngineFactory.TRANSMIT_PROFILE, "inputFile" to "serial.yaml"),
        ) as StatefulComponent
        legacy.start()
        legacyCtx.wireRuntimePorts()
        assertEquals(emptyList(), legacy.interruptRoutes())
        val snapLegacy = roundTripThroughJackson(legacy.saveSnapshot())

        // Promote into repeatLastPlan = true + completionIrqs = true with level-sensitive GIC lines 90 & 91
        val irqLines = mutableMapOf<Int, Boolean>()
        val sink = object : EnabledEdgeInterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLines[irq] = high }
            override fun lineEnabled(irq: Int): Boolean = true
            override fun edgeState(irq: Int): EdgeInterruptState =
                EdgeInterruptState(edge = false, high = irqLines[irq] == true, pending = irqLines[irq] == true)
        }
        val irqCtx = TestContext(doc)
        val engine = SerialEngineFactory().create(
            irqCtx,
            mapOf(
                "profile" to SerialEngineFactory.TRANSMIT_PROFILE,
                "inputFile" to "serial.yaml",
                "repeatLastPlan" to true,
                "completionIrqs" to true,
            ),
        ) as StatefulComponent
        engine.start()
        irqCtx.wireRuntimePorts()
        irqCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, sink)
        assertEquals(
            listOf(
                InterruptRoute("soc-gic.lines", 90..90),
                InterruptRoute("soc-gic.lines", 91..91),
            ),
            engine.interruptRoutes(),
        )
        engine.restoreSnapshot(snapLegacy)

        // All group-0 (0..4) and group-1 (5..7) status ports must be registered so DAT 0x011F82E4 ISR scans cleanly
        for (ch in 0..7) {
            val st = checkNotNull(irqCtx.ports.find(SerialEngineFactory.statusPort(ch), MmioDevice::class.java))
            assertEquals(0L, st.read(0L, 4))
            assertEquals(0L, st.read(0L, 4))
            st.write(0L, 4, 0L)
        }

        // Complete a channel 1 DMA transfer -> latches bit 0 on status-1 and asserts IRQ 90
        val ctrl1 = checkNotNull(irqCtx.ports.find(SerialEngineFactory.controlPort(1), MmioDevice::class.java))
        val tx1 = checkNotNull(irqCtx.ports.find(SerialEngineFactory.txPort(1), DmaDestinationPeer::class.java))
        irqCtx.currentTick = 20L
        ctrl1.write(0L, 4, 0L)
        irqCtx.currentTick = 22L
        tx1.claim(0, 0xfff30024L, 64L, 4, 21)
        tx1.write(0, 0xfff30024L, 0L, ByteBuffer.wrap(ByteArray(64) { 0x33 }))
        irqCtx.currentTick = 25L
        tx1.finish(0, 25L, 25L)
        assertEquals(true, irqLines[90])

        // Snapshot while completion bit 0 is pending on channel 1, round-trip into fresh completionIrqs=true component
        val snapPending = roundTripThroughJackson(engine.saveSnapshot())
        val irqLines2 = mutableMapOf<Int, Boolean>()
        val sink2 = object : EnabledEdgeInterruptSink {
            override fun setLine(irq: Int, high: Boolean) { irqLines2[irq] = high }
            override fun lineEnabled(irq: Int): Boolean = true
            override fun edgeState(irq: Int): EdgeInterruptState =
                EdgeInterruptState(edge = false, high = irqLines2[irq] == true, pending = irqLines2[irq] == true)
        }
        val restoredCtx = TestContext(doc)
        val restored = SerialEngineFactory().create(
            restoredCtx,
            mapOf(
                "profile" to SerialEngineFactory.TRANSMIT_PROFILE,
                "inputFile" to "serial.yaml",
                "repeatLastPlan" to true,
                "completionIrqs" to true,
            ),
        ) as StatefulComponent
        restored.start()
        restoredCtx.wireRuntimePorts()
        restoredCtx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, sink2)
        restored.restoreSnapshot(snapPending)
        restoredCtx.currentTick = 26L
        assertEquals(true, irqLines2[90])

        // Simulate DAT 0x011F82E4(0): read status twice then W1C clear bit 0 -> deasserts IRQ 90
        val st1 = checkNotNull(restoredCtx.ports.find(SerialEngineFactory.statusPort(1), MmioDevice::class.java))
        assertEquals(1L, st1.read(0L, 4))
        assertEquals(1L, st1.read(0L, 4))
        st1.write(0L, 4, 1L)
        assertEquals(0L, st1.read(0L, 4))
        assertEquals(false, irqLines2[90])

        // Complete a channel 6 DMA transfer -> latches bit 0 on status-6 and asserts IRQ 91
        val ctrl6 = checkNotNull(restoredCtx.ports.find(SerialEngineFactory.controlPort(6), MmioDevice::class.java))
        val tx6 = checkNotNull(restoredCtx.ports.find(SerialEngineFactory.txPort(6), DmaDestinationPeer::class.java))
        restoredCtx.currentTick = 40L
        ctrl6.write(0L, 4, 0L)
        restoredCtx.currentTick = 40L
        tx6.claim(1, 0xfff31024L, 128L, 4, 25)
        tx6.write(1, 0xfff31024L, 0L, ByteBuffer.wrap(ByteArray(128) { 0x44 }))
        restoredCtx.currentTick = 42L
        tx6.finish(1, 42L, 42L)
        assertEquals(true, irqLines2[91])
        val st6 = checkNotNull(restoredCtx.ports.find(SerialEngineFactory.statusPort(6), MmioDevice::class.java))
        assertEquals(1L, st6.read(0L, 4))
        assertEquals(1L, st6.read(0L, 4))
        st6.write(0L, 4, 1L)
        assertEquals(false, irqLines2[91])

        // Downgrade completionIrqs=true -> false must be refused
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(snapPending) }
    }
}
