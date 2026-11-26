package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Tile
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

internal class GraphicsCopyExecution(
    raw: Any?,
    private val continuous: Boolean = false,
    private val skipAuditDigests: Boolean = continuous,
) {
    companion object {
        const val PROFILE = "same-geometry-shared-ram-copy-experimental-v1"
        const val TILE_PROFILE_SETTING = "mode2TileProfile"
        const val ALIGNED_8X8_TILES = "aligned-8x8-1024x768-v1"
        private const val GEOMETRY = 0x03000400L
        private const val BYTES = 1024 * 768 * 4
        private const val MAX_CONTINUOUS_DIM = 2048
        private const val MAX_DESTINATIONS = 16
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val samplePattern = Regex("[0-9a-f]{32}")
        private val FAST_CONTINUOUS_SHA256 = "0".repeat(64)
        private val INT_LE_HANDLE: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val MORTON_COL = IntArray(64) { m -> (m and 1) + ((m ushr 1) and 2) + ((m ushr 2) and 4) }
        private val MORTON_ROW = IntArray(64) { m -> ((m ushr 1) and 1) + ((m ushr 2) and 2) + ((m ushr 3) and 4) }
        private fun integer(value: Any?): Long {
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
    }

    private var sourceScratch = ByteArray(0)
    private var tiledScratch = ByteArray(0)
    private var readbackScratch = ByteArray(0)

    private val config = (raw as? Map<*, *>)?.also {
        require(it.keys - setOf("additionalDestinationBases", GraphicsMode2Tile.SETTING, GraphicsMode2Workspace.SETTING, TILE_PROFILE_SETTING) == setOf("profile","sourceBase","destinationBase","surfaceBytes","submissionLimit") && it["profile"] == PROFILE)
    } ?: throw IllegalArgumentException("Invalid graphics copy execution input")
    private val sourceBase = integer(config["sourceBase"])
    private val destinations = buildList {
        add(integer(config["destinationBase"]))
        if ("additionalDestinationBases" in config) {
            val extra = config["additionalDestinationBases"]
            require(extra is List<*> && extra.size in 1 until MAX_DESTINATIONS)
            extra.mapTo(this, ::integer)
        }
    }
    private val capacity = integer(config["surfaceBytes"])
    private val tile = if (GraphicsMode2Tile.SETTING in config) GraphicsMode2Tile.parse(config[GraphicsMode2Tile.SETTING]) else null
    private val workspace = if (GraphicsMode2Workspace.SETTING in config) GraphicsMode2Workspace.parse(config[GraphicsMode2Workspace.SETTING]) else null
    private val mode2TileProfile = config[TILE_PROFILE_SETTING]?.also { require(it == ALIGNED_8X8_TILES) } as String?
    val submissionLimit = integer(config["submissionLimit"]).also { require(it in 1..GraphicsSuccessors.SUBMISSION_LIMIT) }.toInt()

    init {
        require(mode2TileProfile == null || workspace != null) { "Extended mode 2 tiles require mode2Workspace" }
        require(workspace == null || tile == null || workspace.covers(tile))
        require(capacity == BYTES.toLong())
        val surfaces = (destinations + sourceBase).sorted()
        require(surfaces.all { it in 0..0x100000000L-capacity && it and 3L == 0L })
        require(surfaces.zipWithNext().all { (left, right) -> left + capacity <= right }) { "Overlapping graphics copy surfaces" }
    }

    fun settings(): Map<String, Any> = mapOf("profile" to PROFILE, "sourceBase" to sourceBase,
        "destinationBase" to destinations.first(), "surfaceBytes" to capacity, "submissionLimit" to submissionLimit) +
        (if (destinations.size > 1) mapOf("additionalDestinationBases" to destinations.drop(1)) else emptyMap()) +
        (tile?.let { mapOf(GraphicsMode2Tile.SETTING to it.settings()) } ?: emptyMap()) +
        (workspace?.let { mapOf(GraphicsMode2Workspace.SETTING to it.settings()) } ?: emptyMap()) +
        (mode2TileProfile?.let { mapOf(TILE_PROFILE_SETTING to it) } ?: emptyMap())

    fun requireCompatiblePrefix(saved: GraphicsCopyExecution) {
        require(sourceBase == saved.sourceBase && submissionLimit >= saved.submissionLimit &&
            (saved.workspace == null || workspace == saved.workspace) &&
            (saved.mode2TileProfile == null || mode2TileProfile == saved.mode2TileProfile) &&
            (saved.tile == null || tile == saved.tile || workspace?.covers(saved.tile) == true) &&
            destinations.take(saved.destinations.size) == saved.destinations) { "Graphics copy declaration differs from saved prefix" }
    }

    private data class Geometry(val width: Int, val height: Int) {
        val stride = width * 4
        val bytes = stride * height
    }

    private fun geometry(words: List<Long>): Geometry {
        require(words.size == 9 && words[5] == 1L && words[6] == 0L && words[7] == 0L && words[8] == 0L) {
            "Unqualified graphics copy control or auxiliary registers"
        }
        if (words[4] == 2L) {
            val packed = words[2]
            require(packed in 0..0xffffffffL && words[3] == packed)
            val width = (packed and 0xffff).toInt()
            val height = (packed ushr 16).toInt()
            val languageTile = width in 8..GraphicsMode2Tile.WIDTH && height in 8..GraphicsMode2Tile.HEIGHT &&
                width % 8 == 0 && height % 8 == 0
            val alignedTile = mode2TileProfile == ALIGNED_8X8_TILES &&
                width in 8..1024 && height in 8..768 && width % 8 == 0 && height % 8 == 0
            val continuousTile = continuous &&
                width in 8..MAX_CONTINUOUS_DIM && height in 8..MAX_CONTINUOUS_DIM &&
                width % 8 == 0 && height % 8 == 0
            require(alignedTile || languageTile || continuousTile || (width == 144 && height == 40) || (width == 128 && height == 48)) {
                "Unqualified mode 2 tile geometry"
            }
            val geometry = Geometry(width, height)
            val exact = tile != null && words[0] == tile.source && words[1] == tile.destination && packed == GraphicsMode2Tile.GEOMETRY.toLong()
            val continuousAddresses = continuous &&
                words[0] in 0L..(0x1_0000_0000L - geometry.bytes) && (words[0] and 3L) == 0L &&
                words[1] in 0L..(0x1_0000_0000L - geometry.bytes) && (words[1] and 3L) == 0L
            require(exact || workspace?.permits(words[0], words[1], geometry.bytes) == true || continuousAddresses) {
                "Unqualified mode 2 tile transfer"
            }
            return geometry
        }
        if (continuous && words[4] == 1L) {
            val packed = words[2]
            require(packed in 0..0xffffffffL && words[3] == packed) { "Expected matching surface geometries" }
            val width = (packed and 0xffff).toInt()
            val height = (packed ushr 16).toInt()
            require(width in 1..MAX_CONTINUOUS_DIM && height in 1..MAX_CONTINUOUS_DIM) {
                "Unsupported graphics copy dimensions: ${width}x$height"
            }
            val geometry = Geometry(width, height)
            require(words[0] in 0L..(0x1_0000_0000L - geometry.bytes) && (words[0] and 3L) == 0L &&
                words[1] in 0L..(0x1_0000_0000L - geometry.bytes) && (words[1] and 3L) == 0L) {
                "Unqualified graphics copy surfaces"
            }
            return geometry
        }
        require(words[4] == 1L && words[0] == sourceBase && words[1] in destinations) { "Unqualified graphics copy control or surfaces" }
        require(words[2] == GEOMETRY && words[3] == GEOMETRY) { "Expected two 1024x768 surfaces" }
        return Geometry(1024, 768)
    }

    private fun ensureScratch(existing: ByteArray, size: Int) = if (existing.size >= size) existing else ByteArray(size)

    private fun tiled(linear: ByteArray, bytes: Int, geometry: Geometry): ByteArray {
        val output = ensureScratch(tiledScratch, bytes).also { tiledScratch = it }
        val stride = geometry.stride
        val rows = geometry.height
        val cols = geometry.width
        if (NativeRenderBridge.mortonTile8x8(linear, output, cols, rows, stride)) {
            return output
        }
        var offset = 0
        for (top in 0 until rows step 8) {
            for (left in 0 until cols step 8) {
                val base = top * stride + (left shl 2)
                for (morton in 0 until 64) {
                    val start = base + MORTON_ROW[morton] * stride + (MORTON_COL[morton] shl 2)
                    val pixel = INT_LE_HANDLE.get(linear, start) as Int
                    INT_LE_HANDLE.set(output, offset, pixel)
                    offset += 4
                }
            }
        }
        return output
    }

    fun execute(memory: DmaMemory, words: LongArray): Map<String, Any> {
        val geometry = geometry(words.asList())
        val bytes = geometry.bytes
        val source = words[0]
        val destination = words[1]
        memory.check(source, bytes.toLong(), false)
        memory.check(destination, bytes.toLong(), true)
        if (!skipAuditDigests) {
            memory.check(destination, bytes.toLong(), false)
        }
        val sourceArr = ensureScratch(sourceScratch, bytes).also { sourceScratch = it }
        val sourceBytes = ByteBuffer.wrap(sourceArr, 0, bytes)
        memory.read(source, sourceBytes)
        check(!sourceBytes.hasRemaining()) { "Short graphics copy source read" }
        val mode2 = words[4] == 2L
        val expected = if (mode2) tiled(sourceArr, bytes, geometry) else sourceArr
        val transfer = ByteBuffer.wrap(expected, 0, bytes).asReadOnlyBuffer()
        memory.write(destination, transfer)
        check(!transfer.hasRemaining()) { "Short graphics copy destination write" }
        val hex = HexFormat.of()
        if (skipAuditDigests) {
            return mapOf("source" to source, "destination" to destination, "bytes" to bytes.toLong(), "completionEvents" to "NONE",
                "sourceSha256" to FAST_CONTINUOUS_SHA256,
                "sha256" to FAST_CONTINUOUS_SHA256,
                "firstBytes" to hex.formatHex(expected, 0, 16), "lastBytes" to hex.formatHex(expected, bytes - 16, bytes)) +
                (if (mode2) tileProof(geometry, FAST_CONTINUOUS_SHA256) else emptyMap())
        }
        val readbackArr = ensureScratch(readbackScratch, bytes).also { readbackScratch = it }
        val readback = ByteBuffer.wrap(readbackArr, 0, bytes)
        memory.read(destination, readback)
        check(!readback.hasRemaining() && Arrays.equals(expected, 0, bytes, readbackArr, 0, bytes)) { "Graphics copy readback differs" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceArr, 0, bytes)
        val sourceSha = hex.formatHex(digest.digest())
        val destSha = if (mode2) {
            digest.update(readbackArr, 0, bytes)
            hex.formatHex(digest.digest())
        } else {
            sourceSha
        }
        return mapOf("source" to source, "destination" to destination, "bytes" to bytes.toLong(), "completionEvents" to "NONE",
            "sourceSha256" to sourceSha,
            "sha256" to destSha,
            "firstBytes" to hex.formatHex(readbackArr, 0, 16), "lastBytes" to hex.formatHex(readbackArr, bytes - 16, bytes)) +
            (if (mode2) tileProof(geometry, destSha) else emptyMap())
    }

    private fun tileProof(geometry: Geometry, expectedDigest: String) = mapOf("layout" to "linear-u32-to-morton8x8", "confidence" to "HYPOTHESIS",
        "rows" to geometry.height, "sourceStrideBytes" to geometry.stride, "expectedSha256" to expectedDigest)

    fun restore(raw: Any?, words: List<Long>): Map<String, Any> {
        val geometry = geometry(words)
        val bytes = geometry.bytes
        require(raw is Map<*, *>)
        val digest = raw["sha256"]
        require(digest is String && digest.matches(digestPattern))
        val extra = if (words[4] == 2L) tileProof(geometry, digest) else emptyMap()
        require(raw.keys == setOf("source","destination","bytes","completionEvents","sourceSha256","sha256","firstBytes","lastBytes") + extra.keys)
        require(extra.all { (key, value) -> if (value is Int) integer(raw[key]) == value.toLong() else raw[key] == value })
        require(integer(raw["source"]) == words[0] && integer(raw["destination"]) == words[1] &&
            integer(raw["bytes"]) == bytes.toLong() && raw["completionEvents"] == "NONE")
        val sourceDigest = raw["sourceSha256"]
        val first = raw["firstBytes"]
        val last = raw["lastBytes"]
        require(sourceDigest is String && sourceDigest.matches(digestPattern) && (extra.isNotEmpty() || sourceDigest == digest))
        require(first is String && first.matches(samplePattern) && last is String && last.matches(samplePattern))
        return mapOf("source" to words[0], "destination" to words[1], "bytes" to bytes.toLong(), "completionEvents" to "NONE",
            "sourceSha256" to sourceDigest, "sha256" to digest, "firstBytes" to first, "lastBytes" to last) + extra
    }
}
