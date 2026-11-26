package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import org.junit.jupiter.api.Test
import kotlin.test.*

class CheckpointFloatingPointTest {
    @Test
    fun fourCoreSnapshotReadsEveryDRegisterAndPreservesLiveContext() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 4, 0xFFFF_0000L).use { dyn ->
            val interrupts = object : CoreInterruptPort {
                override val coreCount = 4
                override fun choose(): Int? = null
                override fun selectCore(core: Int) { require(core in 0..3) }
            }
            val cluster = DynarmicCluster(dyn, interrupts)
            repeat(4) { core ->
                assertEquals(core, cluster.current)
                dyn.setCpsr(core, 0xd3L)
                dyn.setCp15(core, 0, 1, 0, 2, 0xf00000L)
                dyn.setReg(core, 0, 0x100L + core)
                dyn.setFpexc(core, 0x40000000L)
                repeat(32) { d -> dyn.setDreg(core, d, 0x1122334455667700L + core * 64 + d) }
                dyn.setFpexc(core, if (core % 2 == 0) 0x40000000L else 0L)
                cluster.account(1024)
                assertTrue(cluster.switch())
            }
            dyn.setReg(0, 0, 0xabcdefL)
            dyn.setDreg(0, 0, 0x7f123456789abcdeL)
            dyn.selectCore(0)
            val before = FloatingPointDiagnostics.readRegisters(dyn)

            val snap = cluster.saveSnapshot()
            @Suppress("UNCHECKED_CAST")
            val cores = snap.metadata["cores"] as List<Map<*, *>>
            repeat(4) { core ->
                val arch = cores[core]["architectural"] as Map<*, *>
                val fp = arch["floatingPoint"] as Map<*, *>
                val registers = fp["dRegisters"] as List<*>
                repeat(32) { d ->
                    val expected = if (core == 0 && d == 0) 0x7f123456789abcdeL else 0x1122334455667700L + core * 64 + d
                    assertEquals(expected.toULong().toString(16).padStart(16, '0'), registers[d])
                }
            }
            assertEquals(0, cluster.current)
            assertEquals(0xabcdefL, dyn.getReg(0, 0))
            assertEquals(before, FloatingPointDiagnostics.readRegisters(dyn))
        }
    }
}
