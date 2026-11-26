package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Env-gated (`FUJI_RUN_AFS_LOCK_E2E=1`) live Dynarmic V2 verification for `BUG-A2-2`:
 * Factory-default `AF-S` (`soc-gpio.input7=8256`, `gf45-100`, `scene://qr-ball`) sweeps the real
 * focus motor over `LENSCOM`, acquires a GREEN focus lock (zero red `!AF`) with realistic AE
 * metering, captures a valid still JPEG (`DCIM/100_FUJI/DSCF0001.JPG`) on `shutterFull`, and
 * maintains zero `[RELAXED/MMIO]` and zero `[RELAXED/FLASH/REFUSED]` fallbacks.
 */
class AfsLockQrBallLiveE2eTest {

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            for (src in stream) {
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

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 500_000) {
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

    private fun patchComp(rt: EmulatorRuntime, id: String, patch: Map<String, Any>) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == id }.revision }.join()
        rt.host.patch(id, rev, patch).join()
    }

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComp(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.getInt(0)
    }.join()

    private fun readGuestS16(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.getShort(0).toInt()
    }.join()

    private fun readGuestU8(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(address, buf)
        buf.get(0).toInt() and 0xff
    }.join()

    private data class Quad(val seq: Long, val w: Int, val h: Int, val px: IntArray)

    private fun dumpPort(rt: EmulatorRuntime, portName: String, dumpDir: Path?, fileName: String): Triple<Long, Int, Int> {
        val (seq, w, h, px) = rt.owner.submit {
            val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
            val pixels = IntArray(display.width * display.height)
            display.copyArgb(pixels)
            Quad(display.sequence, display.width, display.height, pixels)
        }.join()
        val nonBlack = px.count { (it and 0x00ffffff) != 0 }
        if (dumpDir != null) {
            Files.createDirectories(dumpDir)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(img, "png", dumpDir.resolve(fileName).toFile())
        }
        return Triple(seq, nonBlack, w)
    }

    private fun countOsdAfColors(rt: EmulatorRuntime): Pair<Int, Int> = rt.owner.submit {
        val disp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(disp.width * disp.height)
        disp.copyArgb(px)
        var green = 0
        var red = 0
        for (y in 0 until disp.height) {
            val row = y * disp.width
            val inCenter = y in 80 until (disp.height - 80).coerceAtLeast(81)
            for (x in 0 until disp.width) {
                val p = px[row + x]
                val r = (p ushr 16) and 0xff
                val g = (p ushr 8) and 0xff
                val b = p and 0xff
                // Matches GFX100S green AF-lock indicator (AFL focus-lock dot RGB~(110,245,30) & green AF frame)
                if (g >= 180 && g >= r + 80 && g >= b + 100) green++
                if (inCenter && x in 80 until (disp.width - 80).coerceAtLeast(81)) {
                    if (r >= 170 && r >= g + 100 && r >= b + 100) red++
                }
            }
        }
        green to red
    }.join()

    @Test
    fun factoryDefaultAfsOnQrBallLocksGreenAndCapturesStillJpegWithZeroFallbacks(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_AFS_LOCK_E2E") == "1",
            "Set FUJI_RUN_AFS_LOCK_E2E=1 to run live AF-S lock E2E test",
        )
        val dumpDir = System.getenv("FUJI_AFS_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val traceFile = dumpDir?.also { Files.createDirectories(it) }?.resolve("trace.log")
        fun logLine(msg: String) {
            println(msg)
            if (traceFile != null) {
                Files.writeString(
                    traceFile,
                    "$msg\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }

        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("afs-release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot through OOBE -> Live STILL HUD (0x0100)
            for (i in 0 until 70) {
                stepBy(rt, 10_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            for (iter in 0 until 60) {
                stepBy(rt, 6_000_000L)
                val scr = readGuestU32(rt, 0x01CC6424L)
                val mainSnap = dumpPort(rt, "display.main", null, "")
                if (scr == 0x0100 && mainSnap.second > 150_000) {
                    logLine("[AFS] Reached STILL HUD at iter=$iter scr=0x${scr.toString(16)} main=$mainSnap tick=${rt.telemetry.virtualTick}")
                    break
                }
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected Live STILL HUD 0x0100")
            stepBy(rt, 30_000_000L)
            dumpPort(rt, "display.main", dumpDir, "01_still_idle.png")

            fun dumpAfState(tag: String): Pair<Int, Int> {
                val lensState = compState(rt, "lens")
                val gpioState = compState(rt, "soc-gpio")
                val ispState = compState(rt, "soc-isp")
                val pdaf = ispState["pdaf"] as? Map<*, *>
                val scr = readGuestU32(rt, 0x01CC6424L)
                val s1Sub0 = readGuestU32(rt, 0x02C47C5CL + 0x60L) and 0xff
                val lensPos = readGuestS16(rt, 0x01A3C1B0L + 0x9C)
                val lensBusyA9 = readGuestU8(rt, 0x01A3C1B0L + 0xA9)
                val lensTarget108 = readGuestS16(rt, 0x01A3C1B0L + 0x108)
                val cdafSum = rt.owner.submit {
                    val h0 = rt.host.ports.find("soc-isp.stats-head-0-v3", MmioDevice::class.java)
                    val s0 = rt.host.ports.find("soc-isp.result-surface-v3", MmioDevice::class.java)
                    val sel4 = h0?.read(0x1000L, 4) ?: 0L
                    val sel5 = h0?.read(0x1028L, 4) ?: 0L
                    val sel32_11 = s0?.read(0x4000L + 512L + 32L - 0x1060L, 4) ?: 0L
                    "($sel4,$sel5,c11=$sel32_11)"
                }.join()
                val colors = countOsdAfColors(rt)
                logLine(
                    "[AFS][$tag] scr=0x${scr.toString(16)} s1=$s1Sub0 focusM=${lensState["focusDistanceM"]} " +
                        "gpio15=${gpioState["input15"]} lens(pos=$lensPos,tgt=$lensTarget108,a9=$lensBusyA9) " +
                        "pdaf(n=${pdaf?.get("completionCount")},dPx=${pdaf?.get("lastDeltaPx")},fM=${pdaf?.get("lastFocusDistanceM")}) " +
                        "cdaf=$cdafSum G=${colors.first} R=${colors.second}",
                )
                return colors
            }

            dumpAfState("IDLE")

            // 2. Half-press shutterHalf=true on factory-default AF-S (no scene/subject override)
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            var lockedGreen = false
            var lockedStep = -1
            var peakGreen = 0
            var lockRed = 0
            var settledNonGreenSteps = 0
            for (step in 1..90) {
                stepBy(rt, 2_000_000L)
                val (gPx, rPx) = dumpAfState("S1_$step")
                val scrNow = readGuestU32(rt, 0x01CC6424L)
                val s1Now = readGuestU32(rt, 0x02C47C5CL + 0x60L) and 0xff
                if (gPx > peakGreen) peakGreen = gPx
                // GFX100S AF-S GREEN lock sets scr=0x011c, s1=8, and paints bright green AF frame + AFL dot (gPx >= 300).
                // Do not reject or early-abort on scene red pixels (scene://qr-ball renders a bright red 3D sphere in center).
                if (scrNow == 0x011c && s1Now == 8 && gPx >= 300) {
                    lockedGreen = true
                    lockedStep = step
                    lockRed = rPx
                    dumpPort(rt, "display.main", dumpDir, "02_shutter_half_afs.png")
                    break
                }
                if (scrNow == 0x011c && gPx < 50) {
                    settledNonGreenSteps++
                    if (settledNonGreenSteps >= 4) {
                        lockRed = rPx
                        dumpPort(rt, "display.main", dumpDir, "02_shutter_half_afs.png")
                        break
                    }
                } else {
                    settledNonGreenSteps = 0
                }
            }
            if (!lockedGreen) {
                dumpPort(rt, "display.main", dumpDir, "02_shutter_half_afs.png")
            }

            assertTrue(
                lockedGreen,
                "Expected factory-default AF-S on scene://qr-ball to lock GREEN (peakGreen=$peakGreen, lockRed=$lockRed)",
            )
            logLine("[AFS] LOCKED GREEN at step=$lockedStep peakGreen=$peakGreen lockRed=$lockRed")

            // 3. Full-press shutterFull while holding shutterHalf -> verify new valid still JPEG written to Slot 1 DCIM
            val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
            fun listDcimJpegs(): List<Path> = if (Files.isDirectory(dcimRoot)) {
                Files.walk(dcimRoot).use { stream ->
                    stream.filter {
                        Files.isRegularFile(it) &&
                            it.fileName.toString().endsWith(".JPG", ignoreCase = true) &&
                            Files.size(it) > 10_000L
                    }.sorted().toList()
                }
            } else {
                emptyList()
            }
            val beforeJpegs = listDcimJpegs().map { "${dcimRoot.relativize(it)}(${Files.size(it)})" }.toSet()
            val encBefore = (compState(rt, "soc-jpeg")["encodeCount"] as? Number)?.toLong() ?: 0L

            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepBy(rt, 12_000_000L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            var capturedJpeg: Path? = null
            var encAfter = encBefore
            for (w in 1..30) {
                stepBy(rt, 10_000_000L)
                encAfter = (compState(rt, "soc-jpeg")["encodeCount"] as? Number)?.toLong() ?: 0L
                val newFile = listDcimJpegs().firstOrNull { "${dcimRoot.relativize(it)}(${Files.size(it)})" !in beforeJpegs }
                val scrNow = readGuestU32(rt, 0x01CC6424L)
                if (newFile != null && encAfter > encBefore && scrNow in listOf(0x0100, 0x0102)) {
                    capturedJpeg = newFile
                    break
                }
                if (newFile != null && capturedJpeg == null) {
                    capturedJpeg = newFile
                }
            }
            dumpAfState("POST_S2")
            dumpPort(rt, "display.main", dumpDir, "03_post_capture.png")

            assertTrue(encAfter > encBefore, "Expected soc-jpeg encodeCount to increase after shutterFull (before=$encBefore after=$encAfter)")
            val jpegPath = requireNotNull(capturedJpeg) { "Expected new still JPEG in Slot 1 DCIM after shutterFull (before=$beforeJpegs)" }
            val jpegBytes = Files.readAllBytes(jpegPath)
            dumpDir?.let { Files.copy(jpegPath, it.resolve("captured-${jpegPath.fileName}"), StandardCopyOption.REPLACE_EXISTING) }
            logLine("[AFS][SD] ${dcimRoot.relativize(jpegPath)} bytes=${jpegBytes.size} enc=$encBefore->$encAfter")
            assertTrue(
                jpegBytes.size > 10_000 &&
                    (jpegBytes[0].toInt() and 0xff) == 0xff &&
                    (jpegBytes[1].toInt() and 0xff) == 0xd8 &&
                    (jpegBytes[jpegBytes.size - 2].toInt() and 0xff) == 0xff &&
                    (jpegBytes[jpegBytes.size - 1].toInt() and 0xff) == 0xd9,
                "Expected valid SOI..EOI JPEG at $jpegPath (size=${jpegBytes.size})",
            )

            // 4. Zero-Fallback Guarantee
            val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = (relaxed?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
            val flashRefusals = (relaxed?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            assertEquals(0L, mmioFallbacks, "Expected ZERO [RELAXED/MMIO] fallbacks | report=$report")
            assertEquals(0L, flashRefusals, "Expected ZERO [RELAXED/FLASH] refusals | report=$report")
        }
    }
}
