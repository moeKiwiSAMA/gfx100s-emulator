package com.github.nyabanban.fuji.emulator.components.isp

import kotlin.math.roundToInt

/**
 * Real-silicon Live-View ISP (`0xFFF68000`) color & tone processing pipeline for `BUG-A1-2`:
 * - `0xFFF68300..0xFFF68314` (`S4 0x011CE51C`, `S5 0x02192AC4`): 3×3 13-bit signed Q10 (`0x400 = 1.0`)
 *   linear RGB Color Correction Matrix (2 words per row: `[12:0]`, `[28:16]`, next word `[12:0]`).
 * - `0xFFF68400..0xFFF684C4` (`S4 0x011CE684..0x011CE95C`): 3 × 31-knot 14-bit (`0..16383`)
 *   piecewise-linear RGB Gamma / Tone Curve LUT (`0x400..0x404` knot spacing nibbles + `3 × 16` words).
 * - `0xFFF68500..0xFFF68514` (`S4 0x011CEAD0..0x011CEC40`): 3×3 13-bit signed RGB -> YCbCr matrix
 *   (controls color saturation, monochrome `ACROS`/`MONOCHROME`, and monochrome warm/cool tint).
 *
 * Precomputes 4096-entry per-channel tone tables only when the 64-bit FNV-1a register signature
 * changes, keeping steady-state per-frame overhead at zero and preserving zero-overhead bypass
 * when registers are unprogrammed or identity.
 */
internal class IspColorPipeline {
    val ccm = IntArray(9)
    val yuv = IntArray(9)
    val chromaMatrix = IntArray(4)
    val toneLutR = IntArray(4096)
    val toneLutG = IntArray(4096)
    val toneLutB = IntArray(4096)
    val knotsR = IntArray(TONE_KNOTS)
    val knotsG = IntArray(TONE_KNOTS)
    val knotsB = IntArray(TONE_KNOTS)
    val drGainKnots = IntArray(DR_GAIN_KNOTS)
    private val gammaScratch = IntArray(4096)

    var signature: Long = 0L
        private set
    var hasCcm: Boolean = false
        private set
    var hasToneLut: Boolean = false
        private set
    var hasDrGainCurve: Boolean = false
        private set
    var hasYuvMatrix: Boolean = false
        private set
    var monochromeMode: Boolean = false
        private set
    var monoToneDeltaCb: Int = 0
        private set
    var monoToneDeltaCr: Int = 0
        private set
    var yuvShift: Int = 10
        private set
    var toneSpacing0: Long = 0L
        private set
    var toneSpacing1: Long = 0L
        private set

    fun updateFromBank68000(words: LongArray) {
        if (words.isEmpty()) {
            reset()
            return
        }
        var sig = FNV_OFFSET
        sig = mix64(sig, words[MONO_CTRL_INDEX])
        sig = mix64(sig, words[CHROMA_534_INDEX])
        sig = mix64(sig, words[CHROMA_538_INDEX])
        sig = mix64(sig, words[MONO_TONE_CTRL_INDEX])
        sig = mix64(sig, words[MONO_TONE_TARGET_INDEX])
        for (i in DR_GAIN_204_INDICES) {
            sig = mix64(sig, words[i])
        }
        for (i in MATRIX_300_INDICES) {
            sig = mix64(sig, words[i])
        }
        for (i in TONE_400_INDICES) {
            sig = mix64(sig, words[i])
        }
        for (i in YUV_500_INDICES) {
            sig = mix64(sig, words[i])
        }
        if (sig == signature && signature != 0L) return
        signature = sig

        // 0. 0xFFF68010 bit 4 (0x10): Real-silicon ISP Monochrome Base Enable
        //    (0x11 = ACROS / MONOCHROME / SEPIA; 0x01 = Color Nostalgic Neg. / ETERNA BLEACH BYPASS; 0x00 = PROVIA..ETERNA).
        val monoBase = (words[MONO_CTRL_INDEX] and 0x10L) != 0L
        monoToneDeltaCb = 0
        monoToneDeltaCr = 0

        // 1. 0xFFF68204..0xFFF68250: 40-knot 14-bit Q9 (0x200 = 1.0x) Tone Curve / DR gain table (S4 0x011CE2E0)
        for (i in 0 until 20) {
            val w = words[DR_GAIN_204_INDICES[i]]
            drGainKnots[i * 2] = (w and 0x3FFFL).toInt()
            drGainKnots[i * 2 + 1] = ((w ushr 16) and 0x3FFFL).toInt()
        }
        hasDrGainCurve = drGainKnots[DR_GAIN_KNOTS - 1] > 0 && drGainKnots.any { it != 0 && it != 512 }

        // 2. 0xFFF68300..0xFFF68314: 3x3 13-bit signed Q10 RGB CCM
        decodeMatrix3x3(words, MATRIX_300_INDICES, ccm)
        val ccmNonZero = ccm.any { it != 0 }
        val ccmIdentity = ccm[0] == 1024 && ccm[1] == 0 && ccm[2] == 0 &&
            ccm[3] == 0 && ccm[4] == 1024 && ccm[5] == 0 &&
            ccm[6] == 0 && ccm[7] == 0 && ccm[8] == 1024
        hasCcm = ccmNonZero && !ccmIdentity

        // 3. 0xFFF68400..0xFFF684C4: 3x31-knot 14-bit RGB Gamma / Film LUT (composed with 0x204..0x250 Tone Curve)
        toneSpacing0 = words[TONE_400_INDICES[0]]
        toneSpacing1 = words[TONE_400_INDICES[1]]
        decodeToneKnots(words, TONE_R_INDICES, knotsR)
        decodeToneKnots(words, TONE_G_INDICES, knotsG)
        decodeToneKnots(words, TONE_B_INDICES, knotsB)
        val validTone = knotsR[TONE_KNOTS - 1] > 0 && knotsG[TONE_KNOTS - 1] > 0 && knotsB[TONE_KNOTS - 1] > 0
        if (validTone) {
            buildComposedToneLut(knotsR, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutR)
            buildComposedToneLut(knotsG, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutG)
            buildComposedToneLut(knotsB, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutB)
            hasToneLut = true
        } else {
            hasToneLut = false
        }

        // 4. 0xFFF68500..0xFFF68514: 3x3 13-bit signed RGB -> YCbCr matrix
        decodeMatrix3x3(words, YUV_500_INDICES, yuv)
        val yRowSum = yuv[0] + yuv[1] + yuv[2]
        if (yRowSum > 64) {
            yuvShift = when {
                yRowSum >= 1536 -> 11
                yRowSum >= 768 -> 10
                yRowSum >= 384 -> 9
                else -> 8
            }
            hasYuvMatrix = true
        } else {
            hasYuvMatrix = false
        }

        // 5. 0xFFF68534..0xFFF68538: 2x2 13-bit signed Q10 Chroma Gain / Hue Matrix
        //    (e.g. PROVIA ~[1109,-31 / 111,1454], Velvia ~[1277,-119 / 246,2044],
        //     Nostalgic Neg. [1255,191 / -43,1544], ETERNA BLEACH BYPASS [550,14 / 167,625], ACROS/MONO/SEPIA [0,0/0,0])
        val w534 = words[CHROMA_534_INDEX]
        val w538 = words[CHROMA_538_INDEX]
        val cBb = signExtend13(w534)
        val cBr = signExtend13(w534 ushr 16)
        val cRr = signExtend13(w538)
        val cRb = signExtend13(w538 ushr 16)
        chromaMatrix[0] = cBb
        chromaMatrix[1] = cBr
        chromaMatrix[2] = cRb
        chromaMatrix[3] = cRr

        if (!monoBase) {
            monochromeMode = false
            // Compose programmed 2x2 chroma matrix directly into yuv[3..8] (zero inner-loop overhead, exact SIMD parity)
            if (hasYuvMatrix && (w534 != 0L || w538 != 0L)) {
                for (col in 0 until 3) {
                    val cb = yuv[3 + col]
                    val cr = yuv[6 + col]
                    val newCb = cBb * cb + cBr * cr
                    val newCr = cRb * cb + cRr * cr
                    yuv[3 + col] = (newCb + if (newCb >= 0) 512 else -512) / 1024
                    yuv[6 + col] = (newCr + if (newCr >= 0) 512 else -512) / 1024
                }
            }
        } else {
            // 6. 0xFFF68C00 bit 0 + 0xFFF68CA0 ([25:16]=Cb10, [9:0]=Cr10): Monochrome Toning (SEPIA & MONOCHROMATIC COLOR WC/MG)
            //    Neutral ACROS/MONOCHROME STD programs 0x0200_0200 -> (dCb=0, dCr=0) -> pure grayscale (Cb=128, Cr=128).
            //    SEPIA programs 0x01CC_0233 -> (dCb=-52, dCr=+51) warm sepia tint; WC/MG shifts 0xCA0 away from 0x0200_0200.
            val toneEnabled = (words[MONO_TONE_CTRL_INDEX] and 1L) != 0L
            val wCa0 = words[MONO_TONE_TARGET_INDEX]
            val rawCb10 = ((wCa0 ushr 16) and 0x3FFL).toInt()
            val rawCr10 = (wCa0 and 0x3FFL).toInt()
            val dCb10: Int
            val dCr10: Int
            if (toneEnabled && wCa0 != 0L && !(rawCb10 == 0x200 && rawCr10 == 0x200)) {
                val center = if (rawCb10 >= 0x300 && rawCr10 >= 0x300) 0x400 else 0x200
                dCb10 = rawCb10 - center
                dCr10 = rawCr10 - center
            } else {
                dCb10 = 0
                dCr10 = 0
            }
            monoToneDeltaCb = dCb10
            monoToneDeltaCr = dCr10
            if (dCb10 != 0 || dCr10 != 0) {
                monochromeMode = false
                if (!hasYuvMatrix) {
                    yuv[0] = 306
                    yuv[1] = 601
                    yuv[2] = 117
                    yuvShift = 10
                    hasYuvMatrix = true
                }
                // Fold luma-proportional monochrome toning directly into yuv[3..8]:
                // at mid-gray Y=128, (k * Y) / 1024 = (2 * d10 * 128) / 1024 = d10 / 4 (pure black Y=0 stays neutral 128/128).
                val kCb = dCb10 * 2
                val kCr = dCr10 * 2
                for (col in 0 until 3) {
                    val yCoeff = yuv[col]
                    val prodCb = kCb * yCoeff
                    val prodCr = kCr * yCoeff
                    yuv[3 + col] = (prodCb + if (prodCb >= 0) 512 else -512) / 1024
                    yuv[6 + col] = (prodCr + if (prodCr >= 0) 512 else -512) / 1024
                }
            } else {
                monochromeMode = true
            }
        }
    }

    /**
     * Configures the Still / In-Camera RAW Conversion (`RECC_S` `0xFFF82000`) color pipeline from
     * real-silicon Still ISP banks (`DAT S4 0x017d017c`, `0x017c03f8`, `0x017d04dc`, `0x017d0098`):
     * - `0xFFF86204..0xFFF86218`: 6 words = 3×3 13-bit signed Q10 linear RGB CCM (identical packing
     *   to LiveView `0xFFF68300..0xFFF68314`, verified live: STD `[1051,-72,45; -59,1136,-53; 73,-202,1153]`,
     *   MONOCHROME/ACROS `[1228,-230,26; 251,1182,-409; -75,-217,1316]`).
     * - `0xFFF98000[0]` + `0xFFF87200[10]` + `0xFFF88000[0]`: Monochrome/ACROS mode select
     *   (`isp98000[0] == 1` and chroma block disabled in `0xFFF87200[10] == 0` / `0xFFF88000[0] == 0`).
     * Falls back to [updateFromBank68000] when `0xFFF86204..0xFFF86218` has not been programmed.
     */
    fun updateFromStillIspBanks(
        isp86000Words: LongArray,
        isp87000Words: LongArray,
        isp88000Words: LongArray,
        isp98000Words: LongArray,
        fallbackBank68000: LongArray,
    ) {
        val ccmBase = 0x204 / 4
        if (isp86000Words.size < ccmBase + 6) {
            updateFromBank68000(fallbackBank68000)
            return
        }
        var anyStillCcm = false
        for (i in 0 until 6) {
            if (isp86000Words[ccmBase + i] != 0L) {
                anyStillCcm = true
                break
            }
        }
        if (!anyStillCcm) {
            updateFromBank68000(fallbackBank68000)
            return
        }
        val ctrl87200 = if (isp87000Words.size > 0x200 / 4) isp87000Words[0x200 / 4] else 0L
        val ctrl88000 = if (isp88000Words.isNotEmpty()) isp88000Words[0] else 0L
        val ctrl98000 = if (isp98000Words.isNotEmpty()) isp98000Words[0] else 0L
        var sig = FNV_OFFSET xor 0x5354_494C_4C01L
        for (i in 0 until 6) sig = mix64(sig, isp86000Words[ccmBase + i])
        sig = mix64(sig, ctrl87200)
        sig = mix64(sig, ctrl88000)
        sig = mix64(sig, ctrl98000)
        if (fallbackBank68000.isNotEmpty()) {
            for (i in DR_GAIN_204_INDICES) sig = mix64(sig, fallbackBank68000[i])
            for (i in TONE_400_INDICES) sig = mix64(sig, fallbackBank68000[i])
        }
        if (sig == signature && signature != 0L) return
        signature = sig

        monochromeMode = (ctrl98000 and 1L) != 0L ||
            (ctrl87200 != 0L && (ctrl87200 and 0x400L) == 0L && ctrl88000 == 0L)

        for (row in 0 until 3) {
            val w0 = isp86000Words[ccmBase + row * 2]
            val w1 = isp86000Words[ccmBase + row * 2 + 1]
            ccm[row * 3 + 0] = signExtend13(w0)
            ccm[row * 3 + 1] = signExtend13(w0 ushr 16)
            ccm[row * 3 + 2] = signExtend13(w1)
        }
        val ccmIdentity = ccm[0] == 1024 && ccm[1] == 0 && ccm[2] == 0 &&
            ccm[3] == 0 && ccm[4] == 1024 && ccm[5] == 0 &&
            ccm[6] == 0 && ccm[7] == 0 && ccm[8] == 1024
        hasCcm = !ccmIdentity

        if (fallbackBank68000.isNotEmpty()) {
            for (i in 0 until 20) {
                val w = fallbackBank68000[DR_GAIN_204_INDICES[i]]
                drGainKnots[i * 2] = (w and 0x3FFFL).toInt()
                drGainKnots[i * 2 + 1] = ((w ushr 16) and 0x3FFFL).toInt()
            }
            hasDrGainCurve = drGainKnots[DR_GAIN_KNOTS - 1] > 0 && drGainKnots.any { it != 0 && it != 512 }
            toneSpacing0 = fallbackBank68000[TONE_400_INDICES[0]]
            toneSpacing1 = fallbackBank68000[TONE_400_INDICES[1]]
            decodeToneKnots(fallbackBank68000, TONE_R_INDICES, knotsR)
            decodeToneKnots(fallbackBank68000, TONE_G_INDICES, knotsG)
            decodeToneKnots(fallbackBank68000, TONE_B_INDICES, knotsB)
            val validTone = knotsR[TONE_KNOTS - 1] > 0 && knotsG[TONE_KNOTS - 1] > 0 && knotsB[TONE_KNOTS - 1] > 0
            if (validTone) {
                buildComposedToneLut(knotsR, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutR)
                buildComposedToneLut(knotsG, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutG)
                buildComposedToneLut(knotsB, toneSpacing0, toneSpacing1, hasDrGainCurve, drGainKnots, gammaScratch, toneLutB)
                hasToneLut = true
            } else {
                hasToneLut = false
            }
        } else {
            hasDrGainCurve = false
            hasToneLut = false
        }
        hasYuvMatrix = false
        yuvShift = 10
    }

    fun reset() {
        ccm.fill(0)
        yuv.fill(0)
        chromaMatrix.fill(0)
        knotsR.fill(0)
        knotsG.fill(0)
        knotsB.fill(0)
        drGainKnots.fill(0)
        signature = 0L
        hasCcm = false
        hasToneLut = false
        hasDrGainCurve = false
        hasYuvMatrix = false
        monochromeMode = false
        monoToneDeltaCb = 0
        monoToneDeltaCr = 0
        yuvShift = 10
        toneSpacing0 = 0L
        toneSpacing1 = 0L
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "monochromeMode" to monochromeMode,
        "monoToneDelta" to listOf(monoToneDeltaCb, monoToneDeltaCr),
        "hasCcm" to hasCcm,
        "hasDrGainCurve" to hasDrGainCurve,
        "hasToneLut" to hasToneLut,
        "hasYuvMatrix" to hasYuvMatrix,
        "yuvShift" to yuvShift,
        "ccm" to ccm.toList(),
        "chromaMat" to chromaMatrix.toList(),
        "yuvMat" to yuv.toList(),
        "drGainSample" to listOf(drGainKnots[0], drGainKnots[10], drGainKnots[20], drGainKnots[30], drGainKnots[39]),
        "toneSpacing" to listOf("0x${toneSpacing0.toString(16)}", "0x${toneSpacing1.toString(16)}"),
        "toneRSample" to listOf(knotsR[0], knotsR[5], knotsR[10], knotsR[15], knotsR[20], knotsR[25], knotsR[30]),
    )

    companion object {
        const val TONE_KNOTS = 31
        const val DR_GAIN_KNOTS = 40
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        private val MONO_CTRL_INDEX = IspCoreOffsets.BANK_68000_INDEX.getValue(0x010)
        private val CHROMA_534_INDEX = IspCoreOffsets.BANK_68000_INDEX.getValue(0x534)
        private val CHROMA_538_INDEX = IspCoreOffsets.BANK_68000_INDEX.getValue(0x538)
        private val MONO_TONE_CTRL_INDEX = IspCoreOffsets.BANK_68000_INDEX.getValue(0xc00)
        private val MONO_TONE_TARGET_INDEX = IspCoreOffsets.BANK_68000_INDEX.getValue(0xca0)
        private val DR_GAIN_204_INDICES = IntArray(20) { i ->
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x204 + i * 4)
        }

        private val MATRIX_300_INDICES = intArrayOf(
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x300),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x304),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x308),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x30c),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x310),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x314),
        )

        private val TONE_400_INDICES = IntArray(50) { i ->
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x400 + i * 4)
        }
        private val TONE_R_INDICES = IntArray(16) { i ->
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x408 + i * 4)
        }
        private val TONE_G_INDICES = IntArray(16) { i ->
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x448 + i * 4)
        }
        private val TONE_B_INDICES = IntArray(16) { i ->
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x488 + i * 4)
        }

        private val YUV_500_INDICES = intArrayOf(
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x500),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x504),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x508),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x50c),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x510),
            IspCoreOffsets.BANK_68000_INDEX.getValue(0x514),
        )

        private fun mix64(acc: Long, value: Long): Long = (acc xor value) * FNV_PRIME

        internal fun signExtend13(raw: Long): Int {
            val v = (raw and 0x1FFFL).toInt()
            return (v shl 19) shr 19
        }

        private fun decodeMatrix3x3(words: LongArray, indices: IntArray, dst: IntArray) {
            for (row in 0 until 3) {
                val w0 = words[indices[row * 2]]
                val w1 = words[indices[row * 2 + 1]]
                dst[row * 3 + 0] = signExtend13(w0)
                dst[row * 3 + 1] = signExtend13(w0 ushr 16)
                dst[row * 3 + 2] = signExtend13(w1)
            }
        }

        private fun decodeToneKnots(words: LongArray, indices: IntArray, dst: IntArray) {
            for (i in 0 until 15) {
                val w = words[indices[i]]
                dst[i * 2] = (w and 0x3FFFL).toInt()
                dst[i * 2 + 1] = ((w ushr 16) and 0x3FFFL).toInt()
            }
            dst[30] = (words[indices[15]] and 0x3FFFL).toInt()
        }

        /**
         * Composes the 40-knot linear Tone Curve / DR gain (`0xFFF68204..0xFFF68250`, Q9 `512 = 1.0x`)
         * with the 31-knot 14-bit Film/Gamma LUT (`0xFFF68408..0xFFF684C4`) into a single 4096-entry
         * lookup table so per-pixel processing remains O(1) with zero extra inner-loop instructions.
         */
        internal fun buildComposedToneLut(
            knots14: IntArray,
            spacing0: Long,
            spacing1: Long,
            applyDrGain: Boolean,
            drGainKnots: IntArray,
            gammaScratch: IntArray,
            dst4096: IntArray,
        ) {
            if (!applyDrGain) {
                buildToneLut(knots14, spacing0, spacing1, dst4096)
                return
            }
            buildToneLut(knots14, spacing0, spacing1, gammaScratch)
            for (x in 0 until 4096) {
                val pos = (x.toDouble() * (DR_GAIN_KNOTS - 1)) / 4095.0
                val i0 = pos.toInt().coerceIn(0, DR_GAIN_KNOTS - 2)
                val frac = pos - i0
                val gQ9 = drGainKnots[i0] + (drGainKnots[i0 + 1] - drGainKnots[i0]) * frac
                val xToned = ((x.toDouble() * gQ9) / 512.0).roundToInt().coerceIn(0, 4095)
                dst4096[x] = gammaScratch[xToned]
            }
        }

        /**
         * Builds a 4096-entry 12-bit-in -> 8-bit-out tone LUT from the 31 hardware 14-bit knots.
         * Supports both uniform spacing and non-uniform power-of-2 knee spacing encoded in `0x400..0x404`
         * (10 × 4-bit nibbles, 3 segments per band -> 30 segments across 31 knots).
         */
        internal fun buildToneLut(knots14: IntArray, spacing0: Long, spacing1: Long, dst4096: IntArray) {
            val knotX = DoubleArray(TONE_KNOTS)
            var totalSpan = 0.0
            val nibbles = IntArray(10) { idx ->
                val word = if (idx < 8) spacing0 else spacing1
                val shift = (idx and 7) * 4
                ((word ushr shift) and 0xFL).toInt()
            }
            val useExpSpacing = nibbles.any { it != 0 } && nibbles.all { it <= 12 }
            if (useExpSpacing) {
                knotX[0] = 0.0
                for (seg in 0 until TONE_KNOTS - 1) {
                    val band = (seg / 3).coerceIn(0, 9)
                    val step = (1 shl nibbles[band]).toDouble()
                    totalSpan += step
                    knotX[seg + 1] = totalSpan
                }
            }
            if (!useExpSpacing || totalSpan <= 0.0) {
                for (k in 0 until TONE_KNOTS) {
                    knotX[k] = (k * 4095.0) / (TONE_KNOTS - 1).toDouble()
                }
            } else {
                val scale = 4095.0 / totalSpan
                for (k in 0 until TONE_KNOTS) {
                    knotX[k] *= scale
                }
            }

            var k = 0
            for (x in 0 until 4096) {
                val xf = x.toDouble()
                while (k < TONE_KNOTS - 2 && xf > knotX[k + 1]) {
                    k++
                }
                val x0 = knotX[k]
                val x1 = knotX[k + 1]
                val y0 = knots14[k].toDouble()
                val y1 = knots14[k + 1].toDouble()
                val t = if (x1 > x0) ((xf - x0) / (x1 - x0)).coerceIn(0.0, 1.0) else 0.0
                val y14 = y0 + (y1 - y0) * t
                dst4096[x] = ((y14 * 255.0) / 16383.0).roundToInt().coerceIn(0, 255)
            }
        }
    }
}
