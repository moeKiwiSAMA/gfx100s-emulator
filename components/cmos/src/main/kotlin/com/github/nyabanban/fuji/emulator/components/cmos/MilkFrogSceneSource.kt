package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneAudioProvider
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource3D
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Animated 3D multi-depth **Laughing Milk Frog (捧腹大笑奶蛙)** full-spectrum optical test scene
 * (`scene://milk-frog`) powered by a real painted 3D triangle mesh (`scenes/milk-frog-mesh.bin.gz`)
 * and real clipped viral Milk Frog laugh audio (`scenes/milk-frog-laugh.pcm.gz`).
 *
 * Features a blazing-fast stripe-parallel Z-buffer 3D triangle rasterizer with 60 fps audio-envelope-
 * synchronized belly-laugh vertex deformation, surrounded by a 4-zone optical test studio:
 * - **Near Foreground (`0.62 m – 0.82 m`)**: two near-field HDR specular bokeh bulbs (`radiance 4.8..7.2`),
 *   floating sparkling laugh-tear droplets, and crisp comic `"HA!"` badge for MFD focus, foreground
 *   bokeh, and foreground-to-subject AF racks.
 * - **Hero Plane (`1.25 m – 1.75 m`)**: Real 3D sculpted & painted Laughing Milk Frog (warm creamy
 *   butter-yellow `#F6D48A` with rosy cheek blush, pale cream belly `#FFF6DE`, dark taupe belly-clutching
 *   hands & feet `#5A524C`, flush sage-green eye rings `#7EB076` framing matte-black pupils, and dark
 *   laughing mouth cavity) on a spotlight turntable pedestal beside an angled `-15..+15 cm` focus ruler.
 *   Naturally triggers real-silicon `0xFFF7C000` hardware Face & Eye-AF!
 * - **Midground Optical Lab (`2.65 m – 2.85 m`)**: Left easel with 24-patch Macbeth ColorChecker +
 *   12-step grayscale wedge (`2.65 m`) and right easel with Slanted-Edge MTF + Siemens star +
 *   fine line-pair resolution/moiré sweep (`2.85 m`).
 * - **Background HDR Bokeh Stage (`4.2 m – 10.5 m`)**: Deep radial twilight studio backdrop with
 *   two pin lights at 7.5 and 8.2 m for wide and telephoto framing, plus
 *   20 staggered multi-depth HDR specular bokeh lights (`radiance 4.8..9.2`) at `4.2 m`, `6.0 m`,
 *   `8.2 m`, and `10.5 m`.
 * - **Spatialized 48 kHz Stereo Real Laugh Audio ([SceneAudioProvider])**: Resamples the real viral
 *   Milk Frog laugh PCM to 48 kHz with equal-power 3D azimuth panning & inverse-distance attenuation
 *   from 6-DoF [CameraPose3D], while its RMS envelope drives the 60 fps belly-laugh vibration.
 *
 * Supported query parameters:
 * - `motion=on|off` (default `on`)
 * - `audio=on|off` (default `on`)
 * - `phase=0.0..1.0` (default `0.0`)
 * - `period=<seconds>` (default matches real laugh clip duration ~`4.01 s`, range `0.5..60.0`)
 */
class MilkFrogSceneSource internal constructor(
    val width: Int = ProceduralOpticsSceneSource.DEFAULT_WIDTH,
    val height: Int = ProceduralOpticsSceneSource.DEFAULT_HEIGHT,
    private val loop: Boolean = true,
    val motionEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    private val initialPhase: Double = 0.0,
    private val periodSeconds: Double = DEFAULT_PERIOD_SECONDS,
    private val nanoClock: () -> Long = System::nanoTime,
    override val activeSourceRef: String = OpticalScenePreset.MILK_FROG.uri,
) : SceneSource3D, LiveStreamSceneSource, SceneAudioProvider {

    /** Animation pose snapshot at normalized cycle [phase] (`0.0..1.0`), synced to real audio RMS envelope. */
    internal data class FrogAnimState(
        val phase: Double,
        val worldX: Double,
        val worldY: Double,
        val worldZ: Double,
        val audioEnvelope: Double,  // 0..1 RMS envelope from real viral laugh clip
        val laughIntensity: Double, // combined animation intensity (0..1)
        val shakeY: Double,         // rapid vertical belly-laugh bounce
        val leanForward: Double,    // forward/back pitch rock (radians)
        val rockSide: Double,       // side-to-side comedy roll (radians)
        val bellySquash: Double,    // squash-and-stretch belly pulse
        val mouthVibration: Double, // mouth opening jitter synced to laugh syllables
        val kickPhase: Double,      // alternating stubby foot tap
        val tearPhase: Double,      // 0..1 repeating tear droplet arc
        val arcPulse: Double,       // 0..1 comic laugh-motion arc visibility
    )

    internal class BakedFrogMesh(
        val vertexCount: Int,
        val triCount: Int,
        val posX: FloatArray,
        val posY: FloatArray,
        val posZ: FloatArray,
        val normX: FloatArray,
        val normY: FloatArray,
        val normZ: FloatArray,
        val linR: FloatArray,
        val linG: FloatArray,
        val linB: FloatArray,
        val partId: ByteArray,
        val indices: IntArray,
    )

    internal class BakedLaughAudio(
        val sampleRateHz: Int,
        val samples: ShortArray,
        val envelope: FloatArray,
    ) {
        val durationSeconds: Double get() = samples.size.toDouble() / sampleRateHz.toDouble()

        fun sampleEnvelope(phase: Double): Double {
            if (envelope.isEmpty()) return 0.5
            val p = wrapPhase(phase) * envelope.size
            val i0 = floor(p).toInt().coerceIn(0, envelope.size - 1)
            val i1 = (i0 + 1) % envelope.size
            val f = p - i0
            return envelope[i0] * (1.0 - f) + envelope[i1] * f
        }
    }

    var exhausted: Boolean = false
        private set

    @Volatile
    private var closed: Boolean = false

    private val startNanos: Long = nanoClock()

    @Volatile
    private var lastRenderedFrame = -1L

    @Volatile
    override var currentPose: CameraPose3D = CameraPose3D.IDENTITY
        private set

    private var lastRenderedPhase: Double = Double.NaN
    private var lastCameraPoseKey: Long = Long.MIN_VALUE
    private var frameSeq: Long = 0L

    private val mesh: BakedFrogMesh = BAKED_MESH
    private val audioClip: BakedLaughAudio = BAKED_AUDIO

    // Reusable per-instance projected vertex & triangle-bounds scratch buffers (zero per-frame allocations).
    private val projX = FloatArray(mesh.vertexCount)
    private val projY = FloatArray(mesh.vertexCount)
    private val projInvZ = FloatArray(mesh.vertexCount)
    private val litR = FloatArray(mesh.vertexCount)
    private val litG = FloatArray(mesh.vertexCount)
    private val litB = FloatArray(mesh.vertexCount)
    private val triMinY = IntArray(mesh.triCount)
    private val triMaxY = IntArray(mesh.triCount)
    private val triMinX = IntArray(mesh.triCount)
    private val triMaxX = IntArray(mesh.triCount)
    private val triVisible = BooleanArray(mesh.triCount)

    // Double-buffered planar RGB+Depth slots so active OpticalSceneFilter reads never race with updates.
    private val slotFrames = Array(2) {
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

    // Pre-rendered static studio stage (20 background HDR bokeh lights + midground Macbeth & MTF easels).
    private val staticStage: StaticStageBuffer = buildStaticStage(width, height)
    private val packedStageRgbaDepth: FloatArray by lazy {
        val n = width * height
        val out = FloatArray(n * 4)
        var o = 0
        for (i in 0 until n) {
            out[o] = staticStage.r[i]
            out[o + 1] = staticStage.g[i]
            out[o + 2] = staticStage.b[i]
            out[o + 3] = staticStage.depth[i]
            o += 4
        }
        out
    }
    private val stageGpuKey: Long = (System.identityHashCode(this).toLong() shl 32) xor
        (width.toLong() shl 16) xor height.toLong() xor 0x4D465354L
    private var gpuSpecSeq: Long = 1L
    private var cachedGpuFrame = -1L
    private var cachedGpuSpec: GpuScene3dRenderSpec.MilkFrog? = null

    // Single-slot Bayer readout cache.
    private var cachedSeq: Long = -1L
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedBitDepth: Int = -1
    private var cachedBayer: com.github.nyabanban.fuji.emulator.api.BayerPattern? = null
    private var cachedGainBits: Long = 0L
    private var cachedBytes: ByteArray? = null

    // Lock-guarded continuous playback cursor into real 24 kHz viral laugh PCM.
    private val audioLock = Any()
    private var audioPlayhead: Double = wrapPhase(initialPhase) * audioClip.samples.size.toDouble()

    init {
        renderPhaseLocked(initialPhase, CameraPose3D.IDENTITY)
        lastRenderedFrame = 0L
    }

    override val sourceWidth: Int get() = width
    override val sourceHeight: Int get() = height
    override val hasDepthBuffer: Boolean get() = true
    override val lastSourceError: String? get() = null
    override val framesReceived: Long get() = max(1L, frameSeq)
    override val framesDropped: Long get() = 0L
    override val sceneAudioEnabled: Boolean get() = audioEnabled && !closed
    override val sceneAudioName: String get() = "Milk Frog 3D Laugh (48 kHz Stereo)"

    override val latestFrameSequence: Long
        get() {
            if (!motionEnabled) return 0L
            if (closed) return frameSeq
            return max(frameSeq, videoFrame() + 1L)
        }

    // The scene publishes at 60 fps. Dividing before multiplying also handles long virtual runtimes.
    override val fusedFrameSequence: Long get() = videoFrame()

    private fun videoFrame(): Long {
        if (!motionEnabled) return 0L
        val nanos = (nanoClock() - startNanos).coerceAtLeast(0L)
        return cameraAnimationFrame(nanos)
    }

    private fun phaseAtFrame(frame: Long): Double = wrapPhase(initialPhase + frame / (60.0 * periodSeconds))

    internal fun currentAnimState(): FrogAnimState = computeAnimState(phaseAtFrame(videoFrame()))

    private fun refreshLinearFrame() {
        if (videoFrame() == lastRenderedFrame) return
        synchronized(this) {
            if (closed) return
            val frame = videoFrame()
            if (frame != lastRenderedFrame) {
                renderPhaseLocked(phaseAtFrame(frame), currentPose)
                lastRenderedFrame = frame
            }
        }
    }

    override fun renderForPose(pose: CameraPose3D): LinearRadianceFrame {
        check(!closed) { "Milk Frog scene source is closed" }
        synchronized(this) {
            currentPose = pose
            val frame = videoFrame()
            val phase = phaseAtFrame(frame)
            lastRenderedFrame = frame
            renderPhaseLocked(phase, pose)
            return slotFrames[activeSlot].toRadianceFrame(
                dimension = SceneDimension.DIM_3D,
                pose = pose,
                sequence = frameSeq,
            )
        }
    }

    override fun gpuScene3dRenderSpec(pose: CameraPose3D): GpuScene3dRenderSpec.MilkFrog {
        check(!closed) { "Milk Frog scene source is closed" }
        synchronized(this) {
            // A direct Bayer read can follow a GPU-only view change within the same camera frame.
            if (pose != currentPose) lastRenderedFrame = -1L
            currentPose = pose
            val frame = videoFrame()
            val cached = cachedGpuSpec
            if (cached != null && frame == cachedGpuFrame && pose == cached.pose) return cached
            val phase = phaseAtFrame(frame)
            val anim = computeAnimState(phase)
            val seq = ++gpuSpecSeq

            val halfW = (width - 1) * 0.5
            val halfH = (height - 1) * 0.5
            val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
            val invFocalScale = 1.0 / focalScale
            val focal = width * 0.76 * focalScale
            val hasPose = pose.hasViewTransform
            val hasTranslation = abs(pose.posXM) > 1e-6 || abs(pose.posYM) > 1e-6 || abs(pose.posZM) > 1e-6

            val rollRad = Math.toRadians(pose.rollDeg)
            val pitchRad = Math.toRadians(pose.pitchDeg)
            val yawRad = Math.toRadians(pose.yawDeg)
            val cR = cos(rollRad); val sR = sin(rollRad)
            val cP = cos(pitchRad); val sP = sin(pitchRad)
            val cY = cos(yawRad); val sY = sin(yawRad)

            val r00 = cR * cY + sR * sP * sY
            val r01 = sR * cY - cR * sP * sY
            val r02 = cP * sY
            val r10 = -sR * cP
            val r11 = cR * cP
            val r12 = sP
            val r20 = -cR * sY + sR * sP * cY
            val r21 = -sR * sY - cR * sP * cY
            val r22 = cP * cY

            val ox = pose.posXM.coerceIn(-1.5, 1.5)
            val oy = pose.posYM.coerceIn(-0.9, 1.2)
            val oz = pose.posZM.coerceIn(-1.2, 0.80)
            val yawShiftPx = tan(Math.toRadians(pose.yawDeg.coerceIn(-60.0, 60.0))) * focal
            val pitchShiftPx = tan(Math.toRadians(pose.pitchDeg.coerceIn(-60.0, 60.0))) * focal

            val handBounce = (0.012 * anim.laughIntensity * abs(sin(anim.phase * 2.0 * PI * 12.0))).toFloat()

            // Pre-project up to 8 depth-aware HDR orbs (4 flying laugh tears + 4 studio lamps)
            val orbs = FloatArray(8 * 8)
            var orbCount = 0
            fun pushOrb(wx: Double, wy: Double, wz: Double, worldRad: Double, minPx: Int, maxPx: Int, cr: Float, cg: Float, cb: Float, alignCore: Boolean = false) {
                if (orbCount >= 8) return
                val vx = wx - ox; val vy = wy - oy; val vz = wz - oz
                val cx = r00 * vx + r10 * vy + r20 * vz
                val cy = r01 * vx + r11 * vy + r21 * vz
                val cz = r02 * vx + r12 * vy + r22 * vz
                if (cz < 0.15) return
                val sx = (halfW + (cx / cz) * focal).toFloat()
                val sy = (halfH - (cy / cz) * focal).toFloat()
                val radF = ((worldRad / cz) * focal).roundToInt().coerceIn(minPx, maxPx).toFloat()
                val haloRadF = orbHaloRadius(radF.toInt()).toFloat()
                val o = orbCount * 8
                // Register the sub-resolution lamp core to the same sample as the CPU raster.
                orbs[o] = if (alignCore) sx.roundToInt().toFloat() else sx
                orbs[o + 1] = if (alignCore) sy.roundToInt().toFloat() else sy
                orbs[o + 2] = radF
                orbs[o + 3] = haloRadF
                orbs[o + 4] = cr; orbs[o + 5] = cg; orbs[o + 6] = cb; orbs[o + 7] = cz.toFloat()
                orbCount++
            }
            if (anim.laughIntensity > 0.28) {
                for (side in intArrayOf(-1, 1)) {
                    for (k in 0 until 2) {
                        val tp = (anim.tearPhase + k * 0.48) % 1.0
                        val localX = side * (0.095 + 0.22 * tp)
                        val localZ = -0.105 - 0.16 * tp
                        val tx = anim.worldX + PLACEMENT_COS * localX + PLACEMENT_SIN * localZ
                        val ty = anim.worldY + 0.19 + 0.10 * tp - 0.22 * tp * tp
                        val tz = anim.worldZ + PLACEMENT_COS * localZ - PLACEMENT_SIN * localX
                        pushOrb(tx, ty, tz, 0.005, 2, 6, 1.3f, 2.5f, 3.2f)
                    }
                }
            }
            for (lamp in STUDIO_LAMPS) {
                pushOrb(lamp.x, lamp.y, lamp.z, lamp.radius, lamp.minPx, lamp.maxPx, lamp.r, lamp.g, lamp.b, alignCore = lamp.minPx == 1)
            }

            val phaseBits = (phase * 100_000.0).roundToInt().toLong()
            val sceneKey = ((pose.quantizedViewKey() * 0x9E3779B97F4A7C15UL.toLong()) xor
                (phaseBits * 0xBF58476D1CE4E5B9UL.toLong()) xor stageGpuKey).let { if (it == 0L) 1L else it }

            return GpuScene3dRenderSpec.MilkFrog(
                width = width,
                height = height,
                pose = pose,
                sceneKey = sceneKey,
                sequence = seq,
                meshKey = BAKED_MESH_GPU_KEY,
                vertexDataProvider = { PACKED_BAKED_MESH_VERTS },
                vertexCount = mesh.vertexCount,
                indicesProvider = { mesh.indices },
                triCount = mesh.triCount,
                stageKey = stageGpuKey,
                stageRgbaDepthProvider = { packedStageRgbaDepth },
                hasPose = hasPose,
                hasTranslation = hasTranslation,
                halfW = halfW.toFloat(),
                halfH = halfH.toFloat(),
                focal = focal.toFloat(),
                invFocalScale = invFocalScale.toFloat(),
                ox = ox.toFloat(),
                oy = oy.toFloat(),
                oz = oz.toFloat(),
                cR = cR.toFloat(),
                sR = sR.toFloat(),
                yawShiftPx = yawShiftPx.toFloat(),
                pitchShiftPx = pitchShiftPx.toFloat(),
                posXM = pose.posXM.toFloat(),
                posYM = pose.posYM.toFloat(),
                posZM = pose.posZM.toFloat(),
                r00 = r00.toFloat(),
                r01 = r01.toFloat(),
                r02 = r02.toFloat(),
                r10 = r10.toFloat(),
                r11 = r11.toFloat(),
                r12 = r12.toFloat(),
                r20 = r20.toFloat(),
                r21 = r21.toFloat(),
                r22 = r22.toFloat(),
                frogX = anim.worldX.toFloat(),
                frogY = anim.worldY.toFloat(),
                frogZ = anim.worldZ.toFloat(),
                cLean = cos(anim.leanForward).toFloat(),
                sLean = sin(anim.leanForward).toFloat(),
                cRock = cos(anim.rockSide).toFloat(),
                sRock = sin(anim.rockSide).toFloat(),
                bellySquash = anim.bellySquash.toFloat(),
                handBounce = handBounce,
                kickPhase = anim.kickPhase.toFloat(),
                mouthVib = anim.mouthVibration.toFloat(),
                arcPulse = anim.arcPulse.toFloat(),
                orbCount = orbCount,
                pedZ = HERO_NOMINAL_DEPTH_M,
                pedTopY = -0.458f,
                pedBotY = -0.538f,
                pedRad = 0.36f,
                orbs = orbs,
            ).also {
                cachedGpuFrame = frame
                cachedGpuSpec = it
            }
        }
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame {
        check(!closed) { "Milk Frog scene source is closed" }
        refreshLinearFrame()
        return slotFrames[activeSlot]
    }

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed) { "Milk Frog scene source is closed" }
        check(!exhausted) { "Milk Frog scene source exhausted" }
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

    /**
     * Streams the real clipped viral Milk Frog laugh audio resampled cleanly to 48 kHz 16-bit
     * interleaved stereo PCM with real 3D equal-power azimuth panning and inverse-distance
     * attenuation derived from [pose]. Strictly silent when `audio=off`.
     */
    override fun sampleAudioStereo48k(
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        pose: CameraPose3D,
    ): Int {
        synchronized(audioLock) {
            val end = renderAudio(pcmStereoInterleaved, frames, pose, audioPlayhead)
            if (frames > 0 && audioEnabled && !closed) audioPlayhead = end
        }
        return frames.coerceAtLeast(0)
    }

    internal fun sampleAudioAtNanos(out: ShortArray, frames: Int, pose: CameraPose3D, nanos: Long): Int {
        // Audio keeps playing even if mesh motion is explicitly disabled.
        val seconds = (nanos - startNanos).coerceAtLeast(0L) / 1_000_000_000.0
        val phase = wrapPhase(initialPhase + seconds / periodSeconds)
        renderAudio(out, frames, pose, phase * audioClip.samples.size)
        return frames.coerceAtLeast(0)
    }

    private fun renderAudio(pcmStereoInterleaved: ShortArray, frames: Int, pose: CameraPose3D, start: Double): Double {
        if (frames <= 0) return start
        val totalSamples = frames * 2
        require(pcmStereoInterleaved.size >= totalSamples) {
            "PCM buffer too small: need $totalSamples shorts, got ${pcmStereoInterleaved.size}"
        }
        if (!audioEnabled || closed || audioClip.samples.isEmpty()) {
            pcmStereoInterleaved.fill(0, 0, totalSamples)
            return start
        }

        val clipSamples = audioClip.samples
        val clipLen = clipSamples.size
        val srcStep = clipLen / (periodSeconds * SceneAudioProvider.SAMPLE_RATE_HZ)

        val anim = computeAnimState(start / clipLen.toDouble())
        // 3D stereo spatialization from CameraPose3D relative to Milk Frog's live world position.
        val dx = anim.worldX - pose.posXM
        val dy = anim.worldY - pose.posYM
        val dz = (anim.worldZ - pose.posZM).coerceAtLeast(0.25)
        val yawRad = Math.toRadians(pose.yawDeg)
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)
        val localX = dx * cosY - dz * sinY
        val localZ = dx * sinY + dz * cosY
        val dist = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.30)
        val azimuth = atan2(localX, max(0.25, abs(localZ))).coerceIn(-PI * 0.48, PI * 0.48)
        val panNorm = (sin(azimuth) * 0.92).coerceIn(-0.92, 0.92)
        val panAngle = (panNorm + 1.0) * (PI * 0.25)
        val gainL = cos(panAngle)
        val gainR = sin(panAngle)
        // Inverse-distance attenuation normalized to nominal hero depth
        val distAtten = (HERO_NOMINAL_DEPTH_M.toDouble() / (0.55 + 0.62 * dist)).coerceIn(0.18, 1.35)
        val masterL = gainL * distAtten * 0.92
        val masterR = gainR * distAtten * 0.92

        var pos = start
        var dst = 0
        for (i in 0 until frames) {
            val i0 = pos.toInt() % clipLen
            val i1 = if (i0 + 1 < clipLen) i0 + 1 else 0
            val frac = pos - floor(pos)
            val mono = clipSamples[i0] * (1.0 - frac) + clipSamples[i1] * frac
            pcmStereoInterleaved[dst++] = (mono * masterL).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcmStereoInterleaved[dst++] = (mono * masterR).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += srcStep
            if (pos >= clipLen) pos -= clipLen
        }
        return pos
    }

    override fun close() {
        closed = true
        cachedBytes = null
    }

    private fun renderPhaseLocked(phase: Double, pose: CameraPose3D) {
        val wrapped = wrapPhase(phase)
        val poseKey = pose.quantizedViewKey()
        if (lastRenderedPhase == wrapped && lastCameraPoseKey == poseKey && frameSeq > 0L) return
        val anim = computeAnimState(wrapped)
        val nextSlot = if (frameSeq == 0L) 0 else (1 - activeSlot)
        val dst = slotFrames[nextSlot]
        renderFullScene(staticStage, anim, pose, dst)
        activeSlot = nextSlot
        lastRenderedPhase = wrapped
        lastCameraPoseKey = poseKey
        frameSeq++
    }

    /**
     * Renders the full multi-depth Milk Frog frame:
     * 1. Transforms and lights the sculpted mesh with 60 fps audio-synced deformation,
     * 2. Stripe-parallel compositing of static stage + turntable pedestal + `-15..+15 cm` focus ruler +
     *    perspective-correct Z-buffered triangle rasterization of Milk Frog,
     * 3. Animated 3D comic laugh tears, belly laugh-motion arcs, and near-foreground (`0.62..0.82 m`) HDR bulbs & `"HA!"`.
     */
    private fun renderFullScene(
        stage: StaticStageBuffer,
        anim: FrogAnimState,
        pose: CameraPose3D,
        dst: LinearRgbFrame,
    ) {
        val w = dst.width
        val h = dst.height
        val outR = dst.red
        val outG = dst.green
        val outB = dst.blue
        val outD = checkNotNull(dst.depthMeters)

        val halfW = (w - 1) * 0.5
        val halfH = (h - 1) * 0.5
        val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
        val invFocalScale = 1.0 / focalScale
        val focal = w * 0.76 * focalScale
        val hasPose = pose.hasViewTransform

        // Camera rotation matrix R = R_yaw * R_pitch * R_roll
        val rollRad = Math.toRadians(pose.rollDeg)
        val pitchRad = Math.toRadians(pose.pitchDeg)
        val yawRad = Math.toRadians(pose.yawDeg)
        val cR = cos(rollRad); val sR = sin(rollRad)
        val cP = cos(pitchRad); val sP = sin(pitchRad)
        val cY = cos(yawRad); val sY = sin(yawRad)

        val r00 = cR * cY + sR * sP * sY
        val r01 = sR * cY - cR * sP * sY
        val r02 = cP * sY
        val r10 = -sR * cP
        val r11 = cR * cP
        val r12 = sP
        val r20 = -cR * sY + sR * sP * cY
        val r21 = -sR * sY - cR * sP * cY
        val r22 = cP * cY

        val ox = pose.posXM.coerceIn(-1.5, 1.5)
        val oy = pose.posYM.coerceIn(-0.9, 1.2)
        val oz = pose.posZM.coerceIn(-1.2, 0.80)

        // Hero pedestal & focus ruler geometry
        val pedTopY = -0.458
        val pedBotY = -0.538
        val pedRad = 0.36
        val pedRadSq = pedRad * pedRad
        val pedZ = HERO_NOMINAL_DEPTH_M.toDouble()

        val rulerX0 = 0.29
        val rulerX1 = 0.42
        val rulerY = pedTopY + 0.004
        val rulerZNear = HERO_NOMINAL_DEPTH_M - 0.16
        val rulerZFar = HERO_NOMINAL_DEPTH_M + 0.16

        // Studio 3-point soft matte-gouache wrap lighting vectors
        val keyLen = sqrt(0.32 * 0.32 + 0.66 * 0.66 + 0.68 * 0.68)
        val kLx = (-0.32 / keyLen).toFloat(); val kLy = (0.66 / keyLen).toFloat(); val kLz = (-0.68 / keyLen).toFloat()
        val fillLen = sqrt(0.70 * 0.70 + 0.28 * 0.28 + 0.66 * 0.66)
        val fLx = (0.70 / fillLen).toFloat(); val fLy = (0.28 / fillLen).toFloat(); val fLz = (-0.66 / fillLen).toFloat()

        // --- Stage 1: Vertex deformation, 3-point studio lighting, and perspective projection ---
        val vCount = mesh.vertexCount
        val mPx = mesh.posX; val mPy = mesh.posY; val mPz = mesh.posZ
        val mNx = mesh.normX; val mNy = mesh.normY; val mNz = mesh.normZ
        val mCr = mesh.linR; val mCg = mesh.linG; val mCb = mesh.linB
        val mPid = mesh.partId

        // Frog animation rotation around pedestal pivot (0, pedTopY, 0)
        val cRock = cos(anim.rockSide).toFloat(); val sRock = sin(anim.rockSide).toFloat()
        val cLean = cos(anim.leanForward).toFloat(); val sLean = sin(anim.leanForward).toFloat()
        val frogX = anim.worldX.toFloat()
        val frogY = anim.worldY.toFloat()
        val frogZ = anim.worldZ.toFloat()
        val pivotY = -0.455f
        val bellySquash = anim.bellySquash.toFloat()
        val mouthVib = anim.mouthVibration.toFloat()
        val kickPhase = anim.kickPhase.toFloat()
        val handBounce = (0.012 * anim.laughIntensity * abs(sin(anim.phase * 2.0 * PI * 12.0))).toFloat()

        val fHalfW = halfW.toFloat()
        val fHalfH = halfH.toFloat()
        val fFocal = focal.toFloat()
        val fOx = ox.toFloat(); val fOy = oy.toFloat(); val fOz = oz.toFloat()
        val fr00 = r00.toFloat(); val fr01 = r01.toFloat(); val fr02 = r02.toFloat()
        val fr10 = r10.toFloat(); val fr11 = r11.toFloat(); val fr12 = r12.toFloat()
        val fr20 = r20.toFloat(); val fr21 = r21.toFloat(); val fr22 = r22.toFloat()

        for (i in 0 until vCount) {
            var lx = mPx[i]
            var ly = mPy[i]
            var lz = mPz[i]
            val pid = mPid[i].toInt()

            // Smooth anatomical belly-laugh deformation driven by real viral laugh RMS envelope!
            val heightAboveFeet = (ly - pivotY).coerceAtLeast(0f)
            // Belly & mid-torso squash-and-stretch bulge (peaks around y = -0.05..+0.05)
            val bellyBand = exp(-((ly + 0.02f) * (ly + 0.02f)) / 0.028f)
            val radialExpand = 1f + bellySquash * (0.45f + 0.75f * bellyBand)
            lx *= radialExpand
            lz *= radialExpand
            ly = pivotY + heightAboveFeet * (1f - bellySquash * 0.28f)

            when (pid) {
                2 -> { // Dark taupe hands & feet
                    if (ly > -0.26f) {
                        // Hands tightly clutching & pressing the bouncing belly
                        ly += handBounce
                        lz -= bellySquash * 0.08f
                    } else {
                        // Playful subtle alternating foot tap
                        val footLift = if (lx < 0f) max(0f, kickPhase) else max(0f, -kickPhase)
                        ly += footLift * 0.016f
                    }
                }
                5 -> { // Laughing mouth vibrates open with laugh syllables
                    if (ly < 0.2215f) {
                        ly -= mouthVib * 0.008f
                    }
                }
            }

            // Pivot rock forward/back (around X) & side-to-side (around Z)
            val py0 = ly - pivotY
            val ry1 = py0 * cLean - lz * sLean
            val rz1 = py0 * sLean + lz * cLean
            val rx2 = lx * cRock - ry1 * sRock
            val ry2 = lx * sRock + ry1 * cRock

            val wx = PLACEMENT_COS * rx2 + PLACEMENT_SIN * rz1 + frogX
            val wy = ry2 + pivotY + frogY
            val wz = PLACEMENT_COS * rz1 - PLACEMENT_SIN * rx2 + frogZ

            // Rotate normal with frog animation
            val nx0 = mNx[i]; val ny0 = mNy[i]; val nz0 = mNz[i]
            val ny1 = ny0 * cLean - nz0 * sLean
            val nz1 = ny0 * sLean + nz0 * cLean
            val nx2 = nx0 * cRock - ny1 * sRock
            val nWx = PLACEMENT_COS * nx2 + PLACEMENT_SIN * nz1
            val nWy = nx0 * sRock + ny1 * cRock
            val nWz = PLACEMENT_COS * nz1 - PLACEMENT_SIN * nx2

            // Camera space = R^T * (wPos - camOrigin)
            val vx = wx - fOx
            val vy = wy - fOy
            val vz = wz - fOz
            val camX = fr00 * vx + fr10 * vy + fr20 * vz
            val camY = fr01 * vx + fr11 * vy + fr21 * vz
            val camZ = fr02 * vx + fr12 * vy + fr22 * vz

            if (camZ > 0.15f) {
                val invZ = 1f / camZ
                projX[i] = fHalfW + camX * invZ * fFocal
                projY[i] = fHalfH - camY * invZ * fFocal
                projInvZ[i] = invZ
            } else {
                projInvZ[i] = -1f
            }

            // Double-sided matte-gouache 3-point wrap lighting + soft rim + vertical AO
            val invVLen = 1f / max(1e-5f, sqrt(vx * vx + vy * vy + vz * vz))
            val viewX = -vx * invVLen; val viewY = -vy * invVLen; val viewZ = -vz * invVLen
            var snx = nWx; var sny = nWy; var snz = nWz
            var nDotV = snx * viewX + sny * viewY + snz * viewZ
            if (nDotV < 0f) {
                snx = -snx; sny = -sny; snz = -snz
                nDotV = -nDotV
            }
            val diffKey = ((snx * kLx + sny * kLy + snz * kLz) * 0.62f + 0.38f).coerceIn(0f, 1f)
            val diffFill = max(0f, snx * fLx + sny * fLy + snz * fLz)
            val rim = (1f - nDotV.coerceIn(0f, 1f)).pow(3.5f) * (if (pid == 4 || pid == 5) 0.01f else 0.07f)
            // Keep upper head/face bright & even for crisp 0xFFF7C000 Face/Eye-AF while giving gentle belly/feet AO
            val ao = 0.84f + 0.16f * (heightAboveFeet / 0.66f).coerceIn(0f, 1f)
            val lR = (0.32f + 0.58f * diffKey + 0.17f * diffFill) * ao
            val lG = (0.32f + 0.57f * diffKey + 0.18f * diffFill) * ao
            val lB = (0.33f + 0.54f * diffKey + 0.21f * diffFill) * ao

            // Pupils & mouth stay crisp matte dark
            val cr = mCr[i]; val cg = mCg[i]; val cb = mCb[i]
            if (pid == 4) {
                litR[i] = cr * (0.45f + 0.55f * diffKey)
                litG[i] = cg * (0.45f + 0.55f * diffKey)
                litB[i] = cb * (0.45f + 0.55f * diffKey)
            } else {
                litR[i] = (cr * lR + rim * 0.88f).coerceIn(0f, 1.15f)
                litG[i] = (cg * lG + rim * 0.94f).coerceIn(0f, 1.15f)
                litB[i] = (cb * lB + rim * 1.02f).coerceIn(0f, 1.15f)
            }
        }

        // Precompute screen bounding box per triangle once so each stripe skips 85%+ of triangles in O(1)
        val tCount = mesh.triCount
        val idxArr = mesh.indices
        val maxX = w - 1
        val maxY = h - 1
        for (t in 0 until tCount) {
            val base = t * 3
            val i0 = idxArr[base]; val i1 = idxArr[base + 1]; val i2 = idxArr[base + 2]
            if (projInvZ[i0] <= 0f || projInvZ[i1] <= 0f || projInvZ[i2] <= 0f) {
                triVisible[t] = false
                continue
            }
            val x0 = projX[i0]; val x1 = projX[i1]; val x2 = projX[i2]
            val y0 = projY[i0]; val y1 = projY[i1]; val y2 = projY[i2]
            val mnX = floor(min(x0, min(x1, x2))).toInt()
            val mxX = floor(max(x0, max(x1, x2))).toInt()
            val mnY = floor(min(y0, min(y1, y2))).toInt()
            val mxY = floor(max(y0, max(y1, y2))).toInt()
            if (mxX < 0 || mnX > maxX || mxY < 0 || mnY > maxY) {
                triVisible[t] = false
                continue
            }
            triMinX[t] = mnX.coerceAtLeast(0)
            triMaxX[t] = mxX.coerceAtMost(maxX)
            triMinY[t] = mnY.coerceAtLeast(0)
            triMaxY[t] = mxY.coerceAtMost(maxY)
            triVisible[t] = true
        }

        val yawShiftPx = tan(Math.toRadians(pose.yawDeg.coerceIn(-60.0, 60.0))) * focal
        val pitchShiftPx = tan(Math.toRadians(pose.pitchDeg.coerceIn(-60.0, 60.0))) * focal
        val hasTranslation = abs(pose.posXM) > 1e-6 || abs(pose.posYM) > 1e-6 || abs(pose.posZM) > 1e-6

        // --- Stage 2: Stripe-parallel background stage + turntable pedestal + focus ruler + 3D mesh rasterization ---
        parallelRows(h) { yStart, yEnd ->
            // 2a. Copy/reproject static stage + analytic pedestal & focus ruler for rows [yStart, yEnd)
            for (y in yStart until yEnd) {
                val row = y * w
                if (!hasPose) {
                    System.arraycopy(stage.r, row, outR, row, w)
                    System.arraycopy(stage.g, row, outG, row, w)
                    System.arraycopy(stage.b, row, outB, row, w)
                    System.arraycopy(stage.depth, row, outD, row, w)
                } else {
                    val dyScreen = (y - halfH) * invFocalScale
                    for (x in 0 until w) {
                        val idx = row + x
                        val dxScreen = (x - halfW) * invFocalScale
                        val rrx = dxScreen * cR + dyScreen * sR
                        val rry = -dxScreen * sR + dyScreen * cR
                        var sx = halfW + rrx + yawShiftPx * invFocalScale
                        var sy = halfH + rry - pitchShiftPx * invFocalScale
                        val pX = sx.roundToInt().coerceIn(0, maxX)
                        val pY = sy.roundToInt().coerceIn(0, maxY)
                        val z0 = stage.depth[pY * w + pX].toDouble().coerceAtLeast(0.25)
                        if (hasTranslation) {
                            sx += (pose.posXM / z0) * focal
                            sy -= (pose.posYM / z0) * focal
                        }
                        if (sx < 0.0 || sx > maxX.toDouble() || sy < 0.0 || sy > maxY.toDouble()) {
                            outR[idx] = 0.014f; outG[idx] = 0.020f; outB[idx] = 0.044f
                            outD[idx] = BACKGROUND_MAX_DEPTH_M
                        } else {
                            val x0 = floor(sx).toInt().coerceIn(0, maxX)
                            val y0 = floor(sy).toInt().coerceIn(0, maxY)
                            val x1 = min(x0 + 1, maxX)
                            val y1 = min(y0 + 1, maxY)
                            val fx = (sx - x0).toFloat()
                            val fy = (sy - y0).toFloat()
                            val i00 = y0 * w + x0; val i10 = y0 * w + x1
                            val i01 = y1 * w + x0; val i11 = y1 * w + x1
                            outR[idx] = (stage.r[i00] + (stage.r[i10] - stage.r[i00]) * fx) * (1f - fy) +
                                (stage.r[i01] + (stage.r[i11] - stage.r[i01]) * fx) * fy
                            outG[idx] = (stage.g[i00] + (stage.g[i10] - stage.g[i00]) * fx) * (1f - fy) +
                                (stage.g[i01] + (stage.g[i11] - stage.g[i01]) * fx) * fy
                            outB[idx] = (stage.b[i00] + (stage.b[i10] - stage.b[i00]) * fx) * (1f - fy) +
                                (stage.b[i01] + (stage.b[i11] - stage.b[i01]) * fx) * fy
                            outD[idx] = (stage.depth[i00].toDouble() - pose.posZM).coerceAtLeast(0.10).toFloat()
                        }
                    }
                }

                // Analytic spotlight turntable pedestal + angled -15..+15 cm focus ruler (lower half of frame)
                val ryLocal = (halfH - y) / focal
                if (hasPose || ryLocal < -0.10) {
                    for (x in 0 until w) {
                        val rxLocal = (x - halfW) / focal
                        val invLen = 1.0 / sqrt(rxLocal * rxLocal + ryLocal * ryLocal + 1.0)
                        val ldx = rxLocal * invLen; val ldy = ryLocal * invLen; val ldz = invLen
                        val dx = r00 * ldx + r01 * ldy + r02 * ldz
                        val dy = r10 * ldx + r11 * ldy + r12 * ldz
                        val dz = r20 * ldx + r21 * ldy + r22 * ldz
                        val idx = row + x

                        if (dy < -1e-5) {
                            val tPed = (pedTopY - oy) / dy
                            if (tPed > 0.15) {
                                val camZ = (tPed * ldz).toFloat()
                                if (camZ < outD[idx]) {
                                    val px = ox + dx * tPed
                                    val pz = oz + dz * tPed
                                    val dpx = px - anim.worldX * 0.25
                                    val dpz = pz - pedZ
                                    val pr2 = dpx * dpx + dpz * dpz
                                    if (pr2 <= pedRadSq) {
                                        val contactDist2 = (px - anim.worldX) * (px - anim.worldX) + (pz - anim.worldZ) * (pz - anim.worldZ)
                                        val ao = 1.0 - 0.62 * exp(-contactDist2 / 0.048)
                                        val rim = if (pr2 > pedRadSq * 0.84) 0.42 else 0.20
                                        val ringAccent = if (abs(sqrt(pr2) - pedRad * 0.90) < 0.012) 0.55 else rim
                                        outR[idx] = (ringAccent * ao * 0.98).toFloat()
                                        outG[idx] = (ringAccent * ao * 1.00).toFloat()
                                        outB[idx] = (ringAccent * ao * 1.08).toFloat()
                                        outD[idx] = camZ
                                    }
                                }
                            }
                            val tRuler = (rulerY - oy) / dy
                            if (tRuler > 0.15) {
                                val camZ = (tRuler * ldz).toFloat()
                                if (camZ < outD[idx]) {
                                    val px = ox + dx * tRuler
                                    val pz = oz + dz * tRuler
                                    if (px in rulerX0..rulerX1 && pz in rulerZNear..rulerZFar) {
                                        val uX = (px - rulerX0) / (rulerX1 - rulerX0)
                                        val cmFromCenter = (pz - HERO_NOMINAL_DEPTH_M) * 100.0
                                        val cmFrac = abs(cmFromCenter - floor(cmFromCenter + 0.5))
                                        val isZeroLine = abs(cmFromCenter) < 0.35
                                        val isMajor5 = abs(cmFromCenter % 5.0) < 0.35 || abs(cmFromCenter % 5.0) > 4.65
                                        val isTick = cmFrac < 0.14 && (uX < (if (isMajor5) 0.78 else 0.48))
                                        val isBorder = uX < 0.05 || uX > 0.95
                                        when {
                                            isZeroLine -> { outR[idx] = 0.86f; outG[idx] = 0.12f; outB[idx] = 0.10f }
                                            isTick || isBorder -> { outR[idx] = 0.03f; outG[idx] = 0.03f; outB[idx] = 0.04f }
                                            else -> { outR[idx] = 0.82f; outG[idx] = 0.81f; outB[idx] = 0.76f }
                                        }
                                        outD[idx] = camZ
                                    }
                                }
                            }
                        }

                        // Cylindrical pedestal rim
                        val pedA = dx * dx + dz * dz
                        if (pedA > 1e-6) {
                            val pOx = ox - anim.worldX * 0.25
                            val pOz = oz - pedZ
                            val pedB = pOx * dx + pOz * dz
                            val pedC = pOx * pOx + pOz * pOz - pedRadSq
                            val pedDisc = pedB * pedB - pedA * pedC
                            if (pedDisc >= 0.0) {
                                val tSide = (-pedB - sqrt(pedDisc)) / pedA
                                if (tSide > 0.15) {
                                    val py = oy + dy * tSide
                                    if (py in pedBotY..pedTopY) {
                                        val camZ = (tSide * ldz).toFloat()
                                        if (camZ < outD[idx]) {
                                            val shade = 0.08 + 0.08 * ((py - pedBotY) / (pedTopY - pedBotY))
                                            outR[idx] = (shade * 0.95).toFloat()
                                            outG[idx] = (shade * 1.00).toFloat()
                                            outB[idx] = (shade * 1.15).toFloat()
                                            outD[idx] = camZ
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2b. Fast double-sided Z-buffer triangle rasterization of the sculpted Milk Frog.
            val stripeEnd = yEnd - 1
            for (t in 0 until tCount) {
                if (!triVisible[t]) continue
                val tMaxY = triMaxY[t]
                if (tMaxY < yStart) continue
                val tMinY = triMinY[t]
                if (tMinY > stripeEnd) continue

                val base = t * 3
                var i0 = idxArr[base]
                var i1 = idxArr[base + 1]
                var i2 = idxArr[base + 2]

                var x0 = projX[i0]; var y0 = projY[i0]
                var x1 = projX[i1]; var y1 = projY[i1]
                var x2 = projX[i2]; var y2 = projY[i2]

                var area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                if (abs(area) < 1e-4f) continue
                // Double-sided: normalize winding to positive area so zero backface holes ever appear
                if (area < 0f) {
                    val tmpI = i1; i1 = i2; i2 = tmpI
                    val tmpX = x1; x1 = x2; x2 = tmpX
                    val tmpY = y1; y1 = y2; y2 = tmpY
                    area = -area
                }
                val invArea = 1f / area

                val iz0 = projInvZ[i0]; val iz1 = projInvZ[i1]; val iz2 = projInvZ[i2]
                val r0 = litR[i0]; val r1 = litR[i1]; val r2 = litR[i2]
                val g0 = litG[i0]; val g1 = litG[i1]; val g2 = litG[i2]
                val b0 = litB[i0]; val b1 = litB[i1]; val b2 = litB[i2]

                val yLo = max(yStart, tMinY)
                val yHi = min(stripeEnd, tMaxY)
                val xLo = triMinX[t]
                val xHi = triMaxX[t]

                val startPx = xLo + 0.5f
                val startPy = yLo + 0.5f
                var e0Row = (x2 - x1) * (startPy - y1) - (y2 - y1) * (startPx - x1)
                var e1Row = (x0 - x2) * (startPy - y2) - (y0 - y2) * (startPx - x2)
                var e2Row = (x1 - x0) * (startPy - y0) - (y1 - y0) * (startPx - x0)

                val stepX0 = -(y2 - y1); val stepY0 = x2 - x1
                val stepX1 = -(y0 - y2); val stepY1 = x0 - x2
                val stepX2 = -(y1 - y0); val stepY2 = x1 - x0

                for (py in yLo..yHi) {
                    var e0 = e0Row
                    var e1 = e1Row
                    var e2 = e2Row
                    val row = py * w
                    for (px in xLo..xHi) {
                        if (e0 >= -1e-4f && e1 >= -1e-4f && e2 >= -1e-4f) {
                            val w0 = e0 * invArea
                            val w1 = e1 * invArea
                            val w2 = e2 * invArea
                            val invZ = w0 * iz0 + w1 * iz1 + w2 * iz2
                            if (invZ > 1e-5f) {
                                val z = 1f / invZ
                                val idx = row + px
                                if (z < outD[idx]) {
                                    outD[idx] = z
                                    outR[idx] = w0 * r0 + w1 * r1 + w2 * r2
                                    outG[idx] = w0 * g0 + w1 * g1 + w2 * g2
                                    outB[idx] = w0 * b0 + w1 * b1 + w2 * b2
                                }
                            }
                        }
                        e0 += stepX0
                        e1 += stepX1
                        e2 += stepX2
                    }
                    e0Row += stepY0
                    e1Row += stepY1
                    e2Row += stepY2
                }
            }
        }

        // --- Stage 3: 3D-projected comic tears, belly laugh arcs, and near-foreground HDR bokeh & "HA!" ---
        fun projectWorld(wx: Double, wy: Double, wz: Double): Triple<Float, Float, Float>? {
            val vx = wx - ox; val vy = wy - oy; val vz = wz - oz
            val cx = r00 * vx + r10 * vy + r20 * vz
            val cy = r01 * vx + r11 * vy + r21 * vz
            val cz = r02 * vx + r12 * vy + r22 * vz
            if (cz < 0.15) return null
            val sx = (halfW + (cx / cz) * focal).toFloat()
            val sy = (halfH - (cy / cz) * focal).toFloat()
            return Triple(sx, sy, cz.toFloat())
        }

        fun drawDepthAwareOrb(cx: Int, cy: Int, radius: Int, cr: Float, cg: Float, cb: Float, d: Float, isHdr: Boolean) {
            val radF = radius.toFloat()
            val haloRad = if (isHdr) orbHaloRadius(radius) else radius
            val haloRadF = haloRad.toFloat()
            val xs = (cx - haloRad).coerceAtLeast(0)
            val xe = (cx + haloRad + 1).coerceAtMost(w)
            val ys = (cy - haloRad).coerceAtLeast(0)
            val ye = (cy + haloRad + 1).coerceAtMost(h)
            for (y in ys until ye) {
                val dy = (y - cy).toFloat()
                val row = y * w
                for (x in xs until xe) {
                    val dx = (x - cx).toFloat()
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist > haloRadF) continue
                    val idx = row + x
                    if (d > outD[idx]) continue
                    if (dist <= radF) {
                        val norm = dist / radF
                        val falloff = if (norm <= 0.45f) 1.0f - 0.10f * (norm / 0.45f) else 0.90f * (1f - ((norm - 0.45f) / 0.55f).let { it * it } * 0.65f)
                        outR[idx] = if (isHdr) max(outR[idx], cr * falloff) else cr * falloff
                        outG[idx] = if (isHdr) max(outG[idx], cg * falloff) else cg * falloff
                        outB[idx] = if (isHdr) max(outB[idx], cb * falloff) else cb * falloff
                        outD[idx] = d
                    } else if (isHdr) {
                        val u = (dist - radF) / (haloRadF - radF).coerceAtLeast(1f)
                        val halo = 0.20f * (1f - u) * (1f - u)
                        outR[idx] = min(max(outR[idx], cr), outR[idx] + cr * halo)
                        outG[idx] = min(max(outG[idx], cg), outG[idx] + cg * halo)
                        outB[idx] = min(max(outB[idx], cb), outB[idx] + cb * halo)
                    }
                }
            }
        }

        // 3a. Flying sparkling laugh tears arcing out from both eyes during laugh bursts
        if (anim.laughIntensity > 0.28) {
            for (side in intArrayOf(-1, 1)) {
                for (k in 0 until 2) {
                    val tp = (anim.tearPhase + k * 0.48) % 1.0
                    val localX = side * (0.095 + 0.22 * tp)
                    val localZ = -0.105 - 0.16 * tp
                    val tx = anim.worldX + PLACEMENT_COS * localX + PLACEMENT_SIN * localZ
                    val ty = anim.worldY + 0.19 + 0.10 * tp - 0.22 * tp * tp
                    val tz = anim.worldZ + PLACEMENT_COS * localZ - PLACEMENT_SIN * localX
                    val proj = projectWorld(tx, ty, tz) ?: continue
                    val rad = ((0.005 / proj.third) * focal).roundToInt().coerceIn(2, 6)
                    drawDepthAwareOrb(proj.first.roundToInt(), proj.second.roundToInt(), rad, 1.3f, 2.5f, 3.2f, proj.third, isHdr = true)
                }
            }
        }

        // 3b. 3 animated comic laugh-motion arcs beside Milk Frog's shaking belly
        if (anim.arcPulse > 0.05) {
            val arcBoost = (0.75 + 0.25 * anim.arcPulse).toFloat()
            for (side in intArrayOf(-1, 1)) {
                for (arcIdx in 0 until 3) {
                    val rArc = 0.24 + arcIdx * 0.036
                    for (s in -5..5) {
                        val ang = s * 0.085
                        val ax = anim.worldX + side * (rArc * cos(ang))
                        val ay = anim.worldY - 0.08 + rArc * sin(ang) * 0.85
                        val az = anim.worldZ - 0.10
                        val proj = projectWorld(ax, ay, az) ?: continue
                        val px = proj.first.roundToInt()
                        val py = proj.second.roundToInt()
                        for (dy in -2..2) {
                            val yy = py + dy
                            if (yy !in 0 until h) continue
                            for (dx in -2..2) {
                                val xx = px + dx
                                if (xx !in 0 until w || dx * dx + dy * dy > 4) continue
                                val idx = yy * w + xx
                                if (proj.third < outD[idx]) {
                                    outR[idx] = 0.96f * arcBoost
                                    outG[idx] = 0.86f * arcBoost
                                    outB[idx] = 0.28f * arcBoost
                                    outD[idx] = proj.third
                                }
                            }
                        }
                    }
                }
            }
        }

        // The same world-space emitters are projected into the GPU orb parameters above.
        for (lamp in STUDIO_LAMPS) {
            val proj = projectWorld(lamp.x, lamp.y, lamp.z) ?: continue
            val radius = ((lamp.radius / proj.third) * focal).roundToInt().coerceIn(lamp.minPx, lamp.maxPx)
            drawDepthAwareOrb(proj.first.roundToInt(), proj.second.roundToInt(), radius,
                lamp.r, lamp.g, lamp.b, proj.third, isHdr = true)
        }

        // 3d. Crisp foreground comic "HA!" plaque at 0.72 m
        val haProj = projectWorld(0.28, 0.22 + 0.015 * anim.laughIntensity, 0.72)
        if (haProj != null) {
            drawComicHaPlaque(
                outR, outG, outB, outD, w, h,
                cx = haProj.first.roundToInt(),
                cy = haProj.second.roundToInt(),
                scale = max(1, (min(w, h) / 145.0 * (0.72 / haProj.third)).roundToInt()),
                depthM = haProj.third,
            )
        }
    }

    private class StaticStageBuffer(
        val w: Int,
        val h: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val depth: FloatArray,
    )

    companion object {
        const val MESH_RESOURCE_PATH = "scenes/milk-frog-mesh.bin.gz"
        const val AUDIO_RESOURCE_PATH = "scenes/milk-frog-laugh.pcm.gz"
        private fun orbHaloRadius(radius: Int): Int =
            if (radius == 1) radius else (radius * 1.5f).roundToInt().coerceAtLeast(radius + 2)

        private data class StudioLamp(
            val x: Double, val y: Double, val z: Double, val radius: Double,
            val minPx: Int, val maxPx: Int, val r: Float, val g: Float, val b: Float,
        )

        // Upper rear spots are small emitters seen past the shoulders of the 1.55 m subject,
        // within both the 45 mm and the 110 mm field of view. The two footlights stay near-camera.
        private val STUDIO_LAMPS = listOf(
            StudioLamp(-1.48, 0.36, 7.5, 0.009, 1, 6, 38.0f, 26.0f, 13.0f),
            StudioLamp(1.72, 0.64, 8.2, 0.010, 1, 6, 16.0f, 32.0f, 40.0f),
            StudioLamp(-0.43, -0.23, 0.74, 0.021, 5, 22, 7.0f, 2.5f, 4.4f),
            StudioLamp(0.44, -0.22, 0.78, 0.021, 5, 22, 6.4f, 6.2f, 5.4f),
        )
        const val FOREGROUND_MIN_DEPTH_M = 0.62f
        const val HERO_NOMINAL_DEPTH_M = 1.55f
        // World X is right and +Z recedes. Looking down, the forward (-Z) axis turns clockwise towards -X.
        private const val PLACEMENT_COS = 0.8660254f
        private const val PLACEMENT_SIN = 0.5f
        const val MIDGROUND_MACBETH_DEPTH_M = 2.65f
        const val MIDGROUND_MTF_DEPTH_M = 2.85f
        const val BACKGROUND_MAX_DEPTH_M = 10.5f

        internal val BAKED_MESH: BakedFrogMesh by lazy { loadBakedMesh() }
        internal val BAKED_AUDIO: BakedLaughAudio by lazy { loadBakedAudio() }
        private const val BAKED_MESH_GPU_KEY: Long = 0x4D46_3344_5631_0002L
        private val PACKED_BAKED_MESH_VERTS: FloatArray by lazy {
            val m = BAKED_MESH
            val vc = m.vertexCount
            val out = FloatArray(vc * 12)
            var o = 0
            for (i in 0 until vc) {
                out[o] = m.posX[i]; out[o + 1] = m.posY[i]; out[o + 2] = m.posZ[i]; out[o + 3] = m.partId[i].toFloat()
                out[o + 4] = m.normX[i]; out[o + 5] = m.normY[i]; out[o + 6] = m.normZ[i]; out[o + 7] = 0f
                out[o + 8] = m.linR[i]; out[o + 9] = m.linG[i]; out[o + 10] = m.linB[i]; out[o + 11] = 0f
                o += 12
            }
            out
        }
        val DEFAULT_PERIOD_SECONDS: Double get() = BAKED_AUDIO.durationSeconds.coerceIn(0.5, 60.0)

        private val MACBETH_SRGB = intArrayOf(
            0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDBA,
            0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
            0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
            0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
        )

        private val STRIPE_WORKERS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        private val STRIPE_THREAD_COUNTER = AtomicInteger(1)
        private val STRIPE_POOL: ExecutorService? by lazy {
            val count = (STRIPE_WORKERS - 1).coerceAtLeast(0)
            if (count <= 0) {
                null
            } else {
                Executors.newFixedThreadPool(count) { r ->
                    Thread(r, "fuji-milkfrog-stripe-${STRIPE_THREAD_COUNTER.getAndIncrement()}").apply {
                        isDaemon = true
                    }
                }
            }
        }

        private fun parallelRows(height: Int, task: (yStart: Int, yEnd: Int) -> Unit) {
            val pool = STRIPE_POOL
            if (pool == null || height < 64 || STRIPE_WORKERS <= 1) {
                task(0, height)
                return
            }
            val stripes = min(STRIPE_WORKERS, height / 16).coerceAtLeast(1)
            if (stripes <= 1) {
                task(0, height)
                return
            }
            val chunk = (height + stripes - 1) / stripes
            val actualStripes = ((height + chunk - 1) / chunk).coerceAtLeast(1)
            val latch = CountDownLatch(actualStripes - 1)
            val firstError = AtomicReference<Throwable?>(null)
            for (s in 1 until actualStripes) {
                val y0 = s * chunk
                val y1 = min(height, y0 + chunk)
                pool.execute {
                    try {
                        if (firstError.get() == null) task(y0, y1)
                    } catch (err: Throwable) {
                        firstError.compareAndSet(null, err)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try {
                task(0, min(height, chunk))
            } catch (err: Throwable) {
                firstError.compareAndSet(null, err)
            } finally {
                var interrupted = false
                while (true) {
                    try {
                        latch.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            firstError.get()?.let { throw it }
        }

        private fun loadBakedMesh(): BakedFrogMesh {
            val stream = checkNotNull(MilkFrogSceneSource::class.java.classLoader.getResourceAsStream(MESH_RESOURCE_PATH)) {
                "Missing bundled Milk Frog mesh resource: $MESH_RESOURCE_PATH"
            }
            val raw = GZIPInputStream(stream).use { it.readAllBytes() }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val m0 = bb.get().toInt(); val m1 = bb.get().toInt(); val m2 = bb.get().toInt(); val m3 = bb.get().toInt()
            require(m0 == 'M'.code && m1 == 'F'.code && m2 == '3'.code && m3 == 'D'.code) {
                "Invalid Milk Frog mesh magic header"
            }
            val ver = bb.short.toInt() and 0xFFFF
            require(ver == 1) { "Unsupported Milk Frog mesh version: $ver" }
            bb.short // flags
            val vc = bb.int
            val tc = bb.int
            val minX = bb.float; val minY = bb.float; val minZ = bb.float
            val maxX = bb.float; val maxY = bb.float; val maxZ = bb.float

            val posX = FloatArray(vc); val posY = FloatArray(vc); val posZ = FloatArray(vc)
            val normX = FloatArray(vc); val normY = FloatArray(vc); val normZ = FloatArray(vc)
            val linR = FloatArray(vc); val linG = FloatArray(vc); val linB = FloatArray(vc)
            val partId = ByteArray(vc)

            val spanX = maxX - minX; val spanY = maxY - minY; val spanZ = maxZ - minZ
            val invQ16 = 0.5f / 32767.0f
            for (i in 0 until vc) posX[i] = minX + (bb.short.toFloat() * invQ16 + 0.5f) * spanX
            for (i in 0 until vc) posY[i] = minY + (bb.short.toFloat() * invQ16 + 0.5f) * spanY
            for (i in 0 until vc) posZ[i] = minZ + (bb.short.toFloat() * invQ16 + 0.5f) * spanZ

            val invQ8 = 1.0f / 127.0f
            for (i in 0 until vc) normX[i] = bb.get().toFloat() * invQ8
            for (i in 0 until vc) normY[i] = bb.get().toFloat() * invQ8
            for (i in 0 until vc) normZ[i] = bb.get().toFloat() * invQ8
            for (i in 0 until vc) {
                val len = sqrt(normX[i] * normX[i] + normY[i] * normY[i] + normZ[i] * normZ[i])
                if (len > 1e-5f) {
                    normX[i] /= len; normY[i] /= len; normZ[i] /= len
                } else {
                    normX[i] = 0f; normY[i] = 0f; normZ[i] = -1f
                }
            }

            val srgbLut = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT
            for (i in 0 until vc) linR[i] = srgbLut[bb.get().toInt() and 0xFF]
            for (i in 0 until vc) linG[i] = srgbLut[bb.get().toInt() and 0xFF]
            for (i in 0 until vc) linB[i] = srgbLut[bb.get().toInt() and 0xFF]
            bb.get(partId)

            val indices = IntArray(tc * 3)
            for (i in 0 until tc * 3) {
                indices[i] = bb.short.toInt() and 0xFFFF
            }
            return BakedFrogMesh(vc, tc, posX, posY, posZ, normX, normY, normZ, linR, linG, linB, partId, indices)
        }

        private fun loadBakedAudio(): BakedLaughAudio {
            val stream = checkNotNull(MilkFrogSceneSource::class.java.classLoader.getResourceAsStream(AUDIO_RESOURCE_PATH)) {
                "Missing bundled Milk Frog laugh audio resource: $AUDIO_RESOURCE_PATH"
            }
            val raw = GZIPInputStream(stream).use { it.readAllBytes() }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val m0 = bb.get().toInt(); val m1 = bb.get().toInt(); val m2 = bb.get().toInt(); val m3 = bb.get().toInt()
            require(m0 == 'M'.code && m1 == 'F'.code && m2 == 'A'.code && m3 == 'D'.code) {
                "Invalid Milk Frog audio magic header"
            }
            val ver = bb.short.toInt() and 0xFFFF
            require(ver == 1) { "Unsupported Milk Frog audio version: $ver" }
            val sr = bb.short.toInt() and 0xFFFF
            val count = bb.int
            val envBins = bb.int
            val env = FloatArray(envBins) { (bb.get().toInt() and 0xFF) / 255.0f }
            val samples = ShortArray(count)
            var acc = 0
            for (i in 0 until count) {
                acc = (acc + bb.short.toInt()) and 0xFFFF
                samples[i] = acc.toShort()
            }
            return BakedLaughAudio(sr, samples, env)
        }

        private fun wrapPhase(phase: Double): Double {
            val m = phase % 1.0
            return if (m < 0.0) m + 1.0 else m
        }

        internal fun parseAndOpen(
            sourceRef: String,
            width: Int,
            height: Int,
            loop: Boolean,
            nanoClock: () -> Long = System::nanoTime,
        ): MilkFrogSceneSource {
            val query = sourceRef.substringAfter('?', "")
            var motion = true
            var audio = true
            var phase = 0.0
            var period = DEFAULT_PERIOD_SECONDS
            if (query.isNotEmpty()) {
                for (part in query.split('&')) {
                    if (part.isEmpty()) continue
                    val eq = part.indexOf('=')
                    require(eq > 0) { "Invalid scene://milk-frog query parameter: '$part'" }
                    val key = part.substring(0, eq).trim().lowercase(Locale.ROOT)
                    val value = part.substring(eq + 1).trim().lowercase(Locale.ROOT)
                    when (key) {
                        "motion" -> motion = when (value) {
                            "on", "true", "1" -> true
                            "off", "false", "0" -> false
                            else -> throw IllegalArgumentException("Invalid scene://milk-frog motion value: '$value'")
                        }
                        "audio", "sound" -> audio = when (value) {
                            "on", "true", "1" -> true
                            "off", "false", "0" -> false
                            else -> throw IllegalArgumentException("Invalid scene://milk-frog audio value: '$value'")
                        }
                        "phase" -> {
                            val p = value.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid scene://milk-frog phase: '$value'")
                            require(p.isFinite()) { "scene://milk-frog phase must be finite" }
                            phase = wrapPhase(p)
                        }
                        "period" -> {
                            val sec = value.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid scene://milk-frog period: '$value'")
                            require(sec.isFinite() && sec in 0.5..60.0) {
                                "scene://milk-frog period must be in 0.5..60.0 s (got $sec)"
                            }
                            period = sec
                        }
                        else -> throw IllegalArgumentException("Unknown scene://milk-frog parameter: '$key'")
                    }
                }
            }
            return MilkFrogSceneSource(
                width = width,
                height = height,
                loop = loop,
                motionEnabled = motion,
                audioEnabled = audio,
                initialPhase = phase,
                periodSeconds = period,
                nanoClock = nanoClock,
                activeSourceRef = sourceRef.trim(),
            )
        }

        /**
         * Computes 60 fps belly-laugh deformation state synced directly to the real viral laugh
         * audio's 128-bin RMS envelope (`BAKED_AUDIO.sampleEnvelope(phase)`).
         */
        internal fun computeAnimState(phase: Double): FrogAnimState {
            val p = wrapPhase(phase)
            val twoPi = 2.0 * PI
            val audioEnv = BAKED_AUDIO.sampleEnvelope(p)
            val laughIntensity = (0.20 + 0.80 * audioEnv).coerceIn(0.0, 1.0)

            val rapidShake = sin(p * twoPi * 12.0)
            val rapidShake2 = sin(p * twoPi * 6.0 + 0.6)

            // Gentle figure-8 lateral X + depth Z sway for continuous AF-C Eye-AF tracking
            val worldX = 0.068 * sin(p * twoPi) + 0.012 * laughIntensity * rapidShake2
            val worldZ = HERO_NOMINAL_DEPTH_M.toDouble() - 0.08 * cos(p * twoPi) // ~1.30..1.46 m
            val shakeY = 0.018 * laughIntensity * abs(rapidShake)
            val worldY = shakeY

            // The upper body folds towards the lens on a syllable, then recoils as the belly expands.
            val exhale = laughIntensity * (0.5 + 0.5 * rapidShake)
            val leanForward = 0.04 - 0.24 * exhale
            val rockSide = 0.095 * laughIntensity * rapidShake2
            val bellySquash = 0.084 * laughIntensity * rapidShake
            val mouthVibration = 0.15 + 1.65 * exhale
            val kickPhase = sin(p * twoPi * 7.0) * laughIntensity
            val tearPhase = (p * 5.0) % 1.0
            val arcPulse = if (laughIntensity > 0.42) (0.55 + 0.45 * abs(rapidShake)) else 0.0

            return FrogAnimState(
                phase = p,
                worldX = worldX,
                worldY = worldY,
                worldZ = worldZ,
                audioEnvelope = audioEnv,
                laughIntensity = laughIntensity,
                shakeY = shakeY,
                leanForward = leanForward,
                rockSide = rockSide,
                bellySquash = bellySquash,
                mouthVibration = mouthVibration,
                kickPhase = kickPhase,
                tearPhase = tearPhase,
                arcPulse = arcPulse,
            )
        }

        private fun buildStaticStage(w: Int, h: Int): StaticStageBuffer {
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            val depth = FloatArray(w * h) { BACKGROUND_MAX_DEPTH_M }

            // 1. Deep radial twilight studio backdrop (10.5 m) with soft spotlight & floor reflection
            val cxF = w * 0.50f
            val cyF = h * 0.44f
            for (y in 0 until h) {
                val ny = (y - cyF) / max(1f, h * 0.55f)
                val tY = y.toFloat() / max(1, h - 1).toFloat()
                val row = y * w
                for (x in 0 until w) {
                    val nx = (x - cxF) / max(1f, w * 0.55f)
                    val rad2 = nx * nx + ny * ny
                    val spot = exp(-rad2 * 1.45f)
                    val isFloor = tY > 0.74f
                    val i = row + x
                    if (isFloor) {
                        val ft = ((tY - 0.74f) / 0.26f).coerceIn(0f, 1f)
                        val gridX = abs(((x.toFloat() / w) * 14f) - floor((x.toFloat() / w) * 14f + 0.5f))
                        val lineBoost = if (gridX < 0.025f) 0.018f else 0f
                        r[i] = 0.020f + 0.025f * spot * (1f - ft * 0.4f) + lineBoost
                        g[i] = 0.024f + 0.028f * spot * (1f - ft * 0.4f) + lineBoost
                        b[i] = 0.040f + 0.042f * spot * (1f - ft * 0.4f) + lineBoost * 1.2f
                        depth[i] = 4.2f - ft * 2.2f
                    } else {
                        r[i] = 0.014f + 0.028f * spot
                        g[i] = 0.020f + 0.036f * spot
                        b[i] = 0.048f + 0.068f * spot
                        depth[i] = BACKGROUND_MAX_DEPTH_M
                    }
                }
            }

            // 2. 20 Staggered Multi-Depth HDR Specular Bokeh Lights (4.2 m, 6.0 m, 8.2 m, 10.5 m; radiance 4.8..9.2)
            fun drawHdrOrb(cx: Int, cy: Int, radius: Int, cr: Float, cg: Float, cb: Float, d: Float) {
                val radF = radius.toFloat().coerceAtLeast(2f)
                val haloRad = (radF * 1.60f).roundToInt().coerceAtLeast(radius + 2)
                val haloRadF = haloRad.toFloat()
                val xs = (cx - haloRad).coerceAtLeast(0)
                val xe = (cx + haloRad + 1).coerceAtMost(w)
                val ys = (cy - haloRad).coerceAtLeast(0)
                val ye = (cy + haloRad + 1).coerceAtMost(h)
                for (y in ys until ye) {
                    val dy = (y - cy).toFloat()
                    val row = y * w
                    for (x in xs until xe) {
                        val dx = (x - cx).toFloat()
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist > haloRadF) continue
                        val idx = row + x
                        if (dist <= radF) {
                            val norm = dist / radF
                            val falloff = if (norm <= 0.45f) {
                                1.0f - 0.12f * (norm / 0.45f)
                            } else {
                                val u = (norm - 0.45f) / 0.55f
                                0.88f * (1f - u * u * 0.68f)
                            }.coerceIn(0f, 1f)
                            r[idx] = max(r[idx], cr * falloff)
                            g[idx] = max(g[idx], cg * falloff)
                            b[idx] = max(b[idx], cb * falloff)
                            depth[idx] = d
                        } else {
                            val u = (dist - radF) / (haloRadF - radF).coerceAtLeast(1f)
                            val halo = 0.22f * (1f - u) * (1f - u)
                            r[idx] = min(max(r[idx], cr), r[idx] + cr * halo)
                            g[idx] = min(max(g[idx], cg), g[idx] + cg * halo)
                            b[idx] = min(max(b[idx], cb), b[idx] + cb * halo)
                            if (d < depth[idx]) depth[idx] = d
                        }
                    }
                }
            }

            val bgOrbs = arrayOf(
                floatArrayOf(0.08f, 0.11f, 0.013f, 10.5f, 8.6f, 6.2f, 1.8f),
                floatArrayOf(0.22f, 0.09f, 0.011f, 10.5f, 2.2f, 7.6f, 8.8f),
                floatArrayOf(0.36f, 0.08f, 0.012f, 10.5f, 8.4f, 3.0f, 5.2f),
                floatArrayOf(0.50f, 0.07f, 0.011f, 10.5f, 8.0f, 7.8f, 7.4f),
                floatArrayOf(0.64f, 0.09f, 0.012f, 10.5f, 9.0f, 4.4f, 1.4f),
                floatArrayOf(0.78f, 0.10f, 0.011f, 10.5f, 2.4f, 8.2f, 5.4f),
                floatArrayOf(0.92f, 0.12f, 0.013f, 10.5f, 8.2f, 6.0f, 2.0f),
                floatArrayOf(0.14f, 0.21f, 0.015f, 8.2f, 7.8f, 3.2f, 4.6f),
                floatArrayOf(0.30f, 0.18f, 0.014f, 8.2f, 7.6f, 7.2f, 6.4f),
                floatArrayOf(0.43f, 0.15f, 0.013f, 8.2f, 8.2f, 5.6f, 1.8f),
                floatArrayOf(0.57f, 0.15f, 0.013f, 8.2f, 2.0f, 6.8f, 8.2f),
                floatArrayOf(0.71f, 0.18f, 0.014f, 8.2f, 8.0f, 3.8f, 1.4f),
                floatArrayOf(0.87f, 0.21f, 0.015f, 8.2f, 7.4f, 7.6f, 8.0f),
                floatArrayOf(0.06f, 0.30f, 0.017f, 6.0f, 6.8f, 5.2f, 1.6f),
                floatArrayOf(0.28f, 0.31f, 0.015f, 6.0f, 2.0f, 6.4f, 7.2f),
                floatArrayOf(0.72f, 0.30f, 0.015f, 6.0f, 7.2f, 2.6f, 4.4f),
                floatArrayOf(0.94f, 0.31f, 0.017f, 6.0f, 7.0f, 4.0f, 1.3f),
                floatArrayOf(0.31f, 0.65f, 0.016f, 4.2f, 5.8f, 4.6f, 1.6f),
                floatArrayOf(0.69f, 0.64f, 0.016f, 4.2f, 2.2f, 5.6f, 6.4f),
                floatArrayOf(0.91f, 0.66f, 0.015f, 4.2f, 6.2f, 3.0f, 4.2f),
            )
            for (o in bgOrbs) {
                drawHdrOrb(
                    cx = (w * o[0]).roundToInt(),
                    cy = (h * o[1]).roundToInt(),
                    radius = (min(w, h) * o[2]).roundToInt().coerceAtLeast(3),
                    cr = o[4], cg = o[5], cb = o[6], d = o[3],
                )
            }

            fun fillRect(x0: Int, y0: Int, rw: Int, rh: Int, cr: Float, cg: Float, cb: Float, d: Float) {
                val xs = x0.coerceAtLeast(0)
                val xe = (x0 + rw).coerceAtMost(w)
                val ys = y0.coerceAtLeast(0)
                val ye = (y0 + rh).coerceAtMost(h)
                for (y in ys until ye) {
                    val row = y * w
                    for (x in xs until xe) {
                        val idx = row + x
                        r[idx] = cr; g[idx] = cg; b[idx] = cb; depth[idx] = d
                    }
                }
            }

            // 3. Midground Left Studio Easel (2.65 m): 24-Patch Macbeth ColorChecker + 12-Step Grayscale Wedge
            val macX = (w * 0.035f).roundToInt()
            val macY = (h * 0.36f).roundToInt()
            val macW = (w * 0.225f).roundToInt()
            val macH = (h * 0.37f).roundToInt()
            fillRect(macX + macW / 2 - 4, macY + macH, 8, (h * 0.16f).roundToInt(), 0.06f, 0.065f, 0.075f, MIDGROUND_MACBETH_DEPTH_M)
            fillRect(macX - 5, macY - 5, macW + 10, macH + 10, 0.16f, 0.17f, 0.19f, MIDGROUND_MACBETH_DEPTH_M)
            fillRect(macX, macY, macW, macH, 0.025f, 0.025f, 0.028f, MIDGROUND_MACBETH_DEPTH_M)
            val mChartH = (macH * 0.72f).roundToInt()
            val mPad = (min(macW, mChartH) / 24).coerceAtLeast(2)
            val mCellW = ((macW - mPad * 7) / 6).coerceAtLeast(3)
            val mCellH = ((mChartH - mPad * 5) / 4).coerceAtLeast(3)
            val lut = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT
            for (mr in 0 until 4) {
                for (mc in 0 until 6) {
                    val rgb24 = MACBETH_SRGB[mr * 6 + mc]
                    fillRect(
                        macX + mPad + mc * (mCellW + mPad),
                        macY + mPad + mr * (mCellH + mPad),
                        mCellW, mCellH,
                        lut[(rgb24 ushr 16) and 0xFF],
                        lut[(rgb24 ushr 8) and 0xFF],
                        lut[rgb24 and 0xFF],
                        MIDGROUND_MACBETH_DEPTH_M,
                    )
                }
            }
            val wedgeY = macY + mChartH + 4
            val wedgeH = (macH - mChartH - 8).coerceAtLeast(6)
            val stepW = ((macW - 8) / 12).coerceAtLeast(2)
            for (s in 0 until 12) {
                val c8 = ((s * 245) / 11 + 6).coerceIn(0, 255)
                val v = lut[c8]
                fillRect(macX + 4 + s * stepW, wedgeY, (stepW - 1).coerceAtLeast(1), wedgeH, v, v, v, MIDGROUND_MACBETH_DEPTH_M)
            }

            // 4. Midground Right Studio Easel (2.85 m): Slanted-Edge MTF + Siemens Star + Line-Pair Resolution/Moiré Target
            val mtfX = (w * 0.74f).roundToInt()
            val mtfY = (h * 0.36f).roundToInt()
            val mtfW = (w * 0.225f).roundToInt()
            val mtfH = (h * 0.37f).roundToInt()
            fillRect(mtfX + mtfW / 2 - 4, mtfY + mtfH, 8, (h * 0.16f).roundToInt(), 0.06f, 0.065f, 0.075f, MIDGROUND_MTF_DEPTH_M)
            fillRect(mtfX - 5, mtfY - 5, mtfW + 10, mtfH + 10, 0.16f, 0.17f, 0.19f, MIDGROUND_MTF_DEPTH_M)
            fillRect(mtfX, mtfY, mtfW, mtfH, 0.86f, 0.86f, 0.86f, MIDGROUND_MTF_DEPTH_M)
            val seW = (mtfW * 0.40f).roundToInt()
            val seH = (mtfH * 0.56f).roundToInt()
            val seX = mtfX + 8; val seY = mtfY + 8
            for (dy in 0 until seH) {
                val skew = (dy * 0.105).roundToInt()
                val row = (seY + dy) * w
                for (dx in 0 until seW) {
                    if (dx + skew < seW / 2) {
                        val idx = row + seX + dx
                        r[idx] = 0.03f; g[idx] = 0.03f; b[idx] = 0.035f; depth[idx] = MIDGROUND_MTF_DEPTH_M
                    }
                }
            }
            val starCx = mtfX + (mtfW * 0.73f).roundToInt()
            val starCy = mtfY + (mtfH * 0.33f).roundToInt()
            val starRad = (min(mtfW, mtfH) * 0.24f).roundToInt().coerceAtLeast(8)
            val starR2 = starRad * starRad
            val innerR2 = max(4, (starRad / 8) * (starRad / 8))
            for (y in max(0, starCy - starRad)..min(h - 1, starCy + starRad)) {
                val dy = (y - starCy).toDouble()
                val row = y * w
                for (x in max(0, starCx - starRad)..min(w - 1, starCx + starRad)) {
                    val dx = (x - starCx).toDouble()
                    val d2 = dx * dx + dy * dy
                    if (d2 <= starR2) {
                        val v = if (d2 <= innerR2) 0.04f else if (sin(atan2(dy, dx) * 18.0) >= 0.0) 0.90f else 0.03f
                        val idx = row + x
                        r[idx] = v; g[idx] = v; b[idx] = v; depth[idx] = MIDGROUND_MTF_DEPTH_M
                    }
                }
            }
            val lpX = mtfX + 8
            val lpW = (mtfW - 16).coerceAtLeast(8)
            val lpY = mtfY + (mtfH * 0.68f).roundToInt()
            val lpH = (mtfH * 0.24f).roundToInt().coerceAtLeast(8)
            for (dx in 0 until lpW) {
                val u = dx.toDouble() / lpW.toDouble()
                val phase = 2.0 * PI * (4.0 * u + 32.0 * u * u)
                val v = if (cos(phase) >= 0.0) 0.90f else 0.03f
                for (dy in 0 until lpH) {
                    val idx = (lpY + dy) * w + (lpX + dx)
                    r[idx] = v; g[idx] = v; b[idx] = v; depth[idx] = MIDGROUND_MTF_DEPTH_M
                }
            }

            return StaticStageBuffer(w, h, r, g, b, depth)
        }

        private val HA_GLYPH_H = intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
        private val HA_GLYPH_A = intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
        private val HA_GLYPH_BANG = intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100)

        private fun drawComicHaPlaque(
            r: FloatArray,
            g: FloatArray,
            b: FloatArray,
            depth: FloatArray,
            w: Int,
            h: Int,
            cx: Int,
            cy: Int,
            scale: Int,
            depthM: Float,
        ) {
            val glyphs = arrayOf(HA_GLYPH_H, HA_GLYPH_A, HA_GLYPH_BANG)
            val totalCols = 5 * 3 + 2
            val totalRows = 7
            val pad = scale * 2
            val bw = totalCols * scale + pad * 2
            val bh = totalRows * scale + pad * 2
            val x0 = cx - bw / 2
            val y0 = cy - bh / 2

            for (py in max(0, y0) until min(h, y0 + bh)) {
                val row = py * w
                val borderY = (py - y0) < scale || (y0 + bh - 1 - py) < scale
                for (px in max(0, x0) until min(w, x0 + bw)) {
                    val idx = row + px
                    if (depthM >= depth[idx]) continue
                    val borderX = (px - x0) < scale || (x0 + bw - 1 - px) < scale
                    if (borderX || borderY) {
                        r[idx] = 0.08f; g[idx] = 0.07f; b[idx] = 0.07f
                    } else {
                        r[idx] = 0.92f; g[idx] = 0.86f; b[idx] = 0.36f
                    }
                    depth[idx] = depthM
                }
            }

            var colOff = 0
            for (glyph in glyphs) {
                for (gr in 0 until 7) {
                    val bits = glyph[gr]
                    for (gc in 0 until 5) {
                        if (((bits ushr (4 - gc)) and 1) == 0) continue
                        val gx0 = x0 + pad + (colOff + gc) * scale
                        val gy0 = y0 + pad + gr * scale
                        for (sy in max(0, gy0) until min(h, gy0 + scale)) {
                            val row = sy * w
                            for (sx in max(0, gx0) until min(w, gx0 + scale)) {
                                val idx = row + sx
                                if (depthM <= depth[idx]) {
                                    r[idx] = 0.82f; g[idx] = 0.10f; b[idx] = 0.14f
                                    depth[idx] = depthM
                                }
                            }
                        }
                    }
                }
                colOff += 6
            }
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
