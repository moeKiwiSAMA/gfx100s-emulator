package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.CompositeOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import com.github.nyabanban.fuji.emulator.api.IbisShiftPort
import com.github.nyabanban.fuji.emulator.api.LensOpticalHook
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.ActiveOpticsBackend
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.BokehPupilShape
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticalComputeBackend
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticalFilterStackConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticalFilterStackTranslator
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.VulkanNativeOpticsBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.abs

/**
 * Pluggable pure-Kotlin [OpticalSceneFilter] wrapping [CpuOpticalPipeline] and a reusable
 * [OpticalFrameWorkspace] so live CMOS streams (static QR, FFmpeg pipes, TCP/HTTP/UDP
 * external streams) can layer lens distortion, lateral/longitudinal CA, vignetting,
 * depth-buffer CoC bokeh, 5-axis IBIS offset, and physical CMOS noise in real time.
 *
 * Defaults to `enabled = false` (pass-through bypassed by caller) so default QR and
 * deterministic `discovery` tests keep zero overhead.
 *
 * **Evidence classification (`HYPOTHESIS` / `SIM`)**: Analytical thin-lens, aberration,
 * IBIS, and CMOS noise parameters are synthetic physical simulation models, not factory
 * GFX100S calibration tables.
 */
class LensOpticalSceneFilter(
    enabled: Boolean = false,
    profile: OpticalLensProfile = OpticalLensProfile.IDENTITY,
    ibis: IbisShiftState = IbisShiftState.ZERO,
    noiseConfig: CmosNoiseConfig = CmosNoiseConfig.NOISELESS,
    samplingMode: SamplingMode = SamplingMode.BILINEAR,
    borderMode: BorderMode = BorderMode.BLACK,
    private val maxBokehRadiusPx: Int = CpuOpticalPipeline.DEFAULT_MAX_BOKEH_RADIUS_PX,
    planarSubjectDistanceM: Double? = null,
    private val ibisShiftPortProvider: () -> IbisShiftPort? = { null },
    private val clockTickProvider: () -> Long = { 0L },
    val parallelThreads: Int = OpticalParallelExecutor.defaultThreadCount,
    private val computeBackendProvider: () -> OpticalComputeBackend? = { null },
    pupilShape: BokehPupilShape = BokehPupilShape.CIRCLE,
    private val opticalFilterStackProvider: () -> OpticalFilterStackPort? = { null },
) : OpticalSceneFilter {
    private val pipeline = CpuOpticalPipeline(
        maxBokehRadiusPx = maxBokehRadiusPx,
        parallelThreads = parallelThreads,
    )
    private val workspace = OpticalFrameWorkspace()

    private data class FilterConfigSnapshot(
        val enabled: Boolean,
        val profile: OpticalLensProfile,
        val ibis: IbisShiftState,
        val noiseConfig: CmosNoiseConfig,
        val samplingMode: SamplingMode,
        val borderMode: BorderMode,
        val planarSubjectDistanceM: Double?,
        val pupilShape: BokehPupilShape,
        val lensOpticalHook: LensOpticalHook?,
        val configVersion: Long,
        val opticalConfigVersion: Long,
    )

    private val configLock = Any()
    private val renderLock = Any()

    @Volatile
    private var activeConfig: FilterConfigSnapshot = FilterConfigSnapshot(
        enabled = enabled,
        profile = profile,
        ibis = ibis,
        noiseConfig = noiseConfig,
        samplingMode = samplingMode,
        borderMode = borderMode,
        planarSubjectDistanceM = planarSubjectDistanceM,
        pupilShape = pupilShape,
        lensOpticalHook = null,
        configVersion = 0L,
        opticalConfigVersion = 0L,
    )

    override val enabled: Boolean get() = activeConfig.enabled
    val profile: OpticalLensProfile get() = activeConfig.profile
    val ibis: IbisShiftState get() = activeConfig.ibis
    val noiseConfig: CmosNoiseConfig get() = activeConfig.noiseConfig
    val samplingMode: SamplingMode get() = activeConfig.samplingMode
    val borderMode: BorderMode get() = activeConfig.borderMode
    val planarSubjectDistanceM: Double? get() = activeConfig.planarSubjectDistanceM
    val pupilShape: BokehPupilShape get() = activeConfig.pupilShape
    val lensOpticalHook: LensOpticalHook? get() = activeConfig.lensOpticalHook

    private var lastFallbackLogReason: String? = null

    @Volatile
    var framesFiltered: Long = 0L
        private set

    @Volatile
    var lastCenterDepthMeters: Double? = null
        private set

    @Volatile
    var lastFrameHadDepth: Boolean = false
        private set

    @Volatile
    internal var lastGpuDepthGrid: com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.VulkanScene3dRenderer.DepthGridSnapshot? = null
        private set

    internal var vulkanScene3dFrames: Long = 0L
        private set

    // Stage-1 Optical Linear-RGB cache: post-warp + post-bokeh linear RGB planes are strictly
    // invariant to exposure.durationNanos, exposure.iso, lens.transmission, and CmosNoiseConfig.
    // Whenever firmware AE adjusts shutter/ISO on a steady scene, Stage-1 hits and runs only
    // Stage-2 CMOS readout (Vulkan GPU or CmosNoiseModel) instead of repeating warp + CoC bokeh.
    private var optCachedR: FloatArray? = null
    private var optCachedG: FloatArray? = null
    private var optCachedB: FloatArray? = null
    private var optCachedBackend: ActiveOpticsBackend? = null
    private var optCachedConfigVersion: Long = -1L
    private var optCachedSrcWidth: Int = -1
    private var optCachedSrcHeight: Int = -1
    private var optCachedRed: FloatArray? = null
    private var optCachedGreen: FloatArray? = null
    private var optCachedBlue: FloatArray? = null
    private var optCachedDepth: FloatArray? = null
    private var optCachedSrcFingerprint: Long = 0L
    private var optCachedCenterDepthMeters: Double? = null
    private var optCachedHadDepth: Boolean = false
    private var optCachedLensApertureBits: Long = 0L
    private var optCachedLensFocalBits: Long = 0L
    private var optCachedRefFocalBits: Long = 0L
    private var optCachedLensFocusBits: Long = 0L
    private var optCachedLensBreathingBits: Long = 0L
    private var optCachedGeomWidth: Int = -1
    private var optCachedGeomHeight: Int = -1
    private var optCachedGeomPitchBits: Long = 0L
    private var optCachedIbisShiftXBits: Long = 0L
    private var optCachedIbisShiftYBits: Long = 0L
    private var optCachedIbisRollBits: Long = 0L
    private var optCachedPlanarDistBits: Long = 0L
    private var optCachedPlanarDistPresent: Boolean = false
    private var optCachedPupilMaskKey: Long = 0L
    private var optCachedSpatialFilterKey: Long = 0L
    private var optCachedLensHookKey: Long = 0L

    // Last single-submit GPU source key: when an identical source/optical/filter frame arrives
    // again with only AE (durationNanos / iso / ndStops) changed, promote into Stage-1 cache so
    // repeated AE updates skip Pass 0..2 completely!
    private var lastSingleSubmitSrcFp: Long = 0L
    private var lastSingleSubmitOpticalKey: Long = 0L
    private var lastRenderedSrcFp: Long = 0L
    private var lastRenderedFocusBits: Long = Long.MIN_VALUE

    // Stage-2 Single-slot noiseless Bayer cache: when noise is disabled and source + optical config +
    // exposure + geometry are unchanged, repeated LiveView frames copy the pre-rendered Bayer
    // buffer in O(1) (~0.1 ms) instead of re-running optical warp + bokeh + mosaic every tick.
    private var cachedBayer: ByteArray? = null
    private var cachedByteCount: Int = -1
    private var cachedBackend: ActiveOpticsBackend? = null
    private var cachedConfigVersion: Long = -1L
    private var cachedSrcWidth: Int = -1
    private var cachedSrcHeight: Int = -1
    private var cachedRed: FloatArray? = null
    private var cachedGreen: FloatArray? = null
    private var cachedBlue: FloatArray? = null
    private var cachedDepth: FloatArray? = null
    private var cachedSrcFingerprint: Long = 0L
    private var cachedCenterDepthMeters: Double? = null
    private var cachedHadDepth: Boolean = false
    private var cachedDurationNanos: Long = -1L
    private var cachedIso: Int = -1
    private var cachedLensApertureBits: Long = 0L
    private var cachedLensTransmissionBits: Long = 0L
    private var cachedLensFocalBits: Long = 0L
    private var cachedRefFocalBits: Long = 0L
    private var cachedLensFocusBits: Long = 0L
    private var cachedLensBreathingBits: Long = 0L
    private var cachedGeomWidth: Int = -1
    private var cachedGeomHeight: Int = -1
    private var cachedGeomBitDepth: Int = -1
    private var cachedGeomBayer: BayerPattern? = null
    private var cachedGeomPitchBits: Long = 0L
    private var cachedIbisShiftXBits: Long = 0L
    private var cachedIbisShiftYBits: Long = 0L
    private var cachedIbisRollBits: Long = 0L
    private var cachedPlanarDistBits: Long = 0L
    private var cachedPlanarDistPresent: Boolean = false
    private var cachedFilterTransBits: Long = 0L
    private var cachedPupilMaskKey: Long = 0L
    private var cachedSpatialFilterKey: Long = 0L
    private var cachedLensHookKey: Long = 0L

    internal var cacheHits: Long = 0L
        private set
    internal var cacheMisses: Long = 0L
        private set
    internal var opticalCacheHits: Long = 0L
        private set
    internal var opticalCacheMisses: Long = 0L
        private set
    internal var vulkanStage1Frames: Long = 0L
        private set
    internal var vulkanStage2Frames: Long = 0L
        private set
    internal var vulkanSingleSubmitFrames: Long = 0L
        private set
    internal var vulkanFusedIspFrames: Long = 0L
        private set
    internal var sourceFingerprintComputations: Long = 0L
        private set

    override fun opticalStateVersion(): Long {
        val cfg = activeConfig
        if (cfg.noiseConfig.enableNoise) return -1L
        val filterRev = opticalFilterStackProvider()?.revision ?: 0L
        return ((cfg.configVersion * 1099511628211L) xor (filterRev * -4265267296055464877L)) and Long.MAX_VALUE
    }

    private fun resolveHookSpirvPass(
        hook: LensOpticalHook?,
        width: Int,
        height: Int,
        profile: OpticalLensProfile,
    ): CustomSpirvPassSpec? {
        if (hook == null) return null
        return runCatching {
            hook.vulkanSpirvPass(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                focusDistanceM = profile.focusDistanceM,
                fNumber = profile.fNumber,
                focalLengthMm = profile.effectiveFocalLengthMm(),
            )
        }.getOrNull()
    }

    private fun cpuOpticsFallbackReason(
        activeMask: OpticalPupilMask?,
        hook: LensOpticalHook?,
        hookSpirvPass: CustomSpirvPassSpec?,
        backend: OpticalComputeBackend?,
    ): String? {
        if (activeMask != null && (activeMask.shape == BokehMaskShape.CUSTOM || activeMask is CompositeOpticalPupilMask)) {
            return "custom/composite OpticalPupilMask (${activeMask.javaClass.simpleName})"
        }
        if (activeMask != null &&
            (activeMask.shape == BokehMaskShape.HEXAGON || activeMask.angleDeg % 360.0 != 0.0 || activeMask.scale < 1.0) &&
            backend?.supportsPupilTransform() != true) {
            return "Vulkan backend lacks a transformed entrance-pupil mask"
        }
        if (hook != null) {
            if (hookSpirvPass == null) {
                return "specialty LensOpticalHook (${hook.javaClass.simpleName})"
            }
            if (backend == null || backend.activeBackend != ActiveOpticsBackend.VULKAN) {
                return "specialty LensOpticalHook (${hook.javaClass.simpleName}) [Vulkan inactive]"
            }
            val spvErr = backend.validateCustomSpirvPasses(listOf(hookSpirvPass))
            if (spvErr != null) {
                return "custom SPIR-V LensOpticalHook (${hook.javaClass.simpleName}): $spvErr"
            }
        }
        return null
    }

    override fun handlesSpatialFilterStack(filterStack: OpticalFilterStackPort?): Boolean {
        val cfg = activeConfig
        if (!cfg.enabled) return false
        val backend = computeBackendProvider() ?: return false
        if (backend.activeBackend != ActiveOpticsBackend.VULKAN) return false
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, 64, 64, cfg.profile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return false
        if (!OpticalFilterStackTranslator.canOffloadSpatial(filterStack)) return false
        val translated = OpticalFilterStackTranslator.translate(filterStack, 64, 64)
        if (!translated.canOffloadSpatialToGpu) return false
        if (translated.customSpirvPasses.isNotEmpty() && backend.validateCustomSpirvPasses(translated.customSpirvPasses) != null) {
            return false
        }
        return true
    }

    /**
     * Atomically updates the active optical filter configuration without ever blocking
     * on an in-flight background render (lock-free read snapshot for [renderLock] consumers).
     */
    fun update(
        enabled: Boolean = this.enabled,
        profile: OpticalLensProfile = this.profile,
        ibis: IbisShiftState = this.ibis,
        noiseConfig: CmosNoiseConfig = this.noiseConfig,
        samplingMode: SamplingMode = this.samplingMode,
        borderMode: BorderMode = this.borderMode,
        planarSubjectDistanceM: Double? = this.planarSubjectDistanceM,
        pupilShape: BokehPupilShape = this.pupilShape,
        lensOpticalHook: LensOpticalHook? = this.lensOpticalHook,
    ) {
        synchronized(configLock) {
            val cur = activeConfig
            val hookChanged = cur.lensOpticalHook !== lensOpticalHook ||
                (cur.lensOpticalHook?.cacheKey() ?: 0L) != (lensOpticalHook?.cacheKey() ?: 0L)
            val opticalChanged = cur.enabled != enabled ||
                cur.profile != profile ||
                cur.ibis != ibis ||
                cur.samplingMode != samplingMode ||
                cur.borderMode != borderMode ||
                cur.planarSubjectDistanceM != planarSubjectDistanceM ||
                cur.pupilShape != pupilShape ||
                hookChanged
            val changed = opticalChanged || cur.noiseConfig != noiseConfig
            if (!changed) return
            activeConfig = FilterConfigSnapshot(
                enabled = enabled,
                profile = profile,
                ibis = ibis,
                noiseConfig = noiseConfig,
                samplingMode = samplingMode,
                borderMode = borderMode,
                planarSubjectDistanceM = planarSubjectDistanceM,
                pupilShape = pupilShape,
                lensOpticalHook = lensOpticalHook,
                configVersion = cur.configVersion + 1L,
                opticalConfigVersion = if (opticalChanged) cur.opticalConfigVersion + 1L else cur.opticalConfigVersion,
            )
        }
    }

    // Frame sequences are local to their source. Swapping two equal-size PNGs restarts
    // at sequence 1, while a stream can reuse its pixel planes with a new sequence.
    private var lastSequencedFrame: LinearRadianceFrame? = null
    private var lastSequencedKey = 0L

    private fun sequencedSourceKey(frame: LinearRadianceFrame): Long {
        if (frame.sequence <= 0L) return 0L
        val previous = lastSequencedFrame
        if (previous === frame) return lastSequencedKey
        if (previous == null || previous.sequence != frame.sequence ||
            previous.width != frame.width || previous.height != frame.height ||
            previous.red !== frame.red || previous.green !== frame.green ||
            previous.blue !== frame.blue || previous.depthMeters !== frame.depthMeters
        ) {
            lastSequencedKey = SOURCE_KEY.incrementAndGet() * 0x9E3779B97F4A7C15UL.toLong()
        }
        lastSequencedFrame = frame
        return lastSequencedKey
    }

    override fun filterRadianceFrame(
        frame: LinearRadianceFrame,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long,
    ): Int = synchronized(renderLock) {
        val cfg = activeConfig
        filterLinearRgbToBayerInternal(
            cfg = cfg,
            srcWidth = frame.width,
            srcHeight = frame.height,
            red = frame.red,
            green = frame.green,
            blue = frame.blue,
            depthMeters = frame.depthMeters,
            effectivePlanarDistM = cfg.planarSubjectDistanceM ?: frame.virtualSubjectDistanceM,
            is3DScene = frame.dimension == SceneDimension.DIM_3D,
            rendered3DFocalLengthMm = if (frame.dimension == SceneDimension.DIM_3D) frame.pose.focalLengthMm else null,
            sourceKey = sequencedSourceKey(frame),
            exposure = exposure,
            geometry = geometry,
            destination = destination,
            frameIndex = frameIndex,
        )
    }

    override fun supportsFusedIspStats(): Boolean {
        val cfg = activeConfig
        if (!cfg.enabled) return false
        val filterStack = opticalFilterStackProvider()
        val backend = computeBackendProvider() ?: return false
        if (!backend.supportsFusedIspStats()) return false
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, 64, 64, cfg.profile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return false
        return if (filterStack != null && filterStack.hasActiveSpatialEffects()) {
            handlesSpatialFilterStack(filterStack)
        } else {
            true
        }
    }

    override fun sensorBlackLevelAdu(bitDepth: Int): Int {
        val maxAdu = (1 shl bitDepth.coerceIn(8, 16)) - 1
        return activeConfig.noiseConfig.blackLevel.coerceIn(0, maxAdu)
    }

    override fun hasHotStage1CacheForExposureOnlyChange(): Boolean =
        optCachedR != null && optCachedG != null && optCachedB != null

    override fun tryFilterRadianceToIspYuv(
        frame: LinearRadianceFrame,
        geometry: SensorGeometry,
        request: LiveViewIspFusionRequest,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray?,
        frameIndex: Long,
    ): Boolean = synchronized(renderLock) {
        val cfg = activeConfig
        if (!cfg.enabled) return false
        val backend = computeBackendProvider() ?: return false
        if (backend.activeBackend != ActiveOpticsBackend.VULKAN) return false
        val filterStack = opticalFilterStackProvider()
        val gpuFilterTranslation = OpticalFilterStackTranslator.translate(filterStack, frame.width, frame.height)
        if (!gpuFilterTranslation.canOffloadSpatialToGpu) return false

        val is3DScene = frame.dimension == SceneDimension.DIM_3D
        val rendered3DFocalLengthMm = if (is3DScene) frame.pose.focalLengthMm else null
        val effectiveProfile = if (
            rendered3DFocalLengthMm != null &&
            abs(rendered3DFocalLengthMm - cfg.profile.focalLengthMm) <= 0.25 &&
            cfg.profile.referenceFocalLengthMm != cfg.profile.focalLengthMm
        ) {
            cfg.profile.copy(referenceFocalLengthMm = cfg.profile.focalLengthMm)
        } else {
            cfg.profile
        }
        val liveIbisPort = ibisShiftPortProvider()
        val effectiveIbis = if (liveIbisPort != null) {
            val (dxPx, dyPx, dRoll) = if (is3DScene) {
                val pitch = geometry.pitchMicrometres.coerceAtLeast(0.5)
                Triple(
                    liveIbisPort.shiftXUm / pitch,
                    liveIbisPort.shiftYUm / pitch,
                    Math.toRadians(liveIbisPort.rollDeg),
                )
            } else {
                liveIbisPort.sampleShiftPixels(geometry.pitchMicrometres, clockTickProvider())
            }
            if (dxPx != 0.0 || dyPx != 0.0 || dRoll != 0.0) {
                IbisShiftState(
                    shiftXPixels = (cfg.ibis.shiftXPixels + dxPx).coerceIn(-512.0, 512.0),
                    shiftYPixels = (cfg.ibis.shiftYPixels + dyPx).coerceIn(-512.0, 512.0),
                    rollRadians = (cfg.ibis.rollRadians + dRoll).coerceIn(-Math.PI, Math.PI),
                )
            } else {
                cfg.ibis
            }
        } else {
            cfg.ibis
        }
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, geometry.width, geometry.height, effectiveProfile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return false
        val customPasses = buildList {
            addAll(gpuFilterTranslation.customSpirvPasses)
            if (hookPass != null) add(hookPass)
        }
        if (customPasses.isNotEmpty() && backend.validateCustomSpirvPasses(customPasses) != null) return false
        val (vulkanProfile, effectivePupilShape) = resolveVulkanProfileAndPupil(effectiveProfile, activeMask, cfg.pupilShape)
        val depthMeters = frame.depthMeters
        if (depthMeters != null && depthMeters.size == frame.width * frame.height) {
            lastCenterDepthMeters = sampleCenterAfDepthMeters(depthMeters, frame.width, frame.height)
            lastFrameHadDepth = true
        } else {
            lastCenterDepthMeters = null
            lastFrameHadDepth = false
        }
        val sceneKey = sequencedSourceKey(frame)
        val image = LinearRgbImage(
            width = frame.width,
            height = frame.height,
            red = frame.red,
            green = frame.green,
            blue = frame.blue,
        )
        val ispParams = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(
            srcWidth = geometry.width,
            srcHeight = geometry.height,
            dstWidth = request.dstWidth,
            dstHeight = request.dstHeight,
            is16Bit = geometry.bitDepth > 8,
            outputFormat = request.outputFormat,
            dstStrideBytes = request.dstStrideBytes,
            flags = request.flags,
            offX = request.offX,
            offY = request.offY,
            fitW = request.fitW,
            fitH = request.fitH,
            gainR1024 = request.gainR1024,
            gainG1024 = request.gainG1024,
            gainB1024 = request.gainB1024,
            yuvShift = request.yuvShift,
            ccm = request.ccm,
            yuvMat = request.yuvMat,
            peakingThreshold = request.peakingThreshold,
            peakingHiY = request.peakingHiY,
            peakingHiCb = request.peakingHiCb,
            peakingHiCr = request.peakingHiCr,
        )
        val wantStats = request.statsRequest != null && request.outStatsWords != null
        val ok = backend.tryRenderOpticsToIspYuv(
            source = image,
            geometry = geometry,
            exposure = request.exposure,
            profile = vulkanProfile,
            ibis = effectiveIbis,
            noiseConfig = cfg.noiseConfig,
            depthMeters = depthMeters,
            planarDepthM = (cfg.planarSubjectDistanceM ?: frame.virtualSubjectDistanceM) ?: vulkanProfile.focusDistanceM,
            pupilShape = effectivePupilShape,
            maxBokehRadiusPx = maxBokehRadiusPx,
            borderMode = cfg.borderMode,
            frameIndex = frameIndex,
            filterStack = gpuFilterTranslation.fullConfigWithNd.withPupil(activeMask),
            lutR = request.lutR,
            lutG = request.lutG,
            lutB = request.lutB,
            outYOrPacked = outYOrPacked,
            outUvPlane = outUvPlane,
            ispParams = ispParams,
            sceneCacheKey = sceneKey,
            customSpirvPasses = customPasses,
            statsRequest = request.statsRequest,
            outStatsWords = request.outStatsWords,
        )
        if (ok) {
            if (wantStats && backend.supportsFusedIspStats()) {
                request.statsProduced = true
                request.statsGpuMs = backend.diagnostics().lastFrameMs
            }
            vulkanFusedIspFrames++
            framesFiltered++
        }
        ok
    }

    override fun supportsGpuScene3d(): Boolean {
        val cfg = activeConfig
        if (!cfg.enabled) return false
        val filterStack = opticalFilterStackProvider()
        val backend = computeBackendProvider() ?: return false
        if (!backend.supportsGpuScene3d()) return false
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, 64, 64, cfg.profile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return false
        return if (filterStack != null && filterStack.hasActiveSpatialEffects()) {
            handlesSpatialFilterStack(filterStack)
        } else {
            true
        }
    }

    override fun tryFilterGpuScene3dToIspYuv(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        request: LiveViewIspFusionRequest,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray?,
        frameIndex: Long,
    ): Boolean = synchronized(renderLock) {
        val cfg = activeConfig
        if (!cfg.enabled) return false
        val backend = computeBackendProvider() ?: return false
        if (!backend.supportsGpuScene3d()) return false
        val filterStack = opticalFilterStackProvider()
        val gpuFilterTranslation = OpticalFilterStackTranslator.translate(filterStack, spec.width, spec.height)
        if (!gpuFilterTranslation.canOffloadSpatialToGpu) return false
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }

        val effectiveProfile = if (
            abs(spec.pose.focalLengthMm - cfg.profile.focalLengthMm) <= 0.25 &&
            cfg.profile.referenceFocalLengthMm != cfg.profile.focalLengthMm
        ) {
            cfg.profile.copy(referenceFocalLengthMm = cfg.profile.focalLengthMm)
        } else {
            cfg.profile
        }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, geometry.width, geometry.height, effectiveProfile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return false
        val customPasses = buildList {
            addAll(gpuFilterTranslation.customSpirvPasses)
            if (hookPass != null) add(hookPass)
        }
        if (customPasses.isNotEmpty() && backend.validateCustomSpirvPasses(customPasses) != null) return false
        val liveIbisPort = ibisShiftPortProvider()
        val effectiveIbis = if (liveIbisPort != null) {
            val pitch = geometry.pitchMicrometres.coerceAtLeast(0.5)
            val dxPx = liveIbisPort.shiftXUm / pitch
            val dyPx = liveIbisPort.shiftYUm / pitch
            val dRoll = Math.toRadians(liveIbisPort.rollDeg)
            if (dxPx != 0.0 || dyPx != 0.0 || dRoll != 0.0) {
                IbisShiftState(
                    shiftXPixels = (cfg.ibis.shiftXPixels + dxPx).coerceIn(-512.0, 512.0),
                    shiftYPixels = (cfg.ibis.shiftYPixels + dyPx).coerceIn(-512.0, 512.0),
                    rollRadians = (cfg.ibis.rollRadians + dRoll).coerceIn(-Math.PI, Math.PI),
                )
            } else cfg.ibis
        } else cfg.ibis
        val (vulkanProfile, effectivePupilShape) = resolveVulkanProfileAndPupil(effectiveProfile, activeMask, cfg.pupilShape)
        val ispParams = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(
            srcWidth = geometry.width,
            srcHeight = geometry.height,
            dstWidth = request.dstWidth,
            dstHeight = request.dstHeight,
            is16Bit = geometry.bitDepth > 8,
            outputFormat = request.outputFormat,
            dstStrideBytes = request.dstStrideBytes,
            flags = request.flags,
            offX = request.offX,
            offY = request.offY,
            fitW = request.fitW,
            fitH = request.fitH,
            gainR1024 = request.gainR1024,
            gainG1024 = request.gainG1024,
            gainB1024 = request.gainB1024,
            yuvShift = request.yuvShift,
            ccm = request.ccm,
            yuvMat = request.yuvMat,
            peakingThreshold = request.peakingThreshold,
            peakingHiY = request.peakingHiY,
            peakingHiCb = request.peakingHiCb,
            peakingHiCr = request.peakingHiCr,
        )
        val wantStats = request.statsRequest != null && request.outStatsWords != null
        val depthGrid = backend.tryRenderGpuScene3dToIspYuv(
            spec = spec,
            geometry = geometry,
            exposure = request.exposure,
            profile = vulkanProfile,
            ibis = effectiveIbis,
            noiseConfig = cfg.noiseConfig,
            pupilShape = effectivePupilShape,
            maxBokehRadiusPx = maxBokehRadiusPx,
            borderMode = cfg.borderMode,
            frameIndex = frameIndex,
            filterStack = gpuFilterTranslation.fullConfigWithNd.withPupil(activeMask),
            lutR = request.lutR,
            lutG = request.lutG,
            lutB = request.lutB,
            outYOrPacked = outYOrPacked,
            outUvPlane = outUvPlane,
            ispParams = ispParams,
            customSpirvPasses = customPasses,
            statsRequest = request.statsRequest,
            outStatsWords = request.outStatsWords,
        ) ?: return false

        if (wantStats && backend.supportsFusedIspStats()) {
            request.statsProduced = true
            request.statsGpuMs = backend.diagnostics().lastFrameMs
        }
        lastGpuDepthGrid = depthGrid
        lastCenterDepthMeters = depthGrid.centerDepthMeters
        lastFrameHadDepth = true
        vulkanScene3dFrames++
        vulkanFusedIspFrames++
        framesFiltered++
        true
    }

    override fun tryFilterGpuScene3dToBayer(
        spec: GpuScene3dRenderSpec,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long,
    ): Int = synchronized(renderLock) {
        val cfg = activeConfig
        if (!cfg.enabled) return -1
        val backend = computeBackendProvider() ?: return -1
        if (!backend.supportsGpuScene3d()) return -1
        val filterStack = opticalFilterStackProvider()
        val gpuFilterTranslation = OpticalFilterStackTranslator.translate(filterStack, spec.width, spec.height)
        if (!gpuFilterTranslation.canOffloadSpatialToGpu) return -1
        val activeMask = filterStack?.activePupilMask()?.takeIf { it.active }

        val effectiveProfile = if (
            abs(spec.pose.focalLengthMm - cfg.profile.focalLengthMm) <= 0.25 &&
            cfg.profile.referenceFocalLengthMm != cfg.profile.focalLengthMm
        ) {
            cfg.profile.copy(referenceFocalLengthMm = cfg.profile.focalLengthMm)
        } else {
            cfg.profile
        }
        val hookPass = resolveHookSpirvPass(cfg.lensOpticalHook, geometry.width, geometry.height, effectiveProfile)
        if (cpuOpticsFallbackReason(activeMask, cfg.lensOpticalHook, hookPass, backend) != null) return -1
        val customPasses = buildList {
            addAll(gpuFilterTranslation.customSpirvPasses)
            if (hookPass != null) add(hookPass)
        }
        if (customPasses.isNotEmpty() && backend.validateCustomSpirvPasses(customPasses) != null) return -1
        val liveIbisPort = ibisShiftPortProvider()
        val effectiveIbis = if (liveIbisPort != null) {
            val pitch = geometry.pitchMicrometres.coerceAtLeast(0.5)
            val dxPx = liveIbisPort.shiftXUm / pitch
            val dyPx = liveIbisPort.shiftYUm / pitch
            val dRoll = Math.toRadians(liveIbisPort.rollDeg)
            if (dxPx != 0.0 || dyPx != 0.0 || dRoll != 0.0) {
                IbisShiftState(
                    shiftXPixels = (cfg.ibis.shiftXPixels + dxPx).coerceIn(-512.0, 512.0),
                    shiftYPixels = (cfg.ibis.shiftYPixels + dyPx).coerceIn(-512.0, 512.0),
                    rollRadians = (cfg.ibis.rollRadians + dRoll).coerceIn(-Math.PI, Math.PI),
                )
            } else cfg.ibis
        } else cfg.ibis
        val (vulkanProfile, effectivePupilShape) = resolveVulkanProfileAndPupil(effectiveProfile, activeMask, cfg.pupilShape)
        val (written, depthGrid) = backend.tryRenderGpuScene3dToBayer(
            spec = spec,
            geometry = geometry,
            exposure = exposure,
            profile = vulkanProfile,
            ibis = effectiveIbis,
            noiseConfig = cfg.noiseConfig,
            destination = destination,
            pupilShape = effectivePupilShape,
            maxBokehRadiusPx = maxBokehRadiusPx,
            borderMode = cfg.borderMode,
            frameIndex = frameIndex,
            filterStack = gpuFilterTranslation.fullConfigWithNd.withPupil(activeMask),
            customSpirvPasses = customPasses,
        ) ?: return -1

        lastGpuDepthGrid = depthGrid
        lastCenterDepthMeters = depthGrid.centerDepthMeters
        lastFrameHadDepth = true
        vulkanScene3dFrames++
        vulkanSingleSubmitFrames++
        framesFiltered++
        written
    }

    override fun filterLinearRgbToBayer(
        srcWidth: Int,
        srcHeight: Int,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        depthMeters: FloatArray?,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long,
    ): Int = synchronized(renderLock) {
        val cfg = activeConfig
        filterLinearRgbToBayerInternal(
            cfg = cfg,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            red = red,
            green = green,
            blue = blue,
            depthMeters = depthMeters,
            effectivePlanarDistM = cfg.planarSubjectDistanceM,
            is3DScene = false,
            rendered3DFocalLengthMm = null,
            sourceKey = 0L,
            exposure = exposure,
            geometry = geometry,
            destination = destination,
            frameIndex = frameIndex,
        )
    }

    private fun filterLinearRgbToBayerInternal(
        cfg: FilterConfigSnapshot,
        srcWidth: Int,
        srcHeight: Int,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        depthMeters: FloatArray?,
        effectivePlanarDistM: Double?,
        is3DScene: Boolean,
        rendered3DFocalLengthMm: Double?,
        sourceKey: Long,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long,
    ): Int {
        val cfgNoise = cfg.noiseConfig
        val cfgProfile = cfg.profile
        val cfgIbis = cfg.ibis
        val cfgSamplingMode = cfg.samplingMode
        val cfgBorderMode = cfg.borderMode
        val cfgPupilShape = cfg.pupilShape
        val cfgHook = cfg.lensOpticalHook
        val cfgVersion = cfg.configVersion
        val cfgOpticalVersion = cfg.opticalConfigVersion
        val canCache = !cfgNoise.enableNoise
        val lens = exposure.lens
        val apertureBits = lens.aperture.toRawBits()
        val transmissionBits = lens.transmission.toRawBits()
        val focalBits = lens.focalLengthMm.toRawBits()
        val focusBits = lens.focusDistanceM.toRawBits()
        val breathingBits = lens.focusBreathing.toRawBits()
        val pitchBits = geometry.pitchMicrometres.toRawBits()
        val planarDistPresent = effectivePlanarDistM != null
        val planarDistBits = effectivePlanarDistM?.toRawBits() ?: 0L
        // When a SceneSource3D already raytraced/projected at the lens's active focal length,
        // avoid digitally double-zooming in Stage-1 while keeping true focalLengthMm for CoC/vignetting.
        val effectiveProfile = if (
            rendered3DFocalLengthMm != null &&
            abs(rendered3DFocalLengthMm - cfgProfile.focalLengthMm) <= 0.25 &&
            cfgProfile.referenceFocalLengthMm != cfgProfile.focalLengthMm
        ) {
            cfgProfile.copy(referenceFocalLengthMm = cfgProfile.focalLengthMm)
        } else {
            cfgProfile
        }
        val refFocalBits = effectiveProfile.referenceFocalLengthMm.toRawBits()
        val liveIbisPort = ibisShiftPortProvider()
        val effectiveIbis = if (liveIbisPort != null) {
            // For 3D scenes, angular handheld tremor (with 90% IBIS cancellation when active vs
            // 100% when OFF) is already applied in 3D via CameraPose3D.fromOrientationAndIbis;
            // apply only static sensor-stage Hall offsets (16-shot Pixel Shift) here so shake is
            // never double-applied. For 2D planar scenes, sampleShiftPixels applies full IBIS shake.
            val (dxPx, dyPx, dRoll) = if (is3DScene) {
                val pitch = geometry.pitchMicrometres.coerceAtLeast(0.5)
                Triple(
                    liveIbisPort.shiftXUm / pitch,
                    liveIbisPort.shiftYUm / pitch,
                    Math.toRadians(liveIbisPort.rollDeg),
                )
            } else {
                liveIbisPort.sampleShiftPixels(geometry.pitchMicrometres, clockTickProvider())
            }
            if (dxPx != 0.0 || dyPx != 0.0 || dRoll != 0.0) {
                IbisShiftState(
                    shiftXPixels = (cfgIbis.shiftXPixels + dxPx).coerceIn(-512.0, 512.0),
                    shiftYPixels = (cfgIbis.shiftYPixels + dyPx).coerceIn(-512.0, 512.0),
                    rollRadians = (cfgIbis.rollRadians + dRoll).coerceIn(-Math.PI, Math.PI),
                )
            } else {
                cfgIbis
            }
        } else {
            cfgIbis
        }
        val ibisXBits = effectiveIbis.shiftXPixels.toRawBits()
        val ibisYBits = effectiveIbis.shiftYPixels.toRawBits()
        val ibisRollBits = effectiveIbis.rollRadians.toRawBits()
        val filterStack = opticalFilterStackProvider()
        val filterTrans = filterStack?.totalTransmittance() ?: 1.0
        val pupilMask = filterStack?.activePupilMask()
        val activeMask = pupilMask?.takeIf { it.active }
        val activeHook = cfgHook
        val filterTransBits = filterTrans.toRawBits()
        val pupilMaskKey = pupilMask?.cacheKey ?: 0L
        val backend = computeBackendProvider()
        val activeBackendMode = backend?.activeBackend ?: ActiveOpticsBackend.CPU
        val hookSpirvPass = resolveHookSpirvPass(activeHook, geometry.width, geometry.height, effectiveProfile)
        val lensHookKey = (activeHook?.cacheKey() ?: 0L) xor (hookSpirvPass?.effectiveCacheKey() ?: 0L)
        val cpuFallbackReason = cpuOpticsFallbackReason(activeMask, activeHook, hookSpirvPass, backend)
        val stage1BackendMode = if (activeBackendMode == ActiveOpticsBackend.VULKAN && cpuFallbackReason != null) {
            if (lastFallbackLogReason != cpuFallbackReason) {
                LOGGER.warning("[FALLBACK][optics-vk-cpu] Routing optical Stage-1 to CPU for $cpuFallbackReason")
                lastFallbackLogReason = cpuFallbackReason
            }
            ActiveOpticsBackend.CPU
        } else {
            if (cpuFallbackReason == null) lastFallbackLogReason = null
            activeBackendMode
        }
        val rawFilterTranslation = if (backend != null && stage1BackendMode == ActiveOpticsBackend.VULKAN) {
            OpticalFilterStackTranslator.translate(filterStack, srcWidth, srcHeight)
        } else {
            OpticalFilterStackTranslator.Translation.EMPTY
        }
        val filterCustomValid = rawFilterTranslation.customSpirvPasses.isEmpty() ||
            (backend != null && backend.validateCustomSpirvPasses(rawFilterTranslation.customSpirvPasses) == null)
        val gpuFilterTranslation = if (filterCustomValid) {
            rawFilterTranslation
        } else {
            OpticalFilterStackTranslator.Translation.EMPTY.copy(canOffloadSpatialToGpu = false)
        }
        val activeCustomSpirvPasses = if (stage1BackendMode == ActiveOpticsBackend.VULKAN) {
            buildList {
                if (gpuFilterTranslation.canOffloadSpatialToGpu) addAll(gpuFilterTranslation.customSpirvPasses)
                if (hookSpirvPass != null) add(hookSpirvPass)
            }
        } else {
            emptyList()
        }
        val spatialFilterKey = gpuFilterTranslation.spatialCacheKey
        val srcFp = if (sourceKey != 0L) {
            sourceKey
        } else {
            sourceFingerprintComputations++
            computeSourceFingerprint(red, green, blue, depthMeters)
        }
        val cached = cachedBayer
        if (
            canCache &&
            cached != null &&
            cachedByteCount > 0 &&
            destination.remaining() >= cachedByteCount &&
            cachedBackend == activeBackendMode &&
            cachedConfigVersion == cfgVersion &&
            cachedSrcWidth == srcWidth &&
            cachedSrcHeight == srcHeight &&
            cachedRed === red &&
            cachedGreen === green &&
            cachedBlue === blue &&
            cachedDepth === depthMeters &&
            cachedSrcFingerprint == srcFp &&
            cachedDurationNanos == exposure.durationNanos &&
            cachedIso == exposure.iso &&
            cachedLensApertureBits == apertureBits &&
            cachedLensTransmissionBits == transmissionBits &&
            cachedLensFocalBits == focalBits &&
            cachedRefFocalBits == refFocalBits &&
            cachedLensFocusBits == focusBits &&
            cachedLensBreathingBits == breathingBits &&
            cachedGeomWidth == geometry.width &&
            cachedGeomHeight == geometry.height &&
            cachedGeomBitDepth == geometry.bitDepth &&
            cachedGeomBayer == geometry.bayer &&
            cachedGeomPitchBits == pitchBits &&
            cachedIbisShiftXBits == ibisXBits &&
            cachedIbisShiftYBits == ibisYBits &&
            cachedIbisRollBits == ibisRollBits &&
            cachedPlanarDistPresent == planarDistPresent &&
            cachedPlanarDistBits == planarDistBits &&
            cachedFilterTransBits == filterTransBits &&
            cachedPupilMaskKey == pupilMaskKey &&
            cachedSpatialFilterKey == spatialFilterKey &&
            cachedLensHookKey == lensHookKey
        ) {
            lastCenterDepthMeters = cachedCenterDepthMeters
            lastFrameHadDepth = cachedHadDepth
            destination.put(cached, 0, cachedByteCount)
            cacheHits++
            framesFiltered++
            return cachedByteCount
        }

        // Stage-1 Optical Linear-RGB cache check (invariant to exposure.durationNanos, exposure.iso,
        // lens.transmission, scalar ND, noiseConfig, and Bayer packing).
        val optHit = optCachedR != null &&
            optCachedG != null &&
            optCachedB != null &&
            optCachedBackend == stage1BackendMode &&
            optCachedConfigVersion == cfgOpticalVersion &&
            optCachedSrcWidth == srcWidth &&
            optCachedSrcHeight == srcHeight &&
            optCachedRed === red &&
            optCachedGreen === green &&
            optCachedBlue === blue &&
            optCachedDepth === depthMeters &&
            optCachedSrcFingerprint == srcFp &&
            optCachedLensApertureBits == apertureBits &&
            optCachedLensFocalBits == focalBits &&
            optCachedRefFocalBits == refFocalBits &&
            optCachedLensFocusBits == focusBits &&
            optCachedLensBreathingBits == breathingBits &&
            optCachedGeomWidth == geometry.width &&
            optCachedGeomHeight == geometry.height &&
            optCachedGeomPitchBits == pitchBits &&
            optCachedIbisShiftXBits == ibisXBits &&
            optCachedIbisShiftYBits == ibisYBits &&
            optCachedIbisRollBits == ibisRollBits &&
            optCachedPlanarDistPresent == planarDistPresent &&
            optCachedPlanarDistBits == planarDistBits &&
            optCachedPupilMaskKey == pupilMaskKey &&
            optCachedSpatialFilterKey == spatialFilterKey &&
            optCachedLensHookKey == lensHookKey

        // Single-submit GPU fast path (with VRAM-resident scene cache): when Vulkan is active,
        // Stage-1 misses, and no custom CPU-only spatial filter plugin is mounted, execute
        // [Pass 0 Filter] -> Pass 1 Warp -> Pass 2 Pupil Bokeh -> Pass 3 CMOS Bayer in ONE single
        // GPU command-buffer submission (ZERO intermediate 9.4 MB RGBA host readback & ZERO FloatArray
        // allocations!). If the exact same (srcFp, opticalKey) returns with only AE/ND changed,
        // promote into Stage-1 cache so repeated AE updates skip Pass 0..2 completely.
        val opticalKey = (cfgOpticalVersion * 0x9E3779B97F4A7C15UL.toLong()) xor
            pupilMaskKey xor
            spatialFilterKey xor
            lensHookKey xor
            apertureBits xor
            focalBits xor
            refFocalBits xor
            focusBits xor
            ibisXBits xor
            ibisYBits xor
            ibisRollBits
        val sceneKeyForGpu = if (srcFp != 0L) srcFp else 1L
        val isFocusSweepOnSameScene = lastRenderedSrcFp == srcFp &&
            lastRenderedFocusBits != Long.MIN_VALUE &&
            lastRenderedFocusBits != focusBits
        val useSingleSubmit = !optHit &&
            backend != null &&
            stage1BackendMode == ActiveOpticsBackend.VULKAN &&
            gpuFilterTranslation.canOffloadSpatialToGpu &&
            (!gpuFilterTranslation.fullConfigWithNd.isBypassed || activeCustomSpirvPasses.isNotEmpty() || isFocusSweepOnSameScene) &&
            !(lastSingleSubmitSrcFp == srcFp && lastSingleSubmitOpticalKey == opticalKey)
        if (useSingleSubmit) {
            if (depthMeters != null && depthMeters.size == srcWidth * srcHeight) {
                lastCenterDepthMeters = sampleCenterAfDepthMeters(depthMeters, srcWidth, srcHeight)
                lastFrameHadDepth = true
            } else {
                lastCenterDepthMeters = null
                lastFrameHadDepth = false
            }
            val image = LinearRgbImage(
                width = srcWidth,
                height = srcHeight,
                red = red,
                green = green,
                blue = blue,
            )
            val (vulkanProfile, effectivePupilShape) = resolveVulkanProfileAndPupil(effectiveProfile, activeMask, cfgPupilShape)
            val bytesPerSample = if (geometry.bitDepth == 8) 1 else 2
            val requiredBytes = Math.multiplyExact(Math.multiplyExact(geometry.width, geometry.height), bytesPerSample)
            require(destination.remaining() >= requiredBytes) {
                "Destination buffer too small for ${geometry.width}x${geometry.height}@${geometry.bitDepth}bpp: " +
                    "need $requiredBytes, remaining ${destination.remaining()}"
            }
            val written: Int
            val usedBackend: ActiveOpticsBackend
            if (canCache) {
                val target = if (cached != null && cached.size >= requiredBytes) cached else ByteArray(requiredBytes)
                written = backend.renderToBayer(
                    source = image,
                    geometry = geometry,
                    exposure = exposure,
                    profile = vulkanProfile,
                    ibis = effectiveIbis,
                    noiseConfig = cfgNoise,
                    destination = ByteBuffer.wrap(target, 0, requiredBytes),
                    depthMeters = depthMeters,
                    planarDepthM = effectivePlanarDistM ?: vulkanProfile.focusDistanceM,
                    pupilShape = effectivePupilShape,
                    maxBokehRadiusPx = maxBokehRadiusPx,
                    samplingMode = cfgSamplingMode,
                    borderMode = cfgBorderMode,
                    frameIndex = frameIndex,
                    filterStack = gpuFilterTranslation.fullConfigWithNd.withPupil(activeMask),
                    sceneCacheKey = sceneKeyForGpu,
                    customSpirvPasses = activeCustomSpirvPasses,
                )
                usedBackend = backend.activeBackend
                cachedBayer = target
                cachedByteCount = written
                cachedBackend = usedBackend
                cachedConfigVersion = cfgVersion
                cachedSrcWidth = srcWidth
                cachedSrcHeight = srcHeight
                cachedRed = red
                cachedGreen = green
                cachedBlue = blue
                cachedDepth = depthMeters
                cachedSrcFingerprint = srcFp
                cachedCenterDepthMeters = lastCenterDepthMeters
                cachedHadDepth = lastFrameHadDepth
                cachedDurationNanos = exposure.durationNanos
                cachedIso = exposure.iso
                cachedLensApertureBits = apertureBits
                cachedLensTransmissionBits = transmissionBits
                cachedLensFocalBits = focalBits
                cachedRefFocalBits = refFocalBits
                cachedLensFocusBits = focusBits
                cachedLensBreathingBits = breathingBits
                cachedGeomWidth = geometry.width
                cachedGeomHeight = geometry.height
                cachedGeomBitDepth = geometry.bitDepth
                cachedGeomBayer = geometry.bayer
                cachedGeomPitchBits = pitchBits
                cachedIbisShiftXBits = ibisXBits
                cachedIbisShiftYBits = ibisYBits
                cachedIbisRollBits = ibisRollBits
                cachedPlanarDistPresent = planarDistPresent
                cachedPlanarDistBits = planarDistBits
                cachedFilterTransBits = filterTransBits
                cachedPupilMaskKey = pupilMaskKey
                cachedSpatialFilterKey = spatialFilterKey
                cachedLensHookKey = lensHookKey
                destination.put(target, 0, written)
            } else {
                written = backend.renderToBayer(
                    source = image,
                    geometry = geometry,
                    exposure = exposure,
                    profile = vulkanProfile,
                    ibis = effectiveIbis,
                    noiseConfig = cfgNoise,
                    destination = destination,
                    depthMeters = depthMeters,
                    planarDepthM = effectivePlanarDistM ?: vulkanProfile.focusDistanceM,
                    pupilShape = effectivePupilShape,
                    maxBokehRadiusPx = maxBokehRadiusPx,
                    samplingMode = cfgSamplingMode,
                    borderMode = cfgBorderMode,
                    frameIndex = frameIndex,
                    filterStack = gpuFilterTranslation.fullConfigWithNd.withPupil(activeMask),
                    sceneCacheKey = sceneKeyForGpu,
                    customSpirvPasses = activeCustomSpirvPasses,
                )
                usedBackend = backend.activeBackend
            }
            if (usedBackend == ActiveOpticsBackend.VULKAN) {
                vulkanSingleSubmitFrames++
            }
            lastSingleSubmitSrcFp = srcFp
            lastSingleSubmitOpticalKey = opticalKey
            lastRenderedSrcFp = srcFp
            lastRenderedFocusBits = focusBits
            opticalCacheMisses++
            cacheMisses++
            framesFiltered++
            return written
        }

        val finalR: FloatArray
        val finalG: FloatArray
        val finalB: FloatArray
        var stage1BackendUsed = stage1BackendMode
        if (optHit) {
            lastCenterDepthMeters = optCachedCenterDepthMeters
            lastFrameHadDepth = optCachedHadDepth
            finalR = optCachedR!!
            finalG = optCachedG!!
            finalB = optCachedB!!
            opticalCacheHits++
        } else {
            if (depthMeters != null && depthMeters.size == srcWidth * srcHeight) {
                lastCenterDepthMeters = sampleCenterAfDepthMeters(depthMeters, srcWidth, srcHeight)
                lastFrameHadDepth = true
            } else {
                lastCenterDepthMeters = null
                lastFrameHadDepth = false
            }
            val image = LinearRgbImage(
                width = srcWidth,
                height = srcHeight,
                red = red,
                green = green,
                blue = blue,
            )
            if (backend != null && stage1BackendMode == ActiveOpticsBackend.VULKAN) {
                val (vulkanProfile, effectivePupilShape) = resolveVulkanProfileAndPupil(effectiveProfile, activeMask, cfgPupilShape)
                val gpuImg = backend.renderLinearRgb(
                    source = image,
                    dstWidth = geometry.width,
                    dstHeight = geometry.height,
                    profile = vulkanProfile,
                    ibis = effectiveIbis,
                    depthMeters = depthMeters,
                    planarDepthM = effectivePlanarDistM ?: vulkanProfile.focusDistanceM,
                    pupilShape = effectivePupilShape,
                    maxBokehRadiusPx = maxBokehRadiusPx,
                    samplingMode = cfgSamplingMode,
                    borderMode = cfgBorderMode,
                    filterStack = gpuFilterTranslation.spatialOnlyConfig.withPupil(activeMask),
                    sceneCacheKey = sceneKeyForGpu,
                    customSpirvPasses = activeCustomSpirvPasses,
                    frameIndex = frameIndex,
                )
                stage1BackendUsed = backend.activeBackend
                if (stage1BackendUsed == ActiveOpticsBackend.VULKAN) {
                    vulkanStage1Frames++
                }
                finalR = gpuImg.red
                finalG = gpuImg.green
                finalB = gpuImg.blue
            } else {
                val (rPlane, gPlane, bPlane) = pipeline.renderOpticalLinearRgb(
                    source = image,
                    dstWidth = geometry.width,
                    dstHeight = geometry.height,
                    profile = effectiveProfile,
                    ibis = effectiveIbis,
                    depthMeters = depthMeters,
                    planarSubjectDistanceM = effectivePlanarDistM,
                    samplingMode = cfgSamplingMode,
                    borderMode = cfgBorderMode,
                    workspace = workspace,
                    pupilMask = activeMask ?: cfgPupilShape.cpuMask,
                    lensHook = activeHook,
                )
                stage1BackendUsed = ActiveOpticsBackend.CPU
                finalR = rPlane
                finalG = gPlane
                finalB = bPlane
            }
            optCachedR = finalR
            optCachedG = finalG
            optCachedB = finalB
            optCachedBackend = stage1BackendUsed
            optCachedConfigVersion = cfgOpticalVersion
            optCachedSrcWidth = srcWidth
            optCachedSrcHeight = srcHeight
            optCachedRed = red
            optCachedGreen = green
            optCachedBlue = blue
            optCachedDepth = depthMeters
            optCachedSrcFingerprint = srcFp
            optCachedCenterDepthMeters = lastCenterDepthMeters
            optCachedHadDepth = lastFrameHadDepth
            optCachedLensApertureBits = apertureBits
            optCachedLensFocalBits = focalBits
            optCachedRefFocalBits = refFocalBits
            optCachedLensFocusBits = focusBits
            optCachedLensBreathingBits = breathingBits
            optCachedGeomWidth = geometry.width
            optCachedGeomHeight = geometry.height
            optCachedGeomPitchBits = pitchBits
            optCachedIbisShiftXBits = ibisXBits
            optCachedIbisShiftYBits = ibisYBits
            optCachedIbisRollBits = ibisRollBits
            optCachedPlanarDistPresent = planarDistPresent
            optCachedPlanarDistBits = planarDistBits
            optCachedPupilMaskKey = pupilMaskKey
            optCachedSpatialFilterKey = spatialFilterKey
            optCachedLensHookKey = lensHookKey
            lastRenderedSrcFp = srcFp
            lastRenderedFocusBits = focusBits
            opticalCacheMisses++
        }

        val effectiveTransmission = (cfgProfile.transmission * filterTrans.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        fun runStage2Cmos(dstBuf: ByteBuffer): Pair<Int, ActiveOpticsBackend> {
            val useVkStage2 = backend != null && backend.activeBackend == ActiveOpticsBackend.VULKAN
            return if (useVkStage2) {
                val stage2Profile = if (effectiveTransmission != cfgProfile.transmission) {
                    cfgProfile.copy(transmission = effectiveTransmission)
                } else {
                    cfgProfile
                }
                val n = backend.renderCmosBayer(
                    red = finalR,
                    green = finalG,
                    blue = finalB,
                    geometry = geometry,
                    exposure = exposure,
                    profile = stage2Profile,
                    noiseConfig = cfgNoise,
                    destination = dstBuf,
                    frameIndex = frameIndex,
                    parallelThreads = parallelThreads,
                )
                val used = backend.activeBackend
                if (used == ActiveOpticsBackend.VULKAN) {
                    vulkanStage2Frames++
                }
                n to used
            } else {
                val n = CmosNoiseModel.mosaicAndReadout(
                    red = finalR,
                    green = finalG,
                    blue = finalB,
                    geometry = geometry,
                    exposure = exposure,
                    config = cfgNoise,
                    destination = dstBuf,
                    fNumber = cfgProfile.fNumber,
                    transmission = effectiveTransmission,
                    frameIndex = frameIndex,
                    parallelThreads = parallelThreads,
                )
                n to ActiveOpticsBackend.CPU
            }
        }

        if (canCache) {
            val bytesPerSample = if (geometry.bitDepth == 8) 1 else 2
            val requiredBytes = Math.multiplyExact(Math.multiplyExact(geometry.width, geometry.height), bytesPerSample)
            require(destination.remaining() >= requiredBytes) {
                "Destination buffer too small for ${geometry.width}x${geometry.height}@${geometry.bitDepth}bpp: " +
                    "need $requiredBytes, remaining ${destination.remaining()}"
            }
            val target = if (cached != null && cached.size >= requiredBytes) cached else ByteArray(requiredBytes)
            val (written, stage2BackendUsed) = runStage2Cmos(ByteBuffer.wrap(target, 0, requiredBytes))
            cachedBayer = target
            cachedByteCount = written
            cachedBackend = stage2BackendUsed
            cachedConfigVersion = cfgVersion
            cachedSrcWidth = srcWidth
            cachedSrcHeight = srcHeight
            cachedRed = red
            cachedGreen = green
            cachedBlue = blue
            cachedDepth = depthMeters
            cachedSrcFingerprint = srcFp
            cachedCenterDepthMeters = lastCenterDepthMeters
            cachedHadDepth = lastFrameHadDepth
            cachedDurationNanos = exposure.durationNanos
            cachedIso = exposure.iso
            cachedLensApertureBits = apertureBits
            cachedLensTransmissionBits = transmissionBits
            cachedLensFocalBits = focalBits
            cachedRefFocalBits = refFocalBits
            cachedLensFocusBits = focusBits
            cachedLensBreathingBits = breathingBits
            cachedGeomWidth = geometry.width
            cachedGeomHeight = geometry.height
            cachedGeomBitDepth = geometry.bitDepth
            cachedGeomBayer = geometry.bayer
            cachedGeomPitchBits = pitchBits
            cachedIbisShiftXBits = ibisXBits
            cachedIbisShiftYBits = ibisYBits
            cachedIbisRollBits = ibisRollBits
            cachedPlanarDistPresent = planarDistPresent
            cachedPlanarDistBits = planarDistBits
            cachedFilterTransBits = filterTransBits
            cachedPupilMaskKey = pupilMaskKey
            cachedSpatialFilterKey = spatialFilterKey
            cachedLensHookKey = lensHookKey

            destination.put(target, 0, written)
            cacheMisses++
            framesFiltered++
            return written
        }

        val (written, _) = runStage2Cmos(destination)
        cacheMisses++
        framesFiltered++
        return written
    }

    private fun resolveVulkanProfileAndPupil(
        baseProfile: OpticalLensProfile,
        activeMask: com.github.nyabanban.fuji.emulator.api.OpticalPupilMask?,
        configuredPupilShape: BokehPupilShape,
    ): Pair<OpticalLensProfile, BokehPupilShape> = when (activeMask?.shape) {
        BokehMaskShape.STAR_5 -> baseProfile to BokehPupilShape.STAR_5
        BokehMaskShape.HEART -> baseProfile to BokehPupilShape.HEART
        BokehMaskShape.RING -> baseProfile to BokehPupilShape.RING
        BokehMaskShape.HEXAGON -> baseProfile.copy(apertureBladeCount = 6, apertureBladeRoundness = 0.0) to BokehPupilShape.POLYGON
        else -> if (configuredPupilShape == BokehPupilShape.CIRCLE && baseProfile.hasShapedBokeh()) {
            baseProfile to BokehPupilShape.POLYGON
        } else {
            baseProfile to configuredPupilShape
        }
    }

    private fun computeSourceFingerprint(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        depthMeters: FloatArray?,
    ): Long {
        val len = red.size
        var h = len.toLong() * 0x9E3779B97F4A7C15UL.toLong()
        if (len > 0) {
            val step = (len ushr 4).coerceAtLeast(1)
            var i = 0
            while (i < len) {
                val bits = (red[i].toRawBits().toLong() and 0xFFFF_FFFFL) xor
                    ((green[i].toRawBits().toLong() and 0xFFFF_FFFFL) shl 21) xor
                    ((blue[i].toRawBits().toLong() and 0xFFFF_FFFFL) shl 42)
                h = (h xor bits) * 0xBF58476D1CE4E5B9UL.toLong()
                i += step
            }
            val last = len - 1
            h = (h xor red[last].toRawBits().toLong() xor (green[last].toRawBits().toLong() shl 16) xor (blue[last].toRawBits().toLong() shl 32)) *
                0x94D049BB133111EBUL.toLong()
        }
        if (depthMeters != null && depthMeters.isNotEmpty()) {
            val dLen = depthMeters.size
            val dStep = (dLen ushr 3).coerceAtLeast(1)
            var i = 0
            while (i < dLen) {
                h = (h xor depthMeters[i].toRawBits().toLong()) * 0x9E3779B97F4A7C15UL.toLong()
                i += dStep
            }
        }
        return h xor (h ushr 31)
    }

    private fun sampleCenterAfDepthMeters(depth: FloatArray, width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0) return null
        val samples = DoubleArray(25)
        var count = 0
        val x0 = (width * 0.40).toInt().coerceIn(0, width - 1)
        val x1 = (width * 0.60).toInt().coerceIn(x0, width - 1)
        val y0 = (height * 0.40).toInt().coerceIn(0, height - 1)
        val y1 = (height * 0.60).toInt().coerceIn(y0, height - 1)
        for (gy in 0 until 5) {
            val y = y0 + ((y1 - y0) * gy) / 4
            val row = y * width
            for (gx in 0 until 5) {
                val x = x0 + ((x1 - x0) * gx) / 4
                val d = depth[row + x].toDouble()
                if (d.isFinite() && d > 0.0) {
                    samples[count++] = d
                }
            }
        }
        if (count == 0) return null
        samples.sort(0, count)
        return samples[count / 2]
    }

    private companion object {
        val SOURCE_KEY = AtomicLong()
        val LOGGER: Logger = Logger.getLogger(LensOpticalSceneFilter::class.java.name)
    }
}
