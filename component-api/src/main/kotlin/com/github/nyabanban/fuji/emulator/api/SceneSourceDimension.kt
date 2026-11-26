package com.github.nyabanban.fuji.emulator.api

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Spatial dimensionality contract of a CMOS [SceneSource].
 *
 * - [DIM_2D]: Planar radiance input (JPEG/PNG stills, FFmpeg video/webcam/RTSP pipes, desktop
 *   screen/window captures, or flat stream ingest) optionally situated at a single
 *   [SceneSource2D.virtualSubjectDistanceM].
 * - [DIM_3D]: Metric 3D scene input providing per-pixel linear RGB radiance paired with a metric
 *   `depthMeters` buffer and reacting to 6-DoF [CameraPose3D] camera orientation, translation,
 *   and IBIS stage telemetry.
 */
enum class SceneDimension {
    DIM_2D,
    DIM_3D,
}

/**
 * 6-DoF physical camera pose and live IBIS sensor-stage offset consumed by [SceneSource3D]
 * backends to couple real camera tilt, roll, yaw, hand-shake, and translation into 3D optical
 * synthesis.
 *
 * Coordinate convention (right-handed camera world frame):
 * - `+X`: camera right (metres)
 * - `+Y`: camera up (metres)
 * - `+Z`: optical axis forward into the scene (metres)
 * - [rollDeg]: clockwise roll around `+Z` in degrees (`+90.0` = portrait CW)
 * - [pitchDeg]: pitch nose-up around `+X` in degrees (`+25.0` = tilted upward)
 * - [yawDeg]: yaw right around `+Y` in degrees
 * - [ibisShiftXUm], [ibisShiftYUm], [ibisRollDeg]: live IBIS sensor-stage compensation telemetry
 */
data class CameraPose3D(
    val posXM: Double = 0.0,
    val posYM: Double = 0.0,
    val posZM: Double = 0.0,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
    val ibisShiftXUm: Double = 0.0,
    val ibisShiftYUm: Double = 0.0,
    val ibisRollDeg: Double = 0.0,
    val focalLengthMm: Double = DEFAULT_FOCAL_LENGTH_MM,
) {
    init {
        require(
            posXM.isFinite() && posYM.isFinite() && posZM.isFinite() &&
                rollDeg.isFinite() && pitchDeg.isFinite() && yawDeg.isFinite() &&
                ibisShiftXUm.isFinite() && ibisShiftYUm.isFinite() && ibisRollDeg.isFinite() &&
                focalLengthMm.isFinite() && focalLengthMm > 0.0,
        ) {
            "CameraPose3D components must be finite with positive focalLengthMm: $this"
        }
    }

    /** True when all 6-DoF pose and IBIS components are zero and focal length is canonical (45 mm). */
    val isIdentity: Boolean
        get() = posXM == 0.0 &&
            posYM == 0.0 &&
            posZM == 0.0 &&
            rollDeg == 0.0 &&
            pitchDeg == 0.0 &&
            yawDeg == 0.0 &&
            ibisShiftXUm == 0.0 &&
            ibisShiftYUm == 0.0 &&
            ibisRollDeg == 0.0 &&
            abs(focalLengthMm - DEFAULT_FOCAL_LENGTH_MM) <= VIEW_EPSILON_FOCAL_MM

    /** True when world-space camera orientation, translation, or optical focal length differs from canonical origin. */
    val hasViewTransform: Boolean
        get() = abs(posXM) > VIEW_EPSILON_M ||
            abs(posYM) > VIEW_EPSILON_M ||
            abs(posZM) > VIEW_EPSILON_M ||
            abs(rollDeg) > VIEW_EPSILON_DEG ||
            abs(pitchDeg) > VIEW_EPSILON_DEG ||
            abs(yawDeg) > VIEW_EPSILON_DEG ||
            abs(focalLengthMm - DEFAULT_FOCAL_LENGTH_MM) > VIEW_EPSILON_FOCAL_MM

    /**
     * Quantized 64-bit cache key for pose-dependent 3D scene rendering (`0.05 deg` angular
     * quantization, `0.5 mm` translation quantization, `0.1 mm` focal length quantization) so
     * sub-threshold jitter reuses cached frames while genuine tilt/roll/parallax/FoV changes
     * immediately re-render.
     */
    fun quantizedViewKey(): Long {
        var h = 1125899906842597L
        fun mix(v: Long) {
            h = (h xor v) * 0x9E3779B97F4A7C15UL.toLong()
        }
        mix((posXM * 2000.0).roundToLong())
        mix((posYM * 2000.0).roundToLong())
        mix((posZM * 2000.0).roundToLong())
        mix((rollDeg * 20.0).roundToLong())
        mix((pitchDeg * 20.0).roundToLong())
        mix((yawDeg * 20.0).roundToLong())
        mix((focalLengthMm * 10.0).roundToLong())
        return h xor (h ushr 31)
    }

    /** Full quantized hash including IBIS sensor-stage telemetry. */
    fun quantizedFullKey(): Long {
        var h = quantizedViewKey()
        fun mix(v: Long) {
            h = (h xor v) * 0xBF58476D1CE4E5B9UL.toLong()
        }
        mix((ibisShiftXUm * 4.0).roundToLong())
        mix((ibisShiftYUm * 4.0).roundToLong())
        mix((ibisRollDeg * 20.0).roundToLong())
        return h xor (h ushr 31)
    }

    companion object {
        private const val VIEW_EPSILON_M = 1e-5
        private const val VIEW_EPSILON_DEG = 1e-4
        private const val VIEW_EPSILON_FOCAL_MM = 1e-3

        /** Canonical GFX studio reference focal length (`45.0 mm`, matching default `gf45-100`). */
        const val DEFAULT_FOCAL_LENGTH_MM = 45.0

        /** Residual angular tremor fraction when 5-axis IBIS is active and unlocked (~90% cancellation). */
        const val IBIS_ACTIVE_RESIDUAL_FACTOR = 0.10

        /** Canonical level landscape pose at world origin with zero IBIS offset. */
        val IDENTITY = CameraPose3D()

        /**
         * Samples a [CameraPose3D] from optional board-level [CameraOrientationPort] and
         * [IbisShiftPort] telemetry at virtual [tick], plus optional active optical [focalLengthMm].
         *
         * When [ibis] is active (`CONTINUOUS` or `SHOOTING_ONLY`) and unlocked (`!stageLocked`),
         * 5-axis IBIS cancels ~90% of handheld angular tremor (`handShakeDeltaDeg`), whereas
         * `MODE_OFF`, `MODE_LOCK`, locked stage, or absent IBIS passes through 100% of shake.
         *
         * Returns [IDENTITY] when orientation is disabled/absent, IBIS shift is zero, and focal
         * length matches [DEFAULT_FOCAL_LENGTH_MM], preserving zero-overhead canonical rendering.
         */
        fun fromOrientationAndIbis(
            orientation: CameraOrientationPort?,
            ibis: IbisShiftPort?,
            tick: Long,
            focalLengthMm: Double = DEFAULT_FOCAL_LENGTH_MM,
        ): CameraPose3D {
            val activeOrientation = orientation?.takeIf { it.accelEnabled }
            val effectiveFocalMm = if (focalLengthMm.isFinite() && focalLengthMm > 0.0) {
                focalLengthMm
            } else {
                DEFAULT_FOCAL_LENGTH_MM
            }
            val ibisActive = ibis != null &&
                !ibis.stageLocked &&
                (ibis.ibisMode == IbisShiftPort.MODE_CONTINUOUS || ibis.ibisMode == IbisShiftPort.MODE_SHOOTING_ONLY)
            val shakeResidualFactor = if (ibisActive) IBIS_ACTIVE_RESIDUAL_FACTOR else 1.0
            val roll: Double
            val pitch: Double
            val yaw: Double
            if (activeOrientation != null) {
                val (shakeRoll, shakePitch, shakeYaw) = activeOrientation.handShakeDeltaDeg(tick)
                roll = activeOrientation.rollDeg + shakeRoll * shakeResidualFactor
                pitch = activeOrientation.pitchDeg + shakePitch * shakeResidualFactor
                yaw = shakeYaw * shakeResidualFactor
            } else {
                roll = 0.0
                pitch = 0.0
                yaw = 0.0
            }
            val ibisX = ibis?.shiftXUm ?: 0.0
            val ibisY = ibis?.shiftYUm ?: 0.0
            val ibisR = ibis?.rollDeg ?: 0.0
            if (
                roll == 0.0 && pitch == 0.0 && yaw == 0.0 &&
                ibisX == 0.0 && ibisY == 0.0 && ibisR == 0.0 &&
                abs(effectiveFocalMm - DEFAULT_FOCAL_LENGTH_MM) <= VIEW_EPSILON_FOCAL_MM
            ) {
                return IDENTITY
            }
            return CameraPose3D(
                rollDeg = roll,
                pitchDeg = pitch,
                yawDeg = yaw,
                ibisShiftXUm = ibisX,
                ibisShiftYUm = ibisY,
                ibisRollDeg = ibisR,
                focalLengthMm = effectiveFocalMm,
            )
        }
    }
}

/**
 * Planar linear-radiance RGB frame produced by a [SceneSource2D] or [SceneSource3D] backend.
 *
 * Arrays are caller/source-owned flat primitives (`width * height`) enabling zero-copy handoff
 * into [OpticalSceneFilter] without per-frame allocations.
 */
class LinearRadianceFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    val depthMeters: FloatArray? = null,
    val dimension: SceneDimension = if (depthMeters != null) SceneDimension.DIM_3D else SceneDimension.DIM_2D,
    val virtualSubjectDistanceM: Double? = null,
    val pose: CameraPose3D = CameraPose3D.IDENTITY,
    val sequence: Long = 0L,
) {
    init {
        require(width >= 1 && height >= 1) { "Radiance frame dimensions must be positive: ${width}x$height" }
        val expected = Math.multiplyExact(width, height)
        require(red.size == expected && green.size == expected && blue.size == expected) {
            "Planar RGB buffers must each have length $expected (got R=${red.size}, G=${green.size}, B=${blue.size})"
        }
        if (depthMeters != null) {
            require(depthMeters.size == expected) {
                "depthMeters length (${depthMeters.size}) must match pixelCount ($expected)"
            }
        }
        if (virtualSubjectDistanceM != null) {
            require(virtualSubjectDistanceM.isFinite() && virtualSubjectDistanceM > 0.0) {
                "virtualSubjectDistanceM must be positive finite, got $virtualSubjectDistanceM"
            }
        }
    }

    val pixelCount: Int get() = width * height
}

/**
 * Explicit 2D planar CMOS [SceneSource] contract (stills, video pipes, desktop/window captures,
 * and flat live stream ingest).
 *
 * Exposes calibrated planar linear RGB radiance alongside an optional [virtualSubjectDistanceM]
 * allowing thin-lens focus defocus to be evaluated consistently on 2D media without fabricating
 * synthetic depth maps.
 */
interface SceneSource2D : SceneSource {
    val dimension: SceneDimension get() = SceneDimension.DIM_2D

    /**
     * Optional virtual distance in metres from the lens to this planar 2D surface (`null` defers
     * to the active lens filter's configured `planarSubjectDistanceM`, if any).
     */
    val virtualSubjectDistanceM: Double? get() = null

    /** Returns the latest calibrated planar linear-RGB frame from this 2D source. */
    fun samplePlanarRadiance(): LinearRadianceFrame
}

/**
 * Explicit 3D metric CMOS [SceneSource] contract providing per-pixel linear RGB radiance paired
 * with metric `depthMeters` and 6-DoF [CameraPose3D] orientation/translation/IBIS coupling.
 */
interface SceneSource3D : SceneSource {
    val dimension: SceneDimension get() = SceneDimension.DIM_3D

    /** Most recent [CameraPose3D] rendered by this 3D scene source. */
    val currentPose: CameraPose3D

    /**
     * Renders (or returns a pose-cached) calibrated metric RGB+Depth [LinearRadianceFrame] from
     * camera pose [pose]. Implementations must always provide non-null `depthMeters`.
     */
    fun renderForPose(pose: CameraPose3D): LinearRadianceFrame

    /**
     * Optional lightweight GPU-render descriptor for [pose] allowing a Vulkan-capable
     * [OpticalSceneFilter] to render this 3D scene straight into `DEVICE_LOCAL` VRAM without
     * running CPU rasterization or transferring 28+ MB/frame over PCIe. Returns `null` when
     * GPU 3D rendering is not supported by this source.
     */
    fun gpuScene3dRenderSpec(pose: CameraPose3D): GpuScene3dRenderSpec? = null
}
