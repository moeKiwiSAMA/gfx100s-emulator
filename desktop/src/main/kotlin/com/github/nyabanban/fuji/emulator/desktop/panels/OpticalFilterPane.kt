package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.FilterParamChoice
import com.github.nyabanban.fuji.emulator.api.FilterParamSpec
import com.github.nyabanban.fuji.emulator.api.FilterParamType
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotSpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round

/**
 * Summary of an available [com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider]
 * (built-in or externally discovered from `<run-dir>/filters/` JAR plugins).
 */
data class OpticalFilterProviderInfo(
    val type: String,
    val displayName: String,
    val loadedFromJar: Boolean,
    val metadata: PluginMetadata = PluginMetadata.DEFAULT,
    val parameterSchema: List<FilterParamSpec> = emptyList(),
)

/**
 * Immutable UI snapshot of the standalone `optical-filter` component state, including
 * ordered multi-slot matte box entries and physical UV filter telemetry.
 */
data class OpticalFilterStatus(
    val loaded: Boolean,
    val enabled: Boolean,
    val ndPreset: NdFilterPreset,
    val ndStops: Double,
    val bokehMask: BokehMaskShape,
    val bokehMaskAngleDeg: Double,
    val starEnabled: Boolean,
    val starPoints: Int,
    val starStrength: Double,
    val starAngleDeg: Double,
    val mistEnabled: Boolean,
    val mistPreset: String,
    val mistStrength: Double,
    val gndEnabled: Boolean,
    val gndStops: Double,
    val gndHorizon: Double,
    val gndFeather: Double,
    val gndAngleDeg: Double,
    val uvPreset: UvFilterPreset = UvFilterPreset.OFF,
    val uvTransmittance: Double = 1.0,
    val uvVeilingGlare: Double = 0.0,
    val uvGhostIntensity: Double = 0.0,
    val uvSoftness: Double = 0.0,
    val uvTintR: Double = 1.0,
    val uvTintG: Double = 1.0,
    val uvTintB: Double = 1.0,
    val slots: List<OpticalFilterSlotSpec> = emptyList(),
    val availableProviders: List<OpticalFilterProviderInfo> = emptyList(),
    val effectiveNdStopsOverride: Double? = null,
    val totalEvLossOverride: Double? = null,
    val activeSummary: String = "OFF",
    val totalTransmittance: Double = 1.0,
    val rescanPlugins: Long = 0L,
) {
    val availableFilterTypes: Set<String>
        get() = availableProviders.mapTo(LinkedHashSet()) { it.type }

    val pluginProviders: List<OpticalFilterProviderInfo>
        get() = availableProviders.filter { it.type !in BUILTIN_TYPES }

    fun providerFor(type: String): OpticalFilterProviderInfo? =
        availableProviders.firstOrNull { it.type.equals(type, ignoreCase = true) }
    val effectiveNdStops: Double
        get() = if (!enabled) 0.0 else (effectiveNdStopsOverride ?: ndStops)

    val totalEvLoss: Double
        get() = if (!enabled) {
            0.0
        } else {
            totalEvLossOverride ?: if (totalTransmittance >= 0.999999) 0.0 else (-ln(totalTransmittance.coerceIn(1e-6, 1.0)) / ln(2.0))
        }

    val ndFactor: Double get() = NdFilterSpec.factor(effectiveNdStops)
    val ndOpticalDensity: Double get() = NdFilterSpec.opticalDensity(effectiveNdStops)
    val ndTransmittancePercent: Double get() = NdFilterSpec.transmittancePercent(effectiveNdStops)

    fun ndTelemetryText(): String = if (!enabled) {
        I18n.t("filter.nd.telemetry.bypass")
    } else if (effectiveNdStops <= 1e-4) {
        I18n.t("filter.nd.telemetry.off")
    } else {
        val label = if (abs(effectiveNdStops - ndStops) <= 0.02) ndPreset.id else "STACK"
        I18n.t(
            "filter.nd.telemetry",
            label,
            effectiveNdStops,
            NdFilterSpec.formatFactor(effectiveNdStops),
            ndOpticalDensity,
            ndTransmittancePercent,
        )
    }

    fun telemetryBadgeText(): String = when {
        !loaded -> I18n.t("filter.unloaded")
        !enabled -> I18n.t("filter.telemetry.bypass")
        totalEvLoss <= 0.004 && slots.none { it.enabled } -> I18n.t("filter.telemetry.clear")
        else -> I18n.t("filter.telemetry.active", totalTransmittance * 100.0, totalEvLoss)
    }

    fun withAddedSlot(slot: OpticalFilterSlotSpec): Map<String, Any> {
        val next = slots.toMutableList()
        if (slot.type == "bokeh-mask") {
            val existingIdx = next.indexOfFirst { it.type == "bokeh-mask" }
            if (existingIdx >= 0) {
                next[existingIdx] = slot.copy(enabled = true)
                return buildSlotsPatch(next)
            }
        }
        if (next.size < OpticalFilterSlotCodec.MAX_SLOTS) {
            next.add(slot)
        }
        return buildSlotsPatch(next)
    }

    fun withRemovedSlot(index: Int): Map<String, Any> {
        if (index !in slots.indices) return buildSlotsPatch(slots)
        val next = slots.toMutableList()
        next.removeAt(index)
        return buildSlotsPatch(next)
    }

    fun withMovedSlot(index: Int, delta: Int): Map<String, Any> {
        val target = index + delta
        if (index !in slots.indices || target !in slots.indices || target == index) return buildSlotsPatch(slots)
        val next = slots.toMutableList()
        val item = next.removeAt(index)
        next.add(target, item)
        return buildSlotsPatch(next)
    }

    fun withToggledSlot(index: Int): Map<String, Any> {
        if (index !in slots.indices) return buildSlotsPatch(slots)
        val next = slots.toMutableList()
        val cur = next[index]
        next[index] = cur.copy(enabled = !cur.enabled)
        return buildSlotsPatch(next)
    }

    fun withUpdatedSlot(index: Int, updated: OpticalFilterSlotSpec): Map<String, Any> {
        if (index !in slots.indices) return buildSlotsPatch(slots)
        val next = slots.toMutableList()
        next[index] = updated
        return buildSlotsPatch(next)
    }

    companion object {
        val BUILTIN_TYPES: Set<String> = setOf("uv", "nd", "bokeh-mask", "star", "black-mist", "gnd")

        val DEFAULT = OpticalFilterStatus(
            loaded = false,
            enabled = true,
            ndPreset = NdFilterPreset.OFF,
            ndStops = 0.0,
            bokehMask = BokehMaskShape.OFF,
            bokehMaskAngleDeg = 0.0,
            starEnabled = false,
            starPoints = 6,
            starStrength = 0.75,
            starAngleDeg = 15.0,
            mistEnabled = false,
            mistPreset = "OFF",
            mistStrength = 0.0,
            gndEnabled = false,
            gndStops = 0.0,
            gndHorizon = 0.50,
            gndFeather = 0.25,
            gndAngleDeg = 0.0,
            uvPreset = UvFilterPreset.OFF,
            slots = emptyList(),
            activeSummary = "OFF",
            totalTransmittance = 1.0,
        )

        fun fromMaps(config: Map<String, Any?>?, state: Map<String, Any?>?): OpticalFilterStatus {
            if (config == null && state == null) return DEFAULT
            fun bool(key: String, def: Boolean): Boolean =
                (state?.get(key) as? Boolean) ?: (config?.get(key) as? Boolean) ?: def
            fun dbl(key: String, def: Double): Double =
                ((state?.get(key) as? Number) ?: (config?.get(key) as? Number))?.toDouble() ?: def
            fun int(key: String, def: Int): Int =
                ((state?.get(key) as? Number) ?: (config?.get(key) as? Number))?.toInt() ?: def
            fun str(key: String, def: String): String =
                (state?.get(key) as? String) ?: (config?.get(key) as? String) ?: def

            val enabled = bool("enabled", true)
            val ndStops = NdFilterSpec.clampStops(dbl("ndStops", 0.0))
            val ndPreset = NdFilterSpec.parsePreset(str("ndPreset", "")) ?: NdFilterSpec.presetForStops(ndStops)
            val bokehMask = BokehMaskShape.parse(str("bokehMask", "OFF")) ?: BokehMaskShape.OFF
            val bokehAngle = dbl("bokehMaskAngleDeg", 0.0)
            val starEnabled = bool("starEnabled", false)
            val starPoints = int("starPoints", 6)
            val starStrength = dbl("starStrength", 0.75).coerceIn(0.0, 2.0)
            val starAngle = dbl("starAngleDeg", 15.0)
            val mistEnabled = bool("mistEnabled", false)
            val mistStrength = dbl("mistStrength", 0.0).coerceIn(0.0, 1.0)
            val mistPreset = str("mistPreset", "OFF")
            val gndEnabled = bool("gndEnabled", false)
            val gndStops = dbl("gndStops", 0.0).coerceIn(0.0, 5.0)
            val gndHorizon = dbl("gndHorizon", 0.50).coerceIn(0.0, 1.0)
            val gndFeather = dbl("gndFeather", 0.25).coerceIn(0.02, 1.0)
            val gndAngle = dbl("gndAngleDeg", 0.0)

            val uvPreset = UvFilterPreset.parse(str("uvPreset", "OFF")) ?: UvFilterPreset.OFF
            val uvTrans = dbl("uvTransmittance", uvPreset.transmittance).coerceIn(0.50, 1.0)
            val uvGlare = dbl("uvVeilingGlare", uvPreset.veilingGlare).coerceIn(0.0, 0.50)
            val uvGhost = dbl("uvGhostIntensity", uvPreset.ghostIntensity).coerceIn(0.0, 0.50)
            val uvSoft = dbl("uvSoftness", uvPreset.softness).coerceIn(0.0, 1.0)
            val uvTintR = dbl("uvTintR", uvPreset.tintR).coerceIn(0.75, 1.25)
            val uvTintG = dbl("uvTintG", uvPreset.tintG).coerceIn(0.75, 1.25)
            val uvTintB = dbl("uvTintB", uvPreset.tintB).coerceIn(0.75, 1.25)

            val parsedSlots = extractSlots(
                config = config,
                state = state,
                uvPreset = uvPreset,
                uvTrans = uvTrans,
                uvGlare = uvGlare,
                uvGhost = uvGhost,
                uvSoft = uvSoft,
                uvTintR = uvTintR,
                uvTintG = uvTintG,
                uvTintB = uvTintB,
                ndPreset = ndPreset,
                ndStops = ndStops,
                bokehMask = bokehMask,
                bokehAngle = bokehAngle,
                starEnabled = starEnabled,
                starPoints = starPoints,
                starStrength = starStrength,
                starAngle = starAngle,
                mistEnabled = mistEnabled,
                mistPreset = mistPreset,
                mistStrength = mistStrength,
                gndEnabled = gndEnabled,
                gndStops = gndStops,
                gndHorizon = gndHorizon,
                gndFeather = gndFeather,
                gndAngle = gndAngle,
            )

            val activeSummary = (state?.get("activeSummary") as? String) ?: if (!enabled) "BYPASSED" else "OFF"
            val totalTrans = (state?.get("totalTransmittance") as? Number)?.toDouble()
                ?: NdFilterSpec.transmittance(if (enabled) ndStops else 0.0)
            val effNdOverride = (state?.get("effectiveNdStops") as? Number)?.toDouble()
            val evLossOverride = (state?.get("totalEvLoss") as? Number)?.toDouble()
            val availableProviders = parseProviders(state)
            val rescanPlugins = ((state?.get("rescanPlugins") as? Number) ?: (config?.get("rescanPlugins") as? Number))?.toLong() ?: 0L

            return OpticalFilterStatus(
                loaded = true,
                enabled = enabled,
                ndPreset = ndPreset,
                ndStops = ndStops,
                bokehMask = bokehMask,
                bokehMaskAngleDeg = bokehAngle,
                starEnabled = starEnabled,
                starPoints = starPoints,
                starStrength = starStrength,
                starAngleDeg = starAngle,
                mistEnabled = mistEnabled,
                mistPreset = mistPreset,
                mistStrength = mistStrength,
                gndEnabled = gndEnabled,
                gndStops = gndStops,
                gndHorizon = gndHorizon,
                gndFeather = gndFeather,
                gndAngleDeg = gndAngle,
                uvPreset = uvPreset,
                uvTransmittance = uvTrans,
                uvVeilingGlare = uvGlare,
                uvGhostIntensity = uvGhost,
                uvSoftness = uvSoft,
                uvTintR = uvTintR,
                uvTintG = uvTintG,
                uvTintB = uvTintB,
                slots = parsedSlots,
                availableProviders = availableProviders,
                effectiveNdStopsOverride = effNdOverride,
                totalEvLossOverride = evLossOverride,
                activeSummary = activeSummary,
                totalTransmittance = totalTrans,
                rescanPlugins = rescanPlugins,
            )
        }

        private fun parseProviders(state: Map<String, Any?>?): List<OpticalFilterProviderInfo> {
            val raw = state?.get("availableFilterProviders") as? List<*>
            if (raw != null) {
                val parsed = raw.mapNotNull { entry ->
                    val m = entry as? Map<*, *> ?: return@mapNotNull null
                    val type = (m["type"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val name = (m["displayName"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: type
                    val fromJar = m["loadedFromJar"] as? Boolean ?: false
                    val meta = PluginMetadata.fromStateMap(m["metadata"] as? Map<*, *>)
                    val schema = parseParameterSchema(m["parameterSchema"] as? List<*>)
                    OpticalFilterProviderInfo(
                        type = type,
                        displayName = name,
                        loadedFromJar = fromJar,
                        metadata = meta,
                        parameterSchema = schema,
                    )
                }
                if (parsed.isNotEmpty()) return parsed
            }
            val rawTypes = state?.get("availableFilterTypes") as? List<*> ?: return emptyList()
            return rawTypes.mapNotNull { t ->
                val type = (t as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                OpticalFilterProviderInfo(type = type, displayName = type, loadedFromJar = false)
            }
        }

        private fun parseParameterSchema(rawList: List<*>?): List<FilterParamSpec> {
            if (rawList.isNullOrEmpty()) return emptyList()
            fun parseLocaleMap(raw: Any?): Map<String, String> {
                val m = raw as? Map<*, *> ?: return emptyMap()
                val out = LinkedHashMap<String, String>()
                for ((k, v) in m) {
                    val ks = (k as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val vs = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    out[ks] = vs
                }
                return out
            }
            return rawList.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val key = (m["key"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val type = runCatching {
                    FilterParamType.valueOf(((m["type"] as? String) ?: "TEXT").trim().uppercase(Locale.ROOT))
                }.getOrElse { FilterParamType.TEXT }
                val labelEn = (m["labelEn"] as? String)?.takeIf { it.isNotBlank() } ?: key
                val labelZh = (m["labelZh"] as? String)?.takeIf { it.isNotBlank() } ?: labelEn
                val descEn = (m["descriptionEn"] as? String).orEmpty()
                val descZh = (m["descriptionZh"] as? String)?.takeIf { it.isNotBlank() } ?: descEn
                val min = (m["min"] as? Number)?.toDouble()
                val max = (m["max"] as? Number)?.toDouble()
                val step = (m["step"] as? Number)?.toDouble()
                val unit = (m["unit"] as? String).orEmpty()
                val choices = (m["choices"] as? List<*>)?.mapNotNull { c ->
                    val cm = c as? Map<*, *> ?: return@mapNotNull null
                    val v = (cm["value"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val en = (cm["labelEn"] as? String)?.takeIf { it.isNotBlank() } ?: v
                    val zh = (cm["labelZh"] as? String)?.takeIf { it.isNotBlank() } ?: en
                    FilterParamChoice(value = v, labelEn = en, labelZh = zh, labels = parseLocaleMap(cm["labels"]))
                }.orEmpty()
                if (type == FilterParamType.ENUM && choices.isEmpty()) return@mapNotNull null
                val defVal: Any = m["defaultValue"] ?: when (type) {
                    FilterParamType.NUMBER -> min ?: 0.0
                    FilterParamType.ENUM -> choices.first().value
                    FilterParamType.BOOLEAN -> false
                    FilterParamType.TEXT -> ""
                }
                runCatching {
                    FilterParamSpec(
                        key = key,
                        type = type,
                        labelEn = labelEn,
                        labelZh = labelZh,
                        descriptionEn = descEn,
                        descriptionZh = descZh,
                        defaultValue = defVal,
                        min = min,
                        max = max,
                        step = step,
                        unit = unit,
                        choices = choices,
                        labels = parseLocaleMap(m["labels"]),
                        descriptions = parseLocaleMap(m["descriptions"]),
                    )
                }.getOrNull()
            }
        }

        private fun extractSlots(
            config: Map<String, Any?>?,
            state: Map<String, Any?>?,
            uvPreset: UvFilterPreset,
            uvTrans: Double,
            uvGlare: Double,
            uvGhost: Double,
            uvSoft: Double,
            uvTintR: Double,
            uvTintG: Double,
            uvTintB: Double,
            ndPreset: NdFilterPreset,
            ndStops: Double,
            bokehMask: BokehMaskShape,
            bokehAngle: Double,
            starEnabled: Boolean,
            starPoints: Int,
            starStrength: Double,
            starAngle: Double,
            mistEnabled: Boolean,
            mistPreset: String,
            mistStrength: Double,
            gndEnabled: Boolean,
            gndStops: Double,
            gndHorizon: Double,
            gndFeather: Double,
            gndAngle: Double,
        ): List<OpticalFilterSlotSpec> {
            val stateSlots = state?.get("slots")
            if (stateSlots is List<*>) {
                val parsed = stateSlots.mapNotNull { entry ->
                    (entry as? Map<*, *>)?.let { OpticalFilterSlotCodec.fromMap(it) }
                }
                if (parsed.isNotEmpty() || state["slotMode"] == true) return parsed
            }
            val jsonStr = (state?.get("slotsJson") as? String)
                ?: (config?.get("slots") as? String)
                ?: (state?.get("slots") as? String)
            if (!jsonStr.isNullOrBlank()) {
                val parsed = OpticalFilterSlotCodec.parse(jsonStr)
                if (parsed.isNotEmpty()) return parsed
            }
            // Synthesize virtual slots from flat settings for backward-compatible stubs/tests.
            val out = ArrayList<OpticalFilterSlotSpec>(6)
            if (uvPreset != UvFilterPreset.OFF) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "uv",
                        enabled = true,
                        params = linkedMapOf(
                            "preset" to uvPreset.id,
                            "transmittance" to uvTrans,
                            "veilingGlare" to uvGlare,
                            "ghostIntensity" to uvGhost,
                            "softness" to uvSoft,
                            "tintR" to uvTintR,
                            "tintG" to uvTintG,
                            "tintB" to uvTintB,
                        ),
                    ),
                )
            }
            if (ndStops > 1e-4) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "nd",
                        enabled = true,
                        params = linkedMapOf("preset" to ndPreset.id, "stops" to ndStops),
                    ),
                )
            }
            if (bokehMask != BokehMaskShape.OFF) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "bokeh-mask",
                        enabled = true,
                        params = linkedMapOf("shape" to bokehMask.id, "angleDeg" to bokehAngle, "scale" to 0.96),
                    ),
                )
            }
            if (starEnabled) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "star",
                        enabled = true,
                        params = linkedMapOf(
                            "points" to starPoints.toLong(),
                            "strength" to starStrength,
                            "angleDeg" to starAngle,
                        ),
                    ),
                )
            }
            if (mistEnabled && mistStrength > 1e-4) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "black-mist",
                        enabled = true,
                        params = linkedMapOf("preset" to mistPreset, "strength" to mistStrength),
                    ),
                )
            }
            if (gndEnabled && gndStops > 1e-4) {
                out.add(
                    OpticalFilterSlotSpec(
                        type = "gnd",
                        enabled = true,
                        params = linkedMapOf(
                            "stops" to gndStops,
                            "horizon" to gndHorizon,
                            "feather" to gndFeather,
                            "angleDeg" to gndAngle,
                        ),
                    ),
                )
            }
            return out
        }

        fun defaultSlot(
            type: String,
            preset: String? = null,
            provider: OpticalFilterProviderInfo? = null,
        ): OpticalFilterSlotSpec = when (type.lowercase(Locale.ROOT)) {
            "uv" -> {
                val p = UvFilterPreset.parse(preset) ?: UvFilterPreset.PRO_MRC
                val resolved = if (p == UvFilterPreset.OFF) UvFilterPreset.PRO_MRC else p
                OpticalFilterSlotSpec(
                    type = "uv",
                    enabled = true,
                    params = linkedMapOf(
                        "preset" to resolved.id,
                        "transmittance" to resolved.transmittance,
                        "veilingGlare" to resolved.veilingGlare,
                        "ghostIntensity" to resolved.ghostIntensity,
                        "softness" to resolved.softness,
                        "tintR" to resolved.tintR,
                        "tintG" to resolved.tintG,
                        "tintB" to resolved.tintB,
                    ),
                )
            }
            "nd" -> {
                val p = NdFilterSpec.parsePreset(preset)
                val stops = p?.fixedStops ?: preset?.toDoubleOrNull()?.let { NdFilterSpec.clampStops(it) } ?: 3.0
                val finalPreset = p?.takeIf { it != NdFilterPreset.OFF } ?: NdFilterSpec.presetForStops(stops)
                OpticalFilterSlotSpec(
                    type = "nd",
                    enabled = true,
                    params = linkedMapOf("preset" to finalPreset.id, "stops" to stops),
                )
            }
            "bokeh", "bokeh-mask" -> {
                val s = BokehMaskShape.parse(preset)?.takeIf { it != BokehMaskShape.OFF } ?: BokehMaskShape.STAR_5
                OpticalFilterSlotSpec(
                    type = "bokeh-mask",
                    enabled = true,
                    params = linkedMapOf("shape" to s.id, "angleDeg" to 0.0, "scale" to 0.96),
                )
            }
            "star", "cross" -> {
                val pts = preset?.lowercase(Locale.ROOT)?.removeSuffix("pt")?.toIntOrNull()?.let {
                    when {
                        it <= 4 -> 4L
                        it <= 6 -> 6L
                        else -> 8L
                    }
                } ?: 6L
                OpticalFilterSlotSpec(
                    type = "star",
                    enabled = true,
                    params = linkedMapOf("points" to pts, "strength" to 0.75, "angleDeg" to 15.0, "threshold" to 0.55, "length" to 0.12),
                )
            }
            "mist", "black-mist" -> {
                val p = preset?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("1/8", "1/4", "1/2", "1") } ?: "1/4"
                val s = when (p) {
                    "1/8" -> 0.125
                    "1/2" -> 0.50
                    "1" -> 1.0
                    else -> 0.25
                }
                OpticalFilterSlotSpec(
                    type = "black-mist",
                    enabled = true,
                    params = linkedMapOf("preset" to p, "strength" to s, "threshold" to 0.35),
                )
            }
            "gnd", "grad" -> {
                val stops = preset?.toDoubleOrNull()?.coerceIn(0.1, 5.0) ?: 2.0
                OpticalFilterSlotSpec(
                    type = "gnd",
                    enabled = true,
                    params = linkedMapOf("stops" to stops, "horizon" to 0.50, "feather" to 0.25, "angleDeg" to 0.0),
                )
            }
            else -> {
                val defaults = LinkedHashMap<String, Any>()
                provider?.parameterSchema?.forEach { spec ->
                    defaults[spec.key] = spec.defaultValue
                }
                OpticalFilterSlotSpec(type = type.lowercase(Locale.ROOT), enabled = true, params = defaults)
            }
        }

        fun buildRescanPluginsPatch(currentRescanPlugins: Long): Map<String, Any> =
            linkedMapOf("rescanPlugins" to (currentRescanPlugins + 1L))

        fun buildSlotsPatch(slots: List<OpticalFilterSlotSpec>): Map<String, Any> =
            linkedMapOf("enabled" to true, "slots" to OpticalFilterSlotCodec.encode(slots))

        fun buildUvPresetPatch(preset: UvFilterPreset): Map<String, Any> = linkedMapOf(
            "enabled" to true,
            "uvPreset" to preset.id,
            "uvTransmittance" to preset.transmittance,
            "uvVeilingGlare" to preset.veilingGlare,
            "uvGhostIntensity" to preset.ghostIntensity,
            "uvSoftness" to preset.softness,
            "uvTintR" to preset.tintR,
            "uvTintG" to preset.tintG,
            "uvTintB" to preset.tintB,
        )

        fun buildNdPresetPatch(preset: NdFilterPreset, currentStops: Double = 0.0): Map<String, Any> {
            val stops = preset.fixedStops ?: NdFilterSpec.clampStops(if (currentStops > 0.0) currentStops else 1.5)
            return linkedMapOf("enabled" to true, "ndPreset" to preset.id, "ndStops" to stops)
        }

        fun buildNdStopsPatch(stops: Double): Map<String, Any> {
            val rounded = round(NdFilterSpec.clampStops(stops) * 10.0) / 10.0
            val preset = NdFilterSpec.presetForStops(rounded)
            return linkedMapOf("enabled" to true, "ndStops" to rounded, "ndPreset" to preset.id)
        }

        fun buildBokehMaskPatch(shape: BokehMaskShape, angleDeg: Double? = null): Map<String, Any> = linkedMapOf<String, Any>(
            "enabled" to true,
            "bokehMask" to shape.id,
        ).apply {
            if (angleDeg != null) put("bokehMaskAngleDeg", angleDeg.coerceIn(-180.0, 180.0))
        }

        fun buildStarPatch(points: Int?, strength: Double? = null): Map<String, Any> {
            if (points == null || points <= 0) {
                return linkedMapOf("starEnabled" to false)
            }
            val snapped = when {
                points <= 4 -> 4L
                points <= 6 -> 6L
                else -> 8L
            }
            return linkedMapOf<String, Any>(
                "enabled" to true,
                "starEnabled" to true,
                "starPoints" to snapped,
            ).apply {
                if (strength != null) put("starStrength", strength.coerceIn(0.0, 2.0))
            }
        }

        fun buildMistPatch(preset: String): Map<String, Any> {
            val norm = preset.trim().uppercase(Locale.ROOT)
            val s = when (norm) {
                "OFF", "0" -> 0.0
                "1/8" -> 0.125
                "1/4" -> 0.25
                "1/2" -> 0.50
                "1" -> 1.0
                else -> 0.25
            }
            return linkedMapOf(
                "enabled" to true,
                "mistEnabled" to (s > 1e-4),
                "mistPreset" to if (s <= 1e-4) "OFF" else norm,
                "mistStrength" to s,
            )
        }

        fun buildClearAllPatch(): Map<String, Any> = linkedMapOf(
            "enabled" to true,
            "slots" to "",
            "ndPreset" to NdFilterPreset.OFF.id,
            "ndStops" to 0.0,
            "bokehMask" to BokehMaskShape.OFF.id,
            "starEnabled" to false,
            "mistEnabled" to false,
            "mistPreset" to "OFF",
            "mistStrength" to 0.0,
            "gndEnabled" to false,
            "gndStops" to 0.0,
            "uvPreset" to UvFilterPreset.OFF.id,
            "uvTransmittance" to 1.0,
            "uvVeilingGlare" to 0.0,
            "uvGhostIntensity" to 0.0,
            "uvSoftness" to 0.0,
            "uvTintR" to 1.0,
            "uvTintG" to 1.0,
            "uvTintB" to 1.0,
        )

        fun formatSlotBadge(slot: OpticalFilterSlotSpec): String = when (slot.type) {
            "uv" -> {
                val p = UvFilterPreset.parse((slot.params["preset"] ?: slot.params["uvPreset"]) as? String)
                    ?: UvFilterPreset.CUSTOM
                val trans = ((slot.params["transmittance"] as? Number)?.toDouble() ?: p.transmittance) * 100.0
                val pName = when (p) {
                    UvFilterPreset.PRO_MRC -> I18n.t("filter.uv.preset.pro")
                    UvFilterPreset.STANDARD -> I18n.t("filter.uv.preset.standard")
                    UvFilterPreset.CHEAP_POOR -> I18n.t("filter.uv.preset.cheap")
                    else -> I18n.t("filter.uv.preset.custom")
                }
                String.format(Locale.ROOT, "UV: %s (%.1f%%)", pName, trans)
            }
            "nd" -> {
                val stops = NdFilterSpec.clampStops(
                    ((slot.params["stops"] ?: slot.params["ndStops"]) as? Number)?.toDouble()
                        ?: NdFilterSpec.parsePreset(slot.params["preset"] as? String)?.fixedStops
                        ?: 3.0,
                )
                NdFilterSpec.formatSummary(stops)
            }
            "bokeh-mask" -> {
                val s = BokehMaskShape.parse((slot.params["shape"] ?: slot.params["bokehMask"]) as? String)
                    ?: BokehMaskShape.STAR_5
                when (s) {
                    BokehMaskShape.STAR_5 -> I18n.t("filter.bokeh.shape.star5")
                    BokehMaskShape.HEART -> I18n.t("filter.bokeh.shape.heart")
                    BokehMaskShape.HEXAGON -> I18n.t("filter.bokeh.shape.hexagon")
                    BokehMaskShape.RING -> I18n.t("filter.bokeh.shape.ring")
                    BokehMaskShape.CUSTOM -> I18n.t("filter.bokeh.shape.custom")
                    BokehMaskShape.OFF -> I18n.t("filter.bokeh.shape.off")
                }
            }
            "star" -> {
                val pts = ((slot.params["points"] as? Number)?.toInt() ?: 6)
                "${I18n.t("filter.type.star")} ${pts}pt"
            }
            "black-mist" -> {
                val p = (slot.params["preset"] as? String)?.takeIf { it.isNotBlank() && !it.equals("CUSTOM", true) }
                    ?: String.format(Locale.ROOT, "%.2f", (slot.params["strength"] as? Number)?.toDouble() ?: 0.25)
                "${I18n.t("filter.type.mist")} $p"
            }
            "gnd" -> {
                val stops = ((slot.params["stops"] as? Number)?.toDouble() ?: 2.0)
                String.format(Locale.ROOT, "GND %.1f EV", stops)
            }
            else -> slot.type
        }
    }
}

/**
 * Matte Box / Ordered Multi-Slot Optical Filter Rack UI pane (patching component `"optical-filter"`).
 *
 * Supports two synchronized display modes with 100% reactive [I18n] bindings:
 * - [Mode.COMPACT]: Studio quick strip with Swing Away bypass, live Total T% / EV Loss badge,
 *   clickable ordered active filter pill chain (`[UV: Cheap] -> [ND8] -> [★5 Star]`),
 *   1-click `+ Add Filter` menu, starter pills, and `Rack` shortcut.
 * - [Mode.DETAILED]: Full Matte Box Filter Rack with `+ Add Filter` menu, starter recipes,
 *   and ordered collapsible slot cards (`#1..#N`) supporting per-slot ON/OFF, reorder up/down,
 *   remove, instant preset pills, and live physical parameter sliders.
 */
class OpticalFilterPane(
    private val mode: Mode = Mode.COMPACT,
    private val patchOpticalFilter: ((Map<String, Any>) -> CompletableFuture<*>)? = null,
    private val onOpenFilterRack: (() -> Unit)? = null,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    enum class Mode { COMPACT, DETAILED }

    private var syncing = false
    private var lastStatus = OpticalFilterStatus.DEFAULT
    private var lastRenderedSlotsKey: String = ""
    private var lastRenderedEnabled: Boolean = true
    private var lastRenderedLocaleCode: String = I18n.localeCode()
    private var lastRenderedPluginsKey: String = ""
    private val expandedSlots = HashSet<Int>()

    private val masterToggle = ToggleButton().apply {
        id = if (mode == Mode.COMPACT) "filter-compact-master" else "filter-detail-master"
        styleClass += "studio-header-btn-secondary"
        isSelected = true
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("filter.master.tooltip")) }
        setOnAction {
            if (!syncing) {
                val on = isSelected
                refreshMasterLabel(on)
                applyPatch(
                    mapOf("enabled" to on),
                    I18n.t(if (on) "filter.status.master_on" else "filter.status.master_bypass"),
                )
            }
        }
    }

    private val evBadge = Label().apply {
        id = if (mode == Mode.COMPACT) "filter-compact-ev-badge" else "filter-detail-ev-badge"
        styleClass += "filter-ev-badge"
    }

    private val builtInAddItems: List<MenuItem> = listOf(
        buildAddMenuItem("filter.add.uv_pro") { addSlot(OpticalFilterStatus.defaultSlot("uv", "PRO_MRC")) },
        buildAddMenuItem("filter.add.uv_standard") { addSlot(OpticalFilterStatus.defaultSlot("uv", "STANDARD")) },
        buildAddMenuItem("filter.add.uv_cheap") { addSlot(OpticalFilterStatus.defaultSlot("uv", "CHEAP_POOR")) },
        buildAddMenuItem("filter.add.nd") { addSlot(OpticalFilterStatus.defaultSlot("nd", "ND8")) },
        buildAddMenuItem("filter.add.bokeh") { addSlot(OpticalFilterStatus.defaultSlot("bokeh-mask", "STAR_5")) },
        buildAddMenuItem("filter.add.star") { addSlot(OpticalFilterStatus.defaultSlot("star", "6")) },
        buildAddMenuItem("filter.add.mist") { addSlot(OpticalFilterStatus.defaultSlot("black-mist", "1/4")) },
        buildAddMenuItem("filter.add.gnd") { addSlot(OpticalFilterStatus.defaultSlot("gnd", "2.0")) },
    )

    private val addFilterMenu = MenuButton().apply {
        id = if (mode == Mode.COMPACT) "filter-compact-add" else "filter-detail-add"
        styleClass += "studio-header-btn"
        textProperty().bind(I18n.bind("filter.add.button"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("filter.add.tooltip")) }
        items.setAll(builtInAddItems)
    }

    private fun syncPluginAddItems(plugins: List<OpticalFilterProviderInfo>): Boolean {
        val locale = I18n.localeCode()
        val key = locale + "|" + plugins.joinToString("|") { p ->
            "${p.type}:${p.displayName}:${p.loadedFromJar}:${p.metadata.version}:${p.parameterSchema.size}"
        }
        if (key == lastRenderedPluginsKey) return false
        lastRenderedPluginsKey = key
        val nextItems = ArrayList<MenuItem>(builtInAddItems.size + plugins.size)
        nextItems.addAll(builtInAddItems)
        for (p in plugins) {
            val tag = if (p.loadedFromJar) "[JAR] " else "[Plugin] "
            val localizedDisplay = p.metadata.localizedName(p.displayName, locale)
            nextItems.add(
                MenuItem("$tag$localizedDisplay (${p.type})").apply {
                    id = "filter-add-plugin-${p.type}"
                    setOnAction { addSlot(OpticalFilterStatus.defaultSlot(p.type, provider = p)) }
                },
            )
        }
        addFilterMenu.items.setAll(nextItems)
        return true
    }

    private val rescanPluginsBtn = Button().apply {
        id = if (mode == Mode.COMPACT) "filter-compact-rescan" else "filter-detail-rescan"
        styleClass += "studio-header-btn-secondary"
        textProperty().bind(I18n.bind("filter.rescan"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("filter.rescan.tooltip")) }
        setOnAction {
            applyPatch(
                OpticalFilterStatus.buildRescanPluginsPatch(lastStatus.rescanPlugins),
                I18n.t("filter.status.rescanned"),
            )
        }
    }

    private val clearAllBtn = Button().apply {
        id = if (mode == Mode.COMPACT) "filter-compact-clear" else "filter-detail-clear"
        styleClass += "studio-header-btn-secondary"
        textProperty().bind(I18n.bind("filter.clear"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("filter.clear.tooltip")) }
        setOnAction {
            expandedSlots.clear()
            applyPatch(OpticalFilterStatus.buildClearAllPatch(), I18n.t("filter.status.cleared"))
        }
    }

    private val openRackBtn = Button().apply {
        id = "filter-compact-open-rack"
        styleClass += "studio-header-btn-secondary"
        textProperty().bind(I18n.bind("filter.open_rack"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("filter.open_rack.tooltip")) }
        isVisible = (onOpenFilterRack != null)
        isManaged = (onOpenFilterRack != null)
        setOnAction { onOpenFilterRack?.invoke() }
    }

    private val pillChain = FlowPane(4.0, 4.0).apply {
        id = "filter-compact-chain"
        alignment = Pos.CENTER_LEFT
    }

    private val slotsContainer = VBox(6.0).apply {
        id = "filter-detail-slots"
    }

    private val localeListener = I18n.onLocaleChange {
        refreshMasterLabel(lastStatus.enabled)
        evBadge.text = lastStatus.telemetryBadgeText()
        syncPluginAddItems(lastStatus.pluginProviders)
        renderRackContents(forceRebuild = true)
    }

    val root: VBox = if (mode == Mode.COMPACT) buildCompactLayout() else buildDetailedLayout()

    init {
        refreshMasterLabel(true)
        evBadge.text = lastStatus.telemetryBadgeText()
        renderRackContents(forceRebuild = true)
    }

    private fun buildAddMenuItem(labelKey: String, onSelect: () -> Unit): MenuItem = MenuItem().apply {
        textProperty().bind(I18n.bind(labelKey))
        setOnAction { onSelect() }
    }

    private fun addSlot(slot: OpticalFilterSlotSpec) {
        val slots = lastStatus.slots
        val existing = if (slot.type == "bokeh-mask") slots.indexOfFirst { it.type == slot.type } else -1
        if (existing >= 0) {
            if (expandedSlots.add(existing)) renderRackContents(forceRebuild = true)
        } else {
            if (slots.size >= OpticalFilterSlotCodec.MAX_SLOTS) {
                onStatus(I18n.t("filter.status.max_slots", OpticalFilterSlotCodec.MAX_SLOTS))
                return
            }
            expandedSlots.add(slots.size)
        }
        applyPatch(lastStatus.withAddedSlot(slot), I18n.t("filter.status.updated"))
    }

    private fun refreshMasterLabel(on: Boolean) {
        masterToggle.text = I18n.t(if (on) "filter.master.on" else "filter.master.bypass")
    }

    private fun buildCompactLayout(): VBox {
        val headerRow = HBox(
            5.0,
            Label().apply {
                styleClass += "studio-section-title"
                textProperty().bind(I18n.bind("filter.compact.title"))
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            masterToggle,
            openRackBtn,
            clearAllBtn,
        ).apply { alignment = Pos.CENTER_LEFT }

        val quickRow = HBox(
            5.0,
            addFilterMenu,
            evBadge,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        ).apply { alignment = Pos.CENTER_LEFT }

        val quickPills = FlowPane(4.0, 4.0).apply {
            children += quickRecipeButton("filter-compact-quick-uv-pro", "filter.recipe.pro_uv") {
                addSlot(OpticalFilterStatus.defaultSlot("uv", "PRO_MRC"))
            }
            children += quickRecipeButton("filter-compact-quick-uv-cheap", "filter.recipe.cheap_uv") {
                addSlot(OpticalFilterStatus.defaultSlot("uv", "CHEAP_POOR"))
            }
            children += Button("ND8").apply {
                id = "filter-compact-quick-nd8"
                styleClass += "filter-preset-chip"
                setOnAction { addSlot(OpticalFilterStatus.defaultSlot("nd", "ND8")) }
            }
            children += quickRecipeButton("filter-compact-quick-bokeh", "filter.bokeh.shape.star5") {
                addSlot(OpticalFilterStatus.defaultSlot("bokeh-mask", "STAR_5"))
            }
        }

        return VBox(6.0, headerRow, quickRow, pillChain, quickPills).apply {
            id = "optical-filter-compact-pane"
            styleClass += "studio-card"
        }
    }

    private fun buildDetailedLayout(): VBox {
        val headerRow = HBox(
            6.0,
            Label().apply {
                styleClass += "studio-section-title"
                textProperty().bind(I18n.bind("filter.detail.title"))
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            masterToggle,
            clearAllBtn,
        ).apply { alignment = Pos.CENTER_LEFT }

        val actionBar = HBox(
            6.0,
            addFilterMenu,
            rescanPluginsBtn,
            evBadge,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        ).apply { alignment = Pos.CENTER_LEFT }

        val orderHint = Label().apply {
            styleClass += "studio-hint-text"
            isWrapText = true
            textProperty().bind(I18n.bind("filter.detail.order_hint"))
        }

        val recipeBar = FlowPane(4.0, 4.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label().apply {
                styleClass += "studio-table-head"
                textProperty().bind(I18n.bind("filter.recipe.label"))
            }
            children += quickRecipeButton("filter-recipe-pro-uv", "filter.recipe.pro_uv") {
                expandedSlots.clear()
                expandedSlots.add(0)
                applyPatch(
                    OpticalFilterStatus.buildSlotsPatch(listOf(OpticalFilterStatus.defaultSlot("uv", "PRO_MRC"))),
                    I18n.t("filter.status.updated"),
                )
            }
            children += quickRecipeButton("filter-recipe-cheap-uv", "filter.recipe.cheap_uv") {
                expandedSlots.clear()
                expandedSlots.add(0)
                applyPatch(
                    OpticalFilterStatus.buildSlotsPatch(listOf(OpticalFilterStatus.defaultSlot("uv", "CHEAP_POOR"))),
                    I18n.t("filter.status.updated"),
                )
            }
            children += quickRecipeButton("filter-recipe-nd8-nd64", "filter.recipe.nd8_nd64") {
                expandedSlots.clear()
                expandedSlots.add(0)
                expandedSlots.add(1)
                applyPatch(
                    OpticalFilterStatus.buildSlotsPatch(
                        listOf(
                            OpticalFilterStatus.defaultSlot("nd", "ND8"),
                            OpticalFilterStatus.defaultSlot("nd", "ND64"),
                        ),
                    ),
                    I18n.t("filter.status.updated"),
                )
            }
            children += quickRecipeButton("filter-recipe-night-cinema", "filter.recipe.night_cinema") {
                expandedSlots.clear()
                expandedSlots.add(0)
                applyPatch(
                    OpticalFilterStatus.buildSlotsPatch(
                        listOf(
                            OpticalFilterStatus.defaultSlot("bokeh-mask", "STAR_5"),
                            OpticalFilterStatus.defaultSlot("star", "6"),
                            OpticalFilterStatus.defaultSlot("black-mist", "1/4"),
                        ),
                    ),
                    I18n.t("filter.status.updated"),
                )
            }
        }

        return VBox(7.0, headerRow, actionBar, orderHint, recipeBar, slotsContainer).apply {
            id = "optical-filter-detailed-pane"
            styleClass += "studio-card"
        }
    }

    private fun quickRecipeButton(nodeId: String, labelKey: String, onClick: () -> Unit): Button = Button().apply {
        id = nodeId
        styleClass += "filter-preset-chip"
        textProperty().bind(I18n.bind(labelKey))
        setOnAction { onClick() }
    }

    private fun renderRackContents(forceRebuild: Boolean) {
        val slots = lastStatus.slots
        val key = OpticalFilterSlotCodec.encode(slots)
        val localeCode = I18n.localeCode()
        if (!forceRebuild &&
            key == lastRenderedSlotsKey &&
            lastStatus.enabled == lastRenderedEnabled &&
            localeCode == lastRenderedLocaleCode
        ) {
            return
        }
        lastRenderedSlotsKey = key
        lastRenderedEnabled = lastStatus.enabled
        lastRenderedLocaleCode = localeCode

        if (mode == Mode.COMPACT) {
            rebuildCompactChain(slots)
        } else {
            rebuildDetailedSlots(slots)
        }
    }

    private fun rebuildCompactChain(slots: List<OpticalFilterSlotSpec>) {
        pillChain.children.clear()
        if (slots.isEmpty()) {
            pillChain.children += Label(I18n.t("filter.compact.empty")).apply {
                styleClass += "studio-hint-text"
                isWrapText = true
            }
            return
        }
        slots.forEachIndexed { idx, slot ->
            if (idx > 0) {
                pillChain.children += Label("→").apply { styleClass += "filter-chain-arrow" }
            }
            val labelText = "#${idx + 1} ${OpticalFilterStatus.formatSlotBadge(slot)}" +
                if (!slot.enabled) " (${I18n.t("filter.slot.disabled_tag")})" else ""
            val pill = Button(labelText).apply {
                id = "filter-compact-pill-$idx"
                styleClass += "filter-chain-pill"
                if (!slot.enabled || !lastStatus.enabled) {
                    styleClass += "filter-chain-pill-off"
                }
                tooltip = Tooltip(I18n.t("filter.slot.toggle.tooltip"))
                setOnAction {
                    applyPatch(lastStatus.withToggledSlot(idx), I18n.t("filter.status.updated"))
                }
            }
            pillChain.children += pill
        }
    }

    private fun rebuildDetailedSlots(slots: List<OpticalFilterSlotSpec>) {
        slotsContainer.children.clear()
        if (slots.isEmpty()) {
            slotsContainer.children += Label(I18n.t("filter.detail.empty")).apply {
                styleClass += "studio-hint-text"
                isWrapText = true
                padding = Insets(4.0, 2.0, 4.0, 2.0)
            }
            return
        }
        if (expandedSlots.isEmpty() && slots.size <= 3) {
            for (i in slots.indices) expandedSlots.add(i)
        }
        slots.forEachIndexed { idx, slot ->
            slotsContainer.children += buildSlotCard(idx, slot, slots.size)
        }
    }

    private fun buildSlotCard(index: Int, slot: OpticalFilterSlotSpec, totalCount: Int): VBox {
        val isExpanded = index in expandedSlots
        val indexBadge = Label("#${index + 1}").apply { styleClass += "filter-slot-index" }
        val titleLabel = Label(OpticalFilterStatus.formatSlotBadge(slot)).apply {
            id = "filter-slot-title-$index"
            styleClass += "studio-table-head"
            isWrapText = true
            val desc = lastStatus.providerFor(slot.type)?.metadata?.localizedDescription(I18n.localeCode()).orEmpty()
            if (desc.isNotBlank()) {
                tooltip = Tooltip(desc)
            }
        }

        val toggleBtn = Button(if (slot.enabled) "●" else "○").apply {
            id = "filter-slot-toggle-$index"
            styleClass += "filter-slot-icon-btn"
            tooltip = Tooltip(I18n.t("filter.slot.toggle.tooltip"))
            setOnAction {
                applyPatch(lastStatus.withToggledSlot(index), I18n.t("filter.status.updated"))
            }
        }
        val upBtn = Button("↑").apply {
            id = "filter-slot-up-$index"
            styleClass += "filter-slot-icon-btn"
            isDisable = (index == 0)
            tooltip = Tooltip(I18n.t("filter.slot.up.tooltip"))
            setOnAction {
                swapExpanded(index, index - 1)
                applyPatch(lastStatus.withMovedSlot(index, -1), I18n.t("filter.status.updated"))
            }
        }
        val downBtn = Button("↓").apply {
            id = "filter-slot-down-$index"
            styleClass += "filter-slot-icon-btn"
            isDisable = (index >= totalCount - 1)
            tooltip = Tooltip(I18n.t("filter.slot.down.tooltip"))
            setOnAction {
                swapExpanded(index, index + 1)
                applyPatch(lastStatus.withMovedSlot(index, +1), I18n.t("filter.status.updated"))
            }
        }
        val expandBtn = Button(if (isExpanded) "▾" else "▸").apply {
            id = "filter-slot-expand-$index"
            styleClass += "filter-slot-icon-btn"
            tooltip = Tooltip(I18n.t("filter.slot.expand.tooltip"))
            setOnAction {
                if (index in expandedSlots) expandedSlots.remove(index) else expandedSlots.add(index)
                renderRackContents(forceRebuild = true)
            }
        }
        val removeBtn = Button("×").apply {
            id = "filter-slot-remove-$index"
            styleClass += "filter-slot-icon-btn"
            tooltip = Tooltip(I18n.t("filter.slot.remove.tooltip"))
            setOnAction {
                expandedSlots.remove(index)
                applyPatch(lastStatus.withRemovedSlot(index), I18n.t("filter.status.updated"))
            }
        }

        val headerRow = HBox(
            4.0,
            indexBadge,
            titleLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            toggleBtn,
            upBtn,
            downBtn,
            expandBtn,
            removeBtn,
        ).apply { alignment = Pos.CENTER_LEFT }

        val card = VBox(5.0, headerRow).apply {
            id = "filter-slot-card-$index"
            styleClass += "filter-slot-card"
            if (!slot.enabled || !lastStatus.enabled) {
                styleClass += "filter-slot-card-disabled"
            }
        }
        if (isExpanded) {
            card.children += buildSlotEditorBody(index, slot)
        }
        return card
    }

    private fun swapExpanded(a: Int, b: Int) {
        val hasA = a in expandedSlots
        val hasB = b in expandedSlots
        if (hasA) expandedSlots.add(b) else expandedSlots.remove(b)
        if (hasB) expandedSlots.add(a) else expandedSlots.remove(a)
    }

    private fun buildSlotEditorBody(index: Int, slot: OpticalFilterSlotSpec): VBox = when (slot.type) {
        "uv" -> buildUvSlotEditor(index, slot)
        "nd" -> buildNdSlotEditor(index, slot)
        "bokeh-mask" -> buildBokehSlotEditor(index, slot)
        "star" -> buildStarSlotEditor(index, slot)
        "black-mist" -> buildMistSlotEditor(index, slot)
        "gnd" -> buildGndSlotEditor(index, slot)
        else -> buildSchemaSlotEditor(index, slot)
    }

    private fun buildSchemaSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val provider = lastStatus.providerFor(slot.type)
        val locale = I18n.localeCode()
        val body = VBox(5.0)
        if (provider != null) {
            val meta = provider.metadata
            val desc = meta.localizedDescription(locale)
            val metaParts = buildList {
                if (meta.version.isNotBlank()) add("v${meta.version}")
                if (meta.author.isNotBlank()) add(meta.author)
            }.joinToString(" · ")
            if (metaParts.isNotBlank() || desc.isNotBlank()) {
                val infoText = listOf(metaParts, desc).filter { it.isNotBlank() }.joinToString(" — ")
                body.children += Label(infoText).apply {
                    id = "filter-slot-$index-plugin-meta"
                    styleClass += "studio-hint-text"
                    isWrapText = true
                }
            }
        }

        val schema = provider?.parameterSchema.orEmpty()
        if (schema.isEmpty()) {
            val fallbackText = if (slot.params.isNotEmpty()) slot.params.toString() else I18n.t("filter.plugin.no_schema")
            body.children += Label(fallbackText).apply { styleClass += "studio-hint-text"; isWrapText = true }
            return body
        }

        fun commitParam(key: String, value: Any) {
            val nextParams = LinkedHashMap<String, Any>(slot.params)
            nextParams[key] = value
            applyPatch(
                lastStatus.withUpdatedSlot(index, slot.copy(params = nextParams)),
                I18n.t("filter.status.updated"),
            )
        }

        for (spec in schema) {
            val labelText = spec.localizedLabel(locale)
            val tipText = spec.localizedDescription(locale).trim()
            val controlId = "filter-slot-$index-param-${spec.key}"
            when (spec.type) {
                FilterParamType.NUMBER -> {
                    val minVal = spec.min ?: 0.0
                    val maxVal = spec.max ?: maxOf(minVal + 1.0, 100.0)
                    val stepVal = spec.step?.takeIf { it > 0.0 }
                    val curVal = ((slot.params[spec.key] as? Number)?.toDouble()
                        ?: (spec.defaultValue as? Number)?.toDouble()
                        ?: minVal).coerceIn(minVal, maxVal)
                    val unitSuffix = if (spec.unit.isNotBlank()) " ${spec.unit}" else ""
                    val row = sliderRow(
                        nodeId = controlId,
                        label = labelText,
                        min = minVal,
                        max = maxVal,
                        value = curVal,
                        format = { v ->
                            val snapped = if (stepVal != null) round((v - minVal) / stepVal) * stepVal + minVal else v
                            if (stepVal != null && stepVal >= 1.0) {
                                String.format(Locale.ROOT, "%.0f%s", snapped, unitSuffix)
                            } else {
                                String.format(Locale.ROOT, "%.2f%s", snapped, unitSuffix)
                            }
                        },
                    ) { v ->
                        val snapped = if (stepVal != null) {
                            (round((v - minVal) / stepVal) * stepVal + minVal).coerceIn(minVal, maxVal)
                        } else {
                            v.coerceIn(minVal, maxVal)
                        }
                        commitParam(spec.key, snapped)
                    }
                    if (tipText.isNotEmpty()) {
                        (row.children.firstOrNull() as? Label)?.tooltip = Tooltip(tipText)
                    }
                    body.children += row
                }
                FilterParamType.ENUM -> {
                    val curRaw = (slot.params[spec.key] ?: spec.defaultValue).toString()
                    val selectedChoice = spec.choices.firstOrNull { it.value.equals(curRaw, ignoreCase = true) }
                        ?: spec.choices.first()
                    val combo = ComboBox<FilterParamChoice>().apply {
                        id = controlId
                        minWidth = 0.0
                        maxWidth = Double.MAX_VALUE
                        HBox.setHgrow(this, Priority.ALWAYS)
                        items.setAll(spec.choices)
                        converter = object : StringConverter<FilterParamChoice>() {
                            override fun toString(item: FilterParamChoice?): String =
                                item?.localizedLabel(locale).orEmpty()
                            override fun fromString(string: String?): FilterParamChoice =
                                spec.choices.firstOrNull { it.localizedLabel(locale) == string || it.labelEn == string || it.labelZh == string || it.value == string }
                                    ?: spec.choices.first()
                        }
                        setCellFactory {
                            object : ListCell<FilterParamChoice>() {
                                override fun updateItem(item: FilterParamChoice?, empty: Boolean) {
                                    super.updateItem(item, empty)
                                    text = if (empty || item == null) null else item.localizedLabel(locale)
                                }
                            }
                        }
                        value = selectedChoice
                        if (tipText.isNotEmpty()) tooltip = Tooltip(tipText)
                        setOnAction {
                            if (!syncing) {
                                val chosen = value ?: return@setOnAction
                                commitParam(spec.key, chosen.value)
                            }
                        }
                    }
                    body.children += HBox(
                        6.0,
                        Label(labelText).apply {
                            styleClass += "studio-table-head"
                            minWidth = 76.0
                            if (tipText.isNotEmpty()) tooltip = Tooltip(tipText)
                        },
                        combo,
                    ).apply { alignment = Pos.CENTER_LEFT }
                }
                FilterParamType.BOOLEAN -> {
                    val curBool = (slot.params[spec.key] as? Boolean)
                        ?: (spec.defaultValue as? Boolean)
                        ?: false
                    val cb = CheckBox(labelText).apply {
                        id = controlId
                        isSelected = curBool
                        if (tipText.isNotEmpty()) tooltip = Tooltip(tipText)
                        setOnAction {
                            if (!syncing) commitParam(spec.key, isSelected)
                        }
                    }
                    body.children += cb
                }
                FilterParamType.TEXT -> {
                    val curText = (slot.params[spec.key] ?: spec.defaultValue).toString()
                    val tf = TextField(curText).apply {
                        id = controlId
                        maxWidth = Double.MAX_VALUE
                        HBox.setHgrow(this, Priority.ALWAYS)
                        if (tipText.isNotEmpty()) tooltip = Tooltip(tipText)
                        setOnAction {
                            if (!syncing) commitParam(spec.key, text)
                        }
                        focusedProperty().addListener { _, wasFocused, isFocused ->
                            if (wasFocused && !isFocused && !syncing && text != curText) {
                                commitParam(spec.key, text)
                            }
                        }
                    }
                    body.children += HBox(
                        6.0,
                        Label(labelText).apply {
                            styleClass += "studio-table-head"
                            minWidth = 76.0
                            if (tipText.isNotEmpty()) tooltip = Tooltip(tipText)
                        },
                        tf,
                    ).apply { alignment = Pos.CENTER_LEFT }
                }
            }
        }
        return body
    }

    private fun buildUvSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val preset = UvFilterPreset.parse((slot.params["preset"] ?: slot.params["uvPreset"]) as? String)
            ?: UvFilterPreset.CUSTOM
        val trans = ((slot.params["transmittance"] as? Number)?.toDouble() ?: preset.transmittance).coerceIn(0.50, 1.0)
        val glare = ((slot.params["veilingGlare"] as? Number)?.toDouble() ?: preset.veilingGlare).coerceIn(0.0, 0.50)
        val ghost = ((slot.params["ghostIntensity"] as? Number)?.toDouble() ?: preset.ghostIntensity).coerceIn(0.0, 0.50)
        val soft = ((slot.params["softness"] as? Number)?.toDouble() ?: preset.softness).coerceIn(0.0, 1.0)
        val tintR = ((slot.params["tintR"] as? Number)?.toDouble() ?: preset.tintR).coerceIn(0.75, 1.25)
        val tintG = ((slot.params["tintG"] as? Number)?.toDouble() ?: preset.tintG).coerceIn(0.75, 1.25)
        val tintB = ((slot.params["tintB"] as? Number)?.toDouble() ?: preset.tintB).coerceIn(0.75, 1.25)

        fun commitUv(
            newPreset: UvFilterPreset,
            t: Double = trans,
            gl: Double = glare,
            gh: Double = ghost,
            sf: Double = soft,
            r: Double = tintR,
            g: Double = tintG,
            b: Double = tintB,
        ) {
            val updated = slot.copy(
                params = linkedMapOf(
                    "preset" to newPreset.id,
                    "transmittance" to t,
                    "veilingGlare" to gl,
                    "ghostIntensity" to gh,
                    "softness" to sf,
                    "tintR" to r,
                    "tintG" to g,
                    "tintB" to b,
                ),
            )
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val presetRow = FlowPane(4.0, 4.0).apply {
            listOf(
                UvFilterPreset.PRO_MRC to "filter.uv.preset.pro",
                UvFilterPreset.STANDARD to "filter.uv.preset.standard",
                UvFilterPreset.CHEAP_POOR to "filter.uv.preset.cheap",
            ).forEach { (p, key) ->
                children += Button(I18n.t(key)).apply {
                    id = "filter-slot-$index-uv-${p.id.lowercase(Locale.ROOT)}"
                    styleClass += "filter-preset-chip"
                    if (preset == p) styleClass += "filter-preset-chip-active"
                    setOnAction {
                        commitUv(p, p.transmittance, p.veilingGlare, p.ghostIntensity, p.softness, p.tintR, p.tintG, p.tintB)
                    }
                }
            }
        }

        val hintKey = when (preset) {
            UvFilterPreset.PRO_MRC -> "filter.uv.hint.pro"
            UvFilterPreset.STANDARD -> "filter.uv.hint.standard"
            else -> "filter.uv.hint.cheap"
        }
        val hintLabel = Label(I18n.t(hintKey)).apply {
            styleClass += "studio-hint-text"
            isWrapText = true
        }

        val transRow = sliderRow(
            nodeId = "filter-slot-$index-uv-trans",
            label = I18n.t("filter.uv.metric.trans"),
            min = 50.0,
            max = 100.0,
            value = trans * 100.0,
            format = { String.format(Locale.ROOT, "%.1f%%", it) },
        ) { v ->
            commitUv(UvFilterPreset.CUSTOM, t = (v / 100.0).coerceIn(0.50, 1.0))
        }

        val glareRow = sliderRow(
            nodeId = "filter-slot-$index-uv-glare",
            label = I18n.t("filter.uv.metric.glare"),
            min = 0.0,
            max = 50.0,
            value = glare * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v ->
            commitUv(UvFilterPreset.CUSTOM, gl = (v / 100.0).coerceIn(0.0, 0.50))
        }

        val ghostRow = sliderRow(
            nodeId = "filter-slot-$index-uv-ghost",
            label = I18n.t("filter.uv.metric.ghost"),
            min = 0.0,
            max = 50.0,
            value = ghost * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v ->
            commitUv(UvFilterPreset.CUSTOM, gh = (v / 100.0).coerceIn(0.0, 0.50))
        }

        val softRow = sliderRow(
            nodeId = "filter-slot-$index-uv-soft",
            label = I18n.t("filter.uv.metric.softness"),
            min = 0.0,
            max = 100.0,
            value = soft * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v ->
            commitUv(UvFilterPreset.CUSTOM, sf = (v / 100.0).coerceIn(0.0, 1.0))
        }

        val greenCast = ((tintG - tintB) / 0.08).coerceIn(0.0, 1.5)
        val tintRow = sliderRow(
            nodeId = "filter-slot-$index-uv-tint",
            label = I18n.t("filter.uv.metric.tint"),
            min = 0.0,
            max = 100.0,
            value = (greenCast * 100.0).coerceIn(0.0, 100.0),
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v ->
            val k = (v / 100.0).coerceIn(0.0, 1.0)
            commitUv(
                UvFilterPreset.CUSTOM,
                r = 1.0 - 0.03 * k,
                g = 1.0 + 0.02 * k,
                b = 1.0 - 0.06 * k,
            )
        }

        return VBox(4.0, presetRow, hintLabel, transRow, glareRow, ghostRow, softRow, tintRow)
    }

    private fun buildNdSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val stops = NdFilterSpec.clampStops(
            ((slot.params["stops"] ?: slot.params["ndStops"]) as? Number)?.toDouble()
                ?: NdFilterSpec.parsePreset(slot.params["preset"] as? String)?.fixedStops
                ?: 3.0,
        )
        val curPreset = NdFilterSpec.presetForStops(stops)

        fun commitNd(newStops: Double, preset: NdFilterPreset = NdFilterSpec.presetForStops(newStops)) {
            val rounded = round(NdFilterSpec.clampStops(newStops) * 10.0) / 10.0
            val updated = slot.copy(params = linkedMapOf("preset" to preset.id, "stops" to rounded))
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val presetRow = FlowPane(4.0, 4.0).apply {
            listOf(
                NdFilterPreset.ND2,
                NdFilterPreset.ND4,
                NdFilterPreset.ND8,
                NdFilterPreset.ND16,
                NdFilterPreset.ND64,
                NdFilterPreset.ND1000,
            ).forEach { p ->
                children += Button(p.id).apply {
                    id = "filter-slot-$index-nd-${p.id.lowercase(Locale.ROOT)}"
                    styleClass += "filter-preset-chip"
                    if (curPreset == p) styleClass += "filter-preset-chip-active"
                    setOnAction { commitNd(p.fixedStops ?: 3.0, p) }
                }
            }
        }

        val slider = sliderRow(
            nodeId = "filter-slot-$index-nd-stops",
            label = I18n.t("filter.nd.stops"),
            min = 0.0,
            max = 10.0,
            value = stops,
            format = { String.format(Locale.ROOT, "%.1f EV", it) },
        ) { v -> commitNd(v) }

        val telemetry = Label(
            I18n.t(
                "filter.nd.telemetry",
                curPreset.id,
                stops,
                NdFilterSpec.formatFactor(stops),
                NdFilterSpec.opticalDensity(stops),
                NdFilterSpec.transmittancePercent(stops),
            ),
        ).apply { styleClass += "studio-hint-text" }

        return VBox(4.0, presetRow, slider, telemetry)
    }

    private fun buildBokehSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val shape = BokehMaskShape.parse((slot.params["shape"] ?: slot.params["bokehMask"]) as? String)
            ?: BokehMaskShape.STAR_5
        val angle = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 0.0).coerceIn(-180.0, 180.0)
        val scale = ((slot.params["scale"] as? Number)?.toDouble() ?: 0.96).coerceIn(0.40, 1.0)

        fun commitBokeh(newShape: BokehMaskShape = shape, newAngle: Double = angle, newScale: Double = scale) {
            val updated = slot.copy(
                params = linkedMapOf(
                    "shape" to newShape.id,
                    "angleDeg" to round(newAngle),
                    "scale" to round(newScale * 100.0) / 100.0,
                ),
            )
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val shapeRow = FlowPane(4.0, 4.0).apply {
            listOf(
                BokehMaskShape.STAR_5 to "filter.bokeh.shape.star5",
                BokehMaskShape.HEART to "filter.bokeh.shape.heart",
                BokehMaskShape.HEXAGON to "filter.bokeh.shape.hexagon",
                BokehMaskShape.RING to "filter.bokeh.shape.ring",
            ).forEach { (s, key) ->
                children += Button(I18n.t(key)).apply {
                    id = "filter-slot-$index-bokeh-${s.id.lowercase(Locale.ROOT)}"
                    styleClass += "filter-preset-chip"
                    if (shape == s) styleClass += "filter-preset-chip-active"
                    setOnAction { commitBokeh(newShape = s) }
                }
            }
        }

        val angleRow = sliderRow(
            nodeId = "filter-slot-$index-bokeh-angle",
            label = I18n.t("filter.bokeh.angle"),
            min = -180.0,
            max = 180.0,
            value = angle,
            format = { String.format(Locale.ROOT, "%.0f°", it) },
        ) { v -> commitBokeh(newAngle = v) }

        val scaleRow = sliderRow(
            nodeId = "filter-slot-$index-bokeh-scale",
            label = I18n.t("filter.bokeh.scale"),
            min = 40.0,
            max = 100.0,
            value = scale * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v -> commitBokeh(newScale = (v / 100.0).coerceIn(0.40, 1.0)) }

        val opticsHint = Label(I18n.t("filter.bokeh.optics_hint")).apply {
            id = "filter-slot-$index-bokeh-hint"
            styleClass += "studio-hint-text"
            isWrapText = true
        }

        return VBox(4.0, shapeRow, angleRow, scaleRow, opticsHint)
    }

    private fun buildStarSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val pts = ((slot.params["points"] as? Number)?.toInt() ?: 6).let { if (it <= 4) 4 else if (it <= 6) 6 else 8 }
        val strength = ((slot.params["strength"] as? Number)?.toDouble() ?: 0.75).coerceIn(0.0, 2.0)
        val angle = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 15.0).coerceIn(0.0, 180.0)

        fun commitStar(newPts: Int = pts, newStr: Double = strength, newAngle: Double = angle) {
            val updated = slot.copy(
                params = linkedMapOf(
                    "points" to newPts.toLong(),
                    "strength" to round(newStr * 20.0) / 20.0,
                    "angleDeg" to round(newAngle),
                    "threshold" to ((slot.params["threshold"] as? Number)?.toDouble() ?: 0.55),
                    "length" to ((slot.params["length"] as? Number)?.toDouble() ?: 0.12),
                ),
            )
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val ptsRow = FlowPane(4.0, 4.0).apply {
            children += Label(I18n.t("filter.star.points")).apply { styleClass += "studio-table-head" }
            listOf(4, 6, 8).forEach { p ->
                children += Button("${p}pt").apply {
                    id = "filter-slot-$index-star-${p}pt"
                    styleClass += "filter-preset-chip"
                    if (pts == p) styleClass += "filter-preset-chip-active"
                    setOnAction { commitStar(newPts = p) }
                }
            }
        }

        val strRow = sliderRow(
            nodeId = "filter-slot-$index-star-strength",
            label = I18n.t("filter.star.strength"),
            min = 0.0,
            max = 2.0,
            value = strength,
            format = { String.format(Locale.ROOT, "%.2f", it) },
        ) { v -> commitStar(newStr = v) }

        val angleRow = sliderRow(
            nodeId = "filter-slot-$index-star-angle",
            label = I18n.t("filter.star.angle"),
            min = 0.0,
            max = 180.0,
            value = angle,
            format = { String.format(Locale.ROOT, "%.0f°", it) },
        ) { v -> commitStar(newAngle = v) }

        return VBox(4.0, ptsRow, strRow, angleRow)
    }

    private fun buildMistSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val curPreset = (slot.params["preset"] as? String) ?: "1/4"
        val strength = ((slot.params["strength"] as? Number)?.toDouble() ?: 0.25).coerceIn(0.0, 1.0)

        fun commitMist(newPreset: String, newStrength: Double) {
            val updated = slot.copy(
                params = linkedMapOf(
                    "preset" to newPreset,
                    "strength" to round(newStrength * 100.0) / 100.0,
                    "threshold" to ((slot.params["threshold"] as? Number)?.toDouble() ?: 0.35),
                ),
            )
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val presetRow = FlowPane(4.0, 4.0).apply {
            children += Label(I18n.t("filter.mist.preset")).apply { styleClass += "studio-table-head" }
            listOf("1/8" to 0.125, "1/4" to 0.25, "1/2" to 0.50, "1" to 1.0).forEach { (p, s) ->
                children += Button(p).apply {
                    id = "filter-slot-$index-mist-${p.replace('/', '_')}"
                    styleClass += "filter-preset-chip"
                    if (curPreset == p) styleClass += "filter-preset-chip-active"
                    setOnAction { commitMist(p, s) }
                }
            }
        }

        val strRow = sliderRow(
            nodeId = "filter-slot-$index-mist-strength",
            label = I18n.t("filter.mist.strength"),
            min = 0.0,
            max = 100.0,
            value = strength * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v -> commitMist("CUSTOM", (v / 100.0).coerceIn(0.0, 1.0)) }

        return VBox(4.0, presetRow, strRow)
    }

    private fun buildGndSlotEditor(index: Int, slot: OpticalFilterSlotSpec): VBox {
        val stops = ((slot.params["stops"] as? Number)?.toDouble() ?: 2.0).coerceIn(0.0, 5.0)
        val horizon = ((slot.params["horizon"] as? Number)?.toDouble() ?: 0.50).coerceIn(0.0, 1.0)
        val feather = ((slot.params["feather"] as? Number)?.toDouble() ?: 0.25).coerceIn(0.02, 1.0)
        val angle = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 0.0).coerceIn(-180.0, 180.0)

        fun commitGnd(newStops: Double = stops, newHorizon: Double = horizon, newFeather: Double = feather, newAngle: Double = angle) {
            val updated = slot.copy(
                params = linkedMapOf(
                    "stops" to round(newStops * 10.0) / 10.0,
                    "horizon" to round(newHorizon * 100.0) / 100.0,
                    "feather" to round(newFeather * 100.0) / 100.0,
                    "angleDeg" to round(newAngle),
                ),
            )
            applyPatch(lastStatus.withUpdatedSlot(index, updated), I18n.t("filter.status.updated"))
        }

        val stopsRow = sliderRow(
            nodeId = "filter-slot-$index-gnd-stops",
            label = I18n.t("filter.gnd.stops"),
            min = 0.0,
            max = 5.0,
            value = stops,
            format = { String.format(Locale.ROOT, "%.1f EV", it) },
        ) { v -> commitGnd(newStops = v) }

        val horizonRow = sliderRow(
            nodeId = "filter-slot-$index-gnd-horizon",
            label = I18n.t("filter.gnd.horizon"),
            min = 10.0,
            max = 90.0,
            value = horizon * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v -> commitGnd(newHorizon = (v / 100.0).coerceIn(0.0, 1.0)) }

        val featherRow = sliderRow(
            nodeId = "filter-slot-$index-gnd-feather",
            label = I18n.t("filter.gnd.feather"),
            min = 5.0,
            max = 80.0,
            value = feather * 100.0,
            format = { String.format(Locale.ROOT, "%.0f%%", it) },
        ) { v -> commitGnd(newFeather = (v / 100.0).coerceIn(0.02, 1.0)) }

        val angleRow = sliderRow(
            nodeId = "filter-slot-$index-gnd-angle",
            label = I18n.t("filter.gnd.angle"),
            min = -90.0,
            max = 90.0,
            value = angle,
            format = { String.format(Locale.ROOT, "%.0f°", it) },
        ) { v -> commitGnd(newAngle = v) }

        return VBox(4.0, stopsRow, horizonRow, featherRow, angleRow)
    }

    private fun sliderRow(
        nodeId: String,
        label: String,
        min: Double,
        max: Double,
        value: Double,
        format: (Double) -> String,
        onCommit: (Double) -> Unit,
    ): HBox {
        val readout = Label(format(value)).apply {
            styleClass += "studio-telemetry-val"
            minWidth = 48.0
            alignment = Pos.CENTER_RIGHT
        }
        val slider = Slider(min, max, value.coerceIn(min, max)).apply {
            id = nodeId
            HBox.setHgrow(this, Priority.ALWAYS)
            valueProperty().addListener { _, _, next ->
                readout.text = format(next.toDouble())
            }
            valueChangingProperty().addListener { _, wasChanging, isChanging ->
                if (wasChanging && !isChanging && !syncing) {
                    onCommit(this.value)
                }
            }
            setOnMouseReleased {
                if (!syncing && !isValueChanging) {
                    onCommit(this.value)
                }
            }
        }
        return HBox(
            6.0,
            Label(label).apply {
                styleClass += "studio-table-head"
                minWidth = 56.0
                prefWidth = 72.0
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            },
            slider,
            readout,
        ).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun applyPatch(patch: Map<String, Any>, message: String) {
        val sender = patchOpticalFilter ?: return
        sender(patch).whenComplete { _, err ->
            if (err != null) onError(err) else onStatus(message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun observeSnapshot(snapshot: Map<String, Any?>) {
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        val comp = components["optical-filter"]
        val status = if (comp != null) {
            OpticalFilterStatus.fromMaps(
                config = comp["config"] as? Map<String, Any?>,
                state = comp["state"] as? Map<String, Any?>,
            )
        } else {
            OpticalFilterStatus.DEFAULT
        }
        lastStatus = status
        root.isDisable = !status.loaded
        syncing = true
        try {
            masterToggle.isSelected = status.enabled
            refreshMasterLabel(status.enabled)
            evBadge.text = status.telemetryBadgeText()
            val pluginsChanged = syncPluginAddItems(status.pluginProviders)
            renderRackContents(forceRebuild = pluginsChanged)
        } finally {
            syncing = false
        }
    }
}
