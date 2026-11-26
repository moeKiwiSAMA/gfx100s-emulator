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
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Env-gated (`FUJI_RUN_PLAYBACK_LCDC_LIVE=1`) live unmodified `GXUP0004.DAT` regression suite for
 * `P1-FIX-PLAYBACK-LCDC`:
 * - **`BUG-V3-8-5`**: Boot #2 post-OOBE Rear LCD renders neutral sRGB white point (no `-14%` Green /
 *   uniform magenta tint from mis-applying Boot #1's BOE gamut white-point compensation).
 * - **`BUG-V3-8-4`**: Real 102MP STILL capture (`DSCF0002.JPG`) embeds a valid non-green optical
 *   `160x120` EXIF thumbnail (not solid BT.601 green), AND Playback `9`-frame / `100`-frame
 *   multi-frame grids render pitch-black backgrounds with crisp thumbnails (zero olive-green stripes).
 * - **`BUG-V3-8-8`**: Playback `9`/`100`-frame Q12-scaled `PIC1` transitions (`+0x17C = 0x0F00_0F0F`,
 *   `+0x170 = 1028x720`, `+0x178 = 1092x768`) render cleanly with `0` `[RELAXED/MMIO]` warnings.
 */
class PlaybackLcdcFixesLiveTest {
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

    private fun readLcdcWord(rt: EmulatorRuntime, offset: Long): Long = rt.owner.submit {
        requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)).read(offset, 4)
    }.join()

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long) {
        rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            while (rt.session.checkpointAttempts() < target && !rt.session.isSocPoweredOff) {
                if (!rt.session.stepFast()) break
            }
        }.join()
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
            val prevTick = rt.telemetry.virtualTick
            val ok = rt.owner.submit {
                var i = 0
                var running = true
                while (i < 512 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    if (!rt.session.stepFast()) { running = false; break }
                    i++
                }
                running
            }.join()
            if (!ok || rt.session.isSocPoweredOff || rt.telemetry.virtualTick == prevTick) break
        }
    }

    private fun patchComp(rt: EmulatorRuntime, id: String, patch: Map<String, Any>) {
        rt.owner.submit { rt.host.patchLatestDirect(id, patch, persistToDisk = false) }.join()
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 5_000_000L, settle: Long = 45_000_000L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepBy(rt, hold)
        patchComp(rt, "buttons", mapOf(key to false))
        stepBy(rt, settle)
    }

    private fun tapCalibrated(rt: EmulatorRuntime, key: String, settleTicks: Long = 55_000_000L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepVirtualTicks(rt, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        patchComp(rt, "buttons", mapOf(key to false))
        stepVirtualTicks(rt, settleTicks)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(dp.width * dp.height)
        dp.copyArgb(px)
        Triple(dp.sequence, dp.width to dp.height, px)
    }.join()

    private fun countGreenDominant(px: IntArray): Int {
        var count = 0
        for (p in px) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            if (g > r + 25 && g > b + 25 && g > 40) count++
        }
        return count
    }

    private fun hudWhiteMeanRgb(dims: Pair<Int, Int>, px: IntArray): Triple<Double, Double, Double> {
        var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var n = 0
        for (y in 680 until minOf(760, dims.second)) {
            for (x in 0 until dims.first) {
                val p = px[y * dims.first + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (r > 120 && g > 120 && b > 120) {
                    rSum += r; gSum += g; bSum += b; n++
                }
            }
        }
        require(n > 500) { "Expected visible white bottom-bar HUD pixels, found $n" }
        return Triple(rSum / n, gSum / n, bSum / n)
    }

    private fun dumpPng(dir: Path?, name: String, dims: Pair<Int, Int>, px: IntArray) {
        if (dir == null) return
        Files.createDirectories(dir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
        ImageIO.write(img, "png", dir.resolve(name).toFile())
    }

    private fun extractEmbeddedExifThumbnail(jpegBytes: ByteArray): BufferedImage? {
        // Scan APP1 EXIF segment (first 64 KiB) for embedded JPEG SOI (FF D8 FF DB/C0/E0/C4) .. EOI (FF D9)
        val scanLimit = minOf(jpegBytes.size - 4, 65536)
        var start = -1
        for (i in 2 until scanLimit) {
            if ((jpegBytes[i].toInt() and 0xFF) == 0xFF &&
                (jpegBytes[i + 1].toInt() and 0xFF) == 0xD8 &&
                (jpegBytes[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                start = i
                break
            }
        }
        if (start < 0) return null
        for (j in start + 4 until minOf(jpegBytes.size - 1, start + 65536)) {
            if ((jpegBytes[j].toInt() and 0xFF) == 0xFF && (jpegBytes[j + 1].toInt() and 0xFF) == 0xD9) {
                val slice = jpegBytes.copyOfRange(start, j + 2)
                return ImageIO.read(ByteArrayInputStream(slice))
            }
        }
        return null
    }

    private fun bootPostOobeToStill(rt: EmulatorRuntime) {
        rt.owner.submit { rt.session.initialize() }.join()
        patchComp(rt, "buttons", mapOf("power" to true))
        for (i in 0 until 80) {
            stepVirtualTicks(rt, 15_000_000L)
            val scr = readU32(rt, 0x01CC6424L)
            val reg008 = readLcdcWord(rt, 0x008)
            val (seq, _, _) = captureMain(rt)
            if (scr == 0x0100 && (reg008 and 0x1000L) != 0L && seq >= 5) break
        }
        stepVirtualTicks(rt, 80_000_000L)
    }

    @Test
    fun boot2NeutralWhitePointRealThumbnailAndPlaybackGridCleanLive(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_PLAYBACK_LCDC_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("pb-lcdc-live")
        copyDirectory(stagedRoot, workRoot)
        val dumpDir = System.getenv("FUJI_PLAYBACK_LCDC_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )

        // ===== BOOT #1: Complete OOBE -> STILL 0x0100 -> Clean Power-Off =====
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt1 ->
            rt1.owner.submit { rt1.session.initialize() }.join()
            stepBy(rt1, 620_000_000L)
            assertEquals(0x0421, readU32(rt1, 0x01CC6424L))
            tapInstr(rt1, "menu")
            tapInstr(rt1, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt1, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt1, "menu")
            tapInstr(rt1, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt1, "right", 4_000_000L, 12_000_000L)
            tapInstr(rt1, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt1, "menu")
            tapInstr(rt1, "menu")
            for (i in 0 until 28) {
                if (readU32(rt1, 0x01CC6424L) == 0x0100 && readU8(rt1, 0x01CBFCA2L) == 0) break
                stepBy(rt1, 8_000_000L)
            }
            stepVirtualTicks(rt1, 60_000_000L)
            assertEquals(0x0100, readU32(rt1, 0x01CC6424L))
            patchComp(rt1, "buttons", mapOf("power" to false))
            for (i in 0 until 25) {
                stepBy(rt1, 30_000_000L)
                if (rt1.session.isSocPoweredOff) break
            }
        }

        // ===== BOOT #2: Verify BUG-V3-8-5 (Neutral White Point) + Capture Real 102MP JPEG (BUG-V3-8-4 Part B) =====
        val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        fun listDcimJpegs(): List<Path> = if (Files.isDirectory(dcimRoot)) {
            Files.walk(dcimRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".JPG", ignoreCase = true) && Files.size(it) > 10_000L }
                    .sorted().toList()
            }
        } else emptyList()

        var capturedJpegPath: Path? = null
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt2 ->
            bootPostOobeToStill(rt2)
            assertEquals(0x0100, readU32(rt2, 0x01CC6424L))
            val (_, b2Dims, b2Px) = captureMain(rt2)
            dumpPng(dumpDir, "boot2_liveview.png", b2Dims, b2Px)
            val (hr, hg, hb) = hudWhiteMeanRgb(b2Dims, b2Px)
            val gOverR = hg / hr
            println("[PB-LCDC] Boot #2 STILL hudWhite=[${"%.1f".format(hr)},${"%.1f".format(hg)},${"%.1f".format(hb)}] G/R=${"%.3f".format(gOverR)}")
            assertTrue(
                gOverR in 0.94..1.06 && abs(hr - hg) < 12.0,
                "BUG-V3-8-5: Boot #2 Rear LCD must stay neutral without magenta tint (G/R=$gOverR, RGB=[$hr,$hg,$hb])",
            )

            // Capture real 102MP JPEG (DSCF0002.JPG)
            val beforeFiles = listDcimJpegs().map { it.fileName.toString() }.toSet()
            val encBefore = (compState(rt2, "soc-jpeg")["encodeCount"] as? Number)?.toLong() ?: 0L
            patchComp(rt2, "buttons", mapOf("shutterHalf" to true))
            for (w in 0 until 5) {
                stepBy(rt2, 12_000_000L)
                val s1Sub0 = readU8(rt2, 0x02C47C5CL + 0x60L)
                val saclac0 = readU32(rt2, 0x02DA49ECL) and 0xFFFF
                if (w >= 1 && (s1Sub0 >= 4 || (s1Sub0 == 0 && saclac0 == 4))) break
            }
            patchComp(rt2, "buttons", mapOf("shutterHalf" to true, "shutterFull" to true))
            repeat(3) { stepBy(rt2, 12_000_000L) }
            patchComp(rt2, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            for (w in 1..30) {
                stepVirtualTicks(rt2, 500L * 54_000L)
                val encNow = (compState(rt2, "soc-jpeg")["encodeCount"] as? Number)?.toLong() ?: 0L
                if (encNow >= encBefore + 3L) break
            }
            stepVirtualTicks(rt2, 1500L * 54_000L)
            capturedJpegPath = listDcimJpegs().firstOrNull { it.fileName.toString() !in beforeFiles }
            val jpegPath = assertNotNull(capturedJpegPath, "Expected captured DSCF0002.JPG on Slot 1 (enc=$encBefore->${compState(rt2, "soc-jpeg")["encodeCount"]}, files=${listDcimJpegs()})")
            val jpegBytes = Files.readAllBytes(jpegPath)
            val thumb = assertNotNull(extractEmbeddedExifThumbnail(jpegBytes), "Expected embedded EXIF JPEG thumbnail inside ${jpegPath.fileName}")
            var tGreen = 0; var tR = 0.0; var tG = 0.0; var tB = 0.0
            for (y in 0 until thumb.height) {
                for (x in 0 until thumb.width) {
                    val rgb = thumb.getRGB(x, y)
                    val r = (rgb ushr 16) and 0xFF; val g = (rgb ushr 8) and 0xFF; val b = rgb and 0xFF
                    tR += r; tG += g; tB += b
                    if (g > r + 25 && g > b + 25 && g > 40) tGreen++
                }
            }
            val tCount = (thumb.width * thumb.height).toDouble()
            println("[PB-LCDC] ${jpegPath.fileName} (${jpegBytes.size} B) EXIF thumb ${thumb.width}x${thumb.height}: meanRGB=[${"%.1f".format(tR / tCount)},${"%.1f".format(tG / tCount)},${"%.1f".format(tB / tCount)}] greenDom=$tGreen")
            assertTrue(
                tGreen < (thumb.width * thumb.height) / 10 && (tR / tCount) > 30.0,
                "BUG-V3-8-4: Captured 102MP EXIF thumbnail must be a real optical scene, not solid BT.601 green (greenDom=$tGreen, meanR=${tR / tCount})",
            )

            patchComp(rt2, "buttons", mapOf("power" to false))
            for (i in 0 until 25) {
                stepBy(rt2, 30_000_000L)
                if (rt2.session.isSocPoweredOff) break
            }
        }

        // ===== BOOT #3: Playback Single -> 9-Frame Grid -> 100-Frame Grid (BUG-V3-8-4 & BUG-V3-8-8) =====
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt3 ->
            bootPostOobeToStill(rt3)
            tapCalibrated(rt3, "play", 55_000_000L)
            for (w in 0 until 20) {
                if (readU32(rt3, 0x01CC6424L) == 0x0300) break
                stepVirtualTicks(rt3, 25_000_000L)
            }
            stepVirtualTicks(rt3, 120_000_000L)
            assertEquals(0x0300, readU32(rt3, 0x01CC6424L))
            val (_, sDims, sPx) = captureMain(rt3)
            dumpPng(dumpDir, "pb_single.png", sDims, sPx)
            val singleGreen = countGreenDominant(sPx)
            println("[PB-LCDC] Playback single: greenDom=$singleGreen")
            assertTrue(singleGreen < 1_000, "Playback single frame must not flash solid green (got $singleGreen)")

            // From full-frame playback the right-hand step enters single-picture zoom.
            patchComp(rt3, "soc-adc", mapOf("rearDialDetents" to 1L))
            stepVirtualTicks(rt3, 60_000_000L)
            assertEquals(0x0311, readU32(rt3, 0x01CC6424L), "Right rear dial step should zoom in")
            patchComp(rt3, "soc-adc", mapOf("rearDialDetents" to 0L))
            stepVirtualTicks(rt3, 60_000_000L)
            assertEquals(0x0300, readU32(rt3, 0x01CC6424L), "Left rear dial step should restore full frame")

            // 9-frame grid (rearDialDetents = -1)
            patchComp(rt3, "soc-adc", mapOf("rearDialDetents" to -1L))
            stepVirtualTicks(rt3, 180_000_000L)
            val (_, g9Dims, g9Px) = captureMain(rt3)
            dumpPng(dumpDir, "pb_grid_9.png", g9Dims, g9Px)
            val g9Green = countGreenDominant(g9Px)
            println("[PB-LCDC] Playback 9-grid: scr=0x${readU32(rt3, 0x01CC6424L).toString(16)} greenDom=$g9Green")
            assertEquals(0x030a, readU32(rt3, 0x01CC6424L))
            assertTrue(g9Green < 500, "BUG-V3-8-4: 9-frame grid must have zero olive-green stripes or solid-green thumbnails (got $g9Green)")

            // 100-frame grid (rearDialDetents = -2, triggers Q12-scaled PIC1 +0x17C=0x0F00_0F0F)
            patchComp(rt3, "soc-adc", mapOf("rearDialDetents" to -2L))
            stepVirtualTicks(rt3, 180_000_000L)
            val (_, g100Dims, g100Px) = captureMain(rt3)
            dumpPng(dumpDir, "pb_grid_100.png", g100Dims, g100Px)
            val g100Green = countGreenDominant(g100Px)
            val summary3 = rt3.owner.submit { rt3.session.relaxedFeedbackSummaryReport().orEmpty() }.join()
            println("[PB-LCDC] Playback 100-grid: scr=0x${readU32(rt3, 0x01CC6424L).toString(16)} greenDom=$g100Green summary=$summary3")
            assertTrue(g100Green < 500, "BUG-V3-8-4: 100-frame grid must have zero green artifacts (got $g100Green)")
            assertTrue(
                summary3.isEmpty() || summary3.contains("mmioUnique=0"),
                "BUG-V3-8-8: Playback multi-frame transitions must emit 0 [RELAXED/MMIO] warnings, got: $summary3",
            )
        }
    }
}
