package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GpioInputPort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedFlashPersistence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Live Dynarmic JIT regression suite for P0-FIX-2 + BUG-31:
 *
 * 1. BUG-31 (early-boot / post-APO power-off stall at STAGE_3_STORAGE_IO, PC=0x012CA224):
 *    When power==false during early boot, real GFX100S firmware drops PMIC POWER_HOLD
 *    (0xFFF4A400: 1 -> 0) without writing 0x0800/0x1F00 to power-peer sel 0x11 and enters `b .`.
 *    Sub-MCU must latch socPoweredOff=true so toggling power=true cold-resets cleanly.
 * 2. BUG-V3-9-1 (SPI5 cold-reset wake crash after VIEW MODE -> EVF ONLY + MOVIE + PLAY):
 *    Boot #2 in persisted EVF mode sends 8-bit Sony ECX33x OLED command (0x80) at SPI5 exchange 0,
 *    which must succeed cleanly via empty-prefix continuous fixed-MISO PanelWordPlan.
 * 3. BUG-V3-9-2 (Boot-hold DISP/BACK FWUP regression):
 *    Holding DISP/BACK on cold boot must reach scr=0x0601 (white 0x200803fc backdrop + black text)
 *    with SD inserted and scr=0x060a without SD (zero -64 watchdog panic / -42 gray stall).
 * 4. BUG-V3-9-4 (Post-SD-hot-reinsert capture):
 *    After hot-eject + hot-reinsert of SD media, AF-S capture must encode JPEG (0 -> 3) and
 *    persist a valid DSCFxxxx.JPG (> 100 KiB, SOI/EOI intact) with zero MMIO/flash fallbacks.
 */
class P0Fix2ColdResetFwupSdReinsertRegressionTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    val name = path.fileName.toString()
                    if (name.endsWith(".DAT") || name.endsWith(".jar") || name.endsWith(".so") || name.endsWith(".so.2")) {
                        try {
                            Files.createLink(target, path)
                        } catch (_: Throwable) {
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    } else {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun requireStagedRelease(): Path {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )
        return stagedRoot
    }

    private fun patchComp(rt: EmulatorRuntime, id: String, changes: Map<String, Any>, persist: Boolean = false) {
        rt.owner.submit { rt.host.patchLatestDirect(id, changes, persistToDisk = persist) }.join()
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> =
        rt.owner.submit { rt.host.components().single { it.descriptor.id == id }.component.state() }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 50_000) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var calls = 0
        while (rt.telemetry.virtualTick < target && calls < maxCalls) {
            val ok = rt.owner.submit {
                var cont = true
                var i = 0
                while (cont && i < 64 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    cont = rt.session.stepFast()
                    i++
                }
                if (cont && rt.session.isSocPoweredOff) {
                    cont = rt.session.stepFast()
                }
                rt.displayBridge.publishAll(rt.host.ports)
                cont
            }.join()
            if (!ok || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tap(rt: EmulatorRuntime, key: String, hold: Long, settle: Long) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepBy(rt, hold)
        patchComp(rt, "buttons", mapOf(key to false))
        stepBy(rt, settle)
    }

    private fun bootToStill(rt: EmulatorRuntime) {
        for (i in 0 until 40) {
            stepBy(rt, 20_000_000L, maxCalls = 10_000)
            val scr = readU32(rt, 0x01CC6424L)
            if (scr == 0x0421 || scr == 0x0100) break
        }
        if (readU32(rt, 0x01CC6424L) == 0x0421) {
            tap(rt, "menu", 5_000_000L, 45_000_000L)
            tap(rt, "down", 5_000_000L, 25_000_000L)
            tap(rt, "down", 5_000_000L, 25_000_000L)
            tap(rt, "menu", 5_000_000L, 45_000_000L)
            tap(rt, "up", 4_000_000L, 12_000_000L)
            tap(rt, "menu", 5_000_000L, 45_000_000L)
            tap(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 40) {
                if (readU32(rt, 0x01CC6424L) == 0x0100) break
                stepBy(rt, 6_000_000L, maxCalls = 5_000)
            }
        }
        stepBy(rt, 30_000_000L, maxCalls = 10_000)
    }

    private fun relaxedCounters(rt: EmulatorRuntime): Pair<Long, Long> {
        val state = rt.owner.submit { rt.session.state() }.join()
        val rf = state["relaxedFallback"] as? Map<*, *>
        val mmio = (rf?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
        val flash = (rf?.get("flashRefusals") as? Number)?.toLong() ?: 0L
        return mmio to flash
    }

    @Test
    fun bug31EarlyPowerOffAndBugV391Spi5EvfColdResetWakeRecoverCleanly(@TempDir tempRoot: Path) {
        val stagedRoot = requireStagedRelease()
        val workRoot = tempRoot.resolve("p0-fix2-wake")
        copyDirectory(stagedRoot, workRoot)
        val cfg = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf("telemetry" to mapOf("enabled" to false))
        RelaxedFlashPersistence.prepare(workRoot, cfg)

        EmulatorRuntime(workRoot, cfg, telemetryDisabled = true).use { rt ->
            // Part A (BUG-31): Cold start with power=false -> firmware drops POWER_HOLD (0xFFF4A400 1->0)
            // without writing 0x0800/0x1F00 to sel 0x11. Sub-MCU must latch socPoweredOff=true rather
            // than letting Core 0 spin forever at 0x012CA224 (STAGE_3_STORAGE_IO, frames=0).
            rt.owner.submit {
                rt.host.patchLatestDirect("buttons", mapOf("power" to false), persistToDisk = true)
                rt.session.initialize()
            }.join()
            stepBy(rt, 60_000_000L, maxCalls = 5_000)
            assertTrue(
                rt.session.isSocPoweredOff,
                "BUG-31: Expected Sub-MCU to latch socPoweredOff=true when early boot drops POWER_HOLD (0xFFF4A400 1->0)",
            )

            // Flip power=true -> must cold-reset cleanly to OOBE (0x0421) and advance through OOBE to STILL HUD (0x0100).
            patchComp(rt, "buttons", mapOf("power" to true), persist = true)
            rt.owner.submit { rt.session.stepFast() }.join()
            assertEquals(1L, rt.session.wakeCount, "Expected wakeCount == 1 after power-switch rising edge")
            bootToStill(rt)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected live STILL HUD 0x0100 after BUG-31 wake")

            // Part B (BUG-V3-9-1): Toggle VIEW MODE (EVF ONLY), MOVIE, STILL, PLAY -> power=false -> power=true.
            // Boot #2 sends 8-bit Sony ECX33x OLED command (0x80) at SPI5 exchange 0; must not crash PanelWordPlan.
            rt.owner.submit {
                requireNotNull(rt.host.ports.find("soc-gpio.inputs", GpioInputPort::class.java)).setInputLevel(7, 6, false)
            }.join()
            stepBy(rt, 6_000_000L, maxCalls = 5_000)
            rt.owner.submit {
                requireNotNull(rt.host.ports.find("soc-gpio.inputs", GpioInputPort::class.java)).setInputLevel(7, 6, true)
            }.join()
            stepBy(rt, 50_000_000L, maxCalls = 10_000)
            patchComp(rt, "buttons", mapOf("movieMode" to true), persist = true)
            stepBy(rt, 40_000_000L, maxCalls = 10_000)
            patchComp(rt, "buttons", mapOf("movieMode" to false), persist = true)
            stepBy(rt, 40_000_000L, maxCalls = 10_000)
            tap(rt, "play", 5_000_000L, 50_000_000L)

            patchComp(rt, "buttons", mapOf("power" to false), persist = true)
            for (i in 0 until 20) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean power-off latch after VIEW MODE + MOVIE + PLAY")

            patchComp(rt, "buttons", mapOf("power" to true), persist = true)
            rt.owner.submit { rt.session.stepFast() }.join()
            assertEquals(2L, rt.session.wakeCount, "Expected wakeCount == 2 after EVF cold-reset wake")

            for (i in 0 until 30) {
                stepBy(rt, 20_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x0100 && rt.telemetry.publishedFrames > 0L) break
            }
            val wakeState = rt.owner.submit { rt.session.state() }.join()
            val (mmio, flash) = relaxedCounters(rt)
            assertEquals("running", wakeState["status"].toString(), "BUG-V3-9-1: Cold-reset wake must stay running (no SPI5 crash)")
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "BUG-V3-9-1: Expected EVF cold-reset wake to reach STILL HUD 0x0100")
            assertTrue(rt.telemetry.publishedFrames >= 1L, "Expected >= 1 rendered frame after EVF cold-reset wake")
            assertEquals(0L, mmio, "Expected 0 [RELAXED/MMIO] fallbacks on cold-reset wake")
            assertEquals(0L, flash, "Expected 0 [RELAXED/FLASH] refusals on cold-reset wake")
        }
    }

    @Test
    fun bugV392BootHoldDispBackFwupReaches0601WithSdAnd060aWithoutSd(@TempDir tempRoot: Path) {
        val stagedRoot = requireStagedRelease()

        // Case 1: With SD inserted -> scr=0x0601 with white 0x200803fc backdrop and crisp black text
        val withSdRoot = tempRoot.resolve("fwup-with-sd")
        copyDirectory(stagedRoot, withSdRoot)
        val cfgSd = Formats.read(withSdRoot.resolve("configs/emulator.yaml")) + mapOf("telemetry" to mapOf("enabled" to false))
        RelaxedFlashPersistence.prepare(withSdRoot, cfgSd)
        EmulatorRuntime(withSdRoot, cfgSd, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            patchComp(rt, "buttons", mapOf("back" to true))
            for (i in 0 until 24) {
                stepBy(rt, 15_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x0601) break
            }
            stepBy(rt, 40_000_000L, maxCalls = 10_000)
            patchComp(rt, "buttons", mapOf("back" to false))
            stepBy(rt, 20_000_000L, maxCalls = 10_000)

            val scr = readU32(rt, 0x01CC6424L)
            val err = readU32(rt, 0x01BE0138L)
            val bgColor = rt.owner.submit {
                requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)).read(0x30L, 4)
            }.join()
            val (whitePx, blackTextPx) = rt.owner.submit {
                val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                val px = IntArray(dp.width * dp.height)
                dp.copyArgb(px)
                var w = 0
                var b = 0
                for (y in 200 until 620) for (x in 150 until 874) {
                    val argb = px[y * dp.width + x]
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val bl = argb and 0xFF
                    if (r >= 245 && g >= 245 && bl >= 245) w++
                    if (r <= 15 && g <= 15 && bl <= 15) b++
                }
                w to b
            }.join()
            val (mmio, flash) = relaxedCounters(rt)
            assertNotEquals(-64, err, "BUG-V3-9-2(SD): Must not hit stack-watchdog panic (-64)")
            assertEquals(0x0601, scr, "BUG-V3-9-2(SD): Holding DISP/BACK with SD must reach FWUP scr=0x0601")
            assertEquals(0x200803fcL, bgColor, "BUG-V3-9-2(SD): FWUP must program white BGCOLOR 0x200803fc")
            assertTrue(whitePx > 150_000, "BUG-V3-9-2(SD): Expected white backdrop > 150k px, got $whitePx")
            assertTrue(blackTextPx > 1_000, "BUG-V3-9-2(SD): Expected black text > 1k px, got $blackTextPx")
            assertEquals(0L, mmio, "BUG-V3-9-2(SD): Expected 0 [RELAXED/MMIO] fallbacks")
            assertEquals(0L, flash, "BUG-V3-9-2(SD): Expected 0 [RELAXED/FLASH] refusals")
        }

        // Case 2: Without SD inserted -> scr=0x060a (no -64 watchdog panic or 0x012B5950 spinlock deadlock)
        val noSdRoot = tempRoot.resolve("fwup-no-sd")
        copyDirectory(stagedRoot, noSdRoot)
        val cfgNoSd = Formats.read(noSdRoot.resolve("configs/emulator.yaml")) + mapOf("telemetry" to mapOf("enabled" to false))
        RelaxedFlashPersistence.prepare(noSdRoot, cfgNoSd)
        EmulatorRuntime(noSdRoot, cfgNoSd, telemetryDisabled = true).use { rt ->
            rt.owner.submit {
                rt.host.patchLatestDirect("sdcard-reader", mapOf("slot1Inserted" to false, "slot2Inserted" to false), persistToDisk = false)
                rt.session.initialize()
            }.join()
            patchComp(rt, "buttons", mapOf("back" to true))
            for (i in 0 until 24) {
                stepBy(rt, 15_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x060a) break
            }
            stepBy(rt, 40_000_000L, maxCalls = 10_000)
            patchComp(rt, "buttons", mapOf("back" to false))
            stepBy(rt, 20_000_000L, maxCalls = 10_000)

            val scr = readU32(rt, 0x01CC6424L)
            val err = readU32(rt, 0x01BE0138L)
            val (mmio, flash) = relaxedCounters(rt)
            assertNotEquals(-64, err, "BUG-V3-9-2(No-SD): Must not hit stack-watchdog panic (-64)")
            assertEquals(0x060a, scr, "BUG-V3-9-2(No-SD): Holding DISP/BACK without SD must reach FWUP scr=0x060a")
            assertTrue(rt.telemetry.publishedFrames >= 1L, "BUG-V3-9-2(No-SD): Expected >= 1 rendered frame")
            assertEquals(0L, mmio, "BUG-V3-9-2(No-SD): Expected 0 [RELAXED/MMIO] fallbacks")
            assertEquals(0L, flash, "BUG-V3-9-2(No-SD): Expected 0 [RELAXED/FLASH] refusals")
        }
    }

    @Test
    fun bugV394PostSdHotReinsertCaptureWritesValidJpeg(@TempDir tempRoot: Path) {
        val stagedRoot = requireStagedRelease()
        val workRoot = tempRoot.resolve("sd-reinsert-capture")
        copyDirectory(stagedRoot, workRoot)
        val cfg = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf("telemetry" to mapOf("enabled" to false))
        RelaxedFlashPersistence.prepare(workRoot, cfg)

        EmulatorRuntime(workRoot, cfg, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            bootToStill(rt)

            val slot1Dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
            val beforeNames = Files.walk(slot1Dcim).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".JPG", ignoreCase = true) }
                    .map { slot1Dcim.relativize(it).toString() }
                    .toList()
                    .toSet()
            }

            // Hot-eject both slots, then hot-reinsert Slot 1 and let firmware remount cleanly.
            patchComp(rt, "sdcard-reader", mapOf("slot1Inserted" to false, "slot2Inserted" to false))
            stepBy(rt, 60_000_000L, maxCalls = 10_000)
            patchComp(rt, "sdcard-reader", mapOf("slot1Inserted" to true))
            stepBy(rt, 160_000_000L, maxCalls = 20_000)

            val jpegBefore = (compState(rt, "soc-jpeg")["encodeCount"] as Number).toLong()
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            var locked = false
            for (step in 1..45) {
                stepBy(rt, 2_000_000L, maxCalls = 5_000)
                val scr = readU32(rt, 0x01CC6424L)
                val s1 = readU32(rt, 0x02C47C5CL + 0x60L) and 0xff
                if (scr == 0x011c && s1 == 8) {
                    locked = true
                    break
                }
            }
            assertTrue(locked, "BUG-V3-9-4: Expected AF-S lock (scr=0x011c, s1=8) after SD hot-reinsert")

            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepBy(rt, 15_000_000L, maxCalls = 5_000)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            stepBy(rt, 180_000_000L, maxCalls = 30_000)

            val jpegAfter = (compState(rt, "soc-jpeg")["encodeCount"] as Number).toLong()
            val newJpegs = Files.walk(slot1Dcim).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".JPG", ignoreCase = true) }
                    .filter { slot1Dcim.relativize(it).toString() !in beforeNames }
                    .toList()
            }
            val (mmio, flash) = relaxedCounters(rt)
            assertTrue(jpegAfter >= jpegBefore + 3L, "BUG-V3-9-4: Expected 3-pass JPEG encode ($jpegBefore -> $jpegAfter)")
            assertEquals(1, newJpegs.size, "BUG-V3-9-4: Expected 1 newly captured JPEG in Slot 1 DCIM after hot-reinsert, found $newJpegs")
            val jpegBytes = Files.readAllBytes(newJpegs.single())
            assertTrue(jpegBytes.size > 100_000, "BUG-V3-9-4: Expected captured JPEG > 100 KiB, got ${jpegBytes.size} B")
            assertEquals(0xFF, jpegBytes[0].toInt() and 0xFF, "Expected JPEG SOI byte 0")
            assertEquals(0xD8, jpegBytes[1].toInt() and 0xFF, "Expected JPEG SOI byte 1")
            assertEquals(0xFF, jpegBytes[jpegBytes.size - 2].toInt() and 0xFF, "Expected JPEG EOI byte -2")
            assertEquals(0xD9, jpegBytes[jpegBytes.size - 1].toInt() and 0xFF, "Expected JPEG EOI byte -1")
            assertEquals(0L, mmio, "BUG-V3-9-4: Expected 0 [RELAXED/MMIO] fallbacks")
            assertEquals(0L, flash, "BUG-V3-9-4: Expected 0 [RELAXED/FLASH] refusals")
        }
    }
}
