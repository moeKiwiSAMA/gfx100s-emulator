package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.PanelBacklightPort
import com.github.nyabanban.fuji.emulator.api.PanelBacklightState
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live unmodified stock GFX100S 2.13 (`nor-flash-2.13.bin` + Dynarmic JIT) regression test for
 * `SET UP -> POWER MANAGEMENT -> PERFORMANCE -> BOOST` (`增强`) mode across all 4 BOOST sub-modes:
 * - `EVF FRAME RATE PRIORITY` (`MDSEL=0xAF`, 85.766 fps EVF)
 * - `EVF RESOLUTION PRIORITY` (`MDSEL=0xB3`)
 * - `AF PRIORITY - LOW LIGHT` (`COMPANION_BB000` 4-input weighted YUV combiner -> `PIC1` ring
 *   `0x3e552000/0x3e7aa000/0x3ea02000` on both LCD and EVF + half-press S1)
 * - `AF PRIORITY - NORMAL`
 *
 * Verifies >= 10 consecutive display frames per sub-mode/display with ZERO black frames, ZERO
 * black flickering, live non-black CMOS LiveView + HUD pixels on every frame, active panel
 * backlight gain (`> 0.25`), and converged AE (no white blowout in 85.766 fps EVF mode).
 *
 * Opt-in via `FUJI_RUN_BOOST_LIVE=1` (optional PNG dump via `FUJI_BOOST_DUMP_DIR`).
 */
class BoostPerformanceModeLiveTest {
    private data class SampleSummary(
        val frames: Int,
        val blackFrames: Int,
        val lowLumaFrames: Int,
        val minLuma: Double,
        val maxLuma: Double,
        val minNonBlackPct: Double,
        val maxBrightPct: Double,
        val minBacklightGain: Float,
    )

    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target)
                }
            }
        }
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, b)
        b.getInt(0)
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(1)
        mem.read(addr, b)
        b.get(0).toInt() and 0xFF
    }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long) {
        rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            while (rt.session.checkpointAttempts() < target) {
                if (!rt.session.stepFast()) break
            }
        }.join()
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target) {
            val ok = rt.owner.submit {
                var i = 0
                var running = true
                while (i < 512 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) {
                        running = false
                        break
                    }
                    i++
                }
                running
            }.join()
            if (!ok) break
        }
    }

    private fun patchBtn(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to down), persistToDisk = false) }.join()
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 5_000_000L, settle: Long = 45_000_000L) {
        patchBtn(rt, key, true)
        stepBy(rt, hold)
        patchBtn(rt, key, false)
        stepBy(rt, settle)
    }

    private fun tapCalibrated(rt: EmulatorRuntime, key: String, settleTicks: Long = 45_000_000L) {
        patchBtn(rt, key, true)
        stepVirtualTicks(rt, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        patchBtn(rt, key, false)
        stepVirtualTicks(rt, settleTicks)
    }

    private fun capturePort(rt: EmulatorRuntime, portName: String): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
        val px = IntArray(dp.width * dp.height)
        dp.copyArgb(px)
        Triple(dp.sequence, dp.width to dp.height, px)
    }.join()

    private fun readLcdcWord(rt: EmulatorRuntime, offset: Long): Long = rt.owner.submit {
        requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)).read(offset, 4)
    }.join()

    private fun backlightState(rt: EmulatorRuntime): PanelBacklightState = rt.owner.submit {
        requireNotNull(rt.host.ports.find(PanelBacklightPort.PORT_NAME, PanelBacklightPort::class.java)).backlightState()
    }.join()

    private fun exposureSummary(rt: EmulatorRuntime): String = rt.owner.submit {
        val exp = rt.host.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java)
            ?: return@submit "exp=null"
        val eff = Imx461ExposurePort.computeEffectiveExposure(exp)
        val st = exp.effectiveExposureState
        "mdsel=0x${exp.mdsel8.toString(16)} raw(shr=${exp.shr15},svr=${exp.svr12},vmax=${exp.vmax12},pgc=${exp.pgc12}) " +
            "eff(shr=${st.shr15},pgc=${st.pgc12}) out(dur=${eff.durationNanos},iso=${eff.iso})"
    }.join()

    private fun centerMetrics(dims: Pair<Int, Int>, px: IntArray): Triple<Double, Double, Double> {
        val w = dims.first
        val h = dims.second
        var sum = 0.0
        var nonBlack = 0
        var bright = 0
        for (y in h / 6 until (h * 5) / 6) {
            for (x in w / 6 until (w * 5) / 6) {
                val argb = px[y * w + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val yLuma = 0.299 * r + 0.587 * g + 0.114 * b
                sum += yLuma
                if (yLuma > 8.0) nonBlack++
                if (yLuma > 240.0) bright++
            }
        }
        val n = ((h * 5) / 6 - h / 6) * ((w * 5) / 6 - w / 6)
        return Triple(sum / n, 100.0 * nonBlack / n, 100.0 * bright / n)
    }

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    private fun sampleConsecutiveFrames(
        rt: EmulatorRuntime,
        tag: String,
        portName: String,
        isEvf: Boolean,
        outDir: Path?,
        steps: Int = 70,
        stepTicks: Long = 350_000L,
    ): SampleSummary {
        var lastSeq = -1L
        var totalFrames = 0
        var blackFrames = 0
        var lowLumaFrames = 0
        var minLuma = Double.MAX_VALUE
        var maxLuma = -1.0
        var minNonBlack = 100.0
        var maxBright = 0.0
        var minGain = Float.MAX_VALUE
        val seenPic1 = linkedSetOf<Long>()
        var lastFrameDim: Pair<Int, Int>? = null
        var lastFramePx: IntArray? = null

        for (i in 0 until steps) {
            stepVirtualTicks(rt, stepTicks)
            val (seq, dim, px) = capturePort(rt, portName)
            if (seq == lastSeq) continue
            lastSeq = seq
            totalFrames++
            lastFrameDim = dim
            lastFramePx = px
            val (luma, nonBlk, bright) = centerMetrics(dim, px)
            val bl = backlightState(rt)
            val activeGain = if (isEvf) bl.evfGain else bl.lcdGain
            if (luma < minLuma) minLuma = luma
            if (luma > maxLuma) maxLuma = luma
            if (nonBlk < minNonBlack) minNonBlack = nonBlk
            if (bright > maxBright) maxBright = bright
            if (activeGain < minGain) minGain = activeGain
            val r168 = readLcdcWord(rt, 0x168)
            seenPic1 += r168
            val isBlack = luma < 5.0 || nonBlk < 5.0
            val isLow = luma < 40.0
            if (isBlack) blackFrames++
            if (isLow) lowLumaFrames++
            if (isBlack || isLow || totalFrames == 1) {
                println(
                    "[BOOST][$tag] f#$totalFrames seq=$seq luma=${"%.1f".format(luma)} nonBlk=${"%.1f".format(nonBlk)}% " +
                        "bright=${"%.1f".format(bright)}% pic1=0x${r168.toString(16)} bl(lcd=${bl.lcdGain},evf=${bl.evfGain}) ${exposureSummary(rt)}",
                )
            }
        }
        if (lastFrameDim != null && lastFramePx != null) {
            dumpPng(outDir, "last_${tag}.png", lastFrameDim, lastFramePx)
        }
        println(
            "[BOOST][$tag] SUMMARY frames=$totalFrames black=$blackFrames lowLuma=$lowLumaFrames " +
                "luma=[${"%.1f".format(minLuma)}..${"%.1f".format(maxLuma)}] minNonBlk=${"%.1f".format(minNonBlack)}% " +
                "maxBright=${"%.1f".format(maxBright)}% minGain=$minGain pic1=${seenPic1.map { "0x" + it.toString(16) }}",
        )
        return SampleSummary(totalFrames, blackFrames, lowLumaFrames, minLuma, maxLuma, minNonBlack, maxBright, minGain)
    }

    private fun backToLiveView(rt: EmulatorRuntime) {
        var tries = 0
        while (readU32(rt, 0x01CC6424L) != 0x0100 && tries < 6) {
            tapCalibrated(rt, "back", 55_000_000L)
            tries++
        }
    }

    @Test
    fun boostPerformanceModeRendersZeroBlackFramesAcrossAllFourSubModesOnLcdAndEvf(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_BOOST_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("boost-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_BOOST_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 550_000_000L)
            for (i in 0 until 25) {
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
                stepBy(rt, 30_000_000L)
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE language screen 0x0421")
            tapInstr(rt, "menu")
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "menu")
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "right", 4_000_000L, 12_000_000L)
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "menu")
            tapInstr(rt, "menu")
            for (i in 0 until 28) {
                if (readU32(rt, 0x01CC6424L) == 0x0100 && readU8(rt, 0x01CBFCA2L) == 0) break
                stepBy(rt, 8_000_000L)
            }
            stepVirtualTicks(rt, 120_000_000L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView 0x0100")

            // Navigate: MENU -> SET UP -> POWER MANAGEMENT -> PERFORMANCE -> BOOST -> EVF SETTING IN BOOST MODE
            tapCalibrated(rt, "menu", 55_000_000L)
            tapCalibrated(rt, "left", 40_000_000L)
            repeat(4) { tapCalibrated(rt, "down", 38_000_000L) }
            tapCalibrated(rt, "right", 40_000_000L)
            repeat(4) { tapCalibrated(rt, "down", 38_000_000L) }
            tapCalibrated(rt, "menu", 55_000_000L) // POWER MANAGEMENT
            tapCalibrated(rt, "down", 40_000_000L) // PERFORMANCE
            tapCalibrated(rt, "menu", 55_000_000L) // PERFORMANCE (NORMAL / BOOST)
            tapCalibrated(rt, "up", 40_000_000L) // BOOST
            tapCalibrated(rt, "menu", 55_000_000L) // EVF SETTING IN BOOST MODE (default row 3)

            val subModes = listOf(
                "evf_fps_prio" to 0,
                "evf_res_prio" to 1,
                "af_lowlight" to 1,
                "af_normal" to 1,
            )
            for ((idx, entry) in subModes.withIndex()) {
                val (name, upSteps) = entry
                if (idx > 0) {
                    tapCalibrated(rt, "menu", 55_000_000L) // POWER MANAGEMENT (PERFORMANCE remembered)
                    tapCalibrated(rt, "menu", 55_000_000L) // PERFORMANCE (BOOST remembered)
                    tapCalibrated(rt, "menu", 55_000_000L) // EVF SETTING IN BOOST MODE
                }
                repeat(upSteps) { tapCalibrated(rt, "up", 40_000_000L) }
                tapCalibrated(rt, "menu", 55_000_000L) // Confirm sub-mode
                backToLiveView(rt)
                assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected LiveView after enabling BOOST/$name")

                // 1. Rear LCD LiveView (>= 10 consecutive frames)
                val lcd = sampleConsecutiveFrames(rt, "${name}_lcd", "display.main", isEvf = false, outDir = outDir, steps = 70)
                assertTrue(lcd.frames >= 10, "BOOST/$name LCD sampled too few frames: ${lcd.frames}")
                assertEquals(0, lcd.blackFrames, "BOOST/$name LCD rendered black frames")
                assertEquals(0, lcd.lowLumaFrames, "BOOST/$name LCD rendered low-luma flicker frames (minLuma=${lcd.minLuma})")
                assertTrue(lcd.minNonBlackPct >= 80.0, "BOOST/$name LCD non-black pixel ratio too low: ${lcd.minNonBlackPct}%")
                assertTrue(lcd.minBacklightGain > 0.25f, "BOOST/$name LCD backlight gain collapsed: ${lcd.minBacklightGain}")

                // 2. Half-press shutter S1 on Rear LCD (>= 10 consecutive frames)
                patchBtn(rt, "shutterHalf", true)
                val lcdS1 = sampleConsecutiveFrames(rt, "${name}_lcd_s1", "display.main", isEvf = false, outDir = outDir, steps = 40)
                patchBtn(rt, "shutterHalf", false)
                stepVirtualTicks(rt, 20_000_000L)
                assertTrue(lcdS1.frames >= 10, "BOOST/$name LCD S1 sampled too few frames: ${lcdS1.frames}")
                assertEquals(0, lcdS1.blackFrames, "BOOST/$name LCD S1 rendered black flicker frames")
                assertEquals(0, lcdS1.lowLumaFrames, "BOOST/$name LCD S1 rendered low-luma flicker frames (minLuma=${lcdS1.minLuma})")
                assertTrue(lcdS1.minNonBlackPct >= 80.0, "BOOST/$name LCD S1 non-black pixel ratio too low: ${lcdS1.minNonBlackPct}%")

                // 3. EVF LiveView (>= 10 consecutive frames)
                rt.owner.submit { rt.host.patchLatestDirect("tmd2772", mapOf("eyeSensorNear" to true), persistToDisk = false) }.join()
                rt.owner.submit { rt.host.patchLatestDirect("i2c-bus", mapOf("eyeSensorNear" to true), persistToDisk = false) }.join()
                stepVirtualTicks(rt, 45_000_000L)
                val evf = sampleConsecutiveFrames(rt, "${name}_evf", "display.evf", isEvf = true, outDir = outDir, steps = 70)
                assertTrue(evf.frames >= 10, "BOOST/$name EVF sampled too few frames: ${evf.frames}")
                assertEquals(0, evf.blackFrames, "BOOST/$name EVF rendered black frames")
                assertEquals(0, evf.lowLumaFrames, "BOOST/$name EVF rendered low-luma flicker frames (minLuma=${evf.minLuma})")
                assertTrue(evf.minNonBlackPct >= 80.0, "BOOST/$name EVF non-black pixel ratio too low: ${evf.minNonBlackPct}%")
                assertTrue(evf.minBacklightGain > 0.25f, "BOOST/$name EVF backlight gain collapsed: ${evf.minBacklightGain}")
                if (name == "evf_fps_prio") {
                    assertTrue(evf.maxBrightPct < 75.0, "BOOST/evf_fps_prio EVF MDSEL=0xAF AE blew out white: maxBright=${evf.maxBrightPct}%")
                }

                // Return to Rear LCD
                rt.owner.submit { rt.host.patchLatestDirect("tmd2772", mapOf("eyeSensorNear" to false), persistToDisk = false) }.join()
                rt.owner.submit { rt.host.patchLatestDirect("i2c-bus", mapOf("eyeSensorNear" to false), persistToDisk = false) }.join()
                stepVirtualTicks(rt, 45_000_000L)
            }
        }
    }
}
