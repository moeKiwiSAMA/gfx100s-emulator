package com.github.nyabanban.fuji.emulator.components.isp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hardware ISP Focus Peak Highlight filter (`0xFFF68900..0xFFF68950` inside `soc-isp.bank-68000`).
 *
 * Register layout verified live on GFX100S 2.13 (`GXUP0004.DAT`, `section4.bin 0x011CFC00..0x011CFE3C`,
 * `section5.bin 0x021987A8..0x0219897C`):
 * - `0xFFF6890C`: LOW-sensitivity HPF bandpass gain word (`0x000003C0` in `LOW`, `0` in `HIGH`).
 * - `0xFFF68914`: HIGH-sensitivity HPF bandpass gain word (`0x03C00000` in `HIGH`, `0` in `LOW`).
 * - `0xFFF68930`: Peaking enable/blend word (`0x00000080` when active, `0` when OFF).
 * - `0xFFF68934`: `[1:0]` strength selector (`0..2`, honored alongside `0x90C`/`0x914`).
 * - `0xFFF68950`:
 *   - `[8:0]`: 9-bit edge threshold (`0x1FF` = OFF / disabled; `0x064` = active on stock firmware).
 *   - `[23:16]`: Highlight `Cb` (`U`) unsigned `0..255` (`0x80`=WHITE, `0x55`=RED, `0xAB`=BLUE, `0x00`=YELLOW).
 *   - `[31:24]`: Highlight `Cr` (`V`) unsigned `0..255` (`0x80`=WHITE, `0xFF`=RED, `0x00`=BLUE, `0x95`=YELLOW).
 */
internal object MfAssistPeakingFilter {
    const val DISABLED_THRESHOLD = 0x1FF
    const val DISABLED_WORD_950 = 0x0000_01FFL

    private val IDX_90C = IspCoreOffsets.BANK_68000_INDEX.getValue(0x90c)
    private val IDX_914 = IspCoreOffsets.BANK_68000_INDEX.getValue(0x914)
    private val IDX_930 = IspCoreOffsets.BANK_68000_INDEX.getValue(0x930)
    private val IDX_934 = IspCoreOffsets.BANK_68000_INDEX.getValue(0x934)
    private val IDX_950 = IspCoreOffsets.BANK_68000_INDEX.getValue(0x950)

    data class PeakingConfig(
        val enabled: Boolean,
        val threshold: Int,
        val cb: Int,
        val cr: Int,
        val highSensitivity: Boolean,
        val cacheKey: Long,
    ) {
        val highlightY: Int = computeHighlightLuma(cb, cr)
        val effectiveThreshold: Int = if (highSensitivity) {
            max(16, (threshold * 3) / 5)
        } else {
            max(24, (threshold * 6) / 5)
        }
    }

    val DISABLED = PeakingConfig(
        enabled = false,
        threshold = DISABLED_THRESHOLD,
        cb = 0x80,
        cr = 0x80,
        highSensitivity = false,
        cacheKey = 0L,
    )

    fun fromWords(
        word90c: Long,
        word914: Long,
        word930: Long,
        word934: Long,
        word950: Long,
    ): PeakingConfig {
        val threshold = (word950 and 0x1FFL).toInt()
        // Both unprogrammed reset state (word950 == 0 / threshold == 0) and explicit OFF (0x1FF) are disabled.
        if (threshold !in 1 until DISABLED_THRESHOLD) return DISABLED
        val cb = ((word950 ushr 16) and 0xFFL).toInt()
        val cr = ((word950 ushr 24) and 0xFFL).toInt()
        val strengthBits = (word934 and 0x3L).toInt()
        val highSensitivity = when {
            strengthBits >= 2 -> true
            strengthBits == 1 -> false
            word914 != 0L && word90c == 0L -> true
            word90c != 0L && word914 == 0L -> false
            else -> true
        }
        var key = 1L
        key = key * 31L + threshold
        key = key * 31L + cb
        key = key * 31L + cr
        key = key * 31L + if (highSensitivity) 2L else 1L
        key = key * 31L + (word930 and 0xFFL)
        return PeakingConfig(
            enabled = true,
            threshold = threshold,
            cb = cb,
            cr = cr,
            highSensitivity = highSensitivity,
            cacheKey = key,
        )
    }

    fun fromBank68000(bank68000Words: LongArray): PeakingConfig {
        if (bank68000Words.size <= IDX_950) return DISABLED
        return fromWords(
            word90c = bank68000Words[IDX_90C],
            word914 = bank68000Words[IDX_914],
            word930 = bank68000Words[IDX_930],
            word934 = bank68000Words[IDX_934],
            word950 = bank68000Words[IDX_950],
        )
    }

    /**
     * Derives the exact in-gamut BT.601 luma `Y` that maximizes saturation for any hardware `(Cb, Cr)`
     * highlight color without clipping or washing out:
     * - `WHITE` (`Cb=0x80, Cr=0x80`) -> `Y = 250` (`#FAFAFA`)
     * - `RED` (`Cb=0x55, Cr=0xFF`) -> `Y = 76` (`#FE0000`)
     * - `BLUE` (`Cb=0xAB, Cr=0x00`) -> `Y = 179` (`#00FFFF`, Fuji electric cyan-blue peaking)
     * - `YELLOW` (`Cb=0x00, Cr=0x95`) -> `Y = 226` (`#FFFF00`)
     */
    internal fun computeHighlightLuma(cb: Int, cr: Int): Int {
        val dCb = cb - 128
        val dCr = cr - 128
        if (abs(dCb) <= 8 && abs(dCr) <= 8) return 250
        val dR = 1.402 * dCr
        val dG = -0.344136 * dCb - 0.714136 * dCr
        val dB = 1.772 * dCb
        val yMin = max(0.0, max(-dR, max(-dG, -dB)))
        val yMax = min(255.0, min(255.0 - dR, min(255.0 - dG, 255.0 - dB)))
        return ((yMin + yMax) * 0.5).roundToInt().coerceIn(48, 250)
    }

    /**
     * Applies 2D HPF edge contrast peaking directly onto a packed BT.601 `UYVY` frame (`U0 Y0 V0 Y1`).
     * Uses an unmodified luma snapshot so edge detection never cascades across stamped pixels.
     */
    fun applyPackedUyvy(
        frame: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        config: PeakingConfig,
    ): Int {
        if (!config.enabled || width < 4 || height < 3 || stride < width * 2) return 0
        val yPlane = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * stride
            val yOff = y * width
            var x = 0
            while (x + 1 < width) {
                val p = rowOff + (x shl 1)
                yPlane[yOff + x] = frame[p + 1]
                yPlane[yOff + x + 1] = frame[p + 3]
                x += 2
            }
        }

        val thr = config.effectiveThreshold
        val hiY = config.highlightY.toByte()
        val hiCb = config.cb.toByte()
        val hiCr = config.cr.toByte()
        var stampedPixels = 0

        for (y in 2 until height - 2) {
            val rowPrev2 = (y - 2) * width
            val rowPrev1 = (y - 1) * width
            val rowCurr = y * width
            val rowNext1 = (y + 1) * width
            val rowNext2 = (y + 2) * width
            val dstRow = y * stride
            var x = 0
            while (x + 1 < width) {
                val peak0 = x in 2 until (width - 2) &&
                    edgeMagnitude(yPlane, rowPrev2, rowPrev1, rowCurr, rowNext1, rowNext2, x) > thr
                val peak1 = (x + 1) in 2 until (width - 2) &&
                    edgeMagnitude(yPlane, rowPrev2, rowPrev1, rowCurr, rowNext1, rowNext2, x + 1) > thr
                if (peak0 || peak1) {
                    val p = dstRow + (x shl 1)
                    frame[p] = hiCb
                    frame[p + 2] = hiCr
                    if (peak0) {
                        frame[p + 1] = hiY
                        stampedPixels++
                    }
                    if (peak1) {
                        frame[p + 3] = hiY
                        stampedPixels++
                    }
                }
                x += 2
            }
        }
        return stampedPixels
    }

    /**
     * Applies 2D HPF edge contrast peaking onto a 2-plane semi-planar `Y + UV` frame (`NV16` or `NV12`).
     */
    fun applySemiPlanar(
        yPlaneBuf: ByteArray,
        uvPlaneBuf: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        nv12: Boolean,
        config: PeakingConfig,
    ): Int {
        if (!config.enabled || width < 6 || height < 5 || stride < width) return 0
        val yCopy = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(yPlaneBuf, y * stride, yCopy, y * width, width)
        }

        val thr = config.effectiveThreshold
        val hiY = config.highlightY.toByte()
        val hiCb = config.cb.toByte()
        val hiCr = config.cr.toByte()
        val uvHeight = if (nv12) height / 2 else height
        var stampedPixels = 0

        for (y in 2 until height - 2) {
            val rowPrev2 = (y - 2) * width
            val rowPrev1 = (y - 1) * width
            val rowCurr = y * width
            val rowNext1 = (y + 1) * width
            val rowNext2 = (y + 2) * width
            val yDstRow = y * stride
            val uvRow = if (nv12) (y ushr 1) else y
            val uvDstRow = if (uvRow < uvHeight) uvRow * stride else -1
            var x = 0
            while (x + 1 < width) {
                val peak0 = x in 2 until (width - 2) &&
                    edgeMagnitude(yCopy, rowPrev2, rowPrev1, rowCurr, rowNext1, rowNext2, x) > thr
                val peak1 = (x + 1) in 2 until (width - 2) &&
                    edgeMagnitude(yCopy, rowPrev2, rowPrev1, rowCurr, rowNext1, rowNext2, x + 1) > thr
                if (peak0 || peak1) {
                    if (peak0) {
                        yPlaneBuf[yDstRow + x] = hiY
                        stampedPixels++
                    }
                    if (peak1) {
                        yPlaneBuf[yDstRow + x + 1] = hiY
                        stampedPixels++
                    }
                    if (uvDstRow >= 0 && uvDstRow + x + 1 < uvPlaneBuf.size) {
                        uvPlaneBuf[uvDstRow + x] = hiCb
                        uvPlaneBuf[uvDstRow + x + 1] = hiCr
                    }
                }
                x += 2
            }
        }
        return stampedPixels
    }

    /**
     * 5-tap horizontal + vertical HPF edge contrast magnitude on the luma plane (`0..510`),
     * handling both 1px razor edges and 1.6x upscaled LiveView edges cleanly.
     */
    private fun edgeMagnitude(
        yPlane: ByteArray,
        rowPrev2: Int,
        rowPrev1: Int,
        rowCurr: Int,
        rowNext1: Int,
        rowNext2: Int,
        x: Int,
    ): Int {
        val gx1 = abs((yPlane[rowCurr + x + 1].toInt() and 0xFF) - (yPlane[rowCurr + x - 1].toInt() and 0xFF))
        val gx2 = abs((yPlane[rowCurr + x + 2].toInt() and 0xFF) - (yPlane[rowCurr + x - 2].toInt() and 0xFF))
        val gy1 = abs((yPlane[rowNext1 + x].toInt() and 0xFF) - (yPlane[rowPrev1 + x].toInt() and 0xFF))
        val gy2 = abs((yPlane[rowNext2 + x].toInt() and 0xFF) - (yPlane[rowPrev2 + x].toInt() and 0xFF))
        return max(gx1, gx2) + max(gy1, gy2)
    }
}
