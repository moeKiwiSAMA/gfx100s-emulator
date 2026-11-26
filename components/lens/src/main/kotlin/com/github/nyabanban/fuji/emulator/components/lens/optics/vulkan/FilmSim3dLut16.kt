package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Interpolation strategy for evaluating a [FilmSim3dLut16].
 */
enum class LutInterpolationMode {
    /** Standard 8-corner trilinear interpolation (matches GPU 3D texture / compute SSBO shader). */
    TRILINEAR,

    /** 4-corner tetrahedral interpolation (preserves neutral gray axis with reduced cross-talk). */
    TETRAHEDRAL,
}

/**
 * Immutable `16x16x16` 3D Film-Simulation Look-Up Table (`4096` lattice nodes, `R`-major / `G`-middle / `B`-minor
 * or `index = ((b * 16 + g) * 16 + r) * 3`) designed for both CPU parallel-tile and Vulkan Compute SSBO execution.
 *
 * **Evidence classification (`HYPOTHESIS` / `SIM`)**: All built-in synthetic film presets (`PROVIA_SYNTHETIC`,
 * `VELVIA_SYNTHETIC`, `CLASSIC_CHROME_SYNTHETIC`, `ACROS_SYNTHETIC`) are clean-room mathematical color curves
 * for optical/shader pipeline verification, **not** extracted or measured FUJIFILM GFX100S factory LUTs.
 */
class FilmSim3dLut16(
    val name: String,
    private val rgbData: FloatArray,
) {
    init {
        require(name.isNotBlank()) { "LUT name must not be blank" }
        require(rgbData.size == FLOAT_COUNT) {
            "16x16x16 3D LUT requires $FLOAT_COUNT RGB floats, got ${rgbData.size}"
        }
        for (i in rgbData.indices) {
            val v = rgbData[i]
            require(v.isFinite() && v in 0.0f..1.0f) {
                "LUT entry [$i] must be finite in [0.0, 1.0], got $v"
            }
        }
    }

    /** Returns the raw RGB float triplet at lattice node `(rIdx, gIdx, bIdx)` where each index is in `0..15`. */
    fun nodeRed(rIdx: Int, gIdx: Int, bIdx: Int): Float = rgbData[nodeOffset(rIdx, gIdx, bIdx)]

    /** Returns the Green output component at lattice node `(rIdx, gIdx, bIdx)`. */
    fun nodeGreen(rIdx: Int, gIdx: Int, bIdx: Int): Float = rgbData[nodeOffset(rIdx, gIdx, bIdx) + 1]

    /** Returns the Blue output component at lattice node `(rIdx, gIdx, bIdx)`. */
    fun nodeBlue(rIdx: Int, gIdx: Int, bIdx: Int): Float = rgbData[nodeOffset(rIdx, gIdx, bIdx) + 2]

    /**
     * Packs the `16x16x16` lattice into a `4096 * 4` (`16384` floats) `std430` `vec4` buffer
     * `[r, g, b, 1.0f]` ordered by `idx = (b * 16 + g) * 16 + r` for Vulkan SSBO / FFM staging.
     */
    fun toStd430Vec4Array(): FloatArray {
        val out = FloatArray(NODE_COUNT * 4)
        var src = 0
        var dst = 0
        repeat(NODE_COUNT) {
            out[dst++] = rgbData[src++]
            out[dst++] = rgbData[src++]
            out[dst++] = rgbData[src++]
            out[dst++] = 1.0f
        }
        return out
    }

    /**
     * Evaluates the 3D LUT at normalized input `(rIn, gIn, bIn)` (clamped to `[0, 1]`), storing
     * the result in [outRgb] starting at [outOffset] (`outRgb[outOffset + 0..2]`) with zero allocations.
     */
    fun sampleInto(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        mode: LutInterpolationMode,
        outRgb: FloatArray,
        outOffset: Int,
    ) {
        val r = rIn.coerceIn(0f, 1f) * MAX_INDEX_F
        val g = gIn.coerceIn(0f, 1f) * MAX_INDEX_F
        val b = bIn.coerceIn(0f, 1f) * MAX_INDEX_F

        val r0 = floor(r).toInt().coerceIn(0, MAX_INDEX)
        val g0 = floor(g).toInt().coerceIn(0, MAX_INDEX)
        val b0 = floor(b).toInt().coerceIn(0, MAX_INDEX)
        val r1 = min(r0 + 1, MAX_INDEX)
        val g1 = min(g0 + 1, MAX_INDEX)
        val b1 = min(b0 + 1, MAX_INDEX)

        val fr = r - r0
        val fg = g - g0
        val fb = b - b0

        if (mode == LutInterpolationMode.TRILINEAR) {
            sampleTrilinear(r0, g0, b0, r1, g1, b1, fr, fg, fb, outRgb, outOffset)
        } else {
            sampleTetrahedral(r0, g0, b0, r1, g1, b1, fr, fg, fb, outRgb, outOffset)
        }
    }

    /**
     * Evaluates the 3D LUT on a single normalized RGB triplet and returns a 3-element [FloatArray].
     */
    fun sample(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ): FloatArray {
        val out = FloatArray(3)
        sampleInto(rIn, gIn, bIn, mode, out, 0)
        return out
    }

    /**
     * Applies this 3D LUT to an entire planar [LinearRgbImage], returning a new [LinearRgbImage].
     */
    fun applyTo(
        image: LinearRgbImage,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ): LinearRgbImage {
        val count = image.pixelCount
        val outR = FloatArray(count)
        val outG = FloatArray(count)
        val outB = FloatArray(count)
        val tmp = FloatArray(3)
        for (i in 0 until count) {
            sampleInto(image.red[i], image.green[i], image.blue[i], mode, tmp, 0)
            outR[i] = tmp[0]
            outG[i] = tmp[1]
            outB[i] = tmp[2]
        }
        return LinearRgbImage(image.width, image.height, outR, outG, outB)
    }

    private fun sampleTrilinear(
        r0: Int, g0: Int, b0: Int,
        r1: Int, g1: Int, b1: Int,
        fr: Float, fg: Float, fb: Float,
        outRgb: FloatArray,
        outOffset: Int,
    ) {
        val o000 = ((b0 * SIZE + g0) * SIZE + r0) * 3
        val o100 = ((b0 * SIZE + g0) * SIZE + r1) * 3
        val o010 = ((b0 * SIZE + g1) * SIZE + r0) * 3
        val o110 = ((b0 * SIZE + g1) * SIZE + r1) * 3
        val o001 = ((b1 * SIZE + g0) * SIZE + r0) * 3
        val o101 = ((b1 * SIZE + g0) * SIZE + r1) * 3
        val o011 = ((b1 * SIZE + g1) * SIZE + r0) * 3
        val o111 = ((b1 * SIZE + g1) * SIZE + r1) * 3

        val data = rgbData
        for (c in 0..2) {
            val c00 = data[o000 + c] + (data[o100 + c] - data[o000 + c]) * fr
            val c10 = data[o010 + c] + (data[o110 + c] - data[o010 + c]) * fr
            val c01 = data[o001 + c] + (data[o101 + c] - data[o001 + c]) * fr
            val c11 = data[o011 + c] + (data[o111 + c] - data[o011 + c]) * fr
            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            outRgb[outOffset + c] = (c0 + (c1 - c0) * fb).coerceIn(0f, 1f)
        }
    }

    private fun sampleTetrahedral(
        r0: Int, g0: Int, b0: Int,
        r1: Int, g1: Int, b1: Int,
        fr: Float, fg: Float, fb: Float,
        outRgb: FloatArray,
        outOffset: Int,
    ) {
        val o000 = ((b0 * SIZE + g0) * SIZE + r0) * 3
        val o111 = ((b1 * SIZE + g1) * SIZE + r1) * 3
        val data = rgbData

        // Standard 6-tetrahedra cube partition (Sakamoto / ICC v4).
        val oA: Int
        val oB: Int
        val w0: Float
        val wA: Float
        val wB: Float
        val w1: Float
        if (fr >= fg) {
            if (fg >= fb) {
                // fr >= fg >= fb
                oA = ((b0 * SIZE + g0) * SIZE + r1) * 3
                oB = ((b0 * SIZE + g1) * SIZE + r1) * 3
                w0 = 1f - fr
                wA = fr - fg
                wB = fg - fb
                w1 = fb
            } else if (fr >= fb) {
                // fr >= fb > fg
                oA = ((b0 * SIZE + g0) * SIZE + r1) * 3
                oB = ((b1 * SIZE + g0) * SIZE + r1) * 3
                w0 = 1f - fr
                wA = fr - fb
                wB = fb - fg
                w1 = fg
            } else {
                // fb > fr >= fg
                oA = ((b1 * SIZE + g0) * SIZE + r0) * 3
                oB = ((b1 * SIZE + g0) * SIZE + r1) * 3
                w0 = 1f - fb
                wA = fb - fr
                wB = fr - fg
                w1 = fg
            }
        } else {
            if (fb > fg) {
                // fb > fg > fr
                oA = ((b1 * SIZE + g0) * SIZE + r0) * 3
                oB = ((b1 * SIZE + g1) * SIZE + r0) * 3
                w0 = 1f - fb
                wA = fb - fg
                wB = fg - fr
                w1 = fr
            } else if (fb > fr) {
                // fg >= fb > fr
                oA = ((b0 * SIZE + g1) * SIZE + r0) * 3
                oB = ((b1 * SIZE + g1) * SIZE + r0) * 3
                w0 = 1f - fg
                wA = fg - fb
                wB = fb - fr
                w1 = fr
            } else {
                // fg > fr >= fb
                oA = ((b0 * SIZE + g1) * SIZE + r0) * 3
                oB = ((b0 * SIZE + g1) * SIZE + r1) * 3
                w0 = 1f - fg
                wA = fg - fr
                wB = fr - fb
                w1 = fb
            }
        }
        for (c in 0..2) {
            val v = w0 * data[o000 + c] + wA * data[oA + c] + wB * data[oB + c] + w1 * data[o111 + c]
            outRgb[outOffset + c] = v.coerceIn(0f, 1f)
        }
    }

    private fun nodeOffset(rIdx: Int, gIdx: Int, bIdx: Int): Int {
        require(rIdx in 0..MAX_INDEX && gIdx in 0..MAX_INDEX && bIdx in 0..MAX_INDEX) {
            "LUT node index out of range 0..$MAX_INDEX: ($rIdx, $gIdx, $bIdx)"
        }
        return ((bIdx * SIZE + gIdx) * SIZE + rIdx) * 3
    }

    companion object {
        const val SIZE: Int = 16
        const val MAX_INDEX: Int = SIZE - 1
        const val MAX_INDEX_F: Float = MAX_INDEX.toFloat()
        const val NODE_COUNT: Int = SIZE * SIZE * SIZE
        const val FLOAT_COUNT: Int = NODE_COUNT * 3

        /** Builds a `16x16x16` 3D LUT by evaluating [transform] at each normalized lattice node `(r, g, b)`. */
        fun generate(
            name: String,
            transform: (r: Float, g: Float, b: Float) -> Triple<Float, Float, Float>,
        ): FilmSim3dLut16 {
            val data = FloatArray(FLOAT_COUNT)
            var idx = 0
            for (b in 0 until SIZE) {
                val bf = b / MAX_INDEX_F
                for (g in 0 until SIZE) {
                    val gf = g / MAX_INDEX_F
                    for (r in 0 until SIZE) {
                        val rf = r / MAX_INDEX_F
                        val (or, og, ob) = transform(rf, gf, bf)
                        data[idx++] = or.coerceIn(0f, 1f)
                        data[idx++] = og.coerceIn(0f, 1f)
                        data[idx++] = ob.coerceIn(0f, 1f)
                    }
                }
            }
            return FilmSim3dLut16(name, data)
        }

        /** Exact unity pass-through `16x16x16` LUT. */
        val IDENTITY: FilmSim3dLut16 = generate("IDENTITY") { r, g, b -> Triple(r, g, b) }

        /** Synthetic mild-contrast standard look (`SIM` / `HYPOTHESIS`). */
        val PROVIA_SYNTHETIC: FilmSim3dLut16 = generate("PROVIA_SYNTHETIC") { r, g, b ->
            val sr = sCurve(r, 0.12f)
            val sg = sCurve(g, 0.12f)
            val sb = sCurve(b, 0.12f)
            saturate(sr, sg, sb, 1.06f)
        }

        /** Synthetic vivid high-saturation / deeper-shadow look (`SIM` / `HYPOTHESIS`). */
        val VELVIA_SYNTHETIC: FilmSim3dLut16 = generate("VELVIA_SYNTHETIC") { r, g, b ->
            val sr = sCurve(r, 0.24f)
            val sg = sCurve(g, 0.22f)
            val sb = sCurve(b, 0.26f)
            saturate(sr, sg, sb, 1.32f)
        }

        /** Synthetic muted-saturation / hard-shadow documentary look (`SIM` / `HYPOTHESIS`). */
        val CLASSIC_CHROME_SYNTHETIC: FilmSim3dLut16 = generate("CLASSIC_CHROME_SYNTHETIC") { r, g, b ->
            val sr = sCurve(r, 0.18f)
            val sg = sCurve(g, 0.18f)
            val sb = sCurve(b, 0.15f)
            val (dr, dg, db) = saturate(sr, sg, sb, 0.78f)
            Triple(dr, (dg * 0.99f), (db * 0.96f))
        }

        /** Synthetic monochrome B&W look with subtle orthochromatic highlight roll-off (`SIM` / `HYPOTHESIS`). */
        val ACROS_SYNTHETIC: FilmSim3dLut16 = generate("ACROS_SYNTHETIC") { r, g, b ->
            val luma = (0.2627f * r + 0.6780f * g + 0.0593f * b).coerceIn(0f, 1f)
            val curved = sCurve(luma.toDouble().pow(0.95).toFloat(), 0.20f)
            Triple(curved, curved, curved)
        }

        private fun sCurve(x: Float, strength: Float): Float {
            val xc = x.coerceIn(0f, 1f)
            val smooth = xc * xc * (3f - 2f * xc)
            return (xc + (smooth - xc) * strength).coerceIn(0f, 1f)
        }

        private fun saturate(r: Float, g: Float, b: Float, factor: Float): Triple<Float, Float, Float> {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val or = max(0f, min(1f, luma + (r - luma) * factor))
            val og = max(0f, min(1f, luma + (g - luma) * factor))
            val ob = max(0f, min(1f, luma + (b - luma) * factor))
            return Triple(or, og, ob)
        }
    }
}
