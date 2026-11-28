#define _POSIX_C_SOURCE 200809L
#include "fuji_codec.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

static void fail(const char* msg) {
    fprintf(stderr, "[test_codec_bridge] FAIL: %s\n", msg);
    exit(1);
}

static void test_lsraw_roundtrip(int32_t width, int32_t height, int32_t blocks_x, int32_t strip_pixels,
                                 int32_t bits, int32_t lossy) {
    int32_t blocks_y = (height + 5) / 6;
    size_t raw_bytes = (size_t)width * (size_t)height * 2u;
    uint8_t* raw = (uint8_t*)malloc(raw_bytes);
    uint8_t* dec = (uint8_t*)malloc(raw_bytes);
    int32_t cap = (int32_t)(raw_bytes * 2u + 65536u);
    uint8_t* enc_1t = (uint8_t*)malloc((size_t)cap);
    uint8_t* enc_mt = (uint8_t*)malloc((size_t)cap);
    if (!raw || !dec || !enc_1t || !enc_mt) fail("OOM in test_lsraw_roundtrip");

    int32_t max_val = (1 << bits) - 1;
    for (int32_t y = 0; y < height; ++y) {
        for (int32_t x = 0; x < width; ++x) {
            int32_t c = ((y & 1) << 1) | (x & 1);
            int32_t v;
            if (c == 0) v = (x * 7 + y * 31) & max_val;
            else if (c == 1) v = ((x * 13 + y * 5) ^ 0x55) & max_val;
            else if (c == 2) v = ((x * 11 + y * 17) + 128) & max_val;
            else v = (((x / 64 + y / 6) & 1) == 0) ? (max_val * 3 / 4) : (max_val / 5);
            size_t off = ((size_t)y * (size_t)width + (size_t)x) * 2u;
            raw[off]     = (uint8_t)(v & 0xFF);
            raw[off + 1] = (uint8_t)((v >> 8) & 0xFF);
        }
    }

    int32_t req1 = 0, reqm = 0;
    int32_t sz_1t = fuji_lsraw_encode_bayer(
        raw, width * 2, width, height, blocks_x, blocks_y, strip_pixels, bits, lossy, 0, 1, enc_1t, cap, &req1
    );
    int32_t sz_mt = fuji_lsraw_encode_bayer(
        raw, width * 2, width, height, blocks_x, blocks_y, strip_pixels, bits, lossy, 0, 8, enc_mt, cap, &reqm
    );
    if (sz_mt > 0) {
        if (enc_mt[2] != (lossy ? 0 : 1) || enc_mt[3] != 0) fail("invalid Fuji Bayer IS header");
        uint8_t sentinel[32];
        memset(sentinel, 0xA5, sizeof(sentinel));
        int32_t required = 0;
        int32_t short_rc = fuji_lsraw_encode_bayer(raw, width * 2, width, height, blocks_x, blocks_y,
            strip_pixels, bits, lossy, 32, 1, sentinel, sizeof(sentinel), &required);
        if (short_rc != FUJI_CODEC_ERR_OVERFLOW || required != sz_mt) fail("incorrect LSRAW overflow size");
        for (size_t j = 0; j < sizeof(sentinel); ++j) {
            if (sentinel[j] != 0xA5) fail("LSRAW overflow changed destination");
        }
    }

    if (sz_1t <= 0 || sz_1t != sz_mt || sz_1t != req1 || sz_mt != reqm) {
        fail("LSRAW 1T vs MT encoded size mismatch");
    }
    if (memcmp(enc_1t, enc_mt, (size_t)sz_1t) != 0) {
        fail("LSRAW 1T vs MT encoded bytes differ!");
    }

    int32_t meta[5] = {0};
    int32_t dec_sz = fuji_lsraw_decode_bayer(enc_mt, sz_mt, width * 2, 8, dec, (int32_t)raw_bytes, meta);
    if (dec_sz != (int32_t)raw_bytes) fail("LSRAW decode byte count mismatch");
    if (meta[0] != width || meta[1] != height || meta[2] != bits || meta[3] != (lossy ? 2 : 1) || meta[4] != blocks_x) {
        fail("LSRAW decode metadata mismatch");
    }
    if (!lossy) {
        if (memcmp(raw, dec, raw_bytes) != 0) fail("LSRAW lossless round-trip not bit-exact!");
    } else {
        for (size_t i = 0; i < raw_bytes; i += 2) {
            int32_t orig_v = (int32_t)raw[i] | ((int32_t)raw[i + 1] << 8);
            int32_t dec_v  = (int32_t)dec[i] | ((int32_t)dec[i + 1] << 8);
            int32_t diff = orig_v - dec_v;
            if (diff < 0) diff = -diff;
            if (diff > 3) fail("LSRAW compressed round-trip error > 3 LSBs!");
        }
    }

    free(raw);
    free(dec);
    free(enc_1t);
    free(enc_mt);
}

static void test_jpeg_roundtrip(void) {
    int32_t caps = fuji_codec_capabilities();
    if ((caps & (FUJI_CODEC_CAP_JPEG_LIBJPEG8 | FUJI_CODEC_CAP_JPEG_LIBJPEG62 | FUJI_CODEC_CAP_JPEG_TURBO)) == 0) {
        printf("[test_codec_bridge] Skipping native JPEG test (no libjpeg/libturbojpeg on host)\n");
        return;
    }

    const int32_t w = 320, h = 240;
    const int32_t stride = w * 2;
    uint8_t* uyvy = (uint8_t*)malloc((size_t)stride * (size_t)h);
    if (!uyvy) fail("OOM");
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; x += 2) {
            uint8_t* p = uyvy + (size_t)y * (size_t)stride + (size_t)x * 2u;
            if (y < h / 2 && x < w / 2) {
                /* Reddish quadrant */
                p[0] = 90; p[1] = 110; p[2] = 210; p[3] = 110;
            } else if (y < h / 2) {
                /* Greenish quadrant */
                p[0] = 80; p[1] = 150; p[2] = 70; p[3] = 150;
            } else if (x < w / 2) {
                /* Blueish quadrant */
                p[0] = 220; p[1] = 80; p[2] = 100; p[3] = 80;
            } else {
                /* Yellowish quadrant */
                p[0] = 50; p[1] = 210; p[2] = 150; p[3] = 210;
            }
        }
    }

    uint8_t* jpeg = NULL;
    int32_t actual = 0;
    int32_t sz = fuji_jpeg_encode_uyvy_alloc(uyvy, w, h, stride, 95, 256 * 1024, &jpeg, &actual);
    if (sz <= 100 || !jpeg || sz != actual) fail("fuji_jpeg_encode_uyvy_alloc failed");
    if (jpeg[0] != 0xFF || jpeg[1] != 0xD8 || jpeg[sz - 2] != 0xFF || jpeg[sz - 1] != 0xD9) {
        fail("Encoded JPEG missing SOI/EOI markers");
    }

    /* Verify overflow reporting */
    uint8_t* ov_buf = NULL;
    int32_t ov_actual = 0;
    int32_t ov_rc = fuji_jpeg_encode_uyvy_alloc(uyvy, w, h, stride, 95, 64, &ov_buf, &ov_actual);
    if (ov_rc != FUJI_CODEC_ERR_OVERFLOW || ov_buf != NULL || ov_actual != sz) {
        fail("fuji_jpeg_encode_uyvy_alloc overflow contract failed");
    }

    /* Probe header + decode 1:1 */
    int32_t probe[7] = {0};
    if (fuji_jpeg_probe_header(jpeg, sz, 0, 0, 0, 0, 0, 0, probe) != 0) fail("probe_header failed");
    if (probe[0] != w || probe[1] != h || probe[2] != w || probe[3] != h || probe[6] != 0) {
        fail("probe_header geometry mismatch (expected 4:2:2)");
    }

    uint8_t* dec_uyvy = (uint8_t*)malloc((size_t)probe[5]);
    int32_t dec_meta[5] = {0};
    int32_t dec_rc = fuji_jpeg_decode_to_uyvy(jpeg, sz, 0, 0, 0, 0, 0, 0, dec_uyvy, probe[5], dec_meta);
    if (dec_rc != probe[5]) fail("decode_to_uyvy 1:1 failed");

    /* Check bottom-right yellow quadrant center pixel matches closely */
    const uint8_t* q4 = dec_uyvy + (size_t)(h * 3 / 4) * (size_t)dec_meta[2] + (size_t)(w * 3 / 4) * 2u;
    if (abs((int)q4[0] - 50) > 10 || abs((int)q4[1] - 210) > 10 || abs((int)q4[2] - 150) > 10) {
        fail("decode_to_uyvy 1:1 yellow quadrant color drift > 10");
    }

    /* Test 1/2 IDCT downscale + ROI crop on bottom-right quadrant */
    int32_t crop_probe[7] = {0};
    if (fuji_jpeg_probe_header(jpeg, sz, 1, 1, w / 2, w - 1, h / 2, h - 1, crop_probe) != 0) {
        fail("crop probe_header failed");
    }
    if (crop_probe[2] != w / 4 || crop_probe[3] != h / 4) fail("crop+downscale geometry mismatch");
    uint8_t* crop_uyvy = (uint8_t*)malloc((size_t)crop_probe[5]);
    int32_t crop_meta[5] = {0};
    if (fuji_jpeg_decode_to_uyvy(jpeg, sz, 1, 1, w / 2, w - 1, h / 2, h - 1, crop_uyvy, crop_probe[5], crop_meta) != crop_probe[5]) {
        fail("decode_to_uyvy crop+downscale failed");
    }
    const uint8_t* cq = crop_uyvy + (size_t)(crop_meta[1] / 2) * (size_t)crop_meta[2] + (size_t)(crop_meta[0] / 2) * 2u;
    if (abs((int)cq[0] - 50) > 12 || abs((int)cq[1] - 210) > 12 || abs((int)cq[2] - 150) > 12) {
        fail("decode_to_uyvy crop+downscale yellow quadrant color drift > 12");
    }

    /* Test Milbeaut black margin sanitization (0x00000000, [00 00 80 80], [80 80 00 00]) */
    uint8_t black_uyvy[16 * 16 * 2];
    memset(black_uyvy, 0, sizeof(black_uyvy));
    for (int i = 0; i < (int)sizeof(black_uyvy); i += 4) {
        if (((i / 4) % 3) == 1) { black_uyvy[i + 2] = 0x80; black_uyvy[i + 3] = 0x80; }
        else if (((i / 4) % 3) == 2) { black_uyvy[i] = 0x80; black_uyvy[i + 1] = 0x80; }
    }
    uint8_t* blk_jpeg = NULL;
    int32_t blk_sz = fuji_jpeg_encode_uyvy_alloc(black_uyvy, 16, 16, 32, 92, 65536, &blk_jpeg, &actual);
    if (blk_sz <= 0 || !blk_jpeg) fail("black margin encode failed");
    uint8_t blk_dec[16 * 16 * 2];
    if (fuji_jpeg_decode_to_uyvy(blk_jpeg, blk_sz, 0, 0, 0, 0, 0, 0, blk_dec, (int32_t)sizeof(blk_dec), NULL) != (int32_t)sizeof(blk_dec)) {
        fail("black margin decode failed");
    }
    const uint8_t* bc = blk_dec + 8 * 32 + 8 * 2;
    if (bc[1] > 8 || abs((int)bc[0] - 128) > 8 || abs((int)bc[2] - 128) > 8) {
        fail("Milbeaut black margin decoded non-black (green regression!)");
    }

    /* Test BGR24 optical sensor encode */
    uint8_t bgr[64 * 48 * 3];
    for (int i = 0; i < 64 * 48; ++i) {
        bgr[i * 3 + 0] = 40;  /* B */
        bgr[i * 3 + 1] = 180; /* G */
        bgr[i * 3 + 2] = 220; /* R */
    }
    uint8_t* bgr_jpeg = NULL;
    int32_t bgr_sz = fuji_jpeg_encode_bgr24_alloc(bgr, 64, 48, 64 * 3, 92, 65536, &bgr_jpeg, &actual);
    if (bgr_sz <= 100 || !bgr_jpeg) fail("fuji_jpeg_encode_bgr24_alloc failed");

    /* Test corrupted JPEG cleanly returns FUJI_CODEC_ERR_CODEC without crashing */
    uint8_t bad[6] = { 0xFF, 0xD8, 0x12, 0x34, 0x56, 0x78 };
    if (fuji_jpeg_decode_to_uyvy(bad, 6, 0, 0, 0, 0, 0, 0, blk_dec, (int32_t)sizeof(blk_dec), NULL) >= 0) {
        fail("Corrupt JPEG must fail cleanly");
    }

    /* Test Milbeaut YYUV422 ([Y0, Y1, Cb, Cr]) encode/decode round-trip + black margin sanitization */
    uint8_t* yyuv = (uint8_t*)malloc((size_t)stride * (size_t)h);
    if (!yyuv) fail("OOM yyuv");
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; x += 2) {
            uint8_t* p = yyuv + (size_t)y * (size_t)stride + (size_t)x * 2u;
            if (x < 16) {
                /* 0x80800000 native YYUV422 neutral black margin ([00 00 80 80]) */
                p[0] = 0x00; p[1] = 0x00; p[2] = 0x80; p[3] = 0x80;
            } else if (x < 32) {
                /* 0x7e7f3e41 50%-blended film-strip neutral dark gray ([41 3e 7f 7e]) */
                p[0] = 0x41; p[1] = 0x3e; p[2] = 0x7f; p[3] = 0x7e;
            } else {
                /* Yellowish interior: Y=210, Cb=50, Cr=150 */
                p[0] = 210; p[1] = 210; p[2] = 50; p[3] = 150;
            }
        }
    }
    uint8_t* yyuv_jpeg = NULL;
    int32_t yyuv_sz = fuji_jpeg_encode_yyuv_alloc(yyuv, w, h, stride, 95, 256 * 1024, &yyuv_jpeg, &actual);
    if (yyuv_sz <= 100 || !yyuv_jpeg || yyuv_sz != actual) fail("fuji_jpeg_encode_yyuv_alloc failed");
    uint8_t* yyuv_dec = (uint8_t*)malloc((size_t)stride * (size_t)h);
    int32_t yyuv_meta[5] = {0};
    if (fuji_jpeg_decode_to_yyuv(yyuv_jpeg, yyuv_sz, 0, 0, 0, 0, 0, 0, yyuv_dec, stride * h, yyuv_meta) != stride * h) {
        fail("fuji_jpeg_decode_to_yyuv failed");
    }
    const uint8_t* ym = yyuv_dec + (size_t)(h / 2) * (size_t)yyuv_meta[2] + (size_t)6 * 2u;
    if (ym[0] > 8 || ym[1] > 8 || abs((int)ym[2] - 128) > 8 || abs((int)ym[3] - 128) > 8) {
        fail("YYUV422 0x80800000 black margin decoded non-black");
    }
    const uint8_t* yf = yyuv_dec + (size_t)(h / 2) * (size_t)yyuv_meta[2] + (size_t)24 * 2u;
    if (abs((int)yf[0] - 64) > 10 || abs((int)yf[2] - 128) > 8 || abs((int)yf[3] - 128) > 8) {
        fail("YYUV422 0x7e7f3e41 film-strip gray drifted");
    }
    const uint8_t* yi = yyuv_dec + (size_t)(h / 2) * (size_t)yyuv_meta[2] + (size_t)(w * 3 / 4) * 2u;
    if (abs((int)yi[0] - 210) > 10 || abs((int)yi[2] - 50) > 10 || abs((int)yi[3] - 150) > 10) {
        fail("YYUV422 interior yellow drifted");
    }
    fuji_codec_free(yyuv_jpeg);
    free(yyuv);
    free(yyuv_dec);

    fuji_codec_free(jpeg);
    fuji_codec_free(blk_jpeg);
    fuji_codec_free(bgr_jpeg);
    free(uyvy);
    free(dec_uyvy);
    free(crop_uyvy);
}

static int parse_sof0_dims(const uint8_t* jpeg, int32_t len, int32_t* out_w, int32_t* out_h) {
    if (!jpeg || len < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8 || jpeg[len - 2] != 0xFF || jpeg[len - 1] != 0xD9) {
        return 0;
    }
    int32_t i = 2;
    while (i + 4 <= len) {
        if (jpeg[i] != 0xFF) return 0;
        while (i < len && jpeg[i] == 0xFF) i++;
        if (i >= len) return 0;
        uint8_t marker = jpeg[i++];
        if (marker == 0xD9 || marker == 0xDA) break;
        if (marker == 0x00 || (marker >= 0xD0 && marker <= 0xD7)) continue;
        if (i + 2 > len) return 0;
        int32_t seg_len = ((int32_t)jpeg[i] << 8) | (int32_t)jpeg[i + 1];
        if (seg_len < 2 || i + seg_len > len) return 0;
        if (marker == 0xC0 && seg_len >= 7) {
            *out_h = ((int32_t)jpeg[i + 3] << 8) | (int32_t)jpeg[i + 4];
            *out_w = ((int32_t)jpeg[i + 5] << 8) | (int32_t)jpeg[i + 6];
            return 1;
        }
        i += seg_len;
    }
    return 0;
}

static void test_streaming_scaled_bgr24_and_102mp(void) {
    int32_t caps = fuji_codec_capabilities();
    if ((caps & FUJI_CODEC_CAP_JPEG_BUILTIN) == 0) {
        fail("FUJI_CODEC_CAP_JPEG_BUILTIN must always be advertised");
    }

    const int32_t src_w = 1024, src_h = 576;
    uint8_t* src_bgr = (uint8_t*)malloc((size_t)src_w * (size_t)src_h * 3u);
    if (!src_bgr) fail("OOM src_bgr");
    for (int32_t y = 0; y < src_h; ++y) {
        for (int32_t x = 0; x < src_w; ++x) {
            size_t p = ((size_t)y * (size_t)src_w + (size_t)x) * 3u;
            /* Colored test pattern: warm red top, cool blue bottom, bright green center */
            int in_center = (x >= 384 && x < 640 && y >= 192 && y < 384);
            if (in_center) {
                src_bgr[p + 0] = 40;  src_bgr[p + 1] = 220; src_bgr[p + 2] = 60;
            } else if (y < src_h / 2) {
                src_bgr[p + 0] = 30;  src_bgr[p + 1] = 70;  src_bgr[p + 2] = 220;
            } else {
                src_bgr[p + 0] = 220; src_bgr[p + 1] = 110; src_bgr[p + 2] = 30;
            }
        }
    }

    /* 1. Small 4:3 scaled encode + color verification */
    int32_t small_cap = 2 * 1024 * 1024;
    uint8_t* small_jpeg = (uint8_t*)malloc((size_t)small_cap);
    if (!small_jpeg) fail("OOM small_jpeg");
    int32_t small_len = 0;
    int32_t rc_small = fuji_codec_jpeg_encode_scaled_bgr24(
        src_bgr, src_w, src_h, 128, 0, 768, 576, 640, 480, 92, small_jpeg, small_cap, &small_len
    );
    if (rc_small <= 100 || rc_small != small_len) fail("scaled 640x480 encode failed");
    int32_t parsed_w = 0, parsed_h = 0;
    if (!parse_sof0_dims(small_jpeg, small_len, &parsed_w, &parsed_h) || parsed_w != 640 || parsed_h != 480) {
        fail("scaled 640x480 SOF0 dimensions mismatch");
    }

    /* Overflow check */
    int32_t ov_len = 0;
    int32_t rc_ov = fuji_codec_jpeg_encode_scaled_bgr24(
        src_bgr, src_w, src_h, 128, 0, 768, 576, 640, 480, 92, small_jpeg, 128, &ov_len
    );
    if (rc_ov != FUJI_CODEC_ERR_OVERFLOW || ov_len != small_len) {
        fail("scaled encode overflow contract mismatch");
    }

    if (caps & (FUJI_CODEC_CAP_JPEG_LIBJPEG8 | FUJI_CODEC_CAP_JPEG_LIBJPEG62 | FUJI_CODEC_CAP_JPEG_TURBO)) {
        int32_t probe[7] = {0};
        if (fuji_jpeg_probe_header(small_jpeg, small_len, 0, 0, 0, 0, 0, 0, probe) != 0 ||
            probe[0] != 640 || probe[1] != 480) {
            fail("probe_header failed on scaled 640x480 JPEG");
        }
        uint8_t* dec = (uint8_t*)malloc((size_t)probe[5]);
        int32_t dec_meta[5] = {0};
        if (fuji_jpeg_decode_to_uyvy(small_jpeg, small_len, 0, 0, 0, 0, 0, 0, dec, probe[5], dec_meta) != probe[5]) {
            fail("decode_to_uyvy failed on scaled 640x480 JPEG");
        }
        const uint8_t* c = dec + (size_t)240 * (size_t)dec_meta[2] + (size_t)320 * 2u;
        /* Bright green center: Y should be high (>140), Cb/Cr low (<115) */
        if (c[1] < 140 || c[0] > 115 || c[2] > 115) {
            fail("scaled 640x480 center green color mismatch");
        }
        free(dec);
    }
    free(small_jpeg);

    /* 2. Full 102MP 11648x8736 streaming scaled encode benchmark (<600 ms!) */
    int32_t big_cap = 32 * 1024 * 1024;
    uint8_t* big_jpeg = (uint8_t*)malloc((size_t)big_cap);
    if (!big_jpeg) fail("OOM big_jpeg");
    int32_t big_len = 0;
    double t0 = now_ms();
    int32_t rc_big = fuji_codec_jpeg_encode_scaled_bgr24(
        src_bgr, src_w, src_h, 128, 0, 768, 576, 11648, 8736, 90, big_jpeg, big_cap, &big_len
    );
    double elapsed_ms = now_ms() - t0;
    if (rc_big <= 1024 || rc_big != big_len) fail("scaled 11648x8736 encode failed");
    if (!parse_sof0_dims(big_jpeg, big_len, &parsed_w, &parsed_h) || parsed_w != 11648 || parsed_h != 8736) {
        fail("scaled 11648x8736 SOF0 dimensions mismatch");
    }
    printf("[test_codec_bridge] 102MP scaled BGR24->JPEG (11648x8736): %d bytes in %.1f ms (backend=%s)\n",
           big_len, elapsed_ms, fuji_codec_backend_name());
    if (elapsed_ms >= 600.0) {
        fail("102MP scaled JPEG encode exceeded 600 ms budget");
    }

    free(big_jpeg);
    free(src_bgr);
}

static void test_scaled_bgr_repeated_rows_match_expanded_pixels(void) {
    /* Both inputs exceed the built-in encoder threshold. The second has the same
     * pixels but a different source row for every output row. */
    enum { src_w = 79, src_h = 51, w = 2048, h = 1955, cap = w * h * 3 };
    uint8_t src[src_h][src_w][3];
    uint8_t* expanded = malloc(cap);
    uint8_t* scaled_jpeg = malloc(cap);
    uint8_t* expanded_jpeg = malloc(cap);
    if (!expanded || !scaled_jpeg || !expanded_jpeg) fail("scaled comparison allocation failed");
    for (int y = 0; y < src_h; ++y) {
        for (int x = 0; x < src_w; ++x) {
            for (int c = 0; c < 3; ++c) {
                src[y][x][c] = x < src_w / 2 ? (uint8_t)(x * (c + 1)) : (uint8_t)(y * 19 + x * 31 + c * 59);
            }
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            memcpy(expanded + (y * w + x) * 3, src[1 + y * (src_h - 2) / h][1 + x * (src_w - 2) / w], 3);
        }
    }
    int32_t scaled_len = 0, expanded_len = 0;
    if (fuji_codec_jpeg_encode_scaled_bgr24(&src[0][0][0], src_w, src_h, 1, 1, src_w - 2, src_h - 2,
                                            w, h, 91, scaled_jpeg, cap, &scaled_len) <= 0 ||
        fuji_codec_jpeg_encode_scaled_bgr24(expanded, w, h, 0, 0, w, h,
                                            w, h, 91, expanded_jpeg, cap, &expanded_len) <= 0 ||
        scaled_len != expanded_len || memcmp(scaled_jpeg, expanded_jpeg, scaled_len) != 0) {
        fail("repeated-row DCT changed the JPEG bitstream");
    }
    free(expanded_jpeg);
    free(scaled_jpeg);
    free(expanded);
}

int main(void) {
    if (fuji_codec_abi_version() != FUJI_CODEC_ABI_VERSION) fail("ABI version mismatch");
    int32_t caps = fuji_codec_capabilities();
    printf("[test_codec_bridge] backend=%s caps=0x%x threads=%d\n",
           fuji_codec_backend_name(), (unsigned)caps, fuji_codec_default_threads());

    /* LSRAW tests across 12/14/16-bit, lossless + compressed, multi-strip + partial edge strips */
    test_lsraw_roundtrip(3000, 24, 4, 768, 12, 0);
    test_lsraw_roundtrip(3000, 24, 4, 768, 14, 0);
    test_lsraw_roundtrip(3000, 24, 4, 768, 16, 0);
    test_lsraw_roundtrip(1536, 48, 8, 192, 16, 1);
    test_lsraw_roundtrip(1024, 96, 8, 128, 14, 0);

    test_jpeg_roundtrip();
    test_scaled_bgr_repeated_rows_match_expanded_pixels();
    test_streaming_scaled_bgr24_and_102mp();

    printf("[test_codec_bridge] ALL PASSED\n");
    return 0;
}
