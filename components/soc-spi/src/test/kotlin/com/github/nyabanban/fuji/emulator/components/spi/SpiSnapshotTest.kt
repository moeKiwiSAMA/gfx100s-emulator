package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SpiSnapshotTest {
    private class TestContext(private val portsData: Map<String, Any?> = emptyMap()) : ComponentContext {
        var currentTick = 0L
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-soc-spi-snapshot")
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
            assertEquals("extra-ports.yaml", relative)
            return portsData
        }
    }

    private class ScriptedSpiPeer : SpiPeer {
        var exchanges = 0
        override fun exchange(bits: Int, tx: Int, tick: Long): Int {
            exchanges++
            val mask = (1 shl bits) - 1
            return ((tx xor 0x5a5a) + exchanges + (tick.toInt() and 7)) and mask
        }
    }

    private fun extraPortsDocument(): Map<String, Any?> = mapOf(
        "profile" to "spi-additional-ports-experimental-v1",
        "controllers" to listOf(
            mapOf("index" to 0, "peerPort" to ""),
            mapOf("index" to 3, "peerPort" to "external.peer"),
        ),
    )

    private fun createBank(context: TestContext): Pair<StatefulComponent, ScriptedSpiPeer> {
        val peer = ScriptedSpiPeer()
        context.ports.register("test", "external.peer", SpiPeer::class.java, peer)
        val factory = SpiFactory()
        val config = mapOf(
            "profile" to "spi-fifo-peer-experimental-v2",
            "peerPort" to "external.peer",
            "portsFile" to "extra-ports.yaml",
        )
        val component = factory.create(context, config)
        component.start()
        return assertIs<StatefulComponent>(component) to peer
    }

    private fun controllerDevice(context: TestContext, controllerIndex: Int): ScheduledMmioDevice =
        assertIs<ScheduledMmioDevice>(context.ports.find("soc-spi.spi$controllerIndex", MmioDevice::class.java))

    @Test fun spi2DmaWaitsForShiftedBytesAndPreservesPendingTransfers() {
        val words = listOf(17,34,51,68).map { mapOf("tx" to it, "rx" to (255-it)) }
        fun setup(plan: List<Map<String,Int>>): Pair<TestContext,StatefulComponent> {
            val row = mapOf("index" to 2, "peerPort" to "", "requestEnable3Limit" to 64, "spi2ByteReplies" to plan)
            val context = TestContext(extraPortsDocument() + ("controllers" to listOf(row)))
            return context to createBank(context).first
        }
        val (context, bank) = setup(words)
        val device = controllerDevice(context,2)
        val fifo = assertIs<SpiDmaFifo>(context.registeredPorts["soc-spi.spi2.dma"])
        device.write(0,4,0xc7)
        device.write(16,4,36)
        device.write(4,4,10)
        assertEquals(0,fifo.writableBytes())
        device.write(0x24,4,3)
        assertEquals(8,fifo.writableBytes())
        assertFailsWith<IllegalArgumentException> { fifo.write(ByteBuffer.allocate(9)) }
        fifo.write(ByteBuffer.wrap(byteArrayOf(17,34,51,68)))
        assertEquals(0,fifo.readableBytes())
        device.beforeInstruction(288)
        assertEquals(1,fifo.readableBytes())
        assertFailsWith<IllegalArgumentException> { fifo.read(ByteBuffer.allocate(4)) }
        val saved = bank.saveSnapshot()
        val (restoredContext, restoredBank) = setup(words)
        restoredBank.restoreSnapshot(simulateJsonRoundTrip(saved))
        val restoredDevice = controllerDevice(restoredContext,2)
        val restoredFifo = assertIs<SpiDmaFifo>(restoredContext.registeredPorts["soc-spi.spi2.dma"])
        restoredDevice.beforeInstruction(1152)
        assertEquals(4,restoredFifo.readableBytes())
        assertFailsWith<IllegalArgumentException> { restoredFifo.read(ByteBuffer.allocate(4).asReadOnlyBuffer()) }
        val received = ByteBuffer.allocate(4)
        restoredFifo.read(received)
        assertEquals(listOf(238,221,204,187),received.array().map { it.toInt() and 255 })
        assertEquals(0,restoredFifo.readableBytes())
        val (_, changedBank) = setup(words.toMutableList().apply { this[1] = mapOf("tx" to 34,"rx" to 0) })
        assertFailsWith<IllegalArgumentException> { changedBank.restoreSnapshot(saved) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(mapOf("tx" to 256,"rx" to 0))) }
        restoredFifo.write(ByteBuffer.wrap(byteArrayOf(0)))
        val pending = restoredBank.saveSnapshot()
        assertFailsWith<IllegalStateException> { restoredDevice.beforeInstruction(2000) }
        assertEquals(pending,restoredBank.saveSnapshot())
    }

    @Test fun spi2LowMisoSuffixResumesAfterScriptedPrefixAndStopsAtomically() {
        val prefix = listOf(mapOf("tx" to 7, "rx" to 65))
        val fixed = mapOf("profile" to "bounded-spi2-miso-low-v1", "prefix" to prefix,
            "fixedMisoHigh" to false, "exchangeLimit" to 2)
        fun setup(plan: Any): Pair<StatefulComponent, ScheduledMmioDevice> {
            val row = mapOf("index" to 2, "peerPort" to "", "requestEnable3Limit" to 1, "spi2ByteReplies" to plan)
            val context = TestContext(extraPortsDocument() + ("controllers" to listOf(row)))
            return createBank(context).first to controllerDevice(context, 2)
        }
        val (scripted, spi) = setup(prefix)
        spi.write(0, 4, 0xc7)
        spi.write(16, 4, 36)
        spi.write(4, 4, 10)
        for (byte in listOf(7L, 0x83L, 0xf6L)) spi.write(8, 4, byte)
        spi.beforeInstruction(288)
        val saved = scripted.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { setup(fixed + ("prefix" to emptyList<Any>())).first.restoreSnapshot(saved) }
        val (bank, resumed) = setup(fixed)
        bank.restoreSnapshot(simulateJsonRoundTrip(saved))
        assertEquals(65L, resumed.read(8, 4))
        resumed.beforeInstruction(576)
        assertEquals(0L, resumed.read(8, 4))
        val exhausted = bank.saveSnapshot()
        assertFailsWith<IllegalStateException> { resumed.beforeInstruction(864) }
        assertEquals(exhausted, bank.saveSnapshot())
        setup(fixed).first.restoreSnapshot(simulateJsonRoundTrip(exhausted))
        val (extended, next) = setup(fixed + ("exchangeLimit" to 256))
        extended.restoreSnapshot(exhausted)
        next.beforeInstruction(864)
        assertEquals(0L, next.read(8, 4))
        for (invalid in listOf(fixed + ("fixedMisoHigh" to true), fixed + ("exchangeLimit" to 257),
            fixed + ("profile" to "bounded-audio-miso-v1"))) {
            assertFailsWith<IllegalArgumentException> { setup(invalid) }
        }
    }

    @Test fun spi2RequestMaskRetainsOneGuestWriteAcrossRestore() {
        fun setup(limit: Int?, index: Int = 2, peerPort: String = ""): Pair<StatefulComponent, ScheduledMmioDevice> {
            val row = mapOf("index" to index, "peerPort" to peerPort) +
                (limit?.let { mapOf("requestEnable3Limit" to it) } ?: emptyMap())
            val context = TestContext(extraPortsDocument() + ("controllers" to listOf(row)))
            return createBank(context).first to controllerDevice(context,index)
        }
        assertFailsWith<IllegalArgumentException> { setup(1,5) }
        assertFailsWith<IllegalArgumentException> { setup(1,2,"external.peer") }
        assertFailsWith<IllegalArgumentException> { setup(65) }
        val (legacy, oldDevice) = setup(null)
        assertFailsWith<IllegalArgumentException> { oldDevice.write(0x24,4,3) }
        val (bank, device) = setup(1)
        bank.restoreSnapshot(legacy.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { device.write(0x24,2,3) }
        assertFailsWith<IllegalArgumentException> { device.write(0x24,4,1) }
        device.write(0x24,4,3)
        assertEquals(3L,device.read(0x24,4))
        assertEquals(null,device.nextDeadline())
        val saved = simulateJsonRoundTrip(bank.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(saved) }
        val (resumed, spi2) = setup(1)
        resumed.restoreSnapshot(saved)
        assertEquals(3L,spi2.read(0x24,4))
        spi2.write(0x24,4,0)
        assertFailsWith<IllegalArgumentException> { spi2.write(0x24,4,3) }
        assertEquals(0L,spi2.read(0x24,4))
        val (extended, device2) = setup(2)
        extended.restoreSnapshot(resumed.saveSnapshot())
        device2.write(0x24,4,3)
        assertFailsWith<IllegalArgumentException> { device2.write(0x24,4,3) }
    }

    @Test fun panelRepliesMatchOnceAndCanAppendAfterRestoringPendingAndCompletedTransfers() {
        val first = mapOf("tx" to 0x1b40, "rx" to 0xa55a)
        val second = mapOf("tx" to 0, "rx" to 0x5a5a)
        fun setup(replies: Any?, index: Int = 5, port: String = ""): Pair<StatefulComponent, ScheduledMmioDevice> {
            val row = mapOf("index" to index, "peerPort" to port) +
                (replies?.let { mapOf("panel16Replies" to it) } ?: emptyMap())
            val host = TestContext(extraPortsDocument() + ("controllers" to listOf(row)))
            return createBank(host).first to controllerDevice(host, index)
        }
        val (legacy, original) = setup(null)
        original.write(0,4,0xcf)
        original.write(16,4,18)
        original.write(4,4,10)
        original.write(8,4,0x1b40)
        val pending = simulateJsonRoundTrip(legacy.saveSnapshot())
        val (bank, spi) = setup(listOf(first))
        bank.restoreSnapshot(pending)
        spi.beforeInstruction(checkNotNull(spi.nextDeadline()))
        val firstComplete = simulateJsonRoundTrip(bank.saveSnapshot())
        assertEquals(0xa55aL, spi.read(8,4))
        spi.write(8,4,0)
        val boundary = bank.saveSnapshot()
        assertFailsWith<IllegalStateException> { spi.beforeInstruction(checkNotNull(spi.nextDeadline())) }
        assertEquals(boundary, bank.saveSnapshot())
        val (extended, resumed) = setup(listOf(first, second))
        extended.restoreSnapshot(firstComplete)
        assertEquals(0xa55aL, resumed.read(8,4))
        extended.restoreSnapshot(boundary)
        resumed.beforeInstruction(checkNotNull(resumed.nextDeadline()))
        assertEquals(0x5a5aL, resumed.read(8,4))
        val (mismatch, wrong) = setup(listOf(second))
        mismatch.restoreSnapshot(pending)
        val before = mismatch.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { wrong.beforeInstruction(checkNotNull(wrong.nextDeadline())) }
        assertFailsWith<IllegalArgumentException> { mismatch.restoreSnapshot(firstComplete) }
        assertEquals(before, mismatch.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(firstComplete) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(first), index = 0) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(first), port = "external.peer") }
        assertFailsWith<IllegalArgumentException> { setup(List(257) { first }) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(first + ("rx" to 65536))) }

        val fixed = mapOf("prefix" to listOf(first), "fixedMisoHigh" to false, "exchangeLimit" to 3)
        val (fixedBank, fixedSpi) = setup(fixed)
        fixedBank.restoreSnapshot(boundary)
        fixedSpi.beforeInstruction(checkNotNull(fixedSpi.nextDeadline()))
        assertEquals(0L, fixedSpi.read(8,4))
        fixedSpi.write(8,4,0xbeef)
        fixedSpi.beforeInstruction(checkNotNull(fixedSpi.nextDeadline()))
        val full = simulateJsonRoundTrip(fixedBank.saveSnapshot())
        val (again, restoredSpi) = setup(fixed)
        again.restoreSnapshot(full)
        assertEquals(0L, restoredSpi.read(8,4))
        restoredSpi.write(8,4,0)
        val exhausted = again.saveSnapshot()
        assertFailsWith<IllegalStateException> { restoredSpi.beforeInstruction(checkNotNull(restoredSpi.nextDeadline())) }
        assertEquals(exhausted, again.saveSnapshot())
        for (changed in listOf(fixed + ("fixedMisoHigh" to true), fixed + ("exchangeLimit" to 2), fixed + ("prefix" to emptyList<Any>()))) {
            assertFailsWith<IllegalArgumentException> { setup(changed).first.restoreSnapshot(full) }
        }
        val (longerBank, longerSpi) = setup(fixed + ("exchangeLimit" to 512))
        longerBank.restoreSnapshot(exhausted)
        longerSpi.beforeInstruction(checkNotNull(longerSpi.nextDeadline()))
        assertEquals(0L, longerSpi.read(8, 4))
        val prefix = listOf(first, second)
        val originalPlan = fixed + mapOf("prefix" to prefix, "exchangeLimit" to 512)
        val (panel, panelSpi) = setup(originalPlan)
        panel.restoreSnapshot(boundary)
        panelSpi.beforeInstruction(checkNotNull(panelSpi.nextDeadline()))
        assertEquals(0x5a5aL, panelSpi.read(8, 4))
        fun exchangeZero(device: ScheduledMmioDevice) {
            device.write(8, 4, 0x6342)
            device.beforeInstruction(checkNotNull(device.nextDeadline()))
            assertEquals(0L, device.read(8, 4))
        }
        repeat(510) { exchangeZero(panelSpi) }
        panelSpi.write(8, 4, 0x6342)
        val pending513 = simulateJsonRoundTrip(panel.saveSnapshot())
        assertFailsWith<IllegalStateException> { panelSpi.beforeInstruction(checkNotNull(panelSpi.nextDeadline())) }
        assertEquals(pending513, simulateJsonRoundTrip(panel.saveSnapshot()))
        val extendedPlan = originalPlan + ("exchangeLimit" to 1024)
        val (extendedPanel, extendedSpi) = setup(extendedPlan)
        for (changed in listOf(prefix.reversed(), listOf(first, second + ("rx" to 1)))) {
            assertFailsWith<IllegalArgumentException> {
                setup(extendedPlan + ("prefix" to changed)).first.restoreSnapshot(pending513)
            }
        }
        extendedPanel.restoreSnapshot(pending513)
        extendedSpi.beforeInstruction(checkNotNull(extendedSpi.nextDeadline()))
        assertEquals(0L, extendedSpi.read(8, 4))
        repeat(511) { exchangeZero(extendedSpi) }
        extendedSpi.write(8, 4, 0x6342)
        val pending1025 = extendedPanel.saveSnapshot()
        assertFailsWith<IllegalStateException> { extendedSpi.beforeInstruction(checkNotNull(extendedSpi.nextDeadline())) }
        assertEquals(pending1025, extendedPanel.saveSnapshot())
        val (lastPanel, lastSpi) = setup(extendedPlan + ("exchangeLimit" to 2048))
        lastPanel.restoreSnapshot(simulateJsonRoundTrip(pending1025))
        lastSpi.beforeInstruction(checkNotNull(lastSpi.nextDeadline()))
        assertEquals(0L, lastSpi.read(8, 4))
        repeat(1023) { exchangeZero(lastSpi) }
        lastSpi.write(8, 4, 0x1b40)
        val pending2049 = lastPanel.saveSnapshot()
        assertFailsWith<IllegalStateException> { lastSpi.beforeInstruction(checkNotNull(lastSpi.nextDeadline())) }
        assertEquals(pending2049, lastPanel.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { setup(extendedPlan + ("exchangeLimit" to 2049)) }
        val high = PanelWordPlan(mapOf("prefix" to emptyList<Any>(), "fixedMisoHigh" to true, "exchangeLimit" to 1))
        assertFailsWith<IllegalArgumentException> { high.exchange(0,8,0) }
        assertEquals(0xffff, high.exchange(0,16,0x0123))
    }

    @Test fun audioPeerConsumesOnlyDeclaredWordsFromPendingSpi4() {
        val first = mapOf("tx" to 15, "rx" to 0xa55a)
        val second = mapOf("tx" to 0x6243, "rx" to 0)
        fun setup(words: Any?, index: Int = 4, port: String = ""): Pair<StatefulComponent, ScheduledMmioDevice> {
            val row = mapOf("index" to index, "peerPort" to port) +
                (words?.let { mapOf("audio16Replies" to it) } ?: emptyMap())
            val host = TestContext(extraPortsDocument() + ("controllers" to listOf(row)))
            return createBank(host).first to controllerDevice(host, index)
        }
        val (old, original) = setup(null)
        original.write(0, 4, 15)
        original.write(16, 4, 28)
        original.write(4, 4, 10)
        original.write(8, 4, 15)
        val pending = simulateJsonRoundTrip(old.saveSnapshot())
        assertFailsWith<IllegalStateException> { original.beforeInstruction(checkNotNull(original.nextDeadline())) }
        val (wrongBank, wrong) = setup(listOf(second))
        wrongBank.restoreSnapshot(pending)
        val before = wrongBank.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { wrong.beforeInstruction(checkNotNull(wrong.nextDeadline())) }
        assertEquals(before, wrongBank.saveSnapshot())
        val (bank, spi) = setup(listOf(first))
        bank.restoreSnapshot(pending)
        spi.beforeInstruction(checkNotNull(spi.nextDeadline()))
        assertEquals(0xa55aL, spi.read(8, 4))
        spi.write(8, 4, 0x6243)
        val boundary = simulateJsonRoundTrip(bank.saveSnapshot())
        assertFailsWith<IllegalStateException> { spi.beforeInstruction(checkNotNull(spi.nextDeadline())) }
        val (extended, resumed) = setup(listOf(first, second))
        extended.restoreSnapshot(boundary)
        resumed.beforeInstruction(checkNotNull(resumed.nextDeadline()))
        assertEquals(0L, resumed.read(8, 4))
        assertFailsWith<IllegalArgumentException> { setup(listOf(second)).first.restoreSnapshot(boundary) }
        assertFailsWith<IllegalArgumentException> { setup(null).first.restoreSnapshot(boundary) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(first), 5) }
        assertFailsWith<IllegalArgumentException> { setup(listOf(first), port = "external.peer") }
        assertFailsWith<IllegalArgumentException> { setup(mapOf("prefix" to listOf(first), "fixedMisoHigh" to false, "exchangeLimit" to 2)) }
        val fixed = mapOf("profile" to "bounded-audio-miso-v1", "prefix" to listOf(first),
            "fixedMisoHigh" to true, "exchangeLimit" to 2)
        val (fixedBank, fixedSpi) = setup(fixed)
        fixedBank.restoreSnapshot(boundary)
        fixedSpi.beforeInstruction(checkNotNull(fixedSpi.nextDeadline()))
        assertEquals(65535L, fixedSpi.read(8, 4))
        fixedSpi.write(8, 4, 0x21ac)
        val exhausted = fixedBank.saveSnapshot()
        assertFailsWith<IllegalStateException> { fixedSpi.beforeInstruction(checkNotNull(fixedSpi.nextDeadline())) }
        assertEquals(exhausted, fixedBank.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { setup(fixed + ("fixedMisoHigh" to false)).first.restoreSnapshot(exhausted) }
        assertFailsWith<IllegalArgumentException> { setup(fixed + ("exchangeLimit" to 16385)) }
        val (extendedFixed, liveFixed) = setup(fixed + ("exchangeLimit" to 16384))
        extendedFixed.restoreSnapshot(exhausted)
        liveFixed.beforeInstruction(checkNotNull(liveFixed.nextDeadline()))
        assertEquals(65535L, liveFixed.read(8, 4))
        assertFailsWith<IllegalArgumentException> { setup(List(4097) { first }) }
        val words = listOf(first) + (1..257).map { mapOf("tx" to it, "rx" to it) }
        val (longBank, longSpi) = setup(words)
        longBank.restoreSnapshot(pending)
        words.forEachIndexed { index, entry ->
            if (index > 0) longSpi.write(8, 4, entry.getValue("tx").toLong())
            longSpi.beforeInstruction(checkNotNull(longSpi.nextDeadline()))
            assertEquals(entry.getValue("rx").toLong(), longSpi.read(8, 4))
        }
        val truncated = longBank.saveSnapshot()
        val (restored, _) = setup(words)
        restored.restoreSnapshot(simulateJsonRoundTrip(truncated))
        assertEquals(truncated, restored.saveSnapshot())
    }

    @Test fun activatesOnlyUnwiredSpi5FromAnIntactSpi0Prefix() {
        val spi0 = mapOf("index" to 0, "peerPort" to "")
        val spi5 = mapOf("index" to 5, "peerPort" to "")
        fun host(vararg controllers: Map<String, Any>) =
            TestContext(extraPortsDocument() + ("controllers" to controllers.toList()))
        val old = host(spi0)
        val (oldBank, _) = createBank(old)
        controllerDevice(old, 1).write(0, 4, 15)
        controllerDevice(old, 0).write(0x10, 4, 6)
        val prefix = simulateJsonRoundTrip(oldBank.saveSnapshot())
        val live = host(spi0, spi5)
        val (bank, peer) = createBank(live)
        val panel = controllerDevice(live, 5)
        val reset = (bank.saveSnapshot()["additionalControllers"] as List<*>).last()
        bank.restoreSnapshot(prefix)
        assertEquals(15L, controllerDevice(live, 1).read(0, 4))
        assertEquals(6L, controllerDevice(live, 0).read(0x10, 4))
        assertEquals(0L, panel.read(0, 4))
        panel.write(0, 4, 15)
        panel.write(0x10, 4, 4)
        panel.write(4, 4, 2)
        panel.write(8, 4, 0x1234)
        val pending = bank.saveSnapshot()
        assertFailsWith<IllegalStateException> { panel.beforeInstruction(checkNotNull(panel.nextDeadline())) }
        assertEquals(pending, bank.saveSnapshot())
        assertEquals(0, peer.exchanges)
        assertFailsWith<IllegalArgumentException> { oldBank.restoreSnapshot(pending) }
        bank.restoreSnapshot(prefix)
        assertEquals(reset, (bank.saveSnapshot()["additionalControllers"] as List<*>).last())
        bank.restoreSnapshot(simulateJsonRoundTrip(pending))
        assertEquals(pending, bank.saveSnapshot())
        for (other in listOf(host(spi0, spi5 + ("peerPort" to "external.peer")), host(spi0, spi5 + ("index" to 6)),
                host(spi0 + ("peerPort" to "external.peer"), spi5))) {
            val (mismatched, _) = createBank(other)
            val before = mismatched.saveSnapshot()
            assertFailsWith<IllegalArgumentException> { mismatched.restoreSnapshot(prefix) }
            assertEquals(before, mismatched.saveSnapshot())
        }
    }

    @Test fun restoresThePanelStateBeforeActivatingAudioSpi4() {
        val spi0 = mapOf("index" to 0, "peerPort" to "")
        val spi5 = mapOf("index" to 5, "peerPort" to "")
        val spi4 = mapOf("index" to 4, "peerPort" to "")
        fun host(vararg controllers: Map<String, Any>) =
            TestContext(extraPortsDocument() + ("controllers" to controllers.toList()))
        val old = host(spi0, spi5)
        val (original, _) = createBank(old)
        controllerDevice(old, 5).write(0, 4, 0xcf)
        val saved = simulateJsonRoundTrip(original.saveSnapshot())
        val live = host(spi0, spi5, spi4)
        val (restored, _) = createBank(live)
        controllerDevice(live, 4).write(0, 4, 15)
        restored.restoreSnapshot(saved)
        assertEquals(0xcfL, controllerDevice(live, 5).read(0, 4))
        assertEquals(0L, controllerDevice(live, 4).read(0, 4))
        for (invalid in listOf(host(spi0, spi5, spi4 + ("index" to 6)), host(spi0, spi5, spi4 + ("peerPort" to "external.peer")))) {
            val (bank, _) = createBank(invalid)
            val before = bank.saveSnapshot()
            assertFailsWith<IllegalArgumentException> { bank.restoreSnapshot(saved) }
            assertEquals(before, bank.saveSnapshot())
        }
    }

    @Test fun activatingAudioKeepsConfiguredSpi2RequestState() {
        val controllers = listOf(
            mapOf("index" to 0, "peerPort" to ""),
            mapOf("index" to 2, "peerPort" to "", "requestEnable3Limit" to 1),
            mapOf("index" to 5, "peerPort" to ""),
        )
        val prefix = TestContext(extraPortsDocument() + ("controllers" to controllers))
        val (original, _) = createBank(prefix)
        controllerDevice(prefix, 2).write(0x24, 4, 3)
        val saved = simulateJsonRoundTrip(original.saveSnapshot())
        val live = TestContext(extraPortsDocument() + ("controllers" to (controllers + mapOf("index" to 4, "peerPort" to ""))))
        val (restored, _) = createBank(live)
        restored.restoreSnapshot(saved)
        assertEquals(3L, controllerDevice(live, 2).read(0x24, 4))
        assertEquals(0L, controllerDevice(live, 4).read(0, 4))
    }

    @Test fun activatesSpi2AfterSetupWithPendingAudioAndPanelControls() {
        val controllers = listOf(0, 4, 5).map { mapOf("index" to it, "peerPort" to "") }
        val old = TestContext(extraPortsDocument() + ("controllers" to controllers))
        val (source, _) = createBank(old)
        val audio = controllerDevice(old, 4)
        audio.write(0, 4, 15)
        audio.write(0x10, 4, 4)
        audio.write(4, 4, 2)
        audio.write(8, 4, 0x1234)
        controllerDevice(old, 5).write(0, 4, 0xcf)
        val saved = source.saveSnapshot()
        val target = TestContext(extraPortsDocument() + ("controllers" to
            controllers + mapOf("index" to 2, "peerPort" to "", "requestEnable3Limit" to 64)))
        val (restored, _) = createBank(target)
        val spi2 = controllerDevice(target, 2)
        spi2.write(0x24, 4, 3)
        restored.restoreSnapshot(simulateJsonRoundTrip(saved))
        assertEquals(0L, spi2.read(0x24, 4))
        val result = restored.saveSnapshot()
        val extras = result["additionalControllers"] as List<*>
        assertEquals(saved, result + ("additionalControllers" to listOf(extras[0], extras[2], extras[3])))
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
    fun snapshotRoundTripPreservesPendingShiftsFifosTruncatedLogsAndContinuationEquivalence() {
        val directContext = TestContext(extraPortsDocument())
        val (directBank, directPeer) = createBank(directContext)
        val directSpi1 = controllerDevice(directContext, 1)
        val directSpi0 = controllerDevice(directContext, 0)
        val directSpi3 = controllerDevice(directContext, 3)

        directSpi0.write(0L, 4, 0x0107L)
        directSpi0.write(0x10L, 4, 2L)
        directSpi0.write(4L, 4, 3L)
        for (step in 0 until 260) {
            val tick = step * 40L
            directContext.currentTick = tick
            directSpi0.write(8L, 4, (step and 0xff).toLong())
            val deadline = checkNotNull(directSpi0.nextDeadline())
            directContext.currentTick = deadline
            directSpi0.beforeInstruction(deadline)
            assertEquals((step and 0xff).toLong(), directSpi0.read(8L, 4))
        }

        directContext.currentTick = 12_000L
        directSpi1.write(0L, 4, 0x020bL)
        directSpi1.write(0x10L, 4, 4L)
        directSpi1.write(4L, 4, 2L)
        directSpi1.write(8L, 4, 0xabcL)
        directSpi1.write(8L, 4, 0xdefL)
        directSpi1.write(8L, 4, 0x123L)
        val firstDeadline = checkNotNull(directSpi1.nextDeadline())
        directContext.currentTick = firstDeadline
        directSpi1.beforeInstruction(firstDeadline)

        directSpi3.write(0L, 4, 0x000fL)
        directSpi3.write(0x10L, 4, 2L)
        directSpi3.write(4L, 4, 2L)
        directSpi3.write(8L, 4, 0xbeefL)

        val peerExchangesAtT1 = directPeer.exchanges
        val roundTripped = simulateJsonRoundTrip(directBank.saveSnapshot())

        val restoredContext = TestContext(extraPortsDocument())
        val (restoredBank, restoredPeer) = createBank(restoredContext)
        restoredPeer.exchanges = peerExchangesAtT1
        restoredBank.restoreSnapshot(roundTripped)

        assertEquals(directBank.state(), restoredBank.state())
        val restoredSpi1 = controllerDevice(restoredContext, 1)
        val restoredSpi0 = controllerDevice(restoredContext, 0)
        val restoredSpi3 = controllerDevice(restoredContext, 3)
        assertEquals(directSpi1.nextDeadline(), restoredSpi1.nextDeadline())
        assertEquals(directSpi0.nextDeadline(), restoredSpi0.nextDeadline())
        assertEquals(directSpi3.nextDeadline(), restoredSpi3.nextDeadline())

        fun advanceToT2(
            context: TestContext,
            spi1: ScheduledMmioDevice,
            spi0: ScheduledMmioDevice,
            spi3: ScheduledMmioDevice,
        ): List<Long> {
            val collected = ArrayList<Long>()
            val targetTick = 13_000L
            context.currentTick = targetTick
            spi1.beforeInstruction(targetTick)
            spi3.beforeInstruction(targetTick)
            collected.add(spi1.read(0xcL, 4))
            collected.add(spi1.read(8L, 4))
            collected.add(spi1.read(8L, 4))
            collected.add(spi1.read(8L, 4))
            collected.add(spi3.read(8L, 4))
            spi1.write(0x14L, 4, 1L)
            spi0.write(8L, 4, 0x77L)
            val nextAt = checkNotNull(spi0.nextDeadline())
            context.currentTick = nextAt
            spi0.beforeInstruction(nextAt)
            collected.add(spi0.read(8L, 4))
            return collected
        }

        val directOutputs = advanceToT2(directContext, directSpi1, directSpi0, directSpi3)
        val restoredOutputs = advanceToT2(restoredContext, restoredSpi1, restoredSpi0, restoredSpi3)

        assertEquals(directOutputs, restoredOutputs)
        assertEquals(directBank.state(), restoredBank.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingControllers() {
        val context = TestContext(extraPortsDocument())
        val (bank, _) = createBank(context)
        val spi1 = controllerDevice(context, 1)
        context.currentTick = 50L
        spi1.write(0L, 4, 0x0007L)
        spi1.write(0x10L, 4, 2L)
        spi1.write(4L, 4, 3L)
        spi1.write(8L, 4, 0x55L)
        spi1.write(8L, 4, 0xaaL)

        val baselineState = bank.state()
        val baselineDeadline = spi1.nextDeadline()
        val valid = simulateJsonRoundTrip(bank.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val primaryMap = valid["primary"] as Map<String, Any?>

        val invalidCandidates = listOf(
            valid - "primary",
            valid + ("extraField" to true),
            valid + ("schema" to 99),
            valid + ("schema" to 1.5),
            valid + ("componentId" to "spi-input"),
            valid + ("scalarValues" to mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml")),
            valid + ("primary" to (primaryMap + ("control" to 0x0030L))),
            valid + ("primary" to (primaryMap + ("enable" to 0x04L))),
            valid + ("primary" to (primaryMap + ("causes" to 2L))),
            valid + ("primary" to (primaryMap + ("tx" to List(9) { 1 }))),
            valid + ("primary" to (primaryMap + ("pending" to null))),
            valid + ("primary" to (primaryMap + ("pending" to mapOf("at" to 66L, "tx" to 0x55, "bits" to 12)))),
            valid + ("primary" to (primaryMap + ("eventCount" to 5L))),
            valid + ("additionalControllers" to emptyList<Any>()),
        )

        for (candidate in invalidCandidates) {
            assertFailsWith<IllegalArgumentException> {
                bank.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, bank.state())
            assertEquals(baselineDeadline, spi1.nextDeadline())
        }
    }

    @Test
    fun biscomPeerAndPreExchangeAttachWorkWithRequestEnable3Limit() {
        val preExchangePorts = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 0, "peerPort" to ""),
                mapOf("index" to 2, "peerPort" to "", "requestEnable3Limit" to 1),
            ),
        )
        val preContext = TestContext(preExchangePorts)
        val preBank = SpiFactory().create(
            preContext,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        val preSpi2 = checkNotNull(preContext.registeredPorts["soc-spi.spi2"] as? ScheduledMmioDevice)
        preSpi2.write(0x24L, 4, 3L) // consume 1 of 1 requestEnable3Limit before biscom exchanges
        val checkpoint = simulateJsonRoundTrip(preBank.saveSnapshot())

        val biscomPorts = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 0, "peerPort" to ""),
                mapOf("index" to 2, "peerPort" to "soc-spi.biscom", "requestEnable3Limit" to 64),
            ),
        )
        val liveContext = TestContext(biscomPorts)
        val liveBank = SpiFactory().create(
            liveContext,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        liveBank.restoreSnapshot(checkpoint)
        val spi2 = checkNotNull(liveContext.registeredPorts["soc-spi.spi2"] as? ScheduledMmioDevice)
        spi2.write(0x24L, 4, 3L) // succeeds now with upgraded limit 64
        spi2.write(0L, 4, 0x00c7L) // 8-bit transfers
        spi2.write(0x10L, 4, 36L)
        spi2.write(4L, 4, 0x0aL)

        fun transfer32(word: Long): Long {
            var rx = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                liveContext.currentTick += 10L
                spi2.write(8L, 4, (word ushr shift) and 0xffL)
                val dl = spi2.nextDeadline()!!
                liveContext.currentTick = dl
                spi2.beforeInstruction(dl)
                rx = (rx shl 8) or (spi2.read(8L, 4) and 0xffL)
            }
            return rx
        }

        // Send observed biscom frames: 0x000820d0 (cmd=0x83, frameType=0), 0x08108022 (frameType=1), 0x008303f6 (cmd=0xf, frameType=0), then poll 0xA55A3CC3
        assertEquals(0L, transfer32(0x000820d0L))
        val rx0 = transfer32(0x08108022L)
        assertEquals(0x83L, (rx0 ushr 6) and 0x1ffL)
        assertEquals(1L, (rx0 ushr 15) and 1L)
        assertEquals(1L, (rx0 ushr 27) and 0x1fL)
        assertEquals(0L, (rx0 ushr 16) and 0x1fL)
        assertEquals(Frame32SpiPeer.checksum(rx0), (rx0 ushr 1) and 0x1fL)

        val midSnap = simulateJsonRoundTrip(liveBank.saveSnapshot())
        val resumedContext = TestContext(biscomPorts)
        val resumedBank = SpiFactory().create(
            resumedContext,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        resumedBank.restoreSnapshot(midSnap)
        val resumedSpi2 = checkNotNull(resumedContext.registeredPorts["soc-spi.spi2"] as? ScheduledMmioDevice)
        fun resumedTransfer32(word: Long): Long {
            var rx = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                resumedContext.currentTick += 10L
                resumedSpi2.write(8L, 4, (word ushr shift) and 0xffL)
                val dl = resumedSpi2.nextDeadline()!!
                resumedContext.currentTick = dl
                resumedSpi2.beforeInstruction(dl)
                rx = (rx shl 8) or (resumedSpi2.read(8L, 4) and 0xffL)
            }
            return rx
        }
        val rx1 = resumedTransfer32(0x008303f6L)
        assertEquals(0x83L, (rx1 ushr 6) and 0x1ffL) // frameType=0 response to cmdId=0x83 after poll 0x08108022
        assertEquals(0L, (rx1 ushr 15) and 1L)
        assertEquals(Frame32SpiPeer.checksum(rx1), (rx1 ushr 1) and 0x1fL)
        val rx2 = resumedTransfer32(0x08108022L)
        assertEquals(0x0fL, (rx2 ushr 6) and 0x1ffL) // cmdId=0xf ACK
        assertEquals(1L, (rx2 ushr 15) and 1L)
        assertEquals(0L, (rx2 ushr 24) and 0x7L) // slaveSeq=0 confirms first DATA frame (rx1)
        assertEquals(1L, (rx2 ushr 27) and 0x1fL)
        assertEquals(0L, (rx2 ushr 16) and 0x1fL)
        assertEquals(Frame32SpiPeer.checksum(rx2), (rx2 ushr 1) and 0x1fL)
        val rx3 = resumedTransfer32(0x00000000L)
        assertEquals(0x0fL, (rx3 ushr 6) and 0x1ffL) // frameType=0 response to cmdId=0xf after poll 0x08108022
        assertEquals(0L, (rx3 ushr 15) and 1L)
        assertEquals(Frame32SpiPeer.checksum(rx3), (rx3 ushr 1) and 0x1fL)
        val rx4 = resumedTransfer32(0x0800a0daL) // frameType=1 ACK after idle tx=0x00000000
        assertEquals(0L, (rx4 ushr 6) and 0x1ffL) // cmdId=0 ACK
        assertEquals(1L, (rx4 ushr 15) and 1L)
        assertEquals(1L, (rx4 ushr 24) and 0x7L) // slaveSeq=1 confirms second DATA frame (rx3) so host dispatches cmdId=0xf immediately
        assertEquals(1L, (rx4 ushr 27) and 0x1fL)
        assertEquals(Frame32SpiPeer.checksum(rx4), (rx4 ushr 1) and 0x1fL)
        assertEquals(0L, resumedTransfer32(0xA55A3CC3L)) // cmdId=0 data response == 0; idle poll clears pending
        assertEquals(0L, resumedTransfer32(0xA55A3CC3L)) // no pending command -> 0
    }

    @Test fun biscomVersion0x2001AndReadyIrq171WorkAcrossSnapshot() {
        val biscomPorts = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 2, "peerPort" to "soc-spi.biscom", "requestEnable3Limit" to 16384),
            ),
        )
        class FakeGic(var edgeMode: Boolean) : EnabledEdgeInterruptSink {
            val enabled = mutableSetOf<Int>()
            val asserted = mutableSetOf<Int>()
            val transitions = mutableListOf<Pair<Int, Boolean>>()
            override fun lineEnabled(irq: Int): Boolean = irq in enabled
            override fun edgeState(irq: Int): EdgeInterruptState =
                EdgeInterruptState(edgeMode, irq in asserted, false)
            override fun setLine(irq: Int, high: Boolean) {
                require(irq == 171 || irq == 155)
                if (high) asserted += irq else asserted -= irq
                transitions += irq to high
            }
        }
        val gpioLevels = mutableListOf<Triple<Int, Int, Boolean>>()
        val fakeGpio = GpioInputPort { port, pin, isHigh -> gpioLevels += Triple(port, pin, isHigh) }
        val gic = FakeGic(edgeMode = false)
        val context = TestContext(biscomPorts)
        context.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        context.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, fakeGpio)
        val bank = SpiFactory().create(
            context,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        val spi2 = checkNotNull(context.registeredPorts["soc-spi.spi2"] as? ScheduledMmioDevice)
        spi2.write(0x24L, 4, 3L)
        spi2.write(0L, 4, 0x00c7L)
        spi2.write(0x10L, 4, 36L)
        spi2.write(4L, 4, 0x0aL)

        // Level-triggered IRQ 171 (falling BIS_RDY -> GPIO(0,4)=false) assert -> snapshot -> release when disabled
        gic.enabled += 171
        context.currentTick = 100L
        spi2.beforeInstruction(100L)
        assertEquals(listOf(171 to true), gic.transitions)
        assertEquals(listOf(Triple(0, 4, false)), gpioLevels)

        val snap = simulateJsonRoundTrip(bank.saveSnapshot())
        val gic2 = FakeGic(edgeMode = false).apply { asserted += 171 }
        val gpioLevels2 = mutableListOf<Triple<Int, Int, Boolean>>()
        val context2 = TestContext(biscomPorts)
        context2.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic2)
        context2.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { p, b, h -> gpioLevels2 += Triple(p, b, h) })
        val bank2 = SpiFactory().create(
            context2,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        bank2.restoreSnapshot(snap)
        val spi2Resumed = checkNotNull(context2.registeredPorts["soc-spi.spi2"] as? ScheduledMmioDevice)
        context2.currentTick = 1_100L
        spi2Resumed.beforeInstruction(1_100L)
        assertEquals(listOf(171 to false), gic2.transitions)

        // Rising BIS_RDY (IRQ 155 -> GPIO(0,4)=true) in edge mode pulses high then low immediately
        gic2.edgeMode = true
        gic2.enabled += 155
        context2.currentTick = 51_100L
        spi2Resumed.beforeInstruction(51_100L)
        assertEquals(listOf(171 to false, 155 to true, 155 to false), gic2.transitions)
        assertEquals(listOf(Triple(0, 4, true)), gpioLevels2)

        // Verify cmdId=0x14 (IBIS firmware version query 0x00000514) returns version 0x2001 (0x0120 in [31:16])
        fun xfer32(word: Long): Long {
            var rx = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                context2.currentTick += 10L
                spi2Resumed.write(8L, 4, (word ushr shift) and 0xffL)
                val dl = spi2Resumed.nextDeadline()!!
                context2.currentTick = dl
                spi2Resumed.beforeInstruction(dl)
                rx = (rx shl 8) or (spi2Resumed.read(8L, 4) and 0xffL)
            }
            return rx
        }
        assertEquals(0L, xfer32(0x00000514L))
        val ack = xfer32(0x08108022L)
        assertEquals(0x14L, (ack ushr 6) and 0x1ffL)
        assertEquals(1L, (ack ushr 15) and 1L)
        assertEquals(Frame32SpiPeer.checksum(ack), (ack ushr 1) and 0x1fL)
        val ver = xfer32(0x00000000L)
        assertEquals(0x14L, (ver ushr 6) and 0x1ffL)
        assertEquals(0L, (ver ushr 15) and 1L)
        assertEquals(0x0120L, (ver ushr 16) and 0xffffL)
        assertEquals(Frame32SpiPeer.checksum(ver), (ver ushr 1) and 0x1fL)
    }

    @Test fun continuousFixedMisoAllowsMonotonicSnapshotPromotionAndRejectsDemotion() {
        val ports = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf(
                    "index" to 5,
                    "peerPort" to "",
                    "panel16Replies" to mapOf(
                        "prefix" to listOf(mapOf("tx" to 0x1b40, "rx" to 0xa55a)),
                        "fixedMisoHigh" to false,
                        "exchangeLimit" to 2,
                    ),
                ),
            ),
        )
        val preCtx = TestContext(ports)
        val preBank = SpiFactory().create(
            preCtx,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml", "continuousFixedMiso" to false),
        ) as StatefulComponent
        val preSpi5 = checkNotNull(preCtx.registeredPorts["soc-spi.spi5"] as? ScheduledMmioDevice)
        preSpi5.write(0L, 4, 0x00cfL) // 16-bit
        preSpi5.write(0x10L, 4, 16L)
        preSpi5.write(4L, 4, 0x0aL)
        preCtx.currentTick += 10L
        preSpi5.write(8L, 4, 0x1b40L)
        preCtx.currentTick = preSpi5.nextDeadline()!!
        preSpi5.beforeInstruction(preCtx.currentTick)
        assertEquals(0xa55aL, preSpi5.read(8L, 4))

        val finiteSnap = simulateJsonRoundTrip(preBank.saveSnapshot())

        // Promote false -> true and exceed exchangeLimit (2) cleanly
        val promoCtx = TestContext(ports)
        val promoBank = SpiFactory().create(
            promoCtx,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml", "continuousFixedMiso" to true),
        ) as StatefulComponent
        promoBank.restoreSnapshot(finiteSnap)
        val promoSpi5 = checkNotNull(promoCtx.registeredPorts["soc-spi.spi5"] as? ScheduledMmioDevice)
        repeat(4) {
            promoCtx.currentTick += 10L
            promoSpi5.write(8L, 4, 0L)
            promoCtx.currentTick = promoSpi5.nextDeadline()!!
            promoSpi5.beforeInstruction(promoCtx.currentTick)
            assertEquals(0L, promoSpi5.read(8L, 4))
        }

        // Round-trip continuousFixedMiso snapshot after exceeding exchangeLimit (eventCount=5 > 2)
        val continuousSnap = simulateJsonRoundTrip(promoBank.saveSnapshot())
        val resumedCtx = TestContext(ports)
        val resumedBank = SpiFactory().create(
            resumedCtx,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml", "continuousFixedMiso" to true),
        ) as StatefulComponent
        resumedBank.restoreSnapshot(continuousSnap)
        assertEquals(promoBank.state(), resumedBank.state())

        // Demotion true -> false must still be refused
        assertFailsWith<IllegalArgumentException> { preBank.restoreSnapshot(continuousSnap) }
    }

    @Test fun lenscomSpi0DmaFifoAndReadyIrq166And182WorkAcrossSnapshot() {
        val lensPorts = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 0, "peerPort" to "mount.spi", "requestEnable3Limit" to 16384),
            ),
        )
        class FakeGic(var edgeMode: Boolean) : EnabledEdgeInterruptSink {
            val enabled = mutableSetOf<Int>()
            val asserted = mutableSetOf<Int>()
            val transitions = mutableListOf<Pair<Int, Boolean>>()
            override fun lineEnabled(irq: Int): Boolean = irq in enabled
            override fun edgeState(irq: Int): EdgeInterruptState =
                EdgeInterruptState(edgeMode, irq in asserted, false)
            override fun setLine(irq: Int, high: Boolean) {
                require(irq in setOf(166, 182, 157, 173)) { "Unexpected lenscom IRQ $irq" }
                if (high) asserted += irq else asserted -= irq
                transitions += irq to high
            }
        }
        val gpioLevels = mutableListOf<Triple<Int, Int, Boolean>>()
        val gic = FakeGic(edgeMode = false)
        val context = TestContext(lensPorts)
        context.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        context.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { p, b, h -> gpioLevels += Triple(p, b, h) })
        val bank = SpiFactory().create(
            context,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        val spi0 = checkNotNull(context.registeredPorts["soc-spi.spi0"] as? ScheduledMmioDevice)
        val spi0Dma = checkNotNull(context.registeredPorts["soc-spi.spi0.dma"] as? SpiDmaFifo)
        spi0.write(0x24L, 4, 3L)
        spi0.write(0L, 4, 0x00c7L)
        spi0.write(0x10L, 4, 36L)
        spi0.write(4L, 4, 0x0aL)
        assertEquals(8, spi0Dma.writableBytes())

        // Level-triggered falling LENS_RDY (IRQ 182 -> GPIO(15,1)=false) -> snapshot -> release when disabled
        gic.enabled += 182
        context.currentTick = 100L
        spi0.beforeInstruction(100L)
        assertEquals(listOf(182 to true), gic.transitions)
        assertEquals(listOf(Triple(15, 1, false)), gpioLevels)

        val snap = simulateJsonRoundTrip(bank.saveSnapshot())
        val gic2 = FakeGic(edgeMode = false).apply { asserted += 182 }
        val gpioLevels2 = mutableListOf<Triple<Int, Int, Boolean>>()
        val context2 = TestContext(lensPorts)
        context2.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic2)
        context2.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { p, b, h -> gpioLevels2 += Triple(p, b, h) })
        val bank2 = SpiFactory().create(
            context2,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        bank2.restoreSnapshot(snap)
        val spi0Resumed = checkNotNull(context2.registeredPorts["soc-spi.spi0"] as? ScheduledMmioDevice)
        context2.currentTick = 1_100L
        spi0Resumed.beforeInstruction(1_100L)
        assertEquals(listOf(182 to false), gic2.transitions)

        // Rising LENS_RDY (IRQ 166 -> GPIO(15,1)=true) in edge mode pulses cleanly
        gic2.edgeMode = true
        gic2.enabled += 166
        context2.currentTick = 51_100L
        spi0Resumed.beforeInstruction(51_100L)
        assertEquals(listOf(182 to false, 166 to true, 166 to false), gic2.transitions)
        assertEquals(listOf(Triple(15, 1, true)), gpioLevels2)
    }

    @Test fun lenscomReadyIrq166HoldsOffDuringPreCommandWindowAndFocusMotorBusyThenFiresOnCompletion() {
        val lensPorts = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 0, "peerPort" to "mount.spi", "requestEnable3Limit" to 16384),
            ),
        )
        class FakeGic : EnabledEdgeInterruptSink {
            val enabled = mutableSetOf<Int>()
            val asserted = mutableSetOf<Int>()
            val transitions = mutableListOf<Pair<Int, Boolean>>()
            override fun lineEnabled(irq: Int): Boolean = irq in enabled
            override fun edgeState(irq: Int): EdgeInterruptState =
                EdgeInterruptState(true, irq in asserted, false)
            override fun setLine(irq: Int, high: Boolean) {
                if (high) asserted += irq else asserted -= irq
                transitions += irq to high
            }
        }
        var motorBusy = false
        val fakeLens = object : LensPort, ModularLensPort {
            override fun parameters(): LensParameters = LensParameters("GF45-100mmF4 R LM OIS WR", 45.0, 4.0, 2.0, 0.91, -0.02)
            override val activeLensId: String = "gf45-100"
            override val activeAdapterId: String? = null
            override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
            override val controlContract: LensControlContract = LensControlContract.Manual
            override val lenscomIdentity: LenscomIdentitySpec? = null
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean get() = motorBusy
            override val focusEncoderPosition: Int = 1000
            override fun commandFocusEncoder(targetEncoder: Int) {}
            override fun commandApertureThirdStopIndex(avThirds: Int) {}
        }
        val gpioLevels = mutableListOf<Triple<Int, Int, Boolean>>()
        val gic = FakeGic()
        val context = TestContext(lensPorts)
        context.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        context.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { p, b, h -> gpioLevels += Triple(p, b, h) })
        context.ports.register("lens", "lens", LensPort::class.java, fakeLens)
        val bank = SpiFactory().create(
            context,
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "extra-ports.yaml"),
        ) as StatefulComponent
        val spi0 = checkNotNull(context.registeredPorts["soc-spi.spi0"] as? ScheduledMmioDevice)

        // 1. Firmware arms rising IRQ 166 BEFORE clocking cmd 0x54/0x55/0x56 (DAT S4 0x01362D58):
        //    must NOT fire spuriously in the pre-command window before the focus motor starts.
        gic.enabled += 166
        context.currentTick = 10_000L
        spi0.beforeInstruction(10_000L)
        context.currentTick = 60_000L
        spi0.beforeInstruction(60_000L)
        assertEquals(emptyList(), gic.transitions.filter { it.first == 166 })

        // 2. Focus motor starts slewing -> GPIO(15,1) goes LOW and IRQ 166 stays held off while busy
        motorBusy = true
        context.currentTick = 110_000L
        spi0.beforeInstruction(110_000L)
        assertEquals(Triple(15, 1, false), gpioLevels.last())
        assertEquals(emptyList(), gic.transitions.filter { it.first == 166 })

        // 3. Focus motor reaches target (true -> false) -> GPIO(15,1) rises HIGH and IRQ 166 pulses immediately
        motorBusy = false
        context.currentTick = 160_000L
        spi0.beforeInstruction(160_000L)
        assertEquals(Triple(15, 1, true), gpioLevels.last())
        assertEquals(listOf(166 to true, 166 to false), gic.transitions.filter { it.first == 166 })
        bank.close()
    }
}
