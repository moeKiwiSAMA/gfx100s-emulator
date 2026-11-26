package com.github.nyabanban.fuji.emulator.components.temperaturesensor

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class TemperatureSensorSnapshotTest {
    private class SensorTestHarness {
        lateinit var samplePort: TemperatureSamplePort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-temperature-snapshot-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("temperature-sensor", owner)
                    assertEquals("temperature-sensor", name)
                    assertEquals<Class<*>>(TemperatureSamplePort::class.java, type)
                    samplePort = port as TemperatureSamplePort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = error("unexpected lookup")
                override fun removeOwner(owner: String) = error("unexpected remove")
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTripMap(map: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTripValue(map) as Map<String, Any?>

    private fun simulateJsonRoundTripValue(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTripValue(element) }
        is Map<*, *> -> value.entries.associate { entry -> (entry.key as String) to simulateJsonRoundTripValue(entry.value) }
        else -> error("Unsupported non-JSON value: ${value::class.java.name}")
    }

    @Test
    fun roundTripPreservesUnsigned12SampleAbsentSentinelAndContinuation() {
        val factory = TemperatureSensorFactory()
        val harnessOne = SensorTestHarness()
        val sensorOne = factory.create(harnessOne.context, mapOf("rawSample" to -1L)) as StatefulComponent
        sensorOne.applySettings(mapOf("rawSample" to 3996L))

        val snapshot = simulateJsonRoundTripMap(sensorOne.saveSnapshot())

        val harnessTwo = SensorTestHarness()
        val sensorTwo = factory.create(harnessTwo.context, mapOf("rawSample" to -1L)) as StatefulComponent
        sensorTwo.restoreSnapshot(snapshot)

        assertEquals(3996, harnessTwo.samplePort.sampleRaw())
        assertEquals(-100, sensorTwo.state()["signed12"])
        assertEquals(sensorOne.state(), sensorTwo.state())

        sensorOne.applySettings(mapOf("rawSample" to -1L))
        sensorTwo.applySettings(mapOf("rawSample" to -1L))
        assertEquals(-1, harnessOne.samplePort.sampleRaw())
        assertEquals(-1, harnessTwo.samplePort.sampleRaw())
        assertEquals(sensorOne.state(), sensorTwo.state())
        assertEquals(sensorOne.saveSnapshot(), sensorTwo.saveSnapshot())
    }

    @Test
    fun closedSensorAndInvalidSnapshotsRejectAtomically() {
        val factory = TemperatureSensorFactory()
        val harness = SensorTestHarness()
        val sensor = factory.create(harness.context, mapOf("rawSample" to 512L)) as StatefulComponent
        val baselineState = sensor.state()
        val validSnapshot = simulateJsonRoundTripMap(sensor.saveSnapshot())

        val invalidCandidates = listOf<Map<String, Any?>>(
            validSnapshot + ("schema" to 2),
            validSnapshot + ("schema" to 1.5),
            validSnapshot + ("componentId" to "battery"),
            validSnapshot + ("extraKey" to 0),
            validSnapshot + ("scalarValues" to mapOf("rawSample" to -2)),
            validSnapshot + ("scalarValues" to mapOf("rawSample" to 4096)),
            validSnapshot + ("scalarValues" to mapOf("rawSample" to 200.5)),
            validSnapshot + ("scalarValues" to mapOf("rawSample" to "25 C")),
            validSnapshot + ("scalarValues" to mapOf("unknown" to 1)),
        )
        for (candidate in invalidCandidates) {
            assertFails { sensor.restoreSnapshot(candidate) }
            assertEquals(baselineState, sensor.state())
            assertEquals(512, harness.samplePort.sampleRaw())
        }

        sensor.close()
        assertFails { sensor.saveSnapshot() }
        assertFails { sensor.restoreSnapshot(validSnapshot) }
    }
}
