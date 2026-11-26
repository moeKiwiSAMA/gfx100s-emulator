package com.github.nyabanban.fuji.emulator.components.dma
import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class DmaDestinationRoutesTest {
 private class Context:ComponentContext {var tick=0L;var data:Map<String,Any?> = mapOf("schema" to 1,"profile" to DestinationRoutes.PROFILE,"ports" to listOf("test.flash"));val claims=linkedMapOf<String,Any>();val irqs=mutableMapOf<Int,Boolean>();override val directory=Path.of(".").toAbsolutePath();override val clock=EmulationClock{tick};override fun readData(relative:String):Map<String,Any?> {require(relative=="routes.yaml");return data};override val ports=object:Ports{override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){check(claims.putIfAbsent(name,port)==null)};override fun<T:Any>find(name:String,type:Class<T>):T?=claims[name]?.let{type.cast(it)};override fun removeOwner(owner:String){}};override val events=object:EventChannel{override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}};override fun dataPath(relative:String)=directory.resolve(relative)}
 private class Ram:DmaMemory {val bytes=ByteArray(4096){it.toByte()};var reads=0;var writes=0;override fun check(address:Long,size:Long,write:Boolean){require(!write&&address>=0x100000&&size>0&&address<=0x101000-size)};override fun read(address:Long,destination:ByteBuffer){check(address,destination.remaining().toLong(),false);reads++;destination.put(bytes,(address-0x100000).toInt(),destination.remaining())};override fun write(address:Long,source:ByteBuffer){writes++;error("No destination RAM writes expected")}}
 private class Sink(val c:Context,var base:Long,val request:Int):RangedDmaDestinationPeer {val bytes=ByteArray(64);var ready:Long?=0;var owner:Int?=null;var received=0;var claims=0;var releases=0;var finishes=0;var lie=false;var badRange=false
  override fun destinationRanges()=listOf(AddressWindow("test.endpoint",base,if(badRange)Long.MAX_VALUE else 4));override fun matches(address:Long)=if(lie)address==0x100000L else address==base
  override fun check(address:Long,size:Long,width:Int,request:Int){require(matches(address)&&size==64L&&width==4&&request==this.request)}
  override fun acceptedAt()=ready?.takeIf{c.tick>=it};override fun claim(channel:Int,address:Long,size:Long,width:Int,request:Int):Long?{check(address,size,width,request);val at=acceptedAt()?:return null;check(owner==null||owner==channel);owner=channel;claims++;return at};override fun release(channel:Int){if(owner==channel){owner=null;received=0;releases++}}
  override fun write(channel:Int,address:Long,offset:Long,source:ByteBuffer){check(owner==channel&&address==base&&offset==received.toLong());val n=source.remaining();source.get(bytes,received,n);received+=n};override fun readBack(channel:Int,address:Long,offset:Long,destination:ByteBuffer){check(owner==channel);destination.put(bytes,offset.toInt(),destination.remaining())};override fun finish(channel:Int,completedAt:Long,observedAt:Long){check(owner==channel&&received==64);owner=null;finishes++}
 }
 private data class Fixture(val c:Context,val ram:Ram,val serial:Sink,val flash:Sink,val h:HardwareComponent,val dma:ScheduledMmioDevice,val mux:MmioDevice)
 private fun fixture(change:(Context,Sink,Sink)->Unit = {_,_,_->}):Fixture{val c=Context();val ram=Ram();val serial=Sink(c,0xfff30024,21);val flash=Sink(c,0xf3dc0000,43);c.claims["cpu.memory"]=ram;c.claims["test.serial"]=serial;c.claims["test.flash"]=flash;c.claims["test.irq"]=object:InterruptSink{override fun setLine(irq:Int,high:Boolean){c.irqs[irq]=high}};change(c,serial,flash);val factory=DmaFactory();val cfg=factory.descriptor.settings.associate{it.key to it.default}+mapOf("transmitPeerPort" to "test.serial","destinationPeersFile" to "routes.yaml","interruptPort" to "test.irq");val h=factory.create(c,cfg);h.start();return Fixture(c,ram,serial,flash,h,c.ports.find("soc-dma.registers",MmioDevice::class.java)as ScheduledMmioDevice,checkNotNull(c.ports.find("soc-dma.mux",MmioDevice::class.java)))}
 private fun configure(f:Fixture,channel:Int,destination:Long,request:Int,source:Long=0x100000){val shift=channel%2*8;f.mux.write(channel/2*4L,4,(if(shift==0)0x10000L else 0x1000000L)or(request.toLong()shl shift));listOf(0x10 to 63L,0x14 to source,0x18 to destination,0x1c to 0x20000L,0x20 to 0x20004L,0x24 to 0L).forEach{(o,v)->f.dma.write(channel*4096L+o,4,v)};f.dma.write(channel*4096L+0x28,4,0x201011)}
 @Test fun separatePeersCanWaitAndCompleteConcurrentlyInDeadlineOrder(){val f=fixture();f.flash.ready=20;f.serial.ready=10;configure(f,0,0xf3dc0000,43);configure(f,1,0xfff30024,21,0x100040);f.dma.write(0x28,4,0x10201011);f.dma.write(0x1028,4,0x10201011);assertNull(f.dma.nextDeadline());f.c.tick=40;f.dma.beforeInstruction(40);assertContentEquals(f.ram.bytes.copyOfRange(0,64),f.flash.bytes);assertContentEquals(f.ram.bytes.copyOfRange(64,128),f.serial.bytes);assertEquals(0,f.ram.writes);assertEquals(1,f.flash.finishes);assertEquals(1,f.serial.finishes);val events=(f.h.state()["events"]as List<*>).map{it as Map<*,*>};assertEquals(listOf(1,0),events.map{it["channel"]});assertEquals(listOf(30L,40L),events.map{it["completedAt"]});assertEquals(listOf("test.serial","test.flash"),events.map{it["destinationPeer"]});assertEquals(true,f.c.irqs[207]);assertEquals(true,f.c.irqs[208]);f.h.close()}
 @Test fun simultaneousSameDeadlineUsesExistingChannelTiePolicyAcrossPeers(){val f=fixture();configure(f,0,0xf3dc0000,43);configure(f,1,0xfff30024,21);f.dma.write(0x1028,4,0x10201011);f.dma.write(0x28,4,0x10201011);f.c.tick=20;f.dma.beforeInstruction(20);assertEquals(listOf(0,1),(f.h.state()["events"]as List<*>).map{(it as Map<*,*>)["channel"]});f.h.close()}
 @Test fun samePeerStillHasOneSubmissionAndCancelTargetsItsOwnLease(){val f=fixture();configure(f,0,0xf3dc0000,43);configure(f,1,0xfff30024,21);configure(f,2,0xf3dc0000,43);f.dma.write(0x28,4,0x10201011);f.dma.write(0x1028,4,0x10201011);val before=f.h.state();assertFails{f.dma.write(0x2028,4,0x10201011)};assertEquals(before,f.h.state());f.dma.write(0x28,4,0x201011);assertNull(f.flash.owner);assertEquals(1,f.flash.releases);assertEquals(1,f.serial.owner);assertEquals(0,f.serial.releases);f.dma.write(0x2028,4,0x10201011);assertEquals(2,f.flash.owner);f.h.close();assertNull(f.flash.owner);assertNull(f.serial.owner)}
 @Test fun addressOverlapAliasMissingUnrangedAndInvalidRangesRefuseBeforeRamReadOrBusy(){for(kind in listOf("overlap","alias","missing","unranged","range","lie")){val f=fixture{c,s,p->when(kind){"overlap"->p.base=s.base;"alias"->c.claims["test.flash"]=s;"missing"->c.claims.remove("test.flash");"unranged"->c.claims["test.flash"]=object:DmaDestinationPeer by p{};"range"->p.badRange=true;"lie"->p.lie=true}};configure(f,0,0xf3dc0000,43);val before=f.h.state();assertFails{f.dma.write(0x28,4,0x10201011)};assertEquals(before,f.h.state());assertEquals(0,f.ram.reads);assertEquals(0,f.flash.claims);assertEquals(0,f.serial.claims);f.h.close()}}
 @Test fun routeFileStrictSchemaPortableNamesAndDuplicateLegacyAreRejectedBeforePorts(){val valid=mapOf("schema" to 1,"profile" to DestinationRoutes.PROFILE,"ports" to listOf("test.flash"));val bad=listOf(valid+mapOf("schema" to 1.0),valid+mapOf("profile" to "unknown"),valid+mapOf("extra" to 1),valid-mapOf("schema" to 1).keys,valid+mapOf("ports" to emptyList<String>()),valid+mapOf("ports" to listOf("test.flash","test.flash")),valid+mapOf("ports" to listOf("test.serial")),valid+mapOf("ports" to listOf("../escape")),valid+mapOf("ports" to listOf(null)),valid+mapOf("ports" to List(17){"test.peer"+it}));for(data in bad){val c=Context();c.data=data;val factory=DmaFactory();val cfg=factory.descriptor.settings.associate{it.key to it.default}+mapOf("transmitPeerPort" to "test.serial","destinationPeersFile" to "routes.yaml");assertFails{factory.create(c,cfg)};assertNull(c.claims["soc-dma.registers"])};val c=Context();val factory=DmaFactory();assertFails{factory.create(c,factory.descriptor.settings.associate{it.key to it.default}+mapOf("destinationPeersFile" to 7))};assertNull(c.claims["soc-dma.registers"])}
 @Test fun wrongDestinationRequestOrSourceCannotClaimAnyPeer(){for(kind in listOf("route","source","address")){val f=fixture();configure(f,0,if(kind=="address")0xf3dc0001 else 0xf3dc0000,if(kind=="route")42 else 43,if(kind=="source")0x200000 else 0x100000);val before=f.h.state();assertFails{f.dma.write(0x28,4,0x10201011)};assertEquals(before,f.h.state());assertEquals(0,f.flash.claims);assertEquals(0,f.ram.reads);f.h.close()}}

 @Test fun audioBurst16TxAndAudioRxSourcePeerTransferAndRaiseCompletionIrqs() {
  val c = Context()
  val ramBytes = ByteArray(4096) { it.toByte() }
  val rwRam = object : DmaMemory {
   override fun check(address: Long, size: Long, write: Boolean) {
    require(address >= 0x100000L && size > 0L && address + size <= 0x101000L)
   }
   override fun read(address: Long, destination: ByteBuffer) {
    destination.put(ramBytes, (address - 0x100000L).toInt(), destination.remaining())
   }
   override fun write(address: Long, source: ByteBuffer) {
    source.get(ramBytes, (address - 0x100000L).toInt(), source.remaining())
   }
  }
  val i2s0Tx = Sink(c, 0xfff4f004L, 29)
  val rxPayload = ByteArray(64) { (0x80 + it).toByte() }
  var rxConsumed = 0
  val i2s0Rx = object : DmaSourcePeer {
   private var owner: Int? = null
   private var cursor = 0
   override fun overlaps(address: Long, size: Long): Boolean = size > 0L && address < 0xfff4f004L && address + size > 0xfff4f000L
   override fun check(address: Long, size: Long) { require(address == 0xfff4f000L && size == 64L) }
   override fun acceptedAt(): Long? = 0L
   override fun claim(channel: Int, address: Long, size: Long): Long? { check(address, size); owner = channel; cursor = 0; return 0L }
   override fun release(channel: Int) { if (owner == channel) owner = null }
   override fun read(channel: Int, address: Long, destination: ByteBuffer) {
    check(owner == channel && address == 0xfff4f000L)
    val n = destination.remaining()
    destination.put(rxPayload, cursor, n)
    cursor += n
   }
   override fun consume(channel: Int) { check(owner == channel); owner = null; rxConsumed++ }
  }
  c.data = mapOf("schema" to 1, "profile" to DestinationRoutes.PROFILE, "ports" to listOf("soc-isp-setup.i2s0-dma-tx"))
  c.claims["cpu.memory"] = rwRam
  c.claims["soc-isp-setup.i2s0-dma-tx"] = i2s0Tx
  c.claims["soc-isp-setup.i2s0-dma-rx"] = i2s0Rx
  c.claims["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { c.irqs[irq] = high } }
  val factory = DmaFactory()
  val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
   "destinationPeersFile" to "routes.yaml",
   "audioRxSourceProfile" to DmaFactory.AUDIO_RX_SOURCE_PROFILE,
   "interruptPort" to "test.irq",
  )
  val h = factory.create(c, cfg)
  h.start()
  val dma = c.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
  val mux = checkNotNull(c.ports.find("soc-dma.mux", MmioDevice::class.java))
  // HW Ch 7 -> req 29 (I2S0 TX @ 0xFFF4F004, burst 16: r[7]=0x20F00, r[8]=0x20F04)
  mux.write(3L * 4L, 4, 0x0100_0000L or (29L shl 8))
  listOf(0x10 to 63L, 0x14 to 0x100000L, 0x18 to 0xfff4f004L, 0x1c to 0x20f00L, 0x20 to 0x20f04L, 0x24 to 0L).forEach { (o, v) -> dma.write(7L * 4096L + o, 4, v) }
  dma.write(7L * 4096L + 0x28L, 4, 0x10201011L)
  // HW Ch 4 -> req 28 (I2S0 RX @ 0xFFF4F000, burst 16: r[7]=0x20F04, r[8]=0x20F00)
  mux.write(2L * 4L, 4, 0x0001_0000L or 28L)
  listOf(0x10 to 63L, 0x14 to 0xfff4f000L, 0x18 to 0x100200L, 0x1c to 0x20f04L, 0x20 to 0x20f00L, 0x24 to 0L).forEach { (o, v) -> dma.write(4L * 4096L + o, 4, v) }
  dma.write(4L * 4096L + 0x28L, 4, 0x10201011L)
  val deadline = checkNotNull(dma.nextDeadline())
  assertTrue(deadline >= 64L / 4L * 562L, "Audio DMA should pace transfers at 48 kHz sample cadence, got deadline=$deadline")
  c.tick = deadline
  dma.beforeInstruction(deadline)
  assertContentEquals(ramBytes.copyOfRange(0, 64), i2s0Tx.bytes)
  assertContentEquals(rxPayload, ramBytes.copyOfRange(0x200, 0x240))
  assertEquals(1, i2s0Tx.finishes)
  assertEquals(1, rxConsumed)
  assertEquals(true, c.irqs[214]) // GIC 207+7
  assertEquals(true, c.irqs[211]) // GIC 207+4
  h.close()
 }

 @Test fun chainedRingAudioTxAndRxStreamRealPcmThroughI2sPeers() = runAudioChain(clearNextTx = false)

 @Test fun clearingNextTxDescriptorDuringPlaybackDoesNotWriteAnotherBlockOrStopRx() = runAudioChain(clearNextTx = true)

 private fun runAudioChain(clearNextTx: Boolean) {
  val c = Context()
  val ramBytes = ByteArray(4096) { idx -> if (idx < 64) (idx + 1).toByte() else 0 }
  val rwRam = object : DmaMemory {
   override fun check(address: Long, size: Long, write: Boolean) {
    require(address >= 0x100000L && size > 0L && address + size <= 0x101000L)
   }
   override fun read(address: Long, destination: ByteBuffer) {
    destination.put(ramBytes, (address - 0x100000L).toInt(), destination.remaining())
   }
   override fun write(address: Long, source: ByteBuffer) {
    source.get(ramBytes, (address - 0x100000L).toInt(), source.remaining())
   }
  }
  // Write 24-byte chained termination descriptor at 0x100400 (w5 = 0 -> terminates after second 64B chunk)
  ByteBuffer.wrap(ramBytes, 0x400, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
   putInt(63); putInt(0x100000); putInt(0xfff4f004.toInt()); putInt(0x20f00); putInt(0x20f04); putInt(0)
  }
  ByteBuffer.wrap(ramBytes, 0x420, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
   putInt(63); putInt(0xfff4f000.toInt()); putInt(0x100240); putInt(0x20f04); putInt(0x20f00); putInt(0)
  }
  val i2s0Tx = Sink(c, 0xfff4f004L, 29)
  val rxPayload = ByteArray(64) { (0x40 + it).toByte() }
  var rxConsumed = 0
  val i2s0Rx = object : DmaSourcePeer {
   private var owner: Int? = null
   private var cursor = 0
   override fun overlaps(address: Long, size: Long): Boolean = size > 0L && address < 0xfff4f004L && address + size > 0xfff4f000L
   override fun check(address: Long, size: Long) { require(address == 0xfff4f000L && size == 64L) }
   override fun acceptedAt(): Long? = 0L
   override fun claim(channel: Int, address: Long, size: Long): Long? { check(address, size); owner = channel; cursor = 0; return 0L }
   override fun release(channel: Int) { if (owner == channel) owner = null }
   override fun read(channel: Int, address: Long, destination: ByteBuffer) {
    check(owner == channel && address == 0xfff4f000L)
    val n = destination.remaining()
    destination.put(rxPayload, cursor, n)
    cursor += n
   }
   override fun consume(channel: Int) { check(owner == channel); owner = null; rxConsumed++ }
  }
  c.data = mapOf("schema" to 1, "profile" to DestinationRoutes.PROFILE, "ports" to listOf("soc-isp-setup.i2s0-dma-tx"))
  c.claims["cpu.memory"] = rwRam
  c.claims["soc-isp-setup.i2s0-dma-tx"] = i2s0Tx
  c.claims["soc-isp-setup.i2s0-dma-rx"] = i2s0Rx
  c.claims["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { c.irqs[irq] = high } }
  val factory = DmaFactory()
  val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
   "destinationPeersFile" to "routes.yaml",
   "chainedRingProfile" to DmaFactory.CHAINED_RING_PROFILE,
   "audioRxSourceProfile" to DmaFactory.AUDIO_RX_SOURCE_PROFILE,
   "interruptPort" to "test.irq",
  )
  val h = factory.create(c, cfg)
  h.start()
  val dma = c.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
  val mux = checkNotNull(c.ports.find("soc-dma.mux", MmioDevice::class.java))
  // Chained TX on HW Ch 7 (req 29)
  mux.write(3L * 4L, 4, 0x0100_0000L or (29L shl 8))
  listOf(0x10 to 63L, 0x14 to 0x100000L, 0x18 to 0xfff4f004L, 0x1c to 0x20f00L, 0x20 to 0x20f04L, 0x24 to 0x100401L).forEach { (o, v) -> dma.write(7L * 4096L + o, 4, v) }
  dma.write(7L * 4096L + 0x28L, 4, 0x10201011L)
  // Chained RX on HW Ch 4 (req 28)
  mux.write(2L * 4L, 4, 0x0001_0000L or 28L)
  listOf(0x10 to 63L, 0x14 to 0xfff4f000L, 0x18 to 0x100200L, 0x1c to 0x20f04L, 0x20 to 0x20f00L, 0x24 to 0x100421L).forEach { (o, v) -> dma.write(4L * 4096L + o, 4, v) }
  dma.write(4L * 4096L + 0x28L, 4, 0x10201011L)
  if (clearNextTx) ramBytes.fill(0, 0x400, 0x418)
  // Beat 1: transfers first 64B chunk & loads chained descriptor at exact 48 kHz 2-word/frame cadence
  // (64B = 16 u32 words = 8 stereo frames @ 48 kHz -> 16 * 1125 / 2 = 9,000 ticks @ 54 MHz)
  val d1 = checkNotNull(dma.nextDeadline())
  assertEquals(9_000L, d1, "Chained audio ring must pace 2-word/frame I2S slots at exact 48 kHz (562.5 ticks/word)")
  c.tick = d1
  dma.beforeInstruction(d1)
  assertEquals(1, i2s0Tx.finishes)
  assertEquals(1, rxConsumed)
  assertContentEquals(ramBytes.copyOfRange(0, 64), i2s0Tx.bytes)
  assertContentEquals(rxPayload, ramBytes.copyOfRange(0x200, 0x240))
  // Beat 2: transfers second 64B chunk & terminates chain (w5 == 0 -> raises GIC 214 & 211)
  i2s0Tx.received = 0
  val d2 = checkNotNull(dma.nextDeadline())
  assertEquals(18_000L, d2)
  c.tick = d2
  dma.beforeInstruction(d2)
  assertEquals(if (clearNextTx) 1 else 2, i2s0Tx.finishes)
  assertEquals(2, rxConsumed)
  assertContentEquals(rxPayload, ramBytes.copyOfRange(0x240, 0x280))
  assertEquals(true, c.irqs[214])
  assertEquals(true, c.irqs[211])
  h.close()
 }

 @Test fun chainedRingAudioTxAndRxHoldExact48KhzWithoutCumulativeJitterDrift() {
  val c = Context()
  val ramBytes = ByteArray(4096)
  val rwRam = object : DmaMemory {
   override fun check(address: Long, size: Long, write: Boolean) {
    require(address >= 0x100000L && size > 0L && address + size <= 0x101000L)
   }
   override fun read(address: Long, destination: ByteBuffer) {
    destination.put(ramBytes, (address - 0x100000L).toInt(), destination.remaining())
   }
   override fun write(address: Long, source: ByteBuffer) {
    source.get(ramBytes, (address - 0x100000L).toInt(), source.remaining())
   }
  }
  // Circular self-linked 64B descriptors for continuous TX (0x100400) and RX (0x100420)
  ByteBuffer.wrap(ramBytes, 0x400, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
   putInt(63); putInt(0x100000); putInt(0xfff4f004.toInt()); putInt(0x20f00); putInt(0x20f04); putInt(0x100401)
  }
  ByteBuffer.wrap(ramBytes, 0x420, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
   putInt(63); putInt(0xfff4f000.toInt()); putInt(0x100200); putInt(0x20f04); putInt(0x20f00); putInt(0x100421)
  }
  val i2s0Tx = Sink(c, 0xfff4f004L, 29)
  var rxConsumed = 0
  val i2s0Rx = object : DmaSourcePeer {
   private var owner: Int? = null
   override fun overlaps(address: Long, size: Long): Boolean = size > 0L && address < 0xfff4f004L && address + size > 0xfff4f000L
   override fun check(address: Long, size: Long) { require(address == 0xfff4f000L && size == 64L) }
   override fun acceptedAt(): Long? = 0L
   override fun claim(channel: Int, address: Long, size: Long): Long? { check(address, size); owner = channel; return 0L }
   override fun release(channel: Int) { if (owner == channel) owner = null }
   override fun read(channel: Int, address: Long, destination: ByteBuffer) { destination.position(destination.limit()) }
   override fun consume(channel: Int) { check(owner == channel); owner = null; rxConsumed++ }
  }
  c.data = mapOf("schema" to 1, "profile" to DestinationRoutes.PROFILE, "ports" to listOf("soc-isp-setup.i2s0-dma-tx"))
  c.claims["cpu.memory"] = rwRam
  c.claims["soc-isp-setup.i2s0-dma-tx"] = i2s0Tx
  c.claims["soc-isp-setup.i2s0-dma-rx"] = i2s0Rx
  c.claims["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { c.irqs[irq] = high } }
  val factory = DmaFactory()
  val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
   "destinationPeersFile" to "routes.yaml",
   "chainedRingProfile" to DmaFactory.CHAINED_RING_PROFILE,
   "audioRxSourceProfile" to DmaFactory.AUDIO_RX_SOURCE_PROFILE,
   "interruptPort" to "test.irq",
  )
  val h = factory.create(c, cfg)
  h.start()
  val dma = c.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
  val mux = checkNotNull(c.ports.find("soc-dma.mux", MmioDevice::class.java))
  mux.write(3L * 4L, 4, 0x0100_0000L or (29L shl 8))
  listOf(0x10 to 63L, 0x14 to 0x100000L, 0x18 to 0xfff4f004L, 0x1c to 0x20f00L, 0x20 to 0x20f04L, 0x24 to 0x100401L).forEach { (o, v) -> dma.write(7L * 4096L + o, 4, v) }
  dma.write(7L * 4096L + 0x28L, 4, 0x10201011L)
  mux.write(2L * 4L, 4, 0x0001_0000L or 28L)
  listOf(0x10 to 63L, 0x14 to 0xfff4f000L, 0x18 to 0x100200L, 0x1c to 0x20f04L, 0x20 to 0x20f00L, 0x24 to 0x100421L).forEach { (o, v) -> dma.write(4L * 4096L + o, 4, v) }
  dma.write(4L * 4096L + 0x28L, 4, 0x10201011L)

  // 1,000 beats of 64B (8 stereo frames @ 48 kHz = 9,000 ticks/beat) with +150..+3,900 ticks random-like
  // observation jitter per beat MUST accumulate 0 ticks drift (exact 48,000.00 Hz)!
  val beatTicks = 9_000L
  for (beat in 1..1000) {
   val expectedDeadline = beat.toLong() * beatTicks
   assertEquals(expectedDeadline, dma.nextDeadline(), "Beat $beat deadline drifted from exact 48 kHz cadence")
   i2s0Tx.received = 0
   val jitter = 150L + (beat.toLong() * 1307L) % 3_750L
   c.tick = expectedDeadline + jitter
   dma.beforeInstruction(c.tick)
  }
  assertEquals(1000, i2s0Tx.finishes)
  assertEquals(1000, rxConsumed)
  assertEquals(1001L * beatTicks, dma.nextDeadline(), "1,000 jittered 48 kHz beats must have 0 ticks cumulative drift")
  h.close()
 }

 @Test fun audio24BitDmaPacesAt48KhzPerStereoFrameNotPerWord() {
  val c = Context()
  val ramBytes = ByteArray(4096)
  val rwRam = object : DmaMemory {
   override fun check(address: Long, size: Long, write: Boolean) {
    require(address >= 0x100000L && size > 0L && address + size <= 0x101000L)
   }
   override fun read(address: Long, destination: ByteBuffer) {
    destination.put(ramBytes, (address - 0x100000L).toInt(), destination.remaining())
   }
   override fun write(address: Long, source: ByteBuffer) {
    source.get(ramBytes, (address - 0x100000L).toInt(), source.remaining())
   }
  }
  val i2s1Tx24 = object : RangedDmaDestinationPeer {
   override fun destinationRanges() = listOf(AddressWindow("soc-isp-setup.i2s1-dma-tx", 0xfff78004L, 4L))
   override fun matches(address: Long) = address == 0xfff78004L
   override fun check(address: Long, size: Long, width: Int, request: Int) { require(matches(address) && size == 512L && width == 4 && request == 30) }
   override fun acceptedAt(): Long? = 0L
   override fun claim(channel: Int, address: Long, size: Long, width: Int, request: Int): Long? = 0L
   override fun release(channel: Int) {}
   override fun write(channel: Int, address: Long, offset: Long, source: ByteBuffer) { source.position(source.limit()) }
   override fun readBack(channel: Int, address: Long, offset: Long, destination: ByteBuffer) { destination.position(destination.limit()) }
   override fun finish(channel: Int, completedAt: Long, observedAt: Long) {}
   override fun pacedBytesPerFrame(): Int = 8
  }
  val i2s0Rx24 = object : DmaSourcePeer {
   override fun overlaps(address: Long, size: Long): Boolean = size > 0L && address < 0xfff4f004L && address + size > 0xfff4f000L
   override fun check(address: Long, size: Long) { require(address == 0xfff4f000L && size == 512L) }
   override fun acceptedAt(): Long? = 0L
   override fun claim(channel: Int, address: Long, size: Long): Long? = 0L
   override fun release(channel: Int) {}
   override fun read(channel: Int, address: Long, destination: ByteBuffer) { destination.position(destination.limit()) }
   override fun consume(channel: Int) {}
   override fun pacedBytesPerFrame(): Int = 8
  }
  c.data = mapOf("schema" to 1, "profile" to DestinationRoutes.PROFILE, "ports" to listOf("soc-isp-setup.i2s1-dma-tx"))
  c.claims["cpu.memory"] = rwRam
  c.claims["soc-isp-setup.i2s1-dma-tx"] = i2s1Tx24
  c.claims["soc-isp-setup.i2s0-dma-rx"] = i2s0Rx24
  c.claims["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { c.irqs[irq] = high } }
  val factory = DmaFactory()
  val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
   "destinationPeersFile" to "routes.yaml",
   "audioRxSourceProfile" to DmaFactory.AUDIO_RX_SOURCE_PROFILE,
   "interruptPort" to "test.irq",
  )
  val h = factory.create(c, cfg)
  h.start()
  val dma = c.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
  val mux = checkNotNull(c.ports.find("soc-dma.mux", MmioDevice::class.java))
  // 512 bytes of 24-bit stereo = 64 stereo frames @ 48 kHz = 64 * 1,125 = 72,000 ticks (NOT 144,000!)
  mux.write(4L * 4L, 4, 0x0001_0000L or 30L)
  listOf(0x10 to 511L, 0x14 to 0x100000L, 0x18 to 0xfff78004L, 0x1c to 0x20f00L, 0x20 to 0x20f04L, 0x24 to 0L).forEach { (o, v) -> dma.write(8L * 4096L + o, 4, v) }
  dma.write(8L * 4096L + 0x28L, 4, 0x10201011L)
  assertEquals(72_000L, dma.nextDeadline(), "24-bit I2S TX 512B (64 frames) must pace at 48 kHz (72,000 ticks)")

  mux.write(2L * 4L, 4, 0x0001_0000L or 28L)
  listOf(0x10 to 511L, 0x14 to 0xfff4f000L, 0x18 to 0x100400L, 0x1c to 0x20f04L, 0x20 to 0x20f00L, 0x24 to 0L).forEach { (o, v) -> dma.write(4L * 4096L + o, 4, v) }
  dma.write(4L * 4096L + 0x28L, 4, 0x10201011L)
  assertEquals(72_000L, dma.nextDeadline(), "24-bit I2S RX 512B (64 frames) must pace at 48 kHz (72,000 ticks)")
  h.close()
 }

 @Test fun softwareMemToMemDmaSupportsUnalignedByteDestinationsAndLengthsForMovMuxer() {
  val c = Context()
  val ramBytes = ByteArray(16384) { idx -> if (idx < 8169) ((idx * 37 + 11) and 0xFF).toByte() else 0 }
  val rwRam = object : DmaMemory {
   override fun check(address: Long, size: Long, write: Boolean) {
    require(address >= 0x100000L && size > 0L && address + size <= 0x104000L)
   }
   override fun read(address: Long, destination: ByteBuffer) {
    destination.put(ramBytes, (address - 0x100000L).toInt(), destination.remaining())
   }
   override fun write(address: Long, source: ByteBuffer) {
    source.get(ramBytes, (address - 0x100000L).toInt(), source.remaining())
   }
  }
  c.claims["cpu.memory"] = rwRam
  c.claims["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { c.irqs[irq] = high } }
  val factory = DmaFactory()
  val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
   "softwareProfile" to "software-request44-experimental-v1",
   "interruptPort" to "test.irq",
  )
  val h = factory.create(c, cfg)
  h.start()
  val dma = c.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
  val mux = checkNotNull(c.ports.find("soc-dma.mux", MmioDevice::class.java))
  // Route HW ch 1 to software request 44 and copy 8169 bytes into odd destination 0x102001 with 0x30f00 burst geometry
  mux.write(0L, 4, 0x0100_0000L or (44L shl 8))
  listOf(0x10 to 8168L, 0x14 to 0x100000L, 0x18 to 0x102001L, 0x1c to 0x30f00L, 0x20 to 0x30f00L, 0x24 to 0L).forEach { (o, v) ->
   dma.write(1L * 4096L + o, 4, v)
  }
  dma.write(1L * 4096L + 0x28L, 4, 0x11100001L)
  val deadline = checkNotNull(dma.nextDeadline())
  c.tick = deadline
  dma.beforeInstruction(deadline)
  assertContentEquals(ramBytes.copyOfRange(0, 8169), ramBytes.copyOfRange(0x2001, 0x2001 + 8169))
  assertEquals(true, c.irqs[208])
  h.close()
 }
}
