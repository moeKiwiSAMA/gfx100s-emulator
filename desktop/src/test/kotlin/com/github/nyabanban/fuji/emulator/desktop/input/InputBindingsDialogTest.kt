package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InputBindingsDialogTest {
    private fun onFx(block: () -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val task = FutureTask { block() }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        task.get(60, TimeUnit.SECONDS)
    }

    private fun key(type: javafx.event.EventType<KeyEvent>, code: KeyCode, ctrl: Boolean = false, shift: Boolean = false) =
        KeyEvent(type, "", "", code, shift, ctrl, false, false)

    private class Sink : CameraInputSink {
        val events = mutableListOf<String>()
        override fun press(action: CameraInputAction, source: String) { events += "+$action" }
        override fun release(action: CameraInputAction, source: String) { events += "-$action" }
        override fun step(action: CameraInputAction) { events += "step:$action" }
    }

    @Test fun dialogCapturesClearsResetsAndFollowsLocale() = onFx {
        Platform.setImplicitExit(false)
        I18n.resetToDefault()
        val home = Files.createTempDirectory("input-dialog")
        val store = InputBindingStore(InputBindingStore.defaultPath(home))
        val sink = Sink()
        val controller = InputBindingController(store.load(), sink)
        val dialog = InputBindingsDialog(null, controller, store::save, gamepadAvailable = { true })
        try {
            dialog.setVisible(true)
            val scene = dialog.stage.scene
            fun button(id: String) = assertNotNull(scene.lookup("#$id") as? Button, id)

            // Every action has a row with keyboard + gamepad cells and clear buttons.
            CameraInputAction.entries.forEach { action ->
                button("input-binding-kb-${action.name}")
                button("input-binding-gp-${action.name}")
                button("input-binding-kb-clear-${action.name}")
                button("input-binding-gp-clear-${action.name}")
            }
            assertNotNull(scene.lookup("#input-bindings-status") as? Label)
            assertNotNull(scene.lookup("#input-bindings-gamepad-enabled") as? CheckBox)
            val slider = assertNotNull(scene.lookup("#input-bindings-deadzone") as? Slider)
            assertEquals(0.10, slider.min); assertEquals(0.50, slider.max); assertEquals(0.28, slider.value)
            assertNotNull(scene.lookup("#input-bindings-two-stage") as? CheckBox)
            val triggerSlider = assertNotNull(scene.lookup("#input-bindings-trigger-full") as? Slider)
            assertEquals(0.40, triggerSlider.min); assertEquals(0.95, triggerSlider.max); assertEquals(0.65, triggerSlider.value)
            val triggerLive = assertNotNull(scene.lookup("#input-bindings-trigger-live") as? Label)
            assertTrue(triggerLive.text.contains("0%"))

            // Defaults: every keyboard cell unbound, gamepad cells show the default profile.
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.SHUTTER_HALF, gamepad = false))
            assertEquals("RT \u00BD", dialog.cellText(CameraInputAction.SHUTTER_HALF, gamepad = true))
            assertEquals(I18n.t("input.status.none"), dialog.statusText)
            assertTrue(button("input-binding-kb-clear-SHUTTER_HALF").isDisable)

            // Live capture of Ctrl+S onto SHUTTER_HALF via a real key event through the dialog scene.
            button("input-binding-kb-SHUTTER_HALF").fire()
            assertTrue(dialog.isCapturing)
            assertEquals(I18n.t("input.binding.capture_prompt"), dialog.cellText(CameraInputAction.SHUTTER_HALF, gamepad = false))
            assertTrue(button("input-binding-kb-SHUTTER_HALF").styleClass.contains("input-binding-capturing"))
            javafx.event.Event.fireEvent(scene.root, key(KeyEvent.KEY_PRESSED, KeyCode.S, ctrl = true))
            javafx.event.Event.fireEvent(scene.root, key(KeyEvent.KEY_RELEASED, KeyCode.S, ctrl = true))
            assertFalse(dialog.isCapturing)
            assertEquals("Ctrl+S", dialog.cellText(CameraInputAction.SHUTTER_HALF, gamepad = false))
            assertEquals(listOf(KeyBindingSpec("S", ctrl = true)), store.load().keyboardBindings[CameraInputAction.SHUTTER_HALF], "persisted")
            assertTrue(sink.events.isEmpty(), "capture never drives the camera")

            // Capturing the same shortcut onto another action moves it and flags the old row.
            button("input-binding-kb-AF_ON").fire()
            javafx.event.Event.fireEvent(scene.root, key(KeyEvent.KEY_PRESSED, KeyCode.S, ctrl = true))
            assertEquals("Ctrl+S", dialog.cellText(CameraInputAction.AF_ON, gamepad = false))
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.SHUTTER_HALF, gamepad = false))
            assertTrue(button("input-binding-kb-SHUTTER_HALF").styleClass.contains("input-binding-conflict"))
            assertEquals(
                I18n.t("input.message.moved", "Ctrl+S", I18n.t("input.action.shutter_half"), I18n.t("input.action.af_on")),
                dialog.messageText,
            )

            // Esc cancels without changing anything.
            button("input-binding-kb-PLAY").fire()
            javafx.event.Event.fireEvent(scene.root, key(KeyEvent.KEY_PRESSED, KeyCode.ESCAPE))
            assertFalse(dialog.isCapturing)
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.PLAY, gamepad = false))
            assertEquals(I18n.t("input.message.cancelled"), dialog.messageText)

            // Gamepad capture through controller frames (as the poller would deliver them).
            button("input-binding-gp-PLAY").fire()
            controller.handleGamepadFrame(GamepadFrame(connected = true, slot = 0, name = "Pad", buttons = GamepadFrame.B))
            controller.handleGamepadFrame(GamepadFrame(connected = true, slot = 0, name = "Pad"))
            assertEquals("B", dialog.cellText(CameraInputAction.PLAY, gamepad = true))
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.MENU_OK, gamepad = true), "B moved off MENU_OK")
            assertEquals(I18n.t("input.status.connected", "Pad", 1), dialog.statusText)

            // × clears one binding.
            button("input-binding-gp-clear-PLAY").fire()
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.PLAY, gamepad = true))

            // Reset Gamepad Default / Clear All Keyboard.
            button("input-bindings-reset-gamepad").fire()
            assertEquals(InputBindingConfig.defaultGamepadBindings(), controller.config.gamepadBindings)
            assertEquals("B", dialog.cellText(CameraInputAction.MENU_OK, gamepad = true))
            assertEquals("A", dialog.cellText(CameraInputAction.DISP_BACK, gamepad = true))
            assertEquals(1, controller.config.keyboardBindings.size)
            button("input-bindings-clear-keyboard").fire()
            assertTrue(controller.config.keyboardBindings.isEmpty())
            assertEquals(InputBindingConfig.default(), store.load(), "back to the shipped defaults on disk")

            // Controller selector: live device list from hot-plug, pinning persists.
            assertEquals(listOf("auto"), dialog.deviceItems)
            controller.updateDevices(listOf(GamepadDevice(0, "Pad"), GamepadDevice(1, "Xbox")))
            assertEquals(listOf("auto", "0:Pad", "1:Xbox"), dialog.deviceItems)
            assertEquals(I18n.t("input.device.slot", 1, "Xbox"), dialog.deviceLabel("1:Xbox"))
            dialog.selectDevice("1:Xbox")
            assertEquals("1:Xbox", controller.config.selectedGamepad)
            assertEquals("1:Xbox", store.load().selectedGamepad)
            controller.updateDevices(listOf(GamepadDevice(0, "Pad")))
            assertEquals(listOf("auto", "0:Pad", "1:Xbox"), dialog.deviceItems, "pinned-but-unplugged stays selectable")
            assertEquals(I18n.t("input.device.missing", I18n.t("input.device.slot", 1, "Xbox")), dialog.deviceLabel("1:Xbox"))
            controller.handleGamepadFrame(GamepadFrame.DISCONNECTED)
            assertEquals(I18n.t("input.status.selected_missing", dialog.deviceLabel("1:Xbox")), dialog.statusText)
            dialog.selectDevice("auto")
            assertEquals("auto", store.load().selectedGamepad)

            // Live trigger readout tracks current RT and peak RT; threshold slider applies live and persists.
            controller.handleGamepadFrame(GamepadFrame(connected = true, slot = 0, name = "Pad", rt = 0.55))
            assertTrue(triggerLive.text.contains("55%"), triggerLive.text)
            assertTrue(triggerLive.text.contains(I18n.t("input.trigger.stage.half")), triggerLive.text)
            triggerSlider.value = 0.50
            assertEquals(0.50, controller.config.triggerFullThreshold)
            assertEquals(0.50, store.load().triggerFullThreshold)
            assertTrue(triggerLive.text.contains(I18n.t("input.trigger.stage.full")), triggerLive.text)

            // Settings toggles apply live and persist.
            (scene.lookup("#input-bindings-two-stage") as CheckBox).isSelected = false
            assertFalse(controller.config.twoStageTriggerEnabled)
            assertTrue(triggerSlider.isDisable, "single-stage mode disables full-press threshold slider")
            slider.value = 0.4
            assertEquals(0.4, controller.config.stickDeadzone)
            assertEquals(0.4, store.load().stickDeadzone)
            (scene.lookup("#input-bindings-gamepad-enabled") as CheckBox).isSelected = false
            assertEquals(I18n.t("input.status.disabled"), dialog.statusText)
            assertFalse(store.load().gamepadEnabled)

            // Live locale switch re-renders dynamic texts.
            I18n.setLocale(I18n.CODE_ZH_CN)
            assertEquals(I18n.t("input.binding.unbound"), dialog.cellText(CameraInputAction.PLAY, gamepad = false))
            assertEquals(I18n.t("input.status.disabled"), dialog.statusText)
            assertEquals(I18n.t("input.dialog.title"), dialog.stage.title)
            assertEquals(I18n.t("input.button.close"), button("input-bindings-close").text)

            // Close hides (non-destructive) and cancels any capture.
            button("input-binding-kb-UP").fire()
            assertTrue(controller.isCapturing)
            button("input-bindings-close").fire()
            assertFalse(dialog.isShowing)
            assertFalse(controller.isCapturing)
        } finally {
            I18n.resetToDefault()
            dialog.close()
        }
    }

    @Test fun runtimeRoutesBoundKeysAndGamepadIntoCameraStudio() = onFx {
        Platform.setImplicitExit(false)
        I18n.resetToDefault()
        val home = Files.createTempDirectory("input-runtime")
        InputBindingStore(InputBindingStore.defaultPath(home)).save(
            InputBindingConfig.default().withKeyboardBinding(CameraInputAction.MENU_OK, KeyBindingSpec("K")).config,
        )
        val holder = StackPane()
        val owner = Stage().apply { scene = Scene(holder, 400.0, 300.0) }
        val commands = mutableListOf<Pair<String, Boolean>>()
        val studio = CameraStudio(owner, { k, down -> commands += k to down; CompletableFuture.completedFuture(Unit) }, {}, {}, {})
        val backend = FakeGamepadBackend()
        val runtime = InputBindingRuntime.create(
            home = home,
            owner = owner,
            studio = studio,
            readLens = { CompletableFuture.completedFuture(null) },
            patchLens = { CompletableFuture.completedFuture(Unit) },
            onError = { throw it },
            backendFactory = { backend },
        )
        try {
            holder.children += studio.root
            owner.show()
            // Bound key K -> MENU_OK button hold.
            val press = key(KeyEvent.KEY_PRESSED, KeyCode.K)
            javafx.event.Event.fireEvent(owner.scene.root, press)
            javafx.event.Event.fireEvent(owner.scene.root, key(KeyEvent.KEY_RELEASED, KeyCode.K))
            assertEquals(listOf("menu" to true, "menu" to false), commands.toList())
            commands.clear()
            // Unbound key does nothing through the binding layer.
            runtime.handleKeyEvent(key(KeyEvent.KEY_PRESSED, KeyCode.J))
            assertTrue(commands.isEmpty())
            // Text field focus suppresses shortcuts.
            val field = TextField()
            holder.children += field
            field.requestFocus()
            val typed = KeyEvent(field, field, KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, false, false, false)
            runtime.handleKeyEvent(typed)
            assertFalse(typed.isConsumed)
            holder.children -= field

            // Gamepad: two-stage RT drives shutterHalf then shutterFull and releases in reverse.
            fun rt(v: Double) = runtime.controller.handleGamepadFrame(GamepadFrame(connected = true, slot = 0, name = "Pad", rt = v))
            rt(0.4); rt(0.9); rt(0.4); rt(0.0)
            assertEquals(
                listOf("shutterHalf" to true, "shutterFull" to true, "shutterFull" to false, "shutterHalf" to false),
                commands.toList(),
            )
            commands.clear()
            // Hot disconnect while holding RB (AF-ON) releases it.
            runtime.controller.handleGamepadFrame(GamepadFrame(connected = true, slot = 0, name = "Pad", buttons = GamepadFrame.RB))
            runtime.controller.handleGamepadFrame(GamepadFrame.DISCONNECTED)
            assertEquals(listOf("afOn" to true, "afOn" to false), commands.toList())

            val dialog = runtime.showDialog()
            assertTrue(dialog.isShowing)
            assertTrue(runtime.isDialogShowing)
        } finally {
            runtime.close()
            assertTrue(backend.closed)
            studio.close(releaseLatching = false)
            owner.hide()
        }
    }
}
