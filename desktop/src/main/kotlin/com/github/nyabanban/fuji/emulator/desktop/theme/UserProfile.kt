package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Global photographer studio profile (`<run-dir>/profile.yaml`).
 *
 * Strictly stores application/shell preferences ONLY (`language`, `theme.body`, `theme.ui`,
 * `windows.fitScale`, `windows.evfWindowOpen`, `windows.evfAlwaysOnTop`). All hardware, lens,
 * optical filter, and scene configurations remain strictly in `configs/components/`.
 */
data class UserProfile(
    val schema: Int = SCHEMA_VERSION,
    val language: String = DEFAULT_LANGUAGE,
    val bodyTheme: String = DEFAULT_BODY_THEME,
    val uiTheme: String = DEFAULT_UI_THEME,
    val fitScale: Double = DEFAULT_FIT_SCALE,
    val evfWindowOpen: Boolean = DEFAULT_EVF_WINDOW_OPEN,
    val evfAlwaysOnTop: Boolean = DEFAULT_EVF_ALWAYS_ON_TOP,
    val opticsBackend: String = DEFAULT_OPTICS_BACKEND,
    val vulkanDevice: String = DEFAULT_VULKAN_DEVICE,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DEFAULT_FILE_NAME: String = "profile.yaml"
        const val DEFAULT_LANGUAGE: String = "en"
        const val DEFAULT_BODY_THEME: String = "origin"
        const val DEFAULT_UI_THEME: String = "origin"
        const val DEFAULT_FIT_SCALE: Double = 1.0
        const val MIN_FIT_SCALE: Double = 0.5
        const val MAX_FIT_SCALE: Double = 1.0
        const val DEFAULT_EVF_WINDOW_OPEN: Boolean = false
        const val DEFAULT_EVF_ALWAYS_ON_TOP: Boolean = false
        const val DEFAULT_OPTICS_BACKEND: String = "auto"
        const val DEFAULT_VULKAN_DEVICE: String = "auto"
        val SUPPORTED_OPTICS_BACKENDS: Set<String> = setOf("auto", "vulkan", "cpu")
        val SUPPORTED_LANGUAGES: Set<String> = setOf("en", "zh", "zh_CN", "ja", "ja_JP", "de", "de_DE", "fr", "fr_FR", "es", "es_ES")
        val THEME_ID_PATTERN: Regex = Regex("""[a-z0-9][a-z0-9_-]{0,63}""")
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema" to schema,
        "language" to language,
        "theme" to linkedMapOf(
            "body" to bodyTheme,
            "ui" to uiTheme,
        ),
        "windows" to linkedMapOf(
            "fitScale" to fitScale,
            "evfWindowOpen" to evfWindowOpen,
            "evfAlwaysOnTop" to evfAlwaysOnTop,
        ),
        "graphics" to linkedMapOf(
            "opticsBackend" to opticsBackend,
            "vulkanDevice" to vulkanDevice,
        ),
    )
}

object UserProfileStore {
    private val log = LoggerFactory.getLogger(UserProfileStore::class.java)

    fun resolveDefaultPath(home: Path): Path = home.resolve(UserProfile.DEFAULT_FILE_NAME)

    fun defaultPath(home: Path): Path = resolveDefaultPath(home)

    /**
     * Loads [path] if present; otherwise auto-creates a clean commented default `profile.yaml`.
     * Any invalid or unrecognized values fall back cleanly to defaults with SLF4J WARN `[FALLBACK][profile]`.
     */
    fun loadOrCreate(path: Path, autoCreate: Boolean = true): UserProfile {
        if (!Files.exists(path)) {
            val defaults = UserProfile()
            if (autoCreate) {
                runCatching { save(path, defaults) }
                    .onFailure { err ->
                        log.warn(
                            "[FALLBACK][profile] Unable to create default user profile at {}: {} (using in-memory defaults)",
                            path,
                            err.message,
                        )
                    }
            }
            return defaults
        }
        return load(path)
    }

    fun load(path: Path): UserProfile {
        val raw = runCatching { Formats.read(path) }.getOrElse { err ->
            log.warn(
                "[FALLBACK][profile] Failed to parse user profile {}: {} (falling back to defaults)",
                path,
                err.message,
            )
            return UserProfile()
        }
        return parse(raw, path.toString())
    }

    internal fun parse(raw: Map<String, Any?>, sourceLabel: String = "profile.yaml"): UserProfile {
        val rawSchema = (raw["schema"] as? Number)?.toInt()
        if (rawSchema != null && rawSchema != UserProfile.SCHEMA_VERSION) {
            log.warn(
                "[FALLBACK][profile] Unsupported schema={} in {} (expected {}); loading compatible fields with defaults",
                rawSchema,
                sourceLabel,
                UserProfile.SCHEMA_VERSION,
            )
        }

        val language = when (val lang = raw["language"]) {
            null -> UserProfile.DEFAULT_LANGUAGE
            is String -> {
                val trimmed = lang.trim()
                val norm = trimmed.lowercase().replace('-', '_')
                val canonical = when {
                    norm == "en" || norm.startsWith("en_") || norm == "english" -> "en"
                    norm == "zh" -> "zh"
                    norm == "zh_cn" || norm == "zh_hans" || norm.startsWith("zh_hans_") || norm == "chinese" -> "zh_CN"
                    norm == "ja" || norm == "jp" || norm == "ja_jp" || norm.startsWith("ja_") || norm == "japanese" -> "ja_JP"
                    norm == "de" || norm == "de_de" || norm.startsWith("de_") || norm == "german" || norm == "deutsch" -> "de_DE"
                    norm == "fr" || norm == "fr_fr" || norm.startsWith("fr_") || norm == "french" || norm == "francais" || norm == "fran\u00E7ais" -> "fr_FR"
                    norm == "es" || norm == "es_es" || norm.startsWith("es_") || norm == "spanish" || norm == "espanol" || norm == "espa\u00F1ol" -> "es_ES"
                    else -> null
                }
                if (canonical != null) {
                    canonical
                } else {
                    log.warn(
                        "[FALLBACK][profile] Invalid language '{}' in {} (supported: {}); falling back to '{}'",
                        lang,
                        sourceLabel,
                        UserProfile.SUPPORTED_LANGUAGES,
                        UserProfile.DEFAULT_LANGUAGE,
                    )
                    UserProfile.DEFAULT_LANGUAGE
                }
            }
            else -> {
                log.warn(
                    "[FALLBACK][profile] Non-string language '{}' in {}; falling back to '{}'",
                    lang,
                    sourceLabel,
                    UserProfile.DEFAULT_LANGUAGE,
                )
                UserProfile.DEFAULT_LANGUAGE
            }
        }

        val themeMap = when (val t = raw["theme"]) {
            null -> emptyMap<Any?, Any?>()
            is Map<*, *> -> t
            else -> {
                log.warn("[FALLBACK][profile] Invalid 'theme' section in {}; using default themes", sourceLabel)
                emptyMap<Any?, Any?>()
            }
        }
        val bodyTheme = parseThemeId(themeMap["body"], "theme.body", UserProfile.DEFAULT_BODY_THEME, sourceLabel)
        val uiTheme = parseThemeId(themeMap["ui"], "theme.ui", UserProfile.DEFAULT_UI_THEME, sourceLabel)

        val windowsMap = when (val w = raw["windows"]) {
            null -> emptyMap<Any?, Any?>()
            is Map<*, *> -> w
            else -> {
                log.warn("[FALLBACK][profile] Invalid 'windows' section in {}; using default window preferences", sourceLabel)
                emptyMap<Any?, Any?>()
            }
        }
        val fitScale = when (val fs = windowsMap["fitScale"]) {
            null -> UserProfile.DEFAULT_FIT_SCALE
            is Number -> {
                val value = fs.toDouble()
                if (value.isFinite() && value in 0.5..1.0) {
                    value
                } else {
                    log.warn(
                        "[FALLBACK][profile] Out-of-range windows.fitScale={} in {} (expected 0.5..1.0); falling back to {}",
                        fs,
                        sourceLabel,
                        UserProfile.DEFAULT_FIT_SCALE,
                    )
                    UserProfile.DEFAULT_FIT_SCALE
                }
            }
            else -> {
                log.warn(
                    "[FALLBACK][profile] Invalid windows.fitScale='{}' in {}; falling back to {}",
                    fs,
                    sourceLabel,
                    UserProfile.DEFAULT_FIT_SCALE,
                )
                UserProfile.DEFAULT_FIT_SCALE
            }
        }

        val evfWindowOpen = when (val evf = windowsMap["evfWindowOpen"]) {
            null -> UserProfile.DEFAULT_EVF_WINDOW_OPEN
            is Boolean -> evf
            else -> {
                log.warn(
                    "[FALLBACK][profile] Invalid windows.evfWindowOpen='{}' in {}; falling back to {}",
                    evf,
                    sourceLabel,
                    UserProfile.DEFAULT_EVF_WINDOW_OPEN,
                )
                UserProfile.DEFAULT_EVF_WINDOW_OPEN
            }
        }

        val evfAlwaysOnTop = when (val pin = windowsMap["evfAlwaysOnTop"]) {
            null -> UserProfile.DEFAULT_EVF_ALWAYS_ON_TOP
            is Boolean -> pin
            else -> {
                log.warn(
                    "[FALLBACK][profile] Invalid windows.evfAlwaysOnTop='{}' in {}; falling back to {}",
                    pin,
                    sourceLabel,
                    UserProfile.DEFAULT_EVF_ALWAYS_ON_TOP,
                )
                UserProfile.DEFAULT_EVF_ALWAYS_ON_TOP
            }
        }

        val graphicsMap = when (val g = raw["graphics"]) {
            null -> emptyMap<Any?, Any?>()
            is Map<*, *> -> g
            else -> {
                log.warn("[FALLBACK][profile] Invalid 'graphics' section in {}; using default graphics preferences", sourceLabel)
                emptyMap<Any?, Any?>()
            }
        }
        val opticsBackend = when (val b = graphicsMap["opticsBackend"]) {
            null -> UserProfile.DEFAULT_OPTICS_BACKEND
            is String -> {
                val norm = b.trim().lowercase()
                if (norm in UserProfile.SUPPORTED_OPTICS_BACKENDS) {
                    norm
                } else {
                    log.warn(
                        "[FALLBACK][profile] Invalid graphics.opticsBackend='{}' in {} (expected {}); falling back to '{}'",
                        b,
                        sourceLabel,
                        UserProfile.SUPPORTED_OPTICS_BACKENDS,
                        UserProfile.DEFAULT_OPTICS_BACKEND,
                    )
                    UserProfile.DEFAULT_OPTICS_BACKEND
                }
            }
            else -> {
                log.warn("[FALLBACK][profile] Non-string graphics.opticsBackend='{}' in {}; falling back to '{}'", b, sourceLabel, UserProfile.DEFAULT_OPTICS_BACKEND)
                UserProfile.DEFAULT_OPTICS_BACKEND
            }
        }
        val vulkanDevice = when (val d = graphicsMap["vulkanDevice"]) {
            null -> UserProfile.DEFAULT_VULKAN_DEVICE
            is String -> d.trim().take(280).ifEmpty { UserProfile.DEFAULT_VULKAN_DEVICE }
            is Number -> d.toInt().toString()
            else -> {
                log.warn("[FALLBACK][profile] Invalid graphics.vulkanDevice='{}' in {}; falling back to '{}'", d, sourceLabel, UserProfile.DEFAULT_VULKAN_DEVICE)
                UserProfile.DEFAULT_VULKAN_DEVICE
            }
        }

        return UserProfile(
            schema = UserProfile.SCHEMA_VERSION,
            language = language,
            bodyTheme = bodyTheme,
            uiTheme = uiTheme,
            fitScale = fitScale,
            evfWindowOpen = evfWindowOpen,
            evfAlwaysOnTop = evfAlwaysOnTop,
            opticsBackend = opticsBackend,
            vulkanDevice = vulkanDevice,
        )
    }

    private fun parseThemeId(raw: Any?, field: String, defaultId: String, sourceLabel: String): String {
        if (raw == null) return defaultId
        if (raw !is String) {
            log.warn("[FALLBACK][profile] Non-string {}='{}' in {}; falling back to '{}'", field, raw, sourceLabel, defaultId)
            return defaultId
        }
        val normalized = raw.trim().lowercase()
        if (!UserProfile.THEME_ID_PATTERN.matches(normalized)) {
            log.warn("[FALLBACK][profile] Invalid {}='{}' in {}; falling back to '{}'", field, raw, sourceLabel, defaultId)
            return defaultId
        }
        return normalized
    }

    fun formatYaml(profile: UserProfile): String = buildString {
        appendLine("# ==============================================================================")
        appendLine("# FUJIFILM GFX100S Emulator — Global Photographer Studio Profile (profile.yaml)")
        appendLine("#")
        appendLine("# Stores desktop shell & appearance preferences ONLY (language, camera body")
        appendLine("# theme, UI window theme, and studio window layout preferences).")
        appendLine("# Hardware, lens, optical filter, SD card, and scene settings belong strictly")
        appendLine("# in configs/components/*.yaml.")
        appendLine("# ==============================================================================")
        appendLine("schema: ${profile.schema}")
        appendLine()
        appendLine("# Interface language preference (en | zh_CN | ja_JP | de_DE | fr_FR | es_ES)")
        appendLine("language: \"${profile.language}\"")
        appendLine()
        appendLine("# Independent theme selection (camera body theme and UI window theme decouple cleanly)")
        appendLine("theme:")
        appendLine("  # Camera body theme (built-in: origin | hasselblad-x2d2 | hasselblad-907x | compact-rangefinder,")
        appendLine("  # or external folder under ./themes/body/<id>/body-theme.json)")
        appendLine("  body: \"${profile.bodyTheme}\"")
        appendLine("  # UI window theme (built-in: origin | nordic-minimal | daylight-studio | vintage-brass | olive-field,")
        appendLine("  # or external stylesheet under ./themes/ui/<id>.css)")
        appendLine("  ui: \"${profile.uiTheme}\"")
        appendLine()
        appendLine("# Desktop studio window layout preferences")
        appendLine("windows:")
        appendLine("  # Overall studio face arrangement scale (0.5 .. 1.0)")
        appendLine("  fitScale: ${profile.fitScale}")
        appendLine("  # Re-open magnified EVF (LCDC0) floating window automatically")
        appendLine("  evfWindowOpen: ${profile.evfWindowOpen}")
        appendLine("  # Keep the floating EVF window pinned above all other windows (Always-on-Top)")
        appendLine("  evfAlwaysOnTop: ${profile.evfAlwaysOnTop}")
        appendLine()
        appendLine("# Graphics / Vulkan optical compute preferences")
        appendLine("# NOTE: For zero-copy game capture (e.g. Minecraft via GPU External Memory),")
        appendLine("# select the SAME GPU that the game runs on.")
        appendLine("graphics:")
        appendLine("  # Optical compute backend (auto | vulkan | cpu)")
        appendLine("  opticsBackend: \"${profile.opticsBackend}\"")
        appendLine("  # Preferred Vulkan physical GPU (\"auto\", device index \"0\", or \"<index>:<deviceName>\")")
        appendLine("  vulkanDevice: \"${profile.vulkanDevice.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    fun save(path: Path, profile: UserProfile) {
        val parent = path.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, formatYaml(profile), StandardCharsets.UTF_8)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
