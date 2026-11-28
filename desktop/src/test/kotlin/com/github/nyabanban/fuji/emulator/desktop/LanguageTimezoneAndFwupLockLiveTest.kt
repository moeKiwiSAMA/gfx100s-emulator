package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.desktop.panels.CoalescingButtonDispatcher
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioInputState
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.NorFlashTool
import com.github.nyabanban.fuji.emulator.runtime.RelaxedFlashPersistence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end live stock GFX100S 2.13 regression tests for:
 * 1. Multi-boot Language + Area/Timezone persistence across BOTH OOBE (`0x0421` / `0x0423`) AND
 *    in-camera `MENU -> SET UP -> USER SETTING` (`0x045E` -> `0x042A` `AREA SETTING` & `0x042B` `言語/LANG.`),
 *    including direct `EmulatorRuntime.close()` / shutdown-hook drain without manual camera `OFF` toggle,
 *    plus automatic self-healing of stale `submcu-nvram.json` when `nor-flash.bin` has an erased `BlockA`.
 * 2. Locking `DISP/BACK` (`StudioInputState.lock("back")` + `CoalescingButtonDispatcher` keeping `back: false`
 *    in `buttons.yaml` on disk) + toggling `Power ON` entering authentic Firmware Update (`FWUP`, `scr = 0x0601`)
 *    both when starting from full `Power OFF` AND when flipping `Power OFF -> Lock DISP/BACK -> Power ON`
 *    immediately while the 6-second shutdown sequence is still draining in the background.
 */
class LanguageTimezoneAndFwupLockLiveTest {
    private fun copyReleaseTree(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    val name = path.fileName.toString()
                    if (name.endsWith(".jar") || name.endsWith(".so") || name.endsWith(".so.2")) {
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
                Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")),
            "Requires :stageReleaseLinux output with runtime/templates/factory-flash/nor-flash-2.13.bin",
        )
        return stagedRoot
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private data class LangAndTz(
        val language: Int,
        val homeSign: Int,
        val homeHours: Int,
        val homeMinutes: Int,
    )

    private fun readLangAndHomeTzRam(rt: EmulatorRuntime): LangAndTz =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val blk = ByteBuffer.allocate(0x1000)
            mem.read(0x01C7339CL, blk)
            LangAndTz(
                language = blk.get(0x01EB).toInt() and 0xFF,
                homeSign = blk.get(0x0E4C).toInt() and 0xFF,
                homeHours = blk.get(0x0E4E).toInt() and 0xFF,
                homeMinutes = blk.get(0x0E50).toInt() and 0xFF,
            )
        }.join()

    private fun readNorBlockASlot(norPath: Path, slot: Int): ByteArray {
        val buf = ByteArray(0x2000)
        RandomAccessFile(norPath.toFile(), "r").use { raf ->
            raf.seek(NorFlashTool.BLOCK_A_OFFSET + slot.toLong() * 0x2000L)
            raf.readFully(buf)
        }
        return buf
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(8)
        }
        return predicate()
    }

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

    @Test
    fun languageAndTimezonePersistAcrossBootsFromBothOobeAndSetupMenuIncludingDirectCloseAndSelfHeal(@TempDir tempRoot: Path) {
        val stagedRoot = requireStagedRelease()
        val workRoot = tempRoot.resolve("lang-tz-multiboot")
        copyReleaseTree(stagedRoot, workRoot)
        val liveNor = workRoot.resolve("flash/nor-flash.bin")
        val factoryNor = workRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")
        val subMcuNvram = workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json")
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "audio" to false,
            "httpPort" to 0,
            "telemetry" to mapOf("enabled" to false),
        )

        fun tapInteractive(rt: EmulatorRuntime, fut: CompletableFuture<*>, key: String, holdTicks: Long, settleTicks: Long) {
            val t0 = rt.telemetry.virtualTick
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
            assertTrue(
                waitUntil(20_000L) { fut.isDone || rt.telemetry.virtualTick >= t0 + holdTicks },
                "Timed out holding '$key'",
            )
            val t1 = rt.telemetry.virtualTick
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
            assertTrue(
                waitUntil(25_000L) { fut.isDone || rt.telemetry.virtualTick >= t1 + settleTicks },
                "Timed out settling after '$key'",
            )
        }

        lateinit var oobeCommitted: LangAndTz

        // Boot #1: OOBE sets BOTH non-English Language AND non-zero Area/Timezone, then closes directly via
        // EmulatorRuntime.close() WITHOUT caller pre-setting stopRequested or power=false (exercises shutdown-hook path).
        var boot1Future: CompletableFuture<Map<String, Any?>>? = null
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt1 ->
            val paused = AtomicBoolean(false)
            val stopRequested = AtomicBoolean(false)
            val fut = CompletableFuture.supplyAsync { rt1.runInteractive(paused = paused, stopRequested = stopRequested) }
            boot1Future = fut

            assertTrue(
                waitUntil(60_000L) {
                    !fut.isDone &&
                        (rt1.telemetry.stageMask and FirmwareStage.STAGE_6_INTERACTIVE_UI.bit) != 0 &&
                        runCatching { readU32(rt1, 0x01CC6424L) == 0x0421 }.getOrDefault(false)
                },
                "Expected Boot #1 to reach OOBE Language 0x0421",
            )
            assertEquals(LangAndTz(1, 0, 0, 0), readLangAndHomeTzRam(rt1), "Factory OOBE defaults must be ENGLISH (1) + GMT 0:00")

            // Select non-English language on 0x0421 (DOWN x1 -> MENU)
            tapInteractive(rt1, fut, "down", 5_000_000L, 25_000_000L)
            tapInteractive(rt1, fut, "menu", 5_000_000L, 45_000_000L)
            assertTrue(
                waitUntil(15_000L) { !fut.isDone && runCatching { readU32(rt1, 0x01CC6424L) == 0x0423 }.getOrDefault(false) },
                "Expected OOBE Area 0x0423",
            )

            // Select non-zero Area on 0x0423 (RIGHT x4 -> DOWN x2 -> MENU)
            repeat(4) { tapInteractive(rt1, fut, "right", 5_000_000L, 18_000_000L) }
            repeat(2) { tapInteractive(rt1, fut, "down", 5_000_000L, 25_000_000L) }
            tapInteractive(rt1, fut, "menu", 5_000_000L, 45_000_000L)
            assertTrue(
                waitUntil(20_000L) { !fut.isDone && runCatching { readU32(rt1, 0x01CC6424L) == 0x0422 }.getOrDefault(false) },
                "Expected OOBE Date/Time 0x0422",
            )

            // Confirm Date/Time -> STILL HUD 0x0100
            tapInteractive(rt1, fut, "menu", 5_000_000L, 45_000_000L)
            if (readU32(rt1, 0x01CC6424L) == 0x0482) {
                tapInteractive(rt1, fut, "menu", 5_000_000L, 40_000_000L)
            }
            assertTrue(
                waitUntil(20_000L) { !fut.isDone && runCatching { readU32(rt1, 0x01CC6424L) == 0x0100 }.getOrDefault(false) },
                "Expected STILL HUD 0x0100 after OOBE",
            )
            oobeCommitted = readLangAndHomeTzRam(rt1)
            assertNotEquals(1, oobeCommitted.language, "Expected non-English bLanguage after OOBE")
            assertTrue(oobeCommitted.homeHours > 0, "Expected non-zero bHomeTimeHours after OOBE, got $oobeCommitted")
            // Leave stopRequested=false and power=true; exiting `.use` calls rt1.close() -> requestGracefulStopAndAwait()!
        }
        val stopState1 = checkNotNull(boot1Future).get(5, TimeUnit.SECONDS)
        assertNotEquals("crashed", stopState1["status"])

        val slot1 = readNorBlockASlot(liveNor, 1)
        assertEquals(oobeCommitted.language, slot1[0x02E7].toInt() and 0xFF, "NOR BlockA Slot 1 must persist OOBE bLanguage")
        assertEquals(oobeCommitted.homeHours, slot1[0x0F4A].toInt() and 0xFF, "NOR BlockA Slot 1 must persist OOBE bHomeTimeHours")

        lateinit var menuCommitted: LangAndTz

        // Boot #2: Verify Boot #1 OOBE Language + Timezone reloaded, then edit BOTH inside MENU -> SET UP -> USER SETTING
        // and close directly while still inside USER SETTING (scr = 0x045E)!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt2 ->
            val paused = AtomicBoolean(false)
            val stopRequested = AtomicBoolean(false)
            val fut = CompletableFuture.supplyAsync { rt2.runInteractive(paused = paused, stopRequested = stopRequested) }
            try {
                assertTrue(
                    waitUntil(60_000L) {
                        !fut.isDone &&
                            (rt2.telemetry.stageMask and FirmwareStage.STAGE_6_INTERACTIVE_UI.bit) != 0 &&
                            runCatching { readU32(rt2, 0x01CC6424L) == 0x0100 }.getOrDefault(false)
                    },
                    "Boot #2 must skip OOBE directly to STILL HUD 0x0100",
                )
                assertEquals(oobeCommitted, readLangAndHomeTzRam(rt2), "Boot #2 must reload BOTH OOBE Language and Timezone")

                // Open MENU -> LEFT -> 4x DOWN (SET UP) -> RIGHT -> MENU (USER SETTING = 0x045E)
                tapInteractive(rt2, fut, "menu", 5_000_000L, 35_000_000L)
                tapInteractive(rt2, fut, "left", 5_000_000L, 20_000_000L)
                repeat(4) { tapInteractive(rt2, fut, "down", 5_000_000L, 18_000_000L) }
                tapInteractive(rt2, fut, "right", 5_000_000L, 20_000_000L)
                tapInteractive(rt2, fut, "menu", 5_000_000L, 25_000_000L)
                assertEquals(0x045E, readU32(rt2, 0x01CC6424L), "Expected USER SETTING 0x045E")

                // Row 1: AREA SETTING (0x042A) -> RIGHT x7 -> MENU confirm
                tapInteractive(rt2, fut, "down", 5_000_000L, 18_000_000L)
                tapInteractive(rt2, fut, "menu", 5_000_000L, 30_000_000L)
                assertEquals(0x042A, readU32(rt2, 0x01CC6424L), "Expected in-menu AREA SETTING 0x042A")
                repeat(7) { tapInteractive(rt2, fut, "right", 5_000_000L, 18_000_000L) }
                tapInteractive(rt2, fut, "menu", 5_000_000L, 35_000_000L)
                assertEquals(0x045E, readU32(rt2, 0x01CC6424L), "Expected return to USER SETTING 0x045E after AREA confirm")

                // Row 4: 言語/LANG. (0x042B) -> DOWN x6 -> MENU confirm
                repeat(3) { tapInteractive(rt2, fut, "down", 5_000_000L, 18_000_000L) }
                tapInteractive(rt2, fut, "menu", 5_000_000L, 30_000_000L)
                assertEquals(0x042B, readU32(rt2, 0x01CC6424L), "Expected in-menu LANGUAGE 0x042B")
                repeat(6) { tapInteractive(rt2, fut, "down", 5_000_000L, 18_000_000L) }
                tapInteractive(rt2, fut, "menu", 5_000_000L, 40_000_000L)
                assertEquals(0x045E, readU32(rt2, 0x01CC6424L), "Expected return to USER SETTING 0x045E after LANGUAGE confirm")

                menuCommitted = readLangAndHomeTzRam(rt2)
                assertNotEquals(oobeCommitted.language, menuCommitted.language, "In-menu LANGUAGE edit must update bLanguage")
                assertNotEquals(oobeCommitted.homeHours, menuCommitted.homeHours, "In-menu AREA SETTING edit must update bHomeTimeHours")
            } finally {
                rt2.requestGracefulStopAndAwait(32_000L)
            }
            val stopState2 = fut.get(5, TimeUnit.SECONDS)
            assertNotEquals("crashed", stopState2["status"])
            assertTrue(rt2.session.isSocPoweredOff, "Close directly from USER SETTING 0x045E must complete graceful power-off")
        }

        // Boot #3: Cold restart must skip OOBE and reload BOTH the new in-menu Language AND new Area/Timezone!
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt3 ->
            rt3.owner.submit { rt3.session.initialize() }.join()
            for (i in 0 until 45) {
                stepBy(rt3, 20_000_000L, maxCalls = 10_000)
                if (readU32(rt3, 0x01CC6424L) == 0x0100) break
            }
            assertEquals(0x0100, readU32(rt3, 0x01CC6424L), "Boot #3 must skip OOBE directly to STILL HUD 0x0100")
            assertEquals(
                menuCommitted,
                readLangAndHomeTzRam(rt3),
                "Boot #3 must persist BOTH Language and Timezone changed inside MENU -> SET UP -> USER SETTING",
            )
        }

        // Boot #4: Self-healing check — if user manually overwrites flash/nor-flash.bin with a fresh factory image
        // while stale submcu-nvram.json still exists, RelaxedFlashPersistence.prepare() clears stale submcu-nvram.json!
        assertTrue(Files.isRegularFile(subMcuNvram), "Expected submcu-nvram.json before factory NOR overwrite")
        Files.copy(factoryNor, liveNor, StandardCopyOption.REPLACE_EXISTING)
        assertTrue(NorFlashTool.isBlockAErased(liveNor), "Factory NOR template must have erased BlockA")
        RelaxedFlashPersistence.prepare(workRoot, config)
        assertFalse(Files.exists(subMcuNvram), "RelaxedFlashPersistence.prepare must clear stale submcu-nvram.json when BlockA is erased")
    }

    @Test
    fun lockingDispBackAndPoweringOnEntersAuthenticFirmwareUpdateModeInOffFirstAndRapidToggleFlows(@TempDir tempRoot: Path) {
        val stagedRoot = requireStagedRelease()
        val workRoot = tempRoot.resolve("fwup-lock-disp-back")
        copyReleaseTree(stagedRoot, workRoot)
        val buttonsYaml = workRoot.resolve("configs/components/buttons/buttons.yaml")
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "audio" to false,
            "httpPort" to 0,
            "telemetry" to mapOf("enabled" to false),
        )
        RelaxedFlashPersistence.prepare(workRoot, config)

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            // Wire StudioInputState + CoalescingButtonDispatcher identically to EmulatorApp.buttonDispatcher
            // (persisting ONLY latching switches to buttons.yaml so momentary keys remain false on disk).
            val dispatcher = CoalescingButtonDispatcher(
                submitOwner = { action -> rt.owner.submit(action) },
                applyBatchOnOwner = { batch, persistToDisk ->
                    val state = rt.host.patchLatestDirect("buttons", batch, persistToDisk = false)
                    if (persistToDisk) {
                        val live = rt.host.components().single { it.descriptor.id == "buttons" }
                        val persisted = LinkedHashMap<String, Any>()
                        for ((k, v) in live.config) {
                            persisted[k] = if (k in PanelCatalog.latchingButtonKeys) v else false
                        }
                        Formats.atomicYaml(live.configFile, persisted)
                    }
                    state
                },
                latchingKeys = PanelCatalog.latchingButtonKeys,
                onError = { err -> throw AssertionError("Unexpected button dispatch error", err) },
            )
            val inputs = StudioInputState(
                send = { key, down -> dispatcher.submit(key, down) },
                changed = {},
                failure = { err -> throw AssertionError("Unexpected StudioInputState error", err) },
                latchingKeys = PanelCatalog.latchingButtonKeys,
            )
            fun syncInputsFromHardware() {
                val state = rt.owner.submit {
                    rt.host.components().single { it.descriptor.id == "buttons" }.component.state()
                }.join()
                inputs.observe(state)
            }
            fun flushDispatcher() {
                // Ensure any async CoalescingButtonDispatcher batch has completed on owner
                rt.owner.submit { }.join()
                rt.owner.submit { }.join()
            }
            fun assertAuthenticFwup0601(label: String) {
                for (i in 0 until 28) {
                    stepBy(rt, 15_000_000L, maxCalls = 10_000)
                    if (readU32(rt, 0x01CC6424L) == 0x0601) break
                }
                stepBy(rt, 35_000_000L, maxCalls = 10_000)
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
                val sessState = rt.owner.submit { rt.session.state() }.join()
                val rf = sessState["relaxedFallback"] as? Map<*, *>
                val mmio = (rf?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
                val flash = (rf?.get("flashRefusals") as? Number)?.toLong() ?: 0L
                assertNotEquals(-64, err, "$label: Must not hit stack-watchdog panic (-64)")
                assertEquals(0x0601, scr, "$label: Locked DISP/BACK + Power ON must enter FWUP scr=0x0601")
                assertEquals(0x200803fcL, bgColor, "$label: FWUP must program white BGCOLOR 0x200803fc")
                assertTrue(whitePx > 150_000, "$label: Expected white backdrop > 150k px, got $whitePx")
                assertTrue(blackTextPx > 1_000, "$label: Expected black text > 1k px, got $blackTextPx")
                assertEquals(0L, mmio, "$label: Expected 0 [RELAXED/MMIO] fallbacks")
                assertEquals(0L, flash, "$label: Expected 0 [RELAXED/FLASH] refusals")
            }

            // Initial boot to OOBE 0x0421
            rt.owner.submit { rt.session.initialize() }.join()
            syncInputsFromHardware()
            for (i in 0 until 35) {
                stepBy(rt, 20_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected initial boot to reach OOBE 0x0421")

            // Flow A: Turn Power OFF -> wait for full SoC power-off -> Lock DISP/BACK -> Turn Power ON -> FWUP 0x0601!
            inputs.toggle("power")
            flushDispatcher()
            for (i in 0 until 15) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Flow A: Expected SoC powered off after toggling power=false")

            assertTrue(inputs.lock("back"), "Flow A: Locking DISP/BACK ('back') must succeed")
            flushDispatcher()
            assertEquals(false, Formats.read(buttonsYaml)["back"], "buttons.yaml on disk must keep momentary 'back' == false")
            assertTrue(
                rt.owner.submit { requireNotNull(rt.host.ports.find("buttons", ButtonPort::class.java)).pressed("back") }.join(),
                "Live ButtonPort must report back=true after lock",
            )

            // Press Power ON -> triggers performSubMcuColdResetOnOwner() -> must preserve back=true and enter 0x0601!
            inputs.toggle("power")
            flushDispatcher()
            rt.owner.submit { rt.session.stepFast() }.join()
            syncInputsFromHardware()
            assertTrue(inputs.isLocked("back"), "Flow A: StudioInputState must keep DISP/BACK locked across cold reset")
            assertTrue(
                rt.owner.submit { requireNotNull(rt.host.ports.find("buttons", ButtonPort::class.java)).pressed("back") }.join(),
                "Flow A: performSubMcuColdResetOnOwner must preserve locked back=true in memory across cold reset",
            )
            assertAuthenticFwup0601("Flow A (Off -> Lock DISP/BACK -> Power ON)")

            // Unlock DISP/BACK, power OFF -> ON back to normal OOBE 0x0421
            assertTrue(inputs.unlock("back"))
            flushDispatcher()
            inputs.toggle("power")
            flushDispatcher()
            for (i in 0 until 15) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean power-off from FWUP")
            inputs.toggle("power")
            flushDispatcher()
            rt.owner.submit { rt.session.stepFast() }.join()
            for (i in 0 until 35) {
                stepBy(rt, 20_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Unlocked cold boot must return to normal OOBE 0x0421")

            // Flow B: Lock DISP/BACK FIRST while camera is ON -> Power OFF -> Power ON -> FWUP 0x0601!
            assertTrue(inputs.lock("back"), "Flow B: Locking DISP/BACK while camera is ON must succeed")
            flushDispatcher()
            inputs.toggle("power")
            flushDispatcher()
            for (i in 0 until 15) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Flow B: Expected SoC powered off with DISP/BACK locked")
            inputs.toggle("power")
            flushDispatcher()
            rt.owner.submit { rt.session.stepFast() }.join()
            syncInputsFromHardware()
            assertTrue(inputs.isLocked("back"), "Flow B: DISP/BACK must remain locked across ON -> OFF -> ON cycle")
            assertAuthenticFwup0601("Flow B (Lock DISP/BACK while ON -> Power OFF -> Power ON)")

            // Unlock DISP/BACK, power OFF -> ON back to normal OOBE 0x0421 for Flow C
            assertTrue(inputs.unlock("back"))
            flushDispatcher()
            inputs.toggle("power")
            flushDispatcher()
            for (i in 0 until 15) {
                if (rt.session.isSocPoweredOff) break
                stepBy(rt, 30_000_000L, maxCalls = 10_000)
            }
            assertTrue(rt.session.isSocPoweredOff, "Expected clean power-off before Flow C")
            inputs.toggle("power")
            flushDispatcher()
            rt.owner.submit { rt.session.stepFast() }.join()
            for (i in 0 until 35) {
                stepBy(rt, 20_000_000L, maxCalls = 10_000)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE 0x0421 before Flow C")

            // Complete OOBE -> STILL HUD 0x0100 (where shutdown runs 154M-tick sensor clean + NOR BlockA flush),
            // then test Flow C: Power OFF -> step 20M ticks (screen blanks while SoC is still draining 154M-tick shutdown!) ->
            // Lock DISP/BACK -> Immediate Power ON -> must auto-restart into FWUP 0x0601 once shutdown finishes!
            fun tapDirect(key: String, hold: Long, settle: Long) {
                rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
                stepBy(rt, hold, maxCalls = 10_000)
                rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
                stepBy(rt, settle, maxCalls = 10_000)
            }
            tapDirect("menu", 5_000_000L, 45_000_000L)
            tapDirect("down", 5_000_000L, 25_000_000L)
            tapDirect("down", 5_000_000L, 25_000_000L)
            tapDirect("menu", 5_000_000L, 45_000_000L)
            tapDirect("menu", 5_000_000L, 45_000_000L)
            if (readU32(rt, 0x01CC6424L) == 0x0482) {
                tapDirect("menu", 5_000_000L, 40_000_000L)
            }
            for (i in 0 until 30) {
                if (readU32(rt, 0x01CC6424L) == 0x0100) break
                stepBy(rt, 15_000_000L, maxCalls = 10_000)
            }
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100 before Flow C")

            val wakeBeforeFlowC = rt.session.wakeCount
            inputs.toggle("power")
            flushDispatcher()
            stepBy(rt, 20_000_000L, maxCalls = 5_000)
            assertFalse(rt.session.isSocPoweredOff, "Flow C precondition: 0x0100 154M-tick shutdown (0x0503 sensor clean) must still be draining at +20M ticks")

            assertTrue(inputs.lock("back"), "Flow C: Locking DISP/BACK during shutdown must succeed")
            inputs.toggle("power")
            flushDispatcher()

            // Stock 2.13 finishes sensor cleaning (~150M ticks), writes sel 0x11 = 0x0002 + 0xFFF4A400 = 0,
            // and because sawPowerSwitchOffDuringBoot && powerNow == true, Sub-MCU must immediately cold-restart into FWUP 0x0601!
            for (i in 0 until 18) {
                if (rt.session.wakeCount > wakeBeforeFlowC) break
                stepBy(rt, 25_000_000L, maxCalls = 10_000)
            }
            assertTrue(
                rt.session.wakeCount > wakeBeforeFlowC,
                "Flow C: Flipping Power back ON before 0x1F00 shutdown completes must immediately cold-restart instead of stranding powered off",
            )
            syncInputsFromHardware()
            assertTrue(inputs.isLocked("back"), "Flow C: DISP/BACK must stay locked after rapid Power OFF -> ON")
            assertAuthenticFwup0601("Flow C (0x0100 Power OFF -> Lock DISP/BACK -> Immediate Power ON during shutdown)")
        }
    }
}
