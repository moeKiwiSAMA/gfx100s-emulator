package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful GPU-resident 3D scene cache & dispatcher backed by [VulkanNativeScene3dBridge].
 *
 * Uploads static scene assets (Milk Frog mesh & studio stage, QR matrix, Mona Lisa
 * sculpted portrait, layered studio base) ONCE into `DEVICE_LOCAL` VRAM and renders dynamic
 * per-frame poses straight into `FujiVkContext.bufSceneDevice` so downstream optical + CMOS +
 * ISP passes consume them with ZERO PCIe upload.
 */
class VulkanScene3dRenderer(
    private val bridge: VulkanNativeScene3dBridge,
) : AutoCloseable {

    data class DepthGridSnapshot(
        val tilesX: Int,
        val tilesY: Int,
        val depthMeters: FloatArray,
        val centerDepthMeters: Double?,
    )

    private var uploadedMeshKey: Long = 0L
    private var uploadedStageKey: Long = 0L
    private var uploadedQrKey: Long = 0L
    private var uploadedMonaKey: Long = 0L
    private var uploadedLayeredKey: Long = 0L
    private var lastRenderedSceneKey: Long = 0L
    private var lastDepthGrid: DepthGridSnapshot? = null

    private val framesRenderedCounter = AtomicLong(0L)
    private val cacheHitsCounter = AtomicLong(0L)
    private val staticUploadsCounter = AtomicLong(0L)

    @Volatile
    var lastScene3dMs: Double = 0.0
        private set

    val framesRendered: Long get() = framesRenderedCounter.get()
    val scene3dCacheHits: Long get() = cacheHitsCounter.get()
    val staticAssetUploads: Long get() = staticUploadsCounter.get()

    internal fun invalidateRenderedFrame() {
        lastRenderedSceneKey = 0L
        lastDepthGrid = null
    }

    /**
     * Renders [spec] straight into `ctx`'s `DEVICE_LOCAL` `bufSceneDevice` tagged with
     * `spec.sceneKey`. Returns the downsampled 16x16-tile metric [DepthGridSnapshot] on
     * success, or `null` if Vulkan dispatch fails (caller should fall back to CPU).
     */
    fun renderIntoDeviceSceneCache(
        ctx: MemorySegment,
        spec: GpuScene3dRenderSpec,
    ): DepthGridSnapshot? {
        if (spec.sceneKey == 0L || spec.width <= 0 || spec.height <= 0) return null
        val prevGrid = lastDepthGrid
        if (lastRenderedSceneKey == spec.sceneKey && prevGrid != null) {
            cacheHitsCounter.incrementAndGet()
            return prevGrid
        }

        val tilesX = (spec.width + VulkanNativeScene3dBridge.TILE_SIZE - 1) / VulkanNativeScene3dBridge.TILE_SIZE
        val tilesY = (spec.height + VulkanNativeScene3dBridge.TILE_SIZE - 1) / VulkanNativeScene3dBridge.TILE_SIZE
        val numTiles = Math.multiplyExact(tilesX, tilesY)
        val depthGrid = FloatArray(numTiles)
        invalidateRenderedFrame()

        val (rc, gpuMs) = when (spec) {
            is GpuScene3dRenderSpec.MilkFrog -> {
                if (uploadedMeshKey != spec.meshKey) {
                    val (urc, _) = bridge.uploadMesh(
                        ctx,
                        spec.meshKey,
                        spec.vertexDataProvider(),
                        spec.vertexCount,
                        spec.indicesProvider(),
                        spec.triCount,
                    )
                    if (urc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null
                    uploadedMeshKey = spec.meshKey
                    staticUploadsCounter.incrementAndGet()
                }
                if (uploadedStageKey != spec.stageKey) {
                    val (urc, _) = bridge.uploadStage(
                        ctx,
                        spec.stageKey,
                        spec.stageRgbaDepthProvider(),
                        spec.width,
                        spec.height,
                    )
                    if (urc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null
                    uploadedStageKey = spec.stageKey
                    staticUploadsCounter.incrementAndGet()
                }
                bridge.renderMilkFrog(ctx, spec, depthGrid)
            }
            is GpuScene3dRenderSpec.QrBall -> {
                if (uploadedQrKey != spec.qrKey) {
                    val (urc, _) = bridge.uploadQrMatrix(ctx, spec.qrKey, spec.qrRowBits32)
                    if (urc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null
                    uploadedQrKey = spec.qrKey
                    staticUploadsCounter.incrementAndGet()
                }
                bridge.renderQrBall(ctx, spec, depthGrid)
            }
            is GpuScene3dRenderSpec.MonaLisa -> {
                if (uploadedMonaKey != spec.portraitKey) {
                    val (urc, _) = bridge.uploadMonaLisa(
                        ctx,
                        spec.portraitKey,
                        spec.fgRgbaProvider(),
                        spec.bgRgbDepthOffsetProvider(),
                        spec.srcWidth,
                        spec.srcHeight,
                    )
                    if (urc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null
                    uploadedMonaKey = spec.portraitKey
                    staticUploadsCounter.incrementAndGet()
                }
                bridge.renderMonaLisa(ctx, spec, depthGrid)
            }
            is GpuScene3dRenderSpec.LayeredStudio -> {
                if (uploadedLayeredKey != spec.baseKey) {
                    val (urc, _) = bridge.uploadLayeredBase(
                        ctx,
                        spec.baseKey,
                        spec.baseRgbaDepthProvider(),
                        spec.width,
                        spec.height,
                    )
                    if (urc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null
                    uploadedLayeredKey = spec.baseKey
                    staticUploadsCounter.incrementAndGet()
                }
                bridge.renderLayered(ctx, spec, depthGrid)
            }
        }
        if (rc != VulkanNativeOpticsBridge.FUJI_VK_OK) return null

        val centerDepth = sampleCenterGridDepth(depthGrid, tilesX, tilesY)
        val snap = DepthGridSnapshot(tilesX, tilesY, depthGrid, centerDepth)
        lastRenderedSceneKey = spec.sceneKey
        lastDepthGrid = snap
        lastScene3dMs = gpuMs
        framesRenderedCounter.incrementAndGet()
        return snap
    }

    fun readbackRenderedScene(ctx: MemorySegment, width: Int, height: Int): FloatArray? {
        val pixels = Math.multiplyExact(width, height)
        val out = FloatArray(Math.multiplyExact(pixels, 4))
        val (rc, _) = bridge.readbackScene(ctx, out, pixels)
        return if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) out else null
    }

    override fun close() {
        bridge.close()
    }

    companion object {
        private fun sampleCenterGridDepth(grid: FloatArray, tw: Int, th: Int): Double? {
            if (tw <= 0 || th <= 0 || grid.size < tw * th) return null
            val cx = tw / 2
            val cy = th / 2
            val rx = (tw / 12).coerceAtLeast(1)
            val ry = (th / 12).coerceAtLeast(1)
            val x0 = (cx - rx).coerceAtLeast(0)
            val x1 = (cx + rx).coerceAtMost(tw - 1)
            val y0 = (cy - ry).coerceAtLeast(0)
            val y1 = (cy + ry).coerceAtMost(th - 1)
            val vals = DoubleArray((x1 - x0 + 1) * (y1 - y0 + 1))
            var n = 0
            for (y in y0..y1) {
                val row = y * tw
                for (x in x0..x1) {
                    val d = grid[row + x].toDouble()
                    if (d.isFinite() && d >= 0.01 && d <= 1e6) {
                        vals[n++] = d
                    }
                }
            }
            if (n == 0) return null
            java.util.Arrays.sort(vals, 0, n)
            return vals[n / 2]
        }
    }
}
