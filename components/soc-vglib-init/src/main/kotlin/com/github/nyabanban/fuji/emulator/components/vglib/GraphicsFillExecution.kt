package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.GraphicsFillSpans
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat

internal class GraphicsFillExecution(
    raw: Any?,
    private val continuous: Boolean = false,
    private val skipAuditDigests: Boolean = continuous,
) {
    companion object {
        const val PROFILE = "q0-register-shared-ram-fill-experimental-v2"
        const val MAX_BYTES = 0x300000L
        const val CONTINUOUS_MAX_BYTES = 0x1000000L
        const val CONTROL = 0x0f100201L
        private val FAST_CONTINUOUS_SHA256 = "0".repeat(64)
        private val INT_LE_HANDLE: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private fun number(raw: Any?): Long {
            require(raw is Int || raw is Long)
            return (raw as Number).toLong()
        }
    }

    private val input = (raw as? Map<*, *>)?.also {
        require(it.keys - "additionalSurfaces" - GraphicsFillSpans.SETTING == setOf("profile", "submissionLimit", "surfaceBase", "surfaceBytes") && it["profile"] == PROFILE)
    } ?: throw IllegalArgumentException("Invalid graphics fill execution input")
    val submissionLimit = number(input["submissionLimit"]).also { require(it in 1..GraphicsSuccessors.SUBMISSION_LIMIT) }.toInt()
    private data class Surface(val base: Long, val bytes: Long) {
        init {
            require(bytes in 4..MAX_BYTES && base in 0..0x100000000L-bytes && (bytes or base) and 3L == 0L)
        }
        fun settings() = mapOf("surfaceBase" to base, "surfaceBytes" to bytes)
    }

    private val surfaces = buildList {
        add(Surface(number(input["surfaceBase"]), number(input["surfaceBytes"])))
        if ("additionalSurfaces" in input) {
            val rows = input["additionalSurfaces"]
            require(rows is List<*> && rows.size in 1..15)
            rows.forEach { row ->
                require(row is Map<*, *> && row.keys == setOf("surfaceBase", "surfaceBytes"))
                add(Surface(number(row["surfaceBase"]), number(row["surfaceBytes"])))
            }
        }
    }.also { ranges ->
        require(ranges.sortedBy { it.base }.zipWithNext().all { (left, right) -> left.base + left.bytes <= right.base }) {
            "Overlapping graphics fill surfaces"
        }
    }

    private val spans = if (GraphicsFillSpans.SETTING in input) GraphicsFillSpans.parse(input[GraphicsFillSpans.SETTING],
        surfaces.drop(1).map { it.base until it.base + it.bytes }) else emptyList()

    fun settings() = mapOf("profile" to PROFILE, "submissionLimit" to submissionLimit) + surfaces.first().settings() +
        (if (spans.isEmpty()) emptyMap() else mapOf(GraphicsFillSpans.SETTING to spans.map { mapOf("start" to it.first, "end" to it.last+1) })) +
        (if (surfaces.size > 1) mapOf("additionalSurfaces" to surfaces.drop(1).map(Surface::settings)) else emptyMap())

    fun requireCompatiblePrefix(saved: GraphicsFillExecution) {
        require(surfaces.first() == saved.surfaces.first() && submissionLimit >= saved.submissionLimit && spans.containsAll(saved.spans) &&
            saved.surfaces.drop(1).all { previous ->
                surfaces.any { current -> previous.base >= current.base && previous.base + previous.bytes <= current.base + current.bytes }
            }) {
            "Graphics fill declaration no longer covers saved surfaces"
        }
    }

    private fun coveredByContiguousAdditionalSurfaces(start: Long, end: Long): Boolean {
        if (end - start !in 4..MAX_BYTES) return false
        val parts = surfaces.drop(1).filter { it.base < end && start < it.base + it.bytes }
        return parts.size >= 2 && parts.sumOf { minOf(end, it.base + it.bytes) - maxOf(start, it.base) } == end - start
    }

    private fun size(start: Long, end: Long, control: Long): Int {
        require(control == CONTROL) { "Unqualified graphics fill control" }
        val span = end - start
        val inContinuousRange = continuous &&
            span in 4L..CONTINUOUS_MAX_BYTES &&
            start in 0L..(0x1_0000_0000L - span)
        require(start < end && (start or end) and 3L == 0L &&
            (surfaces.any { start >= it.base && end <= it.base + it.bytes } ||
                (start until end) in spans ||
                inContinuousRange ||
                (continuous && coveredByContiguousAdditionalSurfaces(start, end)))) {
            "Graphics fill outside selected physical surface"
        }
        return span.toInt()
    }

    private var patternScratch = ByteArray(0)
    private var readbackScratch = ByteArray(0)

    private fun ensureScratch(existing: ByteArray, size: Int) = if (existing.size >= size) existing else ByteArray(size)

    private fun fillPattern(dest: ByteArray, count: Int, packed: Long) {
        val word = packed.toInt()
        if (NativeRenderBridge.fillU32(dest, count, word)) return
        val alignedEnd = count and 3.inv()
        var i = 0
        while (i < alignedEnd) {
            INT_LE_HANDLE.set(dest, i, word)
            i += 4
        }
        while (i < count) {
            dest[i] = (packed ushr ((i and 3) * 8)).toByte()
            i++
        }
    }

    private fun pattern(count: Int, packed: Long) = ByteArray(count).also { fillPattern(it, count, packed) }

    private fun proof(readback: ByteArray, length: Int = readback.size, computeDigest: Boolean = true): Map<String, Any> {
        val hex = HexFormat.of()
        val sample = minOf(16, length)
        val shaHex = if (computeDigest) {
            val digest = MessageDigest.getInstance("SHA-256").apply { update(readback, 0, length) }.digest()
            hex.formatHex(digest)
        } else {
            FAST_CONTINUOUS_SHA256
        }
        return mapOf("bytes" to length, "completionEvents" to "NONE",
            "sha256" to shaHex,
            "firstBytes" to hex.formatHex(readback, 0, sample),
            "lastBytes" to hex.formatHex(readback, length - sample, length))
    }

    fun execute(memory: DmaMemory, words: LongArray, control: Long): Map<String, Any> {
        val count = size(words[0], words[1], control)
        memory.check(words[0], count.toLong(), true)
        if (!skipAuditDigests) {
            memory.check(words[0], count.toLong(), false)
        }
        val expected = ensureScratch(patternScratch, count).also { patternScratch = it }
        fillPattern(expected, count, words[2])
        val source = ByteBuffer.wrap(expected, 0, count)
        memory.write(words[0], source)
        check(!source.hasRemaining())
        if (skipAuditDigests) {
            return proof(expected, count, computeDigest = false)
        }
        val destinationArr = ensureScratch(readbackScratch, count).also { readbackScratch = it }
        val destination = ByteBuffer.wrap(destinationArr, 0, count)
        memory.read(words[0], destination)
        check(!destination.hasRemaining() && java.util.Arrays.equals(expected, 0, count, destinationArr, 0, count)) { "Graphics fill readback differs" }
        return proof(destinationArr, count, computeDigest = true)
    }

    fun restore(raw: Any?, submission: Map<String, Long>): Map<String, Any> {
        require(raw is Map<*, *>)
        val count = size(submission.getValue("start"), submission.getValue("end"), submission.getValue("control"))
        val expected = proof(pattern(count, submission.getValue("packed")), computeDigest = !skipAuditDigests)
        val shaOk = raw["sha256"] == expected["sha256"] ||
            (skipAuditDigests && raw["sha256"] == proof(pattern(count, submission.getValue("packed")), computeDigest = true)["sha256"])
        require(raw.keys == expected.keys && number(raw["bytes"]) == count.toLong() && shaOk &&
            expected.filterKeys { it != "bytes" && it != "sha256" }.all { raw[it.key] == it.value }) { "Saved graphics fill readback differs" }
        return expected + ("sha256" to raw["sha256"]!!)
    }
}
