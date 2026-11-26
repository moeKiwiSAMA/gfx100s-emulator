package com.github.nyabanban.fuji.emulator.components.method4

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal object Method4SnapshotCodec {
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")

    internal data class ParsedMethod4Snapshot(
        val settingsMap: Map<String, Any?>,
        val compatibleKeys: Set<String>,
        val registers: LongArray,
        val configured: Boolean,
        val pending: Boolean,
        val completedSha: String?,
        val stream: Stream,
        val eventCount: Long,
        val events: List<Map<String, Any?>>,
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

    fun requireSha256(raw: Any?, field: String): String {
        val text = requireString(raw, field)
        require(SHA256_REGEX.matches(text)) { "Invalid SHA-256 hex digest in $field" }
        return text
    }

    fun requireMap(raw: Any?, field: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Expected object for $field")
        require(map.keys.all { key -> key is String }) { "Expected string keys for $field" }
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, Any?>
    }

    fun encodeRegisters(registers: LongArray): String {
        require(registers.size == 27)
        val buffer = ByteBuffer.allocate(27 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (word in registers) {
            require(word in 0L..0xffffffffL)
            buffer.putInt(word.toInt())
        }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decodeRegisters(raw: Any?, modeledOffsets: Set<Int>): LongArray {
        val bytes = Base64.getDecoder().decode(requireString(raw, "registers"))
        require(bytes.size == 27 * 4) { "Invalid method-4 registers byte length" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val registers = LongArray(27) { buffer.getInt().toLong() and 0xffffffffL }
        for (regIndex in registers.indices) {
            val offset = regIndex * 4
            if (offset !in modeledOffsets || offset == 0 || offset == 4 || offset == 0x24 || offset == 0x40) {
                require(registers[regIndex] == 0L) { "Invalid non-zero method-4 register at offset 0x${offset.toString(16)}" }
            }
        }
        require(registers[8] in setOf(0L, 0x84000400L, 0x94001400L)) { "Invalid method-4 configuration register value" }
        return registers
    }

    fun parse(
        data: Map<String, Any?>,
        expectedSchema: Int,
        expectedId: String,
        liveValues: Map<String, Any>,
        modeledOffsets: Set<Int>,
    ): ParsedMethod4Snapshot {
        val expectedKeys = setOf(
            "schema", "componentId", "settings", "registers",
            "configured", "irqPending", "completedSha256",
            "stream", "eventCount", "events",
        )
        require(data.keys == expectedKeys) { "Unexpected keys in soc-method4 snapshot" }
        require(requireInt(data["schema"], "schema") == expectedSchema) { "Unsupported soc-method4 snapshot schema" }
        require(data["componentId"] == expectedId) { "Snapshot componentId mismatch for $expectedId" }

        val settingsMap = requireMap(data["settings"], "settings")
        require(settingsMap.keys.containsAll(liveValues.keys)) { "Missing scalar settings in soc-method4 snapshot" }
        val savedProfile = requireString(settingsMap["profile"], "settings.profile")
        val liveProfile = requireString(liveValues["profile"], "live.profile")
        val profileUpgrade = savedProfile == Method4Factory.V1_PROFILE && liveProfile == Method4Factory.V2_PROFILE
        require(savedProfile == liveProfile || profileUpgrade) {
            "Incompatible soc-method4 profile transition: $savedProfile -> $liveProfile"
        }
        val savedIrq = requireInt(settingsMap["irq"], "settings.irq", 32..511)
        val liveIrq = requireInt(liveValues["irq"], "live.irq", 32..511)
        require(savedIrq == liveIrq) { "Restart-only setting irq differs in snapshot for $expectedId" }

        val compatibleKeys = LinkedHashSet<String>()
        if (profileUpgrade) compatibleKeys.add("profile")
        if (liveValues["irq"] !is Long) compatibleKeys.add("irq")

        val registers = decodeRegisters(data["registers"], modeledOffsets)
        val configured = requireBoolean(data["configured"], "configured")
        require(configured == (registers[8] == 0x94001400L)) {
            "Method-4 configured flag disagrees with configuration register"
        }
        val pending = requireBoolean(data["irqPending"], "irqPending")
        require(pending == (((registers[10] or registers[26]) and 1L) != 0L)) {
            "Method-4 irqPending flag disagrees with interrupt status registers"
        }
        val liveInterruptPort = requireString(liveValues["interruptPort"], "live.interruptPort")
        require(!pending || liveInterruptPort.isNotEmpty()) {
            "Method-4 pending IRQ cannot restore onto unrouted live instance"
        }
        val completedSha = data["completedSha256"]?.let { rawHash -> requireSha256(rawHash, "completedSha256") }

        val streamMap = requireMap(data["stream"], "stream")
        val streamKeys = setOf(
            "header", "sourceBase", "destination", "consumed", "produced",
            "page", "literalRemaining", "matchFirst", "historyBase64",
        )
        require(streamMap.keys == streamKeys) { "Unexpected keys in method-4 stream snapshot" }
        val historyBytes = Base64.getDecoder().decode(requireString(streamMap["historyBase64"], "stream.historyBase64"))
        require(historyBytes.size == 2048) { "Method-4 LZ history requires exactly 2048 bytes" }

        val sourceBase = requireLong(streamMap["sourceBase"], "stream.sourceBase")
        val destination = requireLong(streamMap["destination"], "stream.destination")
        val consumed = requireInt(streamMap["consumed"], "stream.consumed", 20..0x4000000)
        val produced = requireInt(streamMap["produced"], "stream.produced", 0..0x4000000)
        val page = requireInt(streamMap["page"], "stream.page", 0..0x1001)
        val literalRemaining = requireInt(streamMap["literalRemaining"], "stream.literalRemaining", 0..127)
        val matchFirst = streamMap["matchFirst"]?.let { rawMatch ->
            requireInt(rawMatch, "stream.matchFirst", 128..255)
        }
        require(literalRemaining == 0 || matchFirst == null) {
            "Method-4 stream cannot hold both active literal run and partial match token"
        }

        val rawHeader = streamMap["header"]
        val parsedHeader: List<Int>? = if (rawHeader == null) {
            require(
                sourceBase == 0L && destination == 0L && consumed == 20 &&
                    produced == 0 && page == 0 && literalRemaining == 0 && matchFirst == null,
            ) { "Uninitialized method-4 stream must hold default counters" }
            require(historyBytes.all { byteVal -> byteVal.toInt() == 0 }) {
                "Uninitialized method-4 stream must hold zeroed history"
            }
            require(completedSha == null) { "Uninitialized method-4 stream cannot carry completedSha256" }
            null
        } else {
            val headerList = rawHeader as? List<*> ?: throw IllegalArgumentException("Invalid stream.header list")
            require(headerList.size == 5) { "Method-4 stream header requires 5 words" }
            val uncompressedSize = requireInt(headerList[0], "stream.header[0]", 1..0x4000000)
            val packedSize = requireInt(headerList[1], "stream.header[1]", 20..0x4000000)
            val pageCount = requireInt(headerList[2], "stream.header[2]", 1..0x1001)
            val pageSize = requireInt(headerList[3], "stream.header[3]", 0x4000..0x4000)
            val method = requireInt(headerList[4], "stream.header[4]", 4..4)
            require(pageCount == (packedSize + 0x3fff) / 0x4000) { "Inconsistent method-4 header pageCount" }
            require(sourceBase in 0L..0xffffffffL && destination in 0L..0xffffffffL) {
                "Method-4 stream base addresses out of range"
            }
            require(destination + uncompressedSize <= sourceBase || sourceBase + packedSize <= destination) {
                "Method-4 snapshot source and destination overlap"
            }
            require(page in 0..pageCount) { "Method-4 page exceeds declared pageCount" }
            val expectedConsumed = if (page == 0) 20 else minOf(page * 0x4000, packedSize)
            require(consumed == expectedConsumed) { "Method-4 consumed byte count disagrees with page progress" }
            require(produced in 0..uncompressedSize) { "Method-4 produced byte count exceeds declared size" }
            if (page == pageCount) {
                require(
                    consumed == packedSize && produced == uncompressedSize &&
                        literalRemaining == 0 && matchFirst == null && completedSha != null,
                ) { "Completed method-4 stream missing terminal invariants or completedSha256" }
            } else {
                require(completedSha == null) { "Incomplete method-4 stream cannot carry completedSha256" }
                if (profileUpgrade && sourceBase == 0x1c08000L) {
                    require(consumed < 0x40000) { "Cannot upgrade linear mid-stream past ring wrap boundary" }
                }
            }
            listOf(uncompressedSize, packedSize, pageCount, pageSize, method)
        }

        val parsedStream = Stream(
            header = parsedHeader,
            sourceBase = sourceBase,
            destination = destination,
            consumed = consumed,
            produced = produced,
            page = page,
            literal = literalRemaining,
            matchFirst = matchFirst,
            history = historyBytes,
        )

        val rawEvents = data["events"] as? List<*> ?: throw IllegalArgumentException("Missing method-4 events list")
        require(rawEvents.size <= 4096) { "Method-4 events exceed bounded capacity 4096" }
        val eventKeys = setOf(
            "tick", "source", "destination", "inputSize", "outputSize",
            "page", "consumed", "produced", "inputSha256", "outputSha256", "completedSha256",
        )
        val events = rawEvents.mapIndexed { eventIndex, rawEvent ->
            val entry = requireMap(rawEvent, "events[$eventIndex]")
            require(entry.keys == eventKeys) { "Invalid keys in method-4 events[$eventIndex]" }
            val tick = requireLong(entry["tick"], "events[$eventIndex].tick")
            require(tick >= 0L) { "Negative tick in method-4 event" }
            val eventSource = requireUnsignedWord(entry["source"], "events[$eventIndex].source")
            val eventDestination = requireLong(entry["destination"], "events[$eventIndex].destination")
            require(eventDestination >= 0L) { "Negative destination in method-4 event" }
            val inputSize = requireInt(entry["inputSize"], "events[$eventIndex].inputSize", 0..0x4000)
            val outputSize = requireInt(entry["outputSize"], "events[$eventIndex].outputSize", 0..0x4000000)
            val eventPage = requireInt(entry["page"], "events[$eventIndex].page", 1..0x1001)
            val eventConsumed = requireInt(entry["consumed"], "events[$eventIndex].consumed", 20..0x4000000)
            val eventProduced = requireInt(entry["produced"], "events[$eventIndex].produced", 0..0x4000000)
            val inputSha = requireSha256(entry["inputSha256"], "events[$eventIndex].inputSha256")
            val outputSha = requireSha256(entry["outputSha256"], "events[$eventIndex].outputSha256")
            val eventCompletedSha = entry["completedSha256"]?.let { rawHash ->
                requireSha256(rawHash, "events[$eventIndex].completedSha256")
            }
            mapOf<String, Any?>(
                "tick" to tick,
                "source" to eventSource,
                "destination" to eventDestination,
                "inputSize" to inputSize,
                "outputSize" to outputSize,
                "page" to eventPage,
                "consumed" to eventConsumed,
                "produced" to eventProduced,
                "inputSha256" to inputSha,
                "outputSha256" to outputSha,
                "completedSha256" to eventCompletedSha,
            )
        }
        val eventCount = requireLong(data["eventCount"], "eventCount")
        require(eventCount >= events.size.toLong()) { "Method-4 eventCount smaller than retained events" }

        return ParsedMethod4Snapshot(
            settingsMap = settingsMap,
            compatibleKeys = compatibleKeys,
            registers = registers,
            configured = configured,
            pending = pending,
            completedSha = completedSha,
            stream = parsedStream,
            eventCount = eventCount,
            events = events,
        )
    }
}
