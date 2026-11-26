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
 * Live Dynarmic JIT verification for Track F4 (`BUG-04`):
 * 1. Shutdown / `AUTO POWER OFF` writes to `part-18-domain492-shutdown` (`0x03D80000`, 256 B) commit cleanly
 *    into `cow-domain490-497.img` with ZERO `[RELAXED/FLASH]` refusals.
 * 2. Real-silicon Sub-MCU power-off latch (`power-peer sel 0x11` + `0xFFF4A400 = 0`) quiesces the main SoC
 *    and wakes cleanly via real-silicon cold-reset on BOTH:
 *    - `power` switch `false -> true` edge (after power-off, rejecting `shutterHalf` while `power=false`), and
 *    - `shutterHalf` press while `power == true` (after real `AUTO POWER OFF`!),
 *    returning directly to live `1024x768` STILL HUD (`Scr=0x0100`) with persisted COW flash intact.
 */
class AutoPowerOffAndWakeIntegrationTest {
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

    private fun patchButton(rt: EmulatorRuntime, key: String, pressed: Boolean) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == "buttons" }.revision }.join()
        rt.host.patch("buttons", rev, mapOf(key to pressed)).join()
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
                if (ok && rt.session.isSocPoweredOff) {
                    ok = rt.session.stepFast()
                }
                rt.displayBridge.publishAll(rt.host.ports)
                ok
            }.join()
            if (!cont || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchButton(rt, key, true)
        stepBy(rt, holdTicks)
        patchButton(rt, key, false)
        stepBy(rt, settleTicks)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> =
        rt.owner.submit {
            val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val seq = port.sequence
            val w = port.width
            val h = port.height
            val px = IntArray(if (w > 0 && h > 0) w * h else 0)
            if (px.isNotEmpty()) port.copyArgb(px)
            Triple(seq, w to h, px)
        }.join()

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00ffffff) != 0 }

    private fun countUniqueColors(pixels: IntArray, cap: Int = 128): Int {
        val seen = HashSet<Int>(cap * 2)
        val stride = maxOf(1, pixels.size / 65536)
        var i = 0
        while (i < pixels.size && seen.size < cap) {
            seen.add(pixels[i] and 0x00f8f8f8)
            i += stride
        }
        return seen.size
    }

    private fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun diagPowerState(rt: EmulatorRuntime, workRoot: Path, state: Map<String, Any?>): String {
        val sysSt = rt.owner.submit { rt.host.components().single { it.descriptor.id == "soc-system-control" }.component.state() }.join()
        val ppSt = rt.owner.submit { rt.host.components().single { it.descriptor.id == "power-peer" }.component.state() }.join()
        val ppEv = (ppSt["events"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .filter { it["write"] == true }.takeLast(12)
            .map { "r${it["register"]}=0x${((it["value"] as? Number)?.toInt() ?: 0).toString(16)}" }
        val (_, _, pxNow) = captureMain(rt)
        val norPath = workRoot.resolve("flash/nor-flash.bin")
        val cowPath = workRoot.resolve("runtime/compoments/flash-image/cow-domain490-497.img")
        val p18 = if (Files.isRegularFile(norPath)) {
            ByteArray(0x100).also { buf ->
                java.io.RandomAccessFile(norPath.toFile(), "r").use { raf ->
                    raf.seek(0x03D80000L)
                    raf.readFully(buf)
                }
            }.any { it != 0xff.toByte() }
        } else {
            Files.isRegularFile(cowPath) && Files.readAllBytes(cowPath).copyOfRange(0x40000, 0x40100).any { it != 0xff.toByte() }
        }
        return "pc=0x${(((state["registers"] as? Map<*, *>)?.get("pc") as? Number)?.toLong() ?: -1L).toString(16)} " +
            "scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} nonBlack=${countNonBlack(pxNow)} " +
            "boolLast=${sysSt["booleanLastWrite"]} ppStored=${ppSt["stored"]} ppWr=$ppEv p18Written=$p18 " +
            "tick=${rt.telemetry.virtualTick} summary=${rt.session.relaxedFeedbackSummaryReport()}"
    }

    private fun waitForStillHud(rt: EmulatorRuntime, maxChunks: Int, chunkTicks: Long): Pair<Boolean, String> {
        var last = ""
        for (i in 0 until maxChunks) {
            stepBy(rt, chunkTicks)
            val st = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", st["status"].toString(), "Expected running session (iter=$i): $st")
            val scr = readGuestU32(rt, 0x01CC6424L)
            val (seq, _, px) = captureMain(rt)
            val nonBlack = countNonBlack(px)
            val colors = countUniqueColors(px)
            last = "iter=$i scr=0x${scr.toString(16)} seq=$seq nonBlack=$nonBlack colors=$colors tick=${rt.telemetry.virtualTick}"
            if (scr == 0x0100 && nonBlack > 150_000 && colors >= 40) return true to last
        }
        return false to last
    }

    @Test
    fun shutdownWrites0x03d80000WithoutRefusalAndWakesViaPowerSwitchAndShutterHalf(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("release-apo")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot to OOBE (Scr=0x0421) -> advance to Area (0x0423) -> test real power=false shutdown
            //    (bypasses power-off sensor cleaning BUG-01 so shutdown reaches 0x03D80000 + sel 0x11 + 0xFFF4A400=0 immediately!)
            stepBy(rt, 620_000_000L)
            for (extra in 0 until 12) {
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
                stepBy(rt, 30_000_000L)
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0423, readGuestU32(rt, 0x01CC6424L), "Expected Area Setting screen 0x0423")

            patchButton(rt, "power", false)
            for (chunk in 0 until 12) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L)
            }
            val offState = rt.owner.submit { rt.session.state() }.join()
            val offFlashRefusals = ((offState["relaxedFallback"] as? Map<*, *>)?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            assertEquals(
                0L,
                offFlashRefusals,
                "Expected ZERO [RELAXED/FLASH] refusals on shutdown (0x03D80000 must be writable): ${diagPowerState(rt, workRoot, offState)}",
            )
            assertTrue(
                rt.session.isSocPoweredOff,
                "Expected real-silicon Sub-MCU power-off latch after power=false | ${diagPowerState(rt, workRoot, offState)}",
            )
            assertEquals("powered-off", offState["socPowerState"])
            val (_, _, offPx) = captureMain(rt)
            assertEquals(0, countNonBlack(offPx), "Expected pure black LCD while powered off")

            val norPath = workRoot.resolve("flash/nor-flash.bin")
            val cowPath = workRoot.resolve("runtime/compoments/flash-image/cow-domain490-497.img")
            val part18Slice = if (Files.isRegularFile(norPath)) {
                ByteArray(0x100).also { buf ->
                    java.io.RandomAccessFile(norPath.toFile(), "r").use { raf ->
                        raf.seek(0x03D80000L)
                        raf.readFully(buf)
                    }
                }
            } else {
                Files.readAllBytes(cowPath).copyOfRange(0x40000, 0x40100)
            }
            assertTrue(
                part18Slice.any { it != 0xff.toByte() },
                "Expected real firmware shutdown write inside part-18-domain492-shutdown (0x03D80000..0x03D800FF)",
            )

            // While power=false, shutterHalf MUST NOT wake the SoC
            patchButton(rt, "shutterHalf", true)
            rt.owner.submit { rt.session.stepFast() }.join()
            patchButton(rt, "shutterHalf", false)
            rt.owner.submit { rt.session.stepFast() }.join()
            assertTrue(rt.session.isSocPoweredOff, "shutterHalf while power=false must NOT wake SoC")

            // 2. Flip power=true -> real-silicon Sub-MCU cold-reset wakes cleanly directly to Area (0x0423),
            //    skipping Language (0x0421) because Sub-MCU coin-cell NVRAM (sel 0x1F..0x22) persisted bLanguageSet!
            patchButton(rt, "power", true)
            rt.owner.submit { rt.session.stepFast() }.join()
            assertFalse(rt.session.isSocPoweredOff, "Expected power=true rising edge to wake SoC")
            assertEquals(1L, rt.session.wakeCount, "Expected socWakeCount == 1 after power-switch wake")

            stepBy(rt, 620_000_000L)
            for (extra in 0 until 12) {
                if (readGuestU32(rt, 0x01CC6424L) == 0x0423) break
                stepBy(rt, 30_000_000L)
            }
            val wake1State = rt.owner.submit { rt.session.state() }.join()
            assertEquals(
                0x0423,
                readGuestU32(rt, 0x01CC6424L),
                "Expected Area OOBE screen 0x0423 after power-switch wake (skipping persisted Language 0x0421) | status=${wake1State["status"]} reason=${wake1State["reason"]} frontier=${wake1State["frontier"]} socPower=${wake1State["socPowerState"]} ${diagPowerState(rt, workRoot, wake1State)}",
            )

            // 3. Complete remaining OOBE (Area -> Date/Time -> Start App) -> Live 1024x768 STILL HUD (0x0100)
            //    -> switch to PLAYBACK (0x0300) so ISP LiveView pauses (~400M ticks/s!) and wait for real out-of-the-box default AUTO POWER OFF (2 MIN)!
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Area -> Date/Time (0x0422)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)   // Set valid Year (+1) so Date/Time commits to RTC & NVRAM
            tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Date/Time -> Start App (0x0482)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L) // Start App -> STILL HUD (0x0100)
            val (hudOk, hudDiag) = waitForStillHud(rt, maxChunks = 25, chunkTicks = 6_000_000L)
            assertTrue(hudOk, "Expected Live STILL HUD after OOBE; $hudDiag")

            tapButton(rt, "play", 5_000_000L, 60_000_000L)
            val playScr = readGuestU32(rt, 0x01CC6424L)
            assertTrue(playScr == 0x0300 || playScr == 0x0506, "Expected PLAYBACK screen 0x0300/0x0506 before AUTO POWER OFF countdown, got 0x${playScr.toString(16)}")

            // 4. Wait idle in PLAYBACK for real out-of-the-box default AUTO POWER OFF (2 MIN ~= 6.5B ticks; cap 18B ticks)
            for (chunk in 0 until 90) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 200_000_000L)
            }
            val apoState = rt.owner.submit { rt.session.state() }.join()
            val apoFlashRefusals = ((apoState["relaxedFallback"] as? Map<*, *>)?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            assertEquals(
                0L,
                apoFlashRefusals,
                "Expected ZERO [RELAXED/FLASH] refusals on AUTO POWER OFF: ${diagPowerState(rt, workRoot, apoState)}",
            )
            assertTrue(
                rt.session.isSocPoweredOff,
                "Expected real 15-sec AUTO POWER OFF to latch Sub-MCU powered-off state | ${diagPowerState(rt, workRoot, apoState)}",
            )
            assertEquals("powered-off", apoState["socPowerState"])

            // 5. Press shutterHalf=true while power=true -> real-silicon Sub-MCU wake straight back to Live 1024x768 STILL HUD!
            patchButton(rt, "shutterHalf", true)
            rt.owner.submit { rt.session.stepFast() }.join()
            patchButton(rt, "shutterHalf", false)
            assertFalse(rt.session.isSocPoweredOff, "Expected shutterHalf press with power=true to wake SoC from AUTO POWER OFF")
            assertEquals(2L, rt.session.wakeCount, "Expected socWakeCount == 2 after shutterHalf wake")

            val (wake2Ok, wake2Diag) = waitForStillHud(rt, maxChunks = 60, chunkTicks = 20_000_000L)
            assertTrue(wake2Ok, "Expected camera to wake straight to Live STILL HUD (skipping OOBE!) after AUTO POWER OFF + shutterHalf; $wake2Diag")
            val wake2State = rt.owner.submit { rt.session.state() }.join()
            assertEquals(0L, ((wake2State["relaxedFallback"] as? Map<*, *>)?.get("flashRefusals") as? Number)?.toLong() ?: 0L)
            println(
                "[AutoPowerOffAndWakeTest] PASS: wakeCount=${rt.session.wakeCount} wake2=($wake2Diag) " +
                    "part18First8=${part18Slice.take(8).joinToString("") { "%02x".format(it) }}",
            )
        }

        // 6. BUG-15B Process 2 full cold restart from 0x0 (ZERO CPU/RAM snapshot):
        //    Verify persisted Sub-MCU coin-cell NVRAM (submcu-nvram.json) + COW Flash skip OOBE straight to Live STILL HUD (0x0100)!
        val nvramPath = workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json")
        assertTrue(
            Files.isRegularFile(nvramPath),
            "Expected relaxed-mode Sub-MCU coin-cell NVRAM persisted at $nvramPath after OOBE + shutdown",
        )
        val config2 = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config2, telemetryDisabled = true).use { rt2 ->
            rt2.owner.submit { rt2.session.initialize() }.join()
            val (coldHudOk, coldHudDiag) = waitForStillHud(rt2, maxChunks = 60, chunkTicks = 20_000_000L)
            assertTrue(
                coldHudOk,
                "Expected Process 2 fresh cold-boot from 0x0 (ZERO snapshot) to skip OOBE straight to Live STILL HUD via submcu-nvram.json; $coldHudDiag",
            )
            println("[AutoPowerOffAndWakeTest] PASS Process-2 cold restart skipped OOBE -> $coldHudDiag nvram=${Files.readString(nvramPath).trim()}")
        }
    }
}
