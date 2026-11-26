package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Bounded FILE navigation. This class cannot map memory, instantiate a CPU or execute a guest. */
object BootRomEntryResearch {
    private const val BASE = 0xfff00000L
    private const val ENTRY = "files/boot-rom.bin"
    private const val ARCHIVE = "firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip"
    private const val SOURCE = "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/BootRomEntryResearch.kt"
    private fun objectMap(value: Any?): Map<String,Any?> {
        require(value is Map<*,*> && value.keys.all { it is String })
        @Suppress("UNCHECKED_CAST") return value as Map<String,Any?>
    }
    private fun readJsonObject(path: Path): Map<String,Any?> {
        require(Files.size(path) in 1..262144)
        return objectMap(Formats.json.readValue(Files.readAllBytes(path),Map::class.java))
    }
    private fun hex(n: Long) = "0x%08X".format(n)
    private fun word(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset % 4 == 0 && offset <= bytes.size - 4)
        return (0..3).fold(0L) { result, i -> result or ((bytes[offset+i].toLong() and 255) shl (8*i)) }
    }
    private fun branch(bytes: ByteArray, offset: Int, base: Long): Long {
        val w = word(bytes,offset)
        require(w and 0x0e000000L == 0x0a000000L && w ushr 28 != 15L)
        val displacement = ((w and 0xffffff).toInt() shl 8 shr 8).toLong() * 4
        return (base + offset + 8 + displacement) and 0xffffffffL
    }
    private fun literal(bytes: ByteArray, offset: Int): Pair<Int,Long> {
        val w = word(bytes,offset)
        require(w and 0xffff0000L == 0xe59f0000L || w and 0xffff0000L == 0xe51f0000L)
        val at = offset + 8 + (if (w and 0x800000L != 0L) 1 else -1) * (w and 0xfff).toInt()
        return at to word(bytes,at)
    }
    private fun row(bytes: ByteArray, offset: Int, explanation: String): Map<String,Any> =
        mapOf("romOffset" to offset,"captureViewAddress" to hex(BASE+offset),"word" to hex(word(bytes,offset)),"interpretation" to explanation)

    fun analyze(repo: Path, inputs: Path): Map<String,Any?> {
        // Exact named entry only. No readArchive, extraction, DAT decode or whole-ZIP hash.
        val bytes = ZipFile(inputs.resolve(ARCHIVE).toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry(ENTRY))
            require(!entry.isDirectory && entry.size == 32768L)
            zip.getInputStream(entry).use { it.readNBytes(32769) }.also { require(it.size == 32768) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4")
        val original = readJsonObject(inputs.resolve("firmware-data/gfx100s/2.13/hardware/gfx100s-20261112/analysis.generated.json"))
        val capture = readJsonObject(inputs.resolve("firmware-data/gfx100s/2.13/hardware/gfx100s-20261112/capture-records.generated.json"))
        val record = (capture.getValue("records") as List<*>).map { objectMap(it) }
            .single { it["recordEntry"] == "runs/boot-rom-full-01/record.json" }
        val retained = (record.getValue("files") as List<*>).map { objectMap(it) }.single { it["name"] == "boot-rom" }
        require(retained["retainedAMatches"] == true && (retained["address"] as Number).toLong() == BASE)
        val metadata = FirmwareMetadata(repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        val booleanWindow = metadata.devices.single { it.port == "soc-system-control.boolean" }
        require(booleanWindow.address == literal(bytes,0x174).second && booleanWindow.size == 4L)
        val body = CatalogIO.readYaml(repo.resolve("catalog/bodies/gfx100s/body.yaml"))
        val catalogWindow = (body.getValue("windows") as List<*>).map { objectMap(it) }
            .single { it["portId"] == "soc-system-control.boolean" }
        require((catalogWindow["base"] as Number).toLong() == booleanWindow.address)

        // Fixed finite path and exact complete words, not a recursive disassembler or execution engine.
        val expected = linkedMapOf(0x0 to 0xea000006L,0x20 to 0xe51ff004L,
            0x130 to 0xee100fb0L,0x134 to 0xe2100003L,0x138 to 0x0a000006L,0x158 to 0xea000005L,
            0x174 to 0xe59f11ccL,0x178 to 0xe3a00001L,0x17c to 0xe5810000L,
            0x180 to 0xee110f10L,0x184 to 0xe3c00a01L,0x188 to 0xe3c00004L,
            0x18c to 0xe3c00001L,0x190 to 0xe3c00b02L,0x194 to 0xee010f10L,
            0x198 to 0xee110f30L,0x19c to 0xe3800040L,0x1a0 to 0xee010f30L,
            0x1a4 to 0xe3a00000L,0x1a8 to 0xee080f17L,0x1ac to 0xee070fd5L,
            0x1b0 to 0xe3a00001L,0x1b4 to 0xee030f10L,0x1b8 to 0xe321f0d3L,
            0x1bc to 0xe3a0c000L,0x1c0 to 0xe3a0e000L,0x1c4 to 0xe59fd180L,
            0x1c8 to 0xeb00006dL,0x384 to 0xe92d4008L,
            0x388 to 0xe59f0118L,0x38c to 0xe5900000L)
        expected.forEach { (at,w) -> require(word(bytes,at) == w) { "Selected ROM word differs at ${hex(at.toLong())}" } }
        (0..11).forEach { reg -> require(word(bytes,0x100+reg*4) == 0xe3a00000L+(reg.toLong() shl 12)) }
        require(branch(bytes,0,0) == 0x20L && literal(bytes,0x20) == (0x24 to (BASE+0x100)))
        require(branch(bytes,0x138,BASE) == BASE+0x158 && branch(bytes,0x158,BASE) == BASE+0x174 && branch(bytes,0x1c8,BASE) == BASE+0x384)
        val sp = literal(bytes,0x1c4).second
        val stackStart = sp - 8
        val profiles = metadata.memoryProfiles.map { (name,regions) ->
            mapOf("profile" to name,"stackWriteCovered" to regions.any { it.address <= stackStart && sp <= it.address+it.size && it.permissions and 2 != 0 })
        }
        require(profiles.none { it["stackWriteCovered"] == true })
        val cpOffsets = listOf(0x130,0x180,0x194,0x198,0x1a0,0x1a8,0x1ac,0x1b4)
        val cp = cpOffsets.map { at ->
            val w = word(bytes,at)
            mapOf("romOffset" to at,"word" to hex(w),"direction" to if (w and 0x100000L != 0L) "MRC" else "MCR",
                "coprocessor" to ((w ushr 8) and 15),"opc1" to ((w ushr 21) and 7),"crn" to ((w ushr 16) and 15),
                "crm" to (w and 15),"opc2" to ((w ushr 5) and 7),"rt" to ((w ushr 12) and 15))
        }
        val vectors = listOf(0L,BASE,0xffff0000L).map { base ->
            val targets = (0..7).map { i -> branch(bytes,i*4,base) }
            mapOf("candidateVectorBase" to hex(base),"words" to (0..7).map { hex(word(bytes,it*4)) },
                "directTargets" to targets.map(::hex),"resetTrampolineLiteral" to hex(literal(bytes,0x20).second),
                "nonResetTargetsAllInCapturedHighRom" to targets.drop(1).all { it in BASE until BASE+bytes.size },
                "mappingProved" to false)
        }
        val trace = mutableListOf(row(bytes,0,"A32 B to vector-base+0x20"),row(bytes,0x20,"A32 LDR pc,[pc,#-4]; literal@0x24 = ${hex(literal(bytes,0x20).second)}"))
        (0..11).forEach { trace += row(bytes,0x100+it*4,"MOV r$it,#0") }
        trace += row(bytes,0x130,"MRC p15,0,r0,c0,c0,5; core affinity input, actual reset value UNKNOWN")
        trace += row(bytes,0x134,"ANDS r0,r0,#3; select core0 conditionally on masked input=0")
        trace += row(bytes,0x138,"BEQ ${hex(branch(bytes,0x138,BASE))}; this selected edge assumes core0")
        trace += row(bytes,0x158,"B ${hex(branch(bytes,0x158,BASE))}")
        trace += row(bytes,0x174,"LDR r1, literal@${hex(literal(bytes,0x174).first.toLong())} = ${hex(booleanWindow.address)}")
        trace += row(bytes,0x178,"MOV r0,#1")
        trace += row(bytes,0x17c,"STR r0,[r1]; existing boolean port accepts with booleanWrite=true; effects UNKNOWN")
        (0x180..0x1b4 step 4).forEach { trace += row(bytes,it,"Selected CP15 setup or immediate data instruction; CP encodings listed separately") }
        trace += row(bytes,0x1b8,"MSR CPSR_c,#0xD3; initial privilege/security still required before this instruction")
        trace += row(bytes,0x1bc,"MOV r12,#0")
        trace += row(bytes,0x1c0,"MOV lr,#0")
        trace += row(bytes,0x1c4,"LDR sp, literal@${hex(literal(bytes,0x1c4).first.toLong())} = ${hex(sp)}")
        trace += row(bytes,0x1c8,"BL ${hex(branch(bytes,0x1c8,BASE))}; produced LR=${hex(BASE+0x1cc)}")
        trace += row(bytes,0x384,"PUSH {r3,lr}; first required backing is [${hex(stackStart)},${hex(sp)}); stop static walk here")
        return linkedMapOf("schema" to 1,"status" to "BOUNDED_FILE_NAVIGATION_NOT_COLD_BOOT",
            "input" to mapOf("archivePath" to ARCHIVE,"entry" to ENTRY,"entryBytes" to bytes.size,"entrySha256" to digest,"entriesOpened" to listOf(ENTRY),"wholeArchiveRehashed" to false),
            "existingFileEvidence" to original["bootRom"],"existingHardwareCaptureRecord" to mapOf("reportPath" to "evidence/hardware/gfx100s-20261112/capture-records.generated.json","reportSourceCommit" to capture["sourceCommit"],"recordEntry" to record["recordEntry"],"tool" to record["tool"],"utc" to record["utc"],"retainedFile" to retained),
            "scope" to "Offsets 0..0x24 vector/trampoline; selected core0 path 0x100..0x138, 0x158, 0x174..0x1C8, first instruction 0x384; literal pool locations and a two-instruction post-frontier preview only. No full-ROM scan.",
            "instructionState" to mapOf("selected" to "A32 little-endian candidate","basis" to "Eight complete A32 unconditional branch words, PC-relative trampoline, register clears, conditional dispatch and in-entry literal targets agree",
                "firstT32Halfwords" to listOf(word(bytes,0) and 65535,word(bytes,0) ushr 16),"t32ResetVectorIdentified" to false,"t32ImpossibleProved" to false,"initialCpsrT" to "UNKNOWN; candidate requires 0"),
            "vectorCandidates" to vectors,"selectedPath" to trace,"cp15EncodedOperands" to cp,
            "firstMmio" to mapOf("address" to hex(booleanWindow.address),"widthBytes" to 4,"value" to 1,"port" to booleanWindow.port,"catalogProvenance" to catalogWindow["provenance"],
                "implementation" to "components/soc-system-control/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/systemcontrol/SystemControlFactory.kt",
                "existingSupport" to "offset0/width4/value0..1 accepted iff booleanWrite=true; default false refuses; retention only, physical effects UNKNOWN"),
            "firstMissingMemory" to mapOf("instruction" to hex(BASE+0x384),"word" to hex(word(bytes,0x384)),"spLiteralOffset" to literal(bytes,0x1c4).first,
                "sp" to hex(sp),"writeStart" to hex(stackStart),"writeEndExclusive" to hex(sp),"wordStores" to listOf(mapOf("address" to hex(stackStart),"value" to hex(0)),mapOf("address" to hex(stackStart+4),"value" to hex(BASE+0x1cc))),
                "meaning" to "ROM-selected writable stack candidate; not proved SRAM type, physical size, reset contents or guest PA", "currentRamProfiles" to profiles),
            "postFrontierPreview" to listOf(row(bytes,0x388,"Not reached: LDR r0,literal@${hex(literal(bytes,0x388).first.toLong())} = ${hex(literal(bytes,0x388).second)}"),row(bytes,0x38c,"Not reached: LDR r0,[r0]; probable boot-choice source, input and physical strap identity UNKNOWN")),
            "requiredContext" to mapOf("mapping" to "UNKNOWN reset fetch base/alias; high capture view does not prove fetch mapping; low alias best fits seven nonreset branches",
                "cpu" to "Privileged A32 little-endian candidate with CP15 access; exact initial CPSR/mode/security, SCTLR/ACTLR/TTBR and MPIDR values UNKNOWN",
                "preDisableMmu" to "First ROM fetches, literal loads and boolean store precede SCTLR change; initial translation/endian state must resolve these addresses correctly",
                "registers" to "r0..r11 cleared before MPIDR; r12/lr cleared later; initial SP not used on selected path before explicit SP load; interrupts/reset release still UNKNOWN",
                "cpWrites" to "Encoded tuples listed; SCTLR read-modify-write clears immediate masks 0x1000,4,1,0x800; ACTLR OR0x40; remaining reset and maintenance semantics not qualified by this navigation",
                "ram" to "Only two stack words required at frontier; no whole SRAM extent or initial bytes inferred", "otherCores" to "core1/2/3 dispatch not traversed; release schedule not measured"),
            "nextAction" to mapOf("implementNow" to false,"boundedResearchPossible" to true,
                "action" to "Confirm ROM reset alias plus physical backing/permission for the ROM-selected [FFF2FBF8,FFF2FC00) stack through SoC reset-map evidence; retain existing boolean handler. If that is qualified, inspect exactly the next FFF4A000 read and selected boot-source value before designing any ROM execution profile.",
                "reason" to "ROM words provide a concrete minimum stack obligation, not a map/initial-value contract. Adding RAM or choosing strap replies now would introduce unqualified behavior."),
            "evidenceClasses" to listOf("FILE","SOURCE","HYPOTHESIS","UNKNOWN"),"guestExecuted" to false,"nativeExecuted" to false,"runtimeOrPermissionsChanged" to false,"firstOriginalMainFrame" to false)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 8 && args.toList().chunked(2).map { it[0] }.toSet() == setOf("--repo","--inputs","--output","--git"))
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        val repo = Path.of(options.getValue("--repo")).toRealPath()
        val inputs = Path.of(options.getValue("--inputs")).toRealPath()
        val output = Path.of(options.getValue("--output")).toAbsolutePath().normalize()
        require(output.startsWith(repo) && !Files.exists(output))
        fun git(at: Path,vararg argv: String): String {
            val p = ProcessBuilder(listOf(options.getValue("--git"),"-C",at.toString())+argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val bytes = p.inputStream.readAllBytes()
            require(p.waitFor(30,TimeUnit.SECONDS) && p.exitValue() == 0)
            return bytes.toString(Charsets.UTF_8).trimEnd()
        }
        val excludedOutput = repo.relativize(output.parent).toString().replace('\\','/')
        require(excludedOutput.startsWith("evidence/refactor/") && excludedOutput.count { it == '/' } == 2)
        // Only this exact study directory contains recorder output created after the clean prelaunch.
        fun sourceStatus() = git(repo,"status","--porcelain","--",".",":(exclude)$excludedOutput")
        require(sourceStatus().isEmpty()) { "Formal navigation requires clean source outside exact output directory" }
        val sourceCommit = git(repo,"rev-parse","HEAD")
        val inputCommit = git(inputs,"rev-parse","HEAD")
        require(git(inputs,"status","--porcelain","--",ARCHIVE,"firmware-data/gfx100s/2.13/hardware/gfx100s-20261112").isEmpty())
        val result = analyze(repo,inputs)
        require(git(repo,"rev-parse","HEAD") == sourceCommit && sourceStatus().isEmpty())
        Files.createDirectories(output.parent)
        Files.write(output,Formats.jsonBytes(result+mapOf("sourceCommit" to sourceCommit,"sourcePath" to SOURCE,"cleanExcludedOutputDirectory" to excludedOutput,"inputCommit" to inputCommit,"arguments" to args.toList(),"javaVersion" to System.getProperty("java.version"))),CREATE_NEW)
        println("BootROM bounded core0 navigation: existing boolean write; first missing stack [FFF2FBF8,FFF2FC00). No guest executed.")
    }
}
