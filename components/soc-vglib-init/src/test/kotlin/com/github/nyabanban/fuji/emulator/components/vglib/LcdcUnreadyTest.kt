package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LcdcUnreadyTest {
    private fun offsets()=listOf(8L,0x30L,0x14L,0x20L)+(0x40L..0x68L step 4)+(0x130L..0x1c0L step 4)+(0x1c8L..0x320L step 4)+listOf(0x600L)
    private fun input(limit:Any?=3)=mapOf<String,Any?>("schema" to 1,"profile" to LcdcSnapshotInput.UNREADY_PROFILE,"readConfidence" to "HYPOTHESIS",
        "reads" to offsets().mapIndexed{i,x->mapOf("offset" to x,"value" to i)},
        "controlReadLimit" to limit,"controlInitialValue" to 0,"controlWriteValue" to 0,"controlReadValue" to 0)
    private class Context(val input:Map<String,Any?>):ComponentContext {
        val entries=HashMap<String,Any>();var tick=0L;var failLocation=false;var core=0;var pc=0x5af98L
        override val directory=Path.of(".")
        override val ports=object:Ports {
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T?=if(name=="cpu.execution-location")type.cast(ExecutionLocationSource{if(failLocation)error("location failed")else ExecutionLocation(core,pc)})else entries[name]?.let(type::cast)
            override fun removeOwner(owner:String){entries.clear()}
        }
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel {override fun publish(event:ComponentEvent)=error("unready must not publish effects");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String)=if(relative=="bank")mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to List(18){mapOf("offset" to 0,"readValue" to 0,"writeValue" to 0)})else input
    }
    private fun prefix(c:Context,p:LcdcSnapshotInput) {offsets().forEachIndexed{i,x->c.tick=i.toLong();assertEquals(i.toLong(),p.read(x,4))}}
    private fun control(p:LcdcSnapshotInput)=p.state()["control"] as Map<*,*>
    private fun unchanged(p:LcdcSnapshotInput,action:()->Unit) {val before=p.state();assertFails{action()};assertEquals(before,p.state())}

    @Test fun completePrefixThenOneZeroWriteAndFiniteUnreadyReads() {
        val c=Context(input());val p=LcdcSnapshotInput.load(c,"snapshot")!!
        assertEquals(LcdcSnapshotInput.UNREADY_PORT,p.port);assertEquals("snapshot",control(p)["phase"])
        unchanged(p){p.write(0,4,0)};unchanged(p){p.read(0,4)}
        prefix(c,p)
        assertEquals("awaiting-zero-write",control(p)["phase"])
        unchanged(p){p.read(0,4)}
        c.tick=200;p.write(0,4,0)
        assertEquals("unready",control(p)["phase"]);assertEquals(1,control(p)["writeCount"])
        repeat(3){i->c.tick=201L+i;c.core=3;c.pc=0x5afe0;assertEquals(0,p.read(0,4))}
        val s=control(p)
        assertEquals("exhausted",s["phase"]);assertEquals(3,s["readCount"]);assertEquals(0,s["remainingReads"]);assertEquals(0L,s["retainedValue"])
        assertEquals(mapOf<String,Any?>("tick" to 200L,"core" to 0,"pcVirtual" to 0x5af98L),s["write"])
        assertEquals(mapOf<String,Any?>("tick" to 201L,"core" to 3,"pcVirtual" to 0x5afe0L),s["firstRead"])
        assertEquals(mapOf<String,Any?>("tick" to 203L,"core" to 3,"pcVirtual" to 0x5afe0L),s["lastRead"])
        assertEquals(140,p.state()["transactionCount"]);assertEquals(0,p.state()["remainingTransactions"])
        unchanged(p){p.read(0,4)};unchanged(p){p.write(0,4,0)}
    }

    @Test fun invalidTransactionsAndObservationFailuresDoNotConsumeState() {
        val c=Context(input());val p=LcdcSnapshotInput.load(c,"snapshot")!!;prefix(c,p)
        for(offset in listOf(-4L,1L,4L,0x600L,0x604L))unchanged(p){p.write(offset,4,0)}
        for(size in listOf(0,1,2,8))unchanged(p){p.write(0,size,0)}
        for(value in listOf(-1L,1L,0x100000000L))unchanged(p){p.write(0,4,value)}
        c.tick=-1;unchanged(p){p.write(0,4,0)};c.tick=200;c.failLocation=true;unchanged(p){p.write(0,4,0)};c.failLocation=false
        p.write(0,4,0)
        for(offset in listOf(-4L,1L,4L,0x600L,0x604L))unchanged(p){p.read(offset,4)}
        for(size in listOf(0,1,2,8))unchanged(p){p.read(0,size)}
        unchanged(p){p.write(0,4,0)}
        c.tick=-1;unchanged(p){p.read(0,4)};c.tick=201;c.failLocation=true;unchanged(p){p.read(0,4)};c.failLocation=false
        assertEquals(0L,p.read(0,4));c.tick=202;c.failLocation=true;unchanged(p){p.read(0,4)};c.failLocation=false
        assertEquals(1,control(p)["readCount"]);assertEquals(201L,(control(p)["lastRead"] as Map<*,*>)["tick"])
    }

    @Test fun inputMustExplicitlySelectBoundedIntegerZeroHypothesis() {
        for(limit in listOf(null,0,-1,400001,Long.MAX_VALUE,1.0,"1",true))assertFails{LcdcSnapshotInput.load(Context(input(limit)),"snapshot")}
        for(key in listOf("controlReadLimit","controlInitialValue","controlWriteValue","controlReadValue"))assertFails{LcdcSnapshotInput.load(Context(input()-key),"snapshot")}
        for(key in listOf("controlInitialValue","controlWriteValue","controlReadValue"))for(value in listOf(null,1,-1,0.0,"0",false))assertFails{LcdcSnapshotInput.load(Context(input()+mapOf(key to value)),"snapshot")}
        for(limit in listOf(1.toByte(),1.toShort(),1,400000L))assertNotNull(LcdcSnapshotInput.load(Context(input(limit)),"snapshot"))
        assertFails{LcdcSnapshotInput.load(Context(input()+mapOf("extra" to 0)),"snapshot")}
        assertFails{LcdcSnapshotInput.load(Context(input()+mapOf("readConfidence" to "SIM")),"snapshot")}
        assertFails{LcdcSnapshotInput.load(Context(input()+mapOf("profile" to LcdcSnapshotInput.PROFILE)),"snapshot")}
        assertFails{LcdcSnapshotInput.load(Context(input()+mapOf("reads" to emptyList<Any>())),"snapshot")}
        val c=Context(input(400000));val p=LcdcSnapshotInput.load(c,"snapshot")!!;prefix(c,p);p.write(0,4,0)
        repeat(400000){assertEquals(0L,p.read(0,4))};unchanged(p){p.read(0,4)}
        assertEquals(400000,control(p)["readCount"])
    }

    @Test fun componentRequiresMatchingProfileAndClaimsOnlyNewPort() {
        val factory=VglibInitFactory();val config=mapOf("profile" to VglibInitFactory.UNREADY_PROFILE,"inputFile" to "bank","lcdcSnapshotInputFile" to "snapshot")
        val c=Context(input());val h=factory.create(c,config)
        assertEquals(setOf("soc-vglib-init.registers",LcdcSnapshotInput.UNREADY_PORT),h.additionalMmioPorts().toSet())
        assertNull(c.ports.find(LcdcSnapshotInput.PORT,MmioDevice::class.java));assertNotNull(c.ports.find(LcdcSnapshotInput.UNREADY_PORT,MmioDevice::class.java));assertTrue(h.interruptRoutes().isEmpty())
        assertEquals(LcdcSnapshotInput.UNREADY_PROFILE,(h.state()["lcdcSnapshot"] as Map<*,*>)["profile"])
        assertFails{factory.create(Context(input()),config+mapOf("profile" to VglibInitFactory.PROFILE))}
        assertFails{factory.create(Context(input()),config+mapOf("lcdcSnapshotInputFile" to ""))}
        val old=input().filterKeys{!it.startsWith("control")}+mapOf("profile" to LcdcSnapshotInput.PROFILE)
        assertFails{factory.create(Context(old),config)}
    }
}
