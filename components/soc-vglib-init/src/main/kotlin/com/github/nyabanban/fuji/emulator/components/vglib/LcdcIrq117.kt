package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

internal class LcdcIrq117(
    private val context: ComponentContext,
    private val bank: MmioDevice,
    val deadline: Long,
    val followups: List<Long>,
    val continuous: Boolean = false,
    private val periodTicks: Long = LcdcIrq118.PERIOD,
) : TickAwareMmioDevice, MmioDevice by bank {
    companion object {
        const val SETTING = "irq117Tick"
        const val PERIOD_SETTING = "irq117PeriodTicks"
        const val FOLLOWUPS = "irq117FollowupTicks"
        const val LIMIT = 32
        fun parseTicks(text: String, first: Long): List<Long> {
            if (text.isEmpty()) return emptyList()
            require(first > 0 && text.length <= LIMIT * 11 - 1)
            val ticks = text.split(',').map { token ->
                require(token.length in 1..10 && token.first() in '1'..'9' && token.all { it in '0'..'9' })
                token.toLong().also { require(it <= 0xffffffffL) }
            }
            require(ticks.size <= LIMIT && ticks.first() > first && ticks.zipWithNext().all { (before, after) -> before < after }) {
                "IRQ117 follow-up ticks must increase strictly after the first tick"
            }
            return ticks
        }
        const val LINE = 117
        const val SINK = "soc-gic.lines"
    }

    init {
        require(periodTicks in 1L..0xffffffffL) { "IRQ117 period must be positive u32" }
    }

    private val target by lazy {
        requireNotNull(context.ports.find(SINK, InterruptSink::class.java) as? EnabledEdgeInterruptSink) {
            "LCD IRQ117 requires GIC edge and enable inspection"
        }
    }
    private val deadlines = listOf(deadline) + followups
    private val history = mutableListOf<Long>()
    private var nextAutoTick: Long? = if (continuous) minOf(deadline, periodTicks) else deadline

    fun bind() { target }

    override fun nextDeadline(): Long? {
        val other = (bank as? ScheduledMmioDevice)?.nextDeadline()
        val phase = if (continuous) nextAutoTick else deadlines.getOrNull(history.size)
        if (phase == null) return other
        return minOf(phase, other ?: phase)
    }
    override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
    override fun readMayMutateSchedule(): Boolean = bank.readMayMutateSchedule()

    override fun beforeInstruction(tick: Long) {
        if (continuous) {
            val dueTick = nextAutoTick
            val readyToFire = if (dueTick != null && tick >= dueTick) {
                val line = target.edgeState(LINE)
                val ready = target.lineEnabled(LINE) && line.edge && !line.high && !line.pending
                if (!ready) {
                    nextAutoTick = tick + periodTicks
                    false
                } else {
                    true
                }
            } else {
                false
            }
            bank.beforeInstruction(tick)
            if (readyToFire) {
                target.setLine(LINE, true)
                target.setLine(LINE, false)
                if (history.size >= 64) history.removeAt(1)
                history.add(tick)
                nextAutoTick = deadlines.getOrNull(history.size)?.let { maxOf(it, tick + periodTicks) }
                    ?: (tick + periodTicks)
            }
            return
        }
        val due = deadlines.getOrNull(history.size)?.let { tick >= it } == true
        if (due) {
            require(deadlines.getOrNull(history.size + 1)?.let { tick < it } != false) { "Missed IRQ117 phase interval" }
            val line = target.edgeState(LINE)
            require(target.lineEnabled(LINE) && line.edge && !line.high && !line.pending) {
                "LCD IRQ117 requires an enabled, low, nonpending edge-triggered line"
            }
        }
        bank.beforeInstruction(tick)
        if (due) {
            target.setLine(LINE, true)
            target.setLine(LINE, false)
            history.add(tick)
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf("deadline" to deadline, "firedAt" to history.firstOrNull()) +
        (if (followups.isEmpty()) emptyMap() else mapOf("followupFiredAt" to history.drop(1)))
    fun state() = snapshot() + mapOf("confidence" to "HYPOTHESIS", "instance" to 1, "group" to 0,
        "line" to LINE, "sink" to SINK, "capacity" to deadlines.size)

    fun parse(raw: Any?, savedTicks: String?, savedDeadline: Long): List<Long> {
        val saved = VglibSnapshotCodec.requireMap(raw, "irq117")
        val prefix = savedTicks?.let { require(it.isNotEmpty()); parseTicks(it, deadline) }.orEmpty()
        require(followups.take(prefix.size) == prefix) { "Saved IRQ117 schedule is not a prefix" }
        val keys = if (prefix.isEmpty()) setOf("deadline", "firedAt") else setOf("deadline", "firedAt", "followupFiredAt")
        require(saved.keys == keys && VglibSnapshotCodec.requireLong(saved["deadline"], "irq117.deadline") == savedDeadline)
        val first = saved["firedAt"]?.let { VglibSnapshotCodec.requireLong(it, "irq117.firedAt") }
        if (savedDeadline != deadline) {
            require(savedDeadline > context.clock.ticks() && deadline > savedDeadline && first == null &&
                savedTicks == null && followups.isEmpty()) { "Only a pending single IRQ117 pulse can be postponed" }
        }
        val extra = if (prefix.isEmpty()) emptyList<Any?>() else saved["followupFiredAt"] as? List<*>
            ?: throw IllegalArgumentException("Missing IRQ117 follow-up history")
        require(extra.size <= prefix.size && (first != null || extra.isEmpty()))
        val fired = listOfNotNull(first) + extra.map { VglibSnapshotCodec.requireLong(it, "irq117.followupFiredAt") }
        val now = context.clock.ticks()
        fired.forEachIndexed { index, tick ->
            if (continuous) {
                require(tick in 1L..now)
            } else {
                require(tick in deadlines[index]..now && deadlines.getOrNull(index + 1)?.let { tick < it } != false)
            }
        }
        if (continuous) {
            require(fired == fired.sorted()) { "Continuous IRQ117 firing ticks must be non-decreasing" }
        } else {
            require(deadlines.getOrNull(fired.size)?.let { it > now } != false) { "Pending IRQ117 deadline must follow restore tick" }
        }
        return fired
    }

    fun restore(fired: List<Long>) {
        history.clear()
        history.addAll(fired)
        if (continuous) {
            val now = context.clock.ticks()
            val scheduled = deadlines.getOrNull(history.size)
            nextAutoTick = when {
                history.isEmpty() -> if (now == 0L) minOf(deadline, periodTicks) else now + periodTicks
                scheduled != null && scheduled > now -> scheduled
                else -> now + periodTicks
            }
        }
    }
}
