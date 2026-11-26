package com.github.nyabanban.fuji.emulator.components.battery

import com.github.nyabanban.fuji.emulator.api.*

class BatteryFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor("battery", "0.0.1", settings = listOf(
        Setting("chargePercent", ValueKind.DECIMAL, 100.0, 0.0, 100.0),
        Setting("voltageMillivolts", ValueKind.INTEGER, 7400L, 0.0, 12000.0),
        Setting("present", ValueKind.BOOLEAN, true),
    ))
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = object : ScalarComponent("battery", context, config), BatteryPort, StatefulComponent {
        init { context.ports.register(id, "battery", BatteryPort::class.java, this) }
        override fun chargePercent() = (values.getValue("chargePercent") as Number).toDouble()
        override fun voltageMillivolts() = (values.getValue("voltageMillivolts") as Number).toInt()
        override fun present() = values.getValue("present") as Boolean
        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalarValues" to saveScalarValues(),
        )
        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
            restoreScalarValues(data["scalarValues"], descriptor)
        }
    }

    private companion object {
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")

        fun exactInt(value: Any?, field: String): Int {
            val number: Long = when (value) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                is java.math.BigInteger -> {
                    require(value.bitLength() <= 63) { "$field out of Long range" }
                    value.toLong()
                }
                is java.math.BigDecimal -> try {
                    value.longValueExact()
                } catch (exception: ArithmeticException) {
                    throw IllegalArgumentException("$field requires exact integer", exception)
                }
                is Number -> {
                    val decimal = value.toDouble()
                    require(
                        decimal.isFinite() &&
                            decimal % 1.0 == 0.0 &&
                            decimal >= Long.MIN_VALUE.toDouble() &&
                            decimal < 9223372036854775808.0
                    ) { "$field requires exact integer" }
                    decimal.toLong()
                }
                else -> throw IllegalArgumentException("$field requires integer")
            }
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
