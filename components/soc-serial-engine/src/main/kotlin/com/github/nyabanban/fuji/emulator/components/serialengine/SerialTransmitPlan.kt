package com.github.nyabanban.fuji.emulator.components.serialengine

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Explicit finite control/acceptance plan. Buffer capture is not display delivery or a physical IRQ/W1C law. */
internal class SerialTransmitPlan(
 val channel:Int,raw:Any?,private val context:ComponentContext,
 private val closed:()->Boolean,private val globalPreflight:(Long)->Unit,private val advanceClock:(Long)->Unit,
 private val captureEvents:Boolean=false,
 private val repeatLastPlan:Boolean=false,
 private val onTransferComplete:(()->Unit)?=null,
):TickAwareMmioDevice,RangedDmaDestinationPeer,TransferCapturePort {
 companion object {
  const val CAPACITY_LIMIT=262144
  fun requestId(channel:Int):Int=when(channel){1->21;2->22;4->26;6->25;else->error("serial transmit channel has no qualified DAT request")}
 }
 private data class Plan(val size:Int,val delay:Long?)
 private val plans:Array<Plan>
 private var index=0
 private var armedAt:Long?=null
 private var readyAt:Long?=null
 private var owner=-1
 private var received=0
 private var armCount=0L
 private var claimCount=0L
 private var cancelCount=0L
 private var completed=0L
 private var captureNotifications=0L
 private var captureNotificationFailures=0L
 private var lastNotificationFailure:String?=null
 private var writing:ByteArray
 private var captured:ByteArray
 private var last:TransferCapture?=null
 private var eventCount=0L
 private val eventKinds=IntArray(64)
 private val eventTicks=LongArray(64)
 private val eventOwners=IntArray(64){-1}
 private val eventCores=IntArray(64){-1}
 private val eventPcs=LongArray(64){-1}
 internal data class ParsedEvent(val kindCode:Int,val tick:Long,val dmaChannel:Int,val core:Int,val pcVirtual:Long)
 internal data class ParsedSnapshot(
  val planIndex:Int,
  val armedAt:Long?,
  val readyAt:Long?,
  val leaseOwner:Int,
  val bytesReceived:Int,
  val writingPrefix:ByteArray,
  val armCount:Long,
  val claimCount:Long,
  val cancelCount:Long,
  val completedCount:Long,
  val captureNotifications:Long,
  val captureNotificationFailures:Long,
  val lastNotificationFailure:String?,
  val lastCapture:TransferCapture?,
  val capturedPayload:ByteArray?,
  val eventCount:Long,
  val events:List<ParsedEvent>,
 )
 override val clockOrder=90
 override val clockIndex=10+channel
 init {
  requestId(channel);require(raw is List<*>&&raw.size in 1..64){"serial transmit requires1..64 plans"}
  plans=raw.map{value->require(value is Map<*,*>&&value.keys==setOf("byteCount","acceptDelayTicks"));val size=integer(value["byteCount"]);require(size in 4..CAPACITY_LIMIT.toLong()&&size%4==0L){"serial transmit exact whole-word capacity required"};val delay=value["acceptDelayTicks"]?.let{integer(it)};Plan(size.toInt(),delay)}.toTypedArray()
  val capacity=plans.maxOf{it.size};writing=ByteArray(capacity);captured=ByteArray(capacity)
 }
 private fun integer(value:Any?):Long {require(value is Byte||value is Short||value is Int||value is Long){"serial transmit exact nonnegative integer required"};return(value as Number).toLong().also{require(it>=0)}}
 private fun live(){check(!closed()){"serial engine closed"}}
 private fun plan():Plan {live();if(index<plans.size)return plans[index];check(repeatLastPlan){"serial transmit explicit plan exhausted"};return plans.last()}
 private fun planAt(planIndex:Int):Plan = plans[minOf(planIndex,plans.size-1)]
 private fun endpoint():AddressWindow {live();val layout=context.ports.find("board.layout",AddressLayout::class.java)?:error("serial transmit board layout missing");return layout.window(SerialEngineFactory.dataPort(channel)).also{require(it.size==4L&&it.base in 0..0xfffffffcL&&it.base%4==0L){"serial transmit exact data window required"}}}
 private fun preflight(tick:Long){globalPreflight(tick);check(eventCount<Long.MAX_VALUE&&armCount<Long.MAX_VALUE&&claimCount<Long.MAX_VALUE&&cancelCount<Long.MAX_VALUE&&completed<Long.MAX_VALUE){"serial transmit diagnostics overflow"}}
 private fun record(kind:Int,tick:Long,lease:Int,location:ExecutionLocation?) {val slot=(eventCount%64).toInt();eventKinds[slot]=kind;eventTicks[slot]=tick;eventOwners[slot]=lease;eventCores[slot]=location?.core?:-1;eventPcs[slot]=location?.pcVirtual?:-1;eventCount++;advanceClock(tick)}
 override fun nextDeadline():Long? {live();val ready=readyAt?:return null;return ready.takeIf{armedAt!=null&&owner<0&&context.clock.ticks()<it}}
 override fun nextInterestedTick():Long=Long.MAX_VALUE
 override fun beforeInstruction(tick:Long){live();require(tick>=0)}
 override fun read(offset:Long,size:Int):Long {live();error("serial transmit control read unqualified")}
 override fun write(offset:Long,size:Int,value:Long) {
  live();require(offset==0L&&size==4&&value==0L){"serial transmit only exact control zero write qualified"};val selected=plan();check(armedAt==null&&owner<0){"serial transmit already armed"}
  val tick=context.clock.ticks();preflight(tick);val ready=selected.delay?.let{Math.addExact(tick,it)}
  // Resolve execution context before any mutation. Endpoint layout is checked by the startup contract and peer preflight.
  val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
  armedAt=tick;readyAt=ready;armCount++;record(0,tick,-1,location)
 }
 override fun destinationRanges():List<AddressWindow> = listOf(endpoint())
 override fun matches(address:Long)=endpoint().base==address
 override fun check(address:Long,size:Long,width:Int,request:Int) {
  val p=plan();require(matches(address)&&size==p.size.toLong()&&width==4&&request==requestId(channel)){"serial transmit endpoint/length/width/request differs"};check(armedAt!=null){"serial transmit controller not armed"}
 }
 override fun acceptedAt():Long? {live();return readyAt?.takeIf{armedAt!=null&&context.clock.ticks()>=it}}
 override fun claim(channel:Int,address:Long,size:Long,width:Int,request:Int):Long? {
  require(channel in 0..15);check(address,size,width,request);val accepted=acceptedAt()?:return null;check(owner<0||owner==channel){"serial transmit request already owned"}
  if(owner<0){val tick=context.clock.ticks();preflight(tick);val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();owner=channel;received=0;claimCount++;record(1,tick,channel,location)};return accepted
 }
 override fun release(channel:Int) {
  require(channel in 0..15);if(closed()||owner!=channel)return;val tick=context.clock.ticks();preflight(tick);val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();owner=-1;received=0;cancelCount++;record(2,tick,channel,location)
 }
 private fun lease(channel:Int,address:Long){live();check(owner==channel&&owner>=0&&matches(address)){"serial transmit lease differs"}}
 override fun write(channel:Int,address:Long,offset:Long,source:ByteBuffer) {
  lease(channel,address);val size=source.remaining();require(size>0&&size%4==0&&offset==received.toLong()&&size<=plan().size-received){"serial transmit stream bounds/order differs"};val tick=context.clock.ticks();globalPreflight(tick);source.get(writing,received,size);received+=size;advanceClock(tick)
 }
 override fun readBack(channel:Int,address:Long,offset:Long,destination:ByteBuffer) {
  lease(channel,address);val size=destination.remaining();require(!destination.isReadOnly&&size>0&&offset>=0&&offset<=received.toLong()-size){"serial transmit readback bounds differ"};globalPreflight(context.clock.ticks());destination.put(writing,offset.toInt(),size)
 }
 override fun finish(channel:Int,completedAt:Long,observedAt:Long) {
  live();check(owner==channel&&owner>=0&&received==plan().size){"serial transmit incomplete ownership/bytes"};require(completedAt>=checkNotNull(armedAt)&&completedAt>=checkNotNull(readyAt)&&observedAt>=completedAt&&observedAt==context.clock.ticks())
  preflight(observedAt);val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current();val hasher=MessageDigest.getInstance("SHA-256");hasher.update(writing,0,received);val digest=hasher.digest();val sha=java.util.HexFormat.of().formatHex(digest)
  val next=TransferCapture(completed+1,received,sha,completedAt,observedAt);val old=captured;captured=writing;writing=old;last=next;completed++;index++;armedAt=null;readyAt=null;owner=-1;received=0;record(3,observedAt,channel,location)
  // Notify hardware completion IRQ/status before passive capture observers.
  onTransferComplete?.invoke()
  // The source transaction is committed before any passive observer is called.
  if(captureEvents) {
   captureNotifications++
   try {context.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",observedAt,mapOf("port" to SerialEngineFactory.capturePort(this.channel),"sequence" to next.sequence,"size" to next.size)))}
   catch(error:Exception) {captureNotificationFailures++;lastNotificationFailure=error.javaClass.simpleName.take(128)}
  }
 }
 override fun capture():TransferCapture? {live();return last}
 override fun read(sequence:Long,offset:Long,destination:ByteBuffer) {
  live();val capture=checkNotNull(last){"serial transmit capture missing"};val size=destination.remaining();require(sequence==capture.sequence&&!destination.isReadOnly&&offset>=0&&size>=0&&offset<=capture.size.toLong()-size){"serial transmit capture generation/bounds differs"};destination.put(captured,offset.toInt(),size)
 }
 fun close(){owner=-1;received=0;armedAt=null;readyAt=null}
 fun frozenPlan():List<Map<String,Any?>> = plans.map{mapOf("byteCount" to it.size,"acceptDelayTicks" to it.delay)}
 fun saveSnapshot():Map<String,Any?> = linkedMapOf(
  "channel" to channel,
  "captureEvents" to captureEvents,
  "frozenPlan" to frozenPlan(),
  "planIndex" to index,
  "armedAt" to armedAt,
  "acceptAt" to readyAt,
  "leaseOwner" to owner.takeIf{currentOwner->currentOwner>=0},
  "bytesReceived" to received,
  "writingChunksBase64" to SerialSnapshotCodec.encodeBytesChunked(writing,received),
  "armCount" to armCount,
  "claimCount" to claimCount,
  "cancelCount" to cancelCount,
  "completedCount" to completed,
  "captureNotifications" to captureNotifications,
  "captureNotificationFailures" to captureNotificationFailures,
  "lastNotificationFailure" to lastNotificationFailure,
  "capture" to last?.let{currentCapture->
   linkedMapOf(
    "sequence" to currentCapture.sequence,
    "size" to currentCapture.size,
    "sha256" to currentCapture.sha256,
    "completedAt" to currentCapture.completedAt,
    "observedAt" to currentCapture.observedAt,
    "payloadChunksBase64" to SerialSnapshotCodec.encodeBytesChunked(captured,currentCapture.size),
   )
  },
  "eventCount" to eventCount,
  "events" to (maxOf(0L,eventCount-64) until eventCount).map{sequence->
   val ringSlot=(sequence%64).toInt()
   linkedMapOf(
    "kind" to listOf("arm","claim","cancel","capture")[eventKinds[ringSlot]],
    "tick" to eventTicks[ringSlot],
    "dmaChannel" to eventOwners[ringSlot].takeIf{dmaOwner->dmaOwner>=0},
    "core" to eventCores[ringSlot].takeIf{coreIndex->coreIndex>=0},
    "pcVirtual" to eventPcs[ringSlot].takeIf{pcValue->pcValue>=0L},
   )
  },
 )
 fun parseSnapshot(raw:Any?,maxClock:Long):ParsedSnapshot {
  val transmitMap=raw as? Map<*,*> ?: throw IllegalArgumentException("Invalid serial transmit snapshot for channel $channel")
  val expectedKeys=setOf(
   "channel","captureEvents","frozenPlan","planIndex","armedAt","acceptAt","leaseOwner",
   "bytesReceived","writingChunksBase64","armCount","claimCount","cancelCount","completedCount",
   "captureNotifications","captureNotificationFailures","lastNotificationFailure","capture",
   "eventCount","events",
  )
  require(transmitMap.keys==expectedKeys){"Unexpected serial transmit snapshot keys for channel $channel"}
  require(SerialSnapshotCodec.parseIntegralInt(transmitMap["channel"],"transmit.channel")==channel){"Serial transmit channel mismatch"}
  require((transmitMap["captureEvents"] as? Boolean)==captureEvents){"Serial transmit captureEvents mismatch for channel $channel"}
  val rawPlan=transmitMap["frozenPlan"] as? List<*> ?: throw IllegalArgumentException("Invalid frozenPlan for transmit channel $channel")
  val parsedPlan=rawPlan.map{rawEntry->
   val planEntry=rawEntry as? Map<*,*> ?: throw IllegalArgumentException("Invalid plan item in transmit frozenPlan")
   require(planEntry.keys==setOf("byteCount","acceptDelayTicks")){"Unexpected transmit frozenPlan item keys"}
   mapOf(
    "byteCount" to SerialSnapshotCodec.parseIntegralInt(planEntry["byteCount"],"transmit.byteCount"),
    "acceptDelayTicks" to planEntry["acceptDelayTicks"]?.let{delayValue->SerialSnapshotCodec.parseIntegralLong(delayValue,"transmit.acceptDelayTicks")},
   )
  }
  val nextPlanIndex=SerialSnapshotCodec.parseIntegralInt(transmitMap["planIndex"],"transmit.planIndex")
  val configuredPlan=frozenPlan()
  require(parsedPlan.isNotEmpty() && (nextPlanIndex in 0..parsedPlan.size || (repeatLastPlan && nextPlanIndex>=0))){"Transmit planIndex out of bounds for channel $channel"}
  require(parsedPlan==configuredPlan || (nextPlanIndex==parsedPlan.size && parsedPlan.size<configuredPlan.size &&
   configuredPlan.take(parsedPlan.size)==parsedPlan)){"Frozen transmit plan differs from snapshot for channel $channel"}
  val nextArmedAt=transmitMap["armedAt"]?.let{armedValue->SerialSnapshotCodec.parseIntegralLong(armedValue,"transmit.armedAt")}
  val nextReadyAt=transmitMap["acceptAt"]?.let{acceptValue->SerialSnapshotCodec.parseIntegralLong(acceptValue,"transmit.acceptAt")}
  val nextOwner=if(transmitMap["leaseOwner"]==null)-1 else SerialSnapshotCodec.parseIntegralInt(transmitMap["leaseOwner"],"transmit.leaseOwner").also{parsed->require(parsed in 0..15)}
  val nextReceived=SerialSnapshotCodec.parseIntegralInt(transmitMap["bytesReceived"],"transmit.bytesReceived")
  if((!repeatLastPlan && nextPlanIndex==parsedPlan.size)||nextArmedAt==null) {
   require(nextArmedAt==null&&nextReadyAt==null&&nextOwner==-1&&nextReceived==0){
    "Unarmed or exhausted transmit channel cannot hold readyAt, leaseOwner, or bytesReceived"
   }
  } else {
   require(nextArmedAt in 0L..maxClock){"Transmit armedAt out of bounds for channel $channel"}
   val activePlan=planAt(nextPlanIndex)
   val expectedReadyAt=try{
    activePlan.delay?.let{delayTicks->Math.addExact(nextArmedAt,delayTicks)}
   }catch(_:ArithmeticException){
    throw IllegalArgumentException("Transmit acceptAt overflow in snapshot")
   }
   require(nextReadyAt==expectedReadyAt){"Transmit acceptAt disagrees with armedAt + acceptDelayTicks"}
   if(nextOwner>=0) {
    require(nextReadyAt!=null&&nextReadyAt<=maxClock){"Owned transmit lease requires accepted deadline <= clock"}
    require(nextReceived in 0..activePlan.size&&nextReceived%4==0){"Transmit bytesReceived out of bounds for channel $channel"}
   } else {
    require(nextReceived==0){"Unowned transmit channel cannot have non-zero bytesReceived"}
   }
  }
  val writingPrefix=SerialSnapshotCodec.decodeBytesChunked(transmitMap["writingChunksBase64"],nextReceived,"transmit.writingChunksBase64")
  val nextArmCount=SerialSnapshotCodec.parseIntegralLong(transmitMap["armCount"],"transmit.armCount")
  val nextClaimCount=SerialSnapshotCodec.parseIntegralLong(transmitMap["claimCount"],"transmit.claimCount")
  val nextCancelCount=SerialSnapshotCodec.parseIntegralLong(transmitMap["cancelCount"],"transmit.cancelCount")
  val nextCompletedCount=SerialSnapshotCodec.parseIntegralLong(transmitMap["completedCount"],"transmit.completedCount")
  val nextEventCount=SerialSnapshotCodec.parseIntegralLong(transmitMap["eventCount"],"transmit.eventCount")
  require(nextArmCount>=0L&&nextClaimCount>=0L&&nextCancelCount>=0L&&nextCompletedCount>=0L&&nextEventCount>=0L){
   "Negative counter in serial transmit snapshot"
  }
  require(nextCompletedCount==nextPlanIndex.toLong()){"Transmit completedCount must equal planIndex"}
  val totalEvents=try{
   Math.addExact(Math.addExact(nextArmCount,nextClaimCount),Math.addExact(nextCancelCount,nextCompletedCount))
  }catch(_:ArithmeticException){
   throw IllegalArgumentException("Transmit event counter overflow")
  }
  require(totalEvents==nextEventCount){"Transmit counters sum must equal eventCount"}
  val nextNotifications=SerialSnapshotCodec.parseIntegralLong(transmitMap["captureNotifications"],"transmit.captureNotifications")
  val nextNotificationFailures=SerialSnapshotCodec.parseIntegralLong(transmitMap["captureNotificationFailures"],"transmit.captureNotificationFailures")
  val nextFailureName=transmitMap["lastNotificationFailure"]?.let{rawFailure->
   (rawFailure as? String ?: throw IllegalArgumentException("Invalid lastNotificationFailure")).also{text->require(text.length in 1..128)}
  }
  if(!captureEvents) {
   require(nextNotifications==0L&&nextNotificationFailures==0L&&nextFailureName==null){
    "Disabled captureEvents cannot carry notification counts"
   }
  } else {
   require(nextNotifications>=0L&&nextNotificationFailures in 0L..nextNotifications&&(nextNotificationFailures==0L)==(nextFailureName==null)){
    "Inconsistent capture notification counters in snapshot"
   }
  }
  val rawCapture=transmitMap["capture"]
  require((nextCompletedCount==0L)==(rawCapture==null)){"Transmit capture presence disagrees with completedCount"}
  var parsedCapture:TransferCapture?=null
  var capturedPayload:ByteArray?=null
  if(rawCapture!=null) {
   val captureMap=rawCapture as? Map<*,*> ?: throw IllegalArgumentException("Invalid capture object in transmit snapshot")
   require(captureMap.keys==setOf("sequence","size","sha256","completedAt","observedAt","payloadChunksBase64")){
    "Unexpected transmit capture keys"
   }
   val captureSequence=SerialSnapshotCodec.parseIntegralLong(captureMap["sequence"],"capture.sequence")
   val captureSize=SerialSnapshotCodec.parseIntegralInt(captureMap["size"],"capture.size")
   val captureSha256=captureMap["sha256"] as? String ?: throw IllegalArgumentException("Invalid capture.sha256")
   val captureCompletedAt=SerialSnapshotCodec.parseIntegralLong(captureMap["completedAt"],"capture.completedAt")
   val captureObservedAt=SerialSnapshotCodec.parseIntegralLong(captureMap["observedAt"],"capture.observedAt")
   require(captureSequence==nextCompletedCount){"Capture sequence must equal completedCount"}
   require(captureSize==planAt(nextPlanIndex-1).size){"Capture size disagrees with completed plan byteCount"}
   require(captureSha256.matches(Regex("[0-9a-f]{64}"))){"Invalid capture sha256 hex"}
   require(captureCompletedAt>=0L&&captureObservedAt in captureCompletedAt..maxClock){"Invalid capture completion timestamps"}
   val payloadBytes=SerialSnapshotCodec.decodeBytesChunked(captureMap["payloadChunksBase64"],captureSize,"capture.payloadChunksBase64")
   val computedDigest=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payloadBytes))
   require(computedDigest==captureSha256){"Captured payload SHA-256 mismatch"}
   parsedCapture=TransferCapture(captureSequence,captureSize,captureSha256,captureCompletedAt,captureObservedAt)
   capturedPayload=payloadBytes
  }
  val rawEvents=transmitMap["events"] as? List<*> ?: throw IllegalArgumentException("Invalid transmit events list")
  require(rawEvents.size==minOf(nextEventCount,64L).toInt()){"Transmit events log size disagrees with eventCount"}
  val kindNames=listOf("arm","claim","cancel","capture")
  val parsedEvents=rawEvents.map{rawEvent->
   val eventMap=rawEvent as? Map<*,*> ?: throw IllegalArgumentException("Invalid transmit event entry")
   require(eventMap.keys==setOf("kind","tick","dmaChannel","core","pcVirtual")){"Unexpected transmit event entry keys"}
   val kindName=eventMap["kind"] as? String ?: throw IllegalArgumentException("Invalid transmit event kind")
   val kindCode=kindNames.indexOf(kindName)
   require(kindCode>=0){"Unknown transmit event kind $kindName"}
   val entryTick=SerialSnapshotCodec.parseIntegralLong(eventMap["tick"],"transmitEvent.tick")
   require(entryTick in 0L..maxClock){"Transmit event tick out of bounds"}
   val entryOwner=if(eventMap["dmaChannel"]==null)-1 else SerialSnapshotCodec.parseIntegralInt(eventMap["dmaChannel"],"transmitEvent.dmaChannel").also{parsed->require(parsed in 0..15)}
   if(kindCode==0)require(entryOwner==-1){"Arm event must not carry dmaChannel"} else require(entryOwner in 0..15){"DMA event requires dmaChannel"}
   val entryCore=if(eventMap["core"]==null)-1 else SerialSnapshotCodec.parseIntegralInt(eventMap["core"],"transmitEvent.core").also{parsed->require(parsed>=0)}
   val entryPc=if(eventMap["pcVirtual"]==null)-1L else SerialSnapshotCodec.parseIntegralLong(eventMap["pcVirtual"],"transmitEvent.pcVirtual").also{parsed->require(parsed>=0L)}
   ParsedEvent(kindCode,entryTick,entryOwner,entryCore,entryPc)
  }
  return ParsedSnapshot(
   nextPlanIndex,nextArmedAt,nextReadyAt,nextOwner,nextReceived,writingPrefix,
   nextArmCount,nextClaimCount,nextCancelCount,nextCompletedCount,
   nextNotifications,nextNotificationFailures,nextFailureName,
   parsedCapture,capturedPayload,nextEventCount,parsedEvents,
  )
 }
 fun applySnapshot(parsed:ParsedSnapshot) {
  index=parsed.planIndex
  armedAt=parsed.armedAt
  readyAt=parsed.readyAt
  owner=parsed.leaseOwner
  received=parsed.bytesReceived
  writing.fill(0)
  parsed.writingPrefix.copyInto(writing,0)
  captured.fill(0)
  parsed.capturedPayload?.copyInto(captured,0)
  last=parsed.lastCapture
  armCount=parsed.armCount
  claimCount=parsed.claimCount
  cancelCount=parsed.cancelCount
  completed=parsed.completedCount
  captureNotifications=parsed.captureNotifications
  captureNotificationFailures=parsed.captureNotificationFailures
  lastNotificationFailure=parsed.lastNotificationFailure
  eventCount=parsed.eventCount
  eventKinds.fill(0)
  eventTicks.fill(0L)
  eventOwners.fill(-1)
  eventCores.fill(-1)
  eventPcs.fill(-1L)
  val startSequence=maxOf(0L,parsed.eventCount-64)
  for((offset,eventEntry)in parsed.events.withIndex()) {
   val ringSlot=((startSequence+offset)%64).toInt()
   eventKinds[ringSlot]=eventEntry.kindCode
   eventTicks[ringSlot]=eventEntry.tick
   eventOwners[ringSlot]=eventEntry.dmaChannel
   eventCores[ringSlot]=eventEntry.core
   eventPcs[ringSlot]=eventEntry.pcVirtual
  }
 }
 fun state():Map<String,Any?> = (if(captureEvents)mapOf("captureNotifications" to captureNotifications,"captureNotificationFailures" to captureNotificationFailures,"lastNotificationFailure" to lastNotificationFailure)else emptyMap())+mapOf("request" to requestId(channel),"planIndex" to index,"exhausted" to(index==plans.size),"armedAt" to armedAt,"acceptAt" to readyAt,"leaseOwner" to owner.takeIf{it>=0},"bytesReceived" to received,"armCount" to armCount,"claimCount" to claimCount,"cancelCount" to cancelCount,"completedCount" to completed,"capture" to last?.let{mapOf("sequence" to it.sequence,"size" to it.size,"sha256" to it.sha256,"completedAt" to it.completedAt,"observedAt" to it.observedAt)},"eventCount" to eventCount,"eventCapacity" to 64,"eventLogTruncated" to(eventCount>64),"events" to(maxOf(0L,eventCount-64) until eventCount).map{sequence->val i=(sequence%64).toInt();mapOf("kind" to listOf("arm","claim","cancel","capture")[eventKinds[i]],"contextKind" to if(eventKinds[i]==0)"MMIO access observation"else "device observation; not an executed guest instruction","tick" to eventTicks[i],"dmaChannel" to eventOwners[i].takeIf{it>=0},"core" to eventCores[i].takeIf{it>=0},"pcVirtual" to eventPcs[i].takeIf{it>=0})},"basis" to "DAT control-zero, endpoint and request geometry; explicit finite HYP acceptance delay in emulator ticks. Captured bytes are not serial completion IRQ, display delivery or firmware frame.")
}
