package com.github.nyabanban.fuji.emulator.components.isp

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Computes the selector data for the two 28 KiB ISP statistics SRAM banks,
 * Bank 0 `0xFFF10000..0xFFF16FFF` and Bank 1 `0xFFF17000..0xFFF1DFFF` per GFX100S 2.13 `section5.bin`
 * `0x02370170` / `0x024B9364` / `0x0251C05C`):
 *
 * - `+0x0000..+0x0FFF` (`sel 0..3`): Secondary Bayer grid (`4 × 256 × u32 LE` `[R, G1, B, G2]`).
 * - `+0x1000..+0x105F` (`sel 4..5`): Summary accumulators.
 * - `+0x1060..+0x205F` (`sel 6..9`): Primary `16×16` Bayer AE/AWB/ROB grid (`4 × 256 × u32 LE`
 *   in exact `[R, G1, B, G2]` order, OB-inclusive sums scaled to `0xFFF7005C/60` `cellW × cellH / 4`).
 * - `+0x2060..+0x219F` (`sel 10`): 10-window AE accumulators (`10 × 4 × u64 LE` `[R, G1, B, G2]`).
 * - `+0x6000..+0x6FFF` (`sel 33..36`): 256-bin LiveView Histogram (`4 × 256 × u32 LE`:
 *   `+0x6000` Luma `Y`, `+0x6400` `R`, `+0x6800` `G`, `+0x6C00` `B`).
 */
internal class IspStatsSramEngine {
    private val bank = ByteArray(BANK_SIZE)
    private val computed = MemorySegment.ofArray(bank)
    val completedBanks = IspStatsBanks()

    fun publishCompletedFrame(coreWords: LongArray) {
        completedBanks.publish(computed, coreWords, aeFramesUpdated > 0, histFramesUpdated > 0)
    }
    private val blackReferenceSums = LongArray(BLACK_REFERENCE_WINDOWS)
    private val cellSumR = LongArray(GRID_CELLS)
    private val cellSumG1 = LongArray(GRID_CELLS)
    private val cellSumB = LongArray(GRID_CELLS)
    private val cellSumG2 = LongArray(GRID_CELLS)
    private val cellCount = IntArray(GRID_CELLS)
    private var cachedGridSrcW: Int = -1
    private var cachedGridCols: Int = -1
    private var xToGxLut: IntArray = IntArray(0)

    // CDAF 13x9 multi-band focus evaluation scratch (DAT S5 0x024C1F40 / 0x024BF8B0)
    private val afMaxG = IntArray(AF_MAX_CELLS)
    private val afSumG = LongArray(AF_MAX_CELLS)
    private val afCntG = IntArray(AF_MAX_CELLS)
    private val afEdgeSum = LongArray(AF_MAX_CELLS)
    private val afBands = LongArray(AF_MAX_CELLS * 8)
    private val afWinLo = LongArray(5)
    private val afWinHi = LongArray(5)

    private val histY = IntArray(HIST_BINS)
    private val histR = IntArray(HIST_BINS)
    private val histG = IntArray(HIST_BINS)
    private val histB = IntArray(HIST_BINS)

    internal var aeFramesUpdated: Long = 0L
        private set
    internal var histFramesUpdated: Long = 0L
        private set
    internal var gpuStatsFramesUpdated: Long = 0L
        private set
    internal var lastGridCols: Int = 16
        private set
    internal var lastGridRows: Int = 16
        private set
    internal var lastCellW: Int = 0
        private set
    internal var lastCellH: Int = 0
        private set
    internal var lastMeanAeCode: Int = 0
        private set
    internal var lastHistPixels: Long = 0L
        private set
    internal var headReadCount: Long = 0L
    internal var surfaceReadCount: Long = 0L
    internal var histReadCount: Long = 0L
    private var hasActiveAfBoxNormY: Boolean = false
    private var activeAfBoxNormY0: Double = 0.30
    private var activeAfBoxNormY1: Double = 0.58

    /** Latest valid Single-Point / Zone primary AF box normalized Y-band `[ny0, ny1]`, or `null`. */
    internal fun activeAfBoxNormY(): Pair<Double, Double>? =
        if (hasActiveAfBoxNormY) (activeAfBoxNormY0 to activeAfBoxNormY1) else null

    @Volatile
    private var nativeShadow: MemorySegment? = null

    fun bindNativeShadow(seg: MemorySegment?) {
        nativeShadow = seg
        if (seg != null && seg.byteSize() >= BANK_SIZE.toLong()) {
            MemorySegment.copy(bank, 0, seg, ValueLayout.JAVA_BYTE, 0L, BANK_SIZE)
        }
    }

    private fun syncShadowSlice(offset: Int, length: Int) {
        val seg = nativeShadow ?: return
        if (seg.byteSize() >= (offset + length).toLong()) {
            MemorySegment.copy(bank, offset, seg, ValueLayout.JAVA_BYTE, offset.toLong(), length)
        }
    }

    /** Four Bayer sums per shielded window, in the sensor's 12-bit ADU domain (S5 0x02371780). */
    fun updateBlackReferenceWindow(window: Int, xRegister: Long, yRegister: Long, blackLevel16: Int) {
        // S4 0x011BAD00..0x011BAD3C programs the dimensions minus one. S5 0x02371864..0x02371898
        // divides the window area by four for the live Bayer readout before normalizing each sum.
        val width = (xRegister and 0x3F) + 1L
        val height = (yRegister and 0xFFF) + 1L
        // The CMOS port and its Bayer samples use 16-bit codes. The OB accumulator consumes bits 15:4.
        val sum = (width * height / 4L) * (blackLevel16 ushr 4)
        if (blackReferenceSums[window] == sum) return
        blackReferenceSums[window] = sum
        val offset = SEL16_OFF + window * BLACK_REFERENCE_WINDOW_BYTES
        for (channel in 0 until 4) putU64(offset + channel * Long.SIZE_BYTES, sum)
        syncShadowSlice(offset, BLACK_REFERENCE_WINDOW_BYTES)
    }

    fun clear() {
        bank.fill(0)
        completedBanks.restore(null)
        blackReferenceSums.fill(0L)
        aeFramesUpdated = 0L
        histFramesUpdated = 0L
        gpuStatsFramesUpdated = 0L
        lastMeanAeCode = 0
        lastHistPixels = 0L
        headReadCount = 0L
        surfaceReadCount = 0L
        histReadCount = 0L
        hasActiveAfBoxNormY = false
        activeAfBoxNormY0 = 0.30
        activeAfBoxNormY1 = 0.58
        nativeShadow?.let { seg ->
            if (seg.byteSize() >= BANK_SIZE.toLong()) seg.fill(0.toByte())
        }
    }

    /**
     * Populates `sel 0..3`, `sel 6..9`, and `sel 10` directly from the exposed CMOS Bayer buffer.
     *
     * @param raw Bayer pixel bytes (`RGGB` order: row even = R, G1; row odd = G2, B)
     * @param srcW Source sensor width in pixels (even)
     * @param srcH Source sensor height in pixels (even)
     * @param is16Bit True for 16-bit LE (12-bit left-aligned `>> 4`), false for 8-bit
     * @param reg5c Core `0xFFF7005C` (`[27:16]=x0, [15:12]=cols-1, [8:0]=cellW-1`)
     * @param reg60 Core `0xFFF70060` (`[27:16]=y0, [15:12]=rows-1, [8:0]=cellH-1`)
     * @param reg68 Secondary grid `0xFFF70068`
     * @param reg6c Secondary grid `0xFFF7006C`
     * @param obR Optical black offset per channel (14-bit domain; added before summing so
     *            firmware's `max(0, raw_sum - N_ch * OB_ch)` at `S5 0x024B9364` recovers true linear signal)
     */
    fun updateAeFromBayer(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        reg5c: Long,
        reg60: Long,
        reg68: Long = 0L,
        reg6c: Long = 0L,
        obR: Int = DEFAULT_OB_14BIT,
        obG1: Int = DEFAULT_OB_14BIT,
        obB: Int = DEFAULT_OB_14BIT,
        obG2: Int = DEFAULT_OB_14BIT,
        reg124: Long = 0L,
        reg128: Long = 0L,
        reg12c: Long = 0L,
        reg134: Long = 0L,
        reg138: Long = 0L,
        reg13c: Long = 0L,
        reg1fc: Long = 0L,
        crop6e0c: Long = 0L,
        crop6e10: Long = 0L,
        crop6e14: Long = 0L,
        crop6e18: Long = 0L,
        viewX: Int = 0,
        viewY: Int = 0,
        viewW: Int = srcW,
        viewH: Int = srcH,
    ) {
        if (srcW < 2 || srcH < 2) return
        val activeX0 = viewX.coerceIn(0, (srcW - 2).coerceAtLeast(0)) and 1.inv()
        val activeY0 = viewY.coerceIn(0, (srcH - 2).coerceAtLeast(0)) and 1.inv()
        val activeW = viewW.coerceIn(2, srcW - activeX0) and 1.inv()
        val activeH = viewH.coerceIn(2, srcH - activeY0) and 1.inv()
        val activeEndX = activeX0 + activeW
        val activeEndY = activeY0 + activeH

        // Primary AE/AWB/ROB grid (sel 6..9, DAT S5 0x024B9434: `add r4, r4, #1024`) is ALWAYS
        // 16x16 (256 cells). In Movie mode (MDSEL 0x92), firmware writes 0xFFF7005C=0x0CF61EF and
        // 0xFFF70060=0x04E6089 ([15:12]=6); treating [15:12]+1=7 as the primary grid dimension
        // previously left cells 49..255 zeroed, creating a -2.39 EV dark bias that drove Movie AE
        // to ISO 12800 all-white blowout. Always populate all 16x16=256 cells scaled by cellW*cellH/4.
        val cols = 16
        val rows = 16
        val rawCellW = ((reg5c and 0x1ffL).toInt() + 1)
        val rawCellH = ((reg60 and 0x1ffL).toInt() + 1)
        val hwCellPixels = if (reg5c != 0L && reg60 != 0L && rawCellW >= 2 && rawCellH >= 2) {
            rawCellW.toLong() * rawCellH.toLong()
        } else {
            (srcW / cols).coerceAtLeast(2).toLong() * (srcH / rows).coerceAtLeast(2).toLong()
        }
        val nChPerCell = (hwCellPixels / 4L).coerceAtLeast(1L)
        lastGridCols = cols
        lastGridRows = rows
        lastCellW = if (reg5c != 0L) rawCellW else (srcW / cols)
        lastCellH = if (reg60 != 0L) rawCellH else (srcH / rows)

        cellSumR.fill(0L)
        cellSumG1.fill(0L)
        cellSumB.fill(0L)
        cellSumG2.fill(0L)
        cellCount.fill(0)

        val xToGx = ensureXToGxLut(activeW, cols)

        // Subsample every 2x2 Bayer quad across the active sensor viewport [activeX0..activeEndX) x [activeY0..activeEndY)
        val stepQuad = if (activeW >= 512 && activeH >= 256) 2 else 1
        val stepPx = stepQuad * 2
        if (is16Bit) {
            var y = activeY0
            while (y + 1 < activeEndY) {
                val gy = (((y - activeY0).toLong() * rows) / activeH).toInt().coerceIn(0, rows - 1)
                val rowEven = y * srcW
                val rowOdd = (y + 1) * srcW
                val rowBase = gy * cols
                var x = activeX0
                while (x + 1 < activeEndX) {
                    val idx = rowBase + xToGx[x - activeX0]
                    val pEven = (rowEven + x) shl 1
                    val pOdd = (rowOdd + x) shl 1
                    // Real-silicon GFX100S AE stats grid (sel 0..3 / sel 6..9 / sel 10) accumulates 10-bit
                    // linear Bayer codes (0..1023) so 18% middle gray (~168..184 ADU) aligns with firmware
                    // APEX target Y_target ~= 168 at 0.0 EV while LiveView ISP renders natural mid-gray Y~120..135.
                    val r14 = ((raw[pEven].toInt() and 0xff) or ((raw[pEven + 1].toInt() and 0xff) shl 8)) ushr 6
                    val g114 = ((raw[pEven + 2].toInt() and 0xff) or ((raw[pEven + 3].toInt() and 0xff) shl 8)) ushr 6
                    val g214 = ((raw[pOdd].toInt() and 0xff) or ((raw[pOdd + 1].toInt() and 0xff) shl 8)) ushr 6
                    val b14 = ((raw[pOdd + 2].toInt() and 0xff) or ((raw[pOdd + 3].toInt() and 0xff) shl 8)) ushr 6
                    cellSumR[idx] += r14.toLong()
                    cellSumG1[idx] += g114.toLong()
                    cellSumG2[idx] += g214.toLong()
                    cellSumB[idx] += b14.toLong()
                    cellCount[idx]++
                    x += stepPx
                }
                y += stepPx
            }
        } else {
            var y = activeY0
            while (y + 1 < activeEndY) {
                val gy = (((y - activeY0).toLong() * rows) / activeH).toInt().coerceIn(0, rows - 1)
                val rowEven = y * srcW
                val rowOdd = (y + 1) * srcW
                val rowBase = gy * cols
                var x = activeX0
                while (x + 1 < activeEndX) {
                    val idx = rowBase + xToGx[x - activeX0]
                    val r14 = (raw[rowEven + x].toInt() and 0xff) shl 2
                    val g114 = (raw[rowEven + x + 1].toInt() and 0xff) shl 2
                    val g214 = (raw[rowOdd + x].toInt() and 0xff) shl 2
                    val b14 = (raw[rowOdd + x + 1].toInt() and 0xff) shl 2
                    cellSumR[idx] += r14.toLong()
                    cellSumG1[idx] += g114.toLong()
                    cellSumG2[idx] += g214.toLong()
                    cellSumB[idx] += b14.toLong()
                    cellCount[idx]++
                    x += stepPx
                }
                y += stepPx
            }
        }

        // Write primary 16x16 grid (sel 6..9 at +0x1060..+0x205F) & mirror to secondary grid (sel 0..3 at +0x0000..+0x0FFF)
        val totalCells = rows * cols
        var totalMeanAccum = 0L
        var sumAllR = 0L
        var sumAllG1 = 0L
        var sumAllB = 0L
        var sumAllG2 = 0L
        var sumCenterR = 0L
        var sumCenterG1 = 0L
        var sumCenterB = 0L
        var sumCenterG2 = 0L
        var centerCells = 0

        // Secondary grid scaling (sel 0..3)
        val secCellW = ((reg68 and 0x1ffL).toInt() + 1)
        val secCellH = ((reg6c and 0x1ffL).toInt() + 1)
        val secNCh = if (reg68 != 0L && reg6c != 0L && secCellW >= 2 && secCellH >= 2) {
            ((secCellW.toLong() * secCellH.toLong()) / 4L).coerceAtLeast(1L)
        } else {
            nChPerCell
        }

        for (cell in 0 until GRID_CELLS) {
            val cnt = cellCount[cell]
            val meanR = if (cnt > 0) cellSumR[cell].toDouble() / cnt else 0.0
            val meanG1 = if (cnt > 0) cellSumG1[cell].toDouble() / cnt else 0.0
            val meanB = if (cnt > 0) cellSumB[cell].toDouble() / cnt else 0.0
            val meanG2 = if (cnt > 0) cellSumG2[cell].toDouble() / cnt else 0.0

            val rSum = ((meanR + obR) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val g1Sum = ((meanG1 + obG1) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val bSum = ((meanB + obB) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val g2Sum = ((meanG2 + obG2) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)

            val off4 = cell shl 2
            putU32(SEL6_R_OFF + off4, rSum)
            putU32(SEL7_G1_OFF + off4, g1Sum)
            putU32(SEL8_B_OFF + off4, bSum)
            putU32(SEL9_G2_OFF + off4, g2Sum)

            // Secondary grid sel 0..3 (+0x0000..+0x0FFF)
            val srSum = ((meanR + obR) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sg1Sum = ((meanG1 + obG1) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sbSum = ((meanB + obB) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sg2Sum = ((meanG2 + obG2) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            putU32(SEL0_R_OFF + off4, srSum)
            putU32(SEL1_G1_OFF + off4, sg1Sum)
            putU32(SEL2_B_OFF + off4, sbSum)
            putU32(SEL3_G2_OFF + off4, sg2Sum)

            if (cell < totalCells) {
                totalMeanAccum += ((meanR + meanG1 + meanB + meanG2) * 0.25).roundToLong()
                sumAllR += rSum
                sumAllG1 += g1Sum
                sumAllB += bSum
                sumAllG2 += g2Sum
                val r = cell / cols
                val c = cell % cols
                if (r in (rows / 4)..(rows - 1 - rows / 4) && c in (cols / 4)..(cols - 1 - cols / 4)) {
                    sumCenterR += rSum
                    sumCenterG1 += g1Sum
                    sumCenterB += bSum
                    sumCenterG2 += g2Sum
                    centerCells++
                }
            }
        }

        // sel 10: 10 multi-window AE accumulators (10 × 4 × u64 LE at +0x2060..+0x219F)
        val cR = if (centerCells > 0) sumCenterR else sumAllR
        val cG1 = if (centerCells > 0) sumCenterG1 else sumAllG1
        val cB = if (centerCells > 0) sumCenterB else sumAllB
        val cG2 = if (centerCells > 0) sumCenterG2 else sumAllG2
        for (win in 0 until 10) {
            val base = SEL10_OFF + win * 32
            val isFull = (win == 0)
            putU64(base, if (isFull) sumAllR else cR)
            putU64(base + 8, if (isFull) sumAllG1 else cG1)
            putU64(base + 16, if (isFull) sumAllB else cB)
            putU64(base + 24, if (isFull) sumAllG2 else cG2)
        }

        // CDAF multi-band focus contrast stats (sel 4..5, sel 14..15, sel 32; DAT S5 0x024C1F40..0x024C2488,
        // S4 0x011BA830..0x011BA8EC 0xFFF70124/128/12C hardware AF grid ROI, S4 0x011E39C8/0x011E3AD4
        // 0xFFF6E00C/10 & 0xFFF6E014/18 CCDIF active crop, S4 0x011B8680 0xFFF701FC stats input frame size).
        updateCdafFromBayer(
            raw = raw,
            srcW = srcW,
            srcH = srcH,
            is16Bit = is16Bit,
            obG = obG1,
            reg124 = reg124,
            reg128 = reg128,
            reg12c = reg12c,
            reg134 = reg134,
            reg138 = reg138,
            reg13c = reg13c,
            reg1fc = reg1fc,
            crop6e0c = crop6e0c,
            crop6e10 = crop6e10,
            crop6e14 = crop6e14,
            crop6e18 = crop6e18,
            viewX = activeX0,
            viewY = activeY0,
            viewW = activeW,
            viewH = activeH,
        )

        lastMeanAeCode = if (totalCells > 0) (totalMeanAccum / totalCells).toInt() else 0
        aeFramesUpdated++
        syncShadowSlice(0, SEL32_OFF + SEL32_SIZE)
    }

    private fun ensureXToGxLut(srcW: Int, cols: Int): IntArray {
        if (cachedGridSrcW == srcW && cachedGridCols == cols && xToGxLut.size >= srcW) {
            return xToGxLut
        }
        val lut = IntArray(srcW)
        val maxCol = cols - 1
        for (x in 0 until srcW) {
            lut[x] = ((x.toLong() * cols) / srcW).toInt().coerceIn(0, maxCol)
        }
        cachedGridSrcW = srcW
        cachedGridCols = cols
        xToGxLut = lut
        return lut
    }

    private fun sample14Signal(raw: ByteArray, idx: Int, is16Bit: Boolean): Int =
        if (is16Bit) (readU16(raw, idx) ushr 2) else ((raw[idx].toInt() and 0xff) shl 6)

    private fun updateCdafFromBayer(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        obG: Int,
        reg124: Long,
        reg128: Long,
        reg12c: Long,
        reg134: Long,
        reg138: Long,
        reg13c: Long,
        reg1fc: Long,
        crop6e0c: Long,
        crop6e10: Long,
        crop6e14: Long,
        crop6e18: Long,
        viewX: Int = 0,
        viewY: Int = 0,
        viewW: Int = srcW,
        viewH: Int = srcH,
    ) {
        afMaxG.fill(0)
        afSumG.fill(0L)
        afCntG.fill(0)
        afEdgeSum.fill(0L)
        afBands.fill(0L)

        // Decode hardware-programmed SPX4 AF ROI grid (DAT S4 0x011BA830 0xFFF70124/128/12C;
        // fallback 0x011BA300 0xFFF70134/138/13C; default full-frame 13x9 when unprogrammed).
        val usePrimaryAf = (reg124 != 0L && reg128 != 0L)
        val useSecondaryAf = !usePrimaryAf && (reg134 != 0L && reg138 != 0L)
        val afCols: Int
        val afRows: Int
        val afX0: Int
        val afY0: Int
        val afCellW: Int
        val afCellH: Int
        val afGapX: Int
        val afGapY: Int
        if (usePrimaryAf) {
            afX0 = ((reg124 ushr 16) and 0xFFFL).toInt()
            afCols = (((reg124 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_COLS)
            afCellW = ((reg124 and 0x1FFL).toInt() + 1).coerceAtLeast(2)
            afY0 = ((reg128 ushr 16) and 0xFFFL).toInt()
            afRows = (((reg128 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_ROWS)
            afCellH = ((reg128 and 0x1FFL).toInt() + 1).coerceAtLeast(2)
            afGapX = ((reg12c ushr 16) and 0x1FFL).toInt()
            afGapY = (reg12c and 0x1FFL).toInt()
        } else if (useSecondaryAf) {
            afX0 = ((reg134 ushr 16) and 0xFFFL).toInt()
            afCols = (((reg134 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_COLS)
            afCellW = ((reg134 and 0xFFL).toInt() + 1).coerceAtLeast(2)
            afY0 = ((reg138 ushr 16) and 0xFFFL).toInt()
            afRows = (((reg138 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_ROWS)
            afCellH = ((reg138 and 0xFFL).toInt() + 1).coerceAtLeast(2)
            afGapX = ((reg13c ushr 16) and 0xFFL).toInt()
            afGapY = (reg13c and 0xFFL).toInt()
        } else {
            afX0 = 0
            afY0 = 0
            afCols = AF_MAX_COLS
            afRows = AF_MAX_ROWS
            afCellW = (srcW / afCols).coerceAtLeast(2)
            afCellH = (srcH / afRows).coerceAtLeast(2)
            afGapX = 0
            afGapY = 0
        }

        val afEndX = afX0 + afCols * afCellW + (afCols - 1) * afGapX
        val afEndY = afY0 + afRows * afCellH + (afRows - 1) * afGapY

        // Resolve the SPX4 hardware input active coordinate space enclosing this AF ROI:
        // 1) 0xFFF701FC ([13:0]+1 x [29:16]+1), 2) CCDIF 3A crop 0xFFF6E014/18 ([12:0] x [14:0]),
        // 3) CCDIF main crop 0xFFF6E00C/10 ([14:0] x [14:0]), 4) full-frame 16x16 0xFFF70134/138.
        val c1W = (crop6e14 and 0x1FFFL).toInt()
        val c1H = (crop6e18 and 0x7FFFL).toInt()
        val c0W = (crop6e0c and 0x7FFFL).toInt()
        val c0H = (crop6e10 and 0x7FFFL).toInt()
        val r1fcW = if (reg1fc != 0L) ((reg1fc and 0x3FFFL).toInt() + 1) else 0
        val r1fcH = if (reg1fc != 0L) (((reg1fc ushr 16) and 0x3FFFL).toInt() + 1) else 0
        val g134Cols = if (reg134 != 0L) (((reg134 ushr 8) and 0xFL).toInt() + 1) else 0
        val g138Rows = if (reg138 != 0L) (((reg138 ushr 8) and 0xFL).toInt() + 1) else 0
        val g134OriginX = ((reg134 ushr 16) and 0xFFFL).toInt()
        val g138OriginY = ((reg138 ushr 16) and 0xFFFL).toInt()
        val g134W = if (g134Cols == 16) g134Cols * ((reg134 and 0xFFL).toInt() + 1) + 15 * (((reg13c ushr 16) and 0xFFL).toInt()) else 0
        val g138H = if (g138Rows == 16) g138Rows * ((reg138 and 0xFFL).toInt() + 1) + 15 * ((reg13c and 0xFFL).toInt()) else 0

        val hwOriginX: Int
        val hwOriginY: Int
        val hwFullW: Int
        val hwFullH: Int
        when {
            r1fcW >= afEndX && r1fcH >= afEndY && r1fcW >= 2 && r1fcH >= 2 -> {
                hwOriginX = 0; hwOriginY = 0; hwFullW = r1fcW; hwFullH = r1fcH
            }
            c1W >= afEndX && c1H >= afEndY && c1W >= 2 && c1H >= 2 -> {
                hwOriginX = 0; hwOriginY = 0; hwFullW = c1W; hwFullH = c1H
            }
            c0W >= afEndX && c0H >= afEndY && c0W >= 2 && c0H >= 2 -> {
                hwOriginX = 0; hwOriginY = 0; hwFullW = c0W; hwFullH = c0H
            }
            g134W >= 2 && g138H >= 2 && (g134OriginX + g134W) >= afEndX && (g138OriginY + g138H) >= afEndY -> {
                hwOriginX = g134OriginX; hwOriginY = g138OriginY; hwFullW = g134W; hwFullH = g138H
            }
            else -> {
                hwOriginX = 0; hwOriginY = 0
                hwFullW = maxOf(srcW, afEndX).coerceAtLeast(2)
                hwFullH = maxOf(srcH, afEndY).coerceAtLeast(2)
            }
        }

        val scaleW = hwFullW.coerceAtLeast(2).toDouble()
        val scaleH = hwFullH.coerceAtLeast(2).toDouble()
        if (usePrimaryAf && afCols in 1..3 && afRows in 1..3) {
            val ny0 = ((afY0 - hwOriginY).toDouble() / scaleH).coerceIn(0.0, 1.0)
            val ny1 = ((afEndY - hwOriginY).toDouble() / scaleH).coerceIn(ny0, 1.0)
            if (ny1 - ny0 >= 0.04) {
                activeAfBoxNormY0 = ny0
                activeAfBoxNormY1 = ny1
                hasActiveAfBoxNormY = true
            }
        }
        val strideX = (afCellW + afGapX).coerceAtLeast(2)
        val strideY = (afCellH + afGapY).coerceAtLeast(2)
        // Use fine 2px Bayer Gr step for small Single-Point/Zone ROIs, 4px for full 13x9 grids
        val step = if ((afCols * afRows) <= 9 || srcW < 512 || srcH < 256) 1 else 2
        val stepPx = step * 2

        val viewEndX = (viewX + viewW).coerceIn(2, srcW)
        val viewEndY = (viewY + viewH).coerceIn(2, srcH)
        for (r in 0 until afRows) {
            val hy0 = (afY0 - hwOriginY) + r * strideY
            val hy1 = hy0 + afCellH
            val py0 = (viewY + ((hy0.toDouble() / scaleH) * viewH).toInt()).coerceIn(viewY, (viewEndY - 2).coerceAtLeast(viewY)) and 1.inv()
            val py1 = (viewY + ((hy1.toDouble() / scaleH) * viewH).toInt()).coerceIn(py0 + 2, viewEndY)
            val rowBase = r * AF_MAX_COLS
            for (c in 0 until afCols) {
                val hx0 = (afX0 - hwOriginX) + c * strideX
                val hx1 = hx0 + afCellW
                val px0 = ((viewX + ((hx0.toDouble() / scaleW) * viewW).toInt()).coerceIn(viewX, (viewEndX - 2).coerceAtLeast(viewX)) and 1.inv()) + 1
                val px1 = (viewX + ((hx1.toDouble() / scaleW) * viewW).toInt()).coerceIn(px0 + 1, viewEndX)
                val afIdx = rowBase + c
                val bandBase = afIdx shl 3
                var maxG = 0
                var sumG = 0L
                var cntG = 0
                var edgeSum = 0L
                var b0 = 0L; var b1 = 0L; var b2 = 0L; var b3 = 0L
                var b4 = 0L; var b5 = 0L; var b6 = 0L; var b7 = 0L

                var y = py0
                if (is16Bit) {
                    while (y < py1) {
                        val rowOff = y * srcW
                        val hasD1 = y + 2 < srcH
                        val hasD2 = hasD1 && (y + 4 < srcH)
                        val hasD4 = hasD2 && (y + 8 < srcH)
                        val rowOff2 = if (hasD1) (y + 2) * srcW else 0
                        val rowOff4 = if (hasD2) (y + 4) * srcW else 0
                        val rowOff8 = if (hasD4) (y + 8) * srcW else 0
                        var x = px0
                        while (x < px1) {
                            val sig14 = readU16(raw, rowOff + x) ushr 2
                            val v14Ob = (sig14 + obG).coerceAtMost(0xFFFF)
                            if (v14Ob > maxG) maxG = v14Ob
                            sumG += v14Ob.toLong()
                            cntG++

                            if (x + 2 < srcW) {
                                val gR1 = readU16(raw, rowOff + x + 2) ushr 2
                                val dH1 = abs(gR1 - sig14).toLong()
                                val eH1 = (dH1 * dH1) ushr 10
                                b0 += eH1
                                edgeSum += eH1
                                if (x + 4 < srcW) {
                                    val gR2 = readU16(raw, rowOff + x + 4) ushr 2
                                    val dH2 = abs(gR2 - sig14).toLong()
                                    val lapH1 = abs((gR1 shl 1) - sig14 - gR2).toLong()
                                    b1 += (dH2 * dH2) ushr 10
                                    b2 += (lapH1 * lapH1) ushr 9
                                    if (x + 8 < srcW) {
                                        val gR4 = readU16(raw, rowOff + x + 8) ushr 2
                                        val lapH2 = abs((gR2 shl 1) - sig14 - gR4).toLong()
                                        b3 += (lapH2 * lapH2) ushr 9
                                    }
                                }
                            }
                            if (hasD1) {
                                val gD1 = readU16(raw, rowOff2 + x) ushr 2
                                val dV1 = abs(gD1 - sig14).toLong()
                                val eV1 = (dV1 * dV1) ushr 10
                                b4 += eV1
                                edgeSum += eV1
                                if (hasD2) {
                                    val gD2 = readU16(raw, rowOff4 + x) ushr 2
                                    val dV2 = abs(gD2 - sig14).toLong()
                                    val lapV1 = abs((gD1 shl 1) - sig14 - gD2).toLong()
                                    b5 += (dV2 * dV2) ushr 10
                                    b6 += (lapV1 * lapV1) ushr 9
                                    if (hasD4) {
                                        val gD4 = readU16(raw, rowOff8 + x) ushr 2
                                        val lapV2 = abs((gD2 shl 1) - sig14 - gD4).toLong()
                                        b7 += (lapV2 * lapV2) ushr 9
                                    }
                                }
                            }
                            x += stepPx
                        }
                        y += stepPx
                    }
                } else {
                    while (y < py1) {
                        val rowOff = y * srcW
                        val hasD1 = y + 2 < srcH
                        val hasD2 = hasD1 && (y + 4 < srcH)
                        val hasD4 = hasD2 && (y + 8 < srcH)
                        val rowOff2 = if (hasD1) (y + 2) * srcW else 0
                        val rowOff4 = if (hasD2) (y + 4) * srcW else 0
                        val rowOff8 = if (hasD4) (y + 8) * srcW else 0
                        var x = px0
                        while (x < px1) {
                            val sig14 = (raw[rowOff + x].toInt() and 0xff) shl 6
                            val v14Ob = (sig14 + obG).coerceAtMost(0xFFFF)
                            if (v14Ob > maxG) maxG = v14Ob
                            sumG += v14Ob.toLong()
                            cntG++

                            if (x + 2 < srcW) {
                                val gR1 = (raw[rowOff + x + 2].toInt() and 0xff) shl 6
                                val dH1 = abs(gR1 - sig14).toLong()
                                val eH1 = (dH1 * dH1) ushr 10
                                b0 += eH1
                                edgeSum += eH1
                                if (x + 4 < srcW) {
                                    val gR2 = (raw[rowOff + x + 4].toInt() and 0xff) shl 6
                                    val dH2 = abs(gR2 - sig14).toLong()
                                    val lapH1 = abs((gR1 shl 1) - sig14 - gR2).toLong()
                                    b1 += (dH2 * dH2) ushr 10
                                    b2 += (lapH1 * lapH1) ushr 9
                                    if (x + 8 < srcW) {
                                        val gR4 = (raw[rowOff + x + 8].toInt() and 0xff) shl 6
                                        val lapH2 = abs((gR2 shl 1) - sig14 - gR4).toLong()
                                        b3 += (lapH2 * lapH2) ushr 9
                                    }
                                }
                            }
                            if (hasD1) {
                                val gD1 = (raw[rowOff2 + x].toInt() and 0xff) shl 6
                                val dV1 = abs(gD1 - sig14).toLong()
                                val eV1 = (dV1 * dV1) ushr 10
                                b4 += eV1
                                edgeSum += eV1
                                if (hasD2) {
                                    val gD2 = (raw[rowOff4 + x].toInt() and 0xff) shl 6
                                    val dV2 = abs(gD2 - sig14).toLong()
                                    val lapV1 = abs((gD1 shl 1) - sig14 - gD2).toLong()
                                    b5 += (dV2 * dV2) ushr 10
                                    b6 += (lapV1 * lapV1) ushr 9
                                    if (hasD4) {
                                        val gD4 = (raw[rowOff8 + x].toInt() and 0xff) shl 6
                                        val lapV2 = abs((gD2 shl 1) - sig14 - gD4).toLong()
                                        b7 += (lapV2 * lapV2) ushr 9
                                    }
                                }
                            }
                            x += stepPx
                        }
                        y += stepPx
                    }
                }
                afMaxG[afIdx] = maxG
                afSumG[afIdx] = sumG
                afCntG[afIdx] = cntG
                afEdgeSum[afIdx] = edgeSum
                afBands[bandBase + 0] = b0
                afBands[bandBase + 1] = b1
                afBands[bandBase + 2] = b2
                afBands[bandBase + 3] = b3
                afBands[bandBase + 4] = b4
                afBands[bandBase + 5] = b5
                afBands[bandBase + 6] = b6
                afBands[bandBase + 7] = b7
            }
        }

        val winLo = afWinLo.also { it.fill(0L) }
        val winHi = afWinHi.also { it.fill(0L) }
        val sampleWeight = (step.toLong() * step.toLong()).coerceAtLeast(1L)

        for (r in 0 until AF_MAX_ROWS) {
            for (c in 0 until AF_MAX_COLS) {
                val afIdx = r * AF_MAX_COLS + c
                val activeCell = (r < afRows && c < afCols)
                val cnt = afCntG[afIdx].coerceAtLeast(1)
                val avgG = if (activeCell && afCntG[afIdx] > 0) (afSumG[afIdx] / cnt).toInt() else obG
                val maxG = if (activeCell) afMaxG[afIdx].coerceIn(0, 0xFFFF) else obG
                val packed14 = ((maxG and 0xFFFF).toLong() shl 16) or (avgG and 0xFFFF).toLong()
                putU32(SEL14_OFF + r * 64 + c * 4, packed14)

                val scaledEdge = if (activeCell) (afEdgeSum[afIdx] * sampleWeight).coerceIn(0L, 0x7FFF_FFFFL) else 0L
                putU32(SEL15_OFF + r * 64 + c * 4, scaledEdge)

                val bandBase = afIdx shl 3
                val cellOff32 = SEL32_OFF + r * 512 + c * 32
                var cellLo = 0L
                var cellHi = 0L
                for (b in 0..7) {
                    val scaled = if (activeCell) (afBands[bandBase + b] * sampleWeight).coerceIn(0L, 0x7FFF_FFFFL) else 0L
                    putU32(cellOff32 + b * 4, scaled)
                    // sel 4 (winLo): 1st-derivative squared Tenengrad BPF (b0, b1, b4, b5)
                    // sel 5 (winHi): 2nd-derivative squared Laplacian HPF (b2, b3, b6, b7)
                    if (b == 0 || b == 1 || b == 4 || b == 5) cellLo += scaled else cellHi += scaled
                }

                if (activeCell) {
                    winLo[0] += cellLo
                    winHi[0] += cellHi
                    val qIdx = 1 + (if (afRows > 1 && r >= afRows / 2) 2 else 0) + (if (afCols > 1 && c >= afCols / 2) 1 else 0)
                    winLo[qIdx] += cellLo
                    winHi[qIdx] += cellHi
                }
            }
        }

        // For 1x1 Single-Point AF, mirror active ROI contrast across all 5 summary windows so every selector index sees it
        if (afCols == 1 && afRows == 1) {
            for (w in 1 until 5) {
                winLo[w] = winLo[0]
                winHi[w] = winHi[0]
            }
        }
        for (w in 0 until 5) {
            putU64(SEL4_OFF + w * 8, winLo[w])
            putU64(SEL5_OFF + w * 8, winHi[w])
        }
    }

    /** Clears the per-frame histogram scratch arrays before accumulating a LiveView frame. */
    fun beginHistogram() {
        histY.fill(0)
        histR.fill(0)
        histG.fill(0)
        histB.fill(0)
    }

    /** Records one demosaiced sRGB + BT.601 Y pixel into the 256-bin LiveView histogram scratch arrays. */
    fun addHistogramPixel(y: Int, r: Int, g: Int, b: Int) {
        histY[y and 0xff]++
        histR[r and 0xff]++
        histG[g and 0xff]++
        histB[b and 0xff]++
    }

    /** Commits the accumulated 4 × 256-bin histogram into Stats SRAM `+0x6000..+0x6FFF` (`sel 33..36`). */
    fun commitHistogram() {
        var total = 0L
        for (bin in 0 until HIST_BINS) {
            val off4 = bin shl 2
            val yCount = histY[bin]
            total += yCount
            putU32(SEL33_HIST_Y_OFF + off4, yCount.toLong() and 0xFFFF_FFFFL)
            putU32(SEL34_HIST_R_OFF + off4, histR[bin].toLong() and 0xFFFF_FFFFL)
            putU32(SEL35_HIST_G_OFF + off4, histG[bin].toLong() and 0xFFFF_FFFFL)
            putU32(SEL36_HIST_B_OFF + off4, histB[bin].toLong() and 0xFFFF_FFFFL)
        }
        lastHistPixels = total
        histFramesUpdated++
        syncShadowSlice(SEL33_HIST_Y_OFF, HIST_BINS * 16)
    }

    /** Commits a native-SIMD-accumulated 4 × 256-bin `[Y(256), R(256), G(256), B(256)]` histogram directly into Stats SRAM. */
    fun commitHistogramFromInterleaved(histYrgb: IntArray) {
        var total = 0L
        for (bin in 0 until HIST_BINS) {
            val off4 = bin shl 2
            val yCount = histYrgb[bin]
            total += yCount
            putU32(SEL33_HIST_Y_OFF + off4, yCount.toLong() and 0xFFFF_FFFFL)
            putU32(SEL34_HIST_R_OFF + off4, histYrgb[256 + bin].toLong() and 0xFFFF_FFFFL)
            putU32(SEL35_HIST_G_OFF + off4, histYrgb[512 + bin].toLong() and 0xFFFF_FFFFL)
            putU32(SEL36_HIST_B_OFF + off4, histYrgb[768 + bin].toLong() and 0xFFFF_FFFFL)
        }
        lastHistPixels = total
        histFramesUpdated++
        syncShadowSlice(SEL33_HIST_Y_OFF, HIST_BINS * 16)
    }

    /**
     * Ingests a compact 9,280-byte GPU-reduced ISP statistics snapshot ([GpuIspStatsSnapshot])
     * from [VulkanIspStatsBridge] and populates the exact same 28 KiB real-silicon Stats SRAM
     * banks (`sel 0..3`, `sel 4..5`, `sel 6..9`, `sel 10`, `sel 14..15`, `sel 32`, and optionally
     * `sel 33..36` 4x256-bin histograms) with bit-exact AE/AWB parity against [updateAeFromBayer].
     */
    fun ingestGpuStats(
        stats: GpuIspStatsSnapshot,
        srcW: Int,
        srcH: Int,
        reg5c: Long,
        reg60: Long,
        reg68: Long = 0L,
        reg6c: Long = 0L,
        obR: Int = DEFAULT_OB_14BIT,
        obG1: Int = DEFAULT_OB_14BIT,
        obB: Int = DEFAULT_OB_14BIT,
        obG2: Int = DEFAULT_OB_14BIT,
        reg124: Long = 0L,
        reg128: Long = 0L,
        reg134: Long = 0L,
        reg138: Long = 0L,
        commitHist: Boolean = true,
    ) {
        // Primary AE/AWB/ROB grid (sel 6..9, DAT S5 0x024B9434) is always 16x16 (256 cells),
        // even in Movie mode when 0xFFF7005C/60 carry [15:12] != 0 (e.g. 0x0CF61EF / 0x04E6089).
        val cols = stats.gridCols.coerceIn(1, 16)
        val rows = stats.gridRows.coerceIn(1, 16)
        val rawCellW = ((reg5c and 0x1ffL).toInt() + 1)
        val rawCellH = ((reg60 and 0x1ffL).toInt() + 1)
        val hwCellPixels = if (reg5c != 0L && reg60 != 0L && rawCellW >= 2 && rawCellH >= 2) {
            rawCellW.toLong() * rawCellH.toLong()
        } else {
            (srcW / cols).coerceAtLeast(2).toLong() * (srcH / rows).coerceAtLeast(2).toLong()
        }
        val nChPerCell = (hwCellPixels / 4L).coerceAtLeast(1L)
        lastGridCols = cols
        lastGridRows = rows
        lastCellW = if (reg5c != 0L) rawCellW else (srcW / cols)
        lastCellH = if (reg60 != 0L) rawCellH else (srcH / rows)

        val secCellW = ((reg68 and 0x1ffL).toInt() + 1)
        val secCellH = ((reg6c and 0x1ffL).toInt() + 1)
        val secNCh = if (reg68 != 0L && reg6c != 0L && secCellW >= 2 && secCellH >= 2) {
            ((secCellW.toLong() * secCellH.toLong()) / 4L).coerceAtLeast(1L)
        } else {
            nChPerCell
        }

        val totalCells = rows * cols
        var totalMeanAccum = 0L
        var sumAllR = 0L
        var sumAllG1 = 0L
        var sumAllB = 0L
        var sumAllG2 = 0L
        var sumCenterR = 0L
        var sumCenterG1 = 0L
        var sumCenterB = 0L
        var sumCenterG2 = 0L
        var centerCells = 0

        for (cell in 0 until GRID_CELLS) {
            val cnt = stats.aeCount[cell].toLong() and 0xFFFF_FFFFL
            val meanR = if (cnt > 0L) (stats.aeSumR[cell].toLong() and 0xFFFF_FFFFL).toDouble() / cnt else 0.0
            val meanG1 = if (cnt > 0L) (stats.aeSumG1[cell].toLong() and 0xFFFF_FFFFL).toDouble() / cnt else 0.0
            val meanB = if (cnt > 0L) (stats.aeSumB[cell].toLong() and 0xFFFF_FFFFL).toDouble() / cnt else 0.0
            val meanG2 = if (cnt > 0L) (stats.aeSumG2[cell].toLong() and 0xFFFF_FFFFL).toDouble() / cnt else 0.0

            val rSum = ((meanR + obR) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val g1Sum = ((meanG1 + obG1) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val bSum = ((meanB + obB) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val g2Sum = ((meanG2 + obG2) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)

            val off4 = cell shl 2
            putU32(SEL6_R_OFF + off4, rSum)
            putU32(SEL7_G1_OFF + off4, g1Sum)
            putU32(SEL8_B_OFF + off4, bSum)
            putU32(SEL9_G2_OFF + off4, g2Sum)

            val srSum = ((meanR + obR) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sg1Sum = ((meanG1 + obG1) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sbSum = ((meanB + obB) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            val sg2Sum = ((meanG2 + obG2) * secNCh).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
            putU32(SEL0_R_OFF + off4, srSum)
            putU32(SEL1_G1_OFF + off4, sg1Sum)
            putU32(SEL2_B_OFF + off4, sbSum)
            putU32(SEL3_G2_OFF + off4, sg2Sum)

            if (cell < totalCells) {
                totalMeanAccum += ((meanR + meanG1 + meanB + meanG2) * 0.25).roundToLong()
                sumAllR += rSum
                sumAllG1 += g1Sum
                sumAllB += bSum
                sumAllG2 += g2Sum
                val r = cell / cols
                val c = cell % cols
                if (r in (rows / 4)..(rows - 1 - rows / 4) && c in (cols / 4)..(cols - 1 - cols / 4)) {
                    sumCenterR += rSum
                    sumCenterG1 += g1Sum
                    sumCenterB += bSum
                    sumCenterG2 += g2Sum
                    centerCells++
                }
            }
        }

        val cR = if (centerCells > 0) sumCenterR else sumAllR
        val cG1 = if (centerCells > 0) sumCenterG1 else sumAllG1
        val cB = if (centerCells > 0) sumCenterB else sumAllB
        val cG2 = if (centerCells > 0) sumCenterG2 else sumAllG2
        for (win in 0 until 10) {
            val base = SEL10_OFF + win * 32
            val isFull = (win == 0)
            putU64(base, if (isFull) sumAllR else cR)
            putU64(base + 8, if (isFull) sumAllG1 else cG1)
            putU64(base + 16, if (isFull) sumAllB else cB)
            putU64(base + 24, if (isFull) sumAllG2 else cG2)
        }

        // Populate CDAF SRAM (sel 4..5, sel 14..15, sel 32) from GPU ROI Tenengrad + Laplacian accumulators
        val usePrimaryAf = (reg124 != 0L && reg128 != 0L)
        val useSecondaryAf = !usePrimaryAf && (reg134 != 0L && reg138 != 0L)
        val afCols = when {
            usePrimaryAf -> (((reg124 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_COLS)
            useSecondaryAf -> (((reg134 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_COLS)
            else -> AF_MAX_COLS
        }
        val afRows = when {
            usePrimaryAf -> (((reg128 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_ROWS)
            useSecondaryAf -> (((reg138 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, AF_MAX_ROWS)
            else -> AF_MAX_ROWS
        }
        val activeAfCells = (afRows * afCols).coerceAtLeast(1)
        val sampleWeight = (stats.stepQuad.toLong() * stats.stepQuad.toLong()).coerceAtLeast(1L)
        val scaledH1 = stats.cdafHorizTenengrad * sampleWeight
        val scaledLapH = stats.cdafHorizLaplacian * sampleWeight
        val scaledV1 = stats.cdafVertTenengrad * sampleWeight
        val scaledLapV = stats.cdafVertLaplacian * sampleWeight
        val perCellH1 = (scaledH1 / activeAfCells).coerceIn(0L, 0x7FFF_FFFFL)
        val perCellLapH = (scaledLapH / activeAfCells).coerceIn(0L, 0x7FFF_FFFFL)
        val perCellV1 = (scaledV1 / activeAfCells).coerceIn(0L, 0x7FFF_FFFFL)
        val perCellLapV = (scaledLapV / activeAfCells).coerceIn(0L, 0x7FFF_FFFFL)
        val perCellEdge = ((scaledH1 + scaledV1) / activeAfCells).coerceIn(0L, 0x7FFF_FFFFL)

        val avgG14Ob = if (stats.cdafRoiCount > 0L) {
            (((stats.cdafRoiSumG10 / stats.cdafRoiCount).toInt() shl 4) + obG1).coerceIn(0, 0xFFFF)
        } else {
            obG1.coerceIn(0, 0xFFFF)
        }
        val maxG14Ob = if (stats.cdafRoiCount > 0L) {
            (stats.cdafRoiMaxG14 + obG1).coerceIn(0, 0xFFFF)
        } else {
            obG1.coerceIn(0, 0xFFFF)
        }

        val winLo = afWinLo.also { it.fill(0L) }
        val winHi = afWinHi.also { it.fill(0L) }
        for (r in 0 until AF_MAX_ROWS) {
            for (c in 0 until AF_MAX_COLS) {
                val activeCell = (r < afRows && c < afCols)
                val packed14 = if (activeCell) {
                    ((maxG14Ob and 0xFFFF).toLong() shl 16) or (avgG14Ob and 0xFFFF).toLong()
                } else {
                    ((obG1 and 0xFFFF).toLong() shl 16) or (obG1 and 0xFFFF).toLong()
                }
                putU32(SEL14_OFF + r * 64 + c * 4, packed14)
                putU32(SEL15_OFF + r * 64 + c * 4, if (activeCell) perCellEdge else 0L)

                val cellOff32 = SEL32_OFF + r * 512 + c * 32
                val b0 = if (activeCell) perCellH1 else 0L
                val b2 = if (activeCell) perCellLapH else 0L
                val b4 = if (activeCell) perCellV1 else 0L
                val b6 = if (activeCell) perCellLapV else 0L
                putU32(cellOff32 + 0, b0)
                putU32(cellOff32 + 4, 0L)
                putU32(cellOff32 + 8, b2)
                putU32(cellOff32 + 12, 0L)
                putU32(cellOff32 + 16, b4)
                putU32(cellOff32 + 20, 0L)
                putU32(cellOff32 + 24, b6)
                putU32(cellOff32 + 28, 0L)

                if (activeCell) {
                    val cellLo = b0 + b4
                    val cellHi = b2 + b6
                    winLo[0] += cellLo
                    winHi[0] += cellHi
                    val qIdx = 1 + (if (afRows > 1 && r >= afRows / 2) 2 else 0) + (if (afCols > 1 && c >= afCols / 2) 1 else 0)
                    winLo[qIdx] += cellLo
                    winHi[qIdx] += cellHi
                }
            }
        }
        if (afCols == 1 && afRows == 1) {
            for (w in 1 until 5) {
                winLo[w] = winLo[0]
                winHi[w] = winHi[0]
            }
        }
        for (w in 0 until 5) {
            putU64(SEL4_OFF + w * 8, winLo[w])
            putU64(SEL5_OFF + w * 8, winHi[w])
        }

        if (commitHist && stats.totalHistPixels > 0L) {
            for (bin in 0 until HIST_BINS) {
                val off4 = bin shl 2
                putU32(SEL33_HIST_Y_OFF + off4, stats.histY[bin].toLong() and 0xFFFF_FFFFL)
                putU32(SEL34_HIST_R_OFF + off4, stats.histR[bin].toLong() and 0xFFFF_FFFFL)
                putU32(SEL35_HIST_G_OFF + off4, stats.histG[bin].toLong() and 0xFFFF_FFFFL)
                putU32(SEL36_HIST_B_OFF + off4, stats.histB[bin].toLong() and 0xFFFF_FFFFL)
            }
            lastHistPixels = stats.totalHistPixels
            histFramesUpdated++
        }

        lastMeanAeCode = if (totalCells > 0) (totalMeanAccum / totalCells).toInt() else 0
        aeFramesUpdated++
        gpuStatsFramesUpdated++
        syncShadowSlice(0, BANK_SIZE)
    }

    /** Reads 1/2/4 bytes from Stats Head (`0x0000..0x105F`, `sel 0..5`). */
    fun readHead(offset: Int, size: Int, physicalBank: Int? = null): Long {
        headReadCount++
        if (physicalBank != null) return completedBanks.read(physicalBank, offset, size)
        return readLittle(offset, size)
    }

    /** Reads an aligned 32-bit word from Result Surface (`0x1060 + offset`, `sel 6..32`). */
    fun readResultSurfaceWord(surfaceOffset: Int, physicalBank: Int? = null): Long {
        surfaceReadCount++
        if (physicalBank != null) return completedBanks.read(physicalBank, RESULT_SURFACE_BASE_OFF + surfaceOffset, 4)
        return readLittle(RESULT_SURFACE_BASE_OFF + surfaceOffset, 4)
    }

    /** Reads 1/2/4 bytes from Histogram SRAM (`0x6000 + offset`, `sel 33..36`). */
    fun readHistogram(histOffset: Int, size: Int, physicalBank: Int? = null): Long {
        histReadCount++
        if (physicalBank != null) return completedBanks.read(physicalBank, SEL33_HIST_Y_OFF + histOffset, size)
        return readLittle(SEL33_HIST_Y_OFF + histOffset, size)
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "aeFramesUpdated" to aeFramesUpdated,
        "histFramesUpdated" to histFramesUpdated,
        "gpuStatsFramesUpdated" to gpuStatsFramesUpdated,
        "gridCols" to lastGridCols,
        "gridRows" to lastGridRows,
        "cellW" to lastCellW,
        "cellH" to lastCellH,
        "meanAeCode14" to lastMeanAeCode,
        "histTotalPixels" to lastHistPixels,
        "headReadCount" to headReadCount,
        "surfaceReadCount" to surfaceReadCount,
        "histReadCount" to histReadCount,
        "nativeShadowBound" to (nativeShadow != null || completedBanks.nativeBound),
    )

    private fun readU16(bytes: ByteArray, pixelIndex: Int): Int {
        val off = pixelIndex shl 1
        return (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)
    }

    private fun putU32(offset: Int, value: Long) {
        bank[offset] = (value and 0xffL).toByte()
        bank[offset + 1] = ((value ushr 8) and 0xffL).toByte()
        bank[offset + 2] = ((value ushr 16) and 0xffL).toByte()
        bank[offset + 3] = ((value ushr 24) and 0xffL).toByte()
    }

    private fun putU64(offset: Int, value: Long) {
        putU32(offset, value and 0xFFFF_FFFFL)
        putU32(offset + 4, (value ushr 32) and 0xFFFF_FFFFL)
    }

    private fun readLittle(offset: Int, size: Int): Long {
        var acc = 0L
        for (i in 0 until size) {
            acc = acc or ((bank[offset + i].toLong() and 0xffL) shl (i * 8))
        }
        return acc
    }

    companion object {
        const val BANK_SIZE = 0x7000 // 28 KiB per Stats SRAM bank (0xFFF10000..0xFFF16FFF / 0xFFF17000..0xFFF1DFFF)
        const val RESULT_SURFACE_BASE_OFF = 0x1060
        const val SEL0_R_OFF = 0x0000
        const val SEL1_G1_OFF = 0x0400
        const val SEL2_B_OFF = 0x0800
        const val SEL3_G2_OFF = 0x0C00
        const val SEL4_OFF = 0x1000
        const val SEL5_OFF = 0x1028
        const val SEL6_R_OFF = 0x1060
        const val SEL7_G1_OFF = 0x1460
        const val SEL8_B_OFF = 0x1860
        const val SEL9_G2_OFF = 0x1C60
        const val SEL10_OFF = 0x2060
        const val SEL14_OFF = 0x21A0
        const val SEL15_OFF = 0x25A0
        const val SEL16_OFF = 0x29A0
        const val BLACK_REFERENCE_WINDOWS = 3
        const val BLACK_REFERENCE_WINDOW_BYTES = 4 * Long.SIZE_BYTES
        const val SEL32_OFF = 0x4000
        const val SEL32_SIZE = 0x2000
        const val SEL33_HIST_Y_OFF = 0x6000
        const val SEL34_HIST_R_OFF = 0x6400
        const val SEL35_HIST_G_OFF = 0x6800
        const val SEL36_HIST_B_OFF = 0x6C00

        const val GRID_CELLS = 256
        const val AF_MAX_COLS = 13
        const val AF_MAX_ROWS = 9
        const val AF_MAX_CELLS = AF_MAX_COLS * AF_MAX_ROWS
        const val HIST_BINS = 256
        const val DEFAULT_OB_14BIT = 1024
    }
}
