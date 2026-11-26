package com.github.nyabanban.fuji.emulator.api

/**
 * Operating mode of the GFX100S focal-plane / electronic shutter assembly.
 *
 * - [MECHANICAL]: Mechanical Front Curtain + Mechanical Rear Curtain (`MS`)
 * - [EFCS]: Electronic Front Curtain + Mechanical Rear Curtain (`EFCS`)
 * - [ELECTRONIC]: Pure IMX461 electronic rolling-shutter readout (`ES`, zero mechanical curtain actuation)
 * - [IDLE]: Shutter assembly idle / awaiting capture
 */
enum class ShutterMode {
    IDLE,
    MECHANICAL,
    EFCS,
    ELECTRONIC,
}

/**
 * Snapshot telemetry for the standalone GFX100S focal-plane shutter component (`:components:shutter`).
 */
data class ShutterTelemetry(
    val mode: ShutterMode,
    val cam1High: Boolean,
    val cam2High: Boolean,
    val curtainHigh: Boolean,
    val motor1Running: Boolean,
    val motor2Running: Boolean,
    val motor2Direction: Int,
    val cam1Cycles: Long,
    val cam2Cycles: Long,
    val curtainPulses: Long,
    val tgActCount: Long,
    val shutterCount: Long,
    val mechanicalCount: Long,
    val efcsCount: Long,
    val electronicCount: Long,
    val curtainTravelUs: Long,
    val rollingReadoutUs: Long,
    val readoutActive: Boolean,
    val lastActuationTick: Long?,
    val readoutCompleteTick: Long?,
)

/**
 * Cross-component port exposed by standalone `:components:shutter` at [PORT_NAME] (`"shutter.mechanism"`).
 *
 * Bridges SoC GPIO motor outputs (`GPIO(1,0)` Motor 1 PWM, `GPIO(7,7..8)` Motor 2 H-bridge) and
 * photo-interrupter readback (`GPIO(0,3)` CAM1, `GPIO(0,5)` CAM2, `GPIO(0,10)` curtain optical gate),
 * plus CCDTG `TG_ACT` (`0xFFF6C184`) observation for authentic electronic/rolling-shutter telemetry
 * without fabricating mechanical curtain pulses during Electronic Shutter (`ES`) captures.
 */
interface ShutterPort {
    companion object {
        const val PORT_NAME = "shutter.mechanism"
    }

    /** Whether the standalone shutter assembly is enabled. */
    val enabled: Boolean

    /** Current photo-interrupter level for Motor 1 / Front Charge Cam (`GPIO(0,3)`, `EXTINT #3`). */
    val cam1High: Boolean

    /** Current photo-interrupter level for Motor 2 / Rear Curtain Cam (`GPIO(0,5)`, `EXTINT #5`). */
    val cam2High: Boolean

    /** Current optical-gate level for focal-plane mechanical curtain travel (`GPIO(0,10)`, `EXTINT #10`). */
    val curtainHigh: Boolean

    /** Observe Motor 1 PWM alt-function state (`GPIO(1,0)`, Bank 1 reg 3 bits[1:0] == 1; DAT `S5 0x02509064`). */
    fun onMotor1PwmChanged(running: Boolean, tick: Long)

    /** Observe Motor 2 H-bridge outputs (`GPIO(7,7)` & `GPIO(7,8)`; DAT `S5 0x02509338`). */
    fun onMotor2HBridgeChanged(pin7High: Boolean, pin8High: Boolean, tick: Long)

    /**
     * Observe CCDTG `TG_ACT` (`0xFFF6C184`) rising `0 -> 1` edge (DAT `S4 0x011B5B4C <- S5 0x0239341C`).
     * Records IMX461 timing-generator / rolling-shutter readout timing and classifies exposure mode;
     * NEVER fabricates mechanical curtain optical-gate pulses (`BUG-A2-5`).
     */
    fun onTgActRising(tick: Long)

    /** Explicitly trigger a mechanical focal-plane curtain stroke across optical gate `GPIO(0,10)`. */
    fun onMechanicalCurtainStroke(tick: Long)

    /** Earliest virtual tick when any shutter cam or curtain edge will transition, or `null` if idle. */
    fun nextDeadline(): Long?

    /**
     * Advance scheduled cam / curtain / readout transitions up to [tick].
     * Returns `true` iff any GPIO Bank 0 photo-interrupter pin (`0.3`, `0.5`, `0.10`) changed level.
     */
    fun advanceToTick(tick: Long): Boolean

    /** Current shutter mechanism telemetry snapshot. */
    fun telemetry(): ShutterTelemetry
}
