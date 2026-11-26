package com.github.nyabanban.fuji.emulator.desktop.theme

import com.fasterxml.jackson.databind.JsonNode
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.util.Locale

/**
 * Shared 6-language (EN / zh_CN / ja_JP / de_DE / fr_FR / es_ES) metadata localization resolver
 * for [BodyTheme], [UiTheme], [com.github.nyabanban.fuji.emulator.desktop.panels.CameraPanel],
 * and [com.github.nyabanban.fuji.emulator.desktop.panels.PanelControl].
 *
 * Strictly falls back to English for any missing or unsupported locale (e.g. `ko-KR`, `pt-BR`),
 * and automatically follows `I18n.currentLocale` / `I18n.localeCode()` as additional desktop
 * locales are enabled.
 */
object ThemeLocalization {
    private const val BUILTIN_I18N_RESOURCE = "/themes/theme-i18n.json"

    data class BuiltInLocalizedEntry(
        val names: Map<String, String> = emptyMap(),
        val descriptions: Map<String, String> = emptyMap(),
    )

    private data class BuiltInCatalogData(
        val sections: Map<String, Map<String, BuiltInLocalizedEntry>>,
        val externalSuffix: Map<String, String>,
    )

    private val builtInCatalog: BuiltInCatalogData by lazy {
        loadBuiltInCatalog()
    }

    private fun loadBuiltInCatalog(): BuiltInCatalogData {
        val stream = ThemeLocalization::class.java.getResourceAsStream(BUILTIN_I18N_RESOURCE)
            ?: return BuiltInCatalogData(emptyMap(), emptyMap())
        val tree = stream.use { Formats.json.readTree(it) }
        val result = linkedMapOf<String, Map<String, BuiltInLocalizedEntry>>()
        listOf("body", "ui").forEach { section ->
            val sectionNode = tree.path(section)
            if (sectionNode.isObject) {
                val entries = linkedMapOf<String, BuiltInLocalizedEntry>()
                sectionNode.properties().forEach { (id, entryNode) ->
                    entries[id] = BuiltInLocalizedEntry(
                        names = parseLocaleMap(entryNode.path("names")),
                        descriptions = parseLocaleMap(entryNode.path("descriptions")),
                    )
                }
                result[section] = entries
            }
        }
        return BuiltInCatalogData(
            sections = result,
            externalSuffix = parseLocaleMap(tree.path("externalSuffix")),
        )
    }

    fun builtInBodyEntry(id: String): BuiltInLocalizedEntry =
        builtInCatalog.sections["body"]?.get(id) ?: BuiltInLocalizedEntry()

    fun builtInUiEntry(id: String): BuiltInLocalizedEntry =
        builtInCatalog.sections["ui"]?.get(id) ?: BuiltInLocalizedEntry()

    /**
     * Resolves the currently active locale tag from [I18n], preferring the full language tag
     * (e.g. `ja-JP`, `de-DE`, `fr-FR`, `es-ES`, `zh-CN`, `en`) so multi-language themes
     * automatically pick up all 6 desktop locales without touching `I18n.kt`.
     */
    fun activeLocaleCode(): String {
        val code = I18n.localeCode()
        val tag = I18n.currentLocale.toLanguageTag().orEmpty().trim()
        if (tag.isNotBlank() && !tag.equals("und", ignoreCase = true)) {
            val normTag = tag.replace('-', '_')
            if (normTag.lowercase(Locale.ROOT) != "en" && code == I18n.CODE_EN) {
                return normTag
            }
        }
        return code
    }

    fun normalizeLocaleKey(code: String): String =
        code.trim().lowercase(Locale.ROOT).replace('-', '_')

    fun languagePrefix(code: String): String =
        normalizeLocaleKey(code).substringBefore('_')

    fun parseLocaleMap(node: JsonNode?): Map<String, String> {
        if (node == null || !node.isObject) return emptyMap()
        val out = linkedMapOf<String, String>()
        node.properties().forEach { (k, v) ->
            if (k.isNotBlank() && v.isTextual && v.asText().isNotBlank()) {
                out[k.trim()] = v.asText().trim()
            }
        }
        return out
    }

    fun mergeLocaleMaps(base: Map<String, String>, overlay: Map<String, String>): Map<String, String> {
        if (base.isEmpty()) return overlay
        if (overlay.isEmpty()) return base
        val merged = LinkedHashMap<String, String>(base.size + overlay.size)
        merged.putAll(base)
        merged.putAll(overlay)
        return merged
    }

    /**
     * Strict localization lookup chain:
     * 1. Exact locale match in [overrides] (case-insensitive, `-` / `_` normalized)
     * 2. Base 2-letter language match in [overrides] (e.g. `"zh"`, `"ja"`, `"de"`, `"fr"`, `"es"`)
     * 3. [zhFallback] when requested language is `"zh"`
     * 4. Built-in `I18n` bundle entry for [i18nKey] (for `"zh"` / `"en"`, or active locale when matching)
     * 5. English entry in [overrides] (`"en"` / `"en_us"`)
     * 6. [defaultEnglish] (or English `I18n` bundle entry for [i18nKey])
     */
    fun resolve(
        localeCode: String = activeLocaleCode(),
        defaultEnglish: String,
        zhFallback: String = "",
        overrides: Map<String, String> = emptyMap(),
        i18nKey: String? = null,
    ): String {
        val norm = normalizeLocaleKey(localeCode)
        val lang = norm.substringBefore('_')
        val normalizedOverrides = if (overrides.isNotEmpty()) {
            LinkedHashMap<String, String>(overrides.size).also { map ->
                overrides.forEach { (k, v) ->
                    val nk = normalizeLocaleKey(k)
                    if (nk.isNotEmpty() && v.isNotBlank()) map[nk] = v.trim()
                }
            }
        } else {
            emptyMap()
        }

        // 1. Exact locale match in overrides
        normalizedOverrides[norm]?.let { return it }
        // 2. Language prefix match in overrides (e.g. zh_cn -> zh, fr_fr -> fr)
        if (lang.isNotEmpty()) {
            normalizedOverrides[lang]?.let { return it }
            // Also allow matching e.g. requested "ja" against map key "ja_jp"
            normalizedOverrides.entries.firstOrNull { it.key.substringBefore('_') == lang }?.value?.let { return it }
        }
        // 3. Legacy zhFallback for Chinese locales
        if (lang == "zh" && zhFallback.isNotBlank()) return zhFallback.trim()
        // 4. Built-in I18n bundle key lookup
        if (i18nKey != null) {
            if (lang == "zh") {
                I18n.rawPattern(I18n.CODE_ZH_CN, i18nKey)?.takeIf { it.isNotBlank() }?.let { return it }
            } else if (lang == "en") {
                I18n.rawPattern(I18n.CODE_EN, i18nKey)?.takeIf { it.isNotBlank() }?.let { return it }
            } else if (normalizeLocaleKey(activeLocaleCode()) == norm) {
                val activeVal = I18n.t(i18nKey)
                if (activeVal != i18nKey && activeVal.isNotBlank()) return activeVal
            }
        }
        // 5. English entry in overrides
        normalizedOverrides["en"]?.let { return it }
        normalizedOverrides["en_us"]?.let { return it }
        // 6. Default English fallback
        if (defaultEnglish.isNotBlank()) return defaultEnglish.trim()
        if (i18nKey != null) {
            I18n.rawPattern(I18n.CODE_EN, i18nKey)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return zhFallback.trim()
    }

    fun formatExternalSuffix(localeCode: String, baseName: String): String {
        val pattern = resolve(
            localeCode = localeCode,
            defaultEnglish = "%s (External)",
            overrides = builtInCatalog.externalSuffix,
            i18nKey = "theme.external.suffix",
        )
        return runCatching { String.format(Locale.ROOT, pattern, baseName) }.getOrDefault("$baseName (External)")
    }
}
