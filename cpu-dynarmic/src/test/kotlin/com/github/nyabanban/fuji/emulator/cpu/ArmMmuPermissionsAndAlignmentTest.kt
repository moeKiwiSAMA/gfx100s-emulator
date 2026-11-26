package com.github.nyabanban.fuji.emulator.cpu

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ARMv7-A Short-Descriptor MMU permission & translation tests on native [Dynarmic]:
 * - `AP=00` (no access) and unmapped null page guard trigger `STOP_DABT`
 * - Read-only section (`APX=1, AP=01` -> `0x8402`) permits reads and faults writes with Permission Fault (`DFSR=0xD`)
 * - `XN=1` (`bit 4`) faults instruction fetch with Permission Fault (`IFSR=0xD`) and unlocks cleanly after `flushTlb()`
 */
class ArmMmuPermissionsAndAlignmentTest {
    @Test
    fun readOnlyPermissionFaultAndDynamicRwUnlockWorkOnNativeMmu() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x0030_0000, Dynarmic.PERM_ALL)
            dyn.setAutoExceptions(0)

            val l1 = 0x0010_0000L
            // Section 0 (code): RWX (AP=11, domain 0)
            dyn.writePhysicalWord(l1 + 0 * 4L, 0x0000_0000L or 0x0000_0C02L)
            // Section 1 (page table): RWX
            dyn.writePhysicalWord(l1 + 1 * 4L, 0x0010_0000L or 0x0000_0C02L)
            // Section 2 (0x00200000): Privileged Read-Only (APX=1 bit 15, AP=01 bits [11:10] -> 0x8402)
            dyn.writePhysicalWord(l1 + 2 * 4L, 0x0020_0000L or 0x0000_8402L)

            dyn.writePhysicalWord(0x0020_0000L, 0x1122_3344L)
            dyn.setCp15(0, 0, 2, 0, 0, l1)
            dyn.setCp15(0, 0, 2, 0, 2, 0L)
            dyn.setCp15(0, 0, 3, 0, 0, 0x5555_5555L) // Client domain
            dyn.setCp15(0, 0, 1, 0, 0, dyn.getCp15(0, 0, 1, 0, 0) or 1L) // SCTLR.M = 1
            dyn.flushTlb()

            // Code @ 0x1000: LDR R1, [R0]; STR R2, [R0]; WFI
            dyn.writeMemory(0x1000, DynarmicTestHelper.words(0xe590_1000L, 0xe580_2000L, 0xe320_f003L))
            dyn.setCpsr(0, 0x0000_01D3L)
            dyn.setReg(0, 0, 0x0020_0000L)
            dyn.setReg(0, 2, 0x5566_7788L)
            dyn.setReg(0, 15, 0x1000L)

            val fault = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_FAULT_DABT, fault.stopReason, "Write to RO section must raise STOP_FAULT_DABT")
            assertEquals(0x1122_3344L, dyn.getReg(0, 1), "Read from RO section must succeed before write fault")
            assertEquals(0x0020_0000L, fault.faultAddr)
            assertEquals(0x0DL, fault.faultStatus and 0x0FL, "Section Permission Fault DFSR=0xD")
            assertEquals(0x1122_3344L, dyn.physicalWord(0x0020_0000L), "Faulting write must not corrupt memory")

            // Unlock section 2 to RW (APX=0, AP=11 -> 0x0C02), flush TLB, retry STR
            dyn.writePhysicalWord(l1 + 2 * 4L, 0x0020_0000L or 0x0000_0C02L)
            dyn.flushTlb()
            dyn.setReg(0, 15, 0x1004L)
            val ok = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, ok.stopReason)
            assertEquals(0x5566_7788L, dyn.physicalWord(0x0020_0000L))
        }
    }

    @Test
    fun xnSectionPrefetchAbortAndDynamicUnlockSucceed() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x0030_0000, Dynarmic.PERM_ALL)
            dyn.setAutoExceptions(0)

            val l1 = 0x0010_0000L
            dyn.writePhysicalWord(l1 + 0 * 4L, 0x0000_0000L or 0x0000_0C02L)
            dyn.writePhysicalWord(l1 + 1 * 4L, 0x0010_0000L or 0x0000_0C02L)
            // Section 2: RW + XN (bit 4 = 0x10)
            dyn.writePhysicalWord(l1 + 2 * 4L, 0x0020_0000L or 0x0000_0C12L)
            // Target code @ 0x0020_0000: MOV R0, #42; WFI
            dyn.writeMemory(0x0020_0000, DynarmicTestHelper.words(0xe3a0_002aL, 0xe320_f003L))

            dyn.setCp15(0, 0, 2, 0, 0, l1)
            dyn.setCp15(0, 0, 2, 0, 2, 0L)
            dyn.setCp15(0, 0, 3, 0, 0, 0x5555_5555L)
            dyn.setCp15(0, 0, 1, 0, 0, dyn.getCp15(0, 0, 1, 0, 0) or 1L)
            dyn.flushTlb()

            dyn.setCpsr(0, 0x0000_01D3L)
            dyn.setReg(0, 0, 0L)
            dyn.setReg(0, 15, 0x0020_0000L)

            val fault = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_FAULT_PABT, fault.stopReason, "Fetching from XN section must raise STOP_FAULT_PABT")
            assertEquals(0x0020_0000L, fault.faultAddr)
            assertEquals(0x0DL, fault.faultStatus and 0x0FL, "XN fetch must report Section Permission Fault (0xD)")

            // Clear XN bit, flush TLB and JIT block cache, re-execute
            dyn.writePhysicalWord(l1 + 2 * 4L, 0x0020_0000L or 0x0000_0C02L)
            dyn.flushTlb()
            dyn.flushBlocks()
            dyn.setReg(0, 15, 0x0020_0000L)
            val ok = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, ok.stopReason)
            assertEquals(42L, dyn.getReg(0, 0))
        }
    }
}
