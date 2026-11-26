package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class PowerRegisterRoutingTest {
    private class Endpoint(val claims:IntArray=intArrayOf(39)):PowerRegisterPort {
        var open=false;var reads=0;var checks=0;var writes=0;var reject=false;var reply=0x1234;var last:Triple<Int,Int,Long>?=null
        override fun connect(owner:String):PowerRegisterConnection{check(!open);open=true;return object:PowerRegisterConnection{
            override fun registers()=claims.copyOf()
            override fun read(register:Int,tick:Long):Int{check(open);reads++;return reply}
            override fun checkWrite(register:Int,value:Int,tick:Long){check(open);if(reject)error("explicit endpoint refusal");checks++}
            override fun write(register:Int,value:Int,tick:Long){checkWrite(register,value,tick);writes++;last=Triple(register,value,tick)}
            override fun close(){open=false}
        }}
    }
    private class Context(val endpoint:Endpoint?,val inputs:Map<String,Any?>):ComponentContext {
        var level=false;var sequence=0L;val entries=hashMapOf<String,Any>()
        override val directory=Path.of("unused-power-test");override val clock=EmulationClock{0}
        override val events=object:EventChannel{override fun publish(event:ComponentEvent){error("unexpected event")};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        init{entries["soc-gpio.outputs"]=object:GpioOutputPort{override fun outputLevel(port:Int,pin:Int)=level;override fun outputSequence(port:Int,pin:Int)=sequence};if(endpoint!=null)entries["rtc.registers"]=endpoint}
        override val ports=object:Ports{
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T?=entries[name]?.let{type.cast(it)}
            override fun removeOwner(owner:String){error("not needed")}
        }
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String)=inputs
        fun begin(){if(level){level=false;sequence++};level=true;sequence++}
    }
    private fun input(words:Map<String,Int> = mapOf("0" to 0),disabled:List<Int> = emptyList())=mapOf<String,Any?>("profile" to "checksum-registers-experimental-v1","chip_select" to mapOf("port" to 12,"pin" to 5,"active_high" to true,"idle_high" to true),"inputs" to words,"retained_registers" to emptyList<Int>(),"disable_writes" to disabled)
    private fun create(c:Context,port:String="rtc.registers"):HardwareComponent {val f=PowerPeerFactory();return f.create(c,f.descriptor.settings.associate{it.key to it.default}+mapOf("profile" to "checksum-registers-experimental-v1","inputFile" to "input.json","registerPort" to port))}
    private fun spi(c:Context)=c.ports.find("power-peer.spi",SpiPeer::class.java)!!
    private fun bytes(reg:Int,value:Int):List<Int>{val b=listOf(reg or 128,value ushr 8,value and 255);return b+(b.sum().inv()and 255)}
    @Test fun readIsSampledAtHeaderAndFrozenThroughThreeRemainingBytes(){val e=Endpoint();val c=Context(e,input());val component=create(c);component.start();val p=spi(c);c.begin();assertEquals(0,p.exchange(8,39,1));e.reply=0xabcd;assertEquals(0x12,p.exchange(8,0,2));assertEquals(0x34,p.exchange(8,0,3));assertEquals((39+0x12+0x34).inv()and 255,p.exchange(8,216,4));assertEquals(1,e.reads);assertEquals(0,e.writes);component.close();assertFalse(e.open)}
    @Test fun everyWrongChecksumPreservesTransportAndEndpointThenCorrectChecksumCommitsOnce(){val e=Endpoint();val c=Context(e,input());val component=create(c);component.start();val p=spi(c);c.begin();val bytes=bytes(39,0x202);bytes.take(3).forEach{p.exchange(8,it,0)};val before=component.state();for(wrong in 0..255)if(wrong!=bytes[3]){assertFails{p.exchange(8,wrong,0)};assertEquals(before,component.state());assertEquals(0,e.checks);assertEquals(0,e.writes)};p.exchange(8,bytes[3],7);assertEquals(Triple(39,0x202,7L),e.last);assertEquals(1,e.writes);assertEquals(1L,component.state()["eventCount"])}
    @Test fun endpointPreflightRefusalKeepsChecksumPendingAndCanResume(){val e=Endpoint();val c=Context(e,input());val component=create(c);component.start();val p=spi(c);c.begin();val b=bytes(39,0x202);b.take(3).forEach{p.exchange(8,it,0)};e.reject=true;val before=component.state();assertFails{p.exchange(8,b[3],1)};assertEquals(before,component.state());assertEquals(0,e.writes);e.reject=false;p.exchange(8,b[3],2);assertEquals(1,e.writes)}
    @Test fun abortedCsFrameNeverWritesEndpoint(){val e=Endpoint();val c=Context(e,input());val component=create(c);component.start();val p=spi(c);c.begin();val b=bytes(39,0xa55a);b.take(3).forEach{p.exchange(8,it,0)};c.begin();b.forEach{p.exchange(8,it,1)};assertEquals(1,e.writes);assertEquals(3L,component.state()["discardedWords"]);assertEquals(1L,component.state()["eventCount"])}
    @Test fun rawAndDisabledOverlapDuplicateOrInvalidClaimsReleaseLease(){for((claims,data)in listOf(intArrayOf(39) to input(mapOf("39" to 0)),intArrayOf(39) to input(disabled=listOf(39)),intArrayOf(39,39) to input(),intArrayOf(128) to input(),intArrayOf() to input())){val e=Endpoint(claims);val c=Context(e,data);val component=create(c);assertFails{component.start()};assertFalse(e.open);component.close()}}
    @Test fun closingPeerReleasesEndpointAndRefusesRetainedSpiHandle(){val e=Endpoint();val c=Context(e,input());val component=create(c);component.start();val p=spi(c);component.close();component.close();assertFalse(e.open);c.begin();assertFails{p.exchange(8,39,0)};assertFails{component.start()};assertEquals(0,e.writes)}
    @Test fun missingEndpointRefusesAndLegacyEmptyPortKeepsOriginalStateSchema(){val c=Context(null,input());val component=create(c);assertFails{component.start()};component.close();val fresh=Context(null,input());val legacy=create(fresh,"");legacy.start();assertFalse(legacy.state().containsKey("registerPort"));assertFalse(legacy.state().containsKey("delegatedRegisters"));fresh.begin();assertFails{spi(fresh).exchange(8,39,0)};assertEquals(0,legacy.state()["phase"]);assertEquals(0L,legacy.state()["wordCount"])}
}
