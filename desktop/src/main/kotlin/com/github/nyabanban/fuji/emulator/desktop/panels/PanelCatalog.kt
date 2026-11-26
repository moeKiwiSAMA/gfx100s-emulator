package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.theme.ThemeLocalization
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Bundled, bounded SVG assets. Local gradients, clipPaths, local `<use>`, procedural microtextures, and bounded material bitmap textures are admitted; scripts, external URLs, and arbitrary filters remain forbidden. */
object PanelResources {
    const val ROOT = "/panels/gfx100s/"
    const val SHARED_BODY_ASSET_ROOT = "/themes/body/shared/"
    const val MAX_BYTES = 262144
    private val safeName = Regex("""[a-z][a-z0-9-]*\.(svg|json)""")
    private val localPaint = Regex("""url\(#([a-zA-Z][a-zA-Z0-9_-]*)\)""")
    private val localFragment = Regex("""#([a-zA-Z][a-zA-Z0-9_-]*)""")
    private val allowedTextureExts = setOf("png", "jpg", "jpeg", "webp")
    fun isSafeRelativeAssetPath(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) return false
        if (trimmed.startsWith("/") || trimmed.contains("\\") || trimmed.contains(":")) return false
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        val ext = trimmed.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in allowedTextureExts
    }
    internal fun sharedBodyAssetPath(relPath: String): String? {
        val trimmed = relPath.trim()
        if (!trimmed.startsWith("shared/") || !isSafeRelativeAssetPath(trimmed)) return null
        val fileName = trimmed.removePrefix("shared/")
        if ('/' in fileName) return null
        return SHARED_BODY_ASSET_ROOT + fileName
    }
    fun bytes(name: String): ByteArray {
        require(safeName.matches(name)) { "invalid panel resource name" }
        return requireNotNull(javaClass.getResourceAsStream(ROOT + name)) { "missing panel resource: $name" }.use {
            it.readNBytes(MAX_BYTES + 1).also { value -> require(value.size <= MAX_BYTES) { "panel resource too large" } }
        }
    }
    fun assetBytes(relPath: String): ByteArray {
        if (safeName.matches(relPath)) return bytes(relPath)
        require(isSafeRelativeAssetPath(relPath)) { "unsafe panel asset path: $relPath" }
        val resourcePath = sharedBodyAssetPath(relPath) ?: (ROOT + relPath)
        return requireNotNull(javaClass.getResourceAsStream(resourcePath)) { "missing panel asset: $relPath" }.use {
            it.readNBytes(SvgArtwork.MAX_RASTER_VIEW_BYTES + 1).also { value ->
                require(value.size <= SvgArtwork.MAX_RASTER_VIEW_BYTES) { "panel asset too large: $relPath" }
            }
        }
    }
    fun document(bytes: ByteArray): Element {
        require(bytes.size <= MAX_BYTES)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isXIncludeAware = false; isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) = throw e
                override fun error(e: org.xml.sax.SAXParseException) = throw e
                override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
            })
        }
        val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
        require(root.localName == "svg" && root.namespaceURI == "http://www.w3.org/2000/svg")
        val allowed = setOf("svg", "g", "rect", "circle", "ellipse", "path", "line", "polyline", "polygon", "text", "tspan", "title", "desc", "defs", "linearGradient", "radialGradient", "stop", "pattern", "clipPath", "use", "image")
        val reusableDefs = setOf("linearGradient", "radialGradient", "pattern", "clipPath", "g", "rect", "circle", "ellipse", "path", "line", "polyline", "polygon")
        val ids = mutableMapOf<String, Element>()
        val paintRefs = mutableListOf<String>()
        val clipRefs = mutableListOf<String>()
        val useRefs = mutableListOf<String>()
        var count = 0
        fun visit(e: Element) {
            require(++count <= 4096 && e.namespaceURI == root.namespaceURI && e.localName in allowed) { "unsupported SVG element" }
            if (e.hasAttribute("id")) require(ids.put(e.getAttribute("id"), e) == null) { "duplicate SVG ID" }
            for (i in 0 until e.attributes.length) {
                val a = e.attributes.item(i)
                val attrName = a.localName ?: a.nodeName
                require(!a.nodeName.startsWith("on", true) && attrName !in setOf("style", "filter", "mask", "gradientTransform", "patternTransform")) { "active/unsupported SVG attribute" }
                if (attrName == "href") {
                    when (e.localName) {
                        "use" -> {
                            val m = localFragment.matchEntire(a.nodeValue.trim())
                            require(m != null) { "only local #id href references are admitted on <use>" }
                            useRefs += m.groupValues[1]
                        }
                        "image" -> {
                            require(isSafeRelativeAssetPath(a.nodeValue.trim())) {
                                "only pack-local relative image hrefs are admitted on <image>: ${a.nodeValue}"
                            }
                        }
                        else -> error("href is only admitted on <use> or <image>")
                    }
                } else if (attrName == "clip-path") {
                    val m = localPaint.matchEntire(a.nodeValue.trim())
                    require(m != null) { "only local url(#id) clip-path references are admitted" }
                    clipRefs += m.groupValues[1]
                } else if (attrName == "stroke-dasharray") {
                    val raw = a.nodeValue.trim()
                    if (raw != "none" && raw.isNotEmpty()) {
                        val parts = raw.split(Regex("""[ ,\s]+""")).map { it.toDouble() }
                        require(parts.isNotEmpty() && parts.all { it.isFinite() && it >= 0.0 }) { "invalid stroke-dasharray" }
                    }
                } else if (a.nodeName == "data-effect") {
                    require(a.nodeValue in setOf("shadow", "inner-shadow")) { "unsupported data-effect: ${a.nodeValue}" }
                } else if (a.nodeValue.contains("url(", true)) {
                    val match = localPaint.matchEntire(a.nodeValue.trim())
                    require(attrName in setOf("fill", "stroke") && match != null) { "only local paint references are admitted" }
                    paintRefs += match.groupValues[1]
                }
            }
            val children = children(e)
            when (e.localName) {
                "defs" -> require(children.all { it.localName in reusableDefs })
                "linearGradient", "radialGradient" -> {
                    require(e.parentNode is Element && (e.parentNode as Element).localName == "defs")
                    require(!e.hasAttribute("gradientUnits") || e.getAttribute("gradientUnits") == "objectBoundingBox")
                    require(!e.hasAttribute("fx") && !e.hasAttribute("fy") && !e.hasAttribute("spreadMethod"))
                    require(children.size in 2..16 && children.all { it.localName == "stop" })
                    val offsets = children.map { fraction(it.getAttribute("offset")) }
                    require(offsets.zipWithNext().all { (a, b) -> a <= b })
                    children.forEach { stop ->
                        require(stop.hasAttribute("stop-color"))
                        if (stop.hasAttribute("stop-opacity")) require(stop.getAttribute("stop-opacity").toDouble().let { it.isFinite() && it in 0.0..1.0 })
                    }
                    val coordinates = if (e.localName == "linearGradient") listOf("x1", "y1", "x2", "y2") else listOf("cx", "cy", "r")
                    coordinates.filter(e::hasAttribute).forEach { fraction(e.getAttribute(it)) }
                    if (e.hasAttribute("r")) require(fraction(e.getAttribute("r")) > 0)
                }
                "pattern" -> {
                    require(e.parentNode is Element && (e.parentNode as Element).localName == "defs")
                    textureSize(e)
                }
                "clipPath" -> {
                    require(e.parentNode is Element && (e.parentNode as Element).localName == "defs")
                    require(e.hasAttribute("id") && children.isNotEmpty() && children.all { it.localName in setOf("rect", "circle", "ellipse", "path", "polygon") })
                }
                "use" -> {
                    require(children.isEmpty()) { "<use> must not have children" }
                    require(e.hasAttribute("href") || e.hasAttributeNS("http://www.w3.org/1999/xlink", "href")) { "<use> requires local href" }
                }
                "image" -> {
                    require(children.isEmpty()) { "<image> must not have children" }
                    val rawHref = e.getAttribute("href").ifBlank { e.getAttributeNS("http://www.w3.org/1999/xlink", "href") }.trim()
                    require(isSafeRelativeAssetPath(rawHref)) { "<image> requires safe relative asset href: $rawHref" }
                }
                "stop" -> require(e.parentNode is Element && (e.parentNode as Element).localName in setOf("linearGradient", "radialGradient"))
            }
            children.forEach(::visit)
        }
        visit(root)
        require(paintRefs.all { ids[it]?.localName in setOf("linearGradient", "radialGradient", "pattern") }) { "missing or unsupported paint server" }
        require(clipRefs.all { ids[it]?.localName == "clipPath" }) { "missing or unsupported clipPath" }
        require(useRefs.all { targetId -> ids[targetId]?.localName in setOf("g", "rect", "circle", "ellipse", "path", "line", "polyline", "polygon") }) { "missing or unsupported <use> target" }
        return root
    }
    /** A bounded alpha tile, continuous procedural microrelief, or pack-local bitmap material texture. */
    fun textureSize(e: Element): Pair<Int, Int> {
        require(e.localName == "pattern" && e.getAttribute("patternUnits") == "userSpaceOnUse")
        for (i in 0 until e.attributes.length) require(e.attributes.item(i).nodeName in setOf("id", "width", "height", "patternUnits") || e.attributes.item(i).nodeName.startsWith("data-"))
        val width = e.getAttribute("width").toInt(); val height = e.getAttribute("height").toInt()
        if (e.hasAttribute("data-generator")) {
            require(width == ContinuousMaterial.WIDTH && height == ContinuousMaterial.HEIGHT)
            continuousSpec(e)
            return width to height
        }
        if (e.hasAttribute("data-texture")) {
            require(children(e).isEmpty()) { "bitmap texture pattern must have empty children" }
            require(width in 8..2048 && height in 8..2048) { "bitmap texture pattern tile dimensions out of range" }
            require(isSafeRelativeAssetPath(e.getAttribute("data-texture"))) { "unsafe bitmap texture path: ${e.getAttribute("data-texture")}" }
            return width to height
        }
        require(width in 8..128 && height in 8..128)
        val pixels = children(e); require(pixels.size in 1..1024 && pixels.all { it.localName == "rect" })
        pixels.forEach { rect ->
            require(children(rect).isEmpty())
            for (i in 0 until rect.attributes.length) require(rect.attributes.item(i).nodeName in setOf("x", "y", "width", "height", "fill", "opacity"))
            fun n(key: String) = rect.getAttribute(key).toDouble().also { require(it.isFinite()) }
            val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
            require(x >= 0 && y >= 0 && w in 0.25..4.0 && h in 0.25..4.0 && x + w <= width && y + h <= height)
            require(Regex("#[0-9a-fA-F]{6}").matches(rect.getAttribute("fill")))
            if (rect.hasAttribute("opacity")) require(n("opacity") in 0.0..1.0)
        }
        return width to height
    }
    fun continuousSpec(e: Element): ContinuousMaterial.Spec {
        require(e.getAttribute("data-generator") == "continuous-grain-v1")
        require(children(e).isEmpty())
        val admitted = setOf("id", "width", "height", "patternUnits", "data-generator", "data-kind", "data-seed", "data-design")
        for (i in 0 until e.attributes.length) require(e.attributes.item(i).nodeName in admitted)
        require(e.getAttribute("width") == "1200" && e.getAttribute("height") == "800")
        require(e.getAttribute("patternUnits") == "userSpaceOnUse")
        return ContinuousMaterial.Spec(e.getAttribute("data-kind"), e.getAttribute("data-seed").toLong())
    }
    fun children(e: Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
    fun fraction(value: String): Double = value.trim().let { text ->
        val number = if (text.endsWith('%')) text.dropLast(1).toDouble() / 100.0 else text.toDouble()
        number.also { require(it.isFinite() && it in 0.0..1.0) { "gradient fraction outside bounds" } }
    }
}

data class DynamicStateBinding(
    val id: String,
    val mode: String, // "rotate-map" | "highlight-button" | "led-binding"
    val componentId: String? = null,
    val settingKey: String? = null,
    val buttonKey: String? = null,
    val angleMap: Map<String, Double> = emptyMap(),
    val pivotX: Double? = null,
    val pivotY: Double? = null,
    val activeOpacity: Double = 1.0,
    val inactiveOpacity: Double = 0.0,
)

data class PanelControl(
    val id: String,
    val viewId: String,
    val label: String,
    val labelZh: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val kind: String,
    val buttonKey: String?,
    val componentId: String?,
    val settingKey: String?,
    val basis: List<String>,
    val bindingStatus: String,
    val firmwareBinding: String,
    val interaction: String,
    val displayPort: String?,
    val role: String? = null,
    val cornerRadius: Double = 0.0,
    val clipShape: String? = null,
    val labels: Map<String, String> = emptyMap(),
) {
    fun localizedLabel(localeCode: String = ThemeLocalization.activeLocaleCode()): String =
        ThemeLocalization.resolve(
            localeCode = localeCode,
            defaultEnglish = label,
            zhFallback = labelZh,
            overrides = labels,
        )
}

data class LayerCondition(val id: String, val componentId: String, val settingKey: String, val expected: Boolean)

data class CameraPanel(
    val id: String,
    val title: String,
    val svg: String,
    val width: Double,
    val height: Double,
    val controls: List<PanelControl>,
    val layers: List<LayerCondition> = emptyList(),
    val titleZh: String = "",
    val titles: Map<String, String> = emptyMap(),
    val artwork: String? = null,
    val dynamicStates: List<DynamicStateBinding> = emptyList(),
    val covers: List<PanelCover> = emptyList(),
) {
    fun isRasterArtwork(): Boolean =
        !artwork.isNullOrBlank() && !artwork.endsWith(".svg", ignoreCase = true)

    fun localizedShortTitle(localeCode: String = ThemeLocalization.activeLocaleCode()): String {
        val enPart = if (" / " in title) title.substringAfter(" / ").trim() else title.trim()
        val zhPart = titleZh.takeIf { it.isNotBlank() }
            ?: if (" / " in title) title.substringBefore(" /").trim() else ""
        return ThemeLocalization.resolve(
            localeCode = localeCode,
            defaultEnglish = enPart,
            zhFallback = zhPart,
            overrides = titles,
        )
    }

    fun localizedCaption(localeCode: String = ThemeLocalization.activeLocaleCode()): String {
        val enPart = if (" / " in title) title.substringAfter(" / ").trim() else title.trim()
        val localized = localizedShortTitle(localeCode)
        val normLang = ThemeLocalization.languagePrefix(localeCode)
        return if (normLang == "zh" && enPart.isNotBlank() && !localized.equals(enPart, ignoreCase = true)) {
            "⠿  $enPart · $localized"
        } else {
            "⠿  $localized"
        }
    }
}

object PanelCatalog {
    val buttonKeys = setOf("power", "menu", "back", "up", "down", "left", "right", "play", "shutterHalf", "shutterFull", "delete", "rearDialPress", "movieMode", "drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress")
    val viewIds = linkedSetOf("rear", "top", "front", "ports-left", "storage-right", "battery-bottom")
    private val idPattern = Regex("[a-z][a-z0-9-]*")
    fun load() = parse(PanelResources.bytes("catalog.json"))
    fun latchingButtonKeys(panels: List<CameraPanel>): Set<String> =
        panels.flatMap { it.controls }
            .filter { it.buttonKey != null && it.interaction == "latching" }
            .mapTo(linkedSetOf()) { requireNotNull(it.buttonKey) }
    val latchingButtonKeys: Set<String> by lazy { latchingButtonKeys(load()) }
    fun latchingSettingControls(panels: List<CameraPanel>): List<PanelControl> =
        panels.flatMap { it.controls }
            .filter { it.buttonKey == null && it.componentId != null && it.settingKey != null && it.interaction == "latching" }
    fun parse(bytes: ByteArray): List<CameraPanel> {
        require(bytes.size <= PanelResources.MAX_BYTES)
        val tree = Formats.json.readTree(bytes)
        require(tree.path("schema").isIntegralNumber && tree.path("schema").asInt() == 2 && tree.path("model").asText() == "GFX100S" && tree.path("modelCode").asText() == "FF200003") { "wrong panel schema/model" }
        require(!tree.has("windows")) { "studio catalog uses views, not windows" }
        val views = tree.path("views"); require(views.isArray && views.size() == 6)
        fun number(node: JsonNode, key: String): Double {
            require(node.path(key).isNumber) { "numeric panel field required: $key" }
            return node.path(key).asDouble().also { require(it.isFinite()) }
        }
        fun text(node: JsonNode, key: String, limit: Int = 200): String {
            val value = node.path(key); require(value.isTextual)
            return value.asText().also { require(it.isNotBlank() && it.length <= limit) }
        }
        fun optional(node: JsonNode, key: String) = node.path(key).takeIf { !it.isMissingNode && !it.isNull }?.let { text(node, key, 100) }
        val result = views.map { v ->
            val id = text(v, "id"); require(id in viewIds)
            val width = number(v, "width"); val height = number(v, "height")
            require(width in 100.0..4096.0 && height in 100.0..4096.0)
            val controls = v.path("controls"); require(controls.isArray && controls.size() in 1..128)
            val parsed = controls.map { c ->
                val controlId = text(c, "id"); require(idPattern.matches(controlId))
                require(text(c, "viewId") == id && !c.has("windowId") && !c.has("openWindow"))
                val x = number(c, "x"); val y = number(c, "y"); val cw = number(c, "width"); val ch = number(c, "height")
                require(x >= 0 && y >= 0 && cw > 0 && ch > 0 && x + cw <= width && y + ch <= height) { "control outside viewBox" }
                val hit = c.path("hitbox"); require(hit.isObject)
                require(listOf("x", "y", "width", "height").map { number(hit, it) } == listOf(x, y, cw, ch)) { "hotspot/catalog bounds differ" }
                val key = optional(c, "buttonKey"); require(key == null || key in buttonKeys)
                val component = optional(c, "componentId"); val setting = optional(c, "settingKey")
                require((setting == null || component != null) && !(key != null && component != null))
                val basis = c.path("basis"); require(basis.isArray && basis.size() > 0 && basis.all { it.isTextual && it.asText().startsWith("GFX-") })
                val display = optional(c, "displayPort"); require(display == null || display in setOf("display.main", "display.shoulder", "display.evf"))
                require(text(c, "firmwareBinding") == "UNKNOWN")
                PanelControl(controlId, id, text(c, "label"), text(c, "labelZh"), x, y, cw, ch, text(c, "kind"), key, component, setting, basis.map { it.asText() }, text(c, "bindingStatus"), "UNKNOWN", c.path("interaction").asText("inspect"), display)
            }
            val layers = v.path("layers").takeIf { !it.isMissingNode }?.let { list ->
                require(list.isArray && list.size() <= 8)
                list.map { layer ->
                    val layerId = text(layer, "id"); require(idPattern.matches(layerId))
                    val whenNode = layer.path("visibleWhen"); require(whenNode.isObject && whenNode.path("equals").isBoolean)
                    LayerCondition(layerId, text(whenNode, "componentId"), text(whenNode, "settingKey"), whenNode.path("equals").asBoolean())
                }.also { require(it.map { layer -> layer.id }.distinct().size == it.size) }
            } ?: emptyList()
            val dynamicStates = v.path("dynamicStates").takeIf { !it.isMissingNode && !it.isNull }?.let { list ->
                require(list.isArray && list.size() <= 32) { "dynamicStates max 32 entries per panel" }
                list.map { ds ->
                    val dsid = text(ds, "id", 64); require(idPattern.matches(dsid))
                    val mode = text(ds, "mode", 32)
                    require(mode in setOf("rotate-map", "highlight-button", "led-binding")) { "unsupported dynamicState mode: $mode" }
                    val compId = optional(ds, "componentId")
                    val setKey = optional(ds, "settingKey")
                    val btnKey = optional(ds, "buttonKey")?.also { require(it in buttonKeys) { "unknown dynamicState buttonKey: $it" } }
                    val angleMap = linkedMapOf<String, Double>()
                    val amNode = ds.path("angleMap")
                    if (amNode.isObject) {
                        amNode.properties().forEach { (k, valNode) ->
                            require(valNode.isNumber && valNode.asDouble().isFinite()) { "angleMap value must be finite number" }
                            angleMap[k] = valNode.asDouble()
                        }
                    }
                    when (mode) {
                        "rotate-map" -> require(compId != null && setKey != null && angleMap.isNotEmpty())
                        "highlight-button" -> require(btnKey != null)
                        "led-binding" -> require(compId != null && setKey != null)
                    }
                    fun optNum(key: String): Double? = ds.path(key).takeIf { it.isNumber }?.asDouble()?.also { require(it.isFinite()) }
                    DynamicStateBinding(
                        id = dsid,
                        mode = mode,
                        componentId = compId,
                        settingKey = setKey,
                        buttonKey = btnKey,
                        angleMap = angleMap,
                        pivotX = optNum("pivotX"),
                        pivotY = optNum("pivotY"),
                        activeOpacity = (optNum("activeOpacity") ?: 1.0).coerceIn(0.0, 1.0),
                        inactiveOpacity = (optNum("inactiveOpacity") ?: 0.0).coerceIn(0.0, 1.0),
                    )
                }.also { require(it.map { d -> d.id }.distinct().size == it.size) }
            } ?: emptyList()
            CameraPanel(id, text(v, "title"), text(v, "svg").also { require(it == "$id.svg") }, width, height, parsed, layers,
                dynamicStates = dynamicStates, covers = PanelCover.parse(v, parsed, width, height))
        }
        require(result.map { it.id }.toSet() == viewIds && result.map { it.id }.distinct().size == result.size)
        val controls = result.flatMap { it.controls }
        require(controls.map { it.id }.distinct().size == controls.size)
        require(controls.mapNotNull { it.buttonKey }.let { it.toSet() == buttonKeys && it.size == buttonKeys.size })
        return result
    }
    fun validateArtwork(panel: CameraPanel, allPanels: List<CameraPanel> = load()): Element {
        val svg = PanelResources.document(PanelResources.bytes(panel.svg))
        val box = svg.getAttribute("viewBox").trim().split(Regex("[ ,]+" )).map(String::toDouble)
        require(box == listOf(0.0, 0.0, panel.width, panel.height)) { "SVG/catalog viewBox mismatch" }
        val controls = mutableMapOf<String, Element>(); val allIds = mutableSetOf<String>()
        fun visit(e: Element) {
            if (e.hasAttribute("data-control-proxy")) {
                val target = e.getAttribute("data-control-proxy")
                require(e.parentNode === svg && e.localName == "rect" && e.hasAttribute("id") &&
                    !e.hasAttribute("transform") && e.getAttribute("fill") == "none" && e.getAttribute("stroke") == "none" &&
                    allPanels.any { face -> face.controls.any { it.id == target && it.buttonKey != null } }) {
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
                val id = e.getAttribute("data-control-id")
                require(e.getAttribute("id") == id && e.getAttribute("data-view-id") == panel.id && controls.put(id, e) == null)
            }
            PanelResources.children(e).forEach(::visit)
        }
        visit(svg)
        require(controls.keys == panel.controls.map { it.id }.toSet()) { "SVG/catalog controls disagree" }
        panel.controls.forEach { c ->
            val hits = PanelResources.children(controls.getValue(c.id)).filter { it.getAttribute("data-hitbox") == "true" }
            require(hits.size == 1 && hits.single().localName == "rect")
            val hit = hits.single()
            require(listOf("x", "y", "width", "height").map { hit.getAttribute(it).toDouble() } == listOf(c.x, c.y, c.width, c.height))
            require(hit.getAttribute("fill") == "none" && hit.getAttribute("stroke") == "none") { "artwork hotspot must be transparent" }
        }
        require(panel.layers.all { it.id in allIds })
        require(panel.covers.all { "${it.id}-open" in allIds && "${it.id}-closed" in allIds }) { "missing cover artwork in ${panel.id}" }
        require(panel.dynamicStates.all { it.id in allIds }) { "missing dynamicState id in ${panel.id}" }
        return svg
    }
}
