/*
 * Standalone C++ Vulkan 1.1 Compute 3D Scene Rasterizer & Raytracer self-test.
 * Verifies:
 *   1. All 6 Scene3D SPIR-V binaries load & compile on Vulkan 1.1 Compute (hardware or Lavapipe).
 *   2. Milk Frog tiled triangle compute rasterizer: vertex deform, double-sided Z-buffer,
 *      pedestal/ruler, HDR orbs, depth grid, and direct DEVICE_LOCAL handoff to
 *      fuji_vk_render_cached_scene_optics (ZERO PCIe scene upload!).
 *   3. QR-Ball analytic 6-DoF 3D raytracer: foreground sphere (~0.83m), back wall QR (5.5m),
 *      6 HDR wall lamps, and 6-DoF pose response.
 *   4. Mona Lisa 3D portrait compositor: foreground relief depth + backdrop parallax.
 *   5. Layered 3D optical studio (depth-studio) identity + 6-DoF parallax reprojection.
 */

#include "fuji_vulkan_scene3d.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#define CHECK(cond, msg) do { \
    if (!(cond)) { \
        std::fprintf(stderr, "[test_vulkan_scene3d] FAIL: %s (line %d, lastErr='%s')\n", (msg), __LINE__, ctx ? fuji_vk_last_error(ctx) : ""); \
        std::exit(1); \
    } \
} while (0)

int main() {
    FujiVkContext* ctx = nullptr;
    for (uint32_t s = 0; s < FUJI_VK_SCENE3D_SHADER_COUNT; ++s) {
        const uint32_t* words = nullptr;
        uint32_t count = 0u;
        CHECK(fuji_vk_scene3d_get_spirv_words(s, &words, &count) == FUJI_VK_OK, "get_spirv_words");
        CHECK(words != nullptr && count > 16u && words[0] == 0x07230203u, "valid SPIR-V magic");
    }

    FujiVkDeviceInfo info{};
    char err[512] = {};
    int32_t rc = fuji_vk_create(
        FUJI_VK_SELECT_PREFER_DISCRETE | FUJI_VK_SELECT_ALLOW_INTEGRATED | FUJI_VK_SELECT_ALLOW_SOFTWARE,
        &ctx, &info, err, sizeof(err));
    if (rc != FUJI_VK_OK || !ctx) {
        std::printf("[test_vulkan_scene3d] SKIP: no Vulkan device available (%s)\n", err);
        return 0;
    }
    std::printf("[test_vulkan_scene3d] Device: %s (type=%u)\n", info.deviceName, info.deviceType);

    /* ---- Test 1: Milk Frog tiled compute rasterizer + direct cached-scene optics handoff ---- */
    const uint32_t W = 256u, H = 144u;
    const uint32_t TX = (W + 15u) / 16u, TY = (H + 15u) / 16u;
    std::vector<float> stage(W * H * 4u);
    for (uint32_t i = 0; i < W * H; ++i) {
        stage[i * 4u + 0u] = 0.02f;
        stage[i * 4u + 1u] = 0.03f;
        stage[i * 4u + 2u] = 0.05f;
        stage[i * 4u + 3u] = 10.5f;
    }
    /* Two overlapping triangles (front butter-yellow at z=1.50, back blue at z=1.90) to test Z-buffer & double-sided winding */
    std::vector<float> verts = {
        /* v0..v2: front butter-yellow quad-half (CW winding -> tests double-sided flip!) */
        -0.18f, -0.15f, -0.05f, 0.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.90f, 0.68f, 0.25f, 0.0f,
         0.18f, -0.15f, -0.05f, 0.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.90f, 0.68f, 0.25f, 0.0f,
         0.00f,  0.22f, -0.05f, 0.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.92f, 0.72f, 0.28f, 0.0f,
        /* v3..v5: farther blue triangle behind it */
        -0.25f, -0.20f,  0.35f, 1.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.10f, 0.20f, 0.90f, 0.0f,
         0.25f, -0.20f,  0.35f, 1.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.10f, 0.20f, 0.90f, 0.0f,
         0.00f,  0.28f,  0.35f, 1.0f,  0.0f, 0.0f, -1.0f, 0.0f,  0.10f, 0.20f, 0.90f, 0.0f,
    };
    std::vector<uint32_t> indices = {0u, 1u, 2u, 3u, 5u, 4u};

    double gpuMs = 0.0;
    CHECK(fuji_vk_scene3d_upload_mesh(ctx, 1001ULL, verts.data(), 6u, indices.data(), 2u, &gpuMs) == FUJI_VK_OK, "upload_mesh");
    CHECK(fuji_vk_scene3d_upload_stage(ctx, 2001ULL, stage.data(), W, H, &gpuMs) == FUJI_VK_OK, "upload_stage");

    FujiVkScene3dMilkFrogParams mf{};
    mf.width = W; mf.height = H; mf.tilesX = TX; mf.tilesY = TY;
    mf.vertexCount = 6u; mf.triCount = 2u;
    mf.halfW = (W - 1u) * 0.5f; mf.halfH = (H - 1u) * 0.5f;
    mf.focal = W * 0.76f; mf.invFocalScale = 1.0f;
    mf.cR = 1.0f; mf.r00 = 1.0f; mf.r11 = 1.0f; mf.r22 = 1.0f;
    mf.frogZ = 1.55f; mf.cLean = 1.0f; mf.cRock = 1.0f;
    mf.pedZ = 1.55f; mf.pedTopY = -0.458f; mf.pedBotY = -0.538f; mf.pedRad = 0.36f;
    mf.orbCount = 1u;
    mf.orbs[0] = 24.0f; mf.orbs[1] = 24.0f; mf.orbs[2] = 6.0f; mf.orbs[3] = 10.0f;
    mf.orbs[4] = 6.5f; mf.orbs[5] = 4.5f; mf.orbs[6] = 1.5f; mf.orbs[7] = 0.65f;

    std::vector<float> grid(TX * TY, 0.0f);
    CHECK(fuji_vk_scene3d_render_milk_frog(ctx, 0xABCD1001ULL, &mf, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render_milk_frog");
    std::printf("[test_vulkan_scene3d] MilkFrog 256x144 raster: %.2f ms\n", gpuMs);

    std::vector<float> fb(W * H * 4u, 0.0f);
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback milk_frog");
    /* Center pixel should hit front butter-yellow triangle at z ≈ 1.50m (NOT farther blue triangle at 1.90m!) */
    const uint32_t cIdx = (H / 2u) * W + (W / 2u);
    CHECK(std::fabs(fb[cIdx * 4u + 3u] - 1.50f) < 0.05f, "milkfrog front triangle Z-buffer depth ~1.50m");
    CHECK(fb[cIdx * 4u + 0u] > fb[cIdx * 4u + 2u] * 1.8f, "milkfrog front triangle is warm yellow over blue");
    /* HDR orb at (24, 24) should have radiance > 4.0 and depth 0.65m */
    const uint32_t oIdx = 24u * W + 24u;
    CHECK(fb[oIdx * 4u + 0u] > 4.0f && std::fabs(fb[oIdx * 4u + 3u] - 0.65f) < 0.01f, "milkfrog HDR orb");
    /* Direct GPU-to-GPU handoff: fuji_vk_render_cached_scene_optics consumes bufSceneDevice with ZERO upload! */
    FujiVkOpticsParams op{};
    op.srcWidth = W; op.srcHeight = H; op.dstWidth = W; op.dstHeight = H;
    op.caRedScale = 1.0f; op.caBlueScale = 1.0f; op.focalLengthMm = 45.0f; op.sensorWidthMm = 43.8f;
    op.focusDistanceM = 1.50f; op.fNumber = 4.0f; op.invZoom = 1.0f; op.ibisCosRoll = 1.0f;
    op.planarDepthM = 1.50f; op.flags = 1u;
    std::vector<float> optOut(W * H * 4u, 0.0f);
    CHECK(fuji_vk_render_cached_scene_optics(ctx, 0xABCD1001ULL, optOut.data(), nullptr, &op, &gpuMs) == FUJI_VK_OK, "cached_scene_optics zero-upload handoff");
    CHECK(optOut[cIdx * 4u + 0u] > 0.35f, "cached_scene_optics preserved rendered Milk Frog pixel");

    // More than 64 distinct 32-triangle groups forces the dense occupancy path.
    // The front-most primitive comes after 2304 occluders; the old 512-entry tile would lose it.
    const auto reference = fb;
    std::vector<uint32_t> crowdedIndices;
    for (int t = 0; t < 2304; ++t) crowdedIndices.insert(crowdedIndices.end(), {3u, 5u, 4u});
    crowdedIndices.insert(crowdedIndices.end(), {0u, 1u, 2u});
    // Duplicate the front geometry, with visibly different material at the exact same depth.
    auto crowdedVerts = verts;
    crowdedVerts.insert(crowdedVerts.end(), verts.begin(), verts.begin() + 36);
    for (int v = 6; v < 9; ++v) {
        crowdedVerts[v * 12 + 8] = 0.05f; crowdedVerts[v * 12 + 9] = 0.15f; crowdedVerts[v * 12 + 10] = 0.90f;
    }
    crowdedIndices.insert(crowdedIndices.end(), {6u, 7u, 8u});
    auto crowded = mf; crowded.vertexCount = 9; crowded.triCount = static_cast<uint32_t>(crowdedIndices.size() / 3);
    CHECK(fuji_vk_scene3d_upload_mesh(ctx, 1002ULL, crowdedVerts.data(), crowded.vertexCount, crowdedIndices.data(),
                                      crowded.triCount, &gpuMs) == FUJI_VK_OK, "upload overlapping triangle groups");
    for (int repeat = 0; repeat < 4; ++repeat) {
        CHECK(fuji_vk_scene3d_render_milk_frog(ctx, 0xCC1000ULL + repeat, &crowded, grid.data(),
                                              static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render dense tiles");
        CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "read back dense tiles");
        CHECK(fb == reference, "Dense tiles must keep the nearest triangle and the first coplanar material");
    }
    CHECK(fuji_vk_scene3d_upload_mesh(ctx, 1001ULL, verts.data(), 6u, indices.data(), 2u, &gpuMs) == FUJI_VK_OK,
          "restore original mesh");


    /* ---- Test 2: QR-Ball analytic 6-DoF raytracer ---- */
    uint32_t qrRows[32] = {};
    for (uint32_t r = 0; r < 25u; ++r) qrRows[r] = (r & 1u) ? 0x01555555u : 0x00AAAAAAu;
    CHECK(fuji_vk_scene3d_upload_qr_matrix(ctx, 3001ULL, qrRows, &gpuMs) == FUJI_VK_OK, "upload_qr_matrix");

    FujiVkScene3dQrBallParams qb{};
    qb.width = W; qb.height = H; qb.tilesX = TX; qb.tilesY = TY;
    qb.qrModules = 25u; qb.quietZone = 4u; qb.totalQrModules = 33u;
    qb.halfW = W * 0.5f; qb.halfH = H * 0.5f; qb.focal = W * 0.60f;
    qb.r00 = 1.0f; qb.r11 = 1.0f; qb.r22 = 1.0f; qb.wallZ = 5.5f;
    CHECK(fuji_vk_scene3d_render_qr_ball(ctx, 0xABCD2001ULL, &qb, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render_qr_ball");
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback qr_ball");
    /* Foreground sphere around (0.34*W, 0.56*H) should be at ~0.83..1.05m; back QR poster right side at 5.5m */
    const uint32_t sphIdx = static_cast<uint32_t>(H * 0.56f) * W + static_cast<uint32_t>(W * 0.34f);
    const uint32_t wallIdx = (H / 2u) * W + static_cast<uint32_t>(W * 0.72f);
    CHECK(fb[sphIdx * 4u + 3u] >= 0.80f && fb[sphIdx * 4u + 3u] <= 1.08f, "qr_ball sphere depth ~0.83..1.05m");
    CHECK(std::fabs(fb[wallIdx * 4u + 3u] - 5.5f) < 0.05f, "qr_ball back wall depth 5.5m");
    std::printf("[test_vulkan_scene3d] QR-Ball 256x144: sphereD=%.3fm wallD=%.3fm (%.2f ms)\n",
        fb[sphIdx * 4u + 3u], fb[wallIdx * 4u + 3u], gpuMs);

    const auto restingBall = fb;
    qb.rotationTurns = 1.0f / 32.0f;
    CHECK(fuji_vk_scene3d_render_qr_ball(ctx, 0xABCD2002ULL, &qb, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "rotate calibrated sphere");
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback rotated sphere");
    uint32_t recolored = 0;
    for (uint32_t i = 0; i < W * H; ++i) {
        const auto pixel = i * 4u;
        const float depth = fb[pixel + 3u];
        CHECK(depth == restingBall[pixel + 3u], "rotation preserves the depth of every pixel");
        if (fb[pixel] != restingBall[pixel] || fb[pixel + 1u] != restingBall[pixel + 1u] || fb[pixel + 2u] != restingBall[pixel + 2u]) {
            CHECK(depth >= 0.8f && depth < 1.28f, "the room must remain stationary");
            ++recolored;
        }
    }
    CHECK(recolored > 400u, "sphere material must visibly turn");

    /* ---- Test 3: Mona Lisa 3D portrait compositor ---- */
    const uint32_t SW = 64u, SH = 48u;
    std::vector<float> monaFg(SW * SH * 4u, 0.0f);
    std::vector<float> monaBg(SW * SH * 4u, 0.0f);
    for (uint32_t y = 0; y < SH; ++y) {
        for (uint32_t x = 0; x < SW; ++x) {
            uint32_t i = (y * SW + x) * 4u;
            bool inFace = (x >= 20u && x <= 44u && y >= 10u && y <= 38u);
            monaFg[i + 0u] = 0.75f; monaFg[i + 1u] = 0.52f; monaFg[i + 2u] = 0.34f;
            monaFg[i + 3u] = inFace ? 1.0f : 0.0f;
            monaBg[i + 0u] = 0.12f; monaBg[i + 1u] = 0.18f; monaBg[i + 2u] = 0.26f;
            monaBg[i + 3u] = -0.05f;
        }
    }
    CHECK(fuji_vk_scene3d_upload_mona_lisa(ctx, 4001ULL, monaFg.data(), monaBg.data(), SW, SH, &gpuMs) == FUJI_VK_OK, "upload_mona_lisa");
    FujiVkScene3dMonaLisaParams ml{};
    ml.dstWidth = W; ml.dstHeight = H; ml.srcWidth = SW; ml.srcHeight = SH;
    ml.bgScaleX = (SW - 1.0f) / (W - 1.0f); ml.bgScaleY = (SH - 1.0f) / (H - 1.0f);
    ml.invBgZoom = 1.0f; ml.bgCx = (SW - 1.0f) * 0.5f; ml.bgCy = (SH - 1.0f) * 0.5f;
    ml.dstPivotX = W * 0.50f; ml.dstPivotY = H * 0.46f;
    ml.srcPivotX = SW * 0.485f; ml.srcPivotY = SH * 0.44f;
    float invFit = static_cast<float>(SH) / static_cast<float>(H);
    ml.stepXX = invFit; ml.stepYY = invFit;
    ml.subjectDepthM = 1.95f; ml.backdropDepthM = 4.50f;
    CHECK(fuji_vk_scene3d_render_mona_lisa(ctx, 0xABCD3001ULL, &ml, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render_mona_lisa");
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback mona_lisa");
    CHECK(std::fabs(fb[cIdx * 4u + 3u] - 1.90f) < 0.05f, "mona_lisa center foreground depth 1.90m");
    CHECK(std::fabs(fb[4u * 4u + 3u] - 4.50f) < 0.05f, "mona_lisa corner backdrop depth 4.50m");

    /* ---- Test 4: Layered 3D studio (depth-studio) identity + 6-DoF reprojection ---- */
    std::vector<float> layered(W * H * 4u);
    for (uint32_t y = 0; y < H; ++y) {
        for (uint32_t x = 0; x < W; ++x) {
            uint32_t i = (y * W + x) * 4u;
            layered[i + 0u] = 0.2f; layered[i + 1u] = 0.5f; layered[i + 2u] = 0.8f;
            layered[i + 3u] = (x < W / 3u) ? 0.8f : ((x < 2u * W / 3u) ? 2.5f : 12.0f);
        }
    }
    CHECK(fuji_vk_scene3d_upload_layered_base(ctx, 5001ULL, layered.data(), W, H, &gpuMs) == FUJI_VK_OK, "upload_layered_base");
    FujiVkScene3dLayeredParams lp{};
    lp.width = W; lp.height = H; lp.hasPose = 0u; lp.bgDepthM = 12.0f;
    lp.halfW = (W - 1u) * 0.5f; lp.halfH = (H - 1u) * 0.5f; lp.invFocalScale = 1.0f; lp.focal = W * 0.85f; lp.cosR = 1.0f;
    CHECK(fuji_vk_scene3d_render_layered(ctx, 0xABCD4001ULL, &lp, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render_layered identity");
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback layered");
    CHECK(std::fabs(fb[cIdx * 4u + 3u] - 2.5f) < 0.01f, "layered midground depth 2.5m");
    lp.hasPose = 1u; lp.hasTranslation = 1u; lp.posZM = 0.5f;
    CHECK(fuji_vk_scene3d_render_layered(ctx, 0xABCD4002ULL, &lp, grid.data(), static_cast<uint32_t>(grid.size()), &gpuMs) == FUJI_VK_OK, "render_layered translated");
    CHECK(fuji_vk_scene3d_readback_scene(ctx, fb.data(), W * H, &gpuMs) == FUJI_VK_OK, "readback layered translated");
    CHECK(std::fabs(fb[cIdx * 4u + 3u] - 2.0f) < 0.05f, "layered translated depth 2.0m");

    fuji_vk_destroy(ctx);
    std::printf("[test_vulkan_scene3d] ALL NATIVE VULKAN 3D SCENE RASTERIZER TESTS PASSED!\n");
    return 0;
}
