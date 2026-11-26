package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One JAR owns the optional paired SoC blocks; supplied samples/clock laws are explicit HYP. */
class DramFactory:ComponentFactory {
 companion object {
  const val PROFILE="gfx100s-dram-functional-experimental-v1"
  const val COMMAND_PROFILE="gfx100s-dram-command-functional-experimental-v2"
  const val SNAPSHOT_PROFILE="gfx100s-dram-snapshot-functional-experimental-v3"
  const val FACTORY_COLDSTART_PROFILE=DramColdStartController.PROFILE
  const val COMMANDS="b4e3892f978b34ab7f70c4a5df7d719b3e80edfbfd7d76239cb0205259a9a30d"
  const val FIRMWARE="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
  const val PROGRAMMING="c98dcc492448021370679b5bfab4d49194d6669069f93c29eacd4d2d2140da0d"
  const val CONFIGURATION="53408ae8622ba49a18ee44ddc04caaa1b2f112982712d1870c56777c58c48481"
 private val DESCRIPTOR=ComponentDescriptor("soc-dram","0.0.1",settings=listOf(Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled",PROFILE,COMMAND_PROFILE,SNAPSHOT_PROFILE,FACTORY_COLDSTART_PROFILE),live=false),Setting("inputFile",ValueKind.TEXT,"",live=false),Setting("repeatableCommandModes",ValueKind.BOOLEAN,false,live=false),Setting("coldStartChannel0",ValueKind.BOOLEAN,false,live=false)))
 private val SNAPSHOT_KEYS=setOf("schema","componentId","scalars","enabled","banks")
 private val COLDSTART_SNAPSHOT_KEYS=SNAPSHOT_KEYS+"coldStart"
 private val BANK_ENTRY_KEYS=setOf("pair","command","snapshot")
 private fun snapshotInt(value:Any?,field:String):Int {
  require(value is Byte||value is Short||value is Int||value is Long){"Snapshot field $field requires integer"}
  val converted=(value as? Number)?.toLong()?:throw IllegalArgumentException("Snapshot field $field requires integer")
  require(converted in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()){"Snapshot field $field out of range"}
  return converted.toInt()
 }
 private fun snapshotBoolean(value:Any?,field:String):Boolean =
  value as? Boolean?:throw IllegalArgumentException("Snapshot field $field requires boolean")
 private fun snapshotMap(value:Any?,field:String):Map<*,*> {
  val map=value as? Map<*,*>?:throw IllegalArgumentException("Snapshot field $field requires map")
  require(map.keys.all{key->key is String}){"Snapshot field $field requires string keys"}
  return map
 }
 }
 override val descriptor=DESCRIPTOR
 override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=Controller(context,config)
 private class Controller(context:ComponentContext,config:Map<String,Any>):ScalarComponent("soc-dram",context,mapOf("repeatableCommandModes" to false,"coldStartChannel0" to false)+config),StatefulComponent {
  private var closed=false
  private val inputContext=context
  private val selectedProfile=config["profile"]
  private val repeatableCommandModes=(config["repeatableCommandModes"] as? Boolean) ?: false
  private val coldStartChannel0=(config["coldStartChannel0"] as? Boolean) ?: false
  private val commandsEnabled=config["profile"] in listOf(COMMAND_PROFILE,SNAPSHOT_PROFILE)
  private val snapshotsEnabled=config["profile"]==SNAPSHOT_PROFILE
  private val coldStartEnabled=config["profile"]==FACTORY_COLDSTART_PROFILE
  private val tableEnabled=config["profile"] in listOf(PROFILE,COMMAND_PROFILE,SNAPSHOT_PROFILE)
  private val enabled=tableEnabled || coldStartEnabled
  private val coldStart=if(coldStartEnabled || coldStartChannel0) DramColdStartController() else null
  private val commandBanks=linkedMapOf<Int,DramCommandBank>()
  private val snapshots=linkedMapOf<Int,DramSnapshotBank>()
  private val postAckPhyBanks=mutableSetOf<Int>()
  private val replays=mutableMapOf<Int,DramPhyReplay>()
  private val banks:Map<Int,DramPairBank>
  private val names:List<String>
  private var refusedAdvance=false
  init {
   require(config.keys - "repeatableCommandModes" - "coldStartChannel0" == setOf("profile","inputFile") &&
    config["profile"] in listOf("disabled",PROFILE,COMMAND_PROFILE,SNAPSHOT_PROFILE,FACTORY_COLDSTART_PROFILE) &&
    config["inputFile"] is String &&
    ("repeatableCommandModes" !in config || config["repeatableCommandModes"] is Boolean) &&
    ("coldStartChannel0" !in config || config["coldStartChannel0"] is Boolean))
   require(!repeatableCommandModes || commandsEnabled) { "DRAM repeatableCommandModes requires a command-enabled profile" }
   require(!coldStartChannel0 || tableEnabled) { "DRAM coldStartChannel0 requires a table-backed DRAM profile" }
   val file=config.getValue("inputFile")as String;require(tableEnabled==file.isNotBlank()) {"DRAM profile/input selection mismatch"}
   banks=if(tableEnabled)parse(context.readData(file))else emptyMap()
   if(coldStartChannel0) require(0 in banks) { "DRAM coldStartChannel0 requires bank 0 in inputFile" }
   names=(banks.keys.flatMap{listOf("soc-dram.controller-$it","soc-dram.phy-$it")+if(it in snapshots)listOf("soc-dram.snapshot-ack-$it")else emptyList()}) +
    (if(coldStartEnabled) DramColdStartController.ALL_PORTS else emptyList())
   if(coldStartEnabled && coldStart!=null) {
    context.ports.register(id,DramColdStartController.PREINIT_PORT,MmioDevice::class.java,coldStart.preInitDevice)
    context.ports.register(id,DramColdStartController.COMMIT_PORT,MmioDevice::class.java,coldStart.commitDevice)
    context.ports.register(id,DramColdStartController.CTL0_PORT,MmioDevice::class.java,coldStart.ctl0Device)
    context.ports.register(id,DramColdStartController.PHY0_PORT,MmioDevice::class.java,coldStart.phy0Device)
    context.ports.register(id,DramColdStartController.ARBITER_PORT,MmioDevice::class.java,coldStart.arbiterDevice)
    context.ports.register(id,DramColdStartController.BRIDGE_PORT,MmioDevice::class.java,coldStart.bridgeDevice)
    context.ports.register(id,DramColdStartController.STAGE1_FLAG_PORT,MmioDevice::class.java,coldStart.stage1FlagDevice)
   }
   // Parse and validate every selected pair before publishing any port.
   for((index,bank)in banks) {
    context.ports.register(id,"soc-dram.controller-$index",MmioDevice::class.java,object:TickAwareMmioDevice {
     override val clockOrder=9 // Explicit functional order: temperature8, DRAM9 (bank index), DMA10.
     override val clockIndex=index
     override fun nextDeadline():Long? {val a=bank.nextDeadline();val b=commandBanks[index]?.nextDeadline();val c=snapshots[index]?.nextDeadline();val first=if(a==null)b else if(b==null)a else minOf(a,b);return if(first==null)c else if(c==null)first else minOf(first,c)}
     override fun nextInterestedTick():Long = nextDeadline() ?: Long.MAX_VALUE
     override fun requiresEveryTick():Boolean = false
     override fun beforeInstruction(tick:Long)=advance(index,bank,tick)
     override fun read(offset:Long,size:Int):Long {
      if (index == 0 && coldStartChannel0 && coldStart != null && !coldStart.isInitComplete) {
       when {
        offset in 0x5200L until 0x5260L -> return coldStart.preInitDevice.read(offset - 0x5200L, size)
        offset == 0x5384L -> return coldStart.commitDevice.read(0L, size)
        offset in 0x7000L until 0x7C44L -> return coldStart.ctl0Device.read(offset - 0x7000L, size)
       }
      }
      val word=wordOffset(offset);val tick=context.clock.ticks()
      replayCommand(index,word)?.let{return it.read(word,size,tick)}
      activeReplay(index)?.let{return it.access(bank,true,word,size,null,tick)}
      val snapshot=snapshots[index];if(snapshot?.isActive()==true&&snapshot.claims(word))return snapshot.read(word,size,tick);val command=commandBanks[index];return if(command?.claims(word)==true)command.read(word,size,tick)else bank.read(true,word,size,tick)}
     override fun write(offset:Long,size:Int,value:Long) {
      if (index == 0 && coldStartChannel0 && coldStart != null &&
          (!coldStart.isInitComplete || (!bank.hasAcknowledgedInitialization() && (offset == 0x7c20L || offset == 0x7c40L || offset == 0x7084L)))) {
       when {
        offset in 0x5200L until 0x5260L -> { coldStart.preInitDevice.write(offset - 0x5200L, size, value); return }
        offset == 0x5384L -> { coldStart.commitDevice.write(0L, size, value); return }
        offset in 0x7000L until 0x7C44L -> { coldStart.ctl0Device.write(offset - 0x7000L, size, value); return }
       }
      }
      val word=wordOffset(offset);val tick=context.clock.ticks()
      replayCommand(index,word)?.let{it.write(word,size,value,tick);return}
      val finishedRepeatable = replays[index]?.takeIf { it.repeatable && it.allowsCommandAt() }
      if (finishedRepeatable != null && word == 0x7114 && snapshots[index]?.hasAcknowledgement() == true) {
       check(bank.nextDeadline() == null && commandBanks[index]?.isActive() != true) { "DRAM earlier request active" }
       snapshots.getValue(index).resetForNextCycle()
       finishedRepeatable.resetForNextCycle()
       writeController(index, bank, word, size, value, tick)
       return
      }
      activeReplay(index)?.let{it.access(bank,true,word,size,value,tick);return}
      writeController(index,bank,word,size,value,tick)
     }
    })
    context.ports.register(id,"soc-dram.phy-$index",MmioDevice::class.java,object:MmioDevice {
     override val clockOrder=90
     override val clockIndex=index
     override fun read(offset:Long,size:Int):Long {
      if (index == 0 && coldStartChannel0 && coldStart != null && !coldStart.isInitComplete && offset in 0x2000L until 0x475CL) {
       return coldStart.phy0Device.read(offset - 0x2000L, size)
      }
      val word=wordOffset(offset);val tick=context.clock.ticks()
      val replay = activeReplay(index)
      if (replay != null && replay.repeatable && replay.allowsCommandAt() && replay.isCalibrationOffset(word)) {
       return bank.read(false, word, size, tick)
      }
      return replay?.access(bank,false,word,size,null,tick)?:bank.read(false,word,size,tick)
     }
     override fun write(offset:Long,size:Int,value:Long){
      if (index == 0 && coldStartChannel0 && coldStart != null && !coldStart.isInitComplete && offset in 0x2000L until 0x475CL) {
       coldStart.phy0Device.write(offset - 0x2000L, size, value)
       return
      }
      val tick=context.clock.ticks()
      val selected=activeReplay(index)?:replays[index]?.takeIf{offset==0x2000L&&snapshots[index]?.hasAcknowledgement()==true}
      if(selected!=null) {
       check(commandBanks[index]?.isActive()!=true)
       selected.access(bank,false,wordOffset(offset),size,value,tick)
       return
      }
      if(index in postAckPhyBanks && offset==0x2000L && snapshots[index]?.hasAcknowledgement()==true) {
       check(commandBanks[index]?.isActive()!=true){"DRAM command sequence active"}
       bank.clearPhyControl(size,value,tick)
       return
      }
      check(commandBanks[index]?.isActive()!=true&&snapshots[index]?.isActive()!=true){"DRAM command/snapshot sequence active"}
      bank.write(false,wordOffset(offset),size,value,tick)
     }
    })
    snapshots[index]?.let{snapshot->context.ports.register(id,"soc-dram.snapshot-ack-$index",MmioDevice::class.java,object:MmioDevice {
     override val clockOrder=90
     override val clockIndex=index
     override fun read(offset:Long,size:Int):Long=error("DRAM snapshot external acknowledgement reads unqualified")
     override fun write(offset:Long,size:Int,value:Long) {
      val tick=context.clock.ticks()
      activeReplay(index)?.let{it.access(bank,false,wordOffset(offset),size,value,tick,external=true);return}
      snapshot.acknowledge(offset,size,value,tick)
     }
    })}
   }
  }
  private fun activeReplay(index:Int):DramPhyReplay? = replays[index]?.takeIf{it.cursor>0}
  private fun replayCommand(index:Int,word:Int):DramCommandBank? {
   if(replays[index]?.allowsCommandAt()!=true)return null
   return commandBanks[index]?.takeIf{it.claims(word)}
  }
  private fun writeController(index:Int,bank:DramPairBank,word:Int,size:Int,value:Long,tick:Long) {
   val command=commandBanks[index];val snapshot=snapshots[index]
   if(snapshot!=null&&snapshot.claims(word)&&(snapshot.isActive()||word==0x7114)) {
    check(bank.nextDeadline()==null&&command?.isActive()!=true){"DRAM earlier request active"}
    if(!snapshot.isActive())check(bank.hasAcknowledgedInitialization()&&command?.hasObservedZero()==true){"DRAM snapshot requires observed init ACK and command zero, not assumed ROM state"}
    snapshot.write(word,size,value,tick)
   }else if(command?.claims(word)==true) {
    check(bank.nextDeadline()==null&&snapshot?.isActive()!=true){"DRAM initialization/snapshot request active"};command.write(word,size,value,tick)
   }else {
    check(command?.isActive()!=true&&snapshot?.isActive()!=true){"DRAM command/snapshot sequence active"};bank.write(true,word,size,value,tick)
   }
  }
  private fun advance(index:Int,bank:DramPairBank,tick:Long) {
   try {
    val command=commandBanks[index];val snapshot=snapshots[index]
    if(command==null){bank.advance(tick);refusedAdvance=false;return}
    bank.preflightClock(tick);command.preflightClock(tick);snapshot?.preflightClock(tick)
    val a=bank.nextDeadline();val b=command.nextDeadline();val c=snapshot?.nextDeadline()
    check((if(a!=null)1 else 0)+(if(b!=null)1 else 0)+(if(c!=null)1 else 0)<=1){"DRAM overlapping requests are unqualified"}
    // Deliver the pending explicit input first. A refusal must not advance any idle subdevice.
    when {
     c!=null->{checkNotNull(snapshot).advance(tick);bank.advance(tick);command.advance(tick)}
     b!=null->{command.advance(tick);bank.advance(tick);snapshot?.advance(tick)}
     else->{bank.advance(tick);command.advance(tick);snapshot?.advance(tick)}
    }
    refusedAdvance=false
   } catch (error: Throwable) {
    refusedAdvance=true
    throw error
   }
  }
  private fun wordOffset(value:Long):Int {require(value in 0..0xfffcL&&value%4==0L){"DRAM exact aligned word required"};return value.toInt()}
  private fun integer(value:Any?,min:Long=0,max:Long=0xffffffffL):Long {require(value is Byte||value is Short||value is Int||value is Long){"DRAM exact integer required"};return(value as Number).toLong().also{require(it in min..max)}}
  private fun pairs(value:Any?,size:Int,expected:String):List<Pair<Int,Long>> {
   require(value is List<*>&&value.size==size){"DRAM contract pair count differs"}
   val pairs=value.map{item->require(item is Map<*,*>&&item.keys==setOf("offset","value"));integer(item["offset"],0,0xfffc).toInt().also{require(it%4==0)} to integer(item["value"])}
   val raw=ByteBuffer.allocate(size*8).order(ByteOrder.LITTLE_ENDIAN);pairs.forEach{(o,v)->raw.putInt(o);raw.putInt(v.toInt())}
   val actual=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.array()))
   require(actual==expected){"DRAM selected-DAT contract identity differs"};return pairs
  }
  private fun commandTables(value:Any?):Map<Int,List<List<Pair<Int,Long>>>> {
   require(value is Map<*,*>&&value.keys==setOf("0","1","2","3")){"DRAM command bank file shape differs"}
   require(value.values.all{it is String&&it.isNotBlank()}&&value.values.distinct().size==4){"DRAM four distinct command table files required"}
   val bytes=ByteBuffer.allocate(11360).order(ByteOrder.LITTLE_ENDIAN)
   val tables=(0..3).associateWith{bank->
    val document=inputContext.readData(value[bank.toString()]as String)
    require(document.keys==setOf("schema","bank","profile","firmwareSha256","modes")&&integer(document["schema"],1,1)==1L&&integer(document["bank"],bank.toLong(),bank.toLong())==bank.toLong()&&document["profile"]==selectedProfile&&document["firmwareSha256"]==FIRMWARE){"DRAM command table file identity differs"}
    val modes=document["modes"];require(modes is Map<*,*>&&modes.keys==setOf("0","1","2","3","4"))
    (0..4).map{mode->val words=modes[mode.toString()];require(words is List<*>&&words.size==71)
     words.map{item->require(item is Map<*,*>&&item.keys==setOf("offset","value"));val offset=integer(item["offset"],0,0xfffc).toInt();require(offset%4==0);val word=integer(item["value"]);bytes.putInt(offset);bytes.putInt(word.toInt());offset to word}
    }
   }
   val sha=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
   require(sha==COMMANDS){"DRAM selected-DAT command tables differ"};return tables
  }
  private fun commandPlan(value:Any?):List<DramCommandBank.Request> {
   require(value is List<*>&&value.size<=1024){"DRAM finite command plan required"}
   return value.map{item->require(item is Map<*,*>&&item.keys==setOf("mode","responseTicks","responseWord","counterWord"));DramCommandBank.Request(integer(item["mode"],0,4).toInt(),integer(item["responseTicks"],1,Long.MAX_VALUE),item["responseWord"]?.let{integer(it)},item["counterWord"]?.let{integer(it)})}
  }
  private fun parse(data:Map<String,Any?>):Map<Int,DramPairBank> {
   require(data.keys==setOf("schema","profile","firmwareSha256","programmingWords","configurationWords","banks")+(if(commandsEnabled)setOf("commandTableFiles")else emptySet()))
   require(integer(data["schema"],1,1)==1L&&data["profile"]==selectedProfile&&data["firmwareSha256"]==FIRMWARE)
   val programming=pairs(data["programmingWords"],686,PROGRAMMING);val configuration=pairs(data["configurationWords"],28,CONFIGURATION)
   val commands=if(commandsEnabled)commandTables(data["commandTableFiles"])else emptyMap()
   if(commandsEnabled){val oldOffsets=configuration.map{it.first}.toSet()+setOf(0x7c18,0x7040,0x7044,0x7360,0x7364);require(commands.values.flatten().flatten().none{it.first in oldOffsets})}
   val supplied=data["banks"];require(supplied is Map<*,*>&&supplied.isNotEmpty()&&supplied.size<=4&&supplied.keys.all{it is String&&it in listOf("0","1","2","3")}){"DRAM explicit bank selection required"}
   val parsed=supplied.entries.sortedBy{it.key.toString().toInt()}.associate{(key,value)->
    require(value is Map<*,*>&&value.keys-"postAckPhyControl"-"postAckReplayPollWords"-"commandPlanMode"==setOf("responseTicks","initialController7044","initialController7364","responses")+(if(commandsEnabled)setOf("commandPlans")else emptySet())+(if(snapshotsEnabled)setOf("snapshotResponse")else emptySet()))
    require("postAckReplayPollWords" !in value || value["postAckPhyControl"] in DramPhyReplay.PROFILES)
    val repeatableCommandMode = if("commandPlanMode" in value) {
     require(commandsEnabled && value["commandPlanMode"] == DramCommandBank.REPEATABLE_ANY_MODE) { "Unknown DRAM commandPlanMode" }
     true
    } else false
    val delays=value["responseTicks"];require(delays is List<*>&&delays.size==5);val times=delays.map{integer(it,1,Long.MAX_VALUE)}.toLongArray()
    val rawResponses=value["responses"];require(rawResponses is Map<*,*>&&rawResponses.keys==setOf("0","1","2","3","4"))
    val responses=(0..4).associateWith {stage->
     val words=rawResponses[stage.toString()];if(words==null)null else {
      val fields=DramPairBank.fieldLayout(stage);require(words is List<*>&&words.size==fields.size){"DRAM response shape differs"}
      words.mapIndexed{i,w->integer(w).also{require(it and fields[i].mask.inv()==0L){"DRAM sample exceeds qualified field mask"}}}.toLongArray()
     }
    }
    val index=key.toString().toInt()
    if("postAckPhyControl" in value) {
     require(index in 1..3&&snapshotsEnabled&&value["snapshotResponse"]!=null)
     when(value["postAckPhyControl"]) {
      "clear-bit0-once-v1"->{require(index==1);postAckPhyBanks.add(index)}
      DramPhyReplay.PROFILE, DramPhyReplay.REPEATABLE_PROFILE -> {
       val polls=if("postAckReplayPollWords" in value) {
        val words=value["postAckReplayPollWords"];require(words is List<*>&&words.size in DramPhyReplay.pollCounts)
        words.map{integer(it)}
       }else emptyList()
       replays[index]=DramPhyReplay(programming,configuration,polls,repeatable=value["postAckPhyControl"]==DramPhyReplay.REPEATABLE_PROFILE)
      }
      else->error("Unknown DRAM post-ACK PHY selection")
     }
    }
    if(commandsEnabled)commandBanks[index]=DramCommandBank(commands.getValue(index),commandPlan(value["commandPlans"]),index,repeatableAnyMode=repeatableCommandMode || repeatableCommandModes)
    if(snapshotsEnabled&&value["snapshotResponse"]!=null) {
     require(index in 1..3){"DRAM bank0 snapshot prerequisites remain unqualified"}
     val selected=value["snapshotResponse"];require(selected is Map<*,*>&&selected.keys==setOf("responseTicks","pendingStatusWord","responseWord")){"DRAM explicit snapshot input shape differs"}
     snapshots[index]=DramSnapshotBank(index,integer(selected["responseTicks"],1,Long.MAX_VALUE),integer(selected["pendingStatusWord"]),selected["responseWord"]?.let{integer(it)})
    }
    index to DramPairBank(programming,configuration,times,integer(value["initialController7044"]),integer(value["initialController7364"]),DramPairBank.ResponseSource{stage->responses[stage]})
   }
   if(snapshotsEnabled)require(snapshots.isNotEmpty()){"DRAM snapshot profile requires an explicit selected bank input"}
   return parsed
  }
  override fun start(){check(!closed){"DRAM controller closed"}}
  override fun additionalMmioPorts()=names.toList()
  override fun close(){closed=true;coldStart?.close();banks.values.forEach{it.close()};commandBanks.values.forEach{it.close()};snapshots.values.forEach{it.close()}}
  private fun bankObservation(index:Int,bank:DramPairBank):Map<String,Any?> {
   val observed=linkedMapOf<String,Any?>();observed.putAll(bank.state())
   if(commandsEnabled)observed["commands"]=commandBanks.getValue(index).state()
   snapshots[index]?.let{observed["snapshot"]=it.state()}
   replays[index]?.let{observed["postAckReplay"]=it.observation(bank)}
   return observed
  }
  private fun syncAllClocks() {
   if (closed || refusedAdvance) return
   val now = inputContext.clock.ticks()
   if (now <= 0L) return
   for ((index, bank) in banks) {
    bank.syncClock(now)
    commandBanks[index]?.syncClock(now)
    snapshots[index]?.syncClock(now)
   }
  }
  override fun state():Map<String,Any?> {
   syncAllClocks()
   val observed:Map<String,Map<String,Any?>> = banks.entries.associate{(index,bank)->index.toString() to bankObservation(index,bank)}
   val basis="Selected GFX100S 2.13 initialization/configuration table identities. Supplied reset words, mask-limited samples, five deadlines, retention and admission rules are explicit functional HYP. Response delivery is not physical training completion; no DRAM capacity/physical reset/clock/IRQ or menu claim. Unknown words and post-ACK status effects refuse."+(if(commandsEnabled)" Finite 71-word command plans, counter input and whole-word responses/deadlines are additional explicit HYP; guest zero observation is separate from sample delivery."else "")+(if(snapshotsEnabled)" Snapshot3 configuration writes/request7080/poll7090 bit0/external ACK are a finite explicit HYP; init ACK is not successful physical training."else "")+(if(coldStartEnabled || coldStartChannel0)" Factory cold-start profile models DRAM0 pre-init/commit, CTL0 4-stage init FSM, PHY0 686 words, DDR arbiter/bridge, and Stage-1 flag."else "")
   val baseScalars = if (coldStartChannel0) super.state() else super.state() - "coldStartChannel0"
   return baseScalars+mapOf("enabled" to enabled,"banks" to observed,"hardwareGeometry" to DramHardwareGeometry.toStateMap(),"basis" to basis)+(coldStart?.let{mapOf("coldStart" to it.state())}?:emptyMap())
  }

  override fun saveSnapshot():Map<String,Any?> {
   check(!closed){"DRAM controller closed"}
   syncAllClocks()
   return linkedMapOf<String,Any?>(
    "schema" to snapshotSchemaVersion,
    "componentId" to id,
    "scalars" to if(coldStartChannel0) saveScalarValues() else saveScalarValues() - "coldStartChannel0",
    "enabled" to enabled,
    "banks" to banks.entries.associate{(bankIndex,pairBank)->
     val replay=replays[bankIndex]
     bankIndex.toString() to linkedMapOf(
      "pair" to pairBank.saveSnapshot(),
      "command" to commandBanks[bankIndex]?.saveSnapshot(),
      "snapshot" to snapshots[bankIndex]?.saveSnapshot(),
     ) + (if(replay!=null) mapOf("postAckReplayCursor" to replay.cursor) else emptyMap()) +
      (if(replay?.pollWords?.isNotEmpty()==true) mapOf("postAckReplayPollWords" to replay.pollWords) else emptyMap()) +
      (if(replay?.repeatable==true) mapOf("postAckReplayRepeatable" to true) else emptyMap())
    },
   ).apply {
    if(coldStart!=null) put("coldStart",coldStart.saveSnapshot())
   }
  }

  override fun restoreSnapshot(data:Map<String,Any?>) {
   check(!closed){"DRAM controller closed"}
   val expectedKeys=if(coldStart!=null) COLDSTART_SNAPSHOT_KEYS else SNAPSHOT_KEYS
   require(data.keys==expectedKeys){"Unexpected snapshot keys for $id"}
   require(snapshotInt(data["schema"],"schema")==snapshotSchemaVersion){"Unsupported snapshot schema for $id"}
   require(data["componentId"]==id){"Snapshot componentId mismatch for $id"}
   val scalarsRaw=snapshotMap(data["scalars"],"scalars")
   val legacyScalarKeys=setOf("profile","inputFile")
   val preColdStartScalarKeys=setOf("profile","inputFile","repeatableCommandModes")
   val fullScalarKeys=DESCRIPTOR.settings.map{setting->setting.key}.toSet()
   require(scalarsRaw.keys==fullScalarKeys || scalarsRaw.keys==preColdStartScalarKeys || scalarsRaw.keys==legacyScalarKeys){"Scalar setting keys differ in snapshot for $id"}
   val savedRepeatableCmd=if("repeatableCommandModes" in scalarsRaw) snapshotBoolean(scalarsRaw["repeatableCommandModes"],"scalars.repeatableCommandModes") else false
   require(!savedRepeatableCmd || repeatableCommandModes){"DRAM repeatableCommandModes cannot be downgraded on restore"}
   val savedColdStartCh0=if("coldStartChannel0" in scalarsRaw) snapshotBoolean(scalarsRaw["coldStartChannel0"],"scalars.coldStartChannel0") else false
   require(savedColdStartCh0 == coldStartChannel0){"DRAM coldStartChannel0 differs in snapshot for $id"}
   val effectiveScalars=LinkedHashMap<String,Any?>(scalarsRaw.mapKeys{it.key as String}).apply {
    put("repeatableCommandModes", repeatableCommandModes)
    put("coldStartChannel0", coldStartChannel0)
   }
   val previousValues=values
   val validatedPairs=linkedMapOf<Int,DramPairBank.ValidatedSnapshot>()
   val validatedCommands=linkedMapOf<Int,DramCommandBank.ValidatedSnapshot>()
   val validatedSnapshots=linkedMapOf<Int,DramSnapshotBank.ValidatedSnapshot>()
   val replayCursors=mutableMapOf<Int,Int>()
   val validatedColdStart:DramColdStartController.ValidatedSnapshot?
   try {
    restoreScalarValues(effectiveScalars,DESCRIPTOR,if(repeatableCommandModes && !savedRepeatableCmd) setOf("repeatableCommandModes") else emptySet())
    require(snapshotBoolean(data["enabled"],"enabled")==enabled){"Snapshot enabled flag differs for $id"}
    validatedColdStart=coldStart?.validateSnapshot(data["coldStart"])
    val banksRaw=snapshotMap(data["banks"],"banks")
    require(banksRaw.keys==banks.keys.map{bankIndex->bankIndex.toString()}.toSet()){"DRAM snapshot bank set differs from live configuration"}
    for((bankIndex,pairBank)in banks) {
     val bankEntry=snapshotMap(banksRaw[bankIndex.toString()],"banks.$bankIndex")
     val replay=replays[bankIndex]
     var replayCursor=0
     val savedReplay=bankEntry.containsKey("postAckReplayCursor")
     require(!savedReplay || replay!=null)
     val savedPolls="postAckReplayPollWords" in bankEntry
     if(savedPolls) {
      require(savedReplay)
      val words=bankEntry["postAckReplayPollWords"];require(words is List<*>&&words.size in DramPhyReplay.pollCounts)
      require(words.map{integer(it)}==replay!!.pollWords.take(words.size))
     }
     val savedRepeatable=if("postAckReplayRepeatable" in bankEntry) {
      val flag=bankEntry["postAckReplayRepeatable"];require(flag is Boolean && flag && savedReplay)
      true
     } else false
     require(!savedRepeatable || replay?.repeatable==true)
     require(bankEntry.keys-"postAckReplayCursor"-"postAckReplayPollWords"-"postAckReplayRepeatable"==BANK_ENTRY_KEYS){"Unexpected DRAM bank snapshot entry keys for bank $bankIndex"}
     val commandBank=commandBanks[bankIndex]
     val snapshotBank=snapshots[bankIndex]
     require((commandBank!=null)==(bankEntry["command"]!=null)){"DRAM command bank presence mismatch at bank $bankIndex"}
     require((snapshotBank!=null)==(bankEntry["snapshot"]!=null)){"DRAM snapshot bank presence mismatch at bank $bankIndex"}
     val validatedPair=pairBank.validateSnapshot(bankEntry["pair"])
     val validatedCommand=commandBank?.validateSnapshot(bankEntry["command"])
     val validatedSnapshot=snapshotBank?.validateSnapshot(bankEntry["snapshot"])
     if(replay!=null) {
      val cleared=pairBank.hasClearedControl(validatedPair) && validatedSnapshot?.acknowledgedAt!=null
      replayCursor=if(savedReplay)snapshotInt(bankEntry["postAckReplayCursor"],"postAckReplayCursor") else if(cleared)1 else 0
      require(replayCursor in 0..replay.capacity && (replay.repeatable || (replayCursor>0)==cleared))
      if(!savedPolls)require(replayCursor<=735)
      if((bankEntry["postAckReplayPollWords"] as? List<*>)?.size==4)require(replayCursor<=742)
      replayCursors[bankIndex]=replayCursor
     }
     val pendingCount=(if(validatedPair.hasPendingDeadline())1 else 0)+
      (if(validatedCommand?.hasPendingDeadline()==true)1 else 0)+
      (if(validatedSnapshot?.hasPendingDeadline()==true)1 else 0)
     require(pendingCount<=1){"DRAM overlapping requests in snapshot at bank $bankIndex"}
     val replayFinished=replay?.allowsCommandAt(replayCursor)==true && validatedSnapshot?.acknowledgedAt!=null
     if(validatedCommand?.isActive()==true) {
      require(!validatedPair.hasPendingDeadline()&&(validatedSnapshot?.isActive()!=true||replayFinished)) {
       "Active DRAM command conflicts with pair/snapshot state at bank $bankIndex"
      }
     }
     if(validatedSnapshot?.isActive()==true) {
      require(!validatedPair.hasPendingDeadline()&&(validatedCommand?.isActive()!=true||replayFinished)&&validatedPair.hasAcknowledged()&&validatedCommand?.hasObservedZero()==true) {
       "Active DRAM snapshot requires completed init ACK and command zero at bank $bankIndex"
      }
     }
     validatedPairs[bankIndex]=validatedPair
     if(validatedCommand!=null)validatedCommands[bankIndex]=validatedCommand
     if(validatedSnapshot!=null)validatedSnapshots[bankIndex]=validatedSnapshot
    }
   } catch(failure:Throwable) {
    values=previousValues
    throw failure
   }
   if(coldStart!=null && validatedColdStart!=null) coldStart.applySnapshot(validatedColdStart)
   for((bankIndex,pairBank)in banks) {
    pairBank.applySnapshot(validatedPairs.getValue(bankIndex))
    validatedCommands[bankIndex]?.let{validatedCommand->commandBanks.getValue(bankIndex).applySnapshot(validatedCommand)}
    validatedSnapshots[bankIndex]?.let{validatedSnapshot->snapshots.getValue(bankIndex).applySnapshot(validatedSnapshot)}
   }
   replayCursors.forEach{(index,cursor)->replays.getValue(index).restore(cursor)}
  }
 }
}
