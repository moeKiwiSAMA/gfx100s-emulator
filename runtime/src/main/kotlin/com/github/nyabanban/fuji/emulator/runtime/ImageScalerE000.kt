package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.roundToInt

/**
 * Explicit behavioral discovery support for the 0xFFF7E000 primary image resizer bank (`RESZ`)
 * (DAT section4.bin 011FCC68..011FDAF8, caller/ISR 0128F434..01290490, channel 9 -> GIC IRQ 132):
 * - 0xFFF7E000..0xFFF7E118 (71 aligned 32-bit words, no holes)
 *
 * Behavioral rules (HYPOTHESIS):
 * - 0xFFF7E000: 011FCCD4..011FCCF8 writes 0 and polls bit 0 == 1 until reset completes; reads assert bit 0.
 * - 0xFFF7E10C: 011FCC70..011FCCD0 clears 0xFFF7E100 bit 28 and 0xFFF7E104 bits 31:28, then polls
 *   0xFFF7E10C == 0x01000100; reads assert bit 8 (0x100) when 0xFFF7E100 bit 28 is clear and bit 24
 *   (0x01000000) when 0xFFF7E104 bits 31:28 are clear.
 * - Under `fff7e000-irq132-completion-v1`: writing 0xFFF7E004 bit 0 after address/stride setup performs
 *   packed UYVY422 bilinear DMA scaling (`E0C0/E0C4` -> `E0D4/E0D8`) and asserts GIC level IRQ 132
 *   after 64 ticks; cleared by writing bit 0 to 0xFFF7E10C.
 * - All other DAT-verified offsets retain written 32-bit words (initially 0).
 */
internal class ImageScalerE000 private constructor(
    private val registerValues: LongArray,
    val profile: String = PROFILE,
    private var pendingCompletionTick: Long? = null,
    private var irqAsserted: Boolean = false,
    private var e014Written: Boolean = false,
    private var scaleCount: Long = 0L,
) {
    constructor(profile: String = PROFILE) : this(LongArray(REGISTER_ADDRESSES.size), profile) {
        require(profile in PROFILES) { "Unknown $KEY selection: $profile" }
    }

    fun nextDeadline(): Long? = if (profile == IRQ132_PROFILE) pendingCompletionTick else null
    fun nextDeadlinePrimitive(): Long = if (profile == IRQ132_PROFILE) (pendingCompletionTick ?: Long.MAX_VALUE) else Long.MAX_VALUE

    fun access(
        address: Long,
        write: Boolean,
        value: Long,
        tick: Long = 0L,
        onAckIrq132: (() -> Unit)? = null,
    ): Triple<Long, Long, Long>? {
        val slot = Arrays.binarySearch(REGISTER_ADDRESSES, address)
        if (slot < 0) return null
        val prior = observableWord(address, slot)
        if (write) {
            if (profile == IRQ132_PROFILE) {
                when {
                    address == 0xfff7e000L && value == 0L -> {
                        pendingCompletionTick = null
                        if (irqAsserted) {
                            irqAsserted = false
                            onAckIrq132?.invoke()
                        }
                        registerValues[E004_SLOT] = registerValues[E004_SLOT] and 1L.inv()
                        e014Written = false
                        registerValues[slot] = value
                    }
                    address == 0xfff7e10cL -> {
                        if ((value and 1L) != 0L && irqAsserted) {
                            irqAsserted = false
                            onAckIrq132?.invoke()
                        }
                        registerValues[slot] = value and 1L.inv()
                    }
                    address == 0xfff7e004L -> {
                        registerValues[slot] = value
                        if ((value and 1L) != 0L &&
                            (registerValues[E0C4_SLOT] != 0L || registerValues[E0D8_SLOT] != 0L || registerValues[E0EC_SLOT] != 0L)
                        ) {
                            pendingCompletionTick = tick + COMPLETION_DELAY_TICKS
                        }
                    }
                    address == 0xfff7e014L -> {
                        e014Written = true
                        registerValues[slot] = value
                    }
                    else -> registerValues[slot] = value
                }
            } else {
                when {
                    address == 0xfff7e000L && value == 0L -> {
                        registerValues[E004_SLOT] = registerValues[E004_SLOT] and 1L.inv()
                        e014Written = false
                        registerValues[slot] = value
                    }
                    address == 0xfff7e014L -> {
                        e014Written = true
                        registerValues[slot] = value
                    }
                    else -> registerValues[slot] = value
                }
            }
            val next = observableWord(address, slot)
            return Triple(value, prior, next)
        }
        return Triple(prior, prior, prior)
    }

    private var rawSensorScratch: ByteArray? = null
    private var warnedNominalExposureFallback: Boolean = false

    fun beforeInstruction(
        tick: Long,
        dmaMemory: DmaMemory? = null,
        sensor: SensorPort? = null,
        lens: LensParameters? = null,
        exposurePort: Imx461ExposurePort? = null,
        assertIrq132: () -> Unit,
    ) {
        if (profile != IRQ132_PROFILE) return
        val due = pendingCompletionTick ?: return
        if (tick < due) return
        pendingCompletionTick = null
        performPixelDma(tick, dmaMemory, sensor, lens, exposurePort)
        scaleCount++
        irqAsserted = true
        registerValues[E004_SLOT] = registerValues[E004_SLOT] and 1L.inv()
        assertIrq132()
    }

    private fun performPixelDma(
        tick: Long,
        dmaMemory: DmaMemory?,
        sensor: SensorPort?,
        lens: LensParameters?,
        exposurePort: Imx461ExposurePort? = null,
    ) {
        val srcAddr = ((registerValues[E0C0_SLOT] and 3L) shl 32) or (registerValues[E0C4_SLOT] and 0xffffffffL)
        val dstAddr = ((registerValues[E0D4_SLOT] and 3L) shl 32) or (registerValues[E0D8_SLOT] and 0xffffffffL)
        val srcStrideBytes = (registerValues[E0D0_SLOT] and 0x3ffffL).toInt()
        val dstStrideBytes = (registerValues[E0E4_SLOT] and 0x3ffffL).toInt()
        if (srcAddr == 0L || dstAddr == 0L || srcStrideBytes == 0 || dstStrideBytes == 0) return

        val memory = requireNotNull(dmaMemory) { "RESZ pixel DMA requires DmaMemory" }
        val e008 = registerValues[E008_SLOT]
        val srcFmt = (e008 and 0x3L).toInt()
        val dstFmt = ((e008 ushr 4) and 0x3L).toInt()

        val srcW = ((registerValues[E00C_SLOT] and 0x3fffL) + 1L).toInt()
        val srcH = (((registerValues[E00C_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()
        val cropX = (registerValues[E010_SLOT] and 0x3fffL).toInt()
        val cropY = ((registerValues[E010_SLOT] ushr 16) and 0x3fffL).toInt()
        val hDecim = ((registerValues[E01C_SLOT] ushr 16) and 7L).toInt().coerceAtMost(3)
        val vDecim = ((registerValues[E020_SLOT] ushr 16) and 7L).toInt().coerceAtMost(3)
        val cropWDecim = ((registerValues[E014_SLOT] and 0x3fffL) + 1L).toInt()
        val cropHDecim = (((registerValues[E014_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()
        val useE014Crop = e014Written || registerValues[E014_SLOT] != 0L
        val cropW = if (useE014Crop) minOf(srcW - cropX, cropWDecim shl hDecim) else (srcW - cropX)
        val cropH = if (useE014Crop) minOf(srcH - cropY, cropHDecim shl vDecim) else (srcH - cropY)
        val dstW = ((registerValues[E018_SLOT] and 0x3fffL) + 1L).toInt()
        val dstH = (((registerValues[E018_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()

        require(cropX in 0 until srcW && cropY in 0 until srcH && cropW >= 1 && cropH >= 1) {
            "Invalid RESZ crop ($cropX,$cropY,${cropW}x$cropH) for ${srcW}x$srcH"
        }
        if (e008 == 0x133L) {
            scaleMovieTiles(memory, srcAddr, srcStrideBytes, cropX, cropY, cropW, cropH,
                dstAddr, dstStrideBytes, dstW, dstH)
            return
        }
        if (e008 == 0x103L) {
            readMovieTiles(memory, srcAddr, srcStrideBytes, cropX, cropY, cropW, cropH,
                dstAddr, dstStrideBytes, dstW, dstH)
            return
        }
        if (srcAddr >= 0x1_0000_0000L || dstAddr >= 0x1_0000_0000L) {
            require(srcAddr in 1L..0x3_FFFF_FFFFL && dstAddr in 1L..0x3_FFFF_FFFFL) {
                "RESZ DMA address out of 34-bit AXI physical range: src=0x${srcAddr.toString(16)} dst=0x${dstAddr.toString(16)}"
            }
            // BUG-A2-3: STILL capture writes full-res YUV via RECC_S to 34-bit AXI high-DDR
            // (e.g. 0x1_7300_0000) and invokes RESZ (0xFFF7E000) to downscale it into a 32-bit
            // DRAM review/post-view buffer (e.g. 0x39F81000 -> 0x3946A000 PIC0, dstFmt=0).
            // Because 34-bit AXI high-DDR lies above the 32-bit ARMv7 DmaMemory aperture,
            // synthesize the optical CMOS frame directly into the 32-bit packed UYVY422 target
            // (mirroring SocJpegFactory's 34-bit AXI CMOS synthesis).
            // Live recording: LV0 writes a strided 4:2:2 source ring. RESZ produces
            // the single-plane 2x2-tiled surface submitted to the MVE as format 0x121.
            if (e008 == 0x131L && dstAddr >= 0x1_0000_0000L) {
                writeMovieTiles(memory, srcAddr, srcStrideBytes, cropX, cropY, cropW, cropH,
                    dstAddr, dstStrideBytes, dstW, dstH)
                return
            }
            if (srcAddr >= 0x1_0000_0000L && dstAddr < 0x1_0000_0000L && dstFmt == 0 && sensor != null) {
                synthesizeHighAxiSourceToUyvyDst(
                    tick = tick,
                    memory = memory,
                    sensor = sensor,
                    lens = lens,
                    exposurePort = exposurePort,
                    hwSrcW = srcW,
                    hwSrcH = srcH,
                    hwCropX = cropX,
                    hwCropY = cropY,
                    hwCropW = cropW,
                    hwCropH = cropH,
                    dstAddr = dstAddr,
                    dstW = dstW,
                    dstH = dstH,
                    dstStrideBytes = dstStrideBytes,
                )
            }
            return
        }
        require(srcFmt == 0 && dstFmt == 0) {
            "Unsupported RESZ pixel format: E008=0x${e008.toString(16)} srcFmt=$srcFmt dstFmt=$dstFmt"
        }

        val maxStridePixels = srcStrideBytes / 2
        require(srcStrideBytes >= 2 && cropX < maxStridePixels && dstStrideBytes >= dstW * 2) {
            "RESZ stride too small: srcStride=$srcStrideBytes dstStride=$dstStrideBytes"
        }
        val effectiveCropW = minOf(cropW, maxStridePixels - cropX)
        val maxSrcX = cropX + effectiveCropW - 1
        val maxSrcY = cropY + cropH - 1
        val lastRowBytes = minOf(srcStrideBytes.toLong(), ((maxSrcX + 2) and 1.inv()).toLong() * 2L)
        val srcSpan = ((cropY + cropH - 1).toLong() * srcStrideBytes) + lastRowBytes
        val dstSpan = ((dstH - 1).toLong() * dstStrideBytes) + dstW.toLong() * 2L
        require(srcSpan in 1L..MAX_PIXEL_DMA_BYTES && dstSpan in 1L..MAX_PIXEL_DMA_BYTES) {
            "RESZ DMA span out of bounds: srcSpan=$srcSpan dstSpan=$dstSpan max=$MAX_PIXEL_DMA_BYTES"
        }
        require(srcAddr in 1L..(0x1_0000_0000L - srcSpan) && dstAddr in 1L..(0x1_0000_0000L - dstSpan)) {
            "RESZ DMA address out of 32-bit physical range: " +
                "srcAddr=0x${srcAddr.toString(16)}(e0c0=0x${registerValues[E0C0_SLOT].toString(16)},e0c4=0x${registerValues[E0C4_SLOT].toString(16)},span=0x${srcSpan.toString(16)}) " +
                "dstAddr=0x${dstAddr.toString(16)}(e0d4=0x${registerValues[E0D4_SLOT].toString(16)},e0d8=0x${registerValues[E0D8_SLOT].toString(16)},span=0x${dstSpan.toString(16)}) " +
                "e0e8=0x${registerValues[E0EC_SLOT - 1].toString(16)} e0ec=0x${registerValues[E0EC_SLOT].toString(16)} " +
                "src=${srcW}x${srcH}(stride=$srcStrideBytes) crop=($cropX,$cropY,${cropW}x$cropH) dst=${dstW}x${dstH}(stride=$dstStrideBytes) e008=0x${e008.toString(16)}"
        }

        memory.check(srcAddr, srcSpan, false)
        memory.check(dstAddr, dstSpan, true)

        val stepX = 1 shl hDecim
        val stepY = 1 shl vDecim
        if (cropX == 0 && cropY == 0 && effectiveCropW == dstW && cropH == dstH &&
            stepX == 1 && stepY == 1 && srcStrideBytes == dstStrideBytes && srcSpan == dstSpan
        ) {
            val transferBuf = ByteArray(minOf(DMA_CHUNK_BYTES, srcSpan.toInt()))
            var done = 0L
            while (done < srcSpan) {
                val chunk = minOf(transferBuf.size.toLong(), srcSpan - done).toInt()
                val readSlice = ByteBuffer.wrap(transferBuf, 0, chunk)
                memory.read(srcAddr + done, readSlice)
                require(readSlice.remaining() == 0) { "Incomplete RESZ source DMA read" }
                val writeSlice = ByteBuffer.wrap(transferBuf, 0, chunk)
                memory.write(dstAddr + done, writeSlice)
                require(writeSlice.remaining() == 0) { "Incomplete RESZ destination DMA write" }
                done += chunk.toLong()
            }
            return
        }

        val src = ByteArray(srcSpan.toInt())
        var readDone = 0
        while (readDone < src.size) {
            val chunk = minOf(DMA_CHUNK_BYTES, src.size - readDone)
            val slice = ByteBuffer.wrap(src, readDone, chunk)
            memory.read(srcAddr + readDone.toLong(), slice)
            require(slice.remaining() == 0) { "Incomplete RESZ source DMA read" }
            readDone += chunk
        }
        normalizeMilbeautBlackMarginWords(src)
        val rowBytes = dstW * 2
        if (dstW.toLong() * dstH.toLong() > FAST_SCALE_PIXEL_THRESHOLD) {
            val dstFull = ByteArray(dstSpan.toInt())
            val sxMap = IntArray(dstW) { dx ->
                if (dstW == 1) cropX
                else (cropX + ((dx.toLong() * (effectiveCropW - 1)) / (dstW - 1)).toInt()).coerceIn(cropX, maxSrcX)
            }
            for (dy in 0 until dstH) {
                val sy = if (dstH == 1) cropY
                else (cropY + ((dy.toLong() * (cropH - 1)) / (dstH - 1)).toInt()).coerceIn(cropY, maxSrcY)
                val srcRowOff = sy * srcStrideBytes
                val dstRowOff = dy * dstStrideBytes
                var dx = 0
                while (dx < dstW) {
                    val x0 = sxMap[dx]
                    val pair0 = srcRowOff + (x0 and 1.inv()) * 2
                    val u0 = if (pair0 < src.size) src[pair0] else 128.toByte()
                    val y0Off = pair0 + if ((x0 and 1) != 0) 3 else 1
                    val y0 = if (y0Off < src.size) src[y0Off] else 0
                    val p = dstRowOff + dx * 2
                    if (dx + 1 < dstW) {
                        val x1 = sxMap[dx + 1]
                        val pair1 = srcRowOff + (x1 and 1.inv()) * 2
                        val vOff = pair0 + 2
                        val v0 = if (vOff < src.size) src[vOff] else 128.toByte()
                        val y1Off = pair1 + if ((x1 and 1) != 0) 3 else 1
                        val y1 = if (y1Off < src.size) src[y1Off] else 0
                        dstFull[p] = u0
                        dstFull[p + 1] = y0
                        dstFull[p + 2] = v0
                        dstFull[p + 3] = y1
                    } else {
                        dstFull[p] = u0
                        dstFull[p + 1] = y0
                    }
                    dx += 2
                }
            }
            var writeDone = 0
            while (writeDone < dstFull.size) {
                val chunk = minOf(DMA_CHUNK_BYTES, dstFull.size - writeDone)
                val slice = ByteBuffer.wrap(dstFull, writeDone, chunk)
                memory.write(dstAddr + writeDone.toLong(), slice)
                require(slice.remaining() == 0) { "Incomplete RESZ destination DMA write" }
                writeDone += chunk
            }
            return
        }
        val rowBuf = ByteArray(rowBytes)
        val dstFullBuf = if (dstStrideBytes == rowBytes) ByteArray(dstSpan.toInt()) else null

        fun sampleY(bx: Int, by: Int): Int {
            if (stepX == 1 && stepY == 1) {
                val off = by * srcStrideBytes + (bx and 1.inv()) * 2 + if ((bx and 1) != 0) 3 else 1
                return if (off < src.size) src[off].toInt() and 0xff else 0
            }
            val xEnd = minOf(bx + stepX, cropX + cropW)
            val yEnd = minOf(by + stepY, cropY + cropH)
            var sum = 0
            var count = 0
            for (yy in by until yEnd) {
                val rowOff = yy * srcStrideBytes
                for (xx in bx until xEnd) {
                    val off = rowOff + (xx and 1.inv()) * 2 + if ((xx and 1) != 0) 3 else 1
                    if (off < src.size) sum += src[off].toInt() and 0xff
                    count++
                }
            }
            return if (count == 0) 0 else sum / count
        }

        fun sampleU(bx: Int, by: Int): Int {
            if (stepX == 1 && stepY == 1) {
                val off = by * srcStrideBytes + (bx and 1.inv()) * 2
                return if (off < src.size) src[off].toInt() and 0xff else 128
            }
            val xEnd = minOf(bx + stepX, cropX + cropW)
            val yEnd = minOf(by + stepY, cropY + cropH)
            var sum = 0
            var count = 0
            for (yy in by until yEnd) {
                val rowOff = yy * srcStrideBytes
                for (xx in bx until xEnd) {
                    val off = rowOff + (xx and 1.inv()) * 2
                    sum += if (off < src.size) src[off].toInt() and 0xff else 128
                    count++
                }
            }
            return if (count == 0) 128 else sum / count
        }

        fun sampleV(bx: Int, by: Int): Int {
            if (stepX == 1 && stepY == 1) {
                val colOff = (bx and 1.inv()) * 2 + 2
                val off = by * srcStrideBytes + colOff
                return if (colOff < srcStrideBytes && off < src.size) src[off].toInt() and 0xff else 128
            }
            val xEnd = minOf(bx + stepX, cropX + cropW)
            val yEnd = minOf(by + stepY, cropY + cropH)
            var sum = 0
            var count = 0
            for (yy in by until yEnd) {
                val rowOff = yy * srcStrideBytes
                for (xx in bx until xEnd) {
                    val colOff = (xx and 1.inv()) * 2 + 2
                    val off = rowOff + colOff
                    sum += if (colOff < srcStrideBytes && off < src.size) src[off].toInt() and 0xff else 128
                    count++
                }
            }
            return if (count == 0) 128 else sum / count
        }

        fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Int {
            val top = c00 + (c10 - c00) * fx
            val bot = c01 + (c11 - c01) * fx
            return (top + (bot - top) * fy + 0.5).toInt().coerceIn(0, 255)
        }

        for (dy in 0 until dstH) {
            val sy = if (dstH == 1) cropY.toDouble() else cropY + (dy.toDouble() * (cropH - 1)) / (dstH - 1)
            val y0 = sy.toInt().coerceIn(cropY, maxSrcY)
            val y1 = (y0 + stepY).coerceIn(cropY, maxSrcY)
            val fy = ((sy - y0) / stepY).coerceIn(0.0, 1.0)

            var dx = 0
            while (dx < dstW) {
                val sx0 = if (dstW == 1) cropX.toDouble() else cropX + (dx.toDouble() * (cropW - 1)) / (dstW - 1)
                val x0a = sx0.toInt().coerceIn(cropX, maxSrcX)
                val x0b = (x0a + stepX).coerceIn(cropX, maxSrcX)
                val fx0 = ((sx0 - x0a) / stepX).coerceIn(0.0, 1.0)

                val outY0 = bilinear(sampleY(x0a, y0), sampleY(x0b, y0), sampleY(x0a, y1), sampleY(x0b, y1), fx0, fy)
                val outU0 = bilinear(sampleU(x0a, y0), sampleU(x0b, y0), sampleU(x0a, y1), sampleU(x0b, y1), fx0, fy)
                val outV0 = bilinear(sampleV(x0a, y0), sampleV(x0b, y0), sampleV(x0a, y1), sampleV(x0b, y1), fx0, fy)

                val p = dx * 2
                if (dx + 1 < dstW) {
                    val sx1 = if (dstW == 1) cropX.toDouble() else cropX + ((dx + 1).toDouble() * (cropW - 1)) / (dstW - 1)
                    val x1a = sx1.toInt().coerceIn(cropX, maxSrcX)
                    val x1b = (x1a + stepX).coerceIn(cropX, maxSrcX)
                    val fx1 = ((sx1 - x1a) / stepX).coerceIn(0.0, 1.0)

                    val outY1 = bilinear(sampleY(x1a, y0), sampleY(x1b, y0), sampleY(x1a, y1), sampleY(x1b, y1), fx1, fy)
                    val outU1 = bilinear(sampleU(x1a, y0), sampleU(x1b, y0), sampleU(x1a, y1), sampleU(x1b, y1), fx1, fy)
                    val outV1 = bilinear(sampleV(x1a, y0), sampleV(x1b, y0), sampleV(x1a, y1), sampleV(x1b, y1), fx1, fy)

                    rowBuf[p] = ((outU0 + outU1) / 2).coerceIn(0, 255).toByte()
                    rowBuf[p + 1] = outY0.toByte()
                    rowBuf[p + 2] = ((outV0 + outV1) / 2).coerceIn(0, 255).toByte()
                    rowBuf[p + 3] = outY1.toByte()
                } else {
                    rowBuf[p] = outU0.toByte()
                    rowBuf[p + 1] = outY0.toByte()
                }
                dx += 2
            }
            if (dstFullBuf != null) {
                System.arraycopy(rowBuf, 0, dstFullBuf, dy * rowBytes, rowBytes)
            } else {
                memory.write(dstAddr + dy.toLong() * dstStrideBytes, ByteBuffer.wrap(rowBuf))
            }
        }
        if (dstFullBuf != null) {
            var writeDone = 0
            while (writeDone < dstFullBuf.size) {
                val chunk = minOf(DMA_CHUNK_BYTES, dstFullBuf.size - writeDone)
                val slice = ByteBuffer.wrap(dstFullBuf, writeDone, chunk)
                memory.write(dstAddr + writeDone.toLong(), slice)
                require(slice.remaining() == 0) { "Incomplete RESZ destination DMA write" }
                writeDone += chunk
            }
        }
    }

    private var movieSource = ByteArray(0)
    private var movieTileRow = ByteArray(0)

    private var playbackUyvy = ByteArray(0)

    /** Movie RECC_S feeds RESZ a 10-bit Y0L2 surface. Each pixel quad occupies two LE words. */
    private fun scaleMovieTiles(
        memory: DmaMemory, srcAddr: Long, srcStride: Int, cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstAddr: Long, dstStride: Int, width: Int, height: Int,
    ) {
        val firstTileX = cropX / 2
        val firstTileY = cropY / 2
        val sourceRows = (cropY + cropH - 1) / 2 - firstTileY + 1
        val sourceRowBytes = ((cropX + cropW - 1) / 2 - firstTileX + 1) * 8
        val targetRowBytes = ((width + 1) / 2) * 8
        val sourceStart = srcAddr + firstTileY.toLong() * srcStride + firstTileX * 8
        val sourceSpan = (sourceRows - 1L) * srcStride + sourceRowBytes
        val targetSpan = ((height - 1L) / 2) * dstStride + targetRowBytes
        require(srcStride >= firstTileX * 8 + sourceRowBytes && dstStride >= targetRowBytes)
        require(sourceSpan in 1..MAX_PIXEL_DMA_BYTES && targetSpan in 1..MAX_PIXEL_DMA_BYTES)
        memory.check(sourceStart, sourceSpan, false)
        memory.check(dstAddr, targetSpan, true)
        // Gather only active bytes. The 4K recording ring pads a 15,552-byte tile row to 36,864.
        // Gathering the crop first also preserves a source that overlaps the destination.
        val sourceSize = sourceRows * sourceRowBytes
        if (movieSource.size < sourceSize) movieSource = ByteArray(sourceSize)
        if (movieTileRow.size < targetRowBytes) movieTileRow = ByteArray(targetRowBytes)
        val src = ByteBuffer.wrap(movieSource).order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until sourceRows) {
            src.limit((row + 1) * sourceRowBytes).position(row * sourceRowBytes)
            memory.read(sourceStart + row.toLong() * srcStride, src)
        }
        val dst = ByteBuffer.wrap(movieTileRow).order(ByteOrder.LITTLE_ENDIAN)
        val xSamples = IntArray((width + 1) and -2) { x -> cropX + (minOf(x, width - 1).toLong() * cropW / width).toInt() }
        fun field(tile: Long, shift: Int) = (tile ushr shift).toInt() and 1023
        for (dy in 0 until height step 2) {
            val sy0 = cropY + (dy.toLong() * cropH / height).toInt()
            val sy1 = cropY + (minOf(dy + 1, height - 1).toLong() * cropH / height).toInt()
            val top = (sy0 / 2 - firstTileY) * sourceRowBytes
            val bottom = (sy1 / 2 - firstTileY) * sourceRowBytes
            dst.clear()
            for (dx in xSamples.indices step 2) {
                val x0 = xSamples[dx]
                val x1 = xSamples[dx + 1]
                val a = (x0 / 2 - firstTileX) * 8
                val b = (x1 / 2 - firstTileX) * 8
                val tile00 = src.getLong(top + a)
                val tile01 = src.getLong(top + b)
                val tile10 = src.getLong(bottom + a)
                val tile11 = src.getLong(bottom + b)
                val y00 = field(tile00, (sy0 and 1) * 32 + (x0 and 1) * 20)
                val y01 = field(tile01, (sy0 and 1) * 32 + (x1 and 1) * 20)
                val y10 = field(tile10, (sy1 and 1) * 32 + (x0 and 1) * 20)
                val y11 = field(tile11, (sy1 and 1) * 32 + (x1 and 1) * 20)
                // Both chroma components cover the same four scaled luma locations.
                val cb = (field(tile00, 10) + field(tile01, 10) + field(tile10, 10) + field(tile11, 10) + 2) / 4
                val cr = (field(tile00, 42) + field(tile01, 42) + field(tile10, 42) + field(tile11, 42) + 2) / 4
                dst.putInt(y00 or (cb shl 10) or (y01 shl 20))
                dst.putInt(y10 or (cr shl 10) or (y11 shl 20))
            }
            dst.flip()
            memory.write(dstAddr + (dy / 2).toLong() * dstStride, dst)
        }
    }

    /** Playback RESZ reads the VPU's 2x2 Y0L2 surface and writes packed 8-bit UYVY to the LCD plane. */
    private fun readMovieTiles(
        memory: DmaMemory, srcAddr: Long, srcStride: Int, cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstAddr: Long, dstStride: Int, width: Int, height: Int,
    ) {
        val rowBytes = ((width + 1) and -2) * 2
        val firstTileRow = cropY / 2
        val lastTileRow = (cropY + cropH - 1) / 2
        val tileRowBytes = ((cropX + cropW + 1) / 2) * 8
        require(srcStride >= tileRowBytes && dstStride >= rowBytes)
        val srcSpan = (lastTileRow - firstTileRow).toLong() * srcStride + tileRowBytes
        val dstSpan = (height - 1L) * dstStride + rowBytes
        require(srcSpan in 1..MAX_PIXEL_DMA_BYTES && dstSpan in 1..MAX_PIXEL_DMA_BYTES)
        val base = srcAddr + firstTileRow.toLong() * srcStride
        memory.check(base, srcSpan, false)
        memory.check(dstAddr, dstSpan, true)
        if (movieSource.size < srcSpan) movieSource = ByteArray(srcSpan.toInt())
        if (playbackUyvy.size < rowBytes * height) playbackUyvy = ByteArray(rowBytes * height)
        memory.read(base, ByteBuffer.wrap(movieSource, 0, srcSpan.toInt()))
        val tileWords = ByteBuffer.wrap(movieSource).order(ByteOrder.LITTLE_ENDIAN)
        val xSamples = IntArray((width + 1) and -2) { x ->
            cropX + (minOf(x, width - 1).toLong() * cropW / width).toInt()
        }
        var dst = 0
        for (y in 0 until height) {
            val sy = cropY + (y.toLong() * cropH / height).toInt()
            val row = (sy / 2 - firstTileRow) * srcStride
            val field = (sy and 1) * 4
            for (x in xSamples.indices step 2) {
                val a = xSamples[x]
                val b = xSamples[x + 1]
                val tileA = row + (a / 2) * 8
                val tileB = row + (b / 2) * 8
                val y0 = tileWords.getInt(tileA + field) ushr (2 + (a and 1) * 20)
                val y1 = tileWords.getInt(tileB + field) ushr (2 + (b and 1) * 20)
                fun chroma(delta: Int): Byte {
                    val ca = (tileWords.getInt(tileA + delta) ushr 12) and 255
                    val cb = (tileWords.getInt(tileB + delta) ushr 12) and 255
                    return ((ca + cb + 1) / 2).toByte()
                }
                playbackUyvy[dst++] = chroma(0)
                playbackUyvy[dst++] = y0.toByte()
                playbackUyvy[dst++] = chroma(4)
                playbackUyvy[dst++] = y1.toByte()
            }
        }
        if (dstStride == rowBytes) {
            memory.write(dstAddr, ByteBuffer.wrap(playbackUyvy, 0, dst))
        } else {
            for (y in 0 until height) {
                memory.write(dstAddr + y.toLong() * dstStride, ByteBuffer.wrap(playbackUyvy, y * rowBytes, rowBytes))
            }
        }
    }

    private fun writeMovieTiles(
        memory: DmaMemory, srcAddr: Long, srcStride: Int, cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstAddr: Long, dstStride: Int, width: Int, height: Int,
    ) {
        val tileRowBytes = ((width + 1) / 2) * 8
        val srcRowBytes = ((cropX + cropW + 1) and -2) * 2
        require(srcStride >= srcRowBytes && dstStride >= tileRowBytes)
        val srcSpan = (cropH - 1L) * srcStride + srcRowBytes
        val dstSpan = ((height - 1L) / 2) * dstStride + tileRowBytes
        require(srcSpan in 1..MAX_PIXEL_DMA_BYTES && dstSpan in 1..MAX_PIXEL_DMA_BYTES)
        val srcBase = srcAddr + cropY.toLong() * srcStride
        memory.check(srcBase, srcSpan, false)
        memory.check(dstAddr, dstSpan, true)
        if (movieSource.size < srcSpan) movieSource = ByteArray(srcSpan.toInt())
        if (movieTileRow.size < tileRowBytes) movieTileRow = ByteArray(tileRowBytes)
        val src = movieSource
        memory.read(srcBase, ByteBuffer.wrap(src, 0, srcSpan.toInt()))
        val row = ByteBuffer.wrap(movieTileRow, 0, tileRowBytes).order(ByteOrder.LITTLE_ENDIAN)
        val xOffsets = IntArray(width) { x -> (cropX + x.toLong() * cropW / width).toInt() }
        for (dy in 0 until height step 2) {
            val top = (dy.toLong() * cropH / height).toInt() * srcStride
            val bottom = (minOf(dy + 1, height - 1).toLong() * cropH / height).toInt() * srcStride
            row.position(0)
            for (dx in 0 until width step 2) {
                val x0 = xOffsets[dx]
                val x1 = xOffsets[minOf(dx + 1, width - 1)]
                val y0 = x0 * 2 + 1
                val y1 = x1 * 2 + 1
                val chroma = (x0 and -2) * 2
                val cb = ((src[top + chroma].toInt() and 255) + (src[bottom + chroma].toInt() and 255) + 1) / 2
                val cr = ((src[top + chroma + 2].toInt() and 255) + (src[bottom + chroma + 2].toInt() and 255) + 1) / 2
                fun pack(line: Int, c: Int): Int = ((src[line + y0].toInt() and 255) shl 2) or
                    (c shl 12) or ((src[line + y1].toInt() and 255) shl 22)
                row.putInt(pack(top, cb))
                row.putInt(pack(bottom, cr))
            }
            row.flip()
            memory.write(dstAddr + (dy / 2).toLong() * dstStride, row)
        }
    }

    private fun synthesizeHighAxiSourceToUyvyDst(
        tick: Long,
        memory: DmaMemory,
        sensor: SensorPort,
        lens: LensParameters?,
        exposurePort: Imx461ExposurePort?,
        hwSrcW: Int,
        hwSrcH: Int,
        hwCropX: Int,
        hwCropY: Int,
        hwCropW: Int,
        hwCropH: Int,
        dstAddr: Long,
        dstW: Int,
        dstH: Int,
        dstStrideBytes: Int,
    ) {
        val evenW = dstW and 1.inv()
        if (evenW < 2 || dstH < 1 || dstStrideBytes < evenW * 2) return
        val dstSpan = ((dstH - 1).toLong() * dstStrideBytes) + evenW.toLong() * 2L
        if (dstSpan !in 1L..MAX_PIXEL_DMA_BYTES || dstAddr !in 1L..(0x1_0000_0000L - dstSpan)) return
        try {
            memory.check(dstAddr, dstSpan, true)
        } catch (_: IllegalArgumentException) {
            return
        }

        val rawCapacity = maxOf(MAX_SYNTH_SENSOR_BYTES, evenW * dstH * 2)
        val rawBytes = rawSensorScratch?.takeIf { it.size >= rawCapacity }
            ?: ByteArray(rawCapacity).also { rawSensorScratch = it }
        val rawBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val effExp = Imx461ExposurePort.computeEffectiveExposure(exposurePort, NOMINAL_DURATION_NANOS, NOMINAL_ISO)
        if ((exposurePort == null || !exposurePort.hasExposureRegs) && !warnedNominalExposureFallback) {
            warnedNominalExposureFallback = true
            LOGGER.warn("[FALLBACK][ImageScalerE000] Imx461ExposurePort unavailable/unprogrammed for high-AXI synthesis; using nominal duration={}ns iso={}", effExp.durationNanos, effExp.iso)
        }
        val exposedBytes = try {
            sensor.expose(Exposure(tick, effExp.durationNanos, effExp.iso, lens ?: DEFAULT_LENS), rawBuf)
        } catch (_: IllegalStateException) {
            return
        }
        if (exposedBytes <= 0) return

        val candidateDims = listOf(
            1024 to 576,
            1024 to 768,
            640 to 480,
            1280 to 960,
            1920 to 1080,
            evenW to dstH,
        )
        val matched = candidateDims.firstOrNull { (w, h) -> w * h * 2 == exposedBytes }
            ?.let { (w, h) -> Triple(w, h, true) }
            ?: candidateDims.firstOrNull { (w, h) -> w * h == exposedBytes }
                ?.let { (w, h) -> Triple(w, h, false) }
            ?: return

        val (srcW, srcH, is16Bit) = matched
        // Map firmware RESZ crop (E010/E014) proportionally onto optical sensor frame, then aspect-match
        // ("cover" center-crop) to evenW:dstH so STILL review & thumbnails never get black letterbox bars (BUG-V3-2-4).
        val normCropX = if (hwSrcW > 0) ((hwCropX.toDouble() / hwSrcW) * srcW).roundToInt().coerceIn(0, srcW - 2) else 0
        val normCropY = if (hwSrcH > 0) ((hwCropY.toDouble() / hwSrcH) * srcH).roundToInt().coerceIn(0, srcH - 2) else 0
        val normCropW = if (hwSrcW > 0) ((hwCropW.toDouble() / hwSrcW) * srcW).roundToInt().coerceIn(2, srcW - normCropX) else srcW
        val normCropH = if (hwSrcH > 0) ((hwCropH.toDouble() / hwSrcH) * srcH).roundToInt().coerceIn(2, srcH - normCropY) else srcH
        val cropAspect = normCropW.toDouble() / normCropH.toDouble()
        val dstAspect = evenW.toDouble() / dstH.toDouble()
        val fitCropW: Int
        val fitCropH: Int
        if (cropAspect > dstAspect + 1e-4) {
            fitCropH = normCropH
            fitCropW = (normCropH.toDouble() * dstAspect).roundToInt().coerceIn(2, normCropW)
        } else if (dstAspect > cropAspect + 1e-4) {
            fitCropW = normCropW
            fitCropH = (normCropW.toDouble() / dstAspect).roundToInt().coerceIn(2, normCropH)
        } else {
            fitCropW = normCropW
            fitCropH = normCropH
        }
        val bayerX = (normCropX + (normCropW - fitCropW) / 2).coerceIn(0, srcW - 2) and 1.inv()
        val bayerY = (normCropY + (normCropH - fitCropH) / 2).coerceIn(0, srcH - 2) and 1.inv()
        val bayerW = (fitCropW and 1.inv()).coerceIn(2, srcW - bayerX)
        val bayerH = (fitCropH and 1.inv()).coerceIn(2, srcH - bayerY)
        val dstBuf = ByteArray(dstSpan.toInt())
        if (bayerX == 0 && bayerY == 0 && bayerW == srcW && bayerH == srcH) {
            ImageLiveViewBanks.demosaicBayerToUyvy(rawBytes, srcW, srcH, is16Bit, dstBuf, evenW, dstH, dstStrideBytes)
        } else {
            val bpp = if (is16Bit) 2 else 1
            val croppedRaw = ByteArray(bayerW * bayerH * bpp)
            val srcRowBytes = srcW * bpp
            val dstRowBytes = bayerW * bpp
            for (r in 0 until bayerH) {
                System.arraycopy(rawBytes, (bayerY + r) * srcRowBytes + bayerX * bpp, croppedRaw, r * dstRowBytes, dstRowBytes)
            }
            ImageLiveViewBanks.demosaicBayerToUyvy(croppedRaw, bayerW, bayerH, is16Bit, dstBuf, evenW, dstH, dstStrideBytes)
        }

        var writeDone = 0
        while (writeDone < dstBuf.size) {
            val chunk = minOf(DMA_CHUNK_BYTES, dstBuf.size - writeDone)
            val slice = ByteBuffer.wrap(dstBuf, writeDone, chunk)
            memory.write(dstAddr + writeDone.toLong(), slice)
            require(slice.remaining() == 0) { "Incomplete RESZ destination DMA write" }
            writeDone += chunk
        }
    }

    fun armPendingKickOnRestore(currentTick: Long) {
        if (profile != IRQ132_PROFILE || pendingCompletionTick != null || irqAsserted) return
        if ((registerValues[E004_SLOT] and 1L) != 0L &&
            (registerValues[E0C4_SLOT] != 0L || registerValues[E0D8_SLOT] != 0L || registerValues[E0EC_SLOT] != 0L)
        ) {
            registerValues[E10C_SLOT] = registerValues[E10C_SLOT] and 1L.inv()
            pendingCompletionTick = currentTick + COMPLETION_DELAY_TICKS
        }
    }

    private fun observableWord(address: Long, slot: Int): Long {
        val stored = registerValues[slot]
        return when (address) {
            0xfff7e000L -> stored or 1L
            0xfff7e10cL -> statusWithIdleBits(stored).let { base ->
                if (profile == IRQ132_PROFILE) {
                    if (irqAsserted) base or 1L else base and 1L.inv()
                } else base
            }
            else -> stored
        }
    }

    private fun statusWithIdleBits(stored: Long): Long {
        val e100Idle = (registerValues[E100_SLOT] and E100_ENABLE_BIT_28) == 0L
        val e104Idle = (registerValues[E104_SLOT] and E104_ENABLE_BITS_31_28) == 0L
        var value = stored and (IDLE_BIT_8 or IDLE_BIT_24).inv()
        if (e100Idle) value = value or IDLE_BIT_8
        if (e104Idle) value = value or IDLE_BIT_24
        return value
    }

    fun receipt(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "nonZeroRegisterCount" to registerValues.count { it != 0L },
    ).apply {
        if (profile == IRQ132_PROFILE) {
            put("irqLine", IRQ_LINE)
            put("pendingCompletionTick", pendingCompletionTick)
            put("irqAsserted", irqAsserted)
            put("scaleCount", scaleCount)
        }
    }

    fun snapshot(): Map<String, Any?> {
        val savedRegisters = linkedMapOf<String, Long>()
        for (i in REGISTER_ADDRESSES.indices) {
            if (registerValues[i] != 0L) {
                savedRegisters["0x%08x".format(REGISTER_ADDRESSES[i])] = registerValues[i]
            }
        }
        return linkedMapOf<String, Any?>(
            "profile" to profile,
            "confidence" to "HYPOTHESIS",
            "registers" to savedRegisters,
        ).apply {
            if (profile == IRQ132_PROFILE) {
                put("pendingCompletionTick", pendingCompletionTick)
                put("irqAsserted", irqAsserted)
                put("scaleCount", scaleCount)
            }
            if (e014Written) {
                put("e014Written", true)
            }
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(ImageScalerE000::class.java)
        const val KEY = "imageScalerE000"
        const val PROFILE = "fff7e000-reset-idle-v1"
        const val IRQ132_PROFILE = "fff7e000-irq132-completion-v1"
        val PROFILES = setOf(PROFILE, IRQ132_PROFILE)
        const val REGION = "diagnostic.image-scaler.fff7e000"
        const val BASE = 0xfff7e000L
        const val SIZE = 0x11c
        const val IRQ_LINE = 132
        const val COMPLETION_DELAY_TICKS = 64L
        // 102MP GFX100S in-camera RAW conversion runs full-sensor RESZ passes up to ~277 MB (11648x8736+stride); allow up to 512 MiB.
        private const val MAX_PIXEL_DMA_BYTES = 512L * 1024L * 1024L
        private const val DMA_CHUNK_BYTES = 8 * 1024 * 1024
        private const val FAST_SCALE_PIXEL_THRESHOLD = 4_000_000L
        private const val MAX_SYNTH_SENSOR_BYTES = 1920 * 1080 * 2
        private const val NOMINAL_DURATION_NANOS = 1000L
        private const val NOMINAL_ISO = 100
        private val DEFAULT_LENS = LensParameters("GF63mmF2.8", 63.0, 2.8, 10.0, 0.9, 0.0)

        private const val E100_ENABLE_BIT_28 = 0x10000000L
        private const val E104_ENABLE_BITS_31_28 = 0xf0000000L
        private const val IDLE_BIT_8 = 0x00000100L
        private const val IDLE_BIT_24 = 0x01000000L

        private val REGISTER_ADDRESSES: LongArray = buildList {
            for (offset in 0x00..0x118 step 4) add(BASE + offset)
        }.toLongArray()

        private val E004_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e004L)
        private val E008_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e008L)
        private val E00C_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e00cL)
        private val E010_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e010L)
        private val E014_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e014L)
        private val E018_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e018L)
        private val E01C_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e01cL)
        private val E020_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e020L)
        private val E0C0_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0c0L)
        private val E0C4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0c4L)
        private val E0D0_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0d0L)
        private val E0D4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0d4L)
        private val E0D8_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0d8L)
        private val E0E4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0e4L)
        private val E0EC_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e0ecL)
        private val E100_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e100L)
        private val E104_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e104L)
        private val E10C_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7e10cL)

        private val REGISTER_BY_HEX: Map<String, Int> = REGISTER_ADDRESSES.withIndex()
            .associate { (index, address) -> "0x%08x".format(address) to index }

        fun region(selection: Any): DiscoveryRegion {
            require(selection in PROFILES) { "Unknown $KEY selection: $selection" }
            return DiscoveryRegion(
                id = REGION,
                deviceId = "section4-011fcc68",
                base = BASE,
                length = SIZE,
                strategy = DiscoveryStrategy.IMAGE_SCALER_E000,
                widths = setOf(4),
                alignment = 4,
                littleEndian = true,
                evidence = listOf(
                    "DAT section4 011FCC68..011FDAF8 & caller/ISR 0128F434..01290490 (ch 9 -> GIC IRQ 132): 0xFFF7E000..0xFFF7E118",
                    "HYPOTHESIS $selection: 0xFFF7E000 asserts bit 0 ready; 0xFFF7E10C asserts bit 8 when E100 bit 28 is clear and bit 24 when E104 bits 31:28 are clear; other DAT offsets retain 32-bit words" +
                        if (selection == IRQ132_PROFILE) "; writing 0xFFF7E004 bit 0 after 0xFFF7E0C0..E0F8 setup performs UYVY DMA scale and asserts GIC level IRQ 132 after $COMPLETION_DELAY_TICKS ticks, cleared on 0xFFF7E10C write-1" else "",
                ),
            )
        }

        fun validRegion(region: DiscoveryRegion): Boolean =
            region.id == REGION && region.base == BASE && region.length == SIZE &&
                region.widths == setOf(4) && region.alignment == 4 && region.littleEndian &&
                region.prohibited.isEmpty() &&
                region.initial == null && region.samples == null && region.fixedValue == null &&
                region.retainedDiagnostic == null

        fun restore(
            raw: Any?,
            targetProfile: String = PROFILE,
            allowPromotion: Boolean = false,
        ): ImageScalerE000 {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing $KEY snapshot state")
            val savedProfile = map["profile"] as? String ?: throw IllegalArgumentException("Invalid $KEY profile")
            require(savedProfile in PROFILES && map["confidence"] == "HYPOTHESIS") {
                "Invalid $KEY snapshot header"
            }
            require(savedProfile == targetProfile || (allowPromotion && savedProfile == PROFILE && targetProfile == IRQ132_PROFILE)) {
                "Incompatible $KEY profile: saved=$savedProfile target=$targetProfile"
            }
            val expectedKeys = if (savedProfile == IRQ132_PROFILE) {
                setOf("profile", "confidence", "registers", "pendingCompletionTick", "irqAsserted", "scaleCount")
            } else {
                setOf("profile", "confidence", "registers")
            }
            require(map.keys == expectedKeys || map.keys == expectedKeys + "e014Written") { "Invalid $KEY snapshot keys" }
            val rawRegs = map["registers"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid $KEY registers")
            val restored = LongArray(REGISTER_ADDRESSES.size)
            for ((k, v) in rawRegs) {
                val key = k as? String ?: throw IllegalArgumentException("Invalid $KEY register key")
                val slot = REGISTER_BY_HEX[key] ?: throw IllegalArgumentException("Unknown $KEY register: $key")
                require(v is Int || v is Long) { "Invalid $KEY register value for $key" }
                val value = (v as Number).toLong()
                require(value in 1L..0xffffffffL) { "Out-of-range $KEY register value for $key" }
                restored[slot] = value
            }
            val pendingTick = if (savedProfile == IRQ132_PROFILE) {
                map["pendingCompletionTick"]?.let { rawTick ->
                    require(rawTick is Int || rawTick is Long) { "Invalid $KEY pendingCompletionTick" }
                    (rawTick as Number).toLong().also { require(it >= 0L) }
                }
            } else null
            val asserted = if (savedProfile == IRQ132_PROFILE) {
                map["irqAsserted"] as? Boolean ?: throw IllegalArgumentException("Invalid $KEY irqAsserted")
            } else false
            val restoredScaleCount = if (savedProfile == IRQ132_PROFILE) {
                val rawCount = map["scaleCount"]
                require(rawCount is Int || rawCount is Long) { "Invalid $KEY scaleCount" }
                (rawCount as Number).toLong().also { require(it >= 0L) }
            } else 0L
            val restoredE014Written = if ("e014Written" in map) {
                (map["e014Written"] as? Boolean ?: throw IllegalArgumentException("Invalid $KEY e014Written")) ||
                    restored[E014_SLOT] != 0L
            } else {
                restored[E014_SLOT] != 0L
            }
            return ImageScalerE000(restored, targetProfile, pendingTick, asserted, restoredE014Written, restoredScaleCount)
        }
    }
}

/**
 * Normalizes DAT-proven GFX100S 2.13 Milbeaut Playback black margin fill words
 * (`0x00000000`, `0x80800000` LE = `[00 00 80 80]`, `0x00008080` LE = `[80 80 00 00]`)
 * in-place to canonical BT.601 packed UYVY neutral black (`[80 00 80 00]`) before
 * bilinear/nearest scaling or decimation, preventing U=0 / Y1=0x80 interpolation into
 * olive-green vertical stripes during Playback 9/100-frame grid transitions (BUG-V3-8-4).
 */
internal fun normalizeMilbeautBlackMarginWords(buf: ByteArray, length: Int = buf.size) {
    var i = 0
    val limit = length and 3.inv()
    while (i < limit) {
        val b0 = buf[i].toInt() and 0xff
        val b1 = buf[i + 1].toInt() and 0xff
        val b2 = buf[i + 2].toInt() and 0xff
        val b3 = buf[i + 3].toInt() and 0xff
        if ((b0 or b1 or b2 or b3) == 0 ||
            (b0 == 0 && b1 == 0 && b2 == 0x80 && b3 == 0x80) ||
            (b0 == 0x80 && b1 == 0x80 && b2 == 0 && b3 == 0)
        ) {
            buf[i] = 0x80.toByte()
            buf[i + 1] = 0
            buf[i + 2] = 0x80.toByte()
            buf[i + 3] = 0
        }
        i += 4
    }
}
