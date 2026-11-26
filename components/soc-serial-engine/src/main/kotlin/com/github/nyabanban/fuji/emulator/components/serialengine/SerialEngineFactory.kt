package com.github.nyabanban.fuji.emulator.components.serialengine

import com.github.nyabanban.fuji.emulator.api.*
import java.util.Base64

internal object SerialSnapshotCodec {
 const val BASE64_CHUNK_BYTES = 24576

 fun parseIntegralLong(value: Any?, fieldName: String): Long {
  require(value is Byte || value is Short || value is Int || value is Long) { "Integral value required for $fieldName" }
  return (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $fieldName")
 }

 fun parseIntegralInt(value: Any?, fieldName: String): Int {
  val numberValue = parseIntegralLong(value, fieldName)
  require(numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out of Int range for $fieldName" }
  return numberValue.toInt()
 }

 fun parseUnsignedWord(value: Any?, fieldName: String): Long =
  parseIntegralLong(value, fieldName).also { parsed -> require(parsed in 0L..0xffffffffL) { "Unsigned 32-bit value required for $fieldName" } }

 fun encodeBytesChunked(bytes: ByteArray, length: Int): List<String> {
  require(length in 0..bytes.size) { "Invalid byte slice length $length" }
  if (length == 0) return emptyList()
  val chunks = ArrayList<String>((length + BASE64_CHUNK_BYTES - 1) / BASE64_CHUNK_BYTES)
  var offset = 0
  val encoder = Base64.getEncoder()
  while (offset < length) {
   val chunkEnd = minOf(length, offset + BASE64_CHUNK_BYTES)
   chunks.add(encoder.encodeToString(bytes.copyOfRange(offset, chunkEnd)))
   offset = chunkEnd
  }
  return chunks
 }

 fun decodeBytesChunked(raw: Any?, expectedLength: Int, fieldName: String): ByteArray {
  require(expectedLength >= 0) { "Negative expected byte length for $fieldName" }
  val decoder = Base64.getDecoder()
  return when (raw) {
   is String -> {
    val decoded = decoder.decode(raw)
    require(decoded.size == expectedLength) { "Decoded byte length mismatch for $fieldName" }
    decoded
   }
   is List<*> -> {
    val decodedChunks = ArrayList<ByteArray>(raw.size)
    var totalBytes = 0
    for (element in raw) {
     val chunkText = element as? String ?: throw IllegalArgumentException("Base64 chunk must be String for $fieldName")
     val chunkBytes = decoder.decode(chunkText)
     require(totalBytes <= expectedLength - chunkBytes.size) { "Decoded byte length exceeds expected size for $fieldName" }
     decodedChunks.add(chunkBytes)
     totalBytes += chunkBytes.size
    }
    require(totalBytes == expectedLength) { "Decoded byte length mismatch for $fieldName" }
    val combined = ByteArray(expectedLength)
    var targetOffset = 0
    for (chunkBytes in decodedChunks) {
     chunkBytes.copyInto(combined, targetOffset)
     targetOffset += chunkBytes.size
    }
    combined
   }
   else -> throw IllegalArgumentException("Expected Base64 string or chunk list for $fieldName")
  }
 }
}

/** Shared words, finite status samples and selected DMA capture plans. Physical reset/ready/IRQ/display laws remain unqualified. */
class SerialEngineFactory:ComponentFactory {
 companion object {
  const val TRANSMIT_PROFILE="gfx100s-serial-transmit-functional-experimental-v3"
  fun controlPort(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.control-"+channel}
  fun dataPort(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.data-"+channel}
  fun txPort(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.tx-"+channel}
  fun capturePort(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.capture-"+channel}
  const val STATUS_PROFILE="gfx100s-serial-status-functional-experimental-v2"
  fun statusPort(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.status-"+channel}
  const val PROFILE="gfx100s-serial-shared-functional-experimental-v1"
  const val FIRMWARE="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
  const val MUTABLE_MASK=255L
  const val IRQ_GROUP_0=90
  const val IRQ_GROUP_1=91
  fun port(group:Int):String {require(group in 0..1);return "soc-serial-engine.shared-"+group}
 }
 private data class ParsedSharedAccess(val group:Int,val write:Boolean,val value:Long,val tick:Long,val core:Int,val pcVirtual:Long)
 override val descriptor=ComponentDescriptor("soc-serial-engine","0.0.1",settings=listOf(Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled",PROFILE,STATUS_PROFILE,TRANSMIT_PROFILE),live=false),Setting("inputFile",ValueKind.TEXT,"",live=false),Setting("captureEvents",ValueKind.BOOLEAN,false,live=false,description="Opt-in passive completion notifications; never a serial/display hardware law"),Setting("repeatLastPlan",ValueKind.BOOLEAN,false,live=false,description="Opt-in repeatable last status/transmit plan beyond finite horizon"),Setting("completionIrqs",ValueKind.BOOLEAN,false,live=false,description="Opt-in GIC IRQ 90 (ch 0..4) / IRQ 91 (ch 5..7) and W1C completion status bit 0 on DMA transmit finish")))
 override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=Engine(context,config)
 private fun normalizeScalarConfig(config:Map<String,Any>):Map<String,Any> {
  var out=config
  if(out["captureEvents"]!=true) out=out-"captureEvents"
  if(out["repeatLastPlan"]!=true) out=out-"repeatLastPlan"
  if(out["completionIrqs"]!=true) out=out-"completionIrqs"
  return out
 }
 private inner class Engine(context:ComponentContext,config:Map<String,Any>):ScalarComponent("soc-serial-engine",context,normalizeScalarConfig(config)),StatefulComponent {
  private val frozenConfig=config.toMap()
  private val captureEvents=(config["captureEvents"]?:false).also {require(it is Boolean)} as Boolean
  private val repeatLastPlan=(config["repeatLastPlan"]?:false).also {require(it is Boolean)} as Boolean
  private val completionIrqs=(config["completionIrqs"]?:false).also {require(it is Boolean)} as Boolean
  private var closed=false
  private val enabled=config["profile"]in listOf(PROFILE,STATUS_PROFILE,TRANSMIT_PROFILE)
  private val withStatus=config["profile"]in listOf(STATUS_PROFILE,TRANSMIT_PROFILE)
  private val withTransmit=config["profile"]==TRANSMIT_PROFILE
  private val transmit=sortedMapOf<Int,SerialTransmitPlan>()
  private val status=sortedMapOf<Int,SerialStatusPlan>()
  private val configuredStatusChannels=linkedSetOf<Int>()
  private val selected=BooleanArray(2)
  private val supplied=BooleanArray(2)
  private val initial=LongArray(2)
  private val words=LongArray(2)
  private var clock=0L
  private var reads=0L
  private var writes=0L
  private var count=0L
  private val capacity=64
  private val groups=IntArray(capacity)
  private val directions=BooleanArray(capacity)
  private val valuesLogged=LongArray(capacity)
  private val ticks=LongArray(capacity)
  private val cores=IntArray(capacity){-1}
  private val pcs=LongArray(capacity){-1}
  private fun integer(value:Any?):Long {require(value is Byte||value is Short||value is Int||value is Long){"serial shared exact unsigned integer required"};return(value as Number).toLong().also{require(it in 0..0xffffffffL)}}
  init {
   require(config.keys.all { it in setOf("profile","inputFile","captureEvents","repeatLastPlan","completionIrqs") } && "profile" in config && "inputFile" in config && config["profile"]in listOf("disabled",PROFILE,STATUS_PROFILE,TRANSMIT_PROFILE)&&config["inputFile"]is String){"serial shared settings shape differs"}
   require(!captureEvents || withTransmit) {"Capture notifications require the explicit transmit profile"}
   require(!repeatLastPlan || withStatus) {"Repeatable plans require an active serial status/transmit profile"}
   require(!completionIrqs || withTransmit) {"Completion IRQs require the explicit transmit profile"}
   val file=config["inputFile"]as String;require(enabled==file.isNotBlank()){"serial shared explicit profile/input file required"}
   if(enabled) {
    val data=context.readData(file)
    val resumeKey="resumeChannel1StatusSample"
    val listKey="resumeChannel1StatusSamples"
    val resumeKeys=data.keys.intersect(setOf(resumeKey,listKey))
    require(resumeKeys.isEmpty() || (withTransmit && resumeKeys.size==1)){"Select one channel 1 transmit status continuation"}
    val resumeSamples=when(resumeKeys.singleOrNull()){
     resumeKey -> listOf(integer(data[resumeKey]).also { require(it==0L) })
     listKey -> {
      val samples=data[listKey]
      require(samples is List<*> && samples.size in 1..63){"Channel 1 requires 1..63 explicit continuation words"}
      samples.map(::integer)
     }
     else -> emptyList()
    }
    require(data.keys==((setOf("schema","profile","firmwareSha256","initialWords")+if(withStatus)setOf("statusPlans")else emptySet())+(if(withTransmit)setOf("transmitPlans")else emptySet())+resumeKeys)&&integer(data["schema"])==1L&&data["profile"]==config["profile"]&&data["firmwareSha256"]==FIRMWARE){"serial shared input identity differs"}
    val raw=data["initialWords"];require(raw is Map<*,*>&&raw.isNotEmpty()&&raw.size<=2&&raw.keys.all{it is String&&it in setOf("0","1")}){"serial shared groups must be explicit0/1"}
    for((key,value)in raw){val index=key.toString().toInt();selected[index]=true;if(value!=null){val word=integer(value);supplied[index]=true;initial[index]=word;words[index]=word}}
    if(withStatus) {
     val plans=data["statusPlans"];require(plans is Map<*,*>&&plans.isNotEmpty()&&plans.size<=8&&plans.keys.all{it is String&&it in (0..7).map(Int::toString)}){"serial status channels must be explicit0..7"}
     require(resumeSamples.isEmpty() || "1" in plans){"Channel 1 continuation requires its status plan"}
     for((key,plan)in plans){val channel=key.toString().toInt();configuredStatusChannels.add(channel);status[channel]=SerialStatusPlan(channel,plan,context,{closed},{tick->preflight(tick)},{tick->clock=tick},if(channel==1) resumeSamples else emptyList(),repeatLastPlan,completionIrqs,{onCompletionCleared(channel)})}
    }
    if(withTransmit){
     val plans=data["transmitPlans"];require(plans is Map<*,*>&&plans.isNotEmpty()&&plans.size<=4&&plans.keys.all{it is String&&it in setOf("1","2","4","6")}){"serial transmit requires explicit DAT DMA channels1/2/4/6"}
     for((key,plan)in plans){val channel=key.toString().toInt();require(channel in status){"serial transmit requires selected status channel"};transmit[channel]=SerialTransmitPlan(channel,plan,context,{closed},{tick->preflight(tick)},{tick->clock=tick},captureEvents,repeatLastPlan,if(completionIrqs){{onTransferComplete(channel)}}else null)}
     if(completionIrqs){
      // DAT 0x011F82E4 scans all channels in the group (0..4 for IRQ 90, 5..7 for IRQ 91) on every interrupt.
      val neededGroups=mutableListOf<IntRange>()
      if(transmit.keys.any{it in 0..4}) neededGroups.add(0..4)
      if(transmit.keys.any{it in 5..7}) neededGroups.add(5..7)
      for(groupRange in neededGroups) {
       for(channel in groupRange) {
        if(channel !in status) {
         status[channel]=SerialStatusPlan(channel,listOf(listOf(0L)),context,{closed},{tick->preflight(tick)},{tick->clock=tick},emptyList(),true,true,{onCompletionCleared(channel)})
        }
       }
      }
     }
    }
    // All configuration is frozen and validated before publishing a typed word port.
    for(index in 0..1)if(selected[index])context.ports.register(id,port(index),MmioDevice::class.java,object:MmioDevice {
     override val clockOrder=90
     override val clockIndex=index
     override fun read(offset:Long,size:Int):Long {
      exact(offset,size);check(supplied[index]){"serial shared explicit input missing group"+index};val tick=context.clock.ticks();preflight(tick)
      val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();val value=words[index];record(index,false,value,tick,location);return value
     }
     override fun write(offset:Long,size:Int,value:Long) {
      exact(offset,size);check(supplied[index]){"serial shared explicit input missing group"+index};require(value in 0..0xffffffffL&&(value xor words[index])and MUTABLE_MASK.inv()==0L){"serial shared write changes unqualified high bits"};val tick=context.clock.ticks();preflight(tick)
      val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();words[index]=value;record(index,true,value,tick,location)
     }
    })
    for((channel,plan)in status)context.ports.register(id,statusPort(channel),MmioDevice::class.java,plan)
    for((channel,plan)in transmit){
     context.ports.register(id,controlPort(channel),MmioDevice::class.java,plan)
     context.ports.register(id,txPort(channel),DmaDestinationPeer::class.java,plan)
     context.ports.register(id,capturePort(channel),TransferCapturePort::class.java,plan)
     context.ports.register(id,dataPort(channel),MmioDevice::class.java,object:MmioDevice {override val clockOrder=90;override val clockIndex=18+channel;override fun read(offset:Long,size:Int):Long=error("serial transmit CPU data read unqualified");override fun write(offset:Long,size:Int,value:Long){error("serial transmit CPU data write unqualified")}})
    }
   }
  }
  private fun onTransferComplete(channel:Int) {
   if(!completionIrqs || closed) return
   status[channel]?.latchCompletion(1L)
   syncCompletionIrqLine(channel,assertOnEdge=true)
  }
  private fun onCompletionCleared(channel:Int) {
   if(!completionIrqs || closed) return
   syncCompletionIrqLine(channel,assertOnEdge=false)
  }
  private fun syncCompletionIrqLine(channel:Int,assertOnEdge:Boolean) {
   val group=if(channel<=4) 0..4 else 5..7
   val irqLine=if(channel<=4) IRQ_GROUP_0 else IRQ_GROUP_1
   val anyPending=group.any{(status[it]?.completionStatusWord()?:0L)!=0L}
   val sink=context.ports.find("soc-gic.lines",InterruptSink::class.java) ?: return
   val enabledSink=sink as? EnabledEdgeInterruptSink
   if(enabledSink!=null) {
    val st=enabledSink.edgeState(irqLine)
    if(st.edge) {
     if(assertOnEdge && anyPending) {
      sink.setLine(irqLine,true)
      sink.setLine(irqLine,false)
     }
    } else {
     sink.setLine(irqLine,anyPending)
    }
   } else if(assertOnEdge && anyPending) {
    sink.setLine(irqLine,true)
    sink.setLine(irqLine,false)
   }
  }
  private fun exact(offset:Long,size:Int){check(!closed){"serial engine closed"};require(offset==0L&&size==4){"serial shared exact aligned word required"}}
  private fun preflight(tick:Long){check(!closed);require(tick>=clock&&tick>=0){"serial shared clock moved backwards"};check(count<Long.MAX_VALUE&&reads<Long.MAX_VALUE&&writes<Long.MAX_VALUE){"serial shared diagnostics overflow"}}
  private fun record(group:Int,write:Boolean,value:Long,tick:Long,location:ExecutionLocation?) {
   val slot=(count%capacity).toInt();groups[slot]=group;directions[slot]=write;valuesLogged[slot]=value;ticks[slot]=tick;cores[slot]=location?.core?:-1;pcs[slot]=location?.pcVirtual?:-1;clock=tick;count++;if(write)writes++ else reads++
  }
  override fun start(){check(!closed)}
  override fun interruptRoutes():List<InterruptRoute> {
   if(!completionIrqs) return emptyList()
   val routes=ArrayList<InterruptRoute>(2)
   if(transmit.keys.any{it in 0..4}) routes.add(InterruptRoute("soc-gic.lines",IRQ_GROUP_0..IRQ_GROUP_0))
   if(transmit.keys.any{it in 5..7}) routes.add(InterruptRoute("soc-gic.lines",IRQ_GROUP_1..IRQ_GROUP_1))
   return routes
  }
  override fun validateSettings(settings:Map<String,Any>){
   val normalized=normalizeScalarConfig(settings)
   require((settings.keys==frozenConfig.keys||settings.keys==values.keys)&&(settings["captureEvents"]?:false)==captureEvents&&(settings["repeatLastPlan"]?:false)==repeatLastPlan&&(settings["completionIrqs"]?:false)==completionIrqs&&normalized==values){"serial engine settings require restart"}
  }
  override fun applySettings(settings:Map<String,Any>){require(settings==frozenConfig){"serial engine settings require restart"}}
  override fun additionalMmioPorts()=(0..1).filter{selected[it]}.map(::port)+status.keys.map(::statusPort)+transmit.keys.map(::controlPort)+transmit.keys.map(::dataPort)
  override fun close(){transmit.values.forEach{it.close()};closed=true}
  override fun saveSnapshot():Map<String,Any?> {
   require(!closed){"serial engine closed"}
   val out=linkedMapOf<String,Any?>(
    "schema" to snapshotSchemaVersion,
    "componentId" to id,
    "scalarValues" to saveScalarValues(),
    "enabled" to enabled,
    "captureEvents" to captureEvents,
    "selectedGroups" to (0..1).filter{groupIndex->selected[groupIndex]},
    "initialWords" to (0..1).filter{groupIndex->selected[groupIndex]}.associate{groupIndex->groupIndex.toString() to if(supplied[groupIndex])initial[groupIndex] else null},
    "words" to (0..1).filter{groupIndex->selected[groupIndex]}.associate{groupIndex->groupIndex.toString() to if(supplied[groupIndex])words[groupIndex] else null},
    "clock" to clock,
    "readCount" to reads,
    "writeCount" to writes,
    "accessCount" to count,
    "accesses" to (maxOf(0L,count-capacity) until count).map{sequence->
     val ringSlot=(sequence%capacity).toInt()
     linkedMapOf(
      "group" to groups[ringSlot],
      "write" to directions[ringSlot],
      "value" to valuesLogged[ringSlot],
      "tick" to ticks[ringSlot],
      "core" to cores[ringSlot].takeIf{coreIndex->coreIndex>=0},
      "pcVirtual" to pcs[ringSlot].takeIf{pcValue->pcValue>=0L},
     )
    },
    "statusPlans" to status.entries.associate{entry->entry.key.toString() to entry.value.saveSnapshot()},
    "transmitPlans" to transmit.entries.associate{entry->entry.key.toString() to entry.value.saveSnapshot()},
   )
   if(completionIrqs) {
    out["completionIrqs"]=true
    out["completionStatusWords"]=status.entries.associate{entry->entry.key.toString() to entry.value.completionStatusWord()}
   }
   return out
  }
  override fun restoreSnapshot(data:Map<String,Any?>) {
   require(!closed){"serial engine closed"}
   val baseExpectedKeys=setOf(
    "schema","componentId","scalarValues","enabled","captureEvents","selectedGroups",
    "initialWords","words","clock","readCount","writeCount","accessCount","accesses",
    "statusPlans","transmitPlans",
   )
   val savedCompletionIrqs=(data["completionIrqs"] as? Boolean) ?: false
   val expectedKeys=if(savedCompletionIrqs) baseExpectedKeys+setOf("completionIrqs","completionStatusWords") else baseExpectedKeys
   require(data.keys==expectedKeys){"Unexpected soc-serial-engine snapshot keys"}
   require(SerialSnapshotCodec.parseIntegralInt(data["schema"],"schema")==snapshotSchemaVersion){"Unsupported soc-serial-engine snapshot schema"}
   require(data["componentId"]==id){"Mismatched soc-serial-engine componentId"}
   require((data["enabled"] as? Boolean)==enabled){"soc-serial-engine enabled state differs"}
   require((data["captureEvents"] as? Boolean)==captureEvents){"soc-serial-engine captureEvents setting differs"}
   require(savedCompletionIrqs == completionIrqs || (!savedCompletionIrqs && completionIrqs)) {
    "soc-serial-engine completionIrqs downgrade is refused"
   }
   val rawScalars=data["scalarValues"] as? Map<*,*> ?: throw IllegalArgumentException("Missing soc-serial-engine scalarValues")
   val savedRepeat=(rawScalars["repeatLastPlan"] as? Boolean) ?: false
   require(savedRepeat == repeatLastPlan || (!savedRepeat && repeatLastPlan)) {
    "soc-serial-engine repeatLastPlan downgrade is refused"
   }
   val savedScalarCompletionIrqs=(rawScalars["completionIrqs"] as? Boolean) ?: false
   require(savedScalarCompletionIrqs == savedCompletionIrqs) {
    "soc-serial-engine completionIrqs scalarValues mismatch"
   }
   var expectedSavedKeys=values.keys
   if(repeatLastPlan && !savedRepeat) expectedSavedKeys=expectedSavedKeys-"repeatLastPlan"
   if(completionIrqs && !savedCompletionIrqs) expectedSavedKeys=expectedSavedKeys-"completionIrqs"
   require(rawScalars.keys==expectedSavedKeys){"soc-serial-engine scalar setting keys differ"}
   for(setting in descriptor.settings) {
    if(!rawScalars.containsKey(setting.key))continue
    val rawSetting=rawScalars[setting.key] ?: throw IllegalArgumentException("Null setting ${setting.key} in $id")
    val validated=setting.validate(rawSetting)
    require(values[setting.key]==validated){"Restart-only setting ${setting.key} differs in snapshot for $id"}
   }
   val rawSelectedGroups=data["selectedGroups"] as? List<*> ?: throw IllegalArgumentException("Invalid selectedGroups in snapshot")
   val parsedSelectedGroups=rawSelectedGroups.map{element->SerialSnapshotCodec.parseIntegralInt(element,"selectedGroup")}
   val expectedSelectedGroups=(0..1).filter{groupIndex->selected[groupIndex]}
   require(parsedSelectedGroups==expectedSelectedGroups){"soc-serial-engine selectedGroups differ"}
   val expectedGroupKeys=expectedSelectedGroups.map(Int::toString).toSet()
   val rawInitialWords=data["initialWords"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid initialWords in snapshot")
   val rawWords=data["words"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid words in snapshot")
   require(rawInitialWords.keys==expectedGroupKeys&&rawWords.keys==expectedGroupKeys){"soc-serial-engine group word keys differ"}
   val nextWords=LongArray(2)
   for(groupIndex in expectedSelectedGroups) {
    val groupKey=groupIndex.toString()
    if(!supplied[groupIndex]) {
     require(rawInitialWords[groupKey]==null&&rawWords[groupKey]==null){"Null group $groupIndex must remain null in snapshot"}
    } else {
     val savedInitial=SerialSnapshotCodec.parseUnsignedWord(rawInitialWords[groupKey],"initialWords.$groupKey")
     val savedWord=SerialSnapshotCodec.parseUnsignedWord(rawWords[groupKey],"words.$groupKey")
     require(savedInitial==initial[groupIndex]){"Frozen initialWord differs for group $groupIndex"}
     require(((savedWord xor initial[groupIndex]) and MUTABLE_MASK.inv())==0L){"High bits of shared word $groupIndex violate mutable mask"}
     nextWords[groupIndex]=savedWord
    }
   }
   val nextClock=SerialSnapshotCodec.parseIntegralLong(data["clock"],"clock")
   val nextReads=SerialSnapshotCodec.parseIntegralLong(data["readCount"],"readCount")
   val nextWrites=SerialSnapshotCodec.parseIntegralLong(data["writeCount"],"writeCount")
   val nextCount=SerialSnapshotCodec.parseIntegralLong(data["accessCount"],"accessCount")
   require(nextClock>=0L&&nextReads>=0L&&nextWrites>=0L&&nextCount>=0L){"Negative counter or clock in soc-serial-engine snapshot"}
   val countSum=try{Math.addExact(nextReads,nextWrites)}catch(_:ArithmeticException){throw IllegalArgumentException("Shared access counter overflow")}
   require(countSum==nextCount){"Shared readCount + writeCount must equal accessCount"}
   if(!enabled)require(nextClock==0L&&nextCount==0L){"Disabled soc-serial-engine must have zero clock and accesses"}
   val rawAccesses=data["accesses"] as? List<*> ?: throw IllegalArgumentException("Invalid accesses list in snapshot")
   require(rawAccesses.size==minOf(nextCount,capacity.toLong()).toInt()){"Shared access log size disagrees with accessCount"}
   val parsedAccesses=rawAccesses.map{rawEntry->
    val entryMap=rawEntry as? Map<*,*> ?: throw IllegalArgumentException("Invalid shared access entry")
    require(entryMap.keys==setOf("group","write","value","tick","core","pcVirtual")){"Unexpected shared access entry keys"}
    val accessGroup=SerialSnapshotCodec.parseIntegralInt(entryMap["group"],"access.group")
    require(accessGroup in 0..1&&selected[accessGroup]&&supplied[accessGroup]){"Invalid shared access group $accessGroup"}
    val accessWrite=entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid shared access write flag")
    val accessValue=SerialSnapshotCodec.parseUnsignedWord(entryMap["value"],"access.value")
    val accessTick=SerialSnapshotCodec.parseIntegralLong(entryMap["tick"],"access.tick")
    require(accessTick in 0L..nextClock){"Shared access tick out of bounds"}
    val accessCore=if(entryMap["core"]==null)-1 else SerialSnapshotCodec.parseIntegralInt(entryMap["core"],"access.core").also{parsed->require(parsed>=0)}
    val accessPc=if(entryMap["pcVirtual"]==null)-1L else SerialSnapshotCodec.parseIntegralLong(entryMap["pcVirtual"],"access.pcVirtual").also{parsed->require(parsed>=0L)}
    ParsedSharedAccess(accessGroup,accessWrite,accessValue,accessTick,accessCore,accessPc)
   }
   val rawStatusPlans=data["statusPlans"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid statusPlans map in snapshot")
   val expectedStatusKeys=if(completionIrqs && !savedCompletionIrqs) configuredStatusChannels.map(Int::toString).toSet() else status.keys.map(Int::toString).toSet()
   require(rawStatusPlans.keys==expectedStatusKeys){"Status channel keys differ in snapshot"}
   val parsedStatus=LinkedHashMap<Int,SerialStatusPlan.ParsedSnapshot>()
   for(channelKey in expectedStatusKeys) {
    val channel=channelKey.toInt()
    parsedStatus[channel]=status.getValue(channel).parseSnapshot(rawStatusPlans[channelKey],nextClock)
   }
   val parsedCompletionWords=LinkedHashMap<Int,Long>()
   if(savedCompletionIrqs) {
    val rawCompletion=data["completionStatusWords"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid completionStatusWords map in snapshot")
    require(rawCompletion.keys==status.keys.map(Int::toString).toSet()){"completionStatusWords channel keys differ in snapshot"}
    for((channel,_)in status) {
     val word=SerialSnapshotCodec.parseUnsignedWord(rawCompletion[channel.toString()],"completionStatusWords.$channel")
     require((word and 0x10001L.inv())==0L){"completionStatusWords.$channel out of 0x10001 domain"}
     parsedCompletionWords[channel]=word
    }
   }
   val rawTransmitPlans=data["transmitPlans"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid transmitPlans map in snapshot")
   require(rawTransmitPlans.keys==transmit.keys.map(Int::toString).toSet()){"Transmit channel keys differ in snapshot"}
   val parsedTransmit=LinkedHashMap<Int,SerialTransmitPlan.ParsedSnapshot>()
   for((channel,transmitPlan)in transmit) {
    parsedTransmit[channel]=transmitPlan.parseSnapshot(rawTransmitPlans[channel.toString()],nextClock)
   }
   val restoredScalars=LinkedHashMap<Any?,Any?>(rawScalars)
   if(repeatLastPlan && !savedRepeat) restoredScalars["repeatLastPlan"]=true
   if(completionIrqs && !savedCompletionIrqs) restoredScalars["completionIrqs"]=true
   restoreScalarValues(restoredScalars,descriptor)
   nextWords.copyInto(words)
   clock=nextClock
   reads=nextReads
   writes=nextWrites
   count=nextCount
   groups.fill(0)
   directions.fill(false)
   valuesLogged.fill(0L)
   ticks.fill(0L)
   cores.fill(-1)
   pcs.fill(-1L)
   val startSequence=maxOf(0L,nextCount-capacity)
   for((offset,accessEntry)in parsedAccesses.withIndex()) {
    val ringSlot=((startSequence+offset)%capacity).toInt()
    groups[ringSlot]=accessEntry.group
    directions[ringSlot]=accessEntry.write
    valuesLogged[ringSlot]=accessEntry.value
    ticks[ringSlot]=accessEntry.tick
    cores[ringSlot]=accessEntry.core
    pcs[ringSlot]=accessEntry.pcVirtual
   }
   for((channel,statusPlan)in status) {
    val parsed=parsedStatus[channel]
    if(parsed!=null) {
     statusPlan.applySnapshot(parsed)
    } else {
     statusPlan.resetIdle()
    }
    statusPlan.setCompletionStatusWord(parsedCompletionWords[channel] ?: 0L)
   }
   for((channel,transmitPlan)in transmit) {
    transmitPlan.applySnapshot(parsedTransmit.getValue(channel))
   }
   if(completionIrqs) {
    if(transmit.keys.any{it in 0..4}) syncCompletionIrqLine(0,assertOnEdge=false)
    if(transmit.keys.any{it in 5..7}) syncCompletionIrqLine(5,assertOnEdge=false)
   }
  }
  override fun state():Map<String,Any?> = super.state()+(if(withTransmit)mapOf("transmitChannels" to transmit.keys.toList(),"transmitPlans" to transmit.entries.associate{it.key.toString() to it.value.frozenPlan()},"transmitStates" to transmit.entries.associate{it.key.toString() to it.value.state()})else emptyMap())+(if(withStatus)mapOf("statusChannels" to status.keys.toList(),"statusPlans" to status.entries.associate{it.key.toString() to it.value.frozenPlan()},"statusStates" to status.entries.associate{it.key.toString() to it.value.state()})else emptyMap())+mapOf("enabled" to enabled,"closed" to closed,"selectedGroups" to(0..1).filter{selected[it]},"initialWords" to(0..1).filter{selected[it]}.associate{it.toString() to if(supplied[it])initial[it]else null},"words" to(0..1).filter{selected[it]}.associate{it.toString() to if(supplied[it])words[it]else null},"mutableMask" to MUTABLE_MASK,"clock" to clock,"readCount" to reads,"writeCount" to writes,"accessCount" to count,"accessCapacity" to capacity,"accessLogTruncated" to(count>capacity),"accesses" to(maxOf(0L,count-capacity) until count).map{sequence->val i=(sequence%capacity).toInt();mapOf("group" to groups[i],"write" to directions[i],"value" to valuesLogged[i],"tick" to ticks[i],"core" to cores[i].takeIf{it>=0},"pcVirtual" to pcs[i].takeIf{it>=0})},"basis" to "DAT11F9828 shared low8-bit read/modify/write domain; initial words are frozen explicit HYP, null remains missing. Passive word retention only, no serial transfer, reset, clock frequency, ready or IRQ semantics.")
 }
}
