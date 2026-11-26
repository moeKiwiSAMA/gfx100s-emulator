package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource
import com.github.nyabanban.fuji.emulator.api.SceneSource2D
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pixel layout accepted by live external CMOS scene sources (`ffmpeg-pipe` and `stream-ingest`).
 */
enum class RawPixelFormat(val code: Int, val bytesPerPixel: Int) {
    RGB24(0, 3),
    RGBA32(1, 4),
    BGRA32(2, 4);

    companion object {
        fun fromCode(code: Int): RawPixelFormat = when (code) {
            0 -> RGB24
            1 -> RGBA32
            2 -> BGRA32
            else -> throw IllegalArgumentException("Unsupported pixelFormat code: $code")
        }

        fun fromName(name: String): RawPixelFormat = when (name.trim().lowercase()) {
            "rgb", "rgb24" -> RGB24
            "rgba", "rgba32" -> RGBA32
            "bgra", "bgra32" -> BGRA32
            else -> throw IllegalArgumentException("Unsupported pixel format: $name")
        }
    }
}

/**
 * Planar linear-radiance RGB frame (values in `[0.0f, 1.0f]`) with optional metric depth buffer
 * (`depthMeters`, length `width * height`) consumed by [com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter].
 */
class LinearRgbFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    val depthMeters: FloatArray? = null,
) {
    /** Zero-copy view wrapping this planar RGB(+Depth) frame as a typed [LinearRadianceFrame]. */
    fun toRadianceFrame(
        dimension: SceneDimension = if (depthMeters != null) SceneDimension.DIM_3D else SceneDimension.DIM_2D,
        virtualSubjectDistanceM: Double? = null,
        pose: CameraPose3D = CameraPose3D.IDENTITY,
        sequence: Long = 0L,
    ): LinearRadianceFrame = LinearRadianceFrame(
        width = width,
        height = height,
        red = red,
        green = green,
        blue = blue,
        depthMeters = depthMeters,
        dimension = dimension,
        virtualSubjectDistanceM = virtualSubjectDistanceM,
        pose = pose,
        sequence = sequence,
    )
}

/**
 * Optional capability implemented by CMOS [SceneSource] backends that can provide planar linear RGB
 * frames directly to an active [com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter].
 */
interface LinearRgbFrameSource {
    /** Returns the latest linear-RGB frame (or fallback frame) for optical filtering. */
    fun latestLinearRgbFrame(): LinearRgbFrame
}

/**
 * Runtime diagnostics reported by built-in CMOS [SceneSource] implementations and surfaced via
 * `cmos.state()` for the GUI source selector and telemetry logs.
 */
internal interface SceneSourceTelemetry {
    val activeSourceRef: String
    val sourceWidth: Int
    val sourceHeight: Int
    val hasDepthBuffer: Boolean
    val lastSourceError: String?
    val framesReceived: Long
    val framesDropped: Long
}

/**
 * Marker implemented by live asynchronously-updating streaming [SceneSource] backends
 * (`ffmpeg-pipe`, `screen-capture`, `stream-ingest`) so [CmosFactory] can offload optical
 * filtering onto a non-blocking double-buffered background worker without altering static
 * or unit-test synchronous determinism.
 */
internal interface LiveStreamSceneSource : LinearRgbFrameSource, SceneSourceTelemetry {
    /** Monotonic sequence of the latest published live frame (`0L` while serving fallback). */
    val latestFrameSequence: Long

    /** Exact generation of the pixels sampled by the fused ISP, or null when it cannot be guaranteed. */
    val fusedFrameSequence: Long? get() = null
}

/**
 * Single-slot packed Bayer cache for live streaming [SceneSource] implementations.
 *
 * Avoids repeating RGB24/RGBA32/BGRA32 -> Bayer conversion when ISP LiveView polls faster
 * than the external video/capture source produces new frames (e.g. 60 Hz ISP vs 24 fps capture).
 */
internal class CachedPackedBayerConverter {
    private var cachedSeq: Long = -1L
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedBitDepth: Int = -1
    private var cachedBayer: BayerPattern? = null
    private var cachedGainBits: Long = 0L
    private var cachedBytes: ByteArray? = null
    private var cachedLen: Int = 0

    internal var cacheHits: Long = 0L
        private set
    internal var cacheMisses: Long = 0L
        private set

    @Synchronized
    fun writeCached(
        frame: PackedRawFrame,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
    ) {
        StaticImageSceneSource.validateExposure(exposure)
        val bytesPerSample = SceneFrameCodec.validateGeometryAndDestination(geometry, destination)
        val requiredBytes = geometry.width * geometry.height * bytesPerSample
        val gain = SceneFrameCodec.exposureGain(exposure)
        val gainBits = if (gain == 0.0) 0L else gain.toRawBits()
        val cached = cachedBytes
        if (
            cached != null &&
            cachedLen == requiredBytes &&
            cachedSeq == frame.sequence &&
            cachedWidth == geometry.width &&
            cachedHeight == geometry.height &&
            cachedBitDepth == geometry.bitDepth &&
            cachedBayer == geometry.bayer &&
            cachedGainBits == gainBits
        ) {
            destination.put(cached, 0, requiredBytes)
            cacheHits++
            return
        }
        val target = if (cached != null && cached.size >= requiredBytes) cached else ByteArray(requiredBytes)
        SceneFrameCodec.writePackedRawToBayerArray(frame, gain, geometry, bytesPerSample, target, 0)
        cachedBytes = target
        cachedLen = requiredBytes
        cachedSeq = frame.sequence
        cachedWidth = geometry.width
        cachedHeight = geometry.height
        cachedBitDepth = geometry.bitDepth
        cachedBayer = geometry.bayer
        cachedGainBits = gainBits
        destination.put(target, 0, requiredBytes)
        cacheMisses++
    }

    @Synchronized
    fun clear() {
        cachedBytes = null
        cachedLen = 0
        cachedSeq = -1L
        cachedWidth = -1
        cachedHeight = -1
        cachedBitDepth = -1
        cachedBayer = null
        cachedGainBits = 0L
    }
}

/**
 * Immutable raw packed sRGB frame stored in lock-free latest-frame slots for external streams.
 */
class PackedRawFrame(
    val width: Int,
    val height: Int,
    val format: RawPixelFormat,
    val pixels: ByteArray,
    val depthMeters: FloatArray? = null,
    val sequence: Long = 0L,
)

/**
 * Shared sRGB-to-linear LUT, packed-to-Bayer conversion, and fallback QR/test-card utilities
 * used across `StaticImageSceneSource`, `FfmpegPipeSceneSource`, and `StreamIngestSceneSource`.
 */
internal object SceneFrameCodec {
    val VALID_BIT_DEPTHS = setOf(8, 12, 14, 16)

    val SRGB_TO_LINEAR_DOUBLE = DoubleArray(256) { v ->
        val c = v / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    val SRGB_TO_LINEAR_FLOAT = FloatArray(256) { v -> SRGB_TO_LINEAR_DOUBLE[v].toFloat() }

    fun exposureGain(exposure: Exposure): Double {
        if (exposure.lens.transmission == 0.0) return 0.0
        val invAperture = 2.8 / exposure.lens.aperture
        val gain = (((exposure.durationNanos.toDouble() / 1000.0) * (exposure.iso.toDouble() / 100.0)) *
            (exposure.lens.transmission / 0.9)) * invAperture * invAperture
        return when {
            gain.isNaN() -> 0.0
            !gain.isFinite() -> Double.MAX_VALUE
            else -> gain
        }
    }

    fun validateGeometryAndDestination(geometry: SensorGeometry, destination: ByteBuffer): Int {
        require(geometry.width >= 2 && geometry.height >= 2 && geometry.width % 2 == 0 && geometry.height % 2 == 0) {
            "Sensor geometry dimensions must be even and >= 2: ${geometry.width}x${geometry.height}"
        }
        require(geometry.bitDepth in VALID_BIT_DEPTHS) {
            "Unsupported sensor bitDepth ${geometry.bitDepth}; expected 8, 12, 14, or 16"
        }
        require(geometry.pitchMicrometres.isFinite() && geometry.pitchMicrometres > 0.0) {
            "Sensor pixel pitch must be positive finite"
        }
        val bytesPerSample = if (geometry.bitDepth == 8) 1 else 2
        val requiredBytes = try {
            Math.multiplyExact(Math.multiplyExact(geometry.width, geometry.height), bytesPerSample)
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException(
                "Sensor geometry buffer size overflows Int: ${geometry.width}x${geometry.height}@${geometry.bitDepth}bpp",
                overflow,
            )
        }
        require(destination.remaining() >= requiredBytes) {
            "Destination buffer too small for ${geometry.width}x${geometry.height}@${geometry.bitDepth}bpp: " +
                "need $requiredBytes, remaining ${destination.remaining()}"
        }
        return bytesPerSample
    }

    fun bayerChannel(bayer: BayerPattern, xOdd: Int, yOdd: Int): Int = when (bayer) {
        BayerPattern.RGGB -> if (yOdd == 0) (if (xOdd == 0) 0 else 1) else (if (xOdd == 0) 1 else 2)
        BayerPattern.GRBG -> if (yOdd == 0) (if (xOdd == 0) 1 else 0) else (if (xOdd == 0) 2 else 1)
        BayerPattern.GBRG -> if (yOdd == 0) (if (xOdd == 0) 1 else 2) else (if (xOdd == 0) 0 else 1)
        BayerPattern.BGGR -> if (yOdd == 0) (if (xOdd == 0) 2 else 1) else (if (xOdd == 0) 1 else 0)
    }

    /**
     * Converts a [PackedRawFrame] into calibrated packed Bayer samples written directly into
     * [destination], fitting aspect ratio cleanly with zero per-pixel allocations.
     */
    fun writePackedRawToBayer(
        frame: PackedRawFrame,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
    ) {
        StaticImageSceneSource.validateExposure(exposure)
        val bytesPerSample = validateGeometryAndDestination(geometry, destination)
        val requiredBytes = geometry.width * geometry.height * bytesPerSample
        val gain = exposureGain(exposure)
        if (destination.hasArray() && !destination.isReadOnly) {
            val outOffset = destination.arrayOffset() + destination.position()
            writePackedRawToBayerArray(frame, gain, geometry, bytesPerSample, destination.array(), outOffset)
            destination.position(destination.position() + requiredBytes)
        } else {
            val tmp = ByteArray(requiredBytes)
            writePackedRawToBayerArray(frame, gain, geometry, bytesPerSample, tmp, 0)
            destination.put(tmp, 0, requiredBytes)
        }
    }

    internal fun writePackedRawToBayerArray(
        frame: PackedRawFrame,
        gain: Double,
        geometry: SensorGeometry,
        bytesPerSample: Int,
        out: ByteArray,
        outOffset: Int,
    ) {
        val maxSample = (1 shl geometry.bitDepth) - 1

        val srcW = frame.width
        val srcH = frame.height
        val dstW = geometry.width
        val dstH = geometry.height
        val bpp = frame.format.bytesPerPixel
        val isBgra = frame.format == RawPixelFormat.BGRA32
        val pixels = frame.pixels

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
        val lut = SRGB_TO_LINEAR_DOUBLE

        var outPos = outOffset
        for (y in 0 until dstH) {
            val inY = y in offY until endY
            val srcRowBytes = if (inY) srcYMap[y - offY] * srcW * bpp else 0
            val yOdd = y and 1
            val chEven = bayerChannel(bayer, 0, yOdd)
            val chOdd = bayerChannel(bayer, 1, yOdd)
            val offEven = if (!isBgra) chEven else (2 - chEven)
            val offOdd = if (!isBgra) chOdd else (2 - chOdd)
            for (x in 0 until dstW) {
                val sample = if (inY && x in offX until endX) {
                    val base = srcRowBytes + srcXMap[x - offX] * bpp
                    val byteOff = if ((x and 1) == 0) offEven else offOdd
                    val v8 = pixels[base + byteOff].toInt() and 0xFF
                    val lin = (lut[v8] * gain).coerceIn(0.0, 1.0)
                    (lin * maxSample).roundToInt()
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

    /**
     * Converts a planar linear-radiance [LinearRadianceFrame] directly into packed Bayer samples
     * in [destination] (used when optical lens filter is bypassed but spatial optical filters are active).
     */
    fun writeLinearRadianceToBayer(
        frame: LinearRadianceFrame,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
    ) {
        StaticImageSceneSource.validateExposure(exposure)
        val bytesPerSample = validateGeometryAndDestination(geometry, destination)
        val requiredBytes = geometry.width * geometry.height * bytesPerSample
        val gain = exposureGain(exposure)
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

        fun writeInto(out: ByteArray, startOffset: Int) {
            var outPos = startOffset
            for (y in 0 until dstH) {
                val inY = y in offY until endY
                val srcRow = if (inY) srcYMap[y - offY] * srcW else 0
                val yOdd = y and 1
                for (x in 0 until dstW) {
                    val sample = if (inY && x in offX until endX) {
                        val idx = srcRow + srcXMap[x - offX]
                        val lin = when (bayerChannel(bayer, x and 1, yOdd)) {
                            0 -> frame.red[idx]
                            1 -> frame.green[idx]
                            else -> frame.blue[idx]
                        }
                        ((lin.toDouble() * gain).coerceIn(0.0, 1.0) * maxSample).roundToInt()
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

        if (destination.hasArray() && !destination.isReadOnly) {
            val outOffset = destination.arrayOffset() + destination.position()
            writeInto(destination.array(), outOffset)
            destination.position(destination.position() + requiredBytes)
        } else {
            val tmp = ByteArray(requiredBytes)
            writeInto(tmp, 0)
            destination.put(tmp, 0, requiredBytes)
        }
    }

    /**
     * Converts a [PackedRawFrame] into planar linear-radiance [LinearRgbFrame] for optical filtering.
     */
    fun toLinearRgbFrame(frame: PackedRawFrame): LinearRgbFrame {
        val count = Math.multiplyExact(frame.width, frame.height)
        val r = FloatArray(count)
        val g = FloatArray(count)
        val b = FloatArray(count)
        val px = frame.pixels
        val bpp = frame.format.bytesPerPixel
        val isBgra = frame.format == RawPixelFormat.BGRA32
        var src = 0
        for (i in 0 until count) {
            if (isBgra) {
                b[i] = SRGB_TO_LINEAR_FLOAT[px[src].toInt() and 0xFF]
                g[i] = SRGB_TO_LINEAR_FLOAT[px[src + 1].toInt() and 0xFF]
                r[i] = SRGB_TO_LINEAR_FLOAT[px[src + 2].toInt() and 0xFF]
            } else {
                r[i] = SRGB_TO_LINEAR_FLOAT[px[src].toInt() and 0xFF]
                g[i] = SRGB_TO_LINEAR_FLOAT[px[src + 1].toInt() and 0xFF]
                b[i] = SRGB_TO_LINEAR_FLOAT[px[src + 2].toInt() and 0xFF]
            }
            src += bpp
        }
        return LinearRgbFrame(frame.width, frame.height, r, g, b, frame.depthMeters)
    }

    /**
     * Loads a local fallback QR image (`test-qr.jpg` or `test-qr.png`) from the `cmos` directory
     * if present, or synthesizes a deterministic built-in QR-style finder/test card so external
     * stream backends degrade gracefully without ever crashing or blocking.
     */
    fun createFallbackSource(resolveDataPath: (String) -> Path): FallbackSceneSource {
        for ((name, fmt) in listOf(
            "test-qr.jpg" to StaticImageSceneSource.Format.JPEG,
            "test-qr.png" to StaticImageSceneSource.Format.PNG,
        )) {
            val candidate = runCatching { resolveDataPath(name) }.getOrNull()
            if (candidate != null && Files.isRegularFile(candidate)) {
                val loaded = runCatching {
                    StaticImageSceneSource.validateAndLoad(candidate, name, fmt, loop = true)
                }.getOrNull()
                if (loaded != null) {
                    return FallbackSceneSource(staticDelegate = loaded, synthesized = null)
                }
            }
        }
        return FallbackSceneSource(staticDelegate = null, synthesized = buildProceduralQrTestCard())
    }

    private fun buildProceduralQrTestCard(size: Int = 128): PackedRawFrame {
        val pixels = ByteArray(size * size * 3)
        // White quiet background
        pixels.fill(0xF0.toByte())
        fun fillRect(x0: Int, y0: Int, w: Int, h: Int, r: Int, g: Int, b: Int) {
            for (y in y0.coerceAtLeast(0) until (y0 + h).coerceAtMost(size)) {
                var idx = (y * size + x0.coerceAtLeast(0)) * 3
                for (x in x0.coerceAtLeast(0) until (x0 + w).coerceAtMost(size)) {
                    pixels[idx++] = r.toByte()
                    pixels[idx++] = g.toByte()
                    pixels[idx++] = b.toByte()
                }
            }
        }
        fun drawFinder(fx: Int, fy: Int) {
            fillRect(fx, fy, 28, 28, 16, 16, 16)
            fillRect(fx + 4, fy + 4, 20, 20, 240, 240, 240)
            fillRect(fx + 8, fy + 8, 12, 12, 16, 16, 16)
        }
        // Three QR finder squares + color reference bars in center
        drawFinder(12, 12)
        drawFinder(size - 40, 12)
        drawFinder(12, size - 40)
        fillRect(48, 48, 12, 32, 220, 40, 40)
        fillRect(60, 48, 12, 32, 40, 200, 60)
        fillRect(72, 48, 12, 32, 40, 80, 220)
        return PackedRawFrame(size, size, RawPixelFormat.RGB24, pixels)
    }
}

/**
 * Fallback [SceneSource] + [LinearRgbFrameSource] backed either by a bundled `test-qr.jpg`/`.png`
 * or a deterministic procedural QR-style test card.
 */
internal class FallbackSceneSource(
    private val staticDelegate: StaticImageSceneSource?,
    private val synthesized: PackedRawFrame?,
) : SceneSource2D, LinearRgbFrameSource {
    private var cachedLinear: LinearRgbFrame? = null
    private var cachedRadiance: LinearRadianceFrame? = null

    val isFileBacked: Boolean get() = staticDelegate != null

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        if (staticDelegate != null) {
            staticDelegate.radiance(exposure, geometry, destination)
        } else {
            SceneFrameCodec.writePackedRawToBayer(checkNotNull(synthesized), exposure, geometry, destination)
        }
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame {
        if (staticDelegate != null) {
            return staticDelegate.latestLinearRgbFrame()
        }
        cachedLinear?.let { return it }
        val built = SceneFrameCodec.toLinearRgbFrame(checkNotNull(synthesized))
        cachedLinear = built
        return built
    }

    override fun samplePlanarRadiance(): LinearRadianceFrame {
        if (staticDelegate != null) {
            return staticDelegate.samplePlanarRadiance()
        }
        cachedRadiance?.let { return it }
        return latestLinearRgbFrame().toRadianceFrame(
            dimension = SceneDimension.DIM_2D,
            virtualSubjectDistanceM = virtualSubjectDistanceM,
        ).also { cachedRadiance = it }
    }

    override fun close() {
        staticDelegate?.close()
        cachedLinear = null
        cachedRadiance = null
    }
}
