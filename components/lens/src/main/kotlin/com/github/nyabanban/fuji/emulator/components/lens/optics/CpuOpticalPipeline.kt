package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensOpticalHook
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flat planar linear-radiance RGB image backed by primitive [FloatArray] buffers.
 *
 * Planar storage enables zero-allocation per-channel sub-pixel resampling during lateral
 * chromatic aberration and depth-aware bokeh convolution.
 */
class LinearRgbImage(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    init {
        require(width >= 1 && height >= 1) { "Image dimensions must be positive: ${width}x$height" }
        val expected = Math.multiplyExact(width, height)
        require(red.size == expected && green.size == expected && blue.size == expected) {
            "Channel arrays must each have size $expected (got R=${red.size}, G=${green.size}, B=${blue.size})"
        }
    }

    /** Pixel count (`width * height`). */
    val pixelCount: Int get() = width * height

    companion object {
        /** Allocates a blank (`0.0f`) linear RGB image of size [width] x [height]. */
        fun allocate(width: Int, height: Int): LinearRgbImage {
            val size = Math.multiplyExact(width, height)
            return LinearRgbImage(width, height, FloatArray(size), FloatArray(size), FloatArray(size))
        }

        /** Creates a uniform linear RGB image filled with `([r], [g], [b])`. */
        fun uniform(width: Int, height: Int, r: Float, g: Float, b: Float): LinearRgbImage {
            val size = Math.multiplyExact(width, height)
            return LinearRgbImage(
                width = width,
                height = height,
                red = FloatArray(size) { r },
                green = FloatArray(size) { g },
                blue = FloatArray(size) { b },
            )
        }

        /** Converts an interleaved `[R0, G0, B0, ...]` [FloatArray] into planar [LinearRgbImage]. */
        fun fromInterleavedRgb(width: Int, height: Int, interleaved: FloatArray): LinearRgbImage {
            val size = Math.multiplyExact(width, height)
            require(interleaved.size == Math.multiplyExact(size, 3)) {
                "Expected ${size * 3} interleaved floats, got ${interleaved.size}"
            }
            val r = FloatArray(size)
            val g = FloatArray(size)
            val b = FloatArray(size)
            var src = 0
            for (i in 0 until size) {
                r[i] = interleaved[src++]
                g[i] = interleaved[src++]
                b[i] = interleaved[src++]
            }
            return LinearRgbImage(width, height, r, g, b)
        }
    }
}

/**
 * Reusable scratch buffers for [CpuOpticalPipeline] so repeated frame renders incur zero heap
 * allocations once warmed up for a given sensor resolution.
 */
class OpticalFrameWorkspace(initialPixels: Int = 0) {
    internal var opticalR: FloatArray = FloatArray(initialPixels)
    internal var opticalG: FloatArray = FloatArray(initialPixels)
    internal var opticalB: FloatArray = FloatArray(initialPixels)
    internal var bokehR: FloatArray = FloatArray(initialPixels)
    internal var bokehG: FloatArray = FloatArray(initialPixels)
    internal var bokehB: FloatArray = FloatArray(initialPixels)
    internal var warpedDepth: FloatArray = FloatArray(initialPixels)
    internal var cocMap: FloatArray = FloatArray(initialPixels)
    internal var planarTmp: FloatArray = FloatArray(initialPixels)
    internal var blurColSum: DoubleArray = DoubleArray(0)
    internal var polyScaleLut: FloatArray = FloatArray(0)
    internal var pupilMaskLut: FloatArray = FloatArray(0)
    internal var pupilMaskLutKey: Long = 0L
    internal var outputBayerBuffer: ByteArray = ByteArray(0)

    internal fun ensureOpticalCapacity(pixelCount: Int) {
        if (opticalR.size < pixelCount) {
            opticalR = FloatArray(pixelCount)
            opticalG = FloatArray(pixelCount)
            opticalB = FloatArray(pixelCount)
        }
    }

    internal fun ensureBokehCapacity(pixelCount: Int) {
        if (bokehR.size < pixelCount) {
            bokehR = FloatArray(pixelCount)
            bokehG = FloatArray(pixelCount)
            bokehB = FloatArray(pixelCount)
            warpedDepth = FloatArray(pixelCount)
            cocMap = FloatArray(pixelCount)
            planarTmp = FloatArray(pixelCount)
        }
    }

    internal fun ensureBlurColSum(width: Int): DoubleArray {
        if (blurColSum.size < width) {
            blurColSum = DoubleArray(width)
        }
        return blurColSum
    }

    internal fun ensurePolyScaleLut(size: Int): FloatArray {
        if (polyScaleLut.size < size) {
            polyScaleLut = FloatArray(size)
        }
        return polyScaleLut
    }

    internal fun ensurePupilMaskLut(mask: OpticalPupilMask, radius: Int): FloatArray {
        val stride = radius * 2 + 1
        val size = stride * stride
        if (pupilMaskLut.size == size && pupilMaskLutKey == mask.cacheKey) {
            return pupilMaskLut
        }
        val lut = if (pupilMaskLut.size == size) pupilMaskLut else FloatArray(size).also { pupilMaskLut = it }
        val invR = 1.0 / radius.toDouble()
        var idx = 0
        for (iy in -radius..radius) {
            val uy = iy * invR
            for (ix in -radius..radius) {
                val ux = ix * invR
                lut[idx++] = mask.pupilWeight(ux, uy).toFloat().coerceIn(0f, 1f)
            }
        }
        pupilMaskLutKey = mask.cacheKey
        return lut
    }

    internal fun ensureOutputBayer(byteCount: Int): ByteArray {
        if (outputBayerBuffer.size < byteCount) {
            outputBayerBuffer = ByteArray(byteCount)
        }
        return outputBayerBuffer
    }
}

/**
 * Pure-Kotlin deterministic CPU optical + CMOS sensor reference engine (`M-Optics P2`).
 *
 * Serves as both the headless-CI / no-Vulkan production fallback and the bit-exact reference
 * oracle against which future `libfuji-optics-vk` Vulkan Compute shaders are verified.
 *
 * Pipeline stages:
 * 1. **IBIS 5-axis sensor pose** (`shiftX`, `shiftY`, `roll`, optional `pitch`/`yaw`).
 * 2. **Brown-Conrady lens distortion** (`k1`, `k2`, `p1`, `p2`), **zoom / focus-breathing scale**,
 *    and **lateral R/G/B chromatic aberration** (`caRedScale`, `caBlueScale`) with sub-pixel
 *    [SamplingMode.BILINEAR] or [SamplingMode.BICUBIC] interpolation.
 * 3. **Optical vignetting** (`cos^p(theta)` + mechanical corner falloff).
 * 4. **Optional depth-buffer Circle-of-Confusion (CoC) bokeh** driven by thin-lens `fNumber`,
 *    `focusDistanceM`, `sensorWidthMm`, and optional `longitudinalCaDefocusMm`.
 * 5. **CMOS Bayer CFA mosaicking & physical noise** via [CmosNoiseModel] into a packed
 *    little-endian Bayer [ByteBuffer] matching `component-api` [SensorGeometry] and [Exposure].
 *
 * **Allocation discipline**: Inner pixel loops operate strictly on flat primitive arrays
 * (`FloatArray`) and scalar primitives with zero per-pixel object allocations, striped across
 * [OpticalParallelExecutor] when [parallelThreads] > 1.
 *
 * @property maxBokehRadiusPx Hard cap on the CoC gather disk radius in pixels (`>= 0`).
 * @property parallelThreads Maximum worker stripes for parallelizable optical passes (`>= 1`).
 */
class CpuOpticalPipeline(
    val maxBokehRadiusPx: Int = DEFAULT_MAX_BOKEH_RADIUS_PX,
    val parallelThreads: Int = OpticalParallelExecutor.defaultThreadCount,
) {
    init {
        require(maxBokehRadiusPx >= 0) { "maxBokehRadiusPx must be non-negative, got $maxBokehRadiusPx" }
        require(parallelThreads >= 1) { "parallelThreads must be positive, got $parallelThreads" }
    }

    private fun effectiveThreads(pixelCount: Int): Int =
        if (parallelThreads > 1 && pixelCount >= PARALLEL_PIXEL_THRESHOLD) parallelThreads else 1

    /**
     * Renders [source] through the optical model ([profile], [ibis], optional [depthMeters]) and
     * CMOS physical sensor readout ([geometry], [exposure], [noiseConfig]) into [destination],
     * returning the number of bytes written.
     */
    fun render(
        source: LinearRgbImage,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        ibis: IbisShiftState = IbisShiftState.ZERO,
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        destination: ByteBuffer,
        depthMeters: FloatArray? = null,
        planarSubjectDistanceM: Double? = null,
        samplingMode: SamplingMode = SamplingMode.BILINEAR,
        borderMode: BorderMode = BorderMode.BLACK,
        frameIndex: Long = 0L,
        workspace: OpticalFrameWorkspace = OpticalFrameWorkspace(),
        filterTransmittance: Double = 1.0,
        pupilMask: OpticalPupilMask? = null,
        lensHook: LensOpticalHook? = null,
    ): Int {
        CmosNoiseModel.validateGeometryAndExposure(geometry, exposure, noiseConfig)
        if (depthMeters != null) {
            require(depthMeters.size == source.pixelCount) {
                "depthMeters length (${depthMeters.size}) must match source pixelCount (${source.pixelCount})"
            }
        }

        val (finalR, finalG, finalB) = renderOpticalLinearRgb(
            source = source,
            dstWidth = geometry.width,
            dstHeight = geometry.height,
            profile = profile,
            ibis = ibis,
            depthMeters = depthMeters,
            planarSubjectDistanceM = planarSubjectDistanceM,
            samplingMode = samplingMode,
            borderMode = borderMode,
            workspace = workspace,
            pupilMask = pupilMask,
            lensHook = lensHook,
        )

        val effTrans = (profile.transmission * filterTransmittance.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        return CmosNoiseModel.mosaicAndReadout(
            red = finalR,
            green = finalG,
            blue = finalB,
            geometry = geometry,
            exposure = exposure,
            config = noiseConfig,
            destination = destination,
            fNumber = profile.fNumber,
            transmission = effTrans,
            frameIndex = frameIndex,
            parallelThreads = parallelThreads,
        )
    }

    /**
     * Executes only the pre-Bayer optical stages into [workspace] and returns direct references to
     * the workspace's `(finalR, finalG, finalB)` planes with zero array copies.
     *
     * Used by [LensOpticalSceneFilter]'s Stage-1 optical cache so AE exposure/ISO updates only
     * re-run [CmosNoiseModel.mosaicAndReadout] (~1 ms) instead of repeating warp + CoC bokeh.
     */
    internal fun renderOpticalLinearRgb(
        source: LinearRgbImage,
        dstWidth: Int,
        dstHeight: Int,
        profile: OpticalLensProfile = OpticalLensProfile.IDENTITY,
        ibis: IbisShiftState = IbisShiftState.ZERO,
        depthMeters: FloatArray? = null,
        planarSubjectDistanceM: Double? = null,
        samplingMode: SamplingMode = SamplingMode.BILINEAR,
        borderMode: BorderMode = BorderMode.BLACK,
        workspace: OpticalFrameWorkspace = OpticalFrameWorkspace(),
        pupilMask: OpticalPupilMask? = null,
        lensHook: LensOpticalHook? = null,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        require(dstWidth >= 1 && dstHeight >= 1) {
            "Destination dimensions must be positive: ${dstWidth}x$dstHeight"
        }
        if (depthMeters != null) {
            require(depthMeters.size == source.pixelCount) {
                "depthMeters length (${depthMeters.size}) must match source pixelCount (${source.pixelCount})"
            }
        }
        val dstPixels = Math.multiplyExact(dstWidth, dstHeight)
        workspace.ensureOpticalCapacity(dstPixels)
        val planarCocRadiusPx = if (depthMeters == null && planarSubjectDistanceM != null && maxBokehRadiusPx > 0) {
            profile.circleOfConfusionRadiusPixels(planarSubjectDistanceM, dstWidth).coerceAtMost(maxBokehRadiusPx.toDouble())
        } else {
            0.0
        }
        val usePlanarBlur = planarCocRadiusPx > MIN_BOKEH_RADIUS_PX
        // A flat source at a known distance still images a point through the selected pupil.
        val useDepthBokeh = maxBokehRadiusPx > 0 && (depthMeters != null ||
            (planarSubjectDistanceM != null && lensHook != null) || (usePlanarBlur && pupilMask?.active == true))
        val useContrastDrop = profile.effectiveContrastDrop() > MIN_EFFECT_EPSILON
        if (useDepthBokeh || usePlanarBlur || useContrastDrop) {
            workspace.ensureBokehCapacity(dstPixels)
        }

        runOpticalWarpPass(
            source = source,
            depthMeters = depthMeters,
            dstW = dstWidth,
            dstH = dstHeight,
            profile = profile,
            ibis = ibis,
            samplingMode = samplingMode,
            borderMode = borderMode,
            outR = workspace.opticalR,
            outG = workspace.opticalG,
            outB = workspace.opticalB,
            outDepth = if (depthMeters != null && useDepthBokeh) workspace.warpedDepth else null,
            lensHook = lensHook,
        )
        if (depthMeters == null && useDepthBokeh && planarSubjectDistanceM != null) {
            workspace.warpedDepth.fill(planarSubjectDistanceM.toFloat(), 0, dstPixels)
        }

        return runPostWarpOpticalStages(
            dstW = dstWidth,
            dstH = dstHeight,
            profile = profile,
            useDepthBokeh = useDepthBokeh,
            usePlanarBlur = usePlanarBlur,
            planarCocRadiusPx = planarCocRadiusPx,
            workspace = workspace,
            pupilMask = pupilMask,
            lensHook = lensHook,
        )
    }

    /**
     * Executes only the pre-Bayer optical stages (IBIS, Brown-Conrady distortion, zoom, lateral CA,
     * vignetting, image-circle cutoff, optional depth-buffer or planar CoC defocus with shaped bokeh
     * & LoCA, wide-open spherical contrast drop, and highlight flare/ghosting) and returns a fresh
     * planar [LinearRgbImage].
     */
    fun renderLinearRgb(
        source: LinearRgbImage,
        dstWidth: Int,
        dstHeight: Int,
        profile: OpticalLensProfile = OpticalLensProfile.IDENTITY,
        ibis: IbisShiftState = IbisShiftState.ZERO,
        depthMeters: FloatArray? = null,
        planarSubjectDistanceM: Double? = null,
        samplingMode: SamplingMode = SamplingMode.BILINEAR,
        borderMode: BorderMode = BorderMode.BLACK,
        workspace: OpticalFrameWorkspace = OpticalFrameWorkspace(),
        pupilMask: OpticalPupilMask? = null,
        lensHook: LensOpticalHook? = null,
    ): LinearRgbImage {
        val dstPixels = Math.multiplyExact(dstWidth, dstHeight)
        val (srcR, srcG, srcB) = renderOpticalLinearRgb(
            source = source,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            profile = profile,
            ibis = ibis,
            depthMeters = depthMeters,
            planarSubjectDistanceM = planarSubjectDistanceM,
            samplingMode = samplingMode,
            borderMode = borderMode,
            workspace = workspace,
            pupilMask = pupilMask,
            lensHook = lensHook,
        )

        return LinearRgbImage(
            width = dstWidth,
            height = dstHeight,
            red = srcR.copyOf(dstPixels),
            green = srcG.copyOf(dstPixels),
            blue = srcB.copyOf(dstPixels),
        )
    }

    private fun runPostWarpOpticalStages(
        dstW: Int,
        dstH: Int,
        profile: OpticalLensProfile,
        useDepthBokeh: Boolean,
        usePlanarBlur: Boolean,
        planarCocRadiusPx: Double,
        workspace: OpticalFrameWorkspace,
        pupilMask: OpticalPupilMask? = null,
        lensHook: LensOpticalHook? = null,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        var curR: FloatArray
        var curG: FloatArray
        var curB: FloatArray
        if (useDepthBokeh) {
            runDepthBokehPass(
                inR = workspace.opticalR,
                inG = workspace.opticalG,
                inB = workspace.opticalB,
                depth = workspace.warpedDepth,
                width = dstW,
                height = dstH,
                profile = profile,
                cocMap = workspace.cocMap,
                outR = workspace.bokehR,
                outG = workspace.bokehG,
                outB = workspace.bokehB,
                workspace = workspace,
                pupilMask = pupilMask,
                lensHook = lensHook,
            )
            curR = workspace.bokehR
            curG = workspace.bokehG
            curB = workspace.bokehB
        } else if (usePlanarBlur) {
            runPlanarDefocusPass(
                inR = workspace.opticalR,
                inG = workspace.opticalG,
                inB = workspace.opticalB,
                width = dstW,
                height = dstH,
                cocRadiusPx = planarCocRadiusPx,
                tmp = workspace.planarTmp,
                colSum = workspace.ensureBlurColSum(dstW),
                outR = workspace.bokehR,
                outG = workspace.bokehG,
                outB = workspace.bokehB,
            )
            curR = workspace.bokehR
            curG = workspace.bokehG
            curB = workspace.bokehB
        } else {
            curR = workspace.opticalR
            curG = workspace.opticalG
            curB = workspace.opticalB
        }

        val contrastDrop = profile.effectiveContrastDrop()
        val ranContrastDrop = contrastDrop > MIN_EFFECT_EPSILON
        if (ranContrastDrop) {
            val outR = if (curR === workspace.opticalR) workspace.bokehR else workspace.opticalR
            val outG = if (curG === workspace.opticalG) workspace.bokehG else workspace.opticalG
            val outB = if (curB === workspace.opticalB) workspace.bokehB else workspace.opticalB
            applyWideOpenContrastDropPass(
                inR = curR,
                inG = curG,
                inB = curB,
                width = dstW,
                height = dstH,
                contrastDrop = contrastDrop,
                tmp = workspace.planarTmp,
                colSum = workspace.ensureBlurColSum(dstW),
                outR = outR,
                outG = outG,
                outB = outB,
            )
            curR = outR
            curG = outG
            curB = outB
        }

        val ranFlare = profile.flareStrength > MIN_EFFECT_EPSILON && applyLensFlarePass(
            r = curR,
            g = curG,
            b = curB,
            width = dstW,
            height = dstH,
            profile = profile,
        )

        // Rear mount baffle / physical image circle strictly blocks stray glow or flare outside imageCircleMm.
        if ((ranContrastDrop || ranFlare) && profile.hasImageCircleFalloff(dstW, dstH)) {
            applyImageCircleBaffleCutoff(curR, curG, curB, dstW, dstH, profile)
        }

        return Triple(curR, curG, curB)
    }

    private fun runOpticalWarpPass(
        source: LinearRgbImage,
        depthMeters: FloatArray?,
        dstW: Int,
        dstH: Int,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        samplingMode: SamplingMode,
        borderMode: BorderMode,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        outDepth: FloatArray?,
        lensHook: LensOpticalHook? = null,
    ) {
        val srcW = source.width
        val srcH = source.height
        val srcR = source.red
        val srcG = source.green
        val srcB = source.blue

        val dstCx = (dstW - 1) * 0.5
        val dstCy = (dstH - 1) * 0.5
        val srcCx = (srcW - 1) * 0.5
        val srcCy = (srcH - 1) * 0.5
        val dstHalfDiag = max(hypot(dstCx, dstCy), 1.0)
        val invDstHalfDiag = 1.0 / dstHalfDiag

        val aspectScale = if (dstW == srcW && dstH == srcH) {
            1.0
        } else {
            val sx = if (dstCx > 0.0) srcCx / dstCx else 1.0
            val sy = if (dstCy > 0.0) srcCy / dstCy else 1.0
            min(sx, sy)
        }
        val normToSrcPx = aspectScale * dstHalfDiag

        val effFocalMm = profile.effectiveFocalLengthMm()
        val focalLengthPx = (effFocalMm / profile.sensorWidthMm) * dstW.toDouble()
        val shiftX = ibis.effectiveShiftXPixels(focalLengthPx)
        val shiftY = ibis.effectiveShiftYPixels(focalLengthPx)
        val cosRoll = cos(ibis.rollRadians)
        val sinRoll = sin(ibis.rollRadians)

        val k1 = profile.k1
        val k2 = profile.k2
        val p1 = profile.p1
        val p2 = profile.p2
        val hasDistortion = k1 != 0.0 || k2 != 0.0 || p1 != 0.0 || p2 != 0.0
        val invZoom = 1.0 / profile.zoomScale()
        val invCaRed = 1.0 / profile.caRedScale
        val invCaBlue = 1.0 / profile.caBlueScale
        val hasLateralCa = profile.caRedScale != 1.0 || profile.caBlueScale != 1.0
        val hasVignetting = profile.cos4Power > 0.0 ||
            profile.mechanicalVignetting > 0.0 ||
            profile.hasImageCircleFalloff(dstW, dstH)
        val useBicubic = samplingMode == SamplingMode.BICUBIC
        val clampBorder = borderMode == BorderMode.CLAMP

        // Precompute vignetting invariants once per frame instead of per pixel.
        val mmPerPx = profile.sensorWidthMm / dstW.toDouble()
        val icRadiusMm = profile.imageCircleMm * 0.5
        val onsetMm = icRadiusMm * OpticalLensProfile.IMAGE_CIRCLE_ONSET_RATIO
        val invTransitionMm = 1.0 / (icRadiusMm - onsetMm)
        val effFSq = effFocalMm * effFocalMm
        val invHalfDiagSq = 1.0 / (dstHalfDiag * dstHalfDiag)
        val srcMaxX = (srcW - 1).toDouble()
        val srcMaxY = (srcH - 1).toDouble()
        val srcMaxXInt = srcW - 1
        val srcMaxYInt = srcH - 1
        val zoomNormToSrcPx = invZoom * normToSrcPx

        OpticalParallelExecutor.parallelStripes(
            totalWork = dstH,
            threads = effectiveThreads(dstW * dstH),
            minChunkSize = 16,
        ) { yStart, yEnd ->
            val hookUv = if (lensHook != null) DoubleArray(2) else null
            var dstIdx = yStart * dstW
            for (y in yStart until yEnd) {
                val dyShift = (y.toDouble() - dstCy) - shiftY
                val dySinRoll = dyShift * sinRoll
                val dyCosRoll = dyShift * cosRoll
                for (x in 0 until dstW) {
                    val dxShift = (x.toDouble() - dstCx) - shiftX

                    // Inverse IBIS roll around optical center.
                    val px = dxShift * cosRoll + dySinRoll
                    val py = -dxShift * sinRoll + dyCosRoll

                    val nx = px * invDstHalfDiag
                    val ny = py * invDstHalfDiag

                    var ux = nx
                    var uy = ny
                    var insideImageCircle = true
                    if (lensHook != null && hookUv != null && lensHook.warpUv(nx, ny, effFocalMm, profile.focusDistanceM, profile.fNumber, hookUv)) {
                        ux = hookUv[0]
                        uy = hookUv[1]
                        if (!ux.isFinite() || !uy.isFinite()) {
                            insideImageCircle = false
                        }
                    } else if (hasDistortion) {
                        val r2 = nx * nx + ny * ny
                        val radialFactor = 1.0 + k1 * r2 + k2 * r2 * r2
                        if (radialFactor <= MIN_RADIAL_FACTOR) {
                            insideImageCircle = false
                        } else {
                            val invRadial = 1.0 / radialFactor
                            val tangX = 2.0 * p1 * nx * ny + p2 * (r2 + 2.0 * nx * nx)
                            val tangY = p1 * (r2 + 2.0 * ny * ny) + 2.0 * p2 * nx * ny
                            ux = nx * invRadial - tangX
                            uy = ny * invRadial - tangY
                        }
                    }

                    if (!insideImageCircle) {
                        outR[dstIdx] = 0f
                        outG[dstIdx] = 0f
                        outB[dstIdx] = 0f
                        if (outDepth != null) outDepth[dstIdx] = 0f
                        dstIdx++
                        continue
                    }

                    val sxG = srcCx + ux * zoomNormToSrcPx
                    val syG = srcCy + uy * zoomNormToSrcPx

                    val rVal: Double
                    val gVal: Double
                    val bVal: Double
                    if (!hasLateralCa && !useBicubic) {
                        if (!clampBorder && (sxG < -EDGE_EPSILON || sxG > srcMaxX + EDGE_EPSILON || syG < -EDGE_EPSILON || syG > srcMaxY + EDGE_EPSILON)) {
                            rVal = 0.0
                            gVal = 0.0
                            bVal = 0.0
                        } else {
                            val cx = sxG.coerceIn(0.0, srcMaxX)
                            val cy = syG.coerceIn(0.0, srcMaxY)
                            val x0 = floor(cx).toInt().coerceIn(0, srcMaxXInt)
                            val y0 = floor(cy).toInt().coerceIn(0, srcMaxYInt)
                            val x1 = if (x0 < srcMaxXInt) x0 + 1 else srcMaxXInt
                            val y1 = if (y0 < srcMaxYInt) y0 + 1 else srcMaxYInt
                            val fx = cx - x0
                            val fy = cy - y0
                            val row0 = y0 * srcW
                            val row1 = y1 * srcW
                            val i00 = row0 + x0
                            val i10 = row0 + x1
                            val i01 = row1 + x0
                            val i11 = row1 + x1

                            val rTop = srcR[i00].toDouble() + (srcR[i10].toDouble() - srcR[i00].toDouble()) * fx
                            val rBot = srcR[i01].toDouble() + (srcR[i11].toDouble() - srcR[i01].toDouble()) * fx
                            rVal = rTop + (rBot - rTop) * fy

                            val gTop = srcG[i00].toDouble() + (srcG[i10].toDouble() - srcG[i00].toDouble()) * fx
                            val gBot = srcG[i01].toDouble() + (srcG[i11].toDouble() - srcG[i01].toDouble()) * fx
                            gVal = gTop + (gBot - gTop) * fy

                            val bTop = srcB[i00].toDouble() + (srcB[i10].toDouble() - srcB[i00].toDouble()) * fx
                            val bBot = srcB[i01].toDouble() + (srcB[i11].toDouble() - srcB[i01].toDouble()) * fx
                            bVal = bTop + (bBot - bTop) * fy
                        }
                    } else if (hasLateralCa) {
                        val zx = ux * invZoom
                        val zy = uy * invZoom
                        gVal = samplePlane(srcG, srcW, srcH, sxG, syG, useBicubic, clampBorder)
                        val sxR = srcCx + (zx * invCaRed) * normToSrcPx
                        val syR = srcCy + (zy * invCaRed) * normToSrcPx
                        val sxB = srcCx + (zx * invCaBlue) * normToSrcPx
                        val syB = srcCy + (zy * invCaBlue) * normToSrcPx
                        rVal = samplePlane(srcR, srcW, srcH, sxR, syR, useBicubic, clampBorder)
                        bVal = samplePlane(srcB, srcW, srcH, sxB, syB, useBicubic, clampBorder)
                    } else {
                        gVal = samplePlane(srcG, srcW, srcH, sxG, syG, true, clampBorder)
                        rVal = samplePlane(srcR, srcW, srcH, sxG, syG, true, clampBorder)
                        bVal = samplePlane(srcB, srcW, srcH, sxG, syG, true, clampBorder)
                    }

                    val vig = if (hasVignetting) {
                        profile.vignettingGainPrecomputed(
                            dxPx = px,
                            dyPx = py,
                            mmPerPx = mmPerPx,
                            icRadiusMm = icRadiusMm,
                            onsetMm = onsetMm,
                            invTransitionMm = invTransitionMm,
                            effFSq = effFSq,
                            invHalfDiagSq = invHalfDiagSq,
                        )
                    } else {
                        1.0
                    }
                    outR[dstIdx] = (rVal * vig).toFloat()
                    outG[dstIdx] = (gVal * vig).toFloat()
                    outB[dstIdx] = (bVal * vig).toFloat()

                    if (outDepth != null && depthMeters != null) {
                        outDepth[dstIdx] = sampleDepthNearest(depthMeters, srcW, srcH, sxG, syG, clampBorder)
                    }
                    dstIdx++
                }
            }
        }
    }

    private fun runPlanarDefocusPass(
        inR: FloatArray,
        inG: FloatArray,
        inB: FloatArray,
        width: Int,
        height: Int,
        cocRadiusPx: Double,
        tmp: FloatArray,
        colSum: DoubleArray,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
    ) {
        val radius = (cocRadiusPx * 0.65).roundToInt().coerceIn(1, max(1, maxBokehRadiusPx))
        val blend = ((cocRadiusPx - MIN_BOKEH_RADIUS_PX) / 0.75).coerceIn(0.0, 1.0).toFloat()
        val passes = if (cocRadiusPx >= 1.25) 2 else 1
        blurPlaneSeparable(inR, tmp, colSum, outR, width, height, radius, passes, blend)
        blurPlaneSeparable(inG, tmp, colSum, outG, width, height, radius, passes, blend)
        blurPlaneSeparable(inB, tmp, colSum, outB, width, height, radius, passes, blend)
    }

    private fun blurPlaneSeparable(
        input: FloatArray,
        tmp: FloatArray,
        colSum: DoubleArray,
        output: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        passes: Int,
        blend: Float,
    ) {
        boxBlurHorizontal(input, tmp, width, height, radius)
        boxBlurVertical(tmp, output, colSum, width, height, radius)
        for (p in 1 until passes) {
            boxBlurHorizontal(output, tmp, width, height, radius)
            boxBlurVertical(tmp, output, colSum, width, height, radius)
        }
        if (blend < 1.0f) {
            val count = width * height
            for (i in 0 until count) {
                val orig = input[i]
                output[i] = orig + (output[i] - orig) * blend
            }
        }
    }

    private fun boxBlurHorizontal(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val invWin = 1.0 / (2 * radius + 1)
        val maxX = width - 1
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 16,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * width
                var sum = src[row].toDouble() * (radius + 1)
                for (k in 1..radius) {
                    sum += src[row + min(k, maxX)].toDouble()
                }
                dst[row] = (sum * invWin).toFloat()
                for (x in 1 until width) {
                    val addIdx = row + min(x + radius, maxX)
                    val subIdx = row + max(x - radius - 1, 0)
                    sum += src[addIdx].toDouble() - src[subIdx].toDouble()
                    dst[row + x] = (sum * invWin).toFloat()
                }
            }
        }
    }

    private fun boxBlurVertical(
        src: FloatArray,
        dst: FloatArray,
        colSum: DoubleArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val invWin = 1.0 / (2 * radius + 1)
        val maxY = height - 1
        val initScale = (radius + 1).toDouble()
        OpticalParallelExecutor.parallelStripes(
            totalWork = width,
            threads = effectiveThreads(width * height),
            minChunkSize = 32,
        ) { xStart, xEnd ->
            for (x in xStart until xEnd) {
                colSum[x] = src[x].toDouble() * initScale
            }
            for (k in 1..radius) {
                val kRow = min(k, maxY) * width
                for (x in xStart until xEnd) {
                    colSum[x] += src[kRow + x].toDouble()
                }
            }
            for (x in xStart until xEnd) {
                dst[x] = (colSum[x] * invWin).toFloat()
            }
            for (y in 1 until height) {
                val addRow = min(y + radius, maxY) * width
                val subRow = max(y - radius - 1, 0) * width
                val dstRow = y * width
                for (x in xStart until xEnd) {
                    val s = colSum[x] + (src[addRow + x].toDouble() - src[subRow + x].toDouble())
                    colSum[x] = s
                    dst[dstRow + x] = (s * invWin).toFloat()
                }
            }
        }
    }

    private fun applyWideOpenContrastDropPass(
        inR: FloatArray,
        inG: FloatArray,
        inB: FloatArray,
        width: Int,
        height: Int,
        contrastDrop: Double,
        tmp: FloatArray,
        colSum: DoubleArray,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
    ) {
        val count = width * height
        if (count <= 0) return
        val radius = (min(width, height) * 0.025).roundToInt().coerceIn(1, 5)
        val glowMix = (contrastDrop * 0.55).coerceIn(0.0, 0.45).toFloat()
        val keepMix = 1.0f - glowMix
        var lumSum = 0.0
        val stride = max(1, count / 1024)
        var samples = 0
        var i = 0
        while (i < count) {
            lumSum += 0.2126 * inR[i] + 0.7152 * inG[i] + 0.0722 * inB[i]
            samples++
            i += stride
        }
        val meanLum = if (samples > 0) (lumSum / samples).toFloat() else 0f
        val veilLift = (contrastDrop * 0.06 * meanLum).toFloat()

        fun blendChannel(src: FloatArray, dst: FloatArray) {
            boxBlurHorizontal(src, tmp, width, height, radius)
            boxBlurVertical(tmp, dst, colSum, width, height, radius)
            for (idx in 0 until count) {
                val v = src[idx] * keepMix + dst[idx] * glowMix + veilLift
                dst[idx] = if (v > 0f) v else 0f
            }
        }
        blendChannel(inR, outR)
        blendChannel(inG, outG)
        blendChannel(inB, outB)
    }

    private fun applyImageCircleBaffleCutoff(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
    ) {
        val cx = (width - 1) * 0.5
        val cy = (height - 1) * 0.5
        val mmPerPx = profile.sensorWidthMm / width.toDouble()
        val icRadiusMm = profile.imageCircleMm * 0.5
        val onsetMm = icRadiusMm * 0.90
        val invTransitionMm = 1.0 / (icRadiusMm - onsetMm).coerceAtLeast(1e-6)
        val onsetSqMm = onsetMm * onsetMm
        val icRadiusSqMm = icRadiusMm * icRadiusMm

        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 16,
        ) { yStart, yEnd ->
            var idx = yStart * width
            for (py in yStart until yEnd) {
                val dyMm = (py - cy) * mmPerPx
                val dySqMm = dyMm * dyMm
                for (px in 0 until width) {
                    val dxMm = (px - cx) * mmPerPx
                    val rSqMm = dxMm * dxMm + dySqMm
                    if (rSqMm >= icRadiusSqMm) {
                        r[idx] = 0f
                        g[idx] = 0f
                        b[idx] = 0f
                    } else if (rSqMm > onsetSqMm) {
                        val t = ((sqrt(rSqMm) - onsetMm) * invTransitionMm).coerceIn(0.0, 1.0)
                        val mask = (1.0 - t * t * (3.0 - 2.0 * t)).toFloat()
                        r[idx] *= mask
                        g[idx] *= mask
                        b[idx] *= mask
                    }
                    idx++
                }
            }
        }
    }

    private fun applyLensFlarePass(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
    ): Boolean {
        val strength = profile.flareStrength
        if (strength <= MIN_EFFECT_EPSILON) return false
        val count = width * height
        if (count <= 0) return false

        // Fast highlight scan: skip completely unless peak radiance exceeds threshold.
        var peakLum = 0.0f
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var hotCount = 0
        val step = if (count > 16384) 2 else 1
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val idx = row + x
                val lum = 0.2126f * r[idx] + 0.7152f * g[idx] + 0.0722f * b[idx]
                if (lum > peakLum) peakLum = lum
                if (lum > FLARE_HIGHLIGHT_THRESHOLD) {
                    val excess = (lum - FLARE_HIGHLIGHT_THRESHOLD).toDouble()
                    sumW += excess
                    sumX += x * excess
                    sumY += y * excess
                    hotCount++
                }
                x += step
            }
            y += step
        }
        if (peakLum <= FLARE_HIGHLIGHT_THRESHOLD || sumW <= 1e-6) return false

        val hx = sumX / sumW
        val hy = sumY / sumW
        val cx = (width - 1) * 0.5
        val cy = (height - 1) * 0.5
        val halfDiag = max(hypot(cx, cy), 1.0)
        // Energy scale bounded smoothly so both small specular suns and bright lamps produce realistic flare.
        val avgExcess = (sumW / max(1, hotCount)).coerceIn(0.05, 4.0)
        val areaScale = sqrt((hotCount.toDouble() * step * step) / (count.toDouble() * 0.015)).coerceIn(0.25, 1.5)
        val flareGain = strength * avgExcess * areaScale

        // Two opposite-axis internal reflection ghosts + wide veiling haze around highlight source.
        val g1x = cx - 0.65 * (hx - cx)
        val g1y = cy - 0.65 * (hy - cy)
        val g1Rad = max(3.0, halfDiag * 0.085)
        val invTwoG1Sq = 1.0 / (2.0 * g1Rad * g1Rad)

        val g2x = cx - 1.25 * (hx - cx)
        val g2y = cy - 1.25 * (hy - cy)
        val g2Rad = max(2.0, halfDiag * 0.050)
        val invTwoG2Sq = 1.0 / (2.0 * g2Rad * g2Rad)

        val veilRad = max(6.0, halfDiag * 0.45)
        val invTwoVeilSq = 1.0 / (2.0 * veilRad * veilRad)

        val tR = (profile.flareTintR * flareGain).toFloat()
        val tG = (profile.flareTintG * flareGain).toFloat()
        val tB = (profile.flareTintB * flareGain).toFloat()

        val g1CutoffSq = g1Rad * g1Rad * 9.0
        val g2CutoffSq = g2Rad * g2Rad * 9.0
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(count),
            minChunkSize = 16,
        ) { yStart, yEnd ->
            var dstIdx = yStart * width
            for (py in yStart until yEnd) {
                val dyH = py - hy
                val dyHSq = dyH * dyH
                val dy1 = py - g1y
                val dy1Sq = dy1 * dy1
                val dy2 = py - g2y
                val dy2Sq = dy2 * dy2
                for (px in 0 until width) {
                    val dxH = px - hx
                    val veil = 0.22 * exp(-(dxH * dxH + dyHSq) * invTwoVeilSq)
                    val dx1 = px - g1x
                    val d1Sq = dx1 * dx1 + dy1Sq
                    val ghost1 = if (d1Sq < g1CutoffSq) 0.34 * exp(-d1Sq * invTwoG1Sq) else 0.0
                    val dx2 = px - g2x
                    val d2Sq = dx2 * dx2 + dy2Sq
                    val ghost2 = if (d2Sq < g2CutoffSq) 0.22 * exp(-d2Sq * invTwoG2Sq) else 0.0
                    val total = (veil + ghost1 + ghost2).toFloat()
                    if (total > 1e-6f) {
                        r[dstIdx] += total * tR
                        g[dstIdx] += total * tG
                        b[dstIdx] += total * tB
                    }
                    dstIdx++
                }
            }
        }
        return true
    }

    private fun runDepthBokehPass(
        inR: FloatArray,
        inG: FloatArray,
        inB: FloatArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
        cocMap: FloatArray,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        workspace: OpticalFrameWorkspace,
        pupilMask: OpticalPupilMask? = null,
        lensHook: LensOpticalHook? = null,
    ) {
        val pixelCount = width * height
        val baseF = profile.effectiveFocalLengthMm()
        val maxR = maxBokehRadiusPx.toFloat()
        val effLoCa = profile.effectiveLongitudinalCaDefocusMm()
        val hasLoCa = abs(effLoCa) > 0.01
        val activeMask = pupilMask?.takeIf { it.active }
        val shaped = profile.hasShapedBokeh() || activeMask != null || lensHook != null
        val polyScaleLut = if (shaped && activeMask == null) buildPolyScaleLutIfNeeded(profile, workspace) else null
        val pupilMaskLut = activeMask?.let { workspace.ensurePupilMaskLut(it, PUPIL_LUT_RADIUS) }

        if (!hasLoCa) {
            computeCocMap(depth, pixelCount, width, height, profile, baseF, maxR, cocMap, lensHook)
            if (shaped) {
                applyShapedDepthBokehAllChannels(
                    inR = inR,
                    inG = inG,
                    inB = inB,
                    depth = depth,
                    cocMap = cocMap,
                    width = width,
                    height = height,
                    profile = profile,
                    polyScaleLut = polyScaleLut,
                    pupilMaskLut = pupilMaskLut,
                    outR = outR,
                    outG = outG,
                    outB = outB,
                    lensHook = lensHook,
                )
            } else {
                applyDepthAwareDiskBlurAllChannels(
                    inR = inR,
                    inG = inG,
                    inB = inB,
                    depth = depth,
                    cocMap = cocMap,
                    width = width,
                    height = height,
                    outR = outR,
                    outG = outG,
                    outB = outB,
                )
            }
            return
        }

        val fR = (baseF + effLoCa).coerceAtLeast(1e-3)
        val fG = baseF
        val fB = (baseF - effLoCa).coerceAtLeast(1e-3)

        computeCocMap(depth, pixelCount, width, height, profile, fR, maxR, cocMap, lensHook)
        if (shaped) {
            applyShapedDepthBokehSingleChannel(inR, depth, cocMap, width, height, profile, polyScaleLut, pupilMaskLut, outR, lensHook)
        } else {
            applyDepthAwareDiskBlurSingleChannel(inR, depth, cocMap, width, height, outR)
        }

        computeCocMap(depth, pixelCount, width, height, profile, fG, maxR, cocMap, lensHook)
        if (shaped) {
            applyShapedDepthBokehSingleChannel(inG, depth, cocMap, width, height, profile, polyScaleLut, pupilMaskLut, outG, lensHook)
        } else {
            applyDepthAwareDiskBlurSingleChannel(inG, depth, cocMap, width, height, outG)
        }

        computeCocMap(depth, pixelCount, width, height, profile, fB, maxR, cocMap, lensHook)
        if (shaped) {
            applyShapedDepthBokehSingleChannel(inB, depth, cocMap, width, height, profile, polyScaleLut, pupilMaskLut, outB, lensHook)
        } else {
            applyDepthAwareDiskBlurSingleChannel(inB, depth, cocMap, width, height, outB)
        }
    }

    private fun buildPolyScaleLutIfNeeded(
        profile: OpticalLensProfile,
        workspace: OpticalFrameWorkspace,
    ): FloatArray? {
        val polyStrength = profile.effectiveBladePolygonStrength()
        if (polyStrength <= MIN_EFFECT_EPSILON || maxBokehRadiusPx > DIST_LUT_RADIUS) return null
        val lut = workspace.ensurePolyScaleLut(DIST_LUT_SIZE)
        val sector = (2.0 * Math.PI) / profile.apertureBladeCount.toDouble()
        val halfSector = sector * 0.5
        val cosHalfSector = cos(halfSector)
        val oneMinusPoly = 1.0 - polyStrength
        for (i in 0 until DIST_LUT_SIZE) {
            if (DIST_LUT_FLOAT[i] <= 1e-6f) {
                lut[i] = 1.0f
            } else {
                var mod = ATAN2_LUT[i] % sector
                if (mod < 0.0) mod += sector
                val localAngle = mod - halfSector
                val polyScale = cosHalfSector / cos(localAngle).coerceAtLeast(0.1)
                lut[i] = (oneMinusPoly + polyStrength * polyScale).toFloat()
            }
        }
        return lut
    }

    private fun computeCocMap(
        depth: FloatArray,
        pixelCount: Int,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
        focalLengthMm: Double,
        maxR: Float,
        cocMap: FloatArray,
        lensHook: LensOpticalHook? = null,
    ) {
        val fM = focalLengthMm.coerceAtLeast(1e-3) * 0.001
        val s1 = profile.focusDistanceM
        val pxPerMmRadius = (width.toDouble() / profile.sensorWidthMm) * 0.5

        if (lensHook == null) {
            val denom = (s1 - fM).coerceAtLeast(1e-6)
            val cocScale = ((fM * fM / profile.fNumber) * 1000.0 / denom) * pxPerMmRadius
            val infCoc = (((fM * fM) / (profile.fNumber * denom)) * 1000.0 * pxPerMmRadius).toFloat().coerceIn(0f, maxR)

            OpticalParallelExecutor.parallelStripes(
                totalWork = pixelCount,
                threads = effectiveThreads(pixelCount),
                minChunkSize = 4096,
            ) { start, end ->
                for (i in start until end) {
                    val d = depth[i].toDouble()
                    cocMap[i] = when {
                        d > 0.0 && d.isFinite() -> (abs(d - s1) / d * cocScale).toFloat().coerceIn(0f, maxR)
                        d == Double.POSITIVE_INFINITY -> infCoc
                        else -> 0f
                    }
                }
            }
            return
        }

        val halfW = (width - 1) * 0.5
        val halfH = (height - 1) * 0.5
        val invHalfDiag = 1.0 / max(hypot(halfW, halfH), 1.0)
        val fSqOverFNumMm = (fM * fM / profile.fNumber) * 1000.0 * pxPerMmRadius
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(pixelCount),
            minChunkSize = 16,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val ny = (y - halfH) * invHalfDiag
                val rowOff = y * width
                for (x in 0 until width) {
                    val i = rowOff + x
                    val d = depth[i].toDouble()
                    if (d <= 0.0 || d.isNaN()) {
                        cocMap[i] = 0f
                        continue
                    }
                    val nx = (x - halfW) * invHalfDiag
                    val localS1 = lensHook.effectiveFocusDistanceAt(nx, ny, s1).coerceIn(fM + 1e-4, 1e9)
                    val localDenom = (localS1 - fM).coerceAtLeast(1e-6)
                    val localCocScale = fSqOverFNumMm / localDenom
                    cocMap[i] = if (d.isFinite()) {
                        (abs(d - localS1) / d * localCocScale).toFloat().coerceIn(0f, maxR)
                    } else {
                        localCocScale.toFloat().coerceIn(0f, maxR)
                    }
                }
            }
        }
    }

    private fun applyDepthAwareDiskBlurAllChannels(
        inR: FloatArray,
        inG: FloatArray,
        inB: FloatArray,
        depth: FloatArray,
        cocMap: FloatArray,
        width: Int,
        height: Int,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
    ) {
        val distLut = DIST_LUT_FLOAT
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 8,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val centerIdx = rowOffset + x
                    val r = cocMap[centerIdx]
                    if (r < MIN_BOKEH_RADIUS_PX) {
                        outR[centerIdx] = inR[centerIdx]
                        outG[centerIdx] = inG[centerIdx]
                        outB[centerIdx] = inB[centerIdx]
                        continue
                    }
                    val centerDepth = depth[centerIdx]
                    val radInt = ceil(r.toDouble()).toInt()
                    val yMin = max(0, y - radInt)
                    val yMax = min(height - 1, y + radInt)
                    val outerLimit = r + 0.5f
                    val outerLimitSq = outerLimit * outerLimit
                    val useLut = radInt <= DIST_LUT_RADIUS

                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f
                    var sumW = 0f
                    for (ny in yMin..yMax) {
                        val dy = ny - y
                        val dySq = (dy * dy).toFloat()
                        val remSq = outerLimitSq - dySq
                        if (remSq < 0f) continue
                        val maxDx = floor(sqrt(remSq.toDouble())).toInt()
                        val xMin = max(0, x - maxDx)
                        val xMax = min(width - 1, x + maxDx)
                        val nRow = ny * width
                        if (useLut) {
                            val lutRow = (dy + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x
                            for (nx in xMin..xMax) {
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val dist = distLut[lutRow + nx]
                                // Exclude sharp foreground pixels from bleeding into background CoC disks.
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = outerLimit - dist
                                if (rawW >= 1f) {
                                    sumR += inR[nIdx]
                                    sumG += inG[nIdx]
                                    sumB += inB[nIdx]
                                    sumW += 1f
                                } else if (rawW > 0f) {
                                    sumR += inR[nIdx] * rawW
                                    sumG += inG[nIdx] * rawW
                                    sumB += inB[nIdx] * rawW
                                    sumW += rawW
                                }
                            }
                        } else {
                            for (nx in xMin..xMax) {
                                val dx = (nx - x).toFloat()
                                val distSq = dx * dx + dySq
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val dist = sqrt(distSq.toDouble()).toFloat()
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = outerLimit - dist
                                if (rawW >= 1f) {
                                    sumR += inR[nIdx]
                                    sumG += inG[nIdx]
                                    sumB += inB[nIdx]
                                    sumW += 1f
                                } else if (rawW > 0f) {
                                    sumR += inR[nIdx] * rawW
                                    sumG += inG[nIdx] * rawW
                                    sumB += inB[nIdx] * rawW
                                    sumW += rawW
                                }
                            }
                        }
                    }
                    if (sumW > 0f) {
                        val invW = 1f / sumW
                        outR[centerIdx] = sumR * invW
                        outG[centerIdx] = sumG * invW
                        outB[centerIdx] = sumB * invW
                    } else {
                        outR[centerIdx] = inR[centerIdx]
                        outG[centerIdx] = inG[centerIdx]
                        outB[centerIdx] = inB[centerIdx]
                    }
                }
            }
        }
    }

    private fun applyDepthAwareDiskBlurSingleChannel(
        input: FloatArray,
        depth: FloatArray,
        cocMap: FloatArray,
        width: Int,
        height: Int,
        output: FloatArray,
    ) {
        val distLut = DIST_LUT_FLOAT
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 8,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val centerIdx = rowOffset + x
                    val r = cocMap[centerIdx]
                    if (r < MIN_BOKEH_RADIUS_PX) {
                        output[centerIdx] = input[centerIdx]
                        continue
                    }
                    val centerDepth = depth[centerIdx]
                    val radInt = ceil(r.toDouble()).toInt()
                    val yMin = max(0, y - radInt)
                    val yMax = min(height - 1, y + radInt)
                    val outerLimit = r + 0.5f
                    val outerLimitSq = outerLimit * outerLimit
                    val useLut = radInt <= DIST_LUT_RADIUS

                    var sum = 0f
                    var sumW = 0f
                    for (ny in yMin..yMax) {
                        val dy = ny - y
                        val dySq = (dy * dy).toFloat()
                        val remSq = outerLimitSq - dySq
                        if (remSq < 0f) continue
                        val maxDx = floor(sqrt(remSq.toDouble())).toInt()
                        val xMin = max(0, x - maxDx)
                        val xMax = min(width - 1, x + maxDx)
                        val nRow = ny * width
                        if (useLut) {
                            val lutRow = (dy + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x
                            for (nx in xMin..xMax) {
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val dist = distLut[lutRow + nx]
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = outerLimit - dist
                                if (rawW >= 1f) {
                                    sum += input[nIdx]
                                    sumW += 1f
                                } else if (rawW > 0f) {
                                    sum += input[nIdx] * rawW
                                    sumW += rawW
                                }
                            }
                        } else {
                            for (nx in xMin..xMax) {
                                val dx = (nx - x).toFloat()
                                val distSq = dx * dx + dySq
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val dist = sqrt(distSq.toDouble()).toFloat()
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = outerLimit - dist
                                if (rawW >= 1f) {
                                    sum += input[nIdx]
                                    sumW += 1f
                                } else if (rawW > 0f) {
                                    sum += input[nIdx] * rawW
                                    sumW += rawW
                                }
                            }
                        }
                    }
                    output[centerIdx] = if (sumW > 0f) (sum / sumW) else input[centerIdx]
                }
            }
        }
    }

    private fun shapedBokehKernelWeight(
        dx: Double,
        dy: Double,
        dist: Double,
        r: Double,
        polyStrength: Double,
        polyScaleOverride: Double,
        sector: Double,
        halfSector: Double,
        cosHalfSector: Double,
        catsShiftX: Double,
        catsShiftY: Double,
        hasCatsEye: Boolean,
        onionRing: Double,
        rimHighlight: Double,
        pupilMaskLut: FloatArray? = null,
        cxNorm: Double = 0.0,
        cyNorm: Double = 0.0,
        fNumber: Double = 4.0,
        lensHook: LensOpticalHook? = null,
    ): Double {
        var w = if (pupilMaskLut != null) {
            val invR = PUPIL_LUT_RADIUS.toDouble() / max(r, 1e-4)
            val lx = (PUPIL_LUT_RADIUS.toDouble() - dx * invR + 0.5).toInt()
            val ly = (PUPIL_LUT_RADIUS.toDouble() - dy * invR + 0.5).toInt()
            if (lx !in 0 until PUPIL_LUT_STRIDE || ly !in 0 until PUPIL_LUT_STRIDE) 0.0
            else pupilMaskLut[ly * PUPIL_LUT_STRIDE + lx].toDouble()
        } else {
            val edgeLimit = if (polyScaleOverride >= 0.0) {
                r * polyScaleOverride
            } else if (polyStrength > MIN_EFFECT_EPSILON && dist > 1e-6) {
                val theta = atan2(dy, dx)
                var mod = theta % sector
                if (mod < 0.0) mod += sector
                val localAngle = mod - halfSector
                val polyScale = cosHalfSector / cos(localAngle).coerceAtLeast(0.1)
                r * ((1.0 - polyStrength) + polyStrength * polyScale)
            } else {
                r
            }
            (edgeLimit + 0.5 - dist).coerceIn(0.0, 1.0)
        }
        if (w <= 0.0) return 0.0

        if (lensHook != null) {
            val invR = 1.0 / max(r, 1e-4)
            w *= lensHook.pupilWeight(-dx * invR, -dy * invR, cxNorm, cyNorm, fNumber).coerceIn(0.0, 1.0)
            if (w <= 0.0) return 0.0
        }

        if (hasCatsEye) {
            val cdx = dx + catsShiftX
            val cdy = dy + catsShiftY
            val cDist = sqrt(cdx * cdx + cdy * cdy)
            val catW = (r + 0.5 - cDist).coerceIn(0.0, 1.0)
            w *= catW
            if (w <= 0.0) return 0.0
        }

        val u = (dist / max(r, 1e-4)).coerceIn(0.0, 1.0)
        if (rimHighlight > MIN_EFFECT_EPSILON) {
            w *= (1.0 - 0.35 * rimHighlight + rimHighlight * 1.45 * u * u * u)
        }
        if (onionRing > MIN_EFFECT_EPSILON) {
            w *= (1.0 + onionRing * 0.55 * cos(u * Math.PI * 6.0)).coerceAtLeast(0.15)
        }
        return w
    }

    private fun applyShapedDepthBokehAllChannels(
        inR: FloatArray,
        inG: FloatArray,
        inB: FloatArray,
        depth: FloatArray,
        cocMap: FloatArray,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
        polyScaleLut: FloatArray?,
        pupilMaskLut: FloatArray?,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        lensHook: LensOpticalHook? = null,
    ) {
        val catsEyeEff = profile.effectiveCatsEyeStrength()
        val onionRing = profile.bokehOnionRing
        val rimHighlight = profile.bokehRimHighlight
        // Fast pure-Float LUT path for aperture pupil bokeh masks (STAR_5 / HEART / HEXAGON / RING)
        // with zero trig calls and ~70% early skip on opaque cutout regions.
        if (pupilMaskLut != null && lensHook == null && catsEyeEff <= MIN_EFFECT_EPSILON && onionRing <= MIN_EFFECT_EPSILON && rimHighlight <= MIN_EFFECT_EPSILON) {
            val distLut = DIST_LUT_FLOAT
            OpticalParallelExecutor.parallelStripes(
                totalWork = height,
                threads = effectiveThreads(width * height),
                minChunkSize = 8,
            ) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val rowOffset = y * width
                    for (x in 0 until width) {
                        val centerIdx = rowOffset + x
                        val r = cocMap[centerIdx]
                        if (r < MIN_BOKEH_RADIUS_PX) {
                            outR[centerIdx] = inR[centerIdx]
                            outG[centerIdx] = inG[centerIdx]
                            outB[centerIdx] = inB[centerIdx]
                            continue
                        }
                        val centerDepth = depth[centerIdx]
                        val radInt = ceil(r.toDouble()).toInt()
                        val yMin = max(0, y - radInt)
                        val yMax = min(height - 1, y + radInt)
                        val outerLimit = r + 0.5f
                        val outerLimitSq = outerLimit * outerLimit
                        val useLut = radInt <= DIST_LUT_RADIUS
                        val invR = PUPIL_LUT_RADIUS.toFloat() / max(r, 1e-4f)

                        var sumR = 0f
                        var sumG = 0f
                        var sumB = 0f
                        var sumW = 0f
                        for (ny in yMin..yMax) {
                            val dyInt = ny - y
                            val ly = (PUPIL_LUT_RADIUS + 0.5f - dyInt * invR).toInt()
                            if (ly !in 0 until PUPIL_LUT_STRIDE) continue
                            val dySq = (dyInt * dyInt).toFloat()
                            val remSq = outerLimitSq - dySq
                            if (remSq < 0f) continue
                            val maxDx = floor(sqrt(remSq.toDouble())).toInt()
                            val xMin = max(0, x - maxDx)
                            val xMax = min(width - 1, x + maxDx)
                            val nRow = ny * width
                            val pLutRow = ly * PUPIL_LUT_STRIDE
                            val dLutRow = if (useLut) (dyInt + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x else 0
                            for (nx in xMin..xMax) {
                                val lx = (PUPIL_LUT_RADIUS + 0.5f - (nx - x) * invR).toInt()
                                if (lx !in 0 until PUPIL_LUT_STRIDE) continue
                                val w = pupilMaskLut[pLutRow + lx]
                                if (w <= 0f) continue
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                if (nDepth < centerDepth) {
                                    val dist = if (useLut) distLut[dLutRow + nx] else sqrt(((nx - x) * (nx - x)).toDouble() + dySq).toFloat()
                                    if (cocMap[nIdx] + 0.5f < dist) continue
                                }
                                sumR += inR[nIdx] * w
                                sumG += inG[nIdx] * w
                                sumB += inB[nIdx] * w
                                sumW += w
                            }
                        }
                        if (sumW > 0f) {
                            val invW = 1f / sumW
                            outR[centerIdx] = sumR * invW
                            outG[centerIdx] = sumG * invW
                            outB[centerIdx] = sumB * invW
                        } else {
                            outR[centerIdx] = inR[centerIdx]
                            outG[centerIdx] = inG[centerIdx]
                            outB[centerIdx] = inB[centerIdx]
                        }
                    }
                }
            }
            return
        }

        val halfW = (width - 1) * 0.5
        val halfH = (height - 1) * 0.5
        val invHalfDiag = 1.0 / max(hypot(halfW, halfH), 1.0)
        val polyStrength = profile.effectiveBladePolygonStrength()
        val sector = (2.0 * Math.PI) / profile.apertureBladeCount.toDouble()
        val halfSector = sector * 0.5
        val cosHalfSector = cos(halfSector)
        val hasOnion = onionRing > MIN_EFFECT_EPSILON
        val hasRim = rimHighlight > MIN_EFFECT_EPSILON
        val distLut = DIST_LUT_FLOAT

        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 8,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOffset = y * width
                val cyNorm = (y - halfH) * invHalfDiag
                for (x in 0 until width) {
                    val centerIdx = rowOffset + x
                    val r = cocMap[centerIdx]
                    if (r < MIN_BOKEH_RADIUS_PX) {
                        outR[centerIdx] = inR[centerIdx]
                        outG[centerIdx] = inG[centerIdx]
                        outB[centerIdx] = inB[centerIdx]
                        continue
                    }
                    val rD = r.toDouble()
                    val centerDepth = depth[centerIdx]
                    val radInt = ceil(rD).toInt()
                    val yMin = max(0, y - radInt)
                    val yMax = min(height - 1, y + radInt)
                    val outerLimit = rD + 0.5
                    val outerLimitSq = outerLimit * outerLimit
                    val useLut = radInt <= DIST_LUT_RADIUS

                    val cxNorm = (x - halfW) * invHalfDiag
                    val rNorm = sqrt(cxNorm * cxNorm + cyNorm * cyNorm).coerceIn(0.0, 1.0)
                    val hasCatsEye = catsEyeEff > MIN_EFFECT_EPSILON && rNorm > 0.05
                    val catsShiftX: Double
                    val catsShiftY: Double
                    if (hasCatsEye) {
                        val shift = catsEyeEff * rNorm * 0.72 * rD
                        catsShiftX = (cxNorm / rNorm) * shift
                        catsShiftY = (cyNorm / rNorm) * shift
                    } else {
                        catsShiftX = 0.0
                        catsShiftY = 0.0
                    }

                    // Lossless fast path for polygonal-blade-only shaped bokeh (no cat's-eye / rim / onion):
                    // avoids per-neighbor function dispatch, Double conversions, sqrt(), and cos().
                    if (useLut && polyScaleLut != null && lensHook == null && !hasCatsEye && !hasRim && !hasOnion) {
                        var sumRf = 0f
                        var sumGf = 0f
                        var sumBf = 0f
                        var sumWf = 0f
                        val outerLimitF = r + 0.5f
                        val outerLimitSqF = outerLimitF * outerLimitF
                        for (ny in yMin..yMax) {
                            val dyInt = ny - y
                            val remSqF = outerLimitSqF - (dyInt * dyInt).toFloat()
                            if (remSqF < 0f) continue
                            val maxDx = floor(sqrt(remSqF.toDouble())).toInt()
                            val xMin = max(0, x - maxDx)
                            val xMax = min(width - 1, x + maxDx)
                            val nRow = ny * width
                            val lutRow = (dyInt + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x
                            for (nx in xMin..xMax) {
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val lutIdx = lutRow + nx
                                val dist = distLut[lutIdx]
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = r * polyScaleLut[lutIdx] + 0.5f - dist
                                if (rawW >= 1f) {
                                    sumRf += inR[nIdx]
                                    sumGf += inG[nIdx]
                                    sumBf += inB[nIdx]
                                    sumWf += 1f
                                } else if (rawW > 0f) {
                                    sumRf += inR[nIdx] * rawW
                                    sumGf += inG[nIdx] * rawW
                                    sumBf += inB[nIdx] * rawW
                                    sumWf += rawW
                                }
                            }
                        }
                        if (sumWf > 0f) {
                            val invW = 1f / sumWf
                            outR[centerIdx] = sumRf * invW
                            outG[centerIdx] = sumGf * invW
                            outB[centerIdx] = sumBf * invW
                        } else {
                            outR[centerIdx] = inR[centerIdx]
                            outG[centerIdx] = inG[centerIdx]
                            outB[centerIdx] = inB[centerIdx]
                        }
                        continue
                    }

                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    var sumW = 0.0
                    for (ny in yMin..yMax) {
                        val dyInt = ny - y
                        val dy = dyInt.toDouble()
                        val dySq = dy * dy
                        val remSq = outerLimitSq - dySq
                        if (remSq < 0.0) continue
                        val maxDx = floor(sqrt(remSq)).toInt()
                        val xMin = max(0, x - maxDx)
                        val xMax = min(width - 1, x + maxDx)
                        val nRow = ny * width
                        val lutRow = if (useLut) (dyInt + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x else 0
                        for (nx in xMin..xMax) {
                            val nIdx = nRow + nx
                            val nDepth = depth[nIdx]
                            if (nDepth <= 0f) continue
                            val dx = (nx - x).toDouble()
                            val dist = if (useLut) distLut[lutRow + nx].toDouble() else sqrt(dx * dx + dySq)
                            if (nDepth < centerDepth && cocMap[nIdx] + 0.5 < dist) continue
                            val polyOverride = if (useLut && polyScaleLut != null) polyScaleLut[lutRow + nx].toDouble() else -1.0
                            val w = shapedBokehKernelWeight(
                                dx = dx,
                                dy = dy,
                                dist = dist,
                                r = rD,
                                polyStrength = polyStrength,
                                polyScaleOverride = polyOverride,
                                sector = sector,
                                halfSector = halfSector,
                                cosHalfSector = cosHalfSector,
                                catsShiftX = catsShiftX,
                                catsShiftY = catsShiftY,
                                hasCatsEye = hasCatsEye,
                                onionRing = onionRing,
                                rimHighlight = rimHighlight,
                                pupilMaskLut = pupilMaskLut,
                                cxNorm = cxNorm,
                                cyNorm = cyNorm,
                                fNumber = profile.fNumber,
                                lensHook = lensHook,
                            )
                            if (w <= 0.0) continue
                            sumR += inR[nIdx] * w
                            sumG += inG[nIdx] * w
                            sumB += inB[nIdx] * w
                            sumW += w
                        }
                    }
                    if (sumW > 0.0) {
                        val invW = 1.0 / sumW
                        outR[centerIdx] = (sumR * invW).toFloat()
                        outG[centerIdx] = (sumG * invW).toFloat()
                        outB[centerIdx] = (sumB * invW).toFloat()
                    } else {
                        outR[centerIdx] = inR[centerIdx]
                        outG[centerIdx] = inG[centerIdx]
                        outB[centerIdx] = inB[centerIdx]
                    }
                }
            }
        }
    }

    private fun applyShapedDepthBokehSingleChannel(
        input: FloatArray,
        depth: FloatArray,
        cocMap: FloatArray,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
        polyScaleLut: FloatArray?,
        pupilMaskLut: FloatArray?,
        output: FloatArray,
        lensHook: LensOpticalHook? = null,
    ) {
        val halfW = (width - 1) * 0.5
        val halfH = (height - 1) * 0.5
        val invHalfDiag = 1.0 / max(hypot(halfW, halfH), 1.0)
        val polyStrength = profile.effectiveBladePolygonStrength()
        val sector = (2.0 * Math.PI) / profile.apertureBladeCount.toDouble()
        val halfSector = sector * 0.5
        val cosHalfSector = cos(halfSector)
        val catsEyeEff = profile.effectiveCatsEyeStrength()
        val onionRing = profile.bokehOnionRing
        val rimHighlight = profile.bokehRimHighlight
        val hasOnion = onionRing > MIN_EFFECT_EPSILON
        val hasRim = rimHighlight > MIN_EFFECT_EPSILON
        val distLut = DIST_LUT_FLOAT

        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = effectiveThreads(width * height),
            minChunkSize = 8,
        ) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOffset = y * width
                val cyNorm = (y - halfH) * invHalfDiag
                for (x in 0 until width) {
                    val centerIdx = rowOffset + x
                    val r = cocMap[centerIdx]
                    if (r < MIN_BOKEH_RADIUS_PX) {
                        output[centerIdx] = input[centerIdx]
                        continue
                    }
                    val rD = r.toDouble()
                    val centerDepth = depth[centerIdx]
                    val radInt = ceil(rD).toInt()
                    val yMin = max(0, y - radInt)
                    val yMax = min(height - 1, y + radInt)
                    val outerLimit = rD + 0.5
                    val outerLimitSq = outerLimit * outerLimit
                    val useLut = radInt <= DIST_LUT_RADIUS

                    val cxNorm = (x - halfW) * invHalfDiag
                    val rNorm = sqrt(cxNorm * cxNorm + cyNorm * cyNorm).coerceIn(0.0, 1.0)
                    val hasCatsEye = catsEyeEff > MIN_EFFECT_EPSILON && rNorm > 0.05
                    val catsShiftX: Double
                    val catsShiftY: Double
                    if (hasCatsEye) {
                        val shift = catsEyeEff * rNorm * 0.72 * rD
                        catsShiftX = (cxNorm / rNorm) * shift
                        catsShiftY = (cyNorm / rNorm) * shift
                    } else {
                        catsShiftX = 0.0
                        catsShiftY = 0.0
                    }

                    if (useLut && polyScaleLut != null && lensHook == null && !hasCatsEye && !hasRim && !hasOnion) {
                        var sumF = 0f
                        var sumWf = 0f
                        val outerLimitF = r + 0.5f
                        val outerLimitSqF = outerLimitF * outerLimitF
                        for (ny in yMin..yMax) {
                            val dyInt = ny - y
                            val remSqF = outerLimitSqF - (dyInt * dyInt).toFloat()
                            if (remSqF < 0f) continue
                            val maxDx = floor(sqrt(remSqF.toDouble())).toInt()
                            val xMin = max(0, x - maxDx)
                            val xMax = min(width - 1, x + maxDx)
                            val nRow = ny * width
                            val lutRow = (dyInt + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x
                            for (nx in xMin..xMax) {
                                val nIdx = nRow + nx
                                val nDepth = depth[nIdx]
                                if (nDepth <= 0f) continue
                                val lutIdx = lutRow + nx
                                val dist = distLut[lutIdx]
                                if (nDepth < centerDepth && cocMap[nIdx] + 0.5f < dist) continue
                                val rawW = r * polyScaleLut[lutIdx] + 0.5f - dist
                                if (rawW >= 1f) {
                                    sumF += input[nIdx]
                                    sumWf += 1f
                                } else if (rawW > 0f) {
                                    sumF += input[nIdx] * rawW
                                    sumWf += rawW
                                }
                            }
                        }
                        output[centerIdx] = if (sumWf > 0f) (sumF / sumWf) else input[centerIdx]
                        continue
                    }

                    var sum = 0.0
                    var sumW = 0.0
                    for (ny in yMin..yMax) {
                        val dyInt = ny - y
                        val dy = dyInt.toDouble()
                        val dySq = dy * dy
                        val remSq = outerLimitSq - dySq
                        if (remSq < 0.0) continue
                        val maxDx = floor(sqrt(remSq)).toInt()
                        val xMin = max(0, x - maxDx)
                        val xMax = min(width - 1, x + maxDx)
                        val nRow = ny * width
                        val lutRow = if (useLut) (dyInt + DIST_LUT_RADIUS) * DIST_LUT_STRIDE + DIST_LUT_RADIUS - x else 0
                        for (nx in xMin..xMax) {
                            val nIdx = nRow + nx
                            val nDepth = depth[nIdx]
                            if (nDepth <= 0f) continue
                            val dx = (nx - x).toDouble()
                            val dist = if (useLut) distLut[lutRow + nx].toDouble() else sqrt(dx * dx + dySq)
                            if (nDepth < centerDepth && cocMap[nIdx] + 0.5 < dist) continue
                            val polyOverride = if (useLut && polyScaleLut != null) polyScaleLut[lutRow + nx].toDouble() else -1.0
                            val w = shapedBokehKernelWeight(
                                dx = dx,
                                dy = dy,
                                dist = dist,
                                r = rD,
                                polyStrength = polyStrength,
                                polyScaleOverride = polyOverride,
                                sector = sector,
                                halfSector = halfSector,
                                cosHalfSector = cosHalfSector,
                                catsShiftX = catsShiftX,
                                catsShiftY = catsShiftY,
                                hasCatsEye = hasCatsEye,
                                onionRing = onionRing,
                                rimHighlight = rimHighlight,
                                pupilMaskLut = pupilMaskLut,
                                cxNorm = cxNorm,
                                cyNorm = cyNorm,
                                fNumber = profile.fNumber,
                                lensHook = lensHook,
                            )
                            if (w <= 0.0) continue
                            sum += input[nIdx] * w
                            sumW += w
                        }
                    }
                    output[centerIdx] = if (sumW > 0.0) (sum / sumW).toFloat() else input[centerIdx]
                }
            }
        }
    }

    private fun samplePlane(
        plane: FloatArray,
        width: Int,
        height: Int,
        sx: Double,
        sy: Double,
        useBicubic: Boolean,
        clampBorder: Boolean,
    ): Double {
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        val x: Double
        val y: Double
        if (clampBorder) {
            x = sx.coerceIn(0.0, maxX)
            y = sy.coerceIn(0.0, maxY)
        } else {
            if (sx < -EDGE_EPSILON || sx > maxX + EDGE_EPSILON || sy < -EDGE_EPSILON || sy > maxY + EDGE_EPSILON) {
                return 0.0
            }
            x = sx.coerceIn(0.0, maxX)
            y = sy.coerceIn(0.0, maxY)
        }
        return if (useBicubic) {
            sampleBicubic(plane, width, height, x, y)
        } else {
            sampleBilinear(plane, width, height, x, y)
        }
    }

    private fun sampleBilinear(
        plane: FloatArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Double {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0

        val row0 = y0 * width
        val row1 = y1 * width
        val c00 = plane[row0 + x0].toDouble()
        val c10 = plane[row0 + x1].toDouble()
        val c01 = plane[row1 + x0].toDouble()
        val c11 = plane[row1 + x1].toDouble()

        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return top + (bottom - top) * fy
    }

    private fun sampleBicubic(
        plane: FloatArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy

        val wx0 = catmullRomWeight(1.0 + fx)
        val wx1 = catmullRomWeight(fx)
        val wx2 = catmullRomWeight(1.0 - fx)
        val wx3 = catmullRomWeight(2.0 - fx)

        val wy0 = catmullRomWeight(1.0 + fy)
        val wy1 = catmullRomWeight(fy)
        val wy2 = catmullRomWeight(1.0 - fy)
        val wy3 = catmullRomWeight(2.0 - fy)

        val maxX = width - 1
        val maxY = height - 1
        val cx0 = (ix - 1).coerceIn(0, maxX)
        val cx1 = ix.coerceIn(0, maxX)
        val cx2 = (ix + 1).coerceIn(0, maxX)
        val cx3 = (ix + 2).coerceIn(0, maxX)

        val r0 = (iy - 1).coerceIn(0, maxY) * width
        val r1 = iy.coerceIn(0, maxY) * width
        val r2 = (iy + 1).coerceIn(0, maxY) * width
        val r3 = (iy + 2).coerceIn(0, maxY) * width

        val v0 = plane[r0 + cx0] * wx0 + plane[r0 + cx1] * wx1 + plane[r0 + cx2] * wx2 + plane[r0 + cx3] * wx3
        val v1 = plane[r1 + cx0] * wx0 + plane[r1 + cx1] * wx1 + plane[r1 + cx2] * wx2 + plane[r1 + cx3] * wx3
        val v2 = plane[r2 + cx0] * wx0 + plane[r2 + cx1] * wx1 + plane[r2 + cx2] * wx2 + plane[r2 + cx3] * wx3
        val v3 = plane[r3 + cx0] * wx0 + plane[r3 + cx1] * wx1 + plane[r3 + cx2] * wx2 + plane[r3 + cx3] * wx3

        return max(0.0, v0 * wy0 + v1 * wy1 + v2 * wy2 + v3 * wy3)
    }

    private fun sampleDepthNearest(
        depth: FloatArray,
        width: Int,
        height: Int,
        sx: Double,
        sy: Double,
        clampBorder: Boolean,
    ): Float {
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        if (!clampBorder && (sx < -EDGE_EPSILON || sx > maxX + EDGE_EPSILON || sy < -EDGE_EPSILON || sy > maxY + EDGE_EPSILON)) {
            return 0f
        }
        val ix = floor(sx + 0.5).toInt().coerceIn(0, width - 1)
        val iy = floor(sy + 0.5).toInt().coerceIn(0, height - 1)
        return depth[iy * width + ix]
    }

    private fun catmullRomWeight(tRaw: Double): Double {
        val t = abs(tRaw)
        return when {
            t <= 1.0 -> (1.5 * t - 2.5) * t * t + 1.0
            t < 2.0 -> ((-0.5 * t + 2.5) * t - 4.0) * t + 2.0
            else -> 0.0
        }
    }

    companion object {
        const val DEFAULT_MAX_BOKEH_RADIUS_PX = 28
        private const val PUPIL_LUT_RADIUS = 32
        private const val PUPIL_LUT_STRIDE = PUPIL_LUT_RADIUS * 2 + 1
        private const val PARALLEL_PIXEL_THRESHOLD = 16_384
        private const val DIST_LUT_RADIUS = 32
        private const val DIST_LUT_STRIDE = DIST_LUT_RADIUS * 2 + 1
        private const val DIST_LUT_SIZE = DIST_LUT_STRIDE * DIST_LUT_STRIDE
        private val DIST_LUT_FLOAT: FloatArray = FloatArray(DIST_LUT_SIZE) { i ->
            val dy = (i / DIST_LUT_STRIDE) - DIST_LUT_RADIUS
            val dx = (i % DIST_LUT_STRIDE) - DIST_LUT_RADIUS
            sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        private val ATAN2_LUT: DoubleArray = DoubleArray(DIST_LUT_SIZE) { i ->
            val dy = (i / DIST_LUT_STRIDE) - DIST_LUT_RADIUS
            val dx = (i % DIST_LUT_STRIDE) - DIST_LUT_RADIUS
            atan2(dy.toDouble(), dx.toDouble())
        }
        private const val MIN_RADIAL_FACTOR = 1e-4
        private const val MIN_BOKEH_RADIUS_PX = 0.5f
        private const val MIN_EFFECT_EPSILON = 1e-4
        private const val FLARE_HIGHLIGHT_THRESHOLD = 0.85f
        private const val EDGE_EPSILON = 1e-6
    }
}
