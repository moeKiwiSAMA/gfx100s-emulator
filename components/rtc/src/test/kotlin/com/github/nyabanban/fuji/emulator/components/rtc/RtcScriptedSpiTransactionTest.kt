package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.powerpeer.PowerPeerFactory
import com.github.nyabanban.fuji.emulator.components.spi.SpiFactory
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

/** TEST: real component classes on a finite in-memory port layout, no guest/native/firmware identity. */
class RtcScriptedSpiTransactionTest {
    private class Rig(scripted: Boolean = true, low: List<Int> = listOf(0x1234, 0xabcd)) : AutoCloseable {
        var tick = 0L; var high = false; var edge = 0L
        val entries = hashMapOf<String, Any>()
        val rtc: HardwareComponent; val peer: HardwareComponent; val spi: HardwareComponent
        val controller: MmioDevice
        private val profile = if (scripted) "gfx100s-rtc-scripted-stage-read-experimental-v2" else "gfx100s-rtc-functional-experimental-v1"
        private val rtcInput = mapOf<String, Any?>("schema" to if (scripted) 2 else 1, "profile" to profile,
            "ticksPerSecond" to 10L, "initialSeconds" to 0x12345678L, "initialControl" to 3,
            "counterRunning" to false, "loadDelayTicks" to 5L) +
            if (scripted) mapOf("stageReadInputs" to mapOf("29" to low, "30" to listOf(0x5678))) else emptyMap()
        private val peerInput = mapOf<String, Any?>("profile" to "checksum-registers-experimental-v1",
            "chip_select" to mapOf("port" to 12, "pin" to 5, "active_high" to true, "idle_high" to false),
            "inputs" to mapOf("0" to 0), "retained_registers" to emptyList<Int>(), "disable_writes" to emptyList<Int>())
        private val context = object : ComponentContext {
            override val directory = Path.of("unused-rtc-scripted-spi-test")
            override val clock = EmulationClock { tick }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("No invented interrupt/event")
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) { check(entries.putIfAbsent(name, port) == null) }
                override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
                override fun removeOwner(owner: String) = error("Not needed")
            }
            override fun dataPath(relative: String) = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = when (relative) { "rtc.json" -> rtcInput; "peer.json" -> peerInput; else -> error("Unexpected data") }
        }
        init {
            entries["soc-gpio.outputs"] = object : GpioOutputPort {
                override fun outputLevel(port: Int, pin: Int) = high
                override fun outputSequence(port: Int, pin: Int) = edge
            }
            fun make(f: ComponentFactory, values: Map<String, Any>) = f.create(context, f.descriptor.settings.associate { it.key to it.default } + values)
            rtc = make(RtcFactory(), mapOf("profile" to profile, "inputFile" to "rtc.json"))
            peer = make(PowerPeerFactory(), mapOf("profile" to "checksum-registers-experimental-v1", "inputFile" to "peer.json", "registerPort" to "rtc.registers"))
            peer.start()
            spi = make(SpiFactory(), mapOf("profile" to "spi-fifo-peer-experimental-v2", "peerPort" to "power-peer.spi"))
            controller = entries.getValue("soc-spi.spi1") as MmioDevice
            controller.write(0, 4, 7); controller.write(0x10, 4, 2); controller.write(4, 4, 2)
        }
        fun begin() { if (high) { high = false; edge++ }; high = true; edge++ }
        fun queue(word: Int) { controller.write(8, 4, word.toLong()); tick += 16 }
        fun complete(): Int { controller.beforeInstruction(tick); return controller.read(8, 4).toInt() }
        fun word(value: Int): Int { queue(value); return complete() }
        fun discardPendingWord() { controller.write(4, 4, 0); controller.write(4, 4, 2) }
        fun frame(selector: Int): List<Int> { begin(); return listOf(selector, 0, 0, selector.inv() and 255).map(::word) }
        fun script() = rtc.state()["scriptedStageReads"] as Map<*, *>
        fun next(selector: Int) = (((script()["selectors"] as Map<*, *>)[selector.toString()] as Map<*, *>)["nextIndex"] as Int)
        override fun close() { peer.close(); rtc.close(); spi.close() }
    }
    @Test fun completeReadFreezesAtHeaderAndConsumesOnlyAtChecksumWithExactJournal() {
        Rig().use { r ->
            r.begin(); assertEquals(0, r.word(29)); assertEquals(0, r.next(29)); assertEquals(0, r.script()["commitCount"])
            assertEquals("prepared", r.peer.state()["preparedReadState"])
            assertEquals(0x12, r.word(0)); assertEquals(0x34, r.word(0)); assertEquals(0, r.next(29))
            assertEquals((29 + 0x12 + 0x34).inv() and 255, r.word(29.inv() and 255))
            assertEquals(1, r.next(29)); assertEquals(1, r.script()["commitCount"]); assertEquals(1L, r.peer.state()["eventCount"])
            assertEquals(mapOf("sequence" to 1, "selector" to 29, "inputIndex" to 0, "value" to 0x1234,
                "prepareTick" to 16L, "commitTick" to 64L, "confidence" to "HYPOTHESIS"), (r.script()["commits"] as List<*>).single())
            assertNull((r.rtc.state()["registers"] as Map<*, *>)["stageLow"])
        }
    }
    @Test fun badChecksumCancelsWithoutRxAndSameCsCannotCommitTheOldReply() {
        Rig().use { r ->
            r.begin(); r.word(29); r.word(0); r.word(0)
            r.queue((29.inv() and 255) xor 1); val spiBefore = r.spi.state()
            assertFails { r.complete() }; assertEquals(spiBefore, r.spi.state())
            assertEquals(0, r.next(29)); assertEquals(0, r.script()["commitCount"]); assertEquals(1L, r.script()["cancelCount"])
            assertEquals("cancelled-awaiting-cs", r.peer.state()["preparedReadState"]); assertEquals(0L, r.peer.state()["eventCount"])
            r.discardPendingWord(); r.queue(29.inv() and 255); val retryBefore = r.spi.state()
            assertFails { r.complete() }; assertEquals(retryBefore, r.spi.state()); assertEquals(0, r.next(29))
            r.discardPendingWord(); val reply = r.frame(29)
            assertEquals(listOf(0, 0x12, 0x34, (29 + 0x12 + 0x34).inv() and 255), reply)
            assertEquals(1, r.next(29)); assertEquals(1, r.script()["commitCount"]); assertEquals(1L, r.peer.state()["eventCount"])
            assertEquals(7L, r.spi.state()["eventCount"])
        }
    }
    @Test fun csAbortAndCloseCancelWithoutConsumingAndRetainedSpiCannotComplete() {
        Rig().use { r ->
            r.begin(); r.word(29); r.word(0); assertEquals(0, r.next(29))
            r.frame(29); assertEquals(1, r.next(29)); assertEquals(1L, r.script()["cancelCount"])
            assertEquals(2L, r.peer.state()["discardedWords"])
            r.begin(); r.word(29); assertEquals(1, r.next(29)); r.peer.close()
            assertEquals(2L, r.script()["cancelCount"]); assertEquals(1, r.next(29)); assertNull(r.script()["pending"])
            r.queue(0); val spiBefore = r.spi.state(); assertFails { r.complete() }; assertEquals(spiBefore, r.spi.state())
            assertEquals(1, r.script()["commitCount"])
        }
    }
    @Test fun exhaustedInputRefusesBeforeHeaderRxAndCannotRepeatLastValue() {
        Rig(low = listOf(0x1234)).use { r ->
            r.frame(29); val committed = r.script(); assertEquals(1, r.next(29))
            r.begin(); r.queue(29); val before = r.spi.state()
            assertFails { r.complete() }; assertEquals(before, r.spi.state()); assertEquals(committed, r.script())
            assertFails { r.complete() }; assertEquals(before, r.spi.state()); assertEquals(1L, r.peer.state()["eventCount"])
            r.discardPendingWord(); assertEquals(0x56, r.frame(30)[1]); assertEquals(1, r.next(30))
        }
    }
    @Test fun legacyV1StillRefusesStagedReadAndKeepsOriginalStateSchema() {
        Rig(scripted = false).use { r ->
            assertFalse(r.rtc.state().containsKey("scriptedStageReads")); assertFalse(r.peer.state().containsKey("preparedReadState"))
            assertEquals(listOf(0, 0x56, 0x78, (35 + 0x56 + 0x78).inv() and 255), r.frame(35))
            r.begin(); r.queue(29); val before = r.spi.state(); assertFails { r.complete() }; assertEquals(before, r.spi.state())
            assertEquals(1L, r.peer.state()["eventCount"]); assertFalse(r.peer.state().containsKey("preparedReadState"))
        }
    }
}
