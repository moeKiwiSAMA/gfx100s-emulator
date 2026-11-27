package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputBindingsTest {
    private class RecordingSink : CameraInputSink {
        val events = mutableListOf<String>()
        override fun press(action: CameraInputAction, source: String) { events += "+$action" }
        override fun release(action: CameraInputAction, source: String) { events += "-$action" }
        override fun step(action: CameraInputAction) { events += "step:$action" }
        fun take(): List<String> = events.toList().also { events.clear() }
    }

    private fun pad(buttons: Int = 0, lx: Double = 0.0, ly: Double = 0.0, rx: Double = 0.0, ry: Double = 0.0, lt: Double = 0.0, rt: Double = 0.0) =
        GamepadFrame(connected = true, slot = 0, name = "Test Pad", buttons = buttons, lx = lx, ly = ly, rx = rx, ry = ry, lt = lt, rt = rt)

    // ------------------------------------------------------------------ defaults

    @Test fun defaultKeyboardMapIsStrictlyEmpty() {
        val config = InputBindingConfig.default()
        assertTrue(config.keyboardBindings.isEmpty())
        assertTrue(config.gamepadEnabled)
        assertEquals(0.28, config.stickDeadzone)
        assertFalse(config.twoStageTriggerEnabled)
        assertEquals(0.50, config.triggerFullThreshold)
    }

    @Test fun defaultGamepadProfileIsComplete() {
        val g = InputBindingConfig.default().gamepadBindings.mapValues { (_, specs) -> specs.map { it.input } }
        val expected = mapOf(
            CameraInputAction.SHUTTER_HALF to listOf(GamepadInput.RT_HALF),
            CameraInputAction.SHUTTER_FULL to listOf(GamepadInput.RT_FULL),
            CameraInputAction.AF_ON to listOf(GamepadInput.B),
            CameraInputAction.AE_L to listOf(GamepadInput.Y),
            CameraInputAction.Q_MENU to listOf(GamepadInput.LB),
            CameraInputAction.DRIVE to listOf(GamepadInput.LT_HALF),
            CameraInputAction.UP to listOf(GamepadInput.DPAD_UP, GamepadInput.LS_UP),
            CameraInputAction.DOWN to listOf(GamepadInput.DPAD_DOWN, GamepadInput.LS_DOWN),
            CameraInputAction.LEFT to listOf(GamepadInput.DPAD_LEFT, GamepadInput.LS_LEFT),
            CameraInputAction.RIGHT to listOf(GamepadInput.DPAD_RIGHT, GamepadInput.LS_RIGHT),
            CameraInputAction.JOY_PRESS to listOf(GamepadInput.L3),
            CameraInputAction.REAR_DIAL_CCW to listOf(GamepadInput.RS_LEFT),
            CameraInputAction.REAR_DIAL_CW to listOf(GamepadInput.RS_RIGHT),
            CameraInputAction.FRONT_DIAL_CW to listOf(GamepadInput.RS_UP),
            CameraInputAction.FRONT_DIAL_CCW to listOf(GamepadInput.RS_DOWN),
            CameraInputAction.REAR_DIAL_PRESS to listOf(GamepadInput.R3),
            CameraInputAction.MENU_OK to listOf(GamepadInput.GUIDE),
            CameraInputAction.DISP_BACK to listOf(GamepadInput.A),
            CameraInputAction.PLAY to listOf(GamepadInput.START),
            CameraInputAction.DELETE to listOf(GamepadInput.X),
            CameraInputAction.VIEW_MODE to listOf(GamepadInput.BACK),
            CameraInputAction.FN1 to listOf(GamepadInput.RB),
        )
        assertEquals(expected, g)
        assertNull(InputBindingConfig.default().actionForGamepad(GamepadInput.LT_FULL), "LT full stage is left free")
    }

    // ------------------------------------------------------------------ key specs

    @Test fun keyBindingSpecParsesEncodesAndRejectsModifierOnly() {
        val spec = KeyBindingSpec.parse("ctrl+Shift+k")
        assertEquals(KeyBindingSpec("K", ctrl = true, shift = true), spec)
        assertEquals("Ctrl+Shift+K", spec.encode())
        assertEquals(spec, KeyBindingSpec.parse(spec.encode()))
        assertEquals(KeyBindingSpec("F5"), KeyBindingSpec.parse("F5"))
        assertFailsWith<IllegalArgumentException> { KeyBindingSpec.parse("SHIFT") }
        assertFailsWith<IllegalArgumentException> { KeyBindingSpec.parse("Hyper+K") }
        assertFailsWith<IllegalArgumentException> { KeyBindingSpec.parse("NOT_A_KEY") }
        assertFailsWith<IllegalArgumentException> { KeyBindingSpec.parse("Ctrl++") }
    }

    // ------------------------------------------------------------------ store

    @Test fun yamlRoundTripPreservesEverything() {
        val home = Files.createTempDirectory("input-bindings")
        val store = InputBindingStore(InputBindingStore.defaultPath(home))
        assertEquals(InputBindingConfig.default(), store.load(), "missing file yields defaults")
        assertFalse(Files.exists(store.path), "loading defaults must not create the file")
        val config = InputBindingConfig.default()
            .withKeyboardBinding(CameraInputAction.SHUTTER_HALF, KeyBindingSpec("S")).config
            .withKeyboardBinding(CameraInputAction.PLAY, KeyBindingSpec("P", ctrl = true, alt = true)).config
            .withGamepadBinding(CameraInputAction.ZOOM_IN, GamepadBindingSpec(GamepadInput.LT_FULL)).config
            .copy(
                gamepadEnabled = false,
                stickDeadzone = 0.35,
                twoStageTriggerEnabled = false,
                triggerFullThreshold = 0.50,
                selectedGamepad = "1:Xbox Wireless Controller",
            )
        store.save(config)
        assertEquals(config, store.load())
        val raw = Formats.readData(store.path)
        assertEquals(1, raw["schema"])
        assertEquals(0.50, (raw["triggerFullThreshold"] as Number).toDouble())
        assertEquals("1:Xbox Wireless Controller", raw["selectedGamepad"])
        assertEquals(listOf("Ctrl+Alt+P"), (raw["keyboard"] as Map<*, *>)["PLAY"])
        assertEquals(listOf("RT_HALF"), (raw["gamepad"] as Map<*, *>)["SHUTTER_HALF"])
    }

    @Test fun defaultsSerializeWithEmptyKeyboardSection() {
        val map = InputBindingStore.toYamlMap(InputBindingConfig.default())
        assertEquals(1, map["schema"])
        assertEquals(0.28, map["stickDeadzone"])
        assertEquals(false, map["twoStageTriggerEnabled"])
        assertEquals(0.50, map["triggerFullThreshold"])
        assertEquals(emptyMap<String, Any>(), map["keyboard"])
        assertTrue((map["gamepad"] as Map<*, *>).isNotEmpty())
    }

    @Test fun corruptOrIncompatibleFileFallsBackToDefaults() {
        val home = Files.createTempDirectory("input-bindings-corrupt")
        val store = InputBindingStore(InputBindingStore.defaultPath(home))
        store.path.writeText("schema: [this is : not valid")
        assertEquals(InputBindingConfig.default(), store.load())
        store.path.writeText("schema: 99\nkeyboard: {}\n")
        assertEquals(InputBindingConfig.default(), store.load())
        store.path.writeText("schema: 1\nstickDeadzone: 7.0\ntriggerFullThreshold: 0.1\nselectedGamepad: 'slot:42'\n")
        val clamped = store.load()
        assertEquals(InputBindingConfig.MAX_STICK_DEADZONE, clamped.stickDeadzone, "out-of-range deadzone is clamped")
        assertEquals(InputBindingConfig.MIN_TRIGGER_FULL_THRESHOLD, clamped.triggerFullThreshold, "out-of-range trigger threshold is clamped")
        assertEquals("auto", clamped.selectedGamepad, "invalid selection falls back to auto")
        assertTrue(clamped.keyboardBindings.isEmpty())
        assertEquals(InputBindingConfig.defaultGamepadBindings(), clamped.gamepadBindings)
        store.path.writeText("schema: 1\nkeyboard: [1, 2]\n")
        assertEquals(InputBindingConfig.default(), store.load(), "structurally invalid section -> defaults")
    }

    @Test fun unknownEntriesAreSkippedWithWarning() {
        val warnings = mutableListOf<String>()
        val config = InputBindingStore.fromYamlMap(
            mapOf(
                "schema" to 1,
                "keyboard" to mapOf("SHUTTER_HALF" to listOf("S"), "TELEPORT" to listOf("T"), "PLAY" to listOf("Bogus+P")),
                "gamepad" to mapOf("MENU_OK" to listOf("A", "PADDLE_9")),
            ),
            warnings::add,
        )
        assertEquals(mapOf(CameraInputAction.SHUTTER_HALF to listOf(KeyBindingSpec("S"))), config.keyboardBindings)
        assertEquals(mapOf(CameraInputAction.MENU_OK to listOf(GamepadBindingSpec(GamepadInput.A))), config.gamepadBindings)
        assertTrue(warnings.size >= 3, warnings.toString())
    }

    // ------------------------------------------------------------------ conflicts

    @Test fun rebindMovesInputAndReportsDisplacedAction() {
        val base = InputBindingConfig.default()
        val moved = base.withGamepadBinding(CameraInputAction.PLAY, GamepadBindingSpec(GamepadInput.B))
        assertEquals(CameraInputAction.AF_ON, moved.displaced)
        assertEquals(CameraInputAction.PLAY, moved.config.actionForGamepad(GamepadInput.B))
        assertNull(moved.config.gamepadBindings[CameraInputAction.AF_ON], "AF_ON lost its only input")
        assertNull(moved.config.actionForGamepad(GamepadInput.START), "PLAY's previous input is replaced")

        val k1 = base.withKeyboardBinding(CameraInputAction.UP, KeyBindingSpec("W"))
        assertNull(k1.displaced)
        val k2 = k1.config.withKeyboardBinding(CameraInputAction.DOWN, KeyBindingSpec("W"))
        assertEquals(CameraInputAction.UP, k2.displaced)
        assertEquals(mapOf(CameraInputAction.DOWN to listOf(KeyBindingSpec("W"))), k2.config.keyboardBindings)

        // Different modifiers are different shortcuts, so no conflict.
        val k3 = k2.config.withKeyboardBinding(CameraInputAction.UP, KeyBindingSpec("W", shift = true))
        assertNull(k3.displaced)
        assertEquals(2, k3.config.keyboardBindings.size)

        assertFailsWith<IllegalArgumentException> {
            InputBindingConfig(keyboardBindings = mapOf(
                CameraInputAction.UP to listOf(KeyBindingSpec("W")),
                CameraInputAction.DOWN to listOf(KeyBindingSpec("W")),
            ))
        }
    }

    @Test fun clearAndResetHelpers() {
        val custom = InputBindingConfig.default()
            .withKeyboardBinding(CameraInputAction.UP, KeyBindingSpec("W")).config
            .clearGamepad(CameraInputAction.MENU_OK)
        assertNull(custom.gamepadBindings[CameraInputAction.MENU_OK])
        assertTrue(custom.clearAllKeyboard().keyboardBindings.isEmpty())
        assertTrue(custom.clearKeyboard(CameraInputAction.UP).keyboardBindings.isEmpty())
        assertEquals(InputBindingConfig.defaultGamepadBindings(), custom.resetGamepadDefaults().gamepadBindings)
        assertEquals(custom.keyboardBindings, custom.resetGamepadDefaults().keyboardBindings, "reset keeps keyboard")
    }

    // ------------------------------------------------------------------ keyboard routing

    @Test fun keyboardRequiresExactModifiersAndIgnoresAutoRepeat() {
        val sink = RecordingSink()
        val config = InputBindingConfig.default()
            .withKeyboardBinding(CameraInputAction.SHUTTER_HALF, KeyBindingSpec("S")).config
            .withKeyboardBinding(CameraInputAction.PLAY, KeyBindingSpec("P", ctrl = true)).config
        val c = InputBindingController(config, sink)
        assertFalse(c.handleKey("P", pressed = true), "plain P is not Ctrl+P")
        assertFalse(c.handleKey("S", pressed = true, shift = true), "Shift+S is not S")
        assertTrue(c.handleKey("P", pressed = true, ctrl = true))
        assertTrue(c.handleKey("P", pressed = true, ctrl = true), "OS auto-repeat is swallowed")
        assertTrue(c.handleKey("P", pressed = false), "release matches by key even if Ctrl was let go first")
        assertTrue(c.handleKey("S", pressed = true))
        c.releaseAllKeyboard()
        assertFalse(c.handleKey("S", pressed = false), "already released")
        assertEquals(listOf("+PLAY", "-PLAY", "+SHUTTER_HALF", "-SHUTTER_HALF"), sink.take())
    }

    @Test fun defaultConfigNeverConsumesKeys() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        listOf("A", "S", "SPACE", "ENTER", "UP", "F6").forEach { assertFalse(c.handleKey(it, true)); assertFalse(c.handleKey(it, false)) }
        assertTrue(sink.events.isEmpty())
    }

    @Test fun textFieldFocusSuppressesShortcuts() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().withKeyboardBinding(CameraInputAction.UP, KeyBindingSpec("W")).config, sink)
        assertFalse(c.handleKey("W", pressed = true, textInputFocused = true))
        assertFalse(c.handleKey("W", pressed = false, textInputFocused = true))
        assertTrue(sink.events.isEmpty())
        assertTrue(c.handleKey("W", pressed = true))
        assertTrue(c.handleKey("W", pressed = false, textInputFocused = true), "a held shortcut still releases")
        assertEquals(listOf("+UP", "-UP"), sink.take())
    }

    @Test fun rebindingWhileHeldReleasesFirst() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().withKeyboardBinding(CameraInputAction.UP, KeyBindingSpec("W")).config, sink)
        c.handleKey("W", true)
        c.handleGamepadFrame(pad(buttons = GamepadFrame.GUIDE))
        c.updateConfig(c.config.clearKeyboard(CameraInputAction.UP).clearGamepad(CameraInputAction.MENU_OK))
        assertEquals(listOf("+UP", "+MENU_OK", "-UP", "-MENU_OK"), sink.take())
    }

    // ------------------------------------------------------------------ gamepad

    @Test fun defaultThresholdTriggerFiresFullShutterCleanlyAtThresholdWithHysteresis() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        // Below 50% threshold: idle (no conflicting SHUTTER_HALF hold).
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(rt = 0.45)); assertEquals(emptyList(), sink.take(), "below 50% threshold stays idle")
        // Crossing 50% fires SHUTTER_FULL directly (StudioShutter handles S1->AF->S2 cleanly).
        c.handleGamepadFrame(pad(rt = 0.52)); assertEquals(listOf("+SHUTTER_FULL"), sink.take())
        c.handleGamepadFrame(pad(rt = 0.47)); assertEquals(emptyList(), sink.take(), "within 5% hysteresis of 50%")
        c.handleGamepadFrame(pad(rt = 0.44)); assertEquals(listOf("-SHUTTER_FULL"), sink.take())
        // Default LT (bound only to LT_HALF -> DRIVE) also triggers cleanly at threshold.
        c.handleGamepadFrame(pad(lt = 0.45)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(lt = 0.55)); assertEquals(listOf("+DRIVE"), sink.take())
        c.handleGamepadFrame(pad(lt = 0.0)); assertEquals(listOf("-DRIVE"), sink.take())
    }

    @Test fun twoStageRightTriggerSequence() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().copy(twoStageTriggerEnabled = true, triggerFullThreshold = 0.65), sink)
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(rt = 0.4)); assertEquals(listOf("+SHUTTER_HALF"), sink.take(), "0 -> 0.4: S1 only")
        c.handleGamepadFrame(pad(rt = 0.9)); assertEquals(listOf("+SHUTTER_FULL"), sink.take(), "0.4 -> 0.9: S1 + S2")
        c.handleGamepadFrame(pad(rt = 0.4)); assertEquals(listOf("-SHUTTER_FULL"), sink.take(), "0.9 -> 0.4: S2 released, S1 kept")
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(listOf("-SHUTTER_HALF"), sink.take(), "0.4 -> 0: S1 released")
        // A fast full pull from rest still presses S1 before S2 and releases S2 before S1.
        c.handleGamepadFrame(pad(rt = 1.0)); assertEquals(listOf("+SHUTTER_HALF", "+SHUTTER_FULL"), sink.take())
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(listOf("-SHUTTER_FULL", "-SHUTTER_HALF"), sink.take())
    }

    @Test fun triggerHysteresisPreventsChatter() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().copy(twoStageTriggerEnabled = true, triggerFullThreshold = 0.65), sink)
        c.handleGamepadFrame(pad(rt = 0.67)); sink.take()
        c.handleGamepadFrame(pad(rt = 0.62)); assertEquals(emptyList(), sink.take(), "within hysteresis of 0.65")
        c.handleGamepadFrame(pad(rt = 0.59)); assertEquals(listOf("-SHUTTER_FULL"), sink.take())
        c.handleGamepadFrame(pad(rt = 0.21)); assertEquals(emptyList(), sink.take(), "within hysteresis of 0.25")
        c.handleGamepadFrame(pad(rt = 0.19)); assertEquals(listOf("-SHUTTER_HALF"), sink.take())
    }

    @Test fun customTriggerFullThresholdSupportsShortTravelHallTriggers() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().copy(triggerFullThreshold = 0.35), sink)
        c.handleGamepadFrame(pad(rt = 0.30)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(rt = 0.36)); assertEquals(listOf("+SHUTTER_FULL"), sink.take(), "short-travel threshold pull fires shutter")
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(listOf("-SHUTTER_FULL"), sink.take())
    }

    @Test fun singleStageTriggerHonorsThresholdAndCapturesFullTrigger() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().copy(twoStageTriggerEnabled = false, triggerFullThreshold = 0.45), sink)
        c.handleGamepadFrame(pad(rt = 0.35)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(rt = 0.48)); assertEquals(listOf("+SHUTTER_FULL"), sink.take())
        c.handleGamepadFrame(pad(rt = 0.9)); assertEquals(emptyList(), sink.take())
        c.handleGamepadFrame(pad(rt = 0.0)); assertEquals(listOf("-SHUTTER_FULL"), sink.take())

        // Capturing RT in single-stage mode resolves directly to RT_FULL at the configured threshold.
        val captured = mutableListOf<CapturedInput?>()
        c.beginCapture { captured += it }
        c.handleGamepadFrame(pad(rt = 0.48))
        c.handleGamepadFrame(pad(rt = 0.0))
        assertEquals(listOf<CapturedInput?>(CapturedInput.Pad(GamepadBindingSpec(GamepadInput.RT_FULL))), captured)
    }

    @Test fun hotDisconnectReleasesEverything() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default().copy(twoStageTriggerEnabled = true), sink)
        c.handleGamepadFrame(pad(buttons = GamepadFrame.GUIDE or GamepadFrame.B or GamepadFrame.DPAD_UP, rt = 1.0))
        assertEquals(setOf("+MENU_OK", "+AF_ON", "+UP", "+SHUTTER_HALF", "+SHUTTER_FULL"), sink.take().toSet())
        assertTrue(c.status.connected)
        c.handleGamepadFrame(GamepadFrame.DISCONNECTED)
        val released = sink.take()
        assertEquals(setOf("-MENU_OK", "-AF_ON", "-UP", "-SHUTTER_HALF", "-SHUTTER_FULL"), released.toSet())
        assertTrue(released.indexOf("-SHUTTER_FULL") < released.indexOf("-SHUTTER_HALF"), "S2 released before S1")
        assertFalse(c.status.connected)
    }

    @Test fun disablingGamepadReleasesAndIgnoresInput() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        c.handleGamepadFrame(pad(buttons = GamepadFrame.A))
        c.updateConfig(c.config.copy(gamepadEnabled = false))
        c.handleGamepadFrame(pad(buttons = GamepadFrame.A or GamepadFrame.B))
        assertEquals(listOf("+DISP_BACK", "-DISP_BACK"), sink.take())
    }

    @Test fun stickDeadzoneAndHysteresis() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        c.handleGamepadFrame(pad(ly = 0.27)); assertEquals(emptyList(), sink.take(), "inside 0.28 deadzone")
        c.handleGamepadFrame(pad(ly = 0.30)); assertEquals(listOf("+UP"), sink.take())
        c.handleGamepadFrame(pad(ly = 0.25)); assertEquals(emptyList(), sink.take(), "held until below 0.24")
        c.handleGamepadFrame(pad(ly = 0.20)); assertEquals(listOf("-UP"), sink.take())
        c.handleGamepadFrame(pad(lx = -0.9)); assertEquals(listOf("+LEFT"), sink.take())
        c.handleGamepadFrame(pad(lx = 0.9)); assertEquals(listOf("-LEFT", "+RIGHT"), sink.take())
        // D-pad and stick both drive UP: each source is held independently.
        c.handleGamepadFrame(pad(buttons = GamepadFrame.DPAD_UP, ly = 0.9))
        assertEquals(listOf("-RIGHT", "+UP", "+UP"), sink.take())
    }

    @Test fun dialStepsAutoRepeatWithFakeClock() {
        val sink = RecordingSink()
        var now = 0L
        val c = InputBindingController(InputBindingConfig.default(), sink) { now }
        c.handleGamepadFrame(pad(rx = 1.0))
        assertEquals(listOf("step:REAR_DIAL_CW"), sink.take())
        assertTrue(c.needsTick)
        now = 399_000_000L; c.tick(); assertEquals(emptyList(), sink.take())
        now = 400_000_000L; c.tick(); assertEquals(listOf("step:REAR_DIAL_CW"), sink.take())
        now = 525_000_000L; c.tick(); assertEquals(listOf("step:REAR_DIAL_CW"), sink.take())
        now = 5_000_000_000L; c.tick(); assertEquals(listOf("step:REAR_DIAL_CW"), sink.take(), "a stall collapses into one step")
        c.handleGamepadFrame(pad())
        assertFalse(c.needsTick)
        now += 1_000_000_000L; c.tick(); assertEquals(emptyList(), sink.take())
        // HOLD actions never repeat host-side (the firmware repeats held body buttons itself).
        c.handleGamepadFrame(pad(buttons = GamepadFrame.DPAD_DOWN))
        assertFalse(c.needsTick)
        assertEquals(listOf("+DOWN"), sink.take())
    }

    @Test fun captureReportsKeysPadButtonsAndTriggerPeak() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        val captured = mutableListOf<CapturedInput?>()
        c.beginCapture { captured += it }
        assertTrue(c.handleKey("SHIFT", true, shift = true), "modifier alone keeps waiting")
        assertTrue(c.isCapturing)
        assertTrue(c.handleKey("K", true, ctrl = true, shift = true))
        assertEquals(CapturedInput.Key(KeyBindingSpec("K", ctrl = true, shift = true)), captured.removeLast())
        assertFalse(c.isCapturing)

        c.beginCapture { captured += it }
        c.handleKey("ESCAPE", true)
        assertEquals(listOf<CapturedInput?>(null), captured.toList()); captured.clear()

        c.beginCapture { captured += it }
        c.handleGamepadFrame(pad(buttons = GamepadFrame.Y))
        assertEquals(CapturedInput.Pad(GamepadBindingSpec(GamepadInput.Y)), captured.removeLast())
        c.handleGamepadFrame(pad())

        c.beginCapture { captured += it }
        c.handleGamepadFrame(pad(rt = 0.5))
        c.handleGamepadFrame(pad(rt = 1.0))
        assertTrue(captured.isEmpty(), "trigger reports on release")
        c.handleGamepadFrame(pad(rt = 0.0))
        assertEquals(CapturedInput.Pad(GamepadBindingSpec(GamepadInput.RT_FULL)), captured.removeLast())

        // In two-stage mode, a partial pull captures LT_HALF; in default single-stage mode, crossing threshold captures LT_FULL.
        c.updateConfig(c.config.copy(twoStageTriggerEnabled = true, triggerFullThreshold = 0.70))
        c.beginCapture { captured += it }
        c.handleGamepadFrame(pad(lt = 0.6)); c.handleGamepadFrame(pad(lt = 0.0))
        assertEquals(CapturedInput.Pad(GamepadBindingSpec(GamepadInput.LT_HALF)), captured.removeLast())
        assertTrue(sink.events.isEmpty(), "nothing reaches the camera while capturing")
    }

    @Test fun captureIgnoresInputsAlreadyHeld() {
        val sink = RecordingSink()
        val c = InputBindingController(InputBindingConfig.default(), sink)
        c.handleGamepadFrame(pad(buttons = GamepadFrame.B))
        val captured = mutableListOf<CapturedInput?>()
        c.beginCapture { captured += it }
        assertEquals(listOf("+AF_ON", "-AF_ON"), sink.take(), "capture releases held actions")
        c.handleGamepadFrame(pad(buttons = GamepadFrame.B))
        assertTrue(captured.isEmpty())
        c.handleGamepadFrame(pad(buttons = GamepadFrame.B or GamepadFrame.X))
        assertEquals(CapturedInput.Pad(GamepadBindingSpec(GamepadInput.X)), captured.single())
    }

    // ------------------------------------------------------------------ mapping

    @Test fun everyActionHasARoute() {
        val routed = CameraInputMapping.buttonKeys.keys + CameraInputMapping.settingControls.keys + CameraInputMapping.latchToggles.keys +
            setOf(
                CameraInputAction.VIEW_MODE, CameraInputAction.FOCUS_MODE_CYCLE, CameraInputAction.FOCUS_NEAR, CameraInputAction.FOCUS_FAR,
                CameraInputAction.FRONT_DIAL_CW, CameraInputAction.FRONT_DIAL_CCW, CameraInputAction.REAR_DIAL_CW, CameraInputAction.REAR_DIAL_CCW,
                CameraInputAction.MODE_DIAL_CW, CameraInputAction.MODE_DIAL_CCW, CameraInputAction.ZOOM_IN, CameraInputAction.ZOOM_OUT,
                CameraInputAction.EYE_SENSOR_TOGGLE, CameraInputAction.EVF_WINDOW_TOGGLE,
            )
        assertEquals(CameraInputAction.entries.toSet(), routed)
        CameraInputAction.entries.filter { it.kind == InputActionKind.HOLD }.forEach { action ->
            assertTrue(action in CameraInputMapping.buttonKeys || action in CameraInputMapping.settingControls || action == CameraInputAction.VIEW_MODE, "$action")
        }
        assertTrue(PanelCatalog.buttonKeys.containsAll(CameraInputMapping.buttonKeys.values + CameraInputMapping.latchToggles.values))
        assertEquals("shutterHalf", CameraInputMapping.buttonKeys[CameraInputAction.SHUTTER_HALF])
        assertEquals("shutterFull", CameraInputMapping.buttonKeys[CameraInputAction.SHUTTER_FULL])
    }

    @Test fun lensStepperClampsZoomAndFocus() {
        val zoom = LensProfileState.fromMaps(null, null, null, null).copy(
            focalLengthMm = 50.0, minFocalLengthMm = 32.0, maxFocalLengthMm = 64.0, focusDistanceM = 2.0, minFocusDistanceM = 0.5,
        )
        run {
            val step = LensBindingStepper.changesFor(zoom, zoomSteps = 1, focusSteps = 0)
            assertEquals(zoom.focalLengthMm * LensBindingStepper.ZOOM_RATIO, step.getValue("focalLengthMm") as Double, 1e-9)
            val huge = LensBindingStepper.changesFor(zoom, zoomSteps = 1000, focusSteps = 0)
            assertEquals(zoom.maxFocalLengthMm, huge.getValue("focalLengthMm") as Double, 1e-9)
        }
        val far = LensBindingStepper.changesFor(zoom, zoomSteps = 0, focusSteps = 10_000)
        assertEquals(LensBindingStepper.MAX_FOCUS_DISTANCE_M, far.getValue("focusDistanceM") as Double, 1e-6)
        val near = LensBindingStepper.changesFor(zoom, zoomSteps = 0, focusSteps = -10_000)
        assertEquals(zoom.minFocusDistanceM.coerceAtLeast(0.05), near.getValue("focusDistanceM") as Double, 1e-9)
        val fixed = zoom.copy(minFocalLengthMm = 50.0, maxFocalLengthMm = 50.0, focalLengthMm = 50.0)
        assertTrue(LensBindingStepper.changesFor(fixed, zoomSteps = 3, focusSteps = 0).isEmpty(), "prime lenses ignore zoom")
    }
}
