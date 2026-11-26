package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.FocusSliderMotorTracker
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import org.junit.jupiter.api.Test
import kotlin.test.*

class LensFocusSliderMotorTest {
    private fun motorizedState(
        focusDistanceM: Double = 10.0,
        focusTargetDistanceM: Double = focusDistanceM,
        focusMotorActive: Boolean = false,
        focusMotorInstant: Boolean = false,
        focusMotorType: String = "LM",
        focusSweepSeconds: Double = 0.5,
        minFocusDistanceM: Double = 0.65,
    ): LensProfileState = LensProfileState.fromMaps(
        lensConfig = mapOf("template" to "gf45-100"),
        lensState = mapOf(
            "template" to "gf45-100",
            "focusDistanceM" to focusDistanceM,
            "focusTargetDistanceM" to focusTargetDistanceM,
            "minFocusDistanceM" to minFocusDistanceM,
            "focusMotorType" to focusMotorType,
            "focusSweepSeconds" to focusSweepSeconds,
            "focusMotorInstant" to focusMotorInstant,
            "focusMotorActive" to focusMotorActive,
        ),
        mountConfig = mapOf("attached" to true),
        mountState = mapOf("attached" to true),
    )

    @Test
    fun focusTargetPatchBuilderAndFromMapsParseTargetDistanceCleanly() {
        val patch = LensProfileState.buildFocusTargetPatch(2.5, minFocusM = 0.65)
        assertEquals(mapOf("focusTargetDistanceM" to 2.5), patch)
        assertFalse("focusDistanceM" in patch, "Panel commits must send focusTargetDistanceM, not instant focusDistanceM")

        val clampedPatch = LensProfileState.buildFocusTargetPatch(0.1, minFocusM = 0.65)
        assertEquals(0.65, (clampedPatch["focusTargetDistanceM"] as Number).toDouble(), 1e-6)

        val state = motorizedState(focusDistanceM = 4.0, focusTargetDistanceM = 1.2, focusMotorActive = true)
        assertEquals(4.0, state.focusDistanceM, 1e-6)
        assertEquals(1.2, state.focusTargetDistanceM, 1e-6)
        assertTrue(state.focusMotorActive)
        assertTrue(LensProfileState.shouldAnimateFocusMotor(state))
        assertFalse(LensProfileState.shouldAnimateFocusMotor(state.copy(focusMotorInstant = true)))
        assertFalse(LensProfileState.shouldAnimateFocusMotor(state.copy(focusMotorType = "MANUAL")))
    }

    @Test
    fun trackerStartsFromCurrentPhysicalPositionAndSlewsSmoothlyToTarget() {
        val tracker = FocusSliderMotorTracker()
        val initial = motorizedState(focusDistanceM = 10.0, focusSweepSeconds = 0.5, minFocusDistanceM = 0.65)
        tracker.onObservedState(initial, userDragging = false, nowNanos = 0L)
        assertFalse(tracker.active)
        assertEquals(10.0, tracker.displayedDistanceM, 1e-6)

        // User drags slider to 0.8m and releases -> slider snaps back to physical 10.0m and begins slewing!
        val startDisplay = tracker.onUserCommitTarget(0.8, initial, nowNanos = 1_000_000L)
        assertTrue(tracker.active)
        assertEquals(10.0, startDisplay, 1e-6, "Slider must start from physical lens distance, not snap statically to target")
        assertEquals(0.8, tracker.targetDistanceM, 1e-6)
        assertTrue(tracker.formattedLabel(initial).contains("\u2192"), "Slewing label must show current -> target: ${tracker.formattedLabel(initial)}")

        // Stale snapshot captured right before patch landed must NOT cancel the animation
        tracker.onObservedState(initial, userDragging = false, nowNanos = 50_000_000L)
        assertTrue(tracker.active, "Stale pre-ack snapshot must not kill in-flight slew")

        // Advance animation in 50ms steps -> monotonic progression toward 0.8m
        val still1 = tracker.onAnimationTick(0.05, initial, nowNanos = 100_000_000L)
        val d1 = tracker.displayedDistanceM
        val still2 = tracker.onAnimationTick(0.05, initial, nowNanos = 150_000_000L)
        val d2 = tracker.displayedDistanceM
        assertTrue(still1 && still2)
        assertTrue(d1 < 10.0 && d2 < d1 && d2 > 0.8, "Expected smooth progression 10.0 -> $d1 -> $d2 -> 0.8")

        // Backend snapshot acknowledges active motor -> tracker stays active & reconciles smoothly
        val activeBackend = motorizedState(focusDistanceM = 2.0, focusTargetDistanceM = 0.8, focusMotorActive = true)
        tracker.onObservedState(activeBackend, userDragging = false, nowNanos = 200_000_000L)
        assertTrue(tracker.active)

        // Backend finishes arrival -> tracker settles at 0.8m and label drops arrow
        val settledBackend = motorizedState(focusDistanceM = 0.8, focusTargetDistanceM = 0.8, focusMotorActive = false)
        tracker.onObservedState(settledBackend, userDragging = false, nowNanos = 600_000_000L)
        assertFalse(tracker.active)
        assertEquals(0.8, tracker.displayedDistanceM, 1e-6)
        assertEquals("0.80 m", tracker.formattedLabel(settledBackend))
    }

    @Test
    fun trackerTracksCameraAfTravelAndSnapsInstantlyForManualOrInstantLenses() {
        val tracker = FocusSliderMotorTracker()
        val idle = motorizedState(focusDistanceM = 5.0, focusMotorActive = false)
        tracker.onObservedState(idle, userDragging = false, nowNanos = 0L)

        // Camera AF triggers motor travel externally -> tracker activates and follows!
        val afMoving = motorizedState(focusDistanceM = 4.2, focusTargetDistanceM = 1.5, focusMotorActive = true)
        tracker.onObservedState(afMoving, userDragging = false, nowNanos = 100_000_000L)
        assertTrue(tracker.active)
        assertEquals(4.2, tracker.displayedDistanceM, 1e-6)
        assertEquals(1.5, tracker.targetDistanceM, 1e-6)
        assertTrue(tracker.formattedLabel(afMoving).contains("4.20 m \u2192 1.50 m"))

        // Manual / instant lenses snap immediately on user commit
        val manualState = motorizedState(focusDistanceM = 5.0, focusMotorType = "MANUAL", focusSweepSeconds = 0.0)
        val manualDist = tracker.onUserCommitTarget(1.2, manualState, nowNanos = 200_000_000L)
        assertFalse(tracker.active)
        assertEquals(1.2, manualDist, 1e-6)

        val instantState = motorizedState(focusDistanceM = 5.0, focusMotorInstant = true)
        val instantDist = tracker.onUserCommitTarget(2.0, instantState, nowNanos = 300_000_000L)
        assertFalse(tracker.active)
        assertEquals(2.0, instantDist, 1e-6)
    }
}
