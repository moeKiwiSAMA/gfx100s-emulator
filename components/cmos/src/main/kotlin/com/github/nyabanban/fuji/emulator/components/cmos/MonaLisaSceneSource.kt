package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource3D
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Animated 3D multi-depth Mona Lisa portrait scene (`scene://mona-lisa`) for continuous
 * real-silicon Face & Eye AF tracking (`0xFFF7C000` + firmware CPU Eye-AF).
 *
 * Loads the translucent portrait and uninterrupted landscape plates once. It places the seated
 * figure at 1.60–2.30 m and the Tuscan valley at 4.50 m, and renders smooth
 * 3D motion (horizontal pan/sway, subtle figure-8 vertical bob, gentle `+-5 deg` roll tilt,
 * and continuous Z-depth breathing with physical perspective scaling) into a zero-allocation
 * double-buffered [LinearRgbFrame] ring.
 *
 * Implements [LiveStreamSceneSource] when `motion=on` (default) so [CmosFactory] offloads
 * optical lens DoF filtering to [CmosFactory.AsyncOpticsWorker] without stalling the ARM loop.
 *
 * Supported URI query parameters:
 * - `motion=on|off` (default: `on`)
 * - `phase=<0.0..1.0>` (optional initial phase; freezes at this phase when `motion=off`)
 * - `period=<seconds>` (full oscillation cycle duration in seconds, default: `8.0`)
 */
class MonaLisaSceneSource internal constructor(
    val width: Int = ProceduralOpticsSceneSource.DEFAULT_WIDTH,
    val height: Int = ProceduralOpticsSceneSource.DEFAULT_HEIGHT,
    private val loop: Boolean = true,
    val motionEnabled: Boolean = true,
    private val initialPhase: Double = 0.0,
    val periodSeconds: Double = DEFAULT_PERIOD_SECONDS,
    private val nanoClock: () -> Long = System::nanoTime,
    override val activeSourceRef: String = OpticalScenePreset.MONA_LISA.uri,
) : SceneSource3D, LiveStreamSceneSource {

    internal data class Pose(
        val phase: Double,
        val panXNorm: Float,
        val panYNorm: Float,
        val rollDeg: Float,
        val subjectDepthM: Float,
        val scale: Float,
        val focalScale: Float = 1.0f,
    )

    private val assets: PreparedPortrait = loadPreparedPortrait()
    private val startNanos: Long = nanoClock()

    // 2-slot alternating ring buffer guarantees distinct FloatArray identities for
    // LensOpticalSceneFilter's identity cache while doing zero per-frame allocations.
    private val slotFrames: Array<LinearRgbFrame> = Array(2) {
        LinearRgbFrame(
            width = width,
            height = height,
            red = FloatArray(width * height),
            green = FloatArray(width * height),
            blue = FloatArray(width * height),
            depthMeters = FloatArray(width * height),
        )
    }

    @Volatile
    private var activeSlot: Int = 0

    @Volatile
    private var frameSeq: Long = 0L

    @Volatile
    private var lastRenderNanos: Long = Long.MIN_VALUE

    @Volatile
    private var lastRenderedPhase: Double = Double.NaN

    @Volatile
    private var lastCameraPoseKey: Long = CameraPose3D.IDENTITY.quantizedViewKey()

    @Volatile
    override var currentPose: CameraPose3D = CameraPose3D.IDENTITY
        private set

    @Volatile
    private var closed: Boolean = false

    var exhausted: Boolean = false
        private set

    // Single-slot packed Bayer cache for direct radiance() calls (when optical filter is disabled).
    private var cachedSeq: Long = -1L
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedBitDepth: Int = -1
    private var cachedBayer: com.github.nyabanban.fuji.emulator.api.BayerPattern? = null
    private var cachedGainBits: Long = 0L
    private var cachedBytes: ByteArray? = null
    private var gpuSpecSeq: Long = 1L

    init {
        renderPhaseLocked(initialPhase)
        lastRenderNanos = startNanos
    }

    override val sourceWidth: Int get() = width
    override val sourceHeight: Int get() = height
    override val hasDepthBuffer: Boolean get() = true
    override val lastSourceError: String? get() = null
    override val framesReceived: Long get() = max(1L, frameSeq)
    override val framesDropped: Long get() = 0L
    override val latestFrameSequence: Long
        get() {
            if (!motionEnabled) return 0L
            if (closed) return frameSeq
            // O(1) non-blocking sequence advance so emulation-owner never runs compositeScene() synchronously.
            // Actual 3D compositing happens in renderForPose() / latestLinearRgbFrame() on AsyncOpticsWorker.
            val elapsedSteps = max(0L, nanoClock() - startNanos) / MIN_UPDATE_INTERVAL_NANOS
            return max(frameSeq, elapsedSteps + 1L)
        }

    internal fun currentPose(): Pose = computePose(currentPhase())

    internal fun renderAtPhase(phase: Double): LinearRgbFrame {
        check(!closed) { "Mona Lisa scene source is closed" }
        synchronized(this) {
            renderPhaseLocked(phase)
            return slotFrames[activeSlot]
        }
    }

    private fun currentPhase(): Double {
        if (!motionEnabled) return wrapPhase(initialPhase)
        val elapsedSec = max(0L, nanoClock() - startNanos) / 1_000_000_000.0
        return wrapPhase(initialPhase + elapsedSec / periodSeconds)
    }

    private fun maybeAdvanceMotion() {
        val now = nanoClock()
        val prev = lastRenderNanos
        if (prev != Long.MIN_VALUE && (now - prev) < MIN_UPDATE_INTERVAL_NANOS) return
        synchronized(this) {
            if (closed) return
            val cur = nanoClock()
            if (lastRenderNanos != Long.MIN_VALUE && (cur - lastRenderNanos) < MIN_UPDATE_INTERVAL_NANOS) return
            val elapsedSec = max(0L, cur - startNanos) / 1_000_000_000.0
            val phase = wrapPhase(initialPhase + elapsedSec / periodSeconds)
            renderPhaseLocked(phase)
            lastRenderNanos = cur
        }
    }

    override fun renderForPose(pose: CameraPose3D): LinearRadianceFrame {
        check(!closed) { "Mona Lisa scene source is closed" }
        synchronized(this) {
            currentPose = pose
            val phase = if (motionEnabled) {
                val now = nanoClock()
                if (lastRenderNanos == Long.MIN_VALUE || (now - lastRenderNanos) >= MIN_UPDATE_INTERVAL_NANOS) {
                    lastRenderNanos = now
                    wrapPhase(initialPhase + max(0L, now - startNanos) / 1_000_000_000.0 / periodSeconds)
                } else {
                    if (lastRenderedPhase.isNaN()) wrapPhase(initialPhase) else lastRenderedPhase
                }
            } else {
                wrapPhase(initialPhase)
            }
            renderPhaseLocked(phase, pose)
            return slotFrames[activeSlot].toRadianceFrame(
                dimension = SceneDimension.DIM_3D,
                pose = pose,
                sequence = frameSeq,
            )
        }
    }

    override fun gpuScene3dRenderSpec(pose: CameraPose3D): GpuScene3dRenderSpec.MonaLisa {
        check(!closed) { "Mona Lisa scene source is closed" }
        synchronized(this) {
            currentPose = pose
            val phase = if (motionEnabled) {
                val now = nanoClock()
                wrapPhase(initialPhase + max(0L, now - startNanos) / 1_000_000_000.0 / periodSeconds)
            } else {
                wrapPhase(initialPhase)
            }
            val basePose = computePose(phase)
            val effectivePose = if (!pose.hasViewTransform) {
                basePose
            } else {
                val depthM = (basePose.subjectDepthM - pose.posZM.toFloat()).coerceIn(0.60f, 8.0f)
                val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).toFloat().coerceIn(0.45f, 3.0f)
                val scale = ((NOMINAL_SUBJECT_DEPTH_M / depthM) * focalScale).coerceIn(0.35f, 3.5f)
                basePose.copy(
                    panXNorm = (basePose.panXNorm - (pose.yawDeg / 45.0).toFloat() - (pose.posXM / depthM).toFloat()).coerceIn(-0.45f, 0.45f),
                    panYNorm = (basePose.panYNorm + (pose.pitchDeg / 35.0).toFloat() + (pose.posYM / depthM).toFloat()).coerceIn(-0.35f, 0.35f),
                    rollDeg = (basePose.rollDeg - pose.rollDeg.toFloat()),
                    subjectDepthM = depthM,
                    scale = scale,
                    focalScale = focalScale,
                )
            }
            val sw = assets.w
            val sh = assets.h
            val swF = (sw - 1).toFloat()
            val shF = (sh - 1).toFloat()
            val bgShiftPx = effectivePose.panXNorm * sw * 0.14f
            val bgScaleX = swF / max(1, width - 1).toFloat()
            val bgScaleY = shF / max(1, height - 1).toFloat()
            val rad = Math.toRadians(effectivePose.rollDeg.toDouble())
            val cosR = cos(rad).toFloat()
            val sinR = sin(rad).toFloat()
            val invScale = 1.0f / effectivePose.scale
            val baseAspectFit = height / (sh * VISIBLE_HEIGHT)
            val invFitScale = invScale / baseAspectFit
            val invBgZoom = 1.0f / effectivePose.focalScale.coerceAtLeast(0.45f)

            val seq = ++gpuSpecSeq
            val phaseBits = (phase * 100_000.0).roundToInt().toLong()
            val sceneKey = ((pose.quantizedViewKey() * 0x9E3779B97F4A7C15UL.toLong()) xor
                (phaseBits * 0xBF58476D1CE4E5B9UL.toLong()) xor PORTRAIT_GPU_KEY).let { if (it == 0L) 1L else it }

            return GpuScene3dRenderSpec.MonaLisa(
                width = width,
                height = height,
                pose = pose,
                sceneKey = sceneKey,
                sequence = seq,
                portraitKey = PORTRAIT_GPU_KEY,
                srcWidth = sw,
                srcHeight = sh,
                fgRgbaProvider = { assets.packedFgRgba },
                bgRgbDepthOffsetProvider = { assets.packedBgRgbDepthOffset },
                bgShiftPx = bgShiftPx,
                bgScaleX = bgScaleX,
                bgScaleY = bgScaleY,
                invBgZoom = invBgZoom,
                bgCx = swF * 0.5f,
                bgCy = shF * 0.5f,
                dstPivotX = width * (0.50f + effectivePose.panXNorm),
                dstPivotY = height * (0.46f + effectivePose.panYNorm),
                srcPivotX = sw * PIVOT_X,
                srcPivotY = sh * PIVOT_Y,
                stepXX = cosR * invFitScale,
                stepXY = sinR * invFitScale,
                stepYX = -sinR * invFitScale,
                stepYY = cosR * invFitScale,
                subjectDepthM = effectivePose.subjectDepthM,
                backdropDepthM = BACKDROP_DEPTH_M,
            )
        }
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame {
        check(!closed) { "Mona Lisa scene source is closed" }
        if (motionEnabled) {
            maybeAdvanceMotion()
        }
        return slotFrames[activeSlot]
    }

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed) { "Mona Lisa scene source is closed" }
        check(!exhausted) { "Mona Lisa scene source exhausted" }
        StaticImageSceneSource.validateExposure(exposure)
        val bytesPerSample = SceneFrameCodec.validateGeometryAndDestination(geometry, destination)
        val requiredBytes = geometry.width * geometry.height * bytesPerSample
        val frame = latestLinearRgbFrame()
        val seq = frameSeq
        val gain = SceneFrameCodec.exposureGain(exposure)
        val gainBits = if (gain == 0.0) 0L else gain.toRawBits()
        val cached = cachedBytes
        val frameBytes = if (
            cached != null &&
            cachedSeq == seq &&
            cachedWidth == geometry.width &&
            cachedHeight == geometry.height &&
            cachedBitDepth == geometry.bitDepth &&
            cachedBayer == geometry.bayer &&
            cachedGainBits == gainBits
        ) {
            cached
        } else {
            val target = if (cached != null && cached.size == requiredBytes) cached else ByteArray(requiredBytes)
            renderLinearToBayer(frame, geometry, gain, bytesPerSample, target)
            cachedSeq = seq
            cachedWidth = geometry.width
            cachedHeight = geometry.height
            cachedBitDepth = geometry.bitDepth
            cachedBayer = geometry.bayer
            cachedGainBits = gainBits
            cachedBytes = target
            target
        }
        destination.put(frameBytes)
        if (!loop) {
            exhausted = true
        }
    }

    override fun close() {
        closed = true
        cachedBytes = null
    }

    private fun renderPhaseLocked(phase: Double, cameraPose: CameraPose3D = currentPose) {
        val wrapped = wrapPhase(phase)
        val camKey = cameraPose.quantizedViewKey()
        if (lastRenderedPhase == wrapped && lastCameraPoseKey == camKey && frameSeq > 0L) return
        val basePose = computePose(wrapped)
        val effectivePose = if (!cameraPose.hasViewTransform) {
            basePose
        } else {
            // Couple 6-DoF camera orientation/translation and optical focal length cleanly into Mona Lisa's 3D portrait view.
            val depthM = (basePose.subjectDepthM - cameraPose.posZM.toFloat()).coerceIn(0.60f, 8.0f)
            val focalScale = (cameraPose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).toFloat().coerceIn(0.45f, 3.0f)
            val scale = ((NOMINAL_SUBJECT_DEPTH_M / depthM) * focalScale).coerceIn(0.35f, 3.5f)
            basePose.copy(
                panXNorm = (basePose.panXNorm - (cameraPose.yawDeg / 45.0).toFloat() - (cameraPose.posXM / depthM).toFloat()).coerceIn(-0.45f, 0.45f),
                panYNorm = (basePose.panYNorm + (cameraPose.pitchDeg / 35.0).toFloat() + (cameraPose.posYM / depthM).toFloat()).coerceIn(-0.35f, 0.35f),
                rollDeg = (basePose.rollDeg - cameraPose.rollDeg.toFloat()),
                subjectDepthM = depthM,
                scale = scale,
                focalScale = focalScale,
            )
        }
        val nextSlot = if (frameSeq == 0L) 0 else (1 - activeSlot)
        val dst = slotFrames[nextSlot]
        compositeScene(assets, effectivePose, dst)
        activeSlot = nextSlot
        lastRenderedPhase = wrapped
        lastCameraPoseKey = camKey
        frameSeq++
    }

    companion object {
        const val RESOURCE_PATH = "scenes/mona-lisa-figure.png"
        private const val LANDSCAPE_PATH = "scenes/mona-lisa-valley.jpg"
        // The lower sixth of the portrait texture is overscan for sway, tilt and focus breathing.
        private const val VISIBLE_HEIGHT = 5f / 6f
        private const val PIVOT_X = 0.485f
        private const val PIVOT_Y = 0.345f
        const val DEFAULT_PERIOD_SECONDS = 8.0
        const val NOMINAL_SUBJECT_DEPTH_M = 1.95f
        const val DEPTH_BREATHE_AMPLITUDE_M = 0.35f
        const val BACKDROP_DEPTH_M = 4.50f
        private const val MIN_UPDATE_INTERVAL_NANOS = 16_666_667L // ~60 Hz smooth pose updates matching LiveView cadence

        @Volatile
        private var cachedPortrait: PreparedPortrait? = null

        private const val PORTRAIT_GPU_KEY: Long = 0x4D4F_4E41_4C49_5302L

        private class PreparedPortrait(val w: Int, val h: Int) {
            val fgR = FloatArray(w * h)
            val fgG = FloatArray(w * h)
            val fgB = FloatArray(w * h)
            val fgAlpha = FloatArray(w * h)
            val fgDepthOffset = FloatArray(w * h)
            val bgR = FloatArray(w * h)
            val bgG = FloatArray(w * h)
            val bgB = FloatArray(w * h)

            val packedFgRgba: FloatArray by lazy {
                val n = w * h
                val out = FloatArray(n * 4)
                var o = 0
                for (i in 0 until n) {
                    out[o] = fgR[i]; out[o + 1] = fgG[i]; out[o + 2] = fgB[i]; out[o + 3] = fgAlpha[i]
                    o += 4
                }
                out
            }
            val packedBgRgbDepthOffset: FloatArray by lazy {
                val n = w * h
                val out = FloatArray(n * 4)
                var o = 0
                for (i in 0 until n) {
                    out[o] = bgR[i]; out[o + 1] = bgG[i]; out[o + 2] = bgB[i]; out[o + 3] = fgDepthOffset[i]
                    o += 4
                }
                out
            }
        }

        internal fun parseAndOpen(
            sourceRef: String,
            width: Int,
            height: Int,
            loop: Boolean,
            nanoClock: () -> Long = System::nanoTime,
        ): MonaLisaSceneSource {
            val query = sourceRef.substringAfter('?', "")
            var motion = true
            var phase = 0.0
            var period = DEFAULT_PERIOD_SECONDS
            if (query.isNotEmpty()) {
                for (part in query.split('&')) {
                    if (part.isEmpty()) continue
                    val eq = part.indexOf('=')
                    require(eq > 0) { "Invalid scene://mona-lisa query parameter: '$part'" }
                    val key = part.substring(0, eq).trim().lowercase()
                    val value = part.substring(eq + 1).trim().lowercase()
                    when (key) {
                        "motion" -> motion = when (value) {
                            "on", "true", "1" -> true
                            "off", "false", "0" -> false
                            else -> throw IllegalArgumentException("Invalid motion value '$value'; expected on or off")
                        }
                        "phase" -> {
                            phase = value.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid phase value '$value'")
                            require(phase.isFinite()) { "Phase must be finite: $value" }
                        }
                        "period" -> {
                            period = value.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid period value '$value'")
                            require(period.isFinite() && period in 0.5..120.0) {
                                "Period must be in [0.5, 120.0] seconds: $value"
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown scene://mona-lisa parameter: '$key'")
                    }
                }
            }
            return MonaLisaSceneSource(
                width = width,
                height = height,
                loop = loop,
                motionEnabled = motion,
                initialPhase = phase,
                periodSeconds = period,
                nanoClock = nanoClock,
                activeSourceRef = sourceRef.trim(),
            )
        }

        internal fun computePose(phase: Double): Pose {
            val theta = 2.0 * PI * wrapPhase(phase)
            // Horizontal sway across central ~55% of frame width while keeping eyes cleanly inside 16:9 LiveView.
            val panX = (0.20 * sin(theta)).toFloat()
            val panY = (0.025 * sin(2.0 * theta)).toFloat()
            val rollDeg = (5.0 * sin(theta + 0.35)).toFloat()
            val depthM = (NOMINAL_SUBJECT_DEPTH_M - DEPTH_BREATHE_AMPLITUDE_M * cos(theta)).toFloat()
            val scale = (NOMINAL_SUBJECT_DEPTH_M / depthM).coerceIn(0.82f, 1.24f)
            return Pose(
                phase = wrapPhase(phase),
                panXNorm = panX,
                panYNorm = panY,
                rollDeg = rollDeg,
                subjectDepthM = depthM,
                scale = scale,
            )
        }

        private fun wrapPhase(phase: Double): Double {
            val m = phase % 1.0
            return if (m < 0.0) m + 1.0 else m
        }

        private fun loadPreparedPortrait(): PreparedPortrait {
            cachedPortrait?.let { return it }
            synchronized(this) {
                cachedPortrait?.let { return it }
                val prepared = preparePortrait(loadPlate(RESOURCE_PATH), loadPlate(LANDSCAPE_PATH))
                cachedPortrait = prepared
                return prepared
            }
        }

        private fun loadPlate(path: String): BufferedImage =
            checkNotNull(MonaLisaSceneSource::class.java.classLoader.getResourceAsStream(path)) {
                "Missing Mona Lisa scene resource: $path"
            }.use { stream -> checkNotNull(ImageIO.read(stream)) { "Cannot decode $path" } }

        private fun preparePortrait(figure: BufferedImage, landscape: BufferedImage): PreparedPortrait {
            val w = figure.width
            val h = figure.height
            require(figure.colorModel.hasAlpha() && landscape.width == w && landscape.height == h)
            val fg = figure.getRGB(0, 0, w, h, null, 0, w)
            val bg = landscape.getRGB(0, 0, w, h, null, 0, w)
            val subject = PreparedPortrait(w, h)
            val radiance = FloatArray(256) { code ->
                val linear = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT[code]
                linear * 0.92f / (1f + linear * 1.15f)
            }
            for (y in 0 until h) {
                val v = y.toFloat() / h
                for (x in 0 until w) {
                    val i = y * w + x
                    val color = fg[i]
                    subject.fgR[i] = radiance[(color ushr 16) and 255]
                    subject.fgG[i] = radiance[(color ushr 8) and 255]
                    subject.fgB[i] = radiance[color and 255]
                    subject.fgAlpha[i] = (color ushr 24) / 255f
                    if (color ushr 24 != 0) {
                        subject.fgDepthOffset[i] = portraitRelief(x.toFloat() / w, v)
                    }
                    val valley = bg[i]
                    subject.bgR[i] = radiance[(valley ushr 16) and 255] * 1.18f + 0.032f
                    subject.bgG[i] = radiance[(valley ushr 8) and 255] * 1.18f + 0.034f
                    subject.bgB[i] = radiance[valley and 255] * 1.18f + 0.036f
                }
            }
            return subject
        }

        // Smooth compact reliefs, registered to the painted face, sleeves and folded hands.
        // Offsets are in metres along the optical axis. The chair and lap sit behind the head.
        private fun portraitRelief(u: Float, v: Float): Float {
            fun mound(x: Float, y: Float, rx: Float, ry: Float): Float {
                val dx = (u - x) / rx
                val dy = (v - y) / ry
                val t = (1f - dx * dx - dy * dy).coerceAtLeast(0f)
                return t * t
            }
            return 0.07f - 0.12f * mound(0.482f, 0.275f, 0.10f, 0.19f) -
                0.045f * mound(0.469f, 0.284f, 0.026f, 0.055f) -
                0.10f * mound(0.410f, 0.714f, 0.14f, 0.10f) -
                0.13f * mound(0.444f, 0.761f, 0.119f, 0.085f) -
                0.07f * mound(0.600f, 0.732f, 0.097f, 0.12f)
        }

        private fun compositeScene(src: PreparedPortrait, pose: Pose, dst: LinearRgbFrame) {
            val dw = dst.width
            val dh = dst.height
            val sw = src.w
            val sh = src.h
            val swF = (sw - 1).toFloat()
            val shF = (sh - 1).toFloat()

            // Subtle inverse parallax shift on deep 4.5m Tuscan backdrop vs 1.95m foreground.
            val bgShiftPx = pose.panXNorm * sw * 0.14f
            val bgScaleX = swF / max(1, dw - 1).toFloat()
            val bgScaleY = shF / max(1, dh - 1).toFloat()

            // Foreground inverse affine transform around the fixed registration point below the eyes.
            val rad = Math.toRadians(pose.rollDeg.toDouble())
            val cosR = cos(rad).toFloat()
            val sinR = sin(rad).toFloat()
            val invScale = 1.0f / pose.scale

            // Frame Mona Lisa naturally in 16:9 (1024x576) so her full head+shoulders fit cleanly
            // with generous horizontal runway for +-20% pan tracking.
            val baseAspectFit = (dh / (sh * VISIBLE_HEIGHT))
            val dstPivotX = dw * (0.50f + pose.panXNorm)
            val dstPivotY = dh * (0.46f + pose.panYNorm)
            val srcPivotX = sw * PIVOT_X
            val srcPivotY = sh * PIVOT_Y

            val outR = dst.red
            val outG = dst.green
            val outB = dst.blue
            val outD = checkNotNull(dst.depthMeters)

            val bgR = src.bgR
            val bgG = src.bgG
            val bgB = src.bgB
            val fgR = src.fgR
            val fgG = src.fgG
            val fgB = src.fgB
            val fgA = src.fgAlpha
            val fgDo = src.fgDepthOffset

            val invFitScale = invScale / baseAspectFit
            val stepXX = cosR * invFitScale
            val stepXY = sinR * invFitScale
            val stepYX = -sinR * invFitScale
            val stepYY = cosR * invFitScale

            // Precompute horizontal backdrop index LUT once per frame (eliminates 590k float->int + coerceIn ops).
            val invBgZoom = 1.0f / pose.focalScale.coerceAtLeast(0.45f)
            val bgCx = swF * 0.5f
            val bgCy = shF * 0.5f
            val bgXMap = IntArray(dw) { x ->
                val centered = (x * bgScaleX - bgCx) * invBgZoom + bgCx - bgShiftPx
                centered.toInt().coerceIn(0, sw - 1)
            }

            // Compute tight destination bounding box of the transformed foreground portrait so pure-backdrop
            // pixels (~55% of the frame) skip affine warp, floor(), and bilinear alpha interpolation completely.
            val fwdScale = pose.scale * baseAspectFit
            val fxx = cosR * fwdScale
            val fxy = -sinR * fwdScale
            val fyx = sinR * fwdScale
            val fyy = cosR * fwdScale
            var fgMinXf = Float.POSITIVE_INFINITY
            var fgMaxXf = Float.NEGATIVE_INFINITY
            var fgMinYf = Float.POSITIVE_INFINITY
            var fgMaxYf = Float.NEGATIVE_INFINITY
            for (c in 0 until 4) {
                val sx = if ((c and 1) == 0) -srcPivotX else (swF - srcPivotX)
                val sy = if ((c and 2) == 0) -srcPivotY else (shF - srcPivotY)
                val dx = dstPivotX + sx * fxx + sy * fxy
                val dy = dstPivotY + sx * fyx + sy * fyy
                if (dx < fgMinXf) fgMinXf = dx
                if (dx > fgMaxXf) fgMaxXf = dx
                if (dy < fgMinYf) fgMinYf = dy
                if (dy > fgMaxYf) fgMaxYf = dy
            }
            val fgMinX = (kotlin.math.floor(fgMinXf).toInt() - 1).coerceIn(0, dw)
            val fgMaxX = (kotlin.math.ceil(fgMaxXf).toInt() + 1).coerceIn(fgMinX, dw)
            val fgMinY = (kotlin.math.floor(fgMinYf).toInt() - 1).coerceIn(0, dh)
            val fgMaxY = (kotlin.math.ceil(fgMaxYf).toInt() + 1).coerceIn(fgMinY, dh)
            val maxSrcU = sw - 1
            val maxSrcV = sh - 1

            for (y in 0 until dh) {
                val bgY = (((y * bgScaleY) - bgCy) * invBgZoom + bgCy).toInt().coerceIn(0, sh - 1)
                val bgRow = bgY * sw
                val rowOff = y * dw

                if (y < fgMinY || y >= fgMaxY) {
                    for (x in 0 until dw) {
                        val idx = rowOff + x
                        val bi = bgRow + bgXMap[x]
                        outR[idx] = bgR[bi]
                        outG[idx] = bgG[bi]
                        outB[idx] = bgB[bi]
                        outD[idx] = BACKDROP_DEPTH_M
                    }
                    continue
                }

                // Pure backdrop left flank
                for (x in 0 until fgMinX) {
                    val idx = rowOff + x
                    val bi = bgRow + bgXMap[x]
                    outR[idx] = bgR[bi]
                    outG[idx] = bgG[bi]
                    outB[idx] = bgB[bi]
                    outD[idx] = BACKDROP_DEPTH_M
                }

                // Foreground portrait bounding span
                val dy = y - dstPivotY
                var u = srcPivotX + dy * stepXY + (fgMinX - dstPivotX) * stepXX
                var v = srcPivotY + dy * stepYY + (fgMinX - dstPivotX) * stepYX
                for (x in fgMinX until fgMaxX) {
                    val idx = rowOff + x
                    val bi = bgRow + bgXMap[x]
                    var r = bgR[bi]
                    var g = bgG[bi]
                    var b = bgB[bi]
                    var d = BACKDROP_DEPTH_M

                    if (u >= 0f && v >= 0f) {
                        val ui = u.toInt()
                        val vi = v.toInt()
                        if (ui < maxSrcU && vi < maxSrcV) {
                            val s00 = vi * sw + ui
                            val s10 = s00 + 1
                            val s01 = s00 + sw
                            val s11 = s01 + 1
                            val a00 = fgA[s00]
                            val a10 = fgA[s10]
                            val a01 = fgA[s01]
                            val a11 = fgA[s11]
                            if (a00 > 0.005f || a10 > 0.005f || a01 > 0.005f || a11 > 0.005f) {
                                val fu = u - ui
                                val fv = v - vi
                                val a0 = a00 + (a10 - a00) * fu
                                val a1 = a01 + (a11 - a01) * fu
                                val alpha = (a0 + (a1 - a0) * fv).coerceIn(0f, 1f)
                                if (alpha > 0.005f) {
                                    val fr = bilerp(fgR[s00], fgR[s10], fgR[s01], fgR[s11], fu, fv)
                                    val fg = bilerp(fgG[s00], fgG[s10], fgG[s01], fgG[s11], fu, fv)
                                    val fb = bilerp(fgB[s00], fgB[s10], fgB[s01], fgB[s11], fu, fv)
                                    val invA = 1.0f - alpha
                                    r = fr * alpha + r * invA
                                    g = fg * alpha + g * invA
                                    b = fb * alpha + b * invA
                                    if (alpha >= 0.45f) {
                                        d = (pose.subjectDepthM + fgDo[s00]).coerceAtLeast(0.5f)
                                    }
                                }
                            }
                        }
                    }

                    outR[idx] = r.coerceIn(0f, 1f)
                    outG[idx] = g.coerceIn(0f, 1f)
                    outB[idx] = b.coerceIn(0f, 1f)
                    outD[idx] = d
                    u += stepXX
                    v += stepYX
                }

                // Pure backdrop right flank
                for (x in fgMaxX until dw) {
                    val idx = rowOff + x
                    val bi = bgRow + bgXMap[x]
                    outR[idx] = bgR[bi]
                    outG[idx] = bgG[bi]
                    outB[idx] = bgB[bi]
                    outD[idx] = BACKDROP_DEPTH_M
                }
            }
        }

        private fun bilerp(c00: Float, c10: Float, c01: Float, c11: Float, fu: Float, fv: Float): Float {
            val top = c00 + (c10 - c00) * fu
            val bot = c01 + (c11 - c01) * fu
            return top + (bot - top) * fv
        }

        private fun renderLinearToBayer(
            frame: LinearRgbFrame,
            geometry: SensorGeometry,
            gain: Double,
            bytesPerSample: Int,
            out: ByteArray,
        ) {
            val maxSample = (1 shl geometry.bitDepth) - 1
            val srcW = frame.width
            val srcH = frame.height
            val dstW = geometry.width
            val dstH = geometry.height

            val scale = min(dstW.toDouble() / srcW.toDouble(), dstH.toDouble() / srcH.toDouble())
            val fitW = (srcW * scale).roundToInt().coerceIn(1, dstW)
            val fitH = (srcH * scale).roundToInt().coerceIn(1, dstH)
            val offX = (dstW - fitW) / 2
            val offY = (dstH - fitH) / 2
            val endX = offX + fitW
            val endY = offY + fitH

            val srcXMap = IntArray(fitW) { dx -> ((dx.toLong() * srcW) / fitW).toInt().coerceIn(0, srcW - 1) }
            val srcYMap = IntArray(fitH) { dy -> ((dy.toLong() * srcH) / fitH).toInt().coerceIn(0, srcH - 1) }
            val bayer = geometry.bayer

            var outPos = 0
            for (y in 0 until dstH) {
                val inY = y in offY until endY
                val srcRow = if (inY) srcYMap[y - offY] * srcW else 0
                val yOdd = y and 1
                for (x in 0 until dstW) {
                    val sample = if (inY && x in offX until endX) {
                        val idx = srcRow + srcXMap[x - offX]
                        val ch = SceneFrameCodec.bayerChannel(bayer, x and 1, yOdd)
                        val linRaw = when (ch) {
                            0 -> frame.red[idx]
                            1 -> frame.green[idx]
                            else -> frame.blue[idx]
                        }
                        val scaled = (linRaw.toDouble() * gain).coerceIn(0.0, 1.0)
                        (scaled * maxSample).roundToInt()
                    } else {
                        0
                    }
                    if (bytesPerSample == 1) {
                        out[outPos++] = sample.toByte()
                    } else {
                        out[outPos++] = (sample and 0xFF).toByte()
                        out[outPos++] = ((sample ushr 8) and 0xFF).toByte()
                    }
                }
            }
        }
    }
}
