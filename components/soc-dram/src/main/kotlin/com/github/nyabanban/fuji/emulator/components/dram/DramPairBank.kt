package com.github.nyabanban.fuji.emulator.components.dram

/** Narrow experimental functional controller/PHY pair; physical training and clock units remain unknown. */
class DramPairBank(
 programming:List<Pair<Int,Long>>,
 configuration:List<Pair<Int,Long>>,
 delays:LongArray,
 initialController7044:Long,
 initialController7364:Long,
 source:ResponseSource?
) {
 fun interface ResponseSource { fun sample(stage:Int):LongArray? }
 data class Field(val controller:Boolean,val offset:Int,val mask:Long)
 companion object {
  private fun phy(offset:Int,mask:Long)=Field(false,offset,mask)
  private fun controller(offset:Int,mask:Long)=Field(true,offset,mask)
  fun fieldLayout(stage:Int):List<Field> {require(stage in fields.indices);return fields[stage].toList()}
  private val fields:List<List<Field>> = listOf(
   listOf(controller(0x7c18,0x40000001),phy(0x4670,0x30000),phy(0x4674,0x30003),phy(0x4678,0x30003)),
   listOf(controller(0x7044,1)),listOf(controller(0x7044,0x10)),
   listOf(phy(0x2110,1),phy(0x4450,15),phy(0x4624,15),phy(0x4480,0x7ff00),phy(0x4484,0x7ff07ff),phy(0x4488,0x7ff07ff),phy(0x448c,0x7ff),phy(0x4660,0x7ff0000),phy(0x4664,0x7ff),phy(0x444c,0x7ff07ff),phy(0x4620,0x7ff07ff),phy(0x40dc,0x1300),phy(0x42dc,0x1300),phy(0x40ec,0x180),phy(0x42ec,0x180),phy(0x40f8,0xff0000),phy(0x42f8,0xff0000))+listOf(0x4138,0x413c,0x4140,0x4144,0x4338,0x433c,0x4340,0x4344).map{phy(it,0x7ff07ff)}+listOf(phy(0x4148,0x7ff),phy(0x4348,0x7ff)),
   listOf(controller(0x7364,1))
  )
  private val SPECIAL_CONTROLLER_OFFSETS=setOf(0x7c18,0x7040,0x7044,0x7360,0x7364)
  private val SNAPSHOT_KEYS=setOf(
   "responseTicks","programmedWordCount","lastResponseStage","pendingStage","deadline",
   "responseDeliveredAt","responseDeliveryCount","completedWriteCount","completedReadCount",
   "clock","acknowledged","phyWords","controllerWords",
  )
  private fun snapshotLong(value:Any?,field:String,minimum:Long=Long.MIN_VALUE,maximum:Long=Long.MAX_VALUE):Long {
   require(value is Byte||value is Short||value is Int||value is Long){"Snapshot field $field requires integer"}
   val converted=(value as? Number)?.toLong()?:throw IllegalArgumentException("Snapshot field $field requires integer")
   require(converted in minimum..maximum){"Snapshot field $field out of range"}
   return converted
  }
  private fun snapshotInt(value:Any?,field:String,minimum:Int=Int.MIN_VALUE,maximum:Int=Int.MAX_VALUE):Int =
   snapshotLong(value,field,minimum.toLong(),maximum.toLong()).toInt()
  private fun snapshotBoolean(value:Any?,field:String):Boolean =
   value as? Boolean?:throw IllegalArgumentException("Snapshot field $field requires boolean")
  private fun snapshotList(value:Any?,field:String):List<*> =
   value as? List<*>?:throw IllegalArgumentException("Snapshot field $field requires list")
  private fun snapshotMap(value:Any?,field:String):Map<*,*> {
   val map=value as? Map<*,*>?:throw IllegalArgumentException("Snapshot field $field requires map")
   require(map.keys.all{key->key is String}){"Snapshot field $field requires string keys"}
   return map
  }
 }
 internal class ValidatedSnapshot(
  val phyWords:LongArray,
  val phyWritten:BooleanArray,
  val commonWords:LongArray,
  val commonWritten:BooleanArray,
  val programmed:Int,
  val lastResponse:Int,
  val pending:Int,
  val due:Long?,
  val deliveredAt:Long?,
  val deliveries:Long,
  val writes:Long,
  val reads:Long,
  val clock:Long,
  val acknowledged:Boolean,
 ) {
  fun hasPendingDeadline():Boolean = due!=null
  fun hasAcknowledged():Boolean = acknowledged
 }
 fun responseFields(stage:Int):List<Field> = fieldLayout(stage)
 private val timing=delays.copyOf()
 private var input=source
 private val program=programming.sortedBy{it.first}.toList()
 private val offsets=program.map{it.first}.toIntArray()
 private val phyWords=LongArray(program.size)
 private val phyWritten=BooleanArray(program.size)
 private val allowed=configuration.groupBy({it.first},{it.second}).mapValues{it.value.toSet()}
 private val commonOffsets=(allowed.keys+listOf(0x7c18,0x7040,0x7044,0x7360,0x7364)).distinct().sorted().toIntArray()
 private val commonWords=LongArray(commonOffsets.size)
 private val commonWritten=BooleanArray(commonOffsets.size)
 private var programmed=0
 private var lastResponse=-1
 private var pending=-1
 private var due:Long?=null
 private var deliveredAt:Long?=null
 private var deliveries=0L
 private var writes=0L
 private var reads=0L
 private var clock=0L
 private var closed=false
 private var acknowledged=false
 init {
  require(program.size==686&&offsets.distinct().size==686&&offsets.all{it in 0..0xfffc&&it%4==0})
  require(program.all{it.second in 0..0xffffffffL})
  require(timing.size==5&&timing.all{it>0})
  require(allowed.isNotEmpty()&&allowed.all{(offset,values)->offset in 0..0xfffc&&offset%4==0&&values.all{it in 0..0xffffffffL}})
  require(initialController7044 in 0..0xffffffffL&&initialController7364 in 0..0xffffffffL)
  require(fields.flatten().filter{!it.controller}.all{offsets.binarySearch(it.offset)>=0})
  put(true,0x7044,initialController7044);put(true,0x7364,initialController7364)
 }
 private fun live(tick:Long){check(!closed){"DRAM pair closed"};require(tick>=clock){"DRAM pair clock moved backwards"}}
 private fun index(controller:Boolean,offset:Int):Int {require(offset in 0..0xfffc&&offset%4==0){"unqualified DRAM offset"};val i=(if(controller)commonOffsets else offsets).binarySearch(offset);require(i>=0){"unqualified DRAM word"};return i}
 private fun known(controller:Boolean,offset:Int):Long {val i=index(controller,offset);check((if(controller)commonWritten else phyWritten)[i]){"DRAM word has no supplied/programmed value"};return (if(controller)commonWords else phyWords)[i]}
 private fun put(controller:Boolean,offset:Int,value:Long){val i=index(controller,offset);(if(controller)commonWords else phyWords)[i]=value;(if(controller)commonWritten else phyWritten)[i]=true}
 private fun patch(field:Field,bits:Long){put(field.controller,field.offset,(known(field.controller,field.offset)and field.mask.inv())or bits)}
 // A cleanup ACK may also set this flag; this is protocol history, not successful physical training.
 internal fun hasAcknowledgedInitialization():Boolean {check(!closed);return acknowledged}
 internal fun retainedWord(controller:Boolean,offset:Int):Long = known(controller,offset)
 internal fun hasClearedControl(state:ValidatedSnapshot):Boolean =
  state.acknowledged && state.phyWords[offsets.binarySearch(0x2000)]==0xb00L
 fun preflightClock(tick:Long){live(tick)}
 fun syncClock(tick:Long){if(!closed && tick>clock && (due==null || tick<due!!)) clock=tick}
 fun nextDeadline():Long? {check(!closed);return due}
 fun advance(tick:Long) {
  live(tick);val at=due
  if(at==null||tick<at){clock=tick;return}
  val values=checkNotNull(input?.sample(pending)){"DRAM response input missing; request remains pending"}.copyOf()
  val changes=fields[pending]
  require(values.size==changes.size){"DRAM response shape differs"}
  require(deliveries<Long.MAX_VALUE)
  for(i in values.indices){require(values[i] in 0..0xffffffffL&&values[i]and changes[i].mask.inv()==0L){"DRAM response field exceeds qualified mask"};known(changes[i].controller,changes[i].offset)}
  // Preflight the entire sample before any status/calibration/ownership change.
  for(i in values.indices)patch(changes[i],values[i])
  lastResponse=pending;pending=-1;due=null;deliveredAt=at;deliveries++;clock=tick
 }
 fun read(controller:Boolean,offset:Int,size:Int,tick:Long):Long {
  live(tick);require(size==4);index(controller,offset)
  require(!(controller&&offset==0x7364&&acknowledged)){"post-ack controller status effects remain unqualified"}
  require(reads<Long.MAX_VALUE);advance(tick);val value=known(controller,offset);reads++;return value
 }
 private fun readyFor(stage:Int) {
  check(pending==-1){"DRAM request already pending"};check(lastResponse==stage-1){"DRAM request order unqualified"}
  when(stage) {
   0->check(programmed==program.size){"DRAM PHY programming incomplete"}
   1->check(fields[0].all{(known(it.controller,it.offset)and it.mask)==if(it.controller)1L else it.mask}){"prior DRAM input did not satisfy the guest's initialization predicates"}
   2->check(known(true,0x7044)and 1L==0L){"first training status remains busy"}
   3->check(known(true,0x7044)and 0x10L==0L){"second training status remains busy"}
   4->check(known(false,0x2110)and 1L==1L){"PHY status remains not-ready"}
  }
 }
 fun clearPhyControl(size:Int,value:Long,tick:Long) {
  live(tick)
  require(size==4 && value==0xb00L)
  check(acknowledged && lastResponse==4 && pending==-1 && known(false,0x2000)==0xb01L && writes<Long.MAX_VALUE)
  put(false,0x2000,value)
  writes++
  clock=tick
 }
 fun write(controller:Boolean,offset:Int,size:Int,value:Long,tick:Long) {
  live(tick);require(size==4&&value in 0..0xffffffffL);val i=index(controller,offset);check(writes<Long.MAX_VALUE)
  val stage=when {
   controller&&offset==0x7c18->{require(value==0x40000000L);0}
   controller&&offset==0x7040->{require(value==3L||value==0x10L);if(value==3L)1 else 2}
   !controller&&offset==0x2000&&value==0xb01L->3
   controller&&offset==0x7360->{require(value==0xff00c00dL);4}
   else->-1
  }
  if(stage>=0) {
   readyFor(stage);val at=Math.addExact(tick,timing[stage]);require(at>tick)
   when(stage){1,2->known(true,0x7044);3->known(false,0x2110);4->known(true,0x7364)}
   // No implicit time advancement in writes: the owner must deliver due events first.
   put(controller,offset,value)
   when(stage){1->patch(Field(true,0x7044,1),1);2->patch(Field(true,0x7044,0x10),0x10);3->patch(Field(false,0x2110,1),0);4->patch(Field(true,0x7364,1),0)}
   pending=stage;due=at;deliveredAt=null
  } else if(!controller) {
   check(lastResponse==-1&&pending==-1){"PHY reprogramming after initialization is unqualified"}
   require(value==program[i].second){"PHY programming value outside selected DAT contract"}
   if(!phyWritten[i])programmed++
   put(false,offset,value)
  } else {
   check(pending==-1&&lastResponse>=0){"controller configuration requires delivered initialization input"}
   if(offset==0x7364){require(value==1L&&lastResponse==4&&!acknowledged&&known(true,offset)and 1L==1L);acknowledged=true}
   else {require(value in (allowed[offset]?:emptySet())){"controller configuration outside selected DAT contract"};put(true,offset,value)}
  }
  writes++;clock=tick
 }
 fun close(){closed=true;input=null}
 fun state():Map<String,Any?> = mapOf("closed" to closed,"programmedWordCount" to programmed,"lastResponseStage" to lastResponse,"pendingStage" to pending.takeIf{it>=0},"deadline" to due,"responseDeliveredAt" to deliveredAt,"responseDeliveryCount" to deliveries,"completedWriteCount" to writes,"completedReadCount" to reads,"clock" to clock,"acknowledged" to acknowledged,"phyWords" to offsets.indices.associate{offsets[it].toString(16) to phyWords[it].takeIf{_->phyWritten[it]}},"controllerWords" to commonOffsets.indices.associate{commonOffsets[it].toString(16) to commonWords[it].takeIf{_->commonWritten[it]}})

 internal fun saveSnapshot():Map<String,Any?> {
  check(!closed){"DRAM pair closed"}
  return linkedMapOf(
   "responseTicks" to timing.toList(),
   "programmedWordCount" to programmed,
   "lastResponseStage" to lastResponse,
   "pendingStage" to pending,
   "deadline" to due,
   "responseDeliveredAt" to deliveredAt,
   "responseDeliveryCount" to deliveries,
   "completedWriteCount" to writes,
   "completedReadCount" to reads,
   "clock" to clock,
   "acknowledged" to acknowledged,
   "phyWords" to offsets.indices.associate{slot->offsets[slot].toString(16) to (if(phyWritten[slot])phyWords[slot] else null)},
   "controllerWords" to commonOffsets.indices.associate{slot->commonOffsets[slot].toString(16) to (if(commonWritten[slot])commonWords[slot] else null)},
  )
 }

 internal fun validateSnapshot(raw:Any?):ValidatedSnapshot {
  check(!closed){"DRAM pair closed"}
  val section=snapshotMap(raw,"pair")
  require(section.keys==SNAPSHOT_KEYS){"Unexpected DRAM pair snapshot keys"}
  val savedTiming=snapshotList(section["responseTicks"],"pair.responseTicks")
  require(savedTiming.size==timing.size){"DRAM pair responseTicks length mismatch"}
  val parsedTiming=LongArray(timing.size){stage->snapshotLong(savedTiming[stage],"pair.responseTicks[$stage]",1L,Long.MAX_VALUE)}
  require(parsedTiming.contentEquals(timing)){"DRAM pair responseTicks differ from live configuration"}
  val nextProgrammed=snapshotInt(section["programmedWordCount"],"pair.programmedWordCount",0,offsets.size)
  val nextLastResponse=snapshotInt(section["lastResponseStage"],"pair.lastResponseStage",-1,4)
  val nextPending=snapshotInt(section["pendingStage"],"pair.pendingStage",-1,4)
  val nextDue=section["deadline"]?.let{rawDeadline->snapshotLong(rawDeadline,"pair.deadline",1L,Long.MAX_VALUE)}
  val nextDeliveredAt=section["responseDeliveredAt"]?.let{rawDelivered->snapshotLong(rawDelivered,"pair.responseDeliveredAt",1L,Long.MAX_VALUE)}
  val nextDeliveries=snapshotLong(section["responseDeliveryCount"],"pair.responseDeliveryCount",0L,Long.MAX_VALUE)
  val nextWrites=snapshotLong(section["completedWriteCount"],"pair.completedWriteCount",0L,Long.MAX_VALUE)
  val nextReads=snapshotLong(section["completedReadCount"],"pair.completedReadCount",0L,Long.MAX_VALUE)
  val nextClock=snapshotLong(section["clock"],"pair.clock",0L,Long.MAX_VALUE)
  val nextAcknowledged=snapshotBoolean(section["acknowledged"],"pair.acknowledged")
  val phyMap=snapshotMap(section["phyWords"],"pair.phyWords")
  require(phyMap.keys==offsets.map{offset->offset.toString(16)}.toSet()){"DRAM pair phyWords keys mismatch"}
  val nextPhyWords=LongArray(offsets.size)
  val nextPhyWritten=BooleanArray(offsets.size)
  for(slot in offsets.indices) {
   val hexKey=offsets[slot].toString(16)
   val rawWord=phyMap[hexKey]
   if(rawWord!=null) {
    val wordValue=snapshotLong(rawWord,"pair.phyWords[$hexKey]",0L,0xffffffffL)
    if(nextLastResponse==-1&&nextPending==-1) {
     require(wordValue==program[slot].second){"DRAM pair PHY word at $hexKey differs from pre-init contract"}
    }
    nextPhyWords[slot]=wordValue
    nextPhyWritten[slot]=true
   }
  }
  require(nextProgrammed==nextPhyWritten.count{writtenFlag->writtenFlag}){"DRAM pair programmedWordCount disagrees with phyWords"}
  if(nextLastResponse>=0||nextPending>=0) {
   require(nextProgrammed==offsets.size){"DRAM pair initialization stage requires complete PHY programming"}
  }
  val controllerMap=snapshotMap(section["controllerWords"],"pair.controllerWords")
  require(controllerMap.keys==commonOffsets.map{offset->offset.toString(16)}.toSet()){"DRAM pair controllerWords keys mismatch"}
  val nextCommonWords=LongArray(commonOffsets.size)
  val nextCommonWritten=BooleanArray(commonOffsets.size)
  for(slot in commonOffsets.indices) {
   val offset=commonOffsets[slot]
   val hexKey=offset.toString(16)
   val rawWord=controllerMap[hexKey]
   if(rawWord!=null) {
    val wordValue=snapshotLong(rawWord,"pair.controllerWords[$hexKey]",0L,0xffffffffL)
    if(offset !in SPECIAL_CONTROLLER_OFFSETS) {
     require(nextLastResponse>=0&&wordValue in allowed.getValue(offset)){"DRAM pair controller word at $hexKey outside selected contract"}
    }
    nextCommonWords[slot]=wordValue
    nextCommonWritten[slot]=true
   } else {
    require(offset!=0x7044&&offset!=0x7364){"DRAM pair initial status word $hexKey cannot be absent"}
   }
  }
  if(nextPending==-1) {
   require(nextDue==null){"Idle DRAM pair cannot carry a deadline"}
   if(nextLastResponse==-1) {
    require(nextDeliveries==0L&&nextDeliveredAt==null){"Pre-stage DRAM pair cannot have delivered responses"}
   } else {
    require(nextDeliveries==(nextLastResponse+1).toLong()&&nextDeliveredAt!=null){"Completed DRAM stage disagrees with delivery metadata"}
   }
  } else {
   require(nextPending==nextLastResponse+1&&nextDue!=null&&nextDeliveredAt==null&&nextDeliveries==(nextLastResponse+1).toLong()) {
    "Pending DRAM stage metadata inconsistent"
   }
  }
  if(nextAcknowledged) {
   val status7364Slot=commonOffsets.binarySearch(0x7364)
   require(nextLastResponse==4&&nextPending==-1&&(nextCommonWords[status7364Slot]and 1L)==1L) {
    "Acknowledged DRAM pair requires completed stage 4 and status bit0"
   }
  }
  return ValidatedSnapshot(
   phyWords=nextPhyWords,
   phyWritten=nextPhyWritten,
   commonWords=nextCommonWords,
   commonWritten=nextCommonWritten,
   programmed=nextProgrammed,
   lastResponse=nextLastResponse,
   pending=nextPending,
   due=nextDue,
   deliveredAt=nextDeliveredAt,
   deliveries=nextDeliveries,
   writes=nextWrites,
   reads=nextReads,
   clock=nextClock,
   acknowledged=nextAcknowledged,
  )
 }

 internal fun applySnapshot(validated:ValidatedSnapshot) {
  check(!closed){"DRAM pair closed"}
  validated.phyWords.copyInto(phyWords)
  validated.phyWritten.copyInto(phyWritten)
  validated.commonWords.copyInto(commonWords)
  validated.commonWritten.copyInto(commonWritten)
  programmed=validated.programmed
  lastResponse=validated.lastResponse
  pending=validated.pending
  due=validated.due
  deliveredAt=validated.deliveredAt
  deliveries=validated.deliveries
  writes=validated.writes
  reads=validated.reads
  clock=validated.clock
  acknowledged=validated.acknowledged
 }
}
