package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SceneDepthPort
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Dedicated GFX100S 2.13 On-Sensor Phase-Detection Autofocus (PDAF) Hardware Engine
 * (`0xFFF71000..0xFFF71217`, GIC IRQ `122` level-sensitive).
 *
 * Register layout, ISR protocol, and DDR output buffers verified against `section5.bin`
 * (`0x021E7DA0..0x021E90E8`, `0x02391FC4..0x02392B00`, `0x0254650C..0x02548AB4`):
 * - `+0x000` CTRL/STATUS: bit `0` START (`1` arms correlation run; `0` stops), bit `8` Output #2
 *   L/R waveform DMA enable, bit `16` (`0x10000`) RO IDLE/READY status (polled by `0x021E8148..54`).
 * - `+0x024/+0x028`: Output #1 3-band hardware SAD curves 34-bit DDR base (32B-aligned; 16 blocks
 *   x `0x800` B = `512 x u32 LE`; lag `0` at word `92 + 164 * band` for `band in 0..2`).
 * - `+0x02C/+0x030`: Output #2 raw 1D Left/Right pupil waveforms 34-bit DDR base (32B-aligned;
 *   16 blocks x `0x1000` B = `0x800` B Left `u16 LE` + `0x800` B Right `u16 LE`).
 * - `+0x040`: `[3:0]` `(numBlocks - 1)` (`1..16` AF blocks).
 * - `+0x044..+0x0A0`: AF block ROI coordinate & phase-pixel geometry registers.
 * - `+0x1F0`: `[0]` enable, `[14:8]` band0 `maxShift`, `[22:16]` band1 `maxShift`, `[30:24]` band2 `maxShift`.
 * - `+0x200`: 16-bit W1C IRQ status (1 bit per completed AF block `0..15`; `0x021E7DDC` clears lowest
 *   set bit per IRQ until `done == numBlocks`).
 * - `+0x204`: 16-bit IRQ enable mask; `+0x208`/`+0x210`: bit `8` (`0x100`) global IRQ enable/route.
 *
 * Physical pupil-disparity synthesis translates thin-lens image-plane defocus
 * `v(d_subj) - v(d_focus)` into exact sub-pixel Left/Right phase disparity `deltaPx`, emitting
 * V-shaped SAD curves whose 3-point equiangular minimum (`S5 0x0254650C`) recovers `deltaPx` to
 * Q8 (`1/256` px) precision alongside matching shifted 1D L/R waveforms.
 */
internal class PdafHardwareEngine(
    private val clockProvider: () -> Long,
    private val sinkProvider: () -> InterruptSink?,
    private val dmaMemoryProvider: () -> DmaMemory?,
    private val lensProvider: () -> LensParameters?,
    private val depthProvider: () -> SceneDepthPort?,
    private val afRoiYProvider: () -> Pair<Double, Double>? = { null },
) {
    companion object {
        const val PORT_ID = "soc-isp.pdaf"
        const val WINDOW_SIZE = 0x218L // 0x000..0x214 inclusive (134 aligned u32 words)
        const val WORD_COUNT = (WINDOW_SIZE / 4L).toInt()
        const val IRQ_LINE = 122
        const val COMPLETION_DELAY_TICKS = 64L

        private const val OFF_CTRL = 0x000
        private const val OFF_SAD_LO = 0x024
        private const val OFF_SAD_HI = 0x028
        private const val OFF_LR_LO = 0x02C
        private const val OFF_LR_HI = 0x030
        private const val OFF_INPUT_LINE_STRIDE = 0x034
        private const val OFF_NUM_BLOCKS = 0x040
        private const val OFF_BLOCK_ROI_X_BASE = 0x044
        private const val OFF_BLOCK_ROI_SPAN = 0x084
        private const val OFF_SUB_BLOCKS = 0x09C
        private const val OFF_SEARCH_RANGE = 0x1F0
        private const val OFF_IRQ_STATUS = 0x200
        private const val OFF_IRQ_MASK = 0x204
        private const val OFF_IRQ_GLOBAL_0 = 0x208
        private const val OFF_IRQ_GLOBAL_1 = 0x210
        // IMX461 active full-sensor readout width (pixels) used as PDAF coordinate space when +0x034[31:16] is unset.
        private const val IMX461_ACTIVE_WIDTH_PX = 11648.0

        private const val CTRL_START_BIT = 1L
        private const val CTRL_LR_OUT_BIT = 0x100L
        private const val CTRL_IDLE_READY_BIT = 0x10000L
        private const val IRQ_GLOBAL_ENABLE_BIT = 0x100L

        private const val SAD_BLOCK_BYTES = 0x800
        private const val SAD_BLOCK_WORDS = SAD_BLOCK_BYTES / 4 // 512 u32 words
        private const val SAD_BAND_CENTER_BASE = 92
        private const val SAD_BAND_STRIDE_WORDS = 164
        private const val LR_BLOCK_BYTES = 0x1000
        private const val LR_HALF_BYTES = 0x800
        private const val LR_SAMPLE_COUNT = 729

        // Balanced positive brightness sums & sharp V-curve parameters satisfying S5 0x02548220..0x025484F0
        private const val NOMINAL_BAND_SUM = 0x0010_0000
        private const val NOMINAL_AUX_STAT = 0x0001_0000
        private const val SAD_BASE_FLOOR = 64.0
        private const val SAD_SLOPE_PER_PX = 32768.0

        // IMX461 live-view on-sensor PDAF defocus-to-phase sensitivity calibrated against GFX100S 2.13
        // `GXUP0004.DAT` `S5 0x025474D0..0x025475D0`:
        //   defocus_q16 = ((-phase_Q8) * K * 256) / 1000
        //   pulses_q16  = (|defocus_q16| * 1000) / c0  =>  pulses = |deltaPx| * K / c0
        // With `c0 = nm/pulse` (from lens EEPROM `0x17700`), live closed-loop measurements on GFX100S
        // 2.13 across the focus stroke yield `K ≈ 402,000 nm/px` (`0.402 mm/px`, reflecting the
        // medium-format IMX461 binned live-view phase-pixel spacing and masked-pixel pupil baseline).
        // `S5 0x025474E8` negates phase shift (`rsbs r0, r0, #0`) before computing focus-pulse delta,
        // so negative disparity drives focus encoder UP toward closer MOD subjects.
        internal const val DEFOCUS_MM_PER_PX = 0.402
        private const val PHASE_SIGN_POLARITY = -1.0

        private const val W1 = 2.0 * PI / 31.0
        private const val W2 = 2.0 * PI / 17.0
        private const val W3 = 2.0 * PI / 9.0
        private val COS_W1 = cos(W1)
        private val SIN_W1 = sin(W1)
        private val COS_W2 = cos(W2)
        private val SIN_W2 = sin(W2)
        private val COS_W3 = cos(W3)
        private val SIN_W3 = sin(W3)

        // Precomputed 729-sample Left pupil waveform bytes (1458 bytes little-endian u16) — invariant across runs!
        private val LEFT_WAVEFORM_BYTES: ByteArray = ByteArray(LR_HALF_BYTES).also { buf ->
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until LR_SAMPLE_COUNT) {
                val x = i.toDouble()
                val v = (2048.0 + 600.0 * sin(W1 * x) + 420.0 * sin(W2 * x + 0.7) + 280.0 * sin(W3 * x + 1.9))
                    .roundToInt().coerceIn(256, 3840)
                bb.putShort(i * 2, v.toShort())
            }
        }

        private val SNAPSHOT_KEYS = setOf(
            "words", "running", "pendingCompletionTick", "irqStatus", "irqAsserted",
            "startCount", "completionCount",
        )
    }

    private val words = LongArray(WORD_COUNT)
    private var running = false
    private var pendingCompletionTick: Long? = null
    private var irqStatus = 0L
    private var irqAsserted = false
    private var startCount = 0L
    private var completionCount = 0L
    private var lastNumBlocks = 0
    private var lastSadAddr = 0L
    private var lastLrAddr = 0L
    private var lastSubjectDepthM = 0.0
    private var lastFocusDistanceM = 0.0
    private var lastDefocusMm = 0.0
    private var lastDeltaPx = 0.0
    private var lastMaxShift0 = 0
    private var lastRoiNormX = 0.5

    private val sadBlockBuf = ByteArray(SAD_BLOCK_BYTES)
    private val lrBlockBuf = ByteArray(LR_BLOCK_BYTES)

    private fun effectiveIrqEnabled(): Boolean {
        val g0 = (words[OFF_IRQ_GLOBAL_0 / 4] and IRQ_GLOBAL_ENABLE_BIT) != 0L
        val g1 = (words[OFF_IRQ_GLOBAL_1 / 4] and IRQ_GLOBAL_ENABLE_BIT) != 0L
        val mask = words[OFF_IRQ_MASK / 4] and 0xFFFFL
        return (g0 || g1) && mask != 0L
    }

    private fun updateIrq() {
        val mask = words[OFF_IRQ_MASK / 4] and 0xFFFFL
        val high = effectiveIrqEnabled() && (irqStatus and mask) != 0L
        if (high != irqAsserted) {
            irqAsserted = high
            sinkProvider()?.setLine(IRQ_LINE, high)
        }
    }

    fun syncIrqLine() {
        sinkProvider()?.setLine(IRQ_LINE, irqAsserted)
    }

    fun clearIrqOnClose() {
        if (irqAsserted) {
            irqAsserted = false
            sinkProvider()?.setLine(IRQ_LINE, false)
        }
    }

    /**
     * Computes thin-lens image-plane defocus (mm) and corresponding signed Left/Right phase
     * disparity in correlation pixels (`deltaPx`).
     *
     * When `subjectDepthM < focusDistanceM` (subject closer than current lens focus), the optical
     * image forms behind the sensor (`defocusMm > 0`).
     */
    internal fun computePhysicalDisparity(
        lens: LensParameters,
        subjectDepthM: Double,
        maxShift: Int,
    ): Pair<Double, Double> {
        val fM = (lens.focalLengthMm / 1000.0).coerceIn(0.01, 2.0)
        val minValidDist = fM * 1.05
        val dFocus = lens.focusDistanceM.coerceAtLeast(minValidDist)
        val dSubj = subjectDepthM.coerceAtLeast(minValidDist)
        val vSubjMm = (fM * dSubj / (dSubj - fM)) * 1000.0
        val vFocusMm = (fM * dFocus / (dFocus - fM)) * 1000.0
        val defocusMm = vSubjMm - vFocusMm
        val rawDeltaPx = PHASE_SIGN_POLARITY * defocusMm / DEFOCUS_MM_PER_PX
        // Clamp only to physical hardware correlator ceiling (±75 px), never artificially inside a narrow
        // fine-tracking window (e.g. maxShift=4): when |deltaPx| > maxShift, the real SAD curve becomes
        // monotonic at the window edge so AF-C firmware detects out-of-range defocus and widens search.
        val deltaPx = rawDeltaPx.coerceIn(-75.0, 75.0)
        return defocusMm to deltaPx
    }

    /**
     * Decodes the hardware-programmed PDAF block horizontal origin (`+0x044..+0x060`) and span
     * (`+0x084`, DAT S5 `0x021E84DC..0x021E85C8`) into a normalized `[nx0, ny0, nx1, ny1]` ROI
     * inside `[0, 1]`, falling back to a center/tiled window when ROI coordinates are unset.
     */
    internal fun resolveBlockRoi(blockIdx: Int, numBlocks: Int): DoubleArray {
        val idx = blockIdx.coerceIn(0, 15)
        val wordIdx = (OFF_BLOCK_ROI_X_BASE / 4) + (idx ushr 1)
        val roiWord = words[wordIdx]
        val rawX = if ((idx and 1) == 0) {
            ((roiWord ushr 16) and 0x3FFFL).toInt()
        } else {
            (roiWord and 0x3FFFL).toInt()
        }
        if (rawX > 0) {
            // DAT S5 0x021E82C0..0x021E82E8: +0x034[31:16] holds input line width/stride (<= 32768).
            val rawStride = ((words[OFF_INPUT_LINE_STRIDE / 4] ushr 16) and 0xFFFFL).toInt()
            val sensorWidthPx = when {
                rawStride in 4096..14000 -> rawStride.toDouble()
                rawStride > 14000 -> (rawStride * 0.5).coerceAtLeast(4096.0)
                else -> IMX461_ACTIVE_WIDTH_PX
            }
            val spanRaw = (words[OFF_BLOCK_ROI_SPAN / 4] and 0x3FFFL).toInt()
            val blockWidthPx = if (spanRaw > 0) spanRaw.toDouble() else 196.0
            val centerX = ((rawX.toDouble() + blockWidthPx * 0.5) / sensorWidthPx).coerceIn(0.02, 0.98)
            val halfSpanX = maxOf(0.015, (blockWidthPx * 0.5) / sensorWidthPx).coerceAtMost(0.12)
            val nx0 = (centerX - halfSpanX).coerceIn(0.0, 0.97)
            val nx1 = (centerX + halfSpanX).coerceIn(nx0 + 0.02, 1.0)
            // Use the active hardware AF box vertical band from SPX4 CDAF ROI (0xFFF70124/128) when
            // available (since the 1D horizontal PDAF correlator receives pre-cropped rows), falling
            // back to the central portrait band [0.30..0.58] when unprogrammed.
            val (ny0, ny1) = afRoiYProvider()?.let { (y0, y1) ->
                val cy0 = y0.coerceIn(0.0, 0.96)
                val cy1 = y1.coerceIn(cy0 + 0.04, 1.0)
                cy0 to cy1
            } ?: (0.30 to 0.58)
            return doubleArrayOf(nx0, ny0, nx1, ny1)
        }
        // Fallback when ROI coordinate registers (+0x044..+0x060) are unwritten (e.g. unit tests)
        return if (numBlocks <= 2) {
            doubleArrayOf(0.40, 0.40, 0.60, 0.60)
        } else {
            val cols = if (numBlocks <= 4) 2 else 4
            val rows = ((numBlocks + cols - 1) / cols).coerceAtLeast(1)
            val c = blockIdx % cols
            val r = (blockIdx / cols).coerceIn(0, rows - 1)
            val spanX = 0.60 / cols
            val spanY = 0.60 / rows
            doubleArrayOf(0.20 + c * spanX, 0.20 + r * spanY, 0.20 + (c + 1) * spanX, 0.20 + (r + 1) * spanY)
        }
    }

    private fun sampleBlockDepth(blockIdx: Int, numBlocks: Int, depthPort: SceneDepthPort?, fallbackFocusM: Double): Double {
        val roi = resolveBlockRoi(blockIdx, numBlocks)
        if (blockIdx == 0) {
            lastRoiNormX = (roi[0] + roi[2]) * 0.5
        }
        if (depthPort == null) return fallbackFocusM
        return depthPort.sampleRegionDepthMeters(roi[0], roi[1], roi[2], roi[3])
    }

    private fun fillSadBlock(deltaPx: Double, maxShifts: IntArray) {
        val bb = ByteBuffer.wrap(sadBlockBuf).order(ByteOrder.LITTLE_ENDIAN)
        sadBlockBuf.fill(0)
        // +0x000 satCountL = 0, +0x004 satCountR = 0
        bb.putInt(0x000, 0)
        bb.putInt(0x004, 0)
        for (band in 0..2) {
            bb.putInt(0x008 + band * 8, NOMINAL_BAND_SUM)
            bb.putInt(0x00C + band * 8, NOMINAL_BAND_SUM)
        }
        bb.putInt(0x020, NOMINAL_AUX_STAT)
        bb.putInt(0x024, NOMINAL_AUX_STAT)

        // DAT S5 0x021E8458 / 0x02548CF8: When firmware programs band 1 at half the search range of
        // band 0 (e.g. +0x1F0 = 0x32193201 -> [50, 25, 50] or 0x04020401 -> [4, 2, 4]), band 1 is
        // the 2x-decimated coarse correlation channel whose recovered lag is shifted left by 1
        // (`lsleq r0, r0, #1` at 0x02548CF8) before cross-band consistency validation (0x02549474).
        val band1HalfRate = maxShifts[1] * 2 <= maxShifts[0] + 1
        for (band in 0..2) {
            val maxShift = maxShifts[band].coerceIn(1, 80)
            val bandDeltaPx = if (band == 1 && band1HalfRate) deltaPx * 0.5 else deltaPx
            val centerWord = SAD_BAND_CENTER_BASE + SAD_BAND_STRIDE_WORDS * band
            for (lag in -maxShift..maxShift) {
                val wordIdx = centerWord + lag
                if (wordIdx in 10 until SAD_BLOCK_WORDS) {
                    val sadVal = (SAD_BASE_FLOOR + SAD_SLOPE_PER_PX * abs(lag.toDouble() - bandDeltaPx))
                        .roundToLong()
                        .coerceIn(0L, 0x7FFF_FFFFL)
                    bb.putInt(wordIdx * 4, sadVal.toInt())
                }
            }
        }
    }

    private fun fillLrWaveformBlock(deltaPx: Double) {
        System.arraycopy(LEFT_WAVEFORM_BYTES, 0, lrBlockBuf, 0, LR_HALF_BYTES)
        var s1 = sin(-W1 * deltaPx)
        var c1 = cos(-W1 * deltaPx)
        var s2 = sin(-W2 * deltaPx + 0.7)
        var c2 = cos(-W2 * deltaPx + 0.7)
        var s3 = sin(-W3 * deltaPx + 1.9)
        var c3 = cos(-W3 * deltaPx + 1.9)
        var off = LR_HALF_BYTES
        for (i in 0 until LR_SAMPLE_COUNT) {
            val v = (2048.0 + 600.0 * s1 + 420.0 * s2 + 280.0 * s3).roundToInt().coerceIn(256, 3840)
            lrBlockBuf[off] = (v and 0xFF).toByte()
            lrBlockBuf[off + 1] = ((v ushr 8) and 0xFF).toByte()
            off += 2
            val ns1 = s1 * COS_W1 + c1 * SIN_W1
            val nc1 = c1 * COS_W1 - s1 * SIN_W1
            s1 = ns1; c1 = nc1
            val ns2 = s2 * COS_W2 + c2 * SIN_W2
            val nc2 = c2 * COS_W2 - s2 * SIN_W2
            s2 = ns2; c2 = nc2
            val ns3 = s3 * COS_W3 + c3 * SIN_W3
            val nc3 = c3 * COS_W3 - s3 * SIN_W3
            s3 = ns3; c3 = nc3
        }
        java.util.Arrays.fill(lrBlockBuf, off, LR_BLOCK_BYTES, 0.toByte())
    }

    private fun completeRun() {
        val numBlocks = ((words[OFF_NUM_BLOCKS / 4] and 0xFL).toInt() + 1).coerceIn(1, 16)
        val numSubBlocks = ((words[OFF_SUB_BLOCKS / 4] and 0x7L).toInt() + 1).coerceIn(1, 8)
        val sadAddr = ((words[OFF_SAD_HI / 4] and 3L) shl 32) or (words[OFF_SAD_LO / 4] and 0xFFFF_FFFFL)
        val lrAddr = ((words[OFF_LR_HI / 4] and 3L) shl 32) or (words[OFF_LR_LO / 4] and 0xFFFF_FFFFL)
        val lrEnabled = (words[OFF_CTRL / 4] and CTRL_LR_OUT_BIT) != 0L
        // DAT S5 0x021E8630 / 0x0239297C: +0x040[3:0] sets IRQ area count (1..16), while +0x09C[2:0] sets
        // sub-block count (1..8) laid out at stride 2*0x800 (dual-polarity pairs) up to 16 slots (0x8000 B).
        val maxGapSlots = if (lrAddr > sadAddr && sadAddr > 0L) {
            ((lrAddr - sadAddr) / SAD_BLOCK_BYTES.toLong()).toInt().coerceIn(1, 16)
        } else {
            16
        }
        val dmaSlots = maxOf(numBlocks, numSubBlocks * 2).coerceIn(1, maxGapSlots)
        val searchReg = words[OFF_SEARCH_RANGE / 4]
        val maxShifts = intArrayOf(
            ((searchReg ushr 8) and 0x7FL).toInt().let { if (it in 1..80) it else 40 },
            ((searchReg ushr 16) and 0x7FL).toInt().let { if (it in 1..80) it else 40 },
            ((searchReg ushr 24) and 0x7FL).toInt().let { if (it in 1..80) it else 40 },
        )
        val minMaxShift = minOf(maxShifts[0], maxShifts[1], maxShifts[2])

        val mem = dmaMemoryProvider()
        val lens = lensProvider()
        val depthPort = depthProvider()

        lastNumBlocks = numBlocks
        lastSadAddr = sadAddr
        lastLrAddr = lrAddr
        lastMaxShift0 = maxShifts[0]

        if (mem != null && sadAddr in 1L..0xFFFF_FFFFL) {
            val sadTotalBytes = dmaSlots.toLong() * SAD_BLOCK_BYTES.toLong()
            val canWriteSad = runCatching { mem.check(sadAddr, sadTotalBytes, true); true }.getOrDefault(false)
            val canWriteLr = lrEnabled && lrAddr in 1L..0xFFFF_FFFFL &&
                runCatching { mem.check(lrAddr, dmaSlots.toLong() * LR_BLOCK_BYTES.toLong(), true); true }.getOrDefault(false)

            var hasFilledSad = false
            var lastSadDeltaBits = 0L
            var hasFilledLr = false
            var lastLrDeltaBits = 0L
            for (ip in 0 until dmaSlots) {
                val focusM = lens?.focusDistanceM ?: 2.0
                val roiBlockIdx = when {
                    numBlocks <= 1 -> 0
                    numSubBlocks <= 1 -> ip.coerceIn(0, numBlocks - 1)
                    else -> (ip / 2).coerceIn(0, numBlocks - 1)
                }
                val subjM = sampleBlockDepth(roiBlockIdx, numBlocks, depthPort, focusM)
                val (defocusMm, deltaPx) = if (lens != null) {
                    computePhysicalDisparity(lens, subjM, minMaxShift)
                } else {
                    0.0 to 0.0
                }
                if (ip == 0) {
                    lastFocusDistanceM = focusM
                    lastSubjectDepthM = subjM
                    lastDefocusMm = defocusMm
                    lastDeltaPx = deltaPx
                }
                val deltaBits = deltaPx.toRawBits()
                if (canWriteSad) {
                    if (!hasFilledSad || deltaBits != lastSadDeltaBits) {
                        fillSadBlock(deltaPx, maxShifts)
                        lastSadDeltaBits = deltaBits
                        hasFilledSad = true
                    }
                    mem.write(sadAddr + ip.toLong() * SAD_BLOCK_BYTES.toLong(), ByteBuffer.wrap(sadBlockBuf))
                }
                if (canWriteLr) {
                    if (!hasFilledLr || deltaBits != lastLrDeltaBits) {
                        fillLrWaveformBlock(deltaPx)
                        lastLrDeltaBits = deltaBits
                        hasFilledLr = true
                    }
                    mem.write(lrAddr + ip.toLong() * LR_BLOCK_BYTES.toLong(), ByteBuffer.wrap(lrBlockBuf))
                }
            }
        }

        val blockBits = (1L shl numBlocks) - 1L
        irqStatus = (irqStatus or blockBits) and 0xFFFFL
        running = false
        words[OFF_CTRL / 4] = words[OFF_CTRL / 4] and CTRL_START_BIT.inv()
        completionCount++
        updateIrq()
    }

    fun createMmioDevice(isClosed: () -> Boolean): TickAwareMmioDevice = object : TickAwareMmioDevice {
        override val clockOrder = 82
        override val clockIndex = 0

        override fun nextDeadline(): Long? = if (isClosed()) null else pendingCompletionTick
        override fun nextInterestedTick(): Long = if (isClosed()) Long.MAX_VALUE else (pendingCompletionTick ?: Long.MAX_VALUE)

        override fun beforeInstruction(tick: Long) {
            if (isClosed()) return
            val due = pendingCompletionTick ?: return
            if (tick >= due) {
                pendingCompletionTick = null
                completeRun()
            }
        }

        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_ID closed" }
            require(size == 4 && offset in 0L until WINDOW_SIZE && offset % 4L == 0L) {
                "$PORT_ID requires aligned 32-bit access within [0, 0x${WINDOW_SIZE.toString(16)}): off=0x${offset.toString(16)} size=$size"
            }
            val off = offset.toInt()
            return when (off) {
                OFF_CTRL -> {
                    val base = words[0] and 0x0000_FFFFL
                    if (running) base else (base or CTRL_IDLE_READY_BIT)
                }
                OFF_IRQ_STATUS -> irqStatus and 0xFFFFL
                else -> words[off / 4]
            }
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_ID closed" }
            require(size == 4 && offset in 0L until WINDOW_SIZE && offset % 4L == 0L) {
                "$PORT_ID requires aligned 32-bit access within [0, 0x${WINDOW_SIZE.toString(16)}): off=0x${offset.toString(16)} size=$size"
            }
            require(value in 0L..0xFFFF_FFFFL) { "$PORT_ID write value outside u32: $value" }
            val off = offset.toInt()
            when (off) {
                OFF_CTRL -> {
                    words[0] = value and 0x0000_FFFFL
                    if ((value and CTRL_START_BIT) != 0L) {
                        running = true
                        startCount++
                        pendingCompletionTick = clockProvider() + COMPLETION_DELAY_TICKS
                    } else {
                        running = false
                        pendingCompletionTick = null
                    }
                }
                OFF_IRQ_STATUS -> {
                    // 16-bit W1C status (S5 0x021E7E48..50 writes 1 << blockIdx)
                    irqStatus = irqStatus and (value and 0xFFFFL).inv()
                    updateIrq()
                }
                OFF_IRQ_MASK, OFF_IRQ_GLOBAL_0, OFF_IRQ_GLOBAL_1 -> {
                    words[off / 4] = value
                    updateIrq()
                }
                else -> words[off / 4] = value
            }
        }
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "running" to running,
        "irqStatus" to irqStatus,
        "irqAsserted" to irqAsserted,
        "startCount" to startCount,
        "completionCount" to completionCount,
        "lastNumBlocks" to lastNumBlocks,
        "lastSadAddr" to "0x${lastSadAddr.toString(16)}",
        "lastLrAddr" to "0x${lastLrAddr.toString(16)}",
        "lastMaxShift0" to lastMaxShift0,
        "lastSubjectDepthM" to lastSubjectDepthM,
        "lastFocusDistanceM" to lastFocusDistanceM,
        "lastDefocusMm" to lastDefocusMm,
        "lastDeltaPx" to lastDeltaPx,
        "lastRoiNormX" to lastRoiNormX,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "words" to words.toList(),
        "running" to running,
        "pendingCompletionTick" to pendingCompletionTick,
        "irqStatus" to irqStatus,
        "irqAsserted" to irqAsserted,
        "startCount" to startCount,
        "completionCount" to completionCount,
    )

    fun restoreSnapshot(raw: Any?) {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid pdaf snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected pdaf snapshot keys" }
        val rawWords = map["words"] as? List<*> ?: throw IllegalArgumentException("pdaf words requires list")
        require(rawWords.size == WORD_COUNT) { "pdaf words size mismatch: ${rawWords.size} != $WORD_COUNT" }
        val nextWords = LongArray(WORD_COUNT) { i -> reqLong(rawWords[i], "pdaf.words[$i]", 0L, 0xFFFF_FFFFL) }
        val nextRunning = map["running"] as? Boolean ?: throw IllegalArgumentException("pdaf running requires boolean")
        val nextPending = map["pendingCompletionTick"]?.let { reqLong(it, "pdaf.pendingCompletionTick", 0L, Long.MAX_VALUE) }
        val nextIrqStatus = reqLong(map["irqStatus"], "pdaf.irqStatus", 0L, 0xFFFFL)
        val nextIrqAsserted = map["irqAsserted"] as? Boolean ?: throw IllegalArgumentException("pdaf irqAsserted requires boolean")
        val nextStart = reqLong(map["startCount"], "pdaf.startCount", 0L, Long.MAX_VALUE)
        val nextComp = reqLong(map["completionCount"], "pdaf.completionCount", 0L, Long.MAX_VALUE)

        nextWords.copyInto(words)
        running = nextRunning
        pendingCompletionTick = nextPending
        irqStatus = nextIrqStatus
        irqAsserted = nextIrqAsserted
        startCount = nextStart
        completionCount = nextComp
    }

    private fun reqLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot $field requires integer" }
        val v = (value as Number).toLong()
        require(v in min..max) { "Snapshot $field out of range: $v" }
        return v
    }
}
