package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.BuiltinOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.ExternalGpuFrameDescriptor
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.LiveViewIspStatsRequest
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.BorderMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.IbisShiftState
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import com.github.nyabanban.fuji.emulator.components.lens.optics.SamplingMode
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * User / config selection policy for the optical compute pipeline (`opticsBackend: auto | vulkan | cpu`).
 */
enum class OpticsBackendPreference(val configValue: String) {
    /** Prefer hardware Vulkan 1.1 Compute GPU (dGPU -> iGPU); automatically fall back to CPU with WARN log. */
    AUTO("auto"),

    /** Request Vulkan Compute explicitly; falls back to CPU with WARN unless `strictVulkan = true`. */
    VULKAN("vulkan"),

    /** Execute exclusively on deterministic CPU reference pipeline without loading native Vulkan bridge. */
    CPU("cpu"),
    ;

    companion object {
        val ALL_CONFIG_VALUES: List<String> = entries.map { it.configValue }

        fun parse(raw: String?): OpticsBackendPreference = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "auto" -> AUTO
            "vulkan", "gpu" -> VULKAN
            "cpu" -> CPU
            else -> throw IllegalArgumentException(
                "Invalid opticsBackend '$raw': expected one of $ALL_CONFIG_VALUES",
            )
        }
    }
}

/**
 * Active runtime execution backend selected for optical simulation.
 */
enum class ActiveOpticsBackend {
    VULKAN,
    CPU,
}

/**
 * True 2D entrance-pupil aperture shapes supported by the Vulkan Compute depth-bokeh shader.
 */
enum class BokehPupilShape(val shaderId: Int, internal val cpuMask: BuiltinOpticalPupilMask? = null) {
    CIRCLE(0),
    POLYGON(1),
    STAR_5(2, BuiltinOpticalPupilMask(BokehMaskShape.STAR_5)),
    HEART(3, BuiltinOpticalPupilMask(BokehMaskShape.HEART)),
    RING(4, BuiltinOpticalPupilMask(BokehMaskShape.RING)),
}

/**
 * Front-of-lens physical optical filter stack configuration for GPU Compute Pass 0
 * (`optical_filter_stack.comp`). When all stages are disabled ([isBypassed] == `true`),
 * Pass 0 is completely skipped in the command buffer with zero GPU overhead.
 */
data class OpticalFilterStackConfig(
    val ndStops: Double = 0.0,
    val gndStops: Double = 0.0,
    val gndHorizonY: Double = 0.5,
    val gndFeather: Double = 0.15,
    val gndAngleDeg: Double = 0.0,
    val blackMistStrength: Double = 0.0,
    val blackMistThreshold: Double = 0.75,
    val blackMistRadiusPx: Double = 8.0,
    val blackMistWarmth: Double = 0.12,
    val starPoints: Int = 0,
    val starStrength: Double = 0.0,
    val starThreshold: Double = 0.85,
    val starRadiusPx: Double = 14.0,
    val starAngleDeg: Double = 0.0,
    val uvEnabled: Boolean = false,
    val uvVeilingGlare: Double = 0.0,
    val uvGhostIntensity: Double = 0.0,
    val uvSoftness: Double = 0.0,
    val uvTintR: Double = 1.0,
    val uvTintG: Double = 1.0,
    val uvTintB: Double = 1.0,
    val uvGhostRadiusPx: Double = 8.0,
    internal val pupilMask: OpticalPupilMask? = null,
    internal val pupilTransformFlags: Int = 0,
) {
    /** Upper mode bits carry the cutout transform without changing the 128-byte native optics ABI. */
    internal fun withPupil(mask: OpticalPupilMask?): OpticalFilterStackConfig {
        if (mask == null) {
            return if (pupilMask == null && pupilTransformFlags == 0) {
                this
            } else {
                copy(pupilMask = null, pupilTransformFlags = 0)
            }
        }
        val turns = (mask.angleDeg % 360.0) / 360.0
        val angle = (turns * 16384.0).roundToInt() and 0x3FFF
        val shrink = ((1.0 - mask.scale.coerceIn(0.4, 1.0)) * (16383.0 / 0.6)).roundToInt()
        val cutout = if (mask.shape == BokehMaskShape.HEXAGON) 8 else 0
        val flags = cutout or (angle shl 4) or (shrink shl 18)
        if (mask === pupilMask && flags == pupilTransformFlags) return this
        return copy(pupilMask = mask, pupilTransformFlags = flags)
    }

    val enableFlags: Int
        get() {
            var f = 0
            if (ndStops > 1e-4) f = f or FLAG_ND
            if (gndStops > 1e-4) f = f or FLAG_GND
            if (blackMistStrength > 1e-4 && blackMistRadiusPx >= 0.5) f = f or FLAG_BLACK_MIST
            if (starPoints in setOf(4, 6, 8) && starStrength > 1e-4 && starRadiusPx >= 1.0) f = f or FLAG_STAR_CROSS
            val hasUvTint = kotlin.math.abs(uvTintR - 1.0) > 1e-4 ||
                kotlin.math.abs(uvTintG - 1.0) > 1e-4 ||
                kotlin.math.abs(uvTintB - 1.0) > 1e-4
            if (uvEnabled && (uvVeilingGlare > 1e-4 || uvGhostIntensity > 1e-4 || uvSoftness > 1e-4 || hasUvTint)) {
                f = f or FLAG_UV
            }
            return f
        }

    val isBypassed: Boolean get() = enableFlags == 0

    companion object {
        const val FLAG_ND = 1
        const val FLAG_GND = 2
        const val FLAG_BLACK_MIST = 4
        const val FLAG_STAR_CROSS = 8
        const val FLAG_UV = 16

        val NONE = OpticalFilterStackConfig()
    }
}

/**
 * External GPU frame handle type for Phase 2 zero-copy Game RGB+Depth ingestion.
 */
enum class ExternalGpuHandleType(val nativeCode: Int) {
    NONE(0),
    WIN32_OPAQUE_HANDLE(1),
    WIN32_KMT_HANDLE(2),
    WIN32_D3D11_TEXTURE(3),
    LINUX_OPAQUE_FD(4),
    LINUX_DMA_BUF_FD(5),
}

/**
 * Descriptor for zero-copy GPU external RGB+Depth frame ingestion (`VK_KHR_external_memory_win32` /
 * `VK_KHR_external_memory_fd` + external timeline/binary semaphore synchronization).
 */
data class ExternalGpuFrameHandle(
    val handleType: ExternalGpuHandleType,
    val width: Int,
    val height: Int,
    val colorHandle: Long,
    val colorMemorySizeBytes: Long,
    val colorPitchBytes: Int = 0,
    val colorVkFormat: Int = 0,
    val depthHandle: Long = 0L,
    val depthMemorySizeBytes: Long = 0L,
    val depthPitchBytes: Int = 0,
    val depthVkFormat: Int = 0,
    val waitSemaphoreHandle: Long = 0L,
    val signalSemaphoreHandle: Long = 0L,
    val timelineWaitValue: Long = 0L,
    val timelineSignalValue: Long = 0L,
    val flags: Int = 0,
)

/**
 * Structured runtime diagnostics for optical compute backend selection and performance.
 */
data class OpticsBackendDiagnostics(
    val activeBackend: ActiveOpticsBackend,
    val preference: OpticsBackendPreference,
    val deviceName: String,
    val deviceType: String,
    val vulkanApiVersion: String,
    val loaderName: String?,
    val nativeBridgeLoaded: Boolean,
    val externalMemoryCaps: Int,
    val lastFrameMs: Double,
    val framesRendered: Long,
    val fallbackReason: String?,
    val deviceIndex: Int = -1,
    val requestedDevice: String = "auto",
    val pipelineCacheHit: Boolean = false,
    val pipelineCacheBytes: Long = 0L,
    val pipelineWarmupMs: Double = 0.0,
    val specializedPipelineCount: Int = 0,
    val bufferReallocCount: Int = 0,
    val hostCachedReadback: Boolean = false,
) {
    // The native ABI reports semaphore capabilities in bits 3 and 4, separately from memory handles.
    val externalMemorySupported: Boolean get() = externalMemoryCaps and 0x3 != 0

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "backend" to activeBackend.name,
        "preference" to preference.configValue,
        "requestedDevice" to requestedDevice,
        "deviceIndex" to deviceIndex,
        "deviceName" to deviceName,
        "deviceType" to deviceType,
        "vulkanApiVersion" to vulkanApiVersion,
        "loaderName" to (loaderName ?: ""),
        "nativeBridgeLoaded" to nativeBridgeLoaded,
        "externalMemorySupported" to externalMemorySupported,
        "externalMemoryCaps" to externalMemoryCaps,
        "lastFrameMs" to lastFrameMs,
        "framesRendered" to framesRendered,
        "fallbackReason" to (fallbackReason ?: ""),
        "pipelineCacheHit" to pipelineCacheHit,
        "pipelineCacheBytes" to pipelineCacheBytes,
        "pipelineWarmupMs" to pipelineWarmupMs,
        "specializedPipelineCount" to specializedPipelineCount,
        "bufferReallocCount" to bufferReallocCount,
        "hostCachedReadback" to hostCachedReadback,
    )
}

/**
 * Telemetry for the bounded 32-entry custom SPIR-V LRU pipeline cache (Plugin SPI v1.0).
 */
data class CustomSpirvCacheStats(
    val capacity: Int,
    val entries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
)

/**
 * Pluggable compute backend contract for optical lens simulation + CMOS Bayer readout.
 */
interface OpticalComputeBackend : AutoCloseable {
    val activeBackend: ActiveOpticsBackend

    fun diagnostics(): OpticsBackendDiagnostics

    fun supportsPupilTransform(): Boolean = false

    fun supportsExternalGpuImport(): Boolean = diagnostics().externalMemorySupported

    fun validateExternalFrameDescriptor(descriptor: ExternalGpuFrameDescriptor): Int = -6

    /**
     * Validates and pre-compiles (or hits the 32-entry LRU cache for) all custom SPIR-V plugin
     * passes. Returns `null` when every pass is ready to run on GPU, or a human-readable reason
     * string when Vulkan is inactive or any pass was rejected (without poisoning the GPU context).
     */
    fun validateCustomSpirvPasses(passes: List<CustomSpirvPassSpec>): String? =
        if (passes.isEmpty()) null else "Custom SPIR-V execution requires active Vulkan compute backend"

    /** Returns LRU pipeline cache statistics for Plugin SPI v1.0 custom `.spv` shaders, or `null`. */
    fun customSpirvCacheStats(): CustomSpirvCacheStats? = null

    fun renderLinearRgb(
        source: LinearRgbImage,
        dstWidth: Int = source.width,
        dstHeight: Int = source.height,
        profile: OpticalLensProfile = OpticalLensProfile.IDENTITY,
        ibis: IbisShiftState = IbisShiftState.ZERO,
        depthMeters: FloatArray? = null,
        planarDepthM: Double = profile.focusDistanceM,
        pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
        maxBokehRadiusPx: Int = 28,
        samplingMode: SamplingMode = SamplingMode.BILINEAR,
        borderMode: BorderMode = BorderMode.BLACK,
        filterStack: OpticalFilterStackConfig = OpticalFilterStackConfig.NONE,
        sceneCacheKey: Long = 0L,
        customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
        frameIndex: Long = 0L,
    ): LinearRgbImage

    fun renderToBayer(
        source: LinearRgbImage,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        ibis: IbisShiftState = IbisShiftState.ZERO,
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        destination: ByteBuffer,
        depthMeters: FloatArray? = null,
        planarDepthM: Double = profile.focusDistanceM,
        pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
        maxBokehRadiusPx: Int = 28,
        samplingMode: SamplingMode = SamplingMode.BILINEAR,
        borderMode: BorderMode = BorderMode.BLACK,
        frameIndex: Long = 0L,
        filterStack: OpticalFilterStackConfig = OpticalFilterStackConfig.NONE,
        sceneCacheKey: Long = 0L,
        customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
    ): Int

    /**
     * Executes only Stage-2 CMOS noise + Bayer mosaic readout on planar linear RGB planes
     * (used after a Stage-1 optical cache HIT when AE changes shutter duration or ISO).
     */
    fun renderCmosBayer(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        destination: ByteBuffer,
        frameIndex: Long = 0L,
        parallelThreads: Int = 1,
    ): Int = com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseModel.mosaicAndReadout(
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

    /** Returns `true` when fused GPU Optics->ISP can emit 9,280-byte GPU ISP stats in the same submit. */
    fun supportsFusedIspStats(): Boolean = false

    /**
     * Optional GPU-resident fused Optics -> CMOS Bayer -> LiveView ISP Demosaic [+ Peaking] path.
     * Keeps the intermediate Bayer frame strictly in GPU memory with zero host readback.
     * Returns `true` if executed on GPU, or `false` when unavailable/unsupported.
     */
    fun tryRenderOpticsToIspYuv(
        source: LinearRgbImage,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        ibis: IbisShiftState = IbisShiftState.ZERO,
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        depthMeters: FloatArray? = null,
        planarDepthM: Double = profile.focusDistanceM,
        pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
        maxBokehRadiusPx: Int = 28,
        borderMode: BorderMode = BorderMode.BLACK,
        frameIndex: Long = 0L,
        filterStack: OpticalFilterStackConfig = OpticalFilterStackConfig.NONE,
        lutR: IntArray? = null,
        lutG: IntArray? = null,
        lutB: IntArray? = null,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        ispParams: VulkanNativeOpticsBridge.IspDemosaicParamsStruct,
        sceneCacheKey: Long = 0L,
        customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
        statsRequest: LiveViewIspStatsRequest? = null,
        outStatsWords: IntArray? = null,
    ): Boolean = false

    /** Returns `true` when GPU-resident 3D scene rasterization into DEVICE_LOCAL VRAM is supported. */
    fun supportsGpuScene3d(): Boolean = false

    /**
     * Renders [spec] straight into DEVICE_LOCAL VRAM and executes fused Pass 0..5
     * (`Filter -> Warp -> Bokeh -> CMOS -> ISP -> YUV`) with ZERO PCIe scene upload.
     * Returns the 16x16-tile metric depth grid snapshot on success, or `null` to fall back to CPU.
     */
    fun tryRenderGpuScene3dToIspYuv(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        ibis: IbisShiftState = IbisShiftState.ZERO,
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
        maxBokehRadiusPx: Int = 28,
        borderMode: BorderMode = BorderMode.BLACK,
        frameIndex: Long = 0L,
        filterStack: OpticalFilterStackConfig = OpticalFilterStackConfig.NONE,
        lutR: IntArray? = null,
        lutG: IntArray? = null,
        lutB: IntArray? = null,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        ispParams: VulkanNativeOpticsBridge.IspDemosaicParamsStruct,
        customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
        statsRequest: LiveViewIspStatsRequest? = null,
        outStatsWords: IntArray? = null,
    ): VulkanScene3dRenderer.DepthGridSnapshot? = null

    /**
     * Renders [spec] straight into DEVICE_LOCAL VRAM and executes Pass 0..3
     * (`Filter -> Warp -> Bokeh -> CMOS Bayer`) into [destination] with ZERO PCIe scene upload.
     * Returns `Pair(bytesWritten, depthGridSnapshot)` on success, or `null` to fall back to CPU.
     */
    fun tryRenderGpuScene3dToBayer(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        exposure: Exposure,
        profile: OpticalLensProfile = OpticalLensProfile.fromLensParameters(exposure.lens),
        ibis: IbisShiftState = IbisShiftState.ZERO,
        noiseConfig: CmosNoiseConfig = CmosNoiseConfig(),
        destination: ByteBuffer,
        pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
        maxBokehRadiusPx: Int = 28,
        borderMode: BorderMode = BorderMode.BLACK,
        frameIndex: Long = 0L,
        filterStack: OpticalFilterStackConfig = OpticalFilterStackConfig.NONE,
        customSpirvPasses: List<CustomSpirvPassSpec> = emptyList(),
    ): Pair<Int, VulkanScene3dRenderer.DepthGridSnapshot>? = null
}
