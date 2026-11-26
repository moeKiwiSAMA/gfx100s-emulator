/*
 * Disk-persistent VkPipelineCache validation, self-contained SHA-256, atomic
 * persistence, and high-water-mark Vulkan buffer sizing helpers.
 * Zero extra DLL dependencies (uses only standard C++17 + POSIX / Win32 Kernel32).
 */

#include "fuji_vulkan_pipeline_cache.h"
#include "fuji_vulkan_optics.h"
#include "fuji_vulkan_shaders_spirv.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#  include <direct.h>
#  include <sys/stat.h>
#else
#  include <sys/stat.h>
#  include <unistd.h>
#endif

namespace {

/* Compact, zero-dependency FIPS 180-4 SHA-256 implementation */
struct Sha256 {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t  data[64];
    size_t   datalen;

    static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32u - n)); }

    void init() {
        state[0] = 0x6a09e667u; state[1] = 0xbb67ae85u; state[2] = 0x3c6ef372u; state[3] = 0xa54ff53au;
        state[4] = 0x510e527fu; state[5] = 0x9b05688cu; state[6] = 0x1f83d9abu; state[7] = 0x5be0cd19u;
        bitlen = 0ULL;
        datalen = 0u;
    }

    void transform() {
        static const uint32_t K[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
        };
        uint32_t m[64];
        for (int i = 0; i < 16; ++i) {
            m[i] = (static_cast<uint32_t>(data[i * 4]) << 24) |
                   (static_cast<uint32_t>(data[i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(data[i * 4 + 2]) << 8) |
                   (static_cast<uint32_t>(data[i * 4 + 3]));
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(m[i - 15], 7) ^ rotr(m[i - 15], 18) ^ (m[i - 15] >> 3);
            uint32_t s1 = rotr(m[i - 2], 17) ^ rotr(m[i - 2], 19) ^ (m[i - 2] >> 10);
            m[i] = m[i - 16] + s0 + m[i - 7] + s1;
        }
        uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
        uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = h + S1 + ch + K[i] + m[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            h = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }

    void update(const void* ptr, size_t len) {
        const uint8_t* bytes = static_cast<const uint8_t*>(ptr);
        for (size_t i = 0; i < len; ++i) {
            data[datalen++] = bytes[i];
            if (datalen == 64u) {
                transform();
                bitlen += 512ULL;
                datalen = 0u;
            }
        }
    }

    void finish(uint8_t out[32]) {
        uint64_t totalBits = bitlen + static_cast<uint64_t>(datalen) * 8ULL;
        data[datalen++] = 0x80u;
        if (datalen > 56u) {
            while (datalen < 64u) data[datalen++] = 0u;
            transform();
            datalen = 0u;
        }
        while (datalen < 56u) data[datalen++] = 0u;
        for (int i = 7; i >= 0; --i) {
            data[datalen++] = static_cast<uint8_t>((totalBits >> (i * 8)) & 0xFFu);
        }
        transform();
        for (int i = 0; i < 8; ++i) {
            out[i * 4 + 0] = static_cast<uint8_t>((state[i] >> 24) & 0xFFu);
            out[i * 4 + 1] = static_cast<uint8_t>((state[i] >> 16) & 0xFFu);
            out[i * 4 + 2] = static_cast<uint8_t>((state[i] >> 8) & 0xFFu);
            out[i * 4 + 3] = static_cast<uint8_t>(state[i] & 0xFFu);
        }
    }
};

struct FvpcDiskHeader {
    char     magic[4];                       /* "FVPC" */
    uint32_t headerVersion;                  /* 1 */
    uint32_t abiVersion;
    uint32_t vendorId;
    uint32_t deviceId;
    uint32_t driverVersion;
    uint8_t  pipelineCacheUUID[VK_UUID_SIZE];
    uint8_t  spirvSha256[32];
    uint64_t payloadSize;
    uint8_t  payloadSha256[32];
};

static_assert(sizeof(FvpcDiskHeader) == 112, "FvpcDiskHeader must be 112 bytes packed/natural");

constexpr uint32_t kFvpcHeaderVersion = 1u;
constexpr size_t   kMaxCacheBlobBytes = 64u * 1024u * 1024u;

bool is_directory_path(const std::string& p) {
    if (p.empty()) return false;
    char last = p.back();
    if (last == '/' || last == '\\') return true;
#if defined(_WIN32)
    DWORD attr = GetFileAttributesA(p.c_str());
    return (attr != INVALID_FILE_ATTRIBUTES) && ((attr & FILE_ATTRIBUTE_DIRECTORY) != 0);
#else
    struct stat st{};
    return (::stat(p.c_str(), &st) == 0) && S_ISDIR(st.st_mode);
#endif
}

void ensure_parent_dirs(const std::string& file_path) {
    size_t pos = 0;
    while ((pos = file_path.find_first_of("/\\", pos)) != std::string::npos) {
        if (pos > 0) {
            std::string sub = file_path.substr(0, pos);
#if defined(_WIN32)
            if (!(sub.size() == 2 && sub[1] == ':')) {
                _mkdir(sub.c_str());
            }
#else
            ::mkdir(sub.c_str(), 0755);
#endif
        }
        ++pos;
    }
}

uint32_t current_pid() {
#if defined(_WIN32)
    return static_cast<uint32_t>(GetCurrentProcessId());
#else
    return static_cast<uint32_t>(::getpid());
#endif
}

std::string default_cache_filename(uint32_t vendorId, uint32_t deviceId) {
    char buf[96];
    std::snprintf(buf, sizeof(buf), "vulkan-pipeline-%04x-%04x.bin", vendorId, deviceId);
    return std::string(buf);
}

std::string join_path(const std::string& dir, const std::string& file) {
    if (dir.empty()) return file;
    char last = dir.back();
    if (last == '/' || last == '\\') return dir + file;
#if defined(_WIN32)
    return dir + "\\" + file;
#else
    return dir + "/" + file;
#endif
}

} /* namespace */

void fuji_vk_compute_spirv_sha256(uint8_t out_sha256[32]) {
    Sha256 h;
    h.init();
    h.update(FUJI_SPIRV_OPTICS_WARP, sizeof(FUJI_SPIRV_OPTICS_WARP));
    h.update(FUJI_SPIRV_PUPIL_BOKEH, sizeof(FUJI_SPIRV_PUPIL_BOKEH));
    h.update(FUJI_SPIRV_CMOS_BAYER, sizeof(FUJI_SPIRV_CMOS_BAYER));
    h.update(FUJI_SPIRV_OPTICAL_FILTER_STACK, sizeof(FUJI_SPIRV_OPTICAL_FILTER_STACK));
    h.update(FUJI_SPIRV_ISP_DEMOSAIC, sizeof(FUJI_SPIRV_ISP_DEMOSAIC));
    h.update(FUJI_SPIRV_ISP_PEAKING, sizeof(FUJI_SPIRV_ISP_PEAKING));
    h.update(FUJI_SPIRV_BOKEH_DOWNSAMPLE, sizeof(FUJI_SPIRV_BOKEH_DOWNSAMPLE));
    h.finish(out_sha256);
}

std::string fuji_vk_resolve_pipeline_cache_path(
    uint32_t vendorId,
    uint32_t deviceId,
    const char* explicit_path,
    const std::string& global_override_path) {
    const std::string fname = default_cache_filename(vendorId, deviceId);

    auto normalize_candidate = [&](const std::string& raw) -> std::string {
        if (raw.empty()) return {};
        if (is_directory_path(raw)) {
            return join_path(raw, fname);
        }
        return raw;
    };

    if (explicit_path && explicit_path[0] != '\0') {
        return normalize_candidate(explicit_path);
    }
    if (!global_override_path.empty()) {
        return normalize_candidate(global_override_path);
    }
    if (const char* env = std::getenv("FUJI_VULKAN_PIPELINE_CACHE_PATH"); env && env[0] != '\0') {
        return normalize_candidate(env);
    }

#if defined(_WIN32)
    if (const char* lad = std::getenv("LOCALAPPDATA"); lad && lad[0] != '\0') {
        return join_path(join_path(lad, "fuji-emulator\\vulkan-cache"), fname);
    }
    if (const char* tmp = std::getenv("TEMP"); tmp && tmp[0] != '\0') {
        return join_path(join_path(tmp, "fuji-vulkan-cache"), fname);
    }
    return fname;
#else
    if (const char* xdg = std::getenv("XDG_CACHE_HOME"); xdg && xdg[0] != '\0') {
        return join_path(join_path(xdg, "fuji-emulator/vulkan-cache"), fname);
    }
    if (const char* home = std::getenv("HOME"); home && home[0] != '\0') {
        return join_path(join_path(home, ".cache/fuji-emulator/vulkan-cache"), fname);
    }
    return join_path("/tmp/fuji-vulkan-cache", fname);
#endif
}

bool fuji_vk_load_pipeline_cache_blob(
    const std::string& path,
    const FujiVkPipelineCacheIdentity& expected,
    std::vector<uint8_t>& out_blob) {
    out_blob.clear();
    if (path.empty()) return false;

    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;

    FvpcDiskHeader hdr{};
    if (std::fread(&hdr, 1, sizeof(hdr), f) != sizeof(hdr)) {
        std::fclose(f);
        return false;
    }
    if (std::memcmp(hdr.magic, "FVPC", 4) != 0 ||
        hdr.headerVersion != kFvpcHeaderVersion ||
        hdr.abiVersion != expected.abiVersion ||
        hdr.vendorId != expected.vendorId ||
        hdr.deviceId != expected.deviceId ||
        hdr.driverVersion != expected.driverVersion ||
        std::memcmp(hdr.pipelineCacheUUID, expected.pipelineCacheUUID, VK_UUID_SIZE) != 0 ||
        std::memcmp(hdr.spirvSha256, expected.spirvSha256, 32) != 0 ||
        hdr.payloadSize == 0ULL ||
        hdr.payloadSize > kMaxCacheBlobBytes) {
        std::fclose(f);
        return false;
    }

    const size_t payloadLen = static_cast<size_t>(hdr.payloadSize);
    std::vector<uint8_t> blob(payloadLen);
    if (std::fread(blob.data(), 1, payloadLen, f) != payloadLen) {
        std::fclose(f);
        return false;
    }
    /* Ensure no trailing garbage bytes */
    int extra = std::fgetc(f);
    std::fclose(f);
    if (extra != EOF) return false;

    uint8_t actualSha[32];
    Sha256 h;
    h.init();
    h.update(blob.data(), blob.size());
    h.finish(actualSha);
    if (std::memcmp(actualSha, hdr.payloadSha256, 32) != 0) {
        return false;
    }

    out_blob.swap(blob);
    return true;
}

bool fuji_vk_save_pipeline_cache_blob(
    const std::string& path,
    const FujiVkPipelineCacheIdentity& id,
    const void* blob_data,
    size_t blob_size) {
    if (path.empty() || !blob_data || blob_size == 0u || blob_size > kMaxCacheBlobBytes) {
        return false;
    }
    ensure_parent_dirs(path);

    FvpcDiskHeader hdr{};
    std::memcpy(hdr.magic, "FVPC", 4);
    hdr.headerVersion = kFvpcHeaderVersion;
    hdr.abiVersion = id.abiVersion;
    hdr.vendorId = id.vendorId;
    hdr.deviceId = id.deviceId;
    hdr.driverVersion = id.driverVersion;
    std::memcpy(hdr.pipelineCacheUUID, id.pipelineCacheUUID, VK_UUID_SIZE);
    std::memcpy(hdr.spirvSha256, id.spirvSha256, 32);
    hdr.payloadSize = static_cast<uint64_t>(blob_size);
    {
        Sha256 h;
        h.init();
        h.update(blob_data, blob_size);
        h.finish(hdr.payloadSha256);
    }

    char tmpSuffix[48];
    std::snprintf(tmpSuffix, sizeof(tmpSuffix), ".tmp.%u", current_pid());
    const std::string tmpPath = path + tmpSuffix;

    std::FILE* f = std::fopen(tmpPath.c_str(), "wb");
    if (!f) return false;

    bool ok = (std::fwrite(&hdr, 1, sizeof(hdr), f) == sizeof(hdr)) &&
              (std::fwrite(blob_data, 1, blob_size, f) == blob_size) &&
              (std::fflush(f) == 0);
    std::fclose(f);
    if (!ok) {
        std::remove(tmpPath.c_str());
        return false;
    }

#if defined(_WIN32)
    if (!MoveFileExA(tmpPath.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        std::remove(tmpPath.c_str());
        return false;
    }
#else
    if (std::rename(tmpPath.c_str(), path.c_str()) != 0) {
        std::remove(tmpPath.c_str());
        return false;
    }
#endif
    return true;
}

VkDeviceSize fuji_vk_hwm_capacity(
    VkDeviceSize current_capacity,
    VkDeviceSize needed_bytes,
    bool& out_should_realloc) {
    if (needed_bytes == 0ULL) needed_bytes = 64ULL;
    constexpr VkDeviceSize kMinAlloc = 4096ULL;
    constexpr VkDeviceSize kShrinkFloorBytes = 64ULL * 1024ULL * 1024ULL;

    if (current_capacity >= needed_bytes) {
        /* Only shrink if >4x oversized AND strictly above 64 MiB (never churn LiveView/EVF buffers) */
        if (current_capacity > kShrinkFloorBytes && current_capacity > needed_bytes * 4ULL) {
            out_should_realloc = true;
            VkDeviceSize grown = needed_bytes + (needed_bytes >> 2); /* 1.25x headroom */
            VkDeviceSize aligned = (std::max<VkDeviceSize>(grown, kMinAlloc) + 4095ULL) & ~4095ULL;
            return aligned;
        }
        out_should_realloc = false;
        return current_capacity;
    }

    out_should_realloc = true;
    VkDeviceSize grown = needed_bytes + (needed_bytes >> 2); /* 1.25x headroom */
    VkDeviceSize aligned = (std::max<VkDeviceSize>(grown, kMinAlloc) + 4095ULL) & ~4095ULL;
    return aligned;
}
