package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.theme.BodyTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.BodyThemeRegistry
import com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.scene.CacheHint
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Scene
import javafx.stage.Screen
import javafx.stage.StageStyle
import javafx.stage.WindowEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.transform.Scale
import javafx.scene.transform.Rotate
import javafx.geometry.BoundingBox
import javafx.scene.robot.Robot
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import javafx.embed.swing.SwingFXUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/** Configuration main Stage owns 1..N independent thin face windows defined by the active [BodyTheme]. The shell never paints a substitute camera menu. */
class CameraStudio(private val owner: Stage, sendButton: (String, Boolean) -> CompletableFuture<*>,
    private val configure: (String) -> Unit, private val inspect: (PanelControl) -> Unit,
    private val failure: (Throwable) -> Unit,
    private val sendSetting: ((String, String, Boolean) -> CompletableFuture<*>)? = null,
    private val sendSettingValue: ((String, String, Any) -> CompletableFuture<*>)? = null,
    private val minMomentaryHoldMs: Long = 0L,
    private val momentaryHoldPolicy: MomentaryHoldPolicy = MomentaryHoldPolicy.wallClockOnly(minMomentaryHoldMs),
    private val virtualTick: (() -> Long)? = null,
    private val isSessionAdvancing: (() -> Boolean)? = null,
    private val sendTouch: ((Int, Int, Boolean) -> CompletableFuture<*>)? = null,
    private val sendViewMode: ((Boolean) -> CompletableFuture<*>)? = null,
    private val patchCmos: ((Map<String, Any>) -> CompletableFuture<*>)? = null,
    private val patchOpticalFilter: ((Map<String, Any>) -> CompletableFuture<*>)? = null,
    private val onOpenFilterRack: (() -> Unit)? = null,
    private val onOpenLensProfile: (() -> Unit)? = null,
    private val applyAudioPatch: ((Map<String, Any?>) -> CompletableFuture<Map<String, Any?>>)? = null,
    private val onOpenAudioPanel: (() -> Unit)? = null,
    private val onStatusMessage: (String) -> Unit = {},
    initialBodyTheme: BodyTheme = BodyThemeRegistry.origin(),
    initialUiTheme: UiTheme = UiThemeRegistry.origin(),
    private val onEvfWindowVisibilityChanged: ((Boolean) -> Unit)? = null,
    private val initialEvfAlwaysOnTop: Boolean = false,
    private val onEvfAlwaysOnTopChanged: ((Boolean) -> Unit)? = null) : AutoCloseable {
    private val originCatalog = PanelCatalog.load()
    var bodyTheme: BodyTheme = initialBodyTheme; private set
    var uiTheme: UiTheme = initialUiTheme; private set
    val catalog: List<CameraPanel> get() = bodyTheme.panels
    private val latchingButtonKeys = PanelCatalog.latchingButtonKeys(originCatalog)
    private val latchingSettingControls = PanelCatalog.latchingSettingControls(originCatalog)
    val buttons = linkedMapOf<String, ToggleButton>()
    private val proxyButtons = mutableMapOf<String, MutableList<ToggleButton>>()
    val latch = CheckBox().apply { textProperty().bind(I18n.bind("studio.latch.debug")) }
    private val regions = linkedMapOf<String, Region>()
    private val canvases = linkedMapOf<String, Pane>()
    private var lastInspectedControlId:String?=null
    private var sentFrontHitPress = false
    private var frontHitStart:Map<String,Any?>?=null
    private var oldPointer:Pair<Double,Double>?=null
    private val frontMouseEvents=mutableListOf<Map<String,Any?>>()
    private var frontPointerAtPress:Map<String,Any?>?=null
    val viewToggles = linkedMapOf<String, CheckBox>()
    private val faceWindows = linkedMapOf<String, Stage>()
    internal fun faceStage(id: String): Stage? = faceWindows[id]
    private var syncingToggle = false
    private var arrangeScale = 1.0
    private var lastFitScale = 1.0
    private var shootingWorkspace = false
    private val windowLayout get() = if (shootingWorkspace) StudioLayout.shootingWorkspace else bodyTheme.layout
    private val togglesGrid = GridPane().apply {
        hgap = 10.0; vgap = 7.0; padding = Insets(6.0, 2.0, 2.0, 2.0)
        columnConstraints.addAll(
            ColumnConstraints().apply { percentWidth = 50.0 },
            ColumnConstraints().apply { percentWidth = 50.0 },
        )
    }
    private val facesSectionTitle = Label().apply { styleClass += "studio-section-title" }
    private var controlsExpanded = true
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var controlsToggleBtn: Button
    private var readbackExpanded = true
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var readbackToggleBtn: Button
    private val readbackKeyLabels = linkedMapOf<String, Label>()
    private val readbackSettingLabels = linkedMapOf<String, Label>()
    private var localeListener: javafx.beans.value.ChangeListener<java.util.Locale>? = null
    private var lastSnapshot: Map<String, Any?>? = null
    private val dragHandles=linkedMapOf<String,Region>()
    private val screen = Screen.getPrimary()
    private val surfaces = linkedMapOf<String, Surface>()
    private val layers = linkedMapOf<String, Node>()
    private val openCoversByTheme = mutableMapOf<String, MutableSet<String>>()
    private val dynamicNodes = linkedMapOf<String, Node>()
    private val dynamicRotates = linkedMapOf<String, Rotate>()
    private var desiredFocusMode: String = "AF-S"
    private data class BodyIndicatorSlot(val label: Label, val control: PanelControl)
    private val bodyModeDialIndicators = mutableListOf<BodyIndicatorSlot>()
    private val bodyFocusSegments = mutableListOf<FocusModeSegments>()
    private val bodyStillMovieIndicators = mutableListOf<BodyIndicatorSlot>()
    private val bodyFocusModeIndicators = mutableListOf<BodyIndicatorSlot>()
    private val center = StackPane()
    private val pendingText = Label()
    private var switchingToStill = false
    internal val showingStillModeTransition: Boolean
        get() = switchingToStill && !effectiveBodyButtonState("movieMode")
    private val originControlsByButtonKey: Map<String, PanelControl> = originCatalog
        .flatMap { it.controls }
        .filter { it.buttonKey != null }
        .associateBy { requireNotNull(it.buttonKey) }
    private data class InputRow(val button: ToggleButton, val lockButton: ToggleButton?, val request: Label, val readback: Label)
    private val inputRows = linkedMapOf<String, InputRow>()
    private data class SettingRow(val control: PanelControl, val gridButton: ToggleButton, val request: Label, val readback: Label)
    private val settingBodyButtons = linkedMapOf<String, ToggleButton>()
    private val settingRows = linkedMapOf<String, SettingRow>()
    private val observedSettings = linkedMapOf<String, Boolean?>()
    private val desiredSettings = linkedMapOf<String, Boolean>()
    private val settingVersions = linkedMapOf<String, Long>()
    private val settingRequests = linkedMapOf<String, StudioInputState.Request>()
    private var touchEnabled = false
    private var touchActive = false
    private var touchPressCoord = TouchPointCoord(0, 0)
    private var touchSwipeDispatched = false
    private var lastTouchCoord = TouchPointCoord(0, 0)
    private var lastTouchDragNanos = 0L
    private var pendingSwipeRelease: AutoCloseable? = null
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var touchHold: SingleStepButtonHold
    private var viewModePressed = false
    private val viewModeSources = linkedSetOf<String>()
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var viewModeHold: SingleStepButtonHold
    private var viewModeBodyButton: Button? = null
    private val momentarySettingControls: Map<String, PanelControl> = originCatalog
        .flatMap { it.controls }
        .filter { it.buttonKey == null && it.componentId != null && it.settingKey != null && it.interaction == "momentary" }
        .associateBy { it.id }
    private val momentarySettingSources = linkedMapOf<String, MutableSet<String>>()
    private val momentarySettingPressed = linkedSetOf<String>()
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var momentarySettingHold: SingleStepButtonHold
    private val adcQuickButtons = linkedMapOf<String, Button>()
    private val adcLockButtons = linkedMapOf<String, ToggleButton>()
    private val adcBodyButtons = linkedMapOf<String, Button>()
    private var syncingLatchMode = false
    private var syncingLockButtons = false
    private var observedModeDial: String = "default"
    private var desiredModeDial: String = "default"
    private var modeDialPending = false
    private var modeDialVersion = 0L
    private var syncingModeDialBox = false
    private var observedRearDialDetents: Long = 0L
    private var desiredRearDialDetents: Long = 0L
    private var rearDialPending = false
    private var rearDialVersion = 0L
    private var observedFrontDialDetents: Long = 0L
    private var desiredFrontDialDetents: Long = 0L
    private var frontDialPending = false
    private var frontDialVersion = 0L
    private val quickModeDialCombo = ComboBox<String>().apply {
        id = "quick-mode-dial"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(MODE_DIAL_CHOICES)
        value = "default"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.mode_dial.tooltip")) }
        valueProperty().addListener { _, _, next ->
            if (!syncingModeDialBox && next != null) selectModeDial(next)
        }
    }
    private class TactileDialWheel(
        wheelId: String,
        tooltipKey: String,
        private val onStep: (Int) -> Unit,
    ) : Pane() {
        private val ribPitch = 7.0
        private val ribs = mutableListOf<Line>()
        private val centerNotch = Line().apply {
            styleClass += "studio-dial-notch"
            strokeWidth = 1.6
            isMouseTransparent = true
        }
        private var detentOffsetPx = 0.0
        private var dragVisualPx = 0.0
        private var lastDragSceneX = 0.0
        private var dragAccumPx = 0.0
        private var dragging = false

        init {
            id = wheelId
            styleClass += "studio-dial-wheel"
            minWidth = 48.0
            prefWidth = 128.0
            maxWidth = Double.MAX_VALUE
            minHeight = 22.0
            prefHeight = 22.0
            maxHeight = 22.0
            val clipRect = Rectangle()
            clipRect.widthProperty().bind(widthProperty())
            clipRect.heightProperty().bind(heightProperty())
            clip = clipRect
            Tooltip.install(this, Tooltip().apply { textProperty().bind(I18n.bind(tooltipKey)) })
            children += centerNotch

            addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
                if (event.button == MouseButton.PRIMARY) {
                    dragging = true
                    lastDragSceneX = event.screenX
                    dragAccumPx = 0.0
                    dragVisualPx = 0.0
                    if ("studio-dial-wheel-dragging" !in styleClass) styleClass += "studio-dial-wheel-dragging"
                    event.consume()
                }
            }
            addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
                if (dragging && event.isPrimaryButtonDown) {
                    val dx = event.screenX - lastDragSceneX
                    lastDragSceneX = event.screenX
                    dragAccumPx += dx
                    dragVisualPx += dx
                    while (dragAccumPx >= SIDEBAR_DIAL_DRAG_PX) {
                        onStep(1)
                        dragAccumPx -= SIDEBAR_DIAL_DRAG_PX
                    }
                    while (dragAccumPx <= -SIDEBAR_DIAL_DRAG_PX) {
                        onStep(-1)
                        dragAccumPx += SIDEBAR_DIAL_DRAG_PX
                    }
                    layoutKnurlRibs()
                    event.consume()
                }
            }
            addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
                if (dragging && event.button == MouseButton.PRIMARY) {
                    dragging = false
                    dragVisualPx = 0.0
                    dragAccumPx = 0.0
                    styleClass.remove("studio-dial-wheel-dragging")
                    layoutKnurlRibs()
                    event.consume()
                }
            }
            addEventFilter(ScrollEvent.SCROLL) { event ->
                if (event.deltaY != 0.0) {
                    onStep(if (event.deltaY > 0.0) 1 else -1)
                    event.consume()
                }
            }
        }

        fun setDetents(detents: Long) {
            detentOffsetPx = (detents * 4.0)
            layoutKnurlRibs()
        }

        override fun layoutChildren() {
            super.layoutChildren()
            layoutKnurlRibs()
        }

        private fun layoutKnurlRibs() {
            val w = width.takeIf { it > 0.0 } ?: prefWidth
            val h = height.takeIf { it > 0.0 } ?: prefHeight
            val needed = (ceil(w / ribPitch).toInt() + 4).coerceAtLeast(8)
            while (ribs.size < needed) {
                val ribIndex = ribs.size
                val rib = Line().apply {
                    isMouseTransparent = true
                    strokeWidth = 1.2
                    styleClass += if (ribIndex % 2 == 0) "studio-dial-rib-major" else "studio-dial-rib-minor"
                }
                ribs += rib
                children.add(0, rib)
            }
            val rawPhase = (detentOffsetPx + dragVisualPx) % ribPitch
            val phase = if (rawPhase < 0.0) rawPhase + ribPitch else rawPhase
            for (i in ribs.indices) {
                val x = (i - 1) * ribPitch + phase
                val rib = ribs[i]
                rib.startX = x
                rib.endX = x
                rib.startY = 3.5
                rib.endY = h - 3.5
                val distNorm = (abs(x - w / 2.0) / (w / 2.0).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val alpha = (0.58 * (1.0 - distNorm * 0.65)).coerceIn(0.14, 0.62)
                rib.opacity = if (i % 2 == 0) alpha else alpha * 0.72
                rib.isVisible = x in -2.0..(w + 2.0)
            }
            centerNotch.startX = w / 2.0
            centerNotch.endX = w / 2.0
            centerNotch.startY = 1.0
            centerNotch.endY = 6.0
        }
    }

    private val rearDialWheel = TactileDialWheel("tactile-rear-dial-wheel", "studio.dials.rear_wheel.tooltip") { delta ->
        stepRearCommandDial(delta)
    }
    private val frontDialWheel = TactileDialWheel("tactile-front-dial-wheel", "studio.dials.front_wheel.tooltip") { delta ->
        stepFrontCommandDial(delta)
    }
    private val rearDialDetentsLabel = Label("0").apply {
        id = "readback-rear-dial-detents"
        styleClass += "studio-telemetry-val"
        minWidth = 28.0
        alignment = javafx.geometry.Pos.CENTER_RIGHT
    }
    private val quickRearDialCcwBtn = Button("◀").apply {
        id = "quick-rear-dial-ccw"
        styleClass += "studio-dial-step-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.rear_ccw.tooltip")) }
        setOnAction { stepRearCommandDial(-1) }
    }
    private val quickRearDialCwBtn = Button("▶").apply {
        id = "quick-rear-dial-cw"
        styleClass += "studio-dial-step-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.rear_cw.tooltip")) }
        setOnAction { stepRearCommandDial(1) }
    }
    private val quickRearDialPressBtn = Button().apply {
        id = "quick-rear-dial-press"
        styleClass += "studio-dial-step-btn"
        textProperty().bind(I18n.bind("studio.dials.press"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.rear_press.tooltip")) }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                pressMomentary("mouse:quick-rear-dial-press", "rearDialPress")
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                releaseMomentary("mouse:quick-rear-dial-press", "rearDialPress")
                event.consume()
            }
        }
        setOnAction { pulseMomentary("rearDialPress") }
    }
    private val frontDialDetentsLabel = Label("0").apply {
        id = "readback-front-dial-detents"
        styleClass += "studio-telemetry-val"
        minWidth = 28.0
        alignment = javafx.geometry.Pos.CENTER_RIGHT
    }
    private val quickFrontDialCcwBtn = Button("◀").apply {
        id = "quick-front-dial-ccw"
        styleClass += "studio-dial-step-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.front_ccw.tooltip")) }
        setOnAction { stepFrontCommandDial(-1) }
    }
    private val quickFrontDialCwBtn = Button("▶").apply {
        id = "quick-front-dial-cw"
        styleClass += "studio-dial-step-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.front_cw.tooltip")) }
        setOnAction { stepFrontCommandDial(1) }
    }
    private val quickFrontDialPressBtn = Button().apply {
        id = "quick-front-dial-press"
        styleClass += "studio-dial-step-btn"
        textProperty().bind(I18n.bind("studio.dials.press"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.dials.front_press.tooltip")) }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                pressMomentary("mouse:quick-front-dial-press", "frontDialPress")
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                releaseMomentary("mouse:quick-front-dial-press", "frontDialPress")
                event.consume()
            }
        }
        setOnAction { pulseMomentary("frontDialPress") }
    }
    private var observedLcdOutputActive: Boolean? = null
    private var observedEvfOutputActive: Boolean? = null
    private var observedShoulderBacklightOn: Boolean = false
    private var shoulderRawPixels: IntArray = IntArray(0)
    private var shoulderTonePixels: IntArray = IntArray(0)
    private var shoulderFrameWidth: Int = 0
    private var shoulderFrameHeight: Int = 0
    private var lastUpdatedDisplayPort: String? = null
    private var syncingEvfWindowToggle = false
    private fun PanelControl.hasRole(expected: String, legacyId: String): Boolean =
        role == expected || id == legacyId

    private fun PanelControl.isEyeSensorControl(): Boolean =
        hasRole("eye-sensor", "rear-eye-sensor") || (componentId == "i2c-bus" && settingKey == "eyeSensorNear")

    private fun surfaceForPort(port: String, legacyId: String): Surface? =
        surfaces.values.firstOrNull { it.port == port } ?: surfaces[legacyId]

    private val eyeSensorControl: PanelControl? = latchingSettingControls.firstOrNull { it.isEyeSensorControl() }
    private val viewfinderStatusLabel = Label().apply {
        id = "viewfinder-active-status"
        styleClass += "studio-control-status"
        isWrapText = true
    }
    private val quickEyeSensorToggle = ToggleButton().apply {
        id = "quick-eye-sensor-toggle"
        styleClass += "studio-setting-switch"
        maxWidth = Double.MAX_VALUE
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("evf.window.eye.tooltip")) }
        setOnAction {
            toggleEyeSensor()
            updateViewfinderStatus()
        }
    }
    private val quickViewModeBtn = Button().apply {
        id = "quick-view-mode-btn"
        maxWidth = Double.MAX_VALUE
        textProperty().bind(I18n.bind("evf.window.view_mode"))
        tooltip = Tooltip().apply {
            textProperty().bind(I18n.bindDynamic { "${I18n.t("evf.window.view_mode.tooltip")}\n${I18n.t("studio.tip.key_combo_hint")}" })
        }
        var unlockedOnPress = false
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (sendViewMode != null && (event.button == MouseButton.SECONDARY || (event.button == MouseButton.PRIMARY && event.isShiftDown))) {
                toggleViewModeLock()
                event.consume()
            } else if (event.button == MouseButton.PRIMARY) {
                if (isViewModeLocked()) {
                    unlockedOnPress = true
                    unlockViewMode()
                } else {
                    pressViewMode("mouse:quick-view-mode")
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                if (unlockedOnPress) {
                    unlockedOnPress = false
                } else {
                    releaseViewMode("mouse:quick-view-mode")
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.SECONDARY) event.consume()
        }
        setOnAction {
            if (isViewModeLocked()) unlockViewMode() else pulseViewMode()
        }
    }
    private val viewModeLockBtn = ToggleButton(UNLOCK_ICON).apply {
        id = "lock-view-mode"
        styleClass += "studio-lock-btn"
        isFocusTraversable = false
        tooltip = Tooltip()
        setOnAction {
            if (!syncingLockButtons) toggleViewModeLock()
        }
    }
    private val comboLockModeToggle = ToggleButton().apply {
        id = "combo-lock-mode-toggle"
        styleClass += "studio-seg-btn"
        textProperty().bind(I18n.bind("studio.lock.mode"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.lock.mode.tooltip")) }
        selectedProperty().addListener { _, _, on ->
            if (!syncingLatchMode) setGlobalLatchMode(on)
        }
    }
    private val comboLockSummaryLabel = Label().apply {
        id = "combo-lock-summary"
        styleClass += "studio-control-status"
        isWrapText = true
    }
    private val comboLockUnlockAllBtn = Button().apply {
        id = "combo-lock-unlock-all"
        styleClass += "studio-chip-btn"
        textProperty().bind(I18n.bind("studio.lock.unlock_all"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.lock.unlock_all.tooltip")) }
        setOnAction { unlockAllButtons() }
    }
    private val comboLockBannerLabel = Label().apply {
        id = "combo-lock-banner-label"
        styleClass += "studio-lock-banner-text"
        isWrapText = true
    }
    private val comboLockBannerUnlockBtn = Button().apply {
        id = "combo-lock-banner-unlock-all"
        styleClass += "studio-chip-btn"
        textProperty().bind(I18n.bind("studio.lock.unlock_all"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.lock.unlock_all.tooltip")) }
        setOnAction { unlockAllButtons() }
    }
    private val comboLockBanner = HBox(
        6.0,
        comboLockBannerLabel,
        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        comboLockBannerUnlockBtn,
    ).apply {
        id = "combo-lock-active-strip"
        styleClass += "studio-lock-banner"
        alignment = javafx.geometry.Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }
    private val comboLockBar = VBox(
        5.0,
        HBox(
            6.0,
            comboLockModeToggle,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            comboLockUnlockAllBtn,
        ).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
        comboLockSummaryLabel,
    ).apply {
        id = "combo-lock-bar"
        styleClass += "studio-lock-bar"
    }
    private val quickEvfWindowToggle = ToggleButton().apply {
        id = "quick-evf-window-toggle"
        styleClass += "studio-setting-switch"
        maxWidth = Double.MAX_VALUE
        textProperty().bind(I18n.bind("studio.vf.evf_window"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.vf.evf_window.tooltip")) }
        selectedProperty().addListener { _, _, on ->
            if (!syncingEvfWindowToggle) setEvfWindowVisible(on)
        }
    }
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var evfWindow: EvfViewfinderWindow
    val shootingControlsPane: ShootingControlsPane
    val sourceSelectorPane: SourceSelectorPane
    val opticalFilterPane: OpticalFilterPane
    val quickAudioStripPane: QuickAudioStripPane
    val root = BorderPane()
    val viewTitleProperty = SimpleStringProperty(I18n.t("studio.view.default_title"))
    var selectedView = "rear"; private set
    private var closed = false
    internal var readShutterHalfReady: (() -> java.util.concurrent.CompletableFuture<Boolean?>)? = null
    internal var readShutterBusy: (() -> java.util.concurrent.CompletableFuture<Boolean?>)? = null
    private var shutterMovieMode = false
    private var shutter: StudioShutter? = null
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var momentaryHold: SingleStepButtonHold
    private val inputs = StudioInputState(sendButton,
        dispatch = { action -> if (Platform.isFxApplicationThread()) action() else Platform.runLater(action) },
        changed = { updateButtons() }, failure = failure, latchingKeys = latchingButtonKeys,
        appliedTickProvider = virtualTick,
        onPressApplied = { key, tick -> if (::momentaryHold.isInitialized) momentaryHold.markApplied(key, tick) })
    init {
        val fxScheduleDelay: (Long, () -> Unit) -> AutoCloseable = { delayMs, action ->
            val timer = PauseTransition(Duration.millis(delayMs.toDouble())).apply {
                setOnFinished { if (!closed) action() }
            }
            timer.play()
            AutoCloseable { timer.stop() }
        }
        if (virtualTick != null && momentaryHoldPolicy.minHoldVirtualTicks > 0L) {
            shutter = StudioShutter(inputs, virtualTick, isSessionAdvancing ?: { true }, {
                if (desiredFocusMode == "AF-S" && !shutterMovieMode) readShutterHalfReady?.invoke()
                else java.util.concurrent.CompletableFuture.completedFuture(true)
            }, readBusy = { if (shutterMovieMode) null else readShutterBusy?.invoke() }) { action ->
                fxScheduleDelay(momentaryHoldPolicy.pollIntervalMs, action)
            }
        }
        momentaryHold = SingleStepButtonHold(
            policy = momentaryHoldPolicy,
            onPress = inputs::press,
            onRelease = inputs::release,
            onPulse = inputs::pulse,
            virtualTick = virtualTick,
            isSessionAdvancing = isSessionAdvancing,
            awaitAppliedTick = virtualTick != null,
            scheduleDelay = fxScheduleDelay,
        )
        viewModeHold = SingleStepButtonHold(
            policy = momentaryHoldPolicy,
            onPress = { source, _ ->
                if (viewModeSources.add(source)) dispatchViewMode(true)
            },
            onRelease = { source, _ ->
                if (viewModeSources.remove(source) && viewModeSources.isEmpty()) dispatchViewMode(false)
            },
            onPulse = { _ ->
                dispatchViewMode(true)
                dispatchViewMode(false)
            },
            virtualTick = virtualTick,
            isSessionAdvancing = isSessionAdvancing,
            awaitAppliedTick = virtualTick != null,
            scheduleDelay = fxScheduleDelay,
        )
        val touchHoldPolicy = if (momentaryHoldPolicy.minHoldVirtualTicks > 0L) {
            momentaryHoldPolicy.copy(
                minHoldVirtualTicks = maxOf(
                    momentaryHoldPolicy.minHoldVirtualTicks,
                    MomentaryHoldPolicy.CALIBRATED_TOUCH_TAP_VIRTUAL_TICKS,
                ),
                directionMinHoldVirtualTicks = maxOf(
                    momentaryHoldPolicy.minHoldVirtualTicks,
                    MomentaryHoldPolicy.CALIBRATED_TOUCH_TAP_VIRTUAL_TICKS,
                ),
            )
        } else {
            momentaryHoldPolicy
        }
        touchHold = SingleStepButtonHold(
            policy = touchHoldPolicy,
            onPress = { _, _ -> dispatchTouch(lastTouchCoord, pressed = true) },
            onRelease = { _, _ -> releaseActiveTouch() },
            onPulse = { _ ->
                dispatchTouch(lastTouchCoord, pressed = true)
                releaseActiveTouch()
            },
            virtualTick = virtualTick,
            isSessionAdvancing = isSessionAdvancing,
            awaitAppliedTick = virtualTick != null,
            scheduleDelay = fxScheduleDelay,
        )
        momentarySettingHold = SingleStepButtonHold(
            policy = momentaryHoldPolicy,
            onPress = { source, controlId ->
                val control = momentarySettingControls[controlId] ?: return@SingleStepButtonHold
                val sources = momentarySettingSources.getOrPut(controlId) { linkedSetOf() }
                if (sources.add(source)) dispatchMomentarySetting(control, true)
            },
            onRelease = { source, controlId ->
                val control = momentarySettingControls[controlId] ?: return@SingleStepButtonHold
                val sources = momentarySettingSources[controlId]
                if (sources != null && sources.remove(source) && sources.isEmpty()) {
                    momentarySettingSources.remove(controlId)
                    dispatchMomentarySetting(control, false)
                }
            },
            onPulse = { controlId ->
                val control = momentarySettingControls[controlId] ?: return@SingleStepButtonHold
                dispatchMomentarySetting(control, true)
                dispatchMomentarySetting(control, false)
            },
            virtualTick = virtualTick,
            isSessionAdvancing = isSessionAdvancing,
            awaitAppliedTick = virtualTick != null,
            scheduleDelay = fxScheduleDelay,
        )
    }
    data class Frame(val width: Int, val height: Int, val sequence: Long, val pixels: IntArray)
    private val portPixelSurfaces = linkedMapOf<String, PixelBufferDisplaySurface>()
    private val portSequences = linkedMapOf<String, Long>()
    private class Surface(
        val viewId: String,
        val port: String?,
        val image: ImageView,
        val waiting: Label,
        val container: StackPane,
        val pixelSurface: PixelBufferDisplaySurface = PixelBufferDisplaySurface(image),
        var sequence: Long = 0L,
    )
    private fun createSurface(viewId: String, port: String?, image: ImageView, waiting: Label, container: StackPane): Surface {
        val reused = if (port != null) {
            portPixelSurfaces.getOrPut(port) {
                PixelBufferDisplaySurface(image, filterMinification = port == "display.main")
            }.also { it.rebindImageView(image) }
        } else {
            PixelBufferDisplaySurface(image)
        }
        val prevSeq = if (port != null) portSequences[port] ?: 0L else 0L
        if (prevSeq > 0L && reused.image != null) {
            waiting.isVisible = false
        }
        return Surface(viewId, port, image, waiting, container, reused, prevSeq)
    }

    init {
        check(Platform.isFxApplicationThread())
        root.styleClass += "camera-studio"
        rebuildFacesForActiveTheme()
        val arrange = Button().apply {
            textProperty().bind(I18n.bind("studio.faces.arrange"))
            setOnAction { arrangeWindows(lastFitScale) }
        }
        val facesCard = VBox(6.0,
            HBox(8.0,
                facesSectionTitle,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                arrange
            ).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            togglesGrid
        ).apply { styleClass += "studio-card" }
        evfWindow = EvfViewfinderWindow(
            owner = owner,
            sourceEvfImageView = requireNotNull(surfaceForPort("display.evf", "rear-evf")).image,
            onToggleEyeSensor = {
                toggleEyeSensor()
                updateViewfinderStatus()
            },
            onViewModePressed = { pressed ->
                if (pressed) pressViewMode("mouse:evf-window-view-mode")
                else releaseViewMode("mouse:evf-window-view-mode")
            },
            onViewModePulse = ::pulseViewMode,
            onViewModeCancel = ::releaseTransientViewMode,
            onVisibilityChanged = { showing ->
                syncingEvfWindowToggle = true
                quickEvfWindowToggle.isSelected = showing
                syncingEvfWindowToggle = false
                onEvfWindowVisibilityChanged?.invoke(showing)
            },
            onKeyShortcut = { event -> if (!closed) handleViewfinderKeyEvent(event) },
            initialAlwaysOnTop = initialEvfAlwaysOnTop,
            onAlwaysOnTopChanged = { pinned -> onEvfAlwaysOnTopChanged?.invoke(pinned) },
        )
        evfWindow.applyUiTheme(uiTheme)
        shootingControlsPane = ShootingControlsPane(
            onSelectMovieMode = { movie -> setMovieModeFromStudio(movie) },
            onSelectFocusMode = { mode ->
                dispatchSettingAny("soc-gpio", "focusMode", mode)
                    ?: CompletableFuture.completedFuture(Unit)
            },
            onSelectModeDial = { mode -> selectModeDial(mode) },
            onStatus = onStatusMessage,
            onError = failure,
            isMovieModePending = { inputs.lastRequest("movieMode")?.submission == StudioInputState.Submission.PENDING },
            isModeDialPending = { modeDialPending },
            onFocusModeChanged = { mode -> updateFocusBodyIndicator(mode) },
        )
        val frontDialLabel = Label().apply {
            styleClass += "studio-table-head"
            minWidth = 44.0
            prefWidth = 54.0
            textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            textProperty().bind(I18n.bind("studio.dials.front"))
        }
        val rearDialLabel = Label().apply {
            styleClass += "studio-table-head"
            minWidth = 44.0
            prefWidth = 54.0
            textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            textProperty().bind(I18n.bind("studio.dials.rear"))
        }
        val tactileCommandDialsBox = VBox(
            4.0,
            Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("studio.dials.title")) },
            HBox(
                4.0,
                frontDialLabel,
                quickFrontDialCcwBtn,
                frontDialWheel,
                quickFrontDialCwBtn,
                frontDialDetentsLabel,
                quickFrontDialPressBtn,
            ).apply {
                alignment = javafx.geometry.Pos.CENTER_LEFT
                HBox.setHgrow(frontDialWheel, Priority.ALWAYS)
            },
            HBox(
                4.0,
                rearDialLabel,
                quickRearDialCcwBtn,
                rearDialWheel,
                quickRearDialCwBtn,
                rearDialDetentsLabel,
                quickRearDialPressBtn,
            ).apply {
                alignment = javafx.geometry.Pos.CENTER_LEFT
                HBox.setHgrow(rearDialWheel, Priority.ALWAYS)
            },
        ).apply { id = "tactile-command-dials-box" }
        shootingControlsPane.attachCommandDials(tactileCommandDialsBox)
        sourceSelectorPane = SourceSelectorPane(
            ownerWindow = { owner },
            patchCmos = patchCmos,
            onOpenLensProfile = onOpenLensProfile,
            onStatus = onStatusMessage,
            onError = failure,
        )
        opticalFilterPane = OpticalFilterPane(
            mode = OpticalFilterPane.Mode.COMPACT,
            patchOpticalFilter = patchOpticalFilter,
            onOpenFilterRack = onOpenFilterRack,
            onStatus = onStatusMessage,
            onError = failure,
        )
        quickAudioStripPane = QuickAudioStripPane(
            applyAudioPatch = applyAudioPatch,
            onOpenAudioPanel = onOpenAudioPanel,
            onStatus = onStatusMessage,
            onError = failure,
        )
        val viewModeQuickGroup = HBox(3.0, quickViewModeBtn, viewModeLockBtn).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
            HBox.setHgrow(quickViewModeBtn, Priority.ALWAYS)
        }
        val viewfinderCard = VBox(6.0,
            Label().apply { styleClass += "studio-section-title"; textProperty().bind(I18n.bind("studio.vf.section")) },
            viewfinderStatusLabel,
            HBox(6.0, quickEyeSensorToggle, viewModeQuickGroup).apply {
                HBox.setHgrow(quickEyeSensorToggle, Priority.ALWAYS)
                HBox.setHgrow(viewModeQuickGroup, Priority.ALWAYS)
            },
            quickEvfWindowToggle,
        ).apply { styleClass += "studio-card" }
        initInputAndSettingRows()
        val controlsBody = VBox(7.0,
            comboLockBar,
            buildPhysicalButtonsMatrix(),
        ).apply {
            id = "physical-controls-body"
            isVisible = controlsExpanded
            isManaged = controlsExpanded
        }
        controlsToggleBtn = Button(I18n.t("studio.matrix.toggle.expanded")).apply {
            id = "physical-controls-toggle"
            styleClass += "studio-chip-btn"
            maxWidth = Double.MAX_VALUE
            alignment = javafx.geometry.Pos.CENTER_LEFT
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.matrix.toggle.tooltip")) }
            setOnAction {
                controlsExpanded = !controlsBody.isVisible
                controlsBody.isVisible = controlsExpanded
                controlsBody.isManaged = controlsExpanded
                updateControlsToggleText()
            }
        }
        val controlsCard = VBox(6.0, controlsToggleBtn, comboLockBanner, controlsBody).apply {
            id = "physical-controls-card"
            styleClass += "studio-card"
        }
        val readbackBody = buildRawReadbackBody().apply {
            id = "raw-readback-body"
            isVisible = readbackExpanded
            isManaged = readbackExpanded
        }
        readbackToggleBtn = Button(I18n.t("studio.readback.toggle.expanded")).apply {
            id = "raw-readback-toggle"
            styleClass += "studio-chip-btn"
            maxWidth = Double.MAX_VALUE
            alignment = javafx.geometry.Pos.CENTER_LEFT
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.readback.toggle.tooltip")) }
            setOnAction {
                readbackExpanded = !readbackBody.isVisible
                readbackBody.isVisible = readbackExpanded
                readbackBody.isManaged = readbackExpanded
                updateReadbackToggleText()
            }
        }
        val readbackCard = VBox(6.0, readbackToggleBtn, readbackBody).apply {
            id = "raw-readback-card"
            styleClass += "studio-card"
        }
        root.center = VBox(
            8.0,
            shootingControlsPane.root,
            controlsCard,
            readbackCard,
            viewfinderCard,
            facesCard,
            sourceSelectorPane.root,
            opticalFilterPane.root,
            quickAudioStripPane.root,
        ).apply { padding = Insets(8.0) }
        refreshAllBodyIndicators()
        updateButtons()
        pendingText.styleClass += "studio-control-status"
        pendingText.isVisible = pendingText.text.isNotEmpty()
        pendingText.isManaged = pendingText.text.isNotEmpty()
        latch.selectedProperty().addListener { _, _, on -> if (!syncingLatchMode) setGlobalLatchMode(on) }
        owner.focusedProperty().addListener { _, _, focused -> if (!focused && !closed) { releaseTransientViewMode(); releaseTransientMomentarySettings(); cancelPendingReleases(); inputs.releaseTransient() } }
        owner.showingProperty().addListener { _, _, showing -> if (!showing && !closed) { cancelAndReleaseTouch(); releaseTransientViewMode(); releaseTransientMomentarySettings(); cancelPendingReleases(); inputs.releaseTransient() } }
        owner.scene.addEventFilter(MouseEvent.MOUSE_RELEASED) { if (!closed) { releaseTouchMouseTransient(); releaseViewModeMouseTransient(); releaseMomentarySettingMouseTransient(); releaseMouseTransient() } }
        owner.scene.addEventFilter(KeyEvent.KEY_PRESSED) { if (!closed) handleViewfinderKeyEvent(it) }
        owner.scene.addEventFilter(KeyEvent.KEY_RELEASED) { if (!closed) handleViewfinderKeyEvent(it) }
        selectView(bodyTheme.defaultViewId())
        localeListener = I18n.onLocaleChange {
            val refresh = {
                if (!closed) refreshLocalizedCopy()
            }
            if (Platform.isFxApplicationThread()) refresh() else Platform.runLater(refresh)
        }
    }
    private fun setGlobalLatchMode(on: Boolean) {
        syncingLatchMode = true
        try {
            if (latch.isSelected != on) latch.isSelected = on
            if (comboLockModeToggle.isSelected != on) comboLockModeToggle.isSelected = on
        } finally {
            syncingLatchMode = false
        }
        inputs.setLatch(on)
    }
    private fun updateControlsToggleText() {
        if (!::controlsToggleBtn.isInitialized) return
        val lockedCount = lockedButtonSummaryIds().size
        controlsToggleBtn.text = if (lockedCount > 0) {
            I18n.t(
                if (controlsExpanded) "studio.matrix.toggle.expanded.locked" else "studio.matrix.toggle.collapsed.locked",
                lockedCount,
            )
        } else {
            I18n.t(if (controlsExpanded) "studio.matrix.toggle.expanded" else "studio.matrix.toggle.collapsed")
        }
    }
    private fun updateReadbackToggleText() {
        if (!::readbackToggleBtn.isInitialized) return
        readbackToggleBtn.text = I18n.t(
            if (readbackExpanded) "studio.readback.toggle.expanded" else "studio.readback.toggle.collapsed",
        )
    }
    private fun controlLabel(c: PanelControl): String = c.localizedLabel()

    private fun compactKeyLabel(keyOrId: String, fallback: PanelControl): String = when (keyOrId) {
        "focusMode" -> I18n.t("studio.raw.short.focusMode")
        "movieMode" -> I18n.t("studio.raw.short.movieMode")
        "frontDialPress" -> I18n.t("studio.raw.short.frontDialPress")
        "rearDialPress" -> I18n.t("studio.raw.short.rearDialPress")
        "rear-eye-sensor" -> I18n.t("studio.raw.short.eyeSensor")
        else -> controlLabel(fallback)
    }

    private fun panelShortLabel(panel: CameraPanel): String = panel.localizedShortTitle()

    private fun panelCaption(panel: CameraPanel): String = panel.localizedCaption()

    private fun refreshLocalizedCopy() {
        updateFacesSectionTitle()
        updateControlsToggleText()
        updateReadbackToggleText()
        catalog.forEach { panel ->
            (dragHandles[panel.id] as? Label)?.text = panelCaption(panel)
            viewToggles[panel.id]?.text = panelShortLabel(panel)
            faceWindows[panel.id]?.title = "${bodyTheme.modelLabel} · ${panelShortLabel(panel)}"
        }
        inputRows.forEach { (key, row) ->
            originControlsByButtonKey[key]?.let { ctrl ->
                val text = compactKeyLabel(key, ctrl)
                row.button.text = text
                readbackKeyLabels[key]?.text = text
            }
        }
        settingRows.values.forEach { row ->
            val text = compactKeyLabel(row.control.id, row.control)
            row.gridButton.text = text
            readbackSettingLabels[row.control.id]?.text = text
        }
        surfaces.values.forEach { surface ->
            if (surface.port != null) surface.waiting.text = I18n.t("studio.display.waiting")
        }
        catalog.firstOrNull { it.id == selectedView }?.let { panel ->
            viewTitleProperty.set(I18n.t("studio.view.window_title", panelShortLabel(panel)))
        }
        updateButtons()
    }

    private fun updateFacesSectionTitle() {
        val activeTheme = bodyTheme
        facesSectionTitle.text = if (activeTheme.id == "origin" && activeTheme.panelCount == 6) {
            I18n.t("studio.faces.title.six")
        } else {
            I18n.t("studio.faces.title.n", activeTheme.panelCount, activeTheme.modelLabel)
        }
    }

    private fun rebuildFacesForActiveTheme() {
        faceWindows.values.forEach { it.close() }
        faceWindows.clear()
        regions.clear()
        canvases.clear()
        dragHandles.clear()
        viewToggles.clear()
        buttons.clear()
        proxyButtons.clear()
        settingBodyButtons.clear()
        adcBodyButtons.clear()
        viewModeBodyButton = null
        surfaces.clear()
        layers.clear()
        dynamicNodes.clear()
        dynamicRotates.clear()
        bodyModeDialIndicators.clear()
        bodyStillMovieIndicators.clear()
        bodyFocusSegments.clear()
        bodyFocusModeIndicators.clear()
        togglesGrid.children.clear()

        val activeTheme = bodyTheme
        activeTheme.panels.forEach { panel ->
            regions[panel.id] = view(panel)
            val faceStage = Stage().apply {
                initOwner(owner); initStyle(StageStyle.UNDECORATED); title = "${activeTheme.modelLabel} · ${panelShortLabel(panel)}"
                val handle = Label(panelCaption(panel)).apply {
                    styleClass += "studio-face-handle"
                    minHeight = 18.0; prefHeight = 18.0; maxHeight = 18.0; maxWidth = Double.MAX_VALUE
                    cursor = javafx.scene.Cursor.MOVE
                    tooltip = Tooltip().apply { textProperty().bind(I18n.bind("studio.faces.drag_tooltip")) }
                }
                var dx = 0.0; var dy = 0.0
                handle.setOnMousePressed { e -> if (e.button == MouseButton.PRIMARY) { dx = e.screenX - x; dy = e.screenY - y; e.consume() } }
                handle.setOnMouseDragged { e -> val b = screen.visualBounds; x = (e.screenX - dx).coerceIn(b.minX, b.maxX - width); y = (e.screenY - dy).coerceIn(b.minY, b.maxY - height); e.consume() }
                handle.setOnMouseReleased { e -> e.consume() }
                dragHandles[panel.id] = handle
                val close = Button("×").apply {
                    styleClass += "studio-face-close"
                    minHeight = 18.0; prefHeight = 18.0; maxHeight = 18.0
                    setOnAction { setFaceVisible(panel.id, false) }
                    accessibleText = I18n.t("studio.faces.hide_accessible", panelShortLabel(panel))
                }
                val bar = HBox(handle, close).apply { HBox.setHgrow(handle, Priority.ALWAYS); styleClass += "studio-face-bar" }
                val pane = BorderPane(regions.getValue(panel.id), bar, null, null, null).apply { styleClass += "studio-face-frame" }
                scene = Scene(pane).also { UiThemeRegistry.applyToScene(it, uiTheme) }
                minWidth = 120.0; minHeight = 100.0
                setOnCloseRequest { event -> event.consume(); setFaceVisible(panel.id, false) }
                focusedProperty().addListener { _, _, on ->
                    if (on) { if ("studio-face-frame-focused" !in pane.styleClass) pane.styleClass += "studio-face-frame-focused" }
                    else pane.styleClass.remove("studio-face-frame-focused")
                    if (!on && !closed) { if (panel.id == "rear") cancelAndReleaseTouch(); releaseTransientViewMode(); releaseTransientMomentarySettings(); cancelPendingReleases(); inputs.releaseTransient() }
                }
                showingProperty().addListener { _, _, on ->
                    if (!on && !closed) { if (panel.id == "rear") cancelAndReleaseTouch(); releaseTransientViewMode(); releaseTransientMomentarySettings(); cancelPendingReleases(); inputs.releaseTransient() }
                    syncingToggle = true; viewToggles[panel.id]?.isSelected = on; syncingToggle = false
                }
                scene.addEventFilter(MouseEvent.MOUSE_RELEASED) { if (!closed) { releaseTouchMouseTransient(); releaseViewModeMouseTransient(); releaseMomentarySettingMouseTransient(); releaseMouseTransient() } }
                scene.addEventFilter(KeyEvent.KEY_PRESSED) { if (!closed) handleViewfinderKeyEvent(it) }
                scene.addEventFilter(KeyEvent.KEY_RELEASED) { if (!closed) handleViewfinderKeyEvent(it) }
            }
            if (panel.id == "front") faceStage.scene.addEventFilter(MouseEvent.ANY) { event ->
                if (frontHitStart != null && event.eventType in setOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED) && frontMouseEvents.size < 16) {
                    var target = event.target as? Node
                    while (target != null && target.id == null) target = target.parent
                    frontMouseEvents += mapOf("type" to event.eventType.name, "targetId" to target?.id, "sceneX" to event.sceneX, "sceneY" to event.sceneY, "screenX" to event.screenX, "screenY" to event.screenY)
                }
            }
            faceWindows[panel.id] = faceStage
            viewToggles[panel.id] = CheckBox(panelShortLabel(panel)).apply {
                id = "show-face-" + panel.id
                selectedProperty().addListener { _, _, on -> if (!syncingToggle) setFaceVisible(panel.id, on) }
            }
        }
        // Keep floating EVF window functional even on body themes without a rear-mounted EVF (e.g., Hasselblad 907X).
        if (surfaceForPort("display.evf", "rear-evf") == null) {
            val evfImage = ImageView().apply { isPreserveRatio = true }
            val evfWaiting = Label(I18n.t("studio.display.waiting"))
            surfaces["rear-evf"] = createSurface("__evf_detached__", "display.evf", evfImage, evfWaiting, StackPane(evfImage, evfWaiting))
        }
        if (::evfWindow.isInitialized) {
            evfWindow.rebindSourceImageView(requireNotNull(surfaceForPort("display.evf", "rear-evf")).image)
            updateViewfinderStatus()
        }
        updateFacesSectionTitle()
        viewToggles.values.forEachIndexed { index, check -> togglesGrid.add(check, index % 2, index / 2) }
        if (observedShoulderBacklightOn) {
            surfaceForPort("display.shoulder", "top-shoulder")?.container?.let { container ->
                if (ShoulderLcdRenderer.BACKLIT_STYLE_CLASS !in container.styleClass) {
                    container.styleClass += ShoulderLcdRenderer.BACKLIT_STYLE_CLASS
                }
            }
        }
    }

    /** Instant runtime hot-switch (or hot-reload when [forceReload] is true) to [nextTheme] without restarting. */
    fun applyBodyTheme(nextTheme: BodyTheme, forceReload: Boolean = false) {
        check(Platform.isFxApplicationThread())
        if (closed || (!forceReload && nextTheme.id == bodyTheme.id)) return
        val wasShowingAny = faceWindows.values.any { it.isShowing }
        val fitScaleToUse = lastFitScale
        cancelAndReleaseTouch()
        releaseTransientViewMode()
        releaseTransientMomentarySettings()
        cancelPendingReleases()
        inputs.releaseTransient()

        bodyTheme = nextTheme
        shootingWorkspace = nextTheme.id == "origin"
        rebuildFacesForActiveTheme()
        selectView(nextTheme.defaultViewId())
        updateButtons()
        lastSnapshot?.let(::applyLayersFromSnapshot)
        refreshAllBodyIndicators()
        if (wasShowingAny || (shootingWorkspace && owner.isShowing && !forceReload)) {
            showDefault(fitScaleToUse)
        } else {
            arrangeWindows(fitScaleToUse)
        }
    }

    /** Instant runtime hot-switch of [UiTheme] across owner stage, all active face stages, and EVF window. */
    fun applyUiTheme(nextUiTheme: UiTheme) {
        check(Platform.isFxApplicationThread())
        if (closed) return
        uiTheme = nextUiTheme
        owner.scene?.let { UiThemeRegistry.applyToScene(it, nextUiTheme) }
        faceWindows.values.forEach { stage ->
            stage.scene?.let { UiThemeRegistry.applyToScene(it, nextUiTheme) }
        }
        if (::evfWindow.isInitialized) {
            evfWindow.applyUiTheme(nextUiTheme)
        }
    }

    fun selectView(id: String) {
        check(Platform.isFxApplicationThread());require(id in regions)
        if(selectedView!=id) { cancelAndReleaseTouch(); cancelPendingReleases(); inputs.releaseTransient() }
        selectedView=id;viewTitleProperty.set(I18n.t("studio.view.window_title", panelShortLabel(catalog.single { it.id==id })))
    }
    fun setFaceVisible(id: String, visible: Boolean) {
        check(Platform.isFxApplicationThread());check(!closed)
        if (bodyTheme.id == "origin" && id !in StudioLayout.shootingWorkspace.faces) {
            val hasExtraFaces = visible || faceWindows.any { (other, stage) ->
                other != id && other !in StudioLayout.shootingWorkspace.faces && stage.isShowing
            }
            val useShootingWorkspace = !hasExtraFaces
            if (shootingWorkspace != useShootingWorkspace) {
                shootingWorkspace = useShootingWorkspace
                arrangeWindows(lastFitScale)
            }
        }
        setFaceShowing(id, visible)
    }
    private fun setFaceShowing(id: String, visible: Boolean) {
        val stage=faceWindows.getValue(id)
        if(visible) { if(!stage.isShowing) { stage.show(); position(id,stage) } }
        else { if (id == "rear") cancelAndReleaseTouch(); cancelPendingReleases(); inputs.releaseTransient();stage.hide() }
        syncingToggle=true;viewToggles.getValue(id).isSelected=stage.isShowing;syncingToggle=false
    }
    private fun layoutWidth(): Double {
        val layout = windowLayout
        return (layout.configuration.x + 10) * arrangeScale + maxOf(layout.entryMinWidth, layout.configuration.width * arrangeScale)
    }
    private fun layoutHeight(): Double {
        val layout = windowLayout
        val config = layout.configuration
        return maxOf(layout.height * arrangeScale, config.y * arrangeScale + maxOf(270.0, config.height * arrangeScale))
    }
    private fun position(id: String, stage: Stage) {
        val layout = windowLayout
        val b = layout.faces[id]?.slot ?: return
        val v = screen.visualBounds
        val ox = v.minX + (v.width - layoutWidth()) / 2
        val oy = v.minY + (v.height - layoutHeight()) / 2
        stage.x = ox + b.x * arrangeScale; stage.y = oy + b.y * arrangeScale
        stage.width = b.width * arrangeScale; stage.height = b.height * arrangeScale
    }
    private var arrangementRevision = 0L

    fun arrangeWindows(fitScale:Double=1.0) {
        require(fitScale.isFinite() && fitScale in .5..1.0)
        check(Platform.isFxApplicationThread())
        lastFitScale = fitScale
        val layout = windowLayout
        val v = screen.visualBounds
        arrangeScale = minOf(v.width / layout.width, v.height / layout.height, (v.width - layout.entryMinWidth) / (layout.configuration.x + 10)) * fitScale
        faceWindows.forEach { (id, stage) -> position(id, stage) }
        val b = layout.configuration
        owner.width = maxOf(layout.entryMinWidth, b.width * arrangeScale)
        owner.height = maxOf(270.0, b.height * arrangeScale)
        require(layoutWidth() <= v.width + 1 && owner.height <= v.height) { "Primary display cannot fit a usable configuration main entry" }
        owner.x = v.minX + (v.width - layoutWidth()) / 2 + b.x * arrangeScale
        owner.y = v.minY + (v.height - layoutHeight()) / 2 + b.y * arrangeScale
        // A native resize notification can restore the old screen position on Windows.
        // Check the placement after this pulse has delivered the new client bounds.
        val revision = ++arrangementRevision
        val scene = owner.scene ?: return
        val placements = (faceWindows.values + owner).associateWith { it.x to it.y }
        val afterLayout = object : Runnable {
            override fun run() {
                scene.removePostLayoutPulseListener(this)
                Platform.runLater {
                    if (closed || arrangementRevision != revision) return@runLater
                    for ((stage, target) in placements) {
                        if (stage.isShowing) {
                            if (kotlin.math.abs(stage.x - target.first) > 1.0) stage.x = target.first
                            if (kotlin.math.abs(stage.y - target.second) > 1.0) stage.y = target.second
                        }
                    }
                }
            }
        }
        scene.addPostLayoutPulseListener(afterLayout)
        Platform.requestNextPulse()
    }
    fun showDefault(fitScale: Double = lastFitScale) {
        shootingWorkspace = bodyTheme.id == "origin"
        showLayout(fitScale)
    }

    fun showAll(fitScale: Double = lastFitScale) {
        shootingWorkspace = false
        showLayout(fitScale)
    }

    private fun showLayout(fitScale: Double) {
        arrangeWindows(fitScale)
        faceWindows.keys.forEach { setFaceShowing(it, it in windowLayout.faces) }
        arrangeWindows(fitScale)
    }
    private fun initInputAndSettingRows() {
        PanelCatalog.buttonKeys.forEach { key ->
            val control = originControlsByButtonKey.getValue(key)
            val button = keyButton(control, onBody = false).apply {
                text = compactKeyLabel(key, control)
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            }
            val lockBtn = if (inputs.isLatching(key)) {
                null
            } else {
                ToggleButton(UNLOCK_ICON).apply {
                    id = "lock-$key"
                    styleClass += "studio-lock-btn-compact"
                    isFocusTraversable = false
                    tooltip = Tooltip()
                    setOnAction {
                        if (!syncingLockButtons) toggleButtonLock(key)
                    }
                }
            }
            inputRows[key] = InputRow(
                button,
                lockBtn,
                Label().apply {
                    id = "request-$key"
                    styleClass += "studio-telemetry-val"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                },
                Label().apply {
                    id = "readback-$key"
                    styleClass += "studio-telemetry-val"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    alignment = javafx.geometry.Pos.CENTER_RIGHT
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                },
            )
        }
        latchingSettingControls.forEach { control ->
            val button = settingToggleButton(control, onBody = false).apply {
                text = compactKeyLabel(control.id, control)
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            }
            settingRows[control.id] = SettingRow(
                control,
                button,
                Label().apply {
                    id = "request-${control.id}"
                    styleClass += "studio-telemetry-val"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                },
                Label().apply {
                    id = "readback-${control.id}"
                    styleClass += "studio-telemetry-val"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    alignment = javafx.geometry.Pos.CENTER_RIGHT
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                },
            )
        }
    }

    private fun buttonCellForKey(key: String): Node {
        val row = inputRows.getValue(key)
        val lockBtn = row.lockButton
        return if (lockBtn != null) {
            HBox(2.0, row.button, lockBtn).apply {
                minWidth = 0.0
                alignment = javafx.geometry.Pos.CENTER_LEFT
                HBox.setHgrow(row.button, Priority.ALWAYS)
            }
        } else {
            row.button
        }
    }

    private fun twoColButtonGrid(nodes: List<Node>): GridPane = GridPane().apply {
        hgap = 5.0
        vgap = 4.0
        minWidth = 0.0
        columnConstraints.addAll(
            ColumnConstraints().apply { percentWidth = 50.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
            ColumnConstraints().apply { percentWidth = 50.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
        )
        nodes.forEachIndexed { idx, node ->
            add(node, idx % 2, idx / 2)
        }
    }

    private fun matrixSubhead(i18nKey: String): Label = Label().apply {
        styleClass += listOf("studio-table-head", "studio-matrix-subhead")
        maxWidth = Double.MAX_VALUE
        textProperty().bind(I18n.bind(i18nKey))
    }

    private fun buildPhysicalButtonsMatrix(): VBox {
        val shutterPowerKeys = listOf("power", "shutterHalf", "shutterFull", "afOn")
            .filter { it in inputRows }
        val shutterPowerSection = VBox(
            4.0,
            matrixSubhead("studio.matrix.group.shutter_power"),
            twoColButtonGrid(shutterPowerKeys.map(::buttonCellForKey)),
        ).apply { id = "physical-group-shutter-power" }

        val dpadGrid = GridPane().apply {
            id = "physical-dpad-grid"
            styleClass += "studio-dpad-grid"
            hgap = 4.0
            vgap = 4.0
            minWidth = 0.0
            repeat(3) {
                columnConstraints.add(ColumnConstraints().apply { percentWidth = 33.333; minWidth = 0.0; hgrow = Priority.ALWAYS })
            }
            if ("up" in inputRows) add(buttonCellForKey("up"), 1, 0)
            if ("left" in inputRows) add(buttonCellForKey("left"), 0, 1)
            if ("focusStickPress" in inputRows) add(buttonCellForKey("focusStickPress"), 1, 1)
            if ("right" in inputRows) add(buttonCellForKey("right"), 2, 1)
            if ("down" in inputRows) add(buttonCellForKey("down"), 1, 2)
        }
        val navKeys = listOf("menu", "back", "play", "drive", "delete", "fn4")
            .filter { it in inputRows }
        val navigationSection = VBox(
            5.0,
            matrixSubhead("studio.matrix.group.navigation"),
            dpadGrid,
            twoColButtonGrid(navKeys.map(::buttonCellForKey)),
        ).apply { id = "physical-group-navigation" }

        val fnAdcSection = VBox(
            4.0,
            matrixSubhead("studio.matrix.group.fn_adc"),
            quickAdcButtonGrid(),
        ).apply { id = "physical-group-fn-adc" }

        return VBox(8.0, shutterPowerSection, navigationSection, fnAdcSection).apply {
            minWidth = 0.0
        }
    }

    private fun buildRawReadbackBody(): VBox {
        val rawSwitchNodes = mutableListOf<Node>()
        listOf("movieMode", "focusMode", "frontDialPress", "rearDialPress")
            .filter { it in inputRows }
            .forEach { rawSwitchNodes += buttonCellForKey(it) }
        settingRows.values.forEach { row ->
            rawSwitchNodes += row.gridButton
        }
        rawSwitchNodes += HBox(
            4.0,
            Label().apply {
                styleClass += "studio-table-head"
                minWidth = Region.USE_PREF_SIZE
                textProperty().bind(I18n.bind("studio.dials.mode_dial"))
            },
            quickModeDialCombo.apply { HBox.setHgrow(this, Priority.ALWAYS) },
        ).apply {
            minWidth = 0.0
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
        val rawSwitchesSection = VBox(
            4.0,
            matrixSubhead("studio.readback.group.raw_switches"),
            twoColButtonGrid(rawSwitchNodes),
        ).apply { id = "raw-readback-switches" }

        val table = GridPane().apply {
            id = "raw-readback-table"
            styleClass += "studio-readback-table"
            hgap = 4.0
            vgap = 1.0
            minWidth = 0.0
            columnConstraints.addAll(
                ColumnConstraints().apply { percentWidth = 36.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
                ColumnConstraints().apply { percentWidth = 38.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
                ColumnConstraints().apply {
                    percentWidth = 26.0
                    minWidth = 0.0
                    hgrow = Priority.ALWAYS
                    halignment = javafx.geometry.HPos.RIGHT
                },
            )
            addRow(
                0,
                Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("studio.table.col.key")) },
                Label().apply { styleClass += "studio-table-head"; textProperty().bind(I18n.bind("studio.table.col.request")) },
                Label().apply {
                    styleClass += "studio-table-head"
                    maxWidth = Double.MAX_VALUE
                    alignment = javafx.geometry.Pos.CENTER_RIGHT
                    textProperty().bind(I18n.bind("studio.table.col.readback"))
                },
            )
            val orderedKeys = listOf(
                "power", "shutterHalf", "shutterFull", "afOn",
                "up", "down", "left", "right", "focusStickPress",
                "menu", "back", "play", "drive", "delete", "fn4",
                "movieMode", "focusMode", "frontDialPress", "rearDialPress",
            ).filter { it in inputRows } + inputRows.keys.filter { it !in setOf(
                "power", "shutterHalf", "shutterFull", "afOn",
                "up", "down", "left", "right", "focusStickPress",
                "menu", "back", "play", "drive", "delete", "fn4",
                "movieMode", "focusMode", "frontDialPress", "rearDialPress",
            ) }
            var rowIndex = 1
            orderedKeys.forEach { key ->
                val row = inputRows.getValue(key)
                val ctrl = originControlsByButtonKey.getValue(key)
                val nameLabel = Label(compactKeyLabel(key, ctrl)).apply {
                    styleClass += "studio-readback-key"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                }
                readbackKeyLabels[key] = nameLabel
                if (rowIndex % 2 == 0) {
                    nameLabel.styleClass += "studio-readback-cell-alt"
                    row.request.styleClass += "studio-readback-cell-alt"
                    row.readback.styleClass += "studio-readback-cell-alt"
                }
                row.request.styleClass += "studio-readback-cell"
                row.readback.styleClass += "studio-readback-cell"
                addRow(rowIndex++, nameLabel, row.request, row.readback)
            }
            settingRows.values.forEach { row ->
                val nameLabel = Label(compactKeyLabel(row.control.id, row.control)).apply {
                    styleClass += "studio-readback-key"
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                    textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                }
                readbackSettingLabels[row.control.id] = nameLabel
                if (rowIndex % 2 == 0) {
                    nameLabel.styleClass += "studio-readback-cell-alt"
                    row.request.styleClass += "studio-readback-cell-alt"
                    row.readback.styleClass += "studio-readback-cell-alt"
                }
                row.request.styleClass += "studio-readback-cell"
                row.readback.styleClass += "studio-readback-cell"
                addRow(rowIndex++, nameLabel, row.request, row.readback)
            }
        }
        val liveReadbackSection = VBox(
            4.0,
            matrixSubhead("studio.readback.group.live_table"),
            table,
        ).apply { id = "raw-readback-live-section" }

        return VBox(8.0, rawSwitchesSection, liveReadbackSection, pendingText).apply {
            minWidth = 0.0
        }
    }
    private fun formatRequest(request: StudioInputState.Request, onLabel: String, offLabel: String): String {
        val action = if (request.down) onLabel else offLabel
        return when (request.submission) {
            StudioInputState.Submission.PENDING -> I18n.t("studio.submission.pending", action)
            StudioInputState.Submission.APPLIED -> I18n.t("studio.submission.applied", action)
            StudioInputState.Submission.FAILED -> I18n.t("studio.submission.failed", action)
        }
    }
    private fun setStyleClassPresent(node: Node?, styleName: String, present: Boolean) {
        if (node == null) return
        if (present) {
            if (styleName !in node.styleClass) node.styleClass += styleName
        } else {
            node.styleClass.remove(styleName)
        }
    }
    private fun applyLockToggleVisual(lockBtn: ToggleButton, locked: Boolean, disabled: Boolean, displayName: String) {
        if (lockBtn.isDisable != disabled) lockBtn.isDisable = disabled
        if (lockBtn.isSelected != locked) lockBtn.isSelected = locked
        val nextText = if (locked) LOCK_ICON else UNLOCK_ICON
        if (lockBtn.text != nextText) lockBtn.text = nextText
        setStyleClassPresent(lockBtn, "studio-lock-btn-active", locked)
        val tipText = I18n.t(if (locked) "studio.lock.btn.unlock.tooltip" else "studio.lock.btn.lock.tooltip", displayName)
        lockBtn.tooltip?.let { if (it.text != tipText) it.text = tipText }
        lockBtn.accessibleText = I18n.t(if (locked) "studio.lock.btn.accessible.unlock" else "studio.lock.btn.accessible.lock", displayName)
    }
    private fun lockedDisplayLabels(): List<String> {
        val names = mutableListOf<String>()
        inputs.lockedKeys().forEach { key ->
            names += originControlsByButtonKey[key]?.let(::controlLabel) ?: key
        }
        momentarySettingSources.forEach { (controlId, sources) ->
            if (StudioInputState.LOCK_SOURCE in sources) {
                names += momentarySettingControls[controlId]?.let(::controlLabel) ?: controlId
            }
        }
        if (isViewModeLocked()) {
            names += I18n.t("evf.window.view_mode")
        }
        return names
    }
    private fun syncLockUi() {
        syncingLockButtons = true
        try {
            inputRows.forEach { (key, row) ->
                val locked = inputs.isLocked(key)
                val disabled = !inputs.supports(key)
                setStyleClassPresent(row.button, "studio-key-locked", locked)
                setStyleClassPresent(buttons[key], "studio-key-locked", locked)
                proxyButtons[key]?.forEach { setStyleClassPresent(it, "studio-key-locked", locked) }
                row.lockButton?.let { lockBtn ->
                    val label = originControlsByButtonKey[key]?.let(::controlLabel) ?: key
                    applyLockToggleVisual(lockBtn, locked, disabled, label)
                }
            }
            val canSendAdc = hasBooleanSettingSender()
            adcQuickButtons.forEach { (controlId, btn) ->
                val locked = isMomentarySettingLocked(controlId)
                setStyleClassPresent(btn, "studio-key-locked", locked)
                setStyleClassPresent(adcBodyButtons[controlId], "studio-key-locked", locked)
                adcLockButtons[controlId]?.let { lockBtn ->
                    val label = momentarySettingControls[controlId]?.let(::controlLabel) ?: controlId
                    applyLockToggleVisual(lockBtn, locked, !canSendAdc, label)
                }
            }
            val vmLocked = isViewModeLocked()
            setStyleClassPresent(quickViewModeBtn, "studio-key-locked", vmLocked)
            setStyleClassPresent(viewModeBodyButton, "studio-key-locked", vmLocked)
            applyLockToggleVisual(viewModeLockBtn, vmLocked, sendViewMode == null, I18n.t("evf.window.view_mode"))
        } finally {
            syncingLockButtons = false
        }
        val lockedNames = lockedDisplayLabels()
        val lockedCount = lockedNames.size
        val summaryText = if (lockedCount == 0) {
            I18n.t("studio.lock.bar.none")
        } else {
            I18n.t("studio.lock.bar.active", lockedCount, lockedNames.joinToString(", "))
        }
        if (comboLockSummaryLabel.text != summaryText) comboLockSummaryLabel.text = summaryText
        if (comboLockUnlockAllBtn.isDisable != (lockedCount == 0)) comboLockUnlockAllBtn.isDisable = (lockedCount == 0)
        val bannerVisible = lockedCount > 0
        if (comboLockBanner.isVisible != bannerVisible) {
            comboLockBanner.isVisible = bannerVisible
            comboLockBanner.isManaged = bannerVisible
        }
        if (bannerVisible) {
            val bannerText = I18n.t("studio.lock.banner.active", lockedCount, lockedNames.joinToString(", "))
            if (comboLockBannerLabel.text != bannerText) comboLockBannerLabel.text = bannerText
        }
        updateControlsToggleText()
        if (!syncingLatchMode) {
            syncingLatchMode = true
            try {
                if (comboLockModeToggle.isSelected != inputs.latchMode) comboLockModeToggle.isSelected = inputs.latchMode
                if (latch.isSelected != inputs.latchMode) latch.isSelected = inputs.latchMode
            } finally {
                syncingLatchMode = false
            }
        }
    }
    private fun updateButtons() {
        applyDynamicButtonHighlights()
        val onText = I18n.t("studio.state.on")
        val offText = I18n.t("studio.state.off")
        val pressedText = I18n.t("studio.state.pressed")
        val releasedText = I18n.t("studio.state.released")
        val unreadText = I18n.t("studio.state.unread")
        val unavailableText = I18n.t("studio.state.unavailable")
        inputRows.forEach { (key, row) ->
            val observed = inputs.observed(key)
            val latching = inputs.isLatching(key)
            val onLabel = if (latching) onText else pressedText
            val offLabel = if (latching) offText else releasedText
            val disabled = !inputs.supports(key)
            val bodyBtn = buttons[key]
            if (bodyBtn != null && bodyBtn.isDisable != disabled) bodyBtn.isDisable = disabled
            if (row.button.isDisable != disabled) row.button.isDisable = disabled
            val selected = observed == true
            if (bodyBtn != null && bodyBtn.isSelected != selected) bodyBtn.isSelected = selected
            proxyButtons[key]?.forEach { proxy ->
                if (proxy.isDisable != disabled) proxy.isDisable = disabled
                if (proxy.isSelected != selected) proxy.isSelected = selected
            }
            if (row.button.isSelected != selected) row.button.isSelected = selected
            val nextReadback = when (observed) { true -> onLabel; false -> offLabel; null -> if (inputs.supports(key)) unreadText else unavailableText }
            if (row.readback.text != nextReadback) row.readback.text = nextReadback
            setStyleClassPresent(row.readback, "studio-readback-active", observed == true)
            val nextRequest = inputs.lastRequest(key)?.let { formatRequest(it, onLabel, offLabel) } ?: "—"
            if (row.request.text != nextRequest) row.request.text = nextRequest
        }
        settingRows.forEach { (id, row) ->
            val observed = observedSettings[id]
            val selected = observed == true
            settingBodyButtons[id]?.let { if (it.isSelected != selected) it.isSelected = selected }
            if (row.gridButton.isSelected != selected) row.gridButton.isSelected = selected
            val nextReadback = when (observed) { true -> onText; false -> offText; null -> unreadText }
            if (row.readback.text != nextReadback) row.readback.text = nextReadback
            setStyleClassPresent(row.readback, "studio-readback-active", observed == true)
            val nextRequest = settingRequests[id]?.let { formatRequest(it, onText, offText) } ?: "—"
            if (row.request.text != nextRequest) row.request.text = nextRequest
        }
        syncLockUi()
        val pendingSettings = settingRequests.values.count { it.submission == StudioInputState.Submission.PENDING }
        val totalPending = inputs.pending + pendingSettings
        val nextPending = when {
            totalPending > 0 -> I18n.t("studio.pending.count", totalPending)
            inputs.failureCount > 0 -> I18n.t("studio.pending.failed")
            showingStillModeTransition -> I18n.t("studio.mode.switching_to_still")
            else -> ""
        }
        if (pendingText.text != nextPending) pendingText.text = nextPending
        val showPending = nextPending.isNotEmpty()
        if (pendingText.isVisible != showPending) {
            pendingText.isVisible = showPending
            pendingText.isManaged = showPending
        }
        val moviePending = inputs.lastRequest("movieMode")?.submission == StudioInputState.Submission.PENDING
        val effectiveMovie = effectiveBodyButtonState("movieMode")
        if (moviePending || inputs.observed("movieMode") != null) {
            shootingControlsPane.syncMovieModeFromStudio(effectiveMovie)
        }
        updateStillMovieBodyIndicator(effectiveMovie)
        updateViewfinderStatus()
    }
    private fun toggleSetting(c: PanelControl) {
        lastInspectedControlId = c.id
        inspect(c)
        val componentId = c.componentId ?: return
        val settingKey = c.settingKey ?: return
        val sender = sendSetting ?: return
        val previous = desiredSettings[c.id] ?: (observedSettings[c.id] == true)
        val next = !previous
        desiredSettings[c.id] = next
        val version = (settingVersions[c.id] ?: 0L) + 1L
        settingVersions[c.id] = version
        settingRequests[c.id] = StudioInputState.Request(next, StudioInputState.Submission.PENDING)
        updateButtons()
        val result = runCatching { sender(componentId, settingKey, next) }.getOrElse { CompletableFuture.failedFuture<Any>(it) }
        result.whenComplete { _, error ->
            val applyResult = {
                if (settingVersions[c.id] == version) {
                    settingRequests[c.id] = StudioInputState.Request(next, if (error == null) StudioInputState.Submission.APPLIED else StudioInputState.Submission.FAILED)
                    if (error != null) desiredSettings[c.id] = previous
                }
                if (error != null) failure(error)
                updateButtons()
            }
            if (Platform.isFxApplicationThread()) applyResult() else Platform.runLater(applyResult)
        }
    }
    private fun settingToggleButton(c: PanelControl, onBody: Boolean = true): ToggleButton = ToggleButton().apply {
        id = if (onBody) "inspect-" + c.id else "setting-toggle-" + c.id
        if (onBody) {
            styleClass += "studio-hotspot"
            settingBodyButtons[c.id] = this
        } else {
            styleClass += "studio-setting-switch"
        }
        accessibleText = I18n.t("studio.accessible.latch_setting", controlLabel(c))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bindDynamic { I18n.t("studio.tip.setting_toggle", tip(c)) }) }
        setOnAction {
            toggleSetting(c)
            isSelected = observedSettings[c.id] == true
        }
        addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.SECONDARY) {
                lastInspectedControlId = c.id
                inspect(c)
                if (c.componentId != null && c.bindingStatus.startsWith("host-")) configure(c.componentId)
                event.consume()
            }
        }
    }
    private fun tip(c: PanelControl) = controlLabel(c)
    private fun inspectButton(c: PanelControl) = Button().apply {
        id = "inspect-" + c.id; styleClass += "studio-hotspot"
        accessibleText = I18n.t("studio.accessible.inspect", controlLabel(c))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bindDynamic { tip(c) }) }
        setOnAction {
            lastInspectedControlId=c.id
            inspect(c)
        }
    }
    fun setTouchEnabled(enabled: Boolean) {
        if (!enabled) cancelAndReleaseTouch()
        touchEnabled = enabled
    }
    private fun cancelPendingSwipeRelease() {
        pendingSwipeRelease?.close()
        pendingSwipeRelease = null
    }
    private fun releaseActiveTouch() {
        cancelPendingSwipeRelease()
        touchSwipeDispatched = false
        if (!touchActive) return
        touchActive = false
        sendTouch?.invoke(lastTouchCoord.x, lastTouchCoord.y, false)?.whenComplete { _, err ->
            if (err != null) failure(err)
        }
    }
    private fun dispatchTouch(coord: TouchPointCoord, pressed: Boolean) {
        lastTouchCoord = coord
        touchActive = pressed
        sendTouch?.invoke(coord.x, coord.y, pressed)?.whenComplete { _, err ->
            val appliedTick = if (pressed && err == null) virtualTick?.invoke() else null
            if (err != null) failure(err)
            else if (appliedTick != null) {
                val apply = { if (!closed && ::touchHold.isInitialized) touchHold.markApplied(TOUCH_HOLD_KEY, appliedTick) }
                if (Platform.isFxApplicationThread()) apply() else Platform.runLater(apply)
            }
        }
    }
    private fun pressTouch(coord: TouchPointCoord) {
        cancelPendingSwipeRelease()
        if (::touchHold.isInitialized && touchHold.pendingReleaseCount() > 0) {
            cancelAndReleaseTouch()
        }
        touchPressCoord = coord
        touchSwipeDispatched = false
        lastTouchCoord = coord
        touchHold.press(TOUCH_MOUSE_SOURCE, TOUCH_HOLD_KEY)
    }
    private fun releaseTouch() {
        if (!touchActive && (!::touchHold.isInitialized || touchHold.pendingReleaseCount() == 0)) return
        touchHold.release(TOUCH_MOUSE_SOURCE, TOUCH_HOLD_KEY)
    }
    private fun releaseSwipeTouch(waitOnePoll: Boolean) {
        cancelPendingSwipeRelease()
        if (::touchHold.isInitialized) touchHold.cancelPendingReleases()
        if (!touchActive) return
        val tickSource = virtualTick
        if (!waitOnePoll || !momentaryHoldPolicy.enabled || tickSource == null ||
            (isSessionAdvancing != null && !isSessionAdvancing.invoke())
        ) {
            releaseActiveTouch()
            return
        }
        val startTick = tickSource.invoke()
        val deadlineNanos = System.nanoTime() + SWIPE_RELEASE_MAX_WALL_MS * 1_000_000L
        lateinit var poll: () -> Unit
        poll = {
            if (closed || !touchActive) {
                cancelPendingSwipeRelease()
            } else if (
                (isSessionAdvancing != null && !isSessionAdvancing.invoke()) ||
                (tickSource.invoke() - startTick >= MomentaryHoldPolicy.CALIBRATED_TOUCH_SWIPE_STEP_VIRTUAL_TICKS) ||
                (System.nanoTime() >= deadlineNanos)
            ) {
                releaseActiveTouch()
            } else {
                val timer = PauseTransition(Duration.millis(momentaryHoldPolicy.pollIntervalMs.toDouble())).apply {
                    setOnFinished { poll() }
                }
                pendingSwipeRelease = AutoCloseable { timer.stop() }
                timer.play()
            }
        }
        val timer = PauseTransition(Duration.millis(momentaryHoldPolicy.pollIntervalMs.toDouble())).apply {
            setOnFinished { poll() }
        }
        pendingSwipeRelease = AutoCloseable { timer.stop() }
        timer.play()
    }
    private fun releaseTouchMouseTransient() {
        cancelPendingSwipeRelease()
        if (::touchHold.isInitialized) {
            touchHold.releaseMouseTransient { releaseActiveTouch() }
        } else {
            releaseActiveTouch()
        }
    }
    private fun cancelAndReleaseTouch() {
        cancelPendingSwipeRelease()
        if (::touchHold.isInitialized) touchHold.cancelPendingReleases()
        releaseActiveTouch()
    }
    private fun toggleEyeSensor() {
        val control = eyeSensorControl ?: return
        toggleSetting(control)
    }
    fun isButtonLocked(key: String): Boolean = inputs.isLocked(key)
    fun lockButton(key: String): Boolean {
        if (!inputs.supports(key) || inputs.isLatching(key)) return false
        if (::momentaryHold.isInitialized) momentaryHold.cancelAndReleaseOpposite(key)
        val locked = inputs.lock(key)
        if (locked) {
            val label = originControlsByButtonKey[key]?.let(::controlLabel) ?: key
            onStatusMessage(I18n.t("studio.lock.status.locked", label))
        }
        return locked
    }
    fun unlockButton(key: String): Boolean {
        if (::momentaryHold.isInitialized) momentaryHold.flushPendingReleasesForKey(key)
        val unlocked = inputs.unlock(key)
        if (unlocked) {
            val label = originControlsByButtonKey[key]?.let(::controlLabel) ?: key
            onStatusMessage(I18n.t("studio.lock.status.unlocked", label))
        }
        return unlocked
    }
    fun toggleButtonLock(key: String): Boolean = if (isButtonLocked(key)) { unlockButton(key); false } else lockButton(key)

    fun isViewModeLocked(): Boolean = StudioInputState.LOCK_SOURCE in viewModeSources
    fun lockViewMode(): Boolean {
        if (sendViewMode == null) return false
        val added = viewModeSources.add(StudioInputState.LOCK_SOURCE)
        dispatchViewMode(true)
        syncLockUi()
        if (added) onStatusMessage(I18n.t("studio.lock.status.locked", I18n.t("evf.window.view_mode")))
        return true
    }
    fun unlockViewMode(): Boolean {
        if (::viewModeHold.isInitialized) viewModeHold.flushPendingReleasesForKey(VIEW_MODE_HOLD_KEY)
        if (!viewModeSources.remove(StudioInputState.LOCK_SOURCE)) return false
        if (viewModeSources.isEmpty()) dispatchViewMode(false)
        syncLockUi()
        onStatusMessage(I18n.t("studio.lock.status.unlocked", I18n.t("evf.window.view_mode")))
        return true
    }
    fun toggleViewModeLock(): Boolean = if (isViewModeLocked()) { unlockViewMode(); false } else lockViewMode()

    fun isMomentarySettingLocked(controlId: String): Boolean =
        StudioInputState.LOCK_SOURCE in momentarySettingSources[controlId].orEmpty()
    fun lockMomentarySetting(controlId: String): Boolean {
        val control = momentarySettingControls[controlId] ?: return false
        if (!hasBooleanSettingSender()) return false
        val sources = momentarySettingSources.getOrPut(controlId) { linkedSetOf() }
        val added = sources.add(StudioInputState.LOCK_SOURCE)
        dispatchMomentarySetting(control, true)
        syncLockUi()
        if (added) onStatusMessage(I18n.t("studio.lock.status.locked", controlLabel(control)))
        return true
    }
    fun unlockMomentarySetting(controlId: String): Boolean {
        val control = momentarySettingControls[controlId] ?: return false
        if (::momentarySettingHold.isInitialized) momentarySettingHold.flushPendingReleasesForKey(controlId)
        val sources = momentarySettingSources[controlId] ?: return false
        if (!sources.remove(StudioInputState.LOCK_SOURCE)) return false
        if (sources.isEmpty()) {
            momentarySettingSources.remove(controlId)
            dispatchMomentarySetting(control, false)
        }
        syncLockUi()
        onStatusMessage(I18n.t("studio.lock.status.unlocked", controlLabel(control)))
        return true
    }
    fun toggleMomentarySettingLock(controlId: String): Boolean =
        if (isMomentarySettingLocked(controlId)) { unlockMomentarySetting(controlId); false } else lockMomentarySetting(controlId)

    // ---- External input bindings (keyboard shortcuts / gamepad, see desktop.input) ----
    /** Invoked first for every key event reaching the studio windows; consume the event to claim it. */
    var externalKeyHandler: ((KeyEvent) -> Unit)? = null

    /** Momentary GPIO key through the calibrated hold path. Sources start with `keyboard:` or `gamepad:`. Locks stay intact. */
    fun pressBindingButton(source: String, key: String) {
        if (closed || !inputs.supports(key) || inputs.isLatching(key)) return
        pressMomentary(source, key)
    }
    fun releaseBindingButton(source: String, key: String) {
        if (closed || inputs.isLatching(key)) return
        releaseMomentary(source, key)
    }
    /** Flips a latching body switch (`power`, `movieMode`, `focusMode`) exactly like clicking it. */
    fun toggleBindingLatch(key: String) {
        if (closed || !inputs.supports(key) || !inputs.isLatching(key)) return
        if (!inputs.desired(key)) momentaryHold.cancelAndReleaseOpposite(key)
        inputs.toggle(key)
    }
    /** Momentary ADC control (`rear-q`, `rear-ael`, `top-fn1`, `top-fn2`, ...). */
    fun pressBindingSetting(source: String, controlId: String) {
        if (closed || controlId !in momentarySettingControls || !hasBooleanSettingSender()) return
        pressMomentarySetting(source, controlId)
    }
    fun releaseBindingSetting(source: String, controlId: String) {
        if (closed || controlId !in momentarySettingControls) return
        releaseMomentarySetting(source, controlId)
    }
    fun pressBindingViewMode(source: String) {
        if (closed || sendViewMode == null) return
        pressViewMode(source)
    }
    fun releaseBindingViewMode(source: String) {
        if (closed) return
        releaseViewMode(source)
    }
    fun stepBindingRearDial(delta: Int) { if (!closed) stepRearCommandDial(delta) }
    fun stepBindingFrontDial(delta: Int) { if (!closed) stepFrontCommandDial(delta) }
    fun stepBindingModeDial(delta: Int) { if (!closed) stepModeDial(delta) }
    fun stepBindingFocusMode(delta: Int) { if (!closed && sendSettingValue != null) shootingControlsPane.stepFocusMode(delta) }
    fun toggleBindingEyeSensor() {
        if (closed) return
        toggleEyeSensor()
        updateViewfinderStatus()
    }
    fun toggleBindingEvfWindow() { if (!closed) setEvfWindowVisible(!isEvfWindowShowing()) }

    fun lockedButtonSummaryIds(): List<String> {
        val ids = mutableListOf<String>()
        ids.addAll(inputs.lockedKeys())
        momentarySettingSources.forEach { (controlId, sources) ->
            if (StudioInputState.LOCK_SOURCE in sources) ids += controlId
        }
        if (isViewModeLocked()) ids += VIEW_MODE_HOLD_KEY
        return ids
    }
    fun unlockAllButtons(): Int {
        var count = 0
        inputs.lockedKeys().forEach { key ->
            if (::momentaryHold.isInitialized) momentaryHold.flushPendingReleasesForKey(key)
        }
        count += inputs.unlockAll()
        val lockedAdcIds = momentarySettingSources
            .filterValues { StudioInputState.LOCK_SOURCE in it }
            .keys
            .toList()
        lockedAdcIds.forEach { controlId ->
            if (::momentarySettingHold.isInitialized) momentarySettingHold.flushPendingReleasesForKey(controlId)
            val sources = momentarySettingSources[controlId]
            if (sources != null && sources.remove(StudioInputState.LOCK_SOURCE)) {
                count++
                if (sources.isEmpty()) {
                    momentarySettingSources.remove(controlId)
                    momentarySettingControls[controlId]?.let { dispatchMomentarySetting(it, false) }
                }
            }
        }
        if (isViewModeLocked()) {
            if (::viewModeHold.isInitialized) viewModeHold.flushPendingReleasesForKey(VIEW_MODE_HOLD_KEY)
            if (viewModeSources.remove(StudioInputState.LOCK_SOURCE)) {
                count++
                if (viewModeSources.isEmpty()) dispatchViewMode(false)
            }
        }
        syncLockUi()
        if (count > 0) onStatusMessage(I18n.t("studio.lock.status.unlocked_all", count))
        return count
    }

    private fun dispatchViewMode(pressed: Boolean) {
        if (pressed == viewModePressed) return
        viewModePressed = pressed
        sendViewMode?.invoke(pressed)?.whenComplete { _, err ->
            val appliedTick = if (pressed && err == null) virtualTick?.invoke() else null
            if (err != null) failure(err)
            else if (appliedTick != null) {
                val apply = { if (!closed && ::viewModeHold.isInitialized) viewModeHold.markApplied(VIEW_MODE_HOLD_KEY, appliedTick) }
                if (Platform.isFxApplicationThread()) apply() else Platform.runLater(apply)
            }
        }
    }
    private fun pressViewMode(source: String) = viewModeHold.press(source, VIEW_MODE_HOLD_KEY)
    private fun releaseViewMode(source: String) = viewModeHold.release(source, VIEW_MODE_HOLD_KEY)
    private fun pulseViewMode() = viewModeHold.pulse(VIEW_MODE_HOLD_KEY)
    private fun releaseViewModeMouseTransient() = viewModeHold.releaseMouseTransient {
        if (viewModeSources.removeIf { it.startsWith("mouse:") } && viewModeSources.isEmpty()) {
            dispatchViewMode(false)
        }
    }
    private fun releaseTransientViewMode() {
        if (::viewModeHold.isInitialized) viewModeHold.cancelPendingReleases()
        if (viewModeSources.removeIf { it.startsWith("mouse:") || it.startsWith("keyboard:") } && viewModeSources.isEmpty()) {
            dispatchViewMode(false)
        }
    }
    private fun cancelAndReleaseViewMode() {
        if (::viewModeHold.isInitialized) viewModeHold.cancelPendingReleases()
        viewModeSources.clear()
        dispatchViewMode(false)
    }
    private fun dispatchSettingAny(componentId: String, settingKey: String, value: Any): CompletableFuture<*>? =
        sendSettingValue?.invoke(componentId, settingKey, value)
            ?: (if (value is Boolean) sendSetting?.invoke(componentId, settingKey, value) else null)

    private fun hasBooleanSettingSender(): Boolean = sendSettingValue != null || sendSetting != null

    private fun dispatchMomentarySetting(c: PanelControl, pressed: Boolean) {
        val componentId = c.componentId ?: return
        val settingKey = c.settingKey ?: return
        if (!hasBooleanSettingSender()) return
        val wasPressed = c.id in momentarySettingPressed
        if (pressed == wasPressed) return
        if (pressed) momentarySettingPressed += c.id else momentarySettingPressed -= c.id
        val future = runCatching { dispatchSettingAny(componentId, settingKey, pressed) }
            .getOrElse { CompletableFuture.failedFuture<Any>(it) } ?: return
        future.whenComplete { _, err ->
            val appliedTick = if (pressed && err == null) virtualTick?.invoke() else null
            if (err != null) failure(err)
            else if (appliedTick != null) {
                val apply = { if (!closed && ::momentarySettingHold.isInitialized) momentarySettingHold.markApplied(c.id, appliedTick) }
                if (Platform.isFxApplicationThread()) apply() else Platform.runLater(apply)
            }
        }
    }
    private fun pressMomentarySetting(source: String, controlId: String) = momentarySettingHold.press(source, controlId)
    private fun releaseMomentarySetting(source: String, controlId: String) = momentarySettingHold.release(source, controlId)
    private fun pulseMomentarySetting(controlId: String) = momentarySettingHold.pulse(controlId)
    private fun releaseMomentarySettingMouseTransient() = momentarySettingHold.releaseMouseTransient {
        val toRelease = mutableListOf<PanelControl>()
        val iter = momentarySettingSources.entries.iterator()
        while (iter.hasNext()) {
            val (controlId, sources) = iter.next()
            if (sources.removeIf { it.startsWith("mouse:") } && sources.isEmpty()) {
                iter.remove()
                momentarySettingControls[controlId]?.let(toRelease::add)
            }
        }
        toRelease.forEach { dispatchMomentarySetting(it, false) }
    }
    private fun releaseTransientMomentarySettings() {
        if (::momentarySettingHold.isInitialized) momentarySettingHold.cancelPendingReleases()
        val toRelease = mutableListOf<PanelControl>()
        val iter = momentarySettingSources.entries.iterator()
        while (iter.hasNext()) {
            val (controlId, sources) = iter.next()
            if (sources.removeIf { it.startsWith("mouse:") || it.startsWith("keyboard:") } && sources.isEmpty()) {
                iter.remove()
                momentarySettingControls[controlId]?.let(toRelease::add)
            }
        }
        toRelease.forEach { dispatchMomentarySetting(it, false) }
    }
    private fun releaseAllMomentarySettings() {
        if (::momentarySettingHold.isInitialized) momentarySettingHold.cancelPendingReleases()
        momentarySettingSources.clear()
        if (momentarySettingPressed.isEmpty()) return
        val ids = momentarySettingPressed.toList()
        ids.forEach { controlId ->
            momentarySettingControls[controlId]?.let { dispatchMomentarySetting(it, false) }
        }
        momentarySettingPressed.clear()
    }
    private fun settingMomentaryButton(c: PanelControl): Button = inspectButton(c).apply {
        adcBodyButtons[c.id] = this
        tooltip = Tooltip().apply {
            textProperty().bind(I18n.bindDynamic {
                "${I18n.t("studio.tip.setting_momentary", tip(c), controlLabel(c), c.componentId.orEmpty(), c.settingKey.orEmpty())}\n${I18n.t("studio.tip.key_combo_hint")}"
            })
        }
        var unlockedOnPress = false
        setOnAction {
            if (!hasBooleanSettingSender()) {
                lastInspectedControlId = c.id
                inspect(c)
            } else if (isMomentarySettingLocked(c.id)) {
                unlockMomentarySetting(c.id)
            } else {
                pulseMomentarySetting(c.id)
            }
        }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (hasBooleanSettingSender() && event.button == MouseButton.PRIMARY) {
                requestFocus()
                if (event.isShiftDown) {
                    toggleMomentarySettingLock(c.id)
                } else if (isMomentarySettingLocked(c.id)) {
                    unlockedOnPress = true
                    unlockMomentarySetting(c.id)
                } else {
                    pressMomentarySetting("mouse:" + c.id, c.id)
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (hasBooleanSettingSender() && event.button == MouseButton.PRIMARY) {
                if (unlockedOnPress) {
                    unlockedOnPress = false
                } else {
                    releaseMomentarySetting("mouse:" + c.id, c.id)
                }
                event.consume()
            }
        }
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (hasBooleanSettingSender() && event.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) {
                if (isMomentarySettingLocked(c.id)) unlockMomentarySetting(c.id)
                else pressMomentarySetting("keyboard:" + c.id, c.id)
                event.consume()
            }
        }
        addEventFilter(KeyEvent.KEY_RELEASED) { event ->
            if (hasBooleanSettingSender() && event.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) {
                releaseMomentarySetting("keyboard:" + c.id, c.id)
                event.consume()
            }
        }
        focusedProperty().addListener { _, _, focused -> if (!focused) releaseMomentarySetting("keyboard:" + c.id, c.id) }
        addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (hasBooleanSettingSender() && event.button == MouseButton.SECONDARY) {
                lastInspectedControlId = c.id
                inspect(c)
                if (c.componentId != null && c.bindingStatus.startsWith("host-")) configure(c.componentId)
                event.consume()
            }
        }
    }
    private fun quickAdcButtonGrid(): GridPane {
        val cells = mutableListOf<Node>()
        fun adcCell(controlId: String, shortLabel: String) {
            val control = momentarySettingControls[controlId] ?: return
            val source = "grid-$controlId"
            val btn = Button(shortLabel).apply {
                id = "quick-adc-$controlId"
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                tooltip = Tooltip().apply {
                    textProperty().bind(I18n.bindDynamic {
                        "${controlLabel(control)} (${control.componentId}.${control.settingKey})\n${I18n.t("studio.tip.key_combo_hint")}"
                    })
                }
                var unlockedOnPress = false
                setOnAction {
                    if (!hasBooleanSettingSender()) {
                        lastInspectedControlId = controlId
                        inspect(control)
                    } else if (isMomentarySettingLocked(controlId)) {
                        unlockMomentarySetting(controlId)
                    } else {
                        pulseMomentarySetting(controlId)
                    }
                }
                addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
                    if (hasBooleanSettingSender() && (event.button == MouseButton.SECONDARY || (event.button == MouseButton.PRIMARY && event.isShiftDown))) {
                        toggleMomentarySettingLock(controlId)
                        event.consume()
                    } else if (hasBooleanSettingSender() && event.button == MouseButton.PRIMARY) {
                        requestFocus()
                        if (isMomentarySettingLocked(controlId)) {
                            unlockedOnPress = true
                            unlockMomentarySetting(controlId)
                        } else {
                            pressMomentarySetting("mouse:$source", controlId)
                        }
                        event.consume()
                    }
                }
                addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
                    if (hasBooleanSettingSender() && event.button == MouseButton.PRIMARY) {
                        if (unlockedOnPress) {
                            unlockedOnPress = false
                        } else {
                            releaseMomentarySetting("mouse:$source", controlId)
                        }
                        event.consume()
                    }
                }
                addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
                    if (event.button == MouseButton.SECONDARY) event.consume()
                }
                addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                    if (hasBooleanSettingSender() && event.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) {
                        if (isMomentarySettingLocked(controlId)) unlockMomentarySetting(controlId)
                        else pressMomentarySetting("keyboard:$source", controlId)
                        event.consume()
                    }
                }
                addEventFilter(KeyEvent.KEY_RELEASED) { event ->
                    if (hasBooleanSettingSender() && event.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) {
                        releaseMomentarySetting("keyboard:$source", controlId)
                        event.consume()
                    }
                }
                focusedProperty().addListener { _, _, focused -> if (!focused) releaseMomentarySetting("keyboard:$source", controlId) }
            }
            val lockBtn = ToggleButton(UNLOCK_ICON).apply {
                id = "lock-adc-$controlId"
                styleClass += "studio-lock-btn-compact"
                isFocusTraversable = false
                tooltip = Tooltip()
                setOnAction {
                    if (!syncingLockButtons) toggleMomentarySettingLock(controlId)
                }
            }
            adcQuickButtons[controlId] = btn
            adcLockButtons[controlId] = lockBtn
            cells += HBox(2.0, btn, lockBtn).apply {
                minWidth = 0.0
                styleClass += "studio-adc-key-group"
                HBox.setHgrow(btn, Priority.ALWAYS)
            }
        }
        adcCell("rear-q", "Q")
        adcCell("rear-ael", "AEL")
        adcCell("top-fn1", "Fn1 / ±")
        if ("top-fn1" in momentarySettingControls) {
            val evHoldToggle = ToggleButton("± Hold").apply {
                id = "quick-adc-ev-hold"
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                tooltip = Tooltip("Hold top-plate ± (Fn1, ADC CH6) while turning Rear Dial to adjust Exposure Comp (-5..+5 EV)")
                setOnAction {
                    if (!hasBooleanSettingSender()) {
                        isSelected = false
                        return@setOnAction
                    }
                    if (isSelected) {
                        pressMomentarySetting("latch:quick-adc-ev-hold", "top-fn1")
                    } else {
                        releaseMomentarySetting("latch:quick-adc-ev-hold", "top-fn1")
                    }
                }
            }
            cells += evHoldToggle
        }
        adcCell("top-fn2", "Fn2")
        adcCell("top-fn3", "Fn3")
        adcCell("rear-sub-backlight", "LIGHT")
        return twoColButtonGrid(cells)
    }
    private fun setMovieModeFromStudio(movie: Boolean) {
        if (inputs.supports("movieMode")) {
            inputs.setLatching("movieMode", movie)
        } else {
            dispatchSettingAny("buttons", "movieMode", movie)?.whenComplete { _, err ->
                if (err != null) failure(err)
            }
        }
        shootingControlsPane.syncMovieModeFromStudio(movie)
        updateStillMovieBodyIndicator(movie)
    }
    private fun updateModeDialVisuals(mode: String) {
        val displaySlot = if (mode == "default") "P" else mode
        bodyModeDialIndicators.forEach { slot ->
            if (slot.label.text != displaySlot) slot.label.text = displaySlot
        }
        catalog.flatMap { it.dynamicStates }.forEach { binding ->
            if (binding.mode == "rotate-map" && binding.componentId == "soc-adc" && binding.settingKey == "modeDial") {
                val angle = binding.angleMap[mode] ?: if (mode == "default") binding.angleMap["P"] else null
                if (angle != null) {
                    dynamicRotates[binding.id]?.angle = angle
                }
            }
        }
    }
    private fun updateStillMovieBodyIndicator(movie: Boolean) {
        if (shutterMovieMode != movie) shutter?.cancel()
        shutterMovieMode = movie
        bodyStillMovieIndicators.forEach { (badge, c) ->
            val targetText = if (movie) "MOVIE" else "STILL"
            if (badge.text != targetText) badge.text = targetText
            setStyleClassPresent(badge, "studio-body-lever-badge-movie", movie)
            val badgeW = 46.0
            val badgeH = 16.0
            badge.layoutX = if (movie) c.x + 2.0 else (c.x + c.width - badgeW - 2.0).coerceAtLeast(c.x)
            badge.layoutY = c.y + (c.height - badgeH) / 2.0
        }
    }
    private fun updateFocusBodyIndicator(mode: String) {
        if (desiredFocusMode != mode) shutter?.cancel()
        desiredFocusMode = mode
        bodyFocusSegments.forEach { it.selectMode(mode) }
        val short = when (mode) {
            "AF-S" -> "S"
            "AF-C" -> "C"
            "MF" -> "M"
            else -> "S"
        }
        val index = when (mode) {
            "AF-S" -> 0
            "AF-C" -> 1
            "MF" -> 2
            else -> 0
        }
        catalog.flatMap { it.dynamicStates }.forEach { binding ->
            if (binding.mode == "rotate-map" && binding.componentId == "soc-gpio" && binding.settingKey == "focusMode") {
                val angle = binding.angleMap[mode] ?: binding.angleMap[short]
                if (angle != null) {
                    dynamicRotates[binding.id]?.angle = angle
                }
            }
        }
        bodyFocusModeIndicators.forEach { (badge, c) ->
            val labelText = if (c.width >= c.height * 1.5) mode else short
            if (badge.text != labelText) badge.text = labelText
            val horizontal = c.width >= c.height * 1.5
            if (horizontal) {
                val badgeW = 36.0
                val badgeH = 16.0
                val travel = (c.width - badgeW - 4.0).coerceAtLeast(0.0)
                badge.layoutX = c.x + 2.0 + travel * (index / 2.0)
                badge.layoutY = c.y + (c.height - badgeH) / 2.0
            } else {
                val badgeW = 20.0
                val badgeH = 16.0
                val travel = (c.height - badgeH - 4.0).coerceAtLeast(0.0)
                badge.layoutX = c.x + c.width - badgeW - 2.0
                badge.layoutY = c.y + 2.0 + travel * (index / 2.0)
            }
        }
    }
    private fun refreshAllBodyIndicators() {
        updateModeDialVisuals(desiredModeDial)
        updateStillMovieBodyIndicator(effectiveBodyButtonState("movieMode"))
        updateFocusBodyIndicator(shootingControlsPane.currentFocusMode())
    }
    private fun selectModeDial(mode: String) {
        if (mode !in MODE_DIAL_CHOICES) return
        val sender = sendSettingValue ?: return
        val previous = desiredModeDial
        desiredModeDial = mode
        syncingModeDialBox = true
        quickModeDialCombo.value = mode
        syncingModeDialBox = false
        shootingControlsPane.syncModeDialFromStudio(mode)
        updateModeDialVisuals(mode)
        modeDialPending = true
        val version = ++modeDialVersion
        runCatching { sender("soc-adc", "modeDial", mode) }
            .getOrElse { CompletableFuture.failedFuture<Any>(it) }
            .whenComplete { _, err ->
                val done = {
                    if (modeDialVersion == version) {
                        modeDialPending = false
                        if (err != null) {
                            desiredModeDial = previous
                            syncingModeDialBox = true
                            quickModeDialCombo.value = previous
                            syncingModeDialBox = false
                            shootingControlsPane.syncModeDialFromStudio(previous)
                            updateModeDialVisuals(previous)
                        }
                    }
                    if (err != null) failure(err)
                }
                if (Platform.isFxApplicationThread()) done() else Platform.runLater(done)
            }
    }
    private fun stepModeDial(delta: Int) {
        if (delta == 0) return
        val idx = MODE_DIAL_CYCLE.indexOf(desiredModeDial)
        val nextIdx = if (idx < 0) {
            if (delta > 0) 0 else MODE_DIAL_CYCLE.lastIndex
        } else {
            Math.floorMod(idx + delta, MODE_DIAL_CYCLE.size)
        }
        selectModeDial(MODE_DIAL_CYCLE[nextIdx])
    }
    private fun stepRearCommandDial(delta: Int) {
        if (delta == 0) return
        val sender = sendSettingValue ?: return
        val previous = desiredRearDialDetents
        val next = (previous + delta.toLong()).coerceIn(REAR_DIAL_MIN_DETENTS, REAR_DIAL_MAX_DETENTS)
        if (next == previous) return
        desiredRearDialDetents = next
        rearDialDetentsLabel.text = next.toString()
        rearDialWheel.setDetents(next)
        rearDialPending = true
        val version = ++rearDialVersion
        runCatching { sender("soc-adc", "rearDialDetents", next) }
            .getOrElse { CompletableFuture.failedFuture<Any>(it) }
            .whenComplete { _, err ->
                val done = {
                    if (rearDialVersion == version) {
                        rearDialPending = false
                        if (err != null) {
                            desiredRearDialDetents = previous
                            rearDialDetentsLabel.text = previous.toString()
                            rearDialWheel.setDetents(previous)
                        }
                    }
                    if (err != null) failure(err)
                }
                if (Platform.isFxApplicationThread()) done() else Platform.runLater(done)
            }
    }
    private fun stepFrontCommandDial(delta: Int) {
        if (delta == 0) return
        val sender = sendSettingValue ?: return
        val previous = desiredFrontDialDetents
        val next = (previous + delta.toLong()).coerceIn(FRONT_DIAL_MIN_DETENTS, FRONT_DIAL_MAX_DETENTS)
        if (next == previous) return
        desiredFrontDialDetents = next
        frontDialDetentsLabel.text = next.toString()
        frontDialWheel.setDetents(next)
        frontDialPending = true
        val version = ++frontDialVersion
        runCatching { sender("soc-gpio", "frontDialDetents", next) }
            .getOrElse { CompletableFuture.failedFuture<Any>(it) }
            .whenComplete { _, err ->
                val done = {
                    if (frontDialVersion == version) {
                        frontDialPending = false
                        if (err != null) {
                            desiredFrontDialDetents = previous
                            frontDialDetentsLabel.text = previous.toString()
                            frontDialWheel.setDetents(previous)
                        }
                    }
                    if (err != null) failure(err)
                }
                if (Platform.isFxApplicationThread()) done() else Platform.runLater(done)
            }
    }
    /** Some camera bodies select exposure mode with a pushbutton and a subsequent choice. */
    private fun exposureModeButton(c: PanelControl): Button = inspectButton(c).apply {
        val picker = ContextMenu()
        val selectedMode = ToggleGroup()
        val choices = MODE_DIAL_CYCLE.map { mode ->
            RadioMenuItem(mode).apply {
                toggleGroup = selectedMode
                setOnAction { selectModeDial(mode) }
            }
        }
        picker.items.setAll(choices)
        setOnAction {
            lastInspectedControlId = c.id
            inspect(c)
            if (sendSettingValue != null) {
                val current = if (desiredModeDial == "default") "P" else desiredModeDial
                choices.firstOrNull { it.text == current }?.isSelected = true
                picker.show(this, javafx.geometry.Side.BOTTOM, 0.0, 0.0)
            }
        }
    }

    private fun modeDialButton(c: PanelControl): Button = inspectButton(c).apply {
        tooltip = Tooltip().apply { textProperty().bind(I18n.bindDynamic { I18n.t("studio.tip.mode_dial", tip(c)) }) }
        var pressSceneX = 0.0
        var pressSceneY = 0.0
        var lastSceneX = 0.0
        var lastAngleDeg = Double.NaN
        var dragAccumPx = 0.0
        var dragAccumDeg = 0.0
        var dragged = false
        var suppressNextAction = false
        setOnAction {
            if (suppressNextAction) {
                suppressNextAction = false
                return@setOnAction
            }
            lastInspectedControlId = c.id
            inspect(c)
            if (sendSettingValue != null) stepModeDial(1)
        }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (sendSettingValue != null && event.button == MouseButton.PRIMARY) {
                pressSceneX = event.screenX
                pressSceneY = event.screenY
                lastSceneX = event.screenX
                dragAccumPx = 0.0
                dragAccumDeg = 0.0
                dragged = false
                suppressNextAction = false
                val cx = width / 2.0
                val cy = height / 2.0
                val r = hypot(event.x - cx, event.y - cy)
                lastAngleDeg = if (width >= 40.0 && r >= 18.0) Math.toDegrees(atan2(event.y - cy, event.x - cx)) else Double.NaN
            }
        }
        addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (sendSettingValue != null && event.isPrimaryButtonDown) {
                val dist = hypot(event.screenX - pressSceneX, event.screenY - pressSceneY)
                if (dist >= BODY_DRAG_THRESHOLD_PX) {
                    dragged = true
                }
                val dx = event.screenX - lastSceneX
                lastSceneX = event.screenX
                val cx = width / 2.0
                val cy = height / 2.0
                val r = hypot(event.x - cx, event.y - cy)
                if (dragged) {
                    val curDeg = if (width >= 40.0 && r >= 18.0) Math.toDegrees(atan2(event.y - cy, event.x - cx)) else Double.NaN
                    var deltaDeg = 0.0
                    val hasAngular = !curDeg.isNaN() && !lastAngleDeg.isNaN()
                    if (hasAngular) {
                        deltaDeg = curDeg - lastAngleDeg
                        while (deltaDeg > 180.0) deltaDeg -= 360.0
                        while (deltaDeg < -180.0) deltaDeg += 360.0
                    }
                    lastAngleDeg = curDeg
                    if (hasAngular && abs(deltaDeg) > abs(dx) * 0.5) {
                        dragAccumDeg += deltaDeg
                        while (dragAccumDeg >= MODE_DIAL_DRAG_DEG) {
                            stepModeDial(1)
                            dragAccumDeg -= MODE_DIAL_DRAG_DEG
                        }
                        while (dragAccumDeg <= -MODE_DIAL_DRAG_DEG) {
                            stepModeDial(-1)
                            dragAccumDeg += MODE_DIAL_DRAG_DEG
                        }
                    } else {
                        dragAccumPx += dx
                        while (dragAccumPx >= MODE_DIAL_DRAG_PX) {
                            stepModeDial(1)
                            dragAccumPx -= MODE_DIAL_DRAG_PX
                        }
                        while (dragAccumPx <= -MODE_DIAL_DRAG_PX) {
                            stepModeDial(-1)
                            dragAccumPx += MODE_DIAL_DRAG_PX
                        }
                    }
                    event.consume()
                }
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (sendSettingValue != null && event.button == MouseButton.PRIMARY && dragged) {
                dragged = false
                suppressNextAction = true
                event.consume()
            }
        }
        addEventFilter(ScrollEvent.SCROLL) { event ->
            if (sendSettingValue != null && event.deltaY != 0.0) {
                lastInspectedControlId = c.id
                stepModeDial(if (event.deltaY > 0.0) 1 else -1)
                event.consume()
            }
        }
        addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.SECONDARY) {
                lastInspectedControlId = c.id
                inspect(c)
                if (sendSettingValue != null) {
                    val menu = ContextMenu().apply {
                        MODE_DIAL_CHOICES.forEach { choice ->
                            val marker = if (choice == desiredModeDial) "● " else "   "
                            items += MenuItem("$marker$choice").apply {
                                setOnAction { selectModeDial(choice) }
                            }
                        }
                        items += SeparatorMenuItem()
                        items += MenuItem(I18n.t("studio.tip.mode_dial.open_adc")).apply {
                            setOnAction { configure("soc-adc") }
                        }
                    }
                    menu.show(this, event.screenX, event.screenY)
                } else if (c.componentId != null && c.bindingStatus.startsWith("host-")) {
                    configure(c.componentId)
                }
                event.consume()
            }
        }
    }
    private fun handleViewfinderKeyEvent(event: KeyEvent) {
        externalKeyHandler?.invoke(event)
        if (event.isConsumed) return
        when (event.code) {
            KeyCode.F6 -> if (event.eventType == KeyEvent.KEY_PRESSED) {
                toggleEyeSensor()
                updateViewfinderStatus()
                event.consume()
            }
            KeyCode.F7 -> {
                if (event.eventType == KeyEvent.KEY_PRESSED) {
                    pressViewMode("keyboard:f7")
                    event.consume()
                } else if (event.eventType == KeyEvent.KEY_RELEASED) {
                    releaseViewMode("keyboard:f7")
                    event.consume()
                }
            }
            KeyCode.F8 -> if (event.eventType == KeyEvent.KEY_PRESSED) {
                setEvfWindowVisible(!isEvfWindowShowing())
                event.consume()
            }
            else -> {}
        }
    }
    fun setEvfWindowVisible(visible: Boolean) {
        check(Platform.isFxApplicationThread())
        check(!closed)
        evfWindow.setVisible(visible)
    }
    fun isEvfWindowShowing(): Boolean = evfWindow.isShowing
    /** Pins/unpins the floating EVF window above all other windows (persisted via [onEvfAlwaysOnTopChanged]). */
    fun setEvfAlwaysOnTop(pinned: Boolean) {
        check(Platform.isFxApplicationThread())
        check(!closed)
        evfWindow.setAlwaysOnTop(pinned)
    }
    fun isEvfAlwaysOnTop(): Boolean = evfWindow.isAlwaysOnTop
    fun viewfinderStatus(): ViewfinderStatus = ViewfinderStatus.derive(
        eyeSensorNear = observedSettings[eyeSensorControl?.id ?: "rear-eye-sensor"] == true,
        lcdSequence = surfaceForPort("display.main", "rear-lcd")?.sequence ?: 0L,
        evfSequence = surfaceForPort("display.evf", "rear-evf")?.sequence ?: 0L,
        lcdOutputActive = observedLcdOutputActive,
        evfOutputActive = observedEvfOutputActive,
        lastUpdatedPort = lastUpdatedDisplayPort,
    )
    private fun updateViewfinderStatus() {
        val status = viewfinderStatus()
        viewfinderStatusLabel.text = I18n.t("studio.vf.status_prefix", status.summaryText())
        quickEyeSensorToggle.isSelected = status.eyeSensorNear
        quickEyeSensorToggle.text = I18n.t(if (status.eyeSensorNear) "evf.window.eye.far" else "evf.window.eye.near")
        evfWindow.updateStatus(status)
    }
    private fun isSurfaceVisible(surface: Surface): Boolean =
        (faceWindows[surface.viewId]?.isShowing == true) || (surface.port == "display.evf" && isEvfWindowShowing())
    private fun rearViewModeButton(c: PanelControl): Button = inspectButton(c).apply {
        viewModeBodyButton = this
        tooltip = Tooltip().apply {
            textProperty().bind(I18n.bindDynamic { "${I18n.t("studio.tip.view_mode", tip(c))}\n${I18n.t("studio.tip.key_combo_hint")}" })
        }
        var unlockedOnPress = false
        setOnAction {
            if (sendViewMode == null) {
                lastInspectedControlId = c.id
                inspect(c)
            } else if (isViewModeLocked()) {
                unlockViewMode()
            } else {
                pulseViewMode()
            }
        }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (sendViewMode != null && event.button == MouseButton.PRIMARY) {
                if (event.isShiftDown) {
                    toggleViewModeLock()
                } else if (isViewModeLocked()) {
                    unlockedOnPress = true
                    unlockViewMode()
                } else {
                    pressViewMode("mouse:rear-view-mode")
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (sendViewMode != null && event.button == MouseButton.PRIMARY) {
                if (unlockedOnPress) {
                    unlockedOnPress = false
                } else {
                    releaseViewMode("mouse:rear-view-mode")
                }
                event.consume()
            }
        }
        addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (sendViewMode != null && event.button == MouseButton.SECONDARY) {
                lastInspectedControlId = c.id
                inspect(c)
                if (c.componentId != null && c.bindingStatus.startsWith("host-")) configure(c.componentId)
                event.consume()
            }
        }
    }
    private fun rearLcdTouchButton(c: PanelControl, surface: Surface): Button = inspectButton(c).apply {
        minWidth = c.width; prefWidth = c.width; maxWidth = Double.MAX_VALUE
        minHeight = c.height; prefHeight = c.height; maxHeight = Double.MAX_VALUE
        tooltip = null
        setOnAction {
            if (!touchEnabled || sendTouch == null) {
                lastInspectedControlId = c.id
                inspect(c)
            }
        }
        fun mapEvent(event: MouseEvent): TouchPointCoord {
            val contentW = surface.pixelSurface.width.takeIf { it > 0 } ?: TouchCoordinateMapper.DEFAULT_TOUCH_WIDTH
            val contentH = surface.pixelSurface.height.takeIf { it > 0 } ?: TouchCoordinateMapper.DEFAULT_TOUCH_HEIGHT
            val viewW = width.takeIf { it > 0.0 } ?: c.width
            val viewH = height.takeIf { it > 0.0 } ?: c.height
            return TouchCoordinateMapper.mapViewportToTouch(event.x, event.y, viewW, viewH, contentW, contentH)
        }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (touchEnabled && sendTouch != null && event.button == MouseButton.PRIMARY) {
                requestFocus()
                lastTouchDragNanos = System.nanoTime()
                pressTouch(mapEvent(event))
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (touchEnabled && sendTouch != null && touchActive && event.isPrimaryButtonDown &&
                pendingSwipeRelease == null &&
                (!::touchHold.isInitialized || touchHold.pendingReleaseCount() == 0)
            ) {
                val coord = mapEvent(event)
                val now = System.nanoTime()
                val crossedFlick = !touchSwipeDispatched && touchDistanceSq(touchPressCoord, coord) > TOUCH_FLICK_THRESHOLD_SQ
                if (coord != lastTouchCoord && (crossedFlick || now - lastTouchDragNanos >= 16_000_000L)) {
                    lastTouchDragNanos = now
                    if (touchDistanceSq(touchPressCoord, coord) > TOUCH_FLICK_THRESHOLD_SQ) {
                        touchSwipeDispatched = true
                    }
                    dispatchTouch(coord, pressed = true)
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (touchActive && sendTouch != null && event.button == MouseButton.PRIMARY) {
                val releaseCoord = mapEvent(event)
                val isSwipe = touchSwipeDispatched || touchDistanceSq(touchPressCoord, releaseCoord) > TOUCH_FLICK_THRESHOLD_SQ
                if (!isSwipe) {
                    lastTouchCoord = releaseCoord
                    releaseTouch()
                } else {
                    val needsFinalMove = (!touchSwipeDispatched) ||
                        (touchDistanceSq(lastTouchCoord, releaseCoord) > TOUCH_FLICK_THRESHOLD_SQ)
                    if (needsFinalMove && releaseCoord != lastTouchCoord) {
                        touchSwipeDispatched = true
                        dispatchTouch(releaseCoord, pressed = true)
                        releaseSwipeTouch(waitOnePoll = true)
                    } else {
                        releaseSwipeTouch(waitOnePoll = false)
                    }
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (touchEnabled && sendTouch != null && event.button == MouseButton.PRIMARY) {
                event.consume()
            }
        }
        addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (touchEnabled && event.button == MouseButton.SECONDARY) {
                lastInspectedControlId = c.id
                inspect(c)
                event.consume()
            }
        }
    }
    private fun pressMomentary(source: String, key: String) {
        if (key == "shutterFull" && inputs.supports("shutterHalf")) {
            val contacts = shutter
            if (contacts != null) {
                contacts.press(source)
                return
            }
            val s1AlreadyDown = inputs.desired("shutterHalf")
            momentaryHold.press("$source:s1-coupled", "shutterHalf")
            if (s1AlreadyDown) {
                virtualTick?.invoke()?.let { momentaryHold.markApplied("shutterHalf", it) }
            }
        }
        momentaryHold.press(source, key)
    }
    private fun releaseMomentary(source: String, key: String) {
        if (key == "shutterFull" && inputs.supports("shutterHalf")) {
            val contacts = shutter
            if (contacts != null) {
                contacts.release(source)
                return
            }
            momentaryHold.release(source, key)
            momentaryHold.release("$source:s1-coupled", "shutterHalf")
        } else {
            momentaryHold.release(source, key)
        }
    }
    private fun pulseMomentary(key: String) {
        if (key == "shutterFull" && inputs.supports("shutterHalf")) {
            pressMomentary("keyboard:pulse", key)
            releaseMomentary("keyboard:pulse", key)
        } else {
            momentaryHold.pulse(key)
        }
    }
    private fun releaseMouseTransient() {
        shutter?.releaseMouse()
        momentaryHold.releaseMouseTransient { inputs.releaseTransient("mouse:") }
    }
    private fun cancelPendingReleases() {
        shutter?.cancel()
        momentaryHold.cancelPendingReleases()
    }
    private fun keyButton(c: PanelControl, onBody: Boolean = true, proxyId: String? = null): ToggleButton {
        val key = requireNotNull(c.buttonKey)
        val latching = inputs.isLatching(key)
        val source = proxyId ?: if (onBody) c.id else "grid-" + c.id
        val isRearDial = c.hasRole("rear-command-dial", "rear-command-dial")
        val isFrontDial = c.hasRole("front-command-dial", "front-command-dial")
        val isCommandDial = onBody && (isRearDial || isFrontDial)
        val isBodyFocusSelector = onBody && c.hasRole("focus-selector", "rear-focus-selector")
        val isBodyStillMovie = onBody && key == "movieMode"
        return ToggleButton().apply {
            id = proxyId ?: if (onBody) "button-" + key else "input-" + key
            if (onBody) {
                styleClass += "studio-hotspot"
                if (proxyId == null) buttons[key] = this
                else proxyButtons.getOrPut(key) { mutableListOf() }.add(this)
            } else styleClass += "studio-input-key"
            accessibleText = I18n.t("studio.accessible.logic_key", controlLabel(c))
            tooltip = Tooltip().apply {
                textProperty().bind(
                    I18n.bindDynamic {
                        val base = when {
                            isRearDial -> I18n.t("studio.tip.rear_command_dial", tip(c))
                            isFrontDial -> I18n.t("studio.tip.front_command_dial", tip(c))
                            c.hasRole("focus-selector", "rear-focus-selector") -> I18n.t("studio.tip.focus_selector", tip(c))
                            key == "movieMode" -> I18n.t("studio.tip.still_movie", tip(c))
                            else -> tip(c)
                        }
                        if (!latching) "$base\n${I18n.t("studio.tip.key_combo_hint")}" else base
                    },
                )
            }
            if (isRearDial) {
                addEventFilter(ScrollEvent.SCROLL) { event ->
                    if (sendSettingValue != null && event.deltaY != 0.0) {
                        stepRearCommandDial(if (event.deltaY > 0.0) 1 else -1)
                        event.consume()
                    }
                }
            } else if (isFrontDial) {
                addEventFilter(ScrollEvent.SCROLL) { event ->
                    if (sendSettingValue != null && event.deltaY != 0.0) {
                        stepFrontCommandDial(if (event.deltaY > 0.0) 1 else -1)
                        event.consume()
                    }
                }
            } else if (isBodyFocusSelector) {
                addEventFilter(ScrollEvent.SCROLL) { event ->
                    if (sendSettingValue != null && event.deltaY != 0.0) {
                        shootingControlsPane.stepFocusMode(if (event.deltaY > 0.0) -1 else 1)
                        event.consume()
                    }
                }
                addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                    if (sendSettingValue != null && event.button == MouseButton.SECONDARY) {
                        val activeMode = shootingControlsPane.currentFocusMode()
                        val menu = ContextMenu().apply {
                            ShootingControlsState.FOCUS_MODES.forEach { mode ->
                                val marker = if (mode == activeMode) "● " else "   "
                                items += MenuItem("$marker$mode").apply {
                                    setOnAction { shootingControlsPane.selectFocusMode(mode) }
                                }
                            }
                        }
                        menu.show(this, event.screenX, event.screenY)
                        event.consume()
                    }
                }
            } else if (isBodyStillMovie) {
                addEventFilter(ScrollEvent.SCROLL) { event ->
                    if (event.deltaY != 0.0) {
                        setMovieModeFromStudio(event.deltaY < 0.0)
                        event.consume()
                    }
                }
            }
            var unlockedOnPress = false
            var dragPressX = 0.0
            var dragPressY = 0.0
            var dragLastX = 0.0
            var dragLastY = 0.0
            var dragAccumPx = 0.0
            var dialDragged = false
            var dialHoldFired = false
            var dialHoldTimer: PauseTransition? = null
            var leverDragged = false
            var suppressNextAction = false
            fun cancelDialHoldTimer() {
                dialHoldTimer?.stop()
                dialHoldTimer = null
            }
            setOnAction {
                if (suppressNextAction) {
                    suppressNextAction = false
                    isSelected = inputs.observed(key) == true
                    return@setOnAction
                }
                if (isBodyFocusSelector && sendSettingValue != null) {
                    shootingControlsPane.stepFocusMode(1)
                    isSelected = inputs.observed(key) == true
                    return@setOnAction
                }
                if (inputs.latchMode || latching) {
                    if (!inputs.desired(key)) {
                        momentaryHold.cancelAndReleaseOpposite(key)
                    }
                    inputs.toggle(key)
                } else if (inputs.isLocked(key)) {
                    unlockButton(key)
                } else {
                    pulseMomentary(key)
                }
                isSelected = inputs.observed(key) == true
            }
            addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
                if (isBodyFocusSelector && sendSettingValue != null && event.button == MouseButton.PRIMARY) {
                    dragPressX = event.screenX
                    dragPressY = event.screenY
                    dragLastX = event.screenX
                    dragLastY = event.screenY
                    dragAccumPx = 0.0
                    leverDragged = false
                    suppressNextAction = false
                } else if (isBodyStillMovie && event.button == MouseButton.PRIMARY) {
                    dragPressX = event.screenX
                    dragPressY = event.screenY
                    leverDragged = false
                    suppressNextAction = false
                } else if (!latching && (event.button == MouseButton.SECONDARY || (event.button == MouseButton.PRIMARY && event.isShiftDown))) {
                    toggleButtonLock(key)
                    event.consume()
                } else if (isCommandDial && sendSettingValue != null && event.button == MouseButton.PRIMARY && !inputs.latchMode && !latching) {
                    requestFocus()
                    if (inputs.isLocked(key)) {
                        unlockedOnPress = true
                        unlockButton(key)
                    } else {
                        dragPressX = event.screenX
                        dragPressY = event.screenY
                        dragLastX = event.screenX
                        dragLastY = event.screenY
                        dragAccumPx = 0.0
                        dialDragged = false
                        dialHoldFired = false
                        cancelDialHoldTimer()
                        dialHoldTimer = PauseTransition(Duration.millis(COMMAND_DIAL_HOLD_MS)).apply {
                            setOnFinished {
                                if (!closed && !dialDragged) {
                                    dialHoldFired = true
                                    pressMomentary("mouse:" + source, key)
                                }
                            }
                            play()
                        }
                    }
                    event.consume()
                } else if (event.button == MouseButton.PRIMARY && !inputs.latchMode && !latching) {
                    requestFocus()
                    if (inputs.isLocked(key)) {
                        unlockedOnPress = true
                        unlockButton(key)
                    } else {
                        pressMomentary("mouse:" + source, key)
                    }
                    event.consume()
                }
            }
            addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
                if (!event.isPrimaryButtonDown) return@addEventFilter
                if (isCommandDial && sendSettingValue != null && !inputs.latchMode && !latching && !unlockedOnPress) {
                    val dist = hypot(event.screenX - dragPressX, event.screenY - dragPressY)
                    if (!dialDragged && dist >= BODY_DRAG_THRESHOLD_PX) {
                        dialDragged = true
                        cancelDialHoldTimer()
                        if (dialHoldFired) {
                            dialHoldFired = false
                            releaseMomentary("mouse:" + source, key)
                        }
                    }
                    val dx = event.screenX - dragLastX
                    val dy = event.screenY - dragLastY
                    dragLastX = event.screenX
                    dragLastY = event.screenY
                    if (dialDragged) {
                        val axisDelta = if (abs(dx) >= abs(dy)) dx else -dy
                        dragAccumPx += axisDelta
                        while (dragAccumPx >= BODY_DIAL_DRAG_PX) {
                            if (isFrontDial) stepFrontCommandDial(1) else stepRearCommandDial(1)
                            dragAccumPx -= BODY_DIAL_DRAG_PX
                        }
                        while (dragAccumPx <= -BODY_DIAL_DRAG_PX) {
                            if (isFrontDial) stepFrontCommandDial(-1) else stepRearCommandDial(-1)
                            dragAccumPx += BODY_DIAL_DRAG_PX
                        }
                    }
                    event.consume()
                } else if (isBodyFocusSelector && sendSettingValue != null) {
                    val dx = event.screenX - dragLastX
                    val dy = event.screenY - dragLastY
                    dragLastX = event.screenX
                    dragLastY = event.screenY
                    val dist = hypot(event.screenX - dragPressX, event.screenY - dragPressY)
                    if (dist >= BODY_DRAG_THRESHOLD_PX) {
                        leverDragged = true
                        val axisDelta = if (abs(dy) >= abs(dx)) dy else dx
                        dragAccumPx += axisDelta
                        while (dragAccumPx >= BODY_LEVER_DRAG_PX) {
                            shootingControlsPane.stepFocusMode(1)
                            dragAccumPx -= BODY_LEVER_DRAG_PX
                        }
                        while (dragAccumPx <= -BODY_LEVER_DRAG_PX) {
                            shootingControlsPane.stepFocusMode(-1)
                            dragAccumPx += BODY_LEVER_DRAG_PX
                        }
                        event.consume()
                    }
                } else if (isBodyStillMovie) {
                    val dx = event.screenX - dragPressX
                    if (abs(dx) >= STILL_MOVIE_DRAG_PX) {
                        leverDragged = true
                        setMovieModeFromStudio(dx < 0.0)
                        event.consume()
                    }
                }
            }
            addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
                if (isBodyFocusSelector && sendSettingValue != null && event.button == MouseButton.PRIMARY && leverDragged) {
                    leverDragged = false
                    suppressNextAction = true
                    event.consume()
                } else if (isBodyStillMovie && event.button == MouseButton.PRIMARY && leverDragged) {
                    leverDragged = false
                    suppressNextAction = true
                    event.consume()
                } else if (!inputs.latchMode && !latching) {
                    if (event.button == MouseButton.SECONDARY) {
                        event.consume()
                    } else if (unlockedOnPress) {
                        unlockedOnPress = false
                        event.consume()
                    } else if (isCommandDial && sendSettingValue != null) {
                        cancelDialHoldTimer()
                        if (dialDragged) {
                            dialDragged = false
                        } else if (dialHoldFired) {
                            dialHoldFired = false
                            releaseMomentary("mouse:" + source, key)
                        } else {
                            pulseMomentary(key)
                        }
                        event.consume()
                    } else {
                        releaseMomentary("mouse:" + source, key)
                        event.consume()
                    }
                }
            }
            addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
                if (!latching && event.button == MouseButton.SECONDARY) event.consume()
            }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code in setOf(KeyCode.SPACE, KeyCode.ENTER) && !inputs.latchMode && !latching) {
                    if (inputs.isLocked(key)) unlockButton(key)
                    else pressMomentary("keyboard:" + source, key)
                    event.consume()
                }
            }
            addEventFilter(KeyEvent.KEY_RELEASED) { event ->
                if (event.code in setOf(KeyCode.SPACE, KeyCode.ENTER) && !inputs.latchMode && !latching) {
                    releaseMomentary("keyboard:" + source, key); event.consume()
                }
            }
            focusedProperty().addListener { _, _, focused -> if (!focused) releaseMomentary("keyboard:" + source, key) }
        }
    }
    private fun view(panel: CameraPanel): Region {
        val canvas = Pane().apply { minWidth = panel.width; maxWidth = panel.width; prefWidth = panel.width; minHeight = panel.height; maxHeight = panel.height; prefHeight = panel.height }
        // Static directions may cache their vectors; owner-controlled layers/dynamic states must render fresh in the same snapshot pulse.
        val artwork = bodyTheme.renderPanelArtwork(panel).apply {
            isCache = panel.layers.isEmpty() && panel.dynamicStates.isEmpty() && panel.covers.isEmpty()
            cacheHint = CacheHint.SCALE
        }
        canvas.children += artwork
        panel.layers.forEach { layer ->
            artwork.lookup("#svg-" + layer.id)?.let { node ->
                node.isVisible = false
                layers[layer.id] = node
            }
        }
        panel.dynamicStates.forEach { binding ->
            artwork.lookup("#svg-" + binding.id)?.let { node ->
                dynamicNodes[binding.id] = node
                if (binding.mode == "rotate-map") {
                    val b = node.boundsInLocal
                    val px = binding.pivotX ?: (b.minX + b.width / 2.0)
                    val py = binding.pivotY ?: (b.minY + b.height / 2.0)
                    val initialAngle = when {
                        binding.componentId == "soc-adc" && binding.settingKey == "modeDial" ->
                            binding.angleMap[desiredModeDial] ?: if (desiredModeDial == "default") binding.angleMap["P"] else null
                        binding.componentId == "soc-gpio" && binding.settingKey == "focusMode" -> {
                            val fm = desiredFocusMode
                            val shortFm = when (fm) { "AF-S" -> "S"; "AF-C" -> "C"; "MF" -> "M"; else -> "S" }
                            binding.angleMap[fm] ?: binding.angleMap[shortFm]
                        }
                        else -> null
                    } ?: 0.0
                    val rot = Rotate(initialAngle, px, py)
                    node.transforms += rot
                    dynamicRotates[binding.id] = rot
                } else {
                    node.opacity = if (binding.mode == "highlight-button" && binding.buttonKey?.let(::effectiveBodyButtonState) == true) {
                        binding.activeOpacity
                    } else binding.inactiveOpacity
                }
            }
        }
        val hasModeDialRotor = panel.dynamicStates.any {
            it.mode == "rotate-map" && it.componentId == "soc-adc" && it.settingKey == "modeDial"
        }
        val hasMovieSwitchArt = panel.dynamicStates.any {
            it.mode == "highlight-button" && it.buttonKey == "movieMode"
        }
        val hasPowerSwitchArt = panel.dynamicStates.any {
            it.mode == "highlight-button" && it.buttonKey == "power" && it.inactiveOpacity > it.activeOpacity
        }
        val hasFocusModeRotor = panel.dynamicStates.any {
            it.mode == "rotate-map" && it.componentId == "soc-gpio" && it.settingKey == "focusMode"
        }
        val coverControls = mutableMapOf<String, Region>()
        panel.controls.forEach { c ->
            val overlay: Region = when {
                c.role == "focus-mode-segments" && c.buttonKey == "focusMode" -> FocusModeSegments(desiredFocusMode) { mode ->
                    lastInspectedControlId = c.id
                    inspect(c)
                    if (mode != desiredFocusMode) shootingControlsPane.selectFocusMode(mode)
                }.also(bodyFocusSegments::add)
                c.role == "exposure-mode-button" && c.componentId == "soc-adc" && c.settingKey == "modeDial" -> exposureModeButton(c)
                c.buttonKey != null -> keyButton(c)
                c.componentId != null && c.settingKey != null && c.interaction == "latching" -> settingToggleButton(c)
                c.componentId != null && c.settingKey != null && c.interaction == "momentary" -> settingMomentaryButton(c)
                c.componentId == "soc-adc" && c.settingKey == "modeDial" -> modeDialButton(c)
                c.hasRole("mode-dial-lock", "top-dial-lock") && sendSettingValue != null -> modeDialButton(c)
                c.kind == "display" -> {
                    val image = ImageView().apply { isPreserveRatio = true; fitWidth = c.width; fitHeight = c.height; isMouseTransparent = true }
                    val waiting = Label(if (c.displayPort == null) "—" else I18n.t("studio.display.waiting")).apply {
                        styleClass += "studio-display-waiting"; isMouseTransparent = true
                        style = "-fx-font-size: " + (if (c.width < 200) 11 else 15) + "px;"
                    }
                    val container = StackPane(image, waiting).apply {
                        if (c.displayPort == "display.shoulder" || c.id == "top-shoulder") {
                            styleClass += ShoulderLcdRenderer.BASE_STYLE_CLASS
                        }
                        when {
                            c.clipShape == "circle" -> {
                                val r = minOf(c.width, c.height) / 2.0
                                clip = javafx.scene.shape.Circle(c.width / 2.0, c.height / 2.0, r)
                            }
                            c.cornerRadius > 0.0 -> {
                                clip = Rectangle(c.width, c.height).apply {
                                    arcWidth = c.cornerRadius * 2.0
                                    arcHeight = c.cornerRadius * 2.0
                                }
                            }
                        }
                    }
                    val surface = createSurface(panel.id, c.displayPort, image, waiting, container)
                    surfaces[c.id] = surface
                    val hotspot = when {
                        c.displayPort == "display.main" || c.id == "rear-lcd" -> rearLcdTouchButton(c, surface)
                        c.displayPort == "display.evf" || c.id == "rear-evf" -> inspectButton(c).apply {
                            tooltip = Tooltip().apply { textProperty().bind(I18n.bindDynamic { I18n.t("studio.tip.rear_evf", tip(c)) }) }
                            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                                if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                                    setEvfWindowVisible(true)
                                    event.consume()
                                }
                            }
                        }
                        else -> inspectButton(c)
                    }.apply {
                        styleClass += "studio-display-hotspot"
                    }
                    container.children += hotspot
                    container
                }
                else -> if (c.hasRole("view-mode", "rear-view-mode")) rearViewModeButton(c) else inspectButton(c)
            }
            if ((hasMovieSwitchArt && c.buttonKey == "movieMode") || (hasPowerSwitchArt && c.buttonKey == "power") ||
                c.role == "exposure-mode-button") {
                overlay.styleClass += "studio-mechanical-control"
            }
            overlay.layoutX = c.x; overlay.layoutY = c.y
            overlay.minWidth = c.width; overlay.maxWidth = c.width; overlay.prefWidth = c.width
            overlay.minHeight = c.height; overlay.maxHeight = c.height; overlay.prefHeight = c.height
            overlay.resize(c.width, c.height)
            canvas.children += overlay
            if (panel.covers.isNotEmpty()) coverControls[c.id] = overlay
            if (!hasModeDialRotor && c.role != "exposure-mode-button" && c.componentId == "soc-adc" && c.settingKey == "modeDial") {
                val badgeW = 36.0
                val badgeH = 20.0
                val badge = Label(if (desiredModeDial == "default") "P" else desiredModeDial).apply {
                    id = "body-mode-dial-indicator"
                    styleClass += "studio-body-dial-badge"
                    isMouseTransparent = true
                    minWidth = badgeW; prefWidth = badgeW; maxWidth = badgeW
                    minHeight = badgeH; prefHeight = badgeH; maxHeight = badgeH
                    layoutX = c.x + (c.width - badgeW) / 2.0
                    layoutY = c.y + (c.height - badgeH) / 2.0
                }
                bodyModeDialIndicators += BodyIndicatorSlot(badge, c)
                canvas.children += badge
            } else if (!hasMovieSwitchArt && c.buttonKey == "movieMode") {
                val badgeW = 46.0
                val badgeH = 16.0
                val badge = Label("STILL").apply {
                    id = "body-still-movie-indicator"
                    styleClass += "studio-body-lever-badge"
                    isMouseTransparent = true
                    minWidth = badgeW; prefWidth = badgeW; maxWidth = badgeW
                    minHeight = badgeH; prefHeight = badgeH; maxHeight = badgeH
                    layoutX = c.x + c.width - badgeW - 2.0
                    layoutY = c.y + (c.height - badgeH) / 2.0
                }
                bodyStillMovieIndicators += BodyIndicatorSlot(badge, c)
                canvas.children += badge
            } else if (!hasFocusModeRotor && c.hasRole("focus-selector", "rear-focus-selector")) {
                val horizontal = c.width >= c.height * 1.5
                val badgeW = if (horizontal) 36.0 else 20.0
                val badgeH = 16.0
                val badge = Label(if (horizontal) "AF-S" else "S").apply {
                    id = "body-focus-mode-indicator"
                    styleClass += "studio-body-lever-badge"
                    isMouseTransparent = true
                    minWidth = badgeW; prefWidth = badgeW; maxWidth = badgeW
                    minHeight = badgeH; prefHeight = badgeH; maxHeight = badgeH
                    layoutX = if (horizontal) c.x + 2.0 else c.x + c.width - badgeW - 2.0
                    layoutY = if (horizontal) c.y + (c.height - badgeH) / 2.0 else c.y + 2.0
                }
                bodyFocusModeIndicators += BodyIndicatorSlot(badge, c)
                canvas.children += badge
            }
        }
        // Keep mouse-transparent indicators visually on top of sibling hotspots (e.g. top-dial-lock).
        artwork.lookupAll(".studio-control-proxy-target").forEach { hit ->
            val control = catalog.firstNotNullOf { face -> face.controls.find { it.id == hit.properties["data-control-proxy"] } }
            // A transparent SVG hit rectangle has empty painted bounds. Its declared geometry is the input area.
            val rect = hit as javafx.scene.shape.Rectangle
            val proxy = keyButton(control, proxyId = "proxy-" + hit.id.removePrefix("svg-")).apply {
                setMinSize(rect.width, rect.height)
                setPrefSize(rect.width, rect.height)
                setMaxSize(rect.width, rect.height)
                resizeRelocate(rect.x, rect.y, rect.width, rect.height)
            }
            canvas.children += proxy
        }
        bodyModeDialIndicators.forEach { it.label.toFront() }
        panel.covers.forEach { it.attach(artwork, coverControls, openCoversByTheme.getOrPut(bodyTheme.id) { mutableSetOf() }) }
        bodyStillMovieIndicators.forEach { it.label.toFront() }
        bodyFocusModeIndicators.forEach { it.label.toFront() }
        val pose = bodyTheme.layout.faces.getValue(panel.id)
        val crop = pose.crop
        canvases[panel.id] = canvas
        val turned = pose.rotationDegrees == 90.0
        if (turned) canvas.transforms += Rotate(90.0)
        canvas.translateX = if (turned) crop.y + crop.height else -crop.x
        canvas.translateY = if (turned) -crop.x else -crop.y
        val drawWidth = if (turned) crop.height else crop.width
        val drawHeight = if (turned) crop.width else crop.height
        val scaled = Group(canvas)
        val scale = Scale(1.0, 1.0, 0.0, 0.0); scaled.transforms += scale
        return object : Region() {
            init { children += scaled; minWidth = 0.0; minHeight = 0.0; prefWidth = panel.width; prefHeight = panel.height }
            override fun layoutChildren() {
                val factor = minOf(width / drawWidth, height / drawHeight).coerceAtLeast(0.0)
                clip = Rectangle(width,height)
                scale.x = factor; scale.y = factor
                scaled.layoutX = (width - drawWidth * factor) / 2
                scaled.layoutY = (height - drawHeight * factor) / 2
            }
        }
    }
    @Suppress("UNCHECKED_CAST") fun observeSnapshot(snapshot: Map<String, Any?>) {
        switchingToStill = snapshot["switchingToStill"] == true
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        val buttonState = components["buttons"]?.get("state") as? Map<String, Any?>
        buttonState?.let(inputs::observe)
        latchingSettingControls.forEach { control ->
            val tmdComponent = if (control.isEyeSensorControl()) components["tmd2772"] else null
            val tmdEnabled = ((tmdComponent?.get("state") as? Map<*, *>)?.get("enabled") as? Boolean)
                ?: ((tmdComponent?.get("config") as? Map<*, *>)?.get("enabled") as? Boolean)
                ?: false
            val component = if (tmdEnabled) tmdComponent else (components[control.componentId] ?: return@forEach)
            val stateMap = component?.get("state") as? Map<*, *>
            val configMap = component?.get("config") as? Map<*, *>
            val obs = (stateMap?.get(control.settingKey) as? Boolean) ?: (configMap?.get(control.settingKey) as? Boolean)
            observedSettings[control.id] = obs
            if (settingRequests[control.id]?.submission != StudioInputState.Submission.PENDING && obs != null) {
                desiredSettings[control.id] = obs
            }
        }
        val rearTouchComponent = components["rear-touch-ic"]
        val rearTouchEnabledFlag = ((rearTouchComponent?.get("state") as? Map<*, *>)?.get("enabled") as? Boolean)
            ?: ((rearTouchComponent?.get("config") as? Map<*, *>)?.get("enabled") as? Boolean)
            ?: false
        val touchSource = if (rearTouchEnabledFlag) rearTouchComponent else components["i2c-bus"]
        val observedTouchEnabled = ((touchSource?.get("state") as? Map<*, *>)?.get("touchEnabled") as? Boolean)
            ?: ((touchSource?.get("config") as? Map<*, *>)?.get("touchEnabled") as? Boolean)
        if (observedTouchEnabled != null) {
            if (!observedTouchEnabled) cancelAndReleaseTouch()
            touchEnabled = observedTouchEnabled
        }
        val vglibComponent = components["soc-vglib-init"]
        if (vglibComponent != null) {
            val vglibState = vglibComponent["state"] as? Map<*, *>
            observedLcdOutputActive = (vglibState?.get("scanout") as? Map<*, *>)?.get("outputActive") as? Boolean
            observedEvfOutputActive = (vglibState?.get("evfScanoutState") as? Map<*, *>)?.get("outputActive") as? Boolean
        }
        val adcComponent = components["soc-adc"]
        if (adcComponent != null) {
            val adcState = adcComponent["state"] as? Map<*, *>
            val adcConfig = adcComponent["config"] as? Map<*, *>
            val mode = (adcState?.get("modeDial") as? String) ?: (adcConfig?.get("modeDial") as? String)
            if (mode != null && mode in MODE_DIAL_CHOICES) {
                observedModeDial = mode
                if (!modeDialPending) {
                    desiredModeDial = mode
                    if (quickModeDialCombo.value != mode) {
                        syncingModeDialBox = true
                        quickModeDialCombo.value = mode
                        syncingModeDialBox = false
                    }
                    updateModeDialVisuals(mode)
                }
            }
            val detents = ((adcState?.get("rearDialDetents") as? Number) ?: (adcConfig?.get("rearDialDetents") as? Number))?.toLong()
            if (detents != null) {
                observedRearDialDetents = detents
                if (!rearDialPending) {
                    desiredRearDialDetents = detents
                    val text = detents.toString()
                    if (rearDialDetentsLabel.text != text) rearDialDetentsLabel.text = text
                    rearDialWheel.setDetents(detents)
                }
            }
        }
        val gpioComponent = components["soc-gpio"]
        if (gpioComponent != null) {
            val gpioState = gpioComponent["state"] as? Map<*, *>
            val gpioConfig = gpioComponent["config"] as? Map<*, *>
            val frontDetents = ((gpioState?.get("frontDialDetents") as? Number) ?: (gpioConfig?.get("frontDialDetents") as? Number))?.toLong()
            if (frontDetents != null) {
                observedFrontDialDetents = frontDetents
                if (!frontDialPending) {
                    desiredFrontDialDetents = frontDetents
                    val text = frontDetents.toString()
                    if (frontDialDetentsLabel.text != text) frontDialDetentsLabel.text = text
                    frontDialWheel.setDetents(frontDetents)
                }
            }
        }
        applyShoulderBacklightState(ShoulderLcdRenderer.extractBacklightState(components))
        updateButtons()
        lastSnapshot = snapshot
        shootingControlsPane.observeSnapshot(snapshot)
        sourceSelectorPane.observeSnapshot(snapshot)
        opticalFilterPane.observeSnapshot(snapshot)
        applyLayersFromSnapshot(snapshot)
    }
    @Suppress("UNCHECKED_CAST")
    private fun applyLayersFromSnapshot(snapshot: Map<String, Any?>) {
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        catalog.flatMap { it.layers }.forEach { layer ->
            val component = components[layer.componentId] ?: return@forEach
            val state = component["state"] as? Map<String, Any?>
            layers[layer.id]?.isVisible = state?.get(layer.settingKey) == layer.expected
        }
        catalog.flatMap { it.dynamicStates }.forEach { binding ->
            val node = dynamicNodes[binding.id] ?: return@forEach
            when (binding.mode) {
                "rotate-map" -> {
                    val component = binding.componentId?.let { components[it] }
                    val state = component?.get("state") as? Map<String, Any?>
                    val config = component?.get("config") as? Map<String, Any?>
                    val rawVal = when {
                        binding.componentId == "soc-adc" && binding.settingKey == "modeDial" && modeDialPending ->
                            desiredModeDial
                        binding.componentId == "soc-gpio" && binding.settingKey == "focusMode" ->
                            (state?.get("focusMode") ?: config?.get("focusMode"))?.toString()
                                ?: desiredFocusMode
                        component != null ->
                            (binding.settingKey?.let { state?.get(it) ?: config?.get(it) })?.toString()
                        else -> null
                    }
                    val angle = rawVal?.let { v ->
                        binding.angleMap[v] ?: when (v) {
                            "default" -> binding.angleMap["P"]
                            "AF-S" -> binding.angleMap["S"]
                            "AF-C" -> binding.angleMap["C"]
                            "MF" -> binding.angleMap["M"]
                            "S" -> binding.angleMap["AF-S"]
                            "C" -> binding.angleMap["AF-C"]
                            "M" -> binding.angleMap["MF"]
                            else -> null
                        }
                    } ?: return@forEach
                    dynamicRotates[binding.id]?.angle = angle
                }
                "led-binding" -> {
                    val component = binding.componentId?.let { components[it] } ?: return@forEach
                    val state = component["state"] as? Map<String, Any?>
                    val config = component["config"] as? Map<String, Any?>
                    val rawVal = binding.settingKey?.let { state?.get(it) ?: config?.get(it) }
                    val active = when (rawVal) {
                        is Boolean -> rawVal
                        is Number -> rawVal.toDouble() != 0.0
                        is String -> rawVal.equals("true", ignoreCase = true) || rawVal.equals("on", ignoreCase = true) || rawVal == "1"
                        else -> false
                    }
                    node.opacity = if (active) binding.activeOpacity else binding.inactiveOpacity
                }
                "highlight-button" -> {
                    val key = binding.buttonKey ?: return@forEach
                    val active = effectiveBodyButtonState(key)
                    node.opacity = if (active) binding.activeOpacity else binding.inactiveOpacity
                }
            }
        }
    }
    // StudioInputState reconciles a latched position on each fresh hardware snapshot.
    // An already-completed write can precede that snapshot, in both switch directions.
    private fun effectiveBodyButtonState(key: String): Boolean =
        inputs.desired(key) || (!inputs.isLatching(key) && inputs.observed(key) == true)

    private fun applyDynamicButtonHighlights() {
        catalog.flatMap { it.dynamicStates }.forEach { binding ->
            if (binding.mode == "highlight-button") {
                val key = binding.buttonKey ?: return@forEach
                val active = effectiveBodyButtonState(key)
                dynamicNodes[binding.id]?.opacity = if (active) binding.activeOpacity else binding.inactiveOpacity
            }
        }
    }
    fun observeAudioStatus(status: Map<String, Any?>?) {
        quickAudioStripPane.observeStatusMap(status)
    }
    fun releaseAll(includeLatching: Boolean = true) {
        cancelAndReleaseTouch()
        cancelAndReleaseViewMode()
        releaseAllMomentarySettings()
        cancelPendingReleases()
        inputs.releaseAll(includeLatching)
        updateButtons()
    }
    private fun applyShoulderBacklightState(backlightOn: Boolean) {
        if (backlightOn == observedShoulderBacklightOn) return
        observedShoulderBacklightOn = backlightOn
        val shoulder = surfaceForPort("display.shoulder", "top-shoulder") ?: return
        if (backlightOn) {
            if (ShoulderLcdRenderer.BACKLIT_STYLE_CLASS !in shoulder.container.styleClass) {
                shoulder.container.styleClass += ShoulderLcdRenderer.BACKLIT_STYLE_CLASS
            }
        } else {
            shoulder.container.styleClass.remove(ShoulderLcdRenderer.BACKLIT_STYLE_CLASS)
        }
        val count = shoulderFrameWidth * shoulderFrameHeight
        if (count > 0 && shoulderRawPixels.size == count) {
            if (shoulderTonePixels.size != count) shoulderTonePixels = IntArray(count)
            ShoulderLcdRenderer.toneMap(shoulderRawPixels, shoulderTonePixels, observedShoulderBacklightOn, count)
            shoulder.pixelSurface.updateFromArgb(shoulderFrameWidth, shoulderFrameHeight, shoulderTonePixels, mutateSourceInPlace = true)
        }
    }
    private fun presentSurfacePixels(surface: Surface, width: Int, height: Int, pixels: IntArray, mutateSourceInPlace: Boolean) {
        if (surface.port == "display.shoulder") {
            val count = width * height
            if (shoulderRawPixels.size != count) shoulderRawPixels = IntArray(count)
            if (shoulderTonePixels.size != count) shoulderTonePixels = IntArray(count)
            pixels.copyInto(shoulderRawPixels, 0, 0, count)
            shoulderFrameWidth = width
            shoulderFrameHeight = height
            ShoulderLcdRenderer.toneMap(shoulderRawPixels, shoulderTonePixels, observedShoulderBacklightOn, count)
            surface.pixelSurface.updateFromArgb(width, height, shoulderTonePixels, mutateSourceInPlace = true)
        } else {
            surface.pixelSurface.updateFromArgb(width, height, pixels, mutateSourceInPlace = mutateSourceInPlace)
        }
    }
    fun sequences(): Map<String, Long> = if (!owner.isShowing && !isEvfWindowShowing()) emptyMap() else surfaces.values.filter { it.port != null && isSurfaceVisible(it) }.associate { requireNotNull(it.port) to it.sequence }
    fun paint(frames: Map<String, Frame>) {
        require(frames.keys.all { it in setOf("display.main", "display.shoulder", "display.evf") })
        var viewfinderChanged = false
        surfaces.values.forEach { surface -> frames[surface.port]?.let { frame ->
            require(frame.width > 0 && frame.height > 0 && frame.width.toLong() * frame.height <= AsyncDisplayBridge.MAX_PIXELS && frame.sequence > 0 && frame.pixels.size == frame.width * frame.height)
            presentSurfacePixels(surface, frame.width, frame.height, frame.pixels, mutateSourceInPlace = false)
            surface.sequence = frame.sequence; surface.waiting.isVisible = false
            surface.port?.let { portSequences[it] = frame.sequence }
            if (surface.port == "display.main" || surface.port == "display.evf") {
                lastUpdatedDisplayPort = surface.port
                viewfinderChanged = true
            }
        } }
        if (viewfinderChanged) updateViewfinderStatus()
    }
    /** Consumes any newly published frames from [bridge] into zero-copy `PixelBuffer` surfaces without touching Owner. */
    fun paintFromBridge(bridge: AsyncDisplayBridge): Int {
        if (closed || (!owner.isShowing && !isEvfWindowShowing())) return 0
        var painted = 0
        var viewfinderChanged = false
        surfaces.values.forEach { surface ->
            val port = surface.port ?: return@forEach
            if (!isSurfaceVisible(surface)) return@forEach
            if (bridge.consumeLatest(port) { w, h, seq, pixels ->
                    presentSurfacePixels(surface, w, h, pixels, mutateSourceInPlace = true)
                    surface.sequence = seq
                    portSequences[port] = seq
                    surface.waiting.isVisible = false
                }) {
                painted++
                if (port == "display.main" || port == "display.evf") {
                    lastUpdatedDisplayPort = port
                    viewfinderChanged = true
                }
            }
        }
        if (viewfinderChanged) updateViewfinderStatus()
        return painted
    }
    fun waiting(controlId: String) = surfaces.getValue(controlId).waiting.isVisible
    fun state(): Map<String, Any?> = mapOf(
        "actualStageCount" to Window.getWindows().filterIsInstance<Stage>().count { it.isShowing },
        "layoutMode" to if (shootingWorkspace) "gfx-shooting-workspace" else "three-column-face-windows",
        "layoutBounds" to faceWindows.mapValues { (_,s)->windowBox(s) },
        "workspaceBounds" to screen.visualBounds.let { b->mapOf("x" to b.minX,"y" to b.minY,"width" to b.width,"height" to b.height) },
        "configurationWindow" to windowBox(owner),
        "captureMethod" to "actual-shown-scene-snapshots-at-observed-native-client-positions; excludes OS desktop and decoration pixels",
        "bodyThemeId" to bodyTheme.id,
        "uiThemeId" to uiTheme.id,
        "viewTransforms" to canvases.mapValues { (id,c)-> val crop=bodyTheme.layout.faces.getValue(id).crop;val b=c.localToParent(BoundingBox(crop.x,crop.y,crop.width,crop.height));mapOf("rotationDegrees" to c.transforms.filterIsInstance<Rotate>().sumOf { it.angle },"x" to b.minX,"y" to b.minY,"width" to b.width,"height" to b.height) },
        "lastInspectedControlId" to lastInspectedControlId,
        "faceWindowStyles" to faceWindows.mapValues { (_,s)->s.style.toString() },
        "faceStageIdentities" to faceWindows.mapValues { (_,s)->System.identityHashCode(s) },
        "windowToggleStates" to viewToggles.mapValues { (_,c)->c.isSelected },
        "createdFaceStageCount" to faceWindows.size,
        "screen" to mapOf("scope" to "primary-screen-JavaFX-logical-coordinates","dpi" to screen.dpi,"outputScaleX" to owner.outputScaleX,"outputScaleY" to owner.outputScaleY,"mixedDpiRuntimeQualified" to false),
        "viewIds" to catalog.map { it.id }, "selectedView" to selectedView,
        "attachedViewIds" to regions.filter { (id,n) -> n.scene === faceWindows.getValue(id).scene && faceWindows.getValue(id).isShowing }.keys.toList(),
        "controls" to catalog.sumOf { it.controls.size }, "buttonKeys" to buttons.keys.toList(),
        "latchMode" to latch.isSelected, "lockedButtons" to lockedButtonSummaryIds(), "input" to inputs.diagnostics(),
        "displays" to surfaces.mapValues { (_, s) -> mapOf("viewId" to s.viewId, "port" to s.port, "sequence" to s.sequence, "waiting" to s.waiting.isVisible) },
        "shoulderBacklightOn" to observedShoulderBacklightOn,
        "modeDial" to observedModeDial,
        "rearDialDetents" to observedRearDialDetents,
        "frontDialDetents" to observedFrontDialDetents,
        "viewfinder" to viewfinderStatus().toMap(),
        "evfWindowShowing" to isEvfWindowShowing(),
        "layers" to layers.mapValues { (_, n) -> n.isVisible },
        "typography" to StudioTypography.observation(),
        "firmwareWiringVerified" to false, "firmwareMenuVerified" to false,
        "layoutBasis" to "GFX100S official manual and photographs; geometry remains an original interpretation",
        "visualBasis" to "Observed ETERNA simulator screenshots: product illustration and transparent physical hotspots only")
    private fun windowBox(s:Stage)=mapOf("x" to s.x,"y" to s.y,"width" to s.width,"height" to s.height,"visible" to s.isShowing,"clientWidth" to s.scene.width,"clientHeight" to s.scene.height)
    /** Current actual shown Scene snapshots, composed at observed native client coordinates. OS desktop/decoration pixels are excluded. */
    fun nativeScreenshot(file:Path) {
        val v=screen.visualBounds;val image=WritableImage(ceil(v.width).toInt(),ceil(v.height).toInt())
        val backdropArgb = runCatching {
            val hex = uiTheme.backdropHex.removePrefix("#").toInt(16)
            (0xFF shl 24) or hex
        }.getOrDefault(0xff161515.toInt())
        image.pixelWriter.setPixels(0,0,image.width.toInt(),image.height.toInt(),PixelFormat.getIntArgbInstance(),IntArray(image.width.toInt()*image.height.toInt()){ backdropArgb },0,image.width.toInt())
        (faceWindows.values+owner).filter { it.isShowing }.forEach { s ->
            s.scene.root.applyCss();s.scene.root.layout()
            val shot=s.scene.snapshot(null)
            val dx=(s.x+s.scene.x-v.minX).toInt().coerceAtLeast(0);val dy=(s.y+s.scene.y-v.minY).toInt().coerceAtLeast(0)
            val cw=minOf(shot.width.toInt(),image.width.toInt()-dx);val ch=minOf(shot.height.toInt(),image.height.toInt()-dy)
            if(cw>0&&ch>0) image.pixelWriter.setPixels(dx,dy,cw,ch,shot.pixelReader,0,0)
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",file.toFile())
    }
    fun dragSmoke(observe:()->Map<String,Any?>):Map<String,Any?> {
        val beforeButtons=observe()
        val rows=catalog.map { p ->
            val s=faceWindows.getValue(p.id);val x=s.x;val y=s.y;val handle=dragHandles.getValue(p.id)
            fun event(type:EventType<MouseEvent>,sx:Double,sy:Double)=MouseEvent(type,2.0,2.0,sx,sy,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,true,null)
            handle.fireEvent(event(MouseEvent.MOUSE_PRESSED,x+10,y+5));handle.fireEvent(event(MouseEvent.MOUSE_DRAGGED,x+30,y+20));handle.fireEvent(event(MouseEvent.MOUSE_RELEASED,x+30,y+20))
            val row=mapOf("viewId" to p.id,"beforeX" to x,"beforeY" to y,"afterX" to s.x,"afterY" to s.y,"stageStyle" to s.style.toString(),"dragHandleOnly" to true)
            position(p.id,s);row
        }
        return mapOf("rows" to rows,"buttonsBefore" to beforeButtons,"buttonsAfter" to observe(),"scope" to "Actual JavaFX drag-bar handlers with native Stage position readback; no physical pointer injection")
    }
    fun beginFrontHitSmoke(observe:()->Map<String,Any?>) {
        val s=faceWindows.getValue("front");s.isAlwaysOnTop=true;s.toFront();s.requestFocus();s.scene.root.applyCss();s.scene.root.layout()
        lastInspectedControlId = null
        frontMouseEvents.clear()
        sentFrontHitPress = false
        val node=requireNotNull(s.scene.lookup("#inspect-front-lens-release"))
        val local=javafx.geometry.Point2D(node.boundsInLocal.width/2,node.boundsInLocal.height/2)
        val target=node.localToScreen(local);val back=node.screenToLocal(target)
        frontHitStart=mapOf("targetId" to "front-lens-release","screenX" to target.x,"screenY" to target.y,"inverseError" to back.distance(local),"buttonsBefore" to observe(),"transform" to (state()["viewTransforms"] as Map<*,*>)["front"])
        val robot=Robot();oldPointer=robot.mouseX to robot.mouseY
        robot.mouseMove(target.x,target.y)
    }
    fun pressFrontHitSmoke() {
        val stage = faceWindows.getValue("front")
        val target = requireNotNull(stage.scene.lookup("#inspect-front-lens-release"))
        val point = target.localToScreen(target.boundsInLocal.centerX, target.boundsInLocal.centerY)
        val robot = Robot()
        robot.mouseMove(point.x, point.y)
        val x = robot.mouseX
        val y = robot.mouseY
        frontPointerAtPress = mapOf("x" to x, "y" to y, "stageFocused" to stage.isFocused)
        if (stage.isFocused && point.distance(x, y) <= 1.5) {
            robot.mousePress(MouseButton.PRIMARY)
            sentFrontHitPress = true
        }
    }
    fun releaseFrontHitSmoke() {
        if (sentFrontHitPress) {
            Robot().mouseRelease(MouseButton.PRIMARY)
            sentFrontHitPress = false
        }
    }
    fun finishFrontHitSmoke(observe:()->Map<String,Any?>):Map<String,Any?> {
        oldPointer?.let { Robot().mouseMove(it.first,it.second) }
        faceWindows.getValue("front").isAlwaysOnTop=false
        return requireNotNull(frontHitStart)+mapOf("actualInspectedControlId" to lastInspectedControlId,"pointerAtPress" to frontPointerAtPress,"mouseEvents" to frontMouseEvents.toList(),"buttonsAfter" to observe(),"method" to "actual JavaFX Robot click at transformed front hotspot with inverse-coordinate and owner readback")
    }
    fun lifecycleSmoke(directory:Path,observe:()->Map<String,Any?>):Map<String,Any?> {
        val rows=catalog.map { p ->
            val stage=faceWindows.getValue(p.id);val identity=System.identityHashCode(stage)
            viewToggles.getValue(p.id).fire();val hidden=!stage.isShowing && !viewToggles.getValue(p.id).isSelected
            
            setFaceVisible(p.id,false);val duplicateHide=!stage.isShowing
            viewToggles.getValue(p.id).fire();setFaceVisible(p.id,true)
            val reopened=stage.isShowing && viewToggles.getValue(p.id).isSelected
            
            stage.fireEvent(WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST));val closeSynced=!stage.isShowing && !viewToggles.getValue(p.id).isSelected
            setFaceVisible(p.id,true)
            mapOf("viewId" to p.id,"hiddenByToggle" to hidden,"duplicateHideStable" to duplicateHide,"reopened" to reopened,"closeRequestSynced" to closeSynced,"sameStageIdentity" to (identity==System.identityHashCode(faceWindows.getValue(p.id))),"showingAfterReopen" to stage.isShowing,"state" to state())
        }
        latch.isSelected=false;buttons.getValue("menu").fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED,1.0,1.0,1.0,1.0,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,true,null))
        val before=observe();setFaceVisible("rear",false);val after=observe();setFaceVisible("rear",true)
        return mapOf("schema" to 1,"rows" to rows,"beforeHideButtons" to before,"afterHideButtons" to after,"stageCount" to faceWindows.size,"final" to state())
    }
    fun screenshots(directory: Path): List<Map<String, Any?>> {
        Files.createDirectories(directory)
        val original = selectedView
        val captures = catalog.map { panel ->
            selectView(panel.id); owner.scene.root.applyCss(); owner.scene.root.layout()
            val file = "ui-studio-" + panel.id + ".png"
            nativeScreenshot(directory.resolve(file))
            state() + mapOf("file" to file)
        }
        selectView(original)
        return captures
    }
    /** Actual JavaFX event handlers, with independent owner-thread ButtonPort observations after each action. */
    fun interactionSmoke(observe: () -> Map<String, Any?>): Map<String, Any?> {
        val observations = linkedMapOf<String, Any?>()
        fun record(name: String) { observations[name] = observe() }
        fun mouse(key: String, type: EventType<MouseEvent>) {
            buttons.getValue(key).fireEvent(MouseEvent(type, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false, false, false, true, null))
        }
        latch.isSelected = false; releaseAll(); selectView("rear")
        mouse("menu", MouseEvent.MOUSE_PRESSED); record("mousePressed")
        mouse("menu", MouseEvent.MOUSE_RELEASED); record("mouseReleased")
        buttons.getValue("up").fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false)); record("keyboardPressed")
        buttons.getValue("up").fireEvent(KeyEvent(KeyEvent.KEY_RELEASED, " ", " ", KeyCode.SPACE, false, false, false, false)); record("keyboardReleased")
        selectView("top"); buttons.getValue("power").fire(); record("powerHeld")
        selectView("rear"); mouse("menu", MouseEvent.MOUSE_PRESSED); record("beforeViewSwitch")
        selectView("top"); record("viewSwitchReleased")
        selectView("rear"); record("viewRestored")
        mouse("menu", MouseEvent.MOUSE_PRESSED); record("beforeFocusLoss")
        val implicitExit = Platform.isImplicitExit()
        try { Platform.setImplicitExit(false); setFaceVisible("rear",false); record("focusLossReleased"); setFaceVisible("rear",true) } finally { Platform.setImplicitExit(implicitExit) }
        latch.isSelected = true; buttons.getValue("menu").fire(); selectView("top"); record("debugLatchSurvivesViewSwitch")
        releaseAll(); record("releaseAll"); selectView("rear")
        return mapOf("schema" to 2, "backend" to "actual JavaFX events with owner-thread ButtonPort readback", "observations" to observations,
            "limitation" to "Host controls only; physical electrical and firmware consumption are unqualified")
    }
    fun close(releaseLatching: Boolean) {
        if (!closed) {
            closed = true
            localeListener?.let { I18n.localeProperty.removeListener(it) }
            localeListener = null
            releaseAll(includeLatching = releaseLatching)
            evfWindow.close()
            faceWindows.values.forEach { it.close() }
            owner.hide()
        }
    }
    override fun close() = close(releaseLatching = true)

    companion object {
        private const val LOCK_ICON = "\uD83D\uDD12"
        private const val UNLOCK_ICON = "\uD83D\uDD13"
        private const val VIEW_MODE_HOLD_KEY = "viewMode"
        private const val TOUCH_HOLD_KEY = "touch"
        private const val TOUCH_MOUSE_SOURCE = "mouse:rear-lcd"
        private const val TOUCH_FLICK_THRESHOLD_SQ: Int =
            MomentaryHoldPolicy.TOUCH_FLICK_THRESHOLD_PX * MomentaryHoldPolicy.TOUCH_FLICK_THRESHOLD_PX
        private const val SWIPE_RELEASE_MAX_WALL_MS: Long = 150L
        private fun touchDistanceSq(a: TouchPointCoord, b: TouchPointCoord): Int {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return dx * dx + dy * dy
        }
        val MODE_DIAL_CHOICES: List<String> = listOf("default", "P", "S", "A", "M", "C1", "C2", "C3", "C4", "C5", "C6")
        val MODE_DIAL_CYCLE: List<String> = listOf("P", "S", "A", "M", "C1", "C2", "C3", "C4", "C5", "C6")
        private const val REAR_DIAL_MIN_DETENTS = -10_000L
        private const val REAR_DIAL_MAX_DETENTS = 10_000L
        private const val FRONT_DIAL_MIN_DETENTS = -10_000L
        private const val FRONT_DIAL_MAX_DETENTS = 10_000L
        private const val BODY_DRAG_THRESHOLD_PX = 6.0
        private const val BODY_DIAL_DRAG_PX = 14.0
        private const val SIDEBAR_DIAL_DRAG_PX = 14.0
        private const val MODE_DIAL_DRAG_PX = 16.0
        private const val MODE_DIAL_DRAG_DEG = 22.0
        private const val BODY_LEVER_DRAG_PX = 12.0
        private const val STILL_MOVIE_DRAG_PX = 10.0
        private const val COMMAND_DIAL_HOLD_MS = 180.0
    }
}
