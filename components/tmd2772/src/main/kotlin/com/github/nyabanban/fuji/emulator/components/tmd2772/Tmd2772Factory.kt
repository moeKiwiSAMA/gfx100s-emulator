package com.github.nyabanban.fuji.emulator.components.tmd2772

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone **ams / TAOS `TMD27723`** EVF proximity & ALS optical sensor IC component.
 *
 * Attaches to pure SoC I2C bus (`i2c-bus.bus1` at 7-bit address `0x39`), drives active-LOW
 * open-drain `INT` on `GPIO(0, 8)` via `soc-gpio.inputs`, and exposes [EyeSensorPort] at
 * `"tmd2772.eye"` so Studio UI / Control API / legacy `i2c-bus.eyeSensorNear` patches operate the
 * discrete silicon directly.
 */
class Tmd2772Factory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, normalizeConfig(config))

    companion object {
        const val DEFAULT_BUS_PORT = "i2c-bus.bus1"
        const val DEFAULT_INT_GPIO_BANK = 0
        const val DEFAULT_INT_GPIO_PIN = 8

        private val DESCRIPTOR = ComponentDescriptor(
            id = "tmd2772",
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false, description = "Attach standalone AMS/TAOS TMD27723 proximity sensor to I2C bus"),
                Setting("busPort", ValueKind.TEXT, DEFAULT_BUS_PORT, live = false, description = "Target I2C bus port name (default i2c-bus.bus1)"),
                Setting("address7", ValueKind.INTEGER, Tmd2772Device.DEFAULT_ADDRESS7.toLong(), 8.0, 119.0, live = false, description = "7-bit I2C slave address (0x39 = 57)"),
                Setting("eyeSensorNear", ValueKind.BOOLEAN, false, live = true, description = "EVF eye-sensor proximity state (true=near/EVF, false=far/LCD)"),
                Setting("proximityRaw", ValueKind.INTEGER, -1L, -1.0, Tmd2772Device.MAX_PROXIMITY_RAW.toDouble(), live = true, description = "Optional raw 10-bit PDATA override (-1=auto from eyeSensorNear, 0..1023)"),
                Setting("driveIntGpio", ValueKind.BOOLEAN, true, live = false, description = "Drive active-LOW open-drain INT onto GPIO(intGpioBank, intGpioPin)"),
                Setting("intGpioBank", ValueKind.INTEGER, DEFAULT_INT_GPIO_BANK.toLong(), 0.0, 15.0, live = false, description = "GPIO input bank for active-LOW INT (GFX100S DAT: bank 0)"),
                Setting("intGpioPin", ValueKind.INTEGER, DEFAULT_INT_GPIO_PIN.toLong(), 0.0, 15.0, live = false, description = "GPIO input pin for active-LOW INT (GFX100S DAT: pin 8)"),
            ),
        )

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
    ) : ScalarComponent("tmd2772", context, config), StatefulComponent, EyeSensorPort {
        private val enabled: Boolean = config.getValue("enabled") as Boolean
        private val busPortName: String = config.getValue("busPort") as String
        private val address7: Int = (config.getValue("address7") as Number).toInt()
        private val driveIntGpio: Boolean = config.getValue("driveIntGpio") as Boolean
        private val intGpioBank: Int = (config.getValue("intGpioBank") as Number).toInt()
        private val intGpioPin: Int = (config.getValue("intGpioPin") as Number).toInt()

        private var attachedBus: I2cBusPort? = null
        private var gpioInputs: GpioInputPort? = null
        private var closed: Boolean = false

        private val device: Tmd2772Device = Tmd2772Device(
            address7 = address7,
            initialNear = config.getValue("eyeSensorNear") as Boolean,
            initialProximityRaw = (config.getValue("proximityRaw") as Number).toInt(),
            onIntChanged = { asserted -> driveIntPin(asserted) },
        )

        init {
            if (enabled) {
                context.ports.register(id, EyeSensorPort.PORT_NAME, EyeSensorPort::class.java, this)
            }
        }

        private fun driveIntPin(asserted: Boolean) {
            if (!enabled || !driveIntGpio || closed) return
            // Active-LOW open-drain INT: asserted -> LOW (false), cleared -> HIGH (true)
            gpioInputs?.setInputLevel(intGpioBank, intGpioPin, high = !asserted)
        }

        override val near: Boolean get() = device.isNear

        override fun setNear(near: Boolean) {
            check(!closed) { "tmd2772 closed" }
            if (values["eyeSensorNear"] != near) {
                values = values + ("eyeSensorNear" to near)
            }
            device.setNear(near)
        }

        override fun start() {
            check(!closed) { "tmd2772 closed" }
            if (!enabled) return
            gpioInputs = context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)
            val bus = context.ports.find(busPortName, I2cBusPort::class.java)
            if (bus != null) {
                bus.registerSlave(device)
                attachedBus = bus
            }
            device.syncIntOutput()
        }

        override fun applySettings(settings: Map<String, Any>) {
            check(!closed) { "tmd2772 closed" }
            super.applySettings(settings)
            if ("eyeSensorNear" in settings) {
                device.setNear(values.getValue("eyeSensorNear") as Boolean)
            }
            if ("proximityRaw" in settings) {
                device.setProximityRawOverride((values.getValue("proximityRaw") as Number).toInt())
            }
        }

        override fun close() {
            if (!closed) {
                attachedBus?.unregisterSlave(address7, device)
                attachedBus = null
                gpioInputs = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "busAttached" to (attachedBus != null),
            "gpioIntAttached" to (gpioInputs != null && driveIntGpio),
            "device" to device.state(),
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "tmd2772 closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "device" to device.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "tmd2772 closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "device")) {
                "Unexpected or missing keys in tmd2772 snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported tmd2772 snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsedDevice = device.parseSnapshot(data["device"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("enabled", "busPort", "address7", "driveIntGpio", "intGpioBank", "intGpioPin"),
            )
            device.applySnapshot(parsedDevice)
        }
    }
}
