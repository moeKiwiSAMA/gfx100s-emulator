package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Hardware-fidelity tests matching the GFX100S 2.13 factory `boot.bin` -> `kernel.bin` -> OS handoff:
 * - Native `PERIPHBASE` (`MRC p15, 4, r0, c15, c0, 0` = `0xFFFF0000`).
 * - `ACTLR.SMP`, `CPACR |= 0x00F00000`, `SCTLR` (`M/C/Z/I/V/A` cleared), `DACR = 0xFFFFFFFF`,
 *   `TTBCR = 0`, `CONTEXTIDR = 0`, `VBAR = 0xFFFF0000`, `ICIALLU` / `BPIALL` / `TLBIALL` / `DSB` / `ISB`.
 * - 4-core [DynarmicCluster] SMP holding pen (`*(0xB3D4) == 1` -> `WFI`), SGI 0 release, and 4-core barrier (`0xB3E4[0..3]`).
 */
class StockBootCp15HandoffTest {
    private class PendingIrqPort : CoreInterruptPort {
        override val coreCount = 4
        var selected = 0
        val pending = arrayOfNulls<Int>(4)
        override fun selectCore(core: Int) { selected = core }
        override fun choose(): Int? = pending[selected]
    }

    @Test
    fun stockBootAndLinuxTakeoverCp15SequenceHardensVbarDacrCpacrSctlrAndCacheOps() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x20_0000, Dynarmic.PERM_ALL)

            dyn.setCp15(0, 0, 12, 0, 0, 0x0005_8d80L)
            dyn.setCp15(0, 0, 3, 0, 0, 0x5555_5555L)
            dyn.setCp15(0, 0, 2, 0, 2, 0L)
            dyn.setCp15(0, 0, 13, 0, 1, 0x1234L)
            val seededSctlr = (dyn.getCp15(0, 0, 1, 0, 0) or 0x1806L) and 0x2001L.inv()
            dyn.setCp15(0, 0, 1, 0, 0, seededSctlr)
            dyn.setCpsr(0, 0x0000_01d3L) // SVC, I=1, F=1, A=1

            dyn.writeMemory(
                0x1000,
                DynarmicTestHelper.words(
                    0xee9f_4f10L, // 0x1000: MRC p15, 4, R4, c15, c0, 0 (CBAR -> 0xFFFF0000)
                    0xe10f_0000L, // 0x1004: MRS R0, CPSR
                    0xe380_00c0L, // 0x1008: ORR R0, R0, #0xC0 (I=1, F=1)
                    0xe3c0_0c01L, // 0x100C: BIC R0, R0, #0x100 (A=0)
                    0xe12f_f000L, // 0x1010: MSR CPSR_cxsf, R0
                    0xee11_0f10L, // 0x1014: MRC SCTLR -> R0
                    0xe3c0_0007L, // 0x1018: BIC R0, R0, #0x07 (M, A, C)
                    0xe3c0_0b02L, // 0x101C: BIC R0, R0, #0x800 (Z)
                    0xe3c0_0a03L, // 0x1020: BIC R0, R0, #0x3000 (I, V)
                    0xee01_0f10L, // 0x1024: MCR SCTLR <- R0
                    0xee11_0f30L, // 0x1028: MRC ACTLR -> R0
                    0xe380_0040L, // 0x102C: ORR R0, R0, #0x40 (ACTLR.SMP = 1)
                    0xee01_0f30L, // 0x1030: MCR ACTLR <- R0
                    0xee11_0f50L, // 0x1034: MRC CPACR -> R0
                    0xe380_060fL, // 0x1038: ORR R0, R0, #0x00F00000
                    0xee01_0f50L, // 0x103C: MCR CPACR <- R0
                    0xee11_5f50L, // 0x1040: MRC CPACR -> R5
                    0xe3a0_0000L, // 0x1044: MOV R0, #0
                    0xee02_0f50L, // 0x1048: MCR TTBCR <- 0
                    0xee0d_0f30L, // 0x104C: MCR CONTEXTIDR <- 0
                    0xe3e0_1000L, // 0x1050: MVN R1, #0 (0xFFFFFFFF)
                    0xee03_1f10L, // 0x1054: MCR DACR <- 0xFFFFFFFF
                    0xe300_1000L, // 0x1058: MOVW R1, #0x0000
                    0xe34f_1fffL, // 0x105C: MOVT R1, #0xFFFF (0xFFFF0000)
                    0xee0c_1f10L, // 0x1060: MCR VBAR <- 0xFFFF0000
                    0xee07_0f15L, // 0x1064: MCR ICIALLU
                    0xee07_0fd5L, // 0x1068: MCR BPIALL
                    0xee08_0f17L, // 0x106C: MCR TLBIALL
                    0xf57f_f04fL, // 0x1070: DSB SY
                    0xf57f_f06fL, // 0x1074: ISB SY
                    0xe320_f003L, // 0x1078: WFI
                ),
            )
            dyn.setReg(0, 15, 0x1000L)
            val res = dyn.runFast(0, 256, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0xffff_0000L, dyn.getReg(0, 4), "PERIPHBASE == 0xFFFF0000")
            assertEquals(0x00d3L, dyn.getCpsr(0) and 0x1ffL, "CPSR must be SVC I=1 F=1 A=0")
            assertEquals(0L, dyn.getCp15(0, 0, 1, 0, 0) and 0x3807L, "SCTLR M/A/C/Z/I/V must be cleared")
            assertEquals(0x00f0_0000L, dyn.getReg(0, 5) and 0x00f0_0000L, "CPACR CP10/CP11 enabled")
            assertEquals(dyn.getReg(0, 5), dyn.getCp15(0, 0, 1, 0, 2), "CPACR API matches guest MRC")
            assertEquals(0L, dyn.getCp15(0, 0, 2, 0, 2), "TTBCR == 0")
            assertEquals(0L, dyn.getCp15(0, 0, 13, 0, 1), "CONTEXTIDR == 0")
            assertEquals(0xffff_ffffL, dyn.getCp15(0, 0, 3, 0, 0) and 0xffff_ffffL, "DACR == 0xFFFFFFFF")
            assertEquals(0xffff_0000L, dyn.getCp15(0, 0, 12, 0, 0) and 0xffff_ffffL, "VBAR == 0xFFFF0000")
        }
    }

    @Test
    fun fourCoreFactoryBootHoldingPenSgiWakeupAndBarrierMatchStockBootBin() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 4, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x20_0000, Dynarmic.PERM_ALL)
            val irqs = PendingIrqPort()
            dyn.writeMemory(0xb3d4, DynarmicTestHelper.words(1L))
            dyn.writeMemory(0xb3e4, DynarmicTestHelper.words(0x022bL, 0x022bL, 0x022bL, 0x022bL))

            dyn.writeMemory(
                0x0100,
                DynarmicTestHelper.words(
                    0xee10_0fb0L, // 0x0100: MRC MPIDR -> R0
                    0xe200_0003L, // 0x0104: AND R0, R0, #3
                    0xe350_0000L, // 0x0108: CMP R0, #0
                    DynarmicTestHelper.branch(0x010c, 0x0130, cond = 0x0000_0000L), // BEQ 0x0130
                    0xe59f_104cL, // 0x0110: LDR R1, [0x0164]
                    0xe591_2000L, // 0x0114: LDR R2, [R1]
                    0xe352_0001L, // 0x0118: CMP R2, #1
                    DynarmicTestHelper.branch(0x011c, 0x0148, cond = 0x1000_0000L), // BNE 0x0148
                    0xe320_f003L, // 0x0120: WFI
                    DynarmicTestHelper.branch(0x0124, 0x0114), // B 0x0114
                    0xe1a0_0000L, // 0x0128: NOP
                    0xe1a0_0000L, // 0x012C: NOP
                    0xe59f_1030L, // 0x0130: LDR R1, [0x0168]
                    0xe59f_2034L, // 0x0134: LDR R2, [0x0170]
                    0xe581_2000L, // 0x0138: STR R2, [R1]
                    0xe59f_1020L, // 0x013C: LDR R1, [0x0164]
                    0xe3a0_2000L, // 0x0140: MOV R2, #0
                    0xe581_2000L, // 0x0144: STR R2, [R1]
                    0xe59f_101cL, // 0x0148: LDR R1, [0x016C]
                    0xe3a0_2000L, // 0x014C: MOV R2, #0
                    0xe781_2100L, // 0x0150: STR R2, [R1, R0, LSL #2]
                    0xe59f_300cL, // 0x0154: LDR R3, [0x0168]
                    0xe593_3000L, // 0x0158: LDR R3, [R3]
                    0xe1a0_f003L, // 0x015C: MOV PC, R3
                    0xe1a0_0000L, // 0x0160: NOP
                    0x0000_b3d4L, // 0x0164
                    0x0000_c200L, // 0x0168
                    0x0000_b3e4L, // 0x016C
                    0x0005_7950L, // 0x0170
                ),
            )
            // Kernel entry @ 0x00057950: WFI; B .
            dyn.writeMemory(0x0005_7950, DynarmicTestHelper.words(0xe320_f003L, 0xeaff_fffeL))

            for (c in 0..3) {
                dyn.setCpsr(c, 0x0000_01d3L)
                dyn.setReg(c, 15, 0x0100L)
            }
            val cluster = DynarmicCluster(dyn, irqs)
            assertTrue(cluster.switch()) // start on core 1 so secondaries enter WFI holding pen first

            var tick = 0L
            val reachedKernel = BooleanArray(4)
            var steps = 0
            while (!reachedKernel.all { it } && steps++ < 64) {
                if (cluster.needsSwitch() && !cluster.switch()) break
                val cur = cluster.current
                val irqDeliverable = irqs.pending[cur] != null && (dyn.getCpsr(cur) and 0x80L) == 0L
                val res = dyn.runFast(cur, 64, tick, irqDeliverable)
                tick += maxOf(1L, res.executedTicks)
                cluster.account(maxOf(1L, res.executedTicks))
                if (res.stopReason == Dynarmic.STOP_WFI) {
                    if (res.stopPc == 0x0005_7950L || res.stopPc == 0x0005_7954L) {
                        reachedKernel[cur] = true
                        irqs.pending[cur] = null
                        cluster.onWfi()
                    } else {
                        // Dynarmic already advances PC past WFI (0x0120 -> 0x0124 B 0x0114)
                        cluster.onWfi()
                    }
                }
                // Once CPU0 clears 0xB3D4, broadcast SGI 0 to wake CPU1..3
                if (dyn.physicalWord(0xb3d4L) == 0L) {
                    for (c in 1..3) {
                        if (!reachedKernel[c]) irqs.pending[c] = 0
                    }
                }
            }

            assertTrue(reachedKernel.all { it }, "all 4 cores must pass holding pen + barrier and reach 0x00057950: ${reachedKernel.toList()}")
            for (c in 0..3) {
                assertEquals(0L, dyn.physicalWord(0xb3e4L + c * 4L), "barrier slot[$c] cleared")
            }
            assertEquals(0x0005_7950L, dyn.physicalWord(0xc200L), "kernel entry mailbox")
        }
    }
}
