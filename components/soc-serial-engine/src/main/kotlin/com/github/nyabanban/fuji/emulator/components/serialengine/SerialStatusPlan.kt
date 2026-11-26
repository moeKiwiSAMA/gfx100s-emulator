package com.github.nyabanban.fuji.emulator.components.serialengine

import com.github.nyabanban.fuji.emulator.api.*

/** Finite explicitly supplied read transactions. Masked guest write acknowledges a sample, not a physical W1C law. */
internal class SerialStatusPlan(
 private val channel:Int,raw:Any?,private val context:ComponentContext,
 private val closed:()->Boolean,private val globalPreflight:(Long)->Unit,private val advanceClock:(Long)->Unit,
 private val resumeSamples:List<Long> = emptyList(),
 private val repeatLastPlan:Boolean = false,
 private val completionIrqs:Boolean = false,
 private val onCompletionCleared:(()->Unit)? = null,
):MmioDevice {
 private val initialTransactions:Array<Array<Long?>>
 private var transactions:Array<Array<Long?>>
 private var transaction=0
 private var sample=0
 private var completionWord=0L
 private var count=0L
 private var reads=0L
 private var writes=0L
 private val capacity=64
 private val directions=BooleanArray(capacity)
 private val values=LongArray(capacity)
 private val ticks=LongArray(capacity)
 private val cores=IntArray(capacity){-1}
 private val pcs=LongArray(capacity){-1}
 internal data class ParsedAccess(val write:Boolean,val value:Long,val tick:Long,val core:Int,val pcVirtual:Long)
 internal data class ParsedSnapshot(
  val transactionIndex:Int,
  val sampleIndex:Int,
  val readCount:Long,
  val writeCount:Long,
  val accessCount:Long,
  val accesses:List<ParsedAccess>,
 )
 override val clockOrder=90
 override val clockIndex=channel+2
 init {
  require(channel in 0..7&&raw is List<*>&&raw.isNotEmpty()&&raw.size<=64){"serial status requires1..64 explicit transactions"}
  initialTransactions=raw.map{group->require(group is List<*>&&group.size in 1..2){"serial status transaction requires1..2 read samples"};group.map{value->if(value==null)null else {require(value is Byte||value is Short||value is Int||value is Long){"serial status exact unsigned integer required"};(value as Number).toLong().also{require(it in 0..0xffffffffL)}}}.toTypedArray()}.toTypedArray()
  transactions=initialTransactions
  if(resumeSamples.isNotEmpty()) require(channel==1 && frozenPlan()==listOf(listOf(0L))) {
   "Channel 1 status continuation requires the original zero sample"
  }
 }
 private fun exact(offset:Long,size:Int){check(!closed()){"serial engine closed"};require(offset==0L&&size==4){"serial status exact aligned word required"}}
 private fun pending():Array<Long?> {if(transaction<transactions.size)return transactions[transaction];check(repeatLastPlan){"serial status explicit plan exhausted channel"+channel};return transactions.last()}
 private fun preflight():Long {
  val tick=context.clock.ticks();globalPreflight(tick);check(count<Long.MAX_VALUE&&reads<Long.MAX_VALUE&&writes<Long.MAX_VALUE){"serial status diagnostics overflow"}
  return tick
 }
 fun completionStatusWord():Long = completionWord
 fun latchCompletion(bits:Long) {
  require(completionIrqs) { "completion status latch requires completionIrqs" }
  require((bits and 0x10001L.inv()) == 0L) { "serial status completion bits out of 0x10001 domain" }
  completionWord = completionWord or bits
 }
 fun setCompletionStatusWord(word:Long) {
  require((word and 0x10001L.inv()) == 0L) { "serial status completion bits out of 0x10001 domain" }
  completionWord = word
 }
 fun resetIdle() {
  transaction = 0
  sample = 0
  completionWord = 0L
  reads = 0L
  writes = 0L
  count = 0L
  directions.fill(false)
  values.fill(0L)
  ticks.fill(0L)
  cores.fill(-1)
  pcs.fill(-1L)
 }
 override fun read(offset:Long,size:Int):Long {
  exact(offset,size)
  if (completionIrqs) {
   val tick=preflight()
   val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
   val word=completionWord
   record(false,word,tick,location)
   return word
  }
  val pending=pending();check(sample<pending.size){"serial status masked write required before next transaction"}
  val word=checkNotNull(pending[sample]){"serial status explicit sample missing channel"+channel+" transaction"+transaction+" sample"+sample}
  val tick=preflight();val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();sample++;record(false,word,tick,location);return word
 }
 override fun write(offset:Long,size:Int,value:Long) {
  exact(offset,size);require(value in 0..0xffffffffL)
  if (completionIrqs) {
   require((value and 0x10001L.inv()) == 0L) { "serial status W1C write sets bits outside 0x10001" }
   val tick=preflight()
   val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
   completionWord = completionWord and value.inv()
   record(true,value,tick,location)
   onCompletionCleared?.invoke()
   return
  }
  val pending=pending();check(sample==pending.size){"serial status write before all explicit reads"}
  val raw=checkNotNull(pending.last());require(value==(raw and 0x10001L)){"serial status guest masked write differs from last supplied sample"}
  val tick=preflight();val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();transaction++;sample=0;record(true,value,tick,location)
 }
 private fun record(write:Boolean,value:Long,tick:Long,location:ExecutionLocation?) {
  val slot=(count%capacity).toInt();directions[slot]=write;values[slot]=value;ticks[slot]=tick;cores[slot]=location?.core?:-1;pcs[slot]=location?.pcVirtual?:-1;count++;if(write)writes++ else reads++;advanceClock(tick)
 }
 fun frozenPlan():List<List<Long?>> = transactions.map{it.toList()}
 fun saveSnapshot():Map<String,Any?> = linkedMapOf(
  "channel" to channel,
  "frozenPlan" to frozenPlan(),
  "transactionIndex" to transaction,
  "sampleIndex" to sample,
  "readCount" to reads,
  "writeCount" to writes,
  "accessCount" to count,
  "accesses" to (maxOf(0L,count-capacity) until count).map{sequence->
   val ringSlot=(sequence%capacity).toInt()
   linkedMapOf(
    "write" to directions[ringSlot],
    "value" to values[ringSlot],
    "tick" to ticks[ringSlot],
    "core" to cores[ringSlot].takeIf{coreIndex->coreIndex>=0},
    "pcVirtual" to pcs[ringSlot].takeIf{pcValue->pcValue>=0L},
   )
  },
 )
 fun parseSnapshot(raw:Any?,maxClock:Long):ParsedSnapshot {
  val statusMap=raw as? Map<*,*> ?: throw IllegalArgumentException("Invalid serial status snapshot for channel $channel")
  require(statusMap.keys==setOf("channel","frozenPlan","transactionIndex","sampleIndex","readCount","writeCount","accessCount","accesses")){
   "Unexpected serial status snapshot keys for channel $channel"
  }
  require(SerialSnapshotCodec.parseIntegralInt(statusMap["channel"],"status.channel")==channel){"Serial status channel mismatch"}
  val rawPlan=statusMap["frozenPlan"] as? List<*> ?: throw IllegalArgumentException("Invalid frozenPlan for status channel $channel")
  val parsedPlan=rawPlan.map{rawGroup->
   val groupList=rawGroup as? List<*> ?: throw IllegalArgumentException("Invalid status transaction list in frozenPlan")
   groupList.map{sampleValue->sampleValue?.let{nonNull->SerialSnapshotCodec.parseUnsignedWord(nonNull,"status.frozenPlanSample")}}
  }
  val original=initialTransactions.map{it.toList()}
  val selected=original+resumeSamples.map { listOf(it) }
  require(parsedPlan.isNotEmpty() && parsedPlan.size<=selected.size && parsedPlan==selected.take(parsedPlan.size) &&
   (resumeSamples.isNotEmpty() || parsedPlan==original)) {
   "Frozen status plan differs from snapshot for channel $channel"
  }
  val nextTransaction=SerialSnapshotCodec.parseIntegralInt(statusMap["transactionIndex"],"status.transactionIndex")
  val nextSample=SerialSnapshotCodec.parseIntegralInt(statusMap["sampleIndex"],"status.sampleIndex")
  require(nextTransaction in 0..parsedPlan.size || (repeatLastPlan && nextTransaction>=0)){"Status transactionIndex out of bounds for channel $channel"}
  if(!repeatLastPlan && nextTransaction==parsedPlan.size) {
   require(nextSample==0){"Exhausted status plan must have sampleIndex 0"}
  } else {
   val currentGroup=parsedPlan[minOf(nextTransaction,parsedPlan.size-1)]
   require(nextSample in 0..currentGroup.size){"Status sampleIndex out of bounds for channel $channel"}
   for(consumedIndex in 0 until nextSample) {
    require(currentGroup[consumedIndex]!=null){"Consumed status sample cannot be null for channel $channel"}
   }
  }
  val nextReads=SerialSnapshotCodec.parseIntegralLong(statusMap["readCount"],"status.readCount")
  val nextWrites=SerialSnapshotCodec.parseIntegralLong(statusMap["writeCount"],"status.writeCount")
  val nextCount=SerialSnapshotCodec.parseIntegralLong(statusMap["accessCount"],"status.accessCount")
  require(nextReads>=0L&&nextWrites>=0L&&nextCount>=0L){"Negative counter in serial status snapshot"}
  val totalAccesses=try{Math.addExact(nextReads,nextWrites)}catch(_:ArithmeticException){throw IllegalArgumentException("Status counter overflow")}
  require(totalAccesses==nextCount){"Status readCount + writeCount must equal accessCount"}
  val rawAccesses=statusMap["accesses"] as? List<*> ?: throw IllegalArgumentException("Invalid accesses list in status snapshot")
  require(rawAccesses.size==minOf(nextCount,capacity.toLong()).toInt()){"Status access log size disagrees with accessCount"}
  val parsedAccesses=rawAccesses.map{rawEntry->
   val entryMap=rawEntry as? Map<*,*> ?: throw IllegalArgumentException("Invalid status access entry")
   require(entryMap.keys==setOf("write","value","tick","core","pcVirtual")){"Unexpected status access entry keys"}
   val entryWrite=entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid status access write flag")
   val entryValue=SerialSnapshotCodec.parseUnsignedWord(entryMap["value"],"statusAccess.value")
   val entryTick=SerialSnapshotCodec.parseIntegralLong(entryMap["tick"],"statusAccess.tick")
   require(entryTick in 0L..maxClock){"Status access tick out of bounds"}
   val entryCore=if(entryMap["core"]==null)-1 else SerialSnapshotCodec.parseIntegralInt(entryMap["core"],"statusAccess.core").also{parsed->require(parsed>=0)}
   val entryPc=if(entryMap["pcVirtual"]==null)-1L else SerialSnapshotCodec.parseIntegralLong(entryMap["pcVirtual"],"statusAccess.pcVirtual").also{parsed->require(parsed>=0L)}
   ParsedAccess(entryWrite,entryValue,entryTick,entryCore,entryPc)
  }
  if(resumeSamples.isNotEmpty() && !completionIrqs) {
   if(!repeatLastPlan) {
    require(parsedPlan.size==selected.size || nextTransaction==parsedPlan.size){"Additional channel 1 samples require an exhausted saved plan"}
   }
   require(nextTransaction>=1 && nextReads==nextTransaction.toLong()+nextSample && nextWrites==nextTransaction.toLong()) {
    "Channel 1 status continuation requires a fully consumed original transaction"
   }
   require(parsedAccesses.withIndex().all{(index,access)->
    val sequence=(nextCount-parsedAccesses.size+index).toInt()
    val write=sequence%2==1
    val word=parsedPlan[minOf(sequence/2,parsedPlan.size-1)].single()!!
    access.write==write && access.value==(if(write) word and 0x10001L else word)
   }) {
    "Channel 1 status continuation ledger differs from the frozen samples"
   }
  }
  return ParsedSnapshot(nextTransaction,nextSample,nextReads,nextWrites,nextCount,parsedAccesses)
 }
 fun applySnapshot(parsed:ParsedSnapshot) {
  if(resumeSamples.isNotEmpty()) transactions=initialTransactions+resumeSamples.map { arrayOf<Long?>(it) }.toTypedArray()
  transaction=parsed.transactionIndex
  sample=parsed.sampleIndex
  reads=parsed.readCount
  writes=parsed.writeCount
  count=parsed.accessCount
  directions.fill(false)
  values.fill(0L)
  ticks.fill(0L)
  cores.fill(-1)
  pcs.fill(-1L)
  val startSequence=maxOf(0L,parsed.accessCount-capacity)
  for((offset,accessEntry)in parsed.accesses.withIndex()) {
   val ringSlot=((startSequence+offset)%capacity).toInt()
   directions[ringSlot]=accessEntry.write
   values[ringSlot]=accessEntry.value
   ticks[ringSlot]=accessEntry.tick
   cores[ringSlot]=accessEntry.core
   pcs[ringSlot]=accessEntry.pcVirtual
  }
 }
 fun state():Map<String,Any?> = mapOf("transactionIndex" to transaction,"sampleIndex" to sample,"exhausted" to(transaction==transactions.size),"pendingWrite" to(transaction<transactions.size&&sample==transactions[transaction].size),"readCount" to reads,"writeCount" to writes,"accessCount" to count,"accessCapacity" to capacity,"accessLogTruncated" to(count>capacity),"accesses" to(maxOf(0L,count-capacity) until count).map{sequence->val i=(sequence%capacity).toInt();mapOf("write" to directions[i],"value" to values[i],"tick" to ticks[i],"core" to cores[i].takeIf{it>=0},"pcVirtual" to pcs[i].takeIf{it>=0})},"basis" to "Explicit finite HYP samples and observed DAT software masked-write domain only. No implicit post-write zero, physical reset/W1C/ready/IRQ or transport effects.")
}
