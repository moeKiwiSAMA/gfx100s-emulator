package com.github.nyabanban.fuji.emulator.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline-safe, zero-external-network KDoc and Javadoc API reference generator.
 *
 * Extracts public Kotlin and Java declarations plus their KDoc/Javadoc comments from core
 * emulator modules (`component-api`, `runtime`, `cpu-dynarmic`, `firmware`, `address-catalog`)
 * and emits deterministic, self-contained HTML, Markdown, CSS, and JSON documentation under
 * `build/docs/api/` without requiring Dokka or mutating `--offline` Gradle lockfiles.
 */
object OfflineKdocApiGenerator {

    /** Default core API modules documented by `:generateApiDocs`. */
    val DEFAULT_MODULES: List<String> = listOf(
        "component-api",
        "runtime",
        "cpu-dynarmic",
        "firmware",
        "address-catalog",
    )

    /** Parsed KDoc / Javadoc comment with summary body and block tags. */
    data class ParsedDoc(
        val summary: String,
        val details: String,
        val params: List<Pair<String, String>>,
        val returns: String?,
        val throwsList: List<Pair<String, String>>,
        val seeAlso: List<String>,
    )

    /** A public member (function, property, constant, or field) belonging to a type or file facade. */
    data class ApiMember(
        val kind: String,
        val name: String,
        val signature: String,
        val doc: ParsedDoc?,
        val line: Int,
    )

    /** A public top-level or nested type (or file facade for top-level functions/properties). */
    data class ApiType(
        val module: String,
        val packageName: String,
        val kind: String,
        val name: String,
        val signature: String,
        val sourceRelPath: String,
        val line: Int,
        val doc: ParsedDoc?,
        val members: List<ApiMember>,
    ) {
        /** Fully-qualified name (`packageName.name`). */
        val fqName: String get() = if (packageName.isEmpty()) name else "$packageName.$name"
    }

    /** Summary statistics produced after generating the offline API documentation bundle. */
    data class GenerationReport(
        val modules: List<String>,
        val totalFilesScanned: Int,
        val totalTypes: Int,
        val totalMembers: Int,
        val documentedTypes: Int,
        val outputDir: Path,
    )

    private val kotlinTypeRegex = Regex(
        """^(?:(?:public|abstract|final|open|sealed|data|value|inline|inner|enum|annotation|fun|expect|actual)\s+)*""" +
            """(interface|class|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val kotlinMemberRegex = Regex(
        """^(?:(?:public|override|open|abstract|final|suspend|inline|operator|infix|tailrec|external|const|lateinit)\s+)*""" +
            """(fun|val|var)\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_.,<>\s?*]+\.)?([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val javaTypeRegex = Regex(
        """^public\s+(?:(?:static|final|abstract|sealed|non-sealed)\s+)*""" +
            """(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val javaMemberRegex = Regex(
        """^(?:public\s+)?(?:(?:static|final|synchronized|default|abstract|native)\s+)*""" +
            """(?:<[^>]+>\s+)?[A-Za-z0-9_$.\[\]<>?, ]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|=|;)""",
    )

    /**
     * Parses a KDoc or Javadoc block comment into structured summary, details, and `@tag` fields.
     *
     * @param rawComment comment text including opening slash-star-star and closing star-slash
     * @return structured [ParsedDoc], or `null` if the comment is empty
     */
    fun parseDocComment(rawComment: String): ParsedDoc? {
        val inner = rawComment.trim()
            .removePrefix("/**")
            .removeSuffix("*/")
        val cleaned = inner.lines().map { line ->
            line.trim().removePrefix("*").let { if (it.startsWith(" ")) it.substring(1) else it }.trimEnd()
        }
        val bodyLines = mutableListOf<String>()
        val params = mutableListOf<Pair<String, String>>()
        val throwsList = mutableListOf<Pair<String, String>>()
        val seeAlso = mutableListOf<String>()
        var returns: String? = null

        var activeTag: String? = null
        val activeBuf = StringBuilder()

        fun flushTag() {
            val tag = activeTag ?: return
            val text = activeBuf.toString().trim()
            activeTag = null
            activeBuf.clear()
            if (text.isEmpty()) return
            when (tag) {
                "@param" -> {
                    val parts = text.split(Regex("\\s+"), limit = 2)
                    params.add(parts[0] to (parts.getOrElse(1) { "" }))
                }
                "@return" -> returns = text
                "@throws", "@exception" -> {
                    val parts = text.split(Regex("\\s+"), limit = 2)
                    throwsList.add(parts[0] to (parts.getOrElse(1) { "" }))
                }
                "@see" -> seeAlso.add(text)
            }
        }

        for (line in cleaned) {
            val trimmed = line.trim()
            if (trimmed.startsWith("@")) {
                flushTag()
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                activeTag = parts[0]
                activeBuf.append(parts.getOrElse(1) { "" })
            } else if (activeTag != null) {
                if (trimmed.isNotEmpty()) {
                    if (activeBuf.isNotEmpty()) activeBuf.append(' ')
                    activeBuf.append(trimmed)
                }
            } else {
                bodyLines.add(line)
            }
        }
        flushTag()

        val bodyText = bodyLines.joinToString("\n").trim()
        if (bodyText.isEmpty() && params.isEmpty() && returns == null && throwsList.isEmpty() && seeAlso.isEmpty()) {
            return null
        }
        val paragraphs = bodyText.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val summary = paragraphs.firstOrNull()?.replace(Regex("\\s+"), " ") ?: ""
        val details = paragraphs.drop(1).joinToString("\n\n")
        return ParsedDoc(summary, details, params, returns, throwsList, seeAlso)
    }

    /**
     * Parses a single Kotlin or Java source file into public [ApiType] declarations.
     *
     * @param module Gradle module name
     * @param sourceRelPath repository-relative POSIX path to the source file
     * @param content UTF-8 source text
     * @return list of extracted public types and file-level facades
     */
    fun parseSourceFile(module: String, sourceRelPath: String, content: String): List<ApiType> {
        val isJava = sourceRelPath.endsWith(".java")
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val packageName = lines.firstOrNull { it.trimStart().startsWith("package ") }
            ?.trimStart()?.removePrefix("package ")?.substringBefore(';')?.trim().orEmpty()

        val types = mutableListOf<ApiType>()
        val topLevelMembers = mutableListOf<ApiMember>()
        var currentType: MutableTypeBuilder? = null
        var braceDepth = 0
        var typeBraceDepth = -1

        var i = 0
        var pendingDoc: ParsedDoc? = null
        var pendingAnnotations = mutableListOf<String>()

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            // KDoc / Javadoc comment block
            if (trimmed.startsWith("/**")) {
                val docBuf = StringBuilder(trimmed)
                var j = i
                while (!trimmed.endsWith("*/") || (j == i && trimmed == "/**")) {
                    if (j > i && lines[j].trim().endsWith("*/")) {
                        docBuf.append('\n').append(lines[j])
                        break
                    }
                    if (j > i) docBuf.append('\n').append(lines[j])
                    j++
                    if (j >= lines.size) break
                }
                pendingDoc = parseDocComment(docBuf.toString())
                i = j + 1
                continue
            }

            // Skip single-line comments & normal block comments
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                if (trimmed.isEmpty()) pendingDoc = null
                i++
                continue
            }
            if (trimmed.startsWith("/*")) {
                var j = i
                while (j < lines.size && !lines[j].contains("*/")) j++
                i = j + 1
                continue
            }
            if (trimmed.startsWith("@")) {
                pendingAnnotations.add(trimmed)
                i++
                continue
            }
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                pendingDoc = null
                pendingAnnotations.clear()
                i++
                continue
            }

            val depthBefore = braceDepth
            val isNonPublic = trimmed.startsWith("private ") || trimmed.startsWith("internal ") ||
                trimmed.startsWith("protected ")

            if (!isNonPublic) {
                val typeMatch = if (isJava) javaTypeRegex.find(trimmed) else kotlinTypeRegex.find(trimmed)
                if (typeMatch != null && (depthBefore == 0 || (currentType != null && depthBefore == typeBraceDepth))) {
                    val kind = typeMatch.groupValues[1]
                    val name = typeMatch.groupValues[2]
                    val sig = collapseSignature(lines, i)
                    val qualifiedName = if (depthBefore > 0 && currentType != null) "${currentType.name}.$name" else name
                    val builder = MutableTypeBuilder(
                        module = module,
                        packageName = packageName,
                        kind = kind,
                        name = qualifiedName,
                        signature = sig,
                        sourceRelPath = sourceRelPath,
                        line = i + 1,
                        doc = pendingDoc,
                    )
                    if (depthBefore == 0) {
                        currentType?.let { types.add(it.build()) }
                        currentType = builder
                        typeBraceDepth = if (trimmed.contains('{')) depthBefore + 1 else -1
                        if (typeBraceDepth == -1) {
                            types.add(builder.build())
                            currentType = null
                        }
                    } else {
                        types.add(builder.build())
                    }
                    pendingDoc = null
                    pendingAnnotations.clear()
                } else {
                    val isTopLevel = depthBefore == 0
                    val isDirectMember = currentType != null && depthBefore == typeBraceDepth
                    val javaMemberAllowed = !isJava || trimmed.startsWith("public ") || currentType?.kind == "interface"
                    if ((isTopLevel || isDirectMember) && javaMemberAllowed) {
                        val memberMatch = if (isJava) javaMemberRegex.find(trimmed) else kotlinMemberRegex.find(trimmed)
                        if (memberMatch != null) {
                            val kind = if (isJava) {
                                if (trimmed.contains('(')) "method" else "field"
                            } else {
                                memberMatch.groupValues[1]
                            }
                            val name = if (isJava) memberMatch.groupValues[1] else memberMatch.groupValues[2]
                            if (name !in setOf("if", "for", "while", "when", "switch", "catch", "return", "throw", "else")) {
                                val member = ApiMember(
                                    kind = kind,
                                    name = name,
                                    signature = collapseSignature(lines, i),
                                    doc = pendingDoc,
                                    line = i + 1,
                                )
                                if (isTopLevel) topLevelMembers.add(member) else currentType?.members?.add(member)
                            }
                            pendingDoc = null
                            pendingAnnotations.clear()
                        }
                    }
                }
            } else {
                pendingDoc = null
                pendingAnnotations.clear()
            }

            braceDepth = updateBraceDepth(braceDepth, trimmed)
            if (currentType != null && typeBraceDepth > 0 && braceDepth < typeBraceDepth) {
                types.add(currentType.build())
                currentType = null
                typeBraceDepth = -1
            }
            i++
        }

        currentType?.let { types.add(it.build()) }

        if (topLevelMembers.isNotEmpty()) {
            val fileBase = Path.of(sourceRelPath).fileName.toString().substringBeforeLast('.')
            val facadeName = if (isJava) fileBase else "${fileBase}Kt"
            types.add(
                ApiType(
                    module = module,
                    packageName = packageName,
                    kind = "file",
                    name = facadeName,
                    signature = "file $facadeName",
                    sourceRelPath = sourceRelPath,
                    line = 1,
                    doc = null,
                    members = topLevelMembers,
                ),
            )
        }
        return types
    }

    private class MutableTypeBuilder(
        val module: String,
        val packageName: String,
        val kind: String,
        val name: String,
        val signature: String,
        val sourceRelPath: String,
        val line: Int,
        val doc: ParsedDoc?,
        val members: MutableList<ApiMember> = mutableListOf(),
    ) {
        fun build(): ApiType = ApiType(module, packageName, kind, name, signature, sourceRelPath, line, doc, members.toList())
    }

    private fun collapseSignature(lines: List<String>, startIdx: Int): String {
        val sb = StringBuilder()
        var idx = startIdx
        var parenDepth = 0
        while (idx < lines.size && idx < startIdx + 12) {
            val part = lines[idx].trim()
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(part)
            for (ch in part) {
                if (ch == '(') parenDepth++
                else if (ch == ')') parenDepth--
            }
            val done = parenDepth <= 0 && (part.contains('{') || part.contains('=') || part.endsWith(';') ||
                (!part.endsWith(',') && !part.endsWith('(') && !part.endsWith(':')))
            if (done) break
            idx++
        }
        val raw = sb.toString()
            .substringBefore('{')
            .substringBefore(" = ")
            .trim()
            .removeSuffix(";")
            .replace(Regex("\\s+"), " ")
        return if (raw.length > 220) raw.take(217) + "..." else raw
    }

    private fun updateBraceDepth(initial: Int, line: String): Int {
        var depth = initial
        var inString = false
        var inChar = false
        var escape = false
        var k = 0
        while (k < line.length) {
            val c = line[k]
            if (escape) { escape = false; k++; continue }
            if (!inString && !inChar && c == '/' && k + 1 < line.length && line[k + 1] == '/') break
            if (c == '\\' && (inString || inChar)) { escape = true; k++; continue }
            if (c == '"' && !inChar) inString = !inString
            else if (c == '\'' && !inString) inChar = !inChar
            else if (!inString && !inChar) {
                if (c == '{') depth++
                else if (c == '}') depth = maxOf(0, depth - 1)
            }
            k++
        }
        return depth
    }

    /**
     * Generates complete offline HTML, Markdown, CSS, and JSON API documentation for [modules]
     * under [outputDir].
     *
     * @param repoRoot repository root containing the module directories
     * @param outputDir target output directory (e.g. `build/docs/api`)
     * @param modules ordered list of module directory names to scan
     * @return [GenerationReport] summarizing generated documentation counts
     */
    fun generate(
        repoRoot: Path,
        outputDir: Path,
        modules: List<String> = DEFAULT_MODULES,
    ): GenerationReport {
        val normRoot = repoRoot.toAbsolutePath().normalize()
        if (Files.exists(outputDir)) {
            Files.walk(outputDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(outputDir)

        val byModule = linkedMapOf<String, MutableList<ApiType>>()
        var filesScanned = 0

        for (mod in modules) {
            val modTypes = mutableListOf<ApiType>()
            val srcRoot = normRoot.resolve(mod).resolve("src/main")
            if (Files.isDirectory(srcRoot)) {
                val srcFiles = Files.walk(srcRoot).use { stream ->
                    stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java")) }
                        .sorted()
                        .toList()
                }
                for (file in srcFiles) {
                    filesScanned++
                    val rel = normRoot.relativize(file).toString().replace('\\', '/')
                    val content = Files.readString(file, StandardCharsets.UTF_8)
                    modTypes.addAll(parseSourceFile(mod, rel, content))
                }
            }
            modTypes.sortWith(compareBy<ApiType>({ it.packageName }, { it.name }, { it.sourceRelPath }))
            byModule[mod] = modTypes
        }

        val allTypes = byModule.values.flatten()
        val totalMembers = allTypes.sumOf { it.members.size }
        val documentedTypes = allTypes.count { it.doc != null }

        writeUtf8(outputDir.resolve("style.css"), buildStylesheet())
        writeUtf8(outputDir.resolve("index.html"), buildRootHtml(byModule, filesScanned, allTypes.size, totalMembers, documentedTypes))
        writeUtf8(outputDir.resolve("index.md"), buildRootMarkdown(byModule, filesScanned, allTypes.size, totalMembers, documentedTypes))
        writeUtf8(outputDir.resolve("api-index.json"), buildApiJson(byModule))

        for ((mod, types) in byModule) {
            val modDir = outputDir.resolve(mod)
            Files.createDirectories(modDir)
            writeUtf8(modDir.resolve("index.html"), buildModuleHtml(mod, types))
            writeUtf8(modDir.resolve("index.md"), buildModuleMarkdown(mod, types))

            for (t in types) {
                val pkgSub = if (t.packageName.isEmpty()) "_root" else t.packageName.replace('.', '/')
                val typeDir = modDir.resolve(pkgSub)
                Files.createDirectories(typeDir)
                val safeFile = t.name.replace('.', '_')
                val depthToRoot = 1 + pkgSub.split('/').size
                val relCss = "../".repeat(depthToRoot) + "style.css"
                val relModIndex = "../".repeat(depthToRoot - 1) + "index.html"
                val relRootIndex = "../".repeat(depthToRoot) + "index.html"
                writeUtf8(typeDir.resolve("$safeFile.html"), buildTypeHtml(t, relCss, relRootIndex, relModIndex))
                writeUtf8(typeDir.resolve("$safeFile.md"), buildTypeMarkdown(t))
            }
        }

        return GenerationReport(
            modules = modules,
            totalFilesScanned = filesScanned,
            totalTypes = allTypes.size,
            totalMembers = totalMembers,
            documentedTypes = documentedTypes,
            outputDir = outputDir,
        )
    }

    private fun writeUtf8(path: Path, text: String) {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
        Files.writeString(path, normalized, StandardCharsets.UTF_8)
    }

    private fun escHtml(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private fun escJson(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    private fun buildStylesheet(): String = """
        :root { --bg:#0f1419; --card:#171e26; --fg:#e6edf3; --muted:#8b949e; --accent:#58a6ff; --border:#2b3542; }
        body { font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--fg); margin: 0; padding: 24px 32px; line-height: 1.55; }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        header { border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 20px; }
        code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: #1d2630; border-radius: 4px; }
        code { padding: 2px 6px; font-size: 0.92em; }
        pre { padding: 12px; overflow-x: auto; border: 1px solid var(--border); }
        table { width: 100%; border-collapse: collapse; margin: 16px 0; background: var(--card); }
        th, td { text-align: left; padding: 8px 12px; border: 1px solid var(--border); vertical-align: top; }
        th { background: #1d2630; }
        .muted { color: var(--muted); }
        .badge { display: inline-block; padding: 2px 7px; border-radius: 10px; background: #223044; color: #9ecbff; font-size: 0.8em; }
        #filter { width: 100%; max-width: 420px; padding: 8px 10px; background: var(--card); color: var(--fg); border: 1px solid var(--border); border-radius: 6px; margin-bottom: 14px; }
    """.trimIndent()

    private fun buildRootHtml(
        byModule: Map<String, List<ApiType>>,
        filesScanned: Int,
        totalTypes: Int,
        totalMembers: Int,
        documentedTypes: Int,
    ): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Fuji GFX100S Emulator Offline API Reference</title><link rel=\"stylesheet\" href=\"style.css\"></head><body>")
        appendLine("<header><h1>Fuji GFX100S Emulator — Offline KDoc API Reference</h1>")
        appendLine("<p class=\"muted\">Zero-network offline API documentation | Modules: ${byModule.size} | Source files: $filesScanned | Public types: $totalTypes ($documentedTypes documented) | Public members: $totalMembers</p></header>")
        appendLine("<table><thead><tr><th>Module</th><th>Public Types</th><th>Public Members</th><th>KDoc Coverage</th></tr></thead><tbody>")
        for ((mod, types) in byModule) {
            val mems = types.sumOf { it.members.size }
            val docs = types.count { it.doc != null }
            val pct = if (types.isEmpty()) 100 else (docs * 100) / types.size
            appendLine("<tr><td><a href=\"$mod/index.html\"><code>${escHtml(mod)}</code></a></td><td>${types.size}</td><td>$mems</td><td>$docs/${types.size} ($pct%)</td></tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</body></html>")
    }

    private fun buildRootMarkdown(
        byModule: Map<String, List<ApiType>>,
        filesScanned: Int,
        totalTypes: Int,
        totalMembers: Int,
        documentedTypes: Int,
    ): String = buildString {
        appendLine("# Fuji GFX100S Emulator — Offline KDoc API Reference")
        appendLine()
        appendLine("- Modules: `${byModule.size}` | Source files: `$filesScanned` | Public types: `$totalTypes` (`$documentedTypes` documented) | Public members: `$totalMembers`")
        appendLine()
        appendLine("| Module | Public Types | Public Members | KDoc Coverage |")
        appendLine("| --- | ---: | ---: | ---: |")
        for ((mod, types) in byModule) {
            val mems = types.sumOf { it.members.size }
            val docs = types.count { it.doc != null }
            val pct = if (types.isEmpty()) 100 else (docs * 100) / types.size
            appendLine("| [`$mod`]($mod/index.md) | ${types.size} | $mems | $docs/${types.size} ($pct%) |")
        }
    }

    private fun buildModuleHtml(mod: String, types: List<ApiType>): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>${escHtml(mod)} — Offline API Reference</title><link rel=\"stylesheet\" href=\"../style.css\"></head><body>")
        appendLine("<header><p><a href=\"../index.html\">All Modules</a> / <strong>${escHtml(mod)}</strong></p><h1>Module <code>${escHtml(mod)}</code></h1></header>")
        appendLine("<input id=\"filter\" type=\"search\" placeholder=\"Filter types in ${escHtml(mod)}...\" oninput=\"filterRows(this.value)\">")
        appendLine("<table id=\"types\"><thead><tr><th>Kind</th><th>Type</th><th>Package</th><th>Summary</th></tr></thead><tbody>")
        for (t in types) {
            val pkgSub = if (t.packageName.isEmpty()) "_root" else t.packageName.replace('.', '/')
            val href = "$pkgSub/${t.name.replace('.', '_')}.html"
            val summary = t.doc?.summary?.ifEmpty { "(no KDoc)" } ?: "(no KDoc)"
            appendLine("<tr><td><span class=\"badge\">${escHtml(t.kind)}</span></td><td><a href=\"$href\"><code>${escHtml(t.name)}</code></a></td><td class=\"muted\"><code>${escHtml(t.packageName)}</code></td><td>${escHtml(summary)}</td></tr>")
        }
        appendLine("</tbody></table>")
        appendLine("<script>function filterRows(q){q=q.toLowerCase();for(const r of document.querySelectorAll('#types tbody tr')){r.style.display=r.textContent.toLowerCase().includes(q)?'':'none';}}</script>")
        appendLine("</body></html>")
    }

    private fun buildModuleMarkdown(mod: String, types: List<ApiType>): String = buildString {
        appendLine("# Module `$mod`")
        appendLine()
        appendLine("[Back to API Index](../index.md)")
        appendLine()
        appendLine("| Kind | Type | Package | Summary |")
        appendLine("| --- | --- | --- | --- |")
        for (t in types) {
            val pkgSub = if (t.packageName.isEmpty()) "_root" else t.packageName.replace('.', '/')
            val href = "$pkgSub/${t.name.replace('.', '_')}.md"
            val summary = (t.doc?.summary ?: "").replace("|", "\\|")
            appendLine("| `${t.kind}` | [`${t.name}`]($href) | `${t.packageName}` | $summary |")
        }
    }

    private fun buildTypeHtml(t: ApiType, relCss: String, relRoot: String, relMod: String): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>${escHtml(t.fqName)}</title><link rel=\"stylesheet\" href=\"$relCss\"></head><body>")
        appendLine("<header><p><a href=\"$relRoot\">All Modules</a> / <a href=\"$relMod\">${escHtml(t.module)}</a> / <code>${escHtml(t.packageName)}</code></p>")
        appendLine("<h1><span class=\"badge\">${escHtml(t.kind)}</span> ${escHtml(t.name)}</h1>")
        appendLine("<p class=\"muted\">Source: <code>${escHtml(t.sourceRelPath)}:${t.line}</code></p></header>")
        appendLine("<pre><code>${escHtml(t.signature)}</code></pre>")
        t.doc?.let { d ->
            if (d.summary.isNotEmpty()) appendLine("<p>${escHtml(d.summary)}</p>")
            if (d.details.isNotEmpty()) appendLine("<p>${escHtml(d.details).replace("\n", "<br>")}</p>")
            if (d.params.isNotEmpty()) {
                appendLine("<h3>Parameters</h3><ul>")
                d.params.forEach { (k, v) -> appendLine("<li><code>${escHtml(k)}</code>: ${escHtml(v)}</li>") }
                appendLine("</ul>")
            }
            d.returns?.let { appendLine("<p><strong>Returns:</strong> ${escHtml(it)}</p>") }
        }
        if (t.members.isNotEmpty()) {
            appendLine("<h2>Public Members (${t.members.size})</h2>")
            appendLine("<table><thead><tr><th>Kind</th><th>Signature</th><th>Documentation</th></tr></thead><tbody>")
            for (m in t.members) {
                val docHtml = buildString {
                    m.doc?.let { d ->
                        if (d.summary.isNotEmpty()) append(escHtml(d.summary))
                        if (d.params.isNotEmpty()) {
                            append("<br><span class=\"muted\">Params: ")
                            append(d.params.joinToString(", ") { (k, v) -> "<code>${escHtml(k)}</code> ${escHtml(v)}" })
                            append("</span>")
                        }
                        d.returns?.let { append("<br><span class=\"muted\">Returns: ${escHtml(it)}</span>") }
                    }
                }
                appendLine("<tr><td><span class=\"badge\">${escHtml(m.kind)}</span></td><td><code>${escHtml(m.signature)}</code></td><td>$docHtml</td></tr>")
            }
            appendLine("</tbody></table>")
        }
        appendLine("</body></html>")
    }

    private fun buildTypeMarkdown(t: ApiType): String = buildString {
        appendLine("# `${t.kind}` `${t.fqName}`")
        appendLine()
        appendLine("- Module: `${t.module}`")
        appendLine("- Source: `${t.sourceRelPath}:${t.line}`")
        appendLine()
        appendLine("```kotlin")
        appendLine(t.signature)
        appendLine("```")
        t.doc?.let { d ->
            if (d.summary.isNotEmpty()) { appendLine(); appendLine(d.summary) }
            if (d.details.isNotEmpty()) { appendLine(); appendLine(d.details) }
            if (d.params.isNotEmpty()) {
                appendLine(); appendLine("## Parameters")
                d.params.forEach { (k, v) -> appendLine("- `$k`: $v") }
            }
            d.returns?.let { appendLine(); appendLine("**Returns:** $it") }
        }
        if (t.members.isNotEmpty()) {
            appendLine()
            appendLine("## Public Members")
            appendLine()
            appendLine("| Kind | Name | Signature | Summary |")
            appendLine("| --- | --- | --- | --- |")
            for (m in t.members) {
                val sig = m.signature.replace("|", "\\|")
                val sum = (m.doc?.summary ?: "").replace("|", "\\|")
                appendLine("| `${m.kind}` | `${m.name}` | `$sig` | $sum |")
            }
        }
    }

    private fun buildApiJson(byModule: Map<String, List<ApiType>>): String = buildString {
        append("{\n  \"schema\": 1,\n  \"generator\": \"OfflineKdocApiGenerator\",\n  \"modules\": [\n")
        val entries = byModule.entries.toList()
        entries.forEachIndexed { mIdx, (mod, types) ->
            append("    {\"name\": ${escJson(mod)}, \"typeCount\": ${types.size}, \"types\": [\n")
            types.forEachIndexed { tIdx, t ->
                val comma = if (tIdx + 1 < types.size) "," else ""
                append("      {\"fqName\": ${escJson(t.fqName)}, \"kind\": ${escJson(t.kind)}, \"source\": ${escJson(t.sourceRelPath)}, \"line\": ${t.line}, \"documented\": ${t.doc != null}, \"memberCount\": ${t.members.size}}$comma\n")
            }
            val mComma = if (mIdx + 1 < entries.size) "," else ""
            append("    ]}$mComma\n")
        }
        append("  ]\n}")
    }
}

/** CLI entrypoint invoked by the root Gradle `:generateApiDocs` task. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: OfflineKdocApiGeneratorKt <repoRoot> <outputDir> [module1 module2 ...]" }
    val repoRoot = Path.of(args[0])
    val outputDir = Path.of(args[1])
    val modules = if (args.size > 2) args.drop(2) else OfflineKdocApiGenerator.DEFAULT_MODULES
    val report = OfflineKdocApiGenerator.generate(repoRoot, outputDir, modules)
    println(
        "OfflineKdocApiGenerator: ${report.modules.size} modules, ${report.totalFilesScanned} files, " +
            "${report.totalTypes} public types (${report.documentedTypes} documented), " +
            "${report.totalMembers} public members -> ${report.outputDir}",
    )
}
