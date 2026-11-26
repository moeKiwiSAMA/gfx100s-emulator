package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import org.junit.jupiter.api.Test
import kotlin.test.*

class ClusterStateRegistersTest {
    private class Interrupts : CoreInterruptPort {
        override val coreCount = 4
        var current = 0
        var selections = 0
        var choices = 0
        override fun selectCore(core: Int) { current = core; selections++ }
        override fun choose(): Int? { choices++; return null }
        fun snapshot() = listOf(current, selections, choices)
    }

    private val cp15 = linkedMapOf(
        "sctlr" to Triple(1, 0, 0),
        "ttbr0" to Triple(2, 0, 0),
        "ttbr1" to Triple(2, 0, 1),
        "ttbcr" to Triple(2, 0, 2),
        "dacr" to Triple(3, 0, 0),
        "vbar" to Triple(12, 0, 0),
    )

    private fun registers(core: Int) = (0..12).associate { "r$it" to (0x81000000L + core * 0x1000L + it) } +
        mapOf(
            "sp" to (0x800L + core * 16),
            "lr" to (0x900L + core * 16),
            "pc" to (0x100L + core * 4),
            "cpsr" to (((core + 1L) shl 28) or 0xd3),
        )

    private fun control(core: Int) = mapOf(
        "sctlr" to 0xc50078L,
        "ttbr0" to (0x100000L + core * 0x4000),
        "ttbr1" to (0x200000L + core * 0x4000),
        "ttbcr" to core.toLong(),
        "dacr" to (0x11111111L * core),
        "vbar" to (0x100L + core * 32),
    )

    @Test
    fun allFourCoresStateRegistersAndCp15ExportWithoutObservationMutation() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 4, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x10000, Dynarmic.PERM_ALL)
            val shared = ByteArray(4096) { (it * 17 + 3).toByte() }
            dyn.writeMemory(0, shared)
            val port = Interrupts()
            val cluster = DynarmicCluster(dyn, port)

            for (core in 0..3) {
                assertEquals(core, cluster.current)
                val expected = registers(core)
                dyn.setCpsr(core, expected.getValue("cpsr"))
                for (r in 0..12) dyn.setReg(core, r, expected.getValue("r$r"))
                dyn.setReg(core, 13, expected.getValue("sp"))
                dyn.setReg(core, 14, expected.getValue("lr"))
                dyn.setReg(core, 15, expected.getValue("pc"))
                cp15.forEach { (name, p) -> dyn.setCp15(core, 0, p.first, p.second, p.third, control(core).getValue(name)) }
                dyn.setCp15(core, 0, 1, 0, 2, 0xf00000L)
                dyn.setFpexc(core, 0x40000000L)
                dyn.setFpscr(core, core.toLong() shl 22)
                for (d in 0..31) dyn.setDreg(core, d, 0x1122334455667700L + core * 64 + d)
                dyn.setFpexc(core, if (core % 2 == 0) 0x40000000L else 0L)
                cluster.account(core + 1L)
                if (core < 3) assertTrue(cluster.switch())
            }

            assertEquals(3, cluster.current)
            val beforePort = port.snapshot()
            val remaining = cluster.remaining()
            val observed = cluster.state()
            @Suppress("UNCHECKED_CAST")
            val cores = observed["cores"] as List<Map<*, *>>
            assertEquals(4, cores.size)
            cores.forEachIndexed { core, row ->
                assertEquals(core, row["core"])
                assertEquals(core + 1L, row["attempts"])
                assertNull(row["waiting"])
                assertEquals(registers(core), row["registers"], "core$core all 17 register fields")
                assertEquals(control(core), row["cp15"], "core$core control")
            }
            assertEquals(3, observed["currentCore"])
            assertEquals(0L, observed["eventCount"])
            val reset = observed["resetSchedule"] as Map<*, *>
            assertEquals(listOf(1L, 2L, 4L, 7L), reset["firstInstructionTicks"])
            assertEquals(observed, cluster.state())
            assertContentEquals(shared, dyn.readMemory(0, 4096))
            assertEquals(beforePort, port.snapshot())
            assertEquals(remaining, cluster.remaining())
        }
    }
}
