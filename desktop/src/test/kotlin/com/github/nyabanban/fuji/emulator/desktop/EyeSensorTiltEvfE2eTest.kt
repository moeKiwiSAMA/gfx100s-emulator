package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live GXUP0004.DAT (Dynarmic V2) end-to-end verification for BUG-V3-9-3:
 *  - Tilting-LCD MMA8452Q (I2C2 @ 0x1D) bank14[0x89d6..0x89de] mounting matrix M_lcd = [[-1,0,0],[0,1,0],[0,0,-1]]
 *  - At lcdTiltDeg=0°, firmware keeps KEY_LCD_TILT flag (0x01CC5DDC) cleared and TMD27723 (I2C1 @ 0x39 + GPIO(0,8))
 *    auto-switches Rear LCD <-> EVF cleanly (both baseline and with FACE ON / EYE AUTO via Fn2)
 *  - At lcdTiltDeg=45° (> ~30° bank14[0x89e5..0x89e7]), firmware emits KEY_LCD_TILT (0x5015 -> 0x01CC5DDC=1)
 *    and suppresses EVF eye-sensor switch; folding back to 0° emits KEY_LCD_OFF (0x5016 -> 0x01CC5DDC=0) and
 *    immediately engages EVF while eyeSensorNear=true.
 */
class EyeSensorTiltEvfE2eTest {
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

    private fun overlayWorktreeStage(repo: Path, workRoot: Path) {
        val emuStage = repo.resolve("build/emulator")
        val compDir = emuStage.resolve("compoments")
        val dstCompDir = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDir)) {
            val expectedJars = Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
        val lensesDir = emuStage.resolve("lenses")
        if (Files.isDirectory(lensesDir)) {
            copyDirectory(lensesDir, workRoot.resolve("lenses"))
        }
        val emuYaml = repo.resolve("tools/release/emulator-release-linux.yaml")
        if (Files.isRegularFile(emuYaml)) {
            Files.copy(emuYaml, workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
        }
        val catalogJson = repo.resolve("catalog/compiled/catalog.json")
        if (Files.isRegularFile(catalogJson)) {
            Files.copy(catalogJson, workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        }
        val relMeta = repo.resolve("build/release-metadata/gfx100s-213-release.yaml")
        if (Files.isRegularFile(relMeta)) {
            val dst = workRoot.resolve("configs/firmware/gfx100s-213-release.yaml")
            Files.createDirectories(dst.parent)
            Files.copy(relMeta, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        val compConfigs = repo.resolve("tools/release/component-configs")
        if (Files.isDirectory(compConfigs)) {
            copyDirectory(compConfigs, workRoot.resolve("configs/components"))
        }
    }

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long): Long {
        val start = rt.owner.submit { (rt.session.state()["attempts"] as Number).toLong() }.join()
        val target = start + deltaAttempts
        var current = start
        while (current < target) {
            current = rt.owner.submit {
                var i = 0
                while (i < 2048) {
                    if (!rt.session.stepFast()) break
                    i++
                }
                (rt.session.state()["attempts"] as Number).toLong()
            }.join()
            val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            if (status != "running") break
        }
        return current
    }

    private fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long) {
        val target = rt.telemetry.virtualTick + deltaMs * 54_000L
        while (rt.telemetry.virtualTick < target) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 1024 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                !rt.session.isSocPoweredOff
            }.join()
            check(keepGoing) { "Emulation stopped before virtual tick $target" }
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, settings).join()
        }.join()
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdAttempts: Long, settleAttempts: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdAttempts)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleAttempts)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun capturePort(rt: EmulatorRuntime, portName: String): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        Triple(display.sequence, display.width to display.height, pixels)
    }.join()

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00ffffff) != 0 }

    @Test
    fun liveTmd2772EyeSensorAutoSwitchesEvfAtZeroTiltAndSuppressesWhenTilted(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_TILT_EVF_LIVE") == "1",
            "Set FUJI_RUN_TILT_EVF_LIVE=1 to run live GXUP0004.DAT eye-sensor/tilt E2E",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires staged release at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("tilt-evf-release")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Boot to OOBE (0x0421) and advance to STILL HUD (0x0100)
            stepBy(rt, 620_000_000L)
            assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join())
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected Language OOBE 0x0421")

            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0423, readU32(rt, 0x01CC6424L), "Expected Area OOBE 0x0423")
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0422, readU32(rt, 0x01CC6424L), "Expected Date/Time OOBE 0x0422")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0482, readU32(rt, 0x01CC6424L), "Expected Start App OOBE 0x0482")
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            var hudReached = false
            for (iter in 0 until 45) {
                stepBy(rt, 6_000_000L)
                val scr = readU32(rt, 0x01CC6424L)
                val mainNb = countNonBlack(capturePort(rt, "display.main").third)
                if (scr == 0x0100 && mainNb > 150_000) {
                    hudReached = true
                    break
                }
            }
            assertTrue(hudReached, "Expected STILL LiveView HUD 0x0100")
            stepVirtualMs(rt, 600L)

            // 2. Flat LCD (lcdTiltDeg=0°): KEY_LCD_TILT flag (0x01CC5DDC) must be 0, eyeSensorNear=true must switch LCD -> EVF
            val tiltFlagFlat = readU8(rt, 0x01CC5DDCL)
            assertEquals(0, tiltFlagFlat, "Flat LCD (lcdTiltDeg=0°) must keep firmware LCD-tilted flag 0x01CC5DDC=0")
            assertEquals(2, readU8(rt, 0x0187974EL), "TMD27723 Task 0x46 must settle in FAR state (2)")

            val (evfSeq0, _, _) = capturePort(rt, "display.evf")
            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 1200L)
            val (evfSeq1, evfDims1, evfPx1) = capturePort(rt, "display.evf")
            val (_, _, mainPx1) = capturePort(rt, "display.main")
            val evfNb1 = countNonBlack(evfPx1)
            val mainNb1 = countNonBlack(mainPx1)
            println("[TILT-EVF][FLAT_NEAR] tiltFlag=${readU8(rt, 0x01CC5DDCL)} eyeTask=${readU8(rt, 0x0187974EL)} evfSeq=$evfSeq0->$evfSeq1 dims=$evfDims1 evfNb=$evfNb1 mainNb=$mainNb1")
            assertEquals(1, readU8(rt, 0x0187974EL), "TMD27723 must enter NEAR state (1)")
            assertTrue(evfSeq1 > evfSeq0 && evfNb1 > 150_000, "eyeSensorNear=true at lcdTiltDeg=0° must auto-switch LCD -> EVF (evfSeq=$evfSeq1, evfNb=$evfNb1)")
            assertEquals(1280 to 960, evfDims1, "EVF display dimensions must be 1280x960")
            assertEquals(0, mainNb1, "Rear LCD must blank while EVF is active (mainNb=$mainNb1)")

            @Suppress("UNCHECKED_CAST")
            val tmdNearDev = (rt.owner.submit { rt.host.components().single { it.descriptor.id == "tmd2772" }.component.state()["device"] }.join()) as? Map<String, Any?>
            assertNotNull(tmdNearDev)
            assertEquals(432, (tmdNearDev["lowThreshold"] as Number).toInt(), "PILT=432 on NEAR")
            assertEquals(65535, (tmdNearDev["highThreshold"] as Number).toInt(), "PIHT=65535 on NEAR")

            // Return to Rear LCD
            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 1200L)
            val evfNbFar = countNonBlack(capturePort(rt, "display.evf").third)
            val mainNbFar = countNonBlack(capturePort(rt, "display.main").third)
            println("[TILT-EVF][FLAT_FAR] tiltFlag=${readU8(rt, 0x01CC5DDCL)} eyeTask=${readU8(rt, 0x0187974EL)} evfNb=$evfNbFar mainNb=$mainNbFar")
            assertEquals(2, readU8(rt, 0x0187974EL), "TMD27723 must return to FAR state (2)")
            assertTrue(mainNbFar > 150_000, "eyeSensorNear=false must restore Rear LCD (mainNb=$mainNbFar)")
            assertEquals(0, evfNbFar, "EVF must blank on eye away (evfNb=$evfNbFar)")

            // 3. Enable FACE ON / EYE AUTO via Fn2 (soc-adc.fn2Button) and verify EVF auto-switch still succeeds cleanly
            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("fn2Button" to true), persistToDisk = false) }.join()
            stepVirtualMs(rt, 100L)
            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("fn2Button" to false), persistToDisk = false) }.join()
            stepVirtualMs(rt, 500L)

            val (evfSeqFace0, _, _) = capturePort(rt, "display.evf")
            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 1200L)
            val (evfSeqFace1, _, evfPxFace1) = capturePort(rt, "display.evf")
            val evfNbFace1 = countNonBlack(evfPxFace1)
            val mainNbFace1 = countNonBlack(capturePort(rt, "display.main").third)
            println("[TILT-EVF][FACE_ON_NEAR] evfSeq=$evfSeqFace0->$evfSeqFace1 evfNb=$evfNbFace1 mainNb=$mainNbFace1")
            assertTrue(evfSeqFace1 > evfSeqFace0 && evfNbFace1 > 150_000, "EVF auto-switch must succeed with FACE ON / EYE AUTO active (evfNb=$evfNbFace1)")
            assertEquals(0, mainNbFace1, "Rear LCD must blank with FACE ON / EYE AUTO EVF active")

            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 1200L)
            assertTrue(countNonBlack(capturePort(rt, "display.main").third) > 150_000, "Rear LCD must restore after FACE ON / EYE AUTO EVF exit")
            assertEquals(0, countNonBlack(capturePort(rt, "display.evf").third), "EVF must blank after FACE ON / EYE AUTO EVF exit")

            // 4. Tilt Rear LCD to 45° -> firmware emits KEY_LCD_TILT (0x5015 -> 0x01CC5DDC=1) and suppresses EVF auto-switch
            patchComponent(rt, "mma8452q", mapOf("lcdTiltDeg" to 45.0))
            stepVirtualMs(rt, 1200L)
            val tiltFlag45 = readU8(rt, 0x01CC5DDCL)
            println("[TILT-EVF][TILT_45] tiltFlag=$tiltFlag45")
            assertEquals(1, tiltFlag45, "Tilting Rear LCD to 45° must assert firmware KEY_LCD_TILT flag 0x01CC5DDC=1")

            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 1000L)
            val evfNbTilted = countNonBlack(capturePort(rt, "display.evf").third)
            val mainNbTilted = countNonBlack(capturePort(rt, "display.main").third)
            println("[TILT-EVF][TILT_45_NEAR] tiltFlag=${readU8(rt, 0x01CC5DDCL)} eyeTask=${readU8(rt, 0x0187974EL)} evfNb=$evfNbTilted mainNb=$mainNbTilted")
            assertEquals(1, readU8(rt, 0x0187974EL), "TMD27723 proximity still triggers NEAR state while LCD is tilted")
            assertEquals(0, evfNbTilted, "Tilted LCD (45°) must suppress EVF eye-sensor auto-switch (evfNb=$evfNbTilted)")
            assertTrue(mainNbTilted > 150_000, "Rear LCD must remain active while tilted even with eyeSensorNear=true (mainNb=$mainNbTilted)")

            // 5. Fold LCD flat (0°) while eyeSensorNear is still true -> firmware emits KEY_LCD_OFF (0x5016) & engages EVF!
            patchComponent(rt, "mma8452q", mapOf("lcdTiltDeg" to 0.0))
            stepVirtualMs(rt, 1400L)
            val tiltFlagRestored = readU8(rt, 0x01CC5DDCL)
            val evfNbRestored = countNonBlack(capturePort(rt, "display.evf").third)
            val mainNbRestored = countNonBlack(capturePort(rt, "display.main").third)
            println("[TILT-EVF][FOLD_0_RESTORE] tiltFlag=$tiltFlagRestored evfNb=$evfNbRestored mainNb=$mainNbRestored")
            assertEquals(0, tiltFlagRestored, "Folding LCD flat (0°) must clear firmware KEY_LCD_TILT flag 0x01CC5DDC=0")
            assertTrue(evfNbRestored > 150_000, "Folding LCD flat while eyeSensorNear=true must immediately restore EVF (evfNb=$evfNbRestored)")
            assertEquals(0, mainNbRestored, "Rear LCD must blank once EVF engages after folding LCD flat")

            // Final clean return to Rear LCD
            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 1000L)
            assertTrue(countNonBlack(capturePort(rt, "display.main").third) > 150_000, "Releasing eye sensor must return cleanly to Rear LCD")
            assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join())

            val relaxedSummary = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join()
            if (relaxedSummary != null) {
                assertTrue(relaxedSummary.contains("discoveryFallbacks=0"), "Zero discovery fallbacks expected: $relaxedSummary")
                assertTrue(relaxedSummary.contains("refused=0"), "Zero refused flash ops expected: $relaxedSummary")
            }
            println("[TILT-EVF] ALL LIVE ASSERTIONS PASSED! relaxed=$relaxedSummary")
        }
    }
}
