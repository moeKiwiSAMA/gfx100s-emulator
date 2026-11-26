package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.theme.ThemeRuntimeController
import com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ComboBoxBase
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Owns the whole key-binding pipeline for one [CameraStudio]: persisted config, the [InputBindingController],
 * the native gamepad poller, keyboard routing through [CameraStudio.externalKeyHandler], STEP auto-repeat ticks and
 * the lazily created [InputBindingsDialog]. Create and use on the JavaFX thread.
 */
class InputBindingRuntime internal constructor(
    private val owner: Stage,
    private val studio: CameraStudio,
    val store: InputBindingStore,
    val controller: InputBindingController,
    private val backend: GamepadBackend,
    private val themeController: ThemeRuntimeController?,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InputBindingRuntime::class.java)
    private var poller: GamepadPoller? = null
    private var dialog: InputBindingsDialog? = null
    private var themeHandle: AutoCloseable? = null
    private var closed = false

    private val focusListener = ChangeListener<Boolean> { _, _, focused -> if (!focused) controller.releaseAllKeyboard() }
    private val repeatTimer = object : AnimationTimer() {
        override fun handle(now: Long) {
            if (controller.needsTick) controller.tick()
        }
    }

    val gamepadAvailable: Boolean get() = backend.available
    val isDialogShowing: Boolean get() = dialog?.isShowing == true

    internal fun start() {
        controller.backendName = backend.name
        studio.externalKeyHandler = ::handleKeyEvent
        owner.focusedProperty().addListener(focusListener)
        repeatTimer.start()
        if (backend.available) {
            poller = GamepadPoller(
                backend = backend,
                dispatch = { task -> Platform.runLater { if (!closed) task() } },
                onFrame = controller::handleGamepadFrame,
                selection = { controller.config.gamepadSelection },
                onDevices = controller::updateDevices,
            ).also { it.start() }
        }
        themeHandle = themeController?.addThemeListener { _, ui, _ -> Platform.runLater { dialog?.applyUiTheme(ui) } }
    }

    /** Routes a main-window / EVF-window key event; consumes it when a user binding (or capture) handled it. */
    fun handleKeyEvent(event: KeyEvent) {
        if (closed) return
        val pressed = when (event.eventType) {
            KeyEvent.KEY_PRESSED -> true
            KeyEvent.KEY_RELEASED -> false
            else -> return
        }
        val focusOwner = (event.source as? Scene)?.focusOwner ?: (event.target as? Node)?.scene?.focusOwner
        val textFocused = focusOwner is TextInputControl || (focusOwner is ComboBoxBase<*> && focusOwner.isEditable)
        val consumed = controller.handleKey(
            event.code.name,
            pressed,
            ctrl = event.isControlDown,
            alt = event.isAltDown,
            shift = event.isShiftDown,
            meta = event.isMetaDown,
            textInputFocused = textFocused,
        )
        if (consumed) event.consume()
    }

    fun showDialog(): InputBindingsDialog {
        val existing = dialog
        val target = if (existing != null) {
            existing
        } else {
            InputBindingsDialog(
                owner = owner,
                controller = controller,
                persist = store::save,
                gamepadAvailable = { backend.available },
                initialTheme = themeController?.uiTheme ?: UiThemeRegistry.origin(),
            ).also { dialog = it }
        }
        target.setVisible(true)
        return target
    }

    fun toggleDialog() {
        if (isDialogShowing) dialog?.setVisible(false) else showDialog()
    }

    fun applyUiTheme(theme: UiTheme) {
        dialog?.applyUiTheme(theme)
    }

    override fun close() {
        if (closed) return
        // The poller owns the backend once started and closes it; otherwise close the (idle) backend directly.
        val activePoller = poller
        runCatching { activePoller?.close() ?: backend.close() }.onFailure { log.warn("Gamepad backend close failed", it) }
        poller = null
        closed = true
        runCatching { controller.cancelCapture() }
        runCatching { controller.releaseAll() }
        repeatTimer.stop()
        owner.focusedProperty().removeListener(focusListener)
        if (studio.externalKeyHandler != null) studio.externalKeyHandler = null
        runCatching { themeHandle?.close() }
        themeHandle = null
        runCatching { dialog?.close() }
        dialog = null
    }

    companion object {
        /**
         * Loads `<home>/input-bindings.yaml` (defaults: empty keyboard map, populated gamepad profile), loads the
         * optional native gamepad bridge (graceful fallback when absent) and wires everything into [studio].
         */
        fun create(
            home: Path,
            owner: Stage,
            studio: CameraStudio,
            readLens: () -> CompletableFuture<LensProfileState?>,
            patchLens: (Map<String, Any>) -> CompletableFuture<*>,
            onError: (Throwable) -> Unit,
            themeController: ThemeRuntimeController? = null,
            backendFactory: () -> GamepadBackend = { NativeGamepadBridge.tryLoad(home) },
        ): InputBindingRuntime {
            val store = InputBindingStore(InputBindingStore.defaultPath(home))
            val controller = InputBindingController(
                store.load(),
                CameraInputDispatcher(studio, LensBindingStepper(readLens, patchLens, onError)),
            )
            val backend = runCatching(backendFactory).getOrElse {
                LoggerFactory.getLogger(InputBindingRuntime::class.java)
                    .warn("[FALLBACK][gamepad] Gamepad backend failed to load: {}", it.message ?: it.javaClass.simpleName)
                UnavailableGamepadBackend
            }
            return InputBindingRuntime(owner, studio, store, controller, backend, themeController).also { it.start() }
        }
    }
}
