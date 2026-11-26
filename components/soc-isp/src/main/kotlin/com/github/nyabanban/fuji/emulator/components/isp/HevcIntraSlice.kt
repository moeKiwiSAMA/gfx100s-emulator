package com.github.nyabanban.fuji.emulator.components.isp

import kotlin.math.abs

/** Source samples and reconstructed borders needed by the next displayed HEVC picture. */
internal class HevcReferenceFrame(
    val width: Int, val height: Int,
    val y: ByteArray?, val cb: ByteArray?, val cr: ByteArray?, val fineLuma: ByteArray?,
    val bitDepth10: Boolean, private val keepReference: Boolean = true,
) {
    val cols = (width.coerceIn(16, 4096) + 15) / 16
    val rows = (height.coerceIn(16, 2160) + 15) / 16
    // Per CTU, store bottom and right edges of 16 luma and 8 samples per chroma plane.
    var borders: ShortArray? = null
        private set

    var pcmBlocks: Array<ShortArray?>? = null
        private set

    init {
        require(fineLuma == null || fineLuma.size >= cols * rows * 16)
    }

    fun prepareBorders(previous: HevcReferenceFrame?, withPcm: Boolean): Boolean {
        if (withPcm && keepReference) pcmBlocks = arrayOfNulls(cols * rows)
        val unchanged = previous != null && y.contentEquals(previous.y) && cb.contentEquals(previous.cb) &&
            cr.contentEquals(previous.cr) && fineLuma.contentEquals(previous.fineLuma)
        borders = when {
            !keepReference -> null
            unchanged -> previous.borders
            else -> ShortArray(cols * rows * 64)
        }
        return unchanged
    }

    fun hasUnrepresentedDetail(pixels: ShortArray, index: Int, origin: Int): Boolean {
        val targetY = y?.getOrNull(index)?.toInt()?.and(255) ?: 128
        for (row in 0 until 16) for (col in 0 until 16) {
            val sample = fineLuma?.get(origin + row / 4 * cols * 4 + col / 4)?.toInt()?.and(255) ?: targetY
            if (abs(pixels[row * 16 + col].toInt() - sample * 4) >= 16) return true
        }
        val blue = (cb?.getOrNull(index)?.toInt()?.and(255) ?: 128) * 4
        val red = (cr?.getOrNull(index)?.toInt()?.and(255) ?: 128) * 4
        for (i in 0 until 64) {
            if (abs(pixels[256 + i].toInt() - blue) >= 16 || abs(pixels[320 + i].toInt() - red) >= 16) return true
        }
        return false
    }

    fun matchesPcm(pixels: ShortArray, reference: ShortArray): Boolean {
        if (bitDepth10) return pixels.contentEquals(reference)
        for (i in pixels.indices) if ((pixels[i].toInt() ushr 2) != (reference[i].toInt() ushr 2)) return false
        return true
    }

    fun matchesBlock(previous: HevcReferenceFrame, index: Int, origin: Int): Boolean {
        if (y?.getOrNull(index) != previous.y?.getOrNull(index) ||
            cb?.getOrNull(index) != previous.cb?.getOrNull(index) ||
            cr?.getOrNull(index) != previous.cr?.getOrNull(index)) return false
        val priorFine = previous.fineLuma
        if (fineLuma == null || priorFine == null) return fineLuma === priorFine
        for (line in 0..3) {
            val start = origin + line * cols * 4
            for (x in start until start + 4) if (fineLuma[x] != priorFine[x]) return false
        }
        return true
    }
}

/** V61 HEVC coding uses zero-motion skip or adaptive intra transforms on each 16x16 source tile. */
internal object HevcIntraSlice {
    /** One CU per 16x16 input tile. Textured CUs use four transforms with low-frequency AC. */
    fun write(
        out: MaliV61BitstreamEncoder.BitWriter, frame: HevcReferenceFrame, previous: HevcReferenceFrame?,
        pcm: MvePcmSource?, targetBytes: Int, constantRate: Boolean,
    ) {
        val cols = frame.cols
        val rows = frame.rows
        val fineLuma = frame.fineLuma
        val bitDepth10 = frame.bitDepth10
        val cabac = Cabac(out, if (previous == null) intraInit else predictiveInit)
        if (frame.prepareBorders(if (pcm == null) previous else null, withPcm = pcm != null)) {
            // A repeated input shares the reconstructed surface and needs only skip syntax.
            for (row in 0 until rows) for (col in 0 until cols) {
                val context = (if (row > 0) 1 else 0) + (if (col > 0) 1 else 0)
                cabac.bin(SPLIT_CU, 0)
                cabac.bin(SKIP + context, 1)
                cabac.terminate(row == rows - 1 && col == cols - 1)
            }
            cabac.finish()
            return
        }
        val skipped = BooleanArray(cols)
        val scale = if (bitDepth10) 4 else 1
        val luma = ReferenceEdges(cols * 16, 16, scale)
        val blue = ReferenceEdges(cols * 8, 8, scale)
        val red = ReferenceEdges(cols * 8, 8, scale)
        val coefficients = Array(4) { IntArray(5) }
        val blueLevels = IntArray(4)
        val redLevels = IntArray(4)
        val fineStride = cols * 4
        val pcmSamples = if (pcm != null) ShortArray(384) else null
        val blockBytes = if (bitDepth10) 480 else 384
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                val origin = row * 4 * fineStride + col * 4
                val priorPcm = previous?.pcmBlocks?.get(index)
                val sameSamples = previous != null && frame.matchesBlock(previous, index, origin)
                // Distribute the budget across the raster using actual escaped VCL bytes already emitted.
                val canSpend = pcm != null && out.byteCount + blockBytes + 5 <=
                    targetBytes.toLong() * (index + 1) / (cols * rows)
                val raw = pcmSamples?.takeIf {
                    (priorPcm != null || canSpend) && pcm?.readBlock(col, row, it) == true
                }
                val sameOriginal = priorPcm != null && raw != null && frame.matchesPcm(raw, priorPcm)
                val usePcm = canSpend && raw != null && !sameOriginal && (constantRate || priorPcm != null ||
                    (previous != null && !sameSamples) || frame.hasUnrepresentedDetail(raw, index, origin))
                cabac.bin(SPLIT_CU, 0)
                if (previous != null) {
                    val skip = !usePcm && if (priorPcm != null) sameOriginal else sameSamples
                    val context = (if (col > 0 && skipped[col - 1]) 1 else 0) + (if (skipped[col]) 1 else 0)
                    cabac.bin(SKIP + context, if (skip) 1 else 0)
                    skipped[col] = skip
                    if (skip) {
                        val prior = requireNotNull(previous.borders)
                        val start = index * 64
                        luma.restore(col, prior, start)
                        blue.restore(col, prior, start + 32)
                        red.restore(col, prior, start + 48)
                        frame.borders?.let { prior.copyInto(it, start, start, start + 64) }
                        frame.pcmBlocks?.set(index, priorPcm)
                        cabac.terminate(index == cols * rows - 1)
                        continue
                    }
                    cabac.bin(PRED_MODE, 1) // intra CU inside a P slice
                }
                if (pcm != null) {
                    cabac.terminate(usePcm) // pcm_flag is a CABAC termination bin
                    if (usePcm) {
                        frame.pcmBlocks?.set(index, raw.copyOf())
                        cabac.finish()
                        if (bitDepth10) {
                            out.writePcm10(raw)
                        } else {
                            out.writePcm8(raw)
                        }
                        cabac.restartArithmetic()
                        val shift = if (bitDepth10) 0 else 2
                        luma.loadPcm(col, raw, 0, shift)
                        blue.loadPcm(col, raw, 256, shift)
                        red.loadPcm(col, raw, 320, shift)
                        frame.borders?.let { edges ->
                            val start = index * 64
                            luma.save(col, edges, start)
                            blue.save(col, edges, start + 32)
                            red.save(col, edges, start + 48)
                        }
                        cabac.terminate(index == cols * rows - 1)
                        continue
                    }
                }
                val targetY = frame.y?.getOrNull(index)?.toInt()?.and(255) ?: 128
                val targetBlue = frame.cb?.getOrNull(index)?.toInt()?.and(255) ?: 128
                val targetRed = frame.cr?.getOrNull(index)?.toInt()?.and(255) ?: 128
                var split = false
                if (fineLuma != null) {
                    for (dy in 0..3) for (dx in 0..3) {
                        if (abs((fineLuma[origin + dy * fineStride + dx].toInt() and 255) - targetY) >= 4) split = true
                    }
                }
                cabac.bin(PREV_INTRA, 1)
                cabac.bypass(1)
                cabac.bypass(0) // DC is candidate 1 with DC neighbours
                cabac.bin(CHROMA_MODE, 0)
                cabac.bin(SPLIT_TRANSFORM, if (split) 1 else 0)
                if (split && fineLuma != null) {
                    for (i in 0..3) {
                        val dx = (i and 1) * 8
                        val dy = (i shr 1) * 8
                        val at = origin + dy / 4 * fineStride + dx / 4
                        luma.encode8x8(col * 16 + dx, row * 16 + dy, fineLuma, at, fineStride, coefficients[i])
                        blueLevels[i] = blue.encodeDc(col * 8 + dx / 2, row * 8 + dy / 2, 4, targetBlue)
                        redLevels[i] = red.encodeDc(col * 8 + dx / 2, row * 8 + dy / 2, 4, targetRed)
                    }
                    val hasBlue = blueLevels.any { it != 0 }
                    val hasRed = redLevels.any { it != 0 }
                    cabac.bin(CBF_CHROMA, if (hasBlue) 1 else 0)
                    cabac.bin(CBF_CHROMA, if (hasRed) 1 else 0)
                    for (i in 0..3) {
                        if (hasBlue) cabac.bin(CBF_CHROMA_CHILD, if (blueLevels[i] != 0) 1 else 0)
                        if (hasRed) cabac.bin(CBF_CHROMA_CHILD, if (redLevels[i] != 0) 1 else 0)
                        val hasY = coefficients[i].any { it != 0 }
                        cabac.bin(CBF_LUMA_CHILD, if (hasY) 1 else 0)
                        if (hasY) cabac.luma8x8(coefficients[i])
                        if (blueLevels[i] != 0) cabac.coefficient(blueLevels[i], true)
                        if (redLevels[i] != 0) cabac.coefficient(redLevels[i], true)
                    }
                } else {
                    val level = luma.encodeDc(col * 16, row * 16, 16, targetY)
                    val cbLevel = blue.encodeDc(col * 8, row * 8, 8, targetBlue)
                    val crLevel = red.encodeDc(col * 8, row * 8, 8, targetRed)
                    cabac.bin(CBF_CHROMA, if (cbLevel != 0) 1 else 0)
                    cabac.bin(CBF_CHROMA, if (crLevel != 0) 1 else 0)
                    cabac.bin(CBF_LUMA, if (level != 0) 1 else 0)
                    if (level != 0) cabac.coefficient(level, false)
                    if (cbLevel != 0) cabac.coefficient(cbLevel, true)
                    if (crLevel != 0) cabac.coefficient(crLevel, true)
                }
                frame.borders?.let { edges ->
                    val start = index * 64
                    luma.save(col, edges, start)
                    blue.save(col, edges, start + 32)
                    red.save(col, edges, start + 48)
                }
                cabac.terminate(index == cols * rows - 1)
            }
        }
        cabac.finish()
    }

    // HEVC intra DC prediction uses the reconstructed left column and top row.
    // A scanline and one CTU's left edge suffice even during recursive TU traversal.
    private class ReferenceEdges(width: Int, val ctuSize: Int, val scale: Int) {
        private val bottom = IntArray(width)
        private val right = IntArray(ctuSize)
        private val luma = ctuSize == 16

        fun save(col: Int, edges: ShortArray, start: Int) {
            for (i in 0 until ctuSize) {
                edges[start + i] = bottom[col * ctuSize + i].toShort()
                edges[start + ctuSize + i] = right[i].toShort()
            }
        }

        fun loadPcm(col: Int, samples: ShortArray, plane: Int, shift: Int) {
            for (i in 0 until ctuSize) {
                bottom[col * ctuSize + i] = samples[plane + (ctuSize - 1) * ctuSize + i].toInt() ushr shift
                right[i] = samples[plane + i * ctuSize + ctuSize - 1].toInt() ushr shift
            }
        }

        fun restore(col: Int, edges: ShortArray, start: Int) {
            for (i in 0 until ctuSize) {
                bottom[col * ctuSize + i] = edges[start + i].toInt()
                right[i] = edges[start + ctuSize + i].toInt()
            }
        }

        private fun predict(x: Int, y: Int, size: Int): Int {
            val offset = y % ctuSize
            var sum = size
            for (i in 0 until size) {
                sum += if (y > 0) bottom[x + i] else if (x > 0) right[offset] else 128 * scale
                sum += if (x > 0) right[offset + i] else if (y > 0) bottom[x] else 128 * scale
            }
            return sum / (size * 2)
        }

        private inline fun reconstruct(x: Int, y: Int, size: Int, dc: Int, residual: (Int, Int) -> Int) {
            val offset = y % ctuSize
            val topAtRight = if (y > 0) bottom[x + size - 1] else if (x > 0) right[offset] else 128 * scale
            val leftAtBottom = if (x > 0) right[offset + size - 1] else if (y > 0) bottom[x] else 128 * scale
            for (i in 0 until size) {
                val bottomPred = if (luma && i == 0) (leftAtBottom + 3 * dc + 2) / 4 else dc
                val rightPred = if (luma && i == 0) (topAtRight + 3 * dc + 2) / 4 else dc
                bottom[x + i] = (bottomPred + residual(i, size - 1)).coerceIn(0, 256 * scale - 1)
                right[offset + i] = (rightPred + residual(size - 1, i)).coerceIn(0, 256 * scale - 1)
            }
        }

        fun encodeDc(x: Int, y: Int, size: Int, target: Int): Int {
            val prediction = predict(x, y, size)
            val step = 16 / size
            val level = roundedDivision(target * scale - prediction, step * scale)
            reconstruct(x, y, size, prediction) { _, _ -> level * step * scale }
            return level
        }

        fun encode8x8(x: Int, y: Int, source: ByteArray, at: Int, stride: Int, levels: IntArray) {
            val prediction = predict(x, y, 8)
            val a = source[at].toInt() and 255
            val b = source[at + 1].toInt() and 255
            val c = source[at + stride].toInt() and 255
            val d = source[at + stride + 1].toInt() and 255
            // Fit the quadrant averages on the separable 8-point HEVC integer DCT.
            // The mean of the first four AC basis entries (89,75,50,18) is 58/64.
            levels[0] = roundedDivision((a + b + c + d) * scale - 4 * prediction, 8 * scale)
            levels[1] = roundedDivision((a + b - c - d) * 8, 58)
            levels[2] = roundedDivision((a - b + c - d) * 8, 58)
            levels[4] = roundedDivision((a - b - c + d) * 512, 58 * 58)
            reconstruct(x, y, 8, prediction) { px, py ->
                val fx = basis8[px]
                val fy = basis8[py]
                // Decoder's separable integer IDCT (QP 28, default scaling list).
                val columnDc = 128 * levels[0] + 2 * fy * levels[1]
                val columnAc = 128 * levels[2] + 2 * fy * levels[4]
                val shift = if (scale == 4) 10 else 12
                (64 * columnDc + fx * columnAc + (1 shl (shift - 1))) shr shift
            }
        }
    }

    private fun roundedDivision(value: Int, divisor: Int): Int =
        if (value < 0) -((-value + divisor / 2) / divisor) else (value + divisor / 2) / divisor

    private val basis8 = intArrayOf(89, 75, 50, 18, -18, -50, -75, -89)

    // H.265 9.3 context initialisation for the I and P slice syntax bins used here.
    private const val SPLIT_CU = 0
    private const val PREV_INTRA = 1
    private const val CHROMA_MODE = 2
    private const val CBF_CHROMA = 3
    private const val CBF_LUMA = 4
    private const val LAST_X_Y = 5
    private const val LAST_Y_Y = 6
    private const val LAST_X_C = 7
    private const val LAST_Y_C = 8
    private const val GREATER1_Y = 9
    private const val GREATER1_C = 10
    private const val GREATER2_Y = 11
    private const val GREATER2_C = 12
    private const val SPLIT_TRANSFORM = 13
    private const val CBF_CHROMA_CHILD = 14
    private const val CBF_LUMA_CHILD = 15
    private const val LAST_X_8 = 16
    private const val LAST_Y_8 = 17
    private const val SIG_DC = 18
    private const val SIG_AC = 19
    private const val GREATER1_0 = 20
    private const val GREATER1_2 = 21
    private const val GREATER1_3 = 22
    private const val SKIP = 23 // three contexts selected by the left and top skip flags
    private const val PRED_MODE = 26
    private val intraInit = intArrayOf(139, 184, 63, 94, 141, 125, 125, 108, 108, 92, 179, 138, 152,
        138, 138, 111, 125, 125, 111, 125, 140, 137, 138)

    private val predictiveInit = intArrayOf(107, 154, 152, 149, 111, 125, 125, 108, 108, 196, 194, 107, 107,
        138, 107, 153, 110, 110, 155, 183, 154, 196, 167, 197, 185, 201, 149)

    private class Cabac(private val out: MaliV61BitstreamEncoder.BitWriter, initialValues: IntArray) {
        private val states = IntArray(initialValues.size) { i ->
            val init = initialValues[i]
            val slope = (init / 16) * 5 - 45
            val intercept = (init % 16) * 8 - 16
            val p = ((slope * 28 shr 4) + intercept).coerceIn(1, 126)
            if (p >= 64) 2 * (p - 64) + 1 else 2 * (63 - p)
        }
        private var low = 0L
        private var range = 510
        private var freeBits = 23
        private var pendingByte = 0xff
        private var pendingCount = 0

        // PCM interrupts arithmetic coding, while its probability contexts survive the raw sample payload.
        fun restartArithmetic() {
            low = 0L
            range = 510
            freeBits = 23
            pendingByte = 0xff
            pendingCount = 0
        }

        fun bin(context: Int, bit: Int) {
            val encoded = states[context]
            val state = encoded shr 1
            val mps = encoded and 1
            val lps = lpsRange[4 * state + ((range shr 6) and 3)]
            range -= lps
            if (bit == mps) {
                states[context] = 2 * minOf(state + 1, 62) + mps
            } else {
                low += range
                range = lps
                states[context] = 2 * nextLpsState[state] + (if (state == 0) 1 - mps else mps)
            }
            while (range < 256) {
                range = range shl 1
                low = low shl 1
                freeBits--
            }
            if (freeBits < 12) emitByte()
        }

        fun bypass(bit: Int) {
            low = (low shl 1) + bit * range
            freeBits--
            if (freeBits < 12) emitByte()
        }

        fun coefficient(level: Int, chroma: Boolean) {
            bin(if (chroma) LAST_X_C else LAST_X_Y, 0)
            bin(if (chroma) LAST_Y_C else LAST_Y_Y, 0)
            val magnitude = abs(level)
            bin(if (chroma) GREATER1_C else GREATER1_Y, if (magnitude > 1) 1 else 0)
            if (magnitude > 1) bin(if (chroma) GREATER2_C else GREATER2_Y, if (magnitude > 2) 1 else 0)
            bypass(if (level < 0) 1 else 0)
            if (magnitude >= 3) remainingLevel(magnitude - 3, 0)
        }

        /** The only potentially nonzero positions are (0,0), (0,1), (1,0) and (1,1). */
        fun luma8x8(levels: IntArray) {
            val last = levels.indexOfLast { it != 0 }
            val lastX = if (last == 2 || last == 4) 1 else 0
            val lastY = if (last == 1 || last == 4) 1 else 0
            if (lastX != 0) bin(LAST_X_8, 1)
            bin(LAST_X_8, 0)
            if (lastY != 0) bin(LAST_Y_8, 1)
            bin(LAST_Y_8, 0)
            for (i in last - 1 downTo 0) bin(if (i == 0) SIG_DC else SIG_AC, if (levels[i] == 0) 0 else 1)
            var greaterContext = 1
            var firstLarge = -1
            for (i in last downTo 0) {
                val magnitude = abs(levels[i])
                if (magnitude == 0) continue
                val context = when (greaterContext) {
                    0 -> GREATER1_0
                    1 -> GREATER1_Y
                    2 -> GREATER1_2
                    else -> GREATER1_3
                }
                bin(context, if (magnitude > 1) 1 else 0)
                if (magnitude > 1) {
                    if (firstLarge < 0) firstLarge = i
                    greaterContext = 0
                } else if (greaterContext != 0) {
                    greaterContext = minOf(3, greaterContext + 1)
                }
            }
            if (firstLarge >= 0) bin(GREATER2_Y, if (abs(levels[firstLarge]) > 2) 1 else 0)
            for (i in last downTo 0) if (levels[i] != 0) bypass(if (levels[i] < 0) 1 else 0)
            var rice = 0
            for (i in last downTo 0) {
                val base = if (i == firstLarge) 3 else 2
                val magnitude = abs(levels[i])
                if (magnitude < base) continue
                remainingLevel(magnitude - base, rice)
                if (magnitude > (3 shl rice)) rice = minOf(4, rice + 1)
            }
        }

        private fun remainingLevel(value: Int, rice: Int) {
            var remaining = value
            val prefix = minOf(remaining shr rice, 3)
            repeat(prefix) { bypass(1) }
            remaining -= prefix shl rice
            var suffixBits = rice
            while (remaining >= (1 shl suffixBits)) {
                bypass(1)
                remaining -= 1 shl suffixBits
                suffixBits++
            }
            bypass(0)
            for (i in suffixBits - 1 downTo 0) bypass((remaining shr i) and 1)
        }

        fun terminate(last: Boolean) {
            range -= 2
            if (last) {
                low = (low + range) shl 7
                range = 256
                freeBits -= 7
            } else if (range < 256) {
                low = low shl 1
                range = range shl 1
                freeBits--
            }
            if (freeBits < 12) emitByte()
        }

        private fun emitByte() {
            val next = (low ushr (24 - freeBits)).toInt()
            freeBits += 8
            low = low and ((1L shl (32 - freeBits)) - 1)
            if (next == 255) {
                pendingCount++
                return
            }
            if (pendingCount > 0) {
                val carry = next shr 8
                out.writeBits(((pendingByte + carry) and 255).toLong(), 8)
                repeat(pendingCount - 1) { out.writeBits(if (carry == 0) 255L else 0L, 8) }
            }
            pendingByte = next and 255
            pendingCount = 1
        }

        fun finish() {
            val unit = 1L shl (32 - freeBits)
            val carry = if (low >= unit) 1 else 0
            if (pendingCount > 0) {
                out.writeBits(((pendingByte + carry) and 255).toLong(), 8)
                repeat(pendingCount - 1) { out.writeBits(if (carry == 0) 255L else 0L, 8) }
            }
            out.writeBits((low - carry * unit) ushr 8, 24 - freeBits)
            out.writeRbspTrailingBits()
        }
    }

    // Normative H.265 CABAC probability state transitions and rangeTabLPS.
    private val nextLpsState = intArrayOf(
        0, 0, 1, 2, 2, 4, 4, 5, 6, 7, 8, 9, 9, 11, 11, 12,
        13, 13, 15, 15, 16, 16, 18, 18, 19, 19, 21, 21, 22, 22, 23, 24,
        24, 25, 26, 26, 27, 27, 28, 29, 29, 30, 30, 30, 31, 32, 32, 33,
        33, 33, 34, 34, 35, 35, 35, 36, 36, 36, 37, 37, 37, 38, 38, 63,
    )
    private val lpsRange = intArrayOf(
            128, 176, 208, 240, 128, 167, 197, 227, 128, 158, 187, 216, 123, 150, 178, 205,
            116, 142, 169, 195, 111, 135, 160, 185, 105, 128, 152, 175, 100, 122, 144, 166,
            95, 116, 137, 158, 90, 110, 130, 150, 85, 104, 123, 142, 81, 99, 117, 135,
            77, 94, 111, 128, 73, 89, 105, 122, 69, 85, 100, 116, 66, 80, 95, 110,
            62, 76, 90, 104, 59, 72, 86, 99, 56, 69, 81, 94, 53, 65, 77, 89,
            51, 62, 73, 85, 48, 59, 69, 80, 46, 56, 66, 76, 43, 53, 63, 72,
            41, 50, 59, 69, 39, 48, 56, 65, 37, 45, 54, 62, 35, 43, 51, 59,
            33, 41, 48, 56, 32, 39, 46, 53, 30, 37, 43, 50, 29, 35, 41, 48,
            27, 33, 39, 45, 26, 31, 37, 43, 24, 30, 35, 41, 23, 28, 33, 39,
            22, 27, 32, 37, 21, 26, 30, 35, 20, 24, 29, 33, 19, 23, 27, 31,
            18, 22, 26, 30, 17, 21, 25, 28, 16, 20, 23, 27, 15, 19, 22, 25,
            14, 18, 21, 24, 14, 17, 20, 23, 13, 16, 19, 22, 12, 15, 18, 21,
            12, 14, 17, 20, 11, 14, 16, 19, 11, 13, 15, 18, 10, 12, 15, 17,
            10, 12, 14, 16, 9, 11, 13, 15, 9, 11, 12, 14, 8, 10, 12, 14,
            8, 9, 11, 13, 7, 9, 11, 12, 7, 9, 10, 12, 7, 8, 10, 11,
            6, 8, 9, 11, 6, 7, 9, 10, 6, 7, 8, 9, 2, 2, 2, 2,
    )
}
