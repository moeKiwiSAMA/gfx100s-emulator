package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Live Dynarmic V2 verification for:
 * - BUG-A4-1: Live Movie Recording (`shutterHalf` + `shutterFull` start -> record -> stop) must
 *   not hit high-DDR Data Aborts (`0x78FF6000` / `0x43B0B0F0`) or crash `ImageScalerE000` on
 *   34-bit AXI 1080p Movie RESZ (`E008=0x131`, `srcFmt=1`, `dstFmt=3`); Mali-V61 VPU must
 *   reset and complete IRQ 138 doorbells and return cleanly to active Movie LiveView.
 * - BUG-A4-3: Sustained Movie Menu navigation (`>= 650` display frames) must run cleanly with
 *   zero kernel exception panics (`0x00057B8C`) and live scanout.
 */
class MovieRecordingAndMenuStabilityIntegrationTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, componentId: String, patch: Map<String, Any>) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == componentId }.revision }.join()
        rt.host.patch(componentId, rev, patch).join()
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

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun captureMainStats(rt: EmulatorRuntime): Pair<Long, Int> =
        rt.owner.submit {
            val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val w = port.width
            val h = port.height
            val seq = port.sequence
            if (w <= 0 || h <= 0) return@submit seq to 0
            val px = IntArray(w * h)
            port.copyArgb(px)
            seq to px.count { (it and 0x00ffffff) != 0 }
        }.join()

    private fun longField(map: Map<*, *>?, key: String): Long =
        (map?.get(key) as? Number)?.toLong() ?: 0L

    @Test
    fun liveMovieRecordingAndSustainedMovieMenuAvoidHighDdrDataAbortAndKernelPanic(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("release-movie-rec")
        copyDirectory(stagedRoot, workRoot)
        for (compId in listOf("soc-dram", "soc-vglib-init", "soc-isp")) {
            val builtJar = repo.resolve("components/$compId/build/libs/$compId-0.3.0.jar")
            if (Files.isRegularFile(builtJar)) {
                Files.copy(
                    builtJar,
                    workRoot.resolve("runtime/compoments/$compId-0.3.0.jar"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot through OOBE -> Live STILL HUD (0x0100)
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 25) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && captureMainStats(rt).second > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected Live STILL HUD 0x0100")

            // 2. Switch STILL -> MOVIE
            patchComponent(rt, "buttons", mapOf("movieMode" to true))
            stepBy(rt, 160_000_000L)
            val (seqMovieReady, nbMovieReady) = captureMainStats(rt)
            assertTrue(nbMovieReady > 150_000, "Movie LiveView blacked out ($nbMovieReady)")

            // 3. BUG-A4-1: Live Movie Recording (shutterHalf + shutterFull start -> record -> shutterFull stop)
            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            stepBy(rt, 15_000_000L)
            patchComponent(rt, "buttons", mapOf("shutterFull" to true))
            stepBy(rt, 10_000_000L)
            patchComponent(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

            var sawMovieRecScreen = false
            for (step in 1..6) {
                stepBy(rt, 25_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0208) sawMovieRecScreen = true
                assertEquals(0, readGuestU32(rt, 0x0008_4AD4L), "Kernel exception during Movie Recording step $step")
            }

            // Stop recording and verify clean recovery to live scanout
            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            stepBy(rt, 10_000_000L)
            patchComponent(rt, "buttons", mapOf("shutterFull" to true))
            stepBy(rt, 10_000_000L)
            patchComponent(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            stepBy(rt, 150_000_000L)

            val (seqAfterStop, nbAfterStop) = captureMainStats(rt)
            val excAfterRec = readGuestU32(rt, 0x0008_4AD4L)
            val maliState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()["maliV61"] as? Map<*, *>
            }.join()
            val maliResets = longField(maliState, "resetCount")
            val maliDoorbells = longField(maliState, "doorbellCount")
            val maliCompletions = longField(maliState, "completionCount")

            assertEquals(0, excAfterRec, "Live Movie Recording hit kernel exception")
            assertTrue(sawMovieRecScreen, "Expected Movie Recording screen 0x0208")
            assertTrue(maliResets >= 1L, "Expected Mali-V61 resetCount >= 1 during Movie Recording, got $maliResets")
            assertTrue(maliDoorbells >= 1L && maliCompletions >= 1L, "Expected Mali-V61 IRQ 138 doorbells/completions ($maliDoorbells/$maliCompletions)")
            assertTrue(seqAfterStop > seqMovieReady && nbAfterStop > 150_000, "Display stalled after Movie Rec ($seqMovieReady -> $seqAfterStop, nb=$nbAfterStop)")

            // 4. BUG-A4-3: Sustained Movie Menu navigation (>= 650 display frames)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            val (seqMenuStart, nbMenuStart) = captureMainStats(rt)
            var curSeq = seqMenuStart
            var navIdx = 0
            val navKeys = listOf("down", "down", "down", "left", "down", "right", "up", "up")
            var iter = 0
            while (curSeq - seqMenuStart < 650 && iter < 200) {
                if (iter % 15 == 0) {
                    tapButton(rt, navKeys[navIdx % navKeys.size], 4_000_000L, 12_000_000L)
                    navIdx++
                } else {
                    stepBy(rt, 16_000_000L)
                }
                curSeq = captureMainStats(rt).first
                assertEquals(0, readGuestU32(rt, 0x0008_4AD4L), "Sustained Movie Menu hit kernel exception at iter $iter")
                iter++
            }
            val menuFrames = curSeq - seqMenuStart
            val (_, nbMenuEnd) = captureMainStats(rt)
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = longField(relaxed, "mmioFallbacks")
            val discoveryFallbacks = longField(relaxed, "discoveryFallbacks")
            val flashRefusals = longField(relaxed, "flashRefusals")
            val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            val regsFinal = rt.owner.submit { rt.session.registerSnapshot() }.join()

            assertTrue(menuFrames >= 650, "Expected >= 650 sustained Movie Menu frames, got $menuFrames")
            assertTrue(nbMenuStart > 150_000 && nbMenuEnd > 150_000, "Movie Menu blacked out ($nbMenuStart -> $nbMenuEnd)")
            assertEquals(0L, mmioFallbacks, "Expected ZERO [RELAXED/MMIO] fallbacks | report=$report")
            assertEquals(0L, discoveryFallbacks, "Expected ZERO [RELAXED/MMIO] discoveryFallbacks | report=$report")
            assertEquals(0L, flashRefusals, "Expected ZERO [RELAXED/FLASH] refusals | report=$report")
            assertNotEquals(0x0005_7B8CL, regsFinal?.get("pc"), "CPU halted in kernel exception panic 0x00057B8C")

            println(
                "[MovieRecordingAndMenuStabilityIntegrationTest] PASS: " +
                    "MovieRec(scr=0x0208 maliReset=$maliResets doorbell=$maliDoorbells comp=$maliCompletions seq=$seqMovieReady->$seqAfterStop nb=$nbAfterStop) " +
                    "MovieMenu(frames=$menuFrames nb=$nbMenuStart->$nbMenuEnd exc=0) mmioFallbacks=0 discoveryFallbacks=0 flashRefusals=0",
            )
        }
    }
}
