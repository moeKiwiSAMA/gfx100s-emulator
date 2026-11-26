package com.github.nyabanban.fuji.emulator.desktop.theme

import com.fasterxml.jackson.databind.JsonNode
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraPanel
import com.github.nyabanban.fuji.emulator.desktop.panels.DynamicStateBinding
import com.github.nyabanban.fuji.emulator.desktop.panels.LayerCondition
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelControl
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCover
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelResources
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioLayout
import com.github.nyabanban.fuji.emulator.desktop.panels.SvgArtwork
import com.github.nyabanban.fuji.emulator.runtime.Formats
import javafx.scene.Group
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Declarative window layout for a [BodyTheme] with 1..N panels.
 * Units are logical JavaFX layout units (independent of camera millimetres).
 */
data class BodyThemeLayout(
    val width: Double,
    val height: Double,
    val entryMinWidth: Double = StudioLayout.ENTRY_MIN_WIDTH,
    val configuration: StudioLayout.Box,
    val faces: Map<String, StudioLayout.Face>,
) {
    init {
        require(width.isFinite() && width in 400.0..4096.0) { "layout width out of range: $width" }
        require(height.isFinite() && height in 300.0..4096.0) { "layout height out of range: $height" }
        require(entryMinWidth.isFinite() && entryMinWidth in 280.0..800.0) { "entryMinWidth out of range" }
        require(faces.isNotEmpty()) { "layout must define at least 1 face" }
    }

    companion object {
        fun origin(): BodyThemeLayout = BodyThemeLayout(
            width = StudioLayout.WIDTH,
            height = StudioLayout.HEIGHT,
            entryMinWidth = StudioLayout.ENTRY_MIN_WIDTH,
            configuration = StudioLayout.configuration,
            faces = StudioLayout.faces.toMap(),
        )
    }
}

/**
 * Decoupled Camera Body Theme descriptor (independent of [UiTheme]).
 *
 * Vector SVG artwork is strongly recommended (crisp scaling, compact, supports procedural microtextures
 * and bounded pack-local bitmap material textures via `<pattern data-texture="textures/...">`).
 * Optional full-view raster artwork (`"artwork": "rear.png"`) is also supported with strict size
 * and pixel-dimension caps, though discouraged compared to vector SVG.
 */
data class BodyTheme(
    val id: String,
    val name: String,
    val nameZh: String,
    val description: String,
    val brandLabel: String,
    val modelLabel: String,
    val panels: List<CameraPanel>,
    val layout: BodyThemeLayout,
    val builtIn: Boolean,
    val sourceLabel: String,
    private val svgLoader: (String) -> ByteArray,
    val descriptionZh: String = "",
    val names: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val recommendedUiTheme: String? = null,
    private val assetLoader: (String) -> ByteArray = svgLoader,
) {
    val panelCount: Int get() = panels.size

    private fun builtInI18nNameKey(): String? = when (id) {
        "origin" -> "theme.body.origin"
        "hasselblad-x2d2" -> "theme.body.hasselblad_x2d2"
        "hasselblad-907x" -> "theme.body.hasselblad_907x"
        "compact-rangefinder" -> "theme.body.compact_rangefinder"
        else -> null
    }

    private fun builtInI18nDescKey(): String? = builtInI18nNameKey()?.let { "$it.desc" }

    /**
     * Localized display name supporting all 6 desktop locales (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`)
     * with strict English default fallback for any missing or unsupported locale.
     */
    fun localizedName(localeCode: String = ThemeLocalization.activeLocaleCode()): String {
        val merged = if (builtIn) {
            ThemeLocalization.mergeLocaleMaps(ThemeLocalization.builtInBodyEntry(id).names, names)
        } else {
            names
        }
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
            ThemeLocalization.mergeLocaleMaps(ThemeLocalization.builtInBodyEntry(id).descriptions, descriptions)
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

    fun defaultViewId(): String =
        panels.firstOrNull { it.id == "rear" }?.id ?: panels.first().id

    fun loadSvgBytes(svgFileName: String): ByteArray = svgLoader(svgFileName)

    fun loadAssetBytes(relativePath: String): ByteArray = assetLoader(relativePath)

    /**
     * Parses, sanitizes, and validates an SVG [panel]'s artwork against [PanelResources.document]
     * and verifies exact viewBox, control hitbox geometry, layer IDs, and dynamic-state target IDs.
     */
    fun validateArtwork(panel: CameraPanel): Element {
        require(!panel.isRasterArtwork()) {
            "Panel '${panel.id}' in theme '$id' uses raster artwork '${panel.artwork}'; use validatePanel() or renderPanelArtwork()"
        }
        val svg = PanelResources.document(loadSvgBytes(panel.svg))
        val box = svg.getAttribute("viewBox").trim().split(Regex("[ ,]+")).map(String::toDouble)
        require(box == listOf(0.0, 0.0, panel.width, panel.height)) {
            "SVG/theme viewBox mismatch in theme '$id' panel '${panel.id}': expected 0 0 ${panel.width} ${panel.height}, got $box"
        }
        val controls = mutableMapOf<String, Element>()
        val allIds = mutableSetOf<String>()
        fun visit(e: Element) {
            if (e.hasAttribute("data-control-proxy")) {
                val target = e.getAttribute("data-control-proxy")
                require(e.parentNode === svg && e.localName == "rect" && e.hasAttribute("id") &&
                    !e.hasAttribute("transform") && e.getAttribute("fill") == "none" && e.getAttribute("stroke") == "none" &&
                    panels.any { face -> face.controls.any { it.id == target && it.buttonKey != null } }) {
                    "invalid proxy for physical control '$target' in ${panel.id}"
                }
                val x = e.getAttribute("x").toDouble()
                val y = e.getAttribute("y").toDouble()
                val w = e.getAttribute("width").toDouble()
                val h = e.getAttribute("height").toDouble()
                require(x >= 0.0 && y >= 0.0 && w > 0.0 && h > 0.0 && x + w <= panel.width && y + h <= panel.height) {
                    "proxy for '$target' outside ${panel.id}"
                }
            }
            if (e.hasAttribute("id")) allIds += e.getAttribute("id")
            if (e.hasAttribute("data-control-id")) {
                val cid = e.getAttribute("data-control-id")
                require(e.getAttribute("id") == cid && e.getAttribute("data-view-id") == panel.id && controls.put(cid, e) == null) {
                    "Invalid or duplicate data-control-id '$cid' in theme '$id' panel '${panel.id}'"
                }
            }
            PanelResources.children(e).forEach(::visit)
        }
        visit(svg)
        require(controls.keys == panel.controls.map { it.id }.toSet()) {
            "SVG/theme controls disagree in theme '$id' panel '${panel.id}': svg=${controls.keys} theme=${panel.controls.map { it.id }.toSet()}"
        }
        panel.controls.forEach { c ->
            val hits = PanelResources.children(controls.getValue(c.id)).filter { it.getAttribute("data-hitbox") == "true" }
            require(hits.size == 1 && hits.single().localName == "rect") {
                "Control '${c.id}' in theme '$id' panel '${panel.id}' must have exactly one <rect data-hitbox=\"true\">"
            }
            val hit = hits.single()
            require(listOf("x", "y", "width", "height").map { hit.getAttribute(it).toDouble() } == listOf(c.x, c.y, c.width, c.height)) {
                "Hitbox geometry mismatch for control '${c.id}' in theme '$id' panel '${panel.id}'"
            }
            require(hit.getAttribute("fill") == "none" && hit.getAttribute("stroke") == "none") {
                "Artwork hotspot '${c.id}' in theme '$id' must be transparent (fill=\"none\" stroke=\"none\")"
            }
        }
        require(panel.covers.all { "${it.id}-open" in allIds && "${it.id}-closed" in allIds }) { "missing cover artwork in ${panel.id}" }
        require(panel.layers.all { it.id in allIds }) {
            "Missing layer id in theme '$id' panel '${panel.id}'"
        }
        require(panel.dynamicStates.all { it.id in allIds }) {
            "Missing dynamicState target id in theme '$id' panel '${panel.id}'"
        }
        return svg
    }

    /** Validates either SVG artwork or optional raster artwork for [panel]. */
    fun validatePanel(panel: CameraPanel) {
        if (panel.isRasterArtwork()) {
            require(panel.covers.isEmpty()) { "Mechanical covers require separate open and closed SVG groups" }
            val path = requireNotNull(panel.artwork)
            require(PanelResources.isSafeRelativeAssetPath(path)) {
                "Unsafe raster artwork path '$path' in theme '$id' panel '${panel.id}'"
            }
            val bytes = loadAssetBytes(path)
            require(bytes.isNotEmpty() && bytes.size <= SvgArtwork.MAX_RASTER_VIEW_BYTES) {
                "Raster artwork '$path' size ${bytes.size} exceeds ${SvgArtwork.MAX_RASTER_VIEW_BYTES} bytes"
            }
            require(hasRecognizedImageMagic(bytes)) {
                "Raster artwork '$path' in theme '$id' panel '${panel.id}' is not a valid PNG/JPEG/WEBP image"
            }
        } else {
            validateArtwork(panel)
        }
    }

    /** Renders [panel] as a JavaFX [Group] using vector SVG (preferred) or optional raster artwork. */
    fun renderPanelArtwork(panel: CameraPanel): Group {
        if (panel.isRasterArtwork()) {
            val path = requireNotNull(panel.artwork)
            val bytes = loadAssetBytes(path)
            return SvgArtwork.renderRasterView(bytes, panel.width, panel.height)
        }
        return SvgArtwork.render(validateArtwork(panel), assetLoader)
    }

    fun toSummaryMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "nameZh" to nameZh,
        "localizedName" to localizedName(),
        "description" to description,
        "localizedDescription" to localizedDescription(),
        "brandLabel" to brandLabel,
        "modelLabel" to modelLabel,
        "recommendedUiTheme" to recommendedUiTheme,
        "panelCount" to panelCount,
        "panelIds" to panels.map { it.id },
        "builtIn" to builtIn,
        "source" to sourceLabel,
    )

    companion object {
        private fun hasRecognizedImageMagic(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            // JPEG: FF D8 FF
            val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            // WEBP: RIFF....WEBP
            val isWebp = bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
            return isPng || isJpeg || isWebp
        }
    }
}

object BodyThemeRegistry {
    private val log = LoggerFactory.getLogger(BodyThemeRegistry::class.java)
    const val BUILTIN_RESOURCE_ROOT: String = "/themes/body/"
    const val THEME_MANIFEST_FILE: String = "body-theme.json"
    private val SAFE_ID = Regex("""[a-z0-9][a-z0-9_-]{0,63}""")
    private val SAFE_VIEW_ID = Regex("""[a-z][a-z0-9-]{0,47}""")
    private val SAFE_CONTROL_ID = Regex("""[a-z][a-z0-9-]{0,63}""")
    private val SAFE_SVG_NAME = Regex("""[a-z][a-z0-9-]*\.svg""")
    private val ALLOWED_ROLES = setOf("view-mode", "eye-sensor", "rear-command-dial", "front-command-dial", "focus-selector", "exposure-mode-button", "focus-mode-segments")
    private val ALLOWED_CLIP_SHAPES = setOf("rect", "circle")
    private val ALLOWED_DYNAMIC_MODES = setOf("rotate-map", "highlight-button", "led-binding")
    private val BUILTIN_PACK_IDS: List<String> = listOf(
        "hasselblad-x2d2",
        "hasselblad-907x",
        "compact-rangefinder",
    )

    private val originTheme: BodyTheme by lazy {
        val i18nEntry = ThemeLocalization.builtInBodyEntry("origin")
        BodyTheme(
            id = "origin",
            name = "Fujifilm GFX100S (Origin)",
            nameZh = I18n.rawPattern(I18n.CODE_ZH_CN, "theme.body.origin")
                ?: i18nEntry.names["zh_CN"]
                ?: "Fujifilm GFX100S (Origin)",
            description = i18nEntry.descriptions["en"]
                ?: "Original 6-face GFX100S camera studio (rear, top, front, left ports, right storage, bottom battery).",
            descriptionZh = i18nEntry.descriptions["zh_CN"].orEmpty(),
            names = i18nEntry.names,
            descriptions = i18nEntry.descriptions,
            brandLabel = "FUJIFILM",
            modelLabel = "GFX100S",
            recommendedUiTheme = "origin",
            panels = PanelCatalog.load(),
            layout = BodyThemeLayout.origin(),
            builtIn = true,
            sourceLabel = PanelResources.ROOT + "catalog.json",
            svgLoader = PanelResources::bytes,
            assetLoader = PanelResources::assetBytes,
        )
    }

    private val builtInPackThemes: List<BodyTheme> by lazy {
        BUILTIN_PACK_IDS.map { packId ->
            loadBuiltInPack(packId)
        }
    }

    fun origin(): BodyTheme = originTheme

    fun builtInThemes(): List<BodyTheme> = buildList {
        add(originTheme)
        addAll(builtInPackThemes)
    }

    private fun loadBuiltInPack(packId: String): BodyTheme {
        val base = "$BUILTIN_RESOURCE_ROOT$packId/"
        val manifestBytes = readResourceBytes(base + THEME_MANIFEST_FILE, PanelResources.MAX_BYTES)
        val svgLoader: (String) -> ByteArray = { svgName ->
            require(SAFE_SVG_NAME.matches(svgName)) { "invalid SVG file name: $svgName" }
            readResourceBytes(base + svgName, PanelResources.MAX_BYTES)
        }
        val assetLoader: (String) -> ByteArray = { relPath ->
            if (SAFE_SVG_NAME.matches(relPath)) {
                readResourceBytes(base + relPath, PanelResources.MAX_BYTES)
            } else {
                require(PanelResources.isSafeRelativeAssetPath(relPath)) { "unsafe asset path: $relPath" }
                val resourcePath = PanelResources.sharedBodyAssetPath(relPath) ?: (base + relPath)
                readResourceBytes(resourcePath, SvgArtwork.MAX_RASTER_VIEW_BYTES)
            }
        }
        return parseThemeManifest(
            bytes = manifestBytes,
            expectedId = packId,
            builtIn = true,
            sourceLabel = base + THEME_MANIFEST_FILE,
            svgLoader = svgLoader,
            assetLoader = assetLoader,
            validateAllArtworks = false,
        )
    }

    private fun readResourceBytes(resourcePath: String, maxBytes: Int): ByteArray {
        return requireNotNull(BodyThemeRegistry::class.java.getResourceAsStream(resourcePath)) {
            "Missing body theme resource: $resourcePath"
        }.use { stream ->
            stream.readNBytes(maxBytes + 1).also { bytes ->
                require(bytes.size <= maxBytes) { "Body theme resource too large: $resourcePath" }
            }
        }
    }

    fun discoverAll(home: Path? = null): List<BodyTheme> {
        val byId = linkedMapOf<String, BodyTheme>()
        builtInThemes().forEach { byId[it.id] = it }
        val extDir = home?.resolve("themes")?.resolve("body")
        if (extDir != null && Files.isDirectory(extDir)) {
            runCatching {
                Files.list(extDir).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .sorted()
                        .forEach { packDir ->
                            loadExternalPack(packDir)?.let { theme ->
                                byId[theme.id] = theme
                            }
                        }
                }
            }.onFailure { err ->
                log.warn("[FALLBACK][theme] Failed scanning external body themes directory {}: {}", extDir, err.message)
            }
        }
        return byId.values.toList()
    }

    private fun loadExternalPack(packDir: Path): BodyTheme? {
        val dirId = packDir.fileName.toString().lowercase(Locale.ROOT)
        if (!SAFE_ID.matches(dirId)) {
            log.warn("[FALLBACK][theme] Ignoring external body theme directory '{}' with invalid id '{}'", packDir, dirId)
            return null
        }
        val manifestPath = packDir.resolve(THEME_MANIFEST_FILE)
        if (!Files.isRegularFile(manifestPath)) {
            log.warn("[FALLBACK][theme] Ignoring external body theme '{}' missing {}", packDir, THEME_MANIFEST_FILE)
            return null
        }
        return runCatching {
            val size = Files.size(manifestPath)
            require(size in 1..PanelResources.MAX_BYTES.toLong()) { "manifest size $size outside 1..${PanelResources.MAX_BYTES}" }
            val manifestBytes = Files.readAllBytes(manifestPath)
            val realPackDir = packDir.toRealPath()
            fun readContained(relPath: String, maxBytes: Long): ByteArray {
                val resolved = realPackDir.resolve(relPath).normalize()
                require(resolved.startsWith(realPackDir) && Files.isRegularFile(resolved)) {
                    "missing or out-of-bounds theme asset: $relPath"
                }
                val realFile = resolved.toRealPath()
                require(realFile.startsWith(realPackDir)) {
                    "symlink escape rejected for theme asset: $relPath"
                }
                val fileSize = Files.size(realFile)
                require(fileSize in 1..maxBytes) { "theme asset '$relPath' size $fileSize outside 1..$maxBytes" }
                return Files.readAllBytes(realFile)
            }
            val svgLoader: (String) -> ByteArray = { svgName ->
                require(SAFE_SVG_NAME.matches(svgName)) { "invalid SVG file name: $svgName" }
                readContained(svgName, PanelResources.MAX_BYTES.toLong())
            }
            val assetLoader: (String) -> ByteArray = { relPath ->
                if (SAFE_SVG_NAME.matches(relPath)) {
                    readContained(relPath, PanelResources.MAX_BYTES.toLong())
                } else {
                    require(PanelResources.isSafeRelativeAssetPath(relPath)) { "unsafe asset path: $relPath" }
                    readContained(relPath, SvgArtwork.MAX_RASTER_VIEW_BYTES.toLong())
                }
            }
            parseThemeManifest(
                bytes = manifestBytes,
                expectedId = dirId,
                builtIn = false,
                sourceLabel = manifestPath.toAbsolutePath().normalize().toString(),
                svgLoader = svgLoader,
                assetLoader = assetLoader,
                validateAllArtworks = true,
            )
        }.onFailure { err ->
            log.warn("[FALLBACK][theme] Invalid external body theme '{}': {}; ignoring pack", packDir, err.message)
        }.getOrNull()
    }

    /** Resolves [requestedId] or falls back to `origin` with SLF4J WARN `[FALLBACK][theme]`. */
    fun resolveOrFallback(requestedId: String?, home: Path? = null): BodyTheme {
        val normalized = requestedId?.trim()?.lowercase(Locale.ROOT).orEmpty().ifEmpty { UserProfile.DEFAULT_BODY_THEME }
        val all = discoverAll(home)
        val match = all.firstOrNull { it.id == normalized }
        if (match != null) return match
        log.warn(
            "[FALLBACK][theme] Requested body theme '{}' not found or invalid (available: {}); falling back to 'origin'",
            requestedId,
            all.map { it.id },
        )
        return origin()
    }

    /** Strict lookup for explicit user commands (CLI / HTTP API); returns null when unknown/invalid. */
    fun findExact(requestedId: String, home: Path? = null): BodyTheme? {
        val normalized = requestedId.trim().lowercase(Locale.ROOT)
        return discoverAll(home).firstOrNull { it.id == normalized }
    }

    fun parseThemeManifest(
        bytes: ByteArray,
        expectedId: String? = null,
        builtIn: Boolean = false,
        sourceLabel: String = "inline",
        svgLoader: (String) -> ByteArray,
        assetLoader: (String) -> ByteArray = svgLoader,
        validateAllArtworks: Boolean = true,
    ): BodyTheme {
        require(bytes.size <= PanelResources.MAX_BYTES) { "body theme manifest too large" }
        val tree = Formats.json.readTree(bytes)
        val schema = tree.path("schema").asInt(-1)
        require(schema == 1 || schema == 2) { "unsupported body theme schema: $schema" }
        fun reqText(node: JsonNode, key: String, limit: Int = 200): String {
            val v = node.path(key)
            require(v.isTextual) { "missing textual field '$key'" }
            return v.asText().also { require(it.isNotBlank() && it.length <= limit) { "invalid field '$key'" } }
        }
        fun optText(node: JsonNode, key: String, limit: Int = 240): String? =
            node.path(key).takeIf { !it.isMissingNode && !it.isNull }?.let { reqText(node, key, limit) }
        fun reqNum(node: JsonNode, key: String): Double {
            require(node.path(key).isNumber) { "missing numeric field '$key'" }
            return node.path(key).asDouble().also { require(it.isFinite()) { "non-finite '$key'" } }
        }
        fun optNum(node: JsonNode, key: String): Double? =
            node.path(key).takeIf { it.isNumber }?.asDouble()?.also { require(it.isFinite()) { "non-finite '$key'" } }
        fun parseBox(node: JsonNode): StudioLayout.Box {
            require(node.isObject) { "layout box must be an object" }
            val x = reqNum(node, "x")
            val y = reqNum(node, "y")
            val w = reqNum(node, "width")
            val h = reqNum(node, "height")
            require(w > 0.0 && h > 0.0) { "box width/height must be positive" }
            return StudioLayout.Box(x, y, w, h)
        }

        val id = reqText(tree, "id", 64).lowercase(Locale.ROOT)
        require(SAFE_ID.matches(id)) { "invalid body theme id: $id" }
        if (expectedId != null) {
            require(id == expectedId) { "body theme id '$id' does not match folder '$expectedId'" }
        }
        val name = reqText(tree, "name", 120)
        val nameZh = optText(tree, "nameZh", 120) ?: name
        val description = optText(tree, "description", 320) ?: name
        val descriptionZh = optText(tree, "descriptionZh", 320).orEmpty()
        val i18nNode = tree.path("i18n")
        val namesMap = ThemeLocalization.mergeLocaleMaps(
            ThemeLocalization.parseLocaleMap(i18nNode.path("names")),
            ThemeLocalization.parseLocaleMap(tree.path("names")),
        )
        val descriptionsMap = ThemeLocalization.mergeLocaleMaps(
            ThemeLocalization.parseLocaleMap(i18nNode.path("descriptions")),
            ThemeLocalization.parseLocaleMap(tree.path("descriptions")),
        )
        val brandLabel = optText(tree, "brandLabel", 40) ?: "FUJIFILM"
        val modelLabel = optText(tree, "modelLabel", 40) ?: "GFX100S"
        val recommendedUiTheme = optText(tree, "recommendedUiTheme", 64)?.lowercase(Locale.ROOT)?.also {
            require(SAFE_ID.matches(it)) { "invalid recommendedUiTheme '$it'" }
        }

        val viewsNode = tree.path("views")
        require(viewsNode.isArray && viewsNode.size() in 1..12) { "views must contain 1..12 panels" }
        val panels = viewsNode.map { v ->
            val viewId = reqText(v, "id", 48)
            require(SAFE_VIEW_ID.matches(viewId)) { "invalid panel id: $viewId" }
            val width = reqNum(v, "width")
            val height = reqNum(v, "height")
            require(width in 100.0..4096.0 && height in 100.0..4096.0) { "panel dimensions out of range" }
            val artworkPath = optText(v, "artwork", 200)
            val svgField = optText(v, "svg", 80)
            val svgName: String
            if (artworkPath != null && !artworkPath.endsWith(".svg", ignoreCase = true)) {
                require(PanelResources.isSafeRelativeAssetPath(artworkPath)) {
                    "invalid raster artwork path '$artworkPath' in panel '$viewId'"
                }
                svgName = svgField ?: ""
            } else {
                val chosenSvg = svgField ?: artworkPath ?: error("panel '$viewId' requires 'svg' (recommended) or 'artwork'")
                require(SAFE_SVG_NAME.matches(chosenSvg)) { "invalid panel svg name: $chosenSvg" }
                svgName = chosenSvg
            }
            val controlsNode = v.path("controls")
            require(controlsNode.isArray && controlsNode.size() in 1..128) { "panel '$viewId' controls must contain 1..128 entries" }
            val controls = controlsNode.map { c ->
                val cid = reqText(c, "id", 64)
                require(SAFE_CONTROL_ID.matches(cid)) { "invalid control id: $cid" }
                val cViewId = optText(c, "viewId", 48) ?: viewId
                require(cViewId == viewId) { "control '$cid' viewId '$cViewId' != '$viewId'" }
                val x = reqNum(c, "x")
                val y = reqNum(c, "y")
                val cw = reqNum(c, "width")
                val ch = reqNum(c, "height")
                require(x >= 0.0 && y >= 0.0 && cw > 0.0 && ch > 0.0 && x + cw <= width && y + ch <= height) {
                    "control '$cid' outside panel '$viewId' viewBox"
                }
                val hitNode = c.path("hitbox")
                if (!hitNode.isMissingNode && !hitNode.isNull) {
                    require(hitNode.isObject) { "hitbox must be an object" }
                    require(listOf("x", "y", "width", "height").map { reqNum(hitNode, it) } == listOf(x, y, cw, ch)) {
                        "control '$cid' hitbox bounds differ from control bounds"
                    }
                }
                val key = optText(c, "buttonKey", 64)
                require(key == null || key in PanelCatalog.buttonKeys) { "unknown buttonKey '$key'" }
                val component = optText(c, "componentId", 64)
                val setting = optText(c, "settingKey", 64)
                require((setting == null || component != null) && !(key != null && component != null)) {
                    "control '$cid' cannot mix buttonKey and componentId"
                }
                val display = optText(c, "displayPort", 64)
                require(display == null || display in setOf("display.main", "display.shoulder", "display.evf")) {
                    "unsupported displayPort '$display'"
                }
                val role = optText(c, "role", 40)?.also {
                    require(it in ALLOWED_ROLES) { "unsupported control role '$it'" }
                }
                val cornerRadius = (optNum(c, "cornerRadius") ?: 0.0).also {
                    require(it in 0.0..512.0) { "cornerRadius out of range: $it" }
                }
                val clipShape = optText(c, "clipShape", 20)?.lowercase(Locale.ROOT)?.also {
                    require(it in ALLOWED_CLIP_SHAPES) { "unsupported clipShape '$it'" }
                }
                val basis = c.path("basis").takeIf { it.isArray && it.size() > 0 }?.map { it.asText() }
                    ?: listOf("THEME-$id")
                val label = reqText(c, "label", 120)
                val labelZh = optText(c, "labelZh", 120) ?: label
                val labelsMap = ThemeLocalization.parseLocaleMap(c.path("labels"))
                PanelControl(
                    id = cid,
                    viewId = viewId,
                    label = label,
                    labelZh = labelZh,
                    x = x,
                    y = y,
                    width = cw,
                    height = ch,
                    kind = optText(c, "kind", 40) ?: "button",
                    buttonKey = key,
                    componentId = component,
                    settingKey = setting,
                    basis = basis,
                    bindingStatus = optText(c, "bindingStatus", 60) ?: "host-logic-only",
                    firmwareBinding = optText(c, "firmwareBinding", 60) ?: "GFX100S-THEME",
                    interaction = c.path("interaction").asText("inspect"),
                    displayPort = display,
                    role = role,
                    cornerRadius = cornerRadius,
                    clipShape = clipShape,
                    labels = labelsMap,
                )
            }
            val layers = v.path("layers").takeIf { !it.isMissingNode && !it.isNull }?.let { list ->
                require(list.isArray && list.size() <= 8)
                list.map { layer ->
                    val lid = reqText(layer, "id", 64)
                    require(SAFE_CONTROL_ID.matches(lid))
                    val whenNode = layer.path("visibleWhen")
                    require(whenNode.isObject && whenNode.path("equals").isBoolean)
                    LayerCondition(
                        lid,
                        reqText(whenNode, "componentId", 64),
                        reqText(whenNode, "settingKey", 64),
                        whenNode.path("equals").asBoolean(),
                    )
                }.also { require(it.map { l -> l.id }.distinct().size == it.size) }
            } ?: emptyList()
            val dynamicStates = v.path("dynamicStates").takeIf { !it.isMissingNode && !it.isNull }?.let { list ->
                require(list.isArray && list.size() <= 32) { "dynamicStates max 32 entries per panel" }
                list.map { ds ->
                    val dsid = reqText(ds, "id", 64)
                    require(SAFE_CONTROL_ID.matches(dsid)) { "invalid dynamicState id '$dsid'" }
                    val mode = reqText(ds, "mode", 32)
                    require(mode in ALLOWED_DYNAMIC_MODES) { "unsupported dynamicState mode '$mode'" }
                    val compId = optText(ds, "componentId", 64)
                    val setKey = optText(ds, "settingKey", 64)
                    val btnKey = optText(ds, "buttonKey", 64)?.also {
                        require(it in PanelCatalog.buttonKeys) { "unknown dynamicState buttonKey '$it'" }
                    }
                    val angleMap = linkedMapOf<String, Double>()
                    val amNode = ds.path("angleMap")
                    if (amNode.isObject) {
                        amNode.properties().forEach { (k, valNode) ->
                            require(valNode.isNumber && valNode.asDouble().isFinite()) { "angleMap value must be finite number" }
                            angleMap[k] = valNode.asDouble()
                        }
                    }
                    when (mode) {
                        "rotate-map" -> require(compId != null && setKey != null && angleMap.isNotEmpty()) {
                            "rotate-map dynamicState '$dsid' requires componentId, settingKey, and non-empty angleMap"
                        }
                        "highlight-button" -> require(btnKey != null) {
                            "highlight-button dynamicState '$dsid' requires buttonKey"
                        }
                        "led-binding" -> require(compId != null && setKey != null) {
                            "led-binding dynamicState '$dsid' requires componentId and settingKey"
                        }
                    }
                    DynamicStateBinding(
                        id = dsid,
                        mode = mode,
                        componentId = compId,
                        settingKey = setKey,
                        buttonKey = btnKey,
                        angleMap = angleMap,
                        pivotX = optNum(ds, "pivotX"),
                        pivotY = optNum(ds, "pivotY"),
                        activeOpacity = (optNum(ds, "activeOpacity") ?: 1.0).coerceIn(0.0, 1.0),
                        inactiveOpacity = (optNum(ds, "inactiveOpacity") ?: 0.0).coerceIn(0.0, 1.0),
                    )
                }.also { require(it.map { d -> d.id }.distinct().size == it.size) { "duplicate dynamicState id" } }
            } ?: emptyList()
            CameraPanel(
                id = viewId,
                title = reqText(v, "title", 120),
                svg = svgName,
                width = width,
                height = height,
                controls = controls,
                layers = layers,
                titleZh = optText(v, "titleZh", 120).orEmpty(),
                titles = ThemeLocalization.parseLocaleMap(v.path("titles")),
                artwork = artworkPath,
                dynamicStates = dynamicStates,
                covers = PanelCover.parse(v, controls, width, height),
            )
        }
        require(panels.map { it.id }.distinct().size == panels.size) { "duplicate panel IDs in body theme '$id'" }
        val allControls = panels.flatMap { it.controls }
        require(allControls.map { it.id }.distinct().size == allControls.size) { "duplicate control IDs in body theme '$id'" }
        val buttonKeys = allControls.mapNotNull { it.buttonKey }
        require(buttonKeys.distinct().size == buttonKeys.size) { "duplicate buttonKey bindings in body theme '$id'" }

        val layoutNode = tree.path("layout")
        val layout = if (layoutNode.isObject) {
            val lw = reqNum(layoutNode, "width")
            val lh = reqNum(layoutNode, "height")
            val entryMin = if (layoutNode.has("entryMinWidth")) reqNum(layoutNode, "entryMinWidth") else StudioLayout.ENTRY_MIN_WIDTH
            val configBox = parseBox(layoutNode.path("configuration"))
            val facesNode = layoutNode.path("faces")
            require(facesNode.isObject) { "layout.faces must be an object" }
            val faces = linkedMapOf<String, StudioLayout.Face>()
            panels.forEach { panel ->
                val f = facesNode.path(panel.id)
                require(f.isObject) { "layout.faces missing entry for panel '${panel.id}'" }
                val slot = parseBox(f.path("slot"))
                val crop = if (f.has("crop")) parseBox(f.path("crop")) else StudioLayout.Box(0.0, 0.0, panel.width, panel.height)
                val rot = if (f.has("rotationDegrees")) reqNum(f, "rotationDegrees") else 0.0
                require(rot == 0.0 || rot == 90.0) { "rotationDegrees must be 0 or 90, got $rot" }
                faces[panel.id] = StudioLayout.Face(slot = slot, crop = crop, rotationDegrees = rot)
            }
            BodyThemeLayout(lw, lh, entryMin, configBox, faces)
        } else {
            autoGenerateLayout(panels)
        }

        val theme = BodyTheme(
            id = id,
            name = name,
            nameZh = nameZh,
            description = description,
            descriptionZh = descriptionZh,
            names = namesMap,
            descriptions = descriptionsMap,
            brandLabel = brandLabel,
            modelLabel = modelLabel,
            recommendedUiTheme = recommendedUiTheme,
            panels = panels,
            layout = layout,
            builtIn = builtIn,
            sourceLabel = sourceLabel,
            svgLoader = svgLoader,
            assetLoader = assetLoader,
        )
        if (validateAllArtworks) {
            panels.forEach { theme.validatePanel(it) }
        }
        return theme
    }

    /** Generates a clean non-overlapping studio layout for external user themes that omit `layout`. */
    private fun autoGenerateLayout(panels: List<CameraPanel>): BodyThemeLayout {
        val faces = linkedMapOf<String, StudioLayout.Face>()
        val panelColumnWidth = 760.0
        val gap = 10.0
        var y = gap
        panels.forEach { panel ->
            val aspect = (panel.height / panel.width).coerceIn(0.32, 0.85)
            val slotHeight = (panelColumnWidth * aspect).coerceIn(220.0, 520.0)
            faces[panel.id] = StudioLayout.Face(
                slot = StudioLayout.Box(gap, y, panelColumnWidth, slotHeight),
                crop = StudioLayout.Box(0.0, 0.0, panel.width, panel.height),
                rotationDegrees = 0.0,
            )
            y += slotHeight + gap
        }
        val totalHeight = maxOf(760.0, y)
        val configX = gap + panelColumnWidth + gap
        val configWidth = 500.0
        return BodyThemeLayout(
            width = configX + configWidth + gap,
            height = totalHeight,
            entryMinWidth = StudioLayout.ENTRY_MIN_WIDTH,
            configuration = StudioLayout.Box(configX, gap, configWidth, totalHeight - 2 * gap),
            faces = faces,
        )
    }
}
