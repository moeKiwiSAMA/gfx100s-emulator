package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.theme.BodyThemeRegistry
import com.github.nyabanban.fuji.emulator.desktop.theme.ThemeRuntimeController
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class I18nTest {
    @BeforeEach
    fun resetBefore() {
        I18n.onLocaleChanged = null
        I18n.resetToDefault()
    }

    @AfterEach
    fun resetAfter() {
        I18n.onLocaleChanged = null
        I18n.resetToDefault()
    }

    @Test
    fun defaultLocaleIsEnglishAndSwitchesLiveWithReactiveStringBinding() {
        assertEquals(Locale.ENGLISH, I18n.currentLocale)
        assertEquals(I18n.CODE_EN, I18n.localeCode())

        val titleBinding = I18n.bind("source.section.title")
        val formattedBinding = I18n.bind("source.summary.frames", 42L)
        val observedChanges = mutableListOf<String>()
        titleBinding.addListener { _, _, newVal -> observedChanges += newVal }

        assertEquals("INPUT SCENE", titleBinding.get())
        assertEquals("Frames: 42", formattedBinding.get())

        I18n.setLocale("zh-CN")
        assertEquals(I18n.SIMPLIFIED_CHINESE, I18n.currentLocale)
        assertEquals(I18n.CODE_ZH_CN, I18n.localeCode())
        assertEquals("输入场景 · INPUT SCENE", titleBinding.get())
        assertEquals("曝光帧: 42", formattedBinding.get())
        assertTrue(observedChanges.isNotEmpty(), "StringBinding must fire live change notifications on locale switch")

        I18n.setLocale("en")
        assertEquals(Locale.ENGLISH, I18n.currentLocale)
        assertEquals("INPUT SCENE", titleBinding.get())
        assertEquals("Frames: 42", formattedBinding.get())
    }

    @Test
    fun resourceBundlesMaintainExactKeyAndPlaceholderParity() {
        val enKeys = I18n.bundleKeys(I18n.CODE_EN)
        val supportedCodes = I18n.supportedLanguageCodes()

        assertEquals(
            listOf(I18n.CODE_EN, I18n.CODE_ZH_CN, I18n.CODE_JA_JP, I18n.CODE_DE_DE, I18n.CODE_FR_FR, I18n.CODE_ES_ES),
            supportedCodes,
        )
        assertTrue(enKeys.isNotEmpty(), "messages_en.properties must not be empty")

        // Pre-populated namespaces required for Part 1 & Part 2
        for (prefix in listOf("app.", "theme.", "controls.", "lens.", "filter.", "source.", "audio.", "orientation.", "evf.")) {
            assertTrue(enKeys.any { it.startsWith(prefix) }, "Expected pre-populated '$prefix*' keys in resource bundles")
        }

        val placeholderRegex = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
        for (code in supportedCodes) {
            val keys = I18n.bundleKeys(code)
            assertEquals(emptySet(), enKeys - keys, "Keys missing from messages_$code.properties: ${enKeys - keys}")
            assertEquals(emptySet(), keys - enKeys, "Extra keys missing from messages_en.properties (in $code): ${keys - enKeys}")

            for (key in enKeys) {
                val enVal = checkNotNull(I18n.rawPattern(I18n.CODE_EN, key))
                val locVal = checkNotNull(I18n.rawPattern(code, key))
                assertTrue(enVal.isNotBlank(), "Blank English pattern for key '$key'")
                assertTrue(locVal.isNotBlank(), "Blank $code pattern for key '$key'")
                val enPlaceholders = placeholderRegex.findAll(enVal).map { it.value }.filter { it != "%%" }.toList()
                val locPlaceholders = placeholderRegex.findAll(locVal).map { it.value }.filter { it != "%%" }.toList()
                assertEquals(
                    enPlaceholders,
                    locPlaceholders,
                    "Placeholder mismatch for key '$key': en=$enPlaceholders vs $code=$locPlaceholders",
                )
            }
        }

        // Verify all 6 languages resolve via canonical codes and aliases and produce localized titles
        val aliasChecks = mapOf(
            "en" to I18n.CODE_EN,
            "en-US" to I18n.CODE_EN,
            "english" to I18n.CODE_EN,
            "zh" to I18n.CODE_ZH_CN,
            "zh-CN" to I18n.CODE_ZH_CN,
            "chinese" to I18n.CODE_ZH_CN,
            "ja" to I18n.CODE_JA_JP,
            "jp" to I18n.CODE_JA_JP,
            "ja-JP" to I18n.CODE_JA_JP,
            "japanese" to I18n.CODE_JA_JP,
            "de" to I18n.CODE_DE_DE,
            "de-DE" to I18n.CODE_DE_DE,
            "german" to I18n.CODE_DE_DE,
            "deutsch" to I18n.CODE_DE_DE,
            "fr" to I18n.CODE_FR_FR,
            "fr-FR" to I18n.CODE_FR_FR,
            "french" to I18n.CODE_FR_FR,
            "francais" to I18n.CODE_FR_FR,
            "es" to I18n.CODE_ES_ES,
            "es-ES" to I18n.CODE_ES_ES,
            "spanish" to I18n.CODE_ES_ES,
            "espanol" to I18n.CODE_ES_ES,
        )
        for ((alias, expectedCode) in aliasChecks) {
            assertTrue(I18n.isSupportedCode(alias), "Alias '$alias' must be supported")
            assertEquals(expectedCode, I18n.localeCode(I18n.normalizeLocale(alias)), "Alias '$alias' resolution mismatch")
        }
        assertFalse(I18n.isSupportedCode("ko-KR"))
        assertFalse(I18n.isSupportedCode("pt-BR"))

        // Verify pickLocalized fallback chain: exact canonical -> hyphen -> 2-letter prefix -> en -> englishFallback
        val mapExact = mapOf("ja_JP" to "日本語ラベル", "en" to "English Label")
        assertEquals("日本語ラベル", I18n.pickLocalized(mapExact, "Fallback", "ja_JP"))
        val mapHyphen = mapOf("de-DE" to "Deutsches Label", "en" to "English Label")
        assertEquals("Deutsches Label", I18n.pickLocalized(mapHyphen, "Fallback", "de_DE"))
        val mapPrefix = mapOf("fr" to "Libellé Français", "en" to "English Label")
        assertEquals("Libellé Français", I18n.pickLocalized(mapPrefix, "Fallback", "fr_FR"))
        val mapEnOnly = mapOf("en" to "English Only")
        assertEquals("English Only", I18n.pickLocalized(mapEnOnly, "Fallback", "es_ES"))
        assertEquals("Fallback", I18n.pickLocalized(emptyMap(), "Fallback", "es_ES"))
        assertEquals("English Only", I18n.pickLocalized(mapEnOnly, "Fallback", "ko-KR"))

        // Verify English bundle stays free of stray CJK characters
        for (key in enKeys) {
            val enVal = checkNotNull(I18n.rawPattern(I18n.CODE_EN, key))
            assertFalse(
                enVal.any { it.code in 0x4E00..0x9FFF },
                "English bundle key '$key' contains unexpected CJK text: $enVal",
            )
        }
    }

    @Test
    fun unsupportedLocaleAndMissingKeyEmitSlf4jWarnFallbackDiagnostics() {
        val localeEvents = com.github.nyabanban.fuji.emulator.runtime.FujiLogCapture.capture(I18n::class.java) {
            val resolved = I18n.setLocale("ko-KR")
            assertEquals(Locale.ENGLISH, resolved)
        }
        assertTrue(
            localeEvents.any { ev ->
                ev.level == com.github.nyabanban.fuji.emulator.runtime.FujiLogLevel.WARN &&
                    ev.formattedMessage.contains("[FALLBACK][i18n]") &&
                    ev.formattedMessage.contains("ko-KR")
            },
            "Unsupported locale fallback must log WARN [FALLBACK][i18n]: $localeEvents",
        )

        val keyEvents = com.github.nyabanban.fuji.emulator.runtime.FujiLogCapture.capture(I18n::class.java) {
            val missing = I18n.t("totally.missing.i18n.key")
            assertEquals("totally.missing.i18n.key", missing)
        }
        assertTrue(
            keyEvents.any { ev ->
                ev.level == com.github.nyabanban.fuji.emulator.runtime.FujiLogLevel.WARN &&
                    ev.formattedMessage.contains("[FALLBACK][i18n]") &&
                    ev.formattedMessage.contains("totally.missing.i18n.key")
            },
            "Missing key fallback must log WARN [FALLBACK][i18n]: $keyEvents",
        )
    }

    @Test
    fun migratedPanelsContainZeroHardcodedCjkStringLiterals() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val desktopRoot = repo.resolve("desktop/src/main/kotlin/com/github/nyabanban/fuji/emulator/desktop")
        val migratedRelativePaths = listOf(
            "panels/SourceSelectorPane.kt",
            "panels/AudioIoPanel.kt",
            "panels/OrientationIbisPane.kt",
            "panels/EvfViewfinderController.kt",
            "panels/LensProfilePanel.kt",
            "panels/OpticalFilterPane.kt",
            "EmulatorApp.kt",
            "panels/CameraStudio.kt",
            "panels/ShootingControlsPane.kt",
            "theme/BodyTheme.kt",
            "theme/UiTheme.kt",
        )
        for (rel in migratedRelativePaths) {
            val file = desktopRoot.resolve(rel)
            assertTrue(Files.isRegularFile(file), "Missing migrated file: $file")
            val lines = Files.readAllLines(file)
            val violations = mutableListOf<String>()
            lines.forEachIndexed { idx, line ->
                if (line.any { it.code in 0x4E00..0x9FFF }) {
                    violations += "$rel:${idx + 1}: ${line.trim()}"
                }
            }
            assertEquals(
                emptyList(),
                violations,
                "Migrated file $rel must not contain hardcoded CJK text:\n${violations.joinToString("\n")}",
            )
        }

        // Sanity check that en and zh_CN actually produce distinct localized outputs
        I18n.setLocale("en")
        val enTitle = I18n.t("audio.engine.section")
        val lensState = LensProfileState.fromMaps(
            lensConfig = mapOf("template" to "gf45-100"),
            lensState = mapOf("template" to "gf45-100", "inFocus" to true, "bodyIrisSynced" to true),
            mountConfig = mapOf("attached" to true),
            mountState = mapOf("attached" to true),
        )
        assertTrue(lensState.telemetrySummaryLines().joinToString("\n").contains("IN FOCUS"))
        I18n.setLocale("zh_CN")
        val zhTitle = I18n.t("audio.engine.section")
        assertNotEquals(enTitle, zhTitle)
        assertTrue(lensState.telemetrySummaryLines().joinToString("\n").contains("合焦"))
    }

    @Test
    fun langConsoleCommandAndOnLocaleChangedPersistenceHookWorkEndToEnd() {
        val persisted = mutableListOf<String>()
        I18n.onLocaleChanged = { code -> persisted += code }

        val backend = object : ConsoleBackend {
            override val home: Path = Path.of(".").toAbsolutePath()
            override val telemetry: FirmwareTelemetry = FirmwareTelemetry()
            override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry)
            override fun isPaused(): Boolean = false
            override fun setPaused(paused: Boolean): Boolean = false
            override fun isRunning(): Boolean = true
            override fun requestBoot(): String = "booted"
            override fun requestStop(): String = "stopped"
            override fun listComponents(): List<String> = emptyList()
            override fun componentState(componentId: String): Map<String, Any?>? = null
            override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> = emptyMap()
            override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = null
        }

        val outBuf = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = outBuf,
            interactivePrompt = false,
        )

        assertTrue(console.executeLine("/lang"))
        assertTrue(console.executeLine("/lang list"))
        assertTrue(console.executeLine("/lang zh_CN"))
        assertEquals(I18n.CODE_ZH_CN, I18n.localeCode())
        assertTrue(console.executeLine("/lang ja"))
        assertEquals(I18n.CODE_JA_JP, I18n.localeCode())
        assertTrue(console.executeLine("/lang de_DE"))
        assertEquals(I18n.CODE_DE_DE, I18n.localeCode())
        assertTrue(console.executeLine("/lang fr-FR"))
        assertEquals(I18n.CODE_FR_FR, I18n.localeCode())
        assertTrue(console.executeLine("/lang es"))
        assertEquals(I18n.CODE_ES_ES, I18n.localeCode())
        assertTrue(console.executeLine("/lang en"))
        assertEquals(I18n.CODE_EN, I18n.localeCode())
        assertTrue(console.executeLine("/lang ko-KR"))
        assertEquals(I18n.CODE_EN, I18n.localeCode())

        val outputText = outBuf.toString(StandardCharsets.UTF_8)
        assertTrue(outputText.contains("Language: en (supported: en, zh_CN, ja_JP, de_DE, fr_FR, es_ES)"), outputText)
        assertTrue(outputText.contains("Supported languages: en (English), zh_CN (简体中文), ja_JP (日本語), de_DE (Deutsch), fr_FR (Français), es_ES (Español)"), outputText)
        assertTrue(outputText.contains("Language -> zh_CN"), outputText)
        assertTrue(outputText.contains("Language -> ja_JP"), outputText)
        assertTrue(outputText.contains("Language -> de_DE"), outputText)
        assertTrue(outputText.contains("Language -> fr_FR"), outputText)
        assertTrue(outputText.contains("Language -> es_ES"), outputText)
        assertTrue(outputText.contains("Language -> en"), outputText)
        assertTrue(outputText.contains("Unsupported language 'ko-KR'"), outputText)
        assertEquals(listOf("zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES", "en"), persisted)
    }

    @Test
    fun themeLocalizedNamesAndProfileLanguageSyncLiveBetweenEnAndZhCn(@TempDir dir: Path) {
        val controller = ThemeRuntimeController(dir)
        assertEquals("en", controller.profile.language)

        I18n.setLocale(controller.profile.language)
        val bodyOriginEn = BodyThemeRegistry.origin().localizedName()
        val uiNordicEn = UiThemeRegistry.resolveOrFallback("nordic-minimal").localizedName()
        assertEquals("GFX100S Original (6-Face)", bodyOriginEn)
        assertEquals("Nordic Minimal", uiNordicEn)

        val updated = controller.updateLanguage(I18n.CODE_ZH_CN)
        assertEquals(I18n.CODE_ZH_CN, updated.language)
        assertEquals(I18n.CODE_ZH_CN, I18n.localeCode())
        assertEquals(I18n.CODE_ZH_CN, UserProfileStore.load(controller.profilePath).language)
        assertNotEquals(bodyOriginEn, BodyThemeRegistry.origin().localizedName())
        assertNotEquals(uiNordicEn, UiThemeRegistry.resolveOrFallback("nordic-minimal").localizedName())

        controller.updateLanguage(I18n.CODE_EN)
        assertEquals(I18n.CODE_EN, UserProfileStore.load(controller.profilePath).language)
        assertEquals("GFX100S Original (6-Face)", BodyThemeRegistry.origin().localizedName())
    }
}
