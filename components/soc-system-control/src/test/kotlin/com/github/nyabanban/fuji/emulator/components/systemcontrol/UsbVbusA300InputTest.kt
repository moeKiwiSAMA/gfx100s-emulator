package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class UsbVbusA300InputTest {
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
    fun defaultProfileOmitsUsbVbusPortAndPreservesHistoricalStateAndSnapshotShape() {
        val ctx = TestContext()
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default }
        val comp = factory.create(ctx, config) as StatefulComponent
        assertNull(ctx.ports.find(UsbVbusA300Input.PORT, MmioDevice::class.java))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertEquals(
            setOf(
                "profile", "booleanWrite", "serialWrite", "masksA", "masksB",
                "wordsA", "wordsB", "booleanLastWrite", "serialLastWrite", "basis",
            ),
            comp.state().keys,
        )
        assertFalse("usbVbusA300State" in comp.saveSnapshot())
    }

    @Test
    fun optInProfileExposesReadOnlyUsbVbusA300DefaultsDetachedSupportsLiveToggleAndRoundTripsSnapshots() {
        val statusData = mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 1L)
        val ctx = TestContext(statusData)
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "profile" to UsbVbusA300Input.PROFILE,
            "statusA200InputFile" to "status-a200.json",
        )
        val comp = factory.create(ctx, config) as StatefulComponent
        assertEquals(
            listOf(UsbVbusA300Input.STATUS_A200_V2_PORT, UsbVbusA300Input.PORT),
            comp.additionalMmioPorts(),
        )
        val vbus = assertNotNull(ctx.ports.find(UsbVbusA300Input.PORT, MmioDevice::class.java))
        val statusA200 = assertNotNull(ctx.ports.find(UsbVbusA300Input.STATUS_A200_V2_PORT, MmioDevice::class.java))
        assertNull(ctx.ports.find(StatusA200Input.PORT, MmioDevice::class.java))

        // Default detached = 0L; status-a200-v2 preserves 1L
        assertEquals(0L, vbus.read(0, 4))
        assertEquals(1L, statusA200.read(0, 4))

        // Refuses writes and non-aligned / non-32-bit reads without mutating state
        val beforeDetached = comp.state()
        for ((offset, size) in listOf(0L to 1, 0L to 2, 0L to 8, 1L to 4, 4L to 4, -1L to 4)) {
            assertFails { vbus.read(offset, size) }
            assertFails { vbus.write(offset, size, 0L) }
        }
        assertFails { vbus.write(0, 4, 1L) }
        assertEquals(beforeDetached, comp.state())

        // Live toggle usbVbusPresent -> true -> 1L, back to false -> 0L
        comp.applySettings(mapOf("usbVbusPresent" to true))
        assertEquals(1L, vbus.read(0, 4))
        val attachedState = comp.state()
        val attachedSnapshot = comp.saveSnapshot()
        assertEquals(mapOf("profile" to UsbVbusA300Input.PROFILE, "present" to true), attachedSnapshot["usbVbusA300State"])
        comp.applySettings(mapOf("usbVbusPresent" to false))
        assertEquals(0L, vbus.read(0, 4))

        val restored = SystemControlFactory().create(TestContext(statusData), config) as StatefulComponent
        restored.restoreSnapshot(attachedSnapshot)
        assertEquals(attachedState, restored.state())
        assertEquals(1L, restored.state().let { (it["usbVbusA300State"] as Map<*, *>)["word"] })

        // Tampered snapshot rejects atomically without mutating state
        val tampered = attachedSnapshot + ("usbVbusA300State" to mapOf("profile" to UsbVbusA300Input.PROFILE, "present" to false))
        assertFailsWith<IllegalArgumentException> { restored.restoreSnapshot(tampered) }
        assertEquals(attachedState, restored.state())

        // Legacy V3 snapshot (without usbVbusPresent scalar or usbVbusA300State) restores cleanly onto default profile
        val defaultCfg = factory.descriptor.settings.associate { it.key to it.default }
        val defaultComp = SystemControlFactory().create(TestContext(), defaultCfg) as StatefulComponent
        val v3Snap = defaultComp.saveSnapshot().let { snap ->
            @Suppress("UNCHECKED_CAST")
            val scalars = snap["scalars"] as Map<String, Any?>
            snap + ("scalars" to (scalars - "usbVbusPresent"))
        }
        val legacyTarget = SystemControlFactory().create(TestContext(), defaultCfg) as StatefulComponent
        legacyTarget.restoreSnapshot(v3Snap)
        assertEquals(defaultComp.state(), legacyTarget.state())
    }
}
