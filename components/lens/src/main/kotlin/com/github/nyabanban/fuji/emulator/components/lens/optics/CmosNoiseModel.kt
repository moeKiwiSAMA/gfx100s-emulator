package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Configuration parameters for the deterministic CMOS physical noise and readout model.
 *
 * **Evidence classification (`HYPOTHESIS` / `SIM`)**: Sensor full-well capacity, read-noise floor,
 * black-level pedestal, and exposure normalization are synthetic physical simulation parameters,
 * not factory-calibrated FUJIFILM GFX100S silicon measurements.
 *
 * @property seed Deterministic 64-bit PRNG seed. Identical inputs and seed produce bit-exact output.
 * @property enableNoise Enables photon shot noise and ISO-scaled Gaussian read noise when `true`.
 * @property fullWellElectrons Photodiode saturation capacity in electrons (`> 0`).
 * @property baseIso Native base ISO sensitivity (`> 0`).
 * @property readNoiseBaseElectrons Input-referred Gaussian read noise standard deviation at [baseIso] (`>= 0`).
 * @property readNoiseIsoSlope Additional fractional read-noise growth per unit ISO gain above `1.0` (`>= 0`).
 * @property blackLevel Digital ADC black-level pedestal in ADU (`>= 0`, must be `< maxCode` for sensor bitDepth).
 * @property nominalExposureNanos Reference shutter duration in nanoseconds at which linear radiance `1.0`
 * fills [fullWellElectrons] under [referenceFNumber] and unity transmission (`> 0`).
 * @property referenceFNumber Reference aperture f-number paired with [nominalExposureNanos] (`> 0`).
 */
data class CmosNoiseConfig(
    val seed: Long = 0L,
    val enableNoise: Boolean = true,
    val fullWellElectrons: Double = 65_000.0,
    val baseIso: Int = 100,
    val readNoiseBaseElectrons: Double = 3.5,
    val readNoiseIsoSlope: Double = 0.015,
    val blackLevel: Int = 256,
    val nominalExposureNanos: Long = 10_000_000L,
    val referenceFNumber: Double = 2.8,
) {
    init {
        require(fullWellElectrons.isFinite() && fullWellElectrons > 0.0) {
            "fullWellElectrons must be positive finite, got $fullWellElectrons"
        }
        require(baseIso > 0) { "baseIso must be positive, got $baseIso" }
        require(readNoiseBaseElectrons.isFinite() && readNoiseBaseElectrons >= 0.0) {
            "readNoiseBaseElectrons must be non-negative finite, got $readNoiseBaseElectrons"
        }
        require(readNoiseIsoSlope.isFinite() && readNoiseIsoSlope >= 0.0) {
            "readNoiseIsoSlope must be non-negative finite, got $readNoiseIsoSlope"
        }
        require(blackLevel >= 0) { "blackLevel must be non-negative, got $blackLevel" }
        require(nominalExposureNanos > 0L) {
            "nominalExposureNanos must be positive, got $nominalExposureNanos"
        }
        require(referenceFNumber.isFinite() && referenceFNumber > 0.0) {
            "referenceFNumber must be positive finite, got $referenceFNumber"
        }
    }

    companion object {
        /** Deterministic noiseless configuration with zero black pedestal for geometry verification. */
        val NOISELESS = CmosNoiseConfig(
            seed = 0L,
            enableNoise = false,
            blackLevel = 0,
        )
    }
}

/**
 * Deterministic CPU CMOS sensor CFA mosaicking and physical noise reference model (`HYPOTHESIS` / `SIM`).
 *
 * Uses a stateless counter-based SplitMix64 mixer keyed by `(seed, frameIndex, pixelIndex)` so
 * CPU sequential execution, parallel tiles, and future Vulkan Compute shaders can generate
 * bit-exact identical noise realizations without per-pixel heap allocations.
 *
 * Signal chain per sensel:
 * 1. CFA channel selection via [BayerPattern] (`RGGB`, `GRBG`, `GBRG`, `BGGR`).
 * 2. Linear radiance to expected photoelectrons scaled by shutter time, aperture, and lens transmittance.
 * 3. Photon shot noise (exact Knuth Poisson for `< 20` electrons; variance-matched Gaussian `N(mu, sqrt(mu))`
 *    above `20` electrons) clamped to `[0, fullWellElectrons]`.
 * 4. ISO analog/digital gain amplification + zero-mean ISO-scaled Gaussian read noise centered at [CmosNoiseConfig.blackLevel].
 * 5. Quantization to `0..((1 shl bitDepth) - 1)` and packed little-endian output (`2` bytes/pixel for
 *    9..16-bit including 14-bit and 16-bit Bayer; `1` byte/pixel for 8-bit).
 */
object CmosNoiseModel {
    const val CHANNEL_RED = 0
    const val CHANNEL_GREEN = 1
    const val CHANNEL_BLUE = 2

    private const val TWO_PI = 2.0 * Math.PI
    private const val POISSON_EXACT_THRESHOLD = 20.0
    private const val INV_TWO_POW_53 = 1.0 / 9007199254740992.0
    private const val GOLDEN_GAMMA = -7046029254386353131L // 0x9E3779B97F4A7C15
    private const val MIX_MUL_1 = -4658895280553007687L // 0xBF58476D1CE4E5B9
    private const val MIX_MUL_2 = -7723592293110705685L // 0x94D049BB133111EB

    /**
     * Returns the CFA color channel index ([CHANNEL_RED], [CHANNEL_GREEN], or [CHANNEL_BLUE])
     * at pixel coordinate `([x], [y])` for [pattern].
     */
    fun bayerChannel(pattern: BayerPattern, x: Int, y: Int): Int {
        val xOdd = x and 1
        val yOdd = y and 1
        return when (pattern) {
            BayerPattern.RGGB -> if (yOdd == 0) {
                if (xOdd == 0) CHANNEL_RED else CHANNEL_GREEN
            } else {
                if (xOdd == 0) CHANNEL_GREEN else CHANNEL_BLUE
            }
            BayerPattern.GRBG -> if (yOdd == 0) {
                if (xOdd == 0) CHANNEL_GREEN else CHANNEL_RED
            } else {
                if (xOdd == 0) CHANNEL_BLUE else CHANNEL_GREEN
            }
            BayerPattern.GBRG -> if (yOdd == 0) {
                if (xOdd == 0) CHANNEL_GREEN else CHANNEL_BLUE
            } else {
                if (xOdd == 0) CHANNEL_RED else CHANNEL_GREEN
            }
            BayerPattern.BGGR -> if (yOdd == 0) {
                if (xOdd == 0) CHANNEL_BLUE else CHANNEL_GREEN
            } else {
                if (xOdd == 0) CHANNEL_GREEN else CHANNEL_RED
            }
        }
    }

    /**
     * Computes optical photoelectron exposure scale relative to [CmosNoiseConfig.nominalExposureNanos]
     * and [CmosNoiseConfig.referenceFNumber]. Does not include ISO electronic gain.
     */
    fun opticalExposureScale(
        exposure: Exposure,
        config: CmosNoiseConfig,
        fNumber: Double = exposure.lens.aperture,
        transmission: Double = exposure.lens.transmission,
    ): Double {
        if (transmission <= 0.0) return 0.0
        val invAperture = config.referenceFNumber / fNumber
        val timeRatio = exposure.durationNanos.toDouble() / config.nominalExposureNanos.toDouble()
        val scale = timeRatio * transmission * invAperture * invAperture
        return when {
            scale.isNaN() || scale <= 0.0 -> 0.0
            !scale.isFinite() -> Double.MAX_VALUE
            else -> scale
        }
    }

    /**
     * Evaluates a single CMOS sensel from linear radiance [linearRadiance] into an integer ADC code
     * in `0..maxCode`, performing zero heap allocations.
     */
    fun samplePixel(
        linearRadiance: Double,
        pixelIndex: Int,
        frameIndex: Long,
        opticalExposureScale: Double,
        iso: Int,
        maxCode: Int,
        config: CmosNoiseConfig,
    ): Int {
        val clampedRadiance = if (linearRadiance.isFinite() && linearRadiance > 0.0) linearRadiance else 0.0
        val expectedElectrons = clampedRadiance * opticalExposureScale * config.fullWellElectrons
        val isoGain = iso.toDouble() / config.baseIso.toDouble()
        val usableRange = (maxCode - config.blackLevel).toDouble()
        val electronToAdu = (usableRange / config.fullWellElectrons) * isoGain

        if (!config.enableNoise) {
            val clampedElectrons = min(expectedElectrons, config.fullWellElectrons)
            val adu = config.blackLevel.toDouble() + clampedElectrons * electronToAdu
            return adu.roundToInt().coerceIn(0, maxCode)
        }

        val baseKey = splitMix64(
            config.seed xor
                (frameIndex * GOLDEN_GAMMA) xor
                (pixelIndex.toLong() * MIX_MUL_1),
        )
        val u1 = ((splitMix64(baseKey) ushr 11).toDouble() + 0.5) * INV_TWO_POW_53
        val u2 = (splitMix64(baseKey + 1L) ushr 11).toDouble() * INV_TWO_POW_53
        val radius = sqrt(-2.0 * ln(u1))
        val theta = TWO_PI * u2
        val zShot = radius * cos(theta)
        val zRead = radius * sin(theta)

        val rawElectrons = when {
            expectedElectrons <= 0.0 -> 0.0
            expectedElectrons < POISSON_EXACT_THRESHOLD -> sampleExactPoisson(expectedElectrons, baseKey + 2L)
            else -> max(0.0, expectedElectrons + zShot * sqrt(expectedElectrons))
        }
        val clampedElectrons = min(rawElectrons, config.fullWellElectrons)

        val readNoiseElectrons = config.readNoiseBaseElectrons *
            (1.0 + config.readNoiseIsoSlope * max(0.0, isoGain - 1.0))
        val readNoiseAdu = zRead * readNoiseElectrons * electronToAdu

        val adu = config.blackLevel.toDouble() + clampedElectrons * electronToAdu + readNoiseAdu
        return adu.roundToInt().coerceIn(0, maxCode)
    }

    /**
     * Mosaics planar linear RGB radiance arrays ([red], [green], [blue]) into packed Bayer sensor bytes
     * in [destination] according to [geometry], [exposure], and [config], returning bytes written.
     */
    fun mosaicAndReadout(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        geometry: SensorGeometry,
        exposure: Exposure,
        config: CmosNoiseConfig,
        destination: ByteBuffer,
        fNumber: Double = exposure.lens.aperture,
        transmission: Double = exposure.lens.transmission,
        frameIndex: Long = 0L,
        parallelThreads: Int = 1,
    ): Int {
        validateGeometryAndExposure(geometry, exposure, config)
        val width = geometry.width
        val height = geometry.height
        val pixelCount = Math.multiplyExact(width, height)
        require(red.size >= pixelCount && green.size >= pixelCount && blue.size >= pixelCount) {
            "Planar RGB buffers must contain at least $pixelCount elements"
        }

        val bytesPerSample = if (geometry.bitDepth == 8) 1 else 2
        val requiredBytes = Math.multiplyExact(pixelCount, bytesPerSample)
        require(destination.remaining() >= requiredBytes) {
            "Destination buffer too small for ${width}x${height}@${geometry.bitDepth}bpp: " +
                "need $requiredBytes, remaining ${destination.remaining()}"
        }

        val maxCode = (1 shl geometry.bitDepth) - 1
        val optScale = opticalExposureScale(exposure, config, fNumber, transmission)
        val bayer = geometry.bayer
        val iso = exposure.iso

        if (!config.enableNoise) {
            return mosaicAndReadoutNoiseless(
                red = red,
                green = green,
                blue = blue,
                width = width,
                height = height,
                bayer = bayer,
                bytesPerSample = bytesPerSample,
                requiredBytes = requiredBytes,
                maxCode = maxCode,
                optScale = optScale,
                iso = iso,
                config = config,
                destination = destination,
                parallelThreads = parallelThreads,
            )
        }

        val fullWell = config.fullWellElectrons
        val isoGain = iso.toDouble() / config.baseIso.toDouble()
        val usableRange = (maxCode - config.blackLevel).toDouble()
        val electronToAdu = (usableRange / fullWell) * isoGain
        val readNoiseAduScale = config.readNoiseBaseElectrons *
            (1.0 + config.readNoiseIsoSlope * max(0.0, isoGain - 1.0)) * electronToAdu
        val blackLevelD = config.blackLevel.toDouble()
        val radToElectrons = optScale * fullWell
        val seedFrameKey = config.seed xor (frameIndex * GOLDEN_GAMMA)

        val outArr: ByteArray
        val outBase: Int
        val directCopy: Boolean
        if (destination.hasArray() && !destination.isReadOnly) {
            outArr = destination.array()
            outBase = destination.arrayOffset() + destination.position()
            directCopy = true
        } else {
            outArr = ByteArray(requiredBytes)
            outBase = 0
            directCopy = false
        }

        val useParallel = parallelThreads > 1 && pixelCount >= PARALLEL_PIXEL_THRESHOLD
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = if (useParallel) parallelThreads else 1,
            minChunkSize = 32,
            align = 2,
        ) { yStart, yEnd ->
            var idx = yStart * width
            var outPos = outBase + idx * bytesPerSample
            for (y in yStart until yEnd) {
                val chEven = bayerChannel(bayer, 0, y)
                val chOdd = bayerChannel(bayer, 1, y)
                val planeEven = if (chEven == CHANNEL_RED) red else if (chEven == CHANNEL_GREEN) green else blue
                val planeOdd = if (chOdd == CHANNEL_RED) red else if (chOdd == CHANNEL_GREEN) green else blue
                var x = 0
                while (x < width) {
                    for (lane in 0 until 2) {
                        val lin = (if (lane == 0) planeEven[idx] else planeOdd[idx]).toDouble()
                        val clampedRadiance = if (lin.isFinite() && lin > 0.0) lin else 0.0
                        val expectedElectrons = clampedRadiance * radToElectrons
                        val baseKey = splitMix64(seedFrameKey xor (idx.toLong() * MIX_MUL_1))
                        val u1 = ((splitMix64(baseKey) ushr 11).toDouble() + 0.5) * INV_TWO_POW_53
                        val u2 = (splitMix64(baseKey + 1L) ushr 11).toDouble() * INV_TWO_POW_53
                        val radius = sqrt(-2.0 * ln(u1))
                        val theta = TWO_PI * u2
                        val zShot = radius * cos(theta)
                        val zRead = radius * sin(theta)
                        val rawElectrons = when {
                            expectedElectrons <= 0.0 -> 0.0
                            expectedElectrons < POISSON_EXACT_THRESHOLD -> sampleExactPoisson(expectedElectrons, baseKey + 2L)
                            else -> max(0.0, expectedElectrons + zShot * sqrt(expectedElectrons))
                        }
                        val clampedElectrons = min(rawElectrons, fullWell)
                        val adu = blackLevelD + clampedElectrons * electronToAdu + zRead * readNoiseAduScale
                        val sample = adu.roundToInt().coerceIn(0, maxCode)
                        if (bytesPerSample == 1) {
                            outArr[outPos++] = sample.toByte()
                        } else {
                            outArr[outPos++] = (sample and 0xFF).toByte()
                            outArr[outPos++] = ((sample ushr 8) and 0xFF).toByte()
                        }
                        idx++
                    }
                    x += 2
                }
            }
        }
        if (directCopy) {
            destination.position(destination.position() + requiredBytes)
        } else {
            destination.put(outArr, 0, requiredBytes)
        }
        return requiredBytes
    }

    private const val PARALLEL_PIXEL_THRESHOLD = 32_768

    private fun mosaicAndReadoutNoiseless(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        bayer: BayerPattern,
        bytesPerSample: Int,
        requiredBytes: Int,
        maxCode: Int,
        optScale: Double,
        iso: Int,
        config: CmosNoiseConfig,
        destination: ByteBuffer,
        parallelThreads: Int,
    ): Int {
        val fullWell = config.fullWellElectrons
        val isoGain = iso.toDouble() / config.baseIso.toDouble()
        val usableRange = (maxCode - config.blackLevel).toDouble()
        val electronToAdu = (usableRange / fullWell) * isoGain
        val radToElectrons = optScale * fullWell
        val blackLevelD = config.blackLevel.toDouble()

        val outArr: ByteArray
        val outBase: Int
        val directCopy: Boolean
        if (destination.hasArray() && !destination.isReadOnly) {
            outArr = destination.array()
            outBase = destination.arrayOffset() + destination.position()
            directCopy = true
        } else {
            outArr = ByteArray(requiredBytes)
            outBase = 0
            directCopy = false
        }

        val useParallel = parallelThreads > 1 && width * height >= PARALLEL_PIXEL_THRESHOLD
        OpticalParallelExecutor.parallelStripes(
            totalWork = height,
            threads = if (useParallel) parallelThreads else 1,
            minChunkSize = 32,
            align = 2,
        ) { yStart, yEnd ->
            var idx = yStart * width
            var outPos = outBase + idx * bytesPerSample
            if (bytesPerSample == 2) {
                for (y in yStart until yEnd) {
                    val chEven = bayerChannel(bayer, 0, y)
                    val chOdd = bayerChannel(bayer, 1, y)
                    val planeEven = if (chEven == CHANNEL_RED) red else if (chEven == CHANNEL_GREEN) green else blue
                    val planeOdd = if (chOdd == CHANNEL_RED) red else if (chOdd == CHANNEL_GREEN) green else blue
                    var x = 0
                    while (x < width) {
                        val lin0 = planeEven[idx].toDouble()
                        val r0 = if (lin0.isFinite() && lin0 > 0.0) lin0 else 0.0
                        val e0 = min(r0 * radToElectrons, fullWell)
                        val s0 = (blackLevelD + e0 * electronToAdu).roundToInt().coerceIn(0, maxCode)
                        outArr[outPos] = (s0 and 0xFF).toByte()
                        outArr[outPos + 1] = ((s0 ushr 8) and 0xFF).toByte()

                        val lin1 = planeOdd[idx + 1].toDouble()
                        val r1 = if (lin1.isFinite() && lin1 > 0.0) lin1 else 0.0
                        val e1 = min(r1 * radToElectrons, fullWell)
                        val s1 = (blackLevelD + e1 * electronToAdu).roundToInt().coerceIn(0, maxCode)
                        outArr[outPos + 2] = (s1 and 0xFF).toByte()
                        outArr[outPos + 3] = ((s1 ushr 8) and 0xFF).toByte()

                        idx += 2
                        outPos += 4
                        x += 2
                    }
                }
            } else {
                for (y in yStart until yEnd) {
                    val chEven = bayerChannel(bayer, 0, y)
                    val chOdd = bayerChannel(bayer, 1, y)
                    val planeEven = if (chEven == CHANNEL_RED) red else if (chEven == CHANNEL_GREEN) green else blue
                    val planeOdd = if (chOdd == CHANNEL_RED) red else if (chOdd == CHANNEL_GREEN) green else blue
                    var x = 0
                    while (x < width) {
                        val lin0 = planeEven[idx].toDouble()
                        val r0 = if (lin0.isFinite() && lin0 > 0.0) lin0 else 0.0
                        val e0 = min(r0 * radToElectrons, fullWell)
                        outArr[outPos] = (blackLevelD + e0 * electronToAdu).roundToInt().coerceIn(0, maxCode).toByte()

                        val lin1 = planeOdd[idx + 1].toDouble()
                        val r1 = if (lin1.isFinite() && lin1 > 0.0) lin1 else 0.0
                        val e1 = min(r1 * radToElectrons, fullWell)
                        outArr[outPos + 1] = (blackLevelD + e1 * electronToAdu).roundToInt().coerceIn(0, maxCode).toByte()

                        idx += 2
                        outPos += 2
                        x += 2
                    }
                }
            }
        }

        if (directCopy) {
            destination.position(destination.position() + requiredBytes)
        } else {
            destination.put(outArr, 0, requiredBytes)
        }
        return requiredBytes
    }

    internal fun validateGeometryAndExposure(
        geometry: SensorGeometry,
        exposure: Exposure,
        config: CmosNoiseConfig,
    ) {
        require(geometry.width >= 2 && geometry.height >= 2 && geometry.width % 2 == 0 && geometry.height % 2 == 0) {
            "Sensor geometry dimensions must be even and >= 2: ${geometry.width}x${geometry.height}"
        }
        require(geometry.bitDepth in 8..16) {
            "Unsupported sensor bitDepth ${geometry.bitDepth}; expected 8..16"
        }
        require(geometry.pitchMicrometres.isFinite() && geometry.pitchMicrometres > 0.0) {
            "Sensor pixel pitch must be positive finite"
        }
        val maxCode = (1 shl geometry.bitDepth) - 1
        require(config.blackLevel < maxCode) {
            "blackLevel (${config.blackLevel}) must be strictly less than maxCode ($maxCode) for ${geometry.bitDepth}-bit sensor"
        }
        require(exposure.durationNanos > 0L) { "Exposure durationNanos must be positive" }
        require(exposure.iso > 0) { "Exposure ISO must be positive" }
        require(exposure.lens.aperture.isFinite() && exposure.lens.aperture > 0.0) {
            "Exposure lens aperture must be positive finite"
        }
        require(exposure.lens.transmission.isFinite() && exposure.lens.transmission in 0.0..1.0) {
            "Exposure lens transmission must be in 0.0..1.0"
        }
    }

    private fun sampleExactPoisson(lambda: Double, startKey: Long): Double {
        val threshold = exp(-lambda)
        var k = 0
        var p = 1.0
        var key = startKey
        while (p > threshold && k < 128) {
            val u = ((splitMix64(key++) ushr 11).toDouble() + 0.5) * INV_TWO_POW_53
            p *= u
            k++
        }
        return (k - 1).coerceAtLeast(0).toDouble()
    }

    private fun splitMix64(z0: Long): Long {
        var z = z0 + GOLDEN_GAMMA
        z = (z xor (z ushr 30)) * MIX_MUL_1
        z = (z xor (z ushr 27)) * MIX_MUL_2
        return z xor (z ushr 31)
    }
}
