package com.github.nyabanban.fuji.emulator.components.vglib

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal object VglibSnapshotCodec {
    internal data class ParsedCoordinate(
        val tick: Long,
        val core: Int,
        val pcVirtual: Long,
    )

    internal data class ParsedSequenceState(
        val position: Int,
        val logValues: LongArray,
        val logTicks: LongArray,
        val logCores: IntArray,
        val logPcs: LongArray,
    )

    internal data class ParsedModeWordState(
        val position: Int,
        val values: LongArray,
        val ticks: LongArray,
        val cores: IntArray,
        val pcs: LongArray,
        val retainedWord: Long? = null,
    )

    internal data class ParsedLcdcSnapshotState(
        val position: Int,
        val ticks: LongArray,
        val cores: IntArray,
        val pcs: LongArray,
        val controlWrites: Int,
        val controlReads: Int,
        val controlTicks: LongArray,
        val controlCores: IntArray,
        val controlPcs: LongArray,
    )

    internal data class ParsedLcdcFunctionalState(
        val instance: Int,
        val words: LongArray,
        val readCounts: LongArray,
        val writeCounts: LongArray,
        val resetWrites: Long,
        val resetPolls: Long,
        val resetCompleted: Boolean,
        val observed: Long,
        val logged: Int,
        val logOffsets: IntArray,
        val logWrites: BooleanArray,
        val logValues: LongArray,
        val logTicks: LongArray,
        val logCores: IntArray,
        val logPcs: LongArray,
        val powered: Boolean? = null,
        val clocked: Boolean? = null,
        val unclockedAccessCount: Long = 0L,
        val lastUnclockedOffset: Long? = null,
        val lastUnclockedWrite: Boolean? = null,
    )

    fun requireLong(raw: Any?, field: String): Long {
        val number = raw as? Number ?: throw IllegalArgumentException("Expected integer for $field")
        if (number is Byte || number is Short || number is Int || number is Long) return number.toLong()
        val decimal = number.toDouble()
        require(decimal.isFinite() && decimal % 1.0 == 0.0 && decimal >= Long.MIN_VALUE.toDouble() && decimal < 9.223372036854776E18) {
            "Non-integral number for $field"
        }
        return number.toLong()
    }

    fun requireInt(raw: Any?, field: String, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        val parsed = requireLong(raw, field)
        require(parsed in range.first.toLong()..range.last.toLong()) { "Out-of-range integer for $field: $parsed" }
        return parsed.toInt()
    }

    fun requireUnsignedWord(raw: Any?, field: String): Long {
        val parsed = requireLong(raw, field)
        require(parsed in 0L..0xffffffffL) { "Word out of u32 range for $field: $parsed" }
        return parsed
    }

    fun requireBoolean(raw: Any?, field: String): Boolean =
        raw as? Boolean ?: throw IllegalArgumentException("Expected boolean for $field")

    fun requireString(raw: Any?, field: String): String =
        raw as? String ?: throw IllegalArgumentException("Expected string for $field")

    fun requireMap(raw: Any?, field: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Expected object for $field")
        require(map.keys.all { key -> key is String }) { "Expected string keys for $field" }
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, Any?>
    }

    fun encodeWords(words: LongArray): String {
        val buffer = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (word in words) {
            require(word in 0L..0xffffffffL)
            buffer.putInt(word.toInt())
        }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decodeWords(raw: Any?, expectedCount: Int, field: String): LongArray {
        val bytes = Base64.getDecoder().decode(requireString(raw, field))
        require(bytes.size == expectedCount * 4) { "Invalid word array byte length for $field" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return LongArray(expectedCount) { buffer.getInt().toLong() and 0xffffffffL }
    }

    fun encodeCoordinates(count: Int, ticks: LongArray, cores: IntArray, pcs: LongArray): String {
        val buffer = ByteBuffer.allocate(count * 20).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            buffer.putLong(ticks[index])
            buffer.putInt(cores[index])
            buffer.putLong(pcs[index])
        }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decodeCoordinates(raw: Any?, expectedCount: Int, capacity: Int, field: String): Triple<LongArray, IntArray, LongArray> {
        val bytes = Base64.getDecoder().decode(requireString(raw, field))
        require(bytes.size == expectedCount * 20) { "Invalid coordinate byte length for $field" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ticks = LongArray(capacity)
        val cores = IntArray(capacity) { -1 }
        val pcs = LongArray(capacity) { -1L }
        for (index in 0 until expectedCount) {
            val tick = buffer.getLong()
            val core = buffer.getInt()
            val pcVirtual = buffer.getLong()
            require(tick >= 0L && core >= -1 && pcVirtual >= -1L) {
                "Invalid coordinate values in $field[$index]"
            }
            ticks[index] = tick
            cores[index] = core
            pcs[index] = pcVirtual
        }
        return Triple(ticks, cores, pcs)
    }

    fun parseSequence(
        raw: Any?,
        active: Boolean,
        liveOffsets: LongArray,
        liveReads: LongArray,
        liveWrites: LongArray,
    ): ParsedSequenceState {
        val map = requireMap(raw, "sequence")
        require(map.keys == setOf("position", "offsets", "reads", "writes", "transactions")) {
            "Unexpected keys in vglib sequence snapshot"
        }
        val maxPosition = if (active) 36 else 0
        val position = requireInt(map["position"], "sequence.position", 0..maxPosition)
        val expectedWordCount = if (active) 18 else 0
        val savedOffsets = decodeWords(map["offsets"], expectedWordCount, "sequence.offsets")
        val savedReads = decodeWords(map["reads"], expectedWordCount, "sequence.reads")
        val savedWrites = decodeWords(map["writes"], expectedWordCount, "sequence.writes")
        if (active) {
            require(
                savedOffsets.contentEquals(liveOffsets) &&
                    savedReads.contentEquals(liveReads) &&
                    savedWrites.contentEquals(liveWrites),
            ) { "Vglib finite sequence definition differs between snapshot and live input" }
        }
        val rawTransactions = map["transactions"] as? List<*> ?: throw IllegalArgumentException("Missing sequence.transactions")
        require(rawTransactions.size == position) { "Vglib sequence transaction count disagrees with position" }
        val logValues = LongArray(36)
        val logTicks = LongArray(36)
        val logCores = IntArray(36) { -1 }
        val logPcs = LongArray(36) { -1L }
        rawTransactions.forEachIndexed { index, rawEntry ->
            val entry = requireMap(rawEntry, "sequence.transactions[$index]")
            require(entry.keys == setOf("value", "tick", "core", "pcVirtual")) {
                "Invalid keys in sequence.transactions[$index]"
            }
            val entryValue = requireUnsignedWord(entry["value"], "sequence.transactions[$index].value")
            val expectedValue = if (index % 2 == 0) liveReads[index / 2] else liveWrites[index / 2]
            require(entryValue == expectedValue) { "Vglib logged transaction value differs from finite sequence" }
            val tick = requireLong(entry["tick"], "sequence.transactions[$index].tick")
            require(tick >= 0L) { "Negative tick in sequence.transactions[$index]" }
            val core = requireInt(entry["core"], "sequence.transactions[$index].core", -1..Int.MAX_VALUE)
            val pcVirtual = requireLong(entry["pcVirtual"], "sequence.transactions[$index].pcVirtual")
            require(pcVirtual >= -1L) { "Invalid pcVirtual in sequence.transactions[$index]" }
            logValues[index] = entryValue
            logTicks[index] = tick
            logCores[index] = core
            logPcs[index] = pcVirtual
        }
        return ParsedSequenceState(
            position = position,
            logValues = logValues,
            logTicks = logTicks,
            logCores = logCores,
            logPcs = logPcs,
        )
    }

    fun parseCoordinateObject(raw: Any?, field: String): ParsedCoordinate {
        val entry = requireMap(raw, field)
        require(entry.keys == setOf("tick", "core", "pcVirtual")) { "Invalid coordinate keys in $field" }
        val tick = requireLong(entry["tick"], "$field.tick")
        require(tick >= 0L) { "Negative tick in $field" }
        val core = requireInt(entry["core"], "$field.core", -1..Int.MAX_VALUE)
        val pcVirtual = requireLong(entry["pcVirtual"], "$field.pcVirtual")
        require(pcVirtual >= -1L) { "Invalid pcVirtual in $field" }
        return ParsedCoordinate(tick = tick, core = core, pcVirtual = pcVirtual)
    }
}
