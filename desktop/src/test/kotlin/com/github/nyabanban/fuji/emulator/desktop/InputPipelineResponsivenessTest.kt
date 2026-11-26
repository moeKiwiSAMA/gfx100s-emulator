package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.desktop.panels.CoalescingButtonDispatcher
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.SingleStepButtonHold
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioInputState
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputPipelineResponsivenessTest {

    @Test
    fun zeroQueueInputPipelineMenuSequenceAndNoStalePressesDeterministic() {
        OwnerLoop().use { owner ->
            val uiExec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "perf5-det-ui").apply { isDaemon = true }
            }
            try {
                val vTick = AtomicLong(1_000_000_000L)
                val appliedLog = mutableListOf<Pair<Map<String, Boolean>, Boolean>>()
                val liveButtons = PanelCatalog.buttonKeys.associateWith { false }.toMutableMap()
                val dispatcher = CoalescingButtonDispatcher(
                    submitOwner = { action -> owner.submit(action) },
                    applyBatchOnOwner = { batch, persistToDisk ->
                        appliedLog += batch to persistToDisk
                        batch.forEach { (k, v) -> liveButtons[k] = v }
                        mapOf("id" to "buttons", "revision" to appliedLog.size.toLong())
                    },
                )
                lateinit var hold: SingleStepButtonHold
                val releaseLatchRef = AtomicReference<CountDownLatch?>(null)
                val model = StudioInputState(
                    send = { key, down ->
                        dispatcher.submit(key, down).whenComplete { _, _ ->
                            if (!down) releaseLatchRef.get()?.countDown()
                        }
                    },
                    dispatch = { action -> uiExec.execute(action) },
                    appliedTickProvider = { vTick.get() },
                    onPressApplied = { key, tick -> hold.markApplied(key, tick) },
                )
                hold = SingleStepButtonHold(
                    policy = MomentaryHoldPolicy.firmwareCalibrated(
                        minHoldVirtualTicks = MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS,
                        maxHoldWallMs = MomentaryHoldPolicy.DEFAULT_MAX_HOLD_WALL_MS,
                        pollIntervalMs = 2L,
                    ),
                    onPress = model::press,
                    onRelease = model::release,
                    onPulse = model::pulse,
                    virtualTick = { vTick.get() },
                    isSessionAdvancing = { true },
                    awaitAppliedTick = true,
                    scheduleDelay = { delayMs, action ->
                        val fut = uiExec.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                        AutoCloseable { fut.cancel(false) }
                    },
                )

                fun simulateQuickTap(key: String, advanceTicks: Long) {
                    val latch = CountDownLatch(1)
                    releaseLatchRef.set(latch)
                    uiExec.submit {
                        hold.press("mouse:$key", key)
                        hold.release("mouse:$key", key)
                    }.get(2, TimeUnit.SECONDS)
                    // Wait for press to reach owner thread, then advance virtual clock past calibrated floor
                    owner.submit {}.get(2, TimeUnit.SECONDS)
                    vTick.addAndGet(advanceTicks)
                    assertTrue(latch.await(2, TimeUnit.SECONDS), "Timed out releasing $key")
                }

                // 1. MENU -> DOWN x5 -> OK -> BACK sequence completes cleanly with zero disk persistence
                val seqStartNanos = System.nanoTime()
                simulateQuickTap("menu", MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
                repeat(5) {
                    simulateQuickTap("down", MomentaryHoldPolicy.CALIBRATED_DIRECTION_SINGLE_STEP_VIRTUAL_TICKS)
                }
                simulateQuickTap("menu", MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
                simulateQuickTap("back", MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
                val seqMs = (System.nanoTime() - seqStartNanos) / 1_000_000L
                assertTrue(seqMs < 1_000L, "Deterministic MENU->DOWNx5->OK->BACK took unexpectedly long: ${seqMs}ms")
                assertTrue(appliedLog.none { it.second }, "Momentary menu keys must never trigger YAML disk writes")

                // 2. Rapid 5x same-key clicks + opposite-key interruption -> zero stuck-key wall-cap stall, zero stale pending presses
                val rapidLatch = CountDownLatch(1)
                releaseLatchRef.set(rapidLatch)
                val rapidStart = System.nanoTime()
                for (i in 1..5) {
                    uiExec.submit { hold.press("mouse:rapid", "down") }.get(2, TimeUnit.SECONDS)
                    owner.submit {}.get(2, TimeUnit.SECONDS)
                    vTick.addAndGet(500_000L)
                    uiExec.submit { hold.release("mouse:rapid", "down") }.get(2, TimeUnit.SECONDS)
                }
                vTick.addAndGet(MomentaryHoldPolicy.CALIBRATED_DIRECTION_SINGLE_STEP_VIRTUAL_TICKS)
                assertTrue(rapidLatch.await(1, TimeUnit.SECONDS), "Rapid 5x DOWN must release without 3000ms stuck-key stall")
                val rapidMs = (System.nanoTime() - rapidStart) / 1_000_000L
                assertTrue(rapidMs < 500L, "Rapid 5x DOWN stalled for ${rapidMs}ms (stuck-key regression)")
                assertEquals(0, uiExec.submit<Int> { hold.pendingReleaseCount() }.get(2, TimeUnit.SECONDS))
                assertFalse(uiExec.submit<Boolean> { model.desired("down") }.get(2, TimeUnit.SECONDS))
                owner.submit {}.get(2, TimeUnit.SECONDS)
                assertTrue(liveButtons.values.none { it }, "All momentary buttons must be released with zero stale queued presses")
            } finally {
                uiExec.shutdownNow()
            }
        }
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst)
                }
            }
        }
    }

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readGuestU8(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(address, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        Triple(display.sequence, display.width to display.height, pixels)
    }.join()

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00ffffff) != 0 }

    private fun countDiffPixels(a: IntArray, b: IntArray): Int {
        var diff = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) diff++
        return diff
    }

    private fun waitTicksDelta(rt: EmulatorRuntime, delta: Long, timeoutMs: Long = 30_000L): Long {
        val start = rt.telemetry.virtualTick
        val target = start + delta
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (rt.telemetry.virtualTick < target && System.nanoTime() < deadline) {
            Thread.sleep(2)
        }
        return rt.telemetry.virtualTick - start
    }

    private fun waitForStillHudReady(rt: EmulatorRuntime, timeoutMs: Long = 60_000L): Long {
        val t0 = System.nanoTime()
        val deadline = t0 + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val scr = readGuestU32(rt, 0x01CC6424L)
            val bDsc = readGuestU8(rt, 0x01CBFCA2L)
            if (scr == 0x0100 && bDsc == 0) {
                val (_, _, px) = captureMain(rt)
                if (countNonBlack(px) > 150_000) {
                    return (System.nanoTime() - t0) / 1_000_000L
                }
            }
            Thread.sleep(20)
        }
        return -1L
    }

    private fun waitForScreenChange(
        rt: EmulatorRuntime,
        baselinePixels: IntArray,
        minDiff: Int = 500,
        timeoutMs: Long = 10_000L,
    ): Pair<Long, IntArray> {
        val t0 = System.nanoTime()
        val deadline = t0 + timeoutMs * 1_000_000L
        var lastSeq = -1L
        while (System.nanoTime() < deadline) {
            val (seq, _, px) = captureMain(rt)
            if (seq != lastSeq) {
                lastSeq = seq
                if (countDiffPixels(baselinePixels, px) >= minDiff) {
                    return ((System.nanoTime() - t0) / 1_000_000L) to px
                }
            }
            Thread.sleep(5)
        }
        val (_, _, px) = captureMain(rt)
        return -1L to px
    }

    private fun waitForScr(rt: EmulatorRuntime, expectedScr: Int, timeoutMs: Long = 20_000L): Long {
        val t0 = System.nanoTime()
        val deadline = t0 + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (readGuestU32(rt, 0x01CC6424L) == expectedScr) {
                return (System.nanoTime() - t0) / 1_000_000L
            }
            Thread.sleep(5)
        }
        return -1L
    }

    @Test
    fun benchmarkLiveMenuNavigationAndDebounceCalibration(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_INPUT_BENCH") == "1",
            "Set FUJI_RUN_INPUT_BENCH=1 to run the live GXUP0004.DAT input latency benchmark",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("perf5-bench-root")
        copyDirectory(stagedRoot, workRoot)
        val cfgPath = workRoot.resolve("configs/emulator.yaml")
        val cfgText = Files.readString(cfgPath).replace("httpPort: 8787", "httpPort: 0")
        Files.writeString(cfgPath, cfgText)
        val config = Formats.read(cfgPath)

        val paused = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val uiExec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "perf5-ui-sim").apply { isDaemon = true }
        }

        try {
            EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
                val runner = Thread.ofPlatform().name("perf5-runner").start {
                    rt.runInteractive(null, paused, stopRequested)
                }
                try {
                    val bootDeadline = System.nanoTime() + 60_000_000_000L
                    while (rt.telemetry.virtualTick < 620_000_000L && System.nanoTime() < bootDeadline) {
                        Thread.sleep(20)
                    }
                    assertTrue(rt.telemetry.virtualTick >= 620_000_000L, "OOBE boot did not reach 620M ticks")

                    val dispatcher = CoalescingButtonDispatcher(
                        submitOwner = { action -> rt.owner.submit(action) },
                        applyBatchOnOwner = { batch, persistToDisk ->
                            rt.telemetry.recordButtonEvent(batch.entries.joinToString(",") { "${it.key}=${it.value}" })
                            rt.host.patchLatestDirect("buttons", batch, persistToDisk = persistToDisk)
                        },
                        latchingKeys = PanelCatalog.latchingButtonKeys,
                    )

                    fun simpleTap(key: String, holdTicks: Long = 3_000_000L, settleTicks: Long = 20_000_000L) {
                        dispatcher.submit(key, true).join()
                        waitTicksDelta(rt, holdTicks)
                        dispatcher.submit(key, false).join()
                        waitTicksDelta(rt, settleTicks)
                    }
                    simpleTap("menu")
                    simpleTap("down")
                    simpleTap("down")
                    simpleTap("menu")
                    simpleTap("menu")
                    simpleTap("menu")
                    val hudWait = waitForStillHudReady(rt, 60_000L)
                    println("[PERF5-AFTER] STILL HUD (0x0100, LiveView active) reached in ${hudWait}ms at vTick=${rt.telemetry.virtualTick}")
                    assertTrue(hudWait >= 0L, "Failed to reach STILL HUD (0x0100)")
                    waitTicksDelta(rt, 8_000_000L)

                    // 1. Direct owner-thread patch latency A/B (zero-disk patchLatestDirect vs legacy atomicYaml patch)
                    val directUs = mutableListOf<Long>()
                    val legacyDiskUs = mutableListOf<Long>()
                    rt.owner.submit {
                        for (i in 0 until 20) {
                            val t0 = System.nanoTime()
                            rt.host.patchLatestDirect("buttons", mapOf("rearDialPress" to true), persistToDisk = false)
                            directUs += (System.nanoTime() - t0) / 1_000L
                            val t1 = System.nanoTime()
                            rt.host.patchLatestDirect("buttons", mapOf("rearDialPress" to false), persistToDisk = false)
                            directUs += (System.nanoTime() - t1) / 1_000L
                        }
                        for (i in 0 until 20) {
                            val t0 = System.nanoTime()
                            val c1 = rt.host.components().single { it.descriptor.id == "buttons" }
                            rt.host.patch("buttons", c1.revision, mapOf("rearDialPress" to true)).join()
                            legacyDiskUs += (System.nanoTime() - t0) / 1_000L
                            val t1 = System.nanoTime()
                            val c2 = rt.host.components().single { it.descriptor.id == "buttons" }
                            rt.host.patch("buttons", c2.revision, mapOf("rearDialPress" to false)).join()
                            legacyDiskUs += (System.nanoTime() - t1) / 1_000L
                        }
                    }.join()
                    directUs.sort()
                    legacyDiskUs.sort()
                    println(
                        "[PERF5-AFTER] Owner-thread button apply (40 ops): BEFORE(atomicYaml) avg=${"%.1f".format(legacyDiskUs.average())}us p50=${legacyDiskUs[20]}us max=${legacyDiskUs.last()}us | AFTER(direct-0disk) avg=${"%.1f".format(directUs.average())}us p50=${directUs[20]}us max=${directUs.last()}us (${"%.1f".format(legacyDiskUs.average() / directUs.average().coerceAtLeast(0.1))}x faster)",
                    )

                    // 2. Full interactive UI stack benchmark: MENU -> DOWN x5 -> OK -> BACK
                    lateinit var hold: SingleStepButtonHold
                    val releaseTimesMs = mutableListOf<Long>()
                    val activeReleaseLatch = AtomicReference<CountDownLatch?>(null)
                    val lastPressNanos = AtomicLong(0L)
                    val uiModel = StudioInputState(
                        send = { key, down ->
                            dispatcher.submit(key, down).whenComplete { _, _ ->
                                if (!down) {
                                    releaseTimesMs += (System.nanoTime() - lastPressNanos.get()) / 1_000_000L
                                    activeReleaseLatch.get()?.countDown()
                                }
                            }
                        },
                        dispatch = { action -> uiExec.execute(action) },
                        appliedTickProvider = { rt.telemetry.virtualTick },
                        onPressApplied = { key, tick -> hold.markApplied(key, tick) },
                    )
                    hold = SingleStepButtonHold(
                        policy = MomentaryHoldPolicy.firmwareCalibrated(),
                        onPress = uiModel::press,
                        onRelease = uiModel::release,
                        onPulse = uiModel::pulse,
                        virtualTick = { rt.telemetry.virtualTick },
                        isSessionAdvancing = { rt.telemetry.running && !rt.telemetry.paused },
                        awaitAppliedTick = true,
                        scheduleDelay = { delayMs, action ->
                            val fut = uiExec.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                            AutoCloseable { fut.cancel(false) }
                        },
                    )

                    fun uiQuickClick(key: String, waitScr: Int? = null, expectDiff: Boolean = true): Long {
                        val (_, _, beforePx) = captureMain(rt)
                        val relLatch = CountDownLatch(1)
                        activeReleaseLatch.set(relLatch)
                        val t0 = System.nanoTime()
                        lastPressNanos.set(t0)
                        uiExec.submit {
                            hold.press("mouse:bench-$key", key)
                        }.get(5, TimeUnit.SECONDS)
                        Thread.sleep(30)
                        uiExec.submit {
                            hold.release("mouse:bench-$key", key)
                        }.get(5, TimeUnit.SECONDS)
                        assertTrue(relLatch.await(10, TimeUnit.SECONDS), "Release did not finish for $key")
                        val screenMs = if (waitScr != null) {
                            val w = waitForScr(rt, waitScr, 15_000L)
                            if (w >= 0L) (System.nanoTime() - t0) / 1_000_000L else -1L
                        } else if (expectDiff) {
                            val (w, _) = waitForScreenChange(rt, beforePx, minDiff = 500, timeoutMs = 8_000L)
                            if (w >= 0L) (System.nanoTime() - t0) / 1_000_000L else -1L
                        } else {
                            (System.nanoTime() - t0) / 1_000_000L
                        }
                        waitTicksDelta(rt, 3_000_000L)
                        return screenMs
                    }

                    val seqStart = System.nanoTime()
                    val menuMs = uiQuickClick("menu", waitScr = 0x0400)
                    val downLatencies = (1..5).map { uiQuickClick("down", expectDiff = true) }
                    val okMs = uiQuickClick("menu", expectDiff = true)
                    val back1Ms = uiQuickClick("back", expectDiff = true)
                    val back2Ms = if (readGuestU32(rt, 0x01CC6424L) != 0x0100) uiQuickClick("back", waitScr = 0x0100) else 0L
                    val totalSeqMs = (System.nanoTime() - seqStart) / 1_000_000L
                    assertTrue(menuMs >= 0L, "Calibrated 3.0M MENU click must enter Main Menu 0x0400")
                    println(
                        "[PERF5-AFTER] Sequence MENU->DOWNx5->OK->BACK: total=${totalSeqMs}ms menu=${menuMs}ms downs=$downLatencies ok=${okMs}ms back1=${back1Ms}ms back2=${back2Ms}ms releaseHoldMs=$releaseTimesMs",
                    )

                    // 3. Rapid 5x DOWN stress test: must NEVER hit 3000ms wall-cap stuck-key bug!
                    if (readGuestU32(rt, 0x01CC6424L) != 0x0400) {
                        simpleTap("menu", 3_000_000L, 15_000_000L)
                        waitForScr(rt, 0x0400, 15_000L)
                    }
                    val rapidRelLatch = CountDownLatch(1)
                    activeReleaseLatch.set(rapidRelLatch)
                    val rapidLastReleaseNanos = AtomicLong(0L)
                    for (click in 1..5) {
                        uiExec.submit { hold.press("mouse:bench-rapid", "down") }.get(5, TimeUnit.SECONDS)
                        Thread.sleep(20)
                        rapidLastReleaseNanos.set(System.nanoTime())
                        uiExec.submit { hold.release("mouse:bench-rapid", "down") }.get(5, TimeUnit.SECONDS)
                        Thread.sleep(20)
                    }
                    val releasedOk = rapidRelLatch.await(10, TimeUnit.SECONDS)
                    val postReleaseStickMs = (System.nanoTime() - rapidLastReleaseNanos.get()) / 1_000_000L
                    val pendingAfter = uiExec.submit<Int> { hold.pendingReleaseCount() }.get(5, TimeUnit.SECONDS)
                    val desiredDownAfter = uiExec.submit<Boolean> { uiModel.desired("down") }.get(5, TimeUnit.SECONDS)
                    println(
                        "[PERF5-AFTER] Rapid 5x DOWN stress: released=$releasedOk postReleaseStickMs=${postReleaseStickMs}ms pendingTimers=$pendingAfter desiredDown=$desiredDownAfter",
                    )
                    assertTrue(releasedOk, "Rapid 5x DOWN did not release")
                    assertTrue(postReleaseStickMs < 2_400L, "Rapid 5x DOWN still hit stuck-key ceiling: ${postReleaseStickMs}ms")
                    assertEquals(0, pendingAfter)
                    assertFalse(desiredDownAfter)
                } finally {
                    stopRequested.set(true)
                    runner.join(15_000L)
                }
            }
        } finally {
            uiExec.shutdownNow()
        }
    }
}
