package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentDescriptor
import com.github.nyabanban.fuji.emulator.api.ComponentFactory
import com.github.nyabanban.fuji.emulator.api.CompositeOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.HardwareComponent
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterContext
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.ScalarComponent
import com.github.nyabanban.fuji.emulator.api.Setting
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import com.github.nyabanban.fuji.emulator.api.ValueKind
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln

/**
 * Standalone decoupled Optical Filter Stack component (`:components:optical-filter`, id = `"optical-filter"`).
 *
 * Models a physical front-of-lens / matte-box filter system independent of the camera body and lens:
 * - Uniform **ND** (`OFF`, `ND2`..`ND1000`, `VARIABLE` `0.0..10.0 EV`) — pure physical `2^(-stops)` scalar attenuation
 * - **Aperture Bokeh Mask** (`OFF`, `STAR_5`, `HEART`, `HEXAGON`, `RING`) — real entrance-pupil bokeh convolution shape
 * - **Star / Cross-Screen** (`4`, `6`, `8` points) — linear highlight diffraction spikes
 * - **Black Mist** (`OFF`, `1/8`, `1/4`, `1/2`, `1`) — cinema highlight halation/diffusion
 * - **Graduated ND** (`0.0..5.0 EV`) — soft-edge rotated horizon graduation
 * - Extensible via [OpticalFilterElementProvider] `ServiceLoader` SPI.
 *
 * Exposes [OpticalFilterStackPort] at [OpticalFilterStackPort.PORT_NAME] (`"optical-filter.stack"`).
 */
class OpticalFilterFactory(
    private val extraProviders: List<OpticalFilterElementProvider> = emptyList(),
) : ComponentFactory {
    constructor() : this(emptyList())

    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        val normalized = normalizeInitialConfig(config)
        val explicitFiltersDir = (normalized["filtersDir"] as? String).orEmpty()
        val registry = OpticalFilterModuleLoader.discover(
            componentDir = context.directory,
            explicitFiltersDir = explicitFiltersDir,
            extraProviders = extraProviders,
        )
        return OpticalFilterComponent(
            context = context,
            initialConfig = normalized,
            explicitFiltersDir = explicitFiltersDir,
            extraProviders = extraProviders,
            initialRegistry = registry,
        )
    }

    companion object {
        private val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")

        private val DESCRIPTOR = ComponentDescriptor(
            id = "optical-filter",
            version = "0.0.1",
            settings = listOf(
                Setting(
                    "enabled",
                    ValueKind.BOOLEAN,
                    true,
                    live = true,
                    description = "Master optical filter holder switch (false bypasses all stacked filters).",
                ),
                Setting(
                    "ndPreset",
                    ValueKind.TEXT,
                    NdFilterPreset.OFF.id,
                    choices = NdFilterSpec.ALL_PRESET_IDS,
                    live = true,
                    description = "Neutral Density preset: OFF, ND2..ND1000 (1..10 EV), or VARIABLE.",
                ),
                Setting(
                    "ndStops",
                    ValueKind.DECIMAL,
                    0.0,
                    NdFilterSpec.MIN_STOPS,
                    NdFilterSpec.MAX_STOPS,
                    live = true,
                    description = "Continuous Neutral Density attenuation in EV stops (0.0..10.0 EV, transmittance = 2^(-ndStops)).",
                ),
                Setting(
                    "bokehMask",
                    ValueKind.TEXT,
                    BokehMaskShape.OFF.id,
                    choices = BokehMaskShape.ALL_IDS,
                    live = true,
                    description = "Physical entrance-pupil bokeh cutout mask: OFF, STAR_5, HEART, HEXAGON, RING.",
                ),
                Setting(
                    "bokehMaskAngleDeg",
                    ValueKind.DECIMAL,
                    0.0,
                    -180.0,
                    180.0,
                    live = true,
                    description = "Rotation angle of the aperture bokeh cutout mask in degrees.",
                ),
                Setting(
                    "bokehMaskScale",
                    ValueKind.DECIMAL,
                    0.96,
                    0.40,
                    1.0,
                    live = true,
                    description = "Normalized pupil diameter scale of the bokeh cutout mask (0.40..1.0).",
                ),
                Setting(
                    "starEnabled",
                    ValueKind.BOOLEAN,
                    false,
                    live = true,
                    description = "Enable Cross-Screen / Star diffraction spikes around bright highlights.",
                ),
                Setting(
                    "starPoints",
                    ValueKind.INTEGER,
                    6L,
                    4.0,
                    8.0,
                    live = true,
                    description = "Number of Star filter diffraction spikes (4, 6, or 8).",
                ),
                Setting(
                    "starStrength",
                    ValueKind.DECIMAL,
                    0.75,
                    0.0,
                    2.0,
                    live = true,
                    description = "Intensity multiplier for Star diffraction spikes (0.0..2.0).",
                ),
                Setting(
                    "starThreshold",
                    ValueKind.DECIMAL,
                    0.55,
                    0.10,
                    2.0,
                    live = true,
                    description = "Linear radiance threshold above which highlights emit Star spikes.",
                ),
                Setting(
                    "starAngleDeg",
                    ValueKind.DECIMAL,
                    15.0,
                    0.0,
                    180.0,
                    live = true,
                    description = "Rotation angle of Star diffraction spikes in degrees.",
                ),
                Setting(
                    "starLength",
                    ValueKind.DECIMAL,
                    0.12,
                    0.02,
                    0.35,
                    live = true,
                    description = "Star diffraction spike length as fraction of frame height (0.02..0.35).",
                ),
                Setting(
                    "mistEnabled",
                    ValueKind.BOOLEAN,
                    false,
                    live = true,
                    description = "Enable Black Mist cinema highlight halation filter.",
                ),
                Setting(
                    "mistPreset",
                    ValueKind.TEXT,
                    "OFF",
                    choices = BlackMistFilterElement.PRESET_CHOICES,
                    live = true,
                    description = "Black Mist strength preset: OFF, 1/8, 1/4, 1/2, 1, or CUSTOM.",
                ),
                Setting(
                    "mistStrength",
                    ValueKind.DECIMAL,
                    0.0,
                    0.0,
                    1.0,
                    live = true,
                    description = "Continuous Black Mist diffusion intensity (0.0..1.0).",
                ),
                Setting(
                    "mistThreshold",
                    ValueKind.DECIMAL,
                    0.35,
                    0.0,
                    1.0,
                    live = true,
                    description = "Linear radiance knee above which Black Mist blooms highlights.",
                ),
                Setting(
                    "gndEnabled",
                    ValueKind.BOOLEAN,
                    false,
                    live = true,
                    description = "Enable soft-edge Graduated ND (GND) sky attenuation filter.",
                ),
                Setting(
                    "gndStops",
                    ValueKind.DECIMAL,
                    0.0,
                    0.0,
                    5.0,
                    live = true,
                    description = "Graduated ND upper-frame attenuation in EV stops (0.0..5.0).",
                ),
                Setting(
                    "gndHorizon",
                    ValueKind.DECIMAL,
                    0.50,
                    0.0,
                    1.0,
                    live = true,
                    description = "Normalized vertical horizon position (0.0 = top, 0.5 = center, 1.0 = bottom).",
                ),
                Setting(
                    "gndFeather",
                    ValueKind.DECIMAL,
                    0.25,
                    0.02,
                    1.0,
                    live = true,
                    description = "Softness width of the Graduated ND transition band (0.02..1.0).",
                ),
                Setting(
                    "gndAngleDeg",
                    ValueKind.DECIMAL,
                    0.0,
                    -180.0,
                    180.0,
                    live = true,
                    description = "Rotation angle of the Graduated ND horizon line in degrees.",
                ),
                Setting(
                    "uvPreset",
                    ValueKind.TEXT,
                    UvFilterPreset.OFF.id,
                    choices = UvFilterPreset.ALL_IDS,
                    live = true,
                    description = "Front UV protective filter preset: OFF, PRO_MRC (99.6%), STANDARD (96.5%), CHEAP_POOR (89.5% glare/ghosting/softness), or CUSTOM.",
                ),
                Setting(
                    "uvTransmittance",
                    ValueKind.DECIMAL,
                    1.0,
                    0.50,
                    1.0,
                    live = true,
                    description = "UV filter linear Fresnel transmittance (0.50..1.0).",
                ),
                Setting(
                    "uvVeilingGlare",
                    ValueKind.DECIMAL,
                    0.0,
                    0.0,
                    0.50,
                    live = true,
                    description = "UV filter veiling glare / stray-light contrast washout (0.0..0.50).",
                ),
                Setting(
                    "uvGhostIntensity",
                    ValueKind.DECIMAL,
                    0.0,
                    0.0,
                    0.50,
                    live = true,
                    description = "UV filter point-symmetric internal double-reflection ghosting strength (0.0..0.50).",
                ),
                Setting(
                    "uvSoftness",
                    ValueKind.DECIMAL,
                    0.0,
                    0.0,
                    1.0,
                    live = true,
                    description = "UV filter micro-softness / MTF degradation blend (0.0..1.0).",
                ),
                Setting(
                    "uvTintR",
                    ValueKind.DECIMAL,
                    1.0,
                    0.75,
                    1.25,
                    live = true,
                    description = "UV glass relative red channel transmittance tint (0.75..1.25).",
                ),
                Setting(
                    "uvTintG",
                    ValueKind.DECIMAL,
                    1.0,
                    0.75,
                    1.25,
                    live = true,
                    description = "UV glass relative green channel transmittance tint (0.75..1.25).",
                ),
                Setting(
                    "uvTintB",
                    ValueKind.DECIMAL,
                    1.0,
                    0.75,
                    1.25,
                    live = true,
                    description = "UV glass relative blue channel transmittance tint (0.75..1.25).",
                ),
                Setting(
                    "slots",
                    ValueKind.TEXT,
                    "",
                    live = true,
                    description = "Optional ordered multi-slot filter rack JSON array (e.g. stacking ND8 + ND64 or UV + Star + Mist). Blank uses flat settings.",
                ),
                Setting(
                    "filtersDir",
                    ValueKind.TEXT,
                    "",
                    live = false,
                    description = "Optional extra directory to scan for external third-party OpticalFilterElementProvider plugin JARs (in addition to <run-dir>/filters/*.jar).",
                ),
                Setting(
                    "rescanPlugins",
                    ValueKind.INTEGER,
                    0L,
                    0.0,
                    9.0e18,
                    live = true,
                    description = "Monotonic counter incremented to rescan external filters/*.jar plugins at runtime.",
                ),
            ),
        )

        private fun normalizeInitialConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { it.key to it.default }
            val merged = LinkedHashMap<String, Any>(defaults)
            for ((k, v) in config) {
                merged[k] = DESCRIPTOR.settings.firstOrNull { it.key == k }?.validate(v) ?: v
            }
            return reconcileLinkedSettings(merged, explicitKeys = config.keys)
        }

        /**
         * Keeps paired preset + continuous numeric settings (ND, Black Mist, Star points, GND)
         * bidirectionally synchronized regardless of which key a caller patches.
         */
        internal fun reconcileLinkedSettings(
            merged: Map<String, Any>,
            explicitKeys: Set<String>,
        ): Map<String, Any> {
            val out = LinkedHashMap<String, Any>(merged)

            // 1. ND preset <-> ndStops
            val hasPreset = "ndPreset" in explicitKeys
            val hasStops = "ndStops" in explicitKeys
            if (hasPreset && !hasStops) {
                val parsed = NdFilterSpec.parsePreset(out["ndPreset"] as? String) ?: NdFilterPreset.OFF
                val currentStops = NdFilterSpec.clampStops((out["ndStops"] as? Number)?.toDouble() ?: 0.0)
                val resolvedStops = parsed.fixedStops ?: currentStops
                out["ndStops"] = resolvedStops
                out["ndPreset"] = if (parsed == NdFilterPreset.VARIABLE) NdFilterPreset.VARIABLE.id else parsed.id
            } else if (hasStops) {
                val clamped = NdFilterSpec.clampStops((out["ndStops"] as? Number)?.toDouble() ?: 0.0)
                out["ndStops"] = clamped
                val requestedPreset = if (hasPreset) NdFilterSpec.parsePreset(out["ndPreset"] as? String) else null
                out["ndPreset"] = if (requestedPreset == NdFilterPreset.VARIABLE && clamped > 0.0) {
                    NdFilterPreset.VARIABLE.id
                } else {
                    NdFilterSpec.presetForStops(clamped).id
                }
            } else {
                val clamped = NdFilterSpec.clampStops((out["ndStops"] as? Number)?.toDouble() ?: 0.0)
                out["ndStops"] = clamped
                val parsed = NdFilterSpec.parsePreset(out["ndPreset"] as? String)
                val fixed = parsed?.fixedStops
                if (parsed == null || (fixed != null && abs(fixed - clamped) > 0.015)) {
                    out["ndPreset"] = NdFilterSpec.presetForStops(clamped).id
                }
            }

            // 2. Bokeh mask canonicalization
            val maskShape = BokehMaskShape.parse(out["bokehMask"] as? String) ?: BokehMaskShape.OFF
            out["bokehMask"] = maskShape.id

            // 3. Star points snap to 4 / 6 / 8
            val rawStarPts = (out["starPoints"] as? Number)?.toInt() ?: 6
            out["starPoints"] = when {
                rawStarPts <= 4 -> 4L
                rawStarPts <= 6 -> 6L
                else -> 8L
            }

            // 4. Black Mist mistPreset <-> mistStrength <-> mistEnabled
            val hasMistPreset = "mistPreset" in explicitKeys
            val hasMistStrength = "mistStrength" in explicitKeys
            val hasMistEnabled = "mistEnabled" in explicitKeys
            if (hasMistPreset && !hasMistStrength) {
                val p = (out["mistPreset"] as? String)?.trim()?.uppercase() ?: "OFF"
                val fixed = BlackMistFilterElement.strengthForPreset(p)
                if (fixed != null) {
                    out["mistStrength"] = fixed
                    out["mistPreset"] = BlackMistFilterElement.presetForStrength(fixed)
                    if (!hasMistEnabled) out["mistEnabled"] = fixed > 1e-4
                }
            } else if (hasMistStrength) {
                val s = ((out["mistStrength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                out["mistStrength"] = s
                out["mistPreset"] = BlackMistFilterElement.presetForStrength(s)
                if (!hasMistEnabled) out["mistEnabled"] = s > 1e-4
            } else if (hasMistEnabled) {
                val on = out["mistEnabled"] as? Boolean ?: false
                val curS = ((out["mistStrength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                if (on && curS <= 1e-4) {
                    out["mistStrength"] = 0.25
                    out["mistPreset"] = "1/4"
                } else if (!on) {
                    out["mistPreset"] = "OFF"
                }
            }

            // 5. GND gndStops <-> gndEnabled
            val hasGndStops = "gndStops" in explicitKeys
            val hasGndEnabled = "gndEnabled" in explicitKeys
            if (hasGndStops && !hasGndEnabled) {
                val gs = ((out["gndStops"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 5.0)
                out["gndStops"] = gs
                out["gndEnabled"] = gs > 1e-4
            } else if (hasGndEnabled) {
                val on = out["gndEnabled"] as? Boolean ?: false
                val curGs = ((out["gndStops"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 5.0)
                if (on && curGs <= 1e-4) {
                    out["gndStops"] = 2.0
                }
            }

            // 6. UV preset <-> UV physics parameters
            val hasUvPreset = "uvPreset" in explicitKeys
            val hasUvMetric = UV_METRIC_KEYS.any { it in explicitKeys }
            if (hasUvPreset && !hasUvMetric) {
                val preset = UvFilterPreset.parse(out["uvPreset"] as? String) ?: UvFilterPreset.OFF
                out["uvPreset"] = preset.id
                if (preset != UvFilterPreset.CUSTOM) {
                    applyUvPresetDefaults(out, preset)
                }
            } else if (hasUvMetric) {
                val trans = ((out["uvTransmittance"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.50, 1.0)
                val glare = ((out["uvVeilingGlare"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 0.50)
                val ghost = ((out["uvGhostIntensity"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 0.50)
                val soft = ((out["uvSoftness"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                val tR = ((out["uvTintR"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                val tG = ((out["uvTintG"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                val tB = ((out["uvTintB"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                out["uvTransmittance"] = trans
                out["uvVeilingGlare"] = glare
                out["uvGhostIntensity"] = ghost
                out["uvSoftness"] = soft
                out["uvTintR"] = tR
                out["uvTintG"] = tG
                out["uvTintB"] = tB
                val requested = if (hasUvPreset) UvFilterPreset.parse(out["uvPreset"] as? String) else null
                out["uvPreset"] = when {
                    requested == UvFilterPreset.OFF -> UvFilterPreset.OFF.id
                    requested == UvFilterPreset.CUSTOM -> UvFilterPreset.CUSTOM.id
                    else -> matchUvPreset(trans, glare, ghost, soft, tR, tG, tB).id
                }
            } else {
                val preset = UvFilterPreset.parse(out["uvPreset"] as? String) ?: UvFilterPreset.OFF
                out["uvPreset"] = preset.id
            }

            // 7. Ordered multi-slot rack JSON (`slots`) reconciliation
            val hasSlotsExplicit = "slots" in explicitKeys
            val hasFlatFilterKeys = FLAT_FILTER_KEYS.any { it in explicitKeys }
            val rawSlots = (out["slots"] as? String).orEmpty()
            val parsedSlots = OpticalFilterSlotCodec.parse(rawSlots)

            if (hasSlotsExplicit) {
                if (parsedSlots.isEmpty()) {
                    out["slots"] = ""
                    if (!hasFlatFilterKeys) {
                        resetFlatFilterSettings(out)
                    }
                } else {
                    out["slots"] = OpticalFilterSlotCodec.encode(parsedSlots)
                    if (!hasFlatFilterKeys) {
                        syncFlatSummaryFromSlots(out, parsedSlots)
                    }
                }
            } else if (parsedSlots.isNotEmpty() && hasFlatFilterKeys) {
                // Keep active slot stack synchronized when legacy flat commands (/nd, /filter uv, etc.) are issued.
                val updatedSlots = applyFlatKeysToSlots(parsedSlots, out, explicitKeys)
                out["slots"] = OpticalFilterSlotCodec.encode(updatedSlots)
            } else if (parsedSlots.isNotEmpty()) {
                out["slots"] = OpticalFilterSlotCodec.encode(parsedSlots)
            } else {
                out["slots"] = ""
            }

            return out
        }

        private val UV_METRIC_KEYS = setOf(
            "uvTransmittance",
            "uvVeilingGlare",
            "uvGhostIntensity",
            "uvSoftness",
            "uvTintR",
            "uvTintG",
            "uvTintB",
        )

        private val FLAT_FILTER_KEYS = setOf(
            "ndPreset", "ndStops",
            "bokehMask", "bokehMaskAngleDeg", "bokehMaskScale",
            "starEnabled", "starPoints", "starStrength", "starThreshold", "starAngleDeg", "starLength",
            "mistEnabled", "mistPreset", "mistStrength", "mistThreshold",
            "gndEnabled", "gndStops", "gndHorizon", "gndFeather", "gndAngleDeg",
            "uvPreset", "uvTransmittance", "uvVeilingGlare", "uvGhostIntensity", "uvSoftness", "uvTintR", "uvTintG", "uvTintB",
        )

        private fun applyUvPresetDefaults(out: MutableMap<String, Any>, preset: UvFilterPreset) {
            out["uvTransmittance"] = preset.transmittance
            out["uvVeilingGlare"] = preset.veilingGlare
            out["uvGhostIntensity"] = preset.ghostIntensity
            out["uvSoftness"] = preset.softness
            out["uvTintR"] = preset.tintR
            out["uvTintG"] = preset.tintG
            out["uvTintB"] = preset.tintB
        }

        private fun matchUvPreset(
            trans: Double,
            glare: Double,
            ghost: Double,
            soft: Double,
            tR: Double,
            tG: Double,
            tB: Double,
        ): UvFilterPreset {
            for (preset in listOf(UvFilterPreset.OFF, UvFilterPreset.PRO_MRC, UvFilterPreset.STANDARD, UvFilterPreset.CHEAP_POOR)) {
                if (abs(trans - preset.transmittance) <= 1e-3 &&
                    abs(glare - preset.veilingGlare) <= 1e-3 &&
                    abs(ghost - preset.ghostIntensity) <= 1e-3 &&
                    abs(soft - preset.softness) <= 1e-3 &&
                    abs(tR - preset.tintR) <= 1e-3 &&
                    abs(tG - preset.tintG) <= 1e-3 &&
                    abs(tB - preset.tintB) <= 1e-3
                ) {
                    return preset
                }
            }
            return UvFilterPreset.CUSTOM
        }

        private fun resetFlatFilterSettings(out: MutableMap<String, Any>) {
            out["ndPreset"] = NdFilterPreset.OFF.id
            out["ndStops"] = 0.0
            out["bokehMask"] = BokehMaskShape.OFF.id
            out["starEnabled"] = false
            out["mistEnabled"] = false
            out["mistPreset"] = "OFF"
            out["mistStrength"] = 0.0
            out["gndEnabled"] = false
            out["gndStops"] = 0.0
            out["uvPreset"] = UvFilterPreset.OFF.id
            applyUvPresetDefaults(out, UvFilterPreset.OFF)
        }

        private fun syncFlatSummaryFromSlots(out: MutableMap<String, Any>, slots: List<OpticalFilterSlotSpec>) {
            resetFlatFilterSettings(out)
            var ndStopsSum = 0.0
            var lastUv: OpticalFilterSlotSpec? = null
            var lastBokeh: OpticalFilterSlotSpec? = null
            var lastStar: OpticalFilterSlotSpec? = null
            var lastMist: OpticalFilterSlotSpec? = null
            var lastGnd: OpticalFilterSlotSpec? = null
            for (slot in slots) {
                if (!slot.enabled) continue
                when (slot.type) {
                    NdFilterElement.TYPE_ID -> {
                        val rawStops = (slot.params["stops"] ?: slot.params["ndStops"]) as? Number
                        val preset = NdFilterSpec.parsePreset((slot.params["preset"] ?: slot.params["ndPreset"]) as? String)
                        ndStopsSum += NdFilterSpec.clampStops(rawStops?.toDouble() ?: preset?.fixedStops ?: 0.0)
                    }
                    UvFilterElement.TYPE_ID -> lastUv = slot
                    BokehMaskFilterElement.TYPE_ID -> lastBokeh = slot
                    StarFilterElement.TYPE_ID -> lastStar = slot
                    BlackMistFilterElement.TYPE_ID -> lastMist = slot
                    GndFilterElement.TYPE_ID -> lastGnd = slot
                }
            }
            val clampedFlatNd = NdFilterSpec.clampStops(ndStopsSum)
            out["ndStops"] = clampedFlatNd
            out["ndPreset"] = NdFilterSpec.presetForStops(clampedFlatNd).id

            lastUv?.let { slot ->
                val p = UvFilterPreset.parse((slot.params["preset"] ?: slot.params["uvPreset"]) as? String)
                    ?: UvFilterPreset.CUSTOM
                out["uvPreset"] = p.id
                out["uvTransmittance"] = ((slot.params["transmittance"] as? Number)?.toDouble() ?: p.transmittance).coerceIn(0.50, 1.0)
                out["uvVeilingGlare"] = ((slot.params["veilingGlare"] as? Number)?.toDouble() ?: p.veilingGlare).coerceIn(0.0, 0.50)
                out["uvGhostIntensity"] = ((slot.params["ghostIntensity"] as? Number)?.toDouble() ?: p.ghostIntensity).coerceIn(0.0, 0.50)
                out["uvSoftness"] = ((slot.params["softness"] as? Number)?.toDouble() ?: p.softness).coerceIn(0.0, 1.0)
                out["uvTintR"] = ((slot.params["tintR"] as? Number)?.toDouble() ?: p.tintR).coerceIn(0.75, 1.25)
                out["uvTintG"] = ((slot.params["tintG"] as? Number)?.toDouble() ?: p.tintG).coerceIn(0.75, 1.25)
                out["uvTintB"] = ((slot.params["tintB"] as? Number)?.toDouble() ?: p.tintB).coerceIn(0.75, 1.25)
            }
            lastBokeh?.let { slot ->
                val shape = BokehMaskShape.parse((slot.params["shape"] ?: slot.params["bokehMask"]) as? String)
                    ?: BokehMaskShape.OFF
                out["bokehMask"] = shape.id
                out["bokehMaskAngleDeg"] = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 0.0).coerceIn(-180.0, 180.0)
                out["bokehMaskScale"] = ((slot.params["scale"] as? Number)?.toDouble() ?: 0.96).coerceIn(0.40, 1.0)
            }
            lastStar?.let { slot ->
                out["starEnabled"] = true
                val pts = ((slot.params["points"] as? Number)?.toInt() ?: 6).let { if (it <= 4) 4L else if (it <= 6) 6L else 8L }
                out["starPoints"] = pts
                out["starStrength"] = ((slot.params["strength"] as? Number)?.toDouble() ?: 0.75).coerceIn(0.0, 2.0)
                out["starThreshold"] = ((slot.params["threshold"] as? Number)?.toDouble() ?: 0.55).coerceIn(0.10, 2.0)
                out["starAngleDeg"] = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 15.0).coerceIn(0.0, 180.0)
                out["starLength"] = ((slot.params["length"] as? Number)?.toDouble() ?: 0.12).coerceIn(0.02, 0.35)
            }
            lastMist?.let { slot ->
                val rawPreset = (slot.params["preset"] as? String)
                val strength = ((slot.params["strength"] as? Number)?.toDouble()
                    ?: rawPreset?.let { BlackMistFilterElement.strengthForPreset(it) }
                    ?: 0.25).coerceIn(0.0, 1.0)
                out["mistEnabled"] = strength > 1e-4
                out["mistStrength"] = strength
                out["mistPreset"] = rawPreset ?: BlackMistFilterElement.presetForStrength(strength)
                out["mistThreshold"] = ((slot.params["threshold"] as? Number)?.toDouble() ?: 0.35).coerceIn(0.0, 1.0)
            }
            lastGnd?.let { slot ->
                val stops = ((slot.params["stops"] as? Number)?.toDouble() ?: 2.0).coerceIn(0.0, 5.0)
                out["gndEnabled"] = stops > 1e-4
                out["gndStops"] = stops
                out["gndHorizon"] = ((slot.params["horizon"] as? Number)?.toDouble() ?: 0.50).coerceIn(0.0, 1.0)
                out["gndFeather"] = ((slot.params["feather"] as? Number)?.toDouble() ?: 0.25).coerceIn(0.02, 1.0)
                out["gndAngleDeg"] = ((slot.params["angleDeg"] as? Number)?.toDouble() ?: 0.0).coerceIn(-180.0, 180.0)
            }
        }

        private fun applyFlatKeysToSlots(
            existing: List<OpticalFilterSlotSpec>,
            flat: Map<String, Any>,
            explicitKeys: Set<String>,
        ): List<OpticalFilterSlotSpec> {
            val mutable = existing.toMutableList()

            fun upsertOrRemove(type: String, active: Boolean, buildSpec: () -> OpticalFilterSlotSpec) {
                val idx = mutable.indexOfFirst { it.type == type }
                if (!active) {
                    mutable.removeAll { it.type == type }
                } else {
                    val spec = buildSpec()
                    if (idx >= 0) mutable[idx] = spec else if (mutable.size < OpticalFilterSlotCodec.MAX_SLOTS) mutable.add(spec)
                }
            }

            if (explicitKeys.any { it == "ndPreset" || it == "ndStops" }) {
                val stops = NdFilterSpec.clampStops((flat["ndStops"] as? Number)?.toDouble() ?: 0.0)
                val preset = (flat["ndPreset"] as? String) ?: NdFilterSpec.presetForStops(stops).id
                mutable.removeAll { it.type == NdFilterElement.TYPE_ID }
                if (stops > 1e-4 && mutable.size < OpticalFilterSlotCodec.MAX_SLOTS) {
                    mutable.add(OpticalFilterSlotSpec(NdFilterElement.TYPE_ID, true, linkedMapOf("preset" to preset, "stops" to stops)))
                }
            }
            if (explicitKeys.any { it.startsWith("uv") }) {
                val preset = UvFilterPreset.parse(flat["uvPreset"] as? String) ?: UvFilterPreset.OFF
                upsertOrRemove(UvFilterElement.TYPE_ID, preset != UvFilterPreset.OFF) {
                    OpticalFilterSlotSpec(
                        UvFilterElement.TYPE_ID,
                        true,
                        linkedMapOf(
                            "preset" to preset.id,
                            "transmittance" to ((flat["uvTransmittance"] as? Number)?.toDouble() ?: preset.transmittance),
                            "veilingGlare" to ((flat["uvVeilingGlare"] as? Number)?.toDouble() ?: preset.veilingGlare),
                            "ghostIntensity" to ((flat["uvGhostIntensity"] as? Number)?.toDouble() ?: preset.ghostIntensity),
                            "softness" to ((flat["uvSoftness"] as? Number)?.toDouble() ?: preset.softness),
                            "tintR" to ((flat["uvTintR"] as? Number)?.toDouble() ?: preset.tintR),
                            "tintG" to ((flat["uvTintG"] as? Number)?.toDouble() ?: preset.tintG),
                            "tintB" to ((flat["uvTintB"] as? Number)?.toDouble() ?: preset.tintB),
                        ),
                    )
                }
            }
            if (explicitKeys.any { it.startsWith("bokeh") }) {
                val shape = BokehMaskShape.parse(flat["bokehMask"] as? String) ?: BokehMaskShape.OFF
                upsertOrRemove(BokehMaskFilterElement.TYPE_ID, shape != BokehMaskShape.OFF) {
                    OpticalFilterSlotSpec(
                        BokehMaskFilterElement.TYPE_ID,
                        true,
                        linkedMapOf(
                            "shape" to shape.id,
                            "angleDeg" to ((flat["bokehMaskAngleDeg"] as? Number)?.toDouble() ?: 0.0),
                            "scale" to ((flat["bokehMaskScale"] as? Number)?.toDouble() ?: 0.96),
                        ),
                    )
                }
            }
            if (explicitKeys.any { it.startsWith("star") }) {
                val on = flat["starEnabled"] as? Boolean ?: false
                upsertOrRemove(StarFilterElement.TYPE_ID, on) {
                    OpticalFilterSlotSpec(
                        StarFilterElement.TYPE_ID,
                        true,
                        linkedMapOf(
                            "points" to ((flat["starPoints"] as? Number)?.toLong() ?: 6L),
                            "strength" to ((flat["starStrength"] as? Number)?.toDouble() ?: 0.75),
                            "threshold" to ((flat["starThreshold"] as? Number)?.toDouble() ?: 0.55),
                            "angleDeg" to ((flat["starAngleDeg"] as? Number)?.toDouble() ?: 15.0),
                            "length" to ((flat["starLength"] as? Number)?.toDouble() ?: 0.12),
                        ),
                    )
                }
            }
            if (explicitKeys.any { it.startsWith("mist") }) {
                val on = flat["mistEnabled"] as? Boolean ?: false
                val s = ((flat["mistStrength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                upsertOrRemove(BlackMistFilterElement.TYPE_ID, on && s > 1e-4) {
                    OpticalFilterSlotSpec(
                        BlackMistFilterElement.TYPE_ID,
                        true,
                        linkedMapOf(
                            "preset" to ((flat["mistPreset"] as? String) ?: BlackMistFilterElement.presetForStrength(s)),
                            "strength" to s,
                            "threshold" to ((flat["mistThreshold"] as? Number)?.toDouble() ?: 0.35),
                        ),
                    )
                }
            }
            if (explicitKeys.any { it.startsWith("gnd") }) {
                val on = flat["gndEnabled"] as? Boolean ?: false
                val stops = ((flat["gndStops"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 5.0)
                upsertOrRemove(GndFilterElement.TYPE_ID, on && stops > 1e-4) {
                    OpticalFilterSlotSpec(
                        GndFilterElement.TYPE_ID,
                        true,
                        linkedMapOf(
                            "stops" to stops,
                            "horizon" to ((flat["gndHorizon"] as? Number)?.toDouble() ?: 0.50),
                            "feather" to ((flat["gndFeather"] as? Number)?.toDouble() ?: 0.25),
                            "angleDeg" to ((flat["gndAngleDeg"] as? Number)?.toDouble() ?: 0.0),
                        ),
                    )
                }
            }
            return mutable
        }

        internal fun synthesizeSlotsFromFlat(cfg: Map<String, Any>): List<OpticalFilterSlotSpec> {
            val list = ArrayList<OpticalFilterSlotSpec>(6)
            val uvPreset = UvFilterPreset.parse(cfg["uvPreset"] as? String) ?: UvFilterPreset.OFF
            if (uvPreset != UvFilterPreset.OFF) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = UvFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "preset" to uvPreset.id,
                            "transmittance" to ((cfg["uvTransmittance"] as? Number)?.toDouble() ?: uvPreset.transmittance),
                            "veilingGlare" to ((cfg["uvVeilingGlare"] as? Number)?.toDouble() ?: uvPreset.veilingGlare),
                            "ghostIntensity" to ((cfg["uvGhostIntensity"] as? Number)?.toDouble() ?: uvPreset.ghostIntensity),
                            "softness" to ((cfg["uvSoftness"] as? Number)?.toDouble() ?: uvPreset.softness),
                            "tintR" to ((cfg["uvTintR"] as? Number)?.toDouble() ?: uvPreset.tintR),
                            "tintG" to ((cfg["uvTintG"] as? Number)?.toDouble() ?: uvPreset.tintG),
                            "tintB" to ((cfg["uvTintB"] as? Number)?.toDouble() ?: uvPreset.tintB),
                        ),
                    ),
                )
            }
            val ndStops = NdFilterSpec.clampStops((cfg["ndStops"] as? Number)?.toDouble() ?: 0.0)
            if (ndStops > 1e-4) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = NdFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "preset" to ((cfg["ndPreset"] as? String) ?: NdFilterSpec.presetForStops(ndStops).id),
                            "stops" to ndStops,
                        ),
                    ),
                )
            }
            val bokeh = BokehMaskShape.parse(cfg["bokehMask"] as? String) ?: BokehMaskShape.OFF
            if (bokeh != BokehMaskShape.OFF) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = BokehMaskFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "shape" to bokeh.id,
                            "angleDeg" to ((cfg["bokehMaskAngleDeg"] as? Number)?.toDouble() ?: 0.0),
                            "scale" to ((cfg["bokehMaskScale"] as? Number)?.toDouble() ?: 0.96),
                        ),
                    ),
                )
            }
            if (cfg["starEnabled"] as? Boolean == true) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = StarFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "points" to ((cfg["starPoints"] as? Number)?.toLong() ?: 6L),
                            "strength" to ((cfg["starStrength"] as? Number)?.toDouble() ?: 0.75),
                            "threshold" to ((cfg["starThreshold"] as? Number)?.toDouble() ?: 0.55),
                            "angleDeg" to ((cfg["starAngleDeg"] as? Number)?.toDouble() ?: 15.0),
                            "length" to ((cfg["starLength"] as? Number)?.toDouble() ?: 0.12),
                        ),
                    ),
                )
            }
            val mistOn = cfg["mistEnabled"] as? Boolean == true
            val mistS = ((cfg["mistStrength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
            if (mistOn && mistS > 1e-4) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = BlackMistFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "preset" to ((cfg["mistPreset"] as? String) ?: BlackMistFilterElement.presetForStrength(mistS)),
                            "strength" to mistS,
                            "threshold" to ((cfg["mistThreshold"] as? Number)?.toDouble() ?: 0.35),
                        ),
                    ),
                )
            }
            val gndOn = cfg["gndEnabled"] as? Boolean == true
            val gndStops = ((cfg["gndStops"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 5.0)
            if (gndOn && gndStops > 1e-4) {
                list.add(
                    OpticalFilterSlotSpec(
                        type = GndFilterElement.TYPE_ID,
                        enabled = true,
                        params = linkedMapOf(
                            "stops" to gndStops,
                            "horizon" to ((cfg["gndHorizon"] as? Number)?.toDouble() ?: 0.50),
                            "feather" to ((cfg["gndFeather"] as? Number)?.toDouble() ?: 0.25),
                            "angleDeg" to ((cfg["gndAngleDeg"] as? Number)?.toDouble() ?: 0.0),
                        ),
                    ),
                )
            }
            return list
        }
    }

    private class OpticalFilterComponent(
        context: ComponentContext,
        initialConfig: Map<String, Any>,
        private val explicitFiltersDir: String,
        private val extraProviders: List<OpticalFilterElementProvider>,
        initialRegistry: OpticalFilterModuleRegistry,
    ) : ScalarComponent("optical-filter", context, initialConfig), StatefulComponent, OpticalFilterStackPort {
        private val registryLock = Any()

        @Volatile
        private var registry: OpticalFilterModuleRegistry = initialRegistry

        @Volatile
        private var providers: List<OpticalFilterElementProvider> = initialRegistry.providers

        @Volatile
        private var providersByType: Map<String, OpticalFilterElementProvider> =
            initialRegistry.providers.associateBy { it.filterType }

        private val revCounter = AtomicLong(1L)
        private var lastRescanTrigger: Long = (initialConfig["rescanPlugins"] as? Number)?.toLong() ?: 0L

        @Volatile
        private var masterEnabled: Boolean = initialConfig["enabled"] as? Boolean ?: true

        @Volatile
        private var elements: List<OpticalFilterElement> = emptyList()

        @Volatile
        private var activeCache: List<OpticalFilterElement> = emptyList()

        @Volatile
        private var effectiveSlots: List<OpticalFilterSlotSpec> = emptyList()

        @Volatile
        private var explicitSlotMode: Boolean = false

        @Volatile
        private var cachedTransmittance: Double = 1.0

        @Volatile
        private var cachedTotalEvLoss: Double = 0.0

        @Volatile
        private var cachedEffectiveNdStops: Double = 0.0

        @Volatile
        private var cachedHasSpatial: Boolean = false

        @Volatile
        private var cachedPupilMask: OpticalPupilMask? = null

        init {
            rebuildStack(initialConfig)
            context.ports.register(id, OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java, this)
        }

        private fun rebuildStack(cfg: Map<String, Any>) {
            val enabledFlag = cfg["enabled"] as? Boolean ?: true
            val parsedSlots = OpticalFilterSlotCodec.parse(cfg["slots"] as? String)
            val currentProviders = providers
            val currentByType = providersByType
            val built: List<OpticalFilterElement>
            val active: List<OpticalFilterElement>
            val slotsSnapshot: List<OpticalFilterSlotSpec>

            if (parsedSlots.isNotEmpty()) {
                val builtList = ArrayList<OpticalFilterElement>(parsedSlots.size)
                val activeList = ArrayList<OpticalFilterElement>(parsedSlots.size)
                for (slot in parsedSlots) {
                    val provider = currentByType[slot.type] ?: continue
                    val slotCfg = LinkedHashMap<String, Any?>(slot.params.size + 1)
                    slotCfg.putAll(slot.params)
                    slotCfg["enabled"] = slot.enabled
                    val el = provider.create(slotCfg)
                    builtList.add(el)
                    if (enabledFlag && slot.enabled && el.enabled) {
                        activeList.add(el)
                    }
                }
                built = builtList
                active = activeList
                slotsSnapshot = parsedSlots
            } else {
                built = currentProviders.map { it.create(cfg) }
                active = if (enabledFlag) built.filter { it.enabled } else emptyList()
                slotsSnapshot = synthesizeSlotsFromFlat(cfg)
            }

            var trans = 1.0
            var ndStopsSum = 0.0
            var spatial = false
            val activeMasks = ArrayList<OpticalPupilMask>(2)
            for (el in active) {
                trans *= el.transmittanceMultiplier().coerceIn(1e-6, 1.0)
                if (el is NdFilterElement) {
                    ndStopsSum += NdFilterSpec.clampStops(el.stops)
                }
                if (el.hasSpatialEffect) spatial = true
                val m = el.pupilMask()
                if (m != null && m.active) {
                    activeMasks.add(m)
                }
            }
            val composedMask: OpticalPupilMask? = when (activeMasks.size) {
                0 -> null
                1 -> activeMasks[0]
                else -> CompositeOpticalPupilMask(activeMasks)
            }
            val finalTrans = if (enabledFlag) trans.coerceIn(1e-6, 1.0) else 1.0
            val totalEvLoss = if (finalTrans >= 0.999999) 0.0 else (-ln(finalTrans) / ln(2.0))

            masterEnabled = enabledFlag
            elements = built
            activeCache = active
            effectiveSlots = slotsSnapshot
            explicitSlotMode = parsedSlots.isNotEmpty()
            cachedTransmittance = finalTrans
            cachedTotalEvLoss = totalEvLoss
            cachedEffectiveNdStops = if (enabledFlag) ndStopsSum else 0.0
            cachedHasSpatial = enabledFlag && spatial
            cachedPupilMask = if (enabledFlag) composedMask else null
            revCounter.incrementAndGet()
        }

        override val revision: Long get() = revCounter.get()
        override val enabled: Boolean get() = masterEnabled
        override fun activeElements(): List<OpticalFilterElement> = activeCache
        override fun totalTransmittance(): Double = cachedTransmittance
        override fun hasActiveSpatialEffects(): Boolean = cachedHasSpatial
        override fun activePupilMask(): OpticalPupilMask? = cachedPupilMask

        override fun applySpatialStack(
            red: FloatArray,
            green: FloatArray,
            blue: FloatArray,
            width: Int,
            height: Int,
            scratch: OpticalFilterScratch,
        ) {
            applySpatialStack(red, green, blue, width, height, scratch, OpticalFilterContext.DEFAULT)
        }

        override fun applySpatialStack(
            red: FloatArray,
            green: FloatArray,
            blue: FloatArray,
            width: Int,
            height: Int,
            scratch: OpticalFilterScratch,
            context: OpticalFilterContext,
        ) {
            if (!masterEnabled || !cachedHasSpatial) return
            val snapshot = activeCache
            for (el in snapshot) {
                if (el.enabled && el.hasSpatialEffect) {
                    el.applySpatialLinearRgb(red, green, blue, width, height, scratch, context)
                }
            }
        }

        override fun rescanExternalPlugins(): Map<String, Any?> = synchronized(registryLock) {
            val nextRegistry = OpticalFilterModuleLoader.discover(
                componentDir = context.directory,
                explicitFiltersDir = explicitFiltersDir,
                extraProviders = extraProviders,
            )
            val oldRegistry = registry
            registry = nextRegistry
            providers = nextRegistry.providers
            providersByType = nextRegistry.providers.associateBy { it.filterType }
            rebuildStack(values)
            oldRegistry.close()
            linkedMapOf(
                "pluginJarCount" to nextRegistry.jarCount,
                "pluginFilterTypes" to nextRegistry.jarFilterTypes.sorted(),
                "availableFilterTypes" to nextRegistry.providers.map { it.filterType },
                "totalProviders" to nextRegistry.providers.size,
            )
        }

        override fun applySettings(settings: Map<String, Any>) {
            val reconciled = reconcileLinkedSettings(values + settings, explicitKeys = settings.keys)
            super.applySettings(reconciled)
            val nextRescan = (values["rescanPlugins"] as? Number)?.toLong() ?: lastRescanTrigger
            if (nextRescan > lastRescanTrigger) {
                lastRescanTrigger = nextRescan
                rescanExternalPlugins()
            } else {
                rebuildStack(values)
            }
        }

        override fun stackState(): Map<String, Any?> {
            val active = activeCache
            val ndStops = cachedEffectiveNdStops
            val slotsSnapshot = effectiveSlots
            val currentRegistry = registry
            val currentProviders = providers
            val badges = active.mapNotNull { it.summaryBadge() }
            val activeSummary = when {
                !masterEnabled -> "BYPASSED"
                badges.isEmpty() -> "OFF"
                else -> badges.joinToString(" + ")
            }
            return linkedMapOf(
                "enabled" to masterEnabled,
                "revision" to revision,
                "totalTransmittance" to cachedTransmittance,
                "totalTransmittancePercent" to cachedTransmittance * 100.0,
                "totalEvLoss" to cachedTotalEvLoss,
                "effectiveNdStops" to ndStops,
                "ndFactor" to NdFilterSpec.factor(ndStops),
                "ndOpticalDensity" to NdFilterSpec.opticalDensity(ndStops),
                "ndSummary" to NdFilterSpec.formatSummary(ndStops),
                "hasSpatialEffects" to cachedHasSpatial,
                "hasPupilMask" to (cachedPupilMask != null),
                "activeFilters" to active.map { it.id },
                "activeSummary" to activeSummary,
                "availableFilterTypes" to currentProviders.map { it.filterType },
                "availableFilterProviders" to currentRegistry.providerSummaries(),
                "pluginJarCount" to currentRegistry.jarCount,
                "pluginFilterTypes" to currentRegistry.jarFilterTypes.sorted(),
                "slotMode" to explicitSlotMode,
                "slots" to slotsSnapshot.map { it.toMap() },
                "slotsJson" to OpticalFilterSlotCodec.encode(slotsSnapshot),
                "stack" to elements.map { it.stateMap() },
            )
        }

        override fun close() {
            synchronized(registryLock) {
                registry.close()
            }
        }

        override fun state(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(super.state())
            out.putAll(stackState())
            return out
        }

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalarValues" to saveScalarValues(),
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require((data["schema"] as? Number)?.toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected keys in snapshot for $id" }
            val supplied = data["scalarValues"] as? Map<*, *> ?: error("Missing scalarValues map for $id")
            val next = LinkedHashMap<String, Any>(values)
            val explicit = LinkedHashSet<String>()
            for (setting in DESCRIPTOR.settings) {
                if (!supplied.containsKey(setting.key)) continue
                val raw = checkNotNull(supplied[setting.key]) { "Null setting ${setting.key} in $id" }
                next[setting.key] = setting.validate(raw)
                explicit += setting.key
            }
            val reconciled = reconcileLinkedSettings(next, explicitKeys = explicit)
            values = reconciled
            rebuildStack(reconciled)
        }
    }
}
