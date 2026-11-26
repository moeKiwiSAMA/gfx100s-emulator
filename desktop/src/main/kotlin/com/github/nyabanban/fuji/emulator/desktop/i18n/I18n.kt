package com.github.nyabanban.fuji.emulator.desktop.i18n

import javafx.application.Platform
import javafx.beans.Observable
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Central desktop internationalization (i18n) engine backed by UTF-8 `.properties` resource bundles
 * for 6 built-in languages (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`).
 *
 * - Default locale is English ([ENGLISH] / `"en"`).
 * - Exposes a reactive JavaFX [localeProperty] plus [bind] / [bindDynamic] helpers so live language
 *   changes immediately refresh every bound control without restarting the emulator.
 * - Exposes [onLocaleChanged] so profile persistence (`profile.yaml`) can auto-save language changes
 *   triggered from the UI, `/lang` console command, or `POST /api/lang`.
 * - Any unsupported locale or missing translation key logs an explicit SLF4J `WARN`
 *   (`[FALLBACK][i18n] ...`) before falling back to English (or the raw key if absent in English).
 */
object I18n {
    private val log = LoggerFactory.getLogger(I18n::class.java)

    val ENGLISH: Locale = Locale.ENGLISH
    val SIMPLIFIED_CHINESE: Locale = Locale.SIMPLIFIED_CHINESE
    val JAPANESE: Locale = Locale.JAPAN
    val GERMAN: Locale = Locale.GERMANY
    val FRENCH: Locale = Locale.FRANCE
    val SPANISH: Locale = Locale.forLanguageTag("es-ES")

    val SUPPORTED_LOCALES: List<Locale> = listOf(
        ENGLISH,
        SIMPLIFIED_CHINESE,
        JAPANESE,
        GERMAN,
        FRENCH,
        SPANISH,
    )

    const val CODE_EN: String = "en"
    const val CODE_ZH_CN: String = "zh_CN"
    const val CODE_JA_JP: String = "ja_JP"
    const val CODE_DE_DE: String = "de_DE"
    const val CODE_FR_FR: String = "fr_FR"
    const val CODE_ES_ES: String = "es_ES"

    private val nativeNames: Map<String, String> = linkedMapOf(
        CODE_EN to "English",
        CODE_ZH_CN to "\u7B80\u4F53\u4E2D\u6587",
        CODE_JA_JP to "\u65E5\u672C\u8A9E",
        CODE_DE_DE to "Deutsch",
        CODE_FR_FR to "Fran\u00E7ais",
        CODE_ES_ES to "Espa\u00F1ol",
    )

    private val bundles: Map<String, Map<String, String>> = linkedMapOf(
        CODE_EN to loadBundle("/i18n/messages_en.properties"),
        CODE_ZH_CN to loadBundle("/i18n/messages_zh_CN.properties"),
        CODE_JA_JP to loadBundle("/i18n/messages_ja_JP.properties"),
        CODE_DE_DE to loadBundle("/i18n/messages_de_DE.properties"),
        CODE_FR_FR to loadBundle("/i18n/messages_fr_FR.properties"),
        CODE_ES_ES to loadBundle("/i18n/messages_es_ES.properties"),
    )

    @Volatile
    private var activeLocale: Locale = ENGLISH

    /**
     * Optional persistence hook invoked whenever [setLocale] updates the active language.
     * Receives the canonical code (`"en"`, `"zh_CN"`, `"ja_JP"`, `"de_DE"`, `"fr_FR"`, or `"es_ES"`).
     */
    @Volatile
    var onLocaleChanged: ((String) -> Unit)? = null

    val localeProperty: ObjectProperty<Locale> = SimpleObjectProperty(ENGLISH)

    var currentLocale: Locale
        get() = activeLocale
        set(value) {
            setLocale(value)
        }

    fun supportedLanguageCodes(): List<String> = listOf(
        CODE_EN,
        CODE_ZH_CN,
        CODE_JA_JP,
        CODE_DE_DE,
        CODE_FR_FR,
        CODE_ES_ES,
    )

    fun nativeDisplayName(code: String): String {
        val canonical = resolveSupportedLocaleOrNull(code)?.let(::localeCode) ?: CODE_EN
        return nativeNames[canonical] ?: "English"
    }

    fun isSupportedCode(code: String?): Boolean = resolveSupportedLocaleOrNull(code) != null

    fun resetToDefault() {
        activeLocale = ENGLISH
        updateLocaleProperty(ENGLISH)
    }

    fun localeCode(locale: Locale = currentLocale): String = when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> CODE_ZH_CN
        "ja" -> CODE_JA_JP
        "de" -> CODE_DE_DE
        "fr" -> CODE_FR_FR
        "es" -> CODE_ES_ES
        else -> CODE_EN
    }

    private fun resolveSupportedLocaleOrNull(code: String?): Locale? {
        val raw = code?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val norm = raw.lowercase(Locale.ROOT).replace('-', '_')
        return when {
            norm == "en" || norm.startsWith("en_") || norm == "english" -> ENGLISH
            norm == "zh" || norm == "zh_cn" || norm == "zh_hans" || norm.startsWith("zh_hans_") ||
                norm == "chinese" || norm == "\u7B80\u4F53\u4E2D\u6587" || norm == "\u4E2D\u6587" ->
                SIMPLIFIED_CHINESE
            norm == "ja" || norm == "jp" || norm == "ja_jp" || norm.startsWith("ja_") ||
                norm == "japanese" || norm == "\u65E5\u672C\u8A9E" ->
                JAPANESE
            norm == "de" || norm == "de_de" || norm.startsWith("de_") ||
                norm == "german" || norm == "deutsch" ->
                GERMAN
            norm == "fr" || norm == "fr_fr" || norm.startsWith("fr_") ||
                norm == "french" || norm == "francais" || norm == "fran\u00E7ais" ->
                FRENCH
            norm == "es" || norm == "es_es" || norm.startsWith("es_") ||
                norm == "spanish" || norm == "espanol" || norm == "espa\u00F1ol" ->
                SPANISH
            else -> null
        }
    }

    fun normalizeLocale(code: String?): Locale {
        val raw = code?.trim().orEmpty()
        if (raw.isEmpty()) {
            log.warn("[FALLBACK][i18n] Blank locale code — falling back to '{}'", CODE_EN)
            return ENGLISH
        }
        resolveSupportedLocaleOrNull(raw)?.let { return it }
        log.warn("[FALLBACK][i18n] Unsupported locale '{}' — falling back to '{}'", raw, CODE_EN)
        return ENGLISH
    }

    /**
     * Picks the best localized string from [translations] for [localeCode], falling back through:
     * exact canonical code (`"ja_JP"`) -> hyphen variant (`"ja-JP"`) -> 2-letter language prefix
     * (`"ja"`) -> English (`"en"`) -> [englishFallback].
     */
    fun pickLocalized(
        translations: Map<String, String>,
        englishFallback: String,
        localeCode: String = localeCode(),
    ): String {
        if (translations.isEmpty()) return englishFallback
        val trimmed = localeCode.trim()
        val canonical = resolveSupportedLocaleOrNull(trimmed)?.let(::localeCode)
            ?: trimmed.replace('-', '_')
        val candidates = linkedSetOf<String>()
        if (canonical.isNotEmpty()) {
            candidates += canonical
            candidates += canonical.replace('_', '-')
            val lower = canonical.lowercase(Locale.ROOT)
            candidates += lower
            candidates += lower.replace('_', '-')
            val baseLang = lower.substringBefore('_')
            if (baseLang.isNotEmpty()) {
                candidates += baseLang
            }
        }
        candidates += CODE_EN
        candidates += "en-US"
        candidates += "en_US"
        for (candidate in candidates) {
            val direct = translations[candidate]
            if (!direct.isNullOrBlank()) return direct
        }
        val ciLookup = translations.entries.associate { (k, v) -> k.lowercase(Locale.ROOT).replace('-', '_') to v }
        for (candidate in candidates) {
            val key = candidate.lowercase(Locale.ROOT).replace('-', '_')
            val matched = ciLookup[key]
            if (!matched.isNullOrBlank()) return matched
        }
        return englishFallback
    }

    fun setLocale(code: String): Locale {
        val resolved = normalizeLocale(code)
        applyResolvedLocale(resolved)
        return resolved
    }

    fun setLocale(locale: Locale): Locale {
        val tag = locale.toLanguageTag().ifBlank { locale.toString() }
        val resolved = normalizeLocale(tag)
        applyResolvedLocale(resolved)
        return resolved
    }

    private fun applyResolvedLocale(resolved: Locale) {
        activeLocale = resolved
        updateLocaleProperty(resolved)
        val canonical = localeCode(resolved)
        onLocaleChanged?.invoke(canonical)
    }

    private fun updateLocaleProperty(resolved: Locale) {
        try {
            if (Platform.isFxApplicationThread()) {
                localeProperty.set(resolved)
                localeProperty.get()
            } else {
                val latch = CountDownLatch(1)
                Platform.runLater {
                    try {
                        localeProperty.set(resolved)
                        localeProperty.get()
                    } finally {
                        latch.countDown()
                    }
                }
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    localeProperty.set(resolved)
                    localeProperty.get()
                }
            }
        } catch (_: IllegalStateException) {
            // Headless / unit-test mode where JavaFX toolkit is not initialized.
            localeProperty.set(resolved)
            localeProperty.get()
        }
    }

    fun t(key: String, vararg args: Any?): String {
        val active = currentLocale
        val activeCode = localeCode(active)
        val activeMap = bundles[activeCode]
        val enMap = bundles[CODE_EN].orEmpty()

        val pattern = when {
            activeMap != null && activeMap.containsKey(key) -> activeMap.getValue(key)
            activeCode != CODE_EN && enMap.containsKey(key) -> {
                log.warn(
                    "[FALLBACK][i18n] Missing translation key '{}' for locale '{}' — falling back to '{}'",
                    key,
                    activeCode,
                    CODE_EN,
                )
                enMap.getValue(key)
            }
            else -> {
                log.warn(
                    "[FALLBACK][i18n] Missing translation key '{}' for locale '{}' — returning raw key",
                    key,
                    activeCode,
                )
                return key
            }
        }

        if (args.isEmpty()) return pattern
        return try {
            String.format(active, pattern, *args)
        } catch (err: Exception) {
            log.warn(
                "[FALLBACK][i18n] Format failure for key '{}' (locale='{}'): {} — returning unformatted pattern",
                key,
                activeCode,
                err.message ?: err.javaClass.simpleName,
            )
            pattern
        }
    }

    fun bind(key: String, vararg args: Any?): StringBinding =
        Bindings.createStringBinding({
            // Reading localeProperty.get() keeps JavaFX's ObjectPropertyBase valid for subsequent invalidations.
            localeProperty.get()
            t(key, *args)
        }, localeProperty)

    fun bindDynamic(vararg dependencies: Observable, supplier: () -> String): StringBinding =
        Bindings.createStringBinding({
            localeProperty.get()
            supplier()
        }, localeProperty, *dependencies)

    fun onLocaleChange(listener: (Locale) -> Unit): ChangeListener<Locale> {
        val wrapped = ChangeListener<Locale> { _, _, newLocale ->
            listener(newLocale ?: ENGLISH)
        }
        localeProperty.addListener(wrapped)
        return wrapped
    }

    internal fun bundleKeys(code: String): Set<String> =
        bundles[code]?.keys?.toSet().orEmpty()

    internal fun rawPattern(code: String, key: String): String? =
        bundles[code]?.get(key)

    private fun loadBundle(resourcePath: String): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream(resourcePath)
            ?: error("Missing required i18n resource bundle: $resourcePath")
        val props = Properties()
        stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                props.load(reader)
            }
        }
        val out = LinkedHashMap<String, String>(props.size)
        for (name in props.stringPropertyNames().sorted()) {
            out[name] = props.getProperty(name)
        }
        return out
    }
}
