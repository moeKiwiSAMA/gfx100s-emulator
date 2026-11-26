package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.ScheduledMmioDevice
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice

internal class GraphicsIrq92Sequence(
    private val context: ComponentContext,
    private val tail: GraphicsFillTail,
    private val first: GraphicsFillIrq92,
    private val copy: GraphicsCopyIrq92,
    selection: String,
    val continuous: Boolean = false,
    private val prefixComplete: () -> Boolean,
) : TickAwareMmioDevice, MmioDevice by copy {
    companion object {
        const val SETTING = "graphicsIrq92Sequence"
    }

    enum class Operation(val control: Long, val bit: Long) {
        FILL(28, GraphicsFillIrq92.samples.first()), COPY(0xc18, GraphicsCopyIrq92.BIT30);

        fun submits(offset: Long, value: Long) = offset == control &&
            (if (this == COPY) value == 1L else value and 1L != 0L)
    }

    private val order = GraphicsSuccessors.checkedOrder(selection, tail.fillLimit.toLong(), tail.copyLimit.toLong())
    private fun matches(token: String?, operation: Operation) = token == "either" || token == operation.name.lowercase()

    data class Event(val operation: Operation, val armed: Long, val fired: Long? = null, val reads: List<Long> = emptyList(), val cleared: Long? = null) {
        val complete get() = reads.size == 2
        fun snapshot() = mapOf("armedAt" to armed, "firedAt" to fired, "statusReadTicks" to reads, "clearedAt" to cleared)
    }
    private val events = mutableListOf<Event>()
    private val active get() = events.lastOrNull()?.takeUnless { it.complete }
    fun snapshot() = events.mapIndexed { index, event ->
        if (index >= order.size || order[index] == "either") event.snapshot() + ("operation" to event.operation.name.lowercase()) else event.snapshot()
    }
    fun restore(parsed: List<Event>) { events.clear(); events.addAll(parsed) }

    override fun nextDeadline(): Long? {
        val next = copy.nextDeadline() ?: active?.takeIf { it.fired == null }?.let { Math.addExact(it.armed, 1L) }
        val command = tail.commandIrq?.effectiveDeadline(tail.renderedAt) ?: return next
        return next?.let { minOf(it,command) } ?: command
    }
    override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
    override fun readMayMutateSchedule(): Boolean = copy.readMayMutateSchedule()

    override fun beforeInstruction(tick: Long) {
        copy.beforeInstruction(tick)
        tail.commandIrq?.fire(tick,tail.renderedAt) {
            check(copy.complete && active == null)
            first.assertIdleLevel()
        }
        val event = active ?: return
        if (event.fired != null || tick <= event.armed) return
        first.assertIdleLevel()
        events[events.lastIndex] = event.copy(fired = tick)
    }

    override fun read(offset: Long, size: Int): Long {
        tail.commandIrq?.access(offset,size,false,0,context.clock.ticks(),first::lowerLevel)?.let { return it }
        val event = active ?: return copy.read(offset, size)
        if (offset != 52L) return copy.read(offset, size)
        require(size == 4 && event.fired != null && event.reads.size == (if (event.cleared == null) 0 else 1)) {
            "IRQ92 successor status outside callback order"
        }
        events[events.lastIndex] = event.copy(reads = event.reads + context.clock.ticks())
        return if (event.reads.isEmpty()) event.operation.bit else 0L
    }

    override fun write(offset: Long, size: Int, value: Long) {
        if (!prefixComplete()) return copy.write(offset, size, value)
        tail.commandIrq?.access(offset,size,true,value,context.clock.ticks(),first::lowerLevel)?.let { return }
        val event = active
        val operation = Operation.entries.firstOrNull { it.submits(offset, value) }
        if (event != null && offset == event.operation.control) {
            require(value == 0L && event.reads.size == 1 && event.cleared == null) { "IRQ92 successor control is busy" }
            copy.write(offset, size, value)
            first.lowerLevel()
            events[events.lastIndex] = event.copy(cleared = context.clock.ticks())
            return
        }
        if (operation == null) return copy.write(offset, size, value)
        require(tail.commandIrq?.inCallback != true)
        if (!copy.complete) {
            require((operation == Operation.FILL && tail.completedFills == 0 && tail.completedCopies == 0) ||
                (operation == Operation.COPY && tail.completedFills == 1 && tail.statusReadsConsumed == 2 && tail.completedCopies == 0)) {
                "IRQ92 legacy callback prefix incomplete"
            }
            return copy.write(offset, size, value)
        }
        if (continuous) {
            require(event == null) { "Unexpected or overlapping IRQ92 submission" }
            copy.write(offset, size, value)
            if (events.size >= 64 && events.first().complete) events.removeFirst()
            events += Event(operation, context.clock.ticks())
        } else {
            require(event == null && matches(order.getOrNull(events.size), operation)) { "Unexpected or overlapping IRQ92 submission" }
            copy.write(offset, size, value)
            events += Event(operation, context.clock.ticks())
        }
    }

    fun parse(raw: Any?, savedSelection: String?, ledger: GraphicsFillTail.Ledger, legacy: GraphicsCopyIrq92.Cursor): List<Event> {
        val now = context.clock.ticks()
        val savedOrder = savedSelection?.let(GraphicsSuccessors::expand).orEmpty()
        val rows = if (savedSelection == null) emptyList<Any?>() else raw as? List<*> ?: error("Missing IRQ92 sequence history")
        require(rows.size <= savedOrder.size)
        require(savedOrder.size <= order.size && savedOrder.withIndex().all { (index, token) ->
            token == order[index] || index >= rows.size && order[index] == "either" && token in listOf("fill", "copy")
        }) { "Saved IRQ92 sequence prefix differs" }
        val submissions = ledger.accesses.withIndex().filter { (_, access) ->
            access.write && Operation.entries.any { it.submits(access.offset, access.value) }
        }
        val expected = listOf("fill", "copy") + order
        require(submissions.size <= expected.size && submissions.withIndex().all { (index, row) ->
            Operation.entries.any { matches(expected[index], it) && it.submits(row.value.offset, row.value.value) }
        }) { "IRQ92 submission order differs" }
        require(submissions.size == ledger.readbacks.size + ledger.copyReadbacks.size)
        submissions.getOrNull(1)?.let { copySubmission ->
            require(ledger.accesses.take(copySubmission.index).count { !it.write && it.offset == 52L } == 2) {
                "First copy overlaps the first fill callback"
            }
        }
        val suffix = submissions.drop(2)
        val adopt = savedSelection == null && suffix.size == 1
        require(suffix.size == rows.size || adopt) { "IRQ92 history must account for every successful submission" }
        if (savedSelection == null) require(legacy.reads.size == 2) { "Selecting IRQ92 successors requires completed fill and copy callbacks" }
        var precedingZero = legacy.reads.lastOrNull()
        return suffix.mapIndexed { index, submission ->
            val tick = submission.value.tick
            require(legacy.reads.size == 2 && precedingZero != null && tick >= requireNotNull(precedingZero))
            val operation = Operation.entries.single { it.submits(submission.value.offset, submission.value.value) }
            val event = if (adopt) Event(operation, now) else decode(rows[index], operation, savedOrder[index] == "either")
            require(event.armed in tick..now && (index == 0 || event.armed == tick))
            val clearWrites = ledger.accesses.subList(submission.index + 1, suffix.getOrNull(index + 1)?.index ?: ledger.accesses.size)
                .filter { it.write && it.offset == operation.control }
            val fired = event.fired
            require(fired == null || fired > event.armed && fired <= now)
            require(fired != null || event.armed == now)
            require(event.reads.size <= 2 && event.reads == event.reads.sorted() && event.reads.all { fired != null && it in fired..now })
            require(event.cleared == clearWrites.firstOrNull()?.tick && clearWrites.firstOrNull()?.value in listOf(null, 0L))
            require(clearWrites.drop(1).all { event.complete && it.tick >= event.reads.last() })
            if (event.cleared != null) require(event.reads.isNotEmpty() && event.cleared >= event.reads.first())
            if (event.complete) require(event.cleared != null && event.reads.last() >= event.cleared)
            precedingZero = event.reads.getOrNull(1)
            event
        }
    }

    private fun decode(raw: Any?, operation: Operation, either: Boolean): Event {
        val keys = setOf("armedAt", "firedAt", "statusReadTicks", "clearedAt")
        require(raw is Map<*, *> && raw.keys == if (either) keys + "operation" else keys)
        if (either) require(raw["operation"] == operation.name.lowercase()) { "IRQ92 event differs from submitted operation" }
        fun tick(value: Any?) = VglibSnapshotCodec.requireLong(value, "IRQ92 tick")
        val reads = raw["statusReadTicks"]
        require(reads is List<*> && reads.size <= 2)
        return Event(operation, tick(raw["armedAt"]), raw["firedAt"]?.let(::tick), reads.map(::tick), raw["clearedAt"]?.let(::tick))
    }
}
