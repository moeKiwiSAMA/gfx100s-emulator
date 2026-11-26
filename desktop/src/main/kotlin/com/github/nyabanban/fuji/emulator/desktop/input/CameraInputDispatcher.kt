package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import java.util.concurrent.CompletableFuture
import kotlin.math.pow

/** Single source of truth for which studio control each [CameraInputAction] drives. */
object CameraInputMapping {
    /** HOLD actions on momentary GPIO `buttons` keys. */
    val buttonKeys: Map<CameraInputAction, String> = linkedMapOf(
        CameraInputAction.SHUTTER_HALF to "shutterHalf",
        CameraInputAction.SHUTTER_FULL to "shutterFull",
        CameraInputAction.AF_ON to "afOn",
        CameraInputAction.MENU_OK to "menu",
        CameraInputAction.DISP_BACK to "back",
        CameraInputAction.PLAY to "play",
        CameraInputAction.DELETE to "delete",
        CameraInputAction.UP to "up",
        CameraInputAction.DOWN to "down",
        CameraInputAction.LEFT to "left",
        CameraInputAction.RIGHT to "right",
        CameraInputAction.JOY_PRESS to "focusStickPress",
        CameraInputAction.FRONT_DIAL_PRESS to "frontDialPress",
        CameraInputAction.REAR_DIAL_PRESS to "rearDialPress",
        CameraInputAction.DRIVE to "drive",
        CameraInputAction.FN4 to "fn4",
    )

    /** HOLD actions on momentary ADC controls (catalog control ids). */
    val settingControls: Map<CameraInputAction, String> = linkedMapOf(
        CameraInputAction.AE_L to "rear-ael",
        CameraInputAction.Q_MENU to "rear-q",
        CameraInputAction.FN1 to "top-fn1",
        CameraInputAction.FN2 to "top-fn2",
    )

    /** STEP actions that flip a latching body switch. */
    val latchToggles: Map<CameraInputAction, String> = linkedMapOf(
        CameraInputAction.MOVIE_MODE to "movieMode",
        CameraInputAction.POWER_TOGGLE to "power",
    )
}

/** Routes resolved actions into [CameraStudio]'s public binding facade; lens focus/zoom go through [lens]. */
class CameraInputDispatcher(
    private val studio: CameraStudio,
    private val lens: LensBindingStepper? = null,
) : CameraInputSink {
    override fun press(action: CameraInputAction, source: String) {
        CameraInputMapping.buttonKeys[action]?.let { studio.pressBindingButton(source, it); return }
        CameraInputMapping.settingControls[action]?.let { studio.pressBindingSetting(source, it); return }
        if (action == CameraInputAction.VIEW_MODE) studio.pressBindingViewMode(source)
    }

    override fun release(action: CameraInputAction, source: String) {
        CameraInputMapping.buttonKeys[action]?.let { studio.releaseBindingButton(source, it); return }
        CameraInputMapping.settingControls[action]?.let { studio.releaseBindingSetting(source, it); return }
        if (action == CameraInputAction.VIEW_MODE) studio.releaseBindingViewMode(source)
    }

    override fun step(action: CameraInputAction) {
        CameraInputMapping.latchToggles[action]?.let { studio.toggleBindingLatch(it); return }
        when (action) {
            CameraInputAction.FOCUS_MODE_CYCLE -> studio.stepBindingFocusMode(1)
            CameraInputAction.FOCUS_NEAR -> lens?.focus(-1)
            CameraInputAction.FOCUS_FAR -> lens?.focus(1)
            CameraInputAction.FRONT_DIAL_CW -> studio.stepBindingFrontDial(1)
            CameraInputAction.FRONT_DIAL_CCW -> studio.stepBindingFrontDial(-1)
            CameraInputAction.REAR_DIAL_CW -> studio.stepBindingRearDial(1)
            CameraInputAction.REAR_DIAL_CCW -> studio.stepBindingRearDial(-1)
            CameraInputAction.MODE_DIAL_CW -> studio.stepBindingModeDial(1)
            CameraInputAction.MODE_DIAL_CCW -> studio.stepBindingModeDial(-1)
            CameraInputAction.ZOOM_IN -> lens?.zoom(1)
            CameraInputAction.ZOOM_OUT -> lens?.zoom(-1)
            CameraInputAction.EYE_SENSOR_TOGGLE -> studio.toggleBindingEyeSensor()
            CameraInputAction.EVF_WINDOW_TOGGLE -> studio.toggleBindingEvfWindow()
            else -> {}
        }
    }
}

/**
 * Steps the simulated lens through the same `lens` component settings the Lens & Optics panel slider patches
 * (`focalLengthMm`, `focusDistanceM`). Rapid repeats are coalesced: at most one read+patch is in flight and further
 * steps accumulate. Fixed-focal lenses ignore zoom.
 */
class LensBindingStepper(
    private val readLens: () -> CompletableFuture<LensProfileState?>,
    private val patchLens: (Map<String, Any>) -> CompletableFuture<*>,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var pendingZoom = 0
    private var pendingFocus = 0
    private var inFlight = false

    fun zoom(direction: Int) = enqueue(zoom = direction, focus = 0)
    fun focus(direction: Int) = enqueue(zoom = 0, focus = direction)

    private fun enqueue(zoom: Int, focus: Int) {
        synchronized(lock) {
            pendingZoom += zoom
            pendingFocus += focus
            if (inFlight) return
            inFlight = true
        }
        flush()
    }

    private fun flush() {
        val (zoomSteps, focusSteps) = synchronized(lock) {
            val pair = pendingZoom to pendingFocus
            pendingZoom = 0
            pendingFocus = 0
            if (pair.first == 0 && pair.second == 0) inFlight = false
            pair
        }
        if (zoomSteps == 0 && focusSteps == 0) return
        val patch: CompletableFuture<*> = runCatching { readLens() }.getOrElse { CompletableFuture.failedFuture<LensProfileState?>(it) }
            .thenCompose<Any?> { state ->
                val changes = if (state == null) emptyMap() else changesFor(state, zoomSteps, focusSteps)
                if (changes.isEmpty()) {
                    CompletableFuture.completedFuture<Any?>(null)
                } else {
                    patchLens(changes).thenApply<Any?> { it }
                }
            }
        patch.whenComplete { _, error ->
            if (error != null) onError(error)
            flush()
        }
    }

    companion object {
        /** 16 steps per focal-length doubling. */
        val ZOOM_RATIO: Double = 2.0.pow(1.0 / 16.0)
        const val FOCUS_RATIO: Double = 1.12
        const val MAX_FOCUS_DISTANCE_M: Double = 1_000.0

        internal fun changesFor(state: LensProfileState, zoomSteps: Int, focusSteps: Int): Map<String, Any> {
            val changes = LinkedHashMap<String, Any>()
            if (zoomSteps != 0 && state.maxFocalLengthMm - state.minFocalLengthMm > 0.05) {
                val next = (state.focalLengthMm * ZOOM_RATIO.pow(zoomSteps)).coerceIn(state.minFocalLengthMm, state.maxFocalLengthMm)
                if (kotlin.math.abs(next - state.focalLengthMm) > 1e-9) changes["focalLengthMm"] = next
            }
            if (focusSteps != 0) {
                val minFocus = state.minFocusDistanceM.coerceAtLeast(0.05)
                val current = state.focusDistanceM.coerceIn(minFocus, MAX_FOCUS_DISTANCE_M)
                val next = (current * FOCUS_RATIO.pow(focusSteps)).coerceIn(minFocus, MAX_FOCUS_DISTANCE_M)
                if (kotlin.math.abs(next - state.focusDistanceM) > 1e-9) changes["focusDistanceM"] = next
            }
            return changes
        }
    }
}
