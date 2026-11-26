#ifndef FUJI_VULKAN_SCENE3D_H
#define FUJI_VULKAN_SCENE3D_H

/*
 * FUJIFILM GFX100S Emulator - Vulkan 1.1 Compute 3D Scene Rasterizer & Raytracer
 *
 * Renders dynamic 3D scenes (Milk Frog 14,644-tri animated mesh, QR-Ball analytic 3D
 * raytracer, Mona Lisa 60fps sculpted portrait, and 6-DoF layered bokeh/depth studios)
 * directly into the shared DEVICE_LOCAL VRAM scene buffer (`bufSceneDevice`) inside
 * `FujiVkContext` so `fuji_vk_render_cached_scene_to_isp_yuv` / `to_bayer` / `optics`
 * consume it with ZERO CPU rasterization and ZERO per-frame PCIe scene upload.
 *
 * Also writes a compact 16x16-tile metric depth grid (e.g. 64x36 = 9 KiB) for
 * real-time AF / PDAF `DepthSnapshot` and center-AF distance telemetry.
 */

#include "fuji_vulkan_optics.h"

#ifdef __cplusplus
extern "C" {
#endif

#define FUJI_VK_SCENE3D_MAX_ORBS           8u
#define FUJI_VK_SCENE3D_TILE_SIZE          16u
/* Legacy source constant. The rasterizer retains all source triangles per tile. */
#define FUJI_VK_SCENE3D_MAX_TRIS_PER_TILE  512u

/* Embedded Scene3D SPIR-V shader IDs */
#define FUJI_VK_SCENE3D_SHADER_MF_VERTEX   0u
#define FUJI_VK_SCENE3D_SHADER_MF_BIN      1u
#define FUJI_VK_SCENE3D_SHADER_MF_RASTER   2u
#define FUJI_VK_SCENE3D_SHADER_QR_BALL     3u
#define FUJI_VK_SCENE3D_SHADER_MONA_LISA   4u
#define FUJI_VK_SCENE3D_SHADER_LAYERED     5u
#define FUJI_VK_SCENE3D_SHADER_COUNT       6u

/*
 * Milk Frog 3D mesh + studio stage frame parameters (448 bytes, 112 scalar 32-bit fields).
 */
typedef struct FujiVkScene3dMilkFrogParams {
    uint32_t width;
    uint32_t height;
    uint32_t tilesX;
    uint32_t tilesY;

    uint32_t vertexCount;
    uint32_t triCount;
    uint32_t hasPose;
    uint32_t hasTranslation;

    float    halfW;
    float    halfH;
    float    focal;
    float    invFocalScale;

    float    ox;
    float    oy;
    float    oz;
    float    cR;

    float    sR;
    float    yawShiftPx;
    float    pitchShiftPx;
    float    posXM;

    float    posYM;
    float    posZM;
    float    r00;
    float    r01;

    float    r02;
    float    r10;
    float    r11;
    float    r12;

    float    r20;
    float    r21;
    float    r22;
    float    frogX;

    float    frogY;
    float    frogZ;
    float    cLean;
    float    sLean;

    float    cRock;
    float    sRock;
    float    bellySquash;
    float    handBounce;

    float    kickPhase;
    float    mouthVib;
    float    arcPulse;
    uint32_t orbCount;

    float    pedZ;
    float    pedTopY;
    float    pedBotY;
    float    pedRad;

    /* Up to 8 depth-aware HDR orbs: [cx, cy, rad, haloRad, r, g, b, depth] each */
    float    orbs[FUJI_VK_SCENE3D_MAX_ORBS * 8u];
} FujiVkScene3dMilkFrogParams;

/*
 * QR-Ball analytic 6-DoF 3D studio raytracer parameters (96 bytes, 24 scalar 32-bit fields).
 */
typedef struct FujiVkScene3dQrBallParams {
    uint32_t width;
    uint32_t height;
    uint32_t tilesX;
    uint32_t tilesY;

    uint32_t qrModules;
    uint32_t quietZone;
    uint32_t totalQrModules;
    float    rotationTurns;

    float    halfW;
    float    halfH;
    float    focal;
    float    ox;

    float    oy;
    float    oz;
    float    r00;
    float    r01;

    float    r02;
    float    r10;
    float    r11;
    float    r12;

    float    r20;
    float    r21;
    float    r22;
    float    wallZ;
} FujiVkScene3dQrBallParams;

/*
 * Mona Lisa 60fps sculpted 3D portrait compositor parameters (96 bytes, 24 scalar 32-bit fields).
 */
typedef struct FujiVkScene3dMonaLisaParams {
    uint32_t dstWidth;
    uint32_t dstHeight;
    uint32_t srcWidth;
    uint32_t srcHeight;

    uint32_t tilesX;
    uint32_t tilesY;
    uint32_t reserved0;
    uint32_t reserved1;

    float    bgShiftPx;
    float    bgScaleX;
    float    bgScaleY;
    float    invBgZoom;

    float    bgCx;
    float    bgCy;
    float    dstPivotX;
    float    dstPivotY;

    float    srcPivotX;
    float    srcPivotY;
    float    stepXX;
    float    stepXY;

    float    stepYX;
    float    stepYY;
    float    subjectDepthM;
    float    backdropDepthM;
} FujiVkScene3dMonaLisaParams;

/*
 * Layered 3D optical studio (depth-studio / bokeh-city / star-bokeh / resolution-chart)
 * 6-DoF parallax reprojection parameters (80 bytes, 20 scalar 32-bit fields).
 */
typedef struct FujiVkScene3dLayeredParams {
    uint32_t width;
    uint32_t height;
    uint32_t tilesX;
    uint32_t tilesY;

    uint32_t hasPose;
    uint32_t hasTranslation;
    uint32_t reserved0;
    uint32_t reserved1;

    float    halfW;
    float    halfH;
    float    invFocalScale;
    float    focal;

    float    cosR;
    float    sinR;
    float    yawShiftPx;
    float    pitchShiftPx;

    float    posXM;
    float    posYM;
    float    posZM;
    float    bgDepthM;
} FujiVkScene3dLayeredParams;

/* Query embedded Scene3D SPIR-V bytecode (for verification/diagnostics). */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_get_spirv_words(
    uint32_t shader_id,
    const uint32_t** out_words,
    uint32_t* out_word_count);

/*
 * Upload Milk Frog static 3D mesh once into DEVICE_LOCAL VRAM (keyed by mesh_key).
 * vertex_data: vertex_count * 12 floats = 3 vec4 per vertex:
 *   [posX, posY, posZ, partIdFloat, normX, normY, normZ, 0, linR, linG, linB, 0]
 * indices: tri_count * 3 uint32 vertex indices.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_mesh(
    FujiVkContext* ctx,
    uint64_t mesh_key,
    const float* vertex_data,
    uint32_t vertex_count,
    const uint32_t* indices,
    uint32_t tri_count,
    double* out_gpu_ms);

/*
 * Upload Milk Frog static studio backdrop + Macbeth/MTF easels + 20 HDR bokeh orbs once
 * into DEVICE_LOCAL VRAM (keyed by stage_key).
 * stage_rgba_depth: width * height * 4 floats (R, G, B, depthMeters).
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_stage(
    FujiVkContext* ctx,
    uint64_t stage_key,
    const float* stage_rgba_depth,
    uint32_t width,
    uint32_t height,
    double* out_gpu_ms);

/*
 * Render animated Milk Frog 3D mesh + stage + turntable pedestal + focus ruler + HDR orbs
 * directly into `ctx`'s DEVICE_LOCAL `bufSceneDevice` tagged with `out_scene_key`.
 * Optionally fills `out_depth_grid` (tilesX * tilesY floats) for AF/PDAF DepthSnapshot.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_milk_frog(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dMilkFrogParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms);

/*
 * Upload QR-Ball 25x25 (up to 32x32) QR matrix bitmask once into DEVICE_LOCAL VRAM.
 * row_bits: 32 uint32 words where bit c of row_bits[r] is 1 for dark module (r, c).
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_qr_matrix(
    FujiVkContext* ctx,
    uint64_t qr_key,
    const uint32_t* row_bits_32,
    double* out_gpu_ms);

/*
 * Render analytic 6-DoF 3D QR-Ball studio directly into DEVICE_LOCAL `bufSceneDevice`.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_qr_ball(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dQrBallParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms);

/*
 * Upload Mona Lisa prepared foreground (fgR, fgG, fgB, fgAlpha) and backdrop+relief
 * (bgR, bgG, bgB, fgDepthOffset) once into DEVICE_LOCAL VRAM.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_mona_lisa(
    FujiVkContext* ctx,
    uint64_t portrait_key,
    const float* fg_rgba,
    const float* bg_rgb_depth_offset,
    uint32_t src_width,
    uint32_t src_height,
    double* out_gpu_ms);

/*
 * Composite animated 3D Mona Lisa portrait + parallax backdrop directly into DEVICE_LOCAL `bufSceneDevice`.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_mona_lisa(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dMonaLisaParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms);

/*
 * Upload layered 3D optical studio base frame (depth-studio / bokeh-city / star-bokeh /
 * resolution-chart) once into DEVICE_LOCAL VRAM (keyed by base_key).
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_layered_base(
    FujiVkContext* ctx,
    uint64_t base_key,
    const float* base_rgba_depth,
    uint32_t width,
    uint32_t height,
    double* out_gpu_ms);

/*
 * Reproject (or zero-copy pass-through on identity pose) the GPU-resident layered 3D
 * studio scene directly into DEVICE_LOCAL `bufSceneDevice`.
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_layered(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dLayeredParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms);

/*
 * Optional host readback of the current DEVICE_LOCAL `bufSceneDevice` RGBA+Depth buffer
 * (used for parity verification, CPU fallback interop, and diagnostics).
 */
FUJI_VK_EXPORT int32_t fuji_vk_scene3d_readback_scene(
    FujiVkContext* ctx,
    float* out_rgba_depth,
    size_t pixel_count,
    double* out_gpu_ms);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_VULKAN_SCENE3D_H */
