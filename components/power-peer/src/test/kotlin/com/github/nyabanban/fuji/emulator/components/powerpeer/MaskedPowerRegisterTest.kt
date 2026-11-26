package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class MaskedPowerRegisterTest {
 private val profile="masked-retained-registers-experimental-v2"
 private class Context(val data:Map<String,Any?>):ComponentContext {
  var level=false;var edge=0L;var endpointOpen=false;var claim=false;val entries=hashMapOf<String,Any>()
  override val directory=Path.of("unused-masked-power-test");override val clock=EmulationClock{0}
  override val events=object:EventChannel{override fun publish(event:ComponentEvent)=error("no power event law");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  init{entries["soc-gpio.outputs"]=object:GpioOutputPort{override fun outputLevel(port:Int,pin:Int)=level;override fun outputSequence(port:Int,pin:Int)=edge}
   entries["endpoint"]=object:PowerRegisterPort{override fun connect(owner:String):PowerRegisterConnection{endpointOpen=true;return object:PowerRegisterConnection{override fun registers()=intArrayOf(2);override fun read(register:Int,tick:Long)=error("no delegated read");override fun checkWrite(register:Int,value:Int,tick:Long)=error("no delegated write");override fun write(register:Int,value:Int,tick:Long)=error("no delegated write");override fun close(){endpointOpen=false}}}}}
  override val ports=object:Ports{override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)};override fun<T:Any> find(name:String,type:Class<T>):T?=entries[name]?.let{type.cast(it)};override fun removeOwner(owner:String)=error("not used")}
  override fun dataPath(relative:String)=directory.resolve(relative)
  override fun readData(relative:String)=data
  fun begin(){if(level){level=false;edge++};level=true;edge++}
 }
 private fun data(initial:Int=0xa558)=linkedMapOf<String,Any?>("profile" to profile,"chip_select" to mapOf("port" to 12,"pin" to 5,"active_high" to true,"idle_high" to false),"inputs" to mapOf("2" to initial,"17" to 0),"retained_registers" to listOf(17),"disable_writes" to listOf(25),"write_masks" to mapOf("2" to 4))
 private fun create(c:Context,selected:String=profile,port:String=""):HardwareComponent{val f=PowerPeerFactory();return f.create(c,f.descriptor.settings.associate{it.key to it.default}+mapOf("profile" to selected,"inputFile" to "input.json","registerPort" to port))}
 private fun peer(c:Context)=checkNotNull(c.ports.find("power-peer.spi",SpiPeer::class.java))
 private fun bytes(reg:Int,value:Int,write:Boolean):List<Int>{val b=listOf(reg or if(write)128 else 0,value ushr 8,value and 255);return b+(b.sum().inv()and 255)}
 private fun frame(c:Context,reg:Int,value:Int=0,write:Boolean=false):List<Int>{c.begin();return bytes(reg,value,write).mapIndexed{i,b->peer(c).exchange(8,b,i.toLong())}}
 private val finiteProfile="finite-candidate-reads-experimental-v3"
 private fun finiteData(mask:Int)=data(4).apply {
  this["profile"]=finiteProfile
  this["write_masks"]=mapOf("2" to mask)
  this["candidate_reads"]=listOf(mapOf("spaceId" to "gfx100s-power-protocol-requests",
   "requestId" to "request.power-peer.selector0x03","selector" to 3,"wordBits" to 16,
   "confidence" to "HYPOTHESIS","values" to listOf(0,0)))
 }
 @Test fun finiteProfileOriginalMaskRejectsPower2Bit8AtChecksumWithoutCommit(){
  val context=Context(finiteData(4));val component=create(context,finiteProfile);component.start()
  val request=bytes(2,260,true);assertEquals(listOf(0x82,0x01,0x04,0x78),request)
  context.begin();request.take(3).forEach { peer(context).exchange(8,it,0) }
  val before=component.state()
  assertEquals("power write changes undeclared bits register2",assertFailsWith<IllegalArgumentException> {
   peer(context).exchange(8,request[3],1)
  }.message)
  assertEquals(before,component.state())
  assertEquals(3,before["phase"]);assertEquals(3L,before["wordCount"]);assertEquals(0L,before["eventCount"])
  assertEquals(4,(before["stored"] as Map<*,*>)["2"])
  assertEquals(listOf(0,0,4,249),frame(context,2))
  component.close()
 }
 @Test fun finiteProfileExplicitMask260CommitsPower2OnlyOnChecksumAndReadsBackBothSelectedBits(){
  val context=Context(finiteData(260));val component=create(context,finiteProfile);component.start()
  val candidates=component.state()["scriptedCandidateReads"]
  var previous=4
  for(value in listOf(260,256,0,4)){
   context.begin();val request=bytes(2,value,true)
   request.take(3).forEach { assertEquals(0,peer(context).exchange(8,it,0)) }
   val before=component.state()
   assertEquals(previous,(before["stored"] as Map<*,*>)["2"])
   assertEquals(request[3],peer(context).exchange(8,request[3],1))
   val after=component.state()
   assertEquals(0,after["phase"]);assertEquals(value,(after["stored"] as Map<*,*>)["2"])
   assertEquals((before["eventCount"] as Long)+1,after["eventCount"])
   val event=(after["events"] as List<*>).last() as Map<*,*>
   assertEquals(true,event["write"]);assertEquals(2,event["register"]);assertEquals(value,event["value"])
   assertEquals(request,event["frame"]);assertEquals(request[3],event["replyChecksum"])
   val response=frame(context,2)
   assertEquals(listOf(0,value ushr 8,value and 255,(2+(value ushr 8)+(value and 255)).inv() and 255),response)
   assertEquals(candidates,component.state()["scriptedCandidateReads"])
   assertEquals(260,((component.state()["inputs"] as Map<*,*>)["write_masks"] as Map<*,*>)["2"])
   previous=value
  }
  component.close()
 }
 @Test fun finiteProfileMask260RejectsEveryOtherBitAndBadChecksumWithoutCommit(){
  val context=Context(finiteData(260));val component=create(context,finiteProfile);component.start()
  for(bit in 0..15)if(bit!=2 && bit!=8){
   context.begin();val request=bytes(2,260 xor (1 shl bit),true)
   request.take(3).forEach { peer(context).exchange(8,it,0) }
   val before=component.state()
   assertEquals("power write changes undeclared bits register2",assertFailsWith<IllegalArgumentException> {
    peer(context).exchange(8,request[3],1)
   }.message)
   assertEquals(before,component.state())
  }
  context.begin();val request=bytes(2,260,true)
  request.take(3).forEach { peer(context).exchange(8,it,0) }
  val before=component.state()
  assertEquals(4,(before["stored"] as Map<*,*>)["2"]);assertEquals(0L,before["eventCount"])
  for(checksum in 0..255)if(checksum!=request[3]){
   assertEquals("invalid checksum power request frame",assertFailsWith<IllegalArgumentException> {
    peer(context).exchange(8,checksum,1)
   }.message)
   assertEquals(before,component.state())
  }
  assertEquals(0x78,peer(context).exchange(8,request[3],2))
  assertEquals(260,(component.state()["stored"] as Map<*,*>)["2"])
  assertEquals(1L,component.state()["eventCount"])
  assertEquals(before["scriptedCandidateReads"],component.state()["scriptedCandidateReads"])
  component.close()
 }
 @Test fun readSetClearPreserveAllUndeclaredBitsAndLegacyRetainedDisableLaws(){
  val c=Context(data());val component=create(c);component.start()
  assertEquals(listOf(0,0xa5,0x58,(2+0xa5+0x58).inv()and 255),frame(c,2))
  val set=bytes(2,0xa55c,true);assertEquals(listOf(0,0,0,set[3]),frame(c,2,0xa55c,true));assertEquals(listOf(0,0xa5,0x5c,(2+0xa5+0x5c).inv()and 255),frame(c,2))
  frame(c,2,0xa558,true);assertEquals(0xa558,(component.state()["stored"]as Map<*,*>)["2"])
  frame(c,17,0x1357,true);assertEquals(0x1357,(component.state()["stored"]as Map<*,*>)["17"]);frame(c,25,0,true);assertFails{frame(c,25,1,true)};assertFails{frame(c,3,0,true)}
  component.close()
 }
 @Test fun eachOtherBitAndEveryWrongChecksumFailBeforeCommitWithExactPendingState(){
  val c=Context(data());val component=create(c);component.start();val p=peer(c)
  for(bit in 0..15)if(bit!=2){c.begin();val b=bytes(2,0xa558 xor(1 shl bit),true);b.take(3).forEach{p.exchange(8,it,0)};val before=component.state();assertFails{p.exchange(8,b[3],1)};assertEquals(before,component.state())}
  c.begin();val b=bytes(2,0xa55c,true);b.take(3).forEach{p.exchange(8,it,0)};val before=component.state();for(wrong in 0..255)if(wrong!=b[3]){assertFails{p.exchange(8,wrong,1)};assertEquals(before,component.state())}
  assertEquals(0L,component.state()["eventCount"]);assertEquals(b[3],p.exchange(8,b[3],2));assertEquals(1L,component.state()["eventCount"]);assertEquals(0xa55c,(component.state()["stored"]as Map<*,*>)["2"]);component.close()
 }
 @Test fun chipSelectAbortIdleAndClosedHandlesNeverCommitPartialWrite(){
  val c=Context(data(0));val component=create(c);component.start();c.begin();bytes(2,4,true).take(3).forEach{peer(c).exchange(8,it,0)}
  c.level=false;c.edge++;assertEquals(0,peer(c).exchange(8,0,1));assertEquals(0,(component.state()["stored"]as Map<*,*>)["2"]);assertEquals(3L,component.state()["discardedWords"])
  frame(c,2,4,true);val p=peer(c);component.close();component.close();val before=component.state();assertFails{p.exchange(8,0,3)};assertEquals(before,component.state());assertFails{component.start()}
 }
 @Test fun inputAndMaskContainersAreFrozenAndHistoryIsBounded(){
  val masks=linkedMapOf<String,Any>("2" to 4);val words=linkedMapOf<String,Any>("2" to 0);val d=data(0).apply{this["inputs"]=words;this["write_masks"]=masks;this["retained_registers"]=emptyList<Int>()};val c=Context(d);val component=create(c);component.start();masks["2"]=65535;words["2"]=65535
  for(i in 0 until 270)frame(c,2,if(i%2==0)4 else 0,true)
  val state=component.state();assertEquals(270L,state["eventCount"]);assertEquals(1080L,state["wordCount"]);assertEquals(256,(state["events"]as List<*>).size);assertEquals(true,state["eventLogTruncated"]);assertEquals(true,state["wordLogTruncated"])
  assertEquals(mapOf("2" to 4),(state["inputs"]as Map<*,*>)["write_masks"]);assertEquals(mapOf("2" to 0),(state["inputs"]as Map<*,*>)["inputs"]);assertFails{frame(c,2,1,true)};component.close()
 }
 @Test fun genericRegister127AndHighMaskAreDataNotGfxAddresses(){
  val d=data().apply{this["inputs"]=mapOf("127" to 0x1234);this["write_masks"]=mapOf("127" to 0x8000);this["retained_registers"]=emptyList<Int>()};val c=Context(d);val component=create(c);component.start();frame(c,127,0x9234,true);assertEquals(0x9234,(component.state()["stored"]as Map<*,*>)["127"]);assertFails{frame(c,127,0x9235,true)};assertFails{frame(c,2)};component.close()
 }
 @Test fun schemaMasksResetsOverlapTypesAndUnknownProfilesRefuseBeforePortPublication(){
  val variants=listOf<(MutableMap<String,Any?>)->Unit>(
   {it.remove("write_masks")},{it["write_masks"]=emptyMap<String,Int>()},{it["write_masks"]=null},{it["extra"]=0},
   {it["write_masks"]=mapOf("2" to 0)},{it["write_masks"]=mapOf("2" to -1)},{it["write_masks"]=mapOf("2" to 65536)},{it["write_masks"]=mapOf("2" to true)},{it["write_masks"]=mapOf("2" to 4.0)},{it["write_masks"]=mapOf("02" to 4)},{it["write_masks"]=mapOf("128" to 4)},{it["write_masks"]=mapOf("2" to "4")},
   {it["inputs"]=mapOf("17" to 0)},{it["retained_registers"]=listOf(2,17)},{it["disable_writes"]=listOf(2,25)},{it["profile"]="checksum-registers-experimental-v1"})
  for(change in variants){val d=data();change(d);val c=Context(d);assertFails{create(c)};assertNull(c.ports.find("power-peer.spi",SpiPeer::class.java))}
  val c=Context(data());assertFails{create(c,"unknown")};assertNull(c.ports.find("power-peer.spi",SpiPeer::class.java))
 }
 @Test fun legacyProfileRejectsNewSchemaAndStillRefusesRegister2Write(){
  val legacy=data(0).apply{this["profile"]="checksum-registers-experimental-v1";remove("write_masks")};val c=Context(legacy);val component=create(c,"checksum-registers-experimental-v1");component.start();assertFails{frame(c,2,4,true)};assertFalse((component.state()["stored"]as Map<*,*>).containsKey("2"));component.close()
  assertFails{create(Context(data()),"checksum-registers-experimental-v1")}
 }
 @Test fun delegatedMaskedClaimRefusesAndReleasesConnection(){
  val c=Context(data());val component=create(c,port="endpoint");assertFails{component.start()};assertFalse(c.endpointOpen);component.close()
 }

 @Test fun fourFullWordRegistersAreIndependentAndCommitOnlyAfterEachValidChecksum(){
  val initial=mapOf("2" to 0,"17" to 0)+(31..34).associate{it.toString() to (it*0x101)}
  val d=data(0).apply{this["inputs"]=initial;this["write_masks"]=mapOf("2" to 4)+(31..34).associate{it.toString() to 65535}}
  val c=Context(d);val component=create(c);component.start();val expected=initial.toMutableMap()
  for(reg in 31..34)for(value in listOf(0,1,0x00ff,0xff00,0x8001,0xffff)){
   c.begin();val b=bytes(reg,value,true);b.take(3).forEach{peer(c).exchange(8,it,0)};val before=component.state()
   assertFails{peer(c).exchange(8,b[3] xor 1,1)};assertEquals(before,component.state());assertEquals(b[3],peer(c).exchange(8,b[3],2))
   expected[reg.toString()]=value;val stored=component.state()["stored"]as Map<*,*>;assertEquals(expected,stored);val reply=frame(c,reg);assertEquals(value,(reply[1] shl 8) or reply[2]);assertEquals(4,(component.state()["inputs"]as Map<*,*>).let{(it["write_masks"]as Map<*,*>)["2"]})
  }
  assertFails{frame(c,35,0,true)};assertFails{frame(c,2,1,true)};component.close()
 }
}
