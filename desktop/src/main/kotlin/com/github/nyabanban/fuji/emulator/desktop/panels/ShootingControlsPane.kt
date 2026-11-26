package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.util.concurrent.CompletableFuture

/**
 * Pure immutable snapshot of the primary physical shooting controls
 * (`STILL / MOVIE`, `AF-S / AF-C / MF`, `MODE DIAL`, and rear LCD touch readiness).
 */
data class ShootingControlsState(
    val movieMode: Boolean = false,
    val focusMode: String = "AF-S",
    val modeDial: String = "default",
    val touchEnabled: Boolean = false,
) {
    val shootingLabel: String get() = if (movieMode) "MOVIE" else "STILL"
    val displayModeDial: String get() = if (modeDial == "default") "P" else modeDial

    fun summaryBadge(): String = "$shootingLabel · $displayModeDial · $focusMode"

    companion object {
        val FOCUS_MODES: List<String> = listOf("AF-S", "AF-C", "MF")
        val MODE_DIAL_SLOTS: List<String> = listOf("P", "S", "A", "M", "C1", "C2", "C3", "C4", "C5", "C6")

        fun fromSnapshot(snapshot: Map<String, Any?>): ShootingControlsState {
            @Suppress("UNCHECKED_CAST")
            val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }

            val buttons = components["buttons"]
            val btnState = buttons?.get("state") as? Map<*, *>
            val btnConfig = buttons?.get("config") as? Map<*, *>
            val movie = (btnState?.get("movieMode") as? Boolean)
                ?: (btnConfig?.get("movieMode") as? Boolean)
                ?: false

            val gpio = components["soc-gpio"]
            val gpioState = gpio?.get("state") as? Map<*, *>
            val gpioConfig = gpio?.get("config") as? Map<*, *>
            val effectiveFocus = (gpioState?.get("effectiveFocusMode") as? String)
                ?.takeIf { it in FOCUS_MODES }
                ?: (gpioState?.get("focusMode") as? String)?.takeIf { it in FOCUS_MODES }
                ?: (gpioConfig?.get("focusMode") as? String)?.takeIf { it in FOCUS_MODES }
                ?: run {
                    val input7 = ((gpioState?.get("input7") as? Number) ?: (gpioConfig?.get("input7") as? Number))?.toLong()
                    if (input7 != null) {
                        val sHigh = ((input7 ushr 13) and 1L) != 0L
                        val cHigh = ((input7 ushr 14) and 1L) != 0L
                        when {
                            !sHigh -> "MF"
                            cHigh -> "AF-C"
                            else -> "AF-S"
                        }
                    } else {
                        "AF-S"
                    }
                }

            val adc = components["soc-adc"]
            val adcState = adc?.get("state") as? Map<*, *>
            val adcConfig = adc?.get("config") as? Map<*, *>
            val dial = (adcState?.get("modeDial") as? String)
                ?: (adcConfig?.get("modeDial") as? String)
                ?: "default"

            val rearTouch = components["rear-touch-ic"]
            val rearTouchEnabled = ((rearTouch?.get("state") as? Map<*, *>)?.get("enabled") as? Boolean)
                ?: ((rearTouch?.get("config") as? Map<*, *>)?.get("enabled") as? Boolean)
                ?: false
            val touchSource = if (rearTouchEnabled) rearTouch else components["i2c-bus"]
            val touchReady = ((touchSource?.get("state") as? Map<*, *>)?.get("touchEnabled") as? Boolean)
                ?: ((touchSource?.get("config") as? Map<*, *>)?.get("touchEnabled") as? Boolean)
                ?: false

            return ShootingControlsState(
                movieMode = movie,
                focusMode = effectiveFocus,
                modeDial = dial,
                touchEnabled = touchReady,
            )
        }
    }
}

/**
 * Tactile camera-native primary shooting switcher card for `CameraStudio`:
 * - **STILL / MOVIE** 2-way switch (`buttons.movieMode` -> `GPIO(7,5)`)
 * - **AF-S / AF-C / MF** 3-way focus lever (`soc-gpio.focusMode` -> `GPIO(7,13)+GPIO(7,14)`)
 * - **MODE DIAL** 10-slot tactile picker (`P | S | A | M | C1..C6`, `soc-adc.modeDial` -> `ADC CH11`)
 * - Rear LCD touch-to-focus contextual hint + pluggable [accessorySlot] (for compact ND filter strip, etc.).
 */
class ShootingControlsPane(
    private val onSelectMovieMode: (Boolean) -> Unit,
    private val onSelectFocusMode: (String) -> CompletableFuture<*>,
    private val onSelectModeDial: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val isMovieModePending: () -> Boolean = { false },
    private val isModeDialPending: () -> Boolean = { false },
    private val onFocusModeChanged: (String) -> Unit = {},
) {
    val root: VBox = VBox(7.0).apply {
        id = "shooting-controls-card"
        styleClass += "studio-card"
    }

    /** Optional plug-in container for tactile command dials directly below Mode Dial. */
    val commandDialsSlot: VBox = VBox(5.0).apply {
        id = "shooting-controls-dials-slot"
        isManaged = false
        isVisible = false
    }

    /** Optional plug-in container for compact shooting accessories (e.g. ND filter strip). */
    val accessorySlot: VBox = VBox(6.0).apply {
        id = "shooting-controls-accessory-slot"
        isManaged = false
        isVisible = false
    }

    private var syncing = false
    private var focusPending = false
    private var focusVersion = 0L
    private var desiredMovieMode: Boolean = false
    private var desiredFocusMode: String = "AF-S"
    private var desiredModeDial: String = "default"
    private var latestState: ShootingControlsState = ShootingControlsState()

    private val summaryBadge = Label("STILL · P · AF-S").apply {
        id = "shooting-controls-badge"
        styleClass += "studio-brand-badge"
    }

    private val stillBtn = segButton("controls.mode.still", "shooting-mode-still", "controls.still.tooltip")
    private val movieBtn = segButton("controls.mode.movie", "shooting-mode-movie", "controls.movie.tooltip")

    private val focusButtons: Map<String, ToggleButton> = linkedMapOf(
        "AF-S" to segButton("controls.focus.afs", "shooting-focus-afs", "controls.focus.afs.tooltip"),
        "AF-C" to segButton("controls.focus.afc", "shooting-focus-afc", "controls.focus.afc.tooltip"),
        "MF" to segButton("controls.focus.mf", "shooting-focus-mf", "controls.focus.mf.tooltip"),
    )

    private val dialButtons: Map<String, ToggleButton> = ShootingControlsState.MODE_DIAL_SLOTS.associateWith { slot ->
        ToggleButton(slot).apply {
            id = "shooting-dial-$slot"
            styleClass += "studio-seg-btn"
            maxWidth = Double.MAX_VALUE
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("controls.dial.slot.tooltip", slot, slot)) }
        }
    }

    private val touchHintLabel = Label().apply {
        id = "shooting-touch-hint"
        styleClass += "studio-hint-text"
        isWrapText = true
    }

    init {
        buildLayout()
        wireActions()
        applyState(latestState)
        I18n.onLocaleChange {
            val refresh = { updateTouchHint() }
            if (Platform.isFxApplicationThread()) refresh() else Platform.runLater(refresh)
        }
    }

    fun attachCommandDials(node: Node) {
        commandDialsSlot.children.setAll(node)
        commandDialsSlot.isManaged = true
        commandDialsSlot.isVisible = true
    }

    fun attachAccessory(node: Node) {
        accessorySlot.children.setAll(node)
        accessorySlot.isManaged = true
        accessorySlot.isVisible = true
    }

    fun currentFocusMode(): String = desiredFocusMode

    private fun segButton(textKey: String, nodeId: String, tipKey: String): ToggleButton = ToggleButton().apply {
        id = nodeId
        styleClass += "studio-seg-btn"
        maxWidth = Double.MAX_VALUE
        textProperty().bind(I18n.bind(textKey))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind(tipKey)) }
    }

    private fun buildLayout() {
        val header = HBox(6.0,
            Label().apply { styleClass += "studio-section-title"; textProperty().bind(I18n.bind("controls.header.title")) },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            summaryBadge,
        ).apply { alignment = Pos.CENTER_LEFT }

        val stillMovieRow = HBox(4.0, stillBtn, movieBtn).apply {
            HBox.setHgrow(stillBtn, Priority.ALWAYS)
            HBox.setHgrow(movieBtn, Priority.ALWAYS)
        }
        val focusRow = HBox(4.0, *focusButtons.values.toTypedArray()).apply {
            focusButtons.values.forEach { HBox.setHgrow(it, Priority.ALWAYS) }
        }

        val topSwitchGrid = GridPane().apply {
            hgap = 8.0
            vgap = 4.0
            columnConstraints.addAll(
                ColumnConstraints().apply { percentWidth = 44.0 },
                ColumnConstraints().apply { percentWidth = 56.0 },
            )
            add(Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("controls.still_movie.label")) }, 0, 0)
            add(Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("controls.focus_lever.label")) }, 1, 0)
            add(stillMovieRow, 0, 1)
            add(focusRow, 1, 1)
        }

        val dialGrid = GridPane().apply {
            hgap = 4.0
            vgap = 4.0
            repeat(5) {
                columnConstraints.add(ColumnConstraints().apply { percentWidth = 20.0 })
            }
            ShootingControlsState.MODE_DIAL_SLOTS.forEachIndexed { idx, slot ->
                add(dialButtons.getValue(slot), idx % 5, idx / 5)
            }
        }

        val dialSection = VBox(4.0,
            Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("controls.mode_dial.title")) },
            dialGrid,
        )

        root.children.addAll(header, topSwitchGrid, dialSection, commandDialsSlot, touchHintLabel, accessorySlot)
    }

    private fun wireActions() {
        stillBtn.setOnAction {
            if (syncing) return@setOnAction
            desiredMovieMode = false
            syncMovieSelection(false)
            onSelectMovieMode(false)
            onStatus(I18n.t("controls.status.shooting_mode", "STILL"))
        }
        movieBtn.setOnAction {
            if (syncing) return@setOnAction
            desiredMovieMode = true
            syncMovieSelection(true)
            onSelectMovieMode(true)
            onStatus(I18n.t("controls.status.shooting_mode", "MOVIE"))
        }
        focusButtons.forEach { (mode, btn) ->
            btn.setOnAction {
                if (syncing) return@setOnAction
                selectFocusMode(mode)
            }
        }
        dialButtons.forEach { (slot, btn) ->
            btn.setOnAction {
                if (syncing) return@setOnAction
                desiredModeDial = slot
                syncDialSelection(slot)
                onSelectModeDial(slot)
                onStatus(I18n.t("controls.status.mode_dial", slot))
            }
        }
    }

    fun selectFocusMode(mode: String) {
        if (mode !in ShootingControlsState.FOCUS_MODES) return
        val previous = desiredFocusMode
        desiredFocusMode = mode
        syncFocusSelection(mode)
        onFocusModeChanged(mode)
        focusPending = true
        val version = ++focusVersion
        runCatching { onSelectFocusMode(mode) }
            .getOrElse { CompletableFuture.failedFuture<Any>(it) }
            .whenComplete { _, err ->
                val done = {
                    if (focusVersion == version) {
                        focusPending = false
                        if (err != null) {
                            desiredFocusMode = previous
                            syncFocusSelection(previous)
                            onFocusModeChanged(previous)
                        } else {
                            onStatus(I18n.t("controls.status.focus_mode", mode))
                        }
                    }
                    if (err != null) onError(err)
                }
                if (Platform.isFxApplicationThread()) done() else Platform.runLater(done)
            }
    }

    fun stepFocusMode(delta: Int) {
        if (delta == 0) return
        val idx = ShootingControlsState.FOCUS_MODES.indexOf(desiredFocusMode).coerceAtLeast(0)
        val nextIdx = Math.floorMod(idx + delta, ShootingControlsState.FOCUS_MODES.size)
        selectFocusMode(ShootingControlsState.FOCUS_MODES[nextIdx])
    }

    fun observeSnapshot(snapshot: Map<String, Any?>) {
        val next = ShootingControlsState.fromSnapshot(snapshot)
        applyState(next)
    }

    fun syncModeDialFromStudio(mode: String) {
        desiredModeDial = mode
        syncDialSelection(mode)
    }

    fun syncMovieModeFromStudio(movie: Boolean) {
        desiredMovieMode = movie
        syncMovieSelection(movie)
    }

    private fun applyState(state: ShootingControlsState) {
        val effectiveMovie = if (isMovieModePending()) desiredMovieMode else state.movieMode.also { desiredMovieMode = it }
        val effectiveFocus = if (focusPending) desiredFocusMode else state.focusMode.also { desiredFocusMode = it }
        val effectiveDial = if (isModeDialPending()) desiredModeDial else state.modeDial.also { desiredModeDial = it }
        val effective = state.copy(
            movieMode = effectiveMovie,
            focusMode = effectiveFocus,
            modeDial = effectiveDial,
        )
        latestState = effective
        syncing = true
        try {
            stillBtn.isSelected = !effectiveMovie
            movieBtn.isSelected = effectiveMovie
            focusButtons.forEach { (m, btn) -> btn.isSelected = (m == effectiveFocus) }
            val activeDial = effective.displayModeDial
            dialButtons.forEach { (slot, btn) -> btn.isSelected = (slot == activeDial) }
            summaryBadge.text = effective.summaryBadge()
            updateTouchHint()
        } finally {
            syncing = false
        }
        onFocusModeChanged(effectiveFocus)
    }

    private fun updateTouchHint() {
        touchHintLabel.text = I18n.t(if (latestState.touchEnabled) "controls.touch.ready" else "controls.touch.disabled")
    }

    private fun syncMovieSelection(movie: Boolean) {
        syncing = true
        try {
            stillBtn.isSelected = !movie
            movieBtn.isSelected = movie
            latestState = latestState.copy(movieMode = movie)
            summaryBadge.text = latestState.summaryBadge()
        } finally {
            syncing = false
        }
    }

    private fun syncFocusSelection(mode: String) {
        syncing = true
        try {
            focusButtons.forEach { (m, btn) -> btn.isSelected = (m == mode) }
            latestState = latestState.copy(focusMode = mode)
            summaryBadge.text = latestState.summaryBadge()
        } finally {
            syncing = false
        }
    }

    private fun syncDialSelection(mode: String) {
        val active = if (mode == "default") "P" else mode
        syncing = true
        try {
            dialButtons.forEach { (slot, btn) -> btn.isSelected = (slot == active) }
            latestState = latestState.copy(modeDial = mode)
            summaryBadge.text = latestState.summaryBadge()
        } finally {
            syncing = false
        }
    }
}
