package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

internal class GraphicsFillIrq92(
    private val context: ComponentContext,
    private val bank: MmioDevice,
    private val fill: GraphicsFillTail,
    val deadline: Long,
    val continuous: Boolean = false,
    private val fillPrefixComplete: () -> Boolean,
) : TickAwareMmioDevice, MmioDevice by bank {
    companion object {
        const val SETTING = "graphicsFillIrq92Tick"
        const val LINE = 92
        const val SINK = "soc-gic.lines"
        val samples = listOf(0x04000000L, 0L)
    }

    private val target by lazy {
        requireNotNull(context.ports.find(SINK, InterruptSink::class.java) as? EdgeInterruptSink)
    }
    init {
        val state = fill.state()
        require(deadline > 0 && state["execution"] != null && state["statusReads"] == samples)
    }

    var firedAt: Long? = null
        private set
    private var acknowledged = false
    private var armedAt: Long? = null

    fun bind() { target }
    override fun nextDeadline() = if (continuous) armedAt?.takeIf { firedAt == null }?.let { Math.addExact(it, 1L) } else deadline.takeIf { firedAt == null }
    override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
    override fun readMayMutateSchedule(): Boolean = bank.readMayMutateSchedule()
    override fun beforeInstruction(tick: Long) {
        if (firedAt != null) return
        if (continuous) {
            val armed = armedAt ?: return
            if (tick <= armed) return
            assertIdleLevel()
            firedAt = tick
            return
        }
        if (tick < deadline) return
        val state = fill.state()
        check((state["readbacks"] as List<*>).size == 1 && state["statusCursor"] == 0 &&
            (state["retainedWords"] as List<*>).last() == GraphicsFillExecution.CONTROL) { "IRQ92 requires one completed Q0 fill" }
        assertIdleLevel()
        firedAt = tick
    }

    fun assertIdleLevel() {
        val line = target.edgeState(LINE)
        require(!line.edge && !line.high && !line.pending) { "IRQ92 requires an idle level line" }
        target.setLine(LINE, true)
    }
    fun lowerLevel() = target.setLine(LINE, false)

    override fun read(offset: Long, size: Int): Long {
        if (offset == 52L && fillPrefixComplete())
            check(firedAt != null && fill.state()["statusCursor"] == (if (acknowledged) 1 else 0)) { "Unexpected IRQ92 status read" }
        return bank.read(offset, size)
    }

    override fun write(offset: Long, size: Int, value: Long) {
        val clear = firedAt != null && !acknowledged && offset == 28L && value == 0L
        if (clear) check(fill.state()["statusCursor"] == 1)
        bank.write(offset, size, value)
        if (continuous && firedAt == null && armedAt == null && fillPrefixComplete() &&
            offset == 28L && value == GraphicsFillExecution.CONTROL && fill.completedFills == 1) {
            armedAt = context.clock.ticks()
        }
        if (clear) {
            lowerLevel()
            acknowledged = true
        }
    }

    fun state() = mapOf("deadline" to deadline, "firedAt" to firedAt, "cleared" to acknowledged)

    fun parse(raw: Any?, ledger: GraphicsFillTail.Ledger?, savedDeadline: Long?): Long? {
        if (raw == null) {
            require(deadline > context.clock.ticks() && (ledger?.statusCursor ?: 0) == 0)
            return null
        }
        require(raw is Map<*, *> && raw.keys == state().keys && savedDeadline != null &&
            VglibSnapshotCodec.requireLong(raw["deadline"], "IRQ92 deadline") == savedDeadline)
        val fired = raw["firedAt"]?.let { VglibSnapshotCodec.requireLong(it, "IRQ92 firedAt") }
        if (savedDeadline != deadline) {
            require(savedDeadline > context.clock.ticks() && deadline > savedDeadline &&
                fired == null && ledger != null && ledger.submissions.isEmpty() && ledger.readbacks.isEmpty()) {
                "Only an outstanding IRQ92 deadline preceding the first fill can be postponed"
            }
        }
        if (fired == null) {
            require(deadline > context.clock.ticks() && ledger?.statusCursor == 0 && raw["cleared"] == false)
            return null
        }
        require(fired in deadline..context.clock.ticks() && ledger != null && ledger.readbacks.isNotEmpty() &&
            ledger.submissions.count { it.getValue("tick") < fired } == 1)
        require(ledger.accesses.lastOrNull { it.tick < fired && it.write && it.offset == 28L }?.value == GraphicsFillExecution.CONTROL)
        var reads = 0
        var cleared = false
        for (access in ledger.accesses) {
            if (!access.write && access.offset == 52L) {
                require(access.tick >= fired && reads == (if (cleared) 1 else 0))
                reads++
            }
            if (!cleared && access.tick >= fired && access.write && access.offset == 28L && access.value == 0L) {
                require(reads == 1)
                cleared = true
            }
        }
        require(raw["cleared"] == cleared)
        return fired
    }

    fun restore(fired: Long?, cleared: Boolean) {
        firedAt = fired
        acknowledged = cleared
    }
}
