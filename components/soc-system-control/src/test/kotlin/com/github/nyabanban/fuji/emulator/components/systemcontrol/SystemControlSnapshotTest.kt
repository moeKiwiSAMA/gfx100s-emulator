package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class SystemControlSnapshotTest {
    private class TestContext(private val frozenWord: Long = 0xa5a5f00dL) : ComponentContext {
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
            override fun publish(event: ComponentEvent) { error("No system-control event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to ReadWordInput.PROFILE,
            "value" to frozenWord,
        )
    }

    private class Harness(
        booleanWrite: Boolean = true,
        serialWrite: Boolean = true,
        readWordEnabled: Boolean = true,
        frozenWord: Long = 0xa5a5f00dL,
    ) {
        val context = TestContext(frozenWord)
        val factory = SystemControlFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
            "booleanWrite" to booleanWrite,
            "serialWrite" to serialWrite,
            "readWordInputFile" to if (readWordEnabled) "fff48004.yaml" else "",
        )
        val component = factory.create(context, config) as StatefulComponent
        fun port(suffix: String): MmioDevice =
            context.ports.find("soc-system-control.$suffix", MmioDevice::class.java)!!
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
    fun snapshotRoundTripPreservesBanksWriteInputsAndReadWordAcrossT1ToT2() {
        val continuous = Harness()
        fun runPrefixToT1(target: Harness) {
            target.port("a").write(0, 4, 5L)
            target.port("a").write(8, 4, 0x1abcdefL)
            target.port("a").write(60, 4, 0x5aaL)
            target.port("b").write(20, 4, 0x70f15L)
            target.port("b-extra").write(0, 4, 6L)
            target.port("boolean").write(0, 4, 1L)
            target.port("serial-clock").write(0, 4, 3L shl 8)
        }
        fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            observations += target.port("a").read(0, 4)
            observations += target.port("a").read(8, 4)
            observations += target.port("b").read(20, 4)
            observations += target.port("b-extra").read(0, 4)
            observations += target.port("read-word-fff48004").read(0, 4)
            target.port("a").write(12, 4, 0xa5L)
            target.port("b").write(0, 4, 3L)
            target.port("boolean").write(0, 4, 0L)
            target.port("serial-clock").write(0, 4, 2L shl 4)
            observations += target.port("a").read(12, 4)
            observations += target.port("b").read(0, 4)
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

        val minimal = Harness(booleanWrite = false, serialWrite = false, readWordEnabled = false)
        minimal.port("a").write(4, 4, 0x12345L)
        val savedMinimal = roundTripMap(minimal.component.saveSnapshot())
        val restoredMinimal = Harness(booleanWrite = false, serialWrite = false, readWordEnabled = false)
        restoredMinimal.component.restoreSnapshot(savedMinimal)
        assertEquals(minimal.component.state(), restoredMinimal.component.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness(booleanWrite = false, serialWrite = true, readWordEnabled = true)
        live.port("a").write(0, 4, 3L)
        live.port("serial-clock").write(0, 4, 3L shl 2)
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 2) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badMaskA = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val wordsA = (get("wordsA") as List<Any?>).toMutableList()
            wordsA[0] = 8
            put("wordsA", wordsA)
        }
        val forbiddenBooleanWrite = LinkedHashMap(validSnapshot).apply {
            put("booleanLastWrite", 1)
        }
        val invalidSerialLane = LinkedHashMap(validSnapshot).apply {
            put("serialLastWrite", 0x0f)
        }
        val mismatchedReadWord = LinkedHashMap(validSnapshot).apply {
            put("readWordFff48004", mapOf("profile" to ReadWordInput.PROFILE, "value" to 0x12345678L))
        }

        for (corrupted in listOf(badSchema, badComponent, badMaskA, forbiddenBooleanWrite, invalidSerialLane, mismatchedReadWord)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }
    }
}
