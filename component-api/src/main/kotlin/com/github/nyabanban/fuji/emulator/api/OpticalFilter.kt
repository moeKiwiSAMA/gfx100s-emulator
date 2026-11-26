package com.github.nyabanban.fuji.emulator.api

import java.util.Locale

/**
 * Reusable scratch buffers passed into [OpticalFilterElement.applySpatialLinearRgb] so spatial
 * optical filters (Star diffraction, Black Mist halation, Graduated ND, etc.) execute with
 * zero per-frame heap allocations on the CMOS hot path.
 */
class OpticalFilterScratch {
    var r: FloatArray = FloatArray(0)
        private set
    var g: FloatArray = FloatArray(0)
        private set
    var b: FloatArray = FloatArray(0)
        private set
    var auxR: FloatArray = FloatArray(0)
        private set
    var auxG: FloatArray = FloatArray(0)
        private set
    var auxB: FloatArray = FloatArray(0)
        private set

    fun ensurePrimary(pixelCount: Int) {
        if (r.size < pixelCount) {
            r = FloatArray(pixelCount)
            g = FloatArray(pixelCount)
            b = FloatArray(pixelCount)
        }
    }

    fun ensureAux(pixelCount: Int) {
        if (auxR.size < pixelCount) {
            auxR = FloatArray(pixelCount)
            auxG = FloatArray(pixelCount)
            auxB = FloatArray(pixelCount)
        }
    }
}

/**
 * Physical entrance-pupil aperture mask shapes for geometrical-optics shaped bokeh filters.
 *
 * When placed in front of a wide-aperture lens, out-of-focus point lights physically convolve with
 * the pupil cutout shape (e.g. 5-pointed star, heart, hexagon, or mirror-lens donut) while
 * in-focus points remain tack-sharp pinpoints.
 */
enum class BokehMaskShape(
    val id: String,
    val labelZh: String,
    val labelEn: String,
) {
    OFF("OFF", "关闭", "Off"),
    STAR_5("STAR_5", "五角星散景", "5-Point Star"),
    HEART("HEART", "爱心散景", "Heart"),
    HEXAGON("HEXAGON", "六边形散景", "Hexagon"),
    RING("RING", "折返环散景", "Mirror Ring"),
    CUSTOM("CUSTOM", "自定义散景", "Custom"),
    ;

    companion object {
        val ALL_IDS: List<String> = entries.map { it.id }

        fun parse(raw: String?): BokehMaskShape? {
            val norm = raw?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
                ?: return null
            if (norm.isEmpty()) return null
            return when (norm) {
                "OFF", "NONE", "0", "CLEAR", "CIRCLE" -> OFF
                "STAR", "STAR_5", "STAR5", "5STAR", "5PT" -> STAR_5
                "HEART", "LOVE" -> HEART
                "HEX", "HEXAGON", "6" -> HEXAGON
                "RING", "DONUT", "MIRROR", "REFLEX" -> RING
                "CUSTOM" -> CUSTOM
                else -> entries.firstOrNull { it.id == norm }
            }
        }
    }
}

/**
 * Pure geometrical-optics entrance-pupil transmission sampler consumed by the lens bokeh kernel.
 *
 * Coordinates `(ux, uy)` lie in the normalized entrance-pupil unit disk (`ux^2 + uy^2 <= 1`),
 * with `+uy` pointing down in image space (so `-uy` is top of frame).
 */
interface OpticalPupilMask {
    val shape: BokehMaskShape
    val angleDeg: Double
    val scale: Double
    val active: Boolean
    /** Stable key for O(1) optical cache invalidation. */
    val cacheKey: Long

    /** Returns pupil transmission weight in `[0.0, 1.0]` at normalized unit-disk coords `(ux, uy)`. */
    fun pupilWeight(ux: Double, uy: Double): Double
}

/**
 * Composite entrance-pupil mask formed by multiplying the transmission of two or more stacked
 * [OpticalPupilMask] elements (e.g. a ring obstruction + a star cutout or custom plugin mask).
 *
 * Reports [BokehMaskShape.CUSTOM] so Vulkan built-in single-shape shaders automatically route to
 * the exact CPU LUT bokeh evaluator instead of silently dropping stacked masks.
 */
class CompositeOpticalPupilMask(
    val masks: List<OpticalPupilMask>,
) : OpticalPupilMask {
    init {
        require(masks.isNotEmpty()) { "CompositeOpticalPupilMask requires at least one mask" }
    }

    override val shape: BokehMaskShape = BokehMaskShape.CUSTOM
    override val angleDeg: Double = masks.first().angleDeg
    override val scale: Double = masks.minOf { it.scale }
    override val active: Boolean = masks.any { it.active }
    override val cacheKey: Long = run {
        var h = 0x517CC1B727220A95L xor masks.size.toLong()
        for (m in masks) {
            if (!m.active) continue
            h = (h xor m.cacheKey) * 0x9E3779B97F4A7C15UL.toLong()
        }
        h xor (h ushr 31)
    }

    override fun pupilWeight(ux: Double, uy: Double): Double {
        var weight = 1.0
        for (m in masks) {
            if (!m.active) continue
            weight *= m.pupilWeight(ux, uy).coerceIn(0.0, 1.0)
            if (weight <= 0.0) return 0.0
        }
        return weight
    }
}

/**
 * Supported interactive parameter control kinds for self-describing optical filter plugins (SPI v2).
 */
enum class FilterParamType {
    NUMBER,
    ENUM,
    BOOLEAN,
    TEXT,
}

/**
 * Localized choice option for an [FilterParamType.ENUM] parameter specification.
 */
data class FilterParamChoice(
    val value: String,
    val labelEn: String,
    val labelZh: String = labelEn,
    val labels: Map<String, String> = emptyMap(),
) {
    /** Binary-compatible 3-arg constructor for Java SPI v2 plugins. */
    constructor(value: String, labelEn: String, labelZh: String) : this(value, labelEn, labelZh, emptyMap())

    /** Resolves localized choice label for [localeCode] with strict English default fallback. */
    fun localizedLabel(localeCode: String = "en"): String {
        PluginMetadata.resolveFromLocaleMap(labels, localeCode)?.let { return it }
        if (PluginMetadata.isChineseLocale(localeCode) && labelZh.isNotBlank()) return labelZh
        PluginMetadata.resolveFromLocaleMap(labels, "en")?.let { return it }
        return labelEn.ifBlank { value }
    }

    fun toStateMap(): Map<String, Any> = linkedMapOf<String, Any>(
        "value" to value,
        "labelEn" to labelEn,
        "labelZh" to labelZh,
    ).apply {
        if (labels.isNotEmpty()) put("labels", labels)
    }
}

/**
 * Self-describing parameter schema entry exported by [OpticalFilterElementProvider.parameterSchema]
 * so the Desktop Filter Rack UI can automatically generate interactive sliders, combo boxes,
 * checkboxes, and text fields for any third-party filter JAR without hardcoded per-filter UI.
 */
data class FilterParamSpec(
    val key: String,
    val type: FilterParamType,
    val labelEn: String,
    val labelZh: String = labelEn,
    val descriptionEn: String = "",
    val descriptionZh: String = descriptionEn,
    val defaultValue: Any,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String = "",
    val choices: List<FilterParamChoice> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
) {
    /** Binary-compatible 12-arg constructor for Java SPI v2 plugins. */
    constructor(
        key: String,
        type: FilterParamType,
        labelEn: String,
        labelZh: String,
        descriptionEn: String,
        descriptionZh: String,
        defaultValue: Any,
        min: Double?,
        max: Double?,
        step: Double?,
        unit: String,
        choices: List<FilterParamChoice>,
    ) : this(
        key = key,
        type = type,
        labelEn = labelEn,
        labelZh = labelZh,
        descriptionEn = descriptionEn,
        descriptionZh = descriptionZh,
        defaultValue = defaultValue,
        min = min,
        max = max,
        step = step,
        unit = unit,
        choices = choices,
        labels = emptyMap(),
        descriptions = emptyMap(),
    )

    init {
        require(key.isNotBlank()) { "FilterParamSpec key must not be blank" }
        if (type == FilterParamType.NUMBER && min != null && max != null) {
            require(min <= max) { "FilterParamSpec '$key' min ($min) > max ($max)" }
        }
        if (type == FilterParamType.ENUM) {
            require(choices.isNotEmpty()) { "ENUM FilterParamSpec '$key' requires non-empty choices" }
        }
    }

    /** Resolves localized parameter label for [localeCode] with strict English default fallback. */
    fun localizedLabel(localeCode: String = "en"): String {
        PluginMetadata.resolveFromLocaleMap(labels, localeCode)?.let { return it }
        if (PluginMetadata.isChineseLocale(localeCode) && labelZh.isNotBlank()) return labelZh
        PluginMetadata.resolveFromLocaleMap(labels, "en")?.let { return it }
        return labelEn.ifBlank { key }
    }

    /** Resolves localized parameter description for [localeCode] with strict English default fallback. */
    fun localizedDescription(localeCode: String = "en"): String {
        PluginMetadata.resolveFromLocaleMap(descriptions, localeCode)?.let { return it }
        if (PluginMetadata.isChineseLocale(localeCode) && descriptionZh.isNotBlank()) return descriptionZh
        PluginMetadata.resolveFromLocaleMap(descriptions, "en")?.let { return it }
        return descriptionEn
    }

    fun toStateMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "key" to key,
        "type" to type.name,
        "labelEn" to labelEn,
        "labelZh" to labelZh,
        "descriptionEn" to descriptionEn,
        "descriptionZh" to descriptionZh,
        "defaultValue" to defaultValue,
        "unit" to unit,
    ).apply {
        if (min != null) put("min", min)
        if (max != null) put("max", max)
        if (step != null) put("step", step)
        if (choices.isNotEmpty()) put("choices", choices.map { it.toStateMap() })
        if (labels.isNotEmpty()) put("labels", labels)
        if (descriptions.isNotEmpty()) put("descriptions", descriptions)
    }
}

/**
 * Per-frame physical optical & pose context passed into SPI v2 spatial filter elements
 * (depth buffer, lens aperture/focal/focus, camera roll/pitch, and frame sequence index).
 */
data class OpticalFilterContext(
    val depthMeters: FloatArray? = null,
    val fNumber: Double = 4.0,
    val focalLengthMm: Double = 50.0,
    val focusDistanceM: Double = 2.5,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val frameIndex: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpticalFilterContext) return false
        val sameDepth = if (depthMeters == null) other.depthMeters == null else other.depthMeters != null && depthMeters.contentEquals(other.depthMeters)
        return sameDepth &&
            fNumber == other.fNumber &&
            focalLengthMm == other.focalLengthMm &&
            focusDistanceM == other.focusDistanceM &&
            rollDeg == other.rollDeg &&
            pitchDeg == other.pitchDeg &&
            frameIndex == other.frameIndex
    }

    override fun hashCode(): Int {
        var result = depthMeters?.contentHashCode() ?: 0
        result = 31 * result + fNumber.hashCode()
        result = 31 * result + focalLengthMm.hashCode()
        result = 31 * result + focusDistanceM.hashCode()
        result = 31 * result + rollDeg.hashCode()
        result = 31 * result + pitchDeg.hashCode()
        result = 31 * result + frameIndex.hashCode()
        return result
    }

    companion object {
        val DEFAULT = OpticalFilterContext()
    }
}

/**
 * Pluggable front-of-lens / in-path optical filter element operating in the physical linear
 * radiance domain BEFORE CMOS shot noise, read noise, and ADC quantization.
 *
 * Pure scalar attenuators (such as uniform Neutral Density filters) set [hasSpatialEffect] = `false`
 * so they incur zero per-pixel spatial pass overhead and fold directly into sensor irradiance
 * transmittance (driving real camera AE/metering naturally).
 */
interface OpticalFilterElement {
    /** Stable machine identifier of this filter type (e.g. `"nd"`, `"star"`, `"black-mist"`, `"gnd"`, `"bokeh-mask"`). */
    val id: String

    /** Human-readable display label. */
    val displayName: String

    /** Whether this filter element is currently active in the optical path. */
    val enabled: Boolean

    /**
     * Scalar linear irradiance transmittance in `(0.0, 1.0]` applied uniformly across the frame.
     * Must return `1.0` when [enabled] is `false`.
     */
    fun transmittanceMultiplier(): Double

    /**
     * `true` when [applySpatialLinearRgb] modifies pixel radiance spatially (e.g. Star spikes,
     * Black Mist halation, Graduated ND). `false` for pure scalar or pupil-only filters so callers
     * bypass spatial buffer copies completely.
     */
    val hasSpatialEffect: Boolean

    /**
     * `true` only for built-in elements whose exact math is implemented in the Vulkan Compute
     * Pass 0 shader (`nd`, `gnd`, `black-mist`, `star`, `uv`, and built-in `bokeh-mask` shapes).
     * Third-party plugins default to `false` and should override [vulkanSpirvPass] to run on GPU.
     */
    val supportsVulkanBuiltin: Boolean get() = false

    /**
     * Optional custom SPIR-V compute shader pass (Plugin SPI v1.0) executed in-place on Vulkan
     * `DEVICE_LOCAL` VRAM for the current frame dimensions `width x height`.
     *
     * When non-null, this filter executes on Vulkan GPU instead of forcing CPU fallback.
     * Plugins MUST still implement [applySpatialLinearRgb] as the deterministic CPU fallback
     * when Vulkan is unavailable (`opticsBackend: cpu`) or shader compilation fails.
     */
    fun vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec? = null

    /**
     * Applies this filter's spatial linear-radiance transformation in-place on planar `(red, green, blue)`
     * arrays of length `width * height`. Called only when [enabled] && [hasSpatialEffect].
     */
    fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    )

    /**
     * Context-aware SPI v2 spatial transform overload supplying live depth, lens parameters,
     * camera orientation, and frame index. Defaults to calling the 6-arg overload for 100%
     * backward compatibility with SPI v1 plugins.
     */
    fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
        context: OpticalFilterContext,
    ) {
        applySpatialLinearRgb(red, green, blue, width, height, scratch)
    }

    /** Optional entrance-pupil bokeh cutout mask provided by this element, or `null`. */
    fun pupilMask(): OpticalPupilMask? = null

    /** Concise human-readable active badge (e.g. `"ND8 (3.0 EV)"`, `"Star 6pt"`, `"Bokeh ★"`), or `null` if inactive. */
    fun summaryBadge(): String?

    /** Structured readback map exposed via component state and HTTP Control API. */
    fun stateMap(): Map<String, Any?>
}

/**
 * ServiceLoader SPI for registering pluggable [OpticalFilterElement] implementations without
 * coupling the optical filter component to concrete filter classes.
 */
interface OpticalFilterElementProvider {
    /** Unique filter type key (e.g. `"nd"`, `"star"`, `"black-mist"`, `"gnd"`, `"bokeh-mask"`). */
    val filterType: String

    /** Default display name for UI/CLI discovery. */
    val displayName: String

    /** Self-describing plugin metadata (SPI v2). */
    val metadata: PluginMetadata get() = PluginMetadata.DEFAULT

    /** Self-describing interactive parameter schema for auto-generated UI controls (SPI v2). */
    fun parameterSchema(): List<FilterParamSpec> = emptyList()

    /** Instantiates a configured [OpticalFilterElement] from normalized component configuration. */
    fun create(config: Map<String, Any?>): OpticalFilterElement
}

/**
 * Decoupled hardware port exposed by `:components:optical-filter` at [PORT_NAME].
 *
 * Represents a physical front-of-lens / matte-box optical filter stack (ND, Star / Cross-Screen,
 * Black Mist, Graduated ND, Aperture Pupil Bokeh Mask, or custom third-party [OpticalFilterElement]
 * plugins). Consumed by CMOS / optical pipelines uniformly across all lens modes
 * (`GENERIC_SIMULATED`, `MANUAL`, `REAL_FIRMWARE`) and bare-sensor setups.
 */
interface OpticalFilterStackPort {
    /** Monotonic revision incremented on every configuration change (O(1) cache invalidation key). */
    val revision: Long

    /** Master filter-holder bypass flag (`false` = physically swung out / bypassed). */
    val enabled: Boolean

    /** Ordered list of currently active filter elements in the holder stack. */
    fun activeElements(): List<OpticalFilterElement>

    /** Combined scalar transmittance multiplier `(0.0, 1.0]` across all active filters (`1.0` when bypassed). */
    fun totalTransmittance(): Double

    /** `true` iff [enabled] and at least one active element has [OpticalFilterElement.hasSpatialEffect]. */
    fun hasActiveSpatialEffects(): Boolean

    /** Active entrance-pupil bokeh mask (single or [CompositeOpticalPupilMask]), or `null` when bypassed/off. */
    fun activePupilMask(): OpticalPupilMask?

    /** Applies all active spatial filters in stack order in-place on linear RGB radiance planes. */
    fun applySpatialStack(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    )

    /** Applies all active spatial filters in stack order with per-frame [OpticalFilterContext] (SPI v2). */
    fun applySpatialStack(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
        context: OpticalFilterContext,
    ) {
        applySpatialStack(red, green, blue, width, height, scratch)
    }

    /** Rescans external filter plugin JAR directories at runtime and returns a telemetry summary map. */
    fun rescanExternalPlugins(): Map<String, Any?> = emptyMap()

    /** Structured stack telemetry snapshot. */
    fun stackState(): Map<String, Any?>

    companion object {
        const val PORT_NAME = "optical-filter.stack"
    }
}

/**
 * Physical optical quality presets for a front-of-lens UV protective filter.
 *
 * Models real radiometric & optical differences between top-tier multi-coated glass
 * ([PRO_MRC]), ordinary single-coated glass ([STANDARD]), and cheap uncoated soda-lime
 * glass ([CHEAP_POOR] with Fresnel loss, veiling glare, point-symmetric double-reflection
 * ghosting, micro-softness MTF blur, and green-yellow color cast).
 */
enum class UvFilterPreset(
    val id: String,
    val transmittance: Double,
    val veilingGlare: Double,
    val ghostIntensity: Double,
    val softness: Double,
    val tintR: Double,
    val tintG: Double,
    val tintB: Double,
) {
    OFF("OFF", 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
    PRO_MRC("PRO_MRC", 0.996, 0.002, 0.0, 0.0, 1.0, 1.0, 1.0),
    STANDARD("STANDARD", 0.965, 0.035, 0.008, 0.06, 0.995, 1.005, 0.985),
    CHEAP_POOR("CHEAP_POOR", 0.895, 0.16, 0.085, 0.35, 0.97, 1.02, 0.94),
    CUSTOM("CUSTOM", 0.965, 0.035, 0.008, 0.06, 1.0, 1.0, 1.0),
    ;

    companion object {
        val ALL_IDS: List<String> = entries.map { it.id }

        fun parse(raw: String?): UvFilterPreset? {
            val norm = raw?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
                ?: return null
            if (norm.isEmpty()) return null
            return when (norm) {
                "OFF", "NONE", "0", "CLEAR" -> OFF
                "PRO", "PRO_MRC", "MRC", "NANO", "GOOD", "TOP", "T*", "T_STAR", "B+W" -> PRO_MRC
                "STD", "STANDARD", "NORMAL", "MID", "SINGLE" -> STANDARD
                "CHEAP", "CHEAP_POOR", "POOR", "BAD", "TRASH", "LOW", "UNCOATED" -> CHEAP_POOR
                "CUSTOM" -> CUSTOM
                else -> entries.firstOrNull { it.id == norm }
            }
        }
    }
}

/**
 * Single ordered filter slot inside the matte-box filter rack (`slots`).
 */
data class OpticalFilterSlotSpec(
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, Any> = emptyMap(),
) {
    fun toMap(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["type"] = type
        out["enabled"] = enabled
        for ((k, v) in params) {
            if (k != "type" && k != "enabled") out[k] = v
        }
        return out
    }
}

/**
 * Dependency-free deterministic JSON codec for ordered [OpticalFilterSlotSpec] lists.
 * Keeps `component-api` free of external JSON library dependencies while guaranteeing
 * bounded (`<= 12` slots, `<= 4096` chars) round-trip serialization for `ValueKind.TEXT`.
 */
object OpticalFilterSlotCodec {
    const val MAX_SLOTS: Int = 12
    const val MAX_JSON_CHARS: Int = 4096

    fun encode(slots: List<OpticalFilterSlotSpec>): String {
        if (slots.isEmpty()) return ""
        require(slots.size <= MAX_SLOTS) { "Filter stack exceeds maximum $MAX_SLOTS slots" }
        val sb = StringBuilder(slots.size * 64)
        sb.append('[')
        slots.forEachIndexed { idx, slot ->
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"type\":")
            appendJsonString(sb, slot.type.trim().lowercase(Locale.ROOT))
            sb.append(",\"enabled\":")
            sb.append(slot.enabled)
            for ((k, v) in slot.params) {
                if (k == "type" || k == "enabled") continue
                sb.append(',')
                appendJsonString(sb, k)
                sb.append(':')
                appendJsonValue(sb, v)
            }
            sb.append('}')
        }
        sb.append(']')
        val text = sb.toString()
        require(text.length <= MAX_JSON_CHARS) { "Encoded filter slots exceed $MAX_JSON_CHARS chars" }
        return text
    }

    fun parse(json: String?): List<OpticalFilterSlotSpec> {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        require(trimmed.length <= MAX_JSON_CHARS) { "Filter slots JSON exceeds $MAX_JSON_CHARS chars" }
        val parser = MiniJsonParser(trimmed)
        val raw = parser.parseArray()
        parser.expectEnd()
        require(raw.size <= MAX_SLOTS) { "Filter stack exceeds maximum $MAX_SLOTS slots" }
        return raw.map { item -> fromMap(item) }
    }

    fun fromMap(map: Map<*, *>): OpticalFilterSlotSpec {
        val rawType = (map["type"] as? String)?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Filter slot missing non-blank 'type'")
        val normalizedType = when (rawType) {
            "bokeh", "pupil", "mask" -> "bokeh-mask"
            "mist", "blackmist" -> "black-mist"
            "grad" -> "gnd"
            "cross" -> "star"
            else -> rawType
        }
        val enabled = (map["enabled"] as? Boolean) ?: true
        val params = LinkedHashMap<String, Any>()
        for ((k, v) in map) {
            val key = k as? String ?: continue
            if (key == "type" || key == "enabled" || v == null) continue
            when (v) {
                is Boolean, is String -> params[key] = v
                is Number -> {
                    val d = v.toDouble()
                    require(d.isFinite()) { "Slot param '$key' must be finite" }
                    params[key] = if (v is Int || v is Long) v.toLong() else d
                }
                else -> throw IllegalArgumentException("Unsupported slot param type for '$key'")
            }
        }
        return OpticalFilterSlotSpec(type = normalizedType, enabled = enabled, params = params)
    }

    private fun appendJsonString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code >= 0x20) sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun appendJsonValue(sb: StringBuilder, value: Any) {
        when (value) {
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value)
            is Number -> {
                val d = value.toDouble()
                require(d.isFinite()) { "Non-finite numeric value in filter slot" }
                if (d == d.toLong().toDouble() && d in -1e12..1e12) {
                    sb.append(String.format(Locale.ROOT, "%.1f", d))
                } else {
                    sb.append(String.format(Locale.ROOT, "%.4f", d).trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it })
                }
            }
            is String -> appendJsonString(sb, value)
            else -> appendJsonString(sb, value.toString())
        }
    }

    private class MiniJsonParser(private val s: String) {
        private var pos = 0

        fun expectEnd() {
            skipWs()
            require(pos == s.length) { "Unexpected trailing characters in filter slots JSON at $pos" }
        }

        fun parseArray(): List<Map<String, Any>> {
            skipWs()
            require(pos < s.length && s[pos] == '[') { "Expected '[' at $pos" }
            pos++
            skipWs()
            val out = ArrayList<Map<String, Any>>()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += parseObject()
                skipWs()
                require(pos < s.length) { "Unterminated array in filter slots JSON" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseObject(): Map<String, Any> {
            skipWs()
            require(pos < s.length && s[pos] == '{') { "Expected '{' at $pos" }
            pos++
            skipWs()
            val out = LinkedHashMap<String, Any>()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "Expected ':' at $pos" }
                pos++
                val value = parseValue()
                if (value != null) out[key] = value
                skipWs()
                require(pos < s.length) { "Unterminated object in filter slots JSON" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            require(pos < s.length) { "Unexpected EOF in filter slots JSON" }
            return when (s[pos]) {
                '"' -> parseString()
                't' -> {
                    require(s.startsWith("true", pos)) { "Invalid token at $pos" }
                    pos += 4
                    true
                }
                'f' -> {
                    require(s.startsWith("false", pos)) { "Invalid token at $pos" }
                    pos += 5
                    false
                }
                'n' -> {
                    require(s.startsWith("null", pos)) { "Invalid token at $pos" }
                    pos += 4
                    null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected value token '${s[pos]}' at $pos")
            }
        }

        private fun parseString(): String {
            skipWs()
            require(pos < s.length && s[pos] == '"') { "Expected '\"' at $pos" }
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "Unterminated escape in string" }
                        when (val esc = s[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string in filter slots JSON")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            return if (isFloat) {
                token.toDouble().also { require(it.isFinite()) { "Non-finite number '$token'" } }
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
