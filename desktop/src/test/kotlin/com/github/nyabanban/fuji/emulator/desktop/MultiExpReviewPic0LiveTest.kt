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

class MultiExpReviewPic0LiveTest {
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

    @Test
    fun probeMultiExpReviewPic0Live(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_MULTIEXP_PIC0_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("mexp-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_MULTIEXP_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

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

            // BUG-A1-1 live check: open I.Q. menu, navigate down through GRAIN EFFECT and open/exit submenu
            // without throwing "Unsupported inline draw packets" or freezing vglib IRQ 92.
            tapCalibrated(rt, "menu", 70_000_000L)
            val menuScr = readU32(rt, 0x01CC6424L)
            val seqBeforeMenu = rt.owner.submit {
                requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence
            }.join()
            repeat(5) { tapCalibrated(rt, "down", 45_000_000L) } // Sweep I.Q. rows down to GRAIN EFFECT
            tapCalibrated(rt, "menu", 60_000_000L)
            tapCalibrated(rt, "down", 45_000_000L)
            tapCalibrated(rt, "back", 60_000_000L)
            tapCalibrated(rt, "back", 70_000_000L)
            val seqAfterMenu = rt.owner.submit {
                requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence
            }.join()
            println("[MEXP] BUG-A1-1 I.Q. menu sweep: menuScr=0x${menuScr.toString(16)} exitScr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$seqBeforeMenu->$seqAfterMenu")
            assertEquals(0x0400, menuScr, "MENU from LiveView must enter I.Q. menu (0x400)")
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "BACK must return cleanly to LiveView (0x100) without freezing")
            assertTrue(seqAfterMenu > seqBeforeMenu, "vglib display scanout must stay live across I.Q./GRAIN EFFECT navigation")

            // Switch physical S/C/M focus selector lever to MF (GPIO 7.13=0, 7.14=1 -> input7=16448)
            rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 16448L))
            }.join()
            stepVirtualTicks(rt, 30_000_000L)

            // DRIVE -> Row 6 MULTIPLE EXPOSURE -> MENU (confirms ADD and returns to Live View 0x100)
            tapCalibrated(rt, "drive", 55_000_000L)
            println("[MEXP] After DRIVE: scr=0x${readU32(rt, 0x01CC6424L).toString(16)}")
            repeat(6) { tapCalibrated(rt, "down", 35_000_000L) }
            tapCalibrated(rt, "menu", 60_000_000L)
            println("[MEXP] After MULTIPLE EXPOSURE ADD confirm: scr=0x${readU32(rt, 0x01CC6424L).toString(16)}")

            // Fire Shutter (S1 -> S2) for Frame 1!
            patchBtn(rt, "shutterHalf", true)
            stepBy(rt, 24_000_000L)
            patchBtn(rt, "shutterFull", true)
            stepBy(rt, 36_000_000L)
            patchBtn(rt, "shutterFull", false)
            patchBtn(rt, "shutterHalf", false)

            for (i in 0 until 60) {
                stepVirtualTicks(rt, 20_000_000L)
                val scr = readU32(rt, 0x01CC6424L)
                if (scr == 0x0141) {
                    println("[MEXP] Reached scr=0x141 at step $i!")
                    break
                }
            }
            stepVirtualTicks(rt, 40_000_000L)

            val diag = rt.owner.submit {
                val lcdc = requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                val r008 = lcdc.read(0x008, 4)
                val r030 = lcdc.read(0x030, 4)
                val pic0Regs = (0x120..0x180 step 4).joinToString(" ") { off ->
                    val v = lcdc.read(off.toLong(), 4)
                    if (v != 0L) "+0x${off.toString(16)}=0x${v.toString(16)}" else ""
                }.replace(Regex("\\s+"), " ").trim()
                val pic0Addr = ((lcdc.read(0x130, 4) and 3L) shl 32) or (lcdc.read(0x134, 4) and 0xffffffffL)
                var nonZeroBytes = 0
                var nonZeroWordHex = ""
                var firstBytes = ""
                if (pic0Addr in 1L..0xEFFF_0000L) {
                    val sample = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
                    try {
                        mem.read(pic0Addr, sample)
                        val arr = sample.array()
                        firstBytes = arr.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                        for (b in arr) if (b.toInt() != 0) nonZeroBytes++
                        val mid = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
                        mem.read(pic0Addr + 2048L * 200L, mid)
                        var midNz = 0
                        for (b in mid.array()) if (b.toInt() != 0) midNz++
                        nonZeroWordHex = "first64kNz=$nonZeroBytes midRow200Nz=$midNz midFirst16=${mid.array().take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}"
                    } catch (e: Exception) {
                        nonZeroWordHex = "readErr=${e.message}"
                    }
                }
                val px = IntArray(dp.width * dp.height)
                dp.copyArgb(px)
                var nonBlack = 0
                var midSum = 0L
                var midCount = 0
                for (y in 0 until dp.height) {
                    val row = y * dp.width
                    for (x in 0 until dp.width) {
                        val p = px[row + x]
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        if ((p and 0xFFFFFF) != 0) nonBlack++
                        if (x in 256 until 768 && y in 192 until 576) {
                            midSum += (r + g + b) / 3
                            midCount++
                        }
                    }
                }
                val centerMeanLuma = if (midCount > 0) midSum.toDouble() / midCount else 0.0
                if (outDir != null) {
                    Files.createDirectories(outDir)
                    val img = BufferedImage(dp.width, dp.height, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, dp.width, dp.height, px, 0, dp.width)
                    ImageIO.write(img, "png", outDir.resolve("mexp_scr141.png").toFile())
                }
                Triple(nonZeroBytes, nonBlack, centerMeanLuma) to
                    "scr=0x${readU32(rt, 0x01CC6424L).toString(16)} 0x008=0x${r008.toString(16)} 0x030=0x${r030.toString(16)} " +
                    "nonBlack=$nonBlack centerMeanLuma=${"%.1f".format(centerMeanLuma)} " +
                    "pic0Addr=0x${pic0Addr.toString(16)} first32=[$firstBytes] $nonZeroWordHex"
            }.join()
            val (metrics, summary) = diag
            val (pic0NonZero, nonBlack, centerMeanLuma) = metrics
            println("[MEXP] $summary")
            assertEquals(0x0141, readU32(rt, 0x01CC6424L), "Must be on Multiple Exposure Frame 1 Review (scr=0x141)")
            assertTrue(pic0NonZero > 0, "PIC0 UYVY buffer must contain synthesized optical photo (first64kNz=$pic0NonZero)")
            assertTrue(nonBlack > 150_000, "PIC0 review backdrop must render visible photo pixels (nonBlack=$nonBlack)")
            assertTrue(centerMeanLuma > 20.0, "Center photo luma must be bright non-black (centerMeanLuma=$centerMeanLuma)")
        }
    }
}
