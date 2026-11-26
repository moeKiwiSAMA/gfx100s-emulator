package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource
import com.github.nyabanban.fuji.emulator.api.MmioDevice

internal class GraphicsFillTail(private val context: ComponentContext, val continuous: Boolean = false) : MmioDevice {
    companion object {
        const val SETTING = "graphicsFillTail"
        const val PROFILE = "graphics-fill-retained-experimental-v2"
        private val offsets = listOf(16L, 20L, 24L, 28L)
        private val copyOffsets = listOf(0xc00L, 0xc04L, 0xc08L, 0xc0cL, 0xc10L, 0xc18L, 0xc20L, 0xc24L, 0xc28L)
        private val commandOffsets = listOf(0x1214L, 0x1298L, 0x10d4L, 0x10dcL, 0x10d0L, 0x10e4L, 0x10ecL, 0x10e0L)
        private const val STATUS = 52L
        private fun integer(raw: Any?, range: LongRange): Long {
            require(raw is Int || raw is Long) { "Graphics fill input requires an integer" }
            return (raw as Number).toLong().also { require(it in range) }
        }
        private fun words(raw: Any?, size: IntRange): List<Long> {
            require(raw is List<*> && raw.size in size)
            return raw.map { integer(it, 0..0xffffffffL) }
        }
    }

    private val input = context.readData("graphics-fill.yaml").also {
        require(it.keys - setOf("execution", "copyInitialWords", "copyExecution", "commandInitialWords", "commandExecution", "commandPairLimit", "commandIrq92Followups", "commandIrq92AutoDelayTicks", GraphicsCommandCompletion.SETTING) == setOf("profile","initialWords","statusReads","accessLimit") && it["profile"] == PROFILE)
    }
    private val skipAuditDigests = continuous || context.relaxedMode
    private val execution = if ("execution" in input) GraphicsFillExecution(input["execution"], continuous, skipAuditDigests) else null
    private val initial = words(input["initialWords"], 4..4)
    private val copyInitial = if ("copyInitialWords" in input) words(input["copyInitialWords"], 9..9) else emptyList()
    private val commandInitial = if ("commandInitialWords" in input) words(input["commandInitialWords"], 8..8) else emptyList()
    private val copyExecution = if ("copyExecution" in input) {
        require(copyInitial.isNotEmpty()) { "Copy execution requires initialized copy registers" }
        GraphicsCopyExecution(input["copyExecution"], continuous, skipAuditDigests)
    } else null
    private val commandExecution = if ("commandExecution" in input) {
        val profile = input["commandExecution"]
        require(profile in listOf(GraphicsCommandExecution.PROFILE, SolidCommandRects.PROFILE, SolidCommandRects.RGBA_PROFILE) && commandInitial.isNotEmpty())
        val selected = requireNotNull(copyExecution).settings()
        GraphicsCommandExecution(GraphicsMode2Workspace.parse(selected["mode2Workspace"]), selected["sourceBase"] as Long,
            profile != GraphicsCommandExecution.PROFILE, profile == SolidCommandRects.RGBA_PROFILE,
            continuous = continuous, skipAuditDigests = skipAuditDigests)
    } else null
    private val statuses = words(input["statusReads"], 0..512)
    private val pairLimit = integer(input.getOrDefault("commandPairLimit",1),1L..4096L).toInt().also {
        require("commandPairLimit" !in input || commandExecution != null)
    }
    private val autoIrqDelayTicks = if ("commandIrq92AutoDelayTicks" in input) {
        integer(input["commandIrq92AutoDelayTicks"], 1L..1000000L).also {
            require(commandExecution != null)
        }
    } else null
    private val staticIrqDeadlines: List<Long> = buildList {
        if (GraphicsCommandCompletion.SETTING in input) {
            require(commandExecution != null)
            add(integer(input[GraphicsCommandCompletion.SETTING],1..0xffffffffL))
        }
        if ("commandIrq92Followups" in input) {
            require(isNotEmpty())
            for (tick in words(input["commandIrq92Followups"],1 until pairLimit)) {
                require(tick > last())
                add(tick)
            }
        }
    }
    private val initialIrqDeadlines: List<Long> = if (continuous) emptyList() else staticIrqDeadlines
    private val commandIrqs = initialIrqDeadlines.map { GraphicsCommandCompletion(it, continuous = continuous) }.toMutableList()
    private var nextIrqIndex = 0
    private val irqIndex: Int
        get() {
            var i = nextIrqIndex
            val size = commandIrqs.size
            while (i < size && commandIrqs[i].complete) {
                i++
            }
            nextIrqIndex = i
            return if (i < size) i else -1
        }
    val commandIrq get() = commandIrqs.getOrNull(irqIndex)
    private val limit = integer(input["accessLimit"], 1L..GraphicsSuccessors.ACCESS_LIMIT).toInt()

    internal data class Access(val offset: Long, val write: Boolean, val value: Long, val tick: Long, val core: Int, val pc: Long) {
        fun fields() = listOf(offset,write,value,tick,core,pc)
    }
    internal class Ledger(initial: List<Long>, copyInitial: List<Long>, commandInitial: List<Long>, initialIrqDeadlines: List<Long> = emptyList()) {
        val words = initial.toLongArray()
        val copyWords = copyInitial.toLongArray()
        val commandWords = commandInitial.toLongArray()
        val commandStarts = mutableListOf<Map<String, Long>>()
        val commandReadbacks = mutableListOf<Map<String,Any>>()
        var commandIrqDeadlines: List<Long> = initialIrqDeadlines
        var commandHistories = emptyList<List<Long>>()
        fun renderTick(index: Int): Long? = if (index in commandReadbacks.indices) commandStarts[2*index+1].getValue("tick") else null
        val copies = mutableListOf<List<Long>>()
        val copyReadbacks = mutableListOf<Map<String, Any>>()
        val accesses = mutableListOf<Access>()
        val submissions = mutableListOf<Map<String,Long>>()
        val readbacks = mutableListOf<Map<String, Any>>()
        var statusCursor = 0
        var activeCanvasWidth = 1024
        var activeCanvasHeight = 768
    }
    private var ledger = Ledger(initial, copyInitial, commandInitial, initialIrqDeadlines)
    val renderedAt get() = ledger.renderTick(irqIndex)
    val completedFills get() = ledger.readbacks.size
    val completedCopies get() = ledger.copyReadbacks.size
    val statusReadsConsumed get() = ledger.statusCursor
    val fillLimit get() = execution?.submissionLimit ?: 0
    val copyLimit get() = copyExecution?.submissionLimit ?: 0

    private fun readValue(state: Ledger, offset: Long): Long {
        if (offset == STATUS) return statuses.getOrNull(state.statusCursor) ?: error("Graphics status input exhausted")
        if (offset in copyOffsets && state.copyWords.isNotEmpty()) return state.copyWords[copyOffsets.indexOf(offset)]
        if (offset in commandOffsets && state.commandWords.isNotEmpty()) return state.commandWords[commandOffsets.indexOf(offset)]
        val index = offsets.indexOf(offset)
        require(index >= 0) { "Undeclared graphics fill register" }
        return state.words[index]
    }

    private fun record(state: Ledger, access: Access, live: Boolean = false) {
        if (continuous) {
            if (state.accesses.size >= limit) state.accesses.removeFirst()
        } else {
            check(state.accesses.size < limit) { "Graphics fill access limit exhausted" }
        }
        require(access.tick >= (state.accesses.lastOrNull()?.tick ?: 0L)) {
            "Non-monotonic graphics fill access tick: ${access.tick} < ${state.accesses.lastOrNull()?.tick}"
        }
        if (access.write) {
            if (access.offset in commandOffsets && state.commandWords.isNotEmpty()) {
                val value = access.value
                require(value in 0..0xffffffffL) { "Graphics command value out of u32 range: 0x${value.toString(16)}" }
                when (access.offset) {
                    0x1214L -> require(value == 0xffffL || value == 0x0100ffffL) {
                        "Unsupported graphics command 0x1214 control word: 0x${value.toString(16)}"
                    }
                    0x1298L -> require(value == 0L) { "Unsupported graphics command 0x1298 word: 0x${value.toString(16)}" }
                    0x10dcL, 0x10ecL -> require(value <= 0x7fffffffL) {
                        "Unsupported graphics command count word at 0x${access.offset.toString(16)}: 0x${value.toString(16)}"
                    }
                    0x10d0L, 0x10e0L -> {
                        require(value == 1L) {
                            "Unsupported graphics command start value at 0x${access.offset.toString(16)}: 0x${value.toString(16)}"
                        }
                        val start = commandOffsets.indexOf(access.offset)
                        val submission = mapOf("offset" to access.offset, "address" to state.commandWords[start-2],
                            "countWord" to state.commandWords[start-1], "control" to state.commandWords[0],
                            "tick" to access.tick, "pcVirtual" to access.pc, "core" to access.core.toLong())
                        if (commandExecution != null) {
                            val pair = state.commandStarts.size / 2
                            require((continuous || pair < pairLimit) && access.offset == (if (state.commandStarts.size % 2 == 0) 0x10d0L else 0x10e0L)) { "Unexpected graphics command pair or order" }
                            if (live && pair > 0) require(commandIrqs.getOrNull(pair-1)?.complete != false) { "Previous command IRQ92 incomplete" }
                            if (access.offset == 0x10e0L && live) {
                                state.commandReadbacks += commandExecution.execute(
                                    requireNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)),
                                    state.commandStarts.last(),
                                    submission,
                                    pair > 0,
                                    canvasWidth = state.activeCanvasWidth,
                                    canvasHeight = state.activeCanvasHeight,
                                )
                                if (continuous) {
                                    if (commandIrqs.size <= pair) {
                                        val delay = autoIrqDelayTicks ?: 1L
                                        val rawDeadline = Math.addExact(access.tick, delay)
                                        val prevDeadline = commandIrqs.lastOrNull()?.deadline ?: 0L
                                        val deadline = maxOf(rawDeadline, Math.addExact(prevDeadline, 1L))
                                        require(deadline > 0L) {
                                            "Continuous command IRQ92 deadline must be positive (pair=$pair, tick=${access.tick}, delay=$delay, deadline=$deadline)"
                                        }
                                        commandIrqs += GraphicsCommandCompletion(deadline, continuous = true)
                                    }
                                } else if (autoIrqDelayTicks != null && pair >= commandIrqs.size) {
                                    val deadline = Math.addExact(access.tick, autoIrqDelayTicks)
                                    val lastDeadline = commandIrqs.lastOrNull()?.deadline
                                    require(deadline in 1..0xffffffffL && (lastDeadline == null || deadline > lastDeadline)) {
                                        "Auto command IRQ92 deadline invalid or non-monotonic (pair=$pair, tick=${access.tick}, delay=$autoIrqDelayTicks, deadline=$deadline, lastDeadline=$lastDeadline)"
                                    }
                                    commandIrqs += GraphicsCommandCompletion(deadline, continuous = false)
                                }
                            }
                        }
                        state.commandStarts += submission
                    }
                }
                state.commandWords[commandOffsets.indexOf(access.offset)] = value
                state.accesses += access
                return
            }
            if (access.offset in copyOffsets && state.copyWords.isNotEmpty()) {
                require(access.value in 0..0xffffffffL)
                val index = copyOffsets.indexOf(access.offset)
                if (access.offset == 0xc18L && access.value == 1L) {
                    val submitted = state.copyWords.copyOf().also { it[index] = access.value }
                    if (copyExecution != null) {
                        if (!continuous) check(state.copies.size < copyExecution.submissionLimit) { "Graphics copy execution limit exhausted" }
                        if (live) state.copyReadbacks += copyExecution.execute(
                            requireNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)), submitted)
                    }
                    state.copies += submitted.toList()
                }
                state.copyWords[index] = access.value
                state.accesses += access
                return
            }
            val index = offsets.indexOf(access.offset)
            require(index >= 0 && access.value in 0..0xffffffffL) { "Invalid graphics fill WRITE4" }
            if (index == 3 && access.value and 1L != 0L) {
                if (execution != null) {
                    if (!continuous) check(state.submissions.size < execution.submissionLimit) { "Graphics fill execution limit exhausted" }
                    if (live) {
                        state.readbacks += execution.execute(
                            requireNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)), state.words, access.value)
                    }
                    if (continuous && state.words[0] == (copyExecution?.settings()?.get("sourceBase") as? Long)) {
                        when (state.words[1] - state.words[0]) {
                            1280L * 960L * 4L -> {
                                state.activeCanvasWidth = 1280
                                state.activeCanvasHeight = 960
                            }
                            1024L * 768L * 4L -> {
                                state.activeCanvasWidth = 1024
                                state.activeCanvasHeight = 768
                            }
                        }
                    }
                }
                state.submissions += mapOf("tick" to access.tick, "pcVirtual" to access.pc, "core" to access.core.toLong(),
                    "start" to state.words[0], "end" to state.words[1], "packed" to state.words[2], "control" to access.value)
            }
            state.words[index] = access.value
        } else {
            require(access.value == readValue(state,access.offset)) { "Saved graphics read differs from retained word or input" }
            if (access.offset == STATUS) state.statusCursor++
        }
        state.accesses += access
    }

    private fun observe(offset: Long, write: Boolean, value: Long) {
        val location = requireNotNull(context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current())
        require(location.core in 0..3 && location.pcVirtual in 0..0xffffffffL) {
            "Invalid execution location core=${location.core} pc=0x${location.pcVirtual.toString(16)}"
        }
        record(ledger, Access(offset,write,value,context.clock.ticks(),location.core,location.pcVirtual), live = true)
    }
    override fun read(offset: Long, size: Int): Long {
        require(size == 4) { "Graphics fill read requires 4-byte access at 0x${offset.toString(16)} (size=$size)" }
        return readValue(ledger,offset).also { observe(offset,false,it) }
    }
    override fun write(offset: Long, size: Int, value: Long) {
        require(size == 4) { "Graphics fill write requires 4-byte access at 0x${offset.toString(16)} (size=$size)" }
        observe(offset,true,value)
    }

    private fun settings() = mapOf("profile" to PROFILE, "initialWords" to initial, "statusReads" to statuses, "accessLimit" to limit) +
        (if (copyInitial.isEmpty()) emptyMap() else mapOf("copyInitialWords" to copyInitial)) +
        (if (commandInitial.isEmpty()) emptyMap() else mapOf("commandInitialWords" to commandInitial))
    fun snapshot() = settings() + ("accesses" to ledger.accesses.map(Access::fields)) +
        (if ("commandPairLimit" in input) mapOf("commandPairLimit" to pairLimit) else emptyMap()) +
        (if (autoIrqDelayTicks != null) mapOf("commandIrq92AutoDelayTicks" to autoIrqDelayTicks) else emptyMap()) +
        (commandIrqs.firstOrNull()?.let { mapOf(GraphicsCommandCompletion.SETTING to it.deadline,"commandIrq92History" to it.snapshot()) } ?: emptyMap()) +
        (if (commandIrqs.size > 1) mapOf("commandIrq92Followups" to commandIrqs.drop(1).map { it.deadline },
            "commandIrq92FollowupHistories" to commandIrqs.drop(1).map { it.snapshot() }) else emptyMap()) +
        (commandExecution?.let { mapOf("commandExecution" to input.getValue("commandExecution"), "commandReadbacks" to ledger.commandReadbacks.toList()) } ?: emptyMap()) +
        (execution?.let { mapOf("execution" to it.settings(), "readbacks" to ledger.readbacks.toList()) } ?: emptyMap()) +
        (copyExecution?.let { mapOf("copyExecution" to it.settings(), "copyReadbacks" to ledger.copyReadbacks.toList()) } ?: emptyMap())
    fun parse(raw: Any?, prefix: Int): Ledger {
        require(raw is Map<*,*>)
        val savedExecution = "execution" in raw
        val savedCopy = "copyInitialWords" in raw
        val savedCopyExecution = "copyExecution" in raw
        val optionalWords = setOf("copyInitialWords", "commandInitialWords", "commandIrq92AutoDelayTicks")
        val savedRenderer = "commandExecution" in raw
        val savedCommandIrq = GraphicsCommandCompletion.SETTING in raw
        val deadlineRange = if (continuous) 1L..Long.MAX_VALUE else 1L..0xffffffffL
        val savedDeadline = if (savedCommandIrq) integer(raw[GraphicsCommandCompletion.SETTING], deadlineRange) else null
        if (savedCommandIrq) {
            require(savedRenderer)
            if (!continuous && (autoIrqDelayTicks == null || staticIrqDeadlines.isNotEmpty())) {
                require(savedDeadline == staticIrqDeadlines.firstOrNull())
            }
        }
        val savedPairLimit = if ("commandPairLimit" in raw) integer(raw["commandPairLimit"],1L..pairLimit.toLong()).toInt() else 1
        val savedFollowups = if ("commandIrq92Followups" in raw) {
            require(savedCommandIrq)
            val rawFollowups = raw["commandIrq92Followups"]
            require(rawFollowups is List<*> && rawFollowups.isNotEmpty() && (continuous || rawFollowups.size < savedPairLimit))
            rawFollowups.map { integer(it, deadlineRange) }.also {
                if (!continuous) {
                    if (autoIrqDelayTicks == null) {
                        require(it == staticIrqDeadlines.drop(1).take(it.size))
                    } else if (staticIrqDeadlines.size > 1) {
                        val staticTail = staticIrqDeadlines.drop(1)
                        val overlap = minOf(it.size, staticTail.size)
                        require(it.take(overlap) == staticTail.take(overlap))
                    }
                }
            }
        } else emptyList()
        if ("commandIrq92AutoDelayTicks" in raw) {
            require(integer(raw["commandIrq92AutoDelayTicks"], 1L..1000000L) == autoIrqDelayTicks)
        }
        val expectedKeys = settings().keys - optionalWords + "accesses" +
            (if ("commandPairLimit" in raw) setOf("commandPairLimit") else emptySet()) +
            (if (savedFollowups.isNotEmpty()) setOf("commandIrq92Followups","commandIrq92FollowupHistories") else emptySet()) +
            (if (savedCommandIrq) setOf(GraphicsCommandCompletion.SETTING,"commandIrq92History") else emptySet()) +
            (if (savedRenderer) setOf("commandExecution","commandReadbacks") else emptySet()) +
            (if (savedExecution) setOf("execution","readbacks") else emptySet()) +
            (if (savedCopyExecution) setOf("copyExecution","copyReadbacks") else emptySet())
        require(raw.keys - optionalWords == expectedKeys)
        val savedCommand = "commandInitialWords" in raw
        if (savedCommand) require(words(raw["commandInitialWords"], 8..8) == commandInitial)
        if (savedCopy) require(words(raw["copyInitialWords"], 9..9) == copyInitial)
        val savedCopyModel = if (savedCopyExecution) {
            require(savedCopy && copyExecution != null)
            GraphicsCopyExecution(raw["copyExecution"], continuous, skipAuditDigests).also(copyExecution::requireCompatiblePrefix)
        } else null
        val savedFillModel = if (savedExecution) {
            requireNotNull(execution)
            GraphicsFillExecution(raw["execution"], continuous, skipAuditDigests).also(execution::requireCompatiblePrefix)
        } else null
        require(raw["profile"] == PROFILE && words(raw["initialWords"],4..4) == initial &&
            words(raw["statusReads"],0..512) == statuses)
        val savedLimit = integer(raw["accessLimit"], 1L..limit.toLong())
        val accesses = raw["accesses"]
        require(accesses is List<*> && accesses.size <= savedLimit && (accesses.isEmpty() || prefix == 36))
        val parsed = Ledger(initial, copyInitial, commandInitial)
        for (row in accesses) {
            require(row is List<*> && row.size == 6 && row[1] is Boolean)
            val offset = integer(row[0], 0..0x1298L)
            require(savedCommand || offset !in commandOffsets)
            require(savedCopy || offset !in copyOffsets)
            record(parsed, Access(offset, row[1] as Boolean, integer(row[2],0..0xffffffffL),
                integer(row[3],0..context.clock.ticks()),integer(row[4],0L..3).toInt(),integer(row[5],0..0xffffffffL)))
        }
        if (savedFillModel != null) {
            val receipts = raw["readbacks"]
            require(receipts is List<*> && receipts.size == parsed.submissions.size && (continuous || receipts.size <= savedFillModel.submissionLimit))
            parsed.submissions.forEachIndexed { index, submission ->
                parsed.readbacks += savedFillModel.restore(receipts[index], submission)
            }
        } else require(execution == null || parsed.submissions.isEmpty()) { "Graphics fill execution requires a pre-submission v2 prefix" }
        if (savedCopyModel != null) {
            val receipts = raw["copyReadbacks"]
            require(receipts is List<*> && receipts.size == parsed.copies.size && (continuous || receipts.size <= savedCopyModel.submissionLimit))
            parsed.copies.forEachIndexed { index, submission ->
                parsed.copyReadbacks += savedCopyModel.restore(receipts[index], submission)
            }
        } else require(copyExecution == null || parsed.copies.isEmpty()) { "Graphics copy execution requires a pre-submission checkpoint" }
        if (savedRenderer) {
            val compatibleCommandExecution = raw["commandExecution"] == input["commandExecution"] ||
                (raw["commandExecution"] == SolidCommandRects.PROFILE && input["commandExecution"] == SolidCommandRects.RGBA_PROFILE)
            require(commandExecution != null && compatibleCommandExecution && savedCommand &&
                savedCopyModel?.settings()?.containsKey("mode2Workspace") == true)
            val proofs = raw["commandReadbacks"]
            require((continuous || parsed.commandStarts.size <= 2*savedPairLimit) && proofs is List<*> && proofs.size == parsed.commandStarts.size / 2)
            proofs.forEachIndexed { index, proof ->
                parsed.commandReadbacks += commandExecution.restore(proof,parsed.commandStarts[2*index],parsed.commandStarts[2*index+1],index > 0)
            }
        } else require(commandExecution == null || parsed.commandStarts.size < 2) { "Command execution requires a pre-render checkpoint" }
        val histories = mutableListOf<Any?>()
        if (savedCommandIrq) histories += raw["commandIrq92History"]
        if (savedFollowups.isNotEmpty()) {
            val rows = raw["commandIrq92FollowupHistories"]
            require(rows is List<*> && rows.size == savedFollowups.size)
            histories.addAll(rows)
        }
        val savedDeadlines = if (savedDeadline != null) listOf(savedDeadline) + savedFollowups else emptyList()
        val effectiveDeadlines = when {
            continuous -> {
                val activeCount = savedDeadlines.indices.lastOrNull { index ->
                    index < parsed.commandReadbacks.size ||
                        (histories.getOrNull(index) as? List<*>)?.isNotEmpty() == true
                }?.plus(1) ?: 0
                savedDeadlines.take(activeCount)
            }
            autoIrqDelayTicks == null -> staticIrqDeadlines
            else -> buildList {
                if (savedDeadlines.isNotEmpty()) {
                    val activeCount = savedDeadlines.indices.lastOrNull { index ->
                        index < staticIrqDeadlines.size ||
                            index < parsed.commandReadbacks.size ||
                            (histories.getOrNull(index) as? List<*>)?.isNotEmpty() == true
                    }?.plus(1) ?: 0
                    addAll(savedDeadlines.take(activeCount))
                    if (staticIrqDeadlines.size > activeCount) {
                        addAll(staticIrqDeadlines.drop(activeCount))
                    }
                } else {
                    addAll(staticIrqDeadlines)
                }
            }
        }
        effectiveDeadlines.zipWithNext().forEach { (a, b) ->
            require(b > a) { "Non-monotonic command IRQ92 deadlines in snapshot: $b <= $a" }
        }
        parsed.commandIrqDeadlines = effectiveDeadlines
        val parsedIrqs = effectiveDeadlines.map { GraphicsCommandCompletion(it, continuous = continuous) }
        parsed.commandHistories = parsedIrqs.mapIndexed { index, irq ->
            irq.parse(histories.getOrNull(index),index < histories.size,parsed.renderTick(index),context.clock.ticks()).also { ticks ->
                parsed.commandStarts.getOrNull(2*index+2)?.let { next ->
                    require(ticks.size == 8 && ticks.last() <= next.getValue("tick"))
                }
            }
        }
        return parsed
    }
    fun restore(parsed: Ledger?) {
        ledger = parsed ?: Ledger(initial, copyInitial, commandInitial, initialIrqDeadlines)
        commandIrqs.clear()
        nextIrqIndex = 0
        val deadlines = parsed?.commandIrqDeadlines ?: initialIrqDeadlines
        deadlines.forEach { commandIrqs += GraphicsCommandCompletion(it, continuous = continuous) }
        commandIrqs.forEachIndexed { index, irq -> irq.restore(ledger.commandHistories.getOrNull(index).orEmpty()) }
    }
    fun state() = snapshot() + mapOf("offsets" to offsets, "statusOffset" to STATUS, "readConfidence" to "HYPOTHESIS",
        "retainedWords" to ledger.words.toList(), "statusCursor" to ledger.statusCursor, "submissions" to ledger.submissions.toList(),
        "transactionCount" to ledger.accesses.size) + (if (copyInitial.isEmpty()) emptyMap() else mapOf(
            "copyOffsets" to copyOffsets, "copyRetainedWords" to ledger.copyWords.toList(), "copySubmissions" to ledger.copies.toList())) +
        (if (commandInitial.isEmpty()) emptyMap() else mapOf("commandOffsets" to commandOffsets,
            "commandRetainedWords" to ledger.commandWords.toList(), "commandStarts" to ledger.commandStarts.toList()))
}
