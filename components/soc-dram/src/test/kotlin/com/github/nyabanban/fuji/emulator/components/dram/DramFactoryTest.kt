package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.zip.ZipFile
import kotlin.test.*

class DramFactoryTest {
 private val f=DramFixture()
 private class Registry:Ports {
  val ports=linkedMapOf<String,Pair<String,Any>>()
  override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){require(type.isInstance(port)&&name !in ports);ports[name]=owner to port}
  override fun<T:Any>find(name:String,type:Class<T>):T?=ports[name]?.second?.let{type.cast(it)}
  override fun removeOwner(owner:String){ports.entries.removeIf{it.value.first==owner}}
 }
 private class Context(var document:Map<String,Any?>):ComponentContext {
  override val directory=Path.of(DramTestPaths.value("studyOutput"),"component-data").toAbsolutePath().normalize()
  override val ports=Registry()
  var tick=0L;var loads=0;var eventsPublished=0
  override val clock=EmulationClock{tick}
  override val events=object:EventChannel {override fun publish(event:ComponentEvent){eventsPublished++};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun dataPath(relative:String):Path=directory.resolve(relative).normalize().also{require(it.startsWith(directory)&&relative.isNotEmpty())}
  override fun readData(relative:String):Map<String,Any?> {dataPath(relative);require(relative=="input.yaml");loads++;return document}
 }
 private fun document(indices:List<Int> = listOf(1)):MutableMap<String,Any?> = linkedMapOf("schema" to 1,"profile" to DramFactory.PROFILE,"firmwareSha256" to DramFactory.FIRMWARE,"programmingWords" to f.programming.map{(o,v)->mapOf("offset" to o,"value" to v)},"configurationWords" to f.config.map{(o,v)->mapOf("offset" to o,"value" to v)},"banks" to indices.associate{it.toString() to linkedMapOf<String,Any?>("responseTicks" to listOf(20,20,20,20,20),"initialController7044" to 0x80,"initialController7364" to 0x40,"responses" to(0..4).associate{stage->stage.toString() to f.reply(stage).toList()}.toMutableMap())}.toMutableMap())
 private fun config()=mapOf<String,Any>("profile" to DramFactory.PROFILE,"inputFile" to "input.yaml")
 private fun create(c:Context,factory:ComponentFactory=DramFactory())=factory.create(c,config()).also{it.start()}
 private fun program(c:Context,index:Int){val p=c.ports.find("soc-dram.phy-$index",MmioDevice::class.java)!!;f.programming.forEach{(o,v)->p.write(o.toLong(),4,v)}}
 private fun control(c:Context,index:Int)=c.ports.find("soc-dram.controller-$index",MmioDevice::class.java)!!
 @Test fun defaultDisabledJarHasNoPortsInputsInterruptsOrSideEffects() {
  val factory=DramFactory();val c=Context(emptyMap());val defaults=factory.descriptor.settings.associate{it.key to it.default};val h=factory.create(c,defaults);h.start();assertEquals(false,h.state()["enabled"]);assertTrue(h.additionalMmioPorts().isEmpty());assertTrue(h.interruptRoutes().isEmpty());assertTrue(c.ports.ports.isEmpty());assertEquals(0,c.loads);assertEquals(0,c.eventsPublished);assertTrue(factory.descriptor.settings.all{!it.live});h.close()
 }
 @Test fun actualJarServiceLoaderKeepsApiParentLoadedAndHardwareClassesIsolated() {
  val jar=Path.of(DramTestPaths.value("candidateJar"));ZipFile(jar.toFile()).use{z->val names=z.entries().asSequence().map{it.name}.toList();assertFalse(names.any{it.startsWith("com/github/nyabanban/fuji/emulator/api/")});assertEquals(1,names.count{it=="META-INF/services/com.github.nyabanban.fuji.emulator.api.ComponentFactory"});val clazz=z.getInputStream(z.getEntry("com/github/nyabanban/fuji/emulator/components/dram/DramFactory.class")).readAllBytes();assertEquals(69,((clazz[6].toInt()and 255)shl 8)or(clazz[7].toInt()and 255))}
  val prefix="com.github.nyabanban.fuji.emulator.components.dram."
  val loader=object:URLClassLoader(arrayOf(jar.toUri().toURL()),ComponentFactory::class.java.classLoader) {
   override fun loadClass(name:String,resolve:Boolean):Class<*> = synchronized(getClassLoadingLock(name)) {if(name.startsWith(prefix)){val type=findLoadedClass(name)?:findClass(name);if(resolve)resolveClass(type);type}else super.loadClass(name,resolve)}
  }
  loader.use{val factories=ServiceLoader.load(ComponentFactory::class.java,it).toList();assertEquals(1,factories.size);val factory=factories.single();assertSame(it,factory.javaClass.classLoader);assertEquals("soc-dram",factory.descriptor.id);val c=Context(document());val h=create(c,factory);val p=control(c,1);assertSame(it,p.javaClass.classLoader);assertNotSame(it,MmioDevice::class.java.classLoader);program(c,1);p.write(0x7c18,4,0x40000000);c.tick=20;p.beforeInstruction(20);assertEquals(1L,p.read(0x7c18,4));val phy=c.ports.find("soc-dram.phy-1",MmioDevice::class.java)!!;assertEquals(0x30924L,phy.read(0x4670,4));h.close();assertFails{phy.read(0x4670,4)};assertFails{(p as ScheduledMmioDevice).nextDeadline()}}
 }
 @Test fun selectedBanksPublishOnlyTheirTwoPortsWithExplicitClockIndices() {
  val c=Context(document(listOf(3,0,1,2)));val h=create(c);assertEquals(8,h.additionalMmioPorts().size);assertEquals((0..3).map{"soc-dram.controller-$it"},h.additionalMmioPorts().filter{it.contains("controller")});for(i in 0..3){val p=control(c,i);assertEquals(9,p.clockOrder);assertEquals(i,p.clockIndex);assertTrue(p is ScheduledMmioDevice)};assertEquals(1,c.loads);h.close()
 }
 @Test fun exactPairHashChecksAndMalformedRootTypesRefuseBeforeAnyPublication() {
  val cases=mutableListOf<Map<String,Any?>>()
  for(key in document().keys)cases.add(document().also{it.remove(key)})
  cases.add(document().also{it["extra"]=0});cases.add(document().also{it["schema"]="1"});cases.add(document().also{it["schema"]=1.0});cases.add(document().also{it["firmwareSha256"]="other"});cases.add(document().also{it["profile"]="other"});cases.add(document().also{it["banks"]=emptyMap<String,Any>()});cases.add(document().also{it["banks"]=mapOf("4" to emptyMap<String,Any>())})
  cases.add(document().also{it["programmingWords"]=f.programming.dropLast(1).map{(o,v)->mapOf("offset" to o,"value" to v)}})
  cases.add(document().also{it["programmingWords"]=f.programming.mapIndexed{i,(o,v)->mapOf("offset" to o,"value" to if(i==0)v xor 1L else v)}})
  cases.add(document().also{it["configurationWords"]=f.config.mapIndexed{i,(o,v)->mapOf("offset" to if(i==0)0 else o,"value" to v)}})
  for(data in cases){val c=Context(data);assertFails{create(c)};assertTrue(c.ports.ports.isEmpty())}
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun bankShapesLossyIntegersExtraBitsAndMissingPhasesRefuseBeforePublication() {
  for(case in 0..7){val data=document();val bank=((data["banks"]as MutableMap<String,Any?>)["1"]as MutableMap<String,Any?>);when(case){0->bank["responseTicks"]=listOf(0,1,1,1,1);1->bank["responseTicks"]=listOf(1.0,1,1,1,1);2->bank["initialController7044"]="0";3->bank["extra"]=1;4->bank["responses"]=mapOf("0" to f.reply(0).toList());5->(bank["responses"]as MutableMap<String,Any?>)["0"]=listOf(1,0x30000,0x30003,0x30004);6->(bank["responses"]as MutableMap<String,Any?>)["3"]=emptyList<Any>();7->bank.remove("initialController7364")};val c=Context(data);assertFails{create(c)};assertTrue(c.ports.ports.isEmpty())}
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun inputSnapshotIsFrozenAndInvalidAccessesCannotOpenHoles() {
  val data=document();val c=Context(data);val h=create(c);val bank=(data["banks"]as MutableMap<String,Any?>)["1"]as MutableMap<String,Any?>;(bank["responses"]as MutableMap<String,Any?>)["0"]=null;c.document=emptyMap();program(c,1);val p=control(c,1);p.write(0x7c18,4,0x40000000);c.tick=20;p.beforeInstruction(20);assertEquals(1L,p.read(0x7c18,4));assertEquals(1,c.loads);val phy=c.ports.find("soc-dram.phy-1",MmioDevice::class.java)!!;for(offset in listOf(-4L,0L,0x2002L,0x10000L,0x100002000L))assertFails{phy.read(offset,4)};assertFails{phy.read(0x4670,1)};assertEquals(0,c.eventsPublished);h.close()
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun nullSampleKeepsPendingAndRealRouterOrdersBanksBeforeDmaIndependentOfWindowOrder() {
  val data=document(listOf(3,1));val bank=(data["banks"]as MutableMap<String,Any?>)["1"]as MutableMap<String,Any?>;(bank["responses"]as MutableMap<String,Any?>)["0"]=null;val c=Context(data);val h=create(c);for(i in listOf(1,3)){program(c,i);control(c,i).write(0x7c18,4,0x40000000)}
  val before=h.state();val ctrl1=control(c,1);val ctrl3=control(c,3);var dma=false;val late=object:MmioDevice{override val clockOrder=10;override fun beforeInstruction(tick:Long){dma=true};override fun read(offset:Long,size:Int)=0L;override fun write(offset:Long,size:Int,value:Long){}}
  c.tick=20;for(reversed in listOf(false,true)){val windows=listOf(MmioWindow("bank3",0x10000,0x10000,ctrl3),MmioWindow("dma",0x40000,4,late),MmioWindow("bank1",0x20000,0x10000,ctrl1));val router=MmioRouter(if(reversed)windows.reversed()else windows);assertFails{router.beforeInstruction(20)};assertFalse(dma);assertEquals(before,h.state())}
  val earlier=object:MmioDevice{override val clockOrder=8;override fun beforeInstruction(tick:Long){error("temperature-first")};override fun read(offset:Long,size:Int)=0L;override fun write(offset:Long,size:Int,value:Long){}}
  assertTrue(assertFails{MmioRouter(listOf(MmioWindow("bank1",0x20000,0x10000,ctrl1),MmioWindow("early",0x50000,4,earlier))).beforeInstruction(20)}.message.orEmpty().contains("temperature-first"));assertEquals(before,h.state());h.close()
 }
 @Test fun disabledProfileCannotSelectInputAndDataPathEscapeRefusesBeforePublication() {
  val c=Context(document());assertFails{DramFactory().create(c,mapOf("profile" to "disabled","inputFile" to "input.yaml"))};assertFails{DramFactory().create(c,mapOf("profile" to DramFactory.PROFILE,"inputFile" to "../input.yaml"))};assertTrue(c.ports.ports.isEmpty())
 }
}
