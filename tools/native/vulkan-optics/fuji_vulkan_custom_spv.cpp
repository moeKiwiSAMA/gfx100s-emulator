/*
 * FUJIFILM GFX100S Emulator - Plugin SPI v1.0 Custom SPIR-V Compute Shader Runtime
 *
 * Dynamically executes validated third-party SPIR-V compute shaders in-place on
 * DEVICE_LOCAL VRAM inside the active FujiVkContext command buffer with ZERO extra
 * PCIe round-trips, bounded 32-entry SHA-256 LRU pipeline caching (backed by
 * ctx->pipelineCache), and strict isolation so malformed shaders never poison ctx.
 */

#include "fuji_vulkan_custom_spv.h"
#include "fuji_vk_abi.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

static_assert(sizeof(FujiVkCustomSpvStageSpec) == 80, "FujiVkCustomSpvStageSpec must be 80 bytes");
static_assert(sizeof(FujiVkCustomSpvCacheStats) == 32, "FujiVkCustomSpvCacheStats must be 32 bytes");

/* Internal bridge defined in fuji_vulkan_optics.cpp (zero struct layout dependency). */
struct FujiVkInternalDeviceView {
    VkDevice                                device;
    VkPhysicalDevice                        physDev;
    VkQueue                                 computeQueue;
    uint32_t                                queueFamily;
    VkCommandPool                           cmdPool;
    const VkPhysicalDeviceMemoryProperties* memProps;
    PFN_vkGetDeviceProcAddr                 getDeviceProcAddr;
    VkPipelineCache                         pipelineCache;
};

extern bool fuji_vk_internal_get_device_view(FujiVkContext* ctx, FujiVkInternalDeviceView* out_view);
extern void fuji_vk_internal_set_error(FujiVkContext* ctx, const char* msg);
extern void fuji_vk_internal_mark_pipeline_cache_dirty(FujiVkContext* ctx);

namespace {

void write_err(char* buf, size_t len, const char* fmt, ...) {
    if (!buf || len == 0) return;
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, len, fmt, ap);
    va_end(ap);
}

/* Self-contained SHA-256 (FIPS 180-4) for exact SPIR-V bytecode cache keying (zero external deps). */
struct Sha256Digest {
    uint8_t bytes[32];
};

static inline uint32_t rotr32(uint32_t x, uint32_t n) { return (x >> n) | (x << (32u - n)); }

Sha256Digest compute_sha256(const uint8_t* data, size_t len) {
    static constexpr uint32_t K[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    };
    uint32_t h[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    };
    auto process_block = [&](const uint8_t block[64]) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(block[i * 4 + 0]) << 24) |
                   (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(block[i * 4 + 2]) << 8) |
                   static_cast<uint32_t>(block[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t temp1 = hh + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t temp2 = S0 + maj;
            hh = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    };

    size_t offset = 0;
    while (offset + 64u <= len) {
        process_block(data + offset);
        offset += 64u;
    }
    uint8_t tail[128] = {};
    const size_t rem = len - offset;
    if (rem > 0u) std::memcpy(tail, data + offset, rem);
    tail[rem] = 0x80u;
    const size_t padLen = (rem < 56u) ? 64u : 128u;
    const uint64_t bitLen = static_cast<uint64_t>(len) * 8ULL;
    for (int i = 0; i < 8; ++i) {
        tail[padLen - 1u - static_cast<size_t>(i)] = static_cast<uint8_t>((bitLen >> (i * 8)) & 0xFFu);
    }
    process_block(tail);
    if (padLen == 128u) process_block(tail + 64);

    Sha256Digest out{};
    for (int i = 0; i < 8; ++i) {
        out.bytes[i * 4 + 0] = static_cast<uint8_t>((h[i] >> 24) & 0xFFu);
        out.bytes[i * 4 + 1] = static_cast<uint8_t>((h[i] >> 16) & 0xFFu);
        out.bytes[i * 4 + 2] = static_cast<uint8_t>((h[i] >> 8) & 0xFFu);
        out.bytes[i * 4 + 3] = static_cast<uint8_t>(h[i] & 0xFFu);
    }
    return out;
}

uint32_t read_u32_le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

/* Minimal Khronos SPIR-V 1.0 assembler for built-in GPU depth-extract & depth-restore helper kernels. */
struct MiniSpv {
    std::vector<uint32_t> words;
    uint32_t nextId = 1u;

    MiniSpv() {
        words.reserve(160);
        words.push_back(0x07230203u); /* SPIR-V magic */
        words.push_back(0x00010000u); /* version 1.0 */
        words.push_back(0u);          /* generator */
        words.push_back(0u);          /* bound (patched in finish()) */
        words.push_back(0u);          /* schema */
    }
    uint32_t id() { return nextId++; }
    void op(uint16_t opcode, std::initializer_list<uint32_t> args) {
        uint32_t wc = static_cast<uint32_t>(1u + args.size());
        words.push_back((wc << 16) | static_cast<uint32_t>(opcode));
        for (uint32_t a : args) words.push_back(a);
    }
    void opStr(uint16_t opcode, std::initializer_list<uint32_t> prefix, const char* s, std::initializer_list<uint32_t> suffix = {}) {
        const size_t slen = std::strlen(s) + 1u;
        const size_t swords = (slen + 3u) / 4u;
        uint32_t wc = static_cast<uint32_t>(1u + prefix.size() + swords + suffix.size());
        words.push_back((wc << 16) | static_cast<uint32_t>(opcode));
        for (uint32_t a : prefix) words.push_back(a);
        for (size_t i = 0; i < swords; ++i) {
            uint32_t w = 0u;
            for (size_t b = 0; b < 4u; ++b) {
                size_t idx = i * 4u + b;
                if (idx < slen) w |= static_cast<uint32_t>(static_cast<uint8_t>(s[idx])) << (b * 8u);
            }
            words.push_back(w);
        }
        for (uint32_t a : suffix) words.push_back(a);
    }
    std::vector<uint32_t> finish() {
        words[3] = nextId;
        return std::move(words);
    }
};

/*
 * Builds SPIR-V 1.0 kernel using the standard 4-binding layout:
 *   b0 = SrcRgba (vec4[]), b1 = DstRgba (unused), b2 = DepthMeters (float[]), b3 = PluginUbo { uint width, height, ... }
 * Copies depth[idx] = src[idx].w for idx < width * height (local_size_x = 64).
 */
std::vector<uint32_t> build_extract_depth_spv() {
    MiniSpv s;
    const uint32_t fnMain = s.id();
    const uint32_t tVoid = s.id(), tFn = s.id();
    const uint32_t tFloat = s.id(), tVec4 = s.id(), tUint = s.id(), tVec3U = s.id(), tBool = s.id();
    const uint32_t tArrVec4 = s.id(), tSrcStruct = s.id(), tPtrSrcStruct = s.id(), tPtrSrcVec4 = s.id();
    const uint32_t tArrFloat = s.id(), tDepthStruct = s.id(), tPtrDepthStruct = s.id(), tPtrDepthFloat = s.id();
    const uint32_t tUboStruct = s.id(), tPtrUboStruct = s.id(), tPtrUboUint = s.id();
    const uint32_t tPtrInVec3U = s.id(), tPtrInUint = s.id();
    const uint32_t vSrc = s.id(), vDepth = s.id(), vUbo = s.id(), vGid = s.id();
    const uint32_t c0 = s.id(), c1 = s.id(), c3 = s.id();
    const uint32_t lblEntry = s.id(), lblBody = s.id(), lblExit = s.id();
    const uint32_t rPtrGx = s.id(), rIdx = s.id();
    const uint32_t rPtrW = s.id(), rW = s.id(), rPtrH = s.id(), rH = s.id(), rCount = s.id(), rCmp = s.id();
    const uint32_t rPtrSrcW = s.id(), rDepthVal = s.id(), rPtrDstD = s.id();

    s.op(17, {1}); /* OpCapability Shader */
    s.op(14, {0, 1}); /* OpMemoryModel Logical GLSL450 */
    s.opStr(15, {5, fnMain}, "main", {vGid}); /* OpEntryPoint GLCompute %main "main" %vGid */
    s.op(16, {fnMain, 17, 64, 1, 1}); /* OpExecutionMode LocalSize 64 1 1 */

    /* Decorations */
    s.op(71, {tArrVec4, 6, 16}); /* ArrayStride 16 */
    s.op(72, {tSrcStruct, 0, 35, 0}); /* MemberDecorate Offset 0 */
    s.op(71, {tSrcStruct, 3}); /* BufferBlock */
    s.op(71, {vSrc, 34, 0}); /* DescriptorSet 0 */
    s.op(71, {vSrc, 33, 0}); /* Binding 0 */

    s.op(71, {tArrFloat, 6, 4}); /* ArrayStride 4 */
    s.op(72, {tDepthStruct, 0, 35, 0}); /* Offset 0 */
    s.op(71, {tDepthStruct, 3}); /* BufferBlock */
    s.op(71, {vDepth, 34, 0}); /* DescriptorSet 0 */
    s.op(71, {vDepth, 33, 2}); /* Binding 2 */

    s.op(72, {tUboStruct, 0, 35, 0}); /* width @ 0 */
    s.op(72, {tUboStruct, 1, 35, 4}); /* height @ 4 */
    s.op(71, {tUboStruct, 2}); /* Block */
    s.op(71, {vUbo, 34, 0}); /* DescriptorSet 0 */
    s.op(71, {vUbo, 33, 3}); /* Binding 3 */

    s.op(71, {vGid, 11, 28}); /* BuiltIn GlobalInvocationId */

    /* Types */
    s.op(19, {tVoid});
    s.op(33, {tFn, tVoid});
    s.op(22, {tFloat, 32});
    s.op(23, {tVec4, tFloat, 4});
    s.op(21, {tUint, 32, 0});
    s.op(23, {tVec3U, tUint, 3});
    s.op(20, {tBool});

    s.op(29, {tArrVec4, tVec4});
    s.op(30, {tSrcStruct, tArrVec4});
    s.op(32, {tPtrSrcStruct, 2, tSrcStruct});
    s.op(32, {tPtrSrcVec4, 2, tFloat});

    s.op(29, {tArrFloat, tFloat});
    s.op(30, {tDepthStruct, tArrFloat});
    s.op(32, {tPtrDepthStruct, 2, tDepthStruct});
    s.op(32, {tPtrDepthFloat, 2, tFloat});

    s.op(30, {tUboStruct, tUint, tUint});
    s.op(32, {tPtrUboStruct, 2, tUboStruct});
    s.op(32, {tPtrUboUint, 2, tUint});

    s.op(32, {tPtrInVec3U, 1, tVec3U});
    s.op(32, {tPtrInUint, 1, tUint});

    /* Constants */
    s.op(43, {tUint, c0, 0});
    s.op(43, {tUint, c1, 1});
    s.op(43, {tUint, c3, 3});

    /* Global variables */
    s.op(59, {tPtrSrcStruct, vSrc, 2});
    s.op(59, {tPtrDepthStruct, vDepth, 2});
    s.op(59, {tPtrUboStruct, vUbo, 2});
    s.op(59, {tPtrInVec3U, vGid, 1});

    /* Function main */
    s.op(54, {tVoid, fnMain, 0, tFn});
    s.op(248, {lblEntry});
    s.op(65, {tPtrInUint, rPtrGx, vGid, c0});
    s.op(61, {tUint, rIdx, rPtrGx});
    s.op(65, {tPtrUboUint, rPtrW, vUbo, c0});
    s.op(61, {tUint, rW, rPtrW});
    s.op(65, {tPtrUboUint, rPtrH, vUbo, c1});
    s.op(61, {tUint, rH, rPtrH});
    s.op(132, {tUint, rCount, rW, rH}); /* OpIMul */
    s.op(176, {tBool, rCmp, rIdx, rCount}); /* OpULessThan */
    s.op(247, {lblExit, 0}); /* OpSelectionMerge */
    s.op(250, {rCmp, lblBody, lblExit}); /* OpBranchConditional */

    s.op(248, {lblBody});
    s.op(65, {tPtrSrcVec4, rPtrSrcW, vSrc, c0, rIdx, c3}); /* &src.px[idx].w */
    s.op(61, {tFloat, rDepthVal, rPtrSrcW});
    s.op(65, {tPtrDepthFloat, rPtrDstD, vDepth, c0, rIdx}); /* &depth.d[idx] */
    s.op(62, {rPtrDstD, rDepthVal});
    s.op(249, {lblExit});

    s.op(248, {lblExit});
    s.op(253, {}); /* OpReturn */
    s.op(56, {});  /* OpFunctionEnd */
    return s.finish();
}

/*
 * Builds SPIR-V 1.0 kernel using the standard 4-binding layout:
 *   b0 = SrcRgba (vec4[]), b1 = DstRgba (vec4[]), b2 = DepthMeters (float[]), b3 = PluginUbo { uint width, height, ... }
 * Writes dst[idx] = vec4(src[idx].xyz, depth[idx]) for idx < width * height (local_size_x = 64).
 */
std::vector<uint32_t> build_restore_depth_spv() {
    MiniSpv s;
    const uint32_t fnMain = s.id();
    const uint32_t tVoid = s.id(), tFn = s.id();
    const uint32_t tFloat = s.id(), tVec4 = s.id(), tUint = s.id(), tVec3U = s.id(), tBool = s.id();
    const uint32_t tArrVec4 = s.id(), tRgbaStruct = s.id(), tPtrRgbaStruct = s.id(), tPtrVec4 = s.id();
    const uint32_t tArrFloat = s.id(), tDepthStruct = s.id(), tPtrDepthStruct = s.id(), tPtrFloat = s.id();
    const uint32_t tUboStruct = s.id(), tPtrUboStruct = s.id(), tPtrUboUint = s.id();
    const uint32_t tPtrInVec3U = s.id(), tPtrInUint = s.id();
    const uint32_t vSrc = s.id(), vDst = s.id(), vDepth = s.id(), vUbo = s.id(), vGid = s.id();
    const uint32_t c0 = s.id(), c1 = s.id();
    const uint32_t lblEntry = s.id(), lblBody = s.id(), lblExit = s.id();
    const uint32_t rPtrGx = s.id(), rIdx = s.id();
    const uint32_t rPtrW = s.id(), rW = s.id(), rPtrH = s.id(), rH = s.id(), rCount = s.id(), rCmp = s.id();
    const uint32_t rPtrSrc = s.id(), rCol = s.id(), rPtrDep = s.id(), rDep = s.id();
    const uint32_t rX = s.id(), rY = s.id(), rZ = s.id(), rOut = s.id(), rPtrDst = s.id();

    s.op(17, {1});
    s.op(14, {0, 1});
    s.opStr(15, {5, fnMain}, "main", {vGid});
    s.op(16, {fnMain, 17, 64, 1, 1});

    s.op(71, {tArrVec4, 6, 16});
    s.op(72, {tRgbaStruct, 0, 35, 0});
    s.op(71, {tRgbaStruct, 3});
    s.op(71, {vSrc, 34, 0}); s.op(71, {vSrc, 33, 0});
    s.op(71, {vDst, 34, 0}); s.op(71, {vDst, 33, 1});

    s.op(71, {tArrFloat, 6, 4});
    s.op(72, {tDepthStruct, 0, 35, 0});
    s.op(71, {tDepthStruct, 3});
    s.op(71, {vDepth, 34, 0}); s.op(71, {vDepth, 33, 2});

    s.op(72, {tUboStruct, 0, 35, 0});
    s.op(72, {tUboStruct, 1, 35, 4});
    s.op(71, {tUboStruct, 2});
    s.op(71, {vUbo, 34, 0}); s.op(71, {vUbo, 33, 3});

    s.op(71, {vGid, 11, 28});

    s.op(19, {tVoid});
    s.op(33, {tFn, tVoid});
    s.op(22, {tFloat, 32});
    s.op(23, {tVec4, tFloat, 4});
    s.op(21, {tUint, 32, 0});
    s.op(23, {tVec3U, tUint, 3});
    s.op(20, {tBool});

    s.op(29, {tArrVec4, tVec4});
    s.op(30, {tRgbaStruct, tArrVec4});
    s.op(32, {tPtrRgbaStruct, 2, tRgbaStruct});
    s.op(32, {tPtrVec4, 2, tVec4});

    s.op(29, {tArrFloat, tFloat});
    s.op(30, {tDepthStruct, tArrFloat});
    s.op(32, {tPtrDepthStruct, 2, tDepthStruct});
    s.op(32, {tPtrFloat, 2, tFloat});

    s.op(30, {tUboStruct, tUint, tUint});
    s.op(32, {tPtrUboStruct, 2, tUboStruct});
    s.op(32, {tPtrUboUint, 2, tUint});

    s.op(32, {tPtrInVec3U, 1, tVec3U});
    s.op(32, {tPtrInUint, 1, tUint});

    s.op(43, {tUint, c0, 0});
    s.op(43, {tUint, c1, 1});

    s.op(59, {tPtrRgbaStruct, vSrc, 2});
    s.op(59, {tPtrRgbaStruct, vDst, 2});
    s.op(59, {tPtrDepthStruct, vDepth, 2});
    s.op(59, {tPtrUboStruct, vUbo, 2});
    s.op(59, {tPtrInVec3U, vGid, 1});

    s.op(54, {tVoid, fnMain, 0, tFn});
    s.op(248, {lblEntry});
    s.op(65, {tPtrInUint, rPtrGx, vGid, c0});
    s.op(61, {tUint, rIdx, rPtrGx});
    s.op(65, {tPtrUboUint, rPtrW, vUbo, c0});
    s.op(61, {tUint, rW, rPtrW});
    s.op(65, {tPtrUboUint, rPtrH, vUbo, c1});
    s.op(61, {tUint, rH, rPtrH});
    s.op(132, {tUint, rCount, rW, rH});
    s.op(176, {tBool, rCmp, rIdx, rCount}); /* OpULessThan */
    s.op(247, {lblExit, 0});
    s.op(250, {rCmp, lblBody, lblExit});

    s.op(248, {lblBody});
    s.op(65, {tPtrVec4, rPtrSrc, vSrc, c0, rIdx});
    s.op(61, {tVec4, rCol, rPtrSrc});
    s.op(65, {tPtrFloat, rPtrDep, vDepth, c0, rIdx});
    s.op(61, {tFloat, rDep, rPtrDep});
    s.op(81, {tFloat, rX, rCol, 0}); /* OpCompositeExtract */
    s.op(81, {tFloat, rY, rCol, 1});
    s.op(81, {tFloat, rZ, rCol, 2});
    s.op(80, {tVec4, rOut, rX, rY, rZ, rDep}); /* OpCompositeConstruct */
    s.op(65, {tPtrVec4, rPtrDst, vDst, c0, rIdx});
    s.op(62, {rPtrDst, rOut});
    s.op(249, {lblExit});

    s.op(248, {lblExit});
    s.op(253, {});
    s.op(56, {});
    return s.finish();
}

struct SpvBuf {
    VkBuffer       buffer = nullptr;
    VkDeviceMemory memory = nullptr;
    VkDeviceSize   capacity = 0;
    void*          mapped = nullptr;
};

struct SpvVk {
    PFN_vkCreateBuffer                createBuffer = nullptr;
    PFN_vkDestroyBuffer               destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements getBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory              allocateMemory = nullptr;
    PFN_vkFreeMemory                  freeMemory = nullptr;
    PFN_vkBindBufferMemory            bindBufferMemory = nullptr;
    PFN_vkMapMemory                   mapMemory = nullptr;
    PFN_vkUnmapMemory                 unmapMemory = nullptr;
    PFN_vkCreateShaderModule          createShaderModule = nullptr;
    PFN_vkDestroyShaderModule         destroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout   createDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout  destroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout        createPipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout       destroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines      createComputePipelines = nullptr;
    PFN_vkDestroyPipeline             destroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool        createDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool       destroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets      allocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets        updateDescriptorSets = nullptr;
    PFN_vkAllocateCommandBuffers      allocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer          resetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer          beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer            endCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline             cmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets       cmdBindDescriptorSets = nullptr;
    PFN_vkCmdPushConstants            cmdPushConstants = nullptr;
    PFN_vkCmdDispatch                 cmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier          cmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyBuffer               cmdCopyBuffer = nullptr;
    PFN_vkCreateFence                 createFence = nullptr;
    PFN_vkDestroyFence                destroyFence = nullptr;
    PFN_vkResetFences                 resetFences = nullptr;
    PFN_vkWaitForFences               waitForFences = nullptr;
    PFN_vkQueueSubmit                 queueSubmit = nullptr;
    PFN_vkDeviceWaitIdle              deviceWaitIdle = nullptr;
};

struct CustomSpvStdUbo {
    uint32_t width;
    uint32_t height;
    float    focusM;
    float    fNumber;
    float    focalMm;
    uint32_t frameIndex;
    uint32_t flags;
    uint32_t userBytes;
    uint8_t  userData[FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES];
};
static_assert(sizeof(CustomSpvStdUbo) == FUJI_VK_CUSTOM_SPV_STD_UBO_HEADER_BYTES + FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES,
              "CustomSpvStdUbo must be 4128 bytes");

struct CachedPipelineEntry {
    std::string      cacheKey;
    uint64_t         handle = 0ULL;
    uint32_t         alignedPushBytes = 0u;
    VkShaderModule   shaderModule = nullptr;
    VkPipelineLayout pipelineLayout = nullptr;
    VkPipeline       pipeline = nullptr;
};

struct ActiveStageBinding {
    uint32_t             stage = 0u;
    uint32_t             workgroupSizeX = 16u;
    uint32_t             workgroupSizeY = 16u;
    uint64_t             handle = 0ULL;
    VkPipeline           pipeline = nullptr;
    VkPipelineLayout     pipelineLayout = nullptr;
    uint32_t             alignedPushBytes = 0u;
    uint8_t              pushConstants[FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES] = {};
    uint32_t             uniformBytes = 0u;
    uint8_t              uniformData[FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES] = {};
    float                focusM = 2.0f;
    float                fNumber = 2.8f;
    float                focalMm = 50.0f;
    uint32_t             frameIndex = 0u;
    uint32_t             flags = 0u;
    uint32_t             descIndex = 0u;
};

struct CustomSpvState {
    /* 16 descriptor sets: [0]=preExtract, [1..8]=custom stages, [9]=preRestore, [10]=postExtract, [11]=standalone */
    static constexpr uint32_t kDescPoolSets = 16u;
    static constexpr VkDeviceSize kUboStride = 4352ULL; /* 4128 rounded up to 256B minUniformBufferOffsetAlignment */

    FujiVkInternalDeviceView view{};
    SpvVk                    vk{};
    bool                     initialized = false;

    VkDescriptorSetLayout descSetLayout = nullptr;
    VkPipelineLayout      helperPipeLayout = nullptr;
    VkDescriptorPool      descPool = nullptr;
    VkDescriptorSet       descSets[kDescPoolSets] = {};

    VkShaderModule extractDepthMod = nullptr;
    VkPipeline     extractDepthPipe = nullptr;
    VkShaderModule restoreDepthMod = nullptr;
    VkPipeline     restoreDepthPipe = nullptr;

    VkCommandBuffer standaloneCmdBuf = nullptr;
    VkFence         standaloneFence = nullptr;

    SpvBuf bufPing{};      /* DEVICE_LOCAL RGBA ping buffer */
    SpvBuf bufPong{};      /* DEVICE_LOCAL RGBA pong buffer */
    SpvBuf bufPreOut{};    /* DEVICE_LOCAL RGBA+Depth restored pre-stage output */
    SpvBuf bufDepth{};     /* DEVICE_LOCAL float[] depth meters */
    SpvBuf bufUbo{};       /* HOST_VISIBLE multi-slice PluginUbo */
    SpvBuf bufHostIn{};    /* HOST_VISIBLE standalone input */
    SpvBuf bufHostOut{};   /* HOST_VISIBLE standalone output */

    /* Bounded 32-entry LRU cache */
    std::list<CachedPipelineEntry> lruList;
    std::unordered_map<std::string, std::list<CachedPipelineEntry>::iterator> lruByKey;
    std::unordered_map<uint64_t, std::list<CachedPipelineEntry>::iterator> lruByHandle;
    uint64_t nextHandle = 1ULL;
    uint64_t cacheHits = 0ULL;
    uint64_t cacheMisses = 0ULL;
    uint64_t cacheEvictions = 0ULL;

    /* Currently attached stages for integrated optics/Bayer/ISP-YUV dispatch */
    std::vector<ActiveStageBinding> activeStages;

    /* Prepared per-dispatch stage state */
    VkBuffer lastPreOutputBuffer = nullptr;
    VkBuffer lastPostFinalBuffer = nullptr;
    uint32_t prePreparedWidth = 0u;
    uint32_t prePreparedHeight = 0u;
    uint32_t postPreparedWidth = 0u;
    uint32_t postPreparedHeight = 0u;

    int32_t findMemoryType(uint32_t typeBits, VkFlags reqProps) const {
        if (!view.memProps) return -1;
        for (uint32_t i = 0; i < view.memProps->memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) != 0u &&
                (view.memProps->memoryTypes[i].propertyFlags & reqProps) == reqProps) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    void freeBuf(SpvBuf& b) {
        if (!view.device) return;
        if (b.mapped && b.memory && vk.unmapMemory) {
            vk.unmapMemory(view.device, b.memory);
        }
        if (b.buffer && vk.destroyBuffer) vk.destroyBuffer(view.device, b.buffer, nullptr);
        if (b.memory && vk.freeMemory) vk.freeMemory(view.device, b.memory, nullptr);
        b = SpvBuf{};
    }

    bool ensureBuf(SpvBuf& b, VkDeviceSize minBytes, VkFlags usage, bool hostVisible) {
        if (minBytes < 64ULL) minBytes = 64ULL;
        if (b.buffer && b.capacity >= minBytes) return true;
        freeBuf(b);

        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = minBytes;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk.createBuffer(view.device, &bci, nullptr, &b.buffer) != VK_SUCCESS) return false;

        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(view.device, b.buffer, &req);
        int32_t memIdx = -1;
        if (hostVisible) {
            memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        } else {
            memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memIdx < 0) {
                memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            }
        }
        if (memIdx < 0) {
            freeBuf(b);
            return false;
        }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(memIdx);
        if (vk.allocateMemory(view.device, &mai, nullptr, &b.memory) != VK_SUCCESS) {
            freeBuf(b);
            return false;
        }
        if (vk.bindBufferMemory(view.device, b.buffer, b.memory, 0) != VK_SUCCESS) {
            freeBuf(b);
            return false;
        }
        if (hostVisible) {
            if (vk.mapMemory(view.device, b.memory, 0, minBytes, 0, &b.mapped) != VK_SUCCESS) {
                freeBuf(b);
                return false;
            }
        }
        b.capacity = minBytes;
        return true;
    }

    void updateDescSet(
        uint32_t dsIdx,
        VkBuffer srcBuf, VkDeviceSize srcBytes,
        VkBuffer dstBuf, VkDeviceSize dstBytes,
        VkBuffer depthBuf, VkDeviceSize depthBytes,
        uint32_t uboSlot) {
        VkDescriptorBufferInfo bi[4]{};
        bi[0] = {srcBuf, 0, srcBytes};
        bi[1] = {dstBuf, 0, dstBytes};
        bi[2] = {depthBuf, 0, depthBytes};
        bi[3] = {bufUbo.buffer, static_cast<VkDeviceSize>(uboSlot) * kUboStride, sizeof(CustomSpvStdUbo)};

        VkWriteDescriptorSet writes[4]{};
        for (uint32_t i = 0; i < 4u; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = descSets[dsIdx];
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1u;
            writes[i].descriptorType = (i == 3u) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &bi[i];
        }
        vk.updateDescriptorSets(view.device, 4u, writes, 0, nullptr);
    }

    void writeUboSlot(
        uint32_t uboSlot,
        uint32_t width,
        uint32_t height,
        float focusM,
        float fNumber,
        float focalMm,
        uint32_t frameIndex,
        uint32_t flags,
        const uint8_t* userData,
        uint32_t userBytes) {
        auto* dst = reinterpret_cast<CustomSpvStdUbo*>(
            static_cast<uint8_t*>(bufUbo.mapped) + static_cast<size_t>(uboSlot) * kUboStride);
        dst->width = width;
        dst->height = height;
        dst->focusM = focusM;
        dst->fNumber = fNumber;
        dst->focalMm = focalMm;
        dst->frameIndex = frameIndex;
        dst->flags = flags;
        const uint32_t copyLen = std::min<uint32_t>(userBytes, FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES);
        dst->userBytes = copyLen;
        if (copyLen > 0u && userData) {
            std::memcpy(dst->userData, userData, copyLen);
        }
        if (copyLen < FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES) {
            std::memset(dst->userData + copyLen, 0, FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES - copyLen);
        }
    }

    void destroyEntry(CachedPipelineEntry& e) {
        if (!view.device) return;
        if (e.pipeline && vk.destroyPipeline) vk.destroyPipeline(view.device, e.pipeline, nullptr);
        if (e.pipelineLayout && vk.destroyPipelineLayout) vk.destroyPipelineLayout(view.device, e.pipelineLayout, nullptr);
        if (e.shaderModule && vk.destroyShaderModule) vk.destroyShaderModule(view.device, e.shaderModule, nullptr);
        e.pipeline = nullptr;
        e.pipelineLayout = nullptr;
        e.shaderModule = nullptr;
    }

    void destroy() {
        if (view.device) {
            if (vk.deviceWaitIdle) vk.deviceWaitIdle(view.device);
            for (auto& e : lruList) destroyEntry(e);
            lruList.clear();
            lruByKey.clear();
            lruByHandle.clear();
            activeStages.clear();

            freeBuf(bufPing);
            freeBuf(bufPong);
            freeBuf(bufPreOut);
            freeBuf(bufDepth);
            freeBuf(bufUbo);
            freeBuf(bufHostIn);
            freeBuf(bufHostOut);

            if (extractDepthPipe && vk.destroyPipeline) vk.destroyPipeline(view.device, extractDepthPipe, nullptr);
            if (extractDepthMod && vk.destroyShaderModule) vk.destroyShaderModule(view.device, extractDepthMod, nullptr);
            if (restoreDepthPipe && vk.destroyPipeline) vk.destroyPipeline(view.device, restoreDepthPipe, nullptr);
            if (restoreDepthMod && vk.destroyShaderModule) vk.destroyShaderModule(view.device, restoreDepthMod, nullptr);
            if (standaloneFence && vk.destroyFence) vk.destroyFence(view.device, standaloneFence, nullptr);
            if (descPool && vk.destroyDescriptorPool) vk.destroyDescriptorPool(view.device, descPool, nullptr);
            if (helperPipeLayout && vk.destroyPipelineLayout) vk.destroyPipelineLayout(view.device, helperPipeLayout, nullptr);
            if (descSetLayout && vk.destroyDescriptorSetLayout) vk.destroyDescriptorSetLayout(view.device, descSetLayout, nullptr);
        }
        initialized = false;
    }

    ~CustomSpvState() { destroy(); }
};

std::mutex g_custom_spv_mutex;
std::unordered_map<const FujiVkContext*, std::unique_ptr<CustomSpvState>> g_custom_spv_states;

template <typename Fn>
bool load_spv_fn(const FujiVkInternalDeviceView& v, const char* name, Fn& out) {
    out = reinterpret_cast<Fn>(v.getDeviceProcAddr(v.device, name));
    return out != nullptr;
}

bool create_helper_pipe(
    CustomSpvState& st,
    const std::vector<uint32_t>& words,
    VkShaderModule& outMod,
    VkPipeline& outPipe) {
    VkShaderModuleCreateInfo smci{};
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = words.size() * sizeof(uint32_t);
    smci.pCode = words.data();
    if (st.vk.createShaderModule(st.view.device, &smci, nullptr, &outMod) != VK_SUCCESS) return false;

    VkComputePipelineCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = outMod;
    cpci.stage.pName = "main";
    cpci.layout = st.helperPipeLayout;
    if (st.vk.createComputePipelines(st.view.device, st.view.pipelineCache, 1, &cpci, nullptr, &outPipe) != VK_SUCCESS) {
        return false;
    }
    return true;
}

CustomSpvState* get_or_init_state_locked(FujiVkContext* ctx) {
    auto it = g_custom_spv_states.find(ctx);
    if (it != g_custom_spv_states.end() && it->second && it->second->initialized) {
        return it->second.get();
    }

    FujiVkInternalDeviceView view{};
    if (!fuji_vk_internal_get_device_view(ctx, &view)) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V runtime requires an initialized FujiVkContext");
        return nullptr;
    }

    auto st = std::make_unique<CustomSpvState>();
    st->view = view;

    bool ok =
        load_spv_fn(view, "vkCreateBuffer", st->vk.createBuffer) &&
        load_spv_fn(view, "vkDestroyBuffer", st->vk.destroyBuffer) &&
        load_spv_fn(view, "vkGetBufferMemoryRequirements", st->vk.getBufferMemoryRequirements) &&
        load_spv_fn(view, "vkAllocateMemory", st->vk.allocateMemory) &&
        load_spv_fn(view, "vkFreeMemory", st->vk.freeMemory) &&
        load_spv_fn(view, "vkBindBufferMemory", st->vk.bindBufferMemory) &&
        load_spv_fn(view, "vkMapMemory", st->vk.mapMemory) &&
        load_spv_fn(view, "vkUnmapMemory", st->vk.unmapMemory) &&
        load_spv_fn(view, "vkCreateShaderModule", st->vk.createShaderModule) &&
        load_spv_fn(view, "vkDestroyShaderModule", st->vk.destroyShaderModule) &&
        load_spv_fn(view, "vkCreateDescriptorSetLayout", st->vk.createDescriptorSetLayout) &&
        load_spv_fn(view, "vkDestroyDescriptorSetLayout", st->vk.destroyDescriptorSetLayout) &&
        load_spv_fn(view, "vkCreatePipelineLayout", st->vk.createPipelineLayout) &&
        load_spv_fn(view, "vkDestroyPipelineLayout", st->vk.destroyPipelineLayout) &&
        load_spv_fn(view, "vkCreateComputePipelines", st->vk.createComputePipelines) &&
        load_spv_fn(view, "vkDestroyPipeline", st->vk.destroyPipeline) &&
        load_spv_fn(view, "vkCreateDescriptorPool", st->vk.createDescriptorPool) &&
        load_spv_fn(view, "vkDestroyDescriptorPool", st->vk.destroyDescriptorPool) &&
        load_spv_fn(view, "vkAllocateDescriptorSets", st->vk.allocateDescriptorSets) &&
        load_spv_fn(view, "vkUpdateDescriptorSets", st->vk.updateDescriptorSets) &&
        load_spv_fn(view, "vkAllocateCommandBuffers", st->vk.allocateCommandBuffers) &&
        load_spv_fn(view, "vkResetCommandBuffer", st->vk.resetCommandBuffer) &&
        load_spv_fn(view, "vkBeginCommandBuffer", st->vk.beginCommandBuffer) &&
        load_spv_fn(view, "vkEndCommandBuffer", st->vk.endCommandBuffer) &&
        load_spv_fn(view, "vkCmdBindPipeline", st->vk.cmdBindPipeline) &&
        load_spv_fn(view, "vkCmdBindDescriptorSets", st->vk.cmdBindDescriptorSets) &&
        load_spv_fn(view, "vkCmdPushConstants", st->vk.cmdPushConstants) &&
        load_spv_fn(view, "vkCmdDispatch", st->vk.cmdDispatch) &&
        load_spv_fn(view, "vkCmdPipelineBarrier", st->vk.cmdPipelineBarrier) &&
        load_spv_fn(view, "vkCmdCopyBuffer", st->vk.cmdCopyBuffer) &&
        load_spv_fn(view, "vkCreateFence", st->vk.createFence) &&
        load_spv_fn(view, "vkDestroyFence", st->vk.destroyFence) &&
        load_spv_fn(view, "vkResetFences", st->vk.resetFences) &&
        load_spv_fn(view, "vkWaitForFences", st->vk.waitForFences) &&
        load_spv_fn(view, "vkQueueSubmit", st->vk.queueSubmit) &&
        load_spv_fn(view, "vkDeviceWaitIdle", st->vk.deviceWaitIdle);
    if (!ok) {
        fuji_vk_internal_set_error(ctx, "Required Vulkan device functions missing for custom SPIR-V runtime");
        return nullptr;
    }

    /* Fixed set = 0 Plugin Shader ABI descriptor set layout:
     * binding 0 = SrcRgba SSBO, binding 1 = DstRgba SSBO, binding 2 = DepthMeters SSBO, binding 3 = PluginUbo */
    VkDescriptorSetLayoutBinding bindings[4]{};
    bindings[0] = {0u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1u, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[1] = {1u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1u, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[2] = {2u, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1u, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[3] = {3u, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};

    VkDescriptorSetLayoutCreateInfo dslci{};
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 4u;
    dslci.pBindings = bindings;
    if (st->vk.createDescriptorSetLayout(view.device, &dslci, nullptr, &st->descSetLayout) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkCreateDescriptorSetLayout failed");
        return nullptr;
    }

    VkPipelineLayoutCreateInfo plci{};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1u;
    plci.pSetLayouts = &st->descSetLayout;
    if (st->vk.createPipelineLayout(view.device, &plci, nullptr, &st->helperPipeLayout) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V helper vkCreatePipelineLayout failed");
        return nullptr;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, CustomSpvState::kDescPoolSets * 3u};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, CustomSpvState::kDescPoolSets};
    VkDescriptorPoolCreateInfo dpci{};
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = CustomSpvState::kDescPoolSets;
    dpci.poolSizeCount = 2u;
    dpci.pPoolSizes = poolSizes;
    if (st->vk.createDescriptorPool(view.device, &dpci, nullptr, &st->descPool) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkCreateDescriptorPool failed");
        return nullptr;
    }

    VkDescriptorSetLayout layouts[CustomSpvState::kDescPoolSets];
    for (uint32_t i = 0; i < CustomSpvState::kDescPoolSets; ++i) layouts[i] = st->descSetLayout;
    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = st->descPool;
    dsai.descriptorSetCount = CustomSpvState::kDescPoolSets;
    dsai.pSetLayouts = layouts;
    if (st->vk.allocateDescriptorSets(view.device, &dsai, st->descSets) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkAllocateDescriptorSets failed");
        return nullptr;
    }

    if (!create_helper_pipe(*st, build_extract_depth_spv(), st->extractDepthMod, st->extractDepthPipe) ||
        !create_helper_pipe(*st, build_restore_depth_spv(), st->restoreDepthMod, st->restoreDepthPipe)) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V depth helper pipeline creation failed");
        return nullptr;
    }

    VkCommandBufferAllocateInfo cbai{};
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = view.cmdPool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1u;
    if (st->vk.allocateCommandBuffers(view.device, &cbai, &st->standaloneCmdBuf) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkAllocateCommandBuffers failed");
        return nullptr;
    }

    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (st->vk.createFence(view.device, &fci, nullptr, &st->standaloneFence) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkCreateFence failed");
        return nullptr;
    }

    if (!st->ensureBuf(
            st->bufUbo,
            static_cast<VkDeviceSize>(CustomSpvState::kDescPoolSets) * CustomSpvState::kUboStride,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            true)) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V UBO buffer allocation failed");
        return nullptr;
    }

    st->initialized = true;
    CustomSpvState* ptr = st.get();
    g_custom_spv_states[ctx] = std::move(st);
    return ptr;
}

int32_t register_custom_spv_locked(
    FujiVkContext* ctx,
    CustomSpvState& st,
    const uint8_t* spirv_bytes,
    size_t spirv_byte_len,
    const char* entry_point,
    uint32_t push_const_bytes,
    CachedPipelineEntry** out_entry) {
    if (push_const_bytes > FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V pushConstBytes exceeds 128-byte limit");
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    const char* ep = (entry_point && entry_point[0] != '\0') ? entry_point : "main";
    char vErr[256] = {};
    int32_t vrc = fuji_vk_validate_custom_spv(spirv_bytes, spirv_byte_len, ep, vErr, sizeof(vErr));
    if (vrc != FUJI_VK_OK) {
        fuji_vk_internal_set_error(ctx, vErr);
        return vrc;
    }

    const uint32_t alignedPush = (push_const_bytes == 0u) ? 0u : ((push_const_bytes + 3u) & ~3u);
    const Sha256Digest sha = compute_sha256(spirv_bytes, spirv_byte_len);

    std::string key;
    key.reserve(32u + 1u + std::strlen(ep) + 16u);
    key.append(reinterpret_cast<const char*>(sha.bytes), 32u);
    key.push_back('|');
    key.append(ep);
    key.push_back('|');
    key.append(std::to_string(alignedPush));

    auto it = st.lruByKey.find(key);
    if (it != st.lruByKey.end()) {
        st.cacheHits++;
        st.lruList.splice(st.lruList.begin(), st.lruList, it->second);
        if (out_entry) *out_entry = &(*it->second);
        return FUJI_VK_OK;
    }

    st.cacheMisses++;

    /* Copy into aligned uint32_t word buffer for vkCreateShaderModule */
    const size_t wordCount = spirv_byte_len / 4u;
    std::vector<uint32_t> words(wordCount);
    for (size_t i = 0; i < wordCount; ++i) {
        words[i] = read_u32_le(spirv_bytes + i * 4u);
    }

    VkShaderModule mod = nullptr;
    VkPipelineLayout layout = nullptr;
    VkPipeline pipe = nullptr;

    VkShaderModuleCreateInfo smci{};
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = spirv_byte_len;
    smci.pCode = words.data();
    VkResult r = st.vk.createShaderModule(st.view.device, &smci, nullptr, &mod);
    if (r != VK_SUCCESS || !mod) {
        std::string msg = "Custom SPIR-V vkCreateShaderModule failed (VkResult=" + std::to_string(r) + ")";
        fuji_vk_internal_set_error(ctx, msg.c_str());
        return FUJI_VK_ERR_CUSTOM_SPV_COMPILE;
    }

    VkPushConstantRange pcRange{};
    pcRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRange.offset = 0u;
    pcRange.size = alignedPush;

    VkPipelineLayoutCreateInfo plci{};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1u;
    plci.pSetLayouts = &st.descSetLayout;
    if (alignedPush > 0u) {
        plci.pushConstantRangeCount = 1u;
        plci.pPushConstantRanges = &pcRange;
    }
    r = st.vk.createPipelineLayout(st.view.device, &plci, nullptr, &layout);
    if (r != VK_SUCCESS || !layout) {
        st.vk.destroyShaderModule(st.view.device, mod, nullptr);
        std::string msg = "Custom SPIR-V vkCreatePipelineLayout failed (VkResult=" + std::to_string(r) + ")";
        fuji_vk_internal_set_error(ctx, msg.c_str());
        return FUJI_VK_ERR_CUSTOM_SPV_COMPILE;
    }

    VkComputePipelineCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = mod;
    cpci.stage.pName = ep;
    cpci.layout = layout;
    r = st.vk.createComputePipelines(st.view.device, st.view.pipelineCache, 1u, &cpci, nullptr, &pipe);
    if (r != VK_SUCCESS || !pipe) {
        st.vk.destroyPipelineLayout(st.view.device, layout, nullptr);
        st.vk.destroyShaderModule(st.view.device, mod, nullptr);
        std::string msg = "Custom SPIR-V vkCreateComputePipelines failed (VkResult=" + std::to_string(r) + ")";
        fuji_vk_internal_set_error(ctx, msg.c_str());
        return FUJI_VK_ERR_CUSTOM_SPV_COMPILE;
    }

    fuji_vk_internal_mark_pipeline_cache_dirty(ctx);

    /* Evict least-recently-used entry if at 32-entry cap */
    if (st.lruList.size() >= FUJI_VK_CUSTOM_SPV_LRU_CAPACITY) {
        if (st.vk.deviceWaitIdle) st.vk.deviceWaitIdle(st.view.device);
        auto& oldest = st.lruList.back();
        st.lruByKey.erase(oldest.cacheKey);
        st.lruByHandle.erase(oldest.handle);
        st.destroyEntry(oldest);
        st.lruList.pop_back();
        st.cacheEvictions++;
    }

    CachedPipelineEntry entry{};
    entry.cacheKey = key;
    entry.handle = st.nextHandle++;
    entry.alignedPushBytes = alignedPush;
    entry.shaderModule = mod;
    entry.pipelineLayout = layout;
    entry.pipeline = pipe;

    st.lruList.push_front(std::move(entry));
    auto frontIt = st.lruList.begin();
    st.lruByKey[frontIt->cacheKey] = frontIt;
    st.lruByHandle[frontIt->handle] = frontIt;
    if (out_entry) *out_entry = &(*frontIt);
    return FUJI_VK_OK;
}

} /* namespace */

/* Internal lifecycle & pipeline hooks called from fuji_vulkan_optics.cpp (inside extern "C" block) */
extern "C" {

void fuji_vk_custom_spv_destroy_for_context(FujiVkContext* ctx) {
    if (!ctx) return;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    g_custom_spv_states.erase(ctx);
}

bool fuji_vk_custom_spv_has_stage(FujiVkContext* ctx, uint32_t stage) {
    if (!ctx) return false;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    auto it = g_custom_spv_states.find(ctx);
    if (it == g_custom_spv_states.end() || !it->second || !it->second->initialized) return false;
    for (const auto& s : it->second->activeStages) {
        if (s.stage == stage) return true;
    }
    return false;
}

bool fuji_vk_custom_spv_prepare_stage(
    FujiVkContext* ctx,
    uint32_t stage,
    VkBuffer inputBuf,
    uint32_t width,
    uint32_t height,
    VkBuffer* outOutputBuf) {
    if (!ctx || !inputBuf || width == 0u || height == 0u || !outOutputBuf) return false;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    auto it = g_custom_spv_states.find(ctx);
    if (it == g_custom_spv_states.end() || !it->second || !it->second->initialized) return false;
    CustomSpvState& st = *it->second;

    std::vector<ActiveStageBinding*> matching;
    for (auto& s : st.activeStages) {
        if (s.stage == stage) matching.push_back(&s);
    }
    if (matching.empty()) {
        *outOutputBuf = inputBuf;
        return true;
    }

    const size_t pixels = static_cast<size_t>(width) * height;
    const VkDeviceSize rgbaBytes = static_cast<VkDeviceSize>(pixels) * 16ULL;
    const VkDeviceSize depthBytes = static_cast<VkDeviceSize>(pixels) * 4ULL;
    const VkFlags ssboFlags = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    if (!st.ensureBuf(st.bufPing, rgbaBytes, ssboFlags, false) ||
        !st.ensureBuf(st.bufPong, rgbaBytes, ssboFlags, false) ||
        !st.ensureBuf(st.bufPreOut, rgbaBytes, ssboFlags, false) ||
        !st.ensureBuf(st.bufDepth, depthBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false)) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V DEVICE_LOCAL ping-pong buffer allocation failed");
        return false;
    }

    const uint32_t extractDs = (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) ? 0u : 10u;
    st.writeUboSlot(extractDs, width, height, 0.0f, 0.0f, 0.0f, 0u, 0u, nullptr, 0u);
    st.updateDescSet(extractDs, inputBuf, rgbaBytes, st.bufPing.buffer, rgbaBytes, st.bufDepth.buffer, depthBytes, extractDs);

    VkBuffer curSrc = inputBuf;
    VkBuffer curDst = st.bufPing.buffer;
    for (size_t i = 0; i < matching.size(); ++i) {
        ActiveStageBinding* b = matching[i];
        /* Assign unique descriptor set & UBO slot per active stage across PRE and POST (slots 1..8) */
        const uint32_t dsIdx = b->descIndex;
        st.writeUboSlot(
            dsIdx, width, height,
            b->focusM, b->fNumber, b->focalMm,
            b->frameIndex, b->flags,
            b->uniformData, b->uniformBytes);
        st.updateDescSet(dsIdx, curSrc, rgbaBytes, curDst, rgbaBytes, st.bufDepth.buffer, depthBytes, dsIdx);
        curSrc = curDst;
        curDst = (curDst == st.bufPing.buffer) ? st.bufPong.buffer : st.bufPing.buffer;
    }

    if (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) {
        /* Restore original metric depth into .w so downstream optics_warp + pupil_bokeh preserve depth! */
        st.writeUboSlot(9u, width, height, 0.0f, 0.0f, 0.0f, 0u, 0u, nullptr, 0u);
        st.updateDescSet(9u, curSrc, rgbaBytes, st.bufPreOut.buffer, rgbaBytes, st.bufDepth.buffer, depthBytes, 9u);
        st.lastPreOutputBuffer = st.bufPreOut.buffer;
        st.prePreparedWidth = width;
        st.prePreparedHeight = height;
        *outOutputBuf = st.bufPreOut.buffer;
    } else {
        st.lastPostFinalBuffer = curSrc;
        st.postPreparedWidth = width;
        st.postPreparedHeight = height;
        *outOutputBuf = curSrc;
    }
    return true;
}

void fuji_vk_custom_spv_record_stage(
    FujiVkContext* ctx,
    VkCommandBuffer cmdBuf,
    uint32_t stage,
    VkBuffer copyBackDstIfPost) {
    if (!ctx || !cmdBuf) return;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    auto it = g_custom_spv_states.find(ctx);
    if (it == g_custom_spv_states.end() || !it->second || !it->second->initialized) return;
    CustomSpvState& st = *it->second;

    std::vector<const ActiveStageBinding*> matching;
    for (const auto& s : st.activeStages) {
        if (s.stage == stage) matching.push_back(&s);
    }
    if (matching.empty()) return;

    const uint32_t w = (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) ? st.prePreparedWidth : st.postPreparedWidth;
    const uint32_t h = (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) ? st.prePreparedHeight : st.postPreparedHeight;
    const uint32_t pixels = w * h;
    const uint32_t linearGroups = (pixels + 63u) / 64u;

    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    /* 1. Extract depth (.w -> binding 2 float[]) on GPU */
    const uint32_t extractDs = (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) ? 0u : 10u;
    st.vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st.extractDepthPipe);
    st.vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st.helperPipeLayout, 0u, 1u, &st.descSets[extractDs], 0u, nullptr);
    st.vk.cmdDispatch(cmdBuf, linearGroups, 1u, 1u);
    st.vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 1u, &mb, 0u, nullptr, 0u, nullptr);

    /* 2. Execute each plugin custom SPIR-V pass in stack order */
    for (const ActiveStageBinding* b : matching) {
        st.vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, b->pipeline);
        st.vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, b->pipelineLayout, 0u, 1u, &st.descSets[b->descIndex], 0u, nullptr);
        if (b->alignedPushBytes > 0u) {
            st.vk.cmdPushConstants(cmdBuf, b->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0u, b->alignedPushBytes, b->pushConstants);
        }
        const uint32_t gx = (w + b->workgroupSizeX - 1u) / b->workgroupSizeX;
        const uint32_t gy = (h + b->workgroupSizeY - 1u) / b->workgroupSizeY;
        st.vk.cmdDispatch(cmdBuf, gx, gy, 1u);
        st.vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 1u, &mb, 0u, nullptr, 0u, nullptr);
    }

    if (stage == FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER) {
        /* 3. Restore original depth (.w) into st.bufPreOut for downstream optics_warp + pupil_bokeh */
        st.vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st.restoreDepthPipe);
        st.vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st.helperPipeLayout, 0u, 1u, &st.descSets[9u], 0u, nullptr);
        st.vk.cmdDispatch(cmdBuf, linearGroups, 1u, 1u);
        st.vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 1u, &mb, 0u, nullptr, 0u, nullptr);
    } else if (copyBackDstIfPost && st.lastPostFinalBuffer && st.lastPostFinalBuffer != copyBackDstIfPost) {
        /* 3. Copy final POST_OPTICS_RGB result back into ctx->bufOut on GPU */
        VkMemoryBarrier toTransfer{};
        toTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        st.vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 1u, &toTransfer, 0u, nullptr, 0u, nullptr);

        VkBufferCopy region{};
        region.srcOffset = 0ULL;
        region.dstOffset = 0ULL;
        region.size = static_cast<VkDeviceSize>(pixels) * 16ULL;
        st.vk.cmdCopyBuffer(cmdBuf, st.lastPostFinalBuffer, copyBackDstIfPost, 1u, &region);

        constexpr VkFlags kVkAccessHostReadBit = 0x00002000u;
        constexpr VkFlags kVkPipelineStageHostBit = 0x00004000u;
        VkMemoryBarrier fromTransfer{};
        fromTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        fromTransfer.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        fromTransfer.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | kVkAccessHostReadBit;
        st.vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | kVkPipelineStageHostBit, 0u, 1u, &fromTransfer, 0u, nullptr, 0u, nullptr);
    }
}

FUJI_VK_EXPORT int32_t fuji_vk_validate_custom_spv(
    const uint8_t* spirv_bytes,
    size_t         spirv_byte_len,
    const char*    entry_point,
    char*          err_buf,
    size_t         err_buf_len) {
    if (!spirv_bytes || spirv_byte_len < FUJI_VK_CUSTOM_SPV_MIN_BYTES) {
        write_err(err_buf, err_buf_len, "SPIR-V buffer is null or smaller than 20-byte header (%zu bytes)", spirv_byte_len);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    if (spirv_byte_len > FUJI_VK_CUSTOM_SPV_MAX_BYTES) {
        write_err(err_buf, err_buf_len, "SPIR-V size %zu exceeds 256 KiB limit", spirv_byte_len);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    if ((spirv_byte_len & 3u) != 0u) {
        write_err(err_buf, err_buf_len, "SPIR-V byte length %zu is not 4-byte aligned", spirv_byte_len);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }

    const size_t wordCount = spirv_byte_len / 4u;
    const uint32_t magic = read_u32_le(spirv_bytes);
    if (magic != 0x07230203u) {
        write_err(err_buf, err_buf_len, "Invalid SPIR-V magic 0x%08x (expected 0x07230203)", magic);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    const uint32_t bound = read_u32_le(spirv_bytes + 12u);
    const uint32_t schema = read_u32_le(spirv_bytes + 16u);
    if (bound == 0u || schema != 0u) {
        write_err(err_buf, err_buf_len, "Invalid SPIR-V header (bound=%u, schema=%u)", bound, schema);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }

    const char* expectedEp = (entry_point && entry_point[0] != '\0') ? entry_point : "main";
    bool foundShaderCap = false;
    bool foundComputeEntry = false;
    bool foundMatchingEp = false;

    size_t idx = 5u;
    while (idx < wordCount) {
        const uint32_t insn = read_u32_le(spirv_bytes + idx * 4u);
        const uint32_t wc = insn >> 16u;
        const uint32_t op = insn & 0xFFFFu;
        if (wc == 0u || idx + wc > wordCount) {
            write_err(err_buf, err_buf_len, "Truncated/corrupt SPIR-V instruction at word %zu (wc=%u, total=%zu)", idx, wc, wordCount);
            return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
        }
        if (op == 17u && wc >= 2u) { /* OpCapability */
            const uint32_t cap = read_u32_le(spirv_bytes + (idx + 1u) * 4u);
            if (cap == 1u) foundShaderCap = true; /* Capability::Shader */
        } else if (op == 15u && wc >= 4u) { /* OpEntryPoint */
            const uint32_t execModel = read_u32_le(spirv_bytes + (idx + 1u) * 4u);
            if (execModel == 5u) { /* ExecutionModel::GLCompute */
                foundComputeEntry = true;
                /* Extract null-terminated UTF-8 entry point string starting at word idx + 3 */
                const size_t maxStrBytes = (wc - 3u) * 4u;
                const uint8_t* strPtr = spirv_bytes + (idx + 3u) * 4u;
                size_t slen = 0u;
                while (slen < maxStrBytes && strPtr[slen] != 0u) slen++;
                if (slen < maxStrBytes &&
                    slen == std::strlen(expectedEp) &&
                    std::memcmp(strPtr, expectedEp, slen) == 0) {
                    foundMatchingEp = true;
                }
            }
        }
        idx += wc;
    }

    if (!foundShaderCap) {
        write_err(err_buf, err_buf_len, "SPIR-V module missing OpCapability Shader");
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    if (!foundComputeEntry) {
        write_err(err_buf, err_buf_len, "SPIR-V module missing GLCompute OpEntryPoint");
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    if (!foundMatchingEp) {
        write_err(err_buf, err_buf_len, "SPIR-V module has no GLCompute entry point named '%s'", expectedEp);
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_register_custom_spv(
    FujiVkContext* ctx,
    const uint8_t* spirv_bytes,
    size_t         spirv_byte_len,
    const char*    entry_point,
    uint32_t       push_const_bytes,
    uint64_t*      out_handle) {
    if (!ctx) return FUJI_VK_ERR_INVALID_ARG;
    if (out_handle) *out_handle = 0ULL;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    CustomSpvState* st = get_or_init_state_locked(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;

    CachedPipelineEntry* entry = nullptr;
    int32_t rc = register_custom_spv_locked(ctx, *st, spirv_bytes, spirv_byte_len, entry_point, push_const_bytes, &entry);
    if (rc != FUJI_VK_OK) return rc;
    if (out_handle && entry) *out_handle = entry->handle;
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_set_custom_spv_stages(
    FujiVkContext*                  ctx,
    const FujiVkCustomSpvStageSpec* stages,
    uint32_t                        stage_count) {
    if (!ctx) return FUJI_VK_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    if (stage_count == 0u || !stages) {
        auto it = g_custom_spv_states.find(ctx);
        if (it != g_custom_spv_states.end() && it->second) {
            it->second->activeStages.clear();
        }
        return FUJI_VK_OK;
    }
    if (stage_count > FUJI_VK_CUSTOM_SPV_MAX_STAGES) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V stage_count exceeds maximum of 8");
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }

    CustomSpvState* st = get_or_init_state_locked(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;

    std::vector<ActiveStageBinding> prepared;
    prepared.reserve(stage_count);

    for (uint32_t i = 0u; i < stage_count; ++i) {
        const FujiVkCustomSpvStageSpec& spec = stages[i];
        if (spec.stage != FUJI_VK_CUSTOM_SPV_STAGE_PRE_OPTICS_FILTER &&
            spec.stage != FUJI_VK_CUSTOM_SPV_STAGE_POST_OPTICS_RGB) {
            st->activeStages.clear();
            fuji_vk_internal_set_error(ctx, "Invalid custom SPIR-V stage enum");
            return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
        }
        if (spec.uniformBytes > FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES ||
            spec.pushConstBytes > FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES) {
            st->activeStages.clear();
            fuji_vk_internal_set_error(ctx, "Custom SPIR-V uniformBytes (>4096) or pushConstBytes (>128) out of range");
            return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
        }
        CachedPipelineEntry* entry = nullptr;
        int32_t rc = register_custom_spv_locked(
            ctx, *st, spec.spirvBytes, spec.spirvByteLen, spec.entryPoint, spec.pushConstBytes, &entry);
        if (rc != FUJI_VK_OK || !entry) {
            st->activeStages.clear();
            return rc;
        }
        ActiveStageBinding b{};
        b.stage = spec.stage;
        b.workgroupSizeX = (spec.workgroupSizeX >= 1u && spec.workgroupSizeX <= 64u) ? spec.workgroupSizeX : 16u;
        b.workgroupSizeY = (spec.workgroupSizeY >= 1u && spec.workgroupSizeY <= 64u) ? spec.workgroupSizeY : 16u;
        b.handle = entry->handle;
        b.pipeline = entry->pipeline;
        b.pipelineLayout = entry->pipelineLayout;
        b.alignedPushBytes = entry->alignedPushBytes;
        if (spec.pushConstBytes > 0u && spec.pushConstants) {
            std::memcpy(b.pushConstants, spec.pushConstants, spec.pushConstBytes);
        }
        b.uniformBytes = spec.uniformBytes;
        if (spec.uniformBytes > 0u && spec.uniformData) {
            std::memcpy(b.uniformData, spec.uniformData, spec.uniformBytes);
        }
        b.focusM = spec.focusM;
        b.fNumber = spec.fNumber;
        b.focalMm = spec.focalMm;
        b.frameIndex = spec.frameIndex;
        b.flags = spec.flags;
        b.descIndex = 1u + i; /* slots 1..8 */
        prepared.push_back(b);
    }

    st->activeStages = std::move(prepared);
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_custom_spv_cache_stats(
    const FujiVkContext*       ctx,
    FujiVkCustomSpvCacheStats* out_stats) {
    if (!ctx || !out_stats) return FUJI_VK_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    out_stats->capacity = FUJI_VK_CUSTOM_SPV_LRU_CAPACITY;
    out_stats->entries = 0u;
    out_stats->hits = 0ULL;
    out_stats->misses = 0ULL;
    out_stats->evictions = 0ULL;
    auto it = g_custom_spv_states.find(ctx);
    if (it != g_custom_spv_states.end() && it->second) {
        out_stats->entries = static_cast<uint32_t>(it->second->lruList.size());
        out_stats->hits = it->second->cacheHits;
        out_stats->misses = it->second->cacheMisses;
        out_stats->evictions = it->second->cacheEvictions;
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_run_custom_spv_pass(
    FujiVkContext*                  ctx,
    const FujiVkCustomSpvStageSpec* stage,
    const float*                    in_rgba_depth,
    float*                          out_rgba,
    uint32_t                        width,
    uint32_t                        height,
    double*                         out_gpu_ms) {
    if (!ctx || !stage || !in_rgba_depth || !out_rgba || width == 0u || height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (stage->uniformBytes > FUJI_VK_CUSTOM_SPV_MAX_UNIFORM_BYTES ||
        stage->pushConstBytes > FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V uniformBytes or pushConstBytes exceeds limit");
        return FUJI_VK_ERR_CUSTOM_SPV_INVALID;
    }

    std::lock_guard<std::mutex> lk(g_custom_spv_mutex);
    CustomSpvState* st = get_or_init_state_locked(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;

    CachedPipelineEntry* entry = nullptr;
    int32_t rc = register_custom_spv_locked(
        ctx, *st, stage->spirvBytes, stage->spirvByteLen, stage->entryPoint, stage->pushConstBytes, &entry);
    if (rc != FUJI_VK_OK || !entry) return rc;

    const size_t pixels = static_cast<size_t>(width) * height;
    const VkDeviceSize rgbaBytes = static_cast<VkDeviceSize>(pixels) * 16ULL;
    const VkDeviceSize depthBytes = static_cast<VkDeviceSize>(pixels) * 4ULL;
    if (!st->ensureBuf(st->bufHostIn, rgbaBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true) ||
        !st->ensureBuf(st->bufHostOut, rgbaBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true) ||
        !st->ensureBuf(st->bufDepth, depthBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false)) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V standalone buffer allocation failed");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    auto t0 = std::chrono::steady_clock::now();
    std::memcpy(st->bufHostIn.mapped, in_rgba_depth, rgbaBytes);

    /* Extract depth (.w -> bufDepth) using slot 0, then execute custom pass using slot 11 */
    st->writeUboSlot(0u, width, height, 0.0f, 0.0f, 0.0f, 0u, 0u, nullptr, 0u);
    st->updateDescSet(0u, st->bufHostIn.buffer, rgbaBytes, st->bufHostOut.buffer, rgbaBytes, st->bufDepth.buffer, depthBytes, 0u);

    st->writeUboSlot(
        11u, width, height,
        stage->focusM, stage->fNumber, stage->focalMm,
        stage->frameIndex, stage->flags,
        stage->uniformData, stage->uniformBytes);
    st->updateDescSet(11u, st->bufHostIn.buffer, rgbaBytes, st->bufHostOut.buffer, rgbaBytes, st->bufDepth.buffer, depthBytes, 11u);

    st->vk.resetCommandBuffer(st->standaloneCmdBuf, 0u);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (st->vk.beginCommandBuffer(st->standaloneCmdBuf, &cbbi) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkBeginCommandBuffer failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    const uint32_t linearGroups = static_cast<uint32_t>((pixels + 63u) / 64u);
    st->vk.cmdBindPipeline(st->standaloneCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->extractDepthPipe);
    st->vk.cmdBindDescriptorSets(st->standaloneCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->helperPipeLayout, 0u, 1u, &st->descSets[0u], 0u, nullptr);
    st->vk.cmdDispatch(st->standaloneCmdBuf, linearGroups, 1u, 1u);
    st->vk.cmdPipelineBarrier(st->standaloneCmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 1u, &mb, 0u, nullptr, 0u, nullptr);

    st->vk.cmdBindPipeline(st->standaloneCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, entry->pipeline);
    st->vk.cmdBindDescriptorSets(st->standaloneCmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, entry->pipelineLayout, 0u, 1u, &st->descSets[11u], 0u, nullptr);
    if (entry->alignedPushBytes > 0u) {
        uint8_t pushPad[FUJI_VK_CUSTOM_SPV_MAX_PUSH_BYTES] = {};
        if (stage->pushConstBytes > 0u && stage->pushConstants) {
            std::memcpy(pushPad, stage->pushConstants, stage->pushConstBytes);
        }
        st->vk.cmdPushConstants(st->standaloneCmdBuf, entry->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0u, entry->alignedPushBytes, pushPad);
    }
    const uint32_t wgX = (stage->workgroupSizeX >= 1u && stage->workgroupSizeX <= 64u) ? stage->workgroupSizeX : 16u;
    const uint32_t wgY = (stage->workgroupSizeY >= 1u && stage->workgroupSizeY <= 64u) ? stage->workgroupSizeY : 16u;
    st->vk.cmdDispatch(st->standaloneCmdBuf, (width + wgX - 1u) / wgX, (height + wgY - 1u) / wgY, 1u);

    if (st->vk.endCommandBuffer(st->standaloneCmdBuf) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V vkEndCommandBuffer failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    st->vk.resetFences(st->view.device, 1u, &st->standaloneFence);
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1u;
    si.pCommandBuffers = &st->standaloneCmdBuf;
    if (st->vk.queueSubmit(st->view.computeQueue, 1u, &si, st->standaloneFence) != VK_SUCCESS ||
        st->vk.waitForFences(st->view.device, 1u, &st->standaloneFence, VK_TRUE, 10000000000ULL) != VK_SUCCESS) {
        fuji_vk_internal_set_error(ctx, "Custom SPIR-V queue submit / wait failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    std::memcpy(out_rgba, st->bufHostOut.mapped, rgbaBytes);
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

} /* extern "C" */
