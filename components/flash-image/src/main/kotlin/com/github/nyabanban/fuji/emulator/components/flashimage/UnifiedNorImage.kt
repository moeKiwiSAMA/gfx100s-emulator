package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HexFormat

/**
 * Unified 64 MiB (`0x0400_0000` bytes) QSPI NOR Flash backing engine (`raw-nor-image-v1`).
 *
 * Models the complete single-chip GFX100S QSPI NOR Flash (`[0x0000_0000, 0x0400_0000)`):
 * - `[0x0000_0000, 0x02F0_0020)`: Stage-1 IPL (`bank-00`), `boot.bin` (`0x20000`), `kernel.bin`, `section4`, `section5`
 * - `[0x02F0_0020, 0x02FE_0000)`: erased NOR gap (`0xFF`)
 * - `[0x02FE_0000, 0x0400_0000)`: lens cache, bank-13, bank-14 calibration/MAC/battery tables, BKUP settings
 *
 * Supports real NOR sector erase (`4 KiB` / `64 KiB` -> `0xFF`) and 256-byte page programming
 * enforcing the physical NOR 1-to-0 bit-transition law (`(existing & payload) == payload`),
 * persisted directly to the backing `.bin` file at exact sector/page offsets under an exclusive
 * single-owner `.lock` file lease.
 */
internal class UnifiedNorImage(
    val path: Path,
    val relativeFile: String,
    val writable: Boolean,
    val relaxedMode: Boolean = false,
) : ReadOnlyImagePort, WritableImagePort, ProgrammableImagePort, LazyDigestImagePort, AutoCloseable {
    companion object {
        const val PROFILE = "raw-nor-image-v1"
        const val REGION_ID = "unified-nor-flash"
        const val NOR_SIZE_BYTES = 64L * 1024L * 1024L // 67,108,864 bytes (512 Mbit QSPI NOR)
        const val DEFERRED_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        private val HEX = HexFormat.of()
    }

    private val lockChannel: FileChannel?
    private val lock: FileLock?
    private val dataChannel: FileChannel?
    private val bytes: ByteArray
    private var seedSha256Cache: String? = null
    private var cachedSha256: String? = null
    private var shaDirty = true
    var fullShaComputeCount = 0L
        private set
    private var generation = 0L
    var eraseCount = 0L
        private set
    var programCount = 0L
        private set
    private var pending: AutoCloseable? = null
    private var closed = false
    private var failure: String? = null
    private val eraseReceipts = ArrayDeque<ImageEraseReceipt>()
    private val programReceipts = ArrayDeque<ImageProgramReceipt>()
    private val shaDigest = MessageDigest.getInstance("SHA-256")

    init {
        require(!Files.isSymbolicLink(path)) { "Unified NOR flash symlink refused: $path" }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Unified NOR flash image missing: $path" }
        val fileSize = Files.size(path)
        require(fileSize == NOR_SIZE_BYTES) {
            "Unified NOR flash image must be exactly $NOR_SIZE_BYTES bytes (64 MiB), got $fileSize: $path"
        }
        var openedLockChannel: FileChannel? = null
        var acquiredLock: FileLock? = null
        var openedDataChannel: FileChannel? = null
        try {
            if (writable) {
                val lockPath = path.resolveSibling("${path.fileName}.lock")
                require(!Files.isSymbolicLink(lockPath)) { "Unified NOR flash lock symlink refused: $lockPath" }
                openedLockChannel = FileChannel.open(lockPath, CREATE, WRITE)
                acquiredLock = openedLockChannel.tryLock() ?: error("Unified NOR flash image already leased: $path")
                openedDataChannel = FileChannel.open(path, READ, WRITE)
            }
            bytes = Files.readAllBytes(path)
            require(bytes.size.toLong() == NOR_SIZE_BYTES) { "Unified NOR flash size changed during read" }
            if (!relaxedMode) {
                val initialSha = digestBytes(bytes, 0, bytes.size)
                seedSha256Cache = initialSha
                cachedSha256 = initialSha
                shaDirty = false
                fullShaComputeCount = 1L
            }
            lockChannel = openedLockChannel
            lock = acquiredLock
            dataChannel = openedDataChannel
        } catch (error: Throwable) {
            runCatching { openedDataChannel?.close() }
            runCatching { acquiredLock?.close() }
            runCatching { openedLockChannel?.close() }
            throw error
        }
    }

    private fun digestBytes(data: ByteArray, offset: Int, length: Int): String {
        shaDigest.reset()
        shaDigest.update(data, offset, length)
        return HEX.formatHex(shaDigest.digest())
    }

    override val isSha256Dirty: Boolean
        get() = shaDirty

    override fun cachedSha256OrNull(): String? =
        if (shaDirty) null else cachedSha256

    private fun ensureFullSha256(): String {
        val existing = cachedSha256
        if (!shaDirty && existing != null) return existing
        val computed = digestBytes(bytes, 0, bytes.size)
        fullShaComputeCount++
        cachedSha256 = computed
        if (seedSha256Cache == null && generation == 0L) {
            seedSha256Cache = computed
        }
        shaDirty = false
        return computed
    }

    private fun live() {
        check(!closed) { "Unified NOR flash image closed" }
        check(failure == null) { "Unified NOR flash image failed: $failure" }
    }

    fun checkLive() = live()

    override val length: Long get() = NOR_SIZE_BYTES

    override val sha256: String
        get() {
            live()
            return ensureFullSha256()
        }

    override val experimentalTail: ExperimentalImageTail? get() = null

    override fun check(offset: Long, size: Long) {
        live()
        require(offset >= 0L && size > 0L && offset <= NOR_SIZE_BYTES && size <= NOR_SIZE_BYTES - offset) {
            "Unified NOR flash read out of bounds: offset=0x${offset.toString(16)} size=0x${size.toString(16)}"
        }
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        live()
        val count = destination.remaining()
        require(!destination.isReadOnly) { "writable read destination required" }
        check(offset, count.toLong())
        destination.put(bytes, offset.toInt(), count)
    }

    override fun snapshot(): WritableImageState {
        live()
        val currentSha: String
        val seedSha: String
        if (relaxedMode) {
            // Never trigger a synchronous 64 MiB hash in hot FlashProgramEngine.bounds()/state() paths.
            currentSha = if (shaDirty) DEFERRED_SHA256 else (cachedSha256 ?: DEFERRED_SHA256)
            seedSha = seedSha256Cache ?: DEFERRED_SHA256
        } else {
            currentSha = ensureFullSha256()
            seedSha = seedSha256Cache ?: currentSha
        }
        return WritableImageState(
            regionId = REGION_ID,
            offset = 0L,
            length = NOR_SIZE_BYTES,
            relativeFile = relativeFile,
            writableOffset = 0L,
            writableLength = if (writable) NOR_SIZE_BYTES else 0L,
            seedSha256 = seedSha,
            currentSha256 = currentSha,
            generation = generation,
            pending = pending != null,
            closed = closed,
            failure = failure,
        )
    }

    fun eraseHistory(): List<ImageEraseReceipt> = eraseReceipts.toList()
    fun programHistory(): List<ImageProgramReceipt> = programReceipts.toList()

    private fun persistSlice(offset: Long, length: Int) {
        val ch = checkNotNull(dataChannel) { "Unified NOR flash is read-only" }
        val view = ByteBuffer.wrap(bytes, offset.toInt(), length)
        var pos = offset
        while (view.hasRemaining()) {
            val written = ch.write(view, pos)
            check(written > 0) { "Short write persisting unified NOR flash at 0x${pos.toString(16)}" }
            pos += written
        }
    }

    override fun prepareErase(offset: Long, size: Long): ImageEraseTransaction {
        live()
        require(writable) { "Unified NOR flash is read-only" }
        check(pending == null) { "Unified NOR flash transaction already pending" }
        require((size == 4096L || size == 65536L) && offset >= 0L && offset % size == 0L && offset <= NOR_SIZE_BYTES - size) {
            "Unified NOR erase out of bounds or misaligned: offset=0x${offset.toString(16)} size=0x${size.toString(16)}"
        }
        val next = Math.addExact(generation, 1L)
        return EraseTxn(offset, size, next).also { pending = it }
    }

    private inner class EraseTxn(
        override val offset: Long,
        override val size: Long,
        private val next: Long,
    ) : ImageEraseTransaction {
        private var ended = false

        override fun commit(): ImageEraseReceipt {
            live()
            check(!ended && pending === this) { "Stale unified NOR erase transaction" }
            val start = offset.toInt()
            val len = size.toInt()
            try {
                val beforeSha = if (relaxedMode) DEFERRED_SHA256 else digestBytes(bytes, start, len)
                var changed = 0L
                val ff = 0xFF.toByte()
                for (i in 0 until len) {
                    if (bytes[start + i] != ff) {
                        bytes[start + i] = ff
                        changed++
                    }
                }
                val afterSha = if (changed > 0L) {
                    persistSlice(offset, len)
                    shaDirty = true
                    if (relaxedMode) DEFERRED_SHA256 else digestBytes(bytes, start, len)
                } else {
                    beforeSha
                }
                generation = next
                eraseCount++
                ended = true
                pending = null
                return ImageEraseReceipt(offset, size, changed, beforeSha, afterSha, generation).also {
                    if (eraseReceipts.size == 64) eraseReceipts.removeFirst()
                    eraseReceipts.addLast(it)
                }
            } catch (error: Throwable) {
                failure = error.toString()
                throw error
            }
        }

        override fun close() {
            if (!ended && pending === this) {
                ended = true
                pending = null
            }
        }
    }

    override fun prepareProgram(offset: Long, payload: ByteBuffer): ImageProgramTransaction {
        live()
        require(writable) { "Unified NOR flash is read-only" }
        check(pending == null) { "Unified NOR flash transaction already pending" }
        val count = payload.remaining()
        require(count in 1..256 && offset >= 0L && offset <= NOR_SIZE_BYTES - count && offset / 256L == (offset + count - 1L) / 256L) {
            "Unified NOR program out of bounds or crosses 256-byte page boundary: offset=0x${offset.toString(16)} size=$count"
        }
        val next = Math.addExact(generation, 1L)
        val frozen = ByteArray(count)
        payload.duplicate().get(frozen)
        val start = offset.toInt()
        for (i in 0 until count) {
            val cur = bytes[start + i].toInt() and 0xFF
            val nxt = frozen[i].toInt() and 0xFF
            require((cur and nxt) == nxt) {
                "NOR 1-to-0 program law violated at 0x${(offset + i).toString(16)}: existing=0x${cur.toString(16)} payload=0x${nxt.toString(16)}"
            }
        }
        return ProgramTxn(offset, frozen, next).also { pending = it }
    }

    private inner class ProgramTxn(
        override val offset: Long,
        private val payload: ByteArray,
        private val next: Long,
    ) : ImageProgramTransaction {
        override val size: Int = payload.size
        private var ended = false

        override fun commit(): ImageProgramReceipt {
            live()
            check(!ended && pending === this) { "Stale unified NOR program transaction" }
            val start = offset.toInt()
            val len = payload.size
            try {
                val beforeSha = if (relaxedMode) DEFERRED_SHA256 else digestBytes(bytes, start, len)
                var changed = 0L
                for (i in 0 until len) {
                    if (bytes[start + i] != payload[i]) {
                        bytes[start + i] = payload[i]
                        changed++
                    }
                }
                val afterSha = if (changed > 0L) {
                    persistSlice(offset, len)
                    shaDirty = true
                    if (relaxedMode) DEFERRED_SHA256 else digestBytes(bytes, start, len)
                } else {
                    beforeSha
                }
                val payloadSha = if (relaxedMode) DEFERRED_SHA256 else digestBytes(payload, 0, len)
                generation = next
                programCount++
                ended = true
                pending = null
                return ImageProgramReceipt(offset, len, changed, payloadSha, beforeSha, afterSha, generation).also {
                    if (programReceipts.size == 64) programReceipts.removeFirst()
                    programReceipts.addLast(it)
                }
            } catch (error: Throwable) {
                failure = error.toString()
                throw error
            }
        }

        override fun close() {
            if (!ended && pending === this) {
                ended = true
                pending = null
            }
        }
    }

    fun forceFlush() {
        if (!closed && writable) {
            dataChannel?.force(false)
        }
    }

    override fun close() {
        if (closed) return
        pending?.close()
        closed = true
        var firstError: Throwable? = null
        try {
            if (writable) dataChannel?.force(false)
        } catch (e: Throwable) {
            firstError = e
        }
        try {
            dataChannel?.close()
        } catch (e: Throwable) {
            if (firstError == null) firstError = e else firstError.addSuppressed(e)
        }
        try {
            lock?.close()
        } catch (e: Throwable) {
            if (firstError == null) firstError = e else firstError.addSuppressed(e)
        }
        try {
            lockChannel?.close()
        } catch (e: Throwable) {
            if (firstError == null) firstError = e else firstError.addSuppressed(e)
        }
        if (firstError != null) throw firstError
    }
}
