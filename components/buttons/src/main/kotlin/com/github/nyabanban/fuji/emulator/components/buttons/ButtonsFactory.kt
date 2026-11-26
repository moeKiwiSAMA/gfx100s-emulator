package com.github.nyabanban.fuji.emulator.components.buttons

import com.github.nyabanban.fuji.emulator.api.*

class ButtonsFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor("buttons", "0.0.1", settings = BUTTON_KEYS.map {
        Setting(it, ValueKind.BOOLEAN, false, description = "Logical switch; firmware electrical binding remains unqualified")
    })
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = object : ScalarComponent("buttons", context, config), ButtonPort, StatefulComponent {
        @Volatile private var pressedMask: Int = maskFromValues()

        init { context.ports.register(id, "buttons", ButtonPort::class.java, this) }

        private fun maskFromValues(): Int {
            var mask = 0
            for (i in BUTTON_KEYS.indices) {
                if (values[BUTTON_KEYS[i]] == true) mask = mask or (1 shl i)
            }
            return mask
        }

        override fun pressed(key: String): Boolean {
            val bit = keyBit(key)
            return if (bit >= 0) (pressedMask and (1 shl bit)) != 0 else values.getValue(key) as Boolean
        }

        override fun applySettings(settings: Map<String, Any>) {
            var changed: LinkedHashMap<String, Any>? = null
            var nextMask = pressedMask
            for ((k, v) in settings) {
                if (values[k] != v) {
                    if (changed == null) changed = LinkedHashMap(settings.size)
                    changed[k] = v
                }
                val bit = keyBit(k)
                if (bit >= 0) {
                    nextMask = if (v == true) (nextMask or (1 shl bit)) else (nextMask and (1 shl bit).inv())
                }
            }
            super.applySettings(settings)
            pressedMask = nextMask
            if (changed != null) context.events.publish(ComponentEvent(id, "switches", context.clock.ticks(), changed))
        }

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalarValues" to saveScalarValues(),
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
            val saved = data["scalarValues"] as? Map<*, *> ?: error("Missing buttons scalarValues")
            restoreScalarValues(
                mapOf<Any?, Any?>(
                    "delete" to false,
                    "rearDialPress" to false,
                    "movieMode" to false,
                    "drive" to false,
                    "afOn" to false,
                    "focusMode" to false,
                    "frontDialPress" to false,
                    "fn4" to false,
                    "focusStickPress" to false,
                ) + saved,
                descriptor,
            )
            pressedMask = maskFromValues()
        }
    }

    private companion object {
        val BUTTON_KEYS = listOf(
            "menu", "back", "up", "down", "left", "right",
            "shutterHalf", "shutterFull", "play", "power",
            "delete", "rearDialPress", "movieMode",
            "drive", "afOn", "focusMode",
            "frontDialPress", "fn4", "focusStickPress",
        )
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")

        fun keyBit(key: String): Int = when (key) {
            "menu" -> 0
            "back" -> 1
            "up" -> 2
            "down" -> 3
            "left" -> 4
            "right" -> 5
            "shutterHalf" -> 6
            "shutterFull" -> 7
            "play" -> 8
            "power" -> 9
            "delete" -> 10
            "rearDialPress" -> 11
            "movieMode" -> 12
            "drive" -> 13
            "afOn" -> 14
            "focusMode" -> 15
            "frontDialPress" -> 16
            "fn4" -> 17
            "focusStickPress" -> 18
            else -> -1
        }

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
