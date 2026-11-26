package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Offline byte evidence only. No extraction, guest execution, mappings or hardware replies. */
object HardwareDumpResearch {
    private const val MAX_ENTRY = 128L * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024
    internal const val TABLE_ADDRESS = 0x17476e8L
    private const val APP_BASE = 0x1021000L
    internal data class Entry(val bytes: ByteArray, val sha256: String)
    internal fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    internal fun safeName(name: String): String {
        require(name.isNotBlank() && name.length <= 512 && name.none { it.code == 92 || it == ':' || it.code < 32 }) { "Unsafe ZIP name" }
        require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe ZIP path" }
        return name
    }
    internal fun readArchive(path: Path): Map<String, Entry> {
        require(Files.size(path) in 1..MAX_ENTRY) { "Archive size outside budget" }
        val result = linkedMapOf<String, Entry>()
        var total = 0L
        ZipFile(path.toFile(), Charsets.UTF_8).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size in 1..128) { "ZIP entry budget" }
            val seen = hashSetOf<String>()
            entries.forEach { item ->
                val name = safeName(if (item.isDirectory) item.name.dropLast(1) else item.name)
                require(seen.add(name)) { "Duplicate ZIP path" }
                if (item.isDirectory) { require(item.size == 0L); return@forEach }
                require(item.size in 0..MAX_ENTRY && item.size <= MAX_TOTAL - total) { "ZIP expansion budget" }
                val bytes = zip.getInputStream(item).use { it.readNBytes(Math.toIntExact(item.size + 1)) }
                require(bytes.size.toLong() == item.size) { "ZIP expanded size mismatch" }
                require(CRC32().apply { update(bytes) }.value == item.crc) { "ZIP CRC mismatch" }
                total += bytes.size
                result[name] = Entry(bytes, sha(bytes))
            }
        }
        return result
    }
    internal fun manifest(entries: Map<String, Entry>): List<Map<String, Any>> {
        val text = entries.getValue("files/SHA256SUMS").bytes.toString(Charsets.UTF_8)
        val names = hashSetOf<String>()
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val match = Regex("([0-9a-f]{64})  (.+)").matchEntire(line) ?: error("Malformed SHA256SUMS row")
            val name = "files/" + safeName(match.groupValues[2])
            require(names.add(name)) { "Duplicate SHA256SUMS path" }
            val actual = entries.getValue(name)
            require(actual.sha256 == match.groupValues[1]) { "SHA256SUMS differs: " + name }
            mapOf("entry" to name, "bytes" to actual.bytes.size, "sha256" to actual.sha256, "matchesProvidedSha" to true)
        }.toList()
        require(names == entries.keys - "files/SHA256SUMS") { "Manifest must cover every payload exactly once" }
        return rows
    }
    internal fun difference(left: ByteArray, right: ByteArray, leftOffset: Int = 0, size: Int = right.size): Map<String, Any?> {
        require(leftOffset >= 0 && size >= 0 && size == right.size && leftOffset <= left.size - size) { "Comparison bounds" }
        var count = 0L
        var first: Int? = null
        var last: Int? = null
        val examples = mutableListOf<Map<String, Int>>()
        val blocks = linkedMapOf<Int, Long>()
        for (i in 0 until size) if (left[leftOffset + i] != right[i]) {
            count++; if (first == null) first = i; last = i
            if (examples.size < 16) examples.add(mapOf("offset" to i, "left" to (left[leftOffset+i].toInt() and 255), "right" to (right[i].toInt() and 255)))
            val block = i / 65536
            blocks[block] = (blocks[block] ?: 0) + 1
        }
        return mapOf("comparedBytes" to size, "equal" to (count == 0L), "differentBytes" to count,
            "firstDifferenceOffset" to first, "lastDifferenceOffset" to last, "firstDifferences" to examples,
            "examplesTruncated" to (count > examples.size), "blockBytes" to 65536,
            "changedBlocks" to blocks.map { (block,n) -> mapOf("offset" to block * 65536, "differentBytes" to n) })
    }
    internal fun table(app: ByteArray): List<Map<String, Long>> {
        val start = Math.toIntExact(TABLE_ADDRESS - APP_BASE)
        require(start >= 0 && start <= app.size - 32 * 12)
        val data = ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until 32).map { bank ->
            val pos = start + bank * 12
            val count = data.getInt(pos).toLong() and 0xffffffffL
            val first = data.getInt(pos+4).toLong() and 0xffffffffL
            val third = data.getInt(pos+8).toLong() and 0xffffffffL
            mapOf("bank" to bank.toLong(), "tableAddress" to TABLE_ADDRESS + bank * 12,
                "offset" to Math.multiplyExact(first, 0x20000L), "size" to Math.multiplyExact(count, 0x20000L),
                "thirdWordUnknown" to third)
        }
    }
    internal fun coverage(rows: List<Map<String, Long>>, length: Long): List<Map<String, Long>> {
        require(length >= 0)
        val gaps = mutableListOf<Map<String, Long>>()
        var cursor = 0L
        rows.filter { it.getValue("size") != 0L }.sortedBy { it.getValue("offset") }.forEach { row ->
            val base = row.getValue("offset"); val size = row.getValue("size")
            require(base >= cursor && size > 0L && base <= length && size <= length - base) { "Partition overlap or out of bounds" }
            if (base > cursor) gaps.add(mapOf("base" to cursor, "endExclusive" to base))
            cursor = base + size
        }
        if (cursor < length) gaps.add(mapOf("base" to cursor, "endExclusive" to length))
        return gaps
    }
    private fun declaredPartitionPath(raw: String): String {
        // This one parent reference is documented by the supplied index; arbitrary traversal is refused.
        return if (raw == "../config-storage-main.bin") "files/config-storage-main.bin"
        else "files/partitions/" + safeName(raw)
    }
    fun analyze(repo: Path, archive: Path): Map<String, Any?> {
        val entries = readArchive(archive)
        val verified = manifest(entries)
        val metadata = FirmwareMetadata(repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        val image = FirmwareImage(metadata, repo.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT"))
        val app = image.decode("section4")
        val secondary = image.decode("section5")
        val index = Formats.json.readTree(entries.getValue("files/partitions/index.json").bytes)
        require(index["firmware_sha256"].asText() == metadata.sha256 && index["table_address"].isIntegralNumber && index["table_address"].asLong() == TABLE_ADDRESS)
        require(index["source_flash"].asText() == "../flash-layout.bin")
        val flash = entries.getValue("files/flash-layout.bin")
        require(index["source_sha256"].asText() == flash.sha256 && flash.bytes.size == 0x4000000)
        val datRows = table(app)
        val declared = index["partitions"].toList()
        val banks = declared.map { row -> require(row["bank"].isIntegralNumber); row["bank"].asInt() }
        require(banks.distinct().size == banks.size && banks.toSet() == datRows.filter { it.getValue("size") > 0 }.map { it.getValue("bank").toInt() }.toSet())
        val partitions = declared.map { row ->
            val bank = row["bank"].asInt(); val dat = datRows[bank]
            for ((input, output) in listOf("offset" to "offset", "size" to "size", "class_number" to "thirdWordUnknown")) {
                require(row[input].isIntegralNumber && row[input].canConvertToLong() && row[input].asLong() == dat.getValue(output)) { "Index differs from DAT table: " + bank }
            }
            val name = declaredPartitionPath(row["file"].asText())
            val slice = entries.getValue(name)
            require(slice.bytes.size.toLong() == dat.getValue("size") && slice.sha256 == row["sha256"].asText())
            val diff = difference(flash.bytes, slice.bytes, Math.toIntExact(dat.getValue("offset")))
            mapOf("bank" to bank, "entry" to name, "offset" to dat.getValue("offset"), "size" to slice.bytes.size,
                "thirdWordUnknown" to dat.getValue("thirdWordUnknown"), "indexMatchesDat" to true,
                "relation" to if (bank == 14) "Independent config capture claimed by supplied note; runs absent" else "Offline slice of flash, not independent hardware read",
                "flashComparison" to diff)
        }
        val catalog = Formats.json.readTree(Files.readAllBytes(repo.resolve("catalog/firmware/gfx100s/2.13/firmware.json")))
        val rom = entries.getValue("files/boot-rom.bin")
        val romMatches = rom.sha256 == catalog["boot_rom"]["content"]["sha256"].asText() && rom.bytes.size == catalog["boot_rom"]["content"]["size"].asInt()
        require(romMatches) { "ROM differs from selected catalog identity" }
        val runtimeComparisons = listOf("runtime-primary.bin" to app, "runtime-secondary.bin" to secondary).map { (name, decoded) ->
            val bytes = entries.getValue("files/" + name).bytes
            require(bytes.size == decoded.size)
            mapOf("entry" to "files/"+name, "comparison" to difference(decoded, bytes),
                "left" to if (name.contains("primary")) "DAT decoded section4" else "DAT decoded section5",
                "right" to "Supplied running-camera A capture; not reset state",
                "addressMappingStatus" to "Section-base correspondence inferred from size/content; acquisition address log absent")
        }
        val config = entries.getValue("files/config-storage-main.bin").bytes
        val ram = entries.getValue("files/config-ram-snapshot.bin").bytes
        val logs = listOf("log-enabled.bin", "log-pointer.bin", "log-control.bin").map { name ->
            val bytes = entries.getValue("files/"+name).bytes
            mapOf("entry" to "files/"+name, "bytes" to bytes.size, "allZero" to bytes.all { it == 0.toByte() },
                "meaning" to "One supplied snapshot; no address/reset/disable semantics proved")
        }
        val dynamicPoints = listOf(0x7f14be to 0xd2, 0x7fa600 to 0x60).map { (offset,a) ->
            val actual = entries.getValue("files/runtime-primary.bin").bytes[offset].toInt() and 255
            mapOf("fileOffset" to offset, "claimedCpuAddress" to APP_BASE+offset, "noteA" to a, "actualA" to actual,
                "matchesNoteA" to (actual == a), "secondReadPresent" to false)
        }
        val powerCacheOffset = Math.toIntExact(0x1878f74L - APP_BASE)
        val primary = entries.getValue("files/runtime-primary.bin").bytes
        val powerCacheInCapture = powerCacheOffset >= 0 && powerCacheOffset + 0x44 <= primary.size
        return linkedMapOf("schema" to 1, "status" to "OFFLINE_FILE_VERIFIED_NOT_BOOT_ACCEPTANCE", "evidenceClasses" to listOf("OWNER", "FILE", "DAT"),
            "archive" to mapOf("path" to repo.relativize(archive).toString().replace(92.toChar(),'/'), "bytes" to Files.size(archive), "sha256" to sha(Files.readAllBytes(archive))),
            "fileCount" to entries.size, "expandedBytes" to entries.values.sumOf { it.bytes.size.toLong() }, "payloads" to verified,
            "zipCrcAllPassed" to true, "providedManifestAllPassed" to true, "firmwareId" to metadata.id,
            "firmwareSource" to "firmware-data/gfx100s/2.13/GXUP0004.DAT", "firmwareSha256" to metadata.sha256,
            "missingAcquisitionEvidence" to listOf("runs/ device identity, tool versions, exact commands, both-read manifests and logs", "Original B captures", "Atomic CPU/MMU/task snapshot", "Power-peer SPI wire transactions"),
            "bootRom" to mapOf("entry" to "files/boot-rom.bin", "catalogIdentityMatches" to romMatches,
                "catalogCpuAddress" to catalog["boot_rom"]["address"].asLong(), "first32BytesHex" to HexFormat.of().formatHex(rom.bytes,0,32), "coldBootTested" to false),
            "partitionTable" to mapOf("datAddress" to TABLE_ADDRESS, "entryBytes" to 12, "blockBytes" to 0x20000,
                "thirdWordMeaning" to "UNKNOWN; source index class_number is a label", "rows" to datRows,
                "zeroLengthBanks" to datRows.filter { it.getValue("size") == 0L }.map { it.getValue("bank") },
                "uncoveredRanges" to coverage(datRows, flash.bytes.size.toLong()), "physicalChipCapacityConfirmed" to false),
            "partitions" to partitions, "runtimeVsDat" to runtimeComparisons,
            "persistentConfigVsRamPrefix" to mapOf("basis" to "File-offset comparison only; no claim that RAM is a direct persistent mirror", "comparison" to difference(config,ram)),
            "runtimePrimaryNote" to mapOf("atomic" to false, "doubleReadEqualityVerified" to false, "claimedDifferences" to dynamicPoints),
            "logSnapshots" to logs, "power25" to mapOf("candidateCacheAddressFromDat" to 0x1878f74L,
                "fitsPrimaryAtCandidateSectionBase" to powerCacheInCapture, "selectorResponsesCaptured" to false,
                "scope" to "Do not fill 0x25/0x26/0x27 replies from a ROM/Flash/RAM filename or a stale variable"),
            "guestExecuted" to false, "hardwareOrProfileChanged" to false)
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 8 && args.toList().chunked(2).map { it[0] }.toSet() == setOf("--repo","--archive","--output","--git"))
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        val repo = Path.of(options.getValue("--repo")).toAbsolutePath().normalize()
        val archive = Path.of(options.getValue("--archive")).toAbsolutePath().normalize()
        val output = Path.of(options.getValue("--output")).toAbsolutePath().normalize()
        require(archive.startsWith(repo) && output.startsWith(repo) && !Files.exists(output))
        fun git(vararg argv: String): String {
            val process = ProcessBuilder(listOf(options.getValue("--git"),"-c","core.longpaths=true","-C",repo.toString()) + argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val bytes = process.inputStream.readAllBytes(); require(process.waitFor() == 0)
            return bytes.toString(Charsets.UTF_8).trimEnd()
        }
        val commit = git("rev-parse","HEAD")
        val source = "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareDumpResearch.kt"
        require(git("status","--porcelain","--",source,"tools/research/hardware-dump.init.gradle.kts",repo.relativize(archive).toString()).isEmpty()) { "Commit analysis source and archive before formal run" }
        val report = analyze(repo,archive) + mapOf("sourceCommit" to commit, "sourcePath" to source,
            "arguments" to args.toList(), "javaVersion" to System.getProperty("java.version"), "scope" to "Offline import only; no hardware, firmware profile, permissions, CPU or timer changed")
        Files.createDirectories(output.parent)
        Files.write(output, Formats.jsonBytes(report), CREATE_NEW)
        println("Hardware dump: " + report["fileCount"] + " files; manifest/CRC/DAT table checked. Original capture logs absent; guest not executed.")
    }
}
