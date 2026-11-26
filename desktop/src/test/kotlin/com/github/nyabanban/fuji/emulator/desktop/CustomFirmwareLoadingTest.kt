package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class CustomFirmwareLoadingTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))

    private fun words(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it.toInt()) }
        }.array()

    private fun branch(from: Long, to: Long): Long =
        0xea00_0000L or (((to - (from + 8L)) shr 2) and 0x00ff_ffffL)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nativeLibrary(): Path {
        val candidate = System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")
            ?: System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?: System.getenv("FUJI_NATIVE_LINUX_CACHE")
        return Path.of(checkNotNull(candidate) { "A Unicorn native library env var must be configured" })
    }

    private fun stageComponents(home: Path) {
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        Files.list(project.resolve("build/emulator/compoments")).use { paths ->
            paths.filter { it.toString().endsWith(".jar") }
                .forEach { Files.copy(it, compDir.resolve(it.fileName)) }
        }
        Files.copy(
            project.resolve("configurations/jvm/firmware/gfx100s-213.yaml"),
            home.resolve("gfx100s-213.yaml"),
        )
    }

    private fun buildPatchedType6Dat(bootCode: ByteArray): ByteArray {
        val bootSectionSize = 0x20000
        require(bootCode.size <= bootSectionSize)
        val seg0Len = 0x20000 + bootSectionSize
        val dat = ByteArray(FirmwareImage.DAT_HEADER_SIZE + seg0Len)
        for (i in FirmwareImage.DAT_HEADER_SIZE until dat.size) {
            dat[i] = 0xff.toByte()
        }
        for (i in bootCode.indices) {
            dat[0x20274 + i] = bootCode[i].toInt().inv().toByte()
        }
        val hdr = ByteBuffer.wrap(dat, 0, FirmwareImage.DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(FirmwareImage.DAT_HEADER_VERSION_OFFSET, FirmwareImage.DAT_HEADER_VERSION_EXPECTED)
        hdr.putInt(FirmwareImage.DAT_SEG0_LENGTH_OFFSET, seg0Len)
        val chk = FirmwareImage.computeSeg0Checksum(dat, seg0Len)
        hdr.putInt(FirmwareImage.DAT_SEG0_CHECKSUM_OFFSET, chk.toInt())
        return dat
    }

    @Test
    fun strictModeRefusesCustomFirmwareAndCustomRawImageWhileCliOverridesWork() {
        val baseStrict = mapOf<String, Any>(
            "schema" to 1,
            "executionMode" to "strict",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to "gfx100s-213.yaml",
            "firmwareFile" to "GXUP0004.DAT",
            "nativeLibrary" to "libunicorn.so",
            "instructionBudget" to 100,
            "enabledDevices" to listOf("soc-gic"),
        )
        validateLaunchConfiguration(baseStrict)
        validateLaunchConfiguration(baseStrict + ("allowCustomFirmware" to false))

        // strict mode MUST refuse allowCustomFirmware=true
        val strictCustomErr = assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(baseStrict + ("allowCustomFirmware" to true))
        }
        assertTrue(strictCustomErr.message.orEmpty().contains("strict"), strictCustomErr.toString())

        // strict mode MUST refuse customRawImage / customKernel
        val rawSpec = mapOf("file" to "zImage", "loadAddress" to "0x10008000")
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(baseStrict + ("customRawImage" to rawSpec))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(baseStrict + ("customKernel" to rawSpec))
        }

        // relaxed & discovery modes accept opt-in custom firmware and raw image (without firmwareFile)
        val baseRelaxed = baseStrict + ("executionMode" to RelaxedPeripheralFallback.MODE)
        validateLaunchConfiguration(baseRelaxed + ("allowCustomFirmware" to true))
        validateLaunchConfiguration((baseRelaxed - "firmwareFile") + ("customRawImage" to rawSpec))
        validateLaunchConfiguration(baseStrict + ("executionMode" to "discovery") + ("allowCustomFirmware" to true))

        // CLI overrides (--firmware, --allow-custom-firmware, --custom-kernel)
        val overridden = CustomFirmwareConfig.applyCliOverrides(
            baseRelaxed,
            arrayOf("--firmware", "GXUP0004_BANBAN.DAT", "--allow-custom-firmware"),
        )
        validateLaunchConfiguration(overridden)
        assertEquals("GXUP0004_BANBAN.DAT", overridden["firmwareFile"])
        assertEquals(true, overridden["allowCustomFirmware"])

        val cliKernel = CustomFirmwareConfig.applyCliOverrides(
            baseRelaxed - "firmwareFile",
            arrayOf(
                "--custom-kernel", "zImage",
                "--load-address", "0x10008000",
                "--dtb", "gfx100s.dtb",
                "--dtb-address", "0x11000000",
                "--initrd", "initramfs.cpio.gz",
                "--initrd-address", "0x12000000",
            ),
        )
        validateLaunchConfiguration(cliKernel)
        val parsed = checkNotNull(CustomFirmwareConfig.parseRawImage(cliKernel, RelaxedPeripheralFallback.MODE))
        assertEquals(0x1000_8000L, parsed.loadAddress)
        assertEquals(0x1000_8000L, parsed.entry)
        assertEquals(0x1100_0000L, parsed.dtbAddress)
        assertEquals(0x1200_0000L, parsed.initrdAddress)
        assertEquals(0L, parsed.r0)
        assertEquals(0xffff_ffffL, parsed.r1)
        assertEquals(0x1100_0000L, parsed.r2)
    }

    @Test
    fun patchedDatExecutesModifiedBootSyncsNorFlashSeg0AndPreservesBkupPartition() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("custom-dat-session-")
        try {
            stageComponents(home)
            Formats.atomicYaml(
                home.resolve("compoments/system-memory/system-memory.yaml"),
                mapOf("profile" to "loader-low128-experimental-v1"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-flash/soc-flash.yaml"),
                mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"),
            )

            // Prepare preserved BKUP supplemental region at NOR offset 0x03D40000 (0xF3D40000 in aperture)
            val flashDir = home.resolve("compoments/flash-image")
            Files.createDirectories(flashDir)
            val bkupBytes = ByteArray(0x10000) { 0xff.toByte() }
            words(0xcafe_babeL).copyInto(bkupBytes, 0)
            Files.write(flashDir.resolve("bkup.img"), bkupBytes)
            Formats.writeJson(
                flashDir.resolve("regions.json"),
                linkedMapOf(
                    "schema" to 1,
                    "profile" to "explicit-readonly-regions-experimental-v1",
                    "regions" to listOf(
                        linkedMapOf(
                            "id" to "bkup-user-settings",
                            "offset" to 0x03d4_0000L,
                            "length" to bkupBytes.size.toLong(),
                            "file" to "bkup.img",
                            "sha256" to sha256(bkupBytes),
                        ),
                    ),
                ),
            )
            Formats.atomicYaml(
                flashDir.resolve("flash-image.yaml"),
                mapOf(
                    "regionsProfile" to "explicit-readonly-regions-experimental-v1",
                    "regionsFile" to "regions.json",
                ),
            )

            // Patched boot code at PA 0x0 (and mirrored in NOR seg0 at 0xF0020000):
            //   0x00: LDR R7, [PC, #0x14]   ; R7 = 0x1337C0DE (custom boot sentinel)
            //   0x04: LDR R0, [PC, #0x14]   ; R0 = 0xF0020000 (NOR Flash aperture -> patched boot word 0)
            //   0x08: LDR R8, [R0]          ; R8 = [0xF0020000] == 0xE59F7014 (first instruction word)
            //   0x0C: LDR R1, [PC, #0x10]   ; R1 = 0xF3D40000 (NOR Flash aperture -> preserved BKUP)
            //   0x10: LDR R9, [R1]          ; R9 = [0xF3D40000] == 0xCAFEBABE
            //   0x14: B .
            //   0x18: NOP
            //   0x1C: 0x1337C0DE
            //   0x20: 0xF0020000
            //   0x24: 0xF3D40000
            val firstInsn = 0xe59f_7014L
            val bootCode = words(
                firstInsn,      // 0x00: LDR R7, [PC, #0x14] (PC=0x08 -> 0x1C)
                0xe59f_0014L,   // 0x04: LDR R0, [PC, #0x14] (PC=0x0C -> 0x20)
                0xe590_8000L,   // 0x08: LDR R8, [R0]
                0xe59f_1010L,   // 0x0C: LDR R1, [PC, #0x10] (PC=0x14 -> 0x24)
                0xe591_9000L,   // 0x10: LDR R9, [R1]
                0xeaff_fffeL,   // 0x14: B .
                0xe1a0_0000L,   // 0x18: NOP
                0x1337_c0deL,   // 0x1C
                0xf002_0000L,   // 0x20
                0xf3d4_0000L,   // 0x24
            )
            val patchedDat = buildPatchedType6Dat(bootCode)
            val patchedSha = sha256(patchedDat)
            Files.write(home.resolve("GXUP0004_BANBAN.DAT"), patchedDat)

            val baseConfig = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "firmwareFile" to "GXUP0004_BANBAN.DAT",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 32,
                "enabledDevices" to listOf("soc-flash", "soc-gic"),
            )

            // 1. Default allowCustomFirmware=false MUST refuse the patched DAT
            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, baseConfig).use { session ->
                        val err = assertFailsWith<IllegalArgumentException> { session.initialize() }
                        assertTrue(err.message.orEmpty().contains("firmware"), err.toString())
                    }
                }.join()
            }

            // 2. Opt-in allowCustomFirmware=true loads patched DAT, syncs NOR seg0, preserves BKUP, and executes patched code
            val customConfig = baseConfig + ("allowCustomFirmware" to true)
            validateLaunchConfiguration(customConfig)
            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, customConfig).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Run to instructionBudget.
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "Unexpected frontier: $st")
                        assertEquals(patchedSha, st["firmwareSha256"], "Session must record actual patched DAT SHA-256")
                        val regs = checkNotNull(session.registerSnapshot())
                        assertEquals(0x14L, regs["pc"], "CPU must reach idle loop")
                        assertEquals(0x1337_c0deL, regs["r7"], "Patched boot instructions must execute")
                        assertEquals(firstInsn, regs["r8"], "NOR Flash aperture 0xF0020000 must reflect synced custom seg0")
                        assertEquals(0xcafe_babeL, regs["r9"], "NOR Flash BKUP region 0xF3D40000 must remain preserved")
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun customRawImageBootsWithoutDatOnContiguous256mDdrWithFullVectorsAndUartTx() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("custom-raw-image-")
        try {
            stageComponents(home)
            Formats.atomicYaml(
                home.resolve("compoments/system-memory/system-memory.yaml"),
                mapOf("profile" to "linux-contiguous-256m-experimental-v1"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-uart/soc-uart.yaml"),
                mapOf("profile" to "pl011-tx-ready-experimental-v1", "initialFill" to 0L, "logTx" to true),
            )

            // Minimal ARM32 raw kernel at 0x10008000 (with VBAR table at 0x10009000):
            //   - Verifies ARM32 Linux boot protocol registers (r0=0, r1=0xFFFFFFFF, r2=0x11000000)
            //   - Reads DTB magic word from [R2] -> R4 (0xD00DFEED)
            //   - Sets VBAR = 0x10009000 and executes SVC #0 (increments R5 -> 1)
            //   - Writes "CUSTOM-RAW-OK\n" to soc-uart UARTDR (0xFFF3A000)
            //   - Loops at B .
            val baseAddr = 0x1000_8000L
            val image = ByteArray(0x1200)
            fun putWords(offset: Int, vararg insns: Long) {
                words(*insns).copyInto(image, offset)
            }
            val msg = "CUSTOM-RAW-OK\n"
            val msgBytes = msg.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            msgBytes.copyInto(image, 0x100)

            putWords(
                0x0000,
                0xe592_4000L, // 0x00: LDR R4, [R2]           ; read DTB magic (0xD00DFEED)
                0xe59f_6038L, // 0x04: LDR R6, [PC, #0x38]    ; 0x0C+0x38=0x44 -> VBAR (0x10009000)
                0xee0c_6f10L, // 0x08: MCR p15, 0, R6, c12, c0, 0 ; set VBAR
                0xe3a0_5000L, // 0x0C: MOV R5, #0             ; SVC counter
                0xef00_0000L, // 0x10: SVC #0                 ; -> VBAR+0x08 -> R5++ -> MOVS PC, LR
                0xe59f_602cL, // 0x14: LDR R6, [PC, #0x2C]    ; 0x1C+0x2C=0x48 -> UARTDR (0xFFF3A000)
                0xe59f_702cL, // 0x18: LDR R7, [PC, #0x2C]    ; 0x20+0x2C=0x4C -> msg addr (0x10008100)
                // Loop @ 0x1C:
                0xe4d7_8001L, // 0x1C: LDRB R8, [R7], #1
                0xe358_0000L, // 0x20: CMP R8, #0
                0x0a00_0001L, // 0x24: BEQ 0x30 (done)
                0xe586_8000L, // 0x28: STR R8, [R6]           ; write UARTDR
                branch(0x2c, 0x1c), // 0x2C: B 0x1C
                0xeaff_fffeL, // 0x30: B . (done)
                0xe1a0_0000L, // 0x34: NOP
                0xe1a0_0000L, // 0x38: NOP
                0xe1a0_0000L, // 0x3C: NOP
                0xe1a0_0000L, // 0x40: NOP
                0x1000_9000L, // 0x44: VBAR
                0xfff3_a000L, // 0x48: UARTDR
                baseAddr + 0x100L, // 0x4C: message pointer
            )
            // VBAR at 0x10009000: SVC vector at +0x08 -> B +0x100 (0x10009100)
            putWords(0x1008, branch(0x1000_9008L, 0x1000_9100L))
            // SVC handler at 0x10009100: ADD R5, R5, #1; MOVS PC, LR
            putWords(0x1100, 0xe285_5001L, 0xe1b0_f00eL)

            val dtbBytes = words(0xd00d_feedL, 0x0000_0040L)
            val initrdBytes = "INITRD-TEST".toByteArray(Charsets.US_ASCII)
            Files.write(home.resolve("raw-kernel.bin"), image)
            Files.write(home.resolve("test.dtb"), dtbBytes)
            Files.write(home.resolve("initrd.cpio"), initrdBytes)

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                // Deliberately NO firmwareFile (no GXUP0004.DAT required!)
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 150,
                "enabledDevices" to listOf("soc-gic", "soc-uart"),
                "exceptionProfile" to "armv7-full-vectors-v1",
                "customRawImage" to mapOf(
                    "file" to "raw-kernel.bin",
                    "loadAddress" to "0x10008000",
                    "dtbFile" to "test.dtb",
                    "dtbAddress" to "0x11000000",
                    "initrdFile" to "initrd.cpio",
                    "initrdAddress" to "0x12000000",
                ),
            )
            validateLaunchConfiguration(config)

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Execute to budget.
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "Unexpected frontier: $st")
                        assertEquals(sha256(image), st["firmwareSha256"])
                        val regs = checkNotNull(session.registerSnapshot())
                        assertEquals(baseAddr + 0x30L, regs["pc"], "CPU must reach halt loop")
                        assertEquals(0L, regs["r0"], "ARM32 Linux boot r0 == 0")
                        assertEquals(0xffff_ffffL, regs["r1"], "ARM32 Linux boot r1 == 0xFFFFFFFF")
                        assertEquals(0x1100_0000L, regs["r2"], "ARM32 Linux boot r2 == dtbAddress")
                        assertEquals(0xd00d_feedL, regs["r4"], "Guest must read DTB magic from 0x11000000")
                        assertEquals(1L, regs["r5"], "SVC #0 must execute via VBAR under armv7-full-vectors-v1")
                        val uart = checkNotNull(session.componentState("soc-uart"))
                        assertEquals(msg, uart["txText"], "UARTDR must capture full TX message")
                        assertContentEquals(initrdBytes, session.memory(0x1200_0000L, initrdBytes.size))
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
