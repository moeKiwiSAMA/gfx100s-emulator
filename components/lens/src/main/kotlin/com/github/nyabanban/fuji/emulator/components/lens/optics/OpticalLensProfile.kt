package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.LensParameters
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Interpolation filter used by [CpuOpticalPipeline] during sub-pixel optical resampling.
 */
enum class SamplingMode {
    /** 2x2 bilinear interpolation (fast, monotonic, zero overshoot). */
    BILINEAR,

    /** 4x4 Catmull-Rom bicubic interpolation (sharper micro-contrast, clamped non-negative). */
    BICUBIC,
}

/**
 * Out-of-bounds sampling policy when distortion, zoom, or IBIS moves rays past the source frame.
 */
enum class BorderMode {
    /** Rays outside the source image or lens image circle return zero radiance (black). */
    BLACK,

    /** Rays outside the source image clamp to the nearest source edge pixel. */
    CLAMP,
}

/**
 * Immutable optical lens model for the deterministic M-Optics P2 CPU reference engine.
 *
 * **Evidence classification (`HYPOTHESIS` / `SIM`)**: Analytical thin-lens, Brown-Conrady
 * distortion, chromatic dispersion, and vignetting equations are physical simulation models
 * for deterministic reference evaluation and future Vulkan Compute shader parity verification.
 * Default coefficients do not represent measured FUJIFILM GFX100S / GF lens calibration tables.
 *
 * @property focalLengthMm Active lens focal length in millimetres (`> 0`).
 * @property referenceFocalLengthMm Reference `1.0x` magnification focal length in millimetres (`> 0`).
 * @property focusBreathing Fractional focal-length shift at close focus (`in -0.5..0.5`).
 * @property k1 Primary Brown-Conrady radial distortion coefficient (`< 0` barrel moves corners inward;
 * `> 0` pincushion moves corners outward).
 * @property k2 Secondary Brown-Conrady radial distortion coefficient.
 * @property p1 First Brown-Conrady tangential (decentering) distortion coefficient.
 * @property p2 Second Brown-Conrady tangential (decentering) distortion coefficient.
 * @property caRedScale Lateral chromatic aberration radial magnification of Red relative to Green (`> 0`;
 * `> 1.0` shifts Red outward, `< 1.0` shifts Red inward).
 * @property caBlueScale Lateral chromatic aberration radial magnification of Blue relative to Green (`> 0`;
 * `< 1.0` shifts Blue inward, `> 1.0` shifts Blue outward).
 * @property longitudinalCaDefocusMm Longitudinal chromatic aberration focal-length offset in millimetres
 * applied symmetrically (`+delta` for Red, `0` for Green, `-delta` for Blue) during CoC defocus.
 * @property cos4Power Natural illumination falloff exponent (`cos^p(theta)` where `p = 4.0` is an
 * ideal rectilinear thin lens and `0.0` disables natural vignetting).
 * @property mechanicalVignetting Additional corner mechanical/cat's-eye falloff strength (`in 0.0..1.0`).
 * @property fNumber Aperture f-number (`> 0`), governing thin-lens Circle-of-Confusion diameter and exposure.
 * @property focusDistanceM Focus plane distance from the lens in metres (`> 0`).
 * @property sensorWidthMm Physical sensor width in millimetres (`> 0`; default `43.8` mm nominal medium format).
 * @property transmission Optical transmittance factor (`in 0.0..1.0`).
 */
data class OpticalLensProfile(
    val focalLengthMm: Double = 63.0,
    val referenceFocalLengthMm: Double = 63.0,
    val focusBreathing: Double = 0.0,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val caRedScale: Double = 1.0,
    val caBlueScale: Double = 1.0,
    val longitudinalCaDefocusMm: Double = 0.0,
    val cos4Power: Double = 0.0,
    val mechanicalVignetting: Double = 0.0,
    val fNumber: Double = 2.8,
    val focusDistanceM: Double = 10.0,
    val sensorWidthMm: Double = 43.8,
    val transmission: Double = 1.0,
    val imageCircleMm: Double = DEFAULT_IMAGE_CIRCLE_MM,
    val minFNumber: Double = fNumber,
    val wideOpenContrastDrop: Double = 0.0,
    val flareStrength: Double = 0.0,
    val flareTintR: Double = 1.0,
    val flareTintG: Double = 0.95,
    val flareTintB: Double = 0.88,
    val apertureBladeCount: Int = 9,
    val apertureBladeRoundness: Double = 1.0,
    val bokehCatsEye: Double = 0.0,
    val bokehOnionRing: Double = 0.0,
    val bokehRimHighlight: Double = 0.0,
) {
    init {
        require(focalLengthMm.isFinite() && focalLengthMm > 0.0) {
            "focalLengthMm must be positive finite, got $focalLengthMm"
        }
        require(referenceFocalLengthMm.isFinite() && referenceFocalLengthMm > 0.0) {
            "referenceFocalLengthMm must be positive finite, got $referenceFocalLengthMm"
        }
        require(focusBreathing.isFinite() && focusBreathing in -0.5..0.5) {
            "focusBreathing must be in -0.5..0.5, got $focusBreathing"
        }
        require(k1.isFinite() && k2.isFinite() && p1.isFinite() && p2.isFinite()) {
            "Brown-Conrady distortion coefficients must be finite"
        }
        require(caRedScale.isFinite() && caRedScale > 0.0) {
            "caRedScale must be positive finite, got $caRedScale"
        }
        require(caBlueScale.isFinite() && caBlueScale > 0.0) {
            "caBlueScale must be positive finite, got $caBlueScale"
        }
        require(longitudinalCaDefocusMm.isFinite()) {
            "longitudinalCaDefocusMm must be finite, got $longitudinalCaDefocusMm"
        }
        require(cos4Power.isFinite() && cos4Power >= 0.0) {
            "cos4Power must be non-negative finite, got $cos4Power"
        }
        require(mechanicalVignetting.isFinite() && mechanicalVignetting in 0.0..1.0) {
            "mechanicalVignetting must be in 0.0..1.0, got $mechanicalVignetting"
        }
        require(fNumber.isFinite() && fNumber > 0.0) {
            "fNumber must be positive finite, got $fNumber"
        }
        require(minFNumber.isFinite() && minFNumber > 0.0) {
            "minFNumber must be positive finite, got $minFNumber"
        }
        require(focusDistanceM.isFinite() && focusDistanceM > 0.0) {
            "focusDistanceM must be positive finite, got $focusDistanceM"
        }
        require(sensorWidthMm.isFinite() && sensorWidthMm > 0.0) {
            "sensorWidthMm must be positive finite, got $sensorWidthMm"
        }
        require(transmission.isFinite() && transmission in 0.0..1.0) {
            "transmission must be in 0.0..1.0, got $transmission"
        }
        require(imageCircleMm.isFinite() && imageCircleMm > 0.0) {
            "imageCircleMm must be positive finite, got $imageCircleMm"
        }
        require(wideOpenContrastDrop.isFinite() && wideOpenContrastDrop in 0.0..0.6) {
            "wideOpenContrastDrop must be in 0.0..0.6, got $wideOpenContrastDrop"
        }
        require(flareStrength.isFinite() && flareStrength in 0.0..1.0) {
            "flareStrength must be in 0.0..1.0, got $flareStrength"
        }
        require(flareTintR.isFinite() && flareTintR in 0.0..2.0 && flareTintG.isFinite() && flareTintG in 0.0..2.0 && flareTintB.isFinite() && flareTintB in 0.0..2.0) {
            "flareTint RGB must be in 0.0..2.0, got ($flareTintR, $flareTintG, $flareTintB)"
        }
        require(apertureBladeCount in 5..11) {
            "apertureBladeCount must be in 5..11, got $apertureBladeCount"
        }
        require(apertureBladeRoundness.isFinite() && apertureBladeRoundness in 0.0..1.0) {
            "apertureBladeRoundness must be in 0.0..1.0, got $apertureBladeRoundness"
        }
        require(bokehCatsEye.isFinite() && bokehCatsEye in 0.0..1.0) {
            "bokehCatsEye must be in 0.0..1.0, got $bokehCatsEye"
        }
        require(bokehOnionRing.isFinite() && bokehOnionRing in 0.0..0.5) {
            "bokehOnionRing must be in 0.0..0.5, got $bokehOnionRing"
        }
        require(bokehRimHighlight.isFinite() && bokehRimHighlight in 0.0..0.6) {
            "bokehRimHighlight must be in 0.0..0.6, got $bokehRimHighlight"
        }
    }

    /**
     * Normalized wide-open aperture ratio (`minFNumber / fNumber` in `(0.0, 1.0]`).
     * Equals `1.0` when wide open and decreases smoothly as the iris stops down.
     */
    fun apertureOpenRatio(): Double = (minFNumber / fNumber).coerceIn(0.0, 1.0)

    /**
     * Longitudinal CA focal-length offset attenuated inversely with f-number as the lens stops down.
     */
    fun effectiveLongitudinalCaDefocusMm(): Double = longitudinalCaDefocusMm * apertureOpenRatio()

    /**
     * Wide-open spherical glow / contrast drop scaled by `(minFNumber / fNumber)^2` so stopping
     * down rapidly restores crisp micro-contrast.
     */
    fun effectiveContrastDrop(): Double {
        val r = apertureOpenRatio()
        return (wideOpenContrastDrop * r * r).coerceIn(0.0, 0.6)
    }

    /**
     * Effective polygonal aperture strength in `[0.0, 1.0]`: circular (`0.0`) wide open, ramping
     * smoothly toward `(1.0 - apertureBladeRoundness)` as the iris stops down.
     */
    fun effectiveBladePolygonStrength(): Double {
        val stopDown = (1.0 - apertureOpenRatio()).coerceIn(0.0, 1.0)
        val bladeEngagement = (stopDown * 2.0).coerceIn(0.0, 1.0)
        return ((1.0 - apertureBladeRoundness) * bladeEngagement).coerceIn(0.0, 1.0)
    }

    /**
     * Effective radial corner cat's-eye clipping proportional to `(minFNumber / fNumber)`.
     */
    fun effectiveCatsEyeStrength(): Double = (bokehCatsEye * apertureOpenRatio()).coerceIn(0.0, 1.0)

    /**
     * True when any bokeh-shape modifier (polygonal blades, cat's-eye, onion-ring, or rim highlight)
     * is active; when false, [CpuOpticalPipeline] runs the fast circular-disk path.
     */
    fun hasShapedBokeh(): Boolean =
        effectiveBladePolygonStrength() > 1e-4 ||
            effectiveCatsEyeStrength() > 1e-4 ||
            bokehOnionRing > 1e-4 ||
            bokehRimHighlight > 1e-4

    /**
     * Effective focal length in millimetres after applying [focusBreathing] at [focusDistanceM].
     */
    fun effectiveFocalLengthMm(): Double {
        if (focusBreathing == 0.0) return focalLengthMm
        val closeFocusFactor = (focalLengthMm / (focusDistanceM * 1000.0)).coerceIn(0.0, 1.0)
        return (focalLengthMm * (1.0 + focusBreathing * closeFocusFactor)).coerceAtLeast(1e-3)
    }

    /**
     * 35mm full-frame equivalent focal length in millimetres (nominal 0.79x GFX medium-format factor).
     */
    fun equivalent35mmFocalMm(): Double =
        effectiveFocalLengthMm() * (FULL_FRAME_DIAGONAL_MM / (sensorWidthMm * 1.25))

    /**
     * Horizontal field of view in degrees across [sensorWidthMm].
     */
    fun horizontalFovDeg(): Double =
        Math.toDegrees(2.0 * atan(sensorWidthMm / (2.0 * effectiveFocalLengthMm())))

    /**
     * Central magnification scale relative to [referenceFocalLengthMm].
     */
    fun zoomScale(): Double = effectiveFocalLengthMm() / referenceFocalLengthMm

    /**
     * True if the sensor corner reaches into the [imageCircleMm] smooth falloff zone for an output
     * image of size [dstWidth] x [dstHeight].
     */
    fun hasImageCircleFalloff(dstWidth: Int, dstHeight: Int): Boolean {
        val cornerPx = hypot((dstWidth - 1) * 0.5, (dstHeight - 1) * 0.5)
        val cornerMm = cornerPx * (sensorWidthMm / dstWidth.toDouble())
        return cornerMm > imageCircleMm * 0.5 * IMAGE_CIRCLE_ONSET_RATIO
    }

    /**
     * Evaluates the optical vignetting transmittance in `[0.0, 1.0]` at sensor-plane offset
     * `([dxPx], [dyPx])` from the optical axis for an output image of width [sensorWidthPixels]
     * and half-diagonal [halfDiagPixels]. Includes `cos^p`, mechanical falloff, and physical
     * [imageCircleMm] cutoff (for adapted smaller-format lenses on medium format).
     */
    fun vignettingGain(
        dxPx: Double,
        dyPx: Double,
        sensorWidthPixels: Int,
        halfDiagPixels: Double,
    ): Double {
        val mmPerPx = sensorWidthMm / sensorWidthPixels.toDouble()
        val icRadiusMm = imageCircleMm * 0.5
        val onsetMm = icRadiusMm * IMAGE_CIRCLE_ONSET_RATIO
        val invTransitionMm = 1.0 / (icRadiusMm - onsetMm)
        val effF = effectiveFocalLengthMm()
        val invHalfDiagSq = if (halfDiagPixels > 0.0) 1.0 / (halfDiagPixels * halfDiagPixels) else 0.0
        return vignettingGainPrecomputed(
            dxPx = dxPx,
            dyPx = dyPx,
            mmPerPx = mmPerPx,
            icRadiusMm = icRadiusMm,
            onsetMm = onsetMm,
            invTransitionMm = invTransitionMm,
            effFSq = effF * effF,
            invHalfDiagSq = invHalfDiagSq,
        )
    }

    internal fun vignettingGainPrecomputed(
        dxPx: Double,
        dyPx: Double,
        mmPerPx: Double,
        icRadiusMm: Double,
        onsetMm: Double,
        invTransitionMm: Double,
        effFSq: Double,
        invHalfDiagSq: Double,
    ): Double {
        val rPxSq = dxPx * dxPx + dyPx * dyPx
        val rPx = sqrt(rPxSq)
        val rMm = rPx * mmPerPx
        if (rMm >= icRadiusMm) return 0.0

        var gain = 1.0
        if (rMm > onsetMm) {
            val t = ((rMm - onsetMm) * invTransitionMm).coerceIn(0.0, 1.0)
            val smooth = t * t * (3.0 - 2.0 * t)
            gain *= (1.0 - smooth)
        }
        val p = cos4Power
        if (p > 0.0) {
            val cosThetaSq = effFSq / (effFSq + rMm * rMm)
            gain *= when (p) {
                2.0 -> cosThetaSq
                4.0 -> cosThetaSq * cosThetaSq
                1.0 -> sqrt(cosThetaSq)
                3.0 -> cosThetaSq * sqrt(cosThetaSq)
                else -> cosThetaSq.pow(p * 0.5)
            }
        }
        if (mechanicalVignetting > 0.0 && invHalfDiagSq > 0.0) {
            val mech = (1.0 - mechanicalVignetting * rPxSq * invHalfDiagSq).coerceIn(0.0, 1.0)
            gain *= mech
        }
        return gain.coerceIn(0.0, 1.0)
    }

    /**
     * Computes the physical thin-lens Circle-of-Confusion (CoC) diameter in micrometres for a scene
     * point at [depthMeters] using [channelFocalLengthMm].
     */
    fun circleOfConfusionDiameterMicrons(
        depthMeters: Double,
        channelFocalLengthMm: Double = effectiveFocalLengthMm(),
    ): Double {
        val fM = channelFocalLengthMm.coerceAtLeast(1e-3) * 0.001
        val s1 = focusDistanceM
        val denom = (s1 - fM).coerceAtLeast(1e-6)
        val cocMm = when {
            depthMeters == Double.POSITIVE_INFINITY -> (fM * fM / (fNumber * denom)) * 1000.0
            !depthMeters.isFinite() || depthMeters <= 0.0 -> 0.0
            else -> (fM * fM / fNumber) * (abs(depthMeters - s1) / (depthMeters * denom)) * 1000.0
        }
        return cocMm * 1000.0
    }

    /**
     * Computes thin-lens hyperfocal distance in metres for acceptable CoC [cocLimitMm].
     */
    fun hyperfocalDistanceMeters(cocLimitMm: Double = GFX_COC_LIMIT_MM): Double {
        val fM = effectiveFocalLengthMm().coerceAtLeast(1e-3) * 0.001
        val cM = cocLimitMm.coerceAtLeast(1e-4) * 0.001
        return (fM * fM) / (fNumber * cM) + fM
    }

    /**
     * Computes thin-lens depth-of-field near and far limits in metres for acceptable CoC [cocLimitMm]
     * (default `0.035` mm for 44x33mm GFX medium format).
     */
    fun depthOfFieldRangeMeters(cocLimitMm: Double = GFX_COC_LIMIT_MM): Pair<Double, Double> {
        val fM = effectiveFocalLengthMm().coerceAtLeast(1e-3) * 0.001
        val hyperfocalM = hyperfocalDistanceMeters(cocLimitMm)
        val s = focusDistanceM
        val nearM = ((hyperfocalM * s) / (hyperfocalM + (s - fM))).coerceAtLeast(fM)
        val farM = if (s >= hyperfocalM) {
            Double.POSITIVE_INFINITY
        } else {
            (hyperfocalM * s) / (hyperfocalM - (s - fM))
        }
        return nearM to farM
    }

    /**
     * Computes the thin-lens Circle-of-Confusion (CoC) radius in pixels for a scene point at
     * [depthMeters] using [channelFocalLengthMm] and an output sensor of [sensorWidthPixels].
     */
    fun circleOfConfusionRadiusPixels(
        depthMeters: Double,
        sensorWidthPixels: Int,
        channelFocalLengthMm: Double = effectiveFocalLengthMm(),
    ): Double {
        val cocMm = circleOfConfusionDiameterMicrons(depthMeters, channelFocalLengthMm) * 0.001
        val pixelPitchMm = sensorWidthMm / sensorWidthPixels.toDouble()
        return 0.5 * (cocMm / pixelPitchMm)
    }

    companion object {
        const val DEFAULT_IMAGE_CIRCLE_MM = 120.0
        const val GFX_COC_LIMIT_MM = 0.035
        private const val FULL_FRAME_DIAGONAL_MM = 43.2666
        internal const val IMAGE_CIRCLE_ONSET_RATIO = 0.88

        /** Unity pass-through optical profile with zero distortion, CA, vignetting, or zoom. */
        val IDENTITY = OpticalLensProfile()

        /**
         * Creates an [OpticalLensProfile] initialized from the narrow `component-api` [LensParameters]
         * contract while allowing optional optical aberration overrides.
         */
        fun fromLensParameters(
            lens: LensParameters,
            referenceFocalLengthMm: Double = lens.focalLengthMm,
            k1: Double = 0.0,
            k2: Double = 0.0,
            p1: Double = 0.0,
            p2: Double = 0.0,
            caRedScale: Double = 1.0,
            caBlueScale: Double = 1.0,
            longitudinalCaDefocusMm: Double = 0.0,
            cos4Power: Double = 0.0,
            mechanicalVignetting: Double = 0.0,
            sensorWidthMm: Double = 43.8,
            imageCircleMm: Double = DEFAULT_IMAGE_CIRCLE_MM,
            minFNumber: Double = lens.aperture,
            wideOpenContrastDrop: Double = 0.0,
            flareStrength: Double = 0.0,
            flareTintR: Double = 1.0,
            flareTintG: Double = 0.95,
            flareTintB: Double = 0.88,
            apertureBladeCount: Int = 9,
            apertureBladeRoundness: Double = 1.0,
            bokehCatsEye: Double = 0.0,
            bokehOnionRing: Double = 0.0,
            bokehRimHighlight: Double = 0.0,
        ): OpticalLensProfile = OpticalLensProfile(
            focalLengthMm = lens.focalLengthMm,
            referenceFocalLengthMm = referenceFocalLengthMm,
            focusBreathing = lens.focusBreathing,
            k1 = k1,
            k2 = k2,
            p1 = p1,
            p2 = p2,
            caRedScale = caRedScale,
            caBlueScale = caBlueScale,
            longitudinalCaDefocusMm = longitudinalCaDefocusMm,
            cos4Power = cos4Power,
            mechanicalVignetting = mechanicalVignetting,
            fNumber = lens.aperture,
            focusDistanceM = lens.focusDistanceM,
            sensorWidthMm = sensorWidthMm,
            transmission = lens.transmission,
            imageCircleMm = imageCircleMm,
            minFNumber = minFNumber,
            wideOpenContrastDrop = wideOpenContrastDrop,
            flareStrength = flareStrength,
            flareTintR = flareTintR,
            flareTintG = flareTintG,
            flareTintB = flareTintB,
            apertureBladeCount = apertureBladeCount,
            apertureBladeRoundness = apertureBladeRoundness,
            bokehCatsEye = bokehCatsEye,
            bokehOnionRing = bokehOnionRing,
            bokehRimHighlight = bokehRimHighlight,
        )
    }
}

/**
 * Immutable 5-axis In-Body Image Stabilization (IBIS) sensor pose offset (`HYPOTHESIS` / `SIM`).
 *
 * Positive [shiftXPixels] / [shiftYPixels] translate the projected scene right / down in output
 * pixel coordinates; positive [rollRadians] rotates the projected scene clockwise in Y-down
 * image coordinates (`x' = x*cos - y*sin`, `y' = x*sin + y*cos`). Optional [pitchRadians] and
 * [yawRadians] model small-angle nodal tilt projected into sensor-plane shifts via focal length.
 *
 * @property shiftXPixels Horizontal sensor shift in output pixels.
 * @property shiftYPixels Vertical sensor shift in output pixels.
 * @property rollRadians In-plane roll angle around the optical center in radians.
 * @property pitchRadians Optional vertical pitch angle in radians (`|pitch| < 0.45 * pi`).
 * @property yawRadians Optional horizontal yaw angle in radians (`|yaw| < 0.45 * pi`).
 */
data class IbisShiftState(
    val shiftXPixels: Double = 0.0,
    val shiftYPixels: Double = 0.0,
    val rollRadians: Double = 0.0,
    val pitchRadians: Double = 0.0,
    val yawRadians: Double = 0.0,
) {
    init {
        require(shiftXPixels.isFinite() && shiftYPixels.isFinite() && rollRadians.isFinite()) {
            "IBIS shift and roll must be finite"
        }
        require(pitchRadians.isFinite() && abs(pitchRadians) < MAX_TILT_RADIANS) {
            "IBIS pitchRadians out of range: $pitchRadians"
        }
        require(yawRadians.isFinite() && abs(yawRadians) < MAX_TILT_RADIANS) {
            "IBIS yawRadians out of range: $yawRadians"
        }
    }

    /** Total horizontal shift in output pixels combining [shiftXPixels] and [yawRadians]. */
    fun effectiveShiftXPixels(focalLengthPixels: Double): Double =
        if (yawRadians == 0.0) shiftXPixels else shiftXPixels + focalLengthPixels * tan(yawRadians)

    /** Total vertical shift in output pixels combining [shiftYPixels] and [pitchRadians]. */
    fun effectiveShiftYPixels(focalLengthPixels: Double): Double =
        if (pitchRadians == 0.0) shiftYPixels else shiftYPixels + focalLengthPixels * tan(pitchRadians)

    /** True when all five IBIS axes are identically zero. */
    val isIdentity: Boolean
        get() = shiftXPixels == 0.0 &&
            shiftYPixels == 0.0 &&
            rollRadians == 0.0 &&
            pitchRadians == 0.0 &&
            yawRadians == 0.0

    companion object {
        private const val MAX_TILT_RADIANS = Math.PI * 0.45

        /** Centered sensor with zero translation, roll, pitch, and yaw. */
        val ZERO = IbisShiftState()
    }
}
