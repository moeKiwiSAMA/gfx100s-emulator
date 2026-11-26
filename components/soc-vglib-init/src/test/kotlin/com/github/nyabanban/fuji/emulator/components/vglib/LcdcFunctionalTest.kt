package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LcdcFunctionalTest {
    internal class Context:ComponentContext {
        val entries=HashMap<String,Any>();var tick=0L;var failLocation=false
        override val directory=Path.of(".")
        override val ports=object:Ports {
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T?=if(name=="cpu.execution-location")type.cast(ExecutionLocationSource{if(failLocation)error("location failed")else ExecutionLocation(1,0x5afe0)})else entries[name]?.let(type::cast)
            override fun removeOwner(owner:String){entries.clear()}
        }
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel {override fun publish(event:ComponentEvent)=error("functional bank must not publish effects");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String)=when(relative) {
            "bank"->mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to List(18){mapOf("offset" to 0,"readValue" to 0,"writeValue" to 0)})
            else->error("unexpected data file $relative")
        }
    }
    private fun unchanged(p:LcdcFunctional,action:()->Unit) {val before=p.state();assertFails{action()};assertEquals(before,p.state())}

    @Test fun completionBitAppearsOnlyAfterGuestZeroWrite() {
        val p=LcdcFunctional(Context(),1)
        assertEquals("soc-vglib-init.lcdc1-functional",p.port)
        assertEquals(0L,p.read(0,4));assertEquals(false,p.state()["resetCompleted"])
        p.write(0,4,0)
        repeat(5){assertEquals(1L,p.read(0,4))}
        val s=p.state()
        assertEquals(true,s["resetCompleted"]);assertEquals(1L,s["resetWrites"]);assertEquals(5L,s["resetPolls"])
        // one pre-reset read, the write and only the first completion poll carry coordinates
        assertEquals(3,s["transactionCount"]);assertEquals(7L,s["observedAccesses"]);assertEquals(true,s["eventLogTruncated"])
        assertEquals("HYPOTHESIS",s["confidence"])
    }

    @Test fun otherWordsAreRetainedAndIndependentPerInstance() {
        val c=Context();val a=LcdcFunctional(c,0);val b=LcdcFunctional(c,1)
        assertEquals(0L,a.read(0x600,4))
        a.write(0x600,4,0x15);a.write(4,4,1);a.write(0xffc,4,0xffffffffL)
        assertEquals(0x15L,a.read(0x600,4));assertEquals(1L,a.read(4,4));assertEquals(0xffffffffL,a.read(0xffc,4))
        assertEquals(0L,b.read(0x600,4));assertEquals(false,a.state()["resetCompleted"])
        // a nonzero control write is retained but does not arm completion
        a.write(0,4,2);assertEquals(2L,a.read(0,4));a.write(0,4,0);assertEquals(1L,a.read(0,4))
        assertEquals(listOf(0,4,0x600,0xffc),(a.state()["touchedWords"] as List<*>).map{(it as Map<*,*>)["offset"]})
    }

    @Test fun refusedAccessesLeaveStateUnchanged() {
        val c=Context();val p=LcdcFunctional(c,0);p.write(8,4,3)
        for(offset in listOf(-4L,1L,2L,0x1000L,0x1004L)) {unchanged(p){p.read(offset,4)};unchanged(p){p.write(offset,4,0)}}
        for(size in listOf(0,1,2,8)) {unchanged(p){p.read(0,size)};unchanged(p){p.write(0,size,0)}}
        for(value in listOf(-1L,0x100000000L))unchanged(p){p.write(0,4,value)}
        c.tick=-1;unchanged(p){p.write(0,4,0)};unchanged(p){p.read(8,4)}
        c.tick=5;c.failLocation=true;unchanged(p){p.write(0,4,0)};unchanged(p){p.read(8,4)}
        c.failLocation=false;p.write(0,4,0);assertEquals(1L,p.read(0,4));assertEquals(3L,p.read(8,4))
        assertFails{LcdcFunctional(c,2)}
    }

    @Test fun factorySelectsTwoBanksAndRefusesFiniteSnapshotTogether() {
        val c=Context()
        val component=VglibInitFactory().create(c,mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,"inputFile" to "bank","controlWordInputFile" to "","lcdcSnapshotInputFile" to ""))
        assertEquals(listOf("soc-vglib-init.registers","soc-vglib-init.lcdc0-functional","soc-vglib-init.lcdc1-functional"),component.additionalMmioPorts())
        val banks=component.state()["lcdcFunctional"] as List<*>
        assertEquals(listOf(0,1),banks.map{(it as Map<*,*>)["instance"]})
        assertNull(component.state()["lcdcSnapshot"])
        assertNull(VglibInitFactory().create(Context(),mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank","controlWordInputFile" to "","lcdcSnapshotInputFile" to "")).state()["lcdcFunctional"])
    }

    @Test fun disabledAndFiniteProfilesExportOnlyTheirSelectedPorts() {
        val factory = VglibInitFactory()
        for (profile in listOf("disabled", VglibInitFactory.PROFILE)) {
            val context = Context()
            val config = mapOf("profile" to profile, "inputFile" to if (profile == "disabled") "" else "bank")
            val component = factory.create(context, config)
            assertEquals(component.additionalMmioPorts().toSet(), context.entries.keys)
            assertTrue(component.interruptRoutes().isEmpty())
            assertFailsWith<IllegalArgumentException> { factory.create(Context(), config + ("irq118Tick" to 42L)) }
            assertFailsWith<IllegalArgumentException> { factory.create(Context(), config + ("irq117Tick" to 42L)) }
        }
    }

    @Test fun phaseEdgeCanResumeLegacyBankAndCoexistWithFrameClock() {
        val factory = VglibInitFactory()
        val original = mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE, "inputFile" to "bank",
            "irq118Tick" to 10L, "frameSyncStartTick" to 30L)
        val context = Context()
        var enabled = false
        var status = EdgeInterruptState(true, false, false)
        val pulses = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : EnabledEdgeInterruptSink {
            override fun lineEnabled(irq: Int): Boolean { assertEquals(117, irq); return enabled }
            override fun edgeState(irq: Int) = if (irq == 117) status else EdgeInterruptState(true, false, false)
            override fun setLine(irq: Int, high: Boolean) { pulses += irq to high }
        }
        context.entries["soc-gic.lines"] = sink
        val previous = factory.create(context, original) as StatefulComponent
        val bank = context.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice
        bank.write(4, 4, 73)
        context.tick = 10L
        bank.beforeInstruction(context.tick)
        val legacy = previous.saveSnapshot()
        assertFalse("irq117" in legacy)
        val selected = original + ("irq117Tick" to 20L)
        assertFailsWith<IllegalArgumentException> { factory.create(Context(), selected).start() }
        val resumed = Context().also { it.tick = context.tick; it.entries["soc-gic.lines"] = sink }
        val component = factory.create(resumed, selected)
        component.start()
        assertEquals(listOf(117..117, 118..118), component.interruptRoutes().map { it.lines })
        val snapshot = component as StatefulComponent
        snapshot.restoreSnapshot(legacy)
        val device = resumed.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice
        assertEquals(legacy["lcdcFunctional"], snapshot.saveSnapshot()["lcdcFunctional"])
        assertEquals(legacy["irq118"], snapshot.saveSnapshot()["irq118"])
        assertEquals(20L, device.nextDeadline())
        device.beforeInstruction(19L)
        val armed = snapshot.saveSnapshot()
        snapshot.restoreSnapshot(armed)
        resumed.tick = 20L
        for ((mask, line) in listOf(false to status, true to status.copy(edge = false),
            true to status.copy(high = true), true to status.copy(pending = true))) {
            enabled = mask
            status = line
            assertFailsWith<IllegalArgumentException> { device.beforeInstruction(resumed.tick) }
            assertEquals(armed, snapshot.saveSnapshot())
            assertEquals(listOf(118 to true, 118 to false), pulses)
        }
        enabled = true
        status = EdgeInterruptState(true, false, false)
        device.beforeInstruction(resumed.tick)
        val spent = snapshot.saveSnapshot()
        assertEquals(mapOf("deadline" to 20L, "firedAt" to 20L), spent["irq117"])
        snapshot.restoreSnapshot(spent)
        device.beforeInstruction(resumed.tick)
        for (bad in listOf(legacy, armed, spent - "irq117",
            spent + ("irq117" to mapOf("deadline" to 19L, "firedAt" to 20L)),
            spent + ("irq117" to mapOf("deadline" to 20L, "firedAt" to 21L)))) {
            assertFailsWith<IllegalArgumentException> { snapshot.restoreSnapshot(bad) }
            assertEquals(spent, snapshot.saveSnapshot())
        }
        assertFailsWith<IllegalArgumentException> { previous.restoreSnapshot(spent) }
        assertEquals(30L, device.nextDeadline())
        resumed.tick = 30L
        device.beforeInstruction(resumed.tick)
        assertEquals(listOf(118, 117, 118).flatMap { listOf(it to true, it to false) }, pulses)
    }

    @Test fun periodicEdgesRemainBoundedAcrossOrdinaryRestoreEvenWhenPending() {
        val factory = VglibInitFactory()
        val config = mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE, "inputFile" to "bank", "irq118Tick" to 42L)
        val context = Context()
        val pulses = mutableListOf<Boolean>()
        var pending = false
        val sink = object : EdgeInterruptSink {
            override fun edgeState(irq: Int) = EdgeInterruptState(true, false, pending)
            override fun setLine(irq: Int, high: Boolean) {
                assertEquals(118, irq)
                pulses += high
                if (high) pending = true
            }
        }
        context.entries["soc-gic.lines"] = sink
        val original = factory.create(context, config) as StatefulComponent
        context.tick = 42
        (context.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice).beforeInstruction(42)
        val legacy = original.saveSnapshot()
        val resumed = Context().also { it.entries["soc-gic.lines"] = sink; it.tick = 42 }
        val selected = config + ("frameSyncStartTick" to 100L)
        val component = factory.create(resumed, selected) as StatefulComponent
        val device = resumed.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice
        component.restoreSnapshot(legacy)
        device.beforeInstruction(99)
        repeat(32) { index ->
            resumed.tick = 100L + index * 2_000_000L
            assertEquals(resumed.tick, device.nextDeadline())
            device.beforeInstruction(resumed.tick)
            val saved = component.saveSnapshot()
            val sync = saved["frameSync"] as Map<*, *>
            assertEquals(index + 1, (sync["edgeTicks"] as List<*>).size)
            assertFailsWith<IllegalArgumentException> { component.restoreSnapshot(saved - "frameSync") }
            assertFailsWith<IllegalArgumentException> { component.restoreSnapshot(saved + ("frameSync" to sync + ("edgeTicks" to emptyList<Long>()))) }
            assertFailsWith<IllegalArgumentException> { component.restoreSnapshot(legacy) }
            component.restoreSnapshot(saved)
            device.beforeInstruction(resumed.tick)
        }
        assertNull(device.nextDeadline())
        assertEquals(List(33) { listOf(true, false) }.flatten(), pulses)
        val spent = component.saveSnapshot()
        val resume = 773664282L
        val extendedContext = Context().also { it.entries["soc-gic.lines"] = sink; it.tick = resumed.tick }
        fun branch(deadline: Long) = factory.create(extendedContext, selected + ("frameSyncResumeTick" to deadline)) as StatefulComponent
        assertFailsWith<IllegalArgumentException> { branch(64_000_099L) }
        val extended = branch(resume)
        val clock = extendedContext.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice
        extended.restoreSnapshot(spent)
        assertEquals(resume, clock.nextDeadline())
        val armed = extended.saveSnapshot()
        val settings = armed["settings"] as Map<*, *>
        val schedule = armed["frameSync"] as Map<*, *>
        for (bad in listOf(armed + ("settings" to settings - "frameSyncResumeTick"),
            armed + ("frameSync" to schedule + ("resumeTick" to resume + 1)),
            armed + ("frameSync" to schedule + ("capacity" to 32)))) {
            assertFailsWith<IllegalArgumentException> { extended.restoreSnapshot(bad) }
            assertEquals(armed, extended.saveSnapshot())
        }
        repeat(32) { index ->
            extendedContext.tick = resume + index * 2_000_000L
            assertEquals(extendedContext.tick, clock.nextDeadline())
            clock.beforeInstruction(extendedContext.tick)
            extended.restoreSnapshot(extended.saveSnapshot())
        }
        assertNull(clock.nextDeadline())
        assertEquals(65 * 2, pulses.size)
        assertFailsWith<IllegalArgumentException> { extended.restoreSnapshot(spent) }
        assertFailsWith<IllegalArgumentException> { component.restoreSnapshot(extended.saveSnapshot()) }
    }

    @Test fun legacyFunctionalSnapshotArmsOneOwnerEdgeAndSpentSnapshotsStaySpent() {
        val factory = VglibInitFactory()
        val config = mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE, "inputFile" to "bank")
        val selected = config + ("irq118Tick" to 42L)
        val oldContext = Context()
        val legacy = factory.create(oldContext, config) as StatefulComponent
        (oldContext.entries.getValue(LcdcFunctional.port(1)) as MmioDevice).write(4, 4, 1)
        val oldSnapshot = legacy.saveSnapshot()
        val context = Context()
        var status = EdgeInterruptState(false, false, false)
        val pulses = mutableListOf<Pair<Int,Boolean>>()
        context.entries["soc-gic.lines"] = object : EdgeInterruptSink {
            override fun edgeState(irq: Int): EdgeInterruptState { assertEquals(118, irq); return status }
            override fun setLine(irq: Int, high: Boolean) { pulses.add(irq to high) }
        }
        val component = factory.create(context, selected)
        component.start()
        assertEquals(listOf(InterruptRoute("soc-gic.lines",118..118)), component.interruptRoutes())
        val snapshot = component as StatefulComponent
        val device = context.entries.getValue(LcdcFunctional.port(1)) as ScheduledMmioDevice
        assertFalse(context.entries.getValue(LcdcFunctional.port(0)) is ScheduledMmioDevice)
        context.tick = 41
        snapshot.restoreSnapshot(oldSnapshot)
        assertEquals(1L,device.read(4,4))
        assertEquals(42L,device.nextDeadline())
        device.beforeInstruction(41)
        val armed = snapshot.saveSnapshot()
        snapshot.restoreSnapshot(armed)
        context.tick = 42
        for (bad in listOf(status, EdgeInterruptState(true,true,false), EdgeInterruptState(true,false,true))) {
            status = bad
            assertFailsWith<IllegalArgumentException> { device.beforeInstruction(42) }
            assertEquals(armed, snapshot.saveSnapshot())
        }
        assertTrue(pulses.isEmpty())
        status = EdgeInterruptState(true,false,false)
        device.beforeInstruction(42)
        assertEquals(listOf(118 to true,118 to false),pulses)
        assertNull(device.nextDeadline())
        val spent = snapshot.saveSnapshot()
        assertEquals(42L,(spent["irq118"] as Map<*, *>)["firedAt"])
        snapshot.restoreSnapshot(spent)
        device.beforeInstruction(50)
        assertEquals(2,pulses.size)
        for (bad in listOf(armed, oldSnapshot, spent - "irq118",
            spent + ("irq118" to mapOf("deadline" to 42L, "firedAt" to 41L)),
            spent + ("irq118" to mapOf("deadline" to 42L, "firedAt" to 43L)))) {
            assertFailsWith<IllegalArgumentException> { snapshot.restoreSnapshot(bad) }
            assertEquals(spent,snapshot.saveSnapshot())
        }
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(spent) }
        assertEquals(oldSnapshot,legacy.saveSnapshot())
        val missing = factory.create(Context(), selected)
        assertFailsWith<IllegalArgumentException> { missing.start() }
    }
}
