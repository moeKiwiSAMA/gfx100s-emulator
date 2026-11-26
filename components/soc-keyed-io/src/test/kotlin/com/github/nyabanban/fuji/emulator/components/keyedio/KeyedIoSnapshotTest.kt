package com.github.nyabanban.fuji.emulator.components.keyedio

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class KeyedIoSnapshotTest {
    private class TestContext : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No keyed-io event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private class Harness {
        val context = TestContext()
        val factory = KeyedIoFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        val component = factory.create(context, config) as StatefulComponent
        val mmio: MmioDevice = context.ports.find("soc-keyed-io", MmioDevice::class.java)!!
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
    fun snapshotRoundTripPreservesRegistersAndSubsequentOperationsAcrossT1ToT2() {
        val continuous = Harness()
        fun runPrefixToT1(target: Harness) {
            target.mmio.write(0x20, 4, 0x800000ffL)
            target.mmio.write(0x40, 4, 0x0000000fL)
            target.mmio.write(0x00, 4, 0x8000aa01L)
            for (slot in 0 until 8) {
                target.mmio.write(0x80L + slot * 4L, 4, 0x01020304L + slot)
            }
            target.mmio.write(0x20, 4, 0x00ff0000L)
            target.mmio.write(0x40, 4, 0x00000030L)
        }
        fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            observations += target.mmio.read(0x00, 4)
            observations += target.mmio.read(0x0c, 4)
            observations += target.mmio.read(0x18, 4)
            observations += target.mmio.read(0x8c, 4)
            target.mmio.write(0x00, 4, 0x0000aa02L)
            observations += target.mmio.read(0x0c, 4)
            observations += target.mmio.read(0x18, 4)
            target.mmio.write(0x10, 4, 0x00000010L)
            target.mmio.write(0x9c, 4, 0x1f1e1d1cL)
            observations += target.mmio.read(0x0c, 4)
            observations += target.mmio.read(0x9c, 4)
            return observations
        }

        runPrefixToT1(continuous)
        val savedAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val resumed = Harness()
        resumed.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, resumed.component.state())

        val expectedObservations = runContinuationToT2(continuous)
        val actualObservations = runContinuationToT2(resumed)
        assertEquals(expectedObservations, actualObservations)
        assertEquals(continuous.component.state(), resumed.component.state())

        val clean = Harness()
        val savedClean = roundTripMap(clean.component.saveSnapshot())
        resumed.component.restoreSnapshot(savedClean)
        assertEquals(clean.component.state(), resumed.component.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness()
        live.mmio.write(0x20, 4, 0x0fL)
        live.mmio.write(0x00, 4, 0xaa01L)
        live.mmio.write(0x80, 4, 0x11121314L)
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 2) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badCommand = LinkedHashMap(validSnapshot).apply { put("command", 0xaa03) }
        val badStatus = LinkedHashMap(validSnapshot).apply { put("status", 0x40) }
        val badRoute = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val routes = (get("routes") as List<Any?>).toMutableList()
            routes[0] = 0x20000000
            put("routes", routes)
        }
        val activeAtReset = LinkedHashMap(validSnapshot).apply {
            put("command", 0)
            put("status", 0)
            put("active", 1)
        }

        for (corrupted in listOf(badSchema, badComponent, badCommand, badStatus, badRoute, activeAtReset)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }
    }
}
