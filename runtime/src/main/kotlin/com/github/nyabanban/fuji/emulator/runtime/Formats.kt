package com.github.nyabanban.fuji.emulator.runtime

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.yaml.snakeyaml.LoaderOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object Formats {
    private val limits = StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(64).build()
    val json: ObjectMapper = ObjectMapper().apply {
        setDefaultPrettyPrinter(DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")))
        factory.setStreamReadConstraints(limits)
        enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    }
    val yaml: ObjectMapper = ObjectMapper(YAMLFactory.builder().loaderOptions(LoaderOptions().apply {
        maxAliasesForCollections = 0; codePointLimit = 65536
    }).build()).apply {
        factory.setStreamReadConstraints(limits)
        enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    }
    fun read(path: Path): Map<String, Any> {
        return map(readData(path))
    }
    /** Component input schemas may explicitly represent an absent optional sample as null. Root settings remain non-null. */
    fun readData(path: Path): Map<String, Any?> {
        require(Files.size(path) <= 65536) { "YAML exceeds 64 KiB" }
        val value=yaml.readValue(Files.readAllBytes(path),Map::class.java)
        require(value is Map<*,*> && value.keys.all { it is String }) { "expected string-keyed data object" }
        @Suppress("UNCHECKED_CAST") return value as Map<String,Any?>
    }
    fun map(value: Any?): Map<String, Any> {
        require(value is Map<*, *> && value.keys.all { it is String } && value.values.none { it == null }) { "expected string-keyed object without nulls" }
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any>
    }
    /** Project text view; input must already be decoded as UTF-8. Raw external bytes are archived separately. */
    fun lf(text: String): String = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").let { if (it.endsWith("\n")) it else it + "\n" }
    fun jsonText(value: Any?, pretty: Boolean = true): String = lf(if (pretty) json.writerWithDefaultPrettyPrinter().writeValueAsString(value) else json.writeValueAsString(value))
    fun jsonBytes(value: Any?, pretty: Boolean = true): ByteArray = jsonText(value, pretty).toByteArray(Charsets.UTF_8)
    /** Stream through a sibling temporary file; serialization failure preserves the previous final file. */
    fun writeJson(path: Path, value: Any?) {
        val target = path.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".json-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { output ->
                json.writerWithDefaultPrettyPrinter()
                    .without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .writeValue(output, value)
                output.write(10)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun writeJsonWithSha256(path: Path, value: Any?): Pair<Long, String> {
        val target = path.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".json-", ".tmp")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var writtenBytes = 0L
            Files.newOutputStream(temporary).use { raw ->
                val buffered = java.io.BufferedOutputStream(raw, 65536)
                val counting = object : java.io.FilterOutputStream(java.security.DigestOutputStream(buffered, digest)) {
                    override fun write(b: Int) { out.write(b); writtenBytes++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); writtenBytes += len }
                }
                json.writerWithDefaultPrettyPrinter()
                    .without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .writeValue(counting, value)
                counting.write(10)
                counting.flush()
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return writtenBytes to java.util.HexFormat.of().formatHex(digest.digest())
        } finally { Files.deleteIfExists(temporary) }
    }
    fun writeLf(path: Path, text: String) { Files.writeString(path, lf(text), Charsets.UTF_8) }
    fun atomicYaml(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".config-", ".yaml")
        try {
            Files.writeString(temporary, lf(yaml.writeValueAsString(value)), Charsets.UTF_8)
            var lastFailure: java.nio.file.AccessDeniedException? = null
            for (attempt in 0 until 4) {
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: java.nio.file.AccessDeniedException) {
                    // Windows NTFS / Defender transient lock on rapid button YAML updates.
                    lastFailure = e
                    try {
                        Thread.sleep(5L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AccessDeniedException) {
                throw lastFailure ?: java.nio.file.AccessDeniedException(path.toString())
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** Resolves existing symlinks, including existing parents of a new file. */
fun containedPath(directory: Path, relative: String): Path {
    require(relative.isNotBlank())
    val root = directory.toRealPath()
    val path = root.resolve(relative).normalize()
    require(path.startsWith(root) && path != root) { "path outside component directory" }
    var existing = path
    while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
    require(existing.toRealPath().startsWith(root)) { "symlink outside component directory" }
    return path
}
