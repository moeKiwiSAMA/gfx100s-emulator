package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.desktop.qualification.ArmProgram
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynarmicSnapshotRoundTripTest {
    private val fixtures = IntegrationTest()

    private fun repoRoot(): Path {
        var cur = Path.of("").toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts"))) return cur
            cur = cur.parent ?: return cur
        }
        return cur
    }

    private fun findDynarmicLib(): Path? =
        CpuEngineConfig.findDynarmicLibrary(repoRoot(), emptyMap())?.takeIf { Files.isRegularFile(it) }

    private fun branch(from: Int, to: Int, condition: Int = 14): Long =
        (condition.toLong() shl 28) or 0x0a000000L or (((to - from - 8) shr 2).toLong() and 0x00ffffffL)

    /**
     * 4-core ARMv7-A program exercising banked SVC/IRQ stacks, GIC SGI delivery,
     * loop counters, and guest RAM writes across cores 0..3.
     */
    private fun buildFourCoreGicProgram(): ByteArray {
        val program = ArmProgram()
        program.emit(branch(0, 0x100))
        program.pad(0x18)
        program.emit(branch(0x18, 0x300))
        program.pad(0x100)
        listOf(0xee104fb0L, 0xe2044003L, 0xe3540001L).forEach { program.emit(it) }
        program.emit(branch(0x10c, 0x200, 0))
        program.emit(0xe3540002L)
        program.emit(branch(0x114, 0x240, 0))
        program.emit(0xe3540003L)
        program.emit(branch(0x11c, 0x180, 0))
        program.emit(0xe320f003L)
        program.emit(branch(0x124, 0x120))
        program.pad(0x180)
        program.load(5, 2000)
        program.emit(0xe2555001L)
        program.emit(0x1afffffdL)
        program.emit(0xe320f003L)
        program.emit(0xeafffffdL)
        program.pad(0x200)
        program.write(0xffff1f00L, 0x00040005L)
        program.emit(0xe320f003L)
        program.emit(0xeafffffdL)
        program.pad(0x240)
        program.emit(0xf1020012L)
        program.load(13, 0xf00)
        program.emit(0xf1020013L)
        program.load(13, 0xe00)
        program.write(0xffff1000L, 1)
        program.write(0xffff2000L, 1)
        program.write(0xffff2004L, 255)
        program.emit(0xf1080080L)
        program.emit(0xe320f003L)
        program.emit(0xeafffffdL)
        program.pad(0x300)
        program.load(0, 0xffff200cL)
        program.emit(0xe5901000L)
        program.load(2, 700)
        program.emit(0xe2522001L)
        program.emit(0x1afffffdL)
        program.load(0, 0x900)
        program.emit(0xe5801000L)
        program.load(0, 0xffff2010L)
        program.emit(0xe5801000L)
        program.emit(0xe25ef004L)
        return program.finish()
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

    private fun prepareRelaxedDynarmicHome(
        home: Path,
        componentsSrc: Path,
        dynLib: Path,
        code: ByteArray,
        budget: Long,
        bootstrapRam: Boolean,
    ): Map<String, Any> {
        Files.createDirectories(home)
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        Files.list(componentsSrc).use { stream ->
            stream.filter { it.toString().endsWith(".jar") }
                .forEach { Files.copy(it, compDir.resolve(it.fileName)) }
        }
        val repo = repoRoot()
        Files.copy(repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml"), home.resolve("gfx100s-213.yaml"))
        Files.copy(repo.resolve("catalog/compiled/catalog.json"), home.resolve("catalog.compiled.json"))
        Files.write(home.resolve("custom.dat"), buildPatchedType6Dat(code))

        val sysMemCfg = linkedMapOf<String, Any>("profile" to "loader-low128-experimental-v1")
        if (bootstrapRam) {
            sysMemCfg["additionalRamProfile"] = "finite-rw-or-rwx-experimental-v2"
        }
        Formats.atomicYaml(compDir.resolve("system-memory/system-memory.yaml"), sysMemCfg)
        Formats.atomicYaml(
            compDir.resolve("soc-gic/soc-gic.yaml"),
            mapOf("coreProfile" to DynarmicCluster.PROFILE),
        )
        val cfg = linkedMapOf<String, Any>(
            "schema" to 1,
            "executionMode" to "relaxed",
            "components" to "compoments",
            "addressDictionary" to "catalog.compiled.json",
            "httpPort" to 0,
            "firmwareMetadata" to "gfx100s-213.yaml",
            "firmwareFile" to "custom.dat",
            "allowCustomFirmware" to true,
            "instructionBudget" to budget,
            "enabledDevices" to listOf("soc-gic"),
            "clusterProfile" to DynarmicCluster.PROFILE,
            "cbarInput" to 0xffff0000L,
            CpuEngineConfig.CPU_ENGINE_KEY to "dynarmic",
            CpuEngineConfig.DYNARMIC_LIBRARY_KEY to dynLib.toString(),
        )
        if (bootstrapRam) {
            cfg[RelaxedBootstrapRam.CONFIG_KEY] = RelaxedBootstrapRam.PROFILE
        }
        validateLaunchConfiguration(cfg)
        return cfg
    }

    @Test
    fun fourCoreDynarmicSnapshotSaveAndRestoreMatchesDirectRun() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping Dynarmic snapshot test: libfuji_dynarmic not built")
        val root = fixtures.fixture()
        try {
            val code = buildFourCoreGicProgram()
            val components = root.resolve("compoments")
            val directHome = root.resolve("dyn-direct")
            val directConfig = prepareRelaxedDynarmicHome(directHome, components, dynLib!!, code, 10000L, bootstrapRam = false)
            val snapshotDir = root.resolve("dyn-snap-v1")

            lateinit var directRegisters: Map<String, Long>
            lateinit var directFloating: Map<String, Long>
            lateinit var directRam: ByteArray
            var directAttempts = 0L

            EmulatorRuntime(directHome, directConfig).use { runtime ->
                runtime.owner.submit { runtime.session.initialize() }.join()
                repeat(2) {
                    assertTrue(runtime.owner.submit { runtime.session.step() }.join())
                }
                val saveReceipt = runtime.owner.submit { runtime.session.saveEmulatorSnapshot(snapshotDir) }.join()
                assertEquals("complete", saveReceipt["status"])
                while (runtime.owner.submit { runtime.session.step() }.join()) {
                    // run to budget/completion
                }
                directRegisters = checkNotNull(runtime.owner.submit { runtime.session.registerSnapshot() }.join())
                directFloating = checkNotNull(runtime.owner.submit { runtime.session.floatingPointSnapshot() }.join())
                directRam = runtime.owner.submit { runtime.session.memory(0, 0x20000) }.join()
                directAttempts = runtime.owner.submit { runtime.session.checkpointAttempts() }.join()
            }

            val restoredHome = root.resolve("dyn-restored")
            val restoredConfig = prepareRelaxedDynarmicHome(restoredHome, components, dynLib, code, 10000L, bootstrapRam = false)
            EmulatorRuntime(restoredHome, restoredConfig).use { runtime ->
                val restoreReceipt = runtime.owner.submit {
                    runtime.session.initialize()
                    runtime.session.restoreEmulatorSnapshot(snapshotDir)
                }.join()
                assertEquals(true, restoreReceipt["restored"])
                while (runtime.owner.submit { runtime.session.step() }.join()) {
                    // run from snapshot to completion
                }
                val restoredRegisters = checkNotNull(runtime.owner.submit { runtime.session.registerSnapshot() }.join())
                val restoredFloating = checkNotNull(runtime.owner.submit { runtime.session.floatingPointSnapshot() }.join())
                val restoredRam = runtime.owner.submit { runtime.session.memory(0, 0x20000) }.join()
                val restoredAttempts = runtime.owner.submit { runtime.session.checkpointAttempts() }.join()

                assertEquals(directAttempts, restoredAttempts)
                assertEquals(directRegisters, restoredRegisters)
                assertEquals(directFloating, restoredFloating)
                assertContentEquals(directRam, restoredRam)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dynarmicRelaxedBootstrapRamSparseGzSnapshotRoundTripsCompactly() {
        val dynLib = findDynarmicLib()
        assumeTrue(dynLib != null, "Skipping Dynarmic relaxed sparse snapshot test: libfuji_dynarmic not built")
        val root = fixtures.fixture()
        try {
            val code = buildFourCoreGicProgram()
            val components = root.resolve("compoments")
            val saveHome = root.resolve("dyn-relaxed-save")
            val saveConfig = prepareRelaxedDynarmicHome(saveHome, components, dynLib!!, code, 10000L, bootstrapRam = true)
            val snapshotDir = root.resolve("dyn-relaxed-snap")

            EmulatorRuntime(saveHome, saveConfig).use { runtime ->
                runtime.owner.submit {
                    runtime.session.initialize()
                    assertTrue(runtime.session.step())
                    val receipt = runtime.session.saveEmulatorSnapshot(snapshotDir)
                    assertEquals("complete", receipt["status"])
                }.join()
            }

            // Huge LPDDR4 regions (>64 MiB) must be stored via sparse-gz-v1, keeping disk usage tiny
            val manifest = EmulatorSnapshot.readJsonObject(snapshotDir.resolve(EmulatorSnapshot.MANIFEST_FILE))
            @Suppress("UNCHECKED_CAST")
            val regions = (manifest["memory"] as Map<*, *>)["regions"] as List<Map<*, *>>
            val sparseRegions = regions.filter { it["encoding"] == "sparse-gz-v1" }
            assertTrue(sparseRegions.isNotEmpty(), "Expected huge relaxed LPDDR4 region to use sparse-gz-v1 encoding")
            val totalSparseBytes = sparseRegions.sumOf { (it["fileSize"] as Number).toLong() }
            assertTrue(totalSparseBytes < 4L * 1024L * 1024L, "Expected compact sparse-gz snapshot, got ${totalSparseBytes}B")

            // Restore into a fresh relaxed session where RelaxedBootstrapRam is pre-selected during initialize()
            val restoreHome = root.resolve("dyn-relaxed-restore")
            val restoreConfig = prepareRelaxedDynarmicHome(restoreHome, components, dynLib, code, 10000L, bootstrapRam = true)
            EmulatorRuntime(restoreHome, restoreConfig).use { runtime ->
                val receipt = runtime.owner.submit {
                    runtime.session.initialize()
                    val r = runtime.session.restoreEmulatorSnapshot(snapshotDir)
                    assertTrue(runtime.session.step())
                    r
                }.join()
                assertEquals(true, receipt["restored"])
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun inspectMachineOutputProducesPureJsonWithoutLogbackStatusDump(@TempDir dir: Path) {
        val root = fixtures.fixture()
        try {
            val home = dir.resolve("inspect-home")
            val dynLib = findDynarmicLib() ?: dir.resolve("dummy-lib.so").also { Files.write(it, ByteArray(0)) }
            val rawCfg = com.github.nyabanban.fuji.emulator.desktop.qualification.SyntheticGuest.prepare(
                home,
                root.resolve("compoments"),
                dynLib,
                buildFourCoreGicProgram(),
                "loader-low128-experimental-v1",
                1000,
            ) + mapOf("schema" to 1, "httpPort" to 0)
            val cfgPath = home.resolve("config/emulator.json")
            Files.createDirectories(cfgPath.parent)
            Formats.writeJson(cfgPath, rawCfg)

            val prevOut = System.out
            val captured = ByteArrayOutputStream()
            try {
                System.setOut(PrintStream(captured, true, StandardCharsets.UTF_8))
                main(arrayOf("--home", home.toString(), "--config", cfgPath.toString(), "--inspect"))
            } finally {
                System.setOut(prevOut)
            }
            val stdout = captured.toString(StandardCharsets.UTF_8)
            assertTrue(stdout.trim().startsWith("{"), "Expected JSON stdout, got: $stdout")
            assertTrue(!stdout.contains("|-INFO") && !stdout.contains("|-WARN"), "Logback status dump leaked into stdout: $stdout")
            val parsed = Formats.json.readTree(stdout)
            assertTrue(parsed.isObject, "Expected parsed JSON object from --inspect")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
