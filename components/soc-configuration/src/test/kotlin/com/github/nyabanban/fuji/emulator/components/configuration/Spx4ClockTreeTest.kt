package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Spx4ClockTreeTest {
    private class StubContext : ComponentContext {
        override val directory: Path = Path.of("unused-config-clocktree-test").toAbsolutePath().normalize()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            private val entries = linkedMapOf<String, Any>()
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                entries[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) { entries.clear() }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to BaseParameterState.PROFILE,
            "initialPairs" to List(13) { listOf(0L, 0xffff_ffffL) },
            "initialStatus" to List(13) { 0L },
            "requestTicks" to List(13) { 24L },
            "settleTicks" to List(13) { 30L },
        )
    }

    @Test
    fun pllAndDividerTablesMatchFirmwareTopologyAndGroupIndices() {
        assertEquals(13, Spx4ClockTree.PLL_COUNT)
        assertEquals(13, Spx4ClockTree.PLLS.size)
        assertEquals(BaseParameterState.groups, Spx4ClockTree.PLLS.map { it.groupIndex })
        assertEquals(52, Spx4ClockTree.PLL_BACKED_DIVIDER_COUNT)
        assertEquals(54, Spx4ClockTree.TOTAL_DIVIDER_COUNT)
        assertEquals(54, Spx4ClockTree.DIVIDERS.size)
        assertEquals(54_000_000L, Spx4ClockTree.TIMER_REFERENCE_HZ)
        assertEquals(
            listOf(48_000_000L, 24_000_000L, 12_000_000L, 6_000_000L, 3_000_000L, 1_500_000L, 750_000L, 375_000L),
            Spx4ClockTree.PERIPHERAL_STEP_TABLE_HZ,
        )

        // Verify PLL0..PLL12 cover clk[0..51] contiguously with zero gaps or overlaps
        var expectedClock = 0
        for ((index, pll) in Spx4ClockTree.PLLS.withIndex()) {
            assertEquals(index, pll.pllIndex)
            assertEquals(expectedClock, pll.firstClockId)
            assertTrue(pll.lastClockId >= pll.firstClockId)
            expectedClock = pll.lastClockId + 1
        }
        assertEquals(52, expectedClock)

        // Key decoded consumers from RE-BOOTROM-DEEP
        val pll10 = Spx4ClockTree.pllForGroup(17)!!
        assertEquals(10, pll10.pllIndex)
        assertEquals(0xFF70_9000L, pll10.mmioBase)
        assertTrue(pll10.label.contains("EVF") && pll10.label.contains("LCDC0"))
        assertEquals(2, pll10.observedBank14Pairs.size)

        val pll12 = Spx4ClockTree.pllForGroup(19)!!
        assertEquals(12, pll12.pllIndex)
        assertEquals(0xFFFA_E000L, pll12.mmioBase)
        assertTrue(pll12.label.contains("Rear LCD") && pll12.label.contains("LCDC1"))
        assertEquals(2, pll12.observedBank14Pairs.size)

        assertEquals(0xFF70_1040L, Spx4ClockTree.divider(0).mmioAddress)
        assertTrue(Spx4ClockTree.divider(0).label.contains("SYS"))
        assertEquals(0xFF70_1048L, Spx4ClockTree.divider(2).mmioAddress)
        assertTrue(Spx4ClockTree.divider(2).label.contains("QSPI"))
        assertEquals(0xFF70_9040L, Spx4ClockTree.divider(47).mmioAddress)
        assertTrue(Spx4ClockTree.divider(47).label.contains("LCDC0"))
        assertEquals(0xFFFA_E040L, Spx4ClockTree.divider(51).mmioAddress)
        assertTrue(Spx4ClockTree.divider(51).label.contains("LCDC1"))
        assertEquals(0xFFF4_860CL, Spx4ClockTree.divider(52).mmioAddress)
        assertNull(Spx4ClockTree.divider(52).pllIndex)
        assertEquals(0xFFF4_8604L, Spx4ClockTree.divider(53).mmioAddress)
        assertNull(Spx4ClockTree.divider(53).pllIndex)
        assertFailsWith<IllegalArgumentException> { Spx4ClockTree.divider(54) }
    }

    @Test
    fun configurationAndBaseParametersExposeClockTreeAndPreserveSnapshotRoundTrip() {
        val factory = ConfigurationFactory()
        val config = mapOf<String, Any>(
            "profile" to PairReconfigurationState.PROFILE,
            "initialValue" to 0L,
            "completionTicks" to 8L,
            "baseProfile" to BaseParameterState.PROFILE,
            "baseInputFile" to "base-parameters.json",
        )
        val primary = assertIs<ConfigurationFactory.Configuration>(factory.create(StubContext(), config))
        val state = primary.state()
        assertFalse("clockTree" in state, "Configuration.state() must preserve strict admission key set")

        val clockTree = primary.clockTreeState()
        assertEquals(13, clockTree["pllCount"])
        assertEquals(54, clockTree["totalDividerCount"])
        assertEquals(54_000_000L, clockTree["timerReferenceHz"])
        assertEquals("UNKNOWN", clockTree["cpuMhzAndVcoFormula"])

        val groupCounts = assertIs<List<Int>>(state["groupCounts"])
        assertEquals(Spx4ClockTree.PLL_BACKED_DIVIDER_COUNT, groupCounts.sum())

        val baseState = assertIs<Map<*, *>>(state["baseParameters"])
        assertFalse("pllLabels" in baseState, "BaseParameterState.state() must preserve strict admission contract")
        val pllLabels = primary.basePllLabels()
        assertEquals(13, pllLabels.size)
        assertEquals("PLL10 (EVF / LCDC0)", pllLabels[10])
        assertEquals("PLL12 (Rear LCD / LCDC1)", pllLabels[12])

        // Snapshot schema stays unchanged and round-trips cleanly
        val snap = primary.saveSnapshot()
        assertFalse("clockTree" in snap)
        val restored = assertIs<StatefulComponent>(factory.create(StubContext(), config))
        restored.restoreSnapshot(snap)
        assertEquals(primary.state(), restored.state())
    }
}
