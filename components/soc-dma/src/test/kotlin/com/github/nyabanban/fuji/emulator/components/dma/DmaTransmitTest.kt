package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class DmaTransmitTest {
 @TempDir lateinit var temporary:Path
 private class Registry:Ports {
  private data class Entry(val type:Class<*>,val port:Any)
  private val data=mutableMapOf<String,Entry>()
  override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){require(type.isInstance(port)&&name !in data);data[name]=Entry(type,port)}
  override fun<T:Any>find(name:String,type:Class<T>):T? {val e=data[name]?:return null;require(e.type==type);return type.cast(e.port)}
  override fun removeOwner(owner:String){}
 }
 private class Context(override val directory:Path):ComponentContext {
  var tick=0L;override val clock=EmulationClock{tick};override val ports=Registry();var eventCount=0
  override val events=object:EventChannel{override fun publish(event:ComponentEvent){eventCount++};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun readData(relative:String):Map<String,Any?> = error("No data file required")
  override fun dataPath(relative:String)=directory.resolve(relative)
 }
 private class Ram: DmaMemory {
  val bytes=ByteArray(0x40000){(it*17+3).toByte()};var reads=0;var writes=0;var allowWrites=false;var shortRead=false;var reject=false
  override fun check(address:Long,size:Long,write:Boolean){require(!reject&&address>=0x100000&&size>=0&&address-0x100000<=bytes.size&&size<=bytes.size-(address-0x100000)&&(!write||allowWrites)){"RAM preflight rejected"}}
  override fun read(address:Long,destination:ByteBuffer){check(address,destination.remaining().toLong(),false);reads++;val n=destination.remaining()-(if(shortRead)4 else 0);destination.put(bytes,(address-0x100000).toInt(),n)}
  override fun write(address:Long,source:ByteBuffer){check(address,source.remaining().toLong(),true);writes++;source.get(bytes,(address-0x100000).toInt(),source.remaining())}
 }
 private class Sink(var count:Int=64):DmaDestinationPeer {
  val bytes=ByteArray(262144){0x5a};var ready:Long?=0L;var owner:Int?=null;var received=0;var claims=0;var cancels=0;var finishes=0;var capacity=262144;var shortWrite=false;var shortBack=false;var wrongClaim=false;var finishedAt:Long?=null
  override fun matches(address:Long)=address==0xfff30024L
  override fun check(address:Long,size:Long,width:Int,request:Int){require(matches(address)&&size==count.toLong()&&size<=capacity&&width==4&&request==21){"Sink preflight rejected"}}
  override fun acceptedAt()=ready
  override fun claim(channel:Int,address:Long,size:Long,width:Int,request:Int):Long? {check(address,size,width,request);val at=ready?:return null;check(owner==null||owner==channel);if(owner==null){owner=channel;received=0;claims++};return if(wrongClaim)at+1 else at}
  override fun release(channel:Int){if(owner==channel){owner=null;received=0;cancels++}}
  override fun write(channel:Int,address:Long,offset:Long,source:ByteBuffer){check(owner==channel&&offset==received.toLong());val n=source.remaining()-(if(shortWrite)4 else 0);source.get(bytes,received,n);received+=n}
  override fun readBack(channel:Int,address:Long,offset:Long,destination:ByteBuffer){check(owner==channel);val n=destination.remaining()-(if(shortBack)4 else 0);destination.put(bytes,offset.toInt(),n)}
  override fun finish(channel:Int,completedAt:Long,observedAt:Long){check(owner==channel&&received==count);finishes++;finishedAt=completedAt;owner=null}
 }
 private data class Fixture(val c:Context,val ram:Ram,val sink:Sink,val device:HardwareComponent,val dma:ScheduledMmioDevice,val mux:MmioDevice,val irqs:MutableMap<Int,Boolean>)
 private fun fixture(count:Int=64,irq:Boolean=true,tx:Boolean=true):Fixture {
  val c=Context(temporary);val ram=Ram();val sink=Sink(count);val irqs=mutableMapOf<Int,Boolean>();c.ports.register("test","cpu.memory",DmaMemory::class.java,ram);c.ports.register("test","test.tx",DmaDestinationPeer::class.java,sink);c.ports.register("test","test.irq",InterruptSink::class.java,object:InterruptSink{override fun setLine(irq:Int,high:Boolean){irqs[irq]=high}})
  val f=DmaFactory();val cfg=f.descriptor.settings.associate{it.key to it.default}+mapOf("transmitPeerPort" to if(tx)"test.tx"else "","interruptPort" to if(irq)"test.irq"else "")
  val h=f.create(c,cfg);h.start();return Fixture(c,ram,sink,h,c.ports.find("soc-dma.registers",MmioDevice::class.java)as ScheduledMmioDevice,checkNotNull(c.ports.find("soc-dma.mux",MmioDevice::class.java)),irqs)
 }
 private fun configure(f:Fixture,channel:Int=1,size:Int=64,source:Long=0x100000,destination:Long=0xfff30024,route:Int=21,sourceGeometry:Long=0x20000,destinationGeometry:Long=0x20004) {
  val shift=if(channel%2==0)0 else 8;val strobe=if(channel%2==0)0x10000L else 0x1000000L;f.mux.write(channel/2*4L,4,strobe or(route.toLong()shl shift))
  listOf(0x10 to size.toLong()-1,0x14 to source,0x18 to destination,0x1c to sourceGeometry,0x20 to destinationGeometry,0x24 to 0L).forEach{(off,value)->f.dma.write(channel*4096L+off,4,value)};f.dma.write(channel*4096L+0x28,4,if(f.device.state()["transmitPeerPort"]=="test.tx")0x201011L else 0L)
 }
 @Test fun exactAndLateMultipleChunkTransfersUseFixedDestinationAndRetainCompletionIrq() {
  for(count in listOf(64,65568,131072))for(late in listOf(0L,101L)){val f=fixture(count);configure(f,size=count);val before=f.ram.bytes.copyOf();f.c.tick=100;f.dma.write(0x1028,4,0x10201011);val due=100L+16+(count+15)/16;assertEquals(due,f.dma.nextDeadline());assertEquals(1,f.sink.claims);f.c.tick=due-1;f.dma.beforeInstruction(f.c.tick);assertEquals(0,f.sink.finishes);f.c.tick=due+late;f.dma.beforeInstruction(f.c.tick);assertContentEquals(before.copyOfRange(0,count),f.sink.bytes.copyOfRange(0,count));assertContentEquals(before,f.ram.bytes);assertEquals(0,f.ram.writes);assertEquals(1,f.sink.finishes);assertEquals(due,f.sink.finishedAt);assertEquals(true,f.irqs[208]);assertEquals(0xfff30024L,f.dma.read(0x1018,4));assertEquals(0x100000L+count,f.dma.read(0x1014,4));assertEquals(1L,f.dma.read(0x1030,4));f.dma.beforeInstruction(f.c.tick+1);assertEquals(1,f.sink.finishes);f.dma.write(0x1030,4,1);f.dma.beforeInstruction(f.c.tick+2);assertEquals(false,f.irqs[208]);f.device.close()}
 }
 @Test fun absentAcceptanceKeepsSubmittedBusyWithoutClaimDeadlineBytesOrIrq() {
  val f=fixture();f.sink.ready=null;configure(f);f.c.tick=1;f.dma.write(0x1028,4,0x10201011);assertNull(f.dma.nextDeadline());f.c.tick=10000;f.dma.beforeInstruction(f.c.tick);assertEquals(0,f.sink.claims);assertEquals(0,f.sink.received);assertEquals(0x10000L,f.dma.read(0x1030,4));assertEquals(false,f.irqs[208]);assertEquals(0L,f.device.state()["eventCount"]);val before=f.device.state();assertFails{f.mux.write(0,4,0x1001600)};assertEquals(before,f.device.state());f.dma.write(0x1028,4,0x201011);assertEquals(0L,f.dma.read(0x1030,4));assertNull(f.dma.nextDeadline());f.device.close()
 }
 @Test fun laterAcceptanceUsesActualAcceptedTimeAndLateObservationCompletesExactlyOnce() {
  val f=fixture();f.sink.ready=null;configure(f);f.c.tick=10;f.dma.write(0x1028,4,0x10201011);f.sink.ready=20;f.c.tick=100;f.dma.beforeInstruction(100);assertEquals(1,f.sink.claims);assertEquals(1,f.sink.finishes);assertEquals(40L,f.sink.finishedAt);f.device.close()
 }
 @Test fun everyBadGeometryRouteBoundsCapacityOverflowRefusesBeforeClaimOrBusyMutation() {
  val cases=listOf<(Fixture)->Unit>({configure(it,route=22)},{configure(it,sourceGeometry=0x20004)},{configure(it,destinationGeometry=0x20000)},{configure(it,source=0x100001)},{configure(it,source=0x200000)},{configure(it,size=60)},{configure(it);it.sink.capacity=60},{configure(it);it.ram.reject=true},{configure(it);it.c.tick=Long.MAX_VALUE})
  for(setup in cases){val f=fixture();setup(f);val before=f.device.state();assertFails{f.dma.write(0x1028,4,0x10201011)};assertEquals(before,f.device.state());assertEquals(0,f.sink.claims);assertNull(f.sink.owner);assertEquals(0,f.sink.received);assertEquals(0,f.ram.reads);f.device.close()}
 }
 @Test fun onlyOneSubmittedTransferCanOwnRequestAndCancellationReleasesWithoutConsumption() {
  val f=fixture();configure(f);configure(f,channel=2);f.c.tick=1;f.dma.write(0x1028,4,0x10201011);val before=f.device.state();assertFails{f.dma.write(0x2028,4,0x10201011)};assertEquals(before,f.device.state());assertEquals(1,f.sink.claims);f.dma.write(0x1028,4,0x201011);assertNull(f.sink.owner);assertEquals(1,f.sink.cancels);assertEquals(0,f.sink.finishes);f.dma.write(0x2028,4,0x10201011);assertEquals(2,f.sink.owner);f.device.close();assertNull(f.sink.owner);assertFails{f.dma.read(0x2030,4)};assertFails{f.device.start()}
 }
 @Test fun shortSourceOrDestinationNeverPublishesCompletedEventOrIrq() {
  for(kind in listOf("source","write","readback")){val f=fixture();configure(f);f.c.tick=1;f.dma.write(0x1028,4,0x10201011);when(kind){"source"->f.ram.shortRead=true;"write"->f.sink.shortWrite=true;else->f.sink.shortBack=true};f.c.tick=21;assertFails{f.dma.beforeInstruction(21)};assertEquals(0,f.sink.finishes);assertEquals(0L,f.device.state()["eventCount"]);assertEquals(0x10000L,f.dma.read(0x1030,4));assertTrue(f.irqs.values.none{it});f.device.close();assertNull(f.sink.owner)}
 }
 @Test fun completionBytesSurviveMissingIrqRouteWithoutFabricatingDelivery() {
  val f=fixture(irq=false);configure(f);f.c.tick=0;f.dma.write(0x1028,4,0x10201011);f.c.tick=20;assertFails{f.dma.beforeInstruction(20)};assertEquals(1,f.sink.finishes);assertEquals(1L,f.device.state()["eventCount"]);assertEquals(1L,f.dma.read(0x1030,4));f.device.close()
 }
 @Test fun mismatchedClaimReleasesLeaseWithoutBusyOrCompletionAndBadSettingPublishesNothing() {
  val f=fixture();configure(f);f.sink.wrongClaim=true;val before=f.device.state();assertFails{f.dma.write(0x1028,4,0x10201011)};assertEquals(before,f.device.state());assertNull(f.sink.owner);assertEquals(1,f.sink.cancels);assertEquals(0,f.sink.finishes);f.device.close()
  val factory=DmaFactory();val c=Context(temporary);val bad=factory.descriptor.settings.associate{it.key to it.default}+mapOf("transmitPeerPort" to 1);assertFails{factory.create(c,bad)};assertNull(c.ports.find("soc-dma.registers",MmioDevice::class.java))
 }
 @Test fun disabledTransmitSettingAndLegacyRamCopiesKeepHistoricalShape() {
  val f=fixture(tx=false);assertFalse("transmitPeerPort" in f.device.state());assertTrue((f.device.state()["channels"]as List<*>).all{!(it as Map<*,*>).containsKey("toPeer")});f.ram.allowWrites=true;configure(f,destination=0x120000,destinationGeometry=0);f.dma.write(0x1028,4,0);f.dma.write(0x101c,4,0);f.dma.write(0x1028,4,0x10000001);f.c.tick=20;f.dma.beforeInstruction(20);assertContentEquals(f.ram.bytes.copyOfRange(0,64),f.ram.bytes.copyOfRange(0x20000,0x20040));assertEquals(0,f.sink.claims);f.device.close()
 }
}
