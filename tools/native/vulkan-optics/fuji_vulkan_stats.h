#ifndef FUJI_VULKAN_STATS_H
#define FUJI_VULKAN_STATS_H

/*
 * FUJIFILM GFX100S Emulator - Vulkan 1.1 Compute ISP Statistics Parallel Reduction
 * Zero-bloat C ABI exported alongside libfuji_vulkan_optics.so / fuji_vulkan_optics.dll.
 *
 * Dynamically loads libvulkan.so.1 (Linux) or vulkan-1.dll (Windows) at runtime with
 * zero static Vulkan linkage and zero extra runtime DLL dependencies.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(FUJI_VK_OPTICS_BUILD_DLL)
#    define FUJI_VK_STATS_EXPORT __declspec(dllexport)
#  else
#    define FUJI_VK_STATS_EXPORT __declspec(dllimport)
#  endif
#else
#  define FUJI_VK_STATS_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_VK_STATS_ABI_VERSION          1u

/* Input pixel buffer formats */
#define FUJI_VK_STATS_FMT_BAYER_RGGB_16LE  0u
#define FUJI_VK_STATS_FMT_BAYER_RGGB_8     1u
#define FUJI_VK_STATS_FMT_PACKED_RGBA8     2u
#define FUJI_VK_STATS_FMT_BAYER_RGGB_U32_12 3u /* GPU-resident 12-bit Bayer in uint32_t per pixel */

/* Feature enable flags */
#define FUJI_VK_STATS_FLAG_CDAF            (1u << 0)
#define FUJI_VK_STATS_FLAG_HIST            (1u << 1)
#define FUJI_VK_STATS_FLAG_AE_GRID         (1u << 2)
#define FUJI_VK_STATS_FLAG_ALL             (FUJI_VK_STATS_FLAG_CDAF | FUJI_VK_STATS_FLAG_HIST | FUJI_VK_STATS_FLAG_AE_GRID)

/* Output buffer layout constants (2320 x uint32 = 9,280 bytes) */
#define FUJI_VK_STATS_GRID_CELLS           256u
#define FUJI_VK_STATS_HIST_BINS            256u
#define FUJI_VK_STATS_SUMMARY_WORDS        16u
#define FUJI_VK_STATS_OUTPUT_WORDS         2320u
#define FUJI_VK_STATS_OUTPUT_BYTES         (FUJI_VK_STATS_OUTPUT_WORDS * 4u)

typedef struct FujiVkStatsContext FujiVkStatsContext;

/*
 * Uniform parameters for isp_stats_reduce.comp (96 bytes, std140 aligned).
 */
typedef struct FujiVkIspStatsParams {
    uint32_t width;
    uint32_t height;
    uint32_t inputFormat;          /* FUJI_VK_STATS_FMT_* */
    uint32_t flags;                /* FUJI_VK_STATS_FLAG_* */

    uint32_t viewX;
    uint32_t viewY;
    uint32_t viewW;                /* 0 = width - viewX */
    uint32_t viewH;                /* 0 = height - viewY */

    uint32_t gridCols;             /* 1..16 (0 -> 16) */
    uint32_t gridRows;             /* 1..16 (0 -> 16) */
    uint32_t stepQuad;             /* 1 or 2 (0 -> auto: 2 if viewW>=512 && viewH>=256 else 1) */
    uint32_t awbHighlightThresh10; /* 10-bit exclusion threshold (0 -> 960) */

    uint32_t afRoiX0;
    uint32_t afRoiY0;
    uint32_t afRoiX1;              /* 0 = width */
    uint32_t afRoiY1;              /* 0 = height */

    uint32_t afCols;               /* 1..13 */
    uint32_t afRows;               /* 1..9 */
    uint32_t afStepQuad;           /* 1 or 2 */
    uint32_t ob14;                 /* 14-bit optical black offset (default 1024) */

    uint32_t reserved0;
    uint32_t reserved1;
    uint32_t reserved2;
    uint32_t reserved3;
} FujiVkIspStatsParams;

/*
 * Compact GPU->CPU reduction readback struct (9,280 bytes = 2320 x uint32_t).
 */
typedef struct FujiVkIspStatsOutput {
    uint32_t aeSumR[256];          /* [0..255]     10-bit R sum per 16x16 zone */
    uint32_t aeSumG1[256];         /* [256..511]   10-bit G1 sum per 16x16 zone */
    uint32_t aeSumB[256];          /* [512..767]   10-bit B sum per 16x16 zone */
    uint32_t aeSumG2[256];         /* [768..1023]  10-bit G2 sum per 16x16 zone */
    uint32_t aeCount[256];         /* [1024..1279] sampled quad count per 16x16 zone */

    uint32_t histY[256];           /* [1280..1535] 256-bin BT.601 luma histogram */
    uint32_t histR[256];           /* [1536..1791] 256-bin R histogram */
    uint32_t histG[256];           /* [1792..2047] 256-bin G histogram */
    uint32_t histB[256];           /* [2048..2303] 256-bin B histogram */

    uint32_t awbGlobalSumR;        /* [2304] global 10-bit R sum */
    uint32_t awbGlobalSumG;        /* [2305] global 10-bit G sum */
    uint32_t awbGlobalSumB;        /* [2306] global 10-bit B sum */
    uint32_t awbGlobalCount;       /* [2307] global sampled quad count */

    uint32_t awbValidSumR;         /* [2308] highlight-excluded 10-bit R sum */
    uint32_t awbValidSumG;         /* [2309] highlight-excluded 10-bit G sum */
    uint32_t awbValidSumB;         /* [2310] highlight-excluded 10-bit B sum */
    uint32_t awbValidCount;        /* [2311] highlight-excluded quad count */

    uint32_t cdafHorizTenengrad;   /* [2312] AF ROI horizontal 1st-deriv squared energy */
    uint32_t cdafHorizLaplacian;   /* [2313] AF ROI horizontal 2nd-deriv squared energy */
    uint32_t cdafVertTenengrad;    /* [2314] AF ROI vertical 1st-deriv squared energy */
    uint32_t cdafVertLaplacian;    /* [2315] AF ROI vertical 2nd-deriv squared energy */

    uint32_t cdafRoiMaxG14;        /* [2316] AF ROI max 14-bit G signal */
    uint32_t cdafRoiSumG10;        /* [2317] AF ROI 10-bit G sum */
    uint32_t cdafRoiCount;         /* [2318] AF ROI sampled quad count */
    uint32_t totalHistPixels;      /* [2319] total histogram samples */
} FujiVkIspStatsOutput;

FUJI_VK_STATS_EXPORT uint32_t fuji_vk_stats_abi_version(void);

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_get_spirv_words(
    const uint32_t** out_words,
    uint32_t* out_word_count);

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_create(
    uint32_t select_flags,
    FujiVkStatsContext** out_ctx,
    char* err_buf,
    size_t err_buf_len);

FUJI_VK_STATS_EXPORT void fuji_vk_stats_destroy(FujiVkStatsContext* ctx);

FUJI_VK_STATS_EXPORT const char* fuji_vk_stats_last_error(const FujiVkStatsContext* ctx);

FUJI_VK_STATS_EXPORT const char* fuji_vk_stats_device_name(const FujiVkStatsContext* ctx);

/*
 * Executes single-dispatch Vulkan compute ISP statistics reduction on raw Bayer RGGB
 * (16-bit LE or 8-bit) or packed RGBA8 input, returning the compact 9,280-byte output struct.
 */
FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_reduce(
    FujiVkStatsContext* ctx,
    const uint8_t* input_bytes,
    size_t input_byte_count,
    const FujiVkIspStatsParams* params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms);

/*
 * Deterministic scalar CPU reference matching isp_stats_reduce.comp bit-for-bit.
 */
FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_reduce_cpu_ref(
    const uint8_t* input_bytes,
    size_t input_byte_count,
    const FujiVkIspStatsParams* params,
    FujiVkIspStatsOutput* out_stats);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_VULKAN_STATS_H */
