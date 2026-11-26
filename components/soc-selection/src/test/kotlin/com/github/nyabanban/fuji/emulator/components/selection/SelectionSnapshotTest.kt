package com.github.nyabanban.fuji.emulator.components.selection

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class SelectionSnapshotTest {
    private class TestContext : ComponentContext {
        var tick = 0L
        var currentLocation: ExecutionLocation? = ExecutionLocation(0, 0x80002000L)
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? {
                if (name == "cpu.execution-location" && type == ExecutionLocationSource::class.java) {
                    return currentLocation?.let { location -> type.cast(ExecutionLocationSource { location }) }
                }
                return claims[name]?.let(type::cast)
            }
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No selection event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to SelectionFactory.PROFILE,
            "selectionReset" to 0x01040411L,
            "special52Reset" to 0x102L,
            "special53Reset" to 0x008L,
        )
    }

    private class Harness(enabled: Boolean = true) {
        val context = TestContext()
        val factory = SelectionFactory()
        val config: Map<String, Any> = if (enabled) {
            mapOf("profile" to SelectionFactory.PROFILE, "inputFile" to "selection.yaml")
        } else {
            mapOf("profile" to "disabled", "inputFile" to "")
        }
        val component = factory.create(context, config) as StatefulComponent
        fun port(suffix: String): MmioDevice =
            context.ports.find("soc-selection.$suffix", MmioDevice::class.java)!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTrip(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int, is Long -> {
            val numeric = (value as Number).toLong()
            if (numeric in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numeric.toInt() else numeric
        }
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTrip(element) }
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>()
            for ((rawKey, rawElement) in value) {
                check(rawKey is String) { "JSON object keys must be strings" }
                copy[rawKey] = simulateJsonRoundTrip(rawElement)
            }
            copy
        }
        else -> error("Unsupported non-JSON value type: ${value::class.java.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripMap(snapshot: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTrip(snapshot) as Map<String, Any?>

    @Test
    fun snapshotRoundTripPreservesRetainedWordsAndWrappedDiagnosticsAcrossT1ToT2() {
        val continuous = Harness()
        fun runPrefixToT1(target: Harness) {
            repeat(530) { stepIndex ->
                target.context.tick = stepIndex.toLong()
                target.context.currentLocation = if (stepIndex % 3 == 0) null else ExecutionLocation(stepIndex % 4, 0x1000L + stepIndex * 4L)
                when (stepIndex % 3) {
                    0 -> target.port("selection").write(0, 4, if (stepIndex % 2 == 0) 0x01020824L else 0x00010142L)
                    1 -> target.port("special52").write(0, 4, 0x100L + (2 + (stepIndex % 200)))
                    else -> target.port("special53").read(0, 4)
                }
            }
            target.context.tick = 600L
            target.port("special53").write(0, 4, 0x10fL)
        }
        fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            target.context.tick = 610L
            observations += target.port("selection").read(0, 4)
            observations += target.port("special52").read(0, 4)
            observations += target.port("special53").read(0, 4)
            target.context.tick = 620L
            target.port("selection").write(0, 4, 0x00000007L)
            target.port("special52").write(0, 4, 0x0ffL)
            target.port("special53").write(0, 4, 0x010L)
            observations += target.port("selection").read(0, 4)
            observations += target.port("special52").read(0, 4)
            observations += target.port("special53").read(0, 4)
            return observations
        }

        runPrefixToT1(continuous)
        val savedAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val resumed = Harness()
        resumed.context.tick = 600L
        resumed.context.currentLocation = continuous.context.currentLocation
        resumed.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, resumed.component.state())

        val expectedObservations = runContinuationToT2(continuous)
        val actualObservations = runContinuationToT2(resumed)
        assertEquals(expectedObservations, actualObservations)
        assertEquals(continuous.component.state(), resumed.component.state())

        val disabled = Harness(enabled = false)
        val savedDisabled = roundTripMap(disabled.component.saveSnapshot())
        val restoredDisabled = Harness(enabled = false)
        restoredDisabled.component.restoreSnapshot(savedDisabled)
        assertEquals(disabled.component.state(), restoredDisabled.component.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness()
        live.context.tick = 15L
        live.port("selection").write(0, 4, 0x01020824L)
        live.port("special52").write(0, 4, 0x07fL)
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 0) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badSelectionWord = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val words = LinkedHashMap(get("words") as Map<String, Any?>)
            words["selection"] = 0x00000c00L
            put("words", words)
        }
        val badSpecial52Word = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val words = LinkedHashMap(get("words") as Map<String, Any?>)
            words["special52"] = 1
            put("words", words)
        }
        val mismatchedCounts = LinkedHashMap(validSnapshot).apply {
            put("readCount", 5)
        }

        for (corrupted in listOf(badSchema, badComponent, badSelectionWord, badSpecial52Word, mismatchedCounts)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }
    }
}
