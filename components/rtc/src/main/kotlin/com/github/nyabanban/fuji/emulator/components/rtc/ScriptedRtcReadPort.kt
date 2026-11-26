package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit finite HYP inputs; these are not reset values or readback of the staged write latches. */
internal class ScriptedRtcReadPort(private val bank: RtcBank, inputs: Map<Int, IntArray>) : DescribedPowerRegisterPort {
    private val samples: Array<IntArray>
    private val consumed = IntArray(2)
    private val commitSelectors = IntArray(128)
    private val commitIndices = IntArray(128)
    private val commitValues = IntArray(128)
    private val prepareTicks = LongArray(128)
    private val commitTicks = LongArray(128)
    private var commitCount = 0
    private var cancelCount = 0L
    private var pending: Reservation? = null
    private var restoredPending: Reservation? = null
    private var activeLiveCheck: (() -> Unit)? = null
    private var closed = false
    private var completedReads = 0L
    private var lastCommittedReadTick: Long? = null

    init {
        require(inputs.keys == setOf(0x1d, 0x1e)) { "Both explicit RTC stage-read sequences are required" }
        samples = Array(2) { index -> inputs.getValue(0x1d + index).also { values ->
            require(values.size in 1..64 && values.all { it in 0..65535 }) { "Finite raw16 RTC input required" }
        }.copyOf() }
    }

    override fun registerContracts(): List<PowerRegisterContract> = rtcRegisterContracts(true)

    override fun connect(owner: String): PowerRegisterConnection {
        check(!closed) { "RTC scripted endpoint closed" }
        val delegate = bank.connect(owner)
        return object : PreparedPowerRegisterConnection {
            private var connectionClosed = false
            private fun live() {
                check(!connectionClosed && !closed) { "Closed RTC scripted connection" }
                delegate.registers() // Also checks the underlying exclusive lease; never reads a register.
            }
            init {
                activeLiveCheck = ::live
            }
            override fun registers(): IntArray { live(); return delegate.registers() }
            override fun read(register: Int, tick: Long): Int {
                live()
                check(register !in 0x1d..0x1e) { "RTC scripted stage read requires a prepared transport transaction" }
                return delegate.read(register, tick)
            }
            override fun prepareRead(register: Int, tick: Long): PowerRegisterReadLease {
                live()
                val reclaim = restoredPending
                if (reclaim != null && pending === reclaim && reclaim.selector == register && reclaim.sampledAt == tick) {
                    restoredPending = null
                    return reclaim
                }
                check(pending == null) { "RTC prepared read already pending" }
                check(cancelCount < Long.MAX_VALUE) { "RTC cancellation counter capacity exhausted" }
                bank.checkReadTime(tick)
                require(lastCommittedReadTick?.let { tick >= it } != false) { "RTC read precedes last completed read" }
                val script = if (register in 0x1d..0x1e) register - 0x1d else -1
                val index = if (script >= 0) consumed[script] else -1
                val value = if (script >= 0) {
                    require(index < samples[script].size) { "RTC scripted input exhausted for selector$register" }
                    samples[script][index]
                } else delegate.read(register, tick)
                val reservation = Reservation(register, script, index, value, tick, ::live)
                pending = reservation
                return reservation
            }
            override fun checkWrite(register: Int, value: Int, tick: Long) {
                live(); check(pending == null) { "RTC write while prepared read pending" }; delegate.checkWrite(register, value, tick)
            }
            override fun write(register: Int, value: Int, tick: Long) {
                checkWrite(register, value, tick); delegate.write(register, value, tick)
            }
            override fun close() {
                if (!connectionClosed) {
                    activeLiveCheck = null
                    pending?.cancel(); connectionClosed = true; delegate.close()
                }
            }
        }
    }

    private inner class Reservation(
        val selector: Int, val script: Int, val sampleIndex: Int,
        override val value: Int, val sampledAt: Long, private val ownerLive: () -> Unit,
    ) : PowerRegisterReadLease {
        private var settled = false
        override fun checkCommit(tick: Long) {
            ownerLive(); check(!settled && pending === this) { "Inactive RTC prepared read" }
            bank.checkReadTime(tick)
            require(tick >= sampledAt && lastCommittedReadTick?.let { tick >= it } != false) { "RTC read commit moved backwards" }
            require(script < 0 || consumed[script] == sampleIndex) { "RTC sample cursor changed before commit" }
            require(script < 0 || commitCount < commitSelectors.size) { "RTC complete input journal capacity exhausted" }
            Math.addExact(completedReads, 1L)
        }
        override fun commit(tick: Long) {
            checkCommit(tick)
            val nextCount = Math.addExact(completedReads, 1L)
            if (script >= 0) {
                commitSelectors[commitCount] = selector; commitIndices[commitCount] = sampleIndex; commitValues[commitCount] = value
                prepareTicks[commitCount] = sampledAt; commitTicks[commitCount] = tick
                commitCount++; consumed[script]++
            }
            completedReads = nextCount; lastCommittedReadTick = tick; settled = true
            if (restoredPending === this) restoredPending = null
            pending = null
        }
        override fun cancel() {
            if (!settled) {
                settled = true
                if (restoredPending === this) restoredPending = null
                if (pending === this) { pending = null; cancelCount++ }
            }
        }
        fun detach() {
            settled = true
            if (restoredPending === this) restoredPending = null
            if (pending === this) pending = null
        }
    }

    fun close() { if (!closed) { activeLiveCheck = null; pending?.cancel(); closed = true; bank.close() } }
    val isClosed: Boolean get() = closed

    internal data class CommitEntry(
        val selector: Int,
        val inputIndex: Int,
        val sampleValue: Int,
        val prepareTick: Long,
        val commitTick: Long,
    )

    internal data class PendingEntry(
        val selector: Int,
        val script: Int,
        val sampleIndex: Int,
        val sampleValue: Int,
        val sampledAt: Long,
    )

    internal class ParsedSnapshot(
        val consumedLow: Int,
        val consumedHigh: Int,
        val completedReads: Long,
        val lastCommittedReadTick: Long?,
        val cancelCount: Long,
        val commits: List<CommitEntry>,
        val pendingEntry: PendingEntry?,
    )

    fun saveSnapshot(): Map<String, Any?> {
        check(!closed) { "RTC scripted endpoint closed" }
        return linkedMapOf(
            "consumed" to linkedMapOf("29" to consumed[0], "30" to consumed[1]),
            "completedReads" to completedReads,
            "lastCommittedReadTick" to lastCommittedReadTick,
            "cancelCount" to cancelCount,
            "commits" to (0 until commitCount).map { index ->
                linkedMapOf(
                    "selector" to commitSelectors[index],
                    "inputIndex" to commitIndices[index],
                    "value" to commitValues[index],
                    "prepareTick" to prepareTicks[index],
                    "commitTick" to commitTicks[index],
                )
            },
            "pending" to pending?.let { reservation ->
                linkedMapOf(
                    "selector" to reservation.selector,
                    "sampleIndex" to reservation.sampleIndex.takeIf { idx -> idx >= 0 },
                    "value" to reservation.value,
                    "sampledAt" to reservation.sampledAt,
                )
            },
        )
    }

    fun parseSnapshot(raw: Any?, parsedBank: RtcBank.ParsedSnapshot): ParsedSnapshot {
        check(!closed) { "RTC scripted endpoint closed" }
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing scriptedStageReads snapshot")
        val expectedKeys = setOf("consumed", "completedReads", "lastCommittedReadTick", "cancelCount", "commits", "pending")
        require(map.keys == expectedKeys) { "Invalid scriptedStageReads snapshot keys" }
        val rawConsumed = map["consumed"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scriptedStageReads consumed map")
        require(rawConsumed.keys.map { key -> key?.toString() }.toSet() == setOf("29", "30") && rawConsumed.size == 2) {
            "Invalid scriptedStageReads consumed selector keys"
        }
        val consumedByKey = rawConsumed.entries.associate { entry -> entry.key.toString() to entry.value }
        val nextConsumedLow = snapshotInt(consumedByKey["29"], "consumed[29]", 0, samples[0].size)
        val nextConsumedHigh = snapshotInt(consumedByKey["30"], "consumed[30]", 0, samples[1].size)
        val nextCompletedReads = snapshotLong(map["completedReads"], "completedReads", 0L, Long.MAX_VALUE)
        val nextLastCommittedTick = map["lastCommittedReadTick"]?.let { tick ->
            snapshotLong(tick, "lastCommittedReadTick", 0L, Long.MAX_VALUE)
        }
        require((nextCompletedReads == 0L) == (nextLastCommittedTick == null)) {
            "RTC completedReads and lastCommittedReadTick disagree"
        }
        val nextCancelCount = snapshotLong(map["cancelCount"], "cancelCount", 0L, Long.MAX_VALUE)
        val rawCommits = map["commits"] as? List<*> ?: throw IllegalArgumentException("Missing scriptedStageReads commits list")
        val expectedCommitCount = nextConsumedLow + nextConsumedHigh
        require(rawCommits.size == expectedCommitCount && expectedCommitCount <= commitSelectors.size) {
            "RTC commits list size disagrees with consumed cursors"
        }
        require(nextCompletedReads >= expectedCommitCount.toLong()) { "RTC completedReads smaller than scripted commit count" }
        val seenByScript = IntArray(2)
        var previousCommitTick = 0L
        val parsedCommits = rawCommits.mapIndexed { index, item ->
            val row = item as? Map<*, *> ?: throw IllegalArgumentException("Invalid RTC commit entry at $index")
            require(row.keys == setOf("selector", "inputIndex", "value", "prepareTick", "commitTick")) {
                "Invalid RTC commit keys at $index"
            }
            val selector = snapshotInt(row["selector"], "commits[$index].selector", 0x1d, 0x1e)
            val scriptIndex = selector - 0x1d
            val inputIndex = snapshotInt(row["inputIndex"], "commits[$index].inputIndex", 0, samples[scriptIndex].size - 1)
            require(inputIndex == seenByScript[scriptIndex]) { "Non-sequential RTC commit inputIndex at $index" }
            seenByScript[scriptIndex]++
            val sampleValue = snapshotInt(row["value"], "commits[$index].value", 0, 65535)
            require(sampleValue == samples[scriptIndex][inputIndex]) { "RTC commit value mismatches configured input at $index" }
            val prepareTick = snapshotLong(row["prepareTick"], "commits[$index].prepareTick", 0L, Long.MAX_VALUE)
            val commitTick = snapshotLong(row["commitTick"], "commits[$index].commitTick", prepareTick, Long.MAX_VALUE)
            require(commitTick >= previousCommitTick) { "RTC commitTick moved backwards at $index" }
            previousCommitTick = commitTick
            CommitEntry(selector, inputIndex, sampleValue, prepareTick, commitTick)
        }
        if (parsedCommits.isNotEmpty()) {
            require(checkNotNull(nextLastCommittedTick) >= parsedCommits.last().commitTick) {
                "RTC lastCommittedReadTick precedes final scripted commit"
            }
        }
        val parsedPending = map["pending"]?.let { rawPending ->
            val pendingMap = rawPending as? Map<*, *> ?: throw IllegalArgumentException("Invalid scriptedStageReads pending map")
            require(pendingMap.keys == setOf("selector", "sampleIndex", "value", "sampledAt")) {
                "Invalid scriptedStageReads pending keys"
            }
            val selector = snapshotInt(pendingMap["selector"], "pending.selector", 0, 127)
            require(selector in setOf(0x1d, 0x1e, 0x23, 0x24, 0x27)) { "Invalid pending RTC selector: $selector" }
            val sampledAt = snapshotLong(pendingMap["sampledAt"], "pending.sampledAt", 0L, Long.MAX_VALUE)
            require(sampledAt >= parsedBank.lastWriteTick) { "Pending RTC read precedes last bank write" }
            require(nextLastCommittedTick == null || sampledAt >= nextLastCommittedTick) {
                "Pending RTC read precedes lastCommittedReadTick"
            }
            val sampleValue = snapshotInt(pendingMap["value"], "pending.value", 0, 65535)
            if (selector in 0x1d..0x1e) {
                val scriptIndex = selector - 0x1d
                val expectedIndex = if (scriptIndex == 0) nextConsumedLow else nextConsumedHigh
                require(expectedIndex < samples[scriptIndex].size) { "Pending RTC scripted read on exhausted selector $selector" }
                val sampleIndex = snapshotInt(pendingMap["sampleIndex"], "pending.sampleIndex", 0, samples[scriptIndex].size - 1)
                require(sampleIndex == expectedIndex) { "Pending RTC sampleIndex disagrees with consumed cursor" }
                require(sampleValue == samples[scriptIndex][sampleIndex]) { "Pending RTC sample value mismatches declared input" }
                PendingEntry(selector, scriptIndex, sampleIndex, sampleValue, sampledAt)
            } else {
                require(pendingMap["sampleIndex"] == null) { "Non-scripted pending RTC read must have null sampleIndex" }
                val expectedRead = bank.computeReadFromParsed(selector, sampledAt, parsedBank)
                require(sampleValue == expectedRead) { "Pending non-scripted RTC read value mismatches bank state" }
                PendingEntry(selector, -1, -1, sampleValue, sampledAt)
            }
        }
        return ParsedSnapshot(
            consumedLow = nextConsumedLow,
            consumedHigh = nextConsumedHigh,
            completedReads = nextCompletedReads,
            lastCommittedReadTick = nextLastCommittedTick,
            cancelCount = nextCancelCount,
            commits = parsedCommits,
            pendingEntry = parsedPending,
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        check(!closed) { "RTC scripted endpoint closed" }
        pending?.detach()
        restoredPending = null
        consumed[0] = parsed.consumedLow
        consumed[1] = parsed.consumedHigh
        completedReads = parsed.completedReads
        lastCommittedReadTick = parsed.lastCommittedReadTick
        cancelCount = parsed.cancelCount
        commitCount = parsed.commits.size
        commitSelectors.fill(0)
        commitIndices.fill(0)
        commitValues.fill(0)
        prepareTicks.fill(0L)
        commitTicks.fill(0L)
        parsed.commits.forEachIndexed { index, entry ->
            commitSelectors[index] = entry.selector
            commitIndices[index] = entry.inputIndex
            commitValues[index] = entry.sampleValue
            prepareTicks[index] = entry.prepareTick
            commitTicks[index] = entry.commitTick
        }
        parsed.pendingEntry?.let { entry ->
            val reservation = Reservation(
                selector = entry.selector,
                script = entry.script,
                sampleIndex = entry.sampleIndex,
                value = entry.sampleValue,
                sampledAt = entry.sampledAt,
                ownerLive = {
                    val checkLive = activeLiveCheck ?: error("Closed RTC scripted connection")
                    checkLive()
                },
            )
            restoredPending = reservation
            pending = reservation
        }
    }

    fun reset() {
        check(!closed) { "RTC scripted endpoint closed" }
        pending?.detach()
        restoredPending = null
        consumed.fill(0)
        commitCount = 0
        commitSelectors.fill(0)
        commitIndices.fill(0)
        commitValues.fill(0)
        prepareTicks.fill(0L)
        commitTicks.fill(0L)
        cancelCount = 0L
        completedReads = 0L
        lastCommittedReadTick = null
    }

    private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
        val number = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
        require(number in min..max) { "Out-of-range integer for $field" }
        return number
    }

    private fun snapshotInt(value: Any?, field: String, min: Int, max: Int): Int =
        snapshotLong(value, field, min.toLong(), max.toLong()).toInt()

    fun state(): Map<String, Any?> = mapOf(
        "confidence" to "HYPOTHESIS", "strategy" to "scripted-read", "exhaustion" to "refuse",
        "commitBoundary" to "complete checksum-qualified four-byte read frame",
        "completedReads" to completedReads, "lastCommittedReadTick" to lastCommittedReadTick,
        "commitCount" to commitCount, "cancelCount" to cancelCount,
        "cancelLog" to mapOf("scope" to "Summary of all prepared read leases; not a complete cancellation sequence", "retained" to 0, "notRetained" to cancelCount),
        "commitCapacity" to commitSelectors.size, "commitsTruncated" to false,
        "commits" to (0 until commitCount).map { index -> mapOf(
            "sequence" to index + 1, "selector" to commitSelectors[index], "inputIndex" to commitIndices[index],
            "value" to commitValues[index], "prepareTick" to prepareTicks[index], "commitTick" to commitTicks[index], "confidence" to "HYPOTHESIS",
        ) },
        "selectors" to (0..1).associate { index -> (0x1d + index).toString() to mapOf(
            "registerId" to if (index == 0) "reg.rtc.stage-low" else "reg.rtc.stage-high",
            "inputs" to samples[index].toList(), "nextIndex" to consumed[index], "remaining" to samples[index].size - consumed[index],
        ) },
        "pending" to pending?.let { mapOf("selector" to it.selector, "sampleIndex" to it.sampleIndex.takeIf { i -> i >= 0 },
            "value" to it.value, "sampledAt" to it.sampledAt) },
        "basis" to "Finite experiment values, not staged latch reset/readback, counter aliases, physical RTC or persistence",
    )
}
