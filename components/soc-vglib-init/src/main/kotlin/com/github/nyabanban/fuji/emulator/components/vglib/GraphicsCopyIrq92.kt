package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

internal class GraphicsCopyIrq92(
    private val context: ComponentContext,
    private val first: GraphicsFillIrq92,
    private val fill: GraphicsFillTail,
    val deadline: Long,
    val continuous: Boolean = false,
) : TickAwareMmioDevice, MmioDevice by first {
    companion object {
        const val SETTING = "graphicsCopyIrq92Tick"
        const val BIT30 = 0x40000000L
        private const val CONTROL = 0xc18L
    }

    data class Cursor(val fired: Long? = null, val reads: List<Long> = emptyList(), val cleared: Long? = null)
    private var cursor = Cursor()
    private var armedAt: Long? = null
    val complete get() = cursor.reads.size == 2

    init {
        require(deadline > first.deadline && fill.state()["copyExecution"] != null) { "Copy IRQ92 requires bounded copy execution" }
    }

    override fun nextDeadline() = first.nextDeadline() ?: if (continuous) {
        armedAt?.takeIf { cursor.fired == null }?.let { Math.addExact(it, 1L) }
    } else {
        deadline.takeIf { cursor.fired == null }
    }
    override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
    override fun readMayMutateSchedule(): Boolean = first.readMayMutateSchedule()
    override fun beforeInstruction(tick: Long) {
        first.beforeInstruction(tick)
        if (cursor.fired != null) return
        if (continuous) {
            val armed = armedAt ?: return
            if (tick <= armed) return
            first.assertIdleLevel()
            cursor = Cursor(fired = tick)
            return
        }
        if (tick < deadline) return
        val tail = fill.state()
        check(first.state()["cleared"] == true && tail["statusCursor"] == 2) { "First IRQ92 callback incomplete" }
        check((tail["copyReadbacks"] as List<*>).size == 1 && (tail["copySubmissions"] as List<*>).size == 1 &&
            (tail["copyRetainedWords"] as List<*>)[5] == 1L) { "Copy IRQ92 requires one executed C18 submission" }
        first.assertIdleLevel()
        cursor = Cursor(fired = tick)
    }

    override fun read(offset: Long, size: Int): Long {
        if (cursor.fired == null || offset != 52L) return first.read(offset, size)
        require(size == 4)
        val index = cursor.reads.size
        check(index == (if (cursor.cleared == null) 0 else 1)) { "Copy IRQ92 status exhausted or missing C18 clear" }
        cursor = cursor.copy(reads = cursor.reads + context.clock.ticks())
        return if (index == 0) BIT30 else 0L
    }

    override fun write(offset: Long, size: Int, value: Long) {
        val clear = cursor.fired != null && cursor.cleared == null && offset == CONTROL && value == 0L
        if (clear) check(cursor.reads.size == 1) { "C18 cleared before bit30 read" }
        first.write(offset, size, value)
        if (continuous && cursor.fired == null && armedAt == null && offset == CONTROL && value == 1L &&
            first.state()["cleared"] == true && fill.completedCopies == 1) {
            armedAt = context.clock.ticks()
        }
        if (clear) {
            first.lowerLevel()
            cursor = cursor.copy(cleared = context.clock.ticks())
        }
    }

    fun snapshot() = mapOf("deadline" to deadline, "firedAt" to cursor.fired,
        "statusReadTicks" to cursor.reads, "clearedAt" to cursor.cleared)

    fun parse(raw: Any?, ledger: GraphicsFillTail.Ledger?, firstCleared: Boolean, savedDeadline: Long?, firstPostponed: Boolean): Cursor {
        val now = context.clock.ticks()
        if (raw == null) {
            require(now < deadline)
            return Cursor()
        }
        require(raw is Map<*, *> && raw.keys == snapshot().keys && ledger != null)
        fun tick(value: Any?) = VglibSnapshotCodec.requireLong(value, "copy IRQ92 tick")
        require(savedDeadline != null && tick(raw["deadline"]) == savedDeadline)
        val fired = raw["firedAt"]?.let(::tick)
        val cleared = raw["clearedAt"]?.let(::tick)
        val reads = raw["statusReadTicks"]
        require(reads is List<*> && reads.size <= 2)
        val ticks = reads.map(::tick)
        if (savedDeadline != deadline) {
            require(firstPostponed && now < savedDeadline && deadline > savedDeadline && fired == null &&
                ledger.copies.isEmpty() && ledger.copyReadbacks.isEmpty()) { "Copy IRQ92 can only follow a postponed first fill" }
        }
        if (fired == null) {
            require(now < deadline && cleared == null && ticks.isEmpty())
            return Cursor()
        }
        require(fired in deadline..now && firstCleared && ledger.statusCursor == 2 && ledger.copyReadbacks.isNotEmpty())
        val controls = ledger.accesses.filter { it.write && it.offset == CONTROL }
        require(controls.count { it.value == 1L && it.tick < fired } == 1 &&
            controls.lastOrNull { it.tick < fired }?.value == 1L)
        require(ticks.all { it in fired..now } && ticks == ticks.sorted())
        require(cleared == controls.firstOrNull { it.value == 0L && it.tick >= fired }?.tick)
        if (cleared != null) require(ticks.isNotEmpty() && cleared in ticks.first()..now)
        if (ticks.size == 2) require(cleared != null && ticks.last() >= cleared)
        return Cursor(fired, ticks, cleared)
    }

    fun restore(parsed: Cursor) { cursor = parsed }
}
