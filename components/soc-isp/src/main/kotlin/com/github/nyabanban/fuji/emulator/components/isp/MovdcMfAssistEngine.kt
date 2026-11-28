package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.LensParameters
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Hardware Digital Split Image (`Mono`/`Color`) and Digital Microprism MF Assist engines:
 *
 * 1. **`MOVDC` (`0xFFF7D000`, GIC IRQ `110`)**:
 *    - Generates dual Left-eye (`+0x40`) and Right-eye (`+0x44`) phase-disparity UYVY buffers
 *      from the active LiveView center ROI, with horizontal disparity authentically scaled by
 *      optical defocus (`1 / focusDistanceM - 1 / subjectDistanceM`, scaled by `focalLengthMm / aperture`).
 *    - Downstream `ImageScalerF000` (`0xFFF7F000`, IRQ `133`) automatically bilinear-scales `+0x40`
 *      and `+0x44` (`crop 977×327` at stride `+0x48 * 2 = 3912`) into the `496×496` Phase-A & Phase-B
 *      UYVY planes consumed by `0xFFFBB000`.
 *
 * 2. **`COMPANION_BB000` (`0xFFFBB000`, GIC IRQ `134`, `section5.bin 0x021E3F80..0x021E4A50`)**:
 *    - Hardware multi-input masked YUV compositor receiving:
 *      - `+0x10/+0x14`: Input 0 (unmodified LiveView center ROI, UYVY)
 *      - `+0x18/+0x1C`: Input 1 (Phase-A Left-eye UYVY)
 *      - `+0x20/+0x24`: Input 2 (Phase-B Right-eye UYVY)
 *      - `+0x30/+0x34`: CPU-generated 2D mask (`section5.bin 0x025654D4`, 1 byte/pixel):
 *        - `0x00`: Passthrough (`in0`, outside Microprism circle)
 *        - `0x01`: Color Phase-A (`in1`, Color Split & Microprism)
 *        - `0x02`: Color Phase-B (`in2`, Color Split & Microprism)
 *        - `0x11`: Monochrome Phase-A (`in1` luma, neutral `U=V=0x80` — Mono Split)
 *        - `0x22`: Monochrome Phase-B (`in2` luma, neutral `U=V=0x80` — Mono Split)
 *      - `+0x3C/+0x40`: Output buffer (typically in-place LiveView center ROI)
 *      - `+0x44`: `[13:0]` = width, `[29:16]` = height (`496 × 492` on GFX100S LiveView)
 */
internal object MovdcMfAssistEngine {
    /** Back-wall / QR focus calibration plane in `scene://qr-ball` (metres). */
    const val NOMINAL_SUBJECT_DISTANCE_M = 5.5
    const val MAX_PHASE_SHIFT_PX = 20

    private const val MAX_DIM = 4096
    private const val MAX_STRIDE = 32768
    private const val MAX_DMA_SPAN = 32L * 1024L * 1024L

    /**
     * Computes signed horizontal PDAF half-shift in LiveView ROI pixels from lens defocus.
     * - Returns `0` when focused on [subjectDistanceM] (aligned strips / crisp microprism).
     * - Returns non-zero opposite shifts for near vs far defocus, scaled by aperture & focal length.
     */
    fun computeDefocusShiftPx(
        lens: LensParameters?,
        subjectDistanceM: Double = NOMINAL_SUBJECT_DISTANCE_M,
    ): Int {
        val focusM = (lens?.focusDistanceM ?: 2.0).coerceIn(0.2, 50.0)
        val focalMm = (lens?.focalLengthMm ?: 63.0).coerceIn(14.0, 500.0)
        val aperture = (lens?.aperture ?: 4.0).coerceIn(1.0, 32.0)
        val deltaDiopters = (1.0 / focusM) - (1.0 / subjectDistanceM.coerceIn(0.2, 50.0))
        val rawShift = deltaDiopters * (focalMm / aperture) * 1.4
        return rawShift.roundToInt().coerceIn(-MAX_PHASE_SHIFT_PX, MAX_PHASE_SHIFT_PX)
    }

    /**
     * Populates `MOVDC` (`0xFFF7D000`) dual Left/Right phase-shifted UYVY output buffers (`+0x40`, `+0x44`)
     * right before IRQ `110` fires.
     *
     * Uses the active LiveView Ch1 buffer (`liveView1Words`) as the optical source when available,
     * falling back to `words[0x20 / 4]` when driven standalone in unit tests.
     */
    fun onMovdcComplete(
        movdcWords: LongArray,
        memory: DmaMemory?,
        liveView1Words: LongArray?,
        lens: LensParameters?,
        subjectDistanceM: Double = NOMINAL_SUBJECT_DISTANCE_M,
    ): Boolean {
        if (memory == null || movdcWords.size <= 0x90 / 4) return false
        val outA = movdcWords[0x40 / 4] and 0xFFFF_FFFFL
        val outB = movdcWords[0x44 / 4] and 0xFFFF_FFFFL
        if (outA == 0L || outB == 0L) return false

        val halfStride = (movdcWords[0x48 / 4] and 0x3FFFL).toInt()
        if (halfStride < 8 || (halfStride and 1) != 0) return false
        val outStrideBytes = halfStride * 2
        val activeW = (halfStride ushr 1) and 1.inv()
        val w90 = movdcWords[0x90 / 4]
        val activeH = (((w90 ushr 16) and 0x3FFFL).toInt() + 1).coerceIn(1, MAX_DIM)
        if (activeW !in 2..MAX_DIM || activeH !in 1..MAX_DIM || outStrideBytes > MAX_STRIDE) return false

        val outSpan = (activeH - 1).toLong() * outStrideBytes.toLong() + activeW.toLong() * 2L
        if (outSpan !in 1L..MAX_DMA_SPAN) return false
        if (!checkRegion(memory, outA, outSpan, write = true) || !checkRegion(memory, outB, outSpan, write = true)) {
            return false
        }

        // Resolve source LiveView UYVY frame (Ch1 1024×768 center ROI matching 496×492 BB000 overlay).
        val src = resolveLiveViewSource(liveView1Words, memory) ?: return false
        val shiftPx = computeDefocusShiftPx(lens, subjectDistanceM)

        // Center ROI matching GFX100S SIF 496×492 window on 1024×768 LiveView (or proportional for smaller frames).
        val roiW = if (src.width >= 512) 496 else src.width
        val roiH = if (src.height >= 512) 492 else src.height
        val roiX = (src.width - roiW) / 2
        val roiY = (src.height - roiH) / 2

        val rowBytes = activeW * 2
        val bufA = ByteArray(outSpan.toInt())
        val bufB = ByteArray(outSpan.toInt())

        val sxBaseMap = IntArray(activeW) { mx ->
            roiX + ((mx.toLong() * roiW) / activeW).toInt()
        }

        for (my in 0 until activeH) {
            val sy = (roiY + ((my.toLong() * roiH) / activeH).toInt()).coerceIn(0, src.height - 1)
            val srcRowOff = sy * src.stride
            val dstRowOff = my * outStrideBytes
            var mx = 0
            while (mx + 1 < activeW) {
                val baseX0 = sxBaseMap[mx]
                val baseX1 = sxBaseMap[mx + 1]
                val dstPos = dstRowOff + (mx shl 1)
                sampleUyvyPair(src.bytes, srcRowOff, src.width, baseX0 - shiftPx, baseX1 - shiftPx, bufA, dstPos)
                sampleUyvyPair(src.bytes, srcRowOff, src.width, baseX0 + shiftPx, baseX1 + shiftPx, bufB, dstPos)
                mx += 2
            }
        }

        memory.write(outA, ByteBuffer.wrap(bufA, 0, outSpan.toInt()))
        memory.write(outB, ByteBuffer.wrap(bufB, 0, outSpan.toInt()))
        return true
    }

    /**
     * Executes the `0xFFFBB000` (`COMPANION_BB000`, GIC IRQ `140`, `section5.bin 0x021E3F24..0x021E5108`)
     * hardware multi-input YUV combiner right before IRQ `140` fires.
     *
     * Supports both real-silicon operating modes verified in `section5.bin`:
     * 1. **Masked 3-input compositor (`maskAddr != 0`, Digital Split Image / Microprism MF Assist)**:
     *    Composites `in0` (passthrough LiveView), `in1` (Phase-A), and `in2` (Phase-B) according to
     *    the CPU-generated 2D mask (`0x00` passthrough, `0x01/0x02` Color Split & Microprism,
     *    `0x11/0x22` Mono Split with neutral `U=V=0x80` desaturation) into `out` row-by-row.
     * 2. **Unmasked 2..4-input signed Q8 weighted YUV frame combiner (`maskAddr == 0`,
     *    `+0x08[2:0] == 3`, `+0x08[6:4] = inputCount`, `+0x54` = `{w1[25:16], w0[9:0]}`,
     *    `+0x58` = `{w3[25:16], w2[9:0]}`, `section5.bin 0x021E4324 / 0x021E4A9C / 0x021E4B2C`)**:
     *    Used by `BOOST -> AF PRIORITY - LOW LIGHT` on both Rear LCD (`1024×768`) and EVF
     *    (`1280×960`) to temporally combine up to 4 LiveView ring frames (`+0x10/+0x18/+0x20/+0x28`)
     *    into the display `PIC1` output ring (`+0x3C`, `0x3e552000/0x3e7aa000/0x3ea02000`).
     */
    fun onCompanionBB000Complete(
        words: LongArray,
        memory: DmaMemory?,
    ): Boolean {
        if (memory == null || words.size <= 0x44 / 4) return false
        val maskAddr = words[0x30 / 4] and 0xFFFF_FFFFL
        val outAddr = words[0x3C / 4] and 0xFFFF_FFFFL
        val outStride = (words[0x40 / 4] and 0x7FFFL).toInt() * 2
        val w44 = words[0x44 / 4]
        val width = (w44 and 0x3FFFL).toInt()
        val height = ((w44 ushr 16) and 0x3FFFL).toInt()

        if (outAddr == 0L) return false
        if (width !in 2..MAX_DIM || (width and 1) != 0 || height !in 1..MAX_DIM) return false
        val rowBytes = width * 2
        if (outStride < rowBytes || outStride > MAX_STRIDE) return false
        val outSpan = (height - 1).toLong() * outStride + rowBytes
        if (outSpan !in 1L..MAX_DMA_SPAN || !checkRegion(memory, outAddr, outSpan, true)) return false

        if (maskAddr == 0L) {
            return onCompanionBB000WeightedCombine(words, memory, outAddr, outStride, width, height, rowBytes)
        }

        val in0Addr = words[0x10 / 4] and 0xFFFF_FFFFL
        // 0xFFFBB000 strides (+0x14/+0x1C/+0x24/+0x34/+0x40) are programmed in pixels:
        // UYVY planes are 2 bytes/pixel (* 2), while the 8-bit 2D mask (+0x34) is 1 byte/pixel.
        val in0Stride = (words[0x14 / 4] and 0x7FFFL).toInt() * 2
        val in1Addr = words[0x18 / 4] and 0xFFFF_FFFFL
        val in1Stride = (words[0x1C / 4] and 0x7FFFL).toInt() * 2
        val in2Addr = words[0x20 / 4] and 0xFFFF_FFFFL
        val in2Stride = (words[0x24 / 4] and 0x7FFFL).toInt() * 2
        val maskStride = (words[0x34 / 4] and 0x7FFFL).toInt()

        if (in0Addr == 0L || in1Addr == 0L || in2Addr == 0L) return false
        if (in0Stride < rowBytes || in1Stride < rowBytes || in2Stride < rowBytes || maskStride < width) {
            return false
        }
        if (in0Stride > MAX_STRIDE || in1Stride > MAX_STRIDE || in2Stride > MAX_STRIDE || maskStride > MAX_STRIDE) {
            return false
        }

        val in0Span = (height - 1).toLong() * in0Stride + rowBytes
        val in1Span = (height - 1).toLong() * in1Stride + rowBytes
        val in2Span = (height - 1).toLong() * in2Stride + rowBytes
        val maskSpan = (height - 1).toLong() * maskStride + width
        if (in0Span !in 1L..MAX_DMA_SPAN || in1Span !in 1L..MAX_DMA_SPAN || in2Span !in 1L..MAX_DMA_SPAN ||
            maskSpan !in 1L..MAX_DMA_SPAN
        ) {
            return false
        }
        if (!checkRegion(memory, in0Addr, in0Span, false) ||
            !checkRegion(memory, in1Addr, in1Span, false) ||
            !checkRegion(memory, in2Addr, in2Span, false) ||
            !checkRegion(memory, maskAddr, maskSpan, false)
        ) {
            return false
        }

        val in0 = readBytes(memory, in0Addr, in0Span.toInt())
        val in1 = readBytes(memory, in1Addr, in1Span.toInt())
        val in2 = readBytes(memory, in2Addr, in2Span.toInt())
        val mask = readBytes(memory, maskAddr, maskSpan.toInt())
        val outRow = ByteArray(rowBytes)

        for (y in 0 until height) {
            val o0 = y * in0Stride
            val o1 = y * in1Stride
            val o2 = y * in2Stride
            val om = y * maskStride
            var x = 0
            while (x + 1 < width) {
                val m0 = mask[om + x].toInt() and 0xFF
                val m1 = mask[om + x + 1].toInt() and 0xFF
                val sel0 = m0 and 0x0F
                val sel1 = m1 and 0x0F
                val mono0 = (m0 ushr 4) != 0
                val mono1 = (m1 ushr 4) != 0

                val p = x shl 1
                val src0 = when (sel0) {
                    1 -> in1
                    2 -> in2
                    else -> in0
                }
                val base0 = when (sel0) {
                    1 -> o1
                    2 -> o2
                    else -> o0
                }
                val src1 = when (sel1) {
                    1 -> in1
                    2 -> in2
                    else -> in0
                }
                val base1 = when (sel1) {
                    1 -> o1
                    2 -> o2
                    else -> o0
                }

                // Packed BT.601 UYVY: [U0, Y0, V0, Y1]
                outRow[p + 1] = src0[base0 + p + 1]
                outRow[p + 3] = src1[base1 + p + 3]
                if (mono0 && mono1) {
                    outRow[p] = 0x80.toByte()
                    outRow[p + 2] = 0x80.toByte()
                } else if (mono0) {
                    // Boundary pair: blend neutral chroma with active color neighbor
                    val u = ((0x80 + (src1[base1 + p].toInt() and 0xFF)) ushr 1).toByte()
                    val v = ((0x80 + (src1[base1 + p + 2].toInt() and 0xFF)) ushr 1).toByte()
                    outRow[p] = u
                    outRow[p + 2] = v
                } else if (mono1) {
                    val u = ((0x80 + (src0[base0 + p].toInt() and 0xFF)) ushr 1).toByte()
                    val v = ((0x80 + (src0[base0 + p + 2].toInt() and 0xFF)) ushr 1).toByte()
                    outRow[p] = u
                    outRow[p + 2] = v
                } else {
                    outRow[p] = src0[base0 + p]
                    outRow[p + 2] = src0[base0 + p + 2]
                }
                x += 2
            }
            // Write strictly [0..width*2) per row so surrounding LiveView pixels outside the ROI are untouched!
            memory.write(outAddr + y.toLong() * outStride.toLong(), ByteBuffer.wrap(outRow, 0, rowBytes))
        }
        return true
    }

    private fun signExtend10(raw: Long): Int {
        val v = (raw and 0x3FFL).toInt()
        return if ((v and 0x200) != 0) v - 0x400 else v
    }

    private fun onCompanionBB000WeightedCombine(
        words: LongArray,
        memory: DmaMemory,
        outAddr: Long,
        outStride: Int,
        width: Int,
        height: Int,
        rowBytes: Int,
    ): Boolean {
        val ctrl08 = words[0x08 / 4]
        val declaredCount = ((ctrl08 ushr 4) and 0x7L).toInt()
        val maxInputs = if (declaredCount in 1..4) declaredCount else 4
        val w54 = if (words.size > 0x54 / 4) words[0x54 / 4] else 0L
        val w58 = if (words.size > 0x58 / 4) words[0x58 / 4] else 0L
        val rawWeights = intArrayOf(
            signExtend10(w54),
            signExtend10(w54 ushr 16),
            signExtend10(w58),
            signExtend10(w58 ushr 16),
        )
        val inAddrs = LongArray(4)
        val inStrides = IntArray(4)
        val inSpans = IntArray(4)
        val inWeights = IntArray(4)
        var validCount = 0
        var hasNonZeroWeight = false
        for (i in 0 until maxInputs) {
            val addrOff = (0x10 + i * 8) / 4
            val strideOff = (0x14 + i * 8) / 4
            if (strideOff >= words.size) break
            val addr = words[addrOff] and 0xFFFF_FFFFL
            val stride = (words[strideOff] and 0x7FFFL).toInt() * 2
            if (addr == 0L || stride < rowBytes || stride > MAX_STRIDE) continue
            val span = (height - 1).toLong() * stride + rowBytes
            if (span !in 1L..MAX_DMA_SPAN || !checkRegion(memory, addr, span, false)) continue
            inAddrs[validCount] = addr
            inStrides[validCount] = stride
            inSpans[validCount] = span.toInt()
            inWeights[validCount] = rawWeights[i]
            if (rawWeights[i] != 0) hasNonZeroWeight = true
            validCount++
        }
        if (validCount == 0) return false

        // Select active non-zero weighted planes (or equal-weight average if weights were left zero).
        var activeCount = 0
        val activeAddrs = LongArray(validCount)
        val activeStrides = IntArray(validCount)
        val activeSpans = IntArray(validCount)
        val activeWeights = IntArray(validCount)
        if (hasNonZeroWeight) {
            for (i in 0 until validCount) {
                if (inWeights[i] != 0) {
                    activeAddrs[activeCount] = inAddrs[i]
                    activeStrides[activeCount] = inStrides[i]
                    activeSpans[activeCount] = inSpans[i]
                    activeWeights[activeCount] = inWeights[i]
                    activeCount++
                }
            }
        } else {
            val baseW = 256 / validCount
            val remW = 256 - baseW * validCount
            for (i in 0 until validCount) {
                activeAddrs[i] = inAddrs[i]
                activeStrides[i] = inStrides[i]
                activeSpans[i] = inSpans[i]
                activeWeights[i] = if (i == 0) baseW + remW else baseW
            }
            activeCount = validCount
        }

        // Fast path: single unity-weight plane copy (w == 256 in Q8, e.g. 0x54 = 0x100 in bright light).
        if (activeCount == 1 && activeWeights[0] == 256) {
            val srcAddr = activeAddrs[0]
            val srcStride = activeStrides[0]
            if (srcAddr == outAddr && srcStride == outStride) return true
            val srcBytes = readBytes(memory, srcAddr, activeSpans[0])
            if (srcStride == outStride && outStride == rowBytes) {
                memory.write(outAddr, ByteBuffer.wrap(srcBytes, 0, srcBytes.size))
            } else {
                for (y in 0 until height) {
                    memory.write(
                        outAddr + y.toLong() * outStride.toLong(),
                        ByteBuffer.wrap(srcBytes, y * srcStride, rowBytes),
                    )
                }
            }
            return true
        }

        val planes = Array(activeCount) { i -> readBytes(memory, activeAddrs[i], activeSpans[i]) }
        val packedOut = outStride == rowBytes
        val outBuf = ByteArray(if (packedOut) height * rowBytes else rowBytes)
        for (y in 0 until height) {
            val dstRowOff = if (packedOut) y * rowBytes else 0
            var x = 0
            while (x + 1 < width) {
                val p = x shl 1
                var uSum = 0
                var y0Sum = 0
                var vSum = 0
                var y1Sum = 0
                for (k in 0 until activeCount) {
                    val wk = activeWeights[k]
                    val plane = planes[k]
                    val off = y * activeStrides[k] + p
                    uSum += wk * ((plane[off].toInt() and 0xFF) - 128)
                    y0Sum += wk * (plane[off + 1].toInt() and 0xFF)
                    vSum += wk * ((plane[off + 2].toInt() and 0xFF) - 128)
                    y1Sum += wk * (plane[off + 3].toInt() and 0xFF)
                }
                val dp = dstRowOff + p
                outBuf[dp] = (128 + ((uSum + 128) shr 8)).coerceIn(0, 255).toByte()
                outBuf[dp + 1] = ((y0Sum + 128) shr 8).coerceIn(0, 255).toByte()
                outBuf[dp + 2] = (128 + ((vSum + 128) shr 8)).coerceIn(0, 255).toByte()
                outBuf[dp + 3] = ((y1Sum + 128) shr 8).coerceIn(0, 255).toByte()
                x += 2
            }
            if (!packedOut) {
                memory.write(outAddr + y.toLong() * outStride.toLong(), ByteBuffer.wrap(outBuf, 0, rowBytes))
            }
        }
        if (packedOut) {
            memory.write(outAddr, ByteBuffer.wrap(outBuf))
        }
        return true
    }

    private class UyvySource(val bytes: ByteArray, val width: Int, val height: Int, val stride: Int)

    private fun resolveLiveViewSource(liveView1Words: LongArray?, memory: DmaMemory): UyvySource? {
        if (liveView1Words == null || liveView1Words.isEmpty()) return null
        val yLow = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c4)]
        val yHigh = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c0)]
        val uvLow = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0cc)]
        val stride = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d0)].toInt()
        val sizeWord = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x018)]
        val yAddr = ((yHigh and 0x3L) shl 32) or (yLow and 0xFFFF_FFFFL)
        if (yAddr == 0L || uvLow != 0L) return null
        val width = ((sizeWord and 0xFFFFL) + 1L).toInt()
        val height = (((sizeWord ushr 16) and 0xFFFFL) + 1L).toInt()
        if (width !in 2..MAX_DIM || (width and 1) != 0 || height !in 2..MAX_DIM || stride < width * 2 || stride > MAX_STRIDE) {
            return null
        }
        val span = (height - 1).toLong() * stride.toLong() + width.toLong() * 2L
        if (span !in 1L..MAX_DMA_SPAN || !checkRegion(memory, yAddr, span, write = false)) return null
        return UyvySource(readBytes(memory, yAddr, span.toInt()), width, height, stride)
    }

    private fun sampleUyvyPair(
        src: ByteArray,
        srcRowOff: Int,
        srcWidth: Int,
        x0Raw: Int,
        x1Raw: Int,
        dst: ByteArray,
        dstOff: Int,
    ) {
        val maxX = srcWidth - 1
        val x0 = x0Raw.coerceIn(0, maxX)
        val x1 = x1Raw.coerceIn(0, maxX)
        val pair0 = srcRowOff + ((x0 and 1.inv()) shl 1)
        val pair1 = srcRowOff + ((x1 and 1.inv()) shl 1)
        val y0 = src[pair0 + if ((x0 and 1) != 0) 3 else 1]
        val y1 = src[pair1 + if ((x1 and 1) != 0) 3 else 1]
        val u0 = src[pair0].toInt() and 0xFF
        val v0 = src[pair0 + 2].toInt() and 0xFF
        val u1 = src[pair1].toInt() and 0xFF
        val v1 = src[pair1 + 2].toInt() and 0xFF
        dst[dstOff] = ((u0 + u1) ushr 1).toByte()
        dst[dstOff + 1] = y0
        dst[dstOff + 2] = ((v0 + v1) ushr 1).toByte()
        dst[dstOff + 3] = y1
    }

    private fun checkRegion(memory: DmaMemory, addr: Long, length: Long, write: Boolean): Boolean =
        try {
            memory.check(addr, length, write)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun readBytes(memory: DmaMemory, addr: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        memory.read(addr, ByteBuffer.wrap(out))
        return out
    }
}
