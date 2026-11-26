package com.github.nyabanban.fuji.emulator.components.timer

internal object TimerSnapshotCodec {
    internal data class ParsedCounter(
        val stored: Long,
        val reload: Long,
        val enabled: Boolean,
        val deadline: Long?,
    )

    internal data class ParsedChannelAudit(
        val lastControlWriteMask: Int = 0,
        val lastControlWriteEnableBits: Int = 0,
        val ch6DisableCount: Long = 0L,
        val blindMultiChannelDisableCount: Long = 0L,
        val perChannelDisableCounts: LongArray = LongArray(8),
    )

    internal data class ParsedTimerSnapshot(
        val settingsMap: Map<String, Any?>,
        val savedRouted: Boolean,
        val clockName: String,
        val lastTick: Long,
        val eventCount: Long,
        val elapsedPeriods: Long,
        val accessCount: Long,
        val counters: List<ParsedCounter>,
        val events: List<Map<String, Any?>>,
        val accesses: List<Map<String, Any?>>,
        val channelAudit: ParsedChannelAudit = ParsedChannelAudit(),
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

    fun parse(
        data: Map<String, Any?>,
        expectedSchema: Int,
        expectedId: String,
        liveRouted: Boolean,
        liveClockName: String,
        requiredSettingKeys: Set<String>,
    ): ParsedTimerSnapshot {
        val expectedKeys = setOf(
            "schema", "componentId", "settings", "routed", "clockName",
            "lastTick", "eventCount", "elapsedPeriods", "accessCount",
            "counters", "events", "accesses",
        )
        require(data.keys - "channelAudit" == expectedKeys) { "Unexpected keys in timer snapshot" }
        require(requireInt(data["schema"], "schema") == expectedSchema) { "Unsupported timer snapshot schema" }
        require(data["componentId"] == expectedId) { "Timer snapshot componentId mismatch" }
        val settingsMap = requireMap(data["settings"], "settings")
        require(settingsMap.keys.containsAll(requiredSettingKeys)) { "Missing scalar settings in timer snapshot" }

        val savedProfile = requireString(settingsMap["profile"], "settings.profile")
        val savedRouted = requireBoolean(data["routed"], "routed")
        require(savedRouted == (savedProfile == TimerFactory.ROUTED)) { "Timer snapshot routed flag disagrees with profile" }
        require(savedRouted == liveRouted) { "Timer routing mode mismatch between snapshot and live instance" }

        val clockName = requireString(data["clockName"], "clockName")
        require(clockName in setOf("one-tick-per-attempt", "functional-device-ticks-with-idle")) { "Unknown timer clockName" }
        require(clockName == liveClockName) { "Timer clockName mismatch between snapshot and live instance" }

        val lastTick = requireLong(data["lastTick"], "lastTick")
        require(lastTick >= 0L) { "Negative timer lastTick" }

        val rawCounters = data["counters"] as? List<*> ?: throw IllegalArgumentException("Missing timer counters")
        require(rawCounters.size == 8) { "Timer requires exactly 8 counters" }
        val counters = rawCounters.mapIndexed { channel, rawCounter ->
            val entry = requireMap(rawCounter, "counters[$channel]")
            require(entry.keys == setOf("stored", "reload", "enabled", "deadline")) { "Invalid keys in counters[$channel]" }
            val stored = requireLong(entry["stored"], "counters[$channel].stored")
            require(stored in 0L..0xffffffffL) { "Out-of-range stored word in counters[$channel]" }
            val reload = requireLong(entry["reload"], "counters[$channel].reload")
            require(reload in 0L..0xffffffffL) { "Out-of-range reload word in counters[$channel]" }
            val enabled = requireBoolean(entry["enabled"], "counters[$channel].enabled")
            val rawDeadline = entry["deadline"]
            val deadline = if (rawDeadline == null) null else requireLong(rawDeadline, "counters[$channel].deadline")
            require((deadline != null) == enabled) { "Timer counter[$channel] enabled flag disagrees with deadline presence" }
            if (deadline != null) {
                require(deadline >= 1L) { "Timer counter[$channel] deadline must be positive" }
            }
            ParsedCounter(stored = stored, reload = reload, enabled = enabled, deadline = deadline)
        }

        val rawEvents = data["events"] as? List<*> ?: throw IllegalArgumentException("Missing timer events")
        require(rawEvents.size <= 256) { "Timer events exceed capacity 256" }
        var retainedPeriods = 0L
        val events = rawEvents.mapIndexed { eventIndex, rawEvent ->
            val entry = requireMap(rawEvent, "events[$eventIndex]")
            val eventKeys = setOf(
                "channel", "line", "scheduledDeadline", "observedTick",
                "reloadPeriod", "elapsedPeriods", "nextDeadline", "pendingBefore",
                "core", "pcVirtual",
            )
            require(entry.keys == eventKeys) { "Invalid keys in timer events[$eventIndex]" }
            val channel = requireInt(entry["channel"], "events[$eventIndex].channel", 0..7)
            val line = requireInt(entry["line"], "events[$eventIndex].line", 32..511)
            val scheduledDeadline = requireLong(entry["scheduledDeadline"], "events[$eventIndex].scheduledDeadline")
            require(scheduledDeadline >= 1L) { "Invalid scheduledDeadline in timer event" }
            val observedTick = requireLong(entry["observedTick"], "events[$eventIndex].observedTick")
            require(observedTick >= scheduledDeadline) { "Timer observedTick precedes scheduledDeadline" }
            val reloadPeriod = requireLong(entry["reloadPeriod"], "events[$eventIndex].reloadPeriod")
            require(reloadPeriod in 1L..0x1_0000_0000L) { "Invalid reloadPeriod in timer event" }
            val periods = requireLong(entry["elapsedPeriods"], "events[$eventIndex].elapsedPeriods")
            require(periods >= 1L) { "Invalid elapsedPeriods in timer event" }
            val expectedNext = try {
                Math.addExact(scheduledDeadline, Math.multiplyExact(periods, reloadPeriod))
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Overflow in timer event deadline arithmetic", overflow)
            }
            val nextDeadline = requireLong(entry["nextDeadline"], "events[$eventIndex].nextDeadline")
            require(nextDeadline == expectedNext) { "Timer event nextDeadline mismatch" }
            val pendingBefore = requireBoolean(entry["pendingBefore"], "events[$eventIndex].pendingBefore")
            val core = entry["core"]?.let { rawCore -> requireInt(rawCore, "events[$eventIndex].core", 0..Int.MAX_VALUE) }
            val pcVirtual = entry["pcVirtual"]?.let { rawPc -> requireLong(rawPc, "events[$eventIndex].pcVirtual") }
            require((core == null) == (pcVirtual == null)) { "Timer event core/pcVirtual presence mismatch" }
            retainedPeriods = try {
                Math.addExact(retainedPeriods, periods)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Overflow summing retained timer periods", overflow)
            }
            mapOf<String, Any?>(
                "channel" to channel,
                "line" to line,
                "scheduledDeadline" to scheduledDeadline,
                "observedTick" to observedTick,
                "reloadPeriod" to reloadPeriod,
                "elapsedPeriods" to periods,
                "nextDeadline" to nextDeadline,
                "pendingBefore" to pendingBefore,
                "core" to core,
                "pcVirtual" to pcVirtual,
            )
        }

        val eventCount = requireLong(data["eventCount"], "eventCount")
        require(eventCount >= events.size.toLong()) { "Timer eventCount smaller than retained events" }
        val elapsedPeriods = requireLong(data["elapsedPeriods"], "elapsedPeriods")
        require(elapsedPeriods >= retainedPeriods && (eventCount > 0L || elapsedPeriods == 0L)) {
            "Invalid timer elapsedPeriods aggregate"
        }

        val rawAccesses = data["accesses"] as? List<*> ?: throw IllegalArgumentException("Missing timer accesses")
        require(rawAccesses.size <= 512) { "Timer accesses exceed capacity 512" }
        val accesses = rawAccesses.mapIndexed { accessIndex, rawAccess ->
            val entry = requireMap(rawAccess, "accesses[$accessIndex]")
            require(entry.keys == setOf("tick", "offset", "size", "operation", "value", "core", "pcVirtual")) {
                "Invalid keys in timer accesses[$accessIndex]"
            }
            val tick = requireLong(entry["tick"], "accesses[$accessIndex].tick")
            require(tick >= 0L) { "Negative tick in timer access" }
            val offset = requireLong(entry["offset"], "accesses[$accessIndex].offset")
            require(offset == 0L || (offset in 0x10L..<0x50L && offset % 4L == 0L)) { "Invalid offset in timer access" }
            val size = requireInt(entry["size"], "accesses[$accessIndex].size", 4..4)
            val operation = requireString(entry["operation"], "accesses[$accessIndex].operation")
            require(operation == "read" || operation == "write") { "Invalid operation in timer access" }
            val accessValue = requireLong(entry["value"], "accesses[$accessIndex].value")
            require(accessValue in 0L..0xffffffffL) { "Invalid word value in timer access" }
            val core = entry["core"]?.let { rawCore -> requireInt(rawCore, "accesses[$accessIndex].core", 0..Int.MAX_VALUE) }
            val pcVirtual = entry["pcVirtual"]?.let { rawPc -> requireLong(rawPc, "accesses[$accessIndex].pcVirtual") }
            require((core == null) == (pcVirtual == null)) { "Timer access core/pcVirtual presence mismatch" }
            mapOf<String, Any?>(
                "tick" to tick,
                "offset" to offset,
                "size" to size,
                "operation" to operation,
                "value" to accessValue,
                "core" to core,
                "pcVirtual" to pcVirtual,
            )
        }
        val accessCount = requireLong(data["accessCount"], "accessCount")
        require(accessCount >= accesses.size.toLong()) { "Timer accessCount smaller than retained accesses" }

        if (!savedRouted) {
            require(
                lastTick == 0L && eventCount == 0L && elapsedPeriods == 0L &&
                    events.isEmpty() && accessCount == 0L && accesses.isEmpty(),
            ) { "Strict unrouted timer cannot carry routed logs or lastTick" }
        }

        val parsedAudit = if ("channelAudit" in data) {
            val auditMap = requireMap(data["channelAudit"], "channelAudit")
            require(
                auditMap.keys == setOf(
                    "lastControlWriteMask",
                    "lastControlWriteEnableBits",
                    "ch6DisableCount",
                    "blindMultiChannelDisableCount",
                    "perChannelDisableCounts",
                ),
            ) { "Unexpected keys in timer channelAudit" }
            val mask = requireInt(auditMap["lastControlWriteMask"], "channelAudit.lastControlWriteMask", 0..0xff)
            val enBits = requireInt(auditMap["lastControlWriteEnableBits"], "channelAudit.lastControlWriteEnableBits", 0..0xff)
            val ch6Dis = requireLong(auditMap["ch6DisableCount"], "channelAudit.ch6DisableCount")
            val blindDis = requireLong(auditMap["blindMultiChannelDisableCount"], "channelAudit.blindMultiChannelDisableCount")
            require(ch6Dis >= 0L && blindDis >= 0L) { "Negative timer channelAudit counter" }
            val rawPerCh = auditMap["perChannelDisableCounts"] as? List<*> ?: throw IllegalArgumentException("Missing perChannelDisableCounts")
            require(rawPerCh.size == 8) { "perChannelDisableCounts must have 8 entries" }
            val perCh = LongArray(8) { idx ->
                val v = requireLong(rawPerCh[idx], "channelAudit.perChannelDisableCounts[$idx]")
                require(v >= 0L) { "Negative perChannelDisableCounts[$idx]" }
                v
            }
            require(ch6Dis == perCh[6]) { "ch6DisableCount disagrees with perChannelDisableCounts[6]" }
            ParsedChannelAudit(
                lastControlWriteMask = mask,
                lastControlWriteEnableBits = enBits,
                ch6DisableCount = ch6Dis,
                blindMultiChannelDisableCount = blindDis,
                perChannelDisableCounts = perCh,
            )
        } else {
            ParsedChannelAudit()
        }

        return ParsedTimerSnapshot(
            settingsMap = settingsMap,
            savedRouted = savedRouted,
            clockName = clockName,
            lastTick = lastTick,
            eventCount = eventCount,
            elapsedPeriods = elapsedPeriods,
            accessCount = accessCount,
            counters = counters,
            events = events,
            accesses = accesses,
            channelAudit = parsedAudit,
        )
    }
}
