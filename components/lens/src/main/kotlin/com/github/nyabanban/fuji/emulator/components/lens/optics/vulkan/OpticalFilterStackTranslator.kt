package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import kotlin.math.ln
import kotlin.math.min

/**
 * Decoupled translator mapping [OpticalFilterStackPort] active elements (`nd`, `gnd`, `black-mist`,
 * `star`, `uv`, `bokeh-mask`, and third-party Plugin SPI v1.0 [CustomSpirvPassSpec] shaders) to
 * Vulkan Compute Pass 0 [OpticalFilterStackConfig] + custom `.spv` passes without creating any
 * module dependency between `:components:lens` and `:components:optical-filter`.
 *
 * When any active spatial filter element is unknown to Vulkan Pass 0 and does not supply a
 * [CustomSpirvPassSpec] via `vulkanSpirvPass(width, height)` (or multiple instances of the same
 * built-in spatial filter type are stacked), [Translation.canOffloadSpatialToGpu] returns `false`
 * so CPU spatial filtering runs cleanly without double-application while GPU Pass 1..3 still run.
 */
internal object OpticalFilterStackTranslator {
    private const val ID_ND = "nd"
    private const val ID_BOKEH_MASK = "bokeh-mask"
    private const val ID_GND = "gnd"
    private const val ID_BLACK_MIST = "black-mist"
    private const val ID_STAR = "star"
    private const val ID_UV = "uv"
    private const val MAX_CUSTOM_SPV_PASSES = 8
    private val INV_LN2 = 1.0 / ln(2.0)

    data class Translation(
        val canOffloadSpatialToGpu: Boolean,
        val spatialOnlyConfig: OpticalFilterStackConfig,
        val fullConfigWithNd: OpticalFilterStackConfig,
        val spatialCacheKey: Long,
        val customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
    ) {
        companion object {
            val EMPTY = Translation(
                canOffloadSpatialToGpu = true,
                spatialOnlyConfig = OpticalFilterStackConfig.NONE,
                fullConfigWithNd = OpticalFilterStackConfig.NONE,
                spatialCacheKey = 0L,
                customSpirvPasses = emptyList(),
            )
        }
    }

    /**
     * Quick check used by [com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter.handlesSpatialFilterStack]
     * to determine whether every active spatial element in [filterStack] maps natively to Vulkan Pass 0
     * or supplies a Plugin SPI v1.0 [CustomSpirvPassSpec].
     */
    fun canOffloadSpatial(filterStack: OpticalFilterStackPort?): Boolean {
        if (filterStack == null || !filterStack.enabled) return false
        val elements = filterStack.activeElements()
        if (elements.isEmpty()) return false
        var hasSpatial = false
        var gndCount = 0
        var mistCount = 0
        var starCount = 0
        var uvCount = 0
        var customCount = 0
        for (el in elements) {
            if (!el.enabled || !el.hasSpatialEffect) continue
            hasSpatial = true
            if (!el.supportsVulkanBuiltin) {
                val spv = runCatching { el.vulkanSpirvPass(64, 64) }.getOrNull() ?: return false
                if (++customCount > MAX_CUSTOM_SPV_PASSES) return false
                continue
            }
            when (el.id) {
                ID_GND -> if (++gndCount > 1) return false
                ID_BLACK_MIST -> if (++mistCount > 1) return false
                ID_STAR -> if (++starCount > 1) return false
                ID_UV -> if (++uvCount > 1) return false
                else -> return false
            }
        }
        return hasSpatial
    }

    fun translate(filterStack: OpticalFilterStackPort?, srcWidth: Int, srcHeight: Int): Translation {
        if (filterStack == null || !filterStack.enabled) return Translation.EMPTY
        val elements = filterStack.activeElements()
        val totalTrans = filterStack.totalTransmittance().coerceIn(1e-6, 1.0)
        val totalNdStops = if (totalTrans < 0.999999) (-ln(totalTrans) * INV_LN2).coerceIn(0.0, 20.0) else 0.0
        if (elements.isEmpty() && totalNdStops <= 1e-4) return Translation.EMPTY

        var canOffloadSpatial = true
        var gndCount = 0
        var mistCount = 0
        var starCount = 0
        var uvCount = 0
        val customPasses = mutableListOf<CustomSpirvPassSpec>()

        var gndStops = 0.0
        var gndHorizonY = 0.5
        var gndFeather = 0.125
        var gndAngleDeg = 0.0

        var blackMistStrength = 0.0
        var blackMistThreshold = 0.35
        var blackMistRadiusPx = 8.0
        val blackMistWarmth = 0.08

        var starPoints = 0
        var starStrength = 0.0
        var starThreshold = 0.55
        var starRadiusPx = 14.0
        var starAngleDeg = 15.0

        var uvEnabled = false
        var uvVeilingGlare = 0.0
        var uvGhostIntensity = 0.0
        var uvSoftness = 0.0
        var uvTintR = 1.0
        var uvTintG = 1.0
        var uvTintB = 1.0
        var uvGhostRadiusPx = 8.0

        val minDim = min(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1)).toDouble()

        for (el in elements) {
            if (!el.enabled) continue
            if (!el.hasSpatialEffect) {
                // Scalar attenuators (e.g. "nd" or Pro MRC UV transmittance) fold into totalTransmittance() above;
                // "bokeh-mask" maps to pupilShape.
                continue
            }
            if (!el.supportsVulkanBuiltin) {
                val spvPass = runCatching { el.vulkanSpirvPass(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1)) }.getOrNull()
                if (spvPass != null && customPasses.size < MAX_CUSTOM_SPV_PASSES) {
                    customPasses.add(spvPass)
                } else {
                    canOffloadSpatial = false
                }
                continue
            }
            val map = el.stateMap()
            when (el.id) {
                ID_GND -> {
                    if (++gndCount > 1) {
                        canOffloadSpatial = false
                    } else {
                        gndStops = ((map["stops"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 5.0)
                        gndHorizonY = ((map["horizon"] as? Number)?.toDouble() ?: 0.5).coerceIn(0.0, 1.0)
                        // GndFilterElement uses halfFeather = feather * 0.5 whereas optical_filter_stack.comp
                        // transitions across (horizon - gndFeather)..(horizon + gndFeather), so divide by 2 for exact parity.
                        val rawFeather = ((map["feather"] as? Number)?.toDouble() ?: 0.25).coerceIn(0.02, 1.0)
                        gndFeather = (rawFeather * 0.5).coerceIn(0.005, 0.5)
                        gndAngleDeg = (map["angleDeg"] as? Number)?.toDouble() ?: 0.0
                    }
                }
                ID_BLACK_MIST -> {
                    if (++mistCount > 1) {
                        canOffloadSpatial = false
                    } else {
                        blackMistStrength = ((map["strength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                        blackMistThreshold = ((map["threshold"] as? Number)?.toDouble() ?: 0.35).coerceIn(0.0, 1.0)
                        blackMistRadiusPx = (minDim * (0.014 + 0.024 * blackMistStrength)).coerceIn(2.0, 24.0)
                    }
                }
                ID_STAR -> {
                    if (++starCount > 1) {
                        canOffloadSpatial = false
                    } else {
                        val pts = (map["points"] as? Number)?.toInt() ?: 6
                        starPoints = when {
                            pts <= 4 -> 4
                            pts <= 6 -> 6
                            else -> 8
                        }
                        starStrength = ((map["strength"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 2.0)
                        starThreshold = ((map["threshold"] as? Number)?.toDouble() ?: 0.55).coerceIn(0.10, 2.0)
                        starAngleDeg = (map["angleDeg"] as? Number)?.toDouble() ?: 15.0
                        val lenFrac = ((map["length"] as? Number)?.toDouble() ?: 0.12).coerceIn(0.02, 0.35)
                        starRadiusPx = (minDim * lenFrac).coerceIn(1.0, 32.0)
                    }
                }
                ID_UV -> {
                    if (++uvCount > 1) {
                        canOffloadSpatial = false
                    } else {
                        uvEnabled = true
                        uvVeilingGlare = ((map["veilingGlare"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 0.50)
                        uvGhostIntensity = ((map["ghostIntensity"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 0.50)
                        uvSoftness = ((map["softness"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
                        val rawTR = ((map["tintR"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                        val rawTG = ((map["tintG"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                        val rawTB = ((map["tintB"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.75, 1.25)
                        val lumaNorm = (0.2126 * rawTR + 0.7152 * rawTG + 0.0722 * rawTB).coerceAtLeast(0.1)
                        uvTintR = rawTR / lumaNorm
                        uvTintG = rawTG / lumaNorm
                        uvTintB = rawTB / lumaNorm
                        uvGhostRadiusPx = (minDim / 32.0).coerceIn(2.0, 16.0)
                    }
                }
                ID_ND, ID_BOKEH_MASK -> Unit
                else -> canOffloadSpatial = false
            }
        }

        val spatialOnly = if (canOffloadSpatial) {
            OpticalFilterStackConfig(
                ndStops = 0.0,
                gndStops = gndStops,
                gndHorizonY = gndHorizonY,
                gndFeather = gndFeather,
                gndAngleDeg = gndAngleDeg,
                blackMistStrength = blackMistStrength,
                blackMistThreshold = blackMistThreshold,
                blackMistRadiusPx = blackMistRadiusPx,
                blackMistWarmth = blackMistWarmth,
                starPoints = starPoints,
                starStrength = starStrength,
                starThreshold = starThreshold,
                starRadiusPx = starRadiusPx,
                starAngleDeg = starAngleDeg,
                uvEnabled = uvEnabled,
                uvVeilingGlare = uvVeilingGlare,
                uvGhostIntensity = uvGhostIntensity,
                uvSoftness = uvSoftness,
                uvTintR = uvTintR,
                uvTintG = uvTintG,
                uvTintB = uvTintB,
                uvGhostRadiusPx = uvGhostRadiusPx,
            )
        } else {
            OpticalFilterStackConfig.NONE
        }

        val fullWithNd = if (totalNdStops > 1e-4) {
            spatialOnly.copy(ndStops = totalNdStops)
        } else {
            spatialOnly
        }

        val effectiveCustomPasses = if (canOffloadSpatial) customPasses.toList() else emptyList()

        val spatialKey = if (spatialOnly.isBypassed && effectiveCustomPasses.isEmpty()) {
            0L
        } else {
            var h = spatialOnly.enableFlags.toLong() * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor gndStops.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor gndHorizonY.toRawBits()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor gndFeather.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor gndAngleDeg.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor blackMistStrength.toRawBits()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor blackMistThreshold.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor blackMistRadiusPx.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor starPoints.toLong()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor starStrength.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor starThreshold.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor starRadiusPx.toRawBits()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor starAngleDeg.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor uvVeilingGlare.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor uvGhostIntensity.toRawBits()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor uvSoftness.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor uvTintR.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor uvTintG.toRawBits()) * 0x94D049BB133111EBUL.toLong()
            h = (h xor uvTintB.toRawBits()) * 0x9E3779B97F4A7C15UL.toLong()
            h = (h xor uvGhostRadiusPx.toRawBits()) * 0xBF58476D1CE4E5B9UL.toLong()
            for (p in effectiveCustomPasses) {
                h = (h xor p.effectiveCacheKey()) * 0x94D049BB133111EBUL.toLong()
            }
            h xor (h ushr 31)
        }

        return Translation(
            canOffloadSpatialToGpu = canOffloadSpatial,
            spatialOnlyConfig = spatialOnly,
            fullConfigWithNd = fullWithNd,
            spatialCacheKey = spatialKey,
            customSpirvPasses = effectiveCustomPasses,
        )
    }
}
