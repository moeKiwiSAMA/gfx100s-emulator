package com.github.nyabanban.fuji.emulator.runtime

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
 * User-facing `relaxed` mode persistence helpers invoked BEFORE `ComponentHost.load()`.
 *
 * 1. `syncMutableManifest`: flips existing `flash-image` COW files in `mutable.json` from
 *    `"create"` to `"resume"` with refreshed SHA-256 digests so restarting the release bundle
 *    preserves guest Flash writes without modifying `WritableRegions.kt` or weakening strict
 *    research-mode verification.
 * 2. `ensureBlankSdImages`: lazily allocates missing SD card images as cross-platform sparse
 *    files (`StandardOpenOption.SPARSE`) matching `soc-sd-host`'s declared `capacityBytes`, so
 *    release archives stay tiny and never extract multi-gigabyte blank images on Windows/Linux/macOS.
 *
 * NEVER invoked in `discovery` or `strict` modes.
 */
object RelaxedFlashPersistence {
    private const val WRITABLE_SET_PROFILE = "explicit-disjoint-copy-on-write-program-experimental-v3"
    private const val MAX_SPARSE_SD_BYTES = 64L * 1024L * 1024L * 1024L

    fun prepare(home: Path, config: Map<String, Any>) {
        if (CatalogRuntime.mode(config) != RelaxedPeripheralFallback.MODE) return
        NorFlashTool.ensureLiveNorExists(home, config)
        syncMutableManifest(home, config)
        ensureBlankSdImages(home, config)
        resetButtonsForBoot(home, config)
    }

    private fun resolveComponentsDir(home: Path, config: Map<String, Any>): Path? =
        (config["components"] as? String)?.takeIf { it.isNotBlank() }?.let { home.resolve(it).toAbsolutePath().normalize() }

    private fun resolveComponentConfigDir(home: Path, config: Map<String, Any>): Path? =
        (config["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }?.let { home.resolve(it).toAbsolutePath().normalize() }
            ?: resolveComponentsDir(home, config)

    /**
     * Ensures `relaxed` mode cold launches never start stuck with `power: false` or a held momentary
     * key left behind by a prior shutdown/close in `buttons/buttons.yaml`, while honoring explicit
     * opt-in boot-hold keys from `config["bootHoldButtons"]` or `buttons/boot-hold.yaml` (e.g.
     * `["back"]` to enter `FIRMWARE UPGRADE` on cold boot without manual live patching).
     * NEVER invoked in `discovery` or `strict` modes.
     */
    fun resetButtonsForBoot(home: Path, config: Map<String, Any>): Boolean {
        if (CatalogRuntime.mode(config) != RelaxedPeripheralFallback.MODE) return false
        val cfgRoot = resolveComponentConfigDir(home, config) ?: return false
        val buttonsDir = cfgRoot.resolve("buttons").toAbsolutePath().normalize()
        val buttonsYaml = buttonsDir.resolve("buttons.yaml")
        if (Files.isSymbolicLink(buttonsYaml) || !Files.isRegularFile(buttonsYaml, NOFOLLOW_LINKS)) return false
        val current = Formats.read(buttonsYaml)
        val bootHoldKeys = parseBootHoldKeys(config, buttonsDir)
        val updated = LinkedHashMap<String, Any>(current)
        var changed = false
        for ((key, value) in current) {
            if (key == "power" || key == "movieMode" || key == "focusMode") continue
            if (key in bootHoldKeys) {
                if (value != true) {
                    updated[key] = true
                    changed = true
                }
            } else if (value == true) {
                updated[key] = false
                changed = true
            }
        }
        if (current["power"] != true) {
            updated["power"] = true
            changed = true
        }
        if (changed) {
            Formats.atomicYaml(buttonsYaml, updated)
        }
        return changed
    }

    private fun parseBootHoldKeys(config: Map<String, Any>, buttonsDir: Path): Set<String> {
        val result = LinkedHashSet<String>()
        collectButtonNames(config["bootHoldButtons"], result)
        val sidecar = buttonsDir.resolve("boot-hold.yaml")
        if (!Files.isSymbolicLink(sidecar) && Files.isRegularFile(sidecar, NOFOLLOW_LINKS)) {
            val doc = Formats.read(sidecar)
            collectButtonNames(doc["holdButtons"], result)
        }
        return result
    }

    private fun collectButtonNames(raw: Any?, out: MutableSet<String>) {
        when (raw) {
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(out::add)
            is Iterable<*> -> raw.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.forEach(out::add)
        }
    }

    fun syncMutableManifest(home: Path, config: Map<String, Any>): Int {
        if (CatalogRuntime.mode(config) != RelaxedPeripheralFallback.MODE) return 0
        val runtimeRoot = resolveComponentsDir(home, config) ?: return 0
        val cfgRoot = resolveComponentConfigDir(home, config) ?: runtimeRoot
        val flashCfgDir = cfgRoot.resolve("flash-image").toAbsolutePath().normalize()
        val flashRuntimeDir = runtimeRoot.resolve("flash-image").toAbsolutePath().normalize()
        val flashYaml = flashCfgDir.resolve("flash-image.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: flashRuntimeDir.resolve("flash-image.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return 0

        val flashCfg = Formats.read(flashYaml)
        if (flashCfg["mutationProfile"] != WRITABLE_SET_PROFILE) return 0
        val regionsRel = flashCfg["regionsFile"] as? String ?: return 0
        val mutableRel = flashCfg["mutationFile"] as? String ?: return 0
        val regionsPath = safeRelativeChild(flashCfgDir, regionsRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: safeRelativeChild(flashRuntimeDir, regionsRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return 0
        val mutablePath = safeRelativeChild(flashCfgDir, mutableRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: safeRelativeChild(flashRuntimeDir, mutableRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return 0

        val regionsDoc = Formats.readData(regionsPath)
        val seedLengths = HashMap<String, Long>()
        for (raw in (regionsDoc["regions"] as? List<*>).orEmpty()) {
            val row = raw as? Map<*, *> ?: continue
            val id = row["id"] as? String ?: continue
            val length = (row["length"] as? Number)?.toLong() ?: continue
            seedLengths[id] = length
        }

        val mutableDoc = Formats.readData(mutablePath)
        if (mutableDoc["profile"] != WRITABLE_SET_PROFILE) return 0
        val rawEntries = mutableDoc["regions"] as? List<*> ?: return 0

        var updatedCount = 0
        val updatedEntries = ArrayList<Any?>(rawEntries.size)
        for (raw in rawEntries) {
            val row = raw as? Map<*, *>
            if (row == null) {
                updatedEntries.add(raw)
                continue
            }
            val regionId = row["regionId"] as? String
            val fileName = row["file"] as? String
            val expectedLength = regionId?.let { seedLengths[it] }
            val cowPath = fileName?.let { name ->
                safeRelativeChild(flashRuntimeDir, name)?.takeIf { !Files.isSymbolicLink(it) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    ?: safeRelativeChild(flashCfgDir, name)?.takeIf { !Files.isSymbolicLink(it) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
            }
            if (cowPath != null &&
                expectedLength != null &&
                Files.size(cowPath) == expectedLength
            ) {
                val actualSha = sha256File(cowPath)
                if (row["mode"] != "resume" || row["sha256"] != actualSha) {
                    val copy = LinkedHashMap<String, Any?>()
                    for ((k, v) in row) copy[k.toString()] = v
                    copy["mode"] = "resume"
                    copy["sha256"] = actualSha
                    updatedEntries.add(copy)
                    updatedCount++
                    continue
                }
            }
            updatedEntries.add(row)
        }

        if (updatedCount > 0) {
            val nextDoc = LinkedHashMap<String, Any?>(mutableDoc)
            nextDoc["regions"] = updatedEntries
            Formats.writeJson(mutablePath, nextDoc)
        }
        return updatedCount
    }

    fun ensureBlankSdImages(home: Path, config: Map<String, Any>): Int {
        if (CatalogRuntime.mode(config) != RelaxedPeripheralFallback.MODE) return 0
        val runtimeRoot = resolveComponentsDir(home, config) ?: return 0
        val cfgRoot = resolveComponentConfigDir(home, config) ?: runtimeRoot
        val readerCfgDir = cfgRoot.resolve("sdcard-reader")
        val readerRuntimeDir = runtimeRoot.resolve("sdcard-reader")
        val readerYaml = readerCfgDir.resolve("sdcard-reader.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: readerRuntimeDir.resolve("sdcard-reader.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return 0
        val readerCfg = Formats.read(readerYaml)

        val capacities = readSdHostCardCapacities(cfgRoot.resolve("soc-sd-host"), runtimeRoot.resolve("soc-sd-host"))
        var created = 0
        for (slot in 1..2) {
            if (readerCfg["slot${slot}Inserted"] != true) continue
            val imageRel = readerCfg["slot${slot}Image"] as? String ?: continue
            val imagePath = safeRelativeChild(readerRuntimeDir, imageRel) ?: continue
            val backend = (readerCfg["slot${slot}Backend"] as? String) ?: "raw"
            if (backend == "directory") {
                val cfgImage = safeRelativeChild(readerCfgDir, imageRel)
                val targetDir = when {
                    Files.isDirectory(imagePath, NOFOLLOW_LINKS) -> imagePath
                    cfgImage != null && Files.isDirectory(cfgImage, NOFOLLOW_LINKS) -> cfgImage
                    Files.exists(imagePath, NOFOLLOW_LINKS) -> continue
                    cfgImage != null && Files.exists(cfgImage, NOFOLLOW_LINKS) -> continue
                    else -> imagePath
                }
                if (ensureFujiCardDirectoryInitialized(targetDir)) {
                    created++
                }
                continue
            }
            if (Files.exists(imagePath, NOFOLLOW_LINKS)) continue
            val cfgImage = safeRelativeChild(readerCfgDir, imageRel)
            if (cfgImage != null && Files.exists(cfgImage, NOFOLLOW_LINKS)) continue
            val explicitCap = (readerCfg["slot${slot}CapacityBytes"] as? Number)?.toLong() ?: 0L
            val capacity = if (explicitCap > 0L) explicitCap else capacities[slot - 1] ?: continue
            if (capacity <= 0L || capacity % 512L != 0L || capacity > MAX_SPARSE_SD_BYTES) continue
            when (backend) {
                "raw" -> {
                    createSparseBlankFile(imagePath, capacity)
                    created++
                }
                "qcow2" -> {
                    val backing = (readerCfg["slot${slot}BackingFile"] as? String)?.takeIf { it.isNotBlank() }
                    createEmptyQcow2V3File(imagePath, capacity, backing)
                    created++
                }
            }
        }
        return created
    }

    private fun ensureFujiCardDirectoryInitialized(dir: Path): Boolean {
        val existed = Files.isDirectory(dir, NOFOLLOW_LINKS)
        if (!existed) {
            Files.createDirectories(dir)
        } else {
            val isEmpty = Files.list(dir).use { !it.findAny().isPresent }
            if (!isEmpty) return false
        }
        val ffdbDir = dir.resolve("FFDB")
        val dcimDir = dir.resolve("DCIM").resolve("100_FUJI")
        Files.createDirectories(ffdbDir)
        Files.createDirectories(dcimDir)
        val xfer = ffdbDir.resolve("FFXFER.DAT")
        if (!Files.exists(xfer, NOFOLLOW_LINKS)) {
            // Authentic GFX100S 2.13 FFDB/FFXFER.DAT v2 header (512 B >= 48 B checked at 0x025821fc: "00000002\0\0" + zero indices)
            val payload = ByteArray(512)
            "00000002".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
            Files.write(xfer, payload)
        }
        return true
    }

    private fun readSdHostCardCapacities(sdHostCfgDir: Path, sdHostRuntimeDir: Path): Map<Int, Long> {
        val sdHostYaml = sdHostCfgDir.resolve("soc-sd-host.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: sdHostRuntimeDir.resolve("soc-sd-host.yaml").takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return emptyMap()
        val sdHostCfg = Formats.read(sdHostYaml)
        val inputRel = sdHostCfg["inputFile"] as? String ?: return emptyMap()
        if (inputRel.isBlank()) return emptyMap()
        val inputPath = safeRelativeChild(sdHostCfgDir, inputRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: safeRelativeChild(sdHostRuntimeDir, inputRel)?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            ?: return emptyMap()
        val inputDoc = Formats.readData(inputPath)
        val cards = inputDoc["cards"] as? Map<*, *> ?: return emptyMap()
        val result = HashMap<Int, Long>()
        for ((k, v) in cards) {
            val idx = k?.toString()?.toIntOrNull() ?: continue
            val card = v as? Map<*, *> ?: continue
            val cap = (card["capacityBytes"] as? Number)?.toLong() ?: continue
            result[idx] = cap
        }
        return result
    }

    private fun createSparseBlankFile(path: Path, sizeBytes: Long) {
        Files.createDirectories(path.parent)
        val options = arrayOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE,
        )
        FileChannel.open(path, *options).use { channel ->
            val zero = ByteBuffer.allocate(1)
            val written = channel.write(zero, sizeBytes - 1L)
            check(written == 1) { "Failed to initialize sparse SD image $path" }
        }
    }

    private fun createEmptyQcow2V3File(path: Path, sizeBytes: Long, backingFileRelative: String? = null) {
        val clusterBits = 16 // 64 KiB clusters
        val clusterSize = 1 shl clusterBits
        val l2Coverage = (clusterSize / 8).toLong() * clusterSize.toLong()
        val l1Size = ((sizeBytes + l2Coverage - 1L) / l2Coverage).toInt().coerceAtLeast(1)
        val l1Clusters = ((l1Size.toLong() * 8L + clusterSize - 1L) / clusterSize).toInt().coerceAtLeast(1)
        val refTableOffset = 1L * clusterSize
        val refBlock0Offset = 2L * clusterSize
        val l1TableOffset = 3L * clusterSize
        val totalInitialClusters = 3 + l1Clusters

        val cluster0 = ByteArray(clusterSize)
        val hdr = ByteBuffer.wrap(cluster0).order(ByteOrder.BIG_ENDIAN)
        val headerLen = 104
        var backingOffset = 0L
        var backingSize = 0
        if (!backingFileRelative.isNullOrEmpty()) {
            val backingBytes = backingFileRelative.toByteArray(StandardCharsets.UTF_8)
            val offset = headerLen + 8 // after 8-byte end-of-extensions marker
            require(offset + backingBytes.size <= clusterSize) { "QCOW2 backing file name too long" }
            backingOffset = offset.toLong()
            backingSize = backingBytes.size
            System.arraycopy(backingBytes, 0, cluster0, offset, backingBytes.size)
        }
        hdr.putInt(0x00, 0x514649FB) // QFI\xfb
        hdr.putInt(0x04, 3) // version 3
        hdr.putLong(0x08, backingOffset)
        hdr.putInt(0x10, backingSize)
        hdr.putInt(0x14, clusterBits)
        hdr.putLong(0x18, sizeBytes)
        hdr.putInt(0x20, 0) // crypt_method = none
        hdr.putInt(0x24, l1Size)
        hdr.putLong(0x28, l1TableOffset)
        hdr.putLong(0x30, refTableOffset)
        hdr.putInt(0x38, 1) // refcount_table_clusters
        hdr.putInt(0x3C, 0) // nb_snapshots
        hdr.putLong(0x40, 0L)
        hdr.putLong(0x48, 0L)
        hdr.putLong(0x50, 0L)
        hdr.putLong(0x58, 0L)
        hdr.putInt(0x60, 4) // refcount_order = 16-bit
        hdr.putInt(0x64, headerLen)

        val refTable = ByteArray(clusterSize)
        ByteBuffer.wrap(refTable).order(ByteOrder.BIG_ENDIAN).putLong(0, refBlock0Offset)
        val refBlock0 = ByteArray(clusterSize)
        val rb = ByteBuffer.wrap(refBlock0).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until totalInitialClusters) {
            rb.putShort(i * 2, 1.toShort())
        }
        val l1Bytes = ByteArray(l1Clusters * clusterSize)

        Files.createDirectories(path.parent)
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { ch ->
            fun writeAll(offset: Long, bytes: ByteArray) {
                val buf = ByteBuffer.wrap(bytes)
                var done = 0
                while (buf.hasRemaining()) {
                    val n = ch.write(buf, offset + done)
                    check(n > 0) { "short QCOW2 write" }
                    done += n
                }
            }
            writeAll(0L, cluster0)
            writeAll(refTableOffset, refTable)
            writeAll(refBlock0Offset, refBlock0)
            writeAll(l1TableOffset, l1Bytes)
        }
    }

    private fun safeRelativeChild(parent: Path, relative: String): Path? {
        if (relative.isBlank()) return null
        val rel = Path.of(relative)
        if (rel.isAbsolute || rel != rel.normalize() || rel.any { it.toString() in setOf(".", "..") }) return null
        val resolved = parent.resolve(rel).normalize()
        if (!resolved.startsWith(parent) || resolved == parent) return null
        var cur: Path? = resolved
        while (cur != null && cur != parent) {
            if (Files.isSymbolicLink(cur)) return null
            cur = cur.parent
        }
        return resolved
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
