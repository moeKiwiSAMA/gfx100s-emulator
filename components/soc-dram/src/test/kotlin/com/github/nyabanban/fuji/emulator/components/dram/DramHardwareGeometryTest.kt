package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class DramHardwareGeometryTest {
    private class Registry : Ports {
        val ports = linkedMapOf<String, Pair<String, Any>>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            require(type.isInstance(port) && name !in ports)
            ports[name] = owner to port
        }

        override fun <T : Any> find(name: String, type: Class<T>): T? =
            ports[name]?.second?.let { type.cast(it) }

        override fun removeOwner(owner: String) {
            ports.entries.removeIf { it.value.first == owner }
        }
    }

    private class Context : ComponentContext {
        override val directory: Path = Path.of("unused-dram-geometry-test").toAbsolutePath().normalize()
        override val ports = Registry()
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = error("not used")
    }

    @Test
    fun quadChannelEightGibLpddr4GeometryConstantsAreSelfConsistent() {
        assertEquals("LPDDR4", DramHardwareGeometry.STANDARD)
        assertEquals(0xB, DramHardwareGeometry.DRAM_CLASS_CODE)
        assertEquals("LPDDR4-2400", DramHardwareGeometry.SPEED_GRADE_HYPOTHESIS)
        assertEquals(4, DramHardwareGeometry.CHANNEL_COUNT)
        assertEquals(2, DramHardwareGeometry.RANKS_PER_CHANNEL)
        assertEquals(16, DramHardwareGeometry.CHANNEL_DATA_WIDTH_BITS)
        assertEquals(64, DramHardwareGeometry.AGGREGATE_DATA_WIDTH_BITS)
        assertEquals(8, DramHardwareGeometry.TOTAL_CAPACITY_GIB)
        assertEquals(8192, DramHardwareGeometry.TOTAL_CAPACITY_MIB)
        assertEquals(8L * 1024L * 1024L * 1024L, DramHardwareGeometry.TOTAL_CAPACITY_BYTES)
        assertEquals(1200, DramHardwareGeometry.CK_FREQUENCY_MHZ_HYPOTHESIS)
        assertEquals(600, DramHardwareGeometry.DFI_FREQUENCY_MHZ_HYPOTHESIS)
        assertEquals(2400, DramHardwareGeometry.DATA_RATE_MT_S_HYPOTHESIS)
        assertEquals(19.2, DramHardwareGeometry.PEAK_BANDWIDTH_GB_S_HYPOTHESIS)
        assertEquals(280.0, DramHardwareGeometry.TRFC_AB_NS_HYPOTHESIS)
        assertEquals(listOf(3, 17, 51), DramHardwareGeometry.PI_FSP_TRAS_CYCLES)

        // Verify 4 contiguous 2 GiB channels covering 0x00_0000_0000..0x01_FFFF_FFFF
        assertEquals(4, DramHardwareGeometry.CHANNELS.size)
        var expectedStart = 0L
        var powerGateUnion = 0L
        for ((index, ch) in DramHardwareGeometry.CHANNELS.withIndex()) {
            assertEquals(index, ch.channel)
            assertEquals(0xFF50_0000L + index * 0x1_0000L, ch.umcBase)
            assertEquals(0xFF60_0000L + index * 0x1_0000L, ch.phyBase)
            assertEquals(expectedStart, ch.physicalAddressStart)
            assertEquals(expectedStart + DramHardwareGeometry.BYTES_PER_CHANNEL - 1L, ch.physicalAddressEnd)
            assertEquals(0xFFF4_7820L + index * 4L, ch.thermalSensorReg)
            assertEquals(0L, powerGateUnion and ch.powerGateMask, "Channel $index power gate mask must not overlap")
            powerGateUnion = powerGateUnion or ch.powerGateMask
            expectedStart += ch.capacityBytes
        }
        assertEquals(DramHardwareGeometry.TOTAL_CAPACITY_BYTES, expectedStart)
        assertEquals(0xF_FFFFL, powerGateUnion)
        assertEquals(listOf(0x01L, 0x02L, 0x44L, 0x88L), DramHardwareGeometry.CHANNELS.map { it.resetGateMask })
        assertEquals("stage1-ipl", DramHardwareGeometry.channel(0).bringUpStage)
        assertTrue((1..3).all { DramHardwareGeometry.channel(it).bringUpStage == "section4-runtime" })
        assertFailsWith<IllegalArgumentException> { DramHardwareGeometry.channel(4) }
    }

    @Test
    fun dramFactoryAndColdStartExposeGeometryWithoutBreakingSnapshots() {
        val factory = DramFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }

        // Disabled profile exposes read-only hardwareGeometry telemetry cleanly
        val disabled = assertIs<StatefulComponent>(factory.create(Context(), defaults))
        disabled.start()
        val disabledGeom = assertIs<Map<*, *>>(disabled.state()["hardwareGeometry"])
        assertEquals(8, disabledGeom["totalCapacityGiB"])
        assertEquals("LPDDR4-2400", disabledGeom["speedGrade"])
        assertEquals("DAT", disabledGeom["geometryConfidence"])
        assertEquals("HYPOTHESIS", disabledGeom["speedGradeConfidence"])
        assertEquals("UNKNOWN", disabledGeom["packageVendor"])
        disabled.close()

        // Factory cold-start profile exposes geometry at top-level and inside coldStart state
        val coldCtx = Context()
        val cold = assertIs<StatefulComponent>(
            factory.create(
                coldCtx,
                defaults + mapOf("profile" to DramFactory.FACTORY_COLDSTART_PROFILE, "inputFile" to ""),
            ),
        )
        cold.start()
        val state = cold.state()
        assertEquals(DramHardwareGeometry.toStateMap(), state["hardwareGeometry"])
        val coldState = assertIs<Map<*, *>>(state["coldStart"])
        assertEquals(DramHardwareGeometry.toStateMap(), coldState["hardwareGeometry"])
        val ch0 = assertIs<Map<*, *>>(coldState["coldStartChannel"])
        assertEquals(0, ch0["channel"])
        assertEquals(0xFF50_0000L, ch0["umcBase"])
        assertEquals(0xFF60_0000L, ch0["phyBase"])
        assertEquals(DramHardwareGeometry.BYTES_PER_CHANNEL, ch0["capacityBytes"])

        // Snapshot schema remains untouched (no extra snapshot keys added)
        val snap = cold.saveSnapshot()
        assertFalse("hardwareGeometry" in snap)
        val restored = assertIs<StatefulComponent>(
            factory.create(
                Context(),
                defaults + mapOf("profile" to DramFactory.FACTORY_COLDSTART_PROFILE, "inputFile" to ""),
            ),
        )
        restored.restoreSnapshot(snap)
        assertEquals(cold.state(), restored.state())
        cold.close()
        restored.close()
    }
}
