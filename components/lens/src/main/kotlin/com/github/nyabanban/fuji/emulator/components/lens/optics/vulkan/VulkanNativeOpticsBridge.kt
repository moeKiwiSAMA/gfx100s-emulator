package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.ExternalGpuFrameDescriptor
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * JDK 25 Foreign Function & Memory (FFM) bridge to `libfuji_vulkan_optics.so` (Linux) /
 * `fuji_vulkan_optics.dll` (Windows).
 *
 * Dynamically discovers the native library with zero static Vulkan linkage and zero JNI.
 * Reuses persistent off-heap staging segments under [stagingLock] so steady-state frame
 * dispatches perform zero per-frame off-heap allocations, and supports direct [ByteBuffer]
 * zero-copy output via [MemorySegment.ofBuffer].
 */
class VulkanNativeOpticsBridge private constructor(
    val libraryPath: Path,
    private val libraryArena: Arena,
    private val abiVersionFn: MethodHandle,
    private val enumerateDevicesFn: MethodHandle?,
    private val createForDeviceFn: MethodHandle?,
    private val createWithCacheFn: MethodHandle?,
    private val setPipelineCachePathFn: MethodHandle?,
    private val getPipelineStatsFn: MethodHandle?,
    val supportsPupilTransform: Boolean,
    private val createFn: MethodHandle,
    private val destroyFn: MethodHandle,
    private val getDeviceInfoFn: MethodHandle,
    private val lastErrorFn: MethodHandle,
    private val getSpirvWordsFn: MethodHandle,
    private val renderOpticsFn: MethodHandle,
    private val renderOpticsFilteredFn: MethodHandle,
    private val renderCmosBayerFn: MethodHandle,
    private val renderOpticsToBayerFn: MethodHandle,
    private val renderOpticsToBayerFilteredFn: MethodHandle,
    private val ispDemosaicFn: MethodHandle,
    private val renderOpticsToIspYuvFn: MethodHandle?,
    private val renderOpticsToIspYuvWithStatsFn: MethodHandle?,
    private val uploadSceneFn: MethodHandle?,
    private val renderCachedSceneOpticsFn: MethodHandle?,
    private val renderCachedSceneToBayerFn: MethodHandle?,
    private val renderCachedSceneToIspYuvFn: MethodHandle?,
    private val renderCachedSceneToIspYuvWithStatsFn: MethodHandle?,
    private val submitAsyncToBayerFn: MethodHandle?,
    private val collectAsyncBayerFn: MethodHandle?,
    private val validateExternalFrameDescFn: MethodHandle,
    private val importExternalFrameFn: MethodHandle,
    private val releaseExternalFrameFn: MethodHandle,
    private val registerCustomSpvFn: MethodHandle?,
    private val setCustomSpvStagesFn: MethodHandle?,
    private val customSpvCacheStatsFn: MethodHandle?,
    private val runCustomSpvPassFn: MethodHandle?,
) : AutoCloseable {

    private data class AsyncTicketKey(val ctxAddr: Long, val ticket: Long)

    private val stagingLock = Any()
    private val asyncTicketBytes = HashMap<AsyncTicketKey, Int>()
    private var inStageArena: Arena? = null
    private var inStageSeg: MemorySegment = MemorySegment.NULL
    private var outStageArena: Arena? = null
    private var outStageSeg: MemorySegment = MemorySegment.NULL
    private var uvStageArena: Arena? = null
    private var uvStageSeg: MemorySegment = MemorySegment.NULL
    private val fixedStageArena: Arena = Arena.ofShared()
    private val filterParamSeg: MemorySegment = fixedStageArena.allocate(FILTER_PARAMS_BYTES, 16L)
    private val opticsParamSeg: MemorySegment = fixedStageArena.allocate(OPTICS_PARAMS_BYTES, 16L)
    private val cmosParamSeg: MemorySegment = fixedStageArena.allocate(CMOS_PARAMS_BYTES, 16L)
    private val ispParamSeg: MemorySegment = fixedStageArena.allocate(ISP_PARAMS_BYTES, 16L)
    private val lutRStageSeg: MemorySegment = fixedStageArena.allocate(4096L * 4L, 16L)
    private val lutGStageSeg: MemorySegment = fixedStageArena.allocate(4096L * 4L, 16L)
    private val lutBStageSeg: MemorySegment = fixedStageArena.allocate(4096L * 4L, 16L)
    private val statsParamSeg: MemorySegment = fixedStageArena.allocate(STATS_PARAMS_BYTES, 16L)
    private val statsOutSeg: MemorySegment = fixedStageArena.allocate(STATS_OUTPUT_BYTES, 16L)
    private val outMsSeg: MemorySegment = fixedStageArena.allocate(ValueLayout.JAVA_DOUBLE, 8L)
    private val outTicketSeg: MemorySegment = fixedStageArena.allocate(ValueLayout.JAVA_LONG, 8L)
    private val pipelineStatsSeg: MemorySegment = fixedStageArena.allocate(PIPELINE_STATS_BYTES, 8L)

    val supportsFusedOpticsIsp: Boolean get() = renderOpticsToIspYuvFn != null
    val supportsFusedIspStats: Boolean
        get() = renderOpticsToIspYuvWithStatsFn != null && renderCachedSceneToIspYuvWithStatsFn != null
    val supportsSceneCache: Boolean
        get() = uploadSceneFn != null && renderCachedSceneOpticsFn != null &&
            renderCachedSceneToBayerFn != null && renderCachedSceneToIspYuvFn != null
    val supportsAsyncRing2: Boolean
        get() = submitAsyncToBayerFn != null && collectAsyncBayerFn != null
    val supportsCustomSpirv: Boolean
        get() = registerCustomSpvFn != null && setCustomSpvStagesFn != null

    data class NativePipelineStats(
        val pipelineCacheHit: Boolean,
        val specializedPipelineCount: Int,
        val bufferReallocCount: Int,
        val hostCachedReadback: Boolean,
        val pipelineCacheBytes: Long,
        val pipelineWarmupMs: Double,
        val asyncFramesSubmitted: Long,
        val asyncFramesCollected: Long,
    )

    private fun ensureInStage(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (inStageSeg != MemorySegment.NULL && inStageSeg.byteSize() >= needed) {
            return inStageSeg
        }
        inStageArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        inStageArena = arena
        return arena.allocate(needed, 16L).also { inStageSeg = it }
    }

    private fun ensureOutStage(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (outStageSeg != MemorySegment.NULL && outStageSeg.byteSize() >= needed) {
            return outStageSeg
        }
        outStageArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        outStageArena = arena
        return arena.allocate(needed, 16L).also { outStageSeg = it }
    }

    private fun ensureUvStage(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (uvStageSeg != MemorySegment.NULL && uvStageSeg.byteSize() >= needed) {
            return uvStageSeg
        }
        uvStageArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        uvStageArena = arena
        return arena.allocate(needed, 16L).also { uvStageSeg = it }
    }

    data class NativeDeviceInfo(
        val abiVersion: Int,
        val apiVersion: Int,
        val vendorId: Int,
        val deviceId: Int,
        val deviceType: Int,
        val computeQueueFamily: Int,
        val externalMemoryCaps: Int,
        val deviceName: String,
        val loaderName: String,
        val deviceIndex: Int = 0,
    ) {
        val supportsExternalMemory: Boolean get() = externalMemoryCaps != 0

        fun specString(): String = "$deviceIndex:$deviceName"

        fun formattedApiVersion(): String {
            if (apiVersion == 0) return "0.0.0"
            val major = (apiVersion ushr 22) and 0x7F
            val minor = (apiVersion ushr 12) and 0x3FF
            val patch = apiVersion and 0xFFF
            return "$major.$minor.$patch"
        }

        fun deviceTypeName(): String = when (deviceType) {
            1 -> "INTEGRATED_GPU"
            2 -> "DISCRETE_GPU"
            3 -> "VIRTUAL_GPU"
            4 -> "CPU_SOFTWARE"
            else -> "OTHER"
        }

        fun shortDeviceTypeLabel(): String = when (deviceType) {
            1 -> "Integrated"
            2 -> "Discrete"
            3 -> "Virtual"
            4 -> "CPU"
            else -> "Other"
        }

        fun toStateMap(): Map<String, Any?> = linkedMapOf(
            "index" to deviceIndex,
            "name" to deviceName,
            "spec" to specString(),
            "deviceType" to deviceTypeName(),
            "deviceTypeCode" to deviceType,
            "vendorId" to vendorId,
            "deviceId" to deviceId,
            "apiVersion" to formattedApiVersion(),
            "supportsExternalMemory" to supportsExternalMemory,
            "externalMemoryCaps" to externalMemoryCaps,
        )
    }

    data class CreateOutcome(
        val status: Int,
        val contextHandle: MemorySegment,
        val deviceInfo: NativeDeviceInfo?,
        val errorMessage: String,
    )

    fun abiVersion(): Int = abiVersionFn.invokeExact() as Int

    fun getSpirvWords(shaderId: Int): IntArray {
        Arena.ofConfined().use { arena ->
            val pWords = arena.allocate(ValueLayout.ADDRESS)
            val pCount = arena.allocate(ValueLayout.JAVA_INT)
            val rc = getSpirvWordsFn.invokeExact(shaderId, pWords, pCount) as Int
            require(rc == FUJI_VK_OK) { "fuji_vk_get_spirv_words($shaderId) failed with rc=$rc" }
            val count = pCount.get(ValueLayout.JAVA_INT, 0)
            val rawPtr = pWords.get(ValueLayout.ADDRESS, 0).reinterpret(count.toLong() * 4L)
            val out = IntArray(count)
            MemorySegment.copy(rawPtr, ValueLayout.JAVA_INT, 0L, out, 0, count)
            return out
        }
    }

    fun enumerateDevices(maxDevices: Int = MAX_ENUMERATED_DEVICES): List<NativeDeviceInfo> {
        val fn = enumerateDevicesFn ?: return emptyList()
        val cap = maxDevices.coerceIn(1, 64)
        Arena.ofConfined().use { arena ->
            val devicesSeg = arena.allocate(DEVICE_INFO_BYTES * cap.toLong(), 8L)
            val totalSeg = arena.allocate(ValueLayout.JAVA_INT)
            val errBuf = arena.allocate(ERR_BUF_BYTES, 1L)
            val rc = fn.invokeExact(devicesSeg, cap, totalSeg, errBuf, ERR_BUF_BYTES.toInt()) as Int
            if (rc != FUJI_VK_OK) return emptyList()
            val total = totalSeg.get(ValueLayout.JAVA_INT, 0L).coerceIn(0, cap)
            val out = ArrayList<NativeDeviceInfo>(total)
            for (i in 0 until total) {
                val slice = devicesSeg.asSlice(i.toLong() * DEVICE_INFO_BYTES, DEVICE_INFO_BYTES)
                out.add(parseDeviceInfo(slice))
            }
            return out
        }
    }

    fun createContext(selectFlags: Int): CreateOutcome = createContextWithCache(-1, selectFlags, null)

    fun createContextForDevice(deviceIndex: Int, selectFlags: Int): CreateOutcome =
        createContextWithCache(deviceIndex, selectFlags, null)

    fun setPipelineCachePath(cachePath: Path?) {
        val fn = setPipelineCachePathFn ?: return
        Arena.ofConfined().use { arena ->
            val pathSeg = if (cachePath != null) arena.allocateFrom(cachePath.toAbsolutePath().toString()) else MemorySegment.NULL
            fn.invokeExact(pathSeg)
        }
    }

    fun createContextWithCache(deviceIndex: Int, selectFlags: Int, cachePath: Path?): CreateOutcome {
        Arena.ofConfined().use { arena ->
            val pCtx = arena.allocate(ValueLayout.ADDRESS)
            val infoSeg = arena.allocate(DEVICE_INFO_BYTES, 8L)
            val errBuf = arena.allocate(ERR_BUF_BYTES, 1L)
            val withCacheFn = createWithCacheFn
            val forDevFn = createForDeviceFn
            val rc = if (withCacheFn != null) {
                val pathSeg = if (cachePath != null) arena.allocateFrom(cachePath.toAbsolutePath().toString()) else MemorySegment.NULL
                withCacheFn.invokeExact(deviceIndex, selectFlags, pathSeg, pCtx, infoSeg, errBuf, ERR_BUF_BYTES) as Int
            } else if (forDevFn != null) {
                forDevFn.invokeExact(deviceIndex, selectFlags, pCtx, infoSeg, errBuf, ERR_BUF_BYTES) as Int
            } else if (deviceIndex < 0) {
                createFn.invokeExact(selectFlags, pCtx, infoSeg, errBuf, ERR_BUF_BYTES) as Int
            } else {
                return CreateOutcome(
                    status = FUJI_VK_ERR_NO_DEVICE,
                    contextHandle = MemorySegment.NULL,
                    deviceInfo = null,
                    errorMessage = "Loaded native Vulkan bridge does not export fuji_vk_create_for_device",
                )
            }
            if (rc != FUJI_VK_OK) {
                return CreateOutcome(
                    status = rc,
                    contextHandle = MemorySegment.NULL,
                    deviceInfo = null,
                    errorMessage = readCString(errBuf, 0L, ERR_BUF_BYTES.toInt()),
                )
            }
            val ctx = pCtx.get(ValueLayout.ADDRESS, 0)
            return CreateOutcome(
                status = FUJI_VK_OK,
                contextHandle = ctx,
                deviceInfo = parseDeviceInfo(infoSeg),
                errorMessage = "",
            )
        }
    }

    fun destroyContext(ctx: MemorySegment) {
        if (ctx != MemorySegment.NULL) {
            val addr = ctx.address()
            synchronized(stagingLock) {
                asyncTicketBytes.keys.removeIf { it.ctxAddr == addr }
            }
            destroyFn.invokeExact(ctx)
        }
    }

    fun lastError(ctx: MemorySegment): String {
        val ptr = lastErrorFn.invokeExact(ctx) as MemorySegment
        if (ptr == MemorySegment.NULL) return ""
        return readCString(ptr.reinterpret(512L), 0L, 512)
    }

    fun renderOptics(
        ctx: MemorySegment,
        inRgbaDepth: FloatArray,
        outRgba: FloatArray,
        opticsParams: OpticsParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
        inFloatCount: Int = inRgbaDepth.size,
        outFloatCount: Int = outRgba.size,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val inputElements = rgbaElements(opticsParams.srcWidth, opticsParams.srcHeight, inFloatCount, inRgbaDepth.size)
        if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inSeg = ensureInStage(inputElements.toLong() * 4L)
        val outputElements = rgbaElements(opticsParams.dstWidth, opticsParams.dstHeight, outFloatCount, outRgba.size)
        if (outputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val outSeg = ensureOutStage(outputElements.toLong() * 4L)
        MemorySegment.copy(inRgbaDepth, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)
        writeOpticsParams(opticsParamSeg, opticsParams)

        val rc = if (filterParams == null || filterParams.enableFlags == 0) {
            renderOpticsFn.invokeExact(ctx, inSeg, outSeg, opticsParamSeg, outMsSeg) as Int
        } else {
            writeFilterParams(filterParamSeg, filterParams)
            renderOpticsFilteredFn.invokeExact(ctx, inSeg, outSeg, filterParamSeg, opticsParamSeg, outMsSeg) as Int
        }
        if (rc == FUJI_VK_OK) {
            MemorySegment.copy(outSeg, ValueLayout.JAVA_FLOAT, 0L, outRgba, 0, outputElements)
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderCmosBayer(
        ctx: MemorySegment,
        inRgba: FloatArray,
        outBayer: ByteArray,
        cmosParams: CmosParamsStruct,
        inFloatCount: Int = inRgba.size,
        outByteCount: Int = outBayer.size,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val inputElements = rgbaElements(cmosParams.width, cmosParams.height, inFloatCount, inRgba.size)
        if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inSeg = ensureInStage(inputElements.toLong() * 4L)
        val bytes = bayerBytes(cmosParams.width, cmosParams.height, cmosParams.bitDepth, outByteCount, outBayer.size)
        if (bytes == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val outSeg = ensureOutStage(bytes.toLong())
        MemorySegment.copy(inRgba, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)
        writeCmosParams(cmosParamSeg, cmosParams)

        val rc = renderCmosBayerFn.invokeExact(
            ctx,
            inSeg,
            outSeg,
            bytes.toLong(),
            cmosParamSeg,
            outMsSeg,
        ) as Int
        if (rc == FUJI_VK_OK) {
            MemorySegment.copy(outSeg, ValueLayout.JAVA_BYTE, 0L, outBayer, 0, bytes)
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    /**
     * Zero-copy direct-ByteBuffer overload writing Bayer bytes directly into [outBayerSegment]
     * without intermediate off-heap staging allocation or heap `ByteArray` copy.
     */
    fun renderCmosBayerDirect(
        ctx: MemorySegment,
        inRgba: FloatArray,
        inFloatCount: Int,
        outBayerSegment: MemorySegment,
        outByteCount: Int,
        cmosParams: CmosParamsStruct,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        if (!isWritableBayerOutput(outBayerSegment, outByteCount)) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inputElements = rgbaElements(cmosParams.width, cmosParams.height, inFloatCount, inRgba.size)
        if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inSeg = ensureInStage(inputElements.toLong() * 4L)
        MemorySegment.copy(inRgba, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)
        writeCmosParams(cmosParamSeg, cmosParams)

        val rc = renderCmosBayerFn.invokeExact(
            ctx,
            inSeg,
            outBayerSegment,
            outByteCount.toLong(),
            cmosParamSeg,
            outMsSeg,
        ) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderOpticsToBayer(
        ctx: MemorySegment,
        inRgbaDepth: FloatArray,
        outBayer: ByteArray,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
        inFloatCount: Int = inRgbaDepth.size,
        outByteCount: Int = outBayer.size,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val inputElements = rgbaElements(opticsParams.srcWidth, opticsParams.srcHeight, inFloatCount, inRgbaDepth.size)
        if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inSeg = ensureInStage(inputElements.toLong() * 4L)
        val bytes = bayerBytes(opticsParams.dstWidth, opticsParams.dstHeight, cmosParams.bitDepth, outByteCount, outBayer.size)
        if (bytes == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val outSeg = ensureOutStage(bytes.toLong())
        MemorySegment.copy(inRgbaDepth, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)
        writeOpticsParams(opticsParamSeg, opticsParams)
        writeCmosParams(cmosParamSeg, cmosParams)

        val rc = if (filterParams == null || filterParams.enableFlags == 0) {
            renderOpticsToBayerFn.invokeExact(
                ctx,
                inSeg,
                outSeg,
                bytes.toLong(),
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
        } else {
            writeFilterParams(filterParamSeg, filterParams)
            renderOpticsToBayerFilteredFn.invokeExact(
                ctx,
                inSeg,
                outSeg,
                bytes.toLong(),
                filterParamSeg,
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
        }
        if (rc == FUJI_VK_OK) {
            MemorySegment.copy(outSeg, ValueLayout.JAVA_BYTE, 0L, outBayer, 0, bytes)
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    /**
     * Zero-copy direct-ByteBuffer overload writing chained Pass 0..3 Bayer bytes directly into
     * [outBayerSegment] with zero intermediate off-heap staging allocation or heap `ByteArray` copy.
     */
    fun renderOpticsToBayerDirect(
        ctx: MemorySegment,
        inRgbaDepth: FloatArray,
        inFloatCount: Int,
        outBayerSegment: MemorySegment,
        outByteCount: Int,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        if (!isWritableBayerOutput(outBayerSegment, outByteCount)) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inputElements = rgbaElements(opticsParams.srcWidth, opticsParams.srcHeight, inFloatCount, inRgbaDepth.size)
        if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
        val inSeg = ensureInStage(inputElements.toLong() * 4L)
        MemorySegment.copy(inRgbaDepth, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)
        writeOpticsParams(opticsParamSeg, opticsParams)
        writeCmosParams(cmosParamSeg, cmosParams)

        val rc = if (filterParams == null || filterParams.enableFlags == 0) {
            renderOpticsToBayerFn.invokeExact(
                ctx,
                inSeg,
                outBayerSegment,
                outByteCount.toLong(),
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
        } else {
            writeFilterParams(filterParamSeg, filterParams)
            renderOpticsToBayerFilteredFn.invokeExact(
                ctx,
                inSeg,
                outBayerSegment,
                outByteCount.toLong(),
                filterParamSeg,
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun ispDemosaic(
        ctx: MemorySegment,
        rawBayer: ByteArray,
        lutR: IntArray? = null,
        lutG: IntArray? = null,
        lutB: IntArray? = null,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        params: IspDemosaicParamsStruct,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val rawSeg = ensureInStage(rawBayer.size.toLong())
        MemorySegment.copy(rawBayer, 0, rawSeg, ValueLayout.JAVA_BYTE, 0L, rawBayer.size)

        val lutRSeg = if (lutR != null) {
            MemorySegment.copy(lutR, 0, lutRStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
            lutRStageSeg
        } else MemorySegment.NULL
        val lutGSeg = if (lutG != null) {
            MemorySegment.copy(lutG, 0, lutGStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
            lutGStageSeg
        } else MemorySegment.NULL
        val lutBSeg = if (lutB != null) {
            MemorySegment.copy(lutB, 0, lutBStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
            lutBStageSeg
        } else MemorySegment.NULL

        val ySeg = ensureOutStage(outYOrPacked.size.toLong())
        val uvSeg = if (outUvPlane != null) ensureUvStage(outUvPlane.size.toLong()) else MemorySegment.NULL
        writeIspParams(ispParamSeg, params)

        val rc = ispDemosaicFn.invokeExact(
            ctx,
            rawSeg,
            rawBayer.size.toLong(),
            lutRSeg,
            lutGSeg,
            lutBSeg,
            ySeg,
            outYOrPacked.size.toLong(),
            uvSeg,
            (outUvPlane?.size ?: 0).toLong(),
            ispParamSeg,
            outMsSeg,
        ) as Int
        if (rc == FUJI_VK_OK) {
            copyIspReadback(params, ySeg, outYOrPacked, uvSeg, outUvPlane)
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    /**
     * Fused single-submit GPU-resident Pass 0..5 (`Filter -> Warp -> Bokeh -> CMOS Bayer ->
     * ISP Demosaic [-> Peaking] -> YUV`) with zero intermediate Bayer host readback.
     */
    fun renderOpticsToIspYuv(
        ctx: MemorySegment,
        inRgbaDepth: FloatArray,
        inFloatCount: Int,
        filterParams: FilterStackParamsStruct?,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        lutR: IntArray? = null,
        lutG: IntArray? = null,
        lutB: IntArray? = null,
        outYOrPacked: ByteArray,
        outYByteCount: Int = outYOrPacked.size,
        outUvPlane: ByteArray? = null,
        outUvByteCount: Int = outUvPlane?.size ?: 0,
        ispParams: IspDemosaicParamsStruct,
        statsRequest: LiveViewIspStatsRequest? = null,
        outStatsWords: IntArray? = null,
    ): Pair<Int, Double> {
        if (outYByteCount !in 1..outYOrPacked.size || outUvByteCount !in 0..(outUvPlane?.size ?: 0)) {
            return FUJI_VK_ERR_INVALID_ARG to 0.0
        }
        val wantStats = statsRequest != null && outStatsWords != null && outStatsWords.size >= LiveViewIspFusionRequest.STATS_OUTPUT_WORDS
        val fnStats = if (wantStats) renderOpticsToIspYuvWithStatsFn else null
        val fn = if (fnStats != null) null else (renderOpticsToIspYuvFn ?: return (-6 to 0.0))
        return synchronized(stagingLock) {
            val inputElements = rgbaElements(opticsParams.srcWidth, opticsParams.srcHeight, inFloatCount, inRgbaDepth.size)
            if (inputElements == 0) return@synchronized FUJI_VK_ERR_INVALID_ARG to 0.0
            val inSeg = ensureInStage(inputElements.toLong() * 4L)
            MemorySegment.copy(inRgbaDepth, 0, inSeg, ValueLayout.JAVA_FLOAT, 0L, inputElements)

            val filterSeg = if (filterParams != null && filterParams.enableFlags != 0) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else MemorySegment.NULL
            writeOpticsParams(opticsParamSeg, opticsParams)
            writeCmosParams(cmosParamSeg, cmosParams)
            writeIspParams(ispParamSeg, ispParams)

            val lutRSeg = if (lutR != null) {
                MemorySegment.copy(lutR, 0, lutRStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutRStageSeg
            } else MemorySegment.NULL
            val lutGSeg = if (lutG != null) {
                MemorySegment.copy(lutG, 0, lutGStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutGStageSeg
            } else MemorySegment.NULL
            val lutBSeg = if (lutB != null) {
                MemorySegment.copy(lutB, 0, lutBStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutBStageSeg
            } else MemorySegment.NULL

            val ySeg = ensureOutStage(outYByteCount.toLong())
            val uvSeg = if (outUvPlane != null && outUvByteCount > 0) ensureUvStage(outUvByteCount.toLong()) else MemorySegment.NULL

            val rc = if (fnStats != null && statsRequest != null) {
                writeStatsParams(statsParamSeg, cmosParams.width, cmosParams.height, statsRequest)
                fnStats.invokeExact(
                    ctx,
                    inSeg,
                    filterSeg,
                    opticsParamSeg,
                    cmosParamSeg,
                    lutRSeg,
                    lutGSeg,
                    lutBSeg,
                    ySeg,
                    outYByteCount.toLong(),
                    uvSeg,
                    outUvByteCount.toLong(),
                    ispParamSeg,
                    statsParamSeg,
                    statsOutSeg,
                    outMsSeg,
                ) as Int
            } else if (fn != null) {
                fn.invokeExact(
                    ctx,
                    inSeg,
                    filterSeg,
                    opticsParamSeg,
                    cmosParamSeg,
                    lutRSeg,
                    lutGSeg,
                    lutBSeg,
                    ySeg,
                    outYByteCount.toLong(),
                    uvSeg,
                    outUvByteCount.toLong(),
                    ispParamSeg,
                    outMsSeg,
                ) as Int
            } else {
                -6
            }
            if (rc == FUJI_VK_OK) {
                copyIspReadback(ispParams, ySeg, outYOrPacked, uvSeg, outUvPlane)
                if (fnStats != null && outStatsWords != null) {
                    MemorySegment.copy(statsOutSeg, ValueLayout.JAVA_INT, 0L, outStatsWords, 0, LiveViewIspFusionRequest.STATS_OUTPUT_WORDS)
                }
            }
            rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun uploadScene(
        ctx: MemorySegment,
        sceneKey: Long,
        inRgbaDepth: FloatArray,
        srcPixelCount: Int,
    ): Pair<Int, Double> {
        if (srcPixelCount !in 1..(inRgbaDepth.size / 4)) return FUJI_VK_ERR_INVALID_ARG to 0.0
        return uploadSceneWithStaging(ctx, sceneKey, srcPixelCount) { input ->
            MemorySegment.copy(inRgbaDepth, 0, input, ValueLayout.JAVA_FLOAT, 0L, srcPixelCount * 4)
        }
    }

    internal fun uploadPlanarScene(
        ctx: MemorySegment,
        sceneKey: Long,
        source: LinearRgbImage,
        depthMeters: FloatArray?,
    ): Pair<Int, Double> {
        val count = source.pixelCount
        if (depthMeters != null && depthMeters.size < count) return FUJI_VK_ERR_INVALID_ARG to 0.0
        return uploadSceneWithStaging(ctx, sceneKey, count) { input ->
            val red = source.red
            val green = source.green
            val blue = source.blue
            var first = 0
            while (first < count) {
                // A ByteBuffer view is limited to Int.MAX_VALUE bytes even on 64-bit JVMs.
                val pixels = minOf(count - first, Int.MAX_VALUE / 16)
                val out = input.asSlice(first * 16L, pixels * 16L).asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until pixels) {
                    val src = first + i
                    val dst = i * 4
                    out.put(dst, red[src])
                    out.put(dst + 1, green[src])
                    out.put(dst + 2, blue[src])
                    out.put(dst + 3, if (depthMeters == null) 0f else depthMeters[src])
                }
                first += pixels
            }
        }
    }

    private inline fun uploadSceneWithStaging(
        ctx: MemorySegment,
        sceneKey: Long,
        srcPixelCount: Int,
        writeInput: (MemorySegment) -> Unit,
    ): Pair<Int, Double> {
        val fn = uploadSceneFn ?: return -6 to 0.0
        synchronized(stagingLock) {
            val inSeg = ensureInStage(srcPixelCount * 16L)
            writeInput(inSeg)
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = fn.invokeExact(ctx, sceneKey, inSeg, srcPixelCount.toLong(), outMsSeg) as Int
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun renderCachedSceneOptics(
        ctx: MemorySegment,
        sceneKey: Long,
        outRgba: FloatArray,
        params: OpticsParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
        outFloatCount: Int = outRgba.size,
    ): Pair<Int, Double> {
        val fn = renderCachedSceneOpticsFn ?: return -6 to 0.0
        synchronized(stagingLock) {
            val outputElements = rgbaElements(params.dstWidth, params.dstHeight, outFloatCount, outRgba.size)
            if (outputElements == 0) return FUJI_VK_ERR_INVALID_ARG to 0.0
            val outSeg = ensureOutStage(outputElements.toLong() * 4L)
            val fSeg = if (filterParams != null) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else {
                MemorySegment.NULL
            }
            writeOpticsParams(opticsParamSeg, params)
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = fn.invokeExact(ctx, sceneKey, outSeg, fSeg, opticsParamSeg, outMsSeg) as Int
            if (rc == FUJI_VK_OK) {
                MemorySegment.copy(outSeg, ValueLayout.JAVA_FLOAT, 0L, outRgba, 0, outputElements)
            }
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun renderCachedSceneToBayerDirect(
        ctx: MemorySegment,
        sceneKey: Long,
        outBayerDirect: MemorySegment,
        outByteCount: Int,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
    ): Pair<Int, Double> {
        val fn = renderCachedSceneToBayerFn ?: return -6 to 0.0
        if (!isWritableBayerOutput(outBayerDirect, outByteCount)) return FUJI_VK_ERR_INVALID_ARG to 0.0
        synchronized(stagingLock) {
            val fSeg = if (filterParams != null) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else {
                MemorySegment.NULL
            }
            writeOpticsParams(opticsParamSeg, opticsParams)
            writeCmosParams(cmosParamSeg, cmosParams)
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = fn.invokeExact(
                ctx,
                sceneKey,
                outBayerDirect,
                outByteCount.toLong(),
                fSeg,
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun renderCachedSceneToBayer(
        ctx: MemorySegment,
        sceneKey: Long,
        outBayer: ByteArray,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
        outByteCount: Int = outBayer.size,
    ): Pair<Int, Double> {
        val fn = renderCachedSceneToBayerFn ?: return -6 to 0.0
        synchronized(stagingLock) {
            val bytes = bayerBytes(opticsParams.dstWidth, opticsParams.dstHeight, cmosParams.bitDepth, outByteCount, outBayer.size)
            if (bytes == 0) return FUJI_VK_ERR_INVALID_ARG to 0.0
            val outSeg = ensureOutStage(bytes.toLong())
            val fSeg = if (filterParams != null) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else {
                MemorySegment.NULL
            }
            writeOpticsParams(opticsParamSeg, opticsParams)
            writeCmosParams(cmosParamSeg, cmosParams)
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = fn.invokeExact(
                ctx,
                sceneKey,
                outSeg,
                bytes.toLong(),
                fSeg,
                opticsParamSeg,
                cmosParamSeg,
                outMsSeg,
            ) as Int
            if (rc == FUJI_VK_OK) {
                MemorySegment.copy(outSeg, ValueLayout.JAVA_BYTE, 0L, outBayer, 0, bytes)
            }
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun renderCachedSceneToIspYuv(
        ctx: MemorySegment,
        sceneKey: Long,
        filterParams: FilterStackParamsStruct?,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        lutR: IntArray?,
        lutG: IntArray?,
        lutB: IntArray?,
        outYOrPacked: ByteArray,
        outYByteCount: Int = outYOrPacked.size,
        outUvPlane: ByteArray? = null,
        outUvByteCount: Int = outUvPlane?.size ?: 0,
        ispParams: IspDemosaicParamsStruct,
        statsRequest: LiveViewIspStatsRequest? = null,
        outStatsWords: IntArray? = null,
    ): Pair<Int, Double> {
        if (outYByteCount !in 1..outYOrPacked.size || outUvByteCount !in 0..(outUvPlane?.size ?: 0)) {
            return FUJI_VK_ERR_INVALID_ARG to 0.0
        }
        val wantStats = statsRequest != null && outStatsWords != null && outStatsWords.size >= LiveViewIspFusionRequest.STATS_OUTPUT_WORDS
        val fnStats = if (wantStats) renderCachedSceneToIspYuvWithStatsFn else null
        val fn = if (fnStats != null) null else (renderCachedSceneToIspYuvFn ?: return -6 to 0.0)
        synchronized(stagingLock) {
            val fSeg = if (filterParams != null) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else {
                MemorySegment.NULL
            }
            writeOpticsParams(opticsParamSeg, opticsParams)
            writeCmosParams(cmosParamSeg, cmosParams)
            val lutRSeg = if (lutR != null && lutR.size >= 4096) {
                MemorySegment.copy(lutR, 0, lutRStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutRStageSeg
            } else MemorySegment.NULL
            val lutGSeg = if (lutG != null && lutG.size >= 4096) {
                MemorySegment.copy(lutG, 0, lutGStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutGStageSeg
            } else MemorySegment.NULL
            val lutBSeg = if (lutB != null && lutB.size >= 4096) {
                MemorySegment.copy(lutB, 0, lutBStageSeg, ValueLayout.JAVA_INT, 0L, 4096)
                lutBStageSeg
            } else MemorySegment.NULL
            val ySeg = ensureOutStage(outYByteCount.toLong())
            val uvSeg = if (outUvPlane != null && outUvByteCount > 0) ensureUvStage(outUvByteCount.toLong()) else MemorySegment.NULL
            writeIspParams(ispParamSeg, ispParams)
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = if (fnStats != null && statsRequest != null) {
                writeStatsParams(statsParamSeg, cmosParams.width, cmosParams.height, statsRequest)
                fnStats.invokeExact(
                    ctx,
                    sceneKey,
                    fSeg,
                    opticsParamSeg,
                    cmosParamSeg,
                    lutRSeg,
                    lutGSeg,
                    lutBSeg,
                    ySeg,
                    outYByteCount.toLong(),
                    uvSeg,
                    outUvByteCount.toLong(),
                    ispParamSeg,
                    statsParamSeg,
                    statsOutSeg,
                    outMsSeg,
                ) as Int
            } else if (fn != null) {
                fn.invokeExact(
                    ctx,
                    sceneKey,
                    fSeg,
                    opticsParamSeg,
                    cmosParamSeg,
                    lutRSeg,
                    lutGSeg,
                    lutBSeg,
                    ySeg,
                    outYByteCount.toLong(),
                    uvSeg,
                    outUvByteCount.toLong(),
                    ispParamSeg,
                    outMsSeg,
                ) as Int
            } else {
                -6
            }
            if (rc == FUJI_VK_OK) {
                copyIspReadback(ispParams, ySeg, outYOrPacked, uvSeg, outUvPlane)
                if (fnStats != null && outStatsWords != null) {
                    MemorySegment.copy(statsOutSeg, ValueLayout.JAVA_INT, 0L, outStatsWords, 0, LiveViewIspFusionRequest.STATS_OUTPUT_WORDS)
                }
            }
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun getPipelineStats(ctx: MemorySegment): NativePipelineStats? {
        val fn = getPipelineStatsFn ?: return null
        synchronized(stagingLock) {
            val rc = fn.invokeExact(ctx, pipelineStatsSeg) as Int
            if (rc != FUJI_VK_OK) return null
            return NativePipelineStats(
                pipelineCacheHit = pipelineStatsSeg.get(ValueLayout.JAVA_INT, 0L) != 0,
                specializedPipelineCount = pipelineStatsSeg.get(ValueLayout.JAVA_INT, 4L),
                bufferReallocCount = pipelineStatsSeg.get(ValueLayout.JAVA_INT, 8L),
                hostCachedReadback = pipelineStatsSeg.get(ValueLayout.JAVA_INT, 12L) != 0,
                pipelineCacheBytes = pipelineStatsSeg.get(ValueLayout.JAVA_LONG, 16L),
                pipelineWarmupMs = pipelineStatsSeg.get(ValueLayout.JAVA_DOUBLE, 24L),
                asyncFramesSubmitted = pipelineStatsSeg.get(ValueLayout.JAVA_LONG, 32L),
                asyncFramesCollected = pipelineStatsSeg.get(ValueLayout.JAVA_LONG, 40L),
            )
        }
    }

    fun registerCustomSpv(ctx: MemorySegment, spec: CustomSpirvPassSpec): Pair<Int, Long> {
        val fn = registerCustomSpvFn ?: return FUJI_VK_ERR_UNSUPPORTED_EXT to 0L
        Arena.ofConfined().use { arena ->
            val spvSeg = arena.allocate(spec.spirvBytes.size.toLong().coerceAtLeast(1L), 4L)
            MemorySegment.copy(spec.spirvBytes, 0, spvSeg, ValueLayout.JAVA_BYTE, 0L, spec.spirvBytes.size)
            val epSeg = arena.allocateFrom(spec.entryPoint)
            val outHandle = arena.allocate(ValueLayout.JAVA_LONG)
            val rc = fn.invokeExact(
                ctx,
                spvSeg,
                spec.spirvBytes.size.toLong(),
                epSeg,
                spec.pushConstants.size,
                outHandle,
            ) as Int
            return rc to outHandle.get(ValueLayout.JAVA_LONG, 0L)
        }
    }

    fun setCustomSpvStages(
        ctx: MemorySegment,
        passes: List<CustomSpirvPassSpec>,
        focusM: Float,
        fNumber: Float,
        focalMm: Float,
        frameIndex: Int,
        hasDepth: Boolean,
    ): Int {
        val fn = setCustomSpvStagesFn ?: return if (passes.isEmpty()) FUJI_VK_OK else FUJI_VK_ERR_UNSUPPORTED_EXT
        if (passes.isEmpty()) {
            return fn.invokeExact(ctx, MemorySegment.NULL, 0) as Int
        }
        Arena.ofConfined().use { arena ->
            val arrSeg = arena.allocate(CUSTOM_SPV_STAGE_SPEC_BYTES * passes.size.toLong(), 8L)
            val flags = if (hasDepth) 1 else 0
            for (i in passes.indices) {
                val p = passes[i]
                val spvSeg = arena.allocate(p.spirvBytes.size.toLong().coerceAtLeast(1L), 4L)
                MemorySegment.copy(p.spirvBytes, 0, spvSeg, ValueLayout.JAVA_BYTE, 0L, p.spirvBytes.size)
                val epSeg = arena.allocateFrom(p.entryPoint)
                val uboSeg = if (p.uniformData.isNotEmpty()) {
                    arena.allocate(p.uniformData.size.toLong(), 4L).also {
                        MemorySegment.copy(p.uniformData, 0, it, ValueLayout.JAVA_BYTE, 0L, p.uniformData.size)
                    }
                } else {
                    MemorySegment.NULL
                }
                val pushSeg = if (p.pushConstants.isNotEmpty()) {
                    arena.allocate(p.pushConstants.size.toLong(), 4L).also {
                        MemorySegment.copy(p.pushConstants, 0, it, ValueLayout.JAVA_BYTE, 0L, p.pushConstants.size)
                    }
                } else {
                    MemorySegment.NULL
                }
                val base = i.toLong() * CUSTOM_SPV_STAGE_SPEC_BYTES
                arrSeg.set(ValueLayout.JAVA_INT, base + 0L, p.stage.nativeCode)
                arrSeg.set(ValueLayout.JAVA_INT, base + 4L, p.workgroupSizeX)
                arrSeg.set(ValueLayout.JAVA_INT, base + 8L, p.workgroupSizeY)
                arrSeg.set(ValueLayout.JAVA_INT, base + 12L, p.spirvBytes.size)
                arrSeg.set(ValueLayout.ADDRESS, base + 16L, spvSeg)
                arrSeg.set(ValueLayout.ADDRESS, base + 24L, epSeg)
                arrSeg.set(ValueLayout.ADDRESS, base + 32L, uboSeg)
                arrSeg.set(ValueLayout.JAVA_INT, base + 40L, p.uniformData.size)
                arrSeg.set(ValueLayout.JAVA_INT, base + 44L, p.pushConstants.size)
                arrSeg.set(ValueLayout.ADDRESS, base + 48L, pushSeg)
                arrSeg.set(ValueLayout.JAVA_FLOAT, base + 56L, focusM)
                arrSeg.set(ValueLayout.JAVA_FLOAT, base + 60L, fNumber)
                arrSeg.set(ValueLayout.JAVA_FLOAT, base + 64L, focalMm)
                arrSeg.set(ValueLayout.JAVA_INT, base + 68L, frameIndex)
                arrSeg.set(ValueLayout.JAVA_INT, base + 72L, flags)
                arrSeg.set(ValueLayout.JAVA_INT, base + 76L, 0)
            }
            return fn.invokeExact(ctx, arrSeg, passes.size) as Int
        }
    }

    fun clearCustomSpvStages(ctx: MemorySegment) {
        val fn = setCustomSpvStagesFn ?: return
        fn.invokeExact(ctx, MemorySegment.NULL, 0) as Int
    }

    fun getCustomSpvCacheStats(ctx: MemorySegment): CustomSpirvCacheStats? {
        val fn = customSpvCacheStatsFn ?: return null
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(CUSTOM_SPV_CACHE_STATS_BYTES, 8L)
            val rc = fn.invokeExact(ctx, seg) as Int
            if (rc != FUJI_VK_OK) return null
            return CustomSpirvCacheStats(
                capacity = seg.get(ValueLayout.JAVA_INT, 0L),
                entries = seg.get(ValueLayout.JAVA_INT, 4L),
                hits = seg.get(ValueLayout.JAVA_LONG, 8L),
                misses = seg.get(ValueLayout.JAVA_LONG, 16L),
                evictions = seg.get(ValueLayout.JAVA_LONG, 24L),
            )
        }
    }

    fun submitAsyncToBayer(
        ctx: MemorySegment,
        sceneKey: Long,
        inRgbaDepth: FloatArray?,
        opticsParams: OpticsParamsStruct,
        cmosParams: CmosParamsStruct,
        filterParams: FilterStackParamsStruct? = null,
        inFloatCount: Int = inRgbaDepth?.size ?: 0,
    ): Pair<Int, Long> {
        val fn = submitAsyncToBayerFn ?: return -6 to 0L
        synchronized(stagingLock) {
            if (inFloatCount < 0 || (inRgbaDepth == null && inFloatCount != 0)) return FUJI_VK_ERR_INVALID_ARG to 0L
            val inSeg = if (inRgbaDepth != null && inFloatCount > 0) {
                val elements = rgbaElements(opticsParams.srcWidth, opticsParams.srcHeight, inFloatCount, inRgbaDepth.size)
                if (elements == 0) return FUJI_VK_ERR_INVALID_ARG to 0L
                val seg = ensureInStage(elements.toLong() * 4L)
                MemorySegment.copy(inRgbaDepth, 0, seg, ValueLayout.JAVA_FLOAT, 0L, elements)
                seg
            } else {
                MemorySegment.NULL
            }
            val fSeg = if (filterParams != null) {
                writeFilterParams(filterParamSeg, filterParams)
                filterParamSeg
            } else {
                MemorySegment.NULL
            }
            writeOpticsParams(opticsParamSeg, opticsParams)
            writeCmosParams(cmosParamSeg, cmosParams)
            outTicketSeg.set(ValueLayout.JAVA_LONG, 0L, 0L)
            val rc = fn.invokeExact(
                ctx,
                sceneKey,
                inSeg,
                fSeg,
                opticsParamSeg,
                cmosParamSeg,
                outTicketSeg,
            ) as Int
            val ticket = outTicketSeg.get(ValueLayout.JAVA_LONG, 0L)
            if (rc == FUJI_VK_OK && ticket != 0L) {
                val expectedBytes = bayerBytes(
                    opticsParams.dstWidth,
                    opticsParams.dstHeight,
                    cmosParams.bitDepth,
                    Int.MAX_VALUE,
                    Int.MAX_VALUE,
                )
                if (expectedBytes > 0) {
                    asyncTicketBytes[AsyncTicketKey(ctx.address(), ticket)] = expectedBytes
                }
            }
            return rc to ticket
        }
    }

    fun collectAsyncBayer(
        ctx: MemorySegment,
        ticket: Long,
        outBayer: ByteArray,
        outByteCount: Int = outBayer.size,
    ): Pair<Int, Double> {
        val fn = collectAsyncBayerFn ?: return -6 to 0.0
        synchronized(stagingLock) {
            if (outByteCount !in 1..outBayer.size) return FUJI_VK_ERR_INVALID_ARG to 0.0
            val key = AsyncTicketKey(ctx.address(), ticket)
            val expectedBytes = asyncTicketBytes[key]
            val transferBytes = if (expectedBytes != null && expectedBytes <= outByteCount) expectedBytes else outByteCount
            val outSeg = ensureOutStage(transferBytes.toLong())
            outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
            val rc = fn.invokeExact(
                ctx,
                ticket,
                outSeg,
                transferBytes.toLong(),
                outMsSeg,
            ) as Int
            if (rc == FUJI_VK_OK) {
                asyncTicketBytes.remove(key)
                MemorySegment.copy(outSeg, ValueLayout.JAVA_BYTE, 0L, outBayer, 0, transferBytes)
            } else if (rc != FUJI_VK_ERR_INVALID_ARG) {
                asyncTicketBytes.remove(key)
            }
            return rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
        }
    }

    fun validateExternalFrameDescriptor(ctx: MemorySegment, descriptor: ExternalGpuFrameDescriptor): Int {
        Arena.ofConfined().use { arena ->
            val descSeg = arena.allocate(EXT_FRAME_DESC_BYTES, 8L)
            descSeg.set(ValueLayout.JAVA_INT, 0L, descriptor.handleType.nativeCode)
            descSeg.set(ValueLayout.JAVA_INT, 4L, descriptor.width)
            descSeg.set(ValueLayout.JAVA_INT, 8L, descriptor.height)
            descSeg.set(ValueLayout.JAVA_INT, 12L, descriptor.colorPitchBytes)
            descSeg.set(ValueLayout.JAVA_INT, 16L, descriptor.depthPitchBytes)
            descSeg.set(ValueLayout.JAVA_INT, 20L, descriptor.colorFormat.vkFormat)
            descSeg.set(ValueLayout.JAVA_INT, 24L, descriptor.depthFormat.vkFormat)
            descSeg.set(ValueLayout.JAVA_INT, 28L, descriptor.flags)
            descSeg.set(ValueLayout.JAVA_LONG, 32L, descriptor.colorHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 40L, descriptor.depthHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 48L, descriptor.waitSemaphoreHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 56L, descriptor.signalSemaphoreHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 64L, descriptor.timelineWaitValue)
            descSeg.set(ValueLayout.JAVA_LONG, 72L, descriptor.timelineSignalValue)
            descSeg.set(ValueLayout.JAVA_LONG, 80L, descriptor.colorMemorySizeBytes)
            descSeg.set(ValueLayout.JAVA_LONG, 88L, descriptor.depthMemorySizeBytes)
            return validateExternalFrameDescFn.invokeExact(ctx, descSeg) as Int
        }
    }

    fun validateExternalFrameImport(ctx: MemorySegment, handle: ExternalGpuFrameHandle): Int {
        Arena.ofConfined().use { arena ->
            val descSeg = arena.allocate(EXT_FRAME_DESC_BYTES, 8L)
            descSeg.set(ValueLayout.JAVA_INT, 0L, handle.handleType.nativeCode)
            descSeg.set(ValueLayout.JAVA_INT, 4L, handle.width)
            descSeg.set(ValueLayout.JAVA_INT, 8L, handle.height)
            descSeg.set(ValueLayout.JAVA_INT, 12L, handle.colorPitchBytes)
            descSeg.set(ValueLayout.JAVA_INT, 16L, handle.depthPitchBytes)
            descSeg.set(ValueLayout.JAVA_INT, 20L, handle.colorVkFormat)
            descSeg.set(ValueLayout.JAVA_INT, 24L, handle.depthVkFormat)
            descSeg.set(ValueLayout.JAVA_INT, 28L, handle.flags)
            descSeg.set(ValueLayout.JAVA_LONG, 32L, handle.colorHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 40L, handle.depthHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 48L, handle.waitSemaphoreHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 56L, handle.signalSemaphoreHandle)
            descSeg.set(ValueLayout.JAVA_LONG, 64L, handle.timelineWaitValue)
            descSeg.set(ValueLayout.JAVA_LONG, 72L, handle.timelineSignalValue)
            descSeg.set(ValueLayout.JAVA_LONG, 80L, handle.colorMemorySizeBytes)
            descSeg.set(ValueLayout.JAVA_LONG, 88L, handle.depthMemorySizeBytes)
            val pOut = arena.allocate(ValueLayout.ADDRESS)
            val rc = importExternalFrameFn.invokeExact(ctx, descSeg, pOut) as Int
            if (rc == FUJI_VK_OK) {
                val framePtr = pOut.get(ValueLayout.ADDRESS, 0)
                if (framePtr != MemorySegment.NULL) {
                    releaseExternalFrameFn.invokeExact(ctx, framePtr)
                }
            }
            return rc
        }
    }

    override fun close() {
        synchronized(stagingLock) {
            asyncTicketBytes.clear()
            inStageSeg = MemorySegment.NULL
            outStageSeg = MemorySegment.NULL
            uvStageSeg = MemorySegment.NULL
            inStageArena?.let { if (it.scope().isAlive) it.close() }
            outStageArena?.let { if (it.scope().isAlive) it.close() }
            uvStageArena?.let { if (it.scope().isAlive) it.close() }
            inStageArena = null
            outStageArena = null
            uvStageArena = null
            if (fixedStageArena.scope().isAlive) {
                fixedStageArena.close()
            }
        }
        if (libraryArena.scope().isAlive) {
            libraryArena.close()
        }
    }

    data class IspDemosaicParamsStruct(
        val srcWidth: Int,
        val srcHeight: Int,
        val dstWidth: Int,
        val dstHeight: Int,
        val is16Bit: Boolean = true,
        val outputFormat: Int = ISP_FMT_UYVY422,
        val dstStrideBytes: Int = 0,
        val flags: Int = 0,
        val offX: Int = 0,
        val offY: Int = 0,
        val fitW: Int = 0,
        val fitH: Int = 0,
        val gainR1024: Int = 1024,
        val gainG1024: Int = 1024,
        val gainB1024: Int = 1024,
        val yuvShift: Int = 10,
        val ccm: IntArray? = null,
        val yuvMat: IntArray? = null,
        val peakingThreshold: Int = 0,
        val peakingHiY: Int = 250,
        val peakingHiCb: Int = 0x80,
        val peakingHiCr: Int = 0x80,
    )

    data class FilterStackParamsStruct(
        val width: Int,
        val height: Int,
        val enableFlags: Int,
        val starPoints: Int,
        val ndTransmission: Float,
        val gndTopTransmission: Float,
        val gndHorizonY: Float,
        val gndFeather: Float,
        val gndSinAngle: Float,
        val gndCosAngle: Float,
        val mistStrength: Float,
        val mistThreshold: Float,
        val mistRadiusPx: Float,
        val starStrength: Float,
        val starThreshold: Float,
        val starRadiusPx: Float,
        val starAngleRad: Float,
        val mistWarmth: Float,
        val uvVeilingGlare: Float = 0.0f,
        val uvGhostIntensity: Float = 0.0f,
        val uvSoftness: Float = 0.0f,
        val uvTintR: Float = 1.0f,
        val uvTintG: Float = 1.0f,
        val uvTintB: Float = 1.0f,
        val uvGhostRadiusPx: Float = 8.0f,
    )

    data class OpticsParamsStruct(
        val srcWidth: Int,
        val srcHeight: Int,
        val dstWidth: Int,
        val dstHeight: Int,
        val k1: Float,
        val k2: Float,
        val p1: Float,
        val p2: Float,
        val caRedScale: Float,
        val caBlueScale: Float,
        val cos4Power: Float,
        val mechVignetting: Float,
        val focalLengthMm: Float,
        val sensorWidthMm: Float,
        val focusDistanceM: Float,
        val fNumber: Float,
        val invZoom: Float,
        val ibisShiftX: Float,
        val ibisShiftY: Float,
        val ibisCosRoll: Float,
        val ibisSinRoll: Float,
        val maxBokehPx: Float,
        val bladeRoundness: Float,
        val catsEye: Float,
        val onionRing: Float,
        val rimHighlight: Float,
        val planarDepthM: Float,
        val imageCircleMm: Float,
        val pupilShape: Int,
        val bladeCount: Int,
        val borderClamp: Int,
        val flags: Int,
    )

    data class CmosParamsStruct(
        val width: Int,
        val height: Int,
        val bayerPattern: Int,
        val bitDepth: Int,
        val maxCode: Int,
        val blackLevel: Int,
        val enableNoise: Int,
        val iso: Int,
        val baseIso: Float,
        val fullWellElectrons: Float,
        val opticalExposureScale: Float,
        val readNoiseBaseElectrons: Float,
        val readNoiseIsoSlope: Float,
        val seedLo: Int,
        val seedHi: Int,
        val frameIndexLo: Int,
    )

    companion object {
        const val FUJI_VK_ERR_INVALID_ARG = -1

        private fun bayerBytes(width: Int, height: Int, bits: Int, declared: Int, capacity: Int): Int {
            if (width <= 0 || height <= 0 || bits !in 8..16 || declared !in 1..capacity) return 0
            val bytes = width.toLong() * height * (if (bits == 8) 1 else 2)
            return if (bytes <= declared) bytes.toInt() else 0
        }

        private fun copyIspReadback(
            params: IspDemosaicParamsStruct, y: MemorySegment, yOutput: ByteArray,
            uv: MemorySegment, uvOutput: ByteArray?,
        ) {
            val semiPlanar = params.outputFormat == ISP_FMT_NV16 || params.outputFormat == ISP_FMT_NV12
            val rowBytes = if (semiPlanar) params.dstWidth else params.dstWidth * 2
            val stride = maxOf(params.dstStrideBytes, rowBytes)
            fun copyRows(from: MemorySegment, to: ByteArray, rows: Int) {
                if (rowBytes == stride) {
                    MemorySegment.copy(from, ValueLayout.JAVA_BYTE, 0L, to, 0, rows * rowBytes)
                } else {
                    for (row in 0 until rows) {
                        val offset = row * stride
                        MemorySegment.copy(from, ValueLayout.JAVA_BYTE, offset.toLong(), to, offset, rowBytes)
                    }
                }
            }
            copyRows(y, yOutput, params.dstHeight)
            if (semiPlanar && uvOutput != null) {
                val chromaRows = if (params.outputFormat == ISP_FMT_NV12) (params.dstHeight + 1) / 2 else params.dstHeight
                copyRows(uv, uvOutput, chromaRows)
            }
        }

        // These native entry points receive pointers and image dimensions, without float counts.
        // Bound the pixel count before multiplying by the four elements per pixel.
        private fun rgbaElements(width: Int, height: Int, declared: Int, capacity: Int): Int {
            if (width <= 0 || height <= 0 || declared !in 1..capacity) return 0
            val pixels = width.toLong() * height
            if (pixels > declared / 4) return 0
            return pixels.toInt() * 4
        }

        // A downcall passes a pointer and byte count separately. The FFM linker pins the
        // segment for the call but cannot validate native writes against the Java slice.
        private fun isWritableBayerOutput(segment: MemorySegment, byteCount: Int): Boolean =
            byteCount > 0 && byteCount.toLong() <= segment.byteSize() &&
                segment.isNative && !segment.isReadOnly && segment.address() != 0L &&
                segment.scope().isAlive && segment.isAccessibleBy(Thread.currentThread())

        const val FUJI_VK_OK = 0
        const val FUJI_VK_SELECT_PREFER_DISCRETE = 1
        const val FUJI_VK_SELECT_ALLOW_INTEGRATED = 2
        const val FUJI_VK_SELECT_ALLOW_SOFTWARE = 4
        const val FUJI_VK_SELECT_DEFAULT = FUJI_VK_SELECT_PREFER_DISCRETE or FUJI_VK_SELECT_ALLOW_INTEGRATED

        const val SHADER_OPTICS_WARP = 0
        const val SHADER_PUPIL_BOKEH = 1
        const val SHADER_CMOS_BAYER = 2
        const val SHADER_OPTICAL_FILTER_STACK = 3
        const val SHADER_ISP_DEMOSAIC = 4
        const val SHADER_ISP_PEAKING = 5

        const val ISP_FMT_UYVY422 = 0
        const val ISP_FMT_NV16 = 1
        const val ISP_FMT_NV12 = 2
        const val ISP_FMT_YYUV422 = 3

        const val ISP_FLAG_HAS_CCM = 1
        const val ISP_FLAG_HAS_YUV_MAT = 2
        const val ISP_FLAG_MONO_MODE = 4
        const val ISP_FLAG_PEAKING_ENABLED = 8
        const val ISP_FLAG_SRC_CROP = 16

        private const val DEVICE_INFO_BYTES = 416L
        private const val FILTER_PARAMS_BYTES = 112L
        private const val OPTICS_PARAMS_BYTES = 128L
        private const val CMOS_PARAMS_BYTES = 64L
        private const val ISP_PARAMS_BYTES = 160L
        private const val EXT_FRAME_DESC_BYTES = 96L
        private const val ERR_BUF_BYTES = 512L

        fun discoverCandidates(explicitPath: Path? = null): List<Path> {
            if (explicitPath != null) return listOf(explicitPath)
            val out = ArrayList<Path>(20)
            System.getProperty("fuji.vulkan.optics.library")?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(Paths.get(it)) }
            System.getenv("FUJI_VULKAN_OPTICS_LIBRARY")?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(Paths.get(it)) }

            val isWindows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
            val libName = if (isWindows) "fuji_vulkan_optics.dll" else "libfuji_vulkan_optics.so"
            val buildSub = if (isWindows) "build-win64" else "build-linux"
            val cacheSub = if (isWindows) "vulkan-optics-win64" else "vulkan-optics-linux"

            System.getProperty("fuji.home")?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
                out.add(Paths.get(home).toAbsolutePath().normalize().resolve("runtime/native/$libName"))
            }

            runCatching {
                val loc = VulkanNativeOpticsBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
                if (loc != null) {
                    var dir: Path? = Paths.get(loc).toAbsolutePath().normalize()
                    if (dir != null && Files.isRegularFile(dir)) dir = dir.parent
                    repeat(4) {
                        val current = dir
                        if (current != null) {
                            out.add(current.resolve("runtime/native/$libName"))
                            out.add(current.resolve(".tools/vulkan-optics/$buildSub/$libName"))
                            dir = current.parent
                        }
                    }
                }
            }

            var cur: Path? = Paths.get("").toAbsolutePath().normalize()
            repeat(5) {
                val current = cur
                if (current != null) {
                    out.add(current.resolve("runtime/native/$libName"))
                    out.add(current.resolve(".tools/vulkan-optics/$buildSub/$libName"))
                    cur = current.parent
                }
            }
            out.add(Paths.get("/opt/fuji-cache/vulkan-optics/$buildSub/$libName"))
            out.add(Paths.get("/opt/fuji-cache/$cacheSub/$libName"))
            out.add(Paths.get("/opt/fuji-cache/vulkan-optics/$libName"))
            return out.distinct()
        }

        fun tryLoad(explicitPath: Path? = null): Result<VulkanNativeOpticsBridge> {
            val candidates = discoverCandidates(explicitPath)
            val found = candidates.firstOrNull { Files.isRegularFile(it) }
                ?: return Result.failure(
                    IllegalStateException("Native Vulkan optics library not found in candidates: ${candidates.take(4)}"),
                )
            val arena = Arena.ofShared()
            return try {
                val sym = SymbolLookup.libraryLookup(found, arena)
                val linker = Linker.nativeLinker()
                fun req(name: String, desc: FunctionDescriptor): MethodHandle {
                    val addr = sym.find(name).orElseThrow {
                        IllegalStateException("Missing symbol '$name' in $found")
                    }
                    return linker.downcallHandle(addr, desc)
                }
                val bridge = VulkanNativeOpticsBridge(
                    libraryPath = found,
                    libraryArena = arena,
                    abiVersionFn = req("fuji_vk_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                    supportsPupilTransform = sym.find("fuji_vk_supports_pupil_transform").isPresent,
                    enumerateDevicesFn = sym.find("fuji_vk_enumerate_devices").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                            ),
                        )
                    }.orElse(null),
                    createForDeviceFn = sym.find("fuji_vk_create_for_device").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                            ),
                        )
                    }.orElse(null),
                    createWithCacheFn = sym.find("fuji_vk_create_with_cache").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                            ),
                        )
                    }.orElse(null),
                    setPipelineCachePathFn = sym.find("fuji_vk_set_pipeline_cache_path").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                        )
                    }.orElse(null),
                    getPipelineStatsFn = sym.find("fuji_vk_get_pipeline_stats").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    createFn = req(
                        "fuji_vk_create",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                        ),
                    ),
                    destroyFn = req("fuji_vk_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                    getDeviceInfoFn = req(
                        "fuji_vk_get_device_info",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    lastErrorFn = req("fuji_vk_last_error", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    getSpirvWordsFn = req(
                        "fuji_vk_get_spirv_words",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    renderOpticsFn = req(
                        "fuji_vk_render_optics",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderOpticsFilteredFn = req(
                        "fuji_vk_render_optics_filtered",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderCmosBayerFn = req(
                        "fuji_vk_render_cmos_bayer",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderOpticsToBayerFn = req(
                        "fuji_vk_render_optics_to_bayer",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderOpticsToBayerFilteredFn = req(
                        "fuji_vk_render_optics_to_bayer_filtered",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    ispDemosaicFn = req(
                        "fuji_vk_isp_demosaic",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderOpticsToIspYuvFn = sym.find("fuji_vk_render_optics_to_isp_yuv").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    renderOpticsToIspYuvWithStatsFn = sym.find("fuji_vk_render_optics_to_isp_yuv_with_stats").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    uploadSceneFn = sym.find("fuji_vk_upload_scene").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    renderCachedSceneOpticsFn = sym.find("fuji_vk_render_cached_scene_optics").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    renderCachedSceneToBayerFn = sym.find("fuji_vk_render_cached_scene_to_bayer").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    renderCachedSceneToIspYuvFn = sym.find("fuji_vk_render_cached_scene_to_isp_yuv").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    renderCachedSceneToIspYuvWithStatsFn = sym.find("fuji_vk_render_cached_scene_to_isp_yuv_with_stats").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    submitAsyncToBayerFn = sym.find("fuji_vk_submit_async_to_bayer").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    collectAsyncBayerFn = sym.find("fuji_vk_collect_async_bayer").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    validateExternalFrameDescFn = req(
                        "fuji_vk_validate_external_frame_desc",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    importExternalFrameFn = req(
                        "fuji_vk_import_external_frame",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    releaseExternalFrameFn = req(
                        "fuji_vk_release_external_frame",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    registerCustomSpvFn = sym.find("fuji_vk_register_custom_spv").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    setCustomSpvStagesFn = sym.find("fuji_vk_set_custom_spv_stages").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                            ),
                        )
                    }.orElse(null),
                    customSpvCacheStatsFn = sym.find("fuji_vk_custom_spv_cache_stats").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                    runCustomSpvPassFn = sym.find("fuji_vk_run_custom_spv_pass").map { addr ->
                        linker.downcallHandle(
                            addr,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                            ),
                        )
                    }.orElse(null),
                )
                val abi = bridge.abiVersion()
                require(abi == 1) { "Unsupported fuji_vulkan_optics ABI version $abi (expected 1)" }
                Result.success(bridge)
            } catch (t: Throwable) {
                runCatching { arena.close() }
                Result.failure(t)
            }
        }

        private const val MAX_ENUMERATED_DEVICES = 32
        private const val FUJI_VK_ERR_NO_DEVICE = -3
        private const val FUJI_VK_ERR_UNSUPPORTED_EXT = -6
        private const val PIPELINE_STATS_BYTES = 48L
        private const val CUSTOM_SPV_STAGE_SPEC_BYTES = 80L
        private const val CUSTOM_SPV_CACHE_STATS_BYTES = 32L
        private const val STATS_PARAMS_BYTES = 96L
        private const val STATS_OUTPUT_BYTES = LiveViewIspFusionRequest.STATS_OUTPUT_WORDS * 4L

        private fun writeStatsParams(
            seg: MemorySegment,
            width: Int,
            height: Int,
            req: LiveViewIspStatsRequest,
        ) {
            seg.fill(0.toByte())
            seg.setAtIndex(ValueLayout.JAVA_INT, 0, width)
            seg.setAtIndex(ValueLayout.JAVA_INT, 1, height)
            seg.setAtIndex(ValueLayout.JAVA_INT, 2, 3) // FUJI_VK_STATS_FMT_BAYER_RGGB_U32_12
            seg.setAtIndex(ValueLayout.JAVA_INT, 3, req.flags)
            seg.setAtIndex(ValueLayout.JAVA_INT, 4, req.viewX)
            seg.setAtIndex(ValueLayout.JAVA_INT, 5, req.viewY)
            seg.setAtIndex(ValueLayout.JAVA_INT, 6, req.viewW)
            seg.setAtIndex(ValueLayout.JAVA_INT, 7, req.viewH)
            seg.setAtIndex(ValueLayout.JAVA_INT, 8, req.gridCols.coerceIn(1, 16))
            seg.setAtIndex(ValueLayout.JAVA_INT, 9, req.gridRows.coerceIn(1, 16))
            seg.setAtIndex(ValueLayout.JAVA_INT, 10, req.stepQuad.coerceAtLeast(1))
            seg.setAtIndex(ValueLayout.JAVA_INT, 11, req.awbHighlightThresh10.coerceIn(1, 1024))
            seg.setAtIndex(ValueLayout.JAVA_INT, 12, req.afRoiX0)
            seg.setAtIndex(ValueLayout.JAVA_INT, 13, req.afRoiY0)
            seg.setAtIndex(ValueLayout.JAVA_INT, 14, req.afRoiX1)
            seg.setAtIndex(ValueLayout.JAVA_INT, 15, req.afRoiY1)
        }

        fun enumerateAvailableDevices(explicitPath: Path? = null): List<NativeDeviceInfo> {
            val loaded = tryLoad(explicitPath).getOrNull() ?: return emptyList()
            return try {
                loaded.enumerateDevices()
            } finally {
                runCatching { loaded.close() }
            }
        }

        private fun parseDeviceInfo(seg: MemorySegment): NativeDeviceInfo = NativeDeviceInfo(
            abiVersion = seg.get(ValueLayout.JAVA_INT, 0L),
            apiVersion = seg.get(ValueLayout.JAVA_INT, 4L),
            vendorId = seg.get(ValueLayout.JAVA_INT, 8L),
            deviceId = seg.get(ValueLayout.JAVA_INT, 12L),
            deviceType = seg.get(ValueLayout.JAVA_INT, 16L),
            computeQueueFamily = seg.get(ValueLayout.JAVA_INT, 20L),
            externalMemoryCaps = seg.get(ValueLayout.JAVA_INT, 24L),
            deviceName = readCString(seg, 32L, 256),
            loaderName = readCString(seg, 288L, 128),
            deviceIndex = seg.get(ValueLayout.JAVA_INT, 28L),
        )

        private fun writeIspParams(seg: MemorySegment, p: IspDemosaicParamsStruct) {
            seg.set(ValueLayout.JAVA_INT, 0L, p.srcWidth)
            seg.set(ValueLayout.JAVA_INT, 4L, p.srcHeight)
            seg.set(ValueLayout.JAVA_INT, 8L, p.dstWidth)
            seg.set(ValueLayout.JAVA_INT, 12L, p.dstHeight)
            seg.set(ValueLayout.JAVA_INT, 16L, if (p.is16Bit) 1 else 0)
            seg.set(ValueLayout.JAVA_INT, 20L, p.outputFormat)
            seg.set(ValueLayout.JAVA_INT, 24L, p.dstStrideBytes)
            seg.set(ValueLayout.JAVA_INT, 28L, p.flags)
            seg.set(ValueLayout.JAVA_INT, 32L, p.offX)
            seg.set(ValueLayout.JAVA_INT, 36L, p.offY)
            seg.set(ValueLayout.JAVA_INT, 40L, p.fitW)
            seg.set(ValueLayout.JAVA_INT, 44L, p.fitH)
            seg.set(ValueLayout.JAVA_INT, 48L, p.gainR1024)
            seg.set(ValueLayout.JAVA_INT, 52L, p.gainG1024)
            seg.set(ValueLayout.JAVA_INT, 56L, p.gainB1024)
            seg.set(ValueLayout.JAVA_INT, 60L, p.yuvShift)
            for (i in 0 until 9) {
                seg.set(ValueLayout.JAVA_INT, 64L + i * 4L, p.ccm?.getOrNull(i) ?: 0)
                seg.set(ValueLayout.JAVA_INT, 100L + i * 4L, p.yuvMat?.getOrNull(i) ?: 0)
            }
            seg.set(ValueLayout.JAVA_INT, 136L, p.peakingThreshold)
            seg.set(ValueLayout.JAVA_INT, 140L, p.peakingHiY)
            seg.set(ValueLayout.JAVA_INT, 144L, p.peakingHiCb)
            seg.set(ValueLayout.JAVA_INT, 148L, p.peakingHiCr)
            seg.set(ValueLayout.JAVA_INT, 152L, 0)
            seg.set(ValueLayout.JAVA_INT, 156L, 0)
        }

        private fun writeFilterParams(seg: MemorySegment, p: FilterStackParamsStruct) {
            seg.set(ValueLayout.JAVA_INT, 0L, p.width)
            seg.set(ValueLayout.JAVA_INT, 4L, p.height)
            seg.set(ValueLayout.JAVA_INT, 8L, p.enableFlags)
            seg.set(ValueLayout.JAVA_INT, 12L, p.starPoints)
            seg.set(ValueLayout.JAVA_FLOAT, 16L, p.ndTransmission)
            seg.set(ValueLayout.JAVA_FLOAT, 20L, p.gndTopTransmission)
            seg.set(ValueLayout.JAVA_FLOAT, 24L, p.gndHorizonY)
            seg.set(ValueLayout.JAVA_FLOAT, 28L, p.gndFeather)
            seg.set(ValueLayout.JAVA_FLOAT, 32L, p.gndSinAngle)
            seg.set(ValueLayout.JAVA_FLOAT, 36L, p.gndCosAngle)
            seg.set(ValueLayout.JAVA_FLOAT, 40L, p.mistStrength)
            seg.set(ValueLayout.JAVA_FLOAT, 44L, p.mistThreshold)
            seg.set(ValueLayout.JAVA_FLOAT, 48L, p.mistRadiusPx)
            seg.set(ValueLayout.JAVA_FLOAT, 52L, p.starStrength)
            seg.set(ValueLayout.JAVA_FLOAT, 56L, p.starThreshold)
            seg.set(ValueLayout.JAVA_FLOAT, 60L, p.starRadiusPx)
            seg.set(ValueLayout.JAVA_FLOAT, 64L, p.starAngleRad)
            seg.set(ValueLayout.JAVA_FLOAT, 68L, p.mistWarmth)
            seg.set(ValueLayout.JAVA_FLOAT, 72L, p.uvVeilingGlare)
            seg.set(ValueLayout.JAVA_FLOAT, 76L, p.uvGhostIntensity)
            seg.set(ValueLayout.JAVA_FLOAT, 80L, p.uvSoftness)
            seg.set(ValueLayout.JAVA_FLOAT, 84L, p.uvTintR)
            seg.set(ValueLayout.JAVA_FLOAT, 88L, p.uvTintG)
            seg.set(ValueLayout.JAVA_FLOAT, 92L, p.uvTintB)
            seg.set(ValueLayout.JAVA_FLOAT, 96L, p.uvGhostRadiusPx)
            seg.set(ValueLayout.JAVA_INT, 100L, 0)
            seg.set(ValueLayout.JAVA_INT, 104L, 0)
            seg.set(ValueLayout.JAVA_INT, 108L, 0)
        }

        private fun writeOpticsParams(seg: MemorySegment, p: OpticsParamsStruct) {
            seg.set(ValueLayout.JAVA_INT, 0L, p.srcWidth)
            seg.set(ValueLayout.JAVA_INT, 4L, p.srcHeight)
            seg.set(ValueLayout.JAVA_INT, 8L, p.dstWidth)
            seg.set(ValueLayout.JAVA_INT, 12L, p.dstHeight)
            seg.set(ValueLayout.JAVA_FLOAT, 16L, p.k1)
            seg.set(ValueLayout.JAVA_FLOAT, 20L, p.k2)
            seg.set(ValueLayout.JAVA_FLOAT, 24L, p.p1)
            seg.set(ValueLayout.JAVA_FLOAT, 28L, p.p2)
            seg.set(ValueLayout.JAVA_FLOAT, 32L, p.caRedScale)
            seg.set(ValueLayout.JAVA_FLOAT, 36L, p.caBlueScale)
            seg.set(ValueLayout.JAVA_FLOAT, 40L, p.cos4Power)
            seg.set(ValueLayout.JAVA_FLOAT, 44L, p.mechVignetting)
            seg.set(ValueLayout.JAVA_FLOAT, 48L, p.focalLengthMm)
            seg.set(ValueLayout.JAVA_FLOAT, 52L, p.sensorWidthMm)
            seg.set(ValueLayout.JAVA_FLOAT, 56L, p.focusDistanceM)
            seg.set(ValueLayout.JAVA_FLOAT, 60L, p.fNumber)
            seg.set(ValueLayout.JAVA_FLOAT, 64L, p.invZoom)
            seg.set(ValueLayout.JAVA_FLOAT, 68L, p.ibisShiftX)
            seg.set(ValueLayout.JAVA_FLOAT, 72L, p.ibisShiftY)
            seg.set(ValueLayout.JAVA_FLOAT, 76L, p.ibisCosRoll)
            seg.set(ValueLayout.JAVA_FLOAT, 80L, p.ibisSinRoll)
            seg.set(ValueLayout.JAVA_FLOAT, 84L, p.maxBokehPx)
            seg.set(ValueLayout.JAVA_FLOAT, 88L, p.bladeRoundness)
            seg.set(ValueLayout.JAVA_FLOAT, 92L, p.catsEye)
            seg.set(ValueLayout.JAVA_FLOAT, 96L, p.onionRing)
            seg.set(ValueLayout.JAVA_FLOAT, 100L, p.rimHighlight)
            seg.set(ValueLayout.JAVA_FLOAT, 104L, p.planarDepthM)
            seg.set(ValueLayout.JAVA_FLOAT, 108L, p.imageCircleMm)
            seg.set(ValueLayout.JAVA_INT, 112L, p.pupilShape)
            seg.set(ValueLayout.JAVA_INT, 116L, p.bladeCount)
            seg.set(ValueLayout.JAVA_INT, 120L, p.borderClamp)
            seg.set(ValueLayout.JAVA_INT, 124L, p.flags)
        }

        private fun writeCmosParams(seg: MemorySegment, p: CmosParamsStruct) {
            seg.set(ValueLayout.JAVA_INT, 0L, p.width)
            seg.set(ValueLayout.JAVA_INT, 4L, p.height)
            seg.set(ValueLayout.JAVA_INT, 8L, p.bayerPattern)
            seg.set(ValueLayout.JAVA_INT, 12L, p.bitDepth)
            seg.set(ValueLayout.JAVA_INT, 16L, p.maxCode)
            seg.set(ValueLayout.JAVA_INT, 20L, p.blackLevel)
            seg.set(ValueLayout.JAVA_INT, 24L, p.enableNoise)
            seg.set(ValueLayout.JAVA_INT, 28L, p.iso)
            seg.set(ValueLayout.JAVA_FLOAT, 32L, p.baseIso)
            seg.set(ValueLayout.JAVA_FLOAT, 36L, p.fullWellElectrons)
            seg.set(ValueLayout.JAVA_FLOAT, 40L, p.opticalExposureScale)
            seg.set(ValueLayout.JAVA_FLOAT, 44L, p.readNoiseBaseElectrons)
            seg.set(ValueLayout.JAVA_FLOAT, 48L, p.readNoiseIsoSlope)
            seg.set(ValueLayout.JAVA_INT, 52L, p.seedLo)
            seg.set(ValueLayout.JAVA_INT, 56L, p.seedHi)
            seg.set(ValueLayout.JAVA_INT, 60L, p.frameIndexLo)
        }

        private fun readCString(seg: MemorySegment, offset: Long, maxBytes: Int): String {
            val buf = ByteArray(maxBytes)
            var len = 0
            while (len < maxBytes) {
                val b = seg.get(ValueLayout.JAVA_BYTE, offset + len.toLong())
                if (b.toInt() == 0) break
                buf[len++] = b
            }
            return String(buf, 0, len, Charsets.UTF_8)
        }
    }
}
