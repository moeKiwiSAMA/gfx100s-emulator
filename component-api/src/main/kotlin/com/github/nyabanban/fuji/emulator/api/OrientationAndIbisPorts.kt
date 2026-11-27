package com.github.nyabanban.fuji.emulator.api

/**
 * Pure chip-level I2C slave endpoint attachable to an [I2cBusPort] by its 7-bit bus address.
 * Keeps individual commercial silicon models (e.g. NXP MMA8452Q, ams TMD2771, touch ICs) decoupled
 * from SoC bus controllers.
 */
interface I2cSlaveDevice {
    val address7: Int
    fun address(byte: Int): Boolean
    fun checkWrite(byte: Int)
    fun write(byte: Int)
    fun peekRead(): Int
    fun consumeRead(expected: Int)
    fun stop()
}

/**
 * Instantaneous 3-axis acceleration vector in standard gravity units (`1.0 = 1g`), expressed in
 * sensor-chip axes `(xG, yG, zG)`.
 */
data class CameraGravityVector(
    val xG: Double,
    val yG: Double,
    val zG: Double,
)

/**
 * Board-level EVF proximity / eye-sensor control port (registered by standalone `:components:tmd2772`
 * at `"tmd2772.eye"`), allowing legacy `i2c-bus.eyeSensorNear` patches and Studio/API controls to
 * stay seamlessly synchronized with the discrete AMS/TAOS `TMD27723` IC.
 */
interface EyeSensorPort {
    val near: Boolean
    fun setNear(near: Boolean)

    companion object {
        const val PORT_NAME: String = "tmd2772.eye"
    }
}

/**
 * Board-level rear capacitive multi-touch controller port (registered by standalone
 * `:components:rear-touch-ic` at `"rear-touch-ic.touch"`), allowing Desktop `CameraStudio`,
 * Control API `POST /api/touch`, and legacy `i2c-bus` touch patches to operate the discrete
 * `I2C2 @ 0x55` touch IC seamlessly.
 */
interface RearTouchPort {
    val touchEnabled: Boolean
    fun updateSingleTouch(enabled: Boolean, pressed: Boolean, x: Int, y: Int)

    companion object {
        const val PORT_NAME: String = "rear-touch-ic.touch"
    }
}

/**
 * Board-level camera physical orientation & micro-shake telemetry port (registered at
 * `"mma8452q.orientation"` by standalone `:components:mma8452q` and `"i2c-bus.orientation"` by
 * legacy `i2c-bus`), shared between the I2C NXP `MMA8452Q` dual accelerometers (`I2C1 0x1C`,
 * `I2C2 0x1D`) and the SPI2 `biscom` 5-axis IBIS sub-MCU.
 */
interface CameraOrientationPort {
    val accelEnabled: Boolean
    val orientationPreset: String
    val rollDeg: Double
    val pitchDeg: Double
    val shakeAmpDeg: Double
    val lcdTiltDeg: Double

    /** Body accelerometer gravity vector in chip axes (`g`) at virtual [tick]. */
    fun bodyGravityG(tick: Long): CameraGravityVector

    /** Secondary tilting-LCD accelerometer gravity vector in chip axes (`g`) at virtual [tick]. */
    fun lcdGravityG(tick: Long): CameraGravityVector

    /** Instantaneous angular hand-shake perturbation `(rollDeltaDeg, pitchDeltaDeg, yawDeltaDeg)`. */
    fun handShakeDeltaDeg(tick: Long): Triple<Double, Double, Double>

    companion object {
        const val MMA8452Q_PORT_NAME: String = "mma8452q.orientation"
    }
}

/**
 * Optional write-through control extension for [CameraOrientationPort] so legacy patches targeting
 * `i2c-bus` seamlessly update standalone `:components:mma8452q`.
 */
interface CameraOrientationControlPort : CameraOrientationPort {
    fun applyOrientationPatch(settings: Map<String, Any>)
}

/** Mechanical captures send a signed APEX Tv in 1/16 EV before the corresponding still readout. */
interface IbisMechanicalShutterPort : IbisShiftPort {
    fun takeMechanicalShutterTv16(): Int?
}

/** Optional BIS port for the APEX shutter value (1/16 EV) commanded in electronic front-curtain mode. */
interface IbisEfcsShutterPort : IbisShiftPort {
    /** Consumes the most recent electronic front-curtain command once at the start of a still readout. */
    fun takeEfcsShutterTv16(): Int?
}

/**
 * Live 5-axis IBIS + 16-shot 400MP Pixel Shift sensor-stage telemetry exposed by the `biscom`
 * (SPI2) sub-MCU at `"soc-spi.biscom.ibis"` and consumed by `components/lens` (`CpuOpticalPipeline`)
 * and Desktop Studio telemetry.
 */
interface IbisShiftPort {
    val ibisMode: Int
    val ibisModeName: String
    val stageLocked: Boolean
    val stabilizationState: Int
    val stabilizationGain: Int
    val focalLengthMm: Double
    val pixelShiftTargetX: Int
    val pixelShiftTargetY: Int
    val shiftXUm: Double
    val shiftYUm: Double
    val rollDeg: Double

    /**
     * Samples the effective sensor-stage optical offset `(shiftXPx, shiftYPx, rollRad)` for the
     * active sensor pixel pitch (`3.76 µm` on GFX100S) at virtual [tick].
     */
    fun sampleShiftPixels(pixelPitchUm: Double, tick: Long): Triple<Double, Double, Double>

    companion object {
        const val PORT_NAME: String = "soc-spi.biscom.ibis"
        const val MODE_OFF: Int = 0
        const val MODE_CONTINUOUS: Int = 1
        const val MODE_SHOOTING_ONLY: Int = 2
        const val MODE_LOCK: Int = 3
        const val HALL_CENTER: Int = 0x8000
    }
}
