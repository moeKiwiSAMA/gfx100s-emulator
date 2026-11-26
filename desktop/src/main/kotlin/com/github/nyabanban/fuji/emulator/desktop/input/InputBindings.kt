package com.github.nyabanban.fuji.emulator.desktop.input

import javafx.scene.input.KeyCode

/** Groups actions in the bindings UI; [i18nKey] resolves the localized section title. */
enum class InputActionCategory(val i18nKey: String) {
    SHUTTER_FOCUS("input.category.shutter_focus"),
    NAVIGATION("input.category.navigation"),
    DIALS_LENS("input.category.dials_lens"),
    FUNCTION("input.category.function"),
    VIEWFINDER_POWER("input.category.viewfinder_power"),
}

/**
 * [HOLD] actions mirror a physical momentary switch: press while the input is held, release when it is let go
 * (the firmware itself performs any key auto-repeat, exactly like a held body button).
 * [STEP] actions emit one discrete detent / toggle per press; when [CameraInputAction.repeats] is set they also
 * auto-repeat while the input stays held (dials, lens focus/zoom).
 */
enum class InputActionKind { HOLD, STEP }

/** Every host-side camera control that a keyboard shortcut or gamepad input can drive. */
enum class CameraInputAction(
    val category: InputActionCategory,
    val kind: InputActionKind,
    val repeats: Boolean = false,
) {
    SHUTTER_HALF(InputActionCategory.SHUTTER_FOCUS, InputActionKind.HOLD),
    SHUTTER_FULL(InputActionCategory.SHUTTER_FOCUS, InputActionKind.HOLD),
    AF_ON(InputActionCategory.SHUTTER_FOCUS, InputActionKind.HOLD),
    AE_L(InputActionCategory.SHUTTER_FOCUS, InputActionKind.HOLD),
    FOCUS_MODE_CYCLE(InputActionCategory.SHUTTER_FOCUS, InputActionKind.STEP),
    FOCUS_NEAR(InputActionCategory.SHUTTER_FOCUS, InputActionKind.STEP, repeats = true),
    FOCUS_FAR(InputActionCategory.SHUTTER_FOCUS, InputActionKind.STEP, repeats = true),

    MENU_OK(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    DISP_BACK(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    PLAY(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    DELETE(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    Q_MENU(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    UP(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    DOWN(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    LEFT(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    RIGHT(InputActionCategory.NAVIGATION, InputActionKind.HOLD),
    JOY_PRESS(InputActionCategory.NAVIGATION, InputActionKind.HOLD),

    FRONT_DIAL_CW(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),
    FRONT_DIAL_CCW(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),
    FRONT_DIAL_PRESS(InputActionCategory.DIALS_LENS, InputActionKind.HOLD),
    REAR_DIAL_CW(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),
    REAR_DIAL_CCW(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),
    REAR_DIAL_PRESS(InputActionCategory.DIALS_LENS, InputActionKind.HOLD),
    MODE_DIAL_CW(InputActionCategory.DIALS_LENS, InputActionKind.STEP),
    MODE_DIAL_CCW(InputActionCategory.DIALS_LENS, InputActionKind.STEP),
    ZOOM_IN(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),
    ZOOM_OUT(InputActionCategory.DIALS_LENS, InputActionKind.STEP, repeats = true),

    DRIVE(InputActionCategory.FUNCTION, InputActionKind.HOLD),
    FN1(InputActionCategory.FUNCTION, InputActionKind.HOLD),
    FN2(InputActionCategory.FUNCTION, InputActionKind.HOLD),
    FN4(InputActionCategory.FUNCTION, InputActionKind.HOLD),
    /** Flips the latching STILL / MOVIE body switch once per press. */
    MOVIE_MODE(InputActionCategory.FUNCTION, InputActionKind.STEP),

    VIEW_MODE(InputActionCategory.VIEWFINDER_POWER, InputActionKind.HOLD),
    EYE_SENSOR_TOGGLE(InputActionCategory.VIEWFINDER_POWER, InputActionKind.STEP),
    EVF_WINDOW_TOGGLE(InputActionCategory.VIEWFINDER_POWER, InputActionKind.STEP),
    /** Flips the latching power switch once per press (never bound by default). */
    POWER_TOGGLE(InputActionCategory.VIEWFINDER_POWER, InputActionKind.STEP),
    ;

    val i18nKey: String get() = "input.action." + name.lowercase()
}

/**
 * A keyboard shortcut: one JavaFX [KeyCode] name (e.g. `K`, `DIGIT1`, `F5`) plus exact modifier state.
 * Matching is exact, so `Ctrl+K` never fires on a plain `K`.
 */
data class KeyBindingSpec(
    val keyCode: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    init {
        require(runCatching { KeyCode.valueOf(keyCode) }.isSuccess) { "Unknown key code: $keyCode" }
        require(keyCode !in MODIFIER_KEY_CODES) { "A modifier key alone cannot be a shortcut: $keyCode" }
    }

    fun matches(code: String, ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, metaDown: Boolean): Boolean =
        code == keyCode && ctrlDown == ctrl && altDown == alt && shiftDown == shift && metaDown == meta

    /** Canonical, parseable, language-neutral text such as `Ctrl+Shift+K`. */
    fun display(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(KeyCode.valueOf(keyCode).getName())
    }.joinToString("+")

    /** Canonical persisted form: modifiers followed by the exact [KeyCode] enum name. */
    fun encode(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(keyCode)
    }.joinToString("+")

    companion object {
        val MODIFIER_KEY_CODES: Set<String> = setOf(
            "SHIFT", "CONTROL", "ALT", "META", "WINDOWS", "COMMAND", "ALT_GRAPH", "CAPS", "SHORTCUT",
        )

        /** Parses [encode] output; modifier names are case-insensitive. */
        fun parse(text: String): KeyBindingSpec {
            val parts = text.split('+').map { it.trim() }
            require(parts.isNotEmpty() && parts.none { it.isEmpty() }) { "Invalid key binding: '$text'" }
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            parts.dropLast(1).forEach { modifier ->
                when (modifier.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                    "meta", "cmd", "command" -> meta = true
                    else -> throw IllegalArgumentException("Unknown modifier '$modifier' in '$text'")
                }
            }
            return KeyBindingSpec(parts.last().uppercase(), ctrl, alt, shift, meta)
        }
    }
}

/** Physical gamepad inputs. Declaration order is also press order (HALF before FULL) and reversed for releases. */
enum class GamepadInput(val label: String) {
    A("A"), B("B"), X("X"), Y("Y"),
    LB("LB"), RB("RB"), BACK("Back"), START("Start"), L3("L3"), R3("R3"),
    DPAD_UP("D-Pad \u2191"), DPAD_DOWN("D-Pad \u2193"), DPAD_LEFT("D-Pad \u2190"), DPAD_RIGHT("D-Pad \u2192"),
    LS_UP("LS \u2191"), LS_DOWN("LS \u2193"), LS_LEFT("LS \u2190"), LS_RIGHT("LS \u2192"),
    RS_UP("RS \u2191"), RS_DOWN("RS \u2193"), RS_LEFT("RS \u2190"), RS_RIGHT("RS \u2192"),
    LT_HALF("LT \u00BD"), LT_FULL("LT"),
    RT_HALF("RT \u00BD"), RT_FULL("RT"),
}

/** A gamepad binding (thin wrapper so future specs, e.g. chords, stay source compatible). */
data class GamepadBindingSpec(val input: GamepadInput) {
    fun display(): String = input.label
}

/** One connected controller as reported by the native bridge (slot 0..3 plus its device name). */
data class GamepadDevice(val slot: Int, val name: String) {
    /** Persisted pinned form `<slot>:<name>`. */
    fun selectionId(): String = "$slot:$name"
}

/**
 * Which controller drives the camera. Persisted as `selectedGamepad`:
 * - `auto`: the first connected controller (sticky while it stays connected);
 * - `slot:<0..3>`: whatever controller sits in that slot;
 * - `<slot>:<name>`: one specific device, matched by name (so it is followed when it reconnects in another slot).
 */
sealed interface GamepadSelection {
    fun encode(): String

    data object Auto : GamepadSelection {
        override fun encode(): String = AUTO
    }

    data class Slot(val slot: Int) : GamepadSelection {
        init { require(slot in 0 until MAX_SLOTS) { "Gamepad slot must be 0..${MAX_SLOTS - 1}: $slot" } }
        override fun encode(): String = "slot:$slot"
    }

    data class Device(val slot: Int, val name: String) : GamepadSelection {
        init {
            require(slot in 0 until MAX_SLOTS) { "Gamepad slot must be 0..${MAX_SLOTS - 1}: $slot" }
            require(name.isNotBlank()) { "Pinned gamepad name must not be blank" }
        }
        override fun encode(): String = "$slot:$name"
    }

    /** Picks the frame this selection follows among [connected] frames; `null` when the selected pad is absent. */
    fun choose(connected: List<GamepadFrame>, followedSlot: Int): GamepadFrame? = when (this) {
        Auto -> connected.firstOrNull { it.slot == followedSlot } ?: connected.minByOrNull { it.slot }
        is Slot -> connected.firstOrNull { it.slot == slot }
        is Device -> connected.firstOrNull { it.slot == slot && it.name == name }
            ?: connected.filter { it.name == name }.let { same -> same.firstOrNull { it.slot == followedSlot } ?: same.minByOrNull { it.slot } }
    }

    companion object {
        const val AUTO: String = "auto"
        const val MAX_SLOTS: Int = 4

        fun parse(text: String): GamepadSelection {
            val trimmed = text.trim()
            if (trimmed.equals(AUTO, ignoreCase = true)) return Auto
            if (trimmed.startsWith("slot:", ignoreCase = true)) {
                val slot = trimmed.substring(5).trim().toIntOrNull() ?: throw IllegalArgumentException("Invalid gamepad slot: '$text'")
                return Slot(slot)
            }
            val colon = trimmed.indexOf(':')
            require(colon > 0) { "Invalid gamepad selection: '$text' (expected auto, slot:<n> or <slot>:<name>)" }
            val slot = trimmed.substring(0, colon).trim().toIntOrNull() ?: throw IllegalArgumentException("Invalid gamepad selection: '$text'")
            return Device(slot, trimmed.substring(colon + 1).trim())
        }
    }
}

/**
 * Complete user binding configuration. Every physical key combination / gamepad input drives at most one action;
 * an action may own several inputs (e.g. `UP` = D-Pad up and left-stick up).
 */
data class InputBindingConfig(
    val gamepadEnabled: Boolean = true,
    val stickDeadzone: Double = DEFAULT_STICK_DEADZONE,
    val twoStageTriggerEnabled: Boolean = true,
    val triggerFullThreshold: Double = DEFAULT_TRIGGER_FULL_THRESHOLD,
    val keyboardBindings: Map<CameraInputAction, List<KeyBindingSpec>> = emptyMap(),
    val gamepadBindings: Map<CameraInputAction, List<GamepadBindingSpec>> = defaultGamepadBindings(),
    /** Encoded [GamepadSelection]: `auto`, `slot:<n>` or `<slot>:<name>`. */
    val selectedGamepad: String = GamepadSelection.AUTO,
) {
    /** Parsed [selectedGamepad] (validated in `init`). */
    val gamepadSelection: GamepadSelection by lazy { GamepadSelection.parse(selectedGamepad) }

    init {
        require(runCatching { GamepadSelection.parse(selectedGamepad) }.isSuccess) { "Invalid selectedGamepad: '$selectedGamepad'" }
        require(stickDeadzone.isFinite() && stickDeadzone in MIN_STICK_DEADZONE..MAX_STICK_DEADZONE) {
            "stickDeadzone must be within $MIN_STICK_DEADZONE..$MAX_STICK_DEADZONE: $stickDeadzone"
        }
        require(triggerFullThreshold.isFinite() && triggerFullThreshold in MIN_TRIGGER_FULL_THRESHOLD..MAX_TRIGGER_FULL_THRESHOLD) {
            "triggerFullThreshold must be within $MIN_TRIGGER_FULL_THRESHOLD..$MAX_TRIGGER_FULL_THRESHOLD: $triggerFullThreshold"
        }
        val keys = keyboardBindings.values.flatten()
        require(keys.size == keys.toSet().size) { "A key combination is bound to more than one action" }
        val pads = gamepadBindings.values.flatten()
        require(pads.size == pads.toSet().size) { "A gamepad input is bound to more than one action" }
        require(keyboardBindings.values.none { it.isEmpty() } && gamepadBindings.values.none { it.isEmpty() }) {
            "Empty binding lists must be omitted"
        }
    }

    fun actionForKey(code: String, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): CameraInputAction? =
        keyboardBindings.entries.firstOrNull { (_, specs) -> specs.any { it.matches(code, ctrl, alt, shift, meta) } }?.key

    fun actionForGamepad(input: GamepadInput): CameraInputAction? =
        gamepadBindings.entries.firstOrNull { (_, specs) -> specs.any { it.input == input } }?.key

    /** Result of a rebind: the new config plus the action (if any) that previously owned the input. */
    data class Rebind(val config: InputBindingConfig, val displaced: CameraInputAction?)

    /** Makes [spec] the only keyboard shortcut of [action], moving it away from any other action. */
    fun withKeyboardBinding(action: CameraInputAction, spec: KeyBindingSpec): Rebind {
        val displaced = keyboardBindings.entries.firstOrNull { (other, specs) -> other != action && spec in specs }?.key
        val next = LinkedHashMap<CameraInputAction, List<KeyBindingSpec>>()
        keyboardBindings.forEach { (other, specs) ->
            if (other != action) specs.filter { it != spec }.takeIf { it.isNotEmpty() }?.let { next[other] = it }
        }
        next[action] = listOf(spec)
        return Rebind(copy(keyboardBindings = next), displaced)
    }

    /** Makes [spec] the only gamepad input of [action], moving it away from any other action. */
    fun withGamepadBinding(action: CameraInputAction, spec: GamepadBindingSpec): Rebind {
        val displaced = gamepadBindings.entries.firstOrNull { (other, specs) -> other != action && spec in specs }?.key
        val next = LinkedHashMap<CameraInputAction, List<GamepadBindingSpec>>()
        gamepadBindings.forEach { (other, specs) ->
            if (other != action) specs.filter { it != spec }.takeIf { it.isNotEmpty() }?.let { next[other] = it }
        }
        next[action] = listOf(spec)
        return Rebind(copy(gamepadBindings = next), displaced)
    }

    fun clearKeyboard(action: CameraInputAction): InputBindingConfig = copy(keyboardBindings = keyboardBindings - action)
    fun clearGamepad(action: CameraInputAction): InputBindingConfig = copy(gamepadBindings = gamepadBindings - action)
    fun clearAllKeyboard(): InputBindingConfig = copy(keyboardBindings = emptyMap())
    fun resetGamepadDefaults(): InputBindingConfig = copy(gamepadBindings = defaultGamepadBindings())

    companion object {
        const val SCHEMA: Int = 1
        const val DEFAULT_STICK_DEADZONE: Double = 0.28
        const val MIN_STICK_DEADZONE: Double = 0.10
        const val MAX_STICK_DEADZONE: Double = 0.50
        /** Stick direction stays active until it falls this far below the deadzone (anti-chatter). */
        const val STICK_HYSTERESIS: Double = 0.04
        const val DEFAULT_TRIGGER_FULL_THRESHOLD: Double = 0.65
        const val MIN_TRIGGER_FULL_THRESHOLD: Double = 0.40
        const val MAX_TRIGGER_FULL_THRESHOLD: Double = 0.95
        const val RT_HALF_THRESHOLD: Double = 0.25
        const val RT_FULL_THRESHOLD: Double = DEFAULT_TRIGGER_FULL_THRESHOLD
        const val LT_HALF_THRESHOLD: Double = 0.50
        const val LT_FULL_THRESHOLD: Double = DEFAULT_TRIGGER_FULL_THRESHOLD
        /** A trigger stage releases only below `threshold - TRIGGER_HYSTERESIS`. */
        const val TRIGGER_HYSTERESIS: Double = 0.05

        /** Strictly empty keyboard map and the ergonomic default gamepad profile. */
        fun default(): InputBindingConfig = InputBindingConfig()

        fun defaultGamepadBindings(): Map<CameraInputAction, List<GamepadBindingSpec>> {
            fun pads(vararg inputs: GamepadInput) = inputs.map(::GamepadBindingSpec)
            return linkedMapOf(
                CameraInputAction.SHUTTER_HALF to pads(GamepadInput.RT_HALF),
                CameraInputAction.SHUTTER_FULL to pads(GamepadInput.RT_FULL),
                CameraInputAction.AF_ON to pads(GamepadInput.RB),
                CameraInputAction.Q_MENU to pads(GamepadInput.LB),
                CameraInputAction.DRIVE to pads(GamepadInput.LT_HALF),
                CameraInputAction.UP to pads(GamepadInput.DPAD_UP, GamepadInput.LS_UP),
                CameraInputAction.DOWN to pads(GamepadInput.DPAD_DOWN, GamepadInput.LS_DOWN),
                CameraInputAction.LEFT to pads(GamepadInput.DPAD_LEFT, GamepadInput.LS_LEFT),
                CameraInputAction.RIGHT to pads(GamepadInput.DPAD_RIGHT, GamepadInput.LS_RIGHT),
                CameraInputAction.JOY_PRESS to pads(GamepadInput.L3),
                CameraInputAction.REAR_DIAL_CCW to pads(GamepadInput.RS_LEFT),
                CameraInputAction.REAR_DIAL_CW to pads(GamepadInput.RS_RIGHT),
                CameraInputAction.FRONT_DIAL_CW to pads(GamepadInput.RS_UP),
                CameraInputAction.FRONT_DIAL_CCW to pads(GamepadInput.RS_DOWN),
                CameraInputAction.REAR_DIAL_PRESS to pads(GamepadInput.R3),
                CameraInputAction.MENU_OK to pads(GamepadInput.B),
                CameraInputAction.DISP_BACK to pads(GamepadInput.A),
                CameraInputAction.PLAY to pads(GamepadInput.X),
                CameraInputAction.DELETE to pads(GamepadInput.Y),
                CameraInputAction.VIEW_MODE to pads(GamepadInput.BACK),
                CameraInputAction.FN1 to pads(GamepadInput.START),
            )
        }
    }
}
