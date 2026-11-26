package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

class BootRomColdStartIntegrationTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))
    private val fixtures = IntegrationTest()

    // The native ARM core delivers an unmapped store to the guest's data-abort
    // vector. Read the actual vector entry before another exception overwrites LR_abt.
    private fun assertGuestStoreAbort(
        runtime: EmulatorRuntime, result: Map<String, Any?>,
        instruction: Long, address: Long, value: Long, callSite: Long,
    ) {
        assertEquals("instruction-limit", result["status"])
        assertNull(result["frontier"])
        val checkpoints = (result["checkpoints"] as List<*>).map { it as Map<*, *> }
        val vector = checkpoints.first {
            it["address"] == 0x10L && it["lr"] == instruction + 8L && (it["cpsr"] as Long) and 0x1fL == 0x17L
        }
        assertEquals(address, vector["r0"])
        assertEquals(value, vector["r1"])
        assertTrue(checkpoints.any { it["address"] == callSite && (it["tick"] as Long) < (vector["tick"] as Long) })
        val opcode = runtime.owner.submit { runtime.session.memory(instruction, 4) }.join()
        assertContentEquals(byteArrayOf(0x00, 0x10, 0x80.toByte(), 0xe5.toByte()), opcode, "Faulting STR r1, [r0]")
    }

    @Test
    fun bootRomExperimentCatalogAndConfigurationValidateAndRefuseMissingBootRomFile() {
        val raw = Formats.read(project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml"))
        val home = fixtures.fixture()
        try {
            val runtime = CatalogRuntime(AddressCatalog.bundled(), "strict", home, emptyMap())
            runtime.validateFirmware(raw)
            val missingBootRomConfig = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                "firmwareFile" to "unused.dat",
                "nativeLibrary" to "unused.so",
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-system-control"),
            )
            val e = assertFails {
                EmulatorRuntime(home, missingBootRomConfig).use { it.probe() }
            }
            val cause = generateSequence(e) { it.cause }.last()
            assertIs<IllegalArgumentException>(cause)
            assertEquals("boot-rom-experiment requires bootRomFile", cause.message)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun coldStartExecutesRealBootRomThroughSpiNorProbeToFff41500WithStrictParity() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        assumeTrue(!firmware.isNullOrBlank() && !native.isNullOrBlank() && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, native Unicorn library, and GFX100S_DUMP.zip required")

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val expectedHeader = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/partitions/bank-00_00000000.bin"))
            zip.getInputStream(entry).use { it.readNBytes(24) }
        }

        val tracePoints = listOf(
            "0xfff00000", "0xfff00100", "0xfff00384", "0xfff00730",
            "0xfff033c4", "0xfff03490", "0xfff035e8", "0xfff0041c",
        )
        val observations = mutableMapOf<String, Map<String, Any?>>()
        for (mode in listOf("strict", "discovery")) {
            val home = fixtures.fixture()
            try {
                Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
                Formats.atomicYaml(home.resolve("compoments/system-memory/system-memory.yaml"),
                    mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
                Formats.atomicYaml(home.resolve("compoments/soc-system-control/boot-strap.json"),
                    mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8))
                Formats.atomicYaml(home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                    mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"))
                Formats.atomicYaml(home.resolve("compoments/soc-flash/soc-flash.yaml"),
                    mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"))
                Formats.atomicYaml(home.resolve("compoments/flash-image/flash-image.yaml"),
                    mapOf("sourcePort" to "firmware.update", "tailProfile" to "strict", "regionsProfile" to "strict", "regionsFile" to ""))

                val config = mapOf<String, Any>(
                    "schema" to 1,
                    "executionMode" to mode,
                    "components" to "compoments",
                    "httpPort" to 0,
                    "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                    "firmwareFile" to firmware!!,
                    "bootRomFile" to "boot-rom.bin",
                    "nativeLibrary" to native!!,
                    "instructionBudget" to 100000,
                    "enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"),
                    "tracePoints" to tracePoints,
                )
                validateLaunchConfiguration(config)
                EmulatorRuntime(home, config).use { runtime ->
                    val actual = runtime.probe()
                    observations[mode] = actual
                    assertEquals("single-core boot-rom-experiment", actual["mode"])
                    assertEquals(true, actual["bootRomExecuted"])
                    val expectedStatus = if (mode == "strict") "unmapped MMIO 0xfff41500 size 4" else "Discovery access refused (reason 1)"
                    assertEquals(expectedStatus, actual["status"])

                    val frontier = actual["frontier"] as Map<*, *>
                    assertEquals(0xfff41500L, frontier["addressPhysical"])
                    assertEquals(0xe3f4e3f4L, frontier["value"])

                    val devices = actual["devices"] as Map<*, *>
                    val sysCtrl = devices["soc-system-control"] as Map<*, *>
                    assertEquals(1L, sysCtrl["booleanLastWrite"])
                    assertEquals(8L, (sysCtrl["bootStrapFff4a000"] as Map<*, *>)["value"])

                    val checkpoints = (actual["checkpoints"] as List<*>).map { (it as Map<*, *>)["address"] as Long }
                    for (hexPoint in tracePoints) {
                        val expectedAddr = hexPoint.substring(2).toLong(16)
                        assertTrue(expectedAddr in checkpoints, "Missing trace point $hexPoint in $checkpoints")
                    }

                    val headerRam = runtime.owner.submit { runtime.session.memory(0xfff2e800L, 24) }.join()
                    assertContentEquals(expectedHeader, headerRam)
                    val words = IntArray(6) {
                        ByteBuffer.wrap(headerRam, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    }
                    assertEquals(0xffffffff.toInt(), words[0])
                    assertEquals(0, words[1])
                    assertEquals(0xaaaaaaaa.toInt(), words[2])
                    assertEquals(0x55555555, words[3])
                    assertEquals(0x1aa0, words[4])
                    assertEquals(0x1e, words[5])

                    val lowRam = runtime.owner.submit { runtime.session.memory(0L, 32) }.join()
                    assertTrue(lowRam.all { it == 0.toByte() }, "RAM at 0x0 must remain zero-initialized before stage-1 copy")
                }
            } finally {
                home.toFile().deleteRecursively()
            }
        }

        val strict = observations.getValue("strict")
        val discovery = observations.getValue("discovery")
        assertEquals(strict["attempts"], discovery["attempts"])
        assertEquals(strict["registers"], discovery["registers"])
        val sf = strict["frontier"] as Map<*, *>
        val df = discovery["frontier"] as Map<*, *>
        assertEquals(sf["addressPhysical"], df["addressPhysical"])
        assertEquals(sf["value"], df["value"])
        assertEquals(sf["pcVirtual"], df["pcVirtual"])
        assertEquals(sf["tick"], df["tick"])
    }

    @Test
    fun bootRomWithoutStrapInputRefusesFff4a000SilentlyReturningZeroIsForbidden() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        assumeTrue(!firmware.isNullOrBlank() && !native.isNullOrBlank() && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, native Unicorn library, and GFX100S_DUMP.zip required")

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val home = fixtures.fixture()
        try {
            Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
            Formats.atomicYaml(home.resolve("compoments/system-memory/system-memory.yaml"),
                mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
            Formats.atomicYaml(home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                mapOf("booleanWrite" to true))
            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to "strict",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                "firmwareFile" to firmware!!,
                "bootRomFile" to "boot-rom.bin",
                "nativeLibrary" to native!!,
                "instructionBudget" to 10000,
                "enabledDevices" to listOf("soc-system-control"),
            )
            EmulatorRuntime(home, config).use { runtime ->
                val actual = runtime.probe()
                assertEquals(true, actual["bootRomExecuted"])
                assertEquals("unmapped MMIO 0xfff4a000 size 4", actual["status"])
                val frontier = actual["frontier"] as Map<*, *>
                assertEquals(0xfff4a000L, frontier["addressPhysical"])
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun coldStartDiscoveryHandoffAdvancesThroughInitTableIntoRealNorFlashStage1Ipl() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        assumeTrue(
            !firmware.isNullOrBlank() && !native.isNullOrBlank() && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, native Unicorn library, and GFX100S_DUMP.zip required",
        )

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val expectedStage1 = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/partitions/bank-00_00000000.bin"))
            zip.getInputStream(entry).use { stream ->
                val prefix = stream.readNBytes(0x800 + 0x1aa0)
                prefix.copyOfRange(0x800, 0x800 + 0x1aa0)
            }
        }

        val tracePoints = listOf(
            "0xfff00000", "0xfff0041c", "0xfff03614", "0xfff00250",
            "0xfff0025c", "0xfff10000", "0xfff100a4", "0xfff11a04",
        )
        val home = fixtures.fixture()
        try {
            Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
            Formats.atomicYaml(
                home.resolve("compoments/system-memory/system-memory.yaml"),
                mapOf("profile" to "boot-rom-cold-start-experimental-v1"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-system-control/boot-strap.json"),
                mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-flash/soc-flash.yaml"),
                mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/flash-image/flash-image.yaml"),
                mapOf("sourcePort" to "firmware.update", "tailProfile" to "strict", "regionsProfile" to "strict", "regionsFile" to ""),
            )

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to "discovery",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                "firmwareFile" to firmware!!,
                "bootRomFile" to "boot-rom.bin",
                "nativeLibrary" to native!!,
                "instructionBudget" to 100000,
                "enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"),
                "discovery" to mapOf("bootRomColdStart" to "bootrom-stage1-handoff-v1"),
                "tracePoints" to (tracePoints + listOf("0x00000010", "0xfff100b4")).distinct(),
                "tracePerPointLimit" to 8,
            )
            validateLaunchConfiguration(config)
            EmulatorRuntime(home, config).use { runtime ->
                val actual = runtime.probe()
                assertEquals("single-core boot-rom-experiment", actual["mode"])
                assertEquals(true, actual["bootRomExecuted"])
                assertGuestStoreAbort(runtime, actual, 0xfff11a0cL, 0xfb000000L, 1L, 0xfff100b4L)

                val checkpoints = (actual["checkpoints"] as List<*>).map { (it as Map<*, *>)["address"] as Long }
                for (hexPoint in tracePoints) {
                    val expectedAddr = hexPoint.substring(2).toLong(16)
                    assertTrue(expectedAddr in checkpoints, "Missing trace point $hexPoint in $checkpoints")
                }

                // Real soc-system-control accepted entries 2 & 3 of the 30-entry init table
                val devices = actual["devices"] as Map<*, *>
                val sysCtrl = devices["soc-system-control"] as Map<*, *>
                val wordsA = (sysCtrl["wordsA"] as List<*>).map { (it as Number).toLong() }
                val wordsB = (sysCtrl["wordsB"] as List<*>).map { (it as Number).toLong() }
                assertEquals(3L, wordsA[0x38 / 4], "0xFF70F038 must be written by init-table entry 2")
                assertEquals(1L, wordsB[0x34 / 4], "0xFFF48334 must be written by init-table entry 3")

                // Boot ROM copied all 6,816 bytes (0x1AA0) of real Stage-1 NOR Flash IPL into 0xFFF10000 SRAM
                val stage1Ram = runtime.owner.submit { runtime.session.memory(0xfff10000L, 0x1aa0) }.join()
                assertContentEquals(expectedStage1, stage1Ram)

                // Boot ROM wrote 0x900D ("GOOD") to 0xFFF4A100 before branching to 0xFFF10000
                val discovery = actual["discovery"] as Map<*, *>
                val regions = (discovery["regions"] as List<*>).map { it as Map<*, *> }
                val bootRomReceipt = regions.single { it["id"] == "diagnostic.bootrom.gpio-global-fff41500" }["bootRomColdStart"] as Map<*, *>
                assertEquals(0xe3f4L, bootRomReceipt["gpioGlobalFff41500"])
                assertEquals(5L, bootRomReceipt["sysCtrlFff48180"])
                assertEquals(20, bootRomReceipt["dram0PreInitNonZeroCount"])
                assertEquals(false, bootRomReceipt["dram0CommitPending"])
                assertEquals(1L, bootRomReceipt["dram0CommitCount"])
                assertEquals(0x900dL, bootRomReceipt["bootStatusFff4a100"])
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun coldStartDiscoveryStage1DdrInitAdvancesThroughStage2XipIntoRealBootBin() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        assumeTrue(
            !firmware.isNullOrBlank() && !native.isNullOrBlank() && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, native Unicorn library, and GFX100S_DUMP.zip required",
        )

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val expectedBootBinPrefix = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/partitions/bank-00_00000000.bin"))
            zip.getInputStream(entry).use { stream ->
                val raw = stream.readNBytes(0x20000 + 0x10000)
                raw.copyOfRange(0x20000, 0x20000 + 0x10000)
            }
        }

        val tracePoints = listOf(
            "0xfff00000", "0xfff0025c", "0xfff10000", "0xfff10a04",
            "0xfff10b2c", "0xfff10778", "0xfff10f10", "0xfff1003c",
            "0xf0007360", "0xf00073a8", "0xf000738c", "0x00000000",
            "0x00000100", "0x000015dc",
        )
        val home = fixtures.fixture()
        try {
            Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
            Formats.atomicYaml(
                home.resolve("compoments/system-memory/system-memory.yaml"),
                mapOf("profile" to "boot-rom-cold-start-experimental-v1"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-system-control/boot-strap.json"),
                mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-flash/soc-flash.yaml"),
                mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/flash-image/flash-image.yaml"),
                mapOf("sourcePort" to "firmware.update", "tailProfile" to "strict", "regionsProfile" to "strict", "regionsFile" to ""),
            )

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to "discovery",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                "firmwareFile" to firmware!!,
                "bootRomFile" to "boot-rom.bin",
                "nativeLibrary" to native!!,
                "instructionBudget" to 250000,
                "enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"),
                "discovery" to mapOf("bootRomColdStart" to "bootrom-stage1-ddr-init-v2"),
                "tracePoints" to (tracePoints + listOf("0x00000010", "0x000069a0")).distinct(),
                "tracePerPointLimit" to 8,
            )
            validateLaunchConfiguration(config)
            EmulatorRuntime(home, config).use { runtime ->
                val actual = runtime.probe()
                assertEquals("single-core boot-rom-experiment", actual["mode"])
                assertEquals(true, actual["bootRomExecuted"])
                assertGuestStoreAbort(runtime, actual, 0x00005298L, 0xfff3c348L, 540000L, 0x000069a0L)

                val checkpoints = (actual["checkpoints"] as List<*>).map { (it as Map<*, *>)["address"] as Long }
                for (hexPoint in tracePoints) {
                    val expectedAddr = hexPoint.substring(2).toLong(16)
                    assertTrue(expectedAddr in checkpoints, "Missing trace point $hexPoint in $checkpoints")
                }

                // Real Stage-2 XIP loader at 0xF0007360 copied real GXUP0004.DAT boot.bin from 0xF0020000 into zeroed RAM 0x0
                val lowRamText = runtime.owner.submit { runtime.session.memory(0L, 0x10000) }.join()
                assertContentEquals(expectedBootBinPrefix, lowRamText)

                // Real soc-system-control saw all Stage-1 RMW clock/reset enables
                val devices = actual["devices"] as Map<*, *>
                val sysCtrl = devices["soc-system-control"] as Map<*, *>
                val wordsA = (sysCtrl["wordsA"] as List<*>).map { (it as Number).toLong() }
                val wordsB = (sysCtrl["wordsB"] as List<*>).map { (it as Number).toLong() }
                assertEquals(0x1fL, wordsA[0x38 / 4], "0xFF70F038 must reach 0x1F after Stage-1 DDR enables")
                assertEquals(1L, wordsB[0x34 / 4], "0xFFF48334 must be 1")

                val discovery = actual["discovery"] as Map<*, *>
                val regions = (discovery["regions"] as List<*>).map { it as Map<*, *> }
                val receipt = regions.single { it["id"] == "diagnostic.bootrom.gpio-global-fff41500" }["bootRomColdStart"] as Map<*, *>
                assertEquals("bootrom-stage1-ddr-init-v2", receipt["profile"])
                assertEquals(0xe3f4L, receipt["gpioGlobalFff41500"])
                assertEquals(5L, receipt["sysCtrlFff48180"])
                assertEquals(0x900dL, receipt["bootStatusFff4a100"])
                assertEquals(1L, receipt["stage1FlagFb000000"])
                assertEquals(4, receipt["dramInitStage"])
                assertEquals(false, receipt["dramCtl0AckPending"])
            }

            // Also run with the already-modeled stock SoC devices enabled to confirm real boot.bin reaches its SPI NOR RDID probe
            val stockSocConfig = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to "discovery",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                "firmwareFile" to firmware,
                "bootRomFile" to "boot-rom.bin",
                "nativeLibrary" to native,
                "instructionBudget" to 500000,
                "enabledDevices" to listOf(
                    "soc-system-control", "soc-flash", "soc-gpio",
                    "soc-timer", "soc-gic", "soc-dma", "soc-keyed-io", "soc-configuration", "soc-method4",
                ),
                "discovery" to mapOf("bootRomColdStart" to "bootrom-stage1-ddr-init-v2"),
                "tracePoints" to (tracePoints + "0x00007a98"),
            )
            validateLaunchConfiguration(stockSocConfig)
            EmulatorRuntime(home, stockSocConfig).use { runtime ->
                val actual = runtime.probe()
                assertEquals(true, actual["bootRomExecuted"])
                assertEquals("flash identity missing or exceeds three bytes", actual["status"])
                assertEquals(64556L, (actual["attempts"] as Number).toLong())
                val frontier = actual["frontier"] as Map<*, *>
                assertEquals("write", frontier["kind"])
                assertEquals(0xfffd8090L, frontier["addressPhysical"])
                assertEquals(0x9f800001L, frontier["value"])
                // The caller loads the RDID register and value, then branches to
                // the STR helper. The host frontier PC identifies a JIT block.
                val call = (actual["checkpoints"] as List<*>).map { it as Map<*, *> }
                    .single { it["address"] == 0x7a98L }
                assertEquals(frontier["addressPhysical"], call["r0"])
                assertEquals(frontier["value"], call["r1"])
                val registers = actual["registers"] as Map<*, *>
                assertEquals(0x7a9cL, registers["lr"])
                val checkpoints = (actual["checkpoints"] as List<*>).map { (it as Map<*, *>)["address"] as Long }
                for (hexPoint in tracePoints) {
                    val expectedAddr = hexPoint.substring(2).toLong(16)
                    assertTrue(expectedAddr in checkpoints, "Missing trace point $hexPoint in $checkpoints")
                }
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun coldStartDiscoveryBootQspiJedecAdvancesThroughFourCoreWakeupAndNorLoadToKernelEntry() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        assumeTrue(
            !firmware.isNullOrBlank() && !native.isNullOrBlank() && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, native Unicorn library, and GFX100S_DUMP.zip required",
        )

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val expectedKernelPrefix = com.github.nyabanban.fuji.emulator.firmware.FirmwareImage(
            com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
                project.resolve("configurations/jvm/firmware/gfx100s-213.yaml"),
            ),
            Path.of(firmware!!),
        ).decode("kernel").copyOfRange(0, 0x1000)

        val tracePoints = listOf(
            "0xfff00000", "0xfff0025c", "0xfff00294", "0xfff002f8",
            "0xfff10000", "0xf0007360", "0x00000000", "0x00008838",
            "0x00001140", "0x000011a4", "0x00057950",
        )
        // Verify BOTH real AVL SPI NOR chips supported by boot.bin 0x8838:
        // - Micron MT25QU512ABB (0x20 BB 20 -> 0x20BB20L -> Table 3 @ 0xC040)
        // - Macronix MX25U51245G (0xC2 25 3A -> 0x3A25C2L -> Table 1 @ 0xBCF8 + WREN 0x06 + RDSR 0x05 + WRSR 0x01 0xC740)
        for ((chipName, jedecId, expectWrsr) in listOf(
            Triple("micron-20bb20", 0x20BB20L, false),
            Triple("macronix-c2253a", 0x3A25C2L, true),
        )) {
            val home = fixtures.fixture()
            try {
                Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
                Formats.atomicYaml(
                    home.resolve("compoments/system-memory/system-memory.yaml"),
                    mapOf("profile" to "boot-rom-cold-start-segments-experimental-v2"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-system-control/boot-strap.json"),
                    mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                    mapOf("booleanWrite" to true, "serialWrite" to true, "bootStrapInputFile" to "boot-strap.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-gic/soc-gic.yaml"),
                    mapOf("coreProfile" to com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster.PROFILE),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-flash/soc-flash.yaml"),
                    mapOf(
                        "profile" to "gfx100s-boot-qspi-jedec-functional-experimental-v5",
                        "jedec" to jedecId,
                        "imagePort" to "flash.image",
                        "interruptPort" to "soc-gic.lines",
                    ),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-dma/soc-dma.yaml"),
                    mapOf(
                        "softwareProfile" to "software-request44-experimental-v1",
                        "flashPeerPort" to "soc-flash.dma",
                        "interruptPort" to "soc-gic.lines",
                    ),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-method4/soc-method4.yaml"),
                    mapOf("interruptPort" to "soc-gic.lines"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-spi/soc-spi.yaml"),
                    mapOf("profile" to "spi-fifo-peer-experimental-v2", "peerPort" to "power-peer.spi", "portsFile" to "ports.json"),
                )
                Files.copy(
                    project.resolve("configurations/jvm/experiments/spi0-missing.json"),
                    home.resolve("compoments/soc-spi/ports.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/power-peer/power-peer.yaml"),
                    mapOf("profile" to "checksum-registers-experimental-v1", "inputFile" to "words.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-adc/soc-adc.yaml"),
                    mapOf("profile" to "adc-source-functional-experimental-v1", "inputFile" to "samples.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-i2c/soc-i2c.yaml"),
                    mapOf("profile" to "byte-i2c-functional-experimental-v1", "inputFile" to "controllers.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/i2c-bus/i2c-bus.yaml"),
                    mapOf("profile" to "explicit-i2c-buses-experimental-v1", "inputFile" to "buses.json"),
                )
                for ((srcName, dstRel) in listOf(
                    "power-raw-8000" to "power-peer/words",
                    "adc-ramp" to "soc-adc/samples",
                    "i2c-empty-lab-routes" to "soc-i2c/controllers",
                    "i2c-empty-buses" to "i2c-bus/buses",
                )) {
                    Files.copy(
                        project.resolve("configurations/jvm/experiments/$srcName.json"),
                        home.resolve("compoments/$dstRel.json"),
                    )
                }
                Formats.atomicYaml(
                    home.resolve("compoments/flash-image/flash-image.yaml"),
                    mapOf(
                        "sourcePort" to "firmware.update",
                        "tailProfile" to "dat-end-block-ff-experimental-v1",
                        "regionsProfile" to "strict",
                        "regionsFile" to "",
                    ),
                )

                val config = mapOf<String, Any>(
                    "schema" to 1,
                    "executionMode" to "discovery",
                    "components" to "compoments",
                    "httpPort" to 0,
                    "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                    "firmwareFile" to firmware,
                    "bootRomFile" to "boot-rom.bin",
                    "nativeLibrary" to native!!,
                    "instructionBudget" to 2500000,
                    "cbarInput" to 0xffff0000L,
                    "clusterProfile" to com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster.PROFILE,
                    "enabledDevices" to listOf(
                        "soc-system-control", "soc-flash", "soc-gpio",
                        "soc-timer", "soc-gic", "soc-dma", "soc-keyed-io",
                        "soc-configuration", "soc-method4", "soc-spi", "soc-adc", "soc-i2c",
                    ),
                    "discovery" to mapOf("bootRomColdStart" to "bootrom-qspi-kernel-v3"),
                    "tracePoints" to tracePoints,
                )
                validateLaunchConfiguration(config)
                EmulatorRuntime(home, config).use { runtime ->
                    val actual = runtime.probe()
                    assertEquals("four-context boot-rom-experiment", actual["mode"], chipName)
                    assertEquals(true, actual["bootRomExecuted"], chipName)

                    val checkpoints = (actual["checkpoints"] as List<*>).map { it as Map<*, *> }
                    val hitAddresses = checkpoints.map { it["address"] as Long }.toSet()
                    for (hexPoint in tracePoints) {
                        val expectedAddr = hexPoint.substring(2).toLong(16)
                        assertTrue(
                            expectedAddr in hitAddresses,
                            "$chipName missing trace point $hexPoint; status=${actual["status"]} frontier=${actual["frontier"]}",
                        )
                    }

                    // All 4 Cortex-A7 cores must independently wake from boot-rom WFI -> boot.bin -> 0xB3E4 barrier -> kernel.bin @ 0x00057950!
                    val kernelEntryCores = checkpoints
                        .filter { it["address"] == 0x00057950L }
                        .map { (it["core"] as Number).toInt() }
                        .toSet()
                    assertEquals(setOf(0, 1, 2, 3), kernelEntryCores, "$chipName expected all 4 cores at kernel.bin 0x00057950")

                    // Verify real boot-rom.bin secondary-core mailboxes 0xFFF4A104..0xFFF4A10C received 0x1000900D/0x2000900D/0x3000900D!
                    val discovery = actual["discovery"] as Map<*, *>
                    val regions = (discovery["regions"] as List<*>).map { it as Map<*, *> }
                    val receipt = regions.single { it["id"] == "diagnostic.bootrom.gpio-global-fff41500" }["bootRomColdStart"] as Map<*, *>
                    assertEquals("bootrom-qspi-kernel-v3", receipt["profile"], chipName)
                    assertEquals(0x900dL, receipt["bootStatusFff4a100"], chipName)
                    assertEquals(listOf(0x1000900dL, 0x2000900dL, 0x3000900dL), receipt["secondaryBootStatusFff4a104"], chipName)

                    // Verify Macronix WRSR 0x01 latched SR=0x40 (QE) & CR=0xC7 in soc-flash
                    val devices = actual["devices"] as Map<*, *>
                    val flashState = devices["soc-flash"] as Map<*, *>
                    val pioEnable = flashState["pioEnableStatus"] as Map<*, *>
                    if (expectWrsr) {
                        assertEquals(listOf(0x40L, 0L), pioEnable["statusRegisterBySelector"], chipName)
                        assertEquals(listOf(0xc7L, 0L), pioEnable["configRegisterBySelector"], chipName)
                    } else {
                        assertEquals(listOf(0L, 0L), pioEnable["statusRegisterBySelector"], chipName)
                    }

                    // Verify real NOR-loaded kernel.bin in RAM at 0x00040000 matches GXUP0004.DAT!
                    val kernelRam = runtime.owner.submit { runtime.session.memory(0x40000L, 0x1000) }.join()
                    assertContentEquals(expectedKernelPrefix, kernelRam, "$chipName RAM 0x40000 must match GXUP0004.DAT kernel.bin")
                }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun coldStartRelaxedDynarmicJitExecutesFourCoreBootRomThroughQspiJedecToKernelEntryAndBeyond() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
        val native = System.getenv("FUJI_DYNARMIC_LIBRARY")
        val dumpZip = project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        val dynLib = CpuEngineConfig.findDynarmicLibrary(project, emptyMap())
        assumeTrue(
            !firmware.isNullOrBlank() && dynLib != null && Files.isRegularFile(dumpZip),
            "GXUP0004.DAT, Dynarmic JIT library, and GFX100S_DUMP.zip required",
        )

        val bootRomBytes = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val expectedBootPrefix = ZipFile(dumpZip.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/partitions/bank-00_00000000.bin"))
            zip.getInputStream(entry).use { stream ->
                val raw = stream.readNBytes(0x20000 + 0x1000)
                raw.copyOfRange(0x20000, 0x20000 + 0x1000)
            }
        }
        val expectedKernelPrefix = com.github.nyabanban.fuji.emulator.firmware.FirmwareImage(
            com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
                project.resolve("configurations/jvm/firmware/gfx100s-213.yaml"),
            ),
            Path.of(firmware!!),
        ).decode("kernel").copyOfRange(0, 0x1000)

        for ((chipName, jedecId, expectWrsr) in listOf(
            Triple("micron-20bb20", 0x20BB20L, false),
            Triple("macronix-c2253a", 0x3A25C2L, true),
        )) {
            val home = fixtures.fixture()
            try {
                Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
                Formats.atomicYaml(
                    home.resolve("compoments/system-memory/system-memory.yaml"),
                    mapOf("profile" to "boot-rom-cold-start-segments-experimental-v2"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-system-control/boot-strap.json"),
                    mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-system-control/soc-system-control.yaml"),
                    mapOf("booleanWrite" to true, "serialWrite" to true, "bootStrapInputFile" to "boot-strap.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-gic/soc-gic.yaml"),
                    mapOf("coreProfile" to com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster.PROFILE),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-flash/soc-flash.yaml"),
                    mapOf(
                        "profile" to "gfx100s-boot-qspi-jedec-functional-experimental-v5",
                        "jedec" to jedecId,
                        "imagePort" to "flash.image",
                        "interruptPort" to "soc-gic.lines",
                    ),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-dma/soc-dma.yaml"),
                    mapOf(
                        "softwareProfile" to "software-request44-experimental-v1",
                        "flashPeerPort" to "soc-flash.dma",
                        "interruptPort" to "soc-gic.lines",
                    ),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-method4/soc-method4.yaml"),
                    mapOf("interruptPort" to "soc-gic.lines"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-spi/soc-spi.yaml"),
                    mapOf("profile" to "spi-fifo-peer-experimental-v2", "peerPort" to "power-peer.spi", "portsFile" to "ports.json"),
                )
                Files.copy(
                    project.resolve("configurations/jvm/experiments/spi0-missing.json"),
                    home.resolve("compoments/soc-spi/ports.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/power-peer/power-peer.yaml"),
                    mapOf("profile" to "checksum-registers-experimental-v1", "inputFile" to "words.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-adc/soc-adc.yaml"),
                    mapOf("profile" to "adc-source-functional-experimental-v1", "inputFile" to "samples.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/soc-i2c/soc-i2c.yaml"),
                    mapOf("profile" to "byte-i2c-functional-experimental-v1", "inputFile" to "controllers.json"),
                )
                Formats.atomicYaml(
                    home.resolve("compoments/i2c-bus/i2c-bus.yaml"),
                    mapOf("profile" to "explicit-i2c-buses-experimental-v1", "inputFile" to "buses.json"),
                )
                for ((srcName, dstRel) in listOf(
                    "power-raw-8000" to "power-peer/words",
                    "adc-ramp" to "soc-adc/samples",
                    "i2c-empty-lab-routes" to "soc-i2c/controllers",
                    "i2c-empty-buses" to "i2c-bus/buses",
                )) {
                    Files.copy(
                        project.resolve("configurations/jvm/experiments/$srcName.json"),
                        home.resolve("compoments/$dstRel.json"),
                    )
                }
                Formats.atomicYaml(
                    home.resolve("compoments/flash-image/flash-image.yaml"),
                    mapOf(
                        "sourcePort" to "firmware.update",
                        "tailProfile" to "dat-end-block-ff-experimental-v1",
                        "regionsProfile" to "strict",
                        "regionsFile" to "",
                    ),
                )

                val config = mapOf<String, Any>(
                    "schema" to 1,
                    "executionMode" to com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback.MODE,
                    "cpuEngine" to "dynarmic",
                    "dynarmicLibrary" to dynLib!!.toAbsolutePath().toString(),
                    "components" to "compoments",
                    "httpPort" to 0,
                    "firmwareMetadata" to project.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml").toString(),
                    "firmwareFile" to firmware,
                    "bootRomFile" to "boot-rom.bin",
                    "nativeLibrary" to (native ?: "unused.so"),
                    "instructionBudget" to 4000000,
                    "cbarInput" to 0xffff0000L,
                    "clusterProfile" to com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster.PROFILE,
                    "enabledDevices" to listOf(
                        "soc-system-control", "soc-flash", "soc-gpio",
                        "soc-timer", "soc-gic", "soc-dma", "soc-keyed-io",
                        "soc-configuration", "soc-method4", "soc-spi", "soc-adc", "soc-i2c",
                    ),
                    "discovery" to mapOf("bootRomColdStart" to "bootrom-qspi-kernel-v3"),
                )
                validateLaunchConfiguration(config)
                val t0 = System.nanoTime()
                EmulatorRuntime(home, config).use { runtime ->
                    val actual = runtime.probe()
                    val elapsedMs = (System.nanoTime() - t0) / 1e6
                    assertEquals("dynarmic-a32-ffm-jvm25", actual["backend"], chipName)
                    assertEquals("four-context boot-rom-experiment", actual["mode"], chipName)
                    assertEquals(true, actual["bootRomExecuted"], chipName)

                    val discovery = actual["discovery"] as Map<*, *>
                    val regions = (discovery["regions"] as List<*>).map { it as Map<*, *> }
                    val receipt = regions.single { it["id"] == "diagnostic.bootrom.gpio-global-fff41500" }["bootRomColdStart"] as Map<*, *>
                    assertEquals("bootrom-qspi-kernel-v3", receipt["profile"], chipName)
                    assertEquals(0xe3f4L, receipt["gpioGlobalFff41500"], chipName)
                    assertEquals(5L, receipt["sysCtrlFff48180"], chipName)
                    assertEquals(4, receipt["dramInitStage"], chipName)
                    assertEquals(0x900dL, receipt["bootStatusFff4a100"], chipName)
                    assertEquals(listOf(0x1000900dL, 0x2000900dL, 0x3000900dL), receipt["secondaryBootStatusFff4a104"], chipName)

                    val devices = actual["devices"] as Map<*, *>
                    val flashState = devices["soc-flash"] as Map<*, *>
                    val pioEnable = flashState["pioEnableStatus"] as Map<*, *>
                    if (expectWrsr) {
                        assertEquals(listOf(0x40L, 0L), pioEnable["statusRegisterBySelector"], chipName)
                        assertEquals(listOf(0xc7L, 0L), pioEnable["configRegisterBySelector"], chipName)
                    } else {
                        assertEquals(listOf(0L, 0L), pioEnable["statusRegisterBySelector"], chipName)
                    }

                    // Verify real Stage-2 XIP loaded boot.bin into RAM 0x0 and boot.bin QSPI-loaded kernel.bin into RAM 0x40000!
                    val bootRam = runtime.owner.submit { runtime.session.memory(0x0L, 0x1000) }.join()
                    assertContentEquals(expectedBootPrefix, bootRam, "$chipName (Dynarmic) RAM 0x0 must match GXUP0004.DAT boot.bin")
                    val kernelRam = runtime.owner.submit { runtime.session.memory(0x40000L, 0x1000) }.join()
                    assertContentEquals(expectedKernelPrefix, kernelRam, "$chipName (Dynarmic) RAM 0x40000 must match GXUP0004.DAT kernel.bin")

                    val attempts = (actual["attempts"] as Number).toLong()
                    assertTrue(attempts >= 500_000L, "$chipName (Dynarmic) expected substantial execution past kernel entry, got $attempts")
                    val stage = com.github.nyabanban.fuji.emulator.runtime.FirmwareStage.highestReached(runtime.session.telemetry.stageMask)?.label ?: "NONE"
                    println(
                        "[BootRomDynarmicTest] chip=$chipName backend=${actual["backend"]} attempts=$attempts " +
                            "elapsedMs=${"%.1f".format(elapsedMs)} status=${actual["status"]} stage=$stage " +
                            "pc=0x${((actual["registers"] as Map<*, *>)["pc"] as Number).toLong().toString(16)}",
                    )
                }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }
}
