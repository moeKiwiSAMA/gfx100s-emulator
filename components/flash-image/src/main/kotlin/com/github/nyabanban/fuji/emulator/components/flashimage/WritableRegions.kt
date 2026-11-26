package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque

/** Disjoint copies with one owner lease across the entire emulated flash, not a contiguous invented image. */
internal class WritableRegions(
 private val context:ComponentContext,
 specification:Map<String,Any?>,
 identities:List<ImageRegionIdentity>,
 seedRead:(Long,ByteBuffer)->Unit,
):WritableImageSetPort,ProgrammableImagePort,AutoCloseable {
 companion object {
  const val PROFILE="explicit-disjoint-copy-on-write-program-experimental-v3"
  private val SNAPSHOT_KEYS = setOf("primaryRegionId", "sequence", "journal", "regions")
  private val JOURNAL_ENTRY_KEYS = setOf("sequence", "regionId", "kind", "receipt")
  private val ERASE_RECEIPT_KEYS = setOf("offset", "size", "changedBytes", "beforeSha256", "afterSha256", "generation")
  private val PROGRAM_RECEIPT_KEYS = setOf("offset", "size", "changedBytes", "payloadSha256", "beforeSha256", "afterSha256", "generation")
  private val HEX_SHA256 = Regex("[0-9a-f]{64}")
 }
 internal class PreparedRestore(
  val sequence: Long,
  val journal: List<Map<String, Any>>,
  val regions: List<WritableRegion.PreparedRestore>,
 )
 override val primaryRegionId:String
 private val regions:List<WritableRegion>
 private val ports:List<RegionPort>
 private val primary:Int
 private var pending:Lease?=null
 private var sequence=0L
 private var closed=false
 private val journal=ArrayDeque<Map<String,Any>>()
 init {
  require(specification.keys==setOf("schema","profile","primaryRegionId","regions")&&integer(specification["schema"])==1L&&specification["profile"]==PROFILE){"explicit writable region-set schema/profile required"}
  primaryRegionId=specification["primaryRegionId"]as? String?:error("primary writable region id required")
  val raw=specification["regions"]as? List<*>?:error("writable region specifications required");require(raw.size in 2..8)
  val specs=raw.map{value->val row=value as? Map<*,*>?:error("writable region must be mapping");require(row.keys.all{it is String});row.entries.associate{it.key.toString() to it.value}}
  val selected=specs.map{row->val id=row["regionId"]as? String?:error("writable region id required");identities.singleOrNull{it.id==id}?:error("writable region seed missing")}
  require(selected.map{it.id}.distinct().size==selected.size&&selected.sumOf{it.length} in 1..32L*1024*1024){"duplicate or excessive writable seed regions"}
  val ordered=selected.sortedBy{it.offset};require(ordered.all{it.offset>=0&&it.length>0&&it.offset<=0x1_0000_0000L-it.length}&&ordered.zipWithNext().all{(a,b)->a.offset+a.length<=b.offset}){"overlapping writable seed regions"}
  primary=selected.indexOfFirst{it.id==primaryRegionId};require(primary>=0){"primary writable region absent"}
  val paths=specs.map{row->val file=row["file"]as? String?:error("copy file required");require(file.matches(Regex("[a-z0-9][a-z0-9._-]{0,120}\\.img"))&&file.substringBefore('.').uppercase() !in setOf("CON","PRN","AUX","NUL")&&!file.substringBefore('.').uppercase().matches(Regex("(COM|LPT)[1-9]")));context.dataPath(file)}
  val reserved=paths.flatMap{listOf(it,context.dataPath(it.fileName.toString()+".lock"))}
  require(paths.distinct().size==paths.size)
  for((i,p)in reserved.withIndex()){
   require(!Files.isSymbolicLink(p)){"writable set symlink refused"}
   for(q in reserved.take(i))require(p!=q&&(!Files.exists(p)||!Files.exists(q)||!Files.isSameFile(p,q))){"writable set copy/lock alias"}
   for(seed in identities){val q=context.dataPath(seed.relativeFile);require(p!=q&&(!Files.exists(p)||!Files.exists(q)||!Files.isSameFile(p,q))){"writable set aliases immutable seed"}}
  }
  // Preflight every specification and existing snapshot before creating the first output.
  for((i,row)in specs.withIndex()){
   require(row.keys==setOf("schema","profile","regionId","file","mode","sha256","writableOffset","writableLength","programLaw","programPageBytes"))
   require(integer(row["schema"])==1L&&row["profile"]==WritableRegion.PROGRAM_PROFILE&&row["programLaw"]==WritableRegion.PROGRAM_LAW&&integer(row["programPageBytes"])==256L)
   val seed=selected[i];val at=integer(row["writableOffset"]);val n=integer(row["writableLength"])
   require(seed.length in 1..32L*1024*1024&&at>=seed.offset&&n in 4096..seed.length&&at<=seed.offset+seed.length-n&&at%4096==0L&&n%4096==0L)
   val sha=row["sha256"]as? String?:error("copy SHA required");require(sha.matches(Regex("[0-9a-f]{64}")))
   when(row["mode"]){
    "create"->require(sha==seed.sha256&&!Files.exists(paths[i],NOFOLLOW_LINKS)){"writable set create identity/path mismatch"}
    "resume"->{require(Files.isRegularFile(paths[i],NOFOLLOW_LINKS)&&Files.size(paths[i])==seed.length);val copy=Files.readAllBytes(paths[i]);require(hash(copy)==sha);val original=ByteArray(seed.length.toInt());val view=ByteBuffer.wrap(original);seedRead(seed.offset,view);check(!view.hasRemaining());require(hash(original)==seed.sha256);val begin=(at-seed.offset).toInt();val end=begin+n.toInt();require((0 until begin).all{copy[it]==original[it]}&&(end until copy.size).all{copy[it]==original[it]})}
    else->error("writable set create/resume mode required")
   }
  }
  val built=mutableListOf<WritableRegion>()
  try{for(row in specs)built.add(WritableRegion(context,row,identities,seedRead))}catch(error:Throwable){for(region in built.asReversed())try{region.close()}catch(close:Throwable){error.addSuppressed(close)};throw error}
  regions=built.toList();ports=regions.indices.map{RegionPort(it)}
 }
 private fun integer(v:Any?):Long {require(v is Byte||v is Short||v is Int||v is Long);return(v as Number).toLong()}
 private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
 fun checkLive(){check(!closed){"writable region set closed"};regions.forEach{it.checkLive()}}
 override fun snapshot()=regions[primary].snapshot()
 override fun regionSnapshots()=regions.map{it.snapshot()}
 override fun regionForWrite(offset:Long,size:Long):WritableImagePort {
  checkLive();require(offset>=0&&size>0&&size<=0x1_0000_0000L&&offset<=0x1_0000_0000L-size){"writable set range overflow"}
  check(pending==null){"writable region set transaction already pending"}
  val indices=regions.indices.filter{i->val s=regions[i].snapshot();offset>=s.writableOffset&&size<=s.writableLength&&offset<=s.writableOffset+s.writableLength-size}
  require(indices.size==1){"write outside one explicit writable region"};return ports[indices.single()]
 }
 fun overlay(offset:Long,destination:ByteBuffer,start:Int,count:Int){checkLive();regions.forEach{it.overlay(offset,destination,start,count)}}
 fun primaryHistory()=regions[primary].history()
 fun primaryProgramHistory()=regions[primary].programHistory()
 override fun prepareErase(offset:Long,size:Long)=regionForWrite(offset,size).prepareErase(offset,size)
 override fun prepareProgram(offset:Long,payload:ByteBuffer):ImageProgramTransaction {
  val port=regionForWrite(offset,payload.remaining().toLong())as ProgrammableImagePort;return port.prepareProgram(offset,payload)
 }
 private inner class RegionPort(private val index:Int):WritableImagePort,ProgrammableImagePort {
  override fun snapshot()=regions[index].snapshot()
  private fun checkSelection(offset:Long,size:Long){check(regionForWrite(offset,size)===this){"writable region handle/range mismatch"}}
  override fun prepareErase(offset:Long,size:Long):ImageEraseTransaction {
   checkSelection(offset,size);val next=Math.addExact(sequence,1);val delegate=regions[index].prepareErase(offset,size)
   val lease=Lease(index,delegate,next);pending=lease
   return object:ImageEraseTransaction {
    override val offset=delegate.offset;override val size=delegate.size
    override fun commit():ImageEraseReceipt {lease.requireOwned();val receipt=delegate.commit();lease.completed("erase",mapOf("offset" to receipt.offset,"size" to receipt.size,"changedBytes" to receipt.changedBytes,"beforeSha256" to receipt.beforeSha256,"afterSha256" to receipt.afterSha256,"generation" to receipt.generation));return receipt}
    override fun close()=lease.close()
   }
  }
  override fun prepareProgram(offset:Long,payload:ByteBuffer):ImageProgramTransaction {
   checkSelection(offset,payload.remaining().toLong());val next=Math.addExact(sequence,1);val delegate=regions[index].prepareProgram(offset,payload)
   val lease=Lease(index,delegate,next);pending=lease
   return object:ImageProgramTransaction {
    override val offset=delegate.offset;override val size=delegate.size
    override fun commit():ImageProgramReceipt {lease.requireOwned();val receipt=delegate.commit();lease.completed("program",mapOf("offset" to receipt.offset,"size" to receipt.size,"changedBytes" to receipt.changedBytes,"payloadSha256" to receipt.payloadSha256,"beforeSha256" to receipt.beforeSha256,"afterSha256" to receipt.afterSha256,"generation" to receipt.generation));return receipt}
    override fun close()=lease.close()
   }
  }
 }
 private inner class Lease(val region:Int,val delegate:AutoCloseable,val next:Long):AutoCloseable {
  private var ended=false
  fun requireOwned(){checkLive();check(!ended&&pending===this){"stale writable region-set transaction"}}
  fun completed(kind:String,receipt:Map<String,Any>){sequence=next;ended=true;pending=null;if(journal.size==128)journal.removeFirst();journal.addLast(mapOf("sequence" to sequence,"regionId" to regions[region].snapshot().regionId,"kind" to kind,"receipt" to receipt))}
  override fun close(){if(!ended&&pending===this){delegate.close();ended=true;pending=null}}
 }
 fun state():Map<String,Any?> = mapOf("profile" to PROFILE,"primaryRegionId" to primaryRegionId,"sequence" to sequence,"pendingRegionId" to pending?.let{regions[it.region].snapshot().regionId},"closed" to closed,"journal" to journal.toList(),"journalTruncated" to(sequence>journal.size),"regions" to regions.map{mapOf("snapshot" to it.snapshot(),"eraseHistory" to it.history(),"programHistory" to it.programHistory(),"programLaw" to WritableRegion.PROGRAM_LAW,"programPageBytes" to 256)})
 fun saveSnapshot(): Map<String, Any?> {
  checkLive()
  check(pending == null) { "Cannot snapshot writable region set with pending transaction" }
  return linkedMapOf(
   "primaryRegionId" to primaryRegionId,
   "sequence" to sequence,
   "journal" to journal.map { entry -> LinkedHashMap(entry) },
   "regions" to regions.map { region -> region.saveSnapshot() },
  )
 }
 fun preflightRestore(raw: Any?): PreparedRestore {
  checkLive()
  check(pending == null) { "Cannot restore writable region set with pending transaction" }
  val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing WritableRegions snapshot")
  require(row.keys == SNAPSHOT_KEYS) { "Unexpected WritableRegions snapshot keys" }
  require(row["primaryRegionId"] == primaryRegionId) { "WritableRegions primaryRegionId mismatch" }
  val nextSequence = integer(row["sequence"])
  require(nextSequence >= 0L) { "Negative WritableRegions sequence" }
  val rawRegions = row["regions"] as? List<*> ?: throw IllegalArgumentException("Missing WritableRegions regions list")
  require(rawRegions.size == regions.size) { "WritableRegions count mismatch" }
  val preparedRegions = regions.indices.map { regionIndex ->
   regions[regionIndex].preflightRestore(rawRegions[regionIndex])
  }
  require(preparedRegions.sumOf { prepared -> prepared.expectedGeneration } == nextSequence) {
   "WritableRegions sequence differs from sum of per-region generations"
  }
  val rawJournal = row["journal"] as? List<*> ?: throw IllegalArgumentException("Missing WritableRegions journal list")
  val expectedJournalSize = minOf(nextSequence, 128L).toInt()
  require(rawJournal.size == expectedJournalSize) { "WritableRegions journal size inconsistent with sequence" }
  val knownRegionIds = regions.map { region -> region.snapshot().regionId }.toSet()
  val firstExpectedSequence = nextSequence - expectedJournalSize + 1L
  val restoredJournal = rawJournal.mapIndexed { journalIndex, rawEntry ->
   val entry = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid journal entry at $journalIndex")
   require(entry.keys == JOURNAL_ENTRY_KEYS) { "Unexpected journal entry keys at $journalIndex" }
   val entrySequence = integer(entry["sequence"])
   require(entrySequence == firstExpectedSequence + journalIndex) { "Non-sequential journal sequence at $journalIndex" }
   val regionId = entry["regionId"] as? String ?: throw IllegalArgumentException("Missing journal regionId at $journalIndex")
   require(regionId in knownRegionIds) { "Unknown journal regionId at $journalIndex: $regionId" }
   val kind = entry["kind"] as? String ?: throw IllegalArgumentException("Missing journal kind at $journalIndex")
   val receiptMap = entry["receipt"] as? Map<*, *> ?: throw IllegalArgumentException("Missing journal receipt at $journalIndex")
   val normalizedReceipt = when (kind) {
    "erase" -> {
     require(receiptMap.keys == ERASE_RECEIPT_KEYS) { "Unexpected journal erase receipt keys at $journalIndex" }
     linkedMapOf<String, Any>(
      "offset" to integer(receiptMap["offset"]),
      "size" to integer(receiptMap["size"]),
      "changedBytes" to integer(receiptMap["changedBytes"]),
      "beforeSha256" to requireSha256(receiptMap["beforeSha256"]),
      "afterSha256" to requireSha256(receiptMap["afterSha256"]),
      "generation" to integer(receiptMap["generation"]),
     )
    }
    "program" -> {
     require(receiptMap.keys == PROGRAM_RECEIPT_KEYS) { "Unexpected journal program receipt keys at $journalIndex" }
     val programSize = integer(receiptMap["size"])
     require(programSize in 1L..256L) { "Invalid journal program receipt size at $journalIndex" }
     linkedMapOf<String, Any>(
      "offset" to integer(receiptMap["offset"]),
      "size" to programSize.toInt(),
      "changedBytes" to integer(receiptMap["changedBytes"]),
      "payloadSha256" to requireSha256(receiptMap["payloadSha256"]),
      "beforeSha256" to requireSha256(receiptMap["beforeSha256"]),
      "afterSha256" to requireSha256(receiptMap["afterSha256"]),
      "generation" to integer(receiptMap["generation"]),
     )
    }
    else -> throw IllegalArgumentException("Unsupported journal kind at $journalIndex: $kind")
   }
   linkedMapOf<String, Any>(
    "sequence" to entrySequence,
    "regionId" to regionId,
    "kind" to kind,
    "receipt" to normalizedReceipt,
   )
  }
  return PreparedRestore(nextSequence, restoredJournal, preparedRegions)
 }
 fun commitRestore(prepared: PreparedRestore) {
  checkLive()
  check(pending == null) { "Cannot commit WritableRegions restore with pending transaction" }
  val stagedPaths = ArrayList<Path?>(regions.size)
  try {
   for (regionIndex in regions.indices) {
    stagedPaths.add(regions[regionIndex].stageRestore(prepared.regions[regionIndex]))
   }
  } catch (error: Throwable) {
   for (staged in stagedPaths) {
    if (staged != null) runCatching { Files.deleteIfExists(staged) }
   }
   throw error
  }
  for (regionIndex in regions.indices) {
   regions[regionIndex].commitRestore(prepared.regions[regionIndex], stagedPaths[regionIndex])
  }
  sequence = prepared.sequence
  journal.clear()
  journal.addAll(prepared.journal)
 }
 private fun requireSha256(value: Any?): String {
  require(value is String && HEX_SHA256.matches(value)) { "Expected SHA-256 hex string" }
  return value
 }
 override fun close(){if(closed)return;var error:Throwable?=null;try{pending?.close()}catch(e:Throwable){error=e};for(region in regions.asReversed())try{region.close()}catch(e:Throwable){if(error==null)error=e else error.addSuppressed(e)};closed=true;if(error!=null)throw error}
}
