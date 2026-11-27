package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.cpu.WordExclusive
import com.github.nyabanban.fuji.emulator.desktop.qualification.SyntheticGuest
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Armv7FullExceptionsSessionTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))

    private fun words(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it.toInt()) }
        }.array()

    private fun branch(from: Long, to: Long, cond: Long = 0xe000_0000L): Long =
        cond or 0x0a00_0000L or (((to - (from + 8L)) shr 2) and 0x00ff_ffffL)

    private fun nativeLibrary(): Path {
        val candidate = System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")
            ?: System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?: System.getenv("FUJI_NATIVE_LINUX_CACHE")
        return Path.of(checkNotNull(candidate) { "A Unicorn native library env var must be configured" })
    }

    @Test
    fun launchValidationAcceptsOptInFullVectorsProfileAndRefusesUnknownValues() {
        val home = Files.createTempDirectory("full-vectors-validate-")
        try {
            val base = SyntheticGuest.prepare(
                home,
                project.resolve("build/emulator/compoments"),
                home.resolve("missing-native"),
                words(0xeaff_fffeL),
                "loader-low128-experimental-v1",
                10,
            ) + mapOf("schema" to 1, "httpPort" to 0)

            validateLaunchConfiguration(base)
            validateLaunchConfiguration(base + ("exceptionProfile" to ""))
            validateLaunchConfiguration(base + ("exceptionProfile" to "armv7-full-vectors-v1"))
            assertFailsWith<IllegalArgumentException> {
                validateLaunchConfiguration(base + ("exceptionProfile" to "invented-profile"))
            }
            assertFailsWith<IllegalArgumentException> {
                validateLaunchConfiguration(base + ("exceptionProfile" to 42))
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun firmwareSessionExecutesSvcUdefAndUartTxUnderFullVectorsProfile() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("full-vectors-session-")
        try {
            // Build a 0x1200-byte synthetic ARM32 image loaded at 0x0:
            //   0x0000: MOV R0, #0x1000
            //   0x0004: MCR p15, 0, R0, c12, c0, 0  ; VBAR = 0x1000
            //   0x0008: MOV R5, #0                  ; SVC counter
            //   0x000C: MOV R6, #0                  ; UDEF counter
            //   0x0010: SVC #0                      ; -> VBAR+0x08 (0x1008) -> R5++ -> MOVS PC, LR
            //   0x0014: UDF #0 (0xE7F000F0)         ; -> UC_ERR_INSN_INVALID -> VBAR+0x04 -> R6++ -> MOVS PC, LR
            //   0x0018: LDR R1, [PC, #0x14]         ; R1 = 0xFFF3A000 (UARTDR)
            //   0x001C: MOV R2, #'H'
            //   0x0020: STR R2, [R1]
            //   0x0024: MOV R2, #'i'
            //   0x0028: STR R2, [R1]
            //   0x002C: MOV R2, #'\n'
            //   0x0030: STR R2, [R1]
            //   0x0034: B .                         ; idle loop until budget expires
            //   0x0038: 0xFFF3A000
            val image = ByteArray(0x1200)
            fun putWords(offset: Int, vararg insns: Long) {
                words(*insns).copyInto(image, offset)
            }
            putWords(
                0x0000,
                0xe3a0_0a01L, // MOV R0, #0x1000 (1 ROR 20 = 0x1000)
                0xee0c_0f10L, // MCR p15, 0, R0, c12, c0, 0 (VBAR)
                0xe3a0_5000L, // MOV R5, #0
                0xe3a0_6000L, // MOV R6, #0
                0xef00_0000L, // SVC #0
                0xe7f0_00f0L, // UDF #0
                0xe59f_1018L, // 0x0018: LDR R1, [PC, #0x18] (PC=0x0020 -> 0x0038)
                0xe3a0_2048L, // MOV R2, #'H'
                0xe581_2000L, // STR R2, [R1]
                0xe3a0_2069L, // MOV R2, #'i'
                0xe581_2000L, // STR R2, [R1]
                0xe3a0_200aL, // MOV R2, #'\n'
                0xe581_2000L, // STR R2, [R1]
                0xeaff_fffeL, // 0x0034: B .
                0xfff3_a000L, // 0x0038: UARTDR address
            )
            // VBAR (0x1000):
            //   0x1004 (UDEF) -> B 0x1100
            //   0x1008 (SVC)  -> B 0x1120
            putWords(0x1004, branch(0x1004, 0x1100), branch(0x1008, 0x1120))
            // UDEF handler at 0x1100: ADD R6, R6, #1; MOVS PC, LR
            putWords(0x1100, 0xe286_6001L, 0xe1b0_f00eL)
            // SVC handler at 0x1120: ADD R5, R5, #1; MOVS PC, LR
            putWords(0x1120, 0xe285_5001L, 0xe1b0_f00eL)

            val base = SyntheticGuest.prepare(
                home,
                project.resolve("build/emulator/compoments"),
                lib,
                image,
                "loader-low128-experimental-v1",
                40,
            )
            // Add soc-uart device window to synthetic metadata and enable soc-uart
            val metaFile = home.resolve("metadata.yaml")
            val meta = Formats.read(metaFile).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val devices = (meta["devices"] as List<Map<String, Any?>>) + listOf(
                mapOf(
                    "id" to "soc-uart",
                    "address" to "0xfff3a000",
                    "size" to "0x30",
                    "port" to "soc-uart.uart-bank",
                    "optional" to true,
                ),
                mapOf(
                    "id" to "soc-uart",
                    "address" to "0xfff3a030",
                    "size" to "0x4",
                    "port" to "soc-uart.uart-config",
                    "optional" to true,
                ),
                mapOf(
                    "id" to "soc-uart",
                    "address" to "0xfff3a034",
                    "size" to "0x8",
                    "port" to "soc-uart.uart-tail",
                    "optional" to true,
                ),
            )
            meta["devices"] = devices
            Formats.atomicYaml(metaFile, meta)
            Formats.atomicYaml(
                home.resolve("compoments/soc-uart/soc-uart.yaml"),
                mapOf("profile" to "pl011-tx-ready-experimental-v1", "initialFill" to 0L, "logTx" to false),
            )

            @Suppress("UNCHECKED_CAST")
            val enabled = (base["enabledDevices"] as List<String>) + "soc-uart"
            val config = base + mapOf(
                "schema" to 1,
                "httpPort" to 0,
                "enabledDevices" to enabled,
                "exceptionProfile" to "armv7-full-vectors-v1",
            )
            validateLaunchConfiguration(config)

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Execute until instructionBudget is exhausted.
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "Expected zero frontier failure: $st")
                        val regs = checkNotNull(session.registerSnapshot())
                        assertEquals(0x34L, regs["pc"], "CPU should reach idle loop after SVC, UDEF, and UART writes: $st")
                        assertEquals(1L, regs["r5"], "SVC handler must execute once")
                        assertEquals(1L, regs["r6"], "UDEF handler must execute once")
                        val uartState = checkNotNull(session.componentState("soc-uart"))
                        assertEquals("Hi\n", uartState["txText"], "UARTDR writes must be captured in TX ring")
                        assertEquals(3L, uartState["txBytesTotal"])
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun linuxContiguous256mMemoryProfileIsDeclaredNonOverlappingAndCompatibleWithRelaxedBootstrapRam() {
        val catalog = AddressCatalog.bundled()
        val profileName = "linux-contiguous-256m-experimental-v1"
        val regions = assertNotNull(catalog.memoryProfiles[profileName], "Missing $profileName in bundled catalog")
        val ddr = regions.single { it["id"] == "linux-contiguous-ddr" }
        val ddrBase = (ddr["base"] as Number).toLong()
        val ddrEnd = (ddr["endExclusive"] as Number).toLong()
        assertEquals(0x1000_0000L, ddrBase)
        assertEquals(0x2000_0000L, ddrEnd)
        assertEquals(0x1000_0000L, ddrEnd - ddrBase)
        assertEquals(7L, (ddr["permissions"] as Number).toLong())

        // Verify RelaxedBootstrapRam.buildPlan filters contained regions and yields zero overlap with linux-contiguous-256m
        val baseRegions = regions.map { r ->
            com.github.nyabanban.fuji.emulator.api.MemoryRegion(
                id = r.getValue("id").toString(),
                address = (r.getValue("base") as Number).toLong(),
                size = (r.getValue("endExclusive") as Number).toLong() - (r.getValue("base") as Number).toLong(),
                permissions = (r.getValue("permissions") as Number).toInt(),
            )
        }
        val plan = RelaxedBootstrapRam.buildPlan(catalog, profileName, baseRegions)
        for (extra in plan.additionalRegions) {
            val extraEnd = extra.address + extra.size
            for (r in regions) {
                val base = (r["base"] as Number).toLong()
                val end = (r["endExclusive"] as Number).toLong()
                assertTrue(
                    extraEnd <= base || end <= extra.address,
                    "Region ${r["id"]} [$base,$end) overlaps RelaxedBootstrapRam ${extra.id} [${extra.address},$extraEnd)",
                )
            }
        }
    }

    @Test
    fun wordExclusiveStrexOnReadOnlyCowPageDeliversDataAbortAndSucceedsOnRetry() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("full-vectors-strex-cow-")
        try {
            // Synthetic 0x11000-byte image at PA 0x0:
            //   0x04000: L1 page table:
            //     L1[0] (0x04000) = 0x00000C02 (1 MiB identity section RW)
            //     L1[4] (0x04010) = 0x00005001 (L2 coarse table at 0x5000, Domain 0) -> VA 0x00400000..
            //   0x05000: L2 page table:
            //     L2[0] (0x05000) = 0x00010232 (PA 0x10000, AP[2]=1 AP[1:0]=11 RO, small page)
            //   0x10020: initial value 0x11223344
            //   0x0000:
            //     Enable MMU (TTBR0=0x4000, TTBCR=0, DACR=1, VBAR=0x1000, SCTLR.M=1),
            //     then run LDREX/STREX CAS loop against RO VA 0x00400020:
            //       - First STREX hits RO page -> WordExclusive translation refused -> DABT -> VBAR+0x10 (0x1010)
            //       - DABT handler logs DFAR->R8, DFSR->R9, upgrades L2[0] to RW (0x00010032), TLBIALL, SUBS PC, LR, #8
            //       - Retried STREX sees cleared reservation (R3=1), loops to LDREX+STREX -> succeeds (R3=0)!
            val image = ByteArray(0x11000)
            fun putWords(offset: Int, vararg insns: Long) {
                words(*insns).copyInto(image, offset)
            }
            putWords(
                0x0000,
                0xe3a0_0a04L, // 0x00: MOV R0, #0x4000 (TTBR0)
                0xee02_0f10L, // 0x04: MCR p15, 0, R0, c2, c0, 0 (TTBR0)
                0xe3a0_0000L, // 0x08: MOV R0, #0
                0xee02_0f50L, // 0x0C: MCR p15, 0, R0, c2, c0, 2 (TTBCR=0)
                0xe3a0_0001L, // 0x10: MOV R0, #1 (Domain 0 = Client)
                0xee03_0f10L, // 0x14: MCR p15, 0, R0, c3, c0, 0 (DACR)
                0xe3a0_0a01L, // 0x18: MOV R0, #0x1000 (VBAR)
                0xee0c_0f10L, // 0x1C: MCR p15, 0, R0, c12, c0, 0 (VBAR)
                0xee11_0f10L, // 0x20: MRC p15, 0, R0, c1, c0, 0 (SCTLR)
                0xe380_0001L, // 0x24: ORR R0, R0, #1 (MMU enable)
                0xee01_0f10L, // 0x28: MCR p15, 0, R0, c1, c0, 0 (SCTLR)
                0xe59f_1024L, // 0x2C: LDR R1, [PC, #0x24] (0x34+0x24=0x58 -> 0x00400020)
                0xe59f_2024L, // 0x30: LDR R2, [PC, #0x24] (0x38+0x24=0x5C -> 0xDEADBEEF)
                0xe3a0_6a05L, // 0x34: MOV R6, #0x5000 (L2 base for DABT handler)
                0xe3a0_7000L, // 0x38: MOV R7, #0 (DABT count)
                // CAS loop @ 0x3C:
                0xe191_0f9fL, // 0x3C: LDREX R0, [R1]
                0xe181_3f92L, // 0x40: STREX R3, R2, [R1]  -> 1st time faults (RO -> DABT), retry fails (R3=1), 2nd loop succeeds (R3=0)!
                0xe353_0000L, // 0x44: CMP R3, #0
                branch(0x48, 0x3c, cond = 0x1000_0000L), // 0x48: BNE 0x3C
                0xe591_4000L, // 0x4C: LDR R4, [R1] (read back written value)
                0xeaff_fffeL, // 0x50: B .
                0xe1a0_0000L, // 0x54: NOP
                0x0040_0020L, // 0x58: target VA
                0xdead_beefL, // 0x5C: store value
            )
            // VBAR + 0x10 (DABT) -> B 0x1100
            putWords(0x1010, branch(0x1010, 0x1100))
            // DABT handler at 0x1100:
            putWords(
                0x1100,
                0xe287_7001L, // 0x1100: ADD R7, R7, #1
                0xee16_8f10L, // 0x1104: MRC DFAR -> R8
                0xee15_9f10L, // 0x1108: MRC DFSR -> R9
                0xe59f_a00cL, // 0x110C: LDR R10, [PC, #0x0C] (0x1114+0x0C=0x1120 -> 0x00010032 RW)
                0xe586_a000L, // 0x1110: STR R10, [R6, #0] (upgrade L2[0] to RW)
                0xee08_af17L, // 0x1114: MCR p15, 0, R10, c8, c7, 0 (TLBIALL)
                0xe25e_f008L, // 0x1118: SUBS PC, LR, #8 (retry STREX)
                0xe1a0_0000L, // 0x111C: NOP
                0x0001_0032L, // 0x1120: PA 0x10000, RW small page
            )
            // Page tables & initial backing word
            putWords(0x4000, 0x0000_0c02L) // L1[0] = identity RW section
            putWords(0x4010, 0x0000_5001L) // L1[4] = L2 table at 0x5000
            putWords(0x5000, 0x0001_0232L) // L2[0] = PA 0x10000 RO (AP[2]=1, AP[1:0]=11)
            putWords(0x10020, 0x1122_3344L)

            val base = SyntheticGuest.prepare(
                home,
                project.resolve("build/emulator/compoments"),
                lib,
                image,
                "loader-low128-experimental-v1",
                80,
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-gic/soc-gic.yaml"),
                mapOf("coreProfile" to "four-context-functional-experimental-v1"),
            )
            val config = base + mapOf(
                "schema" to 1,
                "httpPort" to 0,
                "cbarInput" to 0xffff_0000L,
                "clusterProfile" to DynarmicCluster.PROFILE,
                "mmuProfile" to "arm-short-descriptor-native-experimental-v1",
                "wordExclusive" to mapOf("profile" to WordExclusive.BYTE_PROFILE, "reservationGranule" to 64),
                "exceptionProfile" to "armv7-full-vectors-v1",
            )
            validateLaunchConfiguration(config)

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Execute until budget exhausted at idle loop.
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "STREX on RO COW page must deliver DABT instead of failing: $st")
                        val regs = checkNotNull(session.registerSnapshot())
                        assertEquals(0x50L, regs["pc"], "CPU must finish COW STREX retry loop and reach idle loop: $st")
                        assertEquals(1L, regs["r7"], "Exactly one Data Abort expected on initial RO STREX")
                        assertEquals(0x0040_0020L, regs["r8"], "DFAR must equal faulting STREX VA")
                        assertEquals(0x0000_080fL, regs["r9"], "DFSR must report L2 Page Permission Fault with WnR=1")
                        assertEquals(0L, regs["r3"], "Retried STREX must succeed with status 0")
                        assertEquals(0x1122_3344L, regs["r0"], "LDREX must observe original word")
                        assertEquals(0xdead_beefL, regs["r4"], "Post-STREX LDR must observe stored word")
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun firmwareSessionEnforcesDynamicXnResyncAndLdrdStrdAlignmentOnFastAndOfficialLibraries() {
        val libs = listOf("FUJI_PREFETCH_ABORT_LIBRARY", "FUJI_DYNARMIC_LIBRARY")
            .mapNotNull { name -> System.getenv(name)?.takeIf { it.isNotBlank() }?.let { name to Path.of(it) } }
            .ifEmpty { listOf("default" to nativeLibrary()) }

        val cases = libs.flatMap { (label, lib) ->
            listOf(Triple("$label/Normal", lib, false), Triple("$label/Device", lib, true))
        }
        for ((label, lib, devicePage) in cases) {
            val home = Files.createTempDirectory("hw-mmu-xn-align-session-")
            try {
                // ARMv7-A DDI 0406, A3.2.1: LDRD/STRD require word alignment.
                // This image changes XN twice, then exercises a word-aligned pair on Normal
                // or Device memory and an unaligned Normal load. The final store is word
                // aligned with SCTLR.A set. Log each original FAR/FSR and check memory effects.
                val image = ByteArray(0x16000)
                fun putWords(offset: Int, vararg insns: Long) {
                    words(*insns).copyInto(image, offset)
                }

                putWords(
                    0x0000,
                    0xe3a0_0a04L, // 0x00: MOV R0, #0x4000 (TTBR0)
                    0xee02_0f10L, // 0x04: MCR TTBR0
                    0xe3a0_0000L, // 0x08: MOV R0, #0
                    0xee02_0f50L, // 0x0C: MCR TTBCR
                    0xe59f_0094L, // 0x10: LDR R0, [PC, #0x94] (0x18+0x94=0xAC -> 0x55555555)
                    0xee03_0f10L, // 0x14: MCR DACR
                    0xe3a0_0a01L, // 0x18: MOV R0, #0x1000 (VBAR)
                    0xee0c_0f10L, // 0x1C: MCR VBAR
                    0xee11_0f10L, // 0x20: MRC SCTLR
                    0xe3c0_0002L, // 0x24: BIC R0, R0, #2 (SCTLR.A = 0)
                    0xe380_0001L, // 0x28: ORR R0, R0, #1 (SCTLR.M = 1)
                    0xee01_0f10L, // 0x2C: MCR SCTLR
                    0xe3a0_4000L, // 0x30: MOV R4, #0 (result from 0xF000)
                    0xe3a0_5000L, // 0x34: MOV R5, #0 (PABT count)
                    0xe3a0_6000L, // 0x38: MOV R6, #0 (DABT count)
                    0xe3a0_7a07L, // 0x3C: MOV R7, #0x7000 (fault log cursor)
                    0xe3a0_8b3cL, // 0x40: MOV R8, #0xF000 (0x3C ROR 22 = 0xF000)
                    0xe12f_ff38L, // 0x44: BLX R8 -> PABT#1 (XN=1), handler unlocks + TLBIALLIS + ICIALLUIS -> retries -> R4=0x42!
                    0xe1a0_c004L, // 0x48: MOV R12, R4 (save 0x42)
                    0xe59f_9060L, // 0x4C: LDR R9, [PC, #0x60] (0x54+0x60=0xB4 -> 0x0000F5B7 relock XN=1)
                    0xe59f_a060L, // 0x50: LDR R10, [PC, #0x60] (0x58+0x60=0xB8 -> 0x0000803C &L2[15])
                    0xe58a_9000L, // 0x54: STR R9, [R10]
                    0xee08_9f13L, // 0x58: MCR TLBIALLIS
                    0xee07_9f11L, // 0x5C: MCR ICIALLUIS
                    0xe3a0_4000L, // 0x60: MOV R4, #0
                    0xe12f_ff38L, // 0x64: BLX R8 -> PABT#2 (relocked XN=1), handler returns to 0x68!
                    0xe3a0_9801L, // 0x68: MOV R9, #0x10000 (Normal page)
                    0xe59f_a050L, // 0x6C: LDR R10, [PC, #0x50] (0x74+0x50=0xC4 -> 0x11000 data page)
                    0xe1c9_00d4L, // 0x70: LDRD R0, R1, [R9, #4]  -> word-aligned Normal load
                    0xe1ca_20d4L, // 0x74: LDRD R2, R3, [R10, #4] -> word-aligned pair on the selected page
                    0xe1ca_00f4L, // 0x78: STRD R0, R1, [R10, #4] -> word-aligned pair on the selected page
                    0xe1c9_20d2L, // 0x7C: LDRD R2, R3, [R9, #2]  -> va & 3 == 2: alignment DABT (0x10002, 0x001)
                    0xee11_bf10L, // 0x80: MRC SCTLR -> R11
                    0xe38b_b002L, // 0x84: ORR R11, R11, #2 (SCTLR.A = 1)
                    0xee01_bf10L, // 0x88: MCR SCTLR
                    0xe1c9_00f4L, // 0x8C: STRD R0, R1, [R9, #4]  -> word-aligned Normal store, SCTLR.A=1
                    0xeaff_fffeL, // 0x90: B .
                    0xe1a0_0000L, // 0x94: NOP
                    0xe1a0_0000L, // 0x98: NOP
                    0xe1a0_0000L, // 0x9C: NOP
                    0xe1a0_0000L, // 0xA0: NOP
                    0xe1a0_0000L, // 0xA4: NOP
                    0xe1a0_0000L, // 0xA8: NOP
                    0x5555_5555L, // 0xAC: DACR
                    0xe1a0_0000L, // 0xB0: NOP
                    0x0000_f5b7L, // 0xB4: L2[15] XN=1
                    0x0000_803cL, // 0xB8: &L2[15]
                    0xe1a0_0000L, // 0xBC: NOP
                    0xe1a0_0000L, // 0xC0: NOP
                    0x0001_1000L, // 0xC4: selected buffer VA
                )

                // Target code at 0xF000: MOV R4, #0x42; BX LR
                putWords(0xf000, 0xe3a0_4042L, 0xe12f_ff1eL)
                // Normal Cacheable buffer at 0x10000
                putWords(0x10000, 0x1111_1111L, 0x2222_2222L, 0x3333_3333L, 0x4444_4444L)
                // RAM-backed data page with the memory type selected by its L2 descriptor.
                putWords(0x11000, 0xaaaa_aaa1L, 0xbbbb_bbb2L, 0xcccc_ccc3L, 0xdddd_ddd4L)

                // VBAR @ 0x1000:
                //   0x100C (PABT) -> B 0x1100
                //   0x1010 (DABT) -> B 0x1180
                putWords(0x100c, branch(0x100c, 0x1100), branch(0x1010, 0x1180))

                // PABT handler @ 0x1100:
                putWords(
                    0x1100,
                    0xe285_5001L, // 0x1100: ADD R5, R5, #1
                    0xee16_2f50L, // 0x1104: MRC IFAR -> R2
                    0xee15_3f30L, // 0x1108: MRC IFSR -> R3
                    0xe487_2004L, // 0x110C: STR R2, [R7], #4
                    0xe487_3004L, // 0x1110: STR R3, [R7], #4
                    0xe355_0001L, // 0x1114: CMP R5, #1
                    branch(0x1118, 0x1138, cond = 0x1000_0000L), // 0x1118: BNE 0x1138 (2nd PABT returns to 0x68)
                    0xe59f_2020L, // 0x111C: LDR R2, [PC, #0x20] (0x1124+0x20=0x1144 -> 0x0000F5B6 RWX)
                    0xe59f_3020L, // 0x1120: LDR R3, [PC, #0x20] (0x1128+0x20=0x1148 -> 0x0000803C &L2[15])
                    0xe583_2000L, // 0x1124: STR R2, [R3]
                    0xee08_2f13L, // 0x1128: MCR TLBIALLIS
                    0xee07_2f11L, // 0x112C: MCR ICIALLUIS
                    0xe25e_f004L, // 0x1130: SUBS PC, LR, #4 (retry 0xF000)
                    0xe1a0_0000L, // 0x1134: NOP
                    0xe3a0_e068L, // 0x1138: MOV LR, #0x68
                    0xe1b0_f00eL, // 0x113C: MOVS PC, LR
                    0xe1a0_0000L, // 0x1140: NOP
                    0x0000_f5b6L, // 0x1144: L2[15] RWX
                    0x0000_803cL, // 0x1148: &L2[15]
                )

                // DABT handler @ 0x1180: log (DFAR, DFSR) and advance past faulting insn (SUBS PC, LR, #4)
                putWords(
                    0x1180,
                    0xe286_6001L, // ADD R6, R6, #1
                    0xee16_2f10L, // MRC DFAR -> R2
                    0xee15_3f10L, // MRC DFSR -> R3
                    0xe487_2004L, // STR R2, [R7], #4
                    0xe487_3004L, // STR R3, [R7], #4
                    0xe25e_f004L, // SUBS PC, LR, #4
                )

                // L1[0] -> L2 @ 0x8000
                putWords(0x4000, 0x0000_8001L)
                val l2 = LongArray(256) { idx ->
                    val pa = idx.toLong() shl 12
                    when (idx) {
                        15 -> pa or 0x5b7L // 0xF000: RW + XN=1
                        16 -> pa or 0x5b7L // 0x10000: Normal Cacheable RW
                        17 -> pa or (if (devicePage) 0x437L else 0x473L) // TEX/C/B = 000/0/1 Device or 001/0/0 Normal NC
                        else -> pa or 0x5b6L
                    }
                }
                putWords(0x8000, *l2)

                val base = SyntheticGuest.prepare(
                    home,
                    project.resolve("build/emulator/compoments"),
                    lib,
                    image,
                    "loader-low128-experimental-v1",
                    200,
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-gic/soc-gic.yaml"),
                    mapOf("coreProfile" to "four-context-functional-experimental-v1"),
                )
                val config = base + mapOf(
                    "schema" to 1,
                    "httpPort" to 0,
                    "cbarInput" to 0xffff_0000L,
                    "clusterProfile" to DynarmicCluster.PROFILE,
                    "mmuProfile" to "arm-short-descriptor-native-experimental-v1",
                    "wordExclusive" to mapOf("profile" to WordExclusive.BYTE_PROFILE, "reservationGranule" to 64),
                    "exceptionProfile" to "armv7-full-vectors-v1",
                )
                validateLaunchConfiguration(config)

                IntegrationTest().host(home) { owner, host ->
                    owner.submit {
                        FirmwareSession(host, home, config).use { session ->
                            session.initialize()
                            while (session.step()) {
                                // Run until budget expires in B . loop at 0x90.
                            }
                            val st = session.state()
                            assertNull(st["frontier"], "$label unexpected frontier failure: $st")
                            val regs = checkNotNull(session.registerSnapshot())
                            assertEquals(0x90L, regs["pc"], "$label CPU must reach final halt loop: $st")
                            assertEquals(0x42L, regs["r12"], "$label unlocked 0xF000 call must return 0x42")
                            assertEquals(0L, regs["r4"], "$label relocked 0xF000 call must abort before executing")
                            assertEquals(2L, regs["r5"], "$label expected 2 prefetch aborts (XN=1 unlock + relock)")
                            assertEquals(1L, regs["r6"], "$label alignment data aborts")
                            assertEquals(0x2222_2222L, regs["r0"], "$label Normal Cacheable 4B-aligned LDRD R0")
                            assertEquals(0x3333_3333L, regs["r1"], "$label Normal Cacheable 4B-aligned LDRD R1")
                            assertEquals(0x7018L, regs["r7"], "$label fault log cursor")

                            val dma = checkNotNull(host.ports.find("cpu.memory", com.github.nyabanban.fuji.emulator.api.DmaMemory::class.java))
                            fun readLe32(addr: Long): Long {
                                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                dma.read(addr, buf)
                                buf.flip()
                                return buf.int.toLong() and 0xffff_ffffL
                            }
                            val expectedLog = listOf(
                                0x0000_f000L to 0x0000_000fL, // PABT#1 XN=1
                                0x0000_f000L to 0x0000_000fL, // PABT#2 relocked XN=1
                                0x0001_0002L to 0x0000_0001L, // non-word-aligned LDRD
                            )
                            assertEquals(0x2222_2222L, readLe32(0x11004), "$label STRD low word")
                            assertEquals(0x3333_3333L, readLe32(0x11008), "$label STRD high word")
                            expectedLog.forEachIndexed { idx, (expFar, expFsr) ->
                                assertEquals(expFar, readLe32(0x7000L + idx * 8L), "$label log#${idx + 1} FAR")
                                assertEquals(expFsr, readLe32(0x7004L + idx * 8L), "$label log#${idx + 1} FSR")
                            }
                        }
                    }.join()
                }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }
}
