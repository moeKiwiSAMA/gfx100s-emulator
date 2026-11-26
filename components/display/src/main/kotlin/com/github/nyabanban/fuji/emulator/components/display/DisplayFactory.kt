package com.github.nyabanban.fuji.emulator.components.display

import com.github.nyabanban.fuji.emulator.api.*

class DisplayFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor("display", "0.0.1", settings = listOf(
        Setting("width", ValueKind.INTEGER, 1024L, 1.0, 4096.0, live = false),
        Setting("height", ValueKind.INTEGER, 768L, 1.0, 2160.0, live = false),
        Setting("evfWidth", ValueKind.INTEGER, 1280L, 1.0, 4096.0, live = false),
        Setting("evfHeight", ValueKind.INTEGER, 960L, 1.0, 2160.0, live = false),
        Setting("brightness", ValueKind.DECIMAL, 1.0, 0.0, 1.0),
    ))
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        val defaults = descriptor.settings.associate { setting -> setting.key to setting.default }
        val normalizedConfig = (defaults + config).mapValues { (key, value) ->
            descriptor.settings.firstOrNull { setting -> setting.key == key }?.validate(value) ?: value
        }
        return object : ScalarComponent("display", context, normalizedConfig), StatefulComponent {
            private val main = ArgbFrameBuffer(
                (normalizedConfig.getValue("width") as Number).toInt(),
                (normalizedConfig.getValue("height") as Number).toInt(),
            )
            private val evf = ArgbFrameBuffer(
                (normalizedConfig.getValue("evfWidth") as Number).toInt(),
                (normalizedConfig.getValue("evfHeight") as Number).toInt(),
            )
            init {
                context.ports.register(id, "display.main", DisplayPort::class.java, main)
                context.ports.register(id, "display.evf", DisplayPort::class.java, evf)
            }
            override fun state() = super.state() + mapOf(
                "mainSequence" to main.sequence,
                "evfSequence" to evf.sequence,
                "mainContentSequence" to main.contentSequence,
                "evfContentSequence" to evf.contentSequence,
                "mainSkippedIdenticalFrames" to main.skippedIdenticalFrames,
                "evfSkippedIdenticalFrames" to evf.skippedIdenticalFrames,
                "firmwareDisplayBinding" to "unqualified",
            )
            override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
                "main" to main.saveSnapshot(),
                "evf" to evf.saveSnapshot(),
            )
            override fun restoreSnapshot(data: Map<String, Any?>) {
                require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
                require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
                require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
                val mainMap = validateFrameSnapshot(data["main"], main.width, main.height, "main")
                val evfMap = if (data.containsKey("evf")) {
                    validateFrameSnapshot(data["evf"], evf.width, evf.height, "evf")
                } else {
                    ArgbFrameBuffer(evf.width, evf.height).saveSnapshot()
                }
                restoreScalarValues(data["scalarValues"], descriptor, compatibleNonLiveKeys = setOf("profile"))
                main.restoreSnapshot(mainMap)
                evf.restoreSnapshot(evfMap)
            }
        }
    }

    private companion object {
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues", "main", "evf")
        val FRAME_KEYS = setOf("schema", "width", "height", "sequence", "pixelsBase64")

        fun validateFrameSnapshot(rawFrame: Any?, expectedWidth: Int, expectedHeight: Int, label: String = "main"): Map<*, *> {
            val frameMap = rawFrame as? Map<*, *> ?: error("Missing display $label framebuffer snapshot")
            require(frameMap.keys.all { key -> key in FRAME_KEYS }) { "Unexpected key in display $label framebuffer snapshot" }
            require(exactInt(frameMap["schema"], "$label.schema") == 1) { "Unsupported ArgbFrameBuffer snapshot schema" }
            require(
                exactInt(frameMap["width"], "$label.width") == expectedWidth &&
                    exactInt(frameMap["height"], "$label.height") == expectedHeight
            ) { "ArgbFrameBuffer dimensions differ" }
            val sequence = exactLong(frameMap["sequence"], "$label.sequence")
            require(sequence >= 0L) { "Negative ArgbFrameBuffer sequence" }
            val rawPixels = frameMap["pixelsBase64"]
            require(rawPixels == null || rawPixels is String) { "Invalid ArgbFrameBuffer pixelsBase64" }
            val stagedFrame = ArgbFrameBuffer(expectedWidth, expectedHeight)
            stagedFrame.restoreSnapshot(frameMap)
            return frameMap
        }

        fun exactLong(value: Any?, field: String): Long = when (value) {
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

        fun exactInt(value: Any?, field: String): Int {
            val number = exactLong(value, field)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
