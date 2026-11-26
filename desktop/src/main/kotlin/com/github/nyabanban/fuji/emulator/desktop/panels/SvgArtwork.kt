package com.github.nyabanban.fuji.emulator.desktop.panels

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.effect.DropShadow
import javafx.scene.effect.InnerShadow
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.Ellipse
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.shape.Polyline
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.Shape
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.text.Font
import javafx.scene.text.FontSmoothingType
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import javafx.scene.transform.Rotate
import javafx.scene.transform.Scale
import javafx.scene.transform.Translate
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Retained native JavaFX vector & hybrid-material artwork renderer.
 *
 * Vector SVG is the strongly recommended primary path (resolution-independent scaling,
 * crisp engraving, tiny footprint). Within SVG, `<pattern data-texture="textures/...">`
 * admits bounded pack-local bitmap material textures (leatherette, brushed metal, carbon, wood).
 * Optional full-view raster artwork ([renderRasterView]) is supported with strict size &
 * dimension bounds for convenience, though vector SVG remains preferred.
 */
object SvgArtwork {
    private val log = LoggerFactory.getLogger(SvgArtwork::class.java)

    const val MAX_TEXTURE_BYTES: Int = 2 * 1024 * 1024
    const val MAX_TEXTURE_DIM: Int = 1024
    const val MAX_RASTER_VIEW_BYTES: Int = 4 * 1024 * 1024
    const val MAX_RASTER_VIEW_DIM: Int = 4096
    private const val MAX_USE_DEPTH: Int = 4

    fun gradients(root: Element): Map<String, Paint> {
        val result = linkedMapOf<String, Paint>()
        PanelResources.children(root).filter { it.localName == "defs" }.flatMap(PanelResources::children)
            .filter { it.localName in setOf("linearGradient", "radialGradient") }
            .forEach { e ->
                fun fraction(name: String, default: Double) =
                    if (e.hasAttribute(name)) PanelResources.fraction(e.getAttribute(name)) else default
                val stops = PanelResources.children(e).map { s ->
                    val base = Color.web(s.getAttribute("stop-color"))
                    val opacity = if (s.hasAttribute("stop-opacity")) s.getAttribute("stop-opacity").toDouble() else 1.0
                    Stop(PanelResources.fraction(s.getAttribute("offset")), Color(base.red, base.green, base.blue, base.opacity * opacity))
                }
                val paint = when (e.localName) {
                    "linearGradient" -> LinearGradient(fraction("x1", 0.0), fraction("y1", 0.0), fraction("x2", 1.0), fraction("y2", 0.0), true, CycleMethod.NO_CYCLE, stops)
                    "radialGradient" -> RadialGradient(0.0, 0.0, fraction("cx", 0.5), fraction("cy", 0.5), fraction("r", 0.5), true, CycleMethod.NO_CYCLE, stops)
                    else -> error("unsupported paint server")
                }
                require(result.put(e.getAttribute("id"), paint) == null)
            }
        return result
    }

    /** Deterministic source-over rasterization of original bounded SVG microtexture. */
    fun texturePixels(pattern: Element): IntArray {
        val (width, height) = PanelResources.textureSize(pattern)
        if (pattern.hasAttribute("data-generator")) return ContinuousMaterial.pixels(PanelResources.continuousSpec(pattern))
        val pixels = IntArray(width * height)
        PanelResources.children(pattern).forEach { rect ->
            val x = rect.getAttribute("x").toDouble(); val y = rect.getAttribute("y").toDouble()
            val w = rect.getAttribute("width").toDouble(); val h = rect.getAttribute("height").toDouble()
            val alpha = if (rect.hasAttribute("opacity")) rect.getAttribute("opacity").toDouble() else 1.0
            val rgb = rect.getAttribute("fill").drop(1).toInt(16)
            for (py in floor(y).toInt() until ceil(y + h).toInt()) for (px in floor(x).toInt() until ceil(x + w).toInt()) {
                val coverage = (minOf(px + 1.0, x + w) - maxOf(px.toDouble(), x)).coerceAtLeast(0.0) *
                    (minOf(py + 1.0, y + h) - maxOf(py.toDouble(), y)).coerceAtLeast(0.0)
                val sa = alpha * coverage; val old = pixels[py * width + px]; val da = (old ushr 24) / 255.0
                val a = sa + da * (1.0 - sa)
                fun channel(shift: Int): Int = if (a == 0.0) 0 else ((((rgb ushr shift) and 255) * sa + ((old ushr shift) and 255) * da * (1.0 - sa)) / a).roundToInt().coerceIn(0, 255)
                pixels[py * width + px] = ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        return pixels
    }

    // Bounded shared full-view procedural material paints (LRU up to 6 entries).
    // Mutated only on the JavaFX thread during view construction; not a per-frame cache.
    private val continuousPaints = object : LinkedHashMap<ContinuousMaterial.Spec, ImagePattern>(6, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContinuousMaterial.Spec, ImagePattern>?) = size > 6
    }

    private fun paints(root: Element, assetLoader: ((String) -> ByteArray)?): Map<String, Paint> =
        gradients(root).toMutableMap().apply {
            PanelResources.children(root).filter { it.localName == "defs" }.flatMap(PanelResources::children)
                .filter { it.localName == "pattern" }
                .forEach { pattern ->
                    val (width, height) = PanelResources.textureSize(pattern)
                    val paint: Paint = when {
                        pattern.hasAttribute("data-generator") -> {
                            continuousPaints.getOrPut(PanelResources.continuousSpec(pattern)) {
                                val image = WritableImage(width, height)
                                image.pixelWriter.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), texturePixels(pattern), 0, width)
                                ImagePattern(image, 0.0, 0.0, width.toDouble(), height.toDouble(), false)
                            }
                        }
                        pattern.hasAttribute("data-texture") -> {
                            loadBitmapTexturePattern(pattern, width, height, assetLoader)
                        }
                        else -> {
                            val image = WritableImage(width, height)
                            image.pixelWriter.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), texturePixels(pattern), 0, width)
                            ImagePattern(image, 0.0, 0.0, width.toDouble(), height.toDouble(), false)
                        }
                    }
                    require(put(pattern.getAttribute("id"), paint) == null)
                }
        }

    private fun loadBitmapTexturePattern(
        pattern: Element,
        tileWidth: Int,
        tileHeight: Int,
        assetLoader: ((String) -> ByteArray)?,
    ): Paint {
        val relPath = pattern.getAttribute("data-texture").trim()
        if (!PanelResources.isSafeRelativeAssetPath(relPath) || assetLoader == null) {
            log.warn(
                "[FALLBACK][theme] Ignoring SVG material texture '{}' (safePath={}, loaderPresent={}) — falling back to neutral fill",
                relPath,
                PanelResources.isSafeRelativeAssetPath(relPath),
                assetLoader != null,
            )
            return Color.web("#2a2a2a")
        }
        return try {
            val bytes = assetLoader(relPath)
            require(bytes.isNotEmpty() && bytes.size <= MAX_TEXTURE_BYTES) {
                "material texture '$relPath' exceeds ${MAX_TEXTURE_BYTES} bytes (${bytes.size})"
            }
            val image = Image(ByteArrayInputStream(bytes))
            require(!image.isError && image.width >= 1.0 && image.height >= 1.0) {
                "material texture '$relPath' failed to decode"
            }
            require(image.width <= MAX_TEXTURE_DIM && image.height <= MAX_TEXTURE_DIM) {
                "material texture '$relPath' dimensions ${image.width.toInt()}x${image.height.toInt()} exceed ${MAX_TEXTURE_DIM}x${MAX_TEXTURE_DIM}"
            }
            ImagePattern(image, 0.0, 0.0, tileWidth.toDouble(), tileHeight.toDouble(), false)
        } catch (err: Exception) {
            log.warn(
                "[FALLBACK][theme] Failed to load SVG material texture '{}': {} — falling back to neutral fill",
                relPath,
                err.message ?: err.javaClass.simpleName,
            )
            Color.web("#2a2a2a")
        }
    }

    private class RenderContext(
        val paints: Map<String, Paint>,
        val clipPaths: Map<String, Element>,
        val elementsById: Map<String, Element>,
        val assetLoader: ((String) -> ByteArray)?,
    )

    private fun collectElementsById(root: Element): Map<String, Element> {
        val map = linkedMapOf<String, Element>()
        fun visit(e: Element) {
            if (e.hasAttribute("id")) map[e.getAttribute("id")] = e
            PanelResources.children(e).forEach(::visit)
        }
        visit(root)
        return map
    }

    private fun collectClipPaths(root: Element): Map<String, Element> {
        val map = linkedMapOf<String, Element>()
        PanelResources.children(root).filter { it.localName == "defs" }.flatMap(PanelResources::children)
            .filter { it.localName == "clipPath" && it.hasAttribute("id") }
            .forEach { map[it.getAttribute("id")] = it }
        return map
    }

    fun render(root: Element): Group = render(root, assetLoader = null)

    fun render(root: Element, assetLoader: ((String) -> ByteArray)? = null): Group {
        val ctx = RenderContext(
            paints = paints(root, assetLoader),
            clipPaths = collectClipPaths(root),
            elementsById = collectElementsById(root),
            assetLoader = assetLoader,
        )
        return node(root, emptyMap(), ctx, emptySet(), 0) as Group
    }

    /**
     * Renders an optional full-view raster body artwork (PNG/JPG/WEBP) sized to [width] x [height].
     * Vector SVG is strongly recommended over full-view bitmaps; this method exists for optional
     * raster-mode body packs and enforces strict byte and pixel dimension caps.
     */
    fun renderRasterView(bytes: ByteArray, width: Double, height: Double): Group {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RASTER_VIEW_BYTES) {
            "raster artwork size ${bytes.size} bytes exceeds limit $MAX_RASTER_VIEW_BYTES"
        }
        val image = Image(ByteArrayInputStream(bytes))
        require(!image.isError && image.width >= 1.0 && image.height >= 1.0) {
            "raster artwork failed to decode"
        }
        require(image.width <= MAX_RASTER_VIEW_DIM && image.height <= MAX_RASTER_VIEW_DIM) {
            "raster artwork dimensions ${image.width.toInt()}x${image.height.toInt()} exceed ${MAX_RASTER_VIEW_DIM}x${MAX_RASTER_VIEW_DIM}"
        }
        val view = ImageView(image).apply {
            fitWidth = width
            fitHeight = height
            isPreserveRatio = false
            isSmooth = true
            isMouseTransparent = true
        }
        return Group(view).apply { isMouseTransparent = true }
    }

    private fun loadEmbeddedImageView(
        e: Element,
        x: Double,
        y: Double,
        width: Double?,
        height: Double?,
        assetLoader: ((String) -> ByteArray)?,
    ): Node {
        val relPath = e.getAttribute("href").ifBlank { e.getAttributeNS("http://www.w3.org/1999/xlink", "href") }.trim()
        if (!PanelResources.isSafeRelativeAssetPath(relPath) || assetLoader == null) {
            log.warn(
                "[FALLBACK][theme] Ignoring SVG <image> '{}' (safePath={}, loaderPresent={})",
                relPath,
                PanelResources.isSafeRelativeAssetPath(relPath),
                assetLoader != null,
            )
            return Group().apply { isMouseTransparent = true }
        }
        return try {
            val bytes = assetLoader(relPath)
            require(bytes.isNotEmpty() && bytes.size <= MAX_RASTER_VIEW_BYTES) {
                "embedded SVG image '$relPath' exceeds $MAX_RASTER_VIEW_BYTES bytes (${bytes.size})"
            }
            val image = Image(ByteArrayInputStream(bytes))
            require(!image.isError && image.width >= 1.0 && image.height >= 1.0) {
                "embedded SVG image '$relPath' failed to decode"
            }
            require(image.width <= MAX_RASTER_VIEW_DIM && image.height <= MAX_RASTER_VIEW_DIM) {
                "embedded SVG image '$relPath' dimensions ${image.width.toInt()}x${image.height.toInt()} exceed ${MAX_RASTER_VIEW_DIM}x${MAX_RASTER_VIEW_DIM}"
            }
            ImageView(image).apply {
                this.x = x
                this.y = y
                fitWidth = width ?: image.width
                fitHeight = height ?: image.height
                isPreserveRatio = false
                isSmooth = true
                isMouseTransparent = true
            }
        } catch (err: Exception) {
            log.warn(
                "[FALLBACK][theme] Failed to load SVG <image> '{}': {}",
                relPath,
                err.message ?: err.javaClass.simpleName,
            )
            Group().apply { isMouseTransparent = true }
        }
    }

    private fun buildClipNode(clipElement: Element, ctx: RenderContext): Node {
        val shapes = PanelResources.children(clipElement).map { child ->
            node(child, emptyMap(), ctx, emptySet(), 0).apply {
                if (this is Shape) {
                    fill = Color.WHITE
                    stroke = null
                }
            }
        }
        return if (shapes.size == 1) shapes.single() else Group(shapes)
    }

    private fun node(
        e: Element,
        inherited: Map<String, String>,
        ctx: RenderContext,
        useStack: Set<String>,
        useDepth: Int,
    ): Node {
        val style = inherited.toMutableMap()
        listOf(
            "fill", "stroke", "stroke-width", "stroke-dasharray",
            "font-size", "font-weight", "font-family", "text-anchor",
            "stroke-linecap", "stroke-linejoin", "fill-opacity", "stroke-opacity",
        ).forEach { if (e.hasAttribute(it)) style[it] = e.getAttribute(it) }
        fun n(key: String, default: Double = 0.0) =
            if (e.hasAttribute(key)) e.getAttribute(key).toDouble().also { require(it.isFinite()) } else default
        val result: Node = when (e.localName) {
            "svg", "g" -> Group().apply {
                PanelResources.children(e)
                    .filter { it.localName !in setOf("defs", "title", "desc") }
                    .forEach { children += node(it, style, ctx, useStack, useDepth) }
            }
            "use" -> {
                require(useDepth < MAX_USE_DEPTH) { "<use> nesting exceeds maximum depth $MAX_USE_DEPTH" }
                val rawHref = e.getAttribute("href").ifBlank { e.getAttributeNS("http://www.w3.org/1999/xlink", "href") }.trim()
                require(rawHref.startsWith("#")) { "<use> requires local #id href" }
                val targetId = rawHref.substring(1)
                require(targetId !in useStack) { "cyclic <use> reference detected for #$targetId" }
                val target = requireNotNull(ctx.elementsById[targetId]) { "missing <use> target #$targetId" }
                val cloned = node(target, style, ctx, useStack + targetId, useDepth + 1)
                val ox = n("x", 0.0)
                val oy = n("y", 0.0)
                Group(cloned).apply {
                    if (ox != 0.0 || oy != 0.0) transforms += Translate(ox, oy)
                }
            }
            "rect" -> Rectangle(n("x"), n("y"), n("width"), n("height")).apply { arcWidth = 2 * n("rx"); arcHeight = 2 * n("ry", n("rx")) }
            "circle" -> Circle(n("cx"), n("cy"), n("r"))
            "ellipse" -> Ellipse(n("cx"), n("cy"), n("rx"), n("ry"))
            "line" -> Line(n("x1"), n("y1"), n("x2"), n("y2"))
            "path" -> SVGPath().apply { content = e.getAttribute("d") }
            "polyline", "polygon" -> {
                val points = e.getAttribute("points").trim().split(Regex("""[ ,\s]+""")).map { it.toDouble().also { value -> require(value.isFinite()) } }
                require(points.size % 2 == 0)
                if (e.localName == "polyline") Polyline().apply { getPoints().setAll(points) } else Polygon().apply { getPoints().setAll(points) }
            }
            "text", "tspan" -> Text(e.textContent).apply {
                fontSmoothingType = FontSmoothingType.GRAY
                x = n("x"); y = n("y")
                font = Font.font(StudioTypography.family, if (style["font-weight"] in setOf("bold", "600", "700")) FontWeight.BOLD else FontWeight.NORMAL, style["font-size"]?.toDouble() ?: 14.0)
                when (style["text-anchor"]) { "middle" -> x -= layoutBounds.width / 2; "end" -> x -= layoutBounds.width }
            }
            "image" -> loadEmbeddedImageView(e, n("x"), n("y"), if (e.hasAttribute("width")) n("width") else null, if (e.hasAttribute("height")) n("height") else null, ctx.assetLoader)
            else -> error("unsupported SVG element ${e.localName}")
        }
        if (result is Shape) {
            fun paint(key: String, default: Paint?): Paint? {
                val value = style[key] ?: return default
                if (value == "none") return null
                val alpha = (style["$key-opacity"]?.toDouble() ?: 1.0).also { require(it.isFinite() && it in 0.0..1.0) }
                val base = if (value.startsWith("url(#") && value.endsWith(')')) requireNotNull(ctx.paints[value.substring(5, value.length - 1)]) else Color.web(value)
                if (alpha == 1.0) return base
                fun faded(stops: List<Stop>) = stops.map { Stop(it.offset, it.color.deriveColor(0.0, 1.0, 1.0, alpha)) }
                return when (base) {
                    is Color -> base.deriveColor(0.0, 1.0, 1.0, alpha)
                    is LinearGradient -> LinearGradient(base.startX, base.startY, base.endX, base.endY, base.isProportional, base.cycleMethod, faded(base.stops))
                    is RadialGradient -> RadialGradient(base.focusAngle, base.focusDistance, base.centerX, base.centerY, base.radius, base.isProportional, base.cycleMethod, faded(base.stops))
                    is ImagePattern -> base
                    else -> error("unsupported paint")
                }
            }
            result.fill = paint("fill", if (result is Line) null else Color.BLACK)
            result.stroke = paint("stroke", null)
            result.strokeWidth = style["stroke-width"]?.toDouble()?.also { require(it.isFinite() && it >= 0) } ?: 1.0
            result.strokeLineCap = when (style["stroke-linecap"]) { "round" -> StrokeLineCap.ROUND; "square" -> StrokeLineCap.SQUARE; else -> StrokeLineCap.BUTT }
            result.strokeLineJoin = when (style["stroke-linejoin"]) { "round" -> StrokeLineJoin.ROUND; "bevel" -> StrokeLineJoin.BEVEL; else -> StrokeLineJoin.MITER }
            style["stroke-dasharray"]?.trim()?.takeIf { it.isNotEmpty() && it != "none" }?.let { raw ->
                val dashes = raw.split(Regex("""[ ,\s]+""")).map { it.toDouble() }
                if (dashes.isNotEmpty()) result.strokeDashArray.setAll(dashes)
            }
        }
        result.opacity = n("opacity", 1.0).also { require(it in 0.0..1.0) }
        if (e.hasAttribute("clip-path")) {
            val rawClip = e.getAttribute("clip-path").trim()
            if (rawClip.startsWith("url(#") && rawClip.endsWith(")")) {
                val clipId = rawClip.substring(5, rawClip.length - 1)
                ctx.clipPaths[clipId]?.let { clipEl ->
                    result.clip = buildClipNode(clipEl, ctx)
                }
            }
        }
        if (e.hasAttribute("data-effect")) {
            val radius = n("data-shadow-radius", 6.0).coerceIn(0.5, 32.0)
            val offsetX = n("data-shadow-offset-x", 0.0).coerceIn(-20.0, 20.0)
            val offsetY = n("data-shadow-offset-y", 2.0).coerceIn(-20.0, 20.0)
            val shadowOpacity = n("data-shadow-opacity", 0.38).coerceIn(0.0, 1.0)
            val shadowColor = runCatching { Color.web(e.getAttribute("data-shadow-color").ifBlank { "#000000" }) }
                .getOrDefault(Color.BLACK)
                .deriveColor(0.0, 1.0, 1.0, shadowOpacity)
            result.effect = when (e.getAttribute("data-effect")) {
                "shadow" -> DropShadow(radius, offsetX, offsetY, shadowColor)
                "inner-shadow" -> InnerShadow(radius, offsetX, offsetY, shadowColor)
                else -> null
            }
        }
        if (e.hasAttribute("transform")) {
            val transforms = e.getAttribute("transform")
            val regex = Regex("""(translate|scale|rotate)\s*\(([^)]*)\)""")
            require(regex.replace(transforms, "").trim().isEmpty())
            regex.findAll(transforms).forEach { m ->
                val values = m.groupValues[2].trim().split(Regex("""[ ,\s]+""")).map(String::toDouble)
                require(values.all { it.isFinite() })
                result.transforms += when (m.groupValues[1]) {
                    "translate" -> { require(values.size in 1..2); Translate(values[0], values.getOrElse(1) { 0.0 }) }
                    "scale" -> { require(values.size in 1..2); Scale(values[0], values.getOrElse(1) { values[0] }) }
                    else -> { require(values.size == 1 || values.size == 3); Rotate(values[0], values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 }) }
                }
            }
        }
        if (e.hasAttribute("id")) result.id = "svg-${e.getAttribute("id")}"
        if (e.hasAttribute("data-control-proxy")) {
            result.styleClass += "studio-control-proxy-target"
            result.properties["data-control-proxy"] = e.getAttribute("data-control-proxy")
        }
        result.isMouseTransparent = true
        return result
    }
}
