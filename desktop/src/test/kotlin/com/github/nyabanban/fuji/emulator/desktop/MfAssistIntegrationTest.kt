package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` E2E verification for hardware MF Assist (`BUG-A3-5`):
 * - Hardware ISP Focus Peak Highlight (`0xFFF68900..0xFFF68950`): `WHITE`, `RED` (`LOW` vs `HIGH`),
 *   `BLUE`, and `YELLOW` edge highlights visibly rendered on LiveView.
 * - Hardware Digital Split Image (`COLOR` & `MONOCHROME`) and Digital Microprism (`MOVDC` `0xFFF7D000`
 *   IRQ `110` -> `ImageScalerF000` `0xFFF7F000` IRQ `133` -> `COMPANION_BB000` `0xFFFBB000` IRQ `134`)
 *   visibly compositing phase-shifted strips/tiles into LiveView with `0` `[RELAXED/MMIO]`.
 */
class MfAssistIntegrationTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, componentId: String, patch: Map<String, Any>) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == componentId }.revision }.join()
        rt.host.patch(componentId, rev, patch).join()
    }

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 350_000) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var calls = 0
        while (rt.telemetry.virtualTick < target && calls < maxCalls) {
            val cont = rt.owner.submit {
                var ok = true
                var batch = 0
                while (ok && batch < 64 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    ok = rt.session.stepFast()
                    batch++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                ok
            }.join()
            if (!cont || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long = 5_000_000L, settleTicks: Long = 25_000_000L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Int, Int, IntArray> = rt.owner.submit {
        val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = port.width
        val h = port.height
        val px = if (w > 0 && h > 0) IntArray(w * h).also { port.copyArgb(it) } else IntArray(0)
        Triple(w, h, px)
    }.join()

    private fun savePngIfRequested(dir: Path?, name: String, frame: Triple<Int, Int, IntArray>) {
        if (dir == null) return
        val (w, h, px) = frame
        if (w <= 0 || h <= 0) return
        Files.createDirectories(dir)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        ImageIO.write(img, "PNG", dir.resolve(name).toFile())
    }

    private data class IspMfSnapshot(
        val word90c: Long,
        val word914: Long,
        val word950: Long,
        val movdcCompletions: Long,
        val bb000Completions: Long,
    )

    private fun readIspMfState(rt: EmulatorRuntime): IspMfSnapshot {
        val snap = rt.owner.submit {
            val isp = rt.host.components().single { it.descriptor.id == "soc-isp" }.component as StatefulComponent
            isp.saveSnapshot()
        }.join()
        val b68 = snap["bank68000Words"] as List<*>
        val offsets68 = (
            (0x000..0x010 step 4) + (0x108..0x118 step 4) + (0x200..0x26c step 4) +
                (0x300..0x314 step 4) + (0x400..0x4d0 step 4) + (0x500..0x518 step 4) +
                (0x534..0x568 step 4) + (0x600..0x66c step 4) + (0x700..0x720 step 4) +
                (0x800..0x858 step 4) + (0x900..0x950 step 4) + (0xa00..0xaec step 4) +
                (0xb00..0xb2c step 4) + (0xc00..0xca0 step 4) + listOf(0xd00)
            ).sorted()
        fun r68(off: Int): Long = (b68[offsets68.indexOf(off)] as Number).toLong()
        val ce = snap["companionEngines"] as? Map<*, *>
        val movdc = ((ce?.get("movdc") as? Map<*, *>)?.get("completions") as? Number)?.toLong() ?: 0L
        val bb000 = ((ce?.get("cbb000") as? Map<*, *>)?.get("completions") as? Number)?.toLong() ?: 0L
        return IspMfSnapshot(
            word90c = r68(0x90c),
            word914 = r68(0x914),
            word950 = r68(0x950),
            movdcCompletions = movdc,
            bb000Completions = bb000,
        )
    }

    /** Counts matching pixels inside the clean central LiveView image area (avoiding OSD HUD bars). */
    private fun countLiveViewPixels(
        frame: Triple<Int, Int, IntArray>,
        x0: Int = 80,
        y0: Int = 140,
        x1: Int = 920,
        y1: Int = 620,
        predicate: (r: Int, g: Int, b: Int) -> Boolean,
    ): Int {
        val (w, h, px) = frame
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
            val rowOff = y * w
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                val argb = px[rowOff + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (predicate(r, g, b)) n++
            }
        }
        return n
    }

    private fun countDiffPixels(
        a: Triple<Int, Int, IntArray>,
        b: Triple<Int, Int, IntArray>,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        minChannelDiff: Int = 20,
    ): Int {
        val (w, h, pa) = a
        val (_, _, pb) = b
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
            val rowOff = y * w
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                val ca = pa[rowOff + x]
                val cb = pb[rowOff + x]
                val dr = abs(((ca ushr 16) and 0xFF) - ((cb ushr 16) and 0xFF))
                val dg = abs(((ca ushr 8) and 0xFF) - ((cb ushr 8) and 0xFF))
                val db = abs((ca and 0xFF) - (cb and 0xFF))
                if (dr >= minChannelDiff || dg >= minChannelDiff || db >= minChannelDiff) n++
            }
        }
        return n
    }

    @Test
    fun mfAssistFocusPeakingAndSplitImageMicroprismRenderOnLiveView(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
        )
        val dumpDir = (System.getProperty("fuji.mfAssistDumpDir") ?: System.getenv("FUJI_MF_ASSIST_DUMP_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
        val workRoot = tempRoot.resolve("release-mf-assist")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 620_000_000L)
            // Dismiss first-boot language/date/time wizard cleanly to LiveView
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 60_000_000L)
            stepBy(rt, 80_000_000L)

            fun isLiveViewVisible(frame: Triple<Int, Int, IntArray>): Boolean {
                // Bottom-left white MF badge box (x=68..120, y=640..688) is pure white in LiveView
                // and dark gray menu chrome whenever any menu or submenu overlay is still open.
                val mfBadgeWhite = countLiveViewPixels(frame, 68, 640, 120, 688) { r, g, b ->
                    r > 220 && g > 220 && b > 220
                }
                return mfBadgeWhite > 800
            }

            fun exitMenuToLiveView() {
                for (attempt in 0 until 3) {
                    tapButton(rt, "shutterHalf", 5_000_000L, 55_000_000L)
                    stepBy(rt, 55_000_000L)
                    if (isLiveViewVisible(captureMain(rt))) return
                    // If a deep submenu was still open or animating, confirm/close then retry half-press.
                    tapButton(rt, "menu", 5_000_000L, 55_000_000L)
                }
            }

            // Capture STANDARD baseline in MF mode first (focusMode lever Bank 7 = 0x4040)
            // Navigate to AF/MF SETTING -> MF ASSIST while in AF-S (all AF rows enabled -> 10 downs)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "left", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "right", 5_000_000L, 25_000_000L)
            repeat(10) { tapButton(rt, "down", 4_000_000L, 16_000_000L) }
            // Confirm STANDARD (row 0), then switch to MF lever
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            patchComponent(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            patchComponent(rt, "buttons", mapOf("focusMode" to true))
            exitMenuToLiveView()

            val stdState = readIspMfState(rt)
            val stdFrame = captureMain(rt)
            savePngIfRequested(dumpDir, "01-mf-standard.png", stdFrame)
            assertEquals(0x1FFL, stdState.word950 and 0x1FFL, "STANDARD mode must keep Focus Peaking OFF (0x1FF)")

            val baseWhite = countLiveViewPixels(stdFrame) { r, g, b -> r > 235 && g > 235 && b > 235 }
            val baseRed = countLiveViewPixels(stdFrame) { r, g, b -> r > 200 && (r - g) > 120 && (r - b) > 120 }
            val baseBlue = countLiveViewPixels(stdFrame) { r, g, b -> b > 200 && g > 180 && r < 60 }
            val baseYellow = countLiveViewPixels(stdFrame) { r, g, b -> r > 200 && g > 200 && b < 60 }

            // 1. DIGITAL SPLIT IMAGE -> COLOR (menu remembers MF ASSIST; down 1 -> right -> menu selects COLOR)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            exitMenuToLiveView()

            val splitColorState = readIspMfState(rt)
            val splitColorFrame = captureMain(rt)
            savePngIfRequested(dumpDir, "02-mf-split-color.png", splitColorFrame)
            val splitColorDiff = countDiffPixels(stdFrame, splitColorFrame, 280, 150, 740, 610)
            val splitColorChroma = countLiveViewPixels(splitColorFrame, 280, 150, 740, 610) { r, g, b ->
                maxOf(abs(r - g), abs(g - b), abs(r - b)) > 35
            }
            println("[MF-E2E] SPLIT_COLOR: movdc=${splitColorState.movdcCompletions} bb000=${splitColorState.bb000Completions} roiDiff=$splitColorDiff chromaPx=$splitColorChroma")
            assertTrue(splitColorState.movdcCompletions > stdState.movdcCompletions, "DIGITAL SPLIT IMAGE (COLOR) must execute MOVDC")
            assertTrue(splitColorState.bb000Completions > stdState.bb000Completions, "DIGITAL SPLIT IMAGE (COLOR) must execute COMPANION_BB000")
            assertTrue(splitColorDiff > 3_000, "DIGITAL SPLIT IMAGE (COLOR) must visibly split center ROI vs STANDARD (diff=$splitColorDiff)")
            assertTrue(splitColorChroma > 500, "DIGITAL SPLIT IMAGE (COLOR) must preserve scene color saturation inside ROI (chromaPx=$splitColorChroma)")

            // 2. DIGITAL SPLIT IMAGE -> MONOCHROME (up 1 -> menu)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "up", 4_000_000L, 20_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            exitMenuToLiveView()

            val splitMonoState = readIspMfState(rt)
            val splitMonoFrame = captureMain(rt)
            savePngIfRequested(dumpDir, "03-mf-split-mono.png", splitMonoFrame)
            val splitMonoDiff = countDiffPixels(stdFrame, splitMonoFrame, 280, 150, 740, 610)
            val splitMonoChroma = countLiveViewPixels(splitMonoFrame, 280, 150, 740, 610) { r, g, b ->
                maxOf(abs(r - g), abs(g - b), abs(r - b)) > 35
            }
            println("[MF-E2E] SPLIT_MONO: movdc=${splitMonoState.movdcCompletions} bb000=${splitMonoState.bb000Completions} roiDiff=$splitMonoDiff chromaPx=$splitMonoChroma")
            assertTrue(splitMonoState.movdcCompletions > splitColorState.movdcCompletions, "DIGITAL SPLIT IMAGE (MONO) must execute MOVDC")
            assertTrue(splitMonoState.bb000Completions > splitColorState.bb000Completions, "DIGITAL SPLIT IMAGE (MONO) must execute COMPANION_BB000")
            assertTrue(splitMonoDiff > 3_000, "DIGITAL SPLIT IMAGE (MONO) must visibly render split ROI vs STANDARD (diff=$splitMonoDiff)")
            assertTrue(splitMonoChroma < splitColorChroma / 4, "DIGITAL SPLIT IMAGE (MONO) must desaturate split box (mono=$splitMonoChroma vs color=$splitColorChroma)")

            // 3. DIGITAL MICROPRISM (down 1 from DIGITAL SPLIT IMAGE -> menu)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            exitMenuToLiveView()

            val prismState = readIspMfState(rt)
            val prismFrame = captureMain(rt)
            savePngIfRequested(dumpDir, "04-mf-microprism.png", prismFrame)
            val prismDiff = countDiffPixels(stdFrame, prismFrame, 320, 180, 700, 580)
            println("[MF-E2E] MICROPRISM: movdc=${prismState.movdcCompletions} bb000=${prismState.bb000Completions} centerDiff=$prismDiff")
            assertTrue(prismState.movdcCompletions > splitMonoState.movdcCompletions, "DIGITAL MICROPRISM must execute MOVDC")
            assertTrue(prismState.bb000Completions > splitMonoState.bb000Completions, "DIGITAL MICROPRISM must execute COMPANION_BB000")
            assertTrue(prismDiff > 3_000, "DIGITAL MICROPRISM must visibly render circular checkerboard disparity (diff=$prismDiff)")

            // 4. FOCUS PEAK HIGHLIGHT -> WHITE(HIGH) (down 1 -> right -> menu)
            // Note: opening the 8-row FOCUS PEAK HIGHLIGHT submenu while tearing down MOVDC/BB000
            // takes slightly longer to render than 2-row submenus, so allow generous settle time.
            tapButton(rt, "menu", 5_000_000L, 55_000_000L)
            tapButton(rt, "right", 5_000_000L, 65_000_000L)
            tapButton(rt, "down", 4_000_000L, 25_000_000L)
            tapButton(rt, "right", 5_000_000L, 90_000_000L)
            tapButton(rt, "menu", 5_000_000L, 65_000_000L)
            exitMenuToLiveView()

            val peakWhiteState = readIspMfState(rt)
            val peakWhiteFrame = captureMain(rt)
            savePngIfRequested(dumpDir, "05-mf-peak-white-high.png", peakWhiteFrame)
            val peakWhitePx = countLiveViewPixels(peakWhiteFrame) { r, g, b -> r > 235 && g > 235 && b > 235 }
            println("[MF-E2E] PEAK_WHITE_HIGH: 68950=0x${peakWhiteState.word950.toString(16)} whitePx=$peakWhitePx (base=$baseWhite)")
            assertEquals(0x8080_0064L, peakWhiteState.word950)
            assertTrue(peakWhitePx > baseWhite + 5_000, "WHITE(HIGH) peaking must highlight bright white focus edges (got $peakWhitePx vs base $baseWhite)")

            fun stepPeakingOption(stepsDown: Int): Pair<IspMfSnapshot, Triple<Int, Int, IntArray>> {
                tapButton(rt, "menu", 5_000_000L, 55_000_000L)
                tapButton(rt, "right", 5_000_000L, 65_000_000L)
                tapButton(rt, "right", 5_000_000L, 90_000_000L)
                repeat(stepsDown) { tapButton(rt, "down", 4_000_000L, 22_000_000L) }
                tapButton(rt, "menu", 5_000_000L, 65_000_000L)
                exitMenuToLiveView()
                return readIspMfState(rt) to captureMain(rt)
            }

            // 5. RED(LOW) (1 down from WHITE(HIGH))
            val (peakRedLowState, peakRedLowFrame) = stepPeakingOption(1)
            savePngIfRequested(dumpDir, "06-mf-peak-red-low.png", peakRedLowFrame)
            val peakRedLowPx = countLiveViewPixels(peakRedLowFrame) { r, g, b -> r > 200 && (r - g) > 120 && (r - b) > 120 }
            println("[MF-E2E] PEAK_RED_LOW: 6890c=0x${peakRedLowState.word90c.toString(16)} 68914=0x${peakRedLowState.word914.toString(16)} 68950=0x${peakRedLowState.word950.toString(16)} redPx=$peakRedLowPx (base=$baseRed)")
            assertEquals(0xFF55_0064L, peakRedLowState.word950)
            assertTrue(peakRedLowPx > baseRed + 2_000, "RED(LOW) peaking must highlight crisp red edges (got $peakRedLowPx vs base $baseRed)")

            // 6. RED(HIGH) (1 down from RED(LOW))
            val (peakRedHighState, peakRedHighFrame) = stepPeakingOption(1)
            savePngIfRequested(dumpDir, "07-mf-peak-red-high.png", peakRedHighFrame)
            val peakRedHighPx = countLiveViewPixels(peakRedHighFrame) { r, g, b -> r > 200 && (r - g) > 120 && (r - b) > 120 }
            println("[MF-E2E] PEAK_RED_HIGH: 6890c=0x${peakRedHighState.word90c.toString(16)} 68914=0x${peakRedHighState.word914.toString(16)} 68950=0x${peakRedHighState.word950.toString(16)} redPx=$peakRedHighPx")
            assertEquals(0xFF55_0064L, peakRedHighState.word950)
            assertTrue(peakRedHighPx > peakRedLowPx, "RED(HIGH) ($peakRedHighPx) must highlight more edges than RED(LOW) ($peakRedLowPx)")

            // 7. BLUE(HIGH) (2 downs from RED(HIGH))
            val (peakBlueState, peakBlueFrame) = stepPeakingOption(2)
            savePngIfRequested(dumpDir, "08-mf-peak-blue-high.png", peakBlueFrame)
            val peakBluePx = countLiveViewPixels(peakBlueFrame) { r, g, b -> b > 200 && g > 180 && r < 60 }
            println("[MF-E2E] PEAK_BLUE_HIGH: 68950=0x${peakBlueState.word950.toString(16)} bluePx=$peakBluePx (base=$baseBlue)")
            assertEquals(0x00AB_0064L, peakBlueState.word950)
            assertTrue(peakBluePx > baseBlue + 5_000, "BLUE(HIGH) peaking must highlight vivid blue edges (got $peakBluePx vs base $baseBlue)")

            // 8. YELLOW(HIGH) (2 downs from BLUE(HIGH))
            val (peakYellowState, peakYellowFrame) = stepPeakingOption(2)
            savePngIfRequested(dumpDir, "09-mf-peak-yellow-high.png", peakYellowFrame)
            val peakYellowPx = countLiveViewPixels(peakYellowFrame) { r, g, b -> r > 200 && g > 200 && b < 60 }
            println("[MF-E2E] PEAK_YELLOW_HIGH: 68950=0x${peakYellowState.word950.toString(16)} yellowPx=$peakYellowPx (base=$baseYellow)")
            assertEquals(0x9500_0064L, peakYellowState.word950)
            assertTrue(peakYellowPx > baseYellow + 5_000, "YELLOW(HIGH) peaking must highlight vivid yellow edges (got $peakYellowPx vs base $baseYellow)")

            // Zero RELAXED/MMIO fallbacks and zero refused flash ops across the entire MF Assist sweep!
            val summary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[MF-E2E] SUMMARY: $summary")
            assertTrue(summary.isEmpty() || summary.contains("mmioUnique=0"), "Expected 0 RELAXED/MMIO fallbacks, got: $summary")
            assertTrue(summary.isEmpty() || summary.contains("refused=0"), "Expected 0 refused flash ops, got: $summary")
        }
    }
}
