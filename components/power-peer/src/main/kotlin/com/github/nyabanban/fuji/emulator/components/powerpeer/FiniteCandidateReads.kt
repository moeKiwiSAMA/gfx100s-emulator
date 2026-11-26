package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit finite raw16 hypotheses; no physical owner, ready bits or device behavior is inferred. */
internal class FiniteCandidateReads(raw: Any?, private val repeatLast: Boolean = false) : DescribedProtocolCandidateInputs, AutoCloseable {
    private data class Input(val contract: ProtocolCandidateReadContract, val values: IntArray)
    private val inputs: List<Input>
    private val bySelector = arrayOfNulls<Input>(128)
    private val positions = IntArray(128)
    private var pending: Lease? = null
    private var closed = false
    private var committedReads = 0L

    init {
        require(raw is List<*> && raw.size <= 128) { "candidate_reads must be an explicit list of at most 128 entries" }
        val ids = hashSetOf<String>()
        inputs = raw.map { item ->
            require(item is Map<*,*> && item.keys == setOf("spaceId", "requestId", "selector", "wordBits", "confidence", "values")) { "complete candidate read input required" }
            fun id(key: String): String {
                val value = item[key]
                require(value is String && value.matches(Regex("[a-z][a-zA-Z0-9._-]{0,127}"))) { "candidate input ID must be stable" }
                return value
            }
            val spaceId = id("spaceId")
            val requestId = id("requestId")
            val selector = integer(item["selector"], 127)
            require(integer(item["wordBits"], 16) == 16 && item["confidence"] == "HYPOTHESIS") { "candidate read requires explicit raw16 HYPOTHESIS" }
            val values = item["values"]
            val batteryInput = spaceId == "gfx100s-power-protocol-requests" &&
                requestId == "request.power-peer.selector0x03" && selector == 3
            val limit = if (batteryInput) 320 else 64
            require(values is List<*> && values.size in 1..limit) { "candidate sequence must contain 1..$limit raw16 samples" }
            val contract = ProtocolCandidateReadContract(spaceId, requestId, selector, 16, "read", "scripted-read", "HYPOTHESIS")
            val input = Input(contract, values.map { integer(it, 65535) }.toIntArray())
            require(bySelector[selector] == null && ids.add(spaceId + "/" + requestId)) { "duplicate candidate read selector or ID" }
            bySelector[selector] = input
            input
        }
    }

    private fun integer(value: Any?, max: Int): Int {
        require(value is Byte || value is Short || value is Int || value is Long) { "candidate read requires an exact integer" }
        val number = (value as Number).toLong()
        require(number in 0..max.toLong()) { "candidate read integer out of range" }
        return number.toInt()
    }
    fun contains(selector: Int): Boolean = selector in 0..127 && bySelector[selector] != null
    override fun candidateReadContracts(): List<ProtocolCandidateReadContract> = inputs.map { it.contract }
    fun inputSnapshot(): List<Map<String,Any>> = inputs.map { input ->
        val c = input.contract
        mapOf("spaceId" to c.spaceId, "requestId" to c.requestId, "selector" to c.selector,
            "wordBits" to c.wordBits, "confidence" to c.confidence, "values" to input.values.toList())
    }
    fun prepareRead(selector: Int, tick: Long): PowerRegisterReadLease {
        check(!closed) { "candidate read inputs closed" }
        require(tick >= 0)
        check(pending == null) { "candidate read already prepared" }
        val input = checkNotNull(bySelector[selector]) { "candidate read not configured" }
        val index = positions[selector]
        if (index >= input.values.size && !repeatLast) {
            throw IllegalStateException("power peer finite candidate input exhausted register$selector")
        }
        return Lease(input, index, tick).also { pending = it }
    }
    private inner class Lease(val input: Input, val index: Int, val headerTick: Long) : PowerRegisterReadLease {
        private var active = true
        override val value: Int = input.values[minOf(index, input.values.lastIndex)]
        fun detach() {
            active = false
            if (pending === this) pending = null
        }
        override fun checkCommit(tick: Long) {
            check(!closed && active && pending === this && positions[input.contract.selector] == index) { "candidate read lease is not live" }
            require(tick >= headerTick) { "candidate read commit precedes header" }
        }
        override fun commit(tick: Long) {
            checkCommit(tick)
            if (positions[input.contract.selector] < input.values.size) positions[input.contract.selector]++
            committedReads++
            active = false
            pending = null
        }
        override fun cancel() {
            if (active) {
                active = false
                if (pending === this) pending = null
            }
        }
    }
    override fun close() { pending?.cancel(); closed = true }
    fun hasPending(): Boolean = pending != null
    fun valueAt(selector: Int, sampleIndex: Int): Int {
        val values = checkNotNull(bySelector[selector]).values
        return values[if (repeatLast) minOf(sampleIndex, values.lastIndex) else sampleIndex]
    }
    val extendedBattery: Boolean get() = (bySelector[3]?.values?.size ?: 0) > 96

    internal class ParsedSnapshot(
        val positions: IntArray,
        val committedReads: Long,
        val pendingSelector: Int,
        val pendingIndex: Int,
        val pendingTick: Long,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "committedReads" to committedReads,
        "positions" to inputs.associate { input ->
            val selector = input.contract.selector
            selector.toString() to positions[selector]
        },
        "pending" to pending?.let { lease ->
            linkedMapOf(
                "selector" to lease.input.contract.selector,
                "sampleIndex" to lease.index,
                "value" to lease.value,
                "headerTick" to lease.headerTick,
            )
        },
    ).also { snapshot ->
        if (extendedBattery) snapshot["selector03Prefix"] = bySelector[3]!!.values.take(positions[3])
    }

    fun parseSnapshot(raw: Any?, strictSelectors: Boolean): ParsedSnapshot {
        check(!closed) { "candidate read inputs closed" }
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing candidateReads snapshot")
        val hasPrefix = map.containsKey("selector03Prefix")
        val keys = setOf("committedReads", "positions", "pending")
        require(map.keys == (if (hasPrefix) keys + "selector03Prefix" else keys)) { "Invalid candidateReads snapshot keys" }
        val nextCommitted = snapshotLong(map["committedReads"], "committedReads", 0L, Long.MAX_VALUE)
        val rawPositions = map["positions"] as? Map<*, *> ?: throw IllegalArgumentException("Missing candidateReads positions")
        val configuredSelectors = inputs.map { input -> input.contract.selector }.toSet()
        val seenSelectors = HashSet<Int>()
        val nextPositions = IntArray(128)
        var consumedSum = 0L
        for ((rawKey, rawValue) in rawPositions) {
            val keyText = rawKey as? String ?: rawKey?.toString() ?: throw IllegalArgumentException("Null candidate selector key")
            require(keyText.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Invalid candidate selector key: $keyText" }
            val selector = keyText.toInt()
            require(selector in 0..127 && seenSelectors.add(selector)) { "Duplicate or out-of-range candidate selector: $selector" }
            val liveInput = bySelector[selector] ?: throw IllegalArgumentException("Unknown candidate selector in snapshot: $selector")
            val consumed = snapshotInt(rawValue, "positions[$selector]", 0, liveInput.values.size)
            nextPositions[selector] = consumed
            consumedSum = Math.addExact(consumedSum, consumed.toLong())
        }
        if (strictSelectors) {
            require(seenSelectors == configuredSelectors) { "Candidate read selector set differs from live configuration" }
        }
        require(if (repeatLast) nextCommitted >= consumedSum else nextCommitted == consumedSum) {
            "Candidate committedReads does not match consumed positions sum"
        }
        if (hasPrefix) {
            val prefix = map["selector03Prefix"] as? List<*> ?: throw IllegalArgumentException("Missing selector 03 sample prefix")
            require(extendedBattery && 3 in seenSelectors && prefix.size == nextPositions[3] &&
                prefix.indices.all { index -> integer(prefix[index], 65535) == valueAt(3, index) }) {
                "Saved selector 03 sample prefix differs from selected input"
            }
        }
        val rawPending = map["pending"] ?: return ParsedSnapshot(nextPositions, nextCommitted, -1, -1, -1L)
        val pendingMap = rawPending as? Map<*, *> ?: throw IllegalArgumentException("Invalid candidateReads pending map")
        require(pendingMap.keys == setOf("selector", "sampleIndex", "value", "headerTick")) { "Invalid candidateReads pending keys" }
        val pendingSelector = snapshotInt(pendingMap["selector"], "pending.selector", 0, 127)
        val liveInput = bySelector[pendingSelector] ?: throw IllegalArgumentException("Pending candidate selector not configured: $pendingSelector")
        val maxSampleIndex = if (repeatLast) liveInput.values.size else liveInput.values.size - 1
        val sampleIndex = snapshotInt(pendingMap["sampleIndex"], "pending.sampleIndex", 0, maxSampleIndex)
        require(sampleIndex == nextPositions[pendingSelector]) { "Pending candidate sampleIndex mismatches consumed cursor" }
        val pendingValue = snapshotInt(pendingMap["value"], "pending.value", 0, 65535)
        require(pendingValue == liveInput.values[minOf(sampleIndex, liveInput.values.lastIndex)]) { "Pending candidate value mismatches declared input" }
        val headerTick = snapshotLong(pendingMap["headerTick"], "pending.headerTick", 0L, Long.MAX_VALUE)
        return ParsedSnapshot(nextPositions, nextCommitted, pendingSelector, sampleIndex, headerTick)
    }

    fun applySnapshot(parsed: ParsedSnapshot): PowerRegisterReadLease? {
        check(!closed) { "candidate read inputs closed" }
        pending?.detach()
        parsed.positions.copyInto(positions)
        committedReads = parsed.committedReads
        if (parsed.pendingSelector < 0) return null
        val liveInput = checkNotNull(bySelector[parsed.pendingSelector])
        return Lease(liveInput, parsed.pendingIndex, parsed.pendingTick).also { lease -> pending = lease }
    }

    fun reset() {
        check(!closed) { "candidate read inputs closed" }
        pending?.detach()
        positions.fill(0)
        committedReads = 0L
    }

    private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
        val number = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
        require(number in min..max) { "Out-of-range integer for $field" }
        return number
    }

    private fun snapshotInt(value: Any?, field: String, min: Int, max: Int): Int =
        snapshotLong(value, field, min.toLong(), max.toLong()).toInt()

    fun state(): Map<String,Any?> = mapOf(
        "strategy" to "scripted-read", "confidence" to "HYPOTHESIS", "closed" to closed,
        "selectors" to inputs.associate { input ->
            val c = input.contract
            c.selector.toString() to mapOf("spaceId" to c.spaceId, "requestId" to c.requestId,
                "selector" to c.selector, "wordBits" to c.wordBits, "direction" to c.direction,
                "total" to input.values.size, "consumed" to positions[c.selector],
                "remaining" to input.values.size - positions[c.selector])
        },
        "pending" to pending?.let { lease ->
            val c = lease.input.contract
            mapOf("selector" to c.selector, "spaceId" to c.spaceId, "requestId" to c.requestId,
                "sampleIndex" to lease.index, "value" to lease.value, "headerTick" to lease.headerTick)
        },
        "committedReads" to committedReads,
    )
}
