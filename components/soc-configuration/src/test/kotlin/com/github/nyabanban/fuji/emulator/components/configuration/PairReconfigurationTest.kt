package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class PairReconfigurationTest {
    private class Context : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>()
        override val directory = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T:Any> register(owner:String,name:String,type:Class<T>,port:T) { check(claims.putIfAbsent(name,port)==null) }
            override fun <T:Any> find(name:String,type:Class<T>):T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner:String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event:ComponentEvent) { error("No IRQ/hardware event is qualified") }
            override fun subscribe(listener:(ComponentEvent)->Unit) = AutoCloseable {}
        }
        override fun dataPath(relative:String) = directory.resolve(relative)
        override fun readData(relative:String):Map<String,Any?> = mapOf("schema" to 1,"profile" to BaseParameterState.PROFILE,
            "initialPairs" to List(13) { listOf(0x80000000L+it,0xffffffffL-it) },"initialStatus" to List(13) { 0 },
            "requestTicks" to List(13) { 5 },"settleTicks" to List(13) { 7 })
    }
    private class Fixture(profile:String=PairReconfigurationState.PROFILE, enabled:Boolean=true) {
        val c = Context()
        val factory = ConfigurationFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf("profile" to profile,
            "baseProfile" to if(enabled)BaseParameterState.PROFILE else "disabled", "baseInputFile" to if(enabled)"explicit.json" else "")
        val h = factory.create(c,config)
        fun p(group:Int) = c.ports.find("soc-configuration.group$group",MmioDevice::class.java)!!
        fun step(tick:Long) { c.tick=tick; (0..19).forEach { p(it).beforeInstruction(tick) } }
        fun base():Map<*,*> = h.state()["baseParameters"] as Map<*,*>
        fun pair():Map<*,*> = h.state()["reconfiguration"] as Map<*,*>
        fun field(name:String):Any = h.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(h)
        fun open(group:Int=10) { p(group).write(0x80,4,5); step(c.tick+8) }
        fun fresh(group:Int=10,a:Long=0xfedcba98L,b:Long=0x76543210L) { p(group).write(0,4,a);p(group).write(8,4,b) }
        fun rejects(block:()->Unit) { val before=h.state();assertFails { block() };assertEquals(before,h.state()) }
    }
    private fun setCounter(target:Any,name:String,value:Long) { target.javaClass.getDeclaredField(name).apply { isAccessible=true }.setLong(target,value) }
    private fun contexts(f:Fixture) = f.pair()["contexts"] as List<*>
    private fun phase(f:Fixture,slot:Int=0) = (contexts(f)[slot] as Map<*,*>)["phase"]

    @Test fun fiveCreatesARealGenerationAndOpenDeadlineWithoutChangingAnyAppliedState() {
        val f=Fixture();val p=f.p(10);val oldBase=f.base();val oldNine=f.h.state()["applied"]
        p.write(0x80,4,5)
        assertEquals(5L,p.read(0x80,4));assertEquals(8L,(p as ScheduledMmioDevice).nextDeadline())
        assertEquals(oldBase,f.base());assertEquals(oldNine,f.h.state()["applied"]);assertEquals(listOf(1L,0L,0L),f.pair()["generations"])
        val context=contexts(f)[0] as Map<*,*>;assertEquals((oldBase["applied"] as List<*>)[3],context["originalPair"])
        f.step(7);assertEquals("preparing",phase(f));assertEquals(5L,p.read(0x80,4))
        f.step(8);assertEquals("open",phase(f));assertEquals(0L,p.read(0x80,4));assertEquals(oldBase,f.base());assertNull(p.nextDeadline())
        assertEquals(false,f.pair()["irq"]);assertEquals("UNKNOWN",f.pair()["physicalFunction"])
    }
    @Test fun sixNecessarilyConsumesOpenContextAndTwoNewWordsAtExplicitRequestAndSettleDeadlines() {
        for((slot,group) in listOf(10,16,19).withIndex()) {
            val f=Fixture();val p=f.p(group);val index=PairReconfigurationState.baseIndices[slot];val old=(f.base()["applied"] as List<*>)[index]
            f.open(group);f.fresh(group,0x12340000L+group,0x80000000L+group);p.write(0x80,4,6)
            assertEquals("request",phase(f,slot));assertEquals(6L,p.read(0x80,4));assertEquals(old,(f.base()["applied"] as List<*>)[index]);assertEquals(1L,(f.base()["status"] as List<*>)[index])
            assertEquals(13L,(p as ScheduledMmioDevice).nextDeadline());f.step(12);assertEquals(6L,p.read(0x80,4));f.step(13)
            assertEquals("settling",phase(f,slot));assertEquals(0L,p.read(0x80,4));assertEquals(20L,p.nextDeadline());assertEquals(old,(f.base()["applied"] as List<*>)[index])
            f.step(19);assertNotNull(contexts(f)[slot]);f.step(20);assertNull(contexts(f)[slot]);assertEquals(0L,(f.base()["status"] as List<*>)[index])
            assertEquals(listOf(0x12340000L+group,0x80000000L+group),(f.base()["applied"] as List<*>)[index]);assertEquals(listOf("begin","open","submit6","consume"),(f.pair()["events"] as List<*>).map { (it as Map<*,*>)["operation"] })
            f.rejects { p.write(0x80,4,6) };f.open(group);assertEquals(2L,(f.pair()["generations"] as List<*>)[slot])
        }
    }
    @Test fun staleWrittenBitsRepeatedFirstWordAndOtherGroupWritesCannotBypassFreshPairRequirement() {
        val f=Fixture();val p=f.p(10);f.fresh();p.write(0x80,4,3);f.step(12)
        f.rejects { p.write(0x80,4,6) };f.open();f.rejects { p.write(0x80,4,6) }
        p.write(0,4,17);p.write(0,4,18);f.fresh(16,31,47);f.rejects { p.write(0x80,4,6) };f.rejects { f.p(16).write(0x80,4,6) }
        p.write(8,4,0xeeeeeeeeL);p.write(0x80,4,6);f.step(32)
        assertEquals(listOf(18L,0xeeeeeeeeL),(f.base()["applied"] as List<*>)[3]);assertEquals(listOf(0x80000009L,0xfffffff6L),(f.base()["applied"] as List<*>)[9])
    }
    @Test fun differentTransactionsLatchDifferentGuestPairsAndKeepOriginalPairByGeneration() {
        val f=Fixture();f.open();f.fresh(a=91,b=117);f.p(10).write(0x80,4,6);f.step(20)
        f.open();val t=contexts(f)[0] as Map<*,*>;assertEquals(listOf(91L,117L),t["originalPair"])
        f.fresh(a=0xffffffffL,b=0);f.p(10).write(0x80,4,6);f.step(40)
        assertEquals(listOf(0xffffffffL,0L),(f.base()["applied"] as List<*>)[3]);assertNull(contexts(f)[0])
    }
    @Test fun phaseReissueBusyDirectionWidthReservedAndAllOtherGroupsRefuseAtomically() {
        val f=Fixture();val p=f.p(10)
        for(group in (0..19)-listOf(10,16,19))for(command in listOf(5L,6L))f.rejects { f.p(group).write(0x80,4,command) }
        p.write(0x80,4,5)
        for(offset in listOf(0L,8L,0x40L,0x44L))f.rejects { p.write(offset,4,1) }
        for(command in listOf(2L,3L,5L,6L))f.rejects { p.write(0x80,4,command) }
        f.step(8);f.fresh()
        for(command in listOf(2L,3L,5L))f.rejects { p.write(0x80,4,command) }
        for((offset,size) in listOf(0L to 1,0L to 8,1L to 4,4L to 4,0x80L to 8,0x7cL to 4,0xf0L to 4)) {
            f.rejects { p.write(offset,size,6) };f.rejects { p.read(offset,size) }
        }
        f.rejects { p.write(0,4,-1) };f.rejects { p.write(8,4,0x100000000L) }
        val status=f.c.ports.find("soc-configuration.base-status3",MmioDevice::class.java)!!
        f.rejects { status.write(0,4,1) };f.rejects { status.read(1,4) }
        p.write(0x80,4,6)
        for(tick in listOf(8L,13L)) { f.step(tick);for(command in listOf(2L,3L,5L,6L))f.rejects { p.write(0x80,4,command) };f.rejects { p.write(0,4,7) };f.rejects { p.write(0x40,4,7) } }
        f.step(20);p.write(0x40,4,7);p.write(0x80,4,2);f.rejects { p.write(0x80,4,5) };f.rejects { p.write(0x80,4,6) }
    }
    @Test fun completeSoftwareOrderingAllowsIndependentGroupNineTransactionsAndPostSixCommandTwo() {
        val f=Fixture();val p=f.p(10);val other=f.p(9)
        for(offset in listOf(0x40L,0x44L)) { p.write(offset,4,0);p.write(0x80,4,2);f.step(f.c.tick+8) }
        f.open();f.fresh(9,0xabc,0xdef);other.write(0x80,4,3);f.step(f.c.tick+12)
        other.write(0x40,4,0x19a);other.write(0x80,4,2);f.step(f.c.tick+8)
        f.fresh(10,0x333,0x777);p.write(0x80,4,6);f.step(f.c.tick+12)
        for((offset,value) in listOf(0x44L to 0x1a5L,0x40L to 0x88L)) { p.write(offset,4,value);p.write(0x80,4,2);f.step(f.c.tick+8) }
        assertEquals(listOf(0x333L,0x777L),(f.base()["applied"] as List<*>)[3]);assertEquals(listOf(0xabcL,0xdefL),(f.base()["applied"] as List<*>)[2]);assertNull(contexts(f)[0])
        val flat=f.h.state()["applied"] as List<*>;val start=(f.h.state()["groupCounts"] as List<*>).map { it as Int }.take(10).sum();assertEquals(listOf(0x88L,0x1a5L),flat.subList(start,start+2))
    }
    @Test fun legacyProfileRejectsFiveSixAndPreservesCommandTwoThreeReissueStatesExactly() {
        val old=Fixture("configuration-command2-experimental-v1");val fresh=Fixture()
        for(f in listOf(old,fresh)) { val p=f.p(10);p.write(0x40,4,29);p.write(0x80,4,2);p.write(0x80,4,2);f.step(8);f.fresh(a=23,b=41);p.write(0x80,4,3);p.write(0x80,4,3);f.step(13);p.write(0x80,4,3);f.step(20) }
        assertEquals(old.h.state().filterKeys { it!="profile" },fresh.h.state().filterKeys { it!="profile"&&it!="reconfiguration" })
        for(group in 0..19)for(command in listOf(5L,6L))old.rejects { old.p(group).write(0x80,4,command) }
        assertFalse("reconfiguration" in old.h.state())
    }
    @Test fun explicitBaseInputsAreMandatoryAndDefaultDeclarationStaysLegacy() {
        val factory=ConfigurationFactory();assertEquals("configuration-command2-experimental-v1",factory.descriptor.settings.single { it.key=="profile" }.default)
        assertFails { Fixture(enabled=false) }
        val f=Fixture("configuration-command2-experimental-v1",false);assertEquals(false,f.h.state()["baseParametersEnabled"]);assertTrue(f.h.additionalMmioPorts().isEmpty())
    }
    @Test fun beginDeadlineGenerationAndEventOverflowPreflightBeforeAnyMutation() {
        run { val f=Fixture();f.c.tick=Long.MAX_VALUE-7;f.rejects { f.p(10).write(0x80,4,5) } }
        run { val f=Fixture();val r=f.field("reconfiguration");(r.javaClass.getDeclaredField("generations").apply { isAccessible=true }.get(r) as LongArray)[0]=Long.MAX_VALUE;f.rejects { f.p(10).write(0x80,4,5) } }
        run { val f=Fixture();setCounter(f.field("reconfiguration"),"eventCount",Long.MAX_VALUE);f.rejects { f.p(10).write(0x80,4,5) } }
        run { val f=Fixture();f.p(10).write(0x80,4,5);setCounter(f.field("reconfiguration"),"eventCount",Long.MAX_VALUE);f.c.tick=8;f.rejects { f.p(10).beforeInstruction(8) } }
    }
    @Test fun commitDeadlineBaseAndContextCountersAndSettleOverflowKeepTheWholeTransactionAtomic() {
        for(counter in listOf("pair","base","deadline")) {
            val f=Fixture();f.open();f.fresh()
            when(counter) { "pair" -> setCounter(f.field("reconfiguration"),"eventCount",Long.MAX_VALUE);"base" -> setCounter(f.field("base"),"eventCount",Long.MAX_VALUE);else -> f.c.tick=Long.MAX_VALUE-10 }
            f.rejects { f.p(10).write(0x80,4,6) }
        }
        for(counter in listOf("pair","base")) {
            val f=Fixture();f.open();f.fresh();f.p(10).write(0x80,4,6)
            setCounter(f.field(if(counter=="pair")"reconfiguration" else "base"),"eventCount",if(counter=="pair")Long.MAX_VALUE else Long.MAX_VALUE-1)
            f.c.tick=20;f.rejects { f.p(10).beforeInstruction(20) }
        }
        val f=Fixture();f.open();setCounter(f.field("base"),"accessCount",Long.MAX_VALUE);f.rejects { f.p(10).write(0,4,17) }
    }
    @Test fun finiteDiagnosticsAreBoundedAndIndependentContextsDoNotAlias() {
        val f=Fixture();repeat(70) { n->f.open();f.fresh(a=n.toLong(),b=1000L+n);f.p(10).write(0x80,4,6);f.step(f.c.tick+12) }
        assertEquals(280L,f.pair()["eventCount"]);assertEquals(256,(f.pair()["events"] as List<*>).size);assertEquals(true,f.pair()["eventLogTruncated"])
        f.open(10);f.open(16);assertNotNull(contexts(f)[0]);assertNotNull(contexts(f)[1]);assertNull(contexts(f)[2])
        f.fresh(16,71,83);f.p(16).write(0x80,4,6);f.step(f.c.tick+12);assertNotNull(contexts(f)[0]);assertNull(contexts(f)[1])
    }
}
