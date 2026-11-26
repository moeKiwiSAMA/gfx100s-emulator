package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SceneDepthPort
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
import kotlin.math.abs

class AfTouchAndFilmSimLiveE2eTest {
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
        while (rt.telemetry.virtualTick < target && guard < 8000) {
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

    private fun tapButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 350L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun tapAdcButtonVirtualMs(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 350L) {
        patchComponent(rt, "soc-adc", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "soc-adc", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun tapTouch(rt: EmulatorRuntime, targetId: String, x: Int, y: Int, holdMs: Long = 60L, settleMs: Long = 500L) {
        patchComponent(rt, targetId, mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to x.toLong(), "touchY" to y.toLong()))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, targetId, mapOf("touchEnabled" to true, "touchPressed" to false, "touchX" to x.toLong(), "touchY" to y.toLong()))
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
        fun meanLuma(): Double {
            if (pixels.isEmpty()) return 0.0
            var sum = 0.0
            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                i += 4
            }
            return sum / ((pixels.size + 3) / 4)
        }

        fun countGreenBoxPixels(): Int {
            var cnt = 0
            for (p in pixels) {
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (g > 180 && g - r > 80 && g - b > 80) cnt++
            }
            return cnt
        }

        fun countRedAfPixels(): Int {
            var cnt = 0
            for (p in pixels) {
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (r > 180 && r - g > 90 && r - b > 90) cnt++
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

    private fun dumpAfHardwareState(rt: EmulatorRuntime, tag: String) {
        val report = rt.owner.submit {
            val sb = StringBuilder()
            val lens = rt.host.components().single { it.descriptor.id == "lens" }.component.state()
            val isp = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
            val vglib = rt.host.components().single { it.descriptor.id == "soc-vglib-init" }.component.state()
            val pdafDev = rt.host.ports.find("soc-isp.pdaf", MmioDevice::class.java)
            val depthPort = rt.host.ports.find(SceneDepthPort.PORT_NAME, SceneDepthPort::class.java)
            val disc = rt.session.discoveryState()
            @Suppress("UNCHECKED_CAST")
            val regions = disc["regions"] as? List<Map<String, Any?>>
            val faceRec = regions?.firstOrNull { it["id"] == "diagnostic.image-face.fff7c000" }?.get("imageFace7c000")
            val scr = run {
                val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                mem.read(0x01CC6424L, buf); buf.flip(); buf.int
            }
            sb.append("[$tag] scr=0x${scr.toString(16)} focusM=${lens["focusDistanceM"]} subjM=${lens["subjectDistanceM"]} ")
            sb.append("pdaf=${isp["pdaf"]} ")
            if (pdafDev != null) {
                val r34 = pdafDev.read(0x034, 4)
                val r40 = pdafDev.read(0x040, 4)
                val r44 = pdafDev.read(0x044, 4)
                val r84 = pdafDev.read(0x084, 4)
                val r9c = pdafDev.read(0x09C, 4)
                val ra0 = pdafDev.read(0x0A0, 4)
                sb.append("pdafRegs[34=0x${r34.toString(16)} 40=0x${r40.toString(16)} 44=0x${r44.toString(16)} 84=0x${r84.toString(16)} 9c=0x${r9c.toString(16)} a0=0x${ra0.toString(16)}] ")
            }
            if (depthPort != null) {
                val dBall = depthPort.sampleRegionDepthMeters(0.28, 0.45, 0.40, 0.65)
                val dCenter = depthPort.sampleRegionDepthMeters(0.45, 0.45, 0.55, 0.55)
                val dQrWall = depthPort.sampleRegionDepthMeters(0.60, 0.35, 0.78, 0.60)
                sb.append("depth[ball=${"%.2f".format(dBall)} center=${"%.2f".format(dCenter)} qrWall=${"%.2f".format(dQrWall)}] ")
            }
            sb.append("face=$faceRec ")
            sb.append("liveExp=${isp["liveExposure"]} ")
            sb.append("scanout=${vglib["scanout"]} ")
            val lcdcList = (vglib["lcdcFunctional"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            val lcdc1 = lcdcList?.getOrNull(1)
            if (lcdc1 != null) {
                val words = lcdc1["words"] as? List<*>
                val r004 = words?.getOrNull(1)
                val r008 = words?.getOrNull(2)
                val r030 = words?.getOrNull(0x030 / 4)
                sb.append("lcdc1[004=$r004 008=$r008 030=$r030]")
            }
            sb.toString()
        }.join()
        println(report)
    }

    @Test
    fun diagnoseAfBoxQrBallOffCenterAndFilmSimFlash(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_AF_DIAG") == "1", "Set FUJI_RUN_AF_DIAG=1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        val dumpDir = Path.of(System.getenv("FUJI_AF_DIAG_DUMP_DIR") ?: "/tmp/af-box-diag/live")

        val workRoot = tempRoot.resolve("af-diag")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            val touchTarget = rt.owner.submit {
                if (rt.host.components().any { it.descriptor.id == "rear-touch-ic" && it.config["enabled"] == true }) {
                    "rear-touch-ic"
                } else {
                    "i2c-bus"
                }
            }.join()
            println("[DIAG] touchTarget=$touchTarget dumpDir=$dumpDir")

            // Cold boot -> OOBE -> STILL LiveView (scr=0x0100)
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 16) {
                val scr = readU32(rt, 0x01CC6424L)
                println("[DIAG-OOBE] guard=$guard scr=0x${scr.toString(16)}")
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
            stepVirtualMs(rt, 800L)
            val scrIdle = readU32(rt, 0x01CC6424L)
            println("[DIAG] After OOBE scr=0x${scrIdle.toString(16)}")
            val f0 = captureDisplay(rt)
            savePng(dumpDir, "01-idle-qrball", f0)
            dumpAfHardwareState(rt, "IDLE_QRBALL")

            // 1) Tap right-side QR wall (x=720, y=380 on 1024x768) directly on default boot!
            tapTouch(rt, touchTarget, 720, 380, holdMs = 60L, settleMs = 700L)
            val f1 = captureDisplay(rt)
            savePng(dumpDir, "02-after-tap-720-380-default", f1)
            dumpAfHardwareState(rt, "AFTER_TAP_DEFAULT")

            // 2) Hold shutterHalf for 2500ms, sampling every 200ms to watch exact focus trajectory & lock/!AF!
            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            for (stepIdx in 1..12) {
                stepVirtualMs(rt, 200L)
                val fStep = captureDisplay(rt)
                val scrNow = readU32(rt, 0x01CC6424L)
                val lensF = rt.owner.submit { rt.host.components().single { it.descriptor.id == "lens" }.component.state()["focusDistanceM"] }.join()
                val pdafMap = rt.owner.submit { rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()["pdaf"] }.join()
                println("[QRWALL-AF t=${stepIdx * 200}ms] scr=0x${scrNow.toString(16)} focusM=$lensF green=${fStep.countGreenBoxPixels()} red=${fStep.countRedAfPixels()} pdaf=$pdafMap")
            }
            val f2 = captureDisplay(rt)
            savePng(dumpDir, "03-shutterhalf-after-tap-2400ms", f2)
            dumpAfHardwareState(rt, "SHUTTERHALF_AFTER_TAP_2400MS")
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepVirtualMs(rt, 400L)

            // 3) Also tap the NEAR foreground ball (x=310, y=420) and hold shutterHalf for 2000ms to compare!
            tapTouch(rt, touchTarget, 310, 420, holdMs = 60L, settleMs = 500L)
            val f3 = captureDisplay(rt)
            savePng(dumpDir, "04-after-tap-ball-310-420", f3)
            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            for (stepIdx in 1..10) {
                stepVirtualMs(rt, 200L)
                val fStep = captureDisplay(rt)
                val scrNow = readU32(rt, 0x01CC6424L)
                val lensF = rt.owner.submit { rt.host.components().single { it.descriptor.id == "lens" }.component.state()["focusDistanceM"] }.join()
                val pdafMap = rt.owner.submit { rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()["pdaf"] }.join()
                println("[BALL-AF t=${stepIdx * 200}ms] scr=0x${scrNow.toString(16)} focusM=$lensF green=${fStep.countGreenBoxPixels()} red=${fStep.countRedAfPixels()} pdaf=$pdafMap")
            }
            val f4 = captureDisplay(rt)
            savePng(dumpDir, "05-shutterhalf-ball-2000ms", f4)
            dumpAfHardwareState(rt, "SHUTTERHALF_BALL_2000MS")
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepVirtualMs(rt, 400L)

            // 4) Switch to scene://mona-lisa, enable Face/Eye AF via Fn2, check OSD & 0xFFF7C000!
            patchComponent(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?motion=off&phase=0.25", "loop" to true))
            stepVirtualMs(rt, 600L)
            tapAdcButtonVirtualMs(rt, "fn2Button", 85L, 600L)
            stepVirtualMs(rt, 1200L)
            val f5 = captureDisplay(rt)
            savePng(dumpDir, "06-monalisa-fn2-idle", f5)
            dumpAfHardwareState(rt, "MONALISA_FN2_IDLE")

            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 1500L)
            val f6 = captureDisplay(rt)
            savePng(dumpDir, "07-monalisa-fn2-shutterhalf", f6)
            println("[DIAG] f6 green=${f6.countGreenBoxPixels()} red=${f6.countRedAfPixels()} luma=${"%.1f".format(f6.meanLuma())}")
            dumpAfHardwareState(rt, "MONALISA_FN2_SHUTTERHALF")
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepVirtualMs(rt, 400L)

            // 5) Film Simulation Switch via Q-menu AND I.Q. Menu, sampling EVERY frame to catch black flashes!
            // First switch back to scene://depth-studio or qr-ball so scene is bright
            patchComponent(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://qr-ball", "loop" to true))
            stepVirtualMs(rt, 500L)

            // Open I.Q. Menu -> Row 3 (FILM SIMULATION) -> press down (PROVIA -> Velvia) -> press menu -> sample EVERY 10ms!
            tapButtonVirtualMs(rt, "menu", 85L, 450L)
            repeat(3) { tapButtonVirtualMs(rt, "down", 70L, 260L) }
            tapButtonVirtualMs(rt, "menu", 85L, 400L)
            val fSimMenu = captureDisplay(rt)
            savePng(dumpDir, "08-filmsim-menu", fSimMenu)

            // Now press DOWN to highlight Velvia, then MENU to select Velvia, then BACK/shutterHalf to LiveView,
            // capturing every frame transition at 10ms intervals!
            var lastSeq = -1L
            var minLumaSeen = 999.0
            var blackFrames = 0
            fun sampleStep(label: String, totalMs: Long, stepMs: Long = 10L) {
                var elapsed = 0L
                while (elapsed < totalMs) {
                    stepVirtualMs(rt, stepMs)
                    elapsed += stepMs
                    val fr = captureDisplay(rt)
                    if (fr.seq != lastSeq) {
                        lastSeq = fr.seq
                        val luma = fr.meanLuma()
                        val meta = rt.owner.submit {
                            val isp = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
                            val expPort = rt.host.ports.find(
                                com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort.PORT_ID,
                                com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort::class.java,
                            )
                            val expRegs = expPort?.let {
                                "imx[has=${it.hasExposureRegs} shr=${it.shr15} svr=${it.svr12} vmax=${it.vmax12} pgc=${it.pgc12} hcg=${it.hcgEnabled} dg=${it.dgain8}]"
                            } ?: "imx=null"
                            "liveExp=${isp["liveExposure"]} $expRegs"
                        }.join()
                        if (luma < minLumaSeen) minLumaSeen = luma
                        if (luma < 60.0) {
                            blackFrames++
                            if (blackFrames <= 3) savePng(dumpDir, "09-dark-${label}-seq${fr.seq}", fr)
                            println("[FILMSIM-DARK] label=$label t=${elapsed}ms seq=${fr.seq} luma=${"%.2f".format(luma)} $meta")
                        } else {
                            println("[FILMSIM-FRAME] label=$label t=${elapsed}ms seq=${fr.seq} luma=${"%.2f".format(luma)} $meta")
                        }
                    }
                }
            }

            // Highlight Velvia (live preview changes in menu or right after confirm!)
            patchComponent(rt, "buttons", mapOf("down" to true))
            sampleStep("down_press", 80L)
            patchComponent(rt, "buttons", mapOf("down" to false))
            sampleStep("down_settle", 400L)

            // Confirm Velvia
            patchComponent(rt, "buttons", mapOf("menu" to true))
            sampleStep("menu_confirm_press", 80L)
            patchComponent(rt, "buttons", mapOf("menu" to false))
            sampleStep("menu_confirm_settle", 500L)

            // Return to LiveView via back
            patchComponent(rt, "buttons", mapOf("back" to true))
            sampleStep("back_press", 80L)
            patchComponent(rt, "buttons", mapOf("back" to false))
            sampleStep("back_settle", 600L)

            // Also test Q-menu Film Sim switch (press Q -> navigate -> turn rear dial!)
            tapAdcButtonVirtualMs(rt, "qButton", 85L, 450L)
            val fQMenu = captureDisplay(rt)
            savePng(dumpDir, "10-qmenu", fQMenu)

            println("[DIAG-SUMMARY] minLumaSeen=${"%.2f".format(minLumaSeen)} blackFrames=$blackFrames")
        }
    }
}
