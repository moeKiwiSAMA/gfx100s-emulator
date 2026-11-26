package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object SdHostSnapshotSupport {
 val DESCRIPTOR = ComponentDescriptor(
  "soc-sd-host",
  "0.0.1",
  settings = listOf(
   Setting(
    "profile",
    ValueKind.TEXT,
    "disabled",
    choices = listOf(
     "disabled",
     SdHostFactory.PROFILE,
     SdHostFactory.CONTROL_PROFILE,
     SdHostFactory.CLOCK_PROFILE,
     SdHostFactory.INIT_PROFILE,
     SdHostFactory.PHY_PROFILE,
     SdHostFactory.CARD_PROFILE,
     SdHostFactory.ADDRESS_PROFILE,
     SdHostFactory.VENDOR_PHY_PROFILE,
     SdHostFactory.UHS_PHY_PROFILE,
    ),
    live = false,
   ),
   Setting("inputFile", ValueKind.TEXT, "", live = false),
  ),
 )

 fun parseIntegralLong(value: Any?, fieldName: String): Long {
  require(value is Byte || value is Short || value is Int || value is Long) { "Integral value required for $fieldName" }
  return (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $fieldName")
 }

 fun parseIntegralInt(value: Any?, fieldName: String): Int {
  val numberValue = parseIntegralLong(value, fieldName)
  require(numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out of Int range for $fieldName" }
  return numberValue.toInt()
 }

 fun parseUnsigned32(value: Any?, fieldName: String): Long =
  parseIntegralLong(value, fieldName).also { parsed -> require(parsed in 0L..0xffffffffL) { "Unsigned u32 required for $fieldName" } }

 fun parseUnsigned16(value: Any?, fieldName: String): Long =
  parseIntegralLong(value, fieldName).also { parsed -> require(parsed in 0L..0xffffL) { "Unsigned u16 required for $fieldName" } }

 fun validateScalarValues(rawScalars: Any?, currentValues: Map<String, Any>, componentId: String): Map<*, *> {
  val scalarMap = rawScalars as? Map<*, *> ?: throw IllegalArgumentException("Missing scalarValues for $componentId")
  require(scalarMap.keys.all { key -> key is String && DESCRIPTOR.settings.any { setting -> setting.key == key } }) {
   "Unknown scalar setting key in snapshot for $componentId"
  }
  require(scalarMap.keys == currentValues.keys) { "Scalar setting keys differ in snapshot for $componentId" }
  for (setting in DESCRIPTOR.settings) {
   if (!scalarMap.containsKey(setting.key)) continue
   val rawSetting = scalarMap[setting.key] ?: throw IllegalArgumentException("Null setting ${setting.key} in $componentId")
   val validated = setting.validate(rawSetting)
   require(currentValues[setting.key] == validated) { "Restart-only setting ${setting.key} differs in snapshot for $componentId" }
  }
  return scalarMap
 }
}

/** Exact selected software word only. Explicit initial values and full-word retention are HYP. */
class SdHostFactory : ComponentFactory {
 companion object {
  const val CARD_PROFILE="gfx100s-sd-host-card-transactions-experimental-v6"
  const val ADDRESS_PROFILE="gfx100s-sd-host-address-extension-experimental-v7"
  const val VENDOR_PHY_PROFILE="gfx100s-sd-host-vendor-phy-experimental-v8"
  const val UHS_PHY_PROFILE="gfx100s-sd-host-uhs-phy-experimental-v9"
  const val PHY_PROFILE="gfx100s-sd-host-phy-word-retention-experimental-v5"
  const val INIT_PROFILE="gfx100s-sd-host-init-registers-experimental-v4"
  const val CLOCK_PROFILE="gfx100s-sd-host-clock-samples-experimental-v3"
  const val CONTROL_PROFILE="gfx100s-sd-host-controls-retention-experimental-v2"
  const val PROFILE="gfx100s-sd-host-word80-retention-experimental-v1"
  fun port(slot:Int):String {require(slot in 0..1);return "soc-sd-host.word80-"+slot}
  private fun integer(value:Any?):Long {require(value is Byte||value is Short||value is Int||value is Long);return(value as Number).toLong()}
  private fun unsigned(value:Any?)=integer(value).also{require(it in 0..0xffffffffL){"SD host explicit u32 input required"}}
 }
 override val descriptor=SdHostSnapshotSupport.DESCRIPTOR
 override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=
  when(config["profile"]){CARD_PROFILE,ADDRESS_PROFILE,VENDOR_PHY_PROFILE,UHS_PHY_PROFILE->SdTransactionHost(context,config);PHY_PROFILE,INIT_PROFILE->SdInitHost(context,config);CLOCK_PROFILE->SdClockHost(context,config);CONTROL_PROFILE->SdControlHost(context,config);else->Host(context,config)}
 private class Host(context:ComponentContext,config:Map<String,Any>):ScalarComponent("soc-sd-host",context,config),StatefulComponent {
  private val enabled=config.getValue("profile")==PROFILE
  private val initial=LongArray(2)
  private val words=LongArray(2)
  private var closed=false
  private var count=0L
  private var reads=0L
  private var writes=0L
  private val slots=IntArray(256)
  private val directions=BooleanArray(256)
  private val data=LongArray(256)
  private val ticks=LongArray(256)
  private val cores=IntArray(256){-1}
  private val pcs=LongArray(256){-1}
  private var inputSha:String?=null
  private data class ParsedWordAccess(val slot:Int,val write:Boolean,val value:Long,val tick:Long,val core:Int,val pcVirtual:Long)
  init {
   val file=config.getValue("inputFile")as String
   require(config["profile"]in listOf("disabled",PROFILE)&&enabled==file.isNotBlank()){"SD host profile/input mismatch"}
   if(enabled){
    val input=context.readData(file);require(input.keys==setOf("schema","profile","initialWords")&&integer(input["schema"])==1L&&input["profile"]==PROFILE)
    val raw=input["initialWords"]as? Map<*,*>?:error("two explicit SD host words required");require(raw.keys==setOf("0","1"))
    for(slot in 0..1)initial[slot]=unsigned(raw[slot.toString()]);initial.copyInto(words)
    val canonical=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply{initial.forEach{putInt(it.toInt())}}.array()
    inputSha=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    require((0..1).all{context.ports.find(port(it),MmioDevice::class.java)==null}){"SD host port alias"}
    for(slot in 0..1)context.ports.register(id,port(slot),MmioDevice::class.java,object:MmioDevice {
     override val clockOrder=85
     override val clockIndex=slot
     private fun check(offset:Long,size:Int){check(!closed){"SD host closed"};require(offset==0L&&size==4){"SD host exact aligned word80 only"}}
     override fun read(offset:Long,size:Int):Long {check(offset,size);val at=preflight();val value=words[slot];val location=location();record(slot,false,value,at,location);return value}
     override fun write(offset:Long,size:Int,value:Long){check(offset,size);require(value in 0..0xffffffffL);val at=preflight();val location=location();words[slot]=value;record(slot,true,value,at,location)}
    })
   }
  }
  private fun preflight():Long {val tick=context.clock.ticks();require(tick>=0&&count<Long.MAX_VALUE&&reads<Long.MAX_VALUE&&writes<Long.MAX_VALUE);return tick}
  private fun location()=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
  private fun record(slot:Int,write:Boolean,value:Long,tick:Long,location:ExecutionLocation?){val at=(count%256).toInt();slots[at]=slot;directions[at]=write;data[at]=value;ticks[at]=tick;cores[at]=location?.core?:-1;pcs[at]=location?.pcVirtual?:-1;count++;if(write)writes++ else reads++}
  override fun start(){check(!closed)}
  override fun close(){closed=true}
  override fun validateSettings(settings:Map<String,Any>){check(!closed);require(settings.all{values[it.key]==it.value}){"SD host settings require restart"}}
  override fun applySettings(settings:Map<String,Any>){validateSettings(settings)}
  override fun additionalMmioPorts()=if(enabled)(0..1).map{port(it)}else emptyList()
  override fun saveSnapshot():Map<String,Any?> {
   require(!closed){"SD host closed"}
   return linkedMapOf(
    "schema" to snapshotSchemaVersion,
    "componentId" to id,
    "kind" to "word80",
    "scalarValues" to saveScalarValues(),
    "enabled" to enabled,
    "inputCanonicalSha256" to inputSha,
    "words" to if(enabled)(0..1).associate{slotIndex->slotIndex.toString() to words[slotIndex]} else emptyMap<String,Long>(),
    "readCount" to reads,
    "writeCount" to writes,
    "accessCount" to count,
    "accesses" to (maxOf(0L,count-256) until count).map{sequence->
     val ringSlot=(sequence%256).toInt()
     linkedMapOf(
      "slot" to slots[ringSlot],
      "write" to directions[ringSlot],
      "value" to data[ringSlot],
      "tick" to ticks[ringSlot],
      "core" to cores[ringSlot].takeIf{coreIndex->coreIndex>=0},
      "pcVirtual" to pcs[ringSlot].takeIf{pcValue->pcValue>=0L},
     )
    },
   )
  }
  override fun restoreSnapshot(data:Map<String,Any?>) {
   require(!closed){"SD host closed"}
   val expectedKeys=setOf("schema","componentId","kind","scalarValues","enabled","inputCanonicalSha256","words","readCount","writeCount","accessCount","accesses")
   require(data.keys==expectedKeys){"Unexpected SD word80 snapshot keys"}
   require(SdHostSnapshotSupport.parseIntegralInt(data["schema"],"schema")==snapshotSchemaVersion){"Unsupported SD snapshot schema"}
   require(data["componentId"]==id&&data["kind"]=="word80"){"Mismatched SD snapshot identity"}
   val rawScalars=SdHostSnapshotSupport.validateScalarValues(data["scalarValues"],values,id)
   require((data["enabled"] as? Boolean)==enabled){"SD enabled state differs"}
   require(data["inputCanonicalSha256"]==inputSha){"SD inputCanonicalSha256 differs"}
   val rawWords=data["words"] as? Map<*,*> ?: throw IllegalArgumentException("Invalid words map in SD snapshot")
   val nextWords=LongArray(2)
   if(!enabled) {
    require(rawWords.isEmpty()){"Disabled SD host must have empty words map"}
   } else {
    require(rawWords.keys==setOf("0","1")){"Enabled SD host requires slot 0 and 1 words"}
    for(slotIndex in 0..1) {
     nextWords[slotIndex]=SdHostSnapshotSupport.parseUnsigned32(rawWords[slotIndex.toString()],"words.$slotIndex")
    }
   }
   val nextReads=SdHostSnapshotSupport.parseIntegralLong(data["readCount"],"readCount")
   val nextWrites=SdHostSnapshotSupport.parseIntegralLong(data["writeCount"],"writeCount")
   val nextCount=SdHostSnapshotSupport.parseIntegralLong(data["accessCount"],"accessCount")
   require(nextReads>=0L&&nextWrites>=0L&&nextCount>=0L){"Negative counter in SD snapshot"}
   val countSum=try{Math.addExact(nextReads,nextWrites)}catch(_:ArithmeticException){throw IllegalArgumentException("SD counter overflow")}
   require(countSum==nextCount){"SD readCount + writeCount must equal accessCount"}
   if(!enabled)require(nextCount==0L){"Disabled SD host cannot record accesses"}
   val rawAccesses=data["accesses"] as? List<*> ?: throw IllegalArgumentException("Invalid accesses list in SD snapshot")
   require(rawAccesses.size==minOf(nextCount,256L).toInt()){"SD access log size disagrees with accessCount"}
   val parsedAccesses=rawAccesses.map{rawEntry->
    val entryMap=rawEntry as? Map<*,*> ?: throw IllegalArgumentException("Invalid SD access entry")
    require(entryMap.keys==setOf("slot","write","value","tick","core","pcVirtual")){"Unexpected SD access entry keys"}
    val entrySlot=SdHostSnapshotSupport.parseIntegralInt(entryMap["slot"],"access.slot")
    require(entrySlot in 0..1){"Invalid SD access slot"}
    val entryWrite=entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid SD access write flag")
    val entryValue=SdHostSnapshotSupport.parseUnsigned32(entryMap["value"],"access.value")
    val entryTick=SdHostSnapshotSupport.parseIntegralLong(entryMap["tick"],"access.tick")
    require(entryTick>=0L){"Negative SD access tick"}
    val entryCore=if(entryMap["core"]==null)-1 else SdHostSnapshotSupport.parseIntegralInt(entryMap["core"],"access.core").also{parsed->require(parsed>=0)}
    val entryPc=if(entryMap["pcVirtual"]==null)-1L else SdHostSnapshotSupport.parseIntegralLong(entryMap["pcVirtual"],"access.pcVirtual").also{parsed->require(parsed>=0L)}
    ParsedWordAccess(entrySlot,entryWrite,entryValue,entryTick,entryCore,entryPc)
   }
   restoreScalarValues(rawScalars,SdHostSnapshotSupport.DESCRIPTOR)
   nextWords.copyInto(words)
   reads=nextReads
   writes=nextWrites
   count=nextCount
   slots.fill(0)
   directions.fill(false)
   this.data.fill(0L)
   ticks.fill(0L)
   cores.fill(-1)
   pcs.fill(-1L)
   val startSequence=maxOf(0L,nextCount-256)
   for((offset,accessEntry)in parsedAccesses.withIndex()) {
    val ringSlot=((startSequence+offset)%256).toInt()
    slots[ringSlot]=accessEntry.slot
    directions[ringSlot]=accessEntry.write
    this.data[ringSlot]=accessEntry.value
    ticks[ringSlot]=accessEntry.tick
    cores[ringSlot]=accessEntry.core
    pcs[ringSlot]=accessEntry.pcVirtual
   }
  }
  override fun state():Map<String,Any?> {
   val base=super.state()+mapOf("enabled" to enabled,"closed" to closed)
   if(!enabled)return base
   return base+mapOf("initialWords" to(0..1).associate{it.toString() to initial[it]},"words" to(0..1).associate{it.toString() to words[it]},"inputCanonicalSha256" to inputSha,
    "readCount" to reads,"writeCount" to writes,"accessCount" to count,"accessCapacity" to 256,"accessLogTruncated" to(count>256),
    "accesses" to(maxOf(0,count-256) until count).map{n->val at=(n%256).toInt();mapOf("slot" to slots[at],"write" to directions[at],"value" to data[at],"tick" to ticks[at],"core" to cores[at].takeIf{it>=0},"pcVirtual" to pcs[at].takeIf{it>=0})},
    "cardProtocolQualified" to false,"basis" to "DAT two-slot word80 software access; explicit unmeasured inputs/full-u32 retention HYP. No reset/ready/clock/IRQ/DMA/card protocol or block-storage connection.")
  }
 }
}
