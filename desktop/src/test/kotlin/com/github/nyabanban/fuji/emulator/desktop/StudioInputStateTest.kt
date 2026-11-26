package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.SingleStepButtonHold
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioInputState
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import kotlin.test.*

class StudioInputStateTest {
    @Test fun mouseAndKeyboardShareOwnershipAndPulseDoesNotReleaseAnotherHeldSource() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        model.press("mouse:menu", "menu"); model.press("keyboard:menu", "menu")
        model.release("mouse:menu", "menu"); assertEquals(listOf("menu" to true), sent)
        model.pulse("menu"); assertEquals(listOf("menu" to true), sent)
        model.release("keyboard:menu", "menu"); assertEquals(listOf("menu" to true, "menu" to false), sent)
    }
    @Test fun directionAndFocusLossReleaseTransientInputsButPreserveExplicitPowerAndDebugLatch() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        model.toggle("power"); model.press("mouse:menu", "menu"); model.releaseTransient()
        assertFalse(model.desired("menu")); assertTrue(model.desired("power"))
        model.setLatch(true); model.toggle("menu"); model.releaseTransient()
        assertTrue(model.desired("menu")); assertTrue(model.desired("power"))
        model.setLatch(false); assertFalse(model.desired("menu")); assertTrue(model.desired("power"))
        assertEquals(0, model.pending)
    }
    @Test fun ownerSubmissionIsNotAnAcknowledgementAndFailuresRemainVisible() {
        val pending = mutableListOf<CompletableFuture<Unit>>()
        val failures = mutableListOf<Throwable>()
        val model = StudioInputState({ _, _ -> CompletableFuture<Unit>().also(pending::add) }, failure = { failures += it })
        model.observe(PanelCatalog.buttonKeys.associateWith { false })
        model.press("mouse:menu", "menu")
        assertTrue(model.desired("menu")); assertEquals(false, model.observed("menu")); assertEquals(1, model.pending)
        pending[0].completeExceptionally(IllegalStateException("queue full"))
        assertFalse(model.desired("menu")); assertEquals(false, model.observed("menu")); assertEquals(1, failures.size)
        assertEquals(1L, model.failureCount); assertEquals(0, model.pending)
    }
    @Test fun releaseAllRetriesEveryKeyAfterARejectedReleaseRatherThanSilentlyDroppingIt() {
        val pending = mutableListOf<Pair<Pair<String, Boolean>, CompletableFuture<Unit>>>()
        val model = StudioInputState({ key, down -> CompletableFuture<Unit>().also { pending += (key to down) to it } })
        model.press("mouse:menu", "menu"); pending.last().second.complete(Unit)
        model.observe(PanelCatalog.buttonKeys.associateWith { it == "menu" })
        model.release("mouse:menu", "menu"); pending.last().second.completeExceptionally(IllegalStateException("queue full"))
        assertTrue(model.desired("menu")); assertEquals(true, model.observed("menu"))
        val start = pending.size; model.releaseAll()
        val retried = pending.drop(start)
        assertEquals(PanelCatalog.buttonKeys, retried.map { it.first.first }.toSet())
        assertTrue(retried.all { !it.first.second }); retried.forEach { it.second.complete(Unit) }
        model.observe(PanelCatalog.buttonKeys.associateWith { false }); assertEquals(false, model.observed("menu"))
    }
    @Test fun anOldFailedPressDoesNotRollBackANewerReleaseAndCallbackDispatchIsExplicit() {
        val futures = mutableListOf<CompletableFuture<Unit>>(); val callbacks = mutableListOf<() -> Unit>()
        val model = StudioInputState({ _, _ -> CompletableFuture<Unit>().also(futures::add) }, dispatch = { callbacks += it })
        model.press("mouse:up", "up"); model.release("mouse:up", "up")
        futures[0].completeExceptionally(IllegalStateException("old")); futures[1].complete(Unit)
        assertEquals(2, model.pending); callbacks.forEach { it() }
        assertFalse(model.desired("up")); assertEquals(0, model.pending); assertEquals(1L, model.failureCount)
        assertEquals(StudioInputState.Request(false, StudioInputState.Submission.APPLIED), model.lastRequest("up"))
    }
    @Test fun malformedSourcesUnknownKeysAndIncompleteOwnerObservationsRefuse() {
        val model = StudioInputState({ _, _ -> CompletableFuture.completedFuture(null) })
        assertFails { model.press("unbounded-other-source", "menu") }
        assertFails { model.press("mouse:test", "invented") }
        assertFails { model.observe(mapOf("menu" to true)) }
        assertFails { model.observe(PanelCatalog.buttonKeys.associateWith { "false" }) }
    }
    @Test fun originalTenButtonProviderCanOperateMenuAndReleaseItsOwnKeys() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent.add(key to down); CompletableFuture.completedFuture(null) })
        val keys = PanelCatalog.buttonKeys - setOf("delete", "rearDialPress", "movieMode", "drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress")
        model.observe(keys.associateWith { it == "up" })
        assertEquals(true, model.observed("up"))
        assertEquals(null, model.observed("delete"))
        assertEquals(null, model.observed("drive"))
        model.pulse("delete")
        model.toggle("rearDialPress")
        model.pulse("drive")
        model.pulse("menu")
        assertEquals(listOf("menu" to true, "menu" to false), sent)
        sent.clear()
        model.releaseAll()
        assertEquals(keys.map { it to false }, sent)
    }
    @Test fun startupObservedLatchingPowerSyncsDesiredSoFirstToggleTurnsOff() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val pending = mutableListOf<CompletableFuture<Unit>>()
        val model = StudioInputState({ key, down ->
            sent += key to down
            CompletableFuture<Unit>().also(pending::add)
        })
        assertTrue(model.isLatching("power"))
        assertFalse(model.isLatching("menu"))
        model.observe(PanelCatalog.buttonKeys.associateWith { it == "power" })
        assertTrue(model.desired("power"))
        assertEquals(true, model.observed("power"))

        // First toggle must immediately request power=false (OFF), not re-send true.
        model.toggle("power")
        assertEquals(listOf("power" to false), sent)
        assertFalse(model.desired("power"))

        // A stale snapshot arriving while power=false is still PENDING must not clobber desired.
        model.observe(PanelCatalog.buttonKeys.associateWith { it == "power" })
        assertFalse(model.desired("power"))

        pending.single().complete(Unit)
        model.observe(PanelCatalog.buttonKeys.associateWith { false })
        assertFalse(model.desired("power"))
        assertEquals(false, model.observed("power"))

        // Second toggle turns power back ON.
        model.toggle("power")
        assertEquals(listOf("power" to false, "power" to true), sent)
        assertTrue(model.desired("power"))
    }
    @Test fun releaseAllCanPreserveLatchingSwitchesAndRespectsCustomDataDrivenLatchingKeys() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState(
            { key, down -> sent += key to down; CompletableFuture.completedFuture(null) },
            latchingKeys = setOf("power", "play"),
        )
        assertTrue(model.isLatching("power"))
        assertTrue(model.isLatching("play"))
        assertFalse(model.isLatching("menu"))
        model.observe(PanelCatalog.buttonKeys.associateWith { it in setOf("power", "play") })
        model.press("mouse:menu", "menu")
        model.releaseTransient()
        assertFalse(model.desired("menu"))
        assertTrue(model.desired("power"))
        assertTrue(model.desired("play"))

        sent.clear()
        model.releaseAll(includeLatching = false)
        assertEquals(PanelCatalog.buttonKeys - setOf("power", "play"), sent.map { it.first }.toSet())
        assertTrue(sent.all { !it.second })
        assertTrue(model.desired("power"))
        assertTrue(model.desired("play"))
    }

    @Test fun singleStepHoldReleasesAtCalibratedVirtualTickWindowBeforeWallCeiling() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        var nowNanos = 1_000_000_000L
        var vTick = 100_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(
                minHoldVirtualTicks = 4_000_000L,
                maxHoldWallMs = 250L,
                pollIntervalMs = 5L,
            ),
            onPress = model::press,
            onRelease = model::release,
            onPulse = model::pulse,
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )

        // Instant tap at t=0, vTick=100M
        hold.press("mouse:rear-dpad-down", "down")
        nowNanos += 5_000_000L // 5 ms
        vTick += 1_000_000L    // 1M ticks (< 4M)
        hold.release("mouse:rear-dpad-down", "down")
        assertEquals(listOf("down" to true), sent)
        assertEquals(1, hold.pendingReleaseCount())
        assertEquals(5L, scheduled.last().first)

        // Poll at 20ms, 2.5M ticks -> still held
        nowNanos += 15_000_000L
        vTick += 1_500_000L
        scheduled.removeFirst().second.invoke()
        assertEquals(listOf("down" to true), sent)
        assertEquals(1, hold.pendingReleaseCount())

        // Poll at 45ms, 4.1M ticks -> releases IMMEDIATELY (well before 250ms wall ceiling!)
        nowNanos += 25_000_000L
        vTick += 1_600_000L
        scheduled.removeFirst().second.invoke()
        assertEquals(listOf("down" to true, "down" to false), sent)
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test fun singleStepHoldReleasesOnWallCeilingWhenStalledAndImmediatelyWhenPaused() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        var nowNanos = 10_000_000_000L
        val vTick = 500_000_000L // stalled tick
        var advancing = true
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(
                minHoldVirtualTicks = 4_000_000L,
                maxHoldWallMs = 200L,
                pollIntervalMs = 5L,
            ),
            onPress = model::press,
            onRelease = model::release,
            virtualTick = { vTick },
            isSessionAdvancing = { advancing },
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )

        // Stalled ticks -> hard 200ms wall-clock ceiling forces clean release
        hold.press("mouse:menu", "menu")
        hold.release("mouse:menu", "menu")
        assertEquals(listOf("menu" to true), sent)
        nowNanos += 200_000_000L
        scheduled.removeFirst().second.invoke()
        assertEquals(listOf("menu" to true, "menu" to false), sent)
        assertEquals(0, hold.pendingReleaseCount())

        // Paused session -> releases immediately without scheduling a deferred timer
        sent.clear()
        advancing = false
        hold.press("mouse:menu", "menu")
        hold.release("mouse:menu", "menu")
        assertEquals(listOf("menu" to true, "menu" to false), sent)
        assertEquals(0, hold.pendingReleaseCount())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun oppositeDirectionClickCancelsPendingHoldBeforePressingNewDirection() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        val nowNanos = 1_000_000_000L
        val vTick = 100_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = model::press,
            onRelease = model::release,
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )

        // Tap DOWN (enters pending hold), then immediately press UP
        hold.press("mouse:down", "down")
        hold.release("mouse:down", "down")
        assertEquals(listOf("down" to true), sent)
        assertEquals(1, hold.pendingReleaseCount())

        hold.press("mouse:up", "up")
        assertEquals(listOf("down" to true, "down" to false, "up" to true), sent)
        assertEquals(0, hold.pendingReleaseCount())
        assertTrue(scheduled.isEmpty())

        // Same for LEFT <-> RIGHT
        sent.clear()
        hold.press("mouse:left", "left")
        hold.release("mouse:left", "left")
        hold.press("mouse:right", "right")
        assertEquals(listOf("left" to true, "left" to false, "right" to true), sent)
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test fun oppositeDirectionsAreMutuallyExclusiveWhileDiagonalsRemainAllowed() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        model.press("mouse:up", "up")
        model.press("mouse:left", "left")
        assertTrue(model.desired("up"))
        assertTrue(model.desired("left"))
        assertEquals(listOf("up" to true, "left" to true), sent)

        // Pressing opposite axis releases up strictly before pressing down, leaving left untouched.
        model.press("mouse:down", "down")
        assertFalse(model.desired("up"))
        assertTrue(model.desired("down"))
        assertTrue(model.desired("left"))
        assertEquals(listOf("up" to true, "left" to true, "up" to false, "down" to true), sent)

        // Pressing right releases left strictly before pressing right.
        model.press("mouse:right", "right")
        assertFalse(model.desired("left"))
        assertTrue(model.desired("right"))
        assertTrue(model.desired("down"))
        assertEquals(listOf("up" to true, "left" to true, "up" to false, "down" to true, "left" to false, "right" to true), sent)

        // Same mutual exclusion in latch mode.
        model.releaseTransient()
        sent.clear()
        model.setLatch(true)
        model.toggle("up")
        model.toggle("down")
        assertFalse(model.desired("up"))
        assertTrue(model.desired("down"))
        assertEquals(listOf("up" to true, "up" to false, "down" to true), sent)
    }

    @Test fun singleStepHoldWaitsForAppliedOwnerTickAndSurvivesSlowStillLiveViewWallDuration() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val pendingPresses = mutableListOf<CompletableFuture<Unit>>()
        var nowNanos = 1_000_000_000L
        var vTick = 4_750_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        lateinit var hold: SingleStepButtonHold
        val model = StudioInputState(
            send = { key, down ->
                sent += key to down
                if (down) {
                    CompletableFuture<Unit>().also { pendingPresses += it }
                } else {
                    CompletableFuture.completedFuture(null)
                }
            },
            appliedTickProvider = { vTick },
            onPressApplied = { key, appliedTick -> hold.markApplied(key, appliedTick) },
        )
        hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = model::press,
            onRelease = model::release,
            onPulse = model::pulse,
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            awaitAppliedTick = true,
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )
        assertTrue(
            MomentaryHoldPolicy.DEFAULT_MAX_HOLD_WALL_MS >= 2_500L,
            "STILL Live View (0x0100) needs >=1.05s on Dynarmic and longer on slower hosts for 4M virtual ticks",
        )

        // Human quick click on MENU/OK (or DISP/BACK): mouse press + release in 50ms BEFORE OwnerLoop applies press!
        hold.press("mouse:rear-menu-ok", "menu")
        vTick += 6_000_000L // WFI/batch jump BEFORE buttons patch applied on OwnerLoop!
        nowNanos += 50_000_000L
        hold.release("mouse:rear-menu-ok", "menu")
        assertEquals(listOf("menu" to true), sent, "Must NOT release before OwnerLoop applies press")
        assertEquals(1, hold.pendingReleaseCount())

        // OwnerLoop applies menu=true at vTick = 4_756_000_000L
        pendingPresses.removeFirst().complete(Unit)

        // At 1,000ms wall time on STILL Live View, only 3.2M ticks have elapsed since appliedTick (< 4.05M) -> STILL HELD (not cut off at 250ms!)
        nowNanos += 950_000_000L
        vTick += 3_200_000L
        scheduled.removeFirst().second.invoke()
        assertEquals(listOf("menu" to true), sent, "Must survive past 250ms/1000ms wall time until 4.05M vticks elapse")
        assertEquals(1, hold.pendingReleaseCount())

        // At 1,100ms wall time, 4.1M ticks have elapsed since appliedTick (>= 4.05M) -> clean release!
        nowNanos += 100_000_000L
        vTick += 900_000L
        scheduled.removeFirst().second.invoke()
        assertEquals(listOf("menu" to true, "menu" to false), sent)
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test fun rapidRePressAndKeyboardAutoRepeatPreserveAppliedTickAndNeverStuckForWallCeiling() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val pendingPresses = mutableListOf<CompletableFuture<Unit>>()
        var nowNanos = 1_000_000_000L
        var vTick = 500_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        lateinit var hold: SingleStepButtonHold
        val model = StudioInputState(
            send = { key, down ->
                sent += key to down
                if (down) CompletableFuture<Unit>().also { pendingPresses += it } else CompletableFuture.completedFuture(null)
            },
            appliedTickProvider = { vTick },
            onPressApplied = { key, tick -> hold.markApplied(key, tick) },
        )
        hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = model::press,
            onRelease = model::release,
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            awaitAppliedTick = true,
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )

        // Tap 1: press -> owner applies at vTick=500M -> release after 20ms (only 0.4M ticks elapsed < 2.2M D-pad floor)
        hold.press("mouse:down", "down")
        pendingPresses.removeFirst().complete(Unit)
        nowNanos += 20_000_000L
        vTick += 400_000L
        hold.release("mouse:down", "down")
        // Duplicate release (e.g. scene capture filter + button filter) must be idempotent (no duplicate timer churn)
        hold.release("mouse:down", "down")
        assertEquals(1, scheduled.size)
        assertEquals(1, hold.pendingReleaseCount())

        // Rapid Tap 2 while Tap 1 is still in pendingReleaseTimers (previously reset appliedAtTick=null -> 3000ms stuck!)
        nowNanos += 20_000_000L
        vTick += 400_000L
        hold.press("mouse:down", "down")
        assertEquals(listOf("down" to true), sent)
        assertEquals(0, hold.pendingReleaseCount())

        // Release Tap 2 after 2.2M total ticks since original appliedTick -> releases IMMEDIATELY (NOT stuck for 3000ms!)
        nowNanos += 60_000_000L
        vTick += 1_500_000L // total = 2.3M >= 2.2M direction floor
        hold.release("mouse:down", "down")
        assertEquals(listOf("down" to true, "down" to false), sent)
        assertEquals(0, hold.pendingReleaseCount())

        // OS keyboard auto-repeat: repeated KEY_PRESSED while holding key must NOT reset appliedAtTick
        sent.clear()
        hold.press("keyboard:down", "down")
        pendingPresses.removeFirst().complete(Unit)
        nowNanos += 300_000_000L
        vTick += 5_000_000L
        hold.press("keyboard:down", "down") // OS key repeat #1
        hold.press("keyboard:down", "down") // OS key repeat #2
        assertEquals(listOf("down" to true), sent)
        hold.release("keyboard:down", "down")
        assertEquals(listOf("down" to true, "down" to false), sent, "Must release immediately after held keyboard repeat")
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test fun orthogonalDirectionPressCancelsStalePendingReleaseWithoutBreakingPhysicalDiagonals() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        var nowNanos = 1_000_000_000L
        var vTick = 100_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = model::press,
            onRelease = model::release,
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )

        // 1. Tap DOWN (user released, sitting in pendingReleaseTimers), then tap RIGHT -> DOWN releases immediately (zero diagonal leak)
        hold.press("mouse:down", "down")
        hold.release("mouse:down", "down")
        assertEquals(1, hold.pendingReleaseCount())
        hold.press("mouse:right", "right")
        assertEquals(listOf("down" to true, "down" to false, "right" to true), sent)
        assertEquals(0, hold.pendingReleaseCount())

        // 2. Deliberate physical simultaneous hold (RIGHT NOT released yet) + press UP -> both remain held!
        hold.press("mouse:up", "up")
        assertEquals(listOf("down" to true, "down" to false, "right" to true, "up" to true), sent)
        assertTrue(model.desired("right"))
        assertTrue(model.desired("up"))

        // 3. Per-key DAT hold floor: direction releases at 2.2M ticks while 25ms x deb=2 buttons (menu/back)
        //    stay held through 3.25M ticks and release at the phase-safe 4.05M (75ms, 3 full scans) calibrated floor
        assertEquals(4_050_000L, MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS)
        sent.clear()
        vTick += 5_000_000L
        hold.release("mouse:right", "right")
        hold.release("mouse:up", "up")
        sent.clear()
        hold.press("mouse:menu", "menu")
        hold.press("mouse:back", "back")
        hold.press("mouse:left", "left")
        hold.release("mouse:menu", "menu")
        hold.release("mouse:back", "back")
        hold.release("mouse:left", "left")
        assertEquals(3, hold.pendingReleaseCount())
        nowNanos += 50_000_000L
        vTick += 2_250_000L
        // Flush scheduled polls: left (>= 2.2M) releases while menu & back (< 4.05M) stay held
        scheduled.toList().forEach { it.second.invoke() }
        assertTrue("left" to false in sent, "Direction key must release at 2.2M calibrated floor")
        assertFalse("menu" to false in sent, "25ms x deb=2 button must stay held until 4.05M calibrated floor")
        assertFalse("back" to false in sent, "DISP/BACK must stay held until 4.05M calibrated floor")
        vTick += 1_000_000L // 3.25M total (< 4.05M) -> still held for full scan-phase margin
        scheduled.toList().forEach { it.second.invoke() }
        assertFalse("menu" to false in sent, "MENU must remain held below 4.05M floor")
        assertFalse("back" to false in sent, "DISP/BACK must remain held below 4.05M floor")
        vTick += 850_000L // 4.10M total (>= 4.05M) -> both release cleanly
        scheduled.toList().forEach { it.second.invoke() }
        assertTrue("menu" to false in sent, "MENU must release at 4.05M calibrated floor")
        assertTrue("back" to false in sent, "DISP/BACK must release at 4.05M calibrated floor")
        assertEquals(0, hold.pendingReleaseCount())
    }

    @Test fun coalescingButtonDispatcherMergesBurstsAndSkipsDiskWriteForMomentaryKeys() {
        val ownerTasks = ArrayDeque<() -> Unit>()
        val appliedBatches = mutableListOf<Pair<Map<String, Boolean>, Boolean>>()
        val dispatcher = com.github.nyabanban.fuji.emulator.desktop.panels.CoalescingButtonDispatcher(
            submitOwner = { action ->
                val f = CompletableFuture<Unit>()
                ownerTasks.addLast {
                    try {
                        action()
                        f.complete(Unit)
                    } catch (e: Throwable) {
                        f.completeExceptionally(e)
                    }
                }
                f
            },
            applyBatchOnOwner = { batch, persistToDisk ->
                appliedBatches += batch to persistToDisk
                mapOf("id" to "buttons", "revision" to appliedBatches.size.toLong())
            },
        )

        // Rapid burst while owner is busy: only ONE owner task is submitted!
        val f1 = dispatcher.submit("down", true)
        val f2 = dispatcher.submit("down", false)
        val f3 = dispatcher.submit("up", true)
        assertEquals(1, ownerTasks.size, "Must coalesce rapid burst into a single OwnerLoop submission")
        assertFalse(f1.isDone)

        ownerTasks.removeFirst().invoke()
        assertTrue(f1.isDone && f2.isDone && f3.isDone)
        assertEquals(1, appliedBatches.size)
        assertEquals(mapOf("down" to false, "up" to true), appliedBatches[0].first)
        assertFalse(appliedBatches[0].second, "Momentary keys must NOT trigger synchronous YAML disk persistence")
        assertEquals(1L, dispatcher.coalescedOverwrites)

        // Latching power switch MUST persist to disk
        val fPower = dispatcher.submit("power", false)
        assertEquals(1, ownerTasks.size)
        ownerTasks.removeFirst().invoke()
        assertTrue(fPower.isDone)
        assertEquals(2, appliedBatches.size)
        assertEquals(mapOf("power" to false), appliedBatches[1].first)
        assertTrue(appliedBatches[1].second, "Latching power switch must persist to buttons.yaml")
    }

    @Test fun v4ThirteenKeyProviderAndFocusModeLatchingAndAdcCalibratedHoldWorkHeadlessly() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })
        assertTrue(model.isLatching("focusMode"))
        assertFalse(model.isLatching("drive"))
        assertFalse(model.isLatching("afOn"))

        // Backward-compatible observation of a v4 13-key and v5 16-key buttons component
        val v4Keys = PanelCatalog.buttonKeys - setOf("drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress")
        model.observe(v4Keys.associateWith { it == "power" })
        assertEquals(true, model.observed("power"))
        assertNull(model.observed("drive"))
        assertNull(model.observed("afOn"))
        assertNull(model.observed("focusMode"))
        assertNull(model.observed("frontDialPress"))

        val v5Keys = PanelCatalog.buttonKeys - setOf("frontDialPress", "fn4", "focusStickPress")
        model.observe(v5Keys.associateWith { it == "power" })
        assertEquals(false, model.observed("drive"))
        assertNull(model.observed("frontDialPress"))
        assertNull(model.observed("fn4"))
        assertNull(model.observed("focusStickPress"))

        // Full 19-key observation preserves latching focusMode on transient release & releaseAll(includeLatching=false)
        model.observe(PanelCatalog.buttonKeys.associateWith { it == "power" })
        model.toggle("focusMode")
        model.press("mouse:rear-drive", "drive")
        model.press("mouse:rear-af-on", "afOn")
        model.press("mouse:front-command-dial", "frontDialPress")
        model.press("mouse:front-fn4", "fn4")
        model.press("mouse:rear-stick-press", "focusStickPress")
        model.releaseTransient()
        assertFalse(model.desired("drive"))
        assertFalse(model.desired("afOn"))
        assertFalse(model.desired("frontDialPress"))
        assertFalse(model.desired("fn4"))
        assertFalse(model.desired("focusStickPress"))
        assertTrue(model.desired("focusMode"))

        // Console aliases resolve new GPIO buttons cleanly
        val aliases = com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole.BUTTON_ALIASES
        assertEquals("drive", aliases["drive"])
        assertEquals("afOn", aliases["afon"])
        assertEquals("afOn", aliases["af-on"])
        assertEquals("focusMode", aliases["focus"])
        assertEquals("focusMode", aliases["focusmode"])
        assertEquals("frontDialPress", aliases["frontdialpress"])
        assertEquals("frontDialPress", aliases["front-dial-press"])
        assertEquals("fn4", aliases["fn4"])
        assertEquals("focusStickPress", aliases["focusstickpress"])
        assertEquals("focusStickPress", aliases["stick-press"])

        // Momentary ADC control IDs honor calibrated 4.05M single-step virtual-tick floor
        val adcSent = mutableListOf<Pair<String, Boolean>>()
        var nowNanos = 1_000_000_000L
        var vTick = 50_000_000L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val hold = SingleStepButtonHold(
            policy = MomentaryHoldPolicy.firmwareCalibrated(),
            onPress = { _, id -> adcSent += id to true },
            onRelease = { _, id -> adcSent += id to false },
            virtualTick = { vTick },
            isSessionAdvancing = { true },
            nanoTime = { nowNanos },
            scheduleDelay = { delayMs, action ->
                val entry = delayMs to action
                scheduled += entry
                AutoCloseable { scheduled.remove(entry) }
            },
        )
        hold.press("mouse:rear-q", "rear-q")
        hold.release("mouse:rear-q", "rear-q")
        assertEquals(listOf("rear-q" to true), adcSent)
        nowNanos += 40_000_000L
        vTick += 3_000_000L
        scheduled.toList().forEach { it.second.invoke() }
        assertEquals(listOf("rear-q" to true), adcSent)
        nowNanos += 40_000_000L
        vTick += 1_100_000L
        scheduled.toList().forEach { it.second.invoke() }
        assertEquals(listOf("rear-q" to true, "rear-q" to false), adcSent)
    }

    @Test fun perButtonLockSupportsSimultaneousCombosSurvivesTransientReleaseAndEnforcesOppositeExclusion() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val model = StudioInputState({ key, down -> sent += key to down; CompletableFuture.completedFuture(null) })

        // Latching switches cannot be locked via momentary lock source
        assertFalse(model.lock("power"))
        assertFalse(model.lock("movieMode"))
        assertFalse(model.lock("focusMode"))
        assertFalse(model.isLocked("power"))
        assertTrue(sent.isEmpty())

        // Simultaneous multi-key combo: DISP/BACK + MENU/OK + SHUTTER HALF
        model.toggle("power")
        assertTrue(model.lock("back"))
        assertTrue(model.lock("menu"))
        assertTrue(model.lock("shutterHalf"))
        assertTrue(model.isLocked("back"))
        assertTrue(model.isLocked("menu"))
        assertTrue(model.isLocked("shutterHalf"))
        assertEquals(setOf("back", "menu", "shutterHalf"), model.lockedKeys())
        assertEquals(model.lockedKeys().toList(), model.diagnostics()["lockedKeys"])
        assertTrue(model.desired("back") && model.desired("menu") && model.desired("shutterHalf"))

        // Transient releases, mouse press+release, and global latch OFF must preserve locked keys
        sent.clear()
        model.press("mouse:back", "back")
        model.release("mouse:back", "back")
        model.releaseTransient()
        model.releaseTransient("mouse:")
        model.setLatch(true)
        model.setLatch(false)
        assertTrue(sent.isEmpty(), "Transient releases and latch-off must NOT release locked keys: $sent")
        assertEquals(setOf("back", "menu", "shutterHalf"), model.lockedKeys())
        assertTrue(model.desired("back") && model.desired("menu") && model.desired("shutterHalf"))

        // Opposite D-pad mutual exclusion while allowing orthogonal diagonals
        assertTrue(model.lock("up"))
        assertTrue(model.lock("left"))
        assertTrue(model.isLocked("up") && model.isLocked("left"))
        sent.clear()
        assertTrue(model.lock("down"))
        assertFalse(model.isLocked("up"))
        assertFalse(model.desired("up"))
        assertTrue(model.isLocked("down") && model.isLocked("left"))
        assertEquals(listOf("up" to false, "down" to true), sent)

        sent.clear()
        assertTrue(model.lock("right"))
        assertFalse(model.isLocked("left"))
        assertFalse(model.desired("left"))
        assertTrue(model.isLocked("down") && model.isLocked("right"))
        assertEquals(listOf("left" to false, "right" to true), sent)

        // toggleLock unlocks an already-locked key and releases it
        sent.clear()
        assertFalse(model.toggleLock("back"))
        assertFalse(model.isLocked("back"))
        assertFalse(model.desired("back"))
        assertEquals(listOf("back" to false), sent)

        // unlockAll releases all remaining locked momentary keys while preserving latching power switch
        sent.clear()
        val unlockedCount = model.unlockAll()
        assertEquals(4, unlockedCount) // menu, shutterHalf, down, right
        assertTrue(model.lockedKeys().isEmpty())
        assertEquals(setOf("menu", "shutterHalf", "down", "right"), sent.map { it.first }.toSet())
        assertTrue(sent.all { !it.second })
        assertTrue(model.desired("power"), "unlockAll must not turn off latching power switch")

        // Rejected async send during lock rolls back both desired and locked state cleanly
        val pending = CompletableFuture<Unit>()
        val failingModel = StudioInputState({ _, _ -> pending })
        assertTrue(failingModel.lock("back"))
        assertTrue(failingModel.isLocked("back"))
        pending.completeExceptionally(IllegalStateException("rejected"))
        assertFalse(failingModel.desired("back"))
        assertFalse(failingModel.isLocked("back"))
    }
}
