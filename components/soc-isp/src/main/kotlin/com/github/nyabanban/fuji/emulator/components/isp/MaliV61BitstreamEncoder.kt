package com.github.nyabanban.fuji.emulator.components.isp

import java.io.ByteArrayOutputStream

/**
 * Spec-compliant ITU-T H.264 (AVC) and H.265 (HEVC) NAL bitstream generator for [MaliV61VpuEngine].
 *
 * All generated NAL units begin with the 4-byte Annex-B start code (`00 00 00 01`) and apply
 * RBSP emulation prevention (`00 00 00..03 -> 00 00 03 xx`), matching real ARM Mali-V61 MVE v2.4
 * RASC output (the GFX100S driver overwrites the leading 4 bytes with a big-endian NAL length
 * when `nalu_format == 16` for `.MOV`/`.MP4` muxing at `section4 0x012081a8` & `0x01208258`).
 *
 * - **H.264 (Baseline Profile, CAVLC)**:
 *   - SPS (`0x67`) + PPS (`0x68`) with exact dimensions, 4:2:0 crop offsets (e.g. `1920x1080` -> `120x68` MBs,
 *     `crop_bottom=4`), and VUI timing info matching the firmware's Q16 frame rate.
 *   - IDR and reference P slices use 16x16 DC, textured 4x4 intra blocks and zero-motion P_Skip.
 *     Firmware rate control can spend the picture budget on original samples in I_PCM blocks.
 * - **H.265 (Main / Main10 Profile)**:
 *   - VPS (`0x40 0x01`), SPS (`0x42 0x01`), PPS (`0x44 0x01`) sized within the GFX100S 64-byte
 *     header stride (`0x20000000 + i * 0x40`). [HevcIntraSlice] codes IDR and P pictures with CABAC.
 */
internal object MaliV61BitstreamEncoder {
    internal class BitWriter(nalHeader: ByteArray, initialSize: Int = 64) {
        private val out = ByteArrayOutputStream(initialSize)
        private var cur = 0
        private var bitsInCur = 0
        private var zeros = 0
        val byteCount: Int get() = out.size()

        fun writePcm8(pixels: ShortArray) {
            while (bitsInCur != 0) writeBit(0)
            for (sample in pixels) writePayloadByte(sample.toInt() ushr 2)
        }

        fun writePcm10(pixels: ShortArray) {
            // Four aligned 10-bit samples form five payload bytes before emulation prevention.
            for (i in pixels.indices step 4) {
                val a = pixels[i].toInt()
                val b = pixels[i + 1].toInt()
                val c = pixels[i + 2].toInt()
                val d = pixels[i + 3].toInt()
                writePayloadByte(a ushr 2)
                writePayloadByte(((a and 3) shl 6) or (b ushr 4))
                writePayloadByte(((b and 15) shl 4) or (c ushr 6))
                writePayloadByte(((c and 63) shl 2) or (d ushr 8))
                writePayloadByte(d and 255)
            }
        }

        init {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nalHeader)
        }

        private fun writePayloadByte(value: Int) {
            if (zeros == 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }

        fun writeBit(bit: Int) {
            cur = (cur shl 1) or (bit and 1)
            bitsInCur++
            if (bitsInCur == 8) {
                writePayloadByte(cur)
                cur = 0
                bitsInCur = 0
            }
        }

        fun writeBits(value: Long, count: Int) {
            var remaining = count
            while (remaining > 0) {
                val take = minOf(remaining, 8 - bitsInCur)
                remaining -= take
                cur = (cur shl take) or ((value ushr remaining).toInt() and ((1 shl take) - 1))
                bitsInCur += take
                if (bitsInCur == 8) {
                    writePayloadByte(cur)
                    cur = 0
                    bitsInCur = 0
                }
            }
        }

        fun writeUe(value: Int) {
            require(value >= 0)
            val codeNum = value + 1
            val bits = 32 - Integer.numberOfLeadingZeros(codeNum)
            for (i in 0 until bits - 1) writeBit(0)
            writeBits(codeNum.toLong(), bits)
        }

        fun writeSe(value: Int) {
            val codeNum = if (value > 0) (value shl 1) - 1 else (-value) shl 1
            writeUe(codeNum)
        }

        fun writeRbspTrailingBits() {
            writeBit(1)
            while (bitsInCur != 0) writeBit(0)
        }

        fun toAnnexBNal(): ByteArray {
            if (bitsInCur != 0) {
                writePayloadByte(cur shl (8 - bitsInCur))
                cur = 0
                bitsInCur = 0
            }
            return out.toByteArray()
        }
    }

    private fun writeVideoSignalType(bw: BitWriter, fullRange: Boolean) {
        bw.writeBit(if (fullRange) 1 else 0)
        if (fullRange) {
            bw.writeBits(5, 3) // unspecified camera video format
            bw.writeBit(1) // video_full_range_flag
            bw.writeBit(0) // preserve the container's colour description
        }
    }

    /** H.264 SPS (`0x67`, <= 40 bytes, fits inside 64-byte GFX100S header buffer slot). */
    fun buildH264Sps(width: Int, height: Int, fpsQ16: Long, fullRange: Boolean = false): ByteArray {
        val w = width.coerceIn(16, 4096)
        val h = height.coerceIn(16, 2160)
        val mbW = (w + 15) / 16
        val mbH = (h + 15) / 16
        val levelIdc = when {
            w > 1920 || h > 1088 -> 51 // Level 5.1 (4K/DCI 4K)
            else -> 42 // Level 4.2 (1080p60)
        }
        val bw = BitWriter(byteArrayOf(0x67))
        bw.writeBits(66, 8) // profile_idc = 66 (Baseline)
        bw.writeBits(0xC0, 8) // constraint_set0_flag=1, constraint_set1_flag=1
        bw.writeBits(levelIdc.toLong(), 8)
        bw.writeUe(0) // seq_parameter_set_id
        bw.writeUe(0) // log2_max_frame_num_minus4 (4-bit frame_num)
        bw.writeUe(2) // pic_order_cnt_type = 2 (no POC fields in slice header)
        bw.writeUe(1) // max_num_ref_frames
        bw.writeBit(0) // gaps_in_frame_num_value_allowed_flag
        bw.writeUe(mbW - 1)
        bw.writeUe(mbH - 1)
        bw.writeBit(1) // frame_mbs_only_flag
        bw.writeBit(1) // direct_8x8_inference_flag
        val cropRight = (mbW * 16 - w) / 2
        val cropBottom = (mbH * 16 - h) / 2
        if (cropRight > 0 || cropBottom > 0) {
            bw.writeBit(1) // frame_cropping_flag
            bw.writeUe(0) // left
            bw.writeUe(cropRight)
            bw.writeUe(0) // top
            bw.writeUe(cropBottom)
        } else {
            bw.writeBit(0)
        }
        // VUI with timing_info_present_flag = 1 for accurate container fps metadata
        bw.writeBit(1) // vui_parameters_present_flag
        bw.writeBit(0) // aspect_ratio_info_present_flag
        bw.writeBit(0) // overscan_info_present_flag
        writeVideoSignalType(bw, fullRange)
        bw.writeBit(0) // chroma_loc_info_present_flag
        bw.writeBit(1) // timing_info_present_flag
        // One progressive frame spans two H.264 clock units. Use the actual ISP period.
        val period = MaliV61VpuEngine.framePeriodTicksForFpsQ16(fpsQ16)
        bw.writeBits(period, 32)
        bw.writeBits(2 * MaliV61VpuEngine.CLOCK_HZ, 32)
        bw.writeBit(1) // fixed_frame_rate_flag
        bw.writeBit(0) // nal_hrd_parameters_present_flag
        bw.writeBit(0) // vcl_hrd_parameters_present_flag
        bw.writeBit(0) // pic_struct_present_flag
        bw.writeBit(0) // bitstream_restriction_flag
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    /** H.264 PPS (`0x68`, 8 bytes). */
    fun buildH264Pps(): ByteArray {
        val bw = BitWriter(byteArrayOf(0x68))
        bw.writeUe(0) // pic_parameter_set_id
        bw.writeUe(0) // seq_parameter_set_id
        bw.writeBit(0) // entropy_coding_mode_flag = 0 (CAVLC)
        bw.writeBit(0) // bottom_field_pic_order_in_frame_present_flag
        bw.writeUe(0) // num_slice_groups_minus1
        bw.writeUe(0) // num_ref_idx_l0_default_active_minus1
        bw.writeUe(0) // num_ref_idx_l1_default_active_minus1
        bw.writeBit(0) // weighted_pred_flag
        bw.writeBits(0, 2) // weighted_bipred_idc
        bw.writeSe(0) // pic_init_qp_minus26 (QP = 26)
        bw.writeSe(0) // pic_init_qs_minus26
        bw.writeSe(0) // chroma_qp_index_offset
        bw.writeBit(0) // deblocking_filter_control_present_flag
        bw.writeBit(0) // constrained_intra_pred_flag
        bw.writeBit(0) // redundant_pic_cnt_present_flag
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    /**
     * Encode an ITU-T H.264 IDR slice (`0x65`) using `I_16x16_2_0_0` / `I_16x16_2_1_0` CAVLC macroblocks.
     *
     * When [mbLumaAvg] (length `mbW * mbH`, values `0..255`) is non-null, encodes the quantized
     * per-macroblock DC delta into `Intra16x16DCLevel[0]` (and optional [mbCbAvg]/[mbCrAvg] into
     * `ChromaDCLevel[0]`) using spec-exact CAVLC tables (`nC = 0` for luma DC, `nC = -1` for chroma DC).
     */
    fun buildH264IdrSlice(
        width: Int,
        height: Int,
        idrPicId: Int,
        mbLumaAvg: ByteArray? = null,
        mbCbAvg: ByteArray? = null,
        mbCrAvg: ByteArray? = null,
        luma4x4: ByteArray? = null,
    ): ByteArray = buildH264Slice(width, height, idrPicId, mbLumaAvg, mbCbAvg, mbCrAvg, luma4x4, null, null, 0, null, 0, false)

    fun buildH264Picture(current: AvcReferenceFrame, previous: AvcReferenceFrame?, idrPicId: Int, frameNum: Int,
                         pcm: MvePcmSource? = null, targetBytes: Int = 0, constantRate: Boolean = false): ByteArray =
        buildH264Slice(current.width, current.height, idrPicId, current.y, current.cb, current.cr, current.detail,
            current, previous, frameNum, pcm, targetBytes, constantRate)

    private fun buildH264Slice(
        width: Int, height: Int, idrPicId: Int,
        mbLumaAvg: ByteArray?, mbCbAvg: ByteArray?, mbCrAvg: ByteArray?, luma4x4: ByteArray?,
        current: AvcReferenceFrame?, previous: AvcReferenceFrame?, frameNum: Int,
        pcm: MvePcmSource?, targetBytes: Int, constantRate: Boolean,
    ): ByteArray {
        val w = width.coerceIn(16, 4096)
        val h = height.coerceIn(16, 2160)
        val mbW = (w + 15) / 16
        val mbH = (h + 15) / 16
        val totalMbs = mbW * mbH

        val predictive = previous != null
        val initialBytes = if (constantRate && targetBytes > 0) {
            // A constant-rate picture normally ends within a few PCM blocks of its budget.
            minOf(targetBytes.toLong() + 4096, totalMbs * 512L).toInt()
        } else totalMbs * 4
        val bw = BitWriter(byteArrayOf(if (predictive) 0x41 else 0x65), initialBytes)
        bw.writeUe(0) // first_mb_in_slice
        bw.writeUe(if (predictive) 5 else 7) // P or I slice
        bw.writeUe(0) // pic_parameter_set_id
        bw.writeBits(if (predictive) (frameNum and 15).toLong() else 0L, 4)
        if (predictive) {
            bw.writeBit(0) // num_ref_idx_active_override_flag: one reference
            bw.writeBit(0) // ref_pic_list_modification_flag_l0
            bw.writeBit(0) // adaptive_ref_pic_marking_mode_flag
        } else {
            bw.writeUe(idrPicId and 0xFFFF)
            bw.writeBit(0) // no_output_of_prior_pics_flag
            bw.writeBit(0) // long_term_reference_flag
        }
        // slice_qp_delta: set QP = 24 (pic_init_qp = 26 -> slice_qp_delta = -2)
        // At QP = 24: luma DC step = (160/4)/64 = 0.625 -> 1 unit ~= 1 luma step;
        // chroma DC step = (160*16/32)/64 = 1.25 -> 1 unit ~= 1.25 chroma step.
        bw.writeSe(-2)

        val hasLuma = mbLumaAvg != null && mbLumaAvg.size >= totalMbs
        val hasChroma = hasLuma && mbCbAvg != null && mbCrAvg != null &&
            mbCbAvg.size >= totalMbs && mbCrAvg.size >= totalMbs

        // Track reconstructed per-MB average Y/Cb/Cr to compute exact Intra_16x16_DC predictions
        // Each column holds the last decoded value above it until this row overwrites it.
        val topLuma = IntArray(mbW * 4)
        val leftLuma = IntArray(4)
        val levels4x4 = IntArray(16)
        val recCb = IntArray(mbW)
        val recCr = IntArray(mbW)
        var skipRun = 0
        val pcmPixels = if (pcm != null) ShortArray(384) else null
        val chroma = if (pcm != null) AvcChromaPrediction(mbW) else null
        val pcmAbove = BooleanArray(mbW)

        for (my in 0 until mbH) {
            val rowOff = my * mbW
            var pcmLeft = false
            for (mx in 0 until mbW) {
                val idx = rowOff + mx
                val firstCol = mx * 4
                val firstRow = my * 4
                val edge = idx * 10
                val detailOffset = firstRow * mbW * 4 + firstCol
                val targetY = if (hasLuma) (mbLumaAvg[idx].toInt() and 255) else 128
                val hasDetail = luma4x4 != null && (0 until 16).any { block ->
                    val offset = detailOffset + block / 4 * mbW * 4 + block % 4
                    kotlin.math.abs((luma4x4[offset].toInt() and 255) - targetY) >= 4
                }
                val byteTargetHere = targetBytes.toLong() * (idx + 1) / totalMbs
                val canSpend = bw.byteCount + 385 <= byteTargetHere
                val priorPcm = previous?.pcmBlocks?.get(idx)
                val raw = pcmPixels?.takeIf {
                    (priorPcm != null || canSpend) && pcm?.readBlock(mx, my, it) == true
                }
                val rawDetail = !constantRate && raw != null && luma4x4 != null &&
                    AvcReferenceFrame.hasFineDetail(raw, luma4x4, detailOffset, mbW * 4)
                val promote = priorPcm == null && canSpend && rawDetail
                val accurateReference = priorPcm == null || (raw != null && AvcReferenceFrame.matchesPcm(raw, priorPcm))
                if (current != null && previous != null && accurateReference && !promote && current.matches(previous, idx, detailOffset)) {
                    // P_Skip uses the colocated reference block (all motion vector predictors are zero).
                    previous.edges.copyInto(current.edges, edge, edge, edge + 10)
                    current.pcmBlocks?.set(idx, priorPcm)
                    for (col in 0 until 4) {
                        topLuma[firstCol + col] = previous.edges[edge + col].toInt() and 255
                        leftLuma[col] = previous.edges[edge + 4 + col].toInt() and 255
                    }
                    recCb[mx] = previous.edges[edge + 8].toInt() and 255
                    recCr[mx] = previous.edges[edge + 9].toInt() and 255
                    chroma?.restore(mx, idx, previous)
                    chroma?.save(mx, idx, current)
                    pcmAbove[mx] = false
                    pcmLeft = false
                    skipRun++
                    continue
                }
                if (predictive) {
                    bw.writeUe(skipRun)
                    skipRun = 0
                }
                val wasPcmAbove = pcmAbove[mx]
                val wasPcmLeft = pcmLeft
                // PCM carries all original samples at the selected bitrate, including detail below the 4x4 grid.
                val pixels = raw.takeIf { canSpend && (constantRate || hasDetail || rawDetail) }
                pcmAbove[mx] = pixels != null
                pcmLeft = pixels != null
                if (pixels != null) {
                    current?.pcmBlocks?.set(idx, ByteArray(pixels.size) { (pixels[it].toInt() ushr 2).toByte() })
                    bw.writeUe(if (predictive) 30 else 25) // I_PCM macroblock
                    bw.writePcm8(pixels)
                    for (i in 0 until 4) {
                        var bottom = 0
                        var right = 0
                        for (j in 0 until 4) {
                            bottom += pixels[240 + i * 4 + j].toInt() ushr 2
                            right += pixels[(i * 4 + j) * 16 + 15].toInt() ushr 2
                        }
                        topLuma[firstCol + i] = (bottom + 2) / 4
                        leftLuma[i] = (right + 2) / 4
                    }
                    var cbSum = 0
                    var crSum = 0
                    for (i in 0 until 64) {
                        cbSum += pixels[256 + i].toInt() ushr 2
                        crSum += pixels[320 + i].toInt() ushr 2
                    }
                    recCb[mx] = (cbSum + 32) / 64
                    recCr[mx] = (crSum + 32) / 64
                    chroma?.raw(mx, pixels)
                    chroma?.save(mx, idx, current)
                    current?.saveEdges(idx, topLuma, firstCol, leftLuma, recCb[mx], recCr[mx])
                    continue
                }
                var dcY = 0
                if (hasDetail) {
                    for (block in 0 until 16) {
                        // 8x8 raster order, then the four 4x4 blocks within each group.
                        val bx = ((block / 4) % 2) * 2 + block % 2
                        val by = (block / 8) * 2 + (block / 2) % 2
                        val x = firstCol + bx
                        val y = firstRow + by
                        val prediction = when {
                            x == 0 && y == 0 -> 128
                            x == 0 -> topLuma[x]
                            y == 0 -> leftLuma[by]
                            else -> (topLuma[x] + leftLuma[by] + 1) / 2
                        }
                        val sample = luma4x4[y * mbW * 4 + x].toInt() and 255
                        // QP 24 inverse quantization gives a 2.5-code-value step for 4x4 DC.
                        val level = ((sample - prediction) * 2) / 5
                        val reconstructed = (prediction + ((level * 160 + 32) shr 6)).coerceIn(0, 255)
                        levels4x4[block] = level
                        topLuma[x] = reconstructed
                        leftLuma[by] = reconstructed
                    }
                } else {
                    val topSum = topLuma[firstCol] + topLuma[firstCol + 1] + topLuma[firstCol + 2] + topLuma[firstCol + 3]
                    val leftSum = leftLuma.sum()
                    val prediction = when {
                        mx == 0 && my == 0 -> 128
                        mx == 0 -> (topSum + 2) / 4
                        my == 0 -> (leftSum + 2) / 4
                        else -> (topSum + leftSum + 4) / 8
                    }
                    dcY = ((targetY - prediction) * 64) / 40
                    val reconstructed = (prediction + ((dcY * 40 + 32) shr 6)).coerceIn(0, 255)
                    topLuma.fill(reconstructed, firstCol, firstCol + 4)
                    leftLuma.fill(reconstructed)
                }

                var dcCb = 0
                var dcCr = 0
                // With uniform reconstructed borders, horizontal or vertical prediction gives
                // each 4x4 chroma quadrant the same predictor. AVC DC would mix both edges
                // differently in all four quadrants even if each edge is uniform.
                val chromaMode = chroma?.mode(mx, my) ?: when {
                    mx > 0 -> 1
                    my > 0 -> 2
                    else -> 0
                }
                if (hasChroma) {
                    val predCb = chroma?.mean(mx, 0, chromaMode) ?: uniformChromaPrediction(recCb, mx, chromaMode)
                    val predCr = chroma?.mean(mx, 1, chromaMode) ?: uniformChromaPrediction(recCr, mx, chromaMode)
                    val targetCb = mbCbAvg[idx].toInt() and 0xFF
                    val targetCr = mbCrAvg[idx].toInt() and 0xFF
                    dcCb = ((targetCb - predCb) * 64) / 80
                    dcCr = ((targetCr - predCr) * 64) / 80
                    val actCb = (dcCb * 80 + 32) shr 6
                    val actCr = (dcCr * 80 + 32) shr 6
                    recCb[mx] = (predCb + actCb).coerceIn(0, 255)
                    recCr[mx] = (predCr + actCr).coerceIn(0, 255)
                    chroma?.reconstruct(mx, 0, chromaMode, actCb)
                    chroma?.reconstruct(mx, 1, chromaMode, actCr)
                } else {
                    recCb[mx] = 128
                    recCr[mx] = 128
                }

                val encodeChromaDc = dcCb != 0 || dcCr != 0
                current?.saveEdges(idx, topLuma, firstCol, leftLuma, recCb[mx], recCr[mx])
                chroma?.save(mx, idx, current)
                val intraTypeBase = if (predictive) 5 else 0
                if (hasDetail) {
                    bw.writeUe(intraTypeBase) // I_NxN, transform_8x8_mode_flag disabled
                    bw.writeBits(0xffff, 16) // all sixteen blocks use their predicted intra DC mode
                    bw.writeUe(chromaMode)
                    bw.writeUe(if (encodeChromaDc) 1 else 2) // intra CBP 31 or 15
                    bw.writeSe(0)
                    for (block in levels4x4.indices) {
                        val bx = ((block / 4) % 2) * 2 + block % 2
                        val by = (block / 8) * 2 + (block / 2) % 2
                        writeCavlcSingleDc0(bw, levels4x4[block], false, (bx == 0 && wasPcmLeft) || (by == 0 && wasPcmAbove))
                    }
                } else {
                    bw.writeUe(intraTypeBase + if (encodeChromaDc) 7 else 3) // Intra16x16 DC
                    bw.writeUe(chromaMode)
                    bw.writeSe(0)
                    writeCavlcSingleDc0(bw, dcY, false, wasPcmAbove || wasPcmLeft)
                }

                if (encodeChromaDc) {
                    // ChromaDCLevel Cb (4 coeffs, nC = -1)
                    writeCavlcSingleDc0(bw, dcCb, isChromaDc = true)
                    // ChromaDCLevel Cr (4 coeffs, nC = -1)
                    writeCavlcSingleDc0(bw, dcCr, isChromaDc = true)
                }
            }
        }

        if (skipRun > 0) bw.writeUe(skipRun)
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    private fun uniformChromaPrediction(edge: IntArray, column: Int, mode: Int): Int = when (mode) {
        1 -> edge[column - 1] // left macroblock
        2 -> edge[column] // above
        else -> 128 // upper-left picture boundary
    }

    /**
     * Write CAVLC residual_block for a block with at most ONE non-zero coefficient at index 0.
     *
     * Verified against ITU-T H.264 Tables 9-5 (coeff_token) & 9-7 / 9-9 (total_zeros):
     * - Luma 16-coeff block (`nC = 0`):
     *   - `(TotalCoeff=0, TrailingOnes=0)`: `1` (1 bit)
     *   - `(TotalCoeff=1, TrailingOnes=1)`: `01` (2 bits) + sign bit (`0`=+1, `1`=-1) + `total_zeros(0)` = `1` (1 bit)
     *   - `(TotalCoeff=1, TrailingOnes=0)`: `000101` (6 bits) + level_prefix/suffix (`suffixLength=0`) + `total_zeros(0)` = `1` (1 bit)
     * - Chroma 4-coeff DC block (`nC = -1`):
     *   - `(TotalCoeff=0, TrailingOnes=0)`: `01` (2 bits)
     *   - `(TotalCoeff=1, TrailingOnes=1)`: `1` (1 bit) + sign bit + `total_zeros_4x2(0)` = `1` (1 bit)
     *   - `(TotalCoeff=1, TrailingOnes=0)`: `000111` (6 bits) + level (`suffixLength=0`) + `total_zeros_4x2(0)` = `1` (1 bit)
     */
    private fun writeCavlcSingleDc0(bw: BitWriter, level: Int, isChromaDc: Boolean, pcmNeighbour: Boolean = false) {
        if (level == 0) {
            if (pcmNeighbour) {
                bw.writeBits(3, 6) // nC >= 8: fixed-length coeff_token for TotalCoeff=0
            } else if (isChromaDc) {
                bw.writeBits(0b01, 2)
            } else {
                bw.writeBit(1)
            }
            return
        }
        val absLevel = if (level < 0) -level else level
        val signBit = if (level < 0) 1 else 0
        if (absLevel == 1) {
            // TotalCoeff = 1, TrailingOnes = 1
            if (pcmNeighbour) {
                bw.writeBits(1, 6) // TotalCoeff=1, TrailingOnes=1
            } else if (isChromaDc) {
                bw.writeBit(1)
            } else {
                bw.writeBits(0b01, 2)
            }
            bw.writeBit(signBit)
        } else {
            // TotalCoeff = 1, TrailingOnes = 0
            if (pcmNeighbour) {
                bw.writeBits(0, 6) // TotalCoeff=1, TrailingOnes=0
            } else if (isChromaDc) {
                bw.writeBits(0b000111, 6)
            } else {
                bw.writeBits(0b000101, 6)
            }
            // Level coding with suffixLength = 0 and TrailingOnes < 3 (Table 9-6):
            // levelCode = (absLevel * 2 - 2) if positive, (absLevel * 2 - 1) if negative;
            // since TrailingOnes == 0 and this is the first coeff, no -2 adjustment is applied?
            // Per ITU-T H.264 9.2.2: when i == TrailingOnes and TrailingOnes < 3, decoder adds 2 to levelCode!
            // Therefore encoder subtracts 2 from levelCode (so absLevel == 2 -> adjCode = 0 for +2, 1 for -2).
            val rawLevelCode = (absLevel shl 1) - 2 + signBit
            val adjCode = rawLevelCode - 2 // >= 0 since absLevel >= 2
            if (adjCode < 14) {
                // level_prefix = adjCode zeros followed by 1; level_suffix absent (suffixLength == 0)
                for (z in 0 until adjCode) bw.writeBit(0)
                bw.writeBit(1)
            } else if (adjCode < 30) {
                // Escape prefix 14: 14 zeros + 1 + 4-bit suffix (adjCode - 14)
                for (z in 0 until 14) bw.writeBit(0)
                bw.writeBit(1)
                bw.writeBits((adjCode - 14).toLong(), 4)
            } else {
                // Escape prefix 15: 15 zeros + 1 + 12-bit suffix (adjCode - 30)
                for (z in 0 until 15) bw.writeBit(0)
                bw.writeBit(1)
                bw.writeBits((adjCode - 30).toLong(), 12)
            }
        }
        // total_zeros = 0 (Table 9-7 for TotalCoeff=1 -> `1`; Table 9-9a for ChromaDC TotalCoeff=1 -> `1`)
        bw.writeBit(1)
    }

    /** H.265 VPS (`0x40 0x01`, <= 32 bytes, fits inside 64-byte GFX100S header buffer slot). */
    fun buildH265Vps(bitDepth10: Boolean): ByteArray {
        val bw = BitWriter(byteArrayOf(0x40, 0x01))
        bw.writeBits(0, 4) // vps_video_parameter_set_id
        bw.writeBit(1) // vps_base_layer_internal_flag
        bw.writeBit(1) // vps_base_layer_available_flag
        bw.writeBits(0, 6) // vps_max_layers_minus1
        bw.writeBits(0, 3) // vps_max_sub_layers_minus1
        bw.writeBit(1) // vps_temporal_id_nesting_flag
        bw.writeBits(0xFFFF, 16) // vps_reserved_0xffff_16bits
        writeH265ProfileTierLevel(bw, bitDepth10)
        bw.writeBit(0) // vps_sub_layer_ordering_info_present_flag
        bw.writeUe(1) // vps_max_dec_pic_buffering_minus1[0]
        bw.writeUe(0) // vps_max_num_reorder_pics[0]
        bw.writeUe(0) // vps_max_latency_increase_plus1[0]
        bw.writeBits(0, 6) // vps_max_layer_id
        bw.writeUe(0) // vps_num_layer_sets_minus1
        bw.writeBit(0) // vps_timing_info_present_flag
        bw.writeBit(0) // vps_extension_flag
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    /** H.265 SPS (`0x42 0x01`, <= 48 bytes, fits inside 64-byte GFX100S header buffer slot). */
    fun buildH265Sps(
        width: Int, height: Int, bitDepth10: Boolean, fpsQ16: Long = 3_928_227L,
        fullRange: Boolean = false, pcmEnabled: Boolean = false,
    ): ByteArray {
        val w = width.coerceIn(16, 4096)
        val h = height.coerceIn(16, 2160)
        // Complete 16x16 coding tree units; crop padding with the conformance window.
        val codedW = (w + 15) and 15.inv()
        val codedH = (h + 15) and 15.inv()
        val bw = BitWriter(byteArrayOf(0x42, 0x01))
        bw.writeBits(0, 4) // sps_video_parameter_set_id
        bw.writeBits(0, 3) // sps_max_sub_layers_minus1
        bw.writeBit(1) // sps_temporal_id_nesting_flag
        writeH265ProfileTierLevel(bw, bitDepth10)
        bw.writeUe(0) // sps_seq_parameter_set_id
        bw.writeUe(1) // chroma_format_idc = 1 (4:2:0)
        bw.writeUe(codedW)
        bw.writeUe(codedH)
        val cropR = (codedW - w) / 2
        val cropB = (codedH - h) / 2
        if (cropR > 0 || cropB > 0) {
            bw.writeBit(1)
            bw.writeUe(0)
            bw.writeUe(cropR)
            bw.writeUe(0)
            bw.writeUe(cropB)
        } else {
            bw.writeBit(0)
        }
        val bdMinus8 = if (bitDepth10) 2 else 0
        bw.writeUe(bdMinus8) // bit_depth_luma_minus8
        bw.writeUe(bdMinus8) // bit_depth_chroma_minus8
        bw.writeUe(4) // log2_max_pic_order_cnt_lsb_minus4
        bw.writeBit(0) // sps_sub_layer_ordering_info_present_flag
        bw.writeUe(1) // sps_max_dec_pic_buffering_minus1[0]
        bw.writeUe(0) // sps_max_num_reorder_pics[0]
        bw.writeUe(0) // sps_max_latency_increase_plus1[0]
        bw.writeUe(0) // log2_min_luma_coding_block_size_minus3 (MinCbSizeY = 8)
        bw.writeUe(1) // log2_diff_max_min_luma_coding_block_size (CtbSizeY = 16)
        bw.writeUe(0) // log2_min_luma_transform_block_size_minus2 (4)
        bw.writeUe(2) // log2_diff_max_min_luma_transform_block_size (16)
        bw.writeUe(0) // max_transform_hierarchy_depth_inter
        bw.writeUe(1) // one split into four 8x8 transforms for textured 16x16 CUs
        bw.writeBit(0) // scaling_list_enabled_flag
        bw.writeBit(0) // amp_enabled_flag
        bw.writeBit(0) // sample_adaptive_offset_enabled_flag
        bw.writeBit(if (pcmEnabled) 1 else 0)
        if (pcmEnabled) {
            val bitsMinusOne = if (bitDepth10) 9L else 7L
            bw.writeBits(bitsMinusOne, 4) // pcm_sample_bit_depth_luma_minus1
            bw.writeBits(bitsMinusOne, 4) // pcm_sample_bit_depth_chroma_minus1
            bw.writeUe(1) // log2_min_pcm_luma_coding_block_size_minus3 (16x16)
            bw.writeUe(0) // log2_diff_max_min_pcm_luma_coding_block_size
            bw.writeBit(1) // pcm_loop_filter_disabled_flag
        }
        bw.writeUe(0) // num_short_term_ref_pic_sets
        bw.writeBit(0) // long_term_ref_pics_present_flag
        bw.writeBit(0) // sps_temporal_mvp_enabled_flag
        bw.writeBit(0) // strong_intra_smoothing_enabled_flag
        bw.writeBit(1) // vui_parameters_present_flag
        bw.writeBits(0, 2) // aspect_ratio_info_present_flag, overscan_info_present_flag
        writeVideoSignalType(bw, fullRange)
        bw.writeBits(0, 5) // chroma location, neutral chroma, two field flags, display window
        bw.writeBit(1) // vui_timing_info_present_flag
        bw.writeBits(MaliV61VpuEngine.framePeriodTicksForFpsQ16(fpsQ16), 32)
        bw.writeBits(MaliV61VpuEngine.CLOCK_HZ, 32)
        bw.writeBit(0) // vui_poc_proportional_to_timing_flag
        bw.writeBit(0) // vui_hrd_parameters_present_flag
        bw.writeBit(0) // bitstream_restriction_flag
        bw.writeBit(0) // sps_extension_present_flag
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    /** H.265 PPS (`0x44 0x01`, <= 16 bytes). */
    fun buildH265Pps(): ByteArray {
        val bw = BitWriter(byteArrayOf(0x44, 0x01))
        bw.writeUe(0) // pps_pic_parameter_set_id
        bw.writeUe(0) // pps_seq_parameter_set_id
        bw.writeBit(0) // dependent_slice_segments_enabled_flag
        bw.writeBit(0) // output_flag_present_flag
        bw.writeBits(0, 3) // num_extra_slice_header_bits
        bw.writeBit(0) // sign_data_hiding_enabled_flag
        bw.writeBit(0) // cabac_init_present_flag
        bw.writeUe(0) // num_ref_idx_l0_default_active_minus1
        bw.writeUe(0) // num_ref_idx_l1_default_active_minus1
        bw.writeSe(0) // init_qp_minus26
        bw.writeBit(0) // constrained_intra_pred_flag
        bw.writeBit(0) // transform_skip_enabled_flag
        bw.writeBit(0) // cu_qp_delta_enabled_flag
        bw.writeSe(0) // pps_cb_qp_offset
        bw.writeSe(0) // pps_cr_qp_offset
        bw.writeBit(0) // pps_slice_chroma_qp_offsets_present_flag
        bw.writeBit(0) // weighted_pred_flag
        bw.writeBit(0) // weighted_bipred_flag
        bw.writeBit(0) // transquant_bypass_enabled_flag
        bw.writeBit(0) // tiles_enabled_flag
        bw.writeBit(0) // entropy_coding_sync_enabled_flag
        bw.writeBit(1) // pps_loop_filter_across_slices_enabled_flag
        bw.writeBit(1) // deblocking_filter_control_present_flag
        bw.writeBit(0) // deblocking_filter_override_enabled_flag
        bw.writeBit(1) // pps_deblocking_filter_disabled_flag
        bw.writeBit(0) // pps_scaling_list_data_present_flag
        bw.writeBit(0) // lists_modification_present_flag
        bw.writeUe(0) // log2_parallel_merge_level_minus2
        bw.writeBit(0) // slice_segment_header_extension_present_flag
        bw.writeBit(0) // pps_extension_present_flag
        bw.writeRbspTrailingBits()
        return bw.toAnnexBNal()
    }

    /** Main/Main10 intra picture. Each 16x16 unit consumes the matching live macroblock sample. */
    fun buildH265IdrSlice(
        width: Int, height: Int,
        luma: ByteArray? = null, cb: ByteArray? = null, cr: ByteArray? = null,
        fineLuma: ByteArray? = null, bitDepth10: Boolean = false,
    ): ByteArray = buildH265Picture(
        HevcReferenceFrame(width, height, luma, cb, cr, fineLuma, bitDepth10, keepReference = false), null, 0,
    )

    fun buildH265Picture(
        frame: HevcReferenceFrame, previous: HevcReferenceFrame?, pictureOrderCount: Int,
        pcm: MvePcmSource? = null, targetBytes: Int = 0, constantRate: Boolean = true,
    ): ByteArray {
        val idr = previous == null
        require(previous == null || (previous.width == frame.width && previous.height == frame.height &&
            previous.bitDepth10 == frame.bitDepth10 && previous.borders != null))
        val bw = BitWriter(if (idr) byteArrayOf(0x26, 0x01) else byteArrayOf(0x02, 0x01)) // IDR_W_RADL or TRAIL_R
        bw.writeBit(1) // first_slice_segment_in_pic_flag
        if (idr) bw.writeBit(0) // no_output_of_prior_pics_flag
        bw.writeUe(0) // slice_pic_parameter_set_id
        bw.writeUe(if (idr) 2 else 1)
        if (!idr) {
            bw.writeBits((pictureOrderCount and 255).toLong(), 8)
            bw.writeBit(0) // short_term_ref_pic_set_sps_flag
            bw.writeUe(1) // num_negative_pics
            bw.writeUe(0) // num_positive_pics
            bw.writeUe(0) // previous displayed picture (delta_poc_s0_minus1)
            bw.writeBit(1) // used_by_curr_pic_s0_flag
            bw.writeBit(0) // num_ref_idx_active_override_flag
            bw.writeUe(4) // five_minus_max_num_merge_cand: only zero-motion L0 is needed
        }
        bw.writeSe(2) // QP 28 gives an integer DC reconstruction step at both supported bit depths.
        bw.writeRbspTrailingBits() // byte_alignment()
        HevcIntraSlice.write(bw, frame, previous, pcm, targetBytes, constantRate)
        return bw.toAnnexBNal()
    }

    private fun writeH265ProfileTierLevel(bw: BitWriter, bitDepth10: Boolean) {
        val profileIdc = if (bitDepth10) 2 else 1 // 2 = Main 10, 1 = Main
        bw.writeBits(0, 2) // general_profile_space
        bw.writeBit(0) // general_tier_flag
        bw.writeBits(profileIdc.toLong(), 5) // general_profile_idc
        // general_profile_compatibility_flag[32]
        val compat = 1L shl (31 - profileIdc)
        bw.writeBits(compat, 32)
        bw.writeBit(1) // general_progressive_source_flag
        bw.writeBit(0) // general_interlaced_source_flag
        bw.writeBit(1) // general_non_packed_constraint_flag
        bw.writeBit(1) // general_frame_only_constraint_flag
        bw.writeBits(0, 44) // general_reserved_zero_44bits
        bw.writeBits(153, 8) // general_level_idc = 153 (Level 5.1)
    }
}
