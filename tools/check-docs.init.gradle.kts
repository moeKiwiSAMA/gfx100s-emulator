import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.net.URLDecoder

// Human-written Markdown links only. No global Kotlin CLI and no network requests.
fun documentationJson(value: Any?): String {
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
    return when(value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + documentationJson(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { documentationJson(it) }
        else -> error("Unsupported documentation report value")
    }
}

fun documentationTargets(line: String): List<String> {
    // Strip inline code; literal examples are not rendered Markdown links.
    val visible = StringBuilder()
    var index = 0
    while (index < line.length) {
        if (line[index] == '\u0060') {
            var end = index
            while (end < line.length && line[end] == '\u0060') end++
            val marker = line.substring(index, end)
            val close = line.indexOf(marker, end)
            if (close >= 0) { visible.append(" ".repeat(close + marker.length - index)); index = close + marker.length; continue }
        }
        visible.append(line[index++])
    }
    val text = visible.toString()
    val results = mutableListOf<String>()
    fun target(start: Int): String? {
        if (start >= text.length) return null
        if (text[start] == '<') {
            val close = text.indexOf('>', start + 1)
            return if (close < 0) null else text.substring(start + 1, close)
        }
        var cursor = start
        var depth = 0
        val out = StringBuilder()
        while (cursor < text.length) {
            val c = text[cursor++]
            if (c == '\\' && cursor < text.length && text[cursor] in "\\()[]*{}#!_+.-> ") { out.append(text[cursor++]); continue }
            if (c == '(') depth++
            if (c == ')') { if (depth == 0) break; depth-- }
            if (c.isWhitespace() && depth == 0) break
            out.append(c)
        }
        return out.toString().ifEmpty { null }
    }
    Regex("!?\\[[^\\]\\r\\n]*\\]\\(").findAll(text).forEach { match -> target(match.range.last + 1)?.let(results::add) }
    Regex("^ {0,3}\\[[^\\]]+\\]:\\s*").find(text)?.let { match -> target(match.range.last + 1)?.let(results::add) }
    return results
}

gradle.projectsEvaluated {
    val owner = rootProject
    owner.tasks.register("verifyDocumentation") {
        group = "verification"
        description = "Checks maintained Markdown UTF-8/LF and links; raw archive failures remain separate."
        doLast {
            val root = Path.of(owner.providers.gradleProperty("documentationRoot").getOrElse(owner.projectDir.absolutePath)).toAbsolutePath().normalize()
            val excludedNames = setOf(".git", ".tools", ".gradle", ".kotlin", ".venv", "local", "build", "logs", "node_modules")
            val markdown = mutableListOf<Path>()
            val failures = mutableListOf<Map<String, Any>>()
            var links = 0
            var urls = 0
            var anchors = 0
            var fences = 0
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
                    val relative = root.relativize(dir).toString().replace('\\', '/')
                    return if (dir != root && (dir.fileName.toString() in excludedNames || Files.isSymbolicLink(dir))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (file.fileName.toString().endsWith(".md", ignoreCase = true)) markdown.add(file)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                    failures.add(mapOf("file" to root.relativize(file).toString().replace('\\', '/'), "line" to 0, "target" to "", "reason" to failure.javaClass.simpleName))
                    return FileVisitResult.CONTINUE
                }
            })
            markdown.sorted().forEach { file ->
                var fence: Char? = null
                var fenceLength = 0
                val text = try { Files.readString(file, StandardCharsets.UTF_8) } catch (failure: Exception) {
                    failures.add(mapOf("file" to root.relativize(file).toString().replace('\\', '/'), "line" to 0, "target" to "", "reason" to failure.javaClass.simpleName))
                    return@forEach
                }
                val formatIssues = buildList {
                    if (text.startsWith("\uFEFF")) add("UTF-8 BOM")
                    if ('\r' in text) add("CR bytes")
                    if (!text.endsWith("\n")) add("missing final LF")
                }
                formatIssues.forEach { issue -> failures.add(mapOf("file" to root.relativize(file).toString().replace('\\', '/'), "line" to 0, "target" to "", "reason" to issue)) }
                text.lines().forEachIndexed { lineIndex, line ->
                    val marker = Regex("^ {0,3}([\u0060]{3,}|~{3,})").find(line)?.groupValues?.get(1)
                    if (marker != null && fence == null) { fence = marker[0]; fenceLength = marker.length; fences++; return@forEachIndexed }
                    if (fence != null) {
                        if (marker != null && marker[0] == fence && marker.length >= fenceLength && line.trim().all { it == fence }) fence = null
                        return@forEachIndexed
                    }
                    documentationTargets(line).forEach targetLoop@ { raw ->
                        if (raw.startsWith('#')) { anchors++; return@targetLoop }
                        val windowsDrive = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(raw)
                        if (!windowsDrive && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) { urls++; return@targetLoop }
                        val pathText = raw.substringBefore('#').substringBefore('?')
                        if (pathText.isEmpty()) { anchors++; return@targetLoop }
                        links++
                        try {
                            val decoded = URLDecoder.decode(pathText.replace("+", "%2B"), StandardCharsets.UTF_8)
                            val withoutLine = decoded.replace(Regex(":\\d+(?::\\d+)?$"), "")
                            val candidate = Path.of(withoutLine)
                            val resolved = (if (candidate.isAbsolute) candidate else file.parent.resolve(candidate)).normalize()
                            if (!Files.exists(resolved)) failures.add(mapOf("file" to root.relativize(file).toString().replace('\\', '/'), "line" to lineIndex + 1, "target" to raw, "reason" to "missing local file or directory"))
                        } catch (error: Exception) {
                            failures.add(mapOf("file" to root.relativize(file).toString().replace('\\', '/'), "line" to lineIndex + 1, "target" to raw, "reason" to error.javaClass.simpleName))
                        }
                    }
                }
            }
            // This published README records an old machine-local input. Preserve its
            // bytes, and classify only that exact unavailable link as historical.
            val preservedReadme="evidence/refactor/sd-catalog-evidence-new-01/README.md"
            val preservedBlob="c8c80a86d1b7e8c45daffd324550d054f415b102"
            fun gitMetadata(vararg arguments:String):Pair<Int,String> {
                val process=ProcessBuilder(listOf("git","-C",root.toString())+arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                val output=process.inputStream.use {it.readAllBytes()}.toString(StandardCharsets.UTF_8).trim()
                return process.waitFor() to output
            }
            val baseline=gitMetadata("rev-parse","--verify","--quiet","9af84bf0af679f9e6eed93b77f0601ab3e11f0d6:"+preservedReadme)
            val indexed=gitMetadata("ls-files","--stage","--",preservedReadme)
            val unchanged=gitMetadata("diff","--quiet","--",preservedReadme).first==0
            val preserved=Files.exists(root.resolve(preservedReadme)) && baseline== (0 to preservedBlob) && indexed.first==0 &&
                indexed.second=="100644 "+preservedBlob+" 0\t"+preservedReadme && unchanged
            fun preservedLink(row:Map<String,Any>,sameBytes:Boolean)=sameBytes && row["file"]==preservedReadme &&
                row["target"]=="../../../local/discovery-original-01/discovery-cap0/original.generated.json" &&
                row["reason"]=="missing local file or directory"
            val example=mapOf<String,Any>("file" to preservedReadme,"target" to "../../../local/discovery-original-01/discovery-cap0/original.generated.json","reason" to "missing local file or directory")
            check(preservedLink(example,true) && !preservedLink(example,false) &&
                !preservedLink(example+("file" to preservedReadme+"-new"),true) &&
                !preservedLink(example+("target" to "other-missing.json"),true) &&
                !preservedLink(example+("reason" to "missing final LF"),true))
            fun isPlanAArchivedTarget(row: Map<String, Any>): Boolean {
                if (row["reason"] != "missing local file or directory") return false
                val file = row["file"].toString()
                val historicalSource = file.startsWith("evidence/tooling/") ||
                    file.startsWith("evidence/runs/") ||
                    (file.startsWith("docs/history/") && file != "docs/history/README.md") ||
                    file == "docs/generated/devices.md"
                return historicalSource
            }
            fun archived(row: Map<String, Any>): Boolean {
                val file = row["file"].toString()
                return file.startsWith("evidence/tooling/") || file.startsWith("evidence/runs/") || preservedLink(row,preserved) || isPlanAArchivedTarget(row)
            }
            val currentFailures = failures.filterNot(::archived)
            val historicalFailures = failures.filter(::archived)
            val archivedFiles = markdown.count { val name = root.relativize(it).toString().replace('\\', '/'); name.startsWith("evidence/tooling/") || name.startsWith("evidence/runs/") || (preserved && name==preservedReadme) }
            val report = linkedMapOf<String, Any>(
                "schema" to 2, "backend" to "Gradle Kotlin init script", "task" to "verifyDocumentation",
                "scope" to "Maintained Markdown UTF-8/BOM/LF/final-LF and local link existence, including edited history and generated references. Raw archive failures are separate; no URL/anchor/rendering validation.",
                "excludedDirectories" to excludedNames.sorted() + listOf("directory symlinks"),
                "markdownFiles" to markdown.size, "maintainedMarkdownFiles" to markdown.size - archivedFiles,
                "archivalMarkdownFiles" to archivedFiles, "localLinksChecked" to links, "urlsSkipped" to urls, "anchorsSkipped" to anchors,
                "fencedBlocksSkipped" to fences, "passed" to currentFailures.isEmpty(), "failures" to currentFailures,
                "historicalArchiveFailures" to historicalFailures, "historicalArchiveLinksPassed" to historicalFailures.isEmpty(),
                "preservedRefactorLink" to mapOf("path" to preservedReadme,"baselineBlob" to preservedBlob,"indexAndWorktreeUnchanged" to preserved,"classificationSelfChecks" to 5)
            )
            val configuredOutput = Path.of(owner.providers.gradleProperty("documentationReport").getOrElse("local/documentation-check.generated.json"))
            val output = (if (configuredOutput.isAbsolute) configuredOutput else root.resolve(configuredOutput)).normalize()
            require(output.startsWith(root)) { "Documentation report must remain in selected repository" }
            Files.createDirectories(output.parent)
            Files.writeString(output, documentationJson(report) + "\n", StandardCharsets.UTF_8)
            println("Documentation: " + markdown.size + " Markdown files, " + links + " local links, " + currentFailures.size + " current failures, " + historicalFailures.size + " archived failures.")
            currentFailures.forEach { println(it["file"].toString() + ":" + it["line"] + " -> " + it["target"] + " (" + it["reason"] + ")") }
            if (currentFailures.isNotEmpty()) throw GradleException("Documentation verification failed; see " + root.relativize(output))

        }
    }
}
