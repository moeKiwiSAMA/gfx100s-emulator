package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Continuous 60 Hz edge-triggered VSync/line interrupts for `LCDC0` (`0xFFF7A000`, HDMI channel `ch=0`),
 * verified in GFX100S 2.13 `section4.bin` (`0x016D06E8`, `0x016D5508`, `0x01270270`, `0x015A89AC`):
 * - `GIC IRQ 112` (`type=0`, `lid=0x66`, EDGE, prio `0xB0`, Core 0, ISR `0x0126FBE4 -> 0x0126F98C(0, 0)`)
 * - `GIC IRQ 113` (`type=1`, `lid=0x67`, EDGE, prio `0xB0`, Core 1, ISR `0x0126FBCC -> 0x0126F98C(0, 1)`)
 *
 * Fires strictly when firmware has enabled the corresponding GIC edge line and it is low & non-pending.
 */
internal class Lcdc0ContinuousIrqs(
    private val context: ComponentContext,
    private val bank: LcdcFunctional,
    private val periodTicks: Long = LcdcIrq118.PERIOD,
) : TickAwareMmioDevice, MmioDevice by bank {
    companion object {
        const val LINE_0 = 112
        const val LINE_1 = 113
        const val SINK = LcdcIrq118.SINK
    }

    private val target by lazy {
        context.ports.find(SINK, InterruptSink::class.java) as? EnabledEdgeInterruptSink
    }
    private var nextTick: Long = periodTicks
    private var firedCount112: Long = 0L
    private var firedCount113: Long = 0L
    private var lastFired112: Long? = null
    private var lastFired113: Long? = null

    fun bind() {
        target
    }

    override fun nextDeadline(): Long = nextTick
    override fun nextInterestedTick(): Long = nextTick
    override fun readMayMutateSchedule(): Boolean = false

    override fun beforeInstruction(tick: Long) {
        val due = nextTick
        if (tick < due) return
        val sink = target
        if (sink != null) {
            if (pulseIfReady(sink, LINE_0)) {
                firedCount112++
                lastFired112 = tick
            }
            if (pulseIfReady(sink, LINE_1)) {
                firedCount113++
                lastFired113 = tick
            }
        }
        val anchor = if (tick - due < periodTicks) due else tick
        nextTick = anchor + periodTicks
    }

    private fun pulseIfReady(sink: EnabledEdgeInterruptSink, line: Int): Boolean {
        val state = sink.edgeState(line)
        if (!sink.lineEnabled(line) || !state.edge || state.high || state.pending) {
            return false
        }
        sink.setLine(line, true)
        sink.setLine(line, false)
        return true
    }

    fun resetSchedule(nowTick: Long) {
        nextTick = nowTick + periodTicks
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "instance" to 0,
        "lines" to listOf(LINE_0, LINE_1),
        "sink" to SINK,
        "periodTicks" to periodTicks,
        "firedCount112" to firedCount112,
        "firedCount113" to firedCount113,
        "lastFired112" to lastFired112,
        "lastFired113" to lastFired113,
    )
}
