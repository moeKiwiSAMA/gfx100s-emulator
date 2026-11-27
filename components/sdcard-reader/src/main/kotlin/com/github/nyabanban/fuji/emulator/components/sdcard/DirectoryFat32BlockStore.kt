package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.TreeMap

/**
 * Virtual FAT32 SD card backed by a clean host directory (`<dir>/`) plus a sibling sidecar JSON
 * (`<dir>.fuji-sd.json`). Preserves custom capacity/geometry, MBR, FAT32 BPB/VBR/FSInfo, reserved
 * sectors, and incremental file<->cluster/short-name records across restarts, with bidirectional
 * host-folder <-> guest-sector synchronization and mid-format safety.
 */
internal class DirectoryFat32BlockStore private constructor(
    private val directory: Path,
    private val sidecarPath: Path,
    initialReadOnly: Boolean,
    configuredCapacityBytes: Long,
) : SdBlockStore {
    override var readOnly: Boolean = initialReadOnly
        private set

    override fun canWrite(): Boolean = Files.isWritable(directory)

    override fun setReadOnly(newReadOnly: Boolean) {
        if (newReadOnly == readOnly) return
        if (newReadOnly) {
            flush()
            readOnly = true
        } else {
            require(canWrite()) { "SD directory is not writable on host filesystem" }
            readOnly = false
        }
    }
    companion object {
        private const val SIDECAR_SCHEMA = 1
        private const val MIN_FAT32_CLUSTERS = 65525L
        private const val DEFAULT_PARTITION_START_LBA = 2048L
        private const val DEFAULT_RESERVED_SECTORS = 32
        private const val DEFAULT_NUM_FATS = 2
        private const val DEFAULT_ROOT_CLUSTER = 2
        private const val DEFAULT_FSINFO_SECTOR = 1
        private const val DEFAULT_BACKUP_BOOT_SECTOR = 6
        private const val DEFAULT_VOLUME_ID = 0x46554A49
        private const val DEFAULT_VOLUME_LABEL = "GFX100S    "
        private const val FAT_EOC = 0x0FFFFFFF
        private const val FAT_MASK = 0x0FFFFFFF
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_ARCHIVE = 0x20
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_LFN = 0x0F
        private const val FAT_PAGE_ENTRIES = 1024 // 4 KiB per sparse FAT page
        private val ZERO_BYTES = ByteArray(8192)
        private const val MAX_OPEN_READ_CHANNELS = 16
        private const val MAX_DIR_DEPTH = 32

        fun sidecarPathFor(directory: Path): Path =
            directory.resolveSibling(directory.fileName.toString() + ".fuji-sd.json")

        fun open(
            directory: Path,
            readOnly: Boolean,
            configuredCapacityBytes: Long = 0L,
        ): DirectoryFat32BlockStore {
            val sidecar = sidecarPathFor(directory)
            SdPathSecurity.requireNoSymlinks(directory, "SD directory")
            SdPathSecurity.requireNoSymlinks(sidecar, "SD sidecar")
            require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "SD directory backend target must exist as a directory" }
            require(!Files.exists(sidecar, NOFOLLOW_LINKS) || Files.isRegularFile(sidecar, NOFOLLOW_LINKS)) {
                "SD sidecar must be a regular file"
            }
            return DirectoryFat32BlockStore(directory, sidecar, readOnly, configuredCapacityBytes)
        }

        internal fun computeDefaultVolume(capacityBytes: Long): VolumeGeometry {
            require(capacityBytes > 0L && capacityBytes % SECTOR_BYTES == 0L) {
                "Directory SD capacityBytes must be positive and 512-byte sector aligned"
            }
            val totalSectors = capacityBytes / SECTOR_BYTES
            val partStart = if (totalSectors > DEFAULT_PARTITION_START_LBA + 70000L) DEFAULT_PARTITION_START_LBA else 63L
            require(totalSectors > partStart + 66000L) {
                "Directory FAT32 capacityBytes too small ($capacityBytes); minimum ~34 MiB required for FAT32"
            }
            val partSectors = totalSectors - partStart
            val candidateSpcs = when {
                capacityBytes < 260L * 1024L * 1024L -> listOf(1)
                capacityBytes < 2L * 1024L * 1024L * 1024L -> listOf(8, 4, 2, 1)
                capacityBytes <= 32L * 1024L * 1024L * 1024L -> listOf(64, 32, 16, 8)
                else -> listOf(128, 64)
            }
            for (spc in candidateSpcs) {
                val reserved = DEFAULT_RESERVED_SECTORS
                val numFats = DEFAULT_NUM_FATS
                var spf = 1L
                repeat(16) {
                    val dataSectors = partSectors - reserved - numFats * spf
                    if (dataSectors <= 0L) return@repeat
                    val clusters = dataSectors / spc
                    val neededSpf = ((clusters + 2L) * 4L + SECTOR_BYTES - 1L) / SECTOR_BYTES
                    if (neededSpf == spf) return@repeat
                    spf = neededSpf
                }
                val finalDataSectors = partSectors - reserved - numFats * spf
                val clusters = finalDataSectors / spc
                if (clusters >= MIN_FAT32_CLUSTERS && clusters <= 0x0FFFFFF0L && spf <= Int.MAX_VALUE) {
                    return VolumeGeometry(
                        partitionStartLba = partStart,
                        partitionSectorCount = partSectors,
                        sectorsPerCluster = spc,
                        reservedSectors = reserved,
                        numFats = numFats,
                        sectorsPerFat = spf.toInt(),
                        rootCluster = DEFAULT_ROOT_CLUSTER,
                        fsInfoSector = DEFAULT_FSINFO_SECTOR,
                        backupBootSector = DEFAULT_BACKUP_BOOT_SECTOR,
                        volumeId = DEFAULT_VOLUME_ID,
                        volumeLabel = DEFAULT_VOLUME_LABEL,
                    )
                }
            }
            throw IllegalArgumentException("Unable to compute legal FAT32 geometry for capacityBytes=$capacityBytes")
        }

        private fun le16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)

        private fun putLe16(buf: ByteArray, off: Int, value: Int) {
            buf[off] = (value and 0xFF).toByte()
            buf[off + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        private fun putLe32(buf: ByteArray, off: Int, value: Int) {
            buf[off] = (value and 0xFF).toByte()
            buf[off + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[off + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[off + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun lfnChecksum(shortName11: ByteArray): Int {
            var sum = 0
            for (i in 0 until 11) {
                sum = (((sum and 1) shl 7) + (sum ushr 1) + (shortName11[i].toInt() and 0xFF)) and 0xFF
            }
            return sum
        }

        private fun isValidShortChar(ch: Char): Boolean {
            if (ch in 'A'..'Z' || ch in '0'..'9') return true
            return ch in setOf('$', '%', '\'', '-', '_', '@', '~', '`', '!', '(', ')', '{', '}', '^', '#', '&')
        }

        private fun exactShortNameOrNull(name: String): String? {
            if (name.isEmpty() || name == "." || name == ".." || name.startsWith(".") || name.endsWith(".")) return null
            val dotIdx = name.indexOf('.')
            val base: String
            val ext: String
            if (dotIdx < 0) {
                base = name
                ext = ""
            } else {
                if (name.indexOf('.', dotIdx + 1) >= 0) return null
                base = name.substring(0, dotIdx)
                ext = name.substring(dotIdx + 1)
            }
            if (base.isEmpty() || base.length > 8 || ext.length > 3) return null
            if (!base.all(::isValidShortChar) || !ext.all(::isValidShortChar)) return null
            return base.padEnd(8, ' ') + ext.padEnd(3, ' ')
        }

        private fun generateShortName(name: String, usedShortNames: Set<String>): Pair<String, Boolean> {
            val exact = exactShortNameOrNull(name)
            if (exact != null && exact !in usedShortNames) {
                return exact to false
            }
            val cleaned = name.uppercase()
            val lastDot = cleaned.lastIndexOf('.')
            val rawBase = if (lastDot > 0) cleaned.substring(0, lastDot) else cleaned
            val rawExt = if (lastDot > 0 && lastDot + 1 < cleaned.length) cleaned.substring(lastDot + 1) else ""
            val baseChars = rawBase.filter(::isValidShortChar).ifEmpty { "FILE" }
            val extChars = rawExt.filter(::isValidShortChar).take(3).padEnd(3, ' ')
            for (n in 1..999999) {
                val suffix = "~$n"
                val prefixLen = (8 - suffix.length).coerceAtLeast(1)
                val basePart = (baseChars.take(prefixLen) + suffix).padEnd(8, ' ')
                val candidate = basePart + extChars
                if (candidate !in usedShortNames) {
                    return candidate to true
                }
            }
            error("Exhausted 8.3 short-name generation for $name")
        }

        private fun encodeFatDateTime(epochMillis: Long): Pair<Int, Int> {
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
            val year = dt.year.coerceIn(1980, 2107)
            val date = ((year - 1980) shl 9) or (dt.monthValue shl 5) or dt.dayOfMonth
            val time = (dt.hour shl 11) or (dt.minute shl 5) or (dt.second / 2)
            return date to time
        }

        private fun decodeFatDateTime(date: Int, time: Int): Long {
            val year = ((date ushr 9) and 0x7F) + 1980
            val month = ((date ushr 5) and 0x0F).coerceIn(1, 12)
            val day = (date and 0x1F).coerceIn(1, 28)
            val hour = ((time ushr 11) and 0x1F).coerceIn(0, 23)
            val minute = ((time ushr 5) and 0x3F).coerceIn(0, 59)
            val second = ((time and 0x1F) * 2).coerceIn(0, 58)
            return runCatching {
                LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrDefault(946684800000L) // 2000-01-01T00:00:00Z
        }

        private fun decodeShortName(entry: ByteArray, offset: Int): String {
            val base = String(entry, offset, 8, StandardCharsets.US_ASCII).trimEnd(' ')
            val ext = String(entry, offset + 8, 3, StandardCharsets.US_ASCII).trimEnd(' ')
            return if (ext.isEmpty()) base else "$base.$ext"
        }

        private fun isSafeFileName(name: String): Boolean {
            if (name.isEmpty() || name == "." || name == ".." || name.length > 255) return false
            for (ch in name) {
                if (ch.code < 0x20 || ch in setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')) return false
            }
            return true
        }
    }

    internal data class VolumeGeometry(
        val partitionStartLba: Long,
        val partitionSectorCount: Long,
        val sectorsPerCluster: Int,
        val reservedSectors: Int,
        val numFats: Int,
        val sectorsPerFat: Int,
        val rootCluster: Int,
        val fsInfoSector: Int,
        val backupBootSector: Int,
        val volumeId: Int,
        val volumeLabel: String,
    ) {
        val clusterBytes: Int get() = sectorsPerCluster * SECTOR_BYTES
        val fat1StartLba: Long get() = partitionStartLba + reservedSectors
        val fat2StartLba: Long get() = fat1StartLba + sectorsPerFat
        val dataStartLba: Long get() = partitionStartLba + reservedSectors + numFats.toLong() * sectorsPerFat.toLong()
        val dataSectors: Long get() = partitionSectorCount - reservedSectors - numFats.toLong() * sectorsPerFat.toLong()
        val clusterCount: Long get() = dataSectors / sectorsPerCluster
        val maxCluster: Long get() = clusterCount + 1L

        fun clusterToStartLba(cluster: Int): Long = dataStartLba + (cluster.toLong() - 2L) * sectorsPerCluster.toLong()

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "partitionStartLba" to partitionStartLba,
            "partitionSectorCount" to partitionSectorCount,
            "sectorsPerCluster" to sectorsPerCluster,
            "reservedSectors" to reservedSectors,
            "numFats" to numFats,
            "sectorsPerFat" to sectorsPerFat,
            "rootCluster" to rootCluster,
            "fsInfoSector" to fsInfoSector,
            "backupBootSector" to backupBootSector,
            "volumeId" to volumeId,
            "volumeLabel" to volumeLabel,
        )
    }

    internal data class TrackedEntry(
        val relativePath: String,
        val isDirectory: Boolean,
        val shortName: String,
        val attributes: Int,
        val startCluster: Int,
        val clusters: List<Int>,
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
        val hostMtimeNanos: Long,
        val hostSize: Long,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "relativePath" to relativePath,
            "isDirectory" to isDirectory,
            "shortName" to shortName,
            "attributes" to attributes,
            "startCluster" to startCluster,
            "clusters" to clusters,
            "sizeBytes" to sizeBytes,
            "mtimeEpochMillis" to mtimeEpochMillis,
            "hostMtimeNanos" to hostMtimeNanos,
            "hostSize" to hostSize,
        )
    }

    private data class FileClusterSlice(
        val relativePath: String,
        val fileByteOffset: Long,
        val fileSize: Long,
    )

    private data class ScannedHostEntry(
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
        val mtimeNanos: Long,
    )

    private data class ParsedFatEntry(
        val relativePath: String,
        val isDirectory: Boolean,
        val shortName: String,
        val attributes: Int,
        val startCluster: Int,
        val clusters: List<Int>,
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
    )

    override val backendKind: String = "directory"
    override val capacityBytes: Long
    private val totalSectors: Long
    private var volume: VolumeGeometry
    private val rawSectors = TreeMap<Long, ByteArray>()
    private val trackedEntries = LinkedHashMap<String, TrackedEntry>()
    private val fatPages = TreeMap<Int, IntArray>() // pageIndex -> FAT_PAGE_ENTRIES ints
    private val dirClusterBytes = HashMap<Int, ByteArray>()
    private val fileClusterMap = HashMap<Int, FileClusterSlice>()
    private val dirtySectors = PendingSdSectors(sidecarPath.toAbsolutePath().parent)
    private val fatEntryBytes = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val readChannels = object : LinkedHashMap<Path, FileChannel>(MAX_OPEN_READ_CHANNELS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, FileChannel>?): Boolean {
            if (size > MAX_OPEN_READ_CHANNELS && eldest != null) {
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }

    private var closed = false
    private var dirtyMetadataPending = false
    private var writeGeneration = 0L
    private var hashedGeneration = -1L
    private var cachedSha256: String? = null

    override val isOpen: Boolean
        get() = !closed

    init {
        val loadedSidecar = if (Files.exists(sidecarPath, NOFOLLOW_LINKS)) {
            loadSidecar(sidecarPath)
        } else null

        if (loadedSidecar != null) {
            capacityBytes = loadedSidecar.capacityBytes
            if (configuredCapacityBytes > 0L) {
                require(configuredCapacityBytes == capacityBytes) {
                    "Configured slotCapacityBytes ($configuredCapacityBytes) differs from sidecar capacityBytes ($capacityBytes)"
                }
            }
            totalSectors = capacityBytes / SECTOR_BYTES
            volume = loadedSidecar.volume
            rawSectors.putAll(loadedSidecar.rawSectors)
            mountIncremental(loadedSidecar.entries)
        } else {
            require(configuredCapacityBytes > 0L) {
                "Directory SD backend requires explicit slotCapacityBytes > 0 when no sidecar exists yet"
            }
            capacityBytes = configuredCapacityBytes
            totalSectors = capacityBytes / SECTOR_BYTES
            volume = computeDefaultVolume(capacityBytes)
            initializeBootSectors(volume, rawSectors)
            mountIncremental(emptyList())
        }

        if (!readOnly) {
            persistSidecar()
        }
    }

    private data class LoadedSidecar(
        val capacityBytes: Long,
        val volume: VolumeGeometry,
        val rawSectors: Map<Long, ByteArray>,
        val entries: List<TrackedEntry>,
    )

    private fun loadSidecar(path: Path): LoadedSidecar {
        val root = SdSidecarJson.parseObject(Files.readString(path, StandardCharsets.UTF_8))
        val schema = (root["schema"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing sidecar schema")
        require(schema == SIDECAR_SCHEMA) { "Unsupported SD sidecar schema: $schema" }
        val cap = (root["capacityBytes"] as? Number)?.toLong() ?: throw IllegalArgumentException("Missing sidecar capacityBytes")
        require(cap > 0L && cap % SECTOR_BYTES == 0L) { "Invalid sidecar capacityBytes: $cap" }

        val volMap = root["volume"] as? Map<*, *> ?: throw IllegalArgumentException("Missing sidecar volume")
        fun volLong(k: String): Long = (volMap[k] as? Number)?.toLong() ?: throw IllegalArgumentException("Missing volume.$k")
        fun volInt(k: String): Int = volLong(k).toInt()
        val label = (volMap["volumeLabel"] as? String ?: DEFAULT_VOLUME_LABEL).take(11).padEnd(11, ' ')
        val vol = VolumeGeometry(
            partitionStartLba = volLong("partitionStartLba"),
            partitionSectorCount = volLong("partitionSectorCount"),
            sectorsPerCluster = volInt("sectorsPerCluster"),
            reservedSectors = volInt("reservedSectors"),
            numFats = volInt("numFats"),
            sectorsPerFat = volInt("sectorsPerFat"),
            rootCluster = volInt("rootCluster"),
            fsInfoSector = volInt("fsInfoSector"),
            backupBootSector = volInt("backupBootSector"),
            volumeId = volInt("volumeId"),
            volumeLabel = label,
        )

        val rawMap = LinkedHashMap<Long, ByteArray>()
        val rawObj = root["rawSectors"] as? Map<*, *>
        if (rawObj != null) {
            for ((k, v) in rawObj) {
                val lba = (k as? String)?.toLongOrNull() ?: throw IllegalArgumentException("Invalid rawSector key: $k")
                val b64 = v as? String ?: throw IllegalArgumentException("Invalid rawSector payload at $lba")
                val bytes = Base64.getDecoder().decode(b64)
                require(bytes.size == SECTOR_BYTES) { "rawSector $lba length != 512" }
                rawMap[lba] = bytes
            }
        }

        val entries = ArrayList<TrackedEntry>()
        val rawEntries = root["entries"] as? List<*>
        if (rawEntries != null) {
            for (item in rawEntries) {
                val row = item as? Map<*, *> ?: continue
                val rel = row["relativePath"] as? String ?: continue
                val isDir = row["isDirectory"] as? Boolean ?: continue
                val shortName = (row["shortName"] as? String ?: continue).take(11).padEnd(11, ' ')
                val attrs = (row["attributes"] as? Number)?.toInt() ?: (if (isDir) ATTR_DIRECTORY else ATTR_ARCHIVE)
                val startCluster = (row["startCluster"] as? Number)?.toInt() ?: 0
                val clusters = (row["clusters"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }
                val sizeBytes = (row["sizeBytes"] as? Number)?.toLong() ?: 0L
                val mtime = (row["mtimeEpochMillis"] as? Number)?.toLong() ?: 0L
                val hostMtime = (row["hostMtimeNanos"] as? Number)?.toLong() ?: 0L
                val hostSize = (row["hostSize"] as? Number)?.toLong() ?: sizeBytes
                entries.add(TrackedEntry(rel, isDir, shortName, attrs, startCluster, clusters, sizeBytes, mtime, hostMtime, hostSize))
            }
        }
        return LoadedSidecar(cap, vol, rawMap, entries)
    }

    private fun persistSidecar() {
        val rawEncoded = LinkedHashMap<String, String>()
        for ((lba, bytes) in rawSectors) {
            if (bytes.any { it != 0.toByte() }) {
                rawEncoded[lba.toString()] = Base64.getEncoder().encodeToString(bytes)
            }
        }
        val doc = linkedMapOf<String, Any?>(
            "schema" to SIDECAR_SCHEMA,
            "capacityBytes" to capacityBytes,
            "sectorSize" to SECTOR_BYTES,
            "formatted" to true,
            "volume" to volume.toMap(),
            "rawSectors" to rawEncoded,
            "entries" to trackedEntries.values.map { it.toMap() },
        )
        SdSidecarJson.writeAtomic(sidecarPath, doc)
    }

    private fun initializeBootSectors(vol: VolumeGeometry, targetRaw: MutableMap<Long, ByteArray>) {
        // LBA 0: MBR (unless superfloppy partitionStartLba == 0)
        if (vol.partitionStartLba > 0L) {
            val mbr = ByteArray(SECTOR_BYTES)
            val pe = 0x1BE
            mbr[pe] = 0x00
            mbr[pe + 1] = 0xFE.toByte()
            mbr[pe + 2] = 0xFF.toByte()
            mbr[pe + 3] = 0xFF.toByte()
            mbr[pe + 4] = 0x0C // FAT32 LBA
            mbr[pe + 5] = 0xFE.toByte()
            mbr[pe + 6] = 0xFF.toByte()
            mbr[pe + 7] = 0xFF.toByte()
            putLe32(mbr, pe + 8, vol.partitionStartLba.toInt())
            putLe32(mbr, pe + 12, vol.partitionSectorCount.toInt())
            mbr[0x1FE] = 0x55
            mbr[0x1FF] = 0xAA.toByte()
            targetRaw[0L] = mbr
        }

        // VBR / BPB
        val vbr = ByteArray(SECTOR_BYTES)
        vbr[0] = 0xEB.toByte()
        vbr[1] = 0x58
        vbr[2] = 0x90.toByte()
        "FF-DSC01".toByteArray(StandardCharsets.US_ASCII).copyInto(vbr, 3)
        putLe16(vbr, 0x0B, SECTOR_BYTES)
        vbr[0x0D] = vol.sectorsPerCluster.toByte()
        putLe16(vbr, 0x0E, vol.reservedSectors)
        vbr[0x10] = vol.numFats.toByte()
        putLe16(vbr, 0x11, 0)
        putLe16(vbr, 0x13, 0)
        vbr[0x15] = 0xF8.toByte()
        putLe16(vbr, 0x16, 0)
        putLe16(vbr, 0x18, 63)
        putLe16(vbr, 0x1A, 255)
        putLe32(vbr, 0x1C, vol.partitionStartLba.toInt())
        putLe32(vbr, 0x20, vol.partitionSectorCount.toInt())
        putLe32(vbr, 0x24, vol.sectorsPerFat)
        putLe16(vbr, 0x28, 0)
        putLe16(vbr, 0x2A, 0)
        putLe32(vbr, 0x2C, vol.rootCluster)
        putLe16(vbr, 0x30, vol.fsInfoSector)
        putLe16(vbr, 0x32, vol.backupBootSector)
        vbr[0x40] = 0x80.toByte()
        vbr[0x42] = 0x29
        putLe32(vbr, 0x43, vol.volumeId)
        vol.volumeLabel.take(11).padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII).copyInto(vbr, 0x47)
        "FAT32   ".toByteArray(StandardCharsets.US_ASCII).copyInto(vbr, 0x52)
        vbr[0x1FE] = 0x55
        vbr[0x1FF] = 0xAA.toByte()
        targetRaw[vol.partitionStartLba] = vbr
        if (vol.backupBootSector > 0 && vol.backupBootSector < vol.reservedSectors) {
            targetRaw[vol.partitionStartLba + vol.backupBootSector] = vbr.copyOf()
        }
        updateFsInfoSectors(vol, targetRaw, freeCount = (vol.clusterCount - 1L).toInt(), nextFree = vol.rootCluster + 1)
    }

    private fun updateFsInfoSectors(
        vol: VolumeGeometry,
        targetRaw: MutableMap<Long, ByteArray>,
        freeCount: Int,
        nextFree: Int,
    ) {
        if (vol.fsInfoSector <= 0 || vol.fsInfoSector >= vol.reservedSectors) return
        val fsInfo = ByteArray(SECTOR_BYTES)
        putLe32(fsInfo, 0x000, 0x41615252)
        putLe32(fsInfo, 0x1E4, 0x61417272)
        putLe32(fsInfo, 0x1E8, freeCount)
        putLe32(fsInfo, 0x1EC, nextFree)
        putLe32(fsInfo, 0x1FC, 0xAA550000.toInt())
        targetRaw[vol.partitionStartLba + vol.fsInfoSector] = fsInfo
        if (vol.backupBootSector > 0 && vol.backupBootSector + vol.fsInfoSector < vol.reservedSectors) {
            targetRaw[vol.partitionStartLba + vol.backupBootSector + vol.fsInfoSector] = fsInfo.copyOf()
        }
    }

    private fun scanHostDirectory(): List<ScannedHostEntry> {
        val result = ArrayList<ScannedHostEntry>()
        fun walk(currentDir: Path, prefix: String, depth: Int) {
            require(depth <= MAX_DIR_DEPTH) { "SD host directory nesting exceeds $MAX_DIR_DEPTH" }
            val children = Files.list(currentDir).use { stream ->
                stream.sorted(Comparator.comparing { it.fileName.toString() }).toList()
            }
            val seenLower = HashSet<String>()
            for (child in children) {
                require(!Files.isSymbolicLink(child)) { "Symlink refused inside SD host directory: $child" }
                val name = child.fileName.toString()
                require(isSafeFileName(name)) { "Illegal FAT32 filename in SD host directory: $name" }
                val lower = name.lowercase()
                require(seenLower.add(lower)) {
                    "Case-insensitive filename collision in SD host directory: $prefix$name"
                }
                val attrs = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                val instant = attrs.lastModifiedTime().toInstant()
                val mtimeMillis = instant.toEpochMilli()
                val mtimeNanos = instant.epochSecond * 1_000_000_000L + instant.nano
                if (attrs.isDirectory) {
                    result.add(ScannedHostEntry(rel, true, 0L, mtimeMillis, mtimeNanos))
                    walk(child, rel, depth + 1)
                } else if (attrs.isRegularFile) {
                    require(attrs.size() <= 0xFFFFFFFFL) { "FAT32 file exceeds 4 GiB limit: $rel" }
                    result.add(ScannedHostEntry(rel, false, attrs.size(), mtimeMillis, mtimeNanos))
                }
            }
        }
        walk(directory, "", 0)
        return result
    }

    /**
     * Incrementally mounts host files against previously persisted sidecar entries:
     * unchanged files/directories keep their exact prior clusters and 8.3 short names;
     * deleted files free their clusters; new or modified files allocate cleanly.
     */
    private fun mountIncremental(previousEntries: List<TrackedEntry>) {
        val scanned = scanHostDirectory()
        val prevByPath = previousEntries.associateBy { it.relativePath }
        val clusterBytes = volume.clusterBytes
        val maxCluster = volume.maxCluster.toInt()
        val allocated = HashSet<Int>()
        allocated.add(volume.rootCluster)

        val childrenByParent = LinkedHashMap<String, MutableList<ScannedHostEntry>>()
        childrenByParent[""] = ArrayList()
        for (entry in scanned) {
            val parent = entry.relativePath.substringBeforeLast('/', "")
            childrenByParent.computeIfAbsent(parent) { ArrayList() }.add(entry)
            if (entry.isDirectory) {
                childrenByParent.computeIfAbsent(entry.relativePath) { ArrayList() }
            }
        }

        // Keep surviving aliases before assigning new ones. The emitted short name also
        // determines whether the directory needs VFAT long-name records.
        val shortNames = HashMap<String, String>()
        for (children in childrenByParent.values) {
            val used = HashSet<String>()
            for (item in children) {
                val previous = prevByPath[item.relativePath]?.shortName ?: continue
                if (previous.length == 11 && used.add(previous)) shortNames[item.relativePath] = previous
            }
            for (item in children) {
                if (item.relativePath in shortNames) continue
                val alias = generateShortName(item.relativePath.substringAfterLast('/'), used).first
                used.add(alias)
                shortNames[item.relativePath] = alias
            }
        }

        fun directoryClusters(path: String): Int {
            val root = path.isEmpty()
            var records = 1L // end marker
            if (!root) records += 2 // . and ..
            else if (volume.volumeLabel.isNotBlank() && volume.volumeLabel != "NO NAME    ") records++
            for (item in childrenByParent[path].orEmpty()) {
                val name = item.relativePath.substringAfterLast('/')
                records++
                if (exactShortNameOrNull(name) != shortNames.getValue(item.relativePath)) {
                    records += (name.length + 12) / 13
                }
            }
            return ((records * 32 + clusterBytes - 1) / clusterBytes).toInt()
        }

        val keptByPath = HashMap<String, TrackedEntry>()

        // Pass 1: preserve clusters & short names for surviving unchanged files and surviving directories
        for (item in scanned) {
            val prev = prevByPath[item.relativePath] ?: continue
            if (prev.isDirectory != item.isDirectory) continue
            if (prev.shortName != shortNames.getValue(item.relativePath)) continue

            if (item.isDirectory) {
                val neededClusters = directoryClusters(item.relativePath)
                val retained = prev.clusters.take(neededClusters)
                val validPrev = retained.size == neededClusters &&
                    retained.isNotEmpty() &&
                    retained.all { it in 2..maxCluster && it !in allocated } &&
                    retained.distinct().size == retained.size
                if (validPrev) {
                    allocated.addAll(retained)
                    keptByPath[item.relativePath] = prev.copy(
                        clusters = retained,
                        mtimeEpochMillis = item.mtimeEpochMillis,
                        hostMtimeNanos = item.mtimeNanos,
                    )
                }
            } else {
                val neededClusters = if (item.sizeBytes == 0L) 0 else ((item.sizeBytes + clusterBytes - 1L) / clusterBytes).toInt()
                val unchanged = item.sizeBytes == prev.hostSize && item.mtimeNanos == prev.hostMtimeNanos
                val validPrev = prev.clusters.size == neededClusters &&
                    prev.clusters.all { it in 2..maxCluster && it !in allocated } &&
                    prev.clusters.distinct().size == prev.clusters.size
                if (unchanged && validPrev) {
                    allocated.addAll(prev.clusters)
                    keptByPath[item.relativePath] = prev
                }
            }
        }

        // Reserve extra clusters if root directory spans multiple clusters
        var nextFreeCursor = volume.rootCluster + 1
        fun allocClusters(count: Int): List<Int> {
            if (count == 0) return emptyList()
            val out = ArrayList<Int>(count)
            while (out.size < count) {
                while (nextFreeCursor <= maxCluster && nextFreeCursor in allocated) nextFreeCursor++
                require(nextFreeCursor <= maxCluster) { "Directory SD card out of free FAT32 clusters (capacity=$capacityBytes)" }
                allocated.add(nextFreeCursor)
                out.add(nextFreeCursor)
                nextFreeCursor++
            }
            return out
        }

        val rootDirClusters = ArrayList<Int>().apply {
            add(volume.rootCluster)
            val extraRoot = directoryClusters("") - 1
            if (extraRoot > 0) addAll(allocClusters(extraRoot))
        }

        // Pass 2: allocate clusters & short names for new or modified entries
        trackedEntries.clear()
        for (item in scanned) {
            val preserved = keptByPath[item.relativePath]
            if (preserved != null) {
                trackedEntries[item.relativePath] = preserved
                continue
            }
            val shortName = shortNames.getValue(item.relativePath)

            if (item.isDirectory) {
                val needed = directoryClusters(item.relativePath)
                val clusters = allocClusters(needed)
                trackedEntries[item.relativePath] = TrackedEntry(
                    relativePath = item.relativePath,
                    isDirectory = true,
                    shortName = shortName,
                    attributes = ATTR_DIRECTORY,
                    startCluster = clusters.first(),
                    clusters = clusters,
                    sizeBytes = 0L,
                    mtimeEpochMillis = item.mtimeEpochMillis,
                    hostMtimeNanos = item.mtimeNanos,
                    hostSize = 0L,
                )
            } else {
                val needed = if (item.sizeBytes == 0L) 0 else ((item.sizeBytes + clusterBytes - 1L) / clusterBytes).toInt()
                val clusters = allocClusters(needed)
                trackedEntries[item.relativePath] = TrackedEntry(
                    relativePath = item.relativePath,
                    isDirectory = false,
                    shortName = shortName,
                    attributes = ATTR_ARCHIVE,
                    startCluster = clusters.firstOrNull() ?: 0,
                    clusters = clusters,
                    sizeBytes = item.sizeBytes,
                    mtimeEpochMillis = item.mtimeEpochMillis,
                    hostMtimeNanos = item.mtimeNanos,
                    hostSize = item.sizeBytes,
                )
            }
        }

        // Rebuild FAT, directory clusters, file cluster map, and FSInfo
        rebuildSynthesizedStructures(rootDirClusters, allocExtra = ::allocClusters, allocatedCount = allocated.size, nextFreeHint = nextFreeCursor.coerceAtMost(maxCluster))
    }

    private fun rebuildSynthesizedStructures(
        rootDirClusters: MutableList<Int>,
        allocExtra: (Int) -> List<Int>,
        allocatedCount: Int,
        nextFreeHint: Int,
    ) {
        fatPages.clear()
        dirClusterBytes.clear()
        fileClusterMap.clear()

        setFatEntry(0, 0x0FFFFFF8)
        setFatEntry(1, FAT_EOC)

        val entriesByParent = LinkedHashMap<String, MutableList<TrackedEntry>>()
        entriesByParent[""] = ArrayList()
        for (entry in trackedEntries.values) {
            val parent = entry.relativePath.substringBeforeLast('/', "")
            entriesByParent.computeIfAbsent(parent) { ArrayList() }.add(entry)
            if (entry.isDirectory) {
                entriesByParent.computeIfAbsent(entry.relativePath) { ArrayList() }
            }
        }

        var extraAllocated = 0

        fun populateDir(path: String, clusters: MutableList<Int>, parentStartCluster: Int) {
            val rawDir = buildDirectoryPayload(
                isRoot = path.isEmpty(),
                selfCluster = clusters.first(),
                parentCluster = parentStartCluster,
                children = entriesByParent[path].orEmpty(),
            )
            val neededClusters = ((rawDir.size + volume.clusterBytes - 1) / volume.clusterBytes).coerceAtLeast(1)
            if (neededClusters > clusters.size) {
                val added = allocExtra(neededClusters - clusters.size)
                clusters.addAll(added)
                extraAllocated += added.size
            }
            writeFatChain(clusters)
            for ((idx, cluster) in clusters.withIndex()) {
                val buf = ByteArray(volume.clusterBytes)
                val srcOff = idx * volume.clusterBytes
                val copyLen = minOf(volume.clusterBytes, rawDir.size - srcOff).coerceAtLeast(0)
                if (copyLen > 0) System.arraycopy(rawDir, srcOff, buf, 0, copyLen)
                dirClusterBytes[cluster] = buf
            }
        }

        // Root directory
        populateDir("", rootDirClusters, 0)

        // Subdirectories & files
        for (entry in trackedEntries.values.toList()) {
            if (entry.isDirectory) {
                val parentPath = entry.relativePath.substringBeforeLast('/', "")
                val parentCluster = if (parentPath.isEmpty()) 0 else trackedEntries.getValue(parentPath).startCluster
                val mutClusters = entry.clusters.toMutableList()
                populateDir(entry.relativePath, mutClusters, parentCluster)
                if (mutClusters.size != entry.clusters.size) {
                    trackedEntries[entry.relativePath] = entry.copy(clusters = mutClusters)
                }
            } else {
                writeFatChain(entry.clusters)
                for ((idx, cluster) in entry.clusters.withIndex()) {
                    fileClusterMap[cluster] = FileClusterSlice(
                        relativePath = entry.relativePath,
                        fileByteOffset = idx.toLong() * volume.clusterBytes.toLong(),
                        fileSize = entry.sizeBytes,
                    )
                }
            }
        }

        val totalUsed = allocatedCount + extraAllocated
        val freeCount = (volume.clusterCount - totalUsed.toLong()).coerceAtLeast(0L).toInt()
        updateFsInfoSectors(volume, rawSectors, freeCount = freeCount, nextFree = nextFreeHint)
    }

    private fun buildDirectoryPayload(
        isRoot: Boolean,
        selfCluster: Int,
        parentCluster: Int,
        children: List<TrackedEntry>,
    ): ByteArray {
        val records = ArrayList<ByteArray>()
        if (isRoot) {
            val label = volume.volumeLabel.take(11).padEnd(11, ' ')
            if (label.isNotBlank() && label != "NO NAME    ") {
                records.add(makeShortDirRecord(label, ATTR_VOLUME_ID, 0, 0L, 946684800000L))
            }
        } else {
            records.add(makeShortDirRecord(".          ", ATTR_DIRECTORY, selfCluster, 0L, 946684800000L))
            records.add(makeShortDirRecord("..         ", ATTR_DIRECTORY, parentCluster, 0L, 946684800000L))
        }
        for (child in children) {
            val fileName = child.relativePath.substringAfterLast('/')
            val short11 = child.shortName.toByteArray(StandardCharsets.US_ASCII)
            val needsLfn = exactShortNameOrNull(fileName) != child.shortName
            if (needsLfn) {
                val checksum = lfnChecksum(short11)
                val lfnRecords = buildLfnRecords(fileName, checksum)
                records.addAll(lfnRecords)
            }
            records.add(makeShortDirRecord(child.shortName, child.attributes, child.startCluster, child.sizeBytes, child.mtimeEpochMillis))
        }
        val out = ByteArray((records.size + 1) * 32)
        records.forEachIndexed { idx, rec ->
            System.arraycopy(rec, 0, out, idx * 32, 32)
        }
        return out
    }

    private fun buildLfnRecords(name: String, checksum: Int): List<ByteArray> {
        val chars = name.toCharArray()
        val count = (chars.size + 12) / 13
        val result = ArrayList<ByteArray>(count)
        for (seq in count downTo 1) {
            val rec = ByteArray(32)
            rec[0] = (seq or if (seq == count) 0x40 else 0).toByte()
            rec[11] = ATTR_LFN.toByte()
            rec[12] = 0
            rec[13] = checksum.toByte()
            putLe16(rec, 26, 0)
            val baseIdx = (seq - 1) * 13
            val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (i in 0 until 13) {
                val charIdx = baseIdx + i
                val codeUnit = when {
                    charIdx < chars.size -> chars[charIdx].code
                    charIdx == chars.size -> 0x0000
                    else -> 0xFFFF
                }
                putLe16(rec, positions[i], codeUnit)
            }
            result.add(rec)
        }
        return result
    }

    private fun makeShortDirRecord(
        shortName11: String,
        attributes: Int,
        startCluster: Int,
        sizeBytes: Long,
        mtimeMillis: Long,
    ): ByteArray {
        val rec = ByteArray(32)
        shortName11.take(11).padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII).copyInto(rec, 0)
        rec[11] = attributes.toByte()
        val (date, time) = encodeFatDateTime(mtimeMillis)
        putLe16(rec, 14, time)
        putLe16(rec, 16, date)
        putLe16(rec, 18, date)
        putLe16(rec, 20, (startCluster ushr 16) and 0xFFFF)
        putLe16(rec, 22, time)
        putLe16(rec, 24, date)
        putLe16(rec, 26, startCluster and 0xFFFF)
        putLe32(rec, 28, sizeBytes.toInt())
        return rec
    }

    private fun setFatEntry(cluster: Int, value: Int) {
        val pageIdx = cluster / FAT_PAGE_ENTRIES
        val slot = cluster % FAT_PAGE_ENTRIES
        val page = fatPages.computeIfAbsent(pageIdx) { IntArray(FAT_PAGE_ENTRIES) }
        page[slot] = value
    }

    private fun getFatEntryBase(cluster: Int): Int {
        val page = fatPages[cluster / FAT_PAGE_ENTRIES] ?: return 0
        return page[cluster % FAT_PAGE_ENTRIES]
    }

    private fun writeFatChain(clusters: List<Int>) {
        if (clusters.isEmpty()) return
        for (i in 0 until clusters.size - 1) {
            setFatEntry(clusters[i], clusters[i + 1])
        }
        setFatEntry(clusters.last(), FAT_EOC)
    }

    private fun readHostFileSlice(slice: FileClusterSlice, inClusterOffset: Int, length: Int, target: ByteBuffer) {
        val start = target.position()
        val end = start + length
        val filePos = slice.fileByteOffset + inClusterOffset
        val available = (slice.fileSize - filePos).coerceIn(0L, length.toLong()).toInt()
        val output = target.duplicate()
        if (available > 0) {
            val channel = readChannels.computeIfAbsent(directory.resolve(slice.relativePath)) { path ->
                FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS)
            }
            output.limit(start + available)
            try {
                while (output.hasRemaining()) {
                    if (channel.read(output, filePos + (output.position() - start)) <= 0) break
                }
            } catch (error: java.io.IOException) {
                // A later failed read must leave the caller positioned after the last completed cluster.
                val received = output.position() - start
                val inCompletedClusters = (inClusterOffset.toLong() + received) / volume.clusterBytes * volume.clusterBytes
                target.position(start + (inCompletedClusters - inClusterOffset).coerceAtLeast(0L).toInt())
                throw error
            }
        }
        output.limit(end)
        while (output.hasRemaining()) output.put(ZERO_BYTES, 0, minOf(output.remaining(), ZERO_BYTES.size))
        target.position(end)
    }

    private fun closeReadChannels() {
        for (ch in readChannels.values) {
            runCatching { ch.close() }
        }
        readChannels.clear()
    }

    private fun readBaseSector(lba: Long): ByteArray {
        rawSectors[lba]?.let { return it.copyOf() }
        val vol = volume
        if (lba >= vol.fat1StartLba && lba < vol.dataStartLba) {
            val fatSectorIndex = if (lba < vol.fat1StartLba + vol.sectorsPerFat) {
                (lba - vol.fat1StartLba).toInt()
            } else {
                val rel = lba - vol.fat1StartLba
                (rel % vol.sectorsPerFat).toInt()
            }
            val firstEntry = fatSectorIndex * (SECTOR_BYTES / 4)
            val page = fatPages[firstEntry / FAT_PAGE_ENTRIES] ?: return ByteArray(SECTOR_BYTES)
            val out = ByteArray(SECTOR_BYTES)
            val pageOffset = firstEntry % FAT_PAGE_ENTRIES
            for (i in 0 until 128) {
                putLe32(out, i * 4, page[pageOffset + i])
            }
            return out
        }
        if (lba >= vol.dataStartLba && lba < vol.partitionStartLba + vol.partitionSectorCount) {
            val dataOffset = lba - vol.dataStartLba
            val cluster = (dataOffset / vol.sectorsPerCluster).toInt() + 2
            val sectorInCluster = (dataOffset % vol.sectorsPerCluster).toInt()
            val byteOff = sectorInCluster * SECTOR_BYTES
            dirClusterBytes[cluster]?.let { buf ->
                return buf.copyOfRange(byteOff, byteOff + SECTOR_BYTES)
            }
            fileClusterMap[cluster]?.let { slice ->
                val bytes = ByteArray(SECTOR_BYTES)
                readHostFileSlice(slice, byteOff, SECTOR_BYTES, ByteBuffer.wrap(bytes))
                return bytes
            }
        }
        return ByteArray(SECTOR_BYTES)
    }

    private fun readLayeredSector(lba: Long): ByteArray =
        dirtySectors[lba] ?: readBaseSector(lba)

    private fun readLayeredFatEntry(vol: VolumeGeometry, cluster: Int): Int {
        val sectorIndex = cluster / (SECTOR_BYTES / Int.SIZE_BYTES)
        val offsetInSector = (cluster % (SECTOR_BYTES / Int.SIZE_BYTES)) * Int.SIZE_BYTES
        val lba = vol.fat1StartLba + sectorIndex
        fatEntryBytes.clear()
        if (dirtySectors.read(lba, offsetInSector, fatEntryBytes) != 0) return fatEntryBytes.getInt(0) and FAT_MASK
        rawSectors[lba]?.let { return le32(it, offsetInSector) and FAT_MASK }
        if (vol.fat1StartLba == volume.fat1StartLba && sectorIndex < volume.sectorsPerFat) {
            return getFatEntryBase(cluster) and FAT_MASK
        }
        // During a guest format the new BPB may put this FAT entry over another old physical sector.
        return le32(readBaseSector(lba), offsetInSector) and FAT_MASK
    }

    private fun readLayeredCluster(vol: VolumeGeometry, cluster: Int, out: ByteArray = ByteArray(vol.clusterBytes)): ByteArray {
        val baseLba = vol.clusterToStartLba(cluster)
        val target = ByteBuffer.wrap(out, 0, vol.clusterBytes)
        while (target.hasRemaining()) {
            val lba = baseLba + target.position() / SECTOR_BYTES
            if (dirtySectors.read(lba, 0, target) == 0) target.put(readBaseSector(lba))
        }
        return out
    }

    private fun readLayeredClusterChain(vol: VolumeGeometry, startCluster: Int, maxClusters: Int): List<Int>? {
        if (startCluster < 2 || startCluster.toLong() > vol.maxCluster) return null
        val chain = ArrayList<Int>()
        val seen = HashSet<Int>()
        var cur = startCluster
        while (true) {
            if (!seen.add(cur)) return null // cycle -> inconsistent/mid-write
            chain.add(cur)
            if (chain.size > maxClusters) return null
            val next = readLayeredFatEntry(vol, cur)
            if (next >= 0x0FFFFFF8) break
            if (next < 2 || next.toLong() > vol.maxCluster) return null // incomplete chain
            cur = next
        }
        return chain
    }

    private fun parseLiveVolumeGeometry(): VolumeGeometry? {
        val lba0 = readLayeredSector(0L)
        if (lba0[0x1FE] != 0x55.toByte() || lba0[0x1FF] != 0xAA.toByte()) return null

        // Check if LBA 0 is itself a superfloppy FAT32 VBR or an MBR with partition entry
        val candidateStarts = ArrayList<Long>()
        val partType = lba0[0x1BE + 4].toInt() and 0xFF
        val partStart = le32(lba0, 0x1BE + 8).toLong() and 0xFFFFFFFFL
        val partCount = le32(lba0, 0x1BE + 12).toLong() and 0xFFFFFFFFL
        if (partType != 0 && partStart > 0L && partStart < totalSectors && partCount > 0L && partStart + partCount <= totalSectors) {
            candidateStarts.add(partStart)
        }
        candidateStarts.add(0L)

        for (startLba in candidateStarts) {
            val vbr = if (startLba == 0L) lba0 else readLayeredSector(startLba)
            if (vbr[0x1FE] != 0x55.toByte() || vbr[0x1FF] != 0xAA.toByte()) continue
            val bytesPerSec = le16(vbr, 0x0B)
            val spc = vbr[0x0D].toInt() and 0xFF
            val reserved = le16(vbr, 0x0E)
            val numFats = vbr[0x10].toInt() and 0xFF
            val rootEntCnt = le16(vbr, 0x11)
            val fatSz16 = le16(vbr, 0x16)
            val totSec32 = le32(vbr, 0x20).toLong() and 0xFFFFFFFFL
            val fatSz32 = le32(vbr, 0x24)
            val rootCluster = le32(vbr, 0x2C)
            val fsInfo = le16(vbr, 0x30)
            val backupBoot = le16(vbr, 0x32)
            val volId = le32(vbr, 0x43)
            val volLabel = String(vbr, 0x47, 11, StandardCharsets.US_ASCII)

            val validSpc = spc in setOf(1, 2, 4, 8, 16, 32, 64, 128)
            if (bytesPerSec != SECTOR_BYTES || !validSpc || reserved <= 0 || numFats !in 1..2) continue
            if (rootEntCnt != 0 || fatSz16 != 0 || fatSz32 <= 0 || rootCluster < 2) continue
            if (totSec32 <= 0L || startLba + totSec32 > totalSectors) continue
            val dataSectors = totSec32 - reserved.toLong() - numFats.toLong() * fatSz32.toLong()
            if (dataSectors <= 0L) continue
            val clusters = dataSectors / spc
            if (clusters < 2L || rootCluster.toLong() > clusters + 1L) continue

            return VolumeGeometry(
                partitionStartLba = startLba,
                partitionSectorCount = totSec32,
                sectorsPerCluster = spc,
                reservedSectors = reserved,
                numFats = numFats,
                sectorsPerFat = fatSz32,
                rootCluster = rootCluster,
                fsInfoSector = fsInfo,
                backupBootSector = backupBoot,
                volumeId = volId,
                volumeLabel = volLabel,
            )
        }
        return null
    }

    /**
     * Reconciles dirty guest sector writes back into the host directory and sidecar JSON.
     * Mid-format safety: if MBR/BPB or directory cluster chains are incomplete/invalid,
     * host files are NEVER deleted and dirty sectors stay safely buffered.
     */
    private fun reconcileGuestWrites(preserveUnlinkedData: Boolean = false): Boolean {
        if (readOnly || dirtySectors.size == 0) return false
        val liveVol = parseLiveVolumeGeometry() ?: return false
        val rootChain = readLayeredClusterChain(liveVol, liveVol.rootCluster, maxClusters = 4096) ?: return false

        val parsedEntries = LinkedHashMap<String, ParsedFatEntry>()
        var incompleteDetected = false

        fun parseDirectory(prefix: String, chain: List<Int>, depth: Int) {
            if (depth > MAX_DIR_DEPTH) {
                incompleteDetected = true
                return
            }
            val dirData = ByteArray(chain.size * liveVol.clusterBytes)
            chain.forEachIndexed { idx, cluster ->
                val cl = readLayeredCluster(liveVol, cluster)
                System.arraycopy(cl, 0, dirData, idx * liveVol.clusterBytes, liveVol.clusterBytes)
            }

            val lfnParts = TreeMap<Int, String>()
            var lfnChecksumExpected = -1
            var offset = 0
            while (offset + 32 <= dirData.size) {
                val firstByte = dirData[offset].toInt() and 0xFF
                if (firstByte == 0x00) break
                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    lfnChecksumExpected = -1
                    offset += 32
                    continue
                }
                val attr = dirData[offset + 11].toInt() and 0xFF
                if (attr == ATTR_LFN) {
                    val seq = firstByte and 0x1F
                    if ((firstByte and 0x40) != 0) {
                        lfnParts.clear()
                        lfnChecksumExpected = dirData[offset + 13].toInt() and 0xFF
                    }
                    val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
                    val sb = StringBuilder()
                    for (pos in positions) {
                        val code = le16(dirData, offset + pos)
                        if (code == 0x0000 || code == 0xFFFF) break
                        sb.append(code.toChar())
                    }
                    lfnParts[seq] = sb.toString()
                    offset += 32
                    continue
                }
                if ((attr and ATTR_VOLUME_ID) != 0) {
                    lfnParts.clear()
                    lfnChecksumExpected = -1
                    offset += 32
                    continue
                }

                val shortBytes = dirData.copyOfRange(offset, offset + 11)
                val shortName11 = String(shortBytes, StandardCharsets.US_ASCII)
                val lfnName = if (lfnParts.isNotEmpty() && lfnChecksum(shortBytes) == lfnChecksumExpected) {
                    lfnParts.values.joinToString("")
                } else null
                lfnParts.clear()
                lfnChecksumExpected = -1

                val name = lfnName ?: decodeShortName(dirData, offset)
                if (name == "." || name == "..") {
                    offset += 32
                    continue
                }
                if (!isSafeFileName(name)) {
                    offset += 32
                    continue
                }

                val isDir = (attr and ATTR_DIRECTORY) != 0
                val startCluster = (le16(dirData, offset + 20) shl 16) or le16(dirData, offset + 26)
                val fileSize = le32(dirData, offset + 28).toLong() and 0xFFFFFFFFL
                val mtime = decodeFatDateTime(le16(dirData, offset + 24), le16(dirData, offset + 22))
                val relPath = if (prefix.isEmpty()) name else "$prefix/$name"

                if (isDir) {
                    val subChain = readLayeredClusterChain(liveVol, startCluster, maxClusters = 4096)
                    if (subChain == null) {
                        incompleteDetected = true
                        offset += 32
                        continue
                    }
                    parsedEntries[relPath] = ParsedFatEntry(relPath, true, shortName11, attr, startCluster, subChain, 0L, mtime)
                    parseDirectory(relPath, subChain, depth + 1)
                } else {
                    val needed = if (fileSize == 0L) 0 else ((fileSize + liveVol.clusterBytes - 1L) / liveVol.clusterBytes).toInt()
                    val fileChain = if (needed == 0) {
                        emptyList()
                    } else {
                        readLayeredClusterChain(liveVol, startCluster, maxClusters = maxOf(needed + 16, 65536))
                    }
                    if (fileChain == null || fileChain.size < needed) {
                        incompleteDetected = true
                        offset += 32
                        continue
                    }
                    parsedEntries[relPath] = ParsedFatEntry(relPath, false, shortName11, attr, startCluster, fileChain.take(needed), fileSize, mtime)
                }
                offset += 32
            }
        }

        parseDirectory("", rootChain, depth = 0)
        if (incompleteDetected) return false

        val preservedDirty = if (preserveUnlinkedData && liveVol == volume) {
            val referencedClusters = HashSet<Int>(rootChain.size + parsedEntries.size * 4)
            referencedClusters.addAll(rootChain)
            val expectedFatEntries = HashMap<Int, Int>(rootChain.size + parsedEntries.size * 4)
            fun recordExpectedChain(chain: List<Int>) {
                for (i in chain.indices) {
                    expectedFatEntries[chain[i]] = if (i + 1 < chain.size) chain[i + 1] else FAT_EOC
                }
            }
            recordExpectedChain(rootChain)
            for (entry in parsedEntries.values) {
                referencedClusters.addAll(entry.clusters)
                recordExpectedChain(entry.clusters)
            }
            val previouslyTrackedClusters = HashSet<Int>(trackedEntries.size * 4)
            for (entry in trackedEntries.values) {
                previouslyTrackedClusters.addAll(entry.clusters)
            }
            val dataEndLba = liveVol.partitionStartLba + liveVol.partitionSectorCount
            val fatEndLba = liveVol.fat1StartLba + liveVol.numFats.toLong() * liveVol.sectorsPerFat.toLong()
            val unlinkedFatSectors = HashSet<Long>()
            val kept = PendingSdSectors.Selection()
            dirtySectors.forEachRange { start, end ->
                var lba = maxOf(start, liveVol.dataStartLba)
                val dataEnd = minOf(end, dataEndLba)
                while (lba < dataEnd) {
                    val cluster = ((lba - liveVol.dataStartLba) / liveVol.sectorsPerCluster).toInt() + 2
                    val next = minOf(dataEnd, liveVol.clusterToStartLba(cluster) + liveVol.sectorsPerCluster)
                    if (cluster !in referencedClusters && cluster !in previouslyTrackedClusters) {
                        unlinkedFatSectors.add((cluster.toLong() * 4L) / SECTOR_BYTES)
                        kept.add(lba, next)
                    }
                    lba = next
                }
                for (fatLba in maxOf(start, liveVol.fat1StartLba) until minOf(end, fatEndLba)) {
                    // Preserve any dirty FAT sector containing non-zero unlinked/pre-allocated clusters
                    // or a linked cluster chained forward across a 128-cluster FAT-sector boundary
                    // (e.g. an open streaming .MOV file growing chunk-by-chunk before dir entry close).
                    val secBytes = checkNotNull(dirtySectors[fatLba])
                    val fatSecIdx = ((fatLba - liveVol.fat1StartLba) % liveVol.sectorsPerFat.toLong()).toInt()
                    val baseCluster = fatSecIdx * (SECTOR_BYTES / 4)
                    var hasUnlinkedAlloc = false
                    for (i in 0 until (SECTOR_BYTES / 4)) {
                        val cluster = baseCluster + i
                        if (cluster < 2 || cluster.toLong() > liveVol.maxCluster) continue
                        val entry = le32(secBytes, i * 4) and FAT_MASK
                        if (entry == 0) continue
                        val expected = expectedFatEntries[cluster] ?: 0
                        if (entry != expected) {
                            hasUnlinkedAlloc = true
                            break
                        }
                    }
                    if (hasUnlinkedAlloc) {
                        kept.add(fatLba, fatLba + 1)
                    }
                }
            }
            for (fatSecIdx in unlinkedFatSectors) {
                for (fatCopy in 0 until liveVol.numFats) {
                    val fatLba = liveVol.fat1StartLba + fatCopy.toLong() * liveVol.sectorsPerFat + fatSecIdx
                    kept.add(fatLba, fatLba + 1)
                }
            }
            kept
        } else null

        // All FAT32 structures are valid & complete -> materialize guest state cleanly to host directory!
        closeReadChannels()

        // 1. Update rawSectors (MBR + all non-zero reserved sectors from live layered view)
        val newRawSectors = TreeMap<Long, ByteArray>()
        if (liveVol.partitionStartLba > 0L) {
            newRawSectors[0L] = readLayeredSector(0L)
        }
        for (sec in 0 until liveVol.reservedSectors) {
            val lba = liveVol.partitionStartLba + sec
            val bytes = readLayeredSector(lba)
            if (bytes.any { it != 0.toByte() }) {
                newRawSectors[lba] = bytes
            }
        }

        // 2. Create directories & materialize new/modified files atomically
        val newTracked = LinkedHashMap<String, TrackedEntry>()
        for (entry in parsedEntries.values) {
            val hostPath = directory.resolve(entry.relativePath).normalize()
            require(hostPath.startsWith(directory) && hostPath != directory) { "Refused host path escape: ${entry.relativePath}" }
            if (entry.isDirectory) {
                Files.createDirectories(hostPath)
                val attrs = Files.readAttributes(hostPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val inst = attrs.lastModifiedTime().toInstant()
                newTracked[entry.relativePath] = TrackedEntry(
                    relativePath = entry.relativePath,
                    isDirectory = true,
                    shortName = entry.shortName,
                    attributes = entry.attributes,
                    startCluster = entry.startCluster,
                    clusters = entry.clusters,
                    sizeBytes = 0L,
                    mtimeEpochMillis = entry.mtimeEpochMillis,
                    hostMtimeNanos = inst.epochSecond * 1_000_000_000L + inst.nano,
                    hostSize = 0L,
                )
            } else {
                Files.createDirectories(hostPath.parent)
                val needsWrite = !Files.isRegularFile(hostPath, NOFOLLOW_LINKS) ||
                    entry.clusters.any { clusterHasDirtySector(liveVol, it) } ||
                    trackedEntries[entry.relativePath]?.let { prev ->
                        prev.sizeBytes != entry.sizeBytes || prev.clusters != entry.clusters
                    } != false
                if (needsWrite) {
                    writeFileFromLayeredClusters(liveVol, entry, hostPath)
                }
                val attrs = Files.readAttributes(hostPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val inst = attrs.lastModifiedTime().toInstant()
                newTracked[entry.relativePath] = TrackedEntry(
                    relativePath = entry.relativePath,
                    isDirectory = false,
                    shortName = entry.shortName,
                    attributes = entry.attributes,
                    startCluster = entry.startCluster,
                    clusters = entry.clusters,
                    sizeBytes = entry.sizeBytes,
                    mtimeEpochMillis = entry.mtimeEpochMillis,
                    hostMtimeNanos = inst.epochSecond * 1_000_000_000L + inst.nano,
                    hostSize = attrs.size(),
                )
            }
        }

        // 3. Remove previously tracked host files/directories that were deleted or formatted away by guest
        val removed = trackedEntries.values.filter { it.relativePath !in newTracked }
        for (old in removed.filter { !it.isDirectory }) {
            val hostPath = directory.resolve(old.relativePath).normalize()
            if (hostPath.startsWith(directory) && Files.isRegularFile(hostPath, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(hostPath)
            }
        }
        for (oldDir in removed.filter { it.isDirectory }.sortedByDescending { it.relativePath.length }) {
            val hostPath = directory.resolve(oldDir.relativePath).normalize()
            if (hostPath.startsWith(directory) && Files.isDirectory(hostPath, NOFOLLOW_LINKS)) {
                val empty = Files.list(hostPath).use { !it.findAny().isPresent }
                if (empty) Files.deleteIfExists(hostPath)
            }
        }

        // 4. Snapshot live FAT header & directory clusters BEFORE clearing Layer 0 & dirtySectors
        val liveFat0 = readLayeredFatEntry(liveVol, 0)
        val liveFat1 = readLayeredFatEntry(liveVol, 1)
        val liveDirClusters = HashMap<Int, ByteArray>()
        for (cluster in rootChain) {
            liveDirClusters[cluster] = readLayeredCluster(liveVol, cluster)
        }
        for (entry in newTracked.values) {
            if (entry.isDirectory) {
                for (cluster in entry.clusters) {
                    liveDirClusters[cluster] = readLayeredCluster(liveVol, cluster)
                }
            }
        }

        // 5. Rebuild Layer 0 from exact guest FAT/dir/file clusters and clear dirtySectors
        volume = liveVol
        rawSectors.clear()
        rawSectors.putAll(newRawSectors)
        trackedEntries.clear()
        trackedEntries.putAll(newTracked)

        fatPages.clear()
        dirClusterBytes.clear()
        fileClusterMap.clear()
        setFatEntry(0, liveFat0)
        setFatEntry(1, liveFat1)
        writeFatChain(rootChain)
        for (entry in newTracked.values) {
            writeFatChain(entry.clusters)
            if (!entry.isDirectory) {
                for ((idx, cluster) in entry.clusters.withIndex()) {
                    fileClusterMap[cluster] = FileClusterSlice(
                        relativePath = entry.relativePath,
                        fileByteOffset = idx.toLong() * liveVol.clusterBytes.toLong(),
                        fileSize = entry.sizeBytes,
                    )
                }
            }
        }
        dirClusterBytes.putAll(liveDirClusters)

        persistSidecar()
        dirtySectors.retain(preservedDirty)
        dirtyMetadataPending = false
        return true
    }

    private fun clusterHasDirtySector(vol: VolumeGeometry, cluster: Int): Boolean {
        val startLba = vol.clusterToStartLba(cluster)
        val endLba = startLba + vol.sectorsPerCluster
        val ceiling = dirtySectors.ceilingKey(startLba)
        return ceiling != null && ceiling < endLba
    }

    private fun writeFileFromLayeredClusters(vol: VolumeGeometry, entry: ParsedFatEntry, hostPath: Path) {
        val tmp = hostPath.resolveSibling("." + hostPath.fileName.toString() + ".fuji-tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { out ->
                var remaining = entry.sizeBytes
                val buf = ByteBuffer.allocate(if (remaining > 0L) vol.clusterBytes else 0)
                for (cluster in entry.clusters) {
                    if (remaining <= 0L) break
                    readLayeredCluster(vol, cluster, buf.array())
                    val toWrite = minOf(remaining, vol.clusterBytes.toLong()).toInt()
                    buf.clear().limit(toWrite)
                    while (buf.hasRemaining()) {
                        out.write(buf)
                    }
                    remaining -= toWrite
                }
            }
            if (entry.mtimeEpochMillis > 0L) {
                runCatching { Files.setLastModifiedTime(tmp, FileTime.fromMillis(entry.mtimeEpochMillis)) }
            }
            // Reading clean sectors for the replacement can reopen the old backing file.
            readChannels.remove(hostPath)?.close()
            try {
                Files.move(tmp, hostPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, hostPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw error
        }
    }

    override fun invalidateDigestCache() {
        writeGeneration++
        cachedSha256 = null
    }

    override fun read(offset: Long, target: ByteBuffer): Int {
        val total = target.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0
        val vol = volume
        val partEndLba = vol.partitionStartLba + vol.partitionSectorCount
        var done = 0
        while (done < total) {
            val pos = offset + done
            val lba = pos / SECTOR_BYTES
            val inSec = (pos % SECTOR_BYTES).toInt()
            val chunk = minOf(total - done, SECTOR_BYTES - inSec)

            val pending = dirtySectors.read(lba, inSec, target)
            if (pending > 0) {
                done += pending
                continue
            }
            if (inSec == 0 && total - done >= SECTOR_BYTES && !target.isReadOnly &&
                lba >= vol.dataStartLba && lba < partEndLba && !rawSectors.containsKey(lba)
            ) {
                val dataOffset = lba - vol.dataStartLba
                val cluster = (dataOffset / vol.sectorsPerCluster).toInt() + 2
                val slice = fileClusterMap[cluster]
                if (slice != null && !dirClusterBytes.containsKey(cluster)) {
                    // A pending/raw sector or a discontinuity in either allocation map ends this host read.
                    val endLba = minOf(partEndLba, lba + (total - done) / SECTOR_BYTES)
                    val cleanEnd = minOf(endLba, dirtySectors.ceilingKey(lba) ?: endLba,
                        rawSectors.ceilingKey(lba) ?: endLba)
                    val wanted = ((cleanEnd - lba) * SECTOR_BYTES).toInt()
                    val inCluster = (dataOffset % vol.sectorsPerCluster).toInt() * SECTOR_BYTES
                    var span = minOf(wanted, vol.clusterBytes - inCluster)
                    var nextCluster = cluster
                    while (span < wanted) {
                        nextCluster++
                        val next = fileClusterMap[nextCluster] ?: break
                        if (next.relativePath != slice.relativePath || next.fileSize != slice.fileSize ||
                            next.fileByteOffset != slice.fileByteOffset + inCluster + span ||
                            dirClusterBytes.containsKey(nextCluster)) break
                        span += minOf(vol.clusterBytes, wanted - span)
                    }
                    readHostFileSlice(slice, inCluster, span, target)
                    done += span
                    continue
                }
            }

            val sec = readBaseSector(lba)
            target.put(sec, inSec, chunk)
            done += chunk
        }
        return done
    }

    private fun noteDirtySector(lba: Long) {
        if (dirtyMetadataPending) return
        val vol = volume
        if (lba < vol.dataStartLba) {
            dirtyMetadataPending = true
            return
        }
        if (lba < vol.partitionStartLba + vol.partitionSectorCount) {
            val cluster = ((lba - vol.dataStartLba) / vol.sectorsPerCluster).toInt() + 2
            if (cluster in dirClusterBytes) {
                dirtyMetadataPending = true
            }
        }
    }

    override fun write(offset: Long, source: ByteBuffer): Int {
        require(!readOnly) { "SD slot write-protected" }
        val total = source.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0
        invalidateDigestCache()
        var done = 0
        while (done < total) {
            val pos = offset + done
            val lba = pos / SECTOR_BYTES
            val inSec = (pos % SECTOR_BYTES).toInt()
            val fullSectors = if (inSec == 0) (total - done) / SECTOR_BYTES else 0
            if (fullSectors > 0) {
                var index = 0
                while (index < fullSectors && !dirtyMetadataPending) {
                    noteDirtySector(lba + index)
                    index++
                }
                dirtySectors.write(lba, fullSectors, source)
                done += fullSectors * SECTOR_BYTES
            } else {
                val chunk = minOf(total - done, SECTOR_BYTES - inSec)
                val sec = readLayeredSector(lba)
                source.get(source.position(), sec, inSec, chunk)
                dirtySectors.write(lba, 1, ByteBuffer.wrap(sec))
                source.position(source.position() + chunk)
                noteDirtySector(lba)
                done += chunk
            }
        }
        return done
    }

    override fun readSector(sectorIndex: Long): ByteArray {
        checkRange(sectorIndex * SECTOR_BYTES, SECTOR_BYTES.toLong())
        return readLayeredSector(sectorIndex)
    }

    override fun writeSector(sectorIndex: Long, sectorBytes: ByteArray) {
        require(!readOnly) { "SD slot write-protected" }
        require(sectorBytes.size == SECTOR_BYTES)
        checkRange(sectorIndex * SECTOR_BYTES, SECTOR_BYTES.toLong())
        invalidateDigestCache()
        dirtySectors.write(sectorIndex, 1, ByteBuffer.wrap(sectorBytes))
        noteDirtySector(sectorIndex)
    }

    override fun flush() {
        if (!readOnly && dirtySectors.size != 0) {
            reconcileGuestWrites(preserveUnlinkedData = false)
        }
    }

    override fun flushIfConsistent() {
        if (!readOnly && dirtyMetadataPending && dirtySectors.size != 0) {
            reconcileGuestWrites(preserveUnlinkedData = true)
        }
    }

    override fun computeLogicalSha256(
        sectorOverrides: Map<Long, ByteArray>,
        baselineSectors: Map<Long, ByteArray>,
    ): String {
        val noOverrides = sectorOverrides.isEmpty() && baselineSectors.isEmpty()
        if (noOverrides && cachedSha256 != null && hashedGeneration == writeGeneration) {
            return cachedSha256!!
        }

        // Build merged sorted list of potentially non-zero LBA ranges [start, endExclusive)
        val rawRanges = ArrayList<Pair<Long, Long>>()
        fun addRange(start: Long, endExclusive: Long) {
            val s = start.coerceIn(0L, totalSectors)
            val e = endExclusive.coerceIn(0L, totalSectors)
            if (s < e) rawRanges.add(s to e)
        }
        for (lba in rawSectors.keys) addRange(lba, lba + 1L)
        dirtySectors.forEachRange(::addRange)
        for (lba in sectorOverrides.keys) addRange(lba, lba + 1L)
        for (lba in baselineSectors.keys) addRange(lba, lba + 1L)

        val vol = volume
        val sectorsPerFatPage = FAT_PAGE_ENTRIES / 128 // 8 sectors per 4 KiB FAT page
        for (pageIdx in fatPages.keys) {
            val baseFatSec = pageIdx * sectorsPerFatPage
            val count = minOf(sectorsPerFatPage, vol.sectorsPerFat - baseFatSec)
            if (count > 0) {
                for (fatCopy in 0 until vol.numFats) {
                    val fatStart = vol.fat1StartLba + fatCopy.toLong() * vol.sectorsPerFat.toLong() + baseFatSec
                    addRange(fatStart, fatStart + count)
                }
            }
        }
        for (cluster in dirClusterBytes.keys) {
            val baseLba = vol.clusterToStartLba(cluster)
            addRange(baseLba, baseLba + vol.sectorsPerCluster)
        }
        for (cluster in fileClusterMap.keys) {
            val baseLba = vol.clusterToStartLba(cluster)
            addRange(baseLba, baseLba + vol.sectorsPerCluster)
        }

        rawRanges.sortWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        val merged = ArrayList<Pair<Long, Long>>()
        for (r in rawRanges) {
            if (merged.isEmpty() || r.first > merged.last().second) {
                merged.add(r)
            } else if (r.second > merged.last().second) {
                merged[merged.lastIndex] = merged.last().first to r.second
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var cursorLba = 0L
        for ((rangeStart, rangeEnd) in merged) {
            if (rangeStart > cursorLba) {
                ZeroDigestSupport.updateZeros(digest, (rangeStart - cursorLba) * SECTOR_BYTES)
            }
            for (lba in rangeStart until rangeEnd) {
                val bytes = sectorOverrides[lba] ?: baselineSectors[lba] ?: readLayeredSector(lba)
                digest.update(bytes, 0, SECTOR_BYTES)
            }
            cursorLba = rangeEnd
        }
        if (cursorLba < totalSectors) {
            ZeroDigestSupport.updateZeros(digest, (totalSectors - cursorLba) * SECTOR_BYTES)
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
        "trackedEntryCount" to trackedEntries.size,
        "dirtySectorCount" to dirtySectors.size,
        "sectorsPerCluster" to volume.sectorsPerCluster,
        "partitionStartLba" to volume.partitionStartLba,
    )

    override fun close() {
        if (closed) return
        try {
            flush()
        } finally {
            try {
                dirtySectors.close()
            } finally {
                closeReadChannels()
                closed = true
            }
        }
    }
}
