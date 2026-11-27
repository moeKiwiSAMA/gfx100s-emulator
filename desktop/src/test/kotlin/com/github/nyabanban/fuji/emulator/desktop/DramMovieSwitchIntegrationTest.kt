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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live Dynarmic JIT verification for BUG-A4-2:
 * Live STILL -> MOVIE switch, Movie Menu navigation, and MOVIE -> STILL return on real
 * GXUP0004.DAT firmware must execute DAT 0x011fb654 targeted 3-word DRAM QoS command tables
 * (0x5038 / 0x5438 / 0x5384) cleanly with ZERO `[RELAXED/MMIO]` fallbacks and active scanout.
 */
class DramMovieSwitchIntegrationTest {
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

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 250_000) {
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

    private fun dramBankZeroObservations(rt: EmulatorRuntime, bankIndex: Int = 0): Long =
        rt.owner.submit {
            val dramState = rt.host.components().single { it.descriptor.id == "soc-dram" }.component.state()
            val banks = dramState["banks"] as? Map<*, *> ?: return@submit 0L
            val bank = banks[bankIndex.toString()] as? Map<*, *> ?: return@submit 0L
            val cmds = bank["commands"] as? Map<*, *> ?: return@submit 0L
            (cmds["guestZeroObservationCount"] as? Number)?.toLong() ?: 0L
        }.join()

    private fun longField(map: Map<*, *>?, key: String): Long =
        (map?.get(key) as? Number)?.toLong() ?: 0L

    @Test
    fun liveStillToMovieSwitchAndMenuCompleteTargetedDramCommandsWithZeroMmioFallbacks(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("release-dram-movie")
        copyDirectory(stagedRoot, workRoot)
        val builtDramJar = repo.resolve("components/soc-dram/build/libs/soc-dram-0.3.1.jar")
        if (Files.isRegularFile(builtDramJar)) {
            Files.copy(
                builtDramJar,
                workRoot.resolve("runtime/compoments/soc-dram-0.3.1.jar"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot through OOBE -> Live 1024x768 STILL HUD (Scr=0x0100)
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
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected Live STILL HUD 0x0100 before Movie switch")
            val obsBeforeMovie = dramBankZeroObservations(rt, 0)

            // 2. Live STILL -> MOVIE switch: exercises DAT 0x011fb654 (0xff505038=0x03204020, 0xff505438=0x83, 0xff505384=1)
            patchComponent(rt, "buttons", mapOf("movieMode" to true))
            stepBy(rt, 150_000_000L)
            val (seqMovie1, nonBlackMovie1) = captureMainStats(rt)
            stepBy(rt, 40_000_000L)
            val (seqMovie2, nonBlackMovie2) = captureMainStats(rt)
            val obsAfterMovie = dramBankZeroObservations(rt, 0)

            // 3. Open Movie MENU, step down 3 items, exit back to Movie LiveView
            tapButton(rt, "menu", 5_000_000L, 50_000_000L)
            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            tapButton(rt, "back", 5_000_000L, 45_000_000L)
            val (seqAfterMenu, nonBlackAfterMenu) = captureMainStats(rt)

            // 4. Switch live MOVIE -> STILL: exercises DAT 0x011fb654 normal restore (0x015b15bc)
            patchComponent(rt, "buttons", mapOf("movieMode" to false))
            stepBy(rt, 120_000_000L)
            val (seqStillReturn, nonBlackStillReturn) = captureMainStats(rt)
            val obsAfterStillReturn = dramBankZeroObservations(rt, 0)

            val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = longField(relaxed, "mmioFallbacks")
            val flashRefusals = longField(relaxed, "flashRefusals")

            assertEquals(0L, mmioFallbacks, "Expected ZERO [RELAXED/MMIO] fallbacks on STILL<->MOVIE switch | report=$report")
            assertEquals(0L, flashRefusals, "Expected ZERO [RELAXED/FLASH] refusals | report=$report")
            assertFalse(
                report.contains("0xff505038", ignoreCase = true) ||
                    report.contains("0xff505438", ignoreCase = true) ||
                    report.contains("0xff505384", ignoreCase = true),
                "Unexpected soc-dram Movie command fallback | report=$report",
            )
            assertTrue(
                obsAfterMovie > obsBeforeMovie,
                "Expected bank 0 guestZeroObservationCount to advance on STILL->MOVIE ($obsBeforeMovie -> $obsAfterMovie)",
            )
            assertTrue(nonBlackMovie1 > 150_000 && nonBlackMovie2 > 150_000, "Movie LiveView blacked out ($nonBlackMovie1, $nonBlackMovie2)")
            assertTrue(seqMovie2 > seqMovie1, "Movie LiveView scanout stalled ($seqMovie1 -> $seqMovie2)")
            assertTrue(seqAfterMenu > seqMovie2 && nonBlackAfterMenu > 150_000, "Movie menu/return stalled ($seqMovie2 -> $seqAfterMenu, nb=$nonBlackAfterMenu)")
            assertTrue(nonBlackStillReturn > 150_000 && seqStillReturn > seqAfterMenu, "MOVIE->STILL return stalled ($seqAfterMenu -> $seqStillReturn, nb=$nonBlackStillReturn)")

            println(
                "[DramMovieSwitchIntegrationTest] PASS: STILL->MOVIE->MENU->STILL succeeded " +
                    "(bank0Obs=$obsBeforeMovie->$obsAfterMovie->$obsAfterStillReturn " +
                    "seq=$seqMovie1->$seqMovie2->$seqAfterMenu->$seqStillReturn " +
                    "nonBlack=$nonBlackMovie1/$nonBlackMovie2/$nonBlackAfterMenu/$nonBlackStillReturn " +
                    "mmioFallbacks=0 flashRefusals=0)",
            )
        }
    }
}
