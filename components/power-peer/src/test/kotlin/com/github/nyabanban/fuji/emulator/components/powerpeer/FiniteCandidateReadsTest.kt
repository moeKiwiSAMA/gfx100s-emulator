package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class FiniteCandidateReadsTest {
    private val profile = "finite-candidate-reads-experimental-v3"
    private class Context(val data: Map<String,Any?>, val claims: IntArray = intArrayOf()) : ComponentContext {
        var level = false; var edge = 0L; var endpointOpen = false
        val entries = hashMapOf<String,Any>()
        override val directory = Path.of("unused-finite-candidate-test")
        override val clock = EmulationClock { 0 }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("no ready or event law")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        init {
            entries["soc-gpio.outputs"] = object : GpioOutputPort {
                override fun outputLevel(port: Int, pin: Int) = level
                override fun outputSequence(port: Int, pin: Int) = edge
            }
            entries["endpoint"] = object : PowerRegisterPort {
                override fun connect(owner: String): PowerRegisterConnection {
                    endpointOpen = true
                    return object : PreparedPowerRegisterConnection {
                        override fun registers() = claims.copyOf()
                        override fun read(register: Int, tick: Long) = error("not a physical read")
                        override fun checkWrite(register: Int, value: Int, tick: Long) = error("no delegated write")
                        override fun write(register: Int, value: Int, tick: Long) = error("no delegated write")
                        override fun prepareRead(register: Int, tick: Long) = object : PowerRegisterReadLease {
                            override val value = 0x5678
                            override fun checkCommit(tick: Long) { check(endpointOpen) }
                            override fun commit(tick: Long) { checkCommit(tick) }
                            override fun cancel() {}
                        }
                        override fun close() { endpointOpen = false }
                    }
                }
            }
        }
        override val ports = object : Ports {
            override fun <T:Any> register(owner: String, name: String, type: Class<T>, port: T) { check(entries.putIfAbsent(name,port) == null) }
            override fun <T:Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("not needed")
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String) = data
        fun begin() { if (level) { level = false; edge++ }; level = true; edge++ }
    }
    private fun candidate(selector: Int = 37, values: List<Any> = listOf(0x1234)) = linkedMapOf<String,Any?>(
        "spaceId" to "gfx100s-power-protocol-requests", "requestId" to "request.power-peer.selector0x" + selector.toString(16),
        "selector" to selector, "wordBits" to 16, "confidence" to "HYPOTHESIS", "values" to values,
    )
    private fun input(candidates: List<Any?> = listOf(candidate())) = linkedMapOf<String,Any?>(
        "profile" to profile,
        "chip_select" to mapOf("port" to 12,"pin" to 5,"active_high" to true,"idle_high" to false),
        "inputs" to mapOf("6" to 0x20,"17" to 0), "retained_registers" to listOf(17),
        "disable_writes" to listOf(25), "write_masks" to mapOf("6" to 4), "candidate_reads" to candidates,
    )
    private fun create(c: Context, port: String = ""): HardwareComponent {
        val f = PowerPeerFactory()
        return f.create(c,f.descriptor.settings.associate { it.key to it.default } + mapOf("profile" to profile,"inputFile" to "input.json","registerPort" to port))
    }
    private fun spi(c: Context) = checkNotNull(c.ports.find("power-peer.spi",SpiPeer::class.java))
    private fun bytes(selector: Int, value: Int = 0, write: Boolean = false): List<Int> {
        val prefix = listOf(selector or if (write) 128 else 0,value ushr 8,value and 255)
        return prefix + (prefix.sum().inv() and 255)
    }
    private fun frame(c: Context, selector: Int, tick: Long = 1, value: Int = 0, write: Boolean = false): List<Int> {
        c.begin(); return bytes(selector,value,write).mapIndexed { index, byte -> spi(c).exchange(8,byte,tick+index) }
    }
    private fun state(component: HardwareComponent) = component.state()["scriptedCandidateReads"] as Map<*,*>
    private fun selector(component: HardwareComponent, reg: String = "37") = (state(component)["selectors"] as Map<*,*>)[reg] as Map<*,*>

    @Test fun headerFreezesOneExplicitSampleAndOnlyCompleteFrameConsumesIt() {
        val c = Context(input()); val component = create(c); component.start(); c.begin()
        assertEquals(0,spi(c).exchange(8,37,10)); val pending = state(component)["pending"] as Map<*,*>
        assertEquals(0,pending["sampleIndex"]); assertEquals(0x1234,pending["value"]); assertEquals(10L,pending["headerTick"])
        assertEquals(0,selector(component)["consumed"]); assertEquals(1,selector(component)["remaining"])
        assertEquals(0x12,spi(c).exchange(8,0,11)); assertEquals(0x34,spi(c).exchange(8,0,12))
        assertEquals(0L,state(component)["committedReads"]); assertEquals(0L,component.state()["eventCount"])
        assertEquals((37+0x12+0x34).inv() and 255,spi(c).exchange(8,218,13))
        assertNull(state(component)["pending"]); assertEquals(1,selector(component)["consumed"]); assertEquals(0,selector(component)["remaining"])
        assertEquals(1L,state(component)["committedReads"]); assertEquals(1L,component.state()["eventCount"])
        assertEquals(0x1234,((component.state()["events"] as List<*>).single() as Map<*,*>)["response"])
        component.close()
    }
    @Test fun finiteValuesAreOrderedAndExhaustionIsAtomicWithoutRepeatLast() {
        val c = Context(input(listOf(candidate(values=listOf(0,0xabcd))))); val component = create(c); component.start()
        assertEquals(listOf(0,0,0,218),frame(c,37))
        assertEquals(listOf(0,0xab,0xcd,(37+0xab+0xcd).inv() and 255),frame(c,37,10))
        val before = component.state(); val failure = assertFails { spi(c).exchange(8,37,20) }
        assertEquals("power peer finite candidate input exhausted register37",failure.message); assertEquals(before,component.state())
        assertEquals(2L,state(component)["committedReads"]); assertEquals(0,selector(component)["remaining"]); component.close()
    }
    @Test fun selector03ConsumesExactly96ExplicitSamples() {
        val samples = List(96) { 0xff00 + it }
        val row = candidate(3, samples) + ("requestId" to "request.power-peer.selector0x03")
        val context = Context(input(listOf(row)))
        create(context).use { component ->
            component.start()
            samples.forEachIndexed { index, expected ->
                val response = frame(context, 3, index * 4L)
                assertEquals(expected, (response[1] shl 8) or response[2])
            }
            assertEquals(96, selector(component, "3")["consumed"])
            val before = component.state()
            val failure = assertFailsWith<IllegalStateException> { spi(context).exchange(8, 3, 384) }
            assertEquals("power peer finite candidate input exhausted register3", failure.message)
            assertEquals(before, component.state())
        }
    }

    @Test fun expandedCapacityRequiresTheExactSelector03Identity() {
        val row = candidate(3, List(65) { 0 }) + ("requestId" to "request.power-peer.selector0x03")
        for (change in listOf("spaceId" to "other-space", "requestId" to "request.other", "selector" to 4,
            "values" to List(321) { 0 })) {
            val context = Context(input(listOf(row + change)))
            val failure = assertFailsWith<IllegalArgumentException> { create(context) }
            val limit = if (change.first == "values") 320 else 64
            assertEquals("candidate sequence must contain 1..$limit raw16 samples", failure.message)
            assertNull(context.entries["power-peer.spi"])
        }
    }
    @Test fun checksumErrorCancelsReservationAndRequiresNewChipSelectWithoutConsumption() {
        for (wrong in 0..255) if (wrong != 218) {
            val c = Context(input()); val component = create(c); component.start(); c.begin()
            bytes(37).take(3).forEachIndexed { i,b -> spi(c).exchange(8,b,i.toLong()) }
            assertFails { spi(c).exchange(8,wrong,3) }; assertNull(state(component)["pending"])
            assertEquals(0,selector(component)["consumed"]); assertEquals(0L,state(component)["committedReads"])
            assertEquals(3,component.state()["phase"]); assertEquals(3L,component.state()["wordCount"])
            val after = component.state(); assertFails { spi(c).exchange(8,218,4) }; assertEquals(after,component.state())
            frame(c,37,5); assertEquals(1L,state(component)["committedReads"]); component.close()
        }
    }
    @Test fun everyPartialChipSelectAbortAndClosePreserveTheUnconsumedSample() {
        for (length in 1..3) {
            val c = Context(input()); val component = create(c); component.start(); c.begin()
            bytes(37).take(length).forEachIndexed { i,b -> spi(c).exchange(8,b,i.toLong()) }
            c.level = false; c.edge++; assertEquals(0,spi(c).exchange(8,0,4)); assertNull(state(component)["pending"])
            assertEquals(0,selector(component)["consumed"]); assertEquals(length.toLong(),component.state()["discardedWords"])
            frame(c,37,5); assertEquals(1L,state(component)["committedReads"]); component.close()
            val other = Context(input()); val closing = create(other); closing.start(); other.begin()
            bytes(37).take(length).forEachIndexed { i,b -> spi(other).exchange(8,b,i.toLong()) }
            val handle = spi(other); closing.close(); closing.close(); assertEquals(0,selector(closing)["consumed"])
            assertNull(state(closing)["pending"]); assertEquals(true,state(closing)["closed"])
            val closed = closing.state(); assertFails { handle.exchange(8,218,4) }; assertEquals(closed,closing.state())
        }
    }
    @Test fun candidateWriteRejectsAtHeaderWithoutTransportOrInputMutation() {
        val c = Context(input()); val component = create(c); component.start(); c.begin()
        spi(c).exchange(8,6,1); spi(c).exchange(8,0,2); spi(c).exchange(8,0,3); spi(c).exchange(8,249,4)
        val before = component.state(); assertFails { spi(c).exchange(8,37 or 128,5) }; assertEquals(before,component.state())
        assertEquals(0,selector(component)["consumed"]); component.close()
    }
    @Test fun emptyControlAndMissingNextSelectorKeepOriginalRefusalWithoutDefaultZero() {
        val c = Context(input(emptyList())); val component = create(c); component.start(); c.begin()
        val error = assertFails { spi(c).exchange(8,37,1) }; assertEquals("power peer missing explicit input register37",error.message)
        assertEquals(emptyMap<Any,Any>(),state(component)["selectors"]); assertNull(state(component)["pending"]); assertEquals(0L,state(component)["committedReads"])
        assertEquals(0L,component.state()["wordCount"]); component.close()
        val next = Context(input()); val tested = create(next); tested.start(); frame(next,37)
        val before = tested.state(); assertEquals("power peer missing explicit input register38",assertFails { spi(next).exchange(8,38,5) }.message)
        assertEquals(before,tested.state()); tested.close()
    }
    @Test fun twoSelectorsAreIndependentAndDoNotConsumeDelegatedRtcReads() {
        val c = Context(input(listOf(candidate(37,listOf(1,2)),candidate(38,listOf(3)))) ,intArrayOf(29))
        val component = create(c,"endpoint"); component.start(); frame(c,29)
        assertEquals(0L,state(component)["committedReads"]); assertEquals(0,selector(component)["consumed"])
        frame(c,38,10); frame(c,37,20); frame(c,37,30)
        assertEquals(2,selector(component)["consumed"]); assertEquals(1,selector(component,"38")["consumed"])
        assertEquals(3L,state(component)["committedReads"]); component.close(); assertFalse(c.endpointOpen)
    }
    @Test fun fixedRetainedDisabledMaskedAndDelegatedOwnershipOverlapsAreRejected() {
        val variants = listOf<(MutableMap<String,Any?>)->Unit>(
            { it["inputs"] = mapOf("6" to 0x20,"17" to 0,"37" to 1) },
            { it["disable_writes"] = listOf(25,37) },
            { it["inputs"] = mapOf("6" to 0x20,"17" to 0,"37" to 1); it["retained_registers"] = listOf(17,37) },
            { it["inputs"] = mapOf("6" to 0x20,"17" to 0,"37" to 1); it["write_masks"] = mapOf("6" to 4,"37" to 1) },
        )
        for (change in variants) { val data = input(); change(data); val c = Context(data); assertFails { create(c) }; assertNull(c.entries["power-peer.spi"]) }
        val c = Context(input(),intArrayOf(29,37)); val component = create(c,"endpoint")
        assertFails { component.start() }; assertFalse(c.endpointOpen); component.close()
    }
    @Test fun candidateSchemaRequiresFiniteRaw16ExactIntegersAndNoImplicitFields() {
        val invalid = listOf<(MutableMap<String,Any?>)->Unit>(
            { it.remove("values") }, { it["extra"] = 1 }, { it["spaceId"] = "" }, { it["requestId"] = null },
            { it["selector"] = -1 }, { it["selector"] = 128 }, { it["selector"] = 37.0 }, { it["selector"] = true },
            { it["wordBits"] = 8 }, { it["confidence"] = "HARDWARE" }, { it["values"] = null },
            { it["values"] = emptyList<Int>() }, { it["values"] = List(65) { 0 } }, { it["values"] = listOf(-1) },
            { it["values"] = listOf(65536) }, { it["values"] = listOf(1.0) }, { it["values"] = listOf(true) }, { it["values"] = listOf("0") },
        )
        for (change in invalid) { val candidate = candidate(); change(candidate); val c = Context(input(listOf(candidate))); assertFails { create(c) }; assertNull(c.entries["power-peer.spi"]) }
        for (raw in listOf(null,emptyMap<String,Any>(),listOf(candidate(),candidate()),List(129) { candidate() })) {
            val data = input(); data["candidate_reads"] = raw; val c = Context(data); assertFails { create(c) }; assertNull(c.entries["power-peer.spi"])
        }
        for (key in listOf("write_masks","candidate_reads")) { val data = input(); data.remove(key); assertFails { create(Context(data)) } }
    }
    @Test fun inputsAndContractsAreFrozenAndQueryingThemHasNoSideEffects() {
        val values = mutableListOf<Any>(0x1234); val item = candidate(values=values); val list = mutableListOf<Any?>(item)
        val c = Context(input(list)); val component = create(c); component.start(); values[0] = 0; item["selector"] = 38; list.clear()
        val port = checkNotNull(c.ports.find("power-peer.candidate-inputs",DescribedProtocolCandidateInputs::class.java))
        val before = component.state(); val contracts = port.candidateReadContracts()
        assertEquals(before,component.state()); assertEquals(ProtocolCandidateReadContract("gfx100s-power-protocol-requests","request.power-peer.selector0x25",37,16,"read","scripted-read","HYPOTHESIS"),contracts.single())
        (contracts as MutableList).clear(); assertEquals(1,port.candidateReadContracts().size)
        assertEquals(0x1234,frame(c,37).let { (it[1] shl 8) or it[2] }); component.close()
    }
    @Test fun v3KeepsMaskedRetainedAndDisabledWriteConstraints() {
        val c = Context(input(emptyList())); val component = create(c); component.start()
        frame(c,6,1,0x24,true); assertEquals(0x24,(component.state()["stored"] as Map<*,*>)["6"])
        assertFails { frame(c,6,10,0x25,true) }; assertEquals(0x24,(component.state()["stored"] as Map<*,*>)["6"])
        frame(c,17,20,0xabcd,true); assertEquals(0xabcd,(component.state()["stored"] as Map<*,*>)["17"])
        frame(c,25,30,0,true); assertFails { frame(c,25,40,1,true) }; component.close()
    }
    @Test fun legacyProfilesRejectCandidateSchemaAndExportNoCandidatePort() {
        for (legacy in listOf("checksum-registers-experimental-v1","masked-retained-registers-experimental-v2")) {
            val data = input(emptyList()); data["profile"] = legacy; if (legacy.endsWith("v1")) data.remove("write_masks")
            val c = Context(data); val f = PowerPeerFactory(); val config = f.descriptor.settings.associate { it.key to it.default } + mapOf("profile" to legacy,"inputFile" to "input.json")
            assertFails { f.create(c,config) }; data.remove("candidate_reads")
            val old = f.create(c,config); old.start(); assertNull(c.entries["power-peer.candidate-inputs"]); assertFalse(old.state().containsKey("scriptedCandidateReads")); old.close()
        }
    }
    @Test fun backwardsLeaseCommitAndStaleLeaseNeverConsume() {
        val bank = FiniteCandidateReads(listOf(candidate())); val lease = bank.prepareRead(37,10); val before = bank.state()
        assertFails { lease.checkCommit(9) }; assertFails { lease.commit(9) }; assertEquals(before,bank.state())
        lease.cancel(); lease.cancel(); assertFails { lease.commit(10) }; val next = bank.prepareRead(37,11)
        assertEquals(0x1234,next.value); next.commit(12); val after = bank.state(); assertFails { next.commit(12) }; next.cancel(); assertEquals(after,bank.state())
        bank.close(); assertFails { bank.prepareRead(37,13) }
    }
}
