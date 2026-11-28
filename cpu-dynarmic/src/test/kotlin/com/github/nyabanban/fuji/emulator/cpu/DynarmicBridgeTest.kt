package com.github.nyabanban.fuji.emulator.cpu

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Native ARMv7-A + VFPv4 + NEON + CP15 + Short-Descriptor MMU + 4-core LDREX/STREX + MMIO +
 * exception + GIC/Timer shadow + 40-bit Supersection verification for [Dynarmic].
 *
 * Also audits cross-compiled Windows x86_64 `fuji_dynarmic.dll` PE32+ exports & static MinGW linkage
 * when present, and gracefully skips live native tests via JUnit `Assumptions` on hosts where
 * `libfuji_dynarmic.so` / `fuji_dynarmic.dll` has not been built locally.
 */
class DynarmicBridgeTest {

    companion object {
        private const val CODE_BASE = 0x0001_0000L
        private const val DATA_BASE = 0x0002_0000L
        private const val STACK_BASE = 0x0003_0000L
        private const val MMIO_BASE = 0xFFF0_0000L
        private const val INITIAL_CPSR = 0x0000_01D3L // SVC mode, A/I/F masked, ARM state

        private val REQUIRED_DYNARMIC_EXPORTS = setOf(
            "fuji_dyn_abi_version",
            "fuji_dyn_create",
            "fuji_dyn_destroy",
            "fuji_dyn_reset_core",
            "fuji_dyn_map_ram",
            "fuji_dyn_unmap_ram",
            "fuji_dyn_map_mmio",
            "fuji_dyn_unmap_mmio",
            "fuji_dyn_set_mmio_callbacks",
            "fuji_dyn_mem_read",
            "fuji_dyn_mem_write",
            "fuji_dyn_invalidate_range",
            "fuji_dyn_clear_cache",
            "fuji_dyn_flush_tlb",
            "fuji_dyn_translate",
            "fuji_dyn_run_fast",
            "fuji_dyn_halt_run",
            "fuji_dyn_set_irq_pending",
            "fuji_dyn_deliver_irq",
            "fuji_dyn_deliver_fiq",
            "fuji_dyn_deliver_svc",
            "fuji_dyn_deliver_undef",
            "fuji_dyn_deliver_pabt",
            "fuji_dyn_deliver_dabt",
            "fuji_dyn_set_auto_exceptions",
            "fuji_dyn_set_wfe_event_mask",
            "fuji_dyn_get_wfe_event_mask",
            "fuji_dyn_set_tracepoints",
            "fuji_dyn_set_cntfrq",
            "fuji_dyn_clear_exclusive",
            "fuji_dyn_get_reg",
            "fuji_dyn_set_reg",
            "fuji_dyn_get_cpsr",
            "fuji_dyn_set_cpsr",
            "fuji_dyn_get_cp15",
            "fuji_dyn_set_cp15",
            "fuji_dyn_get_dreg",
            "fuji_dyn_set_dreg",
            "fuji_dyn_get_fpscr",
            "fuji_dyn_set_fpscr",
            "fuji_dyn_get_fpexc",
            "fuji_dyn_set_fpexc",
            "fuji_dyn_get_current_pc",
            "fuji_dyn_get_current_vtick",
            "fuji_dyn_set_timer_shadow",
            "fuji_dyn_set_timer_shadow_ptr",
            "fuji_dyn_clear_timer_shadow",
            "fuji_dyn_set_ro_mmio_word",
            "fuji_dyn_set_ro_mmio_batch",
            "fuji_dyn_add_ro_mmio_range",
            "fuji_dyn_clear_ro_mmio_ranges",
            "fuji_dyn_remove_ro_mmio_word",
            "fuji_dyn_clear_ro_mmio_range",
            "fuji_dyn_clear_ro_mmio",
            "fuji_dyn_set_live_state_buf",
            "fuji_dyn_run_cluster_fast",
            "fuji_dyn_get_mmio_stats",
            "fuji_dyn_reset_mmio_stats",
            "fuji_dyn_set_smp_host_threads",
            "fuji_dyn_get_smp_host_threads",
            "fuji_dyn_register_mmio_mirror",
            "fuji_dyn_unregister_mmio_mirror",
            "fuji_dyn_clear_mmio_mirrors",
        )
    }

    private fun repoRoot(): Path {
        var cur = Path.of("").toAbsolutePath().normalize()
        while (cur.parent != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts"))) return cur
            cur = cur.parent
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    private fun findDynarmicLib(): Path? {
        val env = System.getenv("FUJI_DYNARMIC_LIBRARY")?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (env != null && Files.isRegularFile(env)) return env
        val root = repoRoot()
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val name = if (isWin) "fuji_dynarmic.dll" else "libfuji_dynarmic.so"
        val buildDir = if (isWin) "build-win64" else "build-linux"
        return listOf(
            root.resolve(".tools/dynarmic-v1/$buildDir/$name"),
            root.resolve(".tools/dynarmic-v1/build/$name"),
            root.resolve("runtime/native/$name"),
        ).firstOrNull { Files.isRegularFile(it) }
    }

    private fun wordsToBytes(vararg words: Int): ByteArray {
        val buf = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (w in words) buf.putInt(w)
        return buf.array()
    }

    private fun movImm(rd: Int, imm8: Int): Int = (0xE3A00000L or (rd.toLong() shl 12) or (imm8.toLong() and 0xFF)).toInt()
    private fun movw(rd: Int, imm16: Int): Int {
        val u = imm16 and 0xFFFF
        return (0xE3000000L or ((u and 0xF000).toLong() shl 4) or (rd.toLong() shl 12) or (u and 0xFFF).toLong()).toInt()
    }
    private fun movt(rd: Int, imm16: Int): Int {
        val u = imm16 and 0xFFFF
        return (0xE3400000L or ((u and 0xF000).toLong() shl 4) or (rd.toLong() shl 12) or (u and 0xFFF).toLong()).toInt()
    }
    private fun addReg(rd: Int, rn: Int, rm: Int): Int = (0xE0800000L or (rn.toLong() shl 16) or (rd.toLong() shl 12) or rm.toLong()).toInt()
    private fun subsImm(rd: Int, rn: Int, imm8: Int): Int = (0xE2500000L or (rn.toLong() shl 16) or (rd.toLong() shl 12) or (imm8.toLong() and 0xFF)).toInt()
    private fun eorLsl(rd: Int, rn: Int, rm: Int, shift: Int): Int = (0xE0200000L or (rn.toLong() shl 16) or (rd.toLong() shl 12) or (shift.toLong() shl 7) or rm.toLong()).toInt()
    private fun orrRor(rd: Int, rn: Int, rm: Int, shift: Int): Int = (0xE1800000L or (rn.toLong() shl 16) or (rd.toLong() shl 12) or (shift.toLong() shl 7) or 0x60L or rm.toLong()).toInt()
    private fun mul(rd: Int, rn: Int, rm: Int): Int = (0xE0000090L or (rd.toLong() shl 16) or (rm.toLong() shl 8) or rn.toLong()).toInt()
    private fun umull(rdlo: Int, rdhi: Int, rn: Int, rm: Int): Int = (0xE0800090L or (rdhi.toLong() shl 16) or (rdlo.toLong() shl 12) or (rm.toLong() shl 8) or rn.toLong()).toInt()
    private fun clz(rd: Int, rm: Int): Int = (0xE16F0F10L or (rd.toLong() shl 12) or rm.toLong()).toInt()
    private fun rev(rd: Int, rm: Int): Int = (0xE6BF0F30L or (rd.toLong() shl 12) or rm.toLong()).toInt()
    private fun strImm(rt: Int, rn: Int, off: Int): Int = (0xE5800000L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or (off.toLong() and 0xFFF)).toInt()
    private fun ldrImm(rt: Int, rn: Int, off: Int): Int = (0xE5900000L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or (off.toLong() and 0xFFF)).toInt()
    private fun strhImm(rt: Int, rn: Int, off: Int): Int = (0xE1C000B0L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or ((off and 0xF0).toLong() shl 4) or (off and 0xF).toLong()).toInt()
    private fun ldrhImm(rt: Int, rn: Int, off: Int): Int = (0xE1D000B0L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or ((off and 0xF0).toLong() shl 4) or (off and 0xF).toLong()).toInt()
    private fun strbImm(rt: Int, rn: Int, off: Int): Int = (0xE5C00000L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or (off.toLong() and 0xFFF)).toInt()
    private fun ldrbImm(rt: Int, rn: Int, off: Int): Int = (0xE5D00000L or (rn.toLong() shl 16) or (rt.toLong() shl 12) or (off.toLong() and 0xFFF)).toInt()
    private fun bneRelWords(deltaWords: Int): Int = (0x1A000000L or (deltaWords.toLong() and 0x00FFFFFFL)).toInt()
    private fun mrc(opc1: Int, rt: Int, crn: Int, crm: Int, opc2: Int): Int =
        (0xEE100F10L or (opc1.toLong() shl 21) or (crn.toLong() shl 16) or (rt.toLong() shl 12) or (opc2.toLong() shl 5) or crm.toLong()).toInt()
    private fun mcr(opc1: Int, rt: Int, crn: Int, crm: Int, opc2: Int): Int =
        (0xEE000F10L or (opc1.toLong() shl 21) or (crn.toLong() shl 16) or (rt.toLong() shl 12) or (opc2.toLong() shl 5) or crm.toLong()).toInt()
    private fun ldrex(rt: Int, rn: Int): Int = (0xE1900F9FL or (rn.toLong() shl 16) or (rt.toLong() shl 12)).toInt()
    private fun strex(rd: Int, rt: Int, rn: Int): Int = (0xE1800F90L or (rn.toLong() shl 16) or (rd.toLong() shl 12) or rt.toLong()).toInt()
    private fun svc(imm: Int): Int = (0xEF000000L or (imm.toLong() and 0x00FFFFFFL)).toInt()

    private val WFI_INSN = 0xE320F003.toInt()
    private val NOP_INSN = 0xE320F000.toInt()
    private val MOVS_PC_LR = 0xE1B0F00E.toInt()
    private val SUBS_PC_LR_4 = 0xE25EF004.toInt()
    private val CLREX_INSN = 0xF57FF01F.toInt()

    @Test
    fun cpuEngineKindParsingCoversAllModesAndRejectsUnknown() {
        assertEquals(CpuEngineKind.DYNARMIC, CpuEngineKind.parse(null))
        assertEquals(CpuEngineKind.DYNARMIC, CpuEngineKind.parse(""))
        assertEquals(CpuEngineKind.DYNARMIC, CpuEngineKind.parse("dynarmic"))
        assertEquals(CpuEngineKind.DYNARMIC, CpuEngineKind.parse("  DYNARMIC "))
        assertEquals(CpuEngineKind.AUTO, CpuEngineKind.parse("auto"))
        assertThrows(IllegalArgumentException::class.java) { CpuEngineKind.parse("unicorn") }
        assertThrows(IllegalArgumentException::class.java) { CpuEngineKind.parse("qemu-tcg") }
        assertThrows(IllegalArgumentException::class.java) { CpuEngineKind.parse(42) }
    }

    @Test
    fun win64DynarmicDllExportsAndStaticMinGwAudit() {
        val root = repoRoot()
        val dll = listOfNotNull(
            System.getenv("FUJI_WIN64_DYNARMIC_DLL")?.takeIf { it.isNotBlank() }?.let(Path::of),
            root.resolve(".tools/dynarmic-v1/build-win64/fuji_dynarmic.dll"),
            root.resolve(".tools/dynarmic-v1-win64/build/fuji_dynarmic.dll"),
        ).firstOrNull { Files.isRegularFile(it) }
        assumeTrue(dll != null, "Skipping Win64 fuji_dynarmic.dll PE32+ audit: DLL not built on this host")

        val summary = Win64PeCoffInspector.inspect(checkNotNull(dll))
        assertEquals(Win64PeCoffInspector.MACHINE_AMD64, summary.machine, "Expected AMD64 PE32+ DLL")
        assertEquals(Win64PeCoffInspector.OPTIONAL_MAGIC_PE32_PLUS, summary.optionalMagic, "Expected PE32+ header")
        val missing = REQUIRED_DYNARMIC_EXPORTS - summary.exports
        assertTrue(missing.isEmpty(), "Missing required fuji_dyn_* exports in $dll: $missing")
        val forbidden = summary.imports.map { it.lowercase() }.filter { it !in Win64PeCoffInspector.ALLOWED_SYSTEM_IMPORTS }
        assertTrue(forbidden.isEmpty(), "Unexpected non-system DLL imports in $dll (MinGW runtime must be static): $forbidden")
    }

    @Test
    fun arm32AluBarrelShiftMulBitopsMemoryAndLoopExecuteExact() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping live ARM32 ALU test: libfuji_dynarmic missing")

        // Loop sum(1..100) = 5050 + ALU/shift/mul/umull/clz/rev + word/halfword/byte memory ops, ends at WFI
        val code = intArrayOf(
            movImm(0, 0),                    // r0 = 0 (sum)
            movImm(1, 100),                  // r1 = 100 (counter)
            // loop:
            addReg(0, 0, 1),                 // r0 += r1
            subsImm(1, 1, 1),                // r1 -= 1 (sets flags)
            bneRelWords(-4),                 // if r1 != 0 -> back 2 insns
            movw(2, 0xBEEF),
            movt(2, 0xDEAD),                 // r2 = 0xDEADBEEF
            rev(3, 2),                       // r3 = 0xEFBEADDE
            clz(4, 0),                       // r4 = clz(5050) = 19
            eorLsl(5, 2, 0, 5),              // r5 = r2 ^ (r0 << 5)
            orrRor(6, 3, 5, 7),              // r6 = r3 | ror(r5, 7)
            mul(7, 0, 4),                    // r7 = 5050 * 19 = 95950
            umull(8, 9, 2, 3),               // (r9:r8) = 0xDEADBEEF * 0xEFBEADDE
            movw(10, 0x0000),
            movt(10, 0x0002),                // r10 = DATA_BASE (0x00020000)
            strImm(8, 10, 0),                // [DATA+0] = r8
            strImm(9, 10, 4),                // [DATA+4] = r9
            strhImm(0, 10, 8),               // [DATA+8] = (u16)5050
            strbImm(4, 10, 10),              // [DATA+10] = (u8)19
            ldrImm(11, 10, 0),               // r11 = [DATA+0]
            ldrhImm(12, 10, 8),              // r12 = [DATA+8]
            ldrbImm(1, 10, 10),              // r1 = [DATA+10]
            WFI_INSN,                        // stop marker
        )

        val expectedR2 = 0xDEADBEEFL
        val expectedR3 = 0xEFBEADDEL
        val expectedR4 = Integer.numberOfLeadingZeros(5050).toLong()
        val expectedR5 = (expectedR2 xor (5050L shl 5)) and 0xFFFF_FFFFL
        val expectedR6 = (expectedR3 or Integer.rotateRight(expectedR5.toInt(), 7).toUInt().toLong()) and 0xFFFF_FFFFL
        val prod = expectedR2.toULong() * expectedR3.toULong()
        val expectedR8 = (prod and 0xFFFF_FFFFUL).toLong()
        val expectedR9 = (prod shr 32).toLong()

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.map(DATA_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.map(STACK_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*code))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 13, STACK_BASE + 0x8000)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 100_000, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason, "Expected WFI stop")
            assertEquals(5050L, d.getReg(0, 0), "Sum 1..100 must be 5050")
            assertEquals(expectedR4, d.getReg(0, 1), "LDRB readback mismatch")
            assertEquals(expectedR2, d.getReg(0, 2))
            assertEquals(expectedR3, d.getReg(0, 3))
            assertEquals(expectedR4, d.getReg(0, 4))
            assertEquals(expectedR5, d.getReg(0, 5))
            assertEquals(expectedR6, d.getReg(0, 6))
            assertEquals(5050L * expectedR4, d.getReg(0, 7))
            assertEquals(expectedR8, d.getReg(0, 8))
            assertEquals(expectedR9, d.getReg(0, 9))
            assertEquals(expectedR8, d.getReg(0, 11))
            assertEquals(5050L, d.getReg(0, 12))
            assertEquals(expectedR8, d.physicalWord(DATA_BASE))
            assertEquals(expectedR9, d.physicalWord(DATA_BASE + 4))
            assertEquals(5050L, d.physicalHalfword(DATA_BASE + 8))
            assertEquals(expectedR4, d.physicalByte(DATA_BASE + 10))
        }
    }

    @Test
    fun vfpv4AndNeonSimdArithmeticExecuteExact() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping live VFP/NEON test: libfuji_dynarmic missing")

        val f1Bits = java.lang.Float.floatToRawIntBits(3.25f)
        val f2Bits = java.lang.Float.floatToRawIntBits(-1.5f)
        val code = intArrayOf(
            // Enable CP10/CP11 in CPACR and FPEXC.EN = 1
            movw(0, 0x0000),
            movt(0, 0x00F0),                 // r0 = 0x00F00000
            mcr(0, 0, 1, 0, 2),              // MCR p15, 0, r0, c1, c0, 2 (CPACR)
            movt(0, 0x4000),                 // r0 = 0x40000000
            0xEEE80A10.toInt(),              // VMSR FPEXC, r0
            // Load float32 inputs into s0, s1
            movw(0, f1Bits and 0xFFFF),
            movt(0, (f1Bits ushr 16) and 0xFFFF),
            movw(1, f2Bits and 0xFFFF),
            movt(1, (f2Bits ushr 16) and 0xFFFF),
            0xEE000A10.toInt(),              // VMOV s0, r0
            0xEE001A90.toInt(),              // VMOV s1, r1
            0xEE301A20.toInt(),              // VADD.F32 s2, s0, s1 (1.75f)
            0xEE611A20.toInt(),              // VMUL.F32 s3, s2, s1 (-2.625f)
            0xEE112A10.toInt(),              // VMOV r2, s2
            0xEE113A90.toInt(),              // VMOV r3, s3
            // NEON 64-bit VADD.I32 d2, d0, d1
            movw(4, 0x0010),
            movw(5, 0x0020),
            movw(6, 0x0003),
            movw(7, 0x0007),
            0xEC454B10.toInt(),              // VMOV d0, r4, r5
            0xEC476B11.toInt(),              // VMOV d1, r6, r7
            0xF2202801.toInt(),              // VADD.I32 d2, d0, d1 -> {0x13, 0x27}
            0xEC598B12.toInt(),              // VMOV r8, r9, d2
            WFI_INSN,
        )

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*code))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 10_000, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(java.lang.Float.floatToRawIntBits(1.75f).toLong() and 0xFFFFFFFFL, d.getReg(0, 2))
            assertEquals(java.lang.Float.floatToRawIntBits(-2.625f).toLong() and 0xFFFFFFFFL, d.getReg(0, 3))
            assertEquals(0x13L, d.getReg(0, 8))
            assertEquals(0x27L, d.getReg(0, 9))
            assertEquals((0x27L shl 32) or 0x13L, d.getDreg(0, 2))
        }
    }

    @Test
    fun cp15SystemRegsMmuShortDescriptorTlbAndMulticoreLdrexStrexWork() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping live CP15/MMU/LDREX test: libfuji_dynarmic missing")

        // 1. CP15 CBAR + MPIDR across 4 cores + VBAR/TPIDR
        val cp15Code = intArrayOf(
            mrc(4, 0, 15, 0, 0),             // r0 = CBAR (0xFFFF0000)
            mrc(0, 1, 0, 0, 5),              // r1 = MPIDR (0x80000000 | core)
            movw(2, 0x1234),
            movt(2, 0xABCD),
            mcr(0, 2, 13, 0, 2),             // TPIDRURW = 0xABCD1234
            mrc(0, 3, 13, 0, 2),             // r3 = TPIDRURW
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*cp15Code))
            for (core in 0..3) {
                d.setCpsr(core, INITIAL_CPSR)
                d.setReg(core, 15, CODE_BASE)
                val res = d.runFast(core, 1000, 0L, false)
                assertEquals(Dynarmic.STOP_WFI, res.stopReason)
                assertEquals(0xFFFF_0000L, d.getReg(core, 0), "CBAR mismatch on core $core")
                assertEquals(0x8000_0000L or core.toLong(), d.getReg(core, 1), "MPIDR mismatch on core $core")
                assertEquals(0xABCD_1234L, d.getReg(core, 3), "TPIDRURW mismatch on core $core")
            }
        }

        // 2. ARMv7 Short-Descriptor MMU: 1 MiB section + 4 KiB small page remap
        val l1Base = 0x0010_0000L
        val l2Base = 0x0011_0000L
        val physData = 0x0020_0000L
        val mmuCode = intArrayOf(
            movw(0, 0x0000), movt(0, 0x0010), // r0 = TTBR0 = 0x00100000
            mcr(0, 0, 2, 0, 0),               // TTBR0
            movImm(1, 0),
            mcr(0, 1, 2, 0, 2),               // TTBCR = 0
            movw(2, 0x5555), movt(2, 0x5555),
            mcr(0, 2, 3, 0, 0),               // DACR = 0x55555555 (all client)
            mrc(0, 3, 1, 0, 0),               // r3 = SCTLR
            0xE3833001.toInt(),               // ORR r3, r3, #1 (SCTLR.M = 1)
            mcr(0, 3, 1, 0, 0),               // enable MMU!
            // Write via remapped 1 MiB VA 0x40000000 -> PA 0x00200000
            movw(4, 0x0000), movt(4, 0x4000),
            movw(5, 0xCAFE), movt(5, 0x1234),
            strImm(5, 4, 0),
            ldrImm(6, 4, 0),
            // Write via remapped 4 KiB L2 page VA 0x50000000 -> PA 0x00210000
            movw(7, 0x0000), movt(7, 0x5000),
            movw(8, 0xBEEF), movt(8, 0x5678),
            strImm(8, 7, 16),
            ldrImm(9, 7, 16),
            // TLBIALL
            mcr(0, 1, 8, 7, 0),
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(0x0000_0000L, 0x0030_0000L, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*mmuCode))
            // L1[0x000]: identity 1 MiB section (PA=0x00000000, AP=011, type=0b10 -> 0x00000C02)
            d.writePhysicalWord(l1Base + 0x000 * 4L, 0x0000_0C02L)
            // L1[0x001]: identity 1 MiB section for L1/L2 tables
            d.writePhysicalWord(l1Base + 0x001 * 4L, 0x0010_0C02L)
            // L1[0x400]: remapped 1 MiB section VA 0x40000000 -> PA 0x00200000
            d.writePhysicalWord(l1Base + 0x400 * 4L, physData or 0x0C02L)
            // L1[0x500]: coarse page table at l2Base (type=0b01)
            d.writePhysicalWord(l1Base + 0x500 * 4L, l2Base or 0x0001L)
            // L2[0]: 4 KiB small page -> PA 0x00210000 (AP=011, type=0b10 -> 0x00000032)
            d.writePhysicalWord(l2Base + 0L, 0x0021_0032L)

            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 10_000, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0x1234_CAFEL, d.getReg(0, 6))
            assertEquals(0x5678_BEEFL, d.getReg(0, 9))
            assertEquals(0x1234_CAFEL, d.physicalWord(0x0020_0000L))
            assertEquals(0x5678_BEEFL, d.physicalWord(0x0021_0010L))
            assertEquals(0x0020_0000L, d.translate(0, 0x4000_0000L, 1))
            assertEquals(0x0021_0010L, d.translate(0, 0x5000_0010L, 1))
        }

        // 3. 4-core LDREX / STREX cross-core invalidation
        val c0Part1 = intArrayOf(
            movw(0, 0x0000), movt(0, 0x0002), // r0 = DATA_BASE
            ldrex(1, 0),                      // core 0 reserves [DATA_BASE]
            WFI_INSN,
            movImm(3, 0x77),
            strex(2, 3, 0),                   // must FAIL (r2 = 1) because core 1 wrote!
            ldrex(4, 0),
            movImm(5, 0x88),
            strex(6, 5, 0),                   // must SUCCEED (r6 = 0)
            CLREX_INSN,
            WFI_INSN,
        )
        val c1Write = intArrayOf(
            movw(0, 0x0000), movt(0, 0x0002),
            movImm(1, 0x42),
            strImm(1, 0, 0),                  // core 1 invalidates core 0 exclusive monitor!
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.map(DATA_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writePhysicalWord(DATA_BASE, 0x11L)
            d.writeMemory(CODE_BASE, wordsToBytes(*c0Part1))
            d.writeMemory(CODE_BASE + 0x1000L, wordsToBytes(*c1Write))
            d.setCpsr(0, INITIAL_CPSR); d.setReg(0, 15, CODE_BASE)
            d.setCpsr(1, INITIAL_CPSR); d.setReg(1, 15, CODE_BASE + 0x1000L)

            assertEquals(Dynarmic.STOP_WFI, d.runFast(0, 1000, 0L, false).stopReason)
            assertEquals(0x11L, d.getReg(0, 1))
            assertEquals(Dynarmic.STOP_WFI, d.runFast(1, 1000, 10L, false).stopReason)
            assertEquals(0x42L, d.physicalWord(DATA_BASE))
            assertEquals(Dynarmic.STOP_WFI, d.runFast(0, 1000, 20L, false).stopReason)
            assertEquals(1L, d.getReg(0, 2), "STREX after cross-core store must fail (return 1)")
            assertEquals(0x42L, d.getReg(0, 4), "Second LDREX must observe core 1 store")
            assertEquals(0L, d.getReg(0, 6), "Uncontested STREX must succeed (return 0)")
            assertEquals(0x88L, d.physicalWord(DATA_BASE), "Final RAM word must hold 0x88")
        }
    }

    @Test
    fun mmioUpcallAndSvcIrqArchitecturalExceptionsWork() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping live MMIO/exception test: libfuji_dynarmic missing")

        val mmioLog = ArrayList<String>()
        val mmioRegs = HashMap<Long, Long>()
        mmioRegs[MMIO_BASE + 0x00] = 0xA5A5_5A5AL

        val vbarBase = 0x0004_0000L
        val mainCode = intArrayOf(
            // Set VBAR = 0x00040000
            movw(0, 0x0000), movt(0, 0x0004),
            mcr(0, 0, 12, 0, 0),
            // MMIO write + read
            movw(1, 0x0000), movt(1, 0xFFF0), // r1 = MMIO_BASE
            movw(2, 0xCAFE), movt(2, 0xBABE),
            strImm(2, 1, 4),                  // write MMIO+4 = 0xBABECAFE
            ldrImm(3, 1, 0),                  // read MMIO+0 -> 0xA5A55A5A
            ldrImm(4, 1, 4),                  // read MMIO+4 -> 0xBABECAFE
            // SVC #0x2A -> jumps to VBAR+0x08 which sets r5 = 0x99 and returns via MOVS pc, lr
            svc(0x2A),
            WFI_INSN,
            // Resume after IRQ delivery -> r6 set by IRQ handler
            WFI_INSN,
        )
        // Vector table at vbarBase:
        // +0x00 reset, +0x04 undef, +0x08 svc -> b +0x40, ..., +0x18 irq -> b +0x60
        val vectors = IntArray(32) { NOP_INSN }
        vectors[2] = (0xEA000000L or 14L).toInt() // +0x08 -> +0x48 (index 18)
        vectors[6] = (0xEA000000L or 14L).toInt() // +0x18 -> +0x58 (index 22)
        // SVC handler at index 18 (+0x48)
        vectors[18] = movImm(5, 0x99)
        vectors[19] = MOVS_PC_LR
        // IRQ handler at index 22 (+0x58)
        vectors[22] = movImm(6, 0x77)
        vectors[23] = SUBS_PC_LR_4

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.map(vbarBase, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(MMIO_BASE, 0x1000)
            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    val v = mmioRegs[physicalAddress] ?: 0L
                    mmioLog.add("R:${physicalAddress.toString(16)}:$size:${v.toString(16)}")
                    return v
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    mmioRegs[physicalAddress] = value
                    mmioLog.add("W:${physicalAddress.toString(16)}:$size:${value.toString(16)}")
                }
            })
            d.writeMemory(CODE_BASE, wordsToBytes(*mainCode))
            d.writeMemory(vbarBase, wordsToBytes(*vectors))
            d.setAutoExceptions(Dynarmic.AUTO_EXCP_ALL)
            d.setCpsr(0, 0x0000_0053L) // SVC mode, IRQ unmasked (I=0), F=1
            d.setReg(0, 15, CODE_BASE)

            val res1 = d.runFast(0, 10_000, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res1.stopReason)
            assertEquals(0xA5A5_5A5AL, d.getReg(0, 3), "MMIO read 0 mismatch")
            assertEquals(0xBABE_CAFEL, d.getReg(0, 4), "MMIO read 4 mismatch")
            assertEquals(0x99L, d.getReg(0, 5), "SVC handler did not run")
            assertTrue(mmioLog.any { it.startsWith("W:fff00004:4:babecafe") }, "Missing MMIO write log: $mmioLog")

            // Deliver IRQ -> should vector to VBAR+0x18, set r6=0x77, return to second WFI
            assertTrue(d.deliverIrq(0), "deliverIrq should succeed when CPSR.I == 0")
            assertEquals(vbarBase + 0x18L, d.getReg(0, 15), "PC must vector to VBAR+0x18")
            val res2 = d.runFast(0, 10_000, res1.virtualTick, false)
            assertEquals(Dynarmic.STOP_WFI, res2.stopReason)
            assertEquals(0x77L, d.getReg(0, 6), "IRQ handler did not run")
        }
    }

    @Test
    fun ldrdAndStrdOnMmioSplitIntoAligned32BitAccesses() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping live 64-bit MMIO split test: libfuji_dynarmic missing")

        val seen = ArrayList<String>()
        val regs = hashMapOf(
            MMIO_BASE + 0x00L to 0x1122_3344L,
            MMIO_BASE + 0x04L to 0x5566_7788L,
        )
        // ARM32:
        //   movw r0, #0x0000; movt r0, #0xFFF0
        //   ldrd r2, r3, [r0, #0]  -> 0xE1C020D0
        //   strd r2, r3, [r0, #8]  -> 0xE1C020F8
        //   wfi
        val code = intArrayOf(
            movw(0, 0x0000),
            movt(0, 0xFFF0),
            0xE1C020D0.toInt(),
            0xE1C020F8.toInt(),
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(MMIO_BASE, 0x1000)
            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    assertEquals(4, size, "8-byte MMIO read must split into 32-bit reads (addr=0x${physicalAddress.toString(16)})")
                    val v = regs[physicalAddress] ?: 0L
                    seen += "R:${physicalAddress.toString(16)}:$size:${v.toString(16)}"
                    return v
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    assertEquals(4, size, "8-byte MMIO write must split into 32-bit writes (addr=0x${physicalAddress.toString(16)})")
                    regs[physicalAddress] = value
                    seen += "W:${physicalAddress.toString(16)}:$size:${value.toString(16)}"
                }
            })
            d.writeMemory(CODE_BASE, wordsToBytes(*code))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 10_000, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0x1122_3344L, d.getReg(0, 2))
            assertEquals(0x5566_7788L, d.getReg(0, 3))
            assertEquals(0x1122_3344L, regs[MMIO_BASE + 0x08L])
            assertEquals(0x5566_7788L, regs[MMIO_BASE + 0x0CL])
            assertEquals(
                listOf("R:fff00000:4:11223344", "R:fff00004:4:55667788", "W:fff00008:4:11223344", "W:fff0000c:4:55667788"),
                seen,
            )
        }
    }

    @Test
    fun nativeTimerShadowAndRoMmioFastpathsMatchReferenceAndInvalidateOnWrite() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping native MMIO fastpath parity test: libfuji_dynarmic missing")

        val timerBase = 0xFFF3_C300L
        val gpioBase = 0xFFF4_1000L
        val upcallLog = ArrayList<String>()
        val backing = hashMapOf(
            gpioBase + 0x14L to 0x0000_A55AL,
            gpioBase + 0x00L to 0x1234_5678L,
        )

        // Program:
        //   r0 = 0xFFF3C300 (timerBase), r1 = 0xFFF41000 (gpioBase)
        //   r2 = [r0 + 0x00] (ctrl mask: ch7 enabled + ch0 disabled -> 0x80)
        //   r3 = [r0 + 0x10] (ch0 disabled stored = 0xCAFEBABE)
        //   r4 = [r0 + 0x48] (ch7 live countdown at now < deadline)
        //   r5 = [r0 + 0x4C] (ch7 reload = 999)
        //   r6 = [r1 + 0x14] (RO-MMIO GPIO input = 0x0000A55A, zero upcall)
        //   r7 = [r1 + 0x00] (RO-MMIO GPIO reg0 = 0x12345678, zero upcall)
        //   movw r8, #0xBEEF; movt r8, #0xDEAD
        //   str r8, [r1 + 0x00] (write invalidates RO slot & calls Java upcall!)
        //   r9 = [r1 + 0x00] (read after write MUST hit Java upcall and return 0xDEADBEEF!)
        //   wfi
        val code = intArrayOf(
            movw(0, 0xC300),
            movt(0, 0xFFF3),
            movw(1, 0x1000),
            movt(1, 0xFFF4),
            ldrImm(2, 0, 0x00),
            ldrImm(3, 0, 0x10),
            ldrImm(4, 0, 0x48),
            ldrImm(5, 0, 0x4C),
            ldrImm(6, 1, 0x14),
            ldrImm(7, 1, 0x00),
            movw(8, 0xBEEF),
            movt(8, 0xDEAD),
            strImm(8, 1, 0x00),
            ldrImm(9, 1, 0x00),
            WFI_INSN,
        )

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsNativeMmioFastpaths(), "Loaded libfuji_dynarmic does not export native MMIO fastpaths")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(0xFFF3_C000L, 0x1000)
            d.mmio(gpioBase, 0x1000)
            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    val v = backing[physicalAddress] ?: 0L
                    upcallLog += "R:${physicalAddress.toString(16)}:${v.toString(16)}"
                    return v
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    backing[physicalAddress] = value and 0xffff_ffffL
                    upcallLog += "W:${physicalAddress.toString(16)}:${(value and 0xffff_ffffL).toString(16)}"
                }
            })

            val stored = LongArray(8)
            val reload = LongArray(8)
            val deadline = LongArray(8)
            val enabled = IntArray(8)
            val hasDeadline = IntArray(8)
            stored[0] = 0xCAFE_BABEL
            enabled[0] = 0
            hasDeadline[0] = 0
            stored[7] = 500L
            reload[7] = 999L
            deadline[7] = 1501L // at base vtick=1000: value = 1501 - 1000 - 1 = 500
            enabled[7] = 1
            hasDeadline[7] = 1
            d.setTimerShadow(timerBase, true, stored, reload, deadline, enabled, hasDeadline)
            assertTrue(d.setRoMmioWord(gpioBase + 0x14L, 0x0000_A55AL))
            assertTrue(d.setRoMmioWord(gpioBase + 0x00L, 0x1234_5678L))
            d.resetMmioStats()

            d.writeMemory(CODE_BASE, wordsToBytes(*code))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 10_000, 1000L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0x80L, d.getReg(0, 2), "Timer control mask mismatch")
            assertEquals(0xCAFE_BABEL, d.getReg(0, 3), "Disabled timer ch0 stored value mismatch")
            assertEquals(500L, d.getReg(0, 4), "Running timer ch7 countdown mismatch")
            assertEquals(999L, d.getReg(0, 5), "Timer ch7 reload mismatch")
            assertEquals(0x0000_A55AL, d.getReg(0, 6), "RO-MMIO GPIO input mismatch")
            assertEquals(0x1234_5678L, d.getReg(0, 7), "RO-MMIO GPIO reg0 mismatch")
            assertEquals(0xDEAD_BEEFL, d.getReg(0, 9), "Post-write read must observe invalidated updated value")

            val stats = Dynarmic.MmioStats()
            d.readMmioStats(stats)
            assertEquals(4L, stats.timerFastHits, "Expected 4 C++ timer fastpath hits")
            assertEquals(2L, stats.roMmioFastHits, "Expected 2 C++ RO-MMIO fastpath hits")
            assertEquals(1L, stats.upcallWrites, "Expected 1 Java MMIO write upcall")
            assertEquals(1L, stats.upcallReads, "Expected 1 Java MMIO read upcall (after invalidation)")
            assertEquals(6L, res.fastMmioHits, "RunResult.fastMmioHits mismatch")
            assertEquals(listOf("W:fff41000:deadbeef", "R:fff41000:deadbeef"), upcallLog)
        }
    }

    @Test
    fun roMmioRangesBatchLiveStateAndSharedTimerPtrWork() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping Phase B native fastpath test: libfuji_dynarmic missing")

        val zeroRangeBase = 0xFFF1_8000L
        val bufRangeBase = 0xFFF1_1000L
        val upcallMmio = 0xFFF5_0000L
        val upcalls = ArrayList<String>()
        var observedVtickInUpcall = -1L
        var observedPcInUpcall = -1L
        var observedCpsrInUpcall = -1L

        // Program:
        //   r0 = 0xFFF18000 (zero RO range)
        //   r1 = 0xFFF11000 (buffer RO range)
        //   r2 = [r0 + 0x10] (0 from zero range, zero upcall)
        //   r3 = [r1 + 0x04] (0xAABBCCDD from buffer range, zero upcall)
        //   r4 = ldrb [r1 + 0x05] (0xCC from buffer range, zero upcall)
        //   r5 = ldrh [r1 + 0x06] (0xAABB from buffer range, zero upcall)
        //   r6 = 0xFFF50000 (unshadowed MMIO -> triggers upcall, checks liveStateBuf!)
        //   r7 = [r6 + 0x00] (upcall read returns 0x13572468)
        //   str r7, [r1 + 0x04] (write to buffer range -> auto-invalidates range + upcall)
        //   r8 = [r1 + 0x04] (post-write read -> hits upcall, returns 0x99887766)
        //   wfi
        val code = intArrayOf(
            movw(0, 0x8000), movt(0, 0xFFF1),
            movw(1, 0x1000), movt(1, 0xFFF1),
            ldrImm(2, 0, 0x10),
            ldrImm(3, 1, 0x04),
            ldrbImm(4, 1, 0x05),
            ldrhImm(5, 1, 0x06),
            movw(6, 0x0000), movt(6, 0xFFF5),
            ldrImm(7, 6, 0x00),
            strImm(7, 1, 0x04),
            ldrImm(8, 1, 0x04),
            WFI_INSN,
        )

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsRoMmioRanges(), "Loaded libfuji_dynarmic does not export RO MMIO ranges")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(0xFFF1_0000L, 0x10000)
            d.mmio(upcallMmio, 0x1000)

            val rangeBuf = ByteArray(0x100)
            rangeBuf[4] = 0xDD.toByte()
            rangeBuf[5] = 0xCC.toByte()
            rangeBuf[6] = 0xBB.toByte()
            rangeBuf[7] = 0xAA.toByte()
            assertTrue(d.addRoMmioZeroRange(zeroRangeBase, 0x1000L))
            assertTrue(d.addRoMmioBufferRange(bufRangeBase, rangeBuf))

            // Also verify batch RO-MMIO API
            val batchAddrs = intArrayOf(0xFFF5_0100.toInt(), 0xFFF5_0104.toInt())
            val batchVals = intArrayOf(0x1111_2222, 0x3333_4444)
            assertEquals(2, d.setRoMmioBatch(batchAddrs, batchVals, 2))

            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    upcalls += "R:${physicalAddress.toString(16)}:$size"
                    if (physicalAddress == upcallMmio) {
                        observedVtickInUpcall = d.currentVirtualTick()
                        observedPcInUpcall = d.currentPc(0)
                        observedCpsrInUpcall = d.getCpsr(0)
                        return 0x1357_2468L
                    }
                    if (physicalAddress == bufRangeBase + 4L) {
                        return 0x9988_7766L
                    }
                    return 0L
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    upcalls += "W:${physicalAddress.toString(16)}:$size:${value.toString(16)}"
                }
            })

            d.resetMmioStats()
            d.writeMemory(CODE_BASE, wordsToBytes(*code))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 10_000, 5000L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0L, d.getReg(0, 2), "Zero RO range read mismatch")
            assertEquals(0xAABB_CCDDL, d.getReg(0, 3), "Buffer RO range u32 read mismatch")
            assertEquals(0xCCL, d.getReg(0, 4), "Buffer RO range u8 read mismatch")
            assertEquals(0xAABBL, d.getReg(0, 5), "Buffer RO range u16 read mismatch")
            assertEquals(0x1357_2468L, d.getReg(0, 7), "Upcall read mismatch")
            assertEquals(0x9988_7766L, d.getReg(0, 8), "Post-invalidation buffer range read must hit upcall")
            assertTrue(observedVtickInUpcall >= 5000L, "liveStateBuf vtick must be >= base vtick (got $observedVtickInUpcall)")
            assertTrue(observedPcInUpcall >= CODE_BASE, "liveStateBuf PC must be inside CODE_BASE (got 0x${observedPcInUpcall.toString(16)})")
            assertEquals(INITIAL_CPSR and 0x1FL, observedCpsrInUpcall and 0x1FL, "liveStateBuf CPSR mode mismatch")

            val stats = Dynarmic.MmioStats()
            d.readMmioStats(stats)
            assertEquals(4L, stats.roMmioFastHits, "Expected 4 RO-range fast hits")
            assertEquals(2L, stats.upcallReads, "Expected 2 upcall reads")
            assertEquals(1L, stats.upcallWrites, "Expected 1 upcall write")
            assertEquals(listOf("R:fff50000:4", "W:fff11004:4:13572468", "R:fff11004:4"), upcalls)
        }
    }

    @Test
    fun clusterNativeBatchSteppingAndTracepointResumeLoopWork() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping cluster batch + #769 tracepoint test: libfuji_dynarmic missing")

        // 1. Multi-core native cluster batch stepping via DynarmicCluster.runNativeBatch
        // Core 0 increments r0 30 times then WFI; Core 1 increments r1 20 times then WFI.
        val c0Code = intArrayOf(
            movImm(0, 0),
            movImm(2, 30),
            addReg(0, 0, 2),
            subsImm(2, 2, 1),
            bneRelWords(-4),
            WFI_INSN,
        )
        val c1Code = intArrayOf(
            movImm(1, 0),
            movImm(2, 20),
            addReg(1, 1, 2),
            subsImm(2, 2, 1),
            bneRelWords(-4),
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsClusterFast(), "Loaded libfuji_dynarmic does not export fuji_dyn_run_cluster_fast")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*c0Code))
            d.writeMemory(CODE_BASE + 0x1000L, wordsToBytes(*c1Code))

            val stubIrq = object : com.github.nyabanban.fuji.emulator.api.CoreInterruptPort {
                override val coreCount: Int = 4
                override fun selectCore(core: Int) {}
                override fun choose(): Int? = null
            }
            val cluster = DynarmicCluster(d, stubIrq, null)
            // Park cores 2 and 3 in WFI so only cores 0 and 1 run in the native batch
            val wfiPc = CODE_BASE + 0x2000L
            d.writeMemory(wfiPc, wordsToBytes(WFI_INSN))
            d.setCpsr(0, INITIAL_CPSR); d.setReg(0, 15, CODE_BASE)
            d.setCpsr(1, INITIAL_CPSR); d.setReg(1, 15, CODE_BASE + 0x1000L)
            d.setCpsr(2, INITIAL_CPSR); d.setReg(2, 15, wfiPc)
            d.setCpsr(3, INITIAL_CPSR); d.setReg(3, 15, wfiPc)

            assertTrue(cluster.canRunNativeBatch(10_000L))
            val cr = cluster.runNativeBatch(10_000L, 100L)
            assertEquals(Dynarmic.STOP_ALL_WAITING, cr.stopReason, "All 4 cores must settle into WFI in one native batch")
            assertEquals(465L, d.getReg(0, 0), "Core 0 sum(1..30) mismatch")
            assertEquals(210L, d.getReg(1, 1), "Core 1 sum(1..20) mismatch")
            assertTrue(cr.switches >= 3, "Expected round-robin switches across all 4 cores, got ${cr.switches}")
            assertTrue(!cluster.switch(), "Expected all 4 cores waiting after STOP_ALL_WAITING")
        }

        // 2. Regression for #769: hitting a tracepoint repeatedly inside a loop must NEVER fault with STOP_FAULT_UNDEF
        val tpLoop = intArrayOf(
            movImm(0, 3),        // +0: r0 = 3
            addReg(1, 1, 0),     // +4: TRACEPOINT HERE! r1 += r0
            subsImm(0, 0, 1),    // +8: r0 -= 1
            bneRelWords(-4),     // +12: bne +4
            WFI_INSN,            // +16: wfi
        )
        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*tpLoop))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 1, 0L)
            d.setReg(0, 15, CODE_BASE)
            d.setTracepoints(longArrayOf(CODE_BASE + 4L))

            var hits = 0
            var vt = 0L
            while (hits < 5) {
                val res = d.runFast(0, 1000L, vt, false)
                vt = res.virtualTick
                if (res.stopReason == Dynarmic.STOP_TRACEPOINT) {
                    assertEquals(CODE_BASE + 4L, res.stopPc, "Tracepoint hit at wrong PC on hit #$hits")
                    hits++
                } else {
                    assertEquals(Dynarmic.STOP_WFI, res.stopReason, "Expected WFI after 3 tracepoint hits, got ${res.stopReason}")
                    break
                }
            }
            assertEquals(3, hits, "Expected exactly 3 tracepoint hits across the loop")
            assertEquals(6L, d.getReg(0, 1), "Loop sum 3+2+1 must be 6 after tracepoint resumes")
        }
    }

    @Test
    fun testNativeClusterBatchTracksLiveActiveCoreAndHaltsOnCrossCoreIrqGain() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "libfuji_dynarmic not built; skipping native cluster live-core test")
        val mmioBase = 0x4000_0000L
        val c0Code = intArrayOf(
            movw(3, 0x0000),
            movt(3, 0x4000),
            movImm(0, 0x11),
            strImm(0, 3, 0),
            WFI_INSN,
            movImm(4, 0x55),
            WFI_INSN,
        )
        val c1Code = intArrayOf(
            movw(3, 0x0000),
            movt(3, 0x4000),
            movImm(1, 0x22),
            strImm(1, 3, 4),
            movImm(5, 0x77),
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsClusterFast() && d.supportsLiveState(), "Native bridge lacks clusterFast/liveState")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(mmioBase, 0x1000)
            d.writeMemory(CODE_BASE, wordsToBytes(*c0Code))
            d.writeMemory(CODE_BASE + 0x1000L, wordsToBytes(*c1Code))
            val wfiPc = CODE_BASE + 0x2000L
            d.writeMemory(wfiPc, wordsToBytes(WFI_INSN))

            val pendingByCore = BooleanArray(4)
            var selectedCore = 0
            val irqPort = object : com.github.nyabanban.fuji.emulator.api.CoreInterruptPort {
                override val coreCount: Int = 4
                override fun selectCore(core: Int) { selectedCore = core }
                override fun choose(): Int? = if (pendingByCore[selectedCore]) 32 else null
                override fun hasPending(): Boolean = pendingByCore[selectedCore]
            }
            val cluster = DynarmicCluster(d, irqPort, null)
            val observedWrites = ArrayList<Pair<Int, Long>>()
            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long = 0L
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    val active = d.activeCore()
                    observedWrites += active to value
                    if (physicalAddress == mmioBase + 4L && value == 0x22L) {
                        // Cross-core IRQ raised on Core 0 while Core 1 is running inside the native batch
                        pendingByCore[0] = true
                        if (cluster.anyOtherCoreIrqGained(active)) {
                            d.haltRun()
                        }
                    }
                }
            })

            d.setCpsr(0, INITIAL_CPSR); d.setReg(0, 15, CODE_BASE)
            d.setCpsr(1, INITIAL_CPSR); d.setReg(1, 15, CODE_BASE + 0x1000L)
            d.setCpsr(2, INITIAL_CPSR); d.setReg(2, 15, wfiPc)
            d.setCpsr(3, INITIAL_CPSR); d.setReg(3, 15, wfiPc)

            assertTrue(cluster.canRunNativeBatch(10_000L))
            val cr1 = cluster.runNativeBatch(10_000L, 0L)
            assertEquals(listOf(0 to 0x11L, 1 to 0x22L), observedWrites, "Dynarmic.activeCore() must track live core transitions mid-batch")
            assertEquals(Dynarmic.STOP_MMIO_HALT, cr1.stopReason, "Batch must halt when cross-core IRQ is gained")
            assertEquals(1, cluster.current, "Active core on halt must be Core 1")
            assertTrue(cluster.hasAnotherRunnableCore(), "Core 0 must be runnable via its newly pending IRQ")

            // Clear Core 0 IRQ after waking Core 0 in the next batch; both cores settle into WFI
            val cr2 = cluster.runNativeBatch(10_000L, cr1.virtualTick)
            assertEquals(0x55L, d.getReg(0, 4), "Core 0 must wake from WFI and execute post-WFI instruction")
            assertEquals(0x77L, d.getReg(1, 5), "Core 1 must finish after resuming from halt")
            assertTrue(cr2.stopReason == Dynarmic.STOP_ALL_WAITING || cr2.stopReason == Dynarmic.STOP_QUANTUM)
        }
    }

    @Test
    fun testNativeClusterBatchReconcilesWfeThenSevInSingleBatch() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "libfuji_dynarmic not built; skipping native cluster WFE/SEV test")
        val wfeInsn = 0xE320F002.toInt()
        val sevInsn = 0xE320F004.toInt()
        val c0Code = intArrayOf(
            wfeInsn,
            movImm(4, 0x42),
            WFI_INSN,
        )
        val c1Code = intArrayOf(
            sevInsn,
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsClusterFast(), "Native bridge lacks clusterFast")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.writeMemory(CODE_BASE, wordsToBytes(*c0Code))
            d.writeMemory(CODE_BASE + 0x1000L, wordsToBytes(*c1Code))
            val wfiPc = CODE_BASE + 0x2000L
            d.writeMemory(wfiPc, wordsToBytes(WFI_INSN))

            val stubIrq = object : com.github.nyabanban.fuji.emulator.api.CoreInterruptPort {
                override val coreCount: Int = 4
                override fun selectCore(core: Int) {}
                override fun choose(): Int? = null
            }
            val cluster = DynarmicCluster(d, stubIrq, null)
            d.setCpsr(0, INITIAL_CPSR); d.setReg(0, 15, CODE_BASE)
            d.setCpsr(1, INITIAL_CPSR); d.setReg(1, 15, CODE_BASE + 0x1000L)
            d.setCpsr(2, INITIAL_CPSR); d.setReg(2, 15, wfiPc)
            d.setCpsr(3, INITIAL_CPSR); d.setReg(3, 15, wfiPc)

            // Batch 1: Core 0 enters WFE, Core 1 executes SEV.
            // V2 bridge wakes Core 0 inline and settles all cores in WFI in Batch 1;
            // V1 bridge returns STOP_SEV and completes Core 0 in Batch 2.
            val cr1 = cluster.runNativeBatch(10_000L, 0L)
            assertEquals(0, d.getWfeEventMask() and 1, "Core 0 waking from WFE must consume its event register")
            assertEquals(0xE, d.getWfeEventMask() and 0xE, "Other cores not in WFE must retain SEV event bits")
            if (d.supportsLiveClusterIrq()) {
                assertEquals(Dynarmic.STOP_ALL_WAITING, cr1.stopReason, "V2 bridge must wake Core 0 inline on SEV and settle into WFI")
            } else {
                assertEquals(Dynarmic.STOP_SEV, cr1.stopReason, "V1 bridge must return STOP_SEV when Core 1 executes SEV")
                assertEquals(1, cluster.current, "Active core after SEV must be Core 1")
                assertTrue(cluster.hasAnotherRunnableCore(), "Core 0 must be woken immediately by Core 1's SEV")
                val cr2 = cluster.runNativeBatch(10_000L, cr1.virtualTick)
                assertEquals(Dynarmic.STOP_ALL_WAITING, cr2.stopReason, "All cores must settle into WFI after Core 0 wakes")
            }
            assertEquals(0x42L, d.getReg(0, 4), "Core 0 must execute post-WFE instruction after SEV wakeup")
            assertEquals(
                d.supportsClusterFast() && d.supportsGicShadow(),
                d.supportsLiveClusterIrq(),
                "supportsLiveClusterIrq() must gate native cluster batching on V2 live-IRQ bridge capability",
            )
        }
    }

    @Test
    fun testGicShadowFastpathAndInlineSevClusterElision() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "libfuji_dynarmic not built; skipping GIC shadow & inline SEV test")
        val gicdBase = 0xFFFF_1000L
        val giccBase = 0xFFFF_2000L
        val sevInsn = 0xE320_F004.toInt()
        val wfeInsn = 0xE320_F002.toInt()
        val c0Code = intArrayOf(
            // Read GICC_IAR (0xFFFF200C), write GICC_EOIR (0xFFFF2010), execute SEV x4, then WFI
            movw(3, 0x2000),
            movt(3, 0xFFFF),
            0xE593_000C.toInt(), // ldr r0, [r3, #0x0c] -> GICC_IAR (expects 40)
            0xE583_0010.toInt(), // str r0, [r3, #0x10] -> GICC_EOIR
            0xE593_100C.toInt(), // ldr r1, [r3, #0x0c] -> GICC_IAR (expects 1023 spurious)
            sevInsn,
            sevInsn,
            sevInsn,
            sevInsn,
            WFI_INSN,
        )
        val c1Code = intArrayOf(
            wfeInsn,
            movImm(4, 0x99),
            WFI_INSN,
        )
        Dynarmic(checkNotNull(dynLib), 4, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsGicShadow() && d.supportsClusterFast(), "Native bridge lacks GIC shadow / clusterFast")
            d.map(CODE_BASE, 0x10000, Dynarmic.PERM_ALL)
            d.mmio(gicdBase, 0x1000)
            d.mmio(giccBase, 0x1000)
            d.writeMemory(CODE_BASE, wordsToBytes(*c0Code))
            d.writeMemory(CODE_BASE + 0x1000L, wordsToBytes(*c1Code))
            val wfiPc = CODE_BASE + 0x2000L
            d.writeMemory(wfiPc, wordsToBytes(WFI_INSN))

            var gicUpcalls = 0
            d.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    gicUpcalls++
                    return 0L
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    gicUpcalls++
                }
            })

            // Populate shared GICv2 shadow directly via setGicShadowPtr (exact FujiDynGicShadow / GicV2ShadowState ABI)
            val seg = d.gicShadowSegment()
            seg.fill(0.toByte())
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0x000L, 1) // active = 1
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0x004L, 4) // core_count = 4
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0x008L, 1) // dist_ctrl = 1
            // Enable + pend SPI 40 targeting core 0 (w = 40 >> 6 = 0, bit = 40)
            val bit40 = 1L shl 40
            seg.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0x020L, bit40) // shared_enabled[0]
            seg.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0x060L, bit40) // shared_pending[0]
            seg.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0x160L, bit40) // target_core_bits[0][0]
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0x260L + (40 - 32), 0x40.toByte()) // shared_priority[8]
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0x440L + (40 - 32), 0x01.toByte()) // shared_targets[8]
            // Core 0 interface enabled, pmr = 0xF0, bpr = 0, irq_pending = 1
            val c0Off = 0x620L
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, c0Off + 0x00L, 1)    // irq_pending
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, c0Off + 0x04L, 1)    // cpu_ctrl
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, c0Off + 0x08L, 0xF0) // pmr
            d.setGicShadowPtr(gicdBase, giccBase, seg)

            d.setCpsr(0, INITIAL_CPSR); d.setReg(0, 15, CODE_BASE)
            d.setCpsr(1, INITIAL_CPSR); d.setReg(1, 15, CODE_BASE + 0x1000L)
            d.setCpsr(2, INITIAL_CPSR); d.setReg(2, 15, wfiPc)
            d.setCpsr(3, INITIAL_CPSR); d.setReg(3, 15, wfiPc)

            // First put core 1 into WFE, then run core 0 in a single cluster batch:
            // GICC_IAR/EOIR must execute with ZERO upcalls AND SEV must wake core 1 inline without STOP_SEV exit!
            val cr = d.runClusterFast(10_000L, 0L, 0b0011, 0, 1, 0, 1024, 0)
            assertEquals(Dynarmic.STOP_ALL_WAITING, cr.stopReason, "Cluster batch must settle all cores in WFI without STOP_SEV exit")
            assertEquals(0, gicUpcalls, "GICC_IAR/EOIR must run entirely in native C++ GICv2 fastpath with zero JVM upcalls")
            assertEquals(40L, d.getReg(0, 0), "First GICC_IAR must acknowledge SPI 40")
            assertEquals(1023L, d.getReg(0, 1), "Second GICC_IAR after EOIR must return spurious 1023")
            assertEquals(0x99L, d.getReg(1, 4), "Core 1 must wake from WFE inline on Core 0 SEV")
            assertTrue((cr.sevMask and 1) != 0, "sevMask must report Core 0 SEV")
            assertEquals(0, seg.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, c0Off + 0x00L), "Core 0 irq_pending must be cleared in shadow")
            d.clearGicShadow()
        }
    }

    @Test
    fun supersection40BitHighPhysRamIsolatesFromLow32BitRamAndResetsCleanly() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping 40-bit Supersection test: libfuji_dynarmic missing")

        val l1Base = 0x0010_0000L
        val lowCodeBank = 0x0100_0000L
        val highBankPa = 0x01_0100_0000L // GFX100S 4K Movie pool Bank 2 (PA[35:32]=1, PA[31:24]=0x01)
        val vaSuper = 0xD300_0000L       // GFX100S section5@0x02743368 VA -> PA 0x1_01000000

        val prog = intArrayOf(
            movw(0, 0x0000), movt(0, 0x0010), // r0 = TTBR0 = 0x00100000
            mcr(0, 0, 2, 0, 0),
            movImm(1, 0),
            mcr(0, 1, 2, 0, 2),               // TTBCR = 0
            movw(2, 0x5555), movt(2, 0x5555),
            mcr(0, 2, 3, 0, 0),               // DACR = 0x55555555
            mrc(0, 3, 1, 0, 0),
            0xE3833001.toInt(),               // SCTLR.M = 1
            mcr(0, 3, 1, 0, 0),
            movw(4, 0x0000), movt(4, 0xD300), // r4 = VA 0xD3000000 -> PA 0x1_01000000
            movImm(5, 0),
            strImm(5, 4, 0x40),               // zero VA 0xD3000040 (must NOT clobber PA 0x01000040!)
            movw(6, 0xBEEF), movt(6, 0xCAFE),
            strImm(6, 4, 0x44),               // write 0xCAFEBEEF to VA 0xD3000044
            ldrImm(7, 4, 0x44),               // read back VA 0xD3000044
            WFI_INSN,
        )

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            assumeTrue(d.supportsHighPhysRam(), "Native libfuji_dynarmic lacks fuji_dyn_map_ram64")
            d.map(0x0000_0000L, 0x0020_0000L, Dynarmic.PERM_ALL)
            d.map(lowCodeBank, 0x0100_0000L, Dynarmic.PERM_ALL)
            d.mapHighRam(highBankPa, 0x0100_0000L, Dynarmic.PERM_READ or Dynarmic.PERM_WRITE)

            d.writeMemory(CODE_BASE, wordsToBytes(*prog))
            d.writePhysicalWord(lowCodeBank + 0x40L, 0x5EC5_C0DEL) // live firmware sentinel

            // Identity map 0x00000000..0x01FFFFFF (1 MiB sections)
            for (s in 0 until 32) {
                d.writePhysicalWord(l1Base + s * 4L, (s.toLong() shl 20) or 0x0C02L)
            }
            // 16 MiB Supersection VA 0xD3000000..0xD3FFFFFF -> PA 0x1_01000000 (L1[23:20]=1, L1[18]=1)
            val l1Super = 0x0100_0000L or (1L shl 20) or (1L shl 18) or 0x0C02L
            for (i in 0 until 16) {
                d.writePhysicalWord(l1Base + ((0xD30L + i) shl 2), l1Super)
            }

            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            val res = d.runFast(0, 20_000L, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
            assertEquals(0xCAFE_BEEFL, d.getReg(0, 7))
            assertEquals(0x5EC5_C0DEL, d.physicalWord(lowCodeBank + 0x40L), "High-PA Supersection write must NOT alias low 32-bit RAM")
            assertEquals(highBankPa + 0x44L, d.translate64(vaSuper + 0x44L, Dynarmic.PERM_READ))

            val hiBytes = d.readHighMemory(highBankPa + 0x44L, 4)
            val hiWord = (hiBytes[0].toLong() and 0xFFL) or
                ((hiBytes[1].toLong() and 0xFFL) shl 8) or
                ((hiBytes[2].toLong() and 0xFFL) shl 16) or
                ((hiBytes[3].toLong() and 0xFFL) shl 24)
            assertEquals(0xCAFE_BEEFL, hiWord)

            // DMA must honor position/limit on direct, sliced heap, and read-only source buffers.
            for (direct in listOf(false, true)) {
                val size = 71_000 // larger than the row/page scratch segment
                val backing = if (direct) java.nio.ByteBuffer.allocateDirect(size + 18) else java.nio.ByteBuffer.allocate(size + 18)
                val payload = backing.position(5).limit(size + 11).slice().position(3).limit(size + 3)
                for (i in 0 until size) payload.put(i + 3, (i * 17).toByte())
                val source = payload.asReadOnlyBuffer()
                d.writeHighMemory(highBankPa + 0x1000, source)
                assertEquals(source.limit(), source.position())
                val target = if (direct) java.nio.ByteBuffer.allocateDirect(size + 4) else java.nio.ByteBuffer.allocate(size + 4)
                target.position(2).limit(size + 2)
                d.readHighMemory(highBankPa + 0x1000, target)
                assertEquals(target.limit(), target.position())
                assertEquals(0, target.get(1).toInt())
                for (i in 0 until size) assertEquals((i * 17).toByte(), target.get(i + 2))
            }

            // The FHD movie scaler reads this size from high RAM every frame.
            val frame = ByteArray(6_633_216) { (it xor (it ushr 16)).toByte() }
            d.writeHighMemory(highBankPa, frame)
            assertArrayEquals(frame, d.readHighMemory(highBankPa, frame.size))

            // A transfer crossing the bank end must fail before any valid prefix is copied.
            val tail = highBankPa + 0x0100_0000L - 65_536
            val sentinel = byteArrayOf(12, 34)
            d.writeHighMemory(tail, sentinel)
            val crossing = ByteBuffer.allocate(65_537)
            assertThrows(IllegalStateException::class.java) { d.writeHighMemory(tail, crossing) }
            assertThrows(IllegalStateException::class.java) { d.readHighMemory(tail, crossing) }
            assertEquals(0, crossing.position())
            assertEquals(0, crossing.get(0).toInt())
            assertArrayEquals(sentinel, d.readHighMemory(tail, sentinel.size))

            // Cold reset must cleanly unmap high-PA regions so re-mapping succeeds without overlap
            d.resetForColdReset()
            d.mapHighRam(highBankPa, 0x0100_0000L, Dynarmic.PERM_READ or Dynarmic.PERM_WRITE)
        }
    }

    @Test
    fun sliceDirectRamZeroCopyAndJitInvalidationWork() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping sliceDirectRam test: libfuji_dynarmic missing")

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(CODE_BASE, 0x10000L, Dynarmic.PERM_ALL)
            d.map(DATA_BASE, 0x10000L, Dynarmic.PERM_READ or Dynarmic.PERM_WRITE)

            // Boundary / multi-region / unmapped safety checks must return null.
            assertEquals(null, d.sliceDirectRam(CODE_BASE, 0L))
            assertEquals(null, d.sliceDirectRam(CODE_BASE, -4L))
            assertEquals(null, d.sliceDirectRam(0x5000_0000L, 64L))
            // Spans across CODE_BASE (0x10000..0x20000) into DATA_BASE (0x20000..0x30000) -> two separate regions.
            assertEquals(null, d.sliceDirectRam(CODE_BASE + 0xF800L, 0x1000L))

            // 1. Direct RAM-to-RAM MemorySegment.copy inside mapped guest RAM.
            val payload = ByteArray(4096) { ((it * 29 + 7) and 0xFF).toByte() }
            d.writeMemory(DATA_BASE + 0x1000L, payload)
            val srcSlice = d.sliceDirectRam(DATA_BASE + 0x1000L, payload.size.toLong())
            val dstSlice = d.sliceDirectRam(DATA_BASE + 0x4000L, payload.size.toLong())
            assumeTrue(srcSlice != null && dstSlice != null, "Skipping: host dual-view/backed MemorySegment unavailable")
            assertEquals(payload.size.toLong(), srcSlice!!.byteSize())
            assertEquals(payload.size.toLong(), dstSlice!!.byteSize())
            java.lang.foreign.MemorySegment.copy(srcSlice, 0L, dstSlice, 0L, payload.size.toLong())
            d.invalidateRange(DATA_BASE + 0x4000L, payload.size.toLong())
            assertArrayEquals(payload, d.readMemory(DATA_BASE + 0x4000L, payload.size))

            // 2. Guest ARM32 CPU loads words written directly via sliceDirectRam (zero-copy DMA -> CPU visibility).
            val prog = intArrayOf(
                movw(1, ((DATA_BASE + 0x4000L) and 0xFFFFL).toInt()),
                movt(1, (((DATA_BASE + 0x4000L) ushr 16) and 0xFFFFL).toInt()),
                ldrImm(0, 1, 0),
                ldrImm(2, 1, 4),
                WFI_INSN,
            )
            d.writeMemory(CODE_BASE, wordsToBytes(*prog))
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 15, CODE_BASE)
            assertEquals(Dynarmic.STOP_WFI, d.runFast(0, 1_000L, 0L, false).stopReason)
            val expectedWord0 = java.nio.ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            val expectedWord1 = java.nio.ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            assertEquals(expectedWord0, d.getReg(0, 0), "Guest CPU must observe direct RAM copy word 0")
            assertEquals(expectedWord1, d.getReg(0, 2), "Guest CPU must observe direct RAM copy word 1")
        }
    }

    @Test
    fun vfpFpexcDisableTrapsUndefAndSupportsLazyRtosContextSwitching() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping lazy VFP FPEXC test: libfuji_dynarmic missing")

        val vbar = 0x0000_0000L
        val undHandlerAddr = 0x0000_0100L
        val tcbAddr = DATA_BASE
        val spUndAddr = STACK_BASE + 0x1000L
        val spSvcAddr = STACK_BASE + 0x2000L
        val vfpFuncAddr = CODE_BASE + 0x200L
        val callerAddr = CODE_BASE + 0x300L

        val undHandler = intArrayOf(
            0xE92D0003.toInt(), // push {r0, r1}
            0xE59D0008.toInt(), // ldr  r0, [sp, #8] (tcbAddr)
            0xE5901000.toInt(), // ldr  r1, [r0, #0] (lazy_vfp_count)
            0xE2811001.toInt(), // add  r1, r1, #1
            0xE5801000.toInt(), // str  r1, [r0, #0]
            0xE3A01001.toInt(), // mov  r1, #1
            0xE58010A0.toInt(), // str  r1, [r0, #0xa0] ([tcb + 0xa0] = 1, matching ThreadX!)
            0xE3A01101.toInt(), // mov  r1, #0x40000000
            0xEEE81A10.toInt(), // vmsr fpexc, r1
            0xE8BD0003.toInt(), // pop  {r0, r1}
            SUBS_PC_LR_4,       // subs pc, lr, #4 (retry faulting VFP instruction)
        )

        val vfpFunc = intArrayOf(
            0xED2D8B02.toInt(), // vpush {d8}
            0xEE308A01.toInt(), // vadd.f32 s16, s0, s2 (3.25f + 4.5f = 7.75f)
            0xEE182A10.toInt(), // vmov r2, s16
            0xECBD8B02.toInt(), // vpop {d8}
            0xE12FFF1E.toInt(), // bx lr
        )

        val bl1Off = ((vfpFuncAddr - (callerAddr + 8L) - 8L) / 4L).toInt() and 0x00FF_FFFF
        val bl2Off = ((vfpFuncAddr - (callerAddr + 20L) - 8L) / 4L).toInt() and 0x00FF_FFFF
        val callerCode = intArrayOf(
            movImm(0, 0),
            0xEEE80A10.toInt(),           // vmsr fpexc, r0 (disable VFP)
            (0xEB000000L or bl1Off.toLong()).toInt(), // bl vfpFuncAddr (traps -> enables -> retries)
            movImm(0, 0),
            0xEEE80A10.toInt(),           // vmsr fpexc, r0 (re-disable VFP on already-cached block!)
            (0xEB000000L or bl2Off.toLong()).toInt(), // bl vfpFuncAddr (must trap again without ClearCache!)
            WFI_INSN,
        )

        Dynarmic(checkNotNull(dynLib), 1, 0xFFFF_0000L).use { d ->
            d.map(0x0000_0000L, 0x0004_0000L, Dynarmic.PERM_ALL)
            d.writePhysicalWord(vbar + 0x04L, 0xEA00_003DL) // UND vector -> b 0x100
            d.writeMemory(undHandlerAddr, wordsToBytes(*undHandler))
            d.writeMemory(vfpFuncAddr, wordsToBytes(*vfpFunc))
            d.writeMemory(callerAddr, wordsToBytes(*callerCode))

            // 1. Synchronous STOP_FAULT_UNDEF when AUTO_EXCP_UNDEF is off and FPEXC.EN == 0
            d.setCpsr(0, INITIAL_CPSR)
            d.setReg(0, 13, spSvcAddr)
            d.setFpexc(0, 0L)
            d.setReg(0, 15, vfpFuncAddr)
            val syncFault = d.runFast(0, 100L, 0L, false)
            assertEquals(Dynarmic.STOP_FAULT_UNDEF, syncFault.stopReason)
            assertEquals(vfpFuncAddr, d.getReg(0, 15), "Faulting VFP instruction must report exact fault PC")
            assertEquals(spSvcAddr, d.getReg(0, 13), "Faulting VPUSH must not decrement SP")

            // 2. Warm up vfpFuncAddr with FPEXC.EN == 1, then verify two lazy-VFP traps without ClearCache
            val f1Bits = java.lang.Float.floatToRawIntBits(3.25f).toLong() and 0xFFFF_FFFFL
            val f2Bits = java.lang.Float.floatToRawIntBits(4.5f).toLong() and 0xFFFF_FFFFL
            val expectBits = java.lang.Float.floatToRawIntBits(7.75f).toLong() and 0xFFFF_FFFFL
            d.setDreg(0, 0, f1Bits) // s0 = 3.25f
            d.setDreg(0, 1, f2Bits) // s2 = 4.5f
            d.setDreg(0, 8, 0x1122_3344_5566_7788L)

            val warmupAddr = CODE_BASE + 0x280L
            val blWarmOff = ((vfpFuncAddr - warmupAddr - 8L) / 4L).toInt() and 0x00FF_FFFF
            d.writeMemory(warmupAddr, wordsToBytes((0xEB000000L or blWarmOff.toLong()).toInt(), WFI_INSN))
            d.setFpexc(0, 0x4000_0000L)
            d.setReg(0, 15, warmupAddr)
            assertEquals(Dynarmic.STOP_WFI, d.runFast(0, 1_000L, 0L, false).stopReason)
            assertEquals(expectBits, d.getReg(0, 2))

            d.writePhysicalWord(tcbAddr, 0L)
            d.writePhysicalWord(tcbAddr + 0xA0L, 0L)
            d.writePhysicalWord(spUndAddr, tcbAddr)
            d.setCp15(0, 0, 12, 0, 0, vbar)
            d.setBankedReg(0, Dynarmic.MODE_UND, 13, spUndAddr)
            d.setAutoExceptions(Dynarmic.AUTO_EXCP_UNDEF)
            d.setReg(0, 2, 0L)
            d.setReg(0, 13, spSvcAddr)
            d.setReg(0, 15, callerAddr)

            val lazyRes = d.runFast(0, 5_000L, 0L, false)
            assertEquals(Dynarmic.STOP_WFI, lazyRes.stopReason)
            assertEquals(2L, d.physicalWord(tcbAddr), "UND lazy-VFP handler must fire on both FPEXC=0 transitions")
            assertEquals(1L, d.physicalWord(tcbAddr + 0xA0L), "ThreadX [tcb + 0xa0] VFP active flag must be set")
            assertEquals(0x4000_0000L, d.getFpexc(0))
            assertEquals(expectBits, d.getReg(0, 2))
            assertEquals(0x1122_3344_5566_7788L, d.getDreg(0, 8), "Callee-saved d8 must be preserved")
        }
    }
}
