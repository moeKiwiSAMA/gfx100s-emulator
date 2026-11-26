package com.github.nyabanban.fuji.emulator.components.shutter

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone GFX100S focal-plane / electronic shutter component (`:components:shutter`).
 *
 * Exposes [ShutterPort] at `"shutter.mechanism"` when enabled, owns GIC external-interrupt
 * routes for CAM1 (`EXTINT #3`: IRQ `154`/`170`), CAM2 (`EXTINT #5`: IRQ `156`/`172`), and
 * the focal-plane curtain optical gate (`EXTINT #10`: IRQ `161`/`177`), and integrates with
 * `soc-gpio` (motor PWM/H-bridge actuation + Bank 0 photo-interrupter readback) and
 * `soc-isp-cmd` (CCDTG `TG_ACT` `0xFFF6C184` rolling-shutter observation without fabricating
 * mechanical curtain pulses during Electronic Shutter `ES`).
 */
class ShutterFactory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        ShutterComponent(context, normalizeConfig(config))

    companion object {
        private val DESCRIPTOR = ComponentDescriptor(
            id = "shutter",
            version = "0.0.1",
            settings = listOf(
                Setting(
                    "enabled",
                    ValueKind.BOOLEAN,
                    false,
                    live = false,
                    description = "Enable standalone GFX100S focal-plane / electronic shutter assembly (registers shutter.mechanism and routes EXTINT #3/#5/#10 GIC IRQs 154/156/161/170/172/177).",
                ),
                Setting(
                    "curtainTravelUs",
                    ValueKind.INTEGER,
                    ShutterMechanism.DEFAULT_CURTAIN_TRAVEL_US,
                    100.0,
                    50_000.0,
                    live = true,
                    description = "Mechanical focal-plane curtain transit duration in microseconds (default 4000 us for GFX100S 44x33mm shutter).",
                ),
                Setting(
                    "rollingReadoutUs",
                    ValueKind.INTEGER,
                    ShutterMechanism.DEFAULT_ROLLING_READOUT_US,
                    1_000.0,
                    500_000.0,
                    live = true,
                    description = "Electronic rolling-shutter full-frame readout duration in microseconds (default 125000 us for IMX461 102MP 14-bit).",
                ),
                Setting(
                    "camTravelTicks",
                    ValueKind.INTEGER,
                    ShutterMechanism.DEFAULT_CAM_TRAVEL_TICKS,
                    1_000.0,
                    5_000_000.0,
                    live = false,
                    description = "Initial cam motor photo-interrupter transit time in 54 MHz virtual ticks.",
                ),
                Setting(
                    "camContinueTicks",
                    ValueKind.INTEGER,
                    ShutterMechanism.DEFAULT_CAM_CONTINUE_TICKS,
                    10_000.0,
                    50_000_000.0,
                    live = false,
                    description = "Continuous cam rotation period in 54 MHz virtual ticks while motor remains energized.",
                ),
                Setting(
                    "curtainEdgeTicks",
                    ValueKind.INTEGER,
                    ShutterMechanism.DEFAULT_CURTAIN_EDGE_TICKS,
                    1_000.0,
                    5_000_000.0,
                    live = false,
                    description = "Optical-gate half-pulse interval in 54 MHz virtual ticks during mechanical curtain travel.",
                ),
                Setting(
                    "initialShutterCount",
                    ValueKind.INTEGER,
                    0L,
                    0.0,
                    10_000_000.0,
                    live = false,
                    description = "Initial mechanical + EFCS + electronic shutter actuation counter.",
                ),
            ),
        )

        private fun normalizeConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { it.key to it.default }
            return (defaults + config).mapValues { (key, value) ->
                DESCRIPTOR.settings.firstOrNull { it.key == key }?.validate(value) ?: value
            }
        }
    }

    private class ShutterComponent(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("shutter", context, config), StatefulComponent, ShutterPort {
        override val enabled: Boolean = config.getValue("enabled") as Boolean
        private var interruptSink: InterruptSink? = null
        private var closed: Boolean = false

        private val mechanism = ShutterMechanism(
            camTravelTicks = (config.getValue("camTravelTicks") as Number).toLong(),
            camContinueTicks = (config.getValue("camContinueTicks") as Number).toLong(),
            curtainEdgeTicks = (config.getValue("curtainEdgeTicks") as Number).toLong(),
            curtainTravelUs = (config.getValue("curtainTravelUs") as Number).toLong(),
            rollingReadoutUs = (config.getValue("rollingReadoutUs") as Number).toLong(),
            initialShutterCount = (config.getValue("initialShutterCount") as Number).toLong(),
            irqSinkProvider = { interruptSink ?: context.ports.find("soc-gic.lines", InterruptSink::class.java) },
            tickProvider = { context.clock.ticks() },
        )

        init {
            if (enabled) {
                context.ports.register(id, ShutterPort.PORT_NAME, ShutterPort::class.java, this)
            }
        }

        override val cam1High: Boolean get() = mechanism.cam1High
        override val cam2High: Boolean get() = mechanism.cam2High
        override val curtainHigh: Boolean get() = mechanism.curtainHigh

        override fun interruptRoutes(): List<InterruptRoute> =
            if (enabled) {
                ShutterMechanism.ALL_IRQ_LINES.map { irq -> InterruptRoute("soc-gic.lines", irq..irq) }
            } else {
                emptyList()
            }

        override fun start() {
            check(!closed) { "shutter closed" }
            if (!enabled) return
            interruptSink = checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) {
                "Standalone shutter component requires soc-gic.lines"
            }
        }

        override fun onMotor1PwmChanged(running: Boolean, tick: Long) {
            if (!enabled || closed) return
            mechanism.onMotor1PwmChanged(running, tick)
        }

        override fun onMotor2HBridgeChanged(pin7High: Boolean, pin8High: Boolean, tick: Long) {
            if (!enabled || closed) return
            mechanism.onMotor2HBridgeChanged(pin7High, pin8High, tick)
        }

        override fun onTgActRising(tick: Long) {
            if (!enabled || closed) return
            mechanism.onTgActRising(tick)
        }

        override fun onMechanicalCurtainStroke(tick: Long) {
            if (!enabled || closed) return
            mechanism.scheduleMechanicalCurtainStroke(tick)
        }

        override fun nextDeadline(): Long? =
            if (!enabled || closed) null else mechanism.nextDeadline()

        override fun advanceToTick(tick: Long): Boolean =
            if (!enabled || closed) false else mechanism.advanceToTick(tick)

        override fun telemetry(): ShutterTelemetry = mechanism.telemetry()

        override fun applySettings(settings: Map<String, Any>) {
            check(!closed) { "shutter closed" }
            super.applySettings(settings)
            mechanism.curtainTravelUs = (values.getValue("curtainTravelUs") as Number).toLong()
            mechanism.rollingReadoutUs = (values.getValue("rollingReadoutUs") as Number).toLong()
        }

        override fun close() {
            if (!closed) {
                mechanism.releaseAllIrqs()
                interruptSink = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mechanism.stateMap()

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "shutter closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "mechanism" to mechanism.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "shutter closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "mechanism")) {
                "Unexpected or missing keys in shutter snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported shutter snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsedMechanism = mechanism.parseSnapshot(data["mechanism"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf(
                    "enabled",
                    "camTravelTicks",
                    "camContinueTicks",
                    "curtainEdgeTicks",
                    "initialShutterCount",
                ),
            )
            mechanism.curtainTravelUs = (values.getValue("curtainTravelUs") as Number).toLong()
            mechanism.rollingReadoutUs = (values.getValue("rollingReadoutUs") as Number).toLong()
            mechanism.applySnapshot(parsedMechanism, syncIrqs = enabled)
        }
    }
}
