package com.github.nyabanban.fuji.emulator.components.ispcmd

import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice

/**
 * Real-silicon SPX4 Ultrasonic Piezo Sensor Cleaning controller (`0xFFF6C400..0xFFF6C42B`,
 * `soc-isp-cmd.config-bank-6`, 44 bytes / 11 words) + `GIC IRQ 104` (edge completion interrupt).
 *
 * Derived from GFX100S 2.13 `section5.bin` (`0x024dfdec..0x024e0034`, `0x02388bd4..0x02388db8`,
 * `0x011b779c..0x011b7c3c`):
 * - `+0x00` (`0xFFF6C400`): STOP/IDLE — writing `0` stops/resets piezo; reading returns bit 0 = `1`
 *   when stopped/idle (unblocking tight spin-poll `0x02388c0c..0x02388c18`) and `0` while sweeping.
 * - `+0x04` (`0xFFF6C404`): START — writing bit 0 = `1` starts ultrasonic sweep, clears `+0x00` bit 0,
 *   and schedules completion after [SWEEP_DURATION_TICKS], which sets `+0x00` bit 0 = `1`, clears
 *   `+0x04` bit 0 = `0`, latches `+0x24` bit 0 = `1`, and pulses `GIC IRQ 104` (edge).
 * - `+0x08..+0x20`: Retained piezo control, mode, sweep counts, and 14-bit frequency/period words.
 * - `+0x24` (`0xFFF6C424`): IRQ status/W1C (bit 0 cleared on write of `1`; never triggers a sweep).
 * - `+0x28` (`0xFFF6C428`): Retained IRQ mask/clear word.
 */
internal class UltrasonicPiezoController(
    private val words: LongArray,
    private val clockTicks: () -> Long,
    private val sinkProvider: () -> InterruptSink?,
    private val isClosed: () -> Boolean,
) : TickAwareMmioDevice {
    override val clockOrder: Int = 76
    override fun readMayMutateSchedule(): Boolean = false

    private var running: Boolean = false
    private var completionTick: Long? = null
    private var startedSweeps: Long = 0L
    private var completedSweeps: Long = 0L
    private var stoppedSweeps: Long = 0L

    override fun nextDeadline(): Long? = if (isClosed()) null else completionTick

    override fun nextInterestedTick(): Long = if (isClosed()) Long.MAX_VALUE else (completionTick ?: Long.MAX_VALUE)

    override fun beforeInstruction(tick: Long) {
        if (isClosed()) return
        val due = completionTick ?: return
        if (tick < due) return

        val sink = sinkProvider()
        val enabledSink = sink as? EnabledEdgeInterruptSink
        if (enabledSink != null) {
            if (!enabledSink.lineEnabled(IRQ_LINE)) {
                completionTick = tick + RETRY_POLL_TICKS
                return
            }
            val st = enabledSink.edgeState(IRQ_LINE)
            if (st.high || st.pending) {
                completionTick = tick + RETRY_POLL_TICKS
                return
            }
        }

        running = false
        completionTick = null
        words[SLOT_STOP_IDLE] = words[SLOT_STOP_IDLE] or 1L
        words[SLOT_START] = words[SLOT_START] and 1L.inv()
        words[SLOT_IRQ_STATUS] = words[SLOT_IRQ_STATUS] or 1L
        completedSweeps++

        if (sink != null) {
            sink.setLine(IRQ_LINE, true)
            sink.setLine(IRQ_LINE, false)
        }
    }

    override fun read(offset: Long, size: Int): Long {
        check(!isClosed()) { "soc-isp-cmd closed" }
        require(size == 4 && offset >= 0L && offset < BANK_SIZE && offset % 4L == 0L) {
            "$PORT_ID requires aligned 32-bit word within [0, $BANK_SIZE)"
        }
        val slot = (offset / 4L).toInt()
        val stored = words[slot]
        return when (slot) {
            SLOT_STOP_IDLE -> if (running) stored and 1L.inv() else stored or 1L
            else -> stored
        }
    }

    override fun write(offset: Long, size: Int, value: Long) {
        check(!isClosed()) { "soc-isp-cmd closed" }
        require(size == 4 && offset >= 0L && offset < BANK_SIZE && offset % 4L == 0L) {
            "$PORT_ID requires aligned 32-bit word within [0, $BANK_SIZE)"
        }
        require(value in 0L..0xffffffffL) { "$PORT_ID write outside u32" }
        val slot = (offset / 4L).toInt()
        when (slot) {
            SLOT_STOP_IDLE -> {
                if ((value and 1L) == 0L) {
                    if (running) {
                        stoppedSweeps++
                    }
                    running = false
                    completionTick = null
                    words[SLOT_START] = words[SLOT_START] and 1L.inv()
                    words[SLOT_STOP_IDLE] = value or 1L
                } else {
                    words[SLOT_STOP_IDLE] = value
                }
            }
            SLOT_START -> {
                words[SLOT_START] = value
                if ((value and 1L) != 0L) {
                    running = true
                    words[SLOT_STOP_IDLE] = words[SLOT_STOP_IDLE] and 1L.inv()
                    startedSweeps++
                    completionTick = clockTicks() + SWEEP_DURATION_TICKS
                }
            }
            SLOT_IRQ_STATUS -> {
                // +0x24 bit 0 is W1C IRQ status; upper bits retained. Never starts a sweep.
                val nextBit0 = words[SLOT_IRQ_STATUS] and 1L and value.inv()
                words[SLOT_IRQ_STATUS] = (value and 1L.inv()) or nextBit0
            }
            else -> words[slot] = value
        }
    }

    fun isInitial(): Boolean =
        !running && completionTick == null && startedSweeps == 0L && completedSweeps == 0L && stoppedSweeps == 0L

    fun state(): Map<String, Any?> = linkedMapOf(
        "irqLine" to IRQ_LINE,
        "running" to running,
        "idleBit" to !running,
        "completionTick" to completionTick,
        "startedSweeps" to startedSweeps,
        "completedSweeps" to completedSweeps,
        "stoppedSweeps" to stoppedSweeps,
        "mode" to (words[SLOT_MODE] and 1L),
        "sweepCounts" to words[SLOT_COUNTS],
        "irqStatusBit" to ((words[SLOT_IRQ_STATUS] and 1L) != 0L),
    )

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "running" to running,
        "completionTick" to completionTick,
        "startedSweeps" to startedSweeps,
        "completedSweeps" to completedSweeps,
        "stoppedSweeps" to stoppedSweeps,
    )

    data class ParsedSnapshot(
        val running: Boolean,
        val completionTick: Long?,
        val startedSweeps: Long,
        val completedSweeps: Long,
        val stoppedSweeps: Long,
    )

    fun applySnapshot(parsed: ParsedSnapshot?) {
        if (parsed == null) {
            running = false
            completionTick = null
            startedSweeps = 0L
            completedSweeps = 0L
            stoppedSweeps = 0L
        } else {
            running = parsed.running
            completionTick = parsed.completionTick
            startedSweeps = parsed.startedSweeps
            completedSweeps = parsed.completedSweeps
            stoppedSweeps = parsed.stoppedSweeps
        }
    }

    companion object {
        const val PORT_ID = "soc-isp-cmd.config-bank-6"
        const val BANK_SIZE = 44
        const val IRQ_LINE = 104
        const val SWEEP_DURATION_TICKS = 200_000L
        const val RETRY_POLL_TICKS = 50_000L

        private const val SLOT_STOP_IDLE = 0 // +0x00 (0xFFF6C400)
        private const val SLOT_START = 1 // +0x04 (0xFFF6C404)
        private const val SLOT_MODE = 3 // +0x0C (0xFFF6C40C)
        private const val SLOT_COUNTS = 4 // +0x10 (0xFFF6C410)
        private const val SLOT_IRQ_STATUS = 9 // +0x24 (0xFFF6C424)

        private val SNAPSHOT_KEYS = setOf(
            "running",
            "completionTick",
            "startedSweeps",
            "completedSweeps",
            "stoppedSweeps",
        )

        fun parseSnapshot(raw: Any?): ParsedSnapshot {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field piezo requires map")
            require(map.keys == SNAPSHOT_KEYS) { "Unexpected piezo snapshot keys" }
            val running = map["running"] as? Boolean ?: throw IllegalArgumentException("piezo.running requires boolean")
            val dueRaw = map["completionTick"]
            val due = if (dueRaw == null) {
                null
            } else {
                require(dueRaw is Byte || dueRaw is Short || dueRaw is Int || dueRaw is Long) {
                    "piezo.completionTick requires integer or null"
                }
                (dueRaw as Number).toLong().also { require(it >= 0L) { "piezo.completionTick negative: $it" } }
            }
            require(!running || due != null) { "Running piezo snapshot requires completionTick" }
            val started = reqNonNegLong(map["startedSweeps"], "piezo.startedSweeps")
            val completed = reqNonNegLong(map["completedSweeps"], "piezo.completedSweeps")
            val stopped = reqNonNegLong(map["stoppedSweeps"], "piezo.stoppedSweeps")
            return ParsedSnapshot(running, due, started, completed, stopped)
        }

        private fun reqNonNegLong(raw: Any?, field: String): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "$field requires integer" }
            val v = (raw as Number).toLong()
            require(v >= 0L) { "$field must be non-negative: $v" }
            return v
        }
    }
}
