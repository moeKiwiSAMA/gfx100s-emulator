package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ReadWordInputAvsTest {
    private class StubContext(private val wordValue: Long) : ComponentContext {
        val portsMap = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-sysctrl-avs-test").toAbsolutePath().normalize()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                portsMap[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = portsMap[name]?.let(type::cast)
            override fun removeOwner(owner: String) { portsMap.clear() }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to ReadWordInput.PROFILE,
            "value" to wordValue,
        )
    }

    @Test
    fun avsLookupTablesMatchSection4AndDecodeAllCorners() {
        assertEquals(32, ReadWordInput.AVS_RAIL0_LUT.size)
        assertEquals(32, ReadWordInput.AVS_RAIL2_LUT.size)
        assertEquals(16L, ReadWordInput.NOMINAL_SILICON_BIN)

        // Bins 0..7 fall back to max-voltage safe corner (50 / 140)
        for (bin in 0L..7L) {
            val decoded = ReadWordInput.decodeAvs(bin)
            assertEquals(bin.toInt(), decoded.speedBin)
            assertEquals(50, decoded.rail0LutCode)
            assertEquals(140, decoded.rail2LutCode)
            assertEquals("unbinned-safe-max", decoded.corner)
            assertTrue(decoded.usesSafeFallback)
        }

        // Bin 8 (release word.json): fastest binned corner (33 / 128)
        val bin8 = ReadWordInput.decodeAvs(8L)
        assertEquals(8, bin8.speedBin)
        assertEquals(33, bin8.rail0LutCode)
        assertEquals(128, bin8.rail2LutCode)
        assertEquals("fast", bin8.corner)
        assertFalse(bin8.usesSafeFallback)

        // Nominal silicon bin 16 (39 / 133) with upper bits masked off via & 0x1F
        val bin16Masked = ReadWordInput.decodeAvs(0xABCD_0000L or ReadWordInput.NOMINAL_SILICON_BIN)
        assertEquals(16, bin16Masked.speedBin)
        assertEquals(39, bin16Masked.rail0LutCode)
        assertEquals(133, bin16Masked.rail2LutCode)
        assertEquals("nominal", bin16Masked.corner)
        assertFalse(bin16Masked.usesSafeFallback)

        // Bin 28 (49 / 139) slow corner; bins 29..31 slow-max
        val bin28 = ReadWordInput.decodeAvs(28L)
        assertEquals(49, bin28.rail0LutCode)
        assertEquals(139, bin28.rail2LutCode)
        assertEquals("slow", bin28.corner)

        val bin31 = ReadWordInput.decodeAvs(31L)
        assertEquals(50, bin31.rail0LutCode)
        assertEquals(140, bin31.rail2LutCode)
        assertEquals("slow-max", bin31.corner)

        // Monotonic voltage increase across binned range 8..31
        for (bin in 8..30) {
            assertTrue(ReadWordInput.AVS_RAIL0_LUT[bin] <= ReadWordInput.AVS_RAIL0_LUT[bin + 1])
            assertTrue(ReadWordInput.AVS_RAIL2_LUT[bin] <= ReadWordInput.AVS_RAIL2_LUT[bin + 1])
        }
    }

    @Test
    fun systemControlExposesAvsTelemetryOnlyWhenReadWordConfiguredAndPreservesDefaultKeys() {
        val factory = SystemControlFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }

        // Default profile without readWordInputFile MUST preserve exact 10 historical state() keys
        val defaultComp = assertIs<StatefulComponent>(factory.create(StubContext(0L), defaults))
        assertEquals(
            setOf(
                "profile", "booleanWrite", "serialWrite",
                "masksA", "masksB", "wordsA", "wordsB",
                "booleanLastWrite", "serialLastWrite", "basis",
            ),
            defaultComp.state().keys,
        )

        // With readWordInputFile enabled (nominal bin 16), readWordFff48004 exposes decoded AVS metadata
        val ctx = StubContext(ReadWordInput.NOMINAL_SILICON_BIN)
        val configured = assertIs<StatefulComponent>(
            factory.create(ctx, defaults + ("readWordInputFile" to "word.json")),
        )
        val mmio = checkNotNull(ctx.ports.find(ReadWordInput.PORT, MmioDevice::class.java))
        assertEquals(16L, mmio.read(0L, 4))

        val rwState = assertIs<Map<*, *>>(configured.state()["readWordFff48004"])
        val avs = assertIs<Map<*, *>>(rwState["avs"])
        assertEquals(16, avs["speedBin"])
        assertEquals(39, avs["rail0LutCode"])
        assertEquals(133, avs["rail2LutCode"])
        assertEquals("nominal", avs["corner"])
        assertEquals("DAT", avs["confidence"])

        // Snapshot schema stays unchanged (profile + value only) and round-trips cleanly
        val snap = configured.saveSnapshot()
        val rwSnap = assertIs<Map<*, *>>(snap["readWordFff48004"])
        assertEquals(setOf("profile", "value"), rwSnap.keys)
        val restored = assertIs<StatefulComponent>(
            factory.create(StubContext(ReadWordInput.NOMINAL_SILICON_BIN), defaults + ("readWordInputFile" to "word.json")),
        )
        restored.restoreSnapshot(snap)
        assertEquals(configured.state(), restored.state())
    }
}
