package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
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

class DualDisplayLiveAuditTest {
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

    private fun patchComp(rt: EmulatorRuntime, comp: String, key: String, value: Any) {
        rt.owner.submit { rt.host.patchLatestDirect(comp, mapOf(key to value), persistToDisk = false) }.join()
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 5_000_000L, settle: Long = 45_000_000L) {
        patchComp(rt, "buttons", key, true)
        stepBy(rt, hold)
        patchComp(rt, "buttons", key, false)
        stepBy(rt, settle)
    }

    private fun tapCalibrated(rt: EmulatorRuntime, key: String, settleTicks: Long = 65_000_000L) {
        patchComp(rt, "buttons", key, true)
        stepVirtualTicks(rt, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        patchComp(rt, "buttons", key, false)
        stepVirtualTicks(rt, settleTicks)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(dp.width * dp.height)
        dp.copyArgb(px)
        Triple(dp.sequence, dp.width to dp.height, px)
    }.join()

    private fun dumpLcdc1(rt: EmulatorRuntime): String = rt.owner.submit {
        val lcdc = requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
        val sb = StringBuilder()
        for (off in 0x000..0x200 step 4) {
            val v = lcdc.read(off.toLong(), 4)
            if (v != 0L) sb.append(" 0x${off.toString(16)}=0x${v.toString(16)}")
        }
        sb.toString()
    }.join()

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    @Test
    fun mfDualDisplayDispBackCycleLive(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_DUAL_DISPLAY_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("dual-disp-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_DUAL_DISPLAY_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

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
            stepBy(rt, 24_000_000L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L))

            // Exercise the selector after the release profile applies its button bindings.
            patchComp(rt, "soc-gpio", "focusMode", "MF")
            assertEquals("MF", rt.owner.submit { rt.session.componentState("soc-gpio")?.get("effectiveFocusMode") }.join())
            stepVirtualTicks(rt, 60_000_000L)
            val (s0, d0, p0) = captureMain(rt)
            dumpPng(outDir, "mf_0_base.png", d0, p0)
            println("[DUAL-DISP] MF base: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$s0 nonBlack=${p0.count { (it and 0xFFFFFF) != 0 }} lcdc1:${dumpLcdc1(rt)}")

            var prevPx = p0
            var sawDualDisplay = false
            for (k in 1..5) {
                tapCalibrated(rt, "back", 70_000_000L)
                val (sk, dk, pk) = captureMain(rt)
                val nb = pk.count { (it and 0xFFFFFF) != 0 }
                val diff = pk.indices.count { pk[it] != prevPx[it] }
                dumpPng(outDir, "mf_${k}_disp.png", dk, pk)
                val regs = dumpLcdc1(rt)
                println("[DUAL-DISP] MF DISP #$k: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sk nonBlack=$nb diff=$diff lcdc1:$regs")
                assertTrue(nb > 100_000, "MF DISP #$k must render visibly non-black (got $nb)")
                assertTrue(diff > 20_000, "MF DISP #$k must visibly change screen (got diff=$diff)")
                if (regs.contains("0x8=0x11110")) {
                    sawDualDisplay = true
                    var pic0NonBlack = 0
                    var pic0MidNonBlack = 0
                    var pic0GreenTintPx = 0
                    for (y in 140 until 630) for (x in 64 until 720) {
                        val argb = pk[y * dk.first + x]
                        if ((argb and 0xFFFFFF) != 0) {
                            pic0NonBlack++
                            if (y in 296 until 472) pic0MidNonBlack++
                            val r = (argb ushr 16) and 0xFF
                            val g = (argb ushr 8) and 0xFF
                            val b = argb and 0xFF
                            // Detect swizzled YYUV422-as-UYVY neon-green chroma corruption (G >> R and G >> B)
                            if (g > 45 && g - b > 30 && g - r > 15) pic0GreenTintPx++
                        }
                    }
                    var pic1NonBlack = 0
                    for (y in 296 until 472) for (x in 744 until 984) {
                        if ((pk[y * dk.first + x] and 0xFFFFFF) != 0) pic1NonBlack++
                    }
                    println("[DUAL-DISP] Dual Display panes: pic0NonBlack=$pic0NonBlack pic0MidNonBlack=$pic0MidNonBlack pic0GreenTintPx=$pic0GreenTintPx pic1NonBlack=$pic1NonBlack")
                    assertTrue(pic0NonBlack > 150_000, "BUG-A3-4: Left PIC0 Dual Display pane must render full live image (got $pic0NonBlack)")
                    assertTrue(pic0MidNonBlack > 40_000, "BUG-A3-4: Left PIC0 middle rows (y=296..472) must NOT be wiped by PIC1 strided DMA (got $pic0MidNonBlack)")
                    assertTrue(pic0GreenTintPx < pic0NonBlack / 10, "BUG-V3-5-3: Left PIC0 Dual Display pane must NOT render swizzled YYUV422 green stripes (greenTint=$pic0GreenTintPx / $pic0NonBlack)")
                    assertTrue(pic1NonBlack > 10_000, "BUG-A3-4: Right PIC1 Dual Display magnified inset must render live image (got $pic1NonBlack)")
                }
                prevPx = pk
            }
            assertTrue(sawDualDisplay, "MF DISP cycle must enter Dual Display (LCDC1 +0x008 = 0x11110)")
            val summary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[DUAL-DISP] SUMMARY: $summary")
            assertTrue(summary.isEmpty() || summary.contains("mmioUnique=0"), "BUG-A3-4: Expected 0 RELAXED/MMIO in MF DISP cycle, got: $summary")
        }
    }

    @Test
    fun bootHoldDispBackFwupRendersWhiteBackdropWithBlackTextLive(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_DUAL_DISPLAY_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("fwup-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_DUAL_DISPLAY_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            // Hold DISP/BACK during cold boot to enter Firmware Update screen (scr=0x0601)
            patchComp(rt, "buttons", "back", true)
            for (i in 0 until 80) {
                stepVirtualTicks(rt, 15_000_000L)
                if (readU32(rt, 0x01CC6424L) == 0x0601) break
            }
            stepVirtualTicks(rt, 80_000_000L)
            patchComp(rt, "buttons", "back", false)
            stepVirtualTicks(rt, 40_000_000L)
            val scr = readU32(rt, 0x01CC6424L)
            val (seq, dims, px) = captureMain(rt)
            val regs = dumpLcdc1(rt)
            dumpPng(outDir, "fwup_0601.png", dims, px)
            var whitePx = 0
            var blackTextPx = 0
            for (y in 200 until 620) for (x in 150 until 874) {
                val argb = px[y * dims.first + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (r >= 245 && g >= 245 && b >= 245) whitePx++
                if (r <= 15 && g <= 15 && b <= 15) blackTextPx++
            }
            println("[FWUP-BUG21] scr=0x${scr.toString(16)} seq=$seq whitePx=$whitePx blackTextPx=$blackTextPx lcdc1:$regs")
            assertEquals(0x0601, scr, "Holding DISP/BACK on boot must enter FWUP scr=0x0601")
            assertTrue(regs.contains("0x30=0x200803fc"), "FWUP must program LCDC1 +0x030 = 0x200803fc (white BGCOLOR)")
            assertTrue(whitePx > 150_000, "BUG-21: FWUP center boxes must render white 0x200803fc backdrop (got whitePx=$whitePx)")
            assertTrue(blackTextPx > 1_000, "BUG-21: FWUP black version text must be crisp over white backdrop (got blackTextPx=$blackTextPx)")
        }
    }
}
