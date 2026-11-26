package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.runtime.Formats
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.Window
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Decoupled UI Window Theme descriptor (independent of [BodyTheme]).
 *
 * Every UI theme builds cleanly on top of the base `/panel.css` semantic design-token stylesheet,
 * optionally appending a built-in overlay CSS resource, a bare `./themes/ui/<id>.css` file, or a
 * Theme v2 manifest pack `./themes/ui/<id>/ui-theme.json`.
 */
data class UiTheme(
    val id: String,
    val name: String,
    val nameZh: String,
    val description: String,
    val backdropHex: String,
    val stylesheetUrls: List<String>,
    val builtIn: Boolean,
    val sourceLabel: String,
    val descriptionZh: String = "",
    val names: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val author: String = "Fuji GFX100S Emulator",
    val version: String = "2.0",
    val isDark: Boolean = true,
    val fontFamily: String? = null,
    val recommendedBodyTheme: String? = null,
    val reloadGeneration: Long = 0L,
) {
    private fun builtInI18nNameKey(): String? = when (id) {
        "origin" -> "theme.ui.origin"
        "nordic-minimal" -> "theme.ui.nordic_minimal"
        "daylight-studio" -> "theme.ui.daylight_studio"
        "vintage-brass" -> "theme.ui.vintage_brass"
        "olive-field" -> "theme.ui.olive_field"
        else -> null
    }

    private fun builtInI18nDescKey(): String? = builtInI18nNameKey()?.let { "$it.desc" }

    /**
     * Localized display name supporting all 6 desktop locales (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`)
     * with strict English default fallback for any missing or unsupported locale.
     */
    fun localizedName(localeCode: String = ThemeLocalization.activeLocaleCode()): String {
        if (!builtIn) {
            val resolved = ThemeLocalization.resolve(
                localeCode = localeCode,
                defaultEnglish = name,
                zhFallback = nameZh.takeIf { it != name }.orEmpty(),
                overrides = names,
            ).ifBlank { name }
            // Keep external suffix for bare CSS files that do not provide explicit localized names
            return if (names.isEmpty()) ThemeLocalization.formatExternalSuffix(localeCode, resolved) else resolved
        }
        val merged = ThemeLocalization.mergeLocaleMaps(ThemeLocalization.builtInUiEntry(id).names, names)
        return ThemeLocalization.resolve(
            localeCode = localeCode,
            defaultEnglish = name,
            zhFallback = nameZh.takeIf { it != name }.orEmpty(),
            overrides = merged,
            i18nKey = builtInI18nNameKey(),
        ).ifBlank { name }
    }

    /**
     * Localized theme description supporting all 6 desktop locales with strict English fallback.
     */
    fun localizedDescription(localeCode: String = ThemeLocalization.activeLocaleCode()): String {
        val merged = if (builtIn) {
            ThemeLocalization.mergeLocaleMaps(ThemeLocalization.builtInUiEntry(id).descriptions, descriptions)
        } else {
            descriptions
        }
        return ThemeLocalization.resolve(
            localeCode = localeCode,
            defaultEnglish = description,
            zhFallback = descriptionZh,
            overrides = merged,
            i18nKey = builtInI18nDescKey(),
        ).ifBlank { description }
    }

    fun toSummaryMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "nameZh" to nameZh,
        "localizedName" to localizedName(),
        "description" to description,
        "localizedDescription" to localizedDescription(),
        "author" to author,
        "version" to version,
        "isDark" to isDark,
        "fontFamily" to fontFamily,
        "recommendedBodyTheme" to recommendedBodyTheme,
        "backdropHex" to backdropHex,
        "builtIn" to builtIn,
        "source" to sourceLabel,
    )
}

object UiThemeRegistry {
    private val log = LoggerFactory.getLogger(UiThemeRegistry::class.java)
    const val BASE_STYLESHEET_RESOURCE: String = "/panel.css"
    const val THEME_MANIFEST_FILE: String = "ui-theme.json"
    const val MAX_EXTERNAL_CSS_BYTES: Long = 131_072L
    const val MAX_MANIFEST_BYTES: Long = 65_536L
    private val SAFE_ID = Regex("""[a-z0-9][a-z0-9_-]{0,63}""")
    private val SAFE_HEX = Regex("""#[0-9a-fA-F]{6}""")

    private data class BuiltInUiSpec(
        val id: String,
        val name: String,
        val i18nKey: String,
        val description: String,
        val backdropHex: String,
        val isDark: Boolean,
        val recommendedBodyTheme: String?,
        val overlayResource: String?,
    )

    private val BUILTIN_SPECS: List<BuiltInUiSpec> = listOf(
        BuiltInUiSpec(
            id = "origin",
            name = "Origin Obsidian",
            i18nKey = "theme.ui.origin",
            description = "Classic matte obsidian darkroom palette (#161515) with achromatic neutral controls.",
            backdropHex = "#161515",
            isDark = true,
            recommendedBodyTheme = "origin",
            overlayResource = null,
        ),
        BuiltInUiSpec(
            id = "nordic-minimal",
            name = "Nordic Minimal",
            i18nKey = "theme.ui.nordic_minimal",
            description = "Scandinavian deep slate surfaces with metallic orange (#E85D1A) active highlights.",
            backdropHex = "#13171C",
            isDark = true,
            recommendedBodyTheme = "hasselblad-x2d2",
            overlayResource = "/themes/ui/nordic-minimal.css",
        ),
        BuiltInUiSpec(
            id = "daylight-studio",
            name = "Daylight Studio",
            i18nKey = "theme.ui.daylight_studio",
            description = "Clean bright studio surface (#F3F4F6 / #FFFFFF) with crisp charcoal ink typography.",
            backdropHex = "#F3F4F6",
            isDark = false,
            recommendedBodyTheme = "hasselblad-907x",
            overlayResource = "/themes/ui/daylight-studio.css",
        ),
        BuiltInUiSpec(
            id = "vintage-brass",
            name = "Vintage Brass",
            i18nKey = "theme.ui.vintage_brass",
            description = "Warm charcoal leatherette palette accented with antique brushed brass (#D4A259).",
            backdropHex = "#1A1714",
            isDark = true,
            recommendedBodyTheme = "compact-rangefinder",
            overlayResource = "/themes/ui/vintage-brass.css",
        ),
        BuiltInUiSpec(
            id = "olive-field",
            name = "Olive Field",
            i18nKey = "theme.ui.olive_field",
            description = "Matte field-instrument olive palette with warm sand-gold (#C9B37E) readouts.",
            backdropHex = "#171C17",
            isDark = true,
            recommendedBodyTheme = "origin",
            overlayResource = "/themes/ui/olive-field.css",
        ),
    )

    private val baseUrl: String by lazy {
        requireNotNull(UiThemeRegistry::class.java.getResource(BASE_STYLESHEET_RESOURCE)) {
            "Missing base UI stylesheet: $BASE_STYLESHEET_RESOURCE"
        }.toExternalForm()
    }

    fun origin(): UiTheme = builtInThemes().first()

    fun builtInThemes(): List<UiTheme> = BUILTIN_SPECS.map { spec ->
        val urls = buildList {
            add(baseUrl)
            if (spec.overlayResource != null) {
                val res = requireNotNull(UiThemeRegistry::class.java.getResource(spec.overlayResource)) {
                    "Missing built-in UI theme stylesheet: ${spec.overlayResource}"
                }
                add(res.toExternalForm())
            }
        }
        val i18nEntry = ThemeLocalization.builtInUiEntry(spec.id)
        UiTheme(
            id = spec.id,
            name = spec.name,
            nameZh = I18n.rawPattern(I18n.CODE_ZH_CN, spec.i18nKey)
                ?: i18nEntry.names["zh_CN"]
                ?: spec.name,
            description = i18nEntry.descriptions["en"] ?: spec.description,
            descriptionZh = i18nEntry.descriptions["zh_CN"].orEmpty(),
            names = i18nEntry.names,
            descriptions = i18nEntry.descriptions,
            backdropHex = spec.backdropHex,
            isDark = spec.isDark,
            recommendedBodyTheme = spec.recommendedBodyTheme,
            stylesheetUrls = urls,
            builtIn = true,
            sourceLabel = spec.overlayResource ?: BASE_STYLESHEET_RESOURCE,
        )
    }

    fun discoverAll(home: Path? = null, reloadGeneration: Long = 0L): List<UiTheme> {
        val byId = linkedMapOf<String, UiTheme>()
        builtInThemes().forEach {
            byId[it.id] = if (reloadGeneration == 0L) it else it.copy(reloadGeneration = reloadGeneration)
        }
        val extDir = home?.resolve("themes")?.resolve("ui")
        if (extDir != null && Files.isDirectory(extDir)) {
            runCatching {
                Files.list(extDir).use { stream ->
                    stream.sorted().forEach { entry ->
                        when {
                            Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".css", ignoreCase = true) -> {
                                loadExternalCss(entry, reloadGeneration)?.let { theme ->
                                    byId[theme.id] = theme
                                }
                            }
                            Files.isDirectory(entry) -> {
                                loadExternalManifestPack(entry, reloadGeneration)?.let { theme ->
                                    byId[theme.id] = theme
                                }
                            }
                        }
                    }
                }
            }.onFailure { err ->
                log.warn("[FALLBACK][theme] Failed scanning external UI themes directory {}: {}", extDir, err.message)
            }
        }
        return byId.values.toList()
    }

    private fun readAndValidateCss(cssPath: Path): String? {
        val size = runCatching { Files.size(cssPath) }.getOrElse { -1L }
        if (size <= 0L || size > MAX_EXTERNAL_CSS_BYTES) {
            log.warn(
                "[FALLBACK][theme] Ignoring external UI theme '{}' (size={} bytes outside 1..{})",
                cssPath,
                size,
                MAX_EXTERNAL_CSS_BYTES,
            )
            return null
        }
        val text = runCatching { Files.readString(cssPath) }.getOrElse { err ->
            log.warn("[FALLBACK][theme] Failed reading external UI theme '{}': {}", cssPath, err.message)
            return null
        }
        if (text.contains("@import", ignoreCase = true)) {
            log.warn("[FALLBACK][theme] Ignoring external UI theme '{}' containing disallowed @import directive", cssPath)
            return null
        }
        return text
    }

    private fun loadExternalCss(cssPath: Path, reloadGeneration: Long): UiTheme? {
        val fileName = cssPath.fileName.toString()
        val id = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        if (!SAFE_ID.matches(id)) {
            log.warn("[FALLBACK][theme] Ignoring external UI theme '{}' with invalid id '{}'", cssPath, id)
            return null
        }
        val text = readAndValidateCss(cssPath) ?: return null
        val backdrop = Regex("""-fuji-(?:backdrop|bg-base)\s*:\s*(#[0-9a-fA-F]{6})""").find(text)?.groupValues?.get(1) ?: "#161515"
        val displayName = id.split('-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return UiTheme(
            id = id,
            name = displayName,
            nameZh = displayName,
            description = "External user UI theme loaded from ${cssPath.fileName}",
            backdropHex = backdrop,
            stylesheetUrls = listOf(baseUrl, cssPath.toAbsolutePath().normalize().toUri().toString()),
            builtIn = false,
            sourceLabel = cssPath.toAbsolutePath().normalize().toString(),
            reloadGeneration = reloadGeneration,
        )
    }

    private fun loadExternalManifestPack(packDir: Path, reloadGeneration: Long): UiTheme? {
        val dirId = packDir.fileName.toString().lowercase(Locale.ROOT)
        if (!SAFE_ID.matches(dirId)) {
            log.warn("[FALLBACK][theme] Ignoring external UI theme directory '{}' with invalid id '{}'", packDir, dirId)
            return null
        }
        val manifestPath = packDir.resolve(THEME_MANIFEST_FILE)
        if (!Files.isRegularFile(manifestPath)) {
            log.warn("[FALLBACK][theme] Ignoring external UI theme directory '{}' missing {}", packDir, THEME_MANIFEST_FILE)
            return null
        }
        return runCatching {
            val size = Files.size(manifestPath)
            require(size in 1..MAX_MANIFEST_BYTES) { "ui-theme.json size $size outside 1..$MAX_MANIFEST_BYTES" }
            val tree = Formats.json.readTree(Files.readAllBytes(manifestPath))
            val schema = tree.path("schema").asInt(2)
            require(schema == 1 || schema == 2) { "unsupported ui-theme schema: $schema" }
            val id = tree.path("id").asText(dirId).trim().lowercase(Locale.ROOT)
            require(SAFE_ID.matches(id) && id == dirId) { "ui theme id '$id' does not match folder '$dirId'" }
            val name = tree.path("name").asText("").trim().also { require(it.isNotEmpty() && it.length <= 120) { "missing 'name'" } }
            val nameZh = tree.path("nameZh").asText("").trim().ifEmpty { name }
            val description = tree.path("description").asText("").trim().ifEmpty { "External UI theme '$name'" }
            val descriptionZh = tree.path("descriptionZh").asText("").trim()
            val i18nNode = tree.path("i18n")
            val namesMap = ThemeLocalization.mergeLocaleMaps(
                ThemeLocalization.parseLocaleMap(i18nNode.path("names")),
                ThemeLocalization.parseLocaleMap(tree.path("names")),
            )
            val descriptionsMap = ThemeLocalization.mergeLocaleMaps(
                ThemeLocalization.parseLocaleMap(i18nNode.path("descriptions")),
                ThemeLocalization.parseLocaleMap(tree.path("descriptions")),
            )
            val cssRel = tree.path("css").asText("theme.css").trim()
            require(cssRel.isNotEmpty() && !cssRel.startsWith("/") && !cssRel.contains("\\") && !cssRel.contains("..") && cssRel.endsWith(".css", ignoreCase = true)) {
                "unsafe css path '$cssRel'"
            }
            val realPack = packDir.toRealPath()
            val cssPath = realPack.resolve(cssRel).normalize()
            require(cssPath.startsWith(realPack) && Files.isRegularFile(cssPath)) {
                "missing or out-of-bounds css file '$cssRel'"
            }
            val realCss = cssPath.toRealPath()
            require(realCss.startsWith(realPack)) { "symlink escape rejected for css '$cssRel'" }
            val cssText = readAndValidateCss(realCss) ?: error("rejected CSS file '$cssRel'")
            val backdropRaw = tree.path("backdropHex").asText("").trim()
            val backdrop = when {
                SAFE_HEX.matches(backdropRaw) -> backdropRaw
                else -> Regex("""-fuji-(?:backdrop|bg-base)\s*:\s*(#[0-9a-fA-F]{6})""").find(cssText)?.groupValues?.get(1) ?: "#161515"
            }
            val author = tree.path("author").asText("User").trim().take(80).ifEmpty { "User" }
            val version = tree.path("version").asText("1.0").trim().take(32).ifEmpty { "1.0" }
            val isDark = if (tree.has("isDark") && tree.path("isDark").isBoolean) tree.path("isDark").asBoolean() else true
            val fontFamily = tree.path("fontFamily").asText("").trim().take(80).ifEmpty { null }
            val recommendedBody = tree.path("recommendedBodyTheme").asText("").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.also {
                require(SAFE_ID.matches(it)) { "invalid recommendedBodyTheme '$it'" }
            }
            UiTheme(
                id = id,
                name = name,
                nameZh = nameZh,
                description = description,
                descriptionZh = descriptionZh,
                names = namesMap,
                descriptions = descriptionsMap,
                author = author,
                version = version,
                isDark = isDark,
                fontFamily = fontFamily,
                recommendedBodyTheme = recommendedBody,
                backdropHex = backdrop,
                stylesheetUrls = listOf(baseUrl, realCss.toUri().toString()),
                builtIn = false,
                sourceLabel = manifestPath.toAbsolutePath().normalize().toString(),
                reloadGeneration = reloadGeneration,
            )
        }.onFailure { err ->
            log.warn("[FALLBACK][theme] Invalid external UI theme pack '{}': {}; ignoring pack", packDir, err.message)
        }.getOrNull()
    }

    /** Resolves [requestedId] or falls back to `origin` with SLF4J WARN `[FALLBACK][theme]`. */
    fun resolveOrFallback(requestedId: String?, home: Path? = null, reloadGeneration: Long = 0L): UiTheme {
        val normalized = requestedId?.trim()?.lowercase(Locale.ROOT).orEmpty().ifEmpty { UserProfile.DEFAULT_UI_THEME }
        val all = discoverAll(home, reloadGeneration)
        val match = all.firstOrNull { it.id == normalized }
        if (match != null) return match
        log.warn(
            "[FALLBACK][theme] Requested UI theme '{}' not found (available: {}); falling back to 'origin'",
            requestedId,
            all.map { it.id },
        )
        return origin().let { if (reloadGeneration == 0L) it else it.copy(reloadGeneration = reloadGeneration) }
    }

    /** Strict lookup for explicit user commands (CLI / HTTP API); returns null when unknown. */
    fun findExact(requestedId: String, home: Path? = null, reloadGeneration: Long = 0L): UiTheme? {
        val normalized = requestedId.trim().lowercase(Locale.ROOT)
        return discoverAll(home, reloadGeneration).firstOrNull { it.id == normalized }
    }

    /** Applies [theme] cleanly to a single JavaFX [scene] (stylesheets + backdrop fill, forcing refresh on reload). */
    fun applyToScene(scene: Scene, theme: UiTheme) {
        scene.stylesheets.clear()
        scene.stylesheets.setAll(theme.stylesheetUrls)
        runCatching { Color.web(theme.backdropHex) }.onSuccess { scene.fill = it }
        scene.root?.applyCss()
    }

    /** Hot-swaps [theme] across every currently created JavaFX [Stage] in the application. */
    fun applyToAllOpenStages(theme: UiTheme) {
        Window.getWindows().filterIsInstance<Stage>().forEach { stage ->
            stage.scene?.let { applyToScene(it, theme) }
        }
    }
}
