package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.NativeCodecBridge
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.logging.Logger
import kotlin.math.abs

/**
 * Real-silicon Fujifilm `LSRAW` (`0xFFFA2000..0xFFFA2147`, GIC IRQ `137`) hardware
 * Lossless & Compressed Bayer RAW codec engine.
 *
 * Register geometry, 34-bit AXI DMA addressing, and header/strip layout verified from
 * GFX100S 2.13 `section5.bin` (`0x021E68CC..0x021E7DC0`, `0x02297B64..0x022986B0`,
 * `0x022B944C..0x022B97C0`) and live hardware register capture:
 * - `0xFFFA2080`: `[8]` mode (`1`=Lossless, `0`=Compressed), `[4]` CFA (`0`=Bayer), `[1:0]` depth (`0`=12b, `1`=14b, `2`=16b)
 * - `0xFFFA2084`: `[15:0]` input line stride in pixels (`rowBytes >> 1`, e.g. `11808`)
 * - `0xFFFA2088`: `[30:16]` padded height `H` (`% 6 == 0`), `[14:0]` padded width in pixels (`% 768 == 0`, e.g. `12288`)
 * - `0xFFFA208C`: `[27:16]` `(H / 6) - 1`, `[4:0]` `(W_pad / 768) - 1` (`blocksX - 1`, e.g. `15` for 16 strips)
 * - `0xFFFA2090`: `[30:16]` active height `H`, `[14:0]` active width in pixels (`11808`)
 * - `0xFFFA209C`: max compressed output buffer byte capacity
 * - `0xFFFA20A4`: compressed output byte count readback (ISR `0x021E7CD8`)
 * - `0xFFF4A750[1:0] | 0xFFFA2144`: 34-bit SRC DMA physical address (Encode: RAW Bayer; Decode: compressed stream)
 * - `0xFFF4A754[1:0] | 0xFFFA2140`: 34-bit DST DMA physical address (Encode: compressed stream; Decode: RAW Bayer)
 *
 * Output container follows Fujifilm's 16-byte big-endian `"IS"` header + 16-byte-aligned
 * `blocksX * u32be` strip-size table (`(((blocksX - 1) >> 2) + 2) << 4` bytes, verified by
 * `section5 @ 0x022B95CC..0x022B95D8`) followed by `blocksX` independent Bayer 2x2
 * gradient-adaptive Golomb-Rice strips (Fujifilm Bayer type 0).
 */
internal object LsrawCodecEngine {
    private val log: Logger = Logger.getLogger(LsrawCodecEngine::class.java.name)

    private const val FUJI_MAGIC_HI = 0x49 // 'I'
    private const val FUJI_MAGIC_LO = 0x53 // 'S'
    private const val FUJI_VERSION = 1
    private const val FUJI_RAW_TYPE_BAYER = 0
    private const val DEFAULT_STRIP_PIXELS = 768
    private const val BLOCK_LINES = 6
    private const val MIN_VALUE = 0x40
    private const val MAX_AXI34_PA_END = 0x4_0000_0000L // 34-bit AXI address space (0xFFF4A750/54[1:0] << 32 | u32)
    private const val DMA_CHUNK_BYTES = 16 * 1024 * 1024 // Unicorn per-call DMA cap (16 MiB)

    private fun readDmaChunked(memory: DmaMemory, pa: Long, dst: MemorySegment, totalBytes: Long) {
        var off = 0L
        while (off < totalBytes) {
            val len = minOf(DMA_CHUNK_BYTES.toLong(), totalBytes - off)
            memory.read(pa + off, dst.asSlice(off, len).asByteBuffer())
            off += len
        }
    }

    private fun writeDmaChunked(memory: DmaMemory, pa: Long, src: MemorySegment, totalBytes: Long) {
        var off = 0L
        while (off < totalBytes) {
            val len = minOf(DMA_CHUNK_BYTES.toLong(), totalBytes - off)
            memory.write(pa + off, src.asSlice(off, len).asByteBuffer())
            off += len
        }
    }

    data class EncodeResult(
        val outputBytes: Long,
        val blocksX: Int,
        val blocksY: Int,
        val dmaWritten: Boolean,
        val compressed: Boolean,
        val srcPa: Long = 0L,
        val dstPa: Long = 0L,
        val rawBits: Int = 16,
    )

    data class DecodeResult(
        val blocksX: Int,
        val blocksY: Int,
        val dmaWritten: Boolean,
    )

    fun headerBytesForStrips(blocksX: Int): Int {
        val bxMinus1 = (blocksX - 1).coerceAtLeast(0)
        return (((bxMinus1 ushr 2) + 2) shl 4)
    }

    /**
     * Executes an `LSRAW` encode pass using the programmed MMIO registers and 34-bit AXI high-bank words.
     * Writes the Fujifilm `"IS"` header, strip-size table, and Golomb-Rice strips to `dstPa` via [memory]
     * when addressable, and updates `words[0xA4 / 4]` (output byte count) and `words[0x10 / 4]` (strip status).
     */
    fun executeEncode(
        words: LongArray,
        axiHigh34Words: LongArray,
        memory: DmaMemory?,
    ): EncodeResult {
        val reg80 = words[0x80 / 4]
        // DAT section5 0x02297C48..0x02297CD8 & live GXUP0004.DAT capture:
        // bit 8 == 1 is Lossless (0xFFFA2098 = 0); bit 8 == 0 is Compressed (0xFFFA2098 = 0x900/0xA80/0xC00).
        val isLossless = ((reg80 ushr 8) and 1L) != 0L
        val lossyCompressed = !isLossless
        val bitDepthCode = (reg80 and 3L).toInt()
        val rawBits = when (bitDepthCode) {
            0 -> 12
            1 -> 14
            else -> 16
        }

        val reg84 = words[0x84 / 4]
        val reg88 = words[0x88 / 4]
        val reg8c = words[0x8C / 4]
        val reg90 = words[0x90 / 4]
        val maxCap = words[0x9C / 4]

        val blocksYFromReg = (((reg8c ushr 16) and 0xFFFL).toInt() + 1).coerceAtLeast(1)
        val blocksXFromReg = ((reg8c and 0x1FL).toInt() + 1).coerceIn(1, 22)
        val paddedHeight = ((reg88 ushr 16) and 0x7FFFL).toInt().takeIf { it >= BLOCK_LINES } ?: (blocksYFromReg * BLOCK_LINES)
        val paddedWidth = (reg88 and 0x7FFFL).toInt().takeIf { it >= DEFAULT_STRIP_PIXELS } ?: (blocksXFromReg * DEFAULT_STRIP_PIXELS)
        val activeHeight = ((reg90 ushr 16) and 0x7FFFL).toInt().takeIf { it > 0 } ?: paddedHeight
        val activeWidth = (reg90 and 0x7FFFL).toInt().takeIf { it > 0 } ?: paddedWidth
        val lineStridePixels = (reg84 and 0xFFFFL).toInt().takeIf { it > 0 } ?: activeWidth

        val blocksX = blocksXFromReg
        val stripPixels = (paddedWidth / blocksX).takeIf { it >= 2 && (it and 1) == 0 } ?: DEFAULT_STRIP_PIXELS
        val blocksY = (paddedHeight / BLOCK_LINES).coerceAtLeast(1)
        val hdrBytes = headerBytesForStrips(blocksX)

        val srcPa = ((axiHigh34Words[0x50 / 4] and 3L) shl 32) or (words[0x144 / 4] and 0xFFFF_FFFFL)
        val dstPa = ((axiHigh34Words[0x54 / 4] and 3L) shl 32) or (words[0x140 / 4] and 0xFFFF_FFFFL)

        // Check if destination DMA address is writable through DmaMemory (32-bit or 34-bit high-PA)
        val canWriteDst = memory != null && dstPa != 0L && dstPa < MAX_AXI34_PA_END &&
            runCatching {
                val probeLen = minOf(maxCap.takeIf { it > 0L } ?: hdrBytes.toLong(), hdrBytes.toLong().coerceAtLeast(16L))
                memory.check(dstPa, probeLen, true)
                true
            }.getOrElse { false }

        if (!canWriteDst) {
            // Unmapped destination or unit test without DMA backing: report aligned minimal container size
            if (memory != null && dstPa != 0L) {
                log.warning(
                    "[FALLBACK][lsraw] Encode dstPa=0x${dstPa.toString(16)} (maxCap=$maxCap) outside mapped DmaMemory; reporting metadata-only encoded size",
                )
            }
            val fallbackSize = alignOutputSize(
                rawSize = maxOf(hdrBytes + blocksX * 256, 65536).toLong(),
                hdrBytes = hdrBytes,
                blocksX = blocksX,
                blocksY = blocksY,
                lossyCompressed = lossyCompressed,
                maxCap = maxCap,
            )
            words[0x10 / 4] = (((blocksX - 1).toLong() and 0x1FL) shl 16) or (blocksY.toLong() and 0x7FFFL)
            words[0xA4 / 4] = fallbackSize
            return EncodeResult(fallbackSize, blocksX, blocksY, dmaWritten = false, compressed = lossyCompressed, srcPa = srcPa, dstPa = dstPa, rawBits = rawBits)
        }

        val mem = checkNotNull(memory)
        val rowBytes = lineStridePixels.toLong() * 2L
        val srcTotalBytes = rowBytes * activeHeight.toLong()
        val canReadSrc = srcPa != 0L && srcPa < MAX_AXI34_PA_END && srcTotalBytes > 0L &&
            (srcPa + srcTotalBytes) <= MAX_AXI34_PA_END &&
            runCatching {
                mem.check(srcPa, srcTotalBytes, false)
                true
            }.getOrElse { false }

        if (!canReadSrc && srcPa != 0L) {
            log.warning(
                "[FALLBACK][lsraw] Encode srcPa=0x${srcPa.toString(16)} (${activeWidth}x${activeHeight}, $srcTotalBytes B) outside mapped DmaMemory; encoding black-level Bayer frame",
            )
        }

        // Zero-copy DirectGuestRamPort native encode path: avoids allocating/copying 203 MiB Bayer RAW
        // and ~100 MiB compressed LSRAW intermediate ByteArrays when guest RAM is directly sliceable.
        if (NativeCodecBridge.hasLsraw && mem is DirectGuestRamPort) {
            val directEncodedLen = tryExecuteEncodeDirectNative(
                ram = mem,
                srcPa = if (canReadSrc) srcPa else 0L,
                dstPa = dstPa,
                lineStridePixels = lineStridePixels,
                activeWidth = activeWidth,
                activeHeight = activeHeight,
                stripPixels = stripPixels,
                blocksX = blocksX,
                blocksY = blocksY,
                rawBits = rawBits,
                lossyCompressed = lossyCompressed,
                maxCap = maxCap,
            )
            if (directEncodedLen != null) {
                words[0x10 / 4] = (((blocksX - 1).toLong() and 0x1FL) shl 16) or (blocksY.toLong() and 0x7FFFL)
                words[0xA4 / 4] = directEncodedLen.toLong()
                return EncodeResult(
                    directEncodedLen.toLong(),
                    blocksX,
                    blocksY,
                    dmaWritten = true,
                    compressed = lossyCompressed,
                    srcPa = srcPa,
                    dstPa = dstPa,
                    rawBits = rawBits,
                )
            }
        }

        // Encode all strips into Fujifilm "IS" + strip-table + Golomb-Rice Bayer 2x2 bitstream
        val encoded = encodeFujifilmBayer(
            memory = if (canReadSrc) mem else null,
            srcPa = srcPa,
            lineStridePixels = lineStridePixels,
            activeWidth = activeWidth,
            activeHeight = activeHeight,
            stripPixels = stripPixels,
            blocksX = blocksX,
            blocksY = blocksY,
            rawBits = rawBits,
            lossyCompressed = lossyCompressed,
            maxCap = maxCap,
            useNative = true,
        )

        if (encoded.isEmpty() || (maxCap > 0 && encoded.size > maxCap)) {
            words[0x10 / 4] = 0
            words[0xA4 / 4] = 0
            return EncodeResult(0L, blocksX, blocksY, false, lossyCompressed, srcPa, dstPa, rawBits)
        }
        val writeLen = encoded.size
        val writeOk = runCatching {
            mem.check(dstPa, writeLen.toLong(), true)
            val directDst = (mem as? DirectGuestRamPort)?.sliceDirect(dstPa, writeLen.toLong(), write = true)
            if (directDst != null) {
                MemorySegment.copy(encoded, 0, directDst, ValueLayout.JAVA_BYTE, 0L, writeLen)
                mem.notifyDirectWrite(dstPa, writeLen.toLong())
            } else {
                var off = 0
                while (off < writeLen) {
                    val len = minOf(DMA_CHUNK_BYTES, writeLen - off)
                    mem.write(dstPa + off.toLong(), ByteBuffer.wrap(encoded, off, len))
                    off += len
                }
            }
            true
        }.getOrElse { err ->
            log.warning(
                "[FALLBACK][lsraw] Encode DMA write failed dstPa=0x${dstPa.toString(16)} len=$writeLen: ${err.message}",
            )
            false
        }

        words[0x10 / 4] = (((blocksX - 1).toLong() and 0x1FL) shl 16) or (blocksY.toLong() and 0x7FFFL)
        words[0xA4 / 4] = writeLen.toLong()
        return EncodeResult(writeLen.toLong(), blocksX, blocksY, dmaWritten = writeOk, compressed = lossyCompressed, srcPa = srcPa, dstPa = dstPa, rawBits = rawBits)
    }

    private fun tryExecuteEncodeDirectNative(
        ram: DirectGuestRamPort,
        srcPa: Long,
        dstPa: Long,
        lineStridePixels: Int,
        activeWidth: Int,
        activeHeight: Int,
        stripPixels: Int,
        blocksX: Int,
        blocksY: Int,
        rawBits: Int,
        lossyCompressed: Boolean,
        maxCap: Long,
    ): Int? {
        val srcStrideBytes = lineStridePixels * 2
        val srcTotalBytes = srcStrideBytes.toLong() * activeHeight.toLong()
        if (srcTotalBytes <= 0L || srcTotalBytes > 512L * 1024L * 1024L) return null
        val directSrc = if (srcPa != 0L) ram.sliceDirect(srcPa, srcTotalBytes, write = false) else null
        val cappedMax = if (maxCap > 0L) maxCap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val directDst = if (cappedMax > 0 && cappedMax <= 512 * 1024 * 1024) {
            ram.sliceDirect(dstPa, cappedMax.toLong(), write = true)
        } else {
            null
        }
        if (directSrc == null && directDst == null) return null

        return runCatching {
            Arena.ofConfined().use { arena ->
                val srcSeg = directSrc ?: arena.allocate(srcTotalBytes).also { seg ->
                    if (srcPa != 0L) readDmaChunked(ram, srcPa, seg, srcTotalBytes) else seg.fill(0)
                }
                val reqSeg = arena.allocate(ValueLayout.JAVA_INT)
                val initDstCap = if (directDst != null) {
                    cappedMax
                } else if (cappedMax > 0) {
                    minOf(cappedMax.toLong(), maxOf(65536L, srcTotalBytes + 65536L)).toInt()
                } else {
                    minOf(512L * 1024L * 1024L, maxOf(65536L, srcTotalBytes + 65536L)).toInt()
                }
                var dstSeg = directDst ?: arena.allocate(initDstCap.toLong())
                var dstIsDirect = directDst != null
                var dstCap = initDstCap
                var rc = NativeCodecBridge.lsrawEncodeBayerSegment(
                    srcRawLe16 = srcSeg,
                    srcStrideBytes = srcStrideBytes,
                    activeWidth = activeWidth,
                    activeHeight = activeHeight,
                    blocksX = blocksX,
                    blocksY = blocksY,
                    stripPixels = stripPixels,
                    rawBits = rawBits,
                    lossyCompressed = lossyCompressed,
                    maxCapBytes = cappedMax,
                    maxThreads = 0,
                    dstStream = dstSeg,
                    dstCapacity = dstCap,
                    outRequiredBytes = reqSeg,
                )
                if (rc == NativeCodecBridge.ERR_OVERFLOW) {
                    val needed = reqSeg.get(ValueLayout.JAVA_INT, 0L)
                    if (needed > dstCap && (cappedMax <= 0 || needed <= cappedMax) && needed <= 512 * 1024 * 1024) {
                        dstCap = needed
                        dstSeg = arena.allocate(dstCap.toLong())
                        dstIsDirect = false
                        rc = NativeCodecBridge.lsrawEncodeBayerSegment(
                            srcRawLe16 = srcSeg,
                            srcStrideBytes = srcStrideBytes,
                            activeWidth = activeWidth,
                            activeHeight = activeHeight,
                            blocksX = blocksX,
                            blocksY = blocksY,
                            stripPixels = stripPixels,
                            rawBits = rawBits,
                            lossyCompressed = lossyCompressed,
                            maxCapBytes = cappedMax,
                            maxThreads = 0,
                            dstStream = dstSeg,
                            dstCapacity = dstCap,
                            outRequiredBytes = reqSeg,
                        )
                    }
                }
                if (rc <= 0 || (cappedMax > 0 && rc > cappedMax)) return@use null
                val writeLen = rc
                if (dstIsDirect) {
                    ram.notifyDirectWrite(dstPa, writeLen.toLong())
                } else {
                    ram.check(dstPa, writeLen.toLong(), true)
                    val outDirect = ram.sliceDirect(dstPa, writeLen.toLong(), write = true)
                    if (outDirect != null) {
                        MemorySegment.copy(dstSeg, 0L, outDirect, 0L, writeLen.toLong())
                        ram.notifyDirectWrite(dstPa, writeLen.toLong())
                    } else {
                        writeDmaChunked(ram, dstPa, dstSeg, writeLen.toLong())
                    }
                }
                writeLen
            }
        }.getOrNull()
    }

    /**
     * Executes an `LSRAW` decode pass from `srcPa` (Fujifilm `"IS"` bitstream) into `dstPa`
     * (16-bit little-endian Bayer RAW pixels) via [memory].
     */
    fun executeDecode(
        words: LongArray,
        axiHigh34Words: LongArray,
        memory: DmaMemory?,
    ): DecodeResult {
        val srcPa = ((axiHigh34Words[0x50 / 4] and 3L) shl 32) or (words[0x144 / 4] and 0xFFFF_FFFFL)
        val dstPa = ((axiHigh34Words[0x54 / 4] and 3L) shl 32) or (words[0x140 / 4] and 0xFFFF_FFFFL)
        if (memory == null || srcPa == 0L || dstPa == 0L || srcPa >= MAX_AXI34_PA_END || dstPa >= MAX_AXI34_PA_END) {
            return DecodeResult(blocksX = 0, blocksY = 0, dmaWritten = false)
        }

        val headerBuf = ByteArray(16)
        val headerOk = runCatching {
            memory.check(srcPa, 16L, false)
            memory.read(srcPa, ByteBuffer.wrap(headerBuf))
            true
        }.getOrElse { false }
        if (!headerOk || (headerBuf[0].toInt() and 0xFF) != FUJI_MAGIC_HI || (headerBuf[1].toInt() and 0xFF) != FUJI_MAGIC_LO) {
            log.warning(
                "[FALLBACK][lsraw] Decode srcPa=0x${srcPa.toString(16)} missing Fujifilm IS header; completing with register status only",
            )
            return DecodeResult(blocksX = 0, blocksY = 0, dmaWritten = false)
        }

        val rawBits = (headerBuf[4].toInt() and 0xFF).coerceIn(12, 16)
        val rawHeight = readU16Be(headerBuf, 5)
        val rawWidth = readU16Be(headerBuf, 9)
        val stripPixels = readU16Be(headerBuf, 11).takeIf { it >= 2 && (it and 1) == 0 } ?: DEFAULT_STRIP_PIXELS
        val blocksX = (headerBuf[13].toInt() and 0xFF).coerceIn(1, 22)
        val blocksY = readU16Be(headerBuf, 14).coerceAtLeast(1)
        val hdrBytes = headerBytesForStrips(blocksX)

        val tableBuf = ByteArray(hdrBytes)
        val tableOk = runCatching {
            memory.check(srcPa, hdrBytes.toLong(), false)
            memory.read(srcPa, ByteBuffer.wrap(tableBuf))
            true
        }.getOrElse { false }
        if (!tableOk) return DecodeResult(blocksX, blocksY, dmaWritten = false)

        val stripSizes = IntArray(blocksX) { s -> readU32Be(tableBuf, 16 + s * 4) }
        if (stripSizes.any { it <= 0 }) return DecodeResult(blocksX, blocksY, false)
        val qTableSize = if (headerBuf[2].toInt() == 0 && headerBuf[3].toInt() == FUJI_RAW_TYPE_BAYER) {
            blocksX * ((blocksY + 15) and -16)
        } else 0
        val totalStreamBytes = hdrBytes.toLong() + qTableSize + stripSizes.sumOf { it.toLong() }
        if (totalStreamBytes > 256L * 1024L * 1024L) {
            return DecodeResult(blocksX, blocksY, dmaWritten = false)
        }

        val lineStridePixels = (words[0x84 / 4] and 0xFFFFL).toInt().takeIf { it >= rawWidth } ?: rawWidth
        val dstStrideBytes = lineStridePixels * 2
        val dstTotalBytes = dstStrideBytes.toLong() * rawHeight.toLong()

        // Zero-copy DirectGuestRamPort native decode path: reads compressed stream and writes
        // 16-bit Bayer RAW directly on off-heap guest RAM with zero intermediate ByteArray copies.
        if (NativeCodecBridge.hasLsraw && memory is DirectGuestRamPort &&
            dstTotalBytes > 0L && dstTotalBytes <= 512L * 1024L * 1024L
        ) {
            val directSrc = memory.sliceDirect(srcPa, totalStreamBytes, write = false)
            val directDst = memory.sliceDirect(dstPa, dstTotalBytes, write = true)
            if (directSrc != null || directDst != null) {
                val directDecoded = runCatching {
                    Arena.ofConfined().use { arena ->
                        val srcSeg = directSrc ?: arena.allocate(totalStreamBytes).also { seg ->
                            memory.check(srcPa, totalStreamBytes, false)
                            readDmaChunked(memory, srcPa, seg, totalStreamBytes)
                        }
                        val dstSeg = directDst ?: arena.allocate(dstTotalBytes)
                        val rc = NativeCodecBridge.lsrawDecodeBayerSegment(
                            srcStream = srcSeg,
                            srcStreamLen = totalStreamBytes.toInt(),
                            dstStrideBytes = dstStrideBytes,
                            maxThreads = 0,
                            dstRawLe16 = dstSeg,
                            dstCapacity = dstTotalBytes.toInt(),
                        )
                        if (rc != dstTotalBytes.toInt()) return@use null
                        if (directDst != null) {
                            memory.notifyDirectWrite(dstPa, dstTotalBytes)
                        } else {
                            memory.check(dstPa, dstTotalBytes, true)
                            writeDmaChunked(memory, dstPa, dstSeg, dstTotalBytes)
                        }
                        true
                    }
                }.getOrNull()
                if (directDecoded != null) {
                    words[0x10 / 4] = (((blocksX - 1).toLong() and 0x1FL) shl 16) or (blocksY.toLong() and 0x7FFFL)
                    return DecodeResult(blocksX, blocksY, dmaWritten = directDecoded)
                }
            }
        }

        val stream = ByteArray(totalStreamBytes.toInt())
        val readOk = runCatching {
            memory.check(srcPa, totalStreamBytes, false)
            val directSrc = (memory as? DirectGuestRamPort)?.sliceDirect(srcPa, totalStreamBytes, write = false)
            if (directSrc != null) {
                MemorySegment.copy(directSrc, ValueLayout.JAVA_BYTE, 0L, stream, 0, totalStreamBytes.toInt())
            } else {
                var off = 0
                val totalInt = totalStreamBytes.toInt()
                while (off < totalInt) {
                    val len = minOf(DMA_CHUNK_BYTES, totalInt - off)
                    memory.read(srcPa + off.toLong(), ByteBuffer.wrap(stream, off, len))
                    off += len
                }
            }
            true
        }.getOrElse { false }
        if (!readOk) return DecodeResult(blocksX, blocksY, dmaWritten = false)

        val decodedOk = decodeFujifilmBayer(
            stream = stream,
            hdrBytes = hdrBytes,
            stripSizes = stripSizes,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            lineStridePixels = lineStridePixels,
            stripPixels = stripPixels,
            blocksX = blocksX,
            blocksY = blocksY,
            rawBits = rawBits,
            memory = memory,
            dstPa = dstPa,
            useNative = true,
        )

        words[0x10 / 4] = (((blocksX - 1).toLong() and 0x1FL) shl 16) or (blocksY.toLong() and 0x7FFFL)
        return DecodeResult(blocksX, blocksY, dmaWritten = decodedOk)
    }

    /**
     * Fujifilm Bayer (`raw_type = 0`) per-strip lossless/compressed encoder.
     *
     * Uses exact 6-line Bayer MCU blocks per strip (768 pixels on GFX100S 16-strip 102MP) with
     * gradient-adaptive Golomb-Rice coding. When [lossyCompressed] is true
     * (`0xFFFA2080[8] == 0`), clears the low two sample bits before predictive coding and writes
     * the per-strip zero q-base table consumed by compressed-mode readers.
     */
    internal fun encodeFujifilmBayer(
        memory: DmaMemory?,
        srcPa: Long,
        lineStridePixels: Int,
        activeWidth: Int,
        activeHeight: Int,
        stripPixels: Int,
        blocksX: Int,
        blocksY: Int,
        rawBits: Int,
        lossyCompressed: Boolean,
        maxCap: Long,
        useNative: Boolean = true,
        nativeThreads: Int = 0,
    ): ByteArray {
        if (useNative) {
            if (NativeCodecBridge.hasLsraw) {
                val nativeOut = encodeFujifilmBayerNative(
                    memory = memory,
                    srcPa = srcPa,
                    lineStridePixels = lineStridePixels,
                    activeWidth = activeWidth,
                    activeHeight = activeHeight,
                    stripPixels = stripPixels,
                    blocksX = blocksX,
                    blocksY = blocksY,
                    rawBits = rawBits,
                    lossyCompressed = lossyCompressed,
                    maxCap = maxCap,
                    maxThreads = nativeThreads,
                )
                if (nativeOut != null) return nativeOut
            } else {
                NativeCodecBridge.warnLsrawFallbackOnce()
            }
        }
        val hdrBytes = headerBytesForStrips(blocksX)
        val maxVal = (1 shl rawBits) - 1
        val rawRoundedWidth = blocksX * stripPixels
        val sampleMask = if (lossyCompressed) maxVal and -4 else maxVal
        val stripWriters = Array(blocksX) { StripBitWriter(initialCap = maxOf(4096, blocksY * 64)) }
        val stripStates = Array(blocksX) { BayerStrip(stripPixels, rawBits, stripWriters[it]) }

        val chunkRowBytes = lineStridePixels * 2
        val rawChunkBuf = ByteArray(chunkRowBytes * BLOCK_LINES)
        val lineCache = IntArray(BLOCK_LINES * stripPixels)

        val rowReader = ByteBuffer.wrap(rawChunkBuf)

        for (by in 0 until blocksY) {
            val yBase = by * BLOCK_LINES
            val availLines = (activeHeight - yBase).coerceIn(0, BLOCK_LINES)
            if (memory != null && availLines > 0) {
                rowReader.clear()
                rowReader.limit(availLines * chunkRowBytes)
                memory.read(srcPa + yBase.toLong() * chunkRowBytes.toLong(), rowReader)
            } else {
                rawChunkBuf.fill(0)
            }
            if (availLines < BLOCK_LINES) {
                rawChunkBuf.fill(0, availLines * chunkRowBytes, rawChunkBuf.size)
            }

            for (s in 0 until blocksX) {
                val xStart = s * stripPixels
                val stripActiveW = (activeWidth - xStart).coerceIn(0, stripPixels)
                for (r in 0 until BLOCK_LINES) {
                    val dstOff = r * stripPixels
                    val srcRowOff = r * chunkRowBytes
                    var x = 0
                    while (x < stripActiveW) {
                        val bOff = srcRowOff + (xStart + x) * 2
                        val lo = rawChunkBuf[bOff].toInt() and 0xFF
                        val hi = rawChunkBuf[bOff + 1].toInt() and 0xFF
                        lineCache[dstOff + x] = ((hi shl 8) or lo) and sampleMask
                        x++
                    }
                    val padEven = if (stripActiveW >= 2) lineCache[dstOff + stripActiveW - 2] else 0
                    val padOdd = if (stripActiveW >= 2) lineCache[dstOff + stripActiveW - 1] else 0
                    while (x < stripPixels) {
                        lineCache[dstOff + x] = if ((x and 1) == 0) padEven else padOdd
                        x++
                    }
                }

                stripStates[s].process(lineCache)
            }
        }

        val stripData = Array(blocksX) { s -> stripWriters[s].finishAligned16() }
        val payloadBytes = stripData.sumOf { it.size.toLong() }

        val qTableSize = if (lossyCompressed) blocksX * ((blocksY + 15) and -16) else 0
        val targetTotal = alignOutputSize(
            rawSize = hdrBytes.toLong() + qTableSize + payloadBytes,
            hdrBytes = hdrBytes,
            blocksX = blocksX,
            blocksY = blocksY,
            lossyCompressed = lossyCompressed,
            maxCap = 0,
        )
        if (targetTotal > Int.MAX_VALUE || (maxCap > 0 && targetTotal > maxCap)) return byteArrayOf()
        val finalSize = targetTotal.toInt()
        val out = ByteArray(finalSize)

        out[0] = FUJI_MAGIC_HI.toByte()
        out[1] = FUJI_MAGIC_LO.toByte()
        out[2] = (if (lossyCompressed) 0 else FUJI_VERSION).toByte()
        out[3] = FUJI_RAW_TYPE_BAYER.toByte()
        out[4] = rawBits.toByte()
        writeU16Be(out, 5, activeHeight)
        writeU16Be(out, 7, rawRoundedWidth)
        writeU16Be(out, 9, activeWidth)
        writeU16Be(out, 11, stripPixels)
        out[13] = blocksX.toByte()
        writeU16Be(out, 14, blocksY)

        var cursor = hdrBytes + qTableSize
        for ((index, strip) in stripData.withIndex()) {
            val length = if (index == stripData.lastIndex) finalSize - cursor else strip.size
            writeU32Be(out, 16 + index * 4, length)
            strip.copyInto(out, cursor)
            cursor += strip.size
        }
        return out
    }

    private fun encodeFujifilmBayerNative(
        memory: DmaMemory?,
        srcPa: Long,
        lineStridePixels: Int,
        activeWidth: Int,
        activeHeight: Int,
        stripPixels: Int,
        blocksX: Int,
        blocksY: Int,
        rawBits: Int,
        lossyCompressed: Boolean,
        maxCap: Long,
        maxThreads: Int,
    ): ByteArray? {
        val srcStrideBytes = lineStridePixels * 2
        val srcTotalBytes = srcStrideBytes.toLong() * activeHeight.toLong()
        if (srcTotalBytes <= 0L || srcTotalBytes > 512L * 1024L * 1024L) return null
        val cappedMax = if (maxCap > 0L) maxCap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val initDstCap = if (cappedMax > 0) {
            minOf(cappedMax.toLong(), maxOf(65536L, srcTotalBytes + 65536L)).toInt()
        } else {
            minOf(512L * 1024L * 1024L, maxOf(65536L, srcTotalBytes + 65536L)).toInt()
        }
        return runCatching {
            Arena.ofConfined().use { arena ->
                val directSrc = (memory as? DirectGuestRamPort)?.sliceDirect(srcPa, srcTotalBytes, write = false)
                val srcSeg = directSrc ?: arena.allocate(srcTotalBytes).also { seg ->
                    if (memory != null) {
                        readDmaChunked(memory, srcPa, seg, srcTotalBytes)
                    } else {
                        seg.fill(0)
                    }
                }
                val reqSeg = arena.allocate(ValueLayout.JAVA_INT)
                var dstSeg = arena.allocate(initDstCap.toLong())
                var dstCap = initDstCap
                var rc = NativeCodecBridge.lsrawEncodeBayerSegment(
                    srcRawLe16 = srcSeg,
                    srcStrideBytes = srcStrideBytes,
                    activeWidth = activeWidth,
                    activeHeight = activeHeight,
                    blocksX = blocksX,
                    blocksY = blocksY,
                    stripPixels = stripPixels,
                    rawBits = rawBits,
                    lossyCompressed = lossyCompressed,
                    maxCapBytes = cappedMax,
                    maxThreads = maxThreads,
                    dstStream = dstSeg,
                    dstCapacity = dstCap,
                    outRequiredBytes = reqSeg,
                )
                if (rc == NativeCodecBridge.ERR_OVERFLOW) {
                    val needed = reqSeg.get(ValueLayout.JAVA_INT, 0L)
                    if (needed > dstCap && (cappedMax <= 0 || needed <= cappedMax) && needed <= 512 * 1024 * 1024) {
                        dstCap = needed
                        dstSeg = arena.allocate(dstCap.toLong())
                        rc = NativeCodecBridge.lsrawEncodeBayerSegment(
                            srcRawLe16 = srcSeg,
                            srcStrideBytes = srcStrideBytes,
                            activeWidth = activeWidth,
                            activeHeight = activeHeight,
                            blocksX = blocksX,
                            blocksY = blocksY,
                            stripPixels = stripPixels,
                            rawBits = rawBits,
                            lossyCompressed = lossyCompressed,
                            maxCapBytes = cappedMax,
                            maxThreads = maxThreads,
                            dstStream = dstSeg,
                            dstCapacity = dstCap,
                            outRequiredBytes = reqSeg,
                        )
                    }
                }
                if (rc <= 0) return@use null
                val out = ByteArray(rc)
                MemorySegment.copy(dstSeg, ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
                out
            }
        }.getOrNull()
    }

    internal fun decodeFujifilmBayer(
        stream: ByteArray,
        hdrBytes: Int,
        stripSizes: IntArray,
        rawWidth: Int,
        rawHeight: Int,
        lineStridePixels: Int,
        stripPixels: Int,
        blocksX: Int,
        blocksY: Int,
        rawBits: Int,
        memory: DmaMemory,
        dstPa: Long,
        useNative: Boolean = true,
        nativeThreads: Int = 0,
    ): Boolean {
        if (useNative) {
            if (NativeCodecBridge.hasLsraw) {
                val nativeRes = decodeFujifilmBayerNative(
                    stream = stream,
                    rawHeight = rawHeight,
                    lineStridePixels = lineStridePixels,
                    memory = memory,
                    dstPa = dstPa,
                    maxThreads = nativeThreads,
                )
                if (nativeRes != null) return nativeRes
            } else {
                NativeCodecBridge.warnLsrawFallbackOnce()
            }
        }
        if (stream[3].toInt() == FUJI_RAW_TYPE_BAYER) {
            return decodeBayerStrips(stream, hdrBytes, stripSizes, rawWidth, rawHeight, lineStridePixels,
                stripPixels, blocksX, blocksY, rawBits, memory, dstPa)
        }
        // Older simulator RAFs used type 16 and a private row predictor for Bayer.
        if (stream[3].toInt() != 16 || stream[2].toInt() !in 1..2) return false
        val version = stream[2].toInt() and 0xFF
        val qShift = if (version == 2) 2 else 0
        val effBits = (rawBits - qShift).coerceAtLeast(10)
        val effRange = 1 shl effBits
        val effMask = effRange - 1

        val stripOffsets = IntArray(blocksX)
        var off = hdrBytes
        for (s in 0 until blocksX) {
            stripOffsets[s] = off
            off += stripSizes[s]
        }

        val readers = Array(blocksX) { s ->
            val start = stripOffsets[s].coerceIn(0, stream.size)
            val end = (start + stripSizes[s]).coerceIn(start, stream.size)
            StripBitReader(stream, start, end, padLegacyTail = true)
        }
        val stripStates = Array(blocksX) { StripCodecState(effRange) }
        val prevRows = Array(blocksX) { IntArray(2 * stripPixels) }
        val lineCache = IntArray(BLOCK_LINES * stripPixels)

        val chunkRowBytes = lineStridePixels * 2
        val outChunkBuf = ByteArray(chunkRowBytes * BLOCK_LINES)

        for (by in 0 until blocksY) {
            val yBase = by * BLOCK_LINES
            val availLines = (rawHeight - yBase).coerceIn(0, BLOCK_LINES)
            outChunkBuf.fill(0)

            for (s in 0 until blocksX) {
                val reader = readers[s]
                val state = stripStates[s]
                val prev = prevRows[s]
                val xStart = s * stripPixels
                val stripActiveW = (rawWidth - xStart).coerceIn(0, stripPixels)

                for (r in 0 until BLOCK_LINES) {
                    val curOff = r * stripPixels
                    val hasPrev2 = (by > 0) || (r >= 2)
                    val prev2Arr = if (r >= 2) lineCache else prev
                    val prev2Off = if (r >= 2) (r - 2) * stripPixels else r * stripPixels
                    val phaseRow = r and 1

                    var x = 0
                    while (x < stripPixels) {
                        val colorIdx = (phaseRow shl 1) or (x and 1)
                        val left = if (x >= 2) lineCache[curOff + x - 2] else if (hasPrev2) prev2Arr[prev2Off + x] else 0
                        val top = if (hasPrev2) prev2Arr[prev2Off + x] else left
                        val topLeft = if (hasPrev2 && x >= 2) prev2Arr[prev2Off + x - 2] else top

                        val pred = medianPredict(left, top, topLeft)
                        val grad = abs(left - topLeft) + abs(top - topLeft)
                        val ctx = gradContext(colorIdx, grad, effBits)
                        val k = bitDiff(state.gradA[ctx], state.gradN[ctx])

                        val q = reader.readUnaryZeros()
                        val cur: Int
                        val diff: Int
                        if (q < 31) {
                            val rem = if (k > 0) reader.readBits(k) else 0
                            val mapped = (q shl k) or rem
                            diff = if ((mapped and 1) == 0) (mapped ushr 1) else -((mapped + 1) ushr 1)
                            cur = (pred + diff) and effMask
                        } else {
                            cur = reader.readBits(effBits)
                            var d = (cur - pred) and effMask
                            if (d >= (effRange ushr 1)) d -= effRange
                            diff = d
                        }

                        lineCache[curOff + x] = cur
                        state.gradA[ctx] += abs(diff)
                        state.gradN[ctx] += 1
                        if (state.gradN[ctx] >= MIN_VALUE) {
                            state.gradA[ctx] = state.gradA[ctx] ushr 1
                            state.gradN[ctx] = state.gradN[ctx] ushr 1
                        }
                        x++
                    }

                    if (r < availLines) {
                        val dstRowOff = r * chunkRowBytes
                        for (pxIdx in 0 until stripActiveW) {
                            val v = lineCache[curOff + pxIdx] shl qShift
                            val bOff = dstRowOff + (xStart + pxIdx) * 2
                            outChunkBuf[bOff] = (v and 0xFF).toByte()
                            outChunkBuf[bOff + 1] = ((v ushr 8) and 0xFF).toByte()
                        }
                    }
                }

                System.arraycopy(lineCache, 4 * stripPixels, prev, 0, stripPixels)
                System.arraycopy(lineCache, 5 * stripPixels, prev, stripPixels, stripPixels)
            }

            if (availLines > 0) {
                val writeBytes = availLines * chunkRowBytes
                val ok = runCatching {
                    memory.check(dstPa + yBase.toLong() * chunkRowBytes.toLong(), writeBytes.toLong(), true)
                    memory.write(dstPa + yBase.toLong() * chunkRowBytes.toLong(), ByteBuffer.wrap(outChunkBuf, 0, writeBytes))
                    true
                }.getOrElse { false }
                if (!ok) return false
            }
        }
        return true
    }

    private fun decodeBayerStrips(
        stream: ByteArray, hdrBytes: Int, stripSizes: IntArray, width: Int, height: Int,
        stride: Int, stripWidth: Int, strips: Int, groups: Int, bits: Int, memory: DmaMemory, dst: Long,
    ): Boolean {
        if (stream[2].toInt() !in 0..1 || stride < width) return false
        val qTableSize = if (stream[2].toInt() == 0) strips * ((groups + 15) and -16) else 0
        var cursor = hdrBytes + qTableSize
        if (cursor > stream.size || (hdrBytes until cursor).any { stream[it].toInt() != 0 }) return false
        if (stripSizes.any { it <= 0 } || cursor + stripSizes.sumOf { it.toLong() } > stream.size) return false
        val states = Array(strips) { index ->
            val start = cursor
            cursor += stripSizes[index]
            BayerStrip(stripWidth, bits, StripBitReader(stream, start, cursor))
        }
        val samples = IntArray(stripWidth * BLOCK_LINES)
        val out = ByteArray(stride * 2 * BLOCK_LINES)
        return try {
            for (group in 0 until groups) {
                val rows = (height - group * BLOCK_LINES).coerceIn(0, BLOCK_LINES)
                for ((index, state) in states.withIndex()) {
                    state.process(samples)
                    val xStart = index * stripWidth
                    val active = (width - xStart).coerceIn(0, stripWidth)
                    for (y in 0 until rows) {
                        var offset = 2 * (y * stride + xStart)
                        for (x in 0 until active) {
                            val value = samples[y * stripWidth + x]
                            out[offset++] = value.toByte()
                            out[offset++] = (value ushr 8).toByte()
                        }
                    }
                }
                if (rows > 0) memory.write(dst + group.toLong() * out.size, ByteBuffer.wrap(out, 0, rows * stride * 2))
            }
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun decodeFujifilmBayerNative(
        stream: ByteArray,
        rawHeight: Int,
        lineStridePixels: Int,
        memory: DmaMemory,
        dstPa: Long,
        maxThreads: Int,
    ): Boolean? {
        val dstStrideBytes = lineStridePixels * 2
        val dstTotalBytes = dstStrideBytes.toLong() * rawHeight.toLong()
        if (dstTotalBytes <= 0L || dstTotalBytes > 512L * 1024L * 1024L) return null
        return runCatching {
            Arena.ofConfined().use { arena ->
                val srcSeg = arena.allocate(stream.size.toLong())
                MemorySegment.copy(stream, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, stream.size)
                val directDst = (memory as? DirectGuestRamPort)?.sliceDirect(dstPa, dstTotalBytes, write = true)
                val dstSeg = directDst ?: arena.allocate(dstTotalBytes)
                val rc = NativeCodecBridge.lsrawDecodeBayerSegment(
                    srcStream = srcSeg,
                    srcStreamLen = stream.size,
                    dstStrideBytes = dstStrideBytes,
                    maxThreads = maxThreads,
                    dstRawLe16 = dstSeg,
                    dstCapacity = dstTotalBytes.toInt(),
                )
                if (rc != dstTotalBytes.toInt()) return@use null
                if (directDst != null) {
                    memory.notifyDirectWrite(dstPa, dstTotalBytes)
                } else {
                    memory.check(dstPa, dstTotalBytes, true)
                    writeDmaChunked(memory, dstPa, dstSeg, dstTotalBytes)
                }
                true
            }
        }.getOrNull()
    }

    /**
     * Aligns total encoded byte size to match the exact hardware alignment invariant verified in
     * `section5.bin @ 0x022B95CC..0x022B961C`:
     * - Lossless (`bit 8 == 1`, `lossyCompressed == false`): `(size - hdrBytes) % 256 == 0`
     * - Compressed (`bit 8 == 0`, `lossyCompressed == true`): `(size - hdrBytes - (((blocksY - 1) >> 4) + 1) * (blocksX << 4)) % 256 == 0`
     */
    internal fun alignOutputSize(
        rawSize: Long,
        hdrBytes: Int,
        blocksX: Int,
        blocksY: Int,
        lossyCompressed: Boolean,
        maxCap: Long,
    ): Long {
        val baseOffset = if (!lossyCompressed) {
            hdrBytes.toLong()
        } else {
            val byM1 = (blocksY - 1).coerceAtLeast(0)
            hdrBytes.toLong() + ((byM1 ushr 4) + 1).toLong() * (blocksX.toLong() shl 4)
        }
        val minRequired = maxOf(rawSize, baseOffset + 256L)
        val delta = minRequired - baseOffset
        val alignedDelta = ((delta + 255L) and 255L.inv())
        val candidate = baseOffset + alignedDelta
        if (maxCap <= 0L || candidate <= maxCap) return candidate
        // Clamp below maxCap while keeping alignment
        val maxDelta = (maxCap - baseOffset).coerceAtLeast(0L) and 255L.inv()
        return if (maxDelta > 0L) baseOffset + maxDelta else maxCap
    }

    private fun medianPredict(a: Int, b: Int, c: Int): Int {
        val minAB = if (a < b) a else b
        val maxAB = if (a > b) a else b
        return when {
            c >= maxAB -> minAB
            c <= minAB -> maxAB
            else -> a + b - c
        }
    }

    private fun gradContext(colorIdx: Int, grad: Int, effBits: Int): Int {
        val shift = (effBits - 12).coerceAtLeast(0)
        val g = grad ushr shift
        val bucket = when {
            g < 4 -> 0
            g < 16 -> 1
            g < 64 -> 2
            else -> 3
        }
        return (colorIdx shl 2) or bucket
    }

    private fun bitDiff(a: Int, n: Int): Int {
        var k = 0
        var shifted = n
        while (k < 15 && shifted < a) {
            shifted = shifted shl 1
            k++
        }
        return k
    }

    private class StripCodecState(effRange: Int) {
        val gradN = IntArray(16) { 1 }
        val gradA = IntArray(16) { maxOf(4, (effRange + 32) ushr 6) }
    }

    private interface ResidualStream {
        /** Emit [actual] when writing, or read the signed prediction residual when decoding. */
        fun transfer(actual: Int, k: Int, bits: Int): Int
    }

    /** One independent Fujifilm RGGB strip, including the two preceding lines of each color. */
    private class BayerStrip(
        private val stripWidth: Int,
        private val bits: Int,
        private val codes: ResidualStream,
    ) {
        private val width = stripWidth / 2
        private val range = 1 shl bits
        private val mask = range - 1
        private val colors = Array(3) { ColorLines(width) }
        // Six Rice models: three successive Bayer rows, with separate even and odd samples.
        // Each model indexes the absolute value of a signed 9脳9 gradient pair.
        private val sums = IntArray(6 * 41) { maxOf(2, (range + 32) ushr 6) }
        private val counts = IntArray(sums.size) { 1 }

        fun process(samples: IntArray) {
            for (row in 0 until BLOCK_LINES) {
                val first = colors[row and 1]
                val second = colors[(row and 1) + 1]
                val model = (row % 3) * 82
                val start = row * stripWidth
                var even = 0
                var odd = 1
                // Four even positions lead the odd stream so that its right neighbor is available.
                while (even < width || odd < width) {
                    if (even < width) {
                        val x = start + 2 * even
                        samples[x] = processSample(first, even, model, samples[x], true)
                        samples[x + 1] = processSample(second, even, model, samples[x + 1], true)
                        even += 2
                    }
                    if ((even > 8 || even >= width) && odd < width) {
                        val x = start + 2 * odd
                        samples[x] = processSample(first, odd, model + 41, samples[x], false)
                        samples[x + 1] = processSample(second, odd, model + 41, samples[x + 1], false)
                        odd += 2
                    }
                }
                first.advance()
                second.advance()
            }
        }

        private fun processSample(lines: ColorLines, position: Int, model: Int, value: Int, even: Boolean): Int {
            val x = position + 1
            val above = lines.previous[x]
            val leftAbove = lines.previous[x - 1]
            val rightAbove = lines.previous[x + 1]
            val grad: Int
            val prediction: Int
            if (even) {
                val far = lines.older[x]
                grad = 9 * gradient(above - far) + gradient(leftAbove - above)
                val l = abs(leftAbove - above)
                val r = abs(rightAbove - above)
                val f = abs(far - above)
                val neighbors = when {
                    l > r && l > f -> far + rightAbove
                    r > l && r > f -> far + leftAbove
                    else -> leftAbove + rightAbove
                }
                prediction = (2 * above + neighbors) ushr 2
            } else {
                val left = lines.current[x - 1]
                val right = lines.current[x + 1]
                grad = 9 * gradient(above - leftAbove) + gradient(leftAbove - left)
                prediction = if (above > maxOf(leftAbove, rightAbove) || above < minOf(leftAbove, rightAbove)) {
                    (left + right + 2 * above) ushr 2
                } else {
                    (left + right) ushr 1
                }
            }

            val context = model + abs(grad)
            val delta = if (grad < 0) prediction - value else value - prediction
            val signed = ((delta + range / 2) and mask) - range / 2
            val residual = codes.transfer(signed, bitDiff(sums[context], counts[context]), bits)
            sums[context] += abs(residual)
            if (counts[context] == MIN_VALUE) {
                sums[context] = sums[context] ushr 1
                counts[context] = counts[context] ushr 1
            }
            counts[context]++
            val reconstructed = (prediction + if (grad < 0) -residual else residual) and mask
            lines.current[x] = reconstructed
            return reconstructed
        }

        private fun gradient(delta: Int): Int {
            val bucket = when (abs(delta)) {
                0 -> 0
                in 1..17 -> 1
                in 18..66 -> 2
                in 67..275 -> 3
                else -> 4
            }
            return if (delta < 0) -bucket else bucket
        }

        private class ColorLines(width: Int) {
            var previous = IntArray(width + 2)
            var older = IntArray(width + 2)
            var current = IntArray(width + 2)

            fun advance() {
                val reuse = older
                older = previous
                previous = current
                current = reuse
                current[0] = previous[1]
                current[current.lastIndex] = previous[previous.lastIndex - 1]
            }
        }
    }

    private class StripBitWriter(initialCap: Int) : ResidualStream {
        override fun transfer(actual: Int, k: Int, bits: Int): Int {
            val code = if (actual >= 0) actual * 2 else -actual * 2 - 1
            val prefix = code ushr k
            val escape = bits * 3 - 1
            if (prefix < escape) {
                writeZerosAndOne(prefix)
                writeBits(code, k)
            } else {
                writeZerosAndOne(escape)
                writeBits(code - 1, bits)
            }
            return actual
        }

        private var buf = ByteArray(initialCap)
        private var pos = 0
        private var bitBuf = 0L
        private var bitCount = 0

        fun writeZerosAndOne(zeros: Int) {
            // Fast path: emit `zeros` zero bits followed by a single 1 bit
            var z = zeros
            while (z >= 24) {
                writeBits(0, 24)
                z -= 24
            }
            writeBits(1, z + 1)
        }

        fun writeBits(value: Int, count: Int) {
            if (count <= 0) return
            val masked = value.toLong() and ((1L shl count) - 1L)
            bitBuf = (bitBuf shl count) or masked
            bitCount += count
            if (bitCount >= 32) {
                flushBytes()
            }
        }

        private fun flushBytes() {
            if (pos + 8 > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, pos + 4096))
            }
            while (bitCount >= 8) {
                bitCount -= 8
                buf[pos++] = ((bitBuf ushr bitCount) and 0xFFL).toByte()
            }
        }

        fun finishAligned16(): ByteArray {
            flushBytes()
            if (bitCount > 0) {
                if (pos + 1 > buf.size) buf = buf.copyOf(buf.size + 64)
                buf[pos++] = ((bitBuf shl (8 - bitCount)) and 0xFFL).toByte()
                bitCount = 0
            }
            val aligned = (pos + 15) and 15.inv()
            return buf.copyOf(aligned)
        }
    }

    private class StripBitReader(
        private val data: ByteArray,
        private var pos: Int,
        private val end: Int,
        private val padLegacyTail: Boolean = false,
    ) : ResidualStream {
        override fun transfer(actual: Int, k: Int, bits: Int): Int {
            val escape = bits * 3 - 1
            var prefix = 0
            while (readBits(1) == 0) {
                require(++prefix <= escape) { "Malformed LSRAW unary prefix" }
            }
            val code = if (prefix == escape) readBits(bits) + 1 else (prefix shl k) or readBits(k)
            require(code < (1 shl bits)) { "Invalid LSRAW residual" }
            return if ((code and 1) == 0) code ushr 1 else -(code ushr 1) - 1
        }

        private var bitBuf = 0L
        private var bitCount = 0

        private fun ensureBits(need: Int) {
            while (bitCount < need) {
                require(pos < end || padLegacyTail) { "Truncated LSRAW strip" }
                val b = if (pos < end) data[pos++].toInt() and 0xFF else 0
                bitBuf = (bitBuf shl 8) or b.toLong()
                bitCount += 8
            }
        }

        fun readUnaryZeros(): Int {
            var zeros = 0
            while (zeros < 31) {
                ensureBits(1)
                bitCount--
                if (((bitBuf ushr bitCount) and 1L) != 0L) {
                    return zeros
                }
                zeros++
            }
            // Consume terminating 1 bit after 31 zeros
            ensureBits(1)
            bitCount--
            return 31
        }

        fun readBits(count: Int): Int {
            if (count <= 0) return 0
            ensureBits(count)
            bitCount -= count
            return ((bitBuf ushr bitCount) and ((1L shl count) - 1L)).toInt()
        }
    }

    private fun readU16Be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun writeU16Be(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    private fun readU32Be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun writeU32Be(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
    }
}
