package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryStrategy
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class M5LiveInCameraFormatE2eTest {

    @Test
    fun f7T1_fujifilmFfDsc01Fat32LayoutAndFfdbXferIntegrity(@TempDir tempRoot: Path) {
        val img = tempRoot.resolve("ff-dsc01-4g.img")
        val partLba = 8192L
        val partSectors = 8_380_416L
        val reservedSectors = 6146
        val fatSizeSectors = 1023L
        val numFats = 2
        val spc = 64
        val dataStartLba = partLba + reservedSectors + numFats * fatSizeSectors // 16384
        val xferPayload = ByteArray(1024) { (it * 31 + 7).toByte() }

        RandomAccessFile(img.toFile(), "rw").use { raf ->
            raf.setLength((partLba + partSectors) * 512L)

            // MBR (LBA 0)
            val mbr = ByteArray(512)
            mbr[446] = 0x80.toByte()
            mbr[446 + 4] = 0x0B.toByte()
            ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(446 + 8, partLba.toInt())
                putInt(446 + 12, partSectors.toInt())
                putShort(510, 0xAA55.toShort())
            }
            raf.seek(0L)
            raf.write(mbr)

            // VBR (LBA 8192) — Fujifilm FF-DSC01 geometry
            val vbr = ByteArray(512)
            "FF-DSC01".toByteArray(Charsets.US_ASCII).copyInto(vbr, 3)
            "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(vbr, 82)
            ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(11, 512)
                put(13, spc.toByte())
                putShort(14, reservedSectors.toShort())
                put(16, numFats.toByte())
                putShort(17, 0)
                put(21, 0xF8.toByte())
                putShort(22, 0)
                putInt(28, partLba.toInt())
                putInt(32, partSectors.toInt())
                putInt(36, fatSizeSectors.toInt())
                putInt(44, 2) // root cluster
                putShort(48, 1) // FSInfo sector
                putShort(50, 6) // backup VBR sector
                putShort(510, 0xAA55.toShort())
            }
            raf.seek(partLba * 512L)
            raf.write(vbr)
            raf.seek((partLba + 6L) * 512L)
            raf.write(vbr)

            // FSInfo (LBA 8193)
            val fsi = ByteArray(512)
            ByteBuffer.wrap(fsi).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x41615252)
                putInt(484, 0x61417272)
                putInt(488, -1)
                putInt(492, -1)
                putInt(508, 0xAA550000.toInt())
            }
            raf.seek((partLba + 1L) * 512L)
            raf.write(fsi)

            // FAT1 & FAT2 (cluster 2=root EOC, cluster 3=FFDB dir EOC, cluster 4=FFXFER.DAT EOC)
            val fatSector0 = ByteArray(512)
            ByteBuffer.wrap(fatSector0).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x0FFFFFF8)
                putInt(4, 0x0FFFFFFF)
                putInt(8, 0x0FFFFFFF)
                putInt(12, 0x0FFFFFFF)
                putInt(16, 0x0FFFFFFF)
            }
            raf.seek((partLba + reservedSectors) * 512L)
            raf.write(fatSector0)
            raf.seek((partLba + reservedSectors + fatSizeSectors) * 512L)
            raf.write(fatSector0)

            // Root dir (cluster 2 -> dataStartLba) with FFDB directory at cluster 3
            val rootDir = ByteArray(512)
            "FFDB       ".toByteArray(Charsets.US_ASCII).copyInto(rootDir, 0)
            rootDir[11] = 0x10.toByte()
            ByteBuffer.wrap(rootDir).order(ByteOrder.LITTLE_ENDIAN).putShort(26, 3)
            raf.seek(dataStartLba * 512L)
            raf.write(rootDir)

            // FFDB dir (cluster 3) with FFXFER.DAT at cluster 4
            val ffdbDir = ByteArray(512)
            "FFXFER  DAT".toByteArray(Charsets.US_ASCII).copyInto(ffdbDir, 0)
            ffdbDir[11] = 0x20.toByte()
            ByteBuffer.wrap(ffdbDir).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(26, 4)
                putInt(28, xferPayload.size)
            }
            raf.seek((dataStartLba + spc) * 512L)
            raf.write(ffdbDir)

            // FFXFER.DAT payload (cluster 4)
            raf.seek((dataStartLba + 2L * spc) * 512L)
            raf.write(xferPayload)
        }

        val fat = E2eHarness.inspectFatFilesystem(img)
        assertTrue(fat.fsType.startsWith("FAT32"))
        assertEquals(partLba, fat.partitionStartLba)
        assertEquals(512, fat.bytesPerSector)
        assertEquals(spc, fat.sectorsPerCluster)
        assertEquals(reservedSectors, fat.reservedSectors)
        assertEquals(numFats, fat.numFats)
        assertEquals(fatSizeSectors, fat.fatSizeSectors)
        assertEquals(0xF8, fat.fat0MediaByte)
        assertTrue(fat.fsInfoValid)
        assertTrue(fat.fatMirrorValid)
        assertTrue(fat.fat32EocMarkersValid)
        assertTrue("FFDB" in fat.rootEntries)
        assertContentEquals(xferPayload, E2eHarness.extractFat32File(img, "FFDB/FFXFER.DAT"))
    }

    @Test
    fun f7T1_m5HardwareProfilesStrictRefusalAndDiscoveryAdmission() {
        val catalog = AddressCatalog.bundled()
        val devices = catalog.data["devices"] as List<*>
        val uartDevice = devices.filterIsInstance<Map<*, *>>().single { it["componentId"] == "soc-uart" }
        val uartProfiles = uartDevice["profiles"] as List<*>
        assertTrue("pl011-tx-ready-experimental-v1" in uartProfiles)

        val base = listOf(MemoryRegion("base", 0x0100_0000L, 0x10_0000L, 7))
        val general36 = (0 until 36).map { idx ->
            MemoryRegion("setup-$idx", 0x2a00_0000L + idx * 0x10_0000L, 0x1_0000L, 3)
        }
        val admitted = AdditionalRamRegions.validate(base, general36)
        assertEquals(37, admitted.size)
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamRegions.validate(
                base,
                general36 + MemoryRegion("setup-37th", 0x2f00_0000L, 0x1_0000L, 3),
            )
        }

        // Strict mode must refuse discovery buses and additional RAM bindings
        val strictRuntime = CatalogRuntime(
            catalog,
            "strict",
            Path.of("."),
            mapOf(
                "discovery" to mapOf(
                    "inputs" to mapOf(
                        "gfx100s.fff7801c.poll.candidate" to mapOf("strategy" to DiscoveryStrategy.SETUP_REPEATABLE_7801C.id),
                    ),
                ),
            ),
        )
        assertEquals(
            null,
            strictRuntime.createBus(
                object : com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext {
                    override fun core() = 0
                    override fun tick() = 0L
                    override fun pc() = 0L
                    override fun lr() = 0L
                },
            ),
        )
        val memoryProfile = "flashload-request-buffer-experimental-v24"
        val baseRegions = catalog.memoryProfiles.getValue(memoryProfile)
        assertFailsWith<IllegalArgumentException> {
            strictRuntime.bindPorts(
                emptyList(),
                memoryProfile,
                baseRegions,
                additionalRamProfile = AdditionalRamRegions.PROFILE,
            )
        }
    }

    @Test
    @Tag("e2e-m5")
    fun f7T1_liveInCameraFat32FormatOnBlankUnlockedSdCard(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val snapRootSpec = System.getProperty("fuji.m5.formatSnapshotDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_M5_FORMAT_SNAPSHOT_DIR")?.takeIf { it.isNotBlank() }
        val snapRoot = snapRootSpec?.let { Path.of(it) }
        assumeTrue(
            fw != null && lib != null && snapRoot != null &&
                Files.isDirectory(snapRoot.resolve("home")) &&
                Files.isDirectory(snapRoot.resolve("snap")),
            "Set FUJI_FIRMWARE_FILE, FUJI_UNICORN_LIBRARY, and FUJI_M5_FORMAT_SNAPSHOT_DIR to run live M5 in-camera format E2E",
        )

        val workHome = tempRoot.resolve("home")
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        copyDirectorySparse(snapRoot!!.resolve("home"), workHome)

        val stagedDir = E2eHarness.projectRoot.resolve("build/emulator")
        if (Files.isDirectory(stagedDir.resolve("lib"))) {
            Files.createDirectories(workHome.resolve("lib"))
            Files.list(stagedDir.resolve("lib")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
                    Files.copy(jar, workHome.resolve("lib").resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        if (Files.isDirectory(stagedDir.resolve("compoments"))) {
            Files.list(stagedDir.resolve("compoments")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
                    Files.copy(jar, workHome.resolve("compoments").resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        val compiledCatalog = stagedDir.resolve("config/catalog.compiled.json")
        if (Files.isRegularFile(compiledCatalog)) {
            Files.copy(compiledCatalog, workHome.resolve("config/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        }

        val emuYaml = workHome.resolve("config/emulator.yaml")
        val cfg = Formats.read(emuYaml).toMutableMap()
        cfg["firmwareFile"] = fw!!.toAbsolutePath().toString()
        cfg["nativeLibrary"] = lib!!.toAbsolutePath().toString()
        Formats.atomicYaml(emuYaml, cfg)

        // Confirm starting Slot 1 SD image is completely blank (LBA 0 all-zero)
        val sdImg = workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        assertTrue(Files.isRegularFile(sdImg), "Missing Slot 1 SD image: $sdImg")
        val beforeLba0 = ByteArray(512)
        RandomAccessFile(sdImg.toFile(), "r").use { it.readFully(beforeLba0) }
        assertTrue(beforeLba0.all { it == 0.toByte() }, "Expected blank all-zero LBA 0 before live in-camera format")

        val sourceCommit = Formats.json.readTree(workHome.resolve("bringup-input.generated.json").toFile())
            .path("sourceCommit").asText()

        // Drive real firmware button inputs from UI_LIB_FORMAT (Slot 1 selected):
        // menu (open confirmation) -> up (select Execute) -> menu (execute live FAT32 format)
        val buttonEvents = "869500000:menu:down,872500000:menu:up," +
            "889500000:up:down,892500000:up:up," +
            "901000000:menu:down,904000000:menu:up"

        val res = E2eHarness.runDisplayBringupResume(
            home = workHome,
            sourceCommit = sourceCommit,
            snapshotDir = snapRoot.resolve("snap"),
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "soc-vglib-init,soc-spi,power-peer",
                "--button-events", buttonEvents,
                "--budget", "1149000000",
                "--export-frames-dir", framesDir.toString(),
                "--max-frame-exports", "32",
            ),
            timeoutSeconds = 600,
        )
        assertEquals(0, res.exitCode, "Live M5 in-camera format failed:\n${res.stdout.takeLast(1200)}\n${res.stderr.takeLast(1200)}")

        val resultJson = Formats.json.readTree(workHome.resolve("bringup.result.json").toFile())
        val sdHost = resultJson.path("devices").path("soc-sd-host")
        assertTrue(sdHost.path("commandCount").asLong() > 200L, "Expected >200 SD commands, got ${sdHost.path("commandCount")}")
        assertTrue(sdHost.path("mediaBytes").asLong() > 9_000_000L, "Expected >9MB media bytes, got ${sdHost.path("mediaBytes")}")
        assertEquals(0L, sdHost.path("failedTransactions").asLong(), "Expected 0 failed SD transactions")

        val fat = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(fat.fsType.startsWith("FAT32"), "Expected FAT32, got ${fat.fsType}")
        assertEquals(8192L, fat.partitionStartLba)
        assertEquals(512, fat.bytesPerSector)
        assertEquals(64, fat.sectorsPerCluster)
        assertEquals(2, fat.numFats)
        assertEquals(0xF8, fat.fat0MediaByte)
        assertTrue(fat.fsInfoValid, "FAT32 FSInfo lead/struc/trail invalid")
        assertTrue(fat.fatMirrorValid, "FAT1 does not mirror FAT0")
        assertTrue(fat.fat32EocMarkersValid, "FAT32 cluster 0/1/root EOC markers invalid")
    }

    private fun copyDirectorySparse(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else if (path.fileName.toString().endsWith(".img")) {
                    Files.createDirectories(target.parent)
                    val pb = ProcessBuilder("cp", "--sparse=always", path.toString(), target.toString())
                    val proc = pb.start()
                    assertEquals(0, proc.waitFor(), "cp --sparse=always failed for $path")
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
