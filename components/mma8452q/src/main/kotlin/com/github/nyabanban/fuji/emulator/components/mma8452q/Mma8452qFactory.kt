package com.github.nyabanban.fuji.emulator.components.mma8452q

import com.github.nyabanban.fuji.emulator.api.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Standalone **NXP / Freescale `MMA8452Q` / `MMA8652FC`** dual 3-axis accelerometer component.
 *
 * Attaches body (`I2C1 @ 0x1C`) and tilting-LCD (`I2C2 @ 0x1D`) accelerometers onto pure SoC I2C
 * bus ports (`i2c-bus.bus1` and `i2c-bus.bus2`) and exposes [CameraOrientationControlPort] at
 * `"mma8452q.orientation"` for `soc-spi` `biscom` 5-axis IBIS and Desktop `OrientationIbisPane`.
 */
class Mma8452qFactory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, normalizeConfig(config))

    companion object {
        const val DEFAULT_BODY_BUS_PORT = "i2c-bus.bus1"
        const val DEFAULT_LCD_BUS_PORT = "i2c-bus.bus2"
        private const val NOMINAL_TICKS_PER_SECOND = 54_000_000.0
        val ORIENTATION_PRESETS = listOf("landscape", "portrait-cw", "portrait-ccw", "inverted", "custom")

        private val DESCRIPTOR = ComponentDescriptor(
            id = "mma8452q",
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false, description = "Attach standalone NXP MMA8452Q dual accelerometers to I2C1 (0x1C) and I2C2 (0x1D)"),
                Setting("accelEnabled", ValueKind.BOOLEAN, true, live = true, description = "Enable active I2C slave attachment for MMA8452Q chips"),
                Setting("bodyBusPort", ValueKind.TEXT, DEFAULT_BODY_BUS_PORT, live = false, description = "I2C bus port for body accelerometer (0x1C)"),
                Setting("lcdBusPort", ValueKind.TEXT, DEFAULT_LCD_BUS_PORT, live = false, description = "I2C bus port for tilting-LCD accelerometer (0x1D)"),
                Setting("orientationPreset", ValueKind.TEXT, "landscape", choices = ORIENTATION_PRESETS, live = true, description = "Quick camera orientation preset"),
                Setting("rollDeg", ValueKind.DECIMAL, 0.0, -180.0, 180.0, live = true, description = "Camera roll angle in degrees (0=landscape, +90=portrait CW, -90=portrait CCW, 180=inverted)"),
                Setting("pitchDeg", ValueKind.DECIMAL, 0.0, -90.0, 90.0, live = true, description = "Camera pitch angle in degrees (-90=down, 0=level, +90=up)"),
                Setting("shakeAmpDeg", ValueKind.DECIMAL, 0.0, 0.0, 5.0, live = true, description = "Simulated handheld micro-shake amplitude in degrees"),
                Setting("lcdTiltDeg", ValueKind.DECIMAL, 0.0, -45.0, 90.0, live = true, description = "Secondary rear tilting-LCD angle relative to body in degrees"),
            ),
        )

        internal fun attitudeToGravityG(rollDeg: Double, pitchDeg: Double): CameraGravityVector {
            val rollRad = Math.toRadians(rollDeg)
            val pitchRad = Math.toRadians(pitchDeg)
            val cosPitch = cos(pitchRad)
            val filtX = cosPitch * cos(rollRad)
            val filtY = -cosPitch * sin(rollRad)
            val filtZ = -sin(pitchRad)
            // Inverse of body MMA8452Q (I2C1 @ 0x1C) bank14[0x8a2b..0x8a33] matrix M_body = [[0,-1,0],[-1,0,0],[0,0,1]]
            return CameraGravityVector(xG = -filtY, yG = -filtX, zG = filtZ)
        }

        internal fun lcdAttitudeToGravityG(rollDeg: Double, pitchDeg: Double): CameraGravityVector {
            val rollRad = Math.toRadians(rollDeg)
            val pitchRad = Math.toRadians(pitchDeg)
            val cosPitch = cos(pitchRad)
            val filtX = cosPitch * cos(rollRad)
            val filtY = -cosPitch * sin(rollRad)
            val filtZ = -sin(pitchRad)
            // Inverse of tilting-LCD MMA8452Q (I2C2 @ 0x1D) bank14[0x89d6..0x89de] matrix M_lcd = [[-1,0,0],[0,1,0],[0,0,-1]]
            // (loaded at section4 0x012198e8..0x012199a0, applied at 0x01218af8..0x01218b5c)
            return CameraGravityVector(xG = -filtX, yG = filtY, zG = -filtZ)
        }

        internal fun presetAngles(preset: String): Pair<Double, Double>? = when (preset) {
            "landscape" -> 0.0 to 0.0
            "portrait-cw" -> 90.0 to 0.0
            "portrait-ccw" -> -90.0 to 0.0
            "inverted" -> 180.0 to 0.0
            else -> null
        }

        internal fun handShakeDeltaAtTick(shakeAmpDeg: Double, tick: Long): Triple<Double, Double, Double> {
            if (shakeAmpDeg <= 1e-6) return Triple(0.0, 0.0, 0.0)
            val tSec = tick.toDouble() / NOMINAL_TICKS_PER_SECOND
            val rollD = shakeAmpDeg * 0.45 * sin(2.0 * Math.PI * 2.7 * tSec)
            val pitchD = shakeAmpDeg * sin(2.0 * Math.PI * 3.4 * tSec + 0.7)
            val yawD = shakeAmpDeg * 0.85 * sin(2.0 * Math.PI * 2.1 * tSec + 1.9)
            return Triple(rollD, pitchD, yawD)
        }

        private fun normalizeConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { it.key to it.default }
            return (defaults + config).mapValues { (key, value) ->
                DESCRIPTOR.settings.firstOrNull { it.key == key }?.validate(value) ?: value
            }
        }
    }

    private class Chip(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("mma8452q", context, config), StatefulComponent, CameraOrientationControlPort {
        private val enabled: Boolean = config.getValue("enabled") as Boolean
        private val bodyBusPortName: String = config.getValue("bodyBusPort") as String
        private val lcdBusPortName: String = config.getValue("lcdBusPort") as String

        private var bodyBus: I2cBusPort? = null
        private var lcdBus: I2cBusPort? = null
        private var bodyAttached: Boolean = false
        private var lcdAttached: Boolean = false
        private var closed: Boolean = false

        private val bodyChip = Mma8452qChip(Mma8452qChip.ADDRESS_SA0_LOW) { bodyGravityG(context.clock.ticks()) }
        private val lcdChip = Mma8452qChip(Mma8452qChip.ADDRESS_SA0_HIGH) { lcdGravityG(context.clock.ticks()) }

        init {
            val initPreset = values.getValue("orientationPreset") as String
            if ("rollDeg" !in config && "pitchDeg" !in config) {
                presetAngles(initPreset)?.let { (r, p) ->
                    values = values + mapOf("rollDeg" to r, "pitchDeg" to p)
                }
            }
            if (enabled) {
                context.ports.register(id, CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java, this)
            }
        }

        override val accelEnabled: Boolean get() = enabled && (values.getValue("accelEnabled") as Boolean)
        override val orientationPreset: String get() = values.getValue("orientationPreset") as String
        override val rollDeg: Double get() = (values.getValue("rollDeg") as Number).toDouble()
        override val pitchDeg: Double get() = (values.getValue("pitchDeg") as Number).toDouble()
        override val shakeAmpDeg: Double get() = (values.getValue("shakeAmpDeg") as Number).toDouble()
        override val lcdTiltDeg: Double get() = (values.getValue("lcdTiltDeg") as Number).toDouble()

        override fun handShakeDeltaDeg(tick: Long): Triple<Double, Double, Double> =
            handShakeDeltaAtTick(shakeAmpDeg, tick)

        override fun bodyGravityG(tick: Long): CameraGravityVector {
            val (dRoll, dPitch, _) = handShakeDeltaDeg(tick)
            return attitudeToGravityG(rollDeg + dRoll, (pitchDeg + dPitch).coerceIn(-90.0, 90.0))
        }

        override fun lcdGravityG(tick: Long): CameraGravityVector {
            val (dRoll, dPitch, _) = handShakeDeltaDeg(tick)
            return lcdAttitudeToGravityG(rollDeg + dRoll, (pitchDeg + lcdTiltDeg + dPitch).coerceIn(-90.0, 90.0))
        }

        private fun syncBusAttachments() {
            if (!enabled || closed) return
            val attach = values.getValue("accelEnabled") as Boolean
            if (attach) {
                if (!bodyAttached) {
                    bodyBus?.registerSlave(bodyChip)
                    bodyAttached = bodyBus != null
                }
                if (!lcdAttached) {
                    lcdBus?.registerSlave(lcdChip)
                    lcdAttached = lcdBus != null
                }
            } else {
                if (bodyAttached) {
                    bodyBus?.unregisterSlave(bodyChip.address7, bodyChip)
                    bodyAttached = false
                }
                if (lcdAttached) {
                    lcdBus?.unregisterSlave(lcdChip.address7, lcdChip)
                    lcdAttached = false
                }
            }
        }

        override fun start() {
            check(!closed) { "mma8452q closed" }
            if (!enabled) return
            bodyBus = context.ports.find(bodyBusPortName, I2cBusPort::class.java)
            lcdBus = context.ports.find(lcdBusPortName, I2cBusPort::class.java)
            syncBusAttachments()
        }

        override fun applyOrientationPatch(settings: Map<String, Any>) {
            val allowed = settings.filterKeys {
                it in setOf("accelEnabled", "orientationPreset", "rollDeg", "pitchDeg", "shakeAmpDeg", "lcdTiltDeg")
            }
            if (allowed.isNotEmpty()) {
                applySettings(allowed)
            }
        }

        override fun applySettings(settings: Map<String, Any>) {
            check(!closed) { "mma8452q closed" }
            val expanded = LinkedHashMap<String, Any>()
            val preset = settings["orientationPreset"] as? String
            if (preset != null && "rollDeg" !in settings && "pitchDeg" !in settings) {
                presetAngles(preset)?.let { (r, p) ->
                    expanded["rollDeg"] = r
                    expanded["pitchDeg"] = p
                }
            }
            expanded.putAll(settings)
            if (("rollDeg" in settings || "pitchDeg" in settings) && "orientationPreset" !in settings) {
                val r = (expanded["rollDeg"] as? Number)?.toDouble() ?: rollDeg
                val p = (expanded["pitchDeg"] as? Number)?.toDouble() ?: pitchDeg
                expanded["orientationPreset"] = when {
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r) < 0.5 -> "landscape"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r - 90.0) < 0.5 -> "portrait-cw"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r + 90.0) < 0.5 -> "portrait-ccw"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(kotlin.math.abs(r) - 180.0) < 0.5 -> "inverted"
                    else -> "custom"
                }
            }
            super.applySettings(expanded)
            if ("accelEnabled" in expanded) {
                syncBusAttachments()
            }
        }

        override fun close() {
            if (!closed) {
                if (bodyAttached) bodyBus?.unregisterSlave(bodyChip.address7, bodyChip)
                if (lcdAttached) lcdBus?.unregisterSlave(lcdChip.address7, lcdChip)
                bodyAttached = false
                lcdAttached = false
                bodyBus = null
                lcdBus = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "bodyAttached" to bodyAttached,
            "lcdAttached" to lcdAttached,
            "bodyAccel" to bodyChip.state(),
            "lcdAccel" to lcdChip.state(),
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "mma8452q closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "bodyAccel" to bodyChip.saveSnapshot(),
                "lcdAccel" to lcdChip.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "mma8452q closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "bodyAccel", "lcdAccel")) {
                "Unexpected or missing keys in mma8452q snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported mma8452q snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsedBody = bodyChip.parseSnapshot(data["bodyAccel"])
            val parsedLcd = lcdChip.parseSnapshot(data["lcdAccel"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("enabled", "bodyBusPort", "lcdBusPort"),
            )
            bodyChip.applySnapshot(parsedBody)
            lcdChip.applySnapshot(parsedLcd)
            syncBusAttachments()
        }
    }
}
