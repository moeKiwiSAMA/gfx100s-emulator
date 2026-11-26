#include "fuji_render.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t ref_div255_rounded(uint32_t x) {
    uint32_t t = x + 128u;
    return (t + (t >> 8)) >> 8;
}

static uint32_t ref_uyvy_to_rgb(const uint8_t* pair, int odd) {
    uint32_t u = pair[0], y0 = pair[1], v = pair[2], y1 = pair[3];
    if ((u | y0 | v | y1) == 0u ||
        (u == 0u && y0 == 0u && v == 0x80u && y1 == 0x80u) ||
        (u == 0x80u && y0 == 0x80u && v == 0u && y1 == 0u)) return 0u;
    int32_t y = (int32_t)(odd ? y1 : y0);
    int32_t d = (int32_t)u - 128;
    int32_t e = (int32_t)v - 128;
    int32_t r = (256 * y + 359 * e + 128) >> 8;
    int32_t g = (256 * y - 88 * d - 183 * e + 128) >> 8;
    int32_t b = (256 * y + 454 * d + 128) >> 8;
    if (r < 0) r = 0; else if (r > 255) r = 255;
    if (g < 0) g = 0; else if (g > 255) g = 255;
    if (b < 0) b = 0; else if (b > 255) b = 255;
    return ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}

static uint32_t ref_load_u32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint32_t ref_ror32_8(uint32_t x) {
    return (x >> 8) | (x << 24);
}

static int test_fill_u32(void) {
    const int lengths[] = { 1, 3, 4, 15, 16, 31, 32, 63, 64, 127, 128, 131, 4096 };
    for (size_t k = 0; k < sizeof(lengths) / sizeof(lengths[0]); ++k) {
        int len = lengths[k];
        uint8_t* buf = (uint8_t*)calloc((size_t)len + 8, 1);
        const uint32_t pat = 0xff112233u;
        fuji_render_fill_u32(buf, len, pat);
        for (int i = 0; i < len; ++i) {
            uint8_t exp = (uint8_t)((pat >> ((i & 3) * 8)) & 0xffu);
            if (buf[i] != exp) {
                fprintf(stderr, "FAIL fill_u32 len=%d idx=%d got=0x%02x exp=0x%02x\n", len, i, buf[i], exp);
                free(buf);
                return 1;
            }
        }
        if (buf[len] != 0) {
            fprintf(stderr, "FAIL fill_u32 buffer overrun len=%d\n", len);
            free(buf);
            return 1;
        }
        free(buf);
    }
    return 0;
}

static int test_filtered_morton(void) {
    /* 1. Adjacent Morton texels are opaque red and transparent blue (2x magnification bilinear). */
    uint8_t texture[256] = {255, 0, 0, 255, 0, 255, 0, 0};
    uint8_t frame[16] = {0};
    const uint8_t expected[16] = {
        255, 0, 0, 255, 191, 0, 0, 255, 64, 0, 0, 255, 0, 0, 0, 0
    };
    fuji_render_blit_filtered_morton(texture, frame, 4, 8, 8, 1, 0, 1, 0, 4,
                                     0.25, 0.5, 0.5, 0.0, 0.0, 1.0);
    if (memcmp(frame, expected, sizeof(frame)) != 0) {
        fprintf(stderr, "FAIL filtered Morton edge or alpha mismatch\n");
        return 1;
    }

    /* 2. Out-of-range and NaN/Inf UV safety: must not UB/crash or mutate frame. */
    uint8_t guard_frame[64];
    memset(guard_frame, 0x5a, sizeof(guard_frame));
    uint8_t guard_ref[64];
    memcpy(guard_ref, guard_frame, sizeof(guard_ref));
    const double bad_coords[] = { -1e20, 1e20, -INFINITY, INFINITY, NAN };
    for (size_t i = 0; i < sizeof(bad_coords) / sizeof(bad_coords[0]); ++i) {
        double c = bad_coords[i];
        fuji_render_blit_filtered_morton(texture, guard_frame, 4, 8, 8, 1, 0, 4, 0, 4,
                                         c, 0.5, 0.5, 0.0, 0.0, 1.0);
        fuji_render_blit_filtered_morton(texture, guard_frame, 4, 8, 8, 1, 0, 4, 0, 4,
                                         0.5, c, 0.5, 0.0, 0.0, 1.0);
        fuji_render_blit_filtered_morton(texture, guard_frame, 4, 8, 8, 1, 0, 4, 0, 4,
                                         c, c, 2.33, 0.0, 0.0, 1.0);
        fuji_render_blit_filtered_morton(texture, guard_frame, 4, 8, 8, 1, 0, 4, 0, 4,
                                         c, 0.5, 0.5, 0.2, 0.2, 0.5);
    }
    if (memcmp(guard_frame, guard_ref, sizeof(guard_frame)) != 0) {
        fprintf(stderr, "FAIL filtered Morton out-of-range/NaN mutated destination frame\n");
        return 1;
    }

    /* 3. Heavy 0.43x horizontal minification on 1-px thin vertical white strokes over transparent blue. */
    const int mw = 24, mh = 8;
    uint8_t linear_min[24 * 8 * 4];
    uint8_t morton_min[24 * 8 * 4];
    for (int y = 0; y < mh; ++y) {
        for (int x = 0; x < mw; ++x) {
            uint8_t* p = linear_min + (y * mw + x) * 4;
            if (x % 3 == 1) {
                p[0] = 255; p[1] = 255; p[2] = 255; p[3] = 255; /* opaque white stroke */
            } else {
                p[0] = 0; p[1] = 255; p[2] = 0; p[3] = 0; /* transparent blue gap */
            }
        }
    }
    fuji_render_morton_tile_8x8(linear_min, morton_min, mw, mh, mw * 4);
    uint8_t min_frame[16 * 8 * 4];
    memset(min_frame, 0, sizeof(min_frame));
    const double du = 1.0 / 0.43;
    const int dst_w = 10; /* floor(24 * 0.43) = 10 */
    fuji_render_blit_filtered_morton(morton_min, min_frame, 16, mw, mh, mw / 8,
                                     0, mh, 0, dst_w, 0.5 * du, 0.5, du, 0.0, 0.0, 1.0);
    for (int x = 0; x < dst_w; ++x) {
        const uint8_t* p = min_frame + (3 * 16 + x) * 4;
        if (p[0] < 32 || p[1] != 255 || p[2] != 255 || p[3] != 255) {
            fprintf(stderr, "FAIL 0.43x minification col=%d got=[%u,%u,%u,%u]\n", x, p[0], p[1], p[2], p[3]);
            return 1;
        }
    }

    /* 4. OOBE language glyph 82x32 inside 96x32 Morton storage (padding cols 82..95 sentinel magenta). */
    const int lw = 82, lh = 32, lpitch = 96;
    uint8_t* lang_lin = (uint8_t*)calloc((size_t)(lpitch * lh * 4), 1);
    uint8_t* lang_morton = (uint8_t*)calloc((size_t)(lpitch * lh * 4), 1);
    uint8_t* lang_frame = (uint8_t*)calloc((size_t)(160 * 64 * 4), 1);
    for (int y = 0; y < lh; ++y) {
        for (int x = 0; x < lpitch; ++x) {
            uint8_t* p = lang_lin + (y * lpitch + x) * 4;
            if (x >= lw) {
                /* Magenta padding sentinel outside logical 82px width */
                p[0] = 255; p[1] = 255; p[2] = 0; p[3] = 255;
            } else if (x == 1 || x == 80) {
                /* Crisp white ink at leftmost/rightmost language glyph bounds x=1..80 */
                p[0] = 255; p[1] = 220; p[2] = 220; p[3] = 220;
            }
        }
    }
    fuji_render_morton_tile_8x8(lang_lin, lang_morton, lpitch, lh, lpitch * 4);
    const double sx = 1.5975609756;
    const double sy = 1.59375;
    fuji_render_blit_filtered_morton(lang_morton, lang_frame, 160, lw, lh, lpitch / 8,
                                     0, 51, 0, 131, 0.5 / sx, 0.5 / sy, 1.0 / sx, 0.0, 0.0, 1.0 / sy);
    for (int y = 0; y < 51; ++y) {
        for (int x = 0; x < 131; ++x) {
            const uint8_t* p = lang_frame + (y * 160 + x) * 4;
            if (p[0] > 0 && (p[1] != 220 || p[2] != 220 || p[3] != 220)) {
                fprintf(stderr, "FAIL OOBE 82x32 padding bleed at (%d,%d) got=[%u,%u,%u,%u]\n",
                        x, y, p[0], p[1], p[2], p[3]);
                free(lang_lin); free(lang_morton); free(lang_frame);
                return 1;
            }
        }
    }
    /* Verify right ink stroke near x=80*1.5976=128 rendered cleanly. */
    if (lang_frame[(25 * 160 + 128) * 4] < 200) {
        fprintf(stderr, "FAIL OOBE 82x32 right stroke missing alpha=%u\n", lang_frame[(25 * 160 + 128) * 4]);
        free(lang_lin); free(lang_morton); free(lang_frame);
        return 1;
    }
    free(lang_lin);
    free(lang_morton);
    free(lang_frame);
    return 0;
}

static int test_morton_tile(void) {
    const int w = 32, h = 24, stride = w * 4;
    uint8_t* src = (uint8_t*)malloc((size_t)(w * h * 4));
    uint8_t* dst = (uint8_t*)calloc((size_t)(w * h * 4), 1);
    uint8_t* ref = (uint8_t*)calloc((size_t)(w * h * 4), 1);
    for (int i = 0; i < w * h * 4; ++i) src[i] = (uint8_t)((i * 37 + 13) & 0xff);

    fuji_render_morton_tile_8x8(src, dst, w, h, stride);

    int off = 0;
    for (int top = 0; top < h; top += 8) {
        for (int left = 0; left < w; left += 8) {
            for (int m = 0; m < 64; ++m) {
                int mc = (m & 1) + ((m >> 1) & 2) + ((m >> 2) & 4);
                int mr = ((m >> 1) & 1) + ((m >> 2) & 2) + ((m >> 3) & 4);
                int s = (top + mr) * stride + ((left + mc) << 2);
                memcpy(ref + off, src + s, 4);
                off += 4;
            }
        }
    }
    if (memcmp(dst, ref, (size_t)(w * h * 4)) != 0) {
        fprintf(stderr, "FAIL morton_tile_8x8 mismatch\n");
        free(src); free(dst); free(ref);
        return 1;
    }
    free(src); free(dst); free(ref);
    return 0;
}

static int test_composite_1to1(void) {
    const int w = 130, h = 16; /* non-multiple of 8 to test SIMD + scalar tail */
    const int px = 10, py = 2, pw = 104, ph = 10, pstride = pw * 2;
    uint8_t* osd = (uint8_t*)malloc((size_t)(w * h * 4));
    uint8_t* pic = (uint8_t*)malloc((size_t)(pstride * ph));
    uint32_t* got = (uint32_t*)calloc((size_t)(w * h), sizeof(uint32_t));
    uint32_t* ref = (uint32_t*)calloc((size_t)(w * h), sizeof(uint32_t));

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            int i = (y * w + x) * 4;
            uint8_t a = (x < 24) ? 255 : ((x < 48) ? 0 : (uint8_t)((x * 17 + y * 31) & 0xff));
            osd[i + 0] = a;
            osd[i + 1] = (uint8_t)((x * 3) & 0xff);
            osd[i + 2] = (uint8_t)((y * 15) & 0xff);
            osd[i + 3] = (uint8_t)((x ^ y) & 0xff);
        }
    }
    for (int y = 0; y < ph; ++y) {
        for (int x = 0; x < pw; x += 2) {
            int i = y * pstride + x * 2;
            if (x == 0 || (x == 16 && y == 3)) {
                /* BUG-09 all-zero UYVY macro-pixel test */
                pic[i] = pic[i + 1] = pic[i + 2] = pic[i + 3] = 0;
            } else if (x == 2 || (x == 18 && y == 4)) {
                /* BUG-A5-5 GFX100S 2.13 Milbeaut 0x80800000 ([00 00 80 80]) pillarbox black fill */
                pic[i] = 0x00; pic[i + 1] = 0x00; pic[i + 2] = 0x80; pic[i + 3] = 0x80;
            } else if (x == 4 || (x == 20 && y == 5)) {
                /* BUG-A5-5 GFX100S 2.13 Milbeaut 0x00008080 ([80 80 00 00]) pillarbox black fill */
                pic[i] = 0x80; pic[i + 1] = 0x80; pic[i + 2] = 0x00; pic[i + 3] = 0x00;
            } else {
                pic[i + 0] = (uint8_t)((x * 5 + 32) & 0xff);
                pic[i + 1] = (uint8_t)((y * 23 + x) & 0xff);
                pic[i + 2] = (uint8_t)((255 - x * 2) & 0xff);
                pic[i + 3] = (uint8_t)((y * 19 + x * 3) & 0xff);
            }
        }
    }

    const int alphas[] = { 255, 180, 1 };
    for (size_t ai = 0; ai < sizeof(alphas) / sizeof(alphas[0]); ++ai) {
        int pa = alphas[ai];
        fuji_render_composite_pic1_osd0(osd, 0, w * 4, pic, px, py, pw, ph, pstride, pa, 0, w, h, NULL, NULL, w, h, got);
        for (int row = 0; row < h; ++row) {
            int in_y = (row >= py && row < py + ph);
            for (int col = 0; col < w; ++col) {
                uint32_t argb = ref_ror32_8(ref_load_u32(osd + (row * w + col) * 4));
                uint32_t a = argb >> 24;
                uint32_t out;
                if (a == 255u) {
                    out = argb;
                } else {
                    uint32_t bg = 0xff000000u;
                    if (in_y && col >= px && col < px + pw) {
                        int lx = col - px;
                        uint32_t rgb = ref_uyvy_to_rgb(pic + (row - py) * pstride + (lx & ~1) * 2, lx & 1);
                        if (pa >= 255) {
                            bg = 0xff000000u | rgb;
                        } else {
                            uint32_t pr = ref_div255_rounded(((rgb >> 16) & 0xffu) * (uint32_t)pa);
                            uint32_t pg = ref_div255_rounded(((rgb >> 8) & 0xffu) * (uint32_t)pa);
                            uint32_t pb = ref_div255_rounded((rgb & 0xffu) * (uint32_t)pa);
                            bg = 0xff000000u | (pr << 16) | (pg << 8) | pb;
                        }
                    }
                    if (a == 0u) {
                        out = bg;
                    } else {
                        uint32_t inv = 255u - a;
                        uint32_t r = ref_div255_rounded(((argb >> 16) & 0xffu) * a + ((bg >> 16) & 0xffu) * inv);
                        uint32_t g = ref_div255_rounded(((argb >> 8) & 0xffu) * a + ((bg >> 8) & 0xffu) * inv);
                        uint32_t b = ref_div255_rounded((argb & 0xffu) * a + (bg & 0xffu) * inv);
                        out = 0xff000000u | (r << 16) | (g << 8) | b;
                    }
                }
                ref[row * w + col] = out;
                if (got[row * w + col] != out) {
                    fprintf(stderr, "FAIL composite pa=%d (%d,%d) got=0x%08x exp=0x%08x\n",
                            pa, col, row, got[row * w + col], out);
                    free(osd); free(pic); free(got); free(ref);
                    return 1;
                }
            }
        }
    }

    free(osd); free(pic); free(got); free(ref);
    return 0;
}

static int test_argb_to_pre(void) {
    uint32_t src[35], dst[35];
    for (int i = 0; i < 35; ++i) src[i] = 0xff000000u | (uint32_t)(i * 0x050301);
    int32_t all_op = fuji_render_argb_to_pre(src, dst, 35);
    if (all_op != 1 || memcmp(src, dst, sizeof(src)) != 0) {
        fprintf(stderr, "FAIL argb_to_pre opaque fastpath\n");
        return 1;
    }
    src[17] = 0x80ff8040u;
    all_op = fuji_render_argb_to_pre(src, dst, 35);
    uint32_t exp = (0x80u << 24) |
        (ref_div255_rounded(0xffu * 0x80u) << 16) |
        (ref_div255_rounded(0x80u * 0x80u) << 8) |
        ref_div255_rounded(0x40u * 0x80u);
    if (all_op != 0 || dst[17] != exp) {
        fprintf(stderr, "FAIL argb_to_pre translucent got=0x%08x exp=0x%08x all_op=%d\n", dst[17], exp, all_op);
        return 1;
    }
    return 0;
}

static int test_linear_gradient_v2(void) {
    const int w = 130, h = 40;
    uint8_t* frame = (uint8_t*)calloc((size_t)(w * h * 4), 1);
    uint32_t lut[128];
    for (int i = 0; i < 128; ++i) {
        uint32_t c = (uint32_t)(0x20 + i);
        lut[i] = 0xff000000u | (c << 16) | ((255u - c) << 8) | (c ^ 0x55u);
    }
    /* Diagonal gradient (m3 != 0) exercising AVX-512 / AVX2 double vector + gather path */
    fuji_render_fill_linear_gradient_rect_rgba(frame, w, h, 5, 4, 125, 36, 0.008, 0.01, -0.05, lut, 128, 1);
    for (int row = 4; row < 36; ++row) {
        double y_vg = (double)h - ((double)row + 0.5);
        double rbase = 0.01 * y_vg - 0.05;
        for (int col = 5; col < 125; ++col) {
            double t = 0.008 * ((double)col + 0.5) + rbase;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            int idx = (int)(t * 127.0 + 0.5);
            if (idx < 0) idx = 0; else if (idx > 127) idx = 127;
            uint32_t c = lut[idx];
            uint32_t exp = ((c >> 24) & 0xffu) | (((c >> 16) & 0xffu) << 8) | (((c >> 8) & 0xffu) << 16) | ((c & 0xffu) << 24);
            uint32_t got = ref_load_u32(frame + ((row * w + col) << 2));
            if (got != exp) {
                fprintf(stderr, "FAIL linear_gradient_v2 (%d,%d) got=0x%08x exp=0x%08x\n", col, row, got, exp);
                free(frame);
                return 1;
            }
        }
    }
    free(frame);
    return 0;
}

static int test_composite_planes_matrix_v2(void) {
    const int w = 68, h = 20;
    uint8_t* osd = (uint8_t*)calloc((size_t)(w * h * 4), 1);
    uint8_t* pic0 = (uint8_t*)malloc((size_t)(40 * 12 * 2));
    uint8_t* pic1 = (uint8_t*)malloc((size_t)(24 * 10 * 2));
    uint32_t* out = (uint32_t*)calloc((size_t)(w * h), sizeof(uint32_t));
    for (int i = 0; i < 40 * 12 * 2; i += 4) { pic0[i] = 0x80; pic0[i + 1] = 0x90; pic0[i + 2] = 0x80; pic0[i + 3] = 0x90; }
    for (int i = 0; i < 24 * 10 * 2; i += 4) { pic1[i] = 0x70; pic1[i + 1] = 0xc0; pic1[i + 2] = 0xa0; pic1[i + 3] = 0xc0; }
    /* Single opaque red OSD pixel at (1,1) */
    osd[(1 * w + 1) * 4 + 0] = 0xff; osd[(1 * w + 1) * 4 + 1] = 0x00; osd[(1 * w + 1) * 4 + 2] = 0x00; osd[(1 * w + 1) * 4 + 3] = 0xff;
    const int32_t mat[9] = { 1200, -100, 20, -40, 1100, -30, 10, -80, 1150 };
    const int32_t cfg[20] = {
        0, w * 4,
        4, 2, 40, 12, 80,
        36, 4, 24, 10, 48,
        180, 0, (int32_t)0xff212320u,
        w, h, w, h, 2
    };
    fuji_render_composite_planes_matrix(osd, pic0, pic1, NULL, NULL, mat, cfg, out);
    /* Verify non-black backdrop pixel (0,0) received matrix transform and differs from raw 0xFF212320 */
    if ((out[0] >> 24) != 0xffu || out[0] == 0xff212320u) {
        fprintf(stderr, "FAIL composite_planes_matrix_v2 backdrop=0x%08x\n", out[0]);
        free(osd); free(pic0); free(pic1); free(out);
        return 1;
    }
    /* Verify PIC0 pixel (10,5) and overlapping PIC0+PIC1 pixel (40,6) are non-zero and distinct */
    if (out[5 * w + 10] == out[0] || out[6 * w + 40] == out[5 * w + 10]) {
        fprintf(stderr, "FAIL composite_planes_matrix_v2 planes p0=0x%08x p01=0x%08x\n", out[5 * w + 10], out[6 * w + 40]);
        free(osd); free(pic0); free(pic1); free(out);
        return 1;
    }
    free(osd); free(pic0); free(pic1); free(out);
    return 0;
}

static int test_composite_lcdc_to_argb_pre(void) {
    /* Verify fuji_render_composite_lcdc_to_argb_pre is bit-identical to
     * fuji_render_composite_planes_matrix followed by fuji_render_argb_to_pre
     * across 1:1 & scaled, opaque & translucent backdrops, PIC0+PIC1+OSD0,
     * NULL OSD, non-multiple-of-16 widths, and color matrix modes 0/1/2. */
    const int32_t src_w = 99, src_h = 41;
    const int32_t osd_stride = src_w * 4;
    uint8_t* osd = (uint8_t*)calloc((size_t)(src_h * osd_stride), 1);
    for (int32_t y = 0; y < src_h; ++y) {
        for (int32_t x = 0; x < src_w; ++x) {
            uint8_t a = (x < 16) ? 0xffu : (x < 32) ? 0x00u : (uint8_t)((x * 17 + y * 11) & 0xffu);
            if (y == 1) a = 0x00u; /* full transparent OSD row */
            int32_t off = y * osd_stride + x * 4;
            osd[off + 0] = a;
            osd[off + 1] = (uint8_t)((x * 3) & 0xff);
            osd[off + 2] = (uint8_t)((y * 5) & 0xff);
            osd[off + 3] = (uint8_t)(((x ^ y) * 7) & 0xff);
        }
    }
    const int32_t p0_w = 40, p0_h = 20, p0_stride = p0_w * 2;
    uint8_t* pic0 = (uint8_t*)malloc((size_t)(p0_h * p0_stride));
    for (int32_t y = 0; y < p0_h; ++y) {
        for (int32_t x = 0; x < p0_w; x += 2) {
            int32_t off = y * p0_stride + x * 2;
            pic0[off + 0] = (uint8_t)(80 + (x & 31));
            pic0[off + 1] = (uint8_t)(40 + y * 4);
            pic0[off + 2] = (uint8_t)(176 - (x & 31));
            pic0[off + 3] = (uint8_t)(50 + y * 4);
        }
    }
    const int32_t p1_w = 32, p1_h = 16, p1_stride = p1_w * 2;
    uint8_t* pic1 = (uint8_t*)malloc((size_t)(p1_h * p1_stride));
    for (int32_t y = 0; y < p1_h; ++y) {
        for (int32_t x = 0; x < p1_w; x += 2) {
            int32_t off = y * p1_stride + x * 2;
            pic1[off + 0] = 0x58u;
            pic1[off + 1] = (uint8_t)(90 + x + y);
            pic1[off + 2] = 0xa8u;
            pic1[off + 3] = (uint8_t)(100 + x + y);
        }
    }
    const int32_t diag_mat[9] = { 1300, 0, 0, 0, 1200, 0, 0, 0, 1100 };
    const int32_t full_mat[9] = { 1190, -110, 32, -55, 1130, -45, 22, -135, 1210 };

    const struct {
        int32_t out_w, out_h, mmode, use_osd, p1_alpha;
        uint32_t backdrop;
        const int32_t* mat;
    } cases[] = {
        { src_w, src_h, 0, 1, 255, 0xff212320u, NULL },
        { src_w, src_h, 1, 1, 180, 0xff212320u, diag_mat },
        { src_w, src_h, 2, 1, 180, 0xff212320u, full_mat },
        { src_w, src_h, 0, 1, 180, 0x804080c0u, NULL },
        { src_w, src_h, 0, 0, 180, 0x804080c0u, NULL },
        { src_w, src_h, 0, 0, 0,   0x40ff8040u, NULL },
        { 73,    29,    2, 1, 180, 0xff102030u, full_mat },
        { 73,    29,    0, 1, 180, 0x804080c0u, NULL },
        { 73,    29,    0, 0, 0,   0x60204080u, NULL },
    };

    for (size_t c = 0; c < sizeof(cases) / sizeof(cases[0]); ++c) {
        const int32_t ow = cases[c].out_w;
        const int32_t oh = cases[c].out_h;
        const int32_t n = ow * oh;
        int32_t* srows = (int32_t*)malloc((size_t)oh * sizeof(int32_t));
        int32_t* scols = (int32_t*)malloc((size_t)ow * sizeof(int32_t));
        for (int32_t r = 0; r < oh; ++r) srows[r] = ((2 * r + 1) * src_h) / (2 * oh);
        for (int32_t x = 0; x < ow; ++x) scols[x] = ((2 * x + 1) * src_w) / (2 * ow);

        const int32_t cfg[20] = {
            0, osd_stride,
            10, 4, p0_w, p0_h, p0_stride,
            34, 8, p1_w, p1_h, p1_stride,
            cases[c].p1_alpha, 0, (int32_t)cases[c].backdrop,
            src_w, src_h, ow, oh, cases[c].mmode
        };
        uint32_t* ref_argb = (uint32_t*)calloc((size_t)n, sizeof(uint32_t));
        uint32_t* ref_pre  = (uint32_t*)calloc((size_t)n, sizeof(uint32_t));
        uint32_t* got_pre  = (uint32_t*)calloc((size_t)n, sizeof(uint32_t));
        const uint8_t* osd_ptr = cases[c].use_osd ? osd : NULL;

        fuji_render_composite_planes_matrix(osd_ptr, pic0, pic1, srows, scols, cases[c].mat, cfg, ref_argb);
        int32_t exp_opaque = fuji_render_argb_to_pre(ref_argb, ref_pre, n);
        int32_t got_opaque = fuji_render_composite_lcdc_to_argb_pre(osd_ptr, pic0, pic1, srows, scols, cases[c].mat, cfg, got_pre);

        if (got_opaque != exp_opaque) {
            fprintf(stderr, "FAIL composite_lcdc_to_argb_pre case=%zu opaque got=%d exp=%d\n", c, got_opaque, exp_opaque);
            free(srows); free(scols); free(ref_argb); free(ref_pre); free(got_pre);
            free(osd); free(pic0); free(pic1);
            return 1;
        }
        for (int32_t i = 0; i < n; ++i) {
            if (got_pre[i] != ref_pre[i]) {
                fprintf(stderr, "FAIL composite_lcdc_to_argb_pre case=%zu idx=%d got=0x%08x exp=0x%08x\n",
                        c, i, got_pre[i], ref_pre[i]);
                free(srows); free(scols); free(ref_argb); free(ref_pre); free(got_pre);
                free(osd); free(pic0); free(pic1);
                return 1;
            }
        }
        free(srows); free(scols); free(ref_argb); free(ref_pre); free(got_pre);
    }
    free(osd); free(pic0); free(pic1);
    return 0;
}

static int test_isp_bayer_to_yuv_peaking_v2(void) {
    const int sw = 64, sh = 32, dw = 64, dh = 32, stride = dw * 2;
    uint8_t* raw = (uint8_t*)malloc((size_t)(sw * sh * 2));
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            /* Sharp vertical edge at x == 32 to trigger 5-tap HPF Focus Peaking */
            uint16_t v = (x < 32) ? 0x1000u : 0xF000u;
            int idx = (y * sw + x) * 2;
            raw[idx] = (uint8_t)(v & 0xffu);
            raw[idx + 1] = (uint8_t)(v >> 8);
        }
    }
    int32_t xmap[64];
    for (int x = 0; x < dw; ++x) xmap[x] = x;
    int32_t lut[4096];
    for (int i = 0; i < 4096; ++i) lut[i] = (i * 255) / 4095;
    int32_t hist[1024];
    uint8_t* uyvy = (uint8_t*)calloc((size_t)(dh * stride), 1);
    const int32_t cfg[19] = {
        sw, sh, 1, dw, dh, stride, 0, 0, 0, 0, dw, dh,
        1024, 1024, 1024, 8 /* peaking */, 10, 40,
        (76) | (0x55 << 8) | (0xFF << 16)
    };
    int32_t stamped = fuji_render_isp_bayer_to_yuv_peaking(raw, uyvy, NULL, xmap, NULL, lut, lut, lut, NULL, hist, cfg);
    int32_t hist_total = 0;
    for (int i = 0; i < 256; ++i) hist_total += hist[i];
    if (stamped <= 0 || hist_total != dw * dh) {
        fprintf(stderr, "FAIL isp_bayer_to_yuv_peaking_v2 stamped=%d hist_total=%d\n", stamped, hist_total);
        free(raw); free(uyvy);
        return 1;
    }
    free(raw); free(uyvy);
    return 0;
}

static int test_isp_bayer_border_cfa_parity(void) {
    /* BUG-V3-7-6: Uniform colored RGGB Bayer frame (R=3000, G=1800, B=600) must demosaic identically
     * on top/bottom/left/right border rows/columns as in the interior (no CFA parity inversion!). */
    const int sw = 32, sh = 16, dw = 32, dh = 16, stride = dw * 2;
    uint8_t* raw = (uint8_t*)malloc((size_t)(sw * sh * 2));
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            uint16_t v12 = ((y & 1) == 0 && (x & 1) == 0) ? 3000u
                         : ((y & 1) == 1 && (x & 1) == 1) ? 600u
                         : 1800u;
            uint16_t v16 = (uint16_t)(v12 << 4);
            int idx = (y * sw + x) * 2;
            raw[idx] = (uint8_t)(v16 & 0xffu);
            raw[idx + 1] = (uint8_t)(v16 >> 8);
        }
    }
    int32_t xmap[32];
    for (int x = 0; x < dw; ++x) xmap[x] = x;
    int32_t lut[4096];
    for (int i = 0; i < 4096; ++i) lut[i] = (i * 255) / 4095;
    uint8_t* uyvy = (uint8_t*)calloc((size_t)(dh * stride), 1);
    const int32_t cfg[19] = {
        sw, sh, 1, dw, dh, stride, 0, 0, 0, 0, dw, dh,
        1024, 1024, 1024, 0, 10, 0, 0
    };
    fuji_render_isp_bayer_to_yuv_peaking(raw, uyvy, NULL, xmap, NULL, lut, lut, lut, NULL, NULL, cfg);

    /* Interior reference macro-pixel at (x=10, y=8) */
    const uint8_t* ref = uyvy + 8 * stride + 10 * 2;
    const int check_coords[][2] = {
        {0, 0}, {sw - 2, 0}, {10, 0},
        {0, sh - 1}, {sw - 2, sh - 1}, {10, sh - 1},
        {0, 8}, {sw - 2, 8}
    };
    for (size_t i = 0; i < sizeof(check_coords) / sizeof(check_coords[0]); ++i) {
        int x = check_coords[i][0], y = check_coords[i][1];
        const uint8_t* p = uyvy + y * stride + x * 2;
        if (memcmp(p, ref, 4) != 0) {
            fprintf(stderr, "FAIL isp_bayer_border_cfa_parity (%d,%d) got=[%u,%u,%u,%u] exp=[%u,%u,%u,%u]\n",
                    x, y, p[0], p[1], p[2], p[3], ref[0], ref[1], ref[2], ref[3]);
            free(raw); free(uyvy);
            return 1;
        }
    }
    free(raw); free(uyvy);
    return 0;
}

int main(void) {
    printf("[test_render_bridge] abi=%d simd=%d backend=%s\n",
           fuji_render_abi_version(), fuji_render_simd_level(), fuji_render_backend_name());
    if (fuji_render_abi_version() != FUJI_RENDER_ABI_VERSION) return 1;
    if (test_fill_u32() != 0) return 1;
    if (test_morton_tile() != 0) return 1;
    if (test_filtered_morton() != 0) return 1;
    if (test_composite_1to1() != 0) return 1;
    if (test_argb_to_pre() != 0) return 1;
    if (test_linear_gradient_v2() != 0) return 1;
    if (test_composite_planes_matrix_v2() != 0) return 1;
    if (test_composite_lcdc_to_argb_pre() != 0) return 1;
    if (test_isp_bayer_to_yuv_peaking_v2() != 0) return 1;
    if (test_isp_bayer_border_cfa_parity() != 0) return 1;
    printf("[test_render_bridge] ALL PASS\n");
    return 0;
}
