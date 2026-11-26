package com.github.nyabanban.fuji.emulator.components.spiinput

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SpiInputSnapshotTest {
    private class TestContext(private val inputDocument: Map<String, Any?> = emptyMap()) : ComponentContext {
        var currentTick = 0L
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-spi-input-snapshot")
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(type.isInstance(port) && registeredPorts.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                registeredPorts.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("Unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> {
            assertEquals("pins.yaml", relative)
            return inputDocument
        }
    }

    private fun defaultPinDocument(): Map<String, Any?> = mapOf(
        "pins" to listOf(
            mapOf("id" to "panel-a", "miso" to true),
            mapOf("id" to "sensor-b", "miso" to false),
        ),
    )

    private fun createComponent(context: TestContext, enabled: Boolean = true): StatefulComponent {
        val factory = SpiInputFactory()
        val settings = if (enabled) {
            mapOf("profile" to "spi-fixed-pins-experimental-v1", "inputFile" to "pins.yaml")
        } else {
            mapOf("profile" to "disabled", "inputFile" to "")
        }
        val component = factory.create(context, settings)
        component.start()
        return assertIs<StatefulComponent>(component)
    }

    private fun simulateJsonRoundTrip(snapshot: Map<String, Any?>): Map<String, Any?> {
        val jsonText = encodeJsonValue(snapshot)
        val parser = JsonReader(jsonText)
        val parsed = parser.parseValue()
        parser.expectEnd()
        @Suppress("UNCHECKED_CAST")
        return parsed as Map<String, Any?>
    }

    private fun encodeJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().toString()
        is String -> {
            require(value.length <= 65536) { "JSON string exceeds 64 KiB limit" }
            buildString {
                append('"')
                for (character in value) {
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(character)
                    }
                }
                append('"')
            }
        }
        is List<*> -> value.joinToString(",", "[", "]") { element -> encodeJsonValue(element) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { entry ->
            val key = entry.key as? String ?: error("JSON object keys must be String")
            "${encodeJsonValue(key)}:${encodeJsonValue(entry.value)}"
        }
        else -> error("Unsupported non-JSON snapshot value: ${value::class.java.name}")
    }

    private class JsonReader(private val source: String) {
        private var position = 0

        private fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        fun expectEnd() {
            skipWhitespace()
            require(position == source.length) { "Unexpected trailing JSON text" }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(position < source.length) { "Unexpected end of JSON" }
            return when (source[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            position++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (position < source.length && source[position] == '}') {
                position++
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                require(source[position++] == ':')
                result[key] = parseValue()
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    '}' -> return result
                    else -> error("Malformed JSON object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            position++
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (position < source.length && source[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    ']' -> return result
                    else -> error("Malformed JSON array")
                }
            }
        }

        private fun parseString(): String {
            require(source[position++] == '"')
            val builder = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                if (character == '"') return builder.toString()
                if (character == '\\') {
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        else -> error("Unsupported JSON escape: $escaped")
                    }
                } else {
                    builder.append(character)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseBoolean(): Boolean = if (source.startsWith("true", position)) {
            position += 4
            true
        } else if (source.startsWith("false", position)) {
            position += 5
            false
        } else error("Invalid JSON boolean")

        private fun parseNull(): Any? {
            require(source.startsWith("null", position))
            position += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = position
            if (source[position] == '-') position++
            while (position < source.length && source[position].isDigit()) position++
            val numberValue = source.substring(start, position).toLong()
            return if (numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numberValue.toInt() else numberValue
        }
    }

    @Test
    fun snapshotRoundTripPreservesStateAndContinuationEquivalenceForEnabledAndDisabledProfiles() {
        val directContext = TestContext(defaultPinDocument())
        val directComponent = createComponent(directContext, enabled = true)
        val directHighPeer = checkNotNull(directContext.ports.find("spi-input.panel-a", SpiPeer::class.java))
        val directLowPeer = checkNotNull(directContext.ports.find("spi-input.sensor-b", SpiPeer::class.java))

        directContext.currentTick = 12L
        assertEquals(0xff, directHighPeer.exchange(8, 0x5a, 12L))
        assertEquals(0, directLowPeer.exchange(8, 0x5a, 12L))

        val roundTripped = simulateJsonRoundTrip(directComponent.saveSnapshot())

        directContext.currentTick = 25L
        val directWord16 = directHighPeer.exchange(16, 0x1234, 25L)
        val directWord4 = directLowPeer.exchange(4, 0x0a, 25L)
        val directFinalState = directComponent.state()

        val restoredContext = TestContext(defaultPinDocument())
        val restoredComponent = createComponent(restoredContext, enabled = true)
        restoredComponent.restoreSnapshot(roundTripped)
        val restoredHighPeer = checkNotNull(restoredContext.ports.find("spi-input.panel-a", SpiPeer::class.java))
        val restoredLowPeer = checkNotNull(restoredContext.ports.find("spi-input.sensor-b", SpiPeer::class.java))

        restoredContext.currentTick = 25L
        assertEquals(directWord16, restoredHighPeer.exchange(16, 0x1234, 25L))
        assertEquals(directWord4, restoredLowPeer.exchange(4, 0x0a, 25L))
        assertEquals(directFinalState, restoredComponent.state())

        val disabledDirect = createComponent(TestContext(), enabled = false)
        val disabledSnap = simulateJsonRoundTrip(disabledDirect.saveSnapshot())
        val disabledRestored = createComponent(TestContext(), enabled = false)
        disabledRestored.restoreSnapshot(disabledSnap)
        assertEquals(disabledDirect.state(), disabledRestored.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingStateOrPeers() {
        val context = TestContext(defaultPinDocument())
        val component = createComponent(context, enabled = true)
        val peer = checkNotNull(context.ports.find("spi-input.panel-a", SpiPeer::class.java))
        val baselineState = component.state()
        val valid = simulateJsonRoundTrip(component.saveSnapshot())

        val invalidSnapshots = listOf(
            valid - "schema",
            valid + ("extra" to 1),
            valid + ("schema" to 2),
            valid + ("schema" to 1.0),
            valid + ("schema" to "1"),
            valid + ("componentId" to "soc-spi"),
            valid + ("scalarValues" to mapOf("profile" to "disabled", "inputFile" to "")),
            valid + ("scalarValues" to mapOf("profile" to "spi-fixed-pins-experimental-v1", "inputFile" to null)),
            valid + ("pins" to emptyList<Any>()),
            valid + ("pins" to listOf(
                mapOf("id" to "panel-a", "miso" to false),
                mapOf("id" to "sensor-b", "miso" to false),
            )),
            valid + ("pins" to listOf(
                mapOf("id" to "panel-a", "miso" to true),
                mapOf("id" to "panel-a", "miso" to false),
            )),
        )

        for (candidate in invalidSnapshots) {
            assertFailsWith<IllegalArgumentException> {
                component.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, component.state())
            assertEquals(0xfff, peer.exchange(12, 0x123, 5L))
        }
    }
}
