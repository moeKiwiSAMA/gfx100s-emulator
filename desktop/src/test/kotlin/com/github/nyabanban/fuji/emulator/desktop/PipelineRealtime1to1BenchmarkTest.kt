package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RealTimePacer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Profiler-driven 1:1 real-time clock & pipeline benchmark (`FUJI_RUN_PERF_1TO1=1` / `-Dfuji.perf.run1to1=true`).
 *
 * Profiles unmodified stock GFX100S 2.13 across Still LiveView (LCD & EVF), Movie Standby & Active Recording
 * (FHD 59.94p), Menu, Playback, and RealTimePacer 1.00x tracking using [AsyncPerfProfiler].
 */
class PipelineRealtime1to1BenchmarkTest {

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
        val compDst = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDst)) {
            val compDirs = listOf("components", "lenses").map { repo.resolve(it) }.filter { Files.isDirectory(it) }
            for (parent in compDirs) {
                Files.list(parent).use { mods ->
                    mods.forEach { mod ->
                        val libs = mod.resolve("build/libs")
                        if (Files.isDirectory(libs)) {
                            Files.list(libs).use { jars ->
                                jars.filter { it.toString().endsWith(".jar") && !it.fileName.toString().contains("-sources") }
                                    .forEach { jar ->
                                        val dstJar = compDst.resolve(jar.fileName)
                                        if (Files.isRegularFile(dstJar)) {
                                            Files.copy(jar, dstJar, StandardCopyOption.REPLACE_EXISTING)
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        val nativeDst = workRoot.resolve("runtime/native")
        val nativeLibs = listOf(
            repo.resolve(".tools/dynarmic-v1/build-linux/libfuji_dynarmic.so"),
            repo.resolve(".tools/audio-bridge/build-linux/libfuji_audio.so"),
            repo.resolve(".tools/render-bridge/build-linux/libfuji_render.so"),
            repo.resolve(".tools/vulkan-optics/build-linux/libfuji_vulkan_optics.so"),
            repo.resolve(".tools/isp-simd/build-linux/libfuji_isp_simd.so"),
            repo.resolve(".tools/codec-bridge/build-linux/libfuji_codec.so"),
            repo.resolve(".tools/gamepad-bridge/build-linux/libfuji_gamepad.so"),
        )
        if (Files.isDirectory(nativeDst)) {
            for (lib in nativeLibs) {
                if (Files.isRegularFile(lib)) {
                    Files.copy(lib, nativeDst.resolve(lib.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = rt.host.ports.find("cpu.memory", DmaMemory::class.java) ?: return@submit -1
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.getInt(0)
    }.join()

    private fun patchComp(rt: EmulatorRuntime, compId: String, patch: Map<String, Any>) {
        rt.owner.submit {
            rt.host.patchLatestDirect(compId, patch, persistToDisk = false)
        }.join()
    }

    private fun compState(rt: EmulatorRuntime, compId: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == compId }.component.state()
    }.join()

    private fun stepVirtualMs(rt: EmulatorRuntime, virtualMs: Long, maxWallMs: Long = 60_000L) {
        val targetTick = rt.telemetry.virtualTick + virtualMs * 54_000L
        val deadline = System.currentTimeMillis() + maxWallMs
        while (rt.telemetry.virtualTick < targetTick && System.currentTimeMillis() < deadline) {
            val ok = rt.owner.submit {
                var i = 0
                var cont = true
                while (cont && i < 2048 && rt.telemetry.virtualTick < targetTick) {
                    cont = rt.session.stepFast()
                    i++
                }
                cont
            }.join()
            if (!ok) break
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdMs: Long = 95L, settleMs: Long = 450L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComp(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun profileWindow(
        rt: EmulatorRuntime,
        label: String,
        wallSeconds: Double,
        paced: Boolean = false,
    ): Map<String, Any?> {
        val maliBefore = compState(rt, "soc-isp")["maliV61"] as? Map<*, *>
        val framesBefore = (maliBefore?.get("framesEncodedCount") as? Number)?.toLong() ?: 0L
        val bytesBefore = (maliBefore?.get("bytesEncodedCount") as? Number)?.toLong() ?: 0L
        val decodedBefore = (maliBefore?.get("framesDecodedCount") as? Number)?.toLong() ?: 0L
        val mainSeqBefore = rt.owner.submit {
            rt.host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
        }.join()
        val evfSeqBefore = rt.owner.submit {
            rt.host.ports.find("display.evf", DisplayPort::class.java)?.sequence ?: 0L
        }.join()

        val localPacer = if (paced) RealTimePacer(ticksPerRealSecond = 54_000_000.0, targetRatio = 1.0) else null
        try {
            localPacer?.reanchor(rt.telemetry.virtualTick)
            rt.profiler.start(intervalMs = 5L)
            val startNs = System.nanoTime()
            val deadlineNs = startNs + (wallSeconds * 1_000_000_000.0).toLong()
            while (System.nanoTime() < deadlineNs) {
                val keep = rt.owner.submit {
                    var i = 0
                    var ok = true
                    val maxSteps = if (paced) 256 else 2048
                    while (ok && i < maxSteps && System.nanoTime() < deadlineNs) {
                        ok = rt.session.stepFast()
                        i++
                        if (localPacer != null) {
                            if (rt.session.lastStepAllCoresWfi) {
                                localPacer.paceWfiIdle(rt.telemetry.virtualTick) { System.nanoTime() >= deadlineNs }
                            } else {
                                localPacer.pace(rt.telemetry.virtualTick) { System.nanoTime() >= deadlineNs }
                            }
                        }
                    }
                    ok
                }.join()
                if (!keep) {
                    val st = rt.owner.submit { rt.session.state() }.join()
                    println("[PERF-1TO1][$label][STOPPED] status=${st["status"]} frontier=${st["frontier"]} socPower=${st["socPowerState"]}")
                    break
                }
            }
            val report = rt.profiler.stop()
            val wallSec = (System.nanoTime() - startNs) / 1e9

            val maliAfter = compState(rt, "soc-isp")["maliV61"] as? Map<*, *>
            val framesDelta = ((maliAfter?.get("framesEncodedCount") as? Number)?.toLong() ?: 0L) - framesBefore
            val bytesDelta = ((maliAfter?.get("bytesEncodedCount") as? Number)?.toLong() ?: 0L) - bytesBefore
            val decodedDelta = ((maliAfter?.get("framesDecodedCount") as? Number)?.toLong() ?: 0L) - decodedBefore
            val mainSeqDelta = rt.owner.submit {
                rt.host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
            }.join() - mainSeqBefore
            val evfSeqDelta = rt.owner.submit {
                rt.host.ports.find("display.evf", DisplayPort::class.java)?.sequence ?: 0L
            }.join() - evfSeqBefore
            val scr = readU32(rt, 0x01CC6424L)
            val pacerInfo = if (localPacer != null) {
                " pacer(sleptMs=${"%.1f".format(localPacer.totalSleptNanos / 1e6)},wfiSleptMs=${"%.1f".format(localPacer.wfiIdleSleptNanos / 1e6)},resync=${localPacer.resyncCount},native=${localPacer.usesNativeHighResTimer})"
            } else ""

            println("================================================================================")
            println(
                "[PERF-1TO1][$label] scr=0x${scr.toString(16)} wall=${"%.2f".format(wallSec)}s " +
                    "mainFps=${"%.1f".format(mainSeqDelta / wallSec)} evfFps=${"%.1f".format(evfSeqDelta / wallSec)} " +
                    "vpuEnc=$framesDelta (${"%.1f".format(framesDelta / wallSec)} fps, ${"%.2f".format(bytesDelta / 1_048_576.0)} MiB) " +
                    "vpuDec=$decodedDelta$pacerInfo",
            )
            println(rt.profiler.formatTextReport())
            return report
        } finally {
            localPacer?.close()
        }
    }

    @Test
    @Tag("perf")
    fun profileFullPipelineRealtime1to1AcrossLiveViewMovieAndPlayback(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_PERF_1TO1") == "1" || java.lang.Boolean.getBoolean("fuji.perf.run1to1"),
            "Set FUJI_RUN_PERF_1TO1=1 or -Dfuji.perf.run1to1=true to run 1:1 realtime pipeline profiling",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: listOf(
                repo.resolve("build/release-stage/linux-x86_64"),
                repo.parent.resolve("fuji-emulator-gfx100s/build/release-stage/linux-x86_64"),
            ).firstOrNull { Files.isRegularFile(it.resolve("configs/emulator.yaml")) }
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) && Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")),
            "Requires staged release at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("perf-1to1")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val rawConfig = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        val config = rawConfig + ("realTimePacer" to mapOf("enabled" to true, "targetRatio" to 1.0, "ticksPerRealSecond" to 54_000_000))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // Cold boot -> OOBE (0x0421) -> STILL LiveView (0x0100)
            for (i in 0 until 80) {
                stepVirtualMs(rt, 200L)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE 0x0421")
            stepVirtualMs(rt, 400L)
            for (attempt in 0 until 3) {
                if (readU32(rt, 0x01CC6424L) != 0x0421) break
                tapButton(rt, "menu", 95L, 550L)
            }
            tapButton(rt, "down", 95L, 350L)
            tapButton(rt, "down", 95L, 350L)
            tapButton(rt, "menu", 95L, 550L)
            tapButton(rt, "up", 85L, 250L)
            tapButton(rt, "menu", 95L, 550L)
            tapButton(rt, "menu", 95L, 550L)
            for (i in 0 until 40) {
                stepVirtualMs(rt, 250L)
                if (readU32(rt, 0x01CC6424L) == 0x0100) break
            }
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView 0x0100")

            // Switch to animated milk-frog scene + live 48 kHz audio (realistic desktop session!)
            patchComp(
                rt,
                "cmos",
                mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog?motion=on&audio=on", "loop" to true),
            )
            rt.audioPlayback.applyPatch(mapOf("inputMode" to "SCENE_AUDIO", "inputGainDb" to 0.0))
            stepVirtualMs(rt, 1_000L)

            // A1: Still LiveView LCD (NORMAL, uncapped headroom)
            val rStillLcd = profileWindow(rt, "A1_STILL_LV_LCD_NORMAL", wallSeconds = 4.0, paced = false)

            // A2: Still LiveView EVF (NORMAL, uncapped headroom)
            patchComp(rt, "tmd2772", mapOf("eyeSensorNear" to true))
            patchComp(rt, "i2c-bus", mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 800L)
            val rStillEvf = profileWindow(rt, "A2_STILL_LV_EVF_NORMAL", wallSeconds = 4.0, paced = false)
            patchComp(rt, "tmd2772", mapOf("eyeSensorNear" to false))
            patchComp(rt, "i2c-bus", mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 600L)

            // B1: Movie Standby (FHD 59.94p, uncapped headroom)
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 2_500L)
            val rMovieStby = profileWindow(rt, "B1_MOVIE_STBY_FHD60", wallSeconds = 4.0, paced = false)

            // B2: Active Movie Recording (FHD 59.94p, 0x0208, uncapped headroom!)
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 220L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            stepVirtualMs(rt, 1_000L) // settle into steady-state encoding
            val rMovieRec = profileWindow(rt, "B2_MOVIE_REC_FHD60", wallSeconds = 6.0, paced = false)

            // Stop recording cleanly
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 160L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            stepVirtualMs(rt, 2_500L)

            // C1: Menu
            tapButton(rt, "menu", 95L, 600L)
            val rMenu = profileWindow(rt, "C1_MENU", wallSeconds = 3.0, paced = false)
            tapButton(rt, "back", 95L, 500L)

            // C2: Playback of recorded .MOV
            tapButton(rt, "play", 95L, 800L)
            tapButton(rt, "down", 95L, 400L) // start movie playback
            val rPlayback = profileWindow(rt, "C2_PLAYBACK_MOV", wallSeconds = 4.0, paced = false)
            tapButton(rt, "up", 95L, 400L)

            // D: Paced 1.00x real-time check on Still LiveView LCD
            patchComp(rt, "buttons", mapOf("movieMode" to false))
            stepVirtualMs(rt, 400L)
            tapButton(rt, "play", 95L, 800L)
            stepVirtualMs(rt, 800L)
            val rPaced = profileWindow(rt, "D_PACED_1TO1_STILL_LCD", wallSeconds = 3.0, paced = true)

            fun rtr(m: Map<String, Any?>): Double = (m["realTimeRatio"] as? Number)?.toDouble() ?: 0.0
            println(
                "[PERF-1TO1][SUMMARY] " +
                    "StillLCD=${"%.3f".format(rtr(rStillLcd))}x " +
                    "StillEVF=${"%.3f".format(rtr(rStillEvf))}x " +
                    "MovieStby=${"%.3f".format(rtr(rMovieStby))}x " +
                    "MovieRecFHD60=${"%.3f".format(rtr(rMovieRec))}x " +
                    "Menu=${"%.3f".format(rtr(rMenu))}x " +
                    "PlaybackMOV=${"%.3f".format(rtr(rPlayback))}x " +
                    "PacedStillLCD=${"%.3f".format(rtr(rPaced))}x",
            )
            assertTrue(rtr(rStillLcd) > 0.0)
            assertTrue(rtr(rMovieRec) > 0.0)
            assertTrue(rtr(rPaced) > 0.0, "Paced Still LCD must stay running without session stop")
        }
    }
}
