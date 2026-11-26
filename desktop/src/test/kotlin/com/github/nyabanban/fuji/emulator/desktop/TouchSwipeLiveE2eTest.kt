package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
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
 * Live `GXUP0004.DAT` (Dynarmic V2) end-to-end verification of all 4 directional Rear LCD
 * capacitive touch swipes (`UP / DOWN / LEFT / RIGHT` -> `T-Fn1..4`, incl. Film Simulation)
 * through `rear-touch-ic` (`I2C2 @ 0x55`, `GIC IRQ 153`) and firmware task `T91`.
 *
 * Env-gated with `FUJI_RUN_TOUCH_SWIPE_LIVE=1` so fast unit-test runs stay lean.
 */
class TouchSwipeLiveE2eTest {
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
            val expectedJars = Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
            val emuYaml = repo.resolve("tools/release/emulator-release-linux.yaml")
            if (Files.isRegularFile(emuYaml)) {
                Files.copy(emuYaml, workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
            }
            val catalogJson = repo.resolve("catalog/compiled/catalog.json")
            if (Files.isRegularFile(catalogJson)) {
                Files.copy(catalogJson, workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
            }
            val compConfigs = repo.resolve("tools/release/component-configs")
            if (Files.isDirectory(compConfigs)) {
                copyDirectory(compConfigs, workRoot.resolve("configs/components"))
            }
        }
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
        while (rt.telemetry.virtualTick < target && guard < 6000) {
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

    private fun tapButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 220L) {
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

    private data class FrameCapture(val seq: Long, val width: Int, val height: Int, val pixels: IntArray)

    private fun captureDisplay(rt: EmulatorRuntime, portName: String): FrameCapture = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        FrameCapture(display.sequence, display.width, display.height, pixels)
    }.join()

    private fun countDiff(a: FrameCapture, b: FrameCapture): Int {
        var diff = 0
        val n = minOf(a.pixels.size, b.pixels.size)
        for (i in 0 until n) {
            if (a.pixels[i] != b.pixels[i]) diff++
        }
        return diff
    }

    private fun maybeSavePng(dumpDir: Path?, name: String, frame: FrameCapture) {
        if (dumpDir == null || frame.width <= 0 || frame.height <= 0) return
        Files.createDirectories(dumpDir)
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        ImageIO.write(img, "png", dumpDir.resolve("$name.png").toFile())
    }

    private fun sendTouch(rt: EmulatorRuntime, targetId: String, x: Int, y: Int, pressed: Boolean) {
        patchComponent(
            rt,
            targetId,
            mapOf(
                "touchEnabled" to true,
                "touchPressed" to pressed,
                "touchX" to x.toLong(),
                "touchY" to y.toLong(),
            ),
        )
    }

    private fun performTouchSwipe(
        rt: EmulatorRuntime,
        targetId: String,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        steps: Int = 4,
        stepMs: Long = 20L,
        settleMs: Long = 700L,
    ): String {
        val states = mutableListOf<Int>()
        sendTouch(rt, targetId, x1, y1, true)
        stepVirtualMs(rt, stepMs)
        states += readU8(rt, 0x018A4098L)
        for (i in 1..steps) {
            val sx = x1 + ((x2 - x1) * i) / steps
            val sy = y1 + ((y2 - y1) * i) / steps
            sendTouch(rt, targetId, sx, sy, true)
            stepVirtualMs(rt, stepMs)
            states += readU8(rt, 0x018A4098L)
        }
        sendTouch(rt, targetId, x2, y2, false)
        stepVirtualMs(rt, settleMs)
        val endState = readU8(rt, 0x018A4098L)
        val lastEv = readU32(rt, 0x01CB6B70L)
        return "t91States=$states->end=$endState lastEv=0x${lastEv.toString(16)}"
    }

    @Test
    fun liveFirmwareRearLcdFourDirectionTouchSwipesTriggerTFnMenus(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_TOUCH_SWIPE_LIVE") == "1",
            "Set FUJI_RUN_TOUCH_SWIPE_LIVE=1 to run live GXUP0004.DAT 4-direction touch swipe E2E",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires staged release at $stagedRoot",
        )
        val dumpDir = System.getenv("FUJI_TOUCH_SWIPE_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        val workRoot = tempRoot.resolve("touch-swipe-e2e")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        // Add a visually distinct second JPEG (DSCF0002.JPG) next to pre-populated DSCF0001.JPG
        // in virtual SD Slot 1 so Playback mode (0x0300) has 2/2 distinct photos to swipe between.
        val dcimDir = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI")
        if (Files.isDirectory(dcimDir)) {
            val secondImg = BufferedImage(592, 592, BufferedImage.TYPE_INT_RGB)
            val bars = intArrayOf(0xD93829, 0x2A9D8F, 0x264653, 0xE9C46A, 0xF4A261, 0x457B9D, 0x8338EC, 0x1D3557)
            for (y in 0 until 592) {
                for (x in 0 until 592) {
                    secondImg.setRGB(x, y, bars[(x * bars.size) / 592])
                }
            }
            ImageIO.write(secondImg, "jpg", dcimDir.resolve("DSCF0002.JPG").toFile())
        }

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            val touchTarget = rt.owner.submit {
                val hasRearTouch = rt.host.components().any {
                    it.descriptor.id == "rear-touch-ic" && it.config["enabled"] == true
                }
                if (hasRearTouch) "rear-touch-ic" else "i2c-bus"
            }.join()
            println("[TOUCH-SWIPE] touchTarget=$touchTarget")

            // Boot cold -> OOBE -> STILL LiveView (scr=0x0100)
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 12) {
                val scr = readU32(rt, 0x01CC6424L)
                if (scr in 0x0501..0x0505) {
                    tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 350L)
                } else {
                    tapButtonVirtualMs(rt, "back", holdMs = 85L, settleMs = 350L)
                }
                guard++
            }
            stepVirtualMs(rt, 500L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView scr=0x0100 after OOBE")

            // Wait for initial LiveView frame draw
            var readyGuard = 0
            while (readyGuard < 30) {
                val m = captureDisplay(rt, "display.main")
                if (m.seq >= 20L) break
                stepVirtualMs(rt, 250L)
                readyGuard++
            }
            stepVirtualMs(rt, 400L)

            // Inspect live runtime T91 gesture parameters before enabling TOUCH FUNCTION
            val paramsDefault = (0 until 8).map { readU32(rt, 0x01813180L + it * 4L) }
            println("[TOUCH-SWIPE] default 0x01813180[0..7] = $paramsDefault")
            assertEquals(0, paramsDefault[3], "GFX100S factory default TOUCH FUNCTION is OFF (param[3]=0)")

            // Navigate real camera menu: MENU -> SETUP (Wrench) -> POWER MANAGEMENT -> UP x2 (TOUCH SCREEN SETTING) -> TOUCH FUNCTION = ON
            tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 450L)
            tapButtonVirtualMs(rt, "left", holdMs = 85L, settleMs = 300L)
            repeat(4) { tapButtonVirtualMs(rt, "down", holdMs = 70L, settleMs = 260L) }
            tapButtonVirtualMs(rt, "right", holdMs = 85L, settleMs = 350L)
            repeat(4) { tapButtonVirtualMs(rt, "down", holdMs = 70L, settleMs = 260L) }
            tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 450L)
            repeat(2) { tapButtonVirtualMs(rt, "up", holdMs = 70L, settleMs = 300L) }
            tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 450L)
            repeat(2) { tapButtonVirtualMs(rt, "down", holdMs = 70L, settleMs = 260L) }
            tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 350L)
            tapButtonVirtualMs(rt, "up", holdMs = 70L, settleMs = 260L)
            tapButtonVirtualMs(rt, "menu", holdMs = 85L, settleMs = 350L)

            // Return to STILL LiveView (scr=0x0100) via DISP/BACK
            var backGuard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && backGuard < 6) {
                tapButtonVirtualMs(rt, "back", holdMs = 85L, settleMs = 400L)
                backGuard++
            }
            stepVirtualMs(rt, 400L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected return to STILL LiveView scr=0x0100")

            val paramsEnabled = (0 until 8).map { readU32(rt, 0x01813180L + it * 4L) }
            println("[TOUCH-SWIPE] enabled 0x01813180[0..7] = $paramsEnabled")
            assertEquals(260, paramsEnabled[3], "Enabling TOUCH FUNCTION=ON must activate 260 ms T91 flick window (NV 0x11B6B)")

            val baseFrame = captureDisplay(rt, "display.main")
            maybeSavePng(dumpDir, "00-baseline-liveview", baseFrame)

            data class SwipeCase(val name: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int, val expectedScr: Int)
            val cases = listOf(
                SwipeCase("UP", 512, 560, 512, 180, 0x015b),    // T-Fn1: RGB Live Histogram
                SwipeCase("DOWN", 512, 180, 512, 560, 0x015c),  // T-Fn2: 3D Electronic Level
                SwipeCase("LEFT", 760, 384, 240, 384, 0x0400),  // T-Fn3: Film Simulation selector
                SwipeCase("RIGHT", 240, 384, 760, 384, 0x0400), // T-Fn4: White Balance selector
            )

            // 1. Verify all 4 directional LiveView T-Fn1..4 swipes using the universal default (4 steps x 20ms = 100ms < 260ms)
            for ((idx, c) in cases.withIndex()) {
                val scrBefore = readU32(rt, 0x01CC6424L)
                val before = captureDisplay(rt, "display.main")
                val trace = performTouchSwipe(rt, touchTarget, c.x1, c.y1, c.x2, c.y2, steps = 4, stepMs = 20L, settleMs = 700L)
                val scrAfter = readU32(rt, 0x01CC6424L)
                val after = captureDisplay(rt, "display.main")
                val diff = countDiff(before, after)
                println("[TOUCH-SWIPE][FAST_${c.name}] scr=0x${scrBefore.toString(16)}->0x${scrAfter.toString(16)} diff=$diff $trace")
                maybeSavePng(dumpDir, "0${idx + 1}-fast-${c.name.lowercase()}", after)
                assertEquals(c.expectedScr, scrAfter, "Swipe ${c.name} must open expected T-Fn screen 0x${c.expectedScr.toString(16)}")
                assertTrue(diff > 40_000, "Swipe ${c.name} must visibly render its T-Fn overlay/menu (diff=$diff)")
                tapButtonVirtualMs(rt, "back", holdMs = 85L, settleMs = 600L)
                val scrBack = readU32(rt, 0x01CC6424L)
                println("[TOUCH-SWIPE][FAST_${c.name}_BACK] scr=0x${scrBack.toString(16)}")
                assertTrue(scrBack == 0x0100 || scrBack == 0x0102, "DISP/BACK must return to STILL LiveView after ${c.name}, got 0x${scrBack.toString(16)}")
            }

            // 2. Verify slow drag exceeding 260ms virtual time authentically transitions state 4 -> 9 (DRAG, no T-Fn)
            val slowScrBefore = readU32(rt, 0x01CC6424L)
            val slowTrace = performTouchSwipe(rt, touchTarget, 512, 560, 512, 180, steps = 3, stepMs = 80L, settleMs = 700L)
            val slowScrAfter = readU32(rt, 0x01CC6424L)
            println("[TOUCH-SWIPE][SLOW_UP_320ms] scr=0x${slowScrBefore.toString(16)}->0x${slowScrAfter.toString(16)} $slowTrace")
            assertEquals(slowScrBefore, slowScrAfter, "Slow 320ms drag (>260ms param[3]) must stay in LiveView instead of firing T-Fn")

            // 3. Verify Playback mode touch swipe (PLAY -> scr=0x0300 with 2/2 photos -> swipe RIGHT & LEFT round-trip)
            tapButtonVirtualMs(rt, "play", holdMs = 85L, settleMs = 2600L)
            val playScr = readU32(rt, 0x01CC6424L)
            val playParams = (0 until 8).map { readU32(rt, 0x01813180L + it * 4L) }
            val playFrame0 = captureDisplay(rt, "display.main")
            maybeSavePng(dumpDir, "05-play-frame0", playFrame0)
            println("[TOUCH-SWIPE][PLAY_ENTER] scr=0x${playScr.toString(16)} params=$playParams")
            assertEquals(0x0300, playScr, "PLAY button must enter Playback single-frame screen scr=0x0300")

            // Playback opens on newest photo (2/2 = 100-0002); swipe RIGHT -> previous (1/2 = 100-0001),
            // then swipe LEFT -> next (2/2 = 100-0002) using the same universal 4 steps x 20ms (=100ms, 390px post-m1)
            // default and verify exact bit-identical round-trip!
            val playRightTrace = performTouchSwipe(rt, touchTarget, 240, 384, 760, 384, steps = 4, stepMs = 20L, settleMs = 1000L)
            val playFrame1 = captureDisplay(rt, "display.main")
            val playRightDiff = countDiff(playFrame0, playFrame1)
            maybeSavePng(dumpDir, "06-play-after-right-1of2", playFrame1)
            println("[TOUCH-SWIPE][PLAY_RIGHT_2to1] diff=$playRightDiff $playRightTrace")

            val playLeftTrace = performTouchSwipe(rt, touchTarget, 760, 384, 240, 384, steps = 4, stepMs = 20L, settleMs = 1000L)
            val playFrame2 = captureDisplay(rt, "display.main")
            val playLeftDiff = countDiff(playFrame1, playFrame2)
            val playRoundTripDiff = countDiff(playFrame0, playFrame2)
            maybeSavePng(dumpDir, "07-play-after-left-2of2", playFrame2)
            println("[TOUCH-SWIPE][PLAY_LEFT_1to2] diff=$playLeftDiff roundTripDiff=$playRoundTripDiff $playLeftTrace")
            assertTrue(playRightDiff > 50_000 && playLeftDiff > 50_000, "Playback RIGHT (2/2->1/2) and LEFT (1/2->2/2) touch swipes must both browse photos (rightDiff=$playRightDiff, leftDiff=$playLeftDiff)")
            assertEquals(0, playRoundTripDiff, "Playback RIGHT then LEFT touch swipe must return identically to 2/2 (100-0002)")

            val relaxed = rt.owner.submit {
                @Suppress("UNCHECKED_CAST")
                rt.session.state()["relaxedFallback"] as Map<String, Any?>
            }.join()
            println("[TOUCH-SWIPE] relaxedFallback=$relaxed")
            assertEquals(0L, (relaxed["deviceFallbacks"] as Number).toLong())
            assertEquals(0L, (relaxed["discoveryFallbacks"] as Number).toLong())
            assertEquals(0L, (relaxed["flashRefusals"] as Number).toLong())
            assertTrue(true)
        }
    }
}
