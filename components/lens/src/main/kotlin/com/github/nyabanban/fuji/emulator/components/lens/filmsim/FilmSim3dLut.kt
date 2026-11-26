package com.github.nyabanban.fuji.emulator.components.lens.filmsim

import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Interpolation kernel used when evaluating a [FilmSim3dLut]. */
enum class LutInterpolationMode {
    /** Standard 8-corner trilinear interpolation (fast, smooth). */
    TRILINEAR,

    /** 4-corner tetrahedral interpolation (preserves neutral gray axis with lower hue error). */
    TETRAHEDRAL,
}

/**
 * Immutable X-Processor 4 `16x16x16` 12-bit 3D Color Lookup Table (`0xFFF60000` / `0xFFF90000`).
 *
 * Hardware layout verified (`DAT`) from GFX100S 2.13 `section4.bin` (`0x011D0DEC..0x011D0E7C` and
 * `0x017F1C8C..0x01811C8C`):
 * - `4,096` lattice nodes (`16 x 16 x 16`), indexed as `node = (b shl 8) or (g shl 4) or r`.
 * - Each node stores three 12-bit unsigned channels `(R, G, B)` in `0..4095` plus a zero pad
 *   halfword (`8` bytes/node, `32,768` bytes total per hardware SRAM bank).
 */
class FilmSim3dLut private constructor(
    val name: String,
    val simulation: FilmSimulation?,
    private val rgb12: ShortArray,
    val hardwareBaseLutIndex: Int,
    val toneCurveKey: Int,
    val cbCrMatrixQ10: IntArray,
) {
    init {
        require(rgb12.size == NODE_COUNT * 3) {
            "3D LUT requires ${NODE_COUNT * 3} channel entries, got ${rgb12.size}"
        }
        require(cbCrMatrixQ10.size == 4) {
            "CbCr 2x2 matrix requires 4 Q10 entries, got ${cbCrMatrixQ10.size}"
        }
    }

    /** Returns the raw 12-bit `(R, G, B)` output tuple at lattice coordinate `(r, g, b)` in `0..15`. */
    fun nodeRgb12(r: Int, g: Int, b: Int, out: IntArray = IntArray(3)): IntArray {
        require(r in 0 until GRID_SIZE && g in 0 until GRID_SIZE && b in 0 until GRID_SIZE) {
            "Lattice coordinates out of range 0..15: ($r, $g, $b)"
        }
        require(out.size >= 3) { "Output array requires length >= 3" }
        val base = nodeIndex(r, g, b) * 3
        out[0] = rgb12[base].toInt() and 0xFFFF
        out[1] = rgb12[base + 1].toInt() and 0xFFFF
        out[2] = rgb12[base + 2].toInt() and 0xFFFF
        return out
    }

    /**
     * Serializes this LUT into the exact `32,768`-byte little-endian hardware SRAM format written
     * to `0xFFF60000` (Live-View) and `0xFFF90000` (Still RECC).
     */
    fun toHardwareSramBytes(): ByteArray {
        val out = ByteArray(HARDWARE_SRAM_BYTES)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var src = 0
        for (i in 0 until NODE_COUNT) {
            buf.putShort(rgb12[src++])
            buf.putShort(rgb12[src++])
            buf.putShort(rgb12[src++])
            buf.putShort(0)
        }
        return out
    }

    /**
     * Evaluates the 3D LUT at normalized input coordinates `(r, g, b)` in `[0f, 1f]`, writing
     * normalized `[0f, 1f]` output RGB into `out[0..2]` without heap allocation.
     */
    fun sampleNormalized(
        r: Float,
        g: Float,
        b: Float,
        out: FloatArray,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ) {
        val rc = r.coerceIn(0f, 1f) * MAX_INDEX_F
        val gc = g.coerceIn(0f, 1f) * MAX_INDEX_F
        val bc = b.coerceIn(0f, 1f) * MAX_INDEX_F

        val r0 = rc.toInt().coerceAtMost(GRID_SIZE - 2)
        val g0 = gc.toInt().coerceAtMost(GRID_SIZE - 2)
        val b0 = bc.toInt().coerceAtMost(GRID_SIZE - 2)

        val fr = rc - r0
        val fg = gc - g0
        val fb = bc - b0

        when (mode) {
            LutInterpolationMode.TRILINEAR -> sampleTrilinear(r0, g0, b0, fr, fg, fb, out)
            LutInterpolationMode.TETRAHEDRAL -> sampleTetrahedral(r0, g0, b0, fr, fg, fb, out)
        }
    }

    /**
     * Evaluates the 3D LUT on 12-bit integer RGB (`0..4095`), writing clamped 12-bit integer
     * `(R, G, B)` into `out[0..2]`.
     */
    fun sample12Bit(
        r12: Int,
        g12: Int,
        b12: Int,
        out: IntArray = IntArray(3),
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ): IntArray {
        val tmp = FloatArray(3)
        sampleNormalized(
            r12.coerceIn(0, MAX_VALUE) * INV_MAX_VALUE_F,
            g12.coerceIn(0, MAX_VALUE) * INV_MAX_VALUE_F,
            b12.coerceIn(0, MAX_VALUE) * INV_MAX_VALUE_F,
            tmp,
            mode,
        )
        out[0] = (tmp[0] * MAX_VALUE_F).roundToInt().coerceIn(0, MAX_VALUE)
        out[1] = (tmp[1] * MAX_VALUE_F).roundToInt().coerceIn(0, MAX_VALUE)
        out[2] = (tmp[2] * MAX_VALUE_F).roundToInt().coerceIn(0, MAX_VALUE)
        return out
    }

    /** Applies the 3D LUT to an 8-bit `(r, g, b)` pixel and returns packed `0xFFRRGGBB`. */
    fun applyRgb8(
        r: Int,
        g: Int,
        b: Int,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ): Int {
        val tmp = FloatArray(3)
        sampleNormalized(
            r.coerceIn(0, 255) * INV_255_F,
            g.coerceIn(0, 255) * INV_255_F,
            b.coerceIn(0, 255) * INV_255_F,
            tmp,
            mode,
        )
        val ro = (tmp[0] * 255f).roundToInt().coerceIn(0, 255)
        val go = (tmp[1] * 255f).roundToInt().coerceIn(0, 255)
        val bo = (tmp[2] * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
    }

    /**
     * Applies this 3D LUT in-place to a packed `0xAARRGGBB` pixel buffer while preserving each
     * pixel's alpha channel. Uses zero per-pixel heap allocations.
     */
    fun applyArgbInPlace(
        argb: IntArray,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ) {
        val tmp = FloatArray(3)
        for (i in argb.indices) {
            val px = argb[i]
            val a = px and (0xFF shl 24)
            val r = ((px ushr 16) and 0xFF) * INV_255_F
            val g = ((px ushr 8) and 0xFF) * INV_255_F
            val b = (px and 0xFF) * INV_255_F
            sampleNormalized(r, g, b, tmp, mode)
            val ro = (tmp[0] * 255f).roundToInt().coerceIn(0, 255)
            val go = (tmp[1] * 255f).roundToInt().coerceIn(0, 255)
            val bo = (tmp[2] * 255f).roundToInt().coerceIn(0, 255)
            argb[i] = a or (ro shl 16) or (go shl 8) or bo
        }
    }

    /**
     * Applies this 3D LUT to a planar [LinearRgbImage] from the optical/CMOS pipeline.
     *
     * When [encodeSrgb] is `true`, input linear radiance `[0, 1]` is sRGB OETF encoded before
     * evaluating the display-referred 3D LUT (recommended for CMOS preview/export output).
     */
    fun applyLinearRgb(
        src: LinearRgbImage,
        dst: LinearRgbImage = LinearRgbImage.allocate(src.width, src.height),
        encodeSrgb: Boolean = true,
        mode: LutInterpolationMode = LutInterpolationMode.TRILINEAR,
    ): LinearRgbImage {
        require(dst.width == src.width && dst.height == src.height) {
            "Destination dimensions ${dst.width}x${dst.height} mismatch source ${src.width}x${src.height}"
        }
        val tmp = FloatArray(3)
        val n = src.pixelCount
        for (i in 0 until n) {
            val r = if (encodeSrgb) linearToSrgb(src.red[i]) else src.red[i]
            val g = if (encodeSrgb) linearToSrgb(src.green[i]) else src.green[i]
            val b = if (encodeSrgb) linearToSrgb(src.blue[i]) else src.blue[i]
            sampleNormalized(r, g, b, tmp, mode)
            dst.red[i] = tmp[0]
            dst.green[i] = tmp[1]
            dst.blue[i] = tmp[2]
        }
        return dst
    }

    private fun sampleTrilinear(
        r0: Int,
        g0: Int,
        b0: Int,
        fr: Float,
        fg: Float,
        fb: Float,
        out: FloatArray,
    ) {
        val c000 = ((b0 shl 8) or (g0 shl 4) or r0) * 3
        val c100 = c000 + 3
        val c010 = c000 + 48
        val c110 = c010 + 3
        val c001 = c000 + 768
        val c101 = c001 + 3
        val c011 = c001 + 48
        val c111 = c011 + 3

        val omr = 1f - fr
        val omg = 1f - fg
        val omb = 1f - fb

        val w000 = omr * omg * omb
        val w100 = fr * omg * omb
        val w010 = omr * fg * omb
        val w110 = fr * fg * omb
        val w001 = omr * omg * fb
        val w101 = fr * omg * fb
        val w011 = omr * fg * fb
        val w111 = fr * fg * fb

        val data = rgb12
        for (ch in 0..2) {
            val v = data[c000 + ch] * w000 +
                data[c100 + ch] * w100 +
                data[c010 + ch] * w010 +
                data[c110 + ch] * w110 +
                data[c001 + ch] * w001 +
                data[c101 + ch] * w101 +
                data[c011 + ch] * w011 +
                data[c111 + ch] * w111
            out[ch] = (v * INV_MAX_VALUE_F).coerceIn(0f, 1f)
        }
    }

    private fun sampleTetrahedral(
        r0: Int,
        g0: Int,
        b0: Int,
        fr: Float,
        fg: Float,
        fb: Float,
        out: FloatArray,
    ) {
        val c000 = ((b0 shl 8) or (g0 shl 4) or r0) * 3
        val c100 = c000 + 3
        val c010 = c000 + 48
        val c110 = c010 + 3
        val c001 = c000 + 768
        val c101 = c001 + 3
        val c011 = c001 + 48
        val c111 = c011 + 3

        val data = rgb12
        for (ch in 0..2) {
            val v000 = data[c000 + ch].toFloat()
            val v111 = data[c111 + ch].toFloat()
            val v = if (fr >= fg) {
                when {
                    fg >= fb -> {
                        // T1: fr >= fg >= fb
                        v000 + fr * (data[c100 + ch] - v000) +
                            fg * (data[c110 + ch] - data[c100 + ch]) +
                            fb * (v111 - data[c110 + ch])
                    }
                    fr >= fb -> {
                        // T2: fr >= fb > fg
                        v000 + fr * (data[c100 + ch] - v000) +
                            fb * (data[c101 + ch] - data[c100 + ch]) +
                            fg * (v111 - data[c101 + ch])
                    }
                    else -> {
                        // T3: fb > fr >= fg
                        v000 + fb * (data[c001 + ch] - v000) +
                            fr * (data[c101 + ch] - data[c001 + ch]) +
                            fg * (v111 - data[c101 + ch])
                    }
                }
            } else {
                when {
                    fb > fg -> {
                        // T4: fb > fg > fr
                        v000 + fb * (data[c001 + ch] - v000) +
                            fg * (data[c011 + ch] - data[c001 + ch]) +
                            fr * (v111 - data[c011 + ch])
                    }
                    fb > fr -> {
                        // T5: fg >= fb > fr
                        v000 + fg * (data[c010 + ch] - v000) +
                            fb * (data[c011 + ch] - data[c010 + ch]) +
                            fr * (v111 - data[c011 + ch])
                    }
                    else -> {
                        // T6: fg > fr >= fb
                        v000 + fg * (data[c010 + ch] - v000) +
                            fr * (data[c110 + ch] - data[c010 + ch]) +
                            fb * (v111 - data[c110 + ch])
                    }
                }
            }
            out[ch] = (v * INV_MAX_VALUE_F).coerceIn(0f, 1f)
        }
    }

    companion object {
        const val GRID_SIZE = 16
        const val NODE_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE
        const val MAX_VALUE = 4095
        const val HARDWARE_SRAM_BYTES = NODE_COUNT * 8

        private const val MAX_INDEX_F = (GRID_SIZE - 1).toFloat()
        private const val MAX_VALUE_F = MAX_VALUE.toFloat()
        private const val INV_MAX_VALUE_F = 1f / MAX_VALUE_F
        private const val INV_255_F = 1f / 255f

        /** Hardware lattice index `node = (b << 8) | (g << 4) | r` (`DAT` `0x017F1C8C`). */
        fun nodeIndex(r: Int, g: Int, b: Int): Int = (b shl 8) or (g shl 4) or r

        /** Builds an exact 12-bit identity `16x16x16` 3D LUT matching `LUT[0]` @ `0x017F1C8C`. */
        fun identity(name: String = "Identity"): FilmSim3dLut {
            val rgb12 = ShortArray(NODE_COUNT * 3)
            for (b in 0 until GRID_SIZE) {
                val bv = ((b * MAX_VALUE + 7) / 15).toShort()
                for (g in 0 until GRID_SIZE) {
                    val gv = ((g * MAX_VALUE + 7) / 15).toShort()
                    for (r in 0 until GRID_SIZE) {
                        val rv = ((r * MAX_VALUE + 7) / 15).toShort()
                        val dst = nodeIndex(r, g, b) * 3
                        rgb12[dst] = rv
                        rgb12[dst + 1] = gv
                        rgb12[dst + 2] = bv
                    }
                }
            }
            return FilmSim3dLut(
                name = name,
                simulation = null,
                rgb12 = rgb12,
                hardwareBaseLutIndex = 0,
                toneCurveKey = 0,
                cbCrMatrixQ10 = intArrayOf(1024, 1024, 0, 0),
            )
        }

        /**
         * Creates a `16x16x16` 12-bit 3D LUT from a flat `4096 * 3` RGB12 array (`0..4095`),
         * indexed as `(b << 8) | (g << 4) | r`.
         */
        fun fromRgb12(
            name: String,
            rgb12: ShortArray,
            simulation: FilmSimulation? = null,
            hardwareBaseLutIndex: Int = 0,
            toneCurveKey: Int = 0,
            cbCrMatrixQ10: IntArray = intArrayOf(1024, 1024, 0, 0),
        ): FilmSim3dLut {
            require(rgb12.size == NODE_COUNT * 3) {
                "Expected ${NODE_COUNT * 3} RGB12 entries, got ${rgb12.size}"
            }
            val copy = ShortArray(rgb12.size)
            for (i in rgb12.indices) {
                val v = rgb12[i].toInt() and 0xFFFF
                require(v in 0..MAX_VALUE) { "12-bit LUT value out of range 0..4095 at $i: $v" }
                copy[i] = v.toShort()
            }
            return FilmSim3dLut(
                name = name,
                simulation = simulation,
                rgb12 = copy,
                hardwareBaseLutIndex = hardwareBaseLutIndex,
                toneCurveKey = toneCurveKey,
                cbCrMatrixQ10 = cbCrMatrixQ10.copyOf(),
            )
        }

        /**
         * Parses a raw `32,768`-byte X-Processor 4 3D LUT SRAM block (`4 x u16` little-endian per
         * node: `R[11:0], G[11:0], B[11:0], pad`).
         */
        fun fromHardwareSramBytes(
            name: String,
            bytes: ByteArray,
            offset: Int = 0,
            simulation: FilmSimulation? = null,
            hardwareBaseLutIndex: Int = 0,
            toneCurveKey: Int = 0,
            cbCrMatrixQ10: IntArray = intArrayOf(1024, 1024, 0, 0),
        ): FilmSim3dLut {
            require(offset >= 0 && offset <= bytes.size - HARDWARE_SRAM_BYTES) {
                "Insufficient bytes for 32768-byte 3D LUT at offset=$offset (len=${bytes.size})"
            }
            val buf = ByteBuffer.wrap(bytes, offset, HARDWARE_SRAM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val rgb12 = ShortArray(NODE_COUNT * 3)
            var dst = 0
            for (i in 0 until NODE_COUNT) {
                val r = (buf.short.toInt() and 0xFFF).coerceIn(0, MAX_VALUE)
                val g = (buf.short.toInt() and 0xFFF).coerceIn(0, MAX_VALUE)
                val b = (buf.short.toInt() and 0xFFF).coerceIn(0, MAX_VALUE)
                buf.short // pad
                rgb12[dst++] = r.toShort()
                rgb12[dst++] = g.toShort()
                rgb12[dst++] = b.toShort()
            }
            return FilmSim3dLut(
                name = name,
                simulation = simulation,
                rgb12 = rgb12,
                hardwareBaseLutIndex = hardwareBaseLutIndex,
                toneCurveKey = toneCurveKey,
                cbCrMatrixQ10 = cbCrMatrixQ10.copyOf(),
            )
        }

        private fun linearToSrgb(linear: Float): Float {
            val x = linear.coerceIn(0f, 1f)
            return if (x <= 0.0031308f) {
                12.92f * x
            } else {
                (1.055f * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
            }
        }
    }
}
