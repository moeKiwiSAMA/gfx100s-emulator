package com.github.nyabanban.fuji.emulator.cpu

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full ARMv7-A architectural exception vector tests on native [Dynarmic]:
 * - SVC (`SWI #imm`) with banked `SP_svc`/`LR_svc`/`SPSR_svc` and `MOVS PC, LR` return to User mode
 * - Undefined instruction (`UDF`) -> UND mode (`0x1B`) + `SPSR_und` + `LR_und`
 * - Data Abort (unmapped/demand-paged VA) -> exact `DFAR` / `DFSR`, handler maps page + `SUBS PC, LR, #8` retry
 * - High Vectors (`SCTLR.V = 1`) routing to `0xFFFF0000` vs `VBAR` routing when `SCTLR.V = 0`
 */
class ArmFullExceptionsTest {
    @Test
    fun svcAndUdefSaveExactBankedSpsrLrAndReturnToUserMode() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x10000, Dynarmic.PERM_ALL)
            dyn.setAutoExceptions(Dynarmic.AUTO_EXCP_ALL)

            // Vector table at VBAR = 0x00004000:
            // +0x04 UDEF -> branch to 0x4200
            // +0x08 SVC  -> branch to 0x4100
            dyn.setCp15(0, 0, 12, 0, 0, 0x4000L) // VBAR
            dyn.setCp15(0, 0, 1, 0, 0, dyn.getCp15(0, 0, 1, 0, 0) and (1L shl 13).inv()) // SCTLR.V = 0
            dyn.writeMemory(
                0x4000,
                DynarmicTestHelper.words(
                    0xeaff_fffeL, // +0x00 Reset
                    DynarmicTestHelper.branch(0x4004, 0x4200), // +0x04 UDEF
                    DynarmicTestHelper.branch(0x4008, 0x4100), // +0x08 SVC
                ),
            )
            // SVC handler @ 0x4100: r0 += 1, r1 = CPSR, r2 = SPSR, r3 = LR, MOVS PC, LR
            dyn.writeMemory(
                0x4100,
                DynarmicTestHelper.words(
                    0xe280_0001L, // ADD R0, R0, #1
                    0xe10f_1000L, // MRS R1, CPSR
                    0xe14f_2000L, // MRS R2, SPSR
                    0xe1a0_300eL, // MOV R3, LR
                    0xe1b0_f00eL, // MOVS PC, LR
                ),
            )
            // UDEF handler @ 0x4200: r5 = 0x77, r6 = CPSR, r7 = LR, MOVS PC, LR
            dyn.writeMemory(
                0x4200,
                DynarmicTestHelper.words(
                    0xe3a0_5077L, // MOV R5, #0x77
                    0xe10f_6000L, // MRS R6, CPSR
                    0xe1a0_700eL, // MOV R7, LR
                    0xe1b0_f00eL, // MOVS PC, LR
                ),
            )
            // User code @ 0x0100: SVC #0; UDF #0; MRS R4, CPSR; WFI
            dyn.writeMemory(
                0x0100,
                DynarmicTestHelper.words(
                    0xef00_0000L, // 0x0100: SVC #0
                    0xe7f0_00f0L, // 0x0104: UDF #0
                    0xe10f_4000L, // 0x0108: MRS R4, CPSR
                    0xe320_f003L, // 0x010C: WFI
                ),
            )

            val userCpsr = 0x6000_0010L // User mode (0x10), Z=1, C=1
            dyn.setBankedReg(0, 0x13, 13, 0x3000L) // SP_svc
            dyn.setBankedReg(0, 0x1b, 13, 0x3400L) // SP_und
            dyn.setCpsr(0, userCpsr)
            dyn.setReg(0, 13, 0x2000L) // SP_usr
            dyn.setReg(0, 0, 0x1234L)
            dyn.setReg(0, 15, 0x0100L)

            val res = dyn.runFast(0, 256, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0x1235L, dyn.getReg(0, 0), "R0 incremented in SVC handler")
            assertEquals(0x13L, dyn.getReg(0, 1) and 0x1fL, "SVC handler ran in SVC mode")
            assertEquals(userCpsr, dyn.getReg(0, 2), "SPSR_svc saved user CPSR")
            assertEquals(0x0104L, dyn.getReg(0, 3), "LR_svc == SVC_PC + 4")
            assertEquals(0x77L, dyn.getReg(0, 5), "UDEF handler executed")
            assertEquals(0x1bL, dyn.getReg(0, 6) and 0x1fL, "UDEF handler ran in UND mode")
            assertEquals(0x0108L, dyn.getReg(0, 7), "LR_und == UDF_PC + 4")
            assertEquals(userCpsr, dyn.getReg(0, 4), "User CPSR restored after both handlers")
        }
    }

    @Test
    fun highVectorsSctlrVRoutesSvcTo0xFFFF0000() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x10000, Dynarmic.PERM_ALL)
            dyn.map(0xFFFF_0000L, 0x1000, Dynarmic.PERM_ALL)
            dyn.setAutoExceptions(Dynarmic.AUTO_EXCP_ALL)

            // Enable SCTLR.V (bit 13) -> high vectors at 0xFFFF0000
            dyn.setCp15(0, 0, 1, 0, 0, dyn.getCp15(0, 0, 1, 0, 0) or (1L shl 13))
            dyn.setCp15(0, 0, 12, 0, 0, 0x4000L) // VBAR ignored when V=1
            dyn.writeMemory(
                0xFFFF_0000L,
                DynarmicTestHelper.words(
                    0xeaff_fffeL, // +0x00 Reset
                    0xeaff_fffeL, // +0x04 UDEF
                    DynarmicTestHelper.branch(0xFFFF_0008L, 0xFFFF_0100L), // +0x08 SVC
                ),
            )
            dyn.writeMemory(
                0xFFFF_0100L,
                DynarmicTestHelper.words(
                    0xe3a0_0099L, // MOV R0, #0x99
                    0xe1b0_f00eL, // MOVS PC, LR
                ),
            )
            dyn.writeMemory(0x0100, DynarmicTestHelper.words(0xef00_0000L, 0xe320_f003L))

            dyn.setCpsr(0, 0x0000_0010L)
            dyn.setReg(0, 0, 0L)
            dyn.setReg(0, 15, 0x0100L)
            val res = dyn.runFast(0, 128, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0x99L, dyn.getReg(0, 0), "SVC must route to high vector 0xFFFF0008 when SCTLR.V=1")
        }
    }

    @Test
    fun dataAbortSetsExactDfarDfsrOnUnmappedSectionAndResumesAfterMapping() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        Dynarmic(lib, 1, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x0020_0000, Dynarmic.PERM_ALL)
            dyn.setAutoExceptions(0)

            // L1 table at 0x0010_0000: map 0x00000000..0x001FFFFF RWX (sections 0 and 1); leave section 2 (0x00200000) unmapped
            val l1 = 0x0010_0000L
            dyn.writePhysicalWord(l1 + 0 * 4L, 0x0000_0000L or 0x0000_0C02L)
            dyn.writePhysicalWord(l1 + 1 * 4L, 0x0010_0000L or 0x0000_0C02L)
            dyn.writePhysicalWord(l1 + 2 * 4L, 0L)

            dyn.setCp15(0, 0, 2, 0, 0, l1)          // TTBR0
            dyn.setCp15(0, 0, 2, 0, 2, 0L)          // TTBCR
            dyn.setCp15(0, 0, 3, 0, 0, 0x5555_5555L) // DACR = Client
            dyn.setCp15(0, 0, 1, 0, 0, dyn.getCp15(0, 0, 1, 0, 0) or 1L) // SCTLR.M = 1
            dyn.flushTlb()

            // Code @ 0x1000: LDR R1, [R0] (R0 = 0x0020_0040 -> section translation fault); WFI
            dyn.writeMemory(0x1000, DynarmicTestHelper.words(0xe590_1000L, 0xe320_f003L))
            dyn.setCpsr(0, 0x0000_01D3L)
            dyn.setReg(0, 0, 0x0020_0040L)
            dyn.setReg(0, 15, 0x1000L)

            val fault = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_FAULT_DABT, fault.stopReason, "Unmapped VA load must raise STOP_FAULT_DABT")
            assertEquals(0x0020_0040L, fault.faultAddr, "DFAR must match exact faulting VA")
            assertEquals(0x05L, fault.faultStatus and 0x0fL, "DFSR must indicate Section Translation Fault (0x5)")

            // Map section 2 -> PA 0x0010_0000 (where word @ +0x40 = 0xCAFEBABE), flush TLB, retry
            dyn.writePhysicalWord(0x0010_0040L, 0xCAFE_BABEL)
            dyn.writePhysicalWord(l1 + 2 * 4L, 0x0010_0000L or 0x0000_0C02L)
            dyn.flushTlb()
            dyn.setReg(0, 15, 0x1000L)

            val ok = dyn.runFast(0, 64, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, ok.stopReason)
            assertEquals(0xCAFE_BABEL, dyn.getReg(0, 1))
            assertTrue(ok.executedTicks > 0L)
        }
    }
}
