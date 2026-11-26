package com.github.nyabanban.fuji.emulator.components.temperature

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class TemperatureSnapshotTest {
    private fun buildInputData(): Map<String, Any> = mapOf(
        "schema" to 1,
        "profile" to TemperatureFactory.PROFILE,
        "conversionTicks" to 10L,
        "initialWords" to TemperatureBank.addresses.withIndex().associate { (slot, address) ->
            address.toString(16) to when (slot) {
                1 -> 0x80000000L
                5 -> 12L
                7 -> 15L
                else -> 0L
            }
        },
    )

    private class TestContext(val inputData: Map<String, Any>) : ComponentContext {
        var tick = 0L
        var rawSample = 321
        val claims = linkedMapOf<String, Any>(
            "sensor.temperature" to TemperatureSamplePort { rawSample },
        )
        override val directory: Path = Path.of("unused-temperature-snapshot-data")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No temperature event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any> = inputData
    }

    private inner class Harness(enabled: Boolean = true) {
        val context = TestContext(buildInputData())
        val factory = TemperatureFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
            "profile" to if (enabled) TemperatureFactory.PROFILE else "disabled",
            "inputFile" to if (enabled) "input.yaml" else "",
            "samplePort" to if (enabled) "sensor.temperature" else "",
        )
        val component = (factory.create(context, config) as StatefulComponent).also { instance -> instance.start() }
        fun commonControl(): MmioDevice = context.ports.find("soc-temperature.common-control", MmioDevice::class.java)!!
        fun commonGate(): MmioDevice = context.ports.find("soc-temperature.common-gate", MmioDevice::class.java)!!
        fun converter(): ScheduledMmioDevice =
            context.ports.find("soc-temperature.converter", MmioDevice::class.java) as ScheduledMmioDevice
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
    fun snapshotRoundTripPreservesInFlightConversionThresholdsAndSourceAcrossT1ToT2() {
        val continuous = Harness()
        fun runPrefixToT1(target: Harness) {
            target.context.tick = 5L
            target.commonControl().write(0, 4, 1L)
            target.commonGate().write(0, 4, 0x80000055L)
            target.converter().write(20, 4, 4L)
            target.converter().write(48, 4, (-120).toLong() and 0xffffffffL)
            target.converter().write(64, 4, 0x7aL)
            target.converter().write(8, 4, 2L)
            target.converter().write(0, 4, 1L)
            target.converter().write(4, 4, 1L)
            target.context.tick = 45L
            target.converter().beforeInstruction(45L)
            assertEquals(321L, target.converter().read(36, 4))
            target.context.tick = 50L
            target.converter().write(8, 4, 1L)
            target.converter().write(4, 4, 1L)
            target.context.tick = 60L
            target.converter().beforeInstruction(60L)
        }
        fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            target.context.rawSample = 1987
            observations += target.converter().read(32, 4)
            observations += target.converter().read(36, 4)
            target.context.tick = 70L
            target.converter().beforeInstruction(70L)
            observations += target.converter().read(32, 4)
            observations += target.converter().read(36, 4)
            observations += target.converter().read(16, 4)
            observations += target.converter().read(48, 4)
            observations += target.commonControl().read(0, 4)
            observations += target.commonGate().read(0, 4)
            return observations
        }

        runPrefixToT1(continuous)
        val savedAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val resumed = Harness()
        resumed.context.tick = 60L
        resumed.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, resumed.component.state())
        assertEquals(continuous.converter().nextDeadline(), resumed.converter().nextDeadline())

        val expectedObservations = runContinuationToT2(continuous)
        val actualObservations = runContinuationToT2(resumed)
        assertEquals(expectedObservations, actualObservations)
        assertEquals(continuous.component.state(), resumed.component.state())
        continuous.component.close()
        resumed.component.close()

        val disabled = Harness(enabled = false)
        val savedDisabled = roundTripMap(disabled.component.saveSnapshot())
        val restoredDisabled = Harness(enabled = false)
        restoredDisabled.component.restoreSnapshot(savedDisabled)
        assertEquals(disabled.component.state(), restoredDisabled.component.state())
        disabled.component.close()
        restoredDisabled.component.close()
    }

    @Test
    fun invalidOrClosedSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness()
        live.context.tick = 12L
        live.converter().write(0, 4, 1L)
        live.converter().write(4, 4, 1L)
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 3) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badIrqMask = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val registers = LinkedHashMap(get("registers") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val words = LinkedHashMap(registers["words"] as Map<String, Any?>)
            words[TemperatureBank.addresses[7].toString(16)] = 14
            registers["words"] = words
            put("registers", registers)
        }
        val readyWhilePending = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val registers = LinkedHashMap(get("registers") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val words = LinkedHashMap(registers["words"] as Map<String, Any?>)
            words[TemperatureBank.addresses[8].toString(16)] = 1
            registers["words"] = words
            put("registers", registers)
        }
        val nonCanonicalThreshold = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val registers = LinkedHashMap(get("registers") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val words = LinkedHashMap(registers["words"] as Map<String, Any?>)
            words[TemperatureBank.addresses[10].toString(16)] = 0xfffff800L
            registers["words"] = words
            put("registers", registers)
        }

        for (corrupted in listOf(badSchema, badComponent, badIrqMask, readyWhilePending, nonCanonicalThreshold)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }

        live.component.close()
        assertFailsWith<IllegalStateException> { live.component.saveSnapshot() }
        assertFailsWith<IllegalStateException> { live.component.restoreSnapshot(validSnapshot) }
    }
}
