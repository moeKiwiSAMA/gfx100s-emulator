package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ConfigurationSnapshotTest {
    private class TestContext : ComponentContext {
        var tick = 0L
        var currentLocation: ExecutionLocation? = ExecutionLocation(1, 0x80001020L)
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
            override fun publish(event: ComponentEvent) { error("No IRQ/hardware event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to BaseParameterState.PROFILE,
            "initialPairs" to List(13) { slot -> listOf(0x80000000L + slot, 0xffffffffL - slot) },
            "initialStatus" to List(13) { 0 },
            "requestTicks" to List(13) { 5 },
            "settleTicks" to List(13) { 7 },
        )
    }

    private class Harness(
        profile: String = PairReconfigurationState.PROFILE,
        baseEnabled: Boolean = true,
        initialValue: Long = 3L,
        completionTicks: Long = 8L,
    ) {
        val context = TestContext()
        val factory = ConfigurationFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
            "profile" to profile,
            "initialValue" to initialValue,
            "completionTicks" to completionTicks,
            "baseProfile" to if (baseEnabled) BaseParameterState.PROFILE else "disabled",
            "baseInputFile" to if (baseEnabled) "explicit.json" else "",
        )
        val component = factory.create(context, config) as StatefulComponent
        fun groupPort(group: Int): ScheduledMmioDevice =
            context.ports.find("soc-configuration.group$group", MmioDevice::class.java) as ScheduledMmioDevice
        fun statusPort(index: Int): MmioDevice =
            context.ports.find("soc-configuration.base-status$index", MmioDevice::class.java)!!
        fun advanceTo(targetTick: Long) {
            context.tick = targetTick
            for (group in 0..19) {
                groupPort(group).beforeInstruction(targetTick)
            }
        }
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
    fun snapshotRoundTripPreservesInFlightCommandBaseAndReconfigurationAcrossT1ToT2() {
        val continuous = Harness()
        fun runPrefixToT1(target: Harness) {
            target.context.tick = 4L
            target.groupPort(0).write(0x40, 4, 257L)
            target.groupPort(0).write(0x44, 4, 511L)
            target.groupPort(0).write(0x80, 4, 2L)
            target.advanceTo(12L)
            target.groupPort(2).write(0x40, 4, 99L)
            target.groupPort(2).write(0x80, 4, 2L)
            target.groupPort(2).write(0x80, 4, 2L)
            target.context.currentLocation = ExecutionLocation(2, 0x120L)
            target.groupPort(7).write(0, 4, 0xabcdef01L)
            target.groupPort(7).write(8, 4, 0x10203040L)
            target.groupPort(7).write(0x80, 4, 3L)
            target.groupPort(10).write(0x80, 4, 5L)
            target.groupPort(16).write(0x80, 4, 5L)
            target.advanceTo(20L)
            target.context.currentLocation = null
            target.groupPort(10).write(0, 4, 0x99887766L)
            target.groupPort(10).write(8, 4, 0x55443322L)
            target.groupPort(10).write(0x80, 4, 6L)
            target.groupPort(16).write(0, 4, 0x11112222L)
            target.groupPort(19).write(0x80, 4, 5L)
            target.groupPort(3).write(0x40, 4, 412L)
            target.groupPort(3).write(0x80, 4, 2L)
            assertEquals(1L, target.statusPort(1).read(0, 4))
            target.advanceTo(23L)
        }
        fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            observations += target.groupPort(3).read(0x80, 4)
            observations += target.groupPort(10).read(0x80, 4)
            observations += target.groupPort(19).read(0x80, 4)
            observations += target.statusPort(3).read(0, 4)
            target.advanceTo(25L)
            observations += target.groupPort(10).read(0x80, 4)
            target.advanceTo(28L)
            observations += target.groupPort(3).read(0x80, 4)
            observations += target.groupPort(19).read(0x80, 4)
            target.groupPort(16).write(8, 4, 0x33334444L)
            target.groupPort(16).write(0x80, 4, 6L)
            target.groupPort(19).write(0, 4, 0xdeadbeefL)
            target.groupPort(19).write(8, 4, 0xcafebabeL)
            target.groupPort(19).write(0x80, 4, 6L)
            target.advanceTo(45L)
            observations += target.statusPort(3).read(0, 4)
            observations += target.statusPort(9).read(0, 4)
            observations += target.statusPort(12).read(0, 4)
            observations += target.groupPort(10).read(0, 4)
            observations += target.groupPort(16).read(8, 4)
            observations += target.groupPort(19).read(0, 4)
            return observations
        }

        runPrefixToT1(continuous)
        val snapshotAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val resumed = Harness()
        resumed.context.tick = 23L
        resumed.context.currentLocation = continuous.context.currentLocation
        resumed.component.restoreSnapshot(snapshotAtT1)
        assertEquals(stateAtT1, resumed.component.state())
        for (group in 0..19) {
            assertEquals(continuous.groupPort(group).nextDeadline(), resumed.groupPort(group).nextDeadline())
        }

        val expectedObservations = runContinuationToT2(continuous)
        val actualObservations = runContinuationToT2(resumed)
        assertEquals(expectedObservations, actualObservations)
        assertEquals(continuous.component.state(), resumed.component.state())
    }

    @Test
    fun ringBufferWrapAroundAndLegacyDisabledBaseProfileRoundTripCleanly() {
        val wrapped = Harness()
        repeat(560) { stepIndex ->
            wrapped.context.tick = stepIndex.toLong()
            val offset = if (stepIndex % 2 == 0) 0L else 8L
            wrapped.groupPort(0).write(offset, 4, (1000L + stepIndex) and 0xffffffffL)
        }
        val savedWrapped = roundTripMap(wrapped.component.saveSnapshot())
        val restoredWrapped = Harness()
        restoredWrapped.context.tick = 560L
        restoredWrapped.component.restoreSnapshot(savedWrapped)
        assertEquals(wrapped.component.state(), restoredWrapped.component.state())
        wrapped.context.tick = 561L
        restoredWrapped.context.tick = 561L
        assertEquals(wrapped.groupPort(0).read(0, 4), restoredWrapped.groupPort(0).read(0, 4))
        assertEquals(wrapped.component.state(), restoredWrapped.component.state())

        val legacy = Harness(profile = "configuration-command2-experimental-v1", baseEnabled = false)
        legacy.context.tick = 10L
        legacy.groupPort(5).write(0x48, 4, 333L)
        legacy.groupPort(5).write(0x80, 4, 2L)
        val savedLegacy = roundTripMap(legacy.component.saveSnapshot())
        val restoredLegacy = Harness(profile = "configuration-command2-experimental-v1", baseEnabled = false)
        restoredLegacy.context.tick = 10L
        restoredLegacy.component.restoreSnapshot(savedLegacy)
        assertEquals(legacy.component.state(), restoredLegacy.component.state())
        legacy.advanceTo(18L)
        restoredLegacy.advanceTo(18L)
        assertEquals(legacy.component.state(), restoredLegacy.component.state())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness()
        live.context.tick = 5L
        live.groupPort(1).write(0x40, 4, 123L)
        live.groupPort(1).write(0x80, 4, 2L)
        live.groupPort(10).write(0x80, 4, 5L)
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 2) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badCompletion = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val scalars = LinkedHashMap(get("scalars") as Map<String, Any?>)
            scalars["completionTicks"] = 99
            put("scalars", scalars)
        }
        val badNineBitWord = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val stagedGroups = (get("staged") as List<List<Any?>>).map { groupWords -> groupWords.toMutableList() }.toMutableList()
            stagedGroups[0][0] = 512
            put("staged", stagedGroups)
        }
        val conflictingPending = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val pendingList = (get("pending") as List<Any?>).toMutableList()
            pendingList[10] = mapOf("deadline" to 20, "values" to listOf(3, 3))
            @Suppress("UNCHECKED_CAST")
            val writtenList = (get("written") as List<Boolean>).toMutableList()
            writtenList[10] = true
            put("pending", pendingList)
            put("written", writtenList)
        }
        val badReconfigurationPhase = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val reconfig = LinkedHashMap(get("reconfiguration") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val contexts = (reconfig["contexts"] as List<Any?>).toMutableList()
            @Suppress("UNCHECKED_CAST")
            val contextZero = LinkedHashMap(contexts[0] as Map<String, Any?>)
            contextZero["phase"] = 3
            contexts[0] = contextZero
            reconfig["contexts"] = contexts
            put("reconfiguration", reconfig)
        }

        for (corrupted in listOf(badSchema, badComponent, badCompletion, badNineBitWord, conflictingPending, badReconfigurationPhase)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }
    }
}
