import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins { kotlin("jvm") version "2.3.20" apply false }
val fujiTestForks = providers.gradleProperty("fujiTestForks").orElse("2").get().let { raw ->
    raw.toIntOrNull()?.takeIf { it in 1..8 } ?: error("fujiTestForks must be an integer in 1..8")
}
val platformArtifacts = configurations.create("platformArtifacts") { isTransitive = false }
dependencies {
    listOf("win", "linux", "mac", "mac-aarch64").forEach { platform ->
        listOf("base", "graphics", "controls", "swing").forEach { module ->
            add(platformArtifacts.name, "org.openjfx:javafx-$module:25.0.1:$platform")
        }
    }
}
tasks.register("verifyPlatformArtifacts") { doLast { platformArtifacts.files.sortedBy { it.name }.forEach { println(it.name) } } }
allprojects { group = "com.github.nyabanban.fuji.emulator"; version = "0.3.0" }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
    val normalizeManifestText = tasks.register("normalizeGeneratedManifestText") {
        doLast {
            val temporary=project.layout.buildDirectory.dir("tmp").get().asFile
            if(temporary.exists())temporary.walkTopDown().filter { it.isFile && it.name=="MANIFEST.MF" }.forEach { file ->
                val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
                file.writeText(text.removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n').trimEnd('\n')+"\n",Charsets.UTF_8)
            }
        }
    }
    tasks.withType<Jar>().configureEach { finalizedBy(normalizeManifestText) }

    dependencies {
        "testImplementation"(kotlin("test-junit5"))
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.12.2")
    }
    val normalizeReportText = tasks.register("normalizeTestReportText") {
        doLast {
            listOf(project.layout.buildDirectory.dir("reports/tests").get().asFile,
                project.layout.buildDirectory.dir("test-results").get().asFile).filter { it.exists() }.forEach { root ->
                root.walkTopDown().filter { it.isFile && it.extension in setOf("html","css","js","xml","json","txt") }.forEach { file ->
                    val old=file.readBytes()
                    val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(old)).toString()
                    val normalized=text.removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n').trimEnd('\n') + "\n"
                    val bytes=normalized.toByteArray(Charsets.UTF_8)
                    if(!old.contentEquals(bytes))file.writeBytes(bytes)
                }
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = fujiTestForks
        // Each worker keeps owner-thread/native execution serial and owns its log rotation files.
        systemProperty("junit.jupiter.execution.parallel.enabled", "false")
        systemProperty("fuji.log.dir", layout.buildDirectory.dir("test-logs/$name/worker-\${org.gradle.test.worker}").get().asFile.absolutePath)
        providers.gradleProperty("fujiForkProbeDirectory").orNull?.let { directory ->
            require(fujiTestForks >= 2) { "fujiForkProbeDirectory requires at least two test forks" }
            systemProperty("fuji.fork.probe.dir", rootProject.file(directory).absolutePath)
            if (project.path == ":runtime" && name == "test") {
                // Filter class files before worker dispatch; --tests alone does not guarantee distinct workers.
                include("**/ForkIsolationFirstTest.class", "**/ForkIsolationSecondTest.class")
            }
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging { events("passed", "skipped", "failed"); exceptionFormat = TestExceptionFormat.FULL }
        finalizedBy(normalizeReportText)
    }
    if (path.startsWith(":components:")) {
        dependencies {
            val jakartaInject = "jakarta.inject:jakarta.inject-api:2.0.1"
            "compileOnly"(project(":component-api"))
            "compileOnly"(jakartaInject)
            "testImplementation"(project(":component-api"))
            "testImplementation"(jakartaInject)
        }
        tasks.named<ProcessResources>("processResources") {
            dependsOn(":address-catalog:compileCatalog")
            from(rootProject.file("catalog/compiled/components/" + project.name + ".json")) {
                into("META-INF/fuji"); rename { "catalog.json" }
            }
        }
    }
    if (path.startsWith(":lenses:")) {
        dependencies {
            "compileOnly"(project(":component-api"))
            "testImplementation"(project(":component-api"))
        }
        tasks.named<Jar>("jar") {
            archiveFileName.set("${project.name}.jar")
        }
    }
}
tasks.register<Sync>("stageRuntime") {
    dependsOn(":desktop:installDist")
    from(project(":desktop").layout.buildDirectory.dir("install/desktop"))
    into(layout.buildDirectory.dir("emulator"))
    from("configurations/jvm") { into("config") }
    dependsOn(":address-catalog:compileCatalog")
    from("catalog/compiled/catalog.json") { into("config"); rename { "catalog.compiled.json" } }
    subprojects.filter { it.path.startsWith(":components:") }.forEach { p ->
        dependsOn(p.tasks.named("jar"))
        from(p.tasks.named("jar")) { into("compoments") }
        from(p.file("config")) { into("compoments/${p.name}") }
    }
    subprojects.filter { it.path.startsWith(":lenses:") }.forEach { p ->
        dependsOn(p.tasks.named("jar"))
        from(p.tasks.named("jar")) { into("lenses") }
    }
}
tasks.register<Zip>("distributionZip") {
    dependsOn("stageRuntime")
    from(layout.buildDirectory.dir("emulator"))
    archiveBaseName.set("fuji-emulator-jvm")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.register<Copy>("prepareRuntime") {
    dependsOn("stageRuntime")
    val runtimeHome = layout.projectDirectory.dir("local/runtime")
    from(layout.buildDirectory.dir("emulator")) { include("config/**", "compoments/**", "lenses/**") }
    into(runtimeHome)
    eachFile {
        if (!path.endsWith(".jar") && path != "config/catalog.compiled.json" && runtimeHome.file(path).asFile.exists()) exclude()
    }
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse(project.version.toString())
val factoryFlashOutDir = layout.buildDirectory.dir("release-factory-flash")
val bootRomOutDir = layout.buildDirectory.dir("release-rom")

val extractBootRom = tasks.register("extractBootRom") {
    group = "distribution"
    description = "Extract and SHA-256 verify pure 32 KiB SPX4 mask BootROM (boot-rom.bin) from GFX100S_DUMP.zip"
    val dumpZip = layout.projectDirectory.file("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
    inputs.file(dumpZip)
    outputs.dir(bootRomOutDir)
    doLast {
        val expectedSha = "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4"
        val outDir = bootRomOutDir.get().asFile
        outDir.mkdirs()
        val romBytes = java.util.zip.ZipFile(dumpZip.asFile).use { zf ->
            val entry = requireNotNull(zf.getEntry("files/boot-rom.bin")) {
                "Missing files/boot-rom.bin inside ${dumpZip.asFile.path}"
            }
            zf.getInputStream(entry).use { it.readBytes() }
        }
        require(romBytes.size == 32_768) {
            "Expected exact 32768-byte boot-rom.bin, got ${romBytes.size}"
        }
        val actualSha = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(romBytes),
        )
        require(actualSha == expectedSha) {
            "boot-rom.bin SHA-256 mismatch: expected=$expectedSha actual=$actualSha"
        }
        outDir.resolve("boot-rom.bin").writeBytes(romBytes)
        outDir.resolve("boot-rom.sha256").writeText("$actualSha  boot-rom.bin\n", Charsets.UTF_8)
    }
}

fun generateFactoryFlash(templateYaml: File, readmeFile: File, datFile: File, out: File) {
    out.deleteRecursively()
    out.mkdirs()

    val domainIds = listOf("dat-part12-lens-cache-ff", "dat-bank13-read508", "blank-ff-experiment", "dat-domain490-497-ff", "dat-range504-ff", "dat-secondary-read506-507-ff", "dat-tail-config508-ff")
    val domainOffsets = listOf(0x02fe0000L, 0x034e0000L, 0x03500000L, 0x03d40000L, 0x03f00000L, 0x03f40000L, 0x03f80000L)
    val domainLengths = listOf(0x00500000, 0x00020000, 0x00840000, 0x00100000, 0x00040000, 0x00040000, 0x00020000)
    val domainFiles = listOf("factory-part12-lens-cache.img", "factory-bank13-read508.img", "factory-config.img", "factory-domain490-497.img", "factory-domain504-505.img", "factory-domain506-507.img", "factory-domain508.img")

    fun parseHexOrDecInt(rawValue: String): Int {
        val v = rawValue.trim().trim('"', '\'')
        return if (v.startsWith("0x", true)) v.substring(2).toInt(16) else v.toInt()
    }

    data class DatSeedSpec(var datOffset: Int? = null, var length: Int? = null, var invertBytes: Boolean = false)
    val lines = templateYaml.readLines(Charsets.UTF_8)
    var globalFill = 0xff
    val domainFills = LinkedHashMap<String, Int>()
    val domainSeeds = LinkedHashMap<String, DatSeedSpec>()

    // First pass: parse top-level defaultFillByte and per-domain fill / datSeed metadata.
    var section = ""
    var curDomainId: String? = null
    var inDatSeed = false
    for (raw in lines) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val indent = raw.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
        if (indent == 0) {
            inDatSeed = false
            curDomainId = null
            when {
                trimmed.startsWith("defaultFillByte:") -> globalFill = parseHexOrDecInt(trimmed.substringAfter(":"))
                trimmed == "domains:" -> section = "domains"
                trimmed == "fields:" || trimmed == "fields: []" -> section = "fields"
                else -> section = trimmed.substringBefore(":")
            }
            continue
        }
        if (section != "domains") continue
        if (indent == 2 && trimmed.startsWith("- ")) {
            inDatSeed = false
            val kv = trimmed.removePrefix("- ").trim()
            curDomainId = if (kv.startsWith("id:")) kv.substringAfter(":").trim().trim('"', '\'') else null
            continue
        }
        val did = curDomainId ?: continue
        if (indent == 4) {
            inDatSeed = trimmed == "datSeed:"
            when {
                trimmed.startsWith("id:") -> curDomainId = trimmed.substringAfter(":").trim().trim('"', '\'')
                trimmed.startsWith("defaultFillByte:") -> domainFills[did] = parseHexOrDecInt(trimmed.substringAfter(":"))
                inDatSeed -> domainSeeds[did] = DatSeedSpec()
            }
        } else if (inDatSeed && indent >= 6) {
            val seed = domainSeeds.getOrPut(did) { DatSeedSpec() }
            when {
                trimmed.startsWith("datOffset:") -> seed.datOffset = parseHexOrDecInt(trimmed.substringAfter(":"))
                trimmed.startsWith("length:") -> seed.length = parseHexOrDecInt(trimmed.substringAfter(":"))
                trimmed.startsWith("invertBytes:") -> seed.invertBytes = trimmed.substringAfter(":").trim().toBooleanStrict()
            }
        }
    }

    val buffers = LinkedHashMap<String, ByteArray>()
    for (i in domainIds.indices) {
        val id = domainIds[i]
        val fill = domainFills[id] ?: globalFill
        require(fill in 0..0xff) { "Invalid fill byte $fill for factory flash domain $id" }
        buffers[id] = ByteArray(domainLengths[i]) { fill.toByte() }
    }

    // Apply any public GXUP0004.DAT factory seeds (e.g., official 0x72000-byte bank-14 table @ 0x000C0274).
    if (domainSeeds.isNotEmpty()) {
        require(datFile.isFile) { "Factory flash DAT seed requires ${datFile.path}" }
        val datBytes = datFile.readBytes()
        domainSeeds.forEach { (id, seed) ->
            val buf = buffers[id] ?: error("Unknown factory flash domain for datSeed: $id")
            val datOff = seed.datOffset ?: error("Missing datOffset for datSeed in $id")
            val len = seed.length ?: error("Missing length for datSeed in $id")
            require(datOff >= 0 && len > 0 && datOff + len <= datBytes.size && len <= buf.size) {
                "Factory flash datSeed out of bounds for $id (datOff=$datOff, len=$len)"
            }
            if (seed.invertBytes) {
                for (i in 0 until len) buf[i] = (datBytes[datOff + i].toInt() xor 0xff).toByte()
            } else {
                System.arraycopy(datBytes, datOff, buf, 0, len)
            }
        }
    }

    // Apply declarative field patches from tools/release/factory-flash-template.yaml (`fields:` section).
    var inFields = false
    var curRegion: String? = null
    var curOffset: Int? = null
    var curHex: String? = null
    var touchedBlockA = false
    var touchedBlockB = false
    fun flushPatch() {
        val r = curRegion ?: return
        val off = curOffset ?: error("Missing relativeOffset for factory flash field patch in $r")
        val hex = curHex ?: error("Missing hex for factory flash field patch in $r")
        val buf = buffers[r] ?: error("Unknown factory flash regionId: $r")
        val bytes = java.util.HexFormat.of().parseHex(hex.trim().removePrefix("0x").removePrefix("0X"))
        require(off >= 0 && off + bytes.size <= buf.size) { "Factory flash patch out of bounds in $r at $off" }
        System.arraycopy(bytes, 0, buf, off, bytes.size)
        if (r == "dat-domain490-497-ff") {
            if (off in 0x080000 until 0x081efc) touchedBlockA = true
            if (off in 0x0c0000 until 0x0c1efc) touchedBlockB = true
        }
        curRegion = null; curOffset = null; curHex = null
    }
    for (raw in lines) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        if (trimmed == "fields:" || trimmed == "fields: []") {
            inFields = trimmed == "fields:"
            continue
        }
        if (!inFields) continue
        if (!raw.startsWith(" ") && !raw.startsWith("-")) {
            flushPatch(); inFields = false; continue
        }
        if (trimmed.startsWith("- ")) flushPatch()
        val kv = trimmed.removePrefix("- ").trim()
        when {
            kv.startsWith("regionId:") -> curRegion = kv.substringAfter(":").trim().trim('"', '\'')
            kv.startsWith("relativeOffset:") -> curOffset = parseHexOrDecInt(kv.substringAfter(":"))
            kv.startsWith("hex:") -> curHex = kv.substringAfter(":").trim().trim('"', '\'')
        }
    }
    flushPatch()

    // Recompute exact 2D bit-parity ECC (`section4 @ 0x012A2D34`) if any BlockA/BlockB BKUP payload patch was applied.
    fun applyBkupParity2d(domainBuf: ByteArray, blockBase: Int) {
        val payloadLen = 0x1efc
        val rowCount = payloadLen / 4
        val rowPar = ByteArray(256)
        val colPar = ByteArray(4)
        for (row in 0 until rowCount) {
            val rowBase = blockBase + row * 4
            val rowBit = 1 shl (row and 7)
            val rowByteIdx = row ushr 3
            for (bit in 0 until 32) {
                val colByteIdx = bit ushr 3
                val colBit = 1 shl (bit and 7)
                if ((domainBuf[rowBase + colByteIdx].toInt() and colBit) != 0) {
                    rowPar[rowByteIdx] = (rowPar[rowByteIdx].toInt() xor rowBit).toByte()
                    colPar[colByteIdx] = (colPar[colByteIdx].toInt() xor colBit).toByte()
                }
            }
        }
        System.arraycopy(rowPar, 0, domainBuf, blockBase + 0x1efc, 256)
        System.arraycopy(colPar, 0, domainBuf, blockBase + 0x1ffc, 4)
    }
    if (touchedBlockA) applyBkupParity2d(buffers.getValue("dat-domain490-497-ff"), 0x080000)
    if (touchedBlockB) applyBkupParity2d(buffers.getValue("dat-domain490-497-ff"), 0x0c0000)

    val hashes = LinkedHashMap<String, String>()
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val hexFmt = java.util.HexFormat.of()
    for (i in domainIds.indices) {
        val id = domainIds[i]
        val bytes = buffers.getValue(id)
        out.resolve(domainFiles[i]).writeBytes(bytes)
        md.reset()
        hashes[id] = hexFmt.formatHex(md.digest(bytes))
    }

    val regionsJson = buildString {
        append("{\n")
        append("  \"schema\": 1,\n")
        append("  \"profile\": \"explicit-readonly-regions-experimental-v1\",\n")
        append("  \"regions\": [\n")
        for (i in domainIds.indices) {
            val comma = if (i + 1 < domainIds.size) "," else ""
            append("    {\"id\": \"${domainIds[i]}\", \"offset\": ${domainOffsets[i]}, \"length\": ${domainLengths[i]}, \"file\": \"${domainFiles[i]}\", \"sha256\": \"${hashes.getValue(domainIds[i])}\"}$comma\n")
        }
        append("  ]\n")
        append("}\n")
    }
    out.resolve("regions.json").writeText(regionsJson, Charsets.UTF_8)

    val mutableJson = buildString {
        append("{\n")
        append("  \"schema\": 1,\n")
        append("  \"profile\": \"explicit-disjoint-copy-on-write-program-experimental-v3\",\n")
        append("  \"primaryRegionId\": \"dat-domain490-497-ff\",\n")
        append("  \"regions\": [\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-part12-lens-cache-ff\", \"file\": \"cow-part12-lens-cache.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-part12-lens-cache-ff")}\", \"writableOffset\": ${0x02fe0000L}, \"writableLength\": ${0x00500000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256},\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-bank13-read508\", \"file\": \"cow-bank13.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-bank13-read508")}\", \"writableOffset\": ${0x034e0000L}, \"writableLength\": ${0x00020000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256},\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-domain490-497-ff\", \"file\": \"cow-domain490-497.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-domain490-497-ff")}\", \"writableOffset\": ${0x03d40000L}, \"writableLength\": ${0x00100000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256},\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-range504-ff\", \"file\": \"cow-domain504-505.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-range504-ff")}\", \"writableOffset\": ${0x03f00000L}, \"writableLength\": ${0x00040000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256},\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-secondary-read506-507-ff\", \"file\": \"cow-domain506-507.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-secondary-read506-507-ff")}\", \"writableOffset\": ${0x03f40000L}, \"writableLength\": ${0x00040000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256},\n")
        append("    {\"schema\": 1, \"profile\": \"explicit-copy-on-write-program-experimental-v2\", \"regionId\": \"dat-tail-config508-ff\", \"file\": \"cow-domain508.img\", \"mode\": \"create\", \"sha256\": \"${hashes.getValue("dat-tail-config508-ff")}\", \"writableOffset\": ${0x03f80000L}, \"writableLength\": ${0x00020000L}, \"programLaw\": \"strict-one-to-zero-no-page-wrap-experimental-v1\", \"programPageBytes\": 256}\n")
        append("  ]\n")
        append("}\n")
    }
    out.resolve("mutable.json").writeText(mutableJson, Charsets.UTF_8)

    val flashImageYaml = "# Unified 64 MiB QSPI NOR Flash image (pre-flashed stock GFX100S 2.13 + synthetic factory calibration).\n" +
        "imageProfile: \"raw-nor-image-v1\"\n" +
        "imageFile: \"nor-flash.bin\"\n" +
        "writable: true\n"
    out.resolve("flash-image.yaml").writeText(flashImageYaml, Charsets.UTF_8)

    // Build deterministic, privacy-free 64 MiB unified NOR Flash image (`nor-flash-2.13.bin`):
    // [0x0000_0000, seg0Len) = XOR-decoded public GXUP0004.DAT seg0
    // [seg0Len, 0x02FE_0000) = 0xFF erased NOR state
    // [0x02FE_0000, 0x0400_0000) = synthetic privacy-free factory calibration & settings banks
    require(datFile.isFile) { "Unified NOR Flash generator requires ${datFile.path}" }
    val rawDat = datFile.readBytes()
    require(rawDat.size > 0x274) { "Invalid GXUP0004.DAT size: ${rawDat.size}" }
    val datHdr = java.nio.ByteBuffer.wrap(rawDat, 0, 0x274).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val datVer = datHdr.getInt(0)
    val seg0Len = datHdr.getInt(0x24c).toLong() and 0xffffffffL
    val seg0StoredChecksum = datHdr.getInt(0x20c).toLong() and 0xffffffffL
    require(datVer == 6 && seg0Len in 1L..0x02fe0000L && 0x274L + seg0Len <= rawDat.size.toLong()) {
        "Invalid GXUP0004.DAT header/seg0: ver=$datVer seg0Len=0x${seg0Len.toString(16)}"
    }
    var seg0Sum = 0L
    val norBytes = ByteArray(64 * 1024 * 1024) { 0xff.toByte() }
    val seg0Int = seg0Len.toInt()
    for (i in 0 until seg0Int) {
        val decoded = (rawDat[0x274 + i].toInt() xor 0xff) and 0xff
        norBytes[i] = decoded.toByte()
        seg0Sum = (seg0Sum + decoded) and 0xffffffffL
    }
    val seg0ComputedChecksum = seg0Sum.inv() and 0xffffffffL
    require(seg0StoredChecksum == seg0ComputedChecksum) {
        "GXUP0004.DAT seg0 checksum mismatch: stored=0x${seg0StoredChecksum.toString(16)} computed=0x${seg0ComputedChecksum.toString(16)}"
    }
    for (i in domainIds.indices) {
        val bytes = buffers.getValue(domainIds[i])
        System.arraycopy(bytes, 0, norBytes, domainOffsets[i].toInt(), bytes.size)
    }
    out.resolve("nor-flash-2.13.bin").writeBytes(norBytes)
    md.reset()
    val norSha = hexFmt.formatHex(md.digest(norBytes))
    out.resolve("nor-flash-2.13.sha256").writeText("$norSha  nor-flash-2.13.bin\n", Charsets.UTF_8)

    templateYaml.copyTo(out.resolve("factory-flash-template.yaml"), overwrite = true)
    readmeFile.copyTo(out.resolve("README.md"), overwrite = true)
}

val generateFactoryFlashTemplate = tasks.register("generateFactoryFlashTemplate") {
    group = "distribution"
    description = "Generate synthetic factory-default GFX100S 2.13 Flash template images, regions.json, mutable.json, and flash-image.yaml"
    val templateYaml = layout.projectDirectory.file("tools/release/factory-flash-template.yaml")
    val readmeFile = layout.projectDirectory.file("tools/release/factory-flash-README.md")
    val datFile = layout.projectDirectory.file("firmware-data/gfx100s/2.13/GXUP0004.DAT")
    inputs.file(templateYaml)
    inputs.file(readmeFile)
    inputs.file(datFile)
    outputs.dir(factoryFlashOutDir)
    doLast {
        generateFactoryFlash(templateYaml.asFile, readmeFile.asFile, datFile.asFile, factoryFlashOutDir.get().asFile)
    }
}

val releaseMetadataFile = layout.buildDirectory.file("release-metadata/gfx100s-213-release.yaml")
val generateReleaseFirmwareMetadata = tasks.register<JavaExec>("generateReleaseFirmwareMetadata") {
    group = "distribution"
    description = "Export profile-matched GFX100S 2.13 release firmware metadata from the compiled address catalog"
    dependsOn(":address-catalog:compileCatalog", ":address-catalog:classes")
    val catalogJson = layout.projectDirectory.file("catalog/compiled/catalog.json")
    inputs.file(catalogJson)
    outputs.file(releaseMetadataFile)
    classpath = project(":address-catalog").extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass.set("com.github.nyabanban.fuji.emulator.catalog.CatalogCompilerKt")
    args(
        "--export-firmware-metadata",
        catalogJson.asFile.absolutePath,
        releaseMetadataFile.get().asFile.absolutePath,
        "soc-sd-host=gfx100s-sd-host-uhs-phy-experimental-v9",
        "soc-vglib-init=lcdc-functional-experimental-v1",
        "soc-isp=isp-banks-movie-capture-v3",
        "soc-jpeg=soc-jpeg-codec-experimental-v2",
        "soc-panel=panel-init-repeatable-a8000-experimental-v2",
        "soc-uart=pl011-multi-uart-experimental-v1",
        "soc-system-control=retained-banks-usb-dwc3-experimental-v2",
    )
}

val releaseBootRomMetadataFile = layout.buildDirectory.file("release-metadata/gfx100s-213-bootrom-release.yaml")
val generateReleaseBootRomFirmwareMetadata = tasks.register<JavaExec>("generateReleaseBootRomFirmwareMetadata") {
    group = "distribution"
    description = "Export profile-matched GFX100S 2.13 BootROM cold-boot release firmware metadata from the compiled address catalog"
    dependsOn(":address-catalog:compileCatalog", ":address-catalog:classes")
    val catalogJson = layout.projectDirectory.file("catalog/compiled/catalog.json")
    inputs.file(catalogJson)
    outputs.file(releaseBootRomMetadataFile)
    classpath = project(":address-catalog").extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass.set("com.github.nyabanban.fuji.emulator.catalog.CatalogCompilerKt")
    args(
        "--export-firmware-metadata",
        catalogJson.asFile.absolutePath,
        releaseBootRomMetadataFile.get().asFile.absolutePath,
        "--boot-rom",
        "soc-sd-host=gfx100s-sd-host-uhs-phy-experimental-v9",
        "soc-vglib-init=lcdc-functional-experimental-v1",
        "soc-isp=isp-banks-movie-capture-v3",
        "soc-jpeg=soc-jpeg-codec-experimental-v2",
        "soc-panel=panel-init-repeatable-a8000-experimental-v2",
        "soc-uart=pl011-multi-uart-experimental-v1",
        "soc-system-control=retained-banks-usb-dwc3-experimental-v2",
    )
}

fun buildFujiDcfExifJpeg(rawJpeg: ByteArray): ByteArray {
    require(rawJpeg.size >= 4 && rawJpeg[0] == 0xFF.toByte() && rawJpeg[1] == 0xD8.toByte()) {
        "Expected JPEG SOI marker"
    }
    val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(rawJpeg))
        ?: error("Failed to decode sample JPEG for DCF EXIF wrapping")
    val thumbW = 160
    val thumbH = 120
    val thumb = java.awt.image.BufferedImage(thumbW, thumbH, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = thumb.createGraphics()
    try {
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(img, 0, 0, thumbW, thumbH, null)
    } finally {
        g.dispose()
    }
    val thumbOut = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(thumb, "jpg", thumbOut)
    val thumbBytes = thumbOut.toByteArray()

    // Strip leading JFIF APP0 segment if present so APP1 Exif immediately follows SOI (DCF 2.0)
    var bodyOffset = 2
    if (bodyOffset + 4 <= rawJpeg.size && rawJpeg[bodyOffset] == 0xFF.toByte() && rawJpeg[bodyOffset + 1] == 0xE0.toByte()) {
        val segLen = ((rawJpeg[bodyOffset + 2].toInt() and 0xFF) shl 8) or (rawJpeg[bodyOffset + 3].toInt() and 0xFF)
        if (bodyOffset + 2 + segLen <= rawJpeg.size) {
            bodyOffset += 2 + segLen
        }
    }

    // Build little-endian TIFF Exif 2.32 payload: Header + IFD0 + ExifIFD + InteropIFD + IFD1 + Data + Thumbnail
    val tiff = java.io.ByteArrayOutputStream()
    fun putU16(buf: java.io.ByteArrayOutputStream, v: Int) {
        buf.write(v and 0xFF); buf.write((v ushr 8) and 0xFF)
    }
    fun putU32(buf: java.io.ByteArrayOutputStream, v: Int) {
        buf.write(v and 0xFF); buf.write((v ushr 8) and 0xFF)
        buf.write((v ushr 16) and 0xFF); buf.write((v ushr 24) and 0xFF)
    }
    data class IfdEntry(val tag: Int, val type: Int, val count: Int, val valueOrData: Any)

    val makeBytes = "FUJIFILM\u0000".toByteArray(Charsets.US_ASCII)
    val modelBytes = "GFX100S\u0000".toByteArray(Charsets.US_ASCII)
    val swBytes = "Digital Camera GFX100S Ver2.13\u0000".toByteArray(Charsets.US_ASCII)
    val dtBytes = "2026:11:22 12:00:00\u0000".toByteArray(Charsets.US_ASCII)
    val res72 = byteArrayOf(72, 0, 0, 0, 1, 0, 0, 0)

    val interopEntries = listOf(
        IfdEntry(0x0001, 2, 4, byteArrayOf('R'.code.toByte(), '9'.code.toByte(), '8'.code.toByte(), 0)),
        IfdEntry(0x0002, 7, 4, "0100".toByteArray(Charsets.US_ASCII)),
    )
    val exifEntries = listOf(
        IfdEntry(0x9000, 7, 4, "0232".toByteArray(Charsets.US_ASCII)),
        IfdEntry(0x9003, 2, dtBytes.size, dtBytes),
        IfdEntry(0x9004, 2, dtBytes.size, dtBytes),
        IfdEntry(0x9101, 7, 4, byteArrayOf(1, 2, 3, 0)),
        IfdEntry(0xA000, 7, 4, "0100".toByteArray(Charsets.US_ASCII)),
        IfdEntry(0xA001, 3, 1, 1),
        IfdEntry(0xA002, 4, 1, img.width),
        IfdEntry(0xA003, 4, 1, img.height),
        IfdEntry(0xA005, 4, 1, -1), // placeholder for InteropIFD offset
    )
    val ifd0Entries = listOf(
        IfdEntry(0x010F, 2, makeBytes.size, makeBytes),
        IfdEntry(0x0110, 2, modelBytes.size, modelBytes),
        IfdEntry(0x0112, 3, 1, 1),
        IfdEntry(0x011A, 5, 1, res72),
        IfdEntry(0x011B, 5, 1, res72),
        IfdEntry(0x0128, 3, 1, 2),
        IfdEntry(0x0131, 2, swBytes.size, swBytes),
        IfdEntry(0x0132, 2, dtBytes.size, dtBytes),
        IfdEntry(0x0213, 3, 1, 2),
        IfdEntry(0x8769, 4, 1, -1), // placeholder for ExifIFD offset
    )
    val ifd1Entries = listOf(
        IfdEntry(0x0103, 3, 1, 6), // JPEG compression
        IfdEntry(0x0112, 3, 1, 1),
        IfdEntry(0x011A, 5, 1, res72),
        IfdEntry(0x011B, 5, 1, res72),
        IfdEntry(0x0128, 3, 1, 2),
        IfdEntry(0x0201, 4, 1, -1), // placeholder for thumbnail offset
        IfdEntry(0x0202, 4, 1, thumbBytes.size),
    )

    fun ifdByteSize(entries: List<IfdEntry>): Int = 2 + entries.size * 12 + 4
    val ifd0Off = 8
    val exifOff = ifd0Off + ifdByteSize(ifd0Entries)
    val interopOff = exifOff + ifdByteSize(exifEntries)
    val ifd1Off = interopOff + ifdByteSize(interopEntries)
    val dataPoolStart = ifd1Off + ifdByteSize(ifd1Entries)
    val dataPool = java.io.ByteArrayOutputStream()

    fun emitIfd(entries: List<IfdEntry>, nextIfdOff: Int, overrides: Map<Int, Int> = emptyMap()) {
        putU16(tiff, entries.size)
        for (e in entries) {
            putU16(tiff, e.tag)
            putU16(tiff, e.type)
            putU32(tiff, e.count)
            val ov = overrides[e.tag]
            if (ov != null) {
                putU32(tiff, ov)
            } else {
                when (val v = e.valueOrData) {
                    is Int -> putU32(tiff, v)
                    is ByteArray -> {
                        if (v.size <= 4) {
                            val padded = v.copyOf(4)
                            tiff.write(padded)
                        } else {
                            val ptr = dataPoolStart + dataPool.size()
                            putU32(tiff, ptr)
                            dataPool.write(v)
                            if (v.size % 2 != 0) dataPool.write(0)
                        }
                    }
                    else -> error("Unsupported IFD value")
                }
            }
        }
        putU32(tiff, nextIfdOff)
    }

    // Reserve dataPool offsets first by writing IFDs into TIFF stream, then compute thumb offset
    // Pre-calculate dataPool bytes so thumbOffset is known when emitting IFD1
    val preDataPoolSize = listOf(ifd0Entries, exifEntries, interopEntries, ifd1Entries).sumOf { list ->
        list.sumOf { e ->
            val v = e.valueOrData
            if (v is ByteArray && v.size > 4) ((v.size + 1) and 1.inv()) else 0
        }
    }
    val thumbOffset = dataPoolStart + preDataPoolSize

    // TIFF Header (little-endian "II", magic 42, IFD0 @ 8)
    tiff.write('I'.code); tiff.write('I'.code); putU16(tiff, 42); putU32(tiff, ifd0Off)
    emitIfd(ifd0Entries, nextIfdOff = ifd1Off, overrides = mapOf(0x8769 to exifOff))
    emitIfd(exifEntries, nextIfdOff = 0, overrides = mapOf(0xA005 to interopOff))
    emitIfd(interopEntries, nextIfdOff = 0)
    emitIfd(ifd1Entries, nextIfdOff = 0, overrides = mapOf(0x0201 to thumbOffset))
    require(dataPoolStart + dataPool.size() == thumbOffset)
    tiff.write(dataPool.toByteArray())
    tiff.write(thumbBytes)

    val app1Payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.toByteArray()
    val app1Len = app1Payload.size + 2
    require(app1Len <= 0xFFFF) { "APP1 EXIF segment exceeds 64 KiB ($app1Len B)" }
    val out = java.io.ByteArrayOutputStream(2 + 2 + app1Len + (rawJpeg.size - bodyOffset))
    out.write(0xFF); out.write(0xD8)
    out.write(0xFF); out.write(0xE1)
    out.write((app1Len ushr 8) and 0xFF); out.write(app1Len and 0xFF)
    out.write(app1Payload)
    out.write(rawJpeg, bodyOffset, rawJpeg.size - bodyOffset)
    return out.toByteArray()
}

fun initializeReleaseSdCardDirectories(releaseStageDir: File, sampleJpegFile: File) {
    val sdRoot = releaseStageDir.resolve("runtime/compoments/sdcard-reader")
    // Authentic GFX100S 2.13 FFDB/FFXFER.DAT v2 header (512 B >= 48 B checked at 0x025821fc: "00000002\0\0" + zero indices)
    val xferPayload = ByteArray(512)
    "00000002".toByteArray(Charsets.US_ASCII).copyInto(xferPayload, 0)
    val samplePhoto = buildFujiDcfExifJpeg(sampleJpegFile.readBytes())
    for (slot in 1..2) {
        val cardDir = sdRoot.resolve("sd-card-slot-$slot")
        val ffdbDir = cardDir.resolve("FFDB")
        val fujiDir = cardDir.resolve("DCIM/100_FUJI")
        ffdbDir.mkdirs()
        fujiDir.mkdirs()
        ffdbDir.resolve("FFXFER.DAT").writeBytes(xferPayload)
        if (slot == 1) {
            fujiDir.resolve("DSCF0001.JPG").writeBytes(samplePhoto)
        }
    }
}

fun releaseComponentOverridePaths(): List<String> {
    val dir = layout.projectDirectory.dir("tools/release/component-configs").asFile
    return if (dir.isDirectory) {
        dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    } else emptyList()
}

fun resolveReleaseNative(
    candidates: List<File>,
    label: String,
    buildHint: String = "Build the native library with `./gradlew :cpu-dynarmic:buildDynarmicNative` first.",
): File =
    candidates.firstOrNull { it.isFile }
        ?: error("Missing $label native library; checked ${candidates.map { it.path }}. $buildHint")

fun resolveReleaseDynarmicCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_DYNARMIC_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/dynarmic-v1/build-linux/libfuji_dynarmic.so"),
    rootProject.file(".tools/dynarmic-v1/build/libfuji_dynarmic.so"),
    File("/opt/fuji-cache/dynarmic-v1-linux/libfuji_dynarmic.so"),
    File("/opt/fuji-cache/dynarmic-v1/libfuji_dynarmic.so"),
)

fun resolveReleaseDynarmicCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_DYNARMIC_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_DYNARMIC_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/dynarmic-v1/build-win64/fuji_dynarmic.dll"),
    rootProject.file(".tools/dynarmic-v1-win64/build/fuji_dynarmic.dll"),
    File("/opt/fuji-cache/dynarmic-v1-win64/fuji_dynarmic.dll"),
    File("/opt/fuji-cache/dynarmic-v1/fuji_dynarmic.dll"),
)

fun resolveReleaseAudioCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_AUDIO_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/audio-bridge/build-linux/libfuji_audio.so"),
    rootProject.file(".tools/audio-bridge/build/libfuji_audio.so"),
    File("/opt/fuji-cache/audio-bridge-linux/libfuji_audio.so"),
    File("/opt/fuji-cache/audio-bridge/libfuji_audio.so"),
)

fun resolveReleaseAudioCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_AUDIO_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_AUDIO_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/audio-bridge/build-win64/fuji_audio.dll"),
    rootProject.file(".tools/audio-bridge/build/fuji_audio.dll"),
    File("/opt/fuji-cache/audio-bridge-win64/fuji_audio.dll"),
    File("/opt/fuji-cache/audio-bridge/fuji_audio.dll"),
)

fun resolveReleaseGamepadCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_GAMEPAD_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/gamepad-bridge/build-linux/libfuji_gamepad.so"),
    rootProject.file(".tools/gamepad-bridge/build/libfuji_gamepad.so"),
    File("/opt/fuji-cache/gamepad-bridge/build-linux/libfuji_gamepad.so"),
    File("/opt/fuji-cache/gamepad-bridge-linux/libfuji_gamepad.so"),
    File("/opt/fuji-cache/gamepad-bridge/libfuji_gamepad.so"),
)

fun resolveReleaseGamepadCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_GAMEPAD_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_GAMEPAD_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/gamepad-bridge/build-win64/fuji_gamepad.dll"),
    rootProject.file(".tools/gamepad-bridge/build/fuji_gamepad.dll"),
    File("/opt/fuji-cache/gamepad-bridge/build-win64/fuji_gamepad.dll"),
    File("/opt/fuji-cache/gamepad-bridge-win64/fuji_gamepad.dll"),
    File("/opt/fuji-cache/gamepad-bridge/fuji_gamepad.dll"),
)

fun resolveReleaseRenderCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_RENDER_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/render-bridge/build-linux/libfuji_render.so"),
    rootProject.file(".tools/render-bridge/build/libfuji_render.so"),
    File("/opt/fuji-cache/render-bridge/build-linux/libfuji_render.so"),
    File("/opt/fuji-cache/render-bridge-linux/libfuji_render.so"),
    File("/opt/fuji-cache/render-bridge/libfuji_render.so"),
)

fun resolveReleaseRenderCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_RENDER_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_RENDER_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/render-bridge/build-win64/fuji_render.dll"),
    rootProject.file(".tools/render-bridge/build/fuji_render.dll"),
    File("/opt/fuji-cache/render-bridge/build-win64/fuji_render.dll"),
    File("/opt/fuji-cache/render-bridge-win64/fuji_render.dll"),
    File("/opt/fuji-cache/render-bridge/fuji_render.dll"),
)

fun resolveReleaseVulkanOpticsCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_VULKAN_OPTICS_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/vulkan-optics/build-linux/libfuji_vulkan_optics.so"),
    rootProject.file(".tools/vulkan-optics/build/libfuji_vulkan_optics.so"),
    File("/opt/fuji-cache/vulkan-optics/build-linux/libfuji_vulkan_optics.so"),
    File("/opt/fuji-cache/vulkan-optics-linux/libfuji_vulkan_optics.so"),
    File("/opt/fuji-cache/vulkan-optics/libfuji_vulkan_optics.so"),
)

fun resolveReleaseVulkanOpticsCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_VULKAN_OPTICS_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_VULKAN_OPTICS_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/vulkan-optics/build-win64/fuji_vulkan_optics.dll"),
    rootProject.file(".tools/vulkan-optics/build/fuji_vulkan_optics.dll"),
    File("/opt/fuji-cache/vulkan-optics/build-win64/fuji_vulkan_optics.dll"),
    File("/opt/fuji-cache/vulkan-optics-win64/fuji_vulkan_optics.dll"),
    File("/opt/fuji-cache/vulkan-optics/fuji_vulkan_optics.dll"),
)

fun resolveReleaseIspSimdCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_ISP_SIMD_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/isp-simd/build-linux/libfuji_isp_simd.so"),
    rootProject.file(".tools/isp-simd/build/libfuji_isp_simd.so"),
    File("/opt/fuji-cache/isp-simd/build-linux/libfuji_isp_simd.so"),
    File("/opt/fuji-cache/isp-simd-linux/libfuji_isp_simd.so"),
    File("/opt/fuji-cache/isp-simd/libfuji_isp_simd.so"),
)

fun resolveReleaseIspSimdCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_ISP_SIMD_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_ISP_SIMD_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/isp-simd/build-win64/fuji_isp_simd.dll"),
    rootProject.file(".tools/isp-simd/build/fuji_isp_simd.dll"),
    File("/opt/fuji-cache/isp-simd/build-win64/fuji_isp_simd.dll"),
    File("/opt/fuji-cache/isp-simd-win64/fuji_isp_simd.dll"),
    File("/opt/fuji-cache/isp-simd/fuji_isp_simd.dll"),
)

fun resolveReleaseCodecCandidatesLinux(): List<File> = listOfNotNull(
    System.getenv("FUJI_CODEC_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".so") }?.let(::File),
    rootProject.file(".tools/codec-bridge/build-linux/libfuji_codec.so"),
    rootProject.file(".tools/codec-bridge/build/libfuji_codec.so"),
    File("/opt/fuji-cache/codec-bridge/build-linux/libfuji_codec.so"),
    File("/opt/fuji-cache/codec-bridge-linux/libfuji_codec.so"),
    File("/opt/fuji-cache/codec-bridge/libfuji_codec.so"),
)

fun resolveReleaseCodecCandidatesWindows(): List<File> = listOfNotNull(
    System.getenv("FUJI_WIN64_CODEC_DLL")?.takeIf { it.isNotBlank() }?.let(::File),
    System.getenv("FUJI_CODEC_LIBRARY")?.takeIf { it.isNotBlank() && it.endsWith(".dll") }?.let(::File),
    rootProject.file(".tools/codec-bridge/build-win64/fuji_codec.dll"),
    rootProject.file(".tools/codec-bridge/build/fuji_codec.dll"),
    File("/opt/fuji-cache/codec-bridge/build-win64/fuji_codec.dll"),
    File("/opt/fuji-cache/codec-bridge-win64/fuji_codec.dll"),
    File("/opt/fuji-cache/codec-bridge/fuji_codec.dll"),
)

val jlinkModulesFile = layout.projectDirectory.file("tools/release/jpackage/modules.txt")
val embedJlinkRuntime = providers.gradleProperty("fujiEmbedJlink").map { it.toBoolean() }.orElse(true)
val requireWindowsJlink = providers.gradleProperty("fujiRequireWindowsJlink").map { it.toBoolean() }.orElse(false)
val bundleStockBins = providers.gradleProperty("fujiBundleStockBins").map { it.equals("true", ignoreCase = true) }.orElse(false)
val jlinkLinuxOutDir = layout.buildDirectory.dir("jlink-runtime/linux-x86_64")
val jlinkWindowsOutDir = layout.buildDirectory.dir("jlink-runtime/windows-x86_64")
val jpackageLinuxOutDir = layout.buildDirectory.dir("jpackage/linux-x86_64")
val jpackageWindowsOutDir = layout.buildDirectory.dir("jpackage/windows-x86_64")
val winLauncherOutFile = layout.buildDirectory.file("jpackage/win-launcher/fuji-emulator-gfx100s.exe")
val aotCacheLinuxOutFile = layout.buildDirectory.file("aot-cache/linux-x86_64/fuji-emulator.aot")

fun readJlinkModulesCsv(modulesFile: File): String =
    modulesFile.readLines(Charsets.UTF_8)
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")

fun resolveActiveJdkTool(toolName: String): File {
    val isWin = System.getProperty("os.name").lowercase().contains("win")
    val exe = if (isWin && !toolName.endsWith(".exe")) "$toolName.exe" else toolName
    val javaHome = File(System.getProperty("java.home"))
    return listOf(javaHome.resolve("bin/$exe"), javaHome.parentFile?.resolve("bin/$exe"))
        .filterNotNull()
        .firstOrNull { it.isFile }
        ?: error("JDK 25 tool '$exe' not found under java.home=${javaHome.path}")
}

fun resolveJmodsHome(candidate: File?): File? {
    if (candidate == null || !candidate.isDirectory) return null
    if (candidate.resolve("jmods/java.base.jmod").isFile) return candidate
    return candidate.listFiles()
        ?.filter { it.isDirectory && it.resolve("jmods/java.base.jmod").isFile }
        ?.sortedBy { it.name }
        ?.firstOrNull()
}

fun resolveWin64JdkHome(): File? =
    listOfNotNull(
        System.getenv("FUJI_WIN64_JDK_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
        providers.gradleProperty("fujiWin64JdkHome").orNull?.takeIf { it.isNotBlank() }?.let(::File),
        rootProject.file(".tools/jdk-25-win64"),
        File("/opt/fuji-cache/jdk-25-win64"),
    ).firstNotNullOfOrNull(::resolveJmodsHome)

fun jpackageNumericVersion(rawVersion: String): String {
    val cleaned = rawVersion.trim().removePrefix("v")
    val match = Regex("^([1-9]\\d*(?:\\.\\d+){0,2})").find(cleaned)
    return match?.groupValues?.get(1) ?: "1.0.0"
}

fun runCheckedProcess(command: List<String>, workDir: File = rootProject.projectDir) {
    val proc = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val code = proc.waitFor()
    if (code != 0) {
        error("Command failed (exit=$code): ${command.joinToString(" ")}\n$output")
    }
    if (output.isNotBlank()) println(output.trimEnd())
}

val buildJlinkRuntimeLinux = tasks.register("buildJlinkRuntimeLinux") {
    group = "distribution"
    description = "Build trimmed self-contained JDK 25 Linux x86_64 JRE via jlink (supports both classic jmods and JDK 25 JEP 493 run-time linking)"
    inputs.file(jlinkModulesFile)
    inputs.property("compressLevel", "zip-9")
    outputs.dir(jlinkLinuxOutDir)
    outputs.upToDateWhen { jlinkLinuxOutDir.get().asFile.resolve("bin/java").isFile }
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        val isNativeLinux = osName.contains("linux")
        val jmodsHome = if (isNativeLinux) {
            resolveJmodsHome(File(System.getProperty("java.home")))
        } else {
            listOfNotNull(
                System.getenv("FUJI_LINUX_JDK_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
                rootProject.file(".tools/jdk-25-linux"),
                File("/opt/fuji-cache/jdk-25-linux"),
            ).firstNotNullOfOrNull(::resolveJmodsHome)
        }
        val outDir = jlinkLinuxOutDir.get().asFile
        if (!isNativeLinux && jmodsHome == null) {
            error("Mandatory Linux JDK 25 jmods not found on host ($osName); set FUJI_LINUX_JDK_HOME or cache .tools/jdk-25-linux.")
        }
        val jlink = resolveActiveJdkTool("jlink")
        val modulesCsv = readJlinkModulesCsv(jlinkModulesFile.asFile)
        outDir.deleteRecursively()
        outDir.parentFile.mkdirs()
        val cmd = mutableListOf(jlink.absolutePath)
        if (jmodsHome != null) {
            cmd += listOf("--module-path", jmodsHome.resolve("jmods").absolutePath)
        }
        cmd += listOf(
            "--add-modules", modulesCsv,
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress=zip-9",
            "--output", outDir.absolutePath,
        )
        runCheckedProcess(cmd)
        require(outDir.resolve("bin/java").isFile) {
            "Linux jlink runtime did not produce bin/java at ${outDir.path}"
        }
        println("[buildJlinkRuntimeLinux] Built trimmed Linux JDK 25 runtime at ${outDir.path}")
    }
}

val buildJlinkRuntimeWindows = tasks.register("buildJlinkRuntimeWindows") {
    group = "distribution"
    description = "Build trimmed self-contained JDK 25 Windows x86_64 JRE via jlink (native on Windows or cross-jlink on Linux)"
    inputs.file(jlinkModulesFile)
    inputs.property("compressLevel", "zip-9")
    inputs.property("requireWindowsJlink", requireWindowsJlink)
    outputs.dir(jlinkWindowsOutDir)
    outputs.upToDateWhen { !requireWindowsJlink.get() || jlinkWindowsOutDir.get().asFile.resolve("bin/java.exe").isFile }
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        val isNativeWin = osName.contains("win")
        var jmodsHome = if (isNativeWin) {
            resolveJmodsHome(File(System.getProperty("java.home")))
        } else {
            resolveWin64JdkHome()
        }
        if (jmodsHome == null && !isNativeWin && System.getenv("FUJI_DOWNLOAD_WIN64_JDK") == "1") {
            runCheckedProcess(listOf("sh", rootProject.file("tools/release/jpackage/fetch-win64-jdk.sh").absolutePath))
            jmodsHome = resolveWin64JdkHome()
        }
        val outDir = jlinkWindowsOutDir.get().asFile
        if (!isNativeWin && jmodsHome == null) {
            if (requireWindowsJlink.get()) {
                error("Mandatory Windows JDK 25 jmods not found (-PfujiRequireWindowsJlink=true). Run tools/release/jpackage/fetch-win64-jdk.sh or set FUJI_WIN64_JDK_HOME.")
            }
            outDir.deleteRecursively()
            outDir.mkdirs()
            println("[buildJlinkRuntimeWindows] Windows JDK 25 jmods not cached locally; skipping embedded Windows JRE (pass -PfujiRequireWindowsJlink=true in release CI after running tools/release/jpackage/fetch-win64-jdk.sh).")
            return@doLast
        }
        val jlink = resolveActiveJdkTool("jlink")
        val modulesCsv = readJlinkModulesCsv(jlinkModulesFile.asFile)
        outDir.deleteRecursively()
        outDir.parentFile.mkdirs()
        val cmd = mutableListOf(jlink.absolutePath)
        if (jmodsHome != null) {
            cmd += listOf("--module-path", jmodsHome.resolve("jmods").absolutePath)
        }
        cmd += listOf(
            "--add-modules", modulesCsv,
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress=zip-9",
            "--output", outDir.absolutePath,
        )
        runCheckedProcess(cmd)
        require(outDir.resolve("bin/java.exe").isFile) {
            "Windows jlink runtime did not produce bin/java.exe at ${outDir.path}"
        }
        println("[buildJlinkRuntimeWindows] Built trimmed Windows JDK 25 runtime at ${outDir.path}")
    }
}

val buildWindowsNativeLauncher = tasks.register("buildWindowsNativeLauncher") {
    group = "distribution"
    description = "Compile mandatory zero-dependency Windows x86_64 fuji-emulator-gfx100s.exe native launcher via MinGW-w64"
    val srcFile = layout.projectDirectory.file("tools/release/jpackage/win_launcher.c")
    inputs.file(srcFile)
    outputs.file(winLauncherOutFile)
    doLast {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val compiler = listOfNotNull(
            System.getenv("FUJI_MINGW64_GCC")?.takeIf { it.isNotBlank() },
            if (isWin) "gcc" else "x86_64-w64-mingw32-gcc",
        ).firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        }
        val outExe = winLauncherOutFile.get().asFile
        if (compiler == null) {
            error("Mandatory MinGW-w64 compiler not found on PATH (checked FUJI_MINGW64_GCC and ${if (isWin) "gcc" else "x86_64-w64-mingw32-gcc"}). Install mingw-w64 to compile fuji-emulator-gfx100s.exe.")
        }
        outExe.parentFile.mkdirs()
        runCheckedProcess(
            listOf(
                compiler,
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-municode",
                "-s",
                srcFile.asFile.absolutePath,
                "-o",
                outExe.absolutePath,
            ),
        )
        require(outExe.isFile && outExe.length() > 0L) {
            "Mandatory Windows native launcher was not produced at ${outExe.path}"
        }
        println("[buildWindowsNativeLauncher] Compiled ${outExe.path} (${outExe.length()} bytes)")
    }
}

val audioBridgeSourceDir = layout.projectDirectory.dir("tools/native/audio-bridge")
val audioBridgeLinuxLib = layout.projectDirectory.file(".tools/audio-bridge/build-linux/libfuji_audio.so")
val audioBridgeWin64Dll = layout.projectDirectory.file(".tools/audio-bridge/build-win64/fuji_audio.dll")

val buildAudioNative = tasks.register("buildAudioNative") {
    group = "build"
    description = "Build native host audio bridge library (libfuji_audio.so on Linux, fuji_audio.dll on Windows)"
    inputs.dir(audioBridgeSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) audioBridgeWin64Dll else audioBridgeLinuxLib)
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("win")) {
            val outFile = audioBridgeWin64Dll.asFile
            outFile.parentFile.mkdirs()
            runCheckedProcess(
                listOf(
                    "gcc",
                    "-O2",
                    "-std=c11",
                    "-shared",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    "-DUNICODE",
                    "-D_UNICODE",
                    "-DCOBJMACROS",
                    "-DWIN32_LEAN_AND_MEAN",
                    "-DFUJI_AUDIO_BUILD_DLL",
                    audioBridgeSourceDir.file("fuji_audio_win32.c").asFile.absolutePath,
                    "-o",
                    outFile.absolutePath,
                    "-static-libgcc",
                    "-Wl,--exclude-libs,ALL",
                    "-lole32",
                    "-lavrt",
                    "-lksguid",
                ),
            )
            println("[buildAudioNative] Built ${outFile.path} (${outFile.length()} bytes)")
        } else {
            val script = audioBridgeSourceDir.file("build_audio.sh").asFile
            runCheckedProcess(listOf("bash", script.absolutePath, "linux"))
        }
    }
}

val buildAudioNativeWindowsCross = tasks.register("buildAudioNativeWindowsCross") {
    group = "build"
    description = "Cross-compile zero-dependency Windows x86_64 WASAPI fuji_audio.dll via MinGW-w64"
    inputs.dir(audioBridgeSourceDir)
    outputs.file(audioBridgeWin64Dll)
    doLast {
        val script = audioBridgeSourceDir.file("build_audio.sh").asFile
        runCheckedProcess(listOf("bash", script.absolutePath, "win64"))
    }
}

val gamepadBridgeSourceDir = layout.projectDirectory.dir("tools/native/gamepad-bridge")
val gamepadBridgeLinuxLib = layout.projectDirectory.file(".tools/gamepad-bridge/build-linux/libfuji_gamepad.so")
val gamepadBridgeWin64Dll = layout.projectDirectory.file(".tools/gamepad-bridge/build-win64/fuji_gamepad.dll")

val buildGamepadNative = tasks.register("buildGamepadNative") {
    group = "build"
    description = "Build the mandatory native gamepad bridge (libfuji_gamepad.so on Linux, fuji_gamepad.dll on Windows) and run its C self-tests"
    inputs.dir(gamepadBridgeSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) gamepadBridgeWin64Dll else gamepadBridgeLinuxLib)
    doLast {
        val target = if (System.getProperty("os.name").lowercase().contains("win")) "win64" else "linux"
        runCheckedProcess(listOf("bash", gamepadBridgeSourceDir.file("build_gamepad.sh").asFile.absolutePath, target))
    }
}

val buildGamepadNativeWindowsCross = tasks.register("buildGamepadNativeWindowsCross") {
    group = "build"
    description = "Cross-compile the mandatory Windows x86_64 fuji_gamepad.dll (dynamic XInput; KERNEL32 + msvcrt imports only)"
    inputs.dir(gamepadBridgeSourceDir)
    outputs.file(gamepadBridgeWin64Dll)
    doLast {
        runCheckedProcess(listOf("bash", gamepadBridgeSourceDir.file("build_gamepad.sh").asFile.absolutePath, "win64"))
    }
}

val renderBridgeSourceDir = layout.projectDirectory.dir("tools/native/render-bridge")
val renderBridgeLinuxLib = layout.projectDirectory.file(".tools/render-bridge/build-linux/libfuji_render.so")
val renderBridgeWin64Dll = layout.projectDirectory.file(".tools/render-bridge/build-win64/fuji_render.dll")

val buildRenderNative = tasks.register("buildRenderNative") {
    group = "build"
    description = "Build native SIMD rendering pipeline bridge library (libfuji_render.so on Linux, fuji_render.dll on Windows)"
    inputs.dir(renderBridgeSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) renderBridgeWin64Dll else renderBridgeLinuxLib)
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("win")) {
            val outFile = renderBridgeWin64Dll.asFile
            outFile.parentFile.mkdirs()
            runCheckedProcess(
                listOf(
                    "gcc",
                    "-O3",
                    "-std=c11",
                    "-msse2",
                    "-shared",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    "-DFUJI_RENDER_BUILD_DLL",
                    renderBridgeSourceDir.file("fuji_render.c").asFile.absolutePath,
                    "-o",
                    outFile.absolutePath,
                    "-static-libgcc",
                    "-Wl,--exclude-libs,ALL",
                ),
            )
            println("[buildRenderNative] Built ${outFile.path} (${outFile.length()} bytes)")
        } else {
            val script = renderBridgeSourceDir.file("build_render.sh").asFile
            runCheckedProcess(listOf("bash", script.absolutePath, "linux"))
        }
    }
}

val buildRenderNativeWindowsCross = tasks.register("buildRenderNativeWindowsCross") {
    group = "build"
    description = "Cross-compile zero-dependency Windows x86_64 SIMD fuji_render.dll via MinGW-w64"
    inputs.dir(renderBridgeSourceDir)
    outputs.file(renderBridgeWin64Dll)
    doLast {
        val script = renderBridgeSourceDir.file("build_render.sh").asFile
        runCheckedProcess(listOf("bash", script.absolutePath, "win64"))
    }
}

val vulkanOpticsSourceDir = layout.projectDirectory.dir("tools/native/vulkan-optics")
val vulkanOpticsLinuxLib = layout.projectDirectory.file(".tools/vulkan-optics/build-linux/libfuji_vulkan_optics.so")
val vulkanOpticsWin64Dll = layout.projectDirectory.file(".tools/vulkan-optics/build-win64/fuji_vulkan_optics.dll")

val buildVulkanOpticsNative = tasks.register("buildVulkanOpticsNative") {
    group = "build"
    description = "Build zero-bloat dynamic Vulkan 1.1 compute optical pipeline bridge (libfuji_vulkan_optics.so on Linux, fuji_vulkan_optics.dll on Windows)"
    inputs.dir(vulkanOpticsSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) vulkanOpticsWin64Dll else vulkanOpticsLinuxLib)
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("win")) {
            val outFile = vulkanOpticsWin64Dll.asFile
            outFile.parentFile.mkdirs()
            runCheckedProcess(
                listOf(
                    "g++",
                    "-O3",
                    "-std=c++17",
                    "-shared",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    "-DFUJI_VK_OPTICS_BUILD_DLL",
                    vulkanOpticsSourceDir.file("fuji_vulkan_optics.cpp").asFile.absolutePath,
                    vulkanOpticsSourceDir.file("fuji_vulkan_pipeline_cache.cpp").asFile.absolutePath,
                    vulkanOpticsSourceDir.file("fuji_vulkan_stats.cpp").asFile.absolutePath,
                    vulkanOpticsSourceDir.file("fuji_vulkan_scene3d.cpp").asFile.absolutePath,
                    vulkanOpticsSourceDir.file("fuji_vulkan_custom_spv.cpp").asFile.absolutePath,
                    "-o",
                    outFile.absolutePath,
                    "-static",
                    "-static-libgcc",
                    "-static-libstdc++",
                    "-Wl,--exclude-libs,ALL",
                ),
            )
            println("[buildVulkanOpticsNative] Built ${outFile.path} (${outFile.length()} bytes)")
        } else {
            val script = vulkanOpticsSourceDir.file("build_vulkan_optics.sh").asFile
            runCheckedProcess(listOf("bash", script.absolutePath, "linux"))
        }
    }
}

val buildVulkanOpticsNativeWindowsCross = tasks.register("buildVulkanOpticsNativeWindowsCross") {
    group = "build"
    description = "Cross-compile zero-dependency Windows x86_64 dynamic Vulkan 1.1 compute fuji_vulkan_optics.dll via MinGW-w64"
    inputs.dir(vulkanOpticsSourceDir)
    outputs.file(vulkanOpticsWin64Dll)
    doLast {
        val script = vulkanOpticsSourceDir.file("build_vulkan_optics.sh").asFile
        runCheckedProcess(listOf("bash", script.absolutePath, "win64"))
    }
}

val ispSimdSourceDir = layout.projectDirectory.dir("tools/native/isp-simd")
val ispSimdLinuxLib = layout.projectDirectory.file(".tools/isp-simd/build-linux/libfuji_isp_simd.so")
val ispSimdWin64Dll = layout.projectDirectory.file(".tools/isp-simd/build-win64/fuji_isp_simd.dll")

val buildIspSimdNative = tasks.register("buildIspSimdNative") {
    group = "build"
    description = "Build zero-dependency multi-threaded AVX-512/AVX2/SSE2 CPU ISP & spatial optical filter SIMD library"
    inputs.dir(ispSimdSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) ispSimdWin64Dll else ispSimdLinuxLib)
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("win")) {
            val outFile = ispSimdWin64Dll.asFile
            outFile.parentFile.mkdirs()
            runCheckedProcess(
                listOf(
                    "gcc", "-O3", "-std=c11", "-msse2", "-shared", "-Wall", "-Wextra", "-Werror",
                    ispSimdSourceDir.file("fuji_isp_simd.c").asFile.absolutePath,
                    "-o", outFile.absolutePath, "-lm", "-static-libgcc", "-Wl,--exclude-libs,ALL",
                ),
            )
            println("[buildIspSimdNative] Built ${outFile.path} (${outFile.length()} bytes)")
        } else {
            runCheckedProcess(listOf("bash", ispSimdSourceDir.file("build_isp_simd.sh").asFile.absolutePath, "linux"))
        }
    }
}

val buildIspSimdNativeWindowsCross = tasks.register("buildIspSimdNativeWindowsCross") {
    group = "build"
    description = "Cross-compile zero-dependency Windows x86_64 fuji_isp_simd.dll via MinGW-w64"
    inputs.dir(ispSimdSourceDir)
    outputs.file(ispSimdWin64Dll)
    doLast {
        runCheckedProcess(listOf("bash", ispSimdSourceDir.file("build_isp_simd.sh").asFile.absolutePath, "win64"))
    }
}

val codecBridgeSourceDir = layout.projectDirectory.dir("tools/native/codec-bridge")
val codecBridgeLinuxLib = layout.projectDirectory.file(".tools/codec-bridge/build-linux/libfuji_codec.so")
val codecBridgeWin64Dll = layout.projectDirectory.file(".tools/codec-bridge/build-win64/fuji_codec.dll")

val buildCodecNative = tasks.register("buildCodecNative") {
    group = "build"
    description = "Build zero-static-dependency MT LSRAW + dynamic libjpeg-turbo YUV422 native codec library"
    inputs.dir(codecBridgeSourceDir)
    outputs.file(if (System.getProperty("os.name").lowercase().contains("win")) codecBridgeWin64Dll else codecBridgeLinuxLib)
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("win")) {
            val outFile = codecBridgeWin64Dll.asFile
            outFile.parentFile.mkdirs()
            runCheckedProcess(
                listOf(
                    "gcc", "-O3", "-std=c11", "-msse2", "-shared", "-Wall", "-Wextra", "-Werror",
                    "-DFUJI_CODEC_BUILD_DLL",
                    codecBridgeSourceDir.file("fuji_codec.c").asFile.absolutePath,
                    "-o", outFile.absolutePath, "-static-libgcc", "-Wl,--exclude-libs,ALL",
                ),
            )
            println("[buildCodecNative] Built ${outFile.path} (${outFile.length()} bytes)")
        } else {
            runCheckedProcess(listOf("bash", codecBridgeSourceDir.file("build_codec.sh").asFile.absolutePath, "linux"))
        }
    }
}

val buildCodecNativeWindowsCross = tasks.register("buildCodecNativeWindowsCross") {
    group = "build"
    description = "Cross-compile zero-dependency Windows x86_64 fuji_codec.dll via MinGW-w64"
    inputs.dir(codecBridgeSourceDir)
    outputs.file(codecBridgeWin64Dll)
    doLast {
        runCheckedProcess(listOf("bash", codecBridgeSourceDir.file("build_codec.sh").asFile.absolutePath, "win64"))
    }
}

fun stageCompanionPlaybackFfmpegSource(distributionsDir: File) {
    val archiveName = "ffmpeg-8.1.3.tar.xz"
    val candidate = listOfNotNull(
        System.getenv("FUJI_FFMPEG_TARBALL")?.takeIf { it.isNotBlank() }?.let(::File),
        rootProject.file(".tools/playback-ffmpeg/cache/$archiveName"),
        rootProject.file(".tools/playback-ffmpeg/linux/$archiveName"),
        rootProject.file(".tools/playback-ffmpeg/win64/$archiveName"),
        File("/opt/fuji-cache/playback-ffmpeg/$archiveName"),
    ).firstOrNull { it.isFile && it.length() > 0L } ?: return
    distributionsDir.mkdirs()
    candidate.copyTo(distributionsDir.resolve(archiveName), overwrite = true)
}

fun buildPlaybackFfmpeg(target: String, taskName: String) = tasks.register(taskName) {
    group = "build"
    description = "Build the pinned AVC/HEVC playback decoder for $target"
    val script = rootProject.file("tools/native/playback-ffmpeg/build.sh")
    val packageDir = rootProject.file(".tools/playback-ffmpeg/build-$target")
    val executableName = if (target == "win64") "ffmpeg.exe" else "ffmpeg"
    inputs.file(script)
    outputs.dir(packageDir)
    onlyIf {
        val stamp = packageDir.resolve("bridge.sha256")
        val bin = packageDir.resolve("runtime/media/$executableName")
        val license = packageDir.resolve("runtime/media/licenses/COPYING.LGPLv2.1")
        val buildScriptCopy = packageDir.resolve("runtime/media/licenses/build.sh")
        val sourceNotice = packageDir.resolve("runtime/media/licenses/SOURCE.txt")
        if (!stamp.isFile || !bin.isFile || !license.isFile || !buildScriptCopy.isFile || !sourceNotice.isFile) {
            true
        } else {
            val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(script.readBytes())
                .joinToString("") { "%02x".format(it) }
            stamp.readText(Charsets.UTF_8).trim() != expectedSha
        }
    }
    doLast {
        runCheckedProcess(
            listOf("bash", "tools/native/playback-ffmpeg/build.sh", target),
            workDir = rootProject.projectDir,
        )
    }
}

val buildPlaybackFfmpegLinux = buildPlaybackFfmpeg("linux", "buildPlaybackFfmpegLinux")
val buildPlaybackFfmpegWindows = buildPlaybackFfmpeg("win64", "buildPlaybackFfmpegWindows")

val stageReleaseLinux = tasks.register<Sync>("stageReleaseLinux") {
    dependsOn(buildPlaybackFfmpegLinux)
    from(rootProject.file(".tools/playback-ffmpeg/build-linux")) {
        exclude("bridge.sha256")
        filesMatching("runtime/media/ffmpeg") { permissions { unix("0755") } }
    }
    group = "distribution"
    description = "Stage self-contained one-click Linux x86_64 release bundle with mandatory Dynarmic JIT, mandatory native audio bridge, mandatory native SIMD render bridge, mandatory dynamic Vulkan 1.1 compute optics bridge, embedded jlink JDK 25, 2.13 firmware, and factory Flash template"
    val prebuiltLinuxAudio = System.getenv("FUJI_AUDIO_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    val prebuiltLinuxRender = System.getenv("FUJI_RENDER_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    val prebuiltLinuxVulkanOptics = System.getenv("FUJI_VULKAN_OPTICS_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    val prebuiltLinuxIspSimd = System.getenv("FUJI_ISP_SIMD_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    val prebuiltLinuxCodec = System.getenv("FUJI_CODEC_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    val prebuiltLinuxGamepad = System.getenv("FUJI_GAMEPAD_LIBRARY")
        ?.takeIf { it.isNotBlank() && it.endsWith(".so") }
        ?.let(::File)
    dependsOn("stageRuntime", ":desktop:releaseJar", generateReleaseFirmwareMetadata, generateReleaseBootRomFirmwareMetadata)
    if (bundleStockBins.get()) {
        dependsOn(generateFactoryFlashTemplate, extractBootRom)
    }
    if (prebuiltLinuxAudio == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildAudioNative)
    }
    if (prebuiltLinuxRender == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildRenderNative)
    }
    if (prebuiltLinuxVulkanOptics == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildVulkanOpticsNative)
    }
    if (prebuiltLinuxIspSimd == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildIspSimdNative)
    }
    if (prebuiltLinuxCodec == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildCodecNative)
    }
    if (prebuiltLinuxGamepad == null && !System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(buildGamepadNative)
    }
    if (embedJlinkRuntime.get()) {
        dependsOn(buildJlinkRuntimeLinux)
    }
    into(layout.buildDirectory.dir("release-stage/linux-x86_64"))
    from(layout.buildDirectory.dir("emulator")) {
        include("bin/**")
        filePermissions { unix("0755") }
    }
    from(layout.buildDirectory.dir("emulator")) {
        include("lib/**")
        exclude("lib/desktop-*.jar")
    }
    from(listOf(project(":desktop").tasks.named("releaseJar"), project(":desktop").tasks.named("artworkJar"))) {
        into("lib")
    }
    // Outermost configs/ directory (emulator.yaml, catalog, firmware metadata, discovery seeds, component configs)
    from(layout.buildDirectory.dir("emulator/config")) {
        include("catalog.compiled.json")
        into("configs")
    }
    from(releaseMetadataFile) {
        into("configs/firmware")
    }
    from(releaseBootRomMetadataFile) {
        into("configs/firmware")
    }
    from("tools/release/discovery") {
        into("configs/discovery")
    }
    from("tools/release/examples") {
        into("configs/examples")
    }
    from(layout.buildDirectory.dir("emulator/compoments")) {
        exclude("*.jar", "flash-image/**")
        exclude(releaseComponentOverridePaths())
        into("configs/components")
    }
    from("tools/release/component-configs") {
        exclude("**/*.jpg", "**/*.png", "**/*.bin", "**/*.img")
        into("configs/components")
    }
    from("tools/release/flash") {
        include("flash-image.yaml")
        into("configs/components/flash-image")
    }
    from("tools/release/emulator-release-linux.yaml") {
        into("configs")
        rename { "emulator.yaml" }
    }
    // Outermost user-facing flash/ and bootrom/ directories (always include bilingual PUT_HERE guides)
    from("tools/release/flash") {
        include("PUT_HERE")
        into("flash")
    }
    from("tools/release/bootrom") {
        include("PUT_HERE")
        into("bootrom")
    }
    // Internal runtime/ binaries, component JARs, binary assets, templates, and native library
    from(layout.buildDirectory.dir("emulator/compoments")) {
        include("*.jar")
        into("runtime/compoments")
    }
    from(layout.buildDirectory.dir("emulator/lenses")) {
        include("*.jar")
        into("lenses")
    }
    from("tools/release/component-configs") {
        include("**/*.jpg", "**/*.png", "**/*.bin", "**/*.img")
        into("runtime/compoments")
    }
    if (bundleStockBins.get()) {
        // Ship only ONE 64 MiB NOR image (runtime/templates/factory-flash/nor-flash-2.13.bin);
        // NorFlashTool.ensureLiveNorExists() seeds flash/nor-flash.bin automatically on first launch.
        from(bootRomOutDir) {
            include("boot-rom.bin", "boot-rom.sha256")
            into("bootrom")
        }
        from(factoryFlashOutDir) {
            include("nor-flash-2.13.bin", "nor-flash-2.13.sha256", "factory-flash-template.yaml", "README.md")
            into("runtime/templates/factory-flash")
        }
    }
    from("tools/release/start-gfx100s.sh") {
        filePermissions { unix("0755") }
    }
    from("tools/release/RELEASE-README.md")
    from("LICENSE")
    from("THIRD_PARTY_LICENSES.md")
    doFirst {
        // jlink legal/* files are mode 0444; ensure pre-existing staged jdk/ is writable before incremental Sync
        destinationDir.resolve("jdk").takeIf { it.exists() }?.walkTopDown()?.forEach { it.setWritable(true) }
        val dynLib = resolveReleaseNative(
            resolveReleaseDynarmicCandidatesLinux(),
            "Linux x86_64 Dynarmic JIT (mandatory primary CPU engine)",
            "Build it with `./gradlew :cpu-dynarmic:buildDynarmicNative`.",
        )
        println("Bundling mandatory Linux Dynarmic JIT primary CPU library: ${dynLib.path}")
        val audioLib = resolveReleaseNative(
            resolveReleaseAudioCandidatesLinux(),
            "Linux x86_64 Native Audio Bridge (mandatory M10 full-duplex audio)",
            "Build it with `./gradlew :buildAudioNative`.",
        )
        println("Bundling mandatory Linux Native Audio Bridge library: ${audioLib.path}")
        val renderLib = resolveReleaseNative(
            resolveReleaseRenderCandidatesLinux(),
            "Linux x86_64 Native SIMD Render Bridge (mandatory PERF-2 render pipeline)",
            "Build it with `./gradlew :buildRenderNative` or `bash tools/native/render-bridge/build_render.sh linux`.",
        )
        println("Bundling mandatory Linux Native SIMD Render Bridge library: ${renderLib.path}")
        val vulkanOpticsLib = resolveReleaseNative(
            resolveReleaseVulkanOpticsCandidatesLinux(),
            "Linux x86_64 Dynamic Vulkan 1.1 Compute Optics Bridge (mandatory VULKAN-W1 optical pipeline)",
            "Build it with `./gradlew :buildVulkanOpticsNative` or `bash tools/native/vulkan-optics/build_vulkan_optics.sh linux`.",
        )
        println("Bundling mandatory Linux Dynamic Vulkan 1.1 Compute Optics Bridge library: ${vulkanOpticsLib.path}")
        val ispSimdLib = resolveReleaseNative(
            resolveReleaseIspSimdCandidatesLinux(),
            "Linux x86_64 CPU ISP & Spatial Filter SIMD Bridge (mandatory W11-CPU-ISP-OPTICS-AVX2)",
            "Build it with `./gradlew :buildIspSimdNative` or `bash tools/native/isp-simd/build_isp_simd.sh linux`.",
        )
        println("Bundling mandatory Linux CPU ISP & Optical Filter SIMD library: ${ispSimdLib.path}")
        val codecLib = resolveReleaseNative(
            resolveReleaseCodecCandidatesLinux(),
            "Linux x86_64 Native LSRAW & JPEG Codec Bridge (mandatory W11-CODEC-TURBOJPEG-LSRAW)",
            "Build it with `./gradlew :buildCodecNative` or `bash tools/native/codec-bridge/build_codec.sh linux`.",
        )
        println("Bundling mandatory Linux Native Codec Bridge library: ${codecLib.path}")
        val gamepadLib = resolveReleaseNative(
            resolveReleaseGamepadCandidatesLinux(),
            "Linux x86_64 Native Gamepad Bridge (mandatory INPUT-BINDING-GAMEPAD-W11)",
            "Build it with `./gradlew :buildGamepadNative` or `bash tools/native/gamepad-bridge/build_gamepad.sh linux`.",
        )
        println("Bundling mandatory Linux Native Gamepad Bridge library: ${gamepadLib.path}")
        if (embedJlinkRuntime.get()) {
            val jlinkDir = jlinkLinuxOutDir.get().asFile
            require(jlinkDir.resolve("bin/java").isFile) {
                "Mandatory Linux jlink runtime missing bin/java at ${jlinkDir.path}. Run :buildJlinkRuntimeLinux."
            }
        }
    }
    // Mandatory Linux x86_64 Dynarmic ARMv7-A AVX2 JIT primary CPU engine (non-optional; Release is Dynarmic-only)
    from({
        listOf(
            resolveReleaseNative(
                resolveReleaseDynarmicCandidatesLinux(),
                "Linux x86_64 Dynarmic JIT (mandatory primary CPU engine)",
                "Build it with `./gradlew :cpu-dynarmic:buildDynarmicNative`.",
            ),
        )
    }) {
        into("runtime/native")
        rename { "libfuji_dynarmic.so" }
    }
    // Mandatory Linux x86_64 evdev/joydev native gamepad bridge (non-optional)
    if (prebuiltLinuxGamepad == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildGamepadNative) {
            into("runtime/native")
            rename { "libfuji_gamepad.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseGamepadCandidatesLinux(),
                    "Linux x86_64 Native Gamepad Bridge (mandatory INPUT-BINDING-GAMEPAD-W11)",
                    "Build it with `./gradlew :buildGamepadNative` or `bash tools/native/gamepad-bridge/build_gamepad.sh linux`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_gamepad.so" }
        }
    }
    // Mandatory Linux x86_64 PulseAudio/PipeWire native audio bridge (non-optional)
    if (prebuiltLinuxAudio == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildAudioNative) {
            into("runtime/native")
            rename { "libfuji_audio.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseAudioCandidatesLinux(),
                    "Linux x86_64 Native Audio Bridge (mandatory M10 full-duplex audio)",
                    "Build it with `./gradlew :buildAudioNative`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_audio.so" }
        }
    }
    // Mandatory Linux x86_64 native SIMD render bridge (non-optional)
    if (prebuiltLinuxRender == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildRenderNative) {
            into("runtime/native")
            rename { "libfuji_render.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseRenderCandidatesLinux(),
                    "Linux x86_64 Native SIMD Render Bridge (mandatory PERF-2 render pipeline)",
                    "Build it with `./gradlew :buildRenderNative` or `bash tools/native/render-bridge/build_render.sh linux`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_render.so" }
        }
    }
    // Mandatory Linux x86_64 dynamic Vulkan 1.1 compute optics bridge (zero static Vulkan linkage)
    if (prebuiltLinuxVulkanOptics == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildVulkanOpticsNative) {
            into("runtime/native")
            rename { "libfuji_vulkan_optics.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseVulkanOpticsCandidatesLinux(),
                    "Linux x86_64 Dynamic Vulkan 1.1 Compute Optics Bridge (mandatory VULKAN-W1 optical pipeline)",
                    "Build it with `./gradlew :buildVulkanOpticsNative` or `bash tools/native/vulkan-optics/build_vulkan_optics.sh linux`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_vulkan_optics.so" }
        }
    }
    // Mandatory Linux x86_64 multi-threaded AVX-512/AVX2/SSE2 CPU ISP & spatial optical filter SIMD library
    if (prebuiltLinuxIspSimd == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildIspSimdNative) {
            into("runtime/native")
            rename { "libfuji_isp_simd.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseIspSimdCandidatesLinux(),
                    "Linux x86_64 CPU ISP & Spatial Filter SIMD Bridge (mandatory W11-CPU-ISP-OPTICS-AVX2)",
                    "Build it with `./gradlew :buildIspSimdNative` or `bash tools/native/isp-simd/build_isp_simd.sh linux`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_isp_simd.so" }
        }
    }
    // Mandatory Linux x86_64 native LSRAW & dynamic libjpeg-turbo codec bridge
    if (prebuiltLinuxCodec == null && !System.getProperty("os.name").lowercase().contains("win")) {
        from(buildCodecNative) {
            into("runtime/native")
            rename { "libfuji_codec.so" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseCodecCandidatesLinux(),
                    "Linux x86_64 Native LSRAW & JPEG Codec Bridge (mandatory W11-CODEC-TURBOJPEG-LSRAW)",
                    "Build it with `./gradlew :buildCodecNative` or `bash tools/native/codec-bridge/build_codec.sh linux`.",
                ),
            )
        }) {
            into("runtime/native")
            rename { "libfuji_codec.so" }
        }
    }
    // Embedded trimmed JDK 25 runtime from buildJlinkRuntimeLinux (mandatory unless -PfujiEmbedJlink=false)
    if (embedJlinkRuntime.get()) {
        from(buildJlinkRuntimeLinux) {
            into("jdk")
        }
    }
    doLast {
        val logsDir = destinationDir.resolve("logs")
        logsDir.mkdirs()
        logsDir.resolve(".keep").writeText("")
        initializeReleaseSdCardDirectories(
            destinationDir,
            project.file("tools/release/component-configs/cmos/test-qr.jpg"),
        )
    }
}

val verifyReleaseLinux = tasks.register("verifyReleaseLinux") {
    group = "verification"
    description = "Fail-fast Release gate verifying all mandatory Linux x86_64 high-performance native libraries, configs, and embedded jlink runtime"
    dependsOn(stageReleaseLinux)
    doLast {
        val stageDir = layout.buildDirectory.dir("release-stage/linux-x86_64").get().asFile
        val stockBins = bundleStockBins.get()
        val requiredFiles = mutableListOf(
            "runtime/media/ffmpeg",
            "runtime/media/licenses/COPYING.LGPLv2.1",
            "runtime/media/licenses/build.sh",
            "runtime/media/licenses/SOURCE.txt",
            "runtime/native/libfuji_dynarmic.so",
            "runtime/native/libfuji_audio.so",
            "runtime/native/libfuji_render.so",
            "runtime/native/libfuji_vulkan_optics.so",
            "runtime/native/libfuji_isp_simd.so",
            "runtime/native/libfuji_codec.so",
            "runtime/native/libfuji_gamepad.so",
            "flash/PUT_HERE",
            "bootrom/PUT_HERE",
            "lib/desktop-${project.version}.jar",
            "lib/camera-artwork-${project.version}.jar",
            "configs/emulator.yaml",
            "configs/firmware/gfx100s-213-release.yaml",
            "configs/firmware/gfx100s-213-bootrom-release.yaml",
            "configs/components/flash-image/flash-image.yaml",
            "configs/components/soc-system-control/boot-strap.json",
            "configs/components/lens/lens.yaml",
            "start-gfx100s.sh",
            "RELEASE-README.md",
            "LICENSE",
            "THIRD_PARTY_LICENSES.md",
        )
        if (stockBins) {
            requiredFiles += listOf(
                "runtime/templates/factory-flash/nor-flash-2.13.bin",
                "bootrom/boot-rom.bin",
            )
        }
        if (embedJlinkRuntime.get()) {
            requiredFiles += "jdk/bin/java"
        }
        val missing = requiredFiles.filter { rel ->
            val f = stageDir.resolve(rel)
            !f.isFile || f.length() == 0L
        }
        if (missing.isNotEmpty()) {
            error("verifyReleaseLinux FAILED — missing or empty mandatory release artifact(s) in ${stageDir.path}: ${missing.joinToString(", ")}")
        }
        require(!stageDir.resolve("runtime/media/licenses/ffmpeg-8.1.3.tar.xz").exists()) {
            "verifyReleaseLinux FAILED — ffmpeg-8.1.3.tar.xz must be published as a companion source archive rather than embedded inside the binary bundle (keeps AppImage/tarballs < 100 MiB)"
        }
        require(!stageDir.resolve("runtime/firmware/GXUP0004.DAT").exists()) {
            "verifyReleaseLinux FAILED — Release boots directly from flash/nor-flash.bin and must not bundle redundant GXUP0004.DAT"
        }
        require(!stageDir.resolve("flash/nor-flash.bin").exists()) {
            "verifyReleaseLinux FAILED — staged release must not bundle duplicate flash/nor-flash.bin (first launch seeds it automatically from runtime/templates/factory-flash/nor-flash-2.13.bin)"
        }
        if (stockBins) {
            require(stageDir.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin").length() == 64L * 1024L * 1024L) {
                "verifyReleaseLinux FAILED — runtime/templates/factory-flash/nor-flash-2.13.bin must be exact 64 MiB"
            }
            require(stageDir.resolve("bootrom/boot-rom.bin").length() == 32_768L) {
                "verifyReleaseLinux FAILED — bootrom/boot-rom.bin must be exact 32 KiB"
            }
        } else {
            require(!stageDir.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin").exists()) {
                "verifyReleaseLinux FAILED — -PfujiBundleStockBins=false must omit runtime/templates/factory-flash/nor-flash-2.13.bin"
            }
            require(!stageDir.resolve("bootrom/boot-rom.bin").exists()) {
                "verifyReleaseLinux FAILED — -PfujiBundleStockBins=false must omit bootrom/boot-rom.bin"
            }
        }
        require(!stageDir.resolve("runtime/native/libunicorn.so.2").exists()) {
            "verifyReleaseLinux FAILED — redundant runtime/native/libunicorn.so.2 must not be bundled in Dynarmic-only Release"
        }
        val forbiddenLogbackJars = stageDir.resolve("lib").listFiles()?.filter { it.name.startsWith("logback-") } ?: emptyList()
        require(forbiddenLogbackJars.isEmpty()) {
            "verifyReleaseLinux FAILED — Logback JAR(s) must not be bundled in MIT Release: ${forbiddenLogbackJars.joinToString { it.name }}"
        }
        val desktopJarLen = stageDir.resolve("lib/desktop-${project.version}.jar").length()
        require(desktopJarLen in 1L..3_000_000L) {
            "verifyReleaseLinux FAILED — expected slim Release desktop JAR (<3 MB), got ${desktopJarLen}B"
        }
        val emulatorYaml = stageDir.resolve("configs/emulator.yaml").readText(Charsets.UTF_8)
        require(Regex("(?m)^cpuEngine:\\s*dynarmic\\s*$").containsMatchIn(emulatorYaml)) {
            "verifyReleaseLinux FAILED — configs/emulator.yaml must enforce `cpuEngine: dynarmic`"
        }
        require(Regex("(?m)^audio:\\s*true\\s*$").containsMatchIn(emulatorYaml)) {
            "verifyReleaseLinux FAILED — configs/emulator.yaml must enforce `audio: true`"
        }
        println(
            "[verifyReleaseLinux] PASS: verified ${requiredFiles.size} mandatory Linux release artifacts " +
                "(libfuji_dynarmic.so=${stageDir.resolve("runtime/native/libfuji_dynarmic.so").length()}B, " +
                "libfuji_audio.so=${stageDir.resolve("runtime/native/libfuji_audio.so").length()}B, " +
                "libfuji_render.so=${stageDir.resolve("runtime/native/libfuji_render.so").length()}B, " +
                "libfuji_vulkan_optics.so=${stageDir.resolve("runtime/native/libfuji_vulkan_optics.so").length()}B, " +
                "libfuji_isp_simd.so=${stageDir.resolve("runtime/native/libfuji_isp_simd.so").length()}B, " +
                "libfuji_codec.so=${stageDir.resolve("runtime/native/libfuji_codec.so").length()}B, " +
                "libfuji_gamepad.so=${stageDir.resolve("runtime/native/libfuji_gamepad.so").length()}B, " +
                "desktop.jar=${desktopJarLen}B, unicorn=excluded)",
        )
    }
}

tasks.register<Tar>("packageReleaseLinux") {
    group = "distribution"
    description = "Package self-contained one-click Linux x86_64 release (.tar.gz)"
    dependsOn(stageReleaseLinux, verifyReleaseLinux)
    compression = Compression.GZIP
    archiveBaseName.set("fuji-emulator-gfx100s")
    archiveVersion.set(releaseVersion)
    archiveClassifier.set("linux-x86_64")
    archiveExtension.set("tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("release-stage/linux-x86_64")) {
        into("fuji-emulator-gfx100s-linux-x86_64")
        eachFile {
            val p = relativePath.pathString
            if (p.endsWith("/bin/desktop") ||
                p.endsWith("/start-gfx100s.sh") ||
                p.endsWith("/runtime/media/ffmpeg") ||
                p.contains("/jdk/bin/") ||
                p.endsWith("/jdk/lib/jspawnhelper") ||
                p.endsWith("/jdk/lib/jexec")
            ) {
                permissions { unix("0755") }
            }
        }
    }
    doLast {
        stageCompanionPlaybackFfmpegSource(destinationDirectory.get().asFile)
    }
}

val packageReleaseLinuxJpackage = tasks.register("packageReleaseLinuxJpackage") {
    group = "distribution"
    description = "Build self-contained Linux x86_64 jpackage app-image with native fuji-emulator-gfx100s launcher and embedded jlink JDK 25"
    dependsOn(stageReleaseLinux, verifyReleaseLinux, buildJlinkRuntimeLinux)
    outputs.dir(jpackageLinuxOutDir)
    doLast {
        val jlinkDir = jlinkLinuxOutDir.get().asFile
        require(jlinkDir.resolve("bin/java").isFile) {
            "Missing Linux jlink runtime at ${jlinkDir.path}. Run :buildJlinkRuntimeLinux on Linux JDK 25."
        }
        val stageDir = layout.buildDirectory.dir("release-stage/linux-x86_64").get().asFile
        val inputLibDir = stageDir.resolve("lib")
        val mainJarName = inputLibDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("desktop-") && it.name.endsWith(".jar") }
            ?.name ?: error("Could not locate desktop-*.jar inside ${inputLibDir.path}")
        val destDir = jpackageLinuxOutDir.get().asFile
        val appDir = destDir.resolve("fuji-emulator-gfx100s")
        appDir.deleteRecursively()
        destDir.mkdirs()

        val jpackage = resolveActiveJdkTool("jpackage")
        val appVer = jpackageNumericVersion(releaseVersion.get())
        runCheckedProcess(
            listOf(
                jpackage.absolutePath,
                "--type", "app-image",
                "--name", "fuji-emulator-gfx100s",
                "--app-version", appVer,
                "--vendor", "FUJIFILM GFX100S Emulator Project",
                "--description", "FUJIFILM GFX100S 2.13 Digital Camera Emulator",
                "--input", inputLibDir.absolutePath,
                "--main-jar", mainJarName,
                "--main-class", "com.github.nyabanban.fuji.emulator.desktop.MainKt",
                "--runtime-image", jlinkDir.absolutePath,
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-Xms512m",
                "--java-options", "-Xmx2g",
                "--java-options", "-XX:+UseG1GC",
                "--java-options", "-XX:MaxGCPauseMillis=15",
                "--java-options", "-Dprism.order=es2,sw",
                "--java-options", "-Dprism.forceGPU=true",
                "--java-options", "-Dfuji.home=\$APPDIR/../..",
                "--java-options", "-Dfuji.render.library=\$APPDIR/../../runtime/native/libfuji_render.so",
                "--java-options", "-Dfuji.vulkan.optics.library=\$APPDIR/../../runtime/native/libfuji_vulkan_optics.so",
                "--java-options", "-Dfuji.isp.simd.library=\$APPDIR/../../runtime/native/libfuji_isp_simd.so",
                "--java-options", "-Dfuji.codec.library=\$APPDIR/../../runtime/native/libfuji_codec.so",
                "--java-options", "-Dfuji.gamepad.library=\$APPDIR/../../runtime/native/libfuji_gamepad.so",
                "--arguments", "--home",
                "--arguments", "\$APPDIR/../..",
                "--arguments", "--config",
                "--arguments", "\$APPDIR/../../configs/emulator.yaml",
                "--arguments", "--auto-boot",
                "--dest", destDir.absolutePath,
            ),
        )

        // Overlay release configs/, flash/, bootrom/, lenses/, runtime/, logs/, RELEASE-README.md, and start-gfx100s.sh into app-image root
        for (sub in listOf("configs", "flash", "bootrom", "lenses", "runtime", "logs")) {
            val src = stageDir.resolve(sub)
            if (!src.exists()) continue
            val dst = appDir.resolve(sub)
            dst.deleteRecursively()
            src.copyRecursively(dst, overwrite = true)
        }
        check(appDir.resolve("runtime/media/ffmpeg").setExecutable(true, false)) { "Could not mark playback decoder executable" }
        stageDir.resolve("RELEASE-README.md").copyTo(appDir.resolve("RELEASE-README.md"), overwrite = true)
        stageDir.resolve("LICENSE").copyTo(appDir.resolve("LICENSE"), overwrite = true)
        stageDir.resolve("THIRD_PARTY_LICENSES.md").copyTo(appDir.resolve("THIRD_PARTY_LICENSES.md"), overwrite = true)
        val startSh = appDir.resolve("start-gfx100s.sh")
        stageDir.resolve("start-gfx100s.sh").copyTo(startSh, overwrite = true)
        startSh.setExecutable(true, false)
        println("[packageReleaseLinuxJpackage] Packaged Linux jpackage app-image at ${appDir.path}")
    }
}

tasks.register<Tar>("packageReleaseLinuxAppImageTar") {
    group = "distribution"
    description = "Archive self-contained Linux x86_64 jpackage app-image (.tar.gz)"
    dependsOn(packageReleaseLinuxJpackage, verifyReleaseLinux)
    compression = Compression.GZIP
    archiveBaseName.set("fuji-emulator-gfx100s")
    archiveVersion.set(releaseVersion)
    archiveClassifier.set("linux-x86_64-appimage")
    archiveExtension.set("tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(jpackageLinuxOutDir) {
        include("fuji-emulator-gfx100s/**")
        eachFile {
            val p = relativePath.pathString
            if (p.contains("/bin/") ||
                p.endsWith("/start-gfx100s.sh") ||
                p.endsWith("/runtime/media/ffmpeg") ||
                p.contains("/lib/runtime/bin/") ||
                p.endsWith("/lib/runtime/lib/jspawnhelper") ||
                p.endsWith("/lib/runtime/lib/jexec")
            ) {
                permissions { unix("0755") }
            }
        }
    }
    doLast {
        stageCompanionPlaybackFfmpegSource(destinationDirectory.get().asFile)
    }
}

tasks.register("buildAotCacheLinux") {
    group = "distribution"
    description = "Opt-in JDK 25 JEP 483/514 Ahead-of-Time class loading & linking cache for faster Linux startup"
    dependsOn(stageReleaseLinux, buildJlinkRuntimeLinux)
    outputs.file(aotCacheLinuxOutFile)
    doLast {
        val stageDir = layout.buildDirectory.dir("release-stage/linux-x86_64").get().asFile
        val javaBin = stageDir.resolve("jdk/bin/java")
        require(javaBin.isFile) { "Missing embedded Linux jlink java at ${javaBin.path}" }
        val aotOut = aotCacheLinuxOutFile.get().asFile
        aotOut.parentFile.mkdirs()
        aotOut.delete()
        val cp = stageDir.resolve("lib").absolutePath + "/*"
        runCheckedProcess(
            listOf(
                javaBin.absolutePath,
                "-XX:AOTCacheOutput=${aotOut.absolutePath}",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", cp,
                "com.github.nyabanban.fuji.emulator.desktop.MainKt",
                "--home", stageDir.absolutePath,
                "--config", stageDir.resolve("configs/emulator.yaml").absolutePath,
                "--inspect",
            ),
            workDir = stageDir,
        )
        require(aotOut.isFile && aotOut.length() > 0L) { "AOT cache was not produced at ${aotOut.path}" }
        println("[buildAotCacheLinux] Generated JDK 25 AOT cache at ${aotOut.path} (${aotOut.length()} bytes)")
    }
}

val stageReleaseWindows = tasks.register<Sync>("stageReleaseWindows") {
    dependsOn(buildPlaybackFfmpegWindows)
    from(rootProject.file(".tools/playback-ffmpeg/build-win64")) {
        exclude("bridge.sha256")
    }
    group = "distribution"
    description = "Stage self-contained one-click Windows x86_64 release bundle with mandatory Dynarmic JIT DLL, mandatory WASAPI native audio DLL, mandatory native SIMD render DLL, mandatory native .exe launcher, embedded jlink JDK 25, 2.13 firmware, Windows JavaFX, and factory Flash template"
    val prebuiltWinAudio = System.getenv("FUJI_WIN64_AUDIO_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winAudioBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildAudioNative
    } else {
        buildAudioNativeWindowsCross
    }
    val prebuiltWinRender = System.getenv("FUJI_WIN64_RENDER_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winRenderBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildRenderNative
    } else {
        buildRenderNativeWindowsCross
    }
    val prebuiltWinVulkanOptics = System.getenv("FUJI_WIN64_VULKAN_OPTICS_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winVulkanOpticsBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildVulkanOpticsNative
    } else {
        buildVulkanOpticsNativeWindowsCross
    }
    val prebuiltWinIspSimd = System.getenv("FUJI_WIN64_ISP_SIMD_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winIspSimdBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildIspSimdNative
    } else {
        buildIspSimdNativeWindowsCross
    }
    val prebuiltWinCodec = System.getenv("FUJI_WIN64_CODEC_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winCodecBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildCodecNative
    } else {
        buildCodecNativeWindowsCross
    }
    val prebuiltWinGamepad = System.getenv("FUJI_WIN64_GAMEPAD_DLL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val winGamepadBuildTask = if (System.getProperty("os.name").lowercase().contains("win")) {
        buildGamepadNative
    } else {
        buildGamepadNativeWindowsCross
    }
    dependsOn("stageRuntime", ":desktop:releaseJar", generateReleaseFirmwareMetadata, generateReleaseBootRomFirmwareMetadata, buildWindowsNativeLauncher)
    if (bundleStockBins.get()) {
        dependsOn(generateFactoryFlashTemplate, extractBootRom)
    }
    if (prebuiltWinAudio == null) {
        dependsOn(winAudioBuildTask)
    }
    if (prebuiltWinRender == null) {
        dependsOn(winRenderBuildTask)
    }
    if (prebuiltWinVulkanOptics == null) {
        dependsOn(winVulkanOpticsBuildTask)
    }
    if (prebuiltWinIspSimd == null) {
        dependsOn(winIspSimdBuildTask)
    }
    if (prebuiltWinCodec == null) {
        dependsOn(winCodecBuildTask)
    }
    if (prebuiltWinGamepad == null) {
        dependsOn(winGamepadBuildTask)
    }
    if (embedJlinkRuntime.get()) {
        dependsOn(buildJlinkRuntimeWindows)
    }
    into(layout.buildDirectory.dir("release-stage/windows-x86_64"))
    from(layout.buildDirectory.dir("emulator")) {
        include("bin/**")
    }
    from(layout.buildDirectory.dir("emulator")) {
        include("lib/**")
        exclude("lib/desktop-*.jar", "lib/javafx-*-linux.jar", "lib/javafx-*-mac.jar", "lib/javafx-*-mac-aarch64.jar")
    }
    from(listOf(project(":desktop").tasks.named("releaseJar"), project(":desktop").tasks.named("artworkJar"))) {
        into("lib")
    }
    from({
        platformArtifacts.files.filter { it.name.endsWith("-win.jar") }
    }) {
        into("lib")
    }
    // Outermost configs/ directory
    from(layout.buildDirectory.dir("emulator/config")) {
        include("catalog.compiled.json")
        into("configs")
    }
    from(releaseMetadataFile) {
        into("configs/firmware")
    }
    from(releaseBootRomMetadataFile) {
        into("configs/firmware")
    }
    from("tools/release/discovery") {
        into("configs/discovery")
    }
    from("tools/release/examples") {
        into("configs/examples")
    }
    from(layout.buildDirectory.dir("emulator/compoments")) {
        exclude("*.jar", "flash-image/**")
        exclude(releaseComponentOverridePaths())
        into("configs/components")
    }
    from("tools/release/component-configs") {
        exclude("**/*.jpg", "**/*.png", "**/*.bin", "**/*.img")
        into("configs/components")
    }
    from("tools/release/flash") {
        include("flash-image.yaml")
        into("configs/components/flash-image")
    }
    from("tools/release/emulator-release-windows.yaml") {
        into("configs")
        rename { "emulator.yaml" }
    }
    // Outermost user-facing flash/ and bootrom/ directories (always include bilingual PUT_HERE guides)
    from("tools/release/flash") {
        include("PUT_HERE")
        into("flash")
    }
    from("tools/release/bootrom") {
        include("PUT_HERE")
        into("bootrom")
    }
    // Internal runtime/ binaries, component JARs, binary assets, templates, and native library
    from(layout.buildDirectory.dir("emulator/compoments")) {
        include("*.jar")
        into("runtime/compoments")
    }
    from(layout.buildDirectory.dir("emulator/lenses")) {
        include("*.jar")
        into("lenses")
    }
    from("tools/release/component-configs") {
        include("**/*.jpg", "**/*.png", "**/*.bin", "**/*.img")
        into("runtime/compoments")
    }
    if (bundleStockBins.get()) {
        // Ship only ONE 64 MiB NOR image (runtime/templates/factory-flash/nor-flash-2.13.bin);
        // NorFlashTool.ensureLiveNorExists() seeds flash/nor-flash.bin automatically on first launch.
        from(bootRomOutDir) {
            include("boot-rom.bin", "boot-rom.sha256")
            into("bootrom")
        }
        from(factoryFlashOutDir) {
            include("nor-flash-2.13.bin", "nor-flash-2.13.sha256", "factory-flash-template.yaml", "README.md")
            into("runtime/templates/factory-flash")
        }
    }
    from("tools/release/start-gfx100s.bat")
    from("tools/release/RELEASE-README.md")
    from("LICENSE")
    from("THIRD_PARTY_LICENSES.md")
    doFirst {
        destinationDir.resolve("jdk").takeIf { it.exists() }?.walkTopDown()?.forEach { it.setWritable(true) }
        val dynLib = resolveReleaseNative(
            resolveReleaseDynarmicCandidatesWindows(),
            "Windows x86_64 Dynarmic JIT (mandatory primary CPU engine)",
            "Build it with `./gradlew :cpu-dynarmic:buildDynarmicNativeWindowsCross` (or `./gradlew :cpu-dynarmic:buildDynarmicNative` on Windows).",
        )
        println("Bundling mandatory Windows Dynarmic JIT primary CPU DLL: ${dynLib.path}")
        val audioLib = resolveReleaseNative(
            resolveReleaseAudioCandidatesWindows(),
            "Windows x86_64 Native WASAPI Audio Bridge (mandatory M10 full-duplex audio)",
            "Build it with `./gradlew :buildAudioNativeWindowsCross` (or `./gradlew :buildAudioNative` on Windows).",
        )
        println("Bundling mandatory Windows Native Audio Bridge DLL: ${audioLib.path}")
        val renderLib = resolveReleaseNative(
            resolveReleaseRenderCandidatesWindows(),
            "Windows x86_64 Native SIMD Render Bridge (mandatory PERF-2 render pipeline)",
            "Build it with `./gradlew :buildRenderNativeWindowsCross` (or `bash tools/native/render-bridge/build_render.sh win64`).",
        )
        println("Bundling mandatory Windows Native SIMD Render Bridge DLL: ${renderLib.path}")
        val vulkanOpticsLib = resolveReleaseNative(
            resolveReleaseVulkanOpticsCandidatesWindows(),
            "Windows x86_64 Dynamic Vulkan 1.1 Compute Optics Bridge (mandatory VULKAN-W1 optical pipeline)",
            "Build it with `./gradlew :buildVulkanOpticsNativeWindowsCross` (or `bash tools/native/vulkan-optics/build_vulkan_optics.sh win64`).",
        )
        println("Bundling mandatory Windows Dynamic Vulkan 1.1 Compute Optics Bridge DLL: ${vulkanOpticsLib.path}")
        val ispSimdLib = resolveReleaseNative(
            resolveReleaseIspSimdCandidatesWindows(),
            "Windows x86_64 CPU ISP & Spatial Filter SIMD Bridge (mandatory W11-CPU-ISP-OPTICS-AVX2)",
            "Build it with `./gradlew :buildIspSimdNativeWindowsCross` (or `bash tools/native/isp-simd/build_isp_simd.sh win64`).",
        )
        println("Bundling mandatory Windows CPU ISP & Spatial Filter SIMD DLL: ${ispSimdLib.path}")
        val codecLib = resolveReleaseNative(
            resolveReleaseCodecCandidatesWindows(),
            "Windows x86_64 Native LSRAW & JPEG Codec Bridge (mandatory W11-CODEC-TURBOJPEG-LSRAW)",
            "Build it with `./gradlew :buildCodecNativeWindowsCross` (or `bash tools/native/codec-bridge/build_codec.sh win64`).",
        )
        println("Bundling mandatory Windows Native Codec Bridge DLL: ${codecLib.path}")
        val gamepadLib = resolveReleaseNative(
            resolveReleaseGamepadCandidatesWindows(),
            "Windows x86_64 Native XInput Gamepad Bridge DLL (mandatory INPUT-BINDING-GAMEPAD-W11)",
            "Build it with `./gradlew :buildGamepadNativeWindowsCross` (or `bash tools/native/gamepad-bridge/build_gamepad.sh win64`).",
        )
        println("Bundling mandatory Windows Native Gamepad Bridge DLL: ${gamepadLib.path}")
        val exe = winLauncherOutFile.get().asFile
        require(exe.isFile && exe.length() > 0L) {
            "Missing mandatory Windows native launcher at ${exe.path}. Run :buildWindowsNativeLauncher."
        }
        println("Bundling mandatory Windows native launcher: ${exe.path}")
        if (embedJlinkRuntime.get() && requireWindowsJlink.get()) {
            val jlinkDir = jlinkWindowsOutDir.get().asFile
            require(jlinkDir.resolve("bin/java.exe").isFile) {
                "Mandatory Windows jlink runtime missing bin/java.exe at ${jlinkDir.path} (-PfujiRequireWindowsJlink=true)."
            }
        }
    }
    // Mandatory Windows x86_64 Dynarmic ARMv7-A AVX2 JIT primary CPU DLL (non-optional; Release is Dynarmic-only)
    from({
        listOf(
            resolveReleaseNative(
                resolveReleaseDynarmicCandidatesWindows(),
                "Windows x86_64 Dynarmic JIT (mandatory primary CPU engine)",
                "Build it with `./gradlew :cpu-dynarmic:buildDynarmicNativeWindowsCross` (or `./gradlew :cpu-dynarmic:buildDynarmicNative` on Windows).",
            ),
        )
    }) {
        into("runtime/native")
        rename { "fuji_dynarmic.dll" }
    }
    // Mandatory Windows x86_64 XInput native gamepad bridge DLL (non-optional)
    if (prebuiltWinGamepad == null) {
        from(winGamepadBuildTask) {
            into("runtime/native")
            rename { "fuji_gamepad.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseGamepadCandidatesWindows(),
                    "Windows x86_64 Native XInput Gamepad Bridge DLL (mandatory INPUT-BINDING-GAMEPAD-W11)",
                    "Build it with `./gradlew :buildGamepadNativeWindowsCross` (or `bash tools/native/gamepad-bridge/build_gamepad.sh win64`).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_gamepad.dll" }
        }
    }
    // Mandatory Windows x86_64 WASAPI native audio bridge DLL (non-optional)
    if (prebuiltWinAudio == null) {
        from(winAudioBuildTask) {
            into("runtime/native")
            rename { "fuji_audio.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseAudioCandidatesWindows(),
                    "Windows x86_64 Native WASAPI Audio Bridge (mandatory M10 full-duplex audio)",
                    "Build it with `./gradlew :buildAudioNativeWindowsCross` (or `./gradlew :buildAudioNative` on Windows).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_audio.dll" }
        }
    }
    // Mandatory Windows x86_64 native SIMD render bridge DLL (non-optional)
    if (prebuiltWinRender == null) {
        from(winRenderBuildTask) {
            into("runtime/native")
            rename { "fuji_render.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseRenderCandidatesWindows(),
                    "Windows x86_64 Native SIMD Render Bridge (mandatory PERF-2 render pipeline)",
                    "Build it with `./gradlew :buildRenderNativeWindowsCross` (or `bash tools/native/render-bridge/build_render.sh win64`).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_render.dll" }
        }
    }
    // Mandatory Windows x86_64 dynamic Vulkan 1.1 compute optics bridge DLL (zero extra DLL deps)
    if (prebuiltWinVulkanOptics == null) {
        from(winVulkanOpticsBuildTask) {
            into("runtime/native")
            rename { "fuji_vulkan_optics.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseVulkanOpticsCandidatesWindows(),
                    "Windows x86_64 Dynamic Vulkan 1.1 Compute Optics Bridge (mandatory VULKAN-W1 optical pipeline)",
                    "Build it with `./gradlew :buildVulkanOpticsNativeWindowsCross` (or `bash tools/native/vulkan-optics/build_vulkan_optics.sh win64`).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_vulkan_optics.dll" }
        }
    }
    // Mandatory Windows x86_64 CPU ISP & spatial optical filter SIMD DLL (zero extra DLL deps)
    if (prebuiltWinIspSimd == null) {
        from(winIspSimdBuildTask) {
            into("runtime/native")
            rename { "fuji_isp_simd.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseIspSimdCandidatesWindows(),
                    "Windows x86_64 CPU ISP & Spatial Filter SIMD Bridge (mandatory W11-CPU-ISP-OPTICS-AVX2)",
                    "Build it with `./gradlew :buildIspSimdNativeWindowsCross` (or `bash tools/native/isp-simd/build_isp_simd.sh win64`).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_isp_simd.dll" }
        }
    }
    // Mandatory Windows x86_64 native LSRAW & dynamic libjpeg-turbo codec DLL (zero static DLL deps)
    if (prebuiltWinCodec == null) {
        from(winCodecBuildTask) {
            into("runtime/native")
            rename { "fuji_codec.dll" }
        }
    } else {
        from({
            listOf(
                resolveReleaseNative(
                    resolveReleaseCodecCandidatesWindows(),
                    "Windows x86_64 Native LSRAW & JPEG Codec Bridge (mandatory W11-CODEC-TURBOJPEG-LSRAW)",
                    "Build it with `./gradlew :buildCodecNativeWindowsCross` (or `bash tools/native/codec-bridge/build_codec.sh win64`).",
                ),
            )
        }) {
            into("runtime/native")
            rename { "fuji_codec.dll" }
        }
    }
    // Embedded trimmed Windows JDK 25 runtime from buildJlinkRuntimeWindows (mandatory when -PfujiRequireWindowsJlink=true)
    if (embedJlinkRuntime.get()) {
        from(buildJlinkRuntimeWindows) {
            into("jdk")
            includeEmptyDirs = false
        }
    }
    // Mandatory zero-dependency MinGW-w64 native Windows launcher (fuji-emulator-gfx100s.exe)
    from(buildWindowsNativeLauncher)
    doLast {
        val logsDir = destinationDir.resolve("logs")
        logsDir.mkdirs()
        logsDir.resolve(".keep").writeText("")
        initializeReleaseSdCardDirectories(
            destinationDir,
            project.file("tools/release/component-configs/cmos/test-qr.jpg"),
        )
        // When cross-packaging on Linux/macOS, ensure desktop.bat references Windows JavaFX JARs.
        val bat = destinationDir.resolve("bin/desktop.bat")
        if (bat.isFile) {
            val updated = bat.readText(Charsets.UTF_8)
                .replace("-25.0.1-linux.jar", "-25.0.1-win.jar")
                .replace("-25.0.1-mac-aarch64.jar", "-25.0.1-win.jar")
                .replace("-25.0.1-mac.jar", "-25.0.1-win.jar")
            bat.writeText(updated, Charsets.UTF_8)
        }
    }
}

val verifyReleaseWindows = tasks.register("verifyReleaseWindows") {
    group = "verification"
    description = "Fail-fast Release gate verifying all mandatory Windows x86_64 high-performance native DLLs, native .exe launcher, configs, and required jlink runtime"
    dependsOn(stageReleaseWindows)
    doLast {
        val stageDir = layout.buildDirectory.dir("release-stage/windows-x86_64").get().asFile
        val stockBins = bundleStockBins.get()
        val requiredFiles = mutableListOf(
            "runtime/media/ffmpeg.exe",
            "runtime/media/licenses/COPYING.LGPLv2.1",
            "runtime/media/licenses/build.sh",
            "runtime/media/licenses/SOURCE.txt",
            "runtime/native/fuji_dynarmic.dll",
            "runtime/native/fuji_audio.dll",
            "runtime/native/fuji_render.dll",
            "runtime/native/fuji_vulkan_optics.dll",
            "runtime/native/fuji_isp_simd.dll",
            "runtime/native/fuji_codec.dll",
            "runtime/native/fuji_gamepad.dll",
            "fuji-emulator-gfx100s.exe",
            "start-gfx100s.bat",
            "flash/PUT_HERE",
            "bootrom/PUT_HERE",
            "lib/desktop-${project.version}.jar",
            "lib/camera-artwork-${project.version}.jar",
            "configs/emulator.yaml",
            "configs/firmware/gfx100s-213-release.yaml",
            "configs/firmware/gfx100s-213-bootrom-release.yaml",
            "configs/components/flash-image/flash-image.yaml",
            "configs/components/soc-system-control/boot-strap.json",
            "configs/components/lens/lens.yaml",
            "RELEASE-README.md",
            "LICENSE",
            "THIRD_PARTY_LICENSES.md",
        )
        if (stockBins) {
            requiredFiles += listOf(
                "runtime/templates/factory-flash/nor-flash-2.13.bin",
                "bootrom/boot-rom.bin",
            )
        }
        if (embedJlinkRuntime.get() && requireWindowsJlink.get()) {
            requiredFiles += "jdk/bin/java.exe"
        }
        val missing = requiredFiles.filter { rel ->
            val f = stageDir.resolve(rel)
            !f.isFile || f.length() == 0L
        }
        if (missing.isNotEmpty()) {
            error("verifyReleaseWindows FAILED — missing or empty mandatory release artifact(s) in ${stageDir.path}: ${missing.joinToString(", ")}")
        }
        require(!stageDir.resolve("runtime/media/licenses/ffmpeg-8.1.3.tar.xz").exists()) {
            "verifyReleaseWindows FAILED — ffmpeg-8.1.3.tar.xz must be published as a companion source archive rather than embedded inside the binary bundle"
        }
        require(!stageDir.resolve("runtime/firmware/GXUP0004.DAT").exists()) {
            "verifyReleaseWindows FAILED — Release boots directly from flash/nor-flash.bin and must not bundle redundant GXUP0004.DAT"
        }
        require(!stageDir.resolve("flash/nor-flash.bin").exists()) {
            "verifyReleaseWindows FAILED — staged release must not bundle duplicate flash/nor-flash.bin (first launch seeds it automatically from runtime/templates/factory-flash/nor-flash-2.13.bin)"
        }
        if (stockBins) {
            require(stageDir.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin").length() == 64L * 1024L * 1024L) {
                "verifyReleaseWindows FAILED — runtime/templates/factory-flash/nor-flash-2.13.bin must be exact 64 MiB"
            }
            require(stageDir.resolve("bootrom/boot-rom.bin").length() == 32_768L) {
                "verifyReleaseWindows FAILED — bootrom/boot-rom.bin must be exact 32 KiB"
            }
        } else {
            require(!stageDir.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin").exists()) {
                "verifyReleaseWindows FAILED — -PfujiBundleStockBins=false must omit runtime/templates/factory-flash/nor-flash-2.13.bin"
            }
            require(!stageDir.resolve("bootrom/boot-rom.bin").exists()) {
                "verifyReleaseWindows FAILED — -PfujiBundleStockBins=false must omit bootrom/boot-rom.bin"
            }
        }
        require(!stageDir.resolve("runtime/native/unicorn.dll").exists()) {
            "verifyReleaseWindows FAILED — redundant runtime/native/unicorn.dll must not be bundled in Dynarmic-only Release"
        }
        val forbiddenLogbackJars = stageDir.resolve("lib").listFiles()?.filter { it.name.startsWith("logback-") } ?: emptyList()
        require(forbiddenLogbackJars.isEmpty()) {
            "verifyReleaseWindows FAILED — Logback JAR(s) must not be bundled in MIT Release: ${forbiddenLogbackJars.joinToString { it.name }}"
        }
        val desktopJarLen = stageDir.resolve("lib/desktop-${project.version}.jar").length()
        require(desktopJarLen in 1L..3_000_000L) {
            "verifyReleaseWindows FAILED — expected slim Release desktop JAR (<3 MB), got ${desktopJarLen}B"
        }
        val emulatorYaml = stageDir.resolve("configs/emulator.yaml").readText(Charsets.UTF_8)
        require(Regex("(?m)^cpuEngine:\\s*dynarmic\\s*$").containsMatchIn(emulatorYaml)) {
            "verifyReleaseWindows FAILED — configs/emulator.yaml must enforce `cpuEngine: dynarmic`"
        }
        require(Regex("(?m)^audio:\\s*true\\s*$").containsMatchIn(emulatorYaml)) {
            "verifyReleaseWindows FAILED — configs/emulator.yaml must enforce `audio: true`"
        }
        println(
            "[verifyReleaseWindows] PASS: verified ${requiredFiles.size} mandatory Windows release artifacts " +
                "(fuji_dynarmic.dll=${stageDir.resolve("runtime/native/fuji_dynarmic.dll").length()}B, " +
                "fuji_audio.dll=${stageDir.resolve("runtime/native/fuji_audio.dll").length()}B, " +
                "fuji_render.dll=${stageDir.resolve("runtime/native/fuji_render.dll").length()}B, " +
                "fuji_vulkan_optics.dll=${stageDir.resolve("runtime/native/fuji_vulkan_optics.dll").length()}B, " +
                "fuji_isp_simd.dll=${stageDir.resolve("runtime/native/fuji_isp_simd.dll").length()}B, " +
                "fuji_codec.dll=${stageDir.resolve("runtime/native/fuji_codec.dll").length()}B, " +
                "fuji_gamepad.dll=${stageDir.resolve("runtime/native/fuji_gamepad.dll").length()}B, " +
                "fuji-emulator-gfx100s.exe=${stageDir.resolve("fuji-emulator-gfx100s.exe").length()}B, " +
                "desktop.jar=${desktopJarLen}B, unicorn=excluded)",
        )
    }
}

tasks.register("packageReleaseWindowsJpackage") {
    group = "distribution"
    description = "Build native Windows x86_64 jpackage app-image (runs on Windows JDK 25; on Linux use :packageReleaseWindows with cross-jlink + fuji-emulator-gfx100s.exe)"
    dependsOn(stageReleaseWindows, verifyReleaseWindows, buildJlinkRuntimeWindows)
    outputs.dir(jpackageWindowsOutDir)
    doLast {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        if (!isWin) {
            println("[packageReleaseWindowsJpackage] Native Windows jpackage requires a Windows host. On Linux/macOS, run :packageReleaseWindows for the cross-compiled fuji-emulator-gfx100s.exe + cross-jlink Windows JRE bundle.")
            return@doLast
        }
        val jlinkDir = jlinkWindowsOutDir.get().asFile
        require(jlinkDir.resolve("bin/java.exe").isFile) {
            "Missing Windows jlink runtime at ${jlinkDir.path}"
        }
        val stageDir = layout.buildDirectory.dir("release-stage/windows-x86_64").get().asFile
        val inputLibDir = stageDir.resolve("lib")
        val mainJarName = inputLibDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("desktop-") && it.name.endsWith(".jar") }
            ?.name ?: error("Could not locate desktop-*.jar inside ${inputLibDir.path}")
        val destDir = jpackageWindowsOutDir.get().asFile
        val appDir = destDir.resolve("fuji-emulator-gfx100s")
        appDir.deleteRecursively()
        destDir.mkdirs()

        val jpackage = resolveActiveJdkTool("jpackage")
        val appVer = jpackageNumericVersion(releaseVersion.get())
        runCheckedProcess(
            listOf(
                jpackage.absolutePath,
                "--type", "app-image",
                "--win-console",
                "--name", "fuji-emulator-gfx100s",
                "--app-version", appVer,
                "--vendor", "FUJIFILM GFX100S Emulator Project",
                "--description", "FUJIFILM GFX100S 2.13 Digital Camera Emulator",
                "--input", inputLibDir.absolutePath,
                "--main-jar", mainJarName,
                "--main-class", "com.github.nyabanban.fuji.emulator.desktop.MainKt",
                "--runtime-image", jlinkDir.absolutePath,
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-Xms512m",
                "--java-options", "-Xmx2g",
                "--java-options", "-XX:+UseG1GC",
                "--java-options", "-XX:MaxGCPauseMillis=15",
                "--java-options", "-Dprism.order=d3d,sw",
                "--arguments", "--home",
                "--arguments", "\$APPDIR\\..",
                "--arguments", "--config",
                "--arguments", "\$APPDIR\\..\\configs\\emulator.yaml",
                "--arguments", "--auto-boot",
                "--dest", destDir.absolutePath,
            ),
        )
        for (sub in listOf("configs", "flash", "bootrom", "lenses", "runtime", "logs")) {
            val src = stageDir.resolve(sub)
            if (!src.exists()) continue
            // On Windows jpackage puts the JVM in runtime/. Install the camera's runtime assets alongside it.
            src.copyRecursively(appDir.resolve(sub), overwrite = true)
        }
        check(appDir.resolve("runtime/bin/server/jvm.dll").isFile) { "Packaged Windows JVM is missing" }
        check(appDir.resolve("runtime/media/ffmpeg.exe").isFile) { "Packaged movie decoder is missing" }
        stageDir.resolve("RELEASE-README.md").copyTo(appDir.resolve("RELEASE-README.md"), overwrite = true)
        stageDir.resolve("LICENSE").copyTo(appDir.resolve("LICENSE"), overwrite = true)
        stageDir.resolve("THIRD_PARTY_LICENSES.md").copyTo(appDir.resolve("THIRD_PARTY_LICENSES.md"), overwrite = true)
        stageDir.resolve("start-gfx100s.bat").copyTo(appDir.resolve("start-gfx100s.bat"), overwrite = true)
        println("[packageReleaseWindowsJpackage] Packaged Windows jpackage app-image at ${appDir.path}")
    }
}

tasks.register<Zip>("packageReleaseWindows") {
    group = "distribution"
    description = "Package self-contained one-click Windows x86_64 release (.zip)"
    dependsOn(stageReleaseWindows, verifyReleaseWindows)
    archiveBaseName.set("fuji-emulator-gfx100s")
    archiveVersion.set(releaseVersion)
    archiveClassifier.set("windows-x86_64")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("release-stage/windows-x86_64")) {
        into("fuji-emulator-gfx100s-windows-x86_64")
    }
    doLast {
        stageCompanionPlaybackFfmpegSource(destinationDirectory.get().asFile)
    }
}

tasks.register("verifyActiveDocs") {
    group = "verification"
    description = "Verify maintained active Markdown files have UTF-8/no-BOM/LF/single-final-LF and valid local links."
    doLast {
        val root = rootProject.projectDir.toPath().toAbsolutePath().normalize()
        val excludedDirs = setOf(".git", ".tools", ".gradle", ".kotlin", ".venv", "local", "build", "logs", "node_modules")
        val linkRegex = Regex("!?\\[[^\\]\\r\\n]*\\]\\(([^)\\s]+)")
        val failures = mutableListOf<String>()
        var checkedFiles = 0
        var checkedLinks = 0
        java.nio.file.Files.walk(root).use { stream ->
            stream.filter { path ->
                val rel = root.relativize(path).toString().replace('\\', '/')
                rel.endsWith(".md", ignoreCase = true) &&
                    excludedDirs.none { dir -> rel == dir || rel.startsWith("$dir/") } &&
                    !rel.startsWith("evidence/") &&
                    rel != "docs/generated/devices.md"
            }.sorted().forEach { file ->
                checkedFiles++
                val rel = root.relativize(file).toString().replace('\\', '/')
                val rawBytes = java.nio.file.Files.readAllBytes(file)
                val text = try {
                    java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(rawBytes)).toString()
                } catch (e: Exception) {
                    failures.add("$rel: invalid UTF-8 (${e.javaClass.simpleName})")
                    return@forEach
                }
                if (text.startsWith("\uFEFF")) failures.add("$rel: UTF-8 BOM")
                if ('\r' in text) failures.add("$rel: CR bytes")
                if (!text.endsWith("\n")) failures.add("$rel: missing final LF")
                var inFence = false
                text.lines().forEachIndexed { idx, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                        inFence = !inFence
                        return@forEachIndexed
                    }
                    if (inFence) return@forEachIndexed
                    val noInlineCode = line.replace(Regex("`[^`]*`"), "")
                    linkRegex.findAll(noInlineCode).forEach { match ->
                        val raw = match.groupValues[1].trim('<', '>')
                        if (raw.startsWith("#") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return@forEach
                        val targetPath = raw.substringBefore('#').substringBefore('?')
                        if (targetPath.isEmpty()) return@forEach
                        checkedLinks++
                        val decoded = java.net.URLDecoder.decode(targetPath.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)
                            .replace(Regex(":\\d+(?::\\d+)?$"), "")
                        val resolved = file.parent.resolve(decoded).normalize()
                        if (!java.nio.file.Files.exists(resolved)) {
                            failures.add("$rel:${idx + 1} -> $raw (missing target)")
                        }
                    }
                }
            }
        }
        println("ActiveDocs: $checkedFiles Markdown files, $checkedLinks local links, ${failures.size} failures.")
        failures.forEach { println(it) }
        if (failures.isNotEmpty()) throw GradleException("Active documentation verification failed (${failures.size} failures)")
    }
}

tasks.register("generateHttpApiDocs") {
    group = "documentation"
    description = "Generate deterministic OpenAPI 3.1.0, Markdown, and offline interactive HTML docs for ControlServer HTTP API under docs/api/."
    dependsOn(":runtime:generateHttpApiDocs")
}

tasks.register<JavaExec>("generateApiDocs") {
    group = "documentation"
    description = "Generate offline, zero-network KDoc/Javadoc API reference under build/docs/api/ and HTTP Control API docs under docs/api/."
    dependsOn(":address-catalog:classes", "generateHttpApiDocs")
    val apiModules = listOf("component-api", "runtime", "cpu-dynarmic", "firmware", "address-catalog")
    val outDir = layout.buildDirectory.dir("docs/api")
    apiModules.forEach { mod ->
        inputs.files(fileTree("$mod/src/main") { include("**/*.kt", "**/*.java") })
    }
    outputs.dir(outDir)
    classpath = project(":address-catalog").extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass.set("com.github.nyabanban.fuji.emulator.docs.OfflineKdocApiGeneratorKt")
    doFirst {
        args(
            listOf(
                rootProject.projectDir.absolutePath,
                outDir.get().asFile.absolutePath,
            ) + apiModules,
        )
    }
}

tasks.register("quickGate") {
    group = "verification"
    description = "Quick QA gate: catalog verification, active docs/text checks, core module tests, and f10T1* hygiene E2E."
    dependsOn(
        ":address-catalog:verifyCatalog",
        ":verifyActiveDocs",
        ":component-api:test",
        ":cpu-dynarmic:test",
        ":components:sdcard-reader:test",
        ":components:soc-sd-host:test",
        ":components:soc-jpeg:test",
        ":components:cmos:test",
        ":runtime:quickGateRuntimeTest",
        ":desktop:quickGateHygieneTest",
    )
}
