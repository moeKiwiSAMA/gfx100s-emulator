package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.api.WebcamDevice
import com.github.nyabanban.fuji.emulator.api.WebcamDeviceDiscovery
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.OverrunStyle
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import javafx.util.StringConverter
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Top-level (Level 1) scene taxonomy for the CMOS input source selector:
 * - [DIM_3D]: Built-in analytic 3D optical scenes with true per-pixel metric depth maps.
 * - [DIM_2D]: 2D planar media and external capture inputs (image, video, screen, webcam, stream).
 */
enum class SourceSceneDimension(
    val labelKey: String,
    val badgeKey: String,
) {
    DIM_3D("source.dim.3d.label", "source.dim.3d.badge"),
    DIM_2D("source.dim.2d.label", "source.dim.2d.badge"),
    ;

    val label: String get() = I18n.t(labelKey)
    val badge: String get() = I18n.t(badgeKey)
    val labelZh: String get() = label
    val badgeZh: String get() = badge
}

/**
 * High-level user-facing input source categories shown in the right-hand sidebar list.
 */
enum class InputSourceCategory(
    val labelKey: String,
    val defaultKind: String,
    val promptKey: String,
    val supportsFileChooser: Boolean,
    val supportsScreenRegion: Boolean,
    val dimension: SourceSceneDimension,
    val supportsWebcamPicker: Boolean = false,
) {
    STATIC_IMAGE(
        "source.cat.static_image.label",
        "JPEG",
        "source.cat.static_image.prompt",
        true,
        false,
        SourceSceneDimension.DIM_2D,
    ),
    LOCAL_VIDEO(
        "source.cat.local_video.label",
        "ffmpeg-pipe",
        "source.cat.local_video.prompt",
        true,
        false,
        SourceSceneDimension.DIM_2D,
    ),
    GAME_SCREEN(
        "source.cat.game_screen.label",
        "screen-capture",
        "source.cat.game_screen.prompt",
        false,
        true,
        SourceSceneDimension.DIM_2D,
    ),
    WEBCAM(
        "source.cat.webcam.label",
        "ffmpeg-pipe",
        "source.cat.webcam.prompt",
        false,
        false,
        SourceSceneDimension.DIM_2D,
        supportsWebcamPicker = true,
    ),
    NETWORK_STREAM(
        "source.cat.network_stream.label",
        "stream-ingest",
        "source.cat.network_stream.prompt",
        false,
        false,
        SourceSceneDimension.DIM_2D,
    ),
    OPTICAL_SCENE(
        "source.cat.optical_scene.label",
        "optical-scene",
        "source.cat.optical_scene.prompt",
        false,
        false,
        SourceSceneDimension.DIM_3D,
    ),
    ;

    val label: String get() = I18n.t(labelKey)
    val labelZh: String get() = label
    val promptText: String get() = I18n.t(promptKey)

    companion object {
        val TWO_D_CATEGORIES: List<InputSourceCategory> = listOf(
            STATIC_IMAGE,
            LOCAL_VIDEO,
            GAME_SCREEN,
            WEBCAM,
            NETWORK_STREAM,
        )

        fun categoriesFor(dimension: SourceSceneDimension): List<InputSourceCategory> = when (dimension) {
            SourceSceneDimension.DIM_3D -> listOf(OPTICAL_SCENE)
            SourceSceneDimension.DIM_2D -> TWO_D_CATEGORIES
        }

        fun infer(sourceKind: String, sourceFile: String): InputSourceCategory {
            val kind = sourceKind.trim().lowercase(Locale.ROOT)
            val ref = sourceFile.trim().lowercase(Locale.ROOT)
            return when (kind) {
                "optical-scene" -> OPTICAL_SCENE
                "screen-capture" -> GAME_SCREEN
                "stream-ingest" -> if (ref.contains("game") || ref.contains("obs")) GAME_SCREEN else NETWORK_STREAM
                "jpeg", "png" -> STATIC_IMAGE
                "ffmpeg-pipe" -> when {
                    ref.startsWith("webcam:") -> WEBCAM
                    ref.startsWith("rtsp://") || ref.startsWith("rtmp://") || ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("udp://") -> NETWORK_STREAM
                    else -> LOCAL_VIDEO
                }
                else -> STATIC_IMAGE
            }
        }
    }
}

data class SourcePresetChip(
    val labelKey: String,
    val category: InputSourceCategory,
    val sourceKind: String,
    val sourceFile: String,
    val loop: Boolean = true,
    val subtitleKey: String = "",
) {
    val label: String get() = I18n.t(labelKey)
    val subtitle: String get() = if (subtitleKey.isNotEmpty()) I18n.t(subtitleKey) else ""
    val dimension: SourceSceneDimension get() = category.dimension
}

/**
 * Immutable snapshot of `cmos` component source configuration and live telemetry.
 */
data class CmosSourceStatus(
    val sourceKind: String,
    val sourceFile: String,
    val activeSourceRef: String,
    val loop: Boolean,
    val pipeline: String,
    val framesExposed: Long,
    val framesReceived: Long,
    val framesDropped: Long,
    val opticsFilterActive: Boolean,
    val hasDepthBuffer: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val streamIngestPort: Int?,
    val lastSourceError: String?,
) {
    val inferredCategory: InputSourceCategory
        get() = InputSourceCategory.infer(sourceKind, activeSourceRef.ifBlank { sourceFile })

    val dimension: SourceSceneDimension
        get() = if (hasDepthBuffer || inferredCategory == InputSourceCategory.OPTICAL_SCENE) {
            SourceSceneDimension.DIM_3D
        } else {
            SourceSceneDimension.DIM_2D
        }

    /**
     * Compact 1-line status shown in the sidebar footer when telemetry details are collapsed.
     */
    fun compactStatusText(): String {
        if (!lastSourceError.isNullOrBlank()) {
            return I18n.t("source.status.error", lastSourceError.lineSequence().first().take(48))
        }
        val ref = activeSourceRef.ifBlank { sourceFile }
        val matched = PRESET_CHIPS.firstOrNull { chip ->
            chip.sourceKind.equals(sourceKind, ignoreCase = true) &&
                ref.equals(chip.sourceFile, ignoreCase = true)
        }
        val displayTitle = matched?.label ?: ref.substringAfterLast('/').substringAfterLast('\\').ifBlank { sourceKind }
        val parts = mutableListOf<String>()
        parts += dimension.badge
        parts += displayTitle
        if (sourceWidth > 0 && sourceHeight > 0) parts += "${sourceWidth}\u00D7${sourceHeight}"
        parts += I18n.t(if (opticsFilterActive) "source.status.optics.on" else "source.status.optics.off")
        return parts.joinToString(" \u00B7 ")
    }

    /**
     * Full structured telemetry text (preserved for backward compatibility and expandable details).
     */
    fun summaryText(): String {
        val parts = mutableListOf<String>()
        parts += I18n.t("source.summary.kind", sourceKind)
        if (pipeline.isNotBlank()) parts += I18n.t("source.summary.pipeline", pipeline)
        parts += I18n.t("source.summary.frames", framesExposed)
        parts += I18n.t(if (opticsFilterActive) "source.summary.optics.on" else "source.summary.optics.off")
        if (hasDepthBuffer) parts += I18n.t("source.summary.depth3d")
        if (sourceWidth > 0 && sourceHeight > 0) parts += "${sourceWidth}\u00D7${sourceHeight}"
        if (streamIngestPort != null && streamIngestPort > 0) parts += I18n.t("source.summary.port", streamIngestPort)
        val base = parts.joinToString(" \u00B7 ")
        return if (!lastSourceError.isNullOrBlank()) "$base\n${I18n.t("source.summary.error", lastSourceError)}" else base
    }

    companion object {
        val PRESET_CHIPS_3D: List<SourcePresetChip> = listOf(
            SourcePresetChip(
                labelKey = "source.preset.qr_ball.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://qr-ball",
                loop = true,
                subtitleKey = "source.preset.qr_ball.subtitle",
            ),
            SourcePresetChip(
                labelKey = "source.preset.mona_lisa.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://mona-lisa",
                loop = true,
                subtitleKey = "source.preset.mona_lisa.subtitle",
            ),
            SourcePresetChip(
                labelKey = "source.preset.milk_frog.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://milk-frog",
                loop = true,
                subtitleKey = "source.preset.milk_frog.subtitle",
            ),
            SourcePresetChip(
                labelKey = "source.preset.depth_studio.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://depth-studio",
                loop = true,
                subtitleKey = "source.preset.depth_studio.subtitle",
            ),
            SourcePresetChip(
                labelKey = "source.preset.star_bokeh.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://star-bokeh",
                loop = true,
                subtitleKey = "source.preset.star_bokeh.subtitle",
            ),
            SourcePresetChip(
                labelKey = "source.preset.bokeh_city.label",
                category = InputSourceCategory.OPTICAL_SCENE,
                sourceKind = "optical-scene",
                sourceFile = "scene://bokeh-city",
                loop = true,
                subtitleKey = "source.preset.bokeh_city.subtitle",
            ),
        )

        val PRESET_CHIPS_2D: List<SourcePresetChip> = emptyList()

        val PRESET_CHIPS: List<SourcePresetChip> = PRESET_CHIPS_3D

        fun presetsFor(dimension: SourceSceneDimension): List<SourcePresetChip> = when (dimension) {
            SourceSceneDimension.DIM_3D -> PRESET_CHIPS_3D
            SourceSceneDimension.DIM_2D -> PRESET_CHIPS_2D
        }

        /**
         * Resolves UI category + user source text into a validated `cmos` patch map (`sourceKind`, `sourceFile`, `loop`).
         */
        fun resolveSourcePatch(
            category: InputSourceCategory,
            rawRef: String,
            loop: Boolean,
        ): Result<Map<String, Any>> {
            val ref = rawRef.trim()
            if (ref.isEmpty()) {
                return Result.failure(IllegalArgumentException(I18n.t("source.error.empty_ref")))
            }
            val lower = ref.lowercase(Locale.ROOT)
            val resolvedKind = when (category) {
                InputSourceCategory.STATIC_IMAGE -> if (lower.endsWith(".png")) "PNG" else "JPEG"
                InputSourceCategory.LOCAL_VIDEO -> "ffmpeg-pipe"
                InputSourceCategory.WEBCAM -> "ffmpeg-pipe"
                InputSourceCategory.OPTICAL_SCENE -> "optical-scene"
                InputSourceCategory.NETWORK_STREAM -> when {
                    lower.startsWith("tcp://") || lower.startsWith("udp://") ||
                        lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost") -> "stream-ingest"
                    else -> "ffmpeg-pipe"
                }
                InputSourceCategory.GAME_SCREEN -> when {
                    lower.startsWith("tcp://") || lower.startsWith("udp://") ||
                        lower.startsWith("http://") -> "stream-ingest"
                    else -> "screen-capture"
                }
            }
            return Result.success(
                linkedMapOf(
                    "sourceKind" to resolvedKind,
                    "sourceFile" to ref,
                    "loop" to loop,
                ),
            )
        }

        /**
         * Builds a `screen://region/x,y,w,h?fps=N` URI from optional screen-capture helper fields.
         */
        fun buildScreenRegionRef(
            xText: String,
            yText: String,
            wText: String,
            hText: String,
            fpsText: String,
        ): Result<String> {
            val fps = fpsText.trim().ifEmpty { "24" }.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException(I18n.t("source.error.fps_int")))
            if (fps !in 1..60) {
                return Result.failure(IllegalArgumentException(I18n.t("source.error.fps_range")))
            }
            val xTrim = xText.trim()
            val yTrim = yText.trim()
            val wTrim = wText.trim()
            val hTrim = hText.trim()
            if (xTrim.isEmpty() && yTrim.isEmpty() && wTrim.isEmpty() && hTrim.isEmpty()) {
                return Result.success("screen://primary?fps=$fps")
            }
            val x = xTrim.ifEmpty { "0" }.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException(I18n.t("source.error.x_int")))
            val y = yTrim.ifEmpty { "0" }.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException(I18n.t("source.error.y_int")))
            val w = wTrim.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException(I18n.t("source.error.w_int")))
            val h = hTrim.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException(I18n.t("source.error.h_int")))
            if (x < 0 || y < 0 || w <= 0 || h <= 0) {
                return Result.failure(IllegalArgumentException(I18n.t("source.error.region_bounds")))
            }
            return Result.success("screen://region/$x,$y,$w,$h?fps=$fps")
        }

        fun fromMaps(config: Map<String, Any?>?, state: Map<String, Any?>?): CmosSourceStatus {
            fun get(key: String): Any? = state?.get(key) ?: config?.get(key)
            val kind = (get("sourceKind") as? String) ?: "JPEG"
            val file = (get("sourceFile") as? String).orEmpty()
            val activeRef = (state?.get("activeSourceRef") as? String)?.takeIf { it.isNotBlank() } ?: file
            val loop = (get("loop") as? Boolean) ?: true
            val pipeline = (state?.get("pipeline") as? String).orEmpty()
            val framesExposed = (state?.get("framesExposed") as? Number)?.toLong() ?: 0L
            val framesReceived = (state?.get("framesReceived") as? Number)?.toLong() ?: 0L
            val framesDropped = (state?.get("framesDropped") as? Number)?.toLong() ?: 0L
            val opticsActive = (state?.get("opticsFilterActive") as? Boolean) ?: false
            val hasDepth = (state?.get("hasDepthBuffer") as? Boolean) ?: false
            val sw = (state?.get("sourceWidth") as? Number)?.toInt() ?: 0
            val sh = (state?.get("sourceHeight") as? Number)?.toInt() ?: 0
            val port = (state?.get("streamIngestPort") as? Number)?.toInt()
            val lastErr = (state?.get("lastSourceError") as? String)?.takeIf { it.isNotBlank() }
            return CmosSourceStatus(
                sourceKind = kind,
                sourceFile = file,
                activeSourceRef = activeRef,
                loop = loop,
                pipeline = pipeline,
                framesExposed = framesExposed,
                framesReceived = framesReceived,
                framesDropped = framesDropped,
                opticsFilterActive = opticsActive,
                hasDepthBuffer = hasDepth,
                sourceWidth = sw,
                sourceHeight = sh,
                streamIngestPort = port,
                lastSourceError = lastErr,
            )
        }
    }
}

/**
 * Right-hand sidebar card embedded in [CameraStudio] providing a crisp 2-level scene selector:
 * - Level 1: Segmented toggle between 3D Optical Scenes (true depth) and 2D Planar / External Input.
 * - Level 2: Contextual sub-menu showing ONLY the cards, categories, and controls relevant to the active dimension.
 */
class SourceSelectorPane(
    private val ownerWindow: () -> Window?,
    private val patchCmos: ((Map<String, Any>) -> CompletableFuture<*>)?,
    private val onOpenLensProfile: (() -> Unit)? = null,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val discoverWebcams: () -> List<WebcamDevice> = { WebcamDeviceDiscovery.discover() },
    private val asyncWebcamDiscovery: Boolean = true,
) {
    private var syncing = false
    private var observedOnce = false
    private var lastObservedKey: String? = null
    private var lastStatus: CmosSourceStatus? = null
    private var telemetryExpanded = false
    private var custom3dExpanded = false
    private var webcamScannedOnce = false
    private var webcamScanning = false

    var activeDimension: SourceSceneDimension = SourceSceneDimension.DIM_3D
        private set

    val active2DCategory: InputSourceCategory
        get() = categoryCombo.value ?: InputSourceCategory.STATIC_IMAGE

    private val modeGroup = ToggleGroup()

    private val mode3dToggle = ToggleButton().apply {
        id = "source-mode-3d"
        textProperty().bind(I18n.bind(SourceSceneDimension.DIM_3D.labelKey))
        toggleGroup = modeGroup
        isSelected = true
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val mode2dToggle = ToggleButton().apply {
        id = "source-mode-2d"
        textProperty().bind(I18n.bind(SourceSceneDimension.DIM_2D.labelKey))
        toggleGroup = modeGroup
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val modeSegmentBar = HBox(4.0, mode3dToggle, mode2dToggle).apply {
        id = "source-mode-bar"
        alignment = Pos.CENTER_LEFT
    }

    // --- Level 2A: 3D Optical Scene Sub-Menu ---
    private val depth3dBadge = Label().apply {
        id = "source-3d-depth-badge"
        textProperty().bind(I18n.bind("source.depth3d.badge"))
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("source.depth3d.badge")) }
        styleClass += "source-badge-3d"
    }

    private val scene3dButtons = LinkedHashMap<String, Button>()

    private val scene3dGrid = GridPane().apply {
        id = "source-3d-presets"
        hgap = 4.0
        vgap = 4.0
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        columnConstraints.addAll(
            ColumnConstraints().apply { percentWidth = 50.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
            ColumnConstraints().apply { percentWidth = 50.0; minWidth = 0.0; hgrow = Priority.ALWAYS },
        )
        CmosSourceStatus.PRESET_CHIPS_3D.forEachIndexed { idx, chip ->
            val cardBtn = Button().apply {
                id = "source-3d-card-${chip.sourceFile.removePrefix("scene://")}"
                textProperty().bind(I18n.bind(chip.labelKey))
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
                styleClass += listOf("studio-chip-btn", "source-card-3d")
                tooltip = Tooltip().apply {
                    textProperty().bind(
                        I18n.bindDynamic {
                            "${I18n.t(chip.labelKey)}\n${I18n.t(chip.subtitleKey)}\n(${chip.sourceFile})"
                        },
                    )
                }
                alignment = Pos.CENTER_LEFT
                setOnAction {
                    select3dPreset(chip)
                }
            }
            scene3dButtons[chip.sourceFile.lowercase(Locale.ROOT)] = cardBtn
            add(cardBtn, idx % 2, idx / 2)
        }
    }

    private val custom3dToggle = Button().apply {
        id = "source-3d-custom-toggle"
        styleClass += listOf("studio-chip-btn", "source-link-btn")
        setOnAction {
            custom3dExpanded = !custom3dExpanded
            updateCustom3dVisibility()
        }
    }

    private val custom3dField = TextField("scene://qr-ball").apply {
        id = "source-3d-ref-field"
        promptTextProperty().bind(I18n.bind(InputSourceCategory.OPTICAL_SCENE.promptKey))
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val custom3dApplyBtn = Button().apply {
        id = "source-3d-apply-btn"
        textProperty().bind(I18n.bind("source.custom3d.apply"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
        setOnAction {
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.OPTICAL_SCENE, custom3dField.text, true)
                .onSuccess { patch ->
                    applySourcePatch(patch, I18n.t("source.custom3d.status.switched", patch["sourceFile"]))
                }
                .onFailure(onError)
        }
    }

    private val custom3dRow = HBox(5.0, custom3dField, custom3dApplyBtn).apply {
        id = "source-3d-custom-row"
        alignment = Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }

    private val panel3d = VBox(
        4.0,
        depth3dBadge,
        scene3dGrid,
        custom3dToggle,
        custom3dRow,
    ).apply {
        id = "source-panel-3d"
        minWidth = 0.0
    }

    // --- Level 2B: 2D Planar / External Input Sub-Menu ---
    private val categoryCombo = ComboBox<InputSourceCategory>().apply {
        id = "source-category-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(InputSourceCategory.TWO_D_CATEGORIES)
        converter = object : StringConverter<InputSourceCategory>() {
            override fun toString(item: InputSourceCategory?): String = item?.label.orEmpty()
            override fun fromString(string: String?): InputSourceCategory =
                InputSourceCategory.TWO_D_CATEGORIES.firstOrNull { it.label == string } ?: InputSourceCategory.STATIC_IMAGE
        }
        setCellFactory {
            object : ListCell<InputSourceCategory>() {
                override fun updateItem(item: InputSourceCategory?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        value = InputSourceCategory.STATIC_IMAGE
    }

    private val webcamPlaceholderLabel = Label(I18n.t("source.webcam.empty")).apply {
        styleClass += "studio-hint-text"
    }

    private val webcamCombo = ComboBox<WebcamDevice>().apply {
        id = "source-webcam-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        promptText = I18n.t("source.webcam.select.prompt")
        placeholder = webcamPlaceholderLabel
        HBox.setHgrow(this, Priority.ALWAYS)
        converter = object : StringConverter<WebcamDevice>() {
            override fun toString(item: WebcamDevice?): String = item?.displayName.orEmpty()
            override fun fromString(string: String?): WebcamDevice? = items.firstOrNull { it.displayName == string }
        }
        fun webcamCell() = object : ListCell<WebcamDevice>() {
            init {
                minWidth = 0.0
                textOverrun = OverrunStyle.ELLIPSIS
            }
            override fun updateItem(item: WebcamDevice?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.displayName
                tooltip = if (empty || item == null) null else Tooltip("${item.displayName}\n${item.sourceRef}")
            }
        }
        setCellFactory { webcamCell() }
        buttonCell = webcamCell()
    }

    private val refreshWebcamButton = Button("\u21BB").apply {
        id = "source-webcam-refresh-btn"
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("source.webcam.refresh.tooltip")) }
    }

    private val webcamPickerBox = HBox(5.0, webcamCombo, refreshWebcamButton).apply {
        id = "source-webcam-picker-box"
        alignment = Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }

    private val sourceField = TextField("test-qr.jpg").apply {
        id = "source-ref-field"
        promptText = InputSourceCategory.STATIC_IMAGE.promptText
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val browseButton = Button().apply {
        id = "source-browse-btn"
        textProperty().bind(I18n.bind("source.browse.button"))
        minWidth = Region.USE_PREF_SIZE
    }

    private val sourceInputRow = HBox(5.0, sourceField, browseButton).apply {
        id = "source-input-row"
        alignment = Pos.CENTER_LEFT
    }

    private val regionXField = TextField("0").apply { prefColumnCount = 3; promptText = "X" }
    private val regionYField = TextField("0").apply { prefColumnCount = 3; promptText = "Y" }
    private val regionWField = TextField("1280").apply { prefColumnCount = 4; promptText = "W" }
    private val regionHField = TextField("720").apply { prefColumnCount = 4; promptText = "H" }
    private val regionFpsField = TextField("24").apply { prefColumnCount = 2; promptText = "FPS" }
    private val applyRegionBtn = Button().apply {
        textProperty().bind(I18n.bind("source.region.apply"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
    }
    private val screenRegionBox = VBox(
        4.0,
        HBox(
            4.0,
            Label().apply {
                textProperty().bind(I18n.bind("source.region.label"))
                styleClass += "studio-hint-text"
                minWidth = Region.USE_PREF_SIZE
            },
            regionXField,
            regionYField,
            regionWField,
            regionHField,
            Label().apply {
                textProperty().bind(I18n.bind("source.region.fps"))
                styleClass += "studio-hint-text"
                minWidth = Region.USE_PREF_SIZE
            },
            regionFpsField,
        ).apply { alignment = Pos.CENTER_LEFT },
        applyRegionBtn,
    ).apply {
        id = "source-screen-region-box"
        isVisible = false
        isManaged = false
    }

    private val distance2dHint = Label().apply {
        id = "source-2d-distance-hint"
        textProperty().bind(I18n.bind("source.distance2d.hint"))
        maxWidth = Double.MAX_VALUE
        isWrapText = true
        styleClass += "source-badge-2d"
    }

    private val loopCheck = CheckBox().apply {
        id = "source-loop-check"
        textProperty().bind(I18n.bind("source.loop.check"))
        isSelected = true
    }

    private val applyButton = Button().apply {
        id = "source-apply-btn"
        textProperty().bind(I18n.bind("source.apply.button"))
        minWidth = Region.USE_PREF_SIZE
    }

    private val panel2d = VBox(
        6.0,
        categoryCombo,
        webcamPickerBox,
        sourceInputRow,
        screenRegionBox,
        distance2dHint,
        HBox(
            8.0,
            loopCheck,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            applyButton,
        ).apply { alignment = Pos.CENTER_LEFT },
    ).apply {
        id = "source-panel-2d"
        isVisible = false
        isManaged = false
    }

    // --- Header & Compact 1-Line Status + Expandable Telemetry ---
    private val openLensButton = Button().apply {
        id = "source-open-lens-btn"
        textProperty().bind(I18n.bind("source.open_lens.button"))
        styleClass += "studio-chip-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("source.open_lens.tooltip")) }
        isVisible = onOpenLensProfile != null
        isManaged = onOpenLensProfile != null
        setOnAction { onOpenLensProfile?.invoke() }
    }

    private val statusLabel = Label(I18n.t("source.status.waiting")).apply {
        id = "source-status-label"
        styleClass += "studio-control-status"
        isWrapText = false
        maxWidth = Double.MAX_VALUE
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val telemetryToggle = Button().apply {
        id = "source-telemetry-toggle"
        styleClass += listOf("studio-chip-btn", "source-link-btn")
        minWidth = Region.USE_PREF_SIZE
        setOnAction {
            telemetryExpanded = !telemetryExpanded
            updateTelemetryVisibility()
        }
    }

    private val telemetryDetailLabel = Label("").apply {
        id = "source-telemetry-detail"
        styleClass += "studio-hint-text"
        isWrapText = true
        isVisible = false
        isManaged = false
    }

    private val statusBar = HBox(6.0, statusLabel, telemetryToggle).apply {
        id = "source-status-bar"
        alignment = Pos.CENTER_LEFT
    }

    val root: VBox = VBox(
        6.0,
        HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("source.section.title"))
                styleClass += "studio-section-title"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            openLensButton,
        ).apply { alignment = Pos.CENTER_LEFT },
        modeSegmentBar,
        panel3d,
        panel2d,
        statusBar,
        telemetryDetailLabel,
    ).apply {
        styleClass += "studio-card"
    }

    init {
        mode3dToggle.setOnAction {
            if (!mode3dToggle.isSelected) {
                mode3dToggle.isSelected = true
                return@setOnAction
            }
            setActiveDimension(SourceSceneDimension.DIM_3D)
        }

        mode2dToggle.setOnAction {
            if (!mode2dToggle.isSelected) {
                mode2dToggle.isSelected = true
                return@setOnAction
            }
            setActiveDimension(SourceSceneDimension.DIM_2D)
        }

        categoryCombo.setOnAction {
            if (!syncing) {
                val cat = active2DCategory
                sourceField.promptText = cat.promptText
                if (cat == InputSourceCategory.WEBCAM) {
                    val selected = webcamCombo.value
                    if (selected != null) {
                        sourceField.text = selected.sourceRef
                    } else if (!sourceField.text.startsWith("webcam:", ignoreCase = true)) {
                        sourceField.text = ""
                    }
                    refreshWebcamDevices(force = !webcamScannedOnce)
                } else if (cat == InputSourceCategory.GAME_SCREEN &&
                    !sourceField.text.startsWith("screen://") &&
                    !sourceField.text.startsWith("tcp://")
                ) {
                    sourceField.text = "screen://primary"
                } else if (cat == InputSourceCategory.NETWORK_STREAM &&
                    !sourceField.text.contains("://")
                ) {
                    sourceField.text = "tcp://127.0.0.1:9555"
                } else if (cat == InputSourceCategory.STATIC_IMAGE &&
                    (sourceField.text.startsWith("scene://") || sourceField.text.startsWith("screen://") || sourceField.text.startsWith("webcam:"))
                ) {
                    sourceField.text = "test-qr.jpg"
                }
            }
            updateContextVisibility()
        }

        webcamCombo.setOnAction {
            if (!syncing) {
                webcamCombo.value?.let { device ->
                    sourceField.text = device.sourceRef
                }
            }
        }

        refreshWebcamButton.setOnAction {
            refreshWebcamDevices(force = true)
        }

        browseButton.setOnAction {
            val chooser = FileChooser()
            val cat = active2DCategory
            chooser.title = I18n.t(
                if (cat == InputSourceCategory.LOCAL_VIDEO) "source.browse.title.video" else "source.browse.title.image",
            )
            if (cat == InputSourceCategory.LOCAL_VIDEO) {
                chooser.extensionFilters.addAll(
                    FileChooser.ExtensionFilter(I18n.t("source.browse.filter.video"), "*.mp4", "*.mkv", "*.mov", "*.avi", "*.webm"),
                    FileChooser.ExtensionFilter(I18n.t("source.browse.filter.all"), "*.*"),
                )
            } else {
                chooser.extensionFilters.addAll(
                    FileChooser.ExtensionFilter(I18n.t("source.browse.filter.image"), "*.jpg", "*.jpeg", "*.png"),
                    FileChooser.ExtensionFilter(I18n.t("source.browse.filter.all"), "*.*"),
                )
            }
            val chosen = chooser.showOpenDialog(ownerWindow())
            if (chosen != null) {
                sourceField.text = chosen.toPath().toAbsolutePath().normalize().toString()
            }
        }

        applyRegionBtn.setOnAction {
            CmosSourceStatus.buildScreenRegionRef(
                regionXField.text,
                regionYField.text,
                regionWField.text,
                regionHField.text,
                regionFpsField.text,
            ).onSuccess { uri ->
                sourceField.text = uri
            }.onFailure(onError)
        }

        applyButton.setOnAction {
            val cat = active2DCategory
            val effectiveRef = if (cat == InputSourceCategory.WEBCAM) {
                val chosen = webcamCombo.value?.sourceRef
                if (chosen != null) {
                    sourceField.text = chosen
                    chosen
                } else {
                    onError(IllegalStateException(I18n.t("source.webcam.empty")))
                    return@setOnAction
                }
            } else {
                sourceField.text
            }
            CmosSourceStatus.resolveSourcePatch(cat, effectiveRef, loopCheck.isSelected)
                .onSuccess { patch ->
                    applySourcePatch(patch, I18n.t("source.status.switched_source", patch["sourceKind"], patch["sourceFile"]))
                }
                .onFailure(onError)
        }

        I18n.onLocaleChange { refreshLocalizedDynamicText() }

        setActiveDimension(SourceSceneDimension.DIM_3D)
        updateActivePresetHighlight("scene://qr-ball")
        updateContextVisibility()
        updateCustom3dVisibility()
        updateTelemetryVisibility()
    }

    fun refreshWebcamDevices(force: Boolean = true) {
        if (webcamScanning || (!force && webcamScannedOnce)) return
        webcamScanning = true
        refreshWebcamButton.isDisable = true
        webcamCombo.promptText = I18n.t("source.webcam.scanning")
        webcamPlaceholderLabel.text = I18n.t("source.webcam.scanning")

        fun applyDiscovered(devices: List<WebcamDevice>) {
            webcamScanning = false
            webcamScannedOnce = true
            refreshWebcamButton.isDisable = false
            webcamCombo.promptText = I18n.t(
                if (devices.isEmpty()) "source.webcam.empty" else "source.webcam.select.prompt",
            )
            webcamPlaceholderLabel.text = I18n.t("source.webcam.empty")

            val previousRef = webcamCombo.value?.sourceRef
                ?: sourceField.text.takeIf { it.startsWith("webcam:", ignoreCase = true) }
            syncing = true
            try {
                webcamCombo.items.setAll(devices)
                val matched = previousRef?.let { ref ->
                    devices.firstOrNull { it.sourceRef.equals(ref, ignoreCase = true) }
                } ?: devices.firstOrNull()
                webcamCombo.value = matched
                if (active2DCategory == InputSourceCategory.WEBCAM && matched != null) {
                    sourceField.text = matched.sourceRef
                }
            } finally {
                syncing = false
            }
        }

        if (!asyncWebcamDiscovery) {
            val found = runCatching { discoverWebcams() }.getOrDefault(emptyList())
            applyDiscovered(found)
            return
        }
        Thread.ofVirtual().name("webcam-discovery").start {
            val found = runCatching { discoverWebcams() }.getOrDefault(emptyList())
            Platform.runLater { applyDiscovered(found) }
        }
    }

    private fun refreshLocalizedDynamicText() {
        syncing = true
        try {
            val currentCat = active2DCategory
            categoryCombo.items.setAll(InputSourceCategory.TWO_D_CATEGORIES)
            categoryCombo.value = currentCat
            sourceField.promptText = currentCat.promptText
            webcamPlaceholderLabel.text = I18n.t(if (webcamScanning) "source.webcam.scanning" else "source.webcam.empty")
            webcamCombo.promptText = I18n.t(
                when {
                    webcamScanning -> "source.webcam.scanning"
                    webcamCombo.items.isEmpty() -> "source.webcam.empty"
                    else -> "source.webcam.select.prompt"
                },
            )
        } finally {
            syncing = false
        }
        val st = lastStatus
        if (st != null) {
            statusLabel.text = st.compactStatusText()
            telemetryDetailLabel.text = st.summaryText()
        } else {
            statusLabel.text = I18n.t("source.status.waiting")
        }
        updateCustom3dVisibility()
        updateTelemetryVisibility()
    }

    fun setActiveDimension(dimension: SourceSceneDimension) {
        activeDimension = dimension
        val is3d = dimension == SourceSceneDimension.DIM_3D
        mode3dToggle.isSelected = is3d
        mode2dToggle.isSelected = !is3d
        panel3d.isVisible = is3d
        panel3d.isManaged = is3d
        panel2d.isVisible = !is3d
        panel2d.isManaged = !is3d
        updateModeToggleStyles()
        updateContextVisibility()
    }

    private fun select3dPreset(chip: SourcePresetChip) {
        syncing = true
        try {
            setActiveDimension(SourceSceneDimension.DIM_3D)
            custom3dField.text = chip.sourceFile
            updateActivePresetHighlight(chip.sourceFile)
        } finally {
            syncing = false
        }
        applySourcePatch(
            linkedMapOf(
                "sourceKind" to chip.sourceKind,
                "sourceFile" to chip.sourceFile,
                "loop" to chip.loop,
            ),
            I18n.t("source.status.preset_3d", chip.label, chip.sourceFile),
        )
    }

    private fun setStyleClassPresent(node: javafx.scene.Node, className: String, present: Boolean) {
        if (present) {
            if (className !in node.styleClass) node.styleClass += className
        } else {
            node.styleClass.remove(className)
        }
    }

    private fun updateModeToggleStyles() {
        val is3d = activeDimension == SourceSceneDimension.DIM_3D
        setStyleClassPresent(mode3dToggle, "source-mode-btn", true)
        setStyleClassPresent(mode2dToggle, "source-mode-btn", true)
        setStyleClassPresent(mode3dToggle, "source-mode-3d-active", is3d)
        setStyleClassPresent(mode2dToggle, "source-mode-2d-active", !is3d)
    }

    private fun updateActivePresetHighlight(activeRef: String) {
        val norm = activeRef.trim().lowercase(Locale.ROOT)
        scene3dButtons.forEach { (ref, btn) ->
            val active = norm == ref || norm.startsWith("$ref?")
            setStyleClassPresent(btn, "source-card-3d-active", active)
        }
    }

    private fun updateContextVisibility() {
        val cat = active2DCategory
        val isWebcam = cat.supportsWebcamPicker
        webcamPickerBox.isVisible = isWebcam
        webcamPickerBox.isManaged = isWebcam
        sourceInputRow.isVisible = !isWebcam
        sourceInputRow.isManaged = !isWebcam
        browseButton.isVisible = cat.supportsFileChooser
        browseButton.isManaged = cat.supportsFileChooser
        screenRegionBox.isVisible = cat.supportsScreenRegion
        screenRegionBox.isManaged = cat.supportsScreenRegion
        loopCheck.isVisible = !isWebcam
        loopCheck.isManaged = !isWebcam
    }

    private fun updateCustom3dVisibility() {
        custom3dRow.isVisible = custom3dExpanded
        custom3dRow.isManaged = custom3dExpanded
        custom3dToggle.text = I18n.t(
            if (custom3dExpanded) "source.custom3d.toggle.expanded" else "source.custom3d.toggle.collapsed",
        )
    }

    private fun updateTelemetryVisibility() {
        val hasDetail = telemetryDetailLabel.text.isNotBlank()
        val show = telemetryExpanded && hasDetail
        telemetryDetailLabel.isVisible = show
        telemetryDetailLabel.isManaged = show
        telemetryToggle.text = I18n.t(
            if (show) "source.telemetry.toggle.expanded" else "source.telemetry.toggle.collapsed",
        )
    }

    private fun applySourcePatch(patch: Map<String, Any>, message: String) {
        val sender = patchCmos ?: return
        sender(patch).whenComplete { _, err ->
            if (err != null) onError(err) else onStatus(message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun observeSnapshot(snapshot: Map<String, Any?>) {
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        val cmosComp = components["cmos"] ?: return
        val status = CmosSourceStatus.fromMaps(
            config = cmosComp["config"] as? Map<String, Any?>,
            state = cmosComp["state"] as? Map<String, Any?>,
        )
        lastStatus = status
        statusLabel.text = status.compactStatusText()
        setStyleClassPresent(statusLabel, "source-status-error", !status.lastSourceError.isNullOrBlank())
        telemetryDetailLabel.text = status.summaryText()
        if (!status.lastSourceError.isNullOrBlank() && !telemetryExpanded) {
            telemetryExpanded = true
        }
        updateTelemetryVisibility()

        val activeRef = status.activeSourceRef.ifBlank { status.sourceFile }
        updateActivePresetHighlight(activeRef)

        val observedKey = "${status.sourceKind}|${status.sourceFile}|${status.loop}"
        val sourceChanged = !observedOnce || observedKey != lastObservedKey
        if (sourceChanged) {
            observedOnce = true
            lastObservedKey = observedKey
            syncing = true
            try {
                val inferred = status.inferredCategory
                val dim = status.dimension
                setActiveDimension(dim)
                if (dim == SourceSceneDimension.DIM_3D) {
                    if (!custom3dField.isFocused && status.sourceFile.isNotBlank()) {
                        custom3dField.text = status.sourceFile
                    }
                } else {
                    categoryCombo.value = inferred
                    sourceField.promptText = inferred.promptText
                    if (!sourceField.isFocused && status.sourceFile.isNotBlank()) {
                        sourceField.text = status.sourceFile
                    }
                    if (inferred == InputSourceCategory.WEBCAM && status.sourceFile.startsWith("webcam:", ignoreCase = true)) {
                        val existing = webcamCombo.items.firstOrNull { it.sourceRef.equals(status.sourceFile, ignoreCase = true) }
                        if (existing != null) {
                            webcamCombo.value = existing
                        } else {
                            val devId = status.sourceFile.substringAfter(':').trim()
                            if (devId.isNotEmpty() && !devId.equals("default", ignoreCase = true)) {
                                val synthetic = WebcamDevice(devId, devId)
                                webcamCombo.items.add(synthetic)
                                webcamCombo.value = synthetic
                            }
                        }
                    }
                    loopCheck.isSelected = status.loop
                    updateContextVisibility()
                }
            } finally {
                syncing = false
            }
        }
    }
}
