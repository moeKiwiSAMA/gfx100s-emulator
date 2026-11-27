package com.github.nyabanban.fuji.emulator.components.wm8962

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone **Wolfson / Cirrus Logic `WM8962`** Audio Codec IC component.
 *
 * Exposes a single shared datasheet-faithful WM8962 register & DSP2 RAM silicon model via:
 * - I2C1 slave (`0x1A`) attached to `i2c-bus.bus1`,
 * - SPI4 peer (`wm8962.spi`, [SpiPeer]) for GFX100S 2.13 SPI4 codec control,
 * - Audio codec routing port (`wm8962.codec`, [AudioCodecControlPort]) for host audio playback/capture.
 */
class Wm8962Factory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, normalizeConfig(config))

    companion object {
        const val DEFAULT_BUS_PORT = "i2c-bus.bus1"

        private val DESCRIPTOR = ComponentDescriptor(
            id = "wm8962",
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false, description = "Enable standalone Wolfson WM8962 audio codec IC (I2C1 0x1A + SPI4 wm8962.spi)"),
                Setting("busPort", ValueKind.TEXT, DEFAULT_BUS_PORT, live = false, description = "Target I2C bus port name (default i2c-bus.bus1)"),
                Setting("address7", ValueKind.INTEGER, Wm8962CodecDevice.DEFAULT_ADDRESS7.toLong(), 8.0, 119.0, live = false, description = "7-bit I2C slave address (0x1A = 26)"),
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
    ) : ScalarComponent("wm8962", context, config), StatefulComponent {
        private val enabled: Boolean = config.getValue("enabled") as Boolean
        private val busPortName: String = config.getValue("busPort") as String
        private val address7: Int = (config.getValue("address7") as Number).toInt()

        private var attachedBus: I2cBusPort? = null
        private var closed: Boolean = false

        private val device = Wm8962CodecDevice(address7 = address7) { context.clock.ticks() }

        init {
            if (enabled) {
                context.ports.register(id, Wm8962CodecDevice.SPI_PORT_NAME, SpiPeer::class.java, device)
                context.ports.register(id, Wm8962CodecDevice.CODEC_PORT_NAME, AudioCodecControlPort::class.java, device)
            }
        }

        override fun start() {
            check(!closed) { "wm8962 closed" }
            if (!enabled) return
            val bus = context.ports.find(busPortName, I2cBusPort::class.java)
            if (bus != null) {
                bus.registerSlave(device)
                attachedBus = bus
            }
        }

        override fun close() {
            if (!closed) {
                attachedBus?.unregisterSlave(address7, device)
                attachedBus = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "busAttached" to (attachedBus != null),
            "spiPort" to if (enabled) Wm8962CodecDevice.SPI_PORT_NAME else null,
            "codecPort" to if (enabled) Wm8962CodecDevice.CODEC_PORT_NAME else null,
            "device" to device.state(),
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "wm8962 closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "device" to device.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "wm8962 closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "device")) {
                "Unexpected or missing keys in wm8962 snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported wm8962 snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsed = device.parseSnapshot(data["device"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("enabled", "busPort", "address7"),
            )
            device.applySnapshot(parsed)
        }
    }
}
