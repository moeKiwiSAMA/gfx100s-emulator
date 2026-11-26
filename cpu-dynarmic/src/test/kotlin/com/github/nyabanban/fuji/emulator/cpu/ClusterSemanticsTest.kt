package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import org.junit.jupiter.api.Test
import kotlin.test.*

class ClusterSemanticsTest {
    private class Interrupts : CoreInterruptPort {
        override val coreCount = 4
        var current = 0
        val pending = arrayOfNulls<Int>(4)
        override fun selectCore(core: Int) { current = core }
        override fun choose(): Int? = pending[current]
    }

    @Test
    fun conditionalWfeSevEventConsumptionAndMaskedWfiWakePreserveRegisters() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 4, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x10000, Dynarmic.PERM_ALL)
            for (core in 0..3) {
                dyn.setCpsr(core, 0xd3L)
                dyn.setReg(core, 15, 0L)
            }
            val port = Interrupts()
            val cluster = DynarmicCluster(dyn, port)

            // Execute unconditional WFE on core 0 -> parks core 0 and switches to core 1
            dyn.writeMemory(0x0000, DynarmicTestHelper.words(0xe3a0_0007L, 0xe320_f002L, 0xe320_f003L))
            val r0 = dyn.runFast(0, 8, 1L, false)
            assertEquals(Dynarmic.STOP_WFE, r0.stopReason)
            assertEquals(7L, dyn.getReg(0, 0), "MOV R0, #7 ran before WFE")
            assertEquals(8L, dyn.getReg(0, 15), "PC advanced past WFE at 0x04")

            // Unconditional WFE parks core 0 -> switches to core 1
            cluster.onWfe()
            assertTrue(cluster.needsSwitch())
            assertTrue(cluster.switch())
            assertEquals(1, cluster.current)

            // Core 1 executes SEV -> wakes core 0 and sets event latches on other cores
            cluster.onSev()
            repeat(3) { assertTrue(cluster.switch()) }
            assertEquals(0, cluster.current)
            assertFalse(cluster.needsSwitch())

            // Prior event was consumed by waking WFE, so second WFE parks core 0 again
            cluster.onWfe()
            assertTrue(cluster.needsSwitch())
            assertTrue(cluster.switch())
            assertEquals(1, cluster.current)
            cluster.onSev()
            repeat(3) { cluster.switch() }

            // Masked WFI wakes on pending IRQ even with CPSR.I=1 (no injected ISR)
            cluster.onWfi()
            assertTrue(cluster.needsSwitch())
            port.pending[0] = 5
            repeat(4) { assertTrue(cluster.switch()) }
            assertEquals(0, cluster.current)
            assertFalse(cluster.needsSwitch())
            assertEquals(0x80L, dyn.getCpsr(0) and 0x80L)

            // WFI with pending IRQ wakes immediately
            cluster.onWfi()
            assertFalse(cluster.needsSwitch())
            port.pending[0] = null

            // All 4 cores execute WFI -> all parked
            repeat(4) {
                cluster.onWfi()
                if (it < 3) assertTrue(cluster.switch())
            }
            assertFalse(cluster.switch())
            @Suppress("UNCHECKED_CAST")
            val cores = cluster.state()["cores"] as List<Map<*, *>>
            assertTrue(cores.all { it["waiting"] == "wfi" })
        }
    }
}
