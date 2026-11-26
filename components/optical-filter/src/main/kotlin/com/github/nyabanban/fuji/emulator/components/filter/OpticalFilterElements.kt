package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.BuiltinOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.FilterParamChoice
import com.github.nyabanban.fuji.emulator.api.FilterParamSpec
import com.github.nyabanban.fuji.emulator.api.FilterParamType
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.PluginMetadata
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure physical Neutral Density filter element (`2^(-stops)` scalar attenuation, zero spatial pass).
 */
class NdFilterElement(
    val preset: NdFilterPreset,
    val stops: Double,
) : OpticalFilterElement {
    private val clampedStops = NdFilterSpec.clampStops(stops)
    private val trans = NdFilterSpec.transmittance(clampedStops)

    override val id: String = TYPE_ID
    override val displayName: String = "ND Neutral Density"
    override val enabled: Boolean = clampedStops > 1e-4
    override val hasSpatialEffect: Boolean = false
    override val supportsVulkanBuiltin: Boolean = true

    override fun transmittanceMultiplier(): Double = if (enabled) trans else 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        // Pure scalar attenuation: zero spatial overhead.
    }

    override fun summaryBadge(): String? = if (!enabled) null else NdFilterSpec.formatSummary(clampedStops)

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to enabled,
        "preset" to preset.id,
        "stops" to clampedStops,
        "factor" to NdFilterSpec.factor(clampedStops),
        "opticalDensity" to NdFilterSpec.opticalDensity(clampedStops),
        "transmittance" to trans,
        "transmittancePercent" to NdFilterSpec.transmittancePercent(clampedStops),
        "summary" to NdFilterSpec.formatSummary(clampedStops),
    )

    companion object {
        const val TYPE_ID = "nd"
    }
}

class NdFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = NdFilterElement.TYPE_ID
    override val displayName: String = "ND Neutral Density"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Physical scalar Neutral Density attenuator (2^-stops transmittance) for long exposures and wide-aperture daylight shooting.",
        descriptionZh = "物理纯标量中灰减光镜（2^-stops 透光率衰减），适用于白天大光圈拍摄与长曝光控制。",
        names = mapOf(
            "en" to "ND Neutral Density",
            "zh_CN" to "ND 中灰减光镜",
            "ja_JP" to "ND 減光フィルター",
            "de_DE" to "ND-Neutraldichtefilter",
            "fr_FR" to "Filtre ND (Densité neutre)",
            "es_ES" to "Filtro ND (Densidad neutra)",
        ),
        descriptions = mapOf(
            "en" to "Physical scalar Neutral Density attenuator (2^-stops transmittance) for long exposures and wide-aperture daylight shooting.",
            "zh_CN" to "物理纯标量中灰减光镜（2^-stops 透光率衰减），适用于白天大光圈拍摄与长曝光控制。",
            "ja_JP" to "物理的な純スカラーND減光フィルター（透過率 2^-stops）。晴天時の開放絞り撮影や長時間露光に最適です。",
            "de_DE" to "Physikalischer skalarer Neutraldichtefilter (2^-Blenden Transmission) für Langzeitbelichtungen und Offenblende bei Tageslicht.",
            "fr_FR" to "Filtre à densité neutre scalaire physique (transmission 2^-IL) pour poses longues et grandes ouvertures en plein jour.",
            "es_ES" to "Filtro físico de densidad neutra escalar (transmitancia 2^-pasos) para largas exposiciones y grandes aperturas a plena luz.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "preset",
            type = FilterParamType.ENUM,
            labelEn = "Preset",
            labelZh = "档位预设",
            defaultValue = NdFilterPreset.ND8.id,
            choices = NdFilterPreset.entries.map { FilterParamChoice(it.id, it.id, it.id) },
            labels = mapOf(
                "en" to "Preset",
                "zh_CN" to "档位预设",
                "ja_JP" to "プリセット",
                "de_DE" to "Voreinstellung",
                "fr_FR" to "Préréglage",
                "es_ES" to "Preajuste",
            ),
        ),
        FilterParamSpec(
            key = "stops",
            type = FilterParamType.NUMBER,
            labelEn = "Density",
            labelZh = "减光档位",
            defaultValue = 3.0,
            min = NdFilterSpec.MIN_STOPS,
            max = NdFilterSpec.MAX_STOPS,
            step = 0.1,
            unit = "EV",
            labels = mapOf(
                "en" to "Density",
                "zh_CN" to "减光档位",
                "ja_JP" to "減光段数",
                "de_DE" to "Dichte",
                "fr_FR" to "Densité",
                "es_ES" to "Densidad",
            ),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val rawPreset = (config["ndPreset"] ?: config["preset"]) as? String
        val rawStops = (config["ndStops"] ?: config["stops"]) as? Number
        val parsedPreset = NdFilterSpec.parsePreset(rawPreset)
        val stops = NdFilterSpec.clampStops(
            rawStops?.toDouble() ?: parsedPreset?.fixedStops ?: 0.0,
        )
        val preset = parsedPreset ?: NdFilterSpec.presetForStops(stops)
        return NdFilterElement(preset = preset, stops = stops)
    }
}

/**
 * True geometrical-optics entrance-pupil aperture cutout mask (`STAR_5`, `HEART`, `HEXAGON`, `RING`).
 *
 * Does NOT run a fake post-process stamp; instead feeds [OpticalPupilMask.pupilWeight] directly into
 * the lens 2D depth-aware bokeh convolution kernel so in-focus objects remain tack-sharp while
 * out-of-focus point lights physically convolve into crisp 5-pointed stars / hearts / rings!
 */
class BokehMaskFilterElement(
    shape: BokehMaskShape,
    angleDeg: Double,
    scale: Double,
) : OpticalFilterElement, OpticalPupilMask by BuiltinOpticalPupilMask(shape, angleDeg, scale) {
    override val id: String = TYPE_ID
    override val displayName: String = "Aperture Bokeh Mask"
    override val enabled: Boolean = shape != BokehMaskShape.OFF
    override val hasSpatialEffect: Boolean = false
    override val supportsVulkanBuiltin: Boolean get() = shape != BokehMaskShape.CUSTOM

    override fun transmittanceMultiplier(): Double = 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        // Pupil-plane bokeh cutout is evaluated directly inside the lens bokeh kernel.
    }

    override fun pupilMask(): OpticalPupilMask? = if (enabled) this else null

    override fun summaryBadge(): String? = when (shape) {
        BokehMaskShape.OFF -> null
        BokehMaskShape.STAR_5 -> "Bokeh ★5"
        BokehMaskShape.HEART -> "Bokeh ♥"
        BokehMaskShape.HEXAGON -> "Bokeh ⬡"
        BokehMaskShape.RING -> "Bokeh ◎"
        BokehMaskShape.CUSTOM -> "Bokeh Custom"
    }

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to enabled,
        "shape" to shape.id,
        "angleDeg" to angleDeg,
        "scale" to scale.coerceIn(0.40, 1.0),
    )

    companion object {
        const val TYPE_ID = "bokeh-mask"

        fun star5Weight(rx: Double, ry: Double, scale: Double): Double = BuiltinOpticalPupilMask.star5Weight(rx, ry, scale)
        fun heartWeight(rx: Double, ry: Double, scale: Double): Double = BuiltinOpticalPupilMask.heartWeight(rx, ry, scale)
        fun hexagonWeight(rx: Double, ry: Double, scale: Double): Double = BuiltinOpticalPupilMask.hexagonWeight(rx, ry, scale)
        fun ringWeight(rx: Double, ry: Double, scale: Double): Double = BuiltinOpticalPupilMask.ringWeight(rx, ry, scale)
    }
}

class BokehMaskFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = BokehMaskFilterElement.TYPE_ID
    override val displayName: String = "Aperture Bokeh Mask"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Geometrical-optics entrance-pupil aperture cutout mask that shapes out-of-focus bokeh highlights (star, heart, hexagon, ring).",
        descriptionZh = "几何光学入瞳孔径散景遮罩，将焦外点光源物理卷积为五角星、爱心、六边形或折返环光斑。",
        names = mapOf(
            "en" to "Aperture Bokeh Mask",
            "zh_CN" to "孔径散景遮罩",
            "ja_JP" to "ボケ形状マスク",
            "de_DE" to "Bokeh-Blendenmaske",
            "fr_FR" to "Masque de bokeh pupillaire",
            "es_ES" to "Máscara de bokeh de pupila",
        ),
        descriptions = mapOf(
            "en" to "Geometrical-optics entrance-pupil aperture cutout mask that shapes out-of-focus bokeh highlights (star, heart, hexagon, ring).",
            "zh_CN" to "几何光学入瞳孔径散景遮罩，将焦外点光源物理卷积为五角星、爱心、六边形或折返环光斑。",
            "ja_JP" to "幾何光学の入射瞳マスク。アウトフォーカスの点光源を星・ハート・六角形・リング状のボケに変換します。",
            "de_DE" to "Geometrisch-optische Eintrittspupillenmaske zur Formung unscharfer Bokeh-Lichter (Stern, Herz, Sechseck, Ring).",
            "fr_FR" to "Masque d'ouverture pupillaire en optique géométrique sculptant le bokeh hors foyer (étoile, cœur, hexagone, anneau).",
            "es_ES" to "Máscara óptica de pupila de entrada que moldea los reflejos bokeh desenfocados (estrella, corazón, hexágono, anillo).",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "shape",
            type = FilterParamType.ENUM,
            labelEn = "Shape",
            labelZh = "光斑形状",
            defaultValue = BokehMaskShape.STAR_5.id,
            choices = listOf(
                FilterParamChoice(
                    BokehMaskShape.STAR_5.id, "5-Point Star", "五角星散景",
                    mapOf("en" to "5-Point Star", "zh_CN" to "五角星散景", "ja_JP" to "五芒星", "de_DE" to "5-Zack-Stern", "fr_FR" to "Étoile 5 branches", "es_ES" to "Estrella 5 puntas"),
                ),
                FilterParamChoice(
                    BokehMaskShape.HEART.id, "Heart", "爱心散景",
                    mapOf("en" to "Heart", "zh_CN" to "爱心散景", "ja_JP" to "ハート", "de_DE" to "Herz", "fr_FR" to "Cœur", "es_ES" to "Corazón"),
                ),
                FilterParamChoice(
                    BokehMaskShape.HEXAGON.id, "Hexagon", "六边形散景",
                    mapOf("en" to "Hexagon", "zh_CN" to "六边形散景", "ja_JP" to "六角形", "de_DE" to "Sechseck", "fr_FR" to "Hexagone", "es_ES" to "Hexágono"),
                ),
                FilterParamChoice(
                    BokehMaskShape.RING.id, "Mirror Ring", "折返环散景",
                    mapOf("en" to "Mirror Ring", "zh_CN" to "折返环散景", "ja_JP" to "リングボケ", "de_DE" to "Spiegelring", "fr_FR" to "Anneau catadioptrique", "es_ES" to "Anillo catadióptrico"),
                ),
            ),
            labels = mapOf("en" to "Shape", "zh_CN" to "光斑形状", "ja_JP" to "形状", "de_DE" to "Form", "fr_FR" to "Forme", "es_ES" to "Forma"),
        ),
        FilterParamSpec(
            key = "angleDeg",
            type = FilterParamType.NUMBER,
            labelEn = "Angle",
            labelZh = "旋转角",
            defaultValue = 0.0,
            min = -180.0,
            max = 180.0,
            step = 1.0,
            unit = "°",
            labels = mapOf("en" to "Angle", "zh_CN" to "旋转角", "ja_JP" to "回転角", "de_DE" to "Winkel", "fr_FR" to "Angle", "es_ES" to "Ángulo"),
        ),
        FilterParamSpec(
            key = "scale",
            type = FilterParamType.NUMBER,
            labelEn = "Pupil Scale",
            labelZh = "入瞳尺寸",
            defaultValue = 0.96,
            min = 0.40,
            max = 1.0,
            step = 0.02,
            labels = mapOf("en" to "Pupil Scale", "zh_CN" to "入瞳尺寸", "ja_JP" to "瞳径スケール", "de_DE" to "Pupillengröße", "fr_FR" to "Échelle pupillaire", "es_ES" to "Escala de pupila"),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val shape = BokehMaskShape.parse((config["bokehMask"] ?: config["shape"]) as? String) ?: BokehMaskShape.OFF
        val angleDeg = ((config["bokehMaskAngleDeg"] ?: config["angleDeg"]) as? Number)?.toDouble() ?: 0.0
        val scale = ((config["bokehMaskScale"] ?: config["scale"]) as? Number)?.toDouble() ?: 0.96
        return BokehMaskFilterElement(shape = shape, angleDeg = angleDeg, scale = scale)
    }
}

/**
 * Cross-Screen / Star diffraction grating filter (`4`, `6`, or `8` linear diffraction spikes
 * radiating from specular highlights in linear radiance space). Zero overhead when disabled.
 */
class StarFilterElement(
    override val enabled: Boolean,
    val points: Int,
    val strength: Double,
    val threshold: Double,
    val angleDeg: Double,
    val length: Double,
) : OpticalFilterElement {
    private val clampedPoints = when {
        points <= 4 -> 4
        points <= 6 -> 6
        else -> 8
    }
    private val clampedStrength = strength.coerceIn(0.0, 2.0)
    private val clampedThreshold = threshold.coerceIn(0.10, 2.0)
    private val clampedLength = length.coerceIn(0.02, 0.35)
    private val active = enabled && clampedStrength > 1e-4

    override val id: String = TYPE_ID
    override val displayName: String = "Star Cross-Screen"
    override val hasSpatialEffect: Boolean = active
    override val supportsVulkanBuiltin: Boolean = true

    override fun transmittanceMultiplier(): Double = 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        if (!active || width < 4 || height < 4) return
        val count = width * height
        scratch.ensurePrimary(count)
        if (NativeOpticalFilterSimdBridge.applyStarFilter(
                red = red,
                green = green,
                blue = blue,
                accR = scratch.r,
                accG = scratch.g,
                accB = scratch.b,
                width = width,
                height = height,
                points = clampedPoints,
                strength = clampedStrength.toFloat(),
                threshold = clampedThreshold.toFloat(),
                lengthFrac = clampedLength.toFloat(),
                angleDeg = angleDeg,
            )
        ) {
            return
        }
        val accR = scratch.r
        val accG = scratch.g
        val accB = scratch.b
        accR.fill(0f, 0, count)
        accG.fill(0f, 0, count)
        accB.fill(0f, 0, count)

        val rayCount = clampedPoints / 2
        val maxLenPx = (min(width, height) * clampedLength).roundToInt().coerceIn(4, 180)
        val baseAngle = Math.toRadians(angleDeg)
        val angleStep = PI / rayCount.toDouble()
        val cosRays = DoubleArray(rayCount) { k -> cos(baseAngle + k * angleStep) }
        val sinRays = DoubleArray(rayCount) { k -> sin(baseAngle + k * angleStep) }
        val decayLut = FloatArray(maxLenPx + 1) { step ->
            val t = step.toDouble() / maxLenPx.toDouble()
            ((1.0 - t) * exp(-2.6 * t)).toFloat()
        }

        // Subsample stride keeps large saturated highlight regions fast (< 1.5 ms at 1024x576)
        // while preserving crisp diffraction spikes on every pinpoint specular source.
        val thr = clampedThreshold.toFloat()
        val gain = (clampedStrength * 0.28 / rayCount.toDouble()).toFloat()
        var hotPixels = 0
        val maxHotBudget = 2400

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                val r = red[idx]
                val g = green[idx]
                val b = blue[idx]
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (lum <= thr) continue
                // Local-peak thinning once bright pixel count grows large (prevents sky blowup).
                if (hotPixels > 400 && ((x xor y) and 1) != 0) continue
                if (hotPixels >= maxHotBudget) break

                val excess = (lum - thr) / max(lum, 1e-4f)
                val sr = r * excess * gain
                val sg = g * excess * gain
                val sb = b * excess * gain
                hotPixels++

                for (k in 0 until rayCount) {
                    val dx = cosRays[k]
                    val dy = sinRays[k]
                    for (step in 1..maxLenPx) {
                        val w = decayLut[step]
                        val wr = sr * w
                        val wg = sg * w
                        val wb = sb * w

                        val px1 = (x + dx * step).roundToInt()
                        val py1 = (y + dy * step).roundToInt()
                        if (px1 in 0 until width && py1 in 0 until height) {
                            val d1 = py1 * width + px1
                            accR[d1] += wr
                            accG[d1] += wg
                            accB[d1] += wb
                        }
                        val px2 = (x - dx * step).roundToInt()
                        val py2 = (y - dy * step).roundToInt()
                        if (px2 in 0 until width && py2 in 0 until height) {
                            val d2 = py2 * width + px2
                            accR[d2] += wr
                            accG[d2] += wg
                            accB[d2] += wb
                        }
                    }
                }
            }
            if (hotPixels >= maxHotBudget) break
        }

        if (hotPixels > 0) {
            for (i in 0 until count) {
                red[i] += accR[i]
                green[i] += accG[i]
                blue[i] += accB[i]
            }
        }
    }

    override fun summaryBadge(): String? = if (!active) null else "Star ${clampedPoints}pt"

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to active,
        "points" to clampedPoints,
        "strength" to clampedStrength,
        "threshold" to clampedThreshold,
        "angleDeg" to angleDeg,
        "length" to clampedLength,
    )

    companion object {
        const val TYPE_ID = "star"
    }
}

class StarFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = StarFilterElement.TYPE_ID
    override val displayName: String = "Star Cross-Screen"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Diffraction grating star/cross-screen filter emitting linear 4-, 6-, or 8-point highlight spikes.",
        descriptionZh = "星芒衍射光栅滤镜，在高光点光源周围生成 4、6 或 8 线线性星芒射线。",
        names = mapOf(
            "en" to "Star Cross-Screen",
            "zh_CN" to "星芒滤镜",
            "ja_JP" to "クロススクリーン（スター）",
            "de_DE" to "Sternfilter (Gitter)",
            "fr_FR" to "Filtre étoile (Cross-Screen)",
            "es_ES" to "Filtro de estrella",
        ),
        descriptions = mapOf(
            "en" to "Diffraction grating star/cross-screen filter emitting linear 4-, 6-, or 8-point highlight spikes.",
            "zh_CN" to "星芒衍射光栅滤镜，在高光点光源周围生成 4、6 或 8 线线性星芒射线。",
            "ja_JP" to "高輝度の点光源から4・6・8条の線形回折光条（光芒）を描くクロススクリーンフィルター。",
            "de_DE" to "Beugungsgitter-Sternfilter für lineare 4-, 6- oder 8-strahlige Lichtsterne um Spitzlichter.",
            "fr_FR" to "Filtre à réseau de diffraction générant des rayons lumineux linéaires à 4, 6 ou 8 branches sur les hautes lumières.",
            "es_ES" to "Filtro de red de difracción que proyecta destellos lineales de 4, 6 u 8 puntas alrededor de altas luces.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "points",
            type = FilterParamType.ENUM,
            labelEn = "Spikes",
            labelZh = "星芒线数",
            defaultValue = "6",
            choices = listOf(
                FilterParamChoice("4", "4pt", "4 线", mapOf("en" to "4pt", "zh_CN" to "4 线", "ja_JP" to "4条", "de_DE" to "4-fach", "fr_FR" to "4 branches", "es_ES" to "4 puntas")),
                FilterParamChoice("6", "6pt", "6 线", mapOf("en" to "6pt", "zh_CN" to "6 线", "ja_JP" to "6条", "de_DE" to "6-fach", "fr_FR" to "6 branches", "es_ES" to "6 puntas")),
                FilterParamChoice("8", "8pt", "8 线", mapOf("en" to "8pt", "zh_CN" to "8 线", "ja_JP" to "8条", "de_DE" to "8-fach", "fr_FR" to "8 branches", "es_ES" to "8 puntas")),
            ),
            labels = mapOf("en" to "Spikes", "zh_CN" to "星芒线数", "ja_JP" to "光条数", "de_DE" to "Strahlen", "fr_FR" to "Branches", "es_ES" to "Puntas"),
        ),
        FilterParamSpec(
            key = "strength",
            type = FilterParamType.NUMBER,
            labelEn = "Intensity",
            labelZh = "星芒强度",
            defaultValue = 0.75,
            min = 0.0,
            max = 2.0,
            step = 0.05,
            labels = mapOf("en" to "Intensity", "zh_CN" to "星芒强度", "ja_JP" to "強度", "de_DE" to "Intensität", "fr_FR" to "Intensité", "es_ES" to "Intensidad"),
        ),
        FilterParamSpec(
            key = "angleDeg",
            type = FilterParamType.NUMBER,
            labelEn = "Angle",
            labelZh = "旋转角",
            defaultValue = 15.0,
            min = 0.0,
            max = 180.0,
            step = 1.0,
            unit = "°",
            labels = mapOf("en" to "Angle", "zh_CN" to "旋转角", "ja_JP" to "回転角", "de_DE" to "Winkel", "fr_FR" to "Angle", "es_ES" to "Ángulo"),
        ),
        FilterParamSpec(
            key = "threshold",
            type = FilterParamType.NUMBER,
            labelEn = "Threshold",
            labelZh = "高光阈值",
            defaultValue = 0.55,
            min = 0.10,
            max = 2.0,
            step = 0.05,
            labels = mapOf("en" to "Threshold", "zh_CN" to "高光阈值", "ja_JP" to "しきい値", "de_DE" to "Schwellenwert", "fr_FR" to "Seuil", "es_ES" to "Umbral"),
        ),
        FilterParamSpec(
            key = "length",
            type = FilterParamType.NUMBER,
            labelEn = "Length",
            labelZh = "射线长度",
            defaultValue = 0.12,
            min = 0.02,
            max = 0.35,
            step = 0.01,
            labels = mapOf("en" to "Length", "zh_CN" to "射线长度", "ja_JP" to "光条長", "de_DE" to "Länge", "fr_FR" to "Longueur", "es_ES" to "Longitud"),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val rawPts = config["starPoints"] ?: config["points"]
        val parsedPts = (rawPts as? Number)?.toInt() ?: (rawPts as? String)?.trim()?.removeSuffix("pt")?.toIntOrNull() ?: 6
        return StarFilterElement(
            enabled = (config["starEnabled"] ?: config["enabled"]) as? Boolean ?: false,
            points = parsedPts,
            strength = ((config["starStrength"] ?: config["strength"]) as? Number)?.toDouble() ?: 0.75,
            threshold = ((config["starThreshold"] ?: config["threshold"]) as? Number)?.toDouble() ?: 0.55,
            angleDeg = ((config["starAngleDeg"] ?: config["angleDeg"]) as? Number)?.toDouble() ?: 15.0,
            length = ((config["starLength"] ?: config["length"]) as? Number)?.toDouble() ?: 0.12,
        )
    }
}

/**
 * Cinema Black Mist diffusion filter (`1/8`, `1/4`, `1/2`, `1`): soft linear highlight halation
 * using an O(1) sliding-window separable box blur in reusable scratch buffers (zero allocations).
 */
class BlackMistFilterElement(
    override val enabled: Boolean,
    val preset: String,
    val strength: Double,
    val threshold: Double,
) : OpticalFilterElement {
    private val clampedStrength = strength.coerceIn(0.0, 1.0)
    private val clampedThreshold = threshold.coerceIn(0.0, 1.0)
    private val active = enabled && clampedStrength > 1e-4

    override val id: String = TYPE_ID
    override val displayName: String = "Black Mist Diffusion"
    override val hasSpatialEffect: Boolean = active
    override val supportsVulkanBuiltin: Boolean = true

    override fun transmittanceMultiplier(): Double = 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        if (!active || width < 4 || height < 4) return
        val count = width * height
        scratch.ensurePrimary(count)
        scratch.ensureAux(count)
        val radius = (min(width, height) * (0.014 + 0.024 * clampedStrength)).roundToInt().coerceIn(2, 24)
        if (NativeOpticalFilterSimdBridge.applyBlackMist(
                red = red,
                green = green,
                blue = blue,
                hiR = scratch.r,
                hiG = scratch.g,
                hiB = scratch.b,
                tmpR = scratch.auxR,
                tmpG = scratch.auxG,
                tmpB = scratch.auxB,
                width = width,
                height = height,
                threshold = clampedThreshold.toFloat(),
                strength = clampedStrength.toFloat(),
                radius = radius,
            )
        ) {
            return
        }
        val hiR = scratch.r
        val hiG = scratch.g
        val hiB = scratch.b
        val tmpR = scratch.auxR
        val tmpG = scratch.auxG
        val tmpB = scratch.auxB

        val thr = clampedThreshold.toFloat()
        val knee = 0.25f
        for (i in 0 until count) {
            val r = red[i]
            val g = green[i]
            val b = blue[i]
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val excess = (lum - thr).coerceAtLeast(0f)
            val softWeight = if (excess > 0f) (excess / (excess + knee)) else 0f
            hiR[i] = r * softWeight
            hiG[i] = g * softWeight
            hiB[i] = b * softWeight
        }

        // Two passes of separable box blur approximate a smooth Gaussian halation halo.
        repeat(2) {
            boxBlurH(hiR, tmpR, width, height, radius)
            boxBlurV(tmpR, hiR, width, height, radius)
            boxBlurH(hiG, tmpG, width, height, radius)
            boxBlurV(tmpG, hiG, width, height, radius)
            boxBlurH(hiB, tmpB, width, height, radius)
            boxBlurV(tmpB, hiB, width, height, radius)
        }

        val bloomGain = (clampedStrength * 0.65).toFloat()
        val lift = (clampedStrength * 0.008).toFloat()
        for (i in 0 until count) {
            red[i] = red[i] + hiR[i] * bloomGain + lift
            green[i] = green[i] + hiG[i] * bloomGain + lift
            blue[i] = blue[i] + hiB[i] * bloomGain + lift
        }
    }

    override fun summaryBadge(): String? {
        if (!active) return null
        val label = preset.takeIf { it.isNotBlank() && !it.equals("OFF", true) && !it.equals("CUSTOM", true) }
            ?: String.format(Locale.ROOT, "%.2f", clampedStrength)
        return "Mist $label"
    }

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to active,
        "preset" to preset,
        "strength" to clampedStrength,
        "threshold" to clampedThreshold,
    )

    companion object {
        const val TYPE_ID = "black-mist"
        val PRESET_CHOICES = listOf("OFF", "1/8", "1/4", "1/2", "1", "CUSTOM")

        fun strengthForPreset(preset: String): Double? = when (preset.trim().uppercase(Locale.ROOT)) {
            "OFF", "NONE", "0" -> 0.0
            "1/8", "0.125" -> 0.125
            "1/4", "0.25" -> 0.25
            "1/2", "0.5" -> 0.50
            "1", "1/1", "1.0", "FULL" -> 1.0
            else -> null
        }

        fun presetForStrength(strength: Double): String {
            val s = strength.coerceIn(0.0, 1.0)
            return when {
                s <= 1e-4 -> "OFF"
                abs(s - 0.125) <= 0.01 -> "1/8"
                abs(s - 0.25) <= 0.01 -> "1/4"
                abs(s - 0.50) <= 0.01 -> "1/2"
                abs(s - 1.0) <= 0.01 -> "1"
                else -> "CUSTOM"
            }
        }

        internal fun boxBlurH(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
            val invWin = 1.0f / (2 * radius + 1).toFloat()
            val maxX = width - 1
            for (y in 0 until height) {
                val row = y * width
                var sum = src[row] * (radius + 1)
                for (k in 1..radius) {
                    sum += src[row + min(k, maxX)]
                }
                for (x in 0 until width) {
                    dst[row + x] = sum * invWin
                    val addIdx = row + min(x + radius + 1, maxX)
                    val remIdx = row + max(x - radius, 0)
                    sum += src[addIdx] - src[remIdx]
                }
            }
        }

        internal fun boxBlurV(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
            val invWin = 1.0f / (2 * radius + 1).toFloat()
            val maxY = height - 1
            for (x in 0 until width) {
                var sum = src[x] * (radius + 1)
                for (k in 1..radius) {
                    sum += src[min(k, maxY) * width + x]
                }
                for (y in 0 until height) {
                    dst[y * width + x] = sum * invWin
                    val addY = min(y + radius + 1, maxY)
                    val remY = max(y - radius, 0)
                    sum += src[addY * width + x] - src[remY * width + x]
                }
            }
        }
    }
}

class BlackMistFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = BlackMistFilterElement.TYPE_ID
    override val displayName: String = "Black Mist Diffusion"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Cinema Black Mist diffusion filter producing soft linear highlight halation while preserving deep shadows.",
        descriptionZh = "电影黑柔焦滤镜，在保持暗部质感的同时产生柔和的线性高光晕染光晕。",
        names = mapOf(
            "en" to "Black Mist Diffusion",
            "zh_CN" to "黑柔焦滤镜",
            "ja_JP" to "ブラックミスト拡散",
            "de_DE" to "Black-Mist-Diffusionsfilter",
            "fr_FR" to "Diffusion Black Mist",
            "es_ES" to "Difusión Black Mist",
        ),
        descriptions = mapOf(
            "en" to "Cinema Black Mist diffusion filter producing soft linear highlight halation while preserving deep shadows.",
            "zh_CN" to "电影黑柔焦滤镜，在保持暗部质感的同时产生柔和的线性高光晕染光晕。",
            "ja_JP" to "暗部の締まりを保ちながら、ハイライトに映画的な柔らかい滲み（ハレーション）を加えるブラックミストフィルター。",
            "de_DE" to "Kino-Black-Mist-Diffusionsfilter für weiches Highlight-Halation bei erhaltenen Tiefen.",
            "fr_FR" to "Filtre de diffusion cinéma Black Mist créant un halo doux sur les hautes lumières tout en préservant les ombres.",
            "es_ES" to "Filtro difusor cinematográfico Black Mist que suaviza las altas luces con halación preservando las sombras.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "preset",
            type = FilterParamType.ENUM,
            labelEn = "Grade",
            labelZh = "柔焦档位",
            defaultValue = "1/4",
            choices = listOf("1/8", "1/4", "1/2", "1", "CUSTOM").map { FilterParamChoice(it, it, it) },
            labels = mapOf("en" to "Grade", "zh_CN" to "柔焦档位", "ja_JP" to "グレード", "de_DE" to "Stärkegrad", "fr_FR" to "Grade", "es_ES" to "Grado"),
        ),
        FilterParamSpec(
            key = "strength",
            type = FilterParamType.NUMBER,
            labelEn = "Diffusion",
            labelZh = "晕染强度",
            defaultValue = 0.25,
            min = 0.0,
            max = 1.0,
            step = 0.01,
            labels = mapOf("en" to "Diffusion", "zh_CN" to "晕染强度", "ja_JP" to "拡散強度", "de_DE" to "Diffusion", "fr_FR" to "Diffusion", "es_ES" to "Difusión"),
        ),
        FilterParamSpec(
            key = "threshold",
            type = FilterParamType.NUMBER,
            labelEn = "Threshold",
            labelZh = "高光阈值",
            defaultValue = 0.35,
            min = 0.0,
            max = 1.0,
            step = 0.05,
            labels = mapOf("en" to "Threshold", "zh_CN" to "高光阈值", "ja_JP" to "しきい値", "de_DE" to "Schwellenwert", "fr_FR" to "Seuil", "es_ES" to "Umbral"),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val rawPreset = ((config["mistPreset"] ?: config["preset"]) as? String)?.takeIf { it.isNotBlank() }
        val rawStrength = (config["mistStrength"] ?: config["strength"]) as? Number
        val fixedStrength = rawPreset?.let { BlackMistFilterElement.strengthForPreset(it) }
        val strength = (rawStrength?.toDouble() ?: fixedStrength ?: 0.0).coerceIn(0.0, 1.0)
        val enabled = (config["mistEnabled"] ?: config["enabled"]) as? Boolean ?: (strength > 1e-4)
        val preset = rawPreset ?: BlackMistFilterElement.presetForStrength(strength)
        val threshold = ((config["mistThreshold"] ?: config["threshold"]) as? Number)?.toDouble() ?: 0.35
        return BlackMistFilterElement(enabled = enabled, preset = preset, strength = strength, threshold = threshold)
    }
}

/**
 * Soft-edge Graduated Neutral Density filter (GND): attenuates linear sky/upper-frame radiance by
 * `2^(-stops)` above the rotated horizon band and smoothly transitions to `1.0` below.
 */
class GndFilterElement(
    override val enabled: Boolean,
    val stops: Double,
    val horizon: Double,
    val feather: Double,
    val angleDeg: Double,
) : OpticalFilterElement {
    private val clampedStops = stops.coerceIn(0.0, 5.0)
    private val clampedHorizon = horizon.coerceIn(0.0, 1.0)
    private val clampedFeather = feather.coerceIn(0.02, 1.0)
    private val active = enabled && clampedStops > 1e-4

    override val id: String = TYPE_ID
    override val displayName: String = "Graduated ND"
    override val hasSpatialEffect: Boolean = active
    override val supportsVulkanBuiltin: Boolean = true

    override fun transmittanceMultiplier(): Double = 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        if (!active || width <= 0 || height <= 0) return
        val darkGain = 2.0.pow(-clampedStops)
        val rad = Math.toRadians(angleDeg)
        val nx = -sin(rad)
        val ny = cos(rad)
        val invW = 1.0 / max(1, width - 1).toDouble()
        val invH = 1.0 / max(1, height - 1).toDouble()
        val halfFeather = clampedFeather * 0.5
        val invFeather = 1.0 / clampedFeather

        for (y in 0 until height) {
            val v = y * invH - clampedHorizon
            val row = y * width
            for (x in 0 until width) {
                val u = x * invW - 0.5
                // proj < 0 is above horizon (darkened sky); proj > 0 is below horizon (clear).
                val proj = u * nx + v * ny
                val t = ((proj + halfFeather) * invFeather).coerceIn(0.0, 1.0)
                val smooth = t * t * (3.0 - 2.0 * t)
                val gain = (darkGain + (1.0 - darkGain) * smooth).toFloat()
                if (gain < 0.9999f) {
                    val idx = row + x
                    red[idx] *= gain
                    green[idx] *= gain
                    blue[idx] *= gain
                }
            }
        }
    }

    override fun summaryBadge(): String? = if (!active) null else String.format(Locale.ROOT, "GND %.1f EV", clampedStops)

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to active,
        "stops" to clampedStops,
        "horizon" to clampedHorizon,
        "feather" to clampedFeather,
        "angleDeg" to angleDeg,
    )

    companion object {
        const val TYPE_ID = "gnd"
    }
}

class GndFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = GndFilterElement.TYPE_ID
    override val displayName: String = "Graduated ND"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Soft-edge rotatable Graduated Neutral Density sky attenuator balancing bright skies against foregrounds.",
        descriptionZh = "可旋转软边渐变灰滤镜（GND），用于压暗明亮天空并平衡地景光比。",
        names = mapOf(
            "en" to "Graduated ND",
            "zh_CN" to "渐变灰滤镜 (GND)",
            "ja_JP" to "ハーフND（グラデーション）",
            "de_DE" to "Grauverlaufsfilter (GND)",
            "fr_FR" to "Filtre ND dégradé (GND)",
            "es_ES" to "Filtro ND degradado (GND)",
        ),
        descriptions = mapOf(
            "en" to "Soft-edge rotatable Graduated Neutral Density sky attenuator balancing bright skies against foregrounds.",
            "zh_CN" to "可旋转软边渐变灰滤镜（GND），用于压暗明亮天空并平衡地景光比。",
            "ja_JP" to "明るい空と前景の輝度差を滑らかに整える、回転可能なソフトエッジ・ハーフNDフィルター。",
            "de_DE" to "Drehbarer weicher Grauverlaufsfilter (GND) zum Ausgleich heller Himmel gegenüber dem Vordergrund.",
            "fr_FR" to "Filtre ND dégradé doux orientable équilibrant les ciels lumineux par rapport au premier plan.",
            "es_ES" to "Filtro ND degradado suave y giratorio que equilibra cielos luminosos frente al primer plano.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "stops",
            type = FilterParamType.NUMBER,
            labelEn = "Sky Density",
            labelZh = "天空减光",
            defaultValue = 2.0,
            min = 0.0,
            max = 5.0,
            step = 0.1,
            unit = "EV",
            labels = mapOf("en" to "Sky Density", "zh_CN" to "天空减光", "ja_JP" to "空の減光", "de_DE" to "Himmelsdichte", "fr_FR" to "Densité ciel", "es_ES" to "Densidad cielo"),
        ),
        FilterParamSpec(
            key = "horizon",
            type = FilterParamType.NUMBER,
            labelEn = "Horizon",
            labelZh = "地平线位置",
            defaultValue = 0.50,
            min = 0.0,
            max = 1.0,
            step = 0.02,
            labels = mapOf("en" to "Horizon", "zh_CN" to "地平线位置", "ja_JP" to "水平線位置", "de_DE" to "Horizont", "fr_FR" to "Horizon", "es_ES" to "Horizonte"),
        ),
        FilterParamSpec(
            key = "feather",
            type = FilterParamType.NUMBER,
            labelEn = "Feather",
            labelZh = "过渡柔化",
            defaultValue = 0.25,
            min = 0.02,
            max = 1.0,
            step = 0.02,
            labels = mapOf("en" to "Feather", "zh_CN" to "过渡柔化", "ja_JP" to "境界ぼかし", "de_DE" to "Übergang", "fr_FR" to "Transition", "es_ES" to "Transición"),
        ),
        FilterParamSpec(
            key = "angleDeg",
            type = FilterParamType.NUMBER,
            labelEn = "Tilt",
            labelZh = "倾斜角",
            defaultValue = 0.0,
            min = -180.0,
            max = 180.0,
            step = 1.0,
            unit = "°",
            labels = mapOf("en" to "Tilt", "zh_CN" to "倾斜角", "ja_JP" to "傾き角", "de_DE" to "Neigung", "fr_FR" to "Inclinaison", "es_ES" to "Inclinación"),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val stops = ((config["gndStops"] ?: config["stops"]) as? Number)?.toDouble() ?: 0.0
        val enabled = (config["gndEnabled"] ?: config["enabled"]) as? Boolean ?: (stops > 1e-4)
        return GndFilterElement(
            enabled = enabled,
            stops = stops,
            horizon = ((config["gndHorizon"] ?: config["horizon"]) as? Number)?.toDouble() ?: 0.5,
            feather = ((config["gndFeather"] ?: config["feather"]) as? Number)?.toDouble() ?: 0.25,
            angleDeg = ((config["gndAngleDeg"] ?: config["angleDeg"]) as? Number)?.toDouble() ?: 0.0,
        )
    }
}

/**
 * Physical front-of-lens UV Protective Filter (`PRO_MRC`, `STANDARD`, `CHEAP_POOR`, `CUSTOM`).
 *
 * Models real glass & coating physics in linear radiance space with zero per-frame allocations:
 * 1. Fresnel surface transmittance (`0.50..1.00`, e.g., `99.6%` Pro MRC vs `89.5%` Cheap Uncoated)
 * 2. Micro-softness / MTF degradation via flat-glass aberration blend (`0.0..1.0`)
 * 3. Point-symmetric internal double-reflection ghosting across the optical center `(-x, -y)`
 *    with authentic cheap green/magenta residual coating cast (`0.0..0.5`)
 * 4. Veiling glare / stray-light contrast washout lifting deep shadows (`0.0..0.5`)
 * 5. Soda-lime glass chromatic tint (`tintR`, `tintG`, `tintB`), normalized to preserve
 *    radiometric transmittance accuracy.
 */
class UvFilterElement(
    val preset: UvFilterPreset,
    val transmittance: Double = preset.transmittance,
    val veilingGlare: Double = preset.veilingGlare,
    val ghostIntensity: Double = preset.ghostIntensity,
    val softness: Double = preset.softness,
    val tintR: Double = preset.tintR,
    val tintG: Double = preset.tintG,
    val tintB: Double = preset.tintB,
    enabledFlag: Boolean = preset != UvFilterPreset.OFF,
) : OpticalFilterElement {
    private val clampedTrans = transmittance.coerceIn(0.50, 1.0)
    private val clampedGlare = veilingGlare.coerceIn(0.0, 0.50)
    private val clampedGhost = ghostIntensity.coerceIn(0.0, 0.50)
    private val clampedSoftness = softness.coerceIn(0.0, 1.0)
    private val clampedTintR = tintR.coerceIn(0.75, 1.25)
    private val clampedTintG = tintG.coerceIn(0.75, 1.25)
    private val clampedTintB = tintB.coerceIn(0.75, 1.25)
    private val hasTint = abs(clampedTintR - 1.0) > 1e-4 || abs(clampedTintG - 1.0) > 1e-4 || abs(clampedTintB - 1.0) > 1e-4
    private val active = enabledFlag && preset != UvFilterPreset.OFF

    override val id: String = TYPE_ID
    override val displayName: String = "UV Protective Filter"
    override val enabled: Boolean = active
    override val hasSpatialEffect: Boolean =
        active && (clampedSoftness > 1e-4 || clampedGhost > 1e-4 || clampedGlare > 1e-4 || hasTint)
    override val supportsVulkanBuiltin: Boolean = true

    override fun transmittanceMultiplier(): Double = if (active) clampedTrans else 1.0

    override fun applySpatialLinearRgb(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        scratch: OpticalFilterScratch,
    ) {
        if (!hasSpatialEffect || width < 4 || height < 4) return
        val count = width * height
        if (NativeOpticalFilterSimdBridge.isAvailable) {
            scratch.ensurePrimary(count)
            scratch.ensureAux(count)
            if (NativeOpticalFilterSimdBridge.applyUvFilter(
                    red = red,
                    green = green,
                    blue = blue,
                    scratchR = scratch.r,
                    scratchG = scratch.g,
                    scratchB = scratch.b,
                    auxR = scratch.auxR,
                    auxG = scratch.auxG,
                    auxB = scratch.auxB,
                    width = width,
                    height = height,
                    softness = clampedSoftness.toFloat(),
                    ghostIntensity = clampedGhost.toFloat(),
                    veilingGlare = clampedGlare.toFloat(),
                    tintR = clampedTintR.toFloat(),
                    tintG = clampedTintG.toFloat(),
                    tintB = clampedTintB.toFloat(),
                    hasTint = hasTint,
                )
            ) {
                return
            }
        }

        // 1. Micro-softness / MTF degradation (flat cheap glass micro-aberration)
        if (clampedSoftness > 1e-4) {
            scratch.ensurePrimary(count)
            scratch.ensureAux(count)
            val blurRadius = if (clampedSoftness >= 0.25) 2 else 1
            BlackMistFilterElement.boxBlurH(red, scratch.auxR, width, height, blurRadius)
            BlackMistFilterElement.boxBlurV(scratch.auxR, scratch.r, width, height, blurRadius)
            BlackMistFilterElement.boxBlurH(green, scratch.auxG, width, height, blurRadius)
            BlackMistFilterElement.boxBlurV(scratch.auxG, scratch.g, width, height, blurRadius)
            BlackMistFilterElement.boxBlurH(blue, scratch.auxB, width, height, blurRadius)
            BlackMistFilterElement.boxBlurV(scratch.auxB, scratch.b, width, height, blurRadius)

            val s = clampedSoftness.toFloat()
            val invS = 1.0f - s
            val br = scratch.r
            val bg = scratch.g
            val bb = scratch.b
            for (i in 0 until count) {
                red[i] = red[i] * invS + br[i] * s
                green[i] = green[i] * invS + bg[i] * s
                blue[i] = blue[i] * invS + bb[i] * s
            }
        }

        // 2. Real point-symmetric internal double-reflection ghosting across optical center (-x, -y)
        if (clampedGhost > 1e-4) {
            scratch.ensurePrimary(count)
            scratch.ensureAux(count)
            val gR = scratch.r
            val gG = scratch.g
            val gB = scratch.b
            val thr = 0.45f
            var anySpecular = false
            for (y in 0 until height) {
                val srcRow = y * width
                val dstRow = (height - 1 - y) * width
                for (x in 0 until width) {
                    val srcIdx = srcRow + x
                    val dstIdx = dstRow + (width - 1 - x)
                    val r = red[srcIdx]
                    val g = green[srcIdx]
                    val b = blue[srcIdx]
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    if (lum > thr) {
                        val excess = (lum - thr) / max(lum, 1e-4f)
                        gR[dstIdx] = r * excess
                        gG[dstIdx] = g * excess
                        gB[dstIdx] = b * excess
                        anySpecular = true
                    } else {
                        gR[dstIdx] = 0f
                        gG[dstIdx] = 0f
                        gB[dstIdx] = 0f
                    }
                }
            }
            if (anySpecular) {
                val ghostRadius = (min(width, height) / 32).coerceIn(2, 16)
                BlackMistFilterElement.boxBlurH(gR, scratch.auxR, width, height, ghostRadius)
                BlackMistFilterElement.boxBlurV(scratch.auxR, gR, width, height, ghostRadius)
                BlackMistFilterElement.boxBlurH(gG, scratch.auxG, width, height, ghostRadius)
                BlackMistFilterElement.boxBlurV(scratch.auxG, gG, width, height, ghostRadius)
                BlackMistFilterElement.boxBlurH(gB, scratch.auxB, width, height, ghostRadius)
                BlackMistFilterElement.boxBlurV(scratch.auxB, gB, width, height, ghostRadius)

                // Authentic cheap uncoated green/magenta double-reflection residual hue
                val gain = (clampedGhost * 2.4).toFloat()
                val kR = gain * 0.90f
                val kG = gain * 1.24f
                val kB = gain * 1.06f
                for (i in 0 until count) {
                    red[i] += gR[i] * kR
                    green[i] += gG[i] * kG
                    blue[i] += gB[i] * kB
                }
            }
        }

        // 3. Veiling glare / stray-light contrast washout + soda-lime chromatic cast
        if (clampedGlare > 1e-4 || hasTint) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            if (clampedGlare > 1e-4) {
                for (i in 0 until count) {
                    sumR += red[i]
                    sumG += green[i]
                    sumB += blue[i]
                }
            }
            val invN = 1.0 / count.toDouble()
            val glare = clampedGlare.toFloat()
            val contrastKeep = (1.0f - 0.28f * glare).coerceAtLeast(0.55f)
            val veilR = (sumR * invN * clampedGlare * 0.50).toFloat()
            val veilG = (sumG * invN * clampedGlare * 0.50).toFloat()
            val veilB = (sumB * invN * clampedGlare * 0.50).toFloat()

            val lumaNorm = (0.2126 * clampedTintR + 0.7152 * clampedTintG + 0.0722 * clampedTintB).coerceAtLeast(0.1)
            val tR = if (hasTint) (clampedTintR / lumaNorm).toFloat() else 1.0f
            val tG = if (hasTint) (clampedTintG / lumaNorm).toFloat() else 1.0f
            val tB = if (hasTint) (clampedTintB / lumaNorm).toFloat() else 1.0f

            for (i in 0 until count) {
                red[i] = (red[i] * contrastKeep + veilR) * tR
                green[i] = (green[i] * contrastKeep + veilG) * tG
                blue[i] = (blue[i] * contrastKeep + veilB) * tB
            }
        }
    }

    override fun summaryBadge(): String? = when {
        !active -> null
        preset == UvFilterPreset.PRO_MRC -> "UV: Pro MRC"
        preset == UvFilterPreset.STANDARD -> "UV: Standard"
        preset == UvFilterPreset.CHEAP_POOR -> "UV: Cheap"
        else -> String.format(Locale.ROOT, "UV: %.0f%%", clampedTrans * 100.0)
    }

    override fun stateMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to active,
        "preset" to preset.id,
        "transmittance" to clampedTrans,
        "transmittancePercent" to clampedTrans * 100.0,
        "veilingGlare" to clampedGlare,
        "ghostIntensity" to clampedGhost,
        "softness" to clampedSoftness,
        "tintR" to clampedTintR,
        "tintG" to clampedTintG,
        "tintB" to clampedTintB,
    )

    companion object {
        const val TYPE_ID = "uv"
    }
}

class UvFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = UvFilterElement.TYPE_ID
    override val displayName: String = "UV Protective Filter"
    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Physical UV protective glass modeling Fresnel loss, veiling glare, internal ghosting, MTF micro-softness, and glass tint.",
        descriptionZh = "物理 UV 保护镜，模拟透光损耗、炫光灰雾、内部鬼影、画质微柔及玻璃偏色。",
        names = mapOf(
            "en" to "UV Protective Filter",
            "zh_CN" to "UV 保护镜",
            "ja_JP" to "UV 保護フィルター",
            "de_DE" to "UV-Schutzfilter",
            "fr_FR" to "Filtre UV de protection",
            "es_ES" to "Filtro protector UV",
        ),
        descriptions = mapOf(
            "en" to "Physical UV protective glass modeling Fresnel loss, veiling glare, internal ghosting, MTF micro-softness, and glass tint.",
            "zh_CN" to "物理 UV 保护镜，模拟透光损耗、炫光灰雾、内部鬼影、画质微柔及玻璃偏色。",
            "ja_JP" to "フレネル透過損失・フレア・点対称ゴースト・MTF微小劣化・ガラス色かぶりを物理再現するUV保護フィルター。",
            "de_DE" to "Physikalischer UV-Schutzfilter mit Fresnel-Verlust, Streulichtschleier, internen Geisterbildern, MTF-Weichzeichnung und Glasfarbstich.",
            "fr_FR" to "Verre de protection UV physique modélisant pertes de Fresnel, voile lumineux, reflets internes, douceur MTF et teinte du verre.",
            "es_ES" to "Cristal protector UV físico que modela pérdidas de Fresnel, velo luminoso, reflejos internos, suavidad MTF y tinte del vidrio.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "preset",
            type = FilterParamType.ENUM,
            labelEn = "Glass Grade",
            labelZh = "镀膜等级",
            defaultValue = UvFilterPreset.PRO_MRC.id,
            choices = listOf(
                FilterParamChoice(UvFilterPreset.PRO_MRC.id, "Pro MRC", "专业 MRC", mapOf("en" to "Pro MRC", "zh_CN" to "专业 MRC", "ja_JP" to "プロ MRC", "de_DE" to "Pro MRC", "fr_FR" to "Pro MRC", "es_ES" to "Pro MRC")),
                FilterParamChoice(UvFilterPreset.STANDARD.id, "Standard", "普通镀膜", mapOf("en" to "Standard", "zh_CN" to "普通镀膜", "ja_JP" to "標準コート", "de_DE" to "Standard", "fr_FR" to "Standard", "es_ES" to "Estándar")),
                FilterParamChoice(UvFilterPreset.CHEAP_POOR.id, "Cheap Glass", "劣质玻璃", mapOf("en" to "Cheap Glass", "zh_CN" to "劣质玻璃", "ja_JP" to "廉価ガラス", "de_DE" to "Billigglas", "fr_FR" to "Verre bas de gamme", "es_ES" to "Vidrio económico")),
                FilterParamChoice(UvFilterPreset.CUSTOM.id, "Custom", "自定义", mapOf("en" to "Custom", "zh_CN" to "自定义", "ja_JP" to "カスタム", "de_DE" to "Benutzerdefiniert", "fr_FR" to "Personnalisé", "es_ES" to "Personalizado")),
            ),
            labels = mapOf("en" to "Glass Grade", "zh_CN" to "镀膜等级", "ja_JP" to "ガラス等級", "de_DE" to "Glasqualität", "fr_FR" to "Qualité du verre", "es_ES" to "Calidad del vidrio"),
        ),
        FilterParamSpec(
            key = "transmittance",
            type = FilterParamType.NUMBER,
            labelEn = "Transmittance",
            labelZh = "透光率",
            defaultValue = UvFilterPreset.PRO_MRC.transmittance,
            min = 0.50,
            max = 1.0,
            step = 0.005,
            labels = mapOf("en" to "Transmittance", "zh_CN" to "透光率", "ja_JP" to "透過率", "de_DE" to "Transmission", "fr_FR" to "Transmission", "es_ES" to "Transmitancia"),
        ),
        FilterParamSpec(
            key = "veilingGlare",
            type = FilterParamType.NUMBER,
            labelEn = "Veiling Glare",
            labelZh = "炫光灰雾",
            defaultValue = UvFilterPreset.PRO_MRC.veilingGlare,
            min = 0.0,
            max = 0.50,
            step = 0.01,
            labels = mapOf("en" to "Veiling Glare", "zh_CN" to "炫光灰雾", "ja_JP" to "フレア", "de_DE" to "Streulicht", "fr_FR" to "Voile lumineux", "es_ES" to "Velo luminoso"),
        ),
        FilterParamSpec(
            key = "ghostIntensity",
            type = FilterParamType.NUMBER,
            labelEn = "Internal Ghost",
            labelZh = "内部鬼影",
            defaultValue = UvFilterPreset.PRO_MRC.ghostIntensity,
            min = 0.0,
            max = 0.50,
            step = 0.01,
            labels = mapOf("en" to "Internal Ghost", "zh_CN" to "内部鬼影", "ja_JP" to "内部ゴースト", "de_DE" to "Geisterbilder", "fr_FR" to "Reflets internes", "es_ES" to "Reflejos internos"),
        ),
        FilterParamSpec(
            key = "softness",
            type = FilterParamType.NUMBER,
            labelEn = "MTF Softness",
            labelZh = "微柔画质劣化",
            defaultValue = UvFilterPreset.PRO_MRC.softness,
            min = 0.0,
            max = 1.0,
            step = 0.02,
            labels = mapOf("en" to "MTF Softness", "zh_CN" to "微柔画质劣化", "ja_JP" to "MTF 低下", "de_DE" to "MTF-Weichzeichnung", "fr_FR" to "Douceur MTF", "es_ES" to "Suavidad MTF"),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val rawPreset = (config["uvPreset"] ?: config["preset"]) as? String
        val hasCustomMetric = listOf(
            "uvTransmittance", "transmittance",
            "uvVeilingGlare", "veilingGlare",
            "uvGhostIntensity", "ghostIntensity",
            "uvSoftness", "softness",
            "uvTintR", "tintR", "uvTintG", "tintG", "uvTintB", "tintB",
        ).any { config[it] != null }
        val preset = UvFilterPreset.parse(rawPreset)
            ?: if (rawPreset == null && hasCustomMetric) UvFilterPreset.CUSTOM else UvFilterPreset.OFF
        val enabled = (config["enabled"] as? Boolean) ?: (preset != UvFilterPreset.OFF)
        fun num(flatKey: String, shortKey: String, def: Double): Double =
            ((config[flatKey] ?: config[shortKey]) as? Number)?.toDouble() ?: def
        return UvFilterElement(
            preset = preset,
            transmittance = num("uvTransmittance", "transmittance", preset.transmittance),
            veilingGlare = num("uvVeilingGlare", "veilingGlare", preset.veilingGlare),
            ghostIntensity = num("uvGhostIntensity", "ghostIntensity", preset.ghostIntensity),
            softness = num("uvSoftness", "softness", preset.softness),
            tintR = num("uvTintR", "tintR", preset.tintR),
            tintG = num("uvTintG", "tintG", preset.tintG),
            tintB = num("uvTintB", "tintB", preset.tintB),
            enabledFlag = enabled,
        )
    }
}
