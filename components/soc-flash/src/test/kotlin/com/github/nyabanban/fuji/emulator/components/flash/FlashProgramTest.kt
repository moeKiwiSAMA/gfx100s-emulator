package com.github.nyabanban.fuji.emulator.components.flash
import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class FlashProgramTest {
 private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
 private inner class Context:ComponentContext {
  var tick=0L;val bytes=ByteArray(131072){-1};val seedSha=hash(bytes);var generation=0L;var pending=false;var prepared=0;var commits=0;var cancelled=0;var failPrepare=false;var failCommit=false;var mismatch=false
  val claims=linkedMapOf<String,Any>();override val directory=Path.of(".").toAbsolutePath();override val clock=EmulationClock{tick}
  override val ports=object:Ports{override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){check(claims.putIfAbsent(name,port)==null)};override fun<T:Any>find(name:String,type:Class<T>):T?=claims[name]?.let{type.cast(it)};override fun removeOwner(owner:String){} }
  override val events=object:EventChannel{override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun dataPath(relative:String):Path=error("No filesystem in controller unit TEST")
  init{
   claims["board.layout"]=object:AddressLayout{override fun window(port:String)=AddressWindow(port,0xf0000000,0x8000000)}
   claims["test.image"]=object:SupplementalImagePort{override val length=4096L;override val sha256="0".repeat(64);override val supplementalRegions=listOf(ImageRegionIdentity("seed",0x3dc0000,131072,seedSha,"seed.img"));override fun check(offset:Long,size:Long){require(offset>=0x3dc0000&&size>0&&offset<=0x3de0000-size)};override fun read(offset:Long,destination:ByteBuffer){check(offset,destination.remaining().toLong());destination.put(bytes,(offset-0x3dc0000).toInt(),destination.remaining())}}
   claims["test.image.mutable"]=object:WritableImagePort{override fun snapshot()=WritableImageState("seed",0x3dc0000,131072,"copy.img",0x3dc0000,131072,if(mismatch)"f".repeat(64)else seedSha,hash(bytes),generation,pending,false,null);override fun prepareErase(offset:Long,size:Long):ImageEraseTransaction=error("No erase lease expected in program TEST")}
   claims["test.image.program"]=object:ProgrammableImagePort{override fun prepareProgram(offset:Long,payload:ByteBuffer):ImageProgramTransaction{check(!pending&&!failPrepare);require(offset>=0x3dc0000&&offset<=0x3de0000-payload.remaining());val frozen=ByteArray(payload.remaining());payload.duplicate().get(frozen);pending=true;prepared++;return object:ImageProgramTransaction{override val offset=offset;override val size=frozen.size;var ended=false;override fun commit():ImageProgramReceipt{check(!ended&&pending&&!failCommit);val before=hash(bytes);val start=(offset-0x3dc0000).toInt();val changed=frozen.indices.count{bytes[start+it]!=frozen[it]}.toLong();frozen.copyInto(bytes,start);generation++;commits++;pending=false;ended=true;return ImageProgramReceipt(offset,size,changed,hash(frozen),before,hash(bytes),generation)};override fun close(){if(!ended){ended=true;pending=false;cancelled++}}}}}
   claims["test.irq"]=object:InterruptSink{override fun setLine(irq:Int,high:Boolean){} }
  }
 }
 private inner class Fixture(profile:String=FlashFactory.PROGRAM_PROFILE){val c=Context();val h=FlashFactory().create(c,mapOf("profile" to profile,"jedec" to FlashFactory.ENABLE_JEDEC,"imagePort" to "test.image","interruptPort" to "test.irq","irq" to 37L));val mmio=c.ports.find("soc-flash.registers",MmioDevice::class.java)as ScheduledMmioDevice;val peer=c.ports.find("soc-flash.tx",DmaDestinationPeer::class.java)
  fun write(offset:Int,value:Long)=mmio.write(offset.toLong(),4,value);fun read(offset:Int)=mmio.read(offset.toLong(),4);fun advance(tick:Long){c.tick=tick;mmio.beforeInstruction(tick)}
  fun enable(){write(0x90,0x06000001);advance(c.tick+8)}
  fun configure(){write(0,read(0) or 0x8000);listOf(8 to 0x12L,0x74 to 0xffffffffL,0x78 to 0xf3dc0000L,0x80 to 15L,0x20 to 0x202L,0x7c to 256L,0x1c to 0xf3dc0000L).forEach{(o,v)->write(o,v)}}
  fun submit(){c.tick=10;write(0x70,1)}
  fun claim(){assertEquals(18L,checkNotNull(peer).claim(0,0xf3dc0000,256,4,43))}
  fun stage(observed:Long=50,completed:Long=50){val p=checkNotNull(peer);p.write(0,0xf3dc0000,0,ByteBuffer.wrap(ByteArray(256){it.toByte()}));val back=ByteBuffer.allocate(256);p.readBack(0,0xf3dc0000,0,back);assertContentEquals(ByteArray(256){it.toByte()},back.array());c.tick=observed;p.finish(0,completed,observed)}
  fun state()=h.state()["program"]as Map<*,*>;fun latch()=(h.state()["pioEnableStatus"]as Map<*,*>)["writeEnabledBySelector"]as List<*>
 }
 @Test fun commandAcceptanceStagingAndArrayCommitHaveSeparateExactDeadlines(){val f=Fixture();f.enable();f.configure();val peer=checkNotNull(f.peer);peer.check(0xf3dc0000,256,4,43);assertNull(peer.claim(0,0xf3dc0000,256,4,43));assertEquals(0,f.c.prepared);f.submit();assertEquals(18L,f.mmio.nextDeadline());f.advance(17);assertNull(peer.acceptedAt());assertEquals(listOf(true,false),f.latch());f.advance(18);assertEquals(0x20L,f.read(0x70));assertEquals(listOf(false,false),f.latch());assertEquals(0,f.c.prepared);assertNull(f.mmio.nextDeadline());f.write(0x70,0x20);assertEquals(0L,f.read(0x70));f.claim();f.stage();assertEquals(1,f.c.prepared);assertEquals(0,f.c.commits);assertTrue(f.c.bytes.all{it==(-1).toByte()});assertEquals(4146L,f.mmio.nextDeadline());f.advance(4145);assertEquals(0,f.c.commits);f.advance(4146);assertEquals(1,f.c.commits);assertContentEquals(ByteArray(256){it.toByte()},f.c.bytes.copyOfRange(0,256));assertTrue(f.c.bytes.drop(256).all{it==(-1).toByte()});assertNull(f.mmio.nextDeadline());assertEquals(1L,f.state()["committed"]);f.advance(5000);assertEquals(1,f.c.commits)}
 @Test fun wipRemainsTrueUntilArrayCommitAndEqualDeadlineStatusSamplesAfterCommit(){for(observed in listOf(4146L,9000L)){val f=Fixture();f.enable();f.configure();f.submit();f.advance(18);f.claim();f.stage();f.c.tick=60;f.write(0x90,0x05800001);f.advance(68);assertEquals(1L,f.read(0xa0));f.c.tick=4138;f.write(0x90,0x05800001);f.advance(observed);assertEquals(0L,f.read(0xa0));assertEquals(1,f.c.commits);val last=(f.h.state()["events"]as List<*>).map{it as Map<*,*>}.takeLast(2);assertEquals(listOf("program-array-complete","pio-complete"),last.map{it["operation"]})}}
 @Test fun lateDmaObservationStartsArrayDelayAtActualObservedStaging(){val f=Fixture();f.enable();f.configure();f.submit();f.advance(18);f.claim();f.stage(1000,50);assertEquals(5096L,f.mmio.nextDeadline());f.advance(5095);assertEquals(0,f.c.commits);f.advance(5096);assertEquals(1,f.c.commits)}
 @Test fun missingWriteEnableOpcodeCapabilityIdentityAndOverflowRefuseBeforeCommandMutation(){for(kind in listOf("enable","opcode","capability","identity","overflow","negative")){val f=Fixture();if(kind!="enable")f.enable();f.configure();when(kind){"opcode"->f.write(8,0x13);"capability"->f.c.claims.remove("test.image.program");"identity"->f.c.mismatch=true;"overflow"->f.c.tick=Long.MAX_VALUE-100;"negative"->f.c.tick=-1};val before=f.h.state();assertFails{f.write(0x70,1)};assertEquals(before,f.h.state());assertEquals(0,f.c.prepared);assertFalse(f.c.pending);assertNull(f.mmio.nextDeadline())}}
 @Test fun rejectedDescriptorShapeNeverArmsOrConsumesEnable(){for((o,v)in listOf(0 to 0x40008000L,0x20 to 0x303L,0x74 to 0L,0x78 to 0xf3dc0100L,0x1c to 0xf3dc0100L,0x80 to 7L,0xe0 to 1L,0xf0 to 1L)){val f=Fixture();f.enable();f.configure();f.write(o,v);val before=f.h.state();assertFails{f.write(0x70,1)};assertEquals(before,f.h.state());assertEquals(listOf(true,false),f.latch());assertEquals(0,f.c.prepared)}}
 @Test fun badDmaBoundsWidthRequestAndStreamOwnershipNeverCommit(){val f=Fixture();f.enable();f.configure();val p=checkNotNull(f.peer);for((address,size,width,request)in listOf(listOf(0xf3dc0001L,256L,4L,43L),listOf(0xf3dc00fcL,8L,4L,43L),listOf(0xf3dc0000L,260L,4L,43L),listOf(0xf3de0000L,256L,4L,43L),listOf(0xf3dc0000L,256L,2L,43L),listOf(0xf3dc0000L,256L,4L,42L))){val before=f.h.state();assertFails{p.check(address,size,width.toInt(),request.toInt())};assertEquals(before,f.h.state())};f.submit();f.advance(18);f.claim();assertFails{p.claim(1,0xf3dc0000,256,4,43)};assertFails{p.write(1,0xf3dc0000,0,ByteBuffer.allocate(256))};assertFails{p.write(0,0xf3dc0000,4,ByteBuffer.allocate(256))};assertFails{p.readBack(0,0xf3dc0000,0,ByteBuffer.allocate(4))};assertFails{p.finish(0,18,18)};assertEquals(0,f.c.prepared)}
 @Test fun cancellationReleasesOnlyOwnerAndKeepsAcceptedRequestForRetry(){val f=Fixture();f.enable();f.configure();f.submit();f.advance(18);f.claim();val p=checkNotNull(f.peer);p.write(0,0xf3dc0000,0,ByteBuffer.allocate(4));p.release(1);assertFails{p.claim(1,0xf3dc0000,256,4,43)};p.release(0);assertEquals(18L,p.claim(1,0xf3dc0000,256,4,43));val bytes=ByteBuffer.wrap(ByteArray(256){0x5a});p.write(1,0xf3dc0000,0,bytes);f.c.tick=50;p.finish(1,50,50);p.release(1);f.advance(4146);assertContentEquals(ByteArray(256){0x5a},f.c.bytes.copyOfRange(0,256));assertEquals(1,f.c.commits)}
 @Test fun busyReconfigurationAndUnsupportedControlCannotChangePendingOrLatches(){val f=Fixture();f.enable();f.configure();val before=f.h.state();assertFails{f.write(0x70,0x20)};assertEquals(before,f.h.state());f.submit();for((o,v)in listOf(0x70 to 1L,0x70 to 0L,8 to 0x13L,0x78 to 0xf3dc0100L,0x7c to 128L,0x90 to 0x06000001L,0x90 to 0x120b0001L,0x60 to 1L)){val state=f.h.state();assertFails{f.write(o,v)};assertEquals(state,f.h.state())};assertFails{f.write(0,0x40008000)};assertEquals(0,f.c.prepared)}
 @Test fun prepareAndCommitFailuresRemainVisibleWithoutProgramCompletion(){for(stage in listOf("prepare","commit")){val f=Fixture();f.enable();f.configure();f.submit();f.advance(18);f.claim();if(stage=="prepare"){f.c.failPrepare=true;assertFails{f.stage()}}else{f.stage();f.c.failCommit=true;assertFails{f.advance(4146)}};assertNotNull(f.state()["failure"]);assertNotNull(f.state()["pending"]);assertEquals(0L,f.state()["committed"]);assertEquals(0,f.c.commits);assertTrue(f.c.bytes.all{it==(-1).toByte()});assertFalse((f.h.state()["events"]as List<*>).map{it as Map<*,*>}.any{it["operation"]=="program-array-complete"});assertFails{f.advance(10000)};f.h.close()}}
 @Test fun closeAtEachPhaseCancelsUncommittedStorageAndRejectsFurtherGuestWork(){for(stage in 0..2){val f=Fixture();f.enable();f.configure();f.submit();if(stage>=1){f.advance(18);f.claim()};if(stage==2)f.stage();f.h.close();f.h.close();assertEquals(if(stage==2)1 else 0,f.c.cancelled);assertEquals(0,f.c.commits);assertFalse(f.c.pending);assertNull(f.mmio.nextDeadline());assertFails{f.advance(10000)};assertTrue(f.c.bytes.all{it==(-1).toByte()})}}
 @Test fun oldV3NeverPublishesDestinationOrAllowsProgramRegisters(){val f=Fixture(FlashFactory.ERASE_PROFILE);assertNull(f.peer);for(o in listOf(0x70,0x78,0x7c)){val before=f.h.state();assertFails{f.write(o,0)};assertEquals(before,f.h.state())};assertFalse("program" in f.h.state())}
 @Test fun lateProgramAcceptAckAfterArrayCommitClearsLatchedW1cFlagWithoutError() {
  // Models DAT 0x011e0368..0x011e037c when concurrent DMA + array commit completes before guest writes W1C 0x20 to 0xfffd8070.
  val f = Fixture()
  f.enable()
  f.configure()
  f.submit()
  f.advance(18)
  assertEquals(0x20L, f.read(0x70))
  // DMA claims, transfers, stages, and array commits while guest is preempted before writing W1C 0x20
  f.claim()
  f.stage()
  f.advance(4146)
  assertEquals(1, f.c.commits)
  assertNull(f.state()["pending"])
  assertEquals(0x20L, f.read(0x70))
  // Late W1C ACK cleanly clears bit 5 without throwing
  f.c.tick = 4200
  f.write(0x70, 0x20)
  assertEquals(0L, f.read(0x70))
  // Second redundant ACK with bit 5 already clear still refuses atomically
  val before = f.h.state()
  assertFails { f.write(0x70, 0x20) }
  assertEquals(before, f.h.state())
 }
}
