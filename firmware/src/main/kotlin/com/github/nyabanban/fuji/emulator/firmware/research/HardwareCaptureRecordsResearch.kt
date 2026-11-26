package com.github.nyabanban.fuji.emulator.firmware.research

import com.fasterxml.jackson.databind.JsonNode
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Cross-check supplied collection records; never executes recorded commands or contacts a camera. */
object HardwareCaptureRecordsResearch {
    internal class ReadLedger(val base: Long, val size: Long) {
        var consumed = 0L; private set
        var chunks = 0L; private set
        init { require(base >= 0 && size > 0 && base <= Long.MAX_VALUE - size) }
        fun accept(offset: Long, requested: Long, received: Long) {
            require(requested in 1..256 && received == requested && offset == base + consumed && requested <= size - consumed) { "READ gap, overlap, short read or overrun" }
            consumed += requested; chunks++
        }
        fun finish() { require(consumed == size) { "Incomplete READ sequence" } }
    }
    internal fun number(node: JsonNode?): Long {
        require(node != null && node.isIntegralNumber && node.canConvertToLong()) { "Expected exact integer" }
        return node.asLong()
    }
    internal fun checkPasses(file: JsonNode, actual: HardwareDumpResearch.Entry?): Map<String, Any?> {
        val passes = file["passes"].toList()
        require(passes.size == 2 && passes.map { it["pass"].asText() }.toSet() == setOf("A","B"))
        val a = passes.single { it["pass"].asText() == "A" }; val b = passes.single { it["pass"].asText() == "B" }
        val size = number(file["size"])
        require(number(a["actual"]) == size && number(b["actual"]) == size)
        val shaA = a["sha256"].asText(); val shaB = b["sha256"].asText()
        require(listOf(shaA,shaB).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(file["byte_equal"].isBoolean && file["byte_equal"].asBoolean() == (shaA == shaB)) { "Recorded equality contradicts hashes" }
        if (actual != null) require(actual.bytes.size.toLong() == size && actual.sha256 == shaA) { "Retained A differs from record" }
        return mapOf("name" to file["name"].asText(), "bytes" to size, "recordedAFile" to a["file"].asText(),
            "retainedAMatches" to if (actual == null) null else true, "recordedBFile" to b["file"].asText(),
            "recordedPassHashesEqual" to (shaA == shaB), "recordedByteEqual" to file["byte_equal"].asBoolean(),
            "recordedDifferentBytes" to file["different_bytes"]?.let { number(it) },
            "recordedDifferenceOffsets" to file["first_difference_offsets"]?.map { number(it) },
            "address" to file["address"]?.let { number(it) }, "offset" to number(file["offset"]),
            "view" to file["view"].asText(), "transport" to file["transport"]?.asText(),
            "bBytesAvailable" to false, "atomicSnapshot" to false)
    }
    internal fun verifyCode(row: JsonNode, sections: List<Pair<Long,ByteArray>>) {
        val address = number(row["address"]); val size = number(row["size"])
        require(size in 1..65536)
        val (base,data) = sections.single { (base,data) -> address >= base && address-base <= data.size.toLong()-size }
        val start = Math.toIntExact(address-base)
        require(HardwareDumpResearch.sha(data.copyOfRange(start,start+Math.toIntExact(size))) == row["sha256"].asText()) { "Capture code fingerprint differs from DAT" }
    }
    fun analyze(repo: Path, dumpPath: Path, runsPath: Path): Map<String,Any?> {
        val dumps = HardwareDumpResearch.readArchive(dumpPath)
        val runs = HardwareDumpResearch.readArchive(runsPath)
        val metadata = FirmwareMetadata(repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        val image = FirmwareImage(metadata,repo.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT"))
        val sections = listOf("section4","section5").map { name -> metadata.sections.single { it.name == name }.address to image.decode(name) }
        val recordNames = runs.keys.filter { it.endsWith("/record.json") }.sorted()
        require(recordNames.size == 12 && runs.size == 24 && recordNames.all { it.removeSuffix("record.json")+"read.log" in runs })
        val identities = hashSetOf<String>()
        val matchedNames = hashSetOf<String>()
        val reports = recordNames.map { name ->
            val record = Formats.json.readTree(runs.getValue(name).bytes)
            val logName = name.removeSuffix("record.json")+"read.log"
            val log = runs.getValue(logName)
            require(log.sha256 == record["read_log_sha256"].asText()) { "read.log identity differs" }
            identities.add(record["identity"].toString())
            require(record["identity"]["4"].asText() == "GFX100S")
            val files = record["files"].toList()
            val byName = files.associateBy { it["name"].asText() }
            require(byName.size == files.size)
            val ledgers = files.flatMap { file -> listOf("A","B").map { pass ->
                (file["name"].asText() to pass) to ReadLedger(number(file["offset"]),number(file["size"]))
            } }.toMap()
            val retained = files.map { file ->
                val payload = "files/"+file["name"].asText()+".bin"
                val actual = dumps[payload]
                if (actual != null) require(matchedNames.add(payload)) { "Payload matched multiple records" }
                checkPasses(file,actual)
            }
            val counts = linkedMapOf<String,Long>()
            val pins = mutableListOf<Map<String,Any?>>()
            val anchors = mutableListOf<Map<String,Any?>>()
            val comparisons = hashSetOf<String>()
            val errors = mutableListOf<String>()
            log.bytes.inputStream().bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                require(line.length <= 8192)
                if (line.isBlank()) return@forEach
                val kind = line.substringBefore(' ')
                val row = Formats.json.readTree(line.substringAfter(' '))
                counts[kind] = (counts[kind] ?: 0L)+1
                when (kind) {
                    "READ" -> {
                        val key = row["n"].asText() to row["p"].asText()
                        (ledgers[key] ?: error("Undeclared READ")).accept(number(row["o"]),number(row["q"]),number(row["r"]))
                    }
                    "CODE", "SNAPSHOT_CODE" -> {
                        verifyCode(row,sections)
                        pins.add(mapOf("kind" to kind,"name" to row["name"]?.asText(),"address" to number(row["address"]),"bytes" to number(row["size"]),"matchesDat" to true))
                    }
                    "ANCHOR" -> {
                        val offset = number(row["offset"]); val length = number(row["length"])
                        val flash = dumps.getValue("files/flash-layout.bin").bytes
                        require(offset >= 0 && length in 1..65536 && offset <= flash.size-length)
                        val actual = HardwareDumpResearch.sha(flash.copyOfRange(offset.toInt(),(offset+length).toInt()))
                        require(actual == row["sha256_a"].asText() && actual == row["sha256_b"].asText())
                        anchors.add(mapOf("offset" to offset,"bytes" to length,"retainedFlashMatchesRecordedAB" to true,
                            "recordedOfficialMatch" to row["official_match"].asBoolean()))
                    }
                    "COMPARE" -> {
                        val fileName = row["name"].asText(); require(comparisons.add(fileName))
                        val file = byName.getValue(fileName)
                        for (key in listOf("byte_equal","different_bytes","first_difference_offsets")) require(row[key] == file[key]) { "COMPARE differs from record" }
                    }
                    "ERROR" -> errors.add(row.asText())
                    "SESSION", "CALLBACK", "STATE", "CLOSE" -> Unit
                    else -> error("Unknown read.log event: "+kind)
                }
            } }
            ledgers.values.forEach { it.finish() }
            require(counts["CLOSE"] == 1L)
            val status = record["status"].asText()
            require(status in setOf("ok","different","failed"))
            if (status == "failed") require(errors == listOf(record["error"].asText()) && files.isEmpty()) else require(errors.isEmpty())
            if (record.has("snapshot_mode")) require(comparisons == byName.keys)
            mapOf("recordEntry" to name,"logEntry" to logName,"logShaMatchesRecord" to true,
                "statusPreserved" to status,"recordedError" to record["error"]?.asText(),"utc" to record["utc"].asText(),
                "tool" to record["tool"].asText(),"recordedCommandNotExecuted" to record["command"].asText(),
                "referenceCommits" to mapOf("fujimod" to record["fujimod_commit"].asText(),"fffw" to record["fffw_commit"].asText()),
                "recordedGate" to record["ram_gate"],"eventCounts" to counts,"codePins" to pins,"flashAnchors" to anchors,
                "readCoverage" to ledgers.map { (key,ledger) -> mapOf("name" to key.first,"pass" to key.second,"base" to ledger.base,"bytes" to ledger.consumed,"chunks" to ledger.chunks,"completeContiguous" to true) },
                "files" to retained)
        }
        require(identities.size == 1 && matchedNames.size == 9)
        val app = sections[0].second
        val extra = ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        val at = Math.toIntExact(HardwareDumpResearch.TABLE_ADDRESS - sections[0].first + 32*12)
        return mapOf("schema" to 1,"status" to "ACQUISITION_RECORDS_CROSS_CHECKED",
            "evidenceScope" to "Supplied HARDWARE acquisition records cross-checked with retained bytes and DAT. Not a fresh physical capture or proof of atomic/reset state.",
            "runsArchive" to mapOf("path" to repo.relativize(runsPath).toString().replace(92.toChar(),'/'),"bytes" to Files.size(runsPath),"sha256" to HardwareDumpResearch.sha(Files.readAllBytes(runsPath))),
            "dumpArchivePath" to repo.relativize(dumpPath).toString().replace(92.toChar(),'/'),"runCount" to recordNames.size,"fileCount" to runs.size,
            "sameRecordedIdentity" to true,"model" to "GFX100S","reportedInternalVersion" to "1.94",
            "versionScope" to "Do not treat identity selector 8 as public release. CODE fingerprints were checked against selected 2.13 DAT.",
            "retainedFullPayloadsMatched" to matchedNames.sorted(),"records" to reports,
            "additionalTableRow32" to mapOf("address" to HardwareDumpResearch.TABLE_ADDRESS+32*12,"words" to (0..2).map { extra.getInt(at+it*4).toLong() and 0xffffffffL },"meaning" to "Zero-length extra row in 396-byte fingerprint; not a newly mapped bank or proved sentinel"),
            "limitations" to listOf("B payloads absent; recorded A/B digests checked, no independent B byte comparison", "READ events contain lengths, not USB payload bytes", "Acquisition scripts/profile bytes not in archive; historical hashes/commits are provenance references only", "Scene, controls, lens/battery state and atomic CPU/MMU context incomplete", "No power-peer 0x25/0x26/0x27 response frames"),
            "cameraAccessed" to false,"guestExecuted" to false)
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 10)
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        require(options.keys == setOf("--repo","--dump","--runs","--output","--git"))
        val repo = Path.of(options.getValue("--repo")).toAbsolutePath().normalize()
        fun input(key:String) = Path.of(options.getValue(key)).toAbsolutePath().normalize().also { require(it.startsWith(repo)) }
        val dump=input("--dump");val runs=input("--runs");val output=input("--output")
        require(!Files.exists(output))
        fun git(vararg argv:String):String {
            val p=ProcessBuilder(listOf(options.getValue("--git"),"-c","core.longpaths=true","-C",repo.toString())+argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val out=p.inputStream.readAllBytes();require(p.waitFor()==0);return out.toString(Charsets.UTF_8).trimEnd()
        }
        val source="firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareCaptureRecordsResearch.kt"
        require(git("status","--porcelain","--",source,"firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareDumpResearch.kt","tools/research/hardware-dump.init.gradle.kts",repo.relativize(dump).toString(),repo.relativize(runs).toString()).isEmpty())
        val report=analyze(repo,dump,runs)+mapOf("sourceCommit" to git("rev-parse","HEAD"),"sourcePath" to source,"arguments" to args.toList(),"javaVersion" to System.getProperty("java.version"))
        Files.createDirectories(output.parent);Files.write(output,Formats.jsonBytes(report),CREATE_NEW)
        println("Capture records: "+report["runCount"]+" runs; original failed/different statuses preserved; no camera/guest execution.")
    }
}
