package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Live-view CMOS Bayer-to-YUV DMA engine for `0xFFF72000` (LV Ch0) and `0xFFF73000` (LV Ch1).
 *
 * Supports both:
 * - **2-plane semi-planar `Y + UV`** (real-silicon `DAT` layout: Plane 0 Y at `+0xC0/+0xC4`,
 *   Plane 1 interleaved UV at `+0xC8/+0xCC`, stride at `+0xD0`; NV16 4:2:2 by default, or
 *   NV12 4:2:0 when `+0xD4[21:20] == 1`) whenever Plane 1 UV base (`+0xC8/+0xCC`) is non-zero.
 * - **1-plane packed BT.601 `UYVY`** whenever Plane 1 UV base is zero (preserving 100% backward
 *   compatibility with existing stock-firmware live-view scanout paths).
 */
internal class LiveViewDmaEngine {
    // Successive RECC_S Bayer strips belong to the same physical sensor exposure.
    private var stillBayerSource: ByteArray? = null
    private var stillBayerBytes = 0
    private var stillBayerBase = -1L
    private var stillBayerNextRow = 0

    private val movieTiles = ReccMovieTiles()
    private var movieRaster = ByteArray(0)
    private var movieRasterBase = -1L
    private var movieNextRow = -1
    private var movieFrameHeight = 0
    private var movieActiveWidth = 0
    private var movieTopRows = 0

    fun renderMovieTileStrip(
        tick: Long, base: Long, width: Int, frameHeight: Int, firstRow: Int, rows: Int, stride: Int,
        sensor: SensorPort?, memory: DmaMemory?, lens: LensParameters?, exposure: ExposureParams,
        colorPipeline: IspColorPipeline,
    ): Boolean {
        if (sensor == null || memory == null) return false
        val continuing = firstRow > 0 && movieRasterBase == base && movieNextRow == firstRow
        val projectionHeight = if (firstRow == 0 && movieActiveWidth == width && movieTopRows == rows) {
            movieFrameHeight
        } else frameHeight
        if (!continuing) {
            val sourceBytes = DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2
            if (movieRaster.size != sourceBytes) movieRaster = ByteArray(sourceBytes)
            // The sensor's native live raster is 1024 x 576. Use the same GPU exposure, lens and
            // color transform as LV, and keep this exposure across the firmware's strip requests.
            val produced = pumpPackedUyvy(tick, base, DEFAULT_SENSOR_WIDTH * 2L,
                DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, sensor, memory, lens,
                MfAssistPeakingFilter.DISABLED, exposure, null, colorPipeline) { packed ->
                packed.copyInto(movieRaster, endIndex = sourceBytes)
            }
            if (!produced) {
                movieRasterBase = -1L
                return false
            }
        }
        // The lower strip establishes the active height. Correct the initial upper strip
        // when a new mode has a different split, before raising the lower strip's IRQ.
        if (continuing && movieFrameHeight != projectionHeight && !movieTiles.write(
                memory, base, width, projectionHeight, 0, firstRow, stride, movieRaster,
                DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT)) return false
        if (firstRow == 0) movieTopRows = rows
        movieRasterBase = base
        movieNextRow = firstRow + rows
        movieActiveWidth = width
        movieFrameHeight = projectionHeight
        return movieTiles.write(memory, base, width, projectionHeight, firstRow, rows, stride,
            movieRaster, DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, extendBottom = firstRow > 0)
    }

    fun invalidateStillBayerExposure() {
        movieRasterBase = -1L
        movieActiveWidth = 0
        stillBayerBase = -1L
    }

    fun renderStillBayerStrip(
        tick: Long,
        base: Long,
        width: Int,
        frameHeight: Int,
        firstRow: Int,
        rows: Int,
        stride: Int,
        bits: Int,
        sensor: SensorPort?,
        memory: DmaMemory?,
        lens: LensParameters?,
        exposure: ExposureParams,
        readout: IspStillReadout? = null,
    ): Boolean {
        if (sensor == null || memory == null || bits !in 8..16 || base == 0L ||
            width !in 2..16384 || width % 2 != 0 || frameHeight !in 2..16384 ||
            firstRow < 0 || rows !in 1..frameHeight || firstRow > frameHeight - rows ||
            stride !in width * 2..0x3ffff) return false
        val start = base + firstRow.toLong() * stride
        val span = (rows - 1L) * stride + width * 2L
        if (start !in 0..(1L shl 34) - span) return false
        try {
            memory.check(start, span, true)
        } catch (_: IllegalArgumentException) {
            return false
        }

        val pixels = DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT
        val source = stillBayerSource ?: ByteArray(pixels * 2).also { stillBayerSource = it }
        if (firstRow == 0 || stillBayerBase != base || firstRow != stillBayerNextRow) {
            stillBayerBytes = try {
                val destination = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
                readout?.copyBayerTo(destination) ?: sensor.exposeStill(
                    Exposure(tick, exposure.durationNanos, exposure.iso, lens ?: DEFAULT_LENS),
                    destination,
                )
            } catch (_: IllegalStateException) {
                stillBayerBase = -1L
                return false
            }
        }
        if (stillBayerBytes != pixels * 2 && stillBayerBytes != pixels) {
            stillBayerBase = -1L
            return false
        }
        stillBayerBase = base
        stillBayerNextRow = firstRow + rows
        val crop = computeStillCrop(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, frameHeight, 1.0)
        // Scale the four Bayer phases independently. Copying the nearest individual pixel
        // would turn red samples into green or blue at alternating destination coordinates.
        val sourceX = IntArray(width) { x -> crop[0] + ((x / 2 * crop[2] / width) * 2) + x % 2 }
        val rowBytes = width * 2
        val rowCache = Array(2) { ByteArray(rowBytes) }
        val cachedY = intArrayOf(-1, -1)
        val stripRows = minOf(rows, 32)
        val strip = ByteArray(stride * (stripRows - 1) + rowBytes)
        for (offset in 0 until rows step stripRows) {
            val count = minOf(stripRows, rows - offset)
            for (j in 0 until count) {
                val y = firstRow + offset + j
                val parity = y and 1
                val sy = crop[1] + (y / 2 * crop[3] / frameHeight) * 2 + parity
                val row = rowCache[parity]
                if (cachedY[parity] != sy) {
                    val srcStart = sy * DEFAULT_SENSOR_WIDTH
                    for (x in sourceX.indices) {
                        val i = srcStart + sourceX[x]
                        val linear = if (stillBayerBytes == pixels * 2) {
                            (source[i * 2].toInt() and 255) or ((source[i * 2 + 1].toInt() and 255) shl 8)
                        } else {
                            (source[i].toInt() and 255) * 257
                        }
                        val value = linear ushr (16 - bits)
                        row[x * 2] = value.toByte()
                        row[x * 2 + 1] = (value ushr 8).toByte()
                    }
                    cachedY[parity] = sy
                }
                row.copyInto(strip, j * stride)
            }
            writeStrided2D(memory, start + offset.toLong() * stride, strip, rowBytes, stride, count)
        }
        return true
    }

    internal data class ExposureParams(
        val durationNanos: Long = NOMINAL_DURATION_NANOS,
        val iso: Int = NOMINAL_ISO,
        val gainR1024: Int = UNITY_GAIN_1024,
        val gainG1024: Int = UNITY_GAIN_1024,
        val gainB1024: Int = UNITY_GAIN_1024,
    ) {
        val isUnityGain: Boolean get() = gainR1024 == UNITY_GAIN_1024 && gainG1024 == UNITY_GAIN_1024 && gainB1024 == UNITY_GAIN_1024
        val gainSignature: Long
            get() = (gainR1024.toLong() and 0xFFFFFL) or
                ((gainG1024.toLong() and 0xFFFFFL) shl 20) or
                ((gainB1024.toLong() and 0xFFFFFL) shl 40)
    }

    internal data class StatsConfig(
        val engine: IspStatsSramEngine,
        val reg5c: Long,
        val reg60: Long,
        val reg68: Long = 0L,
        val reg6c: Long = 0L,
        val reg124: Long = 0L,
        val reg128: Long = 0L,
        val reg12c: Long = 0L,
        val reg134: Long = 0L,
        val reg138: Long = 0L,
        val reg13c: Long = 0L,
        val reg1fc: Long = 0L,
        val crop6e0c: Long = 0L,
        val crop6e10: Long = 0L,
        val crop6e14: Long = 0L,
        val crop6e18: Long = 0L,
        val obR: Int = IspStatsSramEngine.DEFAULT_OB_14BIT,
        val obG1: Int = IspStatsSramEngine.DEFAULT_OB_14BIT,
        val obB: Int = IspStatsSramEngine.DEFAULT_OB_14BIT,
        val obG2: Int = IspStatsSramEngine.DEFAULT_OB_14BIT,
    )

    internal data class CropZoom(
        val fracW: Double = 1.0,
        val centerX: Double = 0.5,
        val centerY: Double = 0.5,
    ) {
        val isZoomed: Boolean get() = fracW < ZOOM_DEADBAND_MAX

        companion object {
            const val ZOOM_DEADBAND_MAX = 0.90
            val FULL_FRAME = CropZoom()
        }
    }

    private data class ResolvedCropRect(
        val cropX: Int,
        val cropY: Int,
        val cropW: Int,
        val cropH: Int,
        val offX: Int,
        val offY: Int,
        val fitW: Int,
        val fitH: Int,
        val zoomed: Boolean,
    ) {
        val cacheKey: Long
            get() = (cropX.toLong() shl 36) or
                (cropY.toLong() shl 24) or
                (cropW.toLong() shl 12) or
                cropH.toLong()
    }

    private class CachedYuvSlot {
        var rawGeneration: Long = -1L
        var gainSignature: Long = -1L
        var srcW: Int = -1
        var srcH: Int = -1
        var is16Bit: Boolean = false
        var dstW: Int = -1
        var dstH: Int = -1
        var stride: Int = -1
        var isSemiPlanar: Boolean = false
        var isNv12: Boolean = false
        var isYyuv422: Boolean = false
        var peakingCacheKey: Long = 0L
        var cropCacheKey: Long = 0L
        var fusedStateKey: Long = -1L
        var lastUsedSeq: Long = 0L
        var yuvBytes: ByteArray? = null
        var uvBytes: ByteArray? = null

        fun matches(
            generation: Long,
            gainSig: Long,
            srcW: Int,
            srcH: Int,
            is16Bit: Boolean,
            dstW: Int,
            dstH: Int,
            stride: Int,
            isSemiPlanar: Boolean,
            isNv12: Boolean,
            isYyuv422: Boolean,
            peakingCacheKey: Long,
            cropCacheKey: Long,
            yByteCount: Int,
            uvByteCount: Int,
        ): Boolean {
            if (
                rawGeneration != generation ||
                gainSignature != gainSig ||
                this.srcW != srcW ||
                this.srcH != srcH ||
                this.is16Bit != is16Bit ||
                this.dstW != dstW ||
                this.dstH != dstH ||
                this.stride != stride ||
                this.isSemiPlanar != isSemiPlanar ||
                this.isNv12 != isNv12 ||
                this.isYyuv422 != isYyuv422 ||
                this.peakingCacheKey != peakingCacheKey ||
                this.cropCacheKey != cropCacheKey
            ) {
                return false
            }
            val y = yuvBytes ?: return false
            if (y.size < yByteCount) return false
            if (isSemiPlanar) {
                val uv = uvBytes ?: return false
                if (uv.size < uvByteCount) return false
            }
            return true
        }
    }

    private var rawSensorScratch: ByteArray? = null
    private var croppedRawScratch: ByteArray? = null
    private var cachedRawBytes: ByteArray? = null
    private var cachedRawLength: Int = -1
    private var cachedRawSrcW: Int = -1
    private var cachedRawSrcH: Int = -1
    private var cachedRawIs16Bit: Boolean = false
    private var cachedRawSensor: SensorPort? = null
    private var cachedSensorGen: Long = -1L
    private var rawGeneration: Long = 0L
    private var consecutiveRawChanges: Int = 0
    internal var directRamFramesWritten: Long = 0L
        private set

    internal var stillFusedFrames: Long = 0L
        private set
    internal var stillStripCacheHits: Long = 0L
        private set
    private class StillStripCache(
        val tick: Long,
        val width: Int,
        val height: Int,
        val gainR1024: Int,
        val gainG1024: Int,
        val gainB1024: Int,
        val durationNanos: Long,
        val iso: Int,
        val colorSignature: Long,
        val bayerFingerprint: Long,
        val isYyuv422: Boolean,
        val srcW: Int,
        val srcH: Int,
        val srcStride: Int,
        val srcUyvy: ByteArray,
    )
    private var stillStripCache: StillStripCache? = null
    private var lastAeRawGeneration: Long = -1L
    private var lastAeFusedKey: Long = -1L
    private var lastStatsConfig: StatsConfig? = null
    private var lastStatsCropKey = -1L
    private var slotAccessSeq: Long = 0L
    private val yuvSlots = Array(YUV_CACHE_SLOTS) { CachedYuvSlot() }
    private var srcXMapScratch: IntArray? = null
    private val ispCfgScratch = IntArray(20)
    private val histYrgbScratch = IntArray(1024)
    private val fusedStatsScratch = IntArray(LiveViewIspFusionRequest.STATS_OUTPUT_WORDS)
    internal var allowNativeSimd: Boolean = true

    private var lastExposedBytes: Int = 0
    private var lastExposedSrcW: Int = 0
    private var lastExposedSrcH: Int = 0
    private var lastExposedIs16Bit: Boolean = false
    private var lastExposedTick: Long = Long.MIN_VALUE
    private var lastExposedSensor: SensorPort? = null
    private var lastExposedDurationNanos: Long = -1L
    private var lastExposedIso: Int = -1
    private var lastExposedLens: LensParameters? = null
    private var lastExposedReqW: Int = -1
    private var lastExposedReqH: Int = -1
    private var lastExposeReusedCachedRaw: Boolean = false

    internal var cacheHits: Long = 0L
        private set
    internal var cacheMisses: Long = 0L
        private set
    internal var sameTickExposeReuses: Long = 0L
        private set
    internal var sensorGenFastPathHits: Long = 0L
        private set
    internal var arraysEqualsFallbackCalls: Long = 0L
        private set
    internal var gpuFusedIspFrames: Long = 0L
        private set

    fun pumpLiveViewDma(
        tick: Long,
        addr: Long,
        strideLong: Long,
        sizeWord: Long,
        ctrl: Long,
        sensor: SensorPort?,
        memory: DmaMemory?,
        lens: LensParameters?,
        uvAddr: Long = 0L,
        yHighAddr: Long = 0L,
        uvHighAddr: Long = 0L,
        peaking: MfAssistPeakingFilter.PeakingConfig = MfAssistPeakingFilter.DISABLED,
        exposure: ExposureParams = DEFAULT_EXPOSURE_PARAMS,
        statsConfig: StatsConfig? = null,
        colorPipeline: IspColorPipeline? = null,
        yyuv422: Boolean = false,
        cropZoom: CropZoom = CropZoom.FULL_FRAME,
    ): Boolean {
        if (sensor == null || memory == null) return false
        val plane0Addr = ((yHighAddr and 0x3L) shl 32) or (addr and 0xFFFF_FFFFL)
        val plane1Addr = ((uvHighAddr and 0x3L) shl 32) or (uvAddr and 0xFFFF_FFFFL)
        if (plane0Addr == 0L || (ctrl and ENABLE_BIT_28) == 0L) return false

        val width = ((sizeWord and 0xffffL) + 1L).toInt()
        val height = (((sizeWord ushr 16) and 0xffffL) + 1L).toInt()
        if (width !in 2..2048 || width % 2 != 0 || height !in 1..2048) return false

        return if (plane1Addr != 0L) {
            pumpSemiPlanarYuv(tick, plane0Addr, plane1Addr, strideLong, width, height, ctrl, sensor, memory, lens, peaking, exposure, statsConfig, colorPipeline, cropZoom)
        } else {
            pumpPackedUyvy(tick, plane0Addr, strideLong, width, height, sensor, memory, lens, peaking, exposure, statsConfig, colorPipeline, yyuv422, cropZoom)
        }
    }

    private fun exposeSensor(
        tick: Long,
        width: Int,
        height: Int,
        sensor: SensorPort,
        lens: LensParameters?,
        exposure: ExposureParams = DEFAULT_EXPOSURE_PARAMS,
    ): Boolean {
        val effDuration = exposure.durationNanos.coerceAtLeast(1L)
        val effIso = exposure.iso.coerceAtLeast(1)
        val effLens = lens ?: DEFAULT_LENS
        val prevRaw = cachedRawBytes
        if (
            prevRaw != null &&
            tick == lastExposedTick &&
            sensor === lastExposedSensor &&
            effDuration == lastExposedDurationNanos &&
            effIso == lastExposedIso &&
            effLens == lastExposedLens &&
            (cachedRawLength == DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2 ||
                (lastExposedReqW == width && lastExposedReqH == height))
        ) {
            lastExposedBytes = cachedRawLength
            lastExposedSrcW = cachedRawSrcW
            lastExposedSrcH = cachedRawSrcH
            lastExposedIs16Bit = cachedRawIs16Bit
            lastExposeReusedCachedRaw = true
            sameTickExposeReuses++
            return true
        }
        lastExposeReusedCachedRaw = false
        val rawCapacity = maxOf(DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2, width * height * 2)
        val rawBytes = rawSensorScratch?.takeIf { it.size >= rawCapacity }
            ?: ByteArray(rawCapacity).also { rawSensorScratch = it }
        val rawBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val exposedBytes = try {
            sensor.expose(Exposure(tick, effDuration, effIso, effLens), rawBuf)
        } catch (_: IllegalStateException) {
            return false
        }
        if (exposedBytes <= 0) return false
        lastExposedBytes = exposedBytes
        when (exposedBytes) {
            DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2 -> {
                lastExposedSrcW = DEFAULT_SENSOR_WIDTH
                lastExposedSrcH = DEFAULT_SENSOR_HEIGHT
                lastExposedIs16Bit = true
            }
            width * height * 2 -> {
                lastExposedSrcW = width
                lastExposedSrcH = height
                lastExposedIs16Bit = true
            }
            DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT -> {
                lastExposedSrcW = DEFAULT_SENSOR_WIDTH
                lastExposedSrcH = DEFAULT_SENSOR_HEIGHT
                lastExposedIs16Bit = false
            }
            width * height -> {
                lastExposedSrcW = width
                lastExposedSrcH = height
                lastExposedIs16Bit = false
            }
            else -> throw IllegalArgumentException("Unsupported CMOS expose byte count $exposedBytes for ${width}x${height}")
        }
        lastExposedTick = tick
        lastExposedSensor = sensor
        lastExposedDurationNanos = effDuration
        lastExposedIso = effIso
        lastExposedLens = effLens
        lastExposedReqW = width
        lastExposedReqH = height
        return true
    }

    /**
     * Checks whether the newly exposed Bayer buffer matches the cached raw frame and advances
     * [rawGeneration] via zero-copy buffer swap when it changed.
     *
     * Fast-path: when [sensor.currentFrameGeneration] returns a non-negative monotonic token
     * (`>= 0L`), compares that O(1) generation token directly and skips the 1.2 MB `Arrays.equals`
     * full-frame scan completely. Preserves exact `Arrays.equals` fallback when `-1L`.
     */
    private fun updateRawGeneration(
        sensor: SensorPort,
        rawBytes: ByteArray?,
        exposedBytes: Int,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
    ): ByteArray {
        val prevRaw = cachedRawBytes
        if (lastExposeReusedCachedRaw && prevRaw != null) {
            consecutiveRawChanges = 0
            return prevRaw
        }
        val sensorGen = sensor.currentFrameGeneration()
        if (
            sensorGen >= 0L &&
            prevRaw != null &&
            cachedRawSensor === sensor &&
            cachedSensorGen == sensorGen &&
            cachedRawLength == exposedBytes &&
            cachedRawSrcW == srcW &&
            cachedRawSrcH == srcH &&
            cachedRawIs16Bit == is16Bit
        ) {
            sensorGenFastPathHits++
            consecutiveRawChanges = 0
            return prevRaw
        }
        val incoming = checkNotNull(rawBytes)
        if (sensorGen < 0L) {
            arraysEqualsFallbackCalls++
            if (
                prevRaw != null &&
                cachedRawLength == exposedBytes &&
                cachedRawSrcW == srcW &&
                cachedRawSrcH == srcH &&
                cachedRawIs16Bit == is16Bit &&
                Arrays.equals(incoming, 0, exposedBytes, prevRaw, 0, exposedBytes)
            ) {
                consecutiveRawChanges = 0
                return prevRaw
            }
        }
        val nextScratch = if (prevRaw != null && prevRaw.size >= incoming.size) prevRaw else ByteArray(incoming.size)
        cachedRawBytes = incoming
        rawSensorScratch = nextScratch
        cachedRawLength = exposedBytes
        cachedRawSrcW = srcW
        cachedRawSrcH = srcH
        cachedRawIs16Bit = is16Bit
        cachedRawSensor = sensor
        cachedSensorGen = sensorGen
        rawGeneration++
        consecutiveRawChanges++
        return incoming
    }

    private fun maybeUpdateAeStats(
        activeRaw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        statsConfig: StatsConfig?,
        viewRect: ResolvedCropRect? = null,
    ) {
        if (statsConfig == null) return
        val vx = viewRect?.cropX ?: 0
        val vy = viewRect?.cropY ?: 0
        val vw = viewRect?.cropW ?: srcW
        val vh = viewRect?.cropH ?: srcH
        if (
            lastAeRawGeneration == rawGeneration &&
            lastStatsConfig == statsConfig &&
            lastStatsCropKey == (viewRect?.cacheKey ?: 0L) &&
            statsConfig.engine.aeFramesUpdated > 0L
        ) {
            return
        }
        if (VulkanIspStatsBridge.isHardwareGpuAvailable) {
            val activeX0 = vx.coerceIn(0, (srcW - 2).coerceAtLeast(0)) and 1.inv()
            val activeY0 = vy.coerceIn(0, (srcH - 2).coerceAtLeast(0)) and 1.inv()
            val activeW = vw.coerceIn(2, srcW - activeX0) and 1.inv()
            val activeH = vh.coerceIn(2, srcH - activeY0) and 1.inv()
            val gpuStats = VulkanIspStatsBridge.reduceHardware(
                input = activeRaw,
                width = srcW,
                height = srcH,
                inputFormat = if (is16Bit) VulkanIspStatsBridge.FMT_BAYER_RGGB_16LE else VulkanIspStatsBridge.FMT_BAYER_RGGB_8,
                viewX = activeX0,
                viewY = activeY0,
                viewW = activeW,
                viewH = activeH,
                gridCols = 16,
                gridRows = 16,
                stepQuad = if (activeW >= 512 && activeH >= 256) 2 else 1,
                afRoiX0 = activeX0,
                afRoiY0 = activeY0,
                afRoiX1 = activeX0 + activeW,
                afRoiY1 = activeY0 + activeH,
            )
            if (gpuStats != null) {
                statsConfig.engine.ingestGpuStats(
                    stats = gpuStats,
                    srcW = srcW,
                    srcH = srcH,
                    reg5c = statsConfig.reg5c,
                    reg60 = statsConfig.reg60,
                    reg68 = statsConfig.reg68,
                    reg6c = statsConfig.reg6c,
                    obR = statsConfig.obR,
                    obG1 = statsConfig.obG1,
                    obB = statsConfig.obB,
                    obG2 = statsConfig.obG2,
                    reg124 = statsConfig.reg124,
                    reg128 = statsConfig.reg128,
                    reg134 = statsConfig.reg134,
                    reg138 = statsConfig.reg138,
                    commitHist = false,
                )
                lastAeRawGeneration = rawGeneration
                lastAeFusedKey = -1L
                lastStatsConfig = statsConfig
                lastStatsCropKey = viewRect?.cacheKey ?: 0L
                return
            }
        }
        statsConfig.engine.updateAeFromBayer(
            raw = activeRaw,
            srcW = srcW,
            srcH = srcH,
            is16Bit = is16Bit,
            reg5c = statsConfig.reg5c,
            reg60 = statsConfig.reg60,
            reg68 = statsConfig.reg68,
            reg6c = statsConfig.reg6c,
            reg124 = statsConfig.reg124,
            reg128 = statsConfig.reg128,
            reg12c = statsConfig.reg12c,
            reg134 = statsConfig.reg134,
            reg138 = statsConfig.reg138,
            reg13c = statsConfig.reg13c,
            reg1fc = statsConfig.reg1fc,
            crop6e0c = statsConfig.crop6e0c,
            crop6e10 = statsConfig.crop6e10,
            crop6e14 = statsConfig.crop6e14,
            crop6e18 = statsConfig.crop6e18,
            obR = statsConfig.obR,
            obG1 = statsConfig.obG1,
            obB = statsConfig.obB,
            obG2 = statsConfig.obG2,
            viewX = vx,
            viewY = vy,
            viewW = vw,
            viewH = vh,
        )
        lastAeRawGeneration = rawGeneration
        lastAeFusedKey = -1L
        lastStatsConfig = statsConfig
        lastStatsCropKey = viewRect?.cacheKey ?: 0L
    }

    private fun findCachedSlot(
        gainSig: Long,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        dstW: Int,
        dstH: Int,
        stride: Int,
        isSemiPlanar: Boolean,
        isNv12: Boolean,
        isYyuv422: Boolean,
        peakingCacheKey: Long,
        cropCacheKey: Long,
        yByteCount: Int,
        uvByteCount: Int,
    ): CachedYuvSlot? {
        val gen = rawGeneration
        for (slot in yuvSlots) {
            if (slot.matches(gen, gainSig, srcW, srcH, is16Bit, dstW, dstH, stride, isSemiPlanar, isNv12, isYyuv422, peakingCacheKey, cropCacheKey, yByteCount, uvByteCount)) {
                slot.lastUsedSeq = ++slotAccessSeq
                return slot
            }
        }
        return null
    }

    private fun findCachedFusedSlot(
        fusedKey: Long,
        gainSig: Long,
        dstW: Int,
        dstH: Int,
        stride: Int,
        isSemiPlanar: Boolean,
        isNv12: Boolean,
        isYyuv422: Boolean,
        peakingCacheKey: Long,
        cropCacheKey: Long,
        yByteCount: Int,
        uvByteCount: Int,
    ): CachedYuvSlot? {
        if (fusedKey < 0L) return null
        for (slot in yuvSlots) {
            if (
                slot.fusedStateKey == fusedKey &&
                slot.gainSignature == gainSig &&
                slot.dstW == dstW &&
                slot.dstH == dstH &&
                slot.stride == stride &&
                slot.isSemiPlanar == isSemiPlanar &&
                slot.isNv12 == isNv12 &&
                slot.isYyuv422 == isYyuv422 &&
                slot.peakingCacheKey == peakingCacheKey &&
                slot.cropCacheKey == cropCacheKey
            ) {
                val y = slot.yuvBytes ?: continue
                if (y.size < yByteCount) continue
                if (isSemiPlanar) {
                    val uv = slot.uvBytes ?: continue
                    if (uv.size < uvByteCount) continue
                }
                slot.lastUsedSeq = ++slotAccessSeq
                return slot
            }
        }
        return null
    }

    private fun selectSlotForFill(
        dstW: Int,
        dstH: Int,
        stride: Int,
        isSemiPlanar: Boolean,
        isNv12: Boolean,
        isYyuv422: Boolean = false,
        cropCacheKey: Long = 0L,
    ): CachedYuvSlot {
        // Prefer reusing the slot that already holds buffers for this exact channel geometry.
        for (slot in yuvSlots) {
            if (
                slot.dstW == dstW &&
                slot.dstH == dstH &&
                slot.stride == stride &&
                slot.isSemiPlanar == isSemiPlanar &&
                slot.isNv12 == isNv12 &&
                slot.isYyuv422 == isYyuv422 &&
                slot.cropCacheKey == cropCacheKey
            ) {
                return slot
            }
        }
        // Otherwise evict the least-recently-used slot.
        var lru = yuvSlots[0]
        for (i in 1 until yuvSlots.size) {
            if (yuvSlots[i].lastUsedSeq < lru.lastUsedSeq) {
                lru = yuvSlots[i]
            }
        }
        return lru
    }

    private fun resolveCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int, cropZoom: CropZoom): ResolvedCropRect {
        // Aspect-preserving center-crop ("cover") framing on the CMOS sensor (BUG-V3-2-2):
        // maps a 16:9 (1024x576) optical scene onto 4:3 LCD (1024x768) / EVF (1280x960) by center-cropping
        // the sensor to 4:3 (768x576 @ x=128) with even Bayer alignment, eliminating 96px/120px black bars
        // and double-framing across 4:3, 3:2, 1:1, 5:4, 7:6 while preserving 1.000 square-pixel AR.
        val baseCropW: Int
        val baseCropH: Int
        val srcAspect = srcW.toDouble() / srcH.toDouble()
        val dstAspect = dstW.toDouble() / dstH.toDouble()
        if (srcW == dstW && srcH == dstH) {
            baseCropW = srcW
            baseCropH = srcH
        } else if (srcAspect > dstAspect + 1e-4) {
            baseCropH = (srcH and 1.inv()).coerceAtLeast(2)
            baseCropW = ((baseCropH.toDouble() * dstAspect).roundToInt().coerceIn(2, srcW)) and 1.inv()
        } else if (dstAspect > srcAspect + 1e-4) {
            baseCropW = (srcW and 1.inv()).coerceAtLeast(2)
            baseCropH = ((baseCropW.toDouble() / dstAspect).roundToInt().coerceIn(2, srcH)) and 1.inv()
        } else {
            baseCropW = (srcW and 1.inv()).coerceAtLeast(2)
            baseCropH = (srcH and 1.inv()).coerceAtLeast(2)
        }
        val safeBaseW = baseCropW.coerceIn(2, srcW)
        val safeBaseH = baseCropH.coerceIn(2, srcH)
        if (!cropZoom.isZoomed) {
            val cropX = ((srcW - safeBaseW) / 2) and 1.inv()
            val cropY = ((srcH - safeBaseH) / 2) and 1.inv()
            val isCropped = cropX != 0 || cropY != 0 || safeBaseW != srcW || safeBaseH != srcH
            return ResolvedCropRect(
                cropX = cropX,
                cropY = cropY,
                cropW = safeBaseW,
                cropH = safeBaseH,
                offX = 0,
                offY = 0,
                fitW = dstW,
                fitH = dstH,
                zoomed = isCropped,
            )
        }
        // Apply hardware crop zoom (35mm FORMAT MODE 1.23x, MF Dual Display PIC1 5.35x, Focus Check 3.79x..8.04x)
        // relative to the aspect-matched base window so 35mm ON genuinely zooms 1.23x vs 35mm OFF (BUG-V3-2-5).
        var cropW = ((safeBaseW * cropZoom.fracW).roundToInt().coerceIn(32, safeBaseW)) and 1.inv()
        var cropH = ((cropW.toDouble() * dstH.toDouble() / dstW.toDouble()).roundToInt()) and 1.inv()
        if (cropH > safeBaseH) {
            cropH = safeBaseH and 1.inv()
            cropW = ((cropH.toDouble() * dstW.toDouble() / dstH.toDouble()).roundToInt().coerceIn(32, safeBaseW)) and 1.inv()
        }
        cropW = cropW.coerceIn(32, srcW and 1.inv())
        cropH = cropH.coerceIn(32, srcH and 1.inv())
        val maxCropX = (srcW - cropW).coerceAtLeast(0)
        val maxCropY = (srcH - cropH).coerceAtLeast(0)
        val cropX = ((maxCropX * cropZoom.centerX).roundToInt().coerceIn(0, maxCropX)) and 1.inv()
        val cropY = ((maxCropY * cropZoom.centerY).roundToInt().coerceIn(0, maxCropY)) and 1.inv()
        return ResolvedCropRect(cropX, cropY, cropW, cropH, offX = 0, offY = 0, fitW = dstW, fitH = dstH, zoomed = true)
    }

    private fun ensureSrcXMap(dstW: Int, srcW: Int, cropX: Int, cropW: Int, fitW: Int, offX: Int, endX: Int, sameDims: Boolean): IntArray {
        val map = srcXMapScratch?.takeIf { it.size >= dstW }
            ?: IntArray(dstW).also { srcXMapScratch = it }
        val maxX = srcW - 1
        if (sameDims && cropX == 0 && cropW == srcW) {
            for (x in 0 until dstW) {
                map[x] = x
            }
        } else {
            for (x in offX until endX) {
                map[x] = (cropX + (((x - offX).toLong() * cropW) / fitW).toInt()).coerceIn(0, maxX)
            }
        }
        return map
    }

    private fun writeStrided2D(
        memory: DmaMemory,
        baseAddr: Long,
        bytes: ByteArray,
        rowBytes: Int,
        stride: Int,
        height: Int,
    ) {
        if (height <= 0 || rowBytes <= 0) return
        val totalSpan = (height - 1).toLong() * stride.toLong() + rowBytes.toLong()
        if (memory is DirectGuestRamPort) {
            val directDst = memory.sliceDirect(baseAddr, totalSpan, write = true)
            if (directDst != null) {
                if (stride == rowBytes) {
                    MemorySegment.copy(bytes, 0, directDst, ValueLayout.JAVA_BYTE, 0L, height * rowBytes)
                } else {
                    for (row in 0 until height) {
                        val offset = row * stride
                        MemorySegment.copy(bytes, offset, directDst, ValueLayout.JAVA_BYTE, offset.toLong(), rowBytes)
                    }
                }
                memory.notifyDirectWrite(baseAddr, totalSpan)
                directRamFramesWritten++
                return
            }
        }
        if (stride == rowBytes) {
            memory.write(baseAddr, ByteBuffer.wrap(bytes, 0, height * rowBytes))
            return
        }
        for (row in 0 until height) {
            val offset = row * stride
            memory.write(baseAddr + offset.toLong(), ByteBuffer.wrap(bytes, offset, rowBytes))
        }
    }

    private fun buildFusedStatsRequest(
        srcW: Int,
        srcH: Int,
        statsConfig: StatsConfig,
        viewRect: ResolvedCropRect,
    ): LiveViewIspStatsRequest {
        val activeX0 = viewRect.cropX.coerceIn(0, (srcW - 2).coerceAtLeast(0)) and 1.inv()
        val activeY0 = viewRect.cropY.coerceIn(0, (srcH - 2).coerceAtLeast(0)) and 1.inv()
        val activeW = viewRect.cropW.coerceIn(2, srcW - activeX0) and 1.inv()
        val activeH = viewRect.cropH.coerceIn(2, srcH - activeY0) and 1.inv()
        val gridCols = 16
        val gridRows = 16
        val stepQuad = if (activeW >= 512 && activeH >= 256) 2 else 1

        // Map hardware AF ROI (0xFFF70124/128/12C or 0xFFF70134/138/13C) into active viewport
        // coordinates matching IspStatsSramEngine.updateCdafFromBayer; defaults to full viewport.
        var afRoiX0 = activeX0
        var afRoiY0 = activeY0
        var afRoiX1 = activeX0 + activeW
        var afRoiY1 = activeY0 + activeH
        val usePrimaryAf = statsConfig.reg124 != 0L && statsConfig.reg128 != 0L
        val useSecondaryAf = !usePrimaryAf && statsConfig.reg134 != 0L && statsConfig.reg138 != 0L
        if (usePrimaryAf || useSecondaryAf) {
            val afX0: Int
            val afCols: Int
            val afCellW: Int
            val afY0: Int
            val afRows: Int
            val afCellH: Int
            val afGapX: Int
            val afGapY: Int
            if (usePrimaryAf) {
                afX0 = ((statsConfig.reg124 ushr 16) and 0xFFFL).toInt()
                afCols = (((statsConfig.reg124 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, IspStatsSramEngine.AF_MAX_COLS)
                afCellW = ((statsConfig.reg124 and 0x1FFL).toInt() + 1).coerceAtLeast(2)
                afY0 = ((statsConfig.reg128 ushr 16) and 0xFFFL).toInt()
                afRows = (((statsConfig.reg128 ushr 12) and 0xFL).toInt() + 1).coerceIn(1, IspStatsSramEngine.AF_MAX_ROWS)
                afCellH = ((statsConfig.reg128 and 0x1FFL).toInt() + 1).coerceAtLeast(2)
                afGapX = ((statsConfig.reg12c ushr 16) and 0x1FFL).toInt()
                afGapY = (statsConfig.reg12c and 0x1FFL).toInt()
            } else {
                afX0 = ((statsConfig.reg134 ushr 16) and 0xFFFL).toInt()
                afCols = (((statsConfig.reg134 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, IspStatsSramEngine.AF_MAX_COLS)
                afCellW = ((statsConfig.reg134 and 0xFFL).toInt() + 1).coerceAtLeast(2)
                afY0 = ((statsConfig.reg138 ushr 16) and 0xFFFL).toInt()
                afRows = (((statsConfig.reg138 ushr 8) and 0xFL).toInt() + 1).coerceIn(1, IspStatsSramEngine.AF_MAX_ROWS)
                afCellH = ((statsConfig.reg138 and 0xFFL).toInt() + 1).coerceAtLeast(2)
                afGapX = ((statsConfig.reg13c ushr 16) and 0xFFL).toInt()
                afGapY = (statsConfig.reg13c and 0xFFL).toInt()
            }
            val afEndX = afX0 + afCols * afCellW + (afCols - 1) * afGapX
            val afEndY = afY0 + afRows * afCellH + (afRows - 1) * afGapY
            val c1W = (statsConfig.crop6e14 and 0x1FFFL).toInt()
            val c1H = (statsConfig.crop6e18 and 0x7FFFL).toInt()
            val c0W = (statsConfig.crop6e0c and 0x7FFFL).toInt()
            val c0H = (statsConfig.crop6e10 and 0x7FFFL).toInt()
            val r1fcW = if (statsConfig.reg1fc != 0L) ((statsConfig.reg1fc and 0x3FFFL).toInt() + 1) else 0
            val r1fcH = if (statsConfig.reg1fc != 0L) (((statsConfig.reg1fc ushr 16) and 0x3FFFL).toInt() + 1) else 0
            val hwFullW = when {
                r1fcW >= afEndX && r1fcH >= afEndY && r1fcW >= 2 && r1fcH >= 2 -> r1fcW
                c1W >= afEndX && c1H >= afEndY && c1W >= 2 && c1H >= 2 -> c1W
                c0W >= afEndX && c0H >= afEndY && c0W >= 2 && c0H >= 2 -> c0W
                else -> maxOf(srcW, afEndX).coerceAtLeast(2)
            }
            val hwFullH = when {
                r1fcW >= afEndX && r1fcH >= afEndY && r1fcW >= 2 && r1fcH >= 2 -> r1fcH
                c1W >= afEndX && c1H >= afEndY && c1W >= 2 && c1H >= 2 -> c1H
                c0W >= afEndX && c0H >= afEndY && c0W >= 2 && c0H >= 2 -> c0H
                else -> maxOf(srcH, afEndY).coerceAtLeast(2)
            }
            val viewEndX = activeX0 + activeW
            val viewEndY = activeY0 + activeH
            afRoiX0 = (activeX0 + ((afX0.toDouble() / hwFullW) * activeW).toInt()).coerceIn(activeX0, (viewEndX - 2).coerceAtLeast(activeX0)) and 1.inv()
            afRoiY0 = (activeY0 + ((afY0.toDouble() / hwFullH) * activeH).toInt()).coerceIn(activeY0, (viewEndY - 2).coerceAtLeast(activeY0)) and 1.inv()
            afRoiX1 = (activeX0 + ((afEndX.toDouble() / hwFullW) * activeW).toInt()).coerceIn(afRoiX0 + 2, viewEndX)
            afRoiY1 = (activeY0 + ((afEndY.toDouble() / hwFullH) * activeH).toInt()).coerceIn(afRoiY0 + 2, viewEndY)
        }

        return LiveViewIspStatsRequest(
            viewX = activeX0,
            viewY = activeY0,
            viewW = activeW,
            viewH = activeH,
            gridCols = gridCols,
            gridRows = gridRows,
            stepQuad = stepQuad,
            awbHighlightThresh10 = 960,
            afRoiX0 = afRoiX0,
            afRoiY0 = afRoiY0,
            afRoiX1 = afRoiX1,
            afRoiY1 = afRoiY1,
            flags = VulkanIspStatsBridge.FLAG_ALL,
        )
    }

    private fun ingestFusedGpuStats(
        statsConfig: StatsConfig,
        statsReq: LiveViewIspStatsRequest,
        gpuMs: Double,
        viewRect: ResolvedCropRect,
    ) {
        val snapshot = VulkanIspStatsBridge.decodeOutputWords(
            raw = fusedStatsScratch,
            gridCols = statsReq.gridCols,
            gridRows = statsReq.gridRows,
            stepQuad = statsReq.stepQuad,
            gpuMs = gpuMs,
            usedGpu = true,
        )
        statsConfig.engine.ingestGpuStats(
            stats = snapshot,
            srcW = DEFAULT_SENSOR_WIDTH,
            srcH = DEFAULT_SENSOR_HEIGHT,
            reg5c = statsConfig.reg5c,
            reg60 = statsConfig.reg60,
            reg68 = statsConfig.reg68,
            reg6c = statsConfig.reg6c,
            obR = statsConfig.obR,
            obG1 = statsConfig.obG1,
            obB = statsConfig.obB,
            obG2 = statsConfig.obG2,
            reg124 = statsConfig.reg124,
            reg128 = statsConfig.reg128,
            reg134 = statsConfig.reg134,
            reg138 = statsConfig.reg138,
            commitHist = true,
        )
        lastAeRawGeneration = -1L
        lastStatsConfig = statsConfig
        lastStatsCropKey = viewRect.cacheKey
    }

    private fun buildFusedIspRequest(
        tick: Long,
        dstW: Int,
        dstH: Int,
        stride: Int,
        outputFormat: Int,
        lens: LensParameters?,
        peaking: MfAssistPeakingFilter.PeakingConfig,
        exposure: ExposureParams,
        colorPipeline: IspColorPipeline?,
        cropRect: ResolvedCropRect? = null,
        statsRequest: LiveViewIspStatsRequest? = null,
        outStatsWords: IntArray? = null,
    ): LiveViewIspFusionRequest {
        val hasCcm = colorPipeline?.hasCcm == true
        val hasYuvMat = colorPipeline?.hasYuvMatrix == true
        val monoMode = colorPipeline?.monochromeMode == true
        val activeCrop = cropRect?.takeIf { it.zoomed }
        var flags = 0
        if (hasCcm) flags = flags or LiveViewIspFusionRequest.FLAG_HAS_CCM
        if (hasYuvMat) flags = flags or LiveViewIspFusionRequest.FLAG_HAS_YUV_MAT
        if (monoMode) flags = flags or LiveViewIspFusionRequest.FLAG_MONO_MODE
        if (peaking.enabled) flags = flags or LiveViewIspFusionRequest.FLAG_PEAKING_ENABLED
        if (activeCrop != null) flags = flags or LiveViewIspFusionRequest.FLAG_SRC_CROP
        val hasTone = colorPipeline?.hasToneLut == true
        return LiveViewIspFusionRequest(
            exposure = Exposure(
                startTick = tick,
                durationNanos = exposure.durationNanos.coerceAtLeast(1L),
                iso = exposure.iso.coerceAtLeast(1),
                lens = lens ?: DEFAULT_LENS,
            ),
            dstWidth = dstW,
            dstHeight = dstH,
            dstStrideBytes = stride,
            outputFormat = outputFormat,
            flags = flags,
            offX = activeCrop?.cropX ?: 0,
            offY = activeCrop?.cropY ?: 0,
            fitW = activeCrop?.cropW ?: dstW,
            fitH = activeCrop?.cropH ?: dstH,
            gainR1024 = exposure.gainR1024,
            gainG1024 = exposure.gainG1024,
            gainB1024 = exposure.gainB1024,
            yuvShift = colorPipeline?.yuvShift ?: 10,
            ccm = if (hasCcm) colorPipeline.ccm else null,
            yuvMat = if (hasYuvMat) colorPipeline.yuv else null,
            lutR = if (hasTone) colorPipeline.toneLutR else null,
            lutG = if (hasTone) colorPipeline.toneLutG else null,
            lutB = if (hasTone) colorPipeline.toneLutB else null,
            peakingThreshold = peaking.effectiveThreshold,
            peakingHiY = peaking.highlightY,
            peakingHiCb = peaking.cb,
            peakingHiCr = peaking.cr,
            statsRequest = statsRequest,
            outStatsWords = outStatsWords,
        )
    }

    private fun pumpPackedUyvy(
        tick: Long,
        addr: Long,
        strideLong: Long,
        width: Int,
        height: Int,
        sensor: SensorPort,
        memory: DmaMemory,
        lens: LensParameters?,
        peaking: MfAssistPeakingFilter.PeakingConfig,
        exposure: ExposureParams,
        statsConfig: StatsConfig?,
        colorPipeline: IspColorPipeline?,
        yyuv422: Boolean = false,
        cropZoom: CropZoom = CropZoom.FULL_FRAME,
        consumePacked: ((ByteArray) -> Unit)? = null,
    ): Boolean {
        if (strideLong !in (width * 2L)..MAX_STRIDE_BYTES) return false
        val stride = strideLong.toInt()
        val rowBytes = width * 2
        val byteCountLong = (height - 1).toLong() * strideLong + rowBytes.toLong()
        if (byteCountLong !in 1L..MAX_FRAME_BYTES) return false
        val byteCount = byteCountLong.toInt()

        if (consumePacked == null) {
            try {
                memory.check(addr, byteCountLong, true)
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
        fun deliver(frame: ByteArray) {
            if (consumePacked == null) writeStrided2D(memory, addr, frame, rowBytes, stride, height)
            else consumePacked(frame)
        }

        // GPU-Resident Optics -> CMOS -> ISP Demosaic [+ GPU ISP Stats Reduction] Fusion fast path:
        // Runs Pass 0..6 in ONE single Vulkan command buffer (including 4:3 LCD/EVF aspect crop via
        // FLAG_SRC_CROP and 9,280 B AE/Hist/CDAF stats readback) with ZERO 1.2 MB Bayer host readback!
        if (statsConfig == null || sensor.supportsFusedIspStats()) {
            val baseViewRect = resolveCropRect(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, height, CropZoom.FULL_FRAME)
            val cropRect = if (cropZoom.isZoomed) resolveCropRect(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, height, cropZoom) else baseViewRect
            val cropKey = cropRect.cacheKey
            val gainSig = exposure.gainSignature xor (colorPipeline?.signature ?: 0L)
            val fusedExposure = Exposure(
                startTick = tick,
                durationNanos = exposure.durationNanos.coerceAtLeast(1L),
                iso = exposure.iso.coerceAtLeast(1),
                lens = lens ?: DEFAULT_LENS,
            )
            val fusedKey = sensor.fusedFrameStateKey(fusedExposure)
            val statsUpToDate = statsConfig == null || (
                fusedKey >= 0L &&
                    lastAeFusedKey == fusedKey &&
                    lastStatsConfig == statsConfig &&
                    lastStatsCropKey == baseViewRect.cacheKey &&
                    statsConfig.engine.aeFramesUpdated > 0L &&
                    statsConfig.engine.histFramesUpdated > 0L
                )
            if (fusedKey >= 0L && statsUpToDate) {
                val fusedHit = findCachedFusedSlot(
                    fusedKey = fusedKey,
                    gainSig = gainSig,
                    dstW = width,
                    dstH = height,
                    stride = stride,
                    isSemiPlanar = false,
                    isNv12 = false,
                    isYyuv422 = yyuv422,
                    peakingCacheKey = peaking.cacheKey,
                    cropCacheKey = cropKey,
                    yByteCount = byteCount,
                    uvByteCount = 0,
                )
                if (fusedHit != null) {
                    cacheHits++
                    deliver(checkNotNull(fusedHit.yuvBytes))
                    return true
                }
            }
            val slot = selectSlotForFill(width, height, stride, false, false, yyuv422, cropKey)
            val frameBytes = slot.yuvBytes?.takeIf { it.size >= byteCount }
                ?: ByteArray(byteCount).also { slot.yuvBytes = it }
            val statsReq = if (statsUpToDate) {
                null
            } else {
                buildFusedStatsRequest(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, statsConfig, baseViewRect)
            }
            val fusedReq = buildFusedIspRequest(
                tick = tick,
                dstW = width,
                dstH = height,
                stride = stride,
                outputFormat = if (yyuv422) LiveViewIspFusionRequest.FMT_YYUV422 else LiveViewIspFusionRequest.FMT_UYVY422,
                lens = lens,
                peaking = peaking,
                exposure = exposure,
                colorPipeline = colorPipeline,
                cropRect = cropRect,
                statsRequest = statsReq,
                outStatsWords = if (statsReq != null) fusedStatsScratch else null,
            )
            if (sensor.tryExposeFusedIspYuv(fusedReq, frameBytes, null) && (statsReq == null || fusedReq.statsProduced)) {
                gpuFusedIspFrames++
                cacheMisses++
                rawGeneration++
                if (statsConfig != null && statsReq != null) {
                    ingestFusedGpuStats(statsConfig, statsReq, fusedReq.statsGpuMs, baseViewRect)
                    lastAeFusedKey = fusedKey
                }
                slot.rawGeneration = rawGeneration
                slot.gainSignature = gainSig
                slot.srcW = DEFAULT_SENSOR_WIDTH
                slot.srcH = DEFAULT_SENSOR_HEIGHT
                slot.is16Bit = true
                slot.dstW = width
                slot.dstH = height
                slot.stride = stride
                slot.isSemiPlanar = false
                slot.isNv12 = false
                slot.isYyuv422 = yyuv422
                slot.peakingCacheKey = peaking.cacheKey
                slot.cropCacheKey = cropKey
                slot.fusedStateKey = fusedKey
                slot.lastUsedSeq = ++slotAccessSeq
                deliver(frameBytes)
                return true
            }
        }

        if (!exposeSensor(tick, width, height, sensor, lens, exposure)) return false
        val rawBytes = rawSensorScratch
        val exposedBytes = lastExposedBytes
        val srcW = lastExposedSrcW
        val srcH = lastExposedSrcH
        val is16Bit = lastExposedIs16Bit

        val activeRaw = updateRawGeneration(sensor, rawBytes, exposedBytes, srcW, srcH, is16Bit)
        val baseViewRect = resolveCropRect(srcW, srcH, width, height, CropZoom.FULL_FRAME)
        maybeUpdateAeStats(activeRaw, srcW, srcH, is16Bit, statsConfig, baseViewRect)
        val cropRect = if (cropZoom.isZoomed) resolveCropRect(srcW, srcH, width, height, cropZoom) else baseViewRect
        val cropKey = cropRect.cacheKey
        val gainSig = exposure.gainSignature xor (colorPipeline?.signature ?: 0L)
        val hit = findCachedSlot(gainSig, srcW, srcH, is16Bit, width, height, stride, false, false, yyuv422, peaking.cacheKey, cropKey, byteCount, 0)
        if (hit != null && (statsConfig == null || statsConfig.engine.histFramesUpdated > 0L)) {
            cacheHits++
            deliver(checkNotNull(hit.yuvBytes))
            return true
        }

        cacheMisses++
        // True zero-copy direct demosaic into guest RAM on streaming dynamic scenes where consecutive
        // sensor frames change (skips intermediate ByteArray write + post-demosaic copy completely).
        if (consumePacked == null && consecutiveRawChanges >= 2 && memory is DirectGuestRamPort && allowNativeSimd && NativeIspSimdBridge.isAvailable) {
            val directDst = memory.sliceDirect(addr, byteCountLong, write = true)
            if (directDst != null && tryNativeIspPipelineDirect(
                    raw = activeRaw,
                    srcW = srcW,
                    srcH = srcH,
                    is16Bit = is16Bit,
                    yOrUyvyDst = directDst,
                    uvDst = MemorySegment.NULL,
                    dstW = width,
                    dstH = height,
                    stride = stride,
                    isSemiPlanar = false,
                    isNv12 = false,
                    exposure = exposure,
                    statsEngine = statsConfig?.engine,
                    colorPipeline = colorPipeline,
                    peaking = peaking,
                    cropRect = cropRect,
                    yyuv422 = yyuv422,
                ) == NATIVE_ISP_DONE_COMPLETE
            ) {
                memory.notifyDirectWrite(addr, byteCountLong)
                directRamFramesWritten++
                return true
            }
        }
        val slot = selectSlotForFill(width, height, stride, false, false, yyuv422, cropKey)
        val frameBytes = slot.yuvBytes?.takeIf { it.size >= byteCount }
            ?: ByteArray(byteCount).also { slot.yuvBytes = it }

        val nativeStatus = tryNativeIspPipeline(
            raw = activeRaw,
            srcW = srcW,
            srcH = srcH,
            is16Bit = is16Bit,
            yOrUyvyDst = frameBytes,
            uvDst = null,
            dstW = width,
            dstH = height,
            stride = stride,
            isSemiPlanar = false,
            isNv12 = false,
            exposure = exposure,
            statsEngine = statsConfig?.engine,
            colorPipeline = colorPipeline,
            peaking = peaking,
            cropRect = cropRect,
            yyuv422 = yyuv422,
        )
        if (nativeStatus == NATIVE_ISP_NONE) {
            demosaicBayerToUyvy(activeRaw, srcW, srcH, is16Bit, frameBytes, width, height, stride, exposure, statsConfig?.engine, colorPipeline, cropRect)
            if (peaking.enabled) {
                MfAssistPeakingFilter.applyPackedUyvy(frameBytes, width, height, stride, peaking)
            }
        }
        if (yyuv422 && nativeStatus != NATIVE_ISP_DONE_COMPLETE) {
            // DAT s5:0x025a7658 / 0x025a7770..94: LV Ch0 (0xFFF72000) packed Face/Eye-AF ring
            // uses YYUV422 [Y0, Y1, Cb, Cr] (luma at (x>>1)*4 + (x&1)), swizzled from [U, Y0, V, Y1].
            for (y in 0 until height) {
                val rowOff = y * stride
                var off = rowOff
                val end = rowOff + rowBytes
                while (off + 3 < end) {
                    val u = frameBytes[off]
                    val y0 = frameBytes[off + 1]
                    val v = frameBytes[off + 2]
                    val y1 = frameBytes[off + 3]
                    frameBytes[off] = y0
                    frameBytes[off + 1] = y1
                    frameBytes[off + 2] = u
                    frameBytes[off + 3] = v
                    off += 4
                }
            }
        }
        slot.rawGeneration = rawGeneration
        slot.gainSignature = gainSig
        slot.srcW = srcW
        slot.srcH = srcH
        slot.is16Bit = is16Bit
        slot.dstW = width
        slot.dstH = height
        slot.stride = stride
        slot.isSemiPlanar = false
        slot.isNv12 = false
        slot.isYyuv422 = yyuv422
        slot.peakingCacheKey = peaking.cacheKey
        slot.cropCacheKey = cropKey
        slot.fusedStateKey = -1L
        slot.lastUsedSeq = ++slotAccessSeq

        deliver(frameBytes)
        return true
    }

    /**
     * Renders the active optically-filtered CMOS frame into a STILL capture UYVY422 DMA buffer
     * (e.g. `RECC_S` `0xFFF82000` output buffer at `0xFFF82070/74`).
     *
     * Demosaics the native sensor frame once into a compact UYVY source image and scales rows in
     * bounded strips so even full-resolution 100MP capture buffers complete rapidly without large
     * intermediate allocations.
     */
    fun renderOpticalUyvyToDma(
        tick: Long,
        addr: Long,
        width: Int,
        height: Int,
        stride: Int,
        sensor: SensorPort?,
        memory: DmaMemory?,
        lens: LensParameters?,
        exposure: ExposureParams = DEFAULT_EXPOSURE_PARAMS,
        colorPipeline: IspColorPipeline? = null,
        cropFactor: Double = 1.0,
        stillReadout: IspStillReadout? = null,
        firstRow: Int = 0,
        frameHeight: Int = height,
        yyuv422: Boolean = true,
    ): Boolean {
        if ((sensor == null && stillReadout == null) || memory == null) return false
        if (addr == 0L || width < 2 || (width and 1) != 0 || height < 1 || stride < width * 2) return false
        if (firstRow < 0 || frameHeight < height || firstRow > frameHeight - height) return false
        val stripAddr = addr + firstRow.toLong() * stride
        val byteCountLong = (height - 1).toLong() * stride.toLong() + width.toLong() * 2L
        if (byteCountLong !in 1L..MAX_STILL_FRAME_BYTES || stripAddr !in 0L..((1L shl 34) - byteCountLong)) return false
        try {
            memory.check(stripAddr, byteCountLong, true)
        } catch (_: IllegalArgumentException) {
            return false
        }

        // Fast GPU-resident fused still path (Optics -> CMOS Bayer -> ISP Demosaic + Aspect/35mm Crop -> packed 4:2:2).
        // Skipped when a latched IspStillReadout is active so 102MP YC, RAW, and JPEG stay 100% consistent.
        val fusedOutFormat = if (yyuv422) LiveViewIspFusionRequest.FMT_YYUV422 else LiveViewIspFusionRequest.FMT_UYVY422
        if (stillReadout == null && sensor != null) {
            val defaultCrop = computeStillCrop(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, frameHeight, cropFactor)
            val fusedBaseReq = buildFusedIspRequest(
                tick = tick,
                dstW = width,
                dstH = height,
                stride = stride,
                outputFormat = fusedOutFormat,
                lens = lens,
                peaking = MfAssistPeakingFilter.DISABLED,
                exposure = exposure,
                colorPipeline = colorPipeline,
            )
            val directStillLimitBytes = 16L * 1024L * 1024L
            if (height == frameHeight && height >= 2 && byteCountLong <= directStillLimitBytes && height.toLong() * stride.toLong() <= directStillLimitBytes) {
                val directReq = LiveViewIspFusionRequest(
                    exposure = fusedBaseReq.exposure,
                    dstWidth = width,
                    dstHeight = height,
                    dstStrideBytes = stride,
                    outputFormat = fusedOutFormat,
                    flags = fusedBaseReq.flags or LiveViewIspFusionRequest.FLAG_SRC_CROP,
                    offX = defaultCrop[0],
                    offY = defaultCrop[1],
                    fitW = defaultCrop[2],
                    fitH = defaultCrop[3],
                    gainR1024 = fusedBaseReq.gainR1024,
                    gainG1024 = fusedBaseReq.gainG1024,
                    gainB1024 = fusedBaseReq.gainB1024,
                    yuvShift = fusedBaseReq.yuvShift,
                    ccm = fusedBaseReq.ccm,
                    yuvMat = fusedBaseReq.yuvMat,
                    lutR = fusedBaseReq.lutR,
                    lutG = fusedBaseReq.lutG,
                    lutB = fusedBaseReq.lutB,
                )
                val gpuUyvy = ByteArray(height * stride)
                if (sensor.tryExposeStillUyvy(directReq, gpuUyvy)) {
                    writeStrided2D(memory, addr, gpuUyvy, width * 2, stride, height)
                    stillFusedFrames++
                    return true
                }
            } else if (defaultCrop[2] >= 2 && defaultCrop[3] >= 2) {
                // Large still buffer (e.g. 102MP 11648x8736): fuse Optics + Bayer + ISP Demosaic + Crop at native
                // cropped sensor resolution on GPU (< 1.2 MiB), then stream-upscale in bounded strips to guest RAM.
                val cropW = defaultCrop[2]
                val cropH = defaultCrop[3]
                val cropStride = cropW * 2
                val cropReq = LiveViewIspFusionRequest(
                    exposure = fusedBaseReq.exposure,
                    dstWidth = cropW,
                    dstHeight = cropH,
                    dstStrideBytes = cropStride,
                    outputFormat = fusedOutFormat,
                    flags = fusedBaseReq.flags or LiveViewIspFusionRequest.FLAG_SRC_CROP,
                    offX = defaultCrop[0],
                    offY = defaultCrop[1],
                    fitW = cropW,
                    fitH = cropH,
                    gainR1024 = fusedBaseReq.gainR1024,
                    gainG1024 = fusedBaseReq.gainG1024,
                    gainB1024 = fusedBaseReq.gainB1024,
                    yuvShift = fusedBaseReq.yuvShift,
                    ccm = fusedBaseReq.ccm,
                    yuvMat = fusedBaseReq.yuvMat,
                    lutR = fusedBaseReq.lutR,
                    lutG = fusedBaseReq.lutG,
                    lutB = fusedBaseReq.lutB,
                )
                val croppedUyvy = ByteArray(cropH * cropStride)
                if (sensor.tryExposeStillUyvy(cropReq, croppedUyvy)) {
                    upscaleUyvyToDma(
                        srcUyvy = croppedUyvy,
                        srcW = cropW,
                        srcH = cropH,
                        srcStride = cropStride,
                        cropOffX = 0,
                        cropOffY = 0,
                        cropW = cropW,
                        cropH = cropH,
                        width = width,
                        height = height,
                        stride = stride,
                        memory = memory,
                        addr = stripAddr,
                        firstRow = firstRow,
                        frameHeight = frameHeight,
                    )
                    stillFusedFrames++
                    return true
                }
            }
        }

        val effectiveExposure = if (stillReadout != null) {
            exposure.copy(
                gainR1024 = stillReadout.gainR1024,
                gainG1024 = stillReadout.gainG1024,
                gainB1024 = stillReadout.gainB1024,
            )
        } else {
            exposure
        }
        val colorSignature = colorPipeline?.signature ?: 0L
        val bayerFingerprint = if (stillReadout != null) fingerprintBayerBuffer(stillReadout.bayerSamples()) else 0L
        val cachedStrip = if (stillReadout != null) {
            stillStripCache?.takeIf {
                it.tick == stillReadout.tick &&
                    it.width == stillReadout.width &&
                    it.height == stillReadout.height &&
                    it.gainR1024 == effectiveExposure.gainR1024 &&
                    it.gainG1024 == effectiveExposure.gainG1024 &&
                    it.gainB1024 == effectiveExposure.gainB1024 &&
                    it.durationNanos == effectiveExposure.durationNanos &&
                    it.iso == effectiveExposure.iso &&
                    it.colorSignature == colorSignature &&
                    it.bayerFingerprint == bayerFingerprint &&
                    it.isYyuv422 == yyuv422
            }
        } else null

        val srcW: Int
        val srcH: Int
        val srcStride: Int
        val srcUyvy: ByteArray
        if (cachedStrip != null) {
            stillStripCacheHits++
            srcW = cachedStrip.srcW
            srcH = cachedStrip.srcH
            srcStride = cachedStrip.srcStride
            srcUyvy = cachedStrip.srcUyvy
        } else {
            val rawCapacity = maxOf(
                DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2,
                stillReadout?.let { it.width * it.height * 2 } ?: 0,
            )
            val rawBytes = rawSensorScratch?.takeIf { it.size >= rawCapacity }
                ?: ByteArray(rawCapacity).also { rawSensorScratch = it }
            val rawBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val exposedBytes = try {
                stillReadout?.copyBayerTo(rawBuf) ?: checkNotNull(sensor).exposeStill(
                    Exposure(
                        tick,
                        exposure.durationNanos.coerceAtLeast(1L),
                        exposure.iso.coerceAtLeast(1),
                        lens ?: DEFAULT_LENS,
                    ),
                    rawBuf,
                )
            } catch (_: IllegalStateException) {
                return false
            }
            if (exposedBytes <= 0) return false

            val dims = when {
                stillReadout != null && exposedBytes == stillReadout.width * stillReadout.height * 2 ->
                    Triple(stillReadout.width, stillReadout.height, true)
                stillReadout != null && exposedBytes == stillReadout.width * stillReadout.height ->
                    Triple(stillReadout.width, stillReadout.height, false)
                exposedBytes == DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2 ->
                    Triple(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, true)
                exposedBytes == DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT ->
                    Triple(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, false)
                exposedBytes == width * height * 2 -> Triple(width, height, true)
                exposedBytes == width * height -> Triple(width, height, false)
                else -> return false
            }
            srcW = dims.first
            srcH = dims.second
            val is16Bit = dims.third
            srcStride = srcW * 2
            srcUyvy = ByteArray(srcH * srcStride)
            val nativeStillStatus = tryNativeIspPipeline(
                raw = rawBytes,
                srcW = srcW,
                srcH = srcH,
                is16Bit = is16Bit,
                yOrUyvyDst = srcUyvy,
                uvDst = null,
                dstW = srcW,
                dstH = srcH,
                stride = srcStride,
                isSemiPlanar = false,
                isNv12 = false,
                exposure = effectiveExposure,
                statsEngine = null,
                colorPipeline = colorPipeline,
                peaking = MfAssistPeakingFilter.DISABLED,
                yyuv422 = yyuv422,
            )
            if (nativeStillStatus == NATIVE_ISP_NONE) {
                demosaicBayerToUyvy(
                    raw = rawBytes,
                    srcW = srcW,
                    srcH = srcH,
                    is16Bit = is16Bit,
                    dst = srcUyvy,
                    dstW = srcW,
                    dstH = srcH,
                    stride = srcStride,
                    exposure = effectiveExposure,
                    colorPipeline = colorPipeline,
                )
                if (yyuv422) {
                    var off = 0
                    val end = srcUyvy.size - 3
                    while (off <= end) {
                        val u = srcUyvy[off]
                        val y0 = srcUyvy[off + 1]
                        val v = srcUyvy[off + 2]
                        val y1 = srcUyvy[off + 3]
                        srcUyvy[off] = y0
                        srcUyvy[off + 1] = y1
                        srcUyvy[off + 2] = u
                        srcUyvy[off + 3] = v
                        off += 4
                    }
                }
            }
        }
        // Cache demosaiced packed 4:2:2 across multi-strip RECC_S passes of the same still readout,
        // and release immediately on the final strip so 0 bytes stay pinned after capture.
        if (stillReadout != null && firstRow + height < frameHeight) {
            stillStripCache = StillStripCache(
                tick = stillReadout.tick,
                width = stillReadout.width,
                height = stillReadout.height,
                gainR1024 = effectiveExposure.gainR1024,
                gainG1024 = effectiveExposure.gainG1024,
                gainB1024 = effectiveExposure.gainB1024,
                durationNanos = effectiveExposure.durationNanos,
                iso = effectiveExposure.iso,
                colorSignature = colorSignature,
                bayerFingerprint = bayerFingerprint,
                isYyuv422 = yyuv422,
                srcW = srcW,
                srcH = srcH,
                srcStride = srcStride,
                srcUyvy = srcUyvy,
            )
        } else {
            stillStripCache = null
        }

        // Aspect-preserving center crop matching output aspect ratio (zero anamorphic stretch),
        // with optional 35mm format center-crop magnification when cropFactor > 1.0 (BUG-V3-2-3 / BUG-V3-2-5).
        val (cropOffX, cropOffY, cropW, cropH) = computeStillCrop(srcW, srcH, width, frameHeight, cropFactor)
        upscaleUyvyToDma(
            srcUyvy = srcUyvy,
            srcW = srcW,
            srcH = srcH,
            srcStride = srcStride,
            cropOffX = cropOffX,
            cropOffY = cropOffY,
            cropW = cropW,
            cropH = cropH,
            width = width,
            height = height,
            stride = stride,
            memory = memory,
            addr = stripAddr,
            firstRow = firstRow,
            frameHeight = frameHeight,
        )
        return true
    }

    private fun fingerprintBayerBuffer(buf: ByteBuffer): Long {
        val len = buf.remaining()
        val pos = buf.position()
        var h = 0xcbf29ce484222325uL.toLong() xor len.toLong()
        if (len > 0) {
            val samples = minOf(len, 16)
            val step = maxOf(1, len / samples)
            var i = 0
            while (i < len) {
                h = (h xor (buf.get(pos + i).toLong() and 0xFFL)) * 0x100000001b3L
                i += step
            }
            h = (h xor (buf.get(pos + len - 1).toLong() and 0xFFL)) * 0x100000001b3L
        }
        return h
    }

    /**
     * Upscales a cropped UYVY422 source region to `(width, height)` with row pitch [stride]
     * and writes directly to guest RAM. Deduplicates consecutive destination rows sharing the
     * same source row `sy` (e.g. ~15x fewer row expansions for 102MP 11664x8764) and uses
     * zero-copy `MemorySegment.copy` when [memory] implements [DirectGuestRamPort].
     */
    private fun upscaleUyvyToDma(
        srcUyvy: ByteArray,
        srcW: Int,
        srcH: Int,
        srcStride: Int,
        cropOffX: Int,
        cropOffY: Int,
        cropW: Int,
        cropH: Int,
        width: Int,
        height: Int,
        stride: Int,
        memory: DmaMemory,
        addr: Long,
        firstRow: Int,
        frameHeight: Int,
    ) {
        val pairCount = width ushr 1
        val cropPairCount = (cropW ushr 1).coerceAtLeast(1)
        val cropPairOff = cropOffX ushr 1
        val maxSrcPair = ((srcW ushr 1) - 1).coerceAtLeast(0)
        val xPairMap = IntArray(pairCount) { px ->
            val sp = (cropPairOff + ((px.toLong() * cropPairCount) / pairCount).toInt()).coerceIn(0, maxSrcPair)
            sp shl 2
        }

        val rowBuf = ByteArray(stride)
        val srcSeg = MemorySegment.ofArray(srcUyvy)
        val rowSeg = MemorySegment.ofArray(rowBuf)
        val activeRowBytes = width * 2
        val totalBytes = (height - 1).toLong() * stride.toLong() + activeRowBytes.toLong()

        fun expandSourceRow(sy: Int) {
            val srcRowOff = sy.toLong() * srcStride.toLong()
            var p = 0
            while (p < pairCount) {
                val word = srcSeg.get(ValueLayout.JAVA_INT_UNALIGNED, srcRowOff + xPairMap[p].toLong())
                rowSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (p shl 2).toLong(), word)
                p++
            }
        }

        val directPort = memory as? DirectGuestRamPort
        val directDst = directPort?.sliceDirect(addr, totalBytes, write = true)
        if (directPort != null && directDst != null) {
            var lastSy = -1
            var dstOffset = 0L
            val lastRowIdx = height - 1
            for (dstY in 0 until height) {
                val sy = (cropOffY + (((firstRow + dstY).toLong() * cropH) / frameHeight).toInt()).coerceIn(0, srcH - 1)
                if (sy != lastSy) {
                    expandSourceRow(sy)
                    lastSy = sy
                }
                val copyBytes = if (dstY == lastRowIdx) activeRowBytes.toLong() else stride.toLong()
                MemorySegment.copy(rowSeg, 0L, directDst, dstOffset, copyBytes)
                dstOffset += stride.toLong()
            }
            directPort.notifyDirectWrite(addr, totalBytes)
            return
        }

        val maxStripRows = (STILL_STRIP_MAX_BYTES / stride).coerceIn(1, height)
        val stripBuf = ByteArray(maxStripRows * stride)
        var lastSy = -1
        var y = 0
        while (y < height) {
            val rows = minOf(maxStripRows, height - y)
            for (r in 0 until rows) {
                val dstY = y + r
                val sy = (cropOffY + (((firstRow + dstY).toLong() * cropH) / frameHeight).toInt()).coerceIn(0, srcH - 1)
                if (sy != lastSy) {
                    expandSourceRow(sy)
                    lastSy = sy
                }
                System.arraycopy(rowBuf, 0, stripBuf, r * stride, stride)
            }
            val chunkBytes = if (y + rows == height) {
                (rows - 1) * stride + activeRowBytes
            } else {
                rows * stride
            }
            memory.write(addr + y.toLong() * stride.toLong(), ByteBuffer.wrap(stripBuf, 0, chunkBytes))
            y += rows
        }
    }

    private fun pumpSemiPlanarYuv(
        tick: Long,
        yAddr: Long,
        uvAddr: Long,
        strideLong: Long,
        width: Int,
        height: Int,
        ctrl: Long,
        sensor: SensorPort,
        memory: DmaMemory,
        lens: LensParameters?,
        peaking: MfAssistPeakingFilter.PeakingConfig,
        exposure: ExposureParams,
        statsConfig: StatsConfig?,
        colorPipeline: IspColorPipeline?,
        cropZoom: CropZoom = CropZoom.FULL_FRAME,
    ): Boolean {
        if (strideLong !in width.toLong()..MAX_STRIDE_BYTES) return false
        val stride = strideLong.toInt()
        val subsamplingMode = ((ctrl ushr 20) and 0x3L).toInt()
        val isNv12 = subsamplingMode == 1 && height >= 2 && height % 2 == 0
        val uvHeight = if (isNv12) height / 2 else height

        val yByteCountLong = (height - 1).toLong() * strideLong + width.toLong()
        val uvByteCountLong = (uvHeight - 1).toLong() * strideLong + width.toLong()
        if (yByteCountLong !in 1L..MAX_FRAME_BYTES || uvByteCountLong !in 1L..MAX_FRAME_BYTES) return false
        val yByteCount = yByteCountLong.toInt()
        val uvByteCount = uvByteCountLong.toInt()

        try {
            memory.check(yAddr, yByteCountLong, true)
            memory.check(uvAddr, uvByteCountLong, true)
        } catch (_: IllegalArgumentException) {
            return false
        }

        // GPU-Resident Optics -> CMOS -> ISP Demosaic [+ GPU ISP Stats Reduction] Fusion fast path (NV16/NV12)
        if (statsConfig == null || sensor.supportsFusedIspStats()) {
            val baseViewRect = resolveCropRect(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, height, CropZoom.FULL_FRAME)
            val cropRect = if (cropZoom.isZoomed) resolveCropRect(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, width, height, cropZoom) else baseViewRect
            val cropKey = cropRect.cacheKey
            val gainSig = exposure.gainSignature xor (colorPipeline?.signature ?: 0L)
            val fusedExposure = Exposure(
                startTick = tick,
                durationNanos = exposure.durationNanos.coerceAtLeast(1L),
                iso = exposure.iso.coerceAtLeast(1),
                lens = lens ?: DEFAULT_LENS,
            )
            val fusedKey = sensor.fusedFrameStateKey(fusedExposure)
            val statsUpToDate = statsConfig == null || (
                fusedKey >= 0L &&
                    lastAeFusedKey == fusedKey &&
                    lastStatsConfig == statsConfig &&
                    lastStatsCropKey == baseViewRect.cacheKey &&
                    statsConfig.engine.aeFramesUpdated > 0L &&
                    statsConfig.engine.histFramesUpdated > 0L
                )
            if (fusedKey >= 0L && statsUpToDate) {
                val fusedHit = findCachedFusedSlot(
                    fusedKey = fusedKey,
                    gainSig = gainSig,
                    dstW = width,
                    dstH = height,
                    stride = stride,
                    isSemiPlanar = true,
                    isNv12 = isNv12,
                    isYyuv422 = false,
                    peakingCacheKey = peaking.cacheKey,
                    cropCacheKey = cropKey,
                    yByteCount = yByteCount,
                    uvByteCount = uvByteCount,
                )
                if (fusedHit != null) {
                    cacheHits++
                    writeStrided2D(memory, yAddr, checkNotNull(fusedHit.yuvBytes), width, stride, height)
                    writeStrided2D(memory, uvAddr, checkNotNull(fusedHit.uvBytes), width, stride, uvHeight)
                    return true
                }
            }
            val slot = selectSlotForFill(width, height, stride, true, isNv12, false, cropKey)
            val yBytes = slot.yuvBytes?.takeIf { it.size >= yByteCount }
                ?: ByteArray(yByteCount).also { slot.yuvBytes = it }
            val uvBytes = slot.uvBytes?.takeIf { it.size >= uvByteCount }
                ?: ByteArray(uvByteCount).also { slot.uvBytes = it }
            val statsReq = if (statsUpToDate) {
                null
            } else {
                buildFusedStatsRequest(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, statsConfig, baseViewRect)
            }
            val fusedReq = buildFusedIspRequest(
                tick = tick,
                dstW = width,
                dstH = height,
                stride = stride,
                outputFormat = if (isNv12) LiveViewIspFusionRequest.FMT_NV12 else LiveViewIspFusionRequest.FMT_NV16,
                lens = lens,
                peaking = peaking,
                exposure = exposure,
                colorPipeline = colorPipeline,
                cropRect = cropRect,
                statsRequest = statsReq,
                outStatsWords = if (statsReq != null) fusedStatsScratch else null,
            )
            if (sensor.tryExposeFusedIspYuv(fusedReq, yBytes, uvBytes) && (statsReq == null || fusedReq.statsProduced)) {
                gpuFusedIspFrames++
                cacheMisses++
                rawGeneration++
                if (statsConfig != null && statsReq != null) {
                    ingestFusedGpuStats(statsConfig, statsReq, fusedReq.statsGpuMs, baseViewRect)
                    lastAeFusedKey = fusedKey
                }
                slot.rawGeneration = rawGeneration
                slot.gainSignature = gainSig
                slot.srcW = DEFAULT_SENSOR_WIDTH
                slot.srcH = DEFAULT_SENSOR_HEIGHT
                slot.is16Bit = true
                slot.dstW = width
                slot.dstH = height
                slot.stride = stride
                slot.isSemiPlanar = true
                slot.isNv12 = isNv12
                slot.isYyuv422 = false
                slot.peakingCacheKey = peaking.cacheKey
                slot.cropCacheKey = cropKey
                slot.fusedStateKey = fusedKey
                slot.lastUsedSeq = ++slotAccessSeq
                writeStrided2D(memory, yAddr, yBytes, width, stride, height)
                writeStrided2D(memory, uvAddr, uvBytes, width, stride, uvHeight)
                return true
            }
        }

        if (!exposeSensor(tick, width, height, sensor, lens, exposure)) return false
        val rawBytes = rawSensorScratch
        val exposedBytes = lastExposedBytes
        val srcW = lastExposedSrcW
        val srcH = lastExposedSrcH
        val is16Bit = lastExposedIs16Bit

        val activeRaw = updateRawGeneration(sensor, rawBytes, exposedBytes, srcW, srcH, is16Bit)
        val baseViewRect = resolveCropRect(srcW, srcH, width, height, CropZoom.FULL_FRAME)
        maybeUpdateAeStats(activeRaw, srcW, srcH, is16Bit, statsConfig, baseViewRect)
        val cropRect = if (cropZoom.isZoomed) resolveCropRect(srcW, srcH, width, height, cropZoom) else baseViewRect
        val cropKey = cropRect.cacheKey
        val gainSig = exposure.gainSignature xor (colorPipeline?.signature ?: 0L)
        val hit = findCachedSlot(gainSig, srcW, srcH, is16Bit, width, height, stride, true, isNv12, false, peaking.cacheKey, cropKey, yByteCount, uvByteCount)
        if (hit != null && (statsConfig == null || statsConfig.engine.histFramesUpdated > 0L)) {
            cacheHits++
            writeStrided2D(memory, yAddr, checkNotNull(hit.yuvBytes), width, stride, height)
            writeStrided2D(memory, uvAddr, checkNotNull(hit.uvBytes), width, stride, uvHeight)
            return true
        }

        cacheMisses++
        if (consecutiveRawChanges >= 2 && memory is DirectGuestRamPort && allowNativeSimd && NativeIspSimdBridge.isAvailable) {
            val directY = memory.sliceDirect(yAddr, yByteCountLong, write = true)
            val directUv = memory.sliceDirect(uvAddr, uvByteCountLong, write = true)
            if (directY != null && directUv != null && tryNativeIspPipelineDirect(
                    raw = activeRaw,
                    srcW = srcW,
                    srcH = srcH,
                    is16Bit = is16Bit,
                    yOrUyvyDst = directY,
                    uvDst = directUv,
                    dstW = width,
                    dstH = height,
                    stride = stride,
                    isSemiPlanar = true,
                    isNv12 = isNv12,
                    exposure = exposure,
                    statsEngine = statsConfig?.engine,
                    colorPipeline = colorPipeline,
                    peaking = peaking,
                    cropRect = cropRect,
                ) == NATIVE_ISP_DONE_COMPLETE
            ) {
                memory.notifyDirectWrite(yAddr, yByteCountLong)
                memory.notifyDirectWrite(uvAddr, uvByteCountLong)
                directRamFramesWritten++
                return true
            }
        }
        val slot = selectSlotForFill(width, height, stride, true, isNv12, false, cropKey)
        val yBytes = slot.yuvBytes?.takeIf { it.size >= yByteCount }
            ?: ByteArray(yByteCount).also { slot.yuvBytes = it }
        val uvBytes = slot.uvBytes?.takeIf { it.size >= uvByteCount }
            ?: ByteArray(uvByteCount).also { slot.uvBytes = it }

        if (tryNativeIspPipeline(
                raw = activeRaw,
                srcW = srcW,
                srcH = srcH,
                is16Bit = is16Bit,
                yOrUyvyDst = yBytes,
                uvDst = uvBytes,
                dstW = width,
                dstH = height,
                stride = stride,
                isSemiPlanar = true,
                isNv12 = isNv12,
                exposure = exposure,
                statsEngine = statsConfig?.engine,
                colorPipeline = colorPipeline,
                peaking = peaking,
                cropRect = cropRect,
            ) == NATIVE_ISP_NONE
        ) {
            demosaicBayerToSemiPlanarYuv(
                raw = activeRaw,
                srcW = srcW,
                srcH = srcH,
                is16Bit = is16Bit,
                yDst = yBytes,
                uvDst = uvBytes,
                dstW = width,
                dstH = height,
                stride = stride,
                nv12 = isNv12,
                exposure = exposure,
                statsEngine = statsConfig?.engine,
                colorPipeline = colorPipeline,
                cropRect = cropRect,
            )
            if (peaking.enabled) {
                MfAssistPeakingFilter.applySemiPlanar(yBytes, uvBytes, width, height, stride, isNv12, peaking)
            }
        }
        slot.rawGeneration = rawGeneration
        slot.gainSignature = gainSig
        slot.srcW = srcW
        slot.srcH = srcH
        slot.is16Bit = is16Bit
        slot.dstW = width
        slot.dstH = height
        slot.stride = stride
        slot.isSemiPlanar = true
        slot.isNv12 = isNv12
        slot.isYyuv422 = false
        slot.peakingCacheKey = peaking.cacheKey
        slot.cropCacheKey = cropKey
        slot.fusedStateKey = -1L
        slot.lastUsedSeq = ++slotAccessSeq

        writeStrided2D(memory, yAddr, yBytes, width, stride, height)
        writeStrided2D(memory, uvAddr, uvBytes, width, stride, uvHeight)
        return true
    }

    private fun tryNativeIspPipeline(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        yOrUyvyDst: ByteArray,
        uvDst: ByteArray?,
        dstW: Int,
        dstH: Int,
        stride: Int,
        isSemiPlanar: Boolean,
        isNv12: Boolean,
        exposure: ExposureParams,
        statsEngine: IspStatsSramEngine?,
        colorPipeline: IspColorPipeline?,
        peaking: MfAssistPeakingFilter.PeakingConfig,
        cropRect: ResolvedCropRect = resolveCropRect(srcW, srcH, dstW, dstH, CropZoom.FULL_FRAME),
        yyuv422: Boolean = false,
    ): Int = tryNativeIspPipelineDirect(
        raw = raw,
        srcW = srcW,
        srcH = srcH,
        is16Bit = is16Bit,
        yOrUyvyDst = MemorySegment.ofArray(yOrUyvyDst),
        uvDst = if (uvDst != null) MemorySegment.ofArray(uvDst) else MemorySegment.NULL,
        dstW = dstW,
        dstH = dstH,
        stride = stride,
        isSemiPlanar = isSemiPlanar,
        isNv12 = isNv12,
        exposure = exposure,
        statsEngine = statsEngine,
        colorPipeline = colorPipeline,
        peaking = peaking,
        cropRect = cropRect,
        yyuv422 = yyuv422,
    )

    private fun tryNativeIspPipelineDirect(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        yOrUyvyDst: MemorySegment,
        uvDst: MemorySegment,
        dstW: Int,
        dstH: Int,
        stride: Int,
        isSemiPlanar: Boolean,
        isNv12: Boolean,
        exposure: ExposureParams,
        statsEngine: IspStatsSramEngine?,
        colorPipeline: IspColorPipeline?,
        peaking: MfAssistPeakingFilter.PeakingConfig,
        cropRect: ResolvedCropRect = resolveCropRect(srcW, srcH, dstW, dstH, CropZoom.FULL_FRAME),
        yyuv422: Boolean = false,
    ): Int {
        if (!allowNativeSimd || !NativeIspSimdBridge.isAvailable) return NATIVE_ISP_NONE
        val sameDims = !cropRect.zoomed && srcW == dstW && srcH == dstH
        val fitW = cropRect.fitW
        val fitH = cropRect.fitH
        val offX = cropRect.offX
        val offY = cropRect.offY
        val endX = offX + fitW
        val srcXMap = ensureSrcXMap(dstW, srcW, cropRect.cropX, cropRect.cropW, fitW, offX, endX, sameDims)
        val effectiveRaw: ByteArray
        val effectiveSrcH: Int
        if (cropRect.zoomed && (cropRect.cropY > 0 || cropRect.cropH < srcH)) {
            // cropY is even-aligned by resolveCropRect, preserving RGGB Bayer phase at row 0 of the slice.
            val rowBytes = srcW * (if (is16Bit) 2 else 1)
            val sliceBytes = cropRect.cropH * rowBytes
            val slice = croppedRawScratch?.takeIf { it.size >= sliceBytes }
                ?: ByteArray(sliceBytes).also { croppedRawScratch = it }
            System.arraycopy(raw, cropRect.cropY * rowBytes, slice, 0, sliceBytes)
            effectiveRaw = slice
            effectiveSrcH = cropRect.cropH
        } else {
            effectiveRaw = raw
            effectiveSrcH = srcH
        }
        val hasCcm = colorPipeline?.hasCcm == true
        val hasYuvMat = colorPipeline?.hasYuvMatrix == true
        val monoMode = colorPipeline?.monochromeMode == true
        val lutR = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutR else LINEAR_TO_SRGB
        val lutG = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutG else LINEAR_TO_SRGB
        val lutB = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutB else LINEAR_TO_SRGB

        var flags = 0
        if (hasCcm) flags = flags or NativeIspSimdBridge.FLAG_HAS_CCM
        if (hasYuvMat) flags = flags or NativeIspSimdBridge.FLAG_HAS_YUV_MAT
        if (monoMode) flags = flags or NativeIspSimdBridge.FLAG_MONO_MODE
        if (peaking.enabled) flags = flags or NativeIspSimdBridge.FLAG_PEAKING
        if (!isSemiPlanar && yyuv422) flags = flags or NativeIspSimdBridge.FLAG_YYUV422

        val cfg = ispCfgScratch
        cfg[0] = srcW
        cfg[1] = effectiveSrcH
        cfg[2] = if (is16Bit) 1 else 0
        cfg[3] = dstW
        cfg[4] = dstH
        cfg[5] = stride
        cfg[6] = if (isSemiPlanar) 1 else 0
        cfg[7] = if (isNv12) 1 else 0
        cfg[8] = offX
        cfg[9] = offY
        cfg[10] = fitW
        cfg[11] = fitH
        cfg[12] = exposure.gainR1024
        cfg[13] = exposure.gainG1024
        cfg[14] = exposure.gainB1024
        cfg[15] = flags
        cfg[16] = colorPipeline?.yuvShift ?: 10
        cfg[17] = peaking.effectiveThreshold
        cfg[18] = (peaking.highlightY and 0xFF) or ((peaking.cb and 0xFF) shl 8) or ((peaking.cr and 0xFF) shl 16)
        cfg[19] = 0

        val histBuf = if (statsEngine != null) histYrgbScratch else null
        val stamped = NativeIspSimdBridge.demosaicYuvMtDirect(
            raw = effectiveRaw,
            yOrUyvyDst = yOrUyvyDst,
            uvDst = uvDst,
            srcXMap = srcXMap,
            ccm = if (hasCcm) colorPipeline.ccm else null,
            lutR = lutR,
            lutG = lutG,
            lutB = lutB,
            lut3d16 = null,
            yuvMat = if (hasYuvMat) colorPipeline.yuv else null,
            histYrgb = histBuf,
            outAfContrast = null,
            cfg = cfg,
        )
        if (stamped < 0) return NATIVE_ISP_NONE
        if (statsEngine != null && histBuf != null) {
            statsEngine.commitHistogramFromInterleaved(histBuf)
        }
        return NATIVE_ISP_DONE_COMPLETE
    }

    private fun demosaicBayerToSemiPlanarYuv(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        yDst: ByteArray,
        uvDst: ByteArray,
        dstW: Int,
        dstH: Int,
        stride: Int,
        nv12: Boolean,
        exposure: ExposureParams = DEFAULT_EXPOSURE_PARAMS,
        statsEngine: IspStatsSramEngine? = null,
        colorPipeline: IspColorPipeline? = null,
        cropRect: ResolvedCropRect = resolveCropRect(srcW, srcH, dstW, dstH, CropZoom.FULL_FRAME),
    ) {
        statsEngine?.beginHistogram()
        val gainR = exposure.gainR1024
        val gainG = exposure.gainG1024
        val gainB = exposure.gainB1024
        val unityGain = exposure.isUnityGain
        val hasCcm = colorPipeline?.hasCcm == true
        val ccm = colorPipeline?.ccm
        val lutR = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutR else LINEAR_TO_SRGB
        val lutG = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutG else LINEAR_TO_SRGB
        val lutB = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutB else LINEAR_TO_SRGB
        val monoMode = colorPipeline?.monochromeMode == true
        val yuvMat = colorPipeline?.takeIf { it.hasYuvMatrix }?.yuv
        val yuvShift = colorPipeline?.yuvShift ?: 10
        val yuvHalf = 1 shl (yuvShift - 1)
        val sameDims = !cropRect.zoomed && srcW == dstW && srcH == dstH
        val fitW = cropRect.fitW
        val fitH = cropRect.fitH
        val offX = cropRect.offX
        val offY = cropRect.offY
        val endX = offX + fitW
        val endY = offY + fitH
        val maxX = srcW - 1
        val maxY = srcH - 1
        val srcXMap = ensureSrcXMap(dstW, srcW, cropRect.cropX, cropRect.cropW, fitW, offX, endX, sameDims)
        val uvMaxRow = (if (nv12) dstH / 2 else dstH) - 1

        for (y in 0 until dstH) {
            val yRowOff = y * stride
            val writeUvRow = !nv12 || (y and 1) == 0
            val uvRowIdx = if (nv12) y ushr 1 else y
            val uvRowOff = uvRowIdx * stride
            if (y < offY || y >= endY) {
                Arrays.fill(yDst, yRowOff, yRowOff + dstW, 0.toByte())
                if (writeUvRow) {
                    Arrays.fill(uvDst, uvRowOff, uvRowOff + dstW, 0x80.toByte())
                }
            } else {
                val sy = if (sameDims) y else (cropRect.cropY + (((y - offY).toLong() * cropRect.cropH) / fitH).toInt()).coerceIn(0, maxY)
                // Mirror edge rows by 1 to preserve 2x2 RGGB Bayer CFA parity on top/bottom borders (BUG-V3-7-6).
                val yPrev = if (sy > 0) sy - 1 else minOf(1, maxY)
                val yNext = if (sy < maxY) sy + 1 else maxOf(0, maxY - 1)
                val rowCurr = sy * srcW
                val rowPrev = yPrev * srcW
                val rowNext = yNext * srcW
                val syOdd = (sy and 1) != 0
                var x = 0
                while (x < dstW) {
                    val inX0 = x >= offX && x < endX
                    val x1 = x + 1
                    val inX1 = x1 >= offX && x1 < endX
                    if (!inX0 && !inX1) {
                        yDst[yRowOff + x] = 0
                        yDst[yRowOff + x1] = 0
                        if (writeUvRow) {
                            uvDst[uvRowOff + x] = 0x80.toByte()
                            uvDst[uvRowOff + x1] = 0x80.toByte()
                        }
                    } else {
                        var r0 = 0
                        var g0 = 0
                        var b0 = 0
                        var y0 = 0
                        if (inX0) {
                            val sx0 = srcXMap[x]
                            val packed0 = if (is16Bit) {
                                demosaicPixelSrgb16(raw, maxX, sx0, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            } else {
                                demosaicPixelSrgb8(raw, maxX, sx0, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            }
                            r0 = packed0 and 0xFF
                            g0 = (packed0 ushr 8) and 0xFF
                            b0 = (packed0 ushr 16) and 0xFF
                            y0 = if (yuvMat != null) {
                                ((yuvMat[0] * r0 + yuvMat[1] * g0 + yuvMat[2] * b0 + yuvHalf) shr yuvShift).coerceIn(0, 255)
                            } else {
                                ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                            }
                            statsEngine?.addHistogramPixel(y0, r0, g0, b0)
                        }
                        var r1 = 0
                        var g1 = 0
                        var b1 = 0
                        var y1 = 0
                        if (inX1) {
                            val sx1 = srcXMap[x1]
                            val packed1 = if (is16Bit) {
                                demosaicPixelSrgb16(raw, maxX, sx1, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            } else {
                                demosaicPixelSrgb8(raw, maxX, sx1, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            }
                            r1 = packed1 and 0xFF
                            g1 = (packed1 ushr 8) and 0xFF
                            b1 = (packed1 ushr 16) and 0xFF
                            y1 = if (yuvMat != null) {
                                ((yuvMat[0] * r1 + yuvMat[1] * g1 + yuvMat[2] * b1 + yuvHalf) shr yuvShift).coerceIn(0, 255)
                            } else {
                                ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                            }
                            statsEngine?.addHistogramPixel(y1, r1, g1, b1)
                        }
                        yDst[yRowOff + x] = y0.toByte()
                        yDst[yRowOff + x1] = y1.toByte()
                        if (writeUvRow) {
                            val avgR: Int
                            val avgG: Int
                            val avgB: Int
                            if (inX0 && inX1) {
                                avgR = (r0 + r1) ushr 1
                                avgG = (g0 + g1) ushr 1
                                avgB = (b0 + b1) ushr 1
                            } else if (inX0) {
                                avgR = r0
                                avgG = g0
                                avgB = b0
                            } else {
                                avgR = r1
                                avgG = g1
                                avgB = b1
                            }
                            val u: Int
                            val v: Int
                            if (monoMode) {
                                u = 128
                                v = 128
                            } else if (yuvMat != null) {
                                u = (((yuvMat[3] * avgR + yuvMat[4] * avgG + yuvMat[5] * avgB + yuvHalf) shr yuvShift) + 128).coerceIn(0, 255)
                                v = (((yuvMat[6] * avgR + yuvMat[7] * avgG + yuvMat[8] * avgB + yuvHalf) shr yuvShift) + 128).coerceIn(0, 255)
                            } else {
                                u = (((-43 * avgR - 85 * avgG + 128 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                                v = (((128 * avgR - 107 * avgG - 21 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                            }
                            uvDst[uvRowOff + x] = u.toByte()
                            uvDst[uvRowOff + x1] = v.toByte()
                        }
                    }
                    x += 2
                }
            }
            if (y < dstH - 1 && stride > dstW) {
                Arrays.fill(yDst, yRowOff + dstW, yRowOff + stride, 0.toByte())
            }
            if (writeUvRow && uvRowIdx < uvMaxRow && stride > dstW) {
                Arrays.fill(uvDst, uvRowOff + dstW, uvRowOff + stride, 0.toByte())
            }
        }
        statsEngine?.commitHistogram()
    }

    private fun demosaicBayerToUyvy(
        raw: ByteArray,
        srcW: Int,
        srcH: Int,
        is16Bit: Boolean,
        dst: ByteArray,
        dstW: Int,
        dstH: Int,
        stride: Int,
        exposure: ExposureParams = DEFAULT_EXPOSURE_PARAMS,
        statsEngine: IspStatsSramEngine? = null,
        colorPipeline: IspColorPipeline? = null,
        cropRect: ResolvedCropRect = resolveCropRect(srcW, srcH, dstW, dstH, CropZoom.FULL_FRAME),
    ) {
        statsEngine?.beginHistogram()
        val gainR = exposure.gainR1024
        val gainG = exposure.gainG1024
        val gainB = exposure.gainB1024
        val unityGain = exposure.isUnityGain
        val hasCcm = colorPipeline?.hasCcm == true
        val ccm = colorPipeline?.ccm
        val lutR = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutR else LINEAR_TO_SRGB
        val lutG = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutG else LINEAR_TO_SRGB
        val lutB = if (colorPipeline?.hasToneLut == true) colorPipeline.toneLutB else LINEAR_TO_SRGB
        val monoMode = colorPipeline?.monochromeMode == true
        val yuvMat = colorPipeline?.takeIf { it.hasYuvMatrix }?.yuv
        val yuvShift = colorPipeline?.yuvShift ?: 10
        val yuvHalf = 1 shl (yuvShift - 1)
        val sameDims = !cropRect.zoomed && srcW == dstW && srcH == dstH
        val fitW = cropRect.fitW
        val fitH = cropRect.fitH
        val offX = cropRect.offX
        val offY = cropRect.offY
        val endX = offX + fitW
        val endY = offY + fitH
        val maxX = srcW - 1
        val maxY = srcH - 1
        val srcXMap = ensureSrcXMap(dstW, srcW, cropRect.cropX, cropRect.cropW, fitW, offX, endX, sameDims)

        for (y in 0 until dstH) {
            val rowOff = y * stride
            if (y < offY || y >= endY) {
                var outIdx = rowOff
                val rowEnd = rowOff + dstW * 2
                while (outIdx < rowEnd) {
                    dst[outIdx] = 0x80.toByte()
                    dst[outIdx + 1] = 0
                    dst[outIdx + 2] = 0x80.toByte()
                    dst[outIdx + 3] = 0
                    outIdx += 4
                }
            } else {
                val sy = if (sameDims) y else (cropRect.cropY + (((y - offY).toLong() * cropRect.cropH) / fitH).toInt()).coerceIn(0, maxY)
                // Mirror edge rows by 1 to preserve 2x2 RGGB Bayer CFA parity on top/bottom borders (BUG-V3-7-6).
                val yPrev = if (sy > 0) sy - 1 else minOf(1, maxY)
                val yNext = if (sy < maxY) sy + 1 else maxOf(0, maxY - 1)
                val rowCurr = sy * srcW
                val rowPrev = yPrev * srcW
                val rowNext = yNext * srcW
                val syOdd = (sy and 1) != 0
                var x = 0
                var outIdx = rowOff
                while (x < dstW) {
                    val inX0 = x >= offX && x < endX
                    val x1 = x + 1
                    val inX1 = x1 >= offX && x1 < endX
                    if (!inX0 && !inX1) {
                        dst[outIdx] = 0x80.toByte()
                        dst[outIdx + 1] = 0
                        dst[outIdx + 2] = 0x80.toByte()
                        dst[outIdx + 3] = 0
                    } else {
                        var r0 = 0
                        var g0 = 0
                        var b0 = 0
                        var y0 = 0
                        if (inX0) {
                            val sx0 = srcXMap[x]
                            val packed0 = if (is16Bit) {
                                demosaicPixelSrgb16(raw, maxX, sx0, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            } else {
                                demosaicPixelSrgb8(raw, maxX, sx0, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            }
                            r0 = packed0 and 0xFF
                            g0 = (packed0 ushr 8) and 0xFF
                            b0 = (packed0 ushr 16) and 0xFF
                            y0 = if (yuvMat != null) {
                                ((yuvMat[0] * r0 + yuvMat[1] * g0 + yuvMat[2] * b0 + yuvHalf) shr yuvShift).coerceIn(0, 255)
                            } else {
                                ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                            }
                            statsEngine?.addHistogramPixel(y0, r0, g0, b0)
                        }
                        var r1 = 0
                        var g1 = 0
                        var b1 = 0
                        var y1 = 0
                        if (inX1) {
                            val sx1 = srcXMap[x1]
                            val packed1 = if (is16Bit) {
                                demosaicPixelSrgb16(raw, maxX, sx1, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            } else {
                                demosaicPixelSrgb8(raw, maxX, sx1, rowCurr, rowPrev, rowNext, syOdd, gainR, gainG, gainB, unityGain, hasCcm, ccm, lutR, lutG, lutB)
                            }
                            r1 = packed1 and 0xFF
                            g1 = (packed1 ushr 8) and 0xFF
                            b1 = (packed1 ushr 16) and 0xFF
                            y1 = if (yuvMat != null) {
                                ((yuvMat[0] * r1 + yuvMat[1] * g1 + yuvMat[2] * b1 + yuvHalf) shr yuvShift).coerceIn(0, 255)
                            } else {
                                ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                            }
                            statsEngine?.addHistogramPixel(y1, r1, g1, b1)
                        }
                        val avgR: Int
                        val avgG: Int
                        val avgB: Int
                        if (inX0 && inX1) {
                            avgR = (r0 + r1) ushr 1
                            avgG = (g0 + g1) ushr 1
                            avgB = (b0 + b1) ushr 1
                        } else if (inX0) {
                            avgR = r0
                            avgG = g0
                            avgB = b0
                        } else {
                            avgR = r1
                            avgG = g1
                            avgB = b1
                        }
                        val u: Int
                        val v: Int
                        if (monoMode) {
                            u = 128
                            v = 128
                        } else if (yuvMat != null) {
                            u = (((yuvMat[3] * avgR + yuvMat[4] * avgG + yuvMat[5] * avgB + yuvHalf) shr yuvShift) + 128).coerceIn(0, 255)
                            v = (((yuvMat[6] * avgR + yuvMat[7] * avgG + yuvMat[8] * avgB + yuvHalf) shr yuvShift) + 128).coerceIn(0, 255)
                        } else {
                            u = (((-43 * avgR - 85 * avgG + 128 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                            v = (((128 * avgR - 107 * avgG - 21 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                        }
                        dst[outIdx] = u.toByte()
                        dst[outIdx + 1] = y0.toByte()
                        dst[outIdx + 2] = v.toByte()
                        dst[outIdx + 3] = y1.toByte()
                    }
                    x += 2
                    outIdx += 4
                }
            }
            if (y < dstH - 1 && stride > dstW * 2) {
                Arrays.fill(dst, rowOff + dstW * 2, rowOff + stride, 0.toByte())
            }
        }
        statsEngine?.commitHistogram()
    }

    companion object {
        private fun computeStillCrop(sW: Int, sH: Int, width: Int, height: Int, cropFactor: Double): IntArray {
            val srcAspect = sW.toDouble() / sH.toDouble()
            val dstAspect = width.toDouble() / height.toDouble()
            val baseCropW: Double
            val baseCropH: Double
            if (srcAspect > dstAspect) {
                baseCropH = sH.toDouble()
                baseCropW = (sH.toDouble() * dstAspect).coerceAtMost(sW.toDouble())
            } else {
                baseCropW = sW.toDouble()
                baseCropH = (sW.toDouble() / dstAspect).coerceAtMost(sH.toDouble())
            }
            val effFactor = cropFactor.coerceIn(1.0, 4.0)
            val cW = ((baseCropW / effFactor).roundToInt().coerceIn(2, sW)) and 1.inv()
            val cH = ((baseCropH / effFactor).roundToInt().coerceIn(2, sH)) and 1.inv()
            val cOffX = ((sW - cW) / 2) and 1.inv()
            val cOffY = ((sH - cH) / 2) and 1.inv()
            return intArrayOf(cOffX, cOffY, cW, cH)
        }

        private const val NATIVE_ISP_NONE = 0
        private const val NATIVE_ISP_DONE_NEEDS_YYUV = 1
        private const val NATIVE_ISP_DONE_COMPLETE = 2
        const val ENABLE_BIT_28 = 0x10000000L
        const val IDLE_BIT_8 = 0x100L
        const val UNITY_GAIN_1024 = 1024
        private const val YUV_CACHE_SLOTS = 4
        private const val DEFAULT_SENSOR_WIDTH = 1024
        private const val DEFAULT_SENSOR_HEIGHT = 576
        private const val MAX_STRIDE_BYTES = 65536L
        private const val MAX_FRAME_BYTES = 64L * 1024L * 1024L
        // 102MP GFX100S RECC_S still/RAW-conversion output (11664x8764 @ stride 31680 B = 277.6 MB); allow 512 MiB.
        private const val MAX_STILL_FRAME_BYTES = 512L * 1024L * 1024L
        private const val STILL_STRIP_MAX_BYTES = 2 * 1024 * 1024
        const val NOMINAL_DURATION_NANOS = 1000L
        const val NOMINAL_ISO = 100
        val DEFAULT_EXPOSURE_PARAMS = ExposureParams()
        private val DEFAULT_LENS = LensParameters("GF63mmF2.8", 63.0, 2.8, 10.0, 0.9, 0.0)

        private val LINEAR_TO_SRGB = IntArray(4096) { idx ->
            val c = idx / 4095.0
            val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
            (srgb * 255.0).roundToInt().coerceIn(0, 255)
        }

        private val SAMPLE8_TO_12 = IntArray(256) { v8 -> (v8 * 4095) / 255 }

        private fun sample12From16(raw: ByteArray, idx: Int): Int {
            val off = idx shl 1
            return ((raw[off].toInt() and 0xff) or ((raw[off + 1].toInt() and 0xff) shl 8)) ushr 4
        }

        private fun sample12From8(raw: ByteArray, idx: Int): Int =
            SAMPLE8_TO_12[raw[idx].toInt() and 0xff]

        /**
         * Demosaics a single 16-bit Bayer pixel into packed sRGB `r | (g shl 8) | (b shl 16)`
         * with zero heap allocations and optional Q10 RGB digital gain modulation.
         */
        private fun demosaicPixelSrgb16(
            raw: ByteArray,
            maxX: Int,
            sx: Int,
            rowCurr: Int,
            rowPrev: Int,
            rowNext: Int,
            syOdd: Boolean,
            gainR1024: Int = UNITY_GAIN_1024,
            gainG1024: Int = UNITY_GAIN_1024,
            gainB1024: Int = UNITY_GAIN_1024,
            unityGain: Boolean = true,
            hasCcm: Boolean = false,
            ccm: IntArray? = null,
            lutR: IntArray = LINEAR_TO_SRGB,
            lutG: IntArray = LINEAR_TO_SRGB,
            lutB: IntArray = LINEAR_TO_SRGB,
        ): Int {
            // Mirror edge columns by 1 to preserve 2x2 RGGB Bayer CFA parity on left/right borders (BUG-V3-7-6).
            val xPrev = if (sx > 0) sx - 1 else minOf(1, maxX)
            val xNext = if (sx < maxX) sx + 1 else maxOf(0, maxX - 1)
            val sxOdd = (sx and 1) != 0
            val c = sample12From16(raw, rowCurr + sx)
            val rLin: Int
            val gLin: Int
            val bLin: Int
            if (!syOdd && !sxOdd) {
                rLin = c
                gLin = (sample12From16(raw, rowCurr + xPrev) +
                    sample12From16(raw, rowCurr + xNext) +
                    sample12From16(raw, rowPrev + sx) +
                    sample12From16(raw, rowNext + sx) + 2) ushr 2
                bLin = (sample12From16(raw, rowPrev + xPrev) +
                    sample12From16(raw, rowPrev + xNext) +
                    sample12From16(raw, rowNext + xPrev) +
                    sample12From16(raw, rowNext + xNext) + 2) ushr 2
            } else if (!syOdd && sxOdd) {
                rLin = (sample12From16(raw, rowCurr + xPrev) + sample12From16(raw, rowCurr + xNext) + 1) ushr 1
                gLin = c
                bLin = (sample12From16(raw, rowPrev + sx) + sample12From16(raw, rowNext + sx) + 1) ushr 1
            } else if (syOdd && !sxOdd) {
                rLin = (sample12From16(raw, rowPrev + sx) + sample12From16(raw, rowNext + sx) + 1) ushr 1
                gLin = c
                bLin = (sample12From16(raw, rowCurr + xPrev) + sample12From16(raw, rowCurr + xNext) + 1) ushr 1
            } else {
                rLin = (sample12From16(raw, rowPrev + xPrev) +
                    sample12From16(raw, rowPrev + xNext) +
                    sample12From16(raw, rowNext + xPrev) +
                    sample12From16(raw, rowNext + xNext) + 2) ushr 2
                gLin = (sample12From16(raw, rowCurr + xPrev) +
                    sample12From16(raw, rowCurr + xNext) +
                    sample12From16(raw, rowPrev + sx) +
                    sample12From16(raw, rowNext + sx) + 2) ushr 2
                bLin = c
            }
            val rGained = if (unityGain) rLin else ((rLin.toLong() * gainR1024) ushr 10).toInt().coerceIn(0, 4095)
            val gGained = if (unityGain) gLin else ((gLin.toLong() * gainG1024) ushr 10).toInt().coerceIn(0, 4095)
            val bGained = if (unityGain) bLin else ((bLin.toLong() * gainB1024) ushr 10).toInt().coerceIn(0, 4095)
            val rIdx: Int
            val gIdx: Int
            val bIdx: Int
            if (hasCcm && ccm != null) {
                rIdx = ((ccm[0] * rGained + ccm[1] * gGained + ccm[2] * bGained + 512) shr 10).coerceIn(0, 4095)
                gIdx = ((ccm[3] * rGained + ccm[4] * gGained + ccm[5] * bGained + 512) shr 10).coerceIn(0, 4095)
                bIdx = ((ccm[6] * rGained + ccm[7] * gGained + ccm[8] * bGained + 512) shr 10).coerceIn(0, 4095)
            } else {
                rIdx = rGained
                gIdx = gGained
                bIdx = bGained
            }
            val r = lutR[rIdx]
            val g = lutG[gIdx]
            val b = lutB[bIdx]
            return r or (g shl 8) or (b shl 16)
        }

        /**
         * Demosaics a single 8-bit Bayer pixel into packed sRGB `r | (g shl 8) | (b shl 16)`
         * with zero heap allocations and optional Q10 RGB digital gain, CCM, and Tone LUT modulation.
         */
        private fun demosaicPixelSrgb8(
            raw: ByteArray,
            maxX: Int,
            sx: Int,
            rowCurr: Int,
            rowPrev: Int,
            rowNext: Int,
            syOdd: Boolean,
            gainR1024: Int = UNITY_GAIN_1024,
            gainG1024: Int = UNITY_GAIN_1024,
            gainB1024: Int = UNITY_GAIN_1024,
            unityGain: Boolean = true,
            hasCcm: Boolean = false,
            ccm: IntArray? = null,
            lutR: IntArray = LINEAR_TO_SRGB,
            lutG: IntArray = LINEAR_TO_SRGB,
            lutB: IntArray = LINEAR_TO_SRGB,
        ): Int {
            // Mirror edge columns by 1 to preserve 2x2 RGGB Bayer CFA parity on left/right borders (BUG-V3-7-6).
            val xPrev = if (sx > 0) sx - 1 else minOf(1, maxX)
            val xNext = if (sx < maxX) sx + 1 else maxOf(0, maxX - 1)
            val sxOdd = (sx and 1) != 0
            val c = sample12From8(raw, rowCurr + sx)
            val rLin: Int
            val gLin: Int
            val bLin: Int
            if (!syOdd && !sxOdd) {
                rLin = c
                gLin = (sample12From8(raw, rowCurr + xPrev) +
                    sample12From8(raw, rowCurr + xNext) +
                    sample12From8(raw, rowPrev + sx) +
                    sample12From8(raw, rowNext + sx) + 2) ushr 2
                bLin = (sample12From8(raw, rowPrev + xPrev) +
                    sample12From8(raw, rowPrev + xNext) +
                    sample12From8(raw, rowNext + xPrev) +
                    sample12From8(raw, rowNext + xNext) + 2) ushr 2
            } else if (!syOdd && sxOdd) {
                rLin = (sample12From8(raw, rowCurr + xPrev) + sample12From8(raw, rowCurr + xNext) + 1) ushr 1
                gLin = c
                bLin = (sample12From8(raw, rowPrev + sx) + sample12From8(raw, rowNext + sx) + 1) ushr 1
            } else if (syOdd && !sxOdd) {
                rLin = (sample12From8(raw, rowPrev + sx) + sample12From8(raw, rowNext + sx) + 1) ushr 1
                gLin = c
                bLin = (sample12From8(raw, rowCurr + xPrev) + sample12From8(raw, rowCurr + xNext) + 1) ushr 1
            } else {
                rLin = (sample12From8(raw, rowPrev + xPrev) +
                    sample12From8(raw, rowPrev + xNext) +
                    sample12From8(raw, rowNext + xPrev) +
                    sample12From8(raw, rowNext + xNext) + 2) ushr 2
                gLin = (sample12From8(raw, rowCurr + xPrev) +
                    sample12From8(raw, rowCurr + xNext) +
                    sample12From8(raw, rowPrev + sx) +
                    sample12From8(raw, rowNext + sx) + 2) ushr 2
                bLin = c
            }
            val rGained = if (unityGain) rLin else ((rLin.toLong() * gainR1024) ushr 10).toInt().coerceIn(0, 4095)
            val gGained = if (unityGain) gLin else ((gLin.toLong() * gainG1024) ushr 10).toInt().coerceIn(0, 4095)
            val bGained = if (unityGain) bLin else ((bLin.toLong() * gainB1024) ushr 10).toInt().coerceIn(0, 4095)
            val rIdx: Int
            val gIdx: Int
            val bIdx: Int
            if (hasCcm && ccm != null) {
                rIdx = ((ccm[0] * rGained + ccm[1] * gGained + ccm[2] * bGained + 512) shr 10).coerceIn(0, 4095)
                gIdx = ((ccm[3] * rGained + ccm[4] * gGained + ccm[5] * bGained + 512) shr 10).coerceIn(0, 4095)
                bIdx = ((ccm[6] * rGained + ccm[7] * gGained + ccm[8] * bGained + 512) shr 10).coerceIn(0, 4095)
            } else {
                rIdx = rGained
                gIdx = gGained
                bIdx = bGained
            }
            val r = lutR[rIdx]
            val g = lutG[gIdx]
            val b = lutB[bIdx]
            return r or (g shl 8) or (b shl 16)
        }
    }
}
