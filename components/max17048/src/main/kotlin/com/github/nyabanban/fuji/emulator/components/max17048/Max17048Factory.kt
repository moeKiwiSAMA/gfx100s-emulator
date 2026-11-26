package com.github.nyabanban.fuji.emulator.components.max17048

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone **Maxim Integrated (ADI) `MAX17048` / `MAX17049`** ModelGauge Li-Ion fuel gauge IC.
 *
 * Attaches to `i2c-bus.bus1` at 7-bit address `0x36` and observes `"battery"` ([BatteryPort]) for
 * real-time `VCELL` (`0x02`), `SOC` (`0x04`), and `OCV` (`0x0E`) reporting.
 */
class Max17048Factory : ComponentFactory {
    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, normalizeConfig(config))

    companion object {
        const val DEFAULT_BUS_PORT = "i2c-bus.bus1"

        private val DESCRIPTOR = ComponentDescriptor(
            id = "max17048",
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false, description = "Attach standalone Maxim MAX17048/49 fuel gauge IC to I2C1 @ 0x36"),
                Setting("busPort", ValueKind.TEXT, DEFAULT_BUS_PORT, live = false, description = "Target I2C bus port name (default i2c-bus.bus1)"),
                Setting("address7", ValueKind.INTEGER, Max17048Device.DEFAULT_ADDRESS7.toLong(), 8.0, 119.0, live = false, description = "7-bit I2C slave address (0x36 = 54)"),
                Setting("cells", ValueKind.INTEGER, Max17048Device.DEFAULT_CELLS.toLong(), 1.0, 2.0, live = false, description = "Series Li-Ion cell count (1=MAX17048, 2=MAX17049 for NP-W235)"),
                Setting("versionId", ValueKind.INTEGER, Max17048Device.DEFAULT_VERSION_ID.toLong(), 0.0, 65535.0, live = false, description = "16-bit VERSION register value (0x08)"),
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
    ) : ScalarComponent("max17048", context, config), StatefulComponent {
        private val enabled: Boolean = config.getValue("enabled") as Boolean
        private val busPortName: String = config.getValue("busPort") as String
        private val address7: Int = (config.getValue("address7") as Number).toInt()
        private val cells: Int = (config.getValue("cells") as Number).toInt()
        private val versionId: Int = (config.getValue("versionId") as Number).toInt()

        private var attachedBus: I2cBusPort? = null
        private var batteryPort: BatteryPort? = null
        private var closed: Boolean = false

        private val device = Max17048Device(
            address7 = address7,
            cells = cells,
            versionId = versionId,
            batteryProvider = { batteryPort },
        )

        override fun start() {
            check(!closed) { "max17048 closed" }
            if (!enabled) return
            batteryPort = context.ports.find("battery", BatteryPort::class.java)
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
                batteryPort = null
                closed = true
            }
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "busAttached" to (attachedBus != null),
            "batteryObserved" to (batteryPort != null),
            "device" to device.state(),
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "max17048 closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "device" to device.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "max17048 closed" }
            require(data.keys == setOf("schema", "componentId", "scalars", "device")) {
                "Unexpected or missing keys in max17048 snapshot"
            }
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported max17048 snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalars for $id")
            val parsed = device.parseSnapshot(data["device"])
            restoreScalarValues(
                scalarsRaw,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("enabled", "busPort", "address7", "cells", "versionId"),
            )
            device.applySnapshot(parsed)
        }
    }
}
