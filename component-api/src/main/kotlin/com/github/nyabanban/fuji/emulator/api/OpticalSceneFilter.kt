package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer

/**
 * Narrow pluggable optical filter contract (M-Optics P2) bridging an optical lens
 * simulation component (e.g., `components/lens`) onto any CMOS [SceneSource].
 *
 * Components remain decoupled: `components/lens` implements this interface alongside
 * [LensPort], and `components/cmos` looks it up optionally through `ComponentContext.ports`
 * without introducing any direct JAR dependency between hardware components.
 *
 * When [enabled] is `false` (the default), callers bypass optical filtering completely
 * so static-QR and deterministic `discovery` runs keep zero overhead and bit-exact output.
 */
interface OpticalSceneFilter {
    /** Whether optical lens/IBIS/noise simulation is currently enabled. */
    val enabled: Boolean

    /**
     * Filters a planar linear-radiance RGB frame ([red], [green], [blue], each of length
     * `srcWidth * srcHeight`, with optional [depthMeters] of the same length) through the
     * active lens/IBIS/noise pipeline and writes packed little-endian Bayer bytes into
     * [destination], returning the number of bytes written.
     */
    fun filterLinearRgbToBayer(
        srcWidth: Int,
        srcHeight: Int,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        depthMeters: FloatArray?,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long = 0L,
    ): Int

    /**
     * Convenience overload accepting a typed [LinearRadianceFrame] from either [SceneSource2D]
     * or [SceneSource3D]. Defaults to delegating directly to [filterLinearRgbToBayer] so
     * existing implementations remain 100% source- and binary-compatible.
     */
    fun filterRadianceFrame(
        frame: LinearRadianceFrame,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long = 0L,
    ): Int = filterLinearRgbToBayer(
        srcWidth = frame.width,
        srcHeight = frame.height,
        red = frame.red,
        green = frame.green,
        blue = frame.blue,
        depthMeters = frame.depthMeters,
        exposure = exposure,
        geometry = geometry,
        destination = destination,
        frameIndex = frameIndex,
    )

    /**
     * Returns `true` when this [OpticalSceneFilter] executes all active spatial elements of
     * [filterStack] internally (for example via Vulkan Compute Pass 0 `optical_filter_stack`)
     * so CMOS callers pass the unmodified linear radiance frame without running CPU spatial
     * filtering twice.
     */
    fun handlesSpatialFilterStack(filterStack: OpticalFilterStackPort?): Boolean = false

    /**
     * Monotonic version token bumped whenever internal optical settings (lens profile, IBIS,
     * pupil shape, sampling/border mode, noise config) change. Returns `-1L` when every frame
     * may differ (e.g. stochastic CMOS noise enabled) or when versioning is unsupported.
     */
    fun opticalStateVersion(): Long = -1L

    /**
     * Returns `true` when [tryFilterRadianceToIspYuv] / [tryFilterGpuScene3dToIspYuv] can compute
     * GPU-resident ISP statistics ([LiveViewIspFusionRequest.statsRequest] -> `outStatsWords`) in
     * the same Vulkan submission without host Bayer readback.
     */
    fun supportsFusedIspStats(): Boolean = false

    /**
     * Returns the sensor black-level pedestal in ADU for the given [bitDepth] (e.g. 256 for 16-bit
     * CMOS, 0 when noiseless or unspecified), used for zero-lag linear Bayer exposure scaling.
     */
    fun sensorBlackLevelAdu(bitDepth: Int = 16): Int = 0

    /**
     * Returns `true` when a pure exposure-only change (`durationNanos`/`iso`/`transmittance` with
     * unchanged optics/scene) can reuse a hot Stage-1 optical cache in <1.5 ms for exact readout.
     */
    fun hasHotStage1CacheForExposureOnlyChange(): Boolean = false

    /**
     * Optional GPU-resident fused Optics -> CMOS Bayer -> LiveView ISP Demosaic fast path.
     * Returns `true` when [outYOrPacked] (and optional [outUvPlane] for NV16/NV12) was rendered
     * directly on GPU without reading back intermediate Bayer bytes to the host; returns `false`
     * when unavailable or unsupported.
     */
    fun tryFilterRadianceToIspYuv(
        frame: LinearRadianceFrame,
        geometry: SensorGeometry,
        request: LiveViewIspFusionRequest,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        frameIndex: Long = 0L,
    ): Boolean = false

    /**
     * Returns `true` when this [OpticalSceneFilter] supports GPU-resident 3D scene rasterization
     * directly into `DEVICE_LOCAL` VRAM (avoiding CPU 3D rasterization and PCIe scene upload).
     */
    fun supportsGpuScene3d(): Boolean = false

    /**
     * Renders a [GpuScene3dRenderSpec] directly on Vulkan GPU into `DEVICE_LOCAL` VRAM and
     * immediately executes fused Optics -> CMOS -> LiveView ISP YUV with zero PCIe scene upload.
     * Returns `true` on GPU success, or `false` to transparently fall back to CPU 3D rendering.
     */
    fun tryFilterGpuScene3dToIspYuv(
        spec: GpuScene3dRenderSpec,
        geometry: SensorGeometry,
        request: LiveViewIspFusionRequest,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
        frameIndex: Long = 0L,
    ): Boolean = false

    /**
     * Renders a [GpuScene3dRenderSpec] directly on Vulkan GPU into `DEVICE_LOCAL` VRAM and
     * immediately executes Optics -> CMOS Bayer readout into [destination] with zero PCIe upload.
     * Returns bytes written (`> 0`) on GPU success, or `-1` to transparently fall back to CPU.
     */
    fun tryFilterGpuScene3dToBayer(
        spec: GpuScene3dRenderSpec,
        exposure: Exposure,
        geometry: SensorGeometry,
        destination: ByteBuffer,
        frameIndex: Long = 0L,
    ): Int = -1
}
