package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ImageRegionIdentity
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat

/** Startup-frozen bytes. No path parsing, hashing, JSON or allocation in a region read. */
internal class SupplementalRegions(context: ComponentContext, profile: String, file: String) {
    private data class Region(val identity: ImageRegionIdentity, val bytes: ByteArray) {
        val end: Long get() = identity.offset + identity.length
    }
    private val regions: Array<Region>
    val identities: List<ImageRegionIdentity>
    private val bank30Selected: Boolean
    init {
        require(profile in setOf("strict", PROFILE))
        require((profile == "strict") == file.isBlank()) { "supplement profile/file selection mismatch" }
        regions = if (profile == "strict") {
            bank30Selected = false
            emptyArray()
        } else {
            val data = context.readData(file)
            bank30Selected = "restoreAppend" in data
            if (bank30Selected) require(data["restoreAppend"] == BANK30_SELECTION) { "unknown immutable restore addition" }
            require(data.keys - "restoreAppend" == setOf("schema", "profile", "regions") && integer(data["schema"]) == 1L && data["profile"] == PROFILE) {
                "explicit complete supplemental image specification required"
            }
            val rows = data["regions"] as? List<*> ?: error("regions must be a list")
            require(rows.size in 1..16) { "one to sixteen explicit regions required" }
            val specifications = rows.map { value ->
                val row = value as? Map<*, *> ?: error("region object required")
                require(row.keys == setOf("id", "offset", "length", "file", "sha256")) { "unknown or missing region field" }
                val id = row["id"] as? String ?: error("region id required")
                require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "invalid region id" }
                val offset = integer(row["offset"]); val length = integer(row["length"])
                require(offset >= 0 && length in 1..MAX_FILE && offset <= ADDRESS_SPACE - length) { "image region range out of bounds" }
                val name = row["file"] as? String ?: error("region file required")
                require(name.isNotBlank() && name.length <= 4096)
                val sha = row["sha256"] as? String ?: error("region SHA-256 required")
                require(sha.matches(Regex("[0-9a-f]{64}")))
                ImageRegionIdentity(id, offset, length, sha, name) to name
            }.sortedBy { it.first.offset }
            if (bank30Selected) require(specifications.last().first == BANK30) { "expected exact final bank30 identity" }
            require(specifications.map { it.first.id }.toSet().size == specifications.size) { "duplicate region id" }
            require(specifications.sumOf { it.first.length } <= MAX_TOTAL) { "supplemental images exceed bounded startup memory" }
            specifications.zipWithNext().forEach { (a, b) ->
                require(a.first.offset + a.first.length <= b.first.offset) { "overlapping supplemental regions" }
            }
            // Validate every range before opening any image; failed construction publishes no port.
            specifications.map { (identity, name) ->
                val path = context.dataPath(name)
                require(Files.isRegularFile(path)) { "image file missing" }
                val bytes = ByteArray(identity.length.toInt())
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    require(channel.size() == identity.length) { "image length mismatch" }
                    val target = ByteBuffer.wrap(bytes)
                    while (target.hasRemaining()) check(channel.read(target) > 0) { "short supplemental image" }
                    require(channel.size() == identity.length) { "image changed during load" }
                }
                val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                require(actual == identity.sha256) { "image SHA-256 mismatch" }
                Region(identity, bytes)
            }.toTypedArray()
        }
        identities = Collections.unmodifiableList(regions.map { it.identity })
    }
    fun snapshotIdentities(savedCount: Int): List<ImageRegionIdentity> =
        if (bank30Selected && savedCount == identities.size - 1) identities.dropLast(1) else identities

    fun checkMutation(specification: Map<String, Any?>): Map<String, Any?> {
        if (bank30Selected) {
            val rows = specification["regions"] as? List<*> ?: listOf(specification)
            require(rows.none { (it as? Map<*, *>)?.get("regionId") == BANK30.id }) { "selected bank30 is read-only" }
        }
        return specification
    }
    fun validatePrefix(end: Long) {
        require(end in 1..ADDRESS_SPACE)
        require(regions.all { it.identity.offset >= end }) { "supplement overlaps DAT prefix or selected finite tail" }
    }
    fun check(offset: Long, length: Long, prefixEnd: Long) {
        require(offset >= 0 && length > 0 && offset <= ADDRESS_SPACE && length <= ADDRESS_SPACE - offset) { "image read range out of bounds" }
        val end = offset + length
        var cursor = offset
        if (cursor < prefixEnd) cursor = minOf(end, prefixEnd)
        for (region in regions) {
            if (cursor == end) break
            if (region.end <= cursor) continue
            require(region.identity.offset <= cursor) { "read crosses unsupplied image hole" }
            cursor = minOf(end, region.end)
        }
        require(cursor == end) { "read outside supplied image regions" }
    }
    /** Caller preflights the whole transfer before mutating destination bytes or position. */
    fun read(offset: Long, destination: ByteBuffer) {
        var cursor = offset
        for (region in regions) {
            if (!destination.hasRemaining()) return
            if (region.end <= cursor) continue
            check(region.identity.offset <= cursor)
            val n = minOf(destination.remaining().toLong(), region.end - cursor).toInt()
            destination.put(region.bytes, (cursor - region.identity.offset).toInt(), n)
            cursor += n
        }
        check(!destination.hasRemaining())
    }
    companion object {
        const val PROFILE = "explicit-readonly-regions-experimental-v1"
        const val BANK30_SELECTION = "gfx100s-bank30-readonly-v1"
        val BANK30 = ImageRegionIdentity("hardware-bank30", 0x03f80000L, 0x20000L,
            "41efff951e67ea9a03139ba3441a0d86867404c5928439dadefc377dfd08652f", "bank-30_03f80000.bin")
        private const val ADDRESS_SPACE = 0x1_0000_0000L
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_TOTAL = 128L * 1024 * 1024
        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "integer required" }
            return (value as Number).toLong()
        }
    }
}
