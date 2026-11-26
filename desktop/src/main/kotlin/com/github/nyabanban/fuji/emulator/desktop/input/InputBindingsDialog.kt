package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioTypography
import com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import javafx.beans.value.ChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.Slider
import javafx.scene.control.Tooltip
import javafx.util.StringConverter
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Non-blocking themed window for editing keyboard shortcuts and gamepad bindings.
 *
 * Clicking a keyboard or gamepad cell starts a live capture: the next key combination or gamepad input is bound to
 * that row (an input already owned by another action is moved and the old row is flagged), `Esc` cancels.
 * Every change is applied to the live [controller] immediately and persisted through [persist].
 */
class InputBindingsDialog(
    owner: Stage?,
    private val controller: InputBindingController,
    private val persist: (InputBindingConfig) -> Unit,
    private val gamepadAvailable: () -> Boolean,
    initialTheme: UiTheme = UiThemeRegistry.origin(),
) : AutoCloseable {
    val stage = Stage()

    private data class Row(
        val action: CameraInputAction,
        val keyboard: Button,
        val gamepad: Button,
        val keyboardClear: Button,
        val gamepadClear: Button,
    )

    private enum class Device { KEYBOARD, GAMEPAD }

    private val rows = LinkedHashMap<CameraInputAction, Row>()
    private val statusLabel = Label().apply {
        id = "input-bindings-status"
        styleClass += "input-binding-status"
        isWrapText = true
        maxWidth = Double.MAX_VALUE
    }
    private val gamepadEnabled = CheckBox().apply {
        id = "input-bindings-gamepad-enabled"
        textProperty().bind(I18n.bind("input.settings.gamepad_enabled"))
    }
    /** Controller selector: `auto` plus every connected (or pinned-but-absent) device, as encoded selections. */
    private val deviceSelector = ComboBox<String>().apply {
        id = "input-bindings-gamepad-device"
        styleClass += "input-binding-device"
        minWidth = 0.0
        prefWidth = 220.0
        maxWidth = Double.MAX_VALUE
        HBox.setHgrow(this, Priority.SOMETIMES)
        converter = object : StringConverter<String>() {
            override fun toString(value: String?): String = deviceLabel(value)
            override fun fromString(text: String?): String? = null
        }
        buttonCell = deviceCell()
        setCellFactory { deviceCell() }
    }
    private val deadzone = Slider(InputBindingConfig.MIN_STICK_DEADZONE, InputBindingConfig.MAX_STICK_DEADZONE, InputBindingConfig.DEFAULT_STICK_DEADZONE).apply {
        id = "input-bindings-deadzone"
        majorTickUnit = 0.10
        minorTickCount = 1
        isShowTickMarks = true
        blockIncrement = 0.01
        HBox.setHgrow(this, Priority.ALWAYS)
        maxWidth = Double.MAX_VALUE
    }
    private val deadzoneValue = Label().apply { styleClass += "studio-hint-text"; minWidth = 40.0 }
    private val twoStage = CheckBox().apply {
        id = "input-bindings-two-stage"
        textProperty().bind(I18n.bind("input.settings.two_stage"))
    }
    private val messageLabel = Label().apply {
        id = "input-bindings-message"
        styleClass += "input-binding-message"
        isWrapText = true
        maxWidth = Double.MAX_VALUE
    }
    private var messageKey: String? = null
    private var messageArgs: () -> Array<Any?> = { emptyArray() }

    private var capturing: Pair<CameraInputAction, Device>? = null
    private var conflictAction: CameraInputAction? = null
    private var syncing = false
    private var closed = false

    private val statusHandle: AutoCloseable
    private val localeListener: ChangeListener<Locale>

    val isShowing: Boolean get() = stage.isShowing
    val isCapturing: Boolean get() = capturing != null

    init {
        if (owner != null) stage.initOwner(owner)
        stage.titleProperty().bind(I18n.bind("input.dialog.title"))
        stage.minWidth = 520.0
        stage.minHeight = 480.0

        gamepadEnabled.selectedProperty().addListener { _, _, on ->
            if (!syncing) apply(controller.config.copy(gamepadEnabled = on))
        }
        twoStage.selectedProperty().addListener { _, _, on ->
            if (!syncing) apply(controller.config.copy(twoStageTriggerEnabled = on))
        }
        deviceSelector.valueProperty().addListener { _, _, value ->
            if (syncing || value == null || value == controller.config.selectedGamepad) return@addListener
            apply(controller.config.copy(selectedGamepad = value))
            setMessage("input.message.device_selected") { arrayOf(deviceLabel(value)) }
        }
        deadzone.valueProperty().addListener { _, _, value ->
            if (syncing) return@addListener
            val rounded = (value.toDouble() * 100.0).roundToInt() / 100.0
            deadzoneValue.text = String.format(Locale.ROOT, "%.2f", rounded)
            if (rounded != controller.config.stickDeadzone) {
                apply(controller.config.copy(stickDeadzone = rounded), persistNow = !deadzone.isValueChanging)
            }
        }
        deadzone.valueChangingProperty().addListener { _, _, changing ->
            if (!changing && !syncing) savePersisted(controller.config)
        }

        val settings = VBox(
            6.0,
            HBox(12.0, gamepadEnabled, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Label().apply { textProperty().bind(I18n.bind("input.settings.device")) }, deviceSelector).apply {
                alignment = Pos.CENTER_LEFT
            },
            HBox(8.0, Label().apply { textProperty().bind(I18n.bind("input.settings.deadzone")) }, deadzone, deadzoneValue).apply {
                alignment = Pos.CENTER_LEFT
            },
            twoStage,
            Label().apply {
                id = "input-bindings-hint"
                styleClass += "studio-hint-text"
                isWrapText = true
                textProperty().bind(I18n.bind("input.hint"))
            },
        ).apply { styleClass += "studio-card"; padding = Insets(8.0) }

        val table = buildTable()
        val scroll = ScrollPane(table).apply {
            isFitToWidth = true
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val clearKeyboard = Button().apply {
            id = "input-bindings-clear-keyboard"
            styleClass += "studio-header-btn-secondary"
            textProperty().bind(I18n.bind("input.button.clear_keyboard"))
            setOnAction { clearAllKeyboard() }
        }
        val resetGamepad = Button().apply {
            id = "input-bindings-reset-gamepad"
            styleClass += "studio-header-btn-secondary"
            textProperty().bind(I18n.bind("input.button.reset_gamepad"))
            setOnAction { resetGamepadDefaults() }
        }
        val closeButton = Button().apply {
            id = "input-bindings-close"
            styleClass += "studio-header-btn-secondary"
            textProperty().bind(I18n.bind("input.button.close"))
            setOnAction { this@InputBindingsDialog.setVisible(false) }
        }
        val footer = HBox(8.0, clearKeyboard, resetGamepad, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, closeButton).apply {
            alignment = Pos.CENTER_LEFT
        }

        val content = VBox(8.0, statusLabel, settings, scroll, messageLabel, footer).apply { padding = Insets(10.0) }
        val shell = BorderPane(content).apply {
            styleClass += "studio-shell"
            styleClass += "studio-sidebar"
            style = "-fx-font-family: '${StudioTypography.family}';"
        }
        stage.scene = Scene(shell, 640.0, 720.0).also { scene ->
            UiThemeRegistry.applyToScene(scene, initialTheme)
            // While capturing, every key goes to the capture (so Space / Enter / Esc never trigger dialog buttons).
            scene.addEventFilter(KeyEvent.ANY) { event ->
                if (!controller.isCapturing || capturing == null) return@addEventFilter
                when (event.eventType) {
                    KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED -> controller.handleKey(
                        event.code.name,
                        event.eventType == KeyEvent.KEY_PRESSED,
                        ctrl = event.isControlDown,
                        alt = event.isAltDown,
                        shift = event.isShiftDown,
                        meta = event.isMetaDown,
                    )
                }
                event.consume()
            }
        }
        stage.setOnCloseRequest { event ->
            event.consume()
            setVisible(false)
        }
        stage.setOnHidden { cancelCapture() }

        statusHandle = controller.addStatusListener { refreshStatus() }
        localeListener = I18n.onLocaleChange { refreshAll() }
        refreshAll()
    }

    private fun buildTable(): GridPane = GridPane().apply {
        styleClass += "input-binding-table"
        hgap = 6.0
        vgap = 4.0
        padding = Insets(4.0)
        columnConstraints.addAll(
            ColumnConstraints().apply { hgrow = Priority.ALWAYS; minWidth = 124.0; prefWidth = 160.0 },
            ColumnConstraints().apply { minWidth = 112.0; prefWidth = 170.0 },
            ColumnConstraints().apply { minWidth = 24.0 },
            ColumnConstraints().apply { minWidth = 112.0; prefWidth = 170.0 },
            ColumnConstraints().apply { minWidth = 24.0 },
        )
        var r = 0
        fun head(key: String) = Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind(key)) }
        add(head("input.table.action"), 0, r)
        add(head("input.table.keyboard"), 1, r, 2, 1)
        add(head("input.table.gamepad"), 3, r, 2, 1)
        r++
        InputActionCategory.entries.forEach { category ->
            val actions = CameraInputAction.entries.filter { it.category == category }
            if (actions.isEmpty()) return@forEach
            add(Label().apply {
                styleClass += "studio-section-title"
                textProperty().bind(I18n.bind(category.i18nKey))
                padding = Insets(6.0, 0.0, 0.0, 0.0)
            }, 0, r, 5, 1)
            r++
            actions.forEach { action ->
                val row = Row(
                    action = action,
                    keyboard = cell("input-binding-kb-${action.name}") { startCapture(action, Device.KEYBOARD) },
                    gamepad = cell("input-binding-gp-${action.name}") { startCapture(action, Device.GAMEPAD) },
                    keyboardClear = clearButton("input-binding-kb-clear-${action.name}") { clearKeyboard(action) },
                    gamepadClear = clearButton("input-binding-gp-clear-${action.name}") { clearGamepad(action) },
                )
                rows[action] = row
                add(Label().apply { textProperty().bind(I18n.bind(action.i18nKey)); isWrapText = true }, 0, r)
                add(row.keyboard, 1, r)
                add(row.keyboardClear, 2, r)
                add(row.gamepad, 3, r)
                add(row.gamepadClear, 4, r)
                r++
            }
        }
    }

    private fun cell(cellId: String, onClick: () -> Unit) = Button().apply {
        id = cellId
        styleClass += "input-binding-cell"
        maxWidth = Double.MAX_VALUE
        isFocusTraversable = false
        setOnAction { onClick() }
    }

    private fun clearButton(buttonId: String, onClick: () -> Unit) = Button("\u00D7").apply {
        id = buttonId
        styleClass += "input-binding-clear"
        isFocusTraversable = false
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("input.binding.clear.tooltip")) }
        setOnAction { onClick() }
    }

    // ---------------------------------------------------------------- actions

    private fun startCapture(action: CameraInputAction, device: Device) {
        if (closed) return
        conflictAction = null
        controller.beginCapture { captured -> onCaptured(action, captured) }
        capturing = action to device
        refreshRows()
    }

    /** Test / keyboard entry point: begins capturing for the [action]'s keyboard (`gamepad = false`) or pad cell. */
    fun startCapture(action: CameraInputAction, gamepad: Boolean) =
        startCapture(action, if (gamepad) Device.GAMEPAD else Device.KEYBOARD)

    fun cancelCapture() {
        if (controller.isCapturing) controller.cancelCapture()
        if (capturing != null) {
            capturing = null
            refreshRows()
        }
    }

    private fun onCaptured(action: CameraInputAction, captured: CapturedInput?) {
        capturing = null
        when (captured) {
            null -> setMessage("input.message.cancelled")
            is CapturedInput.Key -> {
                val rebind = controller.config.withKeyboardBinding(action, captured.spec)
                announceRebind(action, captured.spec.display(), rebind.displaced)
                apply(rebind.config)
            }
            is CapturedInput.Pad -> {
                val rebind = controller.config.withGamepadBinding(action, captured.spec)
                announceRebind(action, captured.spec.display(), rebind.displaced)
                apply(rebind.config)
            }
        }
        refreshRows()
    }

    private fun announceRebind(action: CameraInputAction, inputText: String, displaced: CameraInputAction?) {
        conflictAction = displaced
        if (displaced == null) {
            setMessage("input.message.captured") { arrayOf(inputText, I18n.t(action.i18nKey)) }
        } else {
            setMessage("input.message.moved") { arrayOf(inputText, I18n.t(displaced.i18nKey), I18n.t(action.i18nKey)) }
        }
    }

    fun clearKeyboard(action: CameraInputAction) {
        cancelCapture()
        conflictAction = null
        apply(controller.config.clearKeyboard(action))
        setMessage("input.message.cleared") { arrayOf(I18n.t(action.i18nKey)) }
    }

    fun clearGamepad(action: CameraInputAction) {
        cancelCapture()
        conflictAction = null
        apply(controller.config.clearGamepad(action))
        setMessage("input.message.cleared") { arrayOf(I18n.t(action.i18nKey)) }
    }

    fun clearAllKeyboard() {
        cancelCapture()
        conflictAction = null
        apply(controller.config.clearAllKeyboard())
        setMessage("input.message.cleared_keyboard")
    }

    fun resetGamepadDefaults() {
        cancelCapture()
        conflictAction = null
        apply(controller.config.resetGamepadDefaults())
        setMessage("input.message.reset_gamepad")
    }

    private fun apply(next: InputBindingConfig, persistNow: Boolean = true) {
        controller.updateConfig(next)
        if (persistNow) savePersisted(next)
        refreshAll()
    }

    private fun savePersisted(config: InputBindingConfig) {
        try {
            persist(config)
        } catch (error: Exception) {
            val reason = error.message ?: error.javaClass.simpleName
            setMessage("input.message.save_failed") { arrayOf(reason) }
        }
    }

    private fun setMessage(key: String, args: () -> Array<Any?> = { emptyArray() }) {
        messageKey = key
        messageArgs = args
        refreshMessage()
    }

    // ---------------------------------------------------------------- rendering

    private fun refreshAll() {
        if (closed) return
        syncing = true
        try {
            val config = controller.config
            gamepadEnabled.isSelected = config.gamepadEnabled
            twoStage.isSelected = config.twoStageTriggerEnabled
            if (!deadzone.isValueChanging) deadzone.value = config.stickDeadzone
            deadzoneValue.text = String.format(Locale.ROOT, "%.2f", config.stickDeadzone)
            deadzone.isDisable = !config.gamepadEnabled
            twoStage.isDisable = !config.gamepadEnabled
            deviceSelector.isDisable = !config.gamepadEnabled || !gamepadAvailable()
        } finally {
            syncing = false
        }
        refreshDevices()
        refreshStatus()
        refreshRows()
        refreshMessage()
    }

    private fun refreshStatus() {
        if (closed) return
        val status = controller.status
        val connected = gamepadAvailable() && controller.config.gamepadEnabled && status.connected
        val selection = controller.config.gamepadSelection
        statusLabel.text = when {
            !gamepadAvailable() -> I18n.t("input.status.unavailable", NativeGamepadBridge.libraryFileName())
            !controller.config.gamepadEnabled -> I18n.t("input.status.disabled")
            status.connected -> I18n.t("input.status.connected", status.name.ifBlank { status.backend }, status.slot + 1)
            selection != GamepadSelection.Auto ->
                I18n.t("input.status.selected_missing", deviceLabel(selection.encode()))
            else -> I18n.t("input.status.none")
        }
        statusLabel.styleClass.removeAll("input-binding-connected", "input-binding-disconnected")
        statusLabel.styleClass += if (connected) "input-binding-connected" else "input-binding-disconnected"
        refreshDevices()
    }

    /** Rebuilds the selector from the live device list (hot-plug) while keeping the persisted selection visible. */
    private fun refreshDevices() {
        if (closed) return
        val selected = controller.config.selectedGamepad
        val items = buildList {
            add(GamepadSelection.AUTO)
            controller.status.devices.forEach { add(it.selectionId()) }
            if (selected !in this) add(selected)
        }
        val wasSyncing = syncing
        syncing = true
        try {
            if (deviceSelector.items != items) deviceSelector.items.setAll(items)
            if (deviceSelector.value != selected) deviceSelector.value = selected
            // Labels depend on locale and connection state: force the cells to re-render.
            deviceSelector.buttonCell = deviceCell()
        } finally {
            syncing = wasSyncing
        }
    }

    private fun deviceCell() = object : ListCell<String>() {
        override fun updateItem(item: String?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else deviceLabel(item)
        }
    }

    /** Localized label for an encoded [GamepadSelection]. */
    fun deviceLabel(encoded: String?): String {
        val selection = encoded?.let { runCatching { GamepadSelection.parse(it) }.getOrNull() } ?: return ""
        val devices = controller.status.devices
        return when (selection) {
            GamepadSelection.Auto -> I18n.t("input.device.auto")
            is GamepadSelection.Slot -> {
                val present = devices.firstOrNull { it.slot == selection.slot }
                if (present != null) I18n.t("input.device.slot", selection.slot, present.name)
                else I18n.t("input.device.missing", I18n.t("input.device.slot_any", selection.slot))
            }
            is GamepadSelection.Device -> {
                val label = I18n.t("input.device.slot", selection.slot, selection.name)
                if (devices.any { it.name == selection.name }) label else I18n.t("input.device.missing", label)
            }
        }
    }

    /** Test hook: currently offered selector entries (encoded selections). */
    val deviceItems: List<String> get() = deviceSelector.items.toList()

    /** Test / automation hook: picks a selector entry exactly as the user would. */
    fun selectDevice(encoded: String) {
        deviceSelector.value = encoded
    }

    private fun refreshRows() {
        if (closed) return
        val config = controller.config
        val prompt = I18n.t("input.binding.capture_prompt")
        val unbound = I18n.t("input.binding.unbound")
        rows.values.forEach { row ->
            val keys = config.keyboardBindings[row.action].orEmpty()
            val pads = config.gamepadBindings[row.action].orEmpty()
            val capture = capturing?.takeIf { it.first == row.action }
            render(row.keyboard, if (keys.isEmpty()) null else keys.joinToString(", ") { it.display() }, capture?.second == Device.KEYBOARD, prompt, unbound)
            render(row.gamepad, if (pads.isEmpty()) null else pads.joinToString(" / ") { it.display() }, capture?.second == Device.GAMEPAD, prompt, unbound)
            row.keyboardClear.isDisable = keys.isEmpty()
            row.gamepadClear.isDisable = pads.isEmpty()
            val conflict = row.action == conflictAction
            listOf(row.keyboard, row.gamepad).forEach { button ->
                button.styleClass.remove("input-binding-conflict")
                if (conflict) button.styleClass += "input-binding-conflict"
            }
        }
    }

    private fun render(button: Button, bound: String?, capturingHere: Boolean, prompt: String, unbound: String) {
        button.styleClass.removeAll("input-binding-unbound", "input-binding-capturing")
        when {
            capturingHere -> {
                button.text = prompt
                button.styleClass += "input-binding-capturing"
            }
            bound == null -> {
                button.text = unbound
                button.styleClass += "input-binding-unbound"
            }
            else -> button.text = bound
        }
    }

    private fun refreshMessage() {
        val key = messageKey
        messageLabel.text = if (key == null) "" else I18n.t(key, *messageArgs())
    }

    /** Row cell text, for tests and accessibility tooling. */
    fun cellText(action: CameraInputAction, gamepad: Boolean): String =
        rows.getValue(action).let { if (gamepad) it.gamepad.text else it.keyboard.text }

    val statusText: String get() = statusLabel.text
    val messageText: String get() = messageLabel.text

    fun applyUiTheme(theme: UiTheme) {
        if (closed) return
        stage.scene?.let { UiThemeRegistry.applyToScene(it, theme) }
    }

    fun setVisible(visible: Boolean) {
        if (closed) return
        if (visible) {
            refreshAll()
            if (!stage.isShowing) stage.show()
            stage.toFront()
        } else {
            cancelCapture()
            if (stage.isShowing) stage.hide()
        }
    }

    override fun close() {
        if (closed) return
        cancelCapture()
        closed = true
        runCatching { statusHandle.close() }
        I18n.localeProperty.removeListener(localeListener)
        stage.close()
    }
}
