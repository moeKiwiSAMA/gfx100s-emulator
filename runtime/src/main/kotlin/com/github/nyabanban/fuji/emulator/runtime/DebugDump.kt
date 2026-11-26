package com.github.nyabanban.fuji.emulator.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Caller snapshots on owner thread. Memory is opt-in and bounded; evidence publication preserves exact bytes and recorded identities. */
object DebugDump {
    fun write(directory: Path, state: Map<String, Any?>, memory: Map<String, ByteArray> = emptyMap()): Path {
        require(memory.size <= 16 && memory.values.sumOf { it.size.toLong() } <= 16 * 1024 * 1024)
        require(memory.keys.all { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) })
        Files.createDirectories(directory)
        val target = directory.resolve("dump-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.zip")
        val manifest = mapOf("schema" to 1, "created" to Instant.now().toString(), "state" to state,
            "memory" to memory.mapValues { (_, bytes) -> mapOf("size" to bytes.size, "sha256" to java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) })
        ZipOutputStream(Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Formats.jsonBytes(manifest)); zip.closeEntry()
            memory.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("memory/$name.bin")); zip.write(bytes); zip.closeEntry() }
        }
        return target
    }
}
