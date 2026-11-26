package com.github.nyabanban.fuji.emulator.components.reartouch

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone GFX100S rear capacitive multi-touch controller IC component (`I2C2 @ 0x55`,
 * `GIC IRQ 153 / EXTINT #2`, active-HIGH reset on `GPIO(7, 2)`, incrementing `0x12` alive counter).
 *
 * Attaches dynamically to pure SoC `I2cBusPort` (`i2c-bus.bus2`) via [I2cBusPort.registerSlave]
 * and exposes [RearTouchPort] at `"rear-touch-ic.touch"` so Desktop `CameraStudio`, Control API
 * `POST /api/touch`, and legacy `i2c-bus` touch patches all drive the discrete IC seamlessly.
 */
class RearTouchFactory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, normalizeConfig(config))

    companion object {
        const val DEFAULT_BUS_PORT = "i2c-bus.bus2"

        private val DESCRIPTOR = ComponentDescriptor(
            id = "rear-touch-ic",
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false, description = "Attach standalone rear capacitive touch controller IC to I2C2 (0x55)"),
                Setting("busPort", ValueKind.TEXT, DEFAULT_BUS_PORT, live = false, description = "Target I2C bus port name (default i2c-bus.bus2)"),
                Setting("address7", ValueKind.INTEGER, RearTouchDevice.DEFAULT_ADDRESS7.toLong(), 8.0, 119.0, live = false, description = "7-bit I2C slave address (0x55 = 85)"),
                Setting("irqLine", ValueKind.INTEGER, RearTouchDevice.DEFAULT_IRQ_LINE.toLong(), 32.0, 1019.0, live = false, description = "GIC SPI IRQ line for touch/boot-ready interrupt (GFX100S DAT: 153 / EXTINT #2)"),
                Setting("resetGpioBank", ValueKind.INTEGER, RearTouchDevice.DEFAULT_RESET_GPIO_BANK.toLong(), 0.0, 15.0, live = false, description = "GPIO output bank for active-HIGH reset (GFX100S DAT: bank 7)"),
                Setting("resetGpioPin", ValueKind.INTEGER, RearTouchDevice.DEFAULT_RESET_GPIO_PIN.toLong(), 0.0, 15.0, live = false, description = "GPIO output pin for active-HIGH reset (GFX100S DAT: pin 2)"),
                Setting("touchEnabled", ValueKind.BOOLEAN, true, live = true, description = "Enable active rear touchscreen input handling (true=enabled, false=detached/idle)"),
                Setting("touchPressed", ValueKind.BOOLEAN, false, live = true, description = "Simulated primary finger contact state on rear touchscreen"),
                Setting("touchX", ValueKind.INTEGER, 0L, 0.0, RearTouchDevice.MAX_COORD.toDouble(), live = true, description = "Simulated primary finger X coordinate (0..65535)"),
                Setting("touchY", ValueKind.INTEGER, 0L, 0.0, RearTouchDevice.MAX_COORD.toDouble(), live = true, description = "Simulated primary finger Y coordinate (0..65535)"),
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
    ) : ScalarComponent("rear-touch-ic", context, config), StatefulComponent, RearTouchPort {
        private val enabled: Boolean = config.getValue("enabled") as Boolean
        private val busPortName: String = config.getValue("busPort") as String
        private val address7: Int = (config.getValue("address7") as Number).toInt()
        private val irqLine: Int = (config.getValue("irqLine") as Number).toInt()
        private val resetGpioBank: Int = (config.getValue("resetGpioBank") as Number).toInt()
        private val resetGpioPin: Int = (config.getValue("resetGpioPin") as Number).toInt()

        private var attachedBus: I2cBusPort? = null
        private var slaveAttached: Boolean = false
        private var closed: Boolean = false

        private val device: RearTouchDevice = RearTouchDevice(
            address7 = address7,
            irqLine = irqLine,
            resetGpioBank = resetGpioBank,
            resetGpioPin = resetGpioPin,
            irqSinkProvider = { context.ports.find("soc-gic.lines", InterruptSink::class.java) },
            gpioOutputProvider = { context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java) },
        )

        init {
            val initialPressed = config.getValue("touchPressed") as Boolean
            val initialX = (config.getValue("touchX") as Number).toInt()
            val initialY = (config.getValue("touchY") as Number).toInt()
            if (enabled && (config.getValue("touchEnabled") as Boolean) && (initialPressed || initialX != 0 || initialY != 0)) {
                device.setSingleTouch(initialPressed, initialX, initialY)
            }
            if (enabled) {
                context.ports.register(id, RearTouchPort.PORT_NAME, RearTouchPort::class.java, this)
            }
        }

        override val touchEnabled: Boolean
            get() = enabled && (values.getValue("touchEnabled") as Boolean)

        override fun updateSingleTouch(enabled: Boolean, pressed: Boolean, x: Int, y: Int) {
            check(!closed) { "rear-touch-ic closed" }
            applySettings(
                mapOf(
                    "touchEnabled" to enabled,
                    "touchPressed" to pressed,
                    "touchX" to x.toLong(),
                    "touchY" to y.toLong(),
                ),
            )
        }

        override fun interruptRoutes(): List<InterruptRoute> =
            if (enabled) {
                listOf(InterruptRoute("soc-gic.lines", irqLine..irqLine))
            } else {
                emptyList()
            }

        private fun syncBusAttachment() {
            if (!enabled || closed) return
            val bus = attachedBus ?: return
            val active = values.getValue("touchEnabled") as Boolean
            if (active) {
                if (!slaveAttached) {
                    bus.registerSlave(device)
                    slaveAttached = true
                    device.attachResetListener()
                }
            } else {
                if (slaveAttached) {
                    device.detachResetListener()
                    device.releaseAndClearIrq()
                    bus.unregisterSlave(address7, device)
                    slaveAttached = false
                }
            }
        }

        override fun start() {
            check(!closed) { "rear-touch-ic closed" }
            if (!enabled) return
            attachedBus = context.ports.find(busPortName, I2cBusPort::class.java)
            syncBusAttachment()
        }

        override fun applySettings(settings: Map<String, Any>) {
            check(!closed) { "rear-touch-ic closed" }
            super.applySettings(settings)
            if (!enabled) return
            if ("touchEnabled" in settings || "touchPressed" in settings || "touchX" in settings || "touchY" in settings) {
                val active = values.getValue("touchEnabled") as Boolean
                syncBusAttachment()
                if (!active) {
                    device.releaseAndClearIrq()
                } else {
                    val pressed = values.getValue("touchPressed") as Boolean
                    val x = (values.getValue("touchX") as Number).toInt()
                    val y = (values.getValue("touchY") as Number).toInt()
                    device.setSingleTouch(pressed, x, y)
                }
            }
        }

        override fun close() {
            if (!closed) {
                device.detachResetListener()
                device.releaseAndClearIrq()
                if (slaveAttached) {
                    attachedBus?.unregisterSlave(address7, device)
                    slaveAttached = false
                }
                attachedBus = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "busAttached" to slaveAttached,
            "device" to device.state(),
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "rear-touch-ic closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "device" to device.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "rear-touch-ic closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "device")) {
                "Unexpected or missing keys in rear-touch-ic snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported rear-touch-ic snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsedDevice = device.parseSnapshot(data["device"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("enabled", "busPort", "address7", "irqLine", "resetGpioBank", "resetGpioPin"),
            )
            device.applySnapshot(parsedDevice)
            syncBusAttachment()
        }
    }
}
