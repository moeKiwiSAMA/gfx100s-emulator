package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.NorFlashTool
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/**
 * Live Dynarmic JIT E2E verification for Wave 10 Track 10A (`REAL-FLASH-BOOT-W10`):
 * 1. Verifies the staged release bundle has ZERO `GXUP0004.DAT` and ships the outermost
 *    `flash/nor-flash.bin` + `flash/PUT_HERE`, `runtime/templates/factory-flash/nor-flash-2.13.bin`,
 *    and 32 KiB mask `bootrom/boot-rom.bin` + `bootrom/PUT_HERE`.
 * 2. Cold-boots via the explicit BootROM opt-in (`bootRomBoot: true`, `PC=0xFFF00000`) and via the default
 *    Flash-Direct path (`bootMode == "flash-direct"`) through OOBE -> Live `1024x768` STILL HUD `0x0100`
 *    with real NOR erases & programs.
 * 3. Cold-reboots on the same `workRoot` and proves OOBE settings persisted inside `nor-flash.bin`
 *    (skips OOBE directly to STILL HUD `0x0100`).
 * 4. Verifies live Host Flasher operations (`exportNorFlashLive`, `flashDatIntoNorLive` preserving
 *    calibration/OOBE `[0x02FE0000..0x04000000)`, and `restoreFactoryFlashLive` resetting back to OOBE `0x0421`).
 */
class UnifiedNorFlashBootLiveTest {
    private fun copyDir(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 50_000) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var calls = 0
        while (rt.telemetry.virtualTick < target && calls < maxCalls) {
            val ok = rt.owner.submit {
                var cont = true
                var i = 0
                while (cont && i < 64 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    cont = rt.session.stepFast()
                    i++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                cont
            }.join()
            if (!ok || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tap(rt: EmulatorRuntime, key: String, hold: Long, settle: Long) {
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
        stepBy(rt, hold)
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
        stepBy(rt, settle)
    }

    private fun waitForScreen(rt: EmulatorRuntime, targets: Set<Int>, maxIters: Int = 45): Int {
        var scr = 0
        for (i in 0 until maxIters) {
            stepBy(rt, 20_000_000L, maxCalls = 10_000)
            scr = readU32(rt, 0x01CC6424L)
            if (scr in targets) return scr
        }
        return scr
    }

    private fun readCalibrationSlice(norPath: Path): ByteArray {
        val len = (NorFlashTool.NOR_SIZE_BYTES - NorFlashTool.FIRMWARE_REGION_LIMIT).toInt()
        val buf = ByteArray(len)
        RandomAccessFile(norPath.toFile(), "r").use { raf ->
            raf.seek(NorFlashTool.FIRMWARE_REGION_LIMIT)
            raf.readFully(buf)
        }
        return buf
    }

    @Test
    fun stagedReleaseBootsDirectlyFrom64MiBNorFlashPersistsOobeAndSupportsLiveHostFlasher(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")),
            "Requires :stageReleaseLinux output with runtime/templates/factory-flash/nor-flash-2.13.bin",
        )

        // 1. Verify ZERO GXUP0004.DAT, ZERO duplicate flash/nor-flash.bin in archive, and exact staged factory NOR + BootROM + PUT_HERE
        assertFalse(
            Files.exists(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT")),
            "Staged release must NOT ship redundant runtime/firmware/GXUP0004.DAT",
        )
        assertFalse(
            Files.exists(stagedRoot.resolve("flash/nor-flash.bin")),
            "Staged release must NOT duplicate 64 MiB flash/nor-flash.bin (first launch seeds it automatically from factory template)",
        )
        assertTrue(Files.isRegularFile(stagedRoot.resolve("flash/PUT_HERE")), "Missing bilingual flash/PUT_HERE marker")
        assertTrue(Files.isRegularFile(stagedRoot.resolve("bootrom/PUT_HERE")), "Missing bilingual bootrom/PUT_HERE marker")
        assertEquals(NorFlashTool.NOR_SIZE_BYTES, Files.size(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")))
        val bootRomPath = stagedRoot.resolve("bootrom/boot-rom.bin")
        assertTrue(Files.isRegularFile(bootRomPath))
        assertEquals(32768L, Files.size(bootRomPath))
        assertEquals(
            "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4",
            NorFlashTool.sha256File(bootRomPath),
            "Staged boot-rom.bin SHA-256 must match pure SPX4 mask BootROM",
        )

        val workRoot = tempRoot.resolve("release-nor-boot")
        copyDir(stagedRoot, workRoot)
        val liveNor = workRoot.resolve("flash/nor-flash.bin")
        val factoryNor = workRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")
        assertFalse(Files.exists(liveNor), "flash/nor-flash.bin must not pre-exist before first launch")
        val initialSha = NorFlashTool.sha256File(factoryNor)
        // Explicit BootROM opt-in (`bootRomBoot: true` / `--boot-rom`); default launches use verified Flash-Direct.
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + (BOOT_ROM_BOOT_KEY to true)

        // 2. Boot #1: First launch automatically seeds flash/nor-flash.bin from factory template, then cold-boots via BootROM -> OOBE 0x0421 -> STILL HUD 0x0100
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            assertTrue(Files.isRegularFile(liveNor), "First launch must auto-seed flash/nor-flash.bin from factory template")
            assertEquals(NorFlashTool.NOR_SIZE_BYTES, Files.size(liveNor))
            assertEquals(initialSha, NorFlashTool.sha256File(liveNor), "Auto-seeded flash/nor-flash.bin must match factory template")
            rt.owner.submit { rt.session.initialize() }.join()
            val stInit = rt.owner.submit { rt.session.state() }.join()
            assertEquals("four-context boot-rom-experiment", stInit["mode"], "With bootrom/boot-rom.bin present, must cold-boot via full BootROM (PC=0xFFF00000)")
            assertEquals(0xFFF00000L, ((stInit["registers"] as Map<*, *>)["pc"] as Number).toLong(), "Initial PC must be SPX4 mask BootROM reset vector 0xFFF00000")
            assertEquals("bootrom-nor", rt.norFlashStatus()["bootPath"])
            assertEquals(true, rt.norFlashStatus()["bootRomPresent"])

            val scr1 = waitForScreen(rt, setOf(0x0421, 0x0100))
            assertEquals(0x0421, scr1, "Fresh factory NOR Flash must boot to OOBE 0x0421")
            val stBooted = rt.owner.submit { rt.session.state() }.join()
            assertEquals(true, stBooted["bootRomExecuted"], "Expected bootRomExecuted=true after booting from bootrom/boot-rom.bin")

            // Complete OOBE -> STILL HUD 0x0100 (matching P0Fix2ColdResetFwupSdReinsertRegressionTest)
            tap(rt, "menu", 5_000_000L, 45_000_000L)
            var scr = readU32(rt, 0x01CC6424L)
            if (scr == 0x0423) {
                tap(rt, "down", 5_000_000L, 25_000_000L)
                tap(rt, "down", 5_000_000L, 25_000_000L)
                tap(rt, "menu", 5_000_000L, 45_000_000L)
                scr = readU32(rt, 0x01CC6424L)
            }
            if (scr == 0x0422) {
                tap(rt, "up", 4_000_000L, 12_000_000L)
                tap(rt, "menu", 5_000_000L, 45_000_000L)
                scr = readU32(rt, 0x01CC6424L)
            }
            if (scr == 0x0482) {
                tap(rt, "menu", 5_000_000L, 40_000_000L)
            }
            val stillScr = waitForScreen(rt, setOf(0x0100), maxIters = 30)
            assertEquals(0x0100, stillScr, "Expected STILL LiveView HUD 0x0100 after OOBE")
            // Clean power-off via physical power switch so firmware flushes part-18 shutdown record + BKUP + Sub-MCU NVRAM
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("power" to false), persistToDisk = false) }.join()
            for (chunk in 0 until 12) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean Sub-MCU power-off after OOBE")

            val flashState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "flash-image" }.component.state()
            }.join()
            assertEquals("raw-nor-image-v1", flashState["mode"])
            assertTrue((flashState["eraseCount"] as Number).toLong() > 0L, "Expected real NOR erases during OOBE: $flashState")
            assertTrue((flashState["programCount"] as Number).toLong() > 0L, "Expected real NOR programs during OOBE: $flashState")

            val socFlashState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-flash" }.component.state()
            }.join()
            assertEquals("gfx100s-unified-boot-program-v6", socFlashState["profile"])
            assertEquals(2145056L, (socFlashState["jedec"] as Number).toLong())
        }

        // Verify nor-flash.bin mutated on disk after OOBE + clean power-off
        val postOobeSha = NorFlashTool.sha256File(liveNor)
        assertNotEquals(initialSha, postOobeSha, "OOBE completion must persist writes directly into flash/nor-flash.bin")
        run {
            val baseBytes = Files.readAllBytes(factoryNor)
            val nowBytes = Files.readAllBytes(liveNor)
            val changedSectors = mutableListOf<String>()
            var off = 0
            while (off < baseBytes.size) {
                var diff = 0
                for (j in 0 until 4096) {
                    if (baseBytes[off + j] != nowBytes[off + j]) diff++
                }
                if (diff > 0) changedSectors.add("0x${off.toString(16)}($diff)")
                off += 4096
            }
            println("[NOR-DIFF Boot#1] changed4kSectors=${changedSectors.size}: ${changedSectors.joinToString(", ")}")
        }
        val calAfterOobe = readCalibrationSlice(liveNor)

        // 3. Boot #2: Cold restart on same workRoot -> skips OOBE directly to STILL HUD 0x0100,
        //    then exercises live Host Flasher (export, flashDatIntoNorLive preserving calibration, restoreFactoryFlashLive)
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt2 ->
            rt2.owner.submit { rt2.session.initialize() }.join()
            var scr2 = 0
            val seen = mutableListOf<String>()
            for (i in 0 until 45) {
                stepBy(rt2, 20_000_000L, maxCalls = 10_000)
                scr2 = readU32(rt2, 0x01CC6424L)
                seen.add("i=$i:0x${scr2.toString(16)}")
                if (scr2 == 0x0100) break
            }
            val st2 = rt2.owner.submit { rt2.session.state() }.join()
            assertEquals(0x0100, scr2, "Second boot must skip OOBE straight to STILL HUD 0x0100 from persisted nor-flash.bin (status=${st2["status"]} rf=${st2["relaxedFallback"]} seen=$seen)")

            // Export live NOR Flash
            val exported = tempRoot.resolve("exported-nor.bin")
            val expRes = rt2.exportNorFlashLive(exported)
            assertEquals(NorFlashTool.NOR_SIZE_BYTES, expRes.sizeBytes)
            assertEquals(NorFlashTool.NOR_SIZE_BYTES, Files.size(exported))

            // Flash GXUP0004.DAT into live NOR preserving calibration/OOBE settings & cold-reboot
            val repoDat = repo.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT")
            if (Files.isRegularFile(repoDat)) {
                val flashRes = rt2.flashDatIntoNorLive(repoDat, preserveCalibration = true)
                assertTrue(flashRes.preservedCalibration)
                assertContentEquals(calAfterOobe, readCalibrationSlice(liveNor), "flashDatIntoNorLive must preserve [0x02FE0000..0x04000000)")
                val scrAfterFlash = waitForScreen(rt2, setOf(0x0100), maxIters = 45)
                assertEquals(0x0100, scrAfterFlash, "Camera must cold-reboot straight to STILL HUD 0x0100 after flashing .DAT with preserved calibration")
            }

            // Restore Stock 2.13 Factory Flash & cold-reboot -> returns to fresh OOBE 0x0421
            val restoreRes = rt2.restoreFactoryFlashLive()
            assertEquals(NorFlashTool.sha256File(factoryNor), restoreRes.sha256)
            val scrAfterRestore = waitForScreen(rt2, setOf(0x0421), maxIters = 45)
            assertEquals(0x0421, scrAfterRestore, "Camera must cold-reboot cleanly back to fresh OOBE 0x0421 after restoreFactoryFlashLive")
        }
    }

    @Test
    fun inCameraStockInitializeResetProducesCleanPrivacyFreeFactoryNorFlash(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")),
            "Requires :stageReleaseLinux output with runtime/templates/factory-flash/nor-flash-2.13.bin",
        )

        val workRoot = tempRoot.resolve("release-nor-initialize")
        copyDir(stagedRoot, workRoot)
        val liveNor = workRoot.resolve("flash/nor-flash.bin")
        val factoryNor = workRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")
        val factoryBytes = Files.readAllBytes(factoryNor)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        // Boot #1: Complete OOBE -> mutate a user setting (I.Q. -> IMAGE SIZE) -> clean power-off
        //          so BlockA/BlockB/part-12/part-18/part-26/part-27 are populated with non-factory user state.
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            val scr1 = waitForScreen(rt, setOf(0x0421, 0x0100))
            assertEquals(0x0421, scr1)

            // Complete OOBE -> STILL HUD 0x0100
            tap(rt, "menu", 5_000_000L, 45_000_000L)
            var scr = readU32(rt, 0x01CC6424L)
            if (scr == 0x0423) {
                tap(rt, "down", 5_000_000L, 25_000_000L)
                tap(rt, "down", 5_000_000L, 25_000_000L)
                tap(rt, "menu", 5_000_000L, 45_000_000L)
                scr = readU32(rt, 0x01CC6424L)
            }
            if (scr == 0x0422) {
                tap(rt, "up", 4_000_000L, 12_000_000L)
                tap(rt, "menu", 5_000_000L, 45_000_000L)
                scr = readU32(rt, 0x01CC6424L)
            }
            if (scr == 0x0482) {
                tap(rt, "menu", 5_000_000L, 40_000_000L)
            }
            assertEquals(0x0100, waitForScreen(rt, setOf(0x0100), maxIters = 30))

            // Change IMAGE SIZE (I.Q. row 0 -> down -> OK) so BlockA holds dirty user settings
            tap(rt, "menu", 5_000_000L, 25_000_000L)
            tap(rt, "menu", 5_000_000L, 20_000_000L)
            tap(rt, "down", 5_000_000L, 16_000_000L)
            tap(rt, "menu", 5_000_000L, 25_000_000L)
            tap(rt, "back", 5_000_000L, 25_000_000L)

            // Clean power-off so firmware commits all user settings & part-18/26/27 to nor-flash.bin
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("power" to false), persistToDisk = false) }.join()
            for (chunk in 0 until 12) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean power-off after mutating user setting")
        }

        val preInitBytes = Files.readAllBytes(liveNor)

        // Boot #2: Cold restart (skips OOBE -> STILL HUD 0x0100) -> SET UP -> USER SETTING -> RESET -> INITIALIZE -> OK!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            assertEquals(0x0100, waitForScreen(rt, setOf(0x0100), maxIters = 45), "Boot #2 must skip OOBE straight to 0x0100")

            val erasesBefore = rt.owner.submit {
                (rt.host.components().single { it.descriptor.id == "flash-image" }.component.state()["eraseCount"] as Number).toLong()
            }.join()
            val progsBefore = rt.owner.submit {
                (rt.host.components().single { it.descriptor.id == "flash-image" }.component.state()["programCount"] as Number).toLong()
            }.join()

            // Navigate: MENU (opens I.Q. item list) -> LEFT (tab sidebar) -> 4x DOWN (SET UP / WRENCH)
            // -> RIGHT (USER SETTING, row 0) -> MENU (enter USER SETTING, scr=0x45e)
            // -> 9x DOWN (row 9 = RESET) -> MENU (enter RESET) -> 1x UP (INITIALIZE) -> MENU (0x436) -> UP (OK) -> MENU!
            tap(rt, "menu", 5_000_000L, 25_000_000L)
            tap(rt, "left", 5_000_000L, 20_000_000L)
            repeat(4) { tap(rt, "down", 5_000_000L, 18_000_000L) }
            tap(rt, "right", 5_000_000L, 20_000_000L)
            tap(rt, "menu", 5_000_000L, 25_000_000L) // Enter USER SETTING (scr=0x45e)
            repeat(9) { tap(rt, "down", 5_000_000L, 16_000_000L) } // Row 9 = RESET
            tap(rt, "menu", 5_000_000L, 25_000_000L) // Enter RESET
            tap(rt, "up", 5_000_000L, 18_000_000L) // Select INITIALIZE (last item in RESET)
            tap(rt, "menu", 5_000_000L, 25_000_000L) // Open INITIALIZE OK? confirm dialog (scr=0x436)
            assertEquals(0x0436, readU32(rt, 0x01CC6424L), "Expected INITIALIZE confirmation dialog 0x0436")
            tap(rt, "up", 5_000_000L, 18_000_000L) // Move cursor CANCEL -> OK
            tap(rt, "menu", 5_000_000L, 80_000_000L) // Execute stock INITIALIZE!

            val scrAfterInit = waitForScreen(rt, setOf(0x0421), maxIters = 15)
            assertEquals(0x0421, scrAfterInit, "Stock INITIALIZE must reset camera straight back to fresh OOBE 0x0421")

            // Clean power-off from fresh OOBE so any remaining NOR writes settle before Boot #3
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("power" to false), persistToDisk = false) }.join()
            for (chunk in 0 until 12) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean power-off after stock INITIALIZE")

            val st = rt.owner.submit { rt.session.state() }.join()
            val rf = st["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = (rf?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
            val flashRefusals = (rf?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            val flashState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "flash-image" }.component.state()
            }.join()
            val erasesAfter = (flashState["eraseCount"] as Number).toLong()
            val progsAfter = (flashState["programCount"] as Number).toLong()
            println(
                "[INIT-SUMMARY] erases=$erasesBefore->$erasesAfter progs=$progsBefore->$progsAfter " +
                    "mmio=$mmioFallbacks flashRefusals=$flashRefusals sha=${flashState["sha256"]}",
            )
            assertEquals(0L, mmioFallbacks, "Expected 0 [RELAXED/MMIO] fallbacks during INITIALIZE")
            assertEquals(0L, flashRefusals, "Expected 0 [RELAXED/FLASH/REFUSED] during INITIALIZE")
            assertTrue(erasesAfter > erasesBefore, "Expected real NOR erases during INITIALIZE ($erasesBefore -> $erasesAfter)")
            assertTrue(progsAfter > progsBefore, "Expected real NOR programs during INITIALIZE ($progsBefore -> $progsAfter)")

            val postInitBytes = Files.readAllBytes(liveNor)
            val initDiffFromPre = mutableListOf<String>()
            val initDiffFromFactory = mutableListOf<String>()
            var bank14Touched = false
            var firmwareTouched = false
            var off = 0
            while (off < postInitBytes.size) {
                var dPre = 0
                var dFac = 0
                for (j in 0 until 4096) {
                    if (preInitBytes[off + j] != postInitBytes[off + j]) dPre++
                    if (factoryBytes[off + j] != postInitBytes[off + j]) dFac++
                }
                if (dPre > 0) {
                    initDiffFromPre.add("0x${off.toString(16)}($dPre)")
                    if (off < 0x02FE0000) firmwareTouched = true
                    if (off in 0x03500000 until 0x03D40000) bank14Touched = true
                }
                if (dFac > 0) initDiffFromFactory.add("0x${off.toString(16)}($dFac)")
                off += 4096
            }
            println("[INIT-DIFF vs pre-init] sectors=${initDiffFromPre.size}: ${initDiffFromPre.joinToString(", ")}")
            println("[INIT-DIFF vs factory-template] sectors=${initDiffFromFactory.size}: ${initDiffFromFactory.joinToString(", ")}")
            assertFalse(firmwareTouched, "Stock INITIALIZE must NOT modify firmware region [0x00000000..0x02FE0000)")
            assertFalse(bank14Touched, "Stock INITIALIZE must NOT modify synthetic privacy-free bank-14 [0x03500000..0x03D40000)")
            assertTrue(initDiffFromPre.isNotEmpty(), "Expected stock INITIALIZE to erase/rewrite BKUP sectors in nor-flash.bin")
        }

        // Boot #3: Fresh cold reboot from the exact stock-INITIALIZED nor-flash.bin -> MUST boot cleanly back to fresh OOBE 0x0421!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt3 ->
            rt3.owner.submit { rt3.session.initialize() }.join()
            val scr3 = waitForScreen(rt3, setOf(0x0421, 0x0100), maxIters = 45)
            val st3 = rt3.owner.submit { rt3.session.state() }.join()
            val rf3 = st3["relaxedFallback"] as? Map<*, *>
            println("[INIT-BOOT3] scr=0x${scr3.toString(16)} status=${st3["status"]} rf=$rf3")
            assertEquals(0x0421, scr3, "Cold reboot after stock in-camera INITIALIZE must boot cleanly back to fresh OOBE 0x0421")
            assertEquals(0L, (rf3?.get("flashRefusals") as? Number)?.toLong() ?: 0L)
        }
    }

    @Test
    fun bootRomIsOptInFlashDirectIsDefaultAndMissingNorFailsFriendly(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")),
            "Requires :stageReleaseLinux output with runtime/templates/factory-flash/nor-flash-2.13.bin",
        )

        // Case 0: default release config with bootrom/boot-rom.bin present -> verified Flash-Direct (BootROM is opt-in only)
        val defaultRoot = tempRoot.resolve("release-default")
        copyDir(stagedRoot, defaultRoot)
        val defaultConfig = Formats.read(defaultRoot.resolve("configs/emulator.yaml"))
        assertFalse(BOOT_ROM_BOOT_KEY in defaultConfig, "Release config must not opt into BootROM by default")
        assertEquals(true, applyBootRomCliOverride(defaultConfig, arrayOf("--boot-rom"))[BOOT_ROM_BOOT_KEY])
        EmulatorRuntime(defaultRoot, defaultConfig, telemetryDisabled = true).use { rt ->
            assertEquals("flash-direct", rt.norFlashStatus()["bootPath"])
            assertEquals(true, rt.norFlashStatus()["bootRomPresent"])
            rt.owner.submit { rt.session.initialize() }.join()
            val stInit = rt.owner.submit { rt.session.state() }.join()
            assertEquals("flash-direct", stInit["bootMode"], "Default launch must boot via Flash-Direct even when boot-rom.bin is present")
        }

        // Case 1: BootROM opted in but bootrom/boot-rom.bin deleted (clean public release after --flash-dat) -> explicit fallback to Flash-Direct!
        val noBootRomRoot = tempRoot.resolve("release-no-bootrom")
        copyDir(stagedRoot, noBootRomRoot)
        Files.deleteIfExists(noBootRomRoot.resolve("bootrom/boot-rom.bin"))
        val config = Formats.read(noBootRomRoot.resolve("configs/emulator.yaml")) + (BOOT_ROM_BOOT_KEY to true)
        EmulatorRuntime(noBootRomRoot, config, telemetryDisabled = true).use { rt ->
            assertEquals("flash-direct", rt.norFlashStatus()["bootPath"])
            assertEquals(false, rt.norFlashStatus()["bootRomPresent"])
            rt.owner.submit { rt.session.initialize() }.join()
            val stInit = rt.owner.submit { rt.session.state() }.join()
            assertEquals("flash-direct", stInit["bootMode"], "Missing bootrom/boot-rom.bin must seamlessly fall back to flash-direct")
            val scr = waitForScreen(rt, setOf(0x0421, 0x0100), maxIters = 45)
            assertEquals(0x0421, scr, "Flash-Direct fallback must boot cleanly to OOBE 0x0421")
        }

        // Case 2: BOTH flash/nor-flash.bin AND factory template missing -> friendly error pointing to flash/PUT_HERE & --flash-dat
        val noNorRoot = tempRoot.resolve("release-no-nor")
        copyDir(stagedRoot, noNorRoot)
        Files.deleteIfExists(noNorRoot.resolve("flash/nor-flash.bin"))
        Files.deleteIfExists(noNorRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))
        val err = assertFailsWith<IllegalStateException> {
            EmulatorRuntime(noNorRoot, config, telemetryDisabled = true)
        }
        val msg = err.message.orEmpty()
        assertTrue("flash/PUT_HERE" in msg && "--flash-dat" in msg, "Expected friendly guidance in error: $msg")
    }

    @Test
    fun interactiveWindowClosePersistsAreaTimezoneAcrossRestartsAndCliFactoryRestoreClearsSubMcu(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")),
            "Requires :stageReleaseLinux output with runtime/templates/factory-flash/nor-flash-2.13.bin",
        )

        val workRoot = tempRoot.resolve("release-tz-persist")
        copyDir(stagedRoot, workRoot)
        val liveNor = workRoot.resolve("flash/nor-flash.bin")
        val subMcuNvram = workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json")
        val rtcState = workRoot.resolve("runtime/compoments/rtc/rtc-state.json")
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        fun readHomeTzRam(rt: EmulatorRuntime): Triple<Int, Int, Int> =
            rt.owner.submit {
                val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                val buf = ByteBuffer.allocate(8)
                // $BACKUP BlockA RAM @ 0x01C7339C: +0x0E4C=bHomeTimeSign, +0x0E4E=bHomeTimeHours, +0x0E50=bHomeTimeMinutes
                mem.read(0x01C7339CL + 0x0E4CL, buf)
                Triple(buf.get(0).toInt() and 0xFF, buf.get(2).toInt() and 0xFF, buf.get(4).toInt() and 0xFF)
            }.join()

        fun readNorBlockASlot(slot: Int): ByteArray {
            val buf = ByteArray(0x2000)
            RandomAccessFile(liveNor.toFile(), "r").use { raf ->
                raf.seek(0x03DC0000L + slot.toLong() * 0x2000L)
                raf.readFully(buf)
            }
            return buf
        }

        fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.sleep(5)
            }
            return predicate()
        }

        var committedHomeTz = Triple(0, 0, 0)

        // Boot #1: Run via runInteractive -> select non-zero Area/Timezone on OOBE 0x0423 -> reach 0x0100 ->
        //          close emulator (stopRequested=true) WHILE power switch is still ON (power == true)!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            val paused = AtomicBoolean(false)
            val stopRequested = AtomicBoolean(false)
            val interactiveFuture = CompletableFuture.supplyAsync {
                rt.runInteractive(paused = paused, stopRequested = stopRequested)
            }
            fun tapInteractive(key: String, holdTicks: Long, settleTicks: Long) {
                val t0 = rt.telemetry.virtualTick
                rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
                assertTrue(
                    waitUntil(15_000L) { !interactiveFuture.isDone && rt.telemetry.virtualTick >= t0 + holdTicks },
                    "Timed out holding button '$key'",
                )
                val t1 = rt.telemetry.virtualTick
                rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
                assertTrue(
                    waitUntil(20_000L) { !interactiveFuture.isDone && rt.telemetry.virtualTick >= t1 + settleTicks },
                    "Timed out settling after button '$key'",
                )
            }

            try {
                assertTrue(
                    waitUntil(60_000L) {
                        !interactiveFuture.isDone &&
                            (rt.telemetry.stageMask and FirmwareStage.STAGE_6_INTERACTIVE_UI.bit) != 0 &&
                            runCatching { readU32(rt, 0x01CC6424L) == 0x0421 }.getOrDefault(false)
                    },
                    "Expected Boot #1 runInteractive to reach OOBE Language 0x0421",
                )

                // Confirm English -> OOBE Area Setting (0x0423)
                tapInteractive("menu", 5_000_000L, 45_000_000L)
                assertTrue(
                    waitUntil(15_000L) { !interactiveFuture.isDone && runCatching { readU32(rt, 0x01CC6424L) == 0x0423 }.getOrDefault(false) },
                    "Expected OOBE Area Setting 0x0423",
                )
                assertEquals(Triple(0, 0, 0), readHomeTzRam(rt), "Factory default Area before movement must be UTC+0:00 (London)")

                // Move east across 8 timezone bands on the 0x0423 world map, move focus down x2 to [SET], and confirm -> 0x0422 Date/Time
                repeat(8) { tapInteractive("right", 5_000_000L, 18_000_000L) }
                repeat(2) { tapInteractive("down", 5_000_000L, 25_000_000L) }
                tapInteractive("menu", 5_000_000L, 45_000_000L)
                assertTrue(
                    waitUntil(20_000L) { !interactiveFuture.isDone && runCatching { readU32(rt, 0x01CC6424L) == 0x0422 }.getOrDefault(false) },
                    "Expected OOBE Date/Time 0x0422 after confirming Area (scr=0x${runCatching { readU32(rt, 0x01CC6424L) }.getOrDefault(-1).toString(16)}, done=${interactiveFuture.isDone}, off=${rt.session.isSocPoweredOff})",
                )
                committedHomeTz = readHomeTzRam(rt)
                assertEquals(0, committedHomeTz.first, "Moving east from UTC+0 must set positive sign (0), got $committedHomeTz")
                assertTrue(committedHomeTz.second > 0, "Expected non-zero bHomeTimeHours after moving east 8 bands on 0x0423, got $committedHomeTz")

                // Confirm Date/Time (0x0422) -> Start App (0x0482) -> LiveView STILL HUD (0x0100)
                tapInteractive("menu", 5_000_000L, 45_000_000L)
                if (readU32(rt, 0x01CC6424L) == 0x0482) {
                    tapInteractive("menu", 5_000_000L, 40_000_000L)
                }
                assertTrue(
                    waitUntil(20_000L) { !interactiveFuture.isDone && runCatching { readU32(rt, 0x01CC6424L) == 0x0100 }.getOrDefault(false) },
                    "Expected STILL HUD 0x0100 after OOBE",
                )

                // Before closing window: Sub-MCU NVRAM & RTC are already on disk, while NOR BlockA Slot 1 is still unwritten (0xFF) and power==true
                assertTrue(Files.isRegularFile(subMcuNvram), "submcu-nvram.json must exist after OOBE")
                assertTrue(Files.isRegularFile(rtcState), "rtc-state.json must exist after Date/Time confirm")
                val powerBeforeClose = rt.owner.submit {
                    requireNotNull(rt.host.ports.find("buttons", ButtonPort::class.java)).pressed("power")
                }.join()
                assertTrue(powerBeforeClose && !rt.session.isSocPoweredOff, "Camera power switch must still be ON before window close")
                assertTrue(
                    readNorBlockASlot(1).all { it == 0xFF.toByte() },
                    "Stock firmware defers BlockA Slot 1 NOR write until power-off; expected Slot 1 erased before close",
                )
            } finally {
                // Simulate user closing the emulator window (X) without flipping camera ON/OFF to OFF first
                stopRequested.set(true)
            }

            val stopState = interactiveFuture.get(15, TimeUnit.SECONDS)
            assertNotEquals("crashed", stopState["status"], "runInteractive shutdown must not crash")
            assertTrue(rt.session.isSocPoweredOff, "Shutdown drain must complete graceful firmware power-off before exiting runInteractive")
        }

        // Verify NOR Flash BlockA Slot 1 (0x03DC2000) on disk now stores the committed non-zero Area/Timezone!
        val slot1 = readNorBlockASlot(1)
        assertTrue(slot1.any { it != 0xFF.toByte() }, "Graceful shutdown drain must program BlockA Slot 1 (0x03DC2000) into nor-flash.bin")
        val diskSlot1Tz = Triple(
            slot1[0x0F48].toInt() and 0xFF,
            slot1[0x0F4A].toInt() and 0xFF,
            slot1[0x0F4C].toInt() and 0xFF,
        )
        assertEquals(committedHomeTz, diskSlot1Tz, "NOR BlockA Slot 1 (+0x0F48/+0x0F4A/+0x0F4C) must match committed Home Timezone")

        // Boot #2: Cold restart on same workRoot -> auto-recovers power=true, skips OOBE straight to 0x0100, and reloads exact Area/Timezone!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt2 ->
            val powerRestored = rt2.owner.submit {
                requireNotNull(rt2.host.ports.find("buttons", ButtonPort::class.java)).pressed("power")
            }.join()
            assertTrue(powerRestored, "Next session launch must auto-restore power=true")
            rt2.owner.submit { rt2.session.initialize() }.join()
            val scr2 = waitForScreen(rt2, setOf(0x0421, 0x0423, 0x0422, 0x0100), maxIters = 45)
            assertEquals(0x0100, scr2, "Boot #2 must skip OOBE directly to STILL HUD 0x0100")
            assertEquals(
                committedHomeTz,
                readHomeTzRam(rt2),
                "Boot #2 must reload the exact persisted Home Area/Timezone from nor-flash.bin instead of reverting to UTC+0 London",
            )
        }

        // Verify CLI --restore-factory-flash also clears submcu-nvram.json and rtc-state.json so Boot #3 returns to OOBE 0x0421
        val cliExit = executeMain(
            arrayOf("--home", workRoot.toString(), "--config", "configs/emulator.yaml", "--restore-factory-flash"),
            allowInteractiveGuiPrompt = false,
        )
        assertEquals(0, cliExit, "--restore-factory-flash CLI must succeed")
        assertFalse(Files.exists(subMcuNvram), "--restore-factory-flash must delete stale submcu-nvram.json")
        assertFalse(Files.exists(rtcState), "--restore-factory-flash must delete stale rtc-state.json")

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt3 ->
            rt3.owner.submit { rt3.session.initialize() }.join()
            val scr3 = waitForScreen(rt3, setOf(0x0421, 0x0100), maxIters = 45)
            assertEquals(0x0421, scr3, "Cold boot after --restore-factory-flash must enter fresh OOBE 0x0421 instead of skipping OOBE")
        }
    }
}
