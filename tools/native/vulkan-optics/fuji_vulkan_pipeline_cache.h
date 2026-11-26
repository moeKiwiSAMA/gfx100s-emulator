#ifndef FUJI_VULKAN_PIPELINE_CACHE_H
#define FUJI_VULKAN_PIPELINE_CACHE_H

/*
 * Disk-persistent VkPipelineCache header validation, self-contained SHA-256,
 * atomic cache persistence, and high-water-mark Vulkan memory sizing helpers.
 */

#include <stddef.h>
#include <stdint.h>
#include <string>
#include <vector>

#include "fuji_vk_abi.h"

struct FujiVkPipelineCacheIdentity {
    uint32_t abiVersion = 0u;
    uint32_t vendorId = 0u;
    uint32_t deviceId = 0u;
    uint32_t driverVersion = 0u;
    uint8_t  pipelineCacheUUID[VK_UUID_SIZE] = {};
    uint8_t  spirvSha256[32] = {};
};

/* Computes a deterministic SHA-256 digest over all embedded SPIR-V compute shaders. */
void fuji_vk_compute_spirv_sha256(uint8_t out_sha256[32]);

/*
 * Resolves the target pipeline cache file path:
 *   1) explicit_path (if non-empty; if it ends with '/' or '\\' or is an existing dir, appends filename)
 *   2) global override set via fuji_vk_set_pipeline_cache_path()
 *   3) FUJI_VULKAN_PIPELINE_CACHE_PATH environment variable
 *   4) platform user-writable cache directory (`vulkan-pipeline-<vendor>-<device>.bin`)
 */
std::string fuji_vk_resolve_pipeline_cache_path(
    uint32_t vendorId,
    uint32_t deviceId,
    const char* explicit_path,
    const std::string& global_override_path);

/*
 * Loads and validates a disk pipeline cache file (`FVPC` header + identity + SHA-256 integrity).
 * Returns true iff the file exists, matches the exact GPU/driver/UUID/SPIR-V identity, and passes
 * payload integrity check (populating `out_blob`). Returns false cleanly on miss/mismatch/corruption.
 */
bool fuji_vk_load_pipeline_cache_blob(
    const std::string& path,
    const FujiVkPipelineCacheIdentity& expected,
    std::vector<uint8_t>& out_blob);

/*
 * Atomically writes the validated `FVPC` header + Vulkan driver pipeline cache blob to disk
 * (temp file + atomic rename) so concurrent/crashed runs never leave half-written cache files.
 */
bool fuji_vk_save_pipeline_cache_blob(
    const std::string& path,
    const FujiVkPipelineCacheIdentity& id,
    const void* blob_data,
    size_t blob_size);

/*
 * High-water-mark buffer capacity calculator:
 *   - Keeps current capacity without reallocation when `cur_cap >= needed` unless `cur_cap` is >4x
 *     oversized AND larger than 64 MiB (preventing unbounded still-capture RAM retention while
 *     guaranteeing zero reallocations across LiveView EVF 1280x960 <-> Rear LCD 1024x768 switches).
 *   - When growing, allocates with 1.25x headroom aligned up to 4 KiB.
 */
VkDeviceSize fuji_vk_hwm_capacity(
    VkDeviceSize current_capacity,
    VkDeviceSize needed_bytes,
    bool& out_should_realloc);

#endif /* FUJI_VULKAN_PIPELINE_CACHE_H */
