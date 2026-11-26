package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginMetadataI18nTest {
    @Test
    fun `PluginMetadata resolves all six supported locales aliases and strict English fallback`() {
        val meta = PluginMetadata(
            apiVersion = PluginMetadata.CURRENT_API_VERSION,
            version = "2.0.0",
            author = "Fujifilm GFX100S Emulator",
            description = "Reference medium-format prime",
            descriptionZh = "参考级中画幅定焦镜头",
            names = mapOf(
                "en" to "Reference 80mm Prime",
                "zh_CN" to "参考 80mm 定焦",
                "ja_JP" to "リファレンス 80mm 単焦点",
                "de_DE" to "Referenz-80mm-Festbrennweite",
                "fr_FR" to "Focale fixe de référence 80mm",
                "es_ES" to "Objetivo fijo de referencia 80mm",
            ),
            descriptions = mapOf(
                "en" to "Reference medium-format prime",
                "zh_CN" to "参考级中画幅定焦镜头",
                "ja_JP" to "リファレンス中判単焦点レンズ",
                "de_DE" to "Referenz-Mittelformat-Festbrennweite",
                "fr_FR" to "Focale fixe moyen format de référence",
                "es_ES" to "Objetivo fijo de formato medio de referencia",
            ),
        )

        // Exact 6 locales
        assertEquals("Reference medium-format prime", meta.localizedDescription("en"))
        assertEquals("参考级中画幅定焦镜头", meta.localizedDescription("zh_CN"))
        assertEquals("リファレンス中判単焦点レンズ", meta.localizedDescription("ja_JP"))
        assertEquals("Referenz-Mittelformat-Festbrennweite", meta.localizedDescription("de_DE"))
        assertEquals("Focale fixe moyen format de référence", meta.localizedDescription("fr_FR"))
        assertEquals("Objetivo fijo de formato medio de referencia", meta.localizedDescription("es_ES"))

        // Hyphenated and base-language aliases
        assertEquals("参考级中画幅定焦镜头", meta.localizedDescription("zh-CN"))
        assertEquals("参考级中画幅定焦镜头", meta.localizedDescription("zh"))
        assertEquals("リファレンス中判単焦点レンズ", meta.localizedDescription("ja-JP"))
        assertEquals("リファレンス中判単焦点レンズ", meta.localizedDescription("ja"))
        assertEquals("Referenz-Mittelformat-Festbrennweite", meta.localizedDescription("de-DE"))
        assertEquals("Referenz-Mittelformat-Festbrennweite", meta.localizedDescription("de"))
        assertEquals("Focale fixe moyen format de référence", meta.localizedDescription("fr-FR"))
        assertEquals("Focale fixe moyen format de référence", meta.localizedDescription("fr"))
        assertEquals("Objetivo fijo de formato medio de referencia", meta.localizedDescription("es-ES"))
        assertEquals("Objetivo fijo de formato medio de referencia", meta.localizedDescription("es"))
        assertEquals("Reference medium-format prime", meta.localizedDescription("en-US"))

        // Unsupported locales fall back strictly to English
        assertEquals("Reference medium-format prime", meta.localizedDescription("ko-KR"))
        assertEquals("Reference medium-format prime", meta.localizedDescription("pt-BR"))
        assertEquals("Reference medium-format prime", meta.localizedDescription("ru_RU"))
        assertEquals("Reference medium-format prime", meta.localizedDescription(""))

        // Localized names + unsupported fallback
        assertEquals("Reference 80mm Prime", meta.localizedName("Fallback", "en"))
        assertEquals("参考 80mm 定焦", meta.localizedName("Fallback", "zh_CN"))
        assertEquals("リファレンス 80mm 単焦点", meta.localizedName("Fallback", "ja_JP"))
        assertEquals("Referenz-80mm-Festbrennweite", meta.localizedName("Fallback", "de_DE"))
        assertEquals("Focale fixe de référence 80mm", meta.localizedName("Fallback", "fr_FR"))
        assertEquals("Objetivo fijo de referencia 80mm", meta.localizedName("Fallback", "es_ES"))
        assertEquals("Reference 80mm Prime", meta.localizedName("Fallback", "ko-KR"))

        // State map round-trip preserves full i18n maps
        val roundTripped = PluginMetadata.fromStateMap(meta.toStateMap())
        assertEquals(meta, roundTripped)
        assertEquals("リファレンス中判単焦点レンズ", roundTripped.localizedDescription("ja_JP"))
        assertEquals("Objetivo fijo de formato medio de referencia", roundTripped.localizedDescription("es-ES"))
        assertEquals("Reference medium-format prime", roundTripped.localizedDescription("ko-KR"))
    }

    @Test
    fun `English-only external PluginMetadata falls back cleanly to English under every non-English locale`() {
        val englishOnly = PluginMetadata(
            apiVersion = 2,
            version = "1.0.0",
            author = "ThirdParty Optics",
            description = "External third-party anamorphic cinema lens",
            descriptionZh = "",
        )
        for (locale in listOf("en", "zh_CN", "zh-CN", "ja_JP", "de_DE", "fr_FR", "es_ES", "ko-KR")) {
            assertEquals(
                "External third-party anamorphic cinema lens",
                englishOnly.localizedDescription(locale),
                "Expected strict English fallback for locale=$locale",
            )
            assertEquals(
                "Anamorphic 50mm",
                englishOnly.localizedName("Anamorphic 50mm", locale),
                "Expected fallbackName when names map is empty for locale=$locale",
            )
        }
    }

    @Test
    fun `FilterParamSpec and FilterParamChoice resolve all six locales and English fallback`() {
        val choice = FilterParamChoice(
            value = "ND8",
            labelEn = "ND8 (3 EV)",
            labelZh = "ND8 (3 档)",
            labels = mapOf(
                "en" to "ND8 (3 EV)",
                "zh_CN" to "ND8 (3 档)",
                "ja_JP" to "ND8 (3段)",
                "de_DE" to "ND8 (3 EV)",
                "fr_FR" to "ND8 (3 IL)",
                "es_ES" to "ND8 (3 pasos)",
            ),
        )
        assertEquals("ND8 (3 EV)", choice.localizedLabel("en"))
        assertEquals("ND8 (3 档)", choice.localizedLabel("zh_CN"))
        assertEquals("ND8 (3段)", choice.localizedLabel("ja_JP"))
        assertEquals("ND8 (3 EV)", choice.localizedLabel("de_DE"))
        assertEquals("ND8 (3 IL)", choice.localizedLabel("fr_FR"))
        assertEquals("ND8 (3 pasos)", choice.localizedLabel("es_ES"))
        assertEquals("ND8 (3 EV)", choice.localizedLabel("ko-KR"))

        val spec = FilterParamSpec(
            key = "stops",
            type = FilterParamType.NUMBER,
            labelEn = "Density (Stops)",
            labelZh = "减光档位 (EV)",
            descriptionEn = "Attenuation in photographic stops",
            descriptionZh = "以摄影曝光档位计的减光量",
            defaultValue = 3.0,
            min = 0.0,
            max = 10.0,
            step = 0.5,
            unit = "EV",
            choices = listOf(choice),
            labels = mapOf(
                "en" to "Density (Stops)",
                "zh_CN" to "减光档位 (EV)",
                "ja_JP" to "減光量 (段)",
                "de_DE" to "Dichte (Blenden)",
                "fr_FR" to "Densité (IL)",
                "es_ES" to "Densidad (pasos)",
            ),
            descriptions = mapOf(
                "en" to "Attenuation in photographic stops",
                "zh_CN" to "以摄影曝光档位计的减光量",
                "ja_JP" to "絞り段数での減光量",
                "de_DE" to "Lichtdämpfung in Blendenstufen",
                "fr_FR" to "Atténuation en indices de lumination",
                "es_ES" to "Atenuación en pasos de exposición",
            ),
        )
        assertEquals("Density (Stops)", spec.localizedLabel("en"))
        assertEquals("减光档位 (EV)", spec.localizedLabel("zh-CN"))
        assertEquals("減光量 (段)", spec.localizedLabel("ja_JP"))
        assertEquals("Dichte (Blenden)", spec.localizedLabel("de_DE"))
        assertEquals("Densité (IL)", spec.localizedLabel("fr_FR"))
        assertEquals("Densidad (pasos)", spec.localizedLabel("es_ES"))
        assertEquals("Density (Stops)", spec.localizedLabel("ko-KR"))

        assertEquals("Attenuation in photographic stops", spec.localizedDescription("ko-KR"))
        assertEquals("絞り段数での減光量", spec.localizedDescription("ja"))

        val stateMap = spec.toStateMap()
        assertTrue(stateMap["labels"] is Map<*, *>)
        assertTrue(stateMap["descriptions"] is Map<*, *>)

        // English-only external FilterParamSpec (12-arg secondary constructor)
        val englishOnlySpec = FilterParamSpec(
            "gain",
            FilterParamType.NUMBER,
            "Effect Gain",
            "",
            "Linear effect gain",
            "",
            1.0,
            0.0,
            2.0,
            0.1,
            "",
            emptyList(),
        )
        for (locale in listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES", "ko-KR")) {
            assertEquals("Effect Gain", englishOnlySpec.localizedLabel(locale))
            assertEquals("Linear effect gain", englishOnlySpec.localizedDescription(locale))
        }
    }
}
