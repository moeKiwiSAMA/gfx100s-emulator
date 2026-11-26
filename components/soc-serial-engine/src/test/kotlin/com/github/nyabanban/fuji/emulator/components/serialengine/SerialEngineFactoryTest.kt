package com.github.nyabanban.fuji.emulator.components.serialengine

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.zip.ZipFile
import kotlin.test.*

class SerialEngineFactoryTest {
 @TempDir lateinit var temporary:Path
 private val project=Path.of(System.getProperty("componentProjectRoot"))
 private class Registry:Ports {
  val entries=linkedMapOf<String,Pair<String,Any>>()
  override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){require(type.isInstance(port)&&name !in entries);entries[name]=owner to port}
  override fun<T:Any>find(name:String,type:Class<T>):T?=entries[name]?.second?.let{type.cast(it)}
  override fun removeOwner(owner:String){entries.entries.removeIf{it.value.first==owner}}
 }
 private class Context(override val directory:Path,var data:Map<String,Any?>):ComponentContext {
  override val ports=Registry();var tick=0L;var loads=0;var published=0;var disk=false
  override val clock=EmulationClock{tick}
  override val events=object:EventChannel{override fun publish(event:ComponentEvent){published++};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun dataPath(relative:String)=containedPath(directory,relative)
  override fun readData(relative:String):Map<String,Any?> {loads++;val path=dataPath(relative);return if(disk)Formats.readData(path)else data}
 }
 private fun document(words:Map<String,Any?> = mapOf("0" to 0,"1" to 0xa55a00a5L)):Map<String,Any?> = mapOf("schema" to 1,"profile" to SerialEngineFactory.PROFILE,"firmwareSha256" to SerialEngineFactory.FIRMWARE,"initialWords" to words)
 private fun context(data:Map<String,Any?> = document()):Context {val p=Files.createTempDirectory(temporary,"case-");return Context(p,data)}
 private fun settings()=mapOf<String,Any>("profile" to SerialEngineFactory.PROFILE,"inputFile" to "input.yaml")
 private fun device(c:Context,index:Int)=checkNotNull(c.ports.find(SerialEngineFactory.port(index),MmioDevice::class.java))
 private fun create(c:Context,f:ComponentFactory=SerialEngineFactory())=f.create(c,settings()).also{it.start()}
 private fun isolated(action:(ComponentFactory,ClassLoader)->Unit) {
  val jar=project.resolve("components/soc-serial-engine/build/libs/soc-serial-engine-0.3.0.jar");val prefix="com.github.nyabanban.fuji.emulator.components.serialengine."
  val loader=object:URLClassLoader(arrayOf(jar.toUri().toURL()),ComponentFactory::class.java.classLoader){override fun loadClass(name:String,resolve:Boolean):Class<*> = synchronized(getClassLoadingLock(name)){if(name.startsWith(prefix)){val type=findLoadedClass(name)?:findClass(name);if(resolve)resolveClass(type);type}else super.loadClass(name,resolve)}}
  loader.use{val factories=ServiceLoader.load(ComponentFactory::class.java,it).toList();assertEquals(1,factories.size);action(factories.single(),it)}
 }
 @Test fun disabledDefaultHasNoPortsDataLoadsEventsOrIrqsAndSettingsAreRestartOnly() {
  val f=SerialEngineFactory();val c=context(emptyMap());val defaults=f.descriptor.settings.associate{it.key to it.default};val h=f.create(c,defaults);h.start();assertEquals(false,h.state()["enabled"]);assertTrue(h.additionalMmioPorts().isEmpty());assertTrue(h.interruptRoutes().isEmpty());assertTrue(c.ports.entries.isEmpty());assertEquals(0,c.loads);assertEquals(0,c.published);assertTrue(f.descriptor.settings.all{!it.live});assertFails{h.applySettings(settings())};h.applySettings(defaults);h.close();assertFails{h.start()}
 }
 @Test fun allInvalidRootSettingsAndInputShapesRefuseBeforePublishingPorts() {
  val invalid=mutableListOf<Map<String,Any?>>()
  for(key in document().keys)invalid.add(document()-key)
  invalid.addAll(listOf(document()+mapOf("extra" to 1),document()+mapOf("schema" to 1.0),document()+mapOf("schema" to "1"),document()+mapOf("firmwareSha256" to "other"),document()+mapOf("profile" to "other"),document(emptyMap()),document(mapOf("00" to 0)),document(mapOf("2" to 0)),document(mapOf("0" to -1)),document(mapOf("0" to 0x100000000L)),document(mapOf("0" to 1.0)),document(mapOf("0" to "0")),document(mapOf("0" to 0,"1" to false))))
  for(data in invalid){val c=context(data);assertFails{create(c)};assertTrue(c.ports.entries.isEmpty());assertEquals(0,c.published)}
  for(config in listOf(settings()+mapOf("extra" to 0),settings()+mapOf("profile" to "disabled"),settings()+mapOf("inputFile" to ""),settings()+mapOf("inputFile" to "../escape"),settings()+mapOf("profile" to "other"))){val c=context();assertFails{SerialEngineFactory().create(c,config)};assertTrue(c.ports.entries.isEmpty())}
 }
 @Test fun nullIsSelectedMissingInputNotZeroAndDoesNotMutateCountersClockOrPeer() {
  val c=context(document(mapOf("0" to null)));val h=create(c);assertEquals(listOf(SerialEngineFactory.port(0)),h.additionalMmioPorts());assertNull(c.ports.find(SerialEngineFactory.port(1),MmioDevice::class.java));val p=device(c,0);val before=h.state();c.tick=10;assertFails{p.read(0,4)};assertFails{p.write(0,4,64)};assertEquals(before,h.state());assertNull((h.state()["words"]as Map<*,*>)["0"]);assertEquals(0,c.published);h.close()
 }
 @Test fun exactWidthOffsetU32HighBitsAndBackwardsClockRejectAtomically() {
  val c=context(document(mapOf("0" to 0x55aa0000L)));val h=create(c);val p=device(c,0);c.tick=10;assertEquals(0x55aa0000L,p.read(0,4));p.write(0,4,0x55aa0040L);val before=h.state()
  for(offset in listOf(-4L,1L,4L,0x100000000L)){assertFails{p.read(offset,4)};assertFails{p.write(offset,4,0x55aa0040L)};assertEquals(before,h.state())}
  for(size in listOf(1,2,8)){assertFails{p.read(0,size)};assertFails{p.write(0,size,0x55aa0040L)};assertEquals(before,h.state())}
  for(value in listOf(-1L,0x100000000L,0x55ab0040L)){assertFails{p.write(0,4,value)};assertEquals(before,h.state())}
  c.tick=9;assertFails{p.read(0,4)};assertFails{p.write(0,4,0x55aa0041L)};assertEquals(before,h.state());c.tick=11;p.write(0,4,0x55aa00ffL);assertEquals(0x55aa00ffL,p.read(0,4));assertEquals(90,p.clockOrder);assertEquals(0,p.clockIndex);assertFalse(p is ScheduledMmioDevice);assertTrue(h.interruptRoutes().isEmpty());h.close();assertFails{p.read(0,4)};assertFails{p.write(0,4,0x55aa00ffL)}
 }
 @Test fun groupStateIsIndependentFrozenAndSupportsSharedLostUpdateAsWholeWordRetention() {
  val input=linkedMapOf<String,Any?>("1" to 0x80000000L,"0" to 0L);val c=context(document(input));val h=create(c);input["0"]=255;c.data=emptyMap();val a=device(c,0);val b=device(c,1);assertEquals(listOf(SerialEngineFactory.port(0),SerialEngineFactory.port(1)),h.additionalMmioPorts());assertEquals(0L,a.read(0,4));assertEquals(0x80000000L,b.read(0,4));a.write(0,4,1);a.write(0,4,2);assertEquals(2L,a.read(0,4));assertEquals(0x80000000L,b.read(0,4));assertEquals(1,c.loads);assertEquals(0,c.published);h.close()
 }
 @Test fun boundedPrimitiveDiagnosticsPreserveCurrentExecutionContextAndTotalCounts() {
  val c=context(document(mapOf("0" to 0)));var core=0;c.ports.register("test","cpu.execution-location",ExecutionLocationSource::class.java,ExecutionLocationSource{ExecutionLocation(core,0x1000L+core*4)})
  val h=create(c);val p=device(c,0);for(i in 0..99){c.tick=i.toLong();core=i%4;if(i%2==0)p.write(0,4,i.toLong())else p.read(0,4)};val state=h.state();assertEquals(100L,state["accessCount"]);assertEquals(50L,state["readCount"]);assertEquals(50L,state["writeCount"]);assertEquals(true,state["accessLogTruncated"]);val rows=state["accesses"]as List<*>;assertEquals(64,rows.size);rows.forEachIndexed{i,v->val row=v as Map<*,*>;val n=i+36;assertEquals(n.toLong(),row["tick"]);assertEquals(n%4,row["core"]);assertEquals(0x1000L+(n%4)*4,row["pcVirtual"])};assertEquals(0,c.published);h.close()
 }
 @Test fun realBoundedFormatsFreezeYamlAndRejectDuplicatesOversizeAndEscapes() {
  val c=context();c.disk=true;Formats.atomicYaml(c.directory.resolve("input.yaml"),document(mapOf("0" to 0x12345678)));val h=create(c);Formats.atomicYaml(c.directory.resolve("input.yaml"),document(mapOf("0" to 0)));assertEquals(0x12345678L,device(c,0).read(0,4));assertEquals(1,c.loads);h.close()
  val duplicate=context();duplicate.disk=true;Files.writeString(duplicate.directory.resolve("input.yaml"),"schema: 1\nschema: 1\n");assertFails{create(duplicate)};assertTrue(duplicate.ports.entries.isEmpty())
  val large=context();large.disk=true;Files.writeString(large.directory.resolve("input.yaml"),(" ".repeat(65536)+"\n"));assertFails{create(large)};assertTrue(large.ports.entries.isEmpty())
 }
 @Test fun actualIsolatedJarHasParentApiJava25ClassesAndOnlyExplicitWordPorts() {
  val jar=project.resolve("components/soc-serial-engine/build/libs/soc-serial-engine-0.3.0.jar");ZipFile(jar.toFile()).use{z->val entries=z.entries().asSequence().map{it.name}.toList();assertFalse(entries.any{it.startsWith("com/github/nyabanban/fuji/emulator/api/")});val raw=z.getInputStream(z.getEntry("com/github/nyabanban/fuji/emulator/components/serialengine/SerialEngineFactory.class")).readAllBytes();assertEquals(69,((raw[6].toInt()and 255)shl 8)or(raw[7].toInt()and 255))}
  isolated{f,loader->assertSame(loader,f.javaClass.classLoader);val c=context(document(mapOf("0" to 0)));val h=create(c,f);val p=device(c,0);assertSame(loader,p.javaClass.classLoader);assertNotSame(loader,MmioDevice::class.java.classLoader);p.write(0,4,64);assertEquals(64L,p.read(0,4));h.close();assertFails{p.read(0,4)}}
 }
}
