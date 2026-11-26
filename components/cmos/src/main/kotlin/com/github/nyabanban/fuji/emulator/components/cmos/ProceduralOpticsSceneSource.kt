package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource
import com.github.nyabanban.fuji.emulator.api.SceneSource3D
import com.github.nyabanban.fuji.emulator.api.SceneSourceCapability
import com.github.nyabanban.fuji.emulator.api.SceneSourceProvider
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Built-in zero-external-file multi-depth optical test scenes for [ProceduralOpticsSceneSource].
 */
enum class OpticalScenePreset(val id: String, val uri: String) {
    QR_BALL("qr-ball", "scene://qr-ball"),
    MONA_LISA("mona-lisa", "scene://mona-lisa"),
    MILK_FROG("milk-frog", "scene://milk-frog"),
    DEPTH_STUDIO("depth-studio", "scene://depth-studio"),
    BOKEH_CITY("bokeh-city", "scene://bokeh-city"),
    STAR_BOKEH("star-bokeh", "scene://star-bokeh"),
    RESOLUTION_CHART("resolution-chart", "scene://resolution-chart");

    companion object {
        fun parse(sourceRef: String): OpticalScenePreset {
            val trimmed = sourceRef.trim()
            require(trimmed.isNotEmpty()) {
                "optical-scene sourceFile must specify a preset URI (${entries.joinToString(", ") { it.uri }})"
            }
            require(trimmed.none { it.isISOControl() || it.isWhitespace() }) {
                "optical-scene URI must not contain whitespace or control characters: $sourceRef"
            }
            require(trimmed.startsWith("scene://", ignoreCase = true)) {
                "optical-scene sourceFile must use scene:// scheme (expected one of ${entries.joinToString(", ") { it.uri }})"
            }
            val name = trimmed.substringAfter("://").substringBefore('?').trim().lowercase()
            return entries.firstOrNull { it.id == name }
                ?: throw IllegalArgumentException(
                    "Unknown optical-scene preset '$trimmed'; expected one of ${entries.joinToString(", ") { it.uri }}",
                )
        }
    }
}

/**
 * Built-in procedural multi-depth [SceneSource] + [LinearRgbFrameSource] (`sourceKind = "optical-scene"`)
 * requiring zero external image or video files.
 *
 * Every preset synthesizes a calibrated planar linear-RGB frame (`red`, `green`, `blue` in `[0.0f, 1.0f]`)
 * paired with a real per-pixel metric depth buffer (`depthMeters: FloatArray`) spanning three distinct
 * depth planes so lens templates, focal length zoom, aperture bokeh, and focus distance sweeps produce
 * immediate, physically interpretable optical responses:
 * - **Foreground plane (`0.8 m`)**: high-contrast focus chart / Siemens star / calibration scale
 * - **Midground plane (`2.5 m`)**: primary subject, 24-patch Macbeth ColorChecker / resolution wedges
 * - **Background plane (`12.0 m`)**: architectural skyline & high-intensity specular bokeh light orbs
 *
 * The synthesized [LinearRgbFrame] and packed Bayer readout buffer are cached in memory so continuous
 * live-view looping (`loop = true`) runs with zero per-frame allocations.
 */
open class ProceduralOpticsSceneSource internal constructor(
    val preset: OpticalScenePreset,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    private val loop: Boolean = true,
    private val rotation: QrBallRotation? = null,
    override val activeSourceRef: String = preset.uri,
) : SceneSource3D, LinearRgbFrameSource, SceneSourceTelemetry {
    @Volatile
    var exhausted: Boolean = false
        private set

    @Volatile
    private var closed: Boolean = false
    private val baseLinearFrame by lazy { synthesizeScene(preset, width, height) }
    private val baseRadianceFrame by lazy {
        baseLinearFrame.toRadianceFrame(pose = CameraPose3D.IDENTITY, sequence = 1L)
    }

    @Volatile
    final override var currentPose: CameraPose3D = CameraPose3D.IDENTITY
        private set

    private var lastLinearFrame: LinearRgbFrame? = null
    private val activeLinearFrame: LinearRgbFrame get() = lastLinearFrame ?: baseLinearFrame
    private var gpuFrameKey: Long? = null
    private var cachedBallSpec: GpuScene3dRenderSpec.QrBall? = null
    private var rotationFrame: Long = -1L
    private val ballCanvases by lazy { Array(2) { BallCanvas(width, height) } }
    private var ballSlot = 0
    private var activePoseKey: Long = CameraPose3D.IDENTITY.quantizedViewKey()
    @Volatile
    private var poseSequence: Long = 1L
    private var poseCanvas: SceneCanvas? = null
    private var cachedPoseFrame: LinearRadianceFrame? = null
    private val packedBaseRgbaDepth: FloatArray by lazy {
        val n = width * height
        val out = FloatArray(n * 4)
        val r = baseLinearFrame.red
        val g = baseLinearFrame.green
        val b = baseLinearFrame.blue
        val d = baseLinearFrame.depthMeters
        var o = 0
        for (i in 0 until n) {
            out[o] = r[i]
            out[o + 1] = g[i]
            out[o + 2] = b[i]
            out[o + 3] = d?.get(i) ?: BACKGROUND_DEPTH_M
            o += 4
        }
        out
    }
    private val layeredGpuBaseKey: Long = (preset.ordinal.toLong() + 1L shl 48) xor
        (width.toLong() shl 24) xor (height.toLong() shl 8) xor 0x4C59_5244L

    // Single-slot packed Bayer cache for O(1) bulk copies during looping exposures.
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedBitDepth: Int = -1
    private var cachedBayer: BayerPattern? = null
    private var cachedGainBits: Long = 0L
    private var cachedRotationFrame = -1L
    private var cachedBayerPoseKey: Long = 0L
    private var cachedBytes: ByteArray? = null

    override val sourceWidth: Int get() = width
    override val sourceHeight: Int get() = height
    override val hasDepthBuffer: Boolean get() = true
    override val lastSourceError: String? get() = null
    override val framesReceived: Long get() = poseSequence
    override val framesDropped: Long get() = 0L

    @Synchronized
    override fun renderForPose(pose: CameraPose3D): LinearRadianceFrame {
        check(!closed) { "Procedural optics scene source is closed" }
        currentPose = pose
        if (rotation == null && !pose.hasViewTransform) {
            lastLinearFrame = baseLinearFrame
            activePoseKey = CameraPose3D.IDENTITY.quantizedViewKey()
            return if (pose.isIdentity) {
                baseRadianceFrame
            } else {
                baseLinearFrame.toRadianceFrame(
                    dimension = SceneDimension.DIM_3D,
                    pose = pose,
                    sequence = poseSequence,
                )
            }
        }
        val key = pose.quantizedViewKey()
        val frame = rotation?.frame() ?: 0L
        val existing = cachedPoseFrame
        if (existing != null && activePoseKey == key && rotationFrame == frame) {
            return if (existing.pose == pose) {
                existing
            } else {
                activeLinearFrame.toRadianceFrame(
                    dimension = SceneDimension.DIM_3D,
                    pose = pose,
                    sequence = poseSequence,
                )
            }
        }
        val rendered = if (preset == OpticalScenePreset.QR_BALL) {
            val slot = ballCanvases[ballSlot]
            ballSlot = 1 - ballSlot
            val sameView = slot.poseKey == key
            slot.poseKey = key
            buildQrBallForPose(width, height, pose, slot.canvas, rotation?.turns(frame) ?: 0f, slot.spans, sameView)
        } else {
            val canvas = poseCanvas ?: SceneCanvas(width, height).also { poseCanvas = it }
            reprojectLayeredSceneForPose(baseLinearFrame, pose, canvas)
        }
        rotationFrame = frame
        poseSequence++
        lastLinearFrame = rendered
        activePoseKey = key
        val radiance = rendered.toRadianceFrame(
            dimension = SceneDimension.DIM_3D,
            pose = pose,
            sequence = poseSequence,
        )
        cachedPoseFrame = radiance
        return radiance
    }

    @Synchronized
    override fun gpuScene3dRenderSpec(pose: CameraPose3D): GpuScene3dRenderSpec {
        check(!closed) { "Procedural optics scene source is closed" }
        currentPose = pose
        val poseKey = pose.quantizedViewKey()
        val frame = rotation?.frame() ?: 0L
        val turns = rotation?.turns(frame) ?: 0f
        val sceneKey = ((poseKey * 0x9E3779B97F4A7C15UL.toLong()) xor layeredGpuBaseKey xor
            turns.toRawBits().toLong()).let { if (it == 0L) 1L else it }
        if (gpuFrameKey != sceneKey) {
            poseSequence++
            gpuFrameKey = sceneKey
        }
        if (preset == OpticalScenePreset.QR_BALL) {
            cachedBallSpec?.let { if (it.sceneKey == sceneKey && it.pose == pose) return it }
            val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
            val focal = width * 0.60 * focalScale
            val wallZ = QR_BALL_WALL_DEPTH_M.toDouble()
            val roomHalfX = 4.2
            val floorY = -1.75
            val ceilY = 2.35
            val ox = pose.posXM.coerceIn(-roomHalfX + 0.25, roomHalfX - 0.25)
            val oy = pose.posYM.coerceIn(floorY + 0.25, ceilY - 0.25)
            val oz = pose.posZM.coerceIn(-2.0, wallZ - 0.40)

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

            val qrMods = QR_BALL_MATRIX.size
            val quiet = 4
            return GpuScene3dRenderSpec.QrBall(
                width = width,
                height = height,
                pose = pose,
                sceneKey = sceneKey,
                sequence = poseSequence,
                qrKey = QR_BALL_MATRIX_GPU_KEY,
                qrRowBits32 = QR_BALL_ROW_BITS_32,
                qrModules = qrMods,
                quietZone = quiet,
                totalQrModules = qrMods + 2 * quiet,
                halfW = (width * 0.5).toFloat(),
                halfH = (height * 0.5).toFloat(),
                focal = focal.toFloat(),
                ox = ox.toFloat(),
                oy = oy.toFloat(),
                oz = oz.toFloat(),
                r00 = r00.toFloat(),
                r01 = r01.toFloat(),
                r02 = r02.toFloat(),
                r10 = r10.toFloat(),
                r11 = r11.toFloat(),
                r12 = r12.toFloat(),
                r20 = r20.toFloat(),
                r21 = r21.toFloat(),
                r22 = r22.toFloat(),
                wallZ = QR_BALL_WALL_DEPTH_M,
                rotationTurns = turns,
            ).also { cachedBallSpec = it }
        }
        val halfW = (width - 1) * 0.5
        val halfH = (height - 1) * 0.5
        val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
        val invFocalScale = 1.0 / focalScale
        val focal = width * 0.85 * focalScale
        val rollRad = Math.toRadians(pose.rollDeg)
        val yawShiftPx = tan(Math.toRadians(pose.yawDeg.coerceIn(-60.0, 60.0))) * focal
        val pitchShiftPx = tan(Math.toRadians(pose.pitchDeg.coerceIn(-60.0, 60.0))) * focal
        val hasTranslation = abs(pose.posXM) > 1e-6 || abs(pose.posYM) > 1e-6 || abs(pose.posZM) > 1e-6
        return GpuScene3dRenderSpec.LayeredStudio(
            width = width,
            height = height,
            pose = pose,
            sceneKey = sceneKey,
            sequence = poseSequence,
            baseKey = layeredGpuBaseKey,
            baseRgbaDepthProvider = { packedBaseRgbaDepth },
            hasPose = pose.hasViewTransform,
            hasTranslation = hasTranslation,
            halfW = halfW.toFloat(),
            halfH = halfH.toFloat(),
            invFocalScale = invFocalScale.toFloat(),
            focal = focal.toFloat(),
            cosR = cos(rollRad).toFloat(),
            sinR = sin(rollRad).toFloat(),
            yawShiftPx = yawShiftPx.toFloat(),
            pitchShiftPx = pitchShiftPx.toFloat(),
            posXM = pose.posXM.toFloat(),
            posYM = pose.posYM.toFloat(),
            posZM = pose.posZM.toFloat(),
            bgDepthM = BACKGROUND_DEPTH_M,
        )
    }

    @Synchronized
    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed) { "Procedural optics scene source is closed" }
        check(!exhausted) { "Procedural optics scene source exhausted" }
        StaticImageSceneSource.validateExposure(exposure)
        val bytesPerSample = SceneFrameCodec.validateGeometryAndDestination(geometry, destination)
        val requiredBytes = geometry.width * geometry.height * bytesPerSample

        val gain = SceneFrameCodec.exposureGain(exposure)
        val gainBits = if (gain == 0.0) 0L else gain.toRawBits()
        if (rotation != null) renderForPose(currentPose)
        val poseKey = activePoseKey
        val cached = cachedBytes
        val frameBytes = if (
            cached != null &&
            cachedWidth == geometry.width &&
            cachedHeight == geometry.height &&
            cachedBitDepth == geometry.bitDepth &&
            cachedBayer == geometry.bayer &&
            cachedGainBits == gainBits &&
            cachedBayerPoseKey == poseKey && cachedRotationFrame == rotationFrame
        ) {
            cached
        } else {
            val target = if (cached != null && cached.size == requiredBytes) cached else ByteArray(requiredBytes)
            renderLinearToBayer(activeLinearFrame, geometry, gain, bytesPerSample, target)
            cachedWidth = geometry.width
            cachedHeight = geometry.height
            cachedBitDepth = geometry.bitDepth
            cachedBayer = geometry.bayer
            cachedGainBits = gainBits
            cachedBayerPoseKey = poseKey
            cachedRotationFrame = rotationFrame
            cachedBytes = target
            target
        }

        destination.put(frameBytes)
        if (!loop) {
            exhausted = true
        }
    }

    @Synchronized
    override fun latestLinearRgbFrame(): LinearRgbFrame {
        check(!closed) { "Procedural optics scene source is closed" }
        if (rotation != null) renderForPose(currentPose)
        return activeLinearFrame
    }

    @Synchronized
    override fun close() {
        closed = true
        lastLinearFrame = null
        gpuFrameKey = null
        cachedBallSpec = null
        rotationFrame = -1L
        cachedBytes = null
        cachedPoseFrame = null
        poseCanvas = null
        cachedWidth = -1
        cachedHeight = -1
        cachedBitDepth = -1
        cachedBayer = null
        cachedGainBits = 0L
        cachedRotationFrame = -1L
        cachedBayerPoseKey = 0L
    }

    companion object {
        const val KIND = "optical-scene"
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 576
        const val FOREGROUND_DEPTH_M = 0.8f
        const val MIDGROUND_DEPTH_M = 2.5f
        const val BACKGROUND_DEPTH_M = 12.0f
        const val QR_BALL_PAYLOAD = "FUJIFILM GFX100S QR-BALL 5.5M"
        const val QR_BALL_WALL_DEPTH_M = 5.5f
        const val QR_BALL_SPHERE_CENTER_Z_M = 1.05f
        const val QR_BALL_SPHERE_RADIUS_M = 0.22f

        // Standard 24-patch Macbeth ColorChecker sRGB reference triplets (4 rows x 6 cols).
        private val MACBETH_SRGB = intArrayOf(
            0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDBA,
            0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
            0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
            0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
        )

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

        private class BallCanvas(width: Int, height: Int) {
            val canvas = SceneCanvas(width, height)
            val spans = IntArray(height * 2)
            var poseKey: Long? = null
        }

        private class SceneCanvas(val w: Int, val h: Int) {
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            val depth = FloatArray(w * h) { BACKGROUND_DEPTH_M }

            fun setPixel(x: Int, y: Int, cr: Float, cg: Float, cb: Float, d: Float) {
                if (x !in 0 until w || y !in 0 until h) return
                val i = y * w + x
                r[i] = cr.coerceIn(0f, 1f)
                g[i] = cg.coerceIn(0f, 1f)
                b[i] = cb.coerceIn(0f, 1f)
                depth[i] = d
            }

            fun setPixelSrgb(x: Int, y: Int, rgb24: Int, d: Float) {
                val lut = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT
                setPixel(
                    x,
                    y,
                    lut[(rgb24 ushr 16) and 0xFF],
                    lut[(rgb24 ushr 8) and 0xFF],
                    lut[rgb24 and 0xFF],
                    d,
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
                        val i = row + x
                        r[i] = cr
                        g[i] = cg
                        b[i] = cb
                        depth[i] = d
                    }
                }
            }

            fun fillRectSrgb(x0: Int, y0: Int, rw: Int, rh: Int, rgb24: Int, d: Float) {
                val lut = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT
                fillRect(
                    x0,
                    y0,
                    rw,
                    rh,
                    lut[(rgb24 ushr 16) and 0xFF],
                    lut[(rgb24 ushr 8) and 0xFF],
                    lut[rgb24 and 0xFF],
                    d,
                )
            }

            fun drawOrb(cx: Int, cy: Int, radius: Int, cr: Float, cg: Float, cb: Float, d: Float) {
                val r2 = (radius * radius).toFloat().coerceAtLeast(1f)
                val xs = (cx - radius).coerceAtLeast(0)
                val xe = (cx + radius + 1).coerceAtMost(w)
                val ys = (cy - radius).coerceAtLeast(0)
                val ye = (cy + radius + 1).coerceAtMost(h)
                for (y in ys until ye) {
                    val dy = (y - cy).toFloat()
                    val row = y * w
                    for (x in xs until xe) {
                        val dx = (x - cx).toFloat()
                        val dist2 = dx * dx + dy * dy
                        if (dist2 <= r2) {
                            val falloff = (1f - (dist2 / r2) * 0.25f).coerceIn(0f, 1f)
                            val i = row + x
                            r[i] = (r[i] + cr * falloff).coerceIn(0f, 1f)
                            g[i] = (g[i] + cg * falloff).coerceIn(0f, 1f)
                            b[i] = (b[i] + cb * falloff).coerceIn(0f, 1f)
                            depth[i] = d
                        }
                    }
                }
            }

            /**
             * Unclamped HDR specular light orb with crisp pinpoint core, smooth luminous disc body,
             * and subtle atmospheric halo so wide-aperture defocus renders glowing bokeh balls and
             * Star / Black Mist filters produce vivid diffraction spikes and halation.
             */
            fun drawHdrOrb(cx: Int, cy: Int, radius: Int, cr: Float, cg: Float, cb: Float, d: Float) {
                val radF = radius.toFloat().coerceAtLeast(2f)
                val haloRad = (radF * 1.55f).roundToInt().coerceAtLeast(radius + 2)
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
                        val i = row + x
                        if (dist <= radF) {
                            val norm = dist / radF
                            // Crisp pinpoint specular core (norm <= 0.45) tapering across luminous disc
                            val falloff = if (norm <= 0.45f) {
                                1.0f - 0.12f * (norm / 0.45f)
                            } else {
                                val u = (norm - 0.45f) / 0.55f
                                0.88f * (1f - u * u * 0.68f)
                            }.coerceIn(0f, 1f)
                            r[i] = max(r[i], cr * falloff)
                            g[i] = max(g[i], cg * falloff)
                            b[i] = max(b[i], cb * falloff)
                            depth[i] = d
                        } else {
                            // Subtle atmospheric glow just outside the disc edge
                            val u = (dist - radF) / (haloRadF - radF).coerceAtLeast(1f)
                            val halo = 0.24f * (1f - u) * (1f - u)
                            r[i] = min(max(r[i], cr), r[i] + cr * halo)
                            g[i] = min(max(g[i], cg), g[i] + cg * halo)
                            b[i] = min(max(b[i], cb), b[i] + cb * halo)
                            depth[i] = d
                        }
                    }
                }
            }

            fun drawSiemensStar(cx: Int, cy: Int, radius: Int, spokes: Int, d: Float, bright: Float = 0.94f) {
                val r2 = radius * radius
                val inner2 = (radius / 10).coerceAtLeast(3).let { it * it }
                val xs = (cx - radius).coerceAtLeast(0)
                val xe = (cx + radius + 1).coerceAtMost(w)
                val ys = (cy - radius).coerceAtLeast(0)
                val ye = (cy + radius + 1).coerceAtMost(h)
                for (y in ys until ye) {
                    val dy = (y - cy).toDouble()
                    val row = y * w
                    for (x in xs until xe) {
                        val dx = (x - cx).toDouble()
                        val dist2 = dx * dx + dy * dy
                        if (dist2 <= r2) {
                            val v = if (dist2 <= inner2) {
                                0.05f
                            } else {
                                val angle = atan2(dy, dx)
                                if (sin(angle * spokes) >= 0.0) bright else 0.03f
                            }
                            val i = row + x
                            r[i] = v
                            g[i] = v
                            b[i] = v
                            depth[i] = d
                        }
                    }
                }
            }

            fun drawMacbethChart(x0: Int, y0: Int, chartW: Int, chartH: Int, d: Float) {
                // Dark neutral matte border
                fillRect(x0, y0, chartW, chartH, 0.025f, 0.025f, 0.028f, d)
                val cols = 6
                val rows = 4
                val pad = (min(chartW, chartH) / 28).coerceAtLeast(3)
                val cellW = ((chartW - pad * (cols + 1)) / cols).coerceAtLeast(4)
                val cellH = ((chartH - pad * (rows + 1)) / rows).coerceAtLeast(4)
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val px = x0 + pad + col * (cellW + pad)
                        val py = y0 + pad + row * (cellH + pad)
                        fillRectSrgb(px, py, cellW, cellH, MACBETH_SRGB[row * cols + col], d)
                    }
                }
            }

            fun drawPinpointLight(cx: Int, cy: Int, radius: Double, cr: Float, cg: Float, cb: Float, d: Float) {
                val rCeil = (radius + 1.5).roundToInt().coerceAtLeast(2)
                val r2 = radius * radius
                for (dy in -rCeil..rCeil) {
                    val py = cy + dy
                    if (py !in 0 until h) continue
                    val row = py * w
                    for (dx in -rCeil..rCeil) {
                        val px = cx + dx
                        if (px !in 0 until w) continue
                        val distSq = (dx * dx + dy * dy).toDouble()
                        if (distSq > (radius + 1.0) * (radius + 1.0)) continue
                        val dist = sqrt(distSq)
                        val wPix = if (dist <= radius * 0.7) {
                            1.0f
                        } else {
                            ((radius + 1.0 - dist) / (radius * 0.3 + 1.0)).toFloat().coerceIn(0f, 1f)
                        }
                        if (wPix > 0f) {
                            val idx = row + px
                            // Additive unclipped HDR linear radiance so out-of-focus pupil masks shine brightly.
                            r[idx] = max(r[idx], cr * wPix)
                            g[idx] = max(g[idx], cg * wPix)
                            b[idx] = max(b[idx], cb * wPix)
                            depth[idx] = d
                        }
                    }
                }
            }

            fun toLinearRgbFrame(): LinearRgbFrame = LinearRgbFrame(w, h, r, g, b, depth)
        }

        internal fun synthesizeScene(preset: OpticalScenePreset, width: Int, height: Int): LinearRgbFrame =
            when (preset) {
                OpticalScenePreset.QR_BALL -> buildQrBall(width, height)
                OpticalScenePreset.MONA_LISA -> MonaLisaSceneSource(width = width, height = height, motionEnabled = false).use { it.latestLinearRgbFrame() }
                OpticalScenePreset.MILK_FROG -> MilkFrogSceneSource(width = width, height = height, motionEnabled = false, audioEnabled = false).use { it.latestLinearRgbFrame() }
                OpticalScenePreset.DEPTH_STUDIO -> buildDepthStudio(width, height)
                OpticalScenePreset.BOKEH_CITY -> buildBokehCity(width, height)
                OpticalScenePreset.STAR_BOKEH -> buildStarBokeh(width, height)
                OpticalScenePreset.RESOLUTION_CHART -> buildResolutionChart(width, height)
            }

        /**
         * Ray-traced 3D autofocus & focus-motor calibration studio (`scene://qr-ball`):
         * - Perspective 3D studio room with smooth floor/wall depth progression (`~1.0 m` -> `5.5 m`).
         * - Distant flat back wall (`z = 5.5 m`) displaying a crisp, genuinely scannable ISO/IEC 18004
         *   Version 2 QR code poster ([QR_BALL_PAYLOAD]) with 4-module quiet zone & dark studio bezel.
         * - Near foreground 3D calibration sphere (`center = (-0.28, -0.08, 1.05) m`, `radius = 0.22 m`,
         *   front surface `~0.83 m`, screen center `cx ≈ 0.34, cy ≈ 0.56`) with analytic curved
         *   per-pixel `depthMeters(x, y)`, high-frequency spherical calibration micro-grid + equatorial
         *   focus band, Phong/Blinn-Phong shading, and a foreground plinth with soft contact shadow.
         */
        private val STRIPE_WORKERS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        private val STRIPE_THREAD_COUNTER = AtomicInteger(1)
        private val STRIPE_POOL: ExecutorService? by lazy {
            val count = (STRIPE_WORKERS - 1).coerceAtLeast(0)
            if (count <= 0) {
                null
            } else {
                Executors.newFixedThreadPool(count) { runnable ->
                    Thread(runnable, "fuji-scene3d-stripe-${STRIPE_THREAD_COUNTER.getAndIncrement()}").apply {
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

        private val QR_BALL_MATRIX: Array<BooleanArray> by lazy {
            ProceduralQrMatrix.encode(QR_BALL_PAYLOAD)
        }
        private const val QR_BALL_MATRIX_GPU_KEY: Long = 0x5152_4241_4C4C_0001L
        private val QR_BALL_ROW_BITS_32: IntArray by lazy {
            val mat = QR_BALL_MATRIX
            val bits = IntArray(32)
            for (r in mat.indices) {
                var w = 0
                val row = mat[r]
                for (c in row.indices) {
                    if (row[c]) w = w or (1 shl c)
                }
                bits[r] = w
            }
            bits
        }

        // Studio practical HDR wall sconces on z = 5.5 m (worldX, worldY, radiusM, hdrR, hdrG, hdrB),
        // strictly outside QR poster bezel (x in [0.05..3.15], y in [-1.43..1.73]) and foreground sphere silhouette.
        private val QR_BALL_WALL_LAMPS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(-3.20, 1.85, 0.13, 6.8, 5.1, 2.1), // warm tungsten upper-left practical
            doubleArrayOf(-1.75, 2.02, 0.12, 6.2, 6.0, 5.4), // neutral studio key practical
            doubleArrayOf(-0.35, 2.04, 0.11, 7.0, 4.3, 1.6), // warm amber upper-center practical
            doubleArrayOf(1.55, 2.06, 0.11, 5.4, 6.2, 7.2),  // cool daylight gallery spot above QR
            doubleArrayOf(3.68, 1.78, 0.12, 6.8, 5.0, 2.0),  // warm gold upper-right wall sconce
            doubleArrayOf(-3.45, 0.45, 0.12, 2.4, 5.8, 7.0), // cool cyan-white mid-left rim lamp
        )

        // Overhead studio practical track spots on ceiling y = 2.35 m (worldX, worldZ, radiusM, hdrR, hdrG, hdrB).
        private val QR_BALL_CEILING_LAMPS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(-1.65, 4.75, 0.14, 6.4, 5.8, 4.6),
            doubleArrayOf(1.75, 4.75, 0.14, 5.8, 6.2, 6.8),
        )

        private fun buildQrBall(w: Int, h: Int): LinearRgbFrame =
            buildQrBallForPose(w, h, CameraPose3D.IDENTITY, SceneCanvas(w, h))

        /**
         * Ray-traced 3D autofocus & focus-motor calibration studio (`scene://qr-ball`) evaluated
         * from an arbitrary 6-DoF [CameraPose3D] (`rollDeg`, `pitchDeg`, `yawDeg`, `posXM/YM/ZM`)
         * with striped multi-threaded row execution.
         */
        private fun buildQrBallForPose(
            w: Int, h: Int, pose: CameraPose3D, c: SceneCanvas, rotationTurns: Float = 0f,
            sphereSpans: IntArray? = null, sameView: Boolean = false,
        ): LinearRgbFrame {
            val qrMatrix = QR_BALL_MATRIX
            val qrModules = qrMatrix.size
            val quietZone = 4
            val totalQrModules = qrModules + 2 * quietZone

            // Pinhole camera at pose origin looking along R * +Z (square pixels, wide-normal studio FOV
            // scaled proportionally by active optical focal length relative to 45 mm reference).
            val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
            val focal = w * 0.60 * focalScale
            val halfW = w * 0.5
            val halfH = h * 0.5

            // 3D studio room bounds (metres).
            val wallZ = QR_BALL_WALL_DEPTH_M.toDouble()
            val roomHalfX = 4.2
            val floorY = -1.75
            val ceilY = 2.35

            // Camera origin clamped strictly inside room bounds so rays remain well-conditioned.
            val ox = pose.posXM.coerceIn(-roomHalfX + 0.25, roomHalfX - 0.25)
            val oy = pose.posYM.coerceIn(floorY + 0.25, ceilY - 0.25)
            val oz = pose.posZM.coerceIn(-2.0, wallZ - 0.40)

            // Camera-to-world 3x3 rotation matrix R = R_yaw * R_pitch * R_roll.
            val rollRad = Math.toRadians(pose.rollDeg)
            val pitchRad = Math.toRadians(pose.pitchDeg)
            val yawRad = Math.toRadians(pose.yawDeg)
            val cR = cos(rollRad)
            val sR = sin(rollRad)
            val cP = cos(pitchRad)
            val sP = sin(pitchRad)
            val cY = cos(yawRad)
            val sY = sin(yawRad)

            // Basis vectors (local +X right, +Y up, +Z forward) in world space:
            // v1 = R_roll * v = (vx*cR + vy*sR, -vx*sR + vy*cR, vz)
            // v2 = R_pitch * v1 = (v1x, v1y*cP + v1z*sP, -v1y*sP + v1z*cP)
            // vW = R_yaw * v2 = (v2x*cY + v2z*sY, v2y, -v2x*sY + v2z*cY)
            val r00 = cR * cY + sR * sP * sY
            val r01 = sR * cY - cR * sP * sY
            val r02 = cP * sY

            val r10 = -sR * cP
            val r11 = cR * cP
            val r12 = sP

            val r20 = -cR * sY + sR * sP * cY
            val r21 = -sR * sY - cR * sP * cY
            val r22 = cP * cY

            // Distant QR code poster mounted flat on back wall (z = 5.5 m, unobstructed center-right).
            val qrMinX = 0.15
            val qrMaxX = 3.05
            val qrMinY = -1.27
            val qrMaxY = 1.63
            val qrSpan = qrMaxX - qrMinX
            val bezel = 0.10

            // Near foreground 3D sphere (z_center = 1.05 m, radius = 0.22 m -> front apex ~0.83 m).
            val sx = -0.28
            val sy = -0.08
            val sz = QR_BALL_SPHERE_CENTER_Z_M.toDouble()
            val sRad = QR_BALL_SPHERE_RADIUS_M.toDouble()
            val sRadSq = sRad * sRad
            val ocX = sx - ox
            val ocY = sy - oy
            val ocZ = sz - oz
            val ocDotOcMinusR2 = ocX * ocX + ocY * ocY + ocZ * ocZ - sRadSq

            // Foreground studio plinth directly beneath the sphere (y = sy - sRad = -0.30 m).
            val plinthTopY = sy - sRad
            val plinthBotY = plinthTopY - 0.055
            val plinthMinX = sx - 0.28
            val plinthMaxX = sx + 0.28
            val plinthMinZ = sz - 0.25
            val plinthMaxZ = sz + 0.27
            val colMinX = sx - 0.085
            val colMaxX = sx + 0.085
            val colMinZ = sz - 0.085
            val colMaxZ = sz + 0.085

            // Studio directional lighting vectors (normalized).
            val l1Len = sqrt(0.55 * 0.55 + 0.70 * 0.70 + 0.45 * 0.45)
            val l1x = -0.55 / l1Len
            val l1y = 0.70 / l1Len
            val l1z = -0.45 / l1Len

            val l2Len = sqrt(0.65 * 0.65 + 0.25 * 0.25 + 0.55 * 0.55)
            val l2x = 0.65 / l2Len
            val l2y = 0.25 / l2Len
            val l2z = -0.55 / l2Len

            parallelRows(h) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val ry = (halfH - (y + 0.5)) / focal
                    val row = y * w
                    // A rotating sphere has a fixed silhouette. Keep the room from this canvas's last pose.
                    val span = y * 2
                    var first = w
                    var end = 0
                    val xStart = if (sameView && sphereSpans != null) sphereSpans[span] else 0
                    val xEnd = if (sameView && sphereSpans != null) sphereSpans[span + 1] else w
                    for (x in xStart until xEnd) {
                        val rx = (x + 0.5 - halfW) / focal
                        val invLen = 1.0 / sqrt(rx * rx + ry * ry + 1.0)
                        val ldx = rx * invLen
                        val ldy = ry * invLen
                        val ldz = invLen

                        val dx = r00 * ldx + r01 * ldy + r02 * ldz
                        val dy = r10 * ldx + r11 * ldy + r12 * ldz
                        val dz = r20 * ldx + r21 * ldy + r22 * ldz

                        // 1. Intersect studio room box (back wall z = 5.5 m, rear wall z = -3.0 m, side walls, floor, ceiling).
                        var bestT = Double.POSITIVE_INFINITY
                        var hitKind = 0 // 0=backWall, 1=floor, 2=ceil, 3=leftWall, 4=rightWall, 5=plinthTop, 6=plinthFront, 7=column, 8=sphere
                        var hitX = ox
                        var hitY = oy
                        var hitZ = wallZ

                        if (dz > 1e-6) {
                            val tBack = (wallZ - oz) / dz
                            if (tBack > 0.05 && tBack < bestT) {
                                bestT = tBack
                                hitX = ox + dx * tBack
                                hitY = oy + dy * tBack
                                hitZ = wallZ
                                hitKind = 0
                            }
                        } else if (dz < -1e-6) {
                            val tRear = (-3.0 - oz) / dz
                            if (tRear > 0.05 && tRear < bestT) {
                                bestT = tRear
                                hitX = ox + dx * tRear
                                hitY = oy + dy * tRear
                                hitZ = -3.0
                                hitKind = 3
                            }
                        }

                        if (dy < -1e-6) {
                            val tFloor = (floorY - oy) / dy
                            if (tFloor > 0.05 && tFloor < bestT) {
                                val xf = ox + dx * tFloor
                                val zf = oz + dz * tFloor
                                if (abs(xf) <= roomHalfX + 1e-3 && zf in -3.05..(wallZ + 0.05)) {
                                    bestT = tFloor
                                    hitX = xf
                                    hitY = floorY
                                    hitZ = zf
                                    hitKind = 1
                                }
                            }
                        } else if (dy > 1e-6) {
                            val tCeil = (ceilY - oy) / dy
                            if (tCeil > 0.05 && tCeil < bestT) {
                                val xc = ox + dx * tCeil
                                val zc = oz + dz * tCeil
                                if (abs(xc) <= roomHalfX + 1e-3 && zc in -3.05..(wallZ + 0.05)) {
                                    bestT = tCeil
                                    hitX = xc
                                    hitY = ceilY
                                    hitZ = zc
                                    hitKind = 2
                                }
                            }
                        }

                        if (dx < -1e-6) {
                            val tLeft = (-roomHalfX - ox) / dx
                            if (tLeft > 0.05 && tLeft < bestT) {
                                val yl = oy + dy * tLeft
                                val zl = oz + dz * tLeft
                                if (yl in floorY..ceilY && zl in -3.05..(wallZ + 0.05)) {
                                    bestT = tLeft
                                    hitX = -roomHalfX
                                    hitY = yl
                                    hitZ = zl
                                    hitKind = 3
                                }
                            }
                        } else if (dx > 1e-6) {
                            val tRight = (roomHalfX - ox) / dx
                            if (tRight > 0.05 && tRight < bestT) {
                                val yr = oy + dy * tRight
                                val zr = oz + dz * tRight
                                if (yr in floorY..ceilY && zr in -3.05..(wallZ + 0.05)) {
                                    bestT = tRight
                                    hitX = roomHalfX
                                    hitY = yr
                                    hitZ = zr
                                    hitKind = 4
                                }
                            }
                        }

                        // 2. Intersect foreground plinth (top slab, front bevel face, and support column).
                        if (dy < -1e-6) {
                            val tTop = (plinthTopY - oy) / dy
                            if (tTop > 0.05 && tTop < bestT) {
                                val xt = ox + dx * tTop
                                val zt = oz + dz * tTop
                                if (zt in plinthMinZ..plinthMaxZ && xt in plinthMinX..plinthMaxX) {
                                    bestT = tTop
                                    hitX = xt
                                    hitY = plinthTopY
                                    hitZ = zt
                                    hitKind = 5
                                }
                            }
                        }
                        if (dz > 1e-6) {
                            val tFront = (plinthMinZ - oz) / dz
                            if (tFront > 0.05 && tFront < bestT) {
                                val xf = ox + dx * tFront
                                val yf = oy + dy * tFront
                                if (xf in plinthMinX..plinthMaxX && yf in plinthBotY..plinthTopY) {
                                    bestT = tFront
                                    hitX = xf
                                    hitY = yf
                                    hitZ = plinthMinZ
                                    hitKind = 6
                                }
                            }
                            val tCol = (colMinZ - oz) / dz
                            if (tCol > 0.05 && tCol < bestT) {
                                val xc = ox + dx * tCol
                                val yc = oy + dy * tCol
                                if (xc in colMinX..colMaxX && yc in floorY..plinthBotY) {
                                    bestT = tCol
                                    hitX = xc
                                    hitY = yc
                                    hitZ = colMinZ
                                    hitKind = 7
                                }
                            }
                        }
                        if (dx < -1e-6) {
                            val tColSide = (colMaxX - ox) / dx
                            if (tColSide > 0.05 && tColSide < bestT) {
                                val zcs = oz + dz * tColSide
                                val ycs = oy + dy * tColSide
                                if (zcs in colMinZ..colMaxZ && ycs in floorY..plinthBotY) {
                                    bestT = tColSide
                                    hitX = colMaxX
                                    hitY = ycs
                                    hitZ = zcs
                                    hitKind = 7
                                }
                            }
                        } else if (dx > 1e-6) {
                            val tColSide = (colMinX - ox) / dx
                            if (tColSide > 0.05 && tColSide < bestT) {
                                val zcs = oz + dz * tColSide
                                val ycs = oy + dy * tColSide
                                if (zcs in colMinZ..colMaxZ && ycs in floorY..plinthBotY) {
                                    bestT = tColSide
                                    hitX = colMinX
                                    hitY = ycs
                                    hitZ = zcs
                                    hitKind = 7
                                }
                            }
                        }

                        // 3. Analytic ray-sphere intersection for the near foreground 3D calibration sphere.
                        val bProj = dx * ocX + dy * ocY + dz * ocZ
                        val disc = bProj * bProj - ocDotOcMinusR2
                        if (disc >= 0.0) {
                            val tHit = bProj - sqrt(disc)
                            if (tHit > 0.05 && tHit < bestT) {
                                bestT = tHit
                                hitX = ox + dx * tHit
                                hitY = oy + dy * tHit
                                hitZ = oz + dz * tHit
                                hitKind = 8
                            }
                        }

                        val camDepth = if (bestT.isFinite()) (bestT * ldz).coerceAtLeast(0.05) else wallZ
                        var outR: Double
                        var outG: Double
                        var outB: Double

                        when (hitKind) {
                            8 -> {
                                first = minOf(first, x)
                                end = x + 1
                                val nx = (hitX - sx) / sRad
                                val ny = (hitY - sy) / sRad
                                val nz = (hitZ - sz) / sRad

                                val phi = asin(ny.coerceIn(-1.0, 1.0))
                                val theta = atan2(nx, -nz)
                                val uGrid = (theta / (2.0 * PI) + 0.5 - rotationTurns) * 32.0
                                val vGrid = (phi / PI + 0.5) * 16.0
                                val cellU = floor(uGrid).toInt()
                                val cellV = floor(vGrid).toInt()
                                val fracU = uGrid - cellU
                                val fracV = vGrid - cellV
                                val checkerDark = ((cellU + cellV) and 1) == 0
                                val onFineLine = fracU < 0.08 || fracU > 0.92 || fracV < 0.08 || fracV > 0.92
                                val onEquatorBand = abs(phi) < 0.075
                                val onEquatorTicks = abs(phi) < 0.045 && ((floor(uGrid * 2.0).toInt() and 1) == 0)

                                val baseR: Double
                                val baseG: Double
                                val baseB: Double
                                when {
                                    onEquatorTicks -> {
                                        baseR = 0.96
                                        baseG = 0.96
                                        baseB = 0.94
                                    }
                                    onEquatorBand -> {
                                        baseR = 0.78
                                        baseG = 0.09
                                        baseB = 0.07
                                    }
                                    onFineLine -> {
                                        baseR = 0.05
                                        baseG = 0.06
                                        baseB = 0.08
                                    }
                                    checkerDark -> {
                                        baseR = 0.10
                                        baseG = 0.13
                                        baseB = 0.19
                                    }
                                    else -> {
                                        baseR = 0.86
                                        baseG = 0.85
                                        baseB = 0.82
                                    }
                                }

                                val diff1 = max(0.0, nx * l1x + ny * l1y + nz * l1z)
                                val diff2 = max(0.0, nx * l2x + ny * l2y + nz * l2z)

                                val vx = -dx
                                val vy = -dy
                                val vz = -dz
                                val hx = l1x + vx
                                val hy = l1y + vy
                                val hz = l1z + vz
                                val invH = 1.0 / max(1e-6, sqrt(hx * hx + hy * hy + hz * hz))
                                val nDotH = max(0.0, (nx * hx + ny * hy + nz * hz) * invH)
                                val spec = nDotH.pow(52.0) * 0.48
                                val nDotV = max(0.0, nx * vx + ny * vy + nz * vz)
                                val rim = (1.0 - nDotV).pow(3.0) * 0.14

                                val litR = 0.12 + 0.82 * diff1 + 0.18 * diff2
                                val litG = 0.12 + 0.81 * diff1 + 0.21 * diff2
                                val litB = 0.13 + 0.78 * diff1 + 0.26 * diff2

                                outR = baseR * litR + spec + rim * 0.85
                                outG = baseG * litG + spec + rim * 0.92
                                outB = baseB * litB + spec + rim
                            }
                            5 -> {
                                val ddx = hitX - sx
                                val ddz = hitZ - sz
                                val distSq = ddx * ddx + ddz * ddz
                                val contactAo = 1.0 - 0.82 * exp(-distSq / (sRadSq * 0.42))
                                val edgeHighlight = if (
                                    abs(hitX - plinthMinX) < 0.012 || abs(hitX - plinthMaxX) < 0.012 ||
                                    abs(hitZ - plinthMinZ) < 0.012 || abs(hitZ - plinthMaxZ) < 0.012
                                ) 0.28 else 0.19
                                outR = edgeHighlight * contactAo
                                outG = (edgeHighlight + 0.01) * contactAo
                                outB = (edgeHighlight + 0.025) * contactAo
                            }
                            6 -> {
                                val grad = ((hitY - plinthBotY) / (plinthTopY - plinthBotY)).coerceIn(0.0, 1.0)
                                val v = 0.07 + 0.05 * grad
                                outR = v
                                outG = v * 1.04
                                outB = v * 1.12
                            }
                            7 -> {
                                val shade = if (abs(hitZ - colMinZ) < 1e-3) 0.065 else 0.042
                                outR = shade
                                outG = shade * 1.05
                                outB = shade * 1.14
                            }
                            0 -> {
                                if (hitX in qrMinX..qrMaxX && hitY in qrMinY..qrMaxY) {
                                    val u = (hitX - qrMinX) / qrSpan
                                    val v = (qrMaxY - hitY) / qrSpan
                                    val modCol = floor(u * totalQrModules).toInt().coerceIn(0, totalQrModules - 1)
                                    val modRow = floor(v * totalQrModules).toInt().coerceIn(0, totalQrModules - 1)
                                    val rIdx = modRow - quietZone
                                    val cIdx = modCol - quietZone
                                    val isDark = rIdx in 0 until qrModules && cIdx in 0 until qrModules && qrMatrix[rIdx][cIdx]
                                    val lum = if (isDark) 0.02 else 0.94
                                    outR = lum
                                    outG = lum
                                    outB = lum
                                } else if (
                                    hitX in (qrMinX - bezel)..(qrMaxX + bezel) &&
                                    hitY in (qrMinY - bezel * 1.6)..(qrMaxY + bezel)
                                ) {
                                    if (hitY < qrMinY - bezel * 0.35 && hitY > qrMinY - bezel * 1.25 && hitX in (qrMinX + 0.15)..(qrMaxX - 0.15)) {
                                        val barPos = ((hitX - qrMinX) * 14.0).toInt()
                                        val tick = (barPos and 1) == 0 && hitX > qrMinX + 1.85
                                        val lum = if (tick) 0.72 else 0.055
                                        outR = lum
                                        outG = lum
                                        outB = lum
                                    } else {
                                        outR = 0.045
                                        outG = 0.050
                                        outB = 0.060
                                    }
                                } else {
                                    val r2 = ((hitX - 0.4) * (hitX - 0.4)) * 0.035 + ((hitY - 0.3) * (hitY - 0.3)) * 0.07
                                    val wallLum = (0.28 - r2).coerceIn(0.11, 0.30)
                                    val seam = abs(((hitX + 12.0) % 1.2) - 0.6) > 0.585
                                    val panelFactor = if (seam) 0.84 else 1.0
                                    outR = wallLum * panelFactor * 0.98
                                    outG = wallLum * panelFactor * 1.00
                                    outB = wallLum * panelFactor * 1.05

                                    // 6 recessed HDR studio practical rim lamps on the upper/side back wall (z = 5.5 m),
                                    // placed strictly outside the QR poster bezel and foreground sphere silhouette so
                                    // wide-aperture focus, Star Filter starbursts, and Black Mist halation shine vividly
                                    // without disturbing QR readability or 3D sphere calibration geometry.
                                    var lampR = 0.0
                                    var lampG = 0.0
                                    var lampB = 0.0
                                    for (lamp in QR_BALL_WALL_LAMPS) {
                                        val ldx2 = hitX - lamp[0]
                                        val ldy2 = hitY - lamp[1]
                                        val distSq = ldx2 * ldx2 + ldy2 * ldy2
                                        val rad = lamp[2]
                                        val haloRad = rad * 2.1
                                        if (distSq <= haloRad * haloRad) {
                                            val dist = sqrt(distSq)
                                            val wLamp = if (dist <= rad * 0.42) {
                                                1.0
                                            } else if (dist <= rad) {
                                                val u = (dist - rad * 0.42) / (rad * 0.58)
                                                1.0 - 0.55 * u * u
                                            } else {
                                                val u = (dist - rad) / (haloRad - rad)
                                                0.18 * (1.0 - u) * (1.0 - u)
                                            }
                                            lampR = max(lampR, lamp[3] * wLamp)
                                            lampG = max(lampG, lamp[4] * wLamp)
                                            lampB = max(lampB, lamp[5] * wLamp)
                                        }
                                    }
                                    if (lampR > 0.0 || lampG > 0.0 || lampB > 0.0) {
                                        outR += lampR
                                        outG += lampG
                                        outB += lampB
                                    }
                                }
                            }
                            1 -> {
                                val gx = abs((hitX * 2.0) - floor(hitX * 2.0 + 0.5))
                                val gz = abs((hitZ * 2.0) - floor(hitZ * 2.0 + 0.5))
                                val gridLine = gx < 0.032 || gz < 0.032
                                val tileParity = (floor(hitX * 2.0).toInt() + floor(hitZ * 2.0).toInt()) and 1
                                val depthFade = (1.15 - (hitZ - 1.0) * 0.09).coerceIn(0.45, 1.0)
                                val base = when {
                                    gridLine -> 0.32
                                    tileParity == 0 -> 0.145
                                    else -> 0.115
                                } * depthFade
                                outR = base * 0.97
                                outG = base * 1.00
                                outB = base * 1.06
                            }
                            2 -> {
                                val lightStrip = abs(hitX) < 1.6 && ((floor(hitZ * 0.85).toInt() and 1) == 0) && abs((hitZ * 0.85) - floor(hitZ * 0.85) - 0.5) < 0.22
                                if (lightStrip) {
                                    outR = 0.72
                                    outG = 0.74
                                    outB = 0.78
                                } else {
                                    val fade = (0.22 - hitZ * 0.018).coerceIn(0.08, 0.20)
                                    outR = fade * 0.96
                                    outG = fade * 0.98
                                    outB = fade * 1.04
                                }
                                // 2 overhead HDR studio track spotlights on ceiling for 6-DoF pitch-up & wide-angle roaming.
                                for (spot in QR_BALL_CEILING_LAMPS) {
                                    val sdx = hitX - spot[0]
                                    val sdz = hitZ - spot[1]
                                    val distSq = sdx * sdx + sdz * sdz
                                    val rad = spot[2]
                                    val haloRad = rad * 2.0
                                    if (distSq <= haloRad * haloRad) {
                                        val dist = sqrt(distSq)
                                        val wSpot = if (dist <= rad * 0.45) {
                                            1.0
                                        } else if (dist <= rad) {
                                            val u = (dist - rad * 0.45) / (rad * 0.55)
                                            1.0 - 0.55 * u * u
                                        } else {
                                            val u = (dist - rad) / (haloRad - rad)
                                            0.18 * (1.0 - u) * (1.0 - u)
                                        }
                                        outR += spot[3] * wSpot
                                        outG += spot[4] * wSpot
                                        outB += spot[5] * wSpot
                                    }
                                }
                            }
                            else -> {
                                val depthFade = (1.10 - (hitZ - 1.0) * 0.085).coerceIn(0.45, 1.0)
                                val onDado = abs(hitY - (-0.65)) < 0.045
                                val zRib = abs((hitZ % 1.0) - 0.5) > 0.48
                                val base = when {
                                    onDado -> 0.08
                                    zRib -> 0.14
                                    hitY < -0.65 -> 0.16
                                    else -> 0.23
                                } * depthFade
                                val sideBias = if (hitKind == 3) 0.92 else 1.04
                                outR = base * sideBias * 0.98
                                outG = base * sideBias * 1.00
                                outB = base * sideBias * 1.06
                            }
                        }

                        val i = row + x
                        // Keep sphere/plinth/QR/floor/side-walls strictly calibrated in [0, 1],
                        // while preserving unclamped HDR radiance on the studio practical rim/ceiling lamps.
                        val maxLum = if (hitKind == 0 || hitKind == 2) 12f else 1f
                        c.r[i] = outR.toFloat().coerceIn(0f, maxLum)
                        c.g[i] = outG.toFloat().coerceIn(0f, maxLum)
                        c.b[i] = outB.toFloat().coerceIn(0f, maxLum)
                        c.depth[i] = camDepth.toFloat()
                    }
                    if (!sameView && sphereSpans != null) {
                        sphereSpans[span] = first
                        sphereSpans[span + 1] = end
                    }
                }
            }

            return c.toLinearRgbFrame()
        }

        /**
         * Reprojects a layered metric 3D scene (`depth-studio`, `bokeh-city`, `resolution-chart`)
         * under 6-DoF [CameraPose3D] roll, pitch, yaw, and inverse-depth camera parallax.
         */
        private fun reprojectLayeredSceneForPose(
            base: LinearRgbFrame,
            pose: CameraPose3D,
            c: SceneCanvas,
        ): LinearRgbFrame {
            val w = base.width
            val h = base.height
            val srcR = base.red
            val srcG = base.green
            val srcB = base.blue
            val srcD = base.depthMeters ?: FloatArray(w * h) { BACKGROUND_DEPTH_M }

            val halfW = (w - 1) * 0.5
            val halfH = (h - 1) * 0.5
            val focalScale = (pose.focalLengthMm / CameraPose3D.DEFAULT_FOCAL_LENGTH_MM).coerceIn(0.25, 8.0)
            val invFocalScale = 1.0 / focalScale
            val focal = w * 0.85 * focalScale
            val rollRad = Math.toRadians(pose.rollDeg)
            val cosR = cos(rollRad)
            val sinR = sin(rollRad)
            val yawShiftPx = tan(Math.toRadians(pose.yawDeg.coerceIn(-60.0, 60.0))) * focal
            val pitchShiftPx = tan(Math.toRadians(pose.pitchDeg.coerceIn(-60.0, 60.0))) * focal
            val hasTranslation = abs(pose.posXM) > 1e-6 || abs(pose.posYM) > 1e-6 || abs(pose.posZM) > 1e-6
            val maxX = w - 1
            val maxY = h - 1

            parallelRows(h) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val dy = (y - halfH) * invFocalScale
                    val row = y * w
                    for (x in 0 until w) {
                        val dx = (x - halfW) * invFocalScale
                        val rx = dx * cosR + dy * sinR
                        val ry = -dx * sinR + dy * cosR
                        var sx = halfW + rx + yawShiftPx * invFocalScale
                        var sy = halfH + ry - pitchShiftPx * invFocalScale

                        val probeX = sx.roundToInt().coerceIn(0, maxX)
                        val probeY = sy.roundToInt().coerceIn(0, maxY)
                        val z0 = srcD[probeY * w + probeX].toDouble().coerceAtLeast(0.05)

                        if (hasTranslation) {
                            sx += (pose.posXM / z0) * focal
                            sy -= (pose.posYM / z0) * focal
                        }

                        val i = row + x
                        if (sx < 0.0 || sx > maxX.toDouble() || sy < 0.0 || sy > maxY.toDouble()) {
                            c.r[i] = 0.015f
                            c.g[i] = 0.018f
                            c.b[i] = 0.025f
                            c.depth[i] = BACKGROUND_DEPTH_M
                            continue
                        }
                        val x0 = floor(sx).toInt().coerceIn(0, maxX)
                        val y0 = floor(sy).toInt().coerceIn(0, maxY)
                        val x1 = min(x0 + 1, maxX)
                        val y1 = min(y0 + 1, maxY)
                        val fx = (sx - x0).toFloat()
                        val fy = (sy - y0).toFloat()
                        val r0 = y0 * w
                        val r1 = y1 * w
                        val i00 = r0 + x0
                        val i10 = r0 + x1
                        val i01 = r1 + x0
                        val i11 = r1 + x1

                        val rTop = srcR[i00] + (srcR[i10] - srcR[i00]) * fx
                        val rBot = srcR[i01] + (srcR[i11] - srcR[i01]) * fx
                        c.r[i] = rTop + (rBot - rTop) * fy

                        val gTop = srcG[i00] + (srcG[i10] - srcG[i00]) * fx
                        val gBot = srcG[i01] + (srcG[i11] - srcG[i01]) * fx
                        c.g[i] = gTop + (gBot - gTop) * fy

                        val bTop = srcB[i00] + (srcB[i10] - srcB[i00]) * fx
                        val bBot = srcB[i01] + (srcB[i11] - srcB[i01]) * fx
                        c.b[i] = bTop + (bBot - bTop) * fy

                        val dNearest = srcD[y0 * w + x0].toDouble()
                        c.depth[i] = (dNearest - pose.posZM).coerceAtLeast(0.05).toFloat()
                    }
                }
            }
            return c.toLinearRgbFrame()
        }

        /**
         * Unified 3-in-1 3D Optical Bokeh & Depth Studio (`scene://depth-studio`):
         * - **Foreground (`0.8 m`)**: high-contrast Siemens focus star, depth-scale calibration ruler,
         *   and warm corner HDR foreground pinpoint accents.
         * - **Midground (`2.5 m`)**: calibrated 24-patch Macbeth ColorChecker + multi-frequency
         *   vertical MTF/resolution bars (balanced AE luminance, never blows out or crushes).
         * - **Background (`12.0 m`)**: deep dark blue-hour night city skyline silhouette with compact
         *   HDR office window pinpoints and 28 well-spaced, tight (`1.5..2.4 px`) ultra-bright HDR
         *   fairy lights (`radiance 32..54`) so wide-aperture shaped bokeh masks (`HEART`, `STAR_5`,
         *   `HEXAGON`, `RING`) convolve each pinpoint impulse into a razor-sharp, luminous shape,
         *   while Star 4/6/8-point and Black Mist filters produce vivid diffraction spikes & halation.
         */
        private fun buildDepthStudio(w: Int, h: Int): LinearRgbFrame {
            val c = SceneCanvas(w, h)

            // 1. Background plane (12.0 m): deep dark blue-hour twilight sky for razor-sharp shaped bokeh contrast
            for (y in 0 until h) {
                val t = y.toFloat() / max(1, h - 1).toFloat()
                val horizonGlow = exp(-((t - 0.68f) * (t - 0.68f)) / 0.085f)
                val br = 0.010f + 0.024f * t + 0.018f * horizonGlow
                val bg = 0.015f + 0.026f * t + 0.014f * horizonGlow
                val bb = 0.044f + 0.032f * (1f - t * 0.45f) + 0.012f * horizonGlow
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    c.r[i] = br
                    c.g[i] = bg
                    c.b[i] = bb
                    c.depth[i] = BACKGROUND_DEPTH_M
                }
            }

            // Layered dusk/night city skyline silhouettes at 12.0 m with tight HDR lit window pinpoints
            val towers = arrayOf(
                floatArrayOf(0.00f, 0.11f, 0.36f, 0.012f, 0.015f, 0.026f),
                floatArrayOf(0.10f, 0.21f, 0.27f, 0.010f, 0.013f, 0.022f),
                floatArrayOf(0.20f, 0.32f, 0.41f, 0.014f, 0.017f, 0.028f),
                floatArrayOf(0.31f, 0.44f, 0.25f, 0.010f, 0.013f, 0.022f),
                floatArrayOf(0.43f, 0.56f, 0.33f, 0.013f, 0.016f, 0.026f),
                floatArrayOf(0.55f, 0.68f, 0.23f, 0.010f, 0.013f, 0.022f),
                floatArrayOf(0.67f, 0.79f, 0.37f, 0.012f, 0.015f, 0.026f),
                floatArrayOf(0.78f, 0.90f, 0.28f, 0.011f, 0.014f, 0.023f),
                floatArrayOf(0.89f, 1.00f, 0.42f, 0.014f, 0.017f, 0.028f),
            )
            val winRad = (min(w, h) * 0.0028).coerceIn(1.3, 1.9)
            for ((ti, tSpec) in towers.withIndex()) {
                val x0 = (w * tSpec[0]).roundToInt()
                val rw = (w * (tSpec[1] - tSpec[0])).roundToInt().coerceAtLeast(4)
                val yt = (h * tSpec[2]).roundToInt()
                c.fillRect(x0, yt, rw, h - yt, tSpec[3], tSpec[4], tSpec[5], BACKGROUND_DEPTH_M)
                for (wy in 0 until 4) {
                    val py = yt + ((h * 0.74f - yt) * (wy + 0.5f) / 4f).roundToInt()
                    if (py >= h) continue
                    for (wx in 0 until 3) {
                        if (((ti * 7 + wy * 5 + wx * 3) % 5) != 0) continue
                        val px = x0 + (rw * (wx + 0.5f) / 3f).roundToInt()
                        val warm = ((ti + wy + wx) and 1) == 0
                        c.drawPinpointLight(
                            cx = px,
                            cy = py,
                            radius = winRad,
                            cr = if (warm) 28.0f else 18.0f,
                            cg = if (warm) 21.0f else 24.0f,
                            cb = if (warm) 9.0f else 30.0f,
                            d = BACKGROUND_DEPTH_M,
                        )
                    }
                }
            }

            // 28 well-spaced, tight pinpoint HDR fairy lights at 12.0 m (1.6..2.4 px core, radiance 32..54):
            // Impulse-like pinpoint sources ensure `impulse ⊗ pupilMask` yields razor-sharp ♥ / ★ / ⬡ / ◎ bokeh
            // instead of washing out into round blobs as large 12..25 px circular orbs would!
            val fairyPalette = arrayOf(
                floatArrayOf(50.0f, 36.0f, 9.5f),  // warm tungsten gold
                floatArrayOf(46.0f, 48.0f, 52.0f), // crisp starlight white
                floatArrayOf(10.0f, 40.0f, 52.0f), // electric cyan
                floatArrayOf(52.0f, 12.0f, 28.0f), // rose magenta
                floatArrayOf(54.0f, 24.0f, 6.5f),  // glowing amber
                floatArrayOf(12.0f, 46.0f, 24.0f), // mint emerald
            )
            // Generous spacing (~9..12% W, ~12..15% H) around midground Macbeth card (x in 0.27..0.73, y in 0.24..0.74)
            // so individual 20..28 px radius bokeh shapes stand out cleanly without overlapping into mud.
            val fairyLights = arrayOf(
                // Upper sky band (y = 0.07..0.19)
                floatArrayOf(0.06f, 0.10f, 0f, 1.05f),
                floatArrayOf(0.16f, 0.14f, 2f, 1.00f),
                floatArrayOf(0.26f, 0.09f, 3f, 1.05f),
                floatArrayOf(0.37f, 0.13f, 1f, 1.00f),
                floatArrayOf(0.48f, 0.08f, 4f, 1.10f),
                floatArrayOf(0.59f, 0.13f, 5f, 1.00f),
                floatArrayOf(0.70f, 0.09f, 0f, 1.05f),
                floatArrayOf(0.81f, 0.14f, 2f, 1.00f),
                floatArrayOf(0.92f, 0.10f, 3f, 1.05f),
                // Left twilight column (x = 0.05..0.22, y = 0.24..0.50)
                floatArrayOf(0.06f, 0.26f, 4f, 1.00f),
                floatArrayOf(0.18f, 0.28f, 1f, 0.95f),
                floatArrayOf(0.08f, 0.40f, 2f, 1.05f),
                floatArrayOf(0.20f, 0.43f, 3f, 1.00f),
                floatArrayOf(0.13f, 0.50f, 5f, 0.95f),
                // Right twilight column (x = 0.78..0.95, y = 0.24..0.52)
                floatArrayOf(0.79f, 0.26f, 0f, 1.00f),
                floatArrayOf(0.92f, 0.27f, 1f, 1.05f),
                floatArrayOf(0.81f, 0.39f, 4f, 1.00f),
                floatArrayOf(0.93f, 0.41f, 2f, 1.05f),
                floatArrayOf(0.86f, 0.51f, 3f, 0.95f),
                // Secondary upper-sky accents (well clear of neighbors)
                floatArrayOf(0.11f, 0.20f, 5f, 0.90f),
                floatArrayOf(0.32f, 0.18f, 0f, 0.90f),
                floatArrayOf(0.54f, 0.18f, 3f, 0.90f),
                floatArrayOf(0.75f, 0.19f, 1f, 0.90f),
                floatArrayOf(0.87f, 0.19f, 4f, 0.90f),
                // Lower-center bokeh strip beneath Macbeth card (y = 0.83..0.91)
                floatArrayOf(0.34f, 0.86f, 0f, 1.05f),
                floatArrayOf(0.45f, 0.89f, 2f, 1.00f),
                floatArrayOf(0.56f, 0.89f, 3f, 1.00f),
                floatArrayOf(0.67f, 0.86f, 4f, 1.05f),
            )
            val fairyBaseRad = (min(w, h) * 0.0036).coerceIn(1.5, 2.3)
            for (fl in fairyLights) {
                val col = fairyPalette[fl[2].toInt() % fairyPalette.size]
                c.drawPinpointLight(
                    cx = (w * fl[0]).roundToInt(),
                    cy = (h * fl[1]).roundToInt(),
                    radius = fairyBaseRad * fl[3].toDouble(),
                    cr = col[0],
                    cg = col[1],
                    cb = col[2],
                    d = BACKGROUND_DEPTH_M,
                )
            }

            // 2. Midground plane (2.5 m): calibrated 24-patch Macbeth ColorChecker + MTF resolution wedges
            val midX = (w * 0.27f).roundToInt()
            val midY = (h * 0.24f).roundToInt()
            val midW = (w * 0.46f).roundToInt()
            val midH = (h * 0.50f).roundToInt()
            c.fillRect(midX - 8, midY - 8, midW + 16, midH + 16, 0.72f, 0.72f, 0.70f, MIDGROUND_DEPTH_M)
            c.drawMacbethChart(midX, midY, midW, (midH * 0.72f).roundToInt(), MIDGROUND_DEPTH_M)
            // High-contrast vertical resolution bars under Macbeth chart at 2.5 m
            val barY = midY + (midH * 0.76f).roundToInt()
            val barH = (midH * 0.20f).roundToInt().coerceAtLeast(8)
            for (x in midX until (midX + midW)) {
                val rel = (x - midX).toFloat() / midW.toFloat()
                val period = when {
                    rel < 0.33f -> 12
                    rel < 0.66f -> 6
                    else -> 3
                }
                val v = if (((x - midX) / period) % 2 == 0) 0.90f else 0.03f
                for (y in barY until (barY + barH)) {
                    c.setPixel(x, y, v, v, v, MIDGROUND_DEPTH_M)
                }
            }

            // 3. Foreground plane (0.8 m): near-field Siemens star (left) + depth-scale ruler card (right) + warm bokeh accents
            val fgCardX = (w * 0.03f).roundToInt()
            val fgCardY = (h * 0.56f).roundToInt()
            val fgCardW = (w * 0.22f).roundToInt()
            val fgCardH = (h * 0.38f).roundToInt()
            c.fillRect(fgCardX, fgCardY, fgCardW, fgCardH, 0.88f, 0.88f, 0.86f, FOREGROUND_DEPTH_M)
            c.fillRect(fgCardX + 4, fgCardY + 4, fgCardW - 8, fgCardH - 8, 0.08f, 0.08f, 0.09f, FOREGROUND_DEPTH_M)
            c.drawSiemensStar(
                cx = fgCardX + fgCardW / 2,
                cy = fgCardY + fgCardH / 2,
                radius = (min(fgCardW, fgCardH) * 0.42f).roundToInt().coerceAtLeast(10),
                spokes = 18,
                d = FOREGROUND_DEPTH_M,
            )

            // Foreground focus-scale ruler card on bottom-right at 0.8 m
            val ruleX = (w * 0.76f).roundToInt()
            val ruleY = (h * 0.58f).roundToInt()
            val ruleW = (w * 0.21f).roundToInt()
            val ruleH = (h * 0.36f).roundToInt()
            c.fillRect(ruleX, ruleY, ruleW, ruleH, 0.90f, 0.89f, 0.84f, FOREGROUND_DEPTH_M)
            c.fillRect(ruleX + 5, ruleY + 5, ruleW - 10, 10, 0.85f, 0.12f, 0.08f, FOREGROUND_DEPTH_M)
            val tickCount = 12
            val tickStep = (ruleH - 36).coerceAtLeast(12) / tickCount
            for (t in 0..tickCount) {
                val ty = ruleY + 24 + t * tickStep
                val tickW = if (t % 3 == 0) (ruleW * 0.72f).roundToInt() else (ruleW * 0.42f).roundToInt()
                c.fillRect(ruleX + 12, ty, tickW, 3, 0.04f, 0.04f, 0.05f, FOREGROUND_DEPTH_M)
            }

            // Near foreground warm pinpoint fairy-light accents (0.8 m) flanking lower foreground cards
            val fgAccentRad = (min(w, h) * 0.0034).coerceIn(1.5, 2.2)
            c.drawPinpointLight((w * 0.27f).roundToInt(), (h * 0.92f).roundToInt(), fgAccentRad, 38.0f, 26.0f, 9.0f, FOREGROUND_DEPTH_M)
            c.drawPinpointLight((w * 0.73f).roundToInt(), (h * 0.92f).roundToInt(), fgAccentRad, 36.0f, 22.0f, 8.0f, FOREGROUND_DEPTH_M)

            return c.toLinearRgbFrame()
        }

        private fun buildBokehCity(w: Int, h: Int): LinearRgbFrame {
            val c = SceneCanvas(w, h)

            // 1. Background plane (12.0 m): luminous blue-hour twilight sky + dusk skyline + true-HDR neon bokeh field
            for (y in 0 until h) {
                val t = y.toFloat() / max(1, h - 1).toFloat()
                val horizonGlow = exp(-((t - 0.68f) * (t - 0.68f)) / 0.09f)
                val row = y * w
                val sr = 0.024f + 0.052f * t + 0.032f * horizonGlow
                val sg = 0.034f + 0.044f * t + 0.022f * horizonGlow
                val sb = 0.072f + 0.036f * (1f - t * 0.4f) + 0.018f * horizonGlow
                for (x in 0 until w) {
                    val i = row + x
                    c.r[i] = sr
                    c.g[i] = sg
                    c.b[i] = sb
                    c.depth[i] = BACKGROUND_DEPTH_M
                }
            }

            // Layered dusk city skyline silhouettes at 12.0 m with HDR lit office windows
            val towers = arrayOf(
                floatArrayOf(0.00f, 0.11f, 0.34f, 0.032f, 0.038f, 0.056f),
                floatArrayOf(0.10f, 0.21f, 0.25f, 0.028f, 0.034f, 0.052f),
                floatArrayOf(0.20f, 0.33f, 0.38f, 0.035f, 0.040f, 0.058f),
                floatArrayOf(0.32f, 0.45f, 0.22f, 0.026f, 0.032f, 0.048f),
                floatArrayOf(0.44f, 0.56f, 0.32f, 0.034f, 0.039f, 0.055f),
                floatArrayOf(0.55f, 0.68f, 0.20f, 0.025f, 0.031f, 0.047f),
                floatArrayOf(0.67f, 0.79f, 0.36f, 0.033f, 0.038f, 0.056f),
                floatArrayOf(0.78f, 0.90f, 0.27f, 0.028f, 0.034f, 0.050f),
                floatArrayOf(0.89f, 1.00f, 0.40f, 0.034f, 0.040f, 0.058f),
            )
            val winRad = max(1.4, min(w, h) * 0.0032)
            for ((ti, tSpec) in towers.withIndex()) {
                val x0 = (w * tSpec[0]).roundToInt()
                val rw = (w * (tSpec[1] - tSpec[0])).roundToInt().coerceAtLeast(4)
                val yt = (h * tSpec[2]).roundToInt()
                c.fillRect(x0, yt, rw, h - yt, tSpec[3], tSpec[4], tSpec[5], BACKGROUND_DEPTH_M)
                // Crisp HDR skyline window pinpoints (radiance 3.8..5.0)
                val cols = 3
                val rows = 4
                for (wy in 0 until rows) {
                    val py = yt + ((h * 0.76f - yt) * (wy + 0.5f) / rows).roundToInt()
                    if (py >= h) continue
                    for (wx in 0 until cols) {
                        if (((ti * 7 + wy * 5 + wx * 3) % 4) != 0) continue
                        val px = x0 + (rw * (wx + 0.5f) / cols).roundToInt()
                        val warm = ((ti + wy + wx) and 1) == 0
                        c.drawPinpointLight(
                            cx = px,
                            cy = py,
                            radius = winRad,
                            cr = if (warm) 4.8f else 3.6f,
                            cg = if (warm) 3.9f else 4.2f,
                            cb = if (warm) 2.0f else 4.9f,
                            d = BACKGROUND_DEPTH_M,
                        )
                    }
                }
            }

            // 36 true-HDR pinpoint neon city bokeh lights at 12.0 m (1.5..2.3 px core, radiance 30..50)
            // so shaped bokeh masks (HEART / STAR_5 / HEXAGON / RING) stay razor-sharp without disc-washout.
            val palette = arrayOf(
                floatArrayOf(48.0f, 35.0f, 9.5f),  // warm sodium gold
                floatArrayOf(10.0f, 38.0f, 48.0f), // electric cyan
                floatArrayOf(48.0f, 12.0f, 24.0f), // neon magenta-rose
                floatArrayOf(44.0f, 42.0f, 38.0f), // crisp streetlamp white
                floatArrayOf(12.0f, 44.0f, 24.0f), // emerald neon
                floatArrayOf(50.0f, 24.0f, 7.0f),  // warm tangerine amber
            )
            val cityBaseRad = (min(w, h) * 0.0034).coerceIn(1.5, 2.2)
            for (idx in 0 until 36) {
                val fx = ((idx * 37 + 11) % 94 + 3) / 100f
                val fy = ((idx * 23 + 7) % 62 + 8) / 100f
                val rad = cityBaseRad * (0.9 + (idx % 3) * 0.12)
                val col = palette[idx % palette.size]
                c.drawPinpointLight(
                    cx = (w * fx).roundToInt(),
                    cy = (h * fy).roundToInt(),
                    radius = rad,
                    cr = col[0],
                    cg = col[1],
                    cb = col[2],
                    d = BACKGROUND_DEPTH_M,
                )
            }

            // 2. Midground plane (2.5 m): illuminated street sign & horizontal rail with balanced ambient contrast
            val signX = (w * 0.34f).roundToInt()
            val signY = (h * 0.30f).roundToInt()
            val signW = (w * 0.32f).roundToInt()
            val signH = (h * 0.34f).roundToInt()
            c.fillRect(signX, signY, signW, signH, 0.11f, 0.13f, 0.18f, MIDGROUND_DEPTH_M)
            // Neon cyan border frame (calibrated SDR so midground never clips under Multi-AE)
            val border = 6
            c.fillRect(signX, signY, signW, border, 0.22f, 0.88f, 0.96f, MIDGROUND_DEPTH_M)
            c.fillRect(signX, signY + signH - border, signW, border, 0.22f, 0.88f, 0.96f, MIDGROUND_DEPTH_M)
            c.fillRect(signX, signY, border, signH, 0.22f, 0.88f, 0.96f, MIDGROUND_DEPTH_M)
            c.fillRect(signX + signW - border, signY, border, signH, 0.22f, 0.88f, 0.96f, MIDGROUND_DEPTH_M)
            // Mini 6-color strip inside sign at 2.5 m
            val stripW = (signW - 28) / 6
            for (k in 0 until 6) {
                c.fillRectSrgb(signX + 14 + k * stripW, signY + 20, stripW - 4, (signH * 0.36f).roundToInt(), MACBETH_SRGB[12 + k], MIDGROUND_DEPTH_M)
            }
            // High-contrast zebra grating for midground sharpness check
            val zebY = signY + (signH * 0.66f).roundToInt()
            val zebH = (signH * 0.22f).roundToInt()
            for (x in (signX + 14) until (signX + signW - 14)) {
                val v = if (((x - signX) / 5) % 2 == 0) 0.88f else 0.06f
                for (y in zebY until (zebY + zebH)) {
                    c.setPixel(x, y, v, v, v, MIDGROUND_DEPTH_M)
                }
            }

            // 3. Foreground plane (0.8 m): near railing with lifted shadow detail + close focus reticle
            val railY = (h * 0.80f).roundToInt()
            c.fillRect(0, railY, w, h - railY, 0.065f, 0.070f, 0.088f, FOREGROUND_DEPTH_M)
            c.fillRect(0, railY, w, 5, 0.82f, 0.76f, 0.64f, FOREGROUND_DEPTH_M)
            for (x in 16 until w step 28) {
                c.fillRect(x, railY + 12, 4, (h - railY - 20).coerceAtLeast(8), 0.68f, 0.66f, 0.62f, FOREGROUND_DEPTH_M)
            }
            // Foreground star badge on lower-left at 0.8 m
            c.drawSiemensStar(
                cx = (w * 0.14f).roundToInt(),
                cy = (h * 0.74f).roundToInt(),
                radius = (min(w, h) * 0.12f).roundToInt().coerceAtLeast(10),
                spokes = 16,
                d = FOREGROUND_DEPTH_M,
            )

            return c.toLinearRgbFrame()
        }

        private fun buildResolutionChart(w: Int, h: Int): LinearRgbFrame {
            val c = SceneCanvas(w, h)

            // 1. Background plane (12.0 m): outer distortion grid & 4 corner beacon orbs for CA/distortion inspection
            c.fillRect(0, 0, w, h, 0.12f, 0.12f, 0.13f, BACKGROUND_DEPTH_M)
            val gridStep = (min(w, h) / 14).coerceAtLeast(12)
            for (y in 0 until h) {
                val onH = y % gridStep == 0
                for (x in 0 until w) {
                    if (onH || x % gridStep == 0) {
                        c.setPixel(x, y, 0.78f, 0.78f, 0.80f, BACKGROUND_DEPTH_M)
                    }
                }
            }
            val cornerOffset = (min(w, h) * 0.07f).roundToInt().coerceAtLeast(8)
            val cornerRad = (min(w, h) * 0.032f).roundToInt().coerceAtLeast(5)
            c.drawOrb(cornerOffset, cornerOffset, cornerRad, 1.0f, 0.95f, 0.85f, BACKGROUND_DEPTH_M)
            c.drawOrb(w - cornerOffset, cornerOffset, cornerRad, 0.85f, 0.95f, 1.0f, BACKGROUND_DEPTH_M)
            c.drawOrb(cornerOffset, h - cornerOffset, cornerRad, 1.0f, 0.85f, 0.95f, BACKGROUND_DEPTH_M)
            c.drawOrb(w - cornerOffset, h - cornerOffset, cornerRad, 0.95f, 1.0f, 0.85f, BACKGROUND_DEPTH_M)

            // 2. Midground plane (2.5 m): ISO-12233-inspired white resolution target card
            val cardX = (w * 0.13f).roundToInt()
            val cardY = (h * 0.12f).roundToInt()
            val cardW = (w * 0.74f).roundToInt()
            val cardH = (h * 0.76f).roundToInt()
            c.fillRect(cardX, cardY, cardW, cardH, 0.92f, 0.92f, 0.92f, MIDGROUND_DEPTH_M)
            // Outer black framing rectangle
            c.fillRect(cardX, cardY, cardW, 4, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)
            c.fillRect(cardX, cardY + cardH - 4, cardW, 4, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)
            c.fillRect(cardX, cardY, 4, cardH, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)
            c.fillRect(cardX + cardW - 4, cardY, 4, cardH, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)

            // Center Siemens star at 2.5 m
            val starRadius = (min(cardW, cardH) * 0.24f).roundToInt().coerceAtLeast(16)
            c.drawSiemensStar(w / 2, (h * 0.45f).roundToInt(), starRadius, spokes = 24, d = MIDGROUND_DEPTH_M)

            // Left & right slanted knife-edge MTF blocks at 2.5 m
            val mtfW = (cardW * 0.16f).roundToInt()
            val mtfH = (cardH * 0.30f).roundToInt()
            val mtfY = cardY + (cardH * 0.14f).roundToInt()
            val leftMtfX = cardX + (cardW * 0.06f).roundToInt()
            val rightMtfX = cardX + cardW - (cardW * 0.06f).roundToInt() - mtfW
            for (dy in 0 until mtfH) {
                val skew = (dy * 0.0875).roundToInt() // ~5 degree slant
                for (dx in 0 until mtfW) {
                    if (dx + skew < mtfW / 2) {
                        c.setPixel(leftMtfX + dx, mtfY + dy, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)
                        c.setPixel(rightMtfX + dx, mtfY + dy, 0.03f, 0.03f, 0.03f, MIDGROUND_DEPTH_M)
                    }
                }
            }

            // Multi-frequency horizontal line-pair sweep below center at 2.5 m
            val lpX = cardX + (cardW * 0.12f).roundToInt()
            val lpY = cardY + (cardH * 0.68f).roundToInt()
            val lpW = (cardW * 0.76f).roundToInt()
            val lpH = (cardH * 0.12f).roundToInt()
            for (x in lpX until (lpX + lpW)) {
                val u = (x - lpX).toDouble() / lpW.toDouble()
                val phase = 2.0 * PI * (4.0 * u + 44.0 * u * u)
                val v = if (cos(phase) >= 0.0) 0.92f else 0.03f
                for (y in lpY until (lpY + lpH)) {
                    c.setPixel(x, y, v, v, v, MIDGROUND_DEPTH_M)
                }
            }

            // Bottom 12-patch color & grayscale reference strip at 2.5 m
            val stripY = cardY + (cardH * 0.83f).roundToInt()
            val stripH = (cardH * 0.12f).roundToInt().coerceAtLeast(6)
            val patchW = (lpW / 12).coerceAtLeast(4)
            for (k in 0 until 12) {
                c.fillRectSrgb(lpX + k * patchW, stripY, patchW - 2, stripH, MACBETH_SRGB[12 + k], MIDGROUND_DEPTH_M)
            }

            // 3. Foreground plane (0.8 m): near-field depth inset card on bottom-left & focus bar on top-right
            val fgW = (w * 0.15f).roundToInt()
            val fgH = (h * 0.24f).roundToInt()
            val fgX = (w * 0.04f).roundToInt()
            val fgY = h - fgH - (h * 0.04f).roundToInt()
            c.fillRect(fgX, fgY, fgW, fgH, 0.94f, 0.92f, 0.84f, FOREGROUND_DEPTH_M)
            c.drawSiemensStar(
                cx = fgX + fgW / 2,
                cy = fgY + fgH / 2,
                radius = (min(fgW, fgH) * 0.38f).roundToInt().coerceAtLeast(8),
                spokes = 12,
                d = FOREGROUND_DEPTH_M,
            )

            // Second foreground target badge at top-right (0.8 m)
            val fg2X = w - fgW - (w * 0.04f).roundToInt()
            val fg2Y = (h * 0.04f).roundToInt()
            c.fillRect(fg2X, fg2Y, fgW, (fgH * 0.65f).roundToInt(), 0.08f, 0.08f, 0.10f, FOREGROUND_DEPTH_M)
            for (dx in 8 until (fgW - 8)) {
                val v = if ((dx / 4) % 2 == 0) 0.94f else 0.05f
                for (dy in 8 until ((fgH * 0.65f).roundToInt() - 8)) {
                    c.setPixel(fg2X + dx, fg2Y + dy, v, v, v, FOREGROUND_DEPTH_M)
                }
            }

            return c.toLinearRgbFrame()
        }

        /**
         * Focus card at 1.35 m with distant coloured pinpoints. The GF110 crop includes
         * the card and an inner group of lights, even at the long end of the simulated optics.
         */
        private fun buildStarBokeh(w: Int, h: Int): LinearRgbFrame {
            val c = SceneCanvas(w, h)
            val fgDepth = 1.35f
            val bgDefaultDepth = 7.0f

            // 1. Balanced twilight gradient sky at 7.0 m: rich dusk depth for high bokeh contrast
            //    without forcing Multi-AE into excessive positive gain that clips the foreground card.
            for (y in 0 until h) {
                val t = y.toFloat() / max(1, h - 1).toFloat()
                val skyR = 0.022f + 0.020f * t
                val skyG = 0.029f + 0.025f * t
                val skyB = 0.055f + 0.038f * t
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    c.r[idx] = skyR
                    c.g[idx] = skyG
                    c.b[idx] = skyB
                    c.depth[idx] = bgDefaultDepth
                }
            }

            // Soft distant dusk treeline/horizon silhouette along bottom 22% at 6.5 m.
            val horizonY = (h * 0.78f).roundToInt()
            c.fillRect(0, horizonY, w, h - horizonY, 0.016f, 0.020f, 0.030f, 6.5f)

            // Compact HDR lights at different depths let the active entrance pupil determine the blur shape.
            val palette = arrayOf(
                floatArrayOf(48.0f, 34.0f, 9.5f),  // warm tungsten gold
                floatArrayOf(52.0f, 22.0f, 6.0f),  // amber candle
                floatArrayOf(42.0f, 44.0f, 48.0f), // crisp starlight white
                floatArrayOf(48.0f, 12.0f, 26.0f), // rose magenta
                floatArrayOf(10.0f, 38.0f, 50.0f), // electric cyan
                floatArrayOf(12.0f, 44.0f, 22.0f), // mint emerald
            )
            // Curated well-spaced fairy-light field (u, v, depthM, paletteIdx, radiusScale)
            val lights = arrayOf(
                floatArrayOf(0.07f, 0.12f, 7.5f, 0f, 1.0f),
                floatArrayOf(0.15f, 0.22f, 6.2f, 2f, 1.1f),
                floatArrayOf(0.24f, 0.11f, 8.2f, 4f, 1.0f),
                floatArrayOf(0.34f, 0.17f, 6.8f, 1f, 1.1f),
                floatArrayOf(0.45f, 0.10f, 8.5f, 0f, 1.0f),
                floatArrayOf(0.56f, 0.13f, 7.0f, 3f, 1.1f),
                floatArrayOf(0.67f, 0.11f, 8.0f, 2f, 1.0f),
                floatArrayOf(0.77f, 0.19f, 6.4f, 0f, 1.1f),
                floatArrayOf(0.86f, 0.12f, 7.8f, 4f, 1.0f),
                floatArrayOf(0.94f, 0.21f, 6.0f, 1f, 1.0f),
                floatArrayOf(0.06f, 0.34f, 6.6f, 3f, 1.1f),
                floatArrayOf(0.14f, 0.42f, 7.4f, 0f, 1.0f),
                floatArrayOf(0.23f, 0.31f, 5.8f, 5f, 1.0f),
                floatArrayOf(0.31f, 0.39f, 8.4f, 2f, 1.0f),
                floatArrayOf(0.41f, 0.27f, 6.5f, 0f, 1.1f),
                floatArrayOf(0.51f, 0.24f, 7.6f, 4f, 1.0f),
                floatArrayOf(0.62f, 0.28f, 6.1f, 1f, 1.1f),
                floatArrayOf(0.72f, 0.35f, 8.6f, 5f, 1.0f),
                floatArrayOf(0.82f, 0.31f, 6.9f, 3f, 1.1f),
                floatArrayOf(0.92f, 0.38f, 7.3f, 2f, 1.0f),
                floatArrayOf(0.08f, 0.56f, 7.9f, 4f, 1.0f),
                floatArrayOf(0.17f, 0.63f, 6.0f, 1f, 1.1f),
                floatArrayOf(0.27f, 0.54f, 7.1f, 0f, 1.0f),
                floatArrayOf(0.11f, 0.74f, 5.7f, 2f, 1.0f),
                floatArrayOf(0.22f, 0.78f, 8.1f, 3f, 1.0f),
                floatArrayOf(0.30f, 0.69f, 6.5f, 5f, 1.0f),
                floatArrayOf(0.70f, 0.52f, 7.2f, 0f, 1.1f),
                floatArrayOf(0.79f, 0.48f, 6.3f, 4f, 1.0f),
                floatArrayOf(0.89f, 0.55f, 8.4f, 1f, 1.0f),
                floatArrayOf(0.74f, 0.68f, 6.8f, 2f, 1.1f),
                floatArrayOf(0.84f, 0.65f, 5.9f, 0f, 1.0f),
                floatArrayOf(0.93f, 0.72f, 7.7f, 3f, 1.0f),
                floatArrayOf(0.68f, 0.80f, 8.2f, 5f, 1.0f),
                floatArrayOf(0.80f, 0.82f, 6.4f, 1f, 1.0f),
                floatArrayOf(0.91f, 0.86f, 7.0f, 4f, 1.0f),
                floatArrayOf(0.05f, 0.85f, 6.8f, 0f, 1.0f),
                floatArrayOf(0.16f, 0.89f, 7.5f, 5f, 1.0f),
                floatArrayOf(0.28f, 0.88f, 6.2f, 2f, 1.0f),
                // Smaller, deeper lamps around the target remain visible inside the GF110 crop.
                floatArrayOf(0.41f, 0.43f, 8.8f, 2f, 0.85f),
                floatArrayOf(0.44f, 0.35f, 8.9f, 0f, 0.85f),
                floatArrayOf(0.49f, 0.375f, 8.6f, 3f, 0.85f),
                floatArrayOf(0.54f, 0.335f, 9.0f, 5f, 0.85f),
                floatArrayOf(0.585f, 0.41f, 8.7f, 1f, 0.85f),
                floatArrayOf(0.615f, 0.505f, 8.9f, 4f, 0.85f),
                floatArrayOf(0.565f, 0.57f, 8.5f, 0f, 0.85f),
                floatArrayOf(0.585f, 0.645f, 8.8f, 2f, 0.85f),
                floatArrayOf(0.425f, 0.605f, 9.0f, 5f, 0.85f),
                floatArrayOf(0.385f, 0.53f, 8.7f, 3f, 0.85f),
                floatArrayOf(0.29f, 0.21f, 8.8f, 0f, 0.85f),
                floatArrayOf(0.73f, 0.26f, 8.6f, 2f, 0.85f),
                floatArrayOf(0.09f, 0.67f, 8.9f, 1f, 0.85f),
                floatArrayOf(0.88f, 0.77f, 8.7f, 4f, 0.85f),
            )
            val baseRad = (min(w, h) * 0.0034).coerceIn(1.5, 2.2)
            for (l in lights) {
                val col = palette[l[3].toInt() % palette.size]
                c.drawPinpointLight(
                    cx = (w * l[0]).roundToInt(),
                    cy = (h * l[1]).roundToInt(),
                    radius = baseRad * l[4].toDouble(),
                    cr = col[0],
                    cg = col[1],
                    cb = col[2],
                    d = l[2],
                )
            }

            // 3. Illuminated foreground focus target & miniature studio pedestal at z = 1.35 m
            //    (centered horizontally so center AF-S/AF-C locks cleanly at 1.35 m while keeping
            //     upper/side bokeh field unobstructed).
            val cardW = (w * 0.108f).roundToInt().coerceAtLeast(1)
            val cardH = (h * 0.22f).roundToInt().coerceAtLeast(1)
            val cardX = (w - cardW) / 2
            val cardY = (h * 0.418f).roundToInt()
            val border = (min(cardW, cardH) * 0.035f).roundToInt().coerceAtLeast(1)
            val inset = border * 2
            val pedW = (cardW * 0.48f).roundToInt().coerceAtLeast(1)
            val pedX = (w - pedW) / 2
            val pedY = cardY + cardH
            c.fillRect(pedX, pedY, pedW, h - pedY, 0.18f, 0.16f, 0.15f, fgDepth)
            c.fillRect(pedX + border, pedY, pedW - 2 * border, border, 0.52f, 0.42f, 0.24f, fgDepth)

            c.fillRect(cardX, cardY, cardW, cardH, 0.58f, 0.56f, 0.52f, fgDepth)
            c.fillRect(cardX + border, cardY + border, cardW - 2 * border, cardH - 2 * border, 0.11f, 0.11f, 0.13f, fgDepth)
            val headerH = (cardH * 0.075f).roundToInt().coerceAtLeast(1)
            c.fillRect(cardX + inset, cardY + inset, cardW - 2 * inset, headerH, 0.72f, 0.52f, 0.20f, fgDepth)

            val starCx = cardX + cardW / 2
            val starCy = cardY + (cardH * 0.46f).roundToInt()
            val starRad = (min(cardW, cardH) * 0.30f).roundToInt().coerceAtLeast(1)
            c.drawSiemensStar(starCx, starCy, starRad, spokes = 20, d = fgDepth, bright = 0.68f)

            val barW = cardW - 2 * inset
            val barY = cardY + (cardH * 0.80f).roundToInt()
            val barH = (cardH * 0.12f).roundToInt().coerceAtLeast(1)
            val fineBar = (cardW * 0.02f).roundToInt().coerceAtLeast(1)
            for (dx in 0 until barW) {
                val stripe = if (dx < barW / 2) fineBar * 2 else fineBar
                val v = if ((dx / stripe) % 2 == 0) 0.66f else 0.05f
                c.fillRect(cardX + inset + dx, barY, 1, barH, v, v, v, fgDepth)
            }

            return c.toLinearRgbFrame()
        }

        // Helper for unit tests verifying exact metric distance presence.
        internal fun distinctDepthPlanes(frame: LinearRgbFrame): Set<Float> {
            val d = frame.depthMeters ?: return emptySet()
            val set = LinkedHashSet<Float>()
            for (v in d) {
                set.add(v)
            }
            return set
        }

        // Keeps kotlin.math.hypot referenced for radial optics helpers if extended later.
        @Suppress("unused")
        internal fun radialDistance(dx: Double, dy: Double): Double = hypot(dx, dy)
    }
}

/**
 * Built-in M-Optics `optical-scene` [SceneSourceProvider] registered in [SceneSourceRegistry].
 */
class ProceduralOpticsSceneSourceProvider(
    private val width: Int = ProceduralOpticsSceneSource.DEFAULT_WIDTH,
    private val height: Int = ProceduralOpticsSceneSource.DEFAULT_HEIGHT,
) : SceneSourceProvider {
    override val supportedKinds: Set<String> = setOf(ProceduralOpticsSceneSource.KIND)
    override val capability: SceneSourceCapability = SceneSourceCapability.READY

    override fun pipelineStatus(kind: String): String = "optical-scene-procedural-depth-readout"

    override fun validate(request: SceneSourceRequest) {
        require(request.kind == ProceduralOpticsSceneSource.KIND) {
            "Provider for ${ProceduralOpticsSceneSource.KIND} cannot validate ${request.kind}"
        }
        val preset = OpticalScenePreset.parse(request.sourceRef)
        if (preset == OpticalScenePreset.QR_BALL) {
            QrBallRotation.parse(request.sourceRef)
        } else if (preset == OpticalScenePreset.MONA_LISA) {
            MonaLisaSceneSource.parseAndOpen(request.sourceRef, width = 64, height = 48, loop = request.loop).close()
        } else if (preset == OpticalScenePreset.MILK_FROG) {
            MilkFrogSceneSource.parseAndOpen(request.sourceRef, width = 64, height = 48, loop = request.loop).close()
        }
    }

    override fun open(request: SceneSourceRequest): SceneSource = open(request, System::nanoTime)

    internal fun open(request: SceneSourceRequest, nanoClock: () -> Long): SceneSource {
        val preset = OpticalScenePreset.parse(request.sourceRef)
        if (preset == OpticalScenePreset.MONA_LISA) {
            return MonaLisaSceneSource.parseAndOpen(
                sourceRef = request.sourceRef,
                width = width,
                height = height,
                loop = request.loop,
                nanoClock = nanoClock,
            )
        }
        if (preset == OpticalScenePreset.MILK_FROG) {
            return MilkFrogSceneSource.parseAndOpen(
                sourceRef = request.sourceRef,
                width = width,
                height = height,
                loop = request.loop,
                nanoClock = nanoClock,
            )
        }
        if (preset == OpticalScenePreset.QR_BALL) {
            val rotation = QrBallRotation.parse(request.sourceRef, nanoClock)
            return if (rotation.moving) {
                RotatingQrBallSource(width, height, request, rotation)
            } else {
                ProceduralOpticsSceneSource(preset, width, height, request.loop, rotation, request.sourceRef)
            }
        }
        return ProceduralOpticsSceneSource(
            preset = preset,
            width = width,
            height = height,
            loop = request.loop,
        )
    }
}
