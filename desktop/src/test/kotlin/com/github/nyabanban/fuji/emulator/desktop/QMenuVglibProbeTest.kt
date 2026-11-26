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

class QMenuVglibProbeTest {
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

    private fun tapCalibrated(rt: EmulatorRuntime, comp: String, key: String, settleTicks: Long = 55_000_000L) {
        patchComp(rt, comp, key, true)
        stepVirtualTicks(rt, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        patchComp(rt, comp, key, false)
        stepVirtualTicks(rt, settleTicks)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(dp.width * dp.height)
        dp.copyArgb(px)
        Triple(dp.sequence, dp.width to dp.height, px)
    }.join()

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    @Test
    fun probeQMenuHighlightRender(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_QMENU_PROBE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("qmenu-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_QMENU_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
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
            val (s0, d0, p0) = captureMain(rt)
            dumpPng(outDir, "q_before_still.png", d0, p0)
            println("[QMENU] STILL ready: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$s0")

            tapCalibrated(rt, "soc-adc", "qButton", 80_000_000L)
            val (sq0, dq0, pq0) = captureMain(rt)
            dumpPng(outDir, "q0_open.png", dq0, pq0)
            println("[QMENU] OPEN: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sq0 diff=${pq0.indices.count { pq0[it] != p0[it] }}")
            assertEquals(0x0456, readU32(rt, 0x01CC6424L), "Q must open Q-Menu 0x0456")

            tapCalibrated(rt, "buttons", "right", 60_000_000L)
            val (sq1, dq1, pq1) = captureMain(rt)
            dumpPng(outDir, "q1_right.png", dq1, pq1)
            println("[QMENU] RIGHT: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sq1 diff=${pq1.indices.count { pq1[it] != pq0[it] }}")

            // BUG-A5-4 assertion: active ISO 100 tile (row 0 col 1: x=260..500, y=100..210) MUST have BOTH bright silver gradient pixels AND dark black text pixels!
            var isoBright = 0
            var isoDark = 0
            for (y in 105 until 205) for (x in 270 until 490) {
                val argb = pq1[y * dq1.first + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (r >= 180 && g >= 180 && b >= 180) isoBright++
                if (r <= 25 && g <= 25 && b <= 25) isoDark++
            }
            println("[QMENU] Active ISO tile pixels: bright=$isoBright dark=$isoDark")
            kotlin.test.assertTrue(isoBright > 10_000, "BUG-A5-4: Active ISO tile must render silver gradient background (got bright=$isoBright)")
            kotlin.test.assertTrue(isoDark > 1_000, "BUG-A5-4: Active ISO tile must render dark ISO 100 glyphs over silver background (got dark=$isoDark)")

            tapCalibrated(rt, "buttons", "down", 60_000_000L)
            val (sq2, dq2, pq2) = captureMain(rt)
            dumpPng(outDir, "q2_down.png", dq2, pq2)
            println("[QMENU] DOWN: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sq2 diff=${pq2.indices.count { pq2[it] != pq1[it] }}")

            // Close Q-Menu, press Fn2 -> BACK (BUG-20 repro sequence), then re-open Q-Menu!
            tapCalibrated(rt, "soc-adc", "qButton", 60_000_000L)
            tapCalibrated(rt, "soc-adc", "fn2Button", 50_000_000L)
            tapCalibrated(rt, "buttons", "back", 50_000_000L)
            tapCalibrated(rt, "soc-adc", "qButton", 80_000_000L)
            val (sq3, dq3, pq3) = captureMain(rt)
            dumpPng(outDir, "q3_after_fn2.png", dq3, pq3)
            println("[QMENU] AFTER Fn2->Q: scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$sq3 diff=${pq3.indices.count { pq3[it] != p0[it] }}")
            assertEquals(0x0456, readU32(rt, 0x01CC6424L), "BUG-20: Q-Menu must re-open visibly after Fn2")
            kotlin.test.assertTrue(pq3.indices.count { pq3[it] != p0[it] } > 200_000, "BUG-20: Q-Menu must visibly paint 16-tile grid")
        }
    }
}
