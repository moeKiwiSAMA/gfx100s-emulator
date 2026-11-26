package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Immutable DAT prefix, separately selected finite tail, and explicitly supplied frozen file regions. */
class FlashImageFactory : ComponentFactory {
    companion object {
        private val LOG = Logger.getLogger(FlashImageFactory::class.java.name)
        private val HEX = HexFormat.of()
        private const val HASH_BUFFER_BYTES = 256 * 1024
        private const val DIGEST_CACHE_FILE = "dat-digest-cache.json"
        private const val MAX_MEMORY_DIGEST_CACHE = 16
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val CACHE_ENTRY_REGEX = Regex("^\"([A-Za-z0-9_]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))$")

        private data class DigestCacheKey(
            val sourceSha256: String,
            val sourceSize: Long,
            val extent: Long,
        )

        private val memoryDigestCache = ConcurrentHashMap<DigestCacheKey, String>()

        internal fun clearMemoryDigestCacheForTesting() {
            memoryDigestCache.clear()
        }

        internal fun invertBytesInPlace(buffer: ByteArray, offset: Int, length: Int) {
            var pos = offset
            val end = offset + length
            val longEnd = offset + (length and 7.inv())
            if (longEnd > pos) {
                val bb = ByteBuffer.wrap(buffer)
                while (pos < longEnd) {
                    bb.putLong(pos, bb.getLong(pos).inv())
                    pos += 8
                }
            }
            while (pos < end) {
                buffer[pos] = buffer[pos].toInt().inv().toByte()
                pos++
            }
        }

        private val DESCRIPTOR = ComponentDescriptor("flash-image", "0.0.1", settings = listOf(
            Setting("sourcePort", ValueKind.TEXT, "firmware.update", live = false),
            Setting("imageProfile", ValueKind.TEXT, "dat-update-port", choices = listOf("dat-update-port", UnifiedNorImage.PROFILE), live = false,
                description = "Select dat-update-port (legacy DAT UpdateDataPort) or raw-nor-image-v1 (unified 64 MiB QSPI NOR Flash image)"),
            Setting("imageFile", ValueKind.TEXT, "", live = false,
                description = "Unified 64 MiB NOR Flash filename for raw-nor-image-v1 (overridden by host.nor-flash port when configured in emulator.yaml)"),
            Setting("writable", ValueKind.BOOLEAN, false, live = false,
                description = "Allow direct sector erase and 256-byte page programming across the unified 64 MiB NOR Flash image"),
            Setting("tailProfile", ValueKind.TEXT, "strict", choices = listOf("strict", "dat-end-block-zero-experimental-v1", "dat-end-block-ff-experimental-v1"),
                live = false, description = "Explicit unmeasured final-block input; never original DAT or a physical erased-state claim"),
            Setting("regionsProfile", ValueKind.TEXT, "strict", choices = listOf("strict", SupplementalRegions.PROFILE), live = false,
                description = "Explicit external immutable files; no default bytes or physical content claim"),
            Setting("regionsFile", ValueKind.TEXT, "", live = false),
            Setting("mutationProfile",ValueKind.TEXT,"strict",choices=listOf("strict",WritableRegion.PROFILE,WritableRegion.PROGRAM_PROFILE,WritableRegions.PROFILE),live=false,description="Explicit private writable copy; immutable DAT/seed unchanged"),
            Setting("mutationFile",ValueKind.TEXT,"",live=false)))
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "loaded", "length", "sha256",
            "supplementalRegions", "writableRegion", "writableRegionSet",
        )
        private val SUPPLEMENT_KEYS = setOf("id", "offset", "length", "sha256", "file")
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
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Image(context, config)
    private class Image(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("flash-image", context, config), SupplementalImagePort, LazyDigestImagePort, StatefulComponent {
        private val unifiedMode = config["imageProfile"] == UnifiedNorImage.PROFILE
        private val unifiedNor: UnifiedNorImage? = if (unifiedMode) {
            val configuredFile = (config["imageFile"] as? String).orEmpty()
            val backingPort = context.ports.find(NorFlashBackingPort.PORT_NAME, NorFlashBackingPort::class.java)
            val resolvedPath = backingPort?.imagePath ?: run {
                require(configuredFile.isNotBlank()) { "raw-nor-image-v1 requires imageFile or host.nor-flash port" }
                context.dataPath(configuredFile)
            }
            val label = configuredFile.ifBlank { resolvedPath.fileName.toString() }
            val writableFlag = config["writable"] as? Boolean ?: false
            require(
                config["regionsProfile"] == "strict" &&
                    (config["regionsFile"] as? String).orEmpty().isBlank() &&
                    config["mutationProfile"] == "strict" &&
                    (config["mutationFile"] as? String).orEmpty().isBlank() &&
                    config["tailProfile"] == "strict",
            ) { "raw-nor-image-v1 cannot be combined with legacy supplemental/COW/tail profiles" }
            UnifiedNorImage(resolvedPath, label, writableFlag, relaxedMode = context.relaxedMode)
        } else {
            null
        }
        private val supplements = if (unifiedMode) {
            SupplementalRegions(context, "strict", "")
        } else {
            SupplementalRegions(context, config.getValue("regionsProfile") as String, config.getValue("regionsFile") as String)
        }
        override val supplementalRegions: List<ImageRegionIdentity> get() = supplements.identities
        private val writableSet: WritableRegions? = if (!unifiedMode && config["mutationProfile"] == WritableRegions.PROFILE) {
            val file = config["mutationFile"] as? String ?: error("writable region-set file required")
            require(file.isNotBlank())
            WritableRegions(context, supplements.checkMutation(context.readData(file)), supplements.identities) { at, dst -> supplements.read(at, dst) }
        } else null
        private val writable: WritableRegion? = if (unifiedMode) null else run {
            val profile = config["mutationProfile"] ?: "strict"
            val file = (config["mutationFile"] ?: "") as String
            require(profile in listOf("strict", WritableRegion.PROFILE, WritableRegion.PROGRAM_PROFILE, WritableRegions.PROFILE) && (profile == "strict") == file.isBlank()) { "writable profile/file selection mismatch" }
            if (profile == "strict" || profile == WritableRegions.PROFILE) null else {
                val specification = supplements.checkMutation(context.readData(file))
                require(specification["profile"] == profile) { "writable component/specification profile mismatch" }
                WritableRegion(context, specification, supplements.identities) { at, dst -> supplements.read(at, dst) }
            }
        }
        private var cachedSource: UpdateDataPort? = null
        private var size = 0L
        private var digest = ""
        private var closed = false
        init {
            try {
                context.ports.register(id, "flash.image", ReadOnlyImagePort::class.java, this)
                unifiedNor?.takeIf { it.writable }?.let { nor ->
                    context.ports.register(id, "flash.image.mutable", WritableImagePort::class.java, nor)
                    context.ports.register(id, "flash.image.program", ProgrammableImagePort::class.java, nor)
                }
                writableSet?.let {
                    context.ports.register(id, "flash.image.mutable", WritableImagePort::class.java, it)
                    context.ports.register(id, "flash.image.program", ProgrammableImagePort::class.java, it)
                }
                writable?.let {
                    context.ports.register(id, "flash.image.mutable", WritableImagePort::class.java, it)
                    if (it.programEnabled) context.ports.register(id, "flash.image.program", ProgrammableImagePort::class.java, it)
                }
            } catch (error: Throwable) {
                unifiedNor?.close()
                writableSet?.close()
                writable?.close()
                throw error
            }
        }
        private fun source() = context.ports.find(values.getValue("sourcePort") as String, UpdateDataPort::class.java)
        private fun tail(extent: Long): ExperimentalImageTail? {
            val profile = values.getValue("tailProfile") as String
            return if (profile == "strict") null else ExperimentalImageTail(profile, extent, (-extent) and 4095,
                if (profile == "dat-end-block-ff-experimental-v1") 255 else 0)
        }
        private fun digestCachePath(): Path? {
            if (!context.relaxedMode) return null
            val base = context.directory.toAbsolutePath().normalize()
            val resolved = base.resolve(DIGEST_CACHE_FILE).normalize()
            if (!resolved.startsWith(base) || resolved == base) return null
            return resolved
        }
        private fun readDiskDigestCache(key: DigestCacheKey): String? {
            val path = digestCachePath() ?: return null
            return try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    LOG.warning("[FALLBACK][flash-image-cache] Ignoring non-regular/symlink cache file $path; recomputing SHA-256")
                    return null
                }
                val raw = Files.readString(path, StandardCharsets.UTF_8).trim()
                if (!raw.startsWith("{") || !raw.endsWith("}")) {
                    LOG.warning("[FALLBACK][flash-image-cache] Malformed digest cache envelope at $path; recomputing SHA-256")
                    return null
                }
                val body = raw.substring(1, raw.length - 1).trim()
                if (body.isEmpty()) return null
                val strings = HashMap<String, String>()
                val longs = HashMap<String, Long>()
                for (part in body.split(',')) {
                    val m = CACHE_ENTRY_REGEX.matchEntire(part.trim()) ?: run {
                        LOG.warning("[FALLBACK][flash-image-cache] Invalid cache entry in $path; recomputing SHA-256")
                        return null
                    }
                    val k = m.groupValues[1]
                    if (m.groups[2] != null) strings[k] = m.groupValues[2]
                    else longs[k] = m.groupValues[3].toLong()
                }
                val cachedSourceSha = strings["sourceSha256"]
                val cachedInvertedSha = strings["invertedSha256"]
                val cachedSourceSize = longs["sourceSize"]
                val cachedExtent = longs["extent"]
                if (cachedSourceSha == key.sourceSha256 &&
                    cachedSourceSize == key.sourceSize &&
                    cachedExtent == key.extent &&
                    cachedInvertedSha != null &&
                    SHA256_REGEX.matches(cachedInvertedSha)
                ) {
                    cachedInvertedSha
                } else {
                    null
                }
            } catch (error: Throwable) {
                LOG.warning("[FALLBACK][flash-image-cache] Failed to read digest cache at $path (${error.message}); recomputing SHA-256")
                null
            }
        }
        private fun writeDiskDigestCache(key: DigestCacheKey, invertedSha: String) {
            val path = digestCachePath() ?: return
            try {
                val parent = path.parent
                if (parent != null) Files.createDirectories(parent)
                val payload = """{"sourceSha256": "${key.sourceSha256}", "sourceSize": ${key.sourceSize}, "extent": ${key.extent}, "invertedSha256": "$invertedSha"}""" + "\n"
                val tmp = Files.createTempFile(parent, ".dat-digest-cache-", ".tmp")
                try {
                    Files.writeString(tmp, payload, StandardCharsets.UTF_8)
                    try {
                        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (error: Throwable) {
                    runCatching { Files.deleteIfExists(tmp) }
                    throw error
                }
            } catch (error: Throwable) {
                LOG.warning("[FALLBACK][flash-image-cache] Failed to write digest cache at $path (${error.message}); continuing without persisted cache")
            }
        }
        private fun ensureMetadata(): UpdateDataPort {
            val source = source() ?: error("verified DAT image source missing")
            if (source === cachedSource) return source
            val header = ByteBuffer.allocate(0x274).order(ByteOrder.LITTLE_ENDIAN)
            source.read(0, header); check(!header.hasRemaining()) { "short update header" }
            val extent = header.getInt(0x24c).toLong() and 0xffffffffL
            require(header.getInt(0) == 6 && extent in 1..0x08000000 && extent <= source.size - 0x274) { "invalid type-6 image extent" }
            supplements.validatePrefix(extent + (tail(extent)?.size ?: 0))
            size = extent
            digest = ""
            cachedSource = source
            return source
        }
        private fun ensureDigest(): String {
            val source = ensureMetadata()
            if (digest.isNotEmpty()) return digest
            val key = DigestCacheKey(source.sha256, source.size, size)
            memoryDigestCache[key]?.let { cached ->
                digest = cached
                return cached
            }
            readDiskDigestCache(key)?.let { cached ->
                if (memoryDigestCache.size >= MAX_MEMORY_DIGEST_CACHE) memoryDigestCache.clear()
                memoryDigestCache[key] = cached
                digest = cached
                return cached
            }
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(minOf(HASH_BUFFER_BYTES.toLong(), size).toInt())
            var offset = 0L
            while (offset < size) {
                val n = minOf(buffer.size.toLong(), size - offset).toInt()
                val view = ByteBuffer.wrap(buffer, 0, n)
                source.read(0x274 + offset, view); check(!view.hasRemaining()) { "short update image" }
                invertBytesInPlace(buffer, 0, n)
                hash.update(buffer, 0, n)
                offset += n
            }
            val computed = HEX.formatHex(hash.digest())
            if (memoryDigestCache.size >= MAX_MEMORY_DIGEST_CACHE) memoryDigestCache.clear()
            memoryDigestCache[key] = computed
            writeDiskDigestCache(key, computed)
            digest = computed
            return computed
        }
        override val length: Long
            get() = unifiedNor?.length ?: run { ensureMetadata(); size }
        override val sha256: String
            get() = unifiedNor?.sha256 ?: ensureDigest()
        override val isSha256Dirty: Boolean
            get() = unifiedNor?.isSha256Dirty ?: false
        override fun cachedSha256OrNull(): String? =
            unifiedNor?.cachedSha256OrNull() ?: digest.takeIf { it.isNotEmpty() }
        override val experimentalTail: ExperimentalImageTail?
            get() = if (unifiedNor != null) null else run { ensureMetadata(); tail(size) }
        override fun check(offset: Long, size: Long) {
            unifiedNor?.let { it.check(offset, size); return }
            ensureMetadata()
            val end = this.size + (tail(this.size)?.size ?: 0)
            if (supplementalRegions.isEmpty()) {
                require(offset >= 0 && size > 0 && offset <= end && size <= end - offset) { "read outside DAT-provided flash prefix or selected finite tail" }
            } else supplements.check(offset, size, end)
        }
        override fun read(offset: Long, destination: ByteBuffer) {
            unifiedNor?.let { it.read(offset, destination); return }
            writableSet?.checkLive();writable?.checkLive()
            val count = destination.remaining()
            require(!destination.isReadOnly) { "writable read destination required" }
            check(offset, count.toLong()) // Entire range before any destination position/byte changes.
            val known = minOf(count.toLong(), maxOf(0, size - offset)).toInt()
            val start = destination.position()
            if (known > 0) {
                val view = destination.slice(); view.limit(known); ensureMetadata().read(0x274 + offset, view); check(!view.hasRemaining())
                if (destination.hasArray()) {
                    invertBytesInPlace(destination.array(), destination.arrayOffset() + start, known)
                } else {
                    repeat(known) { destination.put(start + it, destination.get(start + it).toInt().inv().toByte()) }
                }
                destination.position(start + known)
            }
            var cursor = offset + known
            val selectedTail = tail(size)
            if (destination.hasRemaining() && selectedTail != null && cursor >= size && cursor < size + selectedTail.size) {
                val n = minOf(destination.remaining().toLong(), size + selectedTail.size - cursor).toInt()
                repeat(n) { destination.put(selectedTail.fill.toByte()) }
                cursor += n
            }
            if (destination.hasRemaining()) supplements.read(cursor, destination)
            writableSet?.overlay(offset,destination,start,count);writable?.overlay(offset,destination,start,count)
        }
        private fun supplied() = supplementalRegions.map { mapOf("id" to it.id, "offset" to it.offset, "length" to it.length, "file" to it.relativeFile, "sha256" to it.sha256,
            "basis" to "explicit external input, not identified original GFX100S hardware contents", "snapshot" to "immutable-startup-copy") }
        override fun close(){closed = true; unifiedNor?.close(); writableSet?.close();writable?.close()}
        private fun baseState():Map<String,Any?> {
            val raw = super.state().filterKeys { k ->
                unifiedMode || (k != "imageProfile" && k != "imageFile" && k != "writable")
            }
            if (unifiedNor != null) {
                return raw + mapOf(
                    "unifiedNor" to mapOf(
                        "profile" to UnifiedNorImage.PROFILE,
                        "path" to unifiedNor.path.toString(),
                        "relativeFile" to unifiedNor.relativeFile,
                        "length" to unifiedNor.length,
                        "writable" to unifiedNor.writable,
                        "snapshot" to unifiedNor.snapshot(),
                    ),
                    "eraseHistory" to unifiedNor.eraseHistory(),
                    "programHistory" to unifiedNor.programHistory(),
                    "programLaw" to WritableRegion.PROGRAM_LAW,
                    "programPageBytes" to 256,
                )
            }
            return if(writableSet!=null)raw+mapOf("writableOverlay" to writableSet.snapshot(),"eraseHistory" to writableSet.primaryHistory(),"programHistory" to writableSet.primaryProgramHistory(),"programLaw" to WritableRegion.PROGRAM_LAW,"programPageBytes" to 256,"writableRegionSet" to writableSet.state())
            else if(writable==null)raw.filterKeys{it!="mutationProfile"&&it!="mutationFile"}
            else raw+mapOf("writableOverlay" to writable.snapshot(),"eraseHistory" to writable.history())+if(writable.programEnabled)mapOf("programHistory" to writable.programHistory(),"programLaw" to WritableRegion.PROGRAM_LAW,"programPageBytes" to 256)else emptyMap()
        }
        override fun state(): Map<String, Any?> {
            unifiedNor?.let { nor ->
                val stateSha = if (context.relaxedMode) nor.cachedSha256OrNull() else nor.sha256
                return baseState() + mapOf(
                    "mode" to UnifiedNorImage.PROFILE,
                    "loaded" to true,
                    "length" to nor.length,
                    "sha256" to stateSha,
                    "sha256Dirty" to nor.isSha256Dirty,
                    "unknownTail" to "none",
                    "writable" to nor.writable,
                    "norFile" to nor.path.toString(),
                    "eraseCount" to nor.eraseCount,
                    "programCount" to nor.programCount,
                    "generation" to nor.snapshot().generation,
                    "supplementalRegions" to emptyList<Any>(),
                )
            }
            if (source() == null) return baseState() + mapOf("loaded" to false, "unknownTail" to "refuse", "writable" to (writable!=null||writableSet!=null), "supplementalRegions" to supplied())
            val resolvedSha = ensureDigest()
            return baseState() + mapOf("loaded" to true, "length" to size, "sha256" to resolvedSha, "unknownTail" to "refuse", "writable" to (writable!=null||writableSet!=null),
                "experimentalTail" to tail(size)?.let { mapOf("profile" to it.profile, "offset" to it.offset, "size" to it.size, "fill" to it.fill, "basis" to "HYPOTHESIS: finite unmeasured block remainder") },
                "supplementalRegions" to supplied())
        }

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "flash-image closed" }
            unifiedNor?.let { nor ->
                nor.checkLive()
                return linkedMapOf(
                    "schema" to snapshotSchemaVersion,
                    "componentId" to id,
                    "scalars" to saveScalarValues(),
                    "loaded" to true,
                    "length" to nor.length,
                    "sha256" to nor.sha256,
                    "supplementalRegions" to emptyList<Any>(),
                    "writableRegion" to null,
                    "writableRegionSet" to null,
                )
            }
            writableSet?.checkLive()
            writable?.checkLive()
            val hasSource = source() != null
            val resolvedSha = if (hasSource) ensureDigest() else null
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "loaded" to hasSource,
                "length" to (if (hasSource) size else null),
                "sha256" to resolvedSha,
                "supplementalRegions" to supplementalRegions.map { region ->
                    linkedMapOf<String, Any>(
                        "id" to region.id,
                        "offset" to region.offset,
                        "length" to region.length,
                        "sha256" to region.sha256,
                        "file" to region.relativeFile,
                    )
                },
                "writableRegion" to writable?.saveSnapshot(),
                "writableRegionSet" to writableSet?.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "flash-image closed" }
            unifiedNor?.let { nor ->
                nor.checkLive()
                require(data.keys == SNAPSHOT_KEYS) { "Unexpected flash-image snapshot keys" }
                require(requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported flash-image snapshot schema" }
                require(data["componentId"] == id) { "flash-image snapshot componentId mismatch" }
                val rawScalars = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing flash-image scalars map")
                require(rawScalars.keys == values.keys) { "flash-image scalar setting keys differ" }
                val previousValues = values
                val validatedValues = try {
                    restoreScalarValues(rawScalars, DESCRIPTOR)
                    values
                } finally {
                    values = previousValues
                }
                require(data["loaded"] == true) { "Unified NOR snapshot requires loaded=true" }
                require(requireLong(data["length"], "length") == nor.length) { "Unified NOR snapshot length differs" }
                val expectedSha = data["sha256"] as? String ?: throw IllegalArgumentException("Missing flash-image sha256")
                require(expectedSha == nor.sha256) { "Unified NOR image SHA-256 differs from snapshot" }
                values = validatedValues
                return
            }
            writableSet?.checkLive()
            writable?.checkLive()
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected flash-image snapshot keys" }
            require(requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported flash-image snapshot schema" }
            require(data["componentId"] == id) { "flash-image snapshot componentId mismatch" }
            val rawScalars = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing flash-image scalars map")
            require(rawScalars.keys == values.keys) { "flash-image scalar setting keys differ" }
            val previousValues = values
            val validatedValues = try {
                restoreScalarValues(rawScalars, DESCRIPTOR)
                values
            } finally {
                values = previousValues
            }

            val expectedLoaded = data["loaded"] as? Boolean
                ?: throw IllegalArgumentException("Expected boolean for flash-image loaded")
            if (expectedLoaded) {
                require(source() != null) { "Snapshot expects loaded DAT source, but sourcePort is missing" }
                val resolvedSha = ensureDigest()
                val expectedLength = requireLong(data["length"], "length")
                val expectedSha = data["sha256"] as? String ?: throw IllegalArgumentException("Missing flash-image sha256")
                require(expectedLength == size && expectedSha == resolvedSha) { "DAT image length or SHA-256 differs from snapshot" }
            } else {
                require(source() == null) { "Snapshot expects unloaded flash-image, but sourcePort is bound" }
                require(data["length"] == null && data["sha256"] == null) { "Unloaded flash-image snapshot cannot carry length or sha256" }
            }

            val rawSupplements = data["supplementalRegions"] as? List<*>
                ?: throw IllegalArgumentException("Missing flash-image supplementalRegions list")
            val expectedSupplements = supplements.snapshotIdentities(rawSupplements.size)
            require(rawSupplements.size == expectedSupplements.size) { "Supplemental region count differs from snapshot" }
            expectedSupplements.forEachIndexed { regionIndex, region ->
                val row = rawSupplements[regionIndex] as? Map<*, *>
                    ?: throw IllegalArgumentException("Invalid supplemental region entry at $regionIndex")
                require(row.keys == SUPPLEMENT_KEYS) { "Unexpected supplemental region keys at $regionIndex" }
                require(
                    row["id"] == region.id &&
                        requireLong(row["offset"], "supplementalRegions[$regionIndex].offset") == region.offset &&
                        requireLong(row["length"], "supplementalRegions[$regionIndex].length") == region.length &&
                        row["sha256"] == region.sha256 &&
                        row["file"] == region.relativeFile
                ) { "Supplemental region identity differs at $regionIndex" }
            }

            val preparedSingle = if (writable != null) {
                require(data["writableRegionSet"] == null) { "Unexpected writableRegionSet in single-region flash-image" }
                writable.preflightRestore(data["writableRegion"])
            } else {
                require(data["writableRegion"] == null) { "Unexpected writableRegion in flash-image snapshot" }
                null
            }
            val preparedSet = if (writableSet != null) {
                require(data["writableRegion"] == null) { "Unexpected writableRegion in region-set flash-image" }
                writableSet.preflightRestore(data["writableRegionSet"])
            } else {
                require(data["writableRegionSet"] == null) { "Unexpected writableRegionSet in flash-image snapshot" }
                null
            }

            if (writable != null && preparedSingle != null) {
                val stagedPath = writable.stageRestore(preparedSingle)
                writable.commitRestore(preparedSingle, stagedPath)
            }
            if (writableSet != null && preparedSet != null) {
                writableSet.commitRestore(preparedSet)
            }
            values = validatedValues
            if (!expectedLoaded) {
                cachedSource = null
                size = 0L
                digest = ""
            }
        }
    }
}
