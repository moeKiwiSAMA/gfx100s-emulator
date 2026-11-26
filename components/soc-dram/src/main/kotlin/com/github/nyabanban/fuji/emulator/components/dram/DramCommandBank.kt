package com.github.nyabanban.fuji.emulator.components.dram

/** Finite functional HYP input plan; delivery/zero observation is not physical completion. */
class DramCommandBank(
 tables:List<List<Pair<Int,Long>>>,
 requests:List<Request>,
 private val bank:Int,
 val repeatableAnyMode:Boolean=false,
) {
 data class Request(val mode:Int,val responseTicks:Long,val responseWord:Long?,val counterWord:Long?=null)
 companion object {
  const val REPEATABLE_ANY_MODE="any-qualified-mode-repeatable-v1"
  private const val FULL_MODES_MASK=0x1f
  private const val TARGETED_MODES_MASK=0x60
  private const val ALL_REPEATABLE_MODES_MASK=FULL_MODES_MASK or TARGETED_MODES_MASK
  /** DAT 0x011fb654 targeted master-#14 QoS update tables (0x015b15bc normal, 0x015b15e0 movie/high-bw + 0x015b15cc trigger). */
  private val TARGETED_OFFSETS=listOf(
   intArrayOf(0x5038,0x5438,0x5384),
   intArrayOf(0x5038,0x5438,0x5384),
  )
  private val TARGETED_WORDS=listOf(
   longArrayOf(0x01004100L,0x00000081L,1L),
   longArrayOf(0x03204020L,0x00000083L,1L),
  )
  private fun expectedTableLength(mask:Int):Int =
   if((mask and TARGETED_MODES_MASK)!=0 && (mask and FULL_MODES_MASK)==0) 3 else 71
  private val SNAPSHOT_KEYS=setOf(
   "bank","plan","planIndex","acceptedWordCount","specialWritePhase","counterObserved",
   "statusWord","deadline","responseDelivered","responseDeliveredAt","responseDeliveryCount",
   "guestZeroObservationCount","completedWriteCount","completedReadCount","clock",
  )
  private val OPTIONAL_SNAPSHOT_KEYS=setOf("repeatableAnyMode","activeCandidateMask")
  private val PLAN_ITEM_KEYS=setOf("mode","responseTicks","responseWord","counterWord")
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
  val position:Int,
  val cursor:Int,
  val special:Int,
  val counterObserved:Boolean,
  val status:Long?,
  val deadline:Long?,
  val delivered:Boolean,
  val deliveredAt:Long?,
  val deliveries:Long,
  val zeroObservations:Long,
  val writes:Long,
  val reads:Long,
  val clock:Long,
  val activeCandidateMask:Int=0,
 ) {
  fun isActive():Boolean = cursor>0
  fun hasPendingDeadline():Boolean = deadline!=null
  fun hasObservedZero():Boolean = zeroObservations>0
 }
 private val offsets=tables.map{row->row.map{it.first}.toIntArray()}
 private val words=tables.map{row->row.map{it.second}.toLongArray()}
 private val repeatableOffsets=offsets+TARGETED_OFFSETS
 private val repeatableWords=words+TARGETED_WORDS
 private val plan=requests.toList()
 private val domain=(tables.flatten().map{it.first}+listOf(0x5200,0x5204,0x5208)).distinct().sorted().toIntArray()
 private var position=0
 private var cursor=0
 private var activeCandidateMask=0
 private var special=0
 private var counterObserved=false
 private var status:Long?=null
 private var deadline:Long?=null
 private var delivered=false
 private var deliveredAt:Long?=null
 private var deliveries=0L
 private var zeroObservations=0L
 private var writes=0L
 private var reads=0L
 private var clock=0L
 private var closed=false
 init {
  require(bank in 0..3&&tables.size==5&&tables.all{it.size==71})
  require(tables.flatten().all{it.first in 0..0xfffc&&it.first%4==0&&it.second in 0..0xffffffffL})
  require(tables.all{it.last()==(0x5384 to 1L)})
  require(plan.size<=1024&&plan.all{it.mode in 0..4&&it.responseTicks>0&&(it.responseWord==null||it.responseWord in 0..0xffffffffL)&&(it.counterWord==null||(bank==0&&it.counterWord in 0..0xffffffffL))})
  require(!repeatableAnyMode||plan.isNotEmpty()){"Repeatable DRAM command plan requires at least one qualified template request"}
 }
 fun preflightClock(tick:Long){live(tick)}
 fun syncClock(tick:Long){if(!closed && tick>clock && (deadline==null || tick<deadline!!)) clock=tick}
 internal fun hasObservedZero():Boolean {check(!closed);return zeroObservations>0}
 fun isActive():Boolean {check(!closed);return cursor>0}
 fun claims(offset:Int)=domain.binarySearch(offset)>=0
 fun nextDeadline():Long? {check(!closed){"DRAM command bank closed"};return deadline}
 private fun live(tick:Long){check(!closed){"DRAM command bank closed"};require(tick>=clock){"DRAM command clock moved backwards"}}
 private fun access(offset:Int,size:Int,tick:Long){live(tick);require(size==4&&offset%4==0&&claims(offset)){"unqualified DRAM command word/width"}}
 private fun selected():Request = plan.getOrNull(position) ?: if(repeatableAnyMode) plan.last() else error("DRAM command input plan exhausted")
 private fun repeatableCounterWord():Long = plan.lastOrNull{it.counterWord!=null}?.counterWord ?: 0L
 fun advance(tick:Long) {
  live(tick);val at=deadline
  if(at==null||tick<at){clock=tick;return}
  val value=if(repeatableAnyMode && position>=plan.size) selected().responseWord ?: 0L else checkNotNull(selected().responseWord){"DRAM command response input missing; request remains pending"}
  check(deliveries<Long.MAX_VALUE)
  status=value;deadline=null;delivered=true;deliveredAt=at;deliveries++;clock=tick
 }
 private fun isSubmitted():Boolean = deadline!=null || delivered
 fun write(offset:Int,size:Int,value:Long,tick:Long) {
  access(offset,size,tick);require(value in 0..0xffffffffL);check(writes<Long.MAX_VALUE)
  val selected=selected()
  check(!isSubmitted()){"DRAM command awaits a supplied response and guest zero observation"}
  if(special in 1..2) {
   val counter=if(repeatableAnyMode && position>=plan.size) selected.counterWord ?: repeatableCounterWord() else checkNotNull(selected.counterWord)
   val expectedOffset=if(special==1)0x5204 else 0x5208
   val expectedValue=if(counter==0L)0L else counter-1
   require(offset==expectedOffset&&value==expectedValue){"DRAM command special write differs"}
   special++;writes++;clock=tick;return
  }
  val targetLength:Int
  if(position<plan.size) {
   require(offset==offsets[selected.mode][cursor]&&value==words[selected.mode][cursor]){"DRAM command table write differs at word "+cursor}
   targetLength=71
  } else {
   val currentMask=if(cursor==0)ALL_REPEATABLE_MODES_MASK else activeCandidateMask
   var nextMask=0
   for(mode in repeatableOffsets.indices) {
    if(((currentMask ushr mode) and 1)!=0 && cursor<repeatableOffsets[mode].size && repeatableOffsets[mode][cursor]==offset && repeatableWords[mode][cursor]==value) {
     nextMask=nextMask or (1 shl mode)
    }
   }
   require(nextMask!=0){"DRAM command table write differs at word "+cursor}
   activeCandidateMask=nextMask
   targetLength=expectedTableLength(nextMask)
  }
  val at=if(cursor==targetLength-1)Math.addExact(tick,selected.responseTicks)else null
  if(at!=null){require(at>tick);check(deadline==null&&!delivered);check(special==0||special==3)}
  cursor++;writes++;clock=tick
  if(at!=null){status=value;deadline=at;deliveredAt=null}
 }
 fun read(offset:Int,size:Int,tick:Long):Long {
  access(offset,size,tick);check(reads<Long.MAX_VALUE)
  if(offset==0x5200) {
   check(bank==0&&cursor==70&&special==0&&!counterObserved){"unqualified DRAM command counter read"}
   val counter=if(repeatableAnyMode && position>=plan.size) selected().counterWord ?: repeatableCounterWord() else checkNotNull(selected().counterWord){"DRAM command counter input missing"}
   counterObserved=true;special=1;reads++;clock=tick;return counter
  }
  require(offset==0x5384&&isSubmitted()){"unqualified DRAM command read before request or after zero observation"}
  advance(tick);val value=checkNotNull(status)
  if(delivered&&value==0L){check(zeroObservations<Long.MAX_VALUE);zeroObservations++;position++;cursor=0;activeCandidateMask=0;special=0;counterObserved=false;delivered=false}
  reads++;clock=tick;return value
 }
 fun close(){closed=true}
 fun state():Map<String,Any?> = mapOf("closed" to closed,"bank" to bank,"planLength" to plan.size,"planIndex" to position,"mode" to plan.getOrNull(position)?.mode,"acceptedWordCount" to cursor,"specialWritePhase" to special,"counterObserved" to counterObserved,"statusWord" to status,"deadline" to deadline,"responseDeliveredAt" to deliveredAt,"responseDelivered" to delivered,"responseDeliveryCount" to deliveries,"guestZeroObservationCount" to zeroObservations,"completedWriteCount" to writes,"completedReadCount" to reads,"clock" to clock) + (if(repeatableAnyMode) mapOf("commandPlanMode" to REPEATABLE_ANY_MODE) else emptyMap())

 internal fun saveSnapshot():Map<String,Any?> {
  check(!closed){"DRAM command bank closed"}
  val map=linkedMapOf<String,Any?>(
   "bank" to bank,
   "plan" to plan.map{request->
    linkedMapOf(
     "mode" to request.mode,
     "responseTicks" to request.responseTicks,
     "responseWord" to request.responseWord,
     "counterWord" to request.counterWord,
    )
   },
   "planIndex" to position,
   "acceptedWordCount" to cursor,
   "specialWritePhase" to special,
   "counterObserved" to counterObserved,
   "statusWord" to status,
   "deadline" to deadline,
   "responseDelivered" to delivered,
   "responseDeliveredAt" to deliveredAt,
   "responseDeliveryCount" to deliveries,
   "guestZeroObservationCount" to zeroObservations,
   "completedWriteCount" to writes,
   "completedReadCount" to reads,
   "clock" to clock,
  )
  if(repeatableAnyMode) {
   map["repeatableAnyMode"]=true
   map["activeCandidateMask"]=activeCandidateMask
  }
  return map
 }

 internal fun validateSnapshot(raw:Any?):ValidatedSnapshot {
  check(!closed){"DRAM command bank closed"}
  val section=snapshotMap(raw,"command")
  require(section.keys.containsAll(SNAPSHOT_KEYS)&&(section.keys-SNAPSHOT_KEYS).all{it in OPTIONAL_SNAPSHOT_KEYS}){"Unexpected DRAM command snapshot keys"}
  val savedRepeatable=section["repeatableAnyMode"]?.let{snapshotBoolean(it,"command.repeatableAnyMode")}?:false
  require(!savedRepeatable||repeatableAnyMode){"Repeatable DRAM command snapshot cannot be restored into finite command plan"}
  val nextActiveCandidateMask=section["activeCandidateMask"]?.let{snapshotInt(it,"command.activeCandidateMask",0,ALL_REPEATABLE_MODES_MASK)}?:0
  if(!savedRepeatable) {
   require(nextActiveCandidateMask==0){"Finite DRAM command snapshot cannot carry activeCandidateMask"}
  }
  require(snapshotInt(section["bank"],"command.bank",0,3)==bank){"DRAM command bank index mismatch"}
  val planRows=snapshotList(section["plan"],"command.plan")
  require(planRows.size<=plan.size){"DRAM command plan was shortened"}
  val parsedPlan=planRows.mapIndexed{rowIndex,rawItem->
   val itemMap=snapshotMap(rawItem,"command.plan[$rowIndex]")
   require(itemMap.keys==PLAN_ITEM_KEYS){"Unexpected DRAM command plan item keys"}
   Request(
    mode=snapshotInt(itemMap["mode"],"command.plan[$rowIndex].mode",0,4),
    responseTicks=snapshotLong(itemMap["responseTicks"],"command.plan[$rowIndex].responseTicks",1L,Long.MAX_VALUE),
    responseWord=itemMap["responseWord"]?.let{rawWord->snapshotLong(rawWord,"command.plan[$rowIndex].responseWord",0L,0xffffffffL)},
    counterWord=itemMap["counterWord"]?.let{rawCounter->snapshotLong(rawCounter,"command.plan[$rowIndex].counterWord",0L,0xffffffffL)},
   )
  }
  require(parsedPlan==plan.take(parsedPlan.size)){"DRAM command plan prefix differs from live configuration"}
  val maxPosition=if(savedRepeatable) Int.MAX_VALUE else parsedPlan.size
  val nextPosition=snapshotInt(section["planIndex"],"command.planIndex",0,maxPosition)
  if(savedRepeatable) {
   require(parsedPlan.isNotEmpty()){"Repeatable DRAM command snapshot requires non-empty plan"}
   if(nextPosition>=parsedPlan.size) {
    require(parsedPlan.size==plan.size){"Repeatable DRAM command plan cannot append entries behind planIndex"}
   }
  }
  val nextCursor=snapshotInt(section["acceptedWordCount"],"command.acceptedWordCount",0,71)
  val nextSpecial=snapshotInt(section["specialWritePhase"],"command.specialWritePhase",0,3)
  val nextCounterObserved=snapshotBoolean(section["counterObserved"],"command.counterObserved")
  val nextStatus=section["statusWord"]?.let{rawStatus->snapshotLong(rawStatus,"command.statusWord",0L,0xffffffffL)}
  val nextDeadline=section["deadline"]?.let{rawDeadline->snapshotLong(rawDeadline,"command.deadline",1L,Long.MAX_VALUE)}
  val nextDelivered=snapshotBoolean(section["responseDelivered"],"command.responseDelivered")
  val nextDeliveredAt=section["responseDeliveredAt"]?.let{rawDeliveredAt->snapshotLong(rawDeliveredAt,"command.responseDeliveredAt",1L,Long.MAX_VALUE)}
  val nextDeliveries=snapshotLong(section["responseDeliveryCount"],"command.responseDeliveryCount",0L,Long.MAX_VALUE)
  val nextZeroObservations=snapshotLong(section["guestZeroObservationCount"],"command.guestZeroObservationCount",0L,Long.MAX_VALUE)
  val nextWrites=snapshotLong(section["completedWriteCount"],"command.completedWriteCount",0L,Long.MAX_VALUE)
  val nextReads=snapshotLong(section["completedReadCount"],"command.completedReadCount",0L,Long.MAX_VALUE)
  val nextClock=snapshotLong(section["clock"],"command.clock",0L,Long.MAX_VALUE)
  require(nextZeroObservations==nextPosition.toLong()){"DRAM command guestZeroObservationCount must equal planIndex"}
  if(!savedRepeatable&&nextPosition==parsedPlan.size) {
   require(nextCursor==0){"Exhausted DRAM command plan cannot have active cursor"}
  }
  if(nextPosition<parsedPlan.size||nextCursor==0) {
   require(nextActiveCandidateMask==0){"DRAM command activeCandidateMask must be 0 outside active repeatable command"}
  } else {
   require(nextActiveCandidateMask in 1..ALL_REPEATABLE_MODES_MASK){"Active repeatable DRAM command requires non-zero activeCandidateMask"}
   require(!((nextActiveCandidateMask and FULL_MODES_MASK)!=0 && (nextActiveCandidateMask and TARGETED_MODES_MASK)!=0)){"DRAM command activeCandidateMask cannot mix full and targeted command tables"}
  }
  val expectedLen=if(nextPosition>=parsedPlan.size&&nextCursor>0) expectedTableLength(nextActiveCandidateMask) else 71
  require(nextCursor<=expectedLen){"DRAM command acceptedWordCount exceeds active table length"}
  if(bank!=0) {
   require(nextSpecial==0&&!nextCounterObserved){"Non-zero DRAM bank cannot observe special counter"}
  }
  if(nextCounterObserved) {
   require(expectedLen==71&&nextSpecial in 1..3&&nextCursor>=70){"Observed DRAM command counter requires special phase 1..3 at cursor 70..71"}
  } else {
   require(nextSpecial==0){"Unobserved DRAM command counter requires specialWritePhase 0"}
  }
  require(nextDeliveries==nextPosition.toLong()+(if(nextDelivered)1L else 0L)){"DRAM command delivery count inconsistent"}
  if(nextCursor<expectedLen) {
   require(nextDeadline==null&&!nextDelivered){"Incomplete DRAM command table cannot have pending/delivered response"}
   if(nextPosition==0) {
    require(nextStatus==null&&nextDeliveredAt==null){"Initial incomplete DRAM command cannot retain prior status"}
   }
  } else {
   require(nextStatus!=null&&(nextDeadline!=null)!=nextDelivered){"Submitted DRAM command requires status and either deadline or delivered"}
   if(nextDeadline!=null) {
    require(nextDeliveredAt==null){"Pending DRAM command cannot have responseDeliveredAt"}
   } else {
    require(nextDeliveredAt!=null){"Delivered DRAM command requires responseDeliveredAt"}
   }
  }
  return ValidatedSnapshot(
   position=nextPosition,
   cursor=nextCursor,
   special=nextSpecial,
   counterObserved=nextCounterObserved,
   status=nextStatus,
   deadline=nextDeadline,
   delivered=nextDelivered,
   deliveredAt=nextDeliveredAt,
   deliveries=nextDeliveries,
   zeroObservations=nextZeroObservations,
   writes=nextWrites,
   reads=nextReads,
   clock=nextClock,
   activeCandidateMask=nextActiveCandidateMask,
  )
 }

 internal fun applySnapshot(validated:ValidatedSnapshot) {
  check(!closed){"DRAM command bank closed"}
  position=validated.position
  cursor=validated.cursor
  activeCandidateMask=validated.activeCandidateMask
  special=validated.special
  counterObserved=validated.counterObserved
  status=validated.status
  deadline=validated.deadline
  delivered=validated.delivered
  deliveredAt=validated.deliveredAt
  deliveries=validated.deliveries
  zeroObservations=validated.zeroObservations
  writes=validated.writes
  reads=validated.reads
  clock=validated.clock
 }
}
