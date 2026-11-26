package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.SparseFileDigest
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

internal class RawBlockStore(
    private val path: Path,
    readOnly: Boolean,
    expectedCapacityBytes: Long = 0L,
) : SdBlockStore {
    override val backendKind: String = "raw"

    override var readOnly: Boolean = readOnly
        private set

    private var channel: FileChannel = openChannel(readOnly)

    private fun openChannel(ro: Boolean): FileChannel {
        val options = if (ro) {
            arrayOf(StandardOpenOption.READ)
        } else {
            arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
        }
        return FileChannel.open(path, *options, NOFOLLOW_LINKS)
    }

    override fun canWrite(): Boolean = Files.isWritable(path)

    override fun setReadOnly(newReadOnly: Boolean) {
        if (newReadOnly == readOnly) return
        if (!newReadOnly) {
            require(canWrite()) { "SD raw image is not writable on host filesystem" }
        }
        val nextChannel = openChannel(newReadOnly)
        val oldChannel = channel
        channel = nextChannel
        readOnly = newReadOnly
        runCatching { oldChannel.close() }
    }

    private var cachedMeta: SparseFileDigest.FileIdentity? = null
    private var cachedSha256: String? = null
    private var writeGeneration = 0L
    private var hashedGeneration = -1L

    override val capacityBytes: Long
        get() = channel.size()

    override val isOpen: Boolean
        get() = channel.isOpen

    init {
        try {
            val size = channel.size()
            require(size > 0L && size % SECTOR_BYTES == 0L) { "SD image must exist and be sector aligned" }
            if (expectedCapacityBytes > 0L) {
                require(size == expectedCapacityBytes) {
                    "Raw SD image size ($size) differs from configured capacityBytes ($expectedCapacityBytes)"
                }
            }
        } catch (error: Throwable) {
            runCatching { channel.close() }
            throw error
        }
    }

    override fun invalidateDigestCache() {
        writeGeneration++
        cachedSha256 = null
        cachedMeta = null
        SparseFileDigest.invalidate(path)
    }

    override fun read(offset: Long, target: ByteBuffer): Int {
        val count = target.remaining()
        checkRange(offset, count.toLong())
        var done = 0
        while (target.hasRemaining()) {
            val n = channel.read(target, offset + done)
            check(n > 0) { "short SD read" }
            done += n
        }
        return done
    }

    override fun write(offset: Long, source: ByteBuffer): Int {
        require(!readOnly) { "SD slot write-protected" }
        val count = source.remaining()
        checkRange(offset, count.toLong())
        if (count > 0 && capacityBytes > MAX_WRITABLE_SYNC_BYTES) {
            invalidateDigestCache()
        }
        var done = 0
        while (source.hasRemaining()) {
            val n = channel.write(source, offset + done)
            check(n > 0) { "short SD write" }
            done += n
        }
        return done
    }

    override fun readSector(sectorIndex: Long): ByteArray {
        val buffer = ByteBuffer.allocate(SECTOR_BYTES)
        val baseOffset = sectorIndex * SECTOR_BYTES
        var bytesRead = 0
        while (buffer.hasRemaining()) {
            val chunkCount = channel.read(buffer, baseOffset + bytesRead)
            check(chunkCount > 0) { "short SD sector read" }
            bytesRead += chunkCount
        }
        return buffer.array()
    }

    override fun writeSector(sectorIndex: Long, sectorBytes: ByteArray) {
        require(!readOnly) { "SD slot write-protected" }
        require(sectorBytes.size == SECTOR_BYTES)
        if (capacityBytes > MAX_WRITABLE_SYNC_BYTES) {
            invalidateDigestCache()
        }
        val buffer = ByteBuffer.wrap(sectorBytes)
        val baseOffset = sectorIndex * SECTOR_BYTES
        var bytesWritten = 0
        while (buffer.hasRemaining()) {
            val chunkCount = channel.write(buffer, baseOffset + bytesWritten)
            check(chunkCount > 0) { "short SD sector write" }
            bytesWritten += chunkCount
        }
    }

    override fun computeLogicalSha256(
        sectorOverrides: Map<Long, ByteArray>,
        baselineSectors: Map<Long, ByteArray>,
    ): String {
        val mediaSize = capacityBytes
        if (sectorOverrides.isEmpty() && baselineSectors.isEmpty() && mediaSize > MAX_WRITABLE_SYNC_BYTES) {
            val currentMeta = SparseFileDigest.readIdentity(path)?.takeIf { it.size == mediaSize }
            val cached = cachedSha256
            if (cached != null && hashedGeneration == writeGeneration &&
                currentMeta != null && currentMeta == cachedMeta
            ) {
                return cached
            }
            val sha = SparseFileDigest.cachedOrCompute(path, channel, mediaSize)
            cachedSha256 = sha
            cachedMeta = currentMeta
            hashedGeneration = writeGeneration
            return sha
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val chunkBytes = ByteArray(minOf(4L * 1024L * 1024L, maxOf(65536L, mediaSize)).toInt())
        val chunkBuffer = ByteBuffer.wrap(chunkBytes)
        var fileOffset = 0L
        while (fileOffset < mediaSize) {
            val targetChunk = minOf(chunkBytes.size.toLong(), mediaSize - fileOffset).toInt()
            chunkBuffer.clear()
            chunkBuffer.limit(targetChunk)
            var filled = 0
            while (chunkBuffer.hasRemaining()) {
                val readNow = channel.read(chunkBuffer, fileOffset + filled)
                check(readNow > 0) { "short SD hash read" }
                filled += readNow
            }
            if (sectorOverrides.isEmpty() && baselineSectors.isEmpty()) {
                digest.update(chunkBytes, 0, targetChunk)
            } else {
                var localOffset = 0
                while (localOffset < targetChunk) {
                    val sectorIndex = (fileOffset + localOffset) / SECTOR_BYTES
                    val overrideBytes = sectorOverrides[sectorIndex] ?: baselineSectors[sectorIndex]
                    if (overrideBytes != null) {
                        digest.update(overrideBytes, 0, SECTOR_BYTES)
                    } else {
                        digest.update(chunkBytes, localOffset, SECTOR_BYTES)
                    }
                    localOffset += SECTOR_BYTES
                }
            }
            fileOffset += targetChunk
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    override fun close() {
        runCatching { channel.close() }
    }
}
