package com.github.nyabanban.fuji.emulator.cpu

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CbarTest {
    @Test
    fun nativeCbarReadReturnsConfiguredPeriphbaseAcrossAllFourCoresAndConditions() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        val periphbase = 0xFFFF_0000L
        Dynarmic(lib, 4, periphbase).use { dyn ->
            dyn.map(0, 0x10000, Dynarmic.PERM_ALL)
            // MRC p15, 4, r0, c15, c0, 0 (0xEE9F0F10); WFI
            dyn.writeMemory(0x1000, DynarmicTestHelper.words(0xEE9F_0F10L, 0xE320_F003L))
            for (core in 0..3) {
                dyn.setCpsr(core, 0x0000_01D3L)
                dyn.setReg(core, 0, 0x55L)
                dyn.setReg(core, 15, 0x1000L)
                val res = dyn.runFast(core, 64, 0L, false)
                assertEquals(Dynarmic.STOP_WFI, res.stopReason, "core $core should reach WFI")
                assertEquals(periphbase, dyn.getReg(core, 0), "core $core CBAR must equal PERIPHBASE")
            }

            // Conditional MRCEQ with Z=0 must not overwrite r0; with Z=1 must write PERIPHBASE
            dyn.writeMemory(0x2000, DynarmicTestHelper.words(0x0E9F_0F10L, 0xE320_F003L))
            dyn.setCpsr(0, 0x0000_01D3L) // Z=0
            dyn.setReg(0, 0, 0x55L)
            dyn.setReg(0, 15, 0x2000L)
            assertEquals(Dynarmic.STOP_WFI, dyn.runFast(0, 64, 0L, false).stopReason)
            assertEquals(0x55L, dyn.getReg(0, 0), "MRCEQ with Z=0 must be skipped")

            dyn.setCpsr(0, 0x4000_01D3L) // Z=1
            dyn.setReg(0, 0, 0x55L)
            dyn.setReg(0, 15, 0x2000L)
            assertEquals(Dynarmic.STOP_WFI, dyn.runFast(0, 64, 0L, false).stopReason)
            assertEquals(periphbase, dyn.getReg(0, 0), "MRCEQ with Z=1 must return PERIPHBASE")
        }
    }
}
