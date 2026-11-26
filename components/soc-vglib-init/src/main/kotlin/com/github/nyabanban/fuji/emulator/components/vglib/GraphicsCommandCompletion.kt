package com.github.nyabanban.fuji.emulator.components.vglib

internal class GraphicsCommandCompletion(val deadline: Long, val continuous: Boolean = false) {
    companion object {
        const val SETTING = "commandIrq92Tick"
        private data class Step(val offset: Long, val write: Boolean, val value: Long)
        private val steps = listOf(Step(0x34,false,0x80000000), Step(0x1800,false,0), Step(0x1800,true,0),
            Step(0x1020,false,0), Step(0x1020,true,0), Step(0x34,false,0), Step(0x58,false,0))
    }

    private val history = mutableListOf<Long>()
    val pending get() = history.isEmpty()
    val complete get() = history.size == steps.size + 1
    val inCallback get() = history.size in 1..6
    fun effectiveDeadline(renderTick: Long?): Long? = if (!pending) null else if (continuous) renderTick?.let { Math.addExact(it, 1L) } else deadline
    fun fire(tick: Long, renderTick: Long?, assertLevel: () -> Unit) {
        if (!pending) return
        if (continuous) {
            if (renderTick == null || tick <= renderTick) return
            assertLevel()
            history += tick
            return
        }
        if (tick < deadline) return
        check(renderTick != null && renderTick < tick) { "Command IRQ92 requires completed render readback" }
        assertLevel()
        history += tick
    }

    fun access(offset: Long, size: Int, write: Boolean, value: Long, tick: Long, lower: () -> Unit): Long? {
        if (history.size !in 1..steps.size) return null
        val step = steps[history.size-1]
        if (offset != step.offset && (!inCallback || steps.none { it.offset == offset })) return null
        require(offset == step.offset && size == 4 && write == step.write && (!write || value == step.value) && tick >= history.last()) {
            "Unexpected command IRQ92 callback access: offset=0x${offset.toString(16)} expected=0x${step.offset.toString(16)} size=$size write=$write expectedWrite=${step.write} value=0x${value.toString(16)} tick=$tick lastTick=${history.last()}"
        }
        if (step.write && offset == 0x1020L) lower()
        history += tick
        return step.value
    }

    fun snapshot() = history.toList()
    fun parse(raw: Any?, saved: Boolean, renderTick: Long?, now: Long): List<Long> {
        if (!saved) {
            require(deadline > now)
            return emptyList()
        }
        require(raw is List<*> && raw.size <= steps.size + 1)
        val ticks = raw.map { VglibSnapshotCodec.requireLong(it,"command IRQ92 tick") }
        if (ticks.isEmpty()) require(continuous || deadline > now)
        else require(renderTick != null && ticks.first() > renderTick && (continuous || ticks.first() >= deadline) && ticks.last() <= now && ticks == ticks.sorted())
        return ticks
    }
    fun restore(ticks: List<Long>) { history.clear(); history.addAll(ticks) }
}
