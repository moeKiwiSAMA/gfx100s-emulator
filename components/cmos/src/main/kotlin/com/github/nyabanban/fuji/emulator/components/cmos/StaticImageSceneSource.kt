package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource
import com.github.nyabanban.fuji.emulator.api.SceneSource2D
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Validated static-image-backed [SceneSource] (JPEG / PNG via standard `javax.imageio.ImageIO`)
 * producing calibrated linear Bayer radiance samples.
 *
 * Caches the packed Bayer output buffer for the active `(geometry, exposureGain)` tuple so
 * continuous live-view looping (`loop = true`) copies a pre-converted frame in O(1) bulk copy
 * with zero per-frame allocations or memory growth.
 *
 * Host media decoding is TEST/HYPOTHESIS modeling only; it does not assert physical
 * sensor silicon identity or electrical readout timing.
 */
internal class StaticImageSceneSource private constructor(
    private val sourceRefLabel: String,
    override val sourceWidth: Int,
    override val sourceHeight: Int,
    private val srgbPixels: IntArray,
    private val loop: Boolean,
    override val virtualSubjectDistanceM: Double? = null,
) : SceneSource2D, LinearRgbFrameSource, SceneSourceTelemetry {
    enum class Format(val imageIoName: String, val label: String) {
        JPEG("JPEG", "JPEG"),
        PNG("PNG", "PNG"),
    }

    override val activeSourceRef: String get() = sourceRefLabel
    override val hasDepthBuffer: Boolean get() = false
    override val lastSourceError: String? get() = null
    override val framesReceived: Long get() = 1L
    override val framesDropped: Long get() = 0L

    var exhausted: Boolean = false
        private set

    private var closed: Boolean = false

    // Single-slot packed Bayer cache; replaced in place on geometry/gain change and cleared on close().
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedBitDepth: Int = -1
    private var cachedBayer: BayerPattern? = null
    private var cachedGainBits: Long = 0L
    private var cachedBytes: ByteArray? = null

    // Lazy planar linear-RGB & radiance caches populated only when an active OpticalSceneFilter requests them.
    private var cachedLinearFrame: LinearRgbFrame? = null
    private var cachedRadianceFrame: LinearRadianceFrame? = null

    internal var cacheHits: Long = 0L
        private set
    internal var cacheMisses: Long = 0L
        private set

    internal fun hasCachedBuffer(): Boolean = cachedBytes != null

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed) { "Static image scene source is closed" }
        check(!exhausted) { "Static image scene source exhausted" }
        require(geometry.width >= 2 && geometry.height >= 2 && geometry.width % 2 == 0 && geometry.height % 2 == 0) {
            "Sensor geometry dimensions must be even and >= 2: ${geometry.width}x${geometry.height}"
        }
        require(geometry.bitDepth in VALID_BIT_DEPTHS) {
            "Unsupported sensor bitDepth ${geometry.bitDepth}; expected 8, 12, or 16"
        }
        require(geometry.pitchMicrometres.isFinite() && geometry.pitchMicrometres > 0.0) {
            "Sensor pixel pitch must be positive finite"
        }
        validateExposure(exposure)

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

        val gain = SceneFrameCodec.exposureGain(exposure)
        val gainBits = if (gain == 0.0) 0L else gain.toRawBits()
        val cached = cachedBytes
        val frameBytes = if (
            cached != null &&
            cachedWidth == geometry.width &&
            cachedHeight == geometry.height &&
            cachedBitDepth == geometry.bitDepth &&
            cachedBayer == geometry.bayer &&
            cachedGainBits == gainBits
        ) {
            cacheHits++
            cached
        } else {
            cacheMisses++
            val target = if (cached != null && cached.size == requiredBytes) cached else ByteArray(requiredBytes)
            renderBayerBuffer(geometry, gain, bytesPerSample, target)
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

    private fun renderBayerBuffer(
        geometry: SensorGeometry,
        gain: Double,
        bytesPerSample: Int,
        out: ByteArray,
    ) {
        val maxSample = (1 shl geometry.bitDepth) - 1
        val whiteSample = (1.0 * gain).coerceIn(0.0, 1.0).let { (it * maxSample).roundToInt() }

        val dstW = geometry.width
        val dstH = geometry.height
        val scale = min(dstW.toDouble() / sourceWidth.toDouble(), dstH.toDouble() / sourceHeight.toDouble())
        val fitW = (sourceWidth * scale).roundToInt().coerceIn(1, dstW)
        val fitH = (sourceHeight * scale).roundToInt().coerceIn(1, dstH)
        val offX = (dstW - fitW) / 2
        val offY = (dstH - fitH) / 2
        val endX = offX + fitW
        val endY = offY + fitH

        val srcXMap = IntArray(fitW) { dx -> ((dx.toLong() * sourceWidth) / fitW).toInt().coerceIn(0, sourceWidth - 1) }
        val srcYMap = IntArray(fitH) { dy -> ((dy.toLong() * sourceHeight) / fitH).toInt().coerceIn(0, sourceHeight - 1) }

        val bayer = geometry.bayer
        val lut = SceneFrameCodec.SRGB_TO_LINEAR_DOUBLE
        var outPos = 0
        for (y in 0 until dstH) {
            val inY = y in offY until endY
            val srcRowOffset = if (inY) srcYMap[y - offY] * sourceWidth else 0
            val yOdd = y and 1
            for (x in 0 until dstW) {
                val sample = if (inY && x in offX until endX) {
                    val rgb = srgbPixels[srcRowOffset + srcXMap[x - offX]]
                    val channel8 = when (SceneFrameCodec.bayerChannel(bayer, x and 1, yOdd)) {
                        0 -> (rgb ushr 16) and 0xff
                        1 -> (rgb ushr 8) and 0xff
                        else -> rgb and 0xff
                    }
                    val lin = (lut[channel8] * gain).coerceIn(0.0, 1.0)
                    (lin * maxSample).roundToInt()
                } else {
                    whiteSample
                }
                if (bytesPerSample == 1) {
                    out[outPos++] = sample.toByte()
                } else {
                    out[outPos++] = (sample and 0xff).toByte()
                    out[outPos++] = ((sample ushr 8) and 0xff).toByte()
                }
            }
        }
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame {
        check(!closed) { "Static image scene source is closed" }
        cachedLinearFrame?.let { return it }
        val count = srgbPixels.size
        val red = FloatArray(count)
        val green = FloatArray(count)
        val blue = FloatArray(count)
        val lut = SceneFrameCodec.SRGB_TO_LINEAR_FLOAT
        for (i in 0 until count) {
            val rgb = srgbPixels[i]
            red[i] = lut[(rgb ushr 16) and 0xFF]
            green[i] = lut[(rgb ushr 8) and 0xFF]
            blue[i] = lut[rgb and 0xFF]
        }
        val frame = LinearRgbFrame(sourceWidth, sourceHeight, red, green, blue)
        cachedLinearFrame = frame
        return frame
    }

    override fun samplePlanarRadiance(): LinearRadianceFrame {
        check(!closed) { "Static image scene source is closed" }
        cachedRadianceFrame?.let { return it }
        return latestLinearRgbFrame().toRadianceFrame(
            dimension = SceneDimension.DIM_2D,
            virtualSubjectDistanceM = virtualSubjectDistanceM,
            sequence = 1L,
        ).also { cachedRadianceFrame = it }
    }

    override fun close() {
        closed = true
        cachedBytes = null
        cachedLinearFrame = null
        cachedRadianceFrame = null
        cachedWidth = -1
        cachedHeight = -1
        cachedBitDepth = -1
        cachedBayer = null
        cachedGainBits = 0L
    }

    companion object {
        private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_DIMENSION = 16384
        private const val MAX_PIXELS = 64_000_000L
        private val VALID_BIT_DEPTHS = setOf(8, 12, 16)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        fun validateExposure(exposure: Exposure) {
            require(exposure.durationNanos > 0L) { "Exposure durationNanos must be positive" }
            require(exposure.iso > 0) { "Exposure ISO must be positive" }
            require(exposure.lens.aperture.isFinite() && exposure.lens.aperture > 0.0) {
                "Exposure lens aperture must be positive finite"
            }
            require(exposure.lens.transmission.isFinite() && exposure.lens.transmission in 0.0..1.0) {
                "Exposure lens transmission must be in 0.0..1.0"
            }
        }

        private fun compositeAlphaOverWhite(argbPixels: IntArray) {
            for (i in argbPixels.indices) {
                val argb = argbPixels[i]
                val a = (argb ushr 24) and 0xff
                if (a == 255) continue
                val invA = 255 - a
                val r = (((argb ushr 16) and 0xff) * a + 255 * invA + 127) / 255
                val g = (((argb ushr 8) and 0xff) * a + 255 * invA + 127) / 255
                val b = ((argb and 0xff) * a + 255 * invA + 127) / 255
                argbPixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        fun validateAndLoad(
            path: Path,
            relativePath: String,
            format: Format,
            loop: Boolean,
        ): StaticImageSceneSource {
            require(Files.isRegularFile(path)) { "CMOS source missing: $relativePath" }
            val size = Files.size(path)
            val minBytes = when (format) {
                Format.JPEG -> 4L
                Format.PNG -> PNG_SIGNATURE.size.toLong()
            }
            require(size in minBytes..MAX_FILE_BYTES) {
                "Invalid ${format.label} CMOS source size ($size bytes): $relativePath"
            }
            val bytes = Files.readAllBytes(path)
            when (format) {
                Format.JPEG -> {
                    require(bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                        "Invalid JPEG SOI header in CMOS source: $relativePath"
                    }
                    require(bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()) {
                        "Invalid or truncated JPEG EOI trailer in CMOS source: $relativePath"
                    }
                }
                Format.PNG -> {
                    require(bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
                        "Invalid PNG signature in CMOS source: $relativePath"
                    }
                }
            }

            val decoded: BufferedImage = try {
                val readers = ImageIO.getImageReadersByFormatName(format.imageIoName)
                require(readers.hasNext()) { "No ${format.label} ImageReader available" }
                val reader = readers.next()
                try {
                    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                        requireNotNull(stream) { "Failed to open ImageInputStream for $relativePath" }
                        reader.setInput(stream, true, true)
                        val headerWidth = reader.getWidth(0)
                        val headerHeight = reader.getHeight(0)
                        require(
                            headerWidth in 1..MAX_DIMENSION &&
                                headerHeight in 1..MAX_DIMENSION &&
                                headerWidth.toLong() * headerHeight.toLong() <= MAX_PIXELS
                        ) {
                            "${format.label} dimensions out of bounds (${headerWidth}x${headerHeight}): $relativePath"
                        }
                        reader.read(0) ?: throw IllegalArgumentException("Null decoded ${format.label} image: $relativePath")
                    }
                } finally {
                    reader.dispose()
                }
            } catch (err: IllegalArgumentException) {
                throw err
            } catch (err: Exception) {
                throw IllegalArgumentException("Corrupted or unreadable ${format.label} CMOS source: $relativePath", err)
            }

            val w = decoded.width
            val h = decoded.height
            require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION && w.toLong() * h.toLong() <= MAX_PIXELS) {
                "Decoded ${format.label} dimensions out of bounds (${w}x${h}): $relativePath"
            }
            val pixels = decoded.getRGB(0, 0, w, h, null, 0, w)
            if (format == Format.PNG) {
                compositeAlphaOverWhite(pixels)
            }
            return StaticImageSceneSource(relativePath, w, h, pixels, loop)
        }
    }
}
