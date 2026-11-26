package com.github.nyabanban.fuji.emulator.components.shutter

import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.ShutterMode
import com.github.nyabanban.fuji.emulator.api.ShutterTelemetry

/**
 * Authentic GFX100S focal-plane / electronic shutter hardware state machine.
 *
 * Models the three physical capture modes:
 * - **Mechanical Shutter (`MS`)**: Motor 1 (`GPIO(1,0)` PWM -> `GPIO(0,3)` `EXTINT #3` IRQ `154`/`170`)
 *   and Motor 2 (`GPIO(7,7..8)` H-bridge -> `GPIO(0,5)` `EXTINT #5` IRQ `156`/`172`) actuate mechanical
 *   focal-plane curtains across the optical gate (`GPIO(0,10)` `EXTINT #10` IRQ `161`/`177`).
 * - **EFCS (`E-FRONT CURTAIN SHUTTER`)**: Electronic front reset paired with mechanical rear curtain
 *   stroke driven via Motor 2.
 * - **Electronic Shutter (`ES`)**: Pure IMX461 rolling-shutter readout triggered by CCDTG `TG_ACT`
 *   (`0xFFF6C184`) with Motor 2 idle (`cam2Cycles == 0`) and ZERO mechanical curtain optical-gate
 *   pulses (`curtainPulses == 0`, fixing `BUG-A2-5`).
 *
 * Hardware pin & IRQ mapping verified against GFX100S 2.13 `DAT` (`section4 0x0176c744`,
 * `section5 0x024e5d78..0x024e6358`, `0x024e502c..0x024e5a94`, `0x02509064`, `0x02509338`).
 */
class ShutterMechanism(
    val camTravelTicks: Long = DEFAULT_CAM_TRAVEL_TICKS,
    val camContinueTicks: Long = DEFAULT_CAM_CONTINUE_TICKS,
    val curtainEdgeTicks: Long = DEFAULT_CURTAIN_EDGE_TICKS,
    var curtainTravelUs: Long = DEFAULT_CURTAIN_TRAVEL_US,
    var rollingReadoutUs: Long = DEFAULT_ROLLING_READOUT_US,
    initialShutterCount: Long = 0L,
    private val irqSinkProvider: () -> InterruptSink?,
    private val tickProvider: () -> Long,
) {
    companion object {
        const val SHUTTER_CAM1_HIGH_IRQ = 154 // EXTINT #3 rising/high: GPIO(0,3)
        const val SHUTTER_CAM1_LOW_IRQ = 170  // EXTINT #3 falling/low: GPIO(0,3)
        const val SHUTTER_CAM2_HIGH_IRQ = 156 // EXTINT #5 rising/high: GPIO(0,5)
        const val SHUTTER_CAM2_LOW_IRQ = 172  // EXTINT #5 falling/low: GPIO(0,5)
        const val SHUTTER_CURTAIN_HIGH_IRQ = 161 // EXTINT #10 rising/high: GPIO(0,10)
        const val SHUTTER_CURTAIN_LOW_IRQ = 177  // EXTINT #10 falling/low: GPIO(0,10)

        val ALL_IRQ_LINES = listOf(
            SHUTTER_CAM1_HIGH_IRQ,
            SHUTTER_CAM2_HIGH_IRQ,
            SHUTTER_CURTAIN_HIGH_IRQ,
            SHUTTER_CAM1_LOW_IRQ,
            SHUTTER_CAM2_LOW_IRQ,
            SHUTTER_CURTAIN_LOW_IRQ,
        )

        const val DEFAULT_CAM_TRAVEL_TICKS = 100_000L
        const val DEFAULT_CAM_CONTINUE_TICKS = 4_000_000L
        const val DEFAULT_CURTAIN_EDGE_TICKS = 80_000L
        const val CURTAIN_PULSE_EDGES = 6 // 3 full optical-gate pulses (0->1->0->1->0->1->0)
        const val DEFAULT_CURTAIN_TRAVEL_US = 4_000L // ~4.0 ms GFX100S 44x33mm focal-plane transit
        const val DEFAULT_ROLLING_READOUT_US = 125_000L // ~125 ms IMX461 102MP 14-bit rolling readout
        const val TICKS_PER_US = 54L // SPX4 54 MHz virtual timer clock

        private val SNAPSHOT_KEYS = setOf(
            "mode",
            "cam1High",
            "cam2High",
            "curtainHigh",
            "motor1Running",
            "motor2Running",
            "motor2Direction",
            "motor1Deadline",
            "motor2Deadline",
            "curtainEdgesRemaining",
            "curtainDeadline",
            "cam1Cycles",
            "cam2Cycles",
            "curtainPulses",
            "tgActCount",
            "shutterCount",
            "mechanicalCount",
            "efcsCount",
            "electronicCount",
            "motor2StrokesInWindow",
            "lastActuationTick",
            "readoutCompleteTick",
        )
    }

    data class SnapshotState(
        val mode: ShutterMode,
        val cam1High: Boolean,
        val cam2High: Boolean,
        val curtainHigh: Boolean,
        val motor1Running: Boolean,
        val motor2Running: Boolean,
        val motor2Direction: Int,
        val motor1Deadline: Long?,
        val motor2Deadline: Long?,
        val curtainEdgesRemaining: Int,
        val curtainDeadline: Long?,
        val cam1Cycles: Long,
        val cam2Cycles: Long,
        val curtainPulses: Long,
        val tgActCount: Long,
        val shutterCount: Long,
        val mechanicalCount: Long,
        val efcsCount: Long,
        val electronicCount: Long,
        val motor2StrokesInWindow: Int,
        val lastActuationTick: Long?,
        val readoutCompleteTick: Long?,
    )

    var mode: ShutterMode = ShutterMode.IDLE
        private set
    var cam1High: Boolean = false
        private set
    var cam2High: Boolean = false
        private set
    var curtainHigh: Boolean = false
        private set
    var motor1Running: Boolean = false
        private set
    var motor2Running: Boolean = false
        private set
    var motor2Direction: Int = 0
        private set

    private var motor1Deadline: Long? = null
    private var motor2Deadline: Long? = null
    private var curtainEdgesRemaining: Int = 0
    private var curtainDeadline: Long? = null

    var cam1Cycles: Long = 0L
        private set
    var cam2Cycles: Long = 0L
        private set
    var curtainPulses: Long = 0L
        private set
    var tgActCount: Long = 0L
        private set
    var shutterCount: Long = initialShutterCount
        private set
    var mechanicalCount: Long = 0L
        private set
    var efcsCount: Long = 0L
        private set
    var electronicCount: Long = 0L
        private set

    private var motor2StrokesInWindow: Int = 0
    private var lastActuationTick: Long? = null
    private var readoutCompleteTick: Long? = null

    private var cam1HighIrq: Boolean = false
    private var cam1LowIrq: Boolean = false
    private var cam2HighIrq: Boolean = false
    private var cam2LowIrq: Boolean = false
    private var curtainHighIrq: Boolean = false
    private var curtainLowIrq: Boolean = false

    private fun driveIrq(irq: Int, current: Boolean, target: Boolean): Boolean {
        if (current == target) return current
        irqSinkProvider()?.setLine(irq, target)
        return target
    }

    private fun syncCam1IrqLines() {
        cam1HighIrq = driveIrq(SHUTTER_CAM1_HIGH_IRQ, cam1HighIrq, cam1High)
        cam1LowIrq = driveIrq(SHUTTER_CAM1_LOW_IRQ, cam1LowIrq, !cam1High)
    }

    private fun syncCam2IrqLines() {
        cam2HighIrq = driveIrq(SHUTTER_CAM2_HIGH_IRQ, cam2HighIrq, cam2High)
        cam2LowIrq = driveIrq(SHUTTER_CAM2_LOW_IRQ, cam2LowIrq, !cam2High)
    }

    private fun syncCurtainIrqLines() {
        curtainHighIrq = driveIrq(SHUTTER_CURTAIN_HIGH_IRQ, curtainHighIrq, curtainHigh)
        curtainLowIrq = driveIrq(SHUTTER_CURTAIN_LOW_IRQ, curtainLowIrq, !curtainHigh)
    }

    fun onMotor1PwmChanged(running: Boolean, tick: Long) {
        if (running && !motor1Running) {
            motor1Running = true
            motor1Deadline = tick + camTravelTicks
        } else if (!running && motor1Running) {
            motor1Running = false
            motor1Deadline = null
        }
    }

    fun onMotor2HBridgeChanged(pin7High: Boolean, pin8High: Boolean, tick: Long) {
        val running = pin7High != pin8High
        val direction = when {
            !running -> 0
            pin7High -> 1
            else -> -1
        }
        if (running && (!motor2Running || direction != motor2Direction)) {
            motor2Running = true
            motor2Direction = direction
            motor2Deadline = tick + camTravelTicks
            motor2StrokesInWindow++
            lastActuationTick = tick
            scheduleMechanicalCurtainStroke(tick)
        } else if (!running && motor2Running) {
            motor2Running = false
            motor2Direction = 0
            motor2Deadline = null
        }
    }

    fun scheduleMechanicalCurtainStroke(tick: Long) {
        curtainEdgesRemaining = CURTAIN_PULSE_EDGES
        curtainDeadline = tick + curtainEdgeTicks
    }

    /**
     * Observes CCDTG `TG_ACT` (`0xFFF6C184`) rising edge.
     *
     * Per DAT `S4 0x011B5B4C <- S5 0x0239341C`, `0xFFF6C184` is the IMX461 Timing Generator
     * Activate strobe (NOT a mechanical curtain solenoid). This updates rolling-shutter readout
     * telemetry and classifies the exposure mode without ever fabricating `GPIO(0,10)` mechanical
     * curtain pulses (`BUG-A2-5`).
     */
    fun onTgActRising(tick: Long) {
        tgActCount++
        val readoutStillInFlight = readoutCompleteTick != null && tick < readoutCompleteTick!!
        if (motor2StrokesInWindow > 0) {
            lastActuationTick = tick
            readoutCompleteTick = tick + rollingReadoutUs * TICKS_PER_US
            if (motor2StrokesInWindow >= 2) {
                mode = ShutterMode.MECHANICAL
                mechanicalCount++
            } else {
                mode = ShutterMode.EFCS
                efcsCount++
            }
            shutterCount++
            motor2StrokesInWindow = 0
        } else if (!readoutStillInFlight) {
            lastActuationTick = tick
            readoutCompleteTick = tick + rollingReadoutUs * TICKS_PER_US
            mode = ShutterMode.ELECTRONIC
            electronicCount++
            shutterCount++
        }
    }

    fun nextDeadline(): Long? {
        var earliest: Long? = motor1Deadline
        val d2 = motor2Deadline
        if (d2 != null && (earliest == null || d2 < earliest)) earliest = d2
        val dc = curtainDeadline
        if (dc != null && (earliest == null || dc < earliest)) earliest = dc
        return earliest
    }

    fun advanceToTick(tick: Long): Boolean {
        var pinChanged = false
        val d1 = motor1Deadline
        if (d1 != null && tick >= d1) {
            if (motor1Running) {
                cam1High = !cam1High
                cam1Cycles++
                pinChanged = true
                syncCam1IrqLines()
                motor1Deadline = tick + camContinueTicks
            } else {
                motor1Deadline = null
            }
        }
        val d2 = motor2Deadline
        if (d2 != null && tick >= d2) {
            if (motor2Running) {
                cam2High = !cam2High
                cam2Cycles++
                pinChanged = true
                syncCam2IrqLines()
                motor2Deadline = tick + camContinueTicks
            } else {
                motor2Deadline = null
            }
        }
        val dc = curtainDeadline
        if (dc != null && tick >= dc) {
            if (curtainEdgesRemaining > 0) {
                curtainHigh = !curtainHigh
                curtainEdgesRemaining--
                if (curtainHigh) curtainPulses++
                pinChanged = true
                syncCurtainIrqLines()
                curtainDeadline = if (curtainEdgesRemaining > 0) tick + curtainEdgeTicks else null
            } else {
                curtainDeadline = null
            }
        }
        val rc = readoutCompleteTick
        if (rc != null && tick >= rc) {
            readoutCompleteTick = null
        }
        return pinChanged
    }

    fun releaseAllIrqs() {
        cam1HighIrq = driveIrq(SHUTTER_CAM1_HIGH_IRQ, cam1HighIrq, false)
        cam1LowIrq = driveIrq(SHUTTER_CAM1_LOW_IRQ, cam1LowIrq, false)
        cam2HighIrq = driveIrq(SHUTTER_CAM2_HIGH_IRQ, cam2HighIrq, false)
        cam2LowIrq = driveIrq(SHUTTER_CAM2_LOW_IRQ, cam2LowIrq, false)
        curtainHighIrq = driveIrq(SHUTTER_CURTAIN_HIGH_IRQ, curtainHighIrq, false)
        curtainLowIrq = driveIrq(SHUTTER_CURTAIN_LOW_IRQ, curtainLowIrq, false)
        motor1Running = false
        motor2Running = false
        motor2Direction = 0
        motor1Deadline = null
        motor2Deadline = null
        curtainEdgesRemaining = 0
        curtainDeadline = null
    }

    fun telemetry(): ShutterTelemetry {
        val now = tickProvider()
        val completeAt = readoutCompleteTick
        val activeReadout = completeAt != null && now < completeAt
        return ShutterTelemetry(
            mode = mode,
            cam1High = cam1High,
            cam2High = cam2High,
            curtainHigh = curtainHigh,
            motor1Running = motor1Running,
            motor2Running = motor2Running,
            motor2Direction = motor2Direction,
            cam1Cycles = cam1Cycles,
            cam2Cycles = cam2Cycles,
            curtainPulses = curtainPulses,
            tgActCount = tgActCount,
            shutterCount = shutterCount,
            mechanicalCount = mechanicalCount,
            efcsCount = efcsCount,
            electronicCount = electronicCount,
            curtainTravelUs = curtainTravelUs,
            rollingReadoutUs = rollingReadoutUs,
            readoutActive = activeReadout,
            lastActuationTick = lastActuationTick,
            readoutCompleteTick = if (activeReadout) completeAt else null,
        )
    }

    fun stateMap(): Map<String, Any?> {
        val t = telemetry()
        return linkedMapOf(
            "mode" to t.mode.name,
            "cam1High" to t.cam1High,
            "cam2High" to t.cam2High,
            "curtainHigh" to t.curtainHigh,
            "motor1Running" to t.motor1Running,
            "motor2Running" to t.motor2Running,
            "motor2Direction" to t.motor2Direction,
            "cam1Cycles" to t.cam1Cycles,
            "cam2Cycles" to t.cam2Cycles,
            "curtainPulses" to t.curtainPulses,
            "tgActCount" to t.tgActCount,
            "shutterCount" to t.shutterCount,
            "mechanicalCount" to t.mechanicalCount,
            "efcsCount" to t.efcsCount,
            "electronicCount" to t.electronicCount,
            "curtainTravelUs" to t.curtainTravelUs,
            "rollingReadoutUs" to t.rollingReadoutUs,
            "readoutActive" to t.readoutActive,
            "lastActuationTick" to t.lastActuationTick,
            "readoutCompleteTick" to t.readoutCompleteTick,
            "confidence" to "DAT",
        )
    }

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.name,
        "cam1High" to cam1High,
        "cam2High" to cam2High,
        "curtainHigh" to curtainHigh,
        "motor1Running" to motor1Running,
        "motor2Running" to motor2Running,
        "motor2Direction" to motor2Direction,
        "motor1Deadline" to motor1Deadline,
        "motor2Deadline" to motor2Deadline,
        "curtainEdgesRemaining" to curtainEdgesRemaining,
        "curtainDeadline" to curtainDeadline,
        "cam1Cycles" to cam1Cycles,
        "cam2Cycles" to cam2Cycles,
        "curtainPulses" to curtainPulses,
        "tgActCount" to tgActCount,
        "shutterCount" to shutterCount,
        "mechanicalCount" to mechanicalCount,
        "efcsCount" to efcsCount,
        "electronicCount" to electronicCount,
        "motor2StrokesInWindow" to motor2StrokesInWindow,
        "lastActuationTick" to lastActuationTick,
        "readoutCompleteTick" to readoutCompleteTick,
    )

    fun parseSnapshot(raw: Any?): SnapshotState {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid shutter mechanism snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected or missing keys in shutter mechanism snapshot" }
        val modeName = map["mode"] as? String ?: throw IllegalArgumentException("Invalid shutter mode")
        val parsedMode = runCatching { ShutterMode.valueOf(modeName) }
            .getOrElse { throw IllegalArgumentException("Unknown shutter mode: $modeName") }
        return SnapshotState(
            mode = parsedMode,
            cam1High = map["cam1High"] as? Boolean ?: throw IllegalArgumentException("Invalid cam1High"),
            cam2High = map["cam2High"] as? Boolean ?: throw IllegalArgumentException("Invalid cam2High"),
            curtainHigh = map["curtainHigh"] as? Boolean ?: throw IllegalArgumentException("Invalid curtainHigh"),
            motor1Running = map["motor1Running"] as? Boolean ?: throw IllegalArgumentException("Invalid motor1Running"),
            motor2Running = map["motor2Running"] as? Boolean ?: throw IllegalArgumentException("Invalid motor2Running"),
            motor2Direction = parseInt(map["motor2Direction"], "motor2Direction", -1, 1),
            motor1Deadline = map["motor1Deadline"]?.let { parseLong(it, "motor1Deadline", 0L, Long.MAX_VALUE) },
            motor2Deadline = map["motor2Deadline"]?.let { parseLong(it, "motor2Deadline", 0L, Long.MAX_VALUE) },
            curtainEdgesRemaining = parseInt(map["curtainEdgesRemaining"], "curtainEdgesRemaining", 0, 64),
            curtainDeadline = map["curtainDeadline"]?.let { parseLong(it, "curtainDeadline", 0L, Long.MAX_VALUE) },
            cam1Cycles = parseLong(map["cam1Cycles"], "cam1Cycles", 0L, Long.MAX_VALUE),
            cam2Cycles = parseLong(map["cam2Cycles"], "cam2Cycles", 0L, Long.MAX_VALUE),
            curtainPulses = parseLong(map["curtainPulses"], "curtainPulses", 0L, Long.MAX_VALUE),
            tgActCount = parseLong(map["tgActCount"], "tgActCount", 0L, Long.MAX_VALUE),
            shutterCount = parseLong(map["shutterCount"], "shutterCount", 0L, Long.MAX_VALUE),
            mechanicalCount = parseLong(map["mechanicalCount"], "mechanicalCount", 0L, Long.MAX_VALUE),
            efcsCount = parseLong(map["efcsCount"], "efcsCount", 0L, Long.MAX_VALUE),
            electronicCount = parseLong(map["electronicCount"], "electronicCount", 0L, Long.MAX_VALUE),
            motor2StrokesInWindow = parseInt(map["motor2StrokesInWindow"], "motor2StrokesInWindow", 0, 1024),
            lastActuationTick = map["lastActuationTick"]?.let { parseLong(it, "lastActuationTick", 0L, Long.MAX_VALUE) },
            readoutCompleteTick = map["readoutCompleteTick"]?.let { parseLong(it, "readoutCompleteTick", 0L, Long.MAX_VALUE) },
        )
    }

    fun applySnapshot(state: SnapshotState, syncIrqs: Boolean) {
        mode = state.mode
        cam1High = state.cam1High
        cam2High = state.cam2High
        curtainHigh = state.curtainHigh
        motor1Running = state.motor1Running
        motor2Running = state.motor2Running
        motor2Direction = state.motor2Direction
        motor1Deadline = state.motor1Deadline
        motor2Deadline = state.motor2Deadline
        curtainEdgesRemaining = state.curtainEdgesRemaining
        curtainDeadline = state.curtainDeadline
        cam1Cycles = state.cam1Cycles
        cam2Cycles = state.cam2Cycles
        curtainPulses = state.curtainPulses
        tgActCount = state.tgActCount
        shutterCount = state.shutterCount
        mechanicalCount = state.mechanicalCount
        efcsCount = state.efcsCount
        electronicCount = state.electronicCount
        motor2StrokesInWindow = state.motor2StrokesInWindow
        lastActuationTick = state.lastActuationTick
        readoutCompleteTick = state.readoutCompleteTick
        if (syncIrqs) {
            syncCam1IrqLines()
            syncCam2IrqLines()
            syncCurtainIrqLines()
        }
    }

    private fun parseLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for shutter.$field" }
        val number = (value as Number).toLong()
        require(number in min..max) { "Out-of-range integer for shutter.$field" }
        return number
    }

    private fun parseInt(value: Any?, field: String, min: Int, max: Int): Int =
        parseLong(value, field, min.toLong(), max.toLong()).toInt()
}
