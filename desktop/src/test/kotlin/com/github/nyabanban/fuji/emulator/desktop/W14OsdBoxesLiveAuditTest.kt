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
 * Wave 14 live-firmware multi-display audit. Asserts hardware face detection on the Mona Lisa and
 * Milk Frog scenes, shoulder scanout and 1280x960 EVF output. Saves rear LCD frames during idle,
 * half-press, DISP cycling and MF for inspection. The green-pixel totals include ordinary AF
 * indicators anywhere on the LCD. Face and eye alignment requires a separate positional check.
 *
 * Opt-in via `FUJI_RUN_W14_OSD_AUDIT=1` (takes ~2-3 min on live ARMv7 dynarmic firmware).
 */
class W14OsdBoxesLiveAuditTest {
    private val faceRegion = "diagnostic.face.fff7c000"
    private val faceKey = "imageFace7c000"

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
        val compDir = repo.resolve("build/emulator/compoments")
        val dstCompDir = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDir)) {
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
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
        while (rt.telemetry.virtualTick < target && guard < 12_000) {
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
            rt.host.patchLatestDirect(id, settings, persistToDisk = false)
        }.join()
    }

    private fun tapButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 350L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun tapAdcButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 450L) {
        patchComponent(rt, "soc-adc", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "soc-adc", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private data class FrameCapture(val seq: Long, val width: Int, val height: Int, val pixels: IntArray) {
        fun countGreenPixels(): Int {
            var cnt = 0
            for (p in pixels) {
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (g > 175 && g - r > 75 && g - b > 75) cnt++
            }
            return cnt
        }

        fun countNonBlackPixels(): Int {
            var cnt = 0
            for (p in pixels) {
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (r > 12 || g > 12 || b > 12) cnt++
            }
            return cnt
        }

        fun countGreenInRegion(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var cnt = 0
            val cx0 = x0.coerceIn(0, width - 1)
            val cx1 = x1.coerceIn(0, width - 1)
            val cy0 = y0.coerceIn(0, height - 1)
            val cy1 = y1.coerceIn(0, height - 1)
            for (y in cy0..cy1) {
                val row = y * width
                for (x in cx0..cx1) {
                    val p = pixels[row + x]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    if (g > 175 && g - r > 75 && g - b > 75) cnt++
                }
            }
            return cnt
        }
    }

    private fun captureDisplay(rt: EmulatorRuntime, portName: String = "display.main"): FrameCapture = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        FrameCapture(display.sequence, display.width, display.height, pixels)
    }.join()

    private fun savePng(dumpDir: Path, name: String, frame: FrameCapture) {
        Files.createDirectories(dumpDir)
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        ImageIO.write(img, "png", dumpDir.resolve("$name.png").toFile())
    }

    private data class FaceDiag(val pass1: Long, val pass2: Long, val faces: Int, val cx: Float, val cy: Float)

    private fun readFaceDiag(rt: EmulatorRuntime): FaceDiag = rt.owner.submit {
        val disc = rt.session.discoveryState()
        @Suppress("UNCHECKED_CAST")
        val regions = disc["regions"] as? List<Map<String, Any?>>
        val fRec = regions?.firstOrNull { it["id"] == faceRegion }?.get(faceKey) as? Map<*, *>
        FaceDiag(
            pass1 = (fRec?.get("pass1Count") as? Number)?.toLong() ?: 0L,
            pass2 = (fRec?.get("pass2Count") as? Number)?.toLong() ?: 0L,
            faces = (fRec?.get("lastFaceCount") as? Number)?.toInt() ?: 0,
            cx = (fRec?.get("lastFaceCenterX") as? Number)?.toFloat() ?: -1f,
            cy = (fRec?.get("lastFaceCenterY") as? Number)?.toFloat() ?: -1f,
        )
    }.join()

    @Test
    fun auditFaceEyeAfBoxesEvfShoulderDispAndMf(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_W14_OSD_AUDIT") == "1", "Set FUJI_RUN_W14_OSD_AUDIT=1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        val dumpDir = Path.of(System.getenv("FUJI_W14_OSD_DUMP_DIR") ?: "/tmp/w14-osd-audit/full")

        val workRoot = tempRoot.resolve("w14-osd-audit")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // Switch immediately to scene://mona-lisa (centered pose phase=0.0) before OOBE completes
            patchComponent(
                rt,
                "cmos",
                mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?motion=off&phase=0.0", "loop" to true),
            )
            patchComponent(rt, "lens", mapOf("focusDistanceM" to 1.55))

            // Boot through OOBE -> STILL LiveView (0x0100)
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 18) {
                val scr = readU32(rt, 0x01CC6424L)
                if (scr == 0x0423) {
                    tapButtonVirtualMs(rt, "down", 70L, 250L)
                    tapButtonVirtualMs(rt, "down", 70L, 250L)
                    tapButtonVirtualMs(rt, "menu", 85L, 400L)
                } else if (scr == 0x0421 || scr == 0x0422 || scr == 0x0482 || scr in 0x0501..0x0505) {
                    tapButtonVirtualMs(rt, "menu", 85L, 400L)
                } else {
                    tapButtonVirtualMs(rt, "back", 85L, 400L)
                }
                guard++
            }
            stepVirtualMs(rt, 600L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView 0x0100")

            // 1. Enable FACE ON / EYE AUTO via Fn2 and wait for AE + 0xFFF7C000 detection on Mona Lisa
            tapAdcButtonVirtualMs(rt, "fn2Button", 85L, 500L)
            var monaDetected = FaceDiag(0, 0, 0, -1f, -1f)
            for (step in 0 until 16) {
                stepVirtualMs(rt, 350L)
                val d = readFaceDiag(rt)
                println("[W14-MONA] step=$step $d")
                if (d.faces > 0 && d.pass2 > 0) {
                    monaDetected = d
                    if (step >= 3) break
                }
            }
            val monaIdle = captureDisplay(rt)
            savePng(dumpDir, "01-mona-face-eye-idle", monaIdle)

            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 1400L)
            val monaLock = captureDisplay(rt)
            savePng(dumpDir, "02-mona-face-eye-lock", monaLock)
            println("[W14-MONA] lock seq=${monaLock.seq} greenTotal=${monaLock.countGreenPixels()} faceDiag=$monaDetected")
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepVirtualMs(rt, 350L)

            // Repeat the face-engine and shutter sequence on the Milk Frog scene.
            patchComponent(
                rt,
                "cmos",
                mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog?motion=off&phase=0.45", "loop" to true),
            )
            patchComponent(rt, "lens", mapOf("focusDistanceM" to 1.55))
            var frogDetected = FaceDiag(0, 0, 0, -1f, -1f)
            for (step in 0 until 14) {
                stepVirtualMs(rt, 350L)
                val d = readFaceDiag(rt)
                println("[W14-FROG] step=$step $d")
                if (d.faces > 0 && d.pass2 > monaDetected.pass2) {
                    frogDetected = d
                    if (step >= 3) break
                }
            }
            val frogIdle = captureDisplay(rt)
            savePng(dumpDir, "03-milkfrog-face-eye-idle", frogIdle)

            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 1400L)
            val frogLock = captureDisplay(rt)
            savePng(dumpDir, "04-milkfrog-face-eye-lock", frogLock)
            println("[W14-FROG] lock seq=${frogLock.seq} greenTotal=${frogLock.countGreenPixels()} faceDiag=$frogDetected")
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepVirtualMs(rt, 350L)

            // 3. DISP/BACK cycle (check overlay modes) + MF focus scale / peaking
            tapButtonVirtualMs(rt, "back", 85L, 450L)
            val disp1 = captureDisplay(rt)
            savePng(dumpDir, "05-disp-cycle-1", disp1)
            tapButtonVirtualMs(rt, "back", 85L, 450L)
            val disp2 = captureDisplay(rt)
            savePng(dumpDir, "06-disp-cycle-2", disp2)
            tapButtonVirtualMs(rt, "back", 85L, 450L)

            // MF mode check
            patchComponent(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            stepVirtualMs(rt, 500L)
            val mfFrame = captureDisplay(rt)
            savePng(dumpDir, "07-mf-overlay", mfFrame)
            patchComponent(rt, "soc-gpio", mapOf("focusMode" to "AF-S"))
            stepVirtualMs(rt, 400L)

            // 4. EVF 1280x960 (LCDC0) via eye sensor near + Shoulder Sub-LCD (304x230)
            val shoulder = captureDisplay(rt, "display.shoulder")
            savePng(dumpDir, "08-shoulder-sublcd", shoulder)
            println("[W14-SHOULDER] ${shoulder.width}x${shoulder.height} seq=${shoulder.seq} nonBlack=${shoulder.countNonBlackPixels()}")

            val eyeTarget = rt.owner.submit {
                if (rt.host.components().any { it.descriptor.id == "tmd2772" }) "tmd2772" else "i2c-bus"
            }.join()
            patchComponent(rt, eyeTarget, mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 1200L)
            val evfFrame = captureDisplay(rt, "display.evf")
            savePng(dumpDir, "09-evf-1280x960", evfFrame)
            println("[W14-EVF] target=$eyeTarget ${evfFrame.width}x${evfFrame.height} seq=${evfFrame.seq} nonBlack=${evfFrame.countNonBlackPixels()}")
            patchComponent(rt, eyeTarget, mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 600L)

            val summary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[W14-AUDIT-SUMMARY] $summary")
            assertTrue(monaDetected.faces > 0, "Expected 0xFFF7C000 face detection on scene://mona-lisa")
            assertTrue(frogDetected.faces > 0, "Expected 0xFFF7C000 face detection on scene://milk-frog")
            assertTrue(shoulder.seq > 0L && shoulder.countNonBlackPixels() > 500, "Expected live shoulder sub-LCD output")
            assertEquals(1280, evfFrame.width, "Expected 1280px EVF width")
            assertEquals(960, evfFrame.height, "Expected 960px EVF height")
            assertTrue(evfFrame.seq > 0L && evfFrame.countNonBlackPixels() > 20_000, "Expected live EVF 1280x960 render")
        }
    }
}
