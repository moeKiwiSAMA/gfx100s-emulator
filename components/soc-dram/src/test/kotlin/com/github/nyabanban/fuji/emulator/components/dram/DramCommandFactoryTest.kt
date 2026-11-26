package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.*

class DramCommandFactoryTest {
 private val f=DramFixture()
 private val cf=CommandFixture()
 private class Registry:Ports {
  val values=linkedMapOf<String,Any>()
  override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){require(type.isInstance(port)&&name !in values);values[name]=port}
  override fun<T:Any>find(name:String,type:Class<T>):T?=values[name]?.let{type.cast(it)}
  override fun removeOwner(owner:String){values.clear()}
 }
 private class Context(var document:Map<String,Any?>,val files:MutableMap<String,Map<String,Any?>>):ComponentContext {
  override val directory=Path.of(DramTestPaths.value("studyOutput"),"command-data").toAbsolutePath().normalize()
  override val ports=Registry()
  var tick=0L;var loads=0;var eventsPublished=0
  override val clock=EmulationClock{tick}
  override val events=object:EventChannel {override fun publish(event:ComponentEvent){eventsPublished++};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
  override fun dataPath(relative:String)=directory.resolve(relative).normalize().also{require(relative.isNotBlank()&&it.startsWith(directory))}
  override fun readData(relative:String):Map<String,Any?> {dataPath(relative);loads++;return if(relative=="input.yaml")document else files[relative]?:error("missing declared test table file")}
 }
 private fun tableFiles():MutableMap<String,Map<String,Any?>> = (0..3).associate{bank->"command-"+bank+".yaml" to linkedMapOf<String,Any?>("schema" to 1,"bank" to bank,"profile" to DramFactory.COMMAND_PROFILE,"firmwareSha256" to DramFactory.FIRMWARE,"modes" to (0..4).associate{mode->mode.toString() to cf.tables(bank)[mode].map{(o,v)->mapOf("offset" to o,"value" to v)}}.toMutableMap())}.toMutableMap()
 private fun context(document:Map<String,Any?>)=Context(document,tableFiles())
 private fun doc(indices:List<Int> = listOf(0)):MutableMap<String,Any?> = linkedMapOf("schema" to 1,"profile" to DramFactory.COMMAND_PROFILE,"firmwareSha256" to DramFactory.FIRMWARE,"programmingWords" to f.programming.map{(o,v)->mapOf("offset" to o,"value" to v)},"configurationWords" to f.config.map{(o,v)->mapOf("offset" to o,"value" to v)},"commandTableFiles" to (0..3).associate{it.toString() to "command-"+it+".yaml"},"banks" to indices.associate{it.toString() to linkedMapOf<String,Any?>("responseTicks" to listOf(20,20,20,20,20),"initialController7044" to 0,"initialController7364" to 0,"responses" to (0..4).associate{stage->stage.toString() to f.reply(stage).toList()}.toMutableMap(),"commandPlans" to listOf(linkedMapOf<String,Any?>("mode" to 4,"responseTicks" to 80,"responseWord" to 0,"counterWord" to null)))}.toMutableMap())
 @Suppress("UNCHECKED_CAST") private fun bank(d:Map<String,Any?>,i:Int=0)=((d.getValue("banks")as Map<String,Any?>).getValue(i.toString())as MutableMap<String,Any?>)
 @Suppress("UNCHECKED_CAST") private fun plan(d:Map<String,Any?>,i:Int=0)=((bank(d,i).getValue("commandPlans")as List<*>).single()as MutableMap<String,Any?>)
 private fun create(c:Context,factory:ComponentFactory=DramFactory())=factory.create(c,mapOf("profile" to DramFactory.COMMAND_PROFILE,"inputFile" to "input.yaml")).also{it.start()}
 private fun control(c:Context,i:Int=0)=c.ports.find("soc-dram.controller-$i",MmioDevice::class.java)!!
 private fun program(c:Context,i:Int=0){for((o,v)in cf.tables(i)[4])control(c,i).write(o.toLong(),4,v)}
 @Suppress("UNCHECKED_CAST") private fun state(h:HardwareComponent,i:Int=0)=((h.state().getValue("banks")as Map<String,Any?>).getValue(i.toString())as Map<String,Any?>)
 @Test fun v2CommandRunsWithoutPretendingBankZeroWasTrained() {
  val c=context(doc());val h=create(c);assertEquals(0,state(h)["programmedWordCount"]);program(c);val p=control(c);assertEquals(80L,(p as ScheduledMmioDevice).nextDeadline());c.tick=80;p.beforeInstruction(80);assertEquals(0L,p.read(0x5384,4));assertEquals(0,state(h)["programmedWordCount"]);assertEquals(0L,state(h)["responseDeliveryCount"]);assertEquals(0L,state(h)["completedWriteCount"]);val command=state(h).getValue("commands")as Map<*,*>;assertEquals(71L,command["completedWriteCount"]);assertEquals(1L,command["guestZeroObservationCount"]);assertEquals(0,c.eventsPublished);h.close();assertFails{p.read(0x5384,4)}
 }
 @Test fun missingCommandDeadlineFreezesWholePairAndStopsLaterBankAndDmaInEitherWindowOrder() {
  for(reverse in listOf(false,true)){val d=doc(listOf(3,0));plan(d)["responseWord"]=null;val c=context(d);val h=create(c);program(c,0);program(c,3);c.tick=79;control(c,0).beforeInstruction(79);control(c,3).beforeInstruction(79);val before=h.state();var dma=false;val late=object:MmioDevice {override val clockOrder=10;override fun beforeInstruction(tick:Long){dma=true};override fun read(offset:Long,size:Int)=0L;override fun write(offset:Long,size:Int,value:Long){}}
   val windows=listOf(MmioWindow("bank3",0x30000,0x10000,control(c,3)),MmioWindow("dma",0x50000,4,late),MmioWindow("bank0",0x10000,0x10000,control(c,0)));c.tick=80;assertFails{MmioRouter(if(reverse)windows.reversed()else windows).beforeInstruction(80)};assertFalse(dma);assertEquals(before,h.state());assertEquals(80L,(control(c)as ScheduledMmioDevice).nextDeadline());h.close()}
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun missingInitializationInputDoesNotAdvanceTheIdleCommandClock() {
  val d=doc(listOf(1));(bank(d,1)["responses"]as MutableMap<String,Any?>)["0"]=null;val c=context(d);val h=create(c);val phy=c.ports.find("soc-dram.phy-1",MmioDevice::class.java)!!;f.programming.forEach{(o,v)->phy.write(o.toLong(),4,v)};control(c,1).write(0x7c18,4,0x40000000);c.tick=19;control(c,1).beforeInstruction(19);val before=h.state();assertFails{control(c,1).beforeInstruction(20)};assertEquals(before,h.state());h.close()
 }
 @Test fun initializationAndCommandRequestsCannotOverlap() {
  for(commandFirst in listOf(false,true)){val c=context(doc());val h=create(c);val phy=c.ports.find("soc-dram.phy-0",MmioDevice::class.java)!!;if(commandFirst){val first=cf.tables(0)[4].first();control(c).write(first.first.toLong(),4,first.second);val before=h.state();assertFails{phy.write(0x2000,4,0xb00)};assertEquals(before,h.state())}else{f.programming.forEach{(o,v)->phy.write(o.toLong(),4,v)};control(c).write(0x7c18,4,0x40000000);val before=h.state();assertFails{control(c).write(0x5000,4,0x01004100)};assertEquals(before,h.state())};h.close()}
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun wrongContractMalformedPlansAndLossyTypesRefuseBeforeAnyPorts() {
  for(case in 0..10){val d=doc();when(case){0->d.remove("commandTableFiles");1->d["commandTableFiles"]=emptyMap<String,Any?>();2->d["commandTableFiles"]=mapOf("0" to "command-1.yaml","1" to "command-1.yaml","2" to "command-2.yaml","3" to "command-3.yaml");3->plan(d).remove("counterWord");4->plan(d)["mode"]=4.0;5->plan(d)["responseTicks"]=0;6->plan(d)["responseWord"]="0";7->plan(d)["counterWord"]=-1;8->plan(d)["extra"]=1;9->{d["banks"]=(doc(listOf(1))["banks"]);plan(d,1)["counterWord"]=0};10->bank(d)["commandPlans"]=List(1025){plan(doc())}};val c=context(d);assertFails{create(c)};assertTrue(c.ports.values.isEmpty())}
 }
 @Test fun loadedDataAndPlansAreFrozenAndExhaustionDoesNotInventTheNextReply() {
  val d=doc();val c=context(d);val h=create(c);plan(d)["responseWord"]=0x80000000L;d["commandTableFiles"]=emptyMap<String,Any?>();c.document=emptyMap();program(c);c.tick=80;control(c).beforeInstruction(80);assertEquals(0L,control(c).read(0x5384,4));assertEquals(5,c.loads);val before=h.state();assertFails{control(c).write(0x5000,4,0x01004100)};assertEquals(before,h.state());h.close()
 }
 @Test fun compositeClockPreflightRejectsBackwardsTickBeforeEitherSubdeviceChanges() {
  val c=context(doc());val h=create(c);program(c);c.tick=79;assertEquals(1L,control(c).read(0x5384,4));val before=h.state();assertFails{control(c).beforeInstruction(78)};assertEquals(before,h.state());h.close()
 }
 @Test fun actualIsolatedJarLoadsV2ThroughParentApiAndClosesRetainedCommandPort() {
  val jar=Path.of(DramTestPaths.value("candidateJar"));val prefix="com.github.nyabanban.fuji.emulator.components.dram."
  val loader=object:URLClassLoader(arrayOf(jar.toUri().toURL()),ComponentFactory::class.java.classLoader){override fun loadClass(name:String,resolve:Boolean):Class<*> = synchronized(getClassLoadingLock(name)){if(name.startsWith(prefix)){val clazz=findLoadedClass(name)?:findClass(name);if(resolve)resolveClass(clazz);clazz}else super.loadClass(name,resolve)}}
  loader.use{val factory=ServiceLoader.load(ComponentFactory::class.java,it).single();assertSame(it,factory.javaClass.classLoader);val c=context(doc());val h=create(c,factory);program(c);val p=control(c);assertSame(it,p.javaClass.classLoader);assertNotSame(it,ScheduledMmioDevice::class.java.classLoader);c.tick=80;p.beforeInstruction(80);assertEquals(0L,p.read(0x5384,4));h.close();assertFails{(p as ScheduledMmioDevice).nextDeadline()};assertFails{p.beforeInstruction(81)}}
 }
 @Suppress("UNCHECKED_CAST")
 @Test fun declaredTableFileIdentityShapeHashAndPathMustMatchBeforePublication() {
  for(case in 0..4){val c=context(doc());val bankFile=c.files.getValue("command-0.yaml").toMutableMap();when(case){0->bankFile["bank"]=1;1->bankFile["profile"]="other";2->{val modes=bankFile["modes"]as MutableMap<String,Any?>;modes["4"]=cf.tables(0)[4].mapIndexed{i,(o,v)->mapOf("offset" to o,"value" to if(i==0)v xor 1L else v)}};3->bankFile.remove("schema");4->c.files.remove("command-0.yaml")};if(case!=4)c.files["command-0.yaml"]=bankFile;assertFails{create(c)};assertTrue(c.ports.values.isEmpty())}
  val d=doc();d["commandTableFiles"]=mapOf("0" to "../command-0.yaml","1" to "command-1.yaml","2" to "command-2.yaml","3" to "command-3.yaml");val c=context(d);assertFails{create(c)};assertTrue(c.ports.values.isEmpty())
 }
 @Test fun actualYamlRoundtripFitsBoundedFilesLoadsOnceAndPreservesMissingNullResponse() {
  val d=doc();plan(d)["responseWord"]=null;val c=context(d);Files.createDirectories(c.directory)
  Formats.atomicYaml(c.directory.resolve("input.yaml"),d);c.files.forEach{(name,data)->Formats.atomicYaml(c.directory.resolve(name),data)}
  assertTrue((listOf("input.yaml")+c.files.keys).all{Files.size(c.directory.resolve(it))<=65536})
  val reads=mutableListOf<String>();val actual=object:ComponentContext by c {override fun readData(relative:String):Map<String,Any?>{reads.add(relative);return Formats.readData(dataPath(relative))}}
  val h=DramFactory().create(actual,mapOf("profile" to DramFactory.COMMAND_PROFILE,"inputFile" to "input.yaml"));h.start();assertEquals(listOf("input.yaml","command-0.yaml","command-1.yaml","command-2.yaml","command-3.yaml"),reads);program(c);c.tick=79;control(c).beforeInstruction(79);val before=h.state();assertFails{control(c).beforeInstruction(80)};assertEquals(before,h.state());assertEquals(5,reads.size);h.close()
  Files.writeString(c.directory.resolve("oversized.yaml"),(" ".repeat(65536)+"\n"));assertTrue(assertFails{Formats.readData(c.directory.resolve("oversized.yaml"))}.message.orEmpty().contains("64 KiB"))
 }

}
