package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import com.github.nyabanban.fuji.emulator.api.IspToneCurves
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt
import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.NativeCodecBridge
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

internal object JpegUyvyCodec {
    private const val MAX_OUTPUT_DIMENSION = 16384
    private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    sealed interface DecodeResult {
        data class Success(
            val uyvyBytes: ByteArray,
            val width: Int,
            val height: Int,
            val alignedWidth: Int,
            val alignedHeight: Int,
            val strideBytes: Int,
            val is420: Boolean,
        ) : DecodeResult

        data class Error(val message: String) : DecodeResult
    }

    sealed interface DecodeDirectResult {
        data class Success(
            val bytesWritten: Int,
            val width: Int,
            val height: Int,
            val alignedWidth: Int,
            val alignedHeight: Int,
            val strideBytes: Int,
            val is420: Boolean,
        ) : DecodeDirectResult

        data class Error(val message: String) : DecodeDirectResult
    }

    sealed interface EncodeResult {
        data class Success(
            val jpegBytes: ByteArray,
            val width: Int,
            val height: Int,
        ) : EncodeResult

        data class Overflow(
            val encodedBytes: Int,
            val maxBytes: Long,
        ) : EncodeResult

        data class Error(val message: String) : EncodeResult
    }

    sealed interface EncodeDirectResult {
        data class DirectWritten(
            val encodedBytes: Int,
            val width: Int,
            val height: Int,
        ) : EncodeDirectResult

        data class Success(
            val jpegBytes: ByteArray,
            val width: Int,
            val height: Int,
        ) : EncodeDirectResult

        data class Overflow(
            val encodedBytes: Int,
            val maxBytes: Long,
        ) : EncodeDirectResult

        data class Error(val message: String) : EncodeDirectResult
    }

    /**
     * Zero-copy native JPEG decode directly from [DirectGuestRamPort] JPEG source into
     * [DirectGuestRamPort] MemYc destination, calling [DirectGuestRamPort.notifyDirectWrite] on
     * success. Returns `null` when native JPEG is unavailable or either guest buffer is not
     * directly sliceable so the caller can fall back cleanly to chunked [DmaMemory] I/O.
     */
    fun decodeDirectToGuestRam(
        ram: DirectGuestRamPort,
        memJpegAddr: Long,
        memJpegSize: Int,
        memYcAddr: Long,
        memYcSize: Long,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropXStart: Int,
        cropXEnd: Int,
        cropYStart: Int,
        cropYEnd: Int,
        yyuv422: Boolean = true,
    ): DecodeDirectResult? {
        if (!NativeCodecBridge.hasJpeg) return null
        if (memJpegSize < 4) return DecodeDirectResult.Error("Missing JPEG SOI marker")
        val srcSeg = ram.sliceDirect(memJpegAddr, memJpegSize.toLong(), write = false) ?: return null
        if (srcSeg.get(ValueLayout.JAVA_BYTE, 0L) != 0xFF.toByte() ||
            srcSeg.get(ValueLayout.JAVA_BYTE, 1L) != 0xD8.toByte()
        ) {
            return DecodeDirectResult.Error("Missing JPEG SOI marker")
        }
        val shift = downscaleShift and 0x3
        val (probeRc, probe) = NativeCodecBridge.jpegProbeHeaderSegment(
            srcJpeg = srcSeg,
            srcJpegLen = memJpegSize,
            downscaleShift = shift,
            cropEnabled = cropEnabled,
            cropX0 = cropXStart,
            cropX1 = cropXEnd,
            cropY0 = cropYStart,
            cropY1 = cropYEnd,
        )
        if (probeRc == NativeCodecBridge.ERR_NO_BACKEND) return null
        if (probeRc != 0 || probe == null) {
            return DecodeDirectResult.Error("Native JPEG probe failed (code=$probeRc)")
        }
        if (probe.outWidth <= 0 || probe.outHeight <= 0 ||
            probe.outWidth > MAX_OUTPUT_DIMENSION || probe.outHeight > MAX_OUTPUT_DIMENSION
        ) {
            return DecodeDirectResult.Error("Output dimensions out of range: ${probe.outWidth}x${probe.outHeight}")
        }
        val totalBytes = probe.totalBytes.toLong()
        if (totalBytes <= 0L || totalBytes > MAX_OUTPUT_BYTES) {
            return DecodeDirectResult.Error("Estimated MemYc output exceeds $MAX_OUTPUT_BYTES bytes: $totalBytes")
        }
        if (memYcSize > 0L && totalBytes > memYcSize) {
            return DecodeDirectResult.Error("MemYc capacity $memYcSize too small for $totalBytes bytes")
        }
        if (memYcAddr > 0x1_0000_0000L - totalBytes) {
            return DecodeDirectResult.Error("MemYc destination wraps 32-bit address space")
        }
        val dstSeg = ram.sliceDirect(memYcAddr, totalBytes, write = true) ?: return null
        return when (
            val outcome = NativeCodecBridge.jpegDecodeToUyvySegment(
                srcJpeg = srcSeg,
                srcJpegLen = memJpegSize,
                downscaleShift = shift,
                cropEnabled = cropEnabled,
                cropX0 = cropXStart,
                cropX1 = cropXEnd,
                cropY0 = cropYStart,
                cropY1 = cropYEnd,
                dstUyvy = dstSeg,
                dstCapacity = probe.totalBytes,
                yyuv422 = yyuv422,
            )
        ) {
            is NativeCodecBridge.JpegDecodeDirectOutcome.Success -> {
                ram.notifyDirectWrite(memYcAddr, outcome.bytesWritten.toLong())
                val alignedW = outcome.strideBytes / 2
                val alignedH = outcome.bytesWritten / outcome.strideBytes
                DecodeDirectResult.Success(
                    bytesWritten = outcome.bytesWritten,
                    width = outcome.outWidth,
                    height = outcome.outHeight,
                    alignedWidth = alignedW,
                    alignedHeight = alignedH,
                    strideBytes = outcome.strideBytes,
                    is420 = outcome.is420,
                )
            }
            is NativeCodecBridge.JpegDecodeDirectOutcome.Error -> {
                if (outcome.code == NativeCodecBridge.ERR_NO_BACKEND) null
                else DecodeDirectResult.Error("Native JPEG decode failed (code=${outcome.code})")
            }
        }
    }

    /**
     * Zero-copy native JPEG encode reading packed UYVY422 directly from an off-heap [srcUyvy]
     * [MemorySegment]. Returns `null` if native JPEG is unavailable so the caller can fall back
     * to JVM ImageIO.
     */
    fun encodeDirectFromSegment(
        srcUyvy: MemorySegment,
        width: Int,
        height: Int,
        linePitchBytes: Int,
        qscale: Int,
        maxBytes: Long,
        qualityOverride: Float? = null,
        yyuv422: Boolean = true,
        directDstWriter: (nativeJpeg: MemorySegment, actualBytes: Int) -> Boolean,
    ): EncodeDirectResult? {
        if (!NativeCodecBridge.hasJpeg) return null
        if (width <= 0 || height <= 0 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION) {
            return EncodeDirectResult.Error("Invalid encode dimensions: ${width}x$height")
        }
        val minRowBytes = width * 2
        val pitch = if (linePitchBytes > 0) linePitchBytes else minRowBytes
        if (pitch < minRowBytes) {
            return EncodeDirectResult.Error("Encode line pitch $pitch smaller than row width $minRowBytes")
        }
        val requiredBytes = (height - 1).toLong() * pitch.toLong() + minRowBytes.toLong()
        if (requiredBytes > MAX_OUTPUT_BYTES || srcUyvy.byteSize() < requiredBytes) {
            return EncodeDirectResult.Error("MemYc source size ${srcUyvy.byteSize()} smaller than required $requiredBytes bytes")
        }
        val qualityF = (qualityOverride ?: qualityFromQscale(qscale)).coerceIn(0.05f, 0.98f)
        val qualityInt = (qualityF * 100.0f).roundToInt().coerceIn(5, 98)
        return when (
            val outcome = NativeCodecBridge.jpegEncodeUyvySegmentDirect(
                srcUyvy = srcUyvy,
                width = width,
                height = height,
                srcStrideBytes = pitch,
                quality = qualityInt,
                maxBytes = maxBytes,
                yyuv422 = yyuv422,
                directDstWriter = directDstWriter,
            )
        ) {
            is NativeCodecBridge.JpegEncodeSegmentOutcome.DirectWritten ->
                EncodeDirectResult.DirectWritten(outcome.encodedBytes, width, height)
            is NativeCodecBridge.JpegEncodeSegmentOutcome.HeapBytes ->
                EncodeDirectResult.Success(outcome.jpegBytes, width, height)
            is NativeCodecBridge.JpegEncodeSegmentOutcome.Overflow ->
                EncodeDirectResult.Overflow(outcome.actualBytes, maxBytes)
            is NativeCodecBridge.JpegEncodeSegmentOutcome.Error -> {
                if (outcome.code == NativeCodecBridge.ERR_NO_BACKEND) null
                else EncodeDirectResult.Error("Native JPEG UYVY encode failed (code=${outcome.code})")
            }
        }
    }

    fun decodeToMemYc(
        jpegBytes: ByteArray,
        memYcSize: Long,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropXStart: Int,
        cropXEnd: Int,
        cropYStart: Int,
        cropYEnd: Int,
        useNative: Boolean = true,
        yyuv422: Boolean = true,
    ): DecodeResult {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return DecodeResult.Error("Missing JPEG SOI marker")
        }
        val shift = downscaleShift and 0x3
        val subsample = 1 shl shift

        if (useNative) {
            if (NativeCodecBridge.hasJpeg) {
                val nativeRes = decodeToMemYcNative(
                    jpegBytes = jpegBytes,
                    memYcSize = memYcSize,
                    shift = shift,
                    cropEnabled = cropEnabled,
                    cropXStart = cropXStart,
                    cropXEnd = cropXEnd,
                    cropYStart = cropYStart,
                    cropYEnd = cropYEnd,
                    yyuv422 = yyuv422,
                )
                if (nativeRes != null) return nativeRes
            } else {
                NativeCodecBridge.warnJpegFallbackOnce()
            }
        }

        val is420 = detectIs420(jpegBytes)
        val verticalAlign = if (is420) 16 else 8

        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(jpegBytes)).use { iis ->
                val readers = ImageIO.getImageReadersByFormatName("jpg")
                if (!readers.hasNext()) {
                    return DecodeResult.Error("No JPEG ImageReader available")
                }
                val reader = readers.next()
                try {
                    reader.input = iis
                    val srcWidth = reader.getWidth(0)
                    val srcHeight = reader.getHeight(0)
                    if (srcWidth <= 0 || srcHeight <= 0) {
                        return DecodeResult.Error("Invalid JPEG dimensions: ${srcWidth}x$srcHeight")
                    }

                    val roiX: Int
                    val roiY: Int
                    val roiW: Int
                    val roiH: Int
                    if (cropEnabled) {
                        if (cropXStart < 0 || cropXEnd < cropXStart || cropXEnd >= srcWidth ||
                            cropYStart < 0 || cropYEnd < cropYStart || cropYEnd >= srcHeight
                        ) {
                            return DecodeResult.Error(
                                "Invalid JPEG ROI ($cropXStart..$cropXEnd, $cropYStart..$cropYEnd) for ${srcWidth}x$srcHeight",
                            )
                        }
                        roiX = cropXStart
                        roiY = cropYStart
                        roiW = cropXEnd - cropXStart + 1
                        roiH = cropYEnd - cropYStart + 1
                    } else {
                        roiX = 0
                        roiY = 0
                        roiW = srcWidth
                        roiH = srcHeight
                    }

                    val estW = (roiW + subsample - 1) / subsample
                    val estH = (roiH + subsample - 1) / subsample
                    if (estW <= 0 || estH <= 0 || estW > MAX_OUTPUT_DIMENSION || estH > MAX_OUTPUT_DIMENSION) {
                        return DecodeResult.Error("Output dimensions out of range: ${estW}x$estH")
                    }
                    val estAlignedW = (estW + 15) and 15.inv()
                    val estAlignedH = (estH + verticalAlign - 1) and (verticalAlign - 1).inv()
                    val estTotalBytes = estAlignedW.toLong() * estAlignedH.toLong() * 2L
                    if (estTotalBytes > MAX_OUTPUT_BYTES) {
                        return DecodeResult.Error("Estimated MemYc output exceeds $MAX_OUTPUT_BYTES bytes: $estTotalBytes")
                    }
                    if (memYcSize > 0L && estTotalBytes > memYcSize) {
                        return DecodeResult.Error("MemYc capacity $memYcSize too small for $estTotalBytes bytes")
                    }

                    val param = reader.defaultReadParam
                    if (cropEnabled) {
                        param.sourceRegion = Rectangle(roiX, roiY, roiW, roiH)
                    }
                    if (subsample > 1) {
                        param.setSourceSubsampling(subsample, subsample, 0, 0)
                    }
                    val decoded: BufferedImage = reader.read(0, param)
                        ?: return DecodeResult.Error("JPEG reader returned null image")
                    val outW = decoded.width
                    val outH = decoded.height
                    if (outW <= 0 || outH <= 0 || outW > MAX_OUTPUT_DIMENSION || outH > MAX_OUTPUT_DIMENSION) {
                        return DecodeResult.Error("Decoded dimensions out of range: ${outW}x$outH")
                    }
                    val alignedW = (outW + 15) and 15.inv()
                    val alignedH = (outH + verticalAlign - 1) and (verticalAlign - 1).inv()
                    val strideBytes = alignedW * 2
                    val totalBytes = strideBytes.toLong() * alignedH.toLong()
                    if (totalBytes > MAX_OUTPUT_BYTES || (memYcSize > 0L && totalBytes > memYcSize)) {
                        return DecodeResult.Error("MemYc capacity $memYcSize too small for $totalBytes bytes")
                    }

                    val uyvy = encodeBufferedImageToUyvy(decoded, outW, outH, alignedW, alignedH, strideBytes, yyuv422 = yyuv422)
                    DecodeResult.Success(
                        uyvyBytes = uyvy,
                        width = outW,
                        height = outH,
                        alignedWidth = alignedW,
                        alignedHeight = alignedH,
                        strideBytes = strideBytes,
                        is420 = is420,
                    )
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            DecodeResult.Error(e.message ?: e::class.java.simpleName)
        }
    }

    private fun decodeToMemYcNative(
        jpegBytes: ByteArray,
        memYcSize: Long,
        shift: Int,
        cropEnabled: Boolean,
        cropXStart: Int,
        cropXEnd: Int,
        cropYStart: Int,
        cropYEnd: Int,
        yyuv422: Boolean,
    ): DecodeResult? {
        val (probeRc, probe) = NativeCodecBridge.jpegProbeHeader(
            jpegBytes = jpegBytes,
            downscaleShift = shift,
            cropEnabled = cropEnabled,
            cropX0 = cropXStart,
            cropX1 = cropXEnd,
            cropY0 = cropYStart,
            cropY1 = cropYEnd,
        )
        if (probeRc == NativeCodecBridge.ERR_NO_BACKEND) return null
        if (probeRc != 0 || probe == null) {
            return DecodeResult.Error("Native JPEG probe failed (code=$probeRc)")
        }
        if (probe.outWidth <= 0 || probe.outHeight <= 0 ||
            probe.outWidth > MAX_OUTPUT_DIMENSION || probe.outHeight > MAX_OUTPUT_DIMENSION
        ) {
            return DecodeResult.Error("Output dimensions out of range: ${probe.outWidth}x${probe.outHeight}")
        }
        val totalBytes = probe.totalBytes.toLong()
        if (totalBytes <= 0L || totalBytes > MAX_OUTPUT_BYTES) {
            return DecodeResult.Error("Estimated MemYc output exceeds $MAX_OUTPUT_BYTES bytes: $totalBytes")
        }
        if (memYcSize > 0L && totalBytes > memYcSize) {
            return DecodeResult.Error("MemYc capacity $memYcSize too small for $totalBytes bytes")
        }

        return when (
            val outcome = NativeCodecBridge.jpegDecodeToUyvy(
                jpegBytes = jpegBytes,
                downscaleShift = shift,
                cropEnabled = cropEnabled,
                cropX0 = cropXStart,
                cropX1 = cropXEnd,
                cropY0 = cropYStart,
                cropY1 = cropYEnd,
                expectedTotalBytes = probe.totalBytes,
                yyuv422 = yyuv422,
            )
        ) {
            is NativeCodecBridge.JpegDecodeOutcome.Success -> {
                val alignedW = outcome.strideBytes / 2
                val alignedH = outcome.uyvyBytes.size / outcome.strideBytes
                DecodeResult.Success(
                    uyvyBytes = outcome.uyvyBytes,
                    width = outcome.outWidth,
                    height = outcome.outHeight,
                    alignedWidth = alignedW,
                    alignedHeight = alignedH,
                    strideBytes = outcome.strideBytes,
                    is420 = outcome.is420,
                )
            }
            is NativeCodecBridge.JpegDecodeOutcome.Error -> {
                if (outcome.code == NativeCodecBridge.ERR_NO_BACKEND) null
                else DecodeResult.Error("Native JPEG decode failed (code=${outcome.code})")
            }
        }
    }

    private fun encodeBufferedImageToUyvy(
        image: BufferedImage,
        outW: Int,
        outH: Int,
        alignedW: Int,
        alignedH: Int,
        strideBytes: Int,
        yyuv422: Boolean = true,
    ): ByteArray {
        val out = ByteArray(strideBytes * alignedH)
        val evenOutW = outW and 1.inv()
        val dataBuf = image.raster.dataBuffer
        val iY0 = if (yyuv422) 0 else 1
        val iY1 = if (yyuv422) 1 else 3
        val iU = if (yyuv422) 2 else 0
        val iV = if (yyuv422) 3 else 2

        // Fast bulk DataBuffer paths (avoids per-row BufferedImage.getRGB color-model overhead)
        val imgType = image.type
        if (imgType == BufferedImage.TYPE_3BYTE_BGR && dataBuf is DataBufferByte && dataBuf.data.size >= outW * outH * 3) {
            val bgr = dataBuf.data
            val srcStride = outW * 3
            for (y in 0 until outH) {
                val sRow = y * srcStride
                val rowOff = y * strideBytes
                var x = 0
                while (x < evenOutW) {
                    val s0 = sRow + x * 3
                    val b0 = bgr[s0].toInt() and 0xFF
                    val g0 = bgr[s0 + 1].toInt() and 0xFF
                    val r0 = bgr[s0 + 2].toInt() and 0xFF
                    val b1 = bgr[s0 + 3].toInt() and 0xFF
                    val g1 = bgr[s0 + 4].toInt() and 0xFF
                    val r1 = bgr[s0 + 5].toInt() and 0xFF
                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val y1 = ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (r0 + r1) - 85 * (g0 + g1) + 128 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (r0 + r1) - 107 * (g0 + g1) - 21 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)
                    val p = rowOff + x * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = y1.toByte()
                    x += 2
                }
                if (evenOutW < outW) {
                    val s0 = sRow + evenOutW * 3
                    val b0 = bgr[s0].toInt() and 0xFF
                    val g0 = bgr[s0 + 1].toInt() and 0xFF
                    val r0 = bgr[s0 + 2].toInt() and 0xFF
                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (2 * r0) - 85 * (2 * g0) + 128 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (2 * r0) - 107 * (2 * g0) - 21 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val p = rowOff + evenOutW * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = 0
                    x = evenOutW + 2
                }
                while (x < alignedW) {
                    val p = rowOff + x * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = 0
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = 0
                    x += 2
                }
            }
        } else if (imgType == BufferedImage.TYPE_BYTE_GRAY && dataBuf is DataBufferByte && dataBuf.data.size >= outW * outH) {
            val gray = dataBuf.data
            for (y in 0 until outH) {
                val sRow = y * outW
                val rowOff = y * strideBytes
                var x = 0
                while (x < evenOutW) {
                    val p = rowOff + x * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = gray[sRow + x]
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = gray[sRow + x + 1]
                    x += 2
                }
                if (evenOutW < outW) {
                    val p = rowOff + evenOutW * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = gray[sRow + evenOutW]
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = 0
                    x = evenOutW + 2
                }
                while (x < alignedW) {
                    val p = rowOff + x * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = 0
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = 0
                    x += 2
                }
            }
        } else if ((imgType == BufferedImage.TYPE_INT_RGB || imgType == BufferedImage.TYPE_INT_ARGB) &&
            dataBuf is DataBufferInt && dataBuf.data.size >= outW * outH
        ) {
            val pixels = dataBuf.data
            for (y in 0 until outH) {
                val sRow = y * outW
                val rowOff = y * strideBytes
                var x = 0
                while (x < evenOutW) {
                    val rgb0 = pixels[sRow + x]
                    val rgb1 = pixels[sRow + x + 1]
                    val r0 = (rgb0 ushr 16) and 0xFF
                    val g0 = (rgb0 ushr 8) and 0xFF
                    val b0 = rgb0 and 0xFF
                    val r1 = (rgb1 ushr 16) and 0xFF
                    val g1 = (rgb1 ushr 8) and 0xFF
                    val b1 = rgb1 and 0xFF
                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val y1 = ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (r0 + r1) - 85 * (g0 + g1) + 128 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (r0 + r1) - 107 * (g0 + g1) - 21 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)
                    val p = rowOff + x * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = y1.toByte()
                    x += 2
                }
                if (evenOutW < outW) {
                    val rgb0 = pixels[sRow + evenOutW]
                    val r0 = (rgb0 ushr 16) and 0xFF
                    val g0 = (rgb0 ushr 8) and 0xFF
                    val b0 = rgb0 and 0xFF
                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (2 * r0) - 85 * (2 * g0) + 128 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (2 * r0) - 107 * (2 * g0) - 21 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val p = rowOff + evenOutW * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = 0
                    x = evenOutW + 2
                }
                while (x < alignedW) {
                    val p = rowOff + x * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = 0
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = 0
                    x += 2
                }
            }
        } else {
            val rowRgb = IntArray(outW)
            for (y in 0 until outH) {
                image.getRGB(0, y, outW, 1, rowRgb, 0, outW)
                val rowOff = y * strideBytes
                var x = 0
                while (x < evenOutW) {
                    val rgb0 = rowRgb[x]
                    val rgb1 = rowRgb[x + 1]
                    val r0 = (rgb0 ushr 16) and 0xFF
                    val g0 = (rgb0 ushr 8) and 0xFF
                    val b0 = rgb0 and 0xFF
                    val r1 = (rgb1 ushr 16) and 0xFF
                    val g1 = (rgb1 ushr 8) and 0xFF
                    val b1 = rgb1 and 0xFF

                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val y1 = ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (r0 + r1) - 85 * (g0 + g1) + 128 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (r0 + r1) - 107 * (g0 + g1) - 21 * (b0 + b1) + 256) shr 9) + 128).coerceIn(0, 255)

                    val p = rowOff + x * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = y1.toByte()
                    x += 2
                }
                if (evenOutW < outW) {
                    val rgb0 = rowRgb[evenOutW]
                    val r0 = (rgb0 ushr 16) and 0xFF
                    val g0 = (rgb0 ushr 8) and 0xFF
                    val b0 = rgb0 and 0xFF
                    val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                    val u = (((-43 * (2 * r0) - 85 * (2 * g0) + 128 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val v = (((128 * (2 * r0) - 107 * (2 * g0) - 21 * (2 * b0) + 256) shr 9) + 128).coerceIn(0, 255)
                    val p = rowOff + evenOutW * 2
                    out[p + iU] = u.toByte()
                    out[p + iY0] = y0.toByte()
                    out[p + iV] = v.toByte()
                    out[p + iY1] = 0
                    x = evenOutW + 2
                }
                while (x < alignedW) {
                    val p = rowOff + x * 2
                    out[p + iU] = 0x80.toByte()
                    out[p + iY0] = 0
                    out[p + iV] = 0x80.toByte()
                    out[p + iY1] = 0
                    x += 2
                }
            }
        }
        for (y in outH until alignedH) {
            val rowOff = y * strideBytes
            var x = 0
            while (x < alignedW) {
                val p = rowOff + x * 2
                out[p + iU] = 0x80.toByte()
                out[p + iY0] = 0
                out[p + iV] = 0x80.toByte()
                out[p + iY1] = 0
                x += 2
            }
        }
        return out
    }

    internal fun detectIs420(jpegBytes: ByteArray): Boolean {
        var pos = 2
        val len = jpegBytes.size
        while (pos < len) {
            while (pos < len && jpegBytes[pos] != 0xFF.toByte()) pos++
            while (pos < len && jpegBytes[pos] == 0xFF.toByte()) pos++
            if (pos >= len) break
            val marker = jpegBytes[pos].toInt() and 0xFF
            pos++
            if (marker == 0x00 || marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) {
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) {
                break
            }
            if (pos + 2 > len) break
            val segLen = ((jpegBytes[pos].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > len) break
            if (marker in 0xC0..0xC3) {
                if (pos + 8 <= len) {
                    val numComp = jpegBytes[pos + 7].toInt() and 0xFF
                    if (numComp >= 3 && pos + 8 + numComp * 3 <= len) {
                        val ySampling = jpegBytes[pos + 9].toInt() and 0xFF
                        val cbSampling = jpegBytes[pos + 12].toInt() and 0xFF
                        val vY = ySampling and 0x0F
                        val vCb = cbSampling and 0x0F
                        return vY > vCb
                    }
                }
                return false
            }
            pos += segLen
        }
        return false
    }

    fun encodeFromMemYc(
        uyvyBytes: ByteArray,
        width: Int,
        height: Int,
        linePitchBytes: Int,
        qscale: Int,
        maxBytes: Long,
        qualityOverride: Float? = null,
        useNative: Boolean = true,
        yyuv422: Boolean = true,
    ): EncodeResult {
        if (width <= 0 || height <= 0 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION) {
            return EncodeResult.Error("Invalid encode dimensions: ${width}x$height")
        }
        val minRowBytes = width * 2
        val pitch = if (linePitchBytes > 0) linePitchBytes else minRowBytes
        if (pitch < minRowBytes) {
            return EncodeResult.Error("Encode line pitch $pitch smaller than row width $minRowBytes")
        }
        val requiredBytes = (height - 1).toLong() * pitch.toLong() + minRowBytes.toLong()
        if (requiredBytes > MAX_OUTPUT_BYTES || uyvyBytes.size.toLong() < requiredBytes) {
            return EncodeResult.Error("MemYc source size ${uyvyBytes.size} smaller than required $requiredBytes bytes")
        }

        val qualityF = (qualityOverride ?: qualityFromQscale(qscale)).coerceIn(0.05f, 0.98f)
        if (useNative) {
            if (NativeCodecBridge.hasJpeg) {
                val qualityInt = (qualityF * 100.0f).roundToInt().coerceIn(5, 98)
                when (val outcome = NativeCodecBridge.jpegEncodeUyvy(uyvyBytes, width, height, pitch, qualityInt, maxBytes, yyuv422 = yyuv422)) {
                    is NativeCodecBridge.JpegEncodeOutcome.Success ->
                        return EncodeResult.Success(outcome.jpegBytes, width, height)
                    is NativeCodecBridge.JpegEncodeOutcome.Overflow ->
                        return EncodeResult.Overflow(outcome.actualBytes, maxBytes)
                    is NativeCodecBridge.JpegEncodeOutcome.Error -> {
                        if (outcome.code != NativeCodecBridge.ERR_NO_BACKEND) {
                            return EncodeResult.Error("Native JPEG UYVY encode failed (code=${outcome.code})")
                        }
                    }
                }
            } else {
                NativeCodecBridge.warnJpegFallbackOnce()
            }
        }

        // Optimized JVM fallback: write directly into BufferedImage TYPE_3BYTE_BGR DataBufferByte
        // (zero double-allocation/setRGB and 25% smaller heap footprint than TYPE_INT_RGB).
        return try {
            val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
            val bgr = (image.raster.dataBuffer as DataBufferByte).data
            val evenW = width and 1.inv()
            val dstRowBytes = width * 3
            val iY0 = if (yyuv422) 0 else 1
            val iY1 = if (yyuv422) 1 else 3
            val iU = if (yyuv422) 2 else 0
            val iV = if (yyuv422) 3 else 2
            for (y in 0 until height) {
                val rowOff = y * pitch
                val dstRow = y * dstRowBytes
                var x = 0
                while (x < evenW) {
                    val pairOff = rowOff + x * 2
                    val u = uyvyBytes[pairOff + iU].toInt() and 0xFF
                    val y0 = uyvyBytes[pairOff + iY0].toInt() and 0xFF
                    val v = uyvyBytes[pairOff + iV].toInt() and 0xFF
                    val y1 = uyvyBytes[pairOff + iY1].toInt() and 0xFF
                    val dOff = dstRow + x * 3
                    // Map unwritten zero DRAM (0x00000000) and Milbeaut Playback black fill words to pure black.
                    val isBlackSentinel = if (yyuv422) {
                        (y0 or y1 or u or v) == 0 || (y0 == 0x80 && y1 == 0x80 && u == 0 && v == 0)
                    } else {
                        (u or y0 or v or y1) == 0 ||
                            (u == 0 && y0 == 0 && v == 0x80 && y1 == 0x80) ||
                            (u == 0x80 && y0 == 0x80 && v == 0 && y1 == 0)
                    }
                    if (isBlackSentinel) {
                        bgr[dOff] = 0
                        bgr[dOff + 1] = 0
                        bgr[dOff + 2] = 0
                        bgr[dOff + 3] = 0
                        bgr[dOff + 4] = 0
                        bgr[dOff + 5] = 0
                        x += 2
                        continue
                    }
                    val d = u - 128
                    val e = v - 128
                    val rOff = 359 * e + 128
                    val gOff = -88 * d - 183 * e + 128
                    val bOff = 454 * d + 128
                    val l0 = y0 shl 8
                    bgr[dOff] = ((l0 + bOff) shr 8).coerceIn(0, 255).toByte()
                    bgr[dOff + 1] = ((l0 + gOff) shr 8).coerceIn(0, 255).toByte()
                    bgr[dOff + 2] = ((l0 + rOff) shr 8).coerceIn(0, 255).toByte()
                    val l1 = y1 shl 8
                    bgr[dOff + 3] = ((l1 + bOff) shr 8).coerceIn(0, 255).toByte()
                    bgr[dOff + 4] = ((l1 + gOff) shr 8).coerceIn(0, 255).toByte()
                    bgr[dOff + 5] = ((l1 + rOff) shr 8).coerceIn(0, 255).toByte()
                    x += 2
                }
                if (evenW < width) {
                    val pairOff = rowOff + evenW * 2
                    val u = uyvyBytes[pairOff + iU].toInt() and 0xFF
                    val y0 = uyvyBytes[pairOff + iY0].toInt() and 0xFF
                    val v = uyvyBytes[pairOff + iV].toInt() and 0xFF
                    val y1 = if (pairOff + iY1 < uyvyBytes.size) uyvyBytes[pairOff + iY1].toInt() and 0xFF else 0
                    val dOff = dstRow + evenW * 3
                    val isBlackSentinel = if (yyuv422) {
                        (y0 or y1 or u or v) == 0 || (y0 == 0x80 && y1 == 0x80 && u == 0 && v == 0)
                    } else {
                        (u or y0 or v or y1) == 0 ||
                            (u == 0 && y0 == 0 && v == 0x80 && y1 == 0x80) ||
                            (u == 0x80 && y0 == 0x80 && v == 0 && y1 == 0)
                    }
                    if (isBlackSentinel) {
                        bgr[dOff] = 0
                        bgr[dOff + 1] = 0
                        bgr[dOff + 2] = 0
                    } else {
                        val d = u - 128
                        val e = v - 128
                        bgr[dOff] = ((256 * y0 + 454 * d + 128) shr 8).coerceIn(0, 255).toByte()
                        bgr[dOff + 1] = ((256 * y0 - 88 * d - 183 * e + 128) shr 8).coerceIn(0, 255).toByte()
                        bgr[dOff + 2] = ((256 * y0 + 359 * e + 128) shr 8).coerceIn(0, 255).toByte()
                    }
                }
            }

            val writers = ImageIO.getImageWritersByFormatName("jpg")
            if (!writers.hasNext()) {
                return EncodeResult.Error("No JPEG ImageWriter available")
            }
            val writer = writers.next()
            val baos = ByteArrayOutputStream()
            try {
                MemoryCacheImageOutputStream(baos).use { out ->
                    writer.output = out
                    val param = writer.defaultWriteParam
                    if (param.canWriteCompressed()) {
                        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        param.compressionQuality = qualityF
                    }
                    writer.write(null, IIOImage(image, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            val encoded = baos.toByteArray()
            if (maxBytes > 0L && encoded.size.toLong() > maxBytes) {
                EncodeResult.Overflow(encoded.size, maxBytes)
            } else {
                EncodeResult.Success(encoded, width, height)
            }
        } catch (e: Exception) {
            EncodeResult.Error(e.message ?: e::class.java.simpleName)
        }
    }

    internal fun qualityFromQscale(qscale: Int): Float {
        val lumaQ = qscale and 0xF
        if (lumaQ == 0) return 0.92f
        // Higher hardware qscale corresponds to stronger quantization; map 1..15 smoothly onto 0.95..0.50.
        return (0.95f - ((lumaQ - 1) * 0.45f / 14.0f)).coerceIn(0.50f, 0.95f)
    }

    private val KNOWN_SENSOR_DIMS = listOf(
        1024 to 576,
        640 to 480,
        1280 to 960,
        1920 to 1080,
        1440 to 1080,
        1600 to 1200,
    )
    private const val MAX_SENSOR_RAW_BYTES = 1920 * 1080 * 2
    private const val NOMINAL_DURATION_NANOS = 1000L
    private const val NOMINAL_ISO = 100
    private val DEFAULT_LENS = LensParameters("GF63mmF2.8", 63.0, 2.8, 10.0, 0.9, 0.0)

    private val LINEAR_TO_SRGB = IntArray(4096) { idx ->
        val c = idx / 4095.0
        val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    private val SAMPLE8_TO_12 = IntArray(256) { v8 -> (v8 * 4095) / 255 }

    private fun sample12(raw: ByteArray, idx: Int, is16Bit: Boolean): Int =
        if (is16Bit) {
            val off = idx shl 1
            ((raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)) ushr 4
        } else {
            SAMPLE8_TO_12[raw[idx].toInt() and 0xFF]
        }

    private val rawExposeScratch: ThreadLocal<ByteArray> = ThreadLocal.withInitial {
        ByteArray(MAX_SENSOR_RAW_BYTES)
    }

    internal data class OpticalBgrFrame(
        val srcBgr: ByteArray,
        val width: Int,
        val height: Int,
    )

    internal sealed interface OpticalExposeResult {
        data class Success(val frame: OpticalBgrFrame) : OpticalExposeResult
        data class Error(val message: String) : OpticalExposeResult
    }

    /**
     * Exposes [sensor] once and demosaics the raw Bayer frame into a compact sRGB BGR24 buffer
     * (`~1.77 MB` for `1024x576`), ready for streaming scaled JPEG encoding or thumbnail reuse.
     */
    internal fun exposeAndDemosaicOpticalSensor(
        tick: Long,
        sensor: SensorPort,
        lens: LensParameters?,
        durationNanos: Long = NOMINAL_DURATION_NANOS,
        iso: Int = NOMINAL_ISO,
        gainR1024: Int = 1024,
        gainG1024: Int = 1024,
        gainB1024: Int = 1024,
        ccmQ10: IntArray? = null,
        chromaMatQ10: IntArray? = null,
        monochromeMode: Boolean = false,
        monoToneDeltaCb10: Int = 0,
        monoToneDeltaCr10: Int = 0,
        fallbackWidthHint: Int = 0,
        fallbackHeightHint: Int = 0,
        toneCurves: IspToneCurves? = null,
        captured: IspStillReadout? = null,
    ): OpticalExposeResult {
        return try {
            val rawBytes = rawExposeScratch.get()
            val rawBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val effectiveDuration = durationNanos.coerceAtLeast(1L)
            val effectiveIso = iso.coerceAtLeast(1)
            val exposed = captured?.copyBayerTo(rawBuf)
                ?: sensor.exposeStill(Exposure(tick, effectiveDuration, effectiveIso, lens ?: DEFAULT_LENS), rawBuf)
            if (exposed <= 0) {
                return OpticalExposeResult.Error("CMOS sensor returned empty exposure ($exposed)")
            }
            val matched16 = KNOWN_SENSOR_DIMS.firstOrNull { (w, h) -> w * h * 2 == exposed }
            val matched8 = KNOWN_SENSOR_DIMS.firstOrNull { (w, h) -> w * h == exposed }
            val (srcW, srcH, is16Bit) = when {
                matched16 != null -> Triple(matched16.first, matched16.second, true)
                matched8 != null -> Triple(matched8.first, matched8.second, false)
                fallbackWidthHint > 0 && fallbackHeightHint > 0 && fallbackWidthHint * fallbackHeightHint * 2 == exposed ->
                    Triple(fallbackWidthHint, fallbackHeightHint, true)
                fallbackWidthHint > 0 && fallbackHeightHint > 0 && fallbackWidthHint * fallbackHeightHint == exposed ->
                    Triple(fallbackWidthHint, fallbackHeightHint, false)
                else -> return OpticalExposeResult.Error("Unsupported CMOS exposure byte count: $exposed")
            }

            // Demosaic native sensor frame once into packed sRGB BGR bytes, applying live ISP WB/digital gains.
            val srcBgr = ByteArray(srcW * srcH * 3)
            val maxX = srcW - 1
            val maxY = srcH - 1
            val lutR = toneCurves?.red ?: LINEAR_TO_SRGB
            val lutG = toneCurves?.green ?: LINEAR_TO_SRGB
            val lutB = toneCurves?.blue ?: LINEAR_TO_SRGB
            val s8To12 = SAMPLE8_TO_12
            val unityGain = gainR1024 == 1024 && gainG1024 == 1024 && gainB1024 == 1024
            val hasCcm = ccmQ10 != null && ccmQ10.size >= 9
            val c0 = if (hasCcm) ccmQ10[0] else 0
            val c1 = if (hasCcm) ccmQ10[1] else 0
            val c2 = if (hasCcm) ccmQ10[2] else 0
            val c3 = if (hasCcm) ccmQ10[3] else 0
            val c4 = if (hasCcm) ccmQ10[4] else 0
            val c5 = if (hasCcm) ccmQ10[5] else 0
            val c6 = if (hasCcm) ccmQ10[6] else 0
            val c7 = if (hasCcm) ccmQ10[7] else 0
            val c8 = if (hasCcm) ccmQ10[8] else 0
            val hasMonoTone = !monochromeMode && (monoToneDeltaCb10 != 0 || monoToneDeltaCr10 != 0)
            val hasChroma = !monochromeMode && !hasMonoTone && chromaMatQ10 != null && chromaMatQ10.size >= 4
            val cm0 = if (hasChroma) chromaMatQ10[0] else 0
            val cm1 = if (hasChroma) chromaMatQ10[1] else 0
            val cm2 = if (hasChroma) chromaMatQ10[2] else 0
            val cm3 = if (hasChroma) chromaMatQ10[3] else 0
            val needsColorPost = monochromeMode || hasMonoTone || hasChroma

            fun s16(idx: Int): Int {
                val off = idx shl 1
                return ((rawBytes[off].toInt() and 0xFF) or ((rawBytes[off + 1].toInt() and 0xFF) shl 8)) ushr 4
            }

            for (sy in 0 until srcH) {
                val rowCurr = sy * srcW
                val rowPrev = (if (sy > 0) sy - 1 else 0) * srcW
                val rowNext = (if (sy < maxY) sy + 1 else maxY) * srcW
                val syOdd = (sy and 1) != 0
                var p = sy * srcW * 3
                for (sx in 0 until srcW) {
                    val xPrev = if (sx > 0) sx - 1 else 0
                    val xNext = if (sx < maxX) sx + 1 else maxX
                    val sxOdd = (sx and 1) != 0
                    val rLin: Int
                    val gLin: Int
                    val bLin: Int
                    if (is16Bit) {
                        val c = s16(rowCurr + sx)
                        if (!syOdd && !sxOdd) {
                            rLin = c
                            gLin = (s16(rowCurr + xPrev) + s16(rowCurr + xNext) + s16(rowPrev + sx) + s16(rowNext + sx) + 2) ushr 2
                            bLin = (s16(rowPrev + xPrev) + s16(rowPrev + xNext) + s16(rowNext + xPrev) + s16(rowNext + xNext) + 2) ushr 2
                        } else if (!syOdd && sxOdd) {
                            rLin = (s16(rowCurr + xPrev) + s16(rowCurr + xNext) + 1) ushr 1
                            gLin = c
                            bLin = (s16(rowPrev + sx) + s16(rowNext + sx) + 1) ushr 1
                        } else if (syOdd && !sxOdd) {
                            rLin = (s16(rowPrev + sx) + s16(rowNext + sx) + 1) ushr 1
                            gLin = c
                            bLin = (s16(rowCurr + xPrev) + s16(rowCurr + xNext) + 1) ushr 1
                        } else {
                            rLin = (s16(rowPrev + xPrev) + s16(rowPrev + xNext) + s16(rowNext + xPrev) + s16(rowNext + xNext) + 2) ushr 2
                            gLin = (s16(rowCurr + xPrev) + s16(rowCurr + xNext) + s16(rowPrev + sx) + s16(rowNext + sx) + 2) ushr 2
                            bLin = c
                        }
                    } else {
                        val c = s8To12[rawBytes[rowCurr + sx].toInt() and 0xFF]
                        if (!syOdd && !sxOdd) {
                            rLin = c
                            gLin = (s8To12[rawBytes[rowCurr + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowCurr + xNext].toInt() and 0xFF] +
                                s8To12[rawBytes[rowPrev + sx].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + sx].toInt() and 0xFF] + 2) ushr 2
                            bLin = (s8To12[rawBytes[rowPrev + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowPrev + xNext].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + xNext].toInt() and 0xFF] + 2) ushr 2
                        } else if (!syOdd && sxOdd) {
                            rLin = (s8To12[rawBytes[rowCurr + xPrev].toInt() and 0xFF] + s8To12[rawBytes[rowCurr + xNext].toInt() and 0xFF] + 1) ushr 1
                            gLin = c
                            bLin = (s8To12[rawBytes[rowPrev + sx].toInt() and 0xFF] + s8To12[rawBytes[rowNext + sx].toInt() and 0xFF] + 1) ushr 1
                        } else if (syOdd && !sxOdd) {
                            rLin = (s8To12[rawBytes[rowPrev + sx].toInt() and 0xFF] + s8To12[rawBytes[rowNext + sx].toInt() and 0xFF] + 1) ushr 1
                            gLin = c
                            bLin = (s8To12[rawBytes[rowCurr + xPrev].toInt() and 0xFF] + s8To12[rawBytes[rowCurr + xNext].toInt() and 0xFF] + 1) ushr 1
                        } else {
                            rLin = (s8To12[rawBytes[rowPrev + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowPrev + xNext].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + xNext].toInt() and 0xFF] + 2) ushr 2
                            gLin = (s8To12[rawBytes[rowCurr + xPrev].toInt() and 0xFF] +
                                s8To12[rawBytes[rowCurr + xNext].toInt() and 0xFF] +
                                s8To12[rawBytes[rowPrev + sx].toInt() and 0xFF] +
                                s8To12[rawBytes[rowNext + sx].toInt() and 0xFF] + 2) ushr 2
                            bLin = c
                        }
                    }
                    val rScaled = if (unityGain) rLin else ((rLin.toLong() * gainR1024 + 512L) ushr 10).toInt()
                    val gScaled = if (unityGain) gLin else ((gLin.toLong() * gainG1024 + 512L) ushr 10).toInt()
                    val bScaled = if (unityGain) bLin else ((bLin.toLong() * gainB1024 + 512L) ushr 10).toInt()
                    val rCcm: Int
                    val gCcm: Int
                    val bCcm: Int
                    if (hasCcm) {
                        rCcm = ((c0 * rScaled + c1 * gScaled + c2 * bScaled + 512) shr 10).coerceIn(0, 4095)
                        gCcm = ((c3 * rScaled + c4 * gScaled + c5 * bScaled + 512) shr 10).coerceIn(0, 4095)
                        bCcm = ((c6 * rScaled + c7 * gScaled + c8 * bScaled + 512) shr 10).coerceIn(0, 4095)
                    } else {
                        rCcm = rScaled.coerceIn(0, 4095)
                        gCcm = gScaled.coerceIn(0, 4095)
                        bCcm = bScaled.coerceIn(0, 4095)
                    }
                    var r8 = lutR[rCcm]
                    var g8 = lutG[gCcm]
                    var b8 = lutB[bCcm]
                    if (needsColorPost) {
                        val y8 = ((306 * r8 + 601 * g8 + 117 * b8 + 512) shr 10).coerceIn(0, 255)
                        if (monochromeMode) {
                            r8 = y8; g8 = y8; b8 = y8
                        } else if (hasMonoTone) {
                            val dCb = (monoToneDeltaCb10 * 2 * y8) / 1024
                            val dCr = (monoToneDeltaCr10 * 2 * y8) / 1024
                            r8 = (y8 + ((1436 * dCr + 512) shr 10)).coerceIn(0, 255)
                            g8 = (y8 - ((352 * dCb + 731 * dCr + 512) shr 10)).coerceIn(0, 255)
                            b8 = (y8 + ((1815 * dCb + 512) shr 10)).coerceIn(0, 255)
                        } else {
                            val cb = ((-173 * r8 - 339 * g8 + 512 * b8) shr 10)
                            val cr = ((512 * r8 - 429 * g8 - 83 * b8) shr 10)
                            val cb2 = ((cm0 * cb + cm1 * cr) / 1024).coerceIn(-128, 127)
                            val cr2 = ((cm2 * cb + cm3 * cr) / 1024).coerceIn(-128, 127)
                            r8 = (y8 + ((1436 * cr2 + 512) shr 10)).coerceIn(0, 255)
                            g8 = (y8 - ((352 * cb2 + 731 * cr2 + 512) shr 10)).coerceIn(0, 255)
                            b8 = (y8 + ((1815 * cb2 + 512) shr 10)).coerceIn(0, 255)
                        }
                    }
                    srcBgr[p] = b8.toByte()
                    srcBgr[p + 1] = g8.toByte()
                    srcBgr[p + 2] = r8.toByte()
                    p += 3
                }
            }
            OpticalExposeResult.Success(OpticalBgrFrame(srcBgr, srcW, srcH))
        } catch (e: Exception) {
            OpticalExposeResult.Error(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Scales a previously demosaiced [OpticalBgrFrame] to `(width, height)` and encodes to JPEG.
     * On the native path, streams 8/16-row MCU strips directly in C without allocating a full-frame
     * `width * height * 3` intermediate buffer (eliminates 305 MB+ GC bombs at 102MP).
     */
    internal fun encodeFromOpticalBgr(
        frame: OpticalBgrFrame,
        width: Int,
        height: Int,
        qscale: Int,
        maxBytes: Long,
        qualityOverride: Float? = null,
        cropFactor: Double = 1.0,
        useNative: Boolean = true,
    ): EncodeResult {
        if (width <= 0 || height <= 0 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION) {
            return EncodeResult.Error("Invalid optical encode dimensions: ${width}x$height")
        }
        return try {
            val srcW = frame.width
            val srcH = frame.height
            val srcBgr = frame.srcBgr

            // Aspect-preserving center crop (BUG-V3-2-3): prevents anamorphic stretch on 4:3, 1:1, 5:4, 7:6, 65:24
            // and optionally applies 35mm format center crop magnification when cropFactor > 1.0.
            val srcAspect = srcW.toDouble() / srcH.toDouble()
            val dstAspect = width.toDouble() / height.toDouble()
            val baseCropW: Double
            val baseCropH: Double
            if (srcAspect > dstAspect) {
                baseCropH = srcH.toDouble()
                baseCropW = (srcH.toDouble() * dstAspect).coerceAtMost(srcW.toDouble())
            } else {
                baseCropW = srcW.toDouble()
                baseCropH = (srcW.toDouble() / dstAspect).coerceAtMost(srcH.toDouble())
            }
            val effFactor = cropFactor.coerceIn(1.0, 4.0)
            val cropW = (baseCropW / effFactor).roundToInt().coerceIn(1, srcW)
            val cropH = (baseCropH / effFactor).roundToInt().coerceIn(1, srcH)
            val cropX = ((srcW - cropW) / 2).coerceIn(0, srcW - cropW)
            val cropY = ((srcH - cropH) / 2).coerceIn(0, srcH - cropH)

            val qualityF = (qualityOverride ?: qualityFromQscale(qscale)).coerceIn(0.05f, 0.98f)
            if (useNative && NativeCodecBridge.hasJpeg) {
                val qualityInt = (qualityF * 100.0f).roundToInt().coerceIn(5, 98)
                when (
                    val outcome = NativeCodecBridge.jpegEncodeScaledBgr24(
                        srcBgr = srcBgr,
                        srcWidth = srcW,
                        srcHeight = srcH,
                        cropX = cropX,
                        cropY = cropY,
                        cropWidth = cropW,
                        cropHeight = cropH,
                        dstWidth = width,
                        dstHeight = height,
                        quality = qualityInt,
                        maxBytes = maxBytes,
                    )
                ) {
                    is NativeCodecBridge.JpegEncodeOutcome.Success ->
                        return EncodeResult.Success(outcome.jpegBytes, width, height)
                    is NativeCodecBridge.JpegEncodeOutcome.Overflow ->
                        return EncodeResult.Overflow(outcome.actualBytes, maxBytes)
                    is NativeCodecBridge.JpegEncodeOutcome.Error -> {
                        if (outcome.code != NativeCodecBridge.ERR_NO_BACKEND) {
                            return EncodeResult.Error("Native scaled JPEG BGR24 encode failed (code=${outcome.code})")
                        }
                    }
                }
            } else if (useNative) {
                NativeCodecBridge.warnJpegFallbackOnce()
            }

            val fallbackImg = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
            val dstBgr = (fallbackImg.raster.dataBuffer as DataBufferByte).data
            val xMap = IntArray(width) { dx ->
                (cropX + ((dx.toLong() * cropW) / width).toInt()).coerceIn(0, srcW - 1) * 3
            }
            val srcRowBytes = srcW * 3
            val dstRowBytes = width * 3
            for (dy in 0 until height) {
                val sy = (cropY + ((dy.toLong() * cropH) / height).toInt()).coerceIn(0, srcH - 1)
                val sRow = sy * srcRowBytes
                var dOff = dy * dstRowBytes
                for (dx in 0 until width) {
                    val sOff = sRow + xMap[dx]
                    dstBgr[dOff] = srcBgr[sOff]
                    dstBgr[dOff + 1] = srcBgr[sOff + 1]
                    dstBgr[dOff + 2] = srcBgr[sOff + 2]
                    dOff += 3
                }
            }

            val writers = ImageIO.getImageWritersByFormatName("jpg")
            if (!writers.hasNext()) {
                return EncodeResult.Error("No JPEG ImageWriter available")
            }
            val writer = writers.next()
            val baos = ByteArrayOutputStream()
            try {
                MemoryCacheImageOutputStream(baos).use { out ->
                    writer.output = out
                    val param = writer.defaultWriteParam
                    if (param.canWriteCompressed()) {
                        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        param.compressionQuality = qualityF
                    }
                    writer.write(null, IIOImage(fallbackImg, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            val encoded = baos.toByteArray()
            if (maxBytes > 0L && encoded.size.toLong() > maxBytes) {
                EncodeResult.Overflow(encoded.size, maxBytes)
            } else {
                EncodeResult.Success(encoded, width, height)
            }
        } catch (e: Exception) {
            EncodeResult.Error(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Exposes the active optically-filtered [SensorPort] frame, demosaics RGGB Bayer -> sRGB,
     * streams scaled RGB/YCbCr strips directly to JPEG (or falls back to JVM ImageIO when native
     * JPEG is disabled), for 34-bit AXI high-DDR / companion-engine capture buffers.
     */
    fun encodeFromOpticalSensor(
        tick: Long,
        sensor: SensorPort,
        lens: LensParameters?,
        width: Int,
        height: Int,
        qscale: Int,
        maxBytes: Long,
        qualityOverride: Float? = null,
        durationNanos: Long = NOMINAL_DURATION_NANOS,
        iso: Int = NOMINAL_ISO,
        gainR1024: Int = 1024,
        gainG1024: Int = 1024,
        gainB1024: Int = 1024,
        cropFactor: Double = 1.0,
        ccmQ10: IntArray? = null,
        chromaMatQ10: IntArray? = null,
        monochromeMode: Boolean = false,
        monoToneDeltaCb10: Int = 0,
        monoToneDeltaCr10: Int = 0,
        useNative: Boolean = true,
    ): EncodeResult {
        if (width <= 0 || height <= 0 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION) {
            return EncodeResult.Error("Invalid optical encode dimensions: ${width}x$height")
        }
        return when (
            val exposed = exposeAndDemosaicOpticalSensor(
                tick = tick,
                sensor = sensor,
                lens = lens,
                durationNanos = durationNanos,
                iso = iso,
                gainR1024 = gainR1024,
                gainG1024 = gainG1024,
                gainB1024 = gainB1024,
                ccmQ10 = ccmQ10,
                chromaMatQ10 = chromaMatQ10,
                monochromeMode = monochromeMode,
                monoToneDeltaCb10 = monoToneDeltaCb10,
                monoToneDeltaCr10 = monoToneDeltaCr10,
                fallbackWidthHint = width,
                fallbackHeightHint = height,
            )
        ) {
            is OpticalExposeResult.Error -> EncodeResult.Error(exposed.message)
            is OpticalExposeResult.Success -> encodeFromOpticalBgr(
                frame = exposed.frame,
                width = width,
                height = height,
                qscale = qscale,
                maxBytes = maxBytes,
                qualityOverride = qualityOverride,
                cropFactor = cropFactor,
                useNative = useNative,
            )
        }
    }
}
