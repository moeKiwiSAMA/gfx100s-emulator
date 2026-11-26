package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class BootStrapInputTest {
    private class TestContext(private val data: Map<String, Any?> = emptyMap()) : ComponentContext {
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
            override fun publish(event: ComponentEvent) { error("No system-control event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = data
    }

    @Test
    fun absentBootStrapRefusesPortRegistrationAndPreservesHistoricalState() {
        val context = TestContext()
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default }
        val component = factory.create(context, config)
        assertNull(context.ports.find(BootStrapInput.PORT, MmioDevice::class.java))
        assertTrue(component.additionalMmioPorts().isEmpty())
        assertFalse("bootStrapInputFile" in component.state())
        assertFalse("bootStrapFff4a000" in component.state())
    }

    @Test
    fun configuredBootStrapReturnsExactAlignedWordRefusesWritesAndUnalignedAndRoundTripsSnapshots() {
        val data = mapOf("schema" to 1, "profile" to BootStrapInput.PROFILE, "value" to 8L)
        val context = TestContext(data)
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "booleanWrite" to true,
            "bootStrapInputFile" to "boot-strap.json",
        )
        val component = factory.create(context, config) as StatefulComponent
        assertEquals(listOf(BootStrapInput.PORT), component.additionalMmioPorts())
        val port = assertNotNull(context.ports.find(BootStrapInput.PORT, MmioDevice::class.java))
        assertEquals(8L, port.read(0, 4))

        val beforeState = component.state()
        for ((offset, size) in listOf(0L to 1, 0L to 2, 0L to 8, 1L to 4, 4L to 4, -1L to 4)) {
            assertFails { port.read(offset, size) }
            assertFails { port.write(offset, size, 0L) }
        }
        assertFails { port.write(0, 4, 8L) }
        assertEquals(beforeState, component.state())

        val snapshot = component.saveSnapshot()
        assertNotNull(snapshot["bootStrapFff4a000"])
        val restored = SystemControlFactory().create(TestContext(data), config) as StatefulComponent
        restored.restoreSnapshot(snapshot)
        assertEquals(component.state(), restored.state())

        val badSnapshot = snapshot + ("bootStrapFff4a000" to mapOf("profile" to BootStrapInput.PROFILE, "value" to 0L))
        assertFailsWith<IllegalArgumentException> { restored.restoreSnapshot(badSnapshot) }
        assertEquals(beforeState, restored.state())

        val legacySnapshot = snapshot - "bootStrapFff4a000" + ("scalars" to (snapshot["scalars"] as Map<*, *>) - "bootStrapInputFile")
        val legacyTarget = SystemControlFactory().create(TestContext(), factory.descriptor.settings.associate { it.key to it.default } + ("booleanWrite" to true)) as StatefulComponent
        legacyTarget.restoreSnapshot(legacySnapshot)
        assertNull((legacyTarget as HardwareComponent).state()["bootStrapFff4a000"])
    }

    @Test
    fun malformedBootStrapInputSchemaRefusesBeforeComponentCreation() {
        val factory = SystemControlFactory()
        val baseConfig = factory.descriptor.settings.associate { it.key to it.default } + ("bootStrapInputFile" to "bad.json")
        val badInputs = listOf(
            mapOf("schema" to 2, "profile" to BootStrapInput.PROFILE, "value" to 8L),
            mapOf("schema" to 1, "profile" to "wrong", "value" to 8L),
            mapOf("schema" to 1, "profile" to BootStrapInput.PROFILE),
            mapOf("schema" to 1, "profile" to BootStrapInput.PROFILE, "value" to -1L),
            mapOf("schema" to 1, "profile" to BootStrapInput.PROFILE, "value" to 0x1_0000_0000L),
            mapOf("schema" to 1, "profile" to BootStrapInput.PROFILE, "value" to "8"),
        )
        for (bad in badInputs) {
            val ctx = TestContext(bad)
            assertFails { factory.create(ctx, baseConfig) }
            assertNull(ctx.ports.find(BootStrapInput.PORT, MmioDevice::class.java))
        }
    }
}
