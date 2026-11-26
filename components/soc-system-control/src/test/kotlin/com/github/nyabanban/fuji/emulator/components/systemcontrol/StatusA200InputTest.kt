package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class StatusA200InputTest {
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
    fun absentStatusA200RefusesPortAndPreservesHistoricalStateShape() {
        val ctx = TestContext()
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default }
        val comp = factory.create(ctx, config)
        assertNull(ctx.ports.find(StatusA200Input.PORT, MmioDevice::class.java))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertEquals(
            setOf("profile", "booleanWrite", "serialWrite", "masksA", "masksB", "wordsA", "wordsB", "booleanLastWrite", "serialLastWrite", "basis"),
            comp.state().keys,
        )
    }

    @Test
    fun configuredStatusA200ReturnsExactAlignedWordRefusesWritesAndRoundTripsLegacyAndNewSnapshots() {
        val data = mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 1L)
        val ctx = TestContext(data)
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "booleanWrite" to true,
            "statusA200InputFile" to "status-a200.json",
        )
        val comp = factory.create(ctx, config) as StatefulComponent
        assertEquals(listOf(StatusA200Input.PORT), comp.additionalMmioPorts())
        val port = assertNotNull(ctx.ports.find(StatusA200Input.PORT, MmioDevice::class.java))
        assertEquals(1L, port.read(0, 4))

        val before = comp.state()
        for ((offset, size) in listOf(0L to 1, 0L to 2, 0L to 8, 1L to 4, 4L to 4, -1L to 4)) {
            assertFails { port.read(offset, size) }
            assertFails { port.write(offset, size, 0L) }
        }
        assertFails { port.write(0, 4, 1L) }
        assertEquals(before, comp.state())

        val snapshot = comp.saveSnapshot()
        assertNotNull(snapshot["statusA200Fff4a200"])
        val restored = SystemControlFactory().create(TestContext(data), config) as StatefulComponent
        restored.restoreSnapshot(snapshot)
        assertEquals(comp.state(), restored.state())

        val badSnapshot = snapshot + ("statusA200Fff4a200" to mapOf("profile" to StatusA200Input.PROFILE, "value" to 0L))
        assertFailsWith<IllegalArgumentException> { restored.restoreSnapshot(badSnapshot) }
        assertEquals(before, restored.state())

        // Both 8-key (V1) and 9-key (V2) legacy snapshots restore onto default disabled target
        val defaultCfg = factory.descriptor.settings.associate { it.key to it.default } + ("booleanWrite" to true)
        @Suppress("UNCHECKED_CAST")
        val scalars = snapshot["scalars"] as Map<String, Any?>
        val v2Legacy = snapshot - "statusA200Fff4a200" - "write49530State" - "tail49500State" +
            ("scalars" to (scalars - "statusA200InputFile" - "write49530" - "tail49500"))
        val v1Legacy = v2Legacy - "bootStrapFff4a000" +
            ("scalars" to ((v2Legacy["scalars"] as Map<*, *>) - "bootStrapInputFile"))

        for (legacy in listOf(v1Legacy, v2Legacy)) {
            val target = SystemControlFactory().create(TestContext(), defaultCfg) as StatefulComponent
            target.restoreSnapshot(legacy)
            assertNull((target as HardwareComponent).state()["statusA200Fff4a200"])
        }
    }

    @Test
    fun malformedStatusA200InputSchemaRefusesBeforePortRegistration() {
        val factory = SystemControlFactory()
        val baseConfig = factory.descriptor.settings.associate { it.key to it.default } + ("statusA200InputFile" to "bad.json")
        val badInputs = listOf(
            mapOf("schema" to 2, "profile" to StatusA200Input.PROFILE, "value" to 1L),
            mapOf("schema" to 1, "profile" to "wrong", "value" to 1L),
            mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE),
            mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to -1L),
            mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 0x1_0000_0000L),
            mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to "1"),
        )
        for (bad in badInputs) {
            val ctx = TestContext(bad)
            assertFails { factory.create(ctx, baseConfig) }
            assertNull(ctx.ports.find(StatusA200Input.PORT, MmioDevice::class.java))
        }
    }
}
