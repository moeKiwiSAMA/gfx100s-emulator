package com.github.nyabanban.fuji.emulator.api

/**
 * Lightweight, zero-pixel-render descriptor of a dynamic 3D scene frame that can be
 * rendered directly into `DEVICE_LOCAL` Vulkan VRAM by the GPU 3D scene rasterizer /
 * raytracer (`fuji_vulkan_scene3d`) with zero CPU rasterization and zero per-frame
 * PCIe scene upload.
 *
 * When Vulkan is unavailable (or an active specialty CPU hook/mask requires CPU optics),
 * callers transparently fall back to [SceneSource3D.renderForPose] on CPU.
 */
sealed interface GpuScene3dRenderSpec {
    val width: Int
    val height: Int
    val pose: CameraPose3D
    /** Non-zero key identifying this rendered frame in `DEVICE_LOCAL` VRAM. */
    val sceneKey: Long
    /** Monotonic scene sequence token for exposure generation tracking. */
    val sequence: Long

    /**
     * Animated Milk Frog 3D mesh + static studio stage + turntable pedestal +
     * focus ruler + up to 8 depth-aware HDR orbs (tears & foreground bokeh bulbs).
     */
    class MilkFrog(
        override val width: Int,
        override val height: Int,
        override val pose: CameraPose3D,
        override val sceneKey: Long,
        override val sequence: Long,
        val meshKey: Long,
        val vertexDataProvider: () -> FloatArray,
        val vertexCount: Int,
        val indicesProvider: () -> IntArray,
        val triCount: Int,
        val stageKey: Long,
        val stageRgbaDepthProvider: () -> FloatArray,
        val hasPose: Boolean,
        val hasTranslation: Boolean,
        val halfW: Float,
        val halfH: Float,
        val focal: Float,
        val invFocalScale: Float,
        val ox: Float,
        val oy: Float,
        val oz: Float,
        val cR: Float,
        val sR: Float,
        val yawShiftPx: Float,
        val pitchShiftPx: Float,
        val posXM: Float,
        val posYM: Float,
        val posZM: Float,
        val r00: Float,
        val r01: Float,
        val r02: Float,
        val r10: Float,
        val r11: Float,
        val r12: Float,
        val r20: Float,
        val r21: Float,
        val r22: Float,
        val frogX: Float,
        val frogY: Float,
        val frogZ: Float,
        val cLean: Float,
        val sLean: Float,
        val cRock: Float,
        val sRock: Float,
        val bellySquash: Float,
        val handBounce: Float,
        val kickPhase: Float,
        val mouthVib: Float,
        val arcPulse: Float,
        val orbCount: Int,
        val pedZ: Float,
        val pedTopY: Float,
        val pedBotY: Float,
        val pedRad: Float,
        /** Packed up to 8 orbs x 8 floats `[cx, cy, rad, haloRad, r, g, b, depth]`. */
        val orbs: FloatArray,
    ) : GpuScene3dRenderSpec

    /**
     * Analytic 6-DoF 3D ray-traced studio (`scene://qr-ball`) with scannable 5.5m QR wall,
     * 8 HDR studio lamps, plinth/column, and calibrated foreground sphere (0.83..1.05m).
     */
    class QrBall @JvmOverloads constructor(
        override val width: Int,
        override val height: Int,
        override val pose: CameraPose3D,
        override val sceneKey: Long,
        override val sequence: Long,
        val qrKey: Long,
        val qrRowBits32: IntArray,
        val qrModules: Int,
        val quietZone: Int,
        val totalQrModules: Int,
        val halfW: Float,
        val halfH: Float,
        val focal: Float,
        val ox: Float,
        val oy: Float,
        val oz: Float,
        val r00: Float,
        val r01: Float,
        val r02: Float,
        val r10: Float,
        val r11: Float,
        val r12: Float,
        val r20: Float,
        val r21: Float,
        val r22: Float,
        val wallZ: Float,
        val rotationTurns: Float = 0f,
    ) : GpuScene3dRenderSpec

    /**
     * 60 fps sculpted 3D Mona Lisa portrait + parallax Tuscan valley backdrop (`scene://mona-lisa`).
     */
    class MonaLisa(
        override val width: Int,
        override val height: Int,
        override val pose: CameraPose3D,
        override val sceneKey: Long,
        override val sequence: Long,
        val portraitKey: Long,
        val srcWidth: Int,
        val srcHeight: Int,
        val fgRgbaProvider: () -> FloatArray,
        val bgRgbDepthOffsetProvider: () -> FloatArray,
        val bgShiftPx: Float,
        val bgScaleX: Float,
        val bgScaleY: Float,
        val invBgZoom: Float,
        val bgCx: Float,
        val bgCy: Float,
        val dstPivotX: Float,
        val dstPivotY: Float,
        val srcPivotX: Float,
        val srcPivotY: Float,
        val stepXX: Float,
        val stepXY: Float,
        val stepYX: Float,
        val stepYY: Float,
        val subjectDepthM: Float,
        val backdropDepthM: Float,
    ) : GpuScene3dRenderSpec

    /**
     * Multi-depth layered 3D optical studio (`scene://depth-studio`, `bokeh-city`, `star-bokeh`,
     * `resolution-chart`) with one-time VRAM base upload and GPU 6-DoF parallax reprojection.
     */
    class LayeredStudio(
        override val width: Int,
        override val height: Int,
        override val pose: CameraPose3D,
        override val sceneKey: Long,
        override val sequence: Long,
        val baseKey: Long,
        val baseRgbaDepthProvider: () -> FloatArray,
        val hasPose: Boolean,
        val hasTranslation: Boolean,
        val halfW: Float,
        val halfH: Float,
        val invFocalScale: Float,
        val focal: Float,
        val cosR: Float,
        val sinR: Float,
        val yawShiftPx: Float,
        val pitchShiftPx: Float,
        val posXM: Float,
        val posYM: Float,
        val posZM: Float,
        val bgDepthM: Float,
    ) : GpuScene3dRenderSpec
}
