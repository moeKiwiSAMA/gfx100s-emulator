package com.github.nyabanban.fuji.emulator.desktop

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

object EmulatorSnapshot {
    const val SCHEMA = 1
    const val FORMAT = "fuji-emulator-snapshot-v1"
    const val MANIFEST_FILE = "manifest.json"
    const val COMPONENTS_FILE = "components.json"
    const val COMPLETE_FILE = "snapshot.complete.json"
    const val RAM_DIR = "ram"
    const val CPU_DIR = "cpu"

    private val snapshotJsonLimits = StreamReadConstraints.builder()
        .maxNestingDepth(64)
        .maxStringLength(16 * 1024 * 1024)
        .maxNumberLength(64)
        .build()

    private val snapshotJson: ObjectMapper = ObjectMapper().apply {
        factory.setStreamReadConstraints(snapshotJsonLimits)
        enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }

    data class RestoreOptions(
        val overrideBudget: Long? = null,
        val allowCompatibleProfileChanges: Set<String> = emptySet(),
        val allowPromotedDiscoveryRegions: Boolean = false,
        val preferArchitecturalCpuRestore: Boolean = false,
        val sdInsertion: SdSnapshotInsertion? = null,
        val unreadSd1: UnreadSd1Replacement? = null,
    ) {
        init {
            require(unreadSd1 == null || (sdInsertion == null && allowCompatibleProfileChanges.isEmpty() && !allowPromotedDiscoveryRegions)) {
                "Unread SD1 replacement requires the same device profiles and routes"
            }
        }
    }

    fun regionFileName(address: Long, size: Long, id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Unsafe RAM region id for snapshot filename: $id" }
        return "%08x-%08x-%s.bin".format(address, size, id)
    }

    fun sha256Bytes(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun readJsonObject(path: Path): Map<String, Any?> {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing snapshot JSON file: $path" }
        val parsed = Files.newInputStream(path).use { input ->
            snapshotJson.readValue(input, Map::class.java)
        }
        require(parsed is Map<*, *> && parsed.keys.all { it is String }) {
            "Expected string-keyed JSON object in $path"
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as Map<String, Any?>
    }

    private val verifyBuffer = ThreadLocal.withInitial { java.nio.ByteBuffer.allocate(1048576) }

    fun verifyRegularFileSha256(path: Path, expectedSize: Long, expectedSha256: String) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing snapshot file: $path" }
        require(Files.size(path) == expectedSize) {
            "Snapshot file size mismatch for $path: expected=$expectedSize actual=${Files.size(path)}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = verifyBuffer.get()
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { channel ->
            while (true) {
                buffer.clear()
                val readCount = channel.read(buffer)
                if (readCount < 0) break
                if (readCount > 0) {
                    buffer.flip()
                    digest.update(buffer)
                }
            }
        }
        val actual = HexFormat.of().formatHex(digest.digest())
        require(actual == expectedSha256) {
            "Snapshot file SHA-256 mismatch for $path: expected=$expectedSha256 actual=$actual"
        }
    }
}
