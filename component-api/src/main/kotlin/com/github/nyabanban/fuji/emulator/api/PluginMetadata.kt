package com.github.nyabanban.fuji.emulator.api

import java.util.Locale

/**
 * Self-describing metadata for modular lens, mount adapter, and optical filter plugins (SPI v2).
 *
 * Supports multi-locale names and descriptions with strict English default fallback:
 * exact locale (`zh_CN`/`zh-CN`) -> base language (`zh` / [descriptionZh]) -> `en` ->
 * [description] / fallback name.
 *
 * All fields have safe defaults and a 5-argument JVM compatibility constructor so SPI v1 and
 * early SPI v2 plugins remain 100% source and binary compatible.
 */
data class PluginMetadata(
    val apiVersion: Int = CURRENT_API_VERSION,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val descriptionZh: String = "",
    val names: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
) {
    /** Binary-compatible 5-arg constructor for Java/Kotlin SPI v2 plugins omitting locale maps. */
    constructor(
        apiVersion: Int,
        version: String,
        author: String,
        description: String,
        descriptionZh: String,
    ) : this(
        apiVersion = apiVersion,
        version = version,
        author = author,
        description = description,
        descriptionZh = descriptionZh,
        names = emptyMap(),
        descriptions = emptyMap(),
    )

    /**
     * Resolves a localized description for [localeCode] with strict English default fallback:
     * exact locale (`zh_CN`/`zh-CN`) -> base language (`zh`/`ja`/`de`/`fr`/`es` or [descriptionZh]) -> `en` -> [description].
     * Unsupported locales (e.g. `ko-KR`, `pt-BR`) always fall back cleanly to English.
     */
    fun localizedDescription(localeCode: String = "en"): String {
        resolveFromLocaleMap(descriptions, localeCode)?.let { return it }
        if (isChineseLocale(localeCode) && descriptionZh.isNotBlank()) return descriptionZh
        resolveFromLocaleMap(descriptions, "en")?.let { return it }
        if (description.isNotBlank()) return description
        if (descriptionZh.isNotBlank()) return descriptionZh
        return descriptions.values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /**
     * Resolves a localized display name for [localeCode] with strict English / [fallbackName] fallback.
     */
    fun localizedName(fallbackName: String, localeCode: String = "en"): String {
        resolveFromLocaleMap(names, localeCode)?.let { return it }
        resolveFromLocaleMap(names, "en")?.let { return it }
        return fallbackName
    }

    fun toStateMap(): Map<String, Any> = linkedMapOf<String, Any>(
        "apiVersion" to apiVersion,
        "version" to version,
        "author" to author,
        "description" to description,
        "descriptionZh" to descriptionZh,
    ).apply {
        if (names.isNotEmpty()) put("names", names)
        if (descriptions.isNotEmpty()) put("descriptions", descriptions)
    }

    companion object {
        const val CURRENT_API_VERSION: Int = 2
        val DEFAULT = PluginMetadata()

        fun fromStateMap(map: Map<*, *>?): PluginMetadata {
            if (map == null) return DEFAULT
            fun parseStringMap(raw: Any?): Map<String, String> {
                val m = raw as? Map<*, *> ?: return emptyMap()
                val out = LinkedHashMap<String, String>()
                for ((k, v) in m) {
                    val ks = (k as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val vs = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    out[ks] = vs
                }
                return out
            }
            return PluginMetadata(
                apiVersion = (map["apiVersion"] as? Number)?.toInt() ?: CURRENT_API_VERSION,
                version = (map["version"] as? String)?.takeIf { it.isNotBlank() } ?: "1.0.0",
                author = (map["author"] as? String).orEmpty(),
                description = (map["description"] as? String).orEmpty(),
                descriptionZh = (map["descriptionZh"] as? String).orEmpty(),
                names = parseStringMap(map["names"]),
                descriptions = parseStringMap(map["descriptions"]),
            )
        }

        internal fun normalizeLocale(localeCode: String): String =
            localeCode.trim().replace('-', '_')

        internal fun isChineseLocale(localeCode: String): Boolean {
            val norm = normalizeLocale(localeCode).lowercase(Locale.ROOT)
            return norm == "zh" || norm.startsWith("zh_")
        }

        internal fun resolveFromLocaleMap(map: Map<String, String>, localeCode: String): String? {
            if (map.isEmpty()) return null
            val norm = normalizeLocale(localeCode)
            if (norm.isEmpty()) return null
            // 1. Exact locale match (e.g. zh_CN == zh-CN, ja_JP == ja_JP)
            map.entries.firstOrNull { normalizeLocale(it.key).equals(norm, ignoreCase = true) && it.value.isNotBlank() }
                ?.let { return it.value }
            val lang = norm.substringBefore('_')
            if (lang.isNotEmpty()) {
                // 2. Exact base-language key match (e.g. en-US -> en, zh_CN -> zh)
                map.entries.firstOrNull { normalizeLocale(it.key).equals(lang, ignoreCase = true) && it.value.isNotBlank() }
                    ?.let { return it.value }
                // 3. Same base-language regional key match (e.g. ja -> ja_JP, fr -> fr_FR, de -> de_DE)
                map.entries.firstOrNull {
                    normalizeLocale(it.key).substringBefore('_').equals(lang, ignoreCase = true) && it.value.isNotBlank()
                }?.let { return it.value }
            }
            return null
        }
    }
}
