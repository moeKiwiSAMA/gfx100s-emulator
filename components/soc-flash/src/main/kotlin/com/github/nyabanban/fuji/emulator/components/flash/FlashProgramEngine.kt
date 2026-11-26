package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.util.Base64

/** Explicit v4 functional hypothesis. Command acceptance, DMA staging and array commit are distinct. */
internal class FlashProgramEngine(
 private val context:ComponentContext,private val registers:LongArray,
 private val aperture:()->AddressWindow,private val image:()->ReadOnlyImagePort,
 private val mutable:()->WritableImagePort,private val program:()->ProgrammableImagePort,
 private val enables:PioEnableStatus,private val otherBusy:()->Boolean,
 private val preflightRecord:()->Unit,private val record:(Map<String,Any>)->Unit
):RangedDmaDestinationPeer,AutoCloseable {
 private data class Pending(val address:Long,val size:Int,val submittedAt:Long,val commandAt:Long,var acceptedAt:Long?=null,var owner:Int?=null,var received:Int=0,var transaction:ImageProgramTransaction?=null,var arrayAt:Long?=null)
 private var pending:Pending?=null
 private val data=ByteArray(256)
 private var closed=false
 private var failure:String?=null
 private var committed=0L
 internal class PreparedSnapshot(
  val committedCount: Long,
  val bufferBytes: ByteArray,
  val pendingAddress: Long?,
  val pendingSize: Int,
  val pendingSubmittedAt: Long,
  val pendingCommandAt: Long,
  val pendingAcceptedAt: Long?,
  val pendingOwner: Int?,
  val pendingReceived: Int,
 ) {
  val hasPending: Boolean get() = pendingAddress != null
 }
 fun ensureLive()=live()
 fun isBusy()=pending!=null
 fun hasStorageLease()=pending?.transaction!=null
 fun committedCount()=committed
 private fun live(){check(!closed){"flash program closed"};check(failure==null){"flash program failed: "+failure};enables.live()}
 override fun destinationRanges():List<AddressWindow> = listOf(aperture())
 override fun matches(address:Long):Boolean {val a=aperture();return address>=a.base&&address-a.base<a.size}
 private fun bounds(address:Long,size:Long) {
  val a=aperture();require(size in 4..256&&size%4==0L&&address%4==0L&&address>=a.base&&size<=a.size&&address<=a.base+a.size-size&&address/256==(address+size-1)/256){"flash program endpoint/length/software-page differs"}
  image().check(address-a.base,size)
  val writable=mutable();val selected=(writable as? WritableImageSetPort)?.regionForWrite(address-a.base,size)?:writable;val state=selected.snapshot();require(!state.closed&&state.failure==null&&!state.pending&&address-a.base>=state.writableOffset&&size<=state.writableLength&&address-a.base<=state.writableOffset+state.writableLength-size){"flash program outside explicit mutable permission or image busy"}
  program() // Required optional capability, never silently use erase or a different backend.
 }
 override fun check(address:Long,size:Long,width:Int,request:Int) {
  live();require(width==4&&request==43){"flash program DMA width/request differs"};bounds(address,size)
  val p=pending
  if(p==null){check(!otherBusy()&&enables.isEnabled(0)&&registers[2]==0x12L){"flash program DMA requires configured opcode12 and completed WREN"}}
  else require(p.address==address&&p.size.toLong()==size&&p.transaction==null){"flash program DMA differs from pending command or data already staged"}
 }
 fun checkRegisterWrite(offset:Int,value:Long) {
  live()
  if(pending!=null)require(offset !in setOf(8,0x1c,0x20,0x74,0x78,0x7c,0x80)){"flash program reconfiguration while busy"}
  if(offset==0x78){val a=aperture();require(value>=a.base&&value<a.base+a.size&&value%4==0L){"flash program raw address outside aperture"}}
  if(offset==0x7c)require(value in 4..256&&value%4==0L){"flash program length outside software page"}
 }
 fun writeControl(value:Long) {
  live()
  // Bit 5 (0x20) is a hardware W1C status flag latched at program-command-accepted; concurrent DMA +
  // array commit may complete (clearing `pending`) before guest code at 0x011e037c clears bit 5.
  if(value==0x20L){require(registers[0x70/4] and 0x20!=0L){"flash program accept ACK without accepted request"};preflightRecord();registers[0x70/4]=registers[0x70/4] and 0x20L.inv();record(mapOf("operation" to "program-accept-ack","tick" to context.clock.ticks()));return}
  require(value==1L&&pending==null&&!otherBusy()){ "unsupported or busy flash program control" }
  require(registers[0] and 0x4000bc00L==0x8000L&&registers[2]==0x12L&&registers[8]==0x202L&&registers[29]==0xffffffffL&&registers[32]==15L&&registers[7]==registers[30]&&registers[56] and 255==0L&&registers[60]==0L){"flash program DAT descriptor/selector/packing differs"}
  require(enables.isEnabled(0)){"flash program requires completed write enable"}
  val address=registers[30];val size=registers[31];bounds(address,size)
  val now=context.clock.ticks();require(now>=0);val commandAt=Math.addExact(now,8L);Math.addExact(commandAt,16+(size+15)/16);Math.addExact(commandAt,4096L);Math.addExact(committed,1L);preflightRecord()
  pending=Pending(address,size.toInt(),now,commandAt);registers[28]=1
  record(mapOf("operation" to "program-submit","tick" to now,"commandDeadline" to commandAt,"address" to address,"logicalOffset" to(address-aperture().base),"size" to size))
 }
 fun nextDeadline():Long? = pending?.let{if(it.acceptedAt==null)it.commandAt else it.arrayAt}
 fun advance(deadline:Long,observedAt:Long) {
  live();require(observedAt>=deadline);val p=checkNotNull(pending)
  if(p.acceptedAt==null){check(deadline==p.commandAt);preflightRecord();enables.consumeEnable(0);p.acceptedAt=deadline;registers[28]=(registers[28] and 1L.inv()) or 0x20
   record(mapOf("operation" to "program-command-accepted","deadline" to deadline,"observedAt" to observedAt,"address" to p.address,"size" to p.size));return}
  check(deadline==p.arrayAt&&p.transaction!=null)
  val next=Math.addExact(committed,1L);preflightRecord()
  val receipt=try{checkNotNull(p.transaction).commit()}catch(error:Throwable){failure=error.toString();throw error}
  pending=null;committed=next
  record(mapOf("operation" to "program-array-complete","deadline" to deadline,"observedAt" to observedAt,"offset" to receipt.offset,"size" to receipt.size,"changedBytes" to receipt.changedBytes,"payloadSha256" to receipt.payloadSha256,"beforeSha256" to receipt.beforeSha256,"afterSha256" to receipt.afterSha256,"generation" to receipt.generation))
 }
 override fun acceptedAt():Long? {live();return pending?.takeIf{it.transaction==null}?.acceptedAt}
 override fun claim(channel:Int,address:Long,size:Long,width:Int,request:Int):Long? {
  require(channel in 0..15);check(address,size,width,request);val p=pending?:return null;val accepted=p.acceptedAt?:return null;check(p.owner==null||p.owner==channel){"flash program request owned by another DMA"};p.owner=channel;return accepted
 }
 override fun release(channel:Int){require(channel in 0..15);if(closed)return;pending?.let{p->if(p.owner==channel&&p.transaction==null){p.owner=null;p.received=0}}}
 private fun owned(channel:Int,address:Long):Pending {live();val p=checkNotNull(pending);check(channel in 0..15&&p.owner==channel&&p.acceptedAt!=null&&p.transaction==null&&p.address==address){"flash program DMA lease differs"};return p}
 override fun write(channel:Int,address:Long,offset:Long,source:ByteBuffer) {val p=owned(channel,address);val n=source.remaining();require(n>0&&n%4==0&&offset==p.received.toLong()&&n<=p.size-p.received){"flash program DMA stream bounds/order differs"};source.get(data,p.received,n);p.received+=n}
 override fun readBack(channel:Int,address:Long,offset:Long,destination:ByteBuffer) {val p=owned(channel,address);val n=destination.remaining();require(!destination.isReadOnly&&n>0&&offset>=0&&offset<=p.received.toLong()-n){"flash program readback differs"};destination.put(data,offset.toInt(),n)}
 override fun finish(channel:Int,completedAt:Long,observedAt:Long) {
  live();val p=checkNotNull(pending);check(p.owner==channel&&p.received==p.size&&p.transaction==null);require(completedAt>=checkNotNull(p.acceptedAt)&&observedAt>=completedAt&&observedAt==context.clock.ticks())
  val at=Math.addExact(observedAt,4096L);Math.addExact(committed,1L);preflightRecord()
  val transaction=try{program().prepareProgram(p.address-aperture().base,ByteBuffer.wrap(data,0,p.size))}catch(error:Throwable){failure=error.toString();throw error}
  p.transaction=transaction;p.arrayAt=at;p.owner=null
  record(mapOf("operation" to "program-dma-staged","completedAt" to completedAt,"observedAt" to observedAt,"arrayDeadline" to at,"address" to p.address,"size" to p.size))
 }
 override fun close(){if(closed)return;pending?.transaction?.close();pending=null;closed=true}
 fun state():Map<String,Any?> = mapOf("commandTicks" to 8,"arrayTicksAfterObservedDma" to 4096,"committed" to committed,"failure" to failure,"closed" to closed,"pending" to pending?.let{mapOf("address" to it.address,"size" to it.size,"submittedAt" to it.submittedAt,"commandDeadline" to it.commandAt,"acceptedAt" to it.acceptedAt,"owner" to it.owner,"received" to it.received,"arrayDeadline" to it.arrayAt,"prepared" to(it.transaction!=null))},"scope" to "HYP explicit opcode12/selector0/request43/software256byte page; command8 and array4096 after actual observed DMA staging. No physical NOR/timing/reset/program IRQ/power-loss qualification.")
 fun saveSnapshot(): Map<String, Any?> {
  live()
  val currentPending = pending
  check(currentPending?.transaction == null && currentPending?.arrayAt == null) {
   "Cannot snapshot flash program engine with uncommitted storage transaction"
  }
  return linkedMapOf(
   "committed" to committed,
   "stagingBufferBase64" to Base64.getEncoder().encodeToString(data),
   "pending" to currentPending?.let { active ->
    linkedMapOf<String, Any?>(
     "address" to active.address,
     "size" to active.size,
     "submittedAt" to active.submittedAt,
     "commandDeadline" to active.commandAt,
     "acceptedAt" to active.acceptedAt,
     "owner" to active.owner,
     "received" to active.received,
    )
   },
  )
 }
 fun preflightSnapshot(raw: Any?): PreparedSnapshot {
  live()
  check(pending?.transaction == null) { "Cannot restore flash program engine while storage transaction is open" }
  val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing FlashProgramEngine snapshot")
  require(row.keys == setOf("committed", "stagingBufferBase64", "pending")) {
   "Unexpected FlashProgramEngine snapshot keys"
  }
  val nextCommitted = requireLong(row["committed"], "programEngine.committed")
  require(nextCommitted >= 0L) { "Negative FlashProgramEngine committed count" }
  val encodedBuffer = row["stagingBufferBase64"] as? String
   ?: throw IllegalArgumentException("Missing FlashProgramEngine stagingBufferBase64")
  val decodedBuffer = Base64.getDecoder().decode(encodedBuffer)
  require(decodedBuffer.size == data.size) { "FlashProgramEngine staging buffer size mismatch" }
  val rawPending = row["pending"]
  if (rawPending == null) {
   return PreparedSnapshot(nextCommitted, decodedBuffer, null, 0, 0L, 0L, null, null, 0)
  }
  val pendingMap = rawPending as? Map<*, *> ?: throw IllegalArgumentException("Invalid FlashProgramEngine pending map")
  require(pendingMap.keys == setOf("address", "size", "submittedAt", "commandDeadline", "acceptedAt", "owner", "received")) {
   "Unexpected FlashProgramEngine pending keys"
  }
  val pendingAddress = requireLong(pendingMap["address"], "programEngine.pending.address")
  val pendingSize = requireInt(pendingMap["size"], "programEngine.pending.size")
  val pendingSubmittedAt = requireLong(pendingMap["submittedAt"], "programEngine.pending.submittedAt")
  val pendingCommandAt = requireLong(pendingMap["commandDeadline"], "programEngine.pending.commandDeadline")
  val pendingAcceptedAt = pendingMap["acceptedAt"]?.let { value -> requireLong(value, "programEngine.pending.acceptedAt") }
  val pendingOwner = pendingMap["owner"]?.let { value -> requireInt(value, "programEngine.pending.owner") }
  val pendingReceived = requireInt(pendingMap["received"], "programEngine.pending.received")
  require(pendingSize in 4..256 && pendingSize % 4 == 0) { "Invalid FlashProgramEngine pending size" }
  require(pendingAddress >= 0L && pendingAddress % 4 == 0L && pendingAddress <= 0x1_0000_0000L - pendingSize && pendingAddress / 256 == (pendingAddress + pendingSize - 1) / 256) {
   "Invalid FlashProgramEngine pending address/page alignment"
  }
  if (context.ports.find("board.layout", AddressLayout::class.java) != null) {
   val window = aperture()
   require(pendingAddress >= window.base && pendingSize.toLong() <= window.size && pendingAddress <= window.base + window.size - pendingSize) {
    "FlashProgramEngine pending address outside aperture"
   }
  }
  require(pendingSubmittedAt >= 0L && pendingCommandAt >= pendingSubmittedAt) {
   "Invalid FlashProgramEngine pending command timing"
  }
  if (pendingAcceptedAt == null) {
   require(pendingOwner == null && pendingReceived == 0) {
    "Unaccepted FlashProgramEngine request cannot carry owner or staged bytes"
   }
  } else {
   require(pendingAcceptedAt >= pendingCommandAt) { "FlashProgramEngine acceptedAt precedes commandDeadline" }
   require(pendingOwner == null || pendingOwner in 0..15) { "Invalid FlashProgramEngine DMA owner" }
   require(pendingReceived in 0..pendingSize && pendingReceived % 4 == 0) { "Invalid FlashProgramEngine received byte count" }
   if (pendingReceived > 0) {
    require(pendingOwner != null) { "FlashProgramEngine staged bytes require active DMA owner" }
   }
  }
  return PreparedSnapshot(
   committedCount = nextCommitted,
   bufferBytes = decodedBuffer,
   pendingAddress = pendingAddress,
   pendingSize = pendingSize,
   pendingSubmittedAt = pendingSubmittedAt,
   pendingCommandAt = pendingCommandAt,
   pendingAcceptedAt = pendingAcceptedAt,
   pendingOwner = pendingOwner,
   pendingReceived = pendingReceived,
  )
 }
 fun applySnapshot(prepared: PreparedSnapshot) {
  live()
  check(pending?.transaction == null) { "Cannot overwrite open FlashProgramEngine storage transaction" }
  prepared.bufferBytes.copyInto(data)
  committed = prepared.committedCount
  pending = prepared.pendingAddress?.let { address ->
   Pending(
    address = address,
    size = prepared.pendingSize,
    submittedAt = prepared.pendingSubmittedAt,
    commandAt = prepared.pendingCommandAt,
    acceptedAt = prepared.pendingAcceptedAt,
    owner = prepared.pendingOwner,
    received = prepared.pendingReceived,
   )
  }
 }
 companion object {
  private fun requireLong(value: Any?, field: String): Long {
   require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
   return (value as? Number)?.toLong() ?: error("Unreachable integer conversion for $field")
  }
  private fun requireInt(value: Any?, field: String): Int {
   val number = requireLong(value, field)
   require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out-of-range int for $field" }
   return number.toInt()
  }
 }
}
