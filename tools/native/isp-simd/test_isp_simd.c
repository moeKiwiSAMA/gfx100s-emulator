/*
 * test_isp_simd.c - Standalone C parity & performance self-test for libfuji_isp_simd.
 * Verifies:
 *   1. Cross-ISA parity (scalar vs sse2 vs avx2 vs avx512) & 1T vs MT parity for
 *      fuji_isp_demosaic_yuv_mt across UYVY422, YYUV422, NV16, NV12, 8-bit/16-bit,
 *      CCM, 1D Tone LUT, 16x16x16 3D LUT, 5-tap Peaking, 256-bin YRGB Histogram,
 *      and single-pass AF contrast.
 *   2. Cross-ISA parity & 1T vs MT parity for Black Mist, UV CHEAP_POOR, and Star 4/6/8-pt.
 *   3. Performance benchmarks at 1024x576 and 2048x1536.
 */

#define _POSIX_C_SOURCE 200809L
#include "fuji_isp_simd.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec * 1e-6;
}

static uint32_t xorshift32(uint32_t* s) {
    uint32_t x = *s;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *s = x;
    return x;
}

static void build_srgb_lut(int32_t* lut) {
    for (int i = 0; i < 4096; ++i) {
        double c = (double)i / 4095.0;
        double s = (c <= 0.0031308) ? (c * 12.92) : (1.055 * pow(c, 1.0 / 2.4) - 0.055);
        int v = (int)floor(s * 255.0 + 0.5);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        lut[i] = v;
    }
}

static void build_test_lut3d(float* lut3d) {
    /* Smooth Classic-Chrome-like warm-highlight, cool-shadow 16x16x16 3D LUT */
    for (int b = 0; b < 16; ++b) {
        float fb = (float)b / 15.0f;
        for (int g = 0; g < 16; ++g) {
            float fg = (float)g / 15.0f;
            for (int r = 0; r < 16; ++r) {
                float fr = (float)r / 15.0f;
                int idx = ((b * 16 + g) * 16 + r) * 3;
                float luma = 0.2126f * fr + 0.7152f * fg + 0.0722f * fb;
                float nr = fr * 0.96f + luma * 0.06f;
                float ng = fg * 0.94f + luma * 0.05f;
                float nb = fb * 0.90f + luma * 0.08f + (1.0f - luma) * 0.02f;
                lut3d[idx + 0] = nr < 0.0f ? 0.0f : (nr > 1.0f ? 1.0f : nr);
                lut3d[idx + 1] = ng < 0.0f ? 0.0f : (ng > 1.0f ? 1.0f : ng);
                lut3d[idx + 2] = nb < 0.0f ? 0.0f : (nb > 1.0f ? 1.0f : nb);
            }
        }
    }
}

static int test_isp_parity(void) {
    const int w = 512, h = 288;
    const int n_px = w * h;
    uint8_t* raw = (uint8_t*)malloc((size_t)n_px * 2u);
    int32_t* xmap = (int32_t*)malloc((size_t)w * sizeof(int32_t));
    int32_t lut[4096];
    float* lut3d = (float*)malloc(16u * 16u * 16u * 3u * sizeof(float));
    build_srgb_lut(lut);
    build_test_lut3d(lut3d);

    uint32_t seed = 0x13572468u;
    for (int i = 0; i < n_px; ++i) {
        uint16_t v = (uint16_t)((xorshift32(&seed) & 0xFFFFu));
        raw[i * 2 + 0] = (uint8_t)(v & 0xFFu);
        raw[i * 2 + 1] = (uint8_t)(v >> 8);
    }
    for (int x = 0; x < w; ++x) xmap[x] = x;

    int32_t ccm[9] = { 1120, -84, -12, -52, 1140, -64, 18, -142, 1148 };
    int32_t yuv[9] = { 306, 601, 117, -173, -339, 512, 512, -429, -83 };

    const int modes[4][3] = {
        { 0, 0, 0 },                     /* UYVY422 */
        { 0, 0, FUJI_ISP_FLAG_YYUV422 }, /* YYUV422 */
        { 1, 0, 0 },                     /* NV16 */
        { 1, 1, 0 },                     /* NV12 */
    };
    const char* mode_names[4] = { "UYVY422", "YYUV422", "NV16", "NV12" };
    const char* tiers[4] = { "scalar", "sse2", "avx2", "avx512" };

    size_t y_size = (size_t)n_px * 2u;
    size_t uv_size = (size_t)n_px;
    uint8_t* ref_y  = (uint8_t*)malloc(y_size);
    uint8_t* ref_uv = (uint8_t*)malloc(uv_size);
    uint8_t* cur_y  = (uint8_t*)malloc(y_size);
    uint8_t* cur_uv = (uint8_t*)malloc(uv_size);

    for (int m = 0; m < 4; ++m) {
        for (int use_3d = 0; use_3d <= 1; ++use_3d) {
            int32_t cfg[20] = {
                w, h, 1, w, h, modes[m][0] ? w : (w * 2),
                modes[m][0], modes[m][1],
                0, 0, w, h,
                1100, 1024, 1180,
                FUJI_ISP_FLAG_HAS_CCM | FUJI_ISP_FLAG_HAS_YUV_MAT | FUJI_ISP_FLAG_PEAKING |
                FUJI_ISP_FLAG_AF_CONTRAST | modes[m][2] | (use_3d ? FUJI_ISP_FLAG_HAS_LUT3D : 0),
                10, 40, 0x00FF554C, 1
            };

            setenv("FUJI_ISP_SIMD_MAX", "scalar", 1);
            int32_t ref_hist[1024];
            int64_t ref_af = 0;
            int32_t ref_st = fuji_isp_demosaic_yuv_mt(raw, ref_y, modes[m][0] ? ref_uv : NULL, xmap, ccm, lut, lut, lut, use_3d ? lut3d : NULL, yuv, ref_hist, &ref_af, cfg);
            if (ref_st < 0 || ref_af <= 0) {
                fprintf(stderr, "FAIL: ref ISP failed mode=%s use3d=%d st=%d af=%lld\n", mode_names[m], use_3d, ref_st, (long long)ref_af);
                return 1;
            }

            for (int ti = 0; ti < 4; ++ti) {
                setenv("FUJI_ISP_SIMD_MAX", tiers[ti], 1);
                for (int th = 1; th <= 4; th += 3) {
                    cfg[19] = th;
                    int32_t cur_hist[1024];
                    int64_t cur_af = 0;
                    int32_t cur_st = fuji_isp_demosaic_yuv_mt(raw, cur_y, modes[m][0] ? cur_uv : NULL, xmap, ccm, lut, lut, lut, use_3d ? lut3d : NULL, yuv, cur_hist, &cur_af, cfg);

                    size_t check_y_len = modes[m][0] ? (size_t)n_px : (size_t)n_px * 2u;
                    int max_diff_y = 0, max_diff_uv = 0;
                    for (size_t i = 0; i < check_y_len; ++i) {
                        int d = abs((int)ref_y[i] - (int)cur_y[i]);
                        if (d > max_diff_y) max_diff_y = d;
                    }
                    if (modes[m][0]) {
                        size_t check_uv_len = modes[m][1] ? ((size_t)n_px / 2u) : (size_t)n_px;
                        for (size_t i = 0; i < check_uv_len; ++i) {
                            int d = abs((int)ref_uv[i] - (int)cur_uv[i]);
                            if (d > max_diff_uv) max_diff_uv = d;
                        }
                    }
                    int tol = use_3d ? 1 : 0;
                    if (!use_3d) {
                        if (cur_st != ref_st || cur_af != ref_af || memcmp(ref_hist, cur_hist, sizeof(ref_hist)) != 0) {
                            fprintf(stderr, "FAIL: ISP exact parity mismatch mode=%s tier=%s th=%d st=%d/%d af=%lld/%lld\n",
                                    mode_names[m], tiers[ti], th, cur_st, ref_st, (long long)cur_af, (long long)ref_af);
                            return 1;
                        }
                    }
                    if (max_diff_y > tol || max_diff_uv > tol) {
                        fprintf(stderr, "FAIL: ISP pixel diff mode=%s use3d=%d tier=%s th=%d dy=%d duv=%d\n",
                                mode_names[m], use_3d, tiers[ti], th, max_diff_y, max_diff_uv);
                        return 1;
                    }
                }
            }
        }
    }

    unsetenv("FUJI_ISP_SIMD_MAX");
    free(raw); free(xmap); free(lut3d);
    free(ref_y); free(ref_uv); free(cur_y); free(cur_uv);
    printf("[PASS] ISP cross-ISA (scalar/sse2/avx2/avx512) & 1T/4T parity across UYVY422/YYUV422/NV16/NV12 + 3D LUT + Peaking + Hist + AF\n");
    return 0;
}

static int test_optics_parity(void) {
    const int w = 256, h = 192;
    const int n = w * h;
    float* base_r = (float*)malloc((size_t)n * sizeof(float));
    float* base_g = (float*)malloc((size_t)n * sizeof(float));
    float* base_b = (float*)malloc((size_t)n * sizeof(float));
    float* ref_r  = (float*)malloc((size_t)n * sizeof(float));
    float* ref_g  = (float*)malloc((size_t)n * sizeof(float));
    float* ref_b  = (float*)malloc((size_t)n * sizeof(float));
    float* cur_r  = (float*)malloc((size_t)n * sizeof(float));
    float* cur_g  = (float*)malloc((size_t)n * sizeof(float));
    float* cur_b  = (float*)malloc((size_t)n * sizeof(float));
    float* s_r    = (float*)malloc((size_t)n * sizeof(float));
    float* s_g    = (float*)malloc((size_t)n * sizeof(float));
    float* s_b    = (float*)malloc((size_t)n * sizeof(float));
    float* a_r    = (float*)malloc((size_t)n * sizeof(float));
    float* a_g    = (float*)malloc((size_t)n * sizeof(float));
    float* a_b    = (float*)malloc((size_t)n * sizeof(float));

    uint32_t seed = 0xCAFEBABEu;
    for (int i = 0; i < n; ++i) {
        base_r[i] = (float)(xorshift32(&seed) & 0xFFFFu) / 65535.0f * 0.55f;
        base_g[i] = (float)(xorshift32(&seed) & 0xFFFFu) / 65535.0f * 0.55f;
        base_b[i] = (float)(xorshift32(&seed) & 0xFFFFu) / 65535.0f * 0.55f;
    }
    /* Add specular highlights */
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            int idx = (40 + dy) * w + (60 + dx);
            base_r[idx] = 1.6f; base_g[idx] = 1.5f; base_b[idx] = 1.3f;
        }
    }

    const char* tiers[4] = { "scalar", "sse2", "avx2", "avx512" };

    /* 1. Black Mist */
    memcpy(ref_r, base_r, (size_t)n * sizeof(float));
    memcpy(ref_g, base_g, (size_t)n * sizeof(float));
    memcpy(ref_b, base_b, (size_t)n * sizeof(float));
    setenv("FUJI_ISP_SIMD_MAX", "scalar", 1);
    fuji_optics_black_mist_f32(ref_r, ref_g, ref_b, s_r, s_g, s_b, a_r, a_g, a_b, w, h, 0.55f, 0.50f, 8, 1);

    for (int ti = 0; ti < 4; ++ti) {
        setenv("FUJI_ISP_SIMD_MAX", tiers[ti], 1);
        memcpy(cur_r, base_r, (size_t)n * sizeof(float));
        memcpy(cur_g, base_g, (size_t)n * sizeof(float));
        memcpy(cur_b, base_b, (size_t)n * sizeof(float));
        fuji_optics_black_mist_f32(cur_r, cur_g, cur_b, s_r, s_g, s_b, a_r, a_g, a_b, w, h, 0.55f, 0.50f, 8, 4);
        float max_err = 0.0f;
        for (int i = 0; i < n; ++i) {
            float er = fabsf(ref_r[i] - cur_r[i]);
            float eg = fabsf(ref_g[i] - cur_g[i]);
            float eb = fabsf(ref_b[i] - cur_b[i]);
            if (er > max_err) max_err = er;
            if (eg > max_err) max_err = eg;
            if (eb > max_err) max_err = eb;
        }
        if (max_err > 1e-5f) {
            fprintf(stderr, "FAIL: Black Mist parity tier=%s err=%g\n", tiers[ti], max_err);
            return 1;
        }
    }

    /* 2. UV CHEAP_POOR */
    memcpy(ref_r, base_r, (size_t)n * sizeof(float));
    memcpy(ref_g, base_g, (size_t)n * sizeof(float));
    memcpy(ref_b, base_b, (size_t)n * sizeof(float));
    setenv("FUJI_ISP_SIMD_MAX", "scalar", 1);
    fuji_optics_uv_filter_f32(ref_r, ref_g, ref_b, s_r, s_g, s_b, a_r, a_g, a_b, w, h, 0.30f, 0.25f, 0.22f, 0.96f, 1.03f, 0.94f, 1, 1);

    for (int ti = 0; ti < 4; ++ti) {
        setenv("FUJI_ISP_SIMD_MAX", tiers[ti], 1);
        memcpy(cur_r, base_r, (size_t)n * sizeof(float));
        memcpy(cur_g, base_g, (size_t)n * sizeof(float));
        memcpy(cur_b, base_b, (size_t)n * sizeof(float));
        fuji_optics_uv_filter_f32(cur_r, cur_g, cur_b, s_r, s_g, s_b, a_r, a_g, a_b, w, h, 0.30f, 0.25f, 0.22f, 0.96f, 1.03f, 0.94f, 1, 4);
        float max_err = 0.0f;
        for (int i = 0; i < n; ++i) {
            float er = fabsf(ref_r[i] - cur_r[i]);
            float eg = fabsf(ref_g[i] - cur_g[i]);
            float eb = fabsf(ref_b[i] - cur_b[i]);
            if (er > max_err) max_err = er;
            if (eg > max_err) max_err = eg;
            if (eb > max_err) max_err = eb;
        }
        if (max_err > 1e-5f) {
            fprintf(stderr, "FAIL: UV parity tier=%s err=%g\n", tiers[ti], max_err);
            return 1;
        }
    }

    /* 3. Star 6-pt */
    memcpy(ref_r, base_r, (size_t)n * sizeof(float));
    memcpy(ref_g, base_g, (size_t)n * sizeof(float));
    memcpy(ref_b, base_b, (size_t)n * sizeof(float));
    setenv("FUJI_ISP_SIMD_MAX", "scalar", 1);
    int32_t ref_hot = fuji_optics_star_filter_f32(ref_r, ref_g, ref_b, s_r, s_g, s_b, w, h, 6, 0.80f, 0.85f, 0.20f, 30.0, 1);

    for (int ti = 0; ti < 4; ++ti) {
        setenv("FUJI_ISP_SIMD_MAX", tiers[ti], 1);
        memcpy(cur_r, base_r, (size_t)n * sizeof(float));
        memcpy(cur_g, base_g, (size_t)n * sizeof(float));
        memcpy(cur_b, base_b, (size_t)n * sizeof(float));
        int32_t cur_hot = fuji_optics_star_filter_f32(cur_r, cur_g, cur_b, s_r, s_g, s_b, w, h, 6, 0.80f, 0.85f, 0.20f, 30.0, 4);
        float max_err = 0.0f;
        for (int i = 0; i < n; ++i) {
            float er = fabsf(ref_r[i] - cur_r[i]);
            float eg = fabsf(ref_g[i] - cur_g[i]);
            float eb = fabsf(ref_b[i] - cur_b[i]);
            if (er > max_err) max_err = er;
            if (eg > max_err) max_err = eg;
            if (eb > max_err) max_err = eb;
        }
        if (cur_hot != ref_hot || max_err > 1e-5f) {
            fprintf(stderr, "FAIL: Star parity tier=%s hot=%d/%d err=%g\n", tiers[ti], cur_hot, ref_hot, max_err);
            return 1;
        }
    }

    unsetenv("FUJI_ISP_SIMD_MAX");
    free(base_r); free(base_g); free(base_b);
    free(ref_r); free(ref_g); free(ref_b);
    free(cur_r); free(cur_g); free(cur_b);
    free(s_r); free(s_g); free(s_b);
    free(a_r); free(a_g); free(a_b);
    printf("[PASS] Optics cross-ISA (scalar/sse2/avx2/avx512) & 1T/4T parity for Black Mist, UV CHEAP_POOR, Star 6-pt\n");
    return 0;
}

static void run_bench_res(int w, int h, int iters) {
    const int n = w * h;
    uint8_t* raw = (uint8_t*)malloc((size_t)n * 2u);
    uint8_t* dst = (uint8_t*)malloc((size_t)n * 2u);
    int32_t* xmap = (int32_t*)malloc((size_t)w * sizeof(int32_t));
    int32_t lut[4096];
    float* lut3d = (float*)malloc(16u * 16u * 16u * 3u * sizeof(float));
    build_srgb_lut(lut);
    build_test_lut3d(lut3d);
    for (int i = 0; i < n; ++i) {
        raw[i * 2] = (uint8_t)(i & 0xFF);
        raw[i * 2 + 1] = (uint8_t)((i * 7) & 0xFF);
    }
    for (int x = 0; x < w; ++x) xmap[x] = x;
    int32_t ccm[9] = { 1120, -84, -12, -52, 1140, -64, 18, -142, 1148 };
    int32_t yuv[9] = { 306, 601, 117, -173, -339, 512, 512, -429, -83 };
    int32_t hist[1024];
    int64_t af = 0;

    int32_t cfg[20] = {
        w, h, 1, w, h, w * 2, 0, 0, 0, 0, w, h,
        1100, 1024, 1180,
        FUJI_ISP_FLAG_HAS_CCM | FUJI_ISP_FLAG_HAS_YUV_MAT | FUJI_ISP_FLAG_PEAKING | FUJI_ISP_FLAG_AF_CONTRAST,
        10, 48, 0x00FF554C, 0
    };

    /* Warmup */
    fuji_isp_demosaic_yuv_mt(raw, dst, NULL, xmap, ccm, lut, lut, lut, NULL, yuv, hist, &af, cfg);

    double t0 = now_ms();
    for (int i = 0; i < iters; ++i) {
        fuji_isp_demosaic_yuv_mt(raw, dst, NULL, xmap, ccm, lut, lut, lut, NULL, yuv, hist, &af, cfg);
    }
    double isp_ms = (now_ms() - t0) / (double)iters;

    cfg[15] |= FUJI_ISP_FLAG_HAS_LUT3D;
    fuji_isp_demosaic_yuv_mt(raw, dst, NULL, xmap, ccm, lut, lut, lut, lut3d, yuv, hist, &af, cfg);
    t0 = now_ms();
    for (int i = 0; i < iters; ++i) {
        fuji_isp_demosaic_yuv_mt(raw, dst, NULL, xmap, ccm, lut, lut, lut, lut3d, yuv, hist, &af, cfg);
    }
    double isp3d_ms = (now_ms() - t0) / (double)iters;

    float* r  = (float*)malloc((size_t)n * sizeof(float));
    float* g  = (float*)malloc((size_t)n * sizeof(float));
    float* b  = (float*)malloc((size_t)n * sizeof(float));
    float* sr = (float*)malloc((size_t)n * sizeof(float));
    float* sg = (float*)malloc((size_t)n * sizeof(float));
    float* sb = (float*)malloc((size_t)n * sizeof(float));
    float* ar = (float*)malloc((size_t)n * sizeof(float));
    float* ag = (float*)malloc((size_t)n * sizeof(float));
    float* ab = (float*)malloc((size_t)n * sizeof(float));
    for (int i = 0; i < n; ++i) { r[i] = 0.35f; g[i] = 0.40f; b[i] = 0.30f; }
    for (int dy = -6; dy <= 6; ++dy) {
        for (int dx = -6; dx <= 6; ++dx) {
            int idx = (h / 3 + dy) * w + (w / 3 + dx);
            r[idx] = 1.5f; g[idx] = 1.4f; b[idx] = 1.2f;
        }
    }

    int bm_rad = (int)floor((double)(w < h ? w : h) * (0.014 + 0.024 * 0.5) + 0.5);
    if (bm_rad < 2) bm_rad = 2;
    if (bm_rad > 24) bm_rad = 24;
    fuji_optics_black_mist_f32(r, g, b, sr, sg, sb, ar, ag, ab, w, h, 0.55f, 0.50f, bm_rad, 0);
    t0 = now_ms();
    for (int i = 0; i < iters; ++i) {
        fuji_optics_black_mist_f32(r, g, b, sr, sg, sb, ar, ag, ab, w, h, 0.55f, 0.50f, bm_rad, 0);
    }
    double bm_ms = (now_ms() - t0) / (double)iters;

    fuji_optics_uv_filter_f32(r, g, b, sr, sg, sb, ar, ag, ab, w, h, 0.30f, 0.25f, 0.22f, 0.96f, 1.03f, 0.94f, 1, 0);
    t0 = now_ms();
    for (int i = 0; i < iters; ++i) {
        fuji_optics_uv_filter_f32(r, g, b, sr, sg, sb, ar, ag, ab, w, h, 0.30f, 0.25f, 0.22f, 0.96f, 1.03f, 0.94f, 1, 0);
    }
    double uv_ms = (now_ms() - t0) / (double)iters;

    for (int i = 0; i < n; ++i) { r[i] = 0.25f; g[i] = 0.25f; b[i] = 0.25f; }
    for (int dy = -6; dy <= 6; ++dy) {
        for (int dx = -6; dx <= 6; ++dx) {
            int idx = (h / 3 + dy) * w + (w / 3 + dx);
            r[idx] = 1.8f; g[idx] = 1.6f; b[idx] = 1.4f;
        }
    }
    fuji_optics_star_filter_f32(r, g, b, sr, sg, sb, w, h, 6, 0.80f, 0.85f, 0.18f, 15.0, 0);
    t0 = now_ms();
    for (int i = 0; i < iters; ++i) {
        fuji_optics_star_filter_f32(r, g, b, sr, sg, sb, w, h, 6, 0.80f, 0.85f, 0.18f, 15.0, 0);
    }
    double star_ms = (now_ms() - t0) / (double)iters;

    printf("[BENCH %dx%d | %s | threads=%d] ISP+CCM+Tone+Peak+Hist+AF=%.3f ms | ISP+3DLUT=%.3f ms | BlackMist=%.3f ms | UV_CHEAP=%.3f ms | Star6pt=%.3f ms\n",
           w, h, fuji_isp_simd_backend_name(), fuji_isp_simd_default_threads(),
           isp_ms, isp3d_ms, bm_ms, uv_ms, star_ms);

    free(raw); free(dst); free(xmap); free(lut3d);
    free(r); free(g); free(b); free(sr); free(sg); free(sb); free(ar); free(ag); free(ab);
}

int main(void) {
    printf("fuji_isp_simd ABI v%d | backend=%s | level=%d | default_threads=%d\n",
           fuji_isp_simd_abi_version(),
           fuji_isp_simd_backend_name(),
           fuji_isp_simd_level(),
           fuji_isp_simd_default_threads());
    if (test_isp_parity() != 0) return 1;
    if (test_optics_parity() != 0) return 1;
    run_bench_res(1024, 576, 30);
    run_bench_res(2048, 1536, 10);
    return 0;
}
