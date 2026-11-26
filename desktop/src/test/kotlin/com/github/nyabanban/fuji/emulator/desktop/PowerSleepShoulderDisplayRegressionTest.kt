package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.desktop.panels.AsyncDisplayBridge
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.SingleStepButtonHold
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import com.github.nyabanban.fuji.emulator.runtime.PortRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for `FIX-POWER-SLEEP-AND-SHOULDER-CLOSED-CRASH`:
 * 1. `AsyncDisplayBridge.publishAll` and `EmulatorRuntime.runInteractive` display polling must
 *    NEVER crash with `IllegalStateException: Shoulder display closed` when `shoulder-display`
 *    is closed and reloaded inside the same [PortRegistry] instance (Sub-MCU cold reset or hot-reload).
 * 2. `Dynarmic.resetForColdReset()` must clear `fallbackDiagnostics()` so repeated power cycles
 *    do not accumulate duplicate fastmem fallback region entries (`39 -> 78`).
 * 3. Live `EmulatorRuntime.runInteractive` power-off -> standby sleep -> power-on restart loop
 *    completes cleanly with zero crashes and healthy `display.shoulder` polling.
 */
class PowerSleepShoulderDisplayRegressionTest {
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

    private class CloseableShoulderPort(
        private val widthPx: Int = 304,
        private val heightPx: Int = 230,
    ) : DisplayPort, AutoCloseable {
        private val frame = ArgbFrameBuffer(widthPx, heightPx)
        private var closed = false
        private fun live() { check(!closed) { "Shoulder display closed" } }
        override val width: Int get() { live(); return frame.width }
        override val height: Int get() { live(); return frame.height }
        override val sequence: Long get() { live(); return frame.sequence }
        override fun copyArgb(target: IntArray): Boolean { live(); return frame.copyArgb(target) }
        override fun presentArgb(pixels: IntArray, width: Int, height: Int) { live(); frame.presentArgb(pixels, width, height) }
        override fun close() { closed = true }
    }

    @Test
    fun asyncDisplayBridgeSurvivesShoulderCloseAndReloadOnSamePortRegistry() {
        OwnerLoop().use { owner ->
            owner.submit {
                val ports = PortRegistry(owner)
                val bridge = AsyncDisplayBridge()

                // Cycle 1: publish a frame on display.shoulder via AsyncDisplayBridge
                val port1 = CloseableShoulderPort()
                ports.register("shoulder-display", "display.shoulder", DisplayPort::class.java, port1)
                val frame1 = IntArray(304 * 230) { 0xff112233.toInt() }
                port1.presentArgb(frame1, 304, 230)
                assertEquals(1, bridge.publishAll(ports))
                var consumedColor = 0
                assertTrue(bridge.consumeLatest("display.shoulder") { _, _, _, px -> consumedColor = px[0] })
                assertEquals(0xff112233.toInt(), consumedColor)

                // Simulate component reload / Sub-MCU cold reset WITHOUT changing the PortRegistry instance
                // AND WITHOUT calling bridge.resetForColdReset() first (worst-case hot-reload hazard).
                port1.close()
                ports.removeOwner("shoulder-display")
                assertEquals(0, bridge.publishAll(ports))

                // Cycle 2: re-register a brand-new shoulder DisplayPort under the exact same PortRegistry
                val port2 = CloseableShoulderPort()
                ports.register("shoulder-display", "display.shoulder", DisplayPort::class.java, port2)
                assertNotEquals(port1, port2)
                val frame2 = IntArray(304 * 230) { 0xff445566.toInt() }
                port2.presentArgb(frame2, 304, 230)
                assertEquals(1, bridge.publishAll(ports), "New shoulder DisplayPort on same PortRegistry must publish without stale-port crash")
                assertTrue(bridge.consumeLatest("display.shoulder") { _, _, _, px -> consumedColor = px[0] })
                assertEquals(0xff445566.toInt(), consumedColor)

                // Even if a closed DisplayPort is still temporarily registered before removal, publishAll must not throw
                port2.close()
                assertEquals(0, bridge.publishAll(ports))
                ports.removeOwner("shoulder-display")
            }.join()
        }
    }

    @Test
    fun dynarmicColdResetClearsFallbackDiagnosticsWithoutAccumulatingRegions() {
        val libPath = System.getenv("FUJI_DYNARMIC_LIBRARY")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("componentProjectRoot")).resolve(".tools/dynarmic-v1/build-linux/libfuji_dynarmic.so")
        assumeTrue(Files.isRegularFile(libPath), "Skipping Dynarmic cold-reset diagnostics test: $libPath missing")

        Dynarmic(libPath, 1, 0xFFFF_0000L).use { dyn ->
            // Map a small 4 KiB page that may fall back on 64 KiB Windows/non-64K fastmem alignment,
            // plus standard RAM, then cold-reset and remap the exact same layout.
            dyn.map(0x0000_1000L, 0x1000L, Dynarmic.PERM_ALL)
            val diagBoot1 = dyn.fallbackDiagnostics()
            dyn.resetForColdReset()
            assertTrue(dyn.fallbackDiagnostics().isEmpty(), "resetForColdReset must clear fallbackDiagnostics before remap")
            dyn.map(0x0000_1000L, 0x1000L, Dynarmic.PERM_ALL)
            val diagBoot2 = dyn.fallbackDiagnostics()
            assertEquals(
                diagBoot1,
                diagBoot2,
                "Cold-reset remap must not double/accumulate fastmem fallback diagnostics across power cycles",
            )
        }
    }

    @Test
    fun subMcuStandbyWakeStateWakesOnAutoPowerOffButtonsAndFastTapsWhileGuardingPowerOff() {
        val wake = SubMcuStandbyWakeState()

        // 1. Full power-off (sel 0x11=0x1F00, power=false): momentary buttons MUST NOT wake SoC
        wake.arm(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false, immediateRestart = false)
        for (btn in listOf("shutterHalf", "shutterFull", "play", "afOn")) {
            val press = wake.observeButtonPatch(
                power = false,
                shutterHalf = btn == "shutterHalf",
                shutterFull = btn == "shutterFull",
                play = btn == "play",
                afOn = btn == "afOn",
            )
            assertEquals(null, press, "$btn while power=false must NOT latch wake")
            wake.observeButtonPatch(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false)
            assertEquals(null, wake.pollWakeReason(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false))
        }
        // Power switch OFF -> ON wakes cleanly
        assertEquals(
            "power-switch-rising-edge",
            wake.observeButtonPatch(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false),
        )
        assertTrue(wake.hasPendingWake)
        assertEquals(
            "power-switch-rising-edge",
            wake.pollWakeReason(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false),
        )
        assertFalse(wake.hasPendingWake)

        // 2. Auto Power Off standby (sel 0x11=0x0002, power=true): fast press+release between 15ms polls
        //    on shutterHalf, shutterFull, play, afOn MUST latch and wake cleanly on next poll!
        val expectedReasons = listOf(
            "shutterHalf" to "shutterHalf-rising-with-power-on",
            "shutterFull" to "shutterFull-rising-with-power-on",
            "play" to "play-rising-with-power-on",
            "afOn" to "afOn-rising-with-power-on",
        )
        for ((btn, expected) in expectedReasons) {
            wake.arm(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false, immediateRestart = false)
            // Press then immediately release BEFORE pollWakeReason() runs (simulating <15ms fast click)
            wake.observeButtonPatch(
                power = true,
                shutterHalf = btn == "shutterHalf",
                shutterFull = btn == "shutterFull",
                play = btn == "play",
                afOn = btn == "afOn",
            )
            wake.observeButtonPatch(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false)
            assertTrue(wake.hasPendingWake, "Fast tap of $btn in Auto Power Off standby must stay latched until polled")
            assertEquals(
                expected,
                wake.pollWakeReason(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false),
                "Auto Power Off standby must wake on fast tap of $btn",
            )
        }

        // 3. Rapid power OFF -> ON toggle during Auto Power Off standby between 15ms polls MUST wake cleanly
        wake.arm(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false, immediateRestart = false)
        wake.observeButtonPatch(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false)
        assertFalse(wake.hasPendingWake)
        wake.observeButtonPatch(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false)
        assertEquals(
            "power-switch-rising-edge",
            wake.pollWakeReason(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false),
        )

        // 4. Button already held at standby entry must NOT false-trigger until released and pressed again
        wake.arm(power = true, shutterHalf = true, shutterFull = false, play = false, afOn = false, immediateRestart = false)
        assertEquals(null, wake.pollWakeReason(power = true, shutterHalf = true, shutterFull = false, play = false, afOn = false))
        wake.observeButtonPatch(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false)
        wake.observeButtonPatch(power = true, shutterHalf = true, shutterFull = false, play = false, afOn = false)
        assertEquals(
            "shutterHalf-rising-with-power-on",
            wake.pollWakeReason(power = true, shutterHalf = true, shutterFull = false, play = false, afOn = false),
        )

        // 5. Turning power OFF after a latched standby button press cancels the pending wake
        wake.arm(power = true, shutterHalf = false, shutterFull = false, play = false, afOn = false, immediateRestart = false)
        wake.observeButtonPatch(power = true, shutterHalf = true, shutterFull = false, play = false, afOn = false)
        assertTrue(wake.hasPendingWake)
        wake.observeButtonPatch(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false)
        assertFalse(wake.hasPendingWake)
        assertEquals(null, wake.pollWakeReason(power = false, shutterHalf = false, shutterFull = false, play = false, afOn = false))
    }

    @Test
    fun singleStepButtonHoldReleasesImmediatelyOnColdResetTickWrapAndStandby() {
        var tick = 6_500_000_000L
        var advancing = true
        var nowNs = 1_000_000_000L
        val events = mutableListOf<String>()
        val scheduled = mutableListOf<() -> Unit>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = { _, key -> events.add("press:$key") },
            onRelease = { _, key -> events.add("release:$key") },
            virtualTick = { tick },
            isSessionAdvancing = { advancing },
            nanoTime = { nowNs },
            scheduleDelay = { _, action ->
                scheduled.add(action)
                AutoCloseable { scheduled.remove(action) }
            },
        )

        // Case A: button pressed at pre-sleep tick (6.5B), cold-reset wake drops virtualTick -> 0
        hold.press("mouse:shutterHalf", "shutterHalf")
        tick = 0L
        hold.release("mouse:shutterHalf", "shutterHalf")
        assertEquals(listOf("press:shutterHalf", "release:shutterHalf"), events, "Cold-reset tick wrap must release wake button immediately without sticking")
        assertEquals(0, hold.pendingReleaseCount())

        // Case B: button clicked while session is in standby sleep (isSessionAdvancing = false)
        events.clear()
        advancing = false
        tick = 6_500_000_000L
        hold.press("mouse:play", "play")
        hold.release("mouse:play", "play")
        assertEquals(listOf("press:play", "release:play"), events, "Standby sleep click must release cleanly immediately while SubMcuStandbyWakeState latches wake")
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test
    fun liveInteractivePowerOffStandbyAndPowerOnWakeSurvivesWithoutShoulderClosedCrash(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = System.getenv("FUJI_RELEASE_STAGE")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                (Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) ||
                    Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")) ||
                    Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("interactive-power-cycle")
        copyDirectory(stagedRoot, workRoot)
        val config = LinkedHashMap(Formats.read(workRoot.resolve("configs/emulator.yaml"))).apply {
            // Run runInteractive at full JIT speed (deterministic, fast test) while exercising the exact display polling & standby loop.
            put("realTimePacer", false)
        }

        fun patchButton(rt: EmulatorRuntime, key: String, pressed: Boolean) {
            val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == "buttons" }.revision }.join()
            rt.host.patch("buttons", rev, mapOf(key to pressed)).join()
        }

        fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
            rt.owner.submit {
                val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                mem.read(addr, buf)
                buf.getInt(0)
            }.join()

        fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.sleep(20)
            }
            return predicate()
        }

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            val stopRequested = AtomicBoolean(false)
            val paused = AtomicBoolean(false)
            val interactiveFuture = CompletableFuture.supplyAsync {
                rt.runInteractive(stopRequested = stopRequested, paused = paused)
            }

            try {
                // 1. Wait for interactive loop to boot and settle on Language OOBE (0x0421)
                assertTrue(
                    waitUntil(60_000L) {
                        !interactiveFuture.isDone &&
                            rt.telemetry.virtualTick >= 620_000_000L &&
                            runCatching { readGuestU32(rt, 0x01CC6424L) == 0x0421 }.getOrDefault(false)
                    },
                    "Expected runInteractive to reach OOBE 0x0421 (done=${interactiveFuture.isDone}, tick=${rt.telemetry.virtualTick})",
                )

                // Advance Language -> Area (0x0423) using calibrated virtual-tick hold so power-off skips sensor cleaning
                val holdStartTick = rt.telemetry.virtualTick
                patchButton(rt, "menu", true)
                waitUntil(5_000L) { rt.telemetry.virtualTick >= holdStartTick + 5_000_000L }
                patchButton(rt, "menu", false)
                assertTrue(
                    waitUntil(20_000L) {
                        !interactiveFuture.isDone && runCatching { readGuestU32(rt, 0x01CC6424L) == 0x0423 }.getOrDefault(false)
                    },
                    "Expected Area screen 0x0423 before power-off (scr=0x${runCatching { readGuestU32(rt, 0x01CC6424L) }.getOrDefault(-1).toString(16)}, tick=${rt.telemetry.virtualTick})",
                )

                // Verify display.shoulder is live before power-off
                val shoulderPortBefore = rt.owner.submit {
                    rt.host.ports.find("display.shoulder", DisplayPort::class.java)
                }.join()
                assertNotNull(shoulderPortBefore, "Expected display.shoulder registered during interactive session")

                // 2. Flip power lever OFF -> authentic graceful firmware shutdown -> low-power standby
                patchButton(rt, "power", false)
                assertTrue(
                    waitUntil(30_000L) { !interactiveFuture.isDone && rt.session.isSocPoweredOff },
                    "Expected power=false to enter graceful Sub-MCU standby (done=${interactiveFuture.isDone})",
                )
                // Let runInteractive poll several standby slices to ensure display.shoulder stays live & crash-free
                Thread.sleep(120)
                assertFalse(interactiveFuture.isDone, "runInteractive must remain alive in standby sleep")
                assertTrue(rt.session.isSocPoweredOff)

                // Verify momentary wake buttons while power=false do NOT wake SoC
                patchButton(rt, "shutterHalf", true)
                patchButton(rt, "shutterHalf", false)
                patchButton(rt, "shutterFull", true)
                patchButton(rt, "shutterFull", false)
                patchButton(rt, "afOn", true)
                patchButton(rt, "afOn", false)
                Thread.sleep(60)
                assertTrue(rt.session.isSocPoweredOff, "Momentary buttons while power=false must NOT wake SoC")

                // 3. Flip power lever ON -> Sub-MCU cold-reset wake (reloads shoulder-display on SAME PortRegistry!)
                //    This exact step previously crashed at Main.kt:916 with `Shoulder display closed` at tick=0 frame=0!
                patchButton(rt, "power", true)
                assertTrue(
                    waitUntil(60_000L) {
                        !interactiveFuture.isDone &&
                            !rt.session.isSocPoweredOff &&
                            rt.session.wakeCount == 1L &&
                            runCatching { readGuestU32(rt, 0x01CC6424L) == 0x0423 }.getOrDefault(false)
                    },
                    "Expected power=true wake through runInteractive to reboot cleanly to Area 0x0423 without Shoulder display closed crash (done=${interactiveFuture.isDone}, wake=${rt.session.wakeCount}, tick=${rt.telemetry.virtualTick})",
                )
                assertFalse(interactiveFuture.isDone, "runInteractive must NOT crash after power-on cold reset")

                val shoulderPortAfter = rt.owner.submit {
                    rt.host.ports.find("display.shoulder", DisplayPort::class.java)
                }.join()
                assertNotNull(shoulderPortAfter)
                assertNotEquals(shoulderPortBefore, shoulderPortAfter, "Cold reset must install fresh shoulder DisplayPort")
                val afterSeq = rt.owner.submit { shoulderPortAfter.contentSequence }.join()
                assertTrue(afterSeq >= 0L)
            } finally {
                stopRequested.set(true)
            }

            val finalState = interactiveFuture.get(15, TimeUnit.SECONDS)
            assertNotEquals("crashed", finalState["status"], "Interactive session must stop cleanly, never crash")
        }
    }
}
