/*
 * Native C++17 parity & self-test for libfuji_vulkan_optics.so / fuji_vulkan_optics.dll.
 * Verifies:
 *  1. ABI version & embedded SPIR-V 1.0 magic/header for all 3 compute shaders.
 *  2. Default hardware-GPU-first selection cleanly skips software ICD when no dGPU/iGPU exists.
 *  3. Real Vulkan 1.1+ compute execution (using software ICD when allowed on headless hosts):
 *     - Pass 1 (optics_warp): identity, vignetting falloff, lateral CA shift, IBIS shift.
 *     - Pass 2 (pupil_bokeh): thin-lens CoC depth bokeh across ALL 5 pupil shapes
 *       (CIRCLE, POLYGON, STAR_5 five-pointed star, HEART, RING) producing distinct PSF profiles!
 *     - Pass 3 (cmos_bayer): noiseless exact RGGB Bayer readout + stateless SplitMix64 noise
 *       determinism & positive variance + chained single-submit optics_to_bayer!
 *  4. External GPU interop descriptor validation (`fuji_vk_import_external_frame`).
 */

#include "fuji_vulkan_optics.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#define ASSERT_TRUE(cond, msg)                                                       \
    do {                                                                             \
        if (!(cond)) {                                                               \
            std::fprintf(stderr, "FAIL [%s:%d]: %s\n", __FILE__, __LINE__, (msg));   \
            std::exit(1);                                                            \
        }                                                                            \
    } while (0)

static FujiVkOpticsParams make_default_optics(uint32_t w, uint32_t h) {
    FujiVkOpticsParams p{};
    p.srcWidth = w;
    p.srcHeight = h;
    p.dstWidth = w;
    p.dstHeight = h;
    p.caRedScale = 1.0f;
    p.caBlueScale = 1.0f;
    p.focalLengthMm = 50.0f;
    p.sensorWidthMm = 43.8f;
    p.focusDistanceM = 2.0f;
    p.fNumber = 1.4f;
    p.invZoom = 1.0f;
    p.ibisCosRoll = 1.0f;
    p.ibisSinRoll = 0.0f;
    p.maxBokehPx = 0.0f;
    p.bladeRoundness = 0.5f;
    p.planarDepthM = 2.0f;
    p.imageCircleMm = 55.0f;
    p.pupilShape = FUJI_VK_PUPIL_CIRCLE;
    p.bladeCount = 7;
    p.borderClamp = 0;
    p.flags = 0;
    return p;
}

int main() {
    ASSERT_TRUE(fuji_vk_abi_version() == FUJI_VK_OPTICS_ABI_VERSION, "ABI version mismatch");

    /* 1. Validate all 3 embedded SPIR-V binaries */
    for (uint32_t s = 0; s < FUJI_VK_SHADER_COUNT; ++s) {
        const uint32_t* words = nullptr;
        uint32_t count = 0;
        ASSERT_TRUE(fuji_vk_get_spirv_words(s, &words, &count) == FUJI_VK_OK, "get_spirv_words failed");
        ASSERT_TRUE(words != nullptr && count >= 16, "SPIR-V too short");
        ASSERT_TRUE(words[0] == 0x07230203u, "Invalid SPIR-V magic");
        ASSERT_TRUE(words[1] == 0x00010000u, "Invalid SPIR-V 1.0 version");
        ASSERT_TRUE(words[3] > 10u, "Invalid SPIR-V ID bound");
    }

    /* 2. Hardware-first selection (skips CPU/software ICD when no hardware GPU) */
    {
        FujiVkContext* hwCtx = nullptr;
        FujiVkDeviceInfo hwInfo{};
        char err[256] = {0};
        int32_t rc = fuji_vk_create(FUJI_VK_SELECT_DEFAULT, &hwCtx, &hwInfo, err, sizeof(err));
        if (rc == FUJI_VK_OK) {
            ASSERT_TRUE(hwInfo.deviceType == 1 || hwInfo.deviceType == 2 || hwInfo.deviceType == 3,
                        "Hardware-first mode must not pick CPU device");
            std::printf("[test_vulkan_optics] Hardware GPU found: %s (type=%u)\n", hwInfo.deviceName, hwInfo.deviceType);
            fuji_vk_destroy(hwCtx);
        } else {
            ASSERT_TRUE(hwCtx == nullptr, "Failed create must leave ctx null");
            std::printf("[test_vulkan_optics] Hardware-first gracefully skipped software/missing GPU: %s\n", err);
        }
    }

    /* 2B. Enumerate physical devices + explicit device_index create + out-of-range rejection */
    {
        FujiVkDeviceInfo devs[16]{};
        int32_t total = 0;
        char enumErr[256] = {0};
        int32_t erc = fuji_vk_enumerate_devices(devs, 16, &total, enumErr, static_cast<int32_t>(sizeof(enumErr)));
        if (erc == FUJI_VK_OK) {
            ASSERT_TRUE(total >= 1, "Enumerate OK must report >= 1 device");
            ASSERT_TRUE(devs[0].deviceName[0] != '\0', "Enumerated device[0] must have non-empty name");
            std::printf("[test_vulkan_optics] Enumerated %d Vulkan compute device(s): [0] idx=%u name='%s' type=%u extCaps=0x%x\n",
                        total, devs[0].deviceIndex, devs[0].deviceName, devs[0].deviceType, devs[0].externalMemoryCaps);

            FujiVkContext* explicitCtx = nullptr;
            FujiVkDeviceInfo explicitInfo{};
            char expErr[256] = {0};
            int32_t crc = fuji_vk_create_for_device(
                static_cast<int32_t>(devs[0].deviceIndex),
                FUJI_VK_SELECT_DEFAULT,
                &explicitCtx,
                &explicitInfo,
                expErr,
                sizeof(expErr));
            ASSERT_TRUE(crc == FUJI_VK_OK && explicitCtx != nullptr, "Explicit device create on enumerated index 0 must succeed");
            ASSERT_TRUE(explicitInfo.deviceIndex == devs[0].deviceIndex, "Explicit device index mismatch");
            ASSERT_TRUE(std::strcmp(explicitInfo.deviceName, devs[0].deviceName) == 0, "Explicit device name mismatch");
            fuji_vk_destroy(explicitCtx);

            FujiVkContext* badCtx = nullptr;
            FujiVkDeviceInfo badInfo{};
            char badErr[256] = {0};
            int32_t brc = fuji_vk_create_for_device(9999, FUJI_VK_SELECT_DEFAULT, &badCtx, &badInfo, badErr, sizeof(badErr));
            ASSERT_TRUE(brc == FUJI_VK_ERR_NO_DEVICE && badCtx == nullptr && badErr[0] != '\0',
                        "Out-of-range device index must return FUJI_VK_ERR_NO_DEVICE cleanly");
        } else {
            std::printf("[test_vulkan_optics] Enumerate returned rc=%d (%s) on host without Vulkan ICD\n", erc, enumErr);
        }
    }

    /* 3. Open Vulkan compute device (allowing Lavapipe software ICD on headless CI) */
    FujiVkContext* ctx = nullptr;
    FujiVkDeviceInfo info{};
    char err[256] = {0};
    int32_t rc = fuji_vk_create(
        FUJI_VK_SELECT_DEFAULT | FUJI_VK_SELECT_ALLOW_SOFTWARE,
        &ctx, &info, err, sizeof(err));
    if (rc != FUJI_VK_OK) {
        std::printf("[test_vulkan_optics] No Vulkan ICD available on this host (%s); skipping GPU dispatch tests.\n", err);
        return 0;
    }
    std::printf("[test_vulkan_optics] Active Vulkan compute device: %s (loader=%s, api=0x%x, extCaps=0x%x)\n",
                info.deviceName, info.loaderName, info.apiVersion, info.externalMemoryCaps);

    /* 3A. Optical Warp: identity & mechanical vignetting */
    const uint32_t W = 64, H = 64;
    std::vector<float> inImg(W * H * 4, 0.0f);
    std::vector<float> outImg(W * H * 4, 0.0f);
    for (uint32_t i = 0; i < W * H; ++i) {
        inImg[i * 4 + 0] = 0.8f;
        inImg[i * 4 + 1] = 0.5f;
        inImg[i * 4 + 2] = 0.25f;
        inImg[i * 4 + 3] = 2.0f;
    }

    auto op = make_default_optics(W, H);
    double ms = 0.0;
    ASSERT_TRUE(fuji_vk_render_optics(ctx, inImg.data(), outImg.data(), &op, &ms) == FUJI_VK_OK, "Identity optics dispatch failed");
    const uint32_t centerIdx = (H / 2) * W + (W / 2);
    ASSERT_TRUE(std::fabs(outImg[centerIdx * 4 + 0] - 0.8f) < 1e-4f, "Identity R mismatch");
    ASSERT_TRUE(std::fabs(outImg[centerIdx * 4 + 1] - 0.5f) < 1e-4f, "Identity G mismatch");
    ASSERT_TRUE(std::fabs(outImg[centerIdx * 4 + 2] - 0.25f) < 1e-4f, "Identity B mismatch");

    /* Mechanical + cos^4 vignetting darkens corners vs center */
    op.cos4Power = 4.0f;
    op.mechVignetting = 0.35f;
    ASSERT_TRUE(fuji_vk_render_optics(ctx, inImg.data(), outImg.data(), &op, &ms) == FUJI_VK_OK, "Vignetting dispatch failed");
    ASSERT_TRUE(outImg[0] < outImg[centerIdx * 4 + 0] * 0.65f, "Vignetting must darken corner relative to center");

    /* 3B. True 2D Entrance-Pupil Depth Bokeh across all 5 shapes on a point light source! */
    const uint32_t BW = 96, BH = 96;
    const uint32_t bcx = BW / 2, bcy = BH / 2;
    const uint32_t bCenterIdx = bcy * BW + bcx;
    std::vector<float> bokehIn(BW * BH * 4, 0.0f);
    for (uint32_t i = 0; i < BW * BH; ++i) {
        bokehIn[i * 4 + 3] = 20.0f; /* far background (defocused when focused at 1.0m) */
    }
    /* Single bright pinpoint impulse light at center */
    bokehIn[bCenterIdx * 4 + 0] = 100.0f;
    bokehIn[bCenterIdx * 4 + 1] = 100.0f;
    bokehIn[bCenterIdx * 4 + 2] = 100.0f;

    auto bokehOp = make_default_optics(BW, BH);
    bokehOp.focalLengthMm = 110.0f;
    bokehOp.focusDistanceM = 0.55f;
    bokehOp.fNumber = 1.2f;
    bokehOp.maxBokehPx = 24.0f;
    bokehOp.flags = 3u; /* hasDepth | enableBokeh */

    std::vector<float> psf[5];
    for (uint32_t shape = 0; shape < 5; ++shape) {
        bokehOp.pupilShape = shape;
        bokehOp.bladeCount = 6; /* sharp regular hexagon */
        bokehOp.bladeRoundness = 0.0f;
        psf[shape].assign(BW * BH * 4, 0.0f);
        ASSERT_TRUE(fuji_vk_render_optics(ctx, bokehIn.data(), psf[shape].data(), &bokehOp, &ms) == FUJI_VK_OK,
                    "Pupil bokeh dispatch failed");
        float energy = 0.0f;
        for (uint32_t i = 0; i < BW * BH; ++i) energy += psf[shape][i * 4 + 0];
        ASSERT_TRUE(energy > 0.1f, "Bokeh PSF energy must be positive");
    }
    auto samplePsf = [&](uint32_t shape, int dx, int dy) -> float {
        int px = static_cast<int>(bcx) + dx;
        int py = static_cast<int>(bcy) + dy;
        return psf[shape][static_cast<size_t>(py * static_cast<int>(BW) + px) * 4 + 0];
    };
    /* Compute actual CoC radius in pixels */
    const double sFocus = 550.0, sDepth = 20000.0, fMm = 110.0;
    const double cocMm = std::fabs(sDepth - sFocus) / sDepth * (fMm * fMm) / (1.2 * (sFocus - fMm));
    const double cocPx = std::min(24.0, 0.5 * cocMm * (static_cast<double>(BW) / 43.8));
    ASSERT_TRUE(cocPx >= 18.0, "Expected CoC >= 18px for crisp shaped bokeh self-test");

    // A hexagonal accessory has a feathered cutout edge. The same blade count on a bare lens
    // retains the original hard polygon aperture so selecting OFF restores the original image.
    bokehOp.pupilShape = FUJI_VK_PUPIL_POLYGON;
    bokehOp.flags = 3u | 8u;
    std::vector<float> hexCutout(BW * BH * 4);
    ASSERT_TRUE(fuji_vk_render_optics(ctx, bokehIn.data(), hexCutout.data(), &bokehOp, &ms) == FUJI_VK_OK,
                "External hexagonal pupil dispatch failed");
    double hexDifference = 0.0;
    for (uint32_t i = 0; i < BW * BH; ++i)
        hexDifference += std::fabs(hexCutout[i * 4] - psf[FUJI_VK_PUPIL_POLYGON][i * 4]);
    ASSERT_TRUE(hexDifference > 0.1, "A physical hex cutout must retain its own edge coverage");

    /* A 90-degree cutout rotation must rotate the physical point-spread function. Shrinking
       the cutout reduces its second moment while normalization preserves the point light. */
    for (uint32_t shape : {FUJI_VK_PUPIL_HEART, FUJI_VK_PUPIL_STAR_5}) {
        for (const uint32_t shrink : {0u, 8192u, 16383u}) {
            bokehOp.pupilShape = shape;
            bokehOp.flags = 3u | (4096u << 4) | (shrink << 18);
            std::vector<float> transformed(BW * BH * 4);
            ASSERT_TRUE(fuji_vk_render_optics(ctx, bokehIn.data(), transformed.data(), &bokehOp, &ms) == FUJI_VK_OK,
                        "Transformed pupil dispatch failed");
            double energy = 0, referenceEnergy = 0, moment = 0, referenceMoment = 0, rotatedError = 0;
            for (int y = -25; y <= 25; ++y) {
                for (int x = -25; x <= 25; ++x) {
                    const float weight = transformed[((bcy + y) * BW + bcx + x) * 4];
                    // The 2x2 energy-preserving pyramid places this impulse at (0.5, 0.5).
                    const float rotated = samplePsf(shape, y, 1 - x);
                    energy += weight;
                    referenceEnergy += rotated;
                    const double radiusSq = (x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5);
                    moment += weight * radiusSq;
                    referenceMoment += rotated * radiusSq;
                    rotatedError += std::fabs(weight - rotated);
                }
            }
            const double scale = 1.0 - 0.6 * shrink / 16383.0;
            std::printf("[test_vulkan_optics] pupil=%u scale=%.3f referenceEnergy=%.4f energy=%.4f momentRatio=%.5f rotatedL1=%.5f\n",
                        shape, scale, referenceEnergy, energy, moment / energy / (referenceMoment / referenceEnergy),
                        rotatedError / referenceEnergy);
            ASSERT_TRUE(std::fabs(energy / referenceEnergy - 1.0) < 0.04, "Cutout changed normalized light energy");
            ASSERT_TRUE(std::fabs(moment / energy / (referenceMoment / referenceEnergy) - scale * scale) < 0.035,
                        "Cutout scale did not change the footprint");
            if (shrink == 0u) {
                ASSERT_TRUE(rotatedError / referenceEnergy < 0.015, "Cutout did not rotate its lobes and points clockwise");
            }
        }
    }
    bokehOp.flags = 3u;

    /* RING: dark central obstruction (< 15% of outer ring peak) */
    const float ringCenter = samplePsf(FUJI_VK_PUPIL_RING, 0, 0);
    const float ringEdge = samplePsf(FUJI_VK_PUPIL_RING, static_cast<int>(std::lround(cocPx * 0.74)), 0);
    ASSERT_TRUE(ringEdge > 0.01f && ringCenter < ringEdge * 0.15f, "RING pupil must have dark center hole (<0.15 of outer ring)");

    /* HEART: upright orientation -> top-center cleft < left/right upper lobes, pointed bottom tip > bottom flanks */
    const int cleftY = -static_cast<int>(std::lround(cocPx * 0.78));
    const int lobeY = -static_cast<int>(std::lround(cocPx * 0.68));
    const int lobeX = static_cast<int>(std::lround(cocPx * 0.44));
    const int tipY = static_cast<int>(std::lround(cocPx * 0.74));
    const int flankX = static_cast<int>(std::lround(cocPx * 0.38));
    const float heartCleft = samplePsf(FUJI_VK_PUPIL_HEART, 0, cleftY);
    const float heartLeftLobe = samplePsf(FUJI_VK_PUPIL_HEART, -lobeX, lobeY);
    const float heartRightLobe = samplePsf(FUJI_VK_PUPIL_HEART, lobeX, lobeY);
    const float heartTip = samplePsf(FUJI_VK_PUPIL_HEART, 0, tipY);
    const float heartFlankL = samplePsf(FUJI_VK_PUPIL_HEART, -flankX, tipY);
    const float heartFlankR = samplePsf(FUJI_VK_PUPIL_HEART, flankX, tipY);
    ASSERT_TRUE(heartLeftLobe > 0.01f && heartRightLobe > 0.01f && heartCleft < heartLeftLobe * 0.25f,
                "HEART must be upright with clear top-center cleft notch below upper lobes");
    ASSERT_TRUE(heartTip > 0.01f && heartFlankL < heartTip * 0.25f && heartFlankR < heartTip * 0.25f,
                "HEART must have pointed bottom tip brighter than bottom flanks");

    /* STAR_5: 5 distinct sharp peaks vs 5 deep valleys (ratio >= 2.0x, tip #0 pointing UP) */
    const double starSampleR = cocPx * 0.64;
    for (int k = 0; k < 5; ++k) {
        const double tipA = -3.141592653589793 * 0.5 + k * (2.0 * 3.141592653589793 / 5.0);
        const double valA = tipA + 3.141592653589793 / 5.0;
        const float tipV = samplePsf(FUJI_VK_PUPIL_STAR_5,
                                     static_cast<int>(std::lround(starSampleR * std::cos(tipA))),
                                     static_cast<int>(std::lround(starSampleR * std::sin(tipA))));
        const float valV = samplePsf(FUJI_VK_PUPIL_STAR_5,
                                     static_cast<int>(std::lround(starSampleR * std::cos(valA))),
                                     static_cast<int>(std::lround(starSampleR * std::sin(valA))));
        ASSERT_TRUE(tipV > 0.01f && tipV >= valV * 2.0f + 0.01f, "STAR_5 peak must exceed valley by >= 2.0x");
    }

    /* HEXAGON (POLYGON bladeCount=6, roundness=0): corner bright vs flat-edge outside dark */
    const float hexCorner = samplePsf(FUJI_VK_PUPIL_POLYGON, 0, -static_cast<int>(std::lround(cocPx * 0.92)));
    const float hexFlatOut = samplePsf(FUJI_VK_PUPIL_POLYGON, static_cast<int>(std::lround(cocPx * 0.92)), 0);
    ASSERT_TRUE(hexCorner > 0.01f && hexFlatOut < hexCorner * 0.25f, "HEXAGON must have sharp corners and flat sides");

    double starVsCircleDiff = 0.0;
    double heartVsCircleDiff = 0.0;
    for (uint32_t i = 0; i < BW * BH; ++i) {
        starVsCircleDiff += std::fabs(psf[FUJI_VK_PUPIL_STAR_5][i * 4 + 0] - psf[FUJI_VK_PUPIL_CIRCLE][i * 4 + 0]);
        heartVsCircleDiff += std::fabs(psf[FUJI_VK_PUPIL_HEART][i * 4 + 0] - psf[FUJI_VK_PUPIL_CIRCLE][i * 4 + 0]);
    }
    ASSERT_TRUE(starVsCircleDiff > 0.5, "STAR_5 pupil must produce distinct PSF from CIRCLE");
    ASSERT_TRUE(heartVsCircleDiff > 0.5, "HEART pupil must produce distinct PSF from CIRCLE");
    std::printf("[test_vulkan_optics] 2D Pupil Bokeh 5 shapes verified (coc=%.1fpx, starDiff=%.2f, heartDiff=%.2f, heartLobe=%.3f/cleft=%.3f/tip=%.3f, ringCenter=%.4f, %.2f ms)\n",
                cocPx, starVsCircleDiff, heartVsCircleDiff, heartLeftLobe, heartCleft, heartTip, ringCenter, ms);

    /* 3C. Stateless SplitMix64 CMOS Noise + RGGB Bayer Readout */
    for (uint32_t i = 0; i < W * H; ++i) {
        inImg[i * 4 + 0] = 0.50f;
        inImg[i * 4 + 1] = 0.25f;
        inImg[i * 4 + 2] = 0.75f;
        inImg[i * 4 + 3] = 2.0f;
    }
    FujiVkCmosParams cmos{};
    cmos.width = W;
    cmos.height = H;
    cmos.bayerPattern = FUJI_VK_BAYER_RGGB;
    cmos.bitDepth = 14;
    cmos.maxCode = 16383;
    cmos.blackLevel = 256;
    cmos.enableNoise = 0;
    cmos.iso = 100;
    cmos.baseIso = 100.0f;
    cmos.fullWellElectrons = 65000.0f;
    cmos.opticalExposureScale = 1.0f;
    cmos.readNoiseBaseElectrons = 3.5f;
    cmos.readNoiseIsoSlope = 0.015f;
    cmos.seedLo = 0x12345678u;
    cmos.seedHi = 0x9ABCDEF0u;
    cmos.frameIndexLo = 1u;

    std::vector<uint8_t> bayerClean(W * H * 2, 0);
    ASSERT_TRUE(fuji_vk_render_cmos_bayer(ctx, inImg.data(), bayerClean.data(), bayerClean.size(), &cmos, &ms) == FUJI_VK_OK,
                "Noiseless CMOS Bayer dispatch failed");
    auto readU16 = [&](uint32_t x, uint32_t y) -> uint16_t {
        size_t o = (static_cast<size_t>(y) * W + x) * 2;
        return static_cast<uint16_t>(bayerClean[o] | (bayerClean[o + 1] << 8));
    };
    const int expR = static_cast<int>(std::lround(256.0 + 0.50 * (16383 - 256)));
    const int expG = static_cast<int>(std::lround(256.0 + 0.25 * (16383 - 256)));
    const int expB = static_cast<int>(std::lround(256.0 + 0.75 * (16383 - 256)));
    ASSERT_TRUE(std::abs(static_cast<int>(readU16(0, 0)) - expR) <= 1, "RGGB (0,0) R mismatch");
    ASSERT_TRUE(std::abs(static_cast<int>(readU16(1, 0)) - expG) <= 1, "RGGB (1,0) G mismatch");
    ASSERT_TRUE(std::abs(static_cast<int>(readU16(0, 1)) - expG) <= 1, "RGGB (0,1) G mismatch");
    ASSERT_TRUE(std::abs(static_cast<int>(readU16(1, 1)) - expB) <= 1, "RGGB (1,1) B mismatch");

    /* Noisy SplitMix64 mode: deterministic across identical seed/frame, different across frames, positive std-dev */
    cmos.enableNoise = 1;
    cmos.iso = 3200;
    cmos.opticalExposureScale = 0.015f; /* Keep mid-gray unclipped (~50% ADU) at ISO 3200 */
    std::vector<uint8_t> bayerN1(W * H * 2, 0), bayerN2(W * H * 2, 0), bayerN3(W * H * 2, 0);
    ASSERT_TRUE(fuji_vk_render_cmos_bayer(ctx, inImg.data(), bayerN1.data(), bayerN1.size(), &cmos, &ms) == FUJI_VK_OK, "Noisy pass 1 failed");
    ASSERT_TRUE(fuji_vk_render_cmos_bayer(ctx, inImg.data(), bayerN2.data(), bayerN2.size(), &cmos, &ms) == FUJI_VK_OK, "Noisy pass 2 failed");
    ASSERT_TRUE(bayerN1 == bayerN2, "SplitMix64 GPU noise must be 100% deterministic for same seed/frame");
    cmos.frameIndexLo = 2u;
    ASSERT_TRUE(fuji_vk_render_cmos_bayer(ctx, inImg.data(), bayerN3.data(), bayerN3.size(), &cmos, &ms) == FUJI_VK_OK, "Noisy pass 3 failed");
    ASSERT_TRUE(bayerN1 != bayerN3, "SplitMix64 GPU noise must vary across frameIndex");

    /* Chained single-submit Optics -> CMOS Bayer */
    cmos.enableNoise = 0;
    cmos.iso = 100;
    cmos.opticalExposureScale = 1.0f;
    auto idOp = make_default_optics(W, H);
    idOp.borderClamp = 1;
    idOp.imageCircleMm = 120.0f; // Cover the full square fixture for the identity comparison.
    std::vector<uint8_t> bayerChained(W * H * 2, 0);
    ASSERT_TRUE(fuji_vk_render_optics_to_bayer(ctx, inImg.data(), bayerChained.data(), bayerChained.size(), &idOp, &cmos, &ms) == FUJI_VK_OK,
                "Chained optics_to_bayer failed");
    int maxAduDiff = 0;
    for (size_t i = 0; i < static_cast<size_t>(W) * H; ++i) {
        int a = bayerClean[i * 2] | (bayerClean[i * 2 + 1] << 8);
        int b = bayerChained[i * 2] | (bayerChained[i * 2 + 1] << 8);
        int d = std::abs(a - b);
        if (d > maxAduDiff) maxAduDiff = d;
    }
    ASSERT_TRUE(maxAduDiff <= 1, "Chained identity optics_to_bayer must match direct CMOS Bayer within <=1 ADU");

    /* 3D. Pass 0 Optical Filter Stack (ND, GND, Black Mist, Star 4/6/8-pt, zero-overhead bypass) */
    {
        std::vector<float> flatIn(W * H * 4, 0.0f);
        for (uint32_t i = 0; i < W * H; ++i) {
            flatIn[i * 4 + 0] = 0.8f;
            flatIn[i * 4 + 1] = 0.8f;
            flatIn[i * 4 + 2] = 0.8f;
            flatIn[i * 4 + 3] = 3.5f;
        }
        std::vector<float> outBypass(W * H * 4, 0.0f);
        std::vector<float> outNd(W * H * 4, 0.0f);
        std::vector<float> outGnd(W * H * 4, 0.0f);

        FujiVkFilterStackParams fBypass{};
        fBypass.width = W;
        fBypass.height = H;
        fBypass.enableFlags = 0u;
        ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, flatIn.data(), outBypass.data(), &fBypass, &idOp, &ms) == FUJI_VK_OK,
                    "Filter bypass dispatch failed");
        ASSERT_TRUE(std::fabs(outBypass[centerIdx * 4 + 0] - 0.8f) < 1e-4f, "Filter bypass must preserve radiance");

        /* ND 2-stop (0.25x transmission) */
        FujiVkFilterStackParams fNd{};
        fNd.width = W;
        fNd.height = H;
        fNd.enableFlags = FUJI_VK_FILTER_ND;
        fNd.ndTransmission = 0.25f;
        ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, flatIn.data(), outNd.data(), &fNd, &idOp, &ms) == FUJI_VK_OK,
                    "ND filter dispatch failed");
        ASSERT_TRUE(std::fabs(outNd[centerIdx * 4 + 0] - 0.20f) < 1e-3f, "ND 2-stop must attenuate 0.8 -> 0.2");
        ASSERT_TRUE(std::fabs(outNd[centerIdx * 4 + 3] - 3.5f) < 1e-4f, "Pass 0 must preserve depth channel");

        /* Graduated ND (top dark 0.125x, bottom clear 1.0x) */
        FujiVkFilterStackParams fGnd{};
        fGnd.width = W;
        fGnd.height = H;
        fGnd.enableFlags = FUJI_VK_FILTER_GND;
        fGnd.gndTopTransmission = 0.125f;
        fGnd.gndHorizonY = 0.5f;
        fGnd.gndFeather = 0.10f;
        fGnd.gndSinAngle = 0.0f;
        fGnd.gndCosAngle = 1.0f;
        ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, flatIn.data(), outGnd.data(), &fGnd, &idOp, &ms) == FUJI_VK_OK,
                    "GND filter dispatch failed");
        const uint32_t topIdx = 2 * W + (W / 2);
        const uint32_t botIdx = (H - 3) * W + (W / 2);
        ASSERT_TRUE(std::fabs(outGnd[topIdx * 4 + 0] - 0.10f) < 0.02f, "GND top sky must darken to ~0.10");
        ASSERT_TRUE(std::fabs(outGnd[botIdx * 4 + 0] - 0.80f) < 0.02f, "GND bottom foreground must remain ~0.80");

        /* Point light for Black Mist halation & Star Cross-Screen 4/6/8-point spikes */
        std::vector<float> starIn(W * H * 4, 0.0f);
        for (uint32_t i = 0; i < W * H; ++i) starIn[i * 4 + 3] = 2.0f;
        starIn[centerIdx * 4 + 0] = 20.0f;
        starIn[centerIdx * 4 + 1] = 20.0f;
        starIn[centerIdx * 4 + 2] = 20.0f;

        FujiVkFilterStackParams fMist{};
        fMist.width = W;
        fMist.height = H;
        fMist.enableFlags = FUJI_VK_FILTER_BLACK_MIST;
        fMist.mistStrength = 0.6f;
        fMist.mistThreshold = 0.8f;
        fMist.mistRadiusPx = 6.0f;
        fMist.mistWarmth = 0.15f;
        std::vector<float> outMist(W * H * 4, 0.0f);
        ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, starIn.data(), outMist.data(), &fMist, &idOp, &ms) == FUJI_VK_OK,
                    "Black Mist dispatch failed");
        const uint32_t haloIdx = (H / 2) * W + (W / 2 + 6);
        ASSERT_TRUE(outMist[haloIdx * 4 + 0] > 0.05f, "Black Mist must produce highlight halation ring");
        ASSERT_TRUE(outMist[haloIdx * 4 + 0] > outMist[haloIdx * 4 + 2], "Black Mist warmth must tilt R > B");

        auto runStar = [&](uint32_t pts) {
            FujiVkFilterStackParams fs{};
            fs.width = W;
            fs.height = H;
            fs.enableFlags = FUJI_VK_FILTER_STAR_CROSS;
            fs.starPoints = pts;
            fs.starStrength = 1.0f;
            fs.starThreshold = 0.8f;
            fs.starRadiusPx = 12.0f;
            fs.starAngleRad = 0.0f;
            std::vector<float> out(W * H * 4, 0.0f);
            ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, starIn.data(), out.data(), &fs, &idOp, &ms) == FUJI_VK_OK,
                        "Star Cross-Screen dispatch failed");
            return out;
        };
        auto star4 = runStar(4u);
        auto star6 = runStar(6u);
        auto star8 = runStar(8u);
        const uint32_t horizSpike = (H / 2) * W + (W / 2 + 6);
        const uint32_t diag45Spike = (H / 2 + 4) * W + (W / 2 + 4);
        ASSERT_TRUE(star4[horizSpike * 4 + 0] > 0.05f, "4-pt Star must emit horizontal diffraction spike");
        ASSERT_TRUE(star8[diag45Spike * 4 + 0] > star4[diag45Spike * 4 + 0] + 0.02f, "8-pt Star must emit 45-deg spikes absent in 4-pt");
        double diff64 = 0.0;
        for (size_t i = 0; i < static_cast<size_t>(W) * H; ++i) {
            diff64 += std::fabs(star6[i * 4 + 0] - star4[i * 4 + 0]);
        }
        ASSERT_TRUE(diff64 > 0.5, "6-pt Star spike pattern must differ from 4-pt Star");
        std::printf("[test_vulkan_optics] Pass 0 Filter Stack PASSED (ND=%.2f, GND=%.2f/%.2f, MistHalo=%.3f, Star4/6/8 diff=%.2f, %.2f ms)\n",
                    outNd[centerIdx * 4 + 0], outGnd[topIdx * 4 + 0], outGnd[botIdx * 4 + 0], outMist[haloIdx * 4 + 0], diff64, ms);

        /* 3D-UV. Pass 0 UV Protective Filter: softness, point-symmetric ghosting, veiling glare, glass tint */
        std::vector<float> uvIn(W * H * 4, 0.0f);
        for (uint32_t y = 0; y < H; ++y) {
            for (uint32_t x = 0; x < W; ++x) {
                uint32_t i = y * W + x;
                /* Background mid-dark + sharp edge at x==W/2 + bright upper-left lamp at (12, 12) */
                float base = (x >= W / 2) ? 0.30f : 0.05f;
                uvIn[i * 4 + 0] = base;
                uvIn[i * 4 + 1] = base;
                uvIn[i * 4 + 2] = base;
                uvIn[i * 4 + 3] = 3.0f;
            }
        }
        for (int dy = -2; dy <= 2; ++dy) {
            for (int dx = -2; dx <= 2; ++dx) {
                uint32_t i = (12u + dy) * W + (12u + dx);
                uvIn[i * 4 + 0] = 6.0f;
                uvIn[i * 4 + 1] = 6.0f;
                uvIn[i * 4 + 2] = 6.0f;
            }
        }
        FujiVkFilterStackParams fUv{};
        fUv.width = W;
        fUv.height = H;
        fUv.enableFlags = FUJI_VK_FILTER_UV;
        fUv.uvVeilingGlare = 0.25f;
        fUv.uvGhostIntensity = 0.30f;
        fUv.uvSoftness = 0.45f;
        fUv.uvTintR = 0.96f;
        fUv.uvTintG = 1.04f;
        fUv.uvTintB = 0.94f;
        fUv.uvGhostRadiusPx = 3.0f;
        std::vector<float> outUv(W * H * 4, 0.0f);
        ASSERT_TRUE(fuji_vk_render_optics_filtered(ctx, uvIn.data(), outUv.data(), &fUv, &idOp, &ms) == FUJI_VK_OK,
                    "UV filter Pass 0 dispatch failed");
        /* Point-symmetric ghost at (W-1-12, H-1-12) must shine brighter than adjacent background, with green tint */
        const uint32_t ghostIdx = (H - 1u - 12u) * W + (W - 1u - 12u);
        const uint32_t refBgIdx = (H - 1u - 12u) * W + (W / 2u + 4u);
        ASSERT_TRUE(outUv[ghostIdx * 4 + 1] > outUv[refBgIdx * 4 + 1] + 0.15f,
                    "UV filter must create point-symmetric ghost across optical center");
        ASSERT_TRUE(outUv[ghostIdx * 4 + 1] > outUv[ghostIdx * 4 + 0],
                    "UV uncoated ghost + green tint must boost G over R");
        /* Veiling glare lifts deep shadows at (4, H-4) above original 0.05 */
        const uint32_t shadowIdx = (H - 4u) * W + 4u;
        ASSERT_TRUE(outUv[shadowIdx * 4 + 1] > 0.055f, "UV veiling glare must lift deep shadows");
        /* Softness blends sharp vertical edge at (W/2 - 1, H/2) upward from dark side */
        const uint32_t edgeDarkIdx = (H / 2u) * W + (W / 2u - 1u);
        ASSERT_TRUE(outUv[edgeDarkIdx * 4 + 0] > outUv[shadowIdx * 4 + 0] + 0.02f,
                    "UV micro-softness must blur sharp high-contrast edge");
        std::printf("[test_vulkan_optics] Pass 0 UV Filter PASSED (ghostG=%.3f vs bgG=%.3f, shadow=%.3f, edge=%.3f, %.2f ms)\n",
                    outUv[ghostIdx * 4 + 1], outUv[refBgIdx * 4 + 1], outUv[shadowIdx * 4 + 1], outUv[edgeDarkIdx * 4 + 0], ms);
    }

    /* 3E. Phase 3 LiveView ISP Demosaic + CCM + 1D Tone LUT + Focus Peaking (UYVY422/NV16/YYUV422) */
    {
        const uint32_t iw = 32, ih = 32;
        std::vector<uint8_t> raw16(iw * ih * 2, 0);
        for (uint32_t y = 0; y < ih; ++y) {
            for (uint32_t x = 0; x < iw; ++x) {
                /* High-contrast vertical edge at x=16 (left dark, right warm bright) */
                uint16_t v12 = (x < 16) ? 160u : (((y & 1u) == 0 && (x & 1u) == 0) ? 3400u : 2200u);
                uint16_t v16 = static_cast<uint16_t>(v12 << 4);
                size_t o = (static_cast<size_t>(y) * iw + x) * 2;
                raw16[o] = static_cast<uint8_t>(v16 & 0xFFu);
                raw16[o + 1] = static_cast<uint8_t>((v16 >> 8) & 0xFFu);
            }
        }
        FujiVkIspDemosaicParams ip{};
        ip.srcWidth = iw;
        ip.srcHeight = ih;
        ip.dstWidth = iw;
        ip.dstHeight = ih;
        ip.is16Bit = 1u;
        ip.outputFormat = FUJI_VK_ISP_FMT_UYVY422;
        ip.gainR1024 = 1024u;
        ip.gainG1024 = 1024u;
        ip.gainB1024 = 1024u;
        ip.yuvShift = 10u;

        std::vector<uint8_t> uyvy(iw * ih * 2, 0);
        ASSERT_TRUE(fuji_vk_isp_demosaic(ctx, raw16.data(), raw16.size(), nullptr, nullptr, nullptr,
                                         uyvy.data(), uyvy.size(), nullptr, 0, &ip, &ms) == FUJI_VK_OK,
                    "ISP UYVY422 demosaic failed");
        const uint8_t yLeft = uyvy[(16 * iw + 4) * 2 + 1];
        const uint8_t yRight = uyvy[(16 * iw + 24) * 2 + 1];
        ASSERT_TRUE(yRight > yLeft + 100, "ISP UYVY422 must distinguish dark left vs bright right");

        /* Semi-planar NV16 must match UYVY422 bit-for-bit */
        ip.outputFormat = FUJI_VK_ISP_FMT_NV16;
        std::vector<uint8_t> yPlane(iw * ih, 0), uvPlane(iw * ih, 0);
        ASSERT_TRUE(fuji_vk_isp_demosaic(ctx, raw16.data(), raw16.size(), nullptr, nullptr, nullptr,
                                         yPlane.data(), yPlane.size(), uvPlane.data(), uvPlane.size(), &ip, &ms) == FUJI_VK_OK,
                    "ISP NV16 demosaic failed");
        ASSERT_TRUE(yPlane[16 * iw + 4] == yLeft && yPlane[16 * iw + 24] == yRight, "NV16 Y must match UYVY422 bit-for-bit");

        /* Face/Eye-AF packed YYUV422 must match UYVY422 bit-for-bit */
        ip.outputFormat = FUJI_VK_ISP_FMT_YYUV422;
        std::vector<uint8_t> yyuv(iw * ih * 2, 0);
        ASSERT_TRUE(fuji_vk_isp_demosaic(ctx, raw16.data(), raw16.size(), nullptr, nullptr, nullptr,
                                         yyuv.data(), yyuv.size(), nullptr, 0, &ip, &ms) == FUJI_VK_OK,
                    "ISP YYUV422 demosaic failed");
        ASSERT_TRUE(yyuv[(16 * iw + 4) * 2 + 0] == yLeft && yyuv[(16 * iw + 24) * 2 + 0] == yRight, "YYUV422 Y0 must match UYVY422");

        /* Focus Peaking (RED: hiY=76, hiCb=0x55, hiCr=0xFF) must stamp the x=16 edge */
        ip.outputFormat = FUJI_VK_ISP_FMT_UYVY422;
        ip.flags = FUJI_VK_ISP_FLAG_PEAKING_ENABLED;
        ip.peakingThreshold = 60u;
        ip.peakingHiY = 76u;
        ip.peakingHiCb = 0x55u;
        ip.peakingHiCr = 0xFFu;
        std::vector<uint8_t> uyvyPeak(iw * ih * 2, 0);
        ASSERT_TRUE(fuji_vk_isp_demosaic(ctx, raw16.data(), raw16.size(), nullptr, nullptr, nullptr,
                                         uyvyPeak.data(), uyvyPeak.size(), nullptr, 0, &ip, &ms) == FUJI_VK_OK,
                    "ISP Focus Peaking dispatch failed");
        const size_t edgeOff = (16 * iw + 16) * 2;
        ASSERT_TRUE(uyvyPeak[edgeOff + 0] == 0x55u && uyvyPeak[edgeOff + 2] == 0xFFu && uyvyPeak[edgeOff + 1] == 76u,
                    "ISP Focus Peaking must stamp RED highlight on sharp vertical edge");
        ASSERT_TRUE(uyvyPeak[(16 * iw + 4) * 2 + 1] == yLeft, "ISP Focus Peaking must leave flat regions untouched");
        std::printf("[test_vulkan_optics] Phase 3 ISP Demosaic + Peaking PASSED (yL=%u, yR=%u, edgeStamped=RED, %.2f ms)\n",
                    yLeft, yRight, ms);
    }

    /* 3F. Fused GPU-Resident Optics -> CMOS Bayer -> LiveView ISP Demosaic [+ Peaking] (Zero Bayer Readback) */
    {
        const uint32_t fw = 64, fh = 64;
        std::vector<float> fIn(fw * fh * 4, 0.0f);
        for (uint32_t y = 0; y < fh; ++y) {
            for (uint32_t x = 0; x < fw; ++x) {
                size_t i = static_cast<size_t>(y) * fw + x;
                bool rightHalf = (x >= fw / 2);
                fIn[i * 4 + 0] = rightHalf ? 0.78f : 0.08f;
                fIn[i * 4 + 1] = rightHalf ? 0.52f : 0.09f;
                fIn[i * 4 + 2] = rightHalf ? 0.28f : 0.14f;
                fIn[i * 4 + 3] = 2.0f;
            }
        }
        FujiVkFilterStackParams fGnd{};
        fGnd.width = fw;
        fGnd.height = fh;
        fGnd.enableFlags = FUJI_VK_FILTER_GND;
        fGnd.gndTopTransmission = 0.25f;
        fGnd.gndHorizonY = 0.5f;
        fGnd.gndFeather = 0.12f;
        fGnd.gndSinAngle = 0.0f;
        fGnd.gndCosAngle = 1.0f;

        auto fOp = make_default_optics(fw, fh);
        fOp.cos4Power = 2.0f;
        fOp.mechVignetting = 0.15f;
        fOp.borderClamp = 1u;

        FujiVkCmosParams fCmos{};
        fCmos.width = fw;
        fCmos.height = fh;
        fCmos.bayerPattern = FUJI_VK_BAYER_RGGB;
        fCmos.bitDepth = 16;
        fCmos.maxCode = 65535;
        fCmos.blackLevel = 256;
        fCmos.enableNoise = 0;
        fCmos.iso = 100;
        fCmos.baseIso = 100.0f;
        fCmos.fullWellElectrons = 65000.0f;
        fCmos.opticalExposureScale = 1.0f;
        fCmos.readNoiseBaseElectrons = 3.5f;
        fCmos.readNoiseIsoSlope = 0.015f;

        FujiVkIspDemosaicParams fIsp{};
        fIsp.srcWidth = fw;
        fIsp.srcHeight = fh;
        fIsp.dstWidth = fw;
        fIsp.dstHeight = fh;
        fIsp.is16Bit = 1u;
        fIsp.outputFormat = FUJI_VK_ISP_FMT_UYVY422;
        fIsp.flags = FUJI_VK_ISP_FLAG_HAS_CCM | FUJI_VK_ISP_FLAG_PEAKING_ENABLED;
        fIsp.gainR1024 = 1088u;
        fIsp.gainG1024 = 1024u;
        fIsp.gainB1024 = 960u;
        fIsp.yuvShift = 10u;
        const int32_t ccm[9] = {1126, -51, -51, -30, 1084, -30, -20, -40, 1084};
        std::memcpy(fIsp.ccm, ccm, sizeof(ccm));
        fIsp.peakingThreshold = 45u;
        fIsp.peakingHiY = 76u;
        fIsp.peakingHiCb = 0x55u;
        fIsp.peakingHiCr = 0xFFu;

        /* Unfused reference: Pass 0..3 -> host Bayer -> Pass 4..5 ISP */
        std::vector<uint8_t> refBayer(fw * fh * 2, 0);
        std::vector<uint8_t> refUyvy(fw * fh * 2, 0);
        double msUnfused1 = 0.0, msUnfused2 = 0.0, msFused = 0.0;
        ASSERT_TRUE(fuji_vk_render_optics_to_bayer_filtered(ctx, fIn.data(), refBayer.data(), refBayer.size(), &fGnd, &fOp, &fCmos, &msUnfused1) == FUJI_VK_OK,
                    "Unfused optics_to_bayer failed");
        ASSERT_TRUE(fuji_vk_isp_demosaic(ctx, refBayer.data(), refBayer.size(), nullptr, nullptr, nullptr,
                                         refUyvy.data(), refUyvy.size(), nullptr, 0, &fIsp, &msUnfused2) == FUJI_VK_OK,
                    "Unfused isp_demosaic failed");

        /* Fused single-submit GPU-resident Pass 0..5 -> UYVY422 */
        std::vector<uint8_t> fusedUyvy(fw * fh * 2, 0);
        ASSERT_TRUE(fuji_vk_render_optics_to_isp_yuv(ctx, fIn.data(), &fGnd, &fOp, &fCmos,
                                                     nullptr, nullptr, nullptr,
                                                     fusedUyvy.data(), fusedUyvy.size(), nullptr, 0, &fIsp, &msFused) == FUJI_VK_OK,
                    "Fused optics_to_isp_yuv UYVY422 failed");
        int maxDiff = 0;
        for (size_t i = 0; i < refUyvy.size(); ++i) {
            int d = std::abs(static_cast<int>(fusedUyvy[i]) - static_cast<int>(refUyvy[i]));
            if (d > maxDiff) maxDiff = d;
        }
        ASSERT_TRUE(maxDiff <= 1, "Fused GPU Optics->ISP UYVY422 must match unfused within <= 1 LSB");

        /* Verify NV16 semi-planar fused output matches UYVY422 bit-for-bit */
        fIsp.outputFormat = FUJI_VK_ISP_FMT_NV16;
        std::vector<uint8_t> fY(fw * fh, 0), fUv(fw * fh, 0);
        ASSERT_TRUE(fuji_vk_render_optics_to_isp_yuv(ctx, fIn.data(), &fGnd, &fOp, &fCmos,
                                                     nullptr, nullptr, nullptr,
                                                     fY.data(), fY.size(), fUv.data(), fUv.size(), &fIsp, &msFused) == FUJI_VK_OK,
                    "Fused optics_to_isp_yuv NV16 failed");
        for (uint32_t y = 0; y < fh; ++y) {
            for (uint32_t x = 0; x < fw; x += 2) {
                size_t uo = (static_cast<size_t>(y) * fw + x) * 2;
                ASSERT_TRUE(fY[y * fw + x] == fusedUyvy[uo + 1], "Fused NV16 Y0 mismatch");
                ASSERT_TRUE(fY[y * fw + x + 1] == fusedUyvy[uo + 3], "Fused NV16 Y1 mismatch");
                ASSERT_TRUE(fUv[y * fw + x] == fusedUyvy[uo + 0], "Fused NV16 U mismatch");
                ASSERT_TRUE(fUv[y * fw + x + 1] == fusedUyvy[uo + 2], "Fused NV16 V mismatch");
            }
        }
        std::printf("[test_vulkan_optics] Fused GPU Optics->ISP YUV PASSED (maxDiff=%d LSB, unfused=%.2f ms, fused=%.2f ms)\n",
                    maxDiff, msUnfused1 + msUnfused2, msFused);

        /* 3e. GPU-Resident VRAM Scene Cache parity across focus sweeps (AF-ON zero-upload path) */
        const uint64_t sceneKey = 0xAF01CAFE12345678ULL;
        double uploadMs1 = 0.0, uploadMs2 = 0.0, cachedBayerMs = 0.0, cachedYuvMs = 0.0;
        ASSERT_TRUE(fuji_vk_upload_scene(ctx, sceneKey, fIn.data(), static_cast<size_t>(fw) * fh, &uploadMs1) == FUJI_VK_OK,
                    "fuji_vk_upload_scene initial upload failed");
        ASSERT_TRUE(fuji_vk_upload_scene(ctx, sceneKey, fIn.data(), static_cast<size_t>(fw) * fh, &uploadMs2) == FUJI_VK_OK,
                    "fuji_vk_upload_scene cache-hit check failed");
        ASSERT_TRUE(uploadMs2 == 0.0, "Second upload with identical sceneKey must be a 0.0ms cache hit");

        /* Render Bayer at a swept focus distance from VRAM cache vs direct upload -> must match bit-for-bit */
        FujiVkOpticsParams sweepOp = fOp;
        sweepOp.focusDistanceM = 3.5f;
        std::vector<uint8_t> directSweepBayer(fw * fh * 2, 0);
        std::vector<uint8_t> cachedSweepBayer(fw * fh * 2, 0);
        double directSweepMs = 0.0;
        ASSERT_TRUE(fuji_vk_render_optics_to_bayer_filtered(ctx, fIn.data(), directSweepBayer.data(), directSweepBayer.size(), &fGnd, &sweepOp, &fCmos, &directSweepMs) == FUJI_VK_OK,
                    "Direct sweep optics_to_bayer failed");
        ASSERT_TRUE(fuji_vk_render_cached_scene_to_bayer(ctx, sceneKey, cachedSweepBayer.data(), cachedSweepBayer.size(), &fGnd, &sweepOp, &fCmos, &cachedBayerMs) == FUJI_VK_OK,
                    "Cached VRAM scene optics_to_bayer failed");
        ASSERT_TRUE(directSweepBayer == cachedSweepBayer, "Cached VRAM scene Bayer must match direct upload bit-for-bit");

        /* Render fused ISP UYVY422 from VRAM cache -> must match fusedUyvy bit-for-bit */
        fIsp.outputFormat = FUJI_VK_ISP_FMT_UYVY422;
        std::vector<uint8_t> cachedUyvy(fw * fh * 2, 0);
        ASSERT_TRUE(fuji_vk_render_cached_scene_to_isp_yuv(ctx, sceneKey, &fGnd, &fOp, &fCmos,
                                                           nullptr, nullptr, nullptr,
                                                           cachedUyvy.data(), cachedUyvy.size(), nullptr, 0, &fIsp, &cachedYuvMs) == FUJI_VK_OK,
                    "Cached VRAM scene optics_to_isp_yuv failed");
        ASSERT_TRUE(cachedUyvy == fusedUyvy, "Cached VRAM scene fused UYVY422 must match direct upload bit-for-bit");
        std::printf("[test_vulkan_optics] GPU-Resident VRAM Scene Cache PASSED (0 LSB diff, upload=%.2f ms, hit=%.2f ms, cachedBayer=%.2f ms, cachedYuv=%.2f ms)\n",
                    uploadMs1, uploadMs2, cachedBayerMs, cachedYuvMs);
    }

    /* 4. External GPU frame descriptor & import validation */
    FujiVkExternalFrameDesc badDesc{};
    FujiVkExternalFrame* extFrame = nullptr;
    ASSERT_TRUE(fuji_vk_validate_external_frame_desc(ctx, &badDesc) == FUJI_VK_ERR_INVALID_ARG,
                "Empty external frame desc must fail validation");
    ASSERT_TRUE(fuji_vk_import_external_frame(ctx, &badDesc, &extFrame) == FUJI_VK_ERR_INVALID_ARG,
                "Empty external frame import must fail validation");
    ASSERT_TRUE(extFrame == nullptr, "Failed external import must leave out_frame null");

    FujiVkExternalFrameDesc validFdDesc{};
    validFdDesc.handleType = FUJI_VK_EXT_HANDLE_DMA_BUF_FD;
    validFdDesc.width = W;
    validFdDesc.height = H;
    validFdDesc.colorVkFormat = 109u; /* RGBA32_SFLOAT */
    validFdDesc.depthVkFormat = 126u; /* D32_SFLOAT */
    validFdDesc.colorHandle = 3u;
    validFdDesc.colorMemorySizeBytes = W * H * 16ULL;
    validFdDesc.depthHandle = 4u;
    validFdDesc.depthMemorySizeBytes = W * H * 4ULL;
    int32_t fdValRc = fuji_vk_validate_external_frame_desc(ctx, &validFdDesc);
    if ((info.externalMemoryCaps & (FUJI_VK_EXT_CAP_MEMORY_FD | FUJI_VK_EXT_CAP_DMA_BUF)) ==
        (FUJI_VK_EXT_CAP_MEMORY_FD | FUJI_VK_EXT_CAP_DMA_BUF)) {
        ASSERT_TRUE(fdValRc == FUJI_VK_OK, "Valid DMA-BUF descriptor must pass validation on DMA-BUF-capable device");
    } else {
        ASSERT_TRUE(fdValRc == FUJI_VK_ERR_UNSUPPORTED_EXT, "DMA-BUF descriptor must return UNSUPPORTED_EXT without ext");
    }

    fuji_vk_destroy(ctx);

    /* 5. Modern Engine Pipeline Verification:
     *    (a) Cold vs Warm disk-persistent VkPipelineCache + corruption & UUID mismatch auto-recovery
     *    (b) Specialized pipeline bit-exact parity (Warp CA/Vig off/on, Bokeh shapes, ISP modes)
     *    (c) Ring-2 double-buffered async submit/collect (0 LSB diff vs sync)
     *    (d) High-water-mark zero-reallocation across 1024x576 <-> 1280x960 resolution switches */
    {
        char errBuf[512] = {};
        const char* cachePath = "/tmp/fuji_vk_test_pipeline_cache.bin";
        std::remove(cachePath);

        /* 5a-1: Cold start -> miss, writes validated FVPC cache file to disk */
        FujiVkContext* cCold = nullptr;
        FujiVkDeviceInfo ci{};
        ASSERT_TRUE(fuji_vk_create_with_cache(-1, FUJI_VK_SELECT_DEFAULT | FUJI_VK_SELECT_ALLOW_SOFTWARE,
                                              cachePath, &cCold, &ci, errBuf, sizeof(errBuf)) == FUJI_VK_OK,
                    "fuji_vk_create_with_cache cold failed");
        FujiVkPipelineStats stCold{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cCold, &stCold) == FUJI_VK_OK, "get_pipeline_stats cold failed");
        ASSERT_TRUE(stCold.pipelineCacheHit == 0u, "Cold start must report pipelineCacheHit == 0");
        ASSERT_TRUE(stCold.pipelineCacheBytes > 0u, "Cold start must serialize non-empty VkPipelineCache");
        ASSERT_TRUE(stCold.specializedPipelineCount >= 5u, "Pre-warm must compile default specialized pipelines");
        fuji_vk_destroy(cCold);

        /* 5a-2: Warm start -> hit from validated disk FVPC cache */
        FujiVkContext* cWarm = nullptr;
        ASSERT_TRUE(fuji_vk_create_with_cache(-1, FUJI_VK_SELECT_DEFAULT | FUJI_VK_SELECT_ALLOW_SOFTWARE,
                                              cachePath, &cWarm, &ci, errBuf, sizeof(errBuf)) == FUJI_VK_OK,
                    "fuji_vk_create_with_cache warm failed");
        FujiVkPipelineStats stWarm{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cWarm, &stWarm) == FUJI_VK_OK, "get_pipeline_stats warm failed");
        ASSERT_TRUE(stWarm.pipelineCacheHit == 1u, "Warm start must report pipelineCacheHit == 1");
        ASSERT_TRUE(stWarm.pipelineCacheBytes > 0u, "Warm start must report pipelineCacheBytes > 0");
        fuji_vk_destroy(cWarm);

        /* 5a-3: Corrupt UUID byte inside FVPC header (offset 24 = pipelineCacheUUID[0]) -> clean auto-recovery */
        {
            std::FILE* f = std::fopen(cachePath, "r+b");
            ASSERT_TRUE(f != nullptr, "Expected cache file on disk for corruption test");
            ASSERT_TRUE(std::fseek(f, 24L, SEEK_SET) == 0, "fseek to UUID failed");
            int orig = std::fgetc(f);
            ASSERT_TRUE(orig != EOF, "fgetc UUID failed");
            std::fseek(f, 24L, SEEK_SET);
            std::fputc(orig ^ 0x5A, f);
            std::fclose(f);
        }
        FujiVkContext* cUuidMismatch = nullptr;
        ASSERT_TRUE(fuji_vk_create_with_cache(-1, FUJI_VK_SELECT_DEFAULT | FUJI_VK_SELECT_ALLOW_SOFTWARE,
                                              cachePath, &cUuidMismatch, &ci, errBuf, sizeof(errBuf)) == FUJI_VK_OK,
                    "UUID mismatch cache recovery failed");
        FujiVkPipelineStats stUuid{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cUuidMismatch, &stUuid) == FUJI_VK_OK, "get_pipeline_stats uuid failed");
        ASSERT_TRUE(stUuid.pipelineCacheHit == 0u, "UUID-mismatched cache must auto-invalidate (pipelineCacheHit == 0)");
        fuji_vk_destroy(cUuidMismatch);

        /* 5a-4: Truncated/corrupted payload -> clean auto-recovery & re-save */
        {
            std::FILE* f = std::fopen(cachePath, "wb");
            ASSERT_TRUE(f != nullptr, "Expected writable cache file for truncation test");
            const char junk[] = "FVPC_CORRUPTED_TRUNCATED";
            std::fwrite(junk, 1, sizeof(junk), f);
            std::fclose(f);
        }
        FujiVkContext* cWork = nullptr;
        ASSERT_TRUE(fuji_vk_create_with_cache(-1, FUJI_VK_SELECT_DEFAULT | FUJI_VK_SELECT_ALLOW_SOFTWARE,
                                              cachePath, &cWork, &ci, errBuf, sizeof(errBuf)) == FUJI_VK_OK,
                    "Corrupt cache recovery failed");
        FujiVkPipelineStats stCorrupt{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cWork, &stCorrupt) == FUJI_VK_OK, "get_pipeline_stats corrupt failed");
        ASSERT_TRUE(stCorrupt.pipelineCacheHit == 0u, "Corrupted cache must auto-invalidate cleanly");

        /* 5b: Specialized Warp (CA=off, Vig=off) vs (CA=on with ca=1.0, Vig=on with vig=0) parity */
        const uint32_t sw = 64u, sh = 48u;
        std::vector<float> sIn(sw * sh * 4u);
        for (uint32_t y = 0; y < sh; ++y) {
            for (uint32_t x = 0; x < sw; ++x) {
                size_t idx = (static_cast<size_t>(y) * sw + x) * 4u;
                sIn[idx + 0] = 0.2f + 0.6f * (static_cast<float>(x) / sw);
                sIn[idx + 1] = 0.15f + 0.5f * (static_cast<float>(y) / sh);
                sIn[idx + 2] = 0.35f;
                sIn[idx + 3] = (x > 16 && x < 48) ? 1.8f : 7.5f;
            }
        }
        FujiVkOpticsParams opNoCa{};
        opNoCa.srcWidth = sw; opNoCa.srcHeight = sh; opNoCa.dstWidth = sw; opNoCa.dstHeight = sh;
        opNoCa.caRedScale = 1.0f; opNoCa.caBlueScale = 1.0f;
        opNoCa.cos4Power = 0.0f; opNoCa.mechVignetting = 0.0f;
        opNoCa.focalLengthMm = 80.0f; opNoCa.sensorWidthMm = 43.8f;
        opNoCa.focusDistanceM = 1.8f; opNoCa.fNumber = 2.0f;
        opNoCa.invZoom = 1.0f; opNoCa.ibisCosRoll = 1.0f; opNoCa.maxBokehPx = 8.0f;
        opNoCa.planarDepthM = 2.0f; opNoCa.pupilShape = FUJI_VK_PUPIL_CIRCLE;
        opNoCa.bladeCount = 9u; opNoCa.borderClamp = 1u; opNoCa.flags = 3u;

        FujiVkOpticsParams opTinyCa = opNoCa;
        opTinyCa.caRedScale = 1.00002f; /* Forces SPEC_HAS_CA=1 variant */
        opTinyCa.cos4Power = 0.00002f;  /* Forces SPEC_HAS_VIG=1 variant */
        std::vector<float> outNoCa(sw * sh * 4u, 0.0f), outCa(sw * sh * 4u, 0.0f);
        ASSERT_TRUE(fuji_vk_render_optics(cWork, sIn.data(), outNoCa.data(), &opNoCa, nullptr) == FUJI_VK_OK, "Specialized warp/bokeh render failed");
        ASSERT_TRUE(fuji_vk_render_optics(cWork, sIn.data(), outCa.data(), &opTinyCa, nullptr) == FUJI_VK_OK, "Dynamic CA/Vig render failed");
        float maxSpecDiff = 0.0f;
        for (size_t i = 0; i < outNoCa.size(); ++i) {
            float d = std::fabs(outNoCa[i] - outCa[i]);
            if (d > maxSpecDiff) maxSpecDiff = d;
        }
        ASSERT_TRUE(maxSpecDiff < 1e-3f, "Specialized Warp (CA=0,Vig=0) must match full path within < 1e-3");

        /* 5c: Ring-2 Double-Buffered Async Submit/Collect (2 frames concurrently in-flight -> 0 LSB diff vs sync) */
        const uint64_t asyncSceneKey = 0x51A0'2222'3333'4444ULL;
        ASSERT_TRUE(fuji_vk_upload_scene(cWork, asyncSceneKey, sIn.data(), static_cast<size_t>(sw) * sh, nullptr) == FUJI_VK_OK,
                    "Async scene upload failed");
        FujiVkCmosParams aCmos{};
        aCmos.width = sw; aCmos.height = sh; aCmos.bayerPattern = FUJI_VK_BAYER_RGGB;
        aCmos.bitDepth = 14u; aCmos.maxCode = 16383u; aCmos.blackLevel = 256u;
        aCmos.enableNoise = 0u; aCmos.iso = 100u; aCmos.baseIso = 100.0f;
        aCmos.fullWellElectrons = 51000.0f; aCmos.opticalExposureScale = 1.0f;

        FujiVkOpticsParams opF0 = opNoCa; opF0.focusDistanceM = 1.6f; opF0.pupilShape = FUJI_VK_PUPIL_STAR_5;
        FujiVkOpticsParams opF1 = opNoCa; opF1.focusDistanceM = 4.2f; opF1.pupilShape = FUJI_VK_PUPIL_HEART;
        std::vector<uint8_t> syncB0(sw * sh * 2u, 0), syncB1(sw * sh * 2u, 0);
        ASSERT_TRUE(fuji_vk_render_cached_scene_to_bayer(cWork, asyncSceneKey, syncB0.data(), syncB0.size(), nullptr, &opF0, &aCmos, nullptr) == FUJI_VK_OK,
                    "Sync reference frame 0 failed");
        ASSERT_TRUE(fuji_vk_render_cached_scene_to_bayer(cWork, asyncSceneKey, syncB1.data(), syncB1.size(), nullptr, &opF1, &aCmos, nullptr) == FUJI_VK_OK,
                    "Sync reference frame 1 failed");

        uint64_t t0 = 0ULL, t1 = 0ULL;
        ASSERT_TRUE(fuji_vk_submit_async_to_bayer(cWork, asyncSceneKey, nullptr, nullptr, &opF0, &aCmos, &t0) == FUJI_VK_OK && t0 != 0ULL,
                    "Ring-2 async submit slot 0 failed");
        ASSERT_TRUE(fuji_vk_submit_async_to_bayer(cWork, asyncSceneKey, nullptr, nullptr, &opF1, &aCmos, &t1) == FUJI_VK_OK && t1 != 0ULL && t1 != t0,
                    "Ring-2 async submit slot 1 failed");
        std::vector<uint8_t> asyncB0(sw * sh * 2u, 0), asyncB1(sw * sh * 2u, 0);
        double aMs0 = 0.0, aMs1 = 0.0;
        ASSERT_TRUE(fuji_vk_collect_async_bayer(cWork, t0, asyncB0.data(), asyncB0.size(), &aMs0) == FUJI_VK_OK,
                    "Ring-2 async collect slot 0 failed");
        ASSERT_TRUE(fuji_vk_collect_async_bayer(cWork, t1, asyncB1.data(), asyncB1.size(), &aMs1) == FUJI_VK_OK,
                    "Ring-2 async collect slot 1 failed");
        ASSERT_TRUE(asyncB0 == syncB0, "Ring-2 async frame 0 must match sync bit-for-bit (0 LSB diff)");
        ASSERT_TRUE(asyncB1 == syncB1, "Ring-2 async frame 1 must match sync bit-for-bit (0 LSB diff)");

        /* 5d: High-Water-Mark Memory Pool: zero buffer reallocations across 1024x576 <-> 1280x960 switches */
        const uint32_t evfW = 1280u, evfH = 960u;
        const uint32_t lcdW = 1024u, lcdH = 576u;
        std::vector<float> evfIn(static_cast<size_t>(evfW) * evfH * 4u, 0.35f);
        std::vector<uint8_t> evfBayer(static_cast<size_t>(evfW) * evfH * 2u, 0);
        std::vector<uint8_t> lcdBayer(static_cast<size_t>(lcdW) * lcdH * 2u, 0);
        FujiVkOpticsParams opEvf = opNoCa;
        opEvf.srcWidth = evfW; opEvf.srcHeight = evfH; opEvf.dstWidth = evfW; opEvf.dstHeight = evfH; opEvf.flags = 0u;
        FujiVkCmosParams cmEvf = aCmos; cmEvf.width = evfW; cmEvf.height = evfH;
        FujiVkOpticsParams opLcd = opNoCa;
        opLcd.srcWidth = lcdW; opLcd.srcHeight = lcdH; opLcd.dstWidth = lcdW; opLcd.dstHeight = lcdH; opLcd.flags = 0u;
        FujiVkCmosParams cmLcd = aCmos; cmLcd.width = lcdW; cmLcd.height = lcdH;

        /* Establish high-water-mark at 1280x960 */
        ASSERT_TRUE(fuji_vk_render_optics_to_bayer(cWork, evfIn.data(), evfBayer.data(), evfBayer.size(), &opEvf, &cmEvf, nullptr) == FUJI_VK_OK,
                    "HWM initial 1280x960 render failed");
        FujiVkPipelineStats stHwm0{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cWork, &stHwm0) == FUJI_VK_OK, "get_pipeline_stats hwm0 failed");
        /* Switch repeatedly 1024x576 <-> 1280x960 -> ZERO Vulkan buffer reallocations! */
        for (int iter = 0; iter < 3; ++iter) {
            ASSERT_TRUE(fuji_vk_render_optics_to_bayer(cWork, evfIn.data(), lcdBayer.data(), lcdBayer.size(), &opLcd, &cmLcd, nullptr) == FUJI_VK_OK,
                        "HWM 1024x576 switch failed");
            ASSERT_TRUE(fuji_vk_render_optics_to_bayer(cWork, evfIn.data(), evfBayer.data(), evfBayer.size(), &opEvf, &cmEvf, nullptr) == FUJI_VK_OK,
                        "HWM 1280x960 switch failed");
        }
        FujiVkPipelineStats stHwm1{};
        ASSERT_TRUE(fuji_vk_get_pipeline_stats(cWork, &stHwm1) == FUJI_VK_OK, "get_pipeline_stats hwm1 failed");
        ASSERT_TRUE(stHwm1.bufferReallocCount == stHwm0.bufferReallocCount,
                    "High-water-mark memory pool must perform ZERO reallocations across 1024x576 <-> 1280x960 switches");
        ASSERT_TRUE(stHwm1.asyncFramesSubmitted == 2u && stHwm1.asyncFramesCollected == 2u,
                    "Async telemetry counters must reflect 2 submitted / 2 collected");

        std::printf("[test_vulkan_optics] Modern Engine Pipeline PASSED (cold=%.2f ms -> warm=%.2f ms, cacheBytes=%llu, specVariants=%u, hwmReallocs=%u->%u, hostCached=%u, ring2=%.2f/%.2f ms)\n",
                    stCold.pipelineWarmupMs, stWarm.pipelineWarmupMs,
                    static_cast<unsigned long long>(stWarm.pipelineCacheBytes),
                    stHwm1.specializedPipelineCount, stHwm0.bufferReallocCount, stHwm1.bufferReallocCount,
                    stHwm1.hostCachedReadback, aMs0, aMs1);
        fuji_vk_destroy(cWork);
        std::remove(cachePath);
    }

    /* Test 6: Large-radius Bokeh 2x2 pyramid benchmark (1024x576 rad=28) + DEVICE_LOCAL Post-Bokeh Stage-1 VRAM Cache (#1642 / #1645) */
    {
        FujiVkContext* c6 = nullptr;
        char err6[256] = {0};
        ASSERT_TRUE(fuji_vk_create(FUJI_VK_SELECT_ALLOW_SOFTWARE, &c6, nullptr, err6, sizeof(err6)) == FUJI_VK_OK && c6 != nullptr, "Test 6 fuji_vk_create failed");
        const uint32_t w = 1024u, h = 576u;
        const size_t nPx = static_cast<size_t>(w) * h;
        std::vector<float> scene(nPx * 4u, 0.0f);
        for (uint32_t y = 0; y < h; ++y) {
            for (uint32_t x = 0; x < w; ++x) {
                const size_t idx = (static_cast<size_t>(y) * w + x) * 4u;
                scene[idx + 0] = 0.15f + 0.35f * (static_cast<float>(x) / static_cast<float>(w));
                scene[idx + 1] = 0.20f + 0.30f * (static_cast<float>(y) / static_cast<float>(h));
                scene[idx + 2] = 0.25f;
                scene[idx + 3] = 20.0f; /* Far background -> forces maxBokehPx=28 large bokeh */
            }
        }
        /* Pinpoint specular highlight in center */
        const size_t cIdx = (static_cast<size_t>(h / 2u) * w + (w / 2u)) * 4u;
        scene[cIdx + 0] = 12.0f; scene[cIdx + 1] = 10.0f; scene[cIdx + 2] = 8.0f; scene[cIdx + 3] = 20.0f;

        const uint64_t sKey = 0xB0CE'1645'0000'0001ULL;
        ASSERT_TRUE(fuji_vk_upload_scene(c6, sKey, scene.data(), nPx, nullptr) == FUJI_VK_OK, "Test 6 upload_scene failed");

        FujiVkOpticsParams op{};
        op.srcWidth = w; op.srcHeight = h; op.dstWidth = w; op.dstHeight = h;
        op.caRedScale = 1.0f; op.caBlueScale = 1.0f;
        op.cos4Power = 0.0f; op.mechVignetting = 0.0f;
        op.focalLengthMm = 80.0f; op.sensorWidthMm = 43.8f;
        op.focusDistanceM = 0.6f; op.fNumber = 1.7f;
        op.invZoom = 1.0f; op.ibisCosRoll = 1.0f; op.maxBokehPx = 28.0f;
        op.planarDepthM = 20.0f; op.pupilShape = FUJI_VK_PUPIL_CIRCLE;
        op.bladeCount = 9u; op.borderClamp = 1u; op.flags = 3u;

        FujiVkCmosParams cmA{};
        cmA.width = w; cmA.height = h; cmA.bayerPattern = FUJI_VK_BAYER_RGGB;
        cmA.bitDepth = 14u; cmA.maxCode = 16383u; cmA.blackLevel = 256u;
        cmA.enableNoise = 0u; cmA.iso = 100u; cmA.baseIso = 100.0f;
        cmA.fullWellElectrons = 51000.0f; cmA.opticalExposureScale = 1.0f;

        FujiVkIspDemosaicParams ispA{};
        ispA.srcWidth = w; ispA.srcHeight = h; ispA.dstWidth = w; ispA.dstHeight = h;
        ispA.dstStrideBytes = w * 2u; ispA.outputFormat = FUJI_VK_ISP_FMT_UYVY422;
        ispA.is16Bit = 1u; ispA.flags = FUJI_VK_ISP_FLAG_HAS_CCM;
        ispA.gainR1024 = 1024u; ispA.gainG1024 = 1024u; ispA.gainB1024 = 1024u;
        ispA.yuvShift = 10u;
        ispA.ccm[0] = 1024; ispA.ccm[4] = 1024; ispA.ccm[8] = 1024;
        ispA.yuvMat[0] = 218;  ispA.yuvMat[1] = 732;  ispA.yuvMat[2] = 74;
        ispA.yuvMat[3] = -117; ispA.yuvMat[4] = -395; ispA.yuvMat[5] = 512;
        ispA.yuvMat[6] = 512;  ispA.yuvMat[7] = -465; ispA.yuvMat[8] = -47;

        std::vector<int32_t> lutA(4096), lutB(4096);
        for (int i = 0; i < 4096; ++i) {
            lutA[i] = static_cast<int32_t>(std::lround(std::pow(i / 4095.0, 1.0 / 2.2) * 255.0));
            lutB[i] = static_cast<int32_t>(std::lround(std::pow(i / 4095.0, 1.0 / 1.8) * 255.0));
        }

        std::vector<uint8_t> yuvCold(nPx * 2u, 0), yuvHit(nPx * 2u, 0), yuvRef(nPx * 2u, 0);
        double coldMs = 0.0, hitMs = 0.0, refMs = 0.0;

        /* Cold fused render (populates DEVICE_LOCAL Stage-1 post-bokeh bufOut at rad=28) */
        ASSERT_TRUE(
            fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
                c6, sKey, nullptr, &op, &cmA, lutA.data(), lutA.data(), lutA.data(),
                yuvCold.data(), yuvCold.size(), nullptr, 0, &ispA, nullptr, nullptr, &coldMs) == FUJI_VK_OK,
            "Test 6 cold rad=28 fused render failed");

        /* Change exposure (opticalExposureScale + ISO), WB gains, and FilmSim tone LUT while keeping (sceneKey, optics) fixed!
         * Must hit Stage-1 VRAM cache (skip Pass 0..2 bokeh completely) and match a cold Pass 0..6 render bit-for-bit (0 LSB diff). */
        FujiVkCmosParams cmB = cmA;
        cmB.opticalExposureScale = 1.65f;
        cmB.iso = 800u;
        FujiVkIspDemosaicParams ispB = ispA;
        ispB.gainR1024 = 1280;
        ispB.gainB1024 = 896;

        ASSERT_TRUE(
            fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
                c6, sKey, nullptr, &op, &cmB, lutB.data(), lutB.data(), lutB.data(),
                yuvHit.data(), yuvHit.size(), nullptr, 0, &ispB, nullptr, nullptr, &hitMs) == FUJI_VK_OK,
            "Test 6 Stage-1 VRAM cache hit render failed");

        /* Force Stage-1 invalidation by uploading under a fresh scene key and running full Pass 0..6 for bit-exact reference */
        const uint64_t sKeyRef = 0xB0CE'1645'0000'0002ULL;
        ASSERT_TRUE(fuji_vk_upload_scene(c6, sKeyRef, scene.data(), nPx, nullptr) == FUJI_VK_OK, "Test 6 ref upload_scene failed");
        ASSERT_TRUE(
            fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
                c6, sKeyRef, nullptr, &op, &cmB, lutB.data(), lutB.data(), lutB.data(),
                yuvRef.data(), yuvRef.size(), nullptr, 0, &ispB, nullptr, nullptr, &refMs) == FUJI_VK_OK,
            "Test 6 full Pass 0..6 reference render failed");

        ASSERT_TRUE(yuvHit == yuvRef, "Stage-1 VRAM cache hit must match full Pass 0..6 render with 0 LSB diff");
        ASSERT_TRUE(yuvHit != yuvCold, "Exposure/WB/LUT change on Stage-1 hit must visibly update output YUV");

        std::printf("[test_vulkan_optics] Bokeh 2x2 Pyramid + Stage-1 VRAM Cache PASSED (1024x576 rad=28 cold=%.2f ms, ref=%.2f ms, Stage-1 AE/WB/LUT hit=%.2f ms, 0 LSB diff)\n",
                    coldMs, refMs, hitMs);
        fuji_vk_destroy(c6);
    }

    /* Test 7: IBIS shift pushing sensor corners into image-circle falloff must keep SPEC_HAS_VIG=1 */
    {
        FujiVkContext* c7 = nullptr;
        char err7[256] = {0};
        ASSERT_TRUE(fuji_vk_create(FUJI_VK_SELECT_ALLOW_SOFTWARE, &c7, nullptr, err7, sizeof(err7)) == FUJI_VK_OK && c7 != nullptr,
                    "Test 7 fuji_vk_create failed");
        const uint32_t w = 64u, h = 64u;
        const size_t nPx = static_cast<size_t>(w) * h;
        std::vector<float> flatIn(nPx * 4u, 1.0f);
        std::vector<float> outUnshifted(nPx * 4u, 0.0f);
        std::vector<float> outShifted(nPx * 4u, 0.0f);
        /* Sensor 40x40 mm -> unshifted corner r = 20*sqrt(2) = 28.28 mm.
         * imageCircleMm = 68 mm -> radius = 34 mm, onset = 0.88*34 = 29.92 mm (> 28.28 mm).
         * With cos4Power=0 & mechVignetting=0: unshifted has zero falloff, but ibisShiftX=16 px
         * (10 mm) pushes the (0,0) corner to sqrt(29.69^2 + 19.69^2) = 35.63 mm > 34 mm -> black! */
        FujiVkOpticsParams op = make_default_optics(w, h);
        op.sensorWidthMm = 40.0f;
        op.imageCircleMm = 68.0f;
        op.cos4Power = 0.0f;
        op.mechVignetting = 0.0f;
        op.borderClamp = 1u;
        double ms = 0.0;
        ASSERT_TRUE(fuji_vk_render_optics(c7, flatIn.data(), outUnshifted.data(), &op, &ms) == FUJI_VK_OK,
                    "Test 7 unshifted warp failed");
        ASSERT_TRUE(std::fabs(outUnshifted[0] - 1.0f) < 1e-4f,
                    "Test 7 unshifted corner must stay fully illuminated inside image circle");

        op.ibisShiftX = 16.0f;
        ASSERT_TRUE(fuji_vk_render_optics(c7, flatIn.data(), outShifted.data(), &op, &ms) == FUJI_VK_OK,
                    "Test 7 IBIS-shifted warp failed");
        const float cornerR = outShifted[0];
        const size_t centerIdx = (static_cast<size_t>(h / 2u) * w + (w / 2u)) * 4u;
        const float centerR = outShifted[centerIdx];
        ASSERT_TRUE(cornerR < 0.01f,
                    "Test 7 IBIS-shifted corner outside image circle must fall off to black (SPEC_HAS_VIG must not be compiled out)");
        ASSERT_TRUE(centerR > 0.99f,
                    "Test 7 IBIS-shifted center inside image circle must remain illuminated");
        std::printf("[test_vulkan_optics] IBIS Image-Circle Specialization PASSED (corner=%.4f, center=%.4f)\n",
                    cornerR, centerR);
        fuji_vk_destroy(c7);
    }

    std::printf("[test_vulkan_optics] ALL NATIVE VULKAN COMPUTE TESTS PASSED!\n");
    return 0;
}
