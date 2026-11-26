package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

internal class LcdcIrq118(
    private val context: ComponentContext,
    bank: LcdcFunctional,
    val deadline: Long,
    val syncStart: Long = 0,
    val resumeTick: Long = 0,
    val followups: List<Long> = emptyList(),
    val continuous: Boolean = false,
    val periodTicks: Long = PERIOD,
) : TickAwareMmioDevice, MmioDevice by bank {
    companion object {
        const val SETTING = "irq118Tick"
        const val LINE = 118
        const val SINK = "soc-gic.lines"
        const val SYNC = "frameSyncStartTick"
        const val RESUME = "frameSyncResumeTick"
        const val FOLLOWUPS = "frameSyncFollowupTicks"
        const val PERIOD_SETTING = "frameSyncPeriodTicks"
        /** Legacy default HYPOTHESIS frame-sync period (`27.0 Hz` at `54 MHz`). */
        const val PERIOD = 2000000L
        /**
         * Exact `59.94005994... Hz` NTSC frame-sync period at the `DAT`-proven `54,000,000 Hz`
         * hardware timer base (`54,000,000 * 1001 / 60,000 = 900,900` exact zero remainder,
         * matching `soc-timer` ch1 reload `900,899 + 1`).
         */
        const val NTSC_59_94_HZ_PERIOD = 900_900L
        /** Exact `29.97002997... Hz` half-rate NTSC frame-sync period (`2 * 900,900`). */
        const val NTSC_29_97_HZ_PERIOD = 1_801_800L
        const val LIMIT = 32
        const val FOLLOWUP_LIMIT = 256
        fun parseTicks(text: String, syncStart: Long, resumeTick: Long = 0L, periodTicks: Long = PERIOD): List<Long> {
            require(periodTicks in 1L..0xffffffffL) { "IRQ118 frame-sync period must be positive u32" }
            if (text.isEmpty()) return emptyList()
            require(syncStart > 0 && text.length <= FOLLOWUP_LIMIT * 11 - 1)
            val floor = (if (resumeTick > 0) resumeTick else syncStart) + LIMIT * periodTicks
            val ticks = text.split(',').map { token ->
                require(token.length in 1..10 && token.first() in '1'..'9' && token.all { it in '0'..'9' })
                token.toLong().also { require(it <= 0xffffffffL) }
            }
            require(ticks.size <= FOLLOWUP_LIMIT && ticks.first() >= floor && ticks.zipWithNext().all { (before, after) -> before < after }) {
                "IRQ118 frame-sync follow-up ticks must increase strictly after the periodic schedule"
            }
            return ticks
        }
    }
    init {
        require(periodTicks in 1L..0xffffffffL) { "IRQ118 frame-sync period must be positive u32" }
        require(syncStart == 0L || syncStart > deadline)
        require(resumeTick == 0L || syncStart > 0 && resumeTick >= syncStart + LIMIT * periodTicks)
        if (followups.isNotEmpty()) {
            val floor = (if (resumeTick > 0) resumeTick else syncStart) + LIMIT * periodTicks
            require(syncStart > 0 && followups.size <= FOLLOWUP_LIMIT &&
                followups.all { it in 1..0xffffffffL } &&
                followups.first() >= floor &&
                followups.zipWithNext().all { (before, after) -> before < after })
        }
    }
    private val periodicCount = if (syncStart == 0L) 0 else if (resumeTick > 0) 2 * LIMIT else LIMIT
    private var edges = mutableListOf<Long>()
    private var totalEdges = 0L
    private var nextAutoTick = if (continuous) minOf(deadline, periodTicks) else deadline
    private fun syncDeadline(count: Long): Long? = when {
        syncStart > 0 && count < LIMIT -> syncStart + count * periodTicks
        resumeTick > 0 && count < 2 * LIMIT -> resumeTick + (count - LIMIT) * periodTicks
        count in periodicCount.toLong() until (periodicCount + followups.size).toLong() -> followups[(count - periodicCount).toInt()]
        continuous && syncStart > 0 -> {
            val anchorCount = (periodicCount + followups.size).toLong()
            val anchorTick = when {
                followups.isNotEmpty() -> followups.last() + periodTicks
                resumeTick > 0 -> resumeTick + LIMIT * periodTicks
                else -> syncStart + LIMIT * periodTicks
            }
            anchorTick + (count - anchorCount) * periodTicks
        }
        else -> null
    }

    private val target by lazy {
        requireNotNull(context.ports.find(SINK, InterruptSink::class.java) as? EdgeInterruptSink) {
            "LCD IRQ118 requires an edge-capable GIC sink"
        }
    }
    var firedAt: Long? = null
        private set

    fun bind() { target }
    override fun nextDeadline() = if (continuous) nextAutoTick else if (firedAt == null) deadline else syncDeadline(totalEdges)
    override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
    override fun readMayMutateSchedule(): Boolean = false

    override fun beforeInstruction(tick: Long) {
        val due = nextDeadline() ?: return
        if (tick < due) return
        if (continuous) {
            val line = target.edgeState(LINE)
            val enabled = (target as? EnabledEdgeInterruptSink)?.lineEnabled(LINE) != false
            val ready = line.edge && !line.high && (firedAt != null || (!line.pending && enabled))
            if (!ready) {
                nextAutoTick = tick + periodTicks
                return
            }
            target.setLine(LINE, true)
            target.setLine(LINE, false)
            if (firedAt == null) {
                firedAt = tick
                nextAutoTick = tick + periodTicks
            } else {
                totalEdges++
                if (edges.size >= 64) edges.removeFirst()
                edges.add(tick)
                val anchor = if (tick >= due && tick - due < periodTicks) due else tick
                nextAutoTick = anchor + periodTicks
            }
            return
        }
        if (firedAt != null) {
            if (totalEdges < periodicCount) {
                require(tick < due + periodTicks) { "Missed LCD frame clock interval" }
            } else {
                require(syncDeadline(totalEdges + 1)?.let { tick < it } != false) { "Missed LCD frame-sync follow-up interval" }
            }
        }
        val line = target.edgeState(LINE)
        require(line.edge && !line.high) { "LCD IRQ118 requires a low edge-triggered line" }
        if (firedAt == null) require(!line.pending) { "LCD startup IRQ118 already pending" }
        target.setLine(LINE, true)
        target.setLine(LINE, false)
        if (firedAt == null) firedAt = tick else {
            totalEdges++
            edges.add(tick)
        }
    }

    fun state() = mapOf("confidence" to "HYPOTHESIS", "instance" to 1, "line" to LINE, "sink" to SINK,
        "capacity" to 1, "deadline" to deadline, "firedAt" to firedAt)

    fun parseFiredAt(raw: Any?): Long? {
        val saved = VglibSnapshotCodec.requireMap(raw, "irq118")
        require(saved.keys == setOf("deadline", "firedAt") && saved["deadline"] is Number &&
            VglibSnapshotCodec.requireLong(saved["deadline"], "irq118.deadline") == deadline) { "LCD IRQ118 deadline differs" }
        return saved["firedAt"]?.let {
            VglibSnapshotCodec.requireLong(it, "irq118.firedAt").also { tick ->
                val minTick = if (continuous) 1L else deadline
                require(tick in minTick..context.clock.ticks()) { "Invalid LCD IRQ118 firing tick" }
            }
        }
    }

    fun restore(tick: Long?) { firedAt = tick }

    fun syncState() = buildMap<String, Any> {
        put("firstTick", syncStart)
        put("periodTicks", periodTicks)
        put("capacity", periodicCount + followups.size)
        put("confidence", "HYPOTHESIS")
        put("edgeTicks", edges.take(periodicCount))
        if (resumeTick > 0) put("resumeTick", resumeTick)
        if (followups.isNotEmpty()) put("followupFiredAt", edges.drop(periodicCount))
    }

    fun parseSync(raw: Any?, bootEdge: Long?, savedFollowups: String? = null): List<Long> {
        if (raw == null) {
            require(savedFollowups == null && (syncStart == 0L || syncStart > context.clock.ticks()))
            return emptyList()
        }
        val saved = VglibSnapshotCodec.requireMap(raw, "frameSync")
        val extended = "resumeTick" in saved
        if (extended) require(resumeTick > 0 && VglibSnapshotCodec.requireLong(saved["resumeTick"], "resumeTick") == resumeTick)
        val savedPrefix = savedFollowups?.let {
            require(it.isNotEmpty() && extended == (resumeTick > 0))
            parseTicks(it, syncStart, resumeTick, periodTicks)
        }.orEmpty()
        require(followups.take(savedPrefix.size) == savedPrefix) { "Saved LCD frame-sync follow-up schedule is not a prefix" }
        val expectedKeys = buildSet {
            add("firstTick")
            add("periodTicks")
            add("capacity")
            add("confidence")
            add("edgeTicks")
            if (extended) add("resumeTick")
            if (savedPrefix.isNotEmpty()) add("followupFiredAt")
        }
        val savedPeriodicCapacity = if (extended) 2 * LIMIT else LIMIT
        val savedCapacity = savedPeriodicCapacity + savedPrefix.size
        require(syncStart > 0 && saved.keys == expectedKeys && saved["confidence"] == "HYPOTHESIS")
        require(VglibSnapshotCodec.requireLong(saved["firstTick"], "firstTick") == syncStart &&
            VglibSnapshotCodec.requireLong(saved["periodTicks"], "periodTicks") == periodTicks &&
            VglibSnapshotCodec.requireInt(saved["capacity"], "capacity", savedCapacity..savedCapacity) == savedCapacity)
        val now = context.clock.ticks()
        val history = saved["edgeTicks"]
        require(history is List<*> && history.size <= savedPeriodicCapacity)
        val periodicParsed = history.mapIndexed { index, value ->
            val due = requireNotNull(syncDeadline(index.toLong()))
            VglibSnapshotCodec.requireLong(value, "edge tick").also {
                if (continuous) {
                    require(bootEdge != null && it > bootEdge && it <= now)
                } else {
                    require(bootEdge != null && it > bootEdge && it in due until due + periodTicks && it <= now)
                }
            }
        }
        val followupRaw = if (savedPrefix.isEmpty()) emptyList<Any?>() else saved["followupFiredAt"] as? List<*>
            ?: throw IllegalArgumentException("Missing LCD frame-sync follow-up history")
        require(followupRaw.size <= savedPrefix.size && (followupRaw.isEmpty() || periodicParsed.size == savedPeriodicCapacity))
        val followupParsed = followupRaw.mapIndexed { index, value ->
            val due = followups[index]
            val next = followups.getOrNull(index + 1)
            VglibSnapshotCodec.requireLong(value, "followup edge tick").also {
                if (continuous) {
                    require(bootEdge != null && it > bootEdge && it <= now)
                } else {
                    require(bootEdge != null && it > bootEdge && it in due..now && (next == null || it < next))
                }
            }
        }
        val parsed = periodicParsed + followupParsed
        if (!continuous) {
            require(syncDeadline(parsed.size.toLong())?.let { it > now } != false) {
                "Pending LCD frame-sync deadline must follow restore tick"
            }
        }
        return parsed
    }

    fun restoreSync(history: List<Long>) {
        edges = history.toMutableList()
        totalEdges = history.size.toLong()
        if (continuous) {
            val now = context.clock.ticks()
            nextAutoTick = if (firedAt == null) {
                if (now == 0L) minOf(deadline, periodTicks) else now + periodTicks
            } else {
                val scheduled = syncDeadline(totalEdges)
                if (scheduled != null && scheduled > now) scheduled else now + periodTicks
            }
        }
    }
}
