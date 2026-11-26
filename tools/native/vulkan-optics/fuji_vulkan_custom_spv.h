#ifndef FUJI_VULKAN_CUSTOM_SPV_H
#define FUJI_VULKAN_CUSTOM_SPV_H

#include "fuji_vulkan_optics.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Plugin SPI v1.0 Custom SPIR-V Compute Shader Execution Constants */
#define FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER 0u
#define FUJI_VK_CUSTOM_SPV_STAGE_POST_OPTICS_RGB   1u

#define FUJI_VK_CUSTOM_SPV_MIN_BYTES               20u
#define FUJI_VK_CUSTOM_SPV_MAX_BYTES               (256u * 1024u)
#define FUJI_VK_CUSTOM_SPV_STD_UBO_HEADER_BYTES    32u
#define FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES       4096u
#define FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES          128u
#define FUJI_VK_CUSTOM_SPV_MAX_STAGES              8u
#define FUJI_VK_CUSTOM_SPV_LRU_CAPACITY            32u

/* Dedicated error codes for custom plugin SPIR-V (never poison FujiVkContext) */
#define FUJI_VK_ERR_CUSTOM_SPV_INVALID            (-10)
#define FUJI_VK_ERR_CUSTOM_SPV_COMPILE            (-11)

/*
 * Fixed 80-byte C ABI descriptor for one custom plugin SPIR-V compute pass
 * (set = 0: binding 0=SrcRgba, 1=DstRgba, 2=DepthMeters, 3=PluginUbo [32B std + <=4096B user],
 * optional push_constant <= 128B).
 */
typedef struct FujiVkCustomSpvStageSpec {
    uint32_t       stage;            /* 0 = PRE_OPTICS_FILTER, 1 = POST_OPTICS_RGB */
    uint32_t       workgroupSizeX;   /* 1..64 (default 16) */
    uint32_t       workgroupSizeY;   /* 1..64 (default 16) */
    uint32_t       spirvByteLen;     /* 20..262144, multiple of 4 */
    const uint8_t* spirvBytes;       /* raw Khronos SPIR-V binary */
    const char*    entryPoint;       /* UTF-8 entry point name (NULL -> "main") */
    const uint8_t* uniformData;      /* optional <= 4096 B plugin UBO payload */
    uint32_t       uniformBytes;     /* 0..4096 */
    uint32_t       pushConstBytes;   /* 0..128 */
    const uint8_t* pushConstants;    /* optional <= 128 B push constant block */
    float          focusM;           /* standard UBO header: active focus distance (m) */
    float          fNumber;          /* standard UBO header: active f-number */
    float          focalMm;          /* standard UBO header: active focal length (mm) */
    uint32_t       frameIndex;       /* standard UBO header: monotonic frame index */
    uint32_t       flags;            /* standard UBO header: bit0 = depth valid */
    uint32_t       reserved;         /* must be 0 */
} FujiVkCustomSpvStageSpec;

/* Fixed 32-byte C ABI cache telemetry for custom SPIR-V LRU pipeline cache. */
typedef struct FujiVkCustomSpvCacheStats {
    uint32_t capacity;
    uint32_t entries;
    uint64_t hits;
    uint64_t misses;
    uint64_t evictions;
} FujiVkCustomSpvCacheStats;

/*
 * Strictly validates raw SPIR-V bytes without touching Vulkan ICD:
 * checks size (20..256 KiB), 4-byte alignment, little-endian magic 0x07230203,
 * id bound > 0, schema == 0, complete instruction word-count framing,
 * OpCapability Shader, and matching OpEntryPoint GLCompute.
 */
FUJI_VK_EXPORT int32_t fuji_vk_validate_custom_spv(
    const uint8_t* spirv_bytes,
    size_t         spirv_byte_len,
    const char*    entry_point,
    char*          err_buf,
    size_t         err_buf_len);

/*
 * Validates, compiles (using ctx->pipelineCache), and caches a custom SPIR-V compute
 * pipeline in the bounded 32-entry LRU cache keyed by SHA-256(spirv) + entryPoint +
 * alignedPushConstBytes. Returns FUJI_VK_OK and sets *out_handle on success.
 * On any error, leaves ctx completely healthy/unpoisoned and returns
 * FUJI_VK_ERR_CUSTOM_SPV_INVALID or FUJI_VK_ERR_CUSTOM_SPV_COMPILE.
 */
FUJI_VK_EXPORT int32_t fuji_vk_register_custom_spv(
    FujiVkContext* ctx,
    const uint8_t* spirv_bytes,
    size_t         spirv_byte_len,
    const char*    entry_point,
    uint32_t       push_const_bytes,
    uint64_t*      out_handle);

/*
 * Sets the active custom SPIR-V stages (0..8) attached to ctx for subsequent
 * render_optics / render_to_bayer / render_to_isp_yuv / render_cached_scene_* dispatches.
 * Passing stage_count == 0 (or stages == NULL) clears all custom stages with zero overhead.
 * Pre-validates and compiles/caches ALL stages up front; on error, clears stages without
 * poisoning ctx and returns FUJI_VK_ERR_CUSTOM_SPV_INVALID or FUJI_VK_ERR_CUSTOM_SPV_COMPILE.
 */
FUJI_VK_EXPORT int32_t fuji_vk_set_custom_spv_stages(
    FujiVkContext*                  ctx,
    const FujiVkCustomSpvStageSpec* stages,
    uint32_t                        stage_count);

/* Queries the 32-entry custom SPIR-V LRU cache statistics for ctx. */
FUJI_VK_EXPORT int32_t fuji_vk_custom_spv_cache_stats(
    const FujiVkContext*       ctx,
    FujiVkCustomSpvCacheStats* out_stats);

/*
 * Standalone single-pass execution helper: uploads interleaved in_rgba_depth (width*height*4 floats),
 * extracts DepthMeters (.w) on GPU, executes the specified custom SPIR-V shader, and reads back
 * out_rgba (width*height*4 floats). Ideal for native unit tests and direct shader verification.
 */
FUJI_VK_EXPORT int32_t fuji_vk_run_custom_spv_pass(
    FujiVkContext*                  ctx,
    const FujiVkCustomSpvStageSpec* stage,
    const float*                    in_rgba_depth,
    float*                          out_rgba,
    uint32_t                        width,
    uint32_t                        height,
    double*                         out_gpu_ms);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_VULKAN_CUSTOM_SPV_H */
