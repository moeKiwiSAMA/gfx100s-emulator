package com.github.nyabanban.fuji.emulator.components.temperaturesensor

import com.github.nyabanban.fuji.emulator.api.*

/** Independent live-configurable stimulus; no physical temperature calibration is asserted. */
class TemperatureSensorFactory : ComponentFactory {
    companion object {
        private val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")
    }
    override val descriptor=ComponentDescriptor("temperature-sensor","0.0.1",settings=listOf(
        Setting("rawSample",ValueKind.INTEGER,-1,minimum=-1.0,maximum=4095.0,description="Explicit unsigned12 raw sample; -1 is absent. DAT arithmetic rawSigned*125 is not physical calibration.")))
    override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=Sensor(context,config,descriptor)
    private class Sensor(
        context: ComponentContext,
        config: Map<String, Any>,
        private val descriptor: ComponentDescriptor,
    ) : ScalarComponent("temperature-sensor", context, config), TemperatureSamplePort, StatefulComponent {
        private var closed=false
        init {context.ports.register(id,id,TemperatureSamplePort::class.java,this)}
        override fun sampleRaw():Int {check(!closed){"temperature sample endpoint closed"};return (values.getValue("rawSample")as Number).toInt()}
        override fun close(){closed=true}
        override fun state():Map<String,Any?> {val raw=(values.getValue("rawSample")as Number).toInt();return super.state()+mapOf("closed" to closed,"samplePresent" to(raw>=0),"signed12" to raw.takeIf{it>=0}?.let{(it shl 20)shr 20},"basis" to "Explicit live raw input; absent by default. No physical calibration, analog electronics, power/battery or IRQ claim")}
        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "temperature sample endpoint closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
            )
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "temperature sample endpoint closed" }
            require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
            restoreScalarValues(data["scalarValues"], descriptor)
        }
        private fun exactInt(value: Any?, field: String): Int {
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
