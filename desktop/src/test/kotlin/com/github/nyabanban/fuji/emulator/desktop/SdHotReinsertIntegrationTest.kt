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
 * Live Dynarmic JIT verification for BUG-18:
 * Repeatedly hot-unplugging and hot-reinserting SD Slot 1 and Slot 2 on real GXUP0004.DAT firmware
 * must re-initialize and re-mount both SD cards cleanly with ZERO `[RELAXED/MMIO]` fallbacks
 * (specifically zero finite-sample exhaustion on secondary SDHCI CLOCK_CONTROL at 0xFFFC622C / 0xFFFCA22C).
 */
class SdHotReinsertIntegrationTest {
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

    private fun captureMainNonBlack(rt: EmulatorRuntime): Int =
        rt.owner.submit {
            val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val w = port.width
            val h = port.height
            if (w <= 0 || h <= 0) return@submit 0
            val px = IntArray(w * h)
            port.copyArgb(px)
            px.count { (it and 0x00ffffff) != 0 }
        }.join()

    private fun sdHostState(rt: EmulatorRuntime): Map<String, Any?> =
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-sd-host" }.component.state()
        }.join()

    private fun slotCardState(sdState: Map<String, Any?>, slotIdx: Int): String? {
        val slots = sdState["slots"] as? List<*> ?: return null
        val slot = slots.getOrNull(slotIdx) as? Map<*, *> ?: return null
        val card = slot["card"] as? Map<*, *> ?: return null
        return card["state"]?.toString()
    }

    private fun longField(map: Map<*, *>?, key: String): Long =
        (map?.get(key) as? Number)?.toLong() ?: 0L

    @Test
    fun liveFirmwareHotEjectAndHotReinsertRemountsBothSlotsWithZeroMmioFallbacks(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("release-sd-hotplug")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot through OOBE -> Live 1024x768 STILL HUD (Scr=0x0100)
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Language -> Area (0x0423)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Area -> Date/Time (0x0422)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Date/Time -> Start App (0x0482)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L) // Start App -> STILL HUD (0x0100)
            for (i in 0 until 25) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && captureMainNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected Live STILL HUD 0x0100 before SD hot-plug")

            val bootSd = sdHostState(rt)
            assertEquals("TRANSFER", slotCardState(bootSd, 0), "Expected Slot 1 mounted in TRANSFER after cold boot")
            assertEquals("TRANSFER", slotCardState(bootSd, 1), "Expected Slot 2 mounted in TRANSFER after cold boot")

            // 2. Two full live dual-slot hot-eject -> hot-reinsert -> remount cycles
            //    (each cycle re-runs 0xFFFC622C / 0xFFFCA22C SDHCI clock init + full CMD0..CMD7 + FAT32 mount reads)
            for (cycle in 1..2) {
                val beforeSd = sdHostState(rt)
                val beforeCmds = longField(beforeSd, "completedCommands")
                val beforeXfers = longField(beforeSd, "transferCount")
                val beforeClkReads = longField((beforeSd["legacyState"] as? Map<*, *>)?.get("clockState") as? Map<*, *>, "clockReadCount")

                // Hot-eject both slots and let firmware observe CARD_DET/CARD2_DET HIGH (>200ms debounce)
                patchComponent(rt, "sdcard-reader", mapOf("slot1Inserted" to false, "slot2Inserted" to false))
                stepBy(rt, 80_000_000L)
                val ejectedSd = sdHostState(rt)
                assertEquals("IDLE", slotCardState(ejectedSd, 0), "Cycle $cycle: Slot 1 should reset to IDLE on eject")
                assertEquals("IDLE", slotCardState(ejectedSd, 1), "Cycle $cycle: Slot 2 should reset to IDLE on eject")

                // Hot-reinsert both slots and enter PLAY -> STILL to drive full real-firmware media remount
                patchComponent(rt, "sdcard-reader", mapOf("slot1Inserted" to true, "slot2Inserted" to true))
                stepBy(rt, 60_000_000L)
                tapButton(rt, "play", 5_000_000L, 120_000_000L)
                tapButton(rt, "play", 5_000_000L, 80_000_000L)

                val afterSd = sdHostState(rt)
                val afterCmds = longField(afterSd, "completedCommands")
                val afterXfers = longField(afterSd, "transferCount")
                val afterClkReads = longField((afterSd["legacyState"] as? Map<*, *>)?.get("clockState") as? Map<*, *>, "clockReadCount")
                val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
                val sessionState = rt.owner.submit { rt.session.state() }.join()
                val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
                val mmioFallbacks = longField(relaxed, "mmioFallbacks")
                val flashRefusals = longField(relaxed, "flashRefusals")

                assertEquals("TRANSFER", slotCardState(afterSd, 0), "Cycle $cycle: Slot 1 failed to re-mount | report=$report")
                assertEquals("TRANSFER", slotCardState(afterSd, 1), "Cycle $cycle: Slot 2 failed to re-mount | report=$report")
                assertTrue(afterCmds > beforeCmds, "Cycle $cycle: expected SD completedCommands to increase ($beforeCmds -> $afterCmds)")
                assertTrue(afterXfers > beforeXfers, "Cycle $cycle: expected SD transferCount to increase ($beforeXfers -> $afterXfers)")
                assertTrue(afterClkReads > beforeClkReads, "Cycle $cycle: expected secondary SDHCI clock reads to increase ($beforeClkReads -> $afterClkReads)")
                assertEquals(0L, mmioFallbacks, "Cycle $cycle: expected ZERO [RELAXED/MMIO] fallbacks on hot-reinsert | report=$report")
                assertEquals(0L, flashRefusals, "Cycle $cycle: expected ZERO [RELAXED/FLASH] refusals | report=$report")
                assertFalse(
                    report.contains("0xfffc622c", ignoreCase = true) || report.contains("0xfffca22c", ignoreCase = true),
                    "Cycle $cycle: unexpected clock fallback at 0xFFFC622C/0xFFFCA22C | report=$report",
                )
            }

            val finalSd = sdHostState(rt)
            val finalClkReads = longField((finalSd["legacyState"] as? Map<*, *>)?.get("clockState") as? Map<*, *>, "clockReadCount")
            assertTrue(finalClkReads >= 20L, "Expected >=20 live secondary SDHCI clock reads across boot + 2 remount cycles, got $finalClkReads")
            println(
                "[SdHotReinsertIntegrationTest] PASS: 2 full dual-slot hot-eject/reinsert remount cycles succeeded " +
                    "(clockReads=$finalClkReads completedCommands=${longField(finalSd, "completedCommands")} " +
                    "transferCount=${longField(finalSd, "transferCount")} mmioFallbacks=0 flashRefusals=0)",
            )
        }
    }
}
