package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
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
import kotlin.test.assertTrue

/**
 * Live GXUP0004.DAT (Dynarmic V2) end-to-end verification for BUG-A3-1 (`gfx100s-2.13-gpio-buttons-v6`):
 *  - Front Command Dial 2-pin GPIO quadrature (`GPIO(1,2)` + `GPIO(1,3)` via `soc-gpio.frontDialDetents`):
 *      * CW/CCW adjusts Aperture (`F`) in A mode on Main LCD + Shoulder Dot-LCD (exact round-trip)
 *      * After `frontDialPress` (`F -> ISO`), CW/CCW adjusts ISO in P mode on Main LCD + Shoulder Dot-LCD (exact round-trip)
 *  - Front Command Dial Press (`GPIO(2,2)` active-LOW -> `frontDialPress`, UI 0x2022)
 *  - Front `Fn4` button (`GPIO(2,13)` active-LOW -> `fn4`, UI 0x0038, Performance Boost)
 *  - Focus Stick Center Press (`GPIO(7,9)` active-LOW wired-OR with idle ADC Ch 9 -> `focusStickPress`, UI 0x0042, scr 0x0100 -> 0x011e)
 *  - Zero `[RELAXED/MMIO]` and zero `[RELAXED/FLASH/REFUSED]` across the entire live session.
 */
class FrontDialAndBodyControlsE2eTest {
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
        var guard = 0
        while (rt.telemetry.virtualTick < target && guard < 6000) {
            guard++
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 512) {
                    if (!rt.session.stepFast()) return@submit false
                    if (rt.telemetry.virtualTick >= target) return@submit true
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, settings).join()
        }.join()
    }

    private fun tapButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 220L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private data class FrameCapture(val seq: Long, val width: Int, val height: Int, val pixels: IntArray)

    private fun captureDisplay(rt: EmulatorRuntime, portName: String): FrameCapture = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        FrameCapture(display.sequence, display.width, display.height, pixels)
    }.join()

    // The live image moves independently of the dial. Compare the firmware's OSD0
    // surface from LCDC1 so only HUD rendering contributes to the pixel counts.
    private fun captureMainHud(rt: EmulatorRuntime): FrameCapture = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val lcdc = requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
        val memory = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val address = (lcdc.read(0x040, 4) shl 32) or lcdc.read(0x044, 4)
        val format = lcdc.read(0x050, 4).toInt()
        val extent = lcdc.read(0x054, 4).toInt()
        val width = extent and 0xffff
        val height = extent ushr 16
        val pitch = format and 0xffff
        check(address != 0L && format ushr 16 == 1 && pitch >= width)
        check(width == display.width && height == display.height)
        val data = ByteBuffer.allocate(((height - 1) * pitch + width) * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        memory.read(address, data)
        val source = data.flip().asIntBuffer()
        val pixels = IntArray(width * height)
        for (y in 0 until height) source.get(y * pitch, pixels, y * width, width)
        FrameCapture(display.sequence, width, height, pixels)
    }.join()

    private fun countRegionDiff(a: FrameCapture, b: FrameCapture, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var diff = 0
        for (y in y0 until y1) {
            val row = y * a.width
            for (x in x0 until x1) {
                if (a.pixels[row + x] != b.pixels[row + x]) diff++
            }
        }
        return diff
    }

    @Test
    fun liveFirmwareFrontCommandDialAdjustsExposureCompAndApertureAndV6ButtonsRespondCleanly(@TempDir tempRoot: Path) {
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

        val workRoot = tempRoot.resolve("front-dial-e2e")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // Confirm v6 GPIO profile is active on boot
            val gpioState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.state()
            }.join()
            assertEquals("gfx100s-2.13-gpio-buttons-v6", gpioState["profile"])

            // Boot cold -> OOBE Language -> Confirm -> Date/Time Skip -> STILL LiveView (scr=0x0100)
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 12) {
                val scr = readU32(rt, 0x01CC6424L)
                if (scr == 0x0501 || scr == 0x0502 || scr == 0x0503 || scr == 0x0504 || scr == 0x0505) {
                    tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 350L)
                } else {
                    tapButtonVirtualMs(rt, "back", holdMs = 85L, settleMs = 350L)
                }
                guard++
            }
            stepVirtualMs(rt, 300L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView scr=0x0100 after OOBE")

            // Wait until STILL LiveView HUD & Shoulder Dot-LCD finish initial draw (subSeq >= 2 & mainSeq >= 20)
            var readyGuard = 0
            while (readyGuard < 30) {
                val m = captureDisplay(rt, "display.main")
                val s = captureDisplay(rt, "display.shoulder")
                if (s.seq >= 2L && m.seq >= 20L) break
                stepVirtualMs(rt, 250L)
                readyGuard++
            }
            stepVirtualMs(rt, 650L)

            // 1. Mode A: Front Command Dial CW (+1, +2, +3 detents) -> Aperture F increases on Main LCD + Shoulder LCD
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "A"))
            stepVirtualMs(rt, 1200L)
            val mainA0 = captureMainHud(rt)
            val subA0 = captureDisplay(rt, "display.shoulder")

            for (detent in 1L..3L) {
                patchComponent(rt, "soc-gpio", mapOf("frontDialDetents" to detent))
                stepVirtualMs(rt, 500L)
            }
            stepVirtualMs(rt, 700L)
            val mainACw = captureMainHud(rt)
            val subACw = captureDisplay(rt, "display.shoulder")
            // Bottom HUD Aperture readout sits in x=[380..620], y=[675..768]
            val fDiffCw = countRegionDiff(mainA0, mainACw, 380, 675, 620, 768)
            val subDiffACw = countRegionDiff(subA0, subACw, 0, 0, subA0.width, subA0.height)
            println("[FRONT-DIAL-E2E] A-mode CW +3: apertureHudDiff=$fDiffCw subSeq=${subA0.seq}->${subACw.seq} subDiff=$subDiffACw")
            assertTrue(fDiffCw > 200, "Front Command Dial CW in A mode must adjust Aperture F-number (apertureHudDiff=$fDiffCw)")
            assertTrue(subACw.seq > subA0.seq && subDiffACw > 50, "Shoulder LCD must reflect Aperture change in A mode (subDiff=$subDiffACw)")

            // Mode A: Front Command Dial CCW back to 0 -> Aperture F returns to initial F4.0
            for (detent in 2L downTo 0L) {
                patchComponent(rt, "soc-gpio", mapOf("frontDialDetents" to detent))
                stepVirtualMs(rt, 500L)
            }
            stepVirtualMs(rt, 700L)
            val mainACcw = captureMainHud(rt)
            val fDiffCcw = countRegionDiff(mainACw, mainACcw, 380, 675, 620, 768)
            val fDiffRoundTrip = countRegionDiff(mainA0, mainACcw, 380, 675, 620, 768)
            println("[FRONT-DIAL-E2E] A-mode CCW ->0: apertureHudDiff=$fDiffCcw roundTripDiff=$fDiffRoundTrip")
            assertTrue(fDiffCcw > 200, "Front Command Dial CCW in A mode must move Aperture back (apertureHudDiff=$fDiffCcw)")
            assertEquals(0, fDiffRoundTrip, "Aperture HUD must return identically to F4.0 after CW +3 / CCW -3 round-trip")

            // 2. frontDialPress (GPIO 2.2) toggles Front Command Dial assignment F -> ISO
            val beforeFrontPress = captureMainHud(rt)
            tapButtonVirtualMs(rt, "frontDialPress", holdMs = 110L, settleMs = 900L)
            val afterFrontPress = captureMainHud(rt)
            val frontPressHudDiff = countRegionDiff(beforeFrontPress, afterFrontPress, 380, 675, 860, 768)
            println("[FRONT-DIAL-E2E] frontDialPress (F -> ISO): hudDiff=$frontPressHudDiff")
            assertTrue(frontPressHudDiff > 200, "frontDialPress must move active Front Command Dial HUD indicator from F to ISO (hudDiff=$frontPressHudDiff)")

            // 3. Mode P (Front Dial on ISO): CW (+1..+3) adjusts ISO on Main LCD + Shoulder LCD; CCW returns identically
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "P"))
            stepVirtualMs(rt, 1200L)
            val mainP0 = captureMainHud(rt)
            val subP0 = captureDisplay(rt, "display.shoulder")

            for (detent in 1L..3L) {
                patchComponent(rt, "soc-gpio", mapOf("frontDialDetents" to detent))
                stepVirtualMs(rt, 500L)
            }
            stepVirtualMs(rt, 700L)
            val mainPCw = captureMainHud(rt)
            val subPCw = captureDisplay(rt, "display.shoulder")
            // Bottom HUD ISO readout sits in x=[640..860], y=[675..768]
            val isoDiffCw = countRegionDiff(mainP0, mainPCw, 640, 675, 860, 768)
            val subDiffPCw = countRegionDiff(subP0, subPCw, 0, 0, subP0.width, subP0.height)
            println("[FRONT-DIAL-E2E] P-mode ISO CW +3: isoHudDiff=$isoDiffCw subSeq=${subP0.seq}->${subPCw.seq} subDiff=$subDiffPCw")
            assertTrue(isoDiffCw > 200, "Front Command Dial CW in P mode (ISO) must adjust ISO (isoHudDiff=$isoDiffCw)")
            assertTrue(subPCw.seq > subP0.seq && subDiffPCw > 50, "Shoulder LCD must reflect ISO change in P mode (subDiff=$subDiffPCw)")

            for (detent in 2L downTo 0L) {
                patchComponent(rt, "soc-gpio", mapOf("frontDialDetents" to detent))
                stepVirtualMs(rt, 500L)
            }
            stepVirtualMs(rt, 700L)
            val mainPCcw = captureMainHud(rt)
            val isoDiffCcw = countRegionDiff(mainPCw, mainPCcw, 640, 675, 860, 768)
            val isoDiffRoundTrip = countRegionDiff(mainP0, mainPCcw, 640, 675, 860, 768)
            println("[FRONT-DIAL-E2E] P-mode ISO CCW ->0: isoHudDiff=$isoDiffCcw roundTripDiff=$isoDiffRoundTrip")
            assertTrue(isoDiffCcw > 200, "Front Command Dial CCW in P mode (ISO) must move ISO back (isoHudDiff=$isoDiffCcw)")
            assertEquals(0, isoDiffRoundTrip, "ISO HUD must return identically to ISO 100 after CW +3 / CCW -3 round-trip")

            // 4. fn4 (GPIO 2.13): triggers Performance Boost overlay + BOOST HUD badge
            val beforeFn4 = captureMainHud(rt)
            tapButtonVirtualMs(rt, "fn4", holdMs = 110L, settleMs = 600L)
            val afterFn4 = captureMainHud(rt)
            val fn4Diff = countRegionDiff(beforeFn4, afterFn4, 0, 0, beforeFn4.width, beforeFn4.height)
            println("[FRONT-DIAL-E2E] fn4: fullDiff=$fn4Diff scr=0x${readU32(rt, 0x01CC6424L).toString(16)}")
            assertTrue(fn4Diff > 500, "fn4 must display Performance Boost overlay / BOOST HUD badge (fn4Diff=$fn4Diff)")

            // 5. focusStickPress (GPIO 7.9 wired-OR active-LOW + idle ADC Ch 9): enters Focus Area screen scr=0x011e
            stepVirtualMs(rt, 2000L)
            val scrBeforeStick = readU32(rt, 0x01CC6424L)
            assertEquals(0x0100, scrBeforeStick, "Expected STILL LiveView scr=0x0100 before focusStickPress")
            tapButtonVirtualMs(rt, "focusStickPress", holdMs = 110L, settleMs = 600L)
            val scrAfterStick = readU32(rt, 0x01CC6424L)
            println("[FRONT-DIAL-E2E] focusStickPress: scr=0x${scrBeforeStick.toString(16)}->0x${scrAfterStick.toString(16)}")
            assertEquals(0x011e, scrAfterStick, "focusStickPress must enter Focus Area selection screen scr=0x011e")
            tapButtonVirtualMs(rt, "menu", holdMs = 110L, settleMs = 450L)
            val scrAfterMenu = readU32(rt, 0x01CC6424L)
            assertTrue(
                scrAfterMenu == 0x0100 || scrAfterMenu == 0x0102,
                "MENU/OK must confirm Focus Area selection back to STILL LiveView (0x0100/0x0102), got 0x${scrAfterMenu.toString(16)}",
            )

            // Strict zero-fallback check
            val relaxed = rt.owner.submit {
                @Suppress("UNCHECKED_CAST")
                rt.session.state()["relaxedFallback"] as Map<String, Any?>
            }.join()
            println("[FRONT-DIAL-E2E] relaxedFallback=$relaxed")
            assertEquals(0L, (relaxed["deviceFallbacks"] as Number).toLong(), "Expected 0 device MMIO fallbacks")
            assertEquals(0L, (relaxed["discoveryFallbacks"] as Number).toLong(), "Expected 0 discovery MMIO fallbacks")
            assertEquals(0L, (relaxed["flashRefusals"] as Number).toLong(), "Expected 0 flash refusals")
        }
    }
}
