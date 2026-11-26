package com.github.nyabanban.fuji.emulator.components.dram

/** DAT11FADF8 register protocol, finite explicit functional input; no physical snapshot/reset/training law. */
class DramSnapshotBank(val bank:Int,val responseTicks:Long,val pendingStatusWord:Long,val responseWord:Long?) {
 companion object {
  const val REQUEST_OFFSET=0x7080
  const val REQUEST_VALUE=0x100L
  const val STATUS_OFFSET=0x7090
  const val STATUS_MASK=1L
  const val STATUS_EXPECTED=1L
  private val offsets=intArrayOf(0x7114,0x7c40,0x7c14)
  private val values=longArrayOf(0,0x80000000L,0x80000000L)
  fun configuration():List<Pair<Int,Long>> = offsets.indices.map{offsets[it] to values[it]}
  fun acknowledgementAddress(bank:Int):Long {require(bank in 0..3);return 0xfff49c00L+bank*16}
  private val SNAPSHOT_KEYS=setOf(
   "bank","responseTicks","pendingStatusWord","responseWord","configurationWrites","requestSubmitted",
   "deadline","statusWord","responseDelivered","responseDeliveredAt","responseDeliveryCount",
   "completedWriteCount","completedReadCount","guestMatchObserved","guestMatchObservedAt",
   "externalAcknowledgementCount","externalAcknowledgedAt","clock",
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
  private fun snapshotMap(value:Any?,field:String):Map<*,*> {
   val map=value as? Map<*,*>?:throw IllegalArgumentException("Snapshot field $field requires map")
   require(map.keys.all{key->key is String}){"Snapshot field $field requires string keys"}
   return map
  }
 }
 internal class ValidatedSnapshot(
  val configured:Int,
  val requested:Boolean,
  val deadline:Long?,
  val status:Long?,
  val delivered:Boolean,
  val deliveredAt:Long?,
  val deliveries:Long,
  val writes:Long,
  val reads:Long,
  val matched:Boolean,
  val matchedAt:Long?,
  val acknowledgements:Long,
  val acknowledgedAt:Long?,
  val clock:Long,
 ) {
  fun isActive():Boolean = configured>0
  fun hasPendingDeadline():Boolean = deadline!=null
 }
 private var closed=false
 private var clock=0L
 private var configured=0
 private var requested=false
 private var deadline:Long?=null
 private var status:Long?=null
 private var delivered=false
 private var deliveredAt:Long?=null
 private var deliveries=0L
 private var reads=0L
 private var writes=0L
 private var matched=false
 private var matchedAt:Long?=null
 private var acknowledgements=0L
 private var acknowledgedAt:Long?=null
 init {
  require(bank in 0..3&&responseTicks>0)
  require(pendingStatusWord in 0..0xffffffffL&&pendingStatusWord and STATUS_MASK!=STATUS_EXPECTED){"snapshot pending sample must not already satisfy the poll"}
  require(responseWord==null||responseWord in 0..0xffffffffL)
 }
 private fun live(tick:Long){check(!closed){"DRAM snapshot bank closed"};require(tick>=clock){"DRAM snapshot clock moved backwards"}}
 fun preflightClock(tick:Long){live(tick)}
 fun syncClock(tick:Long){if(!closed && tick>clock && (deadline==null || tick<deadline!!)) clock=tick}
 fun isActive():Boolean {check(!closed);return configured>0}
 fun hasAcknowledgement():Boolean {check(!closed);return acknowledgements==1L}
 fun resetForNextCycle() {
  check(!closed){"DRAM snapshot bank closed"}
  require(acknowledgements==1L){"DRAM snapshot cycle reset requires completed prior acknowledgement"}
  configured=0;requested=false;deadline=null;status=null;delivered=false;deliveredAt=null
  deliveries=0L;matched=false;matchedAt=null;acknowledgements=0L;acknowledgedAt=null
 }
 fun hasPendingResponse():Boolean {check(!closed);return deadline!=null}
 fun nextDeadline():Long? {check(!closed);return deadline}
 fun claims(offset:Int)=offset==REQUEST_OFFSET||offset==STATUS_OFFSET||offsets.contains(offset)
 fun advance(tick:Long) {
  live(tick);val at=deadline
  if(at==null||tick<at){clock=tick;return}
  val value=checkNotNull(responseWord){"DRAM snapshot response input missing; request remains pending"}
  check(deliveries<Long.MAX_VALUE)
  status=value;deadline=null;delivered=true;deliveredAt=at;deliveries++;clock=tick
 }
 fun write(offset:Int,size:Int,value:Long,tick:Long) {
  live(tick);require(size==4&&offset%4==0&&value in 0..0xffffffffL);check(writes<Long.MAX_VALUE)
  check(!requested&&acknowledgements==0L){"DRAM snapshot finite request already submitted"}
  if(configured<3){require(offset==offsets[configured]&&value==values[configured]){"DRAM snapshot configuration order/value differs"};configured++;writes++;clock=tick;return}
  require(offset==REQUEST_OFFSET&&value==REQUEST_VALUE){"DRAM snapshot request word differs"}
  val at=Math.addExact(tick,responseTicks);require(at>tick)
  requested=true;status=pendingStatusWord;deadline=at;writes++;clock=tick
 }
 fun read(offset:Int,size:Int,tick:Long):Long {
  live(tick);require(size==4&&offset==STATUS_OFFSET);check(requested&&!matched&&acknowledgements==0L){"DRAM snapshot status read before request or after match"};check(reads<Long.MAX_VALUE)
  advance(tick);val value=checkNotNull(status)
  if(delivered&&value and STATUS_MASK==STATUS_EXPECTED){matched=true;matchedAt=tick}
  reads++;clock=tick;return value
 }
 fun acknowledge(offset:Long,size:Int,value:Long,tick:Long) {
  live(tick);require(offset==0L&&size==4&&value==1L){"DRAM snapshot exact external acknowledgement required"}
  check(requested&&delivered&&matched&&acknowledgements==0L){"DRAM snapshot acknowledgement before actual matching guest read or after exhaustion"}
  acknowledgements=1;acknowledgedAt=tick;clock=tick
 }
 fun close(){closed=true}
 fun state():Map<String,Any?> = mapOf("closed" to closed,"bank" to bank,"configurationWrites" to configured,"requestSubmitted" to requested,"deadline" to deadline,"statusWord" to status,"responseDelivered" to delivered,"responseDeliveredAt" to deliveredAt,"responseDeliveryCount" to deliveries,"completedWriteCount" to writes,"completedReadCount" to reads,"guestMatchObserved" to matched,"guestMatchObservedAt" to matchedAt,"externalAcknowledgementCount" to acknowledgements,"externalAcknowledgedAt" to acknowledgedAt,"clock" to clock)

 internal fun saveSnapshot():Map<String,Any?> {
  check(!closed){"DRAM snapshot bank closed"}
  return linkedMapOf(
   "bank" to bank,
   "responseTicks" to responseTicks,
   "pendingStatusWord" to pendingStatusWord,
   "responseWord" to responseWord,
   "configurationWrites" to configured,
   "requestSubmitted" to requested,
   "deadline" to deadline,
   "statusWord" to status,
   "responseDelivered" to delivered,
   "responseDeliveredAt" to deliveredAt,
   "responseDeliveryCount" to deliveries,
   "completedWriteCount" to writes,
   "completedReadCount" to reads,
   "guestMatchObserved" to matched,
   "guestMatchObservedAt" to matchedAt,
   "externalAcknowledgementCount" to acknowledgements,
   "externalAcknowledgedAt" to acknowledgedAt,
   "clock" to clock,
  )
 }

 internal fun validateSnapshot(raw:Any?):ValidatedSnapshot {
  check(!closed){"DRAM snapshot bank closed"}
  val section=snapshotMap(raw,"snapshot")
  require(section.keys==SNAPSHOT_KEYS){"Unexpected DRAM snapshot bank keys"}
  require(snapshotInt(section["bank"],"snapshot.bank",0,3)==bank){"DRAM snapshot bank index mismatch"}
  require(snapshotLong(section["responseTicks"],"snapshot.responseTicks",1L,Long.MAX_VALUE)==responseTicks){"DRAM snapshot responseTicks mismatch"}
  require(snapshotLong(section["pendingStatusWord"],"snapshot.pendingStatusWord",0L,0xffffffffL)==pendingStatusWord){"DRAM snapshot pendingStatusWord mismatch"}
  val savedResponseWord=section["responseWord"]?.let{rawWord->snapshotLong(rawWord,"snapshot.responseWord",0L,0xffffffffL)}
  require(savedResponseWord==responseWord){"DRAM snapshot responseWord mismatch"}
  val nextConfigured=snapshotInt(section["configurationWrites"],"snapshot.configurationWrites",0,3)
  val nextRequested=snapshotBoolean(section["requestSubmitted"],"snapshot.requestSubmitted")
  val nextDeadline=section["deadline"]?.let{rawDeadline->snapshotLong(rawDeadline,"snapshot.deadline",1L,Long.MAX_VALUE)}
  val nextStatus=section["statusWord"]?.let{rawStatus->snapshotLong(rawStatus,"snapshot.statusWord",0L,0xffffffffL)}
  val nextDelivered=snapshotBoolean(section["responseDelivered"],"snapshot.responseDelivered")
  val nextDeliveredAt=section["responseDeliveredAt"]?.let{rawDeliveredAt->snapshotLong(rawDeliveredAt,"snapshot.responseDeliveredAt",1L,Long.MAX_VALUE)}
  val nextDeliveries=snapshotLong(section["responseDeliveryCount"],"snapshot.responseDeliveryCount",0L,1L)
  val nextWrites=snapshotLong(section["completedWriteCount"],"snapshot.completedWriteCount",0L,Long.MAX_VALUE)
  val nextReads=snapshotLong(section["completedReadCount"],"snapshot.completedReadCount",0L,Long.MAX_VALUE)
  val nextMatched=snapshotBoolean(section["guestMatchObserved"],"snapshot.guestMatchObserved")
  val nextMatchedAt=section["guestMatchObservedAt"]?.let{rawMatchedAt->snapshotLong(rawMatchedAt,"snapshot.guestMatchObservedAt",0L,Long.MAX_VALUE)}
  val nextAcknowledgements=snapshotLong(section["externalAcknowledgementCount"],"snapshot.externalAcknowledgementCount",0L,1L)
  val nextAcknowledgedAt=section["externalAcknowledgedAt"]?.let{rawAckAt->snapshotLong(rawAckAt,"snapshot.externalAcknowledgedAt",0L,Long.MAX_VALUE)}
  val nextClock=snapshotLong(section["clock"],"snapshot.clock",0L,Long.MAX_VALUE)
  if(!nextRequested) {
   require(nextDeadline==null&&nextStatus==null&&!nextDelivered&&nextDeliveredAt==null&&nextDeliveries==0L&&!nextMatched&&nextMatchedAt==null&&nextAcknowledgements==0L&&nextAcknowledgedAt==null) {
    "Unsubmitted DRAM snapshot bank cannot carry active request metadata"
   }
  } else {
   require(nextConfigured==3&&nextStatus!=null&&(nextDeadline!=null)!=nextDelivered){"Submitted DRAM snapshot bank metadata inconsistent"}
   if(nextDeadline!=null) {
    require(nextStatus==pendingStatusWord&&nextDeliveredAt==null&&nextDeliveries==0L&&!nextMatched&&nextMatchedAt==null&&nextAcknowledgements==0L&&nextAcknowledgedAt==null) {
     "Pending DRAM snapshot bank metadata inconsistent"
    }
   } else {
    require(nextDeliveredAt!=null&&nextDeliveries==1L){"Delivered DRAM snapshot bank requires responseDeliveredAt and delivery count 1"}
   }
   require(nextMatched==(nextMatchedAt!=null)){"DRAM snapshot guestMatchObserved disagrees with timestamp"}
   require((nextAcknowledgements==1L)==(nextAcknowledgedAt!=null)){"DRAM snapshot externalAcknowledgementCount disagrees with timestamp"}
   if(nextAcknowledgements==1L) {
    require(nextMatched){"DRAM snapshot external acknowledgement requires prior guest match"}
   }
  }
  return ValidatedSnapshot(
   configured=nextConfigured,
   requested=nextRequested,
   deadline=nextDeadline,
   status=nextStatus,
   delivered=nextDelivered,
   deliveredAt=nextDeliveredAt,
   deliveries=nextDeliveries,
   writes=nextWrites,
   reads=nextReads,
   matched=nextMatched,
   matchedAt=nextMatchedAt,
   acknowledgements=nextAcknowledgements,
   acknowledgedAt=nextAcknowledgedAt,
   clock=nextClock,
  )
 }

 internal fun applySnapshot(validated:ValidatedSnapshot) {
  check(!closed){"DRAM snapshot bank closed"}
  configured=validated.configured
  requested=validated.requested
  deadline=validated.deadline
  status=validated.status
  delivered=validated.delivered
  deliveredAt=validated.deliveredAt
  deliveries=validated.deliveries
  writes=validated.writes
  reads=validated.reads
  matched=validated.matched
  matchedAt=validated.matchedAt
  acknowledgements=validated.acknowledgements
  acknowledgedAt=validated.acknowledgedAt
  clock=validated.clock
 }
}
