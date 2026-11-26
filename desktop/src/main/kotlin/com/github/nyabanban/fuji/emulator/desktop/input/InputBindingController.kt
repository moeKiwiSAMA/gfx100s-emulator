package com.github.nyabanban.fuji.emulator.desktop.input

import kotlin.math.abs

/** Receives resolved camera actions. Implementations are called on the UI thread only. */
interface CameraInputSink {
    fun press(action: CameraInputAction, source: String)
    fun release(action: CameraInputAction, source: String)
    fun step(action: CameraInputAction)
}

/**
 * One controller snapshot with raw-normalized axes (no deadzone): sticks in [-1, 1] with +y = up, triggers in [0, 1].
 * [buttons] uses the `FUJI_GP_*` bit layout of `tools/native/gamepad-bridge/fuji_gamepad.h`.
 */
data class GamepadFrame(
    val connected: Boolean,
    val slot: Int = -1,
    val name: String = "",
    val buttons: Int = 0,
    val lx: Double = 0.0,
    val ly: Double = 0.0,
    val rx: Double = 0.0,
    val ry: Double = 0.0,
    val lt: Double = 0.0,
    val rt: Double = 0.0,
) {
    companion object {
        val DISCONNECTED = GamepadFrame(connected = false)

        const val DPAD_UP = 0x0001
        const val DPAD_DOWN = 0x0002
        const val DPAD_LEFT = 0x0004
        const val DPAD_RIGHT = 0x0008
        const val START = 0x0010
        const val BACK = 0x0020
        const val L3 = 0x0040
        const val R3 = 0x0080
        const val LB = 0x0100
        const val RB = 0x0200
        const val GUIDE = 0x0400
        const val A = 0x1000
        const val B = 0x2000
        const val X = 0x4000
        const val Y = 0x8000

        val BUTTON_BITS: Map<GamepadInput, Int> = linkedMapOf(
            GamepadInput.A to A, GamepadInput.B to B, GamepadInput.X to X, GamepadInput.Y to Y,
            GamepadInput.LB to LB, GamepadInput.RB to RB, GamepadInput.BACK to BACK, GamepadInput.START to START,
            GamepadInput.L3 to L3, GamepadInput.R3 to R3,
            GamepadInput.DPAD_UP to DPAD_UP, GamepadInput.DPAD_DOWN to DPAD_DOWN,
            GamepadInput.DPAD_LEFT to DPAD_LEFT, GamepadInput.DPAD_RIGHT to DPAD_RIGHT,
        )
    }
}

/** Observable controller status for the UI banner; [devices] lists every connected controller. */
data class GamepadStatus(
    val connected: Boolean,
    val name: String = "",
    val slot: Int = -1,
    val backend: String = "",
    val devices: List<GamepadDevice> = emptyList(),
)

/** Something the user pressed while the bindings dialog was capturing. */
sealed interface CapturedInput {
    data class Key(val spec: KeyBindingSpec) : CapturedInput
    data class Pad(val spec: GamepadBindingSpec) : CapturedInput
}

/**
 * Resolves keyboard events and gamepad frames into [CameraInputAction]s for a [CameraInputSink].
 *
 * - Keyboard: exact modifier matching, OS key auto-repeat ignored, suppressed while a text input has focus,
 *   everything released by [releaseAllKeyboard] (window focus loss).
 * - Gamepad: radial-free per-axis stick deadzone with hysteresis, two-stage triggers with hysteresis
 *   (press order HALF -> FULL, release order FULL -> HALF), STEP auto-repeat, and every held action released on
 *   disconnect, when gamepad input is disabled, and in [releaseAll].
 * - Capture: while [isCapturing], inputs are reported to the capture listener instead of being dispatched.
 *
 * Not thread-safe: call everything on the UI thread. [needsTick] and [status] may be read from any thread.
 */
class InputBindingController(
    initial: InputBindingConfig,
    private val sink: CameraInputSink,
    private val clock: () -> Long = System::nanoTime,
) {
    /** Written on the UI thread; volatile so the poller thread can read [InputBindingConfig.gamepadSelection]. */
    @Volatile var config: InputBindingConfig = initial
        private set

    private val heldKeys = LinkedHashMap<String, CameraInputAction>()
    private val activePad = LinkedHashSet<GamepadInput>()
    private val heldPad = LinkedHashMap<GamepadInput, CameraInputAction>()
    private data class Repeat(val action: CameraInputAction, var nextDueNanos: Long)
    private val repeats = LinkedHashMap<String, Repeat>()

    private var captureListener: ((CapturedInput?) -> Unit)? = null
    private val captureIgnored = HashSet<GamepadInput>()
    private val captureTriggerPeak = HashMap<GamepadInput, GamepadInput>()

    @Volatile var needsTick: Boolean = false
        private set

    @Volatile var status: GamepadStatus = GamepadStatus(connected = false)
        private set
    @Volatile var lastLt: Double = 0.0
        private set
    @Volatile var lastRt: Double = 0.0
        private set
    private val statusListeners = ArrayList<(GamepadStatus) -> Unit>()
    private val triggerListeners = ArrayList<(Double, Double) -> Unit>()
    var backendName: String = ""
        set(value) {
            field = value
            publishStatus(status.copy(backend = value))
        }

    val isCapturing: Boolean get() = captureListener != null

    fun addStatusListener(listener: (GamepadStatus) -> Unit): AutoCloseable {
        statusListeners += listener
        return AutoCloseable { statusListeners -= listener }
    }

    fun addTriggerListener(listener: (lt: Double, rt: Double) -> Unit): AutoCloseable {
        triggerListeners += listener
        return AutoCloseable { triggerListeners -= listener }
    }

    /** Applies [next]; held inputs whose action changed or was unbound are released first (no stuck buttons). */
    fun updateConfig(next: InputBindingConfig) {
        if (next.selectedGamepad != config.selectedGamepad) {
            // A different controller takes over: release everything the previous one held.
            releaseAllGamepad()
            activePad.clear()
        }
        heldKeys.entries.toList().forEach { (code, action) ->
            if (next.keyboardBindings[action].orEmpty().none { it.keyCode == code }) releaseKeyEntry(code, action)
        }
        heldPad.entries.toList().forEach { (input, action) ->
            if (!next.gamepadEnabled || next.actionForGamepad(input) != action) releasePadEntry(input, action)
        }
        config = next
        if (!next.gamepadEnabled) {
            publishTriggerLevels(0.0, 0.0)
            publishStatus(status)
        }
    }

    // ---------------------------------------------------------------- keyboard

    /** Returns `true` when the event was consumed (bound shortcut, held shortcut release, or capture). */
    fun handleKey(
        keyCode: String,
        pressed: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        textInputFocused: Boolean = false,
    ): Boolean {
        val capture = captureListener
        if (capture != null) {
            if (!pressed) return true
            if (keyCode == "ESCAPE" && !ctrl && !alt && !shift && !meta) {
                finishCapture(null)
                return true
            }
            if (keyCode in KeyBindingSpec.MODIFIER_KEY_CODES || keyCode == "UNDEFINED") return true
            val spec = runCatching { KeyBindingSpec(keyCode, ctrl, alt, shift, meta) }.getOrNull() ?: return true
            finishCapture(CapturedInput.Key(spec))
            return true
        }
        if (!pressed) {
            val action = heldKeys[keyCode] ?: return false
            releaseKeyEntry(keyCode, action)
            return true
        }
        if (keyCode in heldKeys) return true // OS auto-repeat: the shortcut is already held
        if (textInputFocused) return false
        val action = config.actionForKey(keyCode, ctrl, alt, shift, meta) ?: return false
        heldKeys[keyCode] = action
        activate(action, keySource(keyCode))
        return true
    }

    /** Releases every keyboard-held action (window focus loss, dialog open, shutdown). */
    fun releaseAllKeyboard() {
        heldKeys.entries.toList().forEach { (code, action) -> releaseKeyEntry(code, action) }
    }

    private fun releaseKeyEntry(code: String, action: CameraInputAction) {
        heldKeys.remove(code)
        deactivate(action, keySource(code))
    }

    // ---------------------------------------------------------------- gamepad

    fun handleGamepadFrame(frame: GamepadFrame) {
        val nextStatus = if (frame.connected) {
            GamepadStatus(true, frame.name, frame.slot, backendName, status.devices)
        } else {
            GamepadStatus(false, backend = backendName, devices = status.devices)
        }
        if (nextStatus != status) publishStatus(nextStatus)
        val nextLt = if (frame.connected && config.gamepadEnabled) frame.lt else 0.0
        val nextRt = if (frame.connected && config.gamepadEnabled) frame.rt else 0.0
        publishTriggerLevels(nextLt, nextRt)
        val next = if (frame.connected) activeInputs(frame) else emptySet()
        if (isCapturing) {
            captureFromPad(next)
            activePad.clear(); activePad += next
            return
        }
        if (!config.gamepadEnabled || !frame.connected) {
            releaseAllGamepad()
            activePad.clear(); activePad += next
            return
        }
        GamepadInput.entries.reversed().forEach { input ->
            if (input in activePad && input !in next) heldPad[input]?.let { releasePadEntry(input, it) }
        }
        GamepadInput.entries.forEach { input ->
            if (input in next && input !in activePad) {
                val action = config.actionForGamepad(input)
                if (action != null) {
                    heldPad[input] = action
                    activate(action, padSource(input))
                }
            }
        }
        activePad.clear(); activePad += next
    }

    fun releaseAllGamepad() {
        GamepadInput.entries.reversed().forEach { input -> heldPad[input]?.let { releasePadEntry(input, it) } }
    }

    /** Hot-plug update of every connected controller (for the device selector). */
    fun updateDevices(devices: List<GamepadDevice>) {
        if (devices != status.devices) publishStatus(status.copy(devices = devices))
    }

    private fun releasePadEntry(input: GamepadInput, action: CameraInputAction) {
        heldPad.remove(input)
        deactivate(action, padSource(input))
    }

    /** Raw frame -> set of active inputs, applying deadzone / thresholds with hysteresis against [activePad]. */
    internal fun activeInputs(frame: GamepadFrame): Set<GamepadInput> {
        val out = LinkedHashSet<GamepadInput>()
        GamepadFrame.BUTTON_BITS.forEach { (input, bit) -> if (frame.buttons and bit != 0) out += input }
        val dz = config.stickDeadzone
        fun axis(value: Double, positive: GamepadInput, negative: GamepadInput) {
            fun on(input: GamepadInput, magnitude: Double): Boolean {
                val threshold = if (input in activePad) dz - InputBindingConfig.STICK_HYSTERESIS else dz
                return magnitude >= threshold
            }
            if (value > 0 && on(positive, abs(value))) out += positive
            if (value < 0 && on(negative, abs(value))) out += negative
        }
        axis(frame.ly, GamepadInput.LS_UP, GamepadInput.LS_DOWN)
        axis(frame.lx, GamepadInput.LS_RIGHT, GamepadInput.LS_LEFT)
        axis(frame.ry, GamepadInput.RS_UP, GamepadInput.RS_DOWN)
        axis(frame.rx, GamepadInput.RS_RIGHT, GamepadInput.RS_LEFT)
        val fullAt = config.triggerFullThreshold
        val ltHalfAt = minOf(InputBindingConfig.LT_HALF_THRESHOLD, fullAt - 0.10)
        trigger(frame.lt, GamepadInput.LT_HALF, GamepadInput.LT_FULL, ltHalfAt, fullAt, out)
        trigger(frame.rt, GamepadInput.RT_HALF, GamepadInput.RT_FULL, InputBindingConfig.RT_HALF_THRESHOLD, fullAt, out)
        return out
    }

    private fun trigger(value: Double, half: GamepadInput, full: GamepadInput, halfAt: Double, fullAt: Double, out: MutableSet<GamepadInput>) {
        fun stage(input: GamepadInput, threshold: Double): Boolean =
            value >= if (input in activePad) threshold - InputBindingConfig.TRIGGER_HYSTERESIS else threshold
        if (config.twoStageTriggerEnabled) {
            if (stage(half, halfAt)) out += half
            if (stage(full, fullAt)) out += full
        } else if (stage(half, halfAt)) {
            // Single-stage: one pull past the half threshold presses both stages together (S1 + S2)
            // and resolves trigger capture directly to the full trigger (RT/LT) instead of RT ½ / LT ½.
            out += half
            out += full
        }
    }

    // ---------------------------------------------------------------- actions & repeat

    private fun activate(action: CameraInputAction, source: String) {
        when (action.kind) {
            InputActionKind.HOLD -> sink.press(action, source)
            InputActionKind.STEP -> {
                sink.step(action)
                if (action.repeats) {
                    repeats[source] = Repeat(action, clock() + REPEAT_INITIAL_DELAY_NANOS)
                    needsTick = true
                }
            }
        }
    }

    private fun deactivate(action: CameraInputAction, source: String) {
        when (action.kind) {
            InputActionKind.HOLD -> sink.release(action, source)
            InputActionKind.STEP -> {
                repeats.remove(source)
                needsTick = repeats.isNotEmpty()
            }
        }
    }

    /** Emits due STEP auto-repeats. Bursts after a stall are collapsed to a single step. */
    fun tick(nowNanos: Long = clock()) {
        if (repeats.isEmpty()) return
        repeats.values.toList().forEach { repeat ->
            if (nowNanos >= repeat.nextDueNanos) {
                sink.step(repeat.action)
                repeat.nextDueNanos = if (nowNanos - repeat.nextDueNanos > REPEAT_INTERVAL_NANOS * 3) {
                    nowNanos + REPEAT_INTERVAL_NANOS
                } else {
                    repeat.nextDueNanos + REPEAT_INTERVAL_NANOS
                }
            }
        }
    }

    fun releaseAll() {
        releaseAllKeyboard()
        releaseAllGamepad()
        repeats.clear()
        needsTick = false
    }

    // ---------------------------------------------------------------- capture

    /**
     * Starts capturing the next key combination or gamepad input; [listener] receives it, or `null` on cancel.
     * Held inputs are released first; gamepad inputs already held when capture starts are ignored until let go.
     */
    fun beginCapture(listener: (CapturedInput?) -> Unit) {
        cancelCapture()
        releaseAll()
        captureIgnored.clear(); captureIgnored += activePad
        captureTriggerPeak.clear()
        captureListener = listener
    }

    fun cancelCapture() {
        if (captureListener != null) finishCapture(null)
    }

    private fun finishCapture(result: CapturedInput?) {
        val listener = captureListener ?: return
        captureListener = null
        captureIgnored.clear()
        captureTriggerPeak.clear()
        listener(result)
    }

    private fun captureFromPad(next: Set<GamepadInput>) {
        captureIgnored.retainAll(next)
        val triggers = listOf(
            Triple(GamepadInput.LT_HALF, GamepadInput.LT_FULL, GamepadInput.LT_HALF),
            Triple(GamepadInput.RT_HALF, GamepadInput.RT_FULL, GamepadInput.RT_HALF),
        )
        // Triggers report the deepest stage reached once they are let go (so RT_FULL is capturable).
        for ((half, full, key) in triggers) {
            if (half in captureIgnored || full in captureIgnored) continue
            if (full in next) captureTriggerPeak[key] = full
            else if (half in next && captureTriggerPeak[key] == null) captureTriggerPeak[key] = half
            val peak = captureTriggerPeak[key]
            if (peak != null && half !in next && full !in next) {
                finishCapture(CapturedInput.Pad(GamepadBindingSpec(peak)))
                return
            }
        }
        GamepadInput.entries.forEach { input ->
            if (input in TRIGGER_INPUTS) return@forEach
            if (input in next && input !in activePad && input !in captureIgnored) {
                finishCapture(CapturedInput.Pad(GamepadBindingSpec(input)))
                return
            }
        }
    }

    private fun publishStatus(next: GamepadStatus) {
        status = next
        statusListeners.toList().forEach { runCatching { it(next) } }
    }

    private fun publishTriggerLevels(lt: Double, rt: Double) {
        if (lt == lastLt && rt == lastRt) return
        lastLt = lt
        lastRt = rt
        triggerListeners.toList().forEach { runCatching { it(lt, rt) } }
    }

    companion object {
        const val KEY_SOURCE_PREFIX: String = "keyboard:binding:"
        const val PAD_SOURCE_PREFIX: String = "gamepad:"
        const val REPEAT_INITIAL_DELAY_NANOS: Long = 400_000_000L
        const val REPEAT_INTERVAL_NANOS: Long = 125_000_000L
        private val TRIGGER_INPUTS = setOf(GamepadInput.LT_HALF, GamepadInput.LT_FULL, GamepadInput.RT_HALF, GamepadInput.RT_FULL)
        fun keySource(code: String): String = KEY_SOURCE_PREFIX + code
        fun padSource(input: GamepadInput): String = PAD_SOURCE_PREFIX + input.name
    }
}
