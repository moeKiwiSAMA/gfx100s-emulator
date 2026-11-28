package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaBoundsUnavailableException
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.FacePreviewScalerPort
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * Explicit behavioral discovery support for the 0xFFF7F000 live-view video scaler/resizer bank
 * (DAT section4.bin 011FDB00..011FE784, caller 01290584..012913A8, GIC IRQ 133):
 * - 0xFFF7F000..0xFFF7F0E8 (59 aligned 32-bit words)
 * - Hole: 0xFFF7F0EC..0xFFF7F0FC (prohibited)
 * - 0xFFF7F100, 0xFFF7F104 (2 aligned 32-bit words)
 *
 * Behavioral rules (HYPOTHESIS):
 * - 0xFFF7F000: 011FDB70..011FDB90 writes 0 and polls bit 0 == 1 until reset completes; reads assert bit 0.
 * - 0xFFF7F0E4: 011FDB08..011FDB68 clears 0xFFF7F0DC bit 28 and 0xFFF7F0E0 bits 31:28, then polls
 *   0xFFF7F0E4 == 0x01000100; reads assert bit 8 (0x100) when 0xFFF7F0DC bit 28 is clear and bit 24
 *   (0x01000000) when 0xFFF7F0E0 bits 31:28 are clear.
 * - All other DAT-verified offsets retain written 32-bit words (initially 0).
 */
internal class ImageScalerF000 private constructor(
    private val registerValues: LongArray,
    val profile: String = PROFILE,
    private var pendingCompletionTick: Long? = null,
    private var irqAsserted: Boolean = false,
    private var f014Written: Boolean = false,
    private var warnedHighAxiFallback: Boolean = false,
) {
    constructor(profile: String = PROFILE) : this(LongArray(REGISTER_ADDRESSES.size), profile) {
        require(profile in PROFILES) { "Unknown $KEY selection: $profile" }
    }

    // Per-device host scratch, overwritten on each DMA. Guest snapshots contain only registers and deadlines.
    private var sourceScratch = ByteBuffer.allocate(0)
    private var destinationScratch = ByteBuffer.allocate(0)
    private var sourceColumn = IntArray(0)
    private var horizontalWeight = DoubleArray(0)

    private val sourceAddress: Long
        get() = ((registerValues[F0C0_SLOT] and 3L) shl 32) or (registerValues[C4_SLOT] and 0xffff_ffffL)
    private val destinationAddress: Long
        get() = ((registerValues[F0CC_SLOT] and 3L) shl 32) or (registerValues[D0_SLOT] and 0xffff_ffffL)

    fun nextDeadline(): Long? = if (profile == IRQ133_PROFILE) pendingCompletionTick else null
    fun nextDeadlinePrimitive(): Long = if (profile == IRQ133_PROFILE) (pendingCompletionTick ?: Long.MAX_VALUE) else Long.MAX_VALUE

    fun access(
        address: Long,
        write: Boolean,
        value: Long,
        tick: Long = 0L,
        onAckIrq133: (() -> Unit)? = null,
    ): Triple<Long, Long, Long>? {
        val slot = Arrays.binarySearch(REGISTER_ADDRESSES, address)
        if (slot < 0) return null
        val prior = observableWord(address, slot)
        if (write) {
            if (profile == IRQ133_PROFILE) {
                when {
                    address == 0xfff7f000L && value == 0L -> {
                        pendingCompletionTick = null
                        if (irqAsserted) {
                            irqAsserted = false
                            onAckIrq133?.invoke()
                        }
                        registerValues[F004_SLOT] = registerValues[F004_SLOT] and 1L.inv()
                        f014Written = false
                        registerValues[slot] = value
                    }
                    address == 0xfff7f0e4L -> {
                        if ((value and 1L) != 0L && irqAsserted) {
                            irqAsserted = false
                            onAckIrq133?.invoke()
                        }
                        registerValues[slot] = value and 1L.inv()
                    }
                    address == 0xfff7f004L -> {
                        registerValues[slot] = value
                        if ((value and 1L) != 0L && (sourceAddress != 0L || destinationAddress != 0L)) {
                            pendingCompletionTick = tick + COMPLETION_DELAY_TICKS
                        }
                    }
                    address == 0xfff7f014L -> {
                        f014Written = true
                        registerValues[slot] = value
                    }
                    else -> registerValues[slot] = value
                }
            } else {
                when {
                    address == 0xfff7f000L && value == 0L -> {
                        registerValues[F004_SLOT] = registerValues[F004_SLOT] and 1L.inv()
                        f014Written = false
                        registerValues[slot] = value
                    }
                    address == 0xfff7f014L -> {
                        f014Written = true
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

    fun beforeInstruction(
        tick: Long,
        dmaMemory: DmaMemory? = null,
        skipUnmappedDma: Boolean = false,
        assertIrq133: () -> Unit,
    ) = beforeInstruction(tick, dmaMemory, skipUnmappedDma, null, assertIrq133)

    fun beforeInstruction(
        tick: Long,
        dmaMemory: DmaMemory?,
        skipUnmappedDma: Boolean,
        scaler: FacePreviewScalerPort?,
        assertIrq133: () -> Unit,
    ) {
        if (profile != IRQ133_PROFILE) return
        val due = pendingCompletionTick ?: return
        if (tick < due) return
        pendingCompletionTick = null
        performPixelDma(dmaMemory, skipUnmappedDma, scaler)
        irqAsserted = true
        registerValues[F004_SLOT] = registerValues[F004_SLOT] and 1L.inv()
        assertIrq133()
    }

    private fun performPixelDma(dmaMemory: DmaMemory?, skipUnmappedDma: Boolean, scaler: FacePreviewScalerPort?) {
        val srcAddr = sourceAddress
        val dstAddr = destinationAddress
        val srcStrideBytes = (registerValues[F0C8_SLOT] and 0x3ffffL).toInt()
        val dstStrideBytes = (registerValues[F0D4_SLOT] and 0x3ffffL).toInt()
        if (srcAddr == 0L || dstAddr == 0L || srcStrideBytes == 0 || dstStrideBytes == 0) return

        val memory = requireNotNull(dmaMemory) { "UniResz pixel DMA requires DmaMemory" }
        val f008 = registerValues[F008_SLOT]
        require((f008 and 0x11L) == 0L) {
            "Unsupported UniResz pixel format: F008=0x${f008.toString(16)}"
        }

        val srcW = ((registerValues[F00C_SLOT] and 0x3fffL) + 1L).toInt()
        val srcH = (((registerValues[F00C_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()
        val cropX = (registerValues[F010_SLOT] and 0x3fffL).toInt()
        val cropY = ((registerValues[F010_SLOT] ushr 16) and 0x3fffL).toInt()
        val hDecim = ((registerValues[F01C_SLOT] ushr 16) and 3L).toInt()
        val vDecim = ((registerValues[F020_SLOT] ushr 16) and 3L).toInt()
        val cropWDecim = ((registerValues[F014_SLOT] and 0x3fffL) + 1L).toInt()
        val cropHDecim = (((registerValues[F014_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()
        val useF014Crop = f014Written || registerValues[F014_SLOT] != 0L
        val cropW = if (useF014Crop) minOf(srcW - cropX, cropWDecim shl hDecim) else (srcW - cropX)
        val cropH = if (useF014Crop) minOf(srcH - cropY, cropHDecim shl vDecim) else (srcH - cropY)
        val dstW = ((registerValues[F018_SLOT] and 0x3fffL) + 1L).toInt()
        val dstH = (((registerValues[F018_SLOT] ushr 16) and 0x3fffL) + 1L).toInt()

        require(cropX in 0 until srcW && cropY in 0 until srcH && cropW >= 1 && cropH >= 1) {
            "Invalid UniResz crop ($cropX,$cropY,${cropW}x$cropH) for ${srcW}x$srcH"
        }
        require(srcStrideBytes >= (cropX + cropW) * 2 && dstStrideBytes >= dstW * 2) {
            "UniResz stride too small: srcStride=$srcStrideBytes dstStride=$dstStrideBytes"
        }
        val lastRowBytes = minOf(srcStrideBytes.toLong(), ((cropX + cropW + 1) and 1.inv()).toLong() * 2L)
        val srcSpan = ((cropY + cropH - 1).toLong() * srcStrideBytes) + lastRowBytes
        val dstSpan = ((dstH - 1).toLong() * dstStrideBytes) + dstW.toLong() * 2L
        require(srcSpan in 1L..MAX_PIXEL_DMA_BYTES && dstSpan in 1L..MAX_PIXEL_DMA_BYTES) {
            "UniResz DMA span out of bounds: srcSpan=$srcSpan dstSpan=$dstSpan max=$MAX_PIXEL_DMA_BYTES"
        }
        require(srcAddr <= AXI_LIMIT - srcSpan && dstAddr <= AXI_LIMIT - dstSpan) {
            "UniResz DMA span out of 34-bit physical range"
        }
        val highAxi = srcAddr + srcSpan > 0x1_0000_0000L || dstAddr + dstSpan > 0x1_0000_0000L
        try {
            memory.check(srcAddr, srcSpan, false)
            memory.check(dstAddr, dstSpan, true)
        } catch (ex: RuntimeException) {
            val canSkip = when (ex) {
                is IllegalArgumentException -> highAxi || skipUnmappedDma
                // Legacy ports may omit bounds qualification or support only 32-bit RAM.
                is DmaBoundsUnavailableException, is UnsupportedOperationException -> highAxi
                else -> false
            }
            if (!canSkip) throw ex
            if (highAxi && !warnedHighAxiFallback) {
                warnedHighAxiFallback = true
                LOGGER.warn("[FALLBACK][ImageScalerF000] Unmapped DMA span src=0x{} dst=0x{}; completing IRQ 133",
                    srcAddr.toString(16), dstAddr.toString(16))
            }
            return
        }

        // Real-silicon GFX100S 2.13 Milbeaut packed 4:2:2 layout is YYUV422 [Y0, Y1, Cb, Cr] on all paths.
        val yyuv422Out = true
        if (scaler?.supportsFacePreviewResize == true && memory is DirectGuestRamPort && srcW == 1024 && srcH == 576 &&
            cropX == 0 && cropY == 0 && cropW == srcW && cropH == srcH && hDecim == 0 && vDecim == 0 &&
            dstW == 640 && dstH == 360 && (srcStrideBytes == 2048 || srcStrideBytes == 2560) && dstStrideBytes == 1280) {
            val source = memory.sliceDirect(srcAddr, srcSpan, false)
            val target = memory.sliceDirect(dstAddr, dstSpan, true)
            if (source != null && source.isNative && target != null && target.isNative &&
                scaler.resizeFacePreview(source, srcStrideBytes, target, yyuv422Out, yyuvIn = true)) {
                memory.notifyDirectWrite(dstAddr, dstSpan)
                return
            }
        }

        val srcSize = srcSpan.toInt()
        if (sourceScratch.capacity() < srcSize) sourceScratch = ByteBuffer.allocate(srcSize)
        sourceScratch.clear().limit(srcSize)
        memory.read(srcAddr, sourceScratch)
        require(sourceScratch.position() == srcSize) { "Incomplete UniResz source DMA read" }
        val src = sourceScratch.array()
        normalizeMilbeautBlackMarginWords(src, srcSize)

        val stepX = 1 shl hDecim
        val stepY = 1 shl vDecim
        val rowBytes = dstW * 2
        val packedOutput = dstStrideBytes == rowBytes
        val dstSize = if (packedOutput) dstSpan.toInt() else rowBytes
        if (destinationScratch.capacity() < dstSize) destinationScratch = ByteBuffer.allocate(dstSize)
        val output = destinationScratch.array()

        // Integer-exact fast path for the stock 1024x576 -> 640x360 face/eye-AF preview geometry.
        // Uses the same odd-denominator rational arithmetic as the Vulkan UniResz shader so CPU and
        // GPU outputs remain byte-identical while avoiding per-pixel closures and Double math.
        if (srcW == 1024 && srcH == 576 && cropX == 0 && cropY == 0 && cropW == 1024 && cropH == 576 &&
            dstW == 640 && dstH == 360 && stepX == 1 && stepY == 1 && packedOutput
        ) {
            scaleFacePreview1024x576To640x360(src, srcStrideBytes, output, yyuv422Out)
            memory.write(dstAddr, destinationScratch.clear().limit(dstSize))
            return
        }
        val maxSrcX = cropX + cropW - 1
        val maxSrcY = cropY + cropH - 1

        fun samplePixel(x: Int, y: Int): Int {
            val row = y * srcStrideBytes
            val pairCol = (x and 1.inv()) * 2
            val pairOff = row + pairCol
            val luma = src[pairOff + (x and 1)].toInt() and 255
            val u = if (pairCol + 2 < srcStrideBytes && pairOff + 2 < srcSize) src[pairOff + 2].toInt() and 255 else 128
            val v = if (pairCol + 3 < srcStrideBytes && pairOff + 3 < srcSize) src[pairOff + 3].toInt() and 255 else 128
            return (luma shl 16) or (u shl 8) or v
        }

        fun sampleBox(bx: Int, by: Int): Int {
            if (stepX == 1 && stepY == 1) return samplePixel(bx, by)
            val xEnd = minOf(bx + stepX, cropX + cropW)
            val yEnd = minOf(by + stepY, cropY + cropH)
            var luma = 0
            var u = 0
            var v = 0
            for (y in by until yEnd) {
                for (x in bx until xEnd) {
                    val pixel = samplePixel(x, y)
                    luma += pixel ushr 16
                    u += (pixel ushr 8) and 255
                    v += pixel and 255
                }
            }
            val count = (xEnd - bx) * (yEnd - by)
            return ((luma / count) shl 16) or ((u / count) shl 8) or (v / count)
        }

        fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Int {
            val top = c00 + (c10 - c00) * fx
            val bot = c01 + (c11 - c01) * fx
            return (top + (bot - top) * fy + 0.5).toInt().coerceIn(0, 255)
        }

        // The horizontal mapping is identical for every destination row.
        if (sourceColumn.size < dstW) {
            sourceColumn = IntArray(dstW)
            horizontalWeight = DoubleArray(dstW)
        }
        for (dx in 0 until dstW) {
            val sx = if (dstW == 1) cropX.toDouble() else cropX + dx.toDouble() * (cropW - 1) / (dstW - 1)
            val x = sx.toInt().coerceIn(cropX, maxSrcX)
            sourceColumn[dx] = x
            horizontalWeight[dx] = ((sx - x) / stepX).coerceIn(0.0, 1.0)
        }

        fun resample(dx: Int, y0: Int, y1: Int, fy: Double): Int {
            val x0 = sourceColumn[dx]
            val x1 = minOf(x0 + stepX, maxSrcX)
            val fx = horizontalWeight[dx]
            val a = sampleBox(x0, y0)
            val b = sampleBox(x1, y0)
            val c = sampleBox(x0, y1)
            val d = sampleBox(x1, y1)
            val luma = bilinear(a ushr 16, b ushr 16, c ushr 16, d ushr 16, fx, fy)
            val u = bilinear((a ushr 8) and 255, (b ushr 8) and 255, (c ushr 8) and 255, (d ushr 8) and 255, fx, fy)
            val v = bilinear(a and 255, b and 255, c and 255, d and 255, fx, fy)
            return (luma shl 16) or (u shl 8) or v
        }

        for (dy in 0 until dstH) {
            val sy = if (dstH == 1) cropY.toDouble() else cropY + (dy.toDouble() * (cropH - 1)) / (dstH - 1)
            val y0 = sy.toInt().coerceIn(cropY, maxSrcY)
            val y1 = (y0 + stepY).coerceIn(cropY, maxSrcY)
            val fy = ((sy - y0) / stepY).coerceIn(0.0, 1.0)

            val dstRow = if (packedOutput) dy * rowBytes else 0
            var dx = 0
            while (dx < dstW) {
                val first = resample(dx, y0, y1, fy)
                val outY0 = first ushr 16
                val outU0 = (first ushr 8) and 255
                val p = dstRow + dx * 2
                if (dx + 1 < dstW) {
                    val second = resample(dx + 1, y0, y1, fy)
                    val outY1 = second ushr 16
                    val u = (outU0 + ((second ushr 8) and 255)) / 2
                    val v = ((first and 255) + (second and 255)) / 2
                    if (yyuv422Out) {
                        // DAT s5:0x025a7658 / 0x025a7770..94: Face/Eye-AF buffer (F0D8=0x88 -> 0x8a9c8000)
                        // packs YYUV422 [Y0, Y1, Cb, Cr] (luma at (x>>1)*4 + (x&1)).
                        output[p] = outY0.toByte()
                        output[p + 1] = outY1.toByte()
                        output[p + 2] = u.toByte()
                        output[p + 3] = v.toByte()
                    } else {
                        output[p] = u.toByte()
                        output[p + 1] = outY0.toByte()
                        output[p + 2] = v.toByte()
                        output[p + 3] = outY1.toByte()
                    }
                } else if (yyuv422Out) {
                    output[p] = outY0.toByte()
                    output[p + 1] = outY0.toByte()
                } else {
                    output[p] = outU0.toByte()
                    output[p + 1] = outY0.toByte()
                }
                dx += 2
            }
            if (!packedOutput) {
                memory.write(dstAddr + dy.toLong() * dstStrideBytes, destinationScratch.clear().limit(rowBytes))
            }
        }
        if (packedOutput) {
            memory.write(dstAddr, destinationScratch.clear().limit(dstSize))
        }
    }

    private fun scaleFacePreview1024x576To640x360(
        src: ByteArray,
        srcStrideBytes: Int,
        output: ByteArray,
        yyuv422Out: Boolean,
    ) {
        val x0Y = FACE_X0_Y_OFF
        val x1Y = FACE_X1_Y_OFF
        val x0Uv = FACE_X0_UV_OFF
        val x1Uv = FACE_X1_UV_OFF
        val wxArr = FACE_WX
        val cxArr = FACE_CX
        val y0Arr = FACE_Y0
        val y1Arr = FACE_Y1
        val wyArr = FACE_WY
        val cyArr = FACE_CY
        for (dy in 0 until 360) {
            val r0 = y0Arr[dy] * srcStrideBytes
            val r1 = y1Arr[dy] * srcStrideBytes
            val wy = wyArr[dy]
            val cy = cyArr[dy]
            var outPos = dy * 1280
            var dx = 0
            while (dx < 640) {
                val wx0 = wxArr[dx]
                val cx0 = cxArr[dx]
                val aYOff0 = x0Y[dx]
                val bYOff0 = x1Y[dx]
                val aUvOff0 = x0Uv[dx]
                val bUvOff0 = x1Uv[dx]
                val topY0 = (src[r0 + aYOff0].toInt() and 0xff) * cx0 + (src[r0 + bYOff0].toInt() and 0xff) * wx0
                val botY0 = (src[r1 + aYOff0].toInt() and 0xff) * cx0 + (src[r1 + bYOff0].toInt() and 0xff) * wx0
                val outY0 = (topY0 * cy + botY0 * wy + FACE_HALF) / FACE_DENOM
                val topU0 = (src[r0 + aUvOff0].toInt() and 0xff) * cx0 + (src[r0 + bUvOff0].toInt() and 0xff) * wx0
                val botU0 = (src[r1 + aUvOff0].toInt() and 0xff) * cx0 + (src[r1 + bUvOff0].toInt() and 0xff) * wx0
                val outU0 = (topU0 * cy + botU0 * wy + FACE_HALF) / FACE_DENOM
                val topV0 = (src[r0 + aUvOff0 + 1].toInt() and 0xff) * cx0 + (src[r0 + bUvOff0 + 1].toInt() and 0xff) * wx0
                val botV0 = (src[r1 + aUvOff0 + 1].toInt() and 0xff) * cx0 + (src[r1 + bUvOff0 + 1].toInt() and 0xff) * wx0
                val outV0 = (topV0 * cy + botV0 * wy + FACE_HALF) / FACE_DENOM

                val dx1 = dx + 1
                val wx1 = wxArr[dx1]
                val cx1 = cxArr[dx1]
                val aYOff1 = x0Y[dx1]
                val bYOff1 = x1Y[dx1]
                val aUvOff1 = x0Uv[dx1]
                val bUvOff1 = x1Uv[dx1]
                val topY1 = (src[r0 + aYOff1].toInt() and 0xff) * cx1 + (src[r0 + bYOff1].toInt() and 0xff) * wx1
                val botY1 = (src[r1 + aYOff1].toInt() and 0xff) * cx1 + (src[r1 + bYOff1].toInt() and 0xff) * wx1
                val outY1 = (topY1 * cy + botY1 * wy + FACE_HALF) / FACE_DENOM
                val topU1 = (src[r0 + aUvOff1].toInt() and 0xff) * cx1 + (src[r0 + bUvOff1].toInt() and 0xff) * wx1
                val botU1 = (src[r1 + aUvOff1].toInt() and 0xff) * cx1 + (src[r1 + bUvOff1].toInt() and 0xff) * wx1
                val outU1 = (topU1 * cy + botU1 * wy + FACE_HALF) / FACE_DENOM
                val topV1 = (src[r0 + aUvOff1 + 1].toInt() and 0xff) * cx1 + (src[r0 + bUvOff1 + 1].toInt() and 0xff) * wx1
                val botV1 = (src[r1 + aUvOff1 + 1].toInt() and 0xff) * cx1 + (src[r1 + bUvOff1 + 1].toInt() and 0xff) * wx1
                val outV1 = (topV1 * cy + botV1 * wy + FACE_HALF) / FACE_DENOM

                val u = (outU0 + outU1) ushr 1
                val v = (outV0 + outV1) ushr 1
                if (yyuv422Out) {
                    output[outPos] = outY0.toByte()
                    output[outPos + 1] = outY1.toByte()
                    output[outPos + 2] = u.toByte()
                    output[outPos + 3] = v.toByte()
                } else {
                    output[outPos] = u.toByte()
                    output[outPos + 1] = outY0.toByte()
                    output[outPos + 2] = v.toByte()
                    output[outPos + 3] = outY1.toByte()
                }
                outPos += 4
                dx += 2
            }
        }
    }

    fun armPendingKickOnRestore(currentTick: Long) {
        if (profile != IRQ133_PROFILE || pendingCompletionTick != null || irqAsserted) return
        if ((registerValues[F004_SLOT] and 1L) != 0L && (sourceAddress != 0L || destinationAddress != 0L)) {
            registerValues[E4_SLOT] = registerValues[E4_SLOT] and 1L.inv()
            pendingCompletionTick = currentTick + COMPLETION_DELAY_TICKS
        }
    }

    private fun observableWord(address: Long, slot: Int): Long {
        val stored = registerValues[slot]
        return when (address) {
            0xfff7f000L -> stored or 1L
            0xfff7f0e4L -> statusWithIdleBits(stored).let { base ->
                if (profile == IRQ133_PROFILE) {
                    if (irqAsserted) base or 1L else base and 1L.inv()
                } else base
            }
            else -> stored
        }
    }

    private fun statusWithIdleBits(stored: Long): Long {
        val dcSlot = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0dcL)
        val e0Slot = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0e0L)
        val dcIdle = (registerValues[dcSlot] and DC_ENABLE_BIT_28) == 0L
        val e0Idle = (registerValues[e0Slot] and E0_ENABLE_BITS_31_28) == 0L
        var value = stored and (IDLE_BIT_8 or IDLE_BIT_24).inv()
        if (dcIdle) value = value or IDLE_BIT_8
        if (e0Idle) value = value or IDLE_BIT_24
        return value
    }

    fun receipt(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "nonZeroRegisterCount" to registerValues.count { it != 0L },
    ).apply {
        if (profile == IRQ133_PROFILE) {
            put("irqLine", IRQ_LINE)
            put("pendingCompletionTick", pendingCompletionTick)
            put("irqAsserted", irqAsserted)
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
            if (profile == IRQ133_PROFILE) {
                put("pendingCompletionTick", pendingCompletionTick)
                put("irqAsserted", irqAsserted)
            }
            if (f014Written) {
                put("f014Written", true)
            }
        }
    }

    companion object {
        private const val AXI_LIMIT = 1L shl 34
        private val LOGGER = LoggerFactory.getLogger(ImageScalerF000::class.java)
        const val KEY = "imageScalerF000"
        const val PROFILE = "fff7f000-reset-idle-v1"
        const val IRQ133_PROFILE = "fff7f000-irq133-completion-v1"
        val PROFILES = setOf(PROFILE, IRQ133_PROFILE)
        const val REGION = "diagnostic.image-scaler.fff7f000"
        const val BASE = 0xfff7f000L
        const val SIZE = 0x108
        const val IRQ_LINE = 133
        const val COMPLETION_DELAY_TICKS = 64L
        private const val MAX_PIXEL_DMA_BYTES = 64L * 1024L * 1024L

        private const val DC_ENABLE_BIT_28 = 0x10000000L
        private const val E0_ENABLE_BITS_31_28 = 0xf0000000L
        private const val IDLE_BIT_8 = 0x00000100L
        private const val IDLE_BIT_24 = 0x01000000L

        private val PROHIBITED = listOf(AddressInterval(0xfff7f0ecL, 0xfff7f100L))

        private val REGISTER_ADDRESSES: LongArray = buildList {
            for (offset in 0x00..0xe8 step 4) add(BASE + offset)
            add(0xfff7f100L)
            add(0xfff7f104L)
        }.toLongArray()

        private val F004_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f004L)
        private val F008_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f008L)
        private val F00C_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f00cL)
        private val F010_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f010L)
        private val F014_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f014L)
        private val F018_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f018L)
        private val F01C_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f01cL)
        private val F020_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f020L)
        private val F0C0_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0c0L)
        private val C4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0c4L)
        private val F0C8_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0c8L)
        private val F0CC_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0ccL)
        private val D0_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0d0L)
        private val F0D4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0d4L)
        private val F0D8_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0d8L)
        private val E4_SLOT = Arrays.binarySearch(REGISTER_ADDRESSES, 0xfff7f0e4L)

        private val REGISTER_BY_HEX: Map<String, Int> = REGISTER_ADDRESSES.withIndex()
            .associate { (index, address) -> "0x%08x".format(address) to index }

        fun region(selection: Any): DiscoveryRegion {
            require(selection in PROFILES) { "Unknown $KEY selection: $selection" }
            return DiscoveryRegion(
                id = REGION,
                deviceId = "section4-011fdb00",
                base = BASE,
                length = SIZE,
                strategy = DiscoveryStrategy.IMAGE_SCALER_F000,
                widths = setOf(4),
                alignment = 4,
                littleEndian = true,
                prohibited = PROHIBITED,
                evidence = listOf(
                    "DAT section4 011FDB00..011FE784 & caller 01290584..012913A8 (GIC IRQ 133): 0xFFF7F000..0xFFF7F0E8 and 0xFFF7F100/F104 with hole 0xEC..0xFC",
                    "HYPOTHESIS $selection: 0xFFF7F000 asserts bit 0 ready; 0xFFF7F0E4 asserts bit 8 when F0DC bit 28 is clear and bit 24 when F0E0 bits 31:28 are clear; other DAT offsets retain 32-bit words" +
                        if (selection == IRQ133_PROFILE) "; writing 0xFFF7F004 bit 0 after 0xFFF7F0C0..D0 setup asserts GIC level IRQ 133 after $COMPLETION_DELAY_TICKS ticks, cleared on 0xFFF7F0E4 write-1" else "",
                ),
            )
        }

        fun validRegion(region: DiscoveryRegion): Boolean =
            region.id == REGION && region.base == BASE && region.length == SIZE &&
                region.widths == setOf(4) && region.alignment == 4 && region.littleEndian &&
                region.prohibited == PROHIBITED &&
                region.initial == null && region.samples == null && region.fixedValue == null &&
                region.retainedDiagnostic == null

        fun restore(
            raw: Any?,
            targetProfile: String = PROFILE,
            allowPromotion: Boolean = false,
        ): ImageScalerF000 {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing $KEY snapshot state")
            val savedProfile = map["profile"] as? String ?: throw IllegalArgumentException("Invalid $KEY profile")
            require(savedProfile in PROFILES && map["confidence"] == "HYPOTHESIS") {
                "Invalid $KEY snapshot header"
            }
            require(savedProfile == targetProfile || (allowPromotion && savedProfile == PROFILE && targetProfile == IRQ133_PROFILE)) {
                "Incompatible $KEY profile: saved=$savedProfile target=$targetProfile"
            }
            val expectedKeys = if (savedProfile == IRQ133_PROFILE) {
                setOf("profile", "confidence", "registers", "pendingCompletionTick", "irqAsserted")
            } else {
                setOf("profile", "confidence", "registers")
            }
            require(map.keys == expectedKeys || map.keys == expectedKeys + "f014Written") { "Invalid $KEY snapshot keys" }
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
            val pendingTick = if (savedProfile == IRQ133_PROFILE) {
                map["pendingCompletionTick"]?.let { rawTick ->
                    require(rawTick is Int || rawTick is Long) { "Invalid $KEY pendingCompletionTick" }
                    (rawTick as Number).toLong().also { require(it >= 0L) }
                }
            } else null
            val asserted = if (savedProfile == IRQ133_PROFILE) {
                map["irqAsserted"] as? Boolean ?: throw IllegalArgumentException("Invalid $KEY irqAsserted")
            } else false
            val restoredF014Written = if ("f014Written" in map) {
                (map["f014Written"] as? Boolean ?: throw IllegalArgumentException("Invalid $KEY f014Written")) ||
                    restored[F014_SLOT] != 0L
            } else {
                restored[F014_SLOT] != 0L
            }
            return ImageScalerF000(restored, targetProfile, pendingTick, asserted, restoredF014Written)
        }

        private const val FACE_DENOM = 639 * 359
        private const val FACE_HALF = FACE_DENOM / 2
        private val FACE_X0_Y_OFF = IntArray(640) { dx ->
            val sx = (dx * 1023) / 639
            ((sx and 1.inv()) shl 1) + (sx and 1)
        }
        private val FACE_X1_Y_OFF = IntArray(640) { dx ->
            val sx = minOf((dx * 1023) / 639 + 1, 1023)
            ((sx and 1.inv()) shl 1) + (sx and 1)
        }
        private val FACE_X0_UV_OFF = IntArray(640) { dx -> (((dx * 1023) / 639) and 1.inv()) * 2 + 2 }
        private val FACE_X1_UV_OFF = IntArray(640) { dx -> (minOf((dx * 1023) / 639 + 1, 1023) and 1.inv()) * 2 + 2 }
        private val FACE_WX = IntArray(640) { dx -> dx * 1023 - ((dx * 1023) / 639) * 639 }
        private val FACE_CX = IntArray(640) { dx -> 639 - (dx * 1023 - ((dx * 1023) / 639) * 639) }
        private val FACE_Y0 = IntArray(360) { dy -> (dy * 575) / 359 }
        private val FACE_Y1 = IntArray(360) { dy -> minOf((dy * 575) / 359 + 1, 575) }
        private val FACE_WY = IntArray(360) { dy -> dy * 575 - ((dy * 575) / 359) * 359 }
        private val FACE_CY = IntArray(360) { dy -> 359 - (dy * 575 - ((dy * 575) / 359) * 359) }
    }
}
