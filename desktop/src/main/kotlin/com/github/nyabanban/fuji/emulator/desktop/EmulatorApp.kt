package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.input.InputBindingRuntime
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioIoPanel
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.CoalescingButtonDispatcher
import com.github.nyabanban.fuji.emulator.desktop.panels.DiagnosticsRefreshController
import com.github.nyabanban.fuji.emulator.desktop.panels.HostAudioRuntimeAdapter
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfilePanel
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileWindow
import com.github.nyabanban.fuji.emulator.desktop.panels.OpticalFilterPane
import com.github.nyabanban.fuji.emulator.desktop.panels.OrientationIbisPane
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelControl
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioTypography
import com.github.nyabanban.fuji.emulator.desktop.theme.BodyTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfile
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import javafx.animation.AnimationTimer
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.stage.Window
import javafx.util.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class EmulatorApp : Application() {
    private val runtime get() = LaunchContext.runtime
    private lateinit var studio: CameraStudio
    private lateinit var audioAdapter: HostAudioRuntimeAdapter
    private lateinit var audioIoPanel: AudioIoPanel
    private lateinit var lensProfileTabPanel: LensProfilePanel
    private lateinit var opticalFilterTabPanel: OpticalFilterPane
    private lateinit var orientationIbisPane: OrientationIbisPane
    private lateinit var lensProfileWindow: LensProfileWindow
    private lateinit var root: BorderPane
    private lateinit var side: TabPane
    private lateinit var timeline: Timeline
    private var displayTimer: AnimationTimer? = null
    private lateinit var entryStage: Stage
    private var mainCloseRequested=false
    private val busy = AtomicBoolean()
    private val paused get() = LaunchContext.paused
    private val stopRequested get() = LaunchContext.stopRequested
    @Volatile private var runnerThread: Thread? = null
    private var interactiveStarted = false
    private val status = Label(I18n.t("app.status.waiting_firmware"))
    private var lastProblem: String? = null
    private val controlsToggle = ToggleButton("☰").apply {
        accessibleText = I18n.t("app.controls.toggle")
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.controls.toggle")) }
    }
    private val direction = Label(I18n.t("app.direction.default")).apply { styleClass += "studio-view-name" }
    private var appLocaleListener: javafx.beans.value.ChangeListener<java.util.Locale>? = null
    private val configPane = VBox(8.0)
    private lateinit var configurationScroll: ScrollPane
    private val detailsText = TextArea().apply { isEditable = false; isWrapText = true }
    private val stateText = TextArea().apply { id = "diagnostics-state-text"; isEditable = false }
    private val diagnosticsController = DiagnosticsRefreshController()
    private val uiBackgroundExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "fuji-ui-bg").apply { isDaemon = true }
    }
    private lateinit var lensTab: Tab
    private lateinit var configTab: Tab
    private lateinit var audioTab: Tab
    private lateinit var diagnosticsTab: Tab
    private data class RefreshPayload(
        val snapshot: Map<String, Any?>,
        val formattedDiagnostics: String?,
        val audioStatus: Map<String, Any?>?,
    )
    private val editors = linkedMapOf<String, TextField>()
    private val observedValues = hashMapOf<String, String>()
    private var lastObservedConfigRevision = -1L
    private var lastVglibRefreshMainSeq = -1L
    private var lastVglibRefreshEvfSeq = -1L
    private var lastVglibPollNanos = 0L
    private lateinit var uiStateComponentIds: Set<String>
    private lateinit var uiStateComponentIdSets: Array<Set<String>>
    private var themeListenerHandle: AutoCloseable? = null
    private var inputBindings: InputBindingRuntime? = null
    private val firmwareDialogShowing = AtomicBoolean(false)
    private var triggerBootFromUi: (() -> Unit)? = null
    private val log = org.slf4j.LoggerFactory.getLogger(EmulatorApp::class.java)
    private fun error(error: Throwable) {
        val firmwareIssue = if (LaunchContext.isRuntimeInitialized) {
            FirmwareGuidance.diagnose(error, runtime.home, runtime.config)
        } else {
            FirmwareGuidance.extractIssue(error)
        }
        if (firmwareIssue != null) {
            if (com.github.nyabanban.fuji.emulator.runtime.LogArchiver.markLoggedOnce(error)) {
                log.warn("Firmware setup required:\n{}", firmwareIssue.cliReport)
                com.github.nyabanban.fuji.emulator.runtime.LogArchiver.flushAsyncLoggers()
            }
            Platform.runLater {
                val shortMsg = I18n.t("firmware.guide.status.missing_short", firmwareIssue.primaryRelativeLabel)
                lastProblem = shortMsg
                status.text = shortMsg
                if (LaunchContext.smokePath == null && !stopRequested.get() && LaunchContext.isRuntimeInitialized && firmwareDialogShowing.compareAndSet(false, true)) {
                    try {
                        val ownerStage = if (::entryStage.isInitialized && entryStage.isShowing) entryStage else null
                        val importedPath = FirmwareGuidanceDialog.showModal(
                            owner = ownerStage,
                            issue = firmwareIssue,
                            uiTheme = runtime.themeController.uiTheme,
                            onLanguageSelected = { code -> runtime.themeController.updateLanguage(code) },
                            onImportDat = { selectedDat ->
                                FirmwareGuidance.importDatFileForGui(
                                    selectedDatPath = selectedDat,
                                    home = runtime.home,
                                    config = runtime.config,
                                    liveRuntime = runtime,
                                )
                            },
                        )
                        if (importedPath != null) {
                            lastProblem = null
                            status.text = I18n.t("firmware.guide.status.imported", importedPath.fileName.toString())
                            Platform.runLater { triggerBootFromUi?.invoke() }
                        }
                    } finally {
                        firmwareDialogShowing.set(false)
                    }
                }
            }
            return
        }
        val summary = error.cause?.message ?: error.message ?: error.javaClass.simpleName
        if (com.github.nyabanban.fuji.emulator.runtime.LogArchiver.markLoggedOnce(error)) {
            log.error("EmulatorApp runtime/UI error: {}", summary, error)
            com.github.nyabanban.fuji.emulator.runtime.LogArchiver.flushAsyncLoggers()
        }
        Platform.runLater {
            lastProblem = summary
            status.text = I18n.t("app.status.error", summary)
        }
    }
    private val buttonDispatcher by lazy {
        CoalescingButtonDispatcher(
            submitOwner = { action -> runtime.owner.submit(action) },
            applyBatchOnOwner = { batch, persistToDisk ->
                runtime.telemetry.recordButtonEvent(batch.entries.joinToString(",") { "${it.key}=${it.value}" })
                val state = patchStudioButtons(batch, persistToDisk = false)
                if (persistToDisk) {
                    // Persist only latching body switches (power, movieMode) to buttons.yaml so
                    // transiently held momentary keys (e.g. back/shutterHalf/menu) are never frozen
                    // onto disk and replayed as stuck-down buttons across Sub-MCU cold resets.
                    val live = runtime.host.components().singleOrNull { it.descriptor.id == "buttons" }
                    if (live != null) {
                        val persisted = LinkedHashMap<String, Any>()
                        for ((k, v) in live.config) {
                            persisted[k] = if (k in PanelCatalog.latchingButtonKeys) v else false
                        }
                        Formats.atomicYaml(live.configFile, persisted)
                    }
                }
                state
            },
            latchingKeys = PanelCatalog.latchingButtonKeys,
            onError = ::error,
        )
    }
    private fun patchStudioButtons(changes: Map<String, Any>, persistToDisk: Boolean): Map<String, Any?> {
        val movie = changes["movieMode"] as? Boolean
        val previous = if (movie != null) runtime.host.ports.find("buttons", ButtonPort::class.java)?.pressed("movieMode") else null
        val result = runtime.host.patchLatestDirect("buttons", changes, persistToDisk)
        if (movie != null && previous != null && previous != movie) runtime.session.studioModeSwitchChanged(movie)
        return result
    }
    private fun patchLatest(id: String, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> = runtime.owner.submit {
        if (id == "buttons") {
            runtime.telemetry.recordButtonEvent(changes.entries.joinToString(",") { "${it.key}=${it.value}" })
            patchStudioButtons(changes, persistToDisk = true)
        } else runtime.host.patchLatestDirect(id, changes, persistToDisk = true)
    }.whenComplete { _, problem -> if (problem != null) error(problem) }
    private fun showControls(on: Boolean) { if(on && ::entryStage.isInitialized) { entryStage.show(); controlsToggle.isSelected=true } }
    private fun configure(id: String) {
        showControls(true); side.selectionModel.select(configTab)
        configPane.children.filterIsInstance<TitledPane>().forEach { it.isExpanded = it.userData == id }
        root.applyCss();root.layout()
        configPane.children.filterIsInstance<TitledPane>().firstOrNull { it.userData==id }?.let { target ->
            val range=configPane.layoutBounds.height-configurationScroll.viewportBounds.height
            configurationScroll.vvalue=if(range>0) (target.layoutY/range).coerceIn(0.0,1.0) else 0.0
            root.layout()
        }
    }
    private fun inspect(control: PanelControl) {
        val localized = if (I18n.localeCode() == I18n.CODE_ZH_CN) control.labelZh else control.label
        detailsText.text = I18n.t(
            "app.inspect.template",
            localized,
            control.label,
            control.bindingStatus,
            control.firmwareBinding,
            control.basis.joinToString("\n"),
        )
    }
    override fun start(stage: Stage) {
        Thread.currentThread().setUncaughtExceptionHandler { thread, uncaught ->
            if (com.github.nyabanban.fuji.emulator.runtime.LogArchiver.markLoggedOnce(uncaught)) {
                log.error("Uncaught exception on JavaFX thread {}", thread.name, uncaught)
                com.github.nyabanban.fuji.emulator.runtime.LogArchiver.flushAsyncLoggers()
            }
            error(uncaught)
        }
        LaunchContext.missingFirmwareStartup?.let { missingStartup ->
            val profilePath = missingStartup.userProfilePath
                ?: com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore.defaultPath(missingStartup.home)
            val initialProfile = com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore.loadOrCreate(profilePath, autoCreate = false)
            I18n.setLocale(initialProfile.language)
            val initialUiTheme = UiThemeRegistry.resolveOrFallback(initialProfile.uiTheme)
            val imported = FirmwareGuidanceDialog.showModal(
                owner = null,
                issue = missingStartup.issue,
                uiTheme = initialUiTheme,
                onLanguageSelected = { code ->
                    runCatching {
                        val current = com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore.loadOrCreate(profilePath, autoCreate = false)
                        com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore.save(profilePath, current.copy(language = code))
                    }
                },
                onImportDat = { selectedDat ->
                    FirmwareGuidance.importDatFileForGui(
                        selectedDatPath = selectedDat,
                        home = missingStartup.home,
                        config = missingStartup.config,
                        liveRuntime = null,
                    ).also {
                        missingStartup.createAndStartRuntime()
                    }
                },
            )
            if (imported == null || missingStartup.completedRuntime == null) {
                mainCloseRequested = true
                Platform.exit()
                return
            }
            LaunchContext.missingFirmwareStartup = null
        }
        entryStage=stage
        root = BorderPane().apply { styleClass += "studio-shell"; style = "-fx-font-family: '" + StudioTypography.family + "';" }
        stage.minWidth = 340.0; stage.minHeight = 270.0
        // An oversized first show can be maximized by the native window manager before arrangement.
        val themeController = runtime.themeController
        I18n.setLocale(themeController.profile.language)
        status.text = I18n.t("app.status.waiting_firmware")
        stage.scene = Scene(root, stage.minWidth, stage.minHeight)
        UiThemeRegistry.applyToScene(stage.scene, themeController.uiTheme)
        stage.title = I18n.t("app.title.main", themeController.bodyTheme.modelLabel)
        var bootStateKey = if (LaunchContext.snapshot == null) "app.boot.load" else "app.boot.resume_snapshot"
        val boot = Button(I18n.t(bootStateKey)).apply {
            styleClass += "studio-header-btn"
            minWidth = Region.USE_PREF_SIZE
            setOnAction {
                if (runtime.catalog.mode == RelaxedPeripheralFallback.MODE) {
                    if (!interactiveStarted) {
                        interactiveStarted = true
                        paused.set(false)
                        bootStateKey = "app.boot.pause"
                        text = I18n.t(bootStateKey)
                        runnerThread = Thread.ofVirtual()
                            .name("firmware-interactive")
                            .uncaughtExceptionHandler { thread, uncaught ->
                                if (com.github.nyabanban.fuji.emulator.runtime.LogArchiver.markLoggedOnce(uncaught)) {
                                    log.error("Uncaught exception on {}", thread.name, uncaught)
                                    com.github.nyabanban.fuji.emulator.runtime.LogArchiver.flushAsyncLoggers()
                                }
                                error(uncaught)
                            }
                            .start {
                                var failedWithGuidance = false
                                runCatching { runtime.runInteractive(LaunchContext.snapshot, paused, stopRequested) }
                                    .onFailure { err ->
                                        failedWithGuidance = FirmwareGuidance.diagnose(err, runtime.home, runtime.config) != null
                                        error(err)
                                    }
                                Platform.runLater {
                                    if (!stopRequested.get()) {
                                        if (failedWithGuidance) {
                                            interactiveStarted = false
                                            paused.set(false)
                                            isDisable = false
                                            bootStateKey = if (LaunchContext.snapshot == null) "app.boot.load" else "app.boot.resume_snapshot"
                                            text = I18n.t(bootStateKey)
                                        } else {
                                            isDisable = true
                                            bootStateKey = "app.boot.stopped"
                                            text = I18n.t(bootStateKey)
                                        }
                                    }
                                }
                            }
                    } else {
                        val nowPaused = !paused.get()
                        paused.set(nowPaused)
                        bootStateKey = if (nowPaused) "app.boot.resume" else "app.boot.pause"
                        text = I18n.t(bootStateKey)
                    }
                } else {
                    isDisable = true
                    runnerThread = Thread.ofVirtual()
                        .name("firmware-probe")
                        .uncaughtExceptionHandler { thread, uncaught ->
                            if (com.github.nyabanban.fuji.emulator.runtime.LogArchiver.markLoggedOnce(uncaught)) {
                                log.error("Uncaught exception on {}", thread.name, uncaught)
                                com.github.nyabanban.fuji.emulator.runtime.LogArchiver.flushAsyncLoggers()
                            }
                            error(uncaught)
                        }
                        .start {
                            runCatching { runtime.probe(LaunchContext.snapshot) }.onFailure { err ->
                                if (FirmwareGuidance.diagnose(err, runtime.home, runtime.config) != null) {
                                    Platform.runLater { isDisable = false }
                                }
                                error(err)
                            }
                        }
                }
            }
        }
        triggerBootFromUi = {
            if (!interactiveStarted && !boot.isDisable) {
                boot.fire()
            }
        }
        LaunchContext.bootTrigger = {
            if (interactiveStarted) {
                if (paused.get()) {
                    paused.set(false)
                    runtime.telemetry.paused = false
                    Platform.runLater {
                        if (!boot.isDisable) {
                            bootStateKey = "app.boot.pause"
                            boot.text = I18n.t(bootStateKey)
                        }
                    }
                    "Firmware resumed."
                } else {
                    "Firmware is already running."
                }
            } else {
                Platform.runLater { boot.fire() }
                "Firmware boot triggered."
            }
        }
        LaunchContext.onPauseChanged = { isPaused ->
            Platform.runLater {
                if (interactiveStarted && !boot.isDisable) {
                    bootStateKey = if (isPaused) "app.boot.resume" else "app.boot.pause"
                    boot.text = I18n.t(bootStateKey)
                }
            }
        }
        val lensWindowBtn = Button().apply {
            id = "header-lens-profile-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("app.header.lens_profile"))
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.header.lens_profile.tooltip")) }
            setOnAction { if (::lensProfileWindow.isInitialized) lensProfileWindow.toggle() }
        }
        val inputBindingsBtn = Button().apply {
            id = "header-input-bindings-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("app.header.input_bindings"))
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.header.input_bindings.tooltip")) }
            setOnAction { inputBindings?.showDialog() }
        }
        val dump = Button().apply {
            id = "header-dump-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("app.header.dump"))
            setOnAction {
                Thread.ofVirtual().name("studio-dump").start {
                    runCatching { runtime.dump() }
                        .onSuccess { file -> Platform.runLater { status.text = I18n.t("app.status.dump_saved", file.fileName) } }
                        .onFailure(::error)
                }
            }
        }
        fun confirmFlashAction(titleKey: String, bodyText: String): Boolean {
            val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
                initOwner(stage)
                title = I18n.t(titleKey)
                headerText = null
                contentText = bodyText
            }
            return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
        }
        val flashWriteDatItem = MenuItem().apply {
            textProperty().bind(I18n.bind("app.flash.menu.write_dat"))
            setOnAction {
                val chooser = FileChooser().apply {
                    title = I18n.t("app.flash.dialog.write_dat.title")
                    extensionFilters.addAll(
                        FileChooser.ExtensionFilter("Fujifilm Firmware (*.DAT)", "*.DAT", "*.dat"),
                        FileChooser.ExtensionFilter("All Files (*.*)", "*.*"),
                    )
                }
                val file = chooser.showOpenDialog(stage) ?: return@setOnAction
                if (!confirmFlashAction("app.flash.dialog.write_dat.title", I18n.t("app.flash.confirm.write_dat", file.name))) {
                    return@setOnAction
                }
                status.text = I18n.t("app.flash.status.working")
                Thread.ofVirtual().name("studio-flash-dat").start {
                    runCatching { runtime.flashDatIntoNorLive(file.toPath(), preserveCalibration = true) }
                        .onSuccess { res ->
                            val sha = res.norSha256.take(12)
                            Platform.runLater { status.text = I18n.t("app.flash.status.write_dat_ok", file.name, sha) }
                        }
                        .onFailure(::error)
                }
            }
        }
        val flashExportItem = MenuItem().apply {
            textProperty().bind(I18n.bind("app.flash.menu.export_nor"))
            setOnAction {
                val chooser = FileChooser().apply {
                    title = I18n.t("app.flash.dialog.export_nor.title")
                    initialFileName = "nor-flash-export.bin"
                    extensionFilters.addAll(
                        FileChooser.ExtensionFilter("64 MiB NOR Flash Image (*.bin)", "*.bin"),
                        FileChooser.ExtensionFilter("All Files (*.*)", "*.*"),
                    )
                }
                val file = chooser.showSaveDialog(stage) ?: return@setOnAction
                status.text = I18n.t("app.flash.status.working")
                Thread.ofVirtual().name("studio-flash-export").start {
                    runCatching { runtime.exportNorFlashLive(file.toPath()) }
                        .onSuccess { res ->
                            val sha = res.sha256.take(12)
                            Platform.runLater { status.text = I18n.t("app.flash.status.export_ok", file.name, sha) }
                        }
                        .onFailure(::error)
                }
            }
        }
        val flashImportItem = MenuItem().apply {
            textProperty().bind(I18n.bind("app.flash.menu.import_nor"))
            setOnAction {
                val chooser = FileChooser().apply {
                    title = I18n.t("app.flash.dialog.import_nor.title")
                    extensionFilters.addAll(
                        FileChooser.ExtensionFilter("64 MiB NOR Flash Image (*.bin)", "*.bin"),
                        FileChooser.ExtensionFilter("All Files (*.*)", "*.*"),
                    )
                }
                val file = chooser.showOpenDialog(stage) ?: return@setOnAction
                if (!confirmFlashAction("app.flash.dialog.import_nor.title", I18n.t("app.flash.confirm.import_nor", file.name))) {
                    return@setOnAction
                }
                status.text = I18n.t("app.flash.status.working")
                Thread.ofVirtual().name("studio-flash-import").start {
                    runCatching { runtime.importNorFlashLive(file.toPath()) }
                        .onSuccess { res ->
                            val sha = res.sha256.take(12)
                            Platform.runLater { status.text = I18n.t("app.flash.status.import_ok", file.name, sha) }
                        }
                        .onFailure(::error)
                }
            }
        }
        val flashRestoreFactoryItem = MenuItem().apply {
            textProperty().bind(I18n.bind("app.flash.menu.restore_factory"))
            setOnAction {
                if (!confirmFlashAction("app.flash.dialog.restore_factory.title", I18n.t("app.flash.confirm.restore_factory"))) {
                    return@setOnAction
                }
                status.text = I18n.t("app.flash.status.working")
                Thread.ofVirtual().name("studio-flash-restore").start {
                    runCatching { runtime.restoreFactoryFlashLive() }
                        .onSuccess { res ->
                            val sha = res.sha256.take(12)
                            Platform.runLater { status.text = I18n.t("app.flash.status.restore_ok", sha) }
                        }
                        .onFailure(::error)
                }
            }
        }
        val flashMenu = MenuButton().apply {
            id = "header-flash-menu-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("app.header.flash"))
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.header.flash.tooltip")) }
            items.addAll(
                flashWriteDatItem,
                flashExportItem,
                flashImportItem,
                SeparatorMenuItem(),
                flashRestoreFactoryItem,
            )
        }
        controlsToggle.setOnAction { showControls(controlsToggle.isSelected) }
        if (boot.id.isNullOrBlank()) boot.id = "header-boot-btn"
        val brandMarkLabel = Label(themeController.bodyTheme.brandLabel).apply { styleClass += "studio-brand-mark"; minWidth = Region.USE_PREF_SIZE }
        val brandModelLabel = Label(themeController.bodyTheme.modelLabel).apply { styleClass += "studio-brand-model"; minWidth = 0.0; textOverrun = OverrunStyle.ELLIPSIS }
        val consoleHeader = HBox(6.0,
            brandMarkLabel,
            Region().apply { styleClass += "studio-brand-sep" },
            brandModelLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            boot,
        ).apply {
            minWidth = 0.0
            alignment = javafx.geometry.Pos.CENTER_LEFT
            styleClass += "studio-console-header"
        }
        val headerActionsRow = javafx.scene.layout.FlowPane(5.0, 4.0, lensWindowBtn, inputBindingsBtn, flashMenu, dump).apply {
            id = "header-actions-row"
            minWidth = 0.0
            prefWrapLength = 340.0
            padding = Insets(4.0, 10.0, 5.0, 12.0)
            alignment = javafx.geometry.Pos.CENTER_LEFT
            styleClass += "studio-console-header"
        }
        var syncingThemeSelectors = false
        val bodyThemeTooltip = Tooltip()
        val uiThemeTooltip = Tooltip()
        fun refreshThemeTooltips() {
            val activeBody = themeController.bodyTheme
            val activeUi = themeController.uiTheme
            bodyThemeTooltip.text = I18n.t(
                "app.header.body_tooltip_detail",
                I18n.t("app.header.body_tooltip"),
                activeBody.localizedName(),
                activeBody.localizedDescription(),
            )
            uiThemeTooltip.text = I18n.t(
                "app.header.ui_tooltip_detail",
                I18n.t("app.header.ui_tooltip"),
                activeUi.localizedName(),
                activeUi.localizedDescription(),
            )
        }
        val bodyThemeCombo = ComboBox<String>().apply {
            id = "theme-body-combo"
            styleClass += "studio-header-combo"
            minWidth = 0.0
            prefWidth = 70.0
            maxWidth = Double.MAX_VALUE
            tooltip = bodyThemeTooltip
            converter = object : javafx.util.StringConverter<String>() {
                override fun toString(themeId: String?): String {
                    if (themeId == null) return ""
                    return themeController.availableBodyThemes().firstOrNull { it.id == themeId }?.localizedName() ?: themeId
                }
                override fun fromString(string: String?): String = string.orEmpty()
            }
            items.setAll(themeController.availableBodyThemes().map { it.id })
            value = themeController.bodyTheme.id
            setOnAction {
                if (!syncingThemeSelectors && value != null) {
                    runCatching { themeController.applyTheme(bodyId = value) }.onFailure(::error)
                }
            }
        }
        val uiThemeCombo = ComboBox<String>().apply {
            id = "theme-ui-combo"
            styleClass += "studio-header-combo"
            minWidth = 0.0
            prefWidth = 70.0
            maxWidth = Double.MAX_VALUE
            tooltip = uiThemeTooltip
            converter = object : javafx.util.StringConverter<String>() {
                override fun toString(themeId: String?): String {
                    if (themeId == null) return ""
                    return themeController.availableUiThemes().firstOrNull { it.id == themeId }?.localizedName() ?: themeId
                }
                override fun fromString(string: String?): String = string.orEmpty()
            }
            items.setAll(themeController.availableUiThemes().map { it.id })
            value = themeController.uiTheme.id
            setOnAction {
                if (!syncingThemeSelectors && value != null) {
                    runCatching { themeController.applyTheme(uiId = value) }.onFailure(::error)
                }
            }
        }
        val themeReloadBtn = Button("↻").apply {
            id = "theme-reload-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.header.theme_reload.tooltip")) }
            setOnAction {
                runCatching {
                    val reloaded = themeController.reloadThemes()
                    status.text = I18n.t("app.status.themes_reloaded", reloaded.bodyTheme.id, reloaded.uiTheme.id)
                }.onFailure(::error)
            }
        }
        fun refreshThemeSelectorsDisplay() {
            syncingThemeSelectors = true
            val bodyVal = bodyThemeCombo.value
            bodyThemeCombo.items.setAll(themeController.availableBodyThemes().map { it.id })
            bodyThemeCombo.value = bodyVal ?: themeController.bodyTheme.id
            val uiVal = uiThemeCombo.value
            uiThemeCombo.items.setAll(themeController.availableUiThemes().map { it.id })
            uiThemeCombo.value = uiVal ?: themeController.uiTheme.id
            syncingThemeSelectors = false
            refreshThemeTooltips()
        }
        refreshThemeTooltips()
        var syncingLangToggle = false
        val langCombo = ComboBox<String>().apply {
            id = "header-lang-combo"
            styleClass += "studio-header-combo"
            minWidth = 64.0
            prefWidth = 88.0
            maxWidth = 96.0
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.header.lang_tooltip")) }
            items.setAll(I18n.supportedLanguageCodes())
            converter = object : javafx.util.StringConverter<String>() {
                override fun toString(code: String?): String =
                    if (code.isNullOrBlank()) "" else I18n.nativeDisplayName(code)
                override fun fromString(text: String?): String =
                    I18n.localeCode(I18n.normalizeLocale(text))
            }
            setOnAction {
                if (!syncingLangToggle && value != null) {
                    val selectedCode = value
                    themeController.updateLanguage(selectedCode)
                    status.text = I18n.t("app.status.lang_switched", I18n.nativeDisplayName(selectedCode))
                }
            }
        }
        fun syncLanguageSelector() {
            syncingLangToggle = true
            val code = I18n.localeCode()
            if (langCombo.value != code) {
                langCombo.value = code
            }
            syncingLangToggle = false
        }
        syncLanguageSelector()
        val themeHeaderStrip = HBox(5.0,
            Label().apply { styleClass += "studio-table-head"; minWidth = Region.USE_PREF_SIZE; textProperty().bind(I18n.bind("app.header.body_short")) },
            bodyThemeCombo,
            Label().apply { styleClass += "studio-table-head"; minWidth = Region.USE_PREF_SIZE; textProperty().bind(I18n.bind("app.header.ui_short")) },
            uiThemeCombo,
            themeReloadBtn,
            langCombo,
        ).apply {
            minWidth = 0.0
            alignment = javafx.geometry.Pos.CENTER_LEFT
            padding = Insets(4.0, 10.0, 5.0, 12.0)
            styleClass += "studio-console-header"
            HBox.setHgrow(bodyThemeCombo, Priority.ALWAYS)
            HBox.setHgrow(uiThemeCombo, Priority.ALWAYS)
        }
        val topHeaderBox = VBox(consoleHeader, headerActionsRow, themeHeaderStrip).apply { minWidth = 0.0 }
        val updateStatusText: (String) -> Unit = { msg ->
            Platform.runLater {
                lastProblem = null
                status.text = msg
            }
        }
        audioAdapter = HostAudioRuntimeAdapter(runtime)
        audioIoPanel = AudioIoPanel(
            fetchStatus = audioAdapter::fetchStatus,
            fetchDevices = audioAdapter::fetchDevices,
            applyPatchAsync = audioAdapter::applyPatchAsync,
            onStatus = updateStatusText,
            onError = ::error,
        )
        val persistGraphicsChoice: (String?, String?) -> Unit = { backend, device ->
            if (LaunchContext.smokePath == null) {
                themeController.updateGraphicsPreferences(opticsBackend = backend, vulkanDevice = device)
            }
        }
        lensProfileTabPanel = LensProfilePanel(::patchLatest, onStatus = updateStatusText, onError = ::error, onGraphicsChanged = persistGraphicsChoice)
        opticalFilterTabPanel = OpticalFilterPane(
            mode = OpticalFilterPane.Mode.DETAILED,
            patchOpticalFilter = { changes -> patchLatest("optical-filter", changes) },
            onStatus = updateStatusText,
            onError = ::error,
        )
        orientationIbisPane = OrientationIbisPane(::patchLatest, onStatus = updateStatusText, onError = ::error)
        lensProfileWindow = LensProfileWindow(stage, ::patchLatest, onStatus = updateStatusText, onError = ::error, onGraphicsChanged = persistGraphicsChoice).also {
            it.applyUiTheme(themeController.uiTheme)
        }
        val savedProfile = themeController.profile
        if (
            LaunchContext.smokePath == null &&
            (savedProfile.opticsBackend != UserProfile.DEFAULT_OPTICS_BACKEND ||
                savedProfile.vulkanDevice != UserProfile.DEFAULT_VULKAN_DEVICE)
        ) {
            runtime.owner.submit {
                if (runtime.host.components().any { it.descriptor.id == "lens" }) {
                    runtime.host.patchLatestDirect(
                        "lens",
                        mapOf(
                            "opticsBackend" to savedProfile.opticsBackend,
                            "vulkanDevice" to savedProfile.vulkanDevice,
                        ),
                        persistToDisk = true,
                    )
                }
            }.whenComplete { _, err -> if (err != null) error(err) }
        }
        audioTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.audio"))
            content = ScrollPane(audioIoPanel.root).apply { isFitToWidth = true }
            isClosable = false
        }
        val autoRefreshDiagnostics = CheckBox().apply {
            id = "diagnostics-auto-refresh"
            textProperty().bind(I18n.bind("app.diag.auto_refresh"))
            isSelected = diagnosticsController.autoRefreshEnabled
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.diag.auto_refresh.tooltip")) }
            selectedProperty().addListener { _, _, enabled ->
                diagnosticsController.autoRefreshEnabled = enabled
                if (enabled) refresh()
            }
        }
        val refreshDiagnosticsNow = Button().apply {
            id = "diagnostics-refresh-now"
            styleClass += "studio-header-btn-secondary"
            textProperty().bind(I18n.bind("app.diag.refresh_now"))
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.diag.refresh_now.tooltip")) }
            setOnAction {
                diagnosticsController.requestManualRefresh()
                refresh()
            }
        }
        val diagnosticsToolbar = HBox(
            8.0,
            autoRefreshDiagnostics,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            refreshDiagnosticsNow,
        ).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 8.0)
        }
        val diagnosticsPane = BorderPane(stateText, diagnosticsToolbar, null, null, null)
        diagnosticsTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.diagnostics"))
            content = diagnosticsPane
            isClosable = false
        }
        configTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.components"))
            content = ScrollPane(configPane).apply {
                isFitToWidth = true
                configurationScroll = this
            }
            isClosable = false
        }
        val lensScroll = ScrollPane(
            VBox(8.0, lensProfileTabPanel.root, opticalFilterTabPanel.root, orientationIbisPane.root).apply {
                padding = Insets(0.0, 0.0, 8.0, 0.0)
            },
        ).apply { isFitToWidth = true }
        lensTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.lens"))
            content = lensScroll
            isClosable = false
        }
        val evidenceTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.evidence"))
            content = detailsText
            isClosable = false
        }
        side = TabPane().apply {
            minWidth = 0.0; prefWidth = 370.0; maxWidth = Double.MAX_VALUE; styleClass += "studio-sidebar"
            tabs.addAll(
                lensTab,
                audioTab,
                configTab,
                evidenceTab,
                diagnosticsTab)
            selectionModel.selectedItemProperty().addListener { _, _, selected ->
                if (selected === lensTab || (selected === diagnosticsTab && !diagnosticsController.hasRenderedDiagnostics)) {
                    refresh()
                }
            }
        }
        studio = CameraStudio(
            stage,
            { key, down -> buttonDispatcher.submit(key, down) },
            ::configure,
            ::inspect,
            ::error,
            sendSetting = { componentId, settingKey, value -> patchLatest(componentId, mapOf(settingKey to value)) },
            sendSettingValue = { componentId, settingKey, value -> patchLatest(componentId, mapOf(settingKey to value)) },
            momentaryHoldPolicy = if (LaunchContext.smokePath == null && runtime.catalog.mode == RelaxedPeripheralFallback.MODE) {
                MomentaryHoldPolicy.firmwareCalibrated()
            } else {
                MomentaryHoldPolicy.NONE
            },
            virtualTick = { runtime.telemetry.virtualTick },
            isSessionAdvancing = { runtime.telemetry.running && !runtime.telemetry.paused && !runtime.session.isSocPoweredOff },
            sendTouch = { x, y, pressed ->
                runtime.owner.submit {
                    val touchTarget = if (
                        runtime.host.components().any {
                            it.descriptor.id == "rear-touch-ic" && (it.config["enabled"] as? Boolean == true)
                        }
                    ) {
                        "rear-touch-ic"
                    } else {
                        "i2c-bus"
                    }
                    runtime.host.patchLatestDirect(
                        touchTarget,
                        mapOf(
                            "touchEnabled" to true,
                            "touchPressed" to pressed,
                            "touchX" to x.toLong(),
                            "touchY" to y.toLong(),
                        ),
                        persistToDisk = false,
                    )
                }.whenComplete { _, problem -> if (problem != null) error(problem) }
            },
            sendViewMode = { pressed -> runtime.setViewModeButtonAsync(pressed) },
            patchCmos = { changes ->
                patchLatest("cmos", changes).also { future ->
                    val ref = (changes["sourceFile"] as? String)?.trim().orEmpty()
                    if (ref.startsWith("scene://milk-frog", ignoreCase = true) && !ref.contains("audio=off", ignoreCase = true)) {
                        future.whenComplete { _, err ->
                            if (err == null) {
                                runCatching {
                                    val st = audioAdapter.fetchStatus()
                                    val curIn = st["inputMode"]?.toString()?.uppercase(java.util.Locale.ROOT) ?: "DISABLED"
                                    if (curIn == "DISABLED") {
                                        val patch = linkedMapOf<String, Any>("inputMode" to "SCENE_AUDIO")
                                        if (st["enabled"] == true) {
                                            patch["inputMonitor"] = true
                                        }
                                        audioAdapter.applyPatchAsync(patch)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            patchOpticalFilter = { changes -> patchLatest("optical-filter", changes) },
            onOpenFilterRack = {
                showControls(true)
                side.selectionModel.select(lensTab)
                Platform.runLater {
                    val root = lensScroll.scene?.root ?: return@runLater
                    root.applyCss()
                    root.layout()
                    val content = lensScroll.content ?: return@runLater
                    val travel = content.layoutBounds.height - lensScroll.viewportBounds.height
                    if (travel > 0.0) {
                        lensScroll.vvalue = (opticalFilterTabPanel.root.boundsInParent.minY / travel).coerceIn(0.0, 1.0)
                    }
                }
            },
            onOpenLensProfile = { lensProfileWindow.setVisible(true) },
            applyAudioPatch = audioAdapter::applyPatchAsync,
            onOpenAudioPanel = { showControls(true); side.selectionModel.select(audioTab) },
            onStatusMessage = updateStatusText,
            initialBodyTheme = themeController.bodyTheme,
            initialUiTheme = themeController.uiTheme,
            onEvfWindowVisibilityChanged = { open ->
                if (LaunchContext.smokePath == null) {
                    themeController.updateWindowPreferences(evfWindowOpen = open)
                }
            },
            initialEvfAlwaysOnTop = LaunchContext.smokePath == null && themeController.profile.evfAlwaysOnTop,
            onEvfAlwaysOnTopChanged = { pinned ->
                if (LaunchContext.smokePath == null) {
                    themeController.updateWindowPreferences(evfAlwaysOnTop = pinned)
                }
            },
        )
        studio.readShutterHalfReady = { runtime.owner.submit { runtime.session.stillShutterHalfReady() } }
        studio.readShutterBusy = { runtime.owner.submit { runtime.session.stillShutterBusy() } }
        inputBindings = runCatching {
            InputBindingRuntime.create(
                home = themeController.home,
                owner = stage,
                studio = studio,
                readLens = {
                    runtime.owner.submit<LensProfileState?> {
                        val lens = runtime.host.components().singleOrNull { it.descriptor.id == "lens" } ?: return@submit null
                        val mount = runtime.host.components().singleOrNull { it.descriptor.id == "mount" }
                        LensProfileState.fromMaps(lens.config, lens.component.state(), mount?.config, mount?.component?.state())
                    }
                },
                patchLens = { changes -> patchLatest("lens", changes) },
                onError = ::error,
                themeController = themeController,
            )
        }.onFailure(::error).getOrNull()
        themeListenerHandle = themeController.addThemeListener { nextBody: BodyTheme, nextUi: UiTheme, _ ->
            Platform.runLater {
                if (::studio.isInitialized) {
                    studio.applyBodyTheme(nextBody, forceReload = true)
                    studio.applyUiTheme(nextUi)
                }
                if (::lensProfileWindow.isInitialized) {
                    lensProfileWindow.applyUiTheme(nextUi)
                }
                UiThemeRegistry.applyToAllOpenStages(nextUi)
                brandMarkLabel.text = nextBody.brandLabel
                brandModelLabel.text = nextBody.modelLabel
                stage.title = I18n.t("app.title.main", nextBody.modelLabel)
                syncingThemeSelectors = true
                bodyThemeCombo.items.setAll(themeController.availableBodyThemes().map { it.id })
                bodyThemeCombo.value = nextBody.id
                uiThemeCombo.items.setAll(themeController.availableUiThemes().map { it.id })
                uiThemeCombo.value = nextUi.id
                syncingThemeSelectors = false
                refreshThemeTooltips()
                status.text = I18n.t("app.status.theme_switched", nextBody.id, nextUi.id)
            }
        }
        direction.textProperty().bind(studio.viewTitleProperty)
        val release = Button().apply {
            textProperty().bind(I18n.bind("app.release_all"))
            tooltip = Tooltip().apply { textProperty().bind(I18n.bind("app.release_all.tooltip")) }
            setOnAction { studio.releaseAll(includeLatching = false) }
        }
        configPane.padding = Insets(8.0, 0.0, 10.0, 0.0)
        configPane.children += HBox(8.0, studio.latch, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, release).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
            styleClass += "studio-card"
            VBox.setMargin(this, Insets(0.0, 8.0, 4.0, 8.0))
        }
        val controlsTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.controls"))
            content = ScrollPane(studio.root).apply {
                isFitToWidth = true
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            }
            isClosable = false
        }
        side.tabs.add(0, controlsTab)
        val address = TextField("0xFFFC41E0").apply {
            promptText = I18n.t("app.dict.prompt.address")
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val width = ComboBox<Int>().apply { items.addAll(1, 2, 4, 8); value = 4 }
        val protocolWidths = (runtime.catalog.catalog.logicalSpaces.map { it.id to it.wordBits } +
            runtime.catalog.catalog.protocolCandidateSpaces.map { it.id to it.wordBits }).toMap()
        val space = ComboBox<String>().apply { items.setAll(listOf("cpu-physical") + protocolWidths.keys); value = "cpu-physical" }
        val unit = Label(I18n.t("app.dict.unit.bytes")).apply { styleClass += "studio-setting-label" }
        fun refreshDictPromptAndUnit() {
            val bits = protocolWidths[space.value]
            unit.text = I18n.t(if (bits == null) "app.dict.unit.bytes" else "app.dict.unit.bits")
            address.promptText = I18n.t(if (bits == null) "app.dict.prompt.address" else "app.dict.prompt.selector")
        }
        space.valueProperty().addListener { _, _, selected ->
            address.clear()
            val bits = protocolWidths[selected]
            width.items.setAll(if (bits == null) listOf(1, 2, 4, 8) else listOf(bits))
            width.value = bits ?: 4
            refreshDictPromptAndUnit()
        }
        val dictionaryText = TextArea().apply { isEditable = false; isWrapText = true }
        val lookup = Button().apply {
            textProperty().bind(I18n.bind("app.dict.lookup"))
            setOnAction {
                val params = try { catalogQueryParameters(space.value, address.text, width.value) } catch (problem: IllegalArgumentException) { error(problem); return@setOnAction }
                runtime.owner.submit { runtime.catalog.query(params) }.whenComplete { result, problem ->
                    Platform.runLater {
                        if (problem != null) error(problem) else dictionaryText.text = Formats.jsonText(result)
                    }
                }
            }
        }
        val unknown = Button().apply {
            textProperty().bind(I18n.bind("app.dict.unknown"))
            setOnAction {
                runtime.owner.submit { runtime.session.discoveryState() }.whenComplete { result, problem ->
                    Platform.runLater {
                        if (problem != null) error(problem) else dictionaryText.text = Formats.jsonText(result)
                    }
                }
            }
        }
        val dictTop = VBox(6.0,
            HBox(6.0, space, address).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            HBox(6.0, width, unit, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, lookup, unknown).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
        ).apply { padding = Insets(8.0) }
        val dictionaryTab = Tab().apply {
            textProperty().bind(I18n.bind("app.tab.dictionary"))
            content = BorderPane(dictionaryText, dictTop, null, null, null)
            isClosable = false
        }
        side.tabs.add(dictionaryTab)
        appLocaleListener = I18n.onLocaleChange { newLocale ->
            val refresh: () -> Unit = {
                syncLanguageSelector()
                stage.title = I18n.t("app.title.main", themeController.bodyTheme.modelLabel)
                boot.text = I18n.t(bootStateKey)
                controlsToggle.accessibleText = I18n.t("app.controls.toggle")
                refreshDictPromptAndUnit()
                refreshThemeSelectorsDisplay()
                themeController.updateLanguage(I18n.localeCode(newLocale))
            }
            if (Platform.isFxApplicationThread()) refresh() else Platform.runLater(refresh)
        }
        root.top = topHeaderBox; root.center = side
        root.bottom=HBox(status).apply { padding=Insets(5.0,10.0,5.0,10.0);styleClass += "studio-statusbar" }
        stage.setOnCloseRequest { event -> event.consume();mainCloseRequested=true;Platform.exit() }
        stage.show()
        val initialFitScale = if (LaunchContext.smokePath == null) themeController.profile.fitScale else 1.0
        if (LaunchContext.smokePath == null) studio.showDefault(initialFitScale) else studio.showAll(initialFitScale)
        if (LaunchContext.smokePath == null && themeController.profile.evfWindowOpen) {
            studio.setEvfWindowVisible(true)
        }
        audioIoPanel.refreshDevicesAsync()
        runCatching { audioAdapter.fetchStatus() }.getOrNull()?.let { st ->
            audioIoPanel.observeStatusMap(st)
            studio.observeAudioStatus(st)
        }
        runtime.host.snapshot().whenComplete { data, problem -> Platform.runLater {
            if (problem == null) {
                buildConfig(data)
                studio.observeSnapshot(data)
                lensProfileTabPanel.observeSnapshot(data)
                opticalFilterTabPanel.observeSnapshot(data)
                orientationIbisPane.observeSnapshot(data)
                lensProfileWindow.observeSnapshot(data)
                lastObservedConfigRevision = runtime.host.configRevision
                lastVglibPollNanos = System.nanoTime()
            } else error(problem)
        } }
        uiStateComponentIds = buildSet {
            add("buttons")
            add("soc-gpio")
            add("soc-adc")
            add("i2c-bus")
            add("rear-touch-ic")
            add("tmd2772")
            add("mount")
            add("battery")
            add("optical-filter")
            add("cmos")
            add("shutter")
            studio.catalog.forEach { panel ->
                panel.layers.forEach { add(it.componentId) }
                panel.controls.forEach { control ->
                    if (control.settingKey != null && control.componentId != null) {
                        add(control.componentId)
                    }
                }
            }
            remove("soc-vglib-init")
            remove("soc-spi")
            remove("mma8452q")
            remove("lens")
        }
        uiStateComponentIdSets = Array(8) { mask ->
            DiagnosticsRefreshController.selectUiStateComponentIds(
                baseIds = uiStateComponentIds,
                includeVglib = (mask and 4) != 0,
                includeLens = (mask and 2) != 0,
                includeOrientationIbis = (mask and 1) != 0,
            )
        }
        timeline = Timeline(KeyFrame(Duration.millis(250.0), { refresh() })).apply { cycleCount = Timeline.INDEFINITE; play() }
        displayTimer = object : AnimationTimer() {
            override fun handle(now: Long) {
                studio.paintFromBridge(runtime.displayBridge)
            }
        }.also { it.start() }
        LaunchContext.smokePath?.let { path -> smoke(stage, path) }
        if (LaunchContext.autoBoot && LaunchContext.smokePath == null) {
            Platform.runLater { boot.fire() }
        }
    }
    private fun buildConfig(data: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST") val components = data["components"] as List<Map<String, Any?>>
        components.forEach { item ->
            val id = item["id"] as String
            @Suppress("UNCHECKED_CAST")
            val settings = (item["settings"] as? List<com.github.nyabanban.fuji.emulator.api.Setting>)
                ?: runtime.owner.submit { runtime.host.components().single { it.descriptor.id == id }.descriptor.settings }.join()
            @Suppress("UNCHECKED_CAST") val config = item["config"] as Map<String, Any>
            val grid = GridPane().apply {
                hgap = 8.0; vgap = 6.0; padding = Insets(6.0, 6.0, 6.0, 6.0)
                columnConstraints.addAll(
                    ColumnConstraints().apply { minWidth = 104.0; prefWidth = 122.0; maxWidth = 148.0; hgrow = Priority.SOMETIMES },
                    ColumnConstraints().apply { minWidth = 64.0; hgrow = Priority.ALWAYS },
                    ColumnConstraints().apply { hgrow = Priority.NEVER }
                )
            }
            settings.forEachIndexed { row, setting ->
                val text = TextField(config[setting.key].toString()).apply { prefColumnCount = 6; minWidth=60.0; maxWidth = Double.MAX_VALUE; isDisable = !setting.live }
                editors[id + "." + setting.key] = text; observedValues[id + "." + setting.key] = text.text
                val apply = Button().apply {
                    textProperty().bind(I18n.bind(if (setting.live) "app.action.apply" else "app.config.restart_required"))
                    minWidth = if (setting.live) 46.0 else 68.0
                    isDisable = !setting.live
                    setOnAction {
                        runCatching {
                            val value: Any = when (setting.kind) {
                                com.github.nyabanban.fuji.emulator.api.ValueKind.BOOLEAN -> text.text.toBooleanStrict()
                                com.github.nyabanban.fuji.emulator.api.ValueKind.INTEGER -> text.text.toLong()
                                com.github.nyabanban.fuji.emulator.api.ValueKind.DECIMAL -> text.text.toDouble()
                                com.github.nyabanban.fuji.emulator.api.ValueKind.TEXT -> text.text
                            }
                            patchLatest(id, mapOf(setting.key to setting.validate(value))).thenAccept {
                                Platform.runLater { lastProblem = null; status.text = I18n.t("app.status.setting_applied", id, setting.key) }
                            }
                        }.onFailure(::error)
                    }
                }
                grid.addRow(row, Label(setting.key).apply { styleClass += "studio-setting-label"; tooltip=Tooltip(setting.key) }, text, apply)
            }
            configPane.children += TitledPane(id + " · " + item["version"], grid).apply { userData = id; isAnimated=false; isExpanded = id == "battery" }
        }
    }
    private fun refresh() {
        if (uiBackgroundExecutor.isShutdown || !::studio.isInitialized || !::uiStateComponentIdSets.isInitialized || !busy.compareAndSet(false, true)) return
        val selectedTab = side.selectionModel.selectedItem
        val showLensOpticsTab = entryStage.isShowing && selectedTab === lensTab
        val showDiagnosticsTab = entryStage.isShowing && selectedTab === diagnosticsTab
        val showAudioTab = entryStage.isShowing && selectedTab === audioTab
        val configChanged = runtime.host.configRevision != lastObservedConfigRevision
        val needLensState = configChanged || showLensOpticsTab || lensProfileWindow.isShowing
        val needOrientationIbisState = configChanged || showLensOpticsTab
        val nowNanos = System.nanoTime()
        val fetchDiagnostics = diagnosticsController.shouldFetchDiagnostics(
            tabVisible = showDiagnosticsTab,
            paused = interactiveStarted && paused.get(),
            nowNanos = nowNanos,
        )
        if (fetchDiagnostics) {
            diagnosticsController.markDiagnosticsFetchDispatched(nowNanos)
        }
        val fetchAudio = diagnosticsController.shouldFetchAudio(
            audioTabVisible = showAudioTab,
            nowNanos = nowNanos,
        )
        if (fetchAudio) {
            diagnosticsController.markAudioFetchDispatched(nowNanos)
        }
        val mainSeq = runtime.displayBridge.stats("display.main")?.lastPublishedSequence ?: 0L
        val evfSeq = runtime.displayBridge.stats("display.evf")?.lastPublishedSequence ?: 0L
        val needVglibState = fetchDiagnostics ||
            configChanged ||
            mainSeq != lastVglibRefreshMainSeq ||
            evfSeq != lastVglibRefreshEvfSeq ||
            (nowNanos - lastVglibPollNanos) >= 2_000_000_000L
        val stateMask = (if (needVglibState) 4 else 0) or
            (if (needLensState) 2 else 0) or
            (if (needOrientationIbisState) 1 else 0)
        val publishDisplaysHere = !interactiveStarted || paused.get()
        runtime.owner.submit {
            val machine = if (fetchDiagnostics) runtime.session.state() else runtime.session.displayStatus()
            if (publishDisplaysHere) {
                runtime.displayBridge.publishAll(runtime.host.ports)
            }
            val hostSnap = if (fetchDiagnostics) {
                runtime.host.snapshot().join()
            } else {
                runtime.host.uiSnapshotDirect(
                    stateComponentIds = uiStateComponentIdSets[stateMask],
                    includeAllConfigs = configChanged,
                )
            }
            hostSnap + mapOf("machine" to machine, "switchingToStill" to runtime.session.studioSwitchingToStill())
        }.thenApplyAsync({ snapshot ->
            val formattedDiagnostics = if (fetchDiagnostics) {
                diagnosticsController.formatDiagnosticsJson(snapshot)
            } else {
                null
            }
            val audioStatus = if (fetchAudio) {
                runCatching { audioAdapter.fetchStatus() }.getOrNull()
            } else {
                null
            }
            RefreshPayload(snapshot, formattedDiagnostics, audioStatus)
        }, uiBackgroundExecutor).whenComplete { payload, problem -> Platform.runLater {
            try {
                if (problem != null) {
                    if (!stopRequested.get() && !uiBackgroundExecutor.isShutdown) error(problem)
                    return@runLater
                }
                val snapshot = payload.snapshot
                val observedRev = (snapshot["configRevision"] as? Long) ?: runtime.host.configRevision
                if (configChanged || observedRev != lastObservedConfigRevision) {
                    @Suppress("UNCHECKED_CAST") val components = snapshot["components"] as List<Map<String, Any?>>
                    components.forEach { component ->
                        @Suppress("UNCHECKED_CAST") val config = component["config"] as? Map<String, Any> ?: return@forEach
                        val compId = component["id"].toString()
                        config.forEach { (key, value) ->
                            val name = "$compId.$key"; val current = value.toString()
                            editors[name]?.let { field ->
                                val previous = observedValues[name]
                                if (!field.isFocused && (field.text == previous || current != previous)) field.text = current
                            }
                            observedValues[name] = current
                        }
                    }
                    lastObservedConfigRevision = observedRev
                }
                if (needVglibState) {
                    lastVglibRefreshMainSeq = mainSeq
                    lastVglibRefreshEvfSeq = evfSeq
                    lastVglibPollNanos = nowNanos
                }
                studio.observeSnapshot(snapshot)
                val audioStatus = payload.audioStatus
                if (audioStatus != null) {
                    audioIoPanel.observeStatusMap(audioStatus)
                    studio.observeAudioStatus(audioStatus)
                }
                if (configChanged || showLensOpticsTab) {
                    lensProfileTabPanel.observeSnapshot(snapshot)
                    opticalFilterTabPanel.observeSnapshot(snapshot)
                    orientationIbisPane.observeSnapshot(snapshot)
                }
                if (configChanged || lensProfileWindow.isShowing) lensProfileWindow.observeSnapshot(snapshot)
                studio.paintFromBridge(runtime.displayBridge)
                @Suppress("UNCHECKED_CAST") val machine = snapshot["machine"] as Map<String, Any?>
                val labelStatus = if (interactiveStarted && paused.get()) "paused" else machine["status"].toString()
                val nextStatus = lastProblem ?: if (studio.showingStillModeTransition) {
                    I18n.t("studio.mode.switching_to_still")
                } else labelStatus + " · " + machine["attempts"] + " attempts"
                if (status.text != nextStatus) status.text = nextStatus
                val formatted = payload.formattedDiagnostics
                if (formatted != null && diagnosticsController.acceptFormattedIfChanged(formatted)) {
                    val prevScrollTop = stateText.scrollTop
                    val prevScrollLeft = stateText.scrollLeft
                    stateText.text = formatted
                    stateText.scrollTop = prevScrollTop
                    stateText.scrollLeft = prevScrollLeft
                }
            } finally { busy.set(false) }
        } }
    }
    private fun save(path: Path, value: Any?) { Formats.writeJson(path,value) }
    private fun editorVisible(id:String):Boolean {
        val e=editors[id]?:return false
        val viewport=configurationScroll.lookup(".viewport")?:return false
        if(e.scene!==entryStage.scene || !e.isVisible) return false
        val a=e.localToScene(e.boundsInLocal);val b=viewport.localToScene(viewport.boundsInLocal)
        return a.minX>=b.minX-1 && a.maxX<=b.maxX+1 && a.minY>=b.minY-1 && a.maxY<=b.maxY+1
    }
    private fun screenshot(stage: Stage, path: Path) {
        stage.scene.root.applyCss(); stage.scene.root.layout()
        studio.nativeScreenshot(path)
    }
    private fun smoke(stage: Stage, path: Path) {
        fun after(milliseconds:Double, action:()->Unit) { javafx.animation.PauseTransition(Duration.millis(milliseconds)).apply { setOnFinished { action() };play() } }
        fun captureAll() {
            Files.createDirectories(path.parent)
            configure("battery"); screenshot(stage, path.resolveSibling("ui-controls.png"))
            val previews = mutableListOf<Map<String, Any?>>()
            val projectionCaptures = mutableListOf<Map<String, Any?>>()
            listOf(false, true).forEach { attached ->
                patchLatest("mount", mapOf("attached" to attached)).join()
                val snapshot = runtime.host.snapshot().join(); studio.observeSnapshot(snapshot)
                @Suppress("UNCHECKED_CAST") val components = snapshot["components"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST") val mount = components.single { it["id"] == "mount" }["state"] as Map<String, Any?>
                listOf("front", "top", "ports-left", "storage-right", "battery-bottom").forEach { viewId ->
                    studio.selectView(viewId); showControls(false)
                    val state = if (attached) "attached" else "detached"
                    val name = if (viewId == "front") "ui-lens-$state.png" else "ui-lens-$viewId-$state.png"
                    screenshot(stage, path.resolveSibling(name))
                    val observation = studio.state() + mapOf("file" to name, "ownerMountAttached" to mount["attached"])
                    projectionCaptures += observation
                    if (viewId == "front") previews += observation
                }
            }
            save(path.resolveSibling("ui-preview.json"), mapOf("schema" to 2, "observations" to previews, "limitation" to "Host mount parameter and illustration only; no electrical contact claim"))
            save(path.resolveSibling("ui-lens-projections.json"), mapOf("schema" to 1, "captures" to projectionCaptures, "limitation" to "Owner-requested shallow cap silhouettes, not measured optical geometry"))
            showControls(false)
            val captures = studio.screenshots(path.parent)
            save(path.resolveSibling("ui-studio-captures.json"), mapOf("schema" to 2, "captures" to captures))
            studio.selectView("rear"); configure("battery"); screenshot(stage, path)
            save(path.resolveSibling("ui-panels.json"), studio.state() + mapOf("configurationEntryVisible" to stage.isShowing, "configEditorVisible" to editorVisible("battery.chargePercent")))
            save(path.resolveSibling("ui-state.json"), runtime.host.snapshot().join())
            save(path.resolveSibling("ui-widgets.json"), mapOf(
                "batteryChargeText" to editors["battery.chargePercent"]?.text,
                "menuSelected" to studio.buttons.getValue("menu").isSelected,
                "mainWaitingForFirmwareFrame" to studio.waiting("rear-lcd"),
                "evfWaitingForFirmwareFrame" to studio.waiting("rear-evf"),
                "shoulderWaitingForFirmwareFrame" to studio.waiting("top-shoulder")))
            stage.scene.root.applyCss();stage.scene.root.layout()
            val colors=configPane.lookupAll(".text").filterIsInstance<javafx.scene.text.Text>().filter { it.text.isNotBlank() }.mapNotNull { t ->
                (t.fill as? javafx.scene.paint.Color)?.let { c->mapOf("text" to t.text.take(80),"rgba" to listOf(c.red,c.green,c.blue,c.opacity),"smoothing" to t.fontSmoothingType.toString()) }
            }
            save(path.resolveSibling("ui-configuration-colors.json"),mapOf("schema" to 1,"rows" to colors,"scope" to "Computed JavaFX configuration text skin paints; optical glass colors excluded"))
            val layouts = mutableListOf<Map<String,Any?>>()
            showControls(false); screenshot(stage,path.resolveSibling("ui-workbench.png"))
            layouts += studio.state()+mapOf("mode" to "normal","file" to "ui-workbench.png")
            configure("battery"); screenshot(stage,path.resolveSibling("ui-controls.png"))
            layouts += studio.state()+mapOf("mode" to "sidebar","file" to "ui-controls.png")
            showControls(false); studio.arrangeWindows(.8)
            Timeline(KeyFrame(Duration.seconds(.35), {
                configure("battery"); screenshot(stage,path.resolveSibling("ui-workbench-compact.png"))
                layouts += studio.state()+mapOf("mode" to "compact","file" to "ui-workbench-compact.png")
                save(path.resolveSibling("ui-workbench-layout.json"),mapOf("schema" to 1,"captures" to layouts))
                stage.fireEvent(WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST))
            })).play()
        }
        after(1500.0) {
            Files.createDirectories(path.parent)
            val interactions = studio.interactionSmoke {
                runtime.owner.submit {
                    val buttons = requireNotNull(runtime.host.ports.find("buttons", ButtonPort::class.java))
                    PanelCatalog.buttonKeys.associateWith { buttons.pressed(it) }
                }.join()
            }
            save(path.resolveSibling("ui-interactions.json"), interactions)
            save(path.resolveSibling("ui-window-drag.json"), studio.dragSmoke { runtime.owner.submit { val p=requireNotNull(runtime.host.ports.find("buttons",ButtonPort::class.java));PanelCatalog.buttonKeys.associateWith { p.pressed(it) } }.join() })
            side.selectionModel.select(0)
            save(path.resolveSibling("ui-window-lifecycle.json"), studio.lifecycleSmoke(path.parent) { runtime.owner.submit { val p=requireNotNull(runtime.host.ports.find("buttons",ButtonPort::class.java));PanelCatalog.buttonKeys.associateWith { p.pressed(it) } }.join() })
            studio.latch.isSelected = true
            patchLatest("battery", mapOf("chargePercent" to 37.0))
            studio.buttons.getValue("menu").fire()
            after(200.0) { studio.setFaceVisible("rear",false); after(250.0) {
                screenshot(stage,path.resolveSibling("ui-face-hidden.png")); studio.setFaceVisible("rear",true)
                after(250.0) {
                    screenshot(stage,path.resolveSibling("ui-face-reopened.png"));side.selectionModel.select(0);screenshot(stage,path.resolveSibling("ui-window-controls.png"))
                    after(150.0) {
                        studio.beginFrontHitSmoke { runtime.owner.submit { val p=requireNotNull(runtime.host.ports.find("buttons",ButtonPort::class.java));PanelCatalog.buttonKeys.associateWith { p.pressed(it) } }.join() }
                        // Relative phases allow real window skin/picking pulses after every show/hide/native input.
                        after(250.0) { studio.pressFrontHitSmoke(); after(150.0) { studio.releaseFrontHitSmoke(); after(250.0) {
                            save(path.resolveSibling("ui-front-hit.json"),studio.finishFrontHitSmoke { runtime.owner.submit { val p=requireNotNull(runtime.host.ports.find("buttons",ButtonPort::class.java));PanelCatalog.buttonKeys.associateWith { p.pressed(it) } }.join() })
                            captureAll()
                        } } }
                    }
                }
            } }
        }
    }
    override fun stop() {
        triggerBootFromUi = null
        appLocaleListener?.let { I18n.localeProperty.removeListener(it) }
        appLocaleListener = null
        runCatching { themeListenerHandle?.close() }
        themeListenerHandle = null
        LaunchContext.bootTrigger = null
        LaunchContext.onPauseChanged = null
        stopRequested.set(true)
        paused.set(false)
        runnerThread?.let { thread ->
            runCatching { thread.join(2000) }
        }
        displayTimer?.stop()
        if(::timeline.isInitialized) timeline.stop()
        uiBackgroundExecutor.shutdownNow()
        if(::lensProfileWindow.isInitialized) lensProfileWindow.close()
        runCatching { inputBindings?.close() }
        inputBindings = null
        if(::studio.isInitialized) studio.close(releaseLatching = LaunchContext.smokePath != null)
        if (LaunchContext.isRuntimeInitialized) {
            LaunchContext.smokePath?.let { p ->
                val released=runtime.owner.submit { val port=requireNotNull(runtime.host.ports.find("buttons",ButtonPort::class.java));PanelCatalog.buttonKeys.associateWith { port.pressed(it) } }.join()
                save(p.resolveSibling("ui-shutdown.json"),mapOf("schema" to 1,"mainCloseRequestHandled" to mainCloseRequested,"showingStagesAfterClose" to Window.getWindows().filterIsInstance<Stage>().count { it.isShowing },"buttonsAfterClose" to released,"runtimeClosureOwner" to "Main EmulatorRuntime.use after Application.launch returns"))
            }
        }
    }
}
