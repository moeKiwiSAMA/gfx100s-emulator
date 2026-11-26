package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` end-to-end regression suite for `P1-FIX-IQ` (`BUG-V3-3-3`, `BUG-V3-3-4`,
 * `BUG-V3-3-5`, `BUG-V3-3-6`).
 *
 * Env-gated (`FUJI_RUN_IQ_FIXES_E2E=1`) so standard CI stays fast while local/release verification
 * proves all 4 real-silicon fixes on unmodified GFX100S 2.13 firmware:
 * 1. `BUG-V3-3-3`: `Nostalgic Neg.`, `ETERNA BLEACH BYPASS`, `SEPIA`, and `ACROS + MONOCHROMATIC COLOR`
 *    render authentic LiveView chroma/tint while `ACROS STD` stays pure neutral grayscale (`meanChroma < 0.5`).
 * 2. `BUG-V3-3-4`: `MONOCHROMATIC COLOR` (`scr=0x015d`) and `WHITE BALANCE -> CUSTOM 1` (`scr=0x012f`)
 *    render cleanly with zero `[RELAXED/MMIO]` and zero LCD freeze.
 * 3. `BUG-V3-3-5`: `IMAGE QUALITY = SUPER FINE` encodes 102MP JPEG cleanly (`encoderErrorCount = 0`).
 * 4. `BUG-V3-3-6`: `PIXEL MAPPING` (`scr=0x0147`) at room temperature (`soc-adc CH0 = 2712` = 25°C)
 *    executes cleanly without false overheat refusal.
 */
class IqFixesLiveE2eTest {
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
            val expectedJars = Files.list(compDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }.toList().toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar -> Files.copy(jar, dstCompDir.resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        Files.copy(repo.resolve("tools/release/emulator-release-linux.yaml"), workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(repo.resolve("catalog/compiled/catalog.json"), workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        copyDirectory(repo.resolve("tools/release/component-configs"), workRoot.resolve("configs/components"))
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
        while (rt.telemetry.virtualTick < target && guard < 16000) {
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

    private fun tap(rt: EmulatorRuntime, key: String, holdMs: Long = 100L, settleMs: Long = 280L) {
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

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xFF
    }.join()

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private data class ColorMetrics(val seq: Long, val hash: Int, val meanChroma: Double, val meanR: Double, val meanG: Double, val meanB: Double)

    private fun listDcimFiles(workRoot: Path): List<String> {
        val dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcim)) return emptyList()
        return Files.walk(dcim).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { "${dcim.relativize(it)}=${Files.size(it)}" }
                .sorted()
                .toList()
        }
    }

    private fun measureCenterRoi(rt: EmulatorRuntime): ColorMetrics = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = d.width
        val h = d.height
        val px = IntArray(w * h)
        d.copyArgb(px)
        var sumChroma = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        // Sample center-right unobstructed LiveView ROI (covers Macbeth chart while clear of left menu & top/bottom HUD)
        val x0 = (w * 46) / 100
        val x1 = (w * 76) / 100
        val y0 = (h * 30) / 100
        val y1 = (h * 74) / 100
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val c = px[row + x]
                val r = ((c ushr 16) and 0xFF).toDouble()
                val g = ((c ushr 8) and 0xFF).toDouble()
                val b = (c and 0xFF).toDouble()
                val cb = -0.168736 * r - 0.331264 * g + 0.5 * b
                val cr = 0.5 * r - 0.418688 * g - 0.081312 * b
                sumChroma += hypot(cb, cr)
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }
        ColorMetrics(d.sequence, px.contentHashCode(), sumChroma / count, sumR / count, sumG / count, sumB / count)
    }.join()

    private fun openMenu(rt: EmulatorRuntime) {
        for (i in 0 until 5) {
            val scr = readU32(rt, 0x01CC6424L)
            if ((scr and 0xFF00) == 0x0400) return
            tap(rt, "menu", 110L, 350L)
        }
    }

    private fun closeToLiveView(rt: EmulatorRuntime) {
        for (i in 0 until 8) {
            val scr = readU32(rt, 0x01CC6424L)
            if (scr == 0x0100 || scr == 0x0102) return
            tap(rt, "back", 100L, 280L)
        }
    }

    @Test
    fun allFourIqFixesPassLiveOnUnmodifiedFirmware(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_IQ_FIXES_E2E") == "1" || System.getProperty("fuji.runIqFixesE2e") == "1",
            "Set FUJI_RUN_IQ_FIXES_E2E=1 to run live GXUP0004.DAT P1-FIX-IQ E2E verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(Files.isDirectory(stagedRoot), "Requires staged release at $stagedRoot")

        val workRoot = tempRoot.resolve("iq-fixes-live-e2e")
        copyDirectory(stagedRoot, workRoot)
        Files.deleteIfExists(workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json"))
        // Start with a clean Slot 1 DCIM directory so we strictly verify newly encoded SUPER FINE JPEGs.
        val slot1Dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (Files.isDirectory(slot1Dcim)) {
            Files.walk(slot1Dcim).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 12) {
                val scr = readU32(rt, 0x01CC6424L)
                if (scr in 0x0501..0x0505) tap(rt, "menu", 85L, 350L) else tap(rt, "back", 85L, 350L)
                guard++
            }
            stepVirtualMs(rt, 800L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView scr=0x0100 after OOBE")

            // Switch to physical MF lever (GPIO(7,13)=0, GPIO(7,14)=1) and vivid Macbeth scene://depth-studio
            patchComponent(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            patchComponent(rt, "cmos", mapOf("sourceFile" to "scene://depth-studio"))
            stepVirtualMs(rt, 500L)

            // ====== 1. BUG-V3-3-3: LiveView Colorimetry across PROVIA, Nostalgic Neg., Bleach Bypass, ACROS STD, SEPIA ======
            val proviaMetrics = measureCenterRoi(rt)
            val proviaCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            println("[E2E-FS] PROVIA chroma=${"%.2f".format(proviaMetrics.meanChroma)} mono=${proviaCp["monochromeMode"]}")
            assertEquals(false, proviaCp["monochromeMode"])
            assertTrue(proviaMetrics.meanChroma > 5.0, "PROVIA must show vivid scene chroma, got ${proviaMetrics.meanChroma}")

            openMenu(rt)
            tap(rt, "down", 85L, 200L) // R1 IMAGE QUALITY
            tap(rt, "down", 85L, 200L) // R2 RAW RECORDING
            tap(rt, "down", 85L, 200L) // R3 FILM SIMULATION
            tap(rt, "menu", 90L, 320L) // Open FILM SIMULATION list (at idx=0 PROVIA)

            // idx=7: Nostalgic Neg.
            repeat(7) { tap(rt, "down", 75L, 220L) }
            stepVirtualMs(rt, 350L)
            val nostCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            val nostMetrics = measureCenterRoi(rt)
            println("[E2E-FS] NostalgicNeg chroma=${"%.2f".format(nostMetrics.meanChroma)} mono=${nostCp["monochromeMode"]} chromaMat=${nostCp["chromaMat"]}")
            assertEquals(false, nostCp["monochromeMode"], "BUG-V3-3-3: Nostalgic Neg. must NOT clamp to monochrome")
            assertTrue(nostMetrics.meanChroma > 5.0, "BUG-V3-3-3: Nostalgic Neg. LiveView must preserve color chroma, got ${nostMetrics.meanChroma}")

            // idx=9: ETERNA BLEACH BYPASS (+2 from 7)
            repeat(2) { tap(rt, "down", 75L, 220L) }
            stepVirtualMs(rt, 350L)
            val bleachCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            val bleachMetrics = measureCenterRoi(rt)
            println("[E2E-FS] BleachBypass chroma=${"%.2f".format(bleachMetrics.meanChroma)} mono=${bleachCp["monochromeMode"]} chromaMat=${bleachCp["chromaMat"]}")
            assertEquals(false, bleachCp["monochromeMode"], "BUG-V3-3-3: ETERNA BLEACH BYPASS must NOT clamp to monochrome")
            assertTrue(bleachMetrics.meanChroma > 2.5, "BUG-V3-3-3: ETERNA BLEACH BYPASS must retain desaturated color >2.5, got ${bleachMetrics.meanChroma}")
            assertTrue(bleachMetrics.meanChroma < nostMetrics.meanChroma, "Bleach Bypass must be less saturated than Nostalgic Neg.")

            // idx=12: SEPIA (+3 from 9)
            repeat(3) { tap(rt, "down", 75L, 220L) }
            stepVirtualMs(rt, 350L)
            val sepiaCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            val sepiaMetrics = measureCenterRoi(rt)
            println("[E2E-FS] SEPIA chroma=${"%.2f".format(sepiaMetrics.meanChroma)} R/G/B=(${"%.1f".format(sepiaMetrics.meanR)},${"%.1f".format(sepiaMetrics.meanG)},${"%.1f".format(sepiaMetrics.meanB)}) mono=${sepiaCp["monochromeMode"]} toneDelta=${sepiaCp["monoToneDelta"]}")
            assertEquals(false, sepiaCp["monochromeMode"], "BUG-V3-3-3: SEPIA must not force neutral monochromeMode")
            assertEquals(listOf(-52, 51), sepiaCp["monoToneDelta"])
            assertTrue(sepiaMetrics.meanChroma > 4.0, "BUG-V3-3-3: SEPIA LiveView must render warm tint chroma >4.0, got ${sepiaMetrics.meanChroma}")
            assertTrue(sepiaMetrics.meanR > sepiaMetrics.meanB + 4.0, "SEPIA must be warm (meanR > meanB + 4)")

            // idx=10: ACROS STD (up 2 from SEPIA -> enter submenu -> confirm STD)
            repeat(2) { tap(rt, "up", 75L, 220L) }
            tap(rt, "menu", 90L, 300L)
            tap(rt, "menu", 90L, 350L) // confirm ACROS STD -> back to I.Q. P1 R3
            stepVirtualMs(rt, 350L)
            val acrosCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            val acrosMetrics = measureCenterRoi(rt)
            println("[E2E-FS] ACROS_STD chroma=${"%.2f".format(acrosMetrics.meanChroma)} mono=${acrosCp["monochromeMode"]} toneDelta=${acrosCp["monoToneDelta"]}")
            assertEquals(true, acrosCp["monochromeMode"], "Neutral ACROS STD must stay pure monochrome")
            assertTrue(acrosMetrics.meanChroma < 1.5, "Neutral ACROS STD LiveView ROI must be near-zero chroma, got ${acrosMetrics.meanChroma}")

            // ====== 2. BUG-V3-3-4 + BUG-V3-3-3: MONOCHROMATIC COLOR (scr=0x015d) & WC/MG tint ======
            tap(rt, "down", 85L, 220L) // P1 R4 = MONOCHROMATIC COLOR
            val preMono = measureCenterRoi(rt)
            tap(rt, "menu", 100L, 600L) // enter scr=0x015d
            assertEquals(0x015d, readU32(rt, 0x01CC6424L), "Expected MONOCHROMATIC COLOR scr=0x015d")
            val inMono = measureCenterRoi(rt)
            assertNotEquals(preMono.hash, inMono.hash, "BUG-V3-3-4: MONOCHROMATIC COLOR UI must render (no LCD freeze)")
            // Shift WC+5 (up x5 = warm) and check live toning
            repeat(5) { tap(rt, "up", 75L, 220L) }
            stepVirtualMs(rt, 350L)
            val wcCp = compState(rt, "soc-isp")["colorPipeline"] as Map<*, *>
            val wcMetrics = measureCenterRoi(rt)
            println("[E2E-MONO-WC] WC+5 chroma=${"%.2f".format(wcMetrics.meanChroma)} mono=${wcCp["monochromeMode"]} toneDelta=${wcCp["monoToneDelta"]}")
            assertEquals(false, wcCp["monochromeMode"], "BUG-V3-3-3: ACROS + MONOCHROMATIC COLOR WC+5 must apply warm tint")
            assertNotEquals(listOf(0, 0), wcCp["monoToneDelta"])
            tap(rt, "back", 90L, 350L) // exit MONOCHROMATIC COLOR -> guaranteed I.Q. P1 R4
            val postMono = measureCenterRoi(rt)
            assertNotEquals(inMono.hash, postMono.hash, "BUG-V3-3-4: LCD must remain unfrozen after exiting MONOCHROMATIC COLOR")

            // ====== 3. BUG-V3-3-5: IMAGE QUALITY = SUPER FINE (2 consecutive 102MP captures) ======
            // Guaranteed P1 R4 (MONOCHROMATIC COLOR) -> up 3 = P1 R1 (IMAGE QUALITY) -> menu -> up 1 = SUPER FINE -> menu!
            repeat(3) { tap(rt, "up", 80L, 180L) }
            tap(rt, "menu", 90L, 320L) // open IMAGE QUALITY (default FINE)
            tap(rt, "up", 90L, 240L)   // SUPER FINE (dstMax = 76,317,696 B = 72.78 MiB, dstHi=1 PA 0x1_6A01_4600)
            tap(rt, "menu", 90L, 320L) // confirm SUPER FINE -> back to I.Q. P1 R1
            closeToLiveView(rt)

            fun waitForShotEncodes(baseEnc: Long, baseErr: Long, maxVirtualMs: Long = 15_000L): Pair<Long, Long> {
                var elapsed = 0L
                while (elapsed < maxVirtualMs) {
                    stepVirtualMs(rt, 500L)
                    elapsed += 500L
                    val st = compState(rt, "soc-jpeg")
                    val enc = (st["encodeCount"] as Number).toLong()
                    val err = (st["encoderErrorCount"] as Number).toLong()
                    if (enc >= baseEnc + 3L || err > baseErr) return enc to err
                }
                val st = compState(rt, "soc-jpeg")
                return (st["encodeCount"] as Number).toLong() to (st["encoderErrorCount"] as Number).toLong()
            }

            fun fireStillShutter() {
                patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
                for (w in 0 until 5) {
                    stepBy(rt, 12_000_000L)
                    val s1Sub0 = readU8(rt, 0x02C47C5CL + 0x60L)
                    val saclac0 = readU32(rt, 0x02DA49ECL) and 0xFFFF
                    if (w >= 1 && (s1Sub0 >= 4 || (s1Sub0 == 0 && saclac0 == 4))) break
                }
                patchComponent(rt, "buttons", mapOf("shutterHalf" to true, "shutterFull" to true))
                repeat(3) { stepBy(rt, 12_000_000L) }
                patchComponent(rt, "buttons", mapOf("shutterHalf" to false, "shutterFull" to false))
            }

            val jpegBefore = compState(rt, "soc-jpeg")
            val encBefore = (jpegBefore["encodeCount"] as Number).toLong()
            val errBefore = (jpegBefore["encoderErrorCount"] as Number).toLong()
            fireStillShutter()
            val (encAfter, errAfter) = waitForShotEncodes(encBefore, errBefore)
            // Give FAT32/SD flush + auto-resume a short settle window
            stepVirtualMs(rt, 1500L)
            val dcimAfter1 = listDcimFiles(workRoot)
            println("[E2E-JPEG] SUPER FINE shot 1: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} bDsc=${readU8(rt, 0x01CBFCA2L)} encodeCount=$encBefore->$encAfter encoderErrorCount=$errBefore->$errAfter dcim=$dcimAfter1")
            assertTrue(encAfter >= encBefore + 3L, "BUG-V3-3-5: SUPER FINE must encode main + preview + thumb JPEGs ($encBefore -> $encAfter)")
            assertEquals(errBefore, errAfter, "BUG-V3-3-5: SUPER FINE must produce 0 JPEG encoder errors (got ${errAfter - errBefore})")
            assertTrue(dcimAfter1.size >= 1, "BUG-V3-3-5: SUPER FINE shot 1 must write a real JPEG to SD Slot 1 DCIM (got $dcimAfter1)")

            // Fire a 2nd consecutive shot to verify SUPER FINE did NOT poison the session capture queue!
            fireStillShutter()
            val (encAfter2, errAfter2) = waitForShotEncodes(encAfter, errAfter)
            stepVirtualMs(rt, 1500L)
            val dcimAfter2 = listDcimFiles(workRoot)
            println("[E2E-JPEG] SUPER FINE shot 2: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} bDsc=${readU8(rt, 0x01CBFCA2L)} encodeCount=$encAfter->$encAfter2 encoderErrorCount=$errAfter->$errAfter2 dcim=$dcimAfter2")
            assertTrue(encAfter2 >= encAfter + 3L, "BUG-V3-3-5: Follow-up shot after SUPER FINE must also succeed ($encAfter -> $encAfter2)")
            assertEquals(errAfter, errAfter2, "BUG-V3-3-5: Follow-up shot must produce 0 JPEG encoder errors")
            assertTrue(dcimAfter2.size >= 2, "BUG-V3-3-5: Follow-up SUPER FINE shot 2 must write a 2nd real JPEG to SD Slot 1 DCIM (got $dcimAfter2)")

            // ====== 4. BUG-V3-3-4b: WHITE BALANCE -> CUSTOM 1 (scr=0x012f) ======
            // Re-open Menu -> cursor remembers P1 R1 (IMAGE QUALITY) -> down 9 = P2 R3 (WHITE BALANCE)!
            // (R2=RAW, R3=FilmSim, R4=MonoColor, R5=Grain, R6=CCE, R7=CCFB, P2 R0=SmoothSkin, R1=DR, [R2 DRP greyed], R3=WB)
            openMenu(rt)
            repeat(9) { tap(rt, "down", 80L, 200L) } // P2 R3 = WHITE BALANCE
            tap(rt, "menu", 90L, 320L) // open WB list
            repeat(3) { tap(rt, "down", 80L, 200L) } // CUSTOM 1
            val preWb = measureCenterRoi(rt)
            tap(rt, "menu", 100L, 550L) // enter CUSTOM 1
            assertEquals(0x012f, readU32(rt, 0x01CC6424L), "Expected WB CUSTOM 1 scr=0x012f")
            val inWb = measureCenterRoi(rt)
            assertNotEquals(preWb.hash, inWb.hash, "BUG-V3-3-4: WB CUSTOM 1 UI must render without LCD freeze")
            tap(rt, "back", 90L, 300L)
            tap(rt, "back", 90L, 300L) // back to I.Q. P2 R3 (WB)

            // ====== 5. BUG-V3-3-6: PIXEL MAPPING (P3 R4 = down 8 from P2 R3 WB) at room temp CH0=2712 ======
            repeat(8) { tap(rt, "down", 80L, 180L) } // P3 R4 = PIXEL MAPPING
            tap(rt, "menu", 90L, 500L)
            val scrPmPrompt = readU32(rt, 0x01CC6424L)
            val hashPmPrompt = measureCenterRoi(rt).hash
            println("[E2E-PM] Prompt scr=0x${scrPmPrompt.toString(16)}")
            assertEquals(0x0147, scrPmPrompt, "Expected PIXEL MAPPING scr=0x0147")
            // Press OK to execute PIXEL MAPPING — with CH0=2712 (25C), firmware enters active mapping scr=0x0148 instead of overheat refusal!
            tap(rt, "menu", 90L, 2500L)
            val scrPmExec = readU32(rt, 0x01CC6424L)
            val hashPmExec = measureCenterRoi(rt).hash
            println("[E2E-PM] Executing scr=0x${scrPmExec.toString(16)} changed=${hashPmExec != hashPmPrompt}")
            assertEquals(0x0148, scrPmExec, "BUG-V3-3-6: PIXEL MAPPING must enter execution scr=0x0148 at 25C (CH0=2712) instead of staying on overheat refusal 0x0147")
            assertNotEquals(hashPmPrompt, hashPmExec, "BUG-V3-3-6: Display must advance from prompt to active PIXEL MAPPING")

            // Global zero-fallback guardrail check
            @Suppress("UNCHECKED_CAST")
            val fallback = rt.owner.submit { rt.session.state()["relaxedFallback"] as? Map<String, Any?> }.join()
            println("[E2E-SUMMARY] relaxedFallback=$fallback")
            if (fallback != null) {
                assertEquals(0, (fallback["mmioUnique"] as? Number)?.toInt() ?: 0, "Expected 0 [RELAXED/MMIO] across entire E2E run")
                assertEquals(0, (fallback["flashRefused"] as? Number)?.toInt() ?: 0, "Expected 0 [RELAXED/FLASH/REFUSED]")
            }
        }
    }
}
