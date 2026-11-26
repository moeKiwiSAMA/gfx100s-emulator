package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RtcScriptedFactoryTest {
    private val profile = "gfx100s-rtc-scripted-stage-read-experimental-v2"
    private class Context(var data: Map<String, Any?>) : ComponentContext {
        val entries = hashMapOf<String, Any>(); var calls = 0
        override val directory = Path.of("unused-rtc-script-test")
        override val clock = EmulationClock { 0 }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("No invented RTC event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) { check(entries.putIfAbsent(name, port) == null) }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("Not needed")
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> { calls++; return data }
    }
    private fun input() = mutableMapOf<String, Any?>("schema" to 2, "profile" to profile, "ticksPerSecond" to 10L,
        "initialSeconds" to 0x12345678L, "initialControl" to 3, "counterRunning" to false, "loadDelayTicks" to 5L,
        "stageReadInputs" to mapOf("29" to listOf(0x1234), "30" to listOf(0x5678)))
    private fun create(c: Context, selected: String = profile): HardwareComponent {
        val f = RtcFactory(); return f.create(c, f.descriptor.settings.associate { it.key to it.default } + mapOf("profile" to selected, "inputFile" to "input.json"))
    }
    @Test fun explicitV2PublishesDescribedPreparedEndpointWithoutInitializingStageLatches() {
        val c = Context(input()); val component = create(c); val p = c.entries.getValue("rtc.registers") as DescribedPowerRegisterPort
        assertTrue(p.registerContracts().take(2).all { it.readable && it.writable })
        p.connect("test").use { connection -> assertTrue(connection is PreparedPowerRegisterConnection); assertFails { connection.read(29, 0) } }
        val regs = component.state()["registers"] as Map<*, *>
        assertNull(regs["stageLow"]); assertNull(regs["stageHigh"]); assertEquals(0, regs["freshHalves"])
        assertEquals("HYPOTHESIS", (component.state()["scriptedStageReads"] as Map<*, *>)["confidence"])
        assertEquals(1, c.calls); assertTrue(component.interruptRoutes().isEmpty()); assertTrue(component.additionalMmioPorts().isEmpty())
    }
    @Test fun everyMissingExtraOrNullTopLevelPropertyRefusesBeforePublication() {
        for (key in input().keys) {
            for (bad in listOf(input().apply { remove(key) }, input().apply { this[key] = null })) {
                val c = Context(bad); assertFails { create(c) }; assertTrue(c.entries.isEmpty())
            }
        }
        val c = Context(input() + ("ready" to true)); assertFails { create(c) }; assertTrue(c.entries.isEmpty())
    }
    @Test fun selectorAndSequenceShapeRequireExactFiniteRaw16Inputs() {
        val bad = listOf<Any?>(null, emptyMap<String, Any>(), mapOf("29" to listOf(1)), mapOf("29" to listOf(1), "30" to listOf(2), "31" to listOf(3)),
            mapOf(29 to listOf(1), 30 to listOf(2)), mapOf("029" to listOf(1), "30" to listOf(2)),
            mapOf("29" to emptyList<Int>(), "30" to listOf(2)), mapOf("29" to List(65) { 1 }, "30" to listOf(2))) +
            listOf(null, -1, 65536, 1.0, "1", true).map { mapOf("29" to listOf(it), "30" to listOf(2)) }
        for (value in bad) { val c = Context(input().apply { this["stageReadInputs"] = value }); assertFails { create(c) }; assertTrue(c.entries.isEmpty()) }
    }
    @Test fun parserOwnedMapsAndListsAreFrozenDeeply() {
        val low = mutableListOf<Any?>(0x1234); val high = mutableListOf<Any?>(0x5678)
        val sequences = mutableMapOf<String, Any?>("29" to low, "30" to high)
        val c = Context(input().apply { this["stageReadInputs"] = sequences }); val component = create(c)
        low[0] = 0; high.clear(); sequences.clear()
        val port = c.entries.getValue("rtc.registers") as PowerRegisterPort
        (port.connect("test") as PreparedPowerRegisterConnection).use { connection ->
            val r = connection.prepareRead(29, 0); assertEquals(0x1234, r.value); r.cancel()
        }
        val inputs = component.state()["inputs"] as Map<*, *>
        assertEquals(mapOf("29" to listOf(0x1234), "30" to listOf(0x5678)), inputs["stageReadInputs"])
    }
    @Test fun profileAndSchemaCannotCrossSelectLegacyAndScriptedSemantics() {
        for ((selected, data) in listOf(profile to input().apply { this["schema"] = 1 }, profile to input().apply { this["schema"] = 2.0 },
            profile to input().apply { this["profile"] = "gfx100s-rtc-functional-experimental-v1" },
            "gfx100s-rtc-functional-experimental-v1" to input(), "unknown" to input())) {
            val c = Context(data); assertFails { create(c, selected) }; assertTrue(c.entries.isEmpty())
        }
    }
    @Test fun legacyAndDisabledStateStillHaveNoScriptFields() {
        val data = input().apply { remove("stageReadInputs"); this["schema"] = 1; this["profile"] = "gfx100s-rtc-functional-experimental-v1" }
        val c = Context(data); val legacy = create(c, "gfx100s-rtc-functional-experimental-v1")
        assertFalse(legacy.state().containsKey("scriptedStageReads")); assertEquals(1, (legacy.state()["inputs"] as Map<*, *>)["schema"])
        val endpoint = c.entries.getValue("rtc.registers") as DescribedPowerRegisterPort
        endpoint.connect("legacy").use { assertFalse(it is PreparedPowerRegisterConnection); assertFails { it.read(29, 0) } }
        val disabledContext = Context(input()); val f = RtcFactory(); val disabled = f.create(disabledContext, f.descriptor.settings.associate { it.key to it.default })
        assertEquals(0, disabledContext.calls); assertFalse(disabled.state().containsKey("scriptedStageReads")); assertTrue(disabledContext.entries.isEmpty())
    }
}
