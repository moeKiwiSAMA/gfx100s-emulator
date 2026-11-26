package com.github.nyabanban.fuji.emulator.components.gic

import java.util.Base64

internal object GicSnapshotCodec {
    internal data class ParsedShared(
        val distributorControl: Int,
        val enabled: BooleanArray,
        val pending: BooleanArray,
        val levels: BooleanArray,
        val active: BooleanArray,
        val edge: BooleanArray,
        val priorities: IntArray,
        val targets: IntArray,
    )

    internal data class ParsedCore(
        val cpuControl: Int,
        val priorityMask: Int,
        val binaryPoint: Int,
        val localEnabled: BooleanArray,
        val localPending: BooleanArray,
        val localLevels: BooleanArray,
        val localActive: BooleanArray,
        val localEdge: BooleanArray,
        val localPriorities: IntArray,
        val localTargets: IntArray,
        val activeStack: List<Int>,
        val eventCount: Long,
        val events: List<Map<String, Any>>,
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

    fun encodeBits(bits: BooleanArray): String {
        require(bits.size % 8 == 0)
        val bytes = ByteArray(bits.size / 8)
        for (bitIndex in bits.indices) {
            if (bits[bitIndex]) {
                val byteIndex = bitIndex ushr 3
                bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (bitIndex and 7))).toByte()
            }
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeBits(raw: Any?, expectedBits: Int, field: String): BooleanArray {
        require(expectedBits % 8 == 0)
        val encoded = requireString(raw, field)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == expectedBits / 8) { "Invalid bitset byte length for $field" }
        return BooleanArray(expectedBits) { bitIndex ->
            (bytes[bitIndex ushr 3].toInt() and (1 shl (bitIndex and 7))) != 0
        }
    }

    fun encodeUnsignedBytes(values: IntArray): String {
        val bytes = ByteArray(values.size) { index ->
            val entry = values[index]
            require(entry in 0..255)
            entry.toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeUnsignedBytes(raw: Any?, expectedSize: Int, maxAllowed: Int, field: String): IntArray {
        val encoded = requireString(raw, field)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == expectedSize) { "Invalid byte array length for $field" }
        return IntArray(expectedSize) { index ->
            val unsigned = bytes[index].toInt() and 0xff
            require(unsigned in 0..maxAllowed) { "Value out of range in $field at $index: $unsigned" }
            unsigned
        }
    }

    fun parseShared(raw: Any?, coreCount: Int): ParsedShared {
        val map = requireMap(raw, "shared")
        val expectedKeys = setOf("distributorControl", "enabled", "pending", "levels", "active", "edge", "priorities", "targets")
        require(map.keys == expectedKeys) { "Unexpected keys in shared GIC state" }
        val maxTargetMask = (1 shl coreCount) - 1
        return ParsedShared(
            distributorControl = requireInt(map["distributorControl"], "shared.distributorControl", 0..1),
            enabled = decodeBits(map["enabled"], 480, "shared.enabled"),
            pending = decodeBits(map["pending"], 480, "shared.pending"),
            levels = decodeBits(map["levels"], 480, "shared.levels"),
            active = decodeBits(map["active"], 480, "shared.active"),
            edge = decodeBits(map["edge"], 480, "shared.edge"),
            priorities = decodeUnsignedBytes(map["priorities"], 480, 255, "shared.priorities"),
            targets = decodeUnsignedBytes(map["targets"], 480, maxTargetMask, "shared.targets"),
        )
    }

    fun parseCore(raw: Any?, coreIndex: Int, coreCount: Int, singleCoreSgiEnabled: Boolean): ParsedCore {
        val map = requireMap(raw, "core[$coreIndex]")
        val expectedKeys = setOf(
            "cpuControl", "priorityMask", "binaryPoint",
            "localEnabled", "localPending", "localLevels", "localActive", "localEdge",
            "localPriorities", "localTargets", "activeStack", "eventCount", "events",
        )
        require(map.keys == expectedKeys) { "Unexpected keys in GIC core[$coreIndex]" }
        val cpuControl = requireInt(map["cpuControl"], "cpuControl", 0..5)
        require(cpuControl in (if (coreCount > 1) setOf(0, 1, 4, 5) else setOf(0, 1))) {
            "Out-of-range integer for cpuControl: $cpuControl"
        }
        val priorityMask = requireInt(map["priorityMask"], "priorityMask", 0..255)
        val binaryPoint = requireInt(map["binaryPoint"], "binaryPoint", 0..7)
        val localEnabled = decodeBits(map["localEnabled"], 32, "localEnabled")
        require((0..15).all { line -> localEnabled[line] }) { "GIC SGI lines 0..15 must remain enabled" }
        val localPending = decodeBits(map["localPending"], 32, "localPending")
        val localLevels = decodeBits(map["localLevels"], 32, "localLevels")
        require(localLevels.none { level -> level }) { "GIC private lines 0..31 cannot hold external line levels" }
        val localActive = decodeBits(map["localActive"], 32, "localActive")
        val localEdge = decodeBits(map["localEdge"], 32, "localEdge")
        val localPriorities = decodeUnsignedBytes(map["localPriorities"], 32, 255, "localPriorities")
        val expectedLocalTarget = 1 shl coreIndex
        val localTargets = decodeUnsignedBytes(map["localTargets"], 32, 15, "localTargets")
        require(localTargets.all { mask -> mask == expectedLocalTarget }) { "GIC banked local targets must match core mask" }

        val rawStack = map["activeStack"] as? List<*> ?: throw IllegalArgumentException("Missing activeStack")
        require(rawStack.size <= 512) { "GIC activeStack exceeds line count" }
        val seenLines = HashSet<Int>(rawStack.size)
        val activeStack = rawStack.mapIndexed { stackIndex, rawToken ->
            val token = requireInt(rawToken, "activeStack[$stackIndex]", 0..0x0fff)
            val line = token and 1023
            require(line in 0..511) { "Invalid active IRQ line $line" }
            if (line < 16) {
                if (coreCount == 1) {
                    require(singleCoreSgiEnabled && token == line) { "Invalid single-core SGI active token $token" }
                } else {
                    val sender = token ushr 10
                    require(sender in 0..3 && token == (line or (sender shl 10))) { "Invalid cluster SGI active token $token" }
                }
            } else {
                require(token == line) { "Non-SGI active token cannot carry sender bits: $token" }
            }
            require(seenLines.add(line)) { "Duplicate active IRQ line $line in core[$coreIndex] stack" }
            token
        }
        for (privateLine in 0..31) {
            require(localActive[privateLine] == (privateLine in seenLines)) {
                "GIC core[$coreIndex] localActive[$privateLine] disagrees with activeStack"
            }
        }
        if (coreCount == 1 && !singleCoreSgiEnabled) {
            require((0..15).none { sgiLine -> localPending[sgiLine] || localActive[sgiLine] }) {
                "Single-core SGI state present while sgiProfile is disabled"
            }
        }

        val rawEvents = map["events"] as? List<*> ?: throw IllegalArgumentException("Missing GIC events list")
        require(rawEvents.size <= 512) { "GIC event log exceeds bounded capacity" }
        val events = rawEvents.mapIndexed { eventIndex, rawEvent ->
            parseEvent(rawEvent, coreCount, singleCoreSgiEnabled, eventIndex)
        }
        val eventCount = requireLong(map["eventCount"], "eventCount")
        require(eventCount >= events.size.toLong()) { "GIC eventCount smaller than retained events" }

        return ParsedCore(
            cpuControl = cpuControl,
            priorityMask = priorityMask,
            binaryPoint = binaryPoint,
            localEnabled = localEnabled,
            localPending = localPending,
            localLevels = localLevels,
            localActive = localActive,
            localEdge = localEdge,
            localPriorities = localPriorities,
            localTargets = localTargets,
            activeStack = activeStack,
            eventCount = eventCount,
            events = events,
        )
    }

    private fun parseEvent(rawEvent: Any?, coreCount: Int, singleCoreSgiEnabled: Boolean, eventIndex: Int): Map<String, Any> {
        val entry = requireMap(rawEvent, "events[$eventIndex]")
        val operation = requireString(entry["operation"], "events[$eventIndex].operation")
        val tick = requireLong(entry["tick"], "events[$eventIndex].tick")
        require(tick >= 0L) { "Negative tick in GIC event" }
        return when (operation) {
            "line" -> {
                require(entry.keys == setOf("operation", "tick", "line", "high")) { "Invalid GIC line event keys" }
                val line = requireInt(entry["line"], "events[$eventIndex].line", 32..511)
                val high = requireBoolean(entry["high"], "events[$eventIndex].high")
                mapOf("operation" to "line", "tick" to tick, "line" to line, "high" to high)
            }
            "acknowledge" -> {
                if ("token" in entry) {
                    require(coreCount == 4 && entry.keys == setOf("operation", "tick", "line", "sender", "token")) {
                        "Invalid cluster SGI acknowledge event"
                    }
                    val line = requireInt(entry["line"], "events[$eventIndex].line", 0..15)
                    val sender = requireInt(entry["sender"], "events[$eventIndex].sender", 0..3)
                    val token = requireInt(entry["token"], "events[$eventIndex].token", 0..0x0fff)
                    require(token == (line or (sender shl 10))) { "Mismatched SGI acknowledge token" }
                    mapOf("operation" to "acknowledge", "tick" to tick, "line" to line, "sender" to sender, "token" to token)
                } else {
                    require(entry.keys == setOf("operation", "tick", "line")) { "Invalid GIC acknowledge event keys" }
                    val minLine = if (coreCount == 4) 16 else if (singleCoreSgiEnabled) 0 else 16
                    val line = requireInt(entry["line"], "events[$eventIndex].line", minLine..511)
                    mapOf("operation" to "acknowledge", "tick" to tick, "line" to line)
                }
            }
            "eoi" -> {
                if ("token" in entry) {
                    require(coreCount == 4 && entry.keys == setOf("operation", "tick", "line", "token")) {
                        "Invalid cluster SGI EOI event"
                    }
                    val line = requireInt(entry["line"], "events[$eventIndex].line", 0..15).toLong()
                    val token = requireInt(entry["token"], "events[$eventIndex].token", 0..0x0fff).toLong()
                    val sender = token ushr 10
                    require(sender in 0L..3L && token == (line or (sender shl 10))) { "Mismatched SGI EOI token" }
                    mapOf("operation" to "eoi", "tick" to tick, "line" to line, "token" to token)
                } else {
                    require(entry.keys == setOf("operation", "tick", "line")) { "Invalid GIC EOI event keys" }
                    val minLine = if (coreCount == 4) 16 else if (singleCoreSgiEnabled) 0 else 16
                    val line = requireInt(entry["line"], "events[$eventIndex].line", minLine..511).toLong()
                    mapOf("operation" to "eoi", "tick" to tick, "line" to line)
                }
            }
            "sgi" -> {
                if (coreCount == 1) {
                    require(singleCoreSgiEnabled && entry.keys == setOf("operation", "tick", "line", "targetFilter", "targetList", "deliveredCpu0", "modeledCpus")) {
                        "Invalid single-core SGI event"
                    }
                    val line = requireInt(entry["line"], "events[$eventIndex].line", 0..15)
                    val targetFilter = requireInt(entry["targetFilter"], "events[$eventIndex].targetFilter", 0..2)
                    val targetList = requireInt(entry["targetList"], "events[$eventIndex].targetList", 0..255).toLong()
                    val deliveredCpu0 = requireBoolean(entry["deliveredCpu0"], "events[$eventIndex].deliveredCpu0")
                    val modeledCpus = requireInt(entry["modeledCpus"], "events[$eventIndex].modeledCpus", 1..1)
                    mapOf(
                        "operation" to "sgi", "tick" to tick, "line" to line, "targetFilter" to targetFilter,
                        "targetList" to targetList, "deliveredCpu0" to deliveredCpu0, "modeledCpus" to modeledCpus,
                    )
                } else {
                    require(entry.keys == setOf("operation", "tick", "line", "sender", "targets")) {
                        "Invalid cluster SGI event"
                    }
                    val line = requireInt(entry["line"], "events[$eventIndex].line", 0..15)
                    val sender = requireInt(entry["sender"], "events[$eventIndex].sender", 0..3)
                    val rawTargets = entry["targets"] as? List<*> ?: throw IllegalArgumentException("Invalid SGI targets list")
                    val targets = rawTargets.mapIndexed { targetIndex, rawTarget ->
                        requireInt(rawTarget, "events[$eventIndex].targets[$targetIndex]", 0..3)
                    }
                    require(targets == targets.distinct().sorted()) { "Cluster SGI targets must be sorted and distinct" }
                    mapOf("operation" to "sgi", "tick" to tick, "line" to line, "sender" to sender, "targets" to targets)
                }
            }
            else -> throw IllegalArgumentException("Unknown GIC event operation: $operation")
        }
    }

    fun validateSharedActiveAgainstStacks(sharedActive: BooleanArray, cores: List<ParsedCore>) {
        val claimedSpis = HashSet<Int>()
        for (parsedCore in cores) {
            for (token in parsedCore.activeStack) {
                val line = token and 1023
                if (line >= 32) {
                    require(claimedSpis.add(line)) { "SPI $line cannot be simultaneously active on multiple cores" }
                }
            }
        }
        for (spiOffset in 0 until 480) {
            val spiLine = spiOffset + 32
            require(sharedActive[spiOffset] == (spiLine in claimedSpis)) {
                "Shared active bit for SPI $spiLine disagrees with core active stacks"
            }
        }
    }
}
