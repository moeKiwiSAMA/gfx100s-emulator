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

class Bug24PostOobePlaybackAndDispAuditTest {
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

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long): Long {
        val start = rt.telemetry.virtualTick
        val target = start + deltaTicks
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
        return rt.telemetry.virtualTick - start
    }

    private fun patchBtn(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit {
            rt.host.patchLatestDirect("buttons", mapOf(key to down), persistToDisk = (key == "power" || key == "movieMode"))
        }.join()
    }

    private fun tapCalibrated(rt: EmulatorRuntime, key: String, settleTicks: Long = 55_000_000L) {
        patchBtn(rt, key, true)
        stepVirtualTicks(rt, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        patchBtn(rt, key, false)
        stepVirtualTicks(rt, settleTicks)
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 5_000_000L, settle: Long = 45_000_000L) {
        patchBtn(rt, key, true)
        stepBy(rt, hold)
        patchBtn(rt, key, false)
        stepBy(rt, settle)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(dp.width * dp.height)
        dp.copyArgb(px)
        Triple(dp.sequence, dp.width to dp.height, px)
    }.join()

    private fun countNonBlack(px: IntArray): Int = px.count { (it and 0x00FFFFFF) != 0 }

    private fun countDiff(a: IntArray, b: IntArray): Int {
        var c = 0
        for (i in a.indices) if (a[i] != b[i]) c++
        return c
    }

    private fun lcdcInfo(rt: EmulatorRuntime): String = rt.owner.submit {
        val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)
        val vg = rt.host.components().single { it.descriptor.id == "soc-vglib-init" }.component.state()
        val sc = vg["scanout"] as? Map<*, *>
        val outOn = sc?.get("outputActive")
        if (lcdc == null) "lcdc=null"
        else "004=0x${lcdc.read(0x004,4).toString(16)} 008=0x${lcdc.read(0x008,4).toString(16)} 030=0x${lcdc.read(0x030,4).toString(16)} 044=0x${lcdc.read(0x044,4).toString(16)} out=$outOn"
    }.join()

    private fun lcdcReg008(rt: EmulatorRuntime): Long = rt.owner.submit {
        rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)?.read(0x008, 4) ?: 0L
    }.join()

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    @Test
    fun postOobeEarlyPlaybackMenuTabsAndAllDispModesLive(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_BUG24_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("bug24-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_BUG24_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        // ===== BOOT #1: Complete OOBE -> STILL 0x0100 -> real power-off (persists Flash COW) =====
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Boot #1 expected OOBE 0x0421")
            tapInstr(rt, "menu") // -> 0x0423 Area
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "menu") // -> 0x0422 Date/Time
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "right", 4_000_000L, 12_000_000L)
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "menu") // -> 0x0482 Start App
            tapInstr(rt, "menu") // -> 0x0100 STILL
            for (i in 0 until 28) {
                if (readU32(rt, 0x01CC6424L) == 0x0100 && readU8(rt, 0x01CBFCA2L) == 0) break
                stepBy(rt, 8_000_000L)
            }
            stepBy(rt, 24_000_000L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Boot #1 expected STILL 0x0100")
            println("[BUG24] Boot #1 reached STILL 0x0100 at tick=${rt.telemetry.virtualTick}; powering off...")
            patchBtn(rt, "power", false)
            for (i in 0 until 15) {
                stepBy(rt, 30_000_000L)
                if (rt.session.isSocPoweredOff) break
            }
            println("[BUG24] Boot #1 power-off complete: socOff=${rt.session.isSocPoweredOff} summary=${rt.session.relaxedFeedbackSummaryReport()}")
        }

        // ===== BOOT #2: Fast Post-OOBE Cold Boot -> PLAY at tick < 450M (BUG-24!) + Menu Tabs (BUG-25!) + All DISP modes =====
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            // Restore power=true as launcher does on next boot
            patchBtn(rt, "power", true)
            for (i in 0 until 80) {
                stepVirtualTicks(rt, 15_000_000L)
                val scr = readU32(rt, 0x01CC6424L)
                val reg008 = lcdcReg008(rt)
                val (seq, _, px) = captureMain(rt)
                if (scr == 0x0100 && (reg008 and 0x1000L) != 0L && seq >= 5 && countNonBlack(px) > 150_000) break
            }
            val boot2Tick = rt.telemetry.virtualTick
            val (s2, d2, p2) = captureMain(rt)
            dumpPng(outDir, "b2_00_still.png", d2, p2)
            println("[BUG24] Boot #2 STILL ready: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} tick=$boot2Tick seq=$s2 nonBlack=${countNonBlack(p2)} ${lcdcInfo(rt)}")
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Boot #2 must land directly in STILL 0x0100")
            assertTrue(boot2Tick < 572_406_608L, "Boot #2 must test early post-OOBE tick < 572M (got $boot2Tick)")

            // 1. BUG-24: Press PLAY early (< 572M!) and verify Playback 0x0300 opens & finishes JPEG decode with 0 RELAXED/MMIO!
            tapCalibrated(rt, "play", 45_000_000L)
            val playEntryTick = rt.telemetry.virtualTick
            for (w in 0 until 12) {
                val (_, _, curPx) = captureMain(rt)
                if (readU32(rt, 0x01CC6424L) == 0x0300 && (lcdcReg008(rt) and 0x1000L) != 0L && countNonBlack(curPx) > 50_000) break
                stepVirtualTicks(rt, 20_000_000L)
            }
            val playScr = readU32(rt, 0x01CC6424L)
            val playTick = rt.telemetry.virtualTick
            val (sp, dp, pp) = captureMain(rt)
            dumpPng(outDir, "b2_01_play.png", dp, pp)
            val playSummary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[BUG24] PLAY pressed: scr=0x${playScr.toString(16)} entryTick=$playEntryTick settledTick=$playTick seq=$sp nonBlack=${countNonBlack(pp)} ${lcdcInfo(rt)} summary=$playSummary")
            assertEquals(0x0300, playScr, "BUG-24: Early post-OOBE PLAY must open Playback 0x0300 without 0xfffd10e0 deadlock!")
            assertTrue(playEntryTick < 572_406_608L, "BUG-24: Must enter PLAY before legacy 572M deadline (got $playEntryTick)")
            assertTrue(countNonBlack(pp) > 50_000, "Playback screen must render visibly non-black")
            assertTrue(playSummary.isEmpty() || playSummary.contains("mmioUnique=0"), "BUG-24: Must have 0 RELAXED/MMIO fallbacks on PLAY: $playSummary")

            // 2. PLAYBACK DISP/BACK modes audit (4 consecutive taps — ALL modes including Info Off 0x10010 must render non-black!)
            var prevPx = pp
            for (k in 1..4) {
                tapCalibrated(rt, "back", 55_000_000L)
                val (sk, dk, pk) = captureMain(rt)
                val nb = countNonBlack(pk)
                val diff = countDiff(prevPx, pk)
                dumpPng(outDir, "b2_02_play_disp_$k.png", dk, pk)
                println("[BUG24] PLAY DISP #$k: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sk nonBlack=$nb diff=$diff ${lcdcInfo(rt)}")
                assertTrue(nb > 100_000, "PLAY DISP #$k must render visibly non-black (got $nb)")
                assertTrue(diff > 2_000, "PLAY DISP #$k must visibly change HUD/photo overlay (got diff=$diff)")
                prevPx = pk
            }

            // Exit Playback back to STILL 0x0100 via PLAY button (allow transition through 0x0506 -> 0x0100)
            tapCalibrated(rt, "play", 45_000_000L)
            for (w in 0 until 15) {
                if (readU32(rt, 0x01CC6424L) == 0x0100) break
                stepVirtualTicks(rt, 20_000_000L)
            }
            println("[BUG24] Back to STILL from PLAY: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} ${lcdcInfo(rt)}")
            assertEquals(0x0100, readU32(rt, 0x01CC6424L))

            // 3. BUG-25: Open MENU -> LEFT into left-sidebar -> DOWN through EVERY tab (I.Q., AF/MF, SHOOTING, FLASH, SET UP, MY)!
            tapCalibrated(rt, "menu", 55_000_000L)
            val (sm0, dm0, pm0) = captureMain(rt)
            dumpPng(outDir, "b2_03_menu_tab0.png", dm0, pm0)
            println("[BUG25] MENU open: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sm0 nonBlack=${countNonBlack(pm0)} ${lcdcInfo(rt)}")
            assertEquals(0x0400, readU32(rt, 0x01CC6424L))

            tapCalibrated(rt, "left", 35_000_000L)
            var prevMenuPx = pm0
            for (tabIdx in 1..6) {
                tapCalibrated(rt, "down", 55_000_000L)
                val (st, dt, pt) = captureMain(rt)
                val nb = countNonBlack(pt)
                val diff = countDiff(prevMenuPx, pt)
                dumpPng(outDir, "b2_03_menu_tab$tabIdx.png", dt, pt)
                println("[BUG25] MENU sidebar tab #$tabIdx: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$st nonBlack=$nb diff=$diff ${lcdcInfo(rt)}")
                assertTrue(nb > 50_000, "BUG-25: Menu sidebar tab #$tabIdx must NOT black out (nonBlack=$nb)")
                prevMenuPx = pt
            }

            // Close Menu back to STILL 0x0100
            tapCalibrated(rt, "back", 55_000_000L)
            if (readU32(rt, 0x01CC6424L) != 0x0100) tapCalibrated(rt, "back", 55_000_000L)
            println("[BUG25] Exited MENU: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} ${lcdcInfo(rt)}")

            // 4. MOVIE DISP/BACK modes audit (switch movieMode=true -> cycle DISP/BACK x4)
            patchBtn(rt, "movieMode", true)
            stepVirtualTicks(rt, 85_000_000L)
            val (smv0, dmv0, pmv0) = captureMain(rt)
            dumpPng(outDir, "b2_04_movie_base.png", dmv0, pmv0)
            println("[BUG24] MOVIE base: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$smv0 nonBlack=${countNonBlack(pmv0)} ${lcdcInfo(rt)}")
            var prevMvPx = pmv0
            for (k in 1..4) {
                tapCalibrated(rt, "back", 65_000_000L)
                val (sk, dk, pk) = captureMain(rt)
                val nb = countNonBlack(pk)
                val diff = countDiff(prevMvPx, pk)
                dumpPng(outDir, "b2_04_movie_disp_$k.png", dk, pk)
                println("[BUG24] MOVIE DISP #$k: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sk nonBlack=$nb diff=$diff ${lcdcInfo(rt)}")
                prevMvPx = pk
            }
            patchBtn(rt, "movieMode", false)

            val finalSummary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[BUG24] FINAL SUMMARY: $finalSummary")
            assertTrue(finalSummary.isEmpty() || finalSummary.contains("mmioUnique=0"), "Must complete full BUG-24/BUG-25/DISP audit with 0 RELAXED/MMIO: $finalSummary")
        }
    }
}
