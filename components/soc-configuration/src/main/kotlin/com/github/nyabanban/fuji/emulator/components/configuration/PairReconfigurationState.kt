package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.ComponentContext

/** Functional HYPOTHESIS: DAT qualifies the software sequence, not begin/commit hardware semantics. */
internal class PairReconfigurationState(
    private val context: ComponentContext,
    private val base: BaseParameterState,
    private val completionTicks: Long,
) {
    companion object {
        const val PROFILE = "configuration-pair-reconfiguration-experimental-v2"
        val baseIndices = listOf(3, 9, 12)
        val groups = listOf(10, 16, 19)
        private val slotByBaseIndex = IntArray(13) { -1 }.apply {
            baseIndices.forEachIndexed { slot, idx -> this[idx] = slot }
        }
        private fun slotOrMinusOne(index: Int): Int = if (index in 0..12) slotByBaseIndex[index] else -1
        private val SNAPSHOT_KEYS = setOf("profile", "completionTicks", "generations", "contexts", "eventCount", "events")
        private val CONTEXT_KEYS = setOf("generation", "originalPair", "openAt", "phase", "freshWords", "submittedPair")
        private val EVENT_KEYS = setOf("operation", "index", "tick", "generation", "originalPair", "pair")
        private val OPERATIONS = setOf("begin", "open", "submit6", "consume")
        private fun snapshotLong(value: Any?, field: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot field $field requires integer" }
            val converted = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum) { "Snapshot field $field out of range" }
            return converted
        }
        private fun snapshotInt(value: Any?, field: String, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int =
            snapshotLong(value, field, minimum.toLong(), maximum.toLong()).toInt()
        private fun snapshotString(value: Any?, field: String): String =
            value as? String ?: throw IllegalArgumentException("Snapshot field $field requires string")
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
        private fun parsePair(rawPair: Any?, field: String): List<Long> {
            val pairValues = snapshotList(rawPair, field)
            require(pairValues.size == 2) { "Snapshot field $field requires 2 words" }
            return java.util.List.copyOf(listOf(
                snapshotLong(pairValues[0], "$field[0]", 0L, 0xffffffffL),
                snapshotLong(pairValues[1], "$field[1]", 0L, 0xffffffffL),
            ))
        }
    }
    private data class Transaction(val generation: Long, val originalPair: List<Long>, val openAt: Long,
        var phase: Int = 1, var freshWords: Int = 0, var submittedPair: List<Long>? = null)
    private data class Event(val operation: String, val index: Int, val tick: Long, val generation: Long,
        val originalPair: List<Long>, val pair: List<Long>?)
    internal class ValidatedSnapshot(
        val generations: LongArray,
        internal val transactions: Array<Any?>,
        val eventCount: Long,
        internal val events: List<Any>,
    ) {
        fun active(index: Int): Boolean = slotOrMinusOne(index).let { slotIndex -> slotIndex >= 0 && transactions[slotIndex] != null }
    }
    private val generations = LongArray(3)
    private val transactions = arrayOfNulls<Transaction>(3)
    private val events = java.util.ArrayDeque<Event>()
    private var eventCount = 0L
    private fun slot(index: Int) = slotOrMinusOne(index).also { require(it >= 0) { "reconfiguration group is not supported by this model" } }
    private fun now() = context.clock.ticks().also { require(it >= 0) }
    private fun preflightEvent() = require(eventCount < Long.MAX_VALUE) { "reconfiguration event counter overflow" }
    private fun record(operation: String, index: Int, tick: Long, transaction: Transaction) {
        if (events.size == 256) events.removeFirst()
        events.addLast(Event(operation, index, tick, transaction.generation, transaction.originalPair, transaction.submittedPair))
        eventCount++
    }
    fun active(index: Int): Boolean {
        val s = slotOrMinusOne(index)
        return s >= 0 && transactions[s] != null
    }
    fun preflightWrite(index: Int) {
        val s = slotOrMinusOne(index)
        if (s >= 0 && transactions[s] != null) require(transactions[s]!!.phase == 2) { "base write in this reconfiguration phase is not supported by this model" }
    }
    fun wrote(index: Int, offset: Int) {
        val s = slotOrMinusOne(index)
        if (s >= 0 && transactions[s] != null) transactions[s]!!.freshWords = transactions[s]!!.freshWords or if (offset == 0) 1 else 2
    }
    fun nextDeadline(index: Int): Long? {
        val s = slotOrMinusOne(index)
        if (s < 0) return null
        val t = transactions[s] ?: return null
        return if (t.phase == 1) t.openAt else null
    }
    fun request(index: Int): Long? {
        val s = slotOrMinusOne(index)
        if (s < 0) return null
        val transaction = transactions[s] ?: return null
        return when (transaction.phase) { 1 -> 5L; 2 -> 0L; else -> if (base.request(index) != 0L) 6L else 0L }
    }
    fun begin(index: Int) {
        val slot = slot(index)
        require(transactions[slot] == null && !base.busy(index)) { "reconfiguration begin/reissue while active is not supported by this model" }
        val tick = now()
        val generation = Math.incrementExact(generations[slot])
        val openAt = Math.addExact(tick, completionTicks)
        preflightEvent()
        val transaction = Transaction(generation, base.appliedPair(index), openAt)
        generations[slot] = generation; transactions[slot] = transaction
        record("begin", index, tick, transaction)
    }
    fun commit(index: Int) {
        val transaction = transactions[slot(index)]
        require(transaction != null && transaction.phase == 2 && transaction.freshWords == 3 && !base.busy(index)) {
            "reconfiguration commit requires this model's open context and both new words"
        }
        val tick = now(); preflightEvent()
        // Base performs its complete counter/deadline preflight before changing pair/status/phase.
        base.submitReconfiguration(index)
        transaction.submittedPair = base.stagedPair(index); transaction.phase = 3
        record("submit6", index, tick, transaction)
    }
    fun advance(index: Int, tick: Long) {
        require(tick >= 0)
        if (!active(index)) { base.advance(index, tick); return }
        val slot = slot(index); val transaction = transactions[slot]!!
        if (transaction.phase == 1) {
            if (tick >= transaction.openAt) { preflightEvent(); transaction.phase = 2; record("open", index, tick, transaction) }
        } else if (transaction.phase == 3) {
            val consumes = base.settlesAt(index)?.let { tick >= it } == true
            if (consumes) preflightEvent()
            base.advance(index, tick)
            if (consumes) { record("consume", index, tick, transaction); transactions[slot] = null }
        }
    }
    fun state(): Map<String, Any?> = mapOf(
        "profile" to PROFILE, "eligibleBaseIndices" to baseIndices, "eligibleGroups" to groups,
        "commands" to listOf(5, 6), "requestMask" to 7L, "statusMask" to 1L,
        "completionTicks" to completionTicks, "generations" to generations.toList(),
        "contexts" to transactions.mapIndexed { slot, t -> t?.let { mapOf("generation" to it.generation,
            "originalPair" to it.originalPair, "openAt" to it.openAt, "freshWords" to it.freshWords,
            "phase" to when (it.phase) { 1 -> "preparing"; 2 -> "open"; else -> if (base.request(baseIndices[slot]) != 0L) "request" else "settling" },
            "submittedPair" to it.submittedPair) } },
        "eventCount" to eventCount, "eventCapacity" to 256, "eventLogTruncated" to (eventCount > events.size),
        "events" to events.map { mapOf("operation" to it.operation, "index" to it.index, "group" to BaseParameterState.groups[it.index],
            "tick" to it.tick, "generation" to it.generation, "originalPair" to it.originalPair, "pair" to it.pair) },
        "causalMapping" to "HYPOTHESIS", "timing" to "HYPOTHESIS", "physicalFunction" to "UNKNOWN",
        "physicalClockResetEffects" to false, "irq" to false,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "completionTicks" to completionTicks,
        "generations" to generations.toList(),
        "contexts" to transactions.map { transaction ->
            transaction?.let { activeTransaction ->
                linkedMapOf(
                    "generation" to activeTransaction.generation,
                    "originalPair" to activeTransaction.originalPair,
                    "openAt" to activeTransaction.openAt,
                    "phase" to activeTransaction.phase,
                    "freshWords" to activeTransaction.freshWords,
                    "submittedPair" to activeTransaction.submittedPair,
                )
            }
        },
        "eventCount" to eventCount,
        "events" to events.map { entry ->
            linkedMapOf(
                "operation" to entry.operation,
                "index" to entry.index,
                "tick" to entry.tick,
                "generation" to entry.generation,
                "originalPair" to entry.originalPair,
                "pair" to entry.pair,
            )
        },
    )

    fun validateSnapshot(raw: Any?, validatedBase: BaseParameterState.ValidatedSnapshot): ValidatedSnapshot {
        val section = snapshotMap(raw, "reconfiguration")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected reconfiguration snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported reconfiguration snapshot profile" }
        require(snapshotLong(section["completionTicks"], "reconfiguration.completionTicks", 1L, 1_000_000L) == completionTicks) {
            "reconfiguration completionTicks differ from live configuration"
        }
        val generationRows = snapshotList(section["generations"], "reconfiguration.generations")
        require(generationRows.size == 3) { "reconfiguration generations require 3 entries" }
        val nextGenerations = LongArray(3) { slotIndex ->
            snapshotLong(generationRows[slotIndex], "reconfiguration.generations[$slotIndex]", 0L, Long.MAX_VALUE)
        }
        val contextRows = snapshotList(section["contexts"], "reconfiguration.contexts")
        require(contextRows.size == 3) { "reconfiguration contexts require 3 entries" }
        val nextTransactions = arrayOfNulls<Any>(3)
        for (slotIndex in 0 until 3) {
            val rawContext = contextRows[slotIndex] ?: continue
            val contextMap = snapshotMap(rawContext, "reconfiguration.contexts[$slotIndex]")
            require(contextMap.keys == CONTEXT_KEYS) { "Unexpected reconfiguration context keys" }
            val generation = snapshotLong(contextMap["generation"], "contexts[$slotIndex].generation", 1L, Long.MAX_VALUE)
            require(generation == nextGenerations[slotIndex]) { "Active reconfiguration generation must equal slot generation" }
            val originalPair = parsePair(contextMap["originalPair"], "contexts[$slotIndex].originalPair")
            val openAt = snapshotLong(contextMap["openAt"], "contexts[$slotIndex].openAt", 1L, Long.MAX_VALUE)
            val phaseValue = snapshotInt(contextMap["phase"], "contexts[$slotIndex].phase", 1, 3)
            val freshWords = snapshotInt(contextMap["freshWords"], "contexts[$slotIndex].freshWords", 0, 3)
            val submittedPair = contextMap["submittedPair"]?.let { rawPair -> parsePair(rawPair, "contexts[$slotIndex].submittedPair") }
            val baseIndex = baseIndices[slotIndex]
            when (phaseValue) {
                1 -> require(freshWords == 0 && submittedPair == null && !validatedBase.busy(baseIndex)) {
                    "Invalid preparing reconfiguration context at slot $slotIndex"
                }
                2 -> require(submittedPair == null && !validatedBase.busy(baseIndex)) {
                    "Invalid open reconfiguration context at slot $slotIndex"
                }
                3 -> require(freshWords == 3 && submittedPair != null && validatedBase.busy(baseIndex)) {
                    "Invalid submitted reconfiguration context at slot $slotIndex"
                }
            }
            nextTransactions[slotIndex] = Transaction(generation, originalPair, openAt, phaseValue, freshWords, submittedPair)
        }
        val nextEventCount = snapshotLong(section["eventCount"], "reconfiguration.eventCount", 0L, Long.MAX_VALUE)
        val eventRows = snapshotList(section["events"], "reconfiguration.events")
        require(eventRows.size == minOf(nextEventCount, 256L).toInt()) { "reconfiguration events size mismatch" }
        val nextEvents = eventRows.mapIndexed { rowOffset, rawEvent ->
            val eventMap = snapshotMap(rawEvent, "reconfiguration.events[$rowOffset]")
            require(eventMap.keys == EVENT_KEYS) { "Unexpected reconfiguration event keys" }
            val operation = snapshotString(eventMap["operation"], "reconfiguration.events[$rowOffset].operation")
            require(operation in OPERATIONS) { "Unsupported reconfiguration event operation" }
            val baseIndex = snapshotInt(eventMap["index"], "reconfiguration.events[$rowOffset].index")
            require(baseIndex in baseIndices) { "Unsupported reconfiguration event index" }
            val eventTick = snapshotLong(eventMap["tick"], "reconfiguration.events[$rowOffset].tick", 0L, Long.MAX_VALUE)
            val generation = snapshotLong(eventMap["generation"], "reconfiguration.events[$rowOffset].generation", 1L, Long.MAX_VALUE)
            val originalPair = parsePair(eventMap["originalPair"], "reconfiguration.events[$rowOffset].originalPair")
            val pairValue = eventMap["pair"]?.let { rawPair -> parsePair(rawPair, "reconfiguration.events[$rowOffset].pair") }
            if (operation == "begin" || operation == "open") {
                require(pairValue == null) { "reconfiguration $operation event must not carry submitted pair" }
            } else {
                require(pairValue != null) { "reconfiguration $operation event requires submitted pair" }
            }
            Event(operation, baseIndex, eventTick, generation, originalPair, pairValue)
        }
        return ValidatedSnapshot(nextGenerations, nextTransactions, nextEventCount, nextEvents)
    }

    fun applySnapshot(validated: ValidatedSnapshot) {
        validated.generations.copyInto(generations)
        for (slotIndex in transactions.indices) {
            transactions[slotIndex] = validated.transactions[slotIndex] as Transaction?
        }
        events.clear()
        for (entry in validated.events) {
            events.addLast(entry as Event)
        }
        eventCount = validated.eventCount
    }
}
