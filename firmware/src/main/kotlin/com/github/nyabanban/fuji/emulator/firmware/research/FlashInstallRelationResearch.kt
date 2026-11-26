package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.firmware.Method4
import com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.concurrent.TimeUnit

/** Offline relation only: compare installed bytes without granting CPU maps or image write domains. */
object FlashInstallRelationResearch {
    private const val DAT_IMAGE = 0x274L
    private const val METADATA_OFFSET = 0x140000L
    private const val APERTURE = 0xf0000000L
    internal fun slice(bytes: ByteArray, offset: Long, length: Long): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "Input span outside byte array" }
        return bytes.copyOfRange(Math.toIntExact(offset), Math.toIntExact(offset + length))
    }
    internal fun compare(installed: ByteArray, offset: Long, expected: ByteArray): Map<String, Any?> =
        HardwareDumpResearch.difference(installed, expected, Math.toIntExact(offset))
    internal fun packedLength(bytes: ByteArray, offset: Long, allocation: Long): Int {
        val header = ByteBuffer.wrap(slice(bytes, offset, 20)).order(ByteOrder.LITTLE_ENDIAN)
        val unpacked = header.getInt(0).toLong() and 0xffffffffL
        val packed = header.getInt(4).toLong() and 0xffffffffL
        val pages = header.getInt(8).toLong() and 0xffffffffL
        val pageBytes = header.getInt(12).toLong() and 0xffffffffL
        val method = header.getInt(16).toLong() and 0xffffffffL
        require(allocation >= 20 && packed in 20..allocation && offset <= bytes.size && packed <= bytes.size - offset) { "Compressed extent outside declared allocation" }
        require(unpacked in 1..0x4000000L && pages == (packed + 0x3fff) / 0x4000 && pageBytes == 0x4000L && method == 4L) { "Invalid method4 header" }
        return Math.toIntExact(packed)
    }
    internal fun unpacked(bytes: ByteArray, offset: Long, allocation: Long): ByteArray =
        Method4.decode(slice(bytes,offset,packedLength(bytes,offset,allocation).toLong()))
    internal fun initializedReservation(decodedBytes: Long, declaredBytes: Long): Map<String, Any> {
        require(decodedBytes in 1..0x4000000L && declaredBytes >= decodedBytes)
        val rounded = Math.addExact(decodedBytes,4095L) and -4096L
        require(declaredBytes == decodedBytes || declaredBytes == rounded) { "Descriptor length is neither payload length nor its 4 KiB reservation" }
        return mapOf("decodedPayloadBytes" to decodedBytes,"descriptorInitializedBytes" to declaredBytes,
            "relation" to if (declaredBytes == decodedBytes) "EXACT" else "MATCHES_4K_ROUND_UP",
            "trailingReservationBytes" to declaredBytes - decodedBytes,
            "trailingRuntimeContentsProved" to false)
    }
    internal fun mapping(offset: Long, size: Long): Map<String, Any> {
        require(offset >= 0 && size > 0 && offset <= 0x8000000L && size <= 0x8000000L - offset)
        return mapOf("flashLogicalOffset" to offset, "flashLogicalEndExclusive" to offset + size,
            "datFileOffset" to DAT_IMAGE + offset, "datFileEndExclusive" to DAT_IMAGE + offset + size,
            "configuredApertureStart" to APERTURE + offset, "configuredApertureEndExclusive" to APERTURE + offset + size,
            "physicalCpuMappingProved" to false)
    }
    private fun inputBytes(image: FirmwareImage, offset: Long, size: Int): ByteArray {
        val buffer = ByteBuffer.allocate(size); image.inputPort().read(offset,buffer)
        check(!buffer.hasRemaining()); return buffer.array()
    }
    fun analyze(sourceRepo: Path, inputRepo: Path, archive: Path): Map<String, Any?> {
        val archiveEntries = HardwareDumpResearch.readArchive(archive)
        HardwareDumpResearch.manifest(archiveEntries)
        val installed = archiveEntries.getValue("files/flash-layout.bin").bytes
        require(installed.size == 0x4000000) { "This input profile is the retained 64 MiB software Flash view" }
        val metadata = FirmwareMetadata(sourceRepo.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        val image = FirmwareImage(metadata,inputRepo.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT"))
        val header = ByteBuffer.wrap(inputBytes(image,0,0x274)).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(0) == 6)
        val length = header.getInt(0x24c).toLong() and 0xffffffffL
        require(length in 1..installed.size.toLong() && length <= image.inputPort().size - DAT_IMAGE)
        val prefix = inputBytes(image,DAT_IMAGE,Math.toIntExact(length)).apply { indices.forEach { this[it] = this[it].toInt().inv().toByte() } }
        val plan = SegmentsContracts.derive(slice(prefix,METADATA_OFFSET,1024))
        val raw = metadata.sections.filter { it.encoding == "raw" }.map { section ->
            val offset = section.offset.toLong() - DAT_IMAGE
            val decoded = image.decode(section.name)
            require(slice(prefix,offset,decoded.size.toLong()).contentEquals(decoded)) { "DAT prefix and independent raw decoder differ" }
            mapOf("name" to section.name, "mapping" to mapping(offset,decoded.size.toLong()),
                "decodedDestinationFromMetadata" to section.address, "encoding" to "DAT xor-ff; raw installed candidate",
                "datPrefixMatchesIndependentDecode" to true, "installedVsDecoded" to compare(installed,offset,decoded))
        }
        val records = plan.records.map { record ->
            val allocation = slice(prefix,record.flashOffset,record.flashAllocation)
            val common = linkedMapOf<String, Any?>("id" to record.id, "record" to record.report(),
                "mapping" to mapping(record.flashOffset,record.flashAllocation),
                "installedVsDatAllocation" to compare(installed,record.flashOffset,allocation),
                "basis" to "DAT descriptor offset candidate checked against retained mapped-flash-storage bytes; not inferred CPU mapping")
            if (record.id == 5L || record.id == 6L) {
                val packed = packedLength(prefix,record.flashOffset,record.flashAllocation)
                val decoded = unpacked(prefix,record.flashOffset,record.flashAllocation)
                val section = metadata.sections.single { it.encoding == "method4" && it.address == record.initializedAddress }
                require(section.offset.toLong() == DAT_IMAGE + record.flashOffset && section.size == decoded.size) { "Section source offset/decoded length differs from descriptor payload" }
                common["initializedReservation"] = initializedReservation(decoded.size.toLong(),record.initializedSize)
                require(image.decode(section.name).contentEquals(decoded)) { "Descriptor payload differs from independent DAT section" }
                common["kind"] = "method4"
                common["section"] = section.name
                common["packedBytes"] = packed
                common["allocationPaddingBytes"] = record.flashAllocation - packed
                common["decodedBytes"] = decoded.size
                common["datDecodedMatchesIndependentSection"] = true
                common["installedVsPackedDatPayload"] = compare(installed,record.flashOffset,slice(prefix,record.flashOffset,packed.toLong()))
                // Header/decoder failures are recorded as evidence, never converted to zero or ignored equality.
                common["installedDecode"] = try {
                    val installedPacked = packedLength(installed,record.flashOffset,record.flashAllocation)
                    val installedDecoded = unpacked(installed,record.flashOffset,record.flashAllocation)
                    mapOf("status" to "DECODED", "packedBytes" to installedPacked,
                        "decodedVsDat" to compare(installedDecoded,0,decoded))
                } catch (error: IllegalArgumentException) {
                    mapOf("status" to "REFUSED", "reason" to (error.message ?: error.javaClass.name))
                }
            } else {
                common["kind"] = "BOUNDED_SOURCE_ONLY; compression/semantic interpretation not claimed"
                if (record.initializedSize in 1..record.flashAllocation) {
                    common["installedVsInitializedSource"] = compare(installed,record.flashOffset,slice(prefix,record.flashOffset,record.initializedSize))
                } else common["initializedSourceComparison"] = "NOT_APPLICABLE; no positive bounded initialized payload"
            }
            common
        }
        val prefixComparison = compare(installed,0,prefix)
        val supplemental = installed.size.toLong() - length
        return linkedMapOf("schema" to 1, "status" to "OFFLINE_INSTALLATION_RELATION_NOT_BOOT_ACCEPTANCE",
            "firmwareId" to metadata.id, "inputArchive" to "firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip",
            "inputEntry" to "files/flash-layout.bin", "inputBytes" to installed.size,
            "inputMeaning" to "Retained software mapped-flash-storage view; physical chip capacity and reset mapping remain unknown",
            "datSource" to "firmware-data/gfx100s/2.13/GXUP0004.DAT", "datImagePrefixMapping" to mapping(0,length),
            "datPrefixVsInstalled" to prefixComparison,
            "rawSections" to raw, "metadataMapping" to mapping(METADATA_OFFSET,1024),
            "installedVsMetadata" to compare(installed,METADATA_OFFSET,slice(prefix,METADATA_OFFSET,1024)),
            "installationRecords" to records,
            "notInDatPrefix" to mapOf("offset" to length,"endExclusive" to installed.size,"bytes" to supplemental,
                "meaning" to "Real retained bytes beyond current DAT prefix; not a grant to read or write this whole span"),
            "nextInputPolicy" to mapOf("defaultChanged" to false,"replaceDatPrefixDirectly" to false,
                "readSelection" to "Freeze explicit FILE-backed slices only after checking each existing supplemental read domain and allocation; preserve prefix until discrepancies are explained",
                "writes" to "Keep existing separate writable domains, erase/program law and private COW; no permission inferred from file length",
                "ram" to "No runtime or config RAM hydration; guest must perform its own copy, zero, relocation and validation"),
            "evidenceClasses" to listOf("FILE","DAT"),"guestExecuted" to false,"runtimeOrPermissionsChanged" to false)
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 10 && args.toList().chunked(2).map { it[0] }.toSet() == setOf("--repo","--inputs","--archive","--output","--git"))
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        val repo = Path.of(options.getValue("--repo")).toAbsolutePath().normalize()
        val inputs = Path.of(options.getValue("--inputs")).toAbsolutePath().normalize()
        val archive = Path.of(options.getValue("--archive")).toAbsolutePath().normalize()
        val output = Path.of(options.getValue("--output")).toAbsolutePath().normalize()
        require(archive.startsWith(inputs) && output.startsWith(repo) && !Files.exists(output))
        fun gitAt(at: Path, vararg argv: String): String {
            val process = ProcessBuilder(listOf(options.getValue("--git"),"-c","core.longpaths=true","-C",at.toString()) + argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val bytes = process.inputStream.readAllBytes(); require(process.waitFor(30,TimeUnit.SECONDS) && process.exitValue() == 0)
            return bytes.toString(Charsets.UTF_8).trimEnd()
        }
        val sourceCommit = gitAt(repo,"rev-parse","HEAD")
        val inputCommit = gitAt(inputs,"rev-parse","HEAD")
        val source = "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/FlashInstallRelationResearch.kt"
        val related = listOf(source,"firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareDumpResearch.kt",
            "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/Firmware.kt",
            "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/SegmentsContracts.kt",
            "configurations/jvm/firmware/gfx100s-213.yaml","tools/research/flash-install-relation.init.gradle.kts")
        require(gitAt(repo,*(listOf("status","--porcelain","--") + related).toTypedArray()).isEmpty()) { "Commit related source before formal analysis" }
        require(gitAt(inputs,"status","--porcelain","--","firmware-data/gfx100s/2.13/GXUP0004.DAT",inputs.relativize(archive).toString()).isEmpty()) { "Commit input bytes before formal analysis" }
        val result = analyze(repo,inputs,archive)
        require(gitAt(repo,"rev-parse","HEAD") == sourceCommit && gitAt(repo,*(listOf("status","--porcelain","--") + related).toTypedArray()).isEmpty()) { "Source changed during analysis" }
        val report = result + mapOf("sourceCommit" to sourceCommit,"sourcePath" to source,
            "inputCommit" to inputCommit,"arguments" to args.toList(),"javaVersion" to System.getProperty("java.version"))
        Files.createDirectories(output.parent); Files.write(output,Formats.jsonBytes(report),CREATE_NEW)
        println("Flash installation relation checked: " + (result["installationRecords"] as List<*>).size + " DAT records; no guest or permissions changed")
    }
}
