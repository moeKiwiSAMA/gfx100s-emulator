package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.animation.FadeTransition
import javafx.animation.PauseTransition
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.image.ImageView
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Duration

/**
 * Active camera viewfinder channel derived from observed `LCDC0` (EVF), `LCDC1` (Rear LCD),
 * and `I2C1 @ 0x39` eye-sensor proximity state.
 */
enum class ActiveViewfinder(val labelKey: String) {
    EVF("evf.active.evf"),
    LCD("evf.active.lcd"),
    BOTH("evf.active.both"),
    WAITING("evf.active.waiting"),
    ;

    val displayLabel: String get() = I18n.t(labelKey)
    val displayLabelZh: String get() = displayLabel
}

/**
 * Immutable snapshot of EVF/LCD viewfinder activity for UI badges and Control API diagnostics.
 */
data class ViewfinderStatus(
    val active: ActiveViewfinder,
    val eyeSensorNear: Boolean,
    val lcdSequence: Long,
    val evfSequence: Long,
    val lcdOutputActive: Boolean?,
    val evfOutputActive: Boolean?,
    val lastUpdatedPort: String?,
) {
    fun summaryText(): String = I18n.t(
        when (active) {
            ActiveViewfinder.EVF -> if (eyeSensorNear) "evf.summary.evf_near" else "evf.summary.evf"
            ActiveViewfinder.LCD -> if (eyeSensorNear) "evf.summary.lcd_near" else "evf.summary.lcd"
            ActiveViewfinder.BOTH -> "evf.summary.both"
            ActiveViewfinder.WAITING -> if (eyeSensorNear) "evf.summary.waiting_near" else "evf.summary.waiting"
        },
    )

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "active" to active.name,
        "label" to summaryText(),
        "eyeSensorNear" to eyeSensorNear,
        "lcdSequence" to lcdSequence,
        "evfSequence" to evfSequence,
        "lcdOutputActive" to lcdOutputActive,
        "evfOutputActive" to evfOutputActive,
        "lastUpdatedPort" to lastUpdatedPort,
    )

    companion object {
        fun derive(
            eyeSensorNear: Boolean,
            lcdSequence: Long,
            evfSequence: Long,
            lcdOutputActive: Boolean?,
            evfOutputActive: Boolean?,
            lastUpdatedPort: String?,
        ): ViewfinderStatus {
            val active = when {
                evfOutputActive == true && lcdOutputActive != true -> ActiveViewfinder.EVF
                lcdOutputActive == true && evfOutputActive != true -> ActiveViewfinder.LCD
                evfOutputActive == true && lcdOutputActive == true ->
                    if (eyeSensorNear) ActiveViewfinder.EVF else ActiveViewfinder.BOTH
                evfSequence == 0L && lcdSequence == 0L -> ActiveViewfinder.WAITING
                evfSequence > 0L && lcdSequence == 0L -> ActiveViewfinder.EVF
                lcdSequence > 0L && evfSequence == 0L -> ActiveViewfinder.LCD
                lastUpdatedPort == "display.evf" -> ActiveViewfinder.EVF
                lastUpdatedPort == "display.main" -> ActiveViewfinder.LCD
                else -> if (eyeSensorNear) ActiveViewfinder.EVF else ActiveViewfinder.LCD
            }
            return ViewfinderStatus(
                active = active,
                eyeSensorNear = eyeSensorNear,
                lcdSequence = lcdSequence,
                evfSequence = evfSequence,
                lcdOutputActive = lcdOutputActive,
                evfOutputActive = evfOutputActive,
                lastUpdatedPort = lastUpdatedPort,
            )
        }
    }
}

/**
 * Opt-in minimal floating EVF window sharing the exact same zero-copy JavaFX `PixelBuffer`
 * `WritableImage` backing `rear-evf` (`display.evf`).
 *
 * The window is an edge-to-edge optical viewport with no permanent chrome. A compact translucent
 * HUD (status, Eye Sensor `F6`, `VIEW MODE` `F7`, and an Always-on-Top pin) fades in only while
 * the pointer is over the window or a HUD control holds keyboard focus, so the viewfinder stays
 * unobstructed while shooting.
 *
 * The stage is intentionally not owned by the main window so the pin keeps it above other
 * applications even when the studio is minimized or behind; [CameraStudio.close] closes it.
 * Hidden by default so existing six-face layouts and smoke tests remain untouched until opened.
 */
internal class EvfViewfinderWindow(
    owner: Stage,
    sourceEvfImageView: ImageView,
    private val onToggleEyeSensor: () -> Unit,
    private val onViewModePressed: (Boolean) -> Unit,
    private val onViewModePulse: () -> Unit = {
        onViewModePressed(true)
        onViewModePressed(false)
    },
    private val onViewModeCancel: () -> Unit = { onViewModePressed(false) },
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val onKeyShortcut: (KeyEvent) -> Unit,
    initialAlwaysOnTop: Boolean = false,
    private val onAlwaysOnTopChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    val stage = Stage()
    val statusBadge = Label(I18n.t("evf.active.waiting")).apply {
        id = "evf-window-status"
        styleClass += "evf-hud-status"
        minWidth = 0.0
        textOverrun = OverrunStyle.ELLIPSIS
        HBox.setHgrow(this, Priority.SOMETIMES)
    }
    val eyeToggle = ToggleButton(I18n.t("evf.window.eye.near")).apply {
        id = "evf-window-eye-toggle"
        styleClass += "evf-hud-pill"
        minWidth = Region.USE_PREF_SIZE
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("evf.window.eye.tooltip")) }
        setOnAction { onToggleEyeSensor() }
    }
    val viewModeButton = Button().apply {
        id = "evf-window-view-mode"
        textProperty().bind(I18n.bind("evf.window.view_mode"))
        styleClass += "evf-hud-pill"
        minWidth = Region.USE_PREF_SIZE
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("evf.window.view_mode.tooltip")) }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                onViewModePressed(true)
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                onViewModePressed(false)
                event.consume()
            }
        }
        setOnAction {
            onViewModePulse()
        }
    }
    private var syncingPin = false
    val pinToggle = ToggleButton().apply {
        id = "evf-window-pin-toggle"
        styleClass += listOf("evf-hud-pill", "evf-hud-pin")
        minWidth = Region.USE_PREF_SIZE
        textProperty().bind(I18n.bindDynamic(selectedProperty()) {
            I18n.t(if (isSelected) "evf.window.pin.active" else "evf.window.pin")
        })
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("evf.window.pin.tooltip")) }
    }
    internal val hudBar = HBox(6.0, statusBadge, eyeToggle, viewModeButton, pinToggle).apply {
        id = "evf-window-hud"
        styleClass += "evf-hud-bar"
        alignment = Pos.CENTER_RIGHT
        maxWidth = Region.USE_PREF_SIZE
        maxHeight = Region.USE_PREF_SIZE
        StackPane.setAlignment(this, Pos.TOP_RIGHT)
        StackPane.setMargin(this, Insets(8.0, 8.0, 0.0, 8.0))
    }
    private val waitingLabel = Label().apply {
        textProperty().bind(I18n.bind("evf.window.waiting_overlay"))
        styleClass += "studio-display-waiting"
        isMouseTransparent = true
    }
    private val evfView = ImageView().apply {
        id = "evf-window-image"
        isPreserveRatio = true
        isSmooth = true
        imageProperty().bind(sourceEvfImageView.imageProperty())
    }
    internal val rootPane = StackPane(evfView, waitingLabel, hudBar).apply {
        id = "evf-window-root"
        styleClass += listOf("evf-window-root", "evf-viewport-tube")
        style = "-fx-font-family: '${StudioTypography.family}';"
        minWidth = 0.0
        minHeight = 0.0
        evfView.fitWidthProperty().bind(widthProperty())
        evfView.fitHeightProperty().bind(heightProperty())
        hudBar.maxWidthProperty().bind(Bindings.max(120.0, widthProperty().subtract(16.0)))
    }
    private var lastStatus: ViewfinderStatus? = null
    private var closed = false
    private var pointerInside = false
    private var hudPeek = false

    /** Target HUD visibility (the fade animation converges towards this state). */
    internal var hudTargetVisible: Boolean = true
        private set
    private val hudFade = FadeTransition(Duration.millis(HUD_FADE_MS), hudBar)
    private val hudPeekTimer = PauseTransition(Duration.millis(HUD_PEEK_MS)).apply {
        setOnFinished {
            hudPeek = false
            updateHudVisibility()
        }
    }
    private val localeListener = I18n.onLocaleChange { refreshLocalizedStatus() }

    init {
        // Not owned by the studio window: an owned stage follows its owner's Z-order/minimize on
        // several window managers, which would defeat the Always-on-Top pin.
        stage.icons.setAll(owner.icons)
        stage.titleProperty().bind(I18n.bind("evf.window.title"))
        stage.minWidth = MIN_WIDTH
        stage.minHeight = MIN_HEIGHT
        stage.scene = Scene(rootPane, DEFAULT_WIDTH, DEFAULT_HEIGHT).apply {
            fill = Color.BLACK
            stylesheets += EvfViewfinderWindow::class.java.getResource("/panel.css")!!.toExternalForm()
            addEventFilter(KeyEvent.KEY_PRESSED, onKeyShortcut)
            addEventFilter(KeyEvent.KEY_RELEASED, onKeyShortcut)
        }
        rootPane.setOnMouseEntered { setPointerInside(true) }
        rootPane.setOnMouseExited { setPointerInside(false) }
        hudBar.focusWithinProperty().addListener { _, _, _ -> updateHudVisibility() }

        pinToggle.selectedProperty().addListener { _, _, on ->
            if (closed) return@addListener
            stage.isAlwaysOnTop = on
            if (!syncingPin) onAlwaysOnTopChanged(on)
        }
        stage.alwaysOnTopProperty().addListener { _, _, on ->
            if (pinToggle.isSelected != on) {
                syncingPin = true
                pinToggle.isSelected = on
                syncingPin = false
            }
        }
        setAlwaysOnTop(initialAlwaysOnTop, notify = false)

        stage.setOnCloseRequest { event ->
            event.consume()
            setVisible(false)
        }
        stage.showingProperty().addListener { _, _, showing ->
            if (!showing) onViewModeCancel()
            if (showing) peekHud()
            // Shutdown (close()) must not be persisted as a user "EVF window closed" preference.
            if (!closed) onVisibilityChanged(showing)
        }
    }

    val isShowing: Boolean get() = stage.isShowing

    val isAlwaysOnTop: Boolean get() = stage.isAlwaysOnTop

    /** Pins/unpins the EVF window above all other windows; [notify] reports the change for persistence. */
    fun setAlwaysOnTop(on: Boolean, notify: Boolean = true) {
        if (closed) return
        syncingPin = !notify
        try {
            pinToggle.isSelected = on
            stage.isAlwaysOnTop = on
        } finally {
            syncingPin = false
        }
    }

    fun rebindSourceImageView(sourceEvfImageView: ImageView) {
        if (closed) return
        evfView.imageProperty().unbind()
        evfView.imageProperty().bind(sourceEvfImageView.imageProperty())
    }

    fun applyUiTheme(theme: com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme) {
        if (closed) return
        stage.scene?.let {
            com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.applyToScene(it, theme)
            // The viewport tube stays optically black regardless of the studio chrome palette.
            it.fill = Color.BLACK
        }
    }

    fun setVisible(visible: Boolean) {
        if (closed) return
        if (visible) {
            if (!stage.isShowing) stage.show()
            stage.toFront()
        } else if (stage.isShowing) {
            stage.hide()
        }
    }

    /** Briefly reveals the HUD (on open) before it fades away unless hovered or focused. */
    internal fun peekHud() {
        hudPeek = true
        updateHudVisibility()
        hudPeekTimer.playFromStart()
    }

    /** Ends any transient HUD reveal immediately (used by tests and when the user starts shooting). */
    internal fun settleHud() {
        hudPeekTimer.stop()
        hudPeek = false
        updateHudVisibility()
    }

    private fun setPointerInside(inside: Boolean) {
        pointerInside = inside
        updateHudVisibility()
    }

    private fun updateHudVisibility() {
        val show = pointerInside || hudPeek || hudBar.isFocusWithin
        if (show == hudTargetVisible) return
        hudTargetVisible = show
        hudFade.stop()
        hudFade.fromValue = hudBar.opacity
        hudFade.toValue = if (show) 1.0 else 0.0
        hudFade.playFromStart()
    }

    private fun refreshLocalizedStatus() {
        val status = lastStatus
        if (status != null) {
            updateStatus(status)
        } else {
            statusBadge.text = I18n.t("evf.active.waiting")
            eyeToggle.text = I18n.t("evf.window.eye.near")
        }
    }

    fun updateStatus(status: ViewfinderStatus) {
        lastStatus = status
        statusBadge.text = I18n.t("evf.window.status_prefix", status.summaryText())
        eyeToggle.isSelected = status.eyeSensorNear
        eyeToggle.text = I18n.t(if (status.eyeSensorNear) "evf.window.eye.far" else "evf.window.eye.near")
        waitingLabel.isVisible = status.evfSequence == 0L
    }

    override fun close() {
        if (!closed) {
            closed = true
            hudPeekTimer.stop()
            hudFade.stop()
            I18n.localeProperty.removeListener(localeListener)
            stage.titleProperty().unbind()
            evfView.imageProperty().unbind()
            stage.close()
        }
    }

    companion object {
        const val DEFAULT_WIDTH: Double = 560.0
        const val DEFAULT_HEIGHT: Double = 420.0
        const val MIN_WIDTH: Double = 280.0
        const val MIN_HEIGHT: Double = 210.0
        private const val HUD_FADE_MS: Double = 180.0
        private const val HUD_PEEK_MS: Double = 2200.0
    }
}
