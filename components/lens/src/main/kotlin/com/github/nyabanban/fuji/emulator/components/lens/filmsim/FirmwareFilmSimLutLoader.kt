package com.github.nyabanban.fuji.emulator.components.lens.filmsim

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Runtime collection of all Fujifilm GFX100S 2.13 Film Simulation `16x16x16` 12-bit 3D LUTs
 * extracted directly from the user's local firmware image (`GXUP0004.DAT` and/or decoded sections).
 *
 * No copyrighted firmware binaries or coefficients are committed to this repository; every table
 * is decoded dynamically from the user-supplied firmware file at runtime.
 */
data class FirmwareFilmSimLutPack(
    /** The 4 raw `16x16x16` 12-bit hardware 3D LUT cubes from `section4.bin` (`0x017F1C8C`). */
    val rawHardwareLuts: List<FilmSim3dLut>,
    /** Synthesized `16x16x16` 12-bit 3D LUTs for all 19 still Film Simulations + F-Log & HLG. */
    val luts: Map<FilmSimulation, FilmSim3dLut>,
) {
    /** Returns the `16x16x16` 12-bit 3D LUT for [simulation]. */
    operator fun get(simulation: FilmSimulation): FilmSim3dLut =
        luts.getValue(simulation)

    /** Returns only the 19 user-facing still Film Simulation LUTs. */
    fun stillSimulations(): Map<FilmSimulation, FilmSim3dLut> =
        FilmSimulation.ALL_STILL_19.associateWith { luts.getValue(it) }
}

/**
 * Runtime extractor for authentic FUJIFILM GFX100S 2.13 Film Simulation 3D LUTs.
 *
 * Firmware structures verified (`DAT`) from GFX100S 2.13 static disassembly:
 * 1. **4 Hardware `16x16x16` 12-bit 3D LUTs** in `section4.bin` @ `0x017F1C8C..0x01811C8C`
 *    (count word `*(0x01811C8C) = 4`; `LUT[0]` = Identity, `LUT[1]` = Classic Neg / `SUPERIA`,
 *    `LUT[2]` = Nostalgic Neg / `AMERICAN_NEW_COLOR`, `LUT[3]` = Identity).
 * 2. **30 Built-in 31-Knot 14-bit Tone/Gamma Curves** in `section4.bin` @ `0x017E16A4`
 *    (stride `0x4C`, count `*(0x017F0374) = 30`), selected per Film Simulation via `section5`
 *    `0x026FCBB4` and Bank 14 `0x02156F`/`0x021570` (F-Log/HLG).
 * 3. **Per-Film-Simulation `2x2` YCbCr Chroma Matrices (`Q10 = 1024`)** in NOR Flash Bank 14
 *    (`GXUP0004.DAT + 0x000C0274`, `XOR 0xFF`, `0x72000` bytes), indexed by `section5` `0x026FE094`
 *    -> `0x026FE144` (and `0x009E5E`/`0x009E5F` for F-Log/HLG).
 * 4. **BT.601 RGB <-> YCbCr `3x3` Matrices (`Q10 = 1024`)** in Bank 14 via `section5`
 *    `0x026FB2E4` (`Post2`) and `0x026FB3E0` (`Pre1`), plus **Sepia / Monochrome Toning** offsets
 *    (`0x02209F`/`0x0220A1`, knees `0x0220A3`/`0x0220A5`) implementing the exact piecewise-linear
 *    synthesis loop from `section5 0x02199404`.
 */
object FirmwareFilmSimLutLoader {
    const val BANK14_DAT_OFFSET = 0x000C_0274
    const val BANK14_SIZE = 0x0007_2000

    const val SECTION4_DAT_OFFSET = 2_490_996 // 0x0026_0274
    const val SECTION4_VA = 0x0102_1000
    const val SECTION4_SIZE = 8_389_100

    const val SECTION5_DAT_OFFSET = 6_423_156 // 0x0062_0274
    const val SECTION5_VA = 0x01F2_8000
    const val SECTION5_SIZE = 10_248_192

    const val S4_LUT_ARRAY_VA = 0x017F_1C8C
    const val S4_LUT_COUNT_VA = 0x0181_1C8C
    const val S4_TONE_CURVE_TABLE_VA = 0x017E_16A4
    const val S4_TONE_CURVE_STRIDE = 0x4C
    const val S4_TONE_CURVE_COUNT_VA = 0x017F_0374

    const val S5_LUT_ID_TABLE_VA = 0x028D_5C2C
    const val S5_CBCR_SEL_TABLE_VA = 0x026F_E094
    const val S5_CBCR_MAT_TABLE_VA = 0x026F_E144
    const val S5_YCBCR_TO_RGB_TABLE_VA = 0x026F_B3E0
    const val S5_RGB_TO_YCBCR_TABLE_VA = 0x026F_B2E4
    const val S5_TONE_CURVE_SEL_TABLE_VA = 0x026F_CBB4

    const val B14_FLOG_CBCR_SEL = 0x009E5E
    const val B14_HLG_CBCR_SEL = 0x009E5F
    const val B14_FLOG_CURVE_SEL = 0x02156F
    const val B14_HLG_CURVE_SEL = 0x021570
    const val B14_SEPIA_CR = 0x02209F
    const val B14_SEPIA_CB = 0x0220A1
    const val B14_MONO_KNEE_LO = 0x0220A3
    const val B14_MONO_KNEE_HI = 0x0220A5

    /**
     * Loads and synthesizes all 21 (19 still + F-Log + HLG) Film Simulation 3D LUTs directly from
     * a local FUJIFILM GFX100S 2.13 `GXUP0004.DAT` firmware file.
     */
    fun loadFromDat(datPath: Path): FirmwareFilmSimLutPack {
        require(Files.isRegularFile(datPath)) { "Firmware DAT file not found: $datPath" }
        val dat = Files.readAllBytes(datPath)
        val bank14 = decodeInvertedRaw(dat, BANK14_DAT_OFFSET, BANK14_SIZE)
        val section4 = decodeInvertedMethod4(dat, SECTION4_DAT_OFFSET)
        val section5 = decodeInvertedMethod4(dat, SECTION5_DAT_OFFSET)
        return loadFromBuffers(bank14 = bank14, section4 = section4, section5 = section5)
    }

    /**
     * Fast-path loader using a local `GXUP0004.DAT` (for Bank 14) paired with pre-decoded
     * `section4.bin` and `section5.bin` files in [decodedDir].
     */
    fun loadFromDecodedDirectory(datPath: Path, decodedDir: Path): FirmwareFilmSimLutPack {
        require(Files.isRegularFile(datPath)) { "Firmware DAT file not found: $datPath" }
        val s4Path = decodedDir.resolve("section4.bin")
        val s5Path = decodedDir.resolve("section5.bin")
        require(Files.isRegularFile(s4Path)) { "Missing decoded section4.bin: $s4Path" }
        require(Files.isRegularFile(s5Path)) { "Missing decoded section5.bin: $s5Path" }
        val bank14 = readBank14FromDat(datPath)
        return loadFromBuffers(
            bank14 = bank14,
            section4 = Files.readAllBytes(s4Path),
            section5 = Files.readAllBytes(s5Path),
        )
    }

    /**
     * Extracts and synthesizes all 21 Film Simulation 3D LUTs from decoded [bank14], [section4],
     * and [section5] byte arrays.
     */
    fun loadFromBuffers(
        bank14: ByteArray,
        section4: ByteArray,
        section5: ByteArray,
    ): FirmwareFilmSimLutPack {
        require(bank14.size >= BANK14_SIZE) {
            "Bank 14 buffer too small: ${bank14.size} < $BANK14_SIZE"
        }
        require(section4.size >= (S4_LUT_COUNT_VA - SECTION4_VA) + 4) {
            "section4 buffer too small: ${section4.size}"
        }
        require(section5.size >= (S5_LUT_ID_TABLE_VA - SECTION5_VA) + 20 * 4) {
            "section5 buffer too small: ${section5.size}"
        }

        // 1. Read the 4 real 16x16x16 12-bit hardware 3D LUT cubes from section4.bin (0x017F1C8C).
        val lutCount = readU32(section4, S4_LUT_COUNT_VA - SECTION4_VA)
        require(lutCount in 1..8) { "Unexpected section4 3D LUT count at 0x01811C8C: $lutCount" }
        val rawHardwareLuts = (0 until lutCount).map { idx ->
            val off = (S4_LUT_ARRAY_VA - SECTION4_VA) + idx * FilmSim3dLut.HARDWARE_SRAM_BYTES
            FilmSim3dLut.fromHardwareSramBytes(
                name = "GFX100S-HW-LUT[$idx]",
                bytes = section4,
                offset = off,
                hardwareBaseLutIndex = idx,
            )
        }

        // 2. Read the 30 built-in 31-knot 14-bit Tone/Gamma curves from section4.bin (0x017E16A4).
        val curveCount = readU32(section4, S4_TONE_CURVE_COUNT_VA - SECTION4_VA)
        require(curveCount in 1..64) { "Unexpected section4 tone curve count: $curveCount" }
        val toneCurves = HashMap<Int, IntArray>(curveCount)
        for (i in 0 until curveCount) {
            val base = (S4_TONE_CURVE_TABLE_VA - SECTION4_VA) + i * S4_TONE_CURVE_STRIDE
            val key = readU32(section4, base)
            val knots = IntArray(31) { k -> readU16(section4, base + 14 + k * 2) }
            toneCurves[key] = knots
        }

        // 3. Read BT.601 RGB <-> YCbCr 3x3 Q10 matrices (mat_idx = 1) from Bank 14 via section5.
        val rgbToYcbcr = readBank14Matrix3x3S13(section5, bank14, S5_RGB_TO_YCBCR_TABLE_VA, 0)
        val ycbcrToRgb = readBank14Matrix3x3S13(section5, bank14, S5_YCBCR_TO_RGB_TABLE_VA, 0)

        // 4. Read Sepia / Monochrome toning parameters from Bank 14 (0x021991F0: 0x2209F=Cr, 0x220A1=Cb).
        val sepiaCr = readS16(bank14, B14_SEPIA_CR)
        val sepiaCb = readS16(bank14, B14_SEPIA_CB)
        val kneeLo = readU16(bank14, B14_MONO_KNEE_LO)
        val kneeHi = readU16(bank14, B14_MONO_KNEE_HI)

        // Provia baseline tone curve & chroma matrix used to compute relative display-referred tone transfer.
        val proviaCurveKey = readToneCurveKey(section5, bank14, FilmSimulation.PROVIA)
        val proviaCurve = toneCurves[proviaCurveKey] ?: toneCurves.values.first()
        val proviaCbCr = readCbCrMatrixQ10(section5, bank14, FilmSimulation.PROVIA)

        val luts = LinkedHashMap<FilmSimulation, FilmSim3dLut>(FilmSimulation.ALL_21.size)
        for (sim in FilmSimulation.ALL_21) {
            val hwLutIdx = readHardwareLutIndex(section5, bank14, sim).coerceIn(0, rawHardwareLuts.lastIndex)
            val curveKey = readToneCurveKey(section5, bank14, sim)
            val simCurve = toneCurves[curveKey] ?: proviaCurve
            val cbCrMat = readCbCrMatrixQ10(section5, bank14, sim)

            val lut = synthesizeFilmSimLut(
                sim = sim,
                baseLut = rawHardwareLuts[hwLutIdx],
                hwLutIdx = hwLutIdx,
                curveKey = curveKey,
                simCurve = simCurve,
                proviaCurve = proviaCurve,
                cbCrMat = cbCrMat,
                proviaCbCr = proviaCbCr,
                rgbToYcbcr = rgbToYcbcr,
                ycbcrToRgb = ycbcrToRgb,
                sepiaCb = sepiaCb,
                sepiaCr = sepiaCr,
                kneeLo = kneeLo,
                kneeHi = kneeHi,
            )
            luts[sim] = lut
        }

        return FirmwareFilmSimLutPack(
            rawHardwareLuts = rawHardwareLuts,
            luts = luts,
        )
    }

    private fun synthesizeFilmSimLut(
        sim: FilmSimulation,
        baseLut: FilmSim3dLut,
        hwLutIdx: Int,
        curveKey: Int,
        simCurve: IntArray,
        proviaCurve: IntArray,
        cbCrMat: IntArray,
        proviaCbCr: IntArray,
        rgbToYcbcr: IntArray,
        ycbcrToRgb: IntArray,
        sepiaCb: Int,
        sepiaCr: Int,
        kneeLo: Int,
        kneeHi: Int,
    ): FilmSim3dLut {
        val outRgb12 = ShortArray(FilmSim3dLut.NODE_COUNT * 3)
        val nodeBuf = IntArray(3)

        // Normalize Bank 14 Cb/Cr matrix relative to Provia's baseline gain so display-referred
        // sRGB inputs receive the authentic film saturation/hue ratio without clipping.
        val refCbGain = proviaCbCr[0].takeIf { it > 0 }?.toDouble() ?: 1024.0
        val refCrGain = proviaCbCr[1].takeIf { it > 0 }?.toDouble() ?: 1024.0

        // Relative chroma matrix [c00, c01; c10, c11] in floating point (identity = [1, 0; 0, 1]).
        // Bank 14 layout at 0x026FE144: [0]=Cb->Cb, [1]=Cr->Cr, [2]=Cr->Cb, [3]=Cb->Cr (Q10).
        val mCbCb = cbCrMat[0] / refCbGain
        val mCrCr = cbCrMat[1] / refCrGain
        val mCrCb = (cbCrMat[2] - proviaCbCr[2]) / 2048.0
        val mCbCr = (cbCrMat[3] - proviaCbCr[3]) / 2048.0

        for (bIdx in 0 until FilmSim3dLut.GRID_SIZE) {
            for (gIdx in 0 until FilmSim3dLut.GRID_SIZE) {
                for (rIdx in 0 until FilmSim3dLut.GRID_SIZE) {
                    baseLut.nodeRgb12(rIdx, gIdx, bIdx, nodeBuf)
                    val r0 = nodeBuf[0]
                    val g0 = nodeBuf[1]
                    val b0 = nodeBuf[2]

                    val dst = FilmSim3dLut.nodeIndex(rIdx, gIdx, bIdx) * 3
                    if (sim.isMonochrome) {
                        val yRaw = computeMonochromeLuma(r0, g0, b0, sim.monochromeFilter, rgbToYcbcr)
                        val yToned = applyRelativeToneCurve(yRaw, simCurve, proviaCurve, sim)
                        val (rOut, gOut, bOut) = if (sim.isSepia) {
                            synthesizeTonedMonoPixel(
                                y = yToned,
                                offCb = sepiaCb,
                                offCr = sepiaCr,
                                kneeLo = kneeLo,
                                kneeHi = kneeHi,
                                ycbcrToRgb = ycbcrToRgb,
                            )
                        } else {
                            Triple(yToned, yToned, yToned)
                        }
                        outRgb12[dst] = rOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                        outRgb12[dst + 1] = gOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                        outRgb12[dst + 2] = bOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                    } else {
                        // Stage 1: Relative firmware tone curve (31-knot 14-bit table @ 0x017E16A4).
                        val rt = applyRelativeToneCurve(r0, simCurve, proviaCurve, sim)
                        val gt = applyRelativeToneCurve(g0, simCurve, proviaCurve, sim)
                        val bt = applyRelativeToneCurve(b0, simCurve, proviaCurve, sim)

                        // Stage 2: RGB -> YCbCr using firmware Post2 3x3 matrix (Q10).
                        val y = (rgbToYcbcr[0] * rt + rgbToYcbcr[1] * gt + rgbToYcbcr[2] * bt) / 1024.0
                        val cb = (rgbToYcbcr[3] * rt + rgbToYcbcr[4] * gt + rgbToYcbcr[5] * bt) / 1024.0
                        val cr = (rgbToYcbcr[6] * rt + rgbToYcbcr[7] * gt + rgbToYcbcr[8] * bt) / 1024.0

                        // Stage 3: Per-Film-Simulation Bank 14 2x2 Cb/Cr saturation & hue matrix.
                        // Classic Neg / Nostalgic Neg already bake primary hue/chroma nonlinearity
                        // into LUT[1] / LUT[2], so blend their CbCr gain smoothly to avoid double-damping.
                        val cb2: Double
                        val cr2: Double
                        if (hwLutIdx != 0) {
                            val blend = 0.35
                            cb2 = cb * (1.0 - blend + blend * mCbCb) + cr * (blend * mCrCb)
                            cr2 = cr * (1.0 - blend + blend * mCrCr) + cb * (blend * mCbCr)
                        } else {
                            cb2 = cb * mCbCb + cr * mCrCb
                            cr2 = cr * mCrCr + cb * mCbCr
                        }

                        // Stage 4: YCbCr -> RGB using firmware Pre1 3x3 matrix (Q10).
                        val rOut = ((ycbcrToRgb[0] * y + ycbcrToRgb[1] * cb2 + ycbcrToRgb[2] * cr2) / 1024.0).roundToInt()
                        val gOut = ((ycbcrToRgb[3] * y + ycbcrToRgb[4] * cb2 + ycbcrToRgb[5] * cr2) / 1024.0).roundToInt()
                        val bOut = ((ycbcrToRgb[6] * y + ycbcrToRgb[7] * cb2 + ycbcrToRgb[8] * cr2) / 1024.0).roundToInt()

                        outRgb12[dst] = rOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                        outRgb12[dst + 1] = gOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                        outRgb12[dst + 2] = bOut.coerceIn(0, FilmSim3dLut.MAX_VALUE).toShort()
                    }
                }
            }
        }

        return FilmSim3dLut.fromRgb12(
            name = sim.displayName,
            rgb12 = outRgb12,
            simulation = sim,
            hardwareBaseLutIndex = hwLutIdx,
            toneCurveKey = curveKey,
            cbCrMatrixQ10 = cbCrMat,
        )
    }

    /**
     * Computes filtered monochrome luminance in `0..4095`.
     * `NONE` uses the exact firmware BT.601 luminance row `[306, 601, 117]/1024`.
     * Optical `Ye`/`R`/`G` filters apply channel weighting matching classical B&W contrast filters.
     */
    private fun computeMonochromeLuma(
        r: Int,
        g: Int,
        b: Int,
        filter: MonochromeFilter,
        rgbToYcbcr: IntArray,
    ): Int {
        val y = when (filter) {
            MonochromeFilter.NONE -> (rgbToYcbcr[0] * r + rgbToYcbcr[1] * g + rgbToYcbcr[2] * b) / 1024.0
            MonochromeFilter.YELLOW -> 0.44 * r + 0.52 * g + 0.04 * b
            MonochromeFilter.RED -> 0.72 * r + 0.24 * g + 0.04 * b
            MonochromeFilter.GREEN -> 0.20 * r + 0.72 * g + 0.08 * b
        }
        return y.roundToInt().coerceIn(0, FilmSim3dLut.MAX_VALUE)
    }

    /**
     * Exact reproduction of `section5 0x02199698..0x02199838` piecewise-linear Monochrome/Sepia
     * toning ramp with shadow knee (`kneeLo = 906`), highlight knee (`kneeHi = 3328`), and
     * firmware `Pre1` (`YCbCr -> RGB`) transform.
     */
    private fun synthesizeTonedMonoPixel(
        y: Int,
        offCb: Int,
        offCr: Int,
        kneeLo: Int,
        kneeHi: Int,
        ycbcrToRgb: IntArray,
    ): Triple<Int, Int, Int> {
        val cb: Int
        val cr: Int
        when {
            y <= 0 -> {
                cb = 0
                cr = 0
            }
            y < kneeLo && kneeLo > 0 -> {
                cb = (y * offCb) / kneeLo
                cr = (y * offCr) / kneeLo
            }
            y < kneeHi -> {
                cb = offCb
                cr = offCr
            }
            else -> {
                val span = FilmSim3dLut.MAX_VALUE - kneeHi
                if (span > 0) {
                    val delta = kneeHi - y
                    cb = offCb + (delta * offCb) / span
                    cr = offCr + (delta * offCr) / span
                } else {
                    cb = 0
                    cr = 0
                }
            }
        }
        // Note: in 0x021996b8, r0 = Cr/Ch2 offset and r1 = Cb/Ch1 offset per Pre1 column order.
        val rOut = (y * ycbcrToRgb[0] + cb * ycbcrToRgb[1] + cr * ycbcrToRgb[2]) shr 10
        val gOut = (y * ycbcrToRgb[3] + cb * ycbcrToRgb[4] + cr * ycbcrToRgb[5]) shr 10
        val bOut = (y * ycbcrToRgb[6] + cb * ycbcrToRgb[7] + cr * ycbcrToRgb[8]) shr 10
        return Triple(
            rOut.coerceIn(0, FilmSim3dLut.MAX_VALUE),
            gOut.coerceIn(0, FilmSim3dLut.MAX_VALUE),
            bOut.coerceIn(0, FilmSim3dLut.MAX_VALUE),
        )
    }

    /**
     * Evaluates the relative display-referred tone transfer `simCurve(proviaCurve^-1(x))` using
     * the real 31-knot 14-bit curves (`0..16383`) from `section4.bin 0x017E16A4`.
     */
    private fun applyRelativeToneCurve(
        v12: Int,
        simCurve: IntArray,
        proviaCurve: IntArray,
        sim: FilmSimulation,
    ): Int {
        if (simCurve.contentEquals(proviaCurve) && sim != FilmSimulation.ASTIA && sim != FilmSimulation.PRO_NEG_STD) {
            return v12
        }
        val target14 = v12 * 16383.0 / 4095.0
        val t = invertCurve31(proviaCurve, target14)
        var out14 = evalCurve31(simCurve, t)
        // Subtle shadow/highlight curve differentiation for ASTIA (softer shadows) and PRO Neg. Std
        // (lower midtone contrast) when Bank 14 shares the base curve ID with Provia/PRO Neg. Hi.
        val norm = (out14 / 16383.0).coerceIn(0.0, 1.0)
        if (sim == FilmSimulation.ASTIA) {
            out14 = (norm + 0.03 * kotlin.math.sin(norm * Math.PI) * (1.0 - norm)) * 16383.0
        } else if (sim == FilmSimulation.PRO_NEG_STD) {
            out14 = (norm - 0.035 * kotlin.math.sin(norm * 2.0 * Math.PI) * 0.5) * 16383.0
        }
        return (out14 * 4095.0 / 16383.0).roundToInt().coerceIn(0, FilmSim3dLut.MAX_VALUE)
    }

    private fun evalCurve31(knots: IntArray, tNorm: Double): Double {
        val pos = tNorm.coerceIn(0.0, 1.0) * 30.0
        val idx = pos.toInt().coerceIn(0, 29)
        val frac = pos - idx
        return knots[idx] + (knots[idx + 1] - knots[idx]) * frac
    }

    private fun invertCurve31(knots: IntArray, y14: Double): Double {
        if (y14 <= knots[0]) return 0.0
        if (y14 >= knots[30]) return 1.0
        for (i in 0 until 30) {
            val y0 = knots[i].toDouble()
            val y1 = knots[i + 1].toDouble()
            if (y14 <= y1) {
                val frac = if (y1 > y0) (y14 - y0) / (y1 - y0) else 0.0
                return (i + frac) / 30.0
            }
        }
        return 1.0
    }

    private fun readHardwareLutIndex(section5: ByteArray, bank14: ByteArray, sim: FilmSimulation): Int {
        if (sim.videoLogMode != 0) return 0
        val pid = readU32(section5, (S5_LUT_ID_TABLE_VA - SECTION5_VA) + sim.reccCode * 4)
        return readU8(bank14, pid)
    }

    private fun readToneCurveKey(section5: ByteArray, bank14: ByteArray, sim: FilmSimulation): Int {
        val pid = when (sim.videoLogMode) {
            1 -> B14_FLOG_CURVE_SEL
            2 -> B14_HLG_CURVE_SEL
            else -> readU32(section5, (S5_TONE_CURVE_SEL_TABLE_VA - SECTION5_VA) + sim.reccCode * 4)
        }
        return readU8(bank14, pid)
    }

    private fun readCbCrMatrixQ10(section5: ByteArray, bank14: ByteArray, sim: FilmSimulation): IntArray {
        val selPid = when (sim.videoLogMode) {
            1 -> B14_FLOG_CBCR_SEL
            2 -> B14_HLG_CBCR_SEL
            else -> readU32(section5, (S5_CBCR_SEL_TABLE_VA - SECTION5_VA) + sim.reccCode * 4)
        }
        val matId = readU8(bank14, selPid)
        if (matId !in 1..15) return intArrayOf(1024, 1024, 0, 0)
        val tableOff = (S5_CBCR_MAT_TABLE_VA - SECTION5_VA) + (matId - 1) * 16
        return IntArray(4) { i ->
            val pid = readU32(section5, tableOff + i * 4)
            signExtend13(readU16(bank14, pid))
        }
    }

    private fun readBank14Matrix3x3S13(
        section5: ByteArray,
        bank14: ByteArray,
        tableVa: Int,
        matrixZeroBasedIndex: Int,
    ): IntArray {
        val tableOff = (tableVa - SECTION5_VA) + matrixZeroBasedIndex * 36
        return IntArray(9) { i ->
            val pid = readU32(section5, tableOff + i * 4)
            signExtend13(readU16(bank14, pid))
        }
    }

    private fun readBank14FromDat(datPath: Path): ByteArray {
        Files.newByteChannel(datPath).use { ch ->
            require(ch.size() >= (BANK14_DAT_OFFSET + BANK14_SIZE).toLong()) {
                "DAT file too small to contain Bank 14: ${ch.size()}"
            }
            ch.position(BANK14_DAT_OFFSET.toLong())
            val buf = ByteBuffer.allocate(BANK14_SIZE)
            while (buf.hasRemaining()) {
                val n = ch.read(buf)
                require(n >= 0) { "Unexpected EOF reading Bank 14 from $datPath" }
            }
            val arr = buf.array()
            for (i in arr.indices) arr[i] = arr[i].toInt().inv().toByte()
            return arr
        }
    }

    private fun decodeInvertedRaw(dat: ByteArray, offset: Int, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= dat.size - size) {
            "DAT raw extent out of bounds: offset=$offset size=$size fileLen=${dat.size}"
        }
        return ByteArray(size) { dat[offset + it].toInt().inv().toByte() }
    }

    /** Decodes an `XOR 0xFF` inverted Fujifilm `Method4` compressed section directly from `GXUP0004.DAT`. */
    internal fun decodeInvertedMethod4(dat: ByteArray, offset: Int): ByteArray {
        require(offset >= 0 && offset <= dat.size - 20) { "DAT Method4 header out of bounds: offset=$offset" }
        val hdr = decodeInvertedRaw(dat, offset, 20)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val unpacked = bb.int
        val packed = bb.int
        val pages = bb.int
        val pageSize = bb.int
        val method = bb.int
        require(pageSize == 0x4000 && method == 4 && packed in 20..(dat.size - offset)) {
            "Invalid Method4 header at offset=$offset: packed=$packed pageSize=$pageSize method=$method"
        }
        require(pages.toLong() == (packed.toLong() + 0x3FFF) / 0x4000) { "Method4 page count mismatch" }
        require(unpacked in 0..(64 * 1024 * 1024)) { "Method4 unpacked length out of range: $unpacked" }

        val src = decodeInvertedRaw(dat, offset, packed)
        val out = ByteArray(unpacked)
        var pos = 20
        var count = 0
        while (pos < packed) {
            val cmd = src[pos++].toInt() and 0xFF
            if (cmd < 128) {
                require(cmd > 0 && cmd <= packed - pos && cmd <= unpacked - count) {
                    "Corrupt Method4 literal run at pos=$pos"
                }
                src.copyInto(out, count, pos, pos + cmd)
                pos += cmd
                count += cmd
            } else {
                require(pos < packed) { "Truncated Method4 back-reference" }
                val second = src[pos++].toInt() and 0xFF
                val distance = ((cmd and 127) shl 4) or (second ushr 4)
                val length = second and 15
                require(distance in 1..count && length > 0 && length <= unpacked - count) {
                    "Corrupt Method4 back-reference: dist=$distance len=$length count=$count"
                }
                repeat(length) {
                    out[count] = out[count - distance]
                    count++
                }
            }
        }
        require(count == unpacked) { "Method4 decoded size mismatch: $count != $unpacked" }
        return out
    }

    private fun signExtend13(raw: Int): Int {
        val v = raw and 0x1FFF
        return if ((v and 0x1000) != 0) v - 8192 else v
    }

    private fun readU8(buf: ByteArray, off: Int): Int {
        require(off in buf.indices) { "u8 offset out of range: 0x${off.toString(16)}" }
        return buf[off].toInt() and 0xFF
    }

    private fun readU16(buf: ByteArray, off: Int): Int {
        require(off >= 0 && off + 1 < buf.size) { "u16 offset out of range: 0x${off.toString(16)}" }
        return (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readS16(buf: ByteArray, off: Int): Int = readU16(buf, off).toShort().toInt()

    private fun readU32(buf: ByteArray, off: Int): Int {
        require(off >= 0 && off + 3 < buf.size) { "u32 offset out of range: 0x${off.toString(16)}" }
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }
}
