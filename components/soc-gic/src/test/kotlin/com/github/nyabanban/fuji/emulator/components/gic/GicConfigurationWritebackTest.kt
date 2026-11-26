package com.github.nyabanban.fuji.emulator.components.gic

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class GicConfigurationWritebackTest {
 private class Context:ComponentContext {
  val claims=linkedMapOf<String,Any>()
  override val directory=Path.of(".").toAbsolutePath()
  override val clock=EmulationClock{123L}
  override val ports=object:Ports {
   override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){check(claims.putIfAbsent(name,port)==null)}
   override fun<T:Any>find(name:String,type:Class<T>):T?=claims[name]?.let{type.cast(it)}
   override fun removeOwner(owner:String){}
  }
  override val events=object:EventChannel{override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun readData(relative:String):Map<String,Any?> = error("No data inputs")
  override fun dataPath(relative:String)=directory.resolve(relative)
 }
 private class Fixture(cluster:Boolean):AutoCloseable {
  val context=Context()
  val factory=GicFactory()
  val hardware=factory.create(context,factory.descriptor.settings.associate{it.key to it.default}+mapOf("coreProfile" to if(cluster)"four-context-functional-experimental-v1" else ""))
  val d=checkNotNull(context.ports.find("soc-gic.distributor",MmioDevice::class.java))
  val c=checkNotNull(context.ports.find("soc-gic.interface",MmioDevice::class.java))
  val cpu=checkNotNull(context.ports.find("soc-gic.cpu",CpuInterruptPort::class.java))
  val cores=context.ports.find("soc-gic.cores",CoreInterruptPort::class.java)
  val lines=checkNotNull(context.ports.find("soc-gic.lines",InterruptSink::class.java))
  fun select(core:Int){if(cores!=null)cores.selectCore(core) else require(core==0)}
  fun configure(){d.write(0,4,1);for(core in 0 until if(cores==null)1 else 4){select(core);c.write(0,4,1);c.write(4,4,255)}}
  fun state()=hardware.state()
  override fun close(){hardware.close()}
 }
 @Test fun exactNaturalWordPreservesEveryCoreAndActiveOwner() {
  Fixture(true).use{f->f.configure();f.select(0);f.d.write(0xc0c,4,0x80800000);f.d.write(0x104,4,1L shl 31);f.d.write(0x83f,1,8);f.select(3);f.lines.setLine(63,true);assertEquals(63L,f.c.read(0xc,4));f.select(0)
   val before=f.state();assertEquals(0x80800000L,f.d.read(0xc0c,4));f.d.write(0xc0c,4,0x80800000);assertEquals(before,f.state());assertNull(f.cpu.choose());assertFails{f.c.write(0x10,4,63)};assertEquals(before,f.state())
   f.select(3);assertEquals(1023L,f.c.read(0xc,4));f.c.write(0x10,4,63);assertNull(f.cpu.choose());f.lines.setLine(63,false);f.lines.setLine(63,true);assertEquals(63L,f.c.read(0xc,4));f.c.write(0x10,4,63)
  }
 }
 @Test fun sameCoreActiveAndPendingEdgeSurviveRepeatedWritebackUntilRealEoi() {
  for(cluster in listOf(false,true))Fixture(cluster).use{f->f.configure();f.select(0);f.d.write(0xc08,4,2L shl 10);f.d.write(0x104,4,1L shl 5);f.lines.setLine(37,true);assertEquals(37L,f.c.read(0xc,4));f.lines.setLine(37,false);f.lines.setLine(37,true)
   val before=f.state();repeat(10){f.d.write(0xc08,4,2L shl 10)};assertEquals(before,f.state());assertEquals(1023L,f.c.read(0xc,4));f.c.write(0x10,4,37);assertEquals(37L,f.c.read(0xc,4));f.c.write(0x10,4,37);assertNull(f.cpu.choose())
  }
 }
 @Test fun activeFieldAndEveryReservedBitRefuseWhileQuietNeighboursChangeOnAllCores() {
  Fixture(true).use{f->f.configure();f.select(0);val original=0x80800000L;f.d.write(0xc0c,4,original);f.d.write(0x104,4,1L shl 31);f.d.write(0x83f,1,8);f.select(3);f.lines.setLine(63,true);assertEquals(63L,f.c.read(0xc,4))
   for(core in 0..3){f.select(core);val before=f.state();for(bit in 0..31){val value=original xor(1L shl bit);if(bit%2==0||bit==31){assertFails{f.d.write(0xc0c,4,value)}}else{f.d.write(0xc0c,4,value);assertEquals(value,f.d.read(0xc0c,4));f.d.write(0xc0c,4,original)};assertEquals(before,f.state())};for((offset,size,value)in listOf(Triple(0xc0cL,1,0L),Triple(0xc0cL,2,0L),Triple(0xc0eL,4,original),Triple(0xc0cL,4,0x1_0000_0000L),Triple(0xc0cL,4,-1L))){assertFails{f.d.write(offset,size,value)};assertEquals(before,f.state())}}
   f.select(3);f.c.write(0x10,4,63);f.select(0);f.d.write(0xc0c,4,0);assertEquals(0L,f.d.read(0xc0c,4))
  }
 }
 @Test fun privateConfigurationIsBankedAndExactWritebackCannotAcknowledgeSgi() {
  Fixture(true).use{f->f.configure();f.select(0);f.d.write(0xc00,4,0x80000000);f.d.write(0xf00,4,0x00080005);f.select(3);assertEquals(0L,f.d.read(0xc00,4));assertEquals(5L,f.c.read(0xc,4));val before=f.state();f.d.write(0xc00,4,0);assertEquals(before,f.state());assertFails{f.d.write(0xc00,4,2)};assertEquals(before,f.state());f.c.write(0x10,4,5);f.select(0);assertEquals(0x80000000L,f.d.read(0xc00,4))}
 }
 @Test fun everyInactiveWordRetainsExistingWriteReadLawAndRefusesReservedBits() {
  for(cluster in listOf(false,true))Fixture(cluster).use{f->for(core in 0 until if(cluster)4 else 1){f.select(core);for(word in 0 until 32){val offset=0xc00+word*4L;val value=0xaaaaaaaaL xor(2L shl((word%16)*2));f.d.write(offset,4,value);assertEquals(value,f.d.read(offset,4));val before=f.state();f.d.write(offset,4,value);assertEquals(before,f.state());for(bit in 0..15){assertFails{f.d.write(offset,4,value or(1L shl(bit*2)))};assertEquals(before,f.state())};f.d.write(offset,4,0);assertEquals(0L,f.d.read(offset,4))}}}
 }
 @Test fun unchangedActiveSpiKeepsItsOwnerAcrossEveryOwnerAndWriter() {
  for(owner in 0..3)for(writer in 0..3)Fixture(true).use { f ->
   f.configure();f.select(owner);f.d.write(0xc14,4,2);f.d.write(0x108,4,1L shl 16);f.d.write(0x850,1,1L shl owner)
   f.lines.setLine(80,true);assertEquals(80L,f.c.read(0xc,4));f.select(writer)
   val before=f.state();f.d.write(0xc14,4,10);val after=f.state()
   assertEquals(10L,f.d.read(0xc14,4));assertEquals(before-"cores",after-"cores")
   val oldCores=before["cores"] as List<*>;val newCores=after["cores"] as List<*>
   for(core in 0..3){val old=oldCores[core] as Map<*,*>;val new=newCores[core] as Map<*,*>;assertEquals(old-"edgeLines",new-"edgeLines");assertEquals(listOf(80,81),new["edgeLines"])}
   f.d.write(0xc14,4,10);assertEquals(after,f.state())
   assertFails{f.d.write(0xc14,4,8)};assertEquals(after,f.state())
   if(writer!=owner){assertFails{f.c.write(0x10,4,80)};assertEquals(after,f.state())}
   f.select(owner);f.lines.setLine(80,false);f.c.write(0x10,4,80);assertNull(f.cpu.choose())
   f.d.write(0x108,4,1L shl 17);f.d.write(0x851,1,1L shl owner);f.lines.setLine(81,true);assertEquals(81L,f.c.read(0xc,4));f.c.write(0x10,4,81)
  }
 }
 @Test fun everyChangedFieldIsCheckedBeforeAnyStore() {
  for(condition in listOf("enabled","pending","high"))for(writer in 0..3)Fixture(true).use { f ->
   f.configure();f.select(3);val original=2L shl 30;f.d.write(0xc0c,4,original);f.d.write(0x104,4,1L shl 31);f.d.write(0x83f,1,8);f.lines.setLine(63,true);assertEquals(63L,f.c.read(0xc,4))
   when(condition){"enabled"->f.d.write(0x104,4,1L shl 17);"pending"->f.d.write(0x204,4,1L shl 17);"high"->f.lines.setLine(49,true)}
   f.select(writer);val before=f.state()
   assertEquals("GIC active configuration change unqualified",assertFailsWith<IllegalArgumentException>{f.d.write(0xc0c,4,original or 2 or 8)}.message)
   assertEquals(before,f.state());assertEquals(original,f.d.read(0xc0c,4))
   assertEquals("unsupported GIC configuration reserved bits",assertFailsWith<IllegalArgumentException>{f.d.write(0xc0c,4,original or 3 or 8)}.message);assertEquals(before,f.state())
   assertFails{f.d.write(0xc0c,4,2)};assertEquals(before,f.state())
  }
 }
 @Test fun privateMixedActiveWordRemainsUnqualifiedAndInactivePathKeepsItsOldLaw() {
  Fixture(true).use { f ->
   f.configure();f.select(2);f.d.write(0x100,4,1L shl 16);f.d.write(0x200,4,1L shl 16);assertEquals(16L,f.c.read(0xc,4))
   val before=f.state();assertFails{f.d.write(0xc04,4,8)};assertEquals(before,f.state());f.c.write(0x10,4,16)
   f.d.write(0x104,4,2);f.d.write(0x204,4,2);f.lines.setLine(33,true)
   f.d.write(0xc08,4,8);assertEquals(8L,f.d.read(0xc08,4));f.d.write(0xc08,4,0);assertEquals(0L,f.d.read(0xc08,4))
  }
 }
}
