package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64

/**
 * Explicit single-region experimental writable copy. Hot reads are frozen byte-array bulk copies.
 * Erase commits use force(temp)+atomic replace; unsupported atomic move fails, with no unsafe fallback.
 * This qualifies process close/reopen persistence, not power-loss durability or physical NOR behavior.
 */
internal class WritableRegion(
    private val context: ComponentContext,
    specification: Map<String, Any?>,
    identities: List<ImageRegionIdentity>,
    private val seedRead: (Long, ByteBuffer) -> Unit,
) : WritableImagePort, ProgrammableImagePort, AutoCloseable {
    internal class PreparedRestore(
        val candidate: ByteArray,
        val expectedSha: String,
        val expectedGeneration: Long,
        val eraseReceipts: List<ImageEraseReceipt>,
        val programReceipts: List<ImageProgramReceipt>,
    )
    private val identity: ImageRegionIdentity
    private val writableOffset: Long
    private val writableLength: Long
    private val file: String
    private val path: java.nio.file.Path
    private val lockChannel: FileChannel
    private val lock: FileLock
    private var bytes: ByteArray
    private var sha: String
    private var shaDirty = false
    internal var fullShaComputeCount = 0L
        private set
    private var generation = 0L
    private var pending: AutoCloseable? = null
    private var closed = false
    private var failure: String? = null
    private val receipts = ArrayDeque<ImageEraseReceipt>()
    private val programReceipts = ArrayDeque<ImageProgramReceipt>()
    val programEnabled = specification["profile"] == PROGRAM_PROFILE
    init {
        val baseKeys=setOf("schema","profile","regionId","file","mode","sha256","writableOffset","writableLength")
        require(specification.keys == baseKeys + if(programEnabled)setOf("programLaw","programPageBytes")else emptySet())
        require(integer(specification["schema"])==1L && specification["profile"] in listOf(PROFILE,PROGRAM_PROFILE))
        if(programEnabled)require(specification["programLaw"]==PROGRAM_LAW&&integer(specification["programPageBytes"])==256L){"explicit qualified software page and HYP program law required"}
        val id=specification["regionId"] as? String ?: error("writable region id required")
        identity=identities.singleOrNull{it.id==id} ?: error("writable copy requires one supplied immutable region")
        require(identity.length in 1..32L*1024*1024)
        writableOffset=integer(specification["writableOffset"]);writableLength=integer(specification["writableLength"])
        require(writableOffset>=identity.offset&&writableLength in 4096..identity.length&&writableOffset<=identity.offset+identity.length-writableLength&&writableOffset%4096==0L&&writableLength%4096==0L) { "explicit aligned writable subrange required" }
        file=specification["file"] as? String ?: error("writable copy filename required")
        require(file.matches(Regex("[a-z0-9][a-z0-9._-]{0,120}\\.img"))) { "portable writable copy leaf filename required" }
        require(file.substringBefore('.').uppercase() !in setOf("CON","PRN","AUX","NUL") &&
            !file.substringBefore('.').uppercase().matches(Regex("(COM|LPT)[1-9]")))
        val mode=specification["mode"];require(mode in listOf("create","resume"))
        val expected=specification["sha256"] as? String ?: error("explicit writable snapshot SHA required")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        if(mode=="create")require(expected==identity.sha256) { "create SHA must identify immutable seed" }
        path=context.dataPath(file)
        require(!Files.isSymbolicLink(path)) { "writable copy symlink refused" }
        for(source in identities) {
            val sourcePath=context.dataPath(source.relativeFile)
            require(path!=sourcePath && (!Files.exists(path)||!Files.isSameFile(path,sourcePath))) { "writable copy aliases immutable source" }
        }
        if(mode=="create")require(!Files.exists(path,NOFOLLOW_LINKS)) { "new writable copy already exists" }
        else require(Files.isRegularFile(path,NOFOLLOW_LINKS)&&Files.size(path)==identity.length) { "resume copy length/type mismatch" }
        val lockPath=context.dataPath(file+".lock");require(!Files.isSymbolicLink(lockPath))
        lockChannel=FileChannel.open(lockPath,CREATE,WRITE)
        var acquired: FileLock?=null
        try {
            acquired=lockChannel.tryLock() ?: error("writable copy already leased")
            lock=acquired
            val initial=ByteArray(identity.length.toInt());val view=ByteBuffer.wrap(initial);seedRead(identity.offset,view);check(!view.hasRemaining())
            require(hash(initial)==identity.sha256) { "writable seed SHA mismatch" }
            bytes=if(mode=="create") {
                FileChannel.open(path,CREATE_NEW,WRITE).use{channel->writeAll(channel,initial);channel.force(true)}
                initial
            } else Files.readAllBytes(path)
            require(bytes.size.toLong()==identity.length&&hash(bytes)==expected) { "writable snapshot SHA mismatch" }
            val allowedStart=(writableOffset-identity.offset).toInt();val allowedEnd=allowedStart+writableLength.toInt()
            require((0 until allowedStart).all{bytes[it]==initial[it]}&&(allowedEnd until bytes.size).all{bytes[it]==initial[it]}) { "resume changed bytes outside writable subrange" }
            sha=expected
        } catch(error:Throwable) { acquired?.close();lockChannel.close();throw error }
    }
    private fun live() { check(!closed) { "writable image closed" };check(failure==null) { "writable image failed: "+failure } }
    fun checkLive()=live()
    internal val isShaDirty: Boolean get() = shaDirty
    private fun ensureCurrentSha(): String {
        if (shaDirty) {
            sha = digestBytes(bytes)
            shaDirty = false
            fullShaComputeCount++
        }
        return sha
    }
    internal fun ensureSha256(): String {
        live()
        return ensureCurrentSha()
    }
    override fun snapshot(): WritableImageState {
        val currentSha = if (context.relaxedMode) {
            if (shaDirty) DEFERRED_SHA256 else sha
        } else {
            ensureCurrentSha()
        }
        return WritableImageState(identity.id,identity.offset,identity.length,file,writableOffset,writableLength,identity.sha256,currentSha,generation,pending!=null,closed,failure)
    }
    fun history(): List<ImageEraseReceipt> = receipts.toList()
    fun programHistory(): List<ImageProgramReceipt> = programReceipts.toList()
    /** Applied after the immutable base copy, without allocating, changing destination position, or exposing arrays. */
    fun overlay(offset:Long,destination:ByteBuffer,start:Int,count:Int) {
        live()
        val begin=maxOf(offset,identity.offset);val end=minOf(offset+count,identity.offset+identity.length)
        if(begin<end)destination.put(start+(begin-offset).toInt(),bytes,(begin-identity.offset).toInt(),(end-begin).toInt())
    }
    private val readScratch = ByteArray(65536)
    private val shaDigest = MessageDigest.getInstance("SHA-256")
    private fun digestBytes(data: ByteArray): String {
        shaDigest.reset()
        return HEX.formatHex(shaDigest.digest(data))
    }
    private fun matchesDiskBytes(expected: ByteArray): Boolean {
        val len = expected.size
        var pos = 0
        FileChannel.open(path, READ).use { channel ->
            if (channel.size() != len.toLong()) return false
            val buf = ByteBuffer.wrap(readScratch)
            while (pos < len) {
                buf.clear()
                val want = minOf(readScratch.size, len - pos)
                buf.limit(want)
                val n = channel.read(buf)
                if (n <= 0) return false
                if (!java.util.Arrays.equals(readScratch, 0, n, expected, pos, pos + n)) return false
                pos += n
            }
            buf.clear()
            buf.limit(1)
            return channel.read(buf) <= 0
        }
    }
    private fun unchangedDisk() {
        require(!Files.isSymbolicLink(path)&&context.dataPath(file)==path&&Files.isRegularFile(path,NOFOLLOW_LINKS)&&Files.size(path)==identity.length) { "writable copy identity/length changed" }
        require(matchesDiskBytes(bytes)) { "writable copy changed externally" }
    }
    override fun prepareErase(offset:Long,size:Long):ImageEraseTransaction {
        live();check(pending==null) { "writable image transaction already pending" }
        require((size == 4096L || size == 65536L)&&offset>=writableOffset&&offset%size==0L&&size<=writableLength&&offset<=writableOffset+writableLength-size) { "erase outside explicit writable region or software granule" }
        val next=Math.addExact(generation,1)
        unchangedDisk()
        return Transaction(offset,size,next).also{pending=it}
    }
    private inner class Transaction(override val offset:Long,override val size:Long,val next:Long):ImageEraseTransaction {
        private var ended=false
        override fun commit():ImageEraseReceipt {
            live();check(!ended&&pending===this) { "stale writable transaction" }
            val old=if (context.relaxedMode) DEFERRED_SHA256 else ensureCurrentSha()
            try {
                unchangedDisk()
                val candidate=bytes.clone();var changed=0L;val start=(offset-identity.offset).toInt()
                repeat(size.toInt()){at->if(candidate[start+at]!=0xff.toByte())changed++;candidate[start+at]=0xff.toByte()}
                val nextSha=if (context.relaxedMode) {
                    DEFERRED_SHA256
                } else if (changed > 0L) {
                    fullShaComputeCount++
                    digestBytes(candidate)
                } else {
                    old
                }
                if(changed>0) {
                    persist(candidate,next,"erase")
                }
                bytes=candidate
                if (context.relaxedMode) {
                    if (changed > 0L) shaDirty = true
                } else {
                    sha = nextSha
                    shaDirty = false
                }
                generation=next;ended=true;pending=null
                return ImageEraseReceipt(offset,size,changed,old,nextSha,generation).also{if(receipts.size==64)receipts.removeFirst();receipts.addLast(it)}
            }catch(error:Throwable){failure=error.toString();throw error}
        }
        override fun close(){if(!ended&&pending===this){ended=true;pending=null}}
    }
    private fun persist(candidate:ByteArray,next:Long,label:String) {
        val temporary=context.dataPath(file+".txn-"+next+".tmp")
        require(!Files.exists(temporary,NOFOLLOW_LINKS)) { label+" temporary output already exists" }
        FileChannel.open(temporary,CREATE_NEW,WRITE).use{channel->writeAll(channel,candidate);channel.force(true)}
        Files.move(temporary,path,ATOMIC_MOVE,REPLACE_EXISTING)
        require(Files.size(path)==identity.length&&matchesDiskBytes(candidate)) { label+" persistence readback mismatch after replace" }
    }
    override fun prepareProgram(offset:Long,payload:ByteBuffer):ImageProgramTransaction {
        live();check(programEnabled){"program capability not selected"};check(pending==null){"writable image transaction already pending"}
        val count=payload.remaining()
        require(count in 1..256&&offset>=writableOffset&&count.toLong()<=writableLength&&offset<=writableOffset+writableLength-count&&offset/256==(offset+count-1)/256){"program outside explicit writable region or software page"}
        val next=Math.addExact(generation,1)
        val frozen=ByteArray(count);payload.duplicate().get(frozen)
        val start=(offset-identity.offset).toInt()
        require(frozen.indices.all{index->(bytes[start+index].toInt() and frozen[index].toInt() and 255)==(frozen[index].toInt() and 255)}){"HYP strict one-to-zero law refuses requested zero-to-one bits"}
        unchangedDisk()
        return ProgramTransaction(offset,frozen,next).also{pending=it}
    }
    private inner class ProgramTransaction(override val offset:Long,private val payload:ByteArray,val next:Long):ImageProgramTransaction {
        override val size=payload.size
        private var ended=false
        override fun commit():ImageProgramReceipt {
            live();check(!ended&&pending===this){"stale writable program transaction"}
            val old=if (context.relaxedMode) DEFERRED_SHA256 else ensureCurrentSha()
            try {
                unchangedDisk()
                val candidate=bytes.clone();val start=(offset-identity.offset).toInt();var changed=0L
                payload.indices.forEach{index->if(candidate[start+index]!=payload[index])changed++;candidate[start+index]=payload[index]}
                val nextSha=if (context.relaxedMode) {
                    DEFERRED_SHA256
                } else if (changed > 0L) {
                    fullShaComputeCount++
                    digestBytes(candidate)
                } else {
                    old
                }
                if(changed>0)persist(candidate,next,"program")
                bytes=candidate
                if (context.relaxedMode) {
                    if (changed > 0L) shaDirty = true
                } else {
                    sha = nextSha
                    shaDirty = false
                }
                val payloadSha = if (context.relaxedMode) DEFERRED_SHA256 else digestBytes(payload)
                generation=next;ended=true;pending=null
                return ImageProgramReceipt(offset,size,changed,payloadSha,old,nextSha,generation).also{if(programReceipts.size==64)programReceipts.removeFirst();programReceipts.addLast(it)}
            }catch(error:Throwable){failure=error.toString();throw error}
        }
        override fun close(){if(!ended&&pending===this){ended=true;pending=null}}
    }
    override fun close() {
        if(closed)return
        pending?.close();closed=true
        try{lock.close()}finally{lockChannel.close()}
    }
    fun saveSnapshot(): Map<String, Any?> {
        live()
        check(pending == null) { "Cannot snapshot writable image with pending transaction" }
        unchangedDisk()
        val currentSha = ensureCurrentSha()
        val allowedStart = (writableOffset - identity.offset).toInt()
        val allowedEnd = allowedStart + writableLength.toInt()
        val encodedSubrange = if (currentSha == identity.sha256) null else {
            Base64.getEncoder().encodeToString(bytes.copyOfRange(allowedStart, allowedEnd))
        }
        return linkedMapOf(
            "regionId" to identity.id,
            "writableOffset" to writableOffset,
            "writableLength" to writableLength,
            "sha256" to currentSha,
            "generation" to generation,
            "writableSubrangeBase64" to encodedSubrange,
            "eraseReceipts" to receipts.map { receipt ->
                linkedMapOf<String, Any>(
                    "offset" to receipt.offset,
                    "size" to receipt.size,
                    "changedBytes" to receipt.changedBytes,
                    "beforeSha256" to receipt.beforeSha256,
                    "afterSha256" to receipt.afterSha256,
                    "generation" to receipt.generation,
                )
            },
            "programReceipts" to programReceipts.map { receipt ->
                linkedMapOf<String, Any>(
                    "offset" to receipt.offset,
                    "size" to receipt.size,
                    "changedBytes" to receipt.changedBytes,
                    "payloadSha256" to receipt.payloadSha256,
                    "beforeSha256" to receipt.beforeSha256,
                    "afterSha256" to receipt.afterSha256,
                    "generation" to receipt.generation,
                )
            },
        )
    }
    fun preflightRestore(raw: Any?): PreparedRestore {
        live()
        check(pending == null) { "Cannot restore writable image with pending transaction" }
        unchangedDisk()
        val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing WritableRegion snapshot")
        require(row.keys == SNAPSHOT_KEYS) { "Unexpected WritableRegion snapshot keys" }
        require(row["regionId"] == identity.id) { "WritableRegion regionId mismatch" }
        require(integer(row["writableOffset"]) == writableOffset && integer(row["writableLength"]) == writableLength) {
            "WritableRegion bounds mismatch for ${identity.id}"
        }
        val expectedSha = requireSha256(row["sha256"], "sha256")
        val expectedGeneration = integer(row["generation"])
        require(expectedGeneration >= 0L) { "Negative WritableRegion generation" }
        val candidate = ByteArray(identity.length.toInt())
        val seedView = ByteBuffer.wrap(candidate)
        seedRead(identity.offset, seedView)
        check(!seedView.hasRemaining()) { "Short seed read during WritableRegion restore" }
        require(hash(candidate) == identity.sha256) { "Immutable seed SHA mismatch during WritableRegion restore" }
        val rawSubrange = row["writableSubrangeBase64"]
        require(rawSubrange == null || rawSubrange is String) { "Invalid writableSubrangeBase64 field" }
        val allowedStart = (writableOffset - identity.offset).toInt()
        if (rawSubrange == null) {
            require(expectedSha == identity.sha256) { "Missing writableSubrangeBase64 for modified WritableRegion ${identity.id}" }
        } else {
            val decodedSlice = Base64.getDecoder().decode(rawSubrange)
            require(decodedSlice.size.toLong() == writableLength) {
                "Decoded writableSubrangeBase64 length mismatch for ${identity.id}"
            }
            decodedSlice.copyInto(candidate, allowedStart)
        }
        require(hash(candidate) == expectedSha) { "Reconstructed WritableRegion SHA-256 mismatch for ${identity.id}" }

        val rawEraseReceipts = row["eraseReceipts"] as? List<*>
            ?: throw IllegalArgumentException("Missing eraseReceipts list")
        require(rawEraseReceipts.size <= 64) { "Excessive eraseReceipts history size" }
        val restoredEraseReceipts = rawEraseReceipts.mapIndexed { receiptIndex, rawReceipt ->
            val receiptMap = rawReceipt as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid eraseReceipt at $receiptIndex")
            require(receiptMap.keys == ERASE_RECEIPT_KEYS) { "Unexpected eraseReceipt keys at $receiptIndex" }
            val receiptOffset = integer(receiptMap["offset"])
            val receiptSize = integer(receiptMap["size"])
            val changedBytes = integer(receiptMap["changedBytes"])
            val beforeSha = requireSha256(receiptMap["beforeSha256"], "eraseReceipts[$receiptIndex].beforeSha256")
            val afterSha = requireSha256(receiptMap["afterSha256"], "eraseReceipts[$receiptIndex].afterSha256")
            val receiptGeneration = integer(receiptMap["generation"])
            require(receiptSize in listOf(4096L, 65536L) && receiptOffset >= writableOffset && receiptOffset % receiptSize == 0L && receiptSize <= writableLength && receiptOffset <= writableOffset + writableLength - receiptSize) {
                "Invalid eraseReceipt geometry at $receiptIndex"
            }
            require(changedBytes in 0L..receiptSize && receiptGeneration in 1L..expectedGeneration) {
                "Invalid eraseReceipt counters at $receiptIndex"
            }
            ImageEraseReceipt(receiptOffset, receiptSize, changedBytes, beforeSha, afterSha, receiptGeneration)
        }

        val rawProgramReceipts = row["programReceipts"] as? List<*>
            ?: throw IllegalArgumentException("Missing programReceipts list")
        require(rawProgramReceipts.size <= 64) { "Excessive programReceipts history size" }
        if (!programEnabled) {
            require(rawProgramReceipts.isEmpty()) { "Program history not allowed when program capability is disabled" }
        }
        val restoredProgramReceipts = rawProgramReceipts.mapIndexed { receiptIndex, rawReceipt ->
            val receiptMap = rawReceipt as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid programReceipt at $receiptIndex")
            require(receiptMap.keys == PROGRAM_RECEIPT_KEYS) { "Unexpected programReceipt keys at $receiptIndex" }
            val receiptOffset = integer(receiptMap["offset"])
            val rawSize = integer(receiptMap["size"])
            require(rawSize in 1L..256L) { "Invalid programReceipt size at $receiptIndex" }
            val receiptSize = rawSize.toInt()
            val changedBytes = integer(receiptMap["changedBytes"])
            val payloadSha = requireSha256(receiptMap["payloadSha256"], "programReceipts[$receiptIndex].payloadSha256")
            val beforeSha = requireSha256(receiptMap["beforeSha256"], "programReceipts[$receiptIndex].beforeSha256")
            val afterSha = requireSha256(receiptMap["afterSha256"], "programReceipts[$receiptIndex].afterSha256")
            val receiptGeneration = integer(receiptMap["generation"])
            require(receiptOffset >= writableOffset && receiptSize.toLong() <= writableLength && receiptOffset <= writableOffset + writableLength - receiptSize && receiptOffset / 256 == (receiptOffset + receiptSize - 1) / 256) {
                "Invalid programReceipt geometry at $receiptIndex"
            }
            require(changedBytes in 0L..receiptSize.toLong() && receiptGeneration in 1L..expectedGeneration) {
                "Invalid programReceipt counters at $receiptIndex"
            }
            ImageProgramReceipt(receiptOffset, receiptSize, changedBytes, payloadSha, beforeSha, afterSha, receiptGeneration)
        }
        if (expectedGeneration == 0L) {
            require(restoredEraseReceipts.isEmpty() && restoredProgramReceipts.isEmpty()) {
                "Zero-generation WritableRegion cannot carry erase or program receipts"
            }
        }
        require(restoredEraseReceipts.size + restoredProgramReceipts.size <= expectedGeneration.coerceAtMost(128L).toInt()) {
            "Receipt history count exceeds generation for ${identity.id}"
        }
        if (ensureCurrentSha() != expectedSha) {
            val temporary = context.dataPath("$file.restore.tmp")
            require(!Files.exists(temporary, NOFOLLOW_LINKS)) { "restore temporary output already exists" }
        }
        return PreparedRestore(
            candidate = candidate,
            expectedSha = expectedSha,
            expectedGeneration = expectedGeneration,
            eraseReceipts = restoredEraseReceipts,
            programReceipts = restoredProgramReceipts,
        )
    }
    fun stageRestore(prepared: PreparedRestore): java.nio.file.Path? {
        live()
        check(pending == null) { "Cannot stage WritableRegion restore with pending transaction" }
        if (ensureCurrentSha() == prepared.expectedSha) {
            unchangedDisk()
            return null
        }
        val temporary = context.dataPath("$file.restore.tmp")
        require(!Files.exists(temporary, NOFOLLOW_LINKS)) { "restore temporary output already exists" }
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                writeAll(channel, prepared.candidate)
                channel.force(true)
            }
            unchangedDisk()
            return temporary
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
    }
    fun commitRestore(prepared: PreparedRestore, stagedTemporary: java.nio.file.Path?) {
        live()
        check(pending == null) { "Cannot commit WritableRegion restore with pending transaction" }
        if (stagedTemporary != null) {
            try {
                Files.move(stagedTemporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                require(Files.size(path) == identity.length && hash(Files.readAllBytes(path)) == prepared.expectedSha) {
                    "restore persistence readback mismatch after replace"
                }
            } catch (error: Throwable) {
                failure = error.toString()
                runCatching { Files.deleteIfExists(stagedTemporary) }
                throw error
            }
        }
        bytes = prepared.candidate
        sha = prepared.expectedSha
        shaDirty = false
        generation = prepared.expectedGeneration
        receipts.clear()
        receipts.addAll(prepared.eraseReceipts)
        programReceipts.clear()
        programReceipts.addAll(prepared.programReceipts)
    }
    companion object {
        const val PROFILE="explicit-copy-on-write-erase-experimental-v1"
        const val PROGRAM_PROFILE="explicit-copy-on-write-program-experimental-v2"
        const val PROGRAM_LAW="strict-one-to-zero-no-page-wrap-experimental-v1"
        const val DEFERRED_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        private val SNAPSHOT_KEYS = setOf(
            "regionId", "writableOffset", "writableLength", "sha256",
            "generation", "writableSubrangeBase64", "eraseReceipts", "programReceipts",
        )
        private val ERASE_RECEIPT_KEYS = setOf("offset", "size", "changedBytes", "beforeSha256", "afterSha256", "generation")
        private val PROGRAM_RECEIPT_KEYS = setOf("offset", "size", "changedBytes", "payloadSha256", "beforeSha256", "afterSha256", "generation")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private fun integer(value:Any?):Long { require(value is Byte||value is Short||value is Int||value is Long) { "integer required" };return (value as Number).toLong() }
        private fun requireSha256(value: Any?, field: String): String {
            require(value is String && HEX_SHA256.matches(value)) { "Expected SHA-256 hex for $field" }
            return value
        }
        private val HEX = java.util.HexFormat.of()
        private fun hash(bytes:ByteArray)=HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun writeAll(channel:FileChannel,bytes:ByteArray){val view=ByteBuffer.wrap(bytes);while(view.hasRemaining())check(channel.write(view)>0) { "short writable image output" }}
    }
}
