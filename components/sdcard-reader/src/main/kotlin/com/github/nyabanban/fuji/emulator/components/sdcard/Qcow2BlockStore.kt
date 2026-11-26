package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Pure-Kotlin QCOW2 v3 block store compatible with `qemu-img` (big-endian header, L1/L2 tables,
 * 64 KiB default clusters, 16-bit refcount table/blocks, optional read-only raw/qcow2 backing file,
 * and fast sparse-aware logical SHA-256).
 */
internal class Qcow2BlockStore private constructor(
    private val path: Path,
    private val rootDirectory: Path,
    initialReadOnly: Boolean,
    expectedCapacityBytes: Long,
    overrideBackingFile: String,
    private val depth: Int,
) : SdBlockStore {
    companion object {
        const val MAGIC = 0x514649FB
        const val VERSION_3 = 3
        const val HEADER_V3_LENGTH = 104
        const val DEFAULT_CLUSTER_BITS = 16 // 64 KiB
        const val DEFAULT_REFCOUNT_ORDER = 4 // 16-bit refcounts
        private const val MIN_CLUSTER_BITS = 12 // 4 KiB
        private const val MAX_CLUSTER_BITS = 21 // 2 MiB
        private const val MAX_BACKING_DEPTH = 8
        private const val L2_CACHE_MAX_TABLES = 64

        private const val EXT_END = 0
        private const val EXT_BACKING_FORMAT = 0xE2792ACA.toInt()

        private const val INCOMPAT_DIRTY_BIT = 1L shl 0
        private const val OFLAG_COPIED = 1L shl 63
        private const val OFLAG_COMPRESSED = 1L shl 62
        private const val STANDARD_ZERO_FLAG = 1L
        private const val OFFSET_MASK = 0x00FFFFFFFFFFFE00L

        fun open(
            path: Path,
            rootDirectory: Path,
            readOnly: Boolean,
            expectedCapacityBytes: Long = 0L,
            overrideBackingFile: String = "",
        ): Qcow2BlockStore = Qcow2BlockStore(path, rootDirectory, readOnly, expectedCapacityBytes, overrideBackingFile, depth = 0)

        /** Creates a fresh spec-compliant QCOW2 v3 image (4 clusters = 256 KiB at default 64 KiB clusters). */
        fun create(
            path: Path,
            capacityBytes: Long,
            clusterBits: Int = DEFAULT_CLUSTER_BITS,
            backingFileRelative: String? = null,
            backingFormat: String? = null,
        ) {
            require(capacityBytes > 0L && capacityBytes % SECTOR_BYTES == 0L) {
                "QCOW2 capacityBytes must be positive and 512-byte sector aligned"
            }
            require(clusterBits in MIN_CLUSTER_BITS..MAX_CLUSTER_BITS) {
                "QCOW2 clusterBits must be in $MIN_CLUSTER_BITS..$MAX_CLUSTER_BITS"
            }
            val clusterSize = 1 shl clusterBits
            val l2Entries = clusterSize / 8
            val l2Coverage = l2Entries.toLong() * clusterSize.toLong()
            val l1SizeLong = (capacityBytes + l2Coverage - 1L) / l2Coverage
            require(l1SizeLong in 1L..Int.MAX_VALUE.toLong()) { "QCOW2 L1 table too large" }
            val l1Size = l1SizeLong.toInt()
            val l1ByteSize = l1Size.toLong() * 8L
            val l1Clusters = ((l1ByteSize + clusterSize - 1L) / clusterSize).toInt().coerceAtLeast(1)

            // Layout:
            // Cluster 0: Header (+ optional extensions + optional backing file name)
            // Cluster 1: Refcount Table (1 cluster -> covers 32 KiB+ refcount blocks)
            // Cluster 2: Refcount Block 0
            // Cluster 3 .. (3 + l1Clusters - 1): L1 Table
            val refTableOffset = 1L * clusterSize
            val refBlock0Offset = 2L * clusterSize
            val l1TableOffset = 3L * clusterSize
            val totalInitialClusters = 3 + l1Clusters
            val refBlockEntries = clusterSize / 2
            require(totalInitialClusters <= refBlockEntries) {
                "Initial QCOW2 metadata exceeds single refcount block"
            }

            val backingBytes = backingFileRelative?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
            val formatBytes = backingFormat?.takeIf { it.isNotEmpty() && backingBytes != null }?.toByteArray(StandardCharsets.UTF_8)

            val cluster0 = ByteArray(clusterSize)
            val hdr = ByteBuffer.wrap(cluster0).order(ByteOrder.BIG_ENDIAN)
            var extPos = HEADER_V3_LENGTH
            if (formatBytes != null) {
                hdr.putInt(extPos, EXT_BACKING_FORMAT)
                hdr.putInt(extPos + 4, formatBytes.size)
                System.arraycopy(formatBytes, 0, cluster0, extPos + 8, formatBytes.size)
                extPos += 8 + ((formatBytes.size + 7) and 7.inv())
            }
            // End-of-extensions marker (8 zero bytes)
            extPos += 8

            var backingOffset = 0L
            var backingSize = 0
            if (backingBytes != null) {
                require(extPos + backingBytes.size <= clusterSize) { "QCOW2 backing file name exceeds header cluster" }
                backingOffset = extPos.toLong()
                backingSize = backingBytes.size
                System.arraycopy(backingBytes, 0, cluster0, extPos, backingBytes.size)
            }

            hdr.putInt(0x00, MAGIC)
            hdr.putInt(0x04, VERSION_3)
            hdr.putLong(0x08, backingOffset)
            hdr.putInt(0x10, backingSize)
            hdr.putInt(0x14, clusterBits)
            hdr.putLong(0x18, capacityBytes)
            hdr.putInt(0x20, 0) // crypt_method = none
            hdr.putInt(0x24, l1Size)
            hdr.putLong(0x28, l1TableOffset)
            hdr.putLong(0x30, refTableOffset)
            hdr.putInt(0x38, 1) // refcount_table_clusters
            hdr.putInt(0x3C, 0) // nb_snapshots
            hdr.putLong(0x40, 0L) // snapshots_offset
            hdr.putLong(0x48, 0L) // incompatible_features
            hdr.putLong(0x50, 0L) // compatible_features
            hdr.putLong(0x58, 0L) // autoclear_features
            hdr.putInt(0x60, DEFAULT_REFCOUNT_ORDER)
            hdr.putInt(0x64, HEADER_V3_LENGTH)

            val refTableCluster = ByteArray(clusterSize)
            ByteBuffer.wrap(refTableCluster).order(ByteOrder.BIG_ENDIAN).putLong(0, refBlock0Offset)

            val refBlock0 = ByteArray(clusterSize)
            val rbBuf = ByteBuffer.wrap(refBlock0).order(ByteOrder.BIG_ENDIAN)
            for (clusterIdx in 0 until totalInitialClusters) {
                rbBuf.putShort(clusterIdx * 2, 1.toShort())
            }

            val l1Bytes = ByteArray(l1Clusters * clusterSize)

            Files.createDirectories(path.parent)
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                NOFOLLOW_LINKS,
            ).use { ch ->
                writeFully(ch, 0L, cluster0)
                writeFully(ch, refTableOffset, refTableCluster)
                writeFully(ch, refBlock0Offset, refBlock0)
                writeFully(ch, l1TableOffset, l1Bytes)
            }
        }

        private fun writeFully(channel: FileChannel, offset: Long, bytes: ByteArray) {
            val buf = ByteBuffer.wrap(bytes)
            var done = 0
            while (buf.hasRemaining()) {
                val n = channel.write(buf, offset + done)
                check(n > 0) { "short QCOW2 write" }
                done += n
            }
        }

        private fun readFully(channel: FileChannel, offset: Long, bytes: ByteArray) {
            val buf = ByteBuffer.wrap(bytes)
            var done = 0
            while (buf.hasRemaining()) {
                val n = channel.read(buf, offset + done)
                check(n > 0) { "short QCOW2 read at offset $offset" }
                done += n
            }
        }

        private fun isQcow2Magic(path: Path): Boolean {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) < 4L) return false
            FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { ch ->
                val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                if (ch.read(buf, 0L) != 4) return false
                return buf.getInt(0) == MAGIC
            }
        }
    }

    override val backendKind: String = "qcow2"
    override var readOnly: Boolean = initialReadOnly
        private set

    private var channel: FileChannel = openChannel(initialReadOnly)

    private fun openChannel(ro: Boolean): FileChannel {
        val opts = if (ro) arrayOf(StandardOpenOption.READ) else arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
        return FileChannel.open(path, *opts, NOFOLLOW_LINKS)
    }

    override fun canWrite(): Boolean = depth == 0 && Files.isWritable(path)

    override fun setReadOnly(newReadOnly: Boolean) {
        if (newReadOnly == readOnly) return
        require(depth == 0) { "QCOW2 backing store must remain read-only" }
        if (!newReadOnly) {
            require(canWrite()) { "SD QCOW2 image is not writable on host filesystem" }
        } else {
            flush()
        }
        val nextChannel = openChannel(newReadOnly)
        val oldChannel = channel
        channel = nextChannel
        readOnly = newReadOnly
        runCatching { oldChannel.close() }
    }

    private val clusterBits: Int
    private val clusterSize: Int
    private val clusterMask: Long
    private val l2Entries: Int
    private val l2Shift: Int
    private val refBlockEntries: Int
    override val capacityBytes: Long
    private val l1TableOffset: Long
    private val l1Table: LongArray
    private var refTableOffset: Long
    private var refTableClusters: Int
    private var refTable: LongArray
    private var autoclearFeatures: Long
    private var nextFreeHostOffset: Long
    private val backingStore: SdBlockStore?
    private val backingRelativeName: String?

    private val l2Cache = object : LinkedHashMap<Long, LongArray>(L2_CACHE_MAX_TABLES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LongArray>?): Boolean =
            size > L2_CACHE_MAX_TABLES
    }

    private var writeGeneration = 0L
    private var hashedGeneration = -1L
    private var cachedSha256: String? = null

    override val isOpen: Boolean
        get() = channel.isOpen

    init {
        require(depth <= MAX_BACKING_DEPTH) { "QCOW2 backing file chain exceeds depth $MAX_BACKING_DEPTH" }
        try {
            val fileSize = channel.size()
            require(fileSize >= HEADER_V3_LENGTH) { "QCOW2 file too small ($fileSize bytes)" }
            val hdrBytes = ByteArray(HEADER_V3_LENGTH)
            readFully(channel, 0L, hdrBytes)
            val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.BIG_ENDIAN)

            val magic = hdr.getInt(0x00)
            require(magic == MAGIC) { "Invalid QCOW2 magic: 0x${Integer.toHexString(magic)}" }
            val version = hdr.getInt(0x04)
            require(version == VERSION_3) { "Unsupported QCOW2 version: $version (v3 required)" }

            val backingOffset = hdr.getLong(0x08)
            val backingSize = hdr.getInt(0x10)
            clusterBits = hdr.getInt(0x14)
            require(clusterBits in MIN_CLUSTER_BITS..MAX_CLUSTER_BITS) { "Unsupported QCOW2 cluster_bits: $clusterBits" }
            clusterSize = 1 shl clusterBits
            clusterMask = (clusterSize - 1).toLong()
            l2Entries = clusterSize / 8
            l2Shift = clusterBits - 3

            capacityBytes = hdr.getLong(0x18)
            require(capacityBytes > 0L && capacityBytes % SECTOR_BYTES == 0L) {
                "QCOW2 virtual size must be positive and sector-aligned: $capacityBytes"
            }
            if (expectedCapacityBytes > 0L) {
                require(capacityBytes == expectedCapacityBytes) {
                    "QCOW2 virtual size ($capacityBytes) differs from configured capacityBytes ($expectedCapacityBytes)"
                }
            }

            val cryptMethod = hdr.getInt(0x20)
            require(cryptMethod == 0) { "Encrypted QCOW2 images are not supported (crypt_method=$cryptMethod)" }

            val l1Size = hdr.getInt(0x24)
            l1TableOffset = hdr.getLong(0x28)
            val l2Coverage = l2Entries.toLong() * clusterSize.toLong()
            val minL1 = ((capacityBytes + l2Coverage - 1L) / l2Coverage).toInt()
            require(l1Size >= minL1 && l1TableOffset > 0L && l1TableOffset % clusterSize == 0L) {
                "Invalid QCOW2 L1 table geometry (size=$l1Size, min=$minL1, offset=$l1TableOffset)"
            }

            refTableOffset = hdr.getLong(0x30)
            refTableClusters = hdr.getInt(0x38)
            require(refTableClusters >= 1 && refTableOffset > 0L && refTableOffset % clusterSize == 0L) {
                "Invalid QCOW2 refcount table geometry"
            }

            val nbSnapshots = hdr.getInt(0x3C)
            require(nbSnapshots == 0) { "QCOW2 internal snapshots are not supported (nb_snapshots=$nbSnapshots)" }

            val incompat = hdr.getLong(0x48)
            require((incompat and INCOMPAT_DIRTY_BIT.inv()) == 0L) {
                "Unsupported QCOW2 incompatible_features: 0x${java.lang.Long.toHexString(incompat)}"
            }

            autoclearFeatures = hdr.getLong(0x58)
            val refcountOrder = hdr.getInt(0x60)
            require(refcountOrder == DEFAULT_REFCOUNT_ORDER) {
                "Unsupported QCOW2 refcount_order: $refcountOrder (only 4 / 16-bit supported)"
            }
            refBlockEntries = clusterSize / 2

            val headerLength = hdr.getInt(0x64)
            require(headerLength >= HEADER_V3_LENGTH && headerLength % 8 == 0 && headerLength <= clusterSize) {
                "Invalid QCOW2 header_length: $headerLength"
            }

            // Parse optional header extensions (e.g., backing_file_format)
            var backingFormat: String? = null
            val extLimit = if (backingOffset > 0L) minOf(backingOffset.toInt(), clusterSize) else minOf(fileSize.toInt(), clusterSize)
            if (extLimit > headerLength) {
                val cluster0Tail = ByteArray(extLimit - headerLength)
                readFully(channel, headerLength.toLong(), cluster0Tail)
                val extBuf = ByteBuffer.wrap(cluster0Tail).order(ByteOrder.BIG_ENDIAN)
                var pos = 0
                while (pos + 8 <= cluster0Tail.size) {
                    val extType = extBuf.getInt(pos)
                    val extLen = extBuf.getInt(pos + 4)
                    if (extType == EXT_END) break
                    require(extLen >= 0 && pos + 8 + extLen <= cluster0Tail.size) { "Malformed QCOW2 header extension" }
                    if (extType == EXT_BACKING_FORMAT) {
                        backingFormat = String(cluster0Tail, pos + 8, extLen, StandardCharsets.UTF_8)
                    }
                    pos += 8 + ((extLen + 7) and 7.inv())
                }
            }

            // Resolve optional read-only backing file
            val headerBacking: String? = if (backingSize > 0) {
                require(backingOffset >= headerLength && backingOffset + backingSize <= clusterSize) {
                    "QCOW2 backing_file offset/size out of header cluster bounds"
                }
                val nameBytes = ByteArray(backingSize)
                readFully(channel, backingOffset, nameBytes)
                String(nameBytes, StandardCharsets.UTF_8)
            } else null

            val effectiveBacking = when {
                overrideBackingFile.isNotBlank() && headerBacking != null -> {
                    require(overrideBackingFile == headerBacking) {
                        "Configured slotBackingFile ($overrideBackingFile) differs from QCOW2 header backing_file ($headerBacking)"
                    }
                    headerBacking
                }
                overrideBackingFile.isNotBlank() -> overrideBackingFile
                else -> headerBacking
            }

            backingRelativeName = effectiveBacking
            backingStore = if (effectiveBacking != null) {
                val relPath = SdPathSecurity.validateNormalizedRelative(effectiveBacking, "QCOW2 backing_file")
                val resolved = path.parent.resolve(relPath).normalize()
                val normalizedRoot = rootDirectory.toAbsolutePath().normalize()
                require(resolved.startsWith(normalizedRoot) && resolved != normalizedRoot) {
                    "QCOW2 backing_file escapes component directory: $effectiveBacking"
                }
                SdPathSecurity.requireNoSymlinks(resolved, "QCOW2 backing_file")
                require(Files.isRegularFile(resolved, NOFOLLOW_LINKS)) {
                    "QCOW2 backing_file must exist as a regular file: $effectiveBacking"
                }
                require(!Files.isSameFile(resolved, path)) { "QCOW2 backing_file cannot reference itself" }
                val isQcow2Backing = when (backingFormat?.lowercase()) {
                    "qcow2" -> true
                    "raw" -> false
                    null -> isQcow2Magic(resolved)
                    else -> throw IllegalArgumentException("Unsupported QCOW2 backing_file_format: $backingFormat")
                }
                if (isQcow2Backing) {
                    Qcow2BlockStore(resolved, normalizedRoot, initialReadOnly = true, expectedCapacityBytes = 0L, overrideBackingFile = "", depth = depth + 1)
                } else {
                    RawBlockStore(resolved, readOnly = true, expectedCapacityBytes = 0L)
                }
            } else null

            // Load L1 table
            l1Table = LongArray(l1Size)
            val l1Raw = ByteArray(l1Size * 8)
            readFully(channel, l1TableOffset, l1Raw)
            val l1Buf = ByteBuffer.wrap(l1Raw).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until l1Size) {
                l1Table[i] = l1Buf.getLong(i * 8)
            }

            // Load Refcount table
            val rtEntries = refTableClusters * (clusterSize / 8)
            refTable = LongArray(rtEntries)
            val rtRaw = ByteArray(refTableClusters * clusterSize)
            readFully(channel, refTableOffset, rtRaw)
            val rtBuf = ByteBuffer.wrap(rtRaw).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until rtEntries) {
                refTable[i] = rtBuf.getLong(i * 8)
            }

            // Align next free host cluster to clusterSize boundary at or above EOF
            nextFreeHostOffset = ((fileSize + clusterSize - 1L) / clusterSize) * clusterSize
        } catch (error: Throwable) {
            runCatching { channel.close() }
            throw error
        }
    }

    override fun invalidateDigestCache() {
        writeGeneration++
        cachedSha256 = null
    }

    private fun loadL2(l2Offset: Long): LongArray {
        l2Cache[l2Offset]?.let { return it }
        val raw = ByteArray(clusterSize)
        readFully(channel, l2Offset, raw)
        val table = LongArray(l2Entries)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until l2Entries) {
            table[i] = buf.getLong(i * 8)
        }
        l2Cache[l2Offset] = table
        return table
    }

    private fun readBackingRange(offset: Long, dest: ByteArray, destOffset: Int, length: Int) {
        val backing = backingStore
        if (backing == null || offset >= backing.capacityBytes) {
            dest.fill(0, destOffset, destOffset + length)
            return
        }
        val readable = minOf(length.toLong(), backing.capacityBytes - offset).toInt()
        if (readable > 0) {
            val slice = ByteBuffer.wrap(dest, destOffset, readable)
            backing.read(offset, slice)
        }
        if (readable < length) {
            dest.fill(0, destOffset + readable, destOffset + length)
        }
    }

    private fun readClusterInto(clusterIndex: Long, dest: ByteArray, destOffset: Int, inClusterOffset: Int, length: Int) {
        val l1Idx = (clusterIndex ushr l2Shift).toInt()
        val l1Entry = l1Table[l1Idx]
        val l2Offset = l1Entry and OFFSET_MASK
        val virtualOffset = (clusterIndex shl clusterBits) + inClusterOffset
        if (l2Offset == 0L) {
            readBackingRange(virtualOffset, dest, destOffset, length)
            return
        }
        val l2 = loadL2(l2Offset)
        val l2Idx = (clusterIndex and (l2Entries - 1L)).toInt()
        val l2Entry = l2[l2Idx]
        require((l2Entry and OFLAG_COMPRESSED) == 0L) { "Compressed QCOW2 clusters are not supported" }
        if ((l2Entry and STANDARD_ZERO_FLAG) != 0L) {
            dest.fill(0, destOffset, destOffset + length)
            return
        }
        val hostOffset = l2Entry and OFFSET_MASK
        if (hostOffset == 0L) {
            readBackingRange(virtualOffset, dest, destOffset, length)
            return
        }
        val slice = ByteBuffer.wrap(dest, destOffset, length)
        var done = 0
        while (slice.hasRemaining()) {
            val n = channel.read(slice, hostOffset + inClusterOffset + done)
            check(n > 0) { "short QCOW2 data read" }
            done += n
        }
    }

    private fun clearAutoclearOnFirstWrite() {
        if (autoclearFeatures != 0L) {
            autoclearFeatures = 0L
            val zeros = ByteArray(8)
            writeFully(channel, 0x58L, zeros)
        }
    }

    private fun allocateHostClusterRaw(initialBytes: ByteArray): Long {
        require(initialBytes.size == clusterSize)
        val offset = nextFreeHostOffset
        nextFreeHostOffset += clusterSize
        writeFully(channel, offset, initialBytes)
        return offset
    }

    private fun setClusterRefcountOne(hostOffset: Long) {
        require(hostOffset % clusterSize == 0L)
        val hostClusterIdx = hostOffset ushr clusterBits
        val rtIdx = (hostClusterIdx / refBlockEntries).toInt()
        val rbIdx = (hostClusterIdx % refBlockEntries).toInt()

        if (rtIdx >= refTable.size) {
            growRefcountTable(rtIdx + 1)
        }

        var rbOffset = refTable[rtIdx] and OFFSET_MASK
        if (rbOffset == 0L) {
            // Allocate a brand-new refcount block cluster and mark its own refcount = 1
            val newBlock = ByteArray(clusterSize)
            rbOffset = allocateHostClusterRaw(newBlock)
            refTable[rtIdx] = rbOffset
            writeLongBE(refTableOffset + rtIdx.toLong() * 8L, rbOffset)
            // Mark the new refcount block's own cluster refcount = 1
            val selfClusterIdx = rbOffset ushr clusterBits
            val selfRtIdx = (selfClusterIdx / refBlockEntries).toInt()
            val selfRbIdx = (selfClusterIdx % refBlockEntries).toInt()
            if (selfRtIdx == rtIdx) {
                writeShortBE(rbOffset + selfRbIdx.toLong() * 2L, 1.toShort())
            } else {
                setClusterRefcountOne(rbOffset)
            }
        }
        writeShortBE(rbOffset + rbIdx.toLong() * 2L, 1.toShort())
    }

    private fun growRefcountTable(minEntries: Int) {
        val entriesPerCluster = clusterSize / 8
        val newClusters = ((minEntries + entriesPerCluster - 1) / entriesPerCluster).coerceAtLeast(refTableClusters * 2)
        val newEntries = newClusters * entriesPerCluster
        val newTable = LongArray(newEntries)
        System.arraycopy(refTable, 0, newTable, 0, refTable.size)

        val newOffset = nextFreeHostOffset
        nextFreeHostOffset += newClusters.toLong() * clusterSize.toLong()
        val raw = ByteArray(newClusters * clusterSize)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        for (i in newTable.indices) {
            buf.putLong(i * 8, newTable[i])
        }
        writeFully(channel, newOffset, raw)

        val oldOffset = refTableOffset
        val oldClusters = refTableClusters
        refTableOffset = newOffset
        refTableClusters = newClusters
        refTable = newTable

        // Update header fields refcount_table_offset (+0x30) and refcount_table_clusters (+0x38)
        val hdrPatch = ByteArray(12)
        val hbuf = ByteBuffer.wrap(hdrPatch).order(ByteOrder.BIG_ENDIAN)
        hbuf.putLong(0, refTableOffset)
        hbuf.putInt(8, refTableClusters)
        writeFully(channel, 0x30L, hdrPatch)

        // Mark new refcount table clusters refcount = 1 and clear old refcount table refcounts
        for (c in 0 until newClusters) {
            setClusterRefcountOne(newOffset + c.toLong() * clusterSize.toLong())
        }
        for (c in 0 until oldClusters) {
            clearClusterRefcount(oldOffset + c.toLong() * clusterSize.toLong())
        }
    }

    private fun clearClusterRefcount(hostOffset: Long) {
        val hostClusterIdx = hostOffset ushr clusterBits
        val rtIdx = (hostClusterIdx / refBlockEntries).toInt()
        val rbIdx = (hostClusterIdx % refBlockEntries).toInt()
        if (rtIdx >= refTable.size) return
        val rbOffset = refTable[rtIdx] and OFFSET_MASK
        if (rbOffset == 0L) return
        writeShortBE(rbOffset + rbIdx.toLong() * 2L, 0.toShort())
    }

    private fun writeLongBE(fileOffset: Long, value: Long) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(0, value)
        var done = 0
        while (buf.hasRemaining()) {
            val n = channel.write(buf, fileOffset + done)
            check(n > 0) { "short QCOW2 metadata write" }
            done += n
        }
    }

    private fun writeShortBE(fileOffset: Long, value: Short) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(0, value)
        var done = 0
        while (buf.hasRemaining()) {
            val n = channel.write(buf, fileOffset + done)
            check(n > 0) { "short QCOW2 refcount write" }
            done += n
        }
    }

    private fun ensureL2Table(l1Idx: Int): Pair<Long, LongArray> {
        val existing = l1Table[l1Idx] and OFFSET_MASK
        if (existing != 0L) return existing to loadL2(existing)
        val blankL2 = ByteArray(clusterSize)
        val newL2Offset = allocateHostClusterRaw(blankL2)
        setClusterRefcountOne(newL2Offset)
        val newL1Entry = newL2Offset or OFLAG_COPIED
        l1Table[l1Idx] = newL1Entry
        writeLongBE(l1TableOffset + l1Idx.toLong() * 8L, newL1Entry)
        val table = LongArray(l2Entries)
        l2Cache[newL2Offset] = table
        return newL2Offset to table
    }

    private fun writeClusterSlice(clusterIndex: Long, inClusterOffset: Int, src: ByteArray, srcOffset: Int, length: Int) {
        clearAutoclearOnFirstWrite()
        val l1Idx = (clusterIndex ushr l2Shift).toInt()
        val l2Idx = (clusterIndex and (l2Entries - 1L)).toInt()
        val existingL2Offset = l1Table[l1Idx] and OFFSET_MASK
        val existingL2Entry = if (existingL2Offset != 0L) loadL2(existingL2Offset)[l2Idx] else 0L
        require((existingL2Entry and OFLAG_COMPRESSED) == 0L) { "Compressed QCOW2 clusters are not supported" }

        val existingHostOffset = existingL2Entry and OFFSET_MASK
        val isZeroFlag = (existingL2Entry and STANDARD_ZERO_FLAG) != 0L

        if (existingHostOffset != 0L && !isZeroFlag) {
            // Fast path: overwrite directly in already-allocated cluster
            val buf = ByteBuffer.wrap(src, srcOffset, length)
            var done = 0
            while (buf.hasRemaining()) {
                val n = channel.write(buf, existingHostOffset + inClusterOffset + done)
                check(n > 0) { "short QCOW2 data write" }
                done += n
            }
            return
        }

        // Unallocated or zero-flagged cluster: assemble full cluster buffer
        val clusterBuf = ByteArray(clusterSize)
        if (length < clusterSize) {
            if (!isZeroFlag) {
                readBackingRange(clusterIndex shl clusterBits, clusterBuf, 0, clusterSize)
            }
        }
        System.arraycopy(src, srcOffset, clusterBuf, inClusterOffset, length)

        val allZeros = clusterBuf.all { it == 0.toByte() }
        if (allZeros) {
            if (backingStore == null) {
                // Keep maximally sparse: unallocated cluster reads as zeros
                return
            }
            if (isZeroFlag) return
            // Mask potentially non-zero backing data with explicit QCOW2 zero flag (no data cluster allocated!)
            val (l2Offset, l2Table) = ensureL2Table(l1Idx)
            l2Table[l2Idx] = STANDARD_ZERO_FLAG
            writeLongBE(l2Offset + l2Idx.toLong() * 8L, STANDARD_ZERO_FLAG)
            return
        }

        // Crash-safe write order: 1) write cluster data, 2) set refcount = 1, 3) update L2 (and L1 if new)
        val newHostOffset = allocateHostClusterRaw(clusterBuf)
        setClusterRefcountOne(newHostOffset)
        val (l2Offset, l2Table) = ensureL2Table(l1Idx)
        val newL2Entry = newHostOffset or OFLAG_COPIED
        l2Table[l2Idx] = newL2Entry
        writeLongBE(l2Offset + l2Idx.toLong() * 8L, newL2Entry)
    }

    override fun read(offset: Long, target: ByteBuffer): Int {
        val total = target.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0
        val temp = ByteArray(minOf(total, clusterSize))
        var done = 0
        while (done < total) {
            val pos = offset + done
            val clusterIndex = pos ushr clusterBits
            val inCluster = (pos and clusterMask).toInt()
            val chunk = minOf(total - done, clusterSize - inCluster, temp.size)
            readClusterInto(clusterIndex, temp, 0, inCluster, chunk)
            target.put(temp, 0, chunk)
            done += chunk
        }
        return done
    }

    override fun write(offset: Long, source: ByteBuffer): Int {
        require(!readOnly) { "SD slot write-protected" }
        val total = source.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0
        invalidateDigestCache()
        val temp = ByteArray(minOf(total, clusterSize))
        var done = 0
        while (done < total) {
            val pos = offset + done
            val clusterIndex = pos ushr clusterBits
            val inCluster = (pos and clusterMask).toInt()
            val chunk = minOf(total - done, clusterSize - inCluster, temp.size)
            source.get(temp, 0, chunk)
            writeClusterSlice(clusterIndex, inCluster, temp, 0, chunk)
            done += chunk
        }
        return done
    }

    override fun readSector(sectorIndex: Long): ByteArray {
        val offset = sectorIndex * SECTOR_BYTES
        checkRange(offset, SECTOR_BYTES.toLong())
        val out = ByteArray(SECTOR_BYTES)
        readClusterInto(offset ushr clusterBits, out, 0, (offset and clusterMask).toInt(), SECTOR_BYTES)
        return out
    }

    override fun writeSector(sectorIndex: Long, sectorBytes: ByteArray) {
        require(!readOnly) { "SD slot write-protected" }
        require(sectorBytes.size == SECTOR_BYTES)
        val offset = sectorIndex * SECTOR_BYTES
        checkRange(offset, SECTOR_BYTES.toLong())
        invalidateDigestCache()
        writeClusterSlice(offset ushr clusterBits, (offset and clusterMask).toInt(), sectorBytes, 0, SECTOR_BYTES)
    }

    override fun computeLogicalSha256(
        sectorOverrides: Map<Long, ByteArray>,
        baselineSectors: Map<Long, ByteArray>,
    ): String {
        val noOverrides = sectorOverrides.isEmpty() && baselineSectors.isEmpty()
        if (noOverrides && cachedSha256 != null && hashedGeneration == writeGeneration) {
            return cachedSha256!!
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val clusterBuf = ByteArray(clusterSize)
        val totalClusters = (capacityBytes + clusterSize - 1L) / clusterSize
        val l2Coverage = l2Entries.toLong() * clusterSize.toLong()

        var clusterIndex = 0L
        while (clusterIndex < totalClusters) {
            val l1Idx = (clusterIndex ushr l2Shift).toInt()
            val l2Offset = l1Table[l1Idx] and OFFSET_MASK
            if (l2Offset == 0L && backingStore == null) {
                val startByte = clusterIndex shl clusterBits
                val endByte = minOf(capacityBytes, (l1Idx.toLong() + 1L) * l2Coverage)
                ZeroDigestSupport.updateZeroRangeWithOverrides(digest, startByte, endByte - startByte, sectorOverrides, baselineSectors)
                clusterIndex = (l1Idx.toLong() + 1L) * l2Entries.toLong()
                continue
            }
            val l2 = if (l2Offset != 0L) loadL2(l2Offset) else null
            val endInL1 = minOf(totalClusters, (l1Idx.toLong() + 1L) * l2Entries.toLong())
            while (clusterIndex < endInL1) {
                val startByte = clusterIndex shl clusterBits
                val validLen = minOf(clusterSize.toLong(), capacityBytes - startByte).toInt()
                val l2Idx = (clusterIndex and (l2Entries - 1L)).toInt()
                val l2Entry = l2?.get(l2Idx) ?: 0L
                require((l2Entry and OFLAG_COMPRESSED) == 0L) { "Compressed QCOW2 clusters are not supported" }
                val isZeroFlag = (l2Entry and STANDARD_ZERO_FLAG) != 0L
                val hostOffset = l2Entry and OFFSET_MASK
                if (isZeroFlag || (hostOffset == 0L && backingStore == null)) {
                    ZeroDigestSupport.updateZeroRangeWithOverrides(digest, startByte, validLen.toLong(), sectorOverrides, baselineSectors)
                } else {
                    if (hostOffset != 0L) {
                        val slice = ByteBuffer.wrap(clusterBuf, 0, validLen)
                        var done = 0
                        while (slice.hasRemaining()) {
                            val n = channel.read(slice, hostOffset + done)
                            check(n > 0) { "short QCOW2 hash read" }
                            done += n
                        }
                    } else {
                        readBackingRange(startByte, clusterBuf, 0, validLen)
                    }
                    if (noOverrides) {
                        digest.update(clusterBuf, 0, validLen)
                    } else {
                        var localOff = 0
                        while (localOff < validLen) {
                            val secIdx = (startByte + localOff) / SECTOR_BYTES
                            val ov = sectorOverrides[secIdx] ?: baselineSectors[secIdx]
                            if (ov != null) digest.update(ov, 0, SECTOR_BYTES)
                            else digest.update(clusterBuf, localOff, SECTOR_BYTES)
                            localOff += SECTOR_BYTES
                        }
                    }
                }
                clusterIndex++
            }
        }

        val sha = HexFormat.of().formatHex(digest.digest())
        if (noOverrides) {
            cachedSha256 = sha
            hashedGeneration = writeGeneration
        }
        return sha
    }

    override fun diagnostics(): Map<String, Any?> = linkedMapOf(
        "backend" to backendKind,
        "clusterBytes" to clusterSize,
        "backingFile" to backingRelativeName,
        "hostFileBytes" to runCatching { channel.size() }.getOrNull(),
    )

    override fun close() {
        runCatching { backingStore?.close() }
        runCatching { channel.close() }
    }
}
