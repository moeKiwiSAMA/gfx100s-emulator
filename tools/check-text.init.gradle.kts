import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// Read-only policy audit. No file normalization and no host-dependent newline output.
fun policyJson(value: Any?): String {
    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        } }
        append('"')
    }
    return when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + policyJson(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { policyJson(it) }
        else -> error("Unsupported policy JSON value")
    }
}
fun policyGlob(pattern: String): Regex {
    val out = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        if (pattern[index] == '*') {
            if (index + 1 < pattern.length && pattern[index + 1] == '*') { out.append(".*"); index += 2 }
            else { out.append("[^/]*"); index++ }
        } else { out.append(Regex.escape(pattern[index].toString())); index++ }
    }
    return Regex(out.append('$').toString())
}
fun inspectProjectText(input: InputStream): Map<String, Any> {
    val issues = mutableListOf<String>()
    var characters = 0L
    var carriageReturns = 0L
    var last = -1
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        val reader = InputStreamReader(input, decoder)
        val chunk = CharArray(8192)
        while (true) {
            val count = reader.read(chunk)
            if (count < 0) break
            for (index in 0 until count) {
                val c = chunk[index]
                if (characters == 0L && c == '\uFEFF') issues.add("UTF-8 BOM")
                if (c == '\r') carriageReturns++
                characters++
                last = c.code
            }
        }
    } catch (failure: java.nio.charset.CharacterCodingException) {
        issues.add("invalid UTF-8: " + failure.javaClass.simpleName)
    }
    if (carriageReturns != 0L) issues.add("CR bytes")
    if (last != 10) issues.add("missing final LF")
    return linkedMapOf("characters" to characters, "carriageReturns" to carriageReturns, "issues" to issues)
}
fun policyGitBlob(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(("blob " + Files.size(file) + "\u0000").toByteArray(StandardCharsets.US_ASCII))
    Files.newInputStream(file).use { input ->
        val chunk = ByteArray(65536)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            digest.update(chunk, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun policyCommand(root: Path, git: String, vararg args: String): ByteArray {
    val process = ProcessBuilder(listOf(git, "-c", "core.longpaths=true") + args).directory(root.toFile()).start()
    val errors = java.io.ByteArrayOutputStream()
    val errorReader = Thread.ofPlatform().start { process.errorStream.use { it.copyTo(errors) } }
    val bytes = process.inputStream.use { it.readAllBytes() }
    val exit = process.waitFor()
    errorReader.join()
    check(exit == 0) { "Git exit " + exit + ": " + errors.toString(StandardCharsets.UTF_8) }
    return bytes
}
class PolicyBoundedInput(private val source: InputStream, length: Long) : InputStream() {
    var remaining = length
        private set
    override fun read(): Int {
        if (remaining == 0L) return -1
        val result = source.read()
        check(result >= 0) { "Truncated git blob" }
        remaining--
        return result
    }
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = source.read(target, offset, minOf(length.toLong(), remaining).toInt())
        check(count >= 0) { "Truncated git blob" }
        remaining -= count
        return count
    }
    override fun close() { /* Parent batch owns the stream. */ }
}
fun policyLine(input: InputStream): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        check(b >= 0) { "Truncated git batch header" }
        if (b == 10) break
        check(bytes.size() < 4096) { "Oversized git batch header" }
        bytes.write(b)
    }
    return bytes.toString(StandardCharsets.US_ASCII)
}
fun historicalPayloadNeedsRead(expectedBlob: String, indexedBlob: String?, worktreeChanged: Boolean): Boolean =
    indexedBlob != expectedBlob || worktreeChanged

fun isProjectText(name: String, textNames: Set<String>, textExtensions: Set<String>, binaryNames: Set<String>, binaryExtensions: Set<String>): Boolean {
    val leaf = name.substringAfterLast('/')
    val extension = leaf.substringAfterLast('.', "").lowercase()
    return leaf in textNames || extension in textExtensions || name.contains("/META-INF/services/") ||
        (leaf !in binaryNames && extension !in binaryExtensions)
}

fun policySelfChecks(): Map<String, Boolean> {
    fun inspect(bytes: ByteArray) = inspectProjectText(ByteArrayInputStream(bytes))["issues"] as List<*>
    fun issues(text: String) = inspect(text.toByteArray(StandardCharsets.UTF_8))
    return linkedMapOf(
        "unchangedHistoricalPayloadSkipped" to !historicalPayloadNeedsRead("a", "a", false),
        "unstagedHistoricalEditRead" to historicalPayloadNeedsRead("a", "a", true),
        "stagedHistoricalEditRead" to historicalPayloadNeedsRead("a", "b", false),
        "removedHistoricalPathDetected" to historicalPayloadNeedsRead("a", null, true),
        "lfAccepted" to issues("plain\n").isEmpty(),
        "exactSyntheticContainerIsBinary" to !isProjectText("new/home/synthetic.update", emptySet(), setOf("json"), setOf("synthetic.update"), emptySet()),
        "neighborUpdateIsNotExempt" to isProjectText("new/home/config.update", emptySet(), setOf("json"), setOf("synthetic.update"), emptySet()),
        "neighborJsonIsNotExempt" to isProjectText("new/home/synthetic.update.json", emptySet(), setOf("json"), setOf("synthetic.update"), emptySet()),
        "utf8NonAsciiAccepted" to issues("富士\n").isEmpty(),
        "crlfRejected" to issues("x\r\n").contains("CR bytes"),
        "isolatedCrRejected" to issues("x\r").contains("CR bytes"),
        "bomRejected" to issues("\uFEFFx\n").contains("UTF-8 BOM"),
        "missingFinalLfRejected" to issues("x").contains("missing final LF"),
        "emptyTextRejected" to issues("").contains("missing final LF"),
        "invalidUtf8Rejected" to inspect(byteArrayOf(0xC3.toByte(), 0x28, 0x0A)).any { it.toString().startsWith("invalid UTF-8") },
        "recursivePatternAdmitsNestedHistoricalPath" to policyGlob("evidence/runs/**").matches("evidence/runs/old/source/a.kt"),
        "exactPatternDoesNotExemptNewNeighbor" to !policyGlob("docs/research-migration-map.json").matches("docs/research-migration-map-new.json"),
        "singleGlobCannotEscapeDirectory" to !policyGlob("docs/audits/*.json").matches("docs/audits/nested/new.json")
    )
}
gradle.projectsEvaluated {
    val owner = rootProject
    owner.tasks.register("verifyProjectText") {
        group = "verification"
        description = "Checks maintained/generated text; protects historical baseline paths through Git index and changed-path inspection."
        doLast {
            val root = Path.of(owner.providers.gradleProperty("textPolicyRoot").getOrElse(owner.projectDir.absolutePath)).toAbsolutePath().normalize()
            @Suppress("UNCHECKED_CAST")
            val policy = JsonSlurper().parse(root.resolve("text-policy.json").toFile(), "UTF-8") as Map<String, Any?>
            check((policy["schema"] as Number).toInt() == 1)
            fun strings(name: String) = (policy[name] as List<*>).map { it as String }
            val git = owner.providers.gradleProperty("textPolicyGit").getOrElse("git")
            val configuredBase = policy["immutableBaseCommit"] as String
            check(configuredBase.matches(Regex("[0-9a-f]{40}"))) { "Immutable baseline must be a full pinned commit" }
            val hasConfiguredBase = ProcessBuilder(git, "-c", "core.longpaths=true", "cat-file", "-e", "$configuredBase^{tree}")
                .directory(root.toFile()).start().waitFor() == 0
            val base = if (hasConfiguredBase) configuredBase else policyCommand(root, git, "rev-parse", "HEAD").toString(StandardCharsets.US_ASCII).trim()
            val patterns = strings("immutablePatterns").map(::policyGlob)
            fun protectedPath(name: String) = patterns.any { it.matches(name) }
            val extensions = strings("textExtensions").map(String::lowercase).toSet()
            val names = strings("textNames").toSet()
            val binaryExtensions = strings("binaryExtensions").toSet()
            val compilerCaches = strings("generatedCompilerCachePatterns").map(::policyGlob)
            val binaryNames = strings("binaryNames").toSet()
            fun textPath(name: String): Boolean = isProjectText(name, names, extensions, binaryNames, binaryExtensions)
            val treeRows = policyCommand(root, git, "ls-tree", "-rz", base).toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
            val baseline = treeRows.associate { row -> val split = row.indexOf('\t'); row.substring(split + 1) to row.substring(0, split).split(' ')[2] }
                .filterKeys(::protectedPath)
            val indexRows = policyCommand(root, git, "ls-files", "--stage", "-z").toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
            val index = indexRows.associate { row ->
                val split = row.indexOf('\t'); val header = row.substring(0, split).split(' ')
                check(header[2] == "0") { "Unmerged Git index cannot qualify" }
                row.substring(split + 1) to header[1]
            }
            val worktreeNames = policyCommand(root, git, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                .toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty).toSortedSet()
            // Git supplies the changed path set; untouched historical payload bytes are not read.
            val worktreeChanged = policyCommand(root, git, "diff", "--no-ext-diff", "--name-only", "-z", "--ignore-submodules")
                .toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty).toSet()
            val head = policyCommand(root, git, "rev-parse", "HEAD").toString(StandardCharsets.US_ASCII).trim()
            val failures = mutableListOf<Map<String, Any?>>()
            val selfChecks = policySelfChecks()
            selfChecks.filterValues { !it }.keys.forEach { failures.add(mapOf("scope" to "checker", "path" to it, "issues" to listOf("self-check failed"))) }
            var immutableChangedBytes = 0L
            var immutableChangedFiles = 0
            var immutableUnchangedFiles = 0
            baseline.forEach { (name, blob) ->
                val indexed = index[name]
                if (indexed != blob) failures.add(mapOf("scope" to "immutable-index", "path" to name, "expectedGitBlob" to blob, "actualGitBlob" to indexed, "issues" to listOf("published original removed or changed relative to baseline")))
                if (!historicalPayloadNeedsRead(blob, indexed, name in worktreeChanged)) {
                    immutableUnchangedFiles++
                } else {
                    val file = root.resolve(name)
                    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                        failures.add(mapOf("scope" to "immutable-worktree", "path" to name, "issues" to listOf("baseline original absent or symlink; use the committed post-migration baseline after reviewed source-copy removal")))
                    } else {
                        val actual = policyGitBlob(file)
                        immutableChangedBytes += Files.size(file)
                        immutableChangedFiles++
                        if (actual != blob) failures.add(mapOf("scope" to "immutable-worktree", "path" to name, "expectedGitBlob" to blob, "actualGitBlob" to actual, "issues" to listOf("published original bytes changed")))
                    }
                }
            }
            var worktreeFiles = 0
            var indexFiles = 0
            var generatedFiles = 0
            val checkedPaths = mutableSetOf<Path>()
            fun inspectFile(scope: String, name: String, file: Path) {
                if (!Files.isRegularFile(file)) return
                if (Files.isSymbolicLink(file)) {
                    failures.add(mapOf("scope" to scope, "path" to name, "issues" to listOf("text symlink is not a qualified contained file")))
                    return
                }
                val result = Files.newInputStream(file).use(::inspectProjectText)
                val issues = result["issues"] as List<*>
                if (issues.isNotEmpty()) failures.add(mapOf("scope" to scope, "path" to name, "issues" to issues, "carriageReturns" to result["carriageReturns"]))
            }
            worktreeNames.forEach { name ->
                val file = root.resolve(name).normalize()
                check(file.startsWith(root)) { "Path outside repository" }
                if (textPath(name) && Files.isRegularFile(file) && (name !in baseline || index[name] != baseline[name] || name in worktreeChanged)) {
                    inspectFile("worktree", name, file)
                    worktreeFiles++
                    checkedPaths.add(file)
                }
            }
            val indexedTexts = index.filter { (name, blob) -> textPath(name) && baseline[name] != blob }.toSortedMap()
            if (indexedTexts.isNotEmpty()) {
                val process = ProcessBuilder(git, "-c", "core.longpaths=true", "cat-file", "--batch").directory(root.toFile()).start()
                val errors = java.io.ByteArrayOutputStream()
                val errorReader = Thread.ofPlatform().start { process.errorStream.use { it.copyTo(errors) } }
                val writer = Thread.ofPlatform().start {
                    process.outputStream.use { stream -> indexedTexts.values.forEach { stream.write((it + "\n").toByteArray(StandardCharsets.US_ASCII)) } }
                }
                process.inputStream.buffered().use { input ->
                    indexedTexts.forEach { (name, blob) ->
                        val header = policyLine(input).split(' ')
                        check(header.size == 3 && header[0] == blob && header[1] == "blob") { "Unexpected git batch identity" }
                        val bounded = PolicyBoundedInput(input, header[2].toLong())
                        val result = inspectProjectText(bounded)
                        val chunk = ByteArray(8192)
                        while (bounded.read(chunk) >= 0) { /* Consume malformed trailing bytes without losing batch framing. */ }
                        check(input.read() == 10) { "Missing git batch delimiter" }
                        val issues = result["issues"] as List<*>
                        if (issues.isNotEmpty()) failures.add(mapOf("scope" to "index", "path" to name, "issues" to issues, "carriageReturns" to result["carriageReturns"]))
                        indexFiles++
                    }
                }
                writer.join()
                val exit = process.waitFor()
                errorReader.join()
                check(exit == 0) { "Git cat-file failed: " + errors.toString(StandardCharsets.UTF_8) }
            }
            val requested = owner.providers.gradleProperty("textGeneratedRoots").orNull
            val generatedRoots = (if (requested == null) strings("defaultGeneratedRoots") else requested.split(',').filter(String::isNotBlank)).toMutableSet()
            val excludedDirectories = strings("generatedExcludedDirectoryNames").toSet()
            if (requested == null && policy["discoverBuildTrees"] == true) {
                val discoveryExcluded = strings("generatedDiscoveryExcludedRoots").toSet()
                Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (dir == root) return java.nio.file.FileVisitResult.CONTINUE
                        val relative = root.relativize(dir)
                        if (dir.fileName.toString() in excludedDirectories || relative.getName(0).toString() in discoveryExcluded || Files.isSymbolicLink(dir)) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                        if (dir.fileName.toString() == "build") {
                            generatedRoots.add(relative.toString().replace('\\', '/'))
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
            generatedRoots.sorted().forEach { relative ->
                val directory = root.resolve(relative).normalize()
                check(directory.startsWith(root) && directory != root) { "Generated root must be a contained directory: " + relative }
                if (!Files.exists(directory)) return@forEach
                check(!Files.isSymbolicLink(directory)) { "Generated root is a symlink" }
                Files.walkFileTree(directory, object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        return if (dir != directory && (dir.fileName.toString() in excludedDirectories || Files.isSymbolicLink(dir))) java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        val name = root.relativize(file).toString().replace('\\', '/')
                        if (textPath(name) && compilerCaches.none { it.matches(name) } && checkedPaths.add(file.normalize()) && (name !in baseline || index[name] != baseline[name] || name in worktreeChanged)) {
                            inspectFile("generated", name, file)
                            generatedFiles++
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
            val report = linkedMapOf<String, Any?>(
                "schema" to 1, "backend" to "Gradle Kotlin init script", "task" to "verifyProjectText",
                "passed" to failures.isEmpty(), "gitCommit" to head, "policyPath" to "text-policy.json",
                "immutableBaseCommit" to base, "immutableCheckMode" to "git-baseline-index-diff",
                "immutablePathsProtected" to baseline.size, "immutableUnchangedPathsSkipped" to immutableUnchangedFiles,
                "immutableChangedFilesRead" to immutableChangedFiles, "immutableChangedBytesRead" to immutableChangedBytes,
                "workingTreeTextFiles" to worktreeFiles, "indexTextFiles" to indexFiles, "generatedTextFiles" to generatedFiles,
                "generatedRoots" to generatedRoots, "selfChecks" to selfChecks, "failures" to failures,
                "scope" to "Git baseline/index identities and the changed-path set protect historical raw; unchanged historical bytes are not re-read. Maintained, added and changed text plus selected generated text are checked independently. No automatic repair or source copies."
            )
            val configuredOutput = Path.of(owner.providers.gradleProperty("textPolicyReport").getOrElse("local/text-policy-check.generated.json"))
            val output = (if (configuredOutput.isAbsolute) configuredOutput else root.resolve(configuredOutput)).toFile()
            check(output.toPath().toAbsolutePath().normalize().startsWith(root)) { "Report must remain in repository" }
            output.parentFile.mkdirs()
            Files.writeString(output.toPath(), policyJson(report) + "\n", StandardCharsets.UTF_8)
            println("Project text: " + worktreeFiles + " worktree, " + indexFiles + " index, " + generatedFiles + " generated, " + immutableUnchangedFiles + " unchanged historical paths skipped, " + immutableChangedFiles + " changed historical payloads read; failures=" + failures.size)
            failures.take(40).forEach { println(it["scope"].toString() + ": " + it["path"] + " " + it["issues"]) }
            if (failures.isNotEmpty()) throw GradleException("Text policy verification failed; see " + root.relativize(output.toPath().toAbsolutePath().normalize()))
        }
    }
}
