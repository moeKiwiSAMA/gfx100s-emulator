package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.ShutterPort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` (Dynarmic JIT) end-to-end verification for Track `ARCH-SHUTTER` & `BUG-A2-5`:
 * - Verifies standalone decoupled `:components:shutter` (`shutter.mechanism`, EXTINT #3/#5/#10 GIC IRQs
 *   154/156/161/170/172/177) alongside `soc-gpio` (deferring shutter IRQs, owning only S1/S2 167/168)
 *   and `soc-isp-cmd` (decoupled `0xFFF6C184` CCDTG `TG_ACT` never fabricating `curtainRelease`).
 * - Captures real still JPEGs onto SD Slot 1 across all three GFX100S shutter modes:
 *   1. **MS** (`MECHANICAL SHUTTER`): completes cleanly, writes valid JPEG, `cam1Cycles > 0`, `cam2Cycles > 0`, `curtainPulses > 0`.
 *   2. **EFCS** (`E-FRONT CURTAIN SHUTTER`): completes cleanly, writes valid JPEG, mechanical rear curtain actuates.
 *   3. **ES** (`ELECTRONIC SHUTTER`): completes cleanly, writes valid JPEG, **`curtainPulses` delta == 0 (`BUG-A2-5` fixed!)**,
 *      **`cam2Cycles` delta == 0**, and zero unmodeled fallbacks.
 */
class ShutterModesLiveE2eTest {

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun stepBy(
        rt: EmulatorRuntime,
        deltaAttempts: Long,
        maxDeltaVt: Long = Long.MAX_VALUE,
        maxWallMs: Long = Long.MAX_VALUE,
    ): Long {
        val deadlineMs = if (maxWallMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + maxWallMs
        return rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            val targetVt = if (maxDeltaVt == Long.MAX_VALUE) Long.MAX_VALUE else rt.host.tick + maxDeltaVt
            var stepCount = 0
            while (rt.session.checkpointAttempts() < target && rt.host.tick < targetVt) {
                if ((stepCount and 63) == 0 && System.currentTimeMillis() >= deadlineMs) break
                if (!rt.session.stepFast()) break
                stepCount++
            }
            rt.session.checkpointAttempts()
        }.join()
    }

    private fun patchComp(rt: EmulatorRuntime, id: String, patch: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, patch).join()
        }.join()
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 512 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
    }

    private fun tapButton(
        rt: EmulatorRuntime,
        key: String,
        holdTicks: Long = 4_500_000L,
        releaseTicks: Long = 30_000_000L,
    ) {
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
        stepVirtualTicks(rt, holdTicks)
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
        stepVirtualTicks(rt, releaseTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readGuestU16(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.short.toInt() and 0xffff
    }.join()

    private fun readGuestU8(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(address, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun displayNonBlack(rt: EmulatorRuntime): Int = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(d.width * d.height)
        d.copyArgb(px)
        px.count { (it and 0x00ffffff) != 0 }
    }.join()

    private fun displayMainSequence(rt: EmulatorRuntime): Long = rt.owner.submit {
        requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence
    }.join()

    private fun dumpMainPng(rt: EmulatorRuntime, out: Path) {
        val (w, h, px) = rt.owner.submit {
            val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val arr = IntArray(d.width * d.height)
            d.copyArgb(arr)
            Triple(d.width, d.height, arr)
        }.join()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        Files.createDirectories(out.parent)
        ImageIO.write(img, "png", out.toFile())
    }

    private fun jpegEncodeCount(rt: EmulatorRuntime): Long = rt.owner.submit {
        val st = rt.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state()
        (st["encodeCount"] as? Number)?.toLong() ?: 0L
    }.join()

    private fun listDcimJpegs(workRoot: Path): List<Path> {
        val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcimRoot)) return emptyList()
        return Files.walk(dcimRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().uppercase().endsWith(".JPG") }
                .sorted()
                .toList()
        }
    }

    private fun shutterSnapshot(rt: EmulatorRuntime): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == "shutter" }.component.state()
    }.join()

    private fun takeStillShot(rt: EmulatorRuntime, workRoot: Path, label: String): Path {
        val dcimBefore = listDcimJpegs(workRoot).toSet()
        val encBefore = jpegEncodeCount(rt)
        // Set S/C/M lever to MF (input7=16448) so S2 fires immediately without AF-S focus priority wait
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 16448L))
        }.join()
        stepBy(rt, 16_000_000L)

        patchComp(rt, "buttons", mapOf("shutterHalf" to true))
        for (w in 0 until 5) {
            stepBy(rt, 12_000_000L)
            val s1Sub0 = readGuestU8(rt, 0x02C47C5CL + 0x60L)
            val saclac0 = readGuestU16(rt, 0x02DA49ECL)
            if (w >= 1 && (s1Sub0 >= 4 || (s1Sub0 == 0 && saclac0 == 4))) break
        }
        patchComp(rt, "buttons", mapOf("shutterFull" to true))
        repeat(3) { stepBy(rt, 12_000_000L) }
        patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

        var newFile: Path? = null
        var autoResumed = false
        for (chunk in 0 until 40) {
            stepBy(rt, 12_000_000L, maxDeltaVt = 40_000_000L, maxWallMs = 10_000L)
            val encNow = jpegEncodeCount(rt)
            val dcimNow = listDcimJpegs(workRoot)
            val scrNow = readGuestU32(rt, 0x01CC6424L)
            val bDscNow = readGuestU8(rt, 0x01CBFCA2L)
            val added = dcimNow.firstOrNull { it !in dcimBefore }
            if (added != null && newFile == null) {
                newFile = added
            }
            if (chunk == 0 || chunk == 5 || chunk == 15 || chunk == 39) {
                val st = shutterSnapshot(rt)
                val drvMode = readGuestU8(rt, 0x02AB1318L + 0x198L)
                val saclacFp = readGuestU8(rt, 0x02D781B0L + 0x38L)
                val s1Sub0 = readGuestU8(rt, 0x02C47C5CL + 0x60L)
                val saclac0 = readGuestU16(rt, 0x02DA49ECL)
                println(
                    "[SHUTTER-E2E][$label][chunk=$chunk] scr=0x${scrNow.toString(16)} bDsc=$bDscNow enc=$encNow(before=$encBefore) " +
                        "dcim=${dcimNow.size} s1Sub0=$s1Sub0 saclac0=$saclac0 fw198=$drvMode fw38=$saclacFp " +
                        "m1=${st["motor1Running"]} m2=${st["motor2Running"]} cam1=${st["cam1Cycles"]} cam2=${st["cam2Cycles"]} " +
                        "curtain=${st["curtainPulses"]} tgAct=${st["tgActCount"]}",
                )
            }
            // BUG-A2-1 / BUG-V3-3-2 regression gate: firmware MUST automatically resume LiveView
            // (scr=0x0100/0x0102, bDsc=0, nonBlack>150k) WITHOUT tapping BACK!
            if (encNow > encBefore && newFile != null && bDscNow == 0 && scrNow in listOf(0x0100, 0x0102) && displayNonBlack(rt) > 150_000) {
                autoResumed = true
                break
            }
        }
        val seqAtResume = displayMainSequence(rt)
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 8256L))
        }.join()
        stepBy(rt, 12_000_000L, maxDeltaVt = 12_000_000L, maxWallMs = 10_000L)
        val seqAfterResume = displayMainSequence(rt)
        val scrFinal = readGuestU32(rt, 0x01CC6424L)
        val nonBlackFinal = displayNonBlack(rt)
        println(
            "[SHUTTER-E2E][$label][DONE] autoResumed=$autoResumed scr=0x${scrFinal.toString(16)} " +
                "nonBlack=$nonBlackFinal seq=$seqAtResume->$seqAfterResume file=${newFile?.fileName}",
        )
        assertTrue(
            autoResumed,
            "[$label] Expected automatic LiveView resume (scr=0x0100/0x0102, bDsc=0, nonBlack>150000) without BACK tap; final scr=0x${scrFinal.toString(16)} nonBlack=$nonBlackFinal",
        )
        assertTrue(
            scrFinal in listOf(0x0100, 0x0102),
            "[$label] Expected STILL HUD 0x0100/0x0102 after capture, got 0x${scrFinal.toString(16)}",
        )
        assertTrue(
            nonBlackFinal > 150_000,
            "[$label] Expected active LiveView nonBlack > 150000 after capture, got $nonBlackFinal",
        )
        assertTrue(
            seqAfterResume > seqAtResume,
            "[$label] Expected display.main sequence to keep advancing after capture ($seqAtResume -> $seqAfterResume)",
        )

        val jpg = assertNotNull(newFile, "[$label] Expected new JPEG written to SD Slot 1 DCIM")
        val prefix = Files.newInputStream(jpg).use { it.readNBytes(32) }
        assertTrue(
            prefix.size >= 16 && (prefix[0].toInt() and 0xFF) == 0xFF && (prefix[1].toInt() and 0xFF) == 0xD8,
            "[$label] Expected valid JPEG SOI header in ${jpg.fileName}",
        )
        assertTrue(Files.size(jpg) > 4096L, "[$label] Expected non-trivial JPEG size in ${jpg.fileName}")
        return jpg
    }

    private fun returnToStillHud(rt: EmulatorRuntime, maxBackTaps: Int = 5) {
        for (attempt in 0 until maxBackTaps) {
            val scr = readGuestU32(rt, 0x01CC6424L)
            val bDsc = readGuestU8(rt, 0x01CBFCA2L)
            if (scr in listOf(0x0100, 0x0102) && bDsc == 0) break
            tapButton(rt, "back", 4_500_000L, 45_000_000L)
        }
    }

    private fun ensureMenuOpen(rt: EmulatorRuntime) {
        for (attempt in 0 until 5) {
            val scr = readGuestU32(rt, 0x01CC6424L)
            if (scr in 0x0400..0x04FF) return
            if (scr != 0x0100) {
                tapButton(rt, "back", 4_500_000L, 35_000_000L)
            }
            tapButton(rt, "menu", 4_500_000L, 55_000_000L)
            if (readGuestU32(rt, 0x01CC6424L) in 0x0400..0x04FF) return
        }
        val finalScr = readGuestU32(rt, 0x01CC6424L)
        assertTrue(finalScr in 0x0400..0x04FF, "Expected MENU screen 0x04xx, got 0x${finalScr.toString(16)}")
    }

    private fun openShutterTypeSubmenu(
        rt: EmulatorRuntime,
        firstVisit: Boolean,
        stepsFromCurrent: Int,
        dumpDir: Path?,
        tag: String,
    ) {
        ensureMenuOpen(rt)
        if (firstVisit) {
            // First MENU open lands on Tab 0 (I.Q.) item list -> LEFT -> sidebar -> DOWN x2 -> Tab 2 (SHOOTING) -> RIGHT -> DOWN x9 -> SHUTTER TYPE
            tapButton(rt, "left", 4_500_000L, 35_000_000L)
            tapButton(rt, "down", 4_500_000L, 35_000_000L)
            tapButton(rt, "down", 4_500_000L, 35_000_000L)
            tapButton(rt, "right", 4_500_000L, 35_000_000L)
            repeat(9) { tapButton(rt, "down", 4_500_000L, 22_000_000L) }
        }
        // Subsequent MENU opens remember SHUTTER TYPE directly! Enter submenu via RIGHT with full render settle.
        tapButton(rt, "right", 4_500_000L, 65_000_000L)
        dumpDir?.let { dumpMainPng(rt, it.resolve("${tag}-1-submenu-initial.png")) }
        if (stepsFromCurrent > 0) {
            repeat(stepsFromCurrent) { tapButton(rt, "down", 4_500_000L, 35_000_000L) }
        } else if (stepsFromCurrent < 0) {
            repeat(-stepsFromCurrent) { tapButton(rt, "up", 4_500_000L, 35_000_000L) }
        }
        dumpDir?.let { dumpMainPng(rt, it.resolve("${tag}-2-submenu-target.png")) }
        tapButton(rt, "menu", 4_500_000L, 55_000_000L) // Confirm selection
        returnToStillHud(rt)
        val scrAfter = readGuestU32(rt, 0x01CC6424L)
        assertTrue(scrAfter in listOf(0x0100, 0x0102), "[$tag] Expected STILL HUD 0x0100/0x0102, got 0x${scrAfter.toString(16)}")
    }

    @Test
    fun releaseShutterConfigEnablesStandaloneShutterComponent() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val shutterCfg = Formats.read(repo.resolve("tools/release/component-configs/shutter/shutter.yaml"))
        val gpioCfg = Formats.read(repo.resolve("tools/release/component-configs/soc-gpio/soc-gpio.yaml"))
        assertEquals(true, shutterCfg["enabled"], "Release shutter.yaml must enable standalone shutter component")
        assertEquals("disabled", gpioCfg["shutterMechanismProfile"], "Release soc-gpio.yaml must defer shutter mechanism to standalone shutter")
    }

    @Test
    fun verifiesMsEfcsAndEsCapturesLiveWithZeroCurtainPulsesInEs(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_SHUTTER_E2E") == "1",
            "Set FUJI_RUN_SHUTTER_E2E=1 to run live GXUP0004.DAT MS/EFCS/ES shutter verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val dumpDir = System.getenv("FUJI_SHUTTER_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val workRoot = tempRoot.resolve("shutter-release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // Verify standalone shutter port is registered and enabled
            val portLive = rt.owner.submit {
                rt.host.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java)?.enabled == true
            }.join()
            assertTrue(portLive, "Standalone shutter.mechanism port must be live")

            // Cold boot -> OOBE -> STILL HUD (0x0100)
            for (i in 0 until 60) {
                stepBy(rt, 12_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (iter in 0 until 35) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && displayNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100")

            fun logShutter(tag: String): Map<String, Any?> {
                val st = shutterSnapshot(rt)
                val drvMode = readGuestU8(rt, 0x02AB1318L + 0x198L)
                val saclacFp = readGuestU8(rt, 0x02D781B0L + 0x38L)
                val shutterTypeByte = readGuestU8(rt, 0x01C7384DL)
                val scr = readGuestU32(rt, 0x01CC6424L)
                val vtMs = rt.telemetry.virtualTick / 54_000L
                println(
                    "[SHUTTER-E2E][$tag] vt=${vtMs}ms scr=0x${scr.toString(16)} eModeShutterType=$shutterTypeByte " +
                        "mode=${st["mode"]} cam1=${st["cam1Cycles"]} cam2=${st["cam2Cycles"]} " +
                        "curtain=${st["curtainPulses"]} tgAct=${st["tgActCount"]} total=${st["shutterCount"]} " +
                        "fwDrvMode198=$drvMode fwSaclacFp38=$saclacFp",
                )
                return st
            }

            // GFX100S 2.13 SHUTTER TYPE submenu order (verified live):
            //   Row 0: MS  (MECHANICAL SHUTTER)
            //   Row 1: ES  (ELECTRONIC SHUTTER)
            //   Row 2: EF  (E-FRONT CURTAIN SHUTTER — factory boot default!)
            //   Row 3: M+E (MECHANICAL + ELECTRONIC)
            //   Row 4: EF+E (E-FRONT + ELECTRONIC)

            val shotsPerMode = 5

            // 1. Switch EF (Row 2) -> UP x1 -> ES (ELECTRONIC SHUTTER, Row 1) and verify 5 consecutive shots + BUG-A2-5!
            openShutterTypeSubmenu(rt, firstVisit = true, stepsFromCurrent = -1, dumpDir = dumpDir, tag = "es")
            val beforeEs = logShutter("BEFORE_ES")
            val esJpgs = (1..shotsPerMode).map { shot -> takeStillShot(rt, workRoot, "ES-$shot") }
            val afterEs = logShutter("AFTER_ES")
            val esCam1Delta = (afterEs["cam1Cycles"] as Number).toLong() - (beforeEs["cam1Cycles"] as Number).toLong()
            val esCam2Delta = (afterEs["cam2Cycles"] as Number).toLong() - (beforeEs["cam2Cycles"] as Number).toLong()
            val esCurtainDelta = (afterEs["curtainPulses"] as Number).toLong() - (beforeEs["curtainPulses"] as Number).toLong()
            val esTgActDelta = (afterEs["tgActCount"] as Number).toLong() - (beforeEs["tgActCount"] as Number).toLong()
            println("[SHUTTER-E2E] ES shots=${esJpgs.map { it.fileName }} dCam1=$esCam1Delta dCam2=$esCam2Delta dCurtain=$esCurtainDelta dTgAct=$esTgActDelta scrAfter=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")

            assertEquals(shotsPerMode, esJpgs.map { it.fileName }.toSet().size, "ES must produce $shotsPerMode distinct JPEGs")
            // BUG-A2-5 core assertion: pure Electronic Shutter MUST NEVER pulse mechanical curtain gate or Motor 2!
            assertEquals(0L, esCurtainDelta, "BUG-A2-5: Electronic Shutter (ES) must have ZERO curtainPulses delta")
            assertEquals(0L, esCam2Delta, "Electronic Shutter (ES) must have ZERO Motor 2 (cam2Cycles) delta")
            assertTrue(esTgActDelta >= shotsPerMode.toLong(), "Electronic Shutter (ES) must observe CCDTG TG_ACT on every shot: dTgAct=$esTgActDelta")
            assertEquals("ELECTRONIC", afterEs["mode"], "ES capture must report mode=ELECTRONIC")

            // 2. Switch ES (Row 1) -> UP x1 -> MS (MECHANICAL SHUTTER, Row 0) and verify 5 consecutive shots + Motor 1 + Motor 2 + Curtain!
            openShutterTypeSubmenu(rt, firstVisit = false, stepsFromCurrent = -1, dumpDir = dumpDir, tag = "ms")
            val beforeMs = logShutter("BEFORE_MS")
            val msJpgs = (1..shotsPerMode).map { shot -> takeStillShot(rt, workRoot, "MS-$shot") }
            val afterMs = logShutter("AFTER_MS")
            val msCam1Delta = (afterMs["cam1Cycles"] as Number).toLong() - (beforeMs["cam1Cycles"] as Number).toLong()
            val msCam2Delta = (afterMs["cam2Cycles"] as Number).toLong() - (beforeMs["cam2Cycles"] as Number).toLong()
            val msCurtainDelta = (afterMs["curtainPulses"] as Number).toLong() - (beforeMs["curtainPulses"] as Number).toLong()
            println("[SHUTTER-E2E] MS shots=${msJpgs.map { it.fileName }} dCam1=$msCam1Delta dCam2=$msCam2Delta dCurtain=$msCurtainDelta scrAfter=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")

            assertEquals(shotsPerMode, msJpgs.map { it.fileName }.toSet().size, "MS must produce $shotsPerMode distinct JPEGs")
            assertTrue(msCam1Delta >= shotsPerMode.toLong(), "MS capture must actuate Motor 1 on every shot: dCam1=$msCam1Delta")
            assertTrue(msCam2Delta >= shotsPerMode.toLong(), "MS capture must actuate Motor 2 on every shot: dCam2=$msCam2Delta")
            assertTrue(msCurtainDelta >= shotsPerMode.toLong(), "MS capture must pulse mechanical curtain optical gate on every shot: dCurtain=$msCurtainDelta")

            // Zero unmodeled relaxed MMIO/Flash/DABT fallbacks across ES + MS 10-shot session
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", sessionState["status"], "Emulator must stay running with zero crashes")
            val fallbackSummary = sessionState["relaxedFallbackSummary"]?.toString().orEmpty()
            println("[SHUTTER-E2E] ES+MS relaxedFallbackSummary=$fallbackSummary")
            val hits = FullCameraModeAndSubmenuE2eTest.parseRelaxedMmioEntries(fallbackSummary)
            assertTrue(hits.isEmpty(), "Expected 0 unmodeled MMIO fallbacks across ES+MS, got: $hits")
            assertTrue(!fallbackSummary.contains("[RELAXED/FLASH/REFUSED]"), "Expected 0 flash refusals across ES+MS: $fallbackSummary")
            assertTrue(!fallbackSummary.contains("[RELAXED/DABT]"), "Expected 0 DABT fallbacks across ES+MS: $fallbackSummary")
        }

        // 3. Separate clean boot verifying factory-default EFCS (Row 2) takes 5 consecutive shots with automatic LiveView resume & 0 fallbacks
        val efcsRoot = tempRoot.resolve("shutter-efcs-root")
        copyDirectory(stagedRoot, efcsRoot)
        val efcsConfig = Formats.read(efcsRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(efcsRoot, efcsConfig, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            for (i in 0 until 60) {
                stepBy(rt, 12_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (iter in 0 until 35) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && displayNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100 for EFCS boot")
            val shotsPerMode = 5
            val beforeEfcs = shutterSnapshot(rt)
            val efcsJpgs = (1..shotsPerMode).map { shot -> takeStillShot(rt, efcsRoot, "EFCS-$shot") }
            val afterEfcs = shutterSnapshot(rt)
            val efcsCam1Delta = (afterEfcs["cam1Cycles"] as Number).toLong() - (beforeEfcs["cam1Cycles"] as Number).toLong()
            val efcsCam2Delta = (afterEfcs["cam2Cycles"] as Number).toLong() - (beforeEfcs["cam2Cycles"] as Number).toLong()
            val efcsCurtainDelta = (afterEfcs["curtainPulses"] as Number).toLong() - (beforeEfcs["curtainPulses"] as Number).toLong()
            println("[SHUTTER-E2E] EFCS shots=${efcsJpgs.map { it.fileName }} dCam1=$efcsCam1Delta dCam2=$efcsCam2Delta dCurtain=$efcsCurtainDelta mode=${afterEfcs["mode"]}")
            assertEquals(shotsPerMode, efcsJpgs.map { it.fileName }.toSet().size, "EFCS must produce $shotsPerMode distinct JPEGs")
            assertTrue(efcsCam1Delta >= shotsPerMode.toLong(), "EFCS capture must actuate rear curtain Motor 1 on every shot: dCam1=$efcsCam1Delta")
            val efcsSession = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", efcsSession["status"], "EFCS session must stay running with zero crashes")
            val efcsSummary = efcsSession["relaxedFallbackSummary"]?.toString().orEmpty()
            println("[SHUTTER-E2E] EFCS relaxedFallbackSummary=$efcsSummary")
            val efcsHits = FullCameraModeAndSubmenuE2eTest.parseRelaxedMmioEntries(efcsSummary)
            assertTrue(efcsHits.isEmpty(), "Expected 0 unmodeled MMIO fallbacks during EFCS 5-shot session, got: $efcsHits")
            assertTrue(!efcsSummary.contains("[RELAXED/FLASH/REFUSED]"), "Expected 0 flash refusals during EFCS 5-shot session: $efcsSummary")
            assertTrue(!efcsSummary.contains("[RELAXED/DABT]"), "Expected 0 DABT fallbacks during EFCS 5-shot session: $efcsSummary")
        }
    }
}
