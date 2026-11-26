package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioTypography
import com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.StringConverter
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Clean, themed, localized Desktop GUI guidance & one-click `GXUP0004.DAT` import dialog
 * shown whenever firmware (`GXUP0004.DAT`), NOR Flash (`nor-flash.bin`), or requested
 * `boot-rom.bin` is missing or fails SHA-256 verification.
 */
object FirmwareGuidanceDialog {
    internal data class BuiltDialog(
        val root: BorderPane,
        val headlineLabel: Label,
        val introLabel: Label,
        val stepsLabel: Label,
        val urlField: TextField,
        val copyUrlButton: Button,
        val openUrlButton: Button,
        val detailsArea: TextArea,
        val selectDatButton: Button,
        val closeButton: Button,
        val feedbackLabel: Label,
        val languageCombo: ComboBox<String>,
        val dispose: () -> Unit,
    )

    /**
     * Formats the localized, copyable technical summary block (expected paths, SHA-256,
     * optional real-camera dump reminder, and `logs/latest.log` path) for [issue].
     */
    fun formatLocalizedDetails(issue: FirmwareGuidance.Issue): String {
        val datPaths = issue.expectedDatPaths.joinToString(" | ") {
            "${FirmwareGuidance.relativizeDisplay(issue.home, it)} ($it)"
        }
        val norSummary = "${FirmwareGuidance.relativizeDisplay(issue.home, issue.expectedNorPath)} (${issue.expectedNorPath})"
        val bootRomSummary = "${FirmwareGuidance.relativizeDisplay(issue.home, issue.expectedBootRomPath)} (${issue.expectedBootRomPath})"
        val logSummary = "${FirmwareGuidance.DEFAULT_LOG_RELATIVE_PATH} (${issue.logFilePath})"
        return buildString {
            appendLine(I18n.t("firmware.guide.row.target", "${issue.primaryRelativeLabel} (${issue.primaryPath})"))
            appendLine(I18n.t("firmware.guide.row.detail", summarizeSingleLineDetail(issue)))
            appendLine(I18n.t("firmware.guide.row.expected_sha256", FirmwareGuidance.EXPECTED_GXUP0004_SHA256))
            if (issue.actualSha256 != null) {
                appendLine(I18n.t("firmware.guide.row.actual_sha256", issue.actualSha256))
            }
            appendLine(I18n.t("firmware.guide.row.expected_dat", datPaths))
            appendLine(I18n.t("firmware.guide.row.expected_nor", norSummary))
            appendLine(I18n.t("firmware.guide.row.expected_bootrom", bootRomSummary))
            appendLine(I18n.t("firmware.guide.row.cli_tip", "--flash-dat <path/to/${FirmwareGuidance.EXPECTED_DAT_FILENAME}>"))
            appendLine(I18n.t("firmware.guide.row.log_file", logSummary))
            appendLine()
            append(I18n.t("firmware.guide.row.dump_note"))
        }
    }

    private fun summarizeSingleLineDetail(issue: FirmwareGuidance.Issue): String {
        val firstLine = issue.detailMessage.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= 220) firstLine else firstLine.take(217) + "..."
    }

    internal fun buildContent(
        initialIssue: FirmwareGuidance.Issue,
        stageForDialogs: Stage?,
        onLanguageSelected: ((String) -> Unit)? = null,
        onImportDat: ((Path) -> Path)? = null,
        onImportedSuccess: ((Path) -> Unit)? = null,
        onCloseRequested: () -> Unit,
    ): BuiltDialog {
        var activeIssue = initialIssue
        var syncingLang = false

        val headlineLabel = Label().apply {
            id = "firmware-guide-headline"
            styleClass += "studio-brand-model"
            style = "-fx-font-size: 15px; -fx-font-weight: bold;"
            isWrapText = true
            maxWidth = Double.MAX_VALUE
        }

        val languageCombo = ComboBox<String>().apply {
            id = "firmware-guide-lang-combo"
            styleClass += "studio-header-combo"
            minWidth = 84.0
            prefWidth = 108.0
            items.setAll(I18n.supportedLanguageCodes())
            converter = object : StringConverter<String>() {
                override fun toString(code: String?): String =
                    if (code.isNullOrBlank()) "" else I18n.nativeDisplayName(code)
                override fun fromString(text: String?): String =
                    I18n.localeCode(I18n.normalizeLocale(text))
            }
            value = I18n.localeCode()
            setOnAction {
                if (!syncingLang && value != null) {
                    val chosen = value
                    I18n.setLocale(chosen)
                    onLanguageSelected?.invoke(I18n.localeCode())
                }
            }
        }

        val headerBar = HBox(
            10.0,
            headlineLabel.also { HBox.setHgrow(it, Priority.ALWAYS) },
            languageCombo,
        ).apply {
            alignment = Pos.CENTER_LEFT
        }

        val introLabel = Label().apply {
            id = "firmware-guide-intro"
            isWrapText = true
            maxWidth = Double.MAX_VALUE
        }

        val stepsLabel = Label().apply {
            id = "firmware-guide-steps"
            styleClass += "studio-card"
            padding = Insets(10.0, 12.0, 10.0, 12.0)
            isWrapText = true
            maxWidth = Double.MAX_VALUE
        }

        val urlTitleLabel = Label().apply {
            styleClass += "studio-setting-label"
            textProperty().bind(I18n.bind("firmware.guide.url.label"))
        }

        val urlField = TextField(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL).apply {
            id = "firmware-guide-url-field"
            isEditable = false
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
        }

        val feedbackLabel = Label().apply {
            id = "firmware-guide-feedback"
            styleClass += "studio-hint-text"
            isWrapText = true
            maxWidth = Double.MAX_VALUE
        }

        val copyUrlButton = Button().apply {
            id = "firmware-guide-copy-url-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("firmware.guide.action.copy_url"))
            setOnAction {
                runCatching {
                    val clip = Clipboard.getSystemClipboard()
                    val content = ClipboardContent()
                    content.putString(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL)
                    clip.setContent(content)
                }
                urlField.selectAll()
                urlField.requestFocus()
                feedbackLabel.text = I18n.t("firmware.guide.feedback.url_copied")
            }
        }

        val openUrlButton = Button().apply {
            id = "firmware-guide-open-url-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            textProperty().bind(I18n.bind("firmware.guide.action.open_url"))
            setOnAction {
                Thread.ofVirtual().name("firmware-guide-open-url").start {
                    val opened = openExternalUrl(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL)
                    Platform.runLater {
                        feedbackLabel.text = I18n.t(
                            if (opened) "firmware.guide.feedback.url_opened" else "firmware.guide.feedback.url_open_manual",
                        )
                    }
                }
            }
        }

        val urlRow = HBox(6.0, urlField, copyUrlButton, openUrlButton).apply {
            alignment = Pos.CENTER_LEFT
        }

        val detailsTitleLabel = Label().apply {
            styleClass += "studio-setting-label"
            textProperty().bind(I18n.bind("firmware.guide.details.label"))
        }

        val detailsArea = TextArea().apply {
            id = "firmware-guide-details-area"
            isEditable = false
            isWrapText = false
            prefRowCount = 9
            style = "-fx-font-family: 'Monospaced'; -fx-font-size: 12px;"
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val closeButton = Button().apply {
            id = "firmware-guide-close-btn"
            styleClass += "studio-header-btn-secondary"
            minWidth = Region.USE_PREF_SIZE
            isCancelButton = true
            textProperty().bind(I18n.bind("firmware.guide.action.close"))
            setOnAction { onCloseRequested() }
        }

        val selectDatButton = Button().apply {
            id = "firmware-guide-select-dat-btn"
            styleClass += "studio-header-btn"
            minWidth = Region.USE_PREF_SIZE
            isDefaultButton = true
            isManaged = onImportDat != null
            isVisible = onImportDat != null
            textProperty().bind(I18n.bind("firmware.guide.action.select_dat"))
        }

        fun refreshTexts() {
            syncingLang = true
            val code = I18n.localeCode()
            if (languageCombo.value != code) {
                languageCombo.value = code
            }
            syncingLang = false
            stageForDialogs?.title = I18n.t("firmware.guide.title")
            headlineLabel.text = I18n.t(activeIssue.headlineI18nKey())
            introLabel.text = I18n.t("firmware.guide.intro")
            stepsLabel.text = I18n.t("firmware.guide.steps")
            detailsArea.text = formatLocalizedDetails(activeIssue)
        }

        selectDatButton.setOnAction {
            val importer = onImportDat ?: return@setOnAction
            val chooser = FileChooser().apply {
                title = I18n.t("firmware.guide.chooser.title")
                val initialDir = activeIssue.home.toFile().takeIf { it.isDirectory }
                if (initialDir != null) {
                    initialDirectory = initialDir
                }
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("FUJIFILM GFX100S Firmware (*.DAT)", "*.DAT", "*.dat"),
                    FileChooser.ExtensionFilter("All Files (*.*)", "*.*"),
                )
            }
            val selectedFile = chooser.showOpenDialog(stageForDialogs) ?: return@setOnAction
            selectDatButton.isDisable = true
            closeButton.isDisable = true
            feedbackLabel.text = I18n.t("firmware.guide.feedback.importing", selectedFile.name)
            Thread.ofVirtual().name("firmware-guide-import-dat").start {
                runCatching { importer(selectedFile.toPath()) }
                    .onSuccess { importedPath ->
                        Platform.runLater {
                            selectDatButton.isDisable = false
                            closeButton.isDisable = false
                            onImportedSuccess?.invoke(importedPath)
                            onCloseRequested()
                        }
                    }
                    .onFailure { err ->
                        val updatedIssue = FirmwareGuidance.diagnose(
                            error = err,
                            home = activeIssue.home,
                            explicitTarget = selectedFile.toPath(),
                        )
                        val conciseErr = updatedIssue?.let(::summarizeSingleLineDetail)
                            ?: err.message
                            ?: err.javaClass.simpleName
                        Platform.runLater {
                            selectDatButton.isDisable = false
                            closeButton.isDisable = false
                            if (updatedIssue != null) {
                                activeIssue = updatedIssue
                                refreshTexts()
                            }
                            feedbackLabel.text = I18n.t("firmware.guide.feedback.import_failed", conciseErr)
                        }
                    }
            }
        }

        val localeListener = ChangeListener<Locale> { _, _, _ ->
            if (Platform.isFxApplicationThread()) refreshTexts() else Platform.runLater(::refreshTexts)
        }
        I18n.localeProperty.addListener(localeListener)
        refreshTexts()

        val buttonBar = HBox(
            8.0,
            feedbackLabel.also { HBox.setHgrow(it, Priority.ALWAYS) },
            selectDatButton,
            closeButton,
        ).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }

        val centerBox = VBox(
            8.0,
            headerBar,
            introLabel,
            stepsLabel,
            urlTitleLabel,
            urlRow,
            detailsTitleLabel,
            detailsArea,
            buttonBar,
        ).apply {
            padding = Insets(14.0, 16.0, 14.0, 16.0)
        }

        val root = BorderPane(centerBox).apply {
            id = "firmware-guide-dialog-root"
            styleClass += "studio-shell"
            style = "-fx-font-family: '${StudioTypography.family}';"
        }

        return BuiltDialog(
            root = root,
            headlineLabel = headlineLabel,
            introLabel = introLabel,
            stepsLabel = stepsLabel,
            urlField = urlField,
            copyUrlButton = copyUrlButton,
            openUrlButton = openUrlButton,
            detailsArea = detailsArea,
            selectDatButton = selectDatButton,
            closeButton = closeButton,
            feedbackLabel = feedbackLabel,
            languageCombo = languageCombo,
            dispose = { I18n.localeProperty.removeListener(localeListener) },
        )
    }

    /**
     * Opens the modal firmware setup dialog and returns the imported path if the user selected and
     * imported a valid `GXUP0004.DAT`, or `null` if dismissed.
     */
    fun showModal(
        owner: Stage?,
        issue: FirmwareGuidance.Issue,
        uiTheme: UiTheme = UiThemeRegistry.origin(),
        onLanguageSelected: ((String) -> Unit)? = null,
        onImportDat: ((Path) -> Path)? = null,
    ): Path? {
        var importedResult: Path? = null
        val dialogStage = Stage().apply {
            if (owner != null) {
                initOwner(owner)
                initModality(Modality.WINDOW_MODAL)
            } else {
                initModality(Modality.APPLICATION_MODAL)
            }
            minWidth = 560.0
            minHeight = 460.0
            width = 680.0
            height = 530.0
        }
        val built = buildContent(
            initialIssue = issue,
            stageForDialogs = dialogStage,
            onLanguageSelected = onLanguageSelected,
            onImportDat = onImportDat,
            onImportedSuccess = { path -> importedResult = path },
            onCloseRequested = { dialogStage.close() },
        )
        dialogStage.scene = Scene(built.root, 680.0, 530.0)
        UiThemeRegistry.applyToScene(dialogStage.scene, uiTheme)
        dialogStage.setOnHidden { built.dispose() }
        dialogStage.showAndWait()
        return importedResult
    }

    private fun openExternalUrl(url: String): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        if (os.contains("linux")) {
            val launched = runCatching {
                val proc = ProcessBuilder("xdg-open", url)
                    .redirectErrorStream(true)
                    .start()
                proc.isAlive || proc.exitValue() == 0
            }.getOrDefault(false)
            if (launched) return true
        }
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
