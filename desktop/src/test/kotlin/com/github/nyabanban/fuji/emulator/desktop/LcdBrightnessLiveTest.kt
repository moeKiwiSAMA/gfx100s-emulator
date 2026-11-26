package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` hardware-fidelity verification for `BUG-A3-6`:
 * - Path A: SoC `LCDC1` 3×3 13-bit signed Q10 RGB matrix (`+0x4E8..+0x4FC`) driven by `LCD COLOR`.
 * - Path B: External `SPI5` (`0xFFF37000`) Rear LCD (`0xA3[0x05/0x25]` + `WRDISBV 0x51`) and
 *   EVF OLED (`0x13` luminance + `0x2C` emission) brightness registers driven by `LCD BRIGHTNESS`
 *   and `EVF BRIGHTNESS` (`-5 / 0 / +5`).
 *
 * Opt-in via `FUJI_RUN_BRIGHTNESS_LIVE=1` (optional PNG dump via `FUJI_BRIGHTNESS_DUMP_DIR`).
 */
class LcdBrightnessLiveTest {
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
                    if (!rt.session.stepFast()) { running = false; break }
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

    private fun tapCalibrated(rt: EmulatorRuntime, key: String, settleTicks: Long = 55_000_000L) {
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

    private fun backlightState(rt: EmulatorRuntime): PanelBacklightState = rt.owner.submit {
        requireNotNull(rt.host.ports.find(PanelBacklightPort.PORT_NAME, PanelBacklightPort::class.java)).backlightState()
    }.join()

    private fun readLcdcWord(rt: EmulatorRuntime, offset: Long): Long = rt.owner.submit {
        requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)).read(offset, 4)
    }.join()

    private fun centerLuma(dims: Pair<Int, Int>, px: IntArray): Double {
        val w = dims.first
        val h = dims.second
        var sum = 0.0
        var n = 0
        for (y in h / 4 until (h * 3) / 4) {
            for (x in w / 4 until (w * 3) / 4) {
                val argb = px[y * w + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                n++
            }
        }
        return sum / n
    }

    private fun centerChromaSpread(dims: Pair<Int, Int>, px: IntArray): Double {
        val w = dims.first
        val h = dims.second
        var sum = 0.0
        var n = 0
        for (y in h / 4 until (h * 3) / 4) {
            for (x in w / 4 until (w * 3) / 4) {
                val argb = px[y * w + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                sum += (maxOf(r, g, b) - minOf(r, g, b)).toDouble()
                n++
            }
        }
        return sum / n
    }

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    @Test
    fun verifyLcdAndEvfBrightnessAndColorMatrixLive(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_BRIGHTNESS_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("bright-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_BRIGHTNESS_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readU32(rt, 0x01CC6424L))
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
            // Allow BUG-A3-2 live AE loop to converge cleanly on STILL LiveView before opening menu.
            stepBy(rt, 95_000_000L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L))
            println("[BRIGHT] STILL LCDC1 +0x4D8=0x${readLcdcWord(rt, 0x4D8).toString(16)} +0x4E8=0x${readLcdcWord(rt, 0x4E8).toString(16)} +0x500=0x${readLcdcWord(rt, 0x500).toString(16)}")

            // Navigate MENU -> LEFT -> DOWN x4 (SET UP) -> RIGHT -> DOWN x2 (SCREEN SETTING) -> MENU -> DOWN x3 (LCD BRIGHTNESS) -> MENU
            tapCalibrated(rt, "menu", 70_000_000L)
            tapCalibrated(rt, "left", 50_000_000L)
            repeat(4) { tapCalibrated(rt, "down", 50_000_000L) }
            tapCalibrated(rt, "right", 55_000_000L)
            repeat(2) { tapCalibrated(rt, "down", 50_000_000L) }
            tapCalibrated(rt, "menu", 70_000_000L) // SCREEN SETTING
            repeat(3) { tapCalibrated(rt, "down", 50_000_000L) } // Row 3 = LCD BRIGHTNESS
            tapCalibrated(rt, "menu", 70_000_000L) // Open LCD BRIGHTNESS slider (0)
            assertEquals(0x042f, readU32(rt, 0x01CC6424L))
            // Allow BUG-A3-2 live AE to converge after returning from opaque menu to LiveView slider backdrop.
            stepVirtualTicks(rt, 180_000_000L)
            val (_, d0, p0) = capturePort(rt, "display.main")
            val bl0 = backlightState(rt)
            val luma0 = centerLuma(d0, p0)
            dumpPng(outDir, "lcd_brightness_0.png", d0, p0)
            println("[BRIGHT] LCD  0: luma=${"%.2f".format(luma0)} bl=$bl0")

            // Step to +5 (UP x5)
            repeat(5) { tapCalibrated(rt, "up", 40_000_000L) }
            val (_, dup, pup) = capturePort(rt, "display.main")
            val blPlus5 = backlightState(rt)
            val lumaPlus5 = centerLuma(dup, pup)
            dumpPng(outDir, "lcd_brightness_plus5.png", dup, pup)
            println("[BRIGHT] LCD +5: luma=${"%.2f".format(lumaPlus5)} bl=$blPlus5")

            // Step to -5 (DOWN x10)
            repeat(10) { tapCalibrated(rt, "down", 40_000_000L) }
            val (_, ddn, pdn) = capturePort(rt, "display.main")
            val blMinus5 = backlightState(rt)
            val lumaMinus5 = centerLuma(ddn, pdn)
            dumpPng(outDir, "lcd_brightness_minus5.png", ddn, pdn)
            println("[BRIGHT] LCD -5: luma=${"%.2f".format(lumaMinus5)} bl=$blMinus5")

            assertEquals(0x0A, blMinus5.lcdDutyLow)
            assertEquals(0x87, bl0.lcdDutyLow)
            assertEquals(0x7B, blPlus5.lcdDutyBoost)
            assertTrue(blMinus5.lcdGain < bl0.lcdGain && bl0.lcdGain < blPlus5.lcdGain, "Expected monotonic SPI5 LCD gain")
            assertTrue(lumaMinus5 < luma0 && luma0 < lumaPlus5, "Expected monotonic LCD rendered luma: -5=$lumaMinus5 < 0=$luma0 < +5=$lumaPlus5")
            assertTrue(lumaPlus5 - lumaMinus5 > 50.0, "Expected strong visual LCD brightness dynamic range, got ${lumaPlus5 - lumaMinus5}")

            // Return to 0 (UP x5) -> exit slider -> Row 4 LCD COLOR -> +5 -> verify LCDC1 Q10 RGB matrix + chroma shift
            repeat(5) { tapCalibrated(rt, "up", 30_000_000L) }
            tapCalibrated(rt, "menu", 50_000_000L)
            tapCalibrated(rt, "down", 40_000_000L) // Row 4 = LCD COLOR
            tapCalibrated(rt, "menu", 50_000_000L)
            val color0Mat = readLcdcWord(rt, 0x4E8)
            val (_, dc0, pc0) = capturePort(rt, "display.main")
            val chroma0 = centerChromaSpread(dc0, pc0)
            repeat(5) { tapCalibrated(rt, "up", 35_000_000L) }
            val color5Mat = readLcdcWord(rt, 0x4E8)
            val (_, dc5, pc5) = capturePort(rt, "display.main")
            val chroma5 = centerChromaSpread(dc5, pc5)
            dumpPng(outDir, "lcd_color_plus5.png", dc5, pc5)
            println("[BRIGHT] LCD COLOR: mat0=0x${color0Mat.toString(16)} mat+5=0x${color5Mat.toString(16)} chroma0=${"%.2f".format(chroma0)} chroma+5=${"%.2f".format(chroma5)}")
            assertNotEquals(color0Mat, color5Mat, "Expected LCDC1 +0x4E8 Q10 color matrix to update for LCD COLOR +5")
            assertTrue(abs(chroma5 - chroma0) > 1.0, "Expected LCD COLOR +5 to visibly shift rendered chroma spread")

            // Return LCD COLOR to 0 -> exit -> Row 0 EVF BRIGHTNESS -> activate EVF -> verify EVF -5 < 0 < +5!
            repeat(5) { tapCalibrated(rt, "down", 30_000_000L) }
            tapCalibrated(rt, "menu", 50_000_000L)
            repeat(4) { tapCalibrated(rt, "up", 35_000_000L) } // Row 0 = EVF BRIGHTNESS
            rt.owner.submit { rt.host.patchLatestDirect("i2c-bus", mapOf("eyeSensorNear" to true), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 60_000_000L)
            tapCalibrated(rt, "menu", 55_000_000L) // EVF BRIGHTNESS (AUTO/MANUAL)
            tapCalibrated(rt, "down", 45_000_000L) // MANUAL
            tapCalibrated(rt, "menu", 55_000_000L) // EVF slider (0)
            stepVirtualTicks(rt, 180_000_000L)
            val (_, de0, pe0) = capturePort(rt, "display.evf")
            val evfBl0 = backlightState(rt)
            val evfLuma0 = centerLuma(de0, pe0)
            dumpPng(outDir, "evf_brightness_0.png", de0, pe0)
            println("[BRIGHT] EVF  0: luma=${"%.2f".format(evfLuma0)} bl=$evfBl0")

            repeat(6) { tapCalibrated(rt, "up", 40_000_000L) }
            val (_, deUp, peUp) = capturePort(rt, "display.evf")
            val evfBlPlus5 = backlightState(rt)
            val evfLumaPlus5 = centerLuma(deUp, peUp)
            dumpPng(outDir, "evf_brightness_plus5.png", deUp, peUp)
            println("[BRIGHT] EVF +5: luma=${"%.2f".format(evfLumaPlus5)} bl=$evfBlPlus5")

            repeat(11) { tapCalibrated(rt, "down", 40_000_000L) }
            val (_, deDn, peDn) = capturePort(rt, "display.evf")
            val evfBlMinus5 = backlightState(rt)
            val evfLumaMinus5 = centerLuma(deDn, peDn)
            dumpPng(outDir, "evf_brightness_minus5.png", deDn, peDn)
            println("[BRIGHT] EVF -5: luma=${"%.2f".format(evfLumaMinus5)} bl=$evfBlMinus5")

            assertTrue(evfBlMinus5.evfLuminance < evfBl0.evfLuminance && evfBl0.evfLuminance < evfBlPlus5.evfLuminance,
                "Expected monotonic EVF OLED luminance reg 0x13: -5=${evfBlMinus5.evfLuminance} < 0=${evfBl0.evfLuminance} < +5=${evfBlPlus5.evfLuminance}")
            assertTrue(evfBlMinus5.evfGain < evfBl0.evfGain && evfBl0.evfGain < evfBlPlus5.evfGain)
            assertTrue(evfLumaMinus5 < evfLuma0 && evfLuma0 < evfLumaPlus5, "Expected monotonic EVF rendered luma: -5=$evfLumaMinus5 < 0=$evfLuma0 < +5=$evfLumaPlus5")
        }
    }
}
