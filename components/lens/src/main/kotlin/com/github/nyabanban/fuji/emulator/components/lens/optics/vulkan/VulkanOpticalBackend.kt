package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.ExternalGpuFrameDescriptor
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.BorderMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseModel
import com.github.nyabanban.fuji.emulator.components.lens.optics.CpuOpticalPipeline
import com.github.nyabanban.fuji.emulator.components.lens.optics.IbisShiftState
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalFrameWorkspace
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import com.github.nyabanban.fuji.emulator.components.lens.optics.SamplingMode
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Production Vulkan 1.1 Compute optical backend (`VULKAN-W1`) with automatic transparent fallback
 * to [CpuOpticalPipeline] + [CmosNoiseModel] and explicit `WARN` (`[FALLBACK][vulkan] ...`,
 * bridged to SLF4J `WARN` via `jul-to-slf4j` in runtime) whenever a native Vulkan bridge or
 * eligible GPU device is unavailable.
 */
class VulkanOpticalBackend(
    val preference: OpticsBackendPreference = OpticsBackendPreference.AUTO,
    val allowSoftwareVulkan: Boolean = false,
    val explicitLibraryPath: Path? = null,
    val preferredDevice: String = DEFAULT_VULKAN_DEVICE,
    val pipelineCachePath: Path? = null,
) : OpticalComputeBackend {

    private val logger: Logger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
    private val closed = AtomicBoolean(false)
    private val framesRendered = AtomicLong(0L)
    private val renderLock = Any()
    private val cpuFallbackLock = Any()
    private var packInScratch: FloatArray = FloatArray(0)
    private var packOutScratch: FloatArray = FloatArray(0)
    private var bayerOutScratch: ByteArray = ByteArray(0)
    private var cpuFallbackPipeline: CpuOpticalPipeline? = null
    private val cpuFallbackWorkspace: OpticalFrameWorkspace = OpticalFrameWorkspace()
    private var cpuFilterScratchR: FloatArray = FloatArray(0)
    private var cpuFilterScratchG: FloatArray = FloatArray(0)
    private var cpuFilterScratchB: FloatArray = FloatArray(0)
    private var uploadedSceneKey: Long = 0L
    private var uploadedScenePixels: Int = -1
    private var uploadedSceneRed: FloatArray? = null
    private var uploadedSceneGreen: FloatArray? = null
    private var uploadedSceneBlue: FloatArray? = null
    private var uploadedSceneDepth: FloatArray? = null
    private val sceneCacheHits = AtomicLong(0L)
    private val sceneCacheUploads = AtomicLong(0L)

    internal val vramSceneCacheHits: Long get() = sceneCacheHits.get()
    internal val vramSceneCacheUploads: Long get() = sceneCacheUploads.get()

    @Volatile
    private var lastFrameMs: Double = 0.0

    @Volatile
    private var bridge: VulkanNativeOpticsBridge? = null

    @Volatile
    private var scene3dRenderer: VulkanScene3dRenderer? = null

    @Volatile
    private var vkContext: MemorySegment = MemorySegment.NULL

    @Volatile
    private var deviceInfo: VulkanNativeOpticsBridge.NativeDeviceInfo? = null

    @Volatile
    private var enumeratedDevices: List<VulkanNativeOpticsBridge.NativeDeviceInfo> = emptyList()

    @Volatile
    private var effectiveRequestedDevice: String = DEFAULT_VULKAN_DEVICE

    @Volatile
    private var fallbackReason: String? = null

    @Volatile
    private var active: ActiveOpticsBackend = ActiveOpticsBackend.CPU

    override val activeBackend: ActiveOpticsBackend get() = active
    override fun supportsPupilTransform(): Boolean = bridge?.supportsPupilTransform == true

    init {
        effectiveRequestedDevice = resolveEffectiveDeviceSpec(preferredDevice)
        if (preference == OpticsBackendPreference.CPU) {
            active = ActiveOpticsBackend.CPU
            fallbackReason = null
        } else {
            initializeVulkan()
        }
    }

    private fun initializeVulkan() {
        val loaded = VulkanNativeOpticsBridge.tryLoad(explicitLibraryPath)
        val br = loaded.getOrElse { err ->
            val reason = "Native Vulkan optics library unavailable (${err.message}); falling back to CPU pipeline"
            fallbackReason = reason
            active = ActiveOpticsBackend.CPU
            logger.log(Level.WARNING, "[FALLBACK][vulkan] $reason (preference=${preference.configValue})")
            return
        }

        val devices = runCatching { br.enumerateDevices() }.getOrDefault(emptyList())
        enumeratedDevices = devices

        val selectFlags = VulkanNativeOpticsBridge.FUJI_VK_SELECT_DEFAULT or
            (if (allowSoftwareVulkan) VulkanNativeOpticsBridge.FUJI_VK_SELECT_ALLOW_SOFTWARE else 0)

        val requestedSpec = effectiveRequestedDevice
        val isAuto = requestedSpec.equals(DEFAULT_VULKAN_DEVICE, ignoreCase = true)
        var created: VulkanNativeOpticsBridge.CreateOutcome
        var selectionWarning: String? = null

        if (!isAuto) {
            val resolvedIndex = resolveDeviceIndex(requestedSpec, devices)
            if (resolvedIndex != null && resolvedIndex >= 0) {
                created = br.createContextWithCache(resolvedIndex, selectFlags, pipelineCachePath)
                if (created.status != VulkanNativeOpticsBridge.FUJI_VK_OK || created.contextHandle == MemorySegment.NULL) {
                    selectionWarning = "Requested Vulkan device '$requestedSpec' (index=$resolvedIndex) failed (${created.errorMessage}); falling back to auto-select"
                    logger.log(Level.WARNING, "[FALLBACK][vulkan] $selectionWarning")
                    created = br.createContextWithCache(-1, selectFlags, pipelineCachePath)
                }
            } else {
                selectionWarning = "Requested Vulkan device '$requestedSpec' not found among ${devices.size} enumerated device(s); falling back to auto-select"
                logger.log(Level.WARNING, "[FALLBACK][vulkan] $selectionWarning")
                created = br.createContextWithCache(-1, selectFlags, pipelineCachePath)
            }
        } else {
            created = br.createContextWithCache(-1, selectFlags, pipelineCachePath)
        }

        if (created.status != VulkanNativeOpticsBridge.FUJI_VK_OK || created.contextHandle == MemorySegment.NULL) {
            br.close()
            val prefix = if (selectionWarning != null) "$selectionWarning; " else ""
            val reason = "${prefix}Vulkan compute device initialization failed (${created.errorMessage}, rc=${created.status}); falling back to CPU pipeline"
            fallbackReason = reason
            active = ActiveOpticsBackend.CPU
            logger.log(Level.WARNING, "[FALLBACK][vulkan] $reason (preference=${preference.configValue}, device=$requestedSpec)")
            return
        }

        bridge = br
        scene3dRenderer = VulkanNativeScene3dBridge.tryBind(br.libraryPath)?.let { VulkanScene3dRenderer(it) }
        vkContext = created.contextHandle
        deviceInfo = created.deviceInfo
        fallbackReason = selectionWarning
        active = ActiveOpticsBackend.VULKAN
        logger.log(
            Level.INFO,
            "[vulkan-optics] Active GPU compute backend: idx=${created.deviceInfo?.deviceIndex ?: -1} " +
                "device='${created.deviceInfo?.deviceName ?: "unknown"}' " +
                "type=${created.deviceInfo?.deviceTypeName() ?: "OTHER"} " +
                "api=${created.deviceInfo?.formattedApiVersion() ?: "0.0.0"} " +
                "loader=${created.deviceInfo?.loaderName ?: "unknown"} " +
                "extCaps=0x${(created.deviceInfo?.externalMemoryCaps ?: 0).toString(16)}",
        )
    }

    fun availableDevices(): List<VulkanNativeOpticsBridge.NativeDeviceInfo> =
        enumeratedDevices.ifEmpty { enumerateDevices(explicitLibraryPath) }

    fun loadedBridgeOrNull(): VulkanNativeOpticsBridge? = bridge

    fun validateExternalGpuImport(handle: ExternalGpuFrameHandle): Int {
        ensureOpen()
        val br = bridge
        val ctx = vkContext
        if (active != ActiveOpticsBackend.VULKAN || br == null || ctx == MemorySegment.NULL) {
            return -6 // FUJI_VK_ERR_UNSUPPORTED_EXT
        }
        return br.validateExternalFrameImport(ctx, handle)
    }

    override fun diagnostics(): OpticsBackendDiagnostics {
        val info = deviceInfo
        val br = bridge
        val ctx = vkContext
        val pStats = if (active == ActiveOpticsBackend.VULKAN && br != null && ctx != MemorySegment.NULL) {
            runCatching { br.getPipelineStats(ctx) }.getOrNull()
        } else {
            null
        }
        return OpticsBackendDiagnostics(
            activeBackend = active,
            preference = preference,
            deviceName = info?.deviceName ?: "CPU Reference (CpuOpticalPipeline)",
            deviceType = info?.deviceTypeName() ?: "CPU_FALLBACK",
            vulkanApiVersion = info?.formattedApiVersion() ?: "0.0.0",
            loaderName = info?.loaderName,
            nativeBridgeLoaded = br != null,
            externalMemoryCaps = info?.externalMemoryCaps ?: 0,
            lastFrameMs = lastFrameMs,
            framesRendered = framesRendered.get(),
            fallbackReason = fallbackReason,
            deviceIndex = info?.deviceIndex ?: -1,
            requestedDevice = effectiveRequestedDevice,
            pipelineCacheHit = pStats?.pipelineCacheHit ?: false,
            pipelineCacheBytes = pStats?.pipelineCacheBytes ?: 0L,
            pipelineWarmupMs = pStats?.pipelineWarmupMs ?: 0.0,
            specializedPipelineCount = pStats?.specializedPipelineCount ?: 0,
            bufferReallocCount = pStats?.bufferReallocCount ?: 0,
            hostCachedReadback = pStats?.hostCachedReadback ?: false,
        )
    }

    override fun validateExternalFrameDescriptor(descriptor: ExternalGpuFrameDescriptor): Int {
        ensureOpen()
        val br = bridge ?: return -2
        val ctx = vkContext
        if (ctx == MemorySegment.NULL) return -3
        return br.validateExternalFrameDescriptor(ctx, descriptor)
    }

    override fun validateCustomSpirvPasses(passes: List<CustomSpirvPassSpec>): String? {
        if (passes.isEmpty()) return null
        ensureOpen()
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active != ActiveOpticsBackend.VULKAN || br == null || ctx == MemorySegment.NULL || !br.supportsCustomSpirv) {
                return "Vulkan custom SPIR-V runtime unavailable"
            }
            for (pass in passes) {
                val (rc, _) = br.registerCustomSpv(ctx, pass)
                if (rc != VulkanNativeOpticsBridge.FUJI_VK_OK) {
                    val err = br.lastError(ctx).ifBlank { "rc=$rc" }
                    val msg = "Custom SPIR-V pass '${pass.shaderId}' rejected (rc=$rc, err=$err)"
                    logger.log(Level.WARNING, "[FALLBACK][vulkan] $msg; falling back to CPU plugin implementation")
                    return msg
                }
            }
            return null
        }
    }

    override fun customSpirvCacheStats(): CustomSpirvCacheStats? {
        ensureOpen()
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active != ActiveOpticsBackend.VULKAN || br == null || ctx == MemorySegment.NULL) return null
            return br.getCustomSpvCacheStats(ctx)
        }
    }

    private inline fun <T> withCustomSpirvStages(
        br: VulkanNativeOpticsBridge,
        ctx: MemorySegment,
        passes: List<CustomSpirvPassSpec>,
        profile: OpticalLensProfile,
        frameIndex: Long,
        hasDepth: Boolean,
        block: () -> T,
    ): T? {
        if (passes.isEmpty()) return block()
        val rc = br.setCustomSpvStages(
            ctx = ctx,
            passes = passes,
            focusM = profile.focusDistanceM.toFloat(),
            fNumber = profile.fNumber.toFloat(),
            focalMm = profile.effectiveFocalLengthMm().toFloat(),
            frameIndex = frameIndex.toInt(),
            hasDepth = hasDepth,
        )
        if (rc != VulkanNativeOpticsBridge.FUJI_VK_OK) {
            val err = br.lastError(ctx).ifBlank { "rc=$rc" }
            logger.log(
                Level.WARNING,
                "[FALLBACK][vulkan] Custom SPIR-V stage setup failed (rc=$rc, err=$err); falling back to CPU plugin implementation",
            )
            br.clearCustomSpvStages(ctx)
            return null
        }
        return try {
            block()
        } finally {
            br.clearCustomSpvStages(ctx)
        }
    }

    /**
     * Executes Phase 3 GPU Compute LiveView ISP Demosaic + Q10 RGB Gain + 3x3 CCM + 1D Tone LUT +
     * optional 5-tap HPF Focus Peaking (`UYVY422` / `NV16` / `NV12` / `YYUV422`).
     * Returns `true` if executed on Vulkan Compute, or `false` (after logging `[FALLBACK][vulkan]`)
     * when the caller should fall back to its CPU ISP reference implementation.
     */
    fun tryIspDemosaic(
        rawBayer: ByteArray,
        lutR: IntArray? = null,
        lutG: IntArray? = null,
        lutB: IntArray? = null,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        params: VulkanNativeOpticsBridge.IspDemosaicParamsStruct,
    ): Boolean {
        ensureOpen()
        val br = bridge
        val ctx = vkContext
        if (active != ActiveOpticsBackend.VULKAN || br == null || ctx == MemorySegment.NULL) {
            return false
        }
        val (rc, gpuMs) = br.ispDemosaic(ctx, rawBayer, lutR, lutG, lutB, outYOrPacked, outUvPlane, params)
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
            lastFrameMs = gpuMs
            framesRendered.incrementAndGet()
            return true
        }
        val err = br.lastError(ctx)
        demoteToCpu("Vulkan isp_demosaic dispatch failed (rc=$rc, err=$err)")
        return false
    }

    private fun ensureSceneResidentOnGpu(
        br: VulkanNativeOpticsBridge,
        ctx: MemorySegment,
        source: LinearRgbImage,
        depthMeters: FloatArray?,
        sceneCacheKey: Long,
    ): Boolean {
        if (sceneCacheKey == 0L || !br.supportsSceneCache) return false
        val pixelCount = source.pixelCount
        if (
            uploadedSceneKey == sceneCacheKey &&
            uploadedScenePixels == pixelCount &&
            uploadedSceneRed === source.red &&
            uploadedSceneGreen === source.green &&
            uploadedSceneBlue === source.blue &&
            uploadedSceneDepth === depthMeters
        ) {
            sceneCacheHits.incrementAndGet()
            return true
        }
        // A planar upload replaces the 3D renderer's output in the shared device buffer.
        scene3dRenderer?.invalidateRenderedFrame()
        val (rc, _) = br.uploadPlanarScene(ctx, sceneCacheKey, source, depthMeters)
        if (rc != VulkanNativeOpticsBridge.FUJI_VK_OK) {
            uploadedSceneKey = 0L
            uploadedScenePixels = -1
            return false
        }
        uploadedSceneKey = sceneCacheKey
        uploadedScenePixels = pixelCount
        uploadedSceneRed = source.red
        uploadedSceneGreen = source.green
        uploadedSceneBlue = source.blue
        uploadedSceneDepth = depthMeters
        sceneCacheUploads.incrementAndGet()
        return true
    }

    override fun renderLinearRgb(
        source: LinearRgbImage,
        dstWidth: Int,
        dstHeight: Int,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        depthMeters: FloatArray?,
        planarDepthM: Double,
        pupilShape: BokehPupilShape,
        maxBokehRadiusPx: Int,
        samplingMode: SamplingMode,
        borderMode: BorderMode,
        filterStack: OpticalFilterStackConfig,
        sceneCacheKey: Long,
        customSpirvPasses: List<CustomSpirvPassSpec>,
        frameIndex: Long,
    ): LinearRgbImage {
        ensureOpen()
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active == ActiveOpticsBackend.VULKAN && br != null && ctx != MemorySegment.NULL &&
                acceptsPupil(br, filterStack)) {
                val outFloatCount = Math.multiplyExact(Math.multiplyExact(dstWidth, dstHeight), 4)
                val packedOut = ensurePackOutScratch(outFloatCount)
                val filterParams = if (filterStack.isBypassed) null else buildFilterParams(source.width, source.height, filterStack)
                val params = buildOpticsParams(
                    srcWidth = source.width,
                    srcHeight = source.height,
                    dstWidth = dstWidth,
                    dstHeight = dstHeight,
                    profile = profile,
                    ibis = ibis,
                    hasDepth = depthMeters != null,
                    planarDepthM = planarDepthM,
                    pupilShape = pupilShape,
                    pupilTransformFlags = filterStack.pupilTransformFlags,
                    maxBokehRadiusPx = maxBokehRadiusPx,
                    borderMode = borderMode,
                )
                val outcome = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, depthMeters != null) {
                    if (ensureSceneResidentOnGpu(br, ctx, source, depthMeters, sceneCacheKey)) {
                        br.renderCachedSceneOptics(ctx, sceneCacheKey, packedOut, params, filterParams, outFloatCount)
                    } else {
                        val inFloatCount = Math.multiplyExact(source.pixelCount, 4)
                        val packedIn = packInputRgbaDepthIntoScratch(source, depthMeters, inFloatCount)
                        br.renderOptics(ctx, packedIn, packedOut, params, filterParams, inFloatCount, outFloatCount)
                    }
                }
                if (outcome != null) {
                    val (rc, gpuMs) = outcome
                    if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                        lastFrameMs = gpuMs
                        framesRendered.incrementAndGet()
                        return unpackOutputRgba(packedOut, dstWidth, dstHeight)
                    }
                    val err = br.lastError(ctx)
                    demoteToCpu("Vulkan render_optics dispatch failed (rc=$rc, err=$err)")
                }
            }
        }

        val t0 = System.nanoTime()
        val result = synchronized(cpuFallbackLock) {
            val filteredSrc = if (filterStack.isBypassed) source else applyCpuFilterStackFallbackIntoScratch(source, filterStack)
            ensureCpuFallbackPipeline(maxBokehRadiusPx).renderLinearRgb(
                source = filteredSrc,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                profile = profile,
                ibis = ibis,
                depthMeters = depthMeters,
                planarSubjectDistanceM = if (depthMeters == null && planarDepthM > 0.0) planarDepthM else null,
                samplingMode = samplingMode,
                borderMode = borderMode,
                workspace = cpuFallbackWorkspace,
                pupilMask = filterStack.pupilMask ?: pupilShape.cpuMask,
            )
        }
        lastFrameMs = (System.nanoTime() - t0) / 1_000_000.0
        framesRendered.incrementAndGet()
        return result
    }

    override fun renderToBayer(
        source: LinearRgbImage,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        noiseConfig: CmosNoiseConfig,
        destination: ByteBuffer,
        depthMeters: FloatArray?,
        planarDepthM: Double,
        pupilShape: BokehPupilShape,
        maxBokehRadiusPx: Int,
        samplingMode: SamplingMode,
        borderMode: BorderMode,
        frameIndex: Long,
        filterStack: OpticalFilterStackConfig,
        sceneCacheKey: Long,
        customSpirvPasses: List<CustomSpirvPassSpec>,
    ): Int {
        ensureOpen()
        CmosNoiseModel.validateGeometryAndExposure(geometry, exposure, noiseConfig)
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active == ActiveOpticsBackend.VULKAN && br != null && ctx != MemorySegment.NULL &&
                acceptsPupil(br, filterStack)) {
                val bytesPerPx = if (geometry.bitDepth <= 8) 1 else 2
                val requiredBytes = Math.multiplyExact(Math.multiplyExact(geometry.width, geometry.height), bytesPerPx)
                require(destination.remaining() >= requiredBytes) {
                    "Destination buffer too small: need $requiredBytes, remaining ${destination.remaining()}"
                }
                val filterParams = if (filterStack.isBypassed) null else buildFilterParams(source.width, source.height, filterStack)
                val opticsParams = buildOpticsParams(
                    srcWidth = source.width,
                    srcHeight = source.height,
                    dstWidth = geometry.width,
                    dstHeight = geometry.height,
                    profile = profile,
                    ibis = ibis,
                    hasDepth = depthMeters != null,
                    planarDepthM = planarDepthM,
                    pupilShape = pupilShape,
                    pupilTransformFlags = filterStack.pupilTransformFlags,
                    maxBokehRadiusPx = maxBokehRadiusPx,
                    borderMode = borderMode,
                )
                val cmosParams = buildCmosParams(geometry, exposure, profile, noiseConfig, frameIndex)
                val sceneCached = ensureSceneResidentOnGpu(br, ctx, source, depthMeters, sceneCacheKey)
                if (destination.isDirect) {
                    val pos = destination.position()
                    val dstSeg = MemorySegment.ofBuffer(destination).asSlice(pos.toLong(), requiredBytes.toLong())
                    val outcome = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, depthMeters != null) {
                        if (sceneCached) {
                            br.renderCachedSceneToBayerDirect(
                                ctx,
                                sceneCacheKey,
                                dstSeg,
                                requiredBytes,
                                opticsParams,
                                cmosParams,
                                filterParams,
                            )
                        } else {
                            val inFloatCount = Math.multiplyExact(source.pixelCount, 4)
                            val packedIn = packInputRgbaDepthIntoScratch(source, depthMeters, inFloatCount)
                            br.renderOpticsToBayerDirect(
                                ctx,
                                packedIn,
                                inFloatCount,
                                dstSeg,
                                requiredBytes,
                                opticsParams,
                                cmosParams,
                                filterParams,
                            )
                        }
                    }
                    if (outcome != null) {
                        val (rc, gpuMs) = outcome
                        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                            destination.position(pos + requiredBytes)
                            lastFrameMs = gpuMs
                            framesRendered.incrementAndGet()
                            return requiredBytes
                        }
                        val err = br.lastError(ctx)
                        demoteToCpu("Vulkan render_optics_to_bayer (direct) dispatch failed (rc=$rc, err=$err)")
                    }
                } else {
                    val outBytes = ensureBayerScratch(requiredBytes)
                    val outcome = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, depthMeters != null) {
                        if (sceneCached) {
                            br.renderCachedSceneToBayer(
                                ctx,
                                sceneCacheKey,
                                outBytes,
                                opticsParams,
                                cmosParams,
                                filterParams,
                                outByteCount = requiredBytes,
                            )
                        } else {
                            val inFloatCount = Math.multiplyExact(source.pixelCount, 4)
                            val packedIn = packInputRgbaDepthIntoScratch(source, depthMeters, inFloatCount)
                            br.renderOpticsToBayer(
                                ctx,
                                packedIn,
                                outBytes,
                                opticsParams,
                                cmosParams,
                                filterParams,
                                inFloatCount = inFloatCount,
                                outByteCount = requiredBytes,
                            )
                        }
                    }
                    if (outcome != null) {
                        val (rc, gpuMs) = outcome
                        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                            destination.put(outBytes, 0, requiredBytes)
                            lastFrameMs = gpuMs
                            framesRendered.incrementAndGet()
                            return requiredBytes
                        }
                        val err = br.lastError(ctx)
                        demoteToCpu("Vulkan render_optics_to_bayer dispatch failed (rc=$rc, err=$err)")
                    }
                }
            }
        }

        val t0 = System.nanoTime()
        val written = synchronized(cpuFallbackLock) {
            val filteredSrc = if (filterStack.isBypassed) source else applyCpuFilterStackFallbackIntoScratch(source, filterStack)
            ensureCpuFallbackPipeline(maxBokehRadiusPx).render(
                source = filteredSrc,
                geometry = geometry,
                exposure = exposure,
                profile = profile,
                ibis = ibis,
                noiseConfig = noiseConfig,
                destination = destination,
                depthMeters = depthMeters,
                planarSubjectDistanceM = if (depthMeters == null && planarDepthM > 0.0) planarDepthM else null,
                samplingMode = samplingMode,
                borderMode = borderMode,
                frameIndex = frameIndex,
                workspace = cpuFallbackWorkspace,
                pupilMask = filterStack.pupilMask ?: pupilShape.cpuMask,
            )
        }
        lastFrameMs = (System.nanoTime() - t0) / 1_000_000.0
        framesRendered.incrementAndGet()
        return written
    }

    override fun renderCmosBayer(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile,
        noiseConfig: CmosNoiseConfig,
        destination: ByteBuffer,
        frameIndex: Long,
        parallelThreads: Int,
    ): Int {
        ensureOpen()
        CmosNoiseModel.validateGeometryAndExposure(geometry, exposure, noiseConfig)
        val pixelCount = Math.multiplyExact(geometry.width, geometry.height)
        val bytesPerPx = if (geometry.bitDepth <= 8) 1 else 2
        val requiredBytes = Math.multiplyExact(pixelCount, bytesPerPx)
        require(destination.remaining() >= requiredBytes) {
            "Destination buffer too small: need $requiredBytes, remaining ${destination.remaining()}"
        }
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active == ActiveOpticsBackend.VULKAN && br != null && ctx != MemorySegment.NULL) {
                val inFloatCount = Math.multiplyExact(pixelCount, 4)
                val packed = ensurePackInScratch(inFloatCount)
                var o = 0
                for (i in 0 until pixelCount) {
                    packed[o] = red[i]
                    packed[o + 1] = green[i]
                    packed[o + 2] = blue[i]
                    packed[o + 3] = 0f
                    o += 4
                }
                val cmosParams = buildCmosParams(geometry, exposure, profile, noiseConfig, frameIndex)
                if (destination.isDirect) {
                    val pos = destination.position()
                    val dstSeg = MemorySegment.ofBuffer(destination).asSlice(pos.toLong(), requiredBytes.toLong())
                    val (rc, gpuMs) = br.renderCmosBayerDirect(ctx, packed, inFloatCount, dstSeg, requiredBytes, cmosParams)
                    if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                        destination.position(pos + requiredBytes)
                        lastFrameMs = gpuMs
                        framesRendered.incrementAndGet()
                        return requiredBytes
                    }
                    val err = br.lastError(ctx)
                    demoteToCpu("Vulkan render_cmos_bayer (direct) dispatch failed (rc=$rc, err=$err)")
                } else {
                    val outBytes = ensureBayerScratch(requiredBytes)
                    val (rc, gpuMs) = br.renderCmosBayer(ctx, packed, outBytes, cmosParams, inFloatCount, requiredBytes)
                    if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                        destination.put(outBytes, 0, requiredBytes)
                        lastFrameMs = gpuMs
                        framesRendered.incrementAndGet()
                        return requiredBytes
                    }
                    val err = br.lastError(ctx)
                    demoteToCpu("Vulkan render_cmos_bayer dispatch failed (rc=$rc, err=$err)")
                }
            }
        }

        val t0 = System.nanoTime()
        val written = CmosNoiseModel.mosaicAndReadout(
            red = red,
            green = green,
            blue = blue,
            geometry = geometry,
            exposure = exposure,
            config = noiseConfig,
            destination = destination,
            fNumber = profile.fNumber,
            transmission = profile.transmission,
            frameIndex = frameIndex,
            parallelThreads = parallelThreads,
        )
        lastFrameMs = (System.nanoTime() - t0) / 1_000_000.0
        framesRendered.incrementAndGet()
        return written
    }

    override fun supportsFusedIspStats(): Boolean =
        active == ActiveOpticsBackend.VULKAN &&
            bridge?.supportsFusedIspStats == true &&
            vkContext != MemorySegment.NULL

    override fun tryRenderOpticsToIspYuv(
        source: LinearRgbImage,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        noiseConfig: CmosNoiseConfig,
        depthMeters: FloatArray?,
        planarDepthM: Double,
        pupilShape: BokehPupilShape,
        maxBokehRadiusPx: Int,
        borderMode: BorderMode,
        frameIndex: Long,
        filterStack: OpticalFilterStackConfig,
        lutR: IntArray?,
        lutG: IntArray?,
        lutB: IntArray?,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray?,
        ispParams: VulkanNativeOpticsBridge.IspDemosaicParamsStruct,
        sceneCacheKey: Long,
        customSpirvPasses: List<CustomSpirvPassSpec>,
        statsRequest: LiveViewIspStatsRequest?,
        outStatsWords: IntArray?,
    ): Boolean {
        ensureOpen()
        synchronized(renderLock) {
            val br = bridge
            val ctx = vkContext
            if (active != ActiveOpticsBackend.VULKAN || br == null || ctx == MemorySegment.NULL ||
                !br.supportsFusedOpticsIsp || !acceptsPupil(br, filterStack)) {
                return false
            }
            if (statsRequest != null && outStatsWords != null && !br.supportsFusedIspStats) {
                return false
            }
            if (geometry.width != ispParams.srcWidth || geometry.height != ispParams.srcHeight) {
                return false
            }
            val filterParams = if (filterStack.isBypassed) null else buildFilterParams(source.width, source.height, filterStack)
            val opticsParams = buildOpticsParams(
                srcWidth = source.width,
                srcHeight = source.height,
                dstWidth = geometry.width,
                dstHeight = geometry.height,
                profile = profile,
                ibis = ibis,
                hasDepth = depthMeters != null,
                planarDepthM = planarDepthM,
                pupilShape = pupilShape,
                pupilTransformFlags = filterStack.pupilTransformFlags,
                maxBokehRadiusPx = maxBokehRadiusPx,
                borderMode = borderMode,
            )
            val cmosParams = buildCmosParams(geometry, exposure, profile, noiseConfig, frameIndex)
            val outcome = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, depthMeters != null) {
                if (ensureSceneResidentOnGpu(br, ctx, source, depthMeters, sceneCacheKey)) {
                    br.renderCachedSceneToIspYuv(
                        ctx = ctx,
                        sceneKey = sceneCacheKey,
                        filterParams = filterParams,
                        opticsParams = opticsParams,
                        cmosParams = cmosParams,
                        lutR = lutR,
                        lutG = lutG,
                        lutB = lutB,
                        outYOrPacked = outYOrPacked,
                        outYByteCount = outYOrPacked.size,
                        outUvPlane = outUvPlane,
                        outUvByteCount = outUvPlane?.size ?: 0,
                        ispParams = ispParams,
                        statsRequest = statsRequest,
                        outStatsWords = outStatsWords,
                    )
                } else {
                    val inFloatCount = Math.multiplyExact(source.pixelCount, 4)
                    val packedIn = packInputRgbaDepthIntoScratch(source, depthMeters, inFloatCount)
                    br.renderOpticsToIspYuv(
                        ctx = ctx,
                        inRgbaDepth = packedIn,
                        inFloatCount = inFloatCount,
                        filterParams = filterParams,
                        opticsParams = opticsParams,
                        cmosParams = cmosParams,
                        lutR = lutR,
                        lutG = lutG,
                        lutB = lutB,
                        outYOrPacked = outYOrPacked,
                        outYByteCount = outYOrPacked.size,
                        outUvPlane = outUvPlane,
                        outUvByteCount = outUvPlane?.size ?: 0,
                        ispParams = ispParams,
                        statsRequest = statsRequest,
                        outStatsWords = outStatsWords,
                    )
                }
            } ?: return false
            val (rc, gpuMs) = outcome
            if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                lastFrameMs = gpuMs
                framesRendered.incrementAndGet()
                return true
            }
            val err = br.lastError(ctx)
            demoteToCpu("Vulkan fused render_optics_to_isp_yuv dispatch failed (rc=$rc, err=$err)")
            return false
        }
    }

    override fun supportsGpuScene3d(): Boolean =
        active == ActiveOpticsBackend.VULKAN &&
            bridge?.supportsSceneCache == true &&
            scene3dRenderer != null &&
            vkContext != MemorySegment.NULL

    val gpuScene3dFramesRendered: Long get() = scene3dRenderer?.framesRendered ?: 0L
    val gpuScene3dLastMs: Double get() = scene3dRenderer?.lastScene3dMs ?: 0.0
    val gpuScene3dStaticUploads: Long get() = scene3dRenderer?.staticAssetUploads ?: 0L

    /**
     * Renders [spec] on Vulkan GPU directly into `DEVICE_LOCAL` VRAM and reads back the
     * RGBA+Depth pixels alongside the 16x16 depth summary grid (for parity & benchmark tests).
     */
    fun renderGpuScene3dForReadback(
        spec: GpuScene3dRenderSpec,
    ): Pair<FloatArray, VulkanScene3dRenderer.DepthGridSnapshot>? {
        ensureOpen()
        synchronized(renderLock) {
            val renderer = scene3dRenderer ?: return null
            val ctx = vkContext
            if (active != ActiveOpticsBackend.VULKAN || ctx == MemorySegment.NULL) return null
            uploadedSceneKey = 0L
            val grid = renderer.renderIntoDeviceSceneCache(ctx, spec) ?: return null
            val rgbaDepth = renderer.readbackRenderedScene(ctx, spec.width, spec.height) ?: return null
            return rgbaDepth to grid
        }
    }

    override fun tryRenderGpuScene3dToIspYuv(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        noiseConfig: CmosNoiseConfig,
        pupilShape: BokehPupilShape,
        maxBokehRadiusPx: Int,
        borderMode: BorderMode,
        frameIndex: Long,
        filterStack: OpticalFilterStackConfig,
        lutR: IntArray?,
        lutG: IntArray?,
        lutB: IntArray?,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray?,
        ispParams: VulkanNativeOpticsBridge.IspDemosaicParamsStruct,
        customSpirvPasses: List<CustomSpirvPassSpec>,
        statsRequest: LiveViewIspStatsRequest?,
        outStatsWords: IntArray?,
    ): VulkanScene3dRenderer.DepthGridSnapshot? {
        ensureOpen()
        synchronized(renderLock) {
            val br = bridge
            val renderer = scene3dRenderer
            val ctx = vkContext
            if (
                active != ActiveOpticsBackend.VULKAN ||
                br == null || !acceptsPupil(br, filterStack) ||
                renderer == null ||
                ctx == MemorySegment.NULL ||
                !br.supportsSceneCache ||
                !br.supportsFusedOpticsIsp
            ) {
                return null
            }
            if (statsRequest != null && outStatsWords != null && !br.supportsFusedIspStats) {
                return null
            }
            if (geometry.width != ispParams.srcWidth || geometry.height != ispParams.srcHeight) {
                return null
            }
            uploadedSceneKey = 0L
            val depthGrid = renderer.renderIntoDeviceSceneCache(ctx, spec) ?: return null
            val filterParams = if (filterStack.isBypassed) null else buildFilterParams(spec.width, spec.height, filterStack)
            val opticsParams = buildOpticsParams(
                srcWidth = spec.width,
                srcHeight = spec.height,
                dstWidth = geometry.width,
                dstHeight = geometry.height,
                profile = profile,
                ibis = ibis,
                hasDepth = true,
                planarDepthM = depthGrid.centerDepthMeters ?: profile.focusDistanceM,
                pupilShape = pupilShape,
                pupilTransformFlags = filterStack.pupilTransformFlags,
                maxBokehRadiusPx = maxBokehRadiusPx,
                borderMode = borderMode,
            )
            val cmosParams = buildCmosParams(geometry, exposure, profile, noiseConfig, frameIndex)
            val (rc, gpuMs) = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, true) {
                br.renderCachedSceneToIspYuv(
                    ctx = ctx,
                    sceneKey = spec.sceneKey,
                    filterParams = filterParams,
                    opticsParams = opticsParams,
                    cmosParams = cmosParams,
                    lutR = lutR,
                    lutG = lutG,
                    lutB = lutB,
                    outYOrPacked = outYOrPacked,
                    outYByteCount = outYOrPacked.size,
                    outUvPlane = outUvPlane,
                    outUvByteCount = outUvPlane?.size ?: 0,
                    ispParams = ispParams,
                    statsRequest = statsRequest,
                    outStatsWords = outStatsWords,
                )
            } ?: return null
            if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                lastFrameMs = renderer.lastScene3dMs + gpuMs
                framesRendered.incrementAndGet()
                return depthGrid
            }
            return null
        }
    }

    override fun tryRenderGpuScene3dToBayer(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile,
        ibis: IbisShiftState,
        noiseConfig: CmosNoiseConfig,
        destination: ByteBuffer,
        pupilShape: BokehPupilShape,
        maxBokehRadiusPx: Int,
        borderMode: BorderMode,
        frameIndex: Long,
        filterStack: OpticalFilterStackConfig,
        customSpirvPasses: List<CustomSpirvPassSpec>,
    ): Pair<Int, VulkanScene3dRenderer.DepthGridSnapshot>? {
        ensureOpen()
        CmosNoiseModel.validateGeometryAndExposure(geometry, exposure, noiseConfig)
        synchronized(renderLock) {
            val br = bridge
            val renderer = scene3dRenderer
            val ctx = vkContext
            if (
                active != ActiveOpticsBackend.VULKAN ||
                br == null || !acceptsPupil(br, filterStack) ||
                renderer == null ||
                ctx == MemorySegment.NULL ||
                !br.supportsSceneCache
            ) {
                return null
            }
            val bytesPerPx = if (geometry.bitDepth <= 8) 1 else 2
            val requiredBytes = Math.multiplyExact(Math.multiplyExact(geometry.width, geometry.height), bytesPerPx)
            if (destination.remaining() < requiredBytes) return null

            uploadedSceneKey = 0L
            val depthGrid = renderer.renderIntoDeviceSceneCache(ctx, spec) ?: return null
            val filterParams = if (filterStack.isBypassed) null else buildFilterParams(spec.width, spec.height, filterStack)
            val opticsParams = buildOpticsParams(
                srcWidth = spec.width,
                srcHeight = spec.height,
                dstWidth = geometry.width,
                dstHeight = geometry.height,
                profile = profile,
                ibis = ibis,
                hasDepth = true,
                planarDepthM = depthGrid.centerDepthMeters ?: profile.focusDistanceM,
                pupilShape = pupilShape,
                pupilTransformFlags = filterStack.pupilTransformFlags,
                maxBokehRadiusPx = maxBokehRadiusPx,
                borderMode = borderMode,
            )
            val cmosParams = buildCmosParams(geometry, exposure, profile, noiseConfig, frameIndex)
            if (destination.isDirect) {
                val pos = destination.position()
                val dstSeg = MemorySegment.ofBuffer(destination).asSlice(pos.toLong(), requiredBytes.toLong())
                val (rc, gpuMs) = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, true) {
                    br.renderCachedSceneToBayerDirect(
                        ctx,
                        spec.sceneKey,
                        dstSeg,
                        requiredBytes,
                        opticsParams,
                        cmosParams,
                        filterParams,
                    )
                } ?: return null
                if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                    destination.position(pos + requiredBytes)
                    lastFrameMs = renderer.lastScene3dMs + gpuMs
                    framesRendered.incrementAndGet()
                    return requiredBytes to depthGrid
                }
            } else {
                val outBytes = ensureBayerScratch(requiredBytes)
                val (rc, gpuMs) = withCustomSpirvStages(br, ctx, customSpirvPasses, profile, frameIndex, true) {
                    br.renderCachedSceneToBayer(
                        ctx,
                        spec.sceneKey,
                        outBytes,
                        opticsParams,
                        cmosParams,
                        filterParams,
                        outByteCount = requiredBytes,
                    )
                } ?: return null
                if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
                    destination.put(outBytes, 0, requiredBytes)
                    lastFrameMs = renderer.lastScene3dMs + gpuMs
                    framesRendered.incrementAndGet()
                    return requiredBytes to depthGrid
                }
            }
            return null
        }
    }

    private fun ensureCpuFallbackPipeline(maxBokehRadiusPx: Int): CpuOpticalPipeline {
        val cur = cpuFallbackPipeline
        if (cur != null && cur.maxBokehRadiusPx == maxBokehRadiusPx) return cur
        return CpuOpticalPipeline(maxBokehRadiusPx = maxBokehRadiusPx).also { cpuFallbackPipeline = it }
    }

    private fun packInputRgbaDepthIntoScratch(
        source: LinearRgbImage,
        depthMeters: FloatArray?,
        inFloatCount: Int,
    ): FloatArray {
        val out = ensurePackInScratch(inFloatCount)
        val count = source.pixelCount
        val r = source.red
        val g = source.green
        val b = source.blue
        var o = 0
        if (depthMeters != null) {
            for (i in 0 until count) {
                out[o] = r[i]
                out[o + 1] = g[i]
                out[o + 2] = b[i]
                out[o + 3] = depthMeters[i]
                o += 4
            }
        } else {
            for (i in 0 until count) {
                out[o] = r[i]
                out[o + 1] = g[i]
                out[o + 2] = b[i]
                out[o + 3] = 0f
                o += 4
            }
        }
        return out
    }

    private fun applyCpuFilterStackFallbackIntoScratch(
        source: LinearRgbImage,
        cfg: OpticalFilterStackConfig,
    ): LinearRgbImage {
        val w = source.width
        val h = source.height
        val n = source.pixelCount
        if (cpuFilterScratchR.size != n) {
            cpuFilterScratchR = FloatArray(n)
            cpuFilterScratchG = FloatArray(n)
            cpuFilterScratchB = FloatArray(n)
        }
        val outR = cpuFilterScratchR
        val outG = cpuFilterScratchG
        val outB = cpuFilterScratchB
        val flags = cfg.enableFlags
        val ndMul = if ((flags and OpticalFilterStackConfig.FLAG_ND) != 0) 2.0.pow(-cfg.ndStops).coerceIn(0.0, 1.0) else 1.0
        val useGnd = (flags and OpticalFilterStackConfig.FLAG_GND) != 0
        val gndTop = if (useGnd) 2.0.pow(-cfg.gndStops).coerceIn(0.0, 1.0) else 1.0
        val gndRad = Math.toRadians(cfg.gndAngleDeg)
        val gndSin = sin(gndRad)
        val gndCos = cos(gndRad)
        val feather = cfg.gndFeather.coerceIn(0.005, 0.5)
        for (y in 0 until h) {
            val ny = (y + 0.5) / h - 0.5
            for (x in 0 until w) {
                val idx = y * w + x
                var mul = ndMul
                if (useGnd) {
                    val nx = (x + 0.5) / w - 0.5
                    val proj = -nx * gndSin + ny * gndCos + 0.5
                    val t = ((proj - (cfg.gndHorizonY - feather)) / (2.0 * feather)).coerceIn(0.0, 1.0)
                    val sm = t * t * (3.0 - 2.0 * t)
                    mul *= gndTop + (1.0 - gndTop) * sm
                }
                outR[idx] = (source.red[idx] * mul).toFloat()
                outG[idx] = (source.green[idx] * mul).toFloat()
                outB[idx] = (source.blue[idx] * mul).toFloat()
            }
        }
        return LinearRgbImage(w, h, outR, outG, outB)
    }

    private fun ensurePackInScratch(size: Int): FloatArray {
        val cur = packInScratch
        if (cur.size >= size) return cur
        return FloatArray(size).also { packInScratch = it }
    }

    private fun ensurePackOutScratch(size: Int): FloatArray {
        val cur = packOutScratch
        if (cur.size >= size) return cur
        return FloatArray(size).also { packOutScratch = it }
    }

    private fun ensureBayerScratch(size: Int): ByteArray {
        val cur = bayerOutScratch
        if (cur.size >= size) return cur
        return ByteArray(size).also { bayerOutScratch = it }
    }

    private fun demoteToCpu(reason: String) {
        fallbackReason = reason
        active = ActiveOpticsBackend.CPU
        logger.log(Level.WARNING, "[FALLBACK][vulkan] $reason; demoting optical backend to CPU")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(renderLock) {
                val s3d = scene3dRenderer
                scene3dRenderer = null
                runCatching { s3d?.close() }
                val br = bridge
                val ctx = vkContext
                vkContext = MemorySegment.NULL
                bridge = null
                if (br != null) {
                    runCatching { br.destroyContext(ctx) }
                    runCatching { br.close() }
                }
            }
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "VulkanOpticalBackend is closed" }
    }

    companion object {
        private fun unpackOutputRgba(packed: FloatArray, width: Int, height: Int): LinearRgbImage {
            val count = Math.multiplyExact(width, height)
            val r = FloatArray(count)
            val g = FloatArray(count)
            val b = FloatArray(count)
            var o = 0
            for (i in 0 until count) {
                r[i] = packed[o]
                g[i] = packed[o + 1]
                b[i] = packed[o + 2]
                o += 4
            }
            return LinearRgbImage(width, height, r, g, b)
        }

        private fun buildFilterParams(
            width: Int,
            height: Int,
            cfg: OpticalFilterStackConfig,
        ): VulkanNativeOpticsBridge.FilterStackParamsStruct {
            val gndRad = Math.toRadians(cfg.gndAngleDeg)
            val starRad = Math.toRadians(cfg.starAngleDeg)
            val ndTrans = if (cfg.ndStops > 0.0) 2.0.pow(-cfg.ndStops) else 1.0
            val gndTrans = if (cfg.gndStops > 0.0) 2.0.pow(-cfg.gndStops) else 1.0
            return VulkanNativeOpticsBridge.FilterStackParamsStruct(
                width = width,
                height = height,
                enableFlags = cfg.enableFlags,
                starPoints = cfg.starPoints.coerceIn(4, 8),
                ndTransmission = ndTrans.coerceIn(0.0, 1.0).toFloat(),
                gndTopTransmission = gndTrans.coerceIn(0.0, 1.0).toFloat(),
                gndHorizonY = cfg.gndHorizonY.coerceIn(0.0, 1.0).toFloat(),
                gndFeather = cfg.gndFeather.coerceIn(0.005, 0.5).toFloat(),
                gndSinAngle = sin(gndRad).toFloat(),
                gndCosAngle = cos(gndRad).toFloat(),
                mistStrength = cfg.blackMistStrength.coerceIn(0.0, 1.0).toFloat(),
                mistThreshold = cfg.blackMistThreshold.coerceAtLeast(0.0).toFloat(),
                mistRadiusPx = cfg.blackMistRadiusPx.coerceIn(0.5, 24.0).toFloat(),
                starStrength = cfg.starStrength.coerceIn(0.0, 2.0).toFloat(),
                starThreshold = cfg.starThreshold.coerceAtLeast(0.0).toFloat(),
                starRadiusPx = cfg.starRadiusPx.coerceIn(1.0, 32.0).toFloat(),
                starAngleRad = starRad.toFloat(),
                mistWarmth = cfg.blackMistWarmth.coerceIn(0.0, 0.3).toFloat(),
                uvVeilingGlare = cfg.uvVeilingGlare.coerceIn(0.0, 0.5).toFloat(),
                uvGhostIntensity = cfg.uvGhostIntensity.coerceIn(0.0, 0.5).toFloat(),
                uvSoftness = cfg.uvSoftness.coerceIn(0.0, 1.0).toFloat(),
                uvTintR = cfg.uvTintR.coerceIn(0.5, 1.8).toFloat(),
                uvTintG = cfg.uvTintG.coerceIn(0.5, 1.8).toFloat(),
                uvTintB = cfg.uvTintB.coerceIn(0.5, 1.8).toFloat(),
                uvGhostRadiusPx = cfg.uvGhostRadiusPx.coerceIn(2.0, 16.0).toFloat(),
            )
        }

        private fun acceptsPupil(bridge: VulkanNativeOpticsBridge, filter: OpticalFilterStackConfig): Boolean =
            filter.pupilTransformFlags == 0 || bridge.supportsPupilTransform

        private fun buildOpticsParams(
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int,
            dstHeight: Int,
            profile: OpticalLensProfile,
            ibis: IbisShiftState,
            hasDepth: Boolean,
            planarDepthM: Double,
            pupilShape: BokehPupilShape,
            pupilTransformFlags: Int,
            maxBokehRadiusPx: Int,
            borderMode: BorderMode,
        ): VulkanNativeOpticsBridge.OpticsParamsStruct {
            val effF = profile.effectiveFocalLengthMm()
            val fPx = (effF / profile.sensorWidthMm) * dstWidth.toDouble()
            var flags = pupilTransformFlags
            if (hasDepth) flags = flags or 1
            if (maxBokehRadiusPx > 0 && (hasDepth || kotlin.math.abs(planarDepthM - profile.focusDistanceM) > 1e-3)) {
                flags = flags or 2
            }
            return VulkanNativeOpticsBridge.OpticsParamsStruct(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                k1 = profile.k1.toFloat(),
                k2 = profile.k2.toFloat(),
                p1 = profile.p1.toFloat(),
                p2 = profile.p2.toFloat(),
                caRedScale = profile.caRedScale.toFloat(),
                caBlueScale = profile.caBlueScale.toFloat(),
                cos4Power = profile.cos4Power.toFloat(),
                mechVignetting = profile.mechanicalVignetting.toFloat(),
                focalLengthMm = effF.toFloat(),
                sensorWidthMm = profile.sensorWidthMm.toFloat(),
                focusDistanceM = profile.focusDistanceM.toFloat(),
                fNumber = profile.fNumber.toFloat(),
                invZoom = (1.0 / profile.zoomScale()).toFloat(),
                ibisShiftX = ibis.effectiveShiftXPixels(fPx).toFloat(),
                ibisShiftY = ibis.effectiveShiftYPixels(fPx).toFloat(),
                ibisCosRoll = cos(ibis.rollRadians).toFloat(),
                ibisSinRoll = sin(ibis.rollRadians).toFloat(),
                maxBokehPx = maxBokehRadiusPx.toFloat(),
                bladeRoundness = profile.apertureBladeRoundness.toFloat(),
                catsEye = profile.effectiveCatsEyeStrength().toFloat(),
                onionRing = profile.bokehOnionRing.toFloat(),
                rimHighlight = profile.bokehRimHighlight.toFloat(),
                planarDepthM = planarDepthM.toFloat(),
                imageCircleMm = profile.imageCircleMm.toFloat(),
                pupilShape = pupilShape.shaderId,
                bladeCount = profile.apertureBladeCount,
                borderClamp = if (borderMode == BorderMode.CLAMP) 1 else 0,
                flags = flags,
            )
        }

        private fun buildCmosParams(
            geometry: SensorGeometry,
            exposure: Exposure,
            profile: OpticalLensProfile,
            config: CmosNoiseConfig,
            frameIndex: Long,
        ): VulkanNativeOpticsBridge.CmosParamsStruct {
            val bayerId = when (geometry.bayer) {
                BayerPattern.RGGB -> 0
                BayerPattern.GRBG -> 1
                BayerPattern.GBRG -> 2
                BayerPattern.BGGR -> 3
            }
            val optScale = CmosNoiseModel.opticalExposureScale(exposure, config, profile.fNumber, profile.transmission)
            return VulkanNativeOpticsBridge.CmosParamsStruct(
                width = geometry.width,
                height = geometry.height,
                bayerPattern = bayerId,
                bitDepth = geometry.bitDepth,
                maxCode = (1 shl geometry.bitDepth) - 1,
                blackLevel = config.blackLevel,
                enableNoise = if (config.enableNoise) 1 else 0,
                iso = exposure.iso,
                baseIso = config.baseIso.toFloat(),
                fullWellElectrons = config.fullWellElectrons.toFloat(),
                opticalExposureScale = optScale.toFloat(),
                readNoiseBaseElectrons = config.readNoiseBaseElectrons.toFloat(),
                readNoiseIsoSlope = config.readNoiseIsoSlope.toFloat(),
                seedLo = config.seed.toInt(),
                seedHi = (config.seed ushr 32).toInt(),
                frameIndexLo = frameIndex.toInt(),
            )
        }

        const val DEFAULT_VULKAN_DEVICE: String = "auto"

        fun enumerateDevices(explicitLibraryPath: Path? = null): List<VulkanNativeOpticsBridge.NativeDeviceInfo> =
            VulkanNativeOpticsBridge.enumerateAvailableDevices(explicitLibraryPath)

        internal fun resolveEffectiveDeviceSpec(configured: String?): String {
            val trimmed = configured?.trim().orEmpty()
            if (trimmed.isNotEmpty() && !trimmed.equals(DEFAULT_VULKAN_DEVICE, ignoreCase = true)) {
                return trimmed
            }
            val sysProp = System.getProperty("fuji.vulkan.device")?.trim().orEmpty()
            if (sysProp.isNotEmpty()) return sysProp
            val envVar = System.getenv("FUJI_VK_DEVICE")?.trim().orEmpty()
            if (envVar.isNotEmpty()) return envVar
            return DEFAULT_VULKAN_DEVICE
        }

        internal fun resolveDeviceIndex(
            requestedSpec: String,
            devices: List<VulkanNativeOpticsBridge.NativeDeviceInfo>,
        ): Int? {
            val spec = requestedSpec.trim()
            if (spec.isEmpty() || spec.equals(DEFAULT_VULKAN_DEVICE, ignoreCase = true)) return null

            // Format 1: plain non-negative integer index ("0", "1", ...)
            spec.toIntOrNull()?.let { idx ->
                if (idx >= 0) return idx
            }

            // Format 2: "<index>:<deviceName>" -> prefer exact name match (resilient across driver reorders), fallback to index
            val colonIdx = spec.indexOf(':')
            if (colonIdx > 0) {
                val idxPart = spec.substring(0, colonIdx).trim().toIntOrNull()
                val namePart = spec.substring(colonIdx + 1).trim()
                if (namePart.isNotEmpty()) {
                    devices.firstOrNull { it.deviceName.equals(namePart, ignoreCase = true) }?.let { return it.deviceIndex }
                }
                if (idxPart != null && idxPart >= 0) {
                    if (devices.isEmpty() || devices.any { it.deviceIndex == idxPart }) return idxPart
                }
            }

            // Format 3: exact or case-insensitive substring match against enumerated device names
            devices.firstOrNull { it.deviceName.equals(spec, ignoreCase = true) }?.let { return it.deviceIndex }
            devices.firstOrNull { it.deviceName.contains(spec, ignoreCase = true) }?.let { return it.deviceIndex }
            return null
        }
    }
}
