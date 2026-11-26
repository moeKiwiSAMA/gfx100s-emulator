package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

internal const val SECTOR_BYTES = 512
internal const val MAX_WRITABLE_SYNC_BYTES = 64L * 1024L * 1024L

internal interface SdBlockStore : AutoCloseable {
    val backendKind: String
    val capacityBytes: Long
    val readOnly: Boolean
    val isOpen: Boolean

    fun checkRange(offset: Long, length: Long) {
        require(length >= 0L && offset >= 0L && offset <= capacityBytes && length <= capacityBytes - offset) {
            "SD access outside image"
        }
    }

    fun read(offset: Long, target: ByteBuffer): Int
    fun write(offset: Long, source: ByteBuffer): Int
    fun readSector(sectorIndex: Long): ByteArray
    fun writeSector(sectorIndex: Long, sectorBytes: ByteArray)
    fun canWrite(): Boolean = true
    fun setReadOnly(newReadOnly: Boolean)
    fun flush() {}
    /** Non-destructive live sync after a completed SD transfer; defers if unlinked file data is still in flight. */
    fun flushIfConsistent() = flush()
    fun invalidateDigestCache() {}
    fun computeLogicalSha256(
        sectorOverrides: Map<Long, ByteArray> = emptyMap(),
        baselineSectors: Map<Long, ByteArray> = emptyMap(),
    ): String

    fun diagnostics(): Map<String, Any?> = emptyMap()
}

internal object ZeroDigestSupport {
    private const val ZERO_CHUNK_BYTES = 4 * 1024 * 1024
    private val zeroChunk = ByteArray(ZERO_CHUNK_BYTES)

    fun updateZeros(digest: MessageDigest, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val chunk = minOf(ZERO_CHUNK_BYTES.toLong(), remaining).toInt()
            digest.update(zeroChunk, 0, chunk)
            remaining -= chunk
        }
    }

    fun updateZeroRangeWithOverrides(
        digest: MessageDigest,
        startOffset: Long,
        byteCount: Long,
        sectorOverrides: Map<Long, ByteArray>,
        baselineSectors: Map<Long, ByteArray>,
    ) {
        require(startOffset % SECTOR_BYTES == 0L && byteCount % SECTOR_BYTES == 0L)
        if (sectorOverrides.isEmpty() && baselineSectors.isEmpty()) {
            updateZeros(digest, byteCount)
            return
        }
        val firstSector = startOffset / SECTOR_BYTES
        val endSector = (startOffset + byteCount) / SECTOR_BYTES
        val affected = ArrayList<Long>()
        for (key in sectorOverrides.keys) {
            if (key in firstSector until endSector) affected.add(key)
        }
        for (key in baselineSectors.keys) {
            if (key in firstSector until endSector && key !in sectorOverrides) affected.add(key)
        }
        if (affected.isEmpty()) {
            updateZeros(digest, byteCount)
            return
        }
        affected.sort()
        var cursorSector = firstSector
        for (sectorIndex in affected) {
            if (sectorIndex > cursorSector) {
                updateZeros(digest, (sectorIndex - cursorSector) * SECTOR_BYTES)
            }
            val bytes = sectorOverrides[sectorIndex] ?: baselineSectors.getValue(sectorIndex)
            digest.update(bytes, 0, SECTOR_BYTES)
            cursorSector = sectorIndex + 1L
        }
        if (cursorSector < endSector) {
            updateZeros(digest, (endSector - cursorSector) * SECTOR_BYTES)
        }
    }
}

internal object SdPathSecurity {
    fun validateNormalizedRelative(relative: String, label: String): Path {
        val path = Path.of(relative)
        require(
            relative.isNotBlank() &&
                !path.isAbsolute &&
                path == path.normalize() &&
                path.none { it.toString() in setOf(".", "..") },
        ) { "$label must be a normalized relative path" }
        return path
    }

    fun requireNoSymlinks(path: Path, label: String) {
        var part: Path? = path
        while (part != null) {
            require(!Files.isSymbolicLink(part)) { "$label symlink refused" }
            part = part.parent
        }
    }
}
