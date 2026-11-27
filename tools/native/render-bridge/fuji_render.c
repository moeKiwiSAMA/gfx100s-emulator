#include "fuji_render.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#if defined(__x86_64__) || defined(_M_X64)
  #include <cpuid.h>
  #include <immintrin.h>
  #define FUJI_X86_64 1
  #if defined(__GNUC__) || defined(__clang__)
    #define FUJI_HAS_AVX512_TARGET 1
    #define FUJI_AVX512_TARGET __attribute__((target("avx512f,avx512bw,avx512vl,avx512dq")))
  #else
    #define FUJI_HAS_AVX512_TARGET 0
  #endif
#else
  #define FUJI_X86_64 0
  #define FUJI_HAS_AVX512_TARGET 0
#endif

/* ------------------------------------------------------------------------- */
/* Runtime CPUID + OSXSAVE AVX2 / AVX-512 detection (zero SIGILL on SSE2)    */
/* ------------------------------------------------------------------------- */

static int g_simd_level = -1;

static inline int apply_simd_max_env(int detected) {
    const char* env = getenv("FUJI_RENDER_SIMD_MAX");
    if (env != NULL && env[0] >= '0' && env[0] <= '3' && env[1] == '\0') {
        int cap = env[0] - '0';
        if (cap < detected) return cap;
    }
    return detected;
}

static int detect_simd_level(void) {
#if FUJI_X86_64
    unsigned int eax = 0, ebx = 0, ecx = 0, edx = 0;
    if (!__get_cpuid(0, &eax, &ebx, &ecx, &edx) || eax < 7u) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
    }
    if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
    }
    /* OSXSAVE (bit 27) and AVX (bit 28) */
    const unsigned int osxsave_avx = (1u << 27) | (1u << 28);
    if ((ecx & osxsave_avx) != osxsave_avx) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
    }
    unsigned int xcr0_lo = 0, xcr0_hi = 0;
    __asm__ __volatile__(".byte 0x0f, 0x01, 0xd0" : "=a"(xcr0_lo), "=d"(xcr0_hi) : "c"(0));
    /* XMM (bit 1) + YMM (bit 2) state must be OS-enabled */
    if ((xcr0_lo & 0x6u) != 0x6u) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
    }
    if (!__get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx)) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
    }
#if FUJI_HAS_AVX512_TARGET
    /* XCR0: XMM(1) | YMM(2) | opmask(5) | ZMM_Hi256(6) | Hi16_ZMM(7) == 0xE6 */
    const unsigned int avx512_xcr0 = 0xE6u;
    /* Leaf 7 EBX: AVX2(5) | AVX512F(16) | AVX512DQ(17) | AVX512BW(30) | AVX512VL(31) */
    const unsigned int avx512_ebx = (1u << 5) | (1u << 16) | (1u << 17) | (1u << 30) | (1u << 31);
    if ((xcr0_lo & avx512_xcr0) == avx512_xcr0 && (ebx & avx512_ebx) == avx512_ebx) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_AVX512);
    }
#endif
    /* Leaf 7 EBX bit 5 = AVX2 */
    if ((ebx & (1u << 5)) != 0u) {
        return apply_simd_max_env(FUJI_RENDER_SIMD_AVX2);
    }
    return apply_simd_max_env(FUJI_RENDER_SIMD_SSE2);
#else
    return apply_simd_max_env(FUJI_RENDER_SIMD_SCALAR);
#endif
}

static inline int get_simd_level(void) {
    int level = g_simd_level;
    if (__builtin_expect(level < 0, 0)) {
        level = detect_simd_level();
        g_simd_level = level;
    }
    return level;
}

FUJI_RENDER_EXPORT int32_t fuji_render_abi_version(void) {
    return FUJI_RENDER_ABI_VERSION;
}

FUJI_RENDER_EXPORT int32_t fuji_render_simd_level(void) {
    return get_simd_level();
}

FUJI_RENDER_EXPORT const char* fuji_render_backend_name(void) {
    switch (get_simd_level()) {
        case FUJI_RENDER_SIMD_AVX512: return "fuji-render-avx512";
        case FUJI_RENDER_SIMD_AVX2:   return "fuji-render-avx2";
        case FUJI_RENDER_SIMD_SSE2:   return "fuji-render-sse2";
        default:                      return "fuji-render-scalar";
    }
}

/* ------------------------------------------------------------------------- */
/* Exact scalar helpers (bit-identical to AlphaBlendMath & LcdcFirstScanout) */
/* ------------------------------------------------------------------------- */

static inline uint32_t load_u32_le(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

static inline void store_u32_le(uint8_t* p, uint32_t v) {
    memcpy(p, &v, sizeof(v));
}

static inline uint32_t ror32_8(uint32_t v) {
    return (v >> 8) | (v << 24);
}

static inline uint32_t div255_rounded_u32(uint32_t x) {
    uint32_t t = x + 128u;
    return (t + (t >> 8)) >> 8;
}

static inline uint32_t clamp_u8_from_scaled8(int32_t scaled) {
    if (scaled <= 0) return 0u;
    if (scaled >= 65280) return 255u;
    return (uint32_t)(scaled >> 8);
}

static inline int is_uyvy_black_margin_word(uint32_t word) {
    /* 0x00000000 (unwritten DRAM) + GFX100S 2.13 section5@0x021cdea0 / section6@0x0436d200
     * Milbeaut Playback pillarbox/letterbox black fill words: 0x80800000 ([00 00 80 80]) and
     * 0x00008080 ([80 80 00 00]). Bit-identical to LcdcFirstScanout.yuv422UyvyToRgb. */
    return word == 0u || word == 0x80800000u || word == 0x00008080u;
}

static inline uint32_t uyvy_pair_to_rgb(const uint8_t* pair, int odd_pixel) {
    uint32_t word = load_u32_le(pair);
    if (is_uyvy_black_margin_word(word)) return 0u;
    int32_t u = (int32_t)(word & 0xffu);
    int32_t y = (int32_t)(odd_pixel ? ((word >> 24) & 0xffu) : ((word >> 8) & 0xffu));
    int32_t v = (int32_t)((word >> 16) & 0xffu);
    int32_t d = u - 128;
    int32_t e = v - 128;
    int32_t y256 = y << 8;
    uint32_t r = clamp_u8_from_scaled8(y256 + 359 * e + 128);
    uint32_t g = clamp_u8_from_scaled8(y256 - 88 * d - 183 * e + 128);
    uint32_t b = clamp_u8_from_scaled8(y256 + 454 * d + 128);
    return (r << 16) | (g << 8) | b;
}

static inline void uyvy_pair_to_two_rgb(const uint8_t* pair, uint32_t* rgb0, uint32_t* rgb1) {
    uint32_t word = load_u32_le(pair);
    if (is_uyvy_black_margin_word(word)) {
        *rgb0 = 0u;
        *rgb1 = 0u;
        return;
    }
    int32_t u  = (int32_t)(word & 0xffu);
    int32_t y0 = (int32_t)((word >> 8) & 0xffu);
    int32_t v  = (int32_t)((word >> 16) & 0xffu);
    int32_t y1 = (int32_t)((word >> 24) & 0xffu);
    int32_t d = u - 128;
    int32_t e = v - 128;
    int32_t cr = 359 * e + 128;
    int32_t cg = -88 * d - 183 * e + 128;
    int32_t cb = 454 * d + 128;
    int32_t y0_256 = y0 << 8;
    int32_t y1_256 = y1 << 8;
    *rgb0 = (clamp_u8_from_scaled8(y0_256 + cr) << 16) |
            (clamp_u8_from_scaled8(y0_256 + cg) << 8) |
            clamp_u8_from_scaled8(y0_256 + cb);
    *rgb1 = (clamp_u8_from_scaled8(y1_256 + cr) << 16) |
            (clamp_u8_from_scaled8(y1_256 + cg) << 8) |
            clamp_u8_from_scaled8(y1_256 + cb);
}

static inline uint32_t apply_pic1_alpha(uint32_t rgb, int32_t pic1_alpha) {
    if (pic1_alpha >= 255) {
        return 0xff000000u | rgb;
    }
    uint32_t a = (uint32_t)pic1_alpha;
    uint32_t pr = div255_rounded_u32(((rgb >> 16) & 0xffu) * a);
    uint32_t pg = div255_rounded_u32(((rgb >> 8) & 0xffu) * a);
    uint32_t pb = div255_rounded_u32((rgb & 0xffu) * a);
    return 0xff000000u | (pr << 16) | (pg << 8) | pb;
}

static inline uint32_t blend_osd_over_bg(uint32_t argb, uint32_t bg) {
    uint32_t a = argb >> 24;
    if (a == 255u) return argb;
    if (a == 0u) return bg;
    uint32_t inv = 255u - a;
    uint32_t out_r = div255_rounded_u32(((argb >> 16) & 0xffu) * a + ((bg >> 16) & 0xffu) * inv);
    uint32_t out_g = div255_rounded_u32(((argb >> 8) & 0xffu) * a + ((bg >> 8) & 0xffu) * inv);
    uint32_t out_b = div255_rounded_u32((argb & 0xffu) * a + (bg & 0xffu) * inv);
    return 0xff000000u | (out_r << 16) | (out_g << 8) | out_b;
}

static inline uint32_t blend_osd_over_black(uint32_t argb) {
    uint32_t a = argb >> 24;
    if (a == 255u) return argb;
    if (a == 0u) return 0xff000000u;
    uint32_t out_r = div255_rounded_u32(((argb >> 16) & 0xffu) * a);
    uint32_t out_g = div255_rounded_u32(((argb >> 8) & 0xffu) * a);
    uint32_t out_b = div255_rounded_u32((argb & 0xffu) * a);
    return 0xff000000u | (out_r << 16) | (out_g << 8) | out_b;
}

/* ------------------------------------------------------------------------- */
/* Kernel 1: fuji_render_fill_u32 (SSE2 + AVX2 + AVX-512)                    */
/* ------------------------------------------------------------------------- */

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void fill_u32_avx512(uint8_t* dst, int32_t byte_count, uint32_t pattern_le) {
    int32_t i = 0;
    const __m512i v = _mm512_set1_epi32((int)pattern_le);
    const int32_t limit256 = byte_count & ~255;
    for (; i < limit256; i += 256) {
        _mm512_storeu_si512((void*)(dst + i),       v);
        _mm512_storeu_si512((void*)(dst + i + 64),  v);
        _mm512_storeu_si512((void*)(dst + i + 128), v);
        _mm512_storeu_si512((void*)(dst + i + 192), v);
    }
    const int32_t limit64 = byte_count & ~63;
    for (; i < limit64; i += 64) {
        _mm512_storeu_si512((void*)(dst + i), v);
    }
    const int32_t limit4 = byte_count & ~3;
    for (; i < limit4; i += 4) {
        store_u32_le(dst + i, pattern_le);
    }
    for (; i < byte_count; ++i) {
        dst[i] = (uint8_t)((pattern_le >> ((i & 3) * 8)) & 0xffu);
    }
}
#endif

__attribute__((target("avx2")))
static void fill_u32_avx2(uint8_t* dst, int32_t byte_count, uint32_t pattern_le) {
    int32_t i = 0;
    const __m256i v = _mm256_set1_epi32((int)pattern_le);
    const int32_t limit128 = byte_count & ~127;
    for (; i < limit128; i += 128) {
        _mm256_storeu_si256((__m256i*)(dst + i),      v);
        _mm256_storeu_si256((__m256i*)(dst + i + 32), v);
        _mm256_storeu_si256((__m256i*)(dst + i + 64), v);
        _mm256_storeu_si256((__m256i*)(dst + i + 96), v);
    }
    const int32_t limit32 = byte_count & ~31;
    for (; i < limit32; i += 32) {
        _mm256_storeu_si256((__m256i*)(dst + i), v);
    }
    const int32_t limit4 = byte_count & ~3;
    for (; i < limit4; i += 4) {
        store_u32_le(dst + i, pattern_le);
    }
    for (; i < byte_count; ++i) {
        dst[i] = (uint8_t)((pattern_le >> ((i & 3) * 8)) & 0xffu);
    }
}

static void fill_u32_sse2(uint8_t* dst, int32_t byte_count, uint32_t pattern_le) {
    int32_t i = 0;
    const __m128i v = _mm_set1_epi32((int)pattern_le);
    const int32_t limit64 = byte_count & ~63;
    for (; i < limit64; i += 64) {
        _mm_storeu_si128((__m128i*)(dst + i),      v);
        _mm_storeu_si128((__m128i*)(dst + i + 16), v);
        _mm_storeu_si128((__m128i*)(dst + i + 32), v);
        _mm_storeu_si128((__m128i*)(dst + i + 48), v);
    }
    const int32_t limit16 = byte_count & ~15;
    for (; i < limit16; i += 16) {
        _mm_storeu_si128((__m128i*)(dst + i), v);
    }
    const int32_t limit4 = byte_count & ~3;
    for (; i < limit4; i += 4) {
        store_u32_le(dst + i, pattern_le);
    }
    for (; i < byte_count; ++i) {
        dst[i] = (uint8_t)((pattern_le >> ((i & 3) * 8)) & 0xffu);
    }
}
#endif

FUJI_RENDER_EXPORT void fuji_render_fill_u32(uint8_t* dst, int32_t byte_count, uint32_t pattern_le) {
    if (byte_count <= 0 || dst == NULL) return;
#if FUJI_X86_64
    int level = get_simd_level();
#if FUJI_HAS_AVX512_TARGET
    if (level >= FUJI_RENDER_SIMD_AVX512) {
        fill_u32_avx512(dst, byte_count, pattern_le);
        return;
    }
#endif
    if (level >= FUJI_RENDER_SIMD_AVX2) {
        fill_u32_avx2(dst, byte_count, pattern_le);
    } else {
        fill_u32_sse2(dst, byte_count, pattern_le);
    }
#else
    int32_t i = 0;
    const int32_t limit4 = byte_count & ~3;
    for (; i < limit4; i += 4) store_u32_le(dst + i, pattern_le);
    for (; i < byte_count; ++i) dst[i] = (uint8_t)((pattern_le >> ((i & 3) * 8)) & 0xffu);
#endif
}

/* ------------------------------------------------------------------------- */
/* Kernel 2: fuji_render_decode_rgba (SSE2 + AVX2 + AVX-512)                 */
/* ------------------------------------------------------------------------- */

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void decode_rgba_1to1_avx512(const uint8_t* src, int32_t stride, int32_t width, int32_t height, uint32_t* dst) {
    for (int32_t row = 0; row < height; ++row) {
        const uint8_t* srow = src + (size_t)row * (size_t)stride;
        uint32_t* drow = dst + (size_t)row * (size_t)width;
        int32_t col = 0;
        const int32_t lim16 = width & ~15;
        for (; col < lim16; col += 16) {
            __m512i v = _mm512_loadu_si512((const void*)(srow + (col << 2)));
            __m512i r = _mm512_ror_epi32(v, 8);
            _mm512_storeu_si512((void*)(drow + col), r);
        }
        for (; col < width; ++col) {
            drow[col] = ror32_8(load_u32_le(srow + (col << 2)));
        }
    }
}
#endif

__attribute__((target("avx2")))
static void decode_rgba_1to1_avx2(const uint8_t* src, int32_t stride, int32_t width, int32_t height, uint32_t* dst) {
    const __m256i shuf = _mm256_setr_epi8(
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12,
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12
    );
    for (int32_t row = 0; row < height; ++row) {
        const uint8_t* srow = src + (size_t)row * (size_t)stride;
        uint32_t* drow = dst + (size_t)row * (size_t)width;
        int32_t col = 0;
        const int32_t lim8 = width & ~7;
        for (; col < lim8; col += 8) {
            __m256i v = _mm256_loadu_si256((const __m256i*)(srow + (col << 2)));
            __m256i r = _mm256_shuffle_epi8(v, shuf);
            _mm256_storeu_si256((__m256i*)(drow + col), r);
        }
        for (; col < width; ++col) {
            drow[col] = ror32_8(load_u32_le(srow + (col << 2)));
        }
    }
}

static void decode_rgba_1to1_sse2(const uint8_t* src, int32_t stride, int32_t width, int32_t height, uint32_t* dst) {
    for (int32_t row = 0; row < height; ++row) {
        const uint8_t* srow = src + (size_t)row * (size_t)stride;
        uint32_t* drow = dst + (size_t)row * (size_t)width;
        int32_t col = 0;
        const int32_t lim4 = width & ~3;
        for (; col < lim4; col += 4) {
            __m128i v = _mm_loadu_si128((const __m128i*)(srow + (col << 2)));
            __m128i r = _mm_or_si128(_mm_srli_epi32(v, 8), _mm_slli_epi32(v, 24));
            _mm_storeu_si128((__m128i*)(drow + col), r);
        }
        for (; col < width; ++col) {
            drow[col] = ror32_8(load_u32_le(srow + (col << 2)));
        }
    }
}
#endif

FUJI_RENDER_EXPORT void fuji_render_decode_rgba(
    const uint8_t* src_bytes,
    int32_t src_base_offset,
    int32_t src_stride_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    int32_t src_width,
    int32_t src_height,
    int32_t out_width,
    int32_t out_height,
    uint32_t* output_argb
) {
    if (src_bytes == NULL || output_argb == NULL || out_width <= 0 || out_height <= 0) return;
    const uint8_t* base = src_bytes + src_base_offset;
    if (out_width == src_width && out_height == src_height) {
#if FUJI_X86_64
        int level = get_simd_level();
#if FUJI_HAS_AVX512_TARGET
        if (level >= FUJI_RENDER_SIMD_AVX512) {
            decode_rgba_1to1_avx512(base, src_stride_bytes, out_width, out_height, output_argb);
            return;
        }
#endif
        if (level >= FUJI_RENDER_SIMD_AVX2) {
            decode_rgba_1to1_avx2(base, src_stride_bytes, out_width, out_height, output_argb);
        } else {
            decode_rgba_1to1_sse2(base, src_stride_bytes, out_width, out_height, output_argb);
        }
#else
        for (int32_t row = 0; row < out_height; ++row) {
            const uint8_t* srow = base + (size_t)row * (size_t)src_stride_bytes;
            uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
            for (int32_t col = 0; col < out_width; ++col) {
                drow[col] = ror32_8(load_u32_le(srow + (col << 2)));
            }
        }
#endif
        return;
    }
    for (int32_t row = 0; row < out_height; ++row) {
        const uint8_t* srow = base + (size_t)source_rows[row] * (size_t)src_stride_bytes;
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
        for (int32_t col = 0; col < out_width; ++col) {
            drow[col] = ror32_8(load_u32_le(srow + (source_cols[col] << 2)));
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Kernel 3: fuji_render_composite_pic1_osd0 (SSE2 + AVX2 + Scaled C)        */
/* ------------------------------------------------------------------------- */

#if FUJI_X86_64
/* Exact Blinn div255Rounded for 16 unsigned 16-bit lanes in 0..65025 (zero overflow: max intermediate = 65407 <= 65535). */
__attribute__((target("avx2")))
static inline __m256i div255_rounded_epu16_avx2(__m256i x) {
    const __m256i c128 = _mm256_set1_epi16(128);
    __m256i t = _mm256_add_epi16(x, c128);
    return _mm256_srli_epi16(_mm256_add_epi16(t, _mm256_srli_epi16(t, 8)), 8);
}

/* Blends 8 ARGB8888 foreground pixels over 8 ARGB8888 background pixels using 16-bit AVX2 (bit-exact Blinn div255Rounded). */
__attribute__((target("avx2")))
static inline __m256i blend8_argb_over_bg_avx2(__m256i argb, __m256i bg) {
    const __m256i zero = _mm256_setzero_si256();
    const __m256i c255 = _mm256_set1_epi16(255);
    const __m256i opaque_a = _mm256_set1_epi32((int)0xff000000u);

    /* Unpack 8 pixels into two 16x16-bit vectors (in-lane unpacklo/unpackhi -> packus restores exact lane order!) */
    __m256i fg_lo = _mm256_unpacklo_epi8(argb, zero);
    __m256i fg_hi = _mm256_unpackhi_epi8(argb, zero);
    __m256i bg_lo = _mm256_unpacklo_epi8(bg, zero);
    __m256i bg_hi = _mm256_unpackhi_epi8(bg, zero);

    /* Broadcast alpha (word 3 of each 64-bit pixel) across all 4 words [B, G, R, A] */
    __m256i a_lo = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(fg_lo, 0xff), 0xff);
    __m256i a_hi = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(fg_hi, 0xff), 0xff);
    __m256i inv_lo = _mm256_sub_epi16(c255, a_lo);
    __m256i inv_hi = _mm256_sub_epi16(c255, a_hi);

    __m256i sum_lo = _mm256_add_epi16(_mm256_mullo_epi16(fg_lo, a_lo), _mm256_mullo_epi16(bg_lo, inv_lo));
    __m256i sum_hi = _mm256_add_epi16(_mm256_mullo_epi16(fg_hi, a_hi), _mm256_mullo_epi16(bg_hi, inv_hi));

    __m256i res_lo = div255_rounded_epu16_avx2(sum_lo);
    __m256i res_hi = div255_rounded_epu16_avx2(sum_hi);

    /* packus_epi16 reverses unpacklo/unpackhi in-lane, restoring exact 8-pixel order */
    return _mm256_or_si256(opaque_a, _mm256_packus_epi16(res_lo, res_hi));
}

/* Blends 8 ARGB8888 foreground pixels over opaque black (0xFF000000) using 16-bit AVX2. */
__attribute__((target("avx2")))
static inline __m256i blend8_argb_over_black_avx2(__m256i argb) {
    const __m256i zero = _mm256_setzero_si256();
    const __m256i opaque_a = _mm256_set1_epi32((int)0xff000000u);
    __m256i fg_lo = _mm256_unpacklo_epi8(argb, zero);
    __m256i fg_hi = _mm256_unpackhi_epi8(argb, zero);
    __m256i a_lo = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(fg_lo, 0xff), 0xff);
    __m256i a_hi = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(fg_hi, 0xff), 0xff);
    __m256i res_lo = div255_rounded_epu16_avx2(_mm256_mullo_epi16(fg_lo, a_lo));
    __m256i res_hi = div255_rounded_epu16_avx2(_mm256_mullo_epi16(fg_hi, a_hi));
    return _mm256_or_si256(opaque_a, _mm256_packus_epi16(res_lo, res_hi));
}

/* Converts 4 UYVY macro-pixels (16 bytes = 8 output pixels) to 8 ARGB8888 (0xFFRRGGBB) pixels.
 * Computes BT.601 chroma deltas ONCE per macro-pixel (4 lanes) and duplicates across (y0, y1). */
__attribute__((target("avx2")))
static inline __m256i uyvy4_to_argb8_avx2(const uint8_t* uyvy16, int32_t pic1_alpha) {
    /* Load 4 macro-pixels (128 bits): [U0 Y0 V0 Y1 | U1 Y2 V1 Y3 | U2 Y4 V2 Y5 | U3 Y6 V3 Y7] */
    __m128i raw128 = _mm_loadu_si128((const __m128i*)uyvy16);
    const __m128i mask_ff_128 = _mm_set1_epi32(0xff);
    const __m128i c128_128 = _mm_set1_epi32(128);

    /* Compute chroma offsets once per macro-pixel (4 lanes, exact identity: (256*y + k + 128)>>8 == y + ((k+128)>>8)) */
    __m128i u4 = _mm_and_si128(raw128, mask_ff_128);
    __m128i v4 = _mm_and_si128(_mm_srli_epi32(raw128, 16), mask_ff_128);
    __m128i d4 = _mm_sub_epi32(u4, c128_128);
    __m128i e4 = _mm_sub_epi32(v4, c128_128);

    __m128i dr4 = _mm_srai_epi32(_mm_add_epi32(_mm_mullo_epi32(_mm_set1_epi32(359), e4), c128_128), 8);
    __m128i dg4 = _mm_srai_epi32(
        _mm_add_epi32(
            _mm_sub_epi32(_mm_sub_epi32(c128_128, _mm_mullo_epi32(_mm_set1_epi32(88), d4)),
                          _mm_mullo_epi32(_mm_set1_epi32(183), e4)),
            _mm_setzero_si128()
        ),
        8
    );
    __m128i db4 = _mm_srai_epi32(_mm_add_epi32(_mm_mullo_epi32(_mm_set1_epi32(454), d4), c128_128), 8);

    /* Duplicate chroma deltas to 8 lanes: [c0, c0, c1, c1, c2, c2, c3, c3] */
    __m256i dr8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(dr4, dr4)), _mm_unpackhi_epi32(dr4, dr4), 1);
    __m256i dg8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(dg4, dg4)), _mm_unpackhi_epi32(dg4, dg4), 1);
    __m256i db8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(db4, db4)), _mm_unpackhi_epi32(db4, db4), 1);

    /* Duplicate raw macro-pixels to 8 lanes for Y extraction and BUG-09 / BUG-A5-5 black-margin check */
    __m256i mp = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(raw128, raw128)), _mm_unpackhi_epi32(raw128, raw128), 1);
    __m256i is_zero = _mm256_or_si256(
        _mm256_cmpeq_epi32(mp, _mm256_setzero_si256()),
        _mm256_or_si256(
            _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x80800000u)),
            _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x00008080u))
        )
    );

    const __m256i mask_ff = _mm256_set1_epi32(0xff);
    __m256i y_even = _mm256_and_si256(_mm256_srli_epi32(mp, 8), mask_ff);
    __m256i y_odd  = _mm256_srli_epi32(mp, 24);
    __m256i y = _mm256_blend_epi32(y_even, y_odd, 0xAA);

    const __m256i zero = _mm256_setzero_si256();
    __m256i r = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dr8)));
    __m256i g = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dg8)));
    __m256i b = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, db8)));

    __m256i rgb = _mm256_andnot_si256(
        is_zero,
        _mm256_or_si256(_mm256_slli_epi32(r, 16), _mm256_or_si256(_mm256_slli_epi32(g, 8), b))
    );
    __m256i bg = _mm256_or_si256(_mm256_set1_epi32((int)0xff000000u), rgb);

    if (pic1_alpha < 255) {
        __m256i pa = _mm256_set1_epi16((short)pic1_alpha);
        __m256i bg_lo = _mm256_unpacklo_epi8(bg, zero);
        __m256i bg_hi = _mm256_unpackhi_epi8(bg, zero);
        bg_lo = div255_rounded_epu16_avx2(_mm256_mullo_epi16(bg_lo, pa));
        bg_hi = div255_rounded_epu16_avx2(_mm256_mullo_epi16(bg_hi, pa));
        bg = _mm256_or_si256(_mm256_set1_epi32((int)0xff000000u), _mm256_packus_epi16(bg_lo, bg_hi));
    }

    return bg;
}

__attribute__((target("avx2")))
static void composite_row_osd_over_black_avx2(const uint8_t* osd_row, int32_t width, uint32_t* dst_row) {
    const __m256i shuf = _mm256_setr_epi8(
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12,
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12
    );
    const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
    int32_t col = 0;
    const int32_t lim8 = width & ~7;
    for (; col < lim8; col += 8) {
        __m256i raw = _mm256_loadu_si256((const __m256i*)(osd_row + (col << 2)));
        __m256i argb = _mm256_shuffle_epi8(raw, shuf);
        __m256i am = _mm256_and_si256(argb, alpha_mask);
        if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
            _mm256_storeu_si256((__m256i*)(dst_row + col), argb);
            continue;
        }
        if (_mm256_testz_si256(am, am)) {
            _mm256_storeu_si256((__m256i*)(dst_row + col), alpha_mask);
            continue;
        }
        _mm256_storeu_si256((__m256i*)(dst_row + col), blend8_argb_over_black_avx2(argb));
    }
    for (; col < width; ++col) {
        uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
        dst_row[col] = blend_osd_over_black(argb);
    }
}

__attribute__((target("avx2")))
static void composite_row_1to1_avx2(
    const uint8_t* osd_row,
    const uint8_t* pic1_row,
    int32_t pic1_x,
    int32_t pic1_right,
    int32_t pic1_alpha,
    int32_t out_width,
    uint32_t* dst_row
) {
    /* Segment 1: left of PIC1 (0 .. pic1_x) */
    int32_t seg1_end = pic1_x < out_width ? pic1_x : out_width;
    if (seg1_end > 0) {
        if (osd_row == NULL) {
            fill_u32_avx2((uint8_t*)dst_row, seg1_end * 4, 0xff000000u);
        } else {
            composite_row_osd_over_black_avx2(osd_row, seg1_end, dst_row);
        }
    }

    /* Segment 2: inside PIC1 (pic1_x .. pic1_right) */
    int32_t seg2_start = pic1_x > 0 ? pic1_x : 0;
    int32_t seg2_end = pic1_right < out_width ? pic1_right : out_width;
    int32_t col = seg2_start;
    if (col < seg2_end) {
        if (osd_row == NULL) {
            if (((col - pic1_x) & 1) != 0 && col < seg2_end) {
                int32_t local_x = col - pic1_x;
                uint32_t rgb = uyvy_pair_to_rgb(pic1_row + ((local_x & ~1) * 2), 1);
                dst_row[col++] = apply_pic1_alpha(rgb, pic1_alpha);
            }
            for (; col + 8 <= seg2_end; col += 8) {
                int32_t local_x = col - pic1_x;
                __m256i bg = uyvy4_to_argb8_avx2(pic1_row + local_x * 2, pic1_alpha);
                _mm256_storeu_si256((__m256i*)(dst_row + col), bg);
            }
            for (; col < seg2_end; ++col) {
                int32_t local_x = col - pic1_x;
                uint32_t rgb = uyvy_pair_to_rgb(pic1_row + ((local_x & ~1) * 2), local_x & 1);
                dst_row[col] = apply_pic1_alpha(rgb, pic1_alpha);
            }
        } else {
            const __m256i shuf = _mm256_setr_epi8(
                1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12,
                1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12
            );
            const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
            if (((col - pic1_x) & 1) != 0 && col < seg2_end) {
                uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                int32_t local_x = col - pic1_x;
                uint32_t bg = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((local_x & ~1) * 2), 1), pic1_alpha);
                dst_row[col++] = blend_osd_over_bg(argb, bg);
            }
            for (; col + 8 <= seg2_end; col += 8) {
                __m256i raw = _mm256_loadu_si256((const __m256i*)(osd_row + (col << 2)));
                __m256i argb = _mm256_shuffle_epi8(raw, shuf);
                __m256i am = _mm256_and_si256(argb, alpha_mask);
                if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
                    _mm256_storeu_si256((__m256i*)(dst_row + col), argb);
                    continue;
                }
                int32_t local_x = col - pic1_x;
                __m256i bg = uyvy4_to_argb8_avx2(pic1_row + local_x * 2, pic1_alpha);
                if (_mm256_testz_si256(am, am)) {
                    _mm256_storeu_si256((__m256i*)(dst_row + col), bg);
                    continue;
                }
                _mm256_storeu_si256((__m256i*)(dst_row + col), blend8_argb_over_bg_avx2(argb, bg));
            }
            for (; col < seg2_end; ++col) {
                uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                if ((argb >> 24) == 255u) {
                    dst_row[col] = argb;
                } else {
                    int32_t local_x = col - pic1_x;
                    uint32_t bg = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((local_x & ~1) * 2), local_x & 1), pic1_alpha);
                    dst_row[col] = blend_osd_over_bg(argb, bg);
                }
            }
        }
    }

    /* Segment 3: right of PIC1 (pic1_right .. out_width) */
    if (seg2_end < out_width) {
        int32_t rem = out_width - seg2_end;
        if (osd_row == NULL) {
            fill_u32_avx2((uint8_t*)(dst_row + seg2_end), rem * 4, 0xff000000u);
        } else {
            composite_row_osd_over_black_avx2(osd_row + (seg2_end << 2), rem, dst_row + seg2_end);
        }
    }
}

/* Vectorized AVX2 scaled composite (e.g. 1024x768 -> 640x480) using gather + 16-bit blend. */
__attribute__((target("avx2")))
static void composite_scaled_avx2(
    const uint8_t* osd_base,
    int32_t osd_stride_bytes,
    const uint8_t* pic1_bytes,
    int32_t pic1_x,
    int32_t pic1_y,
    int32_t pic1_right,
    int32_t pic1_bottom,
    int32_t pic1_stride_bytes,
    int32_t pic1_alpha,
    int32_t pic1_active,
    const int32_t* source_rows,
    const int32_t* source_cols,
    int32_t out_width,
    int32_t out_height,
    uint32_t* output_argb
) {
    const __m256i shuf = _mm256_setr_epi8(
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12,
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12
    );
    const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
    const __m256i mask_ff = _mm256_set1_epi32(0xff);
    const __m256i c128 = _mm256_set1_epi32(128);
    const __m256i zero = _mm256_setzero_si256();
    const int32_t lim8 = out_width & ~7;

    /* Precompute local_x and UYVY macro-pixel byte offsets per output column (fits on stack for <= 2048px) */
    int32_t stack_pic_off[2048];
    int32_t stack_pic_odd[2048];
    int32_t stack_in_pic[2048];
    int32_t* pic_off = stack_pic_off;
    int32_t* pic_odd = stack_pic_odd;
    int32_t* in_pic_x = stack_in_pic;
    if (out_width > 2048) {
        pic_off = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        pic_odd = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        in_pic_x = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
    }
    if (pic1_active) {
        for (int32_t c = 0; c < out_width; ++c) {
            int32_t sc = source_cols[c];
            if (sc >= pic1_x && sc < pic1_right) {
                int32_t lx = sc - pic1_x;
                pic_off[c] = (lx & ~1) * 2;
                pic_odd[c] = (lx & 1) ? -1 : 0;
                in_pic_x[c] = -1;
            } else {
                pic_off[c] = 0;
                pic_odd[c] = 0;
                in_pic_x[c] = 0;
            }
        }
    }

    for (int32_t row = 0; row < out_height; ++row) {
        const int32_t src_row = source_rows[row];
        const int in_pic_y = pic1_active && (src_row >= pic1_y && src_row < pic1_bottom);
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;

        if (osd_base == NULL) {
            if (!in_pic_y) {
                fill_u32_avx2((uint8_t*)drow, out_width * 4, 0xff000000u);
                continue;
            }
            const uint8_t* pic_row = pic1_bytes + (size_t)(src_row - pic1_y) * (size_t)pic1_stride_bytes;
            for (int32_t col = 0; col < out_width; ++col) {
                if (in_pic_x[col]) {
                    uint32_t rgb = uyvy_pair_to_rgb(pic_row + pic_off[col], pic_odd[col] != 0);
                    drow[col] = apply_pic1_alpha(rgb, pic1_alpha);
                } else {
                    drow[col] = 0xff000000u;
                }
            }
            continue;
        }

        const int32_t* osd_row32 = (const int32_t*)(osd_base + (size_t)src_row * (size_t)osd_stride_bytes);
        if (!in_pic_y) {
            int32_t col = 0;
            for (; col < lim8; col += 8) {
                __m256i idx = _mm256_loadu_si256((const __m256i*)(source_cols + col));
                __m256i raw = _mm256_i32gather_epi32(osd_row32, idx, 4);
                __m256i argb = _mm256_shuffle_epi8(raw, shuf);
                __m256i am = _mm256_and_si256(argb, alpha_mask);
                if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
                    _mm256_storeu_si256((__m256i*)(drow + col), argb);
                } else if (_mm256_testz_si256(am, am)) {
                    _mm256_storeu_si256((__m256i*)(drow + col), alpha_mask);
                } else {
                    _mm256_storeu_si256((__m256i*)(drow + col), blend8_argb_over_black_avx2(argb));
                }
            }
            for (; col < out_width; ++col) {
                drow[col] = blend_osd_over_black(ror32_8((uint32_t)osd_row32[source_cols[col]]));
            }
            continue;
        }

        const uint8_t* pic_row = pic1_bytes + (size_t)(src_row - pic1_y) * (size_t)pic1_stride_bytes;
        int32_t col = 0;
        for (; col < lim8; col += 8) {
            __m256i idx = _mm256_loadu_si256((const __m256i*)(source_cols + col));
            __m256i raw = _mm256_i32gather_epi32(osd_row32, idx, 4);
            __m256i argb = _mm256_shuffle_epi8(raw, shuf);
            __m256i am = _mm256_and_si256(argb, alpha_mask);
            if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
                _mm256_storeu_si256((__m256i*)(drow + col), argb);
                continue;
            }
            __m256i poff = _mm256_loadu_si256((const __m256i*)(pic_off + col));
            __m256i podd = _mm256_loadu_si256((const __m256i*)(pic_odd + col));
            __m256i in_x = _mm256_loadu_si256((const __m256i*)(in_pic_x + col));
            __m256i mp = _mm256_mask_i32gather_epi32(zero, (const int32_t*)pic_row, poff, in_x, 1);
            __m256i is_zero = _mm256_or_si256(
                _mm256_cmpeq_epi32(mp, zero),
                _mm256_or_si256(
                    _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x80800000u)),
                    _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x00008080u))
                )
            );
            __m256i u = _mm256_and_si256(mp, mask_ff);
            __m256i v = _mm256_and_si256(_mm256_srli_epi32(mp, 16), mask_ff);
            __m256i y0 = _mm256_and_si256(_mm256_srli_epi32(mp, 8), mask_ff);
            __m256i y1 = _mm256_srli_epi32(mp, 24);
            __m256i y = _mm256_blendv_epi8(y0, y1, podd);
            __m256i d = _mm256_sub_epi32(u, c128);
            __m256i e = _mm256_sub_epi32(v, c128);
            __m256i dr = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(359), e), c128), 8);
            __m256i dg = _mm256_srai_epi32(_mm256_sub_epi32(_mm256_sub_epi32(c128, _mm256_mullo_epi32(_mm256_set1_epi32(88), d)), _mm256_mullo_epi32(_mm256_set1_epi32(183), e)), 8);
            __m256i db = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(454), d), c128), 8);
            __m256i r = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dr)));
            __m256i g = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dg)));
            __m256i b = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, db)));
            __m256i rgb = _mm256_andnot_si256(is_zero, _mm256_or_si256(_mm256_slli_epi32(r, 16), _mm256_or_si256(_mm256_slli_epi32(g, 8), b)));
            __m256i bg = _mm256_or_si256(alpha_mask, rgb);
            if (pic1_alpha < 255) {
                __m256i pa = _mm256_set1_epi16((short)pic1_alpha);
                __m256i bg_lo = div255_rounded_epu16_avx2(_mm256_mullo_epi16(_mm256_unpacklo_epi8(bg, zero), pa));
                __m256i bg_hi = div255_rounded_epu16_avx2(_mm256_mullo_epi16(_mm256_unpackhi_epi8(bg, zero), pa));
                bg = _mm256_or_si256(alpha_mask, _mm256_packus_epi16(bg_lo, bg_hi));
            }
            if (_mm256_testz_si256(am, am)) {
                _mm256_storeu_si256((__m256i*)(drow + col), bg);
            } else {
                _mm256_storeu_si256((__m256i*)(drow + col), blend8_argb_over_bg_avx2(argb, bg));
            }
        }
        for (; col < out_width; ++col) {
            uint32_t argb = ror32_8((uint32_t)osd_row32[source_cols[col]]);
            uint32_t a = argb >> 24;
            if (a == 255u) {
                drow[col] = argb;
                continue;
            }
            uint32_t bg = 0xff000000u;
            if (in_pic_x[col]) {
                uint32_t rgb = uyvy_pair_to_rgb(pic_row + pic_off[col], pic_odd[col] != 0);
                bg = apply_pic1_alpha(rgb, pic1_alpha);
            }
            drow[col] = (a == 0u) ? bg : blend_osd_over_bg(argb, bg);
        }
    }

    if (pic_off != stack_pic_off) {
        free(pic_off);
        free(pic_odd);
        free(in_pic_x);
    }
}
#endif

static void composite_row_1to1_scalar(
    const uint8_t* osd_row,
    const uint8_t* pic1_row,
    int32_t pic1_x,
    int32_t pic1_right,
    int32_t pic1_alpha,
    int32_t out_width,
    uint32_t* dst_row
) {
    int32_t seg1_end = pic1_x < out_width ? pic1_x : out_width;
    if (seg1_end > 0) {
        if (osd_row == NULL) {
            fuji_render_fill_u32((uint8_t*)dst_row, seg1_end * 4, 0xff000000u);
        } else {
            for (int32_t col = 0; col < seg1_end; ++col) {
                dst_row[col] = blend_osd_over_black(ror32_8(load_u32_le(osd_row + (col << 2))));
            }
        }
    }
    int32_t seg2_start = pic1_x > 0 ? pic1_x : 0;
    int32_t seg2_end = pic1_right < out_width ? pic1_right : out_width;
    int32_t col = seg2_start;
    if (osd_row == NULL) {
        if (((col - pic1_x) & 1) != 0 && col < seg2_end) {
            int32_t lx = col - pic1_x;
            dst_row[col++] = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((lx & ~1) * 2), 1), pic1_alpha);
        }
        for (; col + 2 <= seg2_end; col += 2) {
            uint32_t rgb0, rgb1;
            uyvy_pair_to_two_rgb(pic1_row + (col - pic1_x) * 2, &rgb0, &rgb1);
            dst_row[col]     = apply_pic1_alpha(rgb0, pic1_alpha);
            dst_row[col + 1] = apply_pic1_alpha(rgb1, pic1_alpha);
        }
        for (; col < seg2_end; ++col) {
            int32_t lx = col - pic1_x;
            dst_row[col] = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((lx & ~1) * 2), lx & 1), pic1_alpha);
        }
    } else {
        if (((col - pic1_x) & 1) != 0 && col < seg2_end) {
            uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
            int32_t lx = col - pic1_x;
            uint32_t bg = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((lx & ~1) * 2), 1), pic1_alpha);
            dst_row[col++] = blend_osd_over_bg(argb, bg);
        }
        for (; col + 2 <= seg2_end; col += 2) {
            uint32_t a0 = ror32_8(load_u32_le(osd_row + (col << 2)));
            uint32_t a1 = ror32_8(load_u32_le(osd_row + ((col + 1) << 2)));
            if ((a0 >> 24) == 255u && (a1 >> 24) == 255u) {
                dst_row[col] = a0;
                dst_row[col + 1] = a1;
                continue;
            }
            uint32_t rgb0, rgb1;
            uyvy_pair_to_two_rgb(pic1_row + (col - pic1_x) * 2, &rgb0, &rgb1);
            dst_row[col]     = blend_osd_over_bg(a0, apply_pic1_alpha(rgb0, pic1_alpha));
            dst_row[col + 1] = blend_osd_over_bg(a1, apply_pic1_alpha(rgb1, pic1_alpha));
        }
        for (; col < seg2_end; ++col) {
            uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
            if ((argb >> 24) == 255u) {
                dst_row[col] = argb;
            } else {
                int32_t lx = col - pic1_x;
                uint32_t bg = apply_pic1_alpha(uyvy_pair_to_rgb(pic1_row + ((lx & ~1) * 2), lx & 1), pic1_alpha);
                dst_row[col] = blend_osd_over_bg(argb, bg);
            }
        }
    }
    if (seg2_end < out_width) {
        if (osd_row == NULL) {
            fuji_render_fill_u32((uint8_t*)(dst_row + seg2_end), (out_width - seg2_end) * 4, 0xff000000u);
        } else {
            for (int32_t c = seg2_end; c < out_width; ++c) {
                dst_row[c] = blend_osd_over_black(ror32_8(load_u32_le(osd_row + (c << 2))));
            }
        }
    }
}

#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void composite_planes_matrix_avx512(
    const uint8_t* osd_base, int32_t osd_stride,
    const uint8_t* pic0_bytes, int32_t p0x, int32_t p0y, int32_t p0r, int32_t p0b, int32_t p0s, int32_t p0_active,
    const uint8_t* pic1_bytes, int32_t p1x, int32_t p1y, int32_t p1r, int32_t p1b, int32_t p1s, int32_t p1_alpha, int32_t p1_active,
    uint32_t backdrop_argb,
    int32_t native_1to1, const int32_t* source_rows, const int32_t* source_cols,
    int32_t out_width, int32_t out_height,
    const int32_t* cmat, int32_t mmode,
    uint32_t* output_argb
);
#endif

FUJI_RENDER_EXPORT void fuji_render_composite_pic1_osd0(
    const uint8_t* osd_bytes,
    int32_t osd_base_offset,
    int32_t osd_stride_bytes,
    const uint8_t* pic1_bytes,
    int32_t pic1_x,
    int32_t pic1_y,
    int32_t pic1_width,
    int32_t pic1_height,
    int32_t pic1_stride_bytes,
    int32_t pic1_alpha,
    int32_t opaque_osd_backdrop,
    int32_t canvas_width,
    int32_t canvas_height,
    const int32_t* source_rows,
    const int32_t* source_cols,
    int32_t out_width,
    int32_t out_height,
    uint32_t* output_argb
) {
    if (output_argb == NULL || out_width <= 0 || out_height <= 0) return;

    const int native_1to1 = (out_width == canvas_width && out_height == canvas_height);
    const int pic1_right = pic1_x + pic1_width;
    const int pic1_bottom = pic1_y + pic1_height;
    const int pic1_active = (pic1_bytes != NULL && pic1_alpha > 0 && !(osd_bytes != NULL && opaque_osd_backdrop != 0));
    const uint8_t* osd_base = (osd_bytes != NULL) ? (osd_bytes + osd_base_offset) : NULL;

#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
    if (get_simd_level() >= FUJI_RENDER_SIMD_AVX512) {
        composite_planes_matrix_avx512(
            osd_base, osd_stride_bytes,
            NULL, 0, 0, 0, 0, 0, 0,
            pic1_bytes, pic1_x, pic1_y, pic1_right, pic1_bottom, pic1_stride_bytes, pic1_alpha, pic1_active,
            0xff000000u, native_1to1, source_rows, source_cols,
            out_width, out_height, NULL, 0, output_argb
        );
        return;
    }
#endif

    if (native_1to1) {
#if FUJI_X86_64
        const int use_avx2 = (get_simd_level() >= FUJI_RENDER_SIMD_AVX2);
#endif
        for (int32_t row = 0; row < out_height; ++row) {
            uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
            const int in_pic_y = pic1_active && (row >= pic1_y && row < pic1_bottom);
            const uint8_t* osd_row = (osd_base != NULL) ? (osd_base + (size_t)row * (size_t)osd_stride_bytes) : NULL;
            if (!in_pic_y) {
                if (osd_row == NULL) {
                    fuji_render_fill_u32((uint8_t*)drow, out_width * 4, 0xff000000u);
                } else {
#if FUJI_X86_64
                    if (use_avx2) {
                        composite_row_osd_over_black_avx2(osd_row, out_width, drow);
                    } else
#endif
                    {
                        for (int32_t col = 0; col < out_width; ++col) {
                            drow[col] = blend_osd_over_black(ror32_8(load_u32_le(osd_row + (col << 2))));
                        }
                    }
                }
                continue;
            }
            const uint8_t* pic_row = pic1_bytes + (size_t)(row - pic1_y) * (size_t)pic1_stride_bytes;
#if FUJI_X86_64
            if (use_avx2) {
                composite_row_1to1_avx2(osd_row, pic_row, pic1_x, pic1_right, pic1_alpha, out_width, drow);
            } else
#endif
            {
                composite_row_1to1_scalar(osd_row, pic_row, pic1_x, pic1_right, pic1_alpha, out_width, drow);
            }
        }
        return;
    }

    /* Scaled nearest-neighbor path (e.g. 1024x768 -> 640x480) */
#if FUJI_X86_64
    if (get_simd_level() >= FUJI_RENDER_SIMD_AVX2) {
        composite_scaled_avx2(
            osd_base, osd_stride_bytes, pic1_bytes,
            pic1_x, pic1_y, pic1_right, pic1_bottom, pic1_stride_bytes,
            pic1_alpha, pic1_active, source_rows, source_cols,
            out_width, out_height, output_argb
        );
        return;
    }
#endif
    for (int32_t row = 0; row < out_height; ++row) {
        const int32_t src_row = source_rows[row];
        const int in_pic_y = pic1_active && (src_row >= pic1_y && src_row < pic1_bottom);
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
        if (osd_base == NULL) {
            if (!in_pic_y) {
                fuji_render_fill_u32((uint8_t*)drow, out_width * 4, 0xff000000u);
                continue;
            }
            const uint8_t* pic_row = pic1_bytes + (size_t)(src_row - pic1_y) * (size_t)pic1_stride_bytes;
            for (int32_t col = 0; col < out_width; ++col) {
                const int32_t src_col = source_cols[col];
                if (src_col >= pic1_x && src_col < pic1_right) {
                    const int32_t lx = src_col - pic1_x;
                    const uint32_t rgb = uyvy_pair_to_rgb(pic_row + ((lx & ~1) * 2), lx & 1);
                    drow[col] = apply_pic1_alpha(rgb, pic1_alpha);
                } else {
                    drow[col] = 0xff000000u;
                }
            }
            continue;
        }
        const uint8_t* osd_row = osd_base + (size_t)src_row * (size_t)osd_stride_bytes;
        if (!in_pic_y) {
            for (int32_t col = 0; col < out_width; ++col) {
                const uint32_t argb = ror32_8(load_u32_le(osd_row + (source_cols[col] << 2)));
                drow[col] = blend_osd_over_black(argb);
            }
            continue;
        }
        const uint8_t* pic_row = pic1_bytes + (size_t)(src_row - pic1_y) * (size_t)pic1_stride_bytes;
        for (int32_t col = 0; col < out_width; ++col) {
            const int32_t src_col = source_cols[col];
            const uint32_t argb = ror32_8(load_u32_le(osd_row + (src_col << 2)));
            const uint32_t a = argb >> 24;
            if (a == 255u) {
                drow[col] = argb;
                continue;
            }
            uint32_t bg = 0xff000000u;
            if (src_col >= pic1_x && src_col < pic1_right) {
                const int32_t lx = src_col - pic1_x;
                const uint32_t rgb = uyvy_pair_to_rgb(pic_row + ((lx & ~1) * 2), lx & 1);
                bg = apply_pic1_alpha(rgb, pic1_alpha);
            }
            drow[col] = (a == 0u) ? bg : blend_osd_over_bg(argb, bg);
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Kernel 4: fuji_render_morton_tile_8x8 (Pure SIMD SSE2 16-op 8x8 swizzle)  */
/* ------------------------------------------------------------------------- */

FUJI_RENDER_EXPORT void fuji_render_morton_tile_8x8(
    const uint8_t* linear_src,
    uint8_t* morton_dst,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes
) {
    if (linear_src == NULL || morton_dst == NULL || width <= 0 || height <= 0) return;
    uint8_t* out = morton_dst;
    const size_t stride = (size_t)src_stride_bytes;

#if FUJI_X86_64
    for (int32_t top = 0; top < height; top += 8) {
        const uint8_t* row_base = linear_src + (size_t)top * stride;
        for (int32_t left = 0; left < width; left += 8) {
            const uint8_t* p = row_base + ((size_t)left << 2);
            /* Load rows 0..3 (each row is 8 pixels = 2 x 128-bit registers) */
            __m128i r0_lo = _mm_loadu_si128((const __m128i*)(p));
            __m128i r0_hi = _mm_loadu_si128((const __m128i*)(p + 16));
            __m128i r1_lo = _mm_loadu_si128((const __m128i*)(p + stride));
            __m128i r1_hi = _mm_loadu_si128((const __m128i*)(p + stride + 16));
            __m128i r2_lo = _mm_loadu_si128((const __m128i*)(p + 2 * stride));
            __m128i r2_hi = _mm_loadu_si128((const __m128i*)(p + 2 * stride + 16));
            __m128i r3_lo = _mm_loadu_si128((const __m128i*)(p + 3 * stride));
            __m128i r3_hi = _mm_loadu_si128((const __m128i*)(p + 3 * stride + 16));

            /* Quadrant 0 (x=0..3, y=0..3): morton 0..15 */
            _mm_storeu_si128((__m128i*)(out +   0), _mm_unpacklo_epi64(r0_lo, r1_lo));
            _mm_storeu_si128((__m128i*)(out +  16), _mm_unpackhi_epi64(r0_lo, r1_lo));
            _mm_storeu_si128((__m128i*)(out +  32), _mm_unpacklo_epi64(r2_lo, r3_lo));
            _mm_storeu_si128((__m128i*)(out +  48), _mm_unpackhi_epi64(r2_lo, r3_lo));

            /* Quadrant 1 (x=4..7, y=0..3): morton 16..31 */
            _mm_storeu_si128((__m128i*)(out +  64), _mm_unpacklo_epi64(r0_hi, r1_hi));
            _mm_storeu_si128((__m128i*)(out +  80), _mm_unpackhi_epi64(r0_hi, r1_hi));
            _mm_storeu_si128((__m128i*)(out +  96), _mm_unpacklo_epi64(r2_hi, r3_hi));
            _mm_storeu_si128((__m128i*)(out + 112), _mm_unpackhi_epi64(r2_hi, r3_hi));

            /* Load rows 4..7 */
            const uint8_t* p4 = p + 4 * stride;
            __m128i r4_lo = _mm_loadu_si128((const __m128i*)(p4));
            __m128i r4_hi = _mm_loadu_si128((const __m128i*)(p4 + 16));
            __m128i r5_lo = _mm_loadu_si128((const __m128i*)(p4 + stride));
            __m128i r5_hi = _mm_loadu_si128((const __m128i*)(p4 + stride + 16));
            __m128i r6_lo = _mm_loadu_si128((const __m128i*)(p4 + 2 * stride));
            __m128i r6_hi = _mm_loadu_si128((const __m128i*)(p4 + 2 * stride + 16));
            __m128i r7_lo = _mm_loadu_si128((const __m128i*)(p4 + 3 * stride));
            __m128i r7_hi = _mm_loadu_si128((const __m128i*)(p4 + 3 * stride + 16));

            /* Quadrant 2 (x=0..3, y=4..7): morton 32..47 */
            _mm_storeu_si128((__m128i*)(out + 128), _mm_unpacklo_epi64(r4_lo, r5_lo));
            _mm_storeu_si128((__m128i*)(out + 144), _mm_unpackhi_epi64(r4_lo, r5_lo));
            _mm_storeu_si128((__m128i*)(out + 160), _mm_unpacklo_epi64(r6_lo, r7_lo));
            _mm_storeu_si128((__m128i*)(out + 176), _mm_unpackhi_epi64(r6_lo, r7_lo));

            /* Quadrant 3 (x=4..7, y=4..7): morton 48..63 */
            _mm_storeu_si128((__m128i*)(out + 192), _mm_unpacklo_epi64(r4_hi, r5_hi));
            _mm_storeu_si128((__m128i*)(out + 208), _mm_unpackhi_epi64(r4_hi, r5_hi));
            _mm_storeu_si128((__m128i*)(out + 224), _mm_unpacklo_epi64(r6_hi, r7_hi));
            _mm_storeu_si128((__m128i*)(out + 240), _mm_unpackhi_epi64(r6_hi, r7_hi));

            out += 256;
        }
    }
#else
    static const uint8_t mcol[64] = {
        0,1,0,1,2,3,2,3,0,1,0,1,2,3,2,3,4,5,4,5,6,7,6,7,4,5,4,5,6,7,6,7,
        0,1,0,1,2,3,2,3,0,1,0,1,2,3,2,3,4,5,4,5,6,7,6,7,4,5,4,5,6,7,6,7
    };
    static const uint8_t mrow[64] = {
        0,0,1,1,0,0,1,1,2,2,3,3,2,2,3,3,0,0,1,1,0,0,1,1,2,2,3,3,2,2,3,3,
        4,4,5,5,4,4,5,5,6,6,7,7,6,6,7,7,4,4,5,5,4,4,5,5,6,6,7,7,6,6,7,7
    };
    for (int32_t top = 0; top < height; top += 8) {
        for (int32_t left = 0; left < width; left += 8) {
            const uint8_t* base = linear_src + (size_t)top * stride + ((size_t)left << 2);
            for (int m = 0; m < 64; ++m) {
                store_u32_le(out, load_u32_le(base + (size_t)mrow[m] * stride + ((size_t)mcol[m] << 2)));
                out += 4;
            }
        }
    }
#endif
}

/* ------------------------------------------------------------------------- */
/* Kernel 5: fuji_render_blit_morton_quad (Exact Morton texture blit)        */
/* ------------------------------------------------------------------------- */

FUJI_RENDER_EXPORT void fuji_render_blit_morton_quad(
    const uint8_t* texture,
    uint8_t* frame,
    int32_t canvas_width,
    int32_t tex_width,
    int32_t tex_height,
    int32_t pitch_tiles,
    int32_t row_start,
    int32_t row_end,
    int32_t col_start,
    int32_t col_end,
    double left,
    double top,
    double scale_x,
    double scale_y
) {
    const int32_t col_count = col_end - col_start;
    if (texture == NULL || frame == NULL || row_start >= row_end || col_count <= 0) return;

    static const int32_t morton_x_lut[8] = { 0, 1, 4, 5, 16, 17, 20, 21 };
    static const int32_t morton_y_lut[8] = { 0, 2, 8, 10, 32, 34, 40, 42 };

    int32_t stack_col_offsets[2048];
    int32_t* col_byte_base = stack_col_offsets;
    if (col_count > 2048) {
        col_byte_base = (int32_t*)malloc((size_t)col_count * sizeof(int32_t));
        if (col_byte_base == NULL) return;
    }

    const int32_t max_tex_x = tex_width - 1;
    const int32_t max_tex_y = tex_height - 1;
    for (int32_t i = 0; i < col_count; ++i) {
        double raw_x = ((double)(col_start + i) + 0.5 - left) / scale_x;
        int32_t tx = (raw_x <= 0.0) ? 0 : ((raw_x >= (double)max_tex_x) ? max_tex_x : (int32_t)raw_x);
        col_byte_base[i] = ((tx >> 3) << 8) + (morton_x_lut[tx & 7] << 2);
    }

    for (int32_t row = row_start; row < row_end; ++row) {
        double raw_y = ((double)row + 0.5 - top) / scale_y;
        int32_t ty = (raw_y <= 0.0) ? 0 : ((raw_y >= (double)max_tex_y) ? max_tex_y : (int32_t)raw_y);
        const int32_t row_tex_base = (((ty >> 3) * pitch_tiles) << 8) + (morton_y_lut[ty & 7] << 2);
        const uint8_t* tex_row = texture + row_tex_base;
        uint8_t* dst = frame + (((size_t)row * (size_t)canvas_width + (size_t)col_start) << 2);

        for (int32_t i = 0; i < col_count; ++i) {
            const uint8_t* src = tex_row + col_byte_base[i];
            const uint32_t alpha = src[0];
            if (alpha == 0u) continue;
            uint8_t* d = dst + ((size_t)i << 2);
            if (alpha == 255u) {
                store_u32_le(d, load_u32_le(src));
                continue;
            }
            const uint32_t old_alpha = d[0];
            if (old_alpha == 0u) {
                store_u32_le(d, load_u32_le(src));
                continue;
            }
            const uint32_t inv_alpha = 255u - alpha;
            const uint32_t alpha_weight = alpha * 255u;
            const uint32_t old_weight = old_alpha * inv_alpha;
            const uint32_t coverage = alpha_weight + old_weight;
            const uint32_t half_cov = coverage >> 1;
            d[1] = (uint8_t)((src[1] * alpha_weight + d[1] * old_weight + half_cov) / coverage);
            d[2] = (uint8_t)((src[2] * alpha_weight + d[2] * old_weight + half_cov) / coverage);
            d[3] = (uint8_t)((src[3] * alpha_weight + d[3] * old_weight + half_cov) / coverage);
            d[0] = (uint8_t)div255_rounded_u32(coverage);
        }
    }

    if (col_byte_base != stack_col_offsets) {
        free(col_byte_base);
    }
}

/* ------------------------------------------------------------------------- */
/* Kernel 6: fuji_render_fill_rect_rgba (Exact SolidCommandRects.Rect.paint) */
/* ------------------------------------------------------------------------- */

FUJI_RENDER_EXPORT void fuji_render_fill_rect_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom,
    uint32_t color_aabbggrr,
    int32_t src_over
) {
    if (frame == NULL || left >= right || top >= bottom) return;
    const uint32_t alpha = (color_aabbggrr >> 24) & 0xffu;
    if (src_over && alpha == 0u) return;

    const uint32_t blue  = (color_aabbggrr >> 16) & 0xffu;
    const uint32_t green = (color_aabbggrr >> 8) & 0xffu;
    const uint32_t red   = color_aabbggrr & 0xffu;
    /* Framebuffer byte order: [alpha, blue, green, red] */
    const uint32_t packed_le = alpha | (blue << 8) | (green << 16) | (red << 24);
    const int32_t width = right - left;
    const size_t row_stride = (size_t)canvas_width << 2;

    if (!src_over || alpha == 255u) {
        const int32_t row_bytes = width << 2;
        for (int32_t row = top; row < bottom; ++row) {
            uint8_t* d = frame + (size_t)row * row_stride + ((size_t)left << 2);
            fuji_render_fill_u32(d, row_bytes, packed_le);
        }
        return;
    }

    const uint32_t inv_alpha = 255u - alpha;
    const uint32_t alpha_weight = alpha * 255u;
    const uint32_t blue_w  = blue * alpha_weight;
    const uint32_t green_w = green * alpha_weight;
    const uint32_t red_w   = red * alpha_weight;

    for (int32_t row = top; row < bottom; ++row) {
        uint8_t* d = frame + (size_t)row * row_stride + ((size_t)left << 2);
        for (int32_t i = 0; i < width; ++i, d += 4) {
            const uint32_t old_alpha = d[0];
            if (old_alpha == 0u) {
                store_u32_le(d, packed_le);
                continue;
            }
            const uint32_t old_weight = old_alpha * inv_alpha;
            const uint32_t coverage = alpha_weight + old_weight;
            const uint32_t half_cov = coverage >> 1;
            d[0] = (uint8_t)div255_rounded_u32(coverage);
            d[1] = (uint8_t)((blue_w  + d[1] * old_weight + half_cov) / coverage);
            d[2] = (uint8_t)((green_w + d[2] * old_weight + half_cov) / coverage);
            d[3] = (uint8_t)((red_w   + d[3] * old_weight + half_cov) / coverage);
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Kernel 6b: fuji_render_fill_polygon_rgba (Exact PolygonFill.paint)        */
/* ------------------------------------------------------------------------- */

FUJI_RENDER_EXPORT void fuji_render_fill_polygon_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    const double* edge_x0,
    const double* edge_y0,
    const double* edge_x1,
    const double* edge_y1,
    int32_t edge_count,
    int32_t top,
    int32_t bottom,
    int32_t clip_left,
    int32_t clip_right,
    uint32_t color_aabbggrr,
    int32_t src_over
) {
    if (frame == NULL || edge_count <= 0 || top >= bottom || clip_left >= clip_right) return;
    const uint32_t alpha = (color_aabbggrr >> 24) & 0xffu;
    if (src_over && alpha == 0u) return;

    const uint32_t blue  = (color_aabbggrr >> 16) & 0xffu;
    const uint32_t green = (color_aabbggrr >> 8) & 0xffu;
    const uint32_t red   = color_aabbggrr & 0xffu;
    const uint32_t packed_le = alpha | (blue << 8) | (green << 16) | (red << 24);
    const int opaque_write = (!src_over || alpha == 255u);
    const uint32_t inv_alpha = 255u - alpha;
    const uint32_t alpha_weight = alpha * 255u;
    const uint32_t blue_w  = blue * alpha_weight;
    const uint32_t green_w = green * alpha_weight;
    const uint32_t red_w   = red * alpha_weight;
    const size_t row_stride = (size_t)canvas_width << 2;

    double stack_xs[256];
    double* xs = stack_xs;
    if (edge_count > 256) {
        xs = (double*)malloc((size_t)edge_count * sizeof(double));
        if (xs == NULL) return;
    }

    for (int32_t row = top; row < bottom; ++row) {
        const double yc = (double)row + 0.5;
        int32_t n = 0;
        for (int32_t e = 0; e < edge_count; ++e) {
            const double y0 = edge_y0[e];
            const double y1 = edge_y1[e];
            if ((y0 <= yc && y1 > yc) || (y1 <= yc && y0 > yc)) {
                const double x0 = edge_x0[e];
                xs[n++] = x0 + (yc - y0) * (edge_x1[e] - x0) / (y1 - y0);
            }
        }
        if (n < 2) continue;

        /* Insertion sort intersections (typically n=2..8 -> branch-predicted in registers) */
        for (int32_t i = 1; i < n; ++i) {
            const double key = xs[i];
            int32_t j = i - 1;
            while (j >= 0 && xs[j] > key) {
                xs[j + 1] = xs[j];
                --j;
            }
            xs[j + 1] = key;
        }

        uint8_t* row_ptr = frame + (size_t)row * row_stride;
        for (int32_t k = 0; k + 1 < n; k += 2) {
            int32_t start_col = (int32_t)ceil(xs[k] - 0.5);
            int32_t end_col   = (int32_t)ceil(xs[k + 1] - 0.5);
            if (start_col < clip_left) start_col = clip_left;
            else if (start_col > clip_right) start_col = clip_right;
            if (end_col < clip_left) end_col = clip_left;
            else if (end_col > clip_right) end_col = clip_right;
            const int32_t span = end_col - start_col;
            if (span <= 0) continue;

            uint8_t* d = row_ptr + ((size_t)start_col << 2);
            if (opaque_write) {
                if (span >= 8) {
                    fuji_render_fill_u32(d, span << 2, packed_le);
                } else {
                    for (int32_t i = 0; i < span; ++i, d += 4) {
                        store_u32_le(d, packed_le);
                    }
                }
            } else {
                for (int32_t i = 0; i < span; ++i, d += 4) {
                    const uint32_t old_alpha = d[0];
                    if (old_alpha == 0u) {
                        store_u32_le(d, packed_le);
                        continue;
                    }
                    const uint32_t old_weight = old_alpha * inv_alpha;
                    const uint32_t coverage = alpha_weight + old_weight;
                    const uint32_t half_cov = coverage >> 1;
                    d[0] = (uint8_t)div255_rounded_u32(coverage);
                    d[1] = (uint8_t)((blue_w  + d[1] * old_weight + half_cov) / coverage);
                    d[2] = (uint8_t)((green_w + d[2] * old_weight + half_cov) / coverage);
                    d[3] = (uint8_t)((red_w   + d[3] * old_weight + half_cov) / coverage);
                }
            }
        }
    }

    if (xs != stack_xs) {
        free(xs);
    }
}

/* ------------------------------------------------------------------------- */
/* Kernel 7: fuji_render_argb_to_pre (Single-pass opaque check + copy/premul)*/
/* ------------------------------------------------------------------------- */

static inline uint32_t argb_to_pre_scalar(uint32_t argb) {
    uint32_t a = argb >> 24;
    if (a == 255u) return argb;
    if (a == 0u) return 0u;
    uint32_t r = div255_rounded_u32(((argb >> 16) & 0xffu) * a);
    uint32_t g = div255_rounded_u32(((argb >> 8) & 0xffu) * a);
    uint32_t b = div255_rounded_u32((argb & 0xffu) * a);
    return (a << 24) | (r << 16) | (g << 8) | b;
}

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static inline __m512i div255_rounded_epu16_avx512(__m512i x) {
    const __m512i c128 = _mm512_set1_epi16(128);
    __m512i t = _mm512_add_epi16(x, c128);
    return _mm512_srli_epi16(_mm512_add_epi16(t, _mm512_srli_epi16(t, 8)), 8);
}

FUJI_AVX512_TARGET
static int32_t argb_to_pre_avx512(const uint32_t* src, uint32_t* dst, int32_t count) {
    const __m512i alpha_mask = _mm512_set1_epi32((int)0xff000000u);
    const __m512i zero = _mm512_setzero_si512();
    int32_t all_opaque = 1;
    int32_t i = 0;
    const int32_t lim16 = count & ~15;
    for (; i < lim16; i += 16) {
        __m512i px = _mm512_loadu_si512((const void*)(src + i));
        __m512i am = _mm512_and_si512(px, alpha_mask);
        if (_mm512_cmpeq_epi32_mask(am, alpha_mask) == 0xffffu) {
            if (dst != src) {
                _mm512_storeu_si512((void*)(dst + i), px);
            }
            continue;
        }
        all_opaque = 0;
        __m512i lo = _mm512_unpacklo_epi8(px, zero);
        __m512i hi = _mm512_unpackhi_epi8(px, zero);
        __m512i a_lo = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(lo, 0xff), 0xff);
        __m512i a_hi = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(hi, 0xff), 0xff);
        __m512i r_lo = div255_rounded_epu16_avx512(_mm512_mullo_epi16(lo, a_lo));
        __m512i r_hi = div255_rounded_epu16_avx512(_mm512_mullo_epi16(hi, a_hi));
        __m512i rgb = _mm512_andnot_si512(alpha_mask, _mm512_packus_epi16(r_lo, r_hi));
        _mm512_storeu_si512((void*)(dst + i), _mm512_or_si512(am, rgb));
    }
    for (; i < count; ++i) {
        uint32_t px = src[i];
        if ((px & 0xff000000u) == 0xff000000u) {
            if (dst != src) dst[i] = px;
        } else {
            all_opaque = 0;
            dst[i] = argb_to_pre_scalar(px);
        }
    }
    return all_opaque;
}
#endif

__attribute__((target("avx2")))
static int32_t argb_to_pre_avx2(const uint32_t* src, uint32_t* dst, int32_t count) {
    const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
    const __m256i zero = _mm256_setzero_si256();
    int32_t all_opaque = 1;
    int32_t i = 0;
    const int32_t lim8 = count & ~7;
    for (; i < lim8; i += 8) {
        __m256i px = _mm256_loadu_si256((const __m256i*)(src + i));
        __m256i am = _mm256_and_si256(px, alpha_mask);
        if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
            if (dst != src) {
                _mm256_storeu_si256((__m256i*)(dst + i), px);
            }
            continue;
        }
        all_opaque = 0;
        __m256i lo = _mm256_unpacklo_epi8(px, zero);
        __m256i hi = _mm256_unpackhi_epi8(px, zero);
        __m256i a_lo = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(lo, 0xff), 0xff);
        __m256i a_hi = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(hi, 0xff), 0xff);
        __m256i r_lo = div255_rounded_epu16_avx2(_mm256_mullo_epi16(lo, a_lo));
        __m256i r_hi = div255_rounded_epu16_avx2(_mm256_mullo_epi16(hi, a_hi));
        __m256i rgb = _mm256_andnot_si256(alpha_mask, _mm256_packus_epi16(r_lo, r_hi));
        _mm256_storeu_si256((__m256i*)(dst + i), _mm256_or_si256(am, rgb));
    }
    for (; i < count; ++i) {
        uint32_t px = src[i];
        if ((px & 0xff000000u) == 0xff000000u) {
            if (dst != src) dst[i] = px;
        } else {
            all_opaque = 0;
            dst[i] = argb_to_pre_scalar(px);
        }
    }
    return all_opaque;
}

static int32_t argb_to_pre_sse2(const uint32_t* src, uint32_t* dst, int32_t count) {
    const __m128i alpha_mask = _mm_set1_epi32((int)0xff000000u);
    int32_t all_opaque = 1;
    int32_t i = 0;
    const int32_t lim4 = count & ~3;
    for (; i < lim4; i += 4) {
        __m128i px = _mm_loadu_si128((const __m128i*)(src + i));
        __m128i am = _mm_and_si128(px, alpha_mask);
        if (_mm_movemask_epi8(_mm_cmpeq_epi32(am, alpha_mask)) == 0xffff) {
            if (dst != src) {
                _mm_storeu_si128((__m128i*)(dst + i), px);
            }
            continue;
        }
        all_opaque = 0;
        for (int k = 0; k < 4; ++k) {
            dst[i + k] = argb_to_pre_scalar(src[i + k]);
        }
    }
    for (; i < count; ++i) {
        uint32_t px = src[i];
        if ((px & 0xff000000u) == 0xff000000u) {
            if (dst != src) dst[i] = px;
        } else {
            all_opaque = 0;
            dst[i] = argb_to_pre_scalar(px);
        }
    }
    return all_opaque;
}
#endif

FUJI_RENDER_EXPORT int32_t fuji_render_argb_to_pre(const uint32_t* src_argb, uint32_t* dst_pre, int32_t pixel_count) {
    if (src_argb == NULL || dst_pre == NULL || pixel_count <= 0) return 1;
#if FUJI_X86_64
    int level = get_simd_level();
#if FUJI_HAS_AVX512_TARGET
    if (level >= FUJI_RENDER_SIMD_AVX512) {
        return argb_to_pre_avx512(src_argb, dst_pre, pixel_count);
    }
#endif
    if (level >= FUJI_RENDER_SIMD_AVX2) {
        return argb_to_pre_avx2(src_argb, dst_pre, pixel_count);
    }
    return argb_to_pre_sse2(src_argb, dst_pre, pixel_count);
#else
    int32_t all_opaque = 1;
    for (int32_t i = 0; i < pixel_count; ++i) {
        uint32_t px = src_argb[i];
        if ((px & 0xff000000u) == 0xff000000u) {
            if (dst_pre != src_argb) dst_pre[i] = px;
        } else {
            all_opaque = 0;
            dst_pre[i] = argb_to_pre_scalar(px);
        }
    }
    return all_opaque;
#endif
}

/* ========================================================================= */
/* SIMD-RENDER-V2 Kernel 8: LinearGradientRect (Scalar + AVX2 + AVX-512)     */
/* ========================================================================= */

static inline void paint_pixel_aabbggrr_scalar(uint8_t* d, uint32_t color_aabbggrr, int32_t src_over) {
    const uint32_t alpha = (color_aabbggrr >> 24) & 0xffu;
    if (src_over && alpha == 0u) return;
    const uint32_t blue  = (color_aabbggrr >> 16) & 0xffu;
    const uint32_t green = (color_aabbggrr >> 8) & 0xffu;
    const uint32_t red   = color_aabbggrr & 0xffu;
    const uint32_t packed_le = alpha | (blue << 8) | (green << 16) | (red << 24);
    if (!src_over || alpha == 255u) {
        store_u32_le(d, packed_le);
        return;
    }
    const uint32_t old_alpha = d[0];
    if (old_alpha == 0u) {
        store_u32_le(d, packed_le);
        return;
    }
    const uint32_t inv_alpha = 255u - alpha;
    const uint32_t alpha_weight = alpha * 255u;
    const uint32_t old_weight = old_alpha * inv_alpha;
    const uint32_t coverage = alpha_weight + old_weight;
    const uint32_t half_cov = coverage >> 1;
    d[0] = (uint8_t)div255_rounded_u32(coverage);
    d[1] = (uint8_t)((blue  * alpha_weight + d[1] * old_weight + half_cov) / coverage);
    d[2] = (uint8_t)((green * alpha_weight + d[2] * old_weight + half_cov) / coverage);
    d[3] = (uint8_t)((red   * alpha_weight + d[3] * old_weight + half_cov) / coverage);
}

static inline int32_t morton_x_offset(int32_t x) {
    return (x >> 3) * 64 + (x & 1) + ((x & 2) << 1) + ((x & 4) << 2);
}

static inline int32_t morton_y_offset(int32_t y, int32_t pitch) {
    return (y >> 3) * pitch * 64 + ((y & 1) << 1) + ((y & 2) << 2) + ((y & 4) << 3);
}

static inline int32_t texel_fixed8(double x, int32_t size) {
    double scaled = (x - 0.5) * 256.0 + 0.5;
    int32_t last = (size - 1) * 256;
    if (!(scaled > 0.0)) return 0;
    if (scaled >= (double)last) return last;
    return (int32_t)scaled;
}

static inline int32_t clamp_q8_bound(double pos, int32_t max_q8) {
    if (!(pos > 0.0)) return 0;
    double scaled = pos * 256.0 + 0.5;
    if (scaled >= (double)max_q8) return max_q8;
    return (int32_t)floor(scaled);
}

typedef struct {
    int32_t offset0;
    int32_t offset1;
    uint32_t fraction;
} MortonTexelPair;

static inline MortonTexelPair morton_columns(double u, int32_t width) {
    int32_t fx = texel_fixed8(u, width);
    int32_t x = fx >> 8;
    MortonTexelPair columns = {
        morton_x_offset(x) * 4, morton_x_offset(x + 1 < width ? x + 1 : x) * 4, (uint32_t)fx & 255u
    };
    return columns;
}

static inline MortonTexelPair morton_rows(double v, int32_t height, int32_t pitch) {
    int32_t fy = texel_fixed8(v, height);
    int32_t y = fy >> 8;
    MortonTexelPair rows = {
        morton_y_offset(y, pitch) * 4, morton_y_offset(y + 1 < height ? y + 1 : y, pitch) * 4, (uint32_t)fy & 255u
    };
    return rows;
}

static inline uint32_t filter_morton_pixel(const uint8_t* tex, MortonTexelPair columns, MortonTexelPair rows) {
    const uint8_t* p00 = tex + columns.offset0 + rows.offset0;
    const uint8_t* p10 = tex + columns.offset1 + rows.offset0;
    const uint8_t* p01 = tex + columns.offset0 + rows.offset1;
    const uint8_t* p11 = tex + columns.offset1 + rows.offset1;
    uint32_t wx = columns.fraction;
    uint32_t wy = rows.fraction;
    uint32_t w00 = p00[0] * (256u - wx) * (256u - wy);
    uint32_t w10 = p10[0] * wx * (256u - wy);
    uint32_t w01 = p01[0] * (256u - wx) * wy;
    uint32_t w11 = p11[0] * wx * wy;
    uint32_t weight = w00 + w10 + w01 + w11;
    uint32_t alpha = (weight + 32768u) >> 16;
    if (!alpha) return 0;
    uint32_t color = alpha;
    for (int channel = 1; channel <= 3; ++channel) {
        uint32_t sample = p00[channel];
        if (sample != p10[channel] || sample != p01[channel] || sample != p11[channel]) {
            uint64_t sum = (uint64_t)p00[channel] * w00 + (uint64_t)p10[channel] * w10 +
                (uint64_t)p01[channel] * w01 + (uint64_t)p11[channel] * w11;
            sample = (uint32_t)((sum + (uint64_t)(weight / 2u)) / (uint64_t)weight);
        }
        color = (color << 8) | sample;
    }
    return color;
}

static inline void accum_morton_area_row(
    const uint8_t* tex, int32_t row_off, uint64_t cov_y,
    int32_t minify_u, int32_t lo_x, int32_t hi_x,
    MortonTexelPair cols,
    uint64_t* alpha_sum, uint64_t* b_sum, uint64_t* g_sum, uint64_t* r_sum
) {
    if (minify_u) {
        int32_t tx0 = lo_x >> 8;
        int32_t tx1 = (hi_x - 1) >> 8;
        for (int32_t tx = tx0; tx <= tx1; ++tx) {
            int32_t c_lo = (tx << 8) > lo_x ? (tx << 8) : lo_x;
            int32_t c_hi = ((tx + 1) << 8) < hi_x ? ((tx + 1) << 8) : hi_x;
            uint64_t cov_x = (uint64_t)(c_hi - c_lo);
            const uint8_t* p = tex + row_off + morton_x_offset(tx) * 4;
            uint64_t a = p[0];
            if (a != 0u) {
                uint64_t aw = a * (cov_x * cov_y);
                *alpha_sum += aw;
                *b_sum += (uint64_t)p[1] * aw;
                *g_sum += (uint64_t)p[2] * aw;
                *r_sum += (uint64_t)p[3] * aw;
            }
        }
    } else {
        uint32_t wx = cols.fraction;
        const uint8_t* p0 = tex + row_off + cols.offset0;
        uint64_t a0 = p0[0];
        if (a0 != 0u) {
            uint64_t aw0 = a0 * ((uint64_t)(256u - wx) * cov_y);
            *alpha_sum += aw0;
            *b_sum += (uint64_t)p0[1] * aw0;
            *g_sum += (uint64_t)p0[2] * aw0;
            *r_sum += (uint64_t)p0[3] * aw0;
        }
        if (wx != 0u) {
            const uint8_t* p1 = tex + row_off + cols.offset1;
            uint64_t a1 = p1[0];
            if (a1 != 0u) {
                uint64_t aw1 = a1 * ((uint64_t)wx * cov_y);
                *alpha_sum += aw1;
                *b_sum += (uint64_t)p1[1] * aw1;
                *g_sum += (uint64_t)p1[2] * aw1;
                *r_sum += (uint64_t)p1[3] * aw1;
            }
        }
    }
}

static inline uint32_t filter_morton_area_pixel(
    const uint8_t* tex, int32_t width, int32_t height, int32_t pitch,
    double u, double v, int32_t minify_u, int32_t minify_v, double half_u, double half_v
) {
    int32_t lo_x = 0, hi_x = 0, total_x;
    MortonTexelPair cols = {0, 0, 0};
    if (minify_u) {
        int32_t max_x = width << 8;
        lo_x = clamp_q8_bound(u - half_u, max_x);
        hi_x = clamp_q8_bound(u + half_u, max_x);
        if (hi_x <= lo_x) {
            int32_t tx = (int32_t)floor(u);
            if (tx < 0) tx = 0;
            else if (tx >= width) tx = width - 1;
            lo_x = tx << 8;
            hi_x = (tx + 1) << 8;
        }
        total_x = hi_x - lo_x;
    } else {
        cols = morton_columns(u, width);
        total_x = 256;
    }

    int32_t lo_y = 0, hi_y = 0, total_y;
    MortonTexelPair rows = {0, 0, 0};
    if (minify_v) {
        int32_t max_y = height << 8;
        lo_y = clamp_q8_bound(v - half_v, max_y);
        hi_y = clamp_q8_bound(v + half_v, max_y);
        if (hi_y <= lo_y) {
            int32_t ty = (int32_t)floor(v);
            if (ty < 0) ty = 0;
            else if (ty >= height) ty = height - 1;
            lo_y = ty << 8;
            hi_y = (ty + 1) << 8;
        }
        total_y = hi_y - lo_y;
    } else {
        rows = morton_rows(v, height, pitch);
        total_y = 256;
    }

    uint64_t alpha_sum = 0, b_sum = 0, g_sum = 0, r_sum = 0;
    if (minify_v) {
        int32_t ty0 = lo_y >> 8;
        int32_t ty1 = (hi_y - 1) >> 8;
        for (int32_t ty = ty0; ty <= ty1; ++ty) {
            int32_t r_lo = (ty << 8) > lo_y ? (ty << 8) : lo_y;
            int32_t r_hi = ((ty + 1) << 8) < hi_y ? ((ty + 1) << 8) : hi_y;
            accum_morton_area_row(
                tex, morton_y_offset(ty, pitch) * 4, (uint64_t)(r_hi - r_lo),
                minify_u, lo_x, hi_x, cols, &alpha_sum, &b_sum, &g_sum, &r_sum
            );
        }
    } else {
        uint32_t wy = rows.fraction;
        accum_morton_area_row(
            tex, rows.offset0, (uint64_t)(256u - wy),
            minify_u, lo_x, hi_x, cols, &alpha_sum, &b_sum, &g_sum, &r_sum
        );
        if (wy != 0u) {
            accum_morton_area_row(
                tex, rows.offset1, (uint64_t)wy,
                minify_u, lo_x, hi_x, cols, &alpha_sum, &b_sum, &g_sum, &r_sum
            );
        }
    }

    uint64_t total_area = (uint64_t)total_x * (uint64_t)total_y;
    uint32_t alpha = (uint32_t)((alpha_sum + (total_area >> 1)) / total_area);
    if (alpha == 0u) return 0u;
    uint64_t half_a = alpha_sum >> 1;
    uint32_t b = (uint32_t)((b_sum + half_a) / alpha_sum);
    uint32_t g = (uint32_t)((g_sum + half_a) / alpha_sum);
    uint32_t r = (uint32_t)((r_sum + half_a) / alpha_sum);
    return (alpha << 24) | (b << 16) | (g << 8) | r;
}

FUJI_RENDER_EXPORT void fuji_render_blit_filtered_morton(
    const uint8_t* texture, uint8_t* frame, int32_t canvas_width, int32_t width, int32_t height,
    int32_t pitch_tiles, int32_t top, int32_t bottom, int32_t left, int32_t right,
    double u0, double v0, double du_dx, double dv_dx, double du_dy, double dv_dy
) {
    if (texture == NULL || frame == NULL || canvas_width <= 0 || width <= 0 || height <= 0 ||
        pitch_tiles <= 0 || top >= bottom || left >= right) return;
    double span_u = fabs(du_dx) + fabs(du_dy);
    double span_v = fabs(dv_dx) + fabs(dv_dy);
    int32_t minify_u = (span_u > 1.0);
    int32_t minify_v = (span_v > 1.0);
    int32_t use_area = minify_u || minify_v;
    double half_u = (minify_u && isfinite(span_u)) ? span_u * 0.5 : 0.5;
    double half_v = (minify_v && isfinite(span_v)) ? span_v * 0.5 : 0.5;

    if (!use_area && du_dy == 0.0 && dv_dx == 0.0) {
        /* An axis-aligned menu label reuses the same column taps for every scanline. */
        MortonTexelPair columns[128];
        double u = u0;
        for (int32_t block = left; block < right; block += 128) {
            int32_t count = right - block < 128 ? right - block : 128;
            for (int32_t x = 0; x < count; ++x, u += du_dx) {
                if (!(u >= 0.0 && u < (double)width)) {
                    columns[x].offset0 = -1;
                } else {
                    columns[x] = morton_columns(u, width);
                }
            }
            for (int32_t y = top; y < bottom; ++y) {
                double v = v0 + (y - top) * dv_dy;
                if (!(v >= 0.0 && v < (double)height)) continue;
                MortonTexelPair rows = morton_rows(v, height, pitch_tiles);
                uint8_t* dst = frame + ((size_t)y * (size_t)canvas_width + (size_t)block) * 4;
                for (int32_t x = 0; x < count; ++x, dst += 4) {
                    if (columns[x].offset0 < 0) continue;
                    paint_pixel_aabbggrr_scalar(dst, filter_morton_pixel(texture, columns[x], rows), 1);
                }
            }
        }
        return;
    }

    for (int32_t y = top; y < bottom; ++y) {
        double u = u0 + (y - top) * du_dy;
        double v = v0 + (y - top) * dv_dy;
        uint8_t* dst = frame + ((size_t)y * (size_t)canvas_width + (size_t)left) * 4;
        for (int32_t x = left; x < right; ++x, dst += 4, u += du_dx, v += dv_dx) {
            if (u >= 0.0 && u < (double)width && v >= 0.0 && v < (double)height) {
                uint32_t color = use_area
                    ? filter_morton_area_pixel(texture, width, height, pitch_tiles, u, v, minify_u, minify_v, half_u, half_v)
                    : filter_morton_pixel(texture, morton_columns(u, width), morton_rows(v, height, pitch_tiles));
                paint_pixel_aabbggrr_scalar(dst, color, 1);
            }
        }
    }
}

static inline void blend_row_solid_translucent_scalar(
    uint8_t* d, int32_t width, uint32_t alpha, uint32_t blue, uint32_t green, uint32_t red, uint32_t packed_le
) {
    const uint32_t inv_alpha = 255u - alpha;
    const uint32_t alpha_weight = alpha * 255u;
    const uint32_t blue_w  = blue * alpha_weight;
    const uint32_t green_w = green * alpha_weight;
    const uint32_t red_w   = red * alpha_weight;
    for (int32_t i = 0; i < width; ++i, d += 4) {
        const uint32_t old_alpha = d[0];
        if (old_alpha == 0u) {
            store_u32_le(d, packed_le);
            continue;
        }
        const uint32_t old_weight = old_alpha * inv_alpha;
        const uint32_t coverage = alpha_weight + old_weight;
        const uint32_t half_cov = coverage >> 1;
        d[0] = (uint8_t)div255_rounded_u32(coverage);
        d[1] = (uint8_t)((blue_w  + d[1] * old_weight + half_cov) / coverage);
        d[2] = (uint8_t)((green_w + d[2] * old_weight + half_cov) / coverage);
        d[3] = (uint8_t)((red_w   + d[3] * old_weight + half_cov) / coverage);
    }
}

#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void fill_linear_gradient_general_avx512(
    uint8_t* frame, int32_t canvas_width, int32_t canvas_height,
    int32_t left, int32_t top, int32_t right, int32_t bottom,
    double m3, double m4, double m5,
    const uint32_t* ramp_lut, int32_t max_idx, int32_t src_over
) {
    const __m512d v_zero = _mm512_setzero_pd();
    const __m512d v_one  = _mm512_set1_pd(1.0);
    const __m512d v_half = _mm512_set1_pd(0.5);
    const __m512d v_max  = _mm512_set1_pd((double)max_idx);
    const __m512d v_m3   = _mm512_set1_pd(m3);
    const __m512d v_step = _mm512_setr_pd(0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5);
    const __m256i v_i0   = _mm256_setzero_si256();
    const __m256i v_imax = _mm256_set1_epi32(max_idx);
    const __m256i bswap  = _mm256_setr_epi8(
        3, 2, 1, 0,  7, 6, 5, 4,  11, 10, 9, 8,  15, 14, 13, 12,
        3, 2, 1, 0,  7, 6, 5, 4,  11, 10, 9, 8,  15, 14, 13, 12
    );
    const __m256i a_mask = _mm256_set1_epi32((int)0xff000000u);
    const size_t row_stride = (size_t)canvas_width << 2;

    for (int32_t row = top; row < bottom; ++row) {
        const double y_vg = (double)canvas_height - ((double)row + 0.5);
        const double row_base = m4 * y_vg + m5;
        const __m512d v_rbase = _mm512_set1_pd(row_base);
        uint8_t* row_ptr = frame + (size_t)row * row_stride;
        int32_t col = left;
        for (; col + 8 <= right; col += 8) {
            __m512d vc = _mm512_add_pd(_mm512_set1_pd((double)col), v_step);
            __m512d vt = _mm512_add_pd(_mm512_mul_pd(v_m3, vc), v_rbase);
            vt = _mm512_min_pd(v_one, _mm512_max_pd(v_zero, vt));
            __m512d vpos = _mm512_add_pd(_mm512_mul_pd(vt, v_max), v_half);
            __m256i vidx = _mm256_max_epi32(v_i0, _mm256_min_epi32(v_imax, _mm512_cvttpd_epi32(vpos)));
            __m256i cols = _mm256_i32gather_epi32((const int32_t*)ramp_lut, vidx, 4);
            if (!src_over || _mm256_movemask_epi8(_mm256_cmpeq_epi32(_mm256_and_si256(cols, a_mask), a_mask)) == -1) {
                __m256i packed = _mm256_shuffle_epi8(cols, bswap);
                _mm256_storeu_si256((__m256i*)(row_ptr + ((size_t)col << 2)), packed);
            } else {
                uint32_t tmp[8];
                _mm256_storeu_si256((__m256i*)tmp, cols);
                uint8_t* d = row_ptr + ((size_t)col << 2);
                for (int k = 0; k < 8; ++k, d += 4) {
                    paint_pixel_aabbggrr_scalar(d, tmp[k], src_over);
                }
            }
        }
        for (; col < right; ++col) {
            double t = m3 * ((double)col + 0.5) + row_base;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            int32_t idx = (int32_t)(t * (double)max_idx + 0.5);
            if (idx < 0) idx = 0; else if (idx > max_idx) idx = max_idx;
            paint_pixel_aabbggrr_scalar(row_ptr + ((size_t)col << 2), ramp_lut[idx], src_over);
        }
    }
}
#endif

#if FUJI_X86_64
__attribute__((target("avx2")))
static void fill_linear_gradient_general_avx2(
    uint8_t* frame, int32_t canvas_width, int32_t canvas_height,
    int32_t left, int32_t top, int32_t right, int32_t bottom,
    double m3, double m4, double m5,
    const uint32_t* ramp_lut, int32_t max_idx, int32_t src_over
) {
    const __m256d v_zero = _mm256_setzero_pd();
    const __m256d v_one  = _mm256_set1_pd(1.0);
    const __m256d v_half = _mm256_set1_pd(0.5);
    const __m256d v_max  = _mm256_set1_pd((double)max_idx);
    const __m256d v_m3   = _mm256_set1_pd(m3);
    const __m256d v_step = _mm256_setr_pd(0.5, 1.5, 2.5, 3.5);
    const __m128i v_i0   = _mm_setzero_si128();
    const __m128i v_imax = _mm_set1_epi32(max_idx);
    const __m128i bswap  = _mm_setr_epi8(3, 2, 1, 0,  7, 6, 5, 4,  11, 10, 9, 8,  15, 14, 13, 12);
    const __m128i a_mask = _mm_set1_epi32((int)0xff000000u);
    const size_t row_stride = (size_t)canvas_width << 2;

    for (int32_t row = top; row < bottom; ++row) {
        const double y_vg = (double)canvas_height - ((double)row + 0.5);
        const double row_base = m4 * y_vg + m5;
        const __m256d v_rbase = _mm256_set1_pd(row_base);
        uint8_t* row_ptr = frame + (size_t)row * row_stride;
        int32_t col = left;
        for (; col + 4 <= right; col += 4) {
            __m256d vc = _mm256_add_pd(_mm256_set1_pd((double)col), v_step);
            __m256d vt = _mm256_add_pd(_mm256_mul_pd(v_m3, vc), v_rbase);
            vt = _mm256_min_pd(v_one, _mm256_max_pd(v_zero, vt));
            __m256d vpos = _mm256_add_pd(_mm256_mul_pd(vt, v_max), v_half);
            __m128i vidx = _mm_max_epi32(v_i0, _mm_min_epi32(v_imax, _mm256_cvttpd_epi32(vpos)));
            __m128i cols = _mm_i32gather_epi32((const int32_t*)ramp_lut, vidx, 4);
            if (!src_over || _mm_movemask_epi8(_mm_cmpeq_epi32(_mm_and_si128(cols, a_mask), a_mask)) == 0xffff) {
                __m128i packed = _mm_shuffle_epi8(cols, bswap);
                _mm_storeu_si128((__m128i*)(row_ptr + ((size_t)col << 2)), packed);
            } else {
                uint32_t tmp[4];
                _mm_storeu_si128((__m128i*)tmp, cols);
                uint8_t* d = row_ptr + ((size_t)col << 2);
                for (int k = 0; k < 4; ++k, d += 4) {
                    paint_pixel_aabbggrr_scalar(d, tmp[k], src_over);
                }
            }
        }
        for (; col < right; ++col) {
            double t = m3 * ((double)col + 0.5) + row_base;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            int32_t idx = (int32_t)(t * (double)max_idx + 0.5);
            if (idx < 0) idx = 0; else if (idx > max_idx) idx = max_idx;
            paint_pixel_aabbggrr_scalar(row_ptr + ((size_t)col << 2), ramp_lut[idx], src_over);
        }
    }
}
#endif

FUJI_RENDER_EXPORT void fuji_render_fill_linear_gradient_rect_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    int32_t canvas_height,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom,
    double m3,
    double m4,
    double m5,
    const uint32_t* ramp_lut,
    int32_t ramp_len,
    int32_t src_over
) {
    if (frame == NULL || ramp_lut == NULL || ramp_len <= 0 || left >= right || top >= bottom) return;
    const int32_t max_idx = ramp_len - 1;
    const int32_t width = right - left;
    const size_t row_stride = (size_t)canvas_width << 2;

    if (m3 == 0.0) {
        const int32_t row_bytes = width << 2;
        for (int32_t row = top; row < bottom; ++row) {
            const double y_vg = (double)canvas_height - ((double)row + 0.5);
            double t = m4 * y_vg + m5;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            int32_t idx = (int32_t)(t * (double)max_idx + 0.5);
            if (idx < 0) idx = 0; else if (idx > max_idx) idx = max_idx;
            const uint32_t color = ramp_lut[idx];
            const uint32_t alpha = (color >> 24) & 0xffu;
            if (src_over && alpha == 0u) continue;
            const uint32_t blue  = (color >> 16) & 0xffu;
            const uint32_t green = (color >> 8) & 0xffu;
            const uint32_t red   = color & 0xffu;
            const uint32_t packed_le = alpha | (blue << 8) | (green << 16) | (red << 24);
            uint8_t* d = frame + (size_t)row * row_stride + ((size_t)left << 2);
            if (!src_over || alpha == 255u) {
                fuji_render_fill_u32(d, row_bytes, packed_le);
            } else {
                blend_row_solid_translucent_scalar(d, width, alpha, blue, green, red, packed_le);
            }
        }
        return;
    }

#if FUJI_X86_64
    int level = get_simd_level();
#if FUJI_HAS_AVX512_TARGET
    if (level >= FUJI_RENDER_SIMD_AVX512) {
        fill_linear_gradient_general_avx512(frame, canvas_width, canvas_height, left, top, right, bottom, m3, m4, m5, ramp_lut, max_idx, src_over);
        return;
    }
#endif
    if (level >= FUJI_RENDER_SIMD_AVX2) {
        fill_linear_gradient_general_avx2(frame, canvas_width, canvas_height, left, top, right, bottom, m3, m4, m5, ramp_lut, max_idx, src_over);
        return;
    }
#endif
    for (int32_t row = top; row < bottom; ++row) {
        const double y_vg = (double)canvas_height - ((double)row + 0.5);
        const double row_base = m4 * y_vg + m5;
        uint8_t* row_ptr = frame + (size_t)row * row_stride;
        for (int32_t col = left; col < right; ++col) {
            double t = m3 * ((double)col + 0.5) + row_base;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            int32_t idx = (int32_t)(t * (double)max_idx + 0.5);
            if (idx < 0) idx = 0; else if (idx > max_idx) idx = max_idx;
            paint_pixel_aabbggrr_scalar(row_ptr + ((size_t)col << 2), ramp_lut[idx], src_over);
        }
    }
}

/* ========================================================================= */
/* SIMD-RENDER-V2 Kernel 9: Fused 3-Plane Composite + Q10 Color Matrix       */
/* ========================================================================= */

static inline uint32_t clamp_u8_i32(int32_t v) {
    if (v <= 0) return 0u;
    if (v >= 255) return 255u;
    return (uint32_t)v;
}

static inline uint32_t apply_color_matrix_scalar(uint32_t argb, const int32_t* c, int32_t mode) {
    if (mode == 0 || c == NULL) return argb;
    int32_t sr = (int32_t)((argb >> 16) & 0xffu);
    int32_t sg = (int32_t)((argb >> 8) & 0xffu);
    int32_t sb = (int32_t)(argb & 0xffu);
    uint32_t r, g, b;
    if (mode == 1) {
        r = clamp_u8_i32((sr * c[0] + 512) >> 10);
        g = clamp_u8_i32((sg * c[4] + 512) >> 10);
        b = clamp_u8_i32((sb * c[8] + 512) >> 10);
    } else {
        r = clamp_u8_i32((c[0] * sr + c[1] * sg + c[2] * sb + 512) >> 10);
        g = clamp_u8_i32((c[3] * sr + c[4] * sg + c[5] * sb + 512) >> 10);
        b = clamp_u8_i32((c[6] * sr + c[7] * sg + c[8] * sb + 512) >> 10);
    }
    return 0xff000000u | (r << 16) | (g << 8) | b;
}

static inline uint32_t blend_pic1_rgb_over_bg_scalar(uint32_t rgb, int32_t pic1_alpha, uint32_t bg) {
    if (pic1_alpha >= 255) return 0xff000000u | rgb;
    uint32_t a = (uint32_t)pic1_alpha;
    uint32_t inv = 255u - a;
    uint32_t pr = div255_rounded_u32(((rgb >> 16) & 0xffu) * a + ((bg >> 16) & 0xffu) * inv);
    uint32_t pg = div255_rounded_u32(((rgb >> 8) & 0xffu) * a + ((bg >> 8) & 0xffu) * inv);
    uint32_t pb = div255_rounded_u32((rgb & 0xffu) * a + (bg & 0xffu) * inv);
    return 0xff000000u | (pr << 16) | (pg << 8) | pb;
}

static inline uint32_t premul_if_needed_scalar(uint32_t px, int32_t* all_opaque) {
    if ((px & 0xff000000u) == 0xff000000u) return px;
    *all_opaque = 0;
    return argb_to_pre_scalar(px);
}

#if FUJI_X86_64
__attribute__((target("avx2")))
static inline __m256i apply_color_matrix_8_avx2(__m256i px, const int32_t* c, int32_t mode) {
    if (mode == 0) return px;
    const __m256i mask_ff = _mm256_set1_epi32(0xff);
    const __m256i zero = _mm256_setzero_si256();
    const __m256i c512 = _mm256_set1_epi32(512);
    const __m256i opaque = _mm256_set1_epi32((int)0xff000000u);
    __m256i sr = _mm256_and_si256(_mm256_srli_epi32(px, 16), mask_ff);
    __m256i sg = _mm256_and_si256(_mm256_srli_epi32(px, 8), mask_ff);
    __m256i sb = _mm256_and_si256(px, mask_ff);
    __m256i r, g, b;
    if (mode == 1) {
        r = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(sr, _mm256_set1_epi32(c[0])), c512), 10);
        g = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(sg, _mm256_set1_epi32(c[4])), c512), 10);
        b = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(sb, _mm256_set1_epi32(c[8])), c512), 10);
    } else {
        r = _mm256_srai_epi32(
            _mm256_add_epi32(
                _mm256_add_epi32(_mm256_mullo_epi32(sr, _mm256_set1_epi32(c[0])),
                                 _mm256_mullo_epi32(sg, _mm256_set1_epi32(c[1]))),
                _mm256_add_epi32(_mm256_mullo_epi32(sb, _mm256_set1_epi32(c[2])), c512)
            ), 10);
        g = _mm256_srai_epi32(
            _mm256_add_epi32(
                _mm256_add_epi32(_mm256_mullo_epi32(sr, _mm256_set1_epi32(c[3])),
                                 _mm256_mullo_epi32(sg, _mm256_set1_epi32(c[4]))),
                _mm256_add_epi32(_mm256_mullo_epi32(sb, _mm256_set1_epi32(c[5])), c512)
            ), 10);
        b = _mm256_srai_epi32(
            _mm256_add_epi32(
                _mm256_add_epi32(_mm256_mullo_epi32(sr, _mm256_set1_epi32(c[6])),
                                 _mm256_mullo_epi32(sg, _mm256_set1_epi32(c[7]))),
                _mm256_add_epi32(_mm256_mullo_epi32(sb, _mm256_set1_epi32(c[8])), c512)
            ), 10);
    }
    r = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, r));
    g = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, g));
    b = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, b));
    return _mm256_or_si256(opaque, _mm256_or_si256(_mm256_slli_epi32(r, 16), _mm256_or_si256(_mm256_slli_epi32(g, 8), b)));
}

__attribute__((target("avx2")))
static inline __m256i uyvy4_to_rgb8_avx2(const uint8_t* uyvy16) {
    __m128i raw128 = _mm_loadu_si128((const __m128i*)uyvy16);
    const __m128i mask_ff_128 = _mm_set1_epi32(0xff);
    const __m128i c128_128 = _mm_set1_epi32(128);
    __m128i u4 = _mm_and_si128(raw128, mask_ff_128);
    __m128i v4 = _mm_and_si128(_mm_srli_epi32(raw128, 16), mask_ff_128);
    __m128i d4 = _mm_sub_epi32(u4, c128_128);
    __m128i e4 = _mm_sub_epi32(v4, c128_128);
    __m128i dr4 = _mm_srai_epi32(_mm_add_epi32(_mm_mullo_epi32(_mm_set1_epi32(359), e4), c128_128), 8);
    __m128i dg4 = _mm_srai_epi32(_mm_sub_epi32(_mm_sub_epi32(c128_128, _mm_mullo_epi32(_mm_set1_epi32(88), d4)), _mm_mullo_epi32(_mm_set1_epi32(183), e4)), 8);
    __m128i db4 = _mm_srai_epi32(_mm_add_epi32(_mm_mullo_epi32(_mm_set1_epi32(454), d4), c128_128), 8);
    __m256i dr8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(dr4, dr4)), _mm_unpackhi_epi32(dr4, dr4), 1);
    __m256i dg8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(dg4, dg4)), _mm_unpackhi_epi32(dg4, dg4), 1);
    __m256i db8 = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(db4, db4)), _mm_unpackhi_epi32(db4, db4), 1);
    __m256i mp = _mm256_inserti128_si256(_mm256_castsi128_si256(_mm_unpacklo_epi32(raw128, raw128)), _mm_unpackhi_epi32(raw128, raw128), 1);
    __m256i is_zero = _mm256_or_si256(
        _mm256_cmpeq_epi32(mp, _mm256_setzero_si256()),
        _mm256_or_si256(
            _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x80800000u)),
            _mm256_cmpeq_epi32(mp, _mm256_set1_epi32((int)0x00008080u))
        )
    );
    const __m256i mask_ff = _mm256_set1_epi32(0xff);
    const __m256i zero = _mm256_setzero_si256();
    __m256i y = _mm256_blend_epi32(_mm256_and_si256(_mm256_srli_epi32(mp, 8), mask_ff), _mm256_srli_epi32(mp, 24), 0xAA);
    __m256i r = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dr8)));
    __m256i g = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, dg8)));
    __m256i b = _mm256_max_epi32(zero, _mm256_min_epi32(mask_ff, _mm256_add_epi32(y, db8)));
    return _mm256_andnot_si256(is_zero, _mm256_or_si256(_mm256_slli_epi32(r, 16), _mm256_or_si256(_mm256_slli_epi32(g, 8), b)));
}

__attribute__((target("avx2")))
static inline __m256i premul_if_needed_8_avx2(__m256i px, int32_t* all_opaque) {
    const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
    __m256i am = _mm256_and_si256(px, alpha_mask);
    if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
        return px;
    }
    *all_opaque = 0;
    const __m256i zero = _mm256_setzero_si256();
    __m256i lo = _mm256_unpacklo_epi8(px, zero);
    __m256i hi = _mm256_unpackhi_epi8(px, zero);
    __m256i a_lo = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(lo, 0xff), 0xff);
    __m256i a_hi = _mm256_shufflehi_epi16(_mm256_shufflelo_epi16(hi, 0xff), 0xff);
    __m256i r_lo = div255_rounded_epu16_avx2(_mm256_mullo_epi16(lo, a_lo));
    __m256i r_hi = div255_rounded_epu16_avx2(_mm256_mullo_epi16(hi, a_hi));
    __m256i rgb = _mm256_andnot_si256(alpha_mask, _mm256_packus_epi16(r_lo, r_hi));
    return _mm256_or_si256(am, rgb);
}

__attribute__((target("avx2")))
static int32_t composite_planes_matrix_avx2_impl(
    const uint8_t* osd_base, int32_t osd_stride,
    const uint8_t* pic0_bytes, int32_t p0x, int32_t p0y, int32_t p0r, int32_t p0b, int32_t p0s, int32_t p0_active,
    const uint8_t* pic1_bytes, int32_t p1x, int32_t p1y, int32_t p1r, int32_t p1b, int32_t p1s, int32_t p1_alpha, int32_t p1_active,
    uint32_t backdrop_argb,
    int32_t native_1to1, const int32_t* source_rows, const int32_t* source_cols,
    int32_t out_width, int32_t out_height,
    const int32_t* cmat, int32_t mmode,
    uint32_t* output_argb,
    int32_t to_argb_pre
) {
    const __m256i shuf = _mm256_setr_epi8(
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12,
        1, 2, 3, 0,  5, 6, 7, 4,  9, 10, 11, 8,  13, 14, 15, 12
    );
    const __m256i alpha_mask = _mm256_set1_epi32((int)0xff000000u);
    const __m256i v_backdrop = _mm256_set1_epi32((int)backdrop_argb);
    const __m256i v_p1_alpha_hi = _mm256_set1_epi32((int)((uint32_t)p1_alpha << 24));
    const int32_t need_premul = (to_argb_pre != 0 && mmode == 0 && (backdrop_argb & 0xff000000u) != 0xff000000u);
    const uint32_t backdrop_mat = apply_color_matrix_scalar(backdrop_argb, cmat, mmode);
    const uint32_t backdrop_fill = need_premul ? argb_to_pre_scalar(backdrop_mat) : backdrop_mat;
    int32_t all_opaque = 1;

    if (native_1to1) {
        const int32_t lim8 = out_width & ~7;
        for (int32_t row = 0; row < out_height; ++row) {
            uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
            const uint8_t* osd_row = (osd_base != NULL) ? (osd_base + (size_t)row * (size_t)osd_stride) : NULL;
            const int in_p0_y = p0_active && (row >= p0y && row < p0b);
            const int in_p1_y = p1_active && (row >= p1y && row < p1b);
            const uint8_t* p0_row = in_p0_y ? (pic0_bytes + (size_t)(row - p0y) * (size_t)p0s) : NULL;
            const uint8_t* p1_row = in_p1_y ? (pic1_bytes + (size_t)(row - p1y) * (size_t)p1s) : NULL;

            if (!in_p0_y && !in_p1_y) {
                if (osd_row == NULL) {
                    fill_u32_avx2((uint8_t*)drow, out_width * 4, backdrop_fill);
                    if (need_premul) all_opaque = 0;
                    continue;
                }
                int32_t col = 0;
                for (; col < lim8; col += 8) {
                    __m256i argb = _mm256_shuffle_epi8(_mm256_loadu_si256((const __m256i*)(osd_row + (col << 2))), shuf);
                    __m256i am = _mm256_and_si256(argb, alpha_mask);
                    __m256i out;
                    if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
                        out = argb;
                    } else if (_mm256_testz_si256(am, am)) {
                        out = v_backdrop;
                    } else {
                        out = blend8_argb_over_bg_avx2(argb, v_backdrop);
                    }
                    __m256i res = apply_color_matrix_8_avx2(out, cmat, mmode);
                    if (__builtin_expect(need_premul, 0)) res = premul_if_needed_8_avx2(res, &all_opaque);
                    _mm256_storeu_si256((__m256i*)(drow + col), res);
                }
                for (; col < out_width; ++col) {
                    uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                    uint32_t res = apply_color_matrix_scalar(blend_osd_over_bg(argb, backdrop_argb), cmat, mmode);
                    if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
                    drow[col] = res;
                }
                continue;
            }

            int32_t col = 0;
            while (col < out_width) {
                /* Fastpath 8-pixel vector block when completely inside or completely outside each active PIC plane */
                const int p0_all_in = in_p0_y && col >= p0x && (col + 8) <= p0r && (((col - p0x) & 1) == 0);
                const int p0_all_out = !in_p0_y || (col + 8) <= p0x || col >= p0r;
                const int p1_all_in = in_p1_y && col >= p1x && (col + 8) <= p1r && (((col - p1x) & 1) == 0);
                const int p1_all_out = !in_p1_y || (col + 8) <= p1x || col >= p1r;
                if ((col + 8 <= out_width) && (p0_all_in || p0_all_out) && (p1_all_in || p1_all_out)) {
                    if (osd_row != NULL) {
                        __m256i argb = _mm256_shuffle_epi8(_mm256_loadu_si256((const __m256i*)(osd_row + (col << 2))), shuf);
                        __m256i am = _mm256_and_si256(argb, alpha_mask);
                        if (_mm256_movemask_epi8(_mm256_cmpeq_epi32(am, alpha_mask)) == -1) {
                            _mm256_storeu_si256((__m256i*)(drow + col), apply_color_matrix_8_avx2(argb, cmat, mmode));
                            col += 8;
                            continue;
                        }
                        __m256i bg = p0_all_in
                            ? _mm256_or_si256(alpha_mask, uyvy4_to_rgb8_avx2(p0_row + (col - p0x) * 2))
                            : v_backdrop;
                        if (p1_all_in) {
                            __m256i p1_rgb = uyvy4_to_rgb8_avx2(p1_row + (col - p1x) * 2);
                            bg = (p1_alpha >= 255)
                                ? _mm256_or_si256(alpha_mask, p1_rgb)
                                : blend8_argb_over_bg_avx2(_mm256_or_si256(v_p1_alpha_hi, p1_rgb), bg);
                        }
                        __m256i out = _mm256_testz_si256(am, am) ? bg : blend8_argb_over_bg_avx2(argb, bg);
                        __m256i res = apply_color_matrix_8_avx2(out, cmat, mmode);
                        if (__builtin_expect(need_premul, 0)) res = premul_if_needed_8_avx2(res, &all_opaque);
                        _mm256_storeu_si256((__m256i*)(drow + col), res);
                    } else {
                        __m256i bg = p0_all_in
                            ? _mm256_or_si256(alpha_mask, uyvy4_to_rgb8_avx2(p0_row + (col - p0x) * 2))
                            : v_backdrop;
                        if (p1_all_in) {
                            __m256i p1_rgb = uyvy4_to_rgb8_avx2(p1_row + (col - p1x) * 2);
                            bg = (p1_alpha >= 255)
                                ? _mm256_or_si256(alpha_mask, p1_rgb)
                                : blend8_argb_over_bg_avx2(_mm256_or_si256(v_p1_alpha_hi, p1_rgb), bg);
                        }
                        __m256i res = apply_color_matrix_8_avx2(bg, cmat, mmode);
                        if (__builtin_expect(need_premul, 0)) res = premul_if_needed_8_avx2(res, &all_opaque);
                        _mm256_storeu_si256((__m256i*)(drow + col), res);
                    }
                    col += 8;
                    continue;
                }
                /* Scalar boundary pixel (pane edge or unaligned odd pixel) */
                uint32_t osd_argb = 0u;
                uint32_t osd_a = 0u;
                if (osd_row != NULL) {
                    osd_argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                    osd_a = osd_argb >> 24;
                    if (osd_a == 255u) {
                        drow[col++] = apply_color_matrix_scalar(osd_argb, cmat, mmode);
                        continue;
                    }
                }
                uint32_t bg = backdrop_argb;
                if (in_p0_y && col >= p0x && col < p0r) {
                    int32_t lx = col - p0x;
                    bg = 0xff000000u | uyvy_pair_to_rgb(p0_row + ((lx & ~1) * 2), lx & 1);
                }
                if (in_p1_y && col >= p1x && col < p1r) {
                    int32_t lx = col - p1x;
                    uint32_t rgb = uyvy_pair_to_rgb(p1_row + ((lx & ~1) * 2), lx & 1);
                    bg = blend_pic1_rgb_over_bg_scalar(rgb, p1_alpha, bg);
                }
                uint32_t out = (osd_a == 0u) ? bg : blend_osd_over_bg(osd_argb, bg);
                uint32_t res = apply_color_matrix_scalar(out, cmat, mmode);
                if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
                drow[col++] = res;
            }
        }
        return all_opaque;
    }

    /* Scaled nearest-neighbor path */
    for (int32_t row = 0; row < out_height; ++row) {
        const int32_t srow = source_rows[row];
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
        const uint8_t* osd_row = (osd_base != NULL) ? (osd_base + (size_t)srow * (size_t)osd_stride) : NULL;
        const int in_p0_y = p0_active && (srow >= p0y && srow < p0b);
        const int in_p1_y = p1_active && (srow >= p1y && srow < p1b);
        const uint8_t* p0_row = in_p0_y ? (pic0_bytes + (size_t)(srow - p0y) * (size_t)p0s) : NULL;
        const uint8_t* p1_row = in_p1_y ? (pic1_bytes + (size_t)(srow - p1y) * (size_t)p1s) : NULL;
        if (!in_p0_y && !in_p1_y && osd_row == NULL) {
            fill_u32_avx2((uint8_t*)drow, out_width * 4, backdrop_fill);
            if (need_premul) all_opaque = 0;
            continue;
        }
        for (int32_t col = 0; col < out_width; ++col) {
            const int32_t scol = source_cols[col];
            uint32_t osd_argb = 0u;
            uint32_t osd_a = 0u;
            if (osd_row != NULL) {
                osd_argb = ror32_8(load_u32_le(osd_row + (scol << 2)));
                osd_a = osd_argb >> 24;
                if (osd_a == 255u) {
                    drow[col] = apply_color_matrix_scalar(osd_argb, cmat, mmode);
                    continue;
                }
            }
            uint32_t bg = backdrop_argb;
            if (in_p0_y && scol >= p0x && scol < p0r) {
                int32_t lx = scol - p0x;
                bg = 0xff000000u | uyvy_pair_to_rgb(p0_row + ((lx & ~1) * 2), lx & 1);
            }
            if (in_p1_y && scol >= p1x && scol < p1r) {
                int32_t lx = scol - p1x;
                uint32_t rgb = uyvy_pair_to_rgb(p1_row + ((lx & ~1) * 2), lx & 1);
                bg = blend_pic1_rgb_over_bg_scalar(rgb, p1_alpha, bg);
            }
            uint32_t out = (osd_a == 0u) ? bg : blend_osd_over_bg(osd_argb, bg);
            uint32_t res = apply_color_matrix_scalar(out, cmat, mmode);
            if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
            drow[col] = res;
        }
    }
    return all_opaque;
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static inline __m512i premul_if_needed_16_avx512(__m512i px, int32_t* all_opaque) {
    const __m512i alpha_mask = _mm512_set1_epi32((int)0xff000000u);
    __m512i am = _mm512_and_si512(px, alpha_mask);
    if (_mm512_cmpeq_epi32_mask(am, alpha_mask) == 0xffffu) {
        return px;
    }
    *all_opaque = 0;
    const __m512i zero = _mm512_setzero_si512();
    __m512i lo = _mm512_unpacklo_epi8(px, zero);
    __m512i hi = _mm512_unpackhi_epi8(px, zero);
    __m512i a_lo = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(lo, 0xff), 0xff);
    __m512i a_hi = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(hi, 0xff), 0xff);
    __m512i r_lo = div255_rounded_epu16_avx512(_mm512_mullo_epi16(lo, a_lo));
    __m512i r_hi = div255_rounded_epu16_avx512(_mm512_mullo_epi16(hi, a_hi));
    __m512i rgb = _mm512_andnot_si512(alpha_mask, _mm512_packus_epi16(r_lo, r_hi));
    return _mm512_or_si512(am, rgb);
}

FUJI_AVX512_TARGET
static inline __m512i apply_color_matrix_16_avx512(__m512i px, const int32_t* c, int32_t mode) {
    if (mode == 0) return px;
    const __m512i mask_ff = _mm512_set1_epi32(0xff);
    const __m512i zero = _mm512_setzero_si512();
    const __m512i c512 = _mm512_set1_epi32(512);
    const __m512i opaque = _mm512_set1_epi32((int)0xff000000u);
    __m512i sr = _mm512_and_si512(_mm512_srli_epi32(px, 16), mask_ff);
    __m512i sg = _mm512_and_si512(_mm512_srli_epi32(px, 8), mask_ff);
    __m512i sb = _mm512_and_si512(px, mask_ff);
    __m512i r, g, b;
    if (mode == 1) {
        r = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(sr, _mm512_set1_epi32(c[0])), c512), 10);
        g = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(sg, _mm512_set1_epi32(c[4])), c512), 10);
        b = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(sb, _mm512_set1_epi32(c[8])), c512), 10);
    } else {
        r = _mm512_srai_epi32(
            _mm512_add_epi32(
                _mm512_add_epi32(_mm512_mullo_epi32(sr, _mm512_set1_epi32(c[0])),
                                 _mm512_mullo_epi32(sg, _mm512_set1_epi32(c[1]))),
                _mm512_add_epi32(_mm512_mullo_epi32(sb, _mm512_set1_epi32(c[2])), c512)
            ), 10);
        g = _mm512_srai_epi32(
            _mm512_add_epi32(
                _mm512_add_epi32(_mm512_mullo_epi32(sr, _mm512_set1_epi32(c[3])),
                                 _mm512_mullo_epi32(sg, _mm512_set1_epi32(c[4]))),
                _mm512_add_epi32(_mm512_mullo_epi32(sb, _mm512_set1_epi32(c[5])), c512)
            ), 10);
        b = _mm512_srai_epi32(
            _mm512_add_epi32(
                _mm512_add_epi32(_mm512_mullo_epi32(sr, _mm512_set1_epi32(c[6])),
                                 _mm512_mullo_epi32(sg, _mm512_set1_epi32(c[7]))),
                _mm512_add_epi32(_mm512_mullo_epi32(sb, _mm512_set1_epi32(c[8])), c512)
            ), 10);
    }
    r = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, r));
    g = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, g));
    b = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, b));
    return _mm512_or_si512(opaque, _mm512_or_si512(_mm512_slli_epi32(r, 16), _mm512_or_si512(_mm512_slli_epi32(g, 8), b)));
}

FUJI_AVX512_TARGET
static inline __m512i blend16_argb_over_bg_avx512(__m512i argb, __m512i bg) {
    const __m512i zero = _mm512_setzero_si512();
    const __m512i c255 = _mm512_set1_epi16(255);
    const __m512i opaque_a = _mm512_set1_epi32((int)0xff000000u);
    __m512i fg_lo = _mm512_unpacklo_epi8(argb, zero);
    __m512i fg_hi = _mm512_unpackhi_epi8(argb, zero);
    __m512i bg_lo = _mm512_unpacklo_epi8(bg, zero);
    __m512i bg_hi = _mm512_unpackhi_epi8(bg, zero);
    __m512i a_lo = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(fg_lo, 0xff), 0xff);
    __m512i a_hi = _mm512_shufflehi_epi16(_mm512_shufflelo_epi16(fg_hi, 0xff), 0xff);
    __m512i inv_lo = _mm512_sub_epi16(c255, a_lo);
    __m512i inv_hi = _mm512_sub_epi16(c255, a_hi);
    __m512i res_lo = div255_rounded_epu16_avx512(_mm512_add_epi16(_mm512_mullo_epi16(fg_lo, a_lo), _mm512_mullo_epi16(bg_lo, inv_lo)));
    __m512i res_hi = div255_rounded_epu16_avx512(_mm512_add_epi16(_mm512_mullo_epi16(fg_hi, a_hi), _mm512_mullo_epi16(bg_hi, inv_hi)));
    return _mm512_or_si512(opaque_a, _mm512_packus_epi16(res_lo, res_hi));
}

FUJI_AVX512_TARGET
static inline __m512i uyvy8_to_rgb16_avx512(const uint8_t* uyvy32) {
    /* Load 8 UYVY macro-pixels (32 bytes = 16 pixels) */
    __m256i raw256 = _mm256_loadu_si256((const __m256i*)uyvy32);
    const __m256i mask_ff_256 = _mm256_set1_epi32(0xff);
    const __m256i c128_256 = _mm256_set1_epi32(128);
    __m256i u8 = _mm256_and_si256(raw256, mask_ff_256);
    __m256i v8 = _mm256_and_si256(_mm256_srli_epi32(raw256, 16), mask_ff_256);
    __m256i d8 = _mm256_sub_epi32(u8, c128_256);
    __m256i e8 = _mm256_sub_epi32(v8, c128_256);
    __m256i dr8 = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(359), e8), c128_256), 8);
    __m256i dg8 = _mm256_srai_epi32(_mm256_sub_epi32(_mm256_sub_epi32(c128_256, _mm256_mullo_epi32(_mm256_set1_epi32(88), d8)), _mm256_mullo_epi32(_mm256_set1_epi32(183), e8)), 8);
    __m256i db8 = _mm256_srai_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(454), d8), c128_256), 8);

    const __m512i dup_idx = _mm512_setr_epi32(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7);
    __m512i dr16 = _mm512_permutexvar_epi32(dup_idx, _mm512_castsi256_si512(dr8));
    __m512i dg16 = _mm512_permutexvar_epi32(dup_idx, _mm512_castsi256_si512(dg8));
    __m512i db16 = _mm512_permutexvar_epi32(dup_idx, _mm512_castsi256_si512(db8));
    __m512i mp   = _mm512_permutexvar_epi32(dup_idx, _mm512_castsi256_si512(raw256));

    const __m512i zero = _mm512_setzero_si512();
    const __m512i mask_ff = _mm512_set1_epi32(0xff);
    __mmask16 is_zero =
        _mm512_cmpeq_epi32_mask(mp, zero) |
        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x80800000u)) |
        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x00008080u));

    __m512i y_even = _mm512_and_si512(_mm512_srli_epi32(mp, 8), mask_ff);
    __m512i y_odd  = _mm512_srli_epi32(mp, 24);
    __m512i y = _mm512_mask_blend_epi32(0xAAAAu, y_even, y_odd);

    __m512i r = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dr16)));
    __m512i g = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dg16)));
    __m512i b = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, db16)));
    __m512i rgb = _mm512_or_si512(_mm512_slli_epi32(r, 16), _mm512_or_si512(_mm512_slli_epi32(g, 8), b));
    return _mm512_mask_mov_epi32(rgb, is_zero, zero);
}

FUJI_AVX512_TARGET
static int32_t composite_planes_matrix_avx512_impl(
    const uint8_t* osd_base, int32_t osd_stride,
    const uint8_t* pic0_bytes, int32_t p0x, int32_t p0y, int32_t p0r, int32_t p0b, int32_t p0s, int32_t p0_active,
    const uint8_t* pic1_bytes, int32_t p1x, int32_t p1y, int32_t p1r, int32_t p1b, int32_t p1s, int32_t p1_alpha, int32_t p1_active,
    uint32_t backdrop_argb,
    int32_t native_1to1, const int32_t* source_rows, const int32_t* source_cols,
    int32_t out_width, int32_t out_height,
    const int32_t* cmat, int32_t mmode,
    uint32_t* output_argb,
    int32_t to_argb_pre
) {
    const __m512i alpha_mask = _mm512_set1_epi32((int)0xff000000u);
    const __m512i zero = _mm512_setzero_si512();
    const __m512i v_backdrop = _mm512_set1_epi32((int)backdrop_argb);
    const __m512i v_p1_alpha_hi = _mm512_set1_epi32((int)((uint32_t)p1_alpha << 24));
    const int32_t need_premul = (to_argb_pre != 0 && mmode == 0 && (backdrop_argb & 0xff000000u) != 0xff000000u);
    const uint32_t backdrop_mat = apply_color_matrix_scalar(backdrop_argb, cmat, mmode);
    const uint32_t backdrop_fill = need_premul ? argb_to_pre_scalar(backdrop_mat) : backdrop_mat;
    int32_t all_opaque = 1;

    if (native_1to1) {
        const int32_t lim16 = out_width & ~15;
        for (int32_t row = 0; row < out_height; ++row) {
            uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
            const uint8_t* osd_row = (osd_base != NULL) ? (osd_base + (size_t)row * (size_t)osd_stride) : NULL;
            const int in_p0_y = p0_active && (row >= p0y && row < p0b);
            const int in_p1_y = p1_active && (row >= p1y && row < p1b);
            const uint8_t* p0_row = in_p0_y ? (pic0_bytes + (size_t)(row - p0y) * (size_t)p0s) : NULL;
            const uint8_t* p1_row = in_p1_y ? (pic1_bytes + (size_t)(row - p1y) * (size_t)p1s) : NULL;

            if (!in_p0_y && !in_p1_y) {
                if (osd_row == NULL) {
                    fill_u32_avx512((uint8_t*)drow, out_width * 4, backdrop_fill);
                    if (need_premul) all_opaque = 0;
                    continue;
                }
                int32_t col = 0;
                for (; col < lim16; col += 16) {
                    __m512i argb = _mm512_ror_epi32(_mm512_loadu_si512((const void*)(osd_row + (col << 2))), 8);
                    __m512i am = _mm512_and_si512(argb, alpha_mask);
                    __m512i out;
                    if (_mm512_cmpeq_epi32_mask(am, alpha_mask) == 0xffffu) {
                        out = argb;
                    } else if (_mm512_cmpeq_epi32_mask(am, zero) == 0xffffu) {
                        out = v_backdrop;
                    } else {
                        out = blend16_argb_over_bg_avx512(argb, v_backdrop);
                    }
                    __m512i res = apply_color_matrix_16_avx512(out, cmat, mmode);
                    if (__builtin_expect(need_premul, 0)) res = premul_if_needed_16_avx512(res, &all_opaque);
                    _mm512_storeu_si512((void*)(drow + col), res);
                }
                for (; col < out_width; ++col) {
                    uint32_t argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                    uint32_t res = apply_color_matrix_scalar(blend_osd_over_bg(argb, backdrop_argb), cmat, mmode);
                    if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
                    drow[col] = res;
                }
                continue;
            }

            int32_t col = 0;
            while (col < out_width) {
                const int p0_all_in = in_p0_y && col >= p0x && (col + 16) <= p0r && (((col - p0x) & 1) == 0);
                const int p0_all_out = !in_p0_y || (col + 16) <= p0x || col >= p0r;
                const int p1_all_in = in_p1_y && col >= p1x && (col + 16) <= p1r && (((col - p1x) & 1) == 0);
                const int p1_all_out = !in_p1_y || (col + 16) <= p1x || col >= p1r;
                if ((col + 16 <= out_width) && (p0_all_in || p0_all_out) && (p1_all_in || p1_all_out)) {
                    if (osd_row != NULL) {
                        __m512i argb = _mm512_ror_epi32(_mm512_loadu_si512((const void*)(osd_row + (col << 2))), 8);
                        __m512i am = _mm512_and_si512(argb, alpha_mask);
                        if (_mm512_cmpeq_epi32_mask(am, alpha_mask) == 0xffffu) {
                            _mm512_storeu_si512((void*)(drow + col), apply_color_matrix_16_avx512(argb, cmat, mmode));
                            col += 16;
                            continue;
                        }
                        __m512i bg = p0_all_in
                            ? _mm512_or_si512(alpha_mask, uyvy8_to_rgb16_avx512(p0_row + (col - p0x) * 2))
                            : v_backdrop;
                        if (p1_all_in) {
                            __m512i p1_rgb = uyvy8_to_rgb16_avx512(p1_row + (col - p1x) * 2);
                            bg = (p1_alpha >= 255)
                                ? _mm512_or_si512(alpha_mask, p1_rgb)
                                : blend16_argb_over_bg_avx512(_mm512_or_si512(v_p1_alpha_hi, p1_rgb), bg);
                        }
                        __m512i out = (_mm512_cmpeq_epi32_mask(am, zero) == 0xffffu) ? bg : blend16_argb_over_bg_avx512(argb, bg);
                        __m512i res = apply_color_matrix_16_avx512(out, cmat, mmode);
                        if (__builtin_expect(need_premul, 0)) res = premul_if_needed_16_avx512(res, &all_opaque);
                        _mm512_storeu_si512((void*)(drow + col), res);
                    } else {
                        __m512i bg = p0_all_in
                            ? _mm512_or_si512(alpha_mask, uyvy8_to_rgb16_avx512(p0_row + (col - p0x) * 2))
                            : v_backdrop;
                        if (p1_all_in) {
                            __m512i p1_rgb = uyvy8_to_rgb16_avx512(p1_row + (col - p1x) * 2);
                            bg = (p1_alpha >= 255)
                                ? _mm512_or_si512(alpha_mask, p1_rgb)
                                : blend16_argb_over_bg_avx512(_mm512_or_si512(v_p1_alpha_hi, p1_rgb), bg);
                        }
                        __m512i res = apply_color_matrix_16_avx512(bg, cmat, mmode);
                        if (__builtin_expect(need_premul, 0)) res = premul_if_needed_16_avx512(res, &all_opaque);
                        _mm512_storeu_si512((void*)(drow + col), res);
                    }
                    col += 16;
                    continue;
                }
                uint32_t osd_argb = 0u;
                uint32_t osd_a = 0u;
                if (osd_row != NULL) {
                    osd_argb = ror32_8(load_u32_le(osd_row + (col << 2)));
                    osd_a = osd_argb >> 24;
                    if (osd_a == 255u) {
                        drow[col++] = apply_color_matrix_scalar(osd_argb, cmat, mmode);
                        continue;
                    }
                }
                uint32_t bg = backdrop_argb;
                if (in_p0_y && col >= p0x && col < p0r) {
                    int32_t lx = col - p0x;
                    bg = 0xff000000u | uyvy_pair_to_rgb(p0_row + ((lx & ~1) * 2), lx & 1);
                }
                if (in_p1_y && col >= p1x && col < p1r) {
                    int32_t lx = col - p1x;
                    uint32_t rgb = uyvy_pair_to_rgb(p1_row + ((lx & ~1) * 2), lx & 1);
                    bg = blend_pic1_rgb_over_bg_scalar(rgb, p1_alpha, bg);
                }
                uint32_t out = (osd_a == 0u) ? bg : blend_osd_over_bg(osd_argb, bg);
                uint32_t res = apply_color_matrix_scalar(out, cmat, mmode);
                if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
                drow[col++] = res;
            }
        }
        return all_opaque;
    }

    /* Scaled nearest-neighbor AVX-512 path (16 pixels/iter gather + blend + fused color matrix) */
    const __m512i mask_ff = _mm512_set1_epi32(0xff);
    const __m512i c128 = _mm512_set1_epi32(128);
    const int32_t lim16 = out_width & ~15;
    int32_t stack_p0_off[2048], stack_p0_odd[2048], stack_p0_in[2048];
    int32_t stack_p1_off[2048], stack_p1_odd[2048], stack_p1_in[2048];
    int32_t *p0_off = stack_p0_off, *p0_odd = stack_p0_odd, *p0_in = stack_p0_in;
    int32_t *p1_off = stack_p1_off, *p1_odd = stack_p1_odd, *p1_in = stack_p1_in;
    if (out_width > 2048) {
        p0_off = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        p0_odd = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        p0_in  = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        p1_off = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        p1_odd = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
        p1_in  = (int32_t*)malloc((size_t)out_width * sizeof(int32_t));
    }
    if (p0_active) {
        for (int32_t c = 0; c < out_width; ++c) {
            int32_t sc = source_cols[c];
            if (sc >= p0x && sc < p0r) {
                int32_t lx = sc - p0x;
                p0_off[c] = (lx & ~1) * 2;
                p0_odd[c] = (lx & 1);
                p0_in[c] = 1;
            } else {
                p0_off[c] = 0; p0_odd[c] = 0; p0_in[c] = 0;
            }
        }
    }
    if (p1_active) {
        for (int32_t c = 0; c < out_width; ++c) {
            int32_t sc = source_cols[c];
            if (sc >= p1x && sc < p1r) {
                int32_t lx = sc - p1x;
                p1_off[c] = (lx & ~1) * 2;
                p1_odd[c] = (lx & 1);
                p1_in[c] = 1;
            } else {
                p1_off[c] = 0; p1_odd[c] = 0; p1_in[c] = 0;
            }
        }
    }

    for (int32_t row = 0; row < out_height; ++row) {
        const int32_t srow = source_rows[row];
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
        const int32_t* osd_row32 = (osd_base != NULL) ? (const int32_t*)(osd_base + (size_t)srow * (size_t)osd_stride) : NULL;
        const int in_p0_y = p0_active && (srow >= p0y && srow < p0b);
        const int in_p1_y = p1_active && (srow >= p1y && srow < p1b);
        const uint8_t* p0_row = in_p0_y ? (pic0_bytes + (size_t)(srow - p0y) * (size_t)p0s) : NULL;
        const uint8_t* p1_row = in_p1_y ? (pic1_bytes + (size_t)(srow - p1y) * (size_t)p1s) : NULL;

        if (!in_p0_y && !in_p1_y && osd_row32 == NULL) {
            fill_u32_avx512((uint8_t*)drow, out_width * 4, backdrop_fill);
            if (need_premul) all_opaque = 0;
            continue;
        }
        int32_t col = 0;
        for (; col < lim16; col += 16) {
            __m512i osd_argb = zero;
            __m512i am = zero;
            if (osd_row32 != NULL) {
                __m512i idx = _mm512_loadu_si512((const void*)(source_cols + col));
                osd_argb = _mm512_ror_epi32(_mm512_i32gather_epi32(idx, (const void*)osd_row32, 4), 8);
                am = _mm512_and_si512(osd_argb, alpha_mask);
                if (_mm512_cmpeq_epi32_mask(am, alpha_mask) == 0xffffu) {
                    _mm512_storeu_si512((void*)(drow + col), apply_color_matrix_16_avx512(osd_argb, cmat, mmode));
                    continue;
                }
            }
            __m512i bg = v_backdrop;
            if (in_p0_y) {
                __mmask16 k0 = _mm512_cmpneq_epi32_mask(_mm512_loadu_si512((const void*)(p0_in + col)), zero);
                if (k0 != 0) {
                    __m512i off0 = _mm512_loadu_si512((const void*)(p0_off + col));
                    __mmask16 odd0 = _mm512_cmpneq_epi32_mask(_mm512_loadu_si512((const void*)(p0_odd + col)), zero);
                    __m512i mp = _mm512_mask_i32gather_epi32(zero, k0, off0, (const void*)p0_row, 1);
                    __mmask16 is_z = _mm512_cmpeq_epi32_mask(mp, zero) |
                        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x80800000u)) |
                        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x00008080u));
                    __m512i u = _mm512_and_si512(mp, mask_ff);
                    __m512i v = _mm512_and_si512(_mm512_srli_epi32(mp, 16), mask_ff);
                    __m512i y = _mm512_mask_blend_epi32(odd0, _mm512_and_si512(_mm512_srli_epi32(mp, 8), mask_ff), _mm512_srli_epi32(mp, 24));
                    __m512i d = _mm512_sub_epi32(u, c128);
                    __m512i e = _mm512_sub_epi32(v, c128);
                    __m512i dr = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(359), e), c128), 8);
                    __m512i dg = _mm512_srai_epi32(_mm512_sub_epi32(_mm512_sub_epi32(c128, _mm512_mullo_epi32(_mm512_set1_epi32(88), d)), _mm512_mullo_epi32(_mm512_set1_epi32(183), e)), 8);
                    __m512i db = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(454), d), c128), 8);
                    __m512i r = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dr)));
                    __m512i g = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dg)));
                    __m512i b = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, db)));
                    __m512i rgb = _mm512_mask_mov_epi32(_mm512_or_si512(_mm512_slli_epi32(r, 16), _mm512_or_si512(_mm512_slli_epi32(g, 8), b)), is_z, zero);
                    bg = _mm512_mask_mov_epi32(bg, k0, _mm512_or_si512(alpha_mask, rgb));
                }
            }
            if (in_p1_y) {
                __mmask16 k1 = _mm512_cmpneq_epi32_mask(_mm512_loadu_si512((const void*)(p1_in + col)), zero);
                if (k1 != 0) {
                    __m512i off1 = _mm512_loadu_si512((const void*)(p1_off + col));
                    __mmask16 odd1 = _mm512_cmpneq_epi32_mask(_mm512_loadu_si512((const void*)(p1_odd + col)), zero);
                    __m512i mp = _mm512_mask_i32gather_epi32(zero, k1, off1, (const void*)p1_row, 1);
                    __mmask16 is_z = _mm512_cmpeq_epi32_mask(mp, zero) |
                        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x80800000u)) |
                        _mm512_cmpeq_epi32_mask(mp, _mm512_set1_epi32((int)0x00008080u));
                    __m512i u = _mm512_and_si512(mp, mask_ff);
                    __m512i v = _mm512_and_si512(_mm512_srli_epi32(mp, 16), mask_ff);
                    __m512i y = _mm512_mask_blend_epi32(odd1, _mm512_and_si512(_mm512_srli_epi32(mp, 8), mask_ff), _mm512_srli_epi32(mp, 24));
                    __m512i d = _mm512_sub_epi32(u, c128);
                    __m512i e = _mm512_sub_epi32(v, c128);
                    __m512i dr = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(359), e), c128), 8);
                    __m512i dg = _mm512_srai_epi32(_mm512_sub_epi32(_mm512_sub_epi32(c128, _mm512_mullo_epi32(_mm512_set1_epi32(88), d)), _mm512_mullo_epi32(_mm512_set1_epi32(183), e)), 8);
                    __m512i db = _mm512_srai_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(454), d), c128), 8);
                    __m512i r = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dr)));
                    __m512i g = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, dg)));
                    __m512i b = _mm512_max_epi32(zero, _mm512_min_epi32(mask_ff, _mm512_add_epi32(y, db)));
                    __m512i rgb = _mm512_mask_mov_epi32(_mm512_or_si512(_mm512_slli_epi32(r, 16), _mm512_or_si512(_mm512_slli_epi32(g, 8), b)), is_z, zero);
                    __m512i p1_blended = (p1_alpha >= 255)
                        ? _mm512_or_si512(alpha_mask, rgb)
                        : blend16_argb_over_bg_avx512(_mm512_or_si512(v_p1_alpha_hi, rgb), bg);
                    bg = _mm512_mask_mov_epi32(bg, k1, p1_blended);
                }
            }
            __m512i out = (osd_row32 == NULL || _mm512_cmpeq_epi32_mask(am, zero) == 0xffffu)
                ? bg
                : blend16_argb_over_bg_avx512(osd_argb, bg);
            __m512i res = apply_color_matrix_16_avx512(out, cmat, mmode);
            if (__builtin_expect(need_premul, 0)) res = premul_if_needed_16_avx512(res, &all_opaque);
            _mm512_storeu_si512((void*)(drow + col), res);
        }
        for (; col < out_width; ++col) {
            const int32_t scol = source_cols[col];
            uint32_t osd_argb = 0u;
            uint32_t osd_a = 0u;
            if (osd_row32 != NULL) {
                osd_argb = ror32_8((uint32_t)osd_row32[scol]);
                osd_a = osd_argb >> 24;
                if (osd_a == 255u) {
                    drow[col] = apply_color_matrix_scalar(osd_argb, cmat, mmode);
                    continue;
                }
            }
            uint32_t bg = backdrop_argb;
            if (in_p0_y && p0_in[col]) {
                bg = 0xff000000u | uyvy_pair_to_rgb(p0_row + p0_off[col], p0_odd[col]);
            }
            if (in_p1_y && p1_in[col]) {
                uint32_t rgb = uyvy_pair_to_rgb(p1_row + p1_off[col], p1_odd[col]);
                bg = blend_pic1_rgb_over_bg_scalar(rgb, p1_alpha, bg);
            }
            uint32_t out = (osd_a == 0u) ? bg : blend_osd_over_bg(osd_argb, bg);
            uint32_t res = apply_color_matrix_scalar(out, cmat, mmode);
            if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
            drow[col] = res;
        }
    }
    if (p0_off != stack_p0_off) {
        free(p0_off); free(p0_odd); free(p0_in);
        free(p1_off); free(p1_odd); free(p1_in);
    }
    return all_opaque;
}

FUJI_AVX512_TARGET
static void composite_planes_matrix_avx512(
    const uint8_t* osd_base, int32_t osd_stride,
    const uint8_t* pic0_bytes, int32_t p0x, int32_t p0y, int32_t p0r, int32_t p0b, int32_t p0s, int32_t p0_active,
    const uint8_t* pic1_bytes, int32_t p1x, int32_t p1y, int32_t p1r, int32_t p1b, int32_t p1s, int32_t p1_alpha, int32_t p1_active,
    uint32_t backdrop_argb,
    int32_t native_1to1, const int32_t* source_rows, const int32_t* source_cols,
    int32_t out_width, int32_t out_height,
    const int32_t* cmat, int32_t mmode,
    uint32_t* output_argb
) {
    (void)composite_planes_matrix_avx512_impl(
        osd_base, osd_stride,
        pic0_bytes, p0x, p0y, p0r, p0b, p0s, p0_active,
        pic1_bytes, p1x, p1y, p1r, p1b, p1s, p1_alpha, p1_active,
        backdrop_argb, native_1to1, source_rows, source_cols,
        out_width, out_height, cmat, mmode, output_argb, 0
    );
}
#endif
#endif

static int32_t composite_planes_matrix_dispatch(
    const uint8_t* osd_bytes,
    const uint8_t* pic0_bytes,
    const uint8_t* pic1_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    const int32_t* color_matrix_q10,
    const int32_t* cfg,
    uint32_t* output_argb,
    int32_t to_argb_pre
) {
    if (cfg == NULL || output_argb == NULL) return 1;
    const int32_t osd_base_offset     = cfg[0];
    const int32_t osd_stride_bytes    = cfg[1];
    const int32_t pic0_x              = cfg[2];
    const int32_t pic0_y              = cfg[3];
    const int32_t pic0_width          = cfg[4];
    const int32_t pic0_height         = cfg[5];
    const int32_t pic0_stride_bytes   = cfg[6];
    const int32_t pic1_x              = cfg[7];
    const int32_t pic1_y              = cfg[8];
    const int32_t pic1_width          = cfg[9];
    const int32_t pic1_height         = cfg[10];
    const int32_t pic1_stride_bytes   = cfg[11];
    const int32_t pic1_alpha          = cfg[12];
    const int32_t opaque_osd_backdrop = cfg[13];
    const uint32_t backdrop_argb      = (uint32_t)cfg[14];
    const int32_t canvas_width        = cfg[15];
    const int32_t canvas_height       = cfg[16];
    const int32_t out_width           = cfg[17];
    const int32_t out_height          = cfg[18];
    const int32_t matrix_mode         = (color_matrix_q10 != NULL) ? cfg[19] : 0;
    if (out_width <= 0 || out_height <= 0) return 1;

    const int32_t suppress_pics = (osd_bytes != NULL && opaque_osd_backdrop != 0);
    const int32_t p0_active = (!suppress_pics && pic0_bytes != NULL && pic0_width > 0 && pic0_height > 0);
    const int32_t p1_active = (!suppress_pics && pic1_bytes != NULL && pic1_alpha > 0 && pic1_width > 0 && pic1_height > 0);
    const uint8_t* osd_base = (osd_bytes != NULL) ? (osd_bytes + osd_base_offset) : NULL;
    const int32_t native_1to1 = (out_width == canvas_width && out_height == canvas_height);
    const int32_t p0_right = pic0_x + pic0_width;
    const int32_t p0_bottom = pic0_y + pic0_height;
    const int32_t p1_right = pic1_x + pic1_width;
    const int32_t p1_bottom = pic1_y + pic1_height;

#if FUJI_X86_64
    int level = get_simd_level();
#if FUJI_HAS_AVX512_TARGET
    if (level >= FUJI_RENDER_SIMD_AVX512) {
        return composite_planes_matrix_avx512_impl(
            osd_base, osd_stride_bytes,
            pic0_bytes, pic0_x, pic0_y, p0_right, p0_bottom, pic0_stride_bytes, p0_active,
            pic1_bytes, pic1_x, pic1_y, p1_right, p1_bottom, pic1_stride_bytes, pic1_alpha, p1_active,
            backdrop_argb, native_1to1, source_rows, source_cols,
            out_width, out_height, color_matrix_q10, matrix_mode, output_argb, to_argb_pre
        );
    }
#endif
    if (level >= FUJI_RENDER_SIMD_AVX2) {
        return composite_planes_matrix_avx2_impl(
            osd_base, osd_stride_bytes,
            pic0_bytes, pic0_x, pic0_y, p0_right, p0_bottom, pic0_stride_bytes, p0_active,
            pic1_bytes, pic1_x, pic1_y, p1_right, p1_bottom, pic1_stride_bytes, pic1_alpha, p1_active,
            backdrop_argb, native_1to1, source_rows, source_cols,
            out_width, out_height, color_matrix_q10, matrix_mode, output_argb, to_argb_pre
        );
    }
#endif
    /* Scalar fallback */
    const int32_t need_premul = (to_argb_pre != 0 && matrix_mode == 0 && (backdrop_argb & 0xff000000u) != 0xff000000u);
    const uint32_t backdrop_mat = apply_color_matrix_scalar(backdrop_argb, color_matrix_q10, matrix_mode);
    const uint32_t backdrop_fill = need_premul ? argb_to_pre_scalar(backdrop_mat) : backdrop_mat;
    int32_t all_opaque = 1;
    for (int32_t row = 0; row < out_height; ++row) {
        const int32_t srow = native_1to1 ? row : source_rows[row];
        uint32_t* drow = output_argb + (size_t)row * (size_t)out_width;
        const uint8_t* osd_row = (osd_base != NULL) ? (osd_base + (size_t)srow * (size_t)osd_stride_bytes) : NULL;
        const int in_p0_y = p0_active && (srow >= pic0_y && srow < p0_bottom);
        const int in_p1_y = p1_active && (srow >= pic1_y && srow < p1_bottom);
        const uint8_t* p0_row = in_p0_y ? (pic0_bytes + (size_t)(srow - pic0_y) * (size_t)pic0_stride_bytes) : NULL;
        const uint8_t* p1_row = in_p1_y ? (pic1_bytes + (size_t)(srow - pic1_y) * (size_t)pic1_stride_bytes) : NULL;
        if (!in_p0_y && !in_p1_y && osd_row == NULL) {
            fuji_render_fill_u32((uint8_t*)drow, out_width * 4, backdrop_fill);
            if (need_premul) all_opaque = 0;
            continue;
        }
        for (int32_t col = 0; col < out_width; ++col) {
            const int32_t scol = native_1to1 ? col : source_cols[col];
            uint32_t osd_argb = 0u;
            uint32_t osd_a = 0u;
            if (osd_row != NULL) {
                osd_argb = ror32_8(load_u32_le(osd_row + (scol << 2)));
                osd_a = osd_argb >> 24;
                if (osd_a == 255u) {
                    drow[col] = apply_color_matrix_scalar(osd_argb, color_matrix_q10, matrix_mode);
                    continue;
                }
            }
            uint32_t bg = backdrop_argb;
            if (in_p0_y && scol >= pic0_x && scol < p0_right) {
                int32_t lx = scol - pic0_x;
                bg = 0xff000000u | uyvy_pair_to_rgb(p0_row + ((lx & ~1) * 2), lx & 1);
            }
            if (in_p1_y && scol >= pic1_x && scol < p1_right) {
                int32_t lx = scol - pic1_x;
                uint32_t rgb = uyvy_pair_to_rgb(p1_row + ((lx & ~1) * 2), lx & 1);
                bg = blend_pic1_rgb_over_bg_scalar(rgb, pic1_alpha, bg);
            }
            uint32_t out = (osd_a == 0u) ? bg : blend_osd_over_bg(osd_argb, bg);
            uint32_t res = apply_color_matrix_scalar(out, color_matrix_q10, matrix_mode);
            if (__builtin_expect(need_premul, 0)) res = premul_if_needed_scalar(res, &all_opaque);
            drow[col] = res;
        }
    }
    return all_opaque;
}

FUJI_RENDER_EXPORT void fuji_render_composite_planes_matrix(
    const uint8_t* osd_bytes,
    const uint8_t* pic0_bytes,
    const uint8_t* pic1_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    const int32_t* color_matrix_q10,
    const int32_t* cfg,
    uint32_t* output_argb
) {
    (void)composite_planes_matrix_dispatch(
        osd_bytes, pic0_bytes, pic1_bytes,
        source_rows, source_cols, color_matrix_q10, cfg, output_argb, 0
    );
}

FUJI_RENDER_EXPORT int32_t fuji_render_composite_lcdc_to_argb_pre(
    const uint8_t* osd_bytes,
    const uint8_t* pic0_bytes,
    const uint8_t* pic1_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    const int32_t* color_matrix_q10,
    const int32_t* cfg,
    uint32_t* output_argb_pre
) {
    return composite_planes_matrix_dispatch(
        osd_bytes, pic0_bytes, pic1_bytes,
        source_rows, source_cols, color_matrix_q10, cfg, output_argb_pre, 1
    );
}

/* ========================================================================= */
/* SIMD-RENDER-V2 Kernel 10: Fused LiveView ISP Bayer -> Color -> YUV + Peak */
/* ========================================================================= */

static int32_t g_sample8_to_12[256];
static int g_sample8_inited = 0;

static void init_sample8_to_12(void) {
    if (__builtin_expect(g_sample8_inited != 0, 1)) return;
    for (int i = 0; i < 256; ++i) {
        g_sample8_to_12[i] = (i * 4095) / 255;
    }
    g_sample8_inited = 1;
}

static inline void pack_uv_pair_scalar(
    int32_t avg_r, int32_t avg_g, int32_t avg_b,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* u_out, uint8_t* v_out
);

#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void convert_bayer16_row_avx512(const uint8_t* raw_row, int32_t src_w, int32_t* dst) {
    int32_t x = 0;
    const int32_t lim16 = src_w & ~15;
    for (; x < lim16; x += 16) {
        __m256i v16 = _mm256_srli_epi16(_mm256_loadu_si256((const __m256i*)(raw_row + (x << 1))), 4);
        _mm512_storeu_si512((void*)(dst + x), _mm512_cvtepu16_epi32(v16));
    }
    for (; x < src_w; ++x) {
        uint32_t lo = raw_row[x * 2];
        uint32_t hi = raw_row[x * 2 + 1];
        dst[x] = (int32_t)((lo | (hi << 8)) >> 4);
    }
}

FUJI_AVX512_TARGET
static void pack_interior_yuv_avx512(
    const uint8_t* row_r, const uint8_t* row_g, const uint8_t* row_b, const uint8_t* row_y,
    int32_t x_start, int32_t x_end,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    int32_t is_semi_planar, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    /* Processes 32 pixels (16 chroma pairs) per iteration in AVX-512 */
    const __m512i v_zero = _mm512_setzero_si512();
    const __m512i v_255  = _mm512_set1_epi32(255);
    const __m512i v_128  = _mm512_set1_epi32(128);
    const __m512i v_u0   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[3] : -43);
    const __m512i v_u1   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[4] : -85);
    const __m512i v_u2   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[5] : 128);
    const __m512i v_v0   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[6] : 128);
    const __m512i v_v1   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[7] : -107);
    const __m512i v_v2   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[8] : -21);
    const __m512i v_half = _mm512_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const unsigned int sh = (unsigned int)(has_yuv_mat ? yuv_shift : 8);
    const __m256i v_u8_mask = _mm256_set1_epi16(0x00ff);

    int32_t x = x_start;
    for (; x + 32 <= x_end; x += 32) {
        __m512i u32, v32;
        if (mono_mode) {
            u32 = v_128;
            v32 = v_128;
        } else {
            __m256i r32 = _mm256_loadu_si256((const __m256i*)(row_r + x));
            __m256i g32 = _mm256_loadu_si256((const __m256i*)(row_g + x));
            __m256i b32 = _mm256_loadu_si256((const __m256i*)(row_b + x));
            /* Exact (c0 + c1) >> 1 for 16 adjacent byte pairs */
            __m256i ar16 = _mm256_srli_epi16(_mm256_add_epi16(_mm256_and_si256(r32, v_u8_mask), _mm256_srli_epi16(r32, 8)), 1);
            __m256i ag16 = _mm256_srli_epi16(_mm256_add_epi16(_mm256_and_si256(g32, v_u8_mask), _mm256_srli_epi16(g32, 8)), 1);
            __m256i ab16 = _mm256_srli_epi16(_mm256_add_epi16(_mm256_and_si256(b32, v_u8_mask), _mm256_srli_epi16(b32, 8)), 1);
            __m512i ar = _mm512_cvtepu16_epi32(ar16);
            __m512i ag = _mm512_cvtepu16_epi32(ag16);
            __m512i ab = _mm512_cvtepu16_epi32(ab16);
            u32 = _mm512_add_epi32(_mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(v_u0, ar), _mm512_mullo_epi32(v_u1, ag)), _mm512_add_epi32(_mm512_mullo_epi32(v_u2, ab), v_half)), sh), v_128);
            v32 = _mm512_add_epi32(_mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(v_v0, ar), _mm512_mullo_epi32(v_v1, ag)), _mm512_add_epi32(_mm512_mullo_epi32(v_v2, ab), v_half)), sh), v_128);
            u32 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, u32));
            v32 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, v32));
        }
        if (is_semi_planar) {
            /* Interleave 16 U + 16 V bytes -> 32 bytes [U0 V0 U1 V1 ...] */
            __m512i uv16 = _mm512_or_si512(u32, _mm512_slli_epi32(v32, 8));
            _mm256_storeu_si256((__m256i*)(uv_row + x), _mm512_cvtepi32_epi16(uv16));
        } else {
            /* Pack [U0 Y0 V0 Y1] for 16 macro-pixels = 64 bytes! */
            __m256i y32 = _mm256_loadu_si256((const __m256i*)(row_y + x));
            __m512i y0 = _mm512_cvtepu16_epi32(_mm256_and_si256(y32, v_u8_mask));
            __m512i y1 = _mm512_cvtepu16_epi32(_mm256_srli_epi16(y32, 8));
            __m512i uyvy = _mm512_or_si512(
                _mm512_or_si512(u32, _mm512_slli_epi32(y0, 8)),
                _mm512_or_si512(_mm512_slli_epi32(v32, 16), _mm512_slli_epi32(y1, 24))
            );
            _mm512_storeu_si512((void*)(y_or_uyvy_row + (x << 1)), uyvy);
        }
    }
    for (; x < x_end; x += 2) {
        int32_t ar = ((int32_t)row_r[x] + (int32_t)row_r[x + 1]) >> 1;
        int32_t ag = ((int32_t)row_g[x] + (int32_t)row_g[x + 1]) >> 1;
        int32_t ab = ((int32_t)row_b[x] + (int32_t)row_b[x + 1]) >> 1;
        uint8_t u8, v8;
        pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &u8, &v8);
        if (is_semi_planar) {
            uv_row[x] = u8;
            uv_row[x + 1] = v8;
        } else {
            uint8_t* p = y_or_uyvy_row + (x << 1);
            p[0] = u8; p[1] = row_y[x]; p[2] = v8; p[3] = row_y[x + 1];
        }
    }
}
#endif

static inline void convert_bayer_row_to_i32(const uint8_t* raw, int32_t sy, int32_t src_w, int32_t is_16bit, int32_t simd_level, int32_t* dst_padded) {
    int32_t* dst = dst_padded + 1;
    if (is_16bit) {
        const uint8_t* s = raw + ((size_t)sy * (size_t)src_w * 2u);
#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
        if (simd_level >= FUJI_RENDER_SIMD_AVX512) {
            convert_bayer16_row_avx512(s, src_w, dst);
        } else
#endif
        {
            (void)simd_level;
            for (int32_t x = 0; x < src_w; ++x) {
                uint32_t lo = s[x * 2];
                uint32_t hi = s[x * 2 + 1];
                dst[x] = (int32_t)((lo | (hi << 8)) >> 4);
            }
        }
    } else {
        (void)simd_level;
        const uint8_t* s = raw + (size_t)sy * (size_t)src_w;
        for (int32_t x = 0; x < src_w; ++x) {
            dst[x] = g_sample8_to_12[s[x]];
        }
    }
    /* Mirror edge pixels by 1 to preserve 2x2 RGGB Bayer CFA parity on left/right borders (BUG-V3-7-6). */
    dst[-1] = (src_w > 1) ? dst[1] : dst[0];
    dst[src_w] = (src_w > 1) ? dst[src_w - 2] : dst[src_w - 1];
}

static inline int32_t clamp_12(int32_t v) {
    if (v <= 0) return 0;
    if (v >= 4095) return 4095;
    return v;
}

static inline void isp_process_row_scalar(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    for (int32_t x = off_x; x < end_x; ++x) {
        const int32_t sx = src_x_map[x];
        const int32_t sx_odd = sx & 1;
        const int32_t c  = sc[sx];
        const int32_t l  = sc[sx - 1];
        const int32_t r  = sc[sx + 1];
        const int32_t u  = sp[sx];
        const int32_t d  = sn[sx];
        int32_t r_lin, g_lin, b_lin;
        if (!sy_odd && !sx_odd) {
            r_lin = c;
            g_lin = (l + r + u + d + 2) >> 2;
            b_lin = (sp[sx - 1] + sp[sx + 1] + sn[sx - 1] + sn[sx + 1] + 2) >> 2;
        } else if (!sy_odd && sx_odd) {
            r_lin = (l + r + 1) >> 1;
            g_lin = c;
            b_lin = (u + d + 1) >> 1;
        } else if (sy_odd && !sx_odd) {
            r_lin = (u + d + 1) >> 1;
            g_lin = c;
            b_lin = (l + r + 1) >> 1;
        } else {
            r_lin = (sp[sx - 1] + sp[sx + 1] + sn[sx - 1] + sn[sx + 1] + 2) >> 2;
            g_lin = (l + r + u + d + 2) >> 2;
            b_lin = c;
        }
        int32_t rg = unity_gain ? r_lin : clamp_12((int32_t)(((uint64_t)(int64_t)r_lin * (uint64_t)(int64_t)gain_r) >> 10));
        int32_t gg = unity_gain ? g_lin : clamp_12((int32_t)(((uint64_t)(int64_t)g_lin * (uint64_t)(int64_t)gain_g) >> 10));
        int32_t bg = unity_gain ? b_lin : clamp_12((int32_t)(((uint64_t)(int64_t)b_lin * (uint64_t)(int64_t)gain_b) >> 10));
        int32_t ri, gi, bi;
        if (has_ccm) {
            ri = clamp_12((ccm[0] * rg + ccm[1] * gg + ccm[2] * bg + 512) >> 10);
            gi = clamp_12((ccm[3] * rg + ccm[4] * gg + ccm[5] * bg + 512) >> 10);
            bi = clamp_12((ccm[6] * rg + ccm[7] * gg + ccm[8] * bg + 512) >> 10);
        } else {
            ri = rg; gi = gg; bi = bg;
        }
        int32_t r8 = lut_r[ri];
        int32_t g8 = lut_g[gi];
        int32_t b8 = lut_b[bi];
        int32_t y8 = has_yuv_mat
            ? (int32_t)clamp_u8_i32((yuv_mat[0] * r8 + yuv_mat[1] * g8 + yuv_mat[2] * b8 + yuv_half) >> yuv_shift)
            : (int32_t)clamp_u8_i32((77 * r8 + 150 * g8 + 29 * b8 + 128) >> 8);
        row_r[x] = (uint8_t)r8;
        row_g[x] = (uint8_t)g8;
        row_b[x] = (uint8_t)b8;
        row_y[x] = (uint8_t)y8;
    }
}

#if FUJI_X86_64
__attribute__((target("avx2")))
static void isp_process_row_avx2(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x, int32_t same_dims,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    const int32_t fast_gain_ok = unity_gain || (gain_r >= 0 && gain_r <= 1048575 && gain_g >= 0 && gain_g <= 1048575 && gain_b >= 0 && gain_b <= 1048575);
    if (!fast_gain_ok) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, off_x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
        return;
    }
    const __m256i v_zero = _mm256_setzero_si256();
    const __m256i v_one  = _mm256_set1_epi32(1);
    const __m256i v_two  = _mm256_set1_epi32(2);
    const __m256i v_4095 = _mm256_set1_epi32(4095);
    const __m256i v_255  = _mm256_set1_epi32(255);
    const __m256i v_512  = _mm256_set1_epi32(512);
    const __m256i v_gr   = _mm256_set1_epi32(gain_r);
    const __m256i v_gg   = _mm256_set1_epi32(gain_g);
    const __m256i v_gb   = _mm256_set1_epi32(gain_b);
    const __m256i v_ym0  = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[0] : 77);
    const __m256i v_ym1  = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[1] : 150);
    const __m256i v_ym2  = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[2] : 29);
    const __m256i v_yhalf = _mm256_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const int32_t y_sh   = has_yuv_mat ? yuv_shift : 8;
    const __m256i pack_shuf = _mm256_setr_epi8(
        0, 4, 8, 12,  -1, -1, -1, -1,  -1, -1, -1, -1,  -1, -1, -1, -1,
        0, 4, 8, 12,  -1, -1, -1, -1,  -1, -1, -1, -1,  -1, -1, -1, -1
    );

    int32_t x = off_x;
    for (; x + 8 <= end_x; x += 8) {
        __m256i c, l, r, u, d, ul, ur, dl, dr, odd_mask;
        if (same_dims) {
            c  = _mm256_loadu_si256((const __m256i*)(sc + x));
            l  = _mm256_loadu_si256((const __m256i*)(sc + x - 1));
            r  = _mm256_loadu_si256((const __m256i*)(sc + x + 1));
            u  = _mm256_loadu_si256((const __m256i*)(sp + x));
            d  = _mm256_loadu_si256((const __m256i*)(sn + x));
            ul = _mm256_loadu_si256((const __m256i*)(sp + x - 1));
            ur = _mm256_loadu_si256((const __m256i*)(sp + x + 1));
            dl = _mm256_loadu_si256((const __m256i*)(sn + x - 1));
            dr = _mm256_loadu_si256((const __m256i*)(sn + x + 1));
            odd_mask = ((x & 1) == 0)
                ? _mm256_setr_epi32(0, -1, 0, -1, 0, -1, 0, -1)
                : _mm256_setr_epi32(-1, 0, -1, 0, -1, 0, -1, 0);
        } else {
            __m256i sx = _mm256_loadu_si256((const __m256i*)(src_x_map + x));
            odd_mask = _mm256_cmpeq_epi32(_mm256_and_si256(sx, v_one), v_one);
            c  = _mm256_i32gather_epi32(sc, sx, 4);
            l  = _mm256_i32gather_epi32(sc - 1, sx, 4);
            r  = _mm256_i32gather_epi32(sc + 1, sx, 4);
            u  = _mm256_i32gather_epi32(sp, sx, 4);
            d  = _mm256_i32gather_epi32(sn, sx, 4);
            ul = _mm256_i32gather_epi32(sp - 1, sx, 4);
            ur = _mm256_i32gather_epi32(sp + 1, sx, 4);
            dl = _mm256_i32gather_epi32(sn - 1, sx, 4);
            dr = _mm256_i32gather_epi32(sn + 1, sx, 4);
        }
        __m256i h_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(l, r), v_one), 1);
        __m256i v_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(u, d), v_one), 1);
        __m256i x_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_add_epi32(l, r), _mm256_add_epi32(u, d)), v_two), 2);
        __m256i q_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_add_epi32(ul, ur), _mm256_add_epi32(dl, dr)), v_two), 2);

        __m256i r_lin, g_lin, b_lin;
        if (!sy_odd) {
            r_lin = _mm256_blendv_epi8(c, h_avg, odd_mask);
            g_lin = _mm256_blendv_epi8(x_avg, c, odd_mask);
            b_lin = _mm256_blendv_epi8(q_avg, v_avg, odd_mask);
        } else {
            r_lin = _mm256_blendv_epi8(v_avg, q_avg, odd_mask);
            g_lin = _mm256_blendv_epi8(c, x_avg, odd_mask);
            b_lin = _mm256_blendv_epi8(h_avg, c, odd_mask);
        }
        if (!unity_gain) {
            r_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(r_lin, v_gr), 10));
            g_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(g_lin, v_gg), 10));
            b_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(b_lin, v_gb), 10));
        }
        __m256i ri, gi, bi;
        if (has_ccm) {
            ri = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[0]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[1]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[2]), b_lin), v_512)), 10);
            gi = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[3]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[4]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[5]), b_lin), v_512)), 10);
            bi = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[6]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[7]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[8]), b_lin), v_512)), 10);
            ri = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, ri));
            gi = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, gi));
            bi = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, bi));
        } else {
            ri = r_lin; gi = g_lin; bi = b_lin;
        }
        __m256i r8 = _mm256_i32gather_epi32(lut_r, ri, 4);
        __m256i g8 = _mm256_i32gather_epi32(lut_g, gi, 4);
        __m256i b8 = _mm256_i32gather_epi32(lut_b, bi, 4);
        __m256i y8 = _mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(v_ym0, r8), _mm256_mullo_epi32(v_ym1, g8)), _mm256_add_epi32(_mm256_mullo_epi32(v_ym2, b8), v_yhalf));
        y8 = _mm256_srai_epi32(y8, y_sh);
        y8 = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_255, y8));

        /* Pack 8 epi32 bytes -> 8 consecutive uint8_t */
        __m256i rp = _mm256_shuffle_epi8(r8, pack_shuf);
        __m256i gp = _mm256_shuffle_epi8(g8, pack_shuf);
        __m256i bp = _mm256_shuffle_epi8(b8, pack_shuf);
        __m256i yp = _mm256_shuffle_epi8(y8, pack_shuf);
        store_u32_le(row_r + x,     (uint32_t)_mm256_extract_epi32(rp, 0));
        store_u32_le(row_r + x + 4, (uint32_t)_mm256_extract_epi32(rp, 4));
        store_u32_le(row_g + x,     (uint32_t)_mm256_extract_epi32(gp, 0));
        store_u32_le(row_g + x + 4, (uint32_t)_mm256_extract_epi32(gp, 4));
        store_u32_le(row_b + x,     (uint32_t)_mm256_extract_epi32(bp, 0));
        store_u32_le(row_b + x + 4, (uint32_t)_mm256_extract_epi32(bp, 4));
        store_u32_le(row_y + x,     (uint32_t)_mm256_extract_epi32(yp, 0));
        store_u32_le(row_y + x + 4, (uint32_t)_mm256_extract_epi32(yp, 4));
    }
    if (x < end_x) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
    }
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void isp_process_row_avx512(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x, int32_t same_dims,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    const int32_t fast_gain_ok = unity_gain || (gain_r >= 0 && gain_r <= 1048575 && gain_g >= 0 && gain_g <= 1048575 && gain_b >= 0 && gain_b <= 1048575);
    if (!fast_gain_ok) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, off_x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
        return;
    }
    const __m512i v_zero  = _mm512_setzero_si512();
    const __m512i v_one   = _mm512_set1_epi32(1);
    const __m512i v_two   = _mm512_set1_epi32(2);
    const __m512i v_4095  = _mm512_set1_epi32(4095);
    const __m512i v_255   = _mm512_set1_epi32(255);
    const __m512i v_512   = _mm512_set1_epi32(512);
    const __m512i v_gr    = _mm512_set1_epi32(gain_r);
    const __m512i v_gg    = _mm512_set1_epi32(gain_g);
    const __m512i v_gb    = _mm512_set1_epi32(gain_b);
    const __m512i v_ym0   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[0] : 77);
    const __m512i v_ym1   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[1] : 150);
    const __m512i v_ym2   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[2] : 29);
    const __m512i v_yhalf = _mm512_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const unsigned int y_sh = (unsigned int)(has_yuv_mat ? yuv_shift : 8);

    int32_t x = off_x;
    for (; x + 16 <= end_x; x += 16) {
        __m512i c, l, r, u, d, ul, ur, dl, dr;
        __mmask16 odd_k;
        if (same_dims) {
            c  = _mm512_loadu_si512((const void*)(sc + x));
            l  = _mm512_loadu_si512((const void*)(sc + x - 1));
            r  = _mm512_loadu_si512((const void*)(sc + x + 1));
            u  = _mm512_loadu_si512((const void*)(sp + x));
            d  = _mm512_loadu_si512((const void*)(sn + x));
            ul = _mm512_loadu_si512((const void*)(sp + x - 1));
            ur = _mm512_loadu_si512((const void*)(sp + x + 1));
            dl = _mm512_loadu_si512((const void*)(sn + x - 1));
            dr = _mm512_loadu_si512((const void*)(sn + x + 1));
            odd_k = ((x & 1) == 0) ? 0xAAAAu : 0x5555u;
        } else {
            __m512i sx = _mm512_loadu_si512((const void*)(src_x_map + x));
            odd_k = _mm512_test_epi32_mask(sx, v_one);
            c  = _mm512_i32gather_epi32(sx, (const void*)sc, 4);
            l  = _mm512_i32gather_epi32(sx, (const void*)(sc - 1), 4);
            r  = _mm512_i32gather_epi32(sx, (const void*)(sc + 1), 4);
            u  = _mm512_i32gather_epi32(sx, (const void*)sp, 4);
            d  = _mm512_i32gather_epi32(sx, (const void*)sn, 4);
            ul = _mm512_i32gather_epi32(sx, (const void*)(sp - 1), 4);
            ur = _mm512_i32gather_epi32(sx, (const void*)(sp + 1), 4);
            dl = _mm512_i32gather_epi32(sx, (const void*)(sn - 1), 4);
            dr = _mm512_i32gather_epi32(sx, (const void*)(sn + 1), 4);
        }
        __m512i h_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(l, r), v_one), 1);
        __m512i v_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(u, d), v_one), 1);
        __m512i x_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_add_epi32(l, r), _mm512_add_epi32(u, d)), v_two), 2);
        __m512i q_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_add_epi32(ul, ur), _mm512_add_epi32(dl, dr)), v_two), 2);

        __m512i r_lin, g_lin, b_lin;
        if (!sy_odd) {
            r_lin = _mm512_mask_blend_epi32(odd_k, c, h_avg);
            g_lin = _mm512_mask_blend_epi32(odd_k, x_avg, c);
            b_lin = _mm512_mask_blend_epi32(odd_k, q_avg, v_avg);
        } else {
            r_lin = _mm512_mask_blend_epi32(odd_k, v_avg, q_avg);
            g_lin = _mm512_mask_blend_epi32(odd_k, c, x_avg);
            b_lin = _mm512_mask_blend_epi32(odd_k, h_avg, c);
        }
        if (!unity_gain) {
            r_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(r_lin, v_gr), 10));
            g_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(g_lin, v_gg), 10));
            b_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(b_lin, v_gb), 10));
        }
        __m512i ri, gi, bi;
        if (has_ccm) {
            ri = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[0]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[1]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[2]), b_lin), v_512)), 10);
            gi = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[3]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[4]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[5]), b_lin), v_512)), 10);
            bi = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[6]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[7]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[8]), b_lin), v_512)), 10);
            ri = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, ri));
            gi = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, gi));
            bi = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, bi));
        } else {
            ri = r_lin; gi = g_lin; bi = b_lin;
        }
        __m512i r8 = _mm512_i32gather_epi32(ri, (const void*)lut_r, 4);
        __m512i g8 = _mm512_i32gather_epi32(gi, (const void*)lut_g, 4);
        __m512i b8 = _mm512_i32gather_epi32(bi, (const void*)lut_b, 4);
        __m512i y8 = _mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(v_ym0, r8), _mm512_mullo_epi32(v_ym1, g8)), _mm512_add_epi32(_mm512_mullo_epi32(v_ym2, b8), v_yhalf));
        y8 = _mm512_srai_epi32(y8, y_sh);
        y8 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, y8));

        /* AVX-512 single-instruction 16x epi32 -> 16x uint8_t truncate + store! */
        _mm_storeu_si128((__m128i*)(row_r + x), _mm512_cvtepi32_epi8(r8));
        _mm_storeu_si128((__m128i*)(row_g + x), _mm512_cvtepi32_epi8(g8));
        _mm_storeu_si128((__m128i*)(row_b + x), _mm512_cvtepi32_epi8(b8));
        _mm_storeu_si128((__m128i*)(row_y + x), _mm512_cvtepi32_epi8(y8));
    }
    if (x < end_x) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
    }
}

FUJI_AVX512_TARGET
static int32_t isp_peaking_row_avx512(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const __m512i v_thr = _mm512_set1_epi16((short)thr);
    const int32_t x_end = width - 2;
    int32_t x = 2;
    /* Process 32 pixels at a time in 16-bit precision */
    for (; x + 32 <= x_end; x += 32) {
        __m256i l1 = _mm256_loadu_si256((const __m256i*)(yc + x - 1));
        __m256i r1 = _mm256_loadu_si256((const __m256i*)(yc + x + 1));
        __m256i l2 = _mm256_loadu_si256((const __m256i*)(yc + x - 2));
        __m256i r2 = _mm256_loadu_si256((const __m256i*)(yc + x + 2));
        __m256i u1 = _mm256_loadu_si256((const __m256i*)(yp1 + x));
        __m256i d1 = _mm256_loadu_si256((const __m256i*)(yn1 + x));
        __m256i u2 = _mm256_loadu_si256((const __m256i*)(yp2 + x));
        __m256i d2 = _mm256_loadu_si256((const __m256i*)(yn2 + x));

        __m256i gx1 = _mm256_or_si256(_mm256_subs_epu8(r1, l1), _mm256_subs_epu8(l1, r1));
        __m256i gx2 = _mm256_or_si256(_mm256_subs_epu8(r2, l2), _mm256_subs_epu8(l2, r2));
        __m256i gy1 = _mm256_or_si256(_mm256_subs_epu8(d1, u1), _mm256_subs_epu8(u1, d1));
        __m256i gy2 = _mm256_or_si256(_mm256_subs_epu8(d2, u2), _mm256_subs_epu8(u2, d2));

        __m256i mx = _mm256_max_epu8(gx1, gx2);
        __m256i my = _mm256_max_epu8(gy1, gy2);
        __m512i sum16 = _mm512_add_epi16(_mm512_cvtepu8_epi16(mx), _mm512_cvtepu8_epi16(my));
        uint32_t mask = (uint32_t)_mm512_cmpgt_epi16_mask(sum16, v_thr);
        if (mask == 0u) continue;
        while (mask != 0u) {
            int bit = __builtin_ctz(mask);
            mask &= (mask - 1u);
            int32_t px = x + bit;
            int32_t pair_x = px & ~1;
            if (is_semi_planar) {
                y_or_uyvy_row[px] = hi_y;
                if (uv_row != NULL) {
                    uv_row[pair_x] = hi_cb;
                    uv_row[pair_x + 1] = hi_cr;
                }
            } else {
                uint8_t* p = y_or_uyvy_row + (pair_x << 1);
                p[0] = hi_cb;
                p[2] = hi_cr;
                p[(px & 1) ? 3 : 1] = hi_y;
            }
            stamped++;
        }
    }
    for (; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            int32_t pair_x = x & ~1;
            if (is_semi_planar) {
                y_or_uyvy_row[x] = hi_y;
                if (uv_row != NULL) {
                    uv_row[pair_x] = hi_cb;
                    uv_row[pair_x + 1] = hi_cr;
                }
            } else {
                uint8_t* p = y_or_uyvy_row + (pair_x << 1);
                p[0] = hi_cb;
                p[2] = hi_cr;
                p[(x & 1) ? 3 : 1] = hi_y;
            }
            stamped++;
        }
    }
    return stamped;
}
#endif

__attribute__((target("avx2")))
static int32_t isp_peaking_row_avx2(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const __m256i v_thr = _mm256_set1_epi16((short)thr);
    const int32_t x_end = width - 2;
    int32_t x = 2;
    for (; x + 16 <= x_end; x += 16) {
        __m128i l1 = _mm_loadu_si128((const __m128i*)(yc + x - 1));
        __m128i r1 = _mm_loadu_si128((const __m128i*)(yc + x + 1));
        __m128i l2 = _mm_loadu_si128((const __m128i*)(yc + x - 2));
        __m128i r2 = _mm_loadu_si128((const __m128i*)(yc + x + 2));
        __m128i u1 = _mm_loadu_si128((const __m128i*)(yp1 + x));
        __m128i d1 = _mm_loadu_si128((const __m128i*)(yn1 + x));
        __m128i u2 = _mm_loadu_si128((const __m128i*)(yp2 + x));
        __m128i d2 = _mm_loadu_si128((const __m128i*)(yn2 + x));

        __m128i gx1 = _mm_or_si128(_mm_subs_epu8(r1, l1), _mm_subs_epu8(l1, r1));
        __m128i gx2 = _mm_or_si128(_mm_subs_epu8(r2, l2), _mm_subs_epu8(l2, r2));
        __m128i gy1 = _mm_or_si128(_mm_subs_epu8(d1, u1), _mm_subs_epu8(u1, d1));
        __m128i gy2 = _mm_or_si128(_mm_subs_epu8(d2, u2), _mm_subs_epu8(u2, d2));

        __m128i mx = _mm_max_epu8(gx1, gx2);
        __m128i my = _mm_max_epu8(gy1, gy2);
        __m256i sum16 = _mm256_add_epi16(_mm256_cvtepu8_epi16(mx), _mm256_cvtepu8_epi16(my));
        uint32_t mask = (uint32_t)_mm256_movemask_epi8(_mm256_cmpgt_epi16(sum16, v_thr));
        if (mask == 0u) continue;
        mask &= 0x55555555u;
        while (mask != 0u) {
            int bit = __builtin_ctz(mask) >> 1;
            mask &= (mask - 1u);
            int32_t px = x + bit;
            int32_t pair_x = px & ~1;
            if (is_semi_planar) {
                y_or_uyvy_row[px] = hi_y;
                if (uv_row != NULL) {
                    uv_row[pair_x] = hi_cb;
                    uv_row[pair_x + 1] = hi_cr;
                }
            } else {
                uint8_t* p = y_or_uyvy_row + (pair_x << 1);
                p[0] = hi_cb;
                p[2] = hi_cr;
                p[(px & 1) ? 3 : 1] = hi_y;
            }
            stamped++;
        }
    }
    for (; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            int32_t pair_x = x & ~1;
            if (is_semi_planar) {
                y_or_uyvy_row[x] = hi_y;
                if (uv_row != NULL) {
                    uv_row[pair_x] = hi_cb;
                    uv_row[pair_x + 1] = hi_cr;
                }
            } else {
                uint8_t* p = y_or_uyvy_row + (pair_x << 1);
                p[0] = hi_cb;
                p[2] = hi_cr;
                p[(x & 1) ? 3 : 1] = hi_y;
            }
            stamped++;
        }
    }
    return stamped;
}
#endif

static int32_t isp_peaking_row_scalar(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const int32_t x_end = width - 2;
    for (int32_t x = 2; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            int32_t pair_x = x & ~1;
            if (is_semi_planar) {
                y_or_uyvy_row[x] = hi_y;
                if (uv_row != NULL) {
                    uv_row[pair_x] = hi_cb;
                    uv_row[pair_x + 1] = hi_cr;
                }
            } else {
                uint8_t* p = y_or_uyvy_row + (pair_x << 1);
                p[0] = hi_cb;
                p[2] = hi_cr;
                p[(x & 1) ? 3 : 1] = hi_y;
            }
            stamped++;
        }
    }
    return stamped;
}

static inline void pack_uv_pair_scalar(
    int32_t avg_r, int32_t avg_g, int32_t avg_b,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* u_out, uint8_t* v_out
) {
    if (mono_mode) {
        *u_out = 128u;
        *v_out = 128u;
    } else if (has_yuv_mat) {
        *u_out = (uint8_t)clamp_u8_i32(((yuv_mat[3] * avg_r + yuv_mat[4] * avg_g + yuv_mat[5] * avg_b + yuv_half) >> yuv_shift) + 128);
        *v_out = (uint8_t)clamp_u8_i32(((yuv_mat[6] * avg_r + yuv_mat[7] * avg_g + yuv_mat[8] * avg_b + yuv_half) >> yuv_shift) + 128);
    } else {
        *u_out = (uint8_t)clamp_u8_i32(((-43 * avg_r - 85 * avg_g + 128 * avg_b + 128) >> 8) + 128);
        *v_out = (uint8_t)clamp_u8_i32(((128 * avg_r - 107 * avg_g - 21 * avg_b + 128) >> 8) + 128);
    }
}

FUJI_RENDER_EXPORT int32_t fuji_render_isp_bayer_to_yuv_peaking(
    const uint8_t* raw,
    uint8_t* y_or_uyvy_dst,
    uint8_t* uv_dst,
    const int32_t* src_x_map,
    const int32_t* ccm,
    const int32_t* lut_r,
    const int32_t* lut_g,
    const int32_t* lut_b,
    const int32_t* yuv_mat,
    int32_t* hist_yrgb,
    const int32_t* cfg
) {
    if (raw == NULL || y_or_uyvy_dst == NULL || src_x_map == NULL || lut_r == NULL || lut_g == NULL || lut_b == NULL || cfg == NULL) {
        return 0;
    }
    const int32_t src_w           = cfg[0];
    const int32_t src_h           = cfg[1];
    const int32_t is_16bit        = cfg[2];
    const int32_t dst_w           = cfg[3];
    const int32_t dst_h           = cfg[4];
    const int32_t stride          = cfg[5];
    const int32_t is_semi_planar  = cfg[6];
    const int32_t is_nv12         = cfg[7];
    const int32_t off_x           = cfg[8];
    const int32_t off_y           = cfg[9];
    const int32_t fit_w           = cfg[10];
    const int32_t fit_h           = cfg[11];
    const int32_t gain_r          = cfg[12];
    const int32_t gain_g          = cfg[13];
    const int32_t gain_b          = cfg[14];
    const int32_t flags           = cfg[15];
    const int32_t yuv_shift       = cfg[16];
    const int32_t peaking_thr     = cfg[17];
    const uint32_t peaking_packed = (uint32_t)cfg[18];

    if (src_w < 2 || src_h < 2 || dst_w < 2 || (dst_w & 1) != 0 || dst_h < 1) return 0;
    if (is_semi_planar && uv_dst == NULL) return 0;

    if (!is_16bit) init_sample8_to_12();
    if (hist_yrgb != NULL) {
        memset(hist_yrgb, 0, 1024u * sizeof(int32_t));
    }

    const int32_t unity_gain      = (gain_r == 1024 && gain_g == 1024 && gain_b == 1024);
    const int32_t has_ccm         = ((flags & 1) != 0) && (ccm != NULL);
    const int32_t has_yuv_mat     = ((flags & 2) != 0) && (yuv_mat != NULL);
    const int32_t mono_mode       = ((flags & 4) != 0);
    const int32_t peaking_req     = ((flags & 8) != 0);
    const int32_t peaking_active  = peaking_req && (is_semi_planar ? (dst_w >= 6 && dst_h >= 5) : (dst_w >= 4 && dst_h >= 3));
    const uint8_t hi_y            = (uint8_t)(peaking_packed & 0xffu);
    const uint8_t hi_cb           = (uint8_t)((peaking_packed >> 8) & 0xffu);
    const uint8_t hi_cr           = (uint8_t)((peaking_packed >> 16) & 0xffu);
    const int32_t yuv_half        = 1 << (yuv_shift - 1);
    const int32_t same_dims       = (src_w == dst_w && src_h == dst_h && off_x == 0 && off_y == 0);
    const int32_t end_x           = off_x + fit_w;
    const int32_t end_y           = off_y + fit_h;
    const int32_t max_y           = src_h - 1;
    const int32_t uv_height       = is_nv12 ? (dst_h / 2) : dst_h;
    const int32_t uv_max_row      = uv_height - 1;

    /* L1-hot row buffers (stack for <= 2048px, heap fallback for larger) */
    int32_t stack_src_rows[3 * (2048 + 2)];
    uint8_t stack_rgb[3 * 2048];
    uint8_t stack_y_ring[5 * 2048];

    int32_t* src_rows_buf = stack_src_rows;
    uint8_t* rgb_buf = stack_rgb;
    uint8_t* y_ring_buf = stack_y_ring;
    const size_t src_row_stride = (size_t)src_w + 2u;
    if (src_w > 2048) src_rows_buf = (int32_t*)malloc(3u * src_row_stride * sizeof(int32_t));
    if (dst_w > 2048) {
        rgb_buf = (uint8_t*)malloc(3u * (size_t)dst_w);
        y_ring_buf = (uint8_t*)malloc(5u * (size_t)dst_w);
    }
    int32_t* sp_buf = src_rows_buf;
    int32_t* sc_buf = src_rows_buf + src_row_stride;
    int32_t* sn_buf = src_rows_buf + 2u * src_row_stride;
    uint8_t* row_r = rgb_buf;
    uint8_t* row_g = rgb_buf + dst_w;
    uint8_t* row_b = rgb_buf + 2 * dst_w;
    int32_t cached_sy_p = -1, cached_sy_c = -1, cached_sy_n = -1;

#if FUJI_X86_64
    const int simd_level = get_simd_level();
#endif
    int32_t stamped_pixels = 0;

    for (int32_t y = 0; y < dst_h; ++y) {
        uint8_t* row_y = y_ring_buf + (size_t)(y % 5) * (size_t)dst_w;
        const size_t y_row_off = (size_t)y * (size_t)stride;
        const int write_uv_row = !is_nv12 || ((y & 1) == 0);
        const int32_t uv_row_idx = is_nv12 ? (y >> 1) : y;
        const size_t uv_row_off = (size_t)uv_row_idx * (size_t)stride;

        if (y < off_y || y >= end_y) {
            if (peaking_active) memset(row_y, 0, (size_t)dst_w);
            if (is_semi_planar) {
                memset(y_or_uyvy_dst + y_row_off, 0, (size_t)dst_w);
                if (write_uv_row) {
                    memset(uv_dst + uv_row_off, 0x80, (size_t)dst_w);
                }
            } else {
                fuji_render_fill_u32(y_or_uyvy_dst + y_row_off, dst_w * 2, 0x00800080u);
            }
        } else {
            const int32_t sy = same_dims ? y : (int32_t)(((int64_t)(y - off_y) * (int64_t)src_h) / (int64_t)fit_h);
            const int32_t sy_clamped = (sy < 0) ? 0 : (sy > max_y ? max_y : sy);
            /* Mirror edge rows by 1 to preserve 2x2 RGGB Bayer CFA parity on top/bottom borders (BUG-V3-7-6). */
            const int32_t y_prev = (sy_clamped > 0) ? (sy_clamped - 1) : ((max_y > 0) ? 1 : 0);
            const int32_t y_next = (sy_clamped < max_y) ? (sy_clamped + 1) : ((max_y > 0) ? (max_y - 1) : 0);
            if (y_prev != cached_sy_p) { convert_bayer_row_to_i32(raw, y_prev, src_w, is_16bit, simd_level, sp_buf); cached_sy_p = y_prev; }
            if (sy_clamped != cached_sy_c) { convert_bayer_row_to_i32(raw, sy_clamped, src_w, is_16bit, simd_level, sc_buf); cached_sy_c = sy_clamped; }
            if (y_next != cached_sy_n) { convert_bayer_row_to_i32(raw, y_next, src_w, is_16bit, simd_level, sn_buf); cached_sy_n = y_next; }

            if (off_x > 0) memset(row_y, 0, (size_t)off_x);
            if (end_x < dst_w) memset(row_y + end_x, 0, (size_t)(dst_w - end_x));

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
            if (simd_level >= FUJI_RENDER_SIMD_AVX512) {
                isp_process_row_avx512(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    src_x_map, off_x, end_x, same_dims,
                    gain_r, gain_g, gain_b, unity_gain,
                    has_ccm, ccm, lut_r, lut_g, lut_b,
                    has_yuv_mat, yuv_mat, yuv_shift, yuv_half,
                    row_r, row_g, row_b, row_y
                );
            } else
#endif
            if (simd_level >= FUJI_RENDER_SIMD_AVX2) {
                isp_process_row_avx2(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    src_x_map, off_x, end_x, same_dims,
                    gain_r, gain_g, gain_b, unity_gain,
                    has_ccm, ccm, lut_r, lut_g, lut_b,
                    has_yuv_mat, yuv_mat, yuv_shift, yuv_half,
                    row_r, row_g, row_b, row_y
                );
            } else
#endif
            {
                isp_process_row_scalar(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    src_x_map, off_x, end_x,
                    gain_r, gain_g, gain_b, unity_gain,
                    has_ccm, ccm, lut_r, lut_g, lut_b,
                    has_yuv_mat, yuv_mat, yuv_shift, yuv_half,
                    row_r, row_g, row_b, row_y
                );
            }

            if (hist_yrgb != NULL) {
                int32_t* hy = hist_yrgb;
                int32_t* hr = hist_yrgb + 256;
                int32_t* hg = hist_yrgb + 512;
                int32_t* hb = hist_yrgb + 768;
                for (int32_t x = off_x; x < end_x; ++x) {
                    hy[row_y[x]]++;
                    hr[row_r[x]]++;
                    hg[row_g[x]]++;
                    hb[row_b[x]]++;
                }
            }

            if (is_semi_planar) {
                memcpy(y_or_uyvy_dst + y_row_off, row_y, (size_t)dst_w);
                if (write_uv_row) {
                    uint8_t* uv_row = uv_dst + uv_row_off;
                    const int32_t even_start = (off_x + 1) & ~1;
                    const int32_t even_end = end_x & ~1;
                    for (int32_t x = 0; x < even_start && x < dst_w; x += 2) {
                        const int in0 = (x >= off_x && x < end_x);
                        const int in1 = (x + 1 >= off_x && x + 1 < end_x);
                        if (!in0 && !in1) { uv_row[x] = 0x80u; uv_row[x + 1] = 0x80u; }
                        else {
                            int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                            int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                            int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                            pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &uv_row[x], &uv_row[x + 1]);
                        }
                    }
                    if (even_start < even_end) {
#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
                        if (simd_level >= FUJI_RENDER_SIMD_AVX512) {
                            pack_interior_yuv_avx512(row_r, row_g, row_b, row_y, even_start, even_end, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, 1, NULL, uv_row);
                        } else
#endif
                        {
                            for (int32_t x = even_start; x < even_end; x += 2) {
                                int32_t ar = ((int32_t)row_r[x] + (int32_t)row_r[x + 1]) >> 1;
                                int32_t ag = ((int32_t)row_g[x] + (int32_t)row_g[x + 1]) >> 1;
                                int32_t ab = ((int32_t)row_b[x] + (int32_t)row_b[x + 1]) >> 1;
                                pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &uv_row[x], &uv_row[x + 1]);
                            }
                        }
                    }
                    for (int32_t x = (even_end > even_start ? even_end : even_start); x < dst_w; x += 2) {
                        const int in0 = (x >= off_x && x < end_x);
                        const int in1 = (x + 1 >= off_x && x + 1 < end_x);
                        if (!in0 && !in1) { uv_row[x] = 0x80u; uv_row[x + 1] = 0x80u; }
                        else {
                            int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                            int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                            int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                            pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &uv_row[x], &uv_row[x + 1]);
                        }
                    }
                }
            } else {
                uint8_t* dst_row = y_or_uyvy_dst + y_row_off;
                const int32_t even_start = (off_x + 1) & ~1;
                const int32_t even_end = end_x & ~1;
                for (int32_t x = 0; x < even_start && x < dst_w; x += 2) {
                    const int in0 = (x >= off_x && x < end_x);
                    const int in1 = (x + 1 >= off_x && x + 1 < end_x);
                    uint8_t* p = dst_row + (x << 1);
                    if (!in0 && !in1) { p[0] = 0x80u; p[1] = 0u; p[2] = 0x80u; p[3] = 0u; }
                    else {
                        int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                        int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                        int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                        uint8_t u8, v8;
                        pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &u8, &v8);
                        p[0] = u8; p[1] = in0 ? row_y[x] : 0u; p[2] = v8; p[3] = in1 ? row_y[x + 1] : 0u;
                    }
                }
                if (even_start < even_end) {
#if FUJI_X86_64 && FUJI_HAS_AVX512_TARGET
                    if (simd_level >= FUJI_RENDER_SIMD_AVX512) {
                        pack_interior_yuv_avx512(row_r, row_g, row_b, row_y, even_start, even_end, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, 0, dst_row, NULL);
                    } else
#endif
                    {
                        for (int32_t x = even_start; x < even_end; x += 2) {
                            uint8_t* p = dst_row + (x << 1);
                            int32_t ar = ((int32_t)row_r[x] + (int32_t)row_r[x + 1]) >> 1;
                            int32_t ag = ((int32_t)row_g[x] + (int32_t)row_g[x + 1]) >> 1;
                            int32_t ab = ((int32_t)row_b[x] + (int32_t)row_b[x + 1]) >> 1;
                            uint8_t u8, v8;
                            pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &u8, &v8);
                            p[0] = u8; p[1] = row_y[x]; p[2] = v8; p[3] = row_y[x + 1];
                        }
                    }
                }
                for (int32_t x = (even_end > even_start ? even_end : even_start); x < dst_w; x += 2) {
                    const int in0 = (x >= off_x && x < end_x);
                    const int in1 = (x + 1 >= off_x && x + 1 < end_x);
                    uint8_t* p = dst_row + (x << 1);
                    if (!in0 && !in1) { p[0] = 0x80u; p[1] = 0u; p[2] = 0x80u; p[3] = 0u; }
                    else {
                        int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                        int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                        int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                        uint8_t u8, v8;
                        pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &u8, &v8);
                        p[0] = u8; p[1] = in0 ? row_y[x] : 0u; p[2] = v8; p[3] = in1 ? row_y[x + 1] : 0u;
                    }
                }
            }
        }

        /* Stride padding zero-fill matching Kotlin */
        if (is_semi_planar) {
            if (y < dst_h - 1 && stride > dst_w) {
                memset(y_or_uyvy_dst + y_row_off + dst_w, 0, (size_t)(stride - dst_w));
            }
            if (write_uv_row && uv_row_idx < uv_max_row && stride > dst_w) {
                memset(uv_dst + uv_row_off + dst_w, 0, (size_t)(stride - dst_w));
            }
        } else {
            if (y < dst_h - 1 && stride > dst_w * 2) {
                memset(y_or_uyvy_dst + y_row_off + dst_w * 2, 0, (size_t)(stride - dst_w * 2));
            }
        }

        /* Fused L1-hot 5-tap HPF Focus Peaking on row py = y - 2 (once rows py-2..py+2 are ready) */
        if (peaking_active && y >= 4) {
            const int32_t py = y - 2;
            const uint8_t* yp2 = y_ring_buf + (size_t)((py - 2) % 5) * (size_t)dst_w;
            const uint8_t* yp1 = y_ring_buf + (size_t)((py - 1) % 5) * (size_t)dst_w;
            const uint8_t* yc  = y_ring_buf + (size_t)(py % 5) * (size_t)dst_w;
            const uint8_t* yn1 = y_ring_buf + (size_t)((py + 1) % 5) * (size_t)dst_w;
            const uint8_t* yn2 = y_ring_buf + (size_t)((py + 2) % 5) * (size_t)dst_w;
            uint8_t* dst_py = y_or_uyvy_dst + (size_t)py * (size_t)stride;
            uint8_t* uv_py = NULL;
            if (is_semi_planar) {
                const int32_t p_uv_row = is_nv12 ? (py >> 1) : py;
                if (p_uv_row < uv_height) uv_py = uv_dst + (size_t)p_uv_row * (size_t)stride;
            }
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
            if (simd_level >= FUJI_RENDER_SIMD_AVX512) {
                stamped_pixels += isp_peaking_row_avx512(yp2, yp1, yc, yn1, yn2, dst_w, peaking_thr, hi_y, hi_cb, hi_cr, is_semi_planar, dst_py, uv_py);
            } else
#endif
            if (simd_level >= FUJI_RENDER_SIMD_AVX2) {
                stamped_pixels += isp_peaking_row_avx2(yp2, yp1, yc, yn1, yn2, dst_w, peaking_thr, hi_y, hi_cb, hi_cr, is_semi_planar, dst_py, uv_py);
            } else
#endif
            {
                stamped_pixels += isp_peaking_row_scalar(yp2, yp1, yc, yn1, yn2, dst_w, peaking_thr, hi_y, hi_cb, hi_cr, is_semi_planar, dst_py, uv_py);
            }
        }
    }

    if (src_rows_buf != stack_src_rows) free(src_rows_buf);
    if (rgb_buf != stack_rgb) free(rgb_buf);
    if (y_ring_buf != stack_y_ring) free(y_ring_buf);
    return stamped_pixels;
}
